package com.cityaihub.ai.rag.eval;

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

    // ===== Plan C：LLM-as-judge 增量字段（reference-free，不算 Recall）=====

    /** collection -> EvalConfig -> LLM-judge 平均指标。null = 未跑 LLM-judge */
    private Map<String, Map<EvalConfig, LlmJudgeMetrics>> llmJudgeAggregated;

    /** judge 总命中率 sanity check 用：所有 (query, doc) 对里 LLM 判 true 的比例 */
    private double llmJudgeOverallTrueRate;

    /** judge 调用统计 */
    private int llmJudgeCallCount;
    private int llmJudgeFailureCount;
    private int llmJudgeCacheSize;

    public void recordPerQuery(int queryId, String collection, Map<EvalConfig, EvalResult> results) {
        perQuery.put(queryId, results);
        queryCollection.put(queryId, collection);
    }

    public void aggregate(String collection, EvalConfig config, AggregatedMetrics metrics) {
        aggregated.computeIfAbsent(collection, c -> new LinkedHashMap<>()).put(config, metrics);
    }

    public void aggregateLlmJudge(String collection, EvalConfig config, LlmJudgeMetrics metrics) {
        if (llmJudgeAggregated == null) {
            llmJudgeAggregated = new LinkedHashMap<>();
        }
        llmJudgeAggregated.computeIfAbsent(collection, c -> new LinkedHashMap<>()).put(config, metrics);
    }

    public boolean hasLlmJudge() {
        return llmJudgeAggregated != null && !llmJudgeAggregated.isEmpty();
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

    /**
     * Plan C：LLM-judge reference-free 指标。
     * 注意没有 Recall——reference-free 范式没有 ground truth 全集做分母。
     */
    @Data
    public static class LlmJudgeMetrics {
        private final int querySize;
        private final double avgContextPrecision;  // LLM 判相关 / 实际返回数
        private final double mrr;                  // 第一个 LLM 判相关的排名倒数
        private final double hitRate;              // top-K 至少一个 LLM 判相关
    }
}
