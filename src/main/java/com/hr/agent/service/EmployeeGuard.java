package com.hr.agent.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 员工状态守卫：集中管理「离职员工能否进行某项操作」的校验规则。
 *
 * <p>规则说明：
 * <ul>
 *   <li>调岗（transfer_position）：仅限在职员工，离职员工拦截</li>
 *   <li>调拨资产（allocate_asset）：仅限在职员工，离职员工拦截</li>
 *   <li>更新联系方式（update_employee_contact）：离职员工也允许（联系方式是独立信息，与在职状态无关）</li>
 * </ul>
 *
 * <p>之前这些判断散落在 HrTools / AssetService 各处，规则不统一。
 * 现在收拢到本类，一处修改、全局生效。
 */
@Component
public class EmployeeGuard {

    /** 员工状态：在职 */
    public static final String STATUS_ON_DUTY = "在职";
    /** 员工状态：离职 */
    public static final String STATUS_OFF_DUTY = "离职";

    private final JdbcTemplate jdbc;

    public EmployeeGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 校验员工是否存在且为在职状态。
     *
     * @param employeeId 员工ID
     * @param operation  操作名称（用于错误提示），如「调岗」「调拨资产」
     * @return null 表示校验通过；非 null 为错误提示信息
     */
    public String requireOnDuty(Long employeeId, String operation) {
        Map<String, Object> emp = loadEmployee(employeeId);
        if (emp == null) {
            return "未找到员工 [ID=" + employeeId + "]，请先确认员工ID。";
        }
        String status = String.valueOf(emp.get("status"));
        String name = String.valueOf(emp.get("name"));
        if (!STATUS_ON_DUTY.equals(status)) {
            return "员工「" + name + "」[ID=" + employeeId + "] 当前状态为「" + status
                    + "」，只有" + STATUS_ON_DUTY + "员工才能" + operation + "。";
        }
        return null;
    }

    /**
     * 校验员工是否存在（不限状态）。
     *
     * @param employeeId 员工ID
     * @param operation  操作名称
     * @return null 表示校验通过；非 null 为错误提示信息
     */
    public String requireExists(Long employeeId, String operation) {
        Map<String, Object> emp = loadEmployee(employeeId);
        if (emp == null) {
            return "未找到员工 [ID=" + employeeId + "]，请先确认员工ID。";
        }
        return null;
    }

    /**
     * 判断员工是否为在职状态。
     *
     * @param employeeId 员工ID
     * @return true=在职，false=离职或不存在
     */
    public boolean isOnDuty(Long employeeId) {
        Map<String, Object> emp = loadEmployee(employeeId);
        return emp != null && STATUS_ON_DUTY.equals(String.valueOf(emp.get("status")));
    }

    /**
     * 加载员工信息（内部使用）。
     */
    private Map<String, Object> loadEmployee(Long employeeId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, name, status FROM employee WHERE id = ?", employeeId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
