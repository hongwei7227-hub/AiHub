package com.hmdp.ai.agent.loop;

import com.hmdp.ai.tool.AgentLoopControlTools;
import com.hmdp.ai.tool.DianPingAgentTools;
import com.hmdp.ai.tool.LocalLifeTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地工具注册表。
 * 这里把项目内的 @Tool 方法转换成 Spring AI 的 ToolCallback，供 AgentLoop 手动调用。
 *
 * <p>Plan: 同时合并 Spring AI 自动发现的 MCP {@link ToolCallback}（如 Tavily 联网搜索），
 * 通过 {@link ObjectProvider} 容错——MCP client 不启用时不报错，只 log skip。
 */
@Slf4j
@Component
public class AgentLoopToolRegistry {

    private final List<ToolCallback> toolCallbacks;

    private final Map<String, ToolCallback> callbackMap;

    @Autowired
    public AgentLoopToolRegistry(DianPingAgentTools dianPingAgentTools,
                                 AgentLoopControlTools controlTools,
                                 LocalLifeTools localLifeTools,
                                 ObjectProvider<SyncMcpToolCallbackProvider> mcpProviderProvider) {
        List<ToolCallback> all = new ArrayList<>(Arrays.asList(
                ToolCallbacks.from(dianPingAgentTools, controlTools, localLifeTools)));

        SyncMcpToolCallbackProvider mcpProvider = mcpProviderProvider.getIfAvailable();
        if (mcpProvider != null) {
            ToolCallback[] mcpCallbacks = mcpProvider.getToolCallbacks();
            all.addAll(Arrays.asList(mcpCallbacks));
            log.info("[agent-tools] loaded {} MCP tools", mcpCallbacks.length);
        } else {
            log.info("[agent-tools] MCP client not enabled, skip MCP tools");
        }

        this.toolCallbacks = List.copyOf(all);
        Map<String, ToolCallback> map = new LinkedHashMap<>();
        for (ToolCallback toolCallback : all) {
            map.put(toolCallback.getToolDefinition().name(), toolCallback);
        }
        this.callbackMap = Map.copyOf(map);
    }

    // 这个构造器只给测试使用，便于注入假的 ToolCallback 集合。
    AgentLoopToolRegistry(List<ToolCallback> toolCallbacks) {
        this.toolCallbacks = List.copyOf(toolCallbacks);
        Map<String, ToolCallback> map = new LinkedHashMap<>();
        for (ToolCallback toolCallback : toolCallbacks) {
            map.put(toolCallback.getToolDefinition().name(), toolCallback);
        }
        this.callbackMap = Map.copyOf(map);
    }

    public List<ToolCallback> getToolCallbacks() {
        return toolCallbacks;
    }

    public ToolCallback getToolCallback(String toolName) {
        return callbackMap.get(toolName);
    }
}
