package com.hr.agent.controller.manage;

import com.hr.agent.service.DeliveryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static com.hr.agent.controller.manage.ManageSupport.blankToNull;
import static com.hr.agent.controller.manage.ManageSupport.parseTs;

/**
 * 管理端 · 订单域：销售订单、配送单、采购入库、退货入库的台账维护。
 *
 * <p>配送单的创建/撤销委托 {@link DeliveryService}（biz_delivery 的唯一写入入口），
 * 本类只做参数转换；编辑/删除属于管理员台账维护，保留直写。
 * 业务冲突由全局异常处理器转 409。
 */
@RestController
@RequestMapping("/api/manage")
public class OrderManageController {

    private final JdbcTemplate jdbc;
    private final DeliveryService deliveryService;
    private final ManageSupport support;

    public OrderManageController(JdbcTemplate jdbc, DeliveryService deliveryService, ManageSupport support) {
        this.jdbc = jdbc;
        this.deliveryService = deliveryService;
        this.support = support;
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> orders(@RequestParam(required = false) String status,
                                            @RequestParam(defaultValue = "200") int limit) {
        int lim = Math.min(Math.max(limit, 1), 500);
        String base = """
                SELECT o.id, o.order_no, o.customer, o.quantity, o.unit_price, o.total_amount,
                       o.status, o.remark, o.created_at, o.updated_at,
                       o.item_id, i.sku, i.name AS item_name,
                       d.delivery_no, d.status AS delivery_status, d.employee_name AS courier, d.address AS delivery_address
                FROM biz_order o
                JOIN wms_item i ON i.id = o.item_id
                LEFT JOIN biz_delivery d ON d.order_id = o.id
                """;
        if (status != null && !status.isBlank()) {
            return jdbc.queryForList(base + " WHERE o.status = ? ORDER BY o.id DESC LIMIT ?",
                    status.trim(), lim);
        }
        return jdbc.queryForList(base + " ORDER BY o.id DESC LIMIT ?", lim);
    }

    @GetMapping("/deliveries")
    public List<Map<String, Object>> deliveries(@RequestParam(defaultValue = "200") int limit) {
        return jdbc.queryForList("""
                SELECT d.id, d.delivery_no, d.status, d.employee_id, d.employee_name, d.address, d.destination, d.deadline,
                       d.customer_phone, d.delivered_at, d.created_at,
                       d.order_id, d.warehouse_id, d.courier_id, d.outbound_no, d.route_json,
                       w.name AS warehouse_name,
                       COALESCE(cu.display_name, d.employee_name) AS courier_name,
                       o.order_no, o.customer, i.name AS item_name, o.quantity
                FROM biz_delivery d
                JOIN biz_order o ON o.id = d.order_id
                JOIN wms_item i ON i.id = o.item_id
                LEFT JOIN wms_warehouse w ON w.id = d.warehouse_id
                LEFT JOIN sys_user cu ON cu.id = d.courier_id
                ORDER BY d.id DESC LIMIT ?
                """, Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/purchases")
    public List<Map<String, Object>> purchases(@RequestParam(defaultValue = "200") int limit) {
        return jdbc.queryForList("""
                SELECT p.id, p.purchase_no, p.quantity, p.supplier, p.status, p.employee_name, p.created_at,
                       p.item_id, p.warehouse_id, p.order_id,
                       i.sku, i.name AS item_name, w.name AS warehouse_name, o.order_no
                FROM biz_purchase p
                JOIN wms_item i ON i.id = p.item_id
                LEFT JOIN wms_warehouse w ON w.id = p.warehouse_id
                LEFT JOIN biz_order o ON o.id = p.order_id
                ORDER BY p.id DESC LIMIT ?
                """, Math.min(Math.max(limit, 1), 500));
    }

    @GetMapping("/returns")
    public List<Map<String, Object>> returns(@RequestParam(defaultValue = "200") int limit) {
        return jdbc.queryForList("""
                SELECT r.id, r.return_no, r.quantity, r.reason, r.status, r.created_at,
                       r.order_id, r.item_id, r.warehouse_id,
                       o.order_no, o.customer, i.name AS item_name, w.name AS warehouse_name
                FROM biz_return r
                JOIN biz_order o ON o.id = r.order_id
                JOIN wms_item i ON i.id = r.item_id
                LEFT JOIN wms_warehouse w ON w.id = r.warehouse_id
                ORDER BY r.id DESC LIMIT ?
                """, Math.min(Math.max(limit, 1), 500));
    }

    /** 配送员下拉选项（sys_user 中 role=COURIER 且 enabled=1 的用户，关联员工表取姓名电话） */
    @GetMapping("/couriers")
    public List<Map<String, Object>> couriers() {
        return jdbc.queryForList("""
                SELECT u.id, u.username, u.display_name, u.employee_id,
                       e.name AS employee_name, e.phone
                FROM sys_user u
                LEFT JOIN employee e ON e.id = u.employee_id
                WHERE u.role = 'COURIER' AND u.enabled = 1
                ORDER BY u.id
                """);
    }

    // ==================== 订单 CRUD ====================

    public record OrderForm(String customer, Long itemId, Integer quantity, Double unitPrice,
                            String status, String remark) {}

    @PostMapping("/orders")
    public Map<String, Object> createOrder(@RequestBody OrderForm f) {
        if (f.customer() == null || f.customer().isBlank()) throw new IllegalArgumentException("客户不能为空");
        if (f.itemId() == null) throw new IllegalArgumentException("商品不能为空");
        int qty = f.quantity() == null ? 1 : Math.max(1, f.quantity());
        Double price = f.unitPrice();
        Double total = price == null ? null : Math.round(price * qty * 100) / 100.0;
        String orderNo = "ORD" + System.currentTimeMillis();
        jdbc.update("INSERT INTO biz_order(order_no, customer, item_id, quantity, unit_price, total_amount, status, remark) VALUES (?,?,?,?,?,?,?,?)",
                orderNo, f.customer().trim(), f.itemId(), qty, price, total,
                f.status() == null || f.status().isBlank() ? "待处理" : f.status(), blankToNull(f.remark()));
        return Map.of("ok", true, "orderNo", orderNo);
    }

    @PutMapping("/orders/{id}")
    public Map<String, Object> updateOrder(@PathVariable long id, @RequestBody OrderForm f) {
        if (f.customer() == null || f.customer().isBlank()) throw new IllegalArgumentException("客户不能为空");
        int qty = f.quantity() == null ? 1 : Math.max(1, f.quantity());
        Double price = f.unitPrice();
        Double total = price == null ? null : Math.round(price * qty * 100) / 100.0;
        jdbc.update("UPDATE biz_order SET customer=?, item_id=?, quantity=?, unit_price=?, total_amount=?, status=?, remark=? WHERE id=?",
                f.customer().trim(), f.itemId(), qty, price, total,
                f.status() == null || f.status().isBlank() ? "待处理" : f.status(), blankToNull(f.remark()), id);
        return Map.of("ok", true);
    }

    @DeleteMapping("/orders/{id}")
    public Map<String, Object> deleteOrder(@PathVariable long id) {
        int deliveries = jdbc.queryForObject("SELECT COUNT(*) FROM biz_delivery WHERE order_id=?", Integer.class, id);
        if (deliveries > 0) throw new IllegalStateException("该订单存在 " + deliveries + " 张关联配送单，请先删除配送单");
        jdbc.update("DELETE FROM biz_order WHERE id=?", id);
        return Map.of("ok", true);
    }

    // ==================== 配送单（创建/撤销走 DeliveryService，编辑/删除是台账维护） ====================

    public record DeliveryForm(Long orderId, String employeeName, Long employeeId, String customerPhone,
                               String address, String destination,
                               String deadline, String status, Long warehouseId, Long courierId) {}

    /** 创建配送单：委托 DeliveryService（订单/仓库校验 + 单号生成 + 异步路线规划） */
    @PostMapping("/deliveries")
    public Map<String, Object> createDelivery(@RequestBody DeliveryForm f) {
        if (f.orderId() == null) throw new IllegalArgumentException("关联订单不能为空");
        Timestamp deadline = parseTs(f.deadline());
        String deliveryNo = deliveryService.createDelivery(
                f.orderId(), f.courierId(), f.warehouseId(),
                f.address(), f.destination(), null, null, null, null,
                deadline == null ? null : deadline.toLocalDateTime(), null,
                blankToNull(f.customerPhone()));
        // 配送员工（employee 维度）：选了员工就回填姓名，且该员工有配送账号时自动关联 courier_id
        applyEmployee(deliveryNo, f);
        return Map.of("ok", true, "deliveryNo", deliveryNo);
    }

    @PutMapping("/deliveries/{id}")
    public Map<String, Object> updateDelivery(@PathVariable long id, @RequestBody DeliveryForm f) {
        String status = f.status() == null || f.status().isBlank() ? "待配送" : f.status();
        String empName = resolveEmployeeName(f.employeeId(), f.employeeName());
        Long courierId = f.courierId() != null ? f.courierId()
                : (f.employeeId() != null ? deliveryService.findCourierUserId(f.employeeId()) : null);
        if ("已送达".equals(status)) {
            jdbc.update("UPDATE biz_delivery SET employee_id=?, employee_name=?, customer_phone=?, address=?, destination=?, deadline=?, warehouse_id=?, courier_id=?, status='已送达', delivered_at=NOW() WHERE id=?",
                    f.employeeId(), empName, blankToNull(f.customerPhone()), blankToNull(f.address()), blankToNull(f.destination()), parseTs(f.deadline()),
                    f.warehouseId(), courierId, id);
            Long orderId = jdbc.queryForObject("SELECT order_id FROM biz_delivery WHERE id=?", Long.class, id);
            if (orderId != null) jdbc.update("UPDATE biz_order SET status='已发货' WHERE id=?", orderId);
        } else {
            jdbc.update("UPDATE biz_delivery SET employee_id=?, employee_name=?, customer_phone=?, address=?, destination=?, deadline=?, warehouse_id=?, courier_id=?, status=? WHERE id=?",
                    f.employeeId(), empName, blankToNull(f.customerPhone()), blankToNull(f.address()), blankToNull(f.destination()), parseTs(f.deadline()),
                    f.warehouseId(), courierId, status, id);
        }
        return Map.of("ok", true);
    }

    /** 创建后回填员工维度字段（employee_id/employee_name/courier_id） */
    private void applyEmployee(String deliveryNo, DeliveryForm f) {
        if (f.employeeId() == null) {
            return;
        }
        String empName = resolveEmployeeName(f.employeeId(), f.employeeName());
        Long courierId = f.courierId() != null ? f.courierId() : deliveryService.findCourierUserId(f.employeeId());
        jdbc.update("UPDATE biz_delivery SET employee_id=?, employee_name=?, courier_id=COALESCE(?, courier_id) WHERE delivery_no=?",
                f.employeeId(), empName, courierId, deliveryNo);
    }

    /** 员工ID → 姓名；未选员工时退回表单里的手填姓名 */
    private String resolveEmployeeName(Long employeeId, String fallback) {
        if (employeeId == null) {
            return blankToNull(fallback);
        }
        List<String> names = jdbc.queryForList("SELECT name FROM employee WHERE id = ?", String.class, employeeId);
        return names.isEmpty() ? blankToNull(fallback) : names.get(0);
    }

    /** 撤销配送：走 DeliveryService 的状态机（已出库/已送达不可撤销），成功后关联订单同步置"已取消" */
    @PostMapping("/deliveries/{id}/cancel")
    public Map<String, Object> cancelDelivery(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT delivery_no, order_id FROM biz_delivery WHERE id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("配送单不存在：" + id);
        String deliveryNo = String.valueOf(rows.get(0).get("delivery_no"));
        deliveryService.cancelDelivery(deliveryNo);
        Object orderId = rows.get(0).get("order_id");
        if (orderId != null) jdbc.update("UPDATE biz_order SET status='已取消' WHERE id=?", orderId);
        return Map.of("ok", true);
    }

    @DeleteMapping("/deliveries/{id}")
    public Map<String, Object> deleteDelivery(@PathVariable long id) {
        jdbc.update("DELETE FROM biz_delivery WHERE id=?", id);
        return Map.of("ok", true);
    }

    // ==================== 采购入库 CRUD ====================

    public record PurchaseForm(Long itemId, Long warehouseId, Integer quantity, String supplier,
                               String employeeName, Long orderId, String status) {}

    @PostMapping("/purchases")
    public Map<String, Object> createPurchase(@RequestBody PurchaseForm f) {
        if (f.itemId() == null) throw new IllegalArgumentException("商品不能为空");
        int qty = f.quantity() == null ? 1 : Math.max(1, f.quantity());
        String status = f.status() == null || f.status().isBlank() ? "待采购" : f.status();
        String no = "PUR" + System.currentTimeMillis();
        jdbc.update("INSERT INTO biz_purchase(purchase_no, item_id, quantity, warehouse_id, supplier, employee_name, order_id, status) VALUES (?,?,?,?,?,?,?,?)",
                no, f.itemId(), qty, f.warehouseId(), blankToNull(f.supplier()), blankToNull(f.employeeName()),
                f.orderId(), status);
        if ("已入库".equals(status) && f.warehouseId() != null) support.addStock(f.warehouseId(), f.itemId(), qty);
        return Map.of("ok", true, "purchaseNo", no);
    }

    @PutMapping("/purchases/{id}")
    public Map<String, Object> updatePurchase(@PathVariable long id, @RequestBody PurchaseForm f) {
        int qty = f.quantity() == null ? 1 : Math.max(1, f.quantity());
        String status = f.status() == null || f.status().isBlank() ? "待采购" : f.status();
        String prev = jdbc.queryForObject("SELECT status FROM biz_purchase WHERE id=?", String.class, id);
        jdbc.update("UPDATE biz_purchase SET item_id=?, quantity=?, warehouse_id=?, supplier=?, employee_name=?, order_id=?, status=? WHERE id=?",
                f.itemId(), qty, f.warehouseId(), blankToNull(f.supplier()), blankToNull(f.employeeName()),
                f.orderId(), status, id);
        if ("已入库".equals(status) && f.warehouseId() != null && !"已入库".equals(prev)) support.addStock(f.warehouseId(), f.itemId(), qty);
        return Map.of("ok", true);
    }

    @DeleteMapping("/purchases/{id}")
    public Map<String, Object> deletePurchase(@PathVariable long id) {
        jdbc.update("DELETE FROM biz_purchase WHERE id=?", id);
        return Map.of("ok", true);
    }

    // ==================== 退货入库 CRUD ====================

    public record ReturnForm(Long orderId, Long itemId, Long warehouseId, Integer quantity,
                             String reason, String status) {}

    @PostMapping("/returns")
    public Map<String, Object> createReturn(@RequestBody ReturnForm f) {
        if (f.orderId() == null) throw new IllegalArgumentException("关联订单不能为空");
        int qty = f.quantity() == null ? 1 : Math.max(1, f.quantity());
        String status = f.status() == null || f.status().isBlank() ? "待处理" : f.status();
        Long itemId = f.itemId();
        if (itemId == null) {
            itemId = jdbc.queryForObject("SELECT item_id FROM biz_order WHERE id=?", Long.class, f.orderId());
        }
        String no = "RET" + System.currentTimeMillis();
        jdbc.update("INSERT INTO biz_return(return_no, order_id, item_id, quantity, warehouse_id, reason, status) VALUES (?,?,?,?,?,?,?)",
                no, f.orderId(), itemId, qty, f.warehouseId(), blankToNull(f.reason()), status);
        if ("已入库".equals(status) && f.warehouseId() != null) support.addStock(f.warehouseId(), itemId, qty);
        return Map.of("ok", true, "returnNo", no);
    }

    @PutMapping("/returns/{id}")
    public Map<String, Object> updateReturn(@PathVariable long id, @RequestBody ReturnForm f) {
        int qty = f.quantity() == null ? 1 : Math.max(1, f.quantity());
        String status = f.status() == null || f.status().isBlank() ? "待处理" : f.status();
        String prev = jdbc.queryForObject("SELECT status FROM biz_return WHERE id=?", String.class, id);
        jdbc.update("UPDATE biz_return SET quantity=?, warehouse_id=?, reason=?, status=? WHERE id=?",
                qty, f.warehouseId(), blankToNull(f.reason()), status, id);
        if ("已入库".equals(status) && f.warehouseId() != null && !"已入库".equals(prev)) {
            Long itemId = jdbc.queryForObject("SELECT item_id FROM biz_return WHERE id=?", Long.class, id);
            support.addStock(f.warehouseId(), itemId, qty);
        }
        return Map.of("ok", true);
    }

    @DeleteMapping("/returns/{id}")
    public Map<String, Object> deleteReturn(@PathVariable long id) {
        jdbc.update("DELETE FROM biz_return WHERE id=?", id);
        return Map.of("ok", true);
    }
}
