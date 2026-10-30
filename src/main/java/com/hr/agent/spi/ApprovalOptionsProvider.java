package com.hr.agent.spi;

import java.util.List;
import java.util.Map;

/**
 * 审批项的「候选选项」提供者 —— 让确认框能把一组对象逐条列出来给用户勾选。
 *
 * <p><b>为什么需要它：</b>有些写工具的入参本来就是"一批对象"，
 * 例如"把哪几件资产调拨给某人"。确认框默认只给一个 JSON 文本框，
 * 用户既看不出 <code>AST-20260930-0002</code> 到底是哪件东西，也不知道除了这几件还有哪些可选 ——
 * 想换一件就得手写 JSON。实现本接口后，审批项会附带一份候选清单，
 * 前端渲染成逐条勾选，勾选结果再写回参数。
 *
 * <p><b>返回约定：</b>不需要选项的工具返回 {@link Options#NONE}（空清单），前端就照旧只显示参数框。
 * 清单里每一项至少要带：
 * <ul>
 *   <li>{@code value} —— 勾选后写回参数的值；</li>
 *   <li>{@code label} —— 给用户看的文案（要能分辨出是哪一件东西）；</li>
 *   <li>{@code checked} —— 是否默认勾选（即模型本来选中的那些）。</li>
 * </ul>
 */
public interface ApprovalOptionsProvider {

    /**
     * 为该工具生成候选清单。
     *
     * @param toolName      待审批的工具名，如 allocate_asset
     * @param argumentsJson 模型给出的原始参数 JSON（用户此时还没改过）
     * @return 候选选项；不需要时返回 {@link Options#NONE}
     */
    Options forTool(String toolName, String argumentsJson);

    /**
     * @param field 勾选结果写回哪个参数字段（如 assetNos），可为 null
     * @param hint  勾选区上方的引导语，可为 null
     * @param items 候选条目（value / label / checked）
     */
    record Options(String field, String hint, List<Map<String, Object>> items) {

        /** 空选项：表示这个工具不需要"逐条勾选" */
        public static final Options NONE = new Options(null, null, List.of());

        public boolean isEmpty() {
            return items == null || items.isEmpty();
        }
    }
}
