package com.hr.agent.controller;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 登录认证接口：前端登录页用它验证 Basic 凭据是否有效。
 *
 * 该接口不在 SecurityConfig 的 permitAll 白名单里，因此必须携带有效凭据才能访问，
 * 返回 200 即代表「用户名 + 密码」校验通过，401 即失败。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    /**
     * 校验当前凭据并返回用户信息（登录成功页 / 前端启动时恢复会话共用）
     */
    @GetMapping("/check")
    public Map<String, Object> check(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.startsWith("ROLE_") ? a.substring(5) : a)
                .toList();
        return Map.of(
                "username", authentication.getName(),
                "roles", roles
        );
    }
}
