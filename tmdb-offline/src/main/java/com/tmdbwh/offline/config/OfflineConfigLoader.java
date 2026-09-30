package com.tmdbwh.offline.config;

import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.util.LogContext;
import java.io.File;

/** 离线模块的配置加载（CLI 与作业共用）。 */
public final class OfflineConfigLoader {

    private OfflineConfigLoader() {}

    /**
     * 加载配置：优先使用 --config 指定的文件。
     *
     * @param configFile 配置文件，可为空
     * @param jobName 作业名（写入日志上下文）
     */
    public static AppConfig load(File configFile, String jobName) {
        try (LogContext ctx = LogContext.forJob(jobName)) {
            AppConfig config = configFile != null ? AppConfig.load(configFile) : AppConfig.load();
            org.slf4j.LoggerFactory.getLogger(OfflineConfigLoader.class)
                    .info("{} 配置加载完成 clickhouse={}", jobName, config.getClickhouse().getUrl());
            return config;
        }
    }
}
