package com.hr.agent.tools;

import com.hr.agent.service.BatchInboundService;

import com.hr.agent.service.SkuGenerator;

import com.hr.agent.util.Args;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 仓库模块工具集（对应结构：tools/wms_tools.py）。
 * 供仓库管理 Agent 通过 Function Calling 调用，所有方法直接操作本地 MySQL。
 *
 * 智能分配规则：
 * - 入库：未指定仓库时，自动选择该 SKU 已有库存且库存最少的仓库（均衡库存），否则选第一个启用仓库；
 * - 出库：未指定仓库时，自动选择库存足够且可整单满足的仓库（优先库存最多者），不足时给出各仓库可出数量。
 *
 * 【写工具约定】与 HrTools 一致：写操作返回值必须回显"数据库中实际写入的最终值"并附 GROUND_TRUTH_HINT，
 * 避免人工在确认框改了参数后，模型因为看不到改动而再调一次写工具把值改回去。
 */
@Component
public class WmsTools {

    private static final DateTimeFormatter ORDER_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 附在写工具返回值末尾：告知模型人工可能改过参数，且以返回值为准 */
    // NOTE: GROUND_TRUTH_HINT 在 HrTools/OrderTools/WmsTools/AssetService 四处有意重复（内容必须保持一致），
    // 任一修改需同步其余三处；确有变更时再抽取到 util 包统一。
    private static final String GROUND_TRUTH_HINT = "\n注：以上为数据库实际写入的最终值。若与你的调用参数或用户原话不一致，说明该值已被人工在确认框中修改，"
            + "请直接按本返回值汇报，不要再调用写工具把值改回去。";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SkuGenerator skuGenerator;
    private final BatchInboundService batchInbound;

    // ==================== 批量入库说明（写进工具描述，让模型照着产出清单）====================

    private static final String BATCH_ITEMS_DOC = """
            商品明细的 JSON **数组字符串**，每个元素形如：
            [{"name":"防化服","spec":"M码","category":"劳保用品","unit":"件","quantity":30,"safetyStock":10},
             {"name":"投影仪","spec":"X300","category":"电子设备","unit":"台","quantity":12}]
            · name 与 quantity 必填，其余可省；只填名称时规格按「无规格」处理；
            · name + spec 相同会被视为**同一件物品**并自动合并数量；
            · 清单里还没有的商品会自动建档（SKU 由系统生成，不要自己编）。
            """;

