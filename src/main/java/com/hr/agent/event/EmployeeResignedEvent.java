package com.hr.agent.event;

import org.springframework.context.ApplicationEvent;

/**
 * 员工离职事件：在员工状态改为「离职」后发布，触发后续联动操作（如资产回收）。
 *
 * <p>设计说明：
 * <ul>
 *   <li>事件在员工状态变更的<b>事务提交后</b>发布，确保资产回收看到的是已提交的离职状态</li>
 *   <li>资产回收在独立事务中执行，失败不影响已完成的离职状态变更</li>
 *   <li>若回收失败，可通过日志告警人工介入，员工名下资产仍可通过 return_asset 手动退回</li>
 * </ul>
 */
public class EmployeeResignedEvent extends ApplicationEvent {

    private final Long employeeId;
    private final String employeeName;

    public EmployeeResignedEvent(Object source, Long employeeId, String employeeName) {
        super(source);
        this.employeeId = employeeId;
        this.employeeName = employeeName;
    }

    public Long getEmployeeId() {
        return employeeId;
    }

    public String getEmployeeName() {
        return employeeName;
    }
}
