package com.cityaihub.ai.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.cityaihub.ai.rag.dto.EvalQuery;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Plan C：reference-free LLM-as-judge 评估器。
 *
 * <p>复用 {@link RecallEvaluator} 的"单次检索 + 内存切片"优化：
 * <ol>
 *   <li>每条 query 只 embed 一次（{@link EmbeddingCache} 与 baseline 共享）
 *   <li>每条 query 只调一次 similaritySearch(topK=maxTopK)
 *   <li>对 top-K 候选每个 doc 调 LLM-judge 判 0/1
 *   <li>在内存里对每个配置切片：filtered = hits.filter(sim ≥ threshold).limit(topK)
 *   <li>切片后用 LLM-judge 结果计算 Precision@K / HitRate / MRR（**不算 Recall**）
 * </ol>
 *
 * <p>并发控制：所有 (query × doc) 对的 LLM 调用走 fixed thread pool，路数由
 * {@code rag.eval.llm-judge.concurrency} 控制，默认 3。
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "rag.eval.llm-judge.enabled", havingValue = "true")
public class LlmJudgeEvaluator {

    private final VectorStore knowledgeVectorStore;
    private final VectorStore shopProfileVectorStore;
    private final VectorStore blogReviewVectorStore;
    private final EmbeddingCache embeddingCache;
    private final LlmJudge llmJudge;

    @Value("${rag.eval.llm-judge.concurrency:3}")
    private int concurrency;

    @Value("${rag.eval.llm-judge.batch-mode:true}")
    private boolean batchMode;

    /**
     * 累积每条 query 的所有 (doc, similarity, relevant, reason, content) 详情，
     * 评估完后由 {@link #dumpDetailsJson(Path, String)} 写成 UTF-8 JSON 供人工抽样审计。
     * 内存占用：50 query × 10 doc × ~500 char content ≈ 250KB，可接受。
     */
    private final Map<Integer, QueryDetails> details = new ConcurrentHashMap<>();

    public LlmJudgeEvaluator(@Qualifier("knowledgeVectorStore") VectorStore knowledgeVectorStore,
                             @Qualifier("shopProfileVectorStore") VectorStore shopProfileVectorStore,
                             @Qualifier("blogReviewVectorStore") VectorStore blogReviewVectorStore,
                             EmbeddingCache embeddingCache,
                             LlmJudge llmJudge) {
        this.knowledgeVectorStore = knowledgeVectorStore;
        this.shopProfileVectorStore = shopProfileVectorStore;
        this.blogReviewVectorStore = blogReviewVectorStore;
        this.embeddingCache = embeddingCache;
        this.llmJudge = llmJudge;
    }

