package com.hr.agent.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * 全系统统一时间格式：{@code yyyy-MM-dd HH:mm:ss}。
 *
 * <p>所有 HTTP 出入口的 JSON 时间字段都走这里：
 * <ul>
 *   <li>java.util.Date / java.sql.Timestamp（JdbcTemplate 查出的 created_at 等）→ simpleDateFormat；</li>
 *   <li>java.time.LocalDateTime / LocalDate / LocalTime → JavaTimeModule 序列化器；</li>
 *   <li>反序列化宽容解析：同时接受「yyyy-MM-dd HH:mm:ss」和 ISO「yyyy-MM-dd'T'HH:mm:ss」。</li>
 * </ul>
 */
@Configuration
public class JacksonTimeConfig {

    public static final String DATETIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern(DATETIME_PATTERN);

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer unifiedTimeFormat() {
        return builder -> builder
                .timeZone(TimeZone.getTimeZone("Asia/Shanghai"))
                .simpleDateFormat(DATETIME_PATTERN)
                .serializers(
                        new LocalDateTimeSerializer(DATETIME_FMT),
                        new LocalDateSerializer(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                        new LocalTimeSerializer(DateTimeFormatter.ofPattern("HH:mm:ss")))
                .deserializers(
                        new LenientLocalDateTimeDeserializer(),
                        new LocalDateDeserializer(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
                        new LocalTimeDeserializer(DateTimeFormatter.ofPattern("HH:mm:ss")));
    }

    /** 宽容解析 LocalDateTime：空格与 'T' 分隔都接受，秒可省略 */
    static final class LenientLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String v = p.getText();
            if (v == null || v.isBlank()) {
                return null;
            }
            String s = v.trim().replace('T', ' ');
            // 去掉时区/毫秒后缀（如 .270+08:00、Z）
            s = s.replaceAll("(\\.\\d+)?(\\+\\d{2}:?\\d{2}|Z)?$", "");
            if (s.length() == 16) {
                s = s + ":00";
            }
            if (s.length() == 10) {
                s = s + " 00:00:00";
            }
            return LocalDateTime.parse(s, DATETIME_FMT);
        }
    }
}
