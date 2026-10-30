package com.hr.agent.service;

import com.hr.agent.util.Args;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hr.agent.spi.ApprovalOptionsProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 资产台账服务：一物一码的实物资产「登记 → 调拨 → 退回 → 销毁」。
 *
 * <p><b>为什么单独抽一层：</b>资产既属于仓库动作（登记/调拨/退回/销毁由 WMS 模块发起），
 * 又属于员工属性（个人物品栏、离职自动退回由 HR 模块发起）。
 * 两个模块共用这一份逻辑，避免同一条规则写两遍、时间久了两个模块的账对不上。
 *
 * <p><b>两本账的关系：</b>
 * {@code wms_stock} 是按「仓库 × 商品」记数量的<em>散装库存</em>；
 * {@code wms_asset} 是登记之后的<em>一物一码实物</em>。
 * 「登记」= 从 wms_stock 划出 N 件、变成 N 个资产实例，两边合计守恒、不会重复计算。
 * 登记之后的这件东西就只认资产账，调拨/退回/销毁都不再动 wms_stock。
 *
 * <p><b>「同一个物品只能调拨给一个用户」怎么保证：</b>
 * 表结构上每件资产只有一行、只有一个 {@code holder_employee_id}；
 * 调拨语句是 {@code UPDATE ... WHERE asset_no=? AND status='在库'}，
 * 靠<em>影响行数</em>判断是否抢到——两个并发请求同时调拨同一件时，只有一个能改到 1 行，
 * 另一个拿到 0 行并被如实告知"已被某人领用"。数据库层兜底，不依赖应用层判断。
 *
 * <p><b>事务：</b>所有写操作都在 {@link TransactionTemplate} 内完成，失败整体回滚
 * （不会出现"资产状态改了但流水没写"这种半截状态）。
 * 这里刻意不用 {@code @Transactional} 注解：本类的方法会被带 {@code @Tool} 的类调用，
 * 走 CGLIB 代理容易出现注解丢失的坑，显式事务模板更稳。
 */
@Component
public class AssetService implements ApprovalOptionsProvider {

    /** 资产状态：在仓库里、未被领用 */
    public static final String IN_STOCK = "在库";
    /** 资产状态：已调拨给某位在职员工 */
    public static final String ALLOCATED = "已调拨";
    /** 资产状态：已出库（随出库单离开仓库，不再属于在册物品） */
    public static final String OUTBOUND = "已出库";
    /** 资产状态：已销毁（从"全部物品清单"划掉，但流水留痕可追溯） */
    public static final String SCRAPPED = "已销毁";

    /** 附在写操作结果末尾：告知模型人工可能改过参数，且以返回值为准 */
    // NOTE: GROUND_TRUTH_HINT 在 HrTools/OrderTools/WmsTools/AssetService 四处有意重复（内容必须保持一致），
    // 任一修改需同步其余三处；确有变更时再抽取到 util 包统一。
    private static final String GROUND_TRUTH_HINT =
            "\n注：以上为数据库实际写入的最终值。若与你的调用参数或用户原话不一致，说明该值已被人工在确认框中修改，"
                    + "请直接按本返回值汇报，不要再调用写工具把值改回去。";

    private static final DateTimeFormatter READ_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    /** 员工状态守卫：集中管理离职员工能否进行某项操作的校验规则 */
    private final EmployeeGuard employeeGuard;

