package com.hr.agent.model.api;

import java.util.List;

/**
 * L2/L3 使用的渠道运行时视图（由 api_key 表一行转换而来）。
 *
 * <p>刻意与 {@code ApiKeyService.ModelChannel}（配置持久化模型）解耦：
 * 配置结构变化不应穿透到适配层与运行时层。
 */
public record ModelEndpoint(
        String provider,
        String name,
        String protocol,
        String baseUrl,
        String apiKey,
        String vendor,
        int weight,
        boolean isDefault,
        boolean enabled,
        List<String> models,
        String defaultModel,
        String embeddingModel
) {

    public boolean openaiProtocol() {
        return protocol == null || "openai".equalsIgnoreCase(protocol.trim());
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 该渠道的模型清单里是否声明了指定模型 */
    public boolean supportsModel(String model) {
        return model != null && models != null && models.contains(model);
    }

    /** 审计与日志用的唯一标识 */
    public String label() {
        return provider;
    }
}
