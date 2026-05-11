#!/usr/bin/env python3
"""
RAG 忠实度评分器 - LLM 版本
使用 LLM 作为评判者，更准确地评估答案的忠实度
"""
import json
import os
from typing import Dict, Any, Optional
import requests


class LLMFaithfulnessScorer:
    """使用 LLM 的忠实度评分器"""
    
    def __init__(self, api_key: Optional[str] = None, model: str = "deepseek-ai/DeepSeek-V3"):
        """
        初始化评分器
        
        Args:
            api_key: 硅基流动 API Key，如果不提供则从环境变量读取
            model: 使用的模型，默认 DeepSeek-V3
        """
        self.api_key = api_key or os.getenv("SILICONFLOW_API_KEY")
        if not self.api_key:
            raise ValueError("需要提供 API Key 或设置环境变量 SILICONFLOW_API_KEY")
        
        self.model = model
        self.api_url = "https://api.siliconflow.cn/v1/chat/completions"
    
    def _build_prompt(self, context: str, answer: str) -> str:
        """构建评判提示词"""
        return f"""你是 RAG 系统的忠实度评分员。判断"模型生成的答案"是否完全被"上下文片段"支持，没有编造。

打分标准（0.0~1.0）：
- 1.0：答案中所有声明都能从上下文找到直接依据
- 0.7：答案中绝大部分声明有依据，少量轻微推断（合理引申）
- 0.4：答案部分内容超出上下文（明显推断/补充）
- 0.0：答案大部分凭空编造，与上下文无关

如果答案是"根据现有上下文，无法完整回答"这类承认信息不足的兜底回复，给 0.7（不算编造）。

输出严格 JSON：{{"score": <0.0~1.0 浮点>, "reason": "<不超过 20 字>"}}

上下文片段：
{context}

模型生成的答案：
{answer}"""
    
    def score(self, context: str, answer: str, temperature: float = 0.1) -> Dict[str, Any]:
        """
        使用 LLM 对答案进行忠实度打分
        
        Args:
            context: 上下文片段
            answer: 模型生成的答案
            temperature: LLM 温度参数，越低越稳定
        
        Returns:
            {"score": float, "reason": str}
        """
        
        prompt = self._build_prompt(context, answer)
        
        headers = {
            "Authorization": f"Bearer {self.api_key}",
            "Content-Type": "application/json"
        }
        
        payload = {
            "model": self.model,
            "messages": [
                {"role": "user", "content": prompt}
            ],
            "temperature": temperature,
            "max_tokens": 200,
            "response_format": {"type": "json_object"}
        }
        
        try:
            response = requests.post(self.api_url, headers=headers, json=payload, timeout=30)
            response.raise_for_status()
            
            result = response.json()
            content = result["choices"][0]["message"]["content"]
            
            # 解析 JSON 响应
            score_result = json.loads(content)
            
            # 验证格式
            if "score" not in score_result or "reason" not in score_result:
                raise ValueError("LLM 返回格式不正确")
            
            # 确保分数在有效范围内
            score_result["score"] = max(0.0, min(1.0, float(score_result["score"])))
            
            return score_result
            
        except requests.exceptions.RequestException as e:
            print(f"API 调用失败: {e}")
            return {"score": 0.0, "reason": "API调用失败"}
        except (json.JSONDecodeError, ValueError, KeyError) as e:
            print(f"解析 LLM 响应失败: {e}")
            return {"score": 0.0, "reason": "解析失败"}
    
    def batch_score(self, test_cases: list, verbose: bool = True) -> list:
        """
        批量评分
        
        Args:
            test_cases: [{"context": str, "answer": str}, ...]
            verbose: 是否打印进度
        
        Returns:
            [{"case_id": int, "score": float, "reason": str}, ...]
        """
        results = []
        
        for i, case in enumerate(test_cases, 1):
            if verbose:
                print(f"评估用例 {i}/{len(test_cases)}...", end=" ")
            
            result = self.score(case["context"], case["answer"])
            results.append({
                "case_id": i,
                "score": result["score"],
                "reason": result["reason"]
            })
            
            if verbose:
                print(f"✓ 分数: {result['score']:.1f}, 理由: {result['reason']}")
        
        return results


def evaluate_example(api_key: Optional[str] = None):
    """评估用户提供的示例"""
    
    context = """【店铺1】restId_133548（）

【店铺2】Mamala(杭州远洋乐堤港店)（拱宸桥/上塘/丽水路66号远洋乐堤港商城2期1层B115号/人均290元/评分4.9）

【店铺3】restId_99274（）

【店铺4】restId_53461（）

【店铺5】西湖一号（）"""
    
    answer = "根据现有上下文，无法完整回答。上下文提供的信息中没有直接提到杭帮菜的相关内容。"
    
    try:
        scorer = LLMFaithfulnessScorer(api_key=api_key)
        result = scorer.score(context, answer)
        
        print("=" * 60)
        print("RAG 忠实度评分示例 (LLM 版本)")
        print("=" * 60)
        print(f"\n上下文片段:\n{context}\n")
        print(f"模型生成的答案:\n{answer}\n")
        print("=" * 60)
        print(f"评分结果: {json.dumps(result, ensure_ascii=False, indent=2)}")
        print("=" * 60)
        
        return result
        
    except ValueError as e:
        print(f"错误: {e}")
        print("\n提示: 请设置环境变量 SILICONFLOW_API_KEY 或在代码中提供 API Key")
        return None


def load_api_key_from_config() -> Optional[str]:
    """从 application.yaml 读取 API Key"""
    import yaml
    
    config_path = "src/main/resources/application.yaml"
    if os.path.exists(config_path):
        try:
            with open(config_path, 'r', encoding='utf-8') as f:
                config = yaml.safe_load(f)
                return config.get('spring', {}).get('ai', {}).get('openai', {}).get('api-key')
        except Exception as e:
            print(f"读取配置文件失败: {e}")
    
    return None


if __name__ == "__main__":
    # 尝试从配置文件读取 API Key
    api_key = load_api_key_from_config()
    
    # 评估示例
    result = evaluate_example(api_key)
    
    if result and result["score"] == 0.7:
        print("\n✓ LLM 评分符合预期标准 (0.7 分)")
    elif result:
        print(f"\n⚠ LLM 评分为 {result['score']}, 预期 0.7")
        print(f"  理由: {result['reason']}")
