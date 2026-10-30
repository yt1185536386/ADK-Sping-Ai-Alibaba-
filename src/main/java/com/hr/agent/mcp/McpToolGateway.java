package com.hr.agent.mcp;

import io.modelcontextprotocol.client.McpSyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * MCP 工具网关：统一管理所有 MCP Client 连接，并按需把远端工具适配成 {@link ToolCallback}。
 *
 * <p>Spring AI 的 {@code spring-ai-starter-mcp-client} 会根据 application.yml 中的
 * {@code spring.ai.mcp.client.*} 配置自动创建并注入一组 {@link McpSyncClient}
 * （stdio / SSE / Streamable HTTP 均支持）。本网关把这些 client 收口，
 * 让上层（AgentFactory / 各模块 Agent）用「按名称取工具」的方式使用，
 * 无需关心底层传输协议。
 */
@Component
public class McpToolGateway {

    private static final Logger log = LoggerFactory.getLogger(McpToolGateway.class);

    /** 所有已连接的 MCP client，按 bean 名称索引 */
    private final Map<String, McpSyncClient> clientsByName = new ConcurrentHashMap<>();

    public McpToolGateway(List<McpSyncClient> clients) {
        for (McpSyncClient client : clients) {
            clientsByName.put(client.getServerInfo().name(), client);
            log.info("MCP client 已接入：server={} version={}",
                    client.getServerInfo().name(), client.getServerInfo().version());
        }
    }

    /** 是否已接入指定名称的 MCP Server */
    public boolean hasServer(String serverName) {
        return clientsByName.containsKey(serverName);
    }

    /** 已接入的全部 MCP Server 名称 */
    public List<String> serverNames() {
        return List.copyOf(clientsByName.keySet());
    }

    /**
     * 取某个 MCP Server 上指定名称的工具。
     * 若 server 未接入或工具不存在，返回 {@link Optional#empty()}。
     */
    public Optional<ToolCallback> findTool(String serverName, String toolName) {
        McpSyncClient client = clientsByName.get(serverName);
        if (client == null) {
            log.warn("MCP Server 未接入：{}，可用 server：{}", serverName, serverNames());
            return Optional.empty();
        }
        ToolCallback[] all = new SyncMcpToolCallbackProvider(client).getToolCallbacks();
        for (ToolCallback cb : all) {
            if (cb.getToolDefinition().name().equals(toolName)) {
                return Optional.of(cb);
            }
        }
        log.warn("MCP Server {} 上不存在工具 {}，可用工具：{}", serverName, toolName,
                java.util.Arrays.stream(all).map(c -> c.getToolDefinition().name()).collect(Collectors.toList()));
        return Optional.empty();
    }

    /** 取某个 MCP Server 上的全部工具 */
    public List<ToolCallback> allTools(String serverName) {
        McpSyncClient client = clientsByName.get(serverName);
        if (client == null) {
            log.warn("MCP Server 未接入：{}", serverName);
            return List.of();
        }
        return List.of(new SyncMcpToolCallbackProvider(client).getToolCallbacks());
    }

    /**
     * 跨所有已接入 server 按工具名查找（先到先得）。
     * 仅在工具名全局唯一的场景下使用。
     */
    public Optional<ToolCallback> findToolAnywhere(String toolName) {
        for (Map.Entry<String, McpSyncClient> e : clientsByName.entrySet()) {
            Optional<ToolCallback> found = findTool(e.getKey(), toolName);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }
}
