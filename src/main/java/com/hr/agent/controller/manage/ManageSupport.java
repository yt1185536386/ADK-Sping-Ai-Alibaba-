package com.hr.agent.controller.manage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 管理端业务数据 CRUD 的公共辅助：参数清洗、日期解析、入库联动。
 */
@Component
public class ManageSupport {

    private final JdbcTemplate jdbc;

    public ManageSupport(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 入库联动：upsert 库存数量 */
    public void addStock(long warehouseId, long itemId, int qty) {
        int updated = jdbc.update("UPDATE wms_stock SET quantity=quantity+? WHERE warehouse_id=? AND item_id=?",
                qty, warehouseId, itemId);
        if (updated == 0) {
            jdbc.update("INSERT INTO wms_stock(warehouse_id, item_id, quantity, safety_stock) VALUES (?,?,?,0)",
                    warehouseId, itemId, qty);
        }
    }

    public static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    public static java.sql.Date parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return java.sql.Date.valueOf(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 前端 datetime 字符串（yyyy-MM-dd HH:mm:ss）转 SQL TIMESTAMP */
    public static java.sql.Timestamp parseTs(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return java.sql.Timestamp.valueOf(s.replace('T', ' ').length() == 16 ? s.replace('T', ' ') + ":00" : s.replace('T', ' '));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
