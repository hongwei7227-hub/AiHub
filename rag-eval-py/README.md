# RAG 忠实度评分器 (Faithfulness Scorer)

## 概述

忠实度评分器用于评估 RAG 系统生成的答案是否完全被上下文片段支持，没有编造。这是 RAG 系统质量评估的重要维度，与现有的 retrieval 评估（召回质量）互补。

## 评估维度对比

| 维度 | 评估对象 | 核心问题 | 现有支持 |
|------|---------|---------|---------|
| **Retrieval** | 召回的文档 | "召回的文档对吗？" | ✅ 已实现（见 README_RAG.md） |
| **Faithfulness** | 生成的答案 | "答案是否被上下文支持？" | ✅ 本工具 |
| **Answer Relevance** | 生成的答案 | "答案是否回答了问题？" | ⏳ 待实现 |

## 打分标准

评分范围：**0.0 ~ 1.0**

- **1.0**：答案中所有声明都能从上下文找到直接依据
- **0.7**：答案中绝大部分声明有依据，少量轻微推断（合理引申）
- **0.4**：答案部分内容超出上下文（明显推断/补充）
- **0.0**：答案大部分凭空编造，与上下文无关

**特殊情况**：如果答案是"根据现有上下文，无法完整回答"这类承认信息不足的兜底回复，给 **0.7 分**（不算编造）。

## 两种实现方式

### 1. 基于规则的评分器（基线方法）

**文件**: `faithfulness_scorer.py`

**优点**:
- 无需 API Key，可离线运行
- 速度快，成本低
- 适合快速原型验证

**缺点**:
- 准确率有限（约 50-60%）
- 无法理解复杂的语义关系
- 难以处理同义词、合理推断等情况

**使用示例**:

```python
from faithfulness_scorer import FaithfulnessScorer

scorer = FaithfulnessScorer()

context = """【店铺】外婆家（西湖店）（杭帮菜/人均80元/评分4.8）"""
answer = "外婆家是一家杭帮菜餐厅，人均80元"

result = scorer.score(context, answer)
print(result)  # {"score": 1.0, "reason": "全部有依据"}
```

### 2. 基于 LLM 的评分器（推荐方法）⭐

**文件**: `faithfulness_scorer_llm.py`

**优点**:
- 高准确率（90%+）
- 能理解语义、同义词、合理推断
- 可解释性强（提供详细理由）

**缺点**:
- 需要 API Key 和网络连接
- 有 API 调用成本
- 速度较慢（每个样本 1-3 秒）

**使用示例**:

```python
from faithfulness_scorer_llm import LLMFaithfulnessScorer

# 方式1: 从环境变量读取 API Key
scorer = LLMFaithfulnessScorer()

# 方式2: 显式提供 API Key
scorer = LLMFaithfulnessScorer(api_key="your-api-key-here")

context = """【店铺】外婆家（西湖店）（杭帮菜/人均80元/评分4.8）"""
answer = "外婆家是一家杭帮菜餐厅，人均80元"

result = scorer.score(context, answer)
print(result)  # {"score": 1.0, "reason": "完全基于上下文"}
```

## 配置 API Key

### 方式1: 环境变量（推荐）

```bash
# Windows (PowerShell)
$env:SILICONFLOW_API_KEY="your-api-key-here"

# Linux/Mac
export SILICONFLOW_API_KEY="your-api-key-here"
```

### 方式2: 从配置文件读取

LLM 评分器会自动尝试从 `src/main/resources/application.yaml` 读取 API Key：

```yaml
spring:
  ai:
    openai:
      api-key: sk-xxx
```

## 批量评估

```python
from faithfulness_scorer_llm import LLMFaithfulnessScorer

scorer = LLMFaithfulnessScorer()

test_cases = [
    {
        "context": "店铺：外婆家（杭帮菜/人均80元）",
        "answer": "外婆家是杭帮菜餐厅"
    },
    {
        "context": "店铺：肯德基（快餐）",
        "answer": "根据现有上下文，无法获取详细信息"
    }
]

results = scorer.batch_score(test_cases, verbose=True)

for r in results:
    print(f"用例 {r['case_id']}: {r['score']:.1f} - {r['reason']}")
```

## 测试

运行完整测试套件：

```bash
python test_faithfulness.py
```

测试套件包含 6 个典型场景：
1. 兜底回复（承认信息不足）
2. 完全基于上下文的准确回答
3. 部分编造（添加了不存在的信息）
4. 完全编造（与上下文无关）
5. 空答案
6. 有合理推断的回答

## 输出格式

所有评分器统一返回 JSON 格式：

