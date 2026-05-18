package com.cityaihub.ai.rag.eval;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plan D：generation 层评估聚合报告。
 *
 * <p>同时持有：
 * <ul>
 *   <li>每条 query 的详情记录 {@link Detail}（用于 EvalReportWriter dump 抽样）
 *   <li>按 collection 分组的聚合指标 {@link CollectionMetrics}
 * </ul>
 */
@Data
public class GenerationReport {

    /** 全部 query 的详情，按入库顺序排列 */
    private final List<Detail> details = new ArrayList<>();

    /** 按 collection 名分组的聚合指标，保持插入顺序方便报告 */
    private final Map<String, CollectionMetrics> aggregated = new LinkedHashMap<>();

    /** generation 调用失败次数（业务 chat 抛异常 / 空响应） */
    private int generationFailed = 0;

    /** judge 调用失败次数（faithfulness / relevancy parse 全部失败） */
    private int judgeFailed = 0;

    public void addDetail(Detail d) {
        details.add(d);
    }

    public void aggregate(String collection, CollectionMetrics m) {
        aggregated.put(collection, m);
    }

    public List<String> collections() {
        return new ArrayList<>(aggregated.keySet());
    }

    public double overallAvgFaithfulness() {
        if (details.isEmpty()) return 0;
        return details.stream().mapToDouble(d -> d.faithfulness).average().orElse(0);
    }

    public double overallAvgRelevancy() {
        if (details.isEmpty()) return 0;
        return details.stream().mapToDouble(d -> d.relevant ? 1 : 0).average().orElse(0);
    }

    public int totalCount() {
        return details.size();
    }

    public int failedTotal() {
        return generationFailed + judgeFailed;
    }

    @Data
    @AllArgsConstructor
    public static class Detail {
        public final int queryId;
        public final String query;
        public final String targetCollection;
        public final int contextCount;
        public final String contextsPreview;     // 摘要前 200 字，避免报告膨胀
        public final String answer;              // 完整答案（不截断，方便 audit）
        public final double faithfulness;
        public final boolean relevant;
        public final String faithReason;
        public final String relevancyReason;
        public final long retrievalLatencyMs;
        public final long generationLatencyMs;
    }

    @Data
    @AllArgsConstructor
    public static class CollectionMetrics {
        public final String collection;
        public final int queryCount;
        public final double avgFaithfulness;
        public final double avgRelevancy;
        public final int generationFailed;
        public final int judgeFailed;
    }
}
