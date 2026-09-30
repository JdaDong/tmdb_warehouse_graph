package com.tmdbwh.realtime.jobs;

import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.model.EventEnvelope;
import com.tmdbwh.common.util.TimeUtils;
import com.tmdbwh.realtime.EventCodec;
import com.tmdbwh.realtime.EventDeduplicator;
import com.tmdbwh.realtime.KafkaSourceFactory;
import com.tmdbwh.realtime.RealtimeConfig;
import com.tmdbwh.realtime.sink.ClickHouseSink;
import com.tmdbwh.realtime.sink.DeadLetterSink;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实体变更实时作业：Kafka → 解码 → 去重 → ClickHouse（ods.ods_change_event）→ 坏消息进 DLQ。
 *
 * <p>落库目标选 ODS 而不是 rt 库的原因：变更事件的最终归宿是离线贴源层，实时与离线必须能互相对账。
 * 实时写入保证"分钟级可见"，离线每日全量再算一遍保证"口径一致"，两者按 event_id 去重后应完全一致。
 */
public final class EntityChangeJob {

    private static final Logger LOG = LoggerFactory.getLogger(EntityChangeJob.class);

    /** 坏消息侧输出标签。 */
    public static final OutputTag<String> INVALID_TAG = new OutputTag<String>("invalid-change-event") {};

    private EntityChangeJob() {}

    /**
     * 装配作业（不执行，由调用方决定本地运行还是提交集群）。
     *
     * @return 主流（已写入 ClickHouse），侧输出为坏消息
     */
    public static SingleOutputStreamOperator<EventEnvelope> build(StreamExecutionEnvironment env,
            AppConfig appConfig, RealtimeConfig config) {
        DataStream<byte[]> raw = env.fromSource(
                KafkaSourceFactory.byteSource(config.getKafka(), config.getChangeTopic(), "tmdbwh-change"),
                org.apache.flink.api.common.eventtime.WatermarkStrategy.noWatermarks(),
                "kafka-entity-change");

        // 解码：坏消息走侧输出，不让单条脏数据打挂整个作业
        SingleOutputStreamOperator<EventEnvelope> decoded = raw
                .process(new ProcessFunction<byte[], EventEnvelope>() {
                    private static final long serialVersionUID = 1L;

                    @Override
                    public void processElement(byte[] bytes, Context ctx, Collector<EventEnvelope> out) {
                        Optional<EventEnvelope> parsed = EventCodec.decode(bytes);
                        if (parsed.isPresent()) {
                            out.collect(parsed.get());
                        } else {
                            ctx.output(INVALID_TAG, new String(bytes, StandardCharsets.UTF_8));
                        }
                    }
                })
                .name("decode-envelope")
                .uid("decode-envelope");

        decoded.getSideOutput(INVALID_TAG)
                .sinkTo(DeadLetterSink.create(config.getKafka().getBootstrapServers(), config.getDlqTopic(),
                        "tmdbwh-dlq"))
                .name("dlq-change")
                .uid("dlq-change");

        // 去重：按实体 key 分组，比较内容哈希
        SingleOutputStreamOperator<EventEnvelope> deduped = decoded
                .keyBy(EventEnvelope::kafkaKey)
                .process(new EventDeduplicator(config.getStateTtl()))
                .name("dedup-by-content")
                .uid("dedup-by-content");

        List<String> columns = Arrays.asList("dt", "event_id", "entity_type", "entity_id", "event_time",
                "payload", "ingest_time", "schema_version");
        deduped
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
                .name("to-change-row")
                .uid("to-change-row")
                .addSink(new ClickHouseSink(appConfig.getClickhouse(), config.getTableChangeEvent(), columns,
                        config.getSinkBatchSize(), config.getSinkMaxRetries()))
                .name("clickhouse-change-event")
                .uid("clickhouse-change-event");

        LOG.info("实体变更作业已装配: topic={} table={}", config.getChangeTopic(), config.getTableChangeEvent());
        return deduped;
    }

    /** 作业名（提交到集群时展示）。 */
    public static String jobName() {
        return "tmdbwh-entity-change";
    }

    /** 建议的水位线延迟：变更事件允许 1 分钟乱序。 */
    public static Duration boundedOutOfOrderness() {
        return Duration.ofMinutes(1);
    }
}
