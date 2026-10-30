package com.hr.agent.tools;

import com.hr.agent.service.AssetService;

import com.hr.agent.util.Args;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 资产相关工具集（对应仓库模块，配合 {@link AssetService} 使用）。
 *
 * <p>本类只做三件事：校验入参 → 调 {@link AssetService} → 把结果整理成模型看得懂的中文。
 * 真正的业务规则与事务都在 AssetService 里（HR 模块的离职回收也复用同一份，规则不会走样）。
 *
 * <p><b>几个概念先说清楚（模型也靠这些措辞来判断该调哪个工具）：</b>
 * <ul>
 *   <li><b>登记</b>：把仓库里按数量记的散装库存，划出 N 件变成一物一码的资产。只有登记过的物品才能调拨。</li>
 *   <li><b>全部物品清单</b>：所有登记过、且<em>未被销毁</em>的资产（含在库的和被员工领用的）。</li>
 *   <li><b>当前在库清单</b>：其中状态为「在库」的那部分。</li>
 *   <li><b>销毁</b>：唯一能把物品从「全部物品清单」里划掉的操作。</li>
 * </ul>
 */
@Component
public class AssetTools {

    /** 单次调拨/退回/销毁的资产件数上限，防止模型一口气塞进来几百个编号 */
    private static final int MAX_BATCH = 100;

    private final AssetService assets;
    /** 只读查询用（商品/员工/仓库这类小表）；所有写操作都在 AssetService 里 */
    private final JdbcTemplate jdbc;

    public AssetTools(AssetService assets, JdbcTemplate jdbc) {
        this.assets = assets;
        this.jdbc = jdbc;
    }

    // ==================== 登记 ====================

    @Tool(name = "register_assets",
            description = "登记资产：把仓库中的 N 件商品划出来，生成一物一码的资产编号。"
                    + "只有登记过的物品才能调拨给员工。需要商品(SKU 或名称)、仓库编码、件数。"
                    + "登记会从库存数量里划出对应件数（库存账 + 资产账合计守恒，不会重复计算）")
    public String registerAssets(
            @ToolParam(description = "SKU 或商品名称关键字") String sku,
            @ToolParam(description = "仓库编码，如 WH01") String warehouseCode,
            @ToolParam(description = "登记件数") Integer count,
            @ToolParam(description = "经办人，可为空") String operator) {
        String bad = Args.required(sku, "商品(SKU 或名称)");
        if (bad == null) {
            bad = Args.code(warehouseCode, "仓库编码");
        }
        if (bad == null) {
            bad = Args.quantity(count, "登记件数");
        }
        if (bad == null) {
            bad = Args.text(operator, "经办人", 50, true);
        }
        if (bad != null) {
            return "无法登记资产：" + bad;
        }

        ItemLookup lookup = resolveItem(sku);
        if (lookup.error() != null) {
            return lookup.error();
        }
        Map<String, Object> item = lookup.item();
        Map<String, Object> wh = findWarehouse(warehouseCode);
        if (wh == null) {
            return "未找到仓库「" + warehouseCode + "」，请先用 list_warehouses 核对仓库编码。";
        }
        return assets.register(
                ((Number) item.get("id")).longValue(),
                ((Number) wh.get("id")).longValue(),
                count,
                Args.clean(operator));
    }

    // ==================== 调拨 / 退回 / 销毁 ====================

