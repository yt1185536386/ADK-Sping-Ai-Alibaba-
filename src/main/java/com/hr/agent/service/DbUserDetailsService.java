package com.hr.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 数据库用户加载：从 sys_user 表读取用户，支持 ADMIN/APPROVER/USER/COURIER 四种角色。
 * 配送员（COURIER）必须关联 employee_id，否则无法登录。
 *
 * 注意：刻意**不实现 UserDetailsService 接口**——Spring 的
 * InitializeUserDetailsManagerConfigurer 在存在多个 UserDetailsService Bean 时
 * 会拒绝使用任何一个（@Primary 无效），导致所有 Basic 认证都 401。
 * 这里只做普通 @Service，由 UserConfig 里唯一的 UserDetailsService Bean 调用。
 */
@Service
public class DbUserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(DbUserDetailsService.class);

    private final JdbcTemplate jdbc;

    public DbUserDetailsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UserDetails loadUser(String username) throws UsernameNotFoundException {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT u.id, u.username, u.password, u.display_name, u.role, u.employee_id, u.enabled,
                       e.name AS emp_name, e.status AS emp_status
                FROM sys_user u
                LEFT JOIN employee e ON e.id = u.employee_id
                WHERE u.username = ?
                """, username);

        if (rows.isEmpty()) {
            log.info("登录加载：sys_user 无用户 {}", username);
            throw new UsernameNotFoundException("用户不存在：" + username);
        }
        Map<String, Object> r = rows.get(0);
        if (((Number) r.get("enabled")).intValue() != 1) {
            log.info("登录加载：用户 {} 已被禁用", username);
            throw new UsernameNotFoundException("用户已被禁用：" + username);
        }

        String role = String.valueOf(r.get("role"));
        // 配送员必须关联员工，且员工状态为在职
        if ("COURIER".equals(role)) {
            Object empId = r.get("employee_id");
            if (empId == null) {
                throw new UsernameNotFoundException("配送员账号未关联员工：" + username);
            }
            String empStatus = r.get("emp_status") == null ? "" : String.valueOf(r.get("emp_status"));
            if (!"在职".equals(empStatus)) {
                throw new UsernameNotFoundException("关联员工已离职，无法登录：" + username);
            }
        }

        log.info("登录加载：用户 {} 来自数据库（role={}）", username, role);
        return User.builder()
                .username(username)
                .password(String.valueOf(r.get("password")))
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_" + role)))
                .build();
    }
}
