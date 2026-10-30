package com.hr.agent.tools;

import com.hr.agent.event.EmployeeResignedEvent;

import com.hr.agent.service.AssetService;

import com.hr.agent.service.EmployeeGuard;

import com.hr.agent.util.Args;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 人力模块工具集（对应结构：tools/hr_tools.py）。
 * 供人力资源 Agent 通过 Function Calling 调用，所有方法直接操作本地 MySQL。
 *
 * 【写工具约定】所有会改动数据的写操作，返回值都必须回显"数据库中实际写入的最终值"，并附上 GROUND_TRUTH_HINT。
 * 原因：人工确认（HITL）时用户可以在前端确认框里改参数，框架会把改后的参数替换进 tool call 执行，
 * 但**不会**把这个改动告知模型。若返回文本只写"已办理入职"而不回显字段，模型会误以为自己写的参数已生效，
 * 甚至再调一次写工具把值"改回"用户原话里的旧值（实测发生过，属于真实数据风险）。
 */
@Component
public class HrTools {

    /** 附在写工具返回值末尾：告知模型人工可能改过参数，且以返回值为准 */
    // NOTE: GROUND_TRUTH_HINT 在 HrTools/OrderTools/WmsTools/AssetService 四处有意重复（内容必须保持一致），
    // 任一修改需同步其余三处；确有变更时再抽取到 util 包统一。
    private static final String GROUND_TRUTH_HINT =
            "\n注：以上为数据库实际写入的最终值。若与你的调用参数或用户原话不一致，说明该值已被人工在确认框中修改，"
                    + "请直接按本返回值汇报，不要再调用写工具把值改回去。";

    private final JdbcTemplate jdbc;
    /** 员工名下资产（个人物品栏、离职回收）：与仓库模块共用同一份资产逻辑，避免两套规则走样 */
    private final AssetService assets;
    /** 员工状态守卫：集中管理离职员工能否进行某项操作的校验规则 */
    private final EmployeeGuard employeeGuard;
    /** 事件发布器：离职后发事件触发资产回收（异步、独立事务） */
    private final ApplicationEventPublisher eventPublisher;

    public HrTools(JdbcTemplate jdbc, AssetService assets, EmployeeGuard employeeGuard,
                   ApplicationEventPublisher eventPublisher) {
        this.jdbc = jdbc;
        this.assets = assets;
        this.employeeGuard = employeeGuard;
        this.eventPublisher = eventPublisher;
    }

    private static String nz(Object v, String fallback) {
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
    }

