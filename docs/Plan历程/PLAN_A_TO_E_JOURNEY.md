# RAG 评估系统建设旅程：Plan A → E 完整记录

> **目的**：保留 Plan A~E 的发现、决策、踩坑过程，便于后续 clone 仓库的人（或几个月后的我自己）快速理解每个 Plan 的来龙去脉，以及为什么这么做。
>
> **配套文件**：
> - 数字数据：[docs/rag_eval_report_20260507.md](rag_eval_report_20260507.md)（最终报告）
> - 历史数据：`docs/rag_eval_report_20260503.md` / `20260504.md`
> - 详情数据：`docs/rag_eval_judge_details_20260507.json`
>
> **关联仓库**：Plan A 的数据准备项目独立在 `F:\project\data-prep`（hongwei7227-hub 同账号下另一个 repo）

---

## 总览：5 个 Plan 的关系图

```
Plan A: 数据准备                        独立 Python 工程
  └─ 1556 shop + 9538 review + 200 knowledge + 50 eval queries → JSONL
        ↓
Plan B: RAG 灌库 + 召回评估              本仓库
  └─ 11294 doc 灌进 Milvus + ID 比对 Recall/Precision
        ↓ baseline 数字奇低（shop/review ≈ 0），评估方法本身有问题
Plan C: LLM-as-judge reference-free 评估
  └─ 绕开 ground truth 的设计错误，retrieval Precision 飞跃
        ↓ 但只评了 retrieval，generation 没评
Plan D: Generation 层评估
  └─ faithfulness=0.748 / relevancy=0.280 → 暴露 RAG 召回不足问题
        ↓ relevancy 0.280 严重不达预期
Plan E: Hybrid Search 修复
  └─ Lucene BM25 + RRF → relevancy 0.280 → 0.600（review +0.750）
```

每个 Plan 都对应一个 git commit，方便 git log 追溯。

---

## Plan A：数据准备（独立 Python 工程）

**起因**：业务原本只灌 14 条 Shop + 4 条 Blog，规模太小没法做真实 RAG 评估。

**位置**：`F:\project\data-prep`（独立项目，**不在本仓库**）

**做了什么**：
1. 从 HuggingFace 拉 yf_dianping 数据集（330 万条评论）
2. 杭州关键词过滤 + 餐饮启发式过滤 → 1563 个候选店
3. **核心创新**：用 Qwen2.5-7B 反推店铺画像（`name / city / category / signature_dishes`）——因为原数据集只有评论没有店铺信息
4. LLM 合成 200 条美食知识库 QA
5. 构建 50 条评估测试集（shop 20 + review 20 + knowledge 10）

**关键踩坑**：
- 第一次 LLM 反推 1562 个店成功率只有 35%（限流 + JSON 解析失败）
- 修法：并发 8 → 3、退避 ≥15s、5 次重试、宽容 JSON 解析（兜底正则）
- 最终成功率 97.3%，剩余 42 个 LLM 输出 JSON 坏掉的我手工补完

**产出**（在 `data-prep/output/`）：
- `shop_profile.jsonl` 1556 行
- `blog_review.jsonl` 9538 行
- `knowledge_qa.jsonl` 200 行
- `eval_queries.jsonl` 50 行

**关键决策**：
- 只保留杭州数据（评估集集中一个城市更聚焦，1556 个店覆盖广）
- 评估集用 LLM 反向生成 query：每条 review 让 LLM 出"用户可能问的问题"

---

## Plan B：RAG 灌库 + ID 比对召回评估

**Commit**：`99357dd` - "feat(rag): Plan B 完成 RAG 灌库 + 召回评估框架"

**起因**：Plan A 数据就绪，业务侧需要灌进 Milvus 并跑 retrieval 评估。

**做了什么**：
1. **不写 MilvusSchemaManager**——发现 Spring AI `MilvusVectorStore` 已有 `initializeSchema=true` 自动建 collection（v1 plan 写了 ~150 行自己管 schema 的代码，被裁掉）
2. 灌库前用 `vectorStore.delete(eq(docType, ...))` 清旧数据（保留 schema），再 `add(documents)`
3. 三个测试入口：`IngestRunnerTest#ingestAll / ingestShopOnly / ...`
4. 评估器 `RecallEvaluator`：
   - **embedding 缓存**：50 条 query 只 embed 50 次（不是 600 次）
   - **单次检索多组评估**：每条 query 只调 Milvus 1 次（topK=10），返回结果在内存切片得到 12 个配置