    @Tool(name = "allocate_asset",
            description = "把资产调拨给员工使用：资产从「在库」变为「已调拨」并记录使用人。"
                    + "同一件资产只能调拨给一个人；已被别人领用或已销毁的会被拒绝并说明原因。"
                    + "多个资产编号用逗号分隔（如 AST-20260930-0001,AST-20260930-0002）")
    public String allocateAsset(
            @ToolParam(description = "资产编号，多个用逗号分隔") String assetNos,
            @ToolParam(description = "员工：员工ID 或 姓名") String employee,
            @ToolParam(description = "经办人，可为空") String operator) {
        String bad = Args.required(assetNos, "资产编号");
        if (bad == null) {
            bad = Args.required(employee, "员工（ID 或姓名）");
        }
        if (bad == null) {
            bad = Args.text(operator, "经办人", 50, true);
        }
        if (bad != null) {
            return "无法调拨：" + bad;
        }

        List<String> nos = splitNos(assetNos);
        if (nos.isEmpty()) {
            return "没有解析出有效的资产编号，请提供形如 AST-20260930-0001 的编号。";
        }
        if (nos.size() > MAX_BATCH) {
            return "一次最多调拨 " + MAX_BATCH + " 件（收到 " + nos.size() + " 件），请分批办理。";
        }

        EmployeeLookup emp = resolveEmployee(employee);
        if (emp.error() != null) {
            return "无法调拨：" + emp.error();
        }
        Map<String, Object> e = emp.employee();
        return assets.allocate(nos, ((Number) e.get("id")).longValue(), Args.clean(operator));
    }

    @Tool(name = "return_asset",
            description = "把员工手中的资产退回仓库：状态由「已调拨」回到「在库」，使用人清空。"
                    + "多个资产编号用逗号分隔；不在使用人手中的资产会被跳过并说明原因")
    public String returnAsset(
            @ToolParam(description = "资产编号，多个用逗号分隔") String assetNos,
            @ToolParam(description = "经办人，可为空") String operator) {
        String bad = Args.required(assetNos, "资产编号");
        if (bad == null) {
            bad = Args.text(operator, "经办人", 50, true);
        }
        if (bad != null) {
            return "无法退回：" + bad;
        }
        List<String> nos = splitNos(assetNos);
        if (nos.isEmpty()) {
            return "没有解析出有效的资产编号，请提供形如 AST-20260930-0001 的编号。";
        }
        if (nos.size() > MAX_BATCH) {
            return "一次最多退回 " + MAX_BATCH + " 件（收到 " + nos.size() + " 件），请分批办理。";
        }
        return assets.returnAssets(nos, Args.clean(operator));
    }

    @Tool(name = "scrap_asset",
            description = "销毁资产：这是唯一能把物品从「全部物品清单」里划掉的操作，需要填写销毁原因。"
                    + "销毁后资产不再出现在全部清单与在库清单中（变动流水仍保留可追溯）。"
                    + "多个资产编号用逗号分隔")
    public String scrapAsset(
            @ToolParam(description = "资产编号，多个用逗号分隔") String assetNos,
            @ToolParam(description = "销毁原因，如 损坏报废/丢失/过期") String reason,
            @ToolParam(description = "经办人，可为空") String operator) {
        String bad = Args.required(assetNos, "资产编号");
        if (bad == null) {
            bad = Args.text(reason, "销毁原因", 200, true);
        }
        if (bad == null) {
            bad = Args.text(operator, "经办人", 50, true);
        }
        if (bad != null) {
            return "无法销毁：" + bad;
        }
        List<String> nos = splitNos(assetNos);
        if (nos.isEmpty()) {
            return "没有解析出有效的资产编号，请提供形如 AST-20260930-0001 的编号。";
        }
        if (nos.size() > MAX_BATCH) {
            return "一次最多销毁 " + MAX_BATCH + " 件（收到 " + nos.size() + " 件），请分批办理。";
        }
        return assets.scrap(nos, Args.clean(reason), Args.clean(operator));
    }

    // ==================== 仓间调拨 ====================

