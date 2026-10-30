package com.hr.mcpserver.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * API Key 加密服务：AES-256-GCM
 *
 * <p><b>有意重复（accepted duplication）</b>：与主应用
 * {@code com.hr.agent.service.ApiKeyEncryptionService} 内容一致——
 * MCP Server 是独立进程，需独立解密 external_tool.api_key（同一主密钥环境变量
 * API_KEY_ENCRYPTION_KEY）。算法/密文格式改动必须两边同步。
 *
 * <p>设计要点：
 * - 主密钥从环境变量 {@code API_KEY_ENCRYPTION_KEY} 读取，不落代码/配置
 * - 密钥长度 32 字节（256 位），Base64 编码后配置
 * - 使用 GCM 模式（带认证），IV 随机生成，随密文一起存储
 * - 加密格式：Base64(IV || 密文 || 认证标签)
 *
 * 生成主密钥：{@code openssl rand -base64 32}
 */
@Component
public class ApiKeyEncryptionService {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyEncryptionService.class);

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;  // 96 bits
    private static final int GCM_TAG_LENGTH = 128; // 128 bits

    private final SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public ApiKeyEncryptionService(@Value("${api.key.encryption-key:}") String encryptionKey) {
        if (encryptionKey == null || encryptionKey.isBlank()) {
            log.warn("API Key 加密密钥未配置（环境变量 API_KEY_ENCRYPTION_KEY），将使用空密钥。生产环境必须配置！");
            // 使用空密钥数组，解密会失败，但不会导致启动崩溃
            this.secretKey = new SecretKeySpec(new byte[32], ALGORITHM);
        } else {
            byte[] keyBytes = Base64.getDecoder().decode(encryptionKey);
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException(
                    "API_KEY_ENCRYPTION_KEY 必须是 32 字节（256 位）的 Base64 编码，当前长度：" + keyBytes.length);
            }
            this.secretKey = new SecretKeySpec(keyBytes, ALGORITHM);
            log.info("API Key 加密服务已初始化（AES-256-GCM）");
        }
    }

    /**
     * 加密 API Key
     *
     * @param plainKey 明文密钥
     * @return Base64 编码的密文（包含 IV 和认证标签）
     */
    public String encrypt(String plainKey) {
        if (plainKey == null || plainKey.isBlank()) {
            return "";
        }
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec);

            byte[] encrypted = cipher.doFinal(plainKey.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encrypted, 0, combined, iv.length, encrypted.length);

            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new SecurityException("API Key 加密失败", e);
        }
    }

    /**
     * 解密 API Key
     *
     * @param encryptedKey Base64 编码的密文
     * @return 明文密钥
     */
    public String decrypt(String encryptedKey) {
        if (encryptedKey == null || encryptedKey.isBlank()) {
            return "";
        }
        try {
            byte[] combined = Base64.getDecoder().decode(encryptedKey);

            byte[] iv = new byte[GCM_IV_LENGTH];
            byte[] encrypted = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            System.arraycopy(combined, GCM_IV_LENGTH, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec);

            byte[] decrypted = cipher.doFinal(encrypted);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new SecurityException("API Key 解密失败：密钥错误或数据已损坏", e);
        }
    }

    /**
     * 判断当前是否配置了有效密钥
     */
    public boolean isConfigured() {
        try {
            encrypt("test");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
