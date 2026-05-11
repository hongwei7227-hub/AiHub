#!/usr/bin/env python3
"""
忠实度评分器测试套件
包含多种场景的测试用例
"""
import json
from faithfulness_scorer import FaithfulnessScorer, batch_evaluate


def run_test_suite():
    """运行完整测试套件"""
    
    test_cases = [
        {
            "name": "场景1: 兜底回复（承认信息不足）",
            "context": """【店铺1】restId_133548（）
【店铺2】Mamala(杭州远洋乐堤港店)（拱宸桥/上塘/丽水路66号远洋乐堤港商城2期1层B115号/人均290元/评分4.9）
【店铺3】restId_99274（）
【店铺4】restId_53461（）
【店铺5】西湖一号（）""",
            "answer": "根据现有上下文，无法完整回答。上下文提供的信息中没有直接提到杭帮菜的相关内容。",
            "expected_score": 0.7,
            "expected_reason_contains": ["兜底", "未编造"]
        },
        {
            "name": "场景2: 完全基于上下文的准确回答",
            "context": """【店铺1】外婆家（西湖店）（西湖/延安路/人均80元/评分4.8/杭帮菜）
招牌菜：西湖醋鱼、东坡肉、龙井虾仁""",
            "answer": "外婆家西湖店是一家杭帮菜餐厅，人均消费80元，评分4.8分，招牌菜包括西湖醋鱼、东坡肉和龙井虾仁。",
            "expected_score": 1.0,
            "expected_reason_contains": ["全部", "有依据", "支持"]
        },
        {
            "name": "场景3: 部分编造（添加了不存在的信息）",
            "context": """【店铺1】外婆家（西湖店）（西湖/延安路/人均80元/评分4.8）""",
            "answer": "外婆家西湖店是一家老字号杭帮菜餐厅，创立于1998年，人均消费80元，评分4.8分，餐厅环境古色古香，服务态度一流。",
            "expected_score": 0.4,
            "expected_reason_contains": ["部分", "超出", "编造"]
        },
        {
            "name": "场景4: 完全编造（与上下文无关）",
            "context": """【店铺1】星巴克（西湖店）（咖啡店/人均50元）""",
            "answer": "这是一家传统的杭帮菜餐厅，主打西湖醋鱼和东坡肉，是杭州老字号餐厅，有着百年历史。",
            "expected_score": 0.0,
            "expected_reason_contains": ["编造", "无关"]
        },
        {
            "name": "场景5: 空答案",
            "context": """【店铺1】外婆家（西湖店）（杭帮菜）""",
            "answer": "",
            "expected_score": 0.0,
            "expected_reason_contains": ["空"]
        },
        {
            "name": "场景6: 有合理推断的回答",
            "context": """【店铺1】外婆家（西湖店）（杭帮菜/人均80元/评分4.8/评论数5000）""",
            "answer": "外婆家西湖店是一家受欢迎的杭帮菜餐厅，人均80元，评分4.8分，有大量顾客评论。",
            "expected_score": 0.7,
            "expected_reason_contains": ["大部分", "有依据"]
        }
    ]
    
    scorer = FaithfulnessScorer()
    
    print("=" * 70)
    print("RAG 忠实度评分器 - 完整测试套件")
    print("=" * 70)
    
    passed = 0
    failed = 0
    
    for i, case in enumerate(test_cases, 1):
        print(f"\n测试用例 {i}: {case['name']}")
        print("-" * 70)
        
        result = scorer.score(case["context"], case["answer"])
        
        print(f"评分: {result['score']:.1f}")
        print(f"理由: {result['reason']}")
        
        # 验证结果
        score_match = abs(result["score"] - case["expected_score"]) < 0.15
        reason_match = any(keyword in result["reason"] for keyword in case["expected_reason_contains"])
        
        if score_match and reason_match:
            print("✓ 通过")
            passed += 1
        else:
            print(f"✗ 失败 (预期分数: {case['expected_score']:.1f}, 预期理由包含: {case['expected_reason_contains']})")
            failed += 1
    
    print("\n" + "=" * 70)
    print(f"测试结果: {passed} 通过, {failed} 失败")
    print("=" * 70)
    
    return passed, failed


def test_batch_evaluate():
    """测试批量评估功能"""
    
    print("\n\n" + "=" * 70)
    print("批量评估测试")
    print("=" * 70)
    
    test_cases = [
        {
            "context": "店铺：外婆家（杭帮菜）",
            "answer": "外婆家是一家杭帮菜餐厅"
        },
        {
            "context": "店铺：肯德基（快餐）",
            "answer": "根据上下文，无法获取详细信息"
        },
        {
            "context": "店铺：茶颜悦色（奶茶店）",
            "answer": "茶颜悦色是中国最好的奶茶品牌，有上百种口味"
        }
    ]
    
    results = batch_evaluate(test_cases)
    
    print("\n批量评估结果:")
    for result in results:
        print(f"  用例 {result['case_id']}: 分数={result['score']:.1f}, 理由={result['reason']}")


if __name__ == "__main__":
    passed, failed = run_test_suite()
    test_batch_evaluate()
    
    if failed == 0:
        print("\n🎉 所有测试通过！")
    else:
        print(f"\n⚠️ 有 {failed} 个测试未通过，需要调整评分逻辑")
