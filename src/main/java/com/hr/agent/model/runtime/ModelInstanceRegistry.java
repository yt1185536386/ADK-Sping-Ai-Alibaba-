package com.hr.agent.model.runtime;

import com.hr.agent.model.adapter.ChatModelAdapter;
import com.hr.agent.model.api.ModelEndpoint;
import com.hr.agent.model.api.ModelSpec;
import com.hr.agent.service.ApiKeyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * L3 模型运行时层：模型实例注册表。
 *
 * <p>两件事：
 * <ol>
 *   <li><b>实例缓存</b>：按「渠道 + 模型 + 协议 + 地址 + 密钥指纹」缓存模型实例；</li>
 *   <li><b>配置热更新</b>：每 10 秒最多校验一次 {@code api_key} 表的版本签名，
 *       发现变更即清空缓存并重新构建，改密钥/换地址/启停渠道都不再需要重启后端。</li>
 * </ol>
 *
 * <p>密钥解析（含环境变量兜底）也在本层完成，实例拿到的一定是可用的密钥字符串。
 */
@Component
public class ModelInstanceRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelInstanceRegistry.class);
    /** 配置变更探测的最小间隔：太短会频繁查库，太长会让热更新体感变慢 */
    private static final long CHECK_INTERVAL_MS = 10_000L;

    private final Map<String, ChatModelAdapter> adaptersByProtocol;
    private final KeyResolver keyResolver;
    private final ApiKeyService apiKeyService;

    private final Map<String, ChatModel> chatCache = new ConcurrentHashMap<>();
    private final Map<String, EmbeddingModel> embeddingCache = new ConcurrentHashMap<>();
    private volatile String signature = "";
    private volatile long lastCheck = 0L;

    public ModelInstanceRegistry(List<ChatModelAdapter> adapters,
                                 KeyResolver keyResolver,
                                 ApiKeyService apiKeyService) {
        this.adaptersByProtocol = adapters.stream()
                .collect(Collectors.toMap(a -> a.protocol().toLowerCase(Locale.ROOT), a -> a, (a, b) -> a));
        this.keyResolver = keyResolver;
        this.apiKeyService = apiKeyService;
        log.info("模型适配器已注册：{}", adaptersByProtocol.keySet());
    }

    public ChatModel chat(ModelEndpoint endpoint, ModelSpec spec) {
        refreshIfStale();
        String key = cacheKey("chat", endpoint, spec);
        ChatModel cached = chatCache.get(key);
        return cached != null ? cached : chatCache.computeIfAbsent(key, k -> createChat(endpoint, spec));
    }

    public EmbeddingModel embedding(ModelEndpoint endpoint, ModelSpec spec) {
        refreshIfStale();
        String key = cacheKey("embedding", endpoint, spec);
        EmbeddingModel cached = embeddingCache.get(key);
        return cached != null ? cached : embeddingCache.computeIfAbsent(key, k -> createEmbedding(endpoint, spec));
    }

    /** 显式失效（管理入口改完渠道配置后可直接调用，不必等探测周期） */
    public void invalidateAll() {
        synchronized (this) {
            chatCache.clear();
            embeddingCache.clear();
            signature = "";
            lastCheck = 0L;
        }
        log.info("模型实例缓存已手动清空");
    }

    private ChatModel createChat(ModelEndpoint endpoint, ModelSpec spec) {
        ModelEndpoint resolved = withKey(endpoint);
        return adapter(resolved).createChat(resolved, spec);
    }

    private EmbeddingModel createEmbedding(ModelEndpoint endpoint, ModelSpec spec) {
        ModelEndpoint resolved = withKey(endpoint);
        return adapter(resolved).createEmbedding(resolved, spec);
    }

    private ModelEndpoint withKey(ModelEndpoint endpoint) {
        if (endpoint.hasApiKey()) {
            return endpoint;
        }
        return new ModelEndpoint(endpoint.provider(), endpoint.name(), endpoint.protocol(),
                endpoint.baseUrl(), keyResolver.resolve(endpoint), endpoint.vendor(),
                endpoint.weight(), endpoint.isDefault(), endpoint.enabled(),
                endpoint.models(), endpoint.defaultModel(), endpoint.embeddingModel());
    }

    private ChatModelAdapter adapter(ModelEndpoint endpoint) {
        ChatModelAdapter adapter = adaptersByProtocol.get(endpoint.protocol().toLowerCase(Locale.ROOT));
        if (adapter == null) {
            throw new IllegalArgumentException("不支持的模型协议：" + endpoint.protocol()
                    + "，已注册：" + adaptersByProtocol.keySet());
        }
        return adapter;
    }

    private void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastCheck < CHECK_INTERVAL_MS) {
            return;
        }
        synchronized (this) {
            if (now - lastCheck < CHECK_INTERVAL_MS) {
                return;
            }
            lastCheck = now;
            String latest = apiKeyService.channelsSignature();
            if (latest.equals(signature)) {
                return;
            }
            if (!signature.isEmpty() && !"unavailable".equals(latest) && !"unavailable".equals(signature)) {
                log.info("检测到渠道配置变更（{} → {}），清空模型实例缓存", signature, latest);
                chatCache.clear();
                embeddingCache.clear();
            }
            signature = latest;
        }
    }

    private static String cacheKey(String scope, ModelEndpoint endpoint, ModelSpec spec) {
        return scope + "::" + endpoint.provider()
                + "::" + spec.model()
                + "::" + endpoint.protocol()
                + "::" + endpoint.baseUrl()
                + "::" + Integer.toHexString(String.valueOf(endpoint.apiKey()).hashCode());
    }
}
