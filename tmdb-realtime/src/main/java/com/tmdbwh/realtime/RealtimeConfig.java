package com.tmdbwh.realtime;

import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.config.KafkaConfig;
import java.time.Duration;
import java.util.Objects;
import org.apache.flink.streaming.api.CheckpointingMode;

/**
 * 实时作业配置（对应 {@code tmdbwh.realtime} 段）。
 *
 * <p>单独封装而不是在各作业里散落读取的原因：实时作业的参数（窗口、状态 TTL、批大小）
 * 直接决定语义正确性与资源占用，集中定义才能做到"改一处、两个作业同时生效"，
 * 也便于在启动时一次性完成合法性校验（例如窗口必须大于 0）。
 */
public final class RealtimeConfig {

    private final Duration checkpointInterval;
    private final CheckpointingMode checkpointMode;
    private final Duration checkpointTimeout;
    private final Duration minPause;
    private final boolean externalizedCheckpoint;
    private final Duration stateTtl;
    private final boolean incrementalCheckpoint;
    private final Duration windowSize;
    private final Duration allowedLateness;
    private final Duration idleness;
    private final double surgeRatioThreshold;
    private final int surgeMinSamples;
    private final double surgeMinBaseline;
    private final int sinkBatchSize;
    private final Duration flushInterval;
    private final int sinkMaxRetries;
    private final String tableChangeEvent;
    private final String tablePopularityEvent;
    private final String tableMoviePopularity;
    private final String tableSurgeAlert;
    private final int parallelism;
    private final KafkaConfig kafka;

    @SuppressWarnings("java:S107")
    private RealtimeConfig(com.typesafe.config.Config c, KafkaConfig kafka) {
        this.checkpointInterval = c.getDuration("checkpoint.interval");
        this.checkpointMode = "at_least_once".equalsIgnoreCase(c.getString("checkpoint.mode"))
                ? CheckpointingMode.AT_LEAST_ONCE : CheckpointingMode.EXACTLY_ONCE;
        this.checkpointTimeout = c.getDuration("checkpoint.timeout");
        this.minPause = c.getDuration("checkpoint.min-pause");
        this.externalizedCheckpoint = c.getBoolean("checkpoint.externalized");
        this.stateTtl = c.getDuration("dedup.state-ttl");
        this.incrementalCheckpoint = c.getBoolean("dedup.incremental-checkpoint");
        this.windowSize = c.getDuration("trend.window-size");
        this.allowedLateness = c.getDuration("trend.allowed-lateness");
        this.idleness = c.getDuration("trend.idleness");
        this.surgeRatioThreshold = c.getDouble("surge.ratio-threshold");
        this.surgeMinSamples = c.getInt("surge.min-samples");
        this.surgeMinBaseline = c.getDouble("surge.min-baseline");
        this.sinkBatchSize = c.getInt("sink.batch-size");
        this.flushInterval = c.getDuration("sink.flush-interval");
        this.sinkMaxRetries = c.getInt("sink.max-retries");
        this.tableChangeEvent = c.getString("tables.change-event");
        this.tablePopularityEvent = c.getString("tables.popularity-event");
        this.tableMoviePopularity = c.getString("tables.movie-popularity");
        this.tableSurgeAlert = c.getString("tables.surge-alert");
        this.parallelism = c.getInt("parallelism");
        this.kafka = Objects.requireNonNull(kafka, "kafka");
        validate();
    }

    /** 从平台配置加载（读取 classpath 上合并后的 {@code tmdbwh.realtime} 段）。 */
    public static RealtimeConfig load(AppConfig appConfig) {
        Objects.requireNonNull(appConfig, "appConfig");
        return fromConfig(com.typesafe.config.ConfigFactory.load().getConfig("tmdbwh.realtime"),
                appConfig.getKafka());
    }

    /** 由指定 Config 构造（测试与内嵌场景使用）。 */
    public static RealtimeConfig fromConfig(com.typesafe.config.Config c, KafkaConfig kafka) {
        return new RealtimeConfig(Objects.requireNonNull(c, "config"), kafka);
    }

    private void validate() {
        if (checkpointInterval.isZero() || checkpointInterval.isNegative()) {
            throw new IllegalArgumentException("tmdbwh.realtime.checkpoint.interval 必须为正");
        }
        if (windowSize.isZero() || windowSize.isNegative()) {
            throw new IllegalArgumentException("tmdbwh.realtime.trend.window-size 必须为正");
        }
        if (allowedLateness.isNegative()) {
            throw new IllegalArgumentException("tmdbwh.realtime.trend.allowed-lateness 不能为负");
        }
        if (stateTtl.isZero() || stateTtl.isNegative()) {
            throw new IllegalArgumentException("tmdbwh.realtime.dedup.state-ttl 必须为正（否则状态无限增长）");
        }
        if (surgeRatioThreshold <= 1.0) {
            throw new IllegalArgumentException("tmdbwh.realtime.surge.ratio-threshold 必须大于 1");
        }
        if (surgeMinSamples < 1) {
            throw new IllegalArgumentException("tmdbwh.realtime.surge.min-samples 必须 >= 1");
        }
        if (sinkBatchSize < 1) {
            throw new IllegalArgumentException("tmdbwh.realtime.sink.batch-size 必须 >= 1");
        }
        if (parallelism < 1) {
            throw new IllegalArgumentException("tmdbwh.realtime.parallelism 必须 >= 1");
        }
    }

    public Duration getCheckpointInterval() {
        return checkpointInterval;
    }

    public CheckpointingMode getCheckpointMode() {
        return checkpointMode;
    }

    public Duration getCheckpointTimeout() {
        return checkpointTimeout;
    }

    public Duration getMinPause() {
        return minPause;
    }

    public boolean isExternalizedCheckpoint() {
        return externalizedCheckpoint;
    }

    public Duration getStateTtl() {
        return stateTtl;
    }

    public boolean isIncrementalCheckpoint() {
        return incrementalCheckpoint;
    }

    public Duration getWindowSize() {
        return windowSize;
    }

    public Duration getAllowedLateness() {
        return allowedLateness;
    }

    public Duration getIdleness() {
        return idleness;
    }

    public double getSurgeRatioThreshold() {
        return surgeRatioThreshold;
    }

    public int getSurgeMinSamples() {
        return surgeMinSamples;
    }

    public double getSurgeMinBaseline() {
        return surgeMinBaseline;
    }

    public int getSinkBatchSize() {
        return sinkBatchSize;
    }

    public Duration getFlushInterval() {
        return flushInterval;
    }

    public int getSinkMaxRetries() {
        return sinkMaxRetries;
    }

    public String getTableChangeEvent() {
        return tableChangeEvent;
    }

    public String getTablePopularityEvent() {
        return tablePopularityEvent;
    }

    public String getTableMoviePopularity() {
        return tableMoviePopularity;
    }

    public String getTableSurgeAlert() {
        return tableSurgeAlert;
    }

    public int getParallelism() {
        return parallelism;
    }

    public KafkaConfig getKafka() {
        return kafka;
    }

    /** 变更事件 Topic。 */
    public String getChangeTopic() {
        return kafka.getEntityChangeTopic();
    }

    /** 热度事件 Topic。 */
    public String getPopularityTopic() {
        return kafka.getPopularityTopic();
    }

    /** 死信 Topic。 */
    public String getDlqTopic() {
        return kafka.getDlqTopic();
    }
}