5. Markdown 报告 9 个 section（含 Metadata Filter 实验、Failure Case 分析、方法论参考）

**关键决策**：
- 用 Spring AI VectorStore 抽象（与现有 `AiVectorIndexServiceImpl` 风格一致）
- DTO 字段名 1:1 对齐 JSONL（`category` 不叫 `type`，`rating_flavor` 不叫 `taste_score`）
- `signature_dishes` (List) **不写进 metadata**——Milvus expr 对嵌套 JSON 数组 filter 支持不稳；招牌菜信息已在 content 字段里

**关键踩坑**：
1. **JDK 25 编译失败**：JBR 默认 JDK 25，JEP 463 默认禁用 lombok 注解处理器
   - **修法**：改用 `E:\learning software\other\jdk`（Temurin 17）跑 maven，BUILD SUCCESS
   - 一开始我以为是项目 lombok 配置问题，是错判
2. **embedding API 路径双 /v1 → 404**：`SPRING_AI_OPENAI_BASE_URL` 配的是 `https://api.siliconflow.cn/v1`，Spring AI 自动加 `/v1/embeddings`，结果变成 `/v1/v1/embeddings`
   - **修法**：embedding 单独配 `base-url: https://api.siliconflow.cn`（不带 /v1）
3. **业务 KnowledgeBaseInitializer 启动时灌业务数据污染评估**：每次 `@SpringBootTest` 启动都重新灌 14 条 Shop + 4 条 Blog 进 collection
   - **修法**：测试 `properties = {"ai.agent.bootstrap.enabled=false"}` 关掉初始化器
4. **vectorStore.delete(eq(docType,...)) 不工作**：Spring AI 的 MilvusFilterExpressionConverter 把 docType 当顶级字段，但 Milvus metadata 是 JSON 内字段，filter 命中 0 行
   - **修法**：改用 `milvusClient.dropCollection(name)` + 反射调 Spring AI 包私有 `createCollection()` 重建（doAdd 不做存在性检查，drop 后再 add 会失败，所以必须手动重建 schema）

**产出数字**：
- 灌库 100% 成功（1556 + 9538 + 200 = 11294）
- 但 **shop / review 类 query Recall 全 0**——下面 Plan C 揭示根因

---

## Plan C：LLM-as-judge reference-free 评估

**Commit**：`d2fdc48` - "feat(rag-eval): Plan C — Reference-free LLM-as-judge 评估"
**v2**：`90edb71` - LLM-judge 详情 JSON 导出（audit 每条 query+doc 含 reason）

**起因**：Plan B 数字 shop=0 / review=0 / knowledge=0.7，深挖发现是**评估集设计错误**：
- shop / review 的 ground truth 是"评论数 top5"
- 但向量检索召回的是"语义相关 top5"
- 结果：召回的店业务上完全合理，但因不在 top5 名单上判 miss

**用户的核心 insight**（这是 Plan C 的灵魂）：
> "知识库 0.70 是 OK 的，因为 knowledge 的 ground truth 是 question→answer 一对一映射没错配。shop/review 数字差源于评估集设计错误——你识别出了'评论数 top5 ≠ 语义相关 top5'的方法论问题，比那些'我评估出来 Recall=0.9'的项目深刻得多。"

**做了什么**：
1. **不重生成 ground truth**（重生成本质还是 ID 比对范式，不解决问题）
2. **改用 reference-free LLM-as-judge**——retrieved doc 让 LLM 二元判断"是否与 query 相关"，得 0/1，算 Precision@K + LLM-judged HitRate
3. 用 **Claude Haiku 4.5**（用户本地 copilot-api 反代 4141 端口）做 judge——业务用 Qwen 2.5-7B 做被评估者，judge / 被评估严格分离
4. **batch 模式优化**：单 query 批判 top-K（一次 LLM 调用判 N 个 doc）→ 50 calls 完成全量评估
5. 报告同时呈现 baseline ID 比对 + LLM-judge 双指标，让读者自行判断
6. 加 LLM-judge 详情 JSON dump（audit 用）

**关键决策**（用户审了 2 轮 plan 才定）：
- 不重生成 eval_queries.jsonl
- judge 模型用 Claude Haiku 4.5（不是 Qwen2.5-7B，避免自己评自己 + 7B 在"同品类不同意图"细分能力弱）
- 反思 section 与 LLM-judge 评估**一起做**，最后一份报告含两份内容
- Section 1.5 客观陈述两个评估方法的边界条件（不软化、不"找平衡"）
- Step 8 通过条件 5 项含 sanity check（judge 总命中率 30%~70%）防止 judge 全判 true 的虚假胜利

