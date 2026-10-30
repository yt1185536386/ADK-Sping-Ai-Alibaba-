package com.hr.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * 给人工确认框提供「明细预览」的扩展点。
 *
 * <p><b>解决什么问题：</b>批量类写操作（例如一次入库 100 个商品）如果只把参数 JSON 甩到确认框里，
 * 用户根本看不出自己到底要入库哪些东西、各多少件、哪些是新建商品 —— 只能盲签。
 * 而真正可怕的方案是"一个商品一条工具调用"，那会弹 100 次确认框，等于没法用。
 *
 * <p><b>所以：</b>一个批量工具只产生<em>一次</em>审批，审批项上挂一张<em>只读</em>明细表，
 * 用户在表格里核对清楚后点一次「确认执行」。
 *
 * <p><b>约束：{@link #forTool} 必须是只读的。</b>
 * 它在用户点确认<em>之前</em>就会被调用，而且用户可能拒绝或修改参数。
 * 任何写库、占号、发单号的动作都不能发生在预览阶段 —— 否则用户拒绝之后库里会留下脏数据
 * （例如白白消耗掉一批 SKU 序号）。
 *
 * <p>与 {@link ApprovalOptionsProvider} 的分工：
 * 「从一批已有对象里挑几个」→ 用 Options（可勾选）；
 * 「一屏看清楚这次要批量写入什么」→ 用 Preview（只读表格）。两者可同时挂在一个审批项上。
 */
public interface ApprovalPreviewProvider {

    /**
     * 为某个待审批的工具调用生成明细预览。
     *
     * @param toolName      工具名
     * @param argumentsJson 模型生成的原始参数 JSON
     * @return 不需要预览就返回 {@link Preview#NONE}
     */
    Preview forTool(String toolName, String argumentsJson);

    /**
     * 一张只读明细表。
     *
     * @param title   表格标题，如「本次入库明细」
     * @param hint    表格上方的操作提示
     * @param summary 表格下方的一句话汇总（行数、合计件数、涉及仓库等）
     * @param columns 列定义，决定渲染顺序
     * @param rows    行数据，key 与 {@link Column#key()} 对应
     */
    record Preview(String title, String hint, String summary,
                   List<Column> columns, List<Map<String, Object>> rows) {

        public static final Preview NONE = new Preview(null, null, null, List.of(), List.of());

        public boolean isEmpty() {
            return rows == null || rows.isEmpty();
        }

        /** 便捷构造：列用「key,label」成对给出 */
        public static Preview of(String title, String hint, String summary, List<Column> columns,
                                 List<Map<String, Object>> rows) {
            return new Preview(title, hint, summary, columns, rows);
        }
    }

    /**
     * 列定义。
     *
     * @param key   对应 rows 里的字段名
     * @param label 表头文字
     * @param align "left" / "right"（数量类右对齐更易核对）
     * @param wide  是否允许换行显示长文本（如规格、备注）
     */
    record Column(String key, String label, String align, boolean wide) {

        public static Column of(String key, String label) {
            return new Column(key, label, "left", false);
        }

        public static Column num(String key, String label) {
            return new Column(key, label, "right", false);
        }

        public static Column wide(String key, String label) {
            return new Column(key, label, "left", true);
        }
    }
}