    public WmsTools(JdbcTemplate jdbc, TransactionTemplate tx,
            SkuGenerator skuGenerator, BatchInboundService batchInbound) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.skuGenerator = skuGenerator;
        this.batchInbound = batchInbound;
    }

    private static String nz(Object v, String fallback) {
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
    }

    /** 插入并返回数据库真实生成的主键（不要用 SELECT MAX(id)，并发下会取到别人的 ID） */
    private Long insertReturningId(String sql, Object... args) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? null : key.longValue();
    }

    /** 列表类工具的统一截断提示 */
    private static String moreHint(int shown, int total, String narrowHint) {
        return total > shown ? "\n（共 " + total + " 条，此处只显示前 " + shown + " 条，可" + narrowHint + "缩小范围）\n" : "";
    }

    /**
     * 商品关键字查找结果：要么命中唯一商品，要么带一条可直接回给模型的说明。
     * 之前用 LIMIT 1 静默取第一条，遇到"无线鼠标"这种能匹配到多个 SKU 的关键字时，
     * 可能把 A 商品的库存入到 B 商品上，所以这里强制要求唯一命中。
     */
    private record ItemLookup(Map<String, Object> item, String error) {
    }

    private ItemLookup resolveItem(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return new ItemLookup(null, "请提供 SKU 或商品名称。");
        }
        String k = keyword.trim();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, sku, name, unit FROM wms_item WHERE sku = ? OR name LIKE ? ORDER BY id LIMIT 6",
                k, "%" + k + "%");
        if (rows.isEmpty()) {
            return new ItemLookup(null, "未找到商品「" + k + "」，请先用 add_item 创建商品，或确认 SKU/名称。");
        }
        if (rows.size() > 1) {
            StringBuilder sb = new StringBuilder("关键字「" + k + "」匹配到多个商品，请用户确认后用 SKU 精确指定：\n");
            for (Map<String, Object> r : rows) {
                sb.append("- [ID=").append(r.get("id")).append("] ").append(r.get("sku"))
                        .append(" ").append(r.get("name")).append("\n");
            }
            return new ItemLookup(null, sb.toString());
        }
        return new ItemLookup(rows.get(0), null);
    }

    // ==================== 仓库 ====================

    @Tool(name = "add_warehouse", description = "新增仓库，需要仓库编码和名称，地址可选")
    public String addWarehouse(
            @ToolParam(description = "仓库编码，如 WH03") String code,
            @ToolParam(description = "仓库名称") String name,
            @ToolParam(description = "地址，可为空") String address) {
        String bad = Args.code(code, "仓库编码");
        if (bad == null) {
            bad = Args.name(name, "仓库名称");
        }
        if (bad == null) {
            bad = Args.text(address, "地址", 200, true);
        }
        if (bad != null) {
            return "无法新增仓库：" + bad;
        }
        code = code.trim();
        name = name.trim();
        address = Args.clean(address);

        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM wms_warehouse WHERE code = ?", Integer.class, code);
        if (exists != null && exists > 0) {
            Map<String, Object> old = jdbc.queryForMap(
                    "SELECT id, code, name, address FROM wms_warehouse WHERE code = ? ORDER BY id LIMIT 1", code);
            return "仓库编码「" + code + "」已存在（ID=" + old.get("id") + "，名称：" + old.get("name")
                    + "），未重复添加。如需修改请告知具体要改的字段。";
        }
        Long newId = insertReturningId(
                "INSERT INTO wms_warehouse(code, name, address) VALUES(?,?,?)", code, name, address);
        if (newId == null) {
            return "新增仓库失败：未能获取新仓库ID，请重试。";
        }
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, code, name, address, status FROM wms_warehouse WHERE id = ?", newId);
        return "已新增仓库（以下为数据库实际写入的最终值，以此为准）：\n"
                + "- 仓库ID：" + row.get("id") + "\n"
                + "- 编码：" + row.get("code") + "\n"
                + "- 名称：" + row.get("name") + "\n"
                + "- 地址：" + nz(row.get("address"), "未填写") + "\n"
                + "- 状态：" + row.get("status")
                + GROUND_TRUTH_HINT;
    }

    @Tool(name = "list_warehouses", description = "查询所有仓库列表")
    public String listWarehouses() {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM wms_warehouse", Integer.class);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, code, name, address, status FROM wms_warehouse ORDER BY id LIMIT ?", Args.MAX_LIST);
        if (rows.isEmpty()) {
            return "还没有任何仓库记录。";
        }
        int totalN = total == null ? rows.size() : total;
        StringBuilder sb = new StringBuilder("仓库列表（共 " + totalN + " 个）：\n");
        for (Map<String, Object> r : rows) {
            sb.append("- [ID=").append(r.get("id")).append("] ").append(r.get("code")).append(" ")
                    .append(r.get("name")).append(" | 地址：")
                    .append(r.get("address") == null ? "-" : r.get("address"))
                    .append(" | 状态：").append(r.get("status")).append("\n");
        }
        return sb.append(moreHint(rows.size(), totalN, "按仓库编码精确查询")).toString();
    }

    // ==================== 商品 ====================

    /**
     * 新增商品。SKU 已改为系统自动生成（用户要求：不再手填编码）。
     *
     * <p>
     * 「同一个物品」的判定口径是 <b>名称 + 规格</b>：名称与规格都相同视为同一件东西，
     * 此时直接复用已有 SKU，不重复建档（幂等）。想新增一个不同款式的同名商品，
     * 把规格填成不同的值即可。
     */
    @Tool(name = "add_item", description = "新增商品。SKU 由系统自动生成（格式 SKU-时间戳-序号），**不需要也不要传 SKU**。"
            + "名称+规格相同视为同一物品，若已存在则复用原 SKU 并如实告知，不会重复建档；"
            + "名称相同但规格不同算两个商品。批量新建商品请优先用 batch_inbound 一次性完成")
    public String addItem(
            @ToolParam(description = "商品名称") String name,
            @ToolParam(description = "分类，可为空") String category,
            @ToolParam(description = "单位，如 个/箱/包，可为空") String unit,
            @ToolParam(description = "规格/型号，可为空。名称相同而规格不同的算两个商品") String spec) {
        String bad = Args.name(name, "商品名称");
        if (bad == null) {
            bad = Args.text(category, "分类", 50, true);
        }
        if (bad == null) {
            bad = Args.text(unit, "单位", 20, true);
        }
        if (bad == null) {
            bad = Args.text(spec, "规格", 100, true);
        }
        if (bad != null) {
            return "无法新增商品：" + bad;
        }
        final String nameF = name.trim();
        final String categoryF = Args.clean(category);
        final String unitF = Args.clean(unit);
        final String specF = Args.clean(spec);

        List<Map<String, Object>> dup = jdbc.queryForList("""
                SELECT id, sku, name, category, unit, spec FROM wms_item
                WHERE name = ? AND COALESCE(spec, '') = ? ORDER BY id LIMIT 1
                """, nameF, specF == null ? "" : specF);
        if (!dup.isEmpty()) {
            Map<String, Object> old = dup.get(0);
            return "商品「" + nameF + "」" + (specF == null ? "（无规格）" : "（规格 " + specF + "）")
                    + "已存在，未重复建档。\n"
                    + "- 商品ID：" + old.get("id") + "\n"
                    + "- SKU：" + old.get("sku") + "\n"
                    + "- 分类：" + nz(old.get("category"), "未填写") + "\n"
                    + "- 单位：" + nz(old.get("unit"), "未填写") + "\n"
                    + "如需增加库存，请用 inbound / batch_inbound 入库。";
        }

        Long newId;
        String newSku;
        try {
            // SKU 取号与插入放同一个事务：取号靠行锁保证同秒内不撞号（见 SkuGenerator）
            final Object[] holder = new Object[2];
            tx.executeWithoutResult(status -> {
                String sku = skuGenerator.next();
                holder[0] = sku;
                holder[1] = insertReturningId(
                        "INSERT INTO wms_item(sku, name, category, unit, spec) VALUES(?,?,?,?,?)",
                        sku, nameF, categoryF, unitF, specF);
            });
            newSku = (String) holder[0];
            newId = (Long) holder[1];
        } catch (org.springframework.dao.DataAccessException e) {
            return "新增商品失败，本次操作已回滚。原因：" + rootMessage(e) + "，请重试。";
        }
        if (newId == null) {
            return "新增商品失败：未能获取新商品ID，请重试。";
        }
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, sku, name, category, unit, spec FROM wms_item WHERE id = ?", newId);
        return "已新增商品（以下为数据库实际写入的最终值，以此为准）：\n"
                + "- 商品ID：" + row.get("id") + "\n"
                + "- SKU：" + row.get("sku") + "（系统自动生成，" + SkuGenerator.formatHint() + "）\n"
                + "- 名称：" + row.get("name") + "\n"
                + "- 分类：" + nz(row.get("category"), "未填写") + "\n"
                + "- 单位：" + nz(row.get("unit"), "未填写") + "\n"
                + "- 规格：" + nz(row.get("spec"), "未填写")
                + GROUND_TRUTH_HINT;
    }

    @Tool(name = "list_items", description = "查询商品列表，可按名称或 SKU 关键字模糊搜索")
    public String listItems(@ToolParam(description = "关键字，可为空表示全部") String keyword) {
        boolean all = (keyword == null || keyword.isBlank());
        String where = all ? "" : "WHERE name LIKE ? OR sku LIKE ? ";
        Object[] args = all ? new Object[0] : new Object[] { "%" + keyword + "%", "%" + keyword + "%" };
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM wms_item " + where, Integer.class, args);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, sku, name, category, unit, spec FROM wms_item " + where + "ORDER BY id LIMIT ?",
                append(args, Args.MAX_LIST));
        if (rows.isEmpty()) {
            return "没有查询到商品。";
        }
        int totalN = total == null ? rows.size() : total;
        StringBuilder sb = new StringBuilder("商品列表（共 " + totalN + " 个）：\n");
        for (Map<String, Object> r : rows) {
            sb.append("- [ID=").append(r.get("id")).append("] ").append(r.get("sku")).append(" ")
                    .append(r.get("name")).append(" | 分类：")
                    .append(r.get("category") == null ? "-" : r.get("category"))
                    .append(" | 单位：").append(r.get("unit") == null ? "-" : r.get("unit"))
                    .append(" | 规格：").append(r.get("spec") == null ? "-" : r.get("spec"))
                    .append("\n");
        }
        return sb.append(moreHint(rows.size(), totalN, "用更精确的关键字")).toString();
    }

    private static Object[] append(Object[] args, Object extra) {
        Object[] out = java.util.Arrays.copyOf(args, args.length + 1);
        out[args.length] = extra;
        return out;
    }

    // ==================== 库存 ====================

    @Tool(name = "query_stock", description = "查询库存：按商品名称或 SKU 关键字查询在各仓库的库存量与安全库存，并提示是否低于安全库存")
    public String queryStock(
            @ToolParam(description = "商品名称或 SKU 关键字，可为空表示查询全部库存") String itemKeyword,
            @ToolParam(description = "仓库编码，可为空表示所有仓库") String warehouseCode) {
        StringBuilder sql = new StringBuilder("""
                SELECT w.code AS wh_code, w.name AS wh_name, i.sku, i.name AS item_name, i.unit,
                       s.quantity, s.safety_stock
                FROM wms_stock s
                JOIN wms_warehouse w ON s.warehouse_id = w.id
                JOIN wms_item i ON s.item_id = i.id
                WHERE 1 = 1
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        if (itemKeyword != null && !itemKeyword.isBlank()) {
            sql.append(" AND (i.name LIKE ? OR i.sku LIKE ?)");
            args.add("%" + itemKeyword + "%");
            args.add("%" + itemKeyword + "%");
        }
        if (warehouseCode != null && !warehouseCode.isBlank()) {
            sql.append(" AND w.code = ?");
            args.add(warehouseCode);
        }
        sql.append(" ORDER BY i.sku, w.code LIMIT ?");
        args.add(Args.MAX_LIST);

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        if (rows.isEmpty()) {
            return "没有查询到库存记录（可能是商品或仓库不存在，或尚未入库）。";
        }
        StringBuilder sb = new StringBuilder("库存查询结果（返回 " + rows.size() + " 条）：\n");
        for (Map<String, Object> r : rows) {
            int qty = ((Number) r.get("quantity")).intValue();
            int safety = ((Number) r.get("safety_stock")).intValue();
            sb.append("- ").append(r.get("wh_name")).append("(").append(r.get("wh_code")).append(") ")
                    .append(r.get("item_name")).append("[").append(r.get("sku")).append("]：")
                    .append(qty).append(r.get("unit") == null ? "" : r.get("unit"))
                    .append("，安全库存 ").append(safety)
                    .append(qty < safety ? "  ⚠ 低于安全库存" : "")
                    .append("\n");
        }
        if (rows.size() >= Args.MAX_LIST) {
            sb.append("\n（已达单次返回上限 ").append(Args.MAX_LIST)
                    .append(" 条，请用商品关键字或仓库编码缩小范围再查）\n");
        }
        return sb.toString();
    }

    @Tool(name = "list_stock_alerts", description = "查询所有低于安全库存的商品清单（库存预警）")
    public String listStockAlerts() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT w.code AS wh_code, w.name AS wh_name, i.sku, i.name AS item_name, i.unit,
                       s.quantity, s.safety_stock
                FROM wms_stock s
                JOIN wms_warehouse w ON s.warehouse_id = w.id
                JOIN wms_item i ON s.item_id = i.id
                WHERE s.quantity < s.safety_stock
                ORDER BY (s.safety_stock - s.quantity) DESC
                """);
        if (rows.isEmpty()) {
            return "当前没有低于安全库存的商品，库存状态正常。";
        }
        StringBuilder sb = new StringBuilder("库存预警（" + rows.size() + " 项低于安全库存）：\n");
        for (Map<String, Object> r : rows) {
            sb.append("- ").append(r.get("wh_name")).append(" ").append(r.get("item_name"))
                    .append("[").append(r.get("sku")).append("]：当前 ").append(r.get("quantity"))
                    .append("，安全库存 ").append(r.get("safety_stock"))
                    .append("，建议补货 ").append(
                            ((Number) r.get("safety_stock")).intValue() - ((Number) r.get("quantity")).intValue())
                    .append("\n");
        }
        return sb.toString();
    }

    @Tool(name = "set_safety_stock", description = "设置某商品的安全库存阈值（用于库存预警）。仓库编码为空时，该商品在所有仓库的安全库存都会被设置为同一值")
    public String setSafetyStock(
            @ToolParam(description = "SKU 或商品名称关键字") String sku,
            @ToolParam(description = "仓库编码，可为空表示该商品的所有仓库") String warehouseCode,
            @ToolParam(description = "安全库存数量") Integer safetyStock) {
        String bad = Args.nonNegative(safetyStock, "安全库存数量");
        if (bad != null) {
            return bad;
        }
        ItemLookup lookup = resolveItem(sku);
        if (lookup.error() != null) {
            return lookup.error();
        }
        Map<String, Object> item = lookup.item();
        Long itemId = ((Number) item.get("id")).longValue();
        boolean allWarehouses = (warehouseCode == null || warehouseCode.isBlank());
        int updated = allWarehouses
                ? jdbc.update("UPDATE wms_stock SET safety_stock = ? WHERE item_id = ?", safetyStock, itemId)
                : jdbc.update("""
                        UPDATE wms_stock s JOIN wms_warehouse w ON s.warehouse_id = w.id
                        SET s.safety_stock = ? WHERE s.item_id = ? AND w.code = ?
                        """, safetyStock, itemId, warehouseCode);
        if (updated == 0) {
            return "该商品在"
                    + (allWarehouses ? "任何仓库" : "仓库「" + warehouseCode + "」")
                    + "还没有库存记录，请先办理入库后再设置安全库存。";
        }
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT w.code AS wh_code, w.name AS wh_name, s.quantity, s.safety_stock
                FROM wms_stock s JOIN wms_warehouse w ON s.warehouse_id = w.id
                WHERE s.item_id = ?
                ORDER BY w.code
                """, itemId);
        StringBuilder sb = new StringBuilder("已设置安全库存（以下为数据库实际写入的最终值，以此为准）：\n"
                + "- 商品：" + item.get("name") + "[" + item.get("sku") + "]\n");
        for (Map<String, Object> r : rows) {
            int qty = ((Number) r.get("quantity")).intValue();
            int safety = ((Number) r.get("safety_stock")).intValue();
            sb.append("- ").append(r.get("wh_name")).append("(").append(r.get("wh_code")).append(")：安全库存 ")
                    .append(safety).append("，当前库存 ").append(qty)
                    .append(qty < safety ? " ⚠ 低于安全库存" : "")
                    .append("\n");
        }
        return sb.append(GROUND_TRUTH_HINT).toString();
    }

    // ==================== 出入库 ====================

    /**
     * 批量入库：一张清单 = 一次工具调用 = 一次人工确认。
     *
     * <p>
     * 这一条是为了解决"100 个商品要点 100 次确认"的问题而存在的：原来的 {@code inbound}
     * 是单品工具，模型只能按商品逐个调用，而 HITL 是按工具调用弹确认框的。
     * 批量场景请一律走这里，**不要把一张清单拆成多次调用**。
     */
    @Tool(name = "batch_inbound", description = "【批量入库·首选】一次把整张入库清单写进库，只弹一次确认框供用户核对明细。"
            + "清单里的商品若不存在会自动建档并自动生成 SKU。"
            + "凡是「一次入库多个商品 / 提供一张入库清单 / 批量初始化商品」都必须用本工具，"
            + "**绝对不要按商品拆成多次调用**（那样用户要点很多次确认）。")
    public String batchInbound(
            @ToolParam(description = BATCH_ITEMS_DOC) String items,
            @ToolParam(description = "仓库编码，可为空（默认入库到第一个启用的仓库）") String warehouseCode,
            @ToolParam(description = "供应商，可为空") String supplier,
            @ToolParam(description = "经办人，可为空") String operator) {
        BatchInboundService.Plan plan = batchInbound.parse(items);
        if (!plan.ok()) {
            return "无法批量入库：" + plan.error();
        }
        BatchInboundService.Outcome out = batchInbound.execute(plan, warehouseCode, supplier, operator);
        if (!out.ok()) {
            return out.error();
        }

        List<BatchInboundService.ItemOutcome> list = out.outcomes();
        int createdN = 0;
        long totalQty = 0;
        for (BatchInboundService.ItemOutcome o : list) {
            if (o.created()) {
                createdN++;
            }
            totalQty += o.quantity();
        }

        StringBuilder sb = new StringBuilder("批量入库成功，整批一次写入完成（以下为数据库实际写入的最终值，以此为准）：\n")
                .append("- 批次单号：").append(out.batchNo())
                .append("（共 ").append(list.size()).append(" 行，各行单号 ")
                .append(out.batchNo()).append("-001 ~ -")
                .append(String.format("%03d", list.size())).append("）\n")
                .append("- 入库仓库：").append(out.warehouseName()).append("(").append(out.warehouseCode()).append(")\n")
                .append("- 商品数：").append(list.size())
                .append("（新建 ").append(createdN).append(" 个，已有 ").append(list.size() - createdN).append(" 个）\n")
                .append("- 入库总件数：").append(totalQty).append("\n")
                .append("- 供应商：").append(nz(supplier, "未填写"))
                .append("｜经办人：").append(nz(operator, "未填写")).append("\n");

        int shown = 0;
        int maxShow = 30;
        if (!list.isEmpty()) {
            sb.append("- 明细：\n");
            for (BatchInboundService.ItemOutcome o : list) {
                if (shown++ >= maxShow) {
                    break;
                }
                sb.append("  ").append(o.created() ? "＋新建 " : "  ")
                        .append("[").append(o.sku()).append("] ").append(o.name())
                        .append(" 入库 ").append(o.quantity())
                        .append("（库存 ").append(o.before()).append(" → ").append(o.after()).append("）\n");
            }
            if (list.size() > maxShow) {
                sb.append("  …… 共 ").append(list.size()).append(" 行，此处只列前 ").append(maxShow)
                        .append(" 行；完整明细可看确认框里的预览表，或用 list_orders 查批次单号 ")
                        .append(out.batchNo()).append("。\n");
            }
        }
        if (out.warnings() != null && !out.warnings().isEmpty()) {
            sb.append("- 需要留意的几点：\n");
            for (String w : out.warnings()) {
                sb.append("  ⚠ ").append(w).append("\n");
            }
        }
        return sb.append(GROUND_TRUTH_HINT).toString();
    }

    @Tool(name = "inbound", description = "单个商品入库：按 SKU（或名称关键字）与数量入库，写入库存、流水与入库单。未指定仓库时自动选择合适的仓库。"
            + "只入一个商品时用它；一次要入多个商品请用 batch_inbound")
    public String inbound(
            @ToolParam(description = "SKU 或商品名称关键字") String sku,
            @ToolParam(description = "入库数量") Integer quantity,
            @ToolParam(description = "仓库编码，可为空（自动选择）") String warehouseCode,
            @ToolParam(description = "供应商，可为空") String supplier,
            @ToolParam(description = "经办人，可为空") String operator) {
        String bad = Args.quantity(quantity, "入库数量");
        if (bad != null) {
            return "无法入库：" + bad;
        }
        ItemLookup lookup = resolveItem(sku);
        if (lookup.error() != null) {
            return lookup.error();
        }
        Map<String, Object> item = lookup.item();
        Long itemId = ((Number) item.get("id")).longValue();
        supplier = Args.clean(supplier);
        operator = Args.clean(operator);

        Map<String, Object> warehouse = (warehouseCode == null || warehouseCode.isBlank())
                ? pickWarehouseForInbound(itemId)
                : findWarehouse(warehouseCode);
        if (warehouse == null) {
            return "未找到可用仓库" + (warehouseCode == null || warehouseCode.isBlank() ? "" : "「" + warehouseCode + "」")
                    + "，请先用 add_warehouse 创建仓库。";
        }
        Long whId = ((Number) warehouse.get("id")).longValue();

        // 改库存 + 开入库单 + 记流水 必须同生共死：否则一旦中途失败，
        // 会出现"库存加了但没有单据"或"有单据但库存没加"的不一致数据。
        final String supplierF = supplier;
        final String operatorF = operator;
        int[] qty = new int[2];
        String orderNo;
        try {
            orderNo = tx.execute(status -> {
                qty[0] = currentQty(whId, itemId);
                qty[1] = qty[0] + quantity;
                upsertStock(whId, itemId, qty[1]);
                String no = nextOrderNo("IN");
                jdbc.update(
                        """
                                INSERT INTO wms_order(order_no, order_type, warehouse_id, item_id, quantity, party, operator, status)
                                VALUES(?, '入库', ?, ?, ?, ?, ?, '已完成')
                                """,
                        no, whId, itemId, quantity, supplierF, operatorF);
                jdbc.update(
                        """
                                INSERT INTO wms_stock_log(warehouse_id, item_id, change_type, quantity, before_qty, after_qty, ref_no, operator)
                                VALUES(?, ?, '入库', ?, ?, ?, ?, ?)
                                """,
                        whId, itemId, quantity, qty[0], qty[1], no, operatorF);
                // 统一资产模型：入库即生成 wms_asset 记录（一物一码），确保所有物品都在库且可盘点
                generateAssetsForInbound(whId, itemId, quantity, no);
                return no;
            });
        } catch (org.springframework.dao.DataAccessException e) {
            return "入库失败，本次操作已整体回滚（库存、单据、流水均未变更）。原因：" + rootMessage(e)
                    + "。请重试或联系管理员。";
        }

        return "入库成功（以下为数据库实际写入的最终值，以此为准）：\n- 单号：" + orderNo
                + "\n- 商品：" + item.get("name") + "[" + item.get("sku") + "]"
                + "\n- 仓库：" + warehouse.get("name") + "(" + warehouse.get("code") + ")"
                + "\n- 入库数量：" + quantity + (item.get("unit") == null ? "" : item.get("unit"))
                + "（库存 " + qty[0] + " → " + qty[1] + "）"
                + "\n- 供应商：" + nz(supplier, "未填写")
                + "\n- 经办人：" + nz(operator, "未填写")
    // NOTE: 本方法的执行逻辑被 OutboundFormController#submit 复制（结构化表单出库，按设计绕过 HITL）。
    // 两处为有意复制，修改出库规则时必须同步修改对方，防止语义漂移。
                + GROUND_TRUTH_HINT;
    }

    @Tool(name = "outbound", description = "商品出库：按 SKU（或名称关键字）与数量出库，自动校验库存并写入流水与出库单；未指定仓库时自动选择库存充足的仓库")
    public String outbound(
            @ToolParam(description = "SKU 或商品名称关键字") String sku,
            @ToolParam(description = "出库数量") Integer quantity,
            @ToolParam(description = "仓库编码，可为空（自动选择）") String warehouseCode,
            @ToolParam(description = "收货方/领用部门，可为空") String receiver,
            @ToolParam(description = "经办人，可为空") String operator) {
        String bad = Args.quantity(quantity, "出库数量");
        if (bad != null) {
            return "无法出库：" + bad;
        }
        ItemLookup lookup = resolveItem(sku);
        if (lookup.error() != null) {
            return lookup.error();
        }
        Map<String, Object> item = lookup.item();
        Long itemId = ((Number) item.get("id")).longValue();
        receiver = Args.clean(receiver);
        operator = Args.clean(operator);

        List<Map<String, Object>> stocks = jdbc.queryForList("""
                SELECT w.id AS wh_id, w.code AS wh_code, w.name AS wh_name, s.quantity
                FROM wms_stock s JOIN wms_warehouse w ON s.warehouse_id = w.id
                WHERE s.item_id = ? ORDER BY s.quantity DESC
                """, itemId);
        if (stocks.isEmpty()) {
            return "商品「" + item.get("name") + "」当前没有任何库存记录，无法出库。";
        }

        Map<String, Object> target = null;
        if (warehouseCode == null || warehouseCode.isBlank()) {
            // 智能选仓：库存最多且满足整单的仓库
            for (Map<String, Object> s : stocks) {
                if (((Number) s.get("quantity")).intValue() >= quantity) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                return "各仓库库存均不足 " + quantity + "，无法整单出库。当前可出库情况：\n" + stocksSummary(stocks);
            }
        } else {
            for (Map<String, Object> s : stocks) {
                if (warehouseCode.equalsIgnoreCase(String.valueOf(s.get("wh_code")))) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                return "仓库「" + warehouseCode + "」没有该商品的库存。当前库存分布：\n" + stocksSummary(stocks);
            }
            int qty = ((Number) target.get("quantity")).intValue();
            if (qty < quantity) {
                return "仓库「" + warehouseCode + "」库存不足：当前 " + qty + "，申请出库 " + quantity + "。";
            }
        }

        final Long whId = ((Number) target.get("wh_id")).longValue();
        final String receiverF = receiver;
        final String operatorF = operator;
        int[] qty = new int[2];
        String orderNo;
        try {
            orderNo = tx.execute(status -> {
                // 关键：在事务内重新读取并加行锁（FOR UPDATE）。
                // 否则两个并发出库会同时读到旧库存，各自扣减，导致超卖（库存被扣成负数或扣少）。
                List<Integer> now = jdbc.queryForList(
                        "SELECT quantity FROM wms_stock WHERE warehouse_id = ? AND item_id = ? FOR UPDATE",
                        Integer.class, whId, itemId);
                int before = now.isEmpty() ? 0 : now.get(0);
                if (before < quantity) {
                    throw new InsufficientStockException(before);
                }
                qty[0] = before;
                qty[1] = before - quantity;
                jdbc.update("UPDATE wms_stock SET quantity = ? WHERE warehouse_id = ? AND item_id = ?",
                        qty[1], whId, itemId);
                String no = nextOrderNo("OUT");
                jdbc.update(
                        """
                                INSERT INTO wms_order(order_no, order_type, warehouse_id, item_id, quantity, party, operator, status)
                                VALUES(?, '出库', ?, ?, ?, ?, ?, '已完成')
                                """,
                        no, whId, itemId, quantity, receiverF, operatorF);
                jdbc.update(
                        """
                                INSERT INTO wms_stock_log(warehouse_id, item_id, change_type, quantity, before_qty, after_qty, ref_no, operator)
                                VALUES(?, ?, '出库', ?, ?, ?, ?, ?)
                                """,
                        whId, itemId, quantity, qty[0], qty[1], no, operatorF);
                // 统一资产模型：出库时从 wms_asset 中按先进先出（最早创建）标记为「已出库」
                markAssetsAsOutbound(whId, itemId, quantity, no);
                return no;
            });
        } catch (InsufficientStockException e) {
            // 事务已回滚，库存、单据、流水均未变更
            return "出库失败：仓库「" + target.get("wh_name") + "(" + target.get("wh_code") + ")」的库存在这期间被其它操作占用了，"
                    + "当前实际可出 " + e.available + "，申请出库 " + quantity + "。请减少数量或改用其它仓库。";
        } catch (org.springframework.dao.DataAccessException e) {
            return "出库失败，本次操作已整体回滚（库存、单据、流水均未变更）。原因：" + rootMessage(e)
                    + "。请重试或联系管理员。";
        }

        Integer safety = jdbc.queryForObject(
                "SELECT safety_stock FROM wms_stock WHERE warehouse_id = ? AND item_id = ?", Integer.class, whId,
                itemId);
        String warn = (safety != null && qty[1] < safety)
                ? "\n⚠ 出库后库存 " + qty[1] + " 已低于安全库存 " + safety + "，建议补货。"
                : "";

        return "出库成功（以下为数据库实际写入的最终值，以此为准）：\n- 单号：" + orderNo
                + "\n- 商品：" + item.get("name") + "[" + item.get("sku") + "]"
                + "\n- 仓库：" + target.get("wh_name") + "(" + target.get("wh_code") + ")"
                + "\n- 出库数量：" + quantity + "（库存 " + qty[0] + " → " + qty[1] + "）"
                + "\n- 收货方：" + nz(receiver, "未填写")
                + "\n- 经办人：" + nz(operator, "未填写")
                + warn
                + GROUND_TRUTH_HINT;
    }

    @Tool(name = "list_orders", description = "查询最近的出入库单；orderType 传 入库/出库/全部")
    public String listOrders(
            @ToolParam(description = "类型：入库/出库/全部") String orderType,
            @ToolParam(description = "返回条数，默认 10") Integer limit) {
        int n = (limit == null || limit <= 0) ? 10 : Math.min(limit, 50);
        String sql = """
                SELECT o.order_no, o.order_type, w.name AS wh_name, i.name AS item_name, i.sku,
                       o.quantity, o.party, o.operator, o.created_at
                FROM wms_order o
                JOIN wms_warehouse w ON o.warehouse_id = w.id
                JOIN wms_item i ON o.item_id = i.id
                """;
        List<Map<String, Object>> rows;
        if (orderType == null || orderType.isBlank() || "全部".equals(orderType)) {
            rows = jdbc.queryForList(sql + "ORDER BY o.id DESC LIMIT ?", n);
        } else {
            rows = jdbc.queryForList(sql + "WHERE o.order_type = ? ORDER BY o.id DESC LIMIT ?", orderType, n);
        }
        if (rows.isEmpty()) {
            return "没有查询到出入库单。";
        }
        StringBuilder sb = new StringBuilder("最近出入库单（" + rows.size() + " 条）：\n");
        for (Map<String, Object> r : rows) {
            sb.append("- [").append(r.get("order_type")).append("] ").append(r.get("order_no"))
                    .append(" ").append(r.get("item_name")).append("[").append(r.get("sku")).append("] ")
                    .append(r.get("quantity")).append(" @ ").append(r.get("wh_name"))
                    .append(r.get("party") == null ? "" : " | 往来：" + r.get("party"))
                    .append(r.get("operator") == null ? "" : " | 经办：" + r.get("operator"))
                    .append(" | ").append(r.get("created_at"))
                    .append("\n");
        }
        return sb.toString();
    }

    // ==================== 内部工具方法 ====================

    /** 事务内发现库存不足时抛出的信号异常：抛出即回滚，由 outbound 捕获后转成可读提示 */
    private static final class InsufficientStockException extends RuntimeException {
        final int available;

        InsufficientStockException(int available) {
            super("insufficient stock: " + available);
            this.available = available;
        }
    }

    /** 把底层异常里最有信息量的一句取出来（JdbcTemplate 会包好几层） */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
    }

    private Map<String, Object> findWarehouse(String code) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, code, name FROM wms_warehouse WHERE code = ? OR name = ? ORDER BY id LIMIT 1", code, code);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 入库智能选仓：优先选择已有该商品库存且库存最少的仓库（均衡），否则第一个启用仓库 */
    private Map<String, Object> pickWarehouseForInbound(Long itemId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT w.id, w.code, w.name, COALESCE(s.quantity, -1) AS qty
                FROM wms_warehouse w
                LEFT JOIN wms_stock s ON s.warehouse_id = w.id AND s.item_id = ?
                WHERE w.status = '启用'
                ORDER BY qty ASC, w.id ASC
                LIMIT 1
                """, itemId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int currentQty(Long warehouseId, Long itemId) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT quantity FROM wms_stock WHERE warehouse_id = ? AND item_id = ?", Integer.class, warehouseId,
                itemId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private void upsertStock(Long warehouseId, Long itemId, int quantity) {
        int updated = jdbc.update("UPDATE wms_stock SET quantity = ? WHERE warehouse_id = ? AND item_id = ?",
                quantity, warehouseId, itemId);
        if (updated == 0) {
            jdbc.update("INSERT INTO wms_stock(warehouse_id, item_id, quantity, safety_stock) VALUES(?,?,?,0)",
                    warehouseId, itemId, quantity);
        }
    }

    /** 生成唯一单号：时间戳 + 随机数，并与库内已有单号比对，冲突则重试 */
    private String nextOrderNo(String prefix) {
        for (int i = 0; i < 5; i++) {
            String no = prefix + LocalDateTime.now().format(ORDER_FMT)
                    + ThreadLocalRandom.current().nextInt(100, 999);
            Integer dup = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wms_order WHERE order_no = ?", Integer.class, no);
            if (dup == null || dup == 0) {
                return no;
            }
        }
        // 连续冲突时退化为随机位更多的单号，避免死循环
        return prefix + LocalDateTime.now().format(ORDER_FMT)
                + ThreadLocalRandom.current().nextInt(10000, 99999);
    }

    private String stocksSummary(List<Map<String, Object>> stocks) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> s : stocks) {
            sb.append("- ").append(s.get("wh_name")).append("(").append(s.get("wh_code")).append(")：")
                    .append(s.get("quantity")).append("\n");
        }
        return sb.toString();
    }

    /**
     * 出库时按先进先出（FIFO）标记资产为「已出库」。
     * 从最早创建的「在库」资产开始标记，确保账实一致。
     */
    private void markAssetsAsOutbound(Long warehouseId, Long itemId, int quantity, String refOrderNo) {
        // 查询最早创建的「在库」资产
        List<Map<String, Object>> assets = jdbc.queryForList("""
                SELECT id, asset_no FROM wms_asset
                WHERE warehouse_id = ? AND item_id = ? AND status = '在库'
                ORDER BY id ASC
                LIMIT ?
                """, warehouseId, itemId, quantity);

        for (Map<String, Object> asset : assets) {
            String assetNo = String.valueOf(asset.get("asset_no"));
            jdbc.update("""
                    UPDATE wms_asset SET status = '已出库' WHERE id = ?
                    """, asset.get("id"));
            jdbc.update("""
                    INSERT INTO wms_asset_log(asset_no, action, from_status, to_status, remark, created_at)
                    VALUES(?, '出库', '在库', '已出库', ?, NOW())
                    """, assetNo, "出库单 " + refOrderNo);
        }
    }

    /**
     * 为入库的商品生成一物一码的资产记录（wms_asset）。
     * 统一资产模型：所有入库的物品都必须有资产身份，确保可盘点、可追溯。
     * @param warehouseId 仓库ID
     * @param itemId      商品ID
     * @param quantity    入库数量
     * @param refOrderNo  关联的入库单号
     */
    private void generateAssetsForInbound(Long warehouseId, Long itemId, int quantity, String refOrderNo) {
        // 获取当前最大资产编号序号（当天）
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "AST-" + dateStr + "-";
        Integer maxSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(CAST(SUBSTRING(asset_no, ?) AS UNSIGNED)), 0) FROM wms_asset WHERE asset_no LIKE ?",
                Integer.class, prefix.length() + 1, prefix + "%");
        int startSeq = (maxSeq == null ? 0 : maxSeq) + 1;

        // 批量插入资产记录
        for (int i = 0; i < quantity; i++) {
            String assetNo = prefix + String.format("%04d", startSeq + i);
            jdbc.update("""
                    INSERT INTO wms_asset(asset_no, item_id, warehouse_id, status, created_at)
                    VALUES(?, ?, ?, '在库', NOW())
                    """, assetNo, itemId, warehouseId);
            // 记录资产变动流水
            jdbc.update("""
                    INSERT INTO wms_asset_log(asset_no, action, to_status, remark, created_at)
                    VALUES(?, '入库', '在库', ?, NOW())
                    """, assetNo, "入库单 " + refOrderNo);
        }
    }
}
