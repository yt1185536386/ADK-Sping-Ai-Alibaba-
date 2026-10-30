package com.hr.agent.form;

import com.hr.agent.spi.ChatIntentInterceptor;
import org.springframework.stereotype.Component;

/**
 * 配送意图拦截（order 模块）：命中"配送/派送/送货/发货"时直接下发配送单卡片，
 * 用户填完确认即建单并完成拣货出库，替代 AI 逐步追问客户、数量、员工、地址。
 * 纯查询（查配送单、配送状态、取消配送）不截胡。
 */
@Component
public class DeliveryFormInterceptor implements ChatIntentInterceptor {

    private final DeliveryFormController deliveryForm;

    public DeliveryFormInterceptor(DeliveryFormController deliveryForm) {
        this.deliveryForm = deliveryForm;
    }

    @Override
    public ChatIntentResult tryIntercept(String message, String moduleKey) {
        if (message == null || message.isBlank()) {
            return null;
        }
        if (moduleKey != null && !moduleKey.isBlank() && !"order".equals(moduleKey)) {
            return null;
        }
        boolean hasDeliveryWord = message.contains("配送") || message.contains("派送")
                || message.contains("送货") || message.contains("发货");
        if (!hasDeliveryWord) {
            return null;
        }
        boolean isQuery = message.contains("查") || message.contains("看") || message.contains("有哪些")
                || message.contains("列出") || message.contains("最近") || message.contains("记录")
                || message.contains("列表") || message.contains("多少") || message.contains("状态")
                || message.contains("进度") || message.contains("取消");
        if (isQuery) {
            return null;
        }
        var schema = deliveryForm.schema(null);
        return new ChatIntentResult("delivery_form",
                String.valueOf(schema.get("hint")),
                "order_agent", "order", "订单管理", schema);
    }
}
