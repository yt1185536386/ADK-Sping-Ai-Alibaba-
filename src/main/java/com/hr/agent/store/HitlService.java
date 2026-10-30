package com.hr.agent.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * HITL 人工审批单存储（表 hitl_approval）。
 * <p>写操作触发中断时落一张 PENDING 单，用户确认或拒绝后更新状态并记录恢复执行的结果。
 * 待审批的上下文（工具名、toolCall id、参数、会话、模块）全部落库，因此重启后依然可以完成审批。
 * <p>注意 toolCallId 必须原样保存：恢复执行时框架靠它把人工反馈匹配回对应的工具调用。
 */
@Component
public class HitlService {

    private final JdbcTemplate jdbc;

    public HitlService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Approval(String id, String batchId, String threadId, String moduleKey, String provider, String model,
                           String toolName, String toolCallId, String arguments, String description, String status,
                           String feedback, String editedArguments, String result,
                           Timestamp createdAt, Timestamp updatedAt) {

        public boolean pending() {
            return "PENDING".equals(status);
        }
    }

    private static final RowMapper<Approval> MAPPER = (rs, i) -> new Approval(
            rs.getString("id"),
            rs.getString("batch_id"),
            rs.getString("thread_id"),
            rs.getString("module_key"),
            rs.getString("provider"),
            rs.getString("model"),
            rs.getString("tool_name"),
            rs.getString("tool_call_id"),
            rs.getString("arguments"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getString("feedback"),
            rs.getString("edited_arguments"),
            rs.getString("result"),
            rs.getTimestamp("created_at"),
            rs.getTimestamp("updated_at"));

    public static String newBatchId() {
        return "BATCH-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
    }

    /** 新建一张待审批单，返回审批单号 */
    public String create(String batchId, String threadId, String moduleKey, String provider, String model,
                         String toolName, String toolCallId, String arguments, String description) {
        String id = "HITL-" + System.currentTimeMillis() + "-" + UUID.randomUUID().toString().substring(0, 6);
        jdbc.update("INSERT INTO hitl_approval"
                        + "(id, batch_id, thread_id, module_key, provider, model, tool_name, tool_call_id, `arguments`, description, status) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING')",
                id, batchId, threadId, moduleKey, provider, model, toolName, toolCallId, arguments, description);
        return id;
    }

    public Optional<Approval> find(String id) {
        return jdbc.query("SELECT * FROM hitl_approval WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** 同一个批次的所有审批单（一次中断可能包含多个待审批工具） */
    public List<Approval> listByBatch(String batchId) {
        return jdbc.query("SELECT * FROM hitl_approval WHERE batch_id = ? ORDER BY created_at", MAPPER, batchId);
    }

    /** 某会话下还没处理的审批单 */
    public List<Approval> listPending(String threadId) {
        return jdbc.query("SELECT * FROM hitl_approval WHERE thread_id = ? AND status = 'PENDING' "
                + "ORDER BY created_at", MAPPER, threadId);
    }

    /** 跨会话的全部待审批单（审批员工作台用；按时间倒序，最新在前） */
    public List<Approval> listAllPending(int limit) {
        return jdbc.query("SELECT * FROM hitl_approval WHERE status = 'PENDING' "
                + "ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
    }

    /** 最近的审批记录（供前端展示或排查） */
    public List<Approval> listRecent(int limit) {
        return jdbc.query("SELECT * FROM hitl_approval ORDER BY created_at DESC LIMIT ?", MAPPER, limit);
    }

    /** 记录用户决策：APPROVED / REJECTED / EDITED */
    public void decide(String id, String status, String feedback, String editedArguments) {
        jdbc.update("UPDATE hitl_approval SET status = ?, feedback = ?, edited_arguments = ? WHERE id = ?",
                status, feedback, editedArguments, id);
    }

    /** 恢复执行后的回复 */
    public void saveResult(String id, String result) {
        jdbc.update("UPDATE hitl_approval SET result = ? WHERE id = ?", result, id);
    }

    /** 把某会话下仍未处理的审批单作废（用户重开一轮对话时用） */
    public int expirePending(String threadId) {
        return jdbc.update("UPDATE hitl_approval SET status = 'EXPIRED' WHERE thread_id = ? AND status = 'PENDING'",
                threadId);
    }
}
