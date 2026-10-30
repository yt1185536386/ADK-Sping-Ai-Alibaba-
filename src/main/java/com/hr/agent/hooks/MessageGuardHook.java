package com.hr.agent.hooks;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型调用前的消息守卫（BEFORE_MODEL）——一次遍历同时做两件事，最后统一用 REPLACE 写回。
 *
 * <p><b>1. 修复悬空的 tool_calls（必须）</b>
 * OpenAI 兼容接口有个硬性要求：{@code assistant(tool_calls)} 后面必须**直接**跟着覆盖每个
 * {@code tool_call_id} 的 tool 消息。写操作触发人工确认时，图会停在"模型已提出 tool_call、
 * 但工具还没执行"的位置并保存 checkpoint。如果用户此时不去点确认、而是直接输入了新的问题，
 * 新消息会接在这条悬空的 assistant 消息后面 —— 下次模型调用就会被接口 400 拒绝：
 * <pre>An assistant message with 'tool_calls' must be followed by tool messages ...</pre>
 * 实测确实会复现（前端表现为回复里出现一段 Exception 文本）。这里自动补一条
 * "该操作已取消"的 tool 响应把这段历史闭合掉。
 *
 * <p><b>2. 注入人工确认记录（否则会被模型改回旧值）</b>
 * 人工选择"改参数后批准"（EDITED）时，框架会把改后的参数替换进 tool call 执行，
 * 但**不会**把这个改动写进任何模型可见的消息里（只有 REJECTED 才会注入一条 ToolResponseMessage）。
 * 结果是模型读到的工具返回值和用户原话对不上，就会误判成"我自己填错了"，
 * 然后**再调一次写工具把数据改回旧值** —— 实测在 deepseek-v4-pro 上能稳定复现。
 * 这里把 {@link #NOTE_KEY} 里的说明作为一条 UserMessage 追加进去，让模型明确知道
 * 这些参数是人工确认过的最终值。同一轮内只注入一次（靠 {@link #MARKER} 识别）。
 * <p><b>3. 上下文滑动窗口（控制 token 消耗）</b>
 * checkpoint 会保留全量历史，长会话每轮模型调用都背着全部消息，prompt token 持续增长。
 * 这里在发给模型前把历史截到最近 {@code maxMessages} 条。切点只选 {@link UserMessage}：
 * 从一条用户消息开始截，能保证 {@code assistant(tool_calls)} 与其 tool 响应成对保留，
 * 不会被从中间切开而再次制造悬空调用。找不到安全切点时保留原样（宁可多发也不破坏协议）。
 */
@HookPositions({HookPosition.BEFORE_MODEL})
public class MessageGuardHook extends MessagesModelHook {

    private static final Logger log = LoggerFactory.getLogger(MessageGuardHook.class);

    /** AgentFactory 写入 RunnableConfig metadata 时使用的键 */
    public static final String NOTE_KEY = "HR_HUMAN_DECISION_NOTE";

    /** 人工确认记录的消息前缀，同时用于识别"本轮是否已经注入过" */
    public static final String MARKER = "【人工确认记录】";

    private static final String CANCEL_TEXT =
            "该操作已作废：用户在人工确认框里没有确认就开始了新的对话，因此本次 %s 调用被取消，没有写入任何数据。";

    /** 发给模型的最大历史消息条数；<=0 表示不截断 */
    private final int maxMessages;

    public MessageGuardHook() {
        this(0);
    }

    public MessageGuardHook(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    @Override
    public AgentCommand beforeModel(List<Message> previousMessages, RunnableConfig config) {
        if (previousMessages == null) {
            previousMessages = List.of();
        }
        List<Message> messages = repairDanglingToolCalls(previousMessages);
        boolean changed = messages != previousMessages;

        List<Message> windowed = applyContextWindow(messages);
        if (windowed != messages) {
            messages = windowed;
            changed = true;
        }

        Object note = config.metadata(NOTE_KEY).orElse(null);
        if (note != null && !String.valueOf(note).isBlank() && !hasNote(messages)) {
            messages = new ArrayList<>(messages);
            messages.add(new UserMessage(MARKER + note));
            changed = true;
            log.info("注入人工确认记录，长度={}", String.valueOf(note).length());
        }

        if (!changed) {
            return new AgentCommand((List<Message>) null, UpdatePolicy.APPEND);
        }
        return new AgentCommand(messages, UpdatePolicy.REPLACE);
    }

    /** 给没有 tool 响应的 assistant(tool_calls) 补一条"已作废"的 tool 响应；无需修复时原样返回入参 */
    private static List<Message> repairDanglingToolCalls(List<Message> messages) {
        boolean needRepair = false;
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            if (m instanceof AssistantMessage am && !am.getToolCalls().isEmpty()
                    && missingResponses(messages, i, am).size() > 0) {
                needRepair = true;
                break;
            }
        }
        if (!needRepair) {
            return messages;
        }

        List<Message> out = new ArrayList<>(messages.size() + 2);
        for (int i = 0; i < messages.size(); i++) {
            Message m = messages.get(i);
            out.add(m);
            if (!(m instanceof AssistantMessage am) || am.getToolCalls().isEmpty()) {
                continue;
            }
            List<ToolResponseMessage.ToolResponse> missing = missingResponses(messages, i, am);
            if (missing.isEmpty()) {
                continue;
            }
            for (ToolResponseMessage.ToolResponse r : missing) {
                log.warn("发现未闭合的工具调用，补一条作废的 tool 响应：tool={} id={}", r.name(), r.id());
            }
            out.add(ToolResponseMessage.builder().responses(missing).build());
        }
        return out;
    }

    /** 该 assistant 消息里哪些 tool_call 在紧随其后的 tool 消息中没有响应 */
    private static List<ToolResponseMessage.ToolResponse> missingResponses(List<Message> messages, int assistantIndex,
                                                                          AssistantMessage am) {
        List<ToolResponseMessage.ToolResponse> missing = new ArrayList<>();
        for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
            if (!responsePresent(messages, assistantIndex, tc.id())) {
                missing.add(new ToolResponseMessage.ToolResponse(tc.id(), tc.name(),
                        String.format(CANCEL_TEXT, tc.name())));
            }
        }
        return missing;
    }

    /**
     * 只认紧随其后的连续 ToolResponseMessage —— 这是接口的硬性要求，
     * 隔着别的消息（例如用户新问题）在后面补 tool 响应是不算数的。
     */
    private static boolean responsePresent(List<Message> messages, int assistantIndex, String toolCallId) {
        for (int j = assistantIndex + 1; j < messages.size(); j++) {
            Message m = messages.get(j);
            if (!(m instanceof ToolResponseMessage trm)) {
                break;
            }
            for (ToolResponseMessage.ToolResponse r : trm.getResponses()) {
                if (toolCallId.equals(r.id())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 上下文滑动窗口：只保留最近 maxMessages 条，切点选 UserMessage 保证 tool 调用配对完整。
     * 无需截断或找不到安全切点时原样返回入参。
     */
    private List<Message> applyContextWindow(List<Message> messages) {
        if (maxMessages <= 0 || messages.size() <= maxMessages) {
            return messages;
        }
        int from = messages.size() - maxMessages;
        for (int i = from; i < messages.size() - 1; i++) {
            if (messages.get(i) instanceof UserMessage) {
                List<Message> out = new ArrayList<>(messages.subList(i, messages.size()));
                log.info("上下文窗口截断：{} -> {} 条（上限 {}）", messages.size(), out.size(), maxMessages);
                return out;
            }
        }
        return messages;
    }

    private static boolean hasNote(List<Message> messages) {
        for (Message m : messages) {
            String text = m.getText();
            if (text != null && text.startsWith(MARKER)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String getName() {
        return "MessageGuard";
    }

    @Override
    public List<JumpTo> canJumpTo() {
        return List.of();
    }
}
