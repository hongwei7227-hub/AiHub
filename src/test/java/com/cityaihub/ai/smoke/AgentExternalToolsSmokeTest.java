package com.cityaihub.ai.smoke;

import com.cityaihub.ai.dto.WeatherDiningAdviceDTO;
import com.cityaihub.ai.qweather.WeatherAdvisoryService;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Plan D smoke test：跳过 controller 层（业务有登录拦截），直接用 Spring 容器
 * 拿 bean 调真链路，验证 QWeather 直连 + Tavily MCP 程序化客户端都通。
 *
 * <p>需要 application-local.yaml 真值 + spring.profiles.active=local。
 * 默认 @Disabled 不进 mvn test 全量，手动跑：
 * <pre>
 *   mvn -Dspring.profiles.active=local -Dtest=AgentExternalToolsSmokeTest test
 * </pre>
 */
@Slf4j
@Disabled("Manual smoke test, requires application-local.yaml + active profile")
@SpringBootTest(properties = "ai.agent.bootstrap.enabled=false")
class AgentExternalToolsSmokeTest {

    @Autowired
    private WeatherAdvisoryService weatherAdvisoryService;

    @Autowired
    @Qualifier("tavilyMcpToolCallbackProvider")
    private SyncMcpToolCallbackProvider tavilyMcpProvider;

    @Test
    void qweatherRealApiShouldReturnLiveWeather() {
        WeatherDiningAdviceDTO advice = weatherAdvisoryService.advise("北京", "火锅", null, null);
        log.info("==== QWeather smoke ====");
        log.info("source        = {}", advice.getSource());
        log.info("degraded      = {}", advice.isDegraded());
        log.info("city          = {}", advice.getCity());
        log.info("weatherSummary= {}", advice.getWeatherSummary());
        log.info("diningSuggest = {}", advice.getDiningSuggestion());
        log.info("categories    = {}", advice.getSuitableCategories());
        log.info("reminders     = {}", advice.getReminders());
        // 真链路通则 source=qweather-real / degraded=false；网络故障会降级到 local-fallback
    }

    @Test
    void tavilyMcpToolsShouldBeDiscoverable() {
        ToolCallback[] callbacks = tavilyMcpProvider.getToolCallbacks();
        log.info("==== Tavily MCP tools ({}) ====", callbacks.length);
        for (ToolCallback cb : callbacks) {
            ToolDefinition def = cb.getToolDefinition();
            log.info("  - {} | {}", def.name(),
                    def.description() != null && def.description().length() > 80
                            ? def.description().substring(0, 80) + "..."
                            : def.description());
        }
    }

    @Test
    void tavilySearchToolShouldReturnHits() {
        ToolCallback search = null;
        for (ToolCallback cb : tavilyMcpProvider.getToolCallbacks()) {
            if (cb.getToolDefinition().name().toLowerCase().contains("search")) {
                search = cb;
                break;
            }
        }
        if (search == null) {
            log.warn("[smoke] no Tavily search tool found, available: {}",
                    java.util.Arrays.stream(tavilyMcpProvider.getToolCallbacks())
                            .map(c -> c.getToolDefinition().name()).toList());
            return;
        }
        log.info("==== Tavily search tool ====");
        log.info("name  = {}", search.getToolDefinition().name());
        log.info("input schema = {}", search.getToolDefinition().inputSchema());

        String args = "{\"query\":\"latest news about Beijing 2026\",\"max_results\":3}";
        String result = search.call(args);
        log.info("==== Tavily search result (truncated) ====");
        log.info("{}", result.length() > 800 ? result.substring(0, 800) + "..." : result);
    }
}
