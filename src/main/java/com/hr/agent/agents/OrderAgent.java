package com.hr.agent.agents;

import com.hr.agent.mcp.ToolBinding;
import com.hr.agent.tools.OrderTools;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 订单模块 Agent：销售订单的创建、查询，以及订单衍生的配送、采购入库、退货入库。
 */
@Component
public class OrderAgent implements ModuleAgent {

    /** 写操作需要人工审批；只读查询一律不审批 */
    private static final Map<String, String> APPROVAL_TOOLS = buildApprovalTools();

    private final OrderTools orderTools;

    public OrderAgent(OrderTools orderTools) {
        this.orderTools = orderTools;
    }

    private static Map<String, String> buildApprovalTools() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("create_order", "创建销售订单：会向订单库写入一条新订单");
        m.put("create_delivery", "创建配送单：会从仓库拣货出库（扣库存）并指定员工配送");
        m.put("create_purchase", "采购入库：会增加库存并生成采购单");
        m.put("create_return", "退货入库：会增加库存并把订单标记为已退货");
        return Collections.unmodifiableMap(m);
    }

    @Override
    public String key() {
        return "order";
    }

    @Override
    public String displayName() {
        return "订单管理";
    }

    @Override
    public String agentName() {
        return "order_agent";
    }

    @Override
    public String description() {
        return "订单管理模块：销售订单全生命周期。负责创建订单、查询订单（含配送状态）、"
                + "指定员工配送货物（拣货出库）、指定员工采购商品入库（补货）、订单退货重新入库。"
                + "适用于下单、订单、客户购买、发货、配送、采购补货、退货等问题。";
    }

    @Override
    public String systemPrompt() {
        return """
                你是一名专业的订单管理助手（Order Agent），负责公司销售订单的全生命周期管理。你可以通过工具直接操作公司数据库。

                你拥有的工具：
                - create_order：创建销售订单（客户下单购买商品，只建订单不动库存）
                - list_orders：查询订单列表（含配送状态、配送员，可按状态/客户过滤）
                - create_delivery：给订单指定员工配送货物（自动从仓库拣货出库，订单变已发货）
                - list_deliveries：查询配送单列表
                - create_purchase：指定员工采购商品并入库（可关联到某个订单为其补货，也可独立采购）
                - create_return：订单退货重新入库（客户退回已发货的商品，入库并把订单标记为已退货）

                业务流转：
                1. 客户下单 → create_order（状态：待处理）
                2. 安排发货 → create_delivery（拣货出库，状态：已发货；库存不足时会提示先采购）
                3. 缺货补货 → create_purchase（采购入库加库存，可关联订单号）
                4. 客户退货 → create_return（商品重新入库，全退时订单变已退货）

                工作规范：
                1. 【重要】写操作（下单、配送、采购入库、退货入库）直接调用工具即可，系统会自动弹出"人工确认框"，由用户在界面上确认后才会真正执行。
                   因此**不要再在文字里重复问一遍"是否确认"**。你只需说明你准备做什么，然后调用工具。
                2. 信息不全时不要猜：缺少必要参数（如客户、商品、数量、配送员工）就先向用户询问补齐，再调用工具。
                3. 商品、员工模糊匹配到多个结果时，列出候选项请用户选择，不要擅自猜测；员工必须是在职状态才能指派。
                4. 配送时库存不足：如实说明各仓库可用数量，并建议先用 create_purchase 采购补货，不要执行部分出库。
                5. 只有「已发货」的订单才能退货；全退后订单状态变「已退货」，部分退货订单保持「已发货」。
                6. 回答查询类问题时，用简洁的中文列表或表格呈现（订单号、客户、商品、数量、状态、配送信息）。
                7. 【重要】写工具的返回值里带的就是**数据库实际写入的最终值**（含系统生成的订单号/配送单号/采购单号/退货单号、库存变动前后等）。
                   如果它与你的调用参数或用户原话不一致，说明该参数已被人工在确认框里修改过——此时**以工具返回值为准**，
                   直接如实汇报，把差异说清楚即可；**绝对不要**再调用写工具把值改回去。
                8. 【边界】订单号、单号一律以工具返回值或查询结果为准，**不要自己编造**；数量必须是正整数。
                   参数不合法时工具会说明具体原因，按提示修正后重试一次即可；仍不成功就把原因如实告诉用户。
                9. 只处理订单相关请求；员工档案、职位问题归人力资源模块；纯库存查询、商品建档、资产调拨归仓库管理模块。
                   （**例外**：为订单补货的采购入库、订单退货入库属于你的职责，直接用 create_purchase / create_return。）
                """;
    }

    @Override
    public Object toolBean() {
        return orderTools;
    }

    @Override
    public List<ToolBinding> toolBindings() {
        return List.of(new com.hr.agent.mcp.LocalToolBinding("local:order", orderTools));
    }

    @Override
    public Map<String, String> approvalTools() {
        return APPROVAL_TOOLS;
    }
}
