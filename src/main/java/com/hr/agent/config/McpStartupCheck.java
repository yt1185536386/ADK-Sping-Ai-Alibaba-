package com.hr.agent.config;

import com.hr.agent.mcp.McpToolGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * MCP 接入启动自检：adk-tools（外部工具 MCP Server，mcp-server/ 项目，端口 20811）
 * 未接入时打 ERROR——此后 common 模块的天气/地图/公交/路线工具将全部不可用，
 * 配送路线规划也会跳过。
 *
 * <p>只告警不阻断启动（与项目"缺配置不挡启动"的哲学一致），但错误必须无法被忽略。
 * 恢复方式：启动 mcp-server 后重启本应用（模块 Agent 有缓存，不重启不会重连）。
 */
@Component
public class McpStartupCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(McpStartupCheck.class);

    private final McpToolGateway gateway;

    public McpStartupCheck(McpToolGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!gateway.hasServer("adk-tools")) {
            log.error("MCP Server adk-tools 未接入！通用工具（天气/地图/公交/路线）将全部不可用，"
                    + "配送路线规划也会跳过。请先启动 mcp-server（端口 20811），再重启本应用。已接入 server：{}",
                    gateway.serverNames());
        } else {
            log.info("MCP Server adk-tools 已接入，外部工具走 MCP 协议调用");
        }
    }
}
