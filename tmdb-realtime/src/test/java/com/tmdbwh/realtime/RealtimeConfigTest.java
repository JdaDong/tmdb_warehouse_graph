package com.tmdbwh.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tmdbwh.common.config.AppConfig;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.time.Duration;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.junit.jupiter.api.Test;

/** 实时配置加载与校验。 */
class RealtimeConfigTest {

    private static final Config BASE = ConfigFactory.load().getConfig("tmdbwh.realtime");

    private static RealtimeConfig config(String overrides) {
        AppConfig appConfig = AppConfig.from(ConfigFactory.parseString(
                "tmdbwh.kafka.bootstrap-servers = \"localhost:9092\"")
                .withFallback(ConfigFactory.defaultReference()));
        return RealtimeConfig.fromConfig(ConfigFactory.parseString(overrides).withFallback(BASE),
                appConfig.getKafka());
    }

    @Test
    void defaultsAreSane() {
        RealtimeConfig config = config("");

        assertThat(config.getCheckpointInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(config.getCheckpointMode()).isEqualTo(CheckpointingMode.EXACTLY_ONCE);
        assertThat(config.getWindowSize()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.getStateTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(config.getParallelism()).isPositive();
        assertThat(config.getChangeTopic()).isNotBlank();
        assertThat(config.getDlqTopic()).isNotBlank();
    }

    @Test
    void tablesMatchMigrationScripts() {
        RealtimeConfig config = config("");

        // 与 ClickHouse 迁移脚本 V3 / V9 保持一致
        assertThat(config.getTableChangeEvent()).isEqualTo("ods.ods_change_event");
        assertThat(config.getTablePopularityEvent()).isEqualTo("ods.ods_popularity_event");
        assertThat(config.getTableMoviePopularity()).isEqualTo("rt.rt_movie_popularity");
        assertThat(config.getTableSurgeAlert()).isEqualTo("rt.rt_surge_alert");
    }

    @Test
    void atLeastOnceModeIsSupported() {
        assertThat(config("checkpoint.mode = \"at_least_once\"").getCheckpointMode())
                .isEqualTo(CheckpointingMode.AT_LEAST_ONCE);
    }

    @Test
    void rejectsInvalidValues() {
        assertThatThrownBy(() -> config("checkpoint.interval = 0s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checkpoint.interval");
        assertThatThrownBy(() -> config("trend.window-size = 0s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("window-size");
        assertThatThrownBy(() -> config("trend.allowed-lateness = -1s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("allowed-lateness");
        assertThatThrownBy(() -> config("dedup.state-ttl = 0s"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state-ttl");
        assertThatThrownBy(() -> config("surge.ratio-threshold = 1.0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ratio-threshold");
        assertThatThrownBy(() -> config("surge.min-samples = 0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min-samples");
        assertThatThrownBy(() -> config("sink.batch-size = 0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batch-size");
        assertThatThrownBy(() -> config("parallelism = 0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parallelism");
    }

    @Test
    void environmentOverridesAreApplied() {
        // 部署时通过环境变量调整窗口与并行度（见 .env.example）
        assertThat(config("trend.window-size = 1 min").getWindowSize()).isEqualTo(Duration.ofMinutes(1));
        assertThat(config("parallelism = 8").getParallelism()).isEqualTo(8);
    }
}
