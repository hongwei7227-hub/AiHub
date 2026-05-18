package com.hmdp.ai.rag.ingest;

import com.hmdp.ai.rag.AiMetadataConstants;
import com.hmdp.ai.rag.dto.KnowledgeDoc;
import com.hmdp.ai.rag.dto.ReviewDoc;
import com.hmdp.ai.rag.indexer.ShopProfileDocumentBuilder;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.HasCollectionParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 把 data-prep/output/ 下的 JSONL 灌进 Milvus。
 *
 * 关键设计（与 Plan B M1 / M2 对齐）：
 * - 灌前用 vectorStore.delete(eq(docType, ...)) 把旧业务残留（14 条 Shop / 4 条 Blog 等）清掉，
 *   保留 collection schema 不动（避免 drop 后 Spring AI Bean 不会自动重建 collection 的坑）。
 * - signature_dishes (List&lt;String&gt;) 不写进 Document metadata（招牌菜信息已经在 content 字段里），
 *   避免 Milvus expr 对嵌套 JSON 数组 filter 支持不稳的坑。
 */
@Slf4j
@Service
public class JsonlIngestService {

    private final VectorStore knowledgeVectorStore;
    private final VectorStore shopProfileVectorStore;
    private final VectorStore blogReviewVectorStore;

    private final JsonlReader jsonlReader;
    private final MilvusServiceClient milvusClient;
    private final MilvusVectorStoreProperties milvusProps;
    private final IShopService shopService;

    @Value("${rag.ingest.data-dir}")
    private String dataDir;

    @Value("${rag.ingest.batch-size:100}")
    private int batchSize;

    public JsonlIngestService(@Qualifier("knowledgeVectorStore") VectorStore knowledgeVectorStore,
                              @Qualifier("shopProfileVectorStore") VectorStore shopProfileVectorStore,
                              @Qualifier("blogReviewVectorStore") VectorStore blogReviewVectorStore,
                              JsonlReader jsonlReader,
                              MilvusServiceClient milvusClient,
                              MilvusVectorStoreProperties milvusProps,
                              IShopService shopService) {
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.shopProfileVectorStore = shopProfileVectorStore;
        this.blogReviewVectorStore = blogReviewVectorStore;
        this.jsonlReader = jsonlReader;
        this.milvusClient = milvusClient;
        this.milvusProps = milvusProps;
        this.shopService = shopService;
    }

    // ===== 入口方法 =====

    /**
     * Plan J：评估路径不再读 shop_profile.jsonl，改成读 MySQL tb_shop 全量。
     * 跟业务路径 {@code AiVectorIndexServiceImpl.rebuildAllShopProfiles()} 共用同一画像拼接逻辑
     * ({@link ShopProfileDocumentBuilder#fromShop}), 保证业务-评估同源——
     * 评估 drop+rewrite 后业务侧 ensureInitialized 看到非空跳过，里面装的内容跟业务路径自己灌的字字相同。
     *
     * 玉泉数据保留: 画像本质都是结构化字段拼接(店名+地址+品类+评分),玉泉跟 yf 在 Milvus 召回质量差距不大,
     * 保留全量 1235 让业务-评估完全同源。
     */
    public IngestStat ingestShopProfile() {
        String collectionName = "shop_profile_vector";
        dropAndRecreate(collectionName, shopProfileVectorStore);
        List<Shop> shops = shopService.list();
        log.info("[{}] reading {} shops from MySQL tb_shop", collectionName, shops.size());
        return doIngest(collectionName, shopProfileVectorStore, shops,
                ShopProfileDocumentBuilder::fromShop,
                shop -> String.valueOf(shop.getId()));
    }

    public IngestStat ingestReviews() {
        String collectionName = "blog_review_vector";
        dropAndRecreate(collectionName, blogReviewVectorStore);
        Path file = Path.of(dataDir, "blog_review.jsonl");
        List<ReviewDoc> docs = jsonlReader.readAll(file, ReviewDoc.class);
        return doIngest(collectionName, blogReviewVectorStore, docs, this::toReviewDocument, ReviewDoc::getReviewId);
    }

    public IngestStat ingestKnowledge() {
        String collectionName = "knowledge_vector";
        dropAndRecreate(collectionName, knowledgeVectorStore);
        Path file = Path.of(dataDir, "knowledge_qa.jsonl");
        List<KnowledgeDoc> docs = jsonlReader.readAll(file, KnowledgeDoc.class);
        return doIngest(collectionName, knowledgeVectorStore, docs, this::toKnowledgeDocument,
                d -> String.valueOf(d.getId()));
    }

    // ===== 通用灌库主流程 =====