    @Tool(name = "transfer_asset",
            description = "仓间调拨：把资产从一个仓库转移到另一个仓库。"
                    + "仅允许「在库」状态的资产调拨；已调拨给员工的资产需先退回才能调拨。"
                    + "多个资产编号用逗号分隔（如 AST-20260930-0001,AST-20260930-0002）")
    public String transferAsset(
            @ToolParam(description = "资产编号，多个用逗号分隔") String assetNos,
            @ToolParam(description = "源仓库编码，如 WH01") String fromWarehouseCode,
            @ToolParam(description = "目标仓库编码，如 WH02") String toWarehouseCode,
            @ToolParam(description = "经办人，可为空") String operator) {
        String bad = Args.required(assetNos, "资产编号");
        if (bad == null) {
            bad = Args.code(fromWarehouseCode, "源仓库编码");
        }
        if (bad == null) {
            bad = Args.code(toWarehouseCode, "目标仓库编码");
        }
        if (bad == null) {
            bad = Args.text(operator, "经办人", 50, true);
        }
        if (bad != null) {
            return "无法调拨：" + bad;
        }

        List<String> nos = splitNos(assetNos);
        if (nos.isEmpty()) {
            return "没有解析出有效的资产编号，请提供形如 AST-20260930-0001 的编号。";
        }
        if (nos.size() > MAX_BATCH) {
            return "一次最多调拨 " + MAX_BATCH + " 件（收到 " + nos.size() + " 件），请分批办理。";
        }

        Map<String, Object> fromWh = findWarehouse(fromWarehouseCode);
        if (fromWh == null) {
            return "未找到源仓库「" + fromWarehouseCode + "」，请先用 list_warehouses 核对仓库编码。";
        }
        Map<String, Object> toWh = findWarehouse(toWarehouseCode);
        if (toWh == null) {
            return "未找到目标仓库「" + toWarehouseCode + "」，请先用 list_warehouses 核对仓库编码。";
        }

        return assets.transfer(nos,
                ((Number) fromWh.get("id")).longValue(),
                ((Number) toWh.get("id")).longValue(),
                Args.clean(operator));
    }

    // ==================== 查询 ====================

    @Tool(name = "list_assets",
            description = "查询资产清单。scope 可选：全部（默认，含在库与已调拨、不含已销毁）/ 在库 / 已调拨 / 已销毁。"
                    + "可按商品名称、SKU、资产编号关键字与仓库编码过滤")
    public String listAssets(
            @ToolParam(description = "范围：全部/在库/已调拨/已销毁，可为空（默认全部）") String scope,
            @ToolParam(description = "关键字：商品名称/SKU/资产编号，可为空") String keyword,
            @ToolParam(description = "仓库编码，可为空") String warehouseCode) {
        String bad = Args.text(scope, "范围", 20, true);
        if (bad == null) {
            bad = Args.text(keyword, "关键字", 50, true);
        }
        if (bad == null) {
            bad = Args.text(warehouseCode, "仓库编码", 50, true);
        }
        if (bad != null) {
            return bad;
        }

        List<Map<String, Object>> rows = assets.queryAssets(scope, keyword, warehouseCode, null);
        String scopeLabel = (scope == null || scope.isBlank()) ? "全部" : scope.trim();
        if (rows.isEmpty()) {
            return "没有符合条件的资产记录。"
                    + ("全部".equals(scopeLabel)
                       ? "（资产需要先通过 register_assets 从库存登记出来，登记后才会出现在清单里）"
                       : "（当前没有状态为「" + scopeLabel + "」的资产）");
        }
        Map<String, Integer> stat = assets.countByStatus();
        StringBuilder sb = new StringBuilder("资产清单（范围：" + scopeLabel + "，本次显示 " + rows.size() + " 条）：\n");
        for (Map<String, Object> r : rows) {
            sb.append("- ").append(formatAssetLine(r)).append("\n");
        }
        sb.append("\n总览：");
        if (stat.isEmpty()) {
            sb.append("暂无资产");
        } else {
            stat.forEach((k, v) -> sb.append(k).append(" ").append(v).append(" 件  "));
        }
        sb.append("\n（「全部物品清单」= 在库 + 已调拨；只有销毁才会从该清单划掉）");
        if (rows.size() >= Args.MAX_LIST) {
            sb.append("\n（已达单次返回上限 ").append(Args.MAX_LIST).append(" 条，请用关键字或仓库缩小范围）");
        }
        return sb.toString();
    }

