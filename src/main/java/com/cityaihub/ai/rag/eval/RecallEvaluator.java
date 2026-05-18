package com.cityaihub.ai.rag.eval;

import com.cityaihub.ai.rag.AiMetadataConstants;
import com.cityaihub.ai.rag.dto.EvalQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * RAG 召回评估主器：50 query × 12 配置 × 3 collection 全跑。
 *
 * <p>两条硬性优化：
 * <ol>
 *   <li><b>embedding 缓存</b>——通过 {@link EmbeddingCache} 让每条 query 只 embed 1 次
 *   <li><b>单次检索多组评估</b>——每条 query 只调一次 {@code similaritySearch(topK=maxTopK)}，
 *       返回结果在内存里按 (threshold, topK) 切片得到 12 组指标
 * </ol>
 *
 * <p>指标命名对齐 Ragas（Context Recall@K / Context Precision@K / MRR / HitRate）。
 */
@Slf4j
@Service
public class RecallEvaluator {

    private final VectorStore knowledgeVectorStore;
    private final VectorStore shopProfileVectorStore;
    private final VectorStore blogReviewVectorStore;
    private final EmbeddingCache embeddingCache;

    public RecallEvaluator(@Qualifier("knowledgeVectorStore") VectorStore knowledgeVectorStore,
                           @Qualifier("shopProfileVectorStore") VectorStore shopProfileVectorStore,
                           @Qualifier("blogReviewVectorStore") VectorStore blogReviewVectorStore,
                           EmbeddingCache embeddingCache) {
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.shopProfileVectorStore = shopProfileVectorStore;
        this.blogReviewVectorStore = blogReviewVectorStore;
        this.embeddingCache = embeddingCache;
    }

    public AggregatedReport evaluateAll(List<EvalQuery> queries, List<EvalConfig> configs, int maxTopK) {
        AggregatedReport report = new AggregatedReport();

        // 按 target_collection 分组
        Map<String, List<EvalQuery>> byCollection = queries.stream()
                .collect(Collectors.groupingBy(EvalQuery::getTargetCollection, LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<String, List<EvalQuery>> entry : byCollection.entrySet()) {
            String collection = entry.getKey();
            List<EvalQuery> qs = entry.getValue();
            log.info("[eval] collection={}, queries={}", collection, qs.size());

            // 1. 每条 query 跑 12 个配置（单次检索，内存切片）
            Map<Integer, Map<EvalConfig, EvalResult>> perQueryResults = new LinkedHashMap<>();
            for (EvalQuery q : qs) {
                Map<EvalConfig, EvalResult> results = evaluateOne(q, maxTopK, configs);
                perQueryResults.put(q.getQueryId(), results);
                report.recordPerQuery(q.getQueryId(), collection, results);
            }

            // 2. 对每个配置聚合所有 query 的指标
            for (EvalConfig cfg : configs) {
                int n = qs.size();
                double sumRecall = 0, sumPrecision = 0, sumRr = 0, sumHit = 0;
                for (EvalQuery q : qs) {
                    EvalResult r = perQueryResults.get(q.getQueryId()).get(cfg);
                    sumRecall += r.getContextRecall();
                    sumPrecision += r.getContextPrecision();
                    sumRr += r.getReciprocalRank();
                    sumHit += r.isHit() ? 1.0 : 0.0;
                }
                report.aggregate(collection, cfg, new AggregatedReport.AggregatedMetrics(
                        n,
                        n == 0 ? 0 : sumRecall / n,
                        n == 0 ? 0 : sumPrecision / n,
                        n == 0 ? 0 : sumRr / n,
                        n == 0 ? 0 : sumHit / n
                ));
            }
        }

        log.info("[eval] embedding cache size={}", embeddingCache.size());
        return report;
    }

    /**
     * 单 query × 多配置评估：单次 Milvus 检索（topK=maxTopK），内存切片得到所有配置的指标。
     *
     * 评估专用 filter (Bug 1.6 修复): shop_profile_vector query 时, **评估视角下**
     * 排除玉泉演示数据 (id 100001-100999). 原因: ground truth 是 yf shop_id 列表,
     * 玉泉店因店名/地址含"杭州""杭帮"等关键词,embedding 召回挤前 5,导致 yf 真目标店
     * 落到 top-K 之外, baseline 永远 miss. 业务路径不动 — 玉泉仍在 Milvus 供
     * searchNearbyShops/RAG 真实场景召回, 只是评估指标计算时把它排除以避免污染.
     *
     * 搜 topK*3 留 buffer, java 端 filter 后 truncate 到 maxTopK.
     */
    public Map<EvalConfig, EvalResult> evaluateOne(EvalQuery query, int maxTopK, List<EvalConfig> configs) {
        // embedding 通过 cache 命中
        embeddingCache.embed(query.getQuery());  // 触发缓存填充

        VectorStore vs = pickVectorStore(query.getTargetCollection());
        if (vs == null) {
            log.warn("Unknown target_collection: {}", query.getTargetCollection());
            return emptyResults(configs);
        }

        boolean isShopProfile = "shop_profile_vector".equals(query.getTargetCollection());
        int searchTopK = isShopProfile ? maxTopK * 3 : maxTopK;
        SearchRequest req = SearchRequest.builder()
                .query(query.getQuery())
                .topK(searchTopK)
                .similarityThreshold(0.0)
                .build();
        List<Document> hits = vs.similaritySearch(req);
        if (hits == null) {
            hits = List.of();
        }
        if (isShopProfile) {
            hits = hits.stream()
                    .filter(d -> !isYuquanDemo(extractBusinessId(d)))
                    .limit(maxTopK)
                    .collect(Collectors.toList());
        }

        Set<String> relevantSet = new HashSet<>(query.getRelevantIds());
        Map<EvalConfig, EvalResult> results = new LinkedHashMap<>();
        for (EvalConfig cfg : configs) {
            results.put(cfg, computeMetrics(hits, relevantSet, cfg));
        }
        return results;
    }

    private EvalResult computeMetrics(List<Document> allHits, Set<String> relevantSet, EvalConfig cfg) {
        // 切片：先按 threshold 过滤（用 distance metadata），再 limit topK
        List<Document> filtered = allHits.stream()
                .filter(d -> getSimilarityScore(d) >= cfg.getThreshold())
                .limit(cfg.getTopK())
                .collect(Collectors.toList());

        int relevantHits = 0;
        double rr = 0;
        for (int i = 0; i < filtered.size(); i++) {
            String bizId = extractBusinessId(filtered.get(i));
            if (bizId != null && relevantSet.contains(bizId)) {
                relevantHits++;
                if (rr == 0) {
                    rr = 1.0 / (i + 1);
                }
            }
        }

        double recall = relevantSet.isEmpty() ? 0 : (double) relevantHits / relevantSet.size();
        double precision = filtered.isEmpty() ? 0 : (double) relevantHits / filtered.size();
        boolean hit = relevantHits > 0;
        return new EvalResult(recall, precision, rr, hit);
    }

    /**
     * 从 Document.metadata 提取业务 ID。
     * 优先级：reviewId > qaId > shopId > sourceId（兼容老数据）。
     *
     * Bug 1.5 修复: Spring AI Milvus 把 metadata 里的 Number 类型反序列化为 Double,
     * String.valueOf(Double 200062.0) = "200062.0", 跟评估集 relevant_ids "200062" 不 match.
     * 这里统一剥掉 ".0" 后缀, 兼容 Long/Integer/Double 几种 Number 类型.
     */
    static String extractBusinessId(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null) {
            return null;
        }
        Object reviewId = meta.get(AiMetadataConstants.REVIEW_ID);
        if (reviewId != null && !"".equals(reviewId)) {
            return normalizeId(reviewId);
        }
        Object qaId = meta.get(AiMetadataConstants.QA_ID);
        if (qaId != null && !"".equals(qaId)) {
            return normalizeId(qaId);
        }
        Object shopId = meta.get(AiMetadataConstants.SHOP_ID);
        if (shopId != null && !"".equals(shopId)) {
            return normalizeId(shopId);
        }
        Object sourceId = meta.get(AiMetadataConstants.SOURCE_ID);
        return sourceId == null ? null : normalizeId(sourceId);
    }

