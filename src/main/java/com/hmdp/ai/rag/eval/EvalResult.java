package com.hmdp.ai.rag.eval;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 单条 query 在单个配置下的评估结果。
 *
 * 指标命名对齐 Ragas 业界标准（contextRecall / contextPrecision），
 * 明确表示这是检索（context）阶段的指标，区别于生成阶段的 faithfulness / answer_relevancy。
 */
@Data
@AllArgsConstructor
public class EvalResult {
    private final double contextRecall;     // 命中数 / 应召回数
    private final double contextPrecision;  // 命中数 / 实际返回数
    private final double reciprocalRank;    // 第一个相关文档排名的倒数（用于 MRR 平均）
    private final boolean hit;              // 至少召回一条相关文档？
}
