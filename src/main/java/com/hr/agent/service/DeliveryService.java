package com.hr.agent.service;

import com.hr.agent.service.EmployeeGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 配送单核心服务：biz_delivery 的唯一写入入口。
 *
 * 流程：创建配送单（不动库存） → 确认出库（扣库存+写 wms_order 出库单+写流水+标记资产已出库）
 *      → 配送中（路线规划已生成） → 送达确认（配送员点确认，记录 delivered_at）
 *
 * 关键设计：
 * - {@link #createDelivery}：两阶段第一步，只写 biz_delivery，不扣库存（管理端配送作业台用）
 * - {@link #createImmediateDelivery}：创建+出库一步到位（聊天工具 create_delivery / 配送表单用）
 * - {@link #confirmOutbound}：确认出库时才扣库存、写 wms_order、写 wms_stock_log、标记 wms_asset 为已出库
 * - 事务保证：扣库存+写单据+标记资产在同一事务内，失败整体回滚
 * - 路线规划：创建配送单时异步调用高德 API，结果存 route_json
 *
 * 历史上 biz_delivery 有三条写入路径（本类 / OrderTools#createDelivery / ManageController 配送 CRUD），
 * 语义不一致且 V1 字段不全。现统一收口到本类：OrderTools 与 ManageController 只做参数解析后委托。
 */
@Service
public class DeliveryService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryService.class);
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final EmployeeGuard employeeGuard;
    private final AmapRouteService routeService;

    public DeliveryService(JdbcTemplate jdbc,
                           TransactionTemplate tx,
                           EmployeeGuard employeeGuard,
                           AmapRouteService routeService) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.employeeGuard = employeeGuard;
        this.routeService = routeService;
    }

    // ==================== 创建配送单（不动库存） ====================

    /** 兼容旧签名：不带客户联系方式 */
    public String createDelivery(Long orderId, Long courierUserId, Long warehouseId,
                                 String address, String destination,
                                 BigDecimal fromLng, BigDecimal fromLat,
                                 BigDecimal toLng, BigDecimal toLat,
                                 LocalDateTime deadline, String remark) {
        return createDelivery(orderId, courierUserId, warehouseId, address, destination,
                fromLng, fromLat, toLng, toLat, deadline, remark, null);
    }

    /**
     * 创建配送单：只写 biz_delivery 记录，不扣库存。
     * 同时异步规划路线（如果经纬度已提供）。
     *
     * @return 配送单号
     */
    public String createDelivery(Long orderId, Long courierUserId, Long warehouseId,
                                 String address, String destination,
                                 BigDecimal fromLng, BigDecimal fromLat,
                                 BigDecimal toLng, BigDecimal toLat,
                                 LocalDateTime deadline, String remark, String customerPhone) {
        // 1. 校验订单存在且状态为「待处理」
        Map<String, Object> order = loadOrder(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在 [ID=" + orderId + "]");
        }
        String orderStatus = String.valueOf(order.get("status"));
        if ("已取消".equals(orderStatus)) {
            throw new IllegalStateException("订单已取消，不能创建配送单");
        }
        if ("已发货".equals(orderStatus)) {
            throw new IllegalStateException("订单已发货，不能重复创建配送单");
        }

        // 2. 校验配送员
        if (courierUserId != null) {
            Map<String, Object> courier = loadCourier(courierUserId);
            if (courier == null) {
                throw new IllegalArgumentException("配送员不存在 [ID=" + courierUserId + "]");
            }
            // 校验配送员关联的员工状态
            Object empId = courier.get("employee_id");
            if (empId != null) {
                employeeGuard.requireOnDuty(((Number) empId).longValue(), "创建配送单");
            }
        }

        // 3. 校验仓库
        if (warehouseId != null) {
            Map<String, Object> wh = loadWarehouse(warehouseId);
            if (wh == null) {
                throw new IllegalArgumentException("仓库不存在 [ID=" + warehouseId + "]");
            }
        }

        // 4. 生成配送单号
        String deliveryNo = nextDeliveryNo();

        // 5. 写入配送单（状态：待配送）
        jdbc.update("""
                INSERT INTO biz_delivery(delivery_no, order_id, courier_id, warehouse_id,
                                         employee_name, address, destination, customer_phone,
                                         from_lng, from_lat, to_lng, to_lat,
                                         deadline, status, remark, created_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?, '待配送', ?, NOW())
                """,
                deliveryNo, orderId, courierUserId, warehouseId,
                courierUserId != null ? getCourierName(courierUserId) : null,
                address, destination, customerPhone,
                fromLng, fromLat, toLng, toLat,
                deadline, remark);

        // 6. 异步规划路线（如果经纬度齐全）
        if (fromLng != null && fromLat != null && toLng != null && toLat != null) {
            try {
                String routeJson = routeService.planRoute(
                        fromLng.doubleValue(), fromLat.doubleValue(),
                        toLng.doubleValue(), toLat.doubleValue());
                if (routeJson != null) {
                    jdbc.update("UPDATE biz_delivery SET route_json = ? WHERE delivery_no = ?",
                            routeJson, deliveryNo);
                }
            } catch (Exception e) {
                log.warn("路线规划失败，不影响配送单创建：{}", e.getMessage());
            }
        }

        return deliveryNo;
    }

    // ==================== 立即配送（创建 + 拣货出库一步到位，聊天工具/表单用） ====================

    /** 立即配送的执行结果（供调用方组装回执文案） */
    public record ImmediateResult(String deliveryNo, String outboundNo,
                                  String warehouseName, String warehouseCode,
                                  int stockBefore, int stockAfter) {
    }

    /**
     * 立即配送：选仓 → 同事务内扣库存、写出库单与流水、创建配送单（配送中）、订单置已发货。
     *
     * <p>这是聊天工具（create_delivery）与配送表单的统一入口——历史上 OrderTools 里另有一份
     * 直写 JDBC 的实现（V1），语义一致但字段不全，已收口到本方法。
     *
     * @param warehouseCode 仓库编码；为空时自动选库存充足的仓库（库存最多的优先）
     * @param deadline      配送截止时间；为空由调用方决定默认值（客户端表单默认 5 天后）
     */
    public ImmediateResult createImmediateDelivery(Long orderId, Long employeeId, String employeeName,
                                                   String warehouseCode, String address, String remark,
                                                   LocalDateTime deadline, String customerPhone) {
        Map<String, Object> order = loadOrder(orderId);
        if (order == null) {
            throw new IllegalArgumentException("订单不存在 [ID=" + orderId + "]");
        }
        String orderStatus = String.valueOf(order.get("status"));
        if ("已取消".equals(orderStatus)) {
            throw new IllegalStateException("订单已取消，不能配送");
        }
        if ("已发货".equals(orderStatus)) {
            throw new IllegalStateException("订单已经是已发货状态，请勿重复配送");
        }
        Long itemId = ((Number) order.get("item_id")).longValue();
        int qty = ((Number) order.get("quantity")).intValue();

        // 选仓：指定仓库校验库存；未指定选库存充足且最多的
        List<Map<String, Object>> stocks = jdbc.queryForList("""
                SELECT w.id AS wh_id, w.code AS wh_code, w.name AS wh_name, s.quantity
                FROM wms_stock s JOIN wms_warehouse w ON s.warehouse_id = w.id
                WHERE s.item_id = ? ORDER BY s.quantity DESC
                """, itemId);
        if (stocks.isEmpty()) {
            throw new IllegalStateException("商品当前没有任何库存，无法配送。请先采购或入库");
        }
        Map<String, Object> target = null;
        if (warehouseCode == null || warehouseCode.isBlank()) {
            for (Map<String, Object> s : stocks) {
                if (((Number) s.get("quantity")).intValue() >= qty) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                throw new IllegalStateException("各仓库库存均不足订单数量 " + qty + "，无法配送。可先安排采购入库");
            }
        } else {
            for (Map<String, Object> s : stocks) {
                if (warehouseCode.trim().equalsIgnoreCase(String.valueOf(s.get("wh_code")))) {
                    target = s;
                    break;
                }
            }
            if (target == null) {
                throw new IllegalStateException("仓库「" + warehouseCode + "」没有该商品库存");
            }
            if (((Number) target.get("quantity")).intValue() < qty) {
                throw new IllegalStateException("仓库「" + warehouseCode + "」库存不足：当前 "
                        + target.get("quantity") + "，订单需要 " + qty);
            }
        }

        final Long whId = ((Number) target.get("wh_id")).longValue();
        final String deliveryNo = nextDeliveryNo();
        final String outboundNo = nextOutboundNo();
        final Long courierUserId = findCourierUserId(employeeId);
        final int[] stock = new int[2];
        try {
            tx.executeWithoutResult(txStatus -> {
                // 行锁读库存再扣减，防并发超卖
                List<Integer> now = jdbc.queryForList(
                        "SELECT quantity FROM wms_stock WHERE warehouse_id = ? AND item_id = ? FOR UPDATE",
                        Integer.class, whId, itemId);
                int before = now.isEmpty() ? 0 : now.get(0);
                if (before < qty) {
                    throw new IllegalStateException("库存不足：当前 " + before + "，需要 " + qty);
                }
                stock[0] = before;
                stock[1] = before - qty;
                jdbc.update("UPDATE wms_stock SET quantity = ? WHERE warehouse_id = ? AND item_id = ?",
                        stock[1], whId, itemId);
                jdbc.update("""
                        INSERT INTO wms_order(order_no, order_type, warehouse_id, item_id, quantity, party, operator, status, remark)
                        VALUES(?, '出库', ?, ?, ?, ?, ?, '已完成', ?)
                        """, outboundNo, whId, itemId, qty, order.get("customer"), employeeName,
                        "订单配送 " + order.get("order_no"));
                jdbc.update("""
                        INSERT INTO wms_stock_log(warehouse_id, item_id, change_type, quantity, before_qty, after_qty, ref_no, operator)
                        VALUES(?, ?, '出库', ?, ?, ?, ?, ?)
                        """, whId, itemId, qty, stock[0], stock[1], outboundNo, employeeName);
                // 配送单：写全 V2 字段（发货仓库/出库单号/目的地/配送员账号/截止时间/客户联系方式）
                jdbc.update("""
                        INSERT INTO biz_delivery(delivery_no, order_id, employee_id, employee_name, status,
                                                 address, destination, remark, warehouse_id, courier_id, outbound_no,
                                                 deadline, customer_phone)
                        VALUES(?,?,?,?, '配送中', ?, ?, ?, ?, ?, ?, ?, ?)
                        """, deliveryNo, orderId, employeeId, employeeName,
                        address, address, remark, whId, courierUserId, outboundNo,
                        deadline, customerPhone);
                jdbc.update("UPDATE biz_order SET status = '已发货' WHERE id = ?", orderId);
            });
        } catch (DataAccessException e) {
            throw new IllegalStateException("创建配送单失败，本次操作已整体回滚（库存/单据/订单状态均未变更）。原因："
                    + rootMessage(e), e);
        }
        return new ImmediateResult(deliveryNo, outboundNo,
                String.valueOf(target.get("wh_name")), String.valueOf(target.get("wh_code")),
                stock[0], stock[1]);
    }

    /** 员工 → 配送员账号（sys_user.id）：员工没绑配送账号时返回 null，不阻断配送单创建 */
    public Long findCourierUserId(Long employeeId) {
        if (employeeId == null) {
            return null;
        }
        List<Long> ids = jdbc.queryForList(
                "SELECT id FROM sys_user WHERE employee_id = ? AND role = 'COURIER' AND enabled = 1 LIMIT 1",
                Long.class, employeeId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ==================== 确认出库（扣库存 + 写单据 + 标记资产） ====================

    /**
     * 确认出库：扣库存 + 写 wms_order(出库) + 写 wms_stock_log + 标记 wms_asset 为已出库，
     * 配送单状态改为「配送中」，记录 confirmed_at。
     *
     * 事务内执行，失败整体回滚。
     *
     * @return 出库单号
     */
    public String confirmOutbound(String deliveryNo) {
        Map<String, Object> delivery = loadDelivery(deliveryNo);
        if (delivery == null) {
            throw new IllegalArgumentException("配送单不存在：" + deliveryNo);
        }
        String status = String.valueOf(delivery.get("status"));
        if (!"待配送".equals(status)) {
            throw new IllegalStateException("配送单当前状态是「" + status + "」，只有「待配送」状态才能确认出库");
        }

        Long orderId = ((Number) delivery.get("order_id")).longValue();
        Long warehouseId = delivery.get("warehouse_id") == null ? null
                : ((Number) delivery.get("warehouse_id")).longValue();
        Map<String, Object> order = loadOrder(orderId);
        Long itemId = ((Number) order.get("item_id")).longValue();
        int quantity = ((Number) order.get("quantity")).intValue();

        if (warehouseId == null) {
            throw new IllegalStateException("配送单未指定发货仓库，无法确认出库");
        }

        // 事务内执行
        String outboundNo = nextOutboundNo();
        try {
            tx.executeWithoutResult(txStatus -> {
                // 1. 扣库存（条件更新防超卖）
                int updated = jdbc.update("""
                        UPDATE wms_stock SET quantity = quantity - ?, updated_at = NOW()
                        WHERE warehouse_id = ? AND item_id = ? AND quantity >= ?
                        """, quantity, warehouseId, itemId, quantity);
                if (updated == 0) {
                    throw new IllegalStateException("库存不足，无法出库");
                }
                // 同事务内回读扣减后的库存，推导 before/after 写流水（行锁已持有，无竞态）
                int after = jdbc.queryForObject(
                        "SELECT quantity FROM wms_stock WHERE warehouse_id = ? AND item_id = ?",
                        Integer.class, warehouseId, itemId);
                int before = after + quantity;

                // 2. 写 wms_order(出库单)
                String operator = delivery.get("employee_name") == null ? null : String.valueOf(delivery.get("employee_name"));
                jdbc.update("""
                        INSERT INTO wms_order(order_no, order_type, warehouse_id, item_id, quantity, party, operator, status, remark)
                        VALUES(?, '出库', ?, ?, ?, ?, ?, '已完成', ?)
                        """, outboundNo, warehouseId, itemId, quantity,
                        order.get("customer"), operator,
                        "配送单 " + deliveryNo + " 确认出库");

                // 3. 写 wms_stock_log
                jdbc.update("""
                        INSERT INTO wms_stock_log(warehouse_id, item_id, change_type, quantity, before_qty, after_qty, ref_no, operator)
                        VALUES(?, ?, '出库', ?, ?, ?, ?, ?)
                        """, warehouseId, itemId, quantity, before, after, outboundNo, operator);

                // 4. 标记 wms_asset 为已出库（FIFO）
                markAssetsAsOutbound(warehouseId, itemId, quantity, deliveryNo);

                // 5. 更新配送单状态
                jdbc.update("""
                        UPDATE biz_delivery SET status = '配送中', outbound_no = ?, confirmed_at = NOW()
                        WHERE delivery_no = ?
                        """, outboundNo, deliveryNo);

                // 6. 更新订单状态为「已发货」
                jdbc.update("UPDATE biz_order SET status = '已发货', updated_at = NOW() WHERE id = ?", orderId);
            });
        } catch (DataAccessException e) {
            throw new IllegalStateException("确认出库失败，本次操作已整体回滚。原因：" + rootMessage(e), e);
        }

        return outboundNo;
    }

    // ==================== 取消配送单 ====================

    /** 取消配送单：仅「待配送」状态可取消，不恢复库存（因为还没扣） */
    public void cancelDelivery(String deliveryNo) {
        Map<String, Object> delivery = loadDelivery(deliveryNo);
        if (delivery == null) {
            throw new IllegalArgumentException("配送单不存在：" + deliveryNo);
        }
        String status = String.valueOf(delivery.get("status"));
        if ("已送达".equals(status)) {
            throw new IllegalStateException("配送单已送达，不能取消");
        }
        if ("已取消".equals(status)) {
            throw new IllegalStateException("配送单已是取消状态");
        }
        if ("配送中".equals(status)) {
            throw new IllegalStateException("配送单已出库（配送中），不能取消。如需终止请先联系管理员处理库存");
        }
        jdbc.update("UPDATE biz_delivery SET status = '已取消', updated_at = NOW() WHERE delivery_no = ?",
                deliveryNo);
    }

    // ==================== 配送员送达确认 ====================

    /**
     * 配送员确认送达：更新状态为「已送达」，记录 delivered_at。
     * 只有配送员本人（或管理员）才能确认。
     */
    public void confirmDelivered(String deliveryNo, String currentUsername) {
        Map<String, Object> delivery = loadDelivery(deliveryNo);
        if (delivery == null) {
            throw new IllegalArgumentException("配送单不存在：" + deliveryNo);
        }
        String status = String.valueOf(delivery.get("status"));
        if (!"配送中".equals(status)) {
            throw new IllegalStateException("配送单当前状态是「" + status + "」，只有「配送中」状态才能确认送达");
        }

        // 权限校验：配送员只能确认自己的配送单
        Long courierId = delivery.get("courier_id") == null ? null
                : ((Number) delivery.get("courier_id")).longValue();
        if (courierId != null) {
            Long currentUserId = getCurrentUserId(currentUsername);
            if (currentUserId == null || !currentUserId.equals(courierId)) {
                // 检查是否是管理员
                if (!isAdmin(currentUsername)) {
                    throw new IllegalStateException("只有配送员本人或管理员才能确认送达");
                }
            }
        }

        jdbc.update("""
                UPDATE biz_delivery SET status = '已送达', delivered_at = NOW(), updated_at = NOW()
                WHERE delivery_no = ?
                """, deliveryNo);
    }

    // ==================== 查询 ====================

    /** 管理员视角：查询全部配送单 */
    public List<Map<String, Object>> listAll(String statusFilter, int limit) {
        String base = """
                SELECT d.id, d.delivery_no, d.status, d.address, d.destination, d.deadline, d.customer_phone,
                       d.delivered_at, d.confirmed_at, d.created_at, d.outbound_no,
                       d.from_lng, d.from_lat, d.to_lng, d.to_lat, d.route_json,
                       d.order_id, o.order_no, o.customer, o.quantity,
                       i.sku, i.name AS item_name, i.unit,
                       w.name AS warehouse_name, w.code AS warehouse_code,
                       d.employee_name AS courier_name,
                       e.phone AS courier_phone
                FROM biz_delivery d
                JOIN biz_order o ON o.id = d.order_id
                JOIN wms_item i ON i.id = o.item_id
                LEFT JOIN wms_warehouse w ON w.id = d.warehouse_id
                LEFT JOIN sys_user u ON u.id = d.courier_id
                LEFT JOIN employee e ON e.id = u.employee_id
                """;
        if (statusFilter != null && !statusFilter.isBlank() && !"全部".equals(statusFilter)) {
            return jdbc.queryForList(base + " WHERE d.status = ? ORDER BY d.id DESC LIMIT ?",
                    statusFilter, Math.min(Math.max(limit, 1), 500));
        }
        return jdbc.queryForList(base + " ORDER BY d.id DESC LIMIT ?",
                Math.min(Math.max(limit, 1), 500));
    }

    /** 配送员视角：只查自己的配送单 */
    public List<Map<String, Object>> listByCourier(Long courierUserId, String statusFilter, int limit) {
        String base = """
                SELECT d.id, d.delivery_no, d.status, d.address, d.destination, d.deadline,
                       d.delivered_at, d.confirmed_at, d.created_at, d.outbound_no,
                       d.from_lng, d.from_lat, d.to_lng, d.to_lat, d.route_json,
                       d.order_id, o.order_no, o.customer, o.quantity,
                       i.sku, i.name AS item_name, i.unit,
                       w.name AS warehouse_name, w.code AS warehouse_code,
                       d.employee_name AS courier_name
                FROM biz_delivery d
                JOIN biz_order o ON o.id = d.order_id
                JOIN wms_item i ON i.id = o.item_id
                LEFT JOIN wms_warehouse w ON w.id = d.warehouse_id
                WHERE d.courier_id = ?
                """;
        if (statusFilter != null && !statusFilter.isBlank() && !"全部".equals(statusFilter)) {
            return jdbc.queryForList(base + " AND d.status = ? ORDER BY d.id DESC LIMIT ?",
                    courierUserId, statusFilter, Math.min(Math.max(limit, 1), 500));
        }
        return jdbc.queryForList(base + " ORDER BY d.id DESC LIMIT ?",
                courierUserId, Math.min(Math.max(limit, 1), 500));
    }

    /** 查询配送单详情（含路线） */
    public Map<String, Object> getDetail(String deliveryNo) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT d.*, o.order_no, o.customer, o.quantity,
                       i.sku, i.name AS item_name, i.unit,
                       w.name AS warehouse_name, w.code AS warehouse_code,
                       e.phone AS courier_phone
                FROM biz_delivery d
                JOIN biz_order o ON o.id = d.order_id
                JOIN wms_item i ON i.id = o.item_id
                LEFT JOIN wms_warehouse w ON w.id = d.warehouse_id
                LEFT JOIN sys_user u ON u.id = d.courier_id
                LEFT JOIN employee e ON e.id = u.employee_id
                WHERE d.delivery_no = ?
                """, deliveryNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 检查库存是否充足 */
    public boolean checkStock(Long warehouseId, Long itemId, int quantity) {
        Integer qty = jdbc.queryForObject("""
                SELECT COALESCE(quantity, 0) FROM wms_stock
                WHERE warehouse_id = ? AND item_id = ?
                """, Integer.class, warehouseId, itemId);
        return qty != null && qty >= quantity;
    }

    // ==================== 私有辅助 ====================

    private Map<String, Object> loadOrder(Long orderId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM biz_order WHERE id = ?", orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> loadDelivery(String deliveryNo) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM biz_delivery WHERE delivery_no = ?", deliveryNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> loadCourier(Long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM sys_user WHERE id = ? AND role = 'COURIER' AND enabled = 1", userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> loadWarehouse(Long warehouseId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT * FROM wms_warehouse WHERE id = ?", warehouseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String getCourierName(Long userId) {
        return jdbc.queryForObject("""
                SELECT COALESCE(e.name, u.display_name) FROM sys_user u
                LEFT JOIN employee e ON e.id = u.employee_id
                WHERE u.id = ?
                """, String.class, userId);
    }

    /** 根据用户名查询 sys_user.id */
    public Long getCurrentUserId(String username) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id FROM sys_user WHERE username = ?", username);
        return rows.isEmpty() ? null : ((Number) rows.get(0).get("id")).longValue();
    }

    private boolean isAdmin(String username) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user WHERE username = ? AND role = 'ADMIN'",
                Integer.class, username);
        return count != null && count > 0;
    }

    private String nextDeliveryNo() {
        String prefix = "DLV" + LocalDateTime.now().format(DAY_FMT);
        Integer maxSeq = jdbc.queryForObject("""
                SELECT COALESCE(MAX(CAST(SUBSTRING(delivery_no, ?) AS UNSIGNED)), 0)
                FROM biz_delivery WHERE delivery_no LIKE ?
                """, Integer.class, prefix.length() + 1, prefix + "%");
        return prefix + String.format("%04d", (maxSeq == null ? 0 : maxSeq) + 1);
    }

    private String nextOutboundNo() {
        String prefix = "OUT" + LocalDateTime.now().format(DAY_FMT);
        Integer maxSeq = jdbc.queryForObject("""
                SELECT COALESCE(MAX(CAST(SUBSTRING(order_no, ?) AS UNSIGNED)), 0)
                FROM wms_order WHERE order_no LIKE ?
                """, Integer.class, prefix.length() + 1, prefix + "%");
        return prefix + String.format("%04d", (maxSeq == null ? 0 : maxSeq) + 1);
    }

    private void markAssetsAsOutbound(Long warehouseId, Long itemId, int quantity, String refOrderNo) {
        List<Map<String, Object>> assets = jdbc.queryForList("""
                SELECT id, asset_no FROM wms_asset
                WHERE warehouse_id = ? AND item_id = ? AND status = '在库'
                ORDER BY id ASC LIMIT ?
                """, warehouseId, itemId, quantity);
        for (Map<String, Object> asset : assets) {
            String assetNo = String.valueOf(asset.get("asset_no"));
            jdbc.update("UPDATE wms_asset SET status = '已出库' WHERE id = ?", asset.get("id"));
            jdbc.update("""
                    INSERT INTO wms_asset_log(asset_no, action, from_status, to_status, warehouse_id, remark, created_at)
                    VALUES(?, '出库', '在库', '已出库', ?, ?, NOW())
                    """, assetNo, warehouseId, "配送单 " + refOrderNo);
        }
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) cur = cur.getCause();
        return cur.getMessage() == null ? "未知错误" : cur.getMessage();
    }
}
