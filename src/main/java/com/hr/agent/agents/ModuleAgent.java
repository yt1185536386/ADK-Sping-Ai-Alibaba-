package com.hr.agent.agents;

import com.hr.agent.mcp.ToolBinding;

import java.util.List;
import java.util.Map;

/**
 * 业务模块 Agent 的统一契约（对应结构：agents/hr_agent.py、wms_agent.py）。
 * 每个模块声明自己的身份、路由描述、系统提示词与工具集，由 AgentFactory 负责与具体模型组合成 Agent 实例。
 */
public interface ModuleAgent {

    /** 模块标识：hr / wms */
    String key();

    /** 模块中文名：人力资源 / 仓库管理 */
    String displayName();

    /** Agent 名称（路由时作为节点名） */
    String agentName();

    /** 供统一调度入口做路由判断的职责描述 */
    String description();

    /** 系统提示词 */
    String systemPrompt();

    /** 提供 @Tool 方法的工具 Bean（如 HrTools / WmsTools） */
    Object toolBean();

    /**
     * 该模块挂载的全部工具 Bean。
     * <p>默认只有一个（{@link #toolBean()}）；模块工具变多时覆写本方法即可，
     * 例如仓库模块除 {@code WmsTools} 外还挂了 {@code AssetTools}（资产台账：登记/调拨/退回/销毁/盘点）。
     *
     * @deprecated 请改用 {@link #toolBindings()} 声明工具来源（本地 or MCP），本方法仅用于兼容旧实现。
     */
    @Deprecated
    default Object[] toolBeans() {
        return new Object[]{toolBean()};
    }

    /**
     * 该模块的工具绑定（本地 @Tool Bean / MCP 远端工具）。
     * <p>默认实现把 {@link #toolBeans()} 包装成 {@code LocalToolBinding}，
     * 因此未覆写本方法的模块行为与改造前完全一致。
     * 模块若要接入 MCP 工具，覆写本方法并返回混合的绑定列表即可。
     */
    default List<ToolBinding> toolBindings() {
        return List.of(new com.hr.agent.mcp.LocalToolBinding("local:" + key(), toolBeans()));
    }

    /**
     * 需要人工审批（HITL）的工具：工具名 -&gt; 审批说明。
     * <p>只放"会改动数据的写操作"，只读查询一律不要放进来，否则会频繁打断正常问答。
     */
    Map<String, String> approvalTools();
}
