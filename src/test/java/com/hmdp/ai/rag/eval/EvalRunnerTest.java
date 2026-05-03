package com.hmdp.ai.rag.eval;

import com.hmdp.ai.rag.dto.EvalQuery;
import com.hmdp.ai.rag.ingest.JsonlReader;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * RAG 召回评估入口测试。
 *
 * <p>⚠️ 本测试需要本地启动 MySQL + Redis + Milvus + 硅基流动 API key 配置后手动跑：
 * <pre>
 *   mvn test -Dtest=EvalRunnerTest#runFullEval
 * </pre>
 *
 * <p>因为依赖外部服务，默认 @Disabled 不参与 mvn test 全量执行。
 */
@Slf4j
@Disabled("Requires local MySQL/Redis/Milvus + API key. Run manually with -Dtest=EvalRunnerTest#runFullEval")
@SpringBootTest(properties = "ai.agent.bootstrap.enabled=false")
class EvalRunnerTest {

    @Autowired private RecallEvaluator evaluator;
    @Autowired private EvalReportWriter reportWriter;
    @Autowired private FailureCaseAnalyzer failureCaseAnalyzer;
    @Autowired private FilterExperimentRunner filterExperimentRunner;
    @Autowired private EmbeddingCache embeddingCache;
    @Autowired private JsonlReader jsonlReader;

    @Value("${rag.eval.thresholds}")        private List<Double> thresholds;
    @Value("${rag.eval.top-k-list}")        private List<Integer> topKList;
    @Value("${rag.eval.max-top-k}")         private int maxTopK;
    @Value("${rag.eval.failure-case-limit:8}") private int failureCaseLimit;
    @Value("${rag.ingest.data-dir}")        private String dataDir;
    @Value("${rag.eval.output-dir}")        private String outputDir;

    @Test
    void runFullEval() throws Exception {
        // 1. 读 50 条评估 query
        Path evalFile = Path.of(dataDir, "eval_queries.jsonl");
        List<EvalQuery> queries = jsonlReader.readAll(evalFile, EvalQuery.class);
        log.info("[eval] loaded {} eval queries", queries.size());

        // 2. 生成 12 个配置
        List<EvalConfig> configs = EvalConfig.generateAll(thresholds, topKList);
        log.info("[eval] generated {} configs (thresholds={}, topK={})",
                configs.size(), thresholds, topKList);

        // 3. 跑主评估
        long start = System.currentTimeMillis();
        AggregatedReport report = evaluator.evaluateAll(queries, configs, maxTopK);
        long elapsed = System.currentTimeMillis() - start;
        log.info("[eval] aggregated eval done in {} ms (cache size={})",
                elapsed, embeddingCache.size());

        // 4. 控制台打印关键指标
        printConsoleSummary(report);

        // 5. Filter 实验
        FilterExperimentRunner.FilterExperimentResult filterExp = filterExperimentRunner.run(queries);

        // 6. Failure case：用 shop_profile collection 上的最优配置作为 baseline
        EvalConfig baselineCfg = pickBaselineForFailures(report);
        List<FailureCaseAnalyzer.FailureCase> failures = baselineCfg == null
                ? List.of()
                : failureCaseAnalyzer.findFailures(queries, report, baselineCfg, maxTopK, failureCaseLimit);

        // 7. 写 Markdown 报告
        Path reportPath = reportWriter.write(Path.of(outputDir), report, filterExp, failures, queries.size(), elapsed);
        log.info("[eval] ✅ report written: {}", reportPath);
        log.info("[eval] 📋 NEXT STEP: 人工补完 failure case 报告里的'根因/改进方向'两栏，再 commit");
    }

    private void printConsoleSummary(AggregatedReport report) {
        log.info("=== Eval Summary ===");
        for (String collection : report.getCollections()) {
            Map<EvalConfig, AggregatedReport.AggregatedMetrics> rows = report.getAggregated().get(collection);
            log.info("--- {} ---", collection);
            rows.entrySet().stream()
                    .sorted(Comparator
                            .<Map.Entry<EvalConfig, AggregatedReport.AggregatedMetrics>>comparingDouble(e -> e.getKey().getThreshold())
                            .thenComparingInt(e -> e.getKey().getTopK()))
                    .forEach(e -> log.info("  [{}]  recall={}  precision={}  mrr={}  hit={}",
                            e.getKey().label(),
                            String.format("%.3f", e.getValue().getAvgContextRecall()),
                            String.format("%.3f", e.getValue().getAvgContextPrecision()),
                            String.format("%.3f", e.getValue().getMrr()),
                            String.format("%.3f", e.getValue().getHitRate())));
        }
    }

    private EvalConfig pickBaselineForFailures(AggregatedReport report) {
        // 选 shop_profile 的最优配置；如果没有则第一个 collection 的最优
        for (String collection : report.getCollections()) {
            Map<EvalConfig, AggregatedReport.AggregatedMetrics> rows = report.getAggregated().get(collection);
            return rows.entrySet().stream()
                    .max(Comparator.comparingDouble(e ->
                            e.getValue().getAvgContextRecall() * 0.5 + e.getValue().getAvgContextPrecision() * 0.5))
                    .map(Map.Entry::getKey).orElse(null);
        }
        return null;
    }
}
