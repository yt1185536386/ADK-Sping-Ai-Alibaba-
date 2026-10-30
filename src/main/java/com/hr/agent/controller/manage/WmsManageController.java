package com.hr.agent.controller.manage;

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

import java.util.List;
import java.util.Map;

import static com.hr.agent.controller.manage.ManageSupport.blankToNull;

/**
 * 管理端 · 仓储域：仓库、商品（只读下拉）、库存的主数据维护。
 * 业务冲突（如删除仍有库存的仓库）由全局异常处理器转 409。
 */
@RestController
@RequestMapping("/api/manage")
public class WmsManageController {

    private final JdbcTemplate jdbc;

    public WmsManageController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/warehouses")
    public List<Map<String, Object>> warehouses() {
        return jdbc.queryForList("""
                SELECT w.id, w.code, w.name, w.address, w.status, w.created_at,
                       (SELECT COUNT(*) FROM wms_stock s WHERE s.warehouse_id = w.id) AS sku_count,
                       (SELECT COALESCE(SUM(s.quantity), 0) FROM wms_stock s WHERE s.warehouse_id = w.id) AS total_qty
                FROM wms_warehouse w ORDER BY w.id
                """);
    }

    @GetMapping("/stock")
    public List<Map<String, Object>> stock(@RequestParam(defaultValue = "200") int limit) {
        return jdbc.queryForList("""
                SELECT s.id, s.warehouse_id, s.item_id, w.name AS warehouse_name, i.sku, i.name AS item_name, i.category, i.unit,
                       s.quantity, s.safety_stock, s.updated_at,
                       CASE WHEN s.quantity < s.safety_stock THEN '预警' ELSE '正常' END AS stock_status
                FROM wms_stock s
                JOIN wms_warehouse w ON w.id = s.warehouse_id
                JOIN wms_item i ON i.id = s.item_id
                ORDER BY (s.quantity < s.safety_stock) DESC, s.updated_at DESC LIMIT ?
                """, Math.min(Math.max(limit, 1), 500));
    }

    /** 商品下拉选项（业务表单与管理表单共用） */
    @GetMapping("/items")
    public List<Map<String, Object>> items() {
        return jdbc.queryForList("SELECT id, sku, name, unit, category FROM wms_item ORDER BY id");
    }

    // ==================== 仓库 CRUD ====================

    public record WarehouseForm(String code, String name, String address, String status) {}

    @PostMapping("/warehouses")
    public Map<String, Object> createWarehouse(@RequestBody WarehouseForm f) {
        if (f.code() == null || f.code().isBlank()) throw new IllegalArgumentException("仓库编码不能为空");
        if (f.name() == null || f.name().isBlank()) throw new IllegalArgumentException("仓库名称不能为空");
        jdbc.update("INSERT INTO wms_warehouse(code, name, address, status) VALUES (?,?,?,?)",
                f.code().trim(), f.name().trim(), blankToNull(f.address()),
                f.status() == null || f.status().isBlank() ? "启用" : f.status());
        return Map.of("ok", true);
    }

    @PutMapping("/warehouses/{id}")
    public Map<String, Object> updateWarehouse(@PathVariable long id, @RequestBody WarehouseForm f) {
        if (f.code() == null || f.code().isBlank()) throw new IllegalArgumentException("仓库编码不能为空");
        if (f.name() == null || f.name().isBlank()) throw new IllegalArgumentException("仓库名称不能为空");
        jdbc.update("UPDATE wms_warehouse SET code=?, name=?, address=?, status=? WHERE id=?",
                f.code().trim(), f.name().trim(), blankToNull(f.address()),
                f.status() == null || f.status().isBlank() ? "启用" : f.status(), id);
        return Map.of("ok", true);
    }

    @DeleteMapping("/warehouses/{id}")
    public Map<String, Object> deleteWarehouse(@PathVariable long id) {
        int stock = jdbc.queryForObject("SELECT COUNT(*) FROM wms_stock WHERE warehouse_id=?", Integer.class, id);
        if (stock > 0) throw new IllegalStateException("该仓库下仍有 " + stock + " 条库存记录，请先清空库存");
        jdbc.update("DELETE FROM wms_warehouse WHERE id=?", id);
        return Map.of("ok", true);
    }

    // ==================== 库存 CRUD ====================

    public record StockForm(Long warehouseId, Long itemId, Integer quantity, Integer safetyStock) {}

    @PostMapping("/stock")
    public Map<String, Object> createStock(@RequestBody StockForm f) {
        if (f.warehouseId() == null || f.itemId() == null) throw new IllegalArgumentException("仓库和商品不能为空");
        int qty = f.quantity() == null ? 0 : Math.max(0, f.quantity());
        int safety = f.safetyStock() == null ? 0 : Math.max(0, f.safetyStock());
        int updated = jdbc.update("UPDATE wms_stock SET quantity=quantity+?, safety_stock=? WHERE warehouse_id=? AND item_id=?",
                qty, safety, f.warehouseId(), f.itemId());
        if (updated == 0) {
            jdbc.update("INSERT INTO wms_stock(warehouse_id, item_id, quantity, safety_stock) VALUES (?,?,?,?)",
                    f.warehouseId(), f.itemId(), qty, safety);
        }
        return Map.of("ok", true);
    }

    @PutMapping("/stock/{id}")
    public Map<String, Object> updateStock(@PathVariable long id, @RequestBody StockForm f) {
        jdbc.update("UPDATE wms_stock SET quantity=?, safety_stock=? WHERE id=?",
                f.quantity() == null ? 0 : Math.max(0, f.quantity()),
                f.safetyStock() == null ? 0 : Math.max(0, f.safetyStock()), id);
        return Map.of("ok", true);
    }

    @DeleteMapping("/stock/{id}")
    public Map<String, Object> deleteStock(@PathVariable long id) {
        jdbc.update("DELETE FROM wms_stock WHERE id=?", id);
        return Map.of("ok", true);
    }
}
