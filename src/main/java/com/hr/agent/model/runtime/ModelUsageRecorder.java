package com.hr.agent.model.runtime;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * L3 模型运行时层：token 计量与调用审计。
 *
 * <p>写库不阻塞业务线程：记录先入有界队列，由单个守护线程批量落库
 * （{@code llm_call_log} 表）。队列满时丢弃并降级为 debug 日志，绝不因为审计
 * 拖慢或阻断对话。
 */
@Component
public class ModelUsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(ModelUsageRecorder.class);
    private static final int QUEUE_CAPACITY = 20_000;
    private static final int BATCH_SIZE = 50;
    private static final int ERROR_MSG_MAX = 500;

    /** 一次模型调用的计量记录 */
    public record ModelUsage(
            String moduleKey,
            String provider,
            String model,
            String protocol,
            int promptTokens,
            int completionTokens,
            int totalTokens,
            long latencyMs,
            long firstTokenMs,
            boolean success,
            String errorMsg,
            String username,
            String threadId
    ) {
    }

    private final JdbcTemplate jdbc;
    private final BlockingQueue<ModelUsage> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "model-usage-writer");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running = true;

    public ModelUsageRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        start();
    }

    public void record(ModelUsage usage) {
        if (usage == null) {
            return;
        }
        if (!queue.offer(usage)) {
            log.debug("模型用量队列已满，丢弃一条记录：provider={}, model={}",
                    usage.provider(), usage.model());
        }
    }

    private void start() {
        writer.submit(() -> {
            while (running) {
                try {
                    ModelUsage first = queue.poll(1, TimeUnit.SECONDS);
                    if (first == null) {
                        continue;
                    }
                    List<ModelUsage> batch = new ArrayList<>(BATCH_SIZE);
                    batch.add(first);
                    queue.drainTo(batch, BATCH_SIZE - 1);
                    for (ModelUsage usage : batch) {
                        insert(usage);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    // 审计落库失败不影响业务，记录警告后继续消费
                    log.warn("模型用量落库失败：{}", e.getMessage());
                }
            }
        });
    }

    private void insert(ModelUsage u) {
        try {
            String error = u.errorMsg();
            if (error != null && error.length() > ERROR_MSG_MAX) {
                error = error.substring(0, ERROR_MSG_MAX);
            }
            jdbc.update("""
                    INSERT INTO llm_call_log (module_key, provider, model, protocol,
                        prompt_tokens, completion_tokens, total_tokens,
                        latency_ms, first_token_ms, success, error_msg, username, thread_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    u.moduleKey(), u.provider(), u.model(), u.protocol(),
                    u.promptTokens(), u.completionTokens(), u.totalTokens(),
                    (int) Math.min(u.latencyMs(), Integer.MAX_VALUE),
                    (int) Math.min(u.firstTokenMs(), Integer.MAX_VALUE),
                    u.success() ? 1 : 0, error,
                    u.username(), u.threadId());
        } catch (Exception e) {
            log.debug("模型用量写入失败：{}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        writer.shutdownNow();
    }
}
