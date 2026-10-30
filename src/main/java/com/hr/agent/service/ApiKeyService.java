package com.hr.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 从数据库 api_key（模型渠道配置）表读取各来源的密钥与模型参数。
 * 项目中不保存任何明文 Key，也不依赖环境变量（环境变量仅作兜底）。
 * 密钥在数据库中加密存储，读取时解密。
 */
@Component
public class ApiKeyService {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final ApiKeyEncryptionService encryptionService;

    public ApiKeyService(JdbcTemplate jdbc, ApiKeyEncryptionService encryptionService) {
        this.jdbc = jdbc;
        this.encryptionService = encryptionService;
    }

    /** 渠道配置（对应 api_key 表一行） */
    public record ModelChannel(
            Long id,
            String provider,
            String name,
            String protocol,
            String authType,
            String apiKey,  // 解密后的明文密钥（仅内存中短暂存在）
            String baseUrl,
            String modelsJson,
            String defaultModel,
            String embeddingModel,
            String vendor,
            int weight,
            boolean isDefault,
            String extFlag,
            boolean enabled,
            String source,
            String remark
    ) {
        public boolean openaiProtocol() {
            return protocol == null || "openai".equalsIgnoreCase(protocol.trim());
        }

        public boolean hasApiKey() {
            return apiKey != null && !apiKey.isBlank();
        }

        /** 该渠道声明的全部模型（含向量模型） */
        public List<String> allModels() {
            List<String> list = new ArrayList<>();
            if (modelsJson != null && !modelsJson.isBlank()) {
                try {
                    List<?> raw = MAPPER.readValue(modelsJson, List.class);
                    for (Object o : raw) {
                        if (o != null && !o.toString().isBlank()) {
                            list.add(o.toString().trim());
                        }
                    }
                } catch (Exception ignored) {
                    // 解析失败时按逗号兜底拆分
                    for (String s : modelsJson.replaceAll("[\\[\\]\"]", "").split(",")) {
                        if (!s.isBlank()) {
                            list.add(s.trim());
                        }
                    }
                }
            }
            if (defaultModel != null && !defaultModel.isBlank() && !list.contains(defaultModel)) {
                list.add(0, defaultModel);
            }
            return list;
        }

        /** 推理（对话）模型：排除向量模型 */
        public List<String> chatModels() {
            List<String> result = new ArrayList<>();
            for (String m : allModels()) {
                if (!isEmbeddingModel(m)) {
                    result.add(m);
                }
            }
            return result;
        }

        /** 向量模型：显式配置的 embedding_model，或名称含 embedding 的模型 */
        public List<String> embeddingModels() {
            List<String> result = new ArrayList<>();
            if (embeddingModel != null && !embeddingModel.isBlank()) {
                result.add(embeddingModel.trim());
            }
            for (String m : allModels()) {
                if (isEmbeddingModel(m) && !result.contains(m)) {
                    result.add(m);
                }
            }
            return result;
        }

        public static boolean isEmbeddingModel(String model) {
            return model != null && model.toLowerCase().contains("embedding");
        }
    }

    /** 全部启用的渠道，按权重倒序、id 升序 */
    public List<ModelChannel> findAllEnabled() {
        return jdbc.queryForList("SELECT * FROM api_key WHERE enabled = 1 ORDER BY weight DESC, id ASC")
                .stream().map(this::toChannel).toList();
    }

    /** 全部渠道（含停用），管理端列表用；默认渠道排最前 */
    public List<ModelChannel> findAll() {
        return jdbc.queryForList("SELECT * FROM api_key ORDER BY is_default DESC, weight DESC, id ASC")
                .stream().map(this::toChannel).toList();
    }

    /** 删除渠道（管理端） */
    public void deleteChannel(String provider) {
        jdbc.update("DELETE FROM api_key WHERE provider = ?", provider);
    }