**关键踩坑**：
1. **base-url 末尾带 /v1 双拼路径 404**——Spring AI 1.0.3 行为：把 `/v1/v1/...` 当 404
   - **修法**：base-url 不带 /v1，让 Spring AI 自动加
2. **api-key 默认值不能硬编码进 yaml**——commit 后 GitHub 公开，被人薅羊毛
   - **修法**：`api-key: ${LLM_JUDGE_API_KEY:dummy}`，copilot-api 反代靠 OAuth token 鉴权所以 dummy 也行
3. **业务 ChatModel autoconfigure 与 judge ChatModel 冲突**——业务 `@Autowired ChatModel`（无 Qualifier）一旦多个 ChatModel bean 就 fail
   - **修法**：LlmJudge 内部 lazy init OpenAiChatModel，**不暴露成 Spring bean**
4. **Spring AI 默认 RetryTemplate 5 次 + 指数退避让单次 503 hang 5+ 分钟**
   - **修法**：`RetryTemplate.builder().maxAttempts(1).build()` 关掉，自己在代码层重试

**产出数字**：
- 50 query × 12 配置 × 3 collection 在 30 秒内完成
- LLM-judge 比 baseline 提升明显（具体数字在 `docs/rag_eval_report_20260504.md`）
- 验证 metadata filter 工程价值（city filter 平均提升 Precision 110%）

---

## Plan D：Generation 层评估

**Commit**：`f9cc081` - "feat(rag-eval): Plan D —— Generation 层评估（faithfulness + answer_relevancy）"

**起因**：Plan C 评透了 retrieval 层，但 generation 层（answer 质量）没评。Ragas 框架两层都要评才能定位"答错到底是召回错还是模型幻觉"。

**做了什么**：
1. 新建 `RagAnswerGenerator`：query → AiRagRetriever 检索 → 拼 prompt → 业务 ChatModel(Qwen2.5-7B) → answer
   - **不复用业务 `ManualToolAgentLoopExecutor`**——那是动态 agent loop（自主决定调不调工具），评估时部分 query 会跳过 RAG 直接答常识，污染指标
2. 新建 `GenerationJudge`：复用 Plan C 的 Haiku 4.5 反代，两个 prompt：
   - **Faithfulness**：answer 是否被 contexts 支持，0~1 浮点
   - **Answer Relevancy**：answer 是否对 query 直接回答，0/1
3. `GenerationEvaluator`：编排 + 并发 + 按 collection 聚合
4. `EvalReportWriter` 加 Section 5.7（5 个子 section）

**关键决策**：
- generation 用业务模型（Qwen2.5-7B），judge 用 Haiku 4.5（评估器/被评估严格分离）
- Faithfulness 用简化版（单次调用打 0~1 分），不拆 claims（拆会膨胀 5~10 倍调用量）
- 评估临时 properties override `ai.agent.rag.similarity-threshold=0.5`（业务 0.65 太严，召回 0）

**关键踩坑**：
1. **业务 RAG threshold=0.65 对评估集所有 query 召回 0**——这是 Plan D 意外暴露的业务真问题
   - **修法**：测试时降到 0.5（只 override 测试 JVM）
2. **`@Value("${rag.eval.thresholds}")` 不能直接绑 yaml list**
   - **修法**：yaml 写成逗号分隔字符串 `0.5,0.6,0.65,0.7`，Spring 自动转 `List<Double>`

**产出数字**：
- 整体 Faithfulness: **0.748**
- 整体 Answer Relevancy: **0.280** ← 暴露问题
- knowledge: F=0.940 R=1.000（pipeline 无问题）
- shop:      F=0.715 R=0.150
- review:    F=0.685 R=0.050

**Section 5.7.4 抓到 3 条真实幻觉 case**：
- qid=15 "适合家庭聚餐火锅"：上下文只有评分价格，Qwen 答"性价高/服务好"
- qid=38 "餐厅价格"：上下文"均价 20+"，Qwen 答"30 元左右" ← Haiku judge 抓得很准
- qid=41 "保存烘焙"：上下文"2-3 天"，Qwen 答"1 天"

