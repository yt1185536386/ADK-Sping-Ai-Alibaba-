package com.hr.agent.config;

import com.hr.agent.service.ApiKeyService;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata;
import com.alibaba.cloud.ai.graph.action.InterruptionMetadata.ToolFeedback;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.HumanInTheLoopHook;
import com.alibaba.cloud.ai.graph.agent.hook.hip.ToolConfig;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.hr.agent.agents.ModuleAgent;
import com.hr.agent.model.gateway.ModelGateway;
import com.hr.agent.model.runtime.ModelCallContext;
import com.hr.agent.service.ApiKeyService.ModelChannel;
import com.hr.agent.hooks.MessageGuardHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型与 Agent 装配中心：
 * - 按「渠道 + 模型」构建并缓存 ChatModel / EmbeddingModel；
 * - 按「模块 + 渠道 + 模型」构建并缓存各模块的 ReactAgent（agents 包）；
 * - 统一调度入口（orchestrator）：先由模型把请求分类到某个模块（{@link #classify}），
 *   再交由该模块 Agent 执行，两条入口共用同一套 HITL / checkpoint 流程。
 * 全部懒加载，因此某个渠道缺 Key 也不影响启动。
 */
@Component
public class AgentFactory {

    private static final Logger log = LoggerFactory.getLogger(AgentFactory.class);

    private final ApiKeyService apiKeyService;
    private final MysqlSaver checkpointSaver;
    private final List<ModuleAgent> moduleAgents;
    private final ModelGateway modelGateway;

    private final Map<String, ReactAgent> moduleAgentCache = new ConcurrentHashMap<>();

    /** 发给模型的最大历史消息条数（上下文滑动窗口，控制长会话 token 消耗；<=0 不截断） */
    private final int contextMaxMessages;

    public AgentFactory(ApiKeyService apiKeyService, MysqlSaver checkpointSaver,
                        List<ModuleAgent> moduleAgents, ModelGateway modelGateway,
                        @org.springframework.beans.factory.annotation.Value(
                                "${hr.agent.context-max-messages:40}") int contextMaxMessages) {
        this.apiKeyService = apiKeyService;
        this.checkpointSaver = checkpointSaver;
        this.moduleAgents = moduleAgents;
        this.modelGateway = modelGateway;
        this.contextMaxMessages = contextMaxMessages;
    }

    /** 已注册的业务模块（hr / wms 等） */
    public List<ModuleAgent> modules() {
        return moduleAgents;
    }

    public Optional<ModuleAgent> module(String key) {
        return moduleAgents.stream().filter(m -> m.key().equalsIgnoreCase(key)).findFirst();
    }

    // ==================== 渠道解析 ====================

    public ModelChannel requireChannel(String provider) {
        return apiKeyService.findChannel(provider)
                .orElseThrow(() -> new IllegalArgumentException("api_key 表中不存在来源：" + provider));
    }

    public ModelChannel resolveChannel(String provider) {
        if (provider == null || provider.isBlank()) {
            return apiKeyService.findDefaultChannel()
                    .orElseThrow(() -> new IllegalStateException("api_key 表中没有启用的渠道，请先配置渠道"));
        }
        return requireChannel(provider);
    }

    public String resolveModel(ModelChannel channel, String model) {
        String useModel = (model == null || model.isBlank()) ? channel.defaultModel() : model;
        if (useModel == null || useModel.isBlank()) {
            throw new IllegalArgumentException("渠道 " + channel.provider() + " 未配置默认模型，请指定 model 或设置 default_model");
        }
        return useModel;
    }

    // ==================== 模块 Agent ====================

    /** 取某模块的 ReactAgent（按 模块::渠道::模型 缓存） */
    public ReactAgent moduleAgent(ModuleAgent module, String provider, String model) {
        ModelChannel channel = resolveChannel(provider);
        String useModel = resolveModel(channel, model);
        String key = module.key() + "::" + channel.provider() + "::" + useModel;
        return moduleAgentCache.computeIfAbsent(key, k -> buildModuleAgent(module, channel, useModel));
    }

    private ReactAgent buildModuleAgent(ModuleAgent module, ModelChannel channel, String model) {
        log.info("构建模块 Agent：module={}, provider={}, model={}", module.key(), channel.provider(), model);
        return ReactAgent.builder()
                .name(module.agentName())
                .description(module.description())
                .model(chatModel(channel, model))
                .systemPrompt(module.systemPrompt())
                .tools(collectTools(module))
                .hooks(List.of(humanApprovalHook(module), new MessageGuardHook(contextMaxMessages)))  // 写工具先中断等人工确认；调用模型前补全消息 + 注入人工改动 + 上下文窗口截断
                .outputKey(outputKey(module))   // 统一调度时用它从全局状态里取该模块的结果
                .saver(checkpointSaver)
                .build();
    }

    /**
     * 汇总模块声明的全部工具（统一工具接入层）。
     *
     * <p>遍历模块的 {@code toolBindings}：本地绑定走 {@code @Tool} 注解扫描，
     * MCP 绑定走 MCP Client 远端调用，最终都适配成 {@link ToolCallback} 交给模型。
     * 模块未显式声明绑定时，默认等价于改造前的 {@code toolBeans} 本地装配方式。
     */
    private static ToolCallback[] collectTools(ModuleAgent module) {
        List<ToolCallback> all = new ArrayList<>();
        for (var binding : module.toolBindings()) {
            List<ToolCallback> callbacks = binding.toolCallbacks();
            if (callbacks.isEmpty()) {
                // 静默零工具是 MCP 化后最隐蔽的故障：agent 按 module::channel::model 缓存，
                // 一旦缓存了空工具集，修复后也必须重启后端才恢复
                log.error("模块 {} 的工具绑定 {} 解析出 0 个工具！若是 MCP 绑定，请确认对应 MCP Server 已启动"
                        + "（agent 会被缓存，需修复后重启后端）", module.key(), binding.name());
            } else {
                log.debug("模块 {} 装配工具绑定 {}：{} 个工具", module.key(), binding.name(), callbacks.size());
            }
            all.addAll(callbacks);
        }
        log.info("模块 {} 共装配 {} 个工具（来自 {} 个绑定）",
                module.key(), all.size(), module.toolBindings().size());
        return all.toArray(new ToolCallback[0]);
    }

    /**
     * 人工审批 Hook（HITL）：模块声明的写工具会在工具执行前中断，把待确认信息交给前端，
     * 用户确认（或拒绝/改参数）后带 human feedback 重新调用即从中断点继续。
     */
    private HumanInTheLoopHook humanApprovalHook(ModuleAgent module) {
        HumanInTheLoopHook.Builder builder = HumanInTheLoopHook.builder();
        module.approvalTools().forEach((tool, desc) ->
                builder.approvalOn(tool, ToolConfig.builder().description(desc).build()));
        return builder.build();
    }

    /** 模块在全局状态（OverAllState）里写结果用的 key */
    public static String outputKey(ModuleAgent module) {
        return module.key() + "_output";
    }

    /**
     * 模块 Agent 的 checkpoint 线程名。
     *
     * <p>必须按模块加命名空间：MysqlSaver 建的表 {@code graph_thread} 上
     * {@code (thread_name, is_released)} 是**唯一索引**，thread_name 全局唯一。
     * 如果各模块共用同一个 threadId，它们会映射到同一个 thread_id（UUID），
     * 而 checkpoint 是按 thread_id 取最新一条恢复的 —— 同一个会话里切换模块就会串台，
     * 让 B 模块拿着 A 模块的对话历史开跑。
     * 加命名空间后每个模块有独立的对话上下文，同时会话维度（chat_message / hitl_approval）
     * 仍然统一用前端传来的 threadId。
     */
    private static String checkpointThread(ModuleAgent module, String threadId) {
        String t = (threadId == null || threadId.isBlank()) ? "default" : threadId;
        return module.key() + "@" + t;
    }

    // ==================== 统一调度入口（orchestrator）====================

    /**
     * 统一调度：先由模型把用户请求归类到某个模块，再交由该模块 Agent 执行。
     *
     * <p>这里刻意**不使用**框架的 {@code LlmRoutingAgent}（把子 Agent 挂成节点的嵌套图）。
     * 原因：子图里的写操作中断（HITL）不会被父图正确转换成"待人工审批"的结果——实测在自动调度路径下发
     * "把 50 包 A4 复印纸入库到 WH01 仓库"，接口返回的是模型"我这就去入库"的前置话术，
     * 中断被静默吞掉：写操作既没执行、前端也没弹确认框，属于会丢数据的严重问题。
     * 改成"先分类、再委派"后，显式指定模块与自动调度两条入口共用完全相同的
     * HITL / checkpoint / 恢复执行流程，行为可预期。
     */
    public ModuleAgent classify(String provider, String model, String message) {
        if (moduleAgents.isEmpty()) {
            throw new IllegalStateException("没有注册任何业务模块");
        }
        if (moduleAgents.size() == 1) {
            return moduleAgents.get(0);
        }
        // 明确意图先用关键词规则直达：每次对话省一次分类模型调用（数百 token）。
        // 规则刻意保守——只有唯一模块命中才裁决，多模块命中一律交给模型分类。
        ModuleAgent ruled = ruleBasedModule(message);
        if (ruled != null) {
            log.info("统一调度：关键词规则直达模块={}，跳过分类模型调用", ruled.agentName());
            return ruled;
        }
        String raw;
        try {
            ModelCallContext.enter("orchestrator", null, currentUsername());
            raw = chatModel(provider, model)
                    .call(new Prompt(List.of(new SystemMessage(classifyPrompt()), new UserMessage(message))))
                    .getResult().getOutput().getText();
        }
        catch (Exception e) {
            log.warn("模块分类失败，回退到 {}：{}", moduleAgents.get(0).agentName(), e.getMessage());
            return moduleAgents.get(0);
        }
        finally {
            ModelCallContext.clear();
        }
        ModuleAgent picked = matchModule(raw);
        log.info("统一调度：请求={} 分类原始输出={} 命中模块={}", brief(message), brief(raw), picked.agentName());
        return picked;
    }

    /** 模块路由关键词：只放语义明确、不易跨模块歧义的词；命中多个模块时规则放弃裁决 */
    private static final Map<String, List<String>> ROUTE_KEYWORDS = Map.of(
            "hr", List.of("员工", "职位", "入职", "离职", "调岗", "人力资源", "在职"),
            "wms", List.of("仓库", "库存", "入库", "出库", "安全库存", "盘点", "资产", "调拨", "销毁", "补货"),
            "order", List.of("订单", "配送", "采购", "退货", "发货"),
            "common", List.of("天气", "公交", "地铁", "定位", "路线", "地图", "导航")
    );

    /** 关键词规则路由：唯一模块命中才返回该模块；未命中/多模块命中/模块未注册都返回 null 走模型分类 */
    private ModuleAgent ruleBasedModule(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }
        ModuleAgent hit = null;
        for (Map.Entry<String, List<String>> entry : ROUTE_KEYWORDS.entrySet()) {
            if (entry.getValue().stream().noneMatch(message::contains)) {
                continue;
            }
            if (hit != null) {
                return null; // 多模块命中：规则不裁决，避免误判
            }
            hit = module(entry.getKey()).orElse(null);
        }
        return hit;
    }

    /** 把模型的分类输出宽松地匹配到某个模块；匹配不到时回退到第一个模块 */
    private ModuleAgent matchModule(String raw) {
        if (raw == null || raw.isBlank()) {
            return moduleAgents.get(0);
        }
        String s = raw.trim().toLowerCase();
        for (ModuleAgent m : moduleAgents) {
            if (s.equals(m.agentName().toLowerCase()) || s.equals(m.key().toLowerCase())) {
                return m;
            }
        }
        for (ModuleAgent m : moduleAgents) {
            if (s.contains(m.agentName().toLowerCase())) {
                return m;
            }
        }
        return moduleAgents.get(0);
    }

    private String classifyPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个请求分派器。请判断下面的用户请求应当由哪个模块处理，")
          .append("并且**只输出该模块的 Agent 名称**（例如 hr_agent），不要输出任何其他文字、标点或解释。\n\n可选模块：\n");
        for (ModuleAgent module : moduleAgents) {
            sb.append("- ").append(module.agentName()).append("（").append(module.displayName()).append("）：")
              .append(module.description()).append("\n");
        }
        return sb.toString();
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= 120 ? t : t.substring(0, 120) + "...";
    }

    /** 结果可能是 String，也可能是 List&lt;Message&gt;，统一转成文本 */
    @SuppressWarnings("unchecked")
    private static String asText(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        if (value instanceof Message m) {
            return m.getText();
        }
        if (value instanceof List<?> list) {
            for (int i = list.size() - 1; i >= 0; i--) {
                Object item = list.get(i);
                if (item instanceof AssistantMessage am) {
                    return am.getText();
                }
                if (item instanceof Message m) {
                    return m.getText();
                }
            }
        }
        return String.valueOf(value);
    }

    // ==================== 人工审批（HITL）====================

    /**
     * 审批执行结果：要么是最终回复（status=done），要么又碰到需要审批的写工具（status=pending）。
     * amapSpecs：本轮工具输出里抓到的地图结构化数据（JSON 字符串列表），前端据此渲染交互式地图。
     */
    public record ApprovalOutcome(String status, String reply, InterruptionMetadata interruption,
                                  List<String> amapSpecs) {
        public boolean interrupted() {
            return interruption != null;
        }
    }

    /**
     * 调用模块 Agent（正常对话入口）。若模型要调用需要审批的写工具，会在这里中断并返回待确认信息。
     */
    public ApprovalOutcome chatModule(ModuleAgent module, String provider, String model,
                                      String message, String threadId) throws GraphRunnerException {
        RunnableConfig config = RunnableConfig.builder().threadId(checkpointThread(module, threadId)).build();
        try {
            ModelCallContext.enter(module.key(), threadId, currentUsername());
            return parse(moduleAgent(module, provider, model).invokeAndGetOutput(message, config));
        }
        finally {
            ModelCallContext.clear();
        }
    }

    /**
     * 从人工审批中断点恢复执行：把用户的决策（批准 / 拒绝 / 改参数）作为 human feedback 回传，
     * 图会从上次中断的位置继续往下跑（可能再次中断于另一个写工具，由调用方继续处理）。
     *
     * @param decisionNote 可选：人工做了哪些改动（会经 {@link MessageGuardHook} 注入成模型可见的消息，
     *                     否则模型不知道参数被改过，会试图把数据改回用户原话里的旧值）
     */
    public ApprovalOutcome resumeWithFeedback(ModuleAgent module, String provider, String model, String threadId,
                                              List<ToolFeedback> feedbacks, String decisionNote)
            throws GraphRunnerException {
        InterruptionMetadata feedback = InterruptionMetadata.builder()
                .toolFeedbacks(feedbacks)
                .build();
        RunnableConfig.Builder builder = RunnableConfig.builder()
                .threadId(checkpointThread(module, threadId))
                .addHumanFeedback(feedback);
        if (decisionNote != null && !decisionNote.isBlank()) {
            builder.addMetadata(MessageGuardHook.NOTE_KEY, decisionNote);
        }
        try {
            ModelCallContext.enter(module.key(), threadId, currentUsername());
            return parse(moduleAgent(module, provider, model).invokeAndGetOutput(Map.<String, Object>of(), builder.build()));
        }
        finally {
            ModelCallContext.clear();
        }
    }

    /** 统一解析图输出：中断 → pending；正常结束 → 取最后一条 AI 消息文本 + 工具输出里的地图标记 */
    private ApprovalOutcome parse(Optional<NodeOutput> output) {
        if (output.isEmpty()) {
            return new ApprovalOutcome("done", "", null, List.of());
        }
        NodeOutput node = output.get();
        if (node instanceof InterruptionMetadata interruption) {
            return new ApprovalOutcome("pending", "", interruption, List.of());
        }
        Object messages = node.state().value("messages").orElse(null);
        return new ApprovalOutcome("done", asText(messages), null, extractAmapSpecs(messages));
    }

    /**
     * 从消息历史里抓取【本轮】工具输出携带的 [AMAP]{...}[/AMAP] 结构化标记（地图数据）。
     *
     * <p>模型生成自然语言回复时通常会把这些标记消化掉，导致前端拿不到地图数据；
     * 而工具消息（ToolResponseMessage）里的标记是原样保留的，所以直接从原始消息里抓最可靠。
     *
     * <p>注意：checkpoint 会跨问题复用同一 threadId，完整历史里含历轮标记。为避免把上一轮的
     * 旧地图也渲染出来，这里只统计【最后一条用户消息之后】产生的标记（即本轮），倒序扫描、
     * 遇到用户消息即停。返回去重后的 JSON 字符串数组，逐个就是前端要渲染的地图 spec。
     */
    private static List<String> extractAmapSpecs(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> specs = new ArrayList<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("\\[AMAP](.*?)\\[/AMAP]", java.util.regex.Pattern.DOTALL);
        // 倒序扫描：只收集本轮（最后一条用户消息之后）的标记
        for (int i = list.size() - 1; i >= 0; i--) {
            Object item = list.get(i);
            if (item instanceof UserMessage) {
                break; // 本轮边界：再往前是历史轮，不再收集
            }
            String text = (item instanceof Message m) ? m.getText() : String.valueOf(item);
            if (text == null || !text.contains("[AMAP]")) {
                continue;
            }
            var matcher = p.matcher(text);
            while (matcher.find()) {
                String json = matcher.group(1).trim();
                if (!json.isEmpty() && !specs.contains(json)) {
                    specs.add(json);
                }
            }
        }
        return specs;
    }

    // ==================== 模型构建（委托 L1 模型接入层）====================

    /** 模型实例、协议适配、故障转移、token 计量统一由 ModelGateway 负责 */
    public ChatModel chatModel(ModelChannel channel, String model) {
        return modelGateway.chatModel(channel, model);
    }

    public ChatModel chatModel(String provider, String model) {
        return modelGateway.chatModel(provider, model);
    }

    /** 向量模型：默认取渠道 embedding_model（本项目默认用百炼 qwen3.7-text-embedding） */
    public EmbeddingModel embeddingModel(String provider, String model) {
        return modelGateway.embeddingModel(provider, model);
    }

    /**
     * 当前登录用户：写进模型调用上下文，供管理端按「用户维度」统计调用量。
     * 非 HTTP 线程（内部调用/定时任务）取不到，返回 null。
     */
    private static String currentUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth == null || !auth.isAuthenticated()) {
                return null;
            }
            String name = auth.getName();
            return (name == null || "anonymousUser".equals(name)) ? null : name;
        } catch (Exception e) {
            return null;
        }
    }
}
