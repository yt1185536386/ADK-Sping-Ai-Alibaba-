package com.hr.agent.controller.admin;

import com.hr.agent.agents.ModuleAgent;
import com.hr.agent.config.AgentFactory;
import com.hr.agent.service.ExternalToolService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端：系统配置与审计。
 *
 * <ul>
 *   <li>{@code /tools} 外部工具（高德/天气等 MCP 工具的地址与密钥）配置</li>
 *   <li>{@code /modules} 已注册的业务模块 Agent（提示词、审批工具一览）</li>
 *   <li>{@code /approvals} HITL 人工审批记录（审计）</li>
 *   <li>{@code /threads} 会话记录（审计）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminSystemController {

    private final JdbcTemplate jdbc;
    private final ExternalToolService externalToolService;
    private final AgentFactory agentFactory;

    public AdminSystemController(JdbcTemplate jdbc, ExternalToolService externalToolService, AgentFactory agentFactory) {
        this.jdbc = jdbc;
        this.externalToolService = externalToolService;
        this.agentFactory = agentFactory;
    }

    // ==================== 外部工具 ====================

    /** 外部工具列表（脱敏） */
    @GetMapping("/tools")
    public List<Map<String, Object>> tools() {
        List<Map<String, Object>> list = new ArrayList<>();
        jdbc.queryForList("""
                SELECT id, tool_key, name, base_url, auth_type, auth_key_name, timeout_ms, enabled, remark
                  FROM external_tool ORDER BY id
                """).forEach(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.get("id"));
            m.put("toolKey", row.get("tool_key"));
            m.put("name", row.get("name"));
            m.put("baseUrl", row.get("base_url"));
            m.put("authType", row.get("auth_type"));
            m.put("authKeyName", row.get("auth_key_name"));
            m.put("timeoutMs", row.get("timeout_ms"));
            m.put("enabled", ((Number) row.get("enabled")).intValue() == 1);
            m.put("remark", row.get("remark"));
            m.put("keyConfigured", hasToolKey(String.valueOf(row.get("tool_key"))));
            list.add(m);
        });
        return list;
    }

    /** 保存外部工具配置；apiKey 为空表示不改密钥 */
    @PutMapping("/tools")
    public Map<String, Object> saveTool(@RequestBody Map<String, Object> body) {
        String toolKey = str(body.get("toolKey"));
        if (toolKey == null || toolKey.isBlank()) {
            throw new IllegalArgumentException("工具标识不能为空");
        }
        String authType = str(body.get("authType"));
        if (authType == null || authType.isBlank()) {
            authType = "none";
        }
        String apiKey = str(body.get("apiKey"));
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = existingKey(toolKey);
        }
        int timeout = body.get("timeoutMs") instanceof Number n ? n.intValue() : 10000;
        boolean enabled = body.get("enabled") == null || bool(body.get("enabled"));
        externalToolService.saveTool(new ExternalToolService.ExternalTool(
                null, toolKey,
                str(body.get("name")) == null ? toolKey : str(body.get("name")),
                str(body.get("baseUrl")) == null ? "" : str(body.get("baseUrl")),
                authType, str(body.get("authKeyName")), apiKey, timeout, enabled, str(body.get("remark"))), apiKey);
        return Map.of("ok", true);
    }

    /** 删除外部工具 */
    @DeleteMapping("/tools/{toolKey}")
    public Map<String, Object> deleteTool(@PathVariable String toolKey) {
        jdbc.update("DELETE FROM external_tool WHERE tool_key = ?", toolKey);
        return Map.of("ok", true);
    }

    // ==================== 业务模块 ====================

    /** 已注册的业务模块 Agent */
    @GetMapping("/modules")
    public List<Map<String, Object>> modules() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ModuleAgent m : agentFactory.modules()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", m.key());
            item.put("displayName", m.displayName());
            item.put("agentName", m.agentName());
            item.put("description", m.description());
            item.put("systemPrompt", m.systemPrompt());
            item.put("approvalTools", m.approvalTools());
            item.put("toolBindings", m.toolBindings().stream().map(b -> b.name()).toList());
            list.add(item);
        }
        return list;
    }

    // ==================== 审计 ====================

    /** HITL 人工审批记录 */
    @GetMapping("/approvals")
    public List<Map<String, Object>> approvals(@RequestParam(required = false) String status,
                                               @RequestParam(defaultValue = "200") int limit) {
        int lim = Math.min(Math.max(limit, 1), 500);
        if (status != null && !status.isBlank()) {
            return jdbc.queryForList("""
                    SELECT id, batch_id, thread_id, module_key, provider, model, tool_name,
                           description, status, created_at, updated_at
                      FROM hitl_approval WHERE status = ? ORDER BY created_at DESC LIMIT ?
                    """, status.trim(), lim);
        }
        return jdbc.queryForList("""
                SELECT id, batch_id, thread_id, module_key, provider, model, tool_name,
                       description, status, created_at, updated_at
                  FROM hitl_approval ORDER BY created_at DESC LIMIT ?
                """, lim);
    }

    /** 会话记录（审计） */
    @GetMapping("/threads")
    public List<Map<String, Object>> threads(@RequestParam(defaultValue = "100") int limit) {
        return jdbc.queryForList("""
                SELECT thread_id, MAX(created_at) AS last_at, COUNT(*) AS message_count,
                       SUM(CASE WHEN role = 'user' THEN 1 ELSE 0 END) AS user_messages,
                       MIN(CASE WHEN role = 'user' THEN content END) AS first_question,
                       MAX(provider) AS provider, MAX(model) AS model
                  FROM chat_message GROUP BY thread_id ORDER BY last_at DESC LIMIT ?
                """, Math.min(Math.max(limit, 1), 500));
    }

    // ==================== 辅助 ====================

    private boolean hasToolKey(String toolKey) {
        return !externalToolService.plainApiKey(toolKey).isBlank();
    }

    private String existingKey(String toolKey) {
        return externalToolService.plainApiKey(toolKey);
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static boolean bool(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Number n) {
            return n.intValue() != 0;
        }
        if (o instanceof String s) {
            return "true".equalsIgnoreCase(s) || "1".equals(s);
        }
        return true;
    }
}
