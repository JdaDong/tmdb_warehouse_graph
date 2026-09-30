package com.tmdbwh.ingestion.sink;

import com.tmdbwh.common.config.KafkaConfig;
import com.tmdbwh.common.model.EventEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 平台事件生产者。
 *
 * <p>分区键为 {@code entityType:entityId}（见 {@link EventEnvelope#kafkaKey()}），保证同一实体的事件落入同一分区、
 * 按序消费——这是下游"按实体去重 / 按实体取最新状态"的前提。
 *
 * <p>幂等性由两层保证：
 *
 * <ul>
 *   <li>Kafka 侧：{@code enable.idempotence=true} + {@code acks=all} + {@code max.in.flight=5}，避免重试导致乱序；
 *   <li>业务侧：{@link EventEnvelope#getEventId()} 由内容哈希生成，同一内容重复投递得到相同 ID，
 *       下游 Flink 去重与 ClickHouse ReplacingMergeTree 据此消除重复。
 * </ul>
 *
 * <p><b>关于成功判定</b>：KafkaProducer 的 Future 只在本地入队失败时抛异常；broker 侧的错误通过回调传递，
 * Future 仍会正常完成。因此 {@link #sendSync} / {@link #sendBatch} 以"回调是否报错"作为成功判据，
 * 只统计实际投递成功的条数。发送失败不使作业失败：采集的主要产出是数据湖中的原始文件， 实时事件丢失可由离线链路补算，但失败数会进入监控告警。
 */
public class KafkaEventProducer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaEventProducer.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(30);

    private final Producer<String, byte[]> producer;
    private final com.tmdbwh.ingestion.metrics.IngestionMetrics metrics;
    private boolean closed;

    public KafkaEventProducer(Producer<String, byte[]> producer,
            com.tmdbwh.ingestion.metrics.IngestionMetrics metrics) {
        this.producer = Objects.requireNonNull(producer, "producer");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /** 按配置创建生产者（acks=all、幂等、snappy 压缩、批量投递）。 */
    public static KafkaEventProducer create(KafkaConfig config,
            com.tmdbwh.ingestion.metrics.IngestionMetrics metrics) {
        java.util.Properties props = config.toClientProperties("ingestion-producer");
        props.setProperty("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.setProperty("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        props.setProperty("acks", "all");
        props.setProperty("enable.idempotence", "true");
        props.setProperty("max.in.flight.requests.per.connection", "5");
        props.setProperty("compression.type", "snappy");
        props.setProperty("linger.ms", "50");
        props.setProperty("batch.size", Integer.toString(64 * 1024));
        props.setProperty("delivery.timeout.ms", "120000");
        return new KafkaEventProducer(new KafkaProducer<>(props), metrics);
    }

    /**
     * 异步发送一条事件。
     *
     * @return 投递结果：成功时以 {@code true} 完成，broker 侧失败时以 {@code false} 完成；
     *         {@link CompletableFuture#get()} 不会因 broker 错误而抛异常，便于调用方直接判断布尔值。
     */
    public CompletableFuture<Boolean> send(String topic, EventEnvelope envelope) {
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(envelope, "envelope");
        checkOpen();

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, envelope.kafkaKey(), envelope.toBytes());
        record.headers().add("eventId", envelope.getEventId().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", envelope.getEventType().name().getBytes(StandardCharsets.UTF_8));

        producer.send(record, (metadata, exception) -> {
            if (exception == null) {
                metrics.recordIngested("kafka", "ok", 1);
                result.complete(true);
            } else {
                metrics.recordIngested("kafka", "failed", 1);
                LOG.error("事件发送失败 topic={} eventId={}: {}", topic, envelope.getEventId(), exception.toString());
                result.complete(false);
            }
        });
        return result;
    }

    /**
     * 同步发送一条事件（等待投递结果）。
     *
     * @return 投递成功返回 true；失败返回 false（不抛异常，调用方决定是否容忍）
     */
    public boolean sendSync(String topic, EventEnvelope envelope) {
        try {
            return Boolean.TRUE.equals(send(topic, envelope).get(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
        } catch (Exception e) {
            metrics.recordIngested("kafka", "failed", 1);
            LOG.error("同步发送事件失败 topic={} eventId={}: {}", topic, envelope.getEventId(), e.toString());
            return false;
        }
    }

    /**
     * 批量同步发送：先异步投递，再统一 flush 并等待每条的结果。
     *
     * @return <b>实际投递成功</b>的条数（不是入参条数），失败条数已计入指标
     */
    public int sendBatch(String topic, List<EventEnvelope> envelopes) {
        Objects.requireNonNull(envelopes, "envelopes");
        List<CompletableFuture<Boolean>> futures = new ArrayList<>(envelopes.size());
        for (EventEnvelope e : envelopes) {
            futures.add(send(topic, e));
        }
        flush();
        int ok = 0;
        for (CompletableFuture<Boolean> f : futures) {
            if (Boolean.TRUE.equals(f.getNow(false))) {
                ok++;
            }
        }
        return ok;
    }

    /** 强制刷出缓冲区（不等待服务端确认，确认由 sendSync / sendBatch 处理）。 */
    public void flush() {
        checkOpen();
        producer.flush();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            producer.flush();
        } finally {
            producer.close(CLOSE_TIMEOUT);
        }
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("KafkaEventProducer 已关闭");
        }
    }
}
