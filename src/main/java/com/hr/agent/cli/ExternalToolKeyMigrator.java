package com.hr.agent.cli;

import com.hr.agent.service.ExternalToolService;

import com.hr.agent.service.ApiKeyEncryptionService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.core.env.Environment;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * 外部工具密钥迁移工具：把外部工具的 key 加密写入 external_tool 表。
 *
 * <p>通过应用自身的 {@link ExternalToolService} 写入，复用现有数据库连接与
 * AES-256-GCM 加密（{@link ApiKeyEncryptionService}），密钥以密文入库，不明文落库。
 *
 * <p>运行方式（与应用同源的环境变量，DB_HOST/DB_USERNAME/DB_PASSWORD/API_KEY_ENCRYPTION_KEY 等需已配置）：
 * <pre>
 *   # 迁移高德 key（推荐用环境变量传入，避免命令行留痕）
 *   mvn compile exec:java -Dexec.mainClass="com.hr.agent.cli.ExternalToolKeyMigrator" ^
 *       -Dexec.args="--amap=%AMAP_API_KEY%"
 *
 *   # 或直接以参数传入（注意：命令行可能被历史记录）
 *   mvn compile exec:java -Dexec.mainClass="com.hr.agent.cli.ExternalToolKeyMigrator" ^
 *       -Dexec.args="--amap=你的高德key"
 * </pre>
 *
 * <p>支持一次迁移多个：{@code --amap=xxx --othertool=yyy}
 * 不带参数运行时，仅打印 external_tool 表当前各工具的配置状态（不写任何数据）。
 */
@ComponentScan(basePackages = "com.hr.agent.service",
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = ".*"))
public class ExternalToolKeyMigrator {

    private static final Logger log = LoggerFactory.getLogger(ExternalToolKeyMigrator.class);

    public static void main(String[] args) {
        // 以非 Web 模式启动一个最小 Spring 上下文，只扫描 service 包拿到 ExternalToolService
        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(ExternalToolKeyMigrator.class)
                .web(WebApplicationType.NONE)
                .run(args);
        try {
            ExternalToolService service = ctx.getBean(ExternalToolService.class);
            Map<String, String> keys = parseKeys(args, ctx.getEnvironment());

            if (keys.isEmpty()) {
                printStatus(service);
            } else {
                keys.forEach((toolKey, plainKey) -> {
                    service.saveApiKey(toolKey, plainKey);
                    log.info("已加密写入 external_tool：tool_key={}", toolKey);
                });
                System.out.println("迁移完成，已写入 " + keys.size() + " 个工具密钥。");
                printStatus(service);
            }
        } finally {
            ctx.close();
        }
    }

    /** 解析 --toolKey=value 参数；value 为空时回退读环境变量 TOOLKEY_API_KEY（如 AMAP_API_KEY） */
    private static Map<String, String> parseKeys(String[] args, Environment env) {
        Map<String, String> keys = new HashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            String body = arg.substring(2);
            int eq = body.indexOf('=');
            String toolKey = (eq < 0 ? body : body.substring(0, eq)).trim();
            String value = (eq < 0 ? "" : body.substring(eq + 1)).trim();
            if (value.isEmpty()) {
                // 环境变量约定：amap -> AMAP_API_KEY
                value = env.getProperty(toolKey.toUpperCase() + "_API_KEY", "");
            }
            if (!toolKey.isEmpty() && !value.isEmpty()) {
                keys.put(toolKey, value);
            }
        }
        return keys;
    }

    private static void printStatus(ExternalToolService service) {
        System.out.println("external_tool 表当前状态：");
        for (String toolKey : Arrays.asList("weather", "amap")) {
            service.findTool(toolKey).ifPresentOrElse(
                    t -> System.out.printf("  [%s] %s | base_url=%s | auth=%s | 密钥：%s%n",
                            t.toolKey(), t.name(), t.baseUrl(), t.authType(),
                            t.needsAuth() ? (t.hasApiKey() ? "已配置" : "未配置") : "免鉴权"),
                    () -> System.out.printf("  [%s] 未找到（请确认 schema.sql 已执行）%n", toolKey));
        }
    }
}
