package com.hr.agent.model.gateway;

import com.hr.agent.model.api.ModelEndpoint;
import com.hr.agent.model.api.ModelSpec;
import com.hr.agent.model.runtime.ChannelGovernanceService;
import com.hr.agent.model.runtime.FailoverChatModel;
import com.hr.agent.model.runtime.FailoverEventRecorder;
import com.hr.agent.model.runtime.MeteredChatModel;
import com.hr.agent.model.runtime.ModelInstanceRegistry;
import com.hr.agent.model.runtime.ModelUsageRecorder;
import com.hr.agent.service.ApiKeyService;
import com.hr.agent.service.ApiKeyService.ModelChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * L1 模型接入层：上层（AgentFactory / 业务）唯一可见的模型入口。
 *
 * <p>职责：
 * <ul>
 *   <li>渠道解析与模型名裁决（含默认渠道、默认模型）；</li>
 *   <li>候选链构造：主渠道失败时自动降级到其他可用渠道；</li>
 *   <li>把 L3 实例包装成「计量 + 故障转移」组合，屏蔽底层协议与连接细节。</li>
 * </ul>
 *
 * <p>上层不再关心 openai / dashscope 的差别，也不再自己 new 模型实例。
 */
@Component
public class ModelGateway {

    private static final Logger log = LoggerFactory.getLogger(ModelGateway.class);

    private final ApiKeyService apiKeyService;
    private final ModelInstanceRegistry registry;
    private final ModelUsageRecorder recorder;
    private final ChannelGovernanceService governance;
    private final FailoverEventRecorder failoverEventRecorder;

    public ModelGateway(ApiKeyService apiKeyService,
                        ModelInstanceRegistry registry,
                        ModelUsageRecorder recorder,
                        ChannelGovernanceService governance,
                        FailoverEventRecorder failoverEventRecorder) {
        this.apiKeyService = apiKeyService;
        this.registry = registry;
        this.recorder = recorder;
        this.governance = governance;
        this.failoverEventRecorder = failoverEventRecorder;
    }

    /** 一个候选：渠道 + 该渠道上要使用的模型 */
    public record Candidate(ModelEndpoint endpoint, String model) {
        public String label() {
            return endpoint.provider() + "::" + model;
        }
    }

    // ==================== 渠道与模型裁决 ====================

    /** 解析渠道（provider 为空时取默认渠道） */
    public ModelEndpoint resolveEndpoint(String provider) {
        ModelChannel channel;
        if (provider == null || provider.isBlank()) {
            channel = apiKeyService.findDefaultChannel()
                    .orElseThrow(() -> new IllegalStateException("api_key 表中没有启用的渠道，请先配置渠道"));
        } else {
            channel = apiKeyService.findChannel(provider)
                    .orElseThrow(() -> new IllegalArgumentException("api_key 表中不存在来源：" + provider));
        }
        return toEndpoint(channel);
    }

    public ModelEndpoint toEndpoint(ModelChannel channel) {
        String embedding = channel.embeddingModels().stream().findFirst().orElse(null);
        return new ModelEndpoint(
                channel.provider(),
                channel.name(),
                channel.protocol(),
                channel.baseUrl(),
                channel.apiKey(),
                channel.vendor(),
                channel.weight(),
                channel.isDefault(),
                channel.enabled(),
                channel.allModels(),
                channel.defaultModel(),
                embedding);
    }

    /** 裁决实际使用的模型名（model 为空时取渠道默认模型） */
    public String resolveModel(ModelEndpoint endpoint, String model) {
        String useModel = (model == null || model.isBlank()) ? endpoint.defaultModel() : model;
        if (useModel == null || useModel.isBlank()) {
            throw new IllegalArgumentException("渠道 " + endpoint.provider()
                    + " 未配置默认模型，请指定 model 或设置 default_model");
        }
        return useModel;
    }

    // ==================== 模型获取 ====================

    public ChatModel chatModel(String provider, String model) {
        ModelEndpoint primary = resolveEndpoint(provider);
        return chatModel(primary, resolveModel(primary, model));
    }

    /** 已持有渠道对象时直接用，避免重复查库 */
    public ChatModel chatModel(ModelChannel channel, String model) {
        ModelEndpoint primary = toEndpoint(channel);
        return chatModel(primary, resolveModel(primary, model));
    }