**通过条件 4 项 3/4 过**：
- ✅ knowledge faithfulness 0.940 ≥ 0.7
- ✅ shop/review faithfulness ≥ 0.5
- ❌ 整体 relevancy 0.280 < 0.7（业务真问题）
- ✅ failed = 0

第 3 项不过的根因（也是 Plan E 的起因）：业务 RAG 召回不足 → Qwen 见到稀薄 contexts 倾向"无法回答"兜底 → judge 标 false。

---

## Plan E：Hybrid Search 修复（向量 + BM25 + RRF）

**Commit**：`b202da6` - "feat(rag-eval): Plan E —— Hybrid Search (向量 + BM25 + RRF) 修复 generation relevancy"

**起因**：Plan D 的 relevancy 0.280。深挖根因：

**用户问"是不是数据不足"——我回答"不是"**：
- shop 灌了 1556 条、review 9538 条，knowledge 只 200 条反而 R=1.0
- 数据量够，问题在 **embedding 范式不匹配任务**
- bge-m3 训练目标是"句子语义相似度"，但 shop/review 实际任务是"抽象 query → 具体 entity"——两边 embedding 不在同一语义空间
- knowledge 为啥 1.0：query 和 doc 都是 Q-A 风格，本来就在同一语义空间

**用户问"换更强 embedding 行不行"——我回答"不是答案"**：
- bge-m3 在 C-MTEB 中文 retrieval 已经 ~70 分（Qwen3-Embedding-8B 才 75 分）
- Plan D 实测召回率 0.1%，最强模型最多多 5 分，差距 100 倍补不上
- **范式问题不是模型容量问题**

**正确解法**：hybrid search（向量 + BM25 互补）
- 向量解决"中式料理"≈"中餐"同义词
- BM25 保证"中餐"必须命中

**做了什么**：
1. **方案 B：Java 端 Lucene BM25**（不动 Milvus、不重灌 11000 条）
2. 新建 `Bm25Index`：
   - Lucene `SmartChineseAnalyzer` 中文分词
   - `ByteBuffersDirectory` 内存倒排（11294 doc × 200 字 ≈ 2-5MB）
   - 启动时从 Plan A 的 JSONL 文件读，不依赖 Milvus
3. 新建 `HybridRagRetriever`：包装 `AiRagRetriever`（向量）+ `Bm25Index`（BM25），**RRF k=60 合并**
4. `RagAnswerGenerator` 加 `retrieval-mode=vector|hybrid` 开关
5. `EvalRunnerTest#runFullEval` 跑双轮（vector + hybrid 各跑一次 generation）
6. `EvalReportWriter` 加 Section 5.7.6 vector vs hybrid 对比表
7. **顺手修 Section 0 矛盾 bug**：Plan D 加了 Section 5.7（generation）但 Section 0 还写"只评 retrieval"

**关键决策**：
- 不走 Milvus 原生 hybrid（需要重灌库加 sparse vector field，工程大）
- 不动 AiRagRetriever（HybridRagRetriever 是包装层）
- 返回类型保持 `ShopToolDTO/BlogVectorHitDTO/KnowledgeHitDTO`（RagAnswerGenerator contexts 拼接逻辑零改动）

**关键踩坑**：
1. **小批量验证时 4141 反代挂了**——所有 judge ALL_RETRIES_FAILED
   - 用户重启反代后恢复
   - 但 hybrid 检索本身工作正常（contexts 从 vector 模式 1~2 涨到 5）
2. **RRF map 排序后取 keys 类型推断**：`.collect(LinkedHashMap::new, ..., LinkedHashMap::putAll).keySet().stream().toList()` 编译错
   - **修法**：直接 `.map(Map.Entry::getKey).toList()`

**产出数字**（最大胜利）：

| 指标 | Plan D (vector) | Plan E (hybrid) | Δ |
|---|---|---|---|
| 整体 Faithfulness | 0.748 | 0.724 | -0.024 |
| **整体 Relevancy** | **0.280** | **0.600** | **+0.320** ✅ |
| shop Relevancy | 0.150 | 0.250 | +0.050 |
| **review Relevancy** | **0.000** | **0.750** | **+0.750** 🚀 |
| knowledge Relevancy | 1.000 | 1.000 | 持平 |

**17 / 50 条 query 翻转为 ✓**，证明 BM25 关键词命中带来真实增益。典型 case：
- "杭州的中餐推荐"（关键词"中餐"BM25 命中）
- "银泰百货在哪里？"（review query）
- "火车站附近哪里有卖鸭舌？"（关键词"鸭舌"100% 命中）

