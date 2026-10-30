package com.hr.agent;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAgentAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioSpeechAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioTranscriptionAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeChatAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeEmbeddingAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeImageAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeRerankAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeVideoAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 排除 DashScope 的全部自动配置：这些自动配置要求 spring.ai.dashscope.api-key 配置项，
 * 而本项目的密钥统一存在数据库 api_key 表中，因此 ChatModel 由 AgentFactory 读取密钥后手动构建。
 */
@EnableAsync
@SpringBootApplication(exclude = {
        DashScopeChatAutoConfiguration.class,
        DashScopeAgentAutoConfiguration.class,
        DashScopeEmbeddingAutoConfiguration.class,
        DashScopeImageAutoConfiguration.class,
        DashScopeVideoAutoConfiguration.class,
        DashScopeAudioSpeechAutoConfiguration.class,
        DashScopeAudioTranscriptionAutoConfiguration.class,
        DashScopeRerankAutoConfiguration.class
})
public class HrAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(HrAgentApplication.class, args);
    }
}
