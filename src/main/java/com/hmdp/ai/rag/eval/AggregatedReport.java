package com.hmdp.ai.rag.eval;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评估汇总报告：每个 collection × 每个 EvalConfig 的平均指标。
 * 同时保留每条 query 的原始结果，供 FailureCaseAnalyzer 复用。
 */
@Data
public class AggregatedReport {

    /** collection -> EvalConfig -> 平均指标 */
    private final Map<String, Map<EvalConfig, AggregatedMetrics>> aggregated = new LinkedHashMap<>();

    /** queryId -> EvalConfig -> 单 query 评估结果（用于 failure case 定位） */
    private final Map<Integer, Map<EvalConfig, EvalResult>> perQuery = new LinkedHashMap<>();

    /** queryId -> 该 query 的 target_collection，用于 group by */
    private final Map<Integer, String> queryCollection = new LinkedHashMap<>();

    public void recordPerQuery(int queryId, String collection, Map<EvalConfig, EvalResult> results) {
        perQuery.put(queryId, results);
        queryCollection.put(queryId, collection);
    }

    public void aggregate(String collection, EvalConfig config, AggregatedMetrics metrics) {
        aggregated.computeIfAbsent(collection, c -> new LinkedHashMap<>()).put(config, metrics);
    }

    public List<String> getCollections() {
        return new ArrayList<>(aggregated.keySet());
    }

    @Data
    public static class AggregatedMetrics {
        private final int querySize;
        private final double avgContextRecall;
        private final double avgContextPrecision;
        private final double mrr;
        private final double hitRate;
    }
}
