package com.hr.agent.controller.manage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static com.hr.agent.controller.manage.ManageSupport.blankToNull;
import static com.hr.agent.controller.manage.ManageSupport.parseDate;

/**
 * 管理端 · 人力资源域：员工与职位的主数据维护。
 * 业务冲突（如删除仍有在职员工的职位）由全局异常处理器转 409。
 */
@RestController
@RequestMapping("/api/manage")
public class HrManageController {

    private final JdbcTemplate jdbc;

    public HrManageController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/positions")
    public List<Map<String, Object>> positions() {
        return jdbc.queryForList("""
                SELECT p.id, p.title, p.department, p.salary_range, p.description, p.created_at,
                       (SELECT COUNT(*) FROM employee e WHERE e.position_id = p.id AND e.status = '在职') AS emp_count
                FROM `position` p ORDER BY p.id
                """);
    }

    @GetMapping("/employees")
    public List<Map<String, Object>> employees(@RequestParam(defaultValue = "200") int limit) {
        return jdbc.queryForList("""
                SELECT e.id, e.name, e.gender, e.phone, e.email, e.status, e.hire_date, e.created_at,
                       e.position_id, p.title AS position_title, p.department
                FROM employee e LEFT JOIN `position` p ON p.id = e.position_id
                ORDER BY e.id DESC LIMIT ?
                """, Math.min(Math.max(limit, 1), 500));
    }

    // ==================== 员工 CRUD ====================

    public record EmployeeForm(String name, String gender, Long positionId, String phone,
                               String email, String hireDate, String status) {}

    @PostMapping("/employees")
    public Map<String, Object> createEmployee(@RequestBody EmployeeForm f) {
        if (f.name() == null || f.name().isBlank()) throw new IllegalArgumentException("姓名不能为空");
        jdbc.update("INSERT INTO employee(name, gender, position_id, phone, email, hire_date, status) VALUES (?,?,?,?,?,?,?)",
                f.name().trim(), blankToNull(f.gender()), f.positionId(), blankToNull(f.phone()),
                blankToNull(f.email()), parseDate(f.hireDate()),
                f.status() == null || f.status().isBlank() ? "在职" : f.status());
        return Map.of("ok", true);
    }

    @PutMapping("/employees/{id}")
    public Map<String, Object> updateEmployee(@PathVariable long id, @RequestBody EmployeeForm f) {
        if (f.name() == null || f.name().isBlank()) throw new IllegalArgumentException("姓名不能为空");
        jdbc.update("UPDATE employee SET name=?, gender=?, position_id=?, phone=?, email=?, hire_date=?, status=? WHERE id=?",
                f.name().trim(), blankToNull(f.gender()), f.positionId(), blankToNull(f.phone()),
                blankToNull(f.email()), parseDate(f.hireDate()),
                f.status() == null || f.status().isBlank() ? "在职" : f.status(), id);
        return Map.of("ok", true);
    }

    @DeleteMapping("/employees/{id}")
    public Map<String, Object> deleteEmployee(@PathVariable long id) {
        jdbc.update("DELETE FROM employee WHERE id=?", id);
        return Map.of("ok", true);
    }

    // ==================== 职位 CRUD ====================

    public record PositionForm(String title, String department, String salaryRange, String description) {}

    @PostMapping("/positions")
    public Map<String, Object> createPosition(@RequestBody PositionForm f) {
        if (f.title() == null || f.title().isBlank()) throw new IllegalArgumentException("职位名称不能为空");
        if (f.department() == null || f.department().isBlank()) throw new IllegalArgumentException("所属部门不能为空");
        jdbc.update("INSERT INTO `position`(title, department, salary_range, description) VALUES (?,?,?,?)",
                f.title().trim(), f.department().trim(), blankToNull(f.salaryRange()), blankToNull(f.description()));
        return Map.of("ok", true);
    }

    @PutMapping("/positions/{id}")
    public Map<String, Object> updatePosition(@PathVariable long id, @RequestBody PositionForm f) {
        if (f.title() == null || f.title().isBlank()) throw new IllegalArgumentException("职位名称不能为空");
        if (f.department() == null || f.department().isBlank()) throw new IllegalArgumentException("所属部门不能为空");
        jdbc.update("UPDATE `position` SET title=?, department=?, salary_range=?, description=? WHERE id=?",
                f.title().trim(), f.department().trim(), blankToNull(f.salaryRange()), blankToNull(f.description()), id);
        return Map.of("ok", true);
    }

    @DeleteMapping("/positions/{id}")
    public Map<String, Object> deletePosition(@PathVariable long id) {
        int used = jdbc.queryForObject("SELECT COUNT(*) FROM employee WHERE position_id=? AND status='在职'", Integer.class, id);
        if (used > 0) throw new IllegalStateException("该职位下仍有 " + used + " 名在职员工，请先调整员工职位");
        jdbc.update("DELETE FROM `position` WHERE id=?", id);
        return Map.of("ok", true);
    }
}
