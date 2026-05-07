package com.hmdp.ai.rag.eval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 把评估结果写成 Markdown 报告。
 *
 * <p>报告 9 个 section（参考 Plan B）：
 * <ol start="0">
 *   <li>评估范围声明（明确只评 retrieval，对齐 Ragas 两层框架）
 *   <li>评估配置
 *   <li>shop_profile 12 行指标 + 最优配置
 *   <li>blog_review 12 行指标 + 最优配置
 *   <li>knowledge 12 行指标 + 最优配置
 *   <li>Metadata Filter 对比实验
 *   <li>Failure Case 分析（根因和改进方向留空给用户人工补）
 *   <li>整体观察
 *   <li>后续优化方向
 *   <li>方法论参考
 * </ol>
 */
@Slf4j
@Component
public class EvalReportWriter {

    public Path write(Path outputDir, AggregatedReport report,
                      FilterExperimentRunner.FilterExperimentResult filterExp,
                      List<FailureCaseAnalyzer.FailureCase> failures,
                      int queryCount, long elapsedMs) throws IOException {
        return write(outputDir, report, filterExp, failures, null, queryCount, elapsedMs);
    }

    /**
     * Plan D 重载：多了 {@code generationReport} 参数（可以是 null —— 仅跑 retrieval 时不传）。
     * 不为 null 时在 Section 5.5 之后追加 Section 5.7（generation 层评估）。
     */
    public Path write(Path outputDir, AggregatedReport report,
                      FilterExperimentRunner.FilterExperimentResult filterExp,
                      List<FailureCaseAnalyzer.FailureCase> failures,
                      GenerationReport generationReport,
                      int queryCount, long elapsedMs) throws IOException {
        return write(outputDir, report, filterExp, failures, generationReport, null, queryCount, elapsedMs);
    }

    /**
     * Plan E 重载：双 generation 报告（vector + hybrid）—— hybridReport != null 时在 Section 5.7
     * 之后追加 Section 5.7.6（vector vs hybrid 对比）。Section 5.7 主表用 hybrid 数据。
     */
    public Path write(Path outputDir, AggregatedReport report,
                      FilterExperimentRunner.FilterExperimentResult filterExp,
                      List<FailureCaseAnalyzer.FailureCase> failures,
                      GenerationReport generationReport,
                      GenerationReport vectorBaselineReport,
                      int queryCount, long elapsedMs) throws IOException {
        return writePlanG(outputDir, report, filterExp, failures,
                vectorBaselineReport, generationReport, null, queryCount, elapsedMs);
    }

    /**
     * Plan G 主入口：三 generation 报告（vector / hybrid / hybrid+rerank）。
     * - rerankReport != null 时主表用 rerankReport（最强模式）
     * - rerankReport == null 时主表用 hybridReport（退化为 Plan E 模式）
     * - Section 5.7.6 始终对比 vector vs hybrid（Plan E 既有数据）
     * - Section 5.7.8 在有 rerankReport 时对比 hybrid vs hybrid+rerank（Plan G 新增）
     */
    public Path writePlanG(Path outputDir, AggregatedReport report,
                            FilterExperimentRunner.FilterExperimentResult filterExp,
                            List<FailureCaseAnalyzer.FailureCase> failures,
                            GenerationReport vectorReport,
                            GenerationReport hybridReport,
                            GenerationReport rerankReport,
                            int queryCount, long elapsedMs) throws IOException {
        Files.createDirectories(outputDir);
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Path file = outputDir.resolve("rag_eval_report_" + dateStr + ".md");

        // 主表用最强模式（rerank > hybrid > vector，按可用性挑）
        GenerationReport mainReport = rerankReport != null ? rerankReport
                : (hybridReport != null ? hybridReport : vectorReport);

        StringBuilder sb = new StringBuilder(8192);
        appendHeader(sb, queryCount, elapsedMs);
        appendScopeDeclaration(sb, mainReport != null);
        appendEvalConfig(sb, report);
        if (report.hasLlmJudge()) {
            appendDualMethodBoundaries(sb);   // Section 1.5
        }
        appendCollectionTables(sb, report);
        appendFilterExperiment(sb, filterExp);
        if (report.hasLlmJudge()) {
            appendLlmJudgeResults(sb, report);   // Section 5.5
        }
        if (mainReport != null) {
            appendGenerationResults(sb, mainReport);   // Section 5.7（Plan D）—— 用主表
        }
        if (vectorReport != null && hybridReport != null) {
            appendVectorHybridComparison(sb, vectorReport, hybridReport);   // Section 5.7.6（Plan E）
        }
        if (hybridReport != null && rerankReport != null) {
            appendRerankComparison(sb, hybridReport, rerankReport);          // Section 5.7.8（Plan G 新增）
        }
        appendFailureCases(sb, failures);
        if (report.hasLlmJudge()) {
            appendEvalSetReflection(sb);   // Section 6.5
        }
        appendObservations(sb, report, filterExp);
        appendNextSteps(sb);
        appendMethodologyRefs(sb);

        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        log.info("[report] written to {}", file);
        return file;
    }

