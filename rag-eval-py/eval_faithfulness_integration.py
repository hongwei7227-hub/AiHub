#!/usr/bin/env python3
"""
忠实度评估集成示例
演示如何将忠实度评分集成到现有的 RAG 评估流程
"""
import json
import os
from typing import List, Dict, Any
from datetime import datetime


DEFAULT_EVAL_FILE = os.environ.get(
    "EVAL_QUERIES_FILE",
    r"F:\project\hm-dianping-data-prep\output\eval_queries.jsonl"
)


def load_eval_queries(file_path: str = DEFAULT_EVAL_FILE) -> List[Dict]:
    """加载评估查询集"""
    queries = []
    if not os.path.exists(file_path):
        print(f"⚠️ 文件不存在: {file_path}")
        return queries
    
    with open(file_path, 'r', encoding='utf-8') as f:
        for line in f:
            if line.strip():
                queries.append(json.loads(line))
    
    return queries


def simulate_rag_generation(query: str, retrieved_docs: List[str]) -> str:
    """
    模拟 RAG 生成答案
    实际使用时应替换为真实的 LLM 生成逻辑
    """
    # 这里只是示例，返回一个模拟的答案
    if not retrieved_docs:
        return "根据现有上下文，无法完整回答。"
    
    # 简单拼接上下文信息作为答案（实际应使用 LLM）
    return f"根据上下文，找到了 {len(retrieved_docs)} 家相关店铺。"


def format_context_from_docs(docs: List[Dict]) -> str:
    """将检索到的文档格式化为上下文片段"""
    context_parts = []
    for i, doc in enumerate(docs, 1):
        shop_info = f"【店铺{i}】{doc.get('shopName', 'Unknown')}"
        
        # 添加详细信息
        details = []
        if 'category' in doc:
            details.append(doc['category'])
        if 'avgPrice' in doc:
            details.append(f"人均{doc['avgPrice']}元")
        if 'rating' in doc:
            details.append(f"评分{doc['rating']}")
        
        if details:
            shop_info += f"（{'/'.join(details)}）"
        
        context_parts.append(shop_info)
    
    return "\n\n".join(context_parts)


def evaluate_with_faithfulness(
    queries: List[Dict],
    use_llm: bool = False,
    api_key: str = None
) -> Dict[str, Any]:
    """
    执行完整的忠实度评估
    
    Args:
        queries: 评估查询列表
        use_llm: 是否使用 LLM 评分器
        api_key: API Key（如果使用 LLM）
    
    Returns:
        评估结果统计
    """
    
    if use_llm:
        try:
            from faithfulness_scorer_llm import LLMFaithfulnessScorer
            scorer = LLMFaithfulnessScorer(api_key=api_key)
            print("✓ 使用 LLM 评分器")
        except Exception as e:
            print(f"⚠️ LLM 评分器初始化失败: {e}")
            print("  降级使用基于规则的评分器")
            from faithfulness_scorer import FaithfulnessScorer
            scorer = FaithfulnessScorer()
    else:
        from faithfulness_scorer import FaithfulnessScorer
        scorer = FaithfulnessScorer()
        print("✓ 使用基于规则的评分器")
    
    results = []
    total_score = 0.0
    score_distribution = {
        "1.0": 0,  # 完全忠实
        "0.7": 0,  # 大部分忠实
        "0.4": 0,  # 部分编造
        "0.0": 0   # 大部分编造
    }
    
    print(f"\n开始评估 {len(queries)} 个查询...\n")
    
    for i, query_item in enumerate(queries, 1):
        query = query_item.get('query', '')
        
        # 模拟检索过程（实际应调用真实的检索服务）
        # 这里只是示例数据
        retrieved_docs = [
            {"shopName": f"店铺{j}", "category": "杭帮菜", "avgPrice": 80, "rating": 4.5}
            for j in range(3)
        ]
        
        # 格式化上下文
        context = format_context_from_docs(retrieved_docs)
        
        # 生成答案（实际应调用 LLM）
        answer = simulate_rag_generation(query, retrieved_docs)
        
        # 忠实度评分
        eval_result = scorer.score(context, answer)
        
        # 分类统计
        score = eval_result["score"]
        if score >= 0.95:
            score_distribution["1.0"] += 1
        elif score >= 0.6:
            score_distribution["0.7"] += 1
        elif score >= 0.3:
            score_distribution["0.4"] += 1
        else:
            score_distribution["0.0"] += 1
        
        total_score += score
        
        result = {
            "query_id": i,
            "query": query,
            "answer": answer,
            "faithfulness_score": score,
            "reason": eval_result["reason"]
        }
        results.append(result)
        
        print(f"[{i}/{len(queries)}] Query: {query[:30]}...")
        print(f"         Score: {score:.2f} - {eval_result['reason']}")
    
    # 统计
    avg_score = total_score / len(queries) if queries else 0.0
    
    summary = {
        "total_queries": len(queries),
        "avg_faithfulness_score": round(avg_score, 3),
        "score_distribution": score_distribution,
        "results": results
    }
    
    return summary


