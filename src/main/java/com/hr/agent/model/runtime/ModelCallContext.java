package com.hr.agent.model.runtime;

/**
 * 模型调用上下文：把「哪个业务模块在调模型」传递给计量装饰器。
 *
 * <p>由 {@code AgentFactory} 在进入模块 Agent 前 set，结束时 clear。
 * 流式场景在 {@code MeteredChatModel.stream()} 被调用的那一刻读取（此时仍在请求线程），
 * 再捕获到局部变量里，避免 Reactor 切线程后 ThreadLocal 丢失。
 */
public final class ModelCallContext {

    private static final ThreadLocal<String> MODULE = new ThreadLocal<>();
    private static final ThreadLocal<String> THREAD_ID = new ThreadLocal<>();
    private static final ThreadLocal<String> USER = new ThreadLocal<>();

    private ModelCallContext() {
    }

    public static void enter(String moduleKey, String threadId) {
        enter(moduleKey, threadId, null);
    }

    /** 进入一次模型调用上下文；username 用于管理端按用户维度统计调用量 */
    public static void enter(String moduleKey, String threadId, String username) {
        MODULE.set(moduleKey);
        THREAD_ID.set(threadId);
        USER.set(username);
    }

    public static String module() {
        return MODULE.get();
    }

    public static String threadId() {
        return THREAD_ID.get();
    }

    /** 发起调用的登录用户（可能为 null：内部调用/系统任务） */
    public static String user() {
        return USER.get();
    }

    public static void clear() {
        MODULE.remove();
        THREAD_ID.remove();
        USER.remove();
    }
}
