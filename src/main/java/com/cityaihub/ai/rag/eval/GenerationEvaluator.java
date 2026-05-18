package com.hmdp.ai.rag.eval;

import com.hmdp.ai.rag.dto.EvalQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Plan D：generation 层评估编排器。
 *
 * <p>对每条 EvalQuery：
 * <ol>
 *   <li>调 RagAnswerGenerator 生成 (contexts, answer)
 *   <li>调 GenerationJudge.judgeFaithfulness(answer, contexts) → 0~1
 *   <li>调 GenerationJudge.judgeAnswerRelevancy(query, answer) → bool
 *   <li>记录 Detail 到 GenerationReport
 * </ol>
 *
 * <p>并发：generation + judge 都串行单条处理一条 query（保证 RAG retrieve → generate → judge 顺序），
 * 但跨 query 之间用线程池并发（concurrency 配置）。
 *
 * <p>失败容错：单条 query 失败不影响整体；失败的条目仍然 add Detail（faithfulness=0、relevant=false、
 * reason 含失败原因），report 里能看到失败 case。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rag.eval.generation.enabled", havingValue = "true")
public class GenerationEvaluator {

    private final RagAnswerGenerator generator;
    private final GenerationJudge judge;

    @Value("${rag.eval.generation.concurrency:2}")
    private int concurrency;

    public GenerationEvaluator(RagAnswerGenerator generator, GenerationJudge judge) {
        this.generator = generator;
        this.judge = judge;
    }

    public GenerationReport evaluate(List<EvalQuery> queries) {
        log.info("[gen-eval] start: {} queries, concurrency={}", queries.size(), concurrency);
        long startMs = System.currentTimeMillis();
        GenerationReport report = new GenerationReport();

        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, concurrency));
        try {
            // 提交全部 query 到线程池，按提交顺序拿结果（不打乱报告里的次序）
            List<Future<GenerationReport.Detail>> futures = new java.util.ArrayList<>(queries.size());
            for (EvalQuery q : queries) {
                futures.add(pool.submit(() -> processOne(q, report)));
            }

            for (int i = 0; i < futures.size(); i++) {
                try {
                    GenerationReport.Detail d = futures.get(i).get();
                    if (d != null) {
                        synchronized (report) {
                            report.addDetail(d);
                        }
                    }
                } catch (ExecutionException ee) {
                    log.warn("[gen-eval] query #{} (idx={}) execution failed: {}",
                            queries.get(i).getQueryId(), i, ee.getCause() == null ? ee.toString() : ee.getCause().toString());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Generation eval interrupted", ie);
                }

                int done = i + 1;
                if (done % 10 == 0 || done == queries.size()) {
                    log.info("[gen-eval] progress {}/{}", done, queries.size());
                }
            }
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
        }

        // 按 collection 聚合
        aggregate(report);
        long elapsedMs = System.currentTimeMillis() - startMs;
        log.info("[gen-eval] done in {} ms: total={}, avgFaithfulness={}, avgRelevancy={}, genFailed={}, judgeFailed={}",
                elapsedMs, report.getDetails().size(),
                String.format("%.3f", report.overallAvgFaithfulness()),
                String.format("%.3f", report.overallAvgRelevancy()),
                report.getGenerationFailed(), report.getJudgeFailed());
        return report;
    }

    /**
     * 单条 query：generate → faithfulness judge → relevancy judge → Detail。
     */
    private GenerationReport.Detail processOne(EvalQuery q, GenerationReport report) {
        RagAnswerGenerator.RagAnswerOutput out;
        try {
            out = generator.generate(q);
        } catch (Exception e) {
            log.warn("[gen-eval] generator threw for query_id={}: {}", q.getQueryId(), e.toString());
            synchronized (report) {
                report.setGenerationFailed(report.getGenerationFailed() + 1);
            }
            return new GenerationReport.Detail(
                    q.getQueryId(), q.getQuery(), q.getTargetCollection(),
                    0, "", "",
                    0.0, false,
                    "GENERATOR_THREW", "GENERATOR_THREW",
                    0L, 0L);
        }

        // generation 失败（answer 为空）单独计数，不阻断后续 judge（让 judge 给出 EMPTY_ANSWER reason）
        if (!out.hasAnswer()) {
            synchronized (report) {
                report.setGenerationFailed(report.getGenerationFailed() + 1);
            }
        }

        GenerationJudge.FaithResult faith;
        GenerationJudge.RelevancyResult rel;
        try {
            faith = judge.judgeFaithfulness(out.answer, out.contexts);
        } catch (Exception e) {
            log.warn("[gen-eval] faith judge threw for query_id={}: {}", q.getQueryId(), e.toString());
            synchronized (report) { report.setJudgeFailed(report.getJudgeFailed() + 1); }
            faith = new GenerationJudge.FaithResult(0.0, "JUDGE_THREW");
        }
        try {
            rel = judge.judgeAnswerRelevancy(q.getQuery(), out.answer);
        } catch (Exception e) {
            log.warn("[gen-eval] relevancy judge threw for query_id={}: {}", q.getQueryId(), e.toString());
            synchronized (report) { report.setJudgeFailed(report.getJudgeFailed() + 1); }
            rel = new GenerationJudge.RelevancyResult(false, "JUDGE_THREW");
        }
        if ("ALL_RETRIES_FAILED".equals(faith.reason) || "ALL_RETRIES_FAILED".equals(rel.reason)) {
            synchronized (report) { report.setJudgeFailed(report.getJudgeFailed() + 1); }
        }

        return new GenerationReport.Detail(
                q.getQueryId(), q.getQuery(), q.getTargetCollection(),
                out.contextCount,
                truncate(out.contexts, 200),
                out.answer,
                faith.score, rel.relevant,
                faith.reason, rel.reason,
                out.retrievalLatencyMs, out.generationLatencyMs);
    }

    private void aggregate(GenerationReport report) {
        Map<String, java.util.List<GenerationReport.Detail>> byCol = new LinkedHashMap<>();
        for (GenerationReport.Detail d : report.getDetails()) {
            byCol.computeIfAbsent(d.targetCollection, k -> new java.util.ArrayList<>()).add(d);
        }
        for (Map.Entry<String, java.util.List<GenerationReport.Detail>> e : byCol.entrySet()) {
            String col = e.getKey();
            java.util.List<GenerationReport.Detail> list = e.getValue();
            int n = list.size();
            double avgF = list.stream().mapToDouble(d -> d.faithfulness).average().orElse(0);
            double avgR = list.stream().mapToDouble(d -> d.relevant ? 1 : 0).average().orElse(0);
            int genFailed = (int) list.stream()
                    .filter(d -> d.answer == null || d.answer.isBlank() || "GENERATOR_THREW".equals(d.faithReason))
                    .count();
            int judgeFailed = (int) list.stream()
                    .filter(d -> "ALL_RETRIES_FAILED".equals(d.faithReason)
                              || "ALL_RETRIES_FAILED".equals(d.relevancyReason)
                              || "JUDGE_THREW".equals(d.faithReason)
                              || "JUDGE_THREW".equals(d.relevancyReason))
                    .count();
            report.aggregate(col, new GenerationReport.CollectionMetrics(col, n, avgF, avgR, genFailed, judgeFailed));
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
