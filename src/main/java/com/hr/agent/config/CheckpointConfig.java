package com.hr.agent.config;

import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * 会话记忆的持久化：把默认的内存态 MemorySaver 换成 MysqlSaver，
 * Agent 的多轮上下文（Graph State 里的 messages）落到同一个 MySQL 库，重启不丢。
 * 表由框架自动创建（CREATE_IF_NOT_EXISTS）。
 */
@Configuration
public class CheckpointConfig {

    private static final Logger log = LoggerFactory.getLogger(CheckpointConfig.class);

    @Bean
    public MysqlSaver checkpointSaver(DataSource dataSource) {
        MysqlSaver saver = MysqlSaver.builder()
                .dataSource(dataSource)
                .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
                .build();
        log.info("会话记忆持久化已启用：MysqlSaver（对话上下文写入 MySQL）");
        return saver;
    }
}
