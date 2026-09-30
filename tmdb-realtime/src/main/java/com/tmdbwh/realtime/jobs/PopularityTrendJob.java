package com.tmdbwh.realtime.jobs;

import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.common.model.EventEnvelope;
import com.tmdbwh.common.util.TimeUtils;
import com.tmdbwh.realtime.EventCodec;
import com.tmdbwh.realtime.KafkaSourceFactory;
import com.tmdbwh.realtime.PopularityAggregate;
import com.tmdbwh.realtime.RealtimeConfig;
import com.tmdbwh.realtime.SurgeDetector;
import com.tmdbwh.realtime.WindowedPopularity;
import com.tmdbwh.realtime.sink.ClickHouseSink;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 热度趋势实时作业：Kafka → 解码 → 落 ODS → 滚动窗口聚合 → ClickHouse（rt 库）+ 飙升告警。
 *
 * <p>两条输出缺一不可：
 *
 * <ul>
 *   <li>原始事件落 {@code ods.ods_popularity_event}：实时结果出问题时，可用同一份原始事件离线重算；
 *   <li>窗口聚合落 {@code rt.rt_movie_popularity}：供大屏与接口直接查询的分钟级结果。
 * </ul>
 */
public final class PopularityTrendJob {

    private static final Logger LOG = LoggerFactory.getLogger(PopularityTrendJob.class);

    /** 坏消息侧输出标签。 */
    public static final OutputTag<String> INVALID_TAG = new OutputTag<String>("invalid-popularity-event") {};

    private PopularityTrendJob() {}

