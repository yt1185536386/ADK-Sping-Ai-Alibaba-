package com.hr.mcpserver.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 外部工具配置服务（只读）：从 {@code external_tool} 表读取外部 HTTP 工具的 endpoint、鉴权信息等。
 *
 * <p><b>有意重复（accepted duplication）</b>：是主应用
 * {@code com.hr.agent.service.ExternalToolService} 的只读子集——
 * 写操作（saveApiKey/saveTool）与前端 JS key 下发（amapJsConfig）留在主应用。
 * external_tool 表结构或解密语义改动必须两边同步。
 *
 * <p>密钥（{@code api_key} 列）用 {@link ApiKeyEncryptionService} 加密存储，读取时解密；
 * 解密失败按"未配置"处理，避免把错误的密钥发给外部服务。
 */
@Component
public class ExternalToolConfigService {

    private final JdbcTemplate jdbc;
    private final ApiKeyEncryptionService encryptionService;

    public ExternalToolConfigService(JdbcTemplate jdbc, ApiKeyEncryptionService encryptionService) {
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
