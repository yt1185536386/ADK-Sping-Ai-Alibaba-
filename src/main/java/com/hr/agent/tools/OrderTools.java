package com.hr.agent.tools;

import com.hr.agent.service.DeliveryService;
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
 * 订单模块工具集：销售订单的创建、查询，以及订单衍生的配送、采购入库、退货入库。
 *
 * 【写工具约定】与 HrTools / WmsTools 一致：写操作返回值必须回显"数据库实际写入的最终值"并附 GROUND_TRUTH_HINT。
 *
 * 库存联动规则：
 * - create_order（下单）：只建订单，不动库存（货可能还没发）；
 * - create_delivery（指定人配送）：拣货出库，扣库存 + 写 wms_order(出库) + 写流水；
 * - create_purchase（指定人采购入库）：加库存 + 写 wms_order(入库) + 写流水；
 * - create_return（订单退货重新入库）：加库存 + 写 wms_order(入库) + 写流水。
 */
@Component
public class OrderTools {

    private static final DateTimeFormatter NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 附在写工具返回值末尾：告知模型人工可能改过参数，且以返回值为准 */
    // NOTE: GROUND_TRUTH_HINT 在 HrTools/OrderTools/WmsTools/AssetService 四处有意重复（内容必须保持一致），
    // 任一修改需同步其余三处；确有变更时再抽取到 util 包统一。
    private static final String GROUND_TRUTH_HINT =
            "\n注：以上为数据库实际写入的最终值。若与你的调用参数或用户原话不一致，说明该值已被人工在确认框中修改，"
                    + "请直接按本返回值汇报，不要再调用写工具把值改回去。";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DeliveryService deliveryService;

