package com.tmdbwh.realtime.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/** CLI 参数解析与执行环境配置。 */
class RealtimeCliTest {

    @Test
    void helpSucceeds() {
        int exitCode = new CommandLine(new RealtimeCli()).execute("--help");
        assertThat(exitCode).isZero();
    }

    @Test
    void jobNamesAreParsedFromCliValues() {
        assertThat(RealtimeCli.JobName.parse("entity-change")).isEqualTo(RealtimeCli.JobName.ENTITY_CHANGE);
        assertThat(RealtimeCli.JobName.parse("popularity-trend")).isEqualTo(RealtimeCli.JobName.POPULARITY_TREND);
        assertThat(RealtimeCli.JobName.parse("POPULARITY_TREND")).isEqualTo(RealtimeCli.JobName.POPULARITY_TREND);
    }

    @Test
    void unknownJobIsRejectedWithClearMessage() {
        assertThatThrownBy(() -> RealtimeCli.JobName.parse("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entity-change");
    }

    @Test
    void environmentIsConfiguredWithCheckpointAndParallelism() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        RealtimeCli.configureEnvironment(env, com.tmdbwh.realtime.RealtimeConfigTestSupport.config(), null);

        assertThat(env.getParallelism()).isEqualTo(2);
        assertThat(env.getCheckpointConfig().isCheckpointingEnabled()).isTrue();
        assertThat(env.getCheckpointInterval()).isEqualTo(Duration.ofSeconds(30).toMillis());
    }

    @Test
    void checkpointStorageIsSetWhenProvided() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        RealtimeCli.configureEnvironment(env, com.tmdbwh.realtime.RealtimeConfigTestSupport.config(),
                "file:///tmp/tmdbwh-ck");
        assertThat(env.getCheckpointConfig().isCheckpointingEnabled()).isTrue();
    }

    @Test
    void missingStateBackendClassDoesNotFailTheJob() {
        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironment(1);
        // 状态后端不可用时回退到集群默认，不应阻断启动
        RealtimeCli.configureStateBackend(env, "com.example.NotAStateBackend");
        RealtimeCli.configureStateBackend(env, null);
        RealtimeCli.configureStateBackend(env, "");
    }
}
