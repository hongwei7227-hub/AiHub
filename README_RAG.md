# RAG 召回评估系统（Plan B）

本项目在 CityAIHub 业务工程内实现了 RAG 检索阶段的评估框架，用于量化向量库的召回质量、对比不同阈值/topK 配置、定位 failure case。

## 评估范围

**只覆盖 retrieval 阶段**（"召回的文档对吗"），不覆盖 generation 阶段（"基于召回回答得对吗"）。
分层评估的设计参考 [Ragas](https://github.com/explodinggradients/ragas) 的两层框架——先把 retrieval 评透再上 generation，避免指标混在一起无法定位问题来源。

## 数据来源

数据由独立项目 [hm-dianping-data-prep](../hm-dianping-data-prep) 准备：

| 文件 | 行数 | 说明 |
|------|------|------|
| `shop_profile.jsonl` | 1563 | 杭州餐厅画像（yf_dianping 评论 + LLM 反推 name/city/category） |
| `blog_review.jsonl` | 9538 | 杭州相关用户评论（yf_dianping 真实评论清洗） |
| `knowledge_qa.jsonl` | 200 | LLM 合成的美食问答 |
| `eval_queries.jsonl` | 50 | 评估测试集，含 ground truth 标注 |

## 前置依赖

⚠️ **本评估测试需要本地起以下服务后手动跑**：

- MySQL (localhost:3306)
- Redis (localhost:6379)
- Milvus (localhost:19530)
- 硅基流动 API key（已配在 application.yaml）

测试类都加了 `@Disabled`，默认不参与 `mvn test` 全量执行。

## 灌库

```bash
# 灌全部 3 个 collection
mvn test -Dtest=IngestRunnerTest#ingestAll

# 单独重灌
mvn test -Dtest=IngestRunnerTest#ingestShopOnly
mvn test -Dtest=IngestRunnerTest#ingestReviewOnly
mvn test -Dtest=IngestRunnerTest#ingestKnowledgeOnly
```

灌库逻辑：

1. `vectorStore.delete(eq(docType, ...))` 清掉旧数据（保留 collection schema）
2. 从 `data-dir` 读 JSONL，反序列化到 DTO
3. 按 `batch-size` 分批构造 Spring AI 的 `Document`，调 `vectorStore.add()` 灌入
4. metadata 沿用 `AiMetadataConstants` 已有命名风格，新增 `city / category / enrichmentSource` 等
5. **`signature_dishes` (List) 不写进 metadata**——招牌菜信息已在 `content` 字段里，写 metadata 是冗余且 Milvus expr 对嵌套数组 filter 支持不稳

## 评估

```bash
mvn test -Dtest=EvalRunnerTest#runFullEval
```

报告生成到 `docs/rag_eval_report_YYYYMMDD.md`，包含：

- **Section 0**：评估范围声明（明确只评 retrieval）
- **Section 1**：评估配置
- **Section 2~4**：3 个 collection × 12 个配置（4 阈值 × 3 topK）的指标矩阵
- **Section 5**：Metadata Filter 对比实验（自动筛带"杭州"意图的 query）
- **Section 6**：Failure Case 分析（hit=0 的 query，**根因/改进列留空给用户人工补**）
- **Section 7~8**：整体观察 + 后续优化方向
- **Section 9**：方法论参考（Ragas / LlamaIndex / LangChain / Milvus 致谢）

## 评估指标

命名对齐 Ragas 业界标准：

- **Context Recall@K** — 召回的相关文档占应召回的比例
- **Context Precision@K** — 召回的文档中相关的比例
- **MRR** — 第一个相关文档排名的倒数均值
- **HitRate** — 至少召回一条相关文档的 query 比例

## 性能优化

为了 50 query × 12 配置 × 3 collection 在 30 秒内跑完：

1. **embedding 缓存**：每条 query 只调 1 次 embedding API（`EmbeddingCache`）
2. **单次检索多组评估**：每条 query 只调 1 次 Milvus（`topK=maxTopK`），返回结果在内存里按 `(threshold, topK)` 切片得到 12 组指标

朴素实现需要 600 次 embedding + 600 次 Milvus 检索；优化后降到 50 + 50。

## 设计参考

设计思路对照以下业界标杆（**只参考思想，不引入依赖**）：

- [Ragas](https://github.com/explodinggradients/ragas) — 两层评估框架 + 指标命名（`context_recall` / `context_precision`）
- [LlamaIndex](https://github.com/run-llama/llama_index) — `RetrieverEvaluator` 抽象 + 三阶段解耦
- [LangChain](https://github.com/langchain-ai/langchain) — trace 思路应用于 failure case 分析
- [Milvus](https://github.com/milvus-io/milvus) — metadata filtering 一等公民

具体实现是 Java + Spring AI + Milvus（业务栈），核心评估逻辑约 50 行 Java（标准 IR 公式）。

## 文件结构

```
src/main/java/com/hmdp/ai/rag/
├── dto/
│   ├── ShopProfileDoc.java
│   ├── ReviewDoc.java
│   ├── KnowledgeDoc.java
│   └── EvalQuery.java
├── ingest/
│   ├── JsonlIngestService.java
│   ├── JsonlReader.java
│   └── IngestStat.java
└── eval/
    ├── EmbeddingCache.java
    ├── EvalConfig.java
    ├── EvalResult.java
    ├── AggregatedReport.java
    ├── RecallEvaluator.java
    ├── EvalReportWriter.java
    ├── FailureCaseAnalyzer.java
    └── FilterExperimentRunner.java

src/test/java/com/hmdp/ai/rag/
├── ingest/IngestRunnerTest.java   # 灌库入口
└── eval/EvalRunnerTest.java        # 评估入口

docs/
└── rag_eval_report_YYYYMMDD.md    # 评估报告（跑完后生成）
```

## 评估完成后的工作流

1. 跑 `EvalRunnerTest#runFullEval`
2. 打开 `docs/rag_eval_report_*.md`
3. **人工补完 Section 6 每个 failure case 的"根因 / 改进方向"两栏**——CC 不会瞎写，留空让用户判断
4. （可选）补完 Section 7「整体观察」的总结
5. `git add` + commit + `git push origin main`
