package com.hr.agent.service;

import com.hr.agent.util.Args;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.spi.ApprovalPreviewProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 批量入库服务：一次调用把整张入库清单写进库，只产生<em>一次</em>人工确认。
 *
 * <p><b>为什么要有这个类：</b>原来的 {@code inbound} 是单品工具 —— 一张 100 行的清单会被模型拆成
 * 100 次工具调用，而 HITL 是按工具调用弹确认框的，于是用户要点 100 次「批准」。
 * 这不是模型偷懒，是工具粒度的问题。所以把一个批次的写入收进一个工具、一个事务、一次确认。
 *
 * <p><b>「预览」和「执行」必须共用同一套解析逻辑：</b>
 * 否则预览里说"将新建 100 个商品"，执行时却因为解析差异变成 98 个，用户核对的就不是他要执行的东西。
 * 因此这里把流程拆成三段，预览和执行走的是同一个 {@link Plan}：
 * <ol>
 *   <li>{@link #parse} —— 纯解析 + 校验 + 合并同名同规格，不碰数据库；</li>
 *   <li>{@link #preview} —— 只读查库，标出哪些商品是新建、哪些已存在、会生成什么 SKU；</li>
 *   <li>{@link #execute} —— 一个事务里完成建商品、加库存、开单、记流水。</li>
 * </ol>
 *
 * <p><b>{@link #preview} 绝对不能写库</b>（见 {@link ApprovalPreviewProvider} 的类注释）：
 * 用户在确认框里可能拒绝，也可能改参数。预览阶段就占号的话，用户一拒绝，一批 SKU 序号就白白烧掉了。
 */
@Component
public class BatchInboundService implements ApprovalPreviewProvider {

    /** 一次批量入库最多处理的行数（合并同名同规格之后） */
    public static final int MAX_LINES = 200;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final SkuGenerator skuGenerator;

    public BatchInboundService(JdbcTemplate jdbc, TransactionTemplate tx, SkuGenerator skuGenerator) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.skuGenerator = skuGenerator;
    }

    // ==================== 数据结构 ====================

    /**
     * 合并后的一行明细。
     *
     * @param seq         合并后的行号（从 1 开始）
     * @param mergedFrom  合并了几个原始行（>1 说明清单里有重复，已把数量加总）
     */
    public record Line(int seq, String name, String spec, String category, String unit,
                       int quantity, Integer safetyStock, int mergedFrom) {
    }

    /** 解析结果：要么 lines 有值，要么 error 有值 */
    public record Plan(List<Line> lines, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** 一行明细的执行结果 */
    public record ItemOutcome(String sku, String name, boolean created, int quantity,
                              int before, int after, String orderNo) {
    }

    /** 执行结果：error 非空表示整批已回滚、什么都没写 */
    public record Outcome(String batchNo, String warehouseCode, String warehouseName,
                          List<ItemOutcome> outcomes, List<String> warnings, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    // ==================== 1. 解析 ====================

    /**
     * 把清单 JSON 解析成明细行。宽容处理常见的模型输出走样：markdown 代码围栏、
     * 包一层 {"items":[...]}、数字写成字符串、字段用中文名。
     */
    public Plan parse(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) {
            return new Plan(null, "入库清单为空：请在 items 里传入商品明细的 JSON 数组。");
        }
        JsonNode root;
        try {
            root = JSON.readTree(stripFence(itemsJson));
        } catch (Exception e) {
            return new Plan(null, "入库清单不是合法 JSON：" + e.getMessage()
                    + "。应形如 [{ \"name\":\"防化服\", \"spec\":\"M码\", \"category\":\"劳保用品\", \"unit\":\"件\", \"quantity\":30 }]。");
        }
        if (root.isObject() && root.has("items")) {
            root = root.get("items");
        }
        if (!root.isArray()) {
            return new Plan(null, "入库清单必须是一个 JSON 数组。");
        }
        if (root.isEmpty()) {
            return new Plan(null, "入库清单里没有任何明细行。");
        }
        if (root.size() > MAX_LINES * 4) {
            return new Plan(null, "入库清单有 " + root.size() + " 行，超过单次上限（" + MAX_LINES
                    + " 行，含重复行）。请拆成多批。");
        }

        // 先按「名称 + 规格」合并：同一个商品在清单里出现多次属于常见情况（分供应商、分批到货），
        // 直接当两行写会变成两条单据、两个 SKU —— 那是错的，应该是同一商品的数量相加。
        Map<String, Line> merged = new LinkedHashMap<>();
        int rawIndex = 0;
        for (JsonNode node : root) {
            rawIndex++;
            if (!node.isObject()) {
                return new Plan(null, "第 " + rawIndex + " 行不是对象：" + node);
            }
            String name = text(node, "name", "名称", "商品名称", "商品");
            if (name == null) {
                return new Plan(null, "第 " + rawIndex + " 行缺少商品名称（name）。");
            }
            String bad = Args.name(name, "第 " + rawIndex + " 行的商品名称");
            if (bad != null) {
                return new Plan(null, bad);
            }
            name = name.trim();

            String spec = text(node, "spec", "规格", "型号");
            bad = Args.text(spec, "第 " + rawIndex + " 行的规格", 100, true);
            if (bad != null) {
                return new Plan(null, bad);
            }
            spec = Args.clean(spec);

            String category = Args.clean(text(node, "category", "类别", "分类"));
            bad = Args.text(category, "第 " + rawIndex + " 行的类别", 50, true);
            if (bad != null) {
                return new Plan(null, bad);
            }

            String unit = Args.clean(text(node, "unit", "单位"));
            bad = Args.text(unit, "第 " + rawIndex + " 行的单位", 20, true);
            if (bad != null) {
                return new Plan(null, bad);
            }

            Integer quantity = intOf(node, "quantity", "qty", "数量", "入库数量");
            if (quantity == null) {
                return new Plan(null, "第 " + rawIndex + " 行（" + name + "）缺少入库数量（quantity）。");
            }
            bad = Args.quantity(quantity, "第 " + rawIndex + " 行（" + name + "）的入库数量");
            if (bad != null) {
                return new Plan(null, bad);
            }

            Integer safety = intOf(node, "safetyStock", "safety_stock", "安全库存");
            if (safety != null) {
                bad = Args.nonNegative(safety, "第 " + rawIndex + " 行（" + name + "）的安全库存");
                if (bad != null) {
                    return new Plan(null, bad);
                }
            }

            String key = name + "\u0000" + (spec == null ? "" : spec);
            Line old = merged.get(key);
            if (old == null) {
                merged.put(key, new Line(merged.size() + 1, name, spec, category, unit, quantity, safety, 1));
            } else {
                // 数量相加；类别/单位/安全库存取第一个非空值（后出现的重复行只用来补数量）
                merged.put(key, new Line(old.seq(), old.name(), old.spec(),
                        old.category() != null ? old.category() : category,
                        old.unit() != null ? old.unit() : unit,
                        old.quantity() + quantity,
                        old.safetyStock() != null ? old.safetyStock() : safety,
                        old.mergedFrom() + 1));
            }
        }

        List<Line> lines = new ArrayList<>(merged.values());
        if (lines.size() > MAX_LINES) {
            return new Plan(null, "清单合并后仍有 " + lines.size() + " 个不同商品，超过单次上限 "
                    + MAX_LINES + " 个。请拆成多批入库。");
        }
        return new Plan(lines, null);
    }

    // ==================== 2. 预览（只读）====================

    @Override
    public Preview forTool(String toolName, String argumentsJson) {
        if (!"batch_inbound".equals(toolName)) {
            return Preview.NONE;
        }
        Plan plan = parse(field(argumentsJson, "items"));
        if (!plan.ok()) {
            // 预览失败不报错弹窗，让确认框照常显示参数框即可 —— 真正的报错在执行时由工具如实返回
            return Preview.NONE;
        }
        return preview(plan, field(argumentsJson, "warehouseCode"));
    }

    /**
     * 生成确认框里的明细表。只读：解析出每个商品是「新建」还是「已有」，已有的话带上现有库存。
     */
    public Preview preview(Plan plan, String warehouseCode) {
        if (!plan.ok()) {
            return Preview.NONE;
        }
        Map<String, Object> warehouse = resolveWarehouse(warehouseCode);
        Map<String, Map<String, Object>> existing = lookupExisting(plan.lines(),
                warehouse == null ? null : ((Number) warehouse.get("id")).longValue());

        List<Map<String, Object>> rows = new ArrayList<>(plan.lines().size());
        int newCount = 0;
        long totalQty = 0;
        for (Line line : plan.lines()) {
            Map<String, Object> old = existing.get(itemKey(line.name(), line.spec()));
            boolean isNew = (old == null);
            if (isNew) {
                newCount++;
            }
            totalQty += line.quantity();

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("seq", line.seq());
            r.put("name", line.name());
            r.put("spec", line.spec() == null ? "—" : line.spec());
            r.put("category", line.category() == null ? "—" : line.category());
            r.put("unit", line.unit() == null ? "—" : line.unit());
            r.put("quantity", line.quantity());
            r.put("itemState", isNew ? "新建商品" : "已有商品");
            r.put("itemStateTone", isNew ? "new" : "exist");
            // 新建商品的 SKU 在执行那一刻才生成（预览阶段占号会因为用户拒绝而白白烧掉序号）
            r.put("sku", isNew ? "（执行时生成）" : String.valueOf(old.get("sku")));
            r.put("stockHint", isNew ? "—"
                    : ((Number) old.get("qty")).intValue() + " → "
                      + (((Number) old.get("qty")).intValue() + line.quantity()));
            if (line.mergedFrom() > 1) {
                r.put("note", "清单中 " + line.mergedFrom() + " 行已合并");
            }
            rows.add(r);
        }

        // 名称/规格/类别允许换行（长名字折行比横向滚动好读），
        // 其余列（数量、库存变化、SKU）必须不换行 —— 折成三行的编码根本没法核对。
        List<Column> columns = List.of(
                Column.num("seq", "#"),
                Column.wide("name", "商品名称"),
                Column.wide("spec", "规格"),
                Column.wide("category", "类别"),
                Column.of("unit", "单位"),
                Column.num("quantity", "入库数量"),
                Column.num("stockHint", "库存变化"),
                Column.of("itemState", "商品"),
                Column.of("sku", "SKU"));

        String whLabel = warehouse == null
                ? "⚠ 未找到可用仓库，执行时会失败"
                : warehouse.get("name") + "(" + warehouse.get("code") + ")";
        String summary = "共 " + rows.size() + " 个商品（新建 " + newCount + " 个 / 已有 "
                + (rows.size() - newCount) + " 个），合计入库 " + totalQty + " 件 → 仓库：" + whLabel;

        String hint = "请逐行核对下面的明细，确认无误后点「批准执行」——整批一次写入，只会问这一次。"
                + "新建商品的 SKU 由系统在执行时自动生成（" + SkuGenerator.formatHint()
                + "，同一秒内多个商品靠序号区分）。";

        return Preview.of("本次入库明细", hint, summary, columns, rows);
    }

    // ==================== 3. 执行（一个事务）====================

    /**
     * 整批一个事务：要么 100 个商品全部入库，要么一个都不动。
     * 中途任何一行失败都整体回滚，不会留下"一半商品建了、库存没加"这种烂账。
     */
    public Outcome execute(Plan plan, String warehouseCode, String supplier, String operator) {
        if (!plan.ok()) {
            return new Outcome(null, null, null, null, null, plan.error());
        }
        Map<String, Object> warehouse = resolveWarehouse(warehouseCode);
        if (warehouse == null) {
            return new Outcome(null, null, null, null, null,
                    "未找到可用仓库" + (warehouseCode == null || warehouseCode.isBlank() ? "" : "「" + warehouseCode + "」")
                            + "，请先用 add_warehouse 创建仓库。");
        }
        final Long whId = ((Number) warehouse.get("id")).longValue();
        final String batchNo = nextBatchNo();
        final String supplierF = Args.clean(supplier);
        final String operatorF = Args.clean(operator);
        final List<ItemOutcome> outcomes = new ArrayList<>(plan.lines().size());
        final List<String> warnings = new ArrayList<>();

        try {
            tx.executeWithoutResult(status -> {
                int idx = 0;
                for (Line line : plan.lines()) {
                    idx++;
                    Map<String, Object> item = findItem(line.name(), line.spec());
                    boolean created = false;
                    String sku;
                    if (item == null) {
                        // 商品不存在 → 自动建档并自动发 SKU（用户要求：SKU 一律系统生成）
                        sku = skuGenerator.next();
                        Long newId = insertReturningId(
                                "INSERT INTO wms_item(sku, name, category, unit, spec) VALUES(?,?,?,?,?)",
                                sku, line.name(), line.category(), line.unit(), line.spec());
                        if (newId == null) {
                            throw new IllegalStateException("商品「" + line.name() + "」建档后未能取到 ID");
                        }
                        item = jdbc.queryForMap(
                                "SELECT id, sku, name, unit, spec FROM wms_item WHERE id = ?", newId);
                        created = true;
                    } else {
                        sku = String.valueOf(item.get("sku"));
                        // 清单与库内对同一商品的描述不一致时，以库内为准并提醒，避免把库里的档案悄悄改掉
                        String diff = describeDiff(line, item);
                        if (diff != null) {
                            warnings.add("「" + line.name() + "」" + diff);
                        }
                    }
                    Long itemId = ((Number) item.get("id")).longValue();

                    int before = currentQty(whId, itemId);
                    int after = before + line.quantity();
                    upsertStock(whId, itemId, after, line.safetyStock());

                    String orderNo = batchNo + "-" + String.format("%03d", idx);
                    jdbc.update("""
                            INSERT INTO wms_order(order_no, order_type, warehouse_id, item_id, quantity,
                                                  party, operator, status, remark)
                            VALUES(?, '入库', ?, ?, ?, ?, ?, '已完成', ?)
                            """, orderNo, whId, itemId, line.quantity(), supplierF, operatorF,
                            "批量入库 " + batchNo);
                    jdbc.update("""
                            INSERT INTO wms_stock_log(warehouse_id, item_id, change_type, quantity,
                                                      before_qty, after_qty, ref_no, operator)
                            VALUES(?, ?, '入库', ?, ?, ?, ?, ?)
                            """, whId, itemId, line.quantity(), before, after, orderNo, operatorF);
                    // 统一资产模型：批量入库也同步生成 wms_asset 记录
                    generateAssetsForInbound(whId, itemId, line.quantity(), orderNo);

                    outcomes.add(new ItemOutcome(sku, line.name(), created, line.quantity(),
                            before, after, orderNo));
                }
            });
        } catch (DataAccessException | IllegalStateException e) {
            return new Outcome(null, null, null, null, null,
                    "批量入库失败，本次操作已整体回滚（" + plan.lines().size()
                            + " 个商品全部未写入，库存/单据/流水均无变化）。原因：" + rootMessage(e)
                            + "。请修正后重试。");
        }

        return new Outcome(batchNo, String.valueOf(warehouse.get("code")),
                String.valueOf(warehouse.get("name")), outcomes, warnings, null);
    }

    // ==================== 内部工具方法 ====================

    /** 批量入库的仓库：指定了就用指定的，否则默认第一个启用的仓库（批量场景下不要逐件"智能选仓"，否则同一批货散到多个仓） */
    private Map<String, Object> resolveWarehouse(String warehouseCode) {
        List<Map<String, Object>> rows;
        if (warehouseCode == null || warehouseCode.isBlank()) {
            rows = jdbc.queryForList(
                    "SELECT id, code, name FROM wms_warehouse WHERE status = '启用' ORDER BY id LIMIT 1");
        } else {
            rows = jdbc.queryForList(
                    "SELECT id, code, name FROM wms_warehouse WHERE code = ? OR name = ? ORDER BY id LIMIT 1",
                    warehouseCode.trim(), warehouseCode.trim());
        }
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String itemKey(String name, String spec) {
        return name + "\u0000" + (spec == null ? "" : spec);
    }

    private Map<String, Object> findItem(String name, String spec) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, sku, name, category, unit, spec FROM wms_item
                WHERE name = ? AND COALESCE(spec, '') = ? ORDER BY id LIMIT 1
                """, name, spec == null ? "" : spec);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 一次性把清单涉及的商品查出来（按名称 IN 查询，再在内存里按 名称+规格 精确匹配），避免逐行查库 */
    private Map<String, Map<String, Object>> lookupExisting(List<Line> lines, Long warehouseId) {
        Set<String> names = new LinkedHashSet<>();
        for (Line l : lines) {
            names.add(l.name());
        }
        String holders = String.join(",", Collections.nCopies(names.size(), "?"));
        String sql = """
                SELECT i.id, i.sku, i.name, i.category, i.unit, i.spec,
                       COALESCE(s.quantity, 0) AS qty
                FROM wms_item i
                LEFT JOIN wms_stock s ON s.item_id = i.id AND s.warehouse_id = ?
                WHERE i.name IN (""" + holders + ")";
        List<Object> params = new ArrayList<>();
        params.add(warehouseId == null ? -1L : warehouseId);
        params.addAll(names);

        Map<String, Map<String, Object>> map = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, params.toArray())) {
            String spec = r.get("spec") == null ? "" : String.valueOf(r.get("spec"));
            map.put(itemKey(String.valueOf(r.get("name")), spec), r);
        }
        return map;
    }

    /** 清单与库内对同一商品的描述是否不一致；不一致时返回一句提醒（以库内为准） */
    private static String describeDiff(Line line, Map<String, Object> item) {
        List<String> parts = new ArrayList<>();
        String dbUnit = item.get("unit") == null ? null : String.valueOf(item.get("unit"));
        String dbCategory = item.get("category") == null ? null : String.valueOf(item.get("category"));
        if (line.unit() != null && dbUnit != null && !line.unit().equals(dbUnit)) {
            parts.add("清单单位「" + line.unit() + "」与库内「" + dbUnit + "」不一致");
        }
        if (line.category() != null && dbCategory != null && !line.category().equals(dbCategory)) {
            parts.add("清单类别「" + line.category() + "」与库内「" + dbCategory + "」不一致");
        }
        return parts.isEmpty() ? null : String.join("，", parts) + "，本次以库内档案为准（未改动商品主数据）";
    }

    private int currentQty(Long warehouseId, Long itemId) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT quantity FROM wms_stock WHERE warehouse_id = ? AND item_id = ?",
                Integer.class, warehouseId, itemId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private void upsertStock(Long warehouseId, Long itemId, int quantity, Integer safetyStock) {
        int updated = jdbc.update(
                "UPDATE wms_stock SET quantity = ? WHERE warehouse_id = ? AND item_id = ?",
                quantity, warehouseId, itemId);
        if (updated == 0) {
            jdbc.update("INSERT INTO wms_stock(warehouse_id, item_id, quantity, safety_stock) VALUES(?,?,?,?)",
                    warehouseId, itemId, quantity, safetyStock == null ? 0 : safetyStock);
        } else if (safetyStock != null) {
            jdbc.update("UPDATE wms_stock SET safety_stock = ? WHERE warehouse_id = ? AND item_id = ?",
                    safetyStock, warehouseId, itemId);
        }
    }

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

    /**
     * 生成批次号：{@code IN-<时间戳到秒>-<3位随机>}，各行单号 = 批次号 + "-" + 三位行号。
     * 单号在 wms_order 上有唯一索引，所以批次号必须先确认库里没占用过（用 LIKE 前缀查一次即可）。
     */
    private String nextBatchNo() {
        for (int i = 0; i < 5; i++) {
            String p = "IN-" + LocalDateTime.now().format(TS_FMT) + "-"
                    + ThreadLocalRandom.current().nextInt(100, 999);
            Integer dup = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM wms_order WHERE order_no = ? OR order_no LIKE ?",
                    Integer.class, p, p + "-%");
            if (dup == null || dup == 0) {
                return p;
            }
        }
        return "IN-" + LocalDateTime.now().format(TS_FMT) + "-" + (System.nanoTime() % 10000);
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
    }

    /** 去掉模型爱加的 ```json 围栏 */
    private static String stripFence(String s) {
        String v = s.trim();
        if (v.startsWith("```")) {
            int firstLine = v.indexOf('\n');
            if (firstLine < 0) {
                return v.replace("`", "").trim();
            }
            v = v.substring(firstLine + 1);
            int end = v.lastIndexOf("```");
            if (end >= 0) {
                v = v.substring(0, end);
            }
        }
        return v.trim();
    }

    /** 从 JSON 里取字符串，支持多个候选字段名（中英文都认） */
    private static String text(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v != null && !v.isNull() && !v.asText().isBlank()) {
                return v.asText().trim();
            }
        }
        return null;
    }

    /** 从 JSON 里取整数，容忍 "30"、"30件"、30.0 这类写法 */
    private static Integer intOf(JsonNode node, String... keys) {
        for (String k : keys) {
            JsonNode v = node.get(k);
            if (v == null || v.isNull()) {
                continue;
            }
            if (v.isNumber()) {
                return v.asInt();
            }
            String s = v.asText().trim();
            if (s.isEmpty()) {
                continue;
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("-?\\d+").matcher(s);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group());
                } catch (NumberFormatException ignore) {
                    // 数字太大，交给上层按"数量不合法"报错
                    return Integer.MAX_VALUE;
                }
            }
        }
        return null;
    }

    /** 从参数 JSON 里取一个顶层字符串字段 */
    private static String field(String argumentsJson, String key) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode n = JSON.readTree(argumentsJson).get(key);
            return (n == null || n.isNull()) ? null : n.asText();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 为入库的商品生成一物一码的资产记录（wms_asset）。
     * 统一资产模型：所有入库的物品都必须有资产身份，确保可盘点、可追溯。
     */
    private void generateAssetsForInbound(Long warehouseId, Long itemId, int quantity, String refOrderNo) {
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "AST-" + dateStr + "-";
        Integer maxSeq = jdbc.queryForObject(
                "SELECT COALESCE(MAX(CAST(SUBSTRING(asset_no, ?) AS UNSIGNED)), 0) FROM wms_asset WHERE asset_no LIKE ?",
                Integer.class, prefix.length() + 1, prefix + "%");
        int startSeq = (maxSeq == null ? 0 : maxSeq) + 1;

        for (int i = 0; i < quantity; i++) {
            String assetNo = prefix + String.format("%04d", startSeq + i);
            jdbc.update("""
                    INSERT INTO wms_asset(asset_no, item_id, warehouse_id, status, created_at)
                    VALUES(?, ?, ?, '在库', NOW())
                    """, assetNo, itemId, warehouseId);
            jdbc.update("""
                    INSERT INTO wms_asset_log(asset_no, action, to_status, remark, created_at)
                    VALUES(?, '入库', '在库', ?, NOW())
                    """, assetNo, "入库单 " + refOrderNo);
        }
    }
}
