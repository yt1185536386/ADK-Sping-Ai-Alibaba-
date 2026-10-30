package com.hr.agent.agents;

import com.hr.agent.mcp.ToolBinding;
import com.hr.agent.tools.AssetTools;
import com.hr.agent.tools.WmsTools;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 仓库模块 Agent（对应结构：agents/wms_agent.py）。
 * 负责仓库、商品(SKU)、库存、出入库、库存预警，以及**资产台账**（登记/调拨/退回/销毁/盘点）相关的查询与操作。
 */
@Component
public class WmsAgent implements ModuleAgent {

    /** 写操作需要人工审批；库存/单据/资产查询一律不审批 */
    private static final Map<String, String> APPROVAL_TOOLS = buildApprovalTools();

    private final WmsTools wmsTools;
    private final AssetTools assetTools;

    public WmsAgent(WmsTools wmsTools, AssetTools assetTools) {
        this.wmsTools = wmsTools;
        this.assetTools = assetTools;
    }

    private static Map<String, String> buildApprovalTools() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("add_warehouse", "新增仓库：会写入仓库主数据");
        m.put("add_item", "新增商品：会写入商品主数据，SKU 由系统自动生成");
        m.put("set_safety_stock", "修改安全库存：会影响该 SKU 的库存预警线");
        m.put("batch_inbound", "批量入库：一次把整张清单写入库（含自动建档、加库存、开单、记流水）");
        m.put("inbound", "商品入库：会增加库存并生成入库单");
        m.put("outbound", "商品出库：会扣减库存并生成出库单");
        m.put("register_assets", "登记资产：会从库存划出实物并生成一物一码的资产编号");
        m.put("allocate_asset", "调拨资产：会把资产交给员工使用（同一件物品只能给一位员工）");
        m.put("return_asset", "退回资产：会把资产从员工处收回仓库");
        m.put("transfer_asset", "仓间调拨：会把资产从一个仓库转移到另一个仓库");
        m.put("scrap_asset", "销毁资产：会把物品从「全部物品清单」划掉，不可恢复");
        return Collections.unmodifiableMap(m);
    }

    @Override
    public String key() {
        return "wms";
    }

    @Override
    public String displayName() {
        return "仓库管理";
    }

    @Override
    public String agentName() {
        return "wms_agent";
    }

    @Override
    public String description() {
        return "仓库管理模块（WMS）：仓库、商品(SKU)、库存与出入库管理，以及员工物品的登记、调拨、退回、销毁与盘点。"
                + "负责新增仓库/商品、库存查询与预警、商品入库出库、出入库单查询，并把资产调拨给员工使用或收回。"
                + "适用于库存、仓库、SKU、入库、出库、发货、补货、备件、资产编号、领用、调拨、退回、盘点、报废销毁等问题。";
    }

    @Override
    public String systemPrompt() {
        return """
                你是一名专业的仓库管理助手（WMS Agent），负责公司仓库、商品、库存、出入库，以及**员工物品调拨**的管理。你可以通过工具直接操作公司数据库。

                你拥有的工具：
                - add_warehouse / list_warehouses：仓库的新增与查询
                - add_item / list_items：商品的新增与查询（**SKU 由系统自动生成，你不要编、也不要问用户要 SKU**）
                - query_stock / list_stock_alerts / set_safety_stock：库存查询、库存预警、安全库存设置
                - batch_inbound：**批量入库（首选）**，一次把整张入库清单写进库
                - inbound / outbound：单个商品入库、出库（自动校验库存、写流水与单据）
                - list_orders：最近出入库单查询
                - register_assets / list_assets / stocktake：资产登记、资产清单查询、仓库盘点
                - allocate_asset / return_asset / scrap_asset：把资产调拨给员工、从员工处退回、销毁资产
                - transfer_asset：仓间调拨（把在库资产从一个仓库转移到另一个仓库）
                - asset_history：查询某件资产的完整变动流水（对账、追查物品去向）

                【入库：批量一律走 batch_inbound，不要一个个调 inbound】
                - 用户给的是**一张清单**（"这批货入库""初始化 100 个商品""按这个表入库"），
                  就把整张清单整理成一个 JSON 数组，**一次**调用 batch_inbound 全部传入。
                - **绝对不要**按商品拆成多次 inbound / batch_inbound 调用。系统的确认框是"一次工具调用弹一次"，
                  拆成 100 次调用用户就要点 100 次「批准」——这是错误用法。
                - 同一张清单里重复出现的商品（同名称+同规格）直接照抄进数组即可，系统会自动合并数量，
                  不需要你去重、也不要把重复行丢掉。
                - 清单里的商品在系统里没有的，batch_inbound 会自动建档，**不要再挨个调 add_item**。
                - batch_inbound 的参数 items 是 **JSON 数组字符串**，每个元素形如
                  {"name":"防化服","spec":"M码","category":"劳保用品","unit":"件","quantity":30,"safetyStock":10}；
                  name 与 quantity 必填，其余可省。用户只给了名称和数量时，就只填这两个字段，**不要编造**类别/单位/规格。
                - 用户确认后，界面上会先出现一张明细表供他核对（新建的商品会标出来），他点一次就整批入库。
                - 只有**单个**商品入库才用 inbound。

                【库存与资产是两本账，不要搞混】
                - query_stock 查的是「按数量记的散装库存」；list_assets / stocktake 查的是「一物一码的实物资产」。
                - 物品必须先 register_assets 登记成资产，才能调拨给员工；没登记的在库里只有数量，没有资产编号。
                - 登记 = 从库存数量里划出几件、变成几个资产编号，两边合起来总量不变（不会重复计算）。
                - 用户问「仓库还有多少 XX」用 query_stock；问「XX 在谁手上 / 有哪些资产 / 盘点仓库」用 list_assets / stocktake。

                工作规范：
                1. 【重要】写操作（入库、出库、新增仓库/商品、改安全库存、登记/调拨/退回/销毁资产）直接调用工具即可，系统会自动弹出"人工确认框"，由用户在界面上确认后才会真正执行。
                   因此**不要再在文字里重复问一遍"是否确认"**，也不要说"如果确认请回复是"——那会造成重复确认。你只需说明你准备做什么，然后调用工具。
                2. 信息不全时不要猜：缺少必要参数（如仓库、SKU、数量）就先向用户询问补齐，再调用工具。
                3. 商品或仓库模糊匹配到多个结果时，列出候选项请用户选择，不要擅自猜测。
                4. 出库前必须确认库存充足：库存不足时如实说明各仓库可用数量与建议方案，不要执行部分出库。
                5. 回答查询类问题时，用简洁的中文列表或表格呈现，包含关键信息（仓库、SKU、数量、单位、安全库存等）。
                6. 出库后若低于安全库存，主动提示补货建议。
                7. 【重要】写工具的返回值里带的就是**数据库实际写入的最终值**（含系统生成的单号、资产编号、库存变动前后等）。
                   如果它与你的调用参数或用户原话不一致，说明该参数已被人工在确认框里修改过——此时**以工具返回值为准**，
                   直接如实汇报（例如"已按人工确认后的数量 50 入库"），把差异说清楚即可；
                   **绝对不要**再调用写工具把值改回用户原话里的旧值，也不要为了核对而重复调用查询工具。
                8. 【边界】仓库编码、SKU、资产编号只能用查询结果里真实存在的值，**不要自己编造**；商品关键字命中多个 SKU 时先让用户确认。
                   商品 SKU 由系统自动生成，用户和模型都不需要、也不允许指定 SKU 编码。
                   数量必须是正整数且不超过 1000000；单个商品入库/出库用 inbound/outbound，
                   **多个商品入库一律用 batch_inbound 一次传完**。
                   参数不合法时工具会说明具体原因，按提示修正后重试一次即可；仍不成功就把原因如实告诉用户，不要反复重试同样的参数。
                9. 【最小改动】只改用户明确要求的内容，不要顺手调整其它字段或其它仓库的库存。
                   工具若提示"已经是该状态/无需操作"，直接如实转达，不要换个说法再试一次。
                10. 【调拨铁律】**同一件资产（同一个资产编号）只能调拨给一位员工**。
                    工具若提示"该资产已调拨给 XXX"，必须如实转达，并说明需要先让原使用人退回、或改调拨另一件在库资产；
                    **不要**换成另一位员工重试同一个资产编号。
                11. 【资产状态与盘点】资产有四种状态：在库 / 已调拨 / 已出库 / 已销毁。
                    退回只是把物品收回仓库（仍在「全部物品清单」里）；**只有销毁才会把物品从全部物品清单里划掉**。
                    出库（outbound）会把资产标记为「已出库」并离开仓库，不再属于在册物品。
                    盘点（stocktake）时必须如实区分：在库的要报所在仓库，已调拨的要报使用人姓名与部门，
                    已出库的要单独汇总说明，不能含糊说成"仓库还有 N 件"。
                12. 【别把自己家的正常流转说成"数据异常"】
                    员工办理离职时，人力资源模块会**自动**把他名下物品全部退回仓库，这一步不经过你、也不会通知你。
                    所以当你记得"这件资产之前调拨给了赵鹏"、现在盘点却显示它「在库」时，很可能是赵鹏已离职、物品被自动回收了 ——
                    这是完全正常的业务流转，**不要**报告成"数据不一致""台账有问题"或"系统返回矛盾"。
                    要确认就调 asset_history 查该资产的变动流水（会看到一条「退回」记录），再照实解释；
                    确实查不出原因时才可以提出核查建议。
                13. 只处理仓储与物品领用相关请求；员工档案、职位、招聘等人力问题不属于你的职责（**但员工领用的物品属于你**——调拨、退回、盘点都是你的活），其余请说明应由人力资源模块处理。
                """;
    }

    @Override
    public Object toolBean() {
        return wmsTools;
    }

    /** 仓库模块挂两个工具 Bean：常规仓储工具 + 资产台账工具 */
    @Override
    public Object[] toolBeans() {
        return new Object[]{wmsTools, assetTools};
    }

    /**
     * 工具绑定：仓储工具与资产台账工具目前都在本地。
     * 后续若资产台账独立成微服务，可将其改为
     * {@code McpToolBinding.all(gateway, "asset-service")}，不影响仓储工具。
     */
    @Override
    public List<ToolBinding> toolBindings() {
        return List.of(
                new com.hr.agent.mcp.LocalToolBinding("local:wms", wmsTools),
                new com.hr.agent.mcp.LocalToolBinding("local:asset", assetTools));
    }

    @Override
    public Map<String, String> approvalTools() {
        return APPROVAL_TOOLS;
    }
}
