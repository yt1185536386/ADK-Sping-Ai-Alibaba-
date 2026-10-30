package com.hr.agent.mcp;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

import java.util.Arrays;
import java.util.List;

/**
 * 本地工具绑定：把模块里现有的 {@code @Tool} Bean 原样暴露出去。
 * 这是默认绑定，行为与改造前完全一致（零迁移成本）。
 */
public class LocalToolBinding implements ToolBinding {

    private final String name;
    private final List<Object> toolBeans;

    public LocalToolBinding(String name, Object... toolBeans) {
        this.name = name;
        this.toolBeans = List.of(toolBeans);
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public List<ToolCallback> toolCallbacks() {
        return toolBeans.stream()
                .flatMap(bean -> Arrays.stream(ToolCallbacks.from(bean)))
                .toList();
    }
}
