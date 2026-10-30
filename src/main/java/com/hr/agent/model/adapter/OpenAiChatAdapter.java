package com.hr.agent.model.adapter;

import com.hr.agent.model.api.ModelEndpoint;
import com.hr.agent.model.api.ModelSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Component;

/**
 * L2 适配器：OpenAI 兼容协议（DeepSeek / 公司内部推理服务 / 百炼 OpenAI 兼容模式等）。
 */
@Component
public class OpenAiChatAdapter implements ChatModelAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatAdapter.class);

    @Override
    public String protocol() {
        return "openai";
    }

    @Override
    public ChatModel createChat(ModelEndpoint endpoint, ModelSpec spec) {
        OpenAiApi api = OpenAiApiSupport.build(endpoint, endpoint.apiKey());
        log.info("构建 OpenAI 兼容渠道模型：provider={}, model={}, baseUrl={}",
                endpoint.provider(), spec.model(), endpoint.baseUrl());
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(spec.model())
                        .temperature(spec.temperature())
                        .build())
                .build();
    }

    @Override
    public EmbeddingModel createEmbedding(ModelEndpoint endpoint, ModelSpec spec) {
        OpenAiApi api = OpenAiApiSupport.build(endpoint, endpoint.apiKey());
        log.info("构建向量模型：provider={}, model={}", endpoint.provider(), spec.model());
        return new OpenAiEmbeddingModel(api, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder().model(spec.model()).build());
    }
}
