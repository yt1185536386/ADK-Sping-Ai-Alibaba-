package com.hr.agent.model.adapter;

import com.hr.agent.model.api.ModelEndpoint;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容端点的 API 客户端构建（供 OpenAI 适配器与 DashScope 的向量模型复用）。
 *
 * <p>base_url 末尾是否带 {@code /v1} 决定补全路径，规则与改造前 AgentFactory 中保持一致。
 * 显式设置连接/读取超时：渠道不可达时必须尽快抛错，才能让上层的故障转移有机会接手，
 * 否则会一直挂在底层 SDK 的默认重试里。
 */
final class OpenAiApiSupport {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 120_000;

    private OpenAiApiSupport() {
    }

    static OpenAiApi build(ModelEndpoint endpoint, String apiKey) {
        OpenAiApi.Builder builder = OpenAiApi.builder().apiKey(apiKey);
        String baseUrl = endpoint.baseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            String trimmed = trimSlash(baseUrl);
            builder.baseUrl(trimmed);
            boolean hasV1 = trimmed.endsWith("/v1");
            builder.completionsPath(hasV1 ? "/chat/completions" : "/v1/chat/completions");
            builder.embeddingsPath(hasV1 ? "/embeddings" : "/v1/embeddings");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        requestFactory.setReadTimeout(READ_TIMEOUT_MS);
        builder.restClientBuilder(RestClient.builder().requestFactory(requestFactory));
        return builder.build();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
