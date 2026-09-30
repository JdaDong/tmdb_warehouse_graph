package com.tmdbwh.ingestion.sink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tmdbwh.common.model.ChangeEvent;
import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.common.model.EventEnvelope;
import com.tmdbwh.common.model.EventType;
import com.tmdbwh.ingestion.metrics.IngestionMetrics;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KafkaEventProducerTest {

    private static final String TOPIC = "tmdb.entity.change";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-30T08:00:00Z"), ZoneOffset.UTC);

    private MockProducer<String, byte[]> mock;
    private IngestionMetrics metrics;
    private KafkaEventProducer producer;

    @BeforeEach
    void setUp() {
        // 严格顺序：MockProducer 在 send 时立即执行回调，便于验证成功 / 失败路径
        mock = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer());
        metrics = IngestionMetrics.create();
        producer = new KafkaEventProducer(mock, metrics);
    }

    private static EventEnvelope envelope(long id) {
        ChangeEvent change = new ChangeEvent(EntityType.MOVIE, id, false, "2026-09-29", "2026-09-30",
                CLOCK.millis());
        return EventEnvelope.create(EventType.ENTITY_CHANGED, EntityType.MOVIE, id, CLOCK.millis(), change,
                "test", "trace-1", CLOCK);
    }

    @Test
    void sendUsesEntityKeySoSameEntityStaysOrdered() {
        producer.send(TOPIC, envelope(27205L));

        List<ProducerRecord<String, byte[]>> records = mock.history();
        assertThat(records).hasSize(1);
        // 同一实体的事件必须落到同一分区，下游才能按实体去重 / 取最新状态
        assertThat(records.get(0).key()).isEqualTo("movie:27205");
        assertThat(records.get(0).topic()).isEqualTo(TOPIC);
        assertThat(records.get(0).headers().lastHeader("eventId")).isNotNull();
        assertThat(new String(records.get(0).headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                .isEqualTo("ENTITY_CHANGED");
        assertThat(records.get(0).value()).isNotEmpty();
    }

    @Test
    void valueIsValidJsonEnvelope() {
        producer.send(TOPIC, envelope(155L));
        byte[] value = mock.history().get(0).value();

        EventEnvelope parsed = EventEnvelope.fromBytes(value);
        assertThat(parsed.getEventType()).isEqualTo(EventType.ENTITY_CHANGED);
        assertThat(parsed.getEntityId()).isEqualTo(155L);
        assertThat(parsed.kafkaKey()).isEqualTo("movie:155");
        assertThat(parsed.payloadAs(ChangeEvent.class).getWindowEnd()).isEqualTo("2026-09-30");
    }

    @Test
    void sendSyncReturnsTrueOnSuccess() {
        assertThat(producer.sendSync(TOPIC, envelope(1L))).isTrue();
        assertThat(mock.history()).hasSize(1);
    }

    @Test
    void sendSyncReturnsFalseOnBrokerErrorAndCountsFailure() {
        // 显式构造"回调报错"的底层生产者，验证成功判据来自回调而不是 Future：
        // KafkaProducer 的 Future 在 broker 侧失败时同样正常完成，只看 Future 会把失败误判为成功
        KafkaEventProducer failing = new KafkaEventProducer(producerFailingWith(new RuntimeException("broker down")),
                metrics);

        assertThat(failing.sendSync(TOPIC, envelope(1L))).isFalse();
        // 发送失败只计指标，不使作业失败：实时事件可由离线链路补算
        io.micrometer.core.instrument.Counter counter = metrics.registry()
                .find("tmdb.ingested_records_total").tag("result", "failed").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1);
    }

    @Test
    void sendBatchCountsOnlyActuallyDeliveredEvents() {
        // 3 条中第 3 条投递失败，因此只能统计到 2 条（null 表示该条成功）
        KafkaEventProducer partial =
                new KafkaEventProducer(sequenceProducer(null, null, new RuntimeException("nope")), metrics);

        int ok = partial.sendBatch(TOPIC, List.of(envelope(1L), envelope(2L), envelope(3L)));

        assertThat(ok).isEqualTo(2);
    }

    /** 底层生产者：按传入的异常序列决定每条记录成功 / 失败（null 表示成功）。 */
    private static Producer<String, byte[]> sequenceProducer(RuntimeException... errors) {
        java.util.concurrent.atomic.AtomicInteger index = new java.util.concurrent.atomic.AtomicInteger();
        List<RuntimeException> sequence = java.util.Arrays.asList(errors); // 允许 null 元素
        return new StubProducer((record, callback) -> {
            int i = index.getAndIncrement();
            RuntimeException error = i < sequence.size() ? sequence.get(i) : null;
            callback.onCompletion(null, error);
        });
    }

    private static Producer<String, byte[]> producerFailingWith(RuntimeException error) {
        return new StubProducer((record, callback) -> callback.onCompletion(null, error));
    }

    /** 最小 Producer 实现：只用于测试，不连接任何 broker。 */
    private static class StubProducer implements Producer<String, byte[]> {
        private final java.util.function.BiConsumer<ProducerRecord<String, byte[]>,
                org.apache.kafka.clients.producer.Callback> behavior;

        StubProducer(java.util.function.BiConsumer<ProducerRecord<String, byte[]>,
                org.apache.kafka.clients.producer.Callback> behavior) {
            this.behavior = behavior;
        }

        @Override
        public java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata> send(
                ProducerRecord<String, byte[]> record) {
            return send(record, null);
        }

        @Override
        public java.util.concurrent.Future<org.apache.kafka.clients.producer.RecordMetadata> send(
                ProducerRecord<String, byte[]> record, org.apache.kafka.clients.producer.Callback callback) {
            behavior.accept(record, callback);
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override public void flush() { }
        @Override public void close() { }
        @Override public void close(java.time.Duration timeout) { }
        @Override public java.util.List<org.apache.kafka.common.PartitionInfo> partitionsFor(String topic) {
            return java.util.List.of();
        }
        @Override public java.util.Map<org.apache.kafka.common.MetricName,
                ? extends org.apache.kafka.common.Metric> metrics() {
            return java.util.Map.of();
        }
        @Override public void initTransactions() { }
        @Override public void beginTransaction() { }
        @Override public void sendOffsetsToTransaction(java.util.Map<org.apache.kafka.common.TopicPartition,
                org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets, String consumerGroupId) { }
        @Override public void sendOffsetsToTransaction(java.util.Map<org.apache.kafka.common.TopicPartition,
                org.apache.kafka.clients.consumer.OffsetAndMetadata> offsets,
                org.apache.kafka.clients.consumer.ConsumerGroupMetadata groupMetadata) { }
        @Override public void commitTransaction() { }
        @Override public void abortTransaction() { }
        @Override public org.apache.kafka.common.Uuid clientInstanceId(java.time.Duration timeout) {
            return org.apache.kafka.common.Uuid.randomUuid();
        }
    }

    @Test
    void sendBatchFlushesAndReturnsCount() {
        int sent = producer.sendBatch(TOPIC, List.of(envelope(1L), envelope(2L), envelope(3L)));

        assertThat(sent).isEqualTo(3);
        assertThat(mock.history()).hasSize(3);
        assertThat(mock.history()).extracting(r -> r.key())
                .containsExactly("movie:1", "movie:2", "movie:3");
        assertThat(mock.closed()).isFalse();
    }

    @Test
    void closeFlushesAndClosesProducer() {
        producer.send(TOPIC, envelope(1L));
        producer.close();

        assertThat(mock.closed()).isTrue();
        producer.close(); // 幂等
    }

    @Test
    void operationsAfterCloseAreRejected() {
        producer.close();
        assertThatThrownBy(() -> producer.send(TOPIC, envelope(1L))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> producer.flush()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void sameContentProducesSameEventId() {
        // 幂等基础：内容相同的事件重投得到相同 eventId，下游据此去重
        EventEnvelope a = EventEnvelope.create(EventType.ENTITY_CHANGED, EntityType.MOVIE, 7L, 1L,
                new ChangeEvent(EntityType.MOVIE, 7L, false, "d1", "d2", 1L), "s", "t", CLOCK);
        EventEnvelope b = EventEnvelope.create(EventType.ENTITY_CHANGED, EntityType.MOVIE, 7L, 1L,
                new ChangeEvent(EntityType.MOVIE, 7L, false, "d1", "d2", 1L), "s", "t", CLOCK);
        assertThat(a.getEventId()).isEqualTo(b.getEventId());
    }
}
