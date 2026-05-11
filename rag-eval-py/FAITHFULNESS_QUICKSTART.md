# RAG 忠实度评分器 - 快速开始

## 一句话介绍

**评估 RAG 系统生成的答案是否完全被上下文支持，没有编造。**

## 快速开始

### 1. 评估单个示例（基础版）

```python
from faithfulness_scorer import FaithfulnessScorer

scorer = FaithfulnessScorer()

context = "【店铺】外婆家（杭帮菜/人均80元/评分4.8）"
answer = "外婆家是一家杭帮菜餐厅，人均80元"

result = scorer.score(context, answer)
print(result)  # {"score": 0.7, "reason": "兜底回复,未编造"}
```

### 2. 运行用户示例演示

```bash
python demo_user_example.py
```

输出：
```
评分结果: {"score": 0.7, "reason": "兜底回复,未编造"}
```

### 3. 运行完整测试套件

```bash
python test_faithfulness.py
```

### 4. 集成到评估流程

```bash
python eval_faithfulness_integration.py
```

## 评分标准速查

| 分数 | 说明 | 示例 |
|------|------|------|
| **1.0** | 完全忠实 | "外婆家是杭帮菜餐厅，人均80元"（所有信息来自上下文） |
| **0.7** | 大部分忠实或兜底回复 | "根据上下文，无法完整回答"（承认信息不足） |
| **0.4** | 部分编造 | "外婆家是老字号餐厅，人均80元"（添加了"老字号"） |
| **0.0** | 大部分编造 | "外婆家是百年老店，有50道招牌菜"（完全虚构） |

## 核心文件

```
CityAIHub-master/
├── faithfulness_scorer.py              # ⚡ 基于规则（快速，准确率低）
├── faithfulness_scorer_llm.py          # ⭐ 基于 LLM（慢，准确率高）
├── demo_user_example.py                # 🎯 用户示例演示
├── test_faithfulness.py                # 🧪 测试套件
├── eval_faithfulness_integration.py    # 🔧 集成示例
├── README_FAITHFULNESS.md              # 📖 完整文档
└── FAITHFULNESS_QUICKSTART.md          # 🚀 本文档
```

## 推荐使用流程

### 场景1: 快速验证（开发阶段）

```bash
# 使用基于规则的评分器，快速跑通流程
python demo_user_example.py
```

### 场景2: 高精度评估（生产阶段）

```bash
# 配置 API Key
export SILICONFLOW_API_KEY="your-api-key"

# 使用 LLM 评分器
python -c "
from faithfulness_scorer_llm import LLMFaithfulnessScorer
scorer = LLMFaithfulnessScorer()
result = scorer.score(context, answer)
print(result)
"
```

### 场景3: 批量评估

```python
from faithfulness_scorer_llm import LLMFaithfulnessScorer

scorer = LLMFaithfulnessScorer()

test_cases = [
    {"context": "...", "answer": "..."},
    {"context": "...", "answer": "..."},
]

results = scorer.batch_score(test_cases, verbose=True)
```

## 常见问题

### Q: 为什么我的答案都评为 0.0？

A: 基于规则的评分器准确率有限（约 50-60%），建议使用 LLM 版本获得更准确的评分。

### Q: 如何获取 API Key？

A: 访问 [硅基流动](https://cloud.siliconflow.cn/) 注册并获取 API Key。

### Q: 评分需要多长时间？

| 方法 | 单个样本 | 50个样本 |
|------|---------|---------|
| 基于规则 | < 10ms | < 1s |
| 基于 LLM | 1-3s | 50-150s |

### Q: 为什么兜底回复给 0.7 分而不是 1.0？

A: 兜底回复（"无法回答"）虽然没有编造，但也没有提供实质信息，不算完美答案。0.7 分表示"负责任但不完美"。

## 下一步

1. ✅ 已实现：Retrieval 评估（召回质量）
2. ✅ 已实现：Faithfulness 评估（忠实度）
3. ⏳ 待实现：Answer Relevance 评估（答案相关性）

## 相关文档

- 完整文档：`README_FAITHFULNESS.md`
- RAG 评估：`README_RAG.md`
- 项目文档：`README.md`

## 技术支持

如有问题，请查看：
1. `README_FAITHFULNESS.md` 的"常见问题"部分
2. `test_faithfulness.py` 的测试用例
3. `demo_user_example.py` 的演示代码

---

**快速命令总结**

```bash
# 1. 评估用户示例
python demo_user_example.py

# 2. 运行测试套件
python test_faithfulness.py

# 3. 集成评估演示
python eval_faithfulness_integration.py

# 4. 使用 LLM 评分（需要 API Key）
export SILICONFLOW_API_KEY="your-key"
python faithfulness_scorer_llm.py
```
