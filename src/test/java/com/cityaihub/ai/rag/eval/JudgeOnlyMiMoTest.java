package com.cityaihub.ai.rag.eval;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * judge-only sanity check：用硬编码 (query, answer, contexts) 验证 mimo-v2.5 当 generation judge 是否可用。
 * 不依赖 retrieval（避开 SiliconFlow embedding DNS 问题），不依赖 RagAnswerGenerator。
 *
 * 跑：
 *   $env:LLM_JUDGE_BASE_URL='https://token-plan-cn.xiaomimimo.com'
 *   $env:LLM_JUDGE_MODEL='mimo-v2.5'
 *   $env:LLM_JUDGE_API_KEY='tp-...'
 *   mvn test -Dtest=JudgeOnlyMiMoTest
 */
@Slf4j
@SpringBootTest(properties = {
        "ai.agent.bootstrap.enabled=false",
        "rag.eval.llm-judge.enabled=true",
        "rag.eval.generation.enabled=true",
        "rag.bm25.enabled=false",   // 不需要 BM25
        "rag.rerank.enabled=false", // 不需要 rerank
})
class JudgeOnlyMiMoTest {

    @Autowired private GenerationJudge judge;

    private record Case(String query, String answer, String contexts) {}

    @Test
    void runFiveCases() {
        List<Case> cases = List.of(
                new Case(
                        "杭州有什么好吃的杭帮菜？",
                        "根据上下文，推荐 伊家鲜·经典杭帮菜(黄龙店)，评分4.6，人均106元；以及 老杭州，评分4.2，人均60元。",
                        "店铺名：伊家鲜·经典杭帮菜(黄龙店) 均价：106 评分：4.6\n店铺名：老杭州 均价：60 评分：4.2"
                ),
                new Case(
                        "杭州的甜品推荐",
                        "推荐 玉玲珑（评分4.3，人均170元）和 老杭州（评分4.2）。",
                        "店铺名：玉玲珑 均价：170 评分：4.3 商圈：杭大路2号黄龙饭店西门"
                ),
                new Case(
                        "上海有什么好吃的？",
                        "根据现有上下文，无法完整回答。",
                        "店铺名：杭州小吃 均价：30 评分：4.0"
                ),
                new Case(
                        "这家店适合请客吃饭吗？",
                        "适合。评论说该店环境优雅，适合商务请客。",
                        "评论1：环境很好，适合请客。\n评论2：菜品精致，价格合理。"
                ),
                new Case(
                        "招牌菜东坡肉好吃吗？",
                        "天外天的招牌东坡肉广受好评。北京烤鸭也很受欢迎。",
                        "评论1：东坡肉一绝，肥而不腻。\n评论2：服务好，菜品味道正宗。"
                )
        );

        log.info("===== JudgeOnlyMiMoTest: {} cases =====", cases.size());
        long totalStart = System.currentTimeMillis();

        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            log.info("---- Case {} ----", i + 1);
            log.info("Q: {}", c.query);
            log.info("A: {}", c.answer);

            long t1 = System.currentTimeMillis();
            GenerationJudge.FaithResult faith = judge.judgeFaithfulness(c.answer, c.contexts);
            long faithMs = System.currentTimeMillis() - t1;

            long t2 = System.currentTimeMillis();
            GenerationJudge.RelevancyResult rel = judge.judgeAnswerRelevancy(c.query, c.answer);
            long relMs = System.currentTimeMillis() - t2;

            log.info("Faithfulness: {} ({}ms) reason={}", String.format("%.2f", faith.score), faithMs, faith.reason);
            log.info("Relevancy:    {} ({}ms) reason={}", rel.relevant ? "✓" : "✗", relMs, rel.reason);
        }

        long elapsed = System.currentTimeMillis() - totalStart;
        log.info("===== DONE total {}ms (10 judge calls, avg {}ms/call) =====",
                elapsed, elapsed / 10);
        log.info("LLM calls={} failures={}", judge.llmCallCount(), judge.llmFailureCount());
    }
}
