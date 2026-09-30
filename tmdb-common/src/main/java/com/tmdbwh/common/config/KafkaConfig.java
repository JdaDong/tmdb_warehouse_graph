package com.tmdbwh.common.config;

import com.tmdbwh.common.util.Masking;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigUtil;
import com.typesafe.config.ConfigValue;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/** Kafka 配置（对应 {@code tmdbwh.kafka}）。 */
public final class KafkaConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String bootstrapServers;
    private final String clientIdPrefix;
    private final String entityChangeTopic;
    private final String popularityTopic;
    private final String dlqTopic;
    private final String alertTopic;
    private final Map<String, String> properties;

    private KafkaConfig(Config c) {
        this.bootstrapServers = ConfigSupport.trim(c.getString("bootstrap-servers"));
        this.clientIdPrefix = c.getString("client-id-prefix");
        this.entityChangeTopic = c.getString("topics.entity-change");
        this.popularityTopic = c.getString("topics.popularity");
        this.dlqTopic = c.getString("topics.dlq");
        this.alertTopic = c.getString("topics.alert");
        Map<String, String> props = new TreeMap<>();
        // HOCON 中 security.protocol 既可写成带引号的扁平键，也会被解析为嵌套路径；统一还原为 Kafka 原生点分键名
        for (Map.Entry<String, ConfigValue> e : c.getConfig("properties").entrySet()) {
            props.put(String.join(".", ConfigUtil.splitPath(e.getKey())), String.valueOf(e.getValue().unwrapped()));
        }
        this.properties = Collections.unmodifiableMap(props);
    }

    static KafkaConfig from(Config c) {
        return new KafkaConfig(c);
    }

    void validate(List<String> errors) {
        ConfigSupport.requireNonBlank(errors, "tmdbwh.kafka.bootstrap-servers", bootstrapServers);
        ConfigSupport.requireNonBlank(errors, "tmdbwh.kafka.topics.entity-change", entityChangeTopic);
        ConfigSupport.requireNonBlank(errors, "tmdbwh.kafka.topics.popularity", popularityTopic);
        ConfigSupport.requireNonBlank(errors, "tmdbwh.kafka.topics.dlq", dlqTopic);
        ConfigSupport.requireNonBlank(errors, "tmdbwh.kafka.topics.alert", alertTopic);
    }

    /**
     * 生成 Kafka 客户端基础属性（bootstrap.servers + 透传属性），调用方再追加生产者 / 消费者专属配置。
     *
     * @param clientIdSuffix client.id 后缀，例如 {@code ingestion-changes}
     */
    public Properties toClientProperties(String clientIdSuffix) {
        Properties p = new Properties();
        p.putAll(properties);
        p.setProperty("bootstrap.servers", bootstrapServers);
        p.setProperty("client.id", clientIdPrefix + "-" + clientIdSuffix);
        return p;
    }

    public String getBootstrapServers() {
        return bootstrapServers;
    }

    public String getClientIdPrefix() {
        return clientIdPrefix;
    }

    public String getEntityChangeTopic() {
        return entityChangeTopic;
    }

    public String getPopularityTopic() {
        return popularityTopic;
    }

    public String getDlqTopic() {
        return dlqTopic;
    }

    public String getAlertTopic() {
        return alertTopic;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public String toString() {
        Map<String, String> masked = new TreeMap<>();
        properties.forEach((k, v) -> masked.put(k, Masking.isSensitiveKey(k) || k.contains("jaas") ? Masking.MASK : v));
        return "KafkaConfig{bootstrapServers=" + bootstrapServers + ", topics=[" + entityChangeTopic + ", "
                + popularityTopic + ", " + dlqTopic + ", " + alertTopic + "], properties=" + masked + "}";
    }
}
