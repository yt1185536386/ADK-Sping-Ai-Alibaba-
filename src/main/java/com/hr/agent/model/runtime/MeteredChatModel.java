package com.hr.agent.model.runtime;

import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * L3 模型运行时层：计量装饰器。
 *
 * <p>包在真实模型实例外层，记录耗时、首 token 延迟与 token 用量，落库交
 * {@link ModelUsageRecorder} 异步完成。不改变任何调用语义，异常原样抛出。
 */
public class MeteredChatModel implements ChatModel {

    private final ChatModel delegate;
    private final ModelUsageRecorder recorder;
    private final String provider;
    private final String model;
    private final String protocol;

    public MeteredChatModel(ChatModel delegate, ModelUsageRecorder recorder,
                            String provider, String model, String protocol) {
        this.delegate = delegate;
        this.recorder = recorder;
        this.provider = provider;
        this.model = model;
        this.protocol = protocol;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String module = ModelCallContext.module();
        String user = ModelCallContext.user();
        String threadId = ModelCallContext.threadId();
        long start = System.currentTimeMillis();
        try {
            ChatResponse response = delegate.call(prompt);
            record(module, response, start, 0L, true, null, user, threadId);
            return response;
        } catch (RuntimeException e) {
            record(module, null, start, 0L, false, e.toString(), user, threadId);
            throw e;
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 在此刻（仍在请求线程）读取模块上下文，避免 Reactor 切线程后 ThreadLocal 丢失
        String module = ModelCallContext.module();
        String user = ModelCallContext.user();
        String threadId = ModelCallContext.threadId();
        long start = System.currentTimeMillis();
        AtomicLong firstTokenMs = new AtomicLong(0L);
        AtomicReference<ChatResponse> last = new AtomicReference<>();

        Flux<ChatResponse> flux;
        try {
            flux = delegate.stream(prompt);
        } catch (RuntimeException e) {
            record(module, null, start, 0L, false, e.toString(), user, threadId);
            return Flux.error(e);
        }
        return flux
                .doOnNext(response -> {
                    firstTokenMs.compareAndSet(0L, System.currentTimeMillis() - start);
                    last.set(response);
                })
                .doOnComplete(() -> record(module, last.get(), start, firstTokenMs.get(), true, null, user, threadId))
                .doOnError(error -> record(module, last.get(), start, firstTokenMs.get(), false, error.toString(), user, threadId));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private void record(String module, ChatResponse response, long startMs,
                        long firstTokenMs, boolean success, String error, String user, String threadId) {
        Usage usage = (response != null && response.getMetadata() != null)
                ? response.getMetadata().getUsage() : null;
        int promptTokens = safe(usage == null ? null : usage.getPromptTokens());
        int completionTokens = safe(usage == null ? null : usage.getCompletionTokens());
        int totalTokens = usage == null ? 0 : safe(usage.getTotalTokens());
        if (totalTokens == 0) {
            totalTokens = promptTokens + completionTokens;
        }
        recorder.record(new ModelUsageRecorder.ModelUsage(
                module, provider, model, protocol,
                promptTokens, completionTokens, totalTokens,
                System.currentTimeMillis() - startMs, firstTokenMs, success, error,
                user, threadId));
    }

    private static int safe(Integer value) {
        return value == null ? 0 : value;
    }
}