    public ChatModel chatModel(ModelEndpoint primary, String primaryModel) {
        List<Candidate> candidates = candidates(primary, primaryModel);
        List<ChatModel> delegates = new ArrayList<>(candidates.size());
        List<String> labels = new ArrayList<>(candidates.size());
        List<String> providers = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            ChatModel base = registry.chat(candidate.endpoint(), ModelSpec.of(candidate.model()));
            delegates.add(new MeteredChatModel(base, recorder,
                    candidate.endpoint().provider(), candidate.model(), candidate.endpoint().protocol()));
            labels.add(candidate.label());
            providers.add(candidate.endpoint().provider());
        }
        if (delegates.isEmpty()) {
            throw new IllegalStateException("没有可用的模型渠道");
        }
        if (delegates.size() == 1) {
            return delegates.get(0);
        }
        log.info("模型路由：主渠道 {}，故障转移候选 {}", labels.get(0), labels.subList(1, labels.size()));
        return new FailoverChatModel(delegates, labels, providers, governance, failoverEventRecorder);
    }

    public EmbeddingModel embeddingModel(String provider, String model) {
        ModelEndpoint endpoint = resolveEndpoint(provider);
        String useModel = model;
        if (useModel == null || useModel.isBlank()) {
            useModel = endpoint.embeddingModel();
        }
        if (useModel == null || useModel.isBlank()) {
            throw new IllegalArgumentException("渠道 " + endpoint.provider()
                    + " 未配置向量模型（embedding_model 为空）");
        }
        return registry.embedding(endpoint, ModelSpec.of(useModel));
    }

    /** 清空模型实例缓存（渠道配置变更后可显式调用，无需等待探测周期） */
    public void invalidate() {
        registry.invalidateAll();
    }

    // ==================== 候选链 ====================

    /**
     * 构造故障转移候选链：
     * <ol>
     *   <li>主渠道 + 请求指定的模型（首选）；</li>
     *   <li>若管理端为该渠道配置了<b>显式降级链</b>，按 priority 顺序使用降级目标，不再追加自动候选；</li>
     *   <li>否则走内置自动候选：其他已启用且有密钥、且模型清单里包含同名模型的渠道（能力最接近），
     *       再接其余已启用渠道的默认模型（跨厂商兜底，保证对话不中断）。</li>
     * </ol>
     */
    public List<Candidate> candidates(ModelEndpoint primary, String primaryModel) {
        List<Candidate> list = new ArrayList<>();
        list.add(new Candidate(primary, primaryModel));

        // 显式降级链优先（管理端配置即视为该渠道的权威降级策略）
        List<ChannelGovernanceService.FallbackRule> rules =
                governance.fallbackRules(primary.provider());
        boolean hasEnabledRule = rules.stream().anyMatch(ChannelGovernanceService.FallbackRule::enabled);
        if (hasEnabledRule) {
            for (ChannelGovernanceService.FallbackRule rule : rules) {
                if (!rule.enabled() || rule.targetProvider().equals(primary.provider())) {
                    continue;
                }
                ModelEndpoint target = apiKeyService.findChannel(rule.targetProvider())
                        .map(this::toEndpoint)
                        .filter(endpoint -> endpoint.enabled() && endpoint.hasApiKey())
                        .orElse(null);
                if (target == null) {
                    log.warn("降级链目标渠道不可用，跳过：{}", rule.targetProvider());
                    continue;
                }
                String model = (rule.targetModel() != null && !rule.targetModel().isBlank())
                        ? rule.targetModel() : target.defaultModel();
                if (model == null || model.isBlank()) {
                    log.warn("降级链目标渠道没有可用模型，跳过：{}", rule.targetProvider());
                    continue;
                }
                list.add(new Candidate(target, model));
            }
            return list;
        }

        List<ModelEndpoint> others = apiKeyService.findAllEnabled().stream()
                .map(this::toEndpoint)
                .filter(endpoint -> endpoint.hasApiKey())
                .filter(endpoint -> !endpoint.provider().equals(primary.provider()))
                .toList();

        for (ModelEndpoint endpoint : others) {
            if (endpoint.supportsModel(primaryModel)) {
                list.add(new Candidate(endpoint, primaryModel));
            }
        }
        for (ModelEndpoint endpoint : others) {
            boolean alreadyUsed = list.stream()
                    .anyMatch(candidate -> candidate.endpoint().provider().equals(endpoint.provider()));
            if (!alreadyUsed && endpoint.defaultModel() != null && !endpoint.defaultModel().isBlank()) {
                list.add(new Candidate(endpoint, endpoint.defaultModel()));
            }
        }
        return list;
    }
}
