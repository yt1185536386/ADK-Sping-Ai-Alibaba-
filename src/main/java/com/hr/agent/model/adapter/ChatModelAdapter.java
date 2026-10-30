package com.hr.agent.model.adapter;

import com.hr.agent.model.api.ModelEndpoint;
import com.hr.agent.model.api.ModelSpec;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * L2 模型适配层契约：把「渠道配置 + 模型规格」适配成 Spring AI 的模型实例。
 *
 * <p>职责边界：
 * <ul>
 *   <li>协议差异（openai 兼容 / dashscope / 未来的 anthropic、ollama）只在这一层出现；</li>
 *   <li>不持有连接、不做实例缓存（交 {@code ModelInstanceRegistry}）；</li>
 *   <li>不做渠道路由与故障转移（交 {@code ModelGateway}）。</li>
 * </ul>
 */
public interface ChatModelAdapter {

    /** 本适配器支持的协议标识，与 api_key.protocol 取值一致 */
    String protocol();

    ChatModel createChat(ModelEndpoint endpoint, ModelSpec spec);

    EmbeddingModel createEmbedding(ModelEndpoint endpoint, ModelSpec spec);
}
