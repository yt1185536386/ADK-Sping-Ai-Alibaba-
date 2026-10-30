package com.hr.agent.model.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * L3 模型运行时层：故障转移装饰器（带熔断）。
 *
 * <p>按优先级持有多个已适配好的模型实例（每个实例外层已包计量）。主渠道出现
 * 「配额耗尽 / 鉴权失败 / 限流 / 5xx / 连接超时」这类可转移错误时，自动切到下一候选，
 * 让对话不至于因为单个渠道的额度问题直接失败。
 *
 * <p>与熔断的配合：处于熔断状态（OPEN）的渠道直接被跳过，不再浪费一次必然失败的调用；
 * 统计窗口内失败次数达到阈值时该渠道被熔断并进入冷却，冷却结束转半开做一次探测；
 * 探测成功则恢复，失败则重新熔断。策略见 {@link ChannelGovernanceService}。
 *
 * <p>流式场景的约束：只有在**尚未输出任何 chunk** 时才允许转移，否则重发会导致前端
 * 文本重复，因此一旦开始出字就只在原渠道内报错。
 */
public class FailoverChatModel implements ChatModel {

    private static final Logger log = LoggerFactory.getLogger(FailoverChatModel.class);

    /**
     * 单次调用（含底层 SDK 自身重试）的总超时。底层 SDK 默认会重试多次，
     * 渠道不可达时可能挂数分钟，超时保护确保能在可接受时间内切到下一候选。
     */
    private static final long CALL_TIMEOUT_MS = 60_000L;

