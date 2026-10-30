package com.hr.agent.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.security.SecureRandom;

/**
 * 首次启动初始化管理员账号。
 *
 * <p>设计动机：schema.sql 不再预置弱口令账号——写进代码库的默认密码等于公开后门，
 * 每个新部署的库都会带上它。
 *
 * <p>行为：
 * <ul>
 *   <li>sys_user 已有任何用户 → 什么都不做（绝不覆盖现有账号）；</li>
 *   <li>sys_user 为空 → 创建管理员：用户名取 {@code ADMIN_INITIAL_USERNAME}（默认 admin），
 *       密码取 {@code ADMIN_INITIAL_PASSWORD}；未配置则随机生成 16 位密码，
 *       <b>只在启动日志里打印一次</b>，请立即登录并修改；</li>
 *   <li>数据库不可用（表未建等）→ 跳过，由 UserConfig 的内存应急账号兜底。</li>
 * </ul>
 */
@Configuration
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private static final String USERNAME_ENV = "ADMIN_INITIAL_USERNAME";
    private static final String PASSWORD_ENV = "ADMIN_INITIAL_PASSWORD";
    private static final String DEFAULT_USERNAME = "admin";
    private static final String PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 16;

    @Bean
    public ApplicationRunner adminBootstrap(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                                            @Value("${ADMIN_INITIAL_USERNAME:}") String usernameProp,
                                            @Value("${ADMIN_INITIAL_PASSWORD:}") String passwordProp) {
        return args -> {
            Integer count;
            try {
                count = jdbc.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class);
            } catch (DataAccessException e) {
                log.warn("sys_user 不可用（{}），跳过管理员初始化，内存应急账号仍可用", e.getMessage());
                return;
            }
            if (count != null && count > 0) {
                return;
            }

            String username = firstNonBlank(usernameProp, System.getenv(USERNAME_ENV), DEFAULT_USERNAME);
            String password = firstNonBlank(passwordProp, System.getenv(PASSWORD_ENV), null);
            boolean generated = password == null;
            if (generated) {
                password = randomPassword();
            }

            jdbc.update("INSERT INTO sys_user(username, password, display_name, role) VALUES(?,?,?,?)",
                    username, passwordEncoder.encode(password), "系统管理员", "ADMIN");
            if (generated) {
                log.warn("============================================================");
                log.warn("  初始管理员账号已创建：用户名={}  密码={}", username, password);
                log.warn("  此密码只打印这一次，请立即登录并在「用户管理」中修改！");
                log.warn("  （也可用环境变量 {} 指定初始密码）", PASSWORD_ENV);
                log.warn("============================================================");
            } else {
                log.info("初始管理员账号已创建：用户名={}（密码来自 {}）", username, PASSWORD_ENV);
            }
        };
    }

    private static String randomPassword() {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(PASSWORD_ALPHABET.charAt(random.nextInt(PASSWORD_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String firstNonBlank(String... candidates) {
        for (String s : candidates) {
            if (s != null && !s.isBlank()) {
                return s.trim();
            }
        }
        return null;
    }
}