    public OrderTools(JdbcTemplate jdbc, TransactionTemplate tx, DeliveryService deliveryService) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.deliveryService = deliveryService;
    }

    private static String nz(Object v, String fallback) {
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
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

    private static String moreHint(int shown, int total, String narrowHint) {
        return total > shown ? "\n（共 " + total + " 条，此处只显示前 " + shown + " 条，可" + narrowHint + "缩小范围）\n" : "";
    }

    // ==================== 内部解析 ====================

    /** 商品解析：强制唯一命中，避免把 A 订单挂到 B 商品上 */
    private record ItemLookup(Map<String, Object> item, String error) {
    }

    private ItemLookup resolveItem(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return new ItemLookup(null, "请提供商品名称或 SKU。");
        }
        String k = keyword.trim();
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, sku, name, unit FROM wms_item WHERE sku = ? OR name LIKE ? ORDER BY id LIMIT 6",
                k, "%" + k + "%");
        if (rows.isEmpty()) {
            return new ItemLookup(null, "未找到商品「" + k + "」，请先在仓库模块建档，或确认商品名称。");
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

    /** 员工解析：ID 或姓名，强制唯一命中（配送/采购必须指到具体的人） */
    private record EmpLookup(Long id, String name, String error) {
    }

    private EmpLookup resolveEmployee(String employee) {
        if (employee == null || employee.isBlank()) {
            return new EmpLookup(null, null, "请提供员工姓名或员工ID。");
        }
        String k = employee.trim();
        List<Map<String, Object>> rows;
        if (k.matches("\\d+")) {
            rows = jdbc.queryForList("SELECT id, name, status FROM employee WHERE id = ?", Long.parseLong(k));
        } else {
            rows = jdbc.queryForList(
                    "SELECT id, name, status FROM employee WHERE name LIKE ? ORDER BY id LIMIT 6", "%" + k + "%");
        }
        if (rows.isEmpty()) {
            return new EmpLookup(null, null, "未找到员工「" + k + "」，请先在人力模块确认员工信息。");
        }
        if (rows.size() > 1) {
            StringBuilder sb = new StringBuilder("「" + k + "」匹配到多位员工，请用员工ID 指定：\n");
            for (Map<String, Object> r : rows) {
                sb.append("- [ID=").append(r.get("id")).append("] ").append(r.get("name"))
                        .append("（").append(r.get("status")).append("）\n");
            }
            return new EmpLookup(null, null, sb.toString());
        }
        Map<String, Object> e = rows.get(0);
        if (!"在职".equals(String.valueOf(e.get("status")))) {
            return new EmpLookup(null, null,
                    "员工「" + e.get("name") + "」[ID=" + e.get("id") + "] 已离职，不能指派任务。");
        }
        return new EmpLookup(((Number) e.get("id")).longValue(), String.valueOf(e.get("name")), null);
    }

    /** 订单解析：按订单号精确找 */
    private Map<String, Object> findOrder(String orderNo) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT o.id, o.order_no, o.customer, o.item_id, o.quantity, o.status,
                       i.name AS item_name, i.sku, i.unit
                FROM biz_order o JOIN wms_item i ON i.id = o.item_id
                WHERE o.order_no = ?
                """, orderNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 生成唯一单号：前缀 + 时间戳 + 随机数 */
    private String nextNo(String prefix, String table, String column) {
        for (int i = 0; i < 5; i++) {
            String no = prefix + LocalDateTime.now().format(NO_FMT)
                    + ThreadLocalRandom.current().nextInt(100, 999);
            Integer dup = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, no);
            if (dup == null || dup == 0) {
                return no;
            }
        }
        return prefix + LocalDateTime.now().format(NO_FMT)
                + ThreadLocalRandom.current().nextInt(10000, 99999);
    }

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

    private int currentQty(Long warehouseId, Long itemId) {
        List<Integer> rows = jdbc.queryForList(
                "SELECT quantity FROM wms_stock WHERE warehouse_id = ? AND item_id = ?",
                Integer.class, warehouseId, itemId);
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

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
    }

    // ==================== 下单 ====================

    @Tool(name = "create_order", description = "创建销售订单：客户向公司下单购买商品。只建订单不动库存，发货时再走配送出库")
    public String createOrder(
            @ToolParam(description = "客户名称") String customer,
            @ToolParam(description = "商品名称或 SKU") String itemKeyword,
            @ToolParam(description = "购买数量") Integer quantity,
            @ToolParam(description = "单价（元），可为空") Double unitPrice,
            @ToolParam(description = "备注，可为空") String remark) {
        String bad = Args.name(customer, "客户名称");
        if (bad == null) {
            bad = Args.quantity(quantity, "购买数量");
        }
        if (bad == null && unitPrice != null && unitPrice < 0) {
            bad = "单价不能为负数。";
        }
        if (bad != null) {
            return "无法创建订单：" + bad;
        }
        ItemLookup lookup = resolveItem(itemKeyword);
        if (lookup.error() != null) {
            return lookup.error();
        }
        Map<String, Object> item = lookup.item();
        Long itemId = ((Number) item.get("id")).longValue();
        remark = Args.clean(remark);

        Double total = (unitPrice == null) ? null : unitPrice * quantity;
        String orderNo = nextNo("SO", "biz_order", "order_no");
        Long newId = insertReturningId("""
                INSERT INTO biz_order(order_no, customer, item_id, quantity, unit_price, total_amount, status, remark)
                VALUES(?,?,?,?,?,?,'待处理',?)
                """, orderNo, customer.trim(), itemId, quantity, unitPrice, total, remark);
        if (newId == null) {
            return "创建订单失败：未能获取订单ID，请重试。";
        }
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT o.id, o.order_no, o.customer, o.quantity, o.unit_price, o.total_amount, o.status, o.remark, o.created_at,
                       i.name AS item_name, i.sku
                FROM biz_order o JOIN wms_item i ON i.id = o.item_id WHERE o.id = ?
                """, newId);
        return "已创建销售订单（以下为数据库实际写入的最终值，以此为准）：\n"
                + "- 订单号：" + row.get("order_no") + "\n"
                + "- 客户：" + row.get("customer") + "\n"
                + "- 商品：" + row.get("item_name") + "[" + row.get("sku") + "]\n"
                + "- 数量：" + row.get("quantity") + "\n"
                + "- 单价：" + (row.get("unit_price") == null ? "未填写" : row.get("unit_price") + " 元") + "\n"
                + "- 总金额：" + (row.get("total_amount") == null ? "未计算" : row.get("total_amount") + " 元") + "\n"
                + "- 状态：" + row.get("status") + "\n"
                + "- 备注：" + nz(row.get("remark"), "无")
                + GROUND_TRUTH_HINT;
    }

    @Tool(name = "list_orders", description = "查询销售订单列表，可按状态过滤（待处理/已发货/已退货/已取消/全部），也可按客户名模糊搜索")
    public String listOrders(
            @ToolParam(description = "状态过滤：待处理/已发货/已退货/已取消/全部，可为空查全部") String status,
            @ToolParam(description = "客户名关键字，可为空") String customerKeyword,
            @ToolParam(description = "返回条数，默认 20") Integer limit) {
        int n = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        StringBuilder sql = new StringBuilder("""
                SELECT o.id, o.order_no, o.customer, o.quantity, o.total_amount, o.status, o.created_at,
                       i.name AS item_name, i.sku,
                       (SELECT d.status FROM biz_delivery d WHERE d.order_id = o.id ORDER BY d.id DESC LIMIT 1) AS delivery_status,
                       (SELECT d.employee_name FROM biz_delivery d WHERE d.order_id = o.id ORDER BY d.id DESC LIMIT 1) AS delivery_emp
                FROM biz_order o JOIN wms_item i ON i.id = o.item_id WHERE 1=1
                """);
        java.util.List<Object> args = new java.util.ArrayList<>();
        String st = Args.clean(status);
        if (st != null && !"全部".equals(st)) {
            sql.append(" AND o.status = ?");
            args.add(st);
        }
        String ck = Args.clean(customerKeyword);
        if (ck != null) {
            sql.append(" AND o.customer LIKE ?");
            args.add("%" + ck + "%");
        }
        sql.append(" ORDER BY o.id DESC LIMIT ?");
        args.add(n);
        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), args.toArray());
        if (rows.isEmpty()) {
            return "没有查询到符合条件的订单。";
        }
        StringBuilder sb = new StringBuilder("订单列表（" + rows.size() + " 条）：\n");
        for (Map<String, Object> r : rows) {
            sb.append("- [").append(r.get("order_no")).append("] ").append(r.get("customer"))
                    .append(" 购买 ").append(r.get("item_name")).append("[").append(r.get("sku")).append("] x")
                    .append(r.get("quantity"))
                    .append(r.get("total_amount") == null ? "" : "，金额 " + r.get("total_amount") + " 元")
                    .append(" | 订单状态：").append(r.get("status"))
                    .append(r.get("delivery_status") == null ? "" :
                            " | 配送：" + r.get("delivery_status")
                                    + (r.get("delivery_emp") == null ? "" : "（" + r.get("delivery_emp") + "）"))
                    .append(" | ").append(r.get("created_at"))
                    .append("\n");
        }
        return sb.toString();
    }

    // ==================== 配送 ====================

    // biz_delivery 的唯一写入入口是 DeliveryService；本方法只做参数解析与文案组装
    @Tool(name = "create_delivery", description = "给订单指定员工配送货物：生成配送单，同时从仓库拣货出库（扣库存、写出库单与流水），订单状态改为已发货")
    public String createDelivery(
            @ToolParam(description = "订单号") String orderNo,
            @ToolParam(description = "配送员工姓名或员工ID") String employee,
            @ToolParam(description = "仓库编码，可为空（自动选库存充足的仓库）") String warehouseCode,
            @ToolParam(description = "配送地址，可为空") String address,
            @ToolParam(description = "备注，可为空") String remark,
            @ToolParam(description = "配送截止时间，格式 yyyy-MM-dd HH:mm:ss，可为空") String deadline,
            @ToolParam(description = "客户联系方式（手机号），可为空") String customerPhone) {
        String bad = Args.required(orderNo, "订单号");
        if (bad != null) {
            return bad;
        }
        Map<String, Object> order = findOrder(orderNo.trim());
        if (order == null) {
            return "未找到订单「" + orderNo + "」，请用 list_orders 确认订单号。";
        }
        if ("已取消".equals(String.valueOf(order.get("status")))) {
            return "订单「" + orderNo + "」已取消，不能配送。";
        }
        if ("已发货".equals(String.valueOf(order.get("status")))) {
            return "订单「" + orderNo + "」已经是已发货状态，请勿重复配送。";
        }
        EmpLookup emp = resolveEmployee(employee);
        if (emp.error() != null) {
            return emp.error();
        }
        Long orderId = ((Number) order.get("id")).longValue();
        int qty = ((Number) order.get("quantity")).intValue();
        LocalDateTime deadlineAt = parseDateTime(deadline);

        DeliveryService.ImmediateResult result;
        try {
            result = deliveryService.createImmediateDelivery(orderId, emp.id(), emp.name(),
                    Args.clean(warehouseCode), Args.clean(address), Args.clean(remark),
                    deadlineAt, Args.clean(customerPhone));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return e.getMessage() + "。";
        }

        return "已创建配送单并完成拣货出库（以下为数据库实际写入的最终值，以此为准）：\n"
                + "- 配送单号：" + result.deliveryNo() + "\n"
                + "- 订单号：" + orderNo + "（客户：" + order.get("customer") + "）\n"
                + "- 商品：" + order.get("item_name") + "[" + order.get("sku") + "] x" + qty + "\n"
                + "- 配送员工：" + emp.name() + "[ID=" + emp.id() + "]\n"
                + "- 出货仓库：" + result.warehouseName() + "(" + result.warehouseCode() + ")"
                + "（库存 " + result.stockBefore() + " → " + result.stockAfter() + "，出库单 " + result.outboundNo() + "）\n"
                + "- 配送地址：" + nz(address, "未填写") + "\n"
                + "- 客户联系方式：" + nz(customerPhone, "未填写") + "\n"
                + "- 截止时间：" + (deadlineAt == null ? "未设置" : deadlineAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))) + "\n"
                + "- 配送状态：配送中\n"
                + "- 订单状态：已发货"
                + GROUND_TRUTH_HINT;
    }

    /** 宽松解析模型/表单传入的时间：支持 "yyyy-MM-dd HH:mm:ss" / "yyyy-MM-dd HH:mm" / ISO 本地时间 */
    private static LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.trim().replace('T', ' ');
        for (String p : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"}) {
            try {
                return p.length() > 10
                        ? LocalDateTime.parse(v, DateTimeFormatter.ofPattern(p))
                        : LocalDateTime.parse(v + " 23:59:59", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    @Tool(name = "list_deliveries", description = "查询配送单列表，可按状态过滤（待配送/配送中/已送达/已取消/全部）")
    public String listDeliveries(
            @ToolParam(description = "状态过滤，可为空查全部") String status,
            @ToolParam(description = "返回条数，默认 20") Integer limit) {
        int n = (limit == null || limit <= 0) ? 20 : Math.min(limit, 100);
        String sql = """
                SELECT d.delivery_no, d.status, d.employee_name, d.address, d.created_at, d.delivered_at,
                       o.order_no, o.customer, i.name AS item_name, o.quantity
                FROM biz_delivery d
                JOIN biz_order o ON o.id = d.order_id
                JOIN wms_item i ON i.id = o.item_id
                """;
        List<Map<String, Object>> rows;
        String st = Args.clean(status);
        if (st == null || "全部".equals(st)) {
            rows = jdbc.queryForList(sql + "ORDER BY d.id DESC LIMIT ?", n);
        } else {
            rows = jdbc.queryForList(sql + "WHERE d.status = ? ORDER BY d.id DESC LIMIT ?", st, n);
        }
        if (rows.isEmpty()) {
            return "没有查询到符合条件的配送单。";
        }
        StringBuilder sb = new StringBuilder("配送单列表（" + rows.size() + " 条）：\n");
        for (Map<String, Object> r : rows) {
            sb.append("- [").append(r.get("delivery_no")).append("] 订单 ").append(r.get("order_no"))
                    .append("（").append(r.get("customer")).append("）")
                    .append(" ").append(r.get("item_name")).append(" x").append(r.get("quantity"))
                    .append(" | 配送员：").append(nz(r.get("employee_name"), "未指派"))
                    .append(" | 状态：").append(r.get("status"))
                    .append(r.get("address") == null ? "" : " | 地址：" + r.get("address"))
                    .append("\n");
        }
        return sb.toString();
    }

    // ==================== 采购入库 ====================

    @Tool(name = "create_purchase", description = "指定员工采购商品并入库：生成采购单，同时把采购的商品入库（加库存、写入库单与流水）。可关联到某个订单（为该订单补货），也可独立采购")
    public String createPurchase(
            @ToolParam(description = "商品名称或 SKU") String itemKeyword,
            @ToolParam(description = "采购数量") Integer quantity,
            @ToolParam(description = "采购员工姓名或员工ID") String employee,
            @ToolParam(description = "关联的订单号，可为空（独立采购）") String orderNo,
            @ToolParam(description = "供应商，可为空") String supplier,
            @ToolParam(description = "入库仓库编码，可为空（默认第一个启用仓库）") String warehouseCode,
            @ToolParam(description = "备注，可为空") String remark) {
        String bad = Args.quantity(quantity, "采购数量");
        if (bad != null) {
            return "无法采购入库：" + bad;
        }
        ItemLookup lookup = resolveItem(itemKeyword);
        if (lookup.error() != null) {
            return lookup.error();
        }
        Map<String, Object> item = lookup.item();
        Long itemId = ((Number) item.get("id")).longValue();
        EmpLookup emp = resolveEmployee(employee);
        if (emp.error() != null) {
            return emp.error();
        }
        Long orderId = null;
        if (orderNo != null && !orderNo.isBlank()) {
            Map<String, Object> order = findOrder(orderNo.trim());
            if (order == null) {
                return "未找到订单「" + orderNo + "」，请用 list_orders 确认订单号；如需独立采购，可不填订单号。";
            }
            orderId = ((Number) order.get("id")).longValue();
        }
        Map<String, Object> warehouse = resolveWarehouse(warehouseCode);
        if (warehouse == null) {
            return "未找到可用仓库，请先在仓库模块创建仓库。";
        }
        final Long whId = ((Number) warehouse.get("id")).longValue();
        supplier = Args.clean(supplier);
        remark = Args.clean(remark);

        final Long orderIdF = orderId;
        final String purchaseNo = nextNo("PO", "biz_purchase", "purchase_no");
        final String inNo = nextNo("IN", "wms_order", "order_no");
        final String supplierF = supplier;
        final String remarkF = remark;
        int[] qtyArr = new int[2];
        try {
            tx.executeWithoutResult(status -> {
                qtyArr[0] = currentQty(whId, itemId);
                qtyArr[1] = qtyArr[0] + quantity;
                upsertStock(whId, itemId, qtyArr[1]);
                jdbc.update("""
                        INSERT INTO wms_order(order_no, order_type, warehouse_id, item_id, quantity, party, operator, status, remark)
                        VALUES(?, '入库', ?, ?, ?, ?, ?, '已完成', ?)
                        """, inNo, whId, itemId, quantity, supplierF, emp.name(),
                        "采购入库 " + purchaseNo);
                jdbc.update("""
                        INSERT INTO wms_stock_log(warehouse_id, item_id, change_type, quantity, before_qty, after_qty, ref_no, operator)
                        VALUES(?, ?, '入库', ?, ?, ?, ?, ?)
                        """, whId, itemId, quantity, qtyArr[0], qtyArr[1], inNo, emp.name());
                jdbc.update("""
                        INSERT INTO biz_purchase(purchase_no, order_id, item_id, quantity, warehouse_id, employee_id, employee_name, supplier, status, remark)
                        VALUES(?,?,?,?,?,?,?,?, '已入库', ?)
                        """, purchaseNo, orderIdF, itemId, quantity, whId, emp.id(), emp.name(), supplierF, remarkF);
            });
        } catch (Exception e) {
            return "采购入库失败，本次操作已整体回滚（库存/单据均未变更）。原因：" + rootMessage(e) + "。请重试。";
        }

        return "采购入库完成（以下为数据库实际写入的最终值，以此为准）：\n"
                + "- 采购单号：" + purchaseNo + "\n"
                + "- 商品：" + item.get("name") + "[" + item.get("sku") + "] x" + quantity + "\n"
                + "- 采购员工：" + emp.name() + "[ID=" + emp.id() + "]\n"
                + "- 入库仓库：" + warehouse.get("name") + "(" + warehouse.get("code") + ")"
                + "（库存 " + qtyArr[0] + " → " + qtyArr[1] + "，入库单 " + inNo + "）\n"
                + "- 供应商：" + nz(supplierF, "未填写") + "\n"
                + "- 关联订单：" + (orderIdF == null ? "无（独立采购）" : orderNo.trim()) + "\n"
                + "- 状态：已入库"
                + GROUND_TRUTH_HINT;
    }

    // ==================== 退货入库 ====================

    @Tool(name = "create_return", description = "订单退货重新入库：客户把已发货的商品退回，生成退货单并把商品入库（加库存、写入库单与流水），订单状态改为已退货")
    public String createReturn(
            @ToolParam(description = "订单号") String orderNo,
            @ToolParam(description = "退货数量，可为空表示按订单数量全退") Integer quantity,
            @ToolParam(description = "退货原因，可为空") String reason,
            @ToolParam(description = "入库仓库编码，可为空（默认第一个启用仓库）") String warehouseCode,
            @ToolParam(description = "经办人，可为空") String operator) {
        String bad = Args.required(orderNo, "订单号");
        if (bad != null) {
            return bad;
        }
        Map<String, Object> order = findOrder(orderNo.trim());
        if (order == null) {
            return "未找到订单「" + orderNo + "」，请用 list_orders 确认订单号。";
        }
        String orderStatus = String.valueOf(order.get("status"));
        if ("已取消".equals(orderStatus)) {
            return "订单「" + orderNo + "」已取消，不能退货。";
        }
        if ("已退货".equals(orderStatus)) {
            return "订单「" + orderNo + "」已经退过货了，请勿重复退货。";
        }
        if (!"已发货".equals(orderStatus)) {
            return "订单「" + orderNo + "」当前状态为「" + orderStatus + "」，只有已发货的订单才能退货入库。";
        }
        Long orderId = ((Number) order.get("id")).longValue();
        Long itemId = ((Number) order.get("item_id")).longValue();
        int orderQty = ((Number) order.get("quantity")).intValue();
        int qty = (quantity == null) ? orderQty : quantity;
        bad = Args.quantity(qty, "退货数量");
        if (bad != null) {
            return "无法退货入库：" + bad;
        }
        if (qty > orderQty) {
            return "退货数量 " + qty + " 超过订单数量 " + orderQty + "，请核对。";
        }
        Map<String, Object> warehouse = resolveWarehouse(warehouseCode);
        if (warehouse == null) {
            return "未找到可用仓库，请先在仓库模块创建仓库。";
        }
        final Long whId = ((Number) warehouse.get("id")).longValue();
        reason = Args.clean(reason);
        operator = Args.clean(operator);

        final String returnNo = nextNo("RT", "biz_return", "return_no");
        final String inNo = nextNo("IN", "wms_order", "order_no");
        final String reasonF = reason;
        final String operatorF = operator;
        final int qtyF = qty;
        int[] qtyArr = new int[2];
        try {
            tx.executeWithoutResult(status -> {
                qtyArr[0] = currentQty(whId, itemId);
                qtyArr[1] = qtyArr[0] + qtyF;
                upsertStock(whId, itemId, qtyArr[1]);
                jdbc.update("""
                        INSERT INTO wms_order(order_no, order_type, warehouse_id, item_id, quantity, party, operator, status, remark)
                        VALUES(?, '入库', ?, ?, ?, ?, ?, '已完成', ?)
                        """, inNo, whId, itemId, qtyF, order.get("customer"), operatorF,
                        "订单退货 " + orderNo + "（" + returnNo + "）");
                jdbc.update("""
                        INSERT INTO wms_stock_log(warehouse_id, item_id, change_type, quantity, before_qty, after_qty, ref_no, operator)
                        VALUES(?, ?, '入库', ?, ?, ?, ?, ?)
                        """, whId, itemId, qtyF, qtyArr[0], qtyArr[1], inNo, operatorF);
                jdbc.update("""
                        INSERT INTO biz_return(return_no, order_id, item_id, quantity, warehouse_id, reason, status)
                        VALUES(?,?,?,?,?,?, '已入库')
                        """, returnNo, orderId, itemId, qtyF, whId, reasonF);
                // 全退才算订单退货完成；部分退货保持已发货
                if (qtyF >= orderQty) {
                    jdbc.update("UPDATE biz_order SET status = '已退货' WHERE id = ?", orderId);
                }
            });
        } catch (Exception e) {
            return "退货入库失败，本次操作已整体回滚（库存/单据/订单状态均未变更）。原因：" + rootMessage(e) + "。请重试。";
        }

        return "退货入库完成（以下为数据库实际写入的最终值，以此为准）：\n"
                + "- 退货单号：" + returnNo + "\n"
                + "- 订单号：" + orderNo + "（客户：" + order.get("customer") + "）\n"
                + "- 商品：" + order.get("item_name") + "[" + order.get("sku") + "] 退回 " + qtyF
                + "（订单数量 " + orderQty + (qtyF >= orderQty ? "，全退" : "，部分退货") + "）\n"
                + "- 入库仓库：" + warehouse.get("name") + "(" + warehouse.get("code") + ")"
                + "（库存 " + qtyArr[0] + " → " + qtyArr[1] + "，入库单 " + inNo + "）\n"
                + "- 退货原因：" + nz(reasonF, "未填写") + "\n"
                + "- 订单状态：" + (qtyF >= orderQty ? "已退货" : "已发货（部分退货，订单状态不变）")
                + GROUND_TRUTH_HINT;
    }
}
