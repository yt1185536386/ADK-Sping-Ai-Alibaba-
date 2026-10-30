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
 * 管理端相关的幂等列迁移。
 *
 * <p>llm_call_log 需要补 username / thread_id 两列：管理端要按「用户维度」统计调用量，
 * 而建表语句是 CREATE TABLE IF NOT EXISTS，老库不会被自动补列。
 * MySQL 不支持 ALTER TABLE ... ADD COLUMN IF NOT EXISTS，spring.sql.init 也不认识
 * DELIMITER，所以与应用启动后用 JdbcTemplate 查 INFORMATION_SCHEMA 逐列补齐。
 */
@Configuration
public class AdminSchemaMigrator {

    private static final Logger log = LoggerFactory.getLogger(AdminSchemaMigrator.class);

    /** llm_call_log 待增补的列：列名 -> 列定义（固定白名单，无注入风险） */
    private static final Map<String, String> LLM_CALL_LOG_COLUMNS = new LinkedHashMap<>() {{
        put("username", "VARCHAR(50) NULL COMMENT '发起调用的登录用户（管理端按用户维度统计）'");
        put("thread_id", "VARCHAR(64) NULL COMMENT '会话标识（管理端按会话/用户聚合）'");
    }};

    /** 熔断 / 降级事件流水表（管理端观察熔断时间与降级效果） */
    private static final String FAILOVER_EVENT_DDL = """
            CREATE TABLE IF NOT EXISTS model_failover_event (
                id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                event_type    VARCHAR(20)  NOT NULL COMMENT 'breaker_trip 熔断触发 / failover 降级（含命中熔断跳过）',
                module_key    VARCHAR(50)  NULL COMMENT '发起调用的业务模块',
                username      VARCHAR(50)  NULL COMMENT '发起调用的登录用户',
                thread_id     VARCHAR(64)  NULL COMMENT '会话标识',
                from_provider VARCHAR(50)  NULL COMMENT '原渠道（被熔断/失败的渠道）',
                from_model    VARCHAR(100) NULL COMMENT '原模型',
                to_provider   VARCHAR(50)  NULL COMMENT '降级选中的渠道',
                to_model      VARCHAR(100) NULL COMMENT '降级选中的模型',
                attempts      INT          NOT NULL DEFAULT 1 COMMENT '本次调用实际尝试次数（不含被熔断跳过的）',
                skipped       VARCHAR(255) NULL COMMENT '本次调用因熔断被跳过的渠道',
                success       TINYINT      NULL COMMENT '降级是否成功：1成功 0失败（breaker_trip 事件为 NULL）',
                error_msg     VARCHAR(512) NULL COMMENT '触发原因摘要',
                created_at    DATETIME(6)  DEFAULT CURRENT_TIMESTAMP(6),
                KEY idx_failover_created (created_at),
                KEY idx_failover_provider (from_provider)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='熔断与降级事件记录'
            """;

    /** chat_message 待增补的列：username 用于会话归属隔离（老库历史数据为 NULL，按公共会话兼容） */
    private static final Map<String, String> CHAT_MESSAGE_COLUMNS = new LinkedHashMap<>() {{
        put("username", "VARCHAR(50) NULL COMMENT '消息归属的登录用户（会话隔离）'");
    }};

    @Bean
    public ApplicationRunner adminSchemaMigration(JdbcTemplate jdbc) {
        return args -> {
            for (Map.Entry<String, String> e : LLM_CALL_LOG_COLUMNS.entrySet()) {
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'llm_call_log' AND COLUMN_NAME = ?",
                        Integer.class, e.getKey());
                if (count != null && count == 0) {
                    jdbc.execute("ALTER TABLE llm_call_log ADD COLUMN " + e.getKey() + " " + e.getValue());
                    log.info("llm_call_log 增补列：{}", e.getKey());
                }
            }
            for (Map.Entry<String, String> e : CHAT_MESSAGE_COLUMNS.entrySet()) {
                Integer count = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'chat_message' AND COLUMN_NAME = ?",
                        Integer.class, e.getKey());
                if (count != null && count == 0) {
                    jdbc.execute("ALTER TABLE chat_message ADD COLUMN " + e.getKey() + " " + e.getValue());
                    log.info("chat_message 增补列：{}", e.getKey());
                }
            }
            // CREATE TABLE IF NOT EXISTS 本身幂等，老库启动时自动补齐
            jdbc.execute(FAILOVER_EVENT_DDL);
        };
    }
}
