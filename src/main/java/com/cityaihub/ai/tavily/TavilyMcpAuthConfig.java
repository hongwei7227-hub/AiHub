package com.hmdp.ai.tavily;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Plan: Tavily MCP 程序化接入。
 *
 * <p>背景：Spring AI 1.1.2 的 yaml-driven streamable-http auto-config 实测有 bug——
 * 配的 endpoint=/mcp/ 没用上，实际硬编码 GET /sse，触发 Tavily 404。绕开 auto-config，
 * 用 MCP SDK 0.17.0 的 {@link HttpClientStreamableHttpTransport} 直接构建 client。
 *
 * <p>架构：
 * <ol>
 *   <li>{@link HttpClientStreamableHttpTransport} 直连 Tavily endpoint，httpRequestCustomizer
 *       注入 Authorization Bearer header（Tavily 接受这个）
 *   <li>{@link McpSyncClient} 用 transport 包装 + 初始化协议握手
 *   <li>{@link SyncMcpToolCallbackProvider} 暴露 ToolCallback 给 AgentLoopToolRegistry
 * </ol>
 *
 * <p>启用条件：{@code tavily.enabled=true}（默认 false 业务启动不强求）。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "tavily", name = "enabled", havingValue = "true")
public class TavilyMcpAuthConfig {

    @Value("${tavily.url}")
    private String tavilyUrl;

    @Value("${tavily.api-key}")
    private String tavilyApiKey;

    /**
     * 程序化 Tavily MCP client。Spring 容器关闭时自动调用 close()。
     */
    @Bean(destroyMethod = "close")
    public McpSyncClient tavilyMcpClient() {
        // 拆 url 为 base + endpoint：HttpClientStreamableHttpTransport.builder() 第一参是 base url
        // tavilyUrl 形如 https://mcp.tavily.com/mcp/，分离出 endpoint 部分
        String base;
        String endpoint;
        java.net.URI parsed = java.net.URI.create(tavilyUrl);
        base = parsed.getScheme() + "://" + parsed.getHost()
                + (parsed.getPort() == -1 ? "" : ":" + parsed.getPort());
        endpoint = parsed.getPath();
        if (endpoint == null || endpoint.isEmpty()) {
            endpoint = "/mcp";
        }
        log.info("[tavily-mcp] building transport: base={}, endpoint={}", base, endpoint);

        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder(base)
                .endpoint(endpoint)
                .httpRequestCustomizer((builder, method, uri, body, context) -> {
                    builder.header("Authorization", "Bearer " + tavilyApiKey);
                })
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("cityaihub-agent", "1.0.0"))
                .requestTimeout(Duration.ofSeconds(30))
                .build();

        // 初始化协议握手——失败 throw，业务启动会 fail-fast，方便发现配置问题
        client.initialize();
        log.info("[tavily-mcp] MCP client initialized successfully");
        return client;
    }

    /**
     * 把 Tavily 的 MCP tools 暴露成 ToolCallback，供 AgentLoopToolRegistry 合并到
     * Agent 工具集。AgentLoopToolRegistry 已用 ObjectProvider&lt;SyncMcpToolCallbackProvider&gt; 注入。
     */
    @Bean
    public SyncMcpToolCallbackProvider tavilyMcpToolCallbackProvider(McpSyncClient tavilyMcpClient) {
        return new SyncMcpToolCallbackProvider(List.of(tavilyMcpClient));
    }
}