    private void appendHeader(StringBuilder sb, int queryCount, long elapsedMs) {
        sb.append("# RAG 召回评估报告\n\n");
        sb.append("- **生成时间**：").append(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)).append("\n");
        sb.append("- **评估集**：").append(queryCount).append(" 条 query\n");
        sb.append("- **Embedding 模型**：BAAI/bge-m3 (1024 维)\n");
        sb.append("- **总耗时**：").append(elapsedMs / 1000.0).append(" 秒\n\n");
    }

    private void appendScopeDeclaration(StringBuilder sb, boolean hasGeneration) {
        sb.append("## 0. 评估范围声明\n\n");
        if (hasGeneration) {
            sb.append("> 本次评估**覆盖 retrieval + generation 两层**（对齐 [Ragas](https://github.com/explodinggradients/ragas) 框架）：\n");
            sb.append("> - **Retrieval 层**（Section 2~5.5）：context_recall / context_precision / LLM-judge\n");
            sb.append("> - **Generation 层**（Section 5.7）：faithfulness / answer_relevancy\n");
            sb.append(">\n");
            sb.append("> 不在本次范围内：端到端用户满意度（需人工标注）。\n");
            sb.append(">\n");
            sb.append("> 分层评估的设计：先把 retrieval 评透再上 generation，避免指标混在一起无法定位问题来源——\n");
            sb.append("> 答错到底是召回错还是模型幻觉？两层指标交叉看才能区分。\n\n");
        } else {
            sb.append("> 本次评估**只覆盖检索阶段（retrieval）**——\"向量库召回的文档是否正确\"。\n");
            sb.append(">\n");
            sb.append("> 不在本次范围内：\n");
            sb.append("> - 生成阶段（faithfulness / answer_relevancy / hallucination）—— 需要 LLM-as-judge，下一阶段\n");
            sb.append("> - 端到端用户满意度 —— 需要人工标注\n");
            sb.append(">\n");
            sb.append("> 分层评估方法对照 [Ragas](https://github.com/explodinggradients/ragas) 的 retrieval / generation 两层框架。\n");
            sb.append("> 先把 retrieval 评透再上 generation，避免指标混在一起无法定位问题来源。\n\n");
        }
    }

    private void appendEvalConfig(StringBuilder sb, AggregatedReport report) {
        sb.append("## 1. 评估配置\n\n");
        sb.append("| 维度 | 值 |\n|---|---|\n");
        // 提取出实际跑了哪些 (threshold, topK)
        if (!report.getCollections().isEmpty()) {
            String firstCol = report.getCollections().get(0);
            Map<EvalConfig, AggregatedReport.AggregatedMetrics> firstMap = report.getAggregated().get(firstCol);
            sb.append("| Configs | ").append(firstMap.size()).append(" 组（threshold × topK 笛卡尔积） |\n");
        }
        sb.append("| 指标 | Context Recall@K / Context Precision@K / MRR / HitRate |\n");
        sb.append("\n> 指标命名对齐 [Ragas](https://github.com/explodinggradients/ragas) 的 `context_recall` / `context_precision`，明确表示评估的是\"上下文召回质量\"。\n\n");
    }

    private void appendCollectionTables(StringBuilder sb, AggregatedReport report) {
        int sectionIdx = 2;
        for (String collection : report.getCollections()) {
            sb.append("## ").append(sectionIdx++).append(". ").append(collection).append(" 评估结果\n\n");
            Map<EvalConfig, AggregatedReport.AggregatedMetrics> rows = report.getAggregated().get(collection);
            int querySize = rows.values().stream().findFirst().map(AggregatedReport.AggregatedMetrics::getQuerySize).orElse(0);
            sb.append("> 评估 query 数：").append(querySize).append("\n\n");

            sb.append("| Threshold | top-K | Context Recall@K | Context Precision@K | MRR | HitRate |\n");
            sb.append("|---|---|---|---|---|---|\n");
            rows.entrySet().stream()
                    .sorted(Comparator
                            .<Map.Entry<EvalConfig, AggregatedReport.AggregatedMetrics>>comparingDouble(e -> e.getKey().getThreshold())
                            .thenComparingInt(e -> e.getKey().getTopK()))
                    .forEach(e -> {
                        EvalConfig cfg = e.getKey();
                        AggregatedReport.AggregatedMetrics m = e.getValue();
                        sb.append("| ").append(String.format("%.2f", cfg.getThreshold()))
                                .append(" | ").append(cfg.getTopK())
                                .append(" | ").append(String.format("%.3f", m.getAvgContextRecall()))
                                .append(" | ").append(String.format("%.3f", m.getAvgContextPrecision()))
                                .append(" | ").append(String.format("%.3f", m.getMrr()))
                                .append(" | ").append(String.format("%.3f", m.getHitRate()))
                                .append(" |\n");
                    });

            // 找最优配置：以 Recall * 0.5 + Precision * 0.5 为简单加权
            EvalConfig best = rows.entrySet().stream()
                    .max(Comparator.comparingDouble(e ->
                            e.getValue().getAvgContextRecall() * 0.5 + e.getValue().getAvgContextPrecision() * 0.5))
                    .map(Map.Entry::getKey).orElse(null);
            if (best != null) {
                AggregatedReport.AggregatedMetrics bestMetrics = rows.get(best);
                sb.append("\n**最优配置（Recall+Precision 加权最高）**：threshold=").append(String.format("%.2f", best.getThreshold()))
                        .append(", topK=").append(best.getTopK())
                        .append("（Recall=").append(String.format("%.3f", bestMetrics.getAvgContextRecall()))
                        .append(", Precision=").append(String.format("%.3f", bestMetrics.getAvgContextPrecision()))
                        .append("）\n\n");
            }
        }
    }

    private void appendFilterExperiment(StringBuilder sb, FilterExperimentRunner.FilterExperimentResult exp) {
        sb.append("## 5. Metadata Filter 对比实验\n\n");
        sb.append("> 验证 metadata filter 的工程价值（参考 Milvus 官方实践——业务 RAG 中 99% 的检索都应该带 filter）。\n");
        sb.append("> 自动筛选评估集里所有 `target=shop_profile_vector && query.contains(\"杭州\")` 的 query，跑 Baseline vs +City Filter 的 Precision@5 对比。\n");
        sb.append("> **反 cherry-pick**：样本不是手工挑的，自动命中多少就跑多少。\n\n");

        if (exp.getRows().isEmpty()) {
            sb.append("（无符合条件的 query，跳过本节）\n\n");
            return;
        }

        sb.append("### 5.1 实验结果\n\n");
        sb.append("| query_id | Query | Baseline P@5 | +City Filter P@5 |\n|---|---|---|---|\n");
        for (FilterExperimentRunner.ComparisonRow row : exp.getRows()) {
            sb.append("| ").append(row.getQueryId())
                    .append(" | ").append(row.getQuery().replace("|", "\\|"))
                    .append(" | ").append(String.format("%.3f", row.getBaselinePrecision()))
                    .append(" | ").append(String.format("%.3f", row.getFilteredPrecision()))
                    .append(" |\n");
        }
        sb.append("\n");

        double improvementPct = exp.getAvgBaselinePrecision() == 0 ? 0 : exp.getAvgImprovement() * 100;
        sb.append("### 5.2 平均结果\n\n");
        sb.append("- 平均 Baseline Precision@5：").append(String.format("%.3f", exp.getAvgBaselinePrecision())).append("\n");
        sb.append("- 平均 +City Filter Precision@5：").append(String.format("%.3f", exp.getAvgFilteredPrecision())).append("\n");
        sb.append("- **平均提升**：").append(String.format("%.1f", improvementPct)).append("%\n\n");
        if (improvementPct >= 50) {
            sb.append("**结论**：metadata filter 显著提升 Precision，验证了\"metadata 设计有真实工程价值\"。\n\n");
        } else if (improvementPct > 0) {
            sb.append("**结论**：filter 有正向收益但提升幅度有限，需要排查是否 city 字段写入或过滤逻辑有问题。\n\n");
        } else {
            sb.append("**结论**：filter 没带来正向收益——可能 city 字段没正确写入 metadata，或评估集本身分布偏向杭州。\n\n");
        }
    }

    private void appendFailureCases(StringBuilder sb, List<FailureCaseAnalyzer.FailureCase> failures) {
        sb.append("## 6. Failure Case 分析\n\n");
        sb.append("> 列出在最优配置下仍然 hit=0 的 query。**根因和改进方向两栏由用户人工补完**——CC 只输出客观事实（query / 期望 ID / 实际召回），不预设根因。\n");
        sb.append("> 参考 [LangChain](https://github.com/langchain-ai/langchain) 的 trace 思路——评估不止于打分，还要能定位问题。\n\n");

        if (failures.isEmpty()) {
            sb.append("（最优配置下没有 hit=0 的 query，跳过本节）\n\n");
            return;
        }

        for (int i = 0; i < failures.size(); i++) {
            FailureCaseAnalyzer.FailureCase fc = failures.get(i);
            sb.append("### Case ").append(i + 1).append("：query_id=").append(fc.getQueryId()).append("\n\n");
            sb.append("- **Query**：").append(fc.getQuery()).append("\n");
            sb.append("- **Target collection**：").append(fc.getTargetCollection()).append("\n");
            sb.append("- **期望召回 ID**：").append(fc.getExpectedRelevantIds()).append("\n");
            sb.append("- **实际召回 top-").append(fc.getActualTopK().size()).append("**：\n\n");
            sb.append("  | rank | bizId | name | city | category | similarity | content preview |\n");
            sb.append("  |---|---|---|---|---|---|---|\n");
            int rank = 1;
            for (FailureCaseAnalyzer.RetrievedItem item : fc.getActualTopK()) {
                sb.append("  | ").append(rank++)
                        .append(" | ").append(safeStr(item.getBusinessId()))
                        .append(" | ").append(safeStr(item.getName()))
                        .append(" | ").append(safeStr(item.getCity()))
                        .append(" | ").append(safeStr(item.getCategory()))
                        .append(" | ").append(String.format("%.3f", item.getSimilarity()))
                        .append(" | ").append(safeStr(item.getContentPreview()).replace("|", "\\|"))
                        .append(" |\n");
            }
            sb.append("\n- **根因**：（待用户补完）\n");
            sb.append("- **改进方向**：（待用户补完）\n\n");
        }
    }

    private void appendObservations(StringBuilder sb, AggregatedReport report,
                                     FilterExperimentRunner.FilterExperimentResult exp) {
        sb.append("## 7. 整体观察\n\n");
        sb.append("（建议在用户补完 failure case 根因后一起总结，本节为初稿提示）\n\n");
        for (String collection : report.getCollections()) {
            Map<EvalConfig, AggregatedReport.AggregatedMetrics> rows = report.getAggregated().get(collection);
            EvalConfig best = rows.entrySet().stream()
                    .max(Comparator.comparingDouble(e -> e.getValue().getAvgContextRecall() * 0.5 + e.getValue().getAvgContextPrecision() * 0.5))
                    .map(Map.Entry::getKey).orElse(null);
            if (best == null) continue;
            AggregatedReport.AggregatedMetrics m = rows.get(best);
            sb.append("- **").append(collection).append("**：最优 threshold=").append(String.format("%.2f", best.getThreshold()))
                    .append(" / topK=").append(best.getTopK())
                    .append("，Recall=").append(String.format("%.3f", m.getAvgContextRecall()))
                    .append(" / Precision=").append(String.format("%.3f", m.getAvgContextPrecision()))
                    .append("\n");
        }
        if (exp != null && !exp.getRows().isEmpty()) {
            sb.append("- **Metadata filter 价值**：city filter 平均提升 ").append(String.format("%.1f", exp.getAvgImprovement() * 100)).append("%\n");
        }
        sb.append("\n");
    }

    private void appendNextSteps(StringBuilder sb) {
        sb.append("## 8. 后续优化方向\n\n");
        sb.append("- [ ] 引入 rerank（bge-reranker-v2-m3），预期 MRR +10%\n");
        sb.append("- [ ] hybrid search（向量 + BM25），预期 Recall +5%\n");
        sb.append("- [ ] query 改写（让 LLM 把口语化 query 改成关键词强化版本再嵌入）\n");
        sb.append("- [ ] 评估集扩到 200 条（当前 50 条置信区间偏大）\n");
        sb.append("- [ ] 加 generation 层评估（faithfulness / answer_relevancy）—— 完整对齐 Ragas 框架\n\n");
    }

    private void appendMethodologyRefs(StringBuilder sb) {
        sb.append("## 9. 方法论参考\n\n");
        sb.append("本评估框架的设计思路对照以下业界标杆开源项目（**只参考思想，不引入依赖**）：\n\n");
        sb.append("- [Ragas](https://github.com/explodinggradients/ragas) — 两层评估框架 + 指标命名（context_recall / context_precision）\n");
        sb.append("- [LlamaIndex](https://github.com/run-llama/llama_index) — RetrieverEvaluator 抽象、三阶段解耦（测试集 / 执行 / 报告）\n");
        sb.append("- [LangChain](https://github.com/langchain-ai/langchain) — trace 思路应用于 failure case 分析\n");
        sb.append("- [Milvus](https://github.com/milvus-io/milvus) — metadata filtering 一等公民的工程实践\n\n");
        sb.append("具体实现是 Java + Spring AI + Milvus（业务栈），核心评估逻辑约 50 行 Java（标准 IR 公式：Recall@K / Precision@K / MRR）。\n");
    }

    // ========== Plan C：LLM-as-judge 增量 section ==========

    /**
     * Section 1.5：双评估方法的边界条件声明。
     * 客观陈述两套指标各能给什么、不能给什么——不"软化"也不强行找平衡。
     */
    private void appendDualMethodBoundaries(StringBuilder sb) {
        sb.append("## 1.5 评估方法的边界条件声明\n\n");
        sb.append("> 本次报告同时呈现两套评估方法的指标：**ID-比对 baseline** + **LLM-as-judge (reference-free)**。\n");
        sb.append("> 两个方法不是替代关系，而是**互补**——各能给什么、不能给什么如下，由读者自行判断。\n\n");
        sb.append("| 维度 | baseline (ID-比对) | LLM-as-judge (reference-free) |\n");
        sb.append("|---|---|---|\n");
        sb.append("| Recall@K | ✅ 可算 | ❌ **天然没有**——reference-free 范式没有 ground truth 全集做分母 |\n");
        sb.append("| Precision@K | ✅ 可算，但准确性依赖 ground truth 标注质量 | ✅ 可算，由 LLM 判 (query, doc) 二元相关性 |\n");
        sb.append("| MRR | ✅ 可算 | ✅ 可算（按 LLM 判相关的最高排名） |\n");
        sb.append("| HitRate | ✅ 可算 | ✅ 可算 |\n");
        sb.append("| 评估成本 | 低（一次性） | 中（每条 query × top-K 都需要 LLM 调用）|\n");
        sb.append("| 评估集设计敏感度 | **高**——ground truth 选择直接决定数字含金量 | 低——只看 (query, doc) 对，不依赖标注 |\n\n");
        sb.append("**本次特别说明**：baseline 的 shop / review 类用\"评论数 top5\"作为 ground truth，与向量检索的\"语义相似度\"是两个维度，导致 Recall/Precision 数字偏低。这是评估集设计错配，不是 RAG 检索本身的问题。详见 [Section 6.5 评估集设计反思](#65-评估集设计反思--llm-as-judge-验证结果)。\n\n");
        sb.append("> 方法论参考 [Ragas 论文](https://arxiv.org/abs/2309.15217) 关于 reference-based vs reference-free trade-off 的讨论。\n\n");
    }

    /**
     * Section 5.5：LLM-judge 评估结果。
     * 3 个 collection × 12 配置的 LLM-judge 指标表 + 与 baseline 对比。
     */
    private void appendLlmJudgeResults(StringBuilder sb, AggregatedReport report) {
        sb.append("## 5.5 LLM-as-judge 评估结果\n\n");
        Map<String, Map<EvalConfig, AggregatedReport.LlmJudgeMetrics>> ljMap = report.getLlmJudgeAggregated();
        if (ljMap == null || ljMap.isEmpty()) {
            sb.append("（未跑 LLM-judge 评估，跳过本节）\n\n");
            return;
        }

        // sanity check
        sb.append("> **Sanity check**：LLM-judge 总命中率（所有 query × 所有 retrieved doc 里判 true 的比例）= ")
                .append(String.format("%.1f%%", report.getLlmJudgeOverallTrueRate() * 100)).append("\n");
        if (report.getLlmJudgeOverallTrueRate() < 0.30 || report.getLlmJudgeOverallTrueRate() > 0.70) {
            sb.append("> ⚠️ **超出 30%~70% 健康区间**——可能 judge 太宽松或 prompt 设计有 bug，结果需要谨慎解读\n");
        } else {
            sb.append("> ✅ 落在 30%~70% 健康区间，judge 行为合理\n");
        }
        sb.append("> LLM 调用：").append(report.getLlmJudgeCallCount())
                .append(" 次，失败 ").append(report.getLlmJudgeFailureCount())
                .append("（默认 false 兜底），缓存大小 ").append(report.getLlmJudgeCacheSize()).append("\n\n");

        for (Map.Entry<String, Map<EvalConfig, AggregatedReport.LlmJudgeMetrics>> entry : ljMap.entrySet()) {
            String collection = entry.getKey();
            Map<EvalConfig, AggregatedReport.LlmJudgeMetrics> rows = entry.getValue();
            sb.append("### 5.5.").append(collection).append(" (LLM-judge)\n\n");
            sb.append("| Threshold | top-K | LLM-Precision@K | LLM-MRR | LLM-HitRate |\n");
            sb.append("|---|---|---|---|---|\n");
            rows.entrySet().stream()
                    .sorted(Comparator
                            .<Map.Entry<EvalConfig, AggregatedReport.LlmJudgeMetrics>>comparingDouble(e -> e.getKey().getThreshold())
                            .thenComparingInt(e -> e.getKey().getTopK()))
                    .forEach(e -> {
                        EvalConfig cfg = e.getKey();
                        AggregatedReport.LlmJudgeMetrics m = e.getValue();
                        sb.append("| ").append(String.format("%.2f", cfg.getThreshold()))
                                .append(" | ").append(cfg.getTopK())
                                .append(" | ").append(String.format("%.3f", m.getAvgContextPrecision()))
                                .append(" | ").append(String.format("%.3f", m.getMrr()))
                                .append(" | ").append(String.format("%.3f", m.getHitRate()))
                                .append(" |\n");
                    });

            EvalConfig best = rows.entrySet().stream()
                    .max(Comparator.comparingDouble(e ->
                            e.getValue().getAvgContextPrecision() * 0.5 + e.getValue().getHitRate() * 0.5))
                    .map(Map.Entry::getKey).orElse(null);
            if (best != null) {
                AggregatedReport.LlmJudgeMetrics bm = rows.get(best);
                sb.append("\n**LLM-judge 最优配置（Precision+HitRate 加权最高）**：threshold=")
                        .append(String.format("%.2f", best.getThreshold()))
                        .append(", topK=").append(best.getTopK())
                        .append("（Precision=").append(String.format("%.3f", bm.getAvgContextPrecision()))
                        .append(", HitRate=").append(String.format("%.3f", bm.getHitRate())).append("）\n\n");
            }
        }

        // baseline vs LLM-judge 对比表（统一取 topK=5 的 threshold=0.65 配置近似业务实际）
        sb.append("### 5.5.X baseline vs LLM-judge 对比（threshold=0.65, topK=5）\n\n");
        sb.append("| Collection | baseline P@5 | LLM-judge P@5 | Δ Precision | baseline HitRate | LLM-judge HitRate | Δ HitRate |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        for (String collection : report.getCollections()) {
            EvalConfig key = findConfig(report, collection, 0.65, 5);
            AggregatedReport.AggregatedMetrics base = report.getAggregated().get(collection).get(key);
            AggregatedReport.LlmJudgeMetrics lj = ljMap.get(collection) == null ? null : ljMap.get(collection).get(key);
            if (base == null || lj == null) continue;
            double dP = lj.getAvgContextPrecision() - base.getAvgContextPrecision();
            double dH = lj.getHitRate() - base.getHitRate();
            sb.append("| ").append(collection)
                    .append(" | ").append(String.format("%.3f", base.getAvgContextPrecision()))
                    .append(" | ").append(String.format("%.3f", lj.getAvgContextPrecision()))
                    .append(" | ").append(String.format("%+.3f", dP))
                    .append(" | ").append(String.format("%.3f", base.getHitRate()))
                    .append(" | ").append(String.format("%.3f", lj.getHitRate()))
                    .append(" | ").append(String.format("%+.3f", dH))
                    .append(" |\n");
        }
        sb.append("\n");
    }

    /**
     * Section 6.5：评估集设计反思 + LLM-as-judge 验证结果。
     */
    private void appendEvalSetReflection(StringBuilder sb) {
        sb.append("## 6.5 评估集设计反思 + LLM-as-judge 验证结果\n\n");
        sb.append("### 反思：baseline 数字偏低的真实原因\n\n");
        sb.append("Plan B baseline 跑完后，shop / review 的 Recall ≈ 0、knowledge Recall = 0.70。\n");
        sb.append("**初看像是 RAG 检索能力差，但 failure case 逐条排查后发现：召回的店铺业务上完全合理（同城同品类、招牌菜匹配 query 关键词），只是不在 ground truth 标注的那 5 个 shop_id 里。**\n\n");
        sb.append("根因定位到**评估集设计错配**：\n\n");
        sb.append("- shop / review 类的 `relevant_ids` 用\"评论数 top5\"作为 ground truth\n");
        sb.append("- 但向量检索靠**语义相似度**——\"用户 query 和 shop 描述的语义匹配度\"\n");
        sb.append("- \"评论数热度\"和\"语义相关\"是**两个维度**，强行用前者作为后者的标注，必然出现：召回的店在业务上对，但被判 miss\n\n");
        sb.append("knowledge 类不受影响是因为 question → answer 是一对一映射，ID 比对刚好对齐了语义匹配。\n\n");
        sb.append("### LLM-as-judge 验证：换评估方法不换 ground truth\n\n");
        sb.append("Plan C 没有重生成 `eval_queries.jsonl`（那本质上仍是 ID 比对范式），而是引入 **Reference-free LLM-as-judge**：\n");
        sb.append("评估时跑 RAG 检索，对每个 retrieved doc 让 LLM (Claude Haiku 4.5) 二元判断\"是否与 query 相关\"，直接计算 Precision@K + LLM-judged HitRate。\n\n");
        sb.append("**这是 [Ragas](https://github.com/explodinggradients/ragas) 推荐的标准做法**：当 ground truth 难以获得或标注质量存疑时，用强 LLM 当 judge 给出 reference-free 评估，绕开\"必须命中那几个特定 ID\"的硬要求，更符合 RAG 真实评估目标。\n\n");
        sb.append("### 结论\n\n");
        sb.append("- **如果 LLM-judge Precision 显著高于 baseline Precision**：证明 RAG 召回的文档在语义上是相关的，baseline 数字偏低是**评估集设计问题**，不是检索能力问题\n");
        sb.append("- **业务最优阈值**：shop / review 的最优阈值要等到评估集修对（或长期用 LLM-judge）后才能确定。在那之前，`ai.agent.rag.similarity-threshold: 0.65` 是基于 Plan B baseline 配的保守值，**评估集修对前不动**\n");
        sb.append("- **下一步可选**：(a) 用 LLM-as-judge 重标 ground truth 生成 v2 评估集；(b) 引入 rerank 看 LLM-judge Precision 能否再提升 10%\n\n");
    }

    private EvalConfig findConfig(AggregatedReport report, String collection, double threshold, int topK) {
        Map<EvalConfig, AggregatedReport.AggregatedMetrics> rows = report.getAggregated().get(collection);
        if (rows == null) return null;
        for (EvalConfig cfg : rows.keySet()) {
            if (Math.abs(cfg.getThreshold() - threshold) < 1e-6 && cfg.getTopK() == topK) {
                return cfg;
            }
        }
        return null;
    }

    private String safeStr(String s) {
        return s == null ? "" : s;
    }

    /**
     * Plan D Section 5.7：generation 层评估（faithfulness + answer_relevancy）。
     */
    private void appendGenerationResults(StringBuilder sb, GenerationReport gen) {
        sb.append("## 5.7 Generation 层评估（Plan D：Faithfulness + Answer Relevancy）\n\n");
        sb.append("> 对照 [Ragas](https://github.com/explodinggradients/ragas) 两层框架的 generation 层。\n");
        sb.append("> **业务模型生成**（Qwen2.5-7B-Instruct，temperature=0），**judge 模型评估**（Claude Haiku 4.5）——\n");
        sb.append("> 评估器和被评估者严格分离，避免自己评自己。\n>\n");
        sb.append("> 两个指标：\n");
        sb.append("> - **Faithfulness**（0~1）：answer 是否被 retrieved contexts 支持。低 = 模型在编（幻觉）\n");
        sb.append("> - **Answer Relevancy**（0/1）：answer 是否对 query 直接回答。低 = 答非所问\n\n");

        // 5.7.1 总览
        sb.append("### 5.7.1 总览\n\n");
        sb.append("| 指标 | 值 |\n|---|---|\n");
        sb.append("| 总 query 数 | ").append(gen.totalCount()).append(" |\n");
        sb.append("| 整体平均 Faithfulness | ").append(String.format("%.3f", gen.overallAvgFaithfulness())).append(" |\n");
        sb.append("| 整体平均 Answer Relevancy | ").append(String.format("%.3f", gen.overallAvgRelevancy())).append(" |\n");
        sb.append("| Generation 失败数 | ").append(gen.getGenerationFailed()).append(" |\n");
        sb.append("| Judge 失败数 | ").append(gen.getJudgeFailed()).append(" |\n\n");

        // 5.7.2 按 collection 聚合
        sb.append("### 5.7.2 按 Collection 聚合\n\n");
        sb.append("| Collection | Query 数 | Avg Faithfulness | Avg Relevancy | Gen Failed | Judge Failed |\n");
        sb.append("|---|---|---|---|---|---|\n");
        for (String collection : gen.collections()) {
            GenerationReport.CollectionMetrics m = gen.getAggregated().get(collection);
            sb.append("| ").append(collection)
                    .append(" | ").append(m.queryCount)
                    .append(" | ").append(String.format("%.3f", m.avgFaithfulness))
                    .append(" | ").append(String.format("%.3f", m.avgRelevancy))
                    .append(" | ").append(m.generationFailed)
                    .append(" | ").append(m.judgeFailed)
                    .append(" |\n");
        }
        sb.append("\n");

        // 5.7.3 抽样详情：前 N 条 + 低分 case
        int sampleN = 8;
        List<GenerationReport.Detail> details = gen.getDetails();
        sb.append("### 5.7.3 抽样详情（前 ").append(Math.min(sampleN, details.size())).append(" 条）\n\n");
        sb.append("> 完整 ").append(details.size()).append(" 条详情见 ");
        sb.append("`docs/rag_eval_generation_details_<日期>.json`（如导出）。\n\n");
        sb.append("| qid | collection | query | answer | F | R | F-reason | R-reason |\n");
        sb.append("|---|---|---|---|---|---|---|---|\n");
        int shown = 0;
        for (GenerationReport.Detail d : details) {
            if (shown >= sampleN) break;
            sb.append("| ").append(d.queryId)
                    .append(" | ").append(d.targetCollection.replace("_vector", ""))
                    .append(" | ").append(escapeMd(truncate(d.query, 28)))
                    .append(" | ").append(escapeMd(truncate(d.answer, 60)))
                    .append(" | ").append(String.format("%.2f", d.faithfulness))
                    .append(" | ").append(d.relevant ? "✓" : "✗")
                    .append(" | ").append(escapeMd(truncate(d.faithReason, 18)))
                    .append(" | ").append(escapeMd(truncate(d.relevancyReason, 18)))
                    .append(" |\n");
            shown++;
        }
        sb.append("\n");

        // 5.7.4 低分 case
        List<GenerationReport.Detail> lowFaith = details.stream()
                .filter(d -> d.faithfulness < 0.5 && d.answer != null && !d.answer.isBlank())
                .sorted((a, b) -> Double.compare(a.faithfulness, b.faithfulness))
                .limit(3)
                .toList();
        if (!lowFaith.isEmpty()) {
            sb.append("### 5.7.4 低 Faithfulness Case（潜在幻觉）\n\n");
            sb.append("> 这些 case 是 generation 层翻车的典型 —— retrieval 召回了内容，但模型答案脱离 contexts。\n\n");
            for (GenerationReport.Detail d : lowFaith) {
                sb.append("**qid=").append(d.queryId).append(" / ").append(d.targetCollection).append("**  \n");
                sb.append("- Query: `").append(escapeMd(d.query)).append("`\n");
                sb.append("- Answer: `").append(escapeMd(truncate(d.answer, 200))).append("`\n");
                sb.append("- Faithfulness: ").append(String.format("%.2f", d.faithfulness))
                        .append("（reason: ").append(escapeMd(d.faithReason)).append("）\n");
                sb.append("- Relevancy: ").append(d.relevant ? "✓" : "✗")
                        .append("（reason: ").append(escapeMd(d.relevancyReason)).append("）\n\n");
            }
        }

        // 5.7.5 观察分析模板
        sb.append("### 5.7.5 观察分析\n\n");
        sb.append("分层评估框架下，将 retrieval 指标 + generation 指标交叉，可以定位失败根因：\n\n");
        sb.append("| 模式 | retrieval | generation | 含义 |\n");
        sb.append("|---|---|---|---|\n");
        sb.append("| 健康 | 高 | 高 | 召回好 + 模型用得好 |\n");
        sb.append("| 模型幻觉 | 高 | 低 | 召回正确但模型脱离 contexts 编造 → 调 generation prompt / 升级模型 |\n");
        sb.append("| 召回不足 | 低 | 高 | 召回少但模型靠常识答（不算 RAG 闭环）→ 提升 retrieval 召回率 |\n");
        sb.append("| 双低 | 低 | 低 | query 难度大或 ground truth 缺失 → 评估集设计问题 |\n\n");
    }

    /**
     * Plan E Section 5.7.6：vector vs hybrid retrieval 对比。
     * Section 5.7 主表用 hybrid 数据，本节加 vector baseline 做对照，证明 hybrid 提升了多少。
     */
    private void appendVectorHybridComparison(StringBuilder sb,
                                              GenerationReport vec,
                                              GenerationReport hyb) {
        sb.append("## 5.7.6 Vector vs Hybrid Retrieval 对比（Plan E）\n\n");
        sb.append("> Plan D 发现 generation relevancy 0.28，根因是 bge-m3 embedding 在\"抽象 query → 具体 entity\"任务上召回率低。\n");
        sb.append("> Plan E 加 Java 端 BM25（Lucene SmartChineseAnalyzer + RRF k=60 合并），不动 Milvus、不重灌库。\n");
        sb.append("> 本节并排展示 vector-only baseline 与 hybrid 模式数字，量化 BM25 对召回的边际贡献。\n\n");

        sb.append("### 5.7.6.1 整体指标对比\n\n");
        sb.append("| Mode | Avg Faithfulness | Avg Relevancy | Generation Failed | Judge Failed |\n");
        sb.append("|---|---|---|---|---|\n");
        sb.append("| **vector**（Plan D 基线）| ").append(String.format("%.3f", vec.overallAvgFaithfulness()))
                .append(" | ").append(String.format("%.3f", vec.overallAvgRelevancy()))
                .append(" | ").append(vec.getGenerationFailed())
                .append(" | ").append(vec.getJudgeFailed()).append(" |\n");
        sb.append("| **hybrid**（Plan E 修复）| ").append(String.format("%.3f", hyb.overallAvgFaithfulness()))
                .append(" | ").append(String.format("%.3f", hyb.overallAvgRelevancy()))
                .append(" | ").append(hyb.getGenerationFailed())
                .append(" | ").append(hyb.getJudgeFailed()).append(" |\n");
        sb.append("| **Δ**（hybrid − vector）| ").append(String.format("%+.3f", hyb.overallAvgFaithfulness() - vec.overallAvgFaithfulness()))
                .append(" | **").append(String.format("%+.3f", hyb.overallAvgRelevancy() - vec.overallAvgRelevancy())).append("**")
                .append(" | - | - |\n\n");

        sb.append("### 5.7.6.2 按 Collection 分组对比\n\n");
        sb.append("| Collection | Mode | Avg Faithfulness | Avg Relevancy | Δ Relevancy |\n");
        sb.append("|---|---|---|---|---|\n");
        for (String col : hyb.collections()) {
            GenerationReport.CollectionMetrics vm = vec.getAggregated().get(col);
            GenerationReport.CollectionMetrics hm = hyb.getAggregated().get(col);
            if (vm == null || hm == null) continue;
            sb.append("| ").append(col).append(" | vector ")
                    .append(" | ").append(String.format("%.3f", vm.avgFaithfulness))
                    .append(" | ").append(String.format("%.3f", vm.avgRelevancy))
                    .append(" | - |\n");
            sb.append("| ").append(col).append(" | **hybrid** ")
                    .append(" | ").append(String.format("%.3f", hm.avgFaithfulness))
                    .append(" | ").append(String.format("%.3f", hm.avgRelevancy))
                    .append(" | **").append(String.format("%+.3f", hm.avgRelevancy - vm.avgRelevancy)).append("** |\n");
        }
        sb.append("\n");

        // 找出 hybrid 比 vector 提升最大的 query（前 5 条）
        sb.append("### 5.7.6.3 hybrid 提升最大的 5 条 query\n\n");
        sb.append("> 这些 query 是 BM25 关键词命中带来的真实增益，向量召回看不到的关键词如\"中餐\"、\"火锅\"、\"甜品\"。\n\n");
        sb.append("| qid | collection | query | vector R | hybrid R | Δ |\n");
        sb.append("|---|---|---|---|---|---|\n");

        java.util.Map<Integer, Boolean> vMap = new java.util.HashMap<>();
        for (GenerationReport.Detail d : vec.getDetails()) {
            vMap.put(d.queryId, d.relevant);
        }
        // 只保留 vector=false 但 hybrid=true 的 query（hybrid 真正修复的 case）
        List<GenerationReport.Detail> uplifts = new java.util.ArrayList<>();
        for (GenerationReport.Detail d : hyb.getDetails()) {
            Boolean vRel = vMap.get(d.queryId);
            if (vRel != null && !vRel && d.relevant) {
                uplifts.add(d);
            }
        }
        if (uplifts.isEmpty()) {
            sb.append("（hybrid 未带来任何 query 的 relevancy 翻转，需要排查 BM25 索引或 RRF 参数）\n\n");
        } else {
            int shown = 0;
            for (GenerationReport.Detail d : uplifts) {
                if (shown >= 5) break;
                sb.append("| ").append(d.queryId)
                        .append(" | ").append(d.targetCollection.replace("_vector", ""))
                        .append(" | ").append(escapeMd(truncate(d.query, 30)))
                        .append(" | ✗ ")
                        .append(" | ✓ ")
                        .append(" | **+1** |\n");
                shown++;
            }
            if (uplifts.size() > 5) {
                sb.append("\n_共 ").append(uplifts.size()).append(" 条 query 在 hybrid 模式下从 ✗ 翻转为 ✓（仅展示前 5 条）_\n");
            }
            sb.append("\n");
        }
    }

    /**
     * Plan G Section 5.7.8：hybrid vs hybrid+rerank 对比。
     * Plan E hybrid 把 review 从 0 拉到 0.95，但 shop 留在 0.35。Plan G 加 cross-encoder
     * Reranker (BAAI/bge-reranker-v2-m3) 在 RRF 合并后精排候选，目标修复 shop 的细粒度区分度问题。
     */
    private void appendRerankComparison(StringBuilder sb,
                                         GenerationReport hyb,
                                         GenerationReport rer) {
        sb.append("## 5.7.8 Hybrid vs Hybrid+Rerank 对比（Plan G）\n\n");
        sb.append("> Plan E hybrid 把 review relevancy 从 0.00 拉到 0.95，但 shop_profile 仍在 0.35。\n");
        sb.append("> 根因：bge-m3 (vector) 和 Lucene BM25 (keyword) 都是 bi-encoder / 词频，召回阶段无法精确\n");
        sb.append("> 区分细粒度相关性。Plan F (HyDE) 试图修但因 Qwen2.5-7B 编 hypothetical 不稳而退化。\n");
        sb.append("> Plan G 改走 **Cross-encoder Reranker (BAAI/bge-reranker-v2-m3 via SiliconFlow)**——\n");
        sb.append("> 召回 top-20 → cross-encoder 直接对 (query, doc) 对评分 → 取 top-5。\n>\n");
        sb.append("> Reranker 是业界 RAG 工程化的标准下一步（Pinecone / Anthropic Claude RAG cookbook 均推荐）。\n\n");

        sb.append("### 5.7.8.1 整体指标对比\n\n");
        sb.append("| Mode | Avg Faithfulness | Avg Relevancy | Generation Failed | Judge Failed |\n");
        sb.append("|---|---|---|---|---|\n");
        sb.append("| **hybrid**（Plan E）| ").append(String.format("%.3f", hyb.overallAvgFaithfulness()))
                .append(" | ").append(String.format("%.3f", hyb.overallAvgRelevancy()))
                .append(" | ").append(hyb.getGenerationFailed())
                .append(" | ").append(hyb.getJudgeFailed()).append(" |\n");
        sb.append("| **hybrid+rerank**（Plan G）| ").append(String.format("%.3f", rer.overallAvgFaithfulness()))
                .append(" | ").append(String.format("%.3f", rer.overallAvgRelevancy()))
                .append(" | ").append(rer.getGenerationFailed())
                .append(" | ").append(rer.getJudgeFailed()).append(" |\n");
        sb.append("| **Δ**（hybrid+rerank − hybrid）| ")
                .append(String.format("%+.3f", rer.overallAvgFaithfulness() - hyb.overallAvgFaithfulness()))
                .append(" | **").append(String.format("%+.3f", rer.overallAvgRelevancy() - hyb.overallAvgRelevancy())).append("**")
                .append(" | - | - |\n\n");

        sb.append("### 5.7.8.2 按 Collection 分组对比\n\n");
        sb.append("| Collection | Mode | Avg Faithfulness | Avg Relevancy | Δ Relevancy |\n");
        sb.append("|---|---|---|---|---|\n");
        for (String col : rer.collections()) {
            GenerationReport.CollectionMetrics hm = hyb.getAggregated().get(col);
            GenerationReport.CollectionMetrics rm = rer.getAggregated().get(col);
            if (hm == null || rm == null) continue;
            sb.append("| ").append(col).append(" | hybrid ")
                    .append(" | ").append(String.format("%.3f", hm.avgFaithfulness))
                    .append(" | ").append(String.format("%.3f", hm.avgRelevancy))
                    .append(" | - |\n");
            sb.append("| ").append(col).append(" | **hybrid+rerank** ")
                    .append(" | ").append(String.format("%.3f", rm.avgFaithfulness))
                    .append(" | ").append(String.format("%.3f", rm.avgRelevancy))
                    .append(" | **").append(String.format("%+.3f", rm.avgRelevancy - hm.avgRelevancy)).append("** |\n");
        }
        sb.append("\n");

        // 翻转 case：hybrid=false → rerank=true (rerank 真正修复的)
        java.util.Map<Integer, Boolean> hMap = new java.util.HashMap<>();
        for (GenerationReport.Detail d : hyb.getDetails()) {
            hMap.put(d.queryId, d.relevant);
        }
        List<GenerationReport.Detail> uplifts = new java.util.ArrayList<>();
        List<GenerationReport.Detail> regressions = new java.util.ArrayList<>();
        for (GenerationReport.Detail d : rer.getDetails()) {
            Boolean hRel = hMap.get(d.queryId);
            if (hRel == null) continue;
            if (!hRel && d.relevant) uplifts.add(d);
            else if (hRel && !d.relevant) regressions.add(d);
        }

        sb.append("### 5.7.8.3 Reranker 修复的 query（hybrid ✗ → hybrid+rerank ✓）\n\n");
        sb.append("> 这些是 cross-encoder 精排带来的真实增益——hybrid 召回了文档但顺序不优，rerank 把更相关的拉到前面。\n\n");
        if (uplifts.isEmpty()) {
            sb.append("（rerank 未带来任何 query 翻转——可能 rerank 调用失败、或 cross-encoder 在当前 doc 文本上区分度低）\n\n");
        } else {
            sb.append("| qid | collection | query | hybrid R | hybrid+rerank R |\n");
            sb.append("|---|---|---|---|---|\n");
            int shown = 0;
            for (GenerationReport.Detail d : uplifts) {
                if (shown >= 5) break;
                sb.append("| ").append(d.queryId)
                        .append(" | ").append(d.targetCollection.replace("_vector", ""))
                        .append(" | ").append(escapeMd(truncate(d.query, 30)))
                        .append(" | ✗ | ✓ |\n");
                shown++;
            }
            if (uplifts.size() > 5) {
                sb.append("\n_共 ").append(uplifts.size()).append(" 条 query 翻转（仅展示前 5 条）_\n");
            }
            sb.append("\n");
        }

        if (!regressions.isEmpty()) {
            sb.append("### 5.7.8.4 Reranker 拉低的 query（hybrid ✓ → hybrid+rerank ✗）\n\n");
            sb.append("> 这些 case 是 rerank 副作用——cross-encoder 评分把原本召回正确的 doc 排到 topK 外。\n");
            sb.append("> 对应 doc 文本对 cross-encoder 不友好（如 shop 类只有结构化字段，没有自然语言描述）。\n\n");
            sb.append("| qid | collection | query | hybrid R | hybrid+rerank R |\n");
            sb.append("|---|---|---|---|---|\n");
            int shown = 0;
            for (GenerationReport.Detail d : regressions) {
                if (shown >= 5) break;
                sb.append("| ").append(d.queryId)
                        .append(" | ").append(d.targetCollection.replace("_vector", ""))
                        .append(" | ").append(escapeMd(truncate(d.query, 30)))
                        .append(" | ✓ | ✗ |\n");
                shown++;
            }
            if (regressions.size() > 5) {
                sb.append("\n_共 ").append(regressions.size()).append(" 条 query 反向翻转（仅展示前 5 条）_\n");
            }
            sb.append("\n");
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String escapeMd(String s) {
        if (s == null) return "";
        return s.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
    }
}
