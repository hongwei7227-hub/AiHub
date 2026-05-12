"""
Plan F 改: 验证 composer-2-fast (4647) 在 20 条多样化 eval query 上的 judge 稳定性。
对比基线: sonnet-4.5 (4647).

测试设计: 模拟 Plan D `judgeAnswerRelevancy` 调用 — 给 (query, mock_answer) 让 judge 输出
严格 JSON {relevant: bool, reason: str}. 加 "不要搜索" 防御性指令 (用户其他窗口诊断结果).
concurrency=3 (匹配 Plan F 生产配置).
"""
import json
import time
import statistics
from concurrent.futures import ThreadPoolExecutor
from openai import OpenAI

EVAL_FILE = r"F:\project\data-prep\output\eval_queries.jsonl"
N = 20
CONCURRENCY = 3
MODELS = ["composer-2-fast", "sonnet-4.5"]

PROMPT_TEMPLATE = """你是 RAG 系统的相关性评分员。判断"模型生成的答案"是否对"用户问题"直接回答。

不要做搜索，不要说"我会搜索"，直接基于给出的信息评分。

判断标准：
- 直接回答用户问题（即使不完整也算）→ relevant=true
- 答非所问 / 答了别的话题 → relevant=false
- 答案是"根据现有上下文，无法完整回答"这类兜底回复 → relevant=false（没有真的回答）

输出严格 JSON：{{"relevant": true 或 false, "reason": "<不超过 15 字>"}}

用户问题：{query}

模型生成的答案：
{answer}
"""

# 模拟答案: 一半合格 (相关) + 一半不合格 (兜底/答非所问) 让 judge 有翻转
MOCK_ANSWERS_GOOD = [
    "推荐 Mamala（远洋乐堤港店）：人均 290 元，评分 4.9，主营创意杭帮菜。",
    "知味观西湖醋鱼，老字号杭帮菜代表，人均 80 元，西湖店地理位置便利。",
    "外婆家滨江店，性价比高的杭帮菜连锁，人均 60 元，茶香鸡是招牌。",
]
MOCK_ANSWERS_BAD = [
    "根据现有上下文，无法完整回答这个问题。",
    "建议您查阅大众点评 APP 获取最新店铺信息。",
]


def pick_queries():
    """按 collection 分布选 20 条: 12 shop / 5 review / 3 knowledge"""
    buckets = {"shop_profile_vector": 12, "blog_review_vector": 5, "knowledge_vector": 3}
    pick = []
    with open(EVAL_FILE, encoding="utf-8") as f:
        for line in f:
            q = json.loads(line)
            c = q.get("target_collection")
            if buckets.get(c, 0) > 0:
                pick.append(q)
                buckets[c] -= 1
                if len(pick) >= N:
                    break
    return pick


def make_call(client, model, q, idx):
    answer = MOCK_ANSWERS_GOOD[idx % len(MOCK_ANSWERS_GOOD)] if idx % 3 != 0 \
        else MOCK_ANSWERS_BAD[idx % len(MOCK_ANSWERS_BAD)]
    prompt = PROMPT_TEMPLATE.format(query=q["query"], answer=answer)
    t0 = time.time()
    try:
        r = client.chat.completions.create(
            model=model,
            messages=[{"role": "user", "content": prompt}],
            temperature=0.0,
            max_tokens=200,
            timeout=120,
        )
        elapsed = time.time() - t0
        text = r.choices[0].message.content
        # 解析 JSON (lenient: 找第一个 {...}.)
        parsed = None
        try:
            parsed = json.loads(text.strip())
        except Exception:
            lb, rb = text.find("{"), text.rfind("}")
            if lb >= 0 and rb > lb:
                try:
                    parsed = json.loads(text[lb : rb + 1])
                except Exception:
                    pass
        return {
            "qid": q["query_id"],
            "collection": q["target_collection"].replace("_vector", ""),
            "elapsed": elapsed,
            "ok": parsed is not None and "relevant" in parsed,
            "relevant": parsed.get("relevant") if parsed else None,
            "reason": parsed.get("reason", "")[:40] if parsed else text[:60],
            "raw_first": text[:80],
            "out_tok": r.usage.completion_tokens,
        }
    except Exception as e:
        return {
            "qid": q["query_id"],
            "collection": q["target_collection"].replace("_vector", ""),
            "elapsed": time.time() - t0,
            "ok": False,
            "relevant": None,
            "reason": str(e)[:80],
            "raw_first": "",
            "out_tok": 0,
        }


def run_one_model(model, queries):
    client = OpenAI(base_url="http://127.0.0.1:4647/v1", api_key="dummy")
    print(f"\n=== {model} (n={len(queries)}, concurrency={CONCURRENCY}) ===")
    t0 = time.time()
    results = [None] * len(queries)
    with ThreadPoolExecutor(max_workers=CONCURRENCY) as pool:
        futures = {pool.submit(make_call, client, model, q, i): i for i, q in enumerate(queries)}
        for f in futures:
            i = futures[f]
            results[i] = f.result()
    total = time.time() - t0

    # stats
    times = [r["elapsed"] for r in results]
    ok_count = sum(1 for r in results if r["ok"])
    fail_count = len(results) - ok_count
    times_sorted = sorted(times)
    p50 = times_sorted[len(times_sorted) // 2]
    p95 = times_sorted[int(len(times_sorted) * 0.95)]
    out_toks = [r["out_tok"] for r in results if r["out_tok"] > 0]

    print(f"  total wall-clock: {total:.1f}s")
    print(f"  per-call P50/P95: {p50:.1f}s / {p95:.1f}s")
    print(f"  avg out_tokens:   {statistics.mean(out_toks):.0f}" if out_toks else "  avg out_tokens: n/a")
    print(f"  parse OK: {ok_count}/{len(results)} ({100*ok_count/len(results):.0f}%)")
    print(f"  parse FAIL: {fail_count}")

    by_col = {}
    for r in results:
        by_col.setdefault(r["collection"], []).append(r)
    for col, rs in by_col.items():
        ok = sum(1 for r in rs if r["ok"])
        rel_true = sum(1 for r in rs if r["relevant"] is True)
        rel_false = sum(1 for r in rs if r["relevant"] is False)
        print(f"  [{col:18s}] n={len(rs)} ok={ok} relevant=true={rel_true} false={rel_false}")

    print("\n  sample outputs:")
    for r in results[:8]:
        flag = "✓" if r["ok"] else "✗"
        rel = "T" if r["relevant"] is True else ("F" if r["relevant"] is False else "?")
        print(f"   {flag} qid={r['qid']:3d} {r['collection']:8s} {r['elapsed']:5.1f}s rel={rel} reason={r['reason']!r}")
        if not r["ok"]:
            print(f"     (raw_first: {r['raw_first']!r})")
    return results, total


def main():
    queries = pick_queries()
    print(f"Picked {len(queries)} queries")
    from collections import Counter
    print(Counter(q["target_collection"] for q in queries))

    summary = {}
    for m in MODELS:
        results, total = run_one_model(m, queries)
        ok = sum(1 for r in results if r["ok"])
        summary[m] = {
            "total_s": total,
            "p50_s": sorted([r["elapsed"] for r in results])[len(results) // 2],
            "ok_rate": ok / len(results),
        }

    print("\n=== Final comparison ===")
    print(f"{'model':18s}  total_s  P50    parse_ok")
    for m, s in summary.items():
        print(f"{m:18s}  {s['total_s']:6.1f}  {s['p50_s']:5.1f}  {s['ok_rate']*100:.0f}%")


if __name__ == "__main__":
    main()
