package com.hr.agent.controller;

import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback.FeedbackResult;
import com.hr.agent.agents.ModuleAgent;
import com.hr.agent.config.AgentFactory;
import com.hr.agent.service.ApiKeyService;
import com.hr.agent.service.ApiKeyService.ModelChannel;
import com.hr.agent.spi.ApprovalOptionsProvider;
import com.hr.agent.spi.ApprovalPreviewProvider;
import com.hr.agent.spi.ChatIntentInterceptor;
import com.hr.agent.store.ConversationStore;
import com.hr.agent.store.HitlService;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ChatController.class);

    private final AgentFactory agentFactory;
    private final ApiKeyService apiKeyService;
    private final ConversationStore conversationStore;
    private final HitlService hitlService;
    /** 审批项的候选选项提供者（让"调拨哪几件资产"这类参数能在确认框里逐条勾选）；没有实现时为空列表 */
    private final List<ApprovalOptionsProvider> optionProviders;
    /** 审批项的明细预览提供者（让"一次入库 100 个商品"这类批量操作能在确认框里出表格核对）；没有实现时为空列表 */
    private final List<ApprovalPreviewProvider> previewProviders;
    private final String embeddingProvider;
    /** 前置意图拦截器（出库/配送等结构化卡片）：业务模块各自注册，本类不认识任何业务实现 */
    private final List<ChatIntentInterceptor> intentInterceptors;
    /** SSE 异步执行线程池（守护线程，不阻止 JVM 退出） */
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "chat-sse");
        t.setDaemon(true);
        return t;
    });
    /** 进行中的流式回答：threadId -> 执行任务（/api/chat/stop 据此中断） */
    private final Map<String, Future<?>> runningStreams = new ConcurrentHashMap<>();

    public ChatController(AgentFactory agentFactory,
                          ApiKeyService apiKeyService,
                          ConversationStore conversationStore,
                          HitlService hitlService,
                          List<ApprovalOptionsProvider> optionProviders,
                          List<ApprovalPreviewProvider> previewProviders,
                          @Value("${hr.agent.embedding-provider:bailian}") String embeddingProvider,
                          List<ChatIntentInterceptor> intentInterceptors) {
        this.agentFactory = agentFactory;
        this.apiKeyService = apiKeyService;
        this.conversationStore = conversationStore;
        this.hitlService = hitlService;
        this.optionProviders = optionProviders;
        this.previewProviders = previewProviders;
        this.embeddingProvider = embeddingProvider;
        this.intentInterceptors = intentInterceptors == null ? List.of() : intentInterceptors;
    }

    /** 遍历业务模块注册的意图拦截器；命中则返回卡片结果，全部不命中返回 null */
    private ChatIntentInterceptor.ChatIntentResult interceptIntent(String message, ModuleAgent module) {
        String moduleKey = module == null ? null : module.key();
        for (ChatIntentInterceptor interceptor : intentInterceptors) {
            ChatIntentInterceptor.ChatIntentResult hit = interceptor.tryIntercept(message, moduleKey);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 把拦截结果组装成完整响应体（threadId/provider/model 是传输层字段，由本类合并） */
    private Map<String, Object> intentResponse(ChatIntentInterceptor.ChatIntentResult hit,
                                               String threadId, String provider, String model) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", hit.event());
        body.put("threadId", threadId);
        body.put("provider", provider);
        body.put("model", model);
        body.put("module", hit.module());
        body.put("moduleKey", hit.moduleKey());
        body.put("moduleName", hit.moduleName());
        body.put("reply", hit.reply());
        body.put("form", hit.form());
        return body;
    }

    /** 渠道列表（含可选推理模型）；向量模型单独返回，不作为推理模型展示。
     *  匿名可访问（前端初始化需要），因此不下发 baseUrl / source 等内部地址信息。 */
    @GetMapping("/channels")
    public List<Map<String, Object>> channels() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ModelChannel ch : apiKeyService.findAllEnabled()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("provider", ch.provider());
            m.put("name", ch.name());
            m.put("protocol", ch.protocol());
            m.put("vendor", ch.vendor());
            m.put("weight", ch.weight());
            m.put("isDefault", ch.isDefault());
            m.put("defaultModel", ch.defaultModel());
            m.put("models", ch.chatModels());            // 仅推理模型
            m.put("embeddingModels", ch.embeddingModels()); // 向量模型（不参与推理选择）
            m.put("keyConfigured", ch.hasApiKey());
            result.add(m);
        }
        return result;
    }

    /** 当前向量模型配置（默认百炼的 qwen3.7-text-embedding） */
    @GetMapping("/embedding")
    public Map<String, Object> embeddingConfig() {
        Map<String, Object> body = new LinkedHashMap<>();
        ModelChannel ch = apiKeyService.findChannel(embeddingProvider).orElse(null);
        body.put("provider", embeddingProvider);
        body.put("channelName", ch == null ? null : ch.name());
        body.put("embeddingModel", ch == null ? null : ch.embeddingModels().stream().findFirst().orElse(null));
        body.put("keyConfigured", ch != null && ch.hasApiKey());
        body.put("switchTip", "更换向量渠道/模型：改 application.yml 的 hr.agent.embedding-provider，或改该渠道的 embedding_model 字段");
        return body;
    }

    /** 向量模型连通性测试 */
    @PostMapping("/embedding/test")
    public Map<String, Object> embeddingTest(@RequestBody EmbedRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            EmbeddingModel model = agentFactory.embeddingModel(
                    req.provider() == null || req.provider().isBlank() ? embeddingProvider : req.provider(),
                    req.model());
            float[] vec = model.embed(req.text());
            body.put("ok", true);
            body.put("dimension", vec.length);
            body.put("sample", new float[]{vec[0], vec[1], vec[2]});
        } catch (Exception e) {
            log.warn("向量模型连通性测试失败: {}", e.getMessage());
            body.put("ok", false);
            body.put("error", "向量模型调用失败（详情见服务端日志）");
        }
        return body;
    }

    /** 健康检查 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("channels", apiKeyService.findAllEnabled().size());
        body.put("defaultChannel", apiKeyService.findDefaultChannel().map(ModelChannel::provider).orElse(null));
        body.put("memory", "MysqlSaver（对话上下文持久化到 MySQL）");
        body.put("hitl", "写操作需人工确认（HumanInTheLoopHook + checkpoint 断点续跑）");
        body.put("embedding", embeddingConfig());
        List<String> moduleKeys = new ArrayList<>();
        agentFactory.modules().forEach(m -> moduleKeys.add(m.key() + "(" + m.agentName() + ")"));
        body.put("modules", moduleKeys);
        body.put("router", "orchestrator（先分类到模块，再走与显式指定模块完全相同的执行链路）");
        return body;
    }

    /** 会话列表（按最后活跃时间倒序）：普通用户只看自己的会话，ADMIN 看全部 */
    @GetMapping("/threads")
    public List<Map<String, Object>> threads(@RequestParam(defaultValue = "50") int limit) {
        return conversationStore.listThreads(currentUsername(), isAdmin(), Math.min(Math.max(limit, 1), 200));
    }

    /** 某个会话的历史消息（仅限本人或 ADMIN） */
    @GetMapping("/threads/{threadId}")
    public Map<String, Object> threadMessages(@PathVariable String threadId,
                                              @RequestParam(defaultValue = "200") int limit) {
        requireAccess(threadId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("threadId", threadId);
        body.put("messages", conversationStore.listMessages(threadId, Math.min(Math.max(limit, 1), 500)));
        return body;
    }

    /** 删除某个会话的历史记录，不影响其上下文缓存（仅限本人或 ADMIN） */
    @DeleteMapping("/threads/{threadId}")
    public Map<String, Object> deleteThread(@PathVariable String threadId) {
        requireAccess(threadId);
        return Map.of("threadId", threadId, "deleted", conversationStore.deleteThread(threadId));
    }

    /** 删除单条消息；若是用户消息则连同其 AI 回答一起删（一问一答视为一对，仅限本人或 ADMIN） */
    @DeleteMapping("/messages/{id}")
    public Map<String, Object> deleteMessage(@PathVariable long id) {
        String owner = conversationStore.ownerOfMessage(id);
        if (!isAdmin() && owner != null && !owner.equals(currentUsername())) {
            throw new AccessDeniedException("无权删除他人会话的消息");
        }
        return Map.of("id", id, "deleted", conversationStore.deleteMessageWithReply(id));
    }

    // ==================== 会话归属（水平越权防护）====================

    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : auth.getName();
    }

    private static boolean isAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }

    /** 会话归属校验：本人或 ADMIN 才能读写/续跑/作废该会话；无归属的历史会话任何登录用户可用 */
    private void requireAccess(String threadId) {
        if (!conversationStore.canAccess(threadId, currentUsername(), isAdmin())) {
            throw new AccessDeniedException("无权访问他人会话：" + threadId);
        }
    }

    /** 已注册的业务模块（hr / wms 等），前端可用于手动指定模块 */
    @GetMapping("/modules")
    public List<Map<String, Object>> modules() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ModuleAgent module : agentFactory.modules()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", module.key());
            m.put("displayName", module.displayName());
            m.put("agentName", module.agentName());
            m.put("description", module.description());
            m.put("approvalTools", module.approvalTools());
            result.add(m);
        }
        return result;
    }

    // ==================== 对话 ====================

    /**
     * 与 Agent 对话；可指定来源、模型与模块。
     * POST /api/chat {"message":"...", "threadId":"u1", "provider":"deepseek", "model":"deepseek-v4-pro", "module":"wms"}
     * <p>module 为空时走统一调度入口（orchestrator，自动路由到对应模块）。
     * <p>若模型要执行需要审批的写操作，不会直接执行，而是返回 status=pending_approval 与待确认清单。
     */
    @PostMapping("/chat")
    public Map<String, Object> chat(@RequestBody ChatRequest req) {
        String threadId = (req.threadId() == null || req.threadId().isBlank()) ? "default" : req.threadId();
        String provider = req.provider() == null ? "" : req.provider();
        String model = req.model() == null ? "" : req.model();
        String moduleKey = req.module() == null ? "" : req.module().trim();

        // 会话归属：不能往他人的 checkpoint 上下文里续写对话
        requireAccess(threadId);

        // 用户发起新一轮对话时，上一轮没确认的审批单作废，免得过期确认框再次弹出
        hitlService.expirePending(threadId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("threadId", threadId);
        result.put("provider", provider);
        result.put("model", model);
        conversationStore.save(threadId, "user", provider, model, req.message(), currentUsername());
        try {
            // 两条入口合流：未指定模块时先由 orchestrator 分类，之后走完全相同的执行链路
            // （这样才能保证自动调度路径下的写操作同样能触发 HITL 中断，而不是被静默吞掉）
            boolean routed = moduleKey.isEmpty();
            ModuleAgent module = routed
                    ? agentFactory.classify(provider, model, req.message())
                    : agentFactory.module(moduleKey)
                        .orElseThrow(() -> new IllegalArgumentException("未注册的模块：" + moduleKey));
            // 前置意图拦截（出库/配送等结构化卡片由业务模块通过 SPI 注册），替代 AI 逐步追问参数
            ChatIntentInterceptor.ChatIntentResult intent = interceptIntent(req.message(), module);
            if (intent != null) {
                Map<String, Object> form = intentResponse(intent, threadId, provider, model);
                conversationStore.save(threadId, "assistant", provider, model,
                        "（" + intent.moduleName() + "卡片）" + intent.reply(), currentUsername());
                return form;
            }
            AgentFactory.ApprovalOutcome outcome =
                    agentFactory.chatModule(module, provider, model, req.message(), threadId);
            result.put("module", module.agentName());
            result.put("routed", routed);
            if (outcome.interrupted()) {
                return pendingApproval(threadId, module, provider, model, outcome.interruption());
            }
            result.put("status", "done");
            String reply = outcome.reply() == null ? "" : outcome.reply();
            result.put("reply", stripAmap(reply));
            if (!outcome.amapSpecs().isEmpty()) {
                result.put("amap", outcome.amapSpecs());
            }
            conversationStore.save(threadId, "assistant", provider, model, stripAmap(reply), currentUsername());
        } catch (Exception e) {
            log.error("对话执行失败 [threadId={}]: {}", threadId, e.getMessage(), e);
            result.put("status", "error");
            result.put("reply", "调用失败，请稍后重试（详情见服务端日志）");
            result.put("error", "true");
        }
        return result;
    }

    /**
     * 流式对话（SSE）。执行链路与 {@link #chat} 完全一致（同步 chatModule 在后台线程执行），
     * 只是结果按事件流推给前端：
     * <ul>
     *   <li>{@code meta} —— 模块路由结果（threadId/provider/model/module/routed）</li>
     *   <li>{@code chunk} —— 回复文本分段（按段落/行切片推送，前端增量渲染）</li>
     *   <li>{@code done} —— 正常结束（含完整 reply）</li>
     *   <li>{@code pending} —— 命中 HITL，data 与 /api/chat 的 pending_approval 结构相同</li>
     *   <li>{@code error} —— 失败</li>
     * </ul>
     * 注：底层 ReactAgent 只有同步 API，无法拿到模型 token 级输出，
     * 因此 chunk 是「拿到完整回复后的分段伪流式」，但 meta 能立刻给出、审批也能实时弹出。
     */
    @PostMapping(path = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest req) {
        // 日志不记录消息正文（可能含敏感信息），只记长度用于排查
        log.info("[chatStream] ENTRY: threadId={}, messageLength={}", req.threadId(),
                req.message() == null ? 0 : req.message().length());
        SseEmitter emitter = new SseEmitter(600_000L); // 10 分钟超时，长任务够用
        String threadId = (req.threadId() == null || req.threadId().isBlank()) ? "default" : req.threadId();
        // SecurityContextHolder 是 ThreadLocal：SSE 任务在后台线程池执行，
        // 不把登录态带过去的话 AgentFactory.currentUsername() 只能拿到 null，
        // llm_call_log.username 全是 NULL（管理端用户调用量全显示"未登录"）
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        // 登记执行句柄，供 /api/chat/stop 中断「停止生成」
        Future<?>[] holder = new Future<?>[1];
        Future<?> future = sseExecutor.submit(() -> {
            if (auth != null) {
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
            try {
                runStream(emitter, req, threadId);
            } finally {
                SecurityContextHolder.clearContext();
                Future<?> self = holder[0];
                if (self != null) {
                    runningStreams.remove(threadId, self);
                }
            }
        });
        holder[0] = future;
        runningStreams.put(threadId, future);
        if (future.isDone()) {
            runningStreams.remove(threadId, future);
        }
        return emitter;
    }

    /** 停止正在生成的回答：中断执行线程（模型 HTTP 调用随之放弃，已输出的部分保留在前端） */
    @PostMapping("/chat/stop")
    public Map<String, Object> stopChat(@RequestBody(required = false) Map<String, String> body) {
        String threadId = body == null ? null : body.get("threadId");
        if (threadId != null) {
            requireAccess(threadId);   // 不能中断他人正在生成的回答
        }
        Future<?> running = threadId == null ? null : runningStreams.remove(threadId);
        if (running != null) {
            running.cancel(true);
            log.info("会话 {} 的回答已被用户手动停止", threadId);
        }
        return Map.of("ok", true, "stopped", running != null);
    }

    /** SSE 任务的实际执行体（在 chat-sse 线程上运行，登录态已由调用方注入） */
    private void runStream(SseEmitter emitter, ChatRequest req, String threadId) {
            String provider = req.provider() == null ? "" : req.provider();
            String model = req.model() == null ? "" : req.model();
            String moduleKey = req.module() == null ? "" : req.module().trim();
            try {
                requireAccess(threadId);
                hitlService.expirePending(threadId);
                long userMsgId = conversationStore.save(threadId, "user", provider, model, req.message(), currentUsername());

                boolean routed = moduleKey.isEmpty();
                ModuleAgent module = routed
                        ? agentFactory.classify(provider, model, req.message())
                        : agentFactory.module(moduleKey)
                            .orElseThrow(() -> new IllegalArgumentException("未注册的模块：" + moduleKey));

                Map<String, Object> meta = new LinkedHashMap<>();
                meta.put("threadId", threadId);
                meta.put("provider", provider);
                meta.put("model", model);
                meta.put("module", module.agentName());
                meta.put("moduleName", module.displayName());
                meta.put("routed", routed);
                meta.put("userMsgId", userMsgId);
                send(emitter, "meta", meta);

                // 前置意图拦截（出库/配送等结构化卡片由业务模块通过 SPI 注册），替代 AI 逐步追问参数
                ChatIntentInterceptor.ChatIntentResult intent = interceptIntent(req.message(), module);
                if (intent != null) {
                    Map<String, Object> form = intentResponse(intent, threadId, provider, model);
                    conversationStore.save(threadId, "assistant", provider, model,
                            "（" + intent.moduleName() + "卡片）" + intent.reply(), currentUsername());
                    send(emitter, intent.event(), form);
                    emitter.complete();
                    return;
                }

                AgentFactory.ApprovalOutcome outcome =
                        agentFactory.chatModule(module, provider, model, req.message(), threadId);
                if (outcome.interrupted()) {
                    send(emitter, "pending", pendingApproval(threadId, module, provider, model, outcome.interruption()));
                    emitter.complete();
                    return;
                }
                String reply = outcome.reply() == null ? "" : outcome.reply();
                // 保险：模型若原样复述了 [AMAP] 标记，先从正文剥离，避免露出原始标记
                String cleanReply = stripAmap(reply);
                // 按句子/小片切片推送；片间留小间隔，让前端呈现连续打字效果
                for (String piece : splitChunks(cleanReply)) {
                    send(emitter, "chunk", Map.of("text", piece));
                    try {
                        Thread.sleep(28);
                    } catch (InterruptedException ie) {
                        // /api/chat/stop 中断：已输出的部分前端已收到，直接结束本轮推送
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                long assistantMsgId = conversationStore.save(threadId, "assistant", provider, model, cleanReply + amapTail(outcome.amapSpecs()), currentUsername());
                Map<String, Object> done = new LinkedHashMap<>(meta);
                done.put("status", "done");
                done.put("reply", cleanReply);
                done.put("assistantMsgId", assistantMsgId);
                // 地图数据从工具原始输出提取（不依赖模型保留标记），单独下发给前端渲染
                if (!outcome.amapSpecs().isEmpty()) {
                    done.put("amap", outcome.amapSpecs());
                }
                send(emitter, "done", done);
                emitter.complete();
            } catch (Exception e) {
                log.error("流式对话执行失败 [threadId={}]: {}", threadId, e.getMessage(), e);
                try {
                    send(emitter, "error", Map.of("status", "error",
                            "reply", "调用失败，请稍后重试（详情见服务端日志）", "threadId", threadId));
                } catch (Exception ignore) {
                    // 连接已断，直接结束
                }
                emitter.complete();
            }
    }

    private static void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    /** 剥离正文里的 [AMAP]{...}[/AMAP] 标记（地图数据已通过别的通道下发，正文不该露出原始标记） */
    private static String stripAmap(String text) {
        if (text == null || !text.contains("[AMAP]")) {
            return text;
        }
        return text.replaceAll("(?s)\\[AMAP].*?\\[/AMAP]", "").replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** 把本轮地图标记拼回消息内容存库，历史会话重新打开时前端可据此恢复地图 */
    private static String amapTail(List<String> amapSpecs) {
        if (amapSpecs == null || amapSpecs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String spec : amapSpecs) {
            sb.append("\n[AMAP]").append(spec).append("[/AMAP]");
        }
        return sb.toString();
    }

    /**
     * 把回复切成流式小片：按句子/小粒度切，让前端呈现连续打字效果。
     *
     * <p>切片规则：
     * 1. 先按换行拆成「行」，保留换行符；
     * 2. 每行再按中文/英文句末标点（。！？；.!?;）切成「句」，保留标点；
     * 3. 单句超过 60 字再按逗号/顿号切成更小的子句；
     * 4. 相邻小片合并成目标 20~60 字一段，避免过碎或过粗。
     * 最终按序逐片推送，前端边收边渲染。
     */
    private static List<String> splitChunks(String reply) {
        List<String> pieces = new ArrayList<>();
        // 1+2：按行拆，再按句末标点切句（保留分隔符）
        for (String line : reply.split("(?<=\\n)")) {
            for (String sentence : line.split("(?<=[。！？；.!?;])")) {
                if (sentence.isEmpty()) {
                    continue;
                }
                // 3：超长句再按逗号/顿号切
                if (sentence.length() > 60) {
                    for (String clause : sentence.split("(?<=[，、,])")) {
                        if (!clause.isEmpty()) {
                            pieces.add(clause);
                        }
                    }
                } else {
                    pieces.add(sentence);
                }
            }
        }
        // 4：把过碎的小片合并成 20~60 字一段
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (String p : pieces) {
            buf.append(p);
            // 够长、或刚好吃到换行/句末，就吐出来一段
            if (buf.length() >= 40 || p.endsWith("\n")) {
                out.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            out.add(buf.toString());
        }
        if (out.isEmpty() && !reply.isEmpty()) {
            out.add(reply);
        }
        return out;
    }

    // ==================== 人工审批（HITL）====================

    /** 组装待确认清单：落审批单 + 返回给前端 */
    private Map<String, Object> pendingApproval(String threadId, ModuleAgent module, String provider, String model,
                                                InterruptionMetadata interruption) {
        String batchId = HitlService.newBatchId();
        List<Map<String, Object>> items = new ArrayList<>();
        for (ToolFeedback tf : interruption.toolFeedbacks()) {
            String id = hitlService.create(batchId, threadId, module.key(), provider, model,
                    tf.getName(), tf.getId(), tf.getArguments(), tf.getDescription());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("tool", tf.getName());
            item.put("toolLabel", module.approvalTools().getOrDefault(tf.getName(), tf.getName()));
            item.put("arguments", tf.getArguments());
            item.put("description", tf.getDescription());
            attachChoices(item, tf.getName(), tf.getArguments());
            attachPreview(item, tf.getName(), tf.getArguments());
            items.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "pending_approval");
        body.put("batchId", batchId);
        body.put("threadId", threadId);
        body.put("provider", provider);
        body.put("model", model);
        body.put("module", module.agentName());
        body.put("moduleKey", module.key());
        body.put("moduleName", module.displayName());
        body.put("approvals", items);
        body.put("reply", "以下操作会改动数据，需要你确认后才会执行。");
        return body;
    }

    /**
     * 给需要"逐条勾选"的审批项附上候选清单。
     *
     * <p>例如调拨资产：把仓库里可调拨的同类物品逐件列出来（编号、名称、规格、所在仓库），
     * 用户在确认框里勾选要哪几件 —— 只给一个编号文本框的话，用户既认不出编号对应哪件东西，
     * 也不知道除了这几件还有哪些可选。
     *
     * <p>勾选结果由前端写回 {@code choiceField} 指定的参数字段，所以仍然走原有的
     * 「参数被改过 → EDIT」通道，后端的执行链路一行都不用改。
     */
    private void attachChoices(Map<String, Object> item, String toolName, String argumentsJson) {
        for (ApprovalOptionsProvider provider : optionProviders) {
            ApprovalOptionsProvider.Options opt = provider.forTool(toolName, argumentsJson);
            if (opt != null && !opt.isEmpty()) {
                item.put("choices", opt.items());
                item.put("choiceField", opt.field());
                item.put("choiceHint", opt.hint());
                return;
            }
        }
    }

    /**
     * 给批量类写操作附上「明细预览表」。
     *
     * <p>解决的问题：一张 100 行的入库清单如果只显示参数 JSON，用户看不出自己要入库什么，
     * 只能盲签；而按商品逐个弹确认框又要点 100 次。所以批量工具只产生一次审批，
     * 审批项上挂一张只读表格，核对完点一次「批准执行」。
     *
     * <p>实现方（{@link ApprovalPreviewProvider}）在预览阶段<em>只能读库、不能写</em> ——
     * 用户可能拒绝或改参数，预览时就占号/发单号会留下脏数据。
     */
    private void attachPreview(Map<String, Object> item, String toolName, String argumentsJson) {
        for (ApprovalPreviewProvider provider : previewProviders) {
            ApprovalPreviewProvider.Preview pv = provider.forTool(toolName, argumentsJson);
            if (pv != null && !pv.isEmpty()) {
                item.put("preview", Map.of(
                        "title", pv.title() == null ? "明细预览" : pv.title(),
                        "hint", pv.hint() == null ? "" : pv.hint(),
                        "summary", pv.summary() == null ? "" : pv.summary(),
                        "columns", pv.columns(),
                        "rows", pv.rows()));
                return;
            }
        }
    }

    /** 查询某会话下仍待确认的审批项（前端刷新后恢复确认框用）。
     *  普通用户仅限本人会话；ADMIN/APPROVER 可查任意会话（配合跨人审批）。 */
    @GetMapping("/approvals")
    public List<Map<String, Object>> pendingApprovals(@RequestParam String threadId) {
        if (!canApprove()) {
            requireAccess(threadId);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (HitlService.Approval a : hitlService.listPending(threadId)) {
            result.add(toApprovalItem(a));
        }
        return result;
    }

    /**
     * 审批员工作台：跨会话的全部待审批项（仅 ADMIN/APPROVER）。
     * 审批他人发起的写操作时，审批员从这里拿到 batchId/审批项，再调 POST /api/approvals 决策。
     */
    @GetMapping("/approvals/pending-all")
    public List<Map<String, Object>> pendingAll(@RequestParam(defaultValue = "50") int limit) {
        if (!canApprove()) {
            throw new AccessDeniedException("仅 ADMIN 或 APPROVER 可查看跨会话待审批列表");
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (HitlService.Approval a : hitlService.listAllPending(Math.min(Math.max(limit, 1), 200))) {
            Map<String, Object> item = toApprovalItem(a);
            item.put("threadId", a.threadId());
            result.add(item);
        }
        return result;
    }

    /** 审批单 → 前端确认框所需的完整结构（含勾选候选/明细预览，刷新后也要能原样还原） */
    private Map<String, Object> toApprovalItem(HitlService.Approval a) {
        ModuleAgent module = agentFactory.module(a.moduleKey()).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("batchId", a.batchId());
        m.put("tool", a.toolName());
        m.put("toolLabel", module == null ? a.toolName()
                : module.approvalTools().getOrDefault(a.toolName(), a.toolName()));
        m.put("arguments", a.arguments());
        m.put("description", a.description());
        m.put("createdAt", a.createdAt());
        // 刷新页面后要还原成和当初一模一样、可操作的确认框：
        // 少了这两个，勾选框和明细预览表会凭空消失，用户只能看到一个光秃秃的参数 JSON。
        attachChoices(m, a.toolName(), a.arguments());
        attachPreview(m, a.toolName(), a.arguments());
        return m;
    }

    /** 当前用户是否有审批权限（ADMIN 或 APPROVER） */
    private static boolean canApprove() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()) || "ROLE_APPROVER".equals(a.getAuthority()));
    }

    /**
     * 提交人工审批决策并从中断点继续执行。
     * POST /api/approvals {"batchId":"BATCH-...","decisions":[{"id":"HITL-...","action":"APPROVE","reason":""}]}
     * action：APPROVE（批准）/ REJECT（拒绝，可带 reason）/ EDIT（改参数，arguments 传新的 JSON 串）
     */
    @PostMapping("/approvals")
    public Map<String, Object> decide(@RequestBody ApprovalRequest req) {
        Map<String, Object> body = new LinkedHashMap<>();
        List<HitlService.Approval> batch = hitlService.listByBatch(req.batchId() == null ? "" : req.batchId());
        if (batch.isEmpty()) {
            body.put("status", "error");
            body.put("reply", "审批单不存在，可能已经处理过了。");
            return body;
        }

        Map<String, Decision> byId = new LinkedHashMap<>();
        if (req.decisions() != null) {
            req.decisions().forEach(d -> byId.put(d.id(), d));
        }

        List<HitlService.Approval> pending = new ArrayList<>();
        for (HitlService.Approval a : batch) {
            if (a.pending()) {
                pending.add(a);
            }
        }
        if (pending.isEmpty()) {
            body.put("status", "error");
            body.put("reply", "这批操作已经处理过了（可能你已经开始新的对话，它在那一刻就自动作废了）。请重新发起这次操作。");
            return body;
        }
        for (HitlService.Approval a : pending) {
            if (!byId.containsKey(a.id())) {
                body.put("status", "error");
                body.put("reply", "还有未处理的审批项：" + a.toolName());
                return body;
            }
        }

        HitlService.Approval first = pending.get(0);
        // 审批刻意不做会话归属校验：专职审批员（APPROVER/ADMIN）可以审批他人发起的写操作。
        // 能执行写操作的门槛由 SecurityConfig 保证（POST /api/approvals 仅 ADMIN/APPROVER），
        // 会话归属只约束「谁的发起/查看」，不约束「谁的审批」。
        ModuleAgent module = agentFactory.module(first.moduleKey())
                .orElseThrow(() -> new IllegalStateException("模块不存在：" + first.moduleKey()));

        List<ToolFeedback> feedbacks = new ArrayList<>();
        List<String> decisions = new ArrayList<>();
        StringBuilder editNote = new StringBuilder();
        for (HitlService.Approval a : pending) {
            Decision d = byId.get(a.id());
            String action = (d.action() == null || d.action().isBlank()) ? "APPROVE" : d.action().trim().toUpperCase();
            FeedbackResult result;
            String note;
            String editedArguments = null;
            switch (action) {
                case "REJECT" -> {
                    result = FeedbackResult.REJECTED;
                    note = (d.reason() == null || d.reason().isBlank())
                            ? "用户拒绝了该操作" : d.reason().trim();
                }
                case "EDIT" -> {
                    result = FeedbackResult.EDITED;
                    editedArguments = d.arguments();
                    note = (d.reason() == null || d.reason().isBlank())
                            ? "用户修改了调用参数" : d.reason().trim();
                }
                default -> {
                    result = FeedbackResult.APPROVED;
                    note = "用户已确认执行";
                }
            }
            String useArguments = (editedArguments != null && !editedArguments.isBlank())
                    ? editedArguments : a.arguments();
            if (result == FeedbackResult.EDITED) {
                // 收集人工改动，稍后注入给模型；否则模型不知道参数被改过，会试图改回用户原话里的旧值
                editNote.append("- ").append(a.toolName()).append(" 的最终参数：").append(useArguments)
                        .append("\n  用户说明：").append(note).append("\n");
            }
            hitlService.decide(a.id(), result.name(), note, editedArguments);
            decisions.add(a.toolName() + "=" + result.name());
            feedbacks.add(ToolFeedback.builder()
                    .id(a.toolCallId())
                    .name(a.toolName())
                    .arguments(useArguments)
                    .description(note)
                    .result(result)
                    .build());
        }

        body.put("threadId", first.threadId());
        try {
            AgentFactory.ApprovalOutcome outcome = agentFactory.resumeWithFeedback(
                    module, first.provider(), first.model(), first.threadId(), feedbacks,
                    editNote.isEmpty() ? null : decisionNote(editNote.toString()));
            if (outcome.interrupted()) {
                // 继续执行后又碰到另一个需要审批的写操作，再弹一次确认
                return pendingApproval(first.threadId(), module, first.provider(), first.model(),
                        outcome.interruption());
            }
            String reply = outcome.reply();
            for (HitlService.Approval a : pending) {
                hitlService.saveResult(a.id(), reply);
            }
            conversationStore.save(first.threadId(), "assistant", first.provider(), first.model(),
                    "（人工" + String.join("，", decisions) + "）" + reply, currentUsername());
            body.put("status", "done");
            body.put("reply", reply);
            body.put("module", module.agentName());
            body.put("decisions", decisions);
        } catch (Exception e) {
            log.error("审批后恢复执行失败 [threadId={}]: {}", first.threadId(), e.getMessage(), e);
            body.put("status", "error");
            body.put("reply", "确认后执行失败，请稍后重试（详情见服务端日志）");
            body.put("error", "true");
        }
        return body;
    }

    /** 组装注入给模型的人工改动说明（由 HumanDecisionNoteHook 追加成一条模型可见的消息） */
    private static String decisionNote(String edits) {
        return "你在上一步提交的写操作，用户已在确认框中修改参数后批准执行。人工确认过的最终参数如下：\n"
                + edits
                + "工具返回值会与上面的参数一致。请直接按实际执行结果向用户汇报；"
                + "这些就是正确的最终值，不要再用对话历史或用户最初提到的其他数值重新调用写操作把数据改回去。";
    }

    public record ChatRequest(String message, String threadId, String provider, String model, String module) {
    }

    public record EmbedRequest(String text, String provider, String model) {
    }

    public record ApprovalRequest(String batchId, List<Decision> decisions) {
    }

    public record Decision(String id, String action, String arguments, String reason) {
    }
}