    @Tool(name = "stocktake",
            description = "仓库盘点：统一盘点口径，同时汇总「一物一码资产」与「散装库存」两本账。"
                    + "资产账逐件列出（在库的显示所在仓库，已调拨的显示使用人，已出库的单独汇总）；"
                    + "散装库存按商品汇总数量（尚未登记为资产的库存）。"
                    + "并汇总每件物品分别在谁手上，用于核对物品有没有丢失。可传仓库编码只看某个仓库")
    public String stocktake(@ToolParam(description = "仓库编码，可为空表示全部仓库") String warehouseCode) {
        String bad = Args.text(warehouseCode, "仓库编码", 50, true);
        if (bad != null) {
            return bad;
        }
        String whLabel = (warehouseCode == null || warehouseCode.isBlank()) ? "全部仓库" : "仓库 " + warehouseCode.trim();

        // ===== 第一本账：一物一码资产 =====
        List<Map<String, Object>> assetRows = assets.queryAssets(null, null, warehouseCode, null);

        StringBuilder inStock = new StringBuilder();
        StringBuilder allocated = new StringBuilder();
        StringBuilder outbound = new StringBuilder();
        int inStockCount = 0;
        int allocatedCount = 0;
        int outboundCount = 0;
        for (Map<String, Object> r : assetRows) {
            String status = String.valueOf(r.get("status"));
            if (AssetService.ALLOCATED.equals(status)) {
                allocatedCount++;
                allocated.append("- ").append(r.get("asset_no")).append(" ")
                         .append(r.get("item_name")).append("[").append(r.get("sku")).append("] → 使用人 ")
                         .append(r.get("holder_name"))
                         .append(holderSuffix(r))
                         .append(r.get("allocated_at") == null ? "" : "（" + shortTime(r.get("allocated_at")) + " 调拨）")
                         .append("\n");
            } else if ("已出库".equals(status)) {
                outboundCount++;
                outbound.append("- ").append(r.get("asset_no")).append(" ")
                        .append(r.get("item_name")).append("[").append(r.get("sku")).append("] ")
                        .append("已出库（原属 ").append(r.get("wh_name")).append("(").append(r.get("wh_code")).append(")）")
                        .append("\n");
            } else {
                inStockCount++;
                inStock.append("- ").append(r.get("asset_no")).append(" ")
                       .append(r.get("item_name")).append("[").append(r.get("sku")).append("] ")
                       .append("在库 @").append(r.get("wh_name")).append("(").append(r.get("wh_code")).append(")")
                       .append("\n");
            }
        }

        // ===== 第二本账：散装库存（wms_stock 中尚有数量、但未纳入资产账的部分）=====
        StringBuilder stockSql = new StringBuilder("""
                SELECT i.sku, i.name AS item_name, i.unit, s.quantity,
                       w.name AS wh_name, w.code AS wh_code
                FROM wms_stock s
                JOIN wms_item i ON i.id = s.item_id
                JOIN wms_warehouse w ON w.id = s.warehouse_id
                WHERE s.quantity > 0
                """);
        List<Object> stockArgs = new ArrayList<>();
        if (warehouseCode != null && !warehouseCode.isBlank()) {
            stockSql.append(" AND w.code = ?");
            stockArgs.add(warehouseCode.trim());
        }
        stockSql.append(" ORDER BY w.code, i.sku LIMIT ?");
        stockArgs.add(Args.MAX_LIST);
        List<Map<String, Object>> stockRows = jdbc.queryForList(stockSql.toString(), stockArgs.toArray());
        int stockTotalQty = stockRows.stream()
                .mapToInt(r -> ((Number) r.get("quantity")).intValue()).sum();

        // ===== 拼装输出 =====
        StringBuilder sb = new StringBuilder("仓库盘点（范围：" + whLabel + "）\n");

        sb.append("\n═══ 一物一码资产账（共 ").append(assetRows.size()).append(" 件）═══\n");
        if (assetRows.isEmpty()) {
            sb.append("（无资产记录）\n");
        } else {
            sb.append("\n【在库】").append(inStockCount).append(" 件\n");
            sb.append(inStockCount == 0 ? "- 无\n" : inStock.toString());
            sb.append("\n【已调拨（在员工手上）】").append(allocatedCount).append(" 件\n");
            sb.append(allocatedCount == 0 ? "- 无\n" : allocated.toString());
            if (outboundCount > 0) {
                sb.append("\n【已出库】").append(outboundCount).append(" 件\n");
                sb.append(outbound.toString());
            }
        }

        sb.append("\n═══ 散装库存账（wms_stock，共 ").append(stockTotalQty).append(" 件）═══\n");
        if (stockRows.isEmpty()) {
            sb.append("- 无散装库存\n");
        } else {
            for (Map<String, Object> r : stockRows) {
                sb.append("- ").append(r.get("item_name")).append("[").append(r.get("sku")).append("] ")
                  .append(r.get("quantity")).append(" ").append(r.get("unit"))
                  .append(" @").append(r.get("wh_name")).append("(").append(r.get("wh_code")).append(")")
                  .append("\n");
            }
            sb.append("（散装库存是尚未登记为一物一码资产的库存；入库后会同步生成资产记录，"
                    + "此处数量与「在库」资产中未登记的部分对应）\n");
        }

        List<Map<String, Object>> holders = assets.holdersSummary();
        if (!holders.isEmpty()) {
            sb.append("\n【物品在谁手上】\n");
            for (Map<String, Object> h : holders) {
                sb.append("- ").append(h.get("employee_name"))
                  .append(h.get("department") == null ? "" : "（" + h.get("department") + "）")
                  .append("：").append(h.get("asset_count")).append(" 件")
                  .append("在职".equals(String.valueOf(h.get("employee_status"))) ? "" : "  ⚠ 该员工已离职，物品应已退回，请核对")
                  .append("\n");
            }
        } else {
            sb.append("\n【物品在谁手上】\n- 目前没有资产被员工领用，全部在库\n");
        }

        sb.append("\n核对要点：公司所有物品 = 一物一码资产（在库+已调拨）+ 散装库存，"
          ).append("已出库的 ").append(outboundCount).append(" 件已离开仓库不计入在册物品，已销毁的不计入。")
          .append("实物清点时若发现有物品既不在库、也不在任何使用人手上、也不在散装库存里，"
          ).append("请核查 wms_asset_log 与 wms_stock_log 的变动流水。");
        if (assetRows.size() >= Args.MAX_LIST || stockRows.size() >= Args.MAX_LIST) {
            sb.append("\n（已达单次返回上限 ").append(Args.MAX_LIST)
              .append(" 条，请指定仓库编码缩小盘点范围）");
        }
        return sb.toString();
    }

