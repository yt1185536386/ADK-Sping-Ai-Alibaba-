package com.hr.agent.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 会话消息（历史查询/审计）读写。
 * 注意：Agent 的多轮上下文由 MysqlSaver 持久化，本表只用于列表与历史展示。
 */
@Component
public class ConversationStore {

    private final JdbcTemplate jdbc;

    public ConversationStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 保存一条消息，返回自增主键 id（前端删除/重新生成要用）；username 用于会话归属隔离 */
    public long save(String threadId, String role, String provider, String model, String content, String username) {
        jdbc.update("INSERT INTO chat_message(thread_id, role, provider, model, content, username) VALUES(?,?,?,?,?,?)",
                threadId, role, provider, model, content, username);
        Long id = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1 : id;
    }

    /** 会话列表：按最后活跃时间倒序；普通用户只能看到自己的会话，ADMIN 看全部。
     *  username 为 NULL 的历史会话（归属改造前的数据）对所有登录用户可见，保持兼容。 */
    public List<Map<String, Object>> listThreads(String username, boolean admin, int limit) {
        if (admin) {
            return jdbc.queryForList(THREADS_SQL + "GROUP BY thread_id ORDER BY last_at DESC LIMIT ?", limit);
        }
        return jdbc.queryForList(THREADS_SQL
                + "WHERE username IS NULL OR username = ? GROUP BY thread_id ORDER BY last_at DESC LIMIT ?",
                username, limit);
    }

    private static final String THREADS_SQL = """
            SELECT thread_id,
                   COUNT(*)                AS message_count,
                   MIN(created_at)         AS started_at,
                   MAX(created_at)         AS last_at,
                   MAX(CASE WHEN role = 'user' THEN content END) AS first_question
            FROM chat_message
            """;

    /** 会话归属者：该会话最早一条带 username 的消息的作者；历史会话（无 username）返回 null */
    public String ownerOf(String threadId) {
        return jdbc.query("SELECT username FROM chat_message WHERE thread_id = ? AND username IS NOT NULL "
                        + "ORDER BY id ASC LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null, threadId);
    }

    /** 当前用户能否访问该会话：ADMIN 全能；无归属的历史会话任何登录用户可用；其余仅限本人 */
    public boolean canAccess(String threadId, String username, boolean admin) {
        if (admin) {
            return true;
        }
        String owner = ownerOf(threadId);
        return owner == null || owner.equals(username);
    }

    /** 单条消息所属会话的归属者（删除消息前的归属校验用）；消息不存在时返回 null */
    public String ownerOfMessage(long id) {
        return jdbc.query("SELECT (SELECT c2.username FROM chat_message c2 "
                        + "WHERE c2.thread_id = c1.thread_id AND c2.username IS NOT NULL ORDER BY c2.id ASC LIMIT 1) "
                        + "FROM chat_message c1 WHERE c1.id = ?",
                rs -> rs.next() ? rs.getString(1) : null, id);
    }

    /** 某个会话的历史消息（带消息 id，前端按 id 做删除/重新生成定位） */
    public List<Map<String, Object>> listMessages(String threadId, int limit) {
        return jdbc.queryForList(
                "SELECT id, role, provider, model, content, created_at FROM chat_message " +
                        "WHERE thread_id = ? ORDER BY id ASC LIMIT ?", threadId, limit);
    }

    public int deleteThread(String threadId) {
        return jdbc.update("DELETE FROM chat_message WHERE thread_id = ?", threadId);
    }

    /** 按消息 id 删除单条消息 */
    public int deleteMessage(long id) {
        return jdbc.update("DELETE FROM chat_message WHERE id = ?", id);
    }

    /**
     * 删除一条消息及其配对的 AI 回答。
     * 删用户消息时，把它后面紧跟的 assistant 消息一并删掉（一问一答视为一对）。
     * 返回总共删除的行数。
     */
    public int deleteMessageWithReply(long id) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("SELECT id, thread_id, role FROM chat_message WHERE id = ?", id);
        } catch (Exception e) {
            return 0;
        }
        String threadId = String.valueOf(row.get("thread_id"));
        String role = String.valueOf(row.get("role"));
        int deleted = deleteMessage(id);
        if ("user".equals(role)) {
            // 找这条用户消息之后、同会话内的第一条 assistant 回复一并删除
            List<Long> replyIds = jdbc.queryForList(
                    "SELECT id FROM chat_message WHERE thread_id = ? AND id > ? AND role = 'assistant' ORDER BY id ASC LIMIT 1",
                    Long.class, threadId, id);
            for (Long rid : replyIds) {
                deleted += deleteMessage(rid);
            }
        }
        return deleted;
    }
}
