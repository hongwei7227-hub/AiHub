package com.cityaihub.ai.rag.retriever;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Plan E：内存倒排索引 + BM25 评分，三个 collection 各自独立索引。
 *
 * <p>启动时（@PostConstruct）从 {@code rag.bm25.data-dir} 读取 Plan A 的 JSONL 文件构建索引：
 * <ul>
 *   <li>{@code shop_profile.jsonl} → shopSearcher（1556 条）
 *   <li>{@code blog_review.jsonl} → reviewSearcher（9538 条）
 *   <li>{@code knowledge_qa.jsonl} → knowledgeSearcher（200 条）
 * </ul>
 *
 * <p>索引存在 {@link ByteBuffersDirectory} 内存中，11294 条 × 平均 200 字 ≈ 2-5 MB，毫秒级搜索。
 *
 * <p>查询用 {@link SmartChineseAnalyzer} 分词 + {@link BM25Similarity} 评分（k1=1.2 b=0.75 默认）。
 *
 * <p>独立于 Milvus / Spring AI VectorStore——不依赖向量库，纯关键词召回。和 {@link AiRagRetriever}
 * （向量召回）配合使用，由 {@link HybridRagRetriever} 包装层做 RRF 合并。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.bm25.enabled", havingValue = "true")
public class Bm25Index {

    public static final String COLLECTION_SHOP = "shop_profile_vector";
    public static final String COLLECTION_REVIEW = "blog_review_vector";
    public static final String COLLECTION_KNOWLEDGE = "knowledge_vector";

    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_BIZ_ID = "biz_id";
    private static final String FIELD_NAME = "name";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Value("${rag.bm25.data-dir}")
    private String dataDir;

    private Analyzer analyzer;
    private IndexSearcher shopSearcher;
    private IndexSearcher reviewSearcher;
    private IndexSearcher knowledgeSearcher;

    @PostConstruct
    void init() throws IOException {
        long start = System.currentTimeMillis();
        this.analyzer = new SmartChineseAnalyzer();
        Path dir = Path.of(dataDir);

        this.shopSearcher = buildIndex(dir.resolve("shop_profile.jsonl"), this::shopDocFromJson);
        this.reviewSearcher = buildIndex(dir.resolve("blog_review.jsonl"), this::reviewDocFromJson);
        this.knowledgeSearcher = buildIndex(dir.resolve("knowledge_qa.jsonl"), this::knowledgeDocFromJson);

        log.info("[bm25] init done in {} ms (shop={} review={} knowledge={})",
                System.currentTimeMillis() - start,
                shopSearcher.getIndexReader().numDocs(),
                reviewSearcher.getIndexReader().numDocs(),
                knowledgeSearcher.getIndexReader().numDocs());
    }

