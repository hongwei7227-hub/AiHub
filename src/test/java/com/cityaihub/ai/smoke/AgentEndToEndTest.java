package com.cityaihub.ai.smoke;

import com.cityaihub.ai.agent.loop.AgentLoopExecutor;
import com.cityaihub.ai.agent.loop.AgentLoopRequest;
import com.cityaihub.ai.agent.loop.AgentLoopResult;
import com.cityaihub.ai.agent.loop.AgentLoopTrace;
import com.cityaihub.ai.agent.loop.ToolExecutionLog;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Plan D 端到端 Agent loop 测试：跳过 controller / 鉴权层，直接调 AgentLoopExecutor。
 * <b>会真烧 LLM token</b>（硅基流动 Qwen2.5-7B chat completion）。
 *
 * <p>验证目标：
 * <ol>
 *   <li>LLM 在天气场景 query 下会主动调 getWeatherDiningAdvice 工具
 *   <li>LLM 在联网搜索场景 query 下会主动调 tavily_search 工具
 *   <li>工具结果被塞回 LLM 上下文，最终 finalAnswer 引用真实数据
 * </ol>
 *
 * <p>默认 @Disabled，手动跑：
 * <pre>
 *   mvn -Dspring.profiles.active=local '-Djunit.jupiter.conditions.deactivate=org.junit.*DisabledCondition' '-Dtest=AgentEndToEndTest' test
 * </pre>
 */
@Slf4j
@Disabled("Manual: requires local profile + burns Qwen2.5-7B tokens")
@SpringBootTest
class AgentEndToEndTest {

    @Autowired
    private AgentLoopExecutor executor;

    @Test
    void weatherQueryShouldTriggerQWeatherTool() {
        AgentLoopRequest req = new AgentLoopRequest();
        req.setPrompt("今天北京天气怎么样？适合吃什么？给我推荐火锅类的店。");
        AgentLoopResult result = executor.execute(req);

        log.info("==== Agent end-to-end (weather) ====");
        dumpResult(result, "weather");
    }

    @Test
    void newsQueryShouldTriggerTavilySearchTool() {
        AgentLoopRequest req = new AgentLoopRequest();
        req.setPrompt("最近北京有什么新开的网红咖啡店或者餐饮新闻？查一下最新的资讯。");
        AgentLoopResult result = executor.execute(req);

        log.info("==== Agent end-to-end (news/tavily) ====");
        dumpResult(result, "news");
    }

    private void dumpResult(AgentLoopResult result, String tag) {
        var execution = result.getAgentLoopExecution();
        log.info("[{}] status        = {}", tag, execution.getStatus());
        log.info("[{}] totalSteps    = {} / max {}", tag, execution.getTotalSteps(), execution.getMaxSteps());
        log.info("[{}] terminatedByTool = {}", tag, execution.isTerminatedByTool());
        log.info("[{}] errorMessage  = {}", tag, execution.getErrorMessage());
        log.info("[{}] toolLogs ({}):", tag, result.getToolLogs().size());
        for (ToolExecutionLog tl : result.getToolLogs()) {
            String payload = String.valueOf(tl.getPayload());
            log.info("    - tool={} | payload(first 250)={}", tl.getToolName(),
                    payload.length() > 250 ? payload.substring(0, 250) + "..." : payload);
        }
        log.info("[{}] traces ({}):", tag, execution.getTraces().size());
        for (AgentLoopTrace tr : execution.getTraces()) {
            log.info("    - step={} status={} tools={}", tr.getStep(), tr.getStatus(), tr.getToolNames());
        }
        log.info("[{}] finalAnswer:\n{}", tag, execution.getFinalAnswer());
    }
}
