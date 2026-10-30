package com.hr.agent.mcp;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 工具绑定：统一「本地 @Tool」与「MCP 远端工具」的装配抽象。
 *
 * <p>模块 Agent 通过 {@link com.hr.agent.agents.ModuleAgent#toolBindings()} 声明
 * 自己的工具来自哪些绑定；AgentFactory 装配时遍历绑定、取出 {@link ToolCallback} 即可，
 * 不关心工具最终是跑在 JVM 进程内，还是跑在某个 MCP Server 后面。
 *
 * <p>当前提供两种实现：
 * <ul>
 *   <li>{@link LocalToolBinding} —— 进程内本地工具 Bean（现状，走 {@code @Tool} 注解扫描）</li>
 *   <li>{@link McpToolBinding} —— 通过 MCP 协议接入的远端工具（走 MCP Client）</li>
 * </ul>
 */
public interface ToolBinding {

    /** 绑定标识（用于日志与排障，如 "local:hr"、"mcp:asset-service"） */
    String name();

    /** 该绑定提供的全部工具回调 */
    List<ToolCallback> toolCallbacks();
}
