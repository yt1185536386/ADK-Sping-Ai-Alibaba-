package com.hr.agent.controller.admin;

import com.hr.agent.service.AdminStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端：调用统计（模型调用量 / 用户调用量 / Token / 成功率 / 延迟）。
 *
 * <p>数据源是 llm_call_log（模型调用审计表），由 ModelUsageRecorder 异步落库。
 */
@RestController
@RequestMapping("/api/admin/stats")
public class AdminStatsController {

    private final AdminStatsService stats;

    public AdminStatsController(AdminStatsService stats) {
        this.stats = stats;
    }

    /** 首页看板：一次性返回所有图表所需数据，减少前端请求数 */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestParam(defaultValue = "7") int days) {
        int d = clamp(days);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("overview", stats.overview(d));
        body.put("trend", stats.trend(d));
        body.put("trendByModel", stats.trendByModel(d));
        body.put("byProvider", stats.byProvider(d));
        body.put("byModel", stats.byModel(d));
        body.put("byUser", stats.byUser(d));
        body.put("byModule", stats.byModule(d));
        body.put("byHour", stats.byHour());
        body.put("topErrors", stats.topErrors(d));
        body.put("failoverEvents", stats.failoverEvents(d));
        return body;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "7") int days) {
        return stats.overview(clamp(days));
    }

    @GetMapping("/trend")
    public List<Map<String, Object>> trend(@RequestParam(defaultValue = "7") int days) {
        return stats.trend(clamp(days));
    }

    /** 自定义时间范围趋势（≤24h 按小时，否则按天），缺省为今天 */
    @GetMapping("/trend/range")
    public Map<String, Object> trendRange(@RequestParam(required = false) String start,
                                          @RequestParam(required = false) String end) {
        return stats.trendRange(start, end);
    }

    @GetMapping("/provider")
    public List<Map<String, Object>> byProvider(@RequestParam(defaultValue = "7") int days) {
        return stats.byProvider(clamp(days));
    }

    @GetMapping("/model")
    public List<Map<String, Object>> byModel(@RequestParam(defaultValue = "7") int days) {
        return stats.byModel(clamp(days));
    }

    @GetMapping("/user")
    public List<Map<String, Object>> byUser(@RequestParam(defaultValue = "7") int days) {
        return stats.byUser(clamp(days));
    }

    @GetMapping("/module")
    public List<Map<String, Object>> byModule(@RequestParam(defaultValue = "7") int days) {
        return stats.byModule(clamp(days));
    }

    @GetMapping("/hour")
    public List<Map<String, Object>> byHour() {
        return stats.byHour();
    }

    @GetMapping("/errors")
    public List<Map<String, Object>> topErrors(@RequestParam(defaultValue = "7") int days) {
        return stats.topErrors(clamp(days));
    }

    /** 熔断 / 降级事件流水 */
    @GetMapping("/failover-events")
    public List<Map<String, Object>> failoverEvents(@RequestParam(defaultValue = "7") int days) {
        return stats.failoverEvents(clamp(days));
    }

    /** 调用明细（审计列表，服务端分页）：返回 {list, total, page, size} */
    @GetMapping("/calls")
    public Map<String, Object> calls(@RequestParam(defaultValue = "7") int days,
                                     @RequestParam(required = false) String provider,
                                     @RequestParam(required = false) String model,
                                     @RequestParam(required = false) String username,
                                     @RequestParam(required = false) Boolean success,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return stats.calls(clamp(days), provider, model, username, success, page, size);
    }

    /** 调用明细时间序列（图表用）：按时间升序返回窗口内全部记录（上限 5000） */
    @GetMapping("/calls/series")
    public List<Map<String, Object>> callSeries(@RequestParam(defaultValue = "7") int days,
                                                @RequestParam(required = false) String provider,
                                                @RequestParam(required = false) String model,
                                                @RequestParam(required = false) String username,
                                                @RequestParam(required = false) Boolean success) {
        return stats.callSeries(clamp(days), provider, model, username, success);
    }

    private static int clamp(int days) {
        return Math.min(Math.max(days, 1), 90);
    }
}
