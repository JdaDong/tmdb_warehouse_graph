package com.tmdbwh.governance;

import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.util.LogContext;
import java.io.File;

/** 治理模块的配置加载（CLI 与各治理任务共用）。 */
public final class ConfigLoader {

    private ConfigLoader() {}

    /**
     * 加载配置：优先使用 --config 指定的文件。
     *
     * @param configFile 配置文件，可为空
     * @param jobName 作业名（写入日志上下文）
     */
    public static AppConfig load(File configFile, String jobName) {
        try (LogContext ctx = LogContext.forJob(jobName)) {
            AppConfig config = configFile != null ? AppConfig.load(configFile) : AppConfig.load();
            org.slf4j.LoggerFactory.getLogger(ConfigLoader.class).info("{} 配置加载完成 {}", jobName, config.getEnv());
            return config;
        }
    }
}
