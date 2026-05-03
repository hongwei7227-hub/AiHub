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
        Files.createDirectories(outputDir);
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        Path file = outputDir.resolve("rag_eval_report_" + dateStr + ".md");

        StringBuilder sb = new StringBuilder(8192);
        appendHeader(sb, queryCount, elapsedMs);
        appendScopeDeclaration(sb);
        appendEvalConfig(sb, report);
        appendCollectionTables(sb, report);
        appendFilterExperiment(sb, filterExp);
        appendFailureCases(sb, failures);
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

    private void appendScopeDeclaration(StringBuilder sb) {
        sb.append("## 0. 评估范围声明\n\n");
        sb.append("> 本次评估**只覆盖检索阶段（retrieval）**——\"向量库召回的文档是否正确\"。\n");
        sb.append(">\n");
        sb.append("> 不在本次范围内：\n");
        sb.append("> - 生成阶段（faithfulness / answer_relevancy / hallucination）—— 需要 LLM-as-judge，下一阶段\n");
        sb.append("> - 端到端用户满意度 —— 需要人工标注\n");
        sb.append(">\n");
        sb.append("> 分层评估方法对照 [Ragas](https://github.com/explodinggradients/ragas) 的 retrieval / generation 两层框架。\n");
        sb.append("> 先把 retrieval 评透再上 generation，避免指标混在一起无法定位问题来源。\n\n");
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

    private String safeStr(String s) {
        return s == null ? "" : s;
    }
}
