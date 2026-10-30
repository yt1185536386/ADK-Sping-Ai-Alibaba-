package com.hr.agent.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端「调用统计」服务：基于 llm_call_log 表（模型调用审计与 token 计量）做聚合。
 *
 * <p>所有查询都按时间窗过滤（默认近 7 天），返回给前端直接喂给 ECharts。
 * 维度：整体概览 / 时间趋势 / 渠道 / 模型 / 用户 / 业务模块 / 失败原因。
 */
@Component
public class AdminStatsService {

    private final JdbcTemplate jdbc;

    public AdminStatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 时间窗起点（含），格式 yyyy-MM-dd HH:mm:ss */
    private String since(int days) {
        // 用 Java 计算时间点，避免 MySQL 预处理语句里 INTERVAL ? DAY 的写法问题
        return LocalDateTime.now().minusDays(Math.max(days, 1))
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** 概览卡片：调用量 / Token / 成功率 / 活跃用户 / 平均延迟 */
    public Map<String, Object> overview(int days) {
        String from = since(days);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("days", days);
        m.put("from", from);

        Map<String, Object> agg = jdbc.queryForMap("""
                SELECT COUNT(*) AS calls,
                       COALESCE(SUM(total_tokens), 0) AS tokens,
                       COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,
                       COALESCE(SUM(completion_tokens), 0) AS completion_tokens,
                       COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS failures,
                       COALESCE(AVG(latency_ms), 0) AS avg_latency,
                       COALESCE(MAX(first_token_ms), 0) AS max_first_token
                  FROM llm_call_log WHERE created_at >= ?
                """, from);

        long calls = num(agg.get("calls"));
        long failures = num(agg.get("failures"));
        m.put("calls", calls);
        m.put("tokens", num(agg.get("tokens")));
        m.put("promptTokens", num(agg.get("prompt_tokens")));
        m.put("completionTokens", num(agg.get("completion_tokens")));
        m.put("failures", failures);
        m.put("successRate", calls == 0 ? 100.0 : round((calls - failures) * 100.0 / calls, 2));
        m.put("avgLatency", Math.round(num(agg.get("avg_latency"))));
        m.put("maxFirstToken", num(agg.get("max_first_token")));

        Long users = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT username) FROM llm_call_log WHERE created_at >= ? AND username IS NOT NULL
                """, Long.class, from);
        m.put("activeUsers", users == null ? 0 : users);

        Long models = jdbc.queryForObject("""
                SELECT COUNT(DISTINCT model) FROM llm_call_log WHERE created_at >= ? AND model IS NOT NULL
                """, Long.class, from);
        m.put("modelCount", models == null ? 0 : models);

        List<Map<String, Object>> top = jdbc.queryForList("""
                SELECT model, COUNT(*) AS calls, COALESCE(SUM(total_tokens), 0) AS tokens
                  FROM llm_call_log WHERE created_at >= ? AND model IS NOT NULL
                 GROUP BY model ORDER BY calls DESC LIMIT 5
                """, from);
        m.put("topModels", top);
        return m;
    }

    /** 按天的时间趋势：调用量 / Token / 失败数 / 平均延迟 */
    public List<Map<String, Object>> trend(int days) {
        String from = since(days);
        return jdbc.queryForList("""
                SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS day,
                       COUNT(*) AS calls,
                       COALESCE(SUM(total_tokens), 0) AS tokens,
                       COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS failures,
                       ROUND(COALESCE(AVG(latency_ms), 0)) AS avg_latency
                  FROM llm_call_log WHERE created_at >= ?
                 GROUP BY day ORDER BY day ASC
                """, from);
    }

    /**
     * 自定义时间范围的趋势：范围 ≤ 24 小时按小时聚合，超过则按天聚合。
     * 一次返回 granularity / trend / trendByModel，供概览页趋势图使用。
     */
    public Map<String, Object> trendRange(String start, String end) {
        LocalDateTime to = parseTime(end, LocalDateTime.now());
        LocalDateTime from = parseTime(start, to.toLocalDate().atStartOfDay());
        if (from.isAfter(to)) {
            LocalDateTime tmp = from;
            from = to;
            to = tmp;
        }
        boolean byHour = java.time.Duration.between(from, to).toHours() <= 24;
        String bucket = byHour ? "%Y-%m-%d %H:00" : "%Y-%m-%d";
        String fromStr = fmt(from);
        String toStr = fmt(to);

        List<Map<String, Object>> trend = jdbc.queryForList("""
                SELECT DATE_FORMAT(created_at, ?) AS bucket,
                       COUNT(*) AS calls,
                       COALESCE(SUM(total_tokens), 0) AS tokens,
                       COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS failures
                  FROM llm_call_log WHERE created_at >= ? AND created_at <= ?
                 GROUP BY bucket ORDER BY bucket ASC
                """, bucket, fromStr, toStr);

        List<Map<String, Object>> trendByModel = jdbc.queryForList("""
                SELECT DATE_FORMAT(created_at, ?) AS bucket,
                       COALESCE(model, '未知模型') AS model,
                       COUNT(*) AS calls,
                       COALESCE(SUM(total_tokens), 0) AS tokens
                  FROM llm_call_log WHERE created_at >= ? AND created_at <= ?
                 GROUP BY bucket, model ORDER BY bucket ASC, calls DESC
                """, bucket, fromStr, toStr);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("granularity", byHour ? "hour" : "day");
        body.put("from", fromStr);
        body.put("to", toStr);
        body.put("trend", trend);
        body.put("trendByModel", trendByModel);
        return body;
    }

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String fmt(LocalDateTime t) {
        return t.format(FMT);
    }

    /** 支持 yyyy-MM-dd HH:mm:ss / yyyy-MM-dd / ISO 格式，解析失败时用兜底值 */
    private static LocalDateTime parseTime(String s, LocalDateTime fallback) {
        if (s == null || s.isBlank()) {
            return fallback;
        }
        String v = s.trim().replace('T', ' ');
        try {
            return v.length() <= 10
                    ? LocalDateTime.parse(v + " 00:00:00", FMT)
                    : LocalDateTime.parse(v.length() == 16 ? v + ":00" : v, FMT);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** 按天 × 模型的调用量与 Token（趋势图堆叠用：某一天总量下钻到各模型分别多少） */
    public List<Map<String, Object>> trendByModel(int days) {
        String from = since(days);
        return jdbc.queryForList("""
                SELECT DATE_FORMAT(created_at, '%Y-%m-%d') AS day,
                       COALESCE(model, '未知模型') AS model,
                       COUNT(*) AS calls,
                       COALESCE(SUM(total_tokens), 0) AS tokens
                  FROM llm_call_log WHERE created_at >= ?
                 GROUP BY day, model ORDER BY day ASC, calls DESC
                """, from);
    }

    /** 渠道维度分布 */
    public List<Map<String, Object>> byProvider(int days) {
        String from = since(days);
        return jdbc.queryForList("""
                SELECT provider,
                       COUNT(*) AS calls,
                       COALESCE(SUM(total_tokens), 0) AS tokens,
                       COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS failures
                  FROM llm_call_log WHERE created_at >= ? AND provider IS NOT NULL
                 GROUP BY provider ORDER BY calls DESC
                """, from);
    }

    /** 模型维度分布 */
    public List<Map<String, Object>> byModel(int days) {
        String from = since(days);
        return jdbc.queryForList("""
                SELECT model,
                       COUNT(*) AS calls,
                       COALESCE(SUM(total_tokens), 0) AS tokens,
                       ROUND(COALESCE(AVG(latency_ms), 0)) AS avg_latency
                  FROM llm_call_log WHERE created_at >= ? AND model IS NOT NULL
                 GROUP BY model ORDER BY calls DESC LIMIT 20
                """, from);
    }

    /** 用户维度调用量（管理端「用户调用量」统计） */
    public List<Map<String, Object>> byUser(int days) {
        String from = since(days);
        return jdbc.queryForList("""
                SELECT COALESCE(username, '未登录') AS username,
                       COUNT(*) AS calls,
                       COALESCE(SUM(total_tokens), 0) AS tokens,
                       COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS failures,
                       MAX(created_at) AS last_call_at
                  FROM llm_call_log WHERE created_at >= ?
                 GROUP BY username ORDER BY calls DESC LIMIT 20
                """, from);
    }

    /** 业务模块维度 */
    public List<Map<String, Object>> byModule(int days) {
        String from = since(days);
        return jdbc.queryForList("""
                SELECT COALESCE(module_key, 'unknown') AS module_key,
                       COUNT(*) AS calls,
                       COALESCE(SUM(total_tokens), 0) AS tokens
                  FROM llm_call_log WHERE created_at >= ?
                 GROUP BY module_key ORDER BY calls DESC
                """, from);
    }

    /** 最近 24 小时按小时分布 */
    public List<Map<String, Object>> byHour() {
        return jdbc.queryForList("""
                SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:00') AS hour,
                       COUNT(*) AS calls,
                       COALESCE(SUM(total_tokens), 0) AS tokens
                  FROM llm_call_log WHERE created_at >= NOW() - INTERVAL 24 HOUR
                 GROUP BY hour ORDER BY hour ASC
                """);
    }

    /** 熔断 / 降级事件流水：何时熔断、尝试几次、降级到哪个模型、是否成功 */
    public List<Map<String, Object>> failoverEvents(int days) {
        String from = since(days);
        return jdbc.queryForList("""
                SELECT event_type, module_key, username, from_provider, from_model,
                       to_provider, to_model, attempts, skipped, success, error_msg, created_at
                  FROM model_failover_event WHERE created_at >= ?
                 ORDER BY id DESC LIMIT 100
                """, from);
    }

    /** 失败原因 Top（熔断/降级效果观察） */
    public List<Map<String, Object>> topErrors(int days) {
        String from = since(days);
        return jdbc.queryForList("""
                SELECT provider, LEFT(error_msg, 160) AS error_msg, COUNT(*) AS failures
                  FROM llm_call_log
                 WHERE created_at >= ? AND success = 0 AND error_msg IS NOT NULL
                 GROUP BY provider, error_msg ORDER BY failures DESC LIMIT 10
                """, from);
    }

    /**
     * 调用明细（审计列表）：按渠道 / 模型 / 用户 / 成功与否过滤，服务端分页。
     */
    public Map<String, Object> calls(int days, String provider, String model, String username,
                                     Boolean success, int page, int size) {
        String from = since(days);
        StringBuilder where = new StringBuilder(" FROM llm_call_log WHERE created_at >= ?");
        List<Object> args = new ArrayList<>();
        args.add(from);
        appendFilters(where, args, provider, model, username, success);

        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where, Long.class, args.toArray());

        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 200);
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((p - 1) * s);
        List<Map<String, Object>> list = jdbc.queryForList("""
                SELECT id, module_key, provider, model, protocol, username, thread_id,
                       prompt_tokens, completion_tokens, total_tokens,
                       latency_ms, first_token_ms, success, error_msg, created_at
                """ + where + " ORDER BY id DESC LIMIT ? OFFSET ?", pageArgs.toArray());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("list", list);
        body.put("total", total == null ? 0 : total);
        body.put("page", p);
        body.put("size", s);
        return body;
    }

    /**
     * 调用明细的时间序列（图表用）：按时间升序返回窗口内全部记录的关键指标，
     * 上限 5000 条，超出时取最近的记录。
     */
    public List<Map<String, Object>> callSeries(int days, String provider, String model, String username,
                                                Boolean success) {
        String from = since(days);
        StringBuilder where = new StringBuilder(" FROM llm_call_log WHERE created_at >= ?");
        List<Object> args = new ArrayList<>();
        args.add(from);
        appendFilters(where, args, provider, model, username, success);
        args.add(5000);
        List<Map<String, Object>> desc = jdbc.queryForList("""
                SELECT id, created_at, provider, model, username, success,
                       prompt_tokens, completion_tokens, total_tokens, latency_ms, first_token_ms
                """ + where + " ORDER BY id DESC LIMIT ?", args.toArray());
        // 反转为时间升序，前端直接按数组顺序画图
        List<Map<String, Object>> asc = new ArrayList<>(desc.size());
        for (int i = desc.size() - 1; i >= 0; i--) {
            asc.add(desc.get(i));
        }
        return asc;
    }

    private static void appendFilters(StringBuilder where, List<Object> args,
                                      String provider, String model, String username, Boolean success) {
        if (provider != null && !provider.isBlank()) {
            where.append(" AND provider = ?");
            args.add(provider.trim());
        }
        if (model != null && !model.isBlank()) {
            where.append(" AND model = ?");
            args.add(model.trim());
        }
        if (username != null && !username.isBlank()) {
            where.append(" AND username = ?");
            args.add(username.trim());
        }
        if (success != null) {
            where.append(" AND success = ?");
            args.add(success ? 1 : 0);
        }
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    private static double round(double v, int scale) {
        double f = Math.pow(10, scale);
        return Math.round(v * f) / f;
    }
}