    private <T> IngestStat doIngest(String collectionName,
                                    VectorStore vectorStore,
                                    List<T> docs,
                                    Function<T, Document> toDocument,
                                    Function<T, String> idExtractor) {
        long start = System.currentTimeMillis();
        int total = docs.size();
        int succeeded = 0;
        int failed = 0;
        List<String> failedIds = new ArrayList<>();
        int processed = 0;

        for (int i = 0; i < total; i += batchSize) {
            int to = Math.min(i + batchSize, total);
            List<T> batch = docs.subList(i, to);
            List<Document> springDocs = new ArrayList<>(batch.size());
            for (T t : batch) {
                Document d = toDocument.apply(t);
                if (d != null) {
                    springDocs.add(d);
                }
            }
            try {
                if (!springDocs.isEmpty()) {
                    vectorStore.add(springDocs);
                }
                succeeded += batch.size();
            } catch (Exception e) {
                log.error("[{}] batch [{}, {}) failed: {}", collectionName, i, to, e.toString());
                failed += batch.size();
                for (T t : batch) {
                    failedIds.add(idExtractor.apply(t));
                }
            }
            processed += batch.size();
            if (processed % 500 == 0 || processed == total) {
                log.info("[{}] progress: {}/{} ({}%)  failed={}", collectionName,
                        processed, total, total == 0 ? 0 : processed * 100 / total, failed);
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("[{}] done: {}/{} succeeded, {} failed, {} ms",
                collectionName, succeeded, total, failed, elapsed);
        return new IngestStat(collectionName, total, succeeded, failed, elapsed, failedIds);
    }

    // ===== DTO -> Spring AI Document =====

    // Plan J: 评估路径不再从 ShopProfileDoc 转换——shop 走 MySQL+ShopProfileDocumentBuilder
    // 原 toShopDocument(ShopProfileDoc) 已删除，DTO ShopProfileDoc 仅保留供数据准备阶段使用

    private Document toReviewDocument(ReviewDoc d) {
        if (d.getContent() == null || d.getContent().isBlank()) {
            return null;
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put(AiMetadataConstants.DOC_TYPE, AiMetadataConstants.DOC_TYPE_BLOG_REVIEW);
        meta.put(AiMetadataConstants.SOURCE_ID, nullToEmpty(d.getReviewId()));
        meta.put(AiMetadataConstants.REVIEW_ID, nullToEmpty(d.getReviewId()));
        meta.put(AiMetadataConstants.SHOP_ID, nullToEmpty(d.getShopId()));
        meta.put(AiMetadataConstants.USER_ID, nullToEmpty(d.getUserId()));
        meta.put(AiMetadataConstants.RATING, defaultDouble(d.getRating()));
        meta.put(AiMetadataConstants.RATING_FLAVOR, defaultDouble(d.getRatingFlavor()));
        meta.put(AiMetadataConstants.RATING_ENV, defaultDouble(d.getRatingEnv()));
        meta.put(AiMetadataConstants.RATING_SERVICE, defaultDouble(d.getRatingService()));
        return new Document(d.getContent(), meta);
    }

    private Document toKnowledgeDocument(KnowledgeDoc d) {
        if (d.getContent() == null || d.getContent().isBlank()) {
            return null;
        }
        Map<String, Object> meta = new HashMap<>();
        meta.put(AiMetadataConstants.DOC_TYPE, AiMetadataConstants.DOC_TYPE_KNOWLEDGE);
        meta.put(AiMetadataConstants.SOURCE_ID, String.valueOf(d.getId()));
        meta.put(AiMetadataConstants.QA_ID, d.getId() == null ? 0 : d.getId());
        meta.put(AiMetadataConstants.TOPIC, nullToEmpty(d.getTopic()));
        meta.put(AiMetadataConstants.TITLE, nullToEmpty(d.getQuestion()));
        return new Document(d.getContent(), meta);
    }

    // ===== 清旧数据（Step 5.0）=====

    /**
     * 用 Milvus 原生 expr 把旧 docType 的数据全部删掉，保留 collection schema 不动。
     *
     * 为什么不用 Spring AI 的 vectorStore.delete(FilterExpression)：
     * Spring AI 1.0.3 的 MilvusFilterExpressionConverter 会把 eq(docType, ...) 转成
     * `docType == "shop_profile"`，但 Milvus 里 docType 是 metadata JSON 内字段，
     * 顶级字段不存在 → delete 命中 0 行（debug 阶段 grep 实测验证）。
     * 改用 Milvus 原生 expr：metadata["docType"] == "shop_profile"
     */
    private void dropAndRecreate(String collectionName, VectorStore vectorStore) {
        try {
            String dbName = milvusProps.getDatabaseName();
            HasCollectionParam.Builder hasParam = HasCollectionParam.newBuilder()
                    .withCollectionName(collectionName);
            if (dbName != null && !dbName.isBlank()) {
                hasParam.withDatabaseName(dbName);
            }
            Boolean exists = milvusClient.hasCollection(hasParam.build()).getData();
            if (Boolean.TRUE.equals(exists)) {
                DropCollectionParam.Builder dropParam = DropCollectionParam.newBuilder()
                        .withCollectionName(collectionName);
                if (dbName != null && !dbName.isBlank()) {
                    dropParam.withDatabaseName(dbName);
                }
                milvusClient.dropCollection(dropParam.build());
                log.info("[{}] dropped existing collection", collectionName);
            }
            // 反射调 Spring AI 包私有 createCollection() 重建 schema + 索引 + load
            Method m = vectorStore.getClass().getDeclaredMethod("createCollection");
            m.setAccessible(true);
            m.invoke(vectorStore);
            log.info("[{}] recreated empty collection via Spring AI createCollection()", collectionName);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to drop+recreate collection " + collectionName, e);
        }
    }

    // ===== 工具 =====

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static double defaultDouble(Double d) {
        return d == null ? 0.0 : d;
    }
}
