package com.hr.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Spring Security 配置：API 认证 + 角色控制
 *
 * 安全策略：
 * - /api/health、/api/channels、/api/modules 等只读接口：允许匿名访问（前端初始化需要）
 * - /api/chat、/api/approvals 等写操作接口：需要认证
 * - /api/approvals POST（审批决策）：需要 ADMIN 或 APPROVER 角色
 * - 会话管理：无状态（STATELESS），不创建 HTTP Session
 * - 密码加密：BCrypt
 *
 * 认证方式：HTTP Basic（生产环境可替换为 JWT/OAuth2）
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 密码编码器：BCrypt 强度 10
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * 安全过滤链配置
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // 无状态会话：不创建 Session，每次请求都需认证
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            // CSRF 保护：无状态 API 不需要（使用 Token 认证时）
            .csrf(csrf -> csrf.disable())
            // 授权规则
            .authorizeHttpRequests(auth -> auth
                // 只读接口：允许匿名（仅限前端初始化必需的最小集合）
                .requestMatchers(
                    "/api/health",
                    "/api/channels",
                    "/api/modules",
                    "/api/embedding",
                    "/api/manage/amapJsKey"  // 高德 JS key 配置（前端地图加载）
                ).permitAll()
                // 静态资源
                .requestMatchers("/", "/index.html", "/static/**", "/favicon.ico", "/assets/**").permitAll()
                // 管理端：仅系统管理员（模型来源/熔断降级/统计/用户/系统配置）
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                // 业务数据管理接口：已收敛到管理端，仅管理员
                .requestMatchers("/api/manage/**").hasRole("ADMIN")
                // 配送员专属：只能看自己的配送单
                .requestMatchers("/api/delivery/my/**").hasAnyRole("COURIER", "ADMIN")
                // 配送管理：仅管理员
                .requestMatchers("/api/delivery/**").hasRole("ADMIN")
                // 审批决策（POST）：仅 ADMIN / APPROVER——HITL 是所有写操作的最后防线，
                // 普通 USER 不能批准/拒绝/改参数执行写操作（GET 查询待审批列表只需登录）
                .requestMatchers(HttpMethod.POST, "/api/approvals").hasAnyRole("ADMIN", "APPROVER")
                // 会话历史 / 待审批查询 / 对话：需要登录（会话归属在 Controller 内校验）
                .requestMatchers("/api/threads", "/api/threads/**").authenticated()
                .requestMatchers("/api/approvals").authenticated()
                .requestMatchers("/api/chat", "/api/chat/**").authenticated()
                .requestMatchers("/api/embedding/test").authenticated()
                // 其他所有请求：需要认证
                .anyRequest().authenticated()
            )
            // HTTP Basic 认证（生产环境建议替换为 JWT）
            // 注意：自定义 EntryPoint，401 时不带 WWW-Authenticate 头——
            // 否则浏览器会弹出原生登录框，把 SPA 自己的登录页顶掉。
            .httpBasic(basic -> basic
                .realmName("HR-Agent API")
                .authenticationEntryPoint((req, res, ex) -> res.sendError(401)));

        return http.build();
    }
}
