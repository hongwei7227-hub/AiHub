package com.hmdp.ai.rag.eval;

import com.hmdp.ai.rag.AiMetadataConstants;
import com.hmdp.ai.rag.dto.EvalQuery;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Metadata Filter 对比实验：自动筛 50 条评估集里所有"带'杭州'且 target=shop_profile_vector"
 * 的 query，分别跑 Baseline（纯向量）和 +City Filter（city == "杭州"），对比 Precision@K。
 *
 * <p>对照 Milvus 官方实践——业务 RAG 中 99% 的检索都应该带 filter，纯向量召回是研究场景。
 *
 * <p><b>反 cherry-pick</b>：自动过滤而非手工挑选样本，平均提升幅度有多少就如实报告多少。
 */
@Slf4j
@Service
public class FilterExperimentRunner {

    private static final String CITY_KEYWORD = "杭州";
    private static final String FILTER_CITY = "杭州";
    private static final int TOP_K = 5;

    private final VectorStore shopProfileVectorStore;

    public FilterExperimentRunner(@Qualifier("shopProfileVectorStore") VectorStore shopProfileVectorStore) {
        this.shopProfileVectorStore = shopProfileVectorStore;
    }

    public FilterExperimentResult run(List<EvalQuery> allQueries) {
        List<EvalQuery> cityQueries = allQueries.stream()
                .filter(q -> "shop_profile_vector".equals(q.getTargetCollection()))
                .filter(q -> q.getQuery() != null && q.getQuery().contains(CITY_KEYWORD))
                .toList();

        log.info("[filter-exp] auto-selected {} queries with city='{}' intent",
                cityQueries.size(), CITY_KEYWORD);
        if (cityQueries.isEmpty()) {
            return new FilterExperimentResult(List.of(), 0, 0, 0);
        }

        List<ComparisonRow> rows = new ArrayList<>(cityQueries.size());
        org.springframework.ai.vectorstore.filter.Filter.Expression cityFilter =
                new FilterExpressionBuilder().eq(AiMetadataConstants.CITY, FILTER_CITY).build();

        double sumBaseline = 0, sumFiltered = 0;
        for (EvalQuery q : cityQueries) {
            Set<String> expected = new HashSet<>(q.getRelevantIds());

            List<Document> baselineHits = safeSearch(q.getQuery(), null);
            double baselinePrecision = computePrecision(baselineHits, expected);

            List<Document> filteredHits = safeSearch(q.getQuery(), cityFilter);
            double filteredPrecision = computePrecision(filteredHits, expected);

            rows.add(new ComparisonRow(q.getQueryId(), q.getQuery(), baselinePrecision, filteredPrecision));
            sumBaseline += baselinePrecision;
            sumFiltered += filteredPrecision;
        }
        int n = rows.size();
        double avgBaseline = sumBaseline / n;
        double avgFiltered = sumFiltered / n;
        double improvement = avgBaseline == 0 ? Double.POSITIVE_INFINITY :
                (avgFiltered - avgBaseline) / avgBaseline;
        log.info("[filter-exp] avg Precision@{}  baseline={}  filtered={}  improvement={}%",
                TOP_K, String.format("%.3f", avgBaseline), String.format("%.3f", avgFiltered),
                String.format("%.1f", improvement * 100));
        return new FilterExperimentResult(rows, avgBaseline, avgFiltered, improvement);
    }

    private List<Document> safeSearch(String query,
                                      org.springframework.ai.vectorstore.filter.Filter.Expression filterExpr) {
        SearchRequest.Builder b = SearchRequest.builder().query(query).topK(TOP_K).similarityThreshold(0.0);
        if (filterExpr != null) {
            b.filterExpression(filterExpr);
        }
        try {
            List<Document> hits = shopProfileVectorStore.similaritySearch(b.build());
            return hits == null ? List.of() : hits;
        } catch (Exception e) {
            log.warn("[filter-exp] search failed (filter={}): {}", filterExpr, e.toString());
            return List.of();
        }
    }

    private double computePrecision(List<Document> hits, Set<String> expected) {
        if (hits.isEmpty()) return 0.0;
        long relevantHits = hits.stream()
                .map(RecallEvaluator::extractBusinessId)
                .filter(id -> id != null && expected.contains(id))
                .count();
        return (double) relevantHits / hits.size();
    }

    @Data
    @AllArgsConstructor
    public static class FilterExperimentResult {
        private final List<ComparisonRow> rows;
        private final double avgBaselinePrecision;
        private final double avgFilteredPrecision;
        private final double avgImprovement;  // (avg_filtered - avg_baseline) / avg_baseline
    }

    @Data
    @AllArgsConstructor
    public static class ComparisonRow {
        private final int queryId;
        private final String query;
        private final double baselinePrecision;
        private final double filteredPrecision;
    }
}
