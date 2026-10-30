package com.hr.agent.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器：统一错误响应格式，敏感信息脱敏
 *
 * 安全原则：
 * - 对外：只返回错误码和用户友好的提示，不泄露内部实现细节（SQL、表名、堆栈）
 * - 对内：完整异常信息记录到服务端日志，便于排查
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常：返回具体错误信息（但不含敏感细节）
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e, WebRequest request) {
        log.warn("业务参数错误: {}", e.getMessage());
        return buildError(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", e.getMessage());
    }

    /**
     * 非法状态异常：业务冲突（如"该订单仍有配送单未处理"）返回 409 + 具体原因；
     * 该异常在本项目同时用于业务冲突与系统状态，按业务冲突处理，消息对外可见。
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalState(IllegalStateException e, WebRequest request) {
        log.warn("业务状态冲突: {}", e.getMessage());
        return buildError(HttpStatus.CONFLICT, "STATE_CONFLICT", e.getMessage() == null ? "操作与当前状态冲突" : e.getMessage());
    }

    /**
     * 认证失败：401
     */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException e, WebRequest request) {
        log.warn("认证失败: {}", e.getMessage());
        return buildError(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "认证失败，请检查用户名和密码");
    }

    /**
     * 授权失败（权限不足）：403
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e, WebRequest request) {
        log.warn("权限不足: {}", e.getMessage());
        return buildError(HttpStatus.FORBIDDEN, "FORBIDDEN", "权限不足，无法执行此操作");
    }

    /**
     * 安全相关异常：如密钥解密失败
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> handleSecurity(SecurityException e, WebRequest request) {
        log.error("安全异常: {}", e.getMessage(), e);
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "SECURITY_ERROR", "安全服务异常，请联系管理员");
    }

    /**
     * 通用异常：兜底处理，不泄露任何内部信息
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e, WebRequest request) {
        // 完整异常记录到日志，包括堆栈
        log.error("系统异常 [path={}]: {}", request.getDescription(false), e.getMessage(), e);
        // 对外只返回通用错误提示
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "系统内部错误，请稍后重试");
    }

    /**
     * 构建统一错误响应
     *
     * <p>同时写 message 与 error 两个键：message 是标准格式，error 兼容管理端 CRUD 前端
     * （api/manage.js 只读 error 字段）。
     */
    private ResponseEntity<Map<String, Object>> buildError(HttpStatus status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("code", code);
        body.put("message", message);
        body.put("error", message);
        body.put("timestamp", System.currentTimeMillis());
        return ResponseEntity.status(status).body(body);
    }
}
