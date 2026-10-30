package com.hr.agent.model.adapter;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.hr.agent.model.api.ModelEndpoint;
import com.hr.agent.model.api.ModelSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * L2 适配器：DashScope 原生协议。
 *
 * <p>向量模型仍走 OpenAI 兼容端点（与改造前一致）：百炼的 embedding 接口同时提供
 * {@code /v1/embeddings} 兼容路径，无需为 DashScope 单独实现向量调用。
 */
@Component
public class DashScopeChatAdapter implements ChatModelAdapter {

    private static final Logger log = LoggerFactory.getLogger(DashScopeChatAdapter.class);

    @Override
    public String protocol() {
        return "dashscope";
    }

    @Override
    public ChatModel createChat(ModelEndpoint endpoint, ModelSpec spec) {
        DashScopeApi.Builder builder = DashScopeApi.builder().apiKey(endpoint.apiKey());
        String baseUrl = endpoint.baseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(trimSlash(baseUrl));
        }
        log.info("构建 DashScope 渠道模型：provider={}, model={}", endpoint.provider(), spec.model());
        return DashScopeChatModel.builder()
                .dashScopeApi(builder.build())
                .defaultOptions(DashScopeChatOptions.builder()
                        .model(spec.model())
                        .temperature(spec.temperature())
                        .build())
                .build();
    }

    @Override
    public EmbeddingModel createEmbedding(ModelEndpoint endpoint, ModelSpec spec) {
        OpenAiApi api = OpenAiApiSupport.build(endpoint, endpoint.apiKey());
        log.info("构建 DashScope 渠道向量模型（OpenAI 兼容端点）：provider={}, model={}",
                endpoint.provider(), spec.model());
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(spec.model()).build());
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
