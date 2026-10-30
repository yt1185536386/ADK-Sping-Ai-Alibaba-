package com.hr.agent.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 外部工具配置服务：从 {@code external_tool} 表读取外部 HTTP 工具的 endpoint、鉴权信息等。
 *
 * <p>密钥（{@code api_key} 列）用 {@link ApiKeyEncryptionService} 加密存储，读取时解密；
 * 解密失败（如更换了主密钥、旧数据未加密）按"未配置"处理，避免把错误的密钥发给外部服务。
 * 与 {@link ApiKeyService}（模型渠道）职责分离：那边管"调模型"，这边管"调外部业务 API"。
 */
@Component
public class ExternalToolService {

    private final JdbcTemplate jdbc;
    private final ApiKeyEncryptionService encryptionService;

    public ExternalToolService(JdbcTemplate jdbc, ApiKeyEncryptionService encryptionService) {
        this.jdbc = jdbc;
        this.encryptionService = encryptionService;
    }

    /** 外部工具配置（对应 external_tool 表一行），apiKey 为解密后的明文 */
    public record ExternalTool(
            Long id,
            String toolKey,
            String name,
            String baseUrl,
            String authType,
            String authKeyName,
            String apiKey,
            int timeoutMs,
            boolean enabled,
            String remark
    ) {
        /** 该工具是否需要鉴权（auth_type != none） */
        public boolean needsAuth() {
            return authType != null && !"none".equalsIgnoreCase(authType.trim());
        }

        /** 是否已配置可用密钥（仅对需要鉴权的工具有意义） */
        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    /** 按工具标识读取配置（含解密后的密钥），未找到或未启用返回 empty */
    public Optional<ExternalTool> findTool(String toolKey) {
        return jdbc.queryForList(
                        "SELECT * FROM external_tool WHERE tool_key = ? AND enabled = 1", toolKey)
                .stream().findFirst().map(this::toTool);
    }

    /** 保存或更新工具密钥（加密后入库） */
    public void saveApiKey(String toolKey, String plainApiKey) {
        String encrypted = encryptionService.encrypt(plainApiKey);
        jdbc.update("UPDATE external_tool SET api_key = ? WHERE tool_key = ?", encrypted, toolKey);
    }

    /**
     * 读取工具的明文密钥（管理端保存配置时用于「未提交新密钥则沿用旧密钥」）。
     * 解密失败按空串处理。
     */
    public String plainApiKey(String toolKey) {
        List<String> values = jdbc.queryForList(
                "SELECT api_key FROM external_tool WHERE tool_key = ?", String.class, toolKey);
        if (values.isEmpty()) {
            return "";
        }
        String encrypted = values.get(0);
        if (encrypted == null || encrypted.isBlank()) {
            return "";
        }
        try {
            String plain = encryptionService.decrypt(encrypted);
            return plain == null ? "" : plain;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 读取高德前端 JS API 配置（key + 安全密钥 jscode），供下发浏览器渲染交互式地图。
     * key 解密失败/未配置时返回两个字段都为 null，前端据此提示"未配置 JS key"。
     */
    public AmapJsConfig amapJsConfig() {
        return findTool("amap_js")
                .map(t -> new AmapJsConfig(
                        t.hasApiKey() ? t.apiKey() : null,
                        t.authKeyName() == null || t.authKeyName().isBlank() ? null : t.authKeyName()))
                .orElse(new AmapJsConfig(null, null));
    }

    /** 高德前端 JS API 下发配置（明文，仅这两项本就需公开给浏览器） */
    public record AmapJsConfig(String key, String jscode) {}

    /** 保存或更新完整工具配置 */
    public void saveTool(ExternalTool tool, String plainApiKey) {
        String encrypted = encryptionService.encrypt(plainApiKey);
        jdbc.update("""
                INSERT INTO external_tool (tool_key, name, base_url, auth_type, auth_key_name, api_key, timeout_ms, enabled, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name), base_url = VALUES(base_url), auth_type = VALUES(auth_type),
                    auth_key_name = VALUES(auth_key_name), api_key = VALUES(api_key),
                    timeout_ms = VALUES(timeout_ms), enabled = VALUES(enabled), remark = VALUES(remark)
                """,
                tool.toolKey(), tool.name(), tool.baseUrl(), tool.authType(), tool.authKeyName(),
                encrypted, tool.timeoutMs(), tool.enabled(), tool.remark());
    }

    private ExternalTool toTool(Map<String, Object> r) {
        String encryptedKey = str(r.get("api_key"));
        String plainKey = null;
        if (encryptedKey != null && !encryptedKey.isBlank()) {
            try {
                plainKey = encryptionService.decrypt(encryptedKey);
            } catch (Exception e) {
                plainKey = null;
            }
        }
        return new ExternalTool(
                r.get("id") == null ? null : ((Number) r.get("id")).longValue(),
                str(r.get("tool_key")),
                str(r.get("name")),
                str(r.get("base_url")),
                str(r.get("auth_type")),
                str(r.get("auth_key_name")),
                plainKey,
                r.get("timeout_ms") == null ? 10000 : ((Number) r.get("timeout_ms")).intValue(),
                r.get("enabled") == null || ((Number) r.get("enabled")).intValue() == 1,
                str(r.get("remark"))
        );
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
