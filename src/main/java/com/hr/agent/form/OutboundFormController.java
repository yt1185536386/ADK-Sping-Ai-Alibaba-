package com.hr.agent.form;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出库单前置表单：聊天中识别到出库意图时，先下发结构化表单让用户填写，
 * 确认后直接执行出库（表单本身即确认，不再走 AI 追问和 HITL 二次确认）。
 *
 * 接口：
 * - POST /api/form/outbound/schema  返回表单 schema（字段定义 + 仓库/商品下拉选项 + 库存提示）
 * - POST /api/form/outbound/submit  提交表单，直接执行出库
 *
 * 执行逻辑与 WmsTools#outbound 一致（智能选仓、FOR UPDATE 行锁防超卖、写单据流水、FIFO 标记资产），
 * 只是入口从「模型 Function Calling + 人工审批」换成「表单填写 + 用户确认」。
 */
@RestController
@RequestMapping("/api/form")
public class OutboundFormController {

    private final JdbcTemplate jdbc;
    private final com.hr.agent.tools.WmsTools wmsTools;

    public OutboundFormController(JdbcTemplate jdbc, com.hr.agent.tools.WmsTools wmsTools) {
        this.jdbc = jdbc;
        this.wmsTools = wmsTools;
    }

    /** 表单 schema：字段定义 + 下拉选项 + 库存提示，前端据此渲染出库单卡片 */
    @PostMapping("/outbound/schema")
    public Map<String, Object> schema(@RequestBody(required = false) Map<String, String> req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "outbound_form");
        body.put("title", "出库单");
        body.put("hint", "请填写出库信息，带 * 为必填；仓库留空则由系统自动选择库存充足的仓库。");

        // 仓库下拉选项（启用状态）
        List<Map<String, Object>> warehouses = jdbc.queryForList(
                "SELECT code AS value, CONCAT(name, '(', code, ')') AS label FROM wms_warehouse WHERE status = '启用' ORDER BY id");
        body.put("warehouseOptions", warehouses);

        // 商品下拉选项（带各仓库库存，供前端回显「当前可出数量」）
        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT i.sku AS value, CONCAT(i.name, '[', i.sku, ']') AS label,
                       i.name, i.unit, i.category,
                       COALESCE((SELECT SUM(s.quantity) FROM wms_stock s WHERE s.item_id = i.id), 0) AS total_stock
                FROM wms_item i ORDER BY i.id
                """);
        body.put("itemOptions", items);

        // 字段定义（前端通用渲染器按此生成表单）
        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("warehouseCode", "仓库", "select", false, "留空自动选择库存充足的仓库", warehouses));
        fields.add(field("sku", "商品", "select", true, "按 SKU 或名称搜索", items));
        fields.add(field("quantity", "出库数量", "number", true, "正整数，不能超过所选仓库的当前库存", null));
        fields.add(field("receiver", "收货方/领用部门", "text", false, "选填", null));
        fields.add(field("operator", "经办人", "text", false, "选填", null));
        body.put("fields", fields);
        return body;
    }

    /** NOTE: submit 的执行逻辑复制自 WmsTools#outbound（见该方法处 NOTE），改动出库规则时需双向同步。 */
    /** 提交出库单：参数校验后复用 WmsTools 的出库逻辑执行 */
    @PostMapping("/outbound/submit")
    public Map<String, Object> submit(@RequestBody OutboundForm form) {
        Map<String, Object> body = new LinkedHashMap<>();
        String sku = form.sku() == null ? "" : form.sku().trim();
        if (sku.isEmpty()) {
            body.put("status", "error");
            body.put("reply", "请选择要出库的商品。");
            return body;
        }
        Integer quantity = form.quantity();
        if (quantity == null || quantity <= 0) {
            body.put("status", "error");
            body.put("reply", "出库数量必须是正整数。");
            return body;
        }
        if (quantity > 1_000_000) {
            body.put("status", "error");
            body.put("reply", "出库数量不能超过 1000000。");
            return body;
        }
        try {
            String result = wmsTools.outbound(sku, quantity,
                    form.warehouseCode() == null || form.warehouseCode().isBlank() ? null : form.warehouseCode().trim(),
                    form.receiver(), form.operator());
            body.put("status", "done");
            body.put("reply", result);
        } catch (Exception e) {
            body.put("status", "error");
            body.put("reply", "出库失败：" + e.getMessage());
        }
        return body;
    }

    private static Map<String, Object> field(String name, String label, String type, boolean required,
                                             String placeholder, List<Map<String, Object>> options) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("label", label);
        f.put("type", type);
        f.put("required", required);
        f.put("placeholder", placeholder);
        if (options != null) {
            f.put("options", options);
        }
        return f;
    }

    public record OutboundForm(String warehouseCode, String sku, Integer quantity, String receiver, String operator) {
    }
}
