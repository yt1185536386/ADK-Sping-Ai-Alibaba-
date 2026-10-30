package com.hr.agent.model.runtime;

import com.hr.agent.model.api.ModelEndpoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * L3 模型运行时层：密钥解析。
 *
 * <p>渠道密钥已在 {@code ApiKeyService} 读取时用 AES-256-GCM 解密，这里只做
 * 「库里没有时回退到环境变量」的兜底，并保证总是返回非 null 字符串，
 * 避免底层客户端因 null 直接抛 NPE。
 */
@Component
public class KeyResolver {

    private static final Logger log = LoggerFactory.getLogger(KeyResolver.class);

    public String resolve(ModelEndpoint endpoint) {
        if (endpoint.hasApiKey()) {
            return endpoint.apiKey().trim();
        }
        String env = System.getenv("AI_DASHSCOPE_API_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        log.warn("[未配置密钥] 渠道 provider={} 的 api_key 为空，调用将失败。请执行："
                        + "UPDATE api_key SET api_key = 'sk-你的key' WHERE provider = '{}';",
                endpoint.provider(), endpoint.provider());
        return "";
    }
}
