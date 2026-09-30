package com.tmdbwh.realtime;

import com.tmdbwh.common.config.AppConfig;
import com.typesafe.config.ConfigFactory;

/** 测试辅助：构造默认实时配置（不依赖外部配置文件）。 */
public final class RealtimeConfigTestSupport {

    private RealtimeConfigTestSupport() {}

    /** 默认配置（与 reference.conf 中 tmdbwh.realtime 一致）。 */
    public static RealtimeConfig config() {
        AppConfig appConfig = AppConfig.from(ConfigFactory.parseString(
                "tmdbwh.kafka.bootstrap-servers = \"localhost:9092\"")
                .withFallback(ConfigFactory.defaultReference()));
        return RealtimeConfig.fromConfig(ConfigFactory.load().getConfig("tmdbwh.realtime"), appConfig.getKafka());
    }
}
