package com.tmdbwh.realtime.sink;

import java.util.Objects;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.api.common.serialization.SimpleStringSchema;

/**
 * 死信队列 Sink。
 *
 * <p>为什么必须有：实时链路里"一条坏消息"如果直接抛异常，会让作业进入失败重启循环，
 * 而重启后又会读到同一条消息——整条链路被一条脏数据卡死。
 * 正确做法是把无法处理的消息旁路到 DLQ，主链路继续，DLQ 由离线任务定期回放或人工排查。
 *
 * <p>DLQ 消息为"原始字节 + 错误信息"，保留原始内容以便修复后重放。
 */
public final class DeadLetterSink {

    private DeadLetterSink() {}

    /** 构建写入 DLQ Topic 的 KafkaSink。 */
    public static KafkaSink<String> create(String bootstrapServers, String topic, String transactionPrefix) {
        Objects.requireNonNull(bootstrapServers, "bootstrapServers");
        Objects.requireNonNull(topic, "topic");
        return KafkaSink.<String>builder()
                .setBootstrapServers(bootstrapServers)
                .setRecordSerializer(KafkaRecordSerializationSchema.<String>builder()
                        .setTopic(topic)
                        .setValueSerializationSchema(new SimpleStringSchema())
                        .build())
                // DLQ 本身只要求不丢，使用至少一次即可（重复一条死信不会有副作用）
                .setDeliveryGuarantee(org.apache.flink.connector.base.DeliveryGuarantee.AT_LEAST_ONCE)
                .build();
    }
}
