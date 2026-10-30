package com.hr.agent.util;

import java.util.regex.Pattern;

/**
 * 工具入参校验。
 *
 * 【设计约定】校验方法一律**返回可读的错误说明**（null 表示通过），而不是抛异常。
 * 原因：这些方法服务于 @Tool 方法，返回值会作为工具执行结果回给模型。
 * 直接把"错在哪、应该是什么格式"告诉模型，模型才能自我纠正并重新调用；
 * 抛异常则会被上层包装成一句没有信息量的报错，模型只能反复重试同样的错误参数。
 */
public final class Args {

    private Args() {
    }

    private static final Pattern PHONE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern EMAIL = Pattern.compile("^[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+$");
    private static final Pattern SKU = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$");
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9_-]{1,31}$");

    /** 单次业务操作的数量上限：挡住"入库 999999999"这类越界输入 */
    public static final int MAX_QUANTITY = 1_000_000;

    /** 文本类字段的通用长度上限 */
    public static final int MAX_TEXT = 100;

    /** 列表类工具单次返回的最大条数，避免上下文被撑爆 */
    public static final int MAX_LIST = 50;

    public static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            return field + "不能为空，请补充后重试。";
        }
        return null;
    }

    public static String text(String value, String field, int maxLen, boolean allowBlank) {
        if (value == null || value.isBlank()) {
            return allowBlank ? null : field + "不能为空，请补充后重试。";
        }
        String v = value.trim();
        if (v.length() > maxLen) {
            return field + "过长（最多 " + maxLen + " 个字符，当前 " + v.length() + "），请精简后重试。";
        }
        return null;
    }

    /** 姓名/名称：不允许控制字符，长度受限 */
    public static String name(String value, String field) {
        String bad = text(value, field, 50, false);
        if (bad != null) {
            return bad;
        }
        if (value.trim().chars().anyMatch(Character::isISOControl)) {
            return field + "包含非法字符，请重新提供。";
        }
        return null;
    }

    public static String phone(String value) {
        if (value == null || value.isBlank()) {
            return null; // 手机号可选
        }
        String v = value.trim();
        if (!PHONE.matcher(v).matches()) {
            return "手机号「" + v + "」格式不正确。请提供 11 位中国大陆手机号（1 开头，第二位 3-9），例如 13800138000；"
                    + "用户提供的是座机/分机号时，请先向用户确认正确的手机号。";
        }
        return null;
    }

    public static String email(String value) {
        if (value == null || value.isBlank()) {
            return null; // 邮箱可选
        }
        String v = value.trim();
        if (v.length() > 100) {
            return "邮箱过长（最多 100 个字符）。";
        }
        if (!EMAIL.matcher(v).matches()) {
            return "邮箱「" + v + "」格式不正确，应形如 someone@example.com。";
        }
        return null;
    }

    public static String gender(String value) {
        if (value == null || value.isBlank()) {
            return null; // 性别可选
        }
        String v = value.trim();
        if (!"男".equals(v) && !"女".equals(v)) {
            return "性别只能是「男」或「女」（收到的是「" + v + "」）。";
        }
        return null;
    }

    /** 数量：必须为正整数且不超过上限 */
    public static String quantity(Integer value, String field) {
        if (value == null) {
            return field + "不能为空。";
        }
        if (value <= 0) {
            return field + "必须大于 0（收到 " + value + "）。";
        }
        if (value > MAX_QUANTITY) {
            return field + "过大（收到 " + value + "，单次上限 " + MAX_QUANTITY + "）。"
                    + "数量确实很大时，请拆成多笔办理，或先与用户核对是否多写了一个零。";
        }
        return null;
    }

    /** 非负整数（安全库存等） */
    public static String nonNegative(Integer value, String field) {
        if (value == null) {
            return field + "不能为空。";
        }
        if (value < 0) {
            return field + "不能为负数（收到 " + value + "）。";
        }
        if (value > MAX_QUANTITY) {
            return field + "过大（收到 " + value + "，上限 " + MAX_QUANTITY + "）。";
        }
        return null;
    }

    public static String sku(String value) {
        String bad = required(value, "SKU 编码");
        if (bad != null) {
            return bad;
        }
        String v = value.trim();
        if (!SKU.matcher(v).matches()) {
            return "SKU 编码「" + v + "」格式不正确：只能包含字母、数字、连字符与下划线，长度 2-32，且以字母或数字开头（例如 SKU-1003）。";
        }
        return null;
    }

    public static String code(String value, String field) {
        String bad = required(value, field);
        if (bad != null) {
            return bad;
        }
        String v = value.trim();
        if (!CODE.matcher(v).matches()) {
            return field + "「" + v + "」格式不正确：只能包含字母、数字、连字符与下划线，长度 2-32（例如 WH03）。";
        }
        return null;
    }

    /** trim，null 安全 */
    public static String clean(String value) {
        if (value == null) {
            return null;
        }
        String v = value.trim();
        return v.isEmpty() ? null : v;
    }
}
