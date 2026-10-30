package com.hr.agent.config;

import com.hr.agent.service.DbUserDetailsService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * 用户认证配置：
 * - 主：数据库 sys_user 表（{@link DbUserDetailsService}），支持 ADMIN/APPROVER/USER/COURIER 角色
 * - 兜底：内存应急账号，仅在数据库不可用（表未建/连接失败）时启用——
 *   账号 admin，密码取环境变量 ADMIN_INITIAL_PASSWORD；未配置则应急通道关闭
 *   （硬编码弱口令在公开代码里等于后门，已移除）。
 *   注意：sys_user 为空时由 AdminBootstrap 创建初始管理员，应急账号不再承接"未初始化"场景。
 */
@Configuration
public class UserConfig {

    private static final Logger log = LoggerFactory.getLogger(UserConfig.class);

    /**
     * 唯一的 UserDetailsService Bean：先查数据库，查不到再走内存兜底。
     *
     * ⚠️ 容器里**只能有这一个** UserDetailsService Bean：
     * InitializeUserDetailsManagerConfigurer 发现多个时会全部弃用，
     * 导致任何账号都 401（且不生成应急密码）。因此内存用户在这里就地构建，
     * 不再单独暴露为 Bean；DbUserDetailsService 也刻意不实现该接口。
     */
    @Bean
    public UserDetailsService userDetailsService(DbUserDetailsService dbService,
                                                 PasswordEncoder passwordEncoder,
                                                 @org.springframework.beans.factory.annotation.Value(
                                                         "${ADMIN_INITIAL_PASSWORD:}") String emergencyPasswordProp) {
        // 内存应急账号：仅数据库不可用时启用，密码必须显式配置，杜绝硬编码弱口令
        String emergencyPassword = firstNonBlank(emergencyPasswordProp, System.getenv("ADMIN_INITIAL_PASSWORD"));
        InMemoryUserDetailsManager memoryService = null;
        if (emergencyPassword != null) {
            memoryService = new InMemoryUserDetailsManager(
                    User.builder()
                            .username("admin")
                            .password(passwordEncoder.encode(emergencyPassword))
                            .roles("ADMIN", "APPROVER", "USER")
                            .build());
        } else {
            log.warn("未配置 ADMIN_INITIAL_PASSWORD，数据库不可用时的内存应急登录已关闭");
        }
        final InMemoryUserDetailsManager fallback = memoryService;

        return username -> {
            try {
                return dbService.loadUser(username);
            } catch (UsernameNotFoundException e) {
                // 库里查无此人：直接拒绝（sys_user 为空的初始化场景由 AdminBootstrap 负责，
                // 不再让内存账号兜住，避免弱口令在库建好后仍可登录）
                throw e;
            } catch (org.springframework.dao.DataAccessException e) {
                // 数据库不可用（如表未建/连接失败）：走应急通道（未配置密码则同样拒绝）
                if (fallback == null) {
                    log.error("数据库用户加载失败（{}），且未配置 ADMIN_INITIAL_PASSWORD，拒绝登录：{}",
                            e.getMessage(), username);
                    throw new UsernameNotFoundException("数据库不可用且未配置应急密码");
                }
                log.warn("数据库用户加载失败（{}），回落内存应急账号：{}", e.getMessage(), username);
                return fallback.loadUserByUsername(username);
            }
        };
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
