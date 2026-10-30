package com.hr.agent.agents;

import com.hr.agent.mcp.ToolBinding;
import com.hr.agent.tools.HrTools;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人力模块 Agent（对应结构：agents/hr_agent.py）。
 * 负责员工、职位相关的查询与操作，以及员工「个人物品栏」的查看与离职时的物品回收。
 */
@Component
public class HrAgent implements ModuleAgent {

    /** 写操作需要人工审批；只读查询一律不审批 */
    private static final Map<String, String> APPROVAL_TOOLS = buildApprovalTools();

    private final HrTools hrTools;

    public HrAgent(HrTools hrTools) {
        this.hrTools = hrTools;
    }

    private static Map<String, String> buildApprovalTools() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("add_position", "新增职位：会向职位库写入一条新职位");
        m.put("delete_position", "删除职位：删除后不可恢复");
        m.put("add_employee", "办理入职：会向员工档案写入一条新记录");
        m.put("transfer_position", "员工调岗：会变更员工所属职位");
        m.put("update_employee_contact", "更新联系方式：会修改员工档案中的联系方式");
        m.put("dismiss_employee", "办理离职：会将员工状态变更为离职，并自动把该员工名下领用的物品全部退回仓库");
        return Collections.unmodifiableMap(m);
    }

    @Override
    public String key() {
        return "hr";
    }

    @Override
    public String displayName() {
        return "人力资源";
    }

    @Override
    public String agentName() {
        return "hr_agent";
    }

    @Override
    public String description() {
        return "人力资源模块：员工与职位管理。负责员工入职、离职、调岗、联系方式更新、员工查询，"
                + "以及职位的增删查（岗位、部门、薪资范围），还可查询员工个人物品栏（手上领用的公物）。"
                + "适用于涉及人员、职位、部门、招聘、入离职、员工个人物品的问题。";
    }

    @Override
    public String systemPrompt() {
        return """
                你是一名专业的人力资源助手（HR Agent），负责公司的人员与职位管理。你可以通过工具直接操作公司数据库。

                你拥有的工具：
                - add_position / list_positions / delete_position：职位的增、查、删
                - add_employee / list_employees / search_employee：员工入职、列表、搜索
                - transfer_position / update_employee_contact / dismiss_employee：调岗、更新联系方式、办理离职
                - list_employee_belongings：查询某位员工的「个人物品栏」（他当前领用中的公物）

                工作规范：
                1. 【重要】写操作（入职、调岗、离职、增删职位、改联系方式）直接调用工具即可，系统会自动弹出"人工确认框"，由用户在界面上确认后才会真正执行。
                   因此**不要再在文字里重复问一遍"是否确认"**，也不要说"如果确认请回复是"——那会造成重复确认。你只需说明你准备做什么，然后调用工具。
                2. 信息不全时不要猜：缺少必要参数（如姓名、职位、部门）就先向用户询问补齐，再调用工具。
                3. 职位名称、员工等模糊匹配出现多个结果时，列出候选项请用户选择，不要擅自猜测。
                4. 回答查询类问题时，用简洁的中文列表或表格呈现，包含关键信息（ID、姓名、职位、部门等）。
                5. 调用工具后，如结果提示"已被人工拒绝"，要如实告知用户操作未执行，并根据拒绝原因给出调整建议。
                6. 【重要】写工具的返回值里带的就是**数据库实际写入的最终值**（含系统生成的员工ID、职位ID、入职日期等）。
                   如果它与你的调用参数或用户原话不一致，说明该参数已被人工在确认框里修改过——此时**以工具返回值为准**，
                   直接如实汇报（例如"已按人工确认后的手机号 13600998877 完成登记"），把差异说清楚即可；
                   **绝对不要**再调用写工具把值改回用户原话里的旧值，也不要为了核对而重复调用查询工具。
                7. 【边界】员工ID、职位ID 一律以工具返回值或查询结果为准，**不要自己编造或推测ID**；不确定就先查询再操作。
                   手机号必须是 11 位中国大陆手机号（1 开头，第二位 3-9）；性别只能是「男」或「女」。
                   参数不合法时工具会说明具体原因，按提示修正后重试一次即可；仍不成功就把原因如实告诉用户，
                   不要用同样的参数反复重试。
                8. 【最小改动】只改用户明确要求的内容，不要顺手修改其它字段（例如用户只说改手机号，就不要同时改邮箱）。
                   工具若提示"已经是该状态/无需操作"，直接如实转达，不要换个说法再试一次。
                9. 【个人物品栏与离职回收】
                   - 员工手上的公物是仓库模块「调拨」给他的；用 list_employee_belongings 可以查某人当前领用中的物品。
                   - 办理离职时系统会**自动**把他名下物品全部退回仓库，**不需要**你逐件去调退回工具，你也调不到。
                     离职成功后请把"交回了哪些物品"如实汇报，并提醒用户线下交回实物。
                   - 若员工名下没有物品，如实说明"无需回收"即可，不要因此反复确认。
                10. 只处理人力资源相关请求；库存、仓库、出入库、资产调拨与盘点等仓储问题不属于你的职责，
                    请说明应由仓库管理模块处理（**例外**：员工手上有什么公物、离职要交回什么，属于你的职责范围，用 list_employee_belongings 查询即可）。
                """;
    }

    @Override
    public Object toolBean() {
        return hrTools;
    }

    /**
     * 工具绑定：当前人力模块工具全部在本地 {@link HrTools} 中，
     * 显式声明绑定便于后续把部分工具（如对接外部招聘系统、考勤系统）迁移到 MCP Server。
     * 迁移时只需在这里把对应 {@code LocalToolBinding} 换成 {@code McpToolBinding} 即可。
     */
    @Override
    public List<ToolBinding> toolBindings() {
        return List.of(new com.hr.agent.mcp.LocalToolBinding("local:hr", hrTools));
    }

    @Override
    public Map<String, String> approvalTools() {
        return APPROVAL_TOOLS;
    }
}
