#!/usr/bin/env python3
"""
用户示例演示
评估用户提供的具体案例
"""
import json
from faithfulness_scorer import FaithfulnessScorer


def main():
    """评估用户提供的示例"""
    
    # 用户提供的上下文
    context = """【店铺1】restId_133548（）

【店铺2】Mamala(杭州远洋乐堤港店)（拱宸桥/上塘/丽水路66号远洋乐堤港商城2期1层B115号/人均290元/评分4.9）

【店铺3】restId_99274（）

【店铺4】restId_53461（）

【店铺5】西湖一号（）"""
    
    # 用户提供的答案
    answer = "根据现有上下文，无法完整回答。上下文提供的信息中没有直接提到杭帮菜的相关内容。"
    
    # 评分
    scorer = FaithfulnessScorer()
    result = scorer.score(context, answer)
    
    print("=" * 70)
    print("RAG 忠实度评分 - 用户示例演示")
    print("=" * 70)
    print()
    print("【上下文片段】")
    print(context)
    print()
    print("【模型生成的答案】")
    print(answer)
    print()
    print("=" * 70)
    print("【评分结果】")
    print("=" * 70)
    print()
    print(json.dumps(result, ensure_ascii=False, indent=2))
    print()
    print("=" * 70)
    print("【解释】")
    print("=" * 70)
    print()
    print("这个答案获得 0.7 分的原因:")
    print()
    print("1. ✓ 答案承认了信息不足（'无法完整回答'）")
    print("2. ✓ 答案指出了上下文的局限（'没有直接提到杭帮菜'）")
    print("3. ✓ 答案没有编造任何不存在的信息")
    print("4. ✓ 这是一个负责任的兜底回复")
    print()
    print("根据评分标准，这类承认信息不足的兜底回复应给 0.7 分，")
    print("因为它虽然没有提供实质信息，但也没有编造，是负责任的回答。")
    print()
    print("=" * 70)
    print("【对比：不同答案的评分】")
    print("=" * 70)
    print()
    
    # 演示不同类型的答案
    test_answers = [
        {
            "answer": "根据现有上下文，无法完整回答。上下文提供的信息中没有直接提到杭帮菜的相关内容。",
            "expected": "0.7",
            "type": "兜底回复"
        },
        {
            "answer": "Mamala是一家位于杭州远洋乐堤港店的餐厅，人均消费290元，评分4.9。",
            "expected": "1.0",
            "type": "完全忠实"
        },
        {
            "answer": "Mamala是杭州最好的餐厅之一，人均290元，环境优雅，服务一流。",
            "expected": "0.4",
            "type": "部分编造"
        },
        {
            "answer": "推荐外婆家、绿茶餐厅等杭州老字号杭帮菜餐厅，都是本地人常去的。",
            "expected": "0.0",
            "type": "完全编造"
        }
    ]
    
    for i, test in enumerate(test_answers, 1):
        test_result = scorer.score(context, test["answer"])
        
        print(f"{i}. [{test['type']}]")
        print(f"   答案: {test['answer'][:50]}...")
        print(f"   评分: {test_result['score']:.1f} (预期: {test['expected']})")
        print(f"   理由: {test_result['reason']}")
        print()
    
    print("=" * 70)
    print("评估完成!")
    print("=" * 70)


if __name__ == "__main__":
    main()