    /**
     * 插入并返回数据库真实生成的主键。
     * 不要用 {@code SELECT MAX(id)} 取新 ID —— 并发插入时会取到别人的 ID，
     * 从而把"刚写入的这条记录"回显成另一条（在回显最终值的约定下会造成误报）。
     */
    private Long insertReturningId(String sql, Object... args) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            return ps;
        }, kh);
        Number key = kh.getKey();
        return key == null ? null : key.longValue();
    }

    /** 列表类工具的统一截断提示 */
    private static String moreHint(int shown, int total, String narrowHint) {
        return total > shown ? "\n（共 " + total + " 条，此处只显示前 " + shown + " 条，可" + narrowHint + "缩小范围）\n" : "";
    }

    /** 把 1 个额外参数接到参数数组末尾（用于在已有参数后追加 LIMIT ?） */
    private static Object[] concat(Object[] args, Object extra) {
        Object[] out = java.util.Arrays.copyOf(args, args.length + 1);
        out[args.length] = extra;
        return out;
    }

    // ==================== 职位相关 ====================

    @Tool(name = "add_position", description = "新增一个职位，需要提供职位名称、所属部门、薪资范围（可选）和职位描述（可选）")
    public String addPosition(
            @ToolParam(description = "职位名称，如：Java开发工程师") String title,
            @ToolParam(description = "所属部门，如：技术部") String department,
            @ToolParam(description = "薪资范围，如：15k-25k，可为空") String salaryRange,
            @ToolParam(description = "职位描述，可为空") String description) {
        String bad = Args.name(title, "职位名称");
        if (bad == null) {
            bad = Args.name(department, "所属部门");
        }
        if (bad == null) {
            bad = Args.text(salaryRange, "薪资范围", 50, true);
            if (bad != null) {
                bad = "无法新增职位：" + bad;
            }
        }
        if (bad != null) {
            return bad;
        }
        title = title.trim();
        department = department.trim();
        salaryRange = Args.clean(salaryRange);
        description = Args.clean(description);

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM `position` WHERE title = ? AND department = ?",
                Integer.class, title, department);
        if (count != null && count > 0) {
            Map<String, Object> exist = jdbc.queryForMap(
                    "SELECT id, title, department, salary_range, description FROM `position` "
                            + "WHERE title = ? AND department = ? ORDER BY id LIMIT 1", title, department);
            return "职位「" + title + "」在 " + department + " 已存在（ID=" + exist.get("id") + "），未重复添加。"
                    + "如需调整该职位的薪资或描述，请告知具体要改的内容。";
        }
        Long newId = insertReturningId(
                "INSERT INTO `position`(title, department, salary_range, description) VALUES(?,?,?,?)",
                title, department, salaryRange, description);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, title, department, salary_range, description FROM `position` WHERE id = ?", newId);
        return "已新增职位（以下为数据库实际写入的最终值，以此为准）：\n"
                + "- 职位ID：" + row.get("id") + "\n"
                + "- 职位名称：" + row.get("title") + "\n"
                + "- 部门：" + row.get("department") + "\n"
                + "- 薪资范围：" + nz(row.get("salary_range"), "未填写") + "\n"
                + "- 职位描述：" + nz(row.get("description"), "未填写")
                + GROUND_TRUTH_HINT;
    }

    @Tool(name = "list_positions", description = "查询公司当前所有职位列表")
    public String listPositions() {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM `position`", Integer.class);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, title, department, salary_range, description FROM `position` ORDER BY id LIMIT ?",
                Args.MAX_LIST);
        if (rows.isEmpty()) {
            return "目前公司还没有任何职位记录。";
        }
        StringBuilder sb = new StringBuilder("公司职位列表（共 " + (total == null ? rows.size() : total) + " 个）：\n");
        for (Map<String, Object> r : rows) {
            sb.append("- [ID=").append(r.get("id")).append("] ")
              .append(r.get("title")).append(" | 部门：").append(r.get("department"))
              .append(" | 薪资：").append(r.get("salary_range") == null ? "未填写" : r.get("salary_range"))
              .append(r.get("description") == null ? "" : " | 描述：" + r.get("description"))
              .append("\n");
        }
        return sb.append(moreHint(rows.size(), total == null ? rows.size() : total, "按部门或名称筛选")).toString();
    }

    @Tool(name = "delete_position", description = "删除一个职位（按职位ID）。若该职位下仍挂着员工（在职或离职），则删除失败并说明原因")
    public String deletePosition(@ToolParam(description = "职位ID") Long positionId) {
        if (positionId == null) {
            return "请提供要删除的职位ID（可用 list_positions 查询）。";
        }
        List<Map<String, Object>> found = jdbc.queryForList(
                "SELECT title, department FROM `position` WHERE id = ?", positionId);
        if (found.isEmpty()) {
            return "未找到职位 [ID=" + positionId + "]，请用 list_positions 确认职位ID。";
        }
        String title = String.valueOf(found.get(0).get("title"));
        String department = String.valueOf(found.get(0).get("department"));

        // 外键未设置级联，只要还有**任何**员工引用该职位（含离职员工），DELETE 都会被数据库拒绝。
        // 因此这里必须统计全部引用者，而不能只统计在职员工 —— 否则会抛出原始 SQL 异常给用户。
        Integer onDuty = jdbc.queryForObject(
                "SELECT COUNT(*) FROM employee WHERE position_id = ? AND status = '在职'",
                Integer.class, positionId);
        Integer offDuty = jdbc.queryForObject(
                "SELECT COUNT(*) FROM employee WHERE position_id = ? AND status <> '在职'",
                Integer.class, positionId);
        int onDutyN = onDuty == null ? 0 : onDuty;
        int offDutyN = offDuty == null ? 0 : offDuty;

        if (onDutyN > 0) {
            return "删除失败：职位「" + title + "」[ID=" + positionId + "] 下仍有 " + onDutyN
                    + " 名在职员工，请先为他们办理调岗或离职，再来删除该职位。";
        }
        if (offDutyN > 0) {
            return "删除失败：职位「" + title + "」[ID=" + positionId + "] 下还挂着 " + offDutyN
                    + " 名**已离职**员工的历史记录。为保留历史数据，系统不允许直接删除该职位。"
                    + "可以改为保留该职位，或先把这些历史员工记录调整到其它职位再删除。";
        }

        try {
            int n = jdbc.update("DELETE FROM `position` WHERE id = ?", positionId);
            return n > 0
                    ? "已删除职位：「" + title + "」（部门：" + department + "，ID=" + positionId
                            + "），该职位数据已不可恢复" + GROUND_TRUTH_HINT
                    : "未找到职位 [ID=" + positionId + "]。";
        } catch (org.springframework.dao.DataAccessException e) {
            // 兜底：任何未预料到的引用关系都不应把原始 SQL 错误抛给用户
            return "删除失败：该职位仍被其它数据引用，暂时无法删除。请改用 list_employees 确认是否还有员工挂在此职位下。";
        }
    }

    // ==================== 员工相关 ====================

    @Tool(name = "add_employee", description = "新增（入职）一名员工，需要姓名、职位名称，性别/手机号/邮箱可选。系统自动记录入职日期为今天")
    public String addEmployee(
            @ToolParam(description = "员工姓名") String name,
            @ToolParam(description = "职位名称（必须是公司已有的职位，模糊匹配）") String positionTitle,
            @ToolParam(description = "性别：男/女，可为空") String gender,
            @ToolParam(description = "手机号，可为空") String phone,
            @ToolParam(description = "邮箱，可为空") String email) {
        String bad = Args.name(name, "员工姓名");
        if (bad == null) {
            bad = Args.required(positionTitle, "职位名称");
        }
        if (bad == null) {
            bad = Args.gender(gender);
        }
        if (bad == null) {
            bad = Args.phone(phone);
        }
        if (bad == null) {
            bad = Args.email(email);
        }
        if (bad != null) {
            return "无法办理入职：" + bad;
        }
        name = name.trim();
        positionTitle = positionTitle.trim();
        gender = Args.clean(gender);
        phone = Args.clean(phone);
        email = Args.clean(email);

        List<Map<String, Object>> positions = jdbc.queryForList(
                "SELECT id, title, department FROM `position` WHERE title LIKE ? ORDER BY id LIMIT 5",
                "%" + positionTitle + "%");
        if (positions.isEmpty()) {
            return "没有找到名称包含「" + positionTitle + "」的职位，请先用 add_position 创建职位，或确认职位名称。";
        }
        if (positions.size() > 1) {
            StringBuilder sb = new StringBuilder("匹配到多个职位，请用户确认后再操作：\n");
            for (Map<String, Object> p : positions) {
                sb.append("- [ID=").append(p.get("id")).append("] ").append(p.get("title"))
                  .append("（部门：").append(p.get("department")).append("）\n");
            }
            return sb.toString();
        }
        Long positionId = ((Number) positions.get(0).get("id")).longValue();
        Long newId = insertReturningId(
                "INSERT INTO employee(name, gender, phone, email, position_id, hire_date, status) VALUES(?,?,?,?,?,?,?)",
                name, gender, phone, email, positionId, LocalDate.now().toString(), "在职");
        if (newId == null) {
            return "入职写入失败：未能获取新员工ID，请重试。";
        }
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT e.id, e.name, e.gender, e.phone, e.email, e.hire_date, e.status, "
                        + "p.title AS position, p.department "
                        + "FROM employee e LEFT JOIN `position` p ON e.position_id = p.id WHERE e.id = ?", newId);

        // 同名在职员工提示（不拦截，只提醒，避免把同名的人误判成重复录入）
        Integer sameName = jdbc.queryForObject(
                "SELECT COUNT(*) FROM employee WHERE name = ? AND status = '在职' AND id <> ?",
                Integer.class, name, newId);
        String sameNameHint = (sameName != null && sameName > 0)
                ? "\n注：系统中已存在 " + sameName + " 名同名在职员工，如需核对请用 search_employee 查询确认是否重复录入。"
                : "";

        return "已为「" + row.get("name") + "」办理入职（以下为数据库实际写入的最终值，以此为准）：\n"
                + "- 员工ID：" + row.get("id") + "\n"
                + "- 职位：" + nz(row.get("position"), "未分配")
                + "（部门：" + nz(row.get("department"), "-") + "）\n"
                + "- 性别：" + nz(row.get("gender"), "未填写") + "\n"
                + "- 手机号：" + nz(row.get("phone"), "未填写") + "\n"
                + "- 邮箱：" + nz(row.get("email"), "未填写") + "\n"
                + "- 入职日期：" + row.get("hire_date") + "\n"
                + "- 状态：" + row.get("status")
                + sameNameHint
                + GROUND_TRUTH_HINT;
    }

    @Tool(name = "list_employees", description = "查询员工列表，可按状态过滤（在职/离职/全部），不传状态则默认查在职员工")
    public String listEmployees(@ToolParam(description = "状态过滤：在职/离职/全部") String status) {
        String sql = "SELECT e.id, e.name, e.gender, e.phone, e.email, p.title AS position, p.department, e.hire_date, e.status " +
                "FROM employee e LEFT JOIN `position` p ON e.position_id = p.id ";
        String where;
        Object[] args;
        String state = Args.clean(status);
        if (state == null || "在职".equals(state)) {
            where = "WHERE e.status = '在职' ";
            args = new Object[0];
        } else if ("全部".equals(state)) {
            where = "";
            args = new Object[0];
        } else if ("离职".equals(state)) {
            where = "WHERE e.status = ? ";
            args = new Object[]{"离职"};
        } else {
            return "状态过滤只支持「在职」「离职」「全部」（收到「" + state + "」）。请重新指定，或直接不传该参数查在职员工。";
        }
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM employee e " + where, Integer.class, args);
        List<Map<String, Object>> rows = jdbc.queryForList(
                sql + where + "ORDER BY e.id LIMIT ?", concat(args, Args.MAX_LIST));
        if (rows.isEmpty()) {
            return "没有查询到符合条件的员工。";
        }
        int totalN = total == null ? rows.size() : total;
        StringBuilder sb = new StringBuilder("员工列表（共 " + totalN + " 人）：\n");
        for (Map<String, Object> r : rows) {
            sb.append("- [ID=").append(r.get("id")).append("] ").append(r.get("name"))
              .append(" | 职位：").append(r.get("position") == null ? "未分配" : r.get("position"))
              .append(" | 部门：").append(r.get("department") == null ? "-" : r.get("department"))
              .append(" | 状态：").append(r.get("status"))
              .append(" | 入职：").append(r.get("hire_date"))
              .append("\n");
        }
        return sb.append(moreHint(rows.size(), totalN, "用 search_employee 按姓名/手机号")).toString();
    }

    @Tool(name = "search_employee", description = "按姓名或手机号关键字模糊搜索员工（含离职员工）")
    public String searchEmployee(@ToolParam(description = "姓名或手机号关键字") String keyword) {
        String bad = Args.required(keyword, "搜索关键字");
        if (bad != null) {
            return bad + "（可先用 list_employees 查看在职员工列表）";
        }
        keyword = keyword.trim();
        String base = "FROM employee e LEFT JOIN `position` p ON e.position_id = p.id "
                + "WHERE e.name LIKE ? OR e.phone LIKE ? ";
        Integer total = jdbc.queryForObject("SELECT COUNT(*) " + base, Integer.class,
                "%" + keyword + "%", "%" + keyword + "%");
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT e.id, e.name, e.gender, e.phone, e.email, p.title AS position, p.department, e.hire_date, e.status "
                        + base + "ORDER BY e.id LIMIT ?",
                "%" + keyword + "%", "%" + keyword + "%", Args.MAX_LIST);
        if (rows.isEmpty()) {
            return "没有找到与「" + keyword + "」匹配的员工。可换用姓名片段或手机号后几位再试。";
        }
        int totalN = total == null ? rows.size() : total;
        StringBuilder sb = new StringBuilder("搜索结果（共 " + totalN + " 人）：\n");
        for (Map<String, Object> r : rows) {
            sb.append("- [ID=").append(r.get("id")).append("] ").append(r.get("name"))
              .append(" | 职位：").append(r.get("position") == null ? "未分配" : r.get("position"))
              .append(" | 部门：").append(r.get("department") == null ? "-" : r.get("department"))
              .append(" | 手机：").append(r.get("phone") == null ? "-" : r.get("phone"))
              .append(" | 邮箱：").append(r.get("email") == null ? "-" : r.get("email"))
              .append(" | 状态：").append(r.get("status"))
              .append("\n");
        }
        return sb.append(moreHint(rows.size(), totalN, "用更精确的关键字")).toString();
    }

    @Tool(name = "transfer_position", description = "给员工调岗：将员工（按员工ID）的职位变更为新职位（按新职位名称，模糊匹配）")
    public String transferPosition(
            @ToolParam(description = "员工ID") Long employeeId,
            @ToolParam(description = "新职位名称（模糊匹配）") String newPositionTitle) {
        if (employeeId == null) {
            return "请提供要调岗的员工ID（可用 search_employee 查询）。";
        }
        String bad = Args.required(newPositionTitle, "新职位名称");
        if (bad != null) {
            return bad;
        }
        newPositionTitle = newPositionTitle.trim();
        // 使用 EmployeeGuard 统一校验：仅限在职员工可调岗
        String guardError = employeeGuard.requireOnDuty(employeeId, "调岗");
        if (guardError != null) {
            return guardError;
        }
        // 加载员工姓名用于后续提示
        List<Map<String, Object>> employees = jdbc.queryForList(
                "SELECT id, name FROM employee WHERE id = ?", employeeId);
        String empName = String.valueOf(employees.get(0).get("name"));
        List<Map<String, Object>> positions = jdbc.queryForList(
                "SELECT id, title FROM `position` WHERE title LIKE ? ORDER BY id LIMIT 5",
                "%" + newPositionTitle + "%");
        if (positions.isEmpty()) {
            return "没有找到名称包含「" + newPositionTitle + "」的职位。";
        }
        if (positions.size() > 1) {
            StringBuilder sb = new StringBuilder("匹配到多个职位，请用户确认后再操作：\n");
            for (Map<String, Object> p : positions) {
                sb.append("- [ID=").append(p.get("id")).append("] ").append(p.get("title")).append("\n");
            }
            return sb.toString();
        }
        Long targetPositionId = ((Number) positions.get(0).get("id")).longValue();
        // 已是该职位时不做无意义的写操作：否则会白弹一次确认框，用户批准后数据也没变化
        Long currentPositionId = jdbc.queryForObject(
                "SELECT position_id FROM employee WHERE id = ?", Long.class, employeeId);
        if (currentPositionId != null && currentPositionId.equals(targetPositionId)) {
            return "员工「" + empName + "」[ID=" + employeeId + "] 当前已经是职位「"
                    + positions.get(0).get("title") + "」，无需调岗（数据未做任何改动）。";
        }
        jdbc.update("UPDATE employee SET position_id = ? WHERE id = ?", targetPositionId, employeeId);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT e.id, e.name, p.title AS position, p.department "
                        + "FROM employee e LEFT JOIN `position` p ON e.position_id = p.id WHERE e.id = ?", employeeId);
        return "已完成调岗（以下为数据库实际写入的最终值，以此为准）：\n"
                + "- 员工ID：" + row.get("id") + "\n"
                + "- 姓名：" + row.get("name") + "\n"
                + "- 现职位：" + nz(row.get("position"), "未分配")
                + "（部门：" + nz(row.get("department"), "-") + "）"
                + GROUND_TRUTH_HINT;
    }

    @Tool(name = "update_employee_contact", description = "更新员工的联系方式（手机号/邮箱，至少填一项）")
    public String updateEmployeeContact(
            @ToolParam(description = "员工ID") Long employeeId,
            @ToolParam(description = "新手机号，不修改则留空") String phone,
            @ToolParam(description = "新邮箱，不修改则留空") String email) {
        if (employeeId == null) {
            return "请提供要修改联系方式的员工ID（可用 search_employee 查询）。";
        }
        String bad = Args.phone(phone);
        if (bad == null) {
            bad = Args.email(email);
        }
        if (bad != null) {
            return "无法更新联系方式：" + bad;
        }
        phone = Args.clean(phone);
        email = Args.clean(email);

        // 使用 EmployeeGuard 校验：离职员工也允许改联系方式（联系方式与在职状态无关）
        String guardError = employeeGuard.requireExists(employeeId, "更新联系方式");
        if (guardError != null) {
            return guardError;
        }
        List<Map<String, Object>> employees = jdbc.queryForList(
                "SELECT id, name, status FROM employee WHERE id = ?", employeeId);
        if (phone == null && email == null) {
            return "手机号和邮箱至少填写一项才能更新。";
        }
        // 一次 UPDATE 完成：避免两次写入中途失败导致只改了一半
        jdbc.update("UPDATE employee SET phone = COALESCE(?, phone), email = COALESCE(?, email) WHERE id = ?",
                phone, email, employeeId);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT id, name, phone, email, status FROM employee WHERE id = ?", employeeId);
        String offDutyHint = "在职".equals(String.valueOf(row.get("status")))
                ? "" : "\n注：该员工当前状态为「" + row.get("status") + "」，仅更新了联系方式，状态未变动。";
        return "已更新员工「" + row.get("name") + "」[ID=" + row.get("id")
                + "] 的联系方式（以下为数据库实际写入的最终值，以此为准）：\n"
                + "- 手机号：" + nz(row.get("phone"), "未填写") + "\n"
                + "- 邮箱：" + nz(row.get("email"), "未填写")
                + offDutyHint
                + GROUND_TRUTH_HINT;
    }

    @Tool(name = "list_employee_belongings",
            description = "查询某位员工的「个人物品栏」：他当前领用中的全部物品（资产编号、物品名称、领用时间、所属仓库）。"
                    + "传员工ID 或姓名。用于查看某人手上有什么公物，或离职前核对需要交回哪些东西")
    public String listEmployeeBelongings(@ToolParam(description = "员工ID 或姓名") String employee) {
        if (employee == null || employee.isBlank()) {
            return "请提供员工ID 或姓名（可用 list_employees / search_employee 查询）。";
        }
        String k = employee.trim();
        List<Map<String, Object>> emps;
        if (k.matches("\\d+")) {
            emps = jdbc.queryForList("""
                    SELECT e.id, e.name, e.status, p.title AS position, p.department
                    FROM employee e LEFT JOIN `position` p ON p.id = e.position_id
                    WHERE e.id = ?
                    """, Long.parseLong(k));
        } else {
            emps = jdbc.queryForList("""
                    SELECT e.id, e.name, e.status, p.title AS position, p.department
                    FROM employee e LEFT JOIN `position` p ON p.id = e.position_id
                    WHERE e.name LIKE ? ORDER BY e.id LIMIT 6
                    """, "%" + k + "%");
        }
        if (emps.isEmpty()) {
            return "未找到员工「" + k + "」，请用 search_employee 确认。";
        }
        if (emps.size() > 1) {
            StringBuilder sb = new StringBuilder("「" + k + "」匹配到多位员工，请用员工ID 指定：\n");
            for (Map<String, Object> e : emps) {
                sb.append("- [ID=").append(e.get("id")).append("] ").append(e.get("name"))
                  .append("（").append(e.get("status")).append("）\n");
            }
            return sb.toString();
        }

        Map<String, Object> emp = emps.get(0);
        Long empId = ((Number) emp.get("id")).longValue();
        String head = "员工「" + emp.get("name") + "」[ID=" + empId + "]"
                + (emp.get("position") == null ? "" : "（" + emp.get("position")
                   + (emp.get("department") == null ? "" : "/" + emp.get("department")) + "）")
                + "，状态：" + emp.get("status");

        // 只列「已调拨」= 他手上正在用的；退回之后会回到仓库的在库清单里，不再属于个人物品栏
        // 通过 AssetService.queryByEmployee 查询，HR 不直接写 SQL 查 wms_* 表
        List<Map<String, Object>> rows = assets.queryByEmployee(empId);
        if (rows.size() > Args.MAX_LIST) {
            rows = rows.subList(0, Args.MAX_LIST);
        }

        if (rows.isEmpty()) {
            return head + " 的个人物品栏为空：当前没有领用中的物品。";
        }
        StringBuilder sb = new StringBuilder(head + " 的个人物品栏（共 " + rows.size() + " 件领用中）：\n");
        for (Map<String, Object> r : rows) {
            sb.append("- ").append(r.get("asset_no")).append(" ").append(r.get("item_name"))
              .append("[").append(r.get("sku")).append("]")
              .append(" | 领用于 ").append(r.get("allocated_at"))
              .append(" | 归属仓库：").append(r.get("wh_name")).append("(").append(r.get("wh_code")).append(")")
              .append("\n");
        }
        sb.append("\n（以上为该员工当前持有的全部公物。办理离职时会自动全部退回仓库；"
                + "也可用仓库模块的 return_asset 指定资产编号单独退回。）");
        return sb.toString();
    }

    @Tool(name = "dismiss_employee",
            description = "办理员工离职：将员工状态改为「离职」并置空职位，保留历史记录不删除；"
                    + "并自动把该员工名下领用的物品**全部退回仓库**（异步执行，不影响离职主流程）")
    public String dismissEmployee(@ToolParam(description = "员工ID") Long employeeId) {
        if (employeeId == null) {
            return "请提供要办理离职的员工ID（可用 search_employee 查询）。";
        }
        List<Map<String, Object>> employees = jdbc.queryForList(
                "SELECT id, name, status FROM employee WHERE id = ?", employeeId);
        if (employees.isEmpty()) {
            return "未找到员工 [ID=" + employeeId + "]，可能已离职。请用 search_employee 确认员工ID。";
        }
        String name = String.valueOf(employees.get(0).get("name"));
        if (!"在职".equals(String.valueOf(employees.get(0).get("status")))) {
            return "员工「" + name + "」[ID=" + employeeId + "] 当前已是离职状态，无需重复办理。";
        }

        // 1) 先改员工状态（同时置空 position_id，消除"离职员工挂职位"的脏数据）
        //    使用条件更新：只有当前状态为「在职」才执行，防止并发重复办理
        int updated = jdbc.update(
                "UPDATE employee SET status = '离职', position_id = NULL WHERE id = ? AND status = '在职'",
                employeeId);
        if (updated == 0) {
            return "员工「" + name + "」[ID=" + employeeId + "] 的状态在这期间已被其他操作改变，离职未办理。";
        }

        // 2) 发事件触发资产回收（异步、独立事务，不影响离职主流程）
        //    回收结果会在日志中记录；若失败可通过 return_asset 手动补偿
        eventPublisher.publishEvent(new EmployeeResignedEvent(this, employeeId, name));

        return "已为员工「" + name + "」[ID=" + employeeId + "] 办理离职（以下为数据库实际写入的最终值，以此为准）：\n"
                + "- 员工状态：在职 → 离职（历史记录保留）\n"
                + "- 职位：已置空（离职员工不再占用职位编制）\n"
                + "- 名下物品回收：已触发异步回收，结果请查看日志或稍后使用 list_employee_belongings 确认\n"
                + GROUND_TRUTH_HINT;
    }
}
