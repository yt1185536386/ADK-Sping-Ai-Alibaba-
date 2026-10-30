package com.hr.agent.mcp;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.Optional;

/**
 * MCP 工具绑定：把某个 MCP Server 上的工具（全部或按名称筛选）接入模块。
 *
 * <p>由 {@link McpToolGateway} 负责实际的 client 管理与工具查找；
 * 本类只持有「要用哪个 server 的哪些工具」这一声明。
 */
public class McpToolBinding implements ToolBinding {

    private final McpToolGateway gateway;
    private final String serverName;
    /** 为空表示接入该 server 上的全部工具 */
    private final List<String> toolNames;

    private McpToolBinding(McpToolGateway gateway, String serverName, List<String> toolNames) {
        this.gateway = gateway;
        this.serverName = serverName;
        this.toolNames = toolNames;
    }

    /** 接入该 server 上的全部工具 */
    public static McpToolBinding all(McpToolGateway gateway, String serverName) {
        return new McpToolBinding(gateway, serverName, null);
    }

    /** 按名称接入该 server 上的指定工具 */
    public static McpToolBinding of(McpToolGateway gateway, String serverName, String... toolNames) {
        return new McpToolBinding(gateway, serverName, List.of(toolNames));
    }

    @Override
    public String name() {
        return "mcp:" + serverName;
    }

    @Override
    public List<ToolCallback> toolCallbacks() {
        if (toolNames == null || toolNames.isEmpty()) {
            return gateway.allTools(serverName);
        }
        return toolNames.stream()
                .map(tn -> gateway.findTool(serverName, tn))
                .flatMap(Optional::stream)
                .toList();
    }
}
