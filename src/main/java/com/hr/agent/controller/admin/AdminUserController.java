package com.hr.agent.controller.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理端：用户与角色管理（sys_user）。
 *
 * <p>角色沿用现有四类：ADMIN / APPROVER / USER / COURIER。
 * ADMIN 才能进入管理端；COURIER 只能看自己的配送单（由配送接口自行隔离）。
 */
@RestController
@RequestMapping("/api/admin/users")
public class AdminUserController {

    /** 系统内置角色 */
    private static final List<String> ROLES = List.of("ADMIN", "APPROVER", "USER", "COURIER");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;

    public AdminUserController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
    }

    /** 用户列表（不返回密码） */
    @GetMapping
    public List<Map<String, Object>> list() {
        return jdbc.queryForList("""
                SELECT u.id, u.username, u.display_name, u.role, u.employee_id, u.enabled,
                       u.created_at, u.updated_at, e.name AS employee_name, e.phone, e.status AS employee_status
                  FROM sys_user u
                  LEFT JOIN employee e ON e.id = u.employee_id
                 ORDER BY u.id ASC
                """);
    }

    /** 角色字典（前端下拉用） */
    @GetMapping("/roles")
    public List<String> roles() {
        return ROLES;
    }

    /** 用户表单 */
    public record UserForm(String username, String password, String displayName, String role,
                           Long employeeId, Boolean enabled) {
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody UserForm f) {
        String username = f.username() == null ? "" : f.username().trim();
        if (username.isEmpty()) {
            throw new IllegalArgumentException("登录账号不能为空");
        }
        if (f.password() == null || f.password().length() < 6) {
            throw new IllegalArgumentException("密码长度至少 6 位");
        }
        String role = normalizeRole(f.role());
        Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username = ?", Integer.class, username);
        if (exists != null && exists > 0) {
            throw new IllegalArgumentException("账号已存在：" + username);
        }
        jdbc.update("""
                INSERT INTO sys_user(username, password, display_name, role, employee_id, enabled)
                VALUES (?,?,?,?,?,?)
                """, username, passwordEncoder.encode(f.password()),
                f.displayName() == null || f.displayName().isBlank() ? username : f.displayName().trim(),
                role, f.employeeId(), f.enabled() == null ? 1 : (f.enabled() ? 1 : 0));
        return Map.of("ok", true);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(@PathVariable long id, @RequestBody UserForm f) {
        String role = normalizeRole(f.role());
        jdbc.update("""
                UPDATE sys_user SET display_name = ?, role = ?, employee_id = ?, enabled = ? WHERE id = ?
                """,
                f.displayName() == null || f.displayName().isBlank() ? null : f.displayName().trim(),
                role, f.employeeId(),
                f.enabled() == null ? 1 : (f.enabled() ? 1 : 0), id);
        return Map.of("ok", true);
    }

    /** 重置密码 */
    @PostMapping("/{id}/password")
    public Map<String, Object> resetPassword(@PathVariable long id, @RequestBody Map<String, String> body) {
        String password = body == null ? null : body.get("password");
        if (password == null || password.length() < 6) {
            throw new IllegalArgumentException("密码长度至少 6 位");
        }
        jdbc.update("UPDATE sys_user SET password = ? WHERE id = ?", passwordEncoder.encode(password), id);
        return Map.of("ok", true);
    }

    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT username, role FROM sys_user WHERE id = ?", id);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("用户不存在：" + id);
        }
        String role = String.valueOf(rows.get(0).get("role"));
        if ("ADMIN".equals(role)) {
            Integer admins = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM sys_user WHERE role = 'ADMIN' AND enabled = 1 AND id <> ?", Integer.class, id);
            if (admins != null && admins == 0) {
                throw new IllegalStateException("系统至少需要保留一个可用的管理员账号");
            }
        }
        jdbc.update("DELETE FROM sys_user WHERE id = ?", id);
        return Map.of("ok", true);
    }

    private static String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return "USER";
        }
        String r = role.trim().toUpperCase();
        if (!ROLES.contains(r)) {
            throw new IllegalArgumentException("非法角色：" + role + "（可选：" + String.join("/", ROLES) + "）");
        }
        return r;
    }

    /** 供其它管理端接口复用的空 Map 构造 */
    static Map<String, Object> empty() {
        return new LinkedHashMap<>();
    }
}