    /**
     * 按 collection 名搜索。返回按 BM25 score 降序的 top-K 命中。
     */
    public List<Bm25Hit> search(String collection, String query, int topK) {
        IndexSearcher searcher = pickSearcher(collection);
        if (searcher == null || query == null || query.isBlank()) {
            return List.of();
        }
        try {
            QueryParser qp = new QueryParser(FIELD_CONTENT, analyzer);
            Query q = qp.parse(QueryParser.escape(query));
            TopDocs topDocs = searcher.search(q, topK);
            List<Bm25Hit> hits = new ArrayList<>(topDocs.scoreDocs.length);
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(sd.doc);
                String bizId = doc.get(FIELD_BIZ_ID);
                String name = doc.get(FIELD_NAME);
                String content = doc.get(FIELD_CONTENT);
                hits.add(new Bm25Hit(bizId, name == null ? "" : name, sd.score, content));
            }
            return hits;
        } catch (Exception e) {
            log.warn("[bm25] search failed for collection={}, query='{}': {}",
                    collection, truncate(query, 80), e.toString());
            return List.of();
        }
    }

    private IndexSearcher pickSearcher(String collection) {
        if (COLLECTION_SHOP.equals(collection)) return shopSearcher;
        if (COLLECTION_REVIEW.equals(collection)) return reviewSearcher;
        if (COLLECTION_KNOWLEDGE.equals(collection)) return knowledgeSearcher;
        return null;
    }

    /**
     * 通用建索引：流式读 JSONL，每行用 docMapper 转成 Lucene Document，全部写入内存 Directory。
     */
    private IndexSearcher buildIndex(Path jsonlFile, JsonlToDoc docMapper) throws IOException {
        Directory dir = new ByteBuffersDirectory();
        IndexWriterConfig cfg = new IndexWriterConfig(analyzer).setSimilarity(new BM25Similarity());
        int lineNum = 0;
        int parseFails = 0;
        try (IndexWriter writer = new IndexWriter(dir, cfg);
             BufferedReader reader = Files.newBufferedReader(jsonlFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
                try {
                    JsonNode node = MAPPER.readTree(trimmed);
                    Document doc = docMapper.apply(node);
                    if (doc != null) writer.addDocument(doc);
                } catch (Exception e) {
                    parseFails++;
                    if (parseFails <= 3) {
                        log.warn("[bm25] {} parse failed line {}: {}",
                                jsonlFile.getFileName(), lineNum, e.getMessage());
                    }
                }
            }
        }
        if (parseFails > 0) {
            log.warn("[bm25] {} total parse failures: {}", jsonlFile.getFileName(), parseFails);
        }
        DirectoryReader r = DirectoryReader.open(dir);
        IndexSearcher searcher = new IndexSearcher(r);
        searcher.setSimilarity(new BM25Similarity());
        return searcher;
    }

    private Document shopDocFromJson(JsonNode json) {
        String shopId = textOrEmpty(json, "shop_id");
        if (shopId.isEmpty()) return null;
        Document doc = new Document();
        doc.add(new StoredField(FIELD_BIZ_ID, shopId));
        doc.add(new StoredField(FIELD_NAME, textOrEmpty(json, "name")));
        // content 字段是 Plan A 拼接好的 "店名 品类 位置 价位 评分 营业时间"，用作 BM25 匹配的主战场
        // 同时把 name / city / category / address / signature_dishes 拼进去增加关键词命中面
        StringBuilder text = new StringBuilder();
        appendIfPresent(text, json, "name");
        appendIfPresent(text, json, "city");
        appendIfPresent(text, json, "category");
        appendIfPresent(text, json, "address");
        if (json.has("signature_dishes") && json.get("signature_dishes").isArray()) {
            json.get("signature_dishes").forEach(d -> text.append(' ').append(d.asText("")));
        }
        appendIfPresent(text, json, "content");
        doc.add(new TextField(FIELD_CONTENT, text.toString().trim(), Field.Store.YES));
        return doc;
    }

    private Document reviewDocFromJson(JsonNode json) {
        String reviewId = textOrEmpty(json, "review_id");
        if (reviewId.isEmpty()) return null;
        Document doc = new Document();
        doc.add(new StoredField(FIELD_BIZ_ID, reviewId));
        doc.add(new StoredField(FIELD_NAME, "review_" + reviewId));
        doc.add(new TextField(FIELD_CONTENT, textOrEmpty(json, "content"), Field.Store.YES));
        return doc;
    }

    private Document knowledgeDocFromJson(JsonNode json) {
        // knowledge_qa.jsonl 的 id 字段是数字，直接读 asText
        String id = json.has("id") ? json.get("id").asText("") : "";
        if (id.isEmpty()) return null;
        Document doc = new Document();
        doc.add(new StoredField(FIELD_BIZ_ID, id));
        doc.add(new StoredField(FIELD_NAME, textOrEmpty(json, "topic")));
        // knowledge content 已经是 Q+A 拼接，BM25 匹配 question 和 answer 关键词都有效
        doc.add(new TextField(FIELD_CONTENT, textOrEmpty(json, "content"), Field.Store.YES));
        return doc;
    }

    private static void appendIfPresent(StringBuilder sb, JsonNode json, String field) {
        if (json.has(field) && !json.get(field).isNull()) {
            sb.append(' ').append(json.get(field).asText(""));
        }
    }

    private static String textOrEmpty(JsonNode json, String field) {
        if (!json.has(field) || json.get(field).isNull()) return "";
        return json.get(field).asText("");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    @FunctionalInterface
    private interface JsonlToDoc {
        Document apply(JsonNode json);
    }

    @Data
    @AllArgsConstructor
    public static final class Bm25Hit {
        public final String businessId;   // shop_id / review_id / knowledge.id（统一 String）
        public final String name;         // 用于报告 dump
        public final float score;         // BM25 score
        public final String content;      // 原文，用于拼 contexts 给 generation
    }
}
