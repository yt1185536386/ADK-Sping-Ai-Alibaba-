package com.hr.agent.model.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 熔断 / 降级事件落库（{@code model_failover_event} 表）。
 *
 * <p>事件不高频（只在熔断触发、发生降级时产生），直接同步写入即可；
 * 写库失败只记日志，绝不能影响模型调用主链路。
 */
@Component
public class FailoverEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(FailoverEventRecorder.class);
    private static final int MSG_MAX = 500;

    private final JdbcTemplate jdbc;

    public FailoverEventRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录一条熔断 / 降级事件；调用上下文（模块/用户/会话）取当前线程的 ModelCallContext，可能为空。
     *
     * @param eventType    breaker_trip 熔断触发 / failover 降级
     * @param fromProvider 原渠道
     * @param fromModel    原模型
     * @param toProvider   降级选中的渠道（无降级目标时为 null）
     * @param toModel      降级选中的模型
     * @param attempts     本次调用实际尝试次数
     * @param skipped      因熔断被跳过的渠道（逗号分隔，可为 null）
     * @param success      降级是否成功（breaker_trip 事件传 null）
     * @param errorMsg     触发原因摘要
     */
    public void record(String eventType, String fromProvider, String fromModel,
                       String toProvider, String toModel, int attempts, String skipped,
                       Boolean success, String errorMsg) {
        try {
            String module = safe(ModelCallContext.module());
            String threadId = safe(ModelCallContext.threadId());
            String user = safe(ModelCallContext.user());
            jdbc.update("""
                    INSERT INTO model_failover_event
                        (event_type, module_key, username, thread_id,
                         from_provider, from_model, to_provider, to_model,
                         attempts, skipped, success, error_msg)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    eventType, module, user, threadId,
                    fromProvider, fromModel, toProvider, toModel,
                    Math.max(attempts, 1), cut(skipped, 255),
                    success == null ? null : (success ? 1 : 0),
                    cut(errorMsg, MSG_MAX));
        } catch (Exception e) {
            log.debug("熔断/降级事件落库失败：{}", e.getMessage());
        }
    }

    private static String safe(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() > max ? flat.substring(0, max) : flat;
    }
}