    /**
     * 把 LLM-judge 指标聚合写入 {@code report}（与 baseline 同一份对象）。
     */
    public void evaluateAll(List<EvalQuery> queries, List<EvalConfig> configs, int maxTopK,
                            AggregatedReport report) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, concurrency));
        try {
            // 按 collection 分组
            Map<String, List<EvalQuery>> byCollection = queries.stream()
                    .collect(Collectors.groupingBy(EvalQuery::getTargetCollection,
                            LinkedHashMap::new, Collectors.toList()));

            // 全局 sanity check 计数：所有 (query, doc) 对的 true / total
            long totalJudgements = 0;
            long totalTrue = 0;

            for (Map.Entry<String, List<EvalQuery>> entry : byCollection.entrySet()) {
                String collection = entry.getKey();
                List<EvalQuery> qs = entry.getValue();
                log.info("[llm-judge] collection={}, queries={}", collection, qs.size());

                // queryId -> List<RetrievedHit>（带 LLM-judge 标签）
                Map<Integer, List<JudgedHit>> perQueryHits = new LinkedHashMap<>();
                for (EvalQuery q : qs) {
                    List<JudgedHit> hits = retrieveAndJudge(q, collection, maxTopK, pool);
                    perQueryHits.put(q.getQueryId(), hits);

                    // 累积详情供 dumpDetailsJson 使用
                    List<HitDetail> hitDetails = new ArrayList<>(hits.size());
                    for (int rank = 0; rank < hits.size(); rank++) {
                        JudgedHit h = hits.get(rank);
                        hitDetails.add(new HitDetail(
                                rank + 1, h.docId, h.similarity, h.relevant, h.reason,
                                truncate(h.content, 200)));
                    }
                    details.put(q.getQueryId(), new QueryDetails(
                            q.getQueryId(), q.getQuery(), collection,
                            q.getRelevantIds(), hitDetails));

                    for (JudgedHit h : hits) {
                        totalJudgements++;
                        if (h.relevant) totalTrue++;
                    }
                }

                // 对每个配置聚合
                for (EvalConfig cfg : configs) {
                    int n = qs.size();
                    double sumPrecision = 0, sumRr = 0, sumHit = 0;
                    for (EvalQuery q : qs) {
                        List<JudgedHit> all = perQueryHits.get(q.getQueryId());
                        // 切片：threshold 过滤 + topK 截断
                        List<JudgedHit> filtered = all.stream()
                                .filter(h -> h.similarity >= cfg.getThreshold())
                                .limit(cfg.getTopK())
                                .collect(Collectors.toList());

                        long relevantCount = filtered.stream().filter(h -> h.relevant).count();
                        double precision = filtered.isEmpty() ? 0
                                : (double) relevantCount / filtered.size();
                        boolean hit = relevantCount > 0;
                        double rr = 0;
                        for (int i = 0; i < filtered.size(); i++) {
                            if (filtered.get(i).relevant) {
                                rr = 1.0 / (i + 1);
                                break;
                            }
                        }
                        sumPrecision += precision;
                        sumRr += rr;
                        sumHit += hit ? 1.0 : 0.0;
                    }
                    report.aggregateLlmJudge(collection, cfg, new AggregatedReport.LlmJudgeMetrics(
                            n,
                            n == 0 ? 0 : sumPrecision / n,
                            n == 0 ? 0 : sumRr / n,
                            n == 0 ? 0 : sumHit / n
                    ));
                }
            }

            // 写 sanity check + 调用统计
            double overallTrueRate = totalJudgements == 0 ? 0 : (double) totalTrue / totalJudgements;
            report.setLlmJudgeOverallTrueRate(overallTrueRate);
            report.setLlmJudgeCallCount(llmJudge.callCount());
            report.setLlmJudgeFailureCount(llmJudge.failureCount());
            report.setLlmJudgeCacheSize(llmJudge.cacheSize());
            log.info("[llm-judge] DONE. judgements={} trueRate={} callCount={} failures={} cacheSize={}",
                    totalJudgements, String.format("%.3f", overallTrueRate),
                    llmJudge.callCount(), llmJudge.failureCount(), llmJudge.cacheSize());
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 把所有 query 的 LLM-judge 详情（含 reason）写到 UTF-8 JSON 文件。
     * 解决 Plan C report 只有聚合数字、单条 (query, doc, reason) 不可查的痛点。
     *
     * @return 实际写入的文件路径
     */
    public Path dumpDetailsJson(Path outputDir, String dateStr) throws IOException {
        Files.createDirectories(outputDir);
        Path file = outputDir.resolve("rag_eval_judge_details_" + dateStr + ".json");
        // 按 queryId 排序，列表化方便阅读
        List<QueryDetails> ordered = details.values().stream()
                .sorted((a, b) -> Integer.compare(a.queryId, b.queryId))
                .collect(Collectors.toList());
        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("generatedAt", java.time.LocalDateTime.now().toString());
        wrapper.put("totalQueries", ordered.size());
        wrapper.put("note",
                "Per-query LLM-judge audit: each query × top-K retrieved doc with similarity, relevant, reason, content preview. UTF-8.");
        wrapper.put("queries", ordered);
        Files.writeString(file, JSON_MAPPER.writeValueAsString(wrapper), StandardCharsets.UTF_8);
        log.info("[llm-judge-details] wrote {} queries to {}", ordered.size(), file);
        return file;
    }

    /**
     * 小批量 judge 模式：仅跑前 N 条 query，输出每条 (query, doc) 的判断结果，给用户人工抽样验证。
     * 返回 List 以方便人工查看。
     */
    public List<SmallBatchSample> runSmallBatchSample(List<EvalQuery> queries, int sampleSize, int maxTopK) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, concurrency));
        try {
            List<SmallBatchSample> samples = new java.util.ArrayList<>();
            int taken = 0;
            for (EvalQuery q : queries) {
                if (taken >= sampleSize) break;
                List<JudgedHit> hits = retrieveAndJudge(q, q.getTargetCollection(), maxTopK, pool);
                for (JudgedHit h : hits) {
                    samples.add(new SmallBatchSample(
                            q.getQueryId(), q.getQuery(), q.getTargetCollection(),
                            h.docId, h.similarity, h.relevant, h.reason, truncate(h.content, 160)));
                }
                taken++;
            }
            return samples;
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private List<JudgedHit> retrieveAndJudge(EvalQuery query, String collection, int maxTopK,
                                              ExecutorService pool) {
        // 触发 embedding cache（复用 baseline 已 cache 的 query embedding）
        embeddingCache.embed(query.getQuery());

        VectorStore vs = pickVectorStore(collection);
        if (vs == null) {
            log.warn("[llm-judge] unknown collection={} queryId={}", collection, query.getQueryId());
            return List.of();
        }

        // Bug 1.6: shop_profile 评估排除玉泉 100001-100999 (跟 RecallEvaluator 对齐)
        boolean isShopProfile = "shop_profile_vector".equals(collection);
        int searchTopK = isShopProfile ? maxTopK * 3 : maxTopK;
        SearchRequest req = SearchRequest.builder()
                .query(query.getQuery())
                .topK(searchTopK)
                .similarityThreshold(0.0)
                .build();
        List<Document> hits = vs.similaritySearch(req);
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        if (isShopProfile) {
            hits = hits.stream()
                    .filter(d -> !RecallEvaluator.isYuquanDemo(RecallEvaluator.extractBusinessId(d)))
                    .limit(maxTopK)
                    .collect(java.util.stream.Collectors.toList());
        }

        // batch-mode：单 query 1 次 LLM 调用判全部 top-K，节省 10x 配额（copilot-api 按次计费场景）
        // legacy mode：每个 (query, doc) 一次调用，并发 N 路，质量基线但配额贵
        if (batchMode) {
            Map<String, LlmJudge.JudgeResult> rel =
                    llmJudge.judgeBatchWithResult(query.getQuery(), collection, hits);
            List<JudgedHit> out = new java.util.ArrayList<>(hits.size());
            for (Document d : hits) {
                LlmJudge.JudgeResult r = rel.get(d.getId());
                out.add(new JudgedHit(
                        d.getId(),
                        RecallEvaluator.getSimilarityScore(d),
                        r != null && r.relevant,
                        r == null ? "MISSING" : r.reason,
                        d.getText() == null ? "" : d.getText()));
            }
            return out;
        }

        // legacy 路径：保留供按 token 计费订阅切回（每对单调用 + 并发）
        List<CompletableFuture<JudgedHit>> futures = hits.stream()
                .map(d -> CompletableFuture.supplyAsync(() -> {
                    LlmJudge.JudgeResult r = llmJudge.judgeWithResult(query.getQuery(), collection, d);
                    return new JudgedHit(
                            d.getId(),
                            RecallEvaluator.getSimilarityScore(d),
                            r.relevant,
                            r.reason,
                            d.getText() == null ? "" : d.getText());
                }, pool))
                .collect(Collectors.toList());

        List<JudgedHit> result = new java.util.ArrayList<>(hits.size());
        for (CompletableFuture<JudgedHit> f : futures) {
            try {
                result.add(f.get());
            } catch (Exception e) {
                log.warn("[llm-judge] queryId={} hit judge future failed: {}",
                        query.getQueryId(), e.toString());
            }
        }
        return result;
    }

    private VectorStore pickVectorStore(String targetCollection) {
        if ("shop_profile_vector".equals(targetCollection)) return shopProfileVectorStore;
        if ("blog_review_vector".equals(targetCollection)) return blogReviewVectorStore;
        if ("knowledge_vector".equals(targetCollection)) return knowledgeVectorStore;
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** 单条 query 的全部 retrieve+judge 详情，写 JSON 用 */
    public static class QueryDetails {
        public final int queryId;
        public final String query;
        public final String collection;
        public final List<String> expectedRelevantIds;
        public final List<HitDetail> hits;

        public QueryDetails(int queryId, String query, String collection,
                            List<String> expectedRelevantIds, List<HitDetail> hits) {
            this.queryId = queryId;
            this.query = query;
            this.collection = collection;
            this.expectedRelevantIds = expectedRelevantIds;
            this.hits = hits;
        }
    }

    public static class HitDetail {
        public final int rank;        // 1-based
        public final String docId;
        public final double similarity;
        public final boolean relevant;
        public final String reason;
        public final String contentPreview;

        public HitDetail(int rank, String docId, double similarity, boolean relevant,
                         String reason, String contentPreview) {
            this.rank = rank;
            this.docId = docId;
            this.similarity = similarity;
            this.relevant = relevant;
            this.reason = reason == null ? "" : reason;
            this.contentPreview = contentPreview;
        }
    }

    /** retrieve 后带 LLM-judge 标签的 hit */
    private static class JudgedHit {
        final String docId;
        final double similarity;
        final boolean relevant;
        final String reason;
        final String content;

        JudgedHit(String docId, double similarity, boolean relevant, String reason, String content) {
            this.docId = docId;
            this.similarity = similarity;
            this.relevant = relevant;
            this.reason = reason == null ? "" : reason;
            this.content = content;
        }
    }

    /** 小批量抽样数据点 */
    public static class SmallBatchSample {
        public final int queryId;
        public final String query;
        public final String collection;
        public final String docId;
        public final double similarity;
        public final boolean relevant;
        public final String reason;
        public final String contentPreview;

        public SmallBatchSample(int queryId, String query, String collection, String docId,
                                 double similarity, boolean relevant, String reason, String contentPreview) {
            this.queryId = queryId;
            this.query = query;
            this.collection = collection;
            this.docId = docId;
            this.similarity = similarity;
            this.relevant = relevant;
            this.reason = reason == null ? "" : reason;
            this.contentPreview = contentPreview;
        }
    }
}