    @Tool(name = "asset_history",
            description = "查询资产变动流水：某件资产从登记到现在的全部动作（登记/调拨/退回/销毁），"
                    + "含使用人、经办人与时间，用于对账和追查物品去向。多个资产编号用逗号分隔")
    public String assetHistory(@ToolParam(description = "资产编号，多个用逗号分隔") String assetNos) {
        String bad = Args.required(assetNos, "资产编号");
        if (bad != null) {
            return bad;
        }
        List<String> nos = splitNos(assetNos);
        if (nos.isEmpty()) {
            return "没有解析出有效的资产编号，请提供形如 AST-20260930-0001 的编号。";
        }

        StringBuilder out = new StringBuilder();
        for (String no : nos) {
            List<Map<String, Object>> head = jdbcQuery("""
                    SELECT a.asset_no, a.status, i.name AS item_name, i.sku,
                           w.name AS wh_name, w.code AS wh_code, e.name AS holder_name
                    FROM wms_asset a
                    JOIN wms_item i ON i.id = a.item_id
                    JOIN wms_warehouse w ON w.id = a.warehouse_id
                    LEFT JOIN employee e ON e.id = a.holder_employee_id
                    WHERE a.asset_no = ?
                    """, no);
            if (head.isEmpty()) {
                out.append(no).append("：资产不存在\n\n");
                continue;
            }
            Map<String, Object> h = head.get(0);
            String status = String.valueOf(h.get("status"));
            out.append(h.get("asset_no")).append(" ").append(h.get("item_name"))
               .append("[").append(h.get("sku")).append("] 当前状态：").append(status);
            if (AssetService.ALLOCATED.equals(status)) {
                out.append("（使用人 ").append(h.get("holder_name")).append("）");
            } else if (AssetService.IN_STOCK.equals(status)) {
                out.append("（").append(h.get("wh_name")).append("/").append(h.get("wh_code")).append("）");
            }
            out.append("\n");

            List<Map<String, Object>> logs = jdbcQuery("""
                    SELECT action, from_status, to_status, employee_name, operator, remark, created_at
                    FROM wms_asset_log WHERE asset_no = ? ORDER BY id
                    """, no);
            if (logs.isEmpty()) {
                out.append("- 暂无变动记录\n");
            } else {
                for (Map<String, Object> l : logs) {
                    out.append("- ").append(shortTime(l.get("created_at"))).append(" ").append(l.get("action"));
                    if (l.get("from_status") != null) {
                        out.append("：").append(l.get("from_status")).append(" → ").append(l.get("to_status"));
                    } else {
                        out.append(" → ").append(l.get("to_status"));
                    }
                    if (l.get("employee_name") != null) {
                        out.append("（使用人 ").append(l.get("employee_name")).append("）");
                    }
                    if (l.get("operator") != null) {
                        out.append("（经办 ").append(l.get("operator")).append("）");
                    }
                    if (l.get("remark") != null) {
                        out.append("｜").append(l.get("remark"));
                    }
                    out.append("\n");
                }
            }
            out.append("\n");
        }
        out.append("说明：流水是这件资产的完整履历。若某件资产的流水里最后一条是「调拨给某人」、"
                + "而当前状态显示「在库」，通常是因为该员工后来办理了离职（离职会自动回收名下物品）"
                + "或经办人手工退回过 —— 这是正常业务流转，不是数据异常，请照实说明来龙去脉即可。");
        return out.toString();
    }

