package com.hr.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配送模块 V2 的幂等列迁移：给 biz_delivery 增补经纬度 / 路线规划 / 配送员等字段。
 *
 * MySQL 不支持 ALTER TABLE ... ADD COLUMN IF NOT EXISTS（MariaDB 才有），
 * 而 spring.sql.init 的脚本执行器不认识 DELIMITER（存储过程写法会直接启动失败），
 * 所以改为应用启动后用 JdbcTemplate 查 INFORMATION_SCHEMA 逐列补齐。
 * 幂等可重复执行：列已存在时跳过。
 */
@Configuration
public class DeliveryV2ColumnMigrator {

    private static final Logger log = LoggerFactory.getLogger(DeliveryV2ColumnMigrator.class);

    /** 待增补的列：列名 -> 列定义（固定白名单，无注入风险） */
    private static final Map<String, String> NEW_COLUMNS = new LinkedHashMap<>() {{
        put("from_lng",     "DECIMAL(10,6) NULL COMMENT '起点经度'");
        put("from_lat",     "DECIMAL(10,6) NULL COMMENT '起点纬度'");
        put("to_lng",       "DECIMAL(10,6) NULL COMMENT '终点经度'");
        put("to_lat",       "DECIMAL(10,6) NULL COMMENT '终点纬度'");
        put("route_json",   "TEXT NULL COMMENT '路线规划结果（JSON，含距离/时长/步骤）'");
        put("courier_id",   "BIGINT NULL COMMENT '配送员用户ID（sys_user.id，用于权限隔离）'");
        put("warehouse_id", "BIGINT NULL COMMENT '发货仓库ID'");
        put("outbound_no",  "VARCHAR(40) NULL COMMENT '关联出库单号'");
        put("confirmed_at", "DATETIME NULL COMMENT '出库确认时间'");
        put("customer_phone", "VARCHAR(50) NULL COMMENT '客户联系方式'");
    }};

    @Bean
    public ApplicationRunner deliveryV2ColumnMigration(JdbcTemplate jdbc) {
        return args -> {
            for (Map.Entry<String, String> e : NEW_COLUMNS.entrySet()) {
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'biz_delivery' AND COLUMN_NAME = ?",
                        Integer.class, e.getKey());
                if (count != null && count == 0) {
                    jdbc.execute("ALTER TABLE biz_delivery ADD COLUMN " + e.getKey() + " " + e.getValue());
                    log.info("biz_delivery 增补列：{}", e.getKey());
                }
            }
        };
    }
}
