package com.hr.mcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * ADK MCP Server：把全部外部第三方 API 工具（天气/高德地图）以 MCP 协议暴露。
 *
 * <p>主应用（hr-agent, :20810）作为 MCP Client 经 streamable-http 连接本服务（:20811/mcp），
 * Agent 不再在进程内直连第三方 HTTP API。本服务必须先于主应用启动
 * （主应用的模块 Agent 有缓存，MCP 后启动会导致 common 模块缓存到 0 个工具）。
 *
 * <p>安全注意：/mcp 端点无鉴权，仅限本机回环使用，不得对外暴露（经它可使用高德 key）。
 */
@SpringBootApplication
public class AdkMcpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdkMcpServerApplication.class, args);
    }
}