    /**
     * 渠道配置的轻量版本签名，用于模型实例热更新探测。
     *
     * <p>把全部渠道的关键字段拼起来取哈希：新增/删除渠道、改密钥、改地址、改默认模型、
     * 启停渠道都会让签名变化，{@code ModelInstanceRegistry} 据此清空实例缓存，
     * 无需重启后端即可让配置生效。
     */
    public String channelsSignature() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT provider, protocol, base_url, api_key, models, default_model,
                           embedding_model, enabled, weight, is_default
                    FROM api_key ORDER BY provider
                    """);
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> row : rows) {
                sb.append(row).append('|');
            }
            return Integer.toHexString(sb.toString().hashCode()) + ":" + rows.size();
        } catch (Exception e) {
            // 探测失败不阻断调用，返回固定值避免误清缓存
            return "unavailable";
        }
    }

    /** 按来源标识读取渠道配置 */
    public Optional<ModelChannel> findChannel(String provider) {
        return jdbc.queryForList("SELECT * FROM api_key WHERE provider = ?", provider)
                .stream().findFirst().map(this::toChannel);
    }

    /** 默认渠道：is_default=1 优先，否则权重最高 */
    public Optional<ModelChannel> findDefaultChannel() {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM api_key WHERE enabled = 1 ORDER BY is_default DESC, weight DESC, id ASC LIMIT 1");
        return rows.stream().findFirst().map(this::toChannel);
    }

    /** 某个来源是否已配置可用密钥 */
    public boolean isConfigured(String provider) {
        return findChannel(provider).filter(ModelChannel::hasApiKey).isPresent();
    }

    /**
     * 保存或更新渠道密钥（加密后入库）
     *
     * @param provider 渠道标识
     * @param plainApiKey 明文 API Key
     */
    public void saveApiKey(String provider, String plainApiKey) {
        String encrypted = encryptionService.encrypt(plainApiKey);
        jdbc.update("UPDATE api_key SET api_key = ? WHERE provider = ?", encrypted, provider);
    }

    /**
     * 保存或更新完整渠道配置
     */
    public void saveChannel(ModelChannel channel, String plainApiKey) {
        String encrypted = encryptionService.encrypt(plainApiKey);
        jdbc.update("""
                INSERT INTO api_key (provider, name, protocol, auth_type, api_key, base_url, models, default_model, embedding_model, vendor, weight, is_default, enabled, source, remark)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name), protocol = VALUES(protocol), auth_type = VALUES(auth_type),
                    api_key = VALUES(api_key), base_url = VALUES(base_url), models = VALUES(models),
                    default_model = VALUES(default_model), embedding_model = VALUES(embedding_model),
                    vendor = VALUES(vendor), weight = VALUES(weight), is_default = VALUES(is_default),
                    enabled = VALUES(enabled), source = VALUES(source), remark = VALUES(remark)
                """,
                channel.provider(), channel.name(), channel.protocol(), channel.authType(),
                encrypted, channel.baseUrl(), channel.modelsJson(), channel.defaultModel(),
                channel.embeddingModel(), channel.vendor(), channel.weight(), channel.isDefault(),
                channel.enabled(), channel.source(), channel.remark());
    }

    private ModelChannel toChannel(Map<String, Object> r) {
        String encryptedKey = str(r.get("api_key"));
        String plainKey = null;
        if (encryptedKey != null && !encryptedKey.isBlank()) {
            try {
                plainKey = encryptionService.decrypt(encryptedKey);
            } catch (Exception e) {
                // 解密失败：可能是旧数据未加密，或密钥不匹配
                // 返回空，让 hasApiKey() 返回 false，避免使用错误的密钥
                plainKey = null;
            }
        }
        return new ModelChannel(
                r.get("id") == null ? null : ((Number) r.get("id")).longValue(),
                str(r.get("provider")),
                str(r.get("name")),
                str(r.get("protocol")),
                str(r.get("auth_type")),
                plainKey,
                str(r.get("base_url")),
                str(r.get("models")),
                str(r.get("default_model")),
                str(r.get("embedding_model")),
                str(r.get("vendor")),
                r.get("weight") == null ? 0 : ((Number) r.get("weight")).intValue(),
                r.get("is_default") != null && ((Number) r.get("is_default")).intValue() == 1,
                str(r.get("ext_flag")),
                r.get("enabled") == null || ((Number) r.get("enabled")).intValue() == 1,
                str(r.get("source")),
                str(r.get("remark"))
        );
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
