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
        "rag.eval.llm-judge.enabled=true",
        "rag.eval.generation.enabled=true",
        // Plan D 发现：业务 ai.agent.rag.similarity-threshold=0.65 对评估集所有 query 召回 0 → generation 全部"无法回答"。
        // 评估时降到 0.5 让 generation pipeline 能拿到 contexts；业务运行时仍用 0.65（这里只 override 测试 JVM）。
        "ai.agent.rag.similarity-threshold=0.5",
        // Plan E：打开 BM25 索引 + Hybrid 包装层（@ConditionalOnProperty bean 才会注册）
        "rag.bm25.enabled=true",
        // Plan G：打开 Reranker（@ConditionalOnProperty(rag.rerank.enabled=true) bean 才会注册）
        "rag.rerank.enabled=true",
        // 默认 hybrid 模式跑 generation；runFullEval 跑三 PASS 时会临时切到 vector / hybrid+rerank
        "rag.eval.generation.retrieval-mode=hybrid"
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
    @Autowired private GenerationEvaluator generationEvaluator;
    @Autowired private RagAnswerGenerator ragAnswerGenerator;
    @Autowired private GenerationJudge generationJudge;

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

        // 7.5 Plan D + Plan E + Plan G：跑 generation 层评估三轮做对比
        //   PASS 1: vector baseline（Plan D）
        //   PASS 2: hybrid (vector + BM25 + RRF)（Plan E）
        //   PASS 3: hybrid+rerank (RRF top-20 → bge-reranker-v2-m3 精排 top-5)（Plan G）
        long genStart = System.currentTimeMillis();
        String originalMode = ragAnswerGenerator.getRetrievalMode();
        log.info("[gen-eval] Plan G: triple-pass evaluation (vector + hybrid + hybrid+rerank). Mode default={}", originalMode);

        log.info("[gen-eval] PASS 1/3: vector baseline (~{} business chat + {} judge calls)",
                queries.size(), queries.size() * 2);
        ragAnswerGenerator.setRetrievalMode("vector");
        GenerationReport vectorReport = generationEvaluator.evaluate(queries);
        log.info("[gen-eval] vector baseline: avg faithfulness={}  avg relevancy={}",
                String.format("%.3f", vectorReport.overallAvgFaithfulness()),
                String.format("%.3f", vectorReport.overallAvgRelevancy()));

        log.info("[gen-eval] PASS 2/3: hybrid (vector + BM25 + RRF)");
        ragAnswerGenerator.setRetrievalMode("hybrid");
        GenerationReport hybridReport = generationEvaluator.evaluate(queries);
        log.info("[gen-eval] hybrid: avg faithfulness={}  avg relevancy={}",
                String.format("%.3f", hybridReport.overallAvgFaithfulness()),
                String.format("%.3f", hybridReport.overallAvgRelevancy()));

        log.info("[gen-eval] PASS 3/3: hybrid+rerank (cross-encoder bge-reranker-v2-m3)");
        ragAnswerGenerator.setRetrievalMode("hybrid+rerank");
        GenerationReport rerankReport = generationEvaluator.evaluate(queries);
        ragAnswerGenerator.setRetrievalMode(originalMode);   // 恢复
        long genElapsed = System.currentTimeMillis() - genStart;
        log.info("[gen-eval] hybrid+rerank: avg faithfulness={}  avg relevancy={}",
                String.format("%.3f", rerankReport.overallAvgFaithfulness()),
                String.format("%.3f", rerankReport.overallAvgRelevancy()));
        log.info("[gen-eval] PASS 1+2+3 done in {} ms (judge calls={}, judge failures={})",
                genElapsed, generationJudge.llmCallCount(), generationJudge.llmFailureCount());
        log.info("[gen-eval] Δ relevancy (hybrid - vector) = {}",
                String.format("%+.3f", hybridReport.overallAvgRelevancy() - vectorReport.overallAvgRelevancy()));
        log.info("[gen-eval] Δ relevancy (hybrid+rerank - hybrid) = {}",
                String.format("%+.3f", rerankReport.overallAvgRelevancy() - hybridReport.overallAvgRelevancy()));

        // 8. 写 Markdown 报告（含 baseline + LLM-judge + generation 三套指标 + Plan E + Plan G 对比）
        long totalElapsed = elapsed + ljElapsed + genElapsed;
        Path reportPath = reportWriter.writePlanG(Path.of(outputDir), report, filterExp, failures,
                vectorReport, hybridReport, rerankReport, queries.size(), totalElapsed);

        // 8.5 dump 每条 query 的 LLM-judge 详情（含 reason），UTF-8 JSON，方便 audit / 抽样
        String dateStr = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
        Path detailsPath = llmJudgeEvaluator.dumpDetailsJson(Path.of(outputDir), dateStr);
        log.info("[eval] 📋 LLM-judge 详情 (含 reason) 写到: {}", detailsPath);
        log.info("[eval] ✅ report written: {}", reportPath);
        log.info("[eval] 📋 NEXT STEP: 人工补完 failure case 报告里的'根因/改进方向'两栏，再 commit");
    }

    /**
     * Plan D 独立入口：只跑 generation 层评估（不跑 retrieval / LLM-judge / filter / failure case）。
     * 适合在 retrieval baseline 已有时单独迭代 generation 评估。
     */
    @Test
    void runGenerationEval() throws Exception {
        Path evalFile = Path.of(dataDir, "eval_queries.jsonl");
        List<EvalQuery> queries = jsonlReader.readAll(evalFile, EvalQuery.class);
        log.info("[gen-eval] standalone run, loaded {} eval queries", queries.size());

        GenerationReport genReport = generationEvaluator.evaluate(queries);

        log.info("=== Generation Eval Summary (standalone) ===");
        log.info("Avg Faithfulness  : {}", String.format("%.3f", genReport.overallAvgFaithfulness()));
        log.info("Avg Answer Relevancy: {}", String.format("%.3f", genReport.overallAvgRelevancy()));
        log.info("Generation failed : {}", genReport.getGenerationFailed());
        log.info("Judge failed      : {}", genReport.getJudgeFailed());
        for (String col : genReport.collections()) {
            GenerationReport.CollectionMetrics m = genReport.getAggregated().get(col);
            log.info("[{}] n={} faith={} rel={} genFail={} judgeFail={}",
                    col, m.queryCount,
                    String.format("%.3f", m.avgFaithfulness),
                    String.format("%.3f", m.avgRelevancy),
                    m.generationFailed, m.judgeFailed);
        }

        // 控制台 dump 前 5 条详情
        log.info("=== First 5 Generation Details ===");
        genReport.getDetails().stream().limit(5).forEach(d ->
                log.info("[qid={} {}] q={} | answer={} | F={} | R={} | F-reason={} | R-reason={}",
                        d.queryId, d.targetCollection,
                        truncate60(d.query), truncate60(d.answer),
                        String.format("%.2f", d.faithfulness),
                        d.relevant ? "✓" : "✗",
                        d.faithReason, d.relevancyReason));
    }

    /**
     * Plan D 小批量验证（Step 7.5）：跑前 5 条 query → generate + judge，控制台 dump 全部细节。
     * 跑全量 #runGenerationEval 之前用这个验证：
     *   1. RagAnswerGenerator 检索是否拿到 contexts（不全空）
     *   2. Qwen2.5-7B 答案是否合理（不是英文/重复/空）
     *   3. Haiku 4.5 的 score / relevant 标签是否合理
     */
    @Test
    void smallBatchGenerationSample() throws Exception {
        Path evalFile = Path.of(dataDir, "eval_queries.jsonl");
        List<EvalQuery> queries = jsonlReader.readAll(evalFile, EvalQuery.class);
        List<EvalQuery> sample = queries.subList(0, Math.min(5, queries.size()));
        log.info("[gen-sample] loaded {} eval queries, sampling first {}", queries.size(), sample.size());

        for (EvalQuery q : sample) {
            log.info("---- qid={} target={} ----", q.getQueryId(), q.getTargetCollection());
            log.info("Q: {}", q.getQuery());

            RagAnswerGenerator.RagAnswerOutput out = ragAnswerGenerator.generate(q);
            log.info("Contexts ({} retrieved, latency {} ms):", out.contextCount, out.retrievalLatencyMs);
            log.info("  {}", truncate(out.contexts, 500));
            log.info("Answer (latency {} ms): {}", out.generationLatencyMs, out.answer);

            GenerationJudge.FaithResult faith = generationJudge.judgeFaithfulness(out.answer, out.contexts);
            GenerationJudge.RelevancyResult rel = generationJudge.judgeAnswerRelevancy(q.getQuery(), out.answer);
            log.info("Faithfulness: {} (reason: {})", String.format("%.2f", faith.score), faith.reason);
            log.info("Relevancy:    {} (reason: {})", rel.relevant ? "✓ true" : "✗ false", rel.reason);
        }
        log.info("[gen-sample] 📋 人工抽 5 条对比：(a) contexts 不全空 (b) answer 合理 (c) judge 标签合理");
    }

    private static String truncate60(String s) { return truncate(s, 60); }
    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
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
