package com.hr.agent.controller.admin;

import com.hr.agent.model.runtime.ChannelGovernanceService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端：模型熔断降级管理。
 *
 * <ul>
 *   <li>{@code /circuit-breakers} 熔断策略与当前运行态（含手动熔断 / 手动恢复）</li>
 *   <li>{@code /fallback-rules} 降级链规则（主渠道 → 备用渠道）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminCircuitBreakerController {

    private final ChannelGovernanceService governance;

    public AdminCircuitBreakerController(ChannelGovernanceService governance) {
        this.governance = governance;
    }

    // ==================== 熔断 ====================

    /** 全部渠道的熔断策略 + 运行态 */
    @GetMapping("/circuit-breakers")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ChannelGovernanceService.Snapshot s : governance.snapshots()) {
            list.add(ChannelGovernanceService.toMap(s));
        }
        return list;
    }

    /** 修改熔断策略（阈值 / 统计窗口 / 冷却时长 / 探测次数 / 是否启用） */
    @PutMapping("/circuit-breakers/{provider}")
    public Map<String, Object> update(@PathVariable String provider,
                                      @RequestBody Map<String, Object> body) {
        governance.updatePolicy(provider,
                bool(body.get("enabled"), true),
                intOf(body.get("failureThreshold")),
                intOf(body.get("windowSeconds")),
                intOf(body.get("openSeconds")),
                intOf(body.get("halfOpenMax")));
        return Map.of("ok", true);
    }

    /** 手动熔断：立刻把该渠道摘除（需手动恢复） */
    @PostMapping("/circuit-breakers/{provider}/trip")
    public Map<String, Object> trip(@PathVariable String provider) {
        governance.trip(provider);
        return Map.of("ok", true);
    }

    /** 手动恢复：关闭熔断并清零失败计数 */
    @PostMapping("/circuit-breakers/{provider}/reset")
    public Map<String, Object> reset(@PathVariable String provider) {
        governance.reset(provider);
        return Map.of("ok", true);
    }

    // ==================== 降级链 ====================

    /** 降级规则列表；带 provider 参数时只返回该渠道的规则 */
    @GetMapping("/fallback-rules")
    public List<ChannelGovernanceService.FallbackRule> rules() {
        return governance.allFallbackRules();
    }

    /** 新增/更新降级规则 */
    @PostMapping("/fallback-rules")
    public Map<String, Object> save(@RequestBody Map<String, Object> body) {
        String provider = str(body.get("provider"));
        String target = str(body.get("targetProvider"));
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("主渠道不能为空");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("降级目标渠道不能为空");
        }
        if (provider.equals(target)) {
            throw new IllegalArgumentException("降级目标渠道不能与主渠道相同");
        }
        Long id = body.get("id") == null ? null : ((Number) body.get("id")).longValue();
        governance.saveFallbackRule(id, provider, target, str(body.get("targetModel")),
                intOf(body.get("priority")), body.get("enabled") == null ? Boolean.TRUE : bool(body.get("enabled"), true),
                str(body.get("remark")));
        return Map.of("ok", true);
    }

    /** 删除降级规则 */
    @DeleteMapping("/fallback-rules/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        governance.deleteFallbackRule(id);
        return Map.of("ok", true);
    }

    // ==================== 辅助 ====================

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Integer intOf(Object o) {
        return o instanceof Number n ? n.intValue() : null;
    }

    private static boolean bool(Object o, boolean def) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.intValue() != 0;
        }
        if (o instanceof String s) {
            return "true".equalsIgnoreCase(s) || "1".equals(s);
        }
        return def;
    }

    /** 供前端展示用的降级规则视图（附带主/目标渠道名称） */
    @GetMapping("/fallback-rules/view")
    public List<Map<String, Object>> rulesView() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ChannelGovernanceService.FallbackRule r : governance.allFallbackRules()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.id());
            m.put("provider", r.provider());
            m.put("targetProvider", r.targetProvider());
            m.put("targetModel", r.targetModel());
            m.put("priority", r.priority());
            m.put("enabled", r.enabled());
            m.put("remark", r.remark());
            list.add(m);
        }
        return list;
    }
}
