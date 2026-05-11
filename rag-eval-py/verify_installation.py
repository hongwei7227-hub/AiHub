#!/usr/bin/env python3
"""
忠实度评分器安装验证脚本
验证所有核心功能是否正常工作
"""
import sys
import json


def check_imports():
    """检查必要的导入"""
    print("=" * 70)
    print("1. 检查依赖包...")
    print("=" * 70)
    
    required_modules = {
        'json': '标准库',
        're': '标准库',
        'requests': 'HTTP 请求库（LLM API）',
    }
    
    missing = []
    for module, desc in required_modules.items():
        try:
            __import__(module)
            print(f"  ✓ {module:20s} - {desc}")
        except ImportError:
            print(f"  ✗ {module:20s} - {desc} (缺失)")
            missing.append(module)
    
    if missing:
        print(f"\n⚠️ 缺少依赖包: {', '.join(missing)}")
        print("   请运行: pip install -r requirements_faithfulness.txt")
        return False
    
    print("\n✓ 所有必要的依赖包已安装")
    return True


def check_scorer():
    """检查基于规则的评分器"""
    print("\n" + "=" * 70)
    print("2. 检查基于规则的评分器...")
    print("=" * 70)
    
    try:
        from faithfulness_scorer import FaithfulnessScorer
        
        scorer = FaithfulnessScorer()
        
        # 测试兜底回复
        context = "店铺：外婆家"
        answer = "根据现有上下文，无法完整回答。"
        
        result = scorer.score(context, answer)
        
        print(f"\n  测试案例: 兜底回复")
        print(f"  上下文: {context}")
        print(f"  答案: {answer}")
        print(f"  评分: {result['score']:.1f}")
        print(f"  理由: {result['reason']}")
        
        if result['score'] == 0.7:
            print("\n✓ 基于规则的评分器工作正常")
            return True
        else:
            print(f"\n⚠️ 评分结果异常，预期 0.7，实际 {result['score']:.1f}")
            return False
            
    except Exception as e:
        print(f"\n✗ 评分器检查失败: {e}")
        return False


def check_llm_scorer():
    """检查基于 LLM 的评分器"""
    print("\n" + "=" * 70)
    print("3. 检查基于 LLM 的评分器...")
    print("=" * 70)
    
    try:
        from faithfulness_scorer_llm import LLMFaithfulnessScorer
        
        print("  ✓ LLM 评分器模块导入成功")
        print("  ⚠️ 需要 API Key 才能测试实际功能")
        print("     设置方法: export SILICONFLOW_API_KEY='your-key'")
        print("     或从 application.yaml 读取")
        
        return True
        
    except Exception as e:
        print(f"  ✗ LLM 评分器模块导入失败: {e}")
        return False


def check_files():
    """检查所有必要的文件"""
    print("\n" + "=" * 70)
    print("4. 检查文件完整性...")
    print("=" * 70)
    
    import os
    
    required_files = {
        'faithfulness_scorer.py': '基于规则的评分器',
        'faithfulness_scorer_llm.py': '基于 LLM 的评分器',
        'demo_user_example.py': '用户示例演示',
        'test_faithfulness.py': '测试套件',
        'eval_faithfulness_integration.py': '集成示例',
        'requirements_faithfulness.txt': '依赖包列表',
        'README_FAITHFULNESS.md': '完整文档',
        'FAITHFULNESS_QUICKSTART.md': '快速开始',
        'FAITHFULNESS_SUMMARY.md': '实现总结',
    }
    
    missing = []
    for filename, desc in required_files.items():
        if os.path.exists(filename):
            print(f"  ✓ {filename:40s} - {desc}")
        else:
            print(f"  ✗ {filename:40s} - {desc} (缺失)")
            missing.append(filename)
    
    if missing:
        print(f"\n⚠️ 缺少文件: {', '.join(missing)}")
        return False
    
    print("\n✓ 所有必要文件都存在")
    return True


def run_quick_test():
    """运行快速测试"""
    print("\n" + "=" * 70)
    print("5. 运行快速测试...")
    print("=" * 70)
    
    try:
        from faithfulness_scorer import FaithfulnessScorer
        
        scorer = FaithfulnessScorer()
        
        test_cases = [
            {
                "name": "兜底回复",
                "context": "店铺信息",
                "answer": "根据现有上下文，无法完整回答。",
                "expected": 0.7
            },
            {
                "name": "空答案",
                "context": "店铺信息",
                "answer": "",
                "expected": 0.0
            }
        ]
        
        passed = 0
        failed = 0
        
        for case in test_cases:
            result = scorer.score(case["context"], case["answer"])
            
            if abs(result["score"] - case["expected"]) < 0.01:
                print(f"  ✓ {case['name']:15s} - 评分 {result['score']:.1f}")
                passed += 1
            else:
                print(f"  ✗ {case['name']:15s} - 评分 {result['score']:.1f} (预期 {case['expected']:.1f})")
                failed += 1
        
        print(f"\n  测试结果: {passed} 通过, {failed} 失败")
        
        return failed == 0
        
    except Exception as e:
        print(f"\n✗ 快速测试失败: {e}")
        return False


def print_usage_guide():
    """打印使用指南"""
    print("\n" + "=" * 70)
    print("6. 使用指南")
    print("=" * 70)
    
    print("""
快速开始命令:

1. 评估用户示例（推荐）:
   python demo_user_example.py

2. 运行完整测试套件:
   python test_faithfulness.py

3. 集成评估示例:
   python eval_faithfulness_integration.py

4. 使用 LLM 评分（需要 API Key）:
   export SILICONFLOW_API_KEY="your-key"
   python faithfulness_scorer_llm.py

文档:
- 完整文档: README_FAITHFULNESS.md
- 快速开始: FAITHFULNESS_QUICKSTART.md
- 实现总结: FAITHFULNESS_SUMMARY.md
""")


def main():
    """主函数"""
    print("\n" + "=" * 70)
    print("RAG 忠实度评分器 - 安装验证")
    print("=" * 70)
    
    checks = [
        ("依赖包", check_imports),
        ("评分器", check_scorer),
        ("LLM评分器", check_llm_scorer),
        ("文件", check_files),
        ("快速测试", run_quick_test),
    ]
    
    results = []
    for name, check_func in checks:
        try:
            success = check_func()
            results.append((name, success))
        except Exception as e:
            print(f"\n✗ {name}检查出错: {e}")
            results.append((name, False))
    
    # 打印总结
    print("\n" + "=" * 70)
    print("验证总结")
    print("=" * 70)
    
    for name, success in results:
        status = "✓" if success else "✗"
        print(f"  {status} {name}")
    
    all_passed = all(success for _, success in results)
    
    if all_passed:
        print("\n" + "=" * 70)
        print("🎉 所有检查通过！系统已就绪")
        print("=" * 70)
        print_usage_guide()
        return 0
    else:
        print("\n" + "=" * 70)
        print("⚠️ 部分检查未通过，请查看上述错误信息")
        print("=" * 70)
        return 1


if __name__ == "__main__":
    sys.exit(main())
