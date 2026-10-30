package com.hr.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.model.gateway.ModelGateway;
import com.hr.agent.model.runtime.ModelCallContext;
import com.hr.agent.service.ApiKeyService.ModelChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端「模型来源管理」服务：渠道（api_key 表）的增删改查 + 连通性测试。
 *
 * <p>安全约束：任何出口都不返回明文密钥，只返回是否已配置（keyConfigured）。
 * 密钥只在保存时由前端提交一次，服务端加密入库。
 */
@Component
public class AdminChannelService {

    private static final Logger log = LoggerFactory.getLogger(AdminChannelService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApiKeyService apiKeyService;
    private final ModelGateway modelGateway;
    private final JdbcTemplate jdbc;

    public AdminChannelService(ApiKeyService apiKeyService, ModelGateway modelGateway, JdbcTemplate jdbc) {
        this.apiKeyService = apiKeyService;
        this.modelGateway = modelGateway;
        this.jdbc = jdbc;
    }

    /** 渠道表单（管理端提交） */
    public record ChannelForm(String provider, String name, String protocol, String authType,
                              String baseUrl, List<String> models, String defaultModel, String embeddingModel,
                              String vendor, Integer weight, Boolean isDefault, Boolean enabled,
                              String source, String remark, String apiKey) {
    }

    /** 渠道列表（脱敏） */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ModelChannel ch : apiKeyService.findAll()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", ch.provider());
            m.put("name", ch.name());
            m.put("protocol", ch.protocol());
            m.put("authType", ch.authType());
            m.put("baseUrl", ch.baseUrl());
            m.put("models", ch.allModels());
            m.put("chatModels", ch.chatModels());
            m.put("embeddingModels", ch.embeddingModels());
            m.put("defaultModel", ch.defaultModel());
            m.put("embeddingModel", ch.embeddingModel());
            m.put("vendor", ch.vendor());
            m.put("weight", ch.weight());
            m.put("isDefault", ch.isDefault());
            m.put("enabled", ch.enabled());
            m.put("source", ch.source());
            m.put("remark", ch.remark());
            m.put("keyConfigured", ch.hasApiKey());
            list.add(m);
        }
        return list;
    }

    /** 新增或更新渠道；apiKey 为空表示不改密钥 */
    public void save(ChannelForm f) {
        String provider = f.provider() == null ? null : f.provider().trim();
        if (provider == null || provider.isBlank()) {
            throw new IllegalArgumentException("渠道标识（provider）不能为空");
        }
        ModelChannel existing = apiKeyService.findChannel(provider).orElse(null);
        String plainKey = f.apiKey() == null ? null : f.apiKey().trim();
        if ((plainKey == null || plainKey.isEmpty()) && existing != null) {
            plainKey = existing.apiKey();  // 未提交新密钥则沿用旧密钥
        }
        if (plainKey == null || plainKey.isEmpty()) {
            plainKey = "";                  // 新建渠道允许先不配密钥
        }
        String modelsJson = toJson(f.models());
        boolean isDefault = Boolean.TRUE.equals(f.isDefault());
        ModelChannel channel = new ModelChannel(
                existing == null ? null : existing.id(),
                provider,
                f.name() == null || f.name().isBlank() ? provider : f.name().trim(),
                f.protocol() == null || f.protocol().isBlank() ? "openai" : f.protocol().trim(),
                f.authType() == null || f.authType().isBlank() ? "bearer" : f.authType().trim(),
                plainKey,
                blankToNull(f.baseUrl()),
                modelsJson,
                blankToNull(f.defaultModel()),
                blankToNull(f.embeddingModel()),
                blankToNull(f.vendor()),
                f.weight() == null ? 0 : f.weight(),
                isDefault,
                existing == null ? null : existing.extFlag(),
                f.enabled() == null || f.enabled(),
                blankToNull(f.source()),
                blankToNull(f.remark()));
        apiKeyService.saveChannel(channel, plainKey);
        if (isDefault) {
            jdbc.update("UPDATE api_key SET is_default = 0 WHERE provider <> ?", provider);
        }
        // 渠道配置变了 → 让模型实例缓存立即失效（否则要等 10 秒探测周期）
        modelGateway.invalidate();
        log.info("渠道配置已保存：{}（默认={}，启用={}）", provider, isDefault, channel.enabled());
    }

    public void delete(String provider) {
        Integer used = jdbc.queryForObject("SELECT COUNT(*) FROM model_fallback_rule WHERE provider = ? OR target_provider = ?",
                Integer.class, provider, provider);
        if (used != null && used > 0) {
            throw new IllegalStateException("该渠道仍被 " + used + " 条降级规则引用，请先删除相关降级规则");
        }
        apiKeyService.deleteChannel(provider);
        modelGateway.invalidate();
        log.info("渠道已删除：{}", provider);
    }

    /**
     * 从渠道的 OpenAI 兼容 {@code /models} 端点拉取模型列表，供管理端表单下拉选择。
     * 未提交新密钥/地址时回退到该渠道已保存的配置；base_url 是否以 /v1 结尾决定补全路径。
     */
    public Map<String, Object> fetchModels(String provider, String baseUrl, String apiKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            String key = apiKey == null || apiKey.isBlank() ? null : apiKey.trim();
            String url = baseUrl == null || baseUrl.isBlank() ? null : baseUrl.trim();
            if (provider != null && !provider.isBlank()) {
                ModelChannel ch = apiKeyService.findChannel(provider.trim()).orElse(null);
                if (ch != null) {
                    if (key == null) {
                        key = ch.apiKey();
                    }
                    if (url == null) {
                        url = ch.baseUrl();
                    }
                }
            }
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("请先填写接口地址（baseUrl）");
            }
            String trimmed = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
            String modelsUrl = trimmed.endsWith("/v1") ? trimmed + "/models" : trimmed + "/v1/models";
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(5_000);
            factory.setReadTimeout(15_000);
            RestClient.RequestHeadersSpec<?> req = RestClient.builder()
                    .requestFactory(factory).build().get().uri(modelsUrl);
            if (key != null && !key.isBlank()) {
                req = req.header("Authorization", "Bearer " + key);
            }
            Map<?, ?> resp = req.retrieve().body(Map.class);
            List<String> ids = new ArrayList<>();
            Object data = resp == null ? null : resp.get("data");
            if (data instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof Map<?, ?> m && m.get("id") != null) {
                        ids.add(m.get("id").toString());
                    }
                }
            }
            body.put("ok", true);
            body.put("models", ids);
        } catch (Exception e) {
            body.put("ok", false);
            body.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
            body.put("models", List.of());
        }
        return body;
    }

    /** 连通性测试：用该渠道发一条极短消息，验证密钥/地址/模型是否可用 */
    public Map<String, Object> test(String provider, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            // 标记调用来源，审计/统计里能把自检调用与真实业务调用区分开
            ModelCallContext.enter("channel_test", null, currentUsername());
            ChatModel chatModel = modelGateway.chatModel(provider, model);
            String text = chatModel.call(new Prompt(List.of(new UserMessage("hi"))))
                    .getResult().getOutput().getText();
            body.put("ok", true);
            body.put("sample", text == null ? "" : (text.length() > 200 ? text.substring(0, 200) : text));
        } catch (Exception e) {
            body.put("ok", false);
            body.put("error", e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            ModelCallContext.clear();
        }
        return body;
    }

    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        String name = auth.getName();
        return (name == null || "anonymousUser".equals(name)) ? null : name;
    }

    private static String toJson(List<String> models) {
        if (models == null) {
            return null;
        }
        List<String> clean = models.stream()
                .filter(m -> m != null && !m.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        try {
            return MAPPER.writeValueAsString(clean);
        } catch (Exception e) {
            throw new IllegalArgumentException("模型列表格式不正确");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
