package com.hr.agent.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * SKU 生成器：商品编码不再由用户/模型手填，统一由系统发号。
 *
 * <p>编码规则：{@code SKU-<时间戳到秒>-<4位自增序号>}，例如 {@code SKU-20260930150301-0007}。
 *
 * <p><b>为什么需要一张序列表：</b>时间戳只到秒，同一秒内批量新建 100 个商品时后 99 个会撞号；
 * 靠 {@code MAX(sku)} 或先查后写都不行 —— 并发下两个请求会查到同一个"最大号"。
 * 这里用 {@code INSERT ... ON DUPLICATE KEY UPDATE next_val = next_val + 1}
 * 让 MySQL 在主键行上加锁自增，天然原子，取到的号一定不重复。
 *
 * <p><b>必须在事务内调用：</b>取号语句持有行锁直到事务提交，同一事务里再读到的才是自己的号；
 * 若在自动提交模式下调用，两次取号之间可能被别的线程插进来，读回的序号会偏大。
 */
@Component
public class SkuGenerator {

    /** SKU 统一前缀（用户指定：统一固定前缀，不按类别分） */
    public static final String PREFIX = "SKU";

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** 单秒内序号上限；超过就进位到下一秒（实际用不到，纯兜底） */
    private static final int MAX_SEQ_PER_SECOND = 9999;

    private final JdbcTemplate jdbc;

    public SkuGenerator(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 生成下一个 SKU。**调用方必须已在事务中**（见类注释）。
     *
     * @return 形如 {@code SKU-20260930150301-0001} 的唯一 SKU
     */
    public String next() {
        // 跨秒推进：极端情况下同一秒取满 9999 个号，就往后等到下一秒再取，避免序号溢出位数
        for (int guard = 0; guard < 5; guard++) {
            String ts = LocalDateTime.now().format(TS_FMT);
            jdbc.update("""
                    INSERT INTO wms_sku_seq(ts_key, next_val) VALUES(?, 1)
                    ON DUPLICATE KEY UPDATE next_val = next_val + 1
                    """, ts);
            Integer seq = jdbc.queryForObject(
                    "SELECT next_val FROM wms_sku_seq WHERE ts_key = ?", Integer.class, ts);
            int n = (seq == null || seq <= 0) ? 1 : seq;
            if (n <= MAX_SEQ_PER_SECOND) {
                return PREFIX + "-" + ts + "-" + String.format("%04d", n);
            }
        }
        // 理论上到不了这里；真到了就用时间戳兜底，宁可编码难看也不能发出空值
        return PREFIX + "-" + LocalDateTime.now().format(TS_FMT) + "-" + System.nanoTime() % 10000;
    }

    /** SKU 的编码格式（只给格式本身，别在说明里再套一层解释，否则拼进句子里会很啰嗦） */
    public static String formatHint() {
        return PREFIX + "-<yyyymmddhhmmss>-<四位数序号>";
    }
}
