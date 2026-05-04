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
// @Disabled("Requires local MySQL/Redis/Milvus + API key. Run manually with -Dtest=EvalRunnerTest#runFullEval")
@SpringBootTest(properties = {
        "ai.agent.bootstrap.enabled=false",
        "rag.eval.llm-judge.enabled=true"
})
class EvalRunnerTest {

    @Autowired private RecallEvaluator evaluator;
    @Autowired private EvalReportWriter reportWriter;
    @Autowired private FailureCaseAnalyzer failureCaseAnalyzer;
    @Autowired private FilterExperimentRunner filterExperimentRunner;
    @Autowired private EmbeddingCache embeddingCache;
    @Autowired private JsonlReader jsonlReader;
    @Autowired private LlmJudgeEvaluator llmJudgeEvaluator;
    @Autowired private LlmJudge llmJudge;

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

        // 7. Plan C：跑 LLM-as-judge（reference-free）评估，把指标聚合写回同一个 report
        long ljStart = System.currentTimeMillis();
        log.info("[llm-judge] starting LLM-as-judge evaluation (this calls Claude Haiku 4.5 ~500 times, expect ~3min)");
        llmJudgeEvaluator.evaluateAll(queries, configs, maxTopK, report);
        long ljElapsed = System.currentTimeMillis() - ljStart;
        log.info("[llm-judge] done in {} ms (calls={}, failures={}, cacheSize={})",
                ljElapsed, llmJudge.callCount(), llmJudge.failureCount(), llmJudge.cacheSize());

        // 8. 写 Markdown 报告（含 baseline + LLM-judge 双指标）
        long totalElapsed = elapsed + ljElapsed;
        Path reportPath = reportWriter.write(Path.of(outputDir), report, filterExp, failures, queries.size(), totalElapsed);
        log.info("[eval] ✅ report written: {}", reportPath);
        log.info("[eval] 📋 NEXT STEP: 人工补完 failure case 报告里的'根因/改进方向'两栏，再 commit");
    }

    /**
     * Plan C 小批量验证（Step 7.5）：跑前 10 条 query × top-10，输出供人工抽样验证 judge 准确率。
     * 单独一个测试方法，方便先 mvn test -Dtest=EvalRunnerTest#smallBatchSampleForJudge 验证再跑全量。
     */
    @Test
    void smallBatchSampleForJudge() throws Exception {
        Path evalFile = Path.of(dataDir, "eval_queries.jsonl");
        List<EvalQuery> queries = jsonlReader.readAll(evalFile, EvalQuery.class);
        log.info("[llm-judge-sample] loaded {} eval queries, taking first 10", queries.size());

        List<LlmJudgeEvaluator.SmallBatchSample> samples =
                llmJudgeEvaluator.runSmallBatchSample(queries, 10, maxTopK);

        log.info("[llm-judge-sample] DONE, {} (query, doc) pairs:", samples.size());
        long trueCount = samples.stream().filter(s -> s.relevant).count();
        log.info("[llm-judge-sample] true rate = {}/{} = {}",
                trueCount, samples.size(),
                samples.isEmpty() ? "n/a" : String.format("%.1f%%", trueCount * 100.0 / samples.size()));
        log.info("[llm-judge-sample] judge call count={} failures={}",
                llmJudge.callCount(), llmJudge.failureCount());

        // 控制台打印每条采样供人工对照
        for (LlmJudgeEvaluator.SmallBatchSample s : samples) {
            log.info("[sample] qid={} {} | sim={} | relevant={} | doc[{}]: {}",
                    s.queryId,
                    s.query.length() > 40 ? s.query.substring(0, 40) + "..." : s.query,
                    String.format("%.3f", s.similarity),
                    s.relevant,
                    s.collection,
                    s.contentPreview);
        }
        log.info("[llm-judge-sample] 📋 人工抽 10 条对比 reason 字段与实际相关性，准确率 ≥ 80% 才能跑全量 #runFullEval");
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
