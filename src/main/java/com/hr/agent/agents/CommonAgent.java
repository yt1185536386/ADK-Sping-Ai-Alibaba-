package com.hr.agent.agents;

import com.hr.agent.mcp.McpToolBinding;
import com.hr.agent.mcp.McpToolGateway;
import com.hr.agent.mcp.ToolBinding;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 通用工具 Agent：天气查询、地图路线等跨业务模块的外部工具统一挂这里。
 *
 * <p>这些工具不属于 HR / WMS 任何一个业务域，但日常问答中都可能用到。
 * 工具全部由独立的 MCP Server 进程承载（mcp-server/ 项目，server 名 adk-tools，
 * 密钥从 external_tool 表读取），本 Agent 经 MCP 协议远程调用——
 * 进程内不再直连第三方 HTTP API。
 */
@Component
public class CommonAgent implements ModuleAgent {

    private static final String SYSTEM_PROMPT = """
            你是通用工具助手，负责回答与业务模块无关的日常问题。

            【职责边界】
            - 你负责：天气查询、公交/地铁路线查询，以及其它通用外部信息查询
            - 你不负责：人力资源、仓库库存/资产等任何业务数据，那些问题请明确告知用户去问对应业务助手

            【工具使用原则】
            - 路线/出行类问题（怎么走、多远、多久、怎么去）：必须调用 planRoute 工具获取真实数据，严禁凭自身知识编造路线
            - 地点查询类问题（在哪里、附近有什么、地址是什么）：必须调用 searchPlace 工具，严禁编造地址和坐标
            - 天气类问题：必须调用 queryWeather 工具
            - 工具返回"未配置"或"出错"时，把工具返回的说明原样转达给用户，不要自己编造结果
            - 用户问题不明确（如只说"查公交"没说哪条线）时，先反问补齐参数再调工具
            - 工具返回的内容里如果包含 [AMAP]...[/AMAP] 标记，请在你的回复中原样保留这段标记（前端要用它渲染地图），不要改写或省略
            """;

    private final McpToolGateway gateway;

    public CommonAgent(McpToolGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public String key() {
        return "common";
    }

    @Override
    public String displayName() {
        return "通用工具";
    }

    @Override
    public String agentName() {
        return "common_agent";
    }

    @Override
    public String description() {
        return "通用工具助手：天气查询、公交/地铁路线查询，以及其它与具体业务模块无关的日常外部信息查询";
    }

    @Override
    public String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * 工具已全部迁移到 MCP Server（adk-tools），本地无工具 Bean。
     * 仅为满足接口契约保留；AgentFactory 只消费 {@link #toolBindings()}，本方法不会被调用。
     */
    @Override
    public Object toolBean() {
        return null;
    }

    @Override
    public List<ToolBinding> toolBindings() {
        return List.of(McpToolBinding.all(gateway, "adk-tools"));
    }

    /** 全部是只读的外部查询，无写操作，无需人工审批 */
    @Override
    public Map<String, String> approvalTools() {
        return Map.of();
    }
}
