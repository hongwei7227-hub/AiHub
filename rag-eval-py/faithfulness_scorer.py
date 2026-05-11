#!/usr/bin/env python3
"""
RAG 忠实度评分器 (Faithfulness Scorer)
评估模型生成的答案是否完全被上下文片段支持，没有编造。
"""
import json
from typing import Dict, Any
import re


class FaithfulnessScorer:
    """RAG 忠实度评分器"""
    
    def __init__(self):
        self.hedging_patterns = [
            r"根据现有上下文，无法",
            r"上下文.*?没有.*?提到",
            r"上下文.*?未.*?提供",
            r"无法从.*?获取",
            r"信息不足",
            r"不够完整",
            r"无法完整回答",
            r"未能找到",
        ]
    
    def is_hedging_response(self, answer: str) -> bool:
        """判断是否为兜底回复（承认信息不足）"""
        for pattern in self.hedging_patterns:
            if re.search(pattern, answer):
                return True
        return False
    
    def score(self, context: str, answer: str, auto_reason: bool = True) -> Dict[str, Any]:
        """
        对答案进行忠实度打分
        
        Args:
            context: 上下文片段
            answer: 模型生成的答案
            auto_reason: 是否自动生成打分理由
        
        Returns:
            {"score": float, "reason": str}
        """
        
        # 情况1: 兜底回复
        if self.is_hedging_response(answer):
            return {
                "score": 0.7,
                "reason": "兜底回复,未编造"
            }
        
        # 情况2: 答案为空或过短
        if not answer or len(answer.strip()) < 5:
            return {
                "score": 0.0,
                "reason": "答案为空"
            }
        
        # 情况3: 需要 LLM 判断的情况
        # 这里提供基于规则的简单判断作为基线
        return self._rule_based_score(context, answer)
    
    def _rule_based_score(self, context: str, answer: str) -> Dict[str, Any]:
        """基于规则的简单评分（可被 LLM 评分替代）"""
        
        # 提取答案中的关键信息
        answer_clean = answer.strip()
        
        # 提取答案中的实体和关键词（简单分词）
        # 移除标点和常见虚词
        stop_words = {'是', '的', '了', '在', '和', '与', '有', '等', '个', '家', '这', '那', '一', '也', '都'}
        answer_tokens = set()
        
        # 简单的分词（按字符扫描）
        for i in range(len(answer_clean)):
            for length in [4, 3, 2]:  # 优先匹配长词
                if i + length <= len(answer_clean):
                    token = answer_clean[i:i+length]
                    if token not in stop_words and not re.match(r'[，。！？、；：""''（）\\s]', token):
                        answer_tokens.add(token)
        
        if not answer_tokens:
            return {"score": 0.0, "reason": "无有效声明"}
        
        # 检查有多少答案中的关键词能在上下文中找到
        found_tokens = set()
        hallucinated_tokens = set()
        
        for token in answer_tokens:
            if len(token) < 2:
                continue
            
            if token in context:
                found_tokens.add(token)
            else:
                # 检查是否是数字或度量单位（可能是合理的格式转换）
                if re.match(r'^\d+[元分米公斤份人]', token):
                    found_tokens.add(token)
                else:
                    # 检查是否是同义词或合理推断
                    # 例如: "受欢迎" vs "评论数5000", "高评分" vs "评分4.8"
                    if self._is_reasonable_inference(token, context):
                        found_tokens.add(token)
                    else:
                        hallucinated_tokens.add(token)
        
        total = len(found_tokens) + len(hallucinated_tokens)
        if total == 0:
            return {"score": 0.0, "reason": "无有效声明"}
        
        support_ratio = len(found_tokens) / total
        hallucination_ratio = len(hallucinated_tokens) / total
        
        # 根据支持率和编造率打分
        if hallucination_ratio == 0 and support_ratio > 0.8:
            return {"score": 1.0, "reason": "全部有依据"}
        elif hallucination_ratio < 0.2 and support_ratio > 0.6:
            return {"score": 0.7, "reason": "大部分有依据"}
        elif hallucination_ratio < 0.5:
            return {"score": 0.4, "reason": "部分超出上下文"}
        else:
            return {"score": 0.0, "reason": "大部分编造"}
    
    def _is_reasonable_inference(self, token: str, context: str) -> bool:
        """判断是否为合理推断"""
        # 数字相关的合理推断
        if '大量' in token or '很多' in token or '众多' in token:
            if re.search(r'[1-9]\d{3,}', context):  # 上下文有大数字
                return True
        
        # 评价相关的合理推断
        if '受欢迎' in token or '热门' in token or '火爆' in token:
            if '评分' in context or '评论数' in context:
                return True
        
        if '高评分' in token or '好评' in token:
            score_match = re.search(r'评分[：:]\s*([4-5]\.\d+)', context)
            if score_match:
                return True
        
        # 餐厅类型的合理推断
        if '餐厅' in token or '饭店' in token:
            if any(cuisine in context for cuisine in ['杭帮菜', '川菜', '粤菜', '西餐', '日料']):
                return True
        
        return False


def evaluate_example():
    """评估用户提供的示例"""
    
    context = """【店铺1】restId_133548（）

【店铺2】Mamala(杭州远洋乐堤港店)（拱宸桥/上塘/丽水路66号远洋乐堤港商城2期1层B115号/人均290元/评分4.9）

【店铺3】restId_99274（）

【店铺4】restId_53461（）

【店铺5】西湖一号（）"""
    
    answer = "根据现有上下文，无法完整回答。上下文提供的信息中没有直接提到杭帮菜的相关内容。"
    
    scorer = FaithfulnessScorer()
    result = scorer.score(context, answer)
    
    print("=" * 60)
    print("RAG 忠实度评分示例")
    print("=" * 60)
    print(f"\n上下文片段:\n{context}\n")
    print(f"模型生成的答案:\n{answer}\n")
    print("=" * 60)
    print(f"评分结果: {json.dumps(result, ensure_ascii=False, indent=2)}")
    print("=" * 60)
    
    return result


def batch_evaluate(test_cases: list) -> list:
    """批量评估多个测试用例
    
    Args:
        test_cases: [{"context": str, "answer": str}, ...]
    
    Returns:
        [{"score": float, "reason": str}, ...]
    """
    scorer = FaithfulnessScorer()
    results = []
    
    for i, case in enumerate(test_cases, 1):
        result = scorer.score(case["context"], case["answer"])
        results.append({
            "case_id": i,
            "score": result["score"],
            "reason": result["reason"]
        })
    
    return results


if __name__ == "__main__":
    # 评估示例
    result = evaluate_example()
    
    # 验证结果符合预期
    assert result["score"] == 0.7, f"预期分数 0.7, 实际 {result['score']}"
    assert "兜底" in result["reason"] or "未编造" in result["reason"], f"预期理由包含'兜底'或'未编造', 实际 {result['reason']}"
    
    print("\n✓ 评分符合预期标准")
