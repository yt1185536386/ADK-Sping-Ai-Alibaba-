package com.hr.agent.form;

import com.hr.agent.spi.ChatIntentInterceptor;
import org.springframework.stereotype.Component;

/**
 * 出库意图拦截（wms 模块）：命中"把…出库/出货/领用"等语义时，不再让 AI 逐步追问参数，
 * 直接返回出库单卡片（表单本身即确认，无需再走 HITL 审批框）。
 * 纯查询语义（"查一下出库单"/"最近的出库记录"）不截胡，走正常问答。
 */
@Component
public class OutboundFormInterceptor implements ChatIntentInterceptor {

    private final OutboundFormController outboundForm;

    public OutboundFormInterceptor(OutboundFormController outboundForm) {
        this.outboundForm = outboundForm;
    }

    @Override
    public ChatIntentResult tryIntercept(String message, String moduleKey) {
        if (message == null || message.isBlank()) {
            return null;
        }
        // 出库是仓库模块的能力；显式指定了别的模块时不截胡
        if (moduleKey != null && !moduleKey.isBlank() && !"wms".equals(moduleKey)) {
            return null;
        }
        boolean hasOutboundWord = message.contains("出库") || message.contains("出货") || message.contains("领用");
        if (!hasOutboundWord) {
            return null;
        }
        // 查询类语义：带"查/看/有哪些/最近/记录/列表"时不弹表单，走正常问答
        boolean isQuery = message.contains("查") || message.contains("看") || message.contains("有哪些")
                || message.contains("列出") || message.contains("最近") || message.contains("记录")
                || message.contains("列表") || message.contains("多少");
        if (isQuery) {
            return null;
        }
        var schema = outboundForm.schema(null);
        return new ChatIntentResult("outbound_form",
                String.valueOf(schema.get("hint")),
                "wms_agent", "wms", "仓库管理", schema);
    }
}