    public AssetService(JdbcTemplate jdbc, TransactionTemplate tx, EmployeeGuard employeeGuard) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.employeeGuard = employeeGuard;
    }

    // ==================== 登记 ====================

    /**
     * 登记资产：从「在库」状态的资产中划出 N 件标记为可分配资产。
     *
     * <p>统一资产模型下，所有入库物品已自动生成为 wms_asset 记录（状态=在库）。
     * 本方法的作用是把其中 N 件从「散装」标记为「已登记」，使其可被调拨。
     * 不再需要扣减 wms_stock —— 库存账由资产状态自动汇总。
     *
     * @return 可直接回给模型的中文说明（成功含每个资产编号；失败说明原因）
     */
    public String register(Long itemId, Long warehouseId, int count, String operator) {
        Map<String, Object> item = loadItem(itemId);
        if (item == null) {
            return "登记失败：未找到商品 [ID=" + itemId + "]。";
        }
        Map<String, Object> wh = loadWarehouse(warehouseId);
        if (wh == null) {
            return "登记失败：未找到仓库 [ID=" + warehouseId + "]。";
        }

        List<String> assetNos = null;
        String conflictMsg = null;

        // 资产编号按「日期 + 当日流水号」生成，并发登记同一批时可能撞号（uk_asset_no 会拦下），
        // 撞了就整笔回滚重试，最多 3 次；编号始终在事务内生成，避免生成后没插进去留下空号。
        for (int attempt = 0; attempt < 3 && assetNos == null; attempt++) {
            try {
                assetNos = tx.execute(status -> {
                    // 查询该商品在该仓库下、状态为「在库」且未被标记为「已登记」的资产
                    // 通过 remark 字段区分：入库时 remark="入库单 xxx"，登记后 remark="由库存登记为资产"
                    List<Map<String, Object>> available = jdbc.queryForList("""
                            SELECT id, asset_no FROM wms_asset
                            WHERE item_id = ? AND warehouse_id = ? AND status = '在库'
                              AND (remark IS NULL OR remark NOT LIKE '%由库存登记为资产%')
                            ORDER BY id ASC
                            LIMIT ?
                            """, itemId, warehouseId, count);

                    if (available.size() < count) {
                        throw new NotEnoughStockException(available.size());
                    }

                    List<String> generated = new ArrayList<>();
                    for (Map<String, Object> asset : available) {
                        String no = String.valueOf(asset.get("asset_no"));
                        // 标记为「已登记」：更新 remark，表示这件资产已从散装池划出
                        jdbc.update("""
                                UPDATE wms_asset SET remark = '由库存登记为资产' WHERE id = ?
                                """, asset.get("id"));
                        jdbc.update("""
                                INSERT INTO wms_asset_log(asset_no, action, from_status, to_status, warehouse_id, operator, remark)
                                VALUES(?, '登记', '在库', '在库', ?, ?, '从库存登记为资产')
                                """, no, warehouseId, operator);
                        generated.add(no);
                    }
                    return generated;
                });
            } catch (NotEnoughStockException e) {
                return "登记失败：仓库「" + wh.get("name") + "(" + wh.get("code") + ")」中「" + item.get("name")
                        + "[" + item.get("sku") + "]」当前可登记资产只有 " + e.available + " 件"
                        + "（不足 " + count + " 件）。请先入库补足，或减少登记数量。";
            } catch (DuplicateKeyException e) {
                conflictMsg = rootMessage(e);
            } catch (DataAccessException e) {
                return "登记失败，本次操作已整体回滚（库存与资产均未变更）。原因：" + rootMessage(e) + "。请重试。";
            }
        }

        if (assetNos == null) {
            return "登记失败：资产编号连续冲突，请稍后重试。原因：" + conflictMsg;
        }

        StringBuilder sb = new StringBuilder("登记成功（以下为数据库实际写入的最终值，以此为准）：\n")
                .append("- 商品：").append(item.get("name")).append("[").append(item.get("sku")).append("]\n")
                .append("- 仓库：").append(wh.get("name")).append("(").append(wh.get("code")).append(")\n")
                .append("- 本次登记：").append(count).append(" 件\n")
                .append("- 生成资产编号：").append(String.join("、", assetNos)).append("\n")
                .append("- 经办人：").append(nz(operator, "未填写")).append("\n")
                .append("这些资产已进入「全部物品清单」，当前状态为「在库」，可以调拨给员工使用。")
                .append(GROUND_TRUTH_HINT);
        return sb.toString();
    }

    // ==================== 调拨 ====================

    /**
     * 调拨资产给员工：资产从「在库」变成「已调拨」，并记下使用人。
     *
     * <p>只有「在库」的资产能被调拨；已被别人领用或已销毁的会被逐件挡下并说明原因，
     * 同一批次里合法的那些照常执行（不会因为一件冲突就把整批作废）。
     */
    public String allocate(List<String> assetNos, Long employeeId, String operator) {
        // 使用 EmployeeGuard 统一校验：仅限在职员工可调拨资产
        String guardError = employeeGuard.requireOnDuty(employeeId, "调拨资产");
        if (guardError != null) {
            return guardError;
        }
        // 加载员工姓名用于流水记录
        List<Map<String, Object>> empRows = jdbc.queryForList(
                "SELECT id, name FROM employee WHERE id = ?", employeeId);
        Map<String, Object> emp = empRows.get(0);
        String empName = String.valueOf(emp.get("name"));

        List<String> okList = new ArrayList<>();
        List<String> failList = new ArrayList<>();
        try {
            tx.executeWithoutResult(status -> {
                for (String raw : assetNos) {
                    String no = raw == null ? "" : raw.trim();
                    if (no.isEmpty()) {
                        continue;
                    }
                    // 关键：WHERE status='在库' + 影响行数判断 —— 并发下同一件资产只会被一个人改到
                    int n = jdbc.update("""
                            UPDATE wms_asset
                            SET status = '已调拨', holder_employee_id = ?, allocated_at = NOW()
                            WHERE asset_no = ? AND status = '在库'
                            """, employeeId, no);
                    if (n == 0) {
                        failList.add("`" + no + "`：" + explainCannotAllocate(no));
                        continue;
                    }
                    jdbc.update("""
                            INSERT INTO wms_asset_log(asset_no, action, from_status, to_status, employee_id, employee_name, operator)
                            VALUES(?, '调拨', '在库', '已调拨', ?, ?, ?)
                            """, no, employeeId, empName, operator);
                    okList.add(no);
                }
            });
        } catch (DataAccessException e) {
            return "调拨失败，本次操作已整体回滚（资产状态与流水均未变更）。原因：" + rootMessage(e) + "。请重试。";
        }

        return buildAllocateReply(empName, okList, failList, operator);
    }

    // ==================== 退回 ====================

    /**
     * 退回资产到仓库：资产从「已调拨」变回「在库」，使用人清空。
     * 退回后仍属于该仓库的资产，只是重新可被调拨。
     */
    public String returnAssets(List<String> assetNos, String operator) {
        List<String> okList = new ArrayList<>();
        List<String> failList = new ArrayList<>();
        try {
            tx.executeWithoutResult(status -> {
                for (String raw : assetNos) {
                    String no = raw == null ? "" : raw.trim();
                    if (no.isEmpty()) {
                        continue;
                    }
                    // 先取出原使用人，退回后要写进流水、也要在结果里说清楚"从谁手里收回"
                    List<Map<String, Object>> cur = jdbc.queryForList("""
                            SELECT a.status, a.holder_employee_id, e.name AS holder_name
                            FROM wms_asset a LEFT JOIN employee e ON e.id = a.holder_employee_id
                            WHERE a.asset_no = ?
                            """, no);
                    if (cur.isEmpty()) {
                        failList.add("`" + no + "`：资产不存在");
                        continue;
                    }
                    String curStatus = String.valueOf(cur.get(0).get("status"));
                    if (!ALLOCATED.equals(curStatus)) {
                        failList.add("`" + no + "`：当前状态是「" + curStatus + "」，不在使用人手中，无需退回");
                        continue;
                    }
                    Long holderId = cur.get(0).get("holder_employee_id") == null
                            ? null : ((Number) cur.get(0).get("holder_employee_id")).longValue();
                    String holderName = cur.get(0).get("holder_name") == null
                            ? "未知" : String.valueOf(cur.get(0).get("holder_name"));

                    int n = jdbc.update("""
                            UPDATE wms_asset
                            SET status = '在库', holder_employee_id = NULL, allocated_at = NULL
                            WHERE asset_no = ? AND status = '已调拨'
                            """, no);
                    if (n == 0) {
                        failList.add("`" + no + "`：状态已变化，请重新查询后再试");
                        continue;
                    }
                    jdbc.update("""
                            INSERT INTO wms_asset_log(asset_no, action, from_status, to_status, employee_id, employee_name, operator)
                            VALUES(?, '退回', '已调拨', '在库', ?, ?, ?)
                            """, no, holderId, holderName, operator);
                    okList.add(no + "（从 " + holderName + " 处收回）");
                }
            });
        } catch (DataAccessException e) {
            return "退回失败，本次操作已整体回滚（资产状态与流水均未变更）。原因：" + rootMessage(e) + "。请重试。";
        }

        StringBuilder sb = new StringBuilder();
        if (!okList.isEmpty()) {
            sb.append("已退回仓库（以下为数据库实际写入的最终值，以此为准）：\n");
            for (String s : okList) {
                sb.append("- ").append(s).append(" → 状态「在库」\n");
            }
            sb.append("- 经办人：").append(nz(operator, "未填写")).append("\n")
              .append(GROUND_TRUTH_HINT);
        }
        if (!failList.isEmpty()) {
            sb.append(okList.isEmpty() ? "" : "\n").append("未处理：\n");
            for (String s : failList) {
                sb.append("- ").append(s).append("\n");
            }
        }
        return sb.toString();
    }

    // ==================== 销毁 ====================

    /**
     * 销毁资产：状态改为「已销毁」，从"全部物品清单"里划掉。流水保留，可追溯。
     * 这是唯一能把物品从全部清单里去掉的操作。
     */
    public String scrap(List<String> assetNos, String reason, String operator) {
        List<String> okList = new ArrayList<>();
        List<String> failList = new ArrayList<>();
        try {
            tx.executeWithoutResult(status -> {
                for (String raw : assetNos) {
                    String no = raw == null ? "" : raw.trim();
                    if (no.isEmpty()) {
                        continue;
                    }
                    List<Map<String, Object>> cur = jdbc.queryForList("""
                            SELECT a.status, e.name AS holder_name
                            FROM wms_asset a LEFT JOIN employee e ON e.id = a.holder_employee_id
                            WHERE a.asset_no = ?
                            """, no);
                    if (cur.isEmpty()) {
                        failList.add("`" + no + "`：资产不存在");
                        continue;
                    }
                    String curStatus = String.valueOf(cur.get(0).get("status"));
                    if (SCRAPPED.equals(curStatus)) {
                        failList.add("`" + no + "`：该资产已经是「已销毁」状态，无需重复销毁");
                        continue;
                    }
                    String holder = cur.get(0).get("holder_name") == null
                            ? null : String.valueOf(cur.get(0).get("holder_name"));

                    // 已调拨的资产也能销毁（例如员工把设备用坏了、直接报废），
                    // 此时一并把使用人清空，避免留下"已销毁却还挂着使用人"的脏数据。
                    // 注意 holder_employee_id 必须和 status 在同一条 UPDATE 里改，
                    // 否则中间态会违反表上的 ck_asset_holder 约束。
                    int n = jdbc.update("""
                            UPDATE wms_asset
                            SET status = '已销毁', holder_employee_id = NULL, allocated_at = NULL,
                                scrapped_at = NOW(), scrap_reason = ?
                            WHERE asset_no = ? AND status <> '已销毁'
                            """, reason, no);
                    if (n == 0) {
                        failList.add("`" + no + "`：状态已变化，请重新查询后再试");
                        continue;
                    }
                    jdbc.update("""
                            INSERT INTO wms_asset_log(asset_no, action, from_status, to_status, employee_name, operator, remark)
                            VALUES(?, '销毁', ?, '已销毁', ?, ?, ?)
                            """, no, curStatus, holder, operator, reason);
                    okList.add(no + "（原状态「" + curStatus + "」"
                            + (holder == null ? "" : "，原使用人 " + holder) + "）");
                }
            });
        } catch (DataAccessException e) {
            return "销毁失败，本次操作已整体回滚（资产状态与流水均未变更）。原因：" + rootMessage(e) + "。请重试。";
        }

        StringBuilder sb = new StringBuilder();
        if (!okList.isEmpty()) {
            sb.append("已销毁，该资产已从「全部物品清单」划掉（流水仍保留可追溯）：\n");
            for (String s : okList) {
                sb.append("- ").append(s).append("\n");
            }
            sb.append("- 销毁原因：").append(nz(reason, "未填写")).append("\n")
              .append("- 经办人：").append(nz(operator, "未填写")).append("\n")
              .append(GROUND_TRUTH_HINT);
            sb.append("\n⚠ 若该资产原本挂在某位员工名下，请确认实物已收回；销毁只改账面状态，"
                    + "不能代替线下实际回收。\n");
        }
        if (!failList.isEmpty()) {
            sb.append(okList.isEmpty() ? "" : "\n").append("未处理：\n");
            for (String s : failList) {
                sb.append("- ").append(s).append("\n");
            }
        }
        return sb.toString();
    }

    // ==================== 仓间调拨 ====================

    /**
     * 仓间调拨：把资产从一个仓库转移到另一个仓库，仅允许「在库」状态的资产。
     * 调拨后资产的 warehouse_id 更新为目标仓库，记录流水（from_status 和 to_status 都是「在库」，
     * 但 warehouse_id 从 A 变为 B，在流水里体现为仓库编码变化）。
     *
     * @param assetNos 资产编号列表
     * @param fromWarehouseId 源仓库 ID
     * @param toWarehouseId 目标仓库 ID
     * @param operator 经办人
     * @return 操作结果说明
     */
    public String transfer(List<String> assetNos, Long fromWarehouseId, Long toWarehouseId, String operator) {
        Map<String, Object> fromWh = loadWarehouse(fromWarehouseId);
        if (fromWh == null) {
            return "调拨失败：未找到源仓库 [ID=" + fromWarehouseId + "]。";
        }
        Map<String, Object> toWh = loadWarehouse(toWarehouseId);
        if (toWh == null) {
            return "调拨失败：未找到目标仓库 [ID=" + toWarehouseId + "]。";
        }
        if (fromWarehouseId.equals(toWarehouseId)) {
            return "调拨失败：源仓库与目标仓库相同（「" + fromWh.get("name") + "」），无需调拨。";
        }

        List<String> okList = new ArrayList<>();
        List<String> failList = new ArrayList<>();
        try {
            tx.executeWithoutResult(status -> {
                for (String raw : assetNos) {
                    String no = raw == null ? "" : raw.trim();
                    if (no.isEmpty()) {
                        continue;
                    }
                    // 先查当前资产状态和所在仓库
                    List<Map<String, Object>> cur = jdbc.queryForList("""
                            SELECT a.id, a.status, a.warehouse_id, w.name AS wh_name, w.code AS wh_code
                            FROM wms_asset a JOIN wms_warehouse w ON w.id = a.warehouse_id
                            WHERE a.asset_no = ?
                            """, no);
                    if (cur.isEmpty()) {
                        failList.add("`" + no + "`：资产不存在");
                        continue;
                    }
                    Map<String, Object> asset = cur.get(0);
                    String curStatus = String.valueOf(asset.get("status"));
                    Long curWhId = ((Number) asset.get("warehouse_id")).longValue();

                    if (!IN_STOCK.equals(curStatus)) {
                        failList.add("`" + no + "`：当前状态是「" + curStatus + "」，只有「在库」的资产才能仓间调拨");
                        continue;
                    }
                    if (!curWhId.equals(fromWarehouseId)) {
                        String curWhName = String.valueOf(asset.get("wh_name")) + "(" + asset.get("wh_code") + ")";
                        failList.add("`" + no + "`：资产实际在「" + curWhName + "」，不在源仓库「"
                                + fromWh.get("name") + "(" + fromWh.get("code") + ")」");
                        continue;
                    }

                    // 执行调拨：更新仓库 ID
                    int n = jdbc.update("""
                            UPDATE wms_asset SET warehouse_id = ?
                            WHERE asset_no = ? AND status = '在库' AND warehouse_id = ?
                            """, toWarehouseId, no, fromWarehouseId);
                    if (n == 0) {
                        failList.add("`" + no + "`：状态已变化，请重新查询后再试");
                        continue;
                    }

                    // 记录流水：action=「仓间调拨」，from_status=「在库」，to_status=「在库」，
                    // 但 remark 里写明从哪个仓库到哪个仓库
                    jdbc.update("""
                            INSERT INTO wms_asset_log(asset_no, action, from_status, to_status, warehouse_id, operator, remark)
                            VALUES(?, '仓间调拨', '在库', '在库', ?, ?, ?)
                            """, no, toWarehouseId, operator,
                            "从「" + fromWh.get("name") + "(" + fromWh.get("code") + ")」调拨至「"
                                    + toWh.get("name") + "(" + toWh.get("code") + ")」");
                    okList.add(no);
                }
            });
        } catch (DataAccessException e) {
            return "仓间调拨失败，本次操作已整体回滚。原因：" + rootMessage(e) + "。请重试。";
        }

        StringBuilder sb = new StringBuilder();
        if (!okList.isEmpty()) {
            sb.append("仓间调拨成功（以下为数据库实际写入的最终值，以此为准）：\n");
            for (String no : okList) {
                sb.append("- ").append(no).append("：「").append(fromWh.get("name")).append("(").append(fromWh.get("code"))
                  .append(")」 → 「").append(toWh.get("name")).append("(").append(toWh.get("code")).append(")」\n");
            }
            sb.append("- 共 ").append(okList.size()).append(" 件\n")
              .append("- 经办人：").append(nz(operator, "未填写")).append("\n")
              .append(GROUND_TRUTH_HINT);
        }
        if (!failList.isEmpty()) {
            sb.append(okList.isEmpty() ? "" : "\n").append("未调拨：\n");
            for (String s : failList) {
                sb.append("- ").append(s).append("\n");
            }
        }
        if (okList.isEmpty() && failList.isEmpty()) {
            return "没有收到有效资产编号，请先说明要调拨哪几件（可用 list_assets 查询资产编号）。";
        }
        return sb.toString();
    }

    // ==================== 离职联动：批量退回 ====================

    /**
     * 把某位员工名下的全部资产退回仓库（离职时自动调用）。
     *
     * @return 有资产时返回退回明细说明；该员工名下没有资产时返回 null（调用方据此决定要不要提这件事）
     */
    public String returnAllOfEmployee(Long employeeId, String employeeName, String operator) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.asset_no, i.name AS item_name, i.sku
                FROM wms_asset a
                JOIN wms_item i ON i.id = a.item_id
                WHERE a.holder_employee_id = ? AND a.status = '已调拨'
                ORDER BY a.asset_no
                """, employeeId);
        if (rows.isEmpty()) {
            return null;
        }
        List<String> nos = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            nos.add(String.valueOf(r.get("asset_no")));
        }
        String detail = returnAssets(nos, operator);
        StringBuilder sb = new StringBuilder("离职时自动回收其名下物品（共 " + rows.size() + " 件）：\n");
        for (Map<String, Object> r : rows) {
            sb.append("- ").append(r.get("asset_no")).append(" ").append(r.get("item_name"))
              .append("[").append(r.get("sku")).append("]\n");
        }
        sb.append(detail);
        return sb.toString();
    }

    // ==================== 审批候选清单（确认框里逐件勾选）====================

    /**
     * 哪些工具的参数是"一批资产编号"，以及勾选结果要写回哪个字段。
     * 只有列在这里的工具才会在确认框里出现"逐件勾选"，其余工具照旧只显示参数框。
     */
    private static final Map<String, String> OPTION_FIELDS = Map.of(
            "allocate_asset", "assetNos",
            "return_asset", "assetNos",
            "scrap_asset", "assetNos");

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 为确认框生成候选资产清单。
     *
     * <p>候选范围按工具区分：调拨只能挑「在库」的、退回只能挑「已调拨」的、销毁能挑任何「未销毁」的。
     * 若模型已指定资产编号，就把候选收敛到**同一个商品** —— 否则"调拨无线鼠标"会把仓库里所有其它 SKU
     * 一并列出来，反而更难选；模型给的编号一个都对不上时才退化为全量候选。
     */
    @Override
    public Options forTool(String toolName, String argumentsJson) {
        String field = OPTION_FIELDS.get(toolName);
        if (field == null) {
            return Options.NONE;
        }
        String scope = switch (toolName) {
            case "allocate_asset" -> IN_STOCK;
            case "return_asset" -> ALLOCATED;
            default -> null;   // 销毁：只要没销毁过都可以
        };

        Set<String> chosen = new LinkedHashSet<>(splitField(argumentsJson, field));
        Set<Long> itemIds = new LinkedHashSet<>();
        if (!chosen.isEmpty()) {
            String holders = String.join(",", Collections.nCopies(chosen.size(), "?"));
            itemIds.addAll(jdbc.queryForList(
                    "SELECT DISTINCT item_id FROM wms_asset WHERE asset_no IN (" + holders + ")",
                    Long.class, chosen.toArray()));
        }

        StringBuilder sql = new StringBuilder("""
                SELECT a.asset_no, a.status, a.created_at,
                       i.name AS item_name, i.sku, i.spec,
                       w.name AS wh_name, w.code AS wh_code, e.name AS holder_name
                FROM wms_asset a
                JOIN wms_item i ON i.id = a.item_id
                JOIN wms_warehouse w ON w.id = a.warehouse_id
                LEFT JOIN employee e ON e.id = a.holder_employee_id
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (scope == null) {
            sql.append(" AND a.status <> '已销毁'");
        } else {
            sql.append(" AND a.status = ?");
            params.add(scope);
        }
        if (!itemIds.isEmpty()) {
            sql.append(" AND a.item_id IN (")
               .append(String.join(",", Collections.nCopies(itemIds.size(), "?")))
               .append(")");
            params.addAll(itemIds);
        }
        sql.append(" ORDER BY a.asset_no LIMIT ?");
        params.add(Args.MAX_LIST);

        List<Map<String, Object>> rows = jdbc.queryForList(sql.toString(), params.toArray());
        if (rows.isEmpty()) {
            return Options.NONE;
        }

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            String no = String.valueOf(r.get("asset_no"));
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("value", no);
            o.put("label", optionLabel(no, r));
            o.put("checked", chosen.contains(no));
            items.add(o);
        }

        String employee = employeeName(argumentsJson);
        String hint = switch (toolName) {
            case "allocate_asset" -> "仓库中可调拨的同类物品共 " + rows.size() + " 件，请勾选要调拨"
                    + (employee == null ? "的物品" : "给「" + employee + "」的物品") + "：";
            case "return_asset" -> "当前在员工手上、可退回的同类物品共 " + rows.size() + " 件，请勾选要退回的：";
            default -> "可销毁的同类物品共 " + rows.size() + " 件，请勾选要销毁的：";
        };
        return new Options(field, hint, items);
    }

    /** 从工具参数 JSON 取一个字符串字段并按分隔符拆开（参数由模型给出，可能缺字段或类型不对） */
    private static List<String> splitField(String argumentsJson, String field) {
        List<String> out = new ArrayList<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return out;
        }
        try {
            JsonNode node = JSON.readTree(argumentsJson).get(field);
            if (node == null || node.isNull()) {
                return out;
            }
            for (String part : node.asText().split("[,，、;；\\s]+")) {
                String v = part.trim();
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
        } catch (Exception ignored) {
            // 参数不是合法 JSON 时不给候选，让用户照旧手工改参数
        }
        return out;
    }

    /** 参数里的 employee 可能是ID 也可能是姓名，尽量换成姓名用于提示文案 */
    private String employeeName(String argumentsJson) {
        List<String> raw = splitField(argumentsJson, "employee");
        if (raw.isEmpty()) {
            return null;
        }
        String v = raw.get(0);
        if (v.matches("\\d+")) {
            List<String> names = jdbc.queryForList(
                    "SELECT name FROM employee WHERE id = ?", String.class, Long.parseLong(v));
            if (!names.isEmpty()) {
                return names.get(0);
            }
        }
        return v;
    }

    /** 候选条目的展示文案：要让人一眼看出"这件"是哪件、在哪个仓库、还是在谁手上 */
    private static String optionLabel(String assetNo, Map<String, Object> r) {
        StringBuilder sb = new StringBuilder(assetNo).append(" ").append(r.get("item_name"));
        Object spec = r.get("spec");
        if (spec != null && !String.valueOf(spec).isBlank()) {
            sb.append("（").append(spec).append("）");
        }
        if (r.get("sku") != null) {
            sb.append("[").append(r.get("sku")).append("]");
        }
        sb.append(" | ").append(r.get("wh_name")).append("(").append(r.get("wh_code")).append(")");
        Object holder = r.get("holder_name");
        if (holder != null && !String.valueOf(holder).isBlank()) {
            sb.append(" | 使用人 ").append(holder);
        } else {
            sb.append(" | 登记 ").append(shortTime(r.get("created_at")));
        }
        return sb.toString();
    }

    private static String shortTime(Object value) {
        if (value == null) {
            return "-";
        }
        String s = String.valueOf(value);
        return s.length() >= 10 ? s.substring(0, 10) : s;
    }

    // ==================== 查询（供只读工具使用）====================

    /**
     * 查询某位员工当前领用中的全部物品（个人物品栏）。
     * 供 HR 模块调用，避免 HR 直接写 SQL 查 wms_* 表。
     *
     * @param employeeId 员工ID
     * @return 领用中的资产列表（含资产编号、物品名称、SKU、单位、领用时间、仓库信息）
     */
    public List<Map<String, Object>> queryByEmployee(Long employeeId) {
        return queryAssets("已调拨", null, null, employeeId);
    }

    /**
     * 查询资产清单。
     *
     * @param scope         全部（不含已出库与已销毁）/ 在库 / 已调拨 / 已出库 / 已销毁；空视为「全部」
     * @param keyword       商品名称、SKU 或资产编号关键字，可为空
     * @param warehouseCode 仓库编码，可为空
     * @param employeeId    只看某位员工名下的资产，可为空
     */
    public List<Map<String, Object>> queryAssets(String scope, String keyword, String warehouseCode, Long employeeId) {
        StringBuilder sql = new StringBuilder("""
                SELECT a.asset_no, a.status, a.allocated_at, a.scrapped_at, a.scrap_reason, a.created_at, a.remark,
                       i.sku, i.name AS item_name, i.unit, i.category,
                       w.code AS wh_code, w.name AS wh_name,
                       e.id AS holder_id, e.name AS holder_name, e.status AS holder_status,
                       p.title AS holder_position, p.department
                FROM wms_asset a
                JOIN wms_item i ON i.id = a.item_id
                JOIN wms_warehouse w ON w.id = a.warehouse_id
                LEFT JOIN employee e ON e.id = a.holder_employee_id
                LEFT JOIN `position` p ON p.id = e.position_id
                WHERE 1 = 1
                """);
        List<Object> args = new ArrayList<>();

        String s = scope == null ? "" : scope.trim();
        if (IN_STOCK.equals(s)) {
            sql.append(" AND a.status = '在库'");
        } else if (ALLOCATED.equals(s)) {
            sql.append(" AND a.status = '已调拨'");
        } else if (OUTBOUND.equals(s)) {
            sql.append(" AND a.status = '已出库'");
        } else if (SCRAPPED.equals(s)) {
            sql.append(" AND a.status = '已销毁'");
        } else {
            // 「全部物品清单」的口径：含在库与已调拨；已出库已离开公司、已销毁已划掉，均不计入
            sql.append(" AND a.status NOT IN ('已出库', '已销毁')");
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (i.name LIKE ? OR i.sku LIKE ? OR a.asset_no = ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
            args.add(keyword.trim());
        }
        if (warehouseCode != null && !warehouseCode.isBlank()) {
            sql.append(" AND w.code = ?");
            args.add(warehouseCode.trim());
        }
        if (employeeId != null) {
            sql.append(" AND a.holder_employee_id = ?");
            args.add(employeeId);
        }
        sql.append(" ORDER BY a.asset_no LIMIT ?");
        args.add(Args.MAX_LIST);
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    /** 按状态统计资产件数（盘点汇总用） */
    public Map<String, Integer> countByStatus() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT status, COUNT(*) AS c FROM wms_asset GROUP BY status")) {
            out.put(String.valueOf(r.get("status")), ((Number) r.get("c")).intValue());
        }
        return out;
    }

    /** 当前持有资产的员工及其件数（盘点时"物品在谁手上"的总览） */
    public List<Map<String, Object>> holdersSummary() {
        return jdbc.queryForList("""
                SELECT e.id AS employee_id, e.name AS employee_name, e.status AS employee_status,
                       p.title AS position, p.department, COUNT(*) AS asset_count
                FROM wms_asset a
                JOIN employee e ON e.id = a.holder_employee_id
                LEFT JOIN `position` p ON p.id = e.position_id
                WHERE a.status = '已调拨'
                GROUP BY e.id, e.name, e.status, p.title, p.department
                ORDER BY asset_count DESC, e.id
                """);
    }

    // ==================== 内部方法 ====================

    /** 事务内发现库存不足时抛出的信号异常：抛出即回滚，由调用方转成可读提示 */
    private static final class NotEnoughStockException extends RuntimeException {
        final int available;

        NotEnoughStockException(int available) {
            super("not enough stock: " + available);
            this.available = available;
        }
    }

    /** 解释某件资产为什么不能被调拨（给模型一句能直接转达给用户的话） */
    private String explainCannotAllocate(String assetNo) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.status, e.name AS holder_name
                FROM wms_asset a LEFT JOIN employee e ON e.id = a.holder_employee_id
                WHERE a.asset_no = ?
                """, assetNo);
        if (rows.isEmpty()) {
            return "资产编号不存在，请用 list_assets 核对";
        }
        String status = String.valueOf(rows.get(0).get("status"));
        if (ALLOCATED.equals(status)) {
            Object holder = rows.get(0).get("holder_name");
            return "该资产已调拨给 " + (holder == null ? "某位员工" : holder)
                    + "，同一件物品不能同时给两个人，请先让原使用人退回";
        }
        if (SCRAPPED.equals(status)) {
            return "该资产已销毁，不能再调拨";
        }
        return "当前状态为「" + status + "」，无法调拨";
    }

    private String buildAllocateReply(String empName, List<String> okList, List<String> failList, String operator) {
        StringBuilder sb = new StringBuilder();
        if (!okList.isEmpty()) {
            sb.append("已调拨给「").append(empName).append("」（以下为数据库实际写入的最终值，以此为准）：\n");
            for (String s : okList) {
                sb.append("- ").append(s).append(" → 状态「已调拨」，使用人 ").append(empName).append("\n");
            }
            sb.append("- 共 ").append(okList.size()).append(" 件\n")
              .append("- 经办人：").append(nz(operator, "未填写")).append("\n")
              .append(GROUND_TRUTH_HINT);
        }
        if (!failList.isEmpty()) {
            sb.append(okList.isEmpty() ? "" : "\n").append("未调拨：\n");
            for (String s : failList) {
                sb.append("- ").append(s).append("\n");
            }
        }
        if (okList.isEmpty() && failList.isEmpty()) {
            return "没有收到有效资产编号，请先说明要调拨哪几件（可用 list_assets 查询资产编号）。";
        }
        return sb.toString();
    }

    private Map<String, Object> loadItem(Long itemId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, sku, name, unit, category FROM wms_item WHERE id = ?", itemId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> loadWarehouse(Long warehouseId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, code, name FROM wms_warehouse WHERE id = ?", warehouseId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 把底层异常里最有信息量的一句取出来（JdbcTemplate 会包好几层） */
    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        return (msg == null || msg.isBlank()) ? t.getClass().getSimpleName() : msg;
    }

    private static String nz(Object v, String fallback) {
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
    }
}