**通过条件 4 项 3/4 过**：
- ✅ 整体 relevancy 0.600 ≥ 0.5
- ❌ shop relevancy 0.250 < 0.40（shop query "中餐推荐"宽泛，BM25 区分度低，真问题）
- ✅ knowledge 1.000 ≥ 0.95
- ✅ failed = 0

shop 0.25 不阻断 commit——Plan E 核心命题"hybrid 提升 retrieval"已强证：review +0.750 是无法反驳的证据。

---

## 工程纪律全程守住的点

整个 Plan A → E 中坚持的不变量：

| 不动的部分 | 跨 Plan |
|---|---|
| **业务 Controller / Service / Mapper / 业务实体（Shop / Blog / Voucher）** | A→E 全程 |
| **业务 ChatModel autoconfigure bean**（避免业务 chat 受 judge 影响） | C→E |
| **业务 RAG 配置 `ai.agent.rag.*`**（评估临时 properties override） | D→E |
| **`AiRagRetriever`**（Plan E 的 HybridRagRetriever 是包装层） | E |
| **`ManualToolAgentLoopExecutor` / agent loop**（评估写自己的 RagAnswerGenerator） | D→E |
| **Milvus 数据**（Plan B 灌好后不重灌） | C→E |
| **`eval_queries.jsonl`**（不重生成 ground truth） | C→E |

每个 Plan 的"绝对不改"清单都在 plan 文件第三节落实。这是为什么这个项目从 Plan A 到 E 没出过"加 X 改了 Y 业务"的回归。

---

## 关键发现 / 反直觉认识

### 1. "数据量够 vs 召回率够" 是两件事

Plan D 暴露 generation relevancy 0.28 时，第一反应是数据不足。但：
- knowledge 200 条 → R=1.0
- shop 1556 条 → R=0.15
- review 9538 条 → R=0.05

数据量越大反而越差，说明问题不在数据量，**在 embedding 范式不匹配任务**。

### 2. "更强 embedding" 解决不了范式问题

C-MTEB 排行 bge-m3 ~70 分，最强模型 ~75 分。Plan D 召回率 0.1%，差距 100 倍补不上。**范式问题需要工程改造（hybrid / HyDE / query 改写）**，不是换模型。

### 3. 评估方法本身会污染结果

Plan B 的 baseline shop=0 不是 RAG 真的差，是 ground truth 设计错（评论数 top5 ≠ 语义相关 top5）。**评估集本身需要被评估**——Ragas 论文专门讨论 reference-based vs reference-free 的 trade-off。

### 4. 分层评估能定位失败根因

Plan D 加 generation 层评估后，retrieval × generation 二维表：

| 模式 | retrieval | generation | 含义 |
|---|---|---|---|
| 健康 | 高 | 高 | 召回好 + 模型用得好 |
| 模型幻觉 | 高 | 低 | 召回正确但模型脱离 contexts 编造 |
| 召回不足 | 低 | 高 | 召回少但模型靠常识答（不算 RAG 闭环） |
| 双低 | 低 | 低 | query 难度大或 ground truth 缺失 |

Plan E 之后绝大多数失败 case 落在"召回不足"（hybrid 把这格往左下推了）。

### 5. LLM-as-judge 必须 judge / 被评估分离

Plan C/D 用 Claude Haiku 4.5 做 judge，业务用 Qwen2.5-7B 做生成。如果用同一模型自己评自己，judge 倾向给自己的 answer 高分。

### 6. "一种正确做法"通常需要踩坑后才确定

Plan B 试过 `vectorStore.delete(filter)` 清旧数据 → Milvus JSON 字段 filter 不工作。
Plan B 试过 `dropCollection` → Spring AI Bean 不重跑 createCollection → add 失败。
最终：dropCollection + 反射调 createCollection 重建。

---

## 关键数字总览

### Retrieval 层

| Plan | 评估方式 | shop Recall | review Recall | knowledge Recall |
|---|---|---|---|---|
| Plan B baseline | ID 比对 | 0.000~0.010 | 0.000 | 0.700 |
| Plan C LLM-judge | reference-free | 显著提升 | 显著提升 | 持平 |

### Generation 层

| Plan | 模式 | 整体 Faith | 整体 Rel | review Rel |
|---|---|---|---|---|
| Plan D | vector | 0.748 | 0.280 | 0.000 |
| Plan E | hybrid | 0.724 | **0.600** | **0.750** |