    /** 阻塞式调用的超时看门狗线程池（守护线程，调用结束即回收） */
    private static final ExecutorService CALL_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "model-call-guard");
        t.setDaemon(true);
        return t;
    });

    private final List<ChatModel> delegates;
    private final List<String> labels;
    /** 与 delegates 一一对应的渠道标识，用于熔断统计与降级 */
    private final List<String> providers;
    private final ChannelGovernanceService governance;
    private final FailoverEventRecorder eventRecorder;

    /** 一次调用的降级过程跟踪：尝试了几次、哪些渠道因熔断被跳过、事件是否已落库 */
    private static final class Tracker {
        final List<String> skipped = new ArrayList<>();
        final AtomicInteger attempts = new AtomicInteger();
        final AtomicInteger lastIndex = new AtomicInteger(-1);
        final AtomicBoolean recorded = new AtomicBoolean(false);
        String module;
        String threadId;
        String user;

        boolean engaged() {
            return attempts.get() > 1 || !skipped.isEmpty();
        }

        String skippedJoined() {
            return skipped.isEmpty() ? null : String.join(",", skipped);
        }
    }

    public FailoverChatModel(List<ChatModel> delegates, List<String> labels, List<String> providers,
                             ChannelGovernanceService governance, FailoverEventRecorder eventRecorder) {
        this.delegates = delegates;
        this.labels = labels;
        this.providers = providers;
        this.governance = governance;
        this.eventRecorder = eventRecorder;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        RuntimeException last = null;
        Tracker tracker = new Tracker();
        for (int i = 0; i < delegates.size(); i++) {
            String provider = providerAt(i);
            if (governance != null && governance.isTripped(provider)) {
                log.warn("模型渠道处于熔断状态，跳过：{}（标签 {}）", provider, labels.get(i));
                tracker.skipped.add(provider);
                continue;
            }
            tracker.attempts.incrementAndGet();
            tracker.lastIndex.set(i);
            try {
                if (i > 0) {
                    log.warn("模型渠道故障转移：{} → {}", labels.get(i - 1), labels.get(i));
                }
                ChatResponse response = callWithTimeout(delegates.get(i), prompt, labels.get(i));
                if (governance != null) {
                    governance.onSuccess(provider);
                }
                recordFailover(true, i, tracker, last == null ? null : brief(last));
                return response;
            } catch (RuntimeException e) {
                if (governance != null && failoverable(e)) {
                    governance.onFailure(provider, e.toString());
                }
                if (i == delegates.size() - 1 || !failoverable(e)) {
                    recordFailover(false, i, tracker, brief(e));
                    throw e;
                }
                log.warn("模型调用失败，切换下一候选渠道：{}，原因：{}", labels.get(i), brief(e));
                last = e;
            }
        }
        recordFailover(false, tracker.lastIndex.get(), tracker, last == null ? null : brief(last));
        if (!tracker.skipped.isEmpty() && last == null) {
            throw new IllegalStateException("所有模型渠道均处于熔断状态，请稍后重试或在管理端恢复渠道");
        }
        throw last == null ? new IllegalStateException("没有可用的模型渠道") : last;
    }

    /** 带总超时的一次调用；超时视为可转移错误 */
    private ChatResponse callWithTimeout(ChatModel delegate, Prompt prompt, String label) {
        String module = ModelCallContext.module();
        String threadId = ModelCallContext.threadId();
        String user = ModelCallContext.user();
        Future<ChatResponse> future = CALL_EXECUTOR.submit(() -> {
            if (module != null) {
                ModelCallContext.enter(module, threadId, user);
            }
            try {
                return delegate.call(prompt);
            } finally {
                ModelCallContext.clear();
            }
        });
        try {
            return future.get(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TransientAiException("模型调用超时（" + (CALL_TIMEOUT_MS / 1000) + "s）：" + label, e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("模型调用被中断：" + label, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("模型调用失败：" + label, cause);
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 在请求线程捕获调用上下文，供降级事件落库（Reactor 切线程后 ThreadLocal 会丢失）
        Tracker tracker = new Tracker();
        tracker.module = ModelCallContext.module();
        tracker.threadId = ModelCallContext.threadId();
        tracker.user = ModelCallContext.user();
        AtomicBoolean emitted = new AtomicBoolean(false);
        return attemptStream(prompt, 0, emitted, tracker)
                .doOnError(error -> {
                    // 已开始出字后的错误不属于降级范畴；一次完整降级失败只记一条
                    if (!emitted.get() && tracker.recorded.compareAndSet(false, true)) {
                        recordStreamFailover(false, tracker.lastIndex.get(), tracker, brief(error));
                    }
                });
    }

    private Flux<ChatResponse> attemptStream(Prompt prompt, int index, AtomicBoolean emitted, Tracker tracker) {
        if (index >= delegates.size()) {
            return Flux.error(new IllegalStateException("没有可用的模型渠道"));
        }
        String provider = providerAt(index);
        if (governance != null && governance.isTripped(provider)) {
            log.warn("模型渠道处于熔断状态，跳过：{}（标签 {}）", provider, labels.get(index));
            tracker.skipped.add(provider);
            return attemptStream(prompt, index + 1, emitted, tracker);
        }
        tracker.attempts.incrementAndGet();
        tracker.lastIndex.set(index);
        // 在订阅前捕获调用上下文，避免 Reactor 切线程后 ThreadLocal 丢失
        String module = ModelCallContext.module();
        String threadId = ModelCallContext.threadId();
        String user = ModelCallContext.user();
        AtomicBoolean settled = new AtomicBoolean(false);
        return Flux.defer(() -> {
            if (module != null) {
                ModelCallContext.enter(module, threadId, user);
            }
            if (index > 0) {
                log.warn("模型流式故障转移：{} → {}", labels.get(index - 1), labels.get(index));
            }
            return delegates.get(index).stream(prompt)
                    .doOnNext(response -> {
                        emitted.set(true);
                        if (settled.compareAndSet(false, true)) {
                            if (governance != null) {
                                governance.onSuccess(provider);
                            }
                            if (tracker.recorded.compareAndSet(false, true)) {
                                recordStreamFailover(true, index, tracker, null);
                            }
                        }
                    })
                    .doOnError(error -> {
                        if (settled.compareAndSet(false, true) && governance != null && failoverable(error)) {
                            governance.onFailure(provider, error.toString());
                        }
                    })
                    .onErrorResume(error -> (!emitted.get() && failoverable(error)
                                    && index + 1 < delegates.size())
                            ? attemptStream(prompt, index + 1, emitted, tracker)
                            : Flux.error(error));
        });
    }

    // ==================== 降级事件落库 ====================

    /** 实际发生了降级（多候选尝试或命中熔断跳过）才记事件，正常单次调用不记 */
    private void recordFailover(boolean success, int toIndex, Tracker tracker, String error) {
        if (eventRecorder == null || !tracker.engaged() || !tracker.recorded.compareAndSet(false, true)) {
            return;
        }
        doRecord(success, toIndex, tracker, error);
    }

    /** 流式路径：落库前恢复调用上下文，让事件能带上模块/用户/会话 */
    private void recordStreamFailover(boolean success, int toIndex, Tracker tracker, String error) {
        if (eventRecorder == null || !tracker.engaged()) {
            return;
        }
        try {
            if (tracker.module != null) {
                ModelCallContext.enter(tracker.module, tracker.threadId, tracker.user);
            }
            doRecord(success, toIndex, tracker, error);
        } finally {
            ModelCallContext.clear();
        }
    }

    private void doRecord(boolean success, int toIndex, Tracker tracker, String error) {
        String[] from = splitLabel(labels.get(0));
        String[] to = toIndex >= 0 && toIndex < labels.size()
                ? splitLabel(labels.get(toIndex)) : new String[]{null, null};
        eventRecorder.record("failover", from[0], from[1], to[0], to[1],
                tracker.attempts.get(), tracker.skippedJoined(), success, error);
    }

    /** 标签格式为 provider::model */
    private static String[] splitLabel(String label) {
        if (label == null) {
            return new String[]{null, null};
        }
        int idx = label.indexOf("::");
        return idx < 0
                ? new String[]{label, null}
                : new String[]{label.substring(0, idx), label.substring(idx + 2)};
    }

    private String providerAt(int index) {
        if (providers != null && index < providers.size() && providers.get(index) != null) {
            return providers.get(index);
        }
        return "unknown";
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegates.isEmpty()
                ? ChatOptions.builder().build()
                : delegates.get(0).getDefaultOptions();
    }

    /** 可转移错误：额度/鉴权/限流/服务端 5xx/连接类问题；业务性 4xx 不转移 */
    static boolean failoverable(Throwable error) {
        Throwable t = error;
        for (int guard = 0; t != null && guard < 10; guard++) {
            if (t instanceof TransientAiException) {
                return true;
            }
            String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
            if (message.contains("429") || message.contains("rate limit") || message.contains("rate_limit")
                    || message.contains("insufficient_quota") || message.contains("quota")
                    || message.contains("401") || message.contains("403") || message.contains("unauthorized")
                    || message.contains("502") || message.contains("503") || message.contains("504")
                    || message.contains("timeout") || message.contains("timed out")
                    || message.contains("connection reset") || message.contains("unknownhost")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
            t = t.getCause();
        }
        return false;
    }

    private static String brief(Throwable error) {
        String message = error.getMessage();
        if (message == null) {
            return error.getClass().getSimpleName();
        }
        String flat = message.replaceAll("\\s+", " ").trim();
        return flat.length() <= 160 ? flat : flat.substring(0, 160) + "...";
    }
}
