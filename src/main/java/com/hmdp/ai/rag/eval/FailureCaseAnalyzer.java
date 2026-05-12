package com.hmdp.ai.rag.eval;

import com.hmdp.ai.rag.AiMetadataConstants;
import com.hmdp.ai.rag.dto.EvalQuery;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Failure Case 分析：从评估结果里捞出"hit=0"的 query，对它们做详细 dump。
 *
 * <p><b>设计纪律</b>：本类只输出"召回了什么"的客观事实——query 文本、期望 ID、实际 top-K 召回，
 * <b>不写"为什么没召回"和"怎么改进"</b>，那两栏由用户人工补完才能 commit。
 * 参考 langchain trace 思路：评估不止于打分，更要能定位问题。
 */
@Slf4j
@Service
public class FailureCaseAnalyzer {

    private final VectorStore knowledgeVectorStore;
    private final VectorStore shopProfileVectorStore;
    private final VectorStore blogReviewVectorStore;

    public FailureCaseAnalyzer(@Qualifier("knowledgeVectorStore") VectorStore knowledgeVectorStore,
                               @Qualifier("shopProfileVectorStore") VectorStore shopProfileVectorStore,
                               @Qualifier("blogReviewVectorStore") VectorStore blogReviewVectorStore) {
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.shopProfileVectorStore = shopProfileVectorStore;
        this.blogReviewVectorStore = blogReviewVectorStore;
    }

    /**
     * 在某个 baseline 配置下，找出 hit=0 的 query 并 dump 其 top-K 召回情况。
     */
    public List<FailureCase> findFailures(List<EvalQuery> queries, AggregatedReport report,
                                           EvalConfig baselineConfig, int topK, int maxCases) {
        List<FailureCase> failures = new ArrayList<>();
        for (EvalQuery q : queries) {
            Map<EvalConfig, EvalResult> perConfig = report.getPerQuery().get(q.getQueryId());
            if (perConfig == null) continue;
            EvalResult r = perConfig.get(baselineConfig);
            if (r == null || r.isHit()) continue;

            VectorStore vs = pickVectorStore(q.getTargetCollection());
            if (vs == null) continue;

            // Bug 1.6: shop_profile 评估排除玉泉 100001-100999
            boolean isShopProfile = "shop_profile_vector".equals(q.getTargetCollection());
            int searchTopK = isShopProfile ? topK * 3 : topK;
            SearchRequest req = SearchRequest.builder()
                    .query(q.getQuery())
                    .topK(searchTopK)
                    .similarityThreshold(0.0)
                    .build();
            List<Document> hits;
            try {
                hits = vs.similaritySearch(req);
                if (hits == null) hits = List.of();
                if (isShopProfile) {
                    hits = hits.stream()
                            .filter(d -> !RecallEvaluator.isYuquanDemo(RecallEvaluator.extractBusinessId(d)))
                            .limit(topK)
                            .collect(java.util.stream.Collectors.toList());
                }
            } catch (Exception e) {
                log.warn("Failure analysis search failed for query_id={}: {}", q.getQueryId(), e.toString());
                continue;
            }

            List<RetrievedItem> items = new ArrayList<>(hits.size());
            for (Document d : hits) {
                items.add(new RetrievedItem(
                        RecallEvaluator.extractBusinessId(d),
                        getMetaString(d, AiMetadataConstants.SHOP_NAME),
                        getMetaString(d, AiMetadataConstants.CITY),
                        getMetaString(d, AiMetadataConstants.CATEGORY),
                        RecallEvaluator.getSimilarityScore(d),
                        truncate(d.getText(), 80)
                ));
            }

            failures.add(new FailureCase(q.getQueryId(), q.getQuery(), q.getTargetCollection(),
                    q.getRelevantIds(), items));
            if (failures.size() >= maxCases) break;
        }
        log.info("[failure] collected {} failure cases (limit={})", failures.size(), maxCases);
        return failures;
    }

    private VectorStore pickVectorStore(String targetCollection) {
        if ("shop_profile_vector".equals(targetCollection)) return shopProfileVectorStore;
        if ("blog_review_vector".equals(targetCollection)) return blogReviewVectorStore;
        if ("knowledge_vector".equals(targetCollection)) return knowledgeVectorStore;
        return null;
    }

    private String getMetaString(Document doc, String key) {
        if (doc.getMetadata() == null) return "";
        Object v = doc.getMetadata().get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    @Data
    @AllArgsConstructor
    public static class FailureCase {
        private final int queryId;
        private final String query;
        private final String targetCollection;
        private final List<String> expectedRelevantIds;
        private final List<RetrievedItem> actualTopK;
    }

    @Data
    @AllArgsConstructor
    public static class RetrievedItem {
        private final String businessId;
        private final String name;
        private final String city;
        private final String category;
        private final double similarity;
        private final String contentPreview;
    }
}
