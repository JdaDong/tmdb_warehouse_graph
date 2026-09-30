package com.tmdbwh.ingestion.cli;

import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.storage.CheckpointStore;
import com.tmdbwh.common.storage.ObjectStore;
import com.tmdbwh.common.storage.S3ObjectStore;
import com.tmdbwh.ingestion.client.TmdbClient;
import com.tmdbwh.ingestion.metrics.IngestionMetrics;
import com.tmdbwh.ingestion.sink.KafkaEventProducer;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 作业依赖组件的创建与释放（CLI 与各作业共享）。
 *
 * <p>集中在一处的好处：所有"资源生命周期"规则只写一遍——客户端关闭、生产者 flush 后关闭、 指标推送与注册表关闭，避免不同命令各自遗漏导致连接泄漏或指标丢失。
 */
public final class IngestionComponents {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionComponents.class);

    private IngestionComponents() {}

    /** 按配置创建对象存储（MinIO / S3）。 */
    public static ObjectStore objectStore(AppConfig config) {
        return S3ObjectStore.create(config.getS3());
    }

    /** 按配置创建 TMDB 客户端。调用方需先校验凭证（{@link
     * com.tmdbwh.common.config.TmdbConfig#requireCredentials()}）。 */
    public static TmdbClient tmdbClient(AppConfig config, IngestionMetrics metrics) {
        config.getTmdb().requireCredentials();
        return new TmdbClient(config.getTmdb(), metrics);
    }

    /** 按配置创建 Kafka 生产者。 */
    public static KafkaEventProducer kafkaProducer(AppConfig config, IngestionMetrics metrics) {
        return KafkaEventProducer.create(config.getKafka(), metrics);
    }

    /** 创建进度存储。 */
    public static <T> CheckpointStore<T> checkpoints(ObjectStore store, String jobName) {
        return new CheckpointStore<>(Objects.requireNonNull(store, "store"), jobName);
    }

    /**
     * 释放资源并记录作业状态。
     *
     * @param gateway Pushgateway 地址（为空则跳过推送）
     * @param job 作业名
     * @param groupingKey 分组键
     */
    public static void shutdown(AppConfig config, IngestionMetrics metrics, TmdbClient client,
            KafkaEventProducer producer, ObjectStore store, boolean success, String job, String groupingKey,
            String gateway) {
        if (client != null) {
            client.close();
        }
        if (producer != null) {
            producer.close();
        }
        if (store != null) {
            store.close();
        }
        if (metrics != null) {
            metrics.recordJobStatus(job, success);
            if (gateway != null && !gateway.isBlank()) {
                metrics.push(gateway, job, groupingKey);
            }
            metrics.close();
        }
        LOG.debug("组件已释放 job={} success={}", job, success);
    }
}