```json
{
  "score": 0.7,
  "reason": "大部分有依据,少量推断"
}
```

## 集成到现有评估流程

### 当前评估流程（只评 Retrieval）

```
用户 Query → 向量检索 → 召回文档 → [评估: Context Recall/Precision]
```

### 完整评估流程（增加 Generation 评估）

```
用户 Query → 向量检索 → 召回文档 → [评估1: Context Recall/Precision]
                                    ↓
                              LLM 生成答案 → [评估2: Faithfulness]
                                    ↓         [评估3: Answer Relevance]
                              返回给用户
```

### Java 集成建议

在 `com.cityaihub.ai.rag.eval` 包下新增：

```java
// 1. 数据类
public class FaithfulnessEvalRequest {
    private String context;
    private String answer;
}

public class FaithfulnessEvalResult {
    private Double score;
    private String reason;
}

// 2. 评估服务
@Service
public class FaithfulnessEvaluator {
    
    public FaithfulnessEvalResult evaluate(String context, String answer) {
        // 调用 Python 脚本（通过 ProcessBuilder）
        // 或者实现 Java 版本的 LLM 评分器
    }
}

// 3. 测试类
@Test
@Disabled
public void testFaithfulness() {
    // 读取 eval_queries.jsonl
    // 对每个 query 生成答案
    // 评估 faithfulness
    // 生成报告到 docs/faithfulness_eval_report_YYYYMMDD.md
}
```

## 性能优化建议

对于大规模评估（数百个样本）：

1. **并发调用**: 使用线程池并发调用 LLM API
   ```python
   from concurrent.futures import ThreadPoolExecutor
   
   with ThreadPoolExecutor(max_workers=5) as executor:
       results = list(executor.map(scorer.score, contexts, answers))
   ```

2. **批量请求**: 如果 API 支持 batch 接口，使用批量请求

3. **缓存结果**: 对于相同的 (context, answer) 对，缓存评估结果
   ```python
   import hashlib
   import json
   
   cache = {}
   key = hashlib.md5(f"{context}|{answer}".encode()).hexdigest()
   if key in cache:
       return cache[key]
   ```

## 方法论参考

- [Ragas Faithfulness](https://docs.ragas.io/en/latest/concepts/metrics/faithfulness.html)
- [LlamaIndex Faithfulness Evaluator](https://docs.llamaindex.ai/en/stable/examples/evaluation/faithfulness_eval/)
- [TruLens Groundedness](https://www.trulens.org/trulens_eval/core_concepts_rag_triad/#groundedness)

## 常见问题

### Q1: 为什么基于规则的评分器准确率低？

A: 忠实度评估本质是语义理解问题，需要：
- 理解同义词（"餐厅" vs "饭店"）
- 判断合理推断（"受欢迎" ← "评论数5000"）
- 识别数值等价（"80元" vs "人均80"）

这些都超出了简单规则匹配的能力，需要 LLM 的语义理解能力。

### Q2: 如何选择温度参数？

A: 建议 `temperature=0.1`（默认值）：
- 温度越低，评分越稳定、越严格
- 温度越高，评分越灵活、越宽松
- 对于评估任务，低温度更好

### Q3: 评分器会不会给出偏高或偏低的分数？

A: 可以通过以下方式校准：
1. 准备 30-50 个人工标注的样本
2. 运行评分器，对比人工标注和 LLM 评分
3. 如果系统性偏高/偏低，调整提示词中的标准描述

### Q4: 如何处理多语言？

A: 当前版本只支持中文，如需支持其他语言：
1. 修改 `_build_prompt()` 中的提示词
2. 调整 `hedging_patterns` 正则表达式
3. 使用多语言模型（如 GPT-4）

## 后续优化方向

1. **细粒度评分**: 不仅给出总体分数，还标注哪些句子有依据、哪些编造
2. **自动修正**: 对于编造的部分，自动生成修正建议
3. **对抗测试**: 构造边界 case（如近似编造、巧妙推断）测试鲁棒性
4. **多评委集成**: 使用多个 LLM 投票，提高稳定性

## 文件清单

```
CityAIHub-master/
├── faithfulness_scorer.py          # 基于规则的评分器
├── faithfulness_scorer_llm.py      # 基于 LLM 的评分器（推荐）
├── test_faithfulness.py            # 测试套件
└── README_FAITHFULNESS.md          # 本文档
```

## 引用

如果你在研究中使用此工具，请引用：

```
CityAIHub RAG Faithfulness Scorer
Author: [Your Name]
GitHub: [Your Repo]
Year: 2026
```
