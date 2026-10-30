package com.hr.agent.event;

import com.hr.agent.service.AssetService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 员工离职事件监听器：在员工状态改为「离职」后，异步回收其名下全部资产。
 *
 * <p>设计说明：
 * <ul>
 *   <li>使用 {@link TransactionalEventListener} 确保在员工状态变更事务提交后才执行，
 *       避免"资产已回收但员工状态回滚"的中间态</li>
 *   <li>使用 {@link Async} 异步执行，不阻塞离职操作的主流程响应</li>
 *   <li>回收失败只记录日志，不影响已完成的离职状态变更；可通过 return_asset 手动补偿</li>
 * </ul>
 */
@Component
public class AssetRecycleOnResignListener {

    private static final Logger log = LoggerFactory.getLogger(AssetRecycleOnResignListener.class);

    private final AssetService assetService;

    public AssetRecycleOnResignListener(AssetService assetService) {
        this.assetService = assetService;
    }

    /**
     * 监听员工离职事件，异步回收其名下资产。
     *
     * @param event 离职事件
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onEmployeeResigned(EmployeeResignedEvent event) {
        Long employeeId = event.getEmployeeId();
        String employeeName = event.getEmployeeName();

        log.info("开始回收离职员工名下资产：employeeId={}, name={}", employeeId, employeeName);
        try {
            String result = assetService.returnAllOfEmployee(employeeId, employeeName, "离职自动回收");
            if (result != null) {
                log.info("离职员工资产回收完成：employeeId={}, name={}, 明细：{}", employeeId, employeeName, result);
            } else {
                log.info("离职员工无资产需要回收：employeeId={}, name={}", employeeId, employeeName);
            }
        } catch (Exception e) {
            // 回收失败只记录日志，不影响已完成的离职状态变更
            // 运维可通过日志告警人工介入，或使用 return_asset 手动补偿
            log.error("离职员工资产回收失败：employeeId={}, name={}, 错误：{}", employeeId, employeeName, e.getMessage(), e);
        }
    }
}