    /** 装配作业。 */
    public static void build(StreamExecutionEnvironment env, AppConfig appConfig, RealtimeConfig config) {
        DataStreamSource<byte[]> raw = env.fromSource(
                KafkaSourceFactory.byteSource(config.getKafka(), config.getPopularityTopic(),
                        "tmdbwh-popularity"),
                WatermarkStrategy.noWatermarks(),
                "kafka-popularity");

        SingleOutputStreamOperator<EventEnvelope> decoded = raw
                .process(new ProcessFunction<byte[], EventEnvelope>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void processElement(byte[] bytes, Context ctx, Collector<EventEnvelope> out) {
                        EventCodec.decode(bytes).ifPresentOrElse(out::collect,
                                () -> ctx.output(INVALID_TAG, new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
                    }
                })
                .name("decode-popularity")
                .uid("decode-popularity");

        decoded.getSideOutput(INVALID_TAG)
                .sinkTo(com.tmdbwh.realtime.sink.DeadLetterSink.create(
                        config.getKafka().getBootstrapServers(), config.getDlqTopic(), "tmdbwh-dlq"))
                .name("dlq-popularity")
                .uid("dlq-popularity");

        // 1) 原始事件落 ODS（离线兜底用）
        List<String> eventColumns = Arrays.asList("dt", "event_id", "entity_type", "entity_id", "event_time",
                "payload", "ingest_time", "schema_version");
        decoded
                .map(envelope -> new Object[] {
                        java.sql.Date.valueOf(TimeUtils.toLocalDate(envelope.getEventTime(),
                                java.time.ZoneOffset.UTC)),
                        envelope.getEventId(),
                        envelope.getEntityType().getApiPath(),
                        envelope.getEntityId(),
                        new java.sql.Timestamp(envelope.getEventTime()),
                        envelope.getPayload() == null ? "{}" : envelope.getPayload().toString(),
                        new java.sql.Timestamp(envelope.getIngestTime()),
                        envelope.getSchemaVersion()
                })
                .returns(Object[].class)
                .name("to-popularity-row")
                .uid("to-popularity-row")
                .addSink(new ClickHouseSink(appConfig.getClickhouse(), config.getTablePopularityEvent(),
                        eventColumns, config.getSinkBatchSize(), config.getSinkMaxRetries()))
                .name("clickhouse-popularity-event")
                .uid("clickhouse-popularity-event");

        // 2) 事件时间语义：水印 = 事件时间 - 允许乱序，并标记分区空闲（否则空闲分区会让窗口永不触发）
        SingleOutputStreamOperator<EventEnvelope> timestamped = decoded
                .assignTimestampsAndWatermarks(WatermarkStrategy
                        .<EventEnvelope>forBoundedOutOfOrderness(config.getAllowedLateness())
                        .withTimestampAssigner((envelope, ignored) -> envelope.getEventTime())
                        .withIdleness(config.getIdleness()))
                .name("watermark")
                .uid("watermark");

        // 3) 电影热度窗口聚合
        SingleOutputStreamOperator<WindowedPopularity> windowed = timestamped
                .filter(envelope -> envelope.getEntityType() == EntityType.MOVIE)
                .name("filter-movie")
                .uid("filter-movie")
                .keyBy(EventEnvelope::getEntityId)
                .window(org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows
                        .of(Time.milliseconds(config.getWindowSize().toMillis())))
                .aggregate(new PopularityAggregate(), new PopularityWindowFunction())
                .name("window-popularity")
                .uid("window-popularity");

        List<String> popularityColumns = Arrays.asList("entity_id", "event_time", "popularity", "vote_average",
                "vote_count", "title", "list_name", "ingest_time", "version");
        windowed
                .map(result -> new Object[] {
                        result.getEntityId(),
                        new java.sql.Timestamp(result.getWindowStart()),
                        result.getAvgPopularity(),
                        result.getSampleCount() == 0 ? null : result.getAvgVoteAverage(),
                        result.getVoteCount(),
                        result.getTitle(),
                        result.getListName(),
                        new java.sql.Timestamp(result.getWindowEnd()),
                        // version 取窗口结束时间：同一窗口重算时新版本覆盖旧版本
                        new java.sql.Timestamp(result.getWindowEnd())
                })
                .returns(Object[].class)
                .name("to-movie-popularity-row")
                .uid("to-movie-popularity-row")
                .addSink(new ClickHouseSink(appConfig.getClickhouse(), config.getTableMoviePopularity(),
                        popularityColumns, config.getSinkBatchSize(), config.getSinkMaxRetries()))
                .name("clickhouse-movie-popularity")
                .uid("clickhouse-movie-popularity");

        // 4) 飙升检测与告警
        SurgeDetector detector = new SurgeDetector(config.getSurgeRatioThreshold(), config.getSurgeMinSamples(),
                config.getSurgeMinBaseline());
        windowed
                .keyBy(WindowedPopularity::getEntityId)
                .process(new SurgeDetectorFunction(detector, EntityType.MOVIE.getApiPath(), config.getStateTtl()))
                .name("surge-detect")
                .uid("surge-detect")
                .map(alert -> new Object[] {
                        alert.getAlertId(),
                        alert.getEntityType(),
                        alert.getEntityId(),
                        alert.getTitle(),
                        new java.sql.Timestamp(alert.getWindowStart()),
                        new java.sql.Timestamp(alert.getWindowEnd()),
                        alert.getBaselinePopularity(),
                        alert.getCurrentPopularity(),
                        alert.getGrowthRatio(),
                        new java.sql.Timestamp(alert.getAlertTime())
                })
                .returns(Object[].class)
                .name("to-alert-row")
                .uid("to-alert-row")
                .addSink(new ClickHouseSink(appConfig.getClickhouse(), config.getTableSurgeAlert(),
                        Arrays.asList("alert_id", "entity_type", "entity_id", "title", "window_start",
                                "window_end", "baseline_popularity", "current_popularity", "growth_ratio",
                                "alert_time"),
                        config.getSinkBatchSize(), config.getSinkMaxRetries()))
                .name("clickhouse-surge-alert")
                .uid("clickhouse-surge-alert");

        LOG.info("热度趋势作业已装配: topic={} window={} table={}", config.getPopularityTopic(),
                config.getWindowSize(), config.getTableMoviePopularity());
    }

    /** 作业名。 */
    public static String jobName() {
        return "tmdbwh-popularity-trend";
    }

    /** 建议的水位线延迟。 */
    public static Duration boundedOutOfOrderness(RealtimeConfig config) {
        return config.getAllowedLateness();
    }
}