    // ==================== 内部辅助 ====================

    private List<Map<String, Object>> jdbcQuery(String sql, Object... args) {
        return jdbc.queryForList(sql, args);
    }

    /** 把一行资产拼成「编号 名称[SKU] | 状态 | 位置或使用人」这样一句话 */
    private static String formatAssetLine(Map<String, Object> r) {
        String status = String.valueOf(r.get("status"));
        StringBuilder sb = new StringBuilder();
        sb.append(r.get("asset_no")).append(" ").append(r.get("item_name"))
          .append("[").append(r.get("sku")).append("] | ").append(status);
        if (AssetService.ALLOCATED.equals(status)) {
            sb.append(" → 使用人 ").append(r.get("holder_name")).append(holderSuffix(r));
            if (r.get("allocated_at") != null) {
                sb.append("（").append(shortTime(r.get("allocated_at"))).append("）");
            }
        } else if (AssetService.SCRAPPED.equals(status)) {
            sb.append("（").append(r.get("scrap_reason") == null ? "原因未填写" : r.get("scrap_reason"))
              .append("，").append(shortTime(r.get("scrapped_at"))).append("）");
        } else {
            sb.append(" @").append(r.get("wh_name")).append("(").append(r.get("wh_code")).append(")");
        }
        return sb.toString();
    }

