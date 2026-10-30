package com.hr.agent.model.runtime;

import com.hr.agent.service.ApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 渠道治理：熔断（circuit breaker）+ 降级链（fallback）。
 *
 * <p><b>熔断状态机</b>（每个渠道一份运行态）：
 * <pre>
 *   CLOSED  --统计窗口内失败次数达阈值-->  OPEN  --冷却期 open_seconds 到期-->  HALF_OPEN
 *      ^                                  |                                    |
 *      |                                  |                                    |
 *      +---------- 探测成功 --------------+------------- 探测失败（重新 OPEN）--+
 * </pre>
 *
 * <p>失败计数是<b>窗口内累计</b>语义（不是连续失败）：窗口 window_seconds 内累计失败达
 * failure_threshold 即熔断；窗口过期后重新计数。
 *
 * <p><b>降级链</b>：优先使用管理端配置的显式规则（{@code model_fallback_rule}）；
 * 没有配置规则的渠道沿用内置的「同模型 → 默认模型」候选链（见 ModelGateway#candidates）。
 *
 * <p>策略（阈值/窗口/冷却）存在数据库里，管理端可改，10 秒内生效；
 * 运行态在内存里，同时镜像回数据库，方便管理端直接看到当前熔断状态。
 */
@Component
public class ChannelGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(ChannelGovernanceService.class);

    /** 未配置策略的渠道使用的默认阈值 */
    private static final int DEFAULT_FAILURE_THRESHOLD = 3;
    private static final int DEFAULT_WINDOW_SECONDS = 60;
    private static final int DEFAULT_OPEN_SECONDS = 120;
    private static final int DEFAULT_HALF_OPEN_MAX = 1;

    /** 策略缓存有效期：管理端改完策略最多 10 秒后生效 */
    private static final long POLICY_TTL_MS = 10_000L;

    /** 降级规则缓存有效期：模型调用链路上读取频繁，缓存 5 秒 */
    private static final long FALLBACK_TTL_MS = 5_000L;

    /** 手动熔断的默认持续时间：一天（手动熔断需管理员手动恢复） */
    private static final long MANUAL_TRIP_MS = 24 * 60 * 60 * 1000L;

    public enum State { CLOSED, OPEN, HALF_OPEN }

    /** 熔断策略（可由管理端修改） */
    public record Policy(String provider, boolean enabled, int failureThreshold,
                          int windowSeconds, int openSeconds, int halfOpenMax) {
    }

    /** 策略 + 运行态的合并视图，供管理端展示 */
    public record Snapshot(String provider, boolean enabled, int failureThreshold, int windowSeconds,
                           int openSeconds, int halfOpenMax, State state, int failCount,
                           long openUntil, long remainingSeconds, String lastError, long lastTransitionAt) {
    }

    /** 降级规则 */
    public record FallbackRule(Long id, String provider, String targetProvider, String targetModel,
                               int priority, boolean enabled, String remark) {
    }

    private final JdbcTemplate jdbc;
    private final ApiKeyService apiKeyService;
    private final FailoverEventRecorder eventRecorder;

    /** 策略缓存：provider -> 策略 */
    private final Map<String, Policy> policies = new ConcurrentHashMap<>();
    /** 运行态：provider -> 运行态 */
    private final Map<String, Runtime> runtimes = new ConcurrentHashMap<>();
    /** 降级规则缓存：provider -> 规则 */
    private final Map<String, CachedRules> fallbackCache = new ConcurrentHashMap<>();
    private volatile long lastPolicyLoad = 0L;

    private record CachedRules(List<FallbackRule> rules, long loadedAt) {
    }

    public ChannelGovernanceService(JdbcTemplate jdbc, ApiKeyService apiKeyService,
                                    FailoverEventRecorder eventRecorder) {
        this.jdbc = jdbc;
        this.apiKeyService = apiKeyService;
        this.eventRecorder = eventRecorder;
    }

    // ==================== 运行态 ====================

    private static final class Runtime {
        final AtomicInteger failCount = new AtomicInteger();
        volatile long windowStart = System.currentTimeMillis();
        volatile State state = State.CLOSED;
        volatile long openUntil = 0L;
        volatile int halfOpenCalls = 0;
        volatile String lastError;
        volatile long lastTransitionAt = System.currentTimeMillis();
    }

    private Runtime runtime(String provider) {
        return runtimes.computeIfAbsent(provider, p -> new Runtime());
    }

    /**
     * 该渠道当前是否应被路由跳过。
     *
     * <p>CLOSED → 放行；OPEN 且未到冷却期 → 跳过；OPEN 到期 → 转半开并放行一次探测；
     * HALF_OPEN 且探测额度用尽 → 跳过（避免雪崩式重试）。
     */
    public boolean isTripped(String provider) {
        Policy policy = policy(provider);
        if (!policy.enabled()) {
            return false;
        }
        Runtime rt = runtime(provider);
        State state = rt.state;
        if (state == State.CLOSED) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (state == State.OPEN) {
            if (now < rt.openUntil) {
                return true;
            }
            transition(rt, provider, State.HALF_OPEN, null);
            rt.halfOpenCalls = 0;
        }
        if (rt.halfOpenCalls >= policy.halfOpenMax()) {
            return true;
        }
        rt.halfOpenCalls++;
        return false;
    }

    /** 调用成功：熔断/半开状态下回到 CLOSED 并清零失败计数；正常状态下不动统计窗口 */
    public void onSuccess(String provider) {
        Runtime rt = runtime(provider);
        if (rt.state != State.CLOSED) {
            transition(rt, provider, State.CLOSED, null);
            log.info("渠道 {} 熔断恢复（探测成功），已重新参与路由", provider);
        }
    }

    /** 调用失败：统计窗口内累计失败次数，达到阈值即熔断 */
    public void onFailure(String provider, String error) {
        Policy policy = policy(provider);
        if (!policy.enabled()) {
            return;
        }
        Runtime rt = runtime(provider);
        long now = System.currentTimeMillis();
        // 统计窗口过期 → 失败计数重新计数
        if (now - rt.windowStart > policy.windowSeconds() * 1000L) {
            rt.windowStart = now;
            rt.failCount.set(0);
        }
        int failures = rt.failCount.incrementAndGet();
        rt.lastError = brief(error);
        if (rt.state == State.HALF_OPEN) {
            // 半开探测失败 → 立即重新熔断
            rt.openUntil = now + policy.openSeconds() * 1000L;
            transition(rt, provider, State.OPEN, rt.lastError);
            log.warn("渠道 {} 半开探测失败，重新熔断：{}", provider, rt.lastError);
            return;
        }
        if (failures >= policy.failureThreshold()) {
            rt.openUntil = now + policy.openSeconds() * 1000L;
            transition(rt, provider, State.OPEN, rt.lastError);
            log.warn("渠道 {} 在 {}s 内失败 {} 次，触发熔断（冷却 {}s）：{}",
                    provider, policy.windowSeconds(), failures, policy.openSeconds(), rt.lastError);
            return;
        }
        persistRuntime(provider, rt);
    }

    // ==================== 策略 ====================

    private Policy policy(String provider) {
        long now = System.currentTimeMillis();
        if (now - lastPolicyLoad > POLICY_TTL_MS) {
            synchronized (this) {
                if (now - lastPolicyLoad > POLICY_TTL_MS) {
                    reloadPolicies();
                }
            }
        }
        Policy p = policies.get(provider);
        return p != null ? p : new Policy(provider, true, DEFAULT_FAILURE_THRESHOLD,
                DEFAULT_WINDOW_SECONDS, DEFAULT_OPEN_SECONDS, DEFAULT_HALF_OPEN_MAX);
    }

    private void reloadPolicies() {
        try {
            policies.clear();
            jdbc.queryForList("""
                    SELECT provider, enabled, failure_threshold, window_seconds, open_seconds, half_open_max
                    FROM model_circuit_breaker
                    """).forEach(row -> {
                String provider = str(row.get("provider"));
                if (provider == null) {
                    return;
                }
                policies.put(provider, new Policy(provider,
                        intOf(row.get("enabled"), 1) == 1,
                        intOf(row.get("failure_threshold"), DEFAULT_FAILURE_THRESHOLD),
                        intOf(row.get("window_seconds"), DEFAULT_WINDOW_SECONDS),
                        intOf(row.get("open_seconds"), DEFAULT_OPEN_SECONDS),
                        intOf(row.get("half_open_max"), DEFAULT_HALF_OPEN_MAX)));
            });
            lastPolicyLoad = System.currentTimeMillis();
        } catch (Exception e) {
            // 读不到策略不阻断调用，退回默认阈值
            log.warn("熔断策略加载失败，使用默认策略：{}", e.getMessage());
        }
    }

    /** 管理端：修改某个渠道的熔断策略 */
    public void updatePolicy(String provider, boolean enabled, Integer failureThreshold,
                             Integer windowSeconds, Integer openSeconds, Integer halfOpenMax) {
        Policy old = policy(provider);
        Policy next = new Policy(provider, enabled,
                failureThreshold == null ? old.failureThreshold() : Math.max(1, failureThreshold),
                windowSeconds == null ? old.windowSeconds() : Math.max(1, windowSeconds),
                openSeconds == null ? old.openSeconds() : Math.max(1, openSeconds),
                halfOpenMax == null ? old.halfOpenMax() : Math.max(0, halfOpenMax));
        int updated = jdbc.update("""
                UPDATE model_circuit_breaker
                   SET enabled = ?, failure_threshold = ?, window_seconds = ?, open_seconds = ?, half_open_max = ?
                 WHERE provider = ?
                """, next.enabled() ? 1 : 0, next.failureThreshold(), next.windowSeconds(),
                next.openSeconds(), next.halfOpenMax(), provider);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO model_circuit_breaker(provider, enabled, failure_threshold, window_seconds, open_seconds, half_open_max)
                    VALUES (?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE enabled = VALUES(enabled), failure_threshold = VALUES(failure_threshold),
                        window_seconds = VALUES(window_seconds), open_seconds = VALUES(open_seconds),
                        half_open_max = VALUES(half_open_max)
                    """, provider, next.enabled() ? 1 : 0, next.failureThreshold(), next.windowSeconds(),
                    next.openSeconds(), next.halfOpenMax());
        }
        policies.put(provider, next);
        lastPolicyLoad = 0L;
        log.info("渠道 {} 熔断策略已更新：{}", provider, next);
    }

    /** 管理端：手动熔断（需手动恢复） */
    public void trip(String provider) {
        Runtime rt = runtime(provider);
        rt.openUntil = System.currentTimeMillis() + MANUAL_TRIP_MS;
        rt.lastError = "管理员手动熔断";
        transition(rt, provider, State.OPEN, rt.lastError);
        log.warn("渠道 {} 被管理员手动熔断", provider);
    }

    /** 管理端：手动恢复（清零失败计数并关闭熔断） */
    public void reset(String provider) {
        Runtime rt = runtime(provider);
        rt.failCount.set(0);
        rt.openUntil = 0L;
        rt.halfOpenCalls = 0;
        rt.lastError = null;
        transition(rt, provider, State.CLOSED, null);
        log.info("渠道 {} 熔断状态已被管理员手动恢复", provider);
    }

    /** 管理端：查看全部渠道的熔断快照 */
    public List<Snapshot> snapshots() {
        List<String> providers = new ArrayList<>();
        for (ApiKeyService.ModelChannel ch : apiKeyService.findAllEnabled()) {
            providers.add(ch.provider());
        }
        try {
            jdbc.queryForList("SELECT provider FROM model_circuit_breaker")
                    .forEach(row -> {
                        String p = str(row.get("provider"));
                        if (p != null && !providers.contains(p)) {
                            providers.add(p);
                        }
                    });
        } catch (Exception ignored) {
            // 表还没建好时不影响列表
        }
        List<Snapshot> list = new ArrayList<>(providers.size());
        for (String provider : providers) {
            list.add(snapshot(provider));
        }
        return list;
    }

    public Snapshot snapshot(String provider) {
        Policy p = policy(provider);
        Runtime rt = runtime(provider);
        long now = System.currentTimeMillis();
        long remaining = rt.state == State.OPEN ? Math.max(0, rt.openUntil - now) : 0L;
        return new Snapshot(provider, p.enabled(), p.failureThreshold(), p.windowSeconds(),
                p.openSeconds(), p.halfOpenMax(), rt.state, rt.failCount.get(),
                rt.openUntil, remaining, rt.lastError, rt.lastTransitionAt);
    }

    // ==================== 降级链 ====================

    /**
     * 某个渠道的降级规则（按优先级升序，只含启用的）。
     *
     * <p>该方法在每次模型调用时都会被用到，因此带一个短 TTL 缓存，避免高频打库；
     * 管理端增删改规则时会主动失效缓存。
     */
    public List<FallbackRule> fallbackRules(String provider) {
        CachedRules cached = fallbackCache.get(provider);
        if (cached != null && System.currentTimeMillis() - cached.loadedAt() < FALLBACK_TTL_MS) {
            return cached.rules();
        }
        List<FallbackRule> rules = loadFallbackRules(provider);
        fallbackCache.put(provider, new CachedRules(rules, System.currentTimeMillis()));
        return rules;
    }

    private List<FallbackRule> loadFallbackRules(String provider) {
        try {
            return jdbc.query("""
                    SELECT id, provider, target_provider, target_model, priority, enabled, remark
                    FROM model_fallback_rule WHERE provider = ? ORDER BY priority ASC, id ASC
                    """, (rs, i) -> new FallbackRule(
                    rs.getLong("id"), rs.getString("provider"), rs.getString("target_provider"),
                    rs.getString("target_model"), rs.getInt("priority"),
                    rs.getInt("enabled") == 1, rs.getString("remark")), provider);
        } catch (Exception e) {
            // 降级表还没建好时不应阻断正常调用
            log.warn("降级规则加载失败（provider={}）：{}", provider, e.getMessage());
            return List.of();
        }
    }

    public List<FallbackRule> allFallbackRules() {
        return jdbc.query("""
                SELECT id, provider, target_provider, target_model, priority, enabled, remark
                FROM model_fallback_rule ORDER BY provider ASC, priority ASC, id ASC
                """, (rs, i) -> new FallbackRule(
                rs.getLong("id"), rs.getString("provider"), rs.getString("target_provider"),
                rs.getString("target_model"), rs.getInt("priority"),
                rs.getInt("enabled") == 1, rs.getString("remark")));
    }

    public void saveFallbackRule(Long id, String provider, String targetProvider, String targetModel,
                                 Integer priority, Boolean enabled, String remark) {
        int p = priority == null ? 0 : priority;
        boolean en = enabled == null || enabled;
        if (id != null && id > 0) {
            jdbc.update("""
                    UPDATE model_fallback_rule
                       SET provider = ?, target_provider = ?, target_model = ?, priority = ?, enabled = ?, remark = ?
                     WHERE id = ?
                    """, provider, targetProvider, blankToNull(targetModel), p, en ? 1 : 0, remark, id);
        } else {
            jdbc.update("""
                    INSERT INTO model_fallback_rule(provider, target_provider, target_model, priority, enabled, remark)
                    VALUES (?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE target_model = VALUES(target_model), priority = VALUES(priority),
                        enabled = VALUES(enabled), remark = VALUES(remark)
                    """, provider, targetProvider, blankToNull(targetModel), p, en ? 1 : 0, remark);
        }
        invalidateFallbackCache(provider);
    }

    public void deleteFallbackRule(long id) {
        List<FallbackRule> existing = jdbc.query("""
                SELECT id, provider, target_provider, target_model, priority, enabled, remark
                FROM model_fallback_rule WHERE id = ?
                """, (rs, i) -> new FallbackRule(
                rs.getLong("id"), rs.getString("provider"), rs.getString("target_provider"),
                rs.getString("target_model"), rs.getInt("priority"),
                rs.getInt("enabled") == 1, rs.getString("remark")), id);
        jdbc.update("DELETE FROM model_fallback_rule WHERE id = ?", id);
        existing.forEach(r -> invalidateFallbackCache(r.provider()));
    }

    /** 降级规则变更后立即失效缓存 */
    public void invalidateFallbackCache(String provider) {
        if (provider != null) {
            fallbackCache.remove(provider);
        }
    }

    // ==================== 内部 ====================

    private void transition(Runtime rt, String provider, State target, String error) {
        rt.state = target;
        rt.lastError = error != null ? error : rt.lastError;
        rt.lastTransitionAt = System.currentTimeMillis();
        if (target == State.CLOSED) {
            rt.failCount.set(0);
            rt.openUntil = 0L;
            rt.halfOpenCalls = 0;
        }
        if (target == State.OPEN) {
            // 熔断触发事件：created_at 即熔断时间，管理端据此观察「何时熔断、因何熔断」
            eventRecorder.record("breaker_trip", provider, null, null, null,
                    rt.failCount.get(), null, null, rt.lastError);
        }
        persistRuntime(provider, rt);
    }

    /** 把运行态镜像回数据库（管理端据此展示当前熔断状态） */
    private void persistRuntime(String provider, Runtime rt) {
        try {
            Timestamp openUntil = rt.openUntil > 0 ? new Timestamp(rt.openUntil) : null;
            int updated = jdbc.update("""
                    UPDATE model_circuit_breaker
                       SET state = ?, fail_count = ?, open_until = ?, last_error = ?, last_transition_at = ?
                     WHERE provider = ?
                    """, rt.state.name(), rt.failCount.get(), openUntil, brief(rt.lastError),
                    new Timestamp(rt.lastTransitionAt), provider);
            if (updated == 0) {
                jdbc.update("""
                        INSERT INTO model_circuit_breaker(provider, enabled, state, fail_count, open_until, last_error, last_transition_at)
                        VALUES (?,?,?,?,?,?,?)
                        """, provider, 1, rt.state.name(), rt.failCount.get(), openUntil,
                        brief(rt.lastError), new Timestamp(rt.lastTransitionAt));
            }
        } catch (Exception e) {
            log.debug("熔断状态落库失败：{}", e.getMessage());
        }
    }

    private static String brief(String error) {
        if (error == null) {
            return null;
        }
        String flat = error.replaceAll("\\s+", " ").trim();
        return flat.length() > 500 ? flat.substring(0, 500) : flat;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static int intOf(Object o, int def) {
        return o instanceof Number n ? n.intValue() : def;
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    /** 便于管理端组装 Map（避免 Map.of 不支持 null）；时间统一为 yyyy-MM-dd HH:mm:ss */
    public static Map<String, Object> toMap(Snapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", s.provider());
        m.put("enabled", s.enabled());
        m.put("failureThreshold", s.failureThreshold());
        m.put("windowSeconds", s.windowSeconds());
        m.put("openSeconds", s.openSeconds());
        m.put("halfOpenMax", s.halfOpenMax());
        m.put("state", s.state().name());
        m.put("failCount", s.failCount());
        m.put("openUntil", fmtTs(s.openUntil()));
        m.put("remainingSeconds", s.remainingSeconds() / 1000);
        m.put("lastError", s.lastError());
        m.put("lastTransitionAt", fmtTs(s.lastTransitionAt()));
        return m;
    }

    private static final java.time.format.DateTimeFormatter TS_FMT =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String fmtTs(long epochMs) {
        return epochMs > 0 ? new Timestamp(epochMs).toLocalDateTime().format(TS_FMT) : null;
    }
}