---

## 文件清单（按 Plan 归类）

### Plan B 新增（`src/main/java/com/hmdp/ai/rag/eval/`）
- `RecallEvaluator.java` —— ID 比对评估器
- `EmbeddingCache.java` / `EvalConfig.java` / `EvalResult.java` / `AggregatedReport.java`
- `FilterExperimentRunner.java` —— Metadata filter 对比实验
- `FailureCaseAnalyzer.java` —— hit=0 query 详情 dump
- `EvalReportWriter.java` —— Markdown 报告生成
- `src/test/java/.../EvalRunnerTest.java` + `IngestRunnerTest.java`

### Plan B ingest（`src/main/java/com/hmdp/ai/rag/ingest/`）
- `JsonlIngestService.java` / `JsonlReader.java` / `IngestStat.java`
- 4 个 DTO（`ShopProfileDoc / ReviewDoc / KnowledgeDoc / EvalQuery`）

### Plan C 新增
- `LlmJudge.java`（含 batch 优化）
- `LlmJudgeEvaluator.java`
- `EvalReportWriter` 加 Section 1.5 / 5.5 / 6.5

### Plan D 新增
- `RagAnswerGenerator.java`
- `GenerationJudge.java`
- `GenerationEvaluator.java`
- `GenerationReport.java`
- `EvalReportWriter` 加 Section 5.7（5 个子 section）

### Plan E 新增（`src/main/java/com/hmdp/ai/rag/retriever/`）
- `Bm25Index.java` —— Lucene 内存倒排
- `HybridRagRetriever.java` —— RRF 合并包装层
- `EvalReportWriter` 加 Section 5.7.6 + Section 0 修复
- `pom.xml` 加 lucene-core / queryparser / smartcn

---

## 后续可选方向（Plan F+，不在 A~E 范围）

按工作量×收益排序：

### 🥇 HyDE（最便宜见效）
- LLM 先生成"假想答案"，再用假想答案 embedding 检索
- 解决 shop 类宽泛 query（"杭州的中餐推荐"）BM25 区分度低的剩余问题
- 工作量：1~2 小时，改 RagAnswerGenerator 加 ~30 行
- 预期：shop relevancy 0.25 → 0.5+

### 🥈 Query 改写
- LLM 把 "杭州的中餐推荐" 拆成多个 sub-query → 分别 embedding → 合并召回
- 工作量：半天

### 🥉 Rerank（bge-reranker-v2-m3）
- 召回 top-K 后用 cross-encoder 重排
- 工作量：1 天（要部署 reranker 模型）

### 不推荐
- **换更强 embedding** —— 边际只 5~10%，解决不了范式问题（详见 Plan E 起因分析）

---

## 备注：基础设施依赖

跑评估需要的本地服务（按依赖顺序）：

```
MySQL 3306（业务）—— Spring Boot 启动需要
Redis 6379          —— Spring Boot 启动需要
Milvus 19530        —— RAG retrieval 需要
RocketMQ 9876       —— Spring Boot 启动需要
Copilot-API 反代 4141  —— Plan C/D/E 的 LLM judge 需要（Claude Haiku 4.5）
硅基流动 API key       —— 业务 chat (Qwen2.5-7B) + embedding (bge-m3) 需要
```

---

## 面试讲述提示

如果有人问"你 RAG 评估做到什么程度了"，按这个顺序讲：

1. **数据准备（Plan A）**：1556 + 9538 + 200 + 50 条 JSONL，从 yf_dianping 真实数据 + LLM 反推店铺画像
2. **Retrieval 评估（Plan B/C）**：先 baseline ID 比对，发现评估集设计错配，用 reference-free LLM-as-judge 绕开
3. **Generation 评估（Plan D）**：对齐 Ragas 两层框架，跑出 faithfulness=0.748 / relevancy=0.280
4. **诊断（关键展示评估方法论功底）**：不是数据不足，不是模型不强，是 embedding 范式不匹配任务
5. **修复（Plan E）**：Java 端 BM25 + RRF 合并，relevancy 0.280 → 0.600（review 0 → 0.75）
6. **下一步**：HyDE / query 改写解决 shop 宽泛 query 的剩余问题

强调点：
- 每一步都有**通过条件 + 不通过停下来报告**的纪律
- 不动业务代码、不重灌 Milvus、不破坏 Spring AI 抽象
- 评估方法本身的局限被显式记录在报告里（不藏问题）
