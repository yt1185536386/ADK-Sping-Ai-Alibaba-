package com.hr.agent.spi;

import java.util.Map;

/**
 * 聊天前置意图拦截器（harness 与业务模块之间的扩展点）。
 *
 * <p>命中结构化业务意图（如"出库"、"配送"）时，直接返回卡片响应，不再走模型问答——
 * 避免 AI 逐步追问参数。业务模块各自注册实现（通常是 @Component），
 * ChatController 只遍历 {@code List<ChatIntentInterceptor>}，不 import 任何业务类。
 *
 * <p>实现方只返回卡片负载；threadId / provider / model 等传输层字段由调用方合并。
 */
public interface ChatIntentInterceptor {

    /**
     * 尝试拦截一条用户消息。
     *
     * @param message   用户消息原文
     * @param moduleKey 分类或显式指定后的业务模块 key（hr / wms / order / common）
     * @return 命中时返回意图结果；不命中返回 null（继续走正常模型问答）
     */
    ChatIntentResult tryIntercept(String message, String moduleKey);

    /**
     * 命中的意图结果。
     *
     * @param event     响应类型（同时作为 SSE 事件名与 status 值），如 outbound_form / delivery_form
     * @param reply     卡片附带的提示文案（用于会话记录与回复展示）
     * @param module    模块 agent 名，如 wms_agent
     * @param moduleKey 模块 key，如 wms
     * @param moduleName 模块显示名，如 仓库管理
     * @param form      结构化表单 schema（前端卡片渲染依据）
     */
    record ChatIntentResult(String event, String reply,
                            String module, String moduleKey, String moduleName,
                            Map<String, Object> form) {
    }
}