def generate_faithfulness_report(summary: Dict, output_dir: str = "docs"):
    """生成忠实度评估报告"""
    
    os.makedirs(output_dir, exist_ok=True)
    
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    report_file = os.path.join(output_dir, f"faithfulness_eval_report_{timestamp}.md")
    
    with open(report_file, 'w', encoding='utf-8') as f:
        f.write("# RAG 忠实度评估报告\n\n")
        f.write(f"生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n\n")
        
        f.write("## 评估概览\n\n")
        f.write(f"- 总查询数: {summary['total_queries']}\n")
        f.write(f"- 平均忠实度: {summary['avg_faithfulness_score']:.3f}\n\n")
        
        f.write("## 分数分布\n\n")
        f.write("| 分数区间 | 数量 | 占比 | 说明 |\n")
        f.write("|---------|------|------|------|\n")
        
        total = summary['total_queries']
        dist = summary['score_distribution']
        f.write(f"| 1.0 | {dist['1.0']} | {dist['1.0']/total*100:.1f}% | 完全忠实 |\n")
        f.write(f"| 0.7 | {dist['0.7']} | {dist['0.7']/total*100:.1f}% | 大部分忠实 |\n")
        f.write(f"| 0.4 | {dist['0.4']} | {dist['0.4']/total*100:.1f}% | 部分编造 |\n")
        f.write(f"| 0.0 | {dist['0.0']} | {dist['0.0']/total*100:.1f}% | 大部分编造 |\n\n")
        
        f.write("## 详细结果\n\n")
        
        # 按分数排序，低分在前（需要关注的问题）
        sorted_results = sorted(summary['results'], key=lambda x: x['faithfulness_score'])
        
        for result in sorted_results:
            f.write(f"### Query {result['query_id']}: {result['query']}\n\n")
            f.write(f"**忠实度**: {result['faithfulness_score']:.2f} - {result['reason']}\n\n")
            f.write(f"**生成答案**: {result['answer']}\n\n")
            f.write("---\n\n")
    
    print(f"\n✓ 报告已生成: {report_file}")
    return report_file


def main():
    """主函数"""
    
    print("=" * 70)
    print("RAG 忠实度评估集成示例")
    print("=" * 70)
    
    # 场景1: 使用基于规则的评分器（快速验证）
    print("\n【场景1】使用基于规则的评分器进行快速评估\n")
    
    # 创建示例数据（实际使用时从 eval_queries.jsonl 加载）
    sample_queries = [
        {"query": "杭州有什么好吃的杭帮菜"},
        {"query": "推荐拱墅区的火锅店"},
        {"query": "西湖附近有什么好玩的"},
    ]
    
    summary = evaluate_with_faithfulness(sample_queries, use_llm=False)
    
    print(f"\n评估完成！")
    print(f"平均忠实度: {summary['avg_faithfulness_score']:.3f}")
    print(f"分数分布: {summary['score_distribution']}")
    
    # 生成报告
    report_file = generate_faithfulness_report(summary)
    
    # 场景2: 使用 LLM 评分器（高精度评估）
    print("\n" + "=" * 70)
    print("【场景2】使用 LLM 评分器进行高精度评估")
    print("=" * 70)
    print("\n提示: 需要设置 SILICONFLOW_API_KEY 环境变量")
    print("跳过 LLM 评估演示（如需运行，请配置 API Key 并取消注释）\n")
    
    # 取消下面的注释以运行 LLM 评估
    # summary_llm = evaluate_with_faithfulness(sample_queries, use_llm=True)
    # report_file_llm = generate_faithfulness_report(summary_llm)


if __name__ == "__main__":
    main()
