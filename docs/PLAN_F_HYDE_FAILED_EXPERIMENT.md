# Plan F: HyDE 失败实验记录

> **结论先行**：HyDE (Hypothetical Document Embedding) 在业务模型 (Qwen2.5-7B) 上**反而拉低 relevancy** 0.720 → 0.420。代码不进 main，作为失败实验保留在 `plan-f-hyde-experiment` 分支供后续参考。

## 背景

Plan E (commit `b202da6`) 跑 hybrid search (向量 + BM25 + RRF k=60) 修复了 review collection（0 → 0.95）和整体 relevancy（0.28 → 0.72），但 shop_profile 留在 0.350。

Plan F 想用 **HyDE**（[Gao et al. 2022](https://arxiv.org/abs/2212.10496)）让业务 ChatModel 先编一个"理想答案示例"，再用假想答案的 embedding 去检索。**直觉**：抽象 query（"杭州的中餐推荐"）和具体 doc（"楼外楼·西湖醋鱼·人均 200"）的 embedding 距离远，但**假想答案**和具体 doc 的距离近——HyDE 把 query 投到 doc 的语义空间。

## 实测结果（50 query × triple-pass，judge=sonnet-4.5，73 分钟）

### 整体指标

| Mode        | Faithfulness | Relevancy | Δ vs hybrid |
|-------------|--------------|-----------|-------------|
| vector      | 0.724        | 0.300     | -           |
| **hybrid** (Plan E)  | 0.720        | **0.720** | -           |
| **hyde-hybrid** (Plan F) | 0.692        | **0.420** | **-0.300** ❌ |

### 按 Collection 分组

| Collection | hybrid | hyde-hybrid | Δ |
|-----------|--------|-------------|---|
| shop_profile_vector | 0.350 | 0.350 | +0.000（持平） |
| blog_review_vector  | 0.950 | 0.400 | **-0.550** ❌ |
| knowledge_vector    | 1.000 | 0.600 | **-0.400** ❌ |

**HyDE 不仅没修 shop（持平），反而把 review 和 knowledge 已经达到的高分大幅拉低。**

## 失败根因

### 1. Qwen2.5-7B 编 hypothetical 不稳

实测日志中观察到 hypothetical answer 异常 case：

**Case A — prompt template leak**
```
原 query: 杭州哪家潜艇堡好吃？
Qwen 编出：杭州潜艇堡 ... |?35\n用户\n安装我是杭州本地美食专家...
你帮助...用户\n问题：杭州哪家潜艇堡好吃？\n\n示例答案：
```
模型把 prompt template 的结构泄漏到输出里。

**Case B — 多语言乱码**
```
原 query: 杭州减肥代茶饮的推荐
Qwen 编出：杭州可以拿口香糖糖果，2\nuser pérdida de peso saludable
pérdida de peso salud pérdida de peso salud
```
中文 query 输出退化为西语短语循环。

### 2. bge-m3 embed 这种异常文本时偏离原 query 语义

正常 hypothetical（"推荐杭州几家中餐：1. 楼外楼..."）和具体 doc 的 embedding 距离应该比 query 短。但**异常 hypothetical 的 embedding 完全脱离原 query 主题** → 召回的 top-5 跟 query 不相关 → relevancy 暴跌。

### 3. 在 review / knowledge 上尤其严重

Plan E hybrid 模式下，review 已经到 0.95、knowledge 到 1.0（接近天花板）。HyDE 在已经召回正确的情况下引入噪声，是单向损失。

## 工程结论

1. **HyDE 在小模型业务里不适合**。评估的命题是"业务真实链路质量"，业务实际只能用 7B 模型。强行用 sonnet/opus 编 hypothetical 就改变了评估对象。

2. **想救 HyDE 需要先升业务 chat**（Qwen2.5-72B / DeepSeek-V3 / 更强模型），但那是独立优化方向，不在本评估迭代周期。

3. **Plan F 副产物有用**——本次失败实验留下三件可复用资产：
   - **judge 后端从 4141 (Haiku 4.5) 切到 4647 (sonnet-4.5)** 的迁移路径，绕开 Copilot 配额限制
   - **sonnet-4.5 复测 hybrid = 0.720**（Plan E commit 时 Haiku 数字 0.60，新数字更准确）
   - **完整 triple-pass 评估框架**（vector / hybrid / hyde-hybrid 三轮）已实现，下次接 Reranker 直接复用第三 PASS 的位置

## Plan F 4 项通过条件检查

按计划纪律,4 项必须全过才能 commit:

| # | 条件 | 实测 | 通过 |
|---|------|------|------|
| ① | shop relevancy ≥ 0.40 | 0.350 | ❌ |
| ② | 整体 relevancy ≥ 0.65 | 0.420（hybrid 0.720,**退化 -0.300**） | ❌ |
| ③ | 假想答案抽样合理 | log 见 prompt leak / 乱码 | ❌ |
| ④ | judge failed = 0 | calls=275 / failures=0 | ✅ |

**4 项过 1 项 → 不进 main**。

## Plan F 完整代码 + 详细报告

见 GitHub 分支 [`plan-f-hyde-experiment`](https://github.com/hongwei7227-hub/-/tree/plan-f-hyde-experiment)（commit `43d5466`）：

- HyDE 核心：`RagAnswerGenerator.generateHypothetical()`
- 三 PASS 调度：`EvalRunnerTest.runFullEval()`
- 报告章节：`EvalReportWriter.appendHydeComparison()` 输出 Section 5.7.7
- 完整数据：`docs/rag_eval_report_20260507.md`（约 700 行）
- judge 抽样：`docs/rag_eval_judge_details_20260507.json`（含每条 reason）

## 下一步：Plan G — Reranker

基于 Plan F 失败发现，下一步选用 **Reranker (BAAI/bge-reranker-v2-m3 via SiliconFlow)**：

- 不依赖业务 chat 编 hypothetical（绕开 Qwen2.5-7B 不稳问题）
- cross-encoder 直接用 query × doc 对评分，是业界 RAG 工程化的标准下一步
- 预期 shop relevancy 从 0.35 → 0.5+，整体 ≥ 0.75
- judge 从 sonnet-4.5 切到 composer-2-fast（实测 20 query × 二元判断 100% 一致 + 速度快 47%）

详见后续 commit。
