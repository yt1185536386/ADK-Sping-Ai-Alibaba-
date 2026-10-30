package com.hr.mcpserver.config;

import com.hr.mcpserver.tools.ExternalApiTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 {@link ExternalApiTools} 上的 @Tool 方法注册为 MCP 工具，
 * 由 spring-ai-starter-mcp-server-webmvc 暴露到 /mcp 端点。
 *
 * <p>工具名 = Java 方法名（queryWeather / queryBusRoute / getCurrentLocation /
 * searchPlace / planRoute / driveRouteJson），主应用 CommonAgent 的 system prompt
 * 逐字引用前 5 个名字，不得改名。
 */
@Configuration
public class ToolProviderConfig {

    @Bean
    public ToolCallbackProvider externalApiToolCallbacks(ExternalApiTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