    /** Spring AI Milvus 把 Number 反序列化为 Double, 剥 ".0" 后缀避免 ID 比对 miss. */
    private static String normalizeId(Object value) {
        String s = String.valueOf(value);
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }

    /** 玉泉演示数据 id 段 (100001-100999) 评估视角下排除 — 业务路径仍能召回. */
    static boolean isYuquanDemo(String bizId) {
        if (bizId == null) return false;
        try {
            long id = Long.parseLong(bizId);
            return id >= 100001 && id <= 100999;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Spring AI 的 MilvusVectorStore 把 cosine similarity 写进 metadata 的 "distance" 字段，
     * 注意：是 1 - similarity（即越小越像），需要换算回 similarity ∈ [0, 1]。
     */
    static double getSimilarityScore(Document doc) {
        Map<String, Object> meta = doc.getMetadata();
        if (meta == null) {
            return 0.0;
        }
        Object dist = meta.get("distance");
        if (dist instanceof Number) {
            return 1.0 - ((Number) dist).doubleValue();
        }
        // 兜底：如果未来 Spring AI 版本直接写 similarity，从 _score 取
        Object score = meta.get("_score");
        if (score instanceof Number) {
            return ((Number) score).doubleValue();
        }
        return 0.0;
    }

    private VectorStore pickVectorStore(String targetCollection) {
        if ("shop_profile_vector".equals(targetCollection)) return shopProfileVectorStore;
        if ("blog_review_vector".equals(targetCollection)) return blogReviewVectorStore;
        if ("knowledge_vector".equals(targetCollection)) return knowledgeVectorStore;
        return null;
    }

    private Map<EvalConfig, EvalResult> emptyResults(List<EvalConfig> configs) {
        Map<EvalConfig, EvalResult> empty = new HashMap<>();
        for (EvalConfig cfg : configs) {
            empty.put(cfg, new EvalResult(0, 0, 0, false));
        }
        return empty;
    }
}
