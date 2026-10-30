package com.hr.agent.form;

import com.hr.agent.tools.OrderTools;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配送单前置表单：聊天中识别到配送意图时，先下发结构化表单让用户填写，
 * 确认后直接创建配送单并完成拣货出库，替代 AI 逐步追问客户/数量/员工/地址。
 *
 * 接口：
 * - POST /api/form/delivery/schema  返回表单 schema（字段定义 + 商品/员工/仓库下拉选项）
 * - POST /api/form/delivery/submit  提交表单：无现成订单时先建单，再创建配送单
 *
 * 执行逻辑复用 {@link OrderTools#createOrder} 与 {@link OrderTools#createDelivery}，
 * 与模型 Function Calling 路径完全一致（库存行锁、出库单流水、订单状态均由同一段代码处理）。
 */
@RestController
@RequestMapping("/api/form")
public class DeliveryFormController {

    /** 订单号形如 SO20261026xxxx */
    private static final Pattern ORDER_NO = Pattern.compile("SO\\d{8,}");
    /** 配送单号前缀兼容 "DL"（OrderTools）与 "DLV"（DeliveryService）两种生成规则 */
    private static final Pattern DELIVERY_NO = Pattern.compile("DLV?\\d{8,}");

    private final JdbcTemplate jdbc;
    private final OrderTools orderTools;

    public DeliveryFormController(JdbcTemplate jdbc, OrderTools orderTools) {
        this.jdbc = jdbc;
        this.orderTools = orderTools;
    }

    /** 表单 schema：字段定义 + 下拉选项，前端据此渲染配送单卡片 */
    @PostMapping("/delivery/schema")
    public Map<String, Object> schema(@RequestBody(required = false) Map<String, String> req) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "delivery_form");
        body.put("title", "配送单");
        body.put("hint", "填写后直接创建配送单并完成拣货出库；带 * 为必填，未指定仓库时自动选择库存充足的仓库。");

        List<Map<String, Object>> warehouses = jdbc.queryForList(
                "SELECT code AS value, CONCAT(name, '(', code, ')') AS label FROM wms_warehouse WHERE status = '启用' ORDER BY id");
        body.put("warehouseOptions", warehouses);

        List<Map<String, Object>> items = jdbc.queryForList("""
                SELECT i.sku AS value, CONCAT(i.name, '[', i.sku, ']') AS label,
                       i.name, i.unit,
                       COALESCE((SELECT SUM(s.quantity) FROM wms_stock s WHERE s.item_id = i.id), 0) AS total_stock
                FROM wms_item i ORDER BY i.id
                """);
        body.put("itemOptions", items);

        List<Map<String, Object>> employees = jdbc.queryForList("""
                SELECT e.id AS value, CONCAT(e.name, '(ID:', e.id, ')') AS label, e.name
                FROM employee e WHERE e.status = '在职' ORDER BY e.id
                """);
        body.put("employeeOptions", employees);

        List<Map<String, Object>> fields = new ArrayList<>();
        fields.add(field("sku", "商品", "select", true, "按 SKU 或名称搜索", items));
        fields.add(field("quantity", "配送数量", "number", true, "正整数", null));
        fields.add(field("customer", "客户名称", "text", true, "收货客户或部门，例如：榆舍酒店", null));
        fields.add(field("customerPhone", "客户联系方式", "text", true, "收货人手机号", null));
        fields.add(field("address", "配送地址", "text", true, "详细地址，例如：广州市越秀区榆舍酒店", null));
        fields.add(field("employee", "配送员工", "select", true, "选择在职配送人员", employees));
        fields.add(field("warehouseCode", "发货仓库", "select", false, "留空自动选择库存充足的仓库", warehouses));
        fields.add(field("deadline", "截止时间", "date", false, "留空默认 5 天后", null));

        Map<String, Object> price = field("unitPrice", "单价（元）", "number", false, "选填，用于计算订单金额", null);
        price.put("min", 0);
        price.put("precision", 2);
        fields.add(price);
        fields.add(field("remark", "备注", "text", false, "楼层、联系人电话等补充信息", null));
        body.put("fields", fields);
        return body;
    }

    /** 提交配送单：参数校验 → 必要时先建订单 → 创建配送单并拣货出库 */
    @PostMapping("/delivery/submit")
    public Map<String, Object> submit(@RequestBody DeliveryForm form) {
        Map<String, Object> body = new LinkedHashMap<>();
        String sku = trim(form.sku());
        String customer = trim(form.customer());
        String address = trim(form.address());
        String employee = trim(form.employee());
        Integer quantity = form.quantity();

        if (sku == null) {
            return fail(body, "请选择要配送的商品。");
        }
        if (quantity == null || quantity <= 0) {
            return fail(body, "配送数量必须是正整数。");
        }
        if (quantity > 100_000) {
            return fail(body, "配送数量不能超过 100000。");
        }
        if (customer == null) {
            return fail(body, "请填写客户名称。");
        }
        if (address == null) {
            return fail(body, "请填写配送地址。");
        }
        if (employee == null) {
            return fail(body, "请选择配送员工。");
        }
        if (form.unitPrice() != null && form.unitPrice() < 0) {
            return fail(body, "单价不能为负数。");
        }

        String remark = trim(form.remark());
        String customerPhone = trim(form.customerPhone());
        if (customerPhone == null) {
            return fail(body, "请填写客户联系方式。");
        }
        // 截止时间：未填写时默认 5 天后
        java.time.LocalDateTime deadline = parseDeadline(form.deadline());
        if (deadline == null) {
            deadline = java.time.LocalDateTime.now().plusDays(5);
        }
        String orderNo = trim(form.orderNo());
        boolean orderCreated = false;
        // 下拉选的是员工ID，回复里展示姓名（找不到时退回原值）
        String employeeName = resolveEmployeeName(employee);
        String itemLabel = resolveItemLabel(sku);

        // 没有现成订单时，先按表单内容建销售订单（单商品订单，与业务表模型一致）
        if (orderNo == null) {
            String createResult = orderTools.createOrder(customer, sku, quantity, form.unitPrice(), remark);
            orderNo = extract(ORDER_NO, createResult);
            if (orderNo == null) {
                return fail(body, "创建订单失败：" + createResult);
            }
            orderCreated = true;
        }

        String deliveryResult = orderTools.createDelivery(orderNo, employee,
                trim(form.warehouseCode()), address, remark,
                deadline.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                customerPhone);
        String deliveryNo = extract(DELIVERY_NO, deliveryResult);
        if (deliveryNo == null) {
            String prefix = orderCreated ? "已创建订单 " + orderNo + "，但配送单创建失败：" : "配送单创建失败：";
            return fail(body, prefix + deliveryResult);
        }

        body.put("status", "done");
        body.put("orderNo", orderNo);
        body.put("deliveryNo", deliveryNo);
        body.put("reply", "配送单已创建：" + deliveryNo
                + "\n- 订单号：" + orderNo + (orderCreated ? "（已按表单新建订单）" : "")
                + "\n- 商品：" + itemLabel + " × " + quantity
                + "\n- 客户：" + customer + "（" + customerPhone + "）"
                + "\n- 地址：" + address
                + "\n- 配送员工：" + employeeName
                + "\n- 截止时间：" + deadline.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                + "\n配送完成后请在「配送管理」中确认送达。");
        return body;
    }

    /** 解析表单截止时间（yyyy-MM-dd HH:mm:ss / yyyy-MM-dd）；解析失败返回 null 由调用方兜底 */
    private static java.time.LocalDateTime parseDeadline(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.trim().replace('T', ' ');
        for (String p : new String[]{"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd"}) {
            try {
                return p.length() > 10
                        ? java.time.LocalDateTime.parse(v, java.time.format.DateTimeFormatter.ofPattern(p))
                        : java.time.LocalDateTime.parse(v + " 23:59:59",
                                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    /** 员工ID → 姓名；传入的本来就是姓名时原样返回 */
    private String resolveEmployeeName(String employee) {
        List<String> names = jdbc.queryForList(
                "SELECT name FROM employee WHERE id = ? OR name = ? LIMIT 1",
                String.class, employee.matches("\\d+") ? Long.parseLong(employee) : -1L, employee);
        return names.isEmpty() ? employee : names.get(0);
    }

    /** SKU → 商品名[SKU]；找不到时原样返回 */
    private String resolveItemLabel(String sku) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT name FROM wms_item WHERE sku = ? LIMIT 1", sku);
        return rows.isEmpty() ? sku : rows.get(0).get("name") + "[" + sku + "]";
    }

    private static Map<String, Object> fail(Map<String, Object> body, String reply) {
        body.put("status", "error");
        body.put("reply", reply);
        return body;
    }

    private static String extract(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group() : null;
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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

    public record DeliveryForm(String sku, Integer quantity, String customer, String customerPhone,
                               String address, String employee, String warehouseCode, Double unitPrice,
                               String deadline, String remark, String orderNo) {
    }
}