    /** 「（职位/部门）」后缀，没有职位信息就不显示 */
    private static String holderSuffix(Map<String, Object> r) {
        Object position = r.get("holder_position");
        Object dept = r.get("department");
        if (position == null && dept == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("（");
        if (position != null) {
            sb.append(position);
        }
        if (dept != null) {
            sb.append(position == null ? "" : "/").append(dept);
        }
        return sb.append("）").toString();
    }

    private static String shortTime(Object value) {
        String s = String.valueOf(value);
        return s.length() >= 16 ? s.substring(0, 16) : s;
    }

    /** 拆资产编号：中英文逗号、顿号、空格、换行都当作分隔符 */
    private static List<String> splitNos(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("[,，、;；\\s]+")) {
            String v = part.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }

    private record ItemLookup(Map<String, Object> item, String error) {
    }

    /**
     * 商品关键字解析：命中唯一才返回。与 WmsTools 同款策略 —— 关键字可能匹配多个 SKU，
     * 静默取第一条会把 A 商品登记成 B 商品的资产。
     */
    private ItemLookup resolveItem(String keyword) {
        String k = keyword == null ? "" : keyword.trim();
        if (k.isEmpty()) {
            return new ItemLookup(null, "请提供 SKU 或商品名称。");
        }
        List<Map<String, Object>> rows = jdbcQuery(
                "SELECT id, sku, name, unit FROM wms_item WHERE sku = ? OR name LIKE ? ORDER BY id LIMIT 6",
                k, "%" + k + "%");
        if (rows.isEmpty()) {
            return new ItemLookup(null, "未找到商品「" + k + "」，请先用 add_item 创建商品，或核对 SKU/名称。");
        }
        if (rows.size() > 1) {
            StringBuilder sb = new StringBuilder("关键字「" + k + "」匹配到多个商品，请用 SKU 精确指定：\n");
            for (Map<String, Object> r : rows) {
                sb.append("- [ID=").append(r.get("id")).append("] ").append(r.get("sku"))
                  .append(" ").append(r.get("name")).append("\n");
            }
            return new ItemLookup(null, sb.toString());
        }
        return new ItemLookup(rows.get(0), null);
    }

    private record EmployeeLookup(Map<String, Object> employee, String error) {
    }

    /**
     * 员工解析：支持员工ID 或 姓名。姓名命中多人时必须让用户确认，不能随手挑一个 ——
     * 否则会把物品登记到重名的另一位员工名下。
     */
    private EmployeeLookup resolveEmployee(String keyword) {
        String k = keyword == null ? "" : keyword.trim();
        if (k.isEmpty()) {
            return new EmployeeLookup(null, "请提供员工ID 或姓名。");
        }
        List<Map<String, Object>> rows;
        if (k.matches("\\d+")) {
            rows = jdbcQuery("SELECT id, name, status FROM employee WHERE id = ?", Long.parseLong(k));
        } else {
            rows = jdbcQuery("""
                    SELECT e.id, e.name, e.status, p.department AS dept
                    FROM employee e LEFT JOIN `position` p ON p.id = e.position_id
                    WHERE e.name LIKE ? ORDER BY e.id LIMIT 6
                    """, "%" + k + "%");
        }
        if (rows.isEmpty()) {
            return new EmployeeLookup(null, "未找到员工「" + k + "」，请用 search_employee 确认。");
        }
        if (rows.size() > 1) {
            StringBuilder sb = new StringBuilder("「" + k + "」匹配到多位员工，请用员工ID 指定：\n");
            for (Map<String, Object> r : rows) {
                sb.append("- [ID=").append(r.get("id")).append("] ").append(r.get("name"))
                  .append("（").append(r.get("status")).append("）").append("\n");
            }
            return new EmployeeLookup(null, sb.toString());
        }
        return new EmployeeLookup(rows.get(0), null);
    }

    private Map<String, Object> findWarehouse(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        List<Map<String, Object>> rows = jdbcQuery(
                "SELECT id, code, name FROM wms_warehouse WHERE code = ? OR name = ? ORDER BY id LIMIT 1",
                code.trim(), code.trim());
        return rows.isEmpty() ? null : rows.get(0);
    }
}
