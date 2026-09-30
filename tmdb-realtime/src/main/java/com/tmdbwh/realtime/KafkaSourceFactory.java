package com.tmdbwh.realtime;

import com.tmdbwh.common.config.KafkaConfig;
import java.util.Objects;
import java.util.Properties;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.KafkaSourceBuilder;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.connector.kafka.source.reader.deserializer.KafkaRecordDeserializationSchema;
import org.apache.flink.util.Collector;

/**
 * Kafka Source 构建。
 *
 * <p>统一在这里设置的三件事，都是"不设就会出问题"的项：
 *
 * <ul>
 *   <li><b>起始位移</b>：从已提交位移继续，无位移时从最早开始（首次启动不丢数据）；
 *   <li><b>位移提交方式</b>：关闭自动提交，由 checkpoint 完成时提交——这是"精确一次"的前提，
 *       否则会出现"处理了但没提交 / 提交了但没处理"的偏差；
 *   <li><b>分区发现</b>：Topic 扩容分区后能自动感知（默认 10 分钟间隔，这里显式配置便于调整）。
 * </ul>
 */
public final class KafkaSourceFactory {

    private KafkaSourceFactory() {}

    /**
     * 构建字节流 Source（解码放在后续算子，便于坏消息路由到 DLQ）。
     *
     * @param kafka Kafka 配置
     * @param topic Topic 名
     * @param groupId 消费组（同一个作业的不同实例必须一致，否则会重复消费）
     */
    public static KafkaSource<byte[]> byteSource(KafkaConfig kafka, String topic, String groupId) {
        Objects.requireNonNull(kafka, "kafka");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(groupId, "groupId");
        KafkaSourceBuilder<byte[]> builder = KafkaSource.<byte[]>builder()
                .setBootstrapServers(kafka.getBootstrapServers())
                .setTopics(topic)
                .setGroupId(groupId)
                // 无已提交位移时从最早开始：首次上线不丢历史数据
                .setStartingOffsets(OffsetsInitializer.committedOffsets(
                        org.apache.flink.connector.kafka.source.enumerator.initializer
                                .OffsetsInitializer.OffsetResetStrategy.EARLIEST))
                .setDeserializer(KafkaRecordDeserializationSchema.valueOnly(new ByteArraySchema()))
                .setProperty("commit.offsets.on.checkpoint", "true")
                .setProperty("partition.discovery.interval.ms", "600000");
        Properties extra = kafka.toClientProperties("realtime");
        extra.stringPropertyNames().forEach(name -> builder.setProperty(name, extra.getProperty(name)));
        return builder.build();
    }

    /** 原始字节反序列化：不做解析，解析失败由下游算子处理并写入 DLQ。 */
    static final class ByteArraySchema implements DeserializationSchema<byte[]> {

        private static final long serialVersionUID = 1L;

        @Override
        public byte[] deserialize(byte[] message) {
            return message;
        }

        @Override
        public boolean isEndOfStream(byte[] nextElement) {
            return false;
        }

        @Override
        public TypeInformation<byte[]> getProducedType() {
            return TypeInformation.of(byte[].class);
        }

        @Override
        public void deserialize(byte[] message, Collector<byte[]> out) {
            out.collect(message);
        }
    }
}
