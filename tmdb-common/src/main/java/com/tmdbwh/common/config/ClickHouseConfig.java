package com.tmdbwh.common.config;

import com.tmdbwh.common.util.Masking;
import com.typesafe.config.Config;
import java.io.Serializable;
import java.time.Duration;
import java.util.List;

/** ClickHouse 配置（对应 {@code tmdbwh.clickhouse}）。 */
public final class ClickHouseConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String url;
    private final String user;
    private final String password;
    private final String cluster;
    private final int maxPoolSize;
    private final Duration connectTimeout;
    private final Duration socketTimeout;
    private final int batchSize;

    private ClickHouseConfig(Config c) {
        this.url = ConfigSupport.trim(c.getString("url"));
        this.user = ConfigSupport.trim(c.getString("user"));
        this.password = c.getString("password");
        this.cluster = ConfigSupport.trim(c.getString("cluster"));
        this.maxPoolSize = c.getInt("max-pool-size");
        this.connectTimeout = c.getDuration("connect-timeout");
        this.socketTimeout = c.getDuration("socket-timeout");
        this.batchSize = c.getInt("batch-size");
    }

    /** 直接构造（测试 / 工具场景）。 */
    public ClickHouseConfig(String url, String user, String password, String cluster, int maxPoolSize, int batchSize) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.cluster = cluster == null ? "" : cluster;
        this.maxPoolSize = maxPoolSize;
        this.connectTimeout = Duration.ofSeconds(10);
        this.socketTimeout = Duration.ofMinutes(5);
        this.batchSize = batchSize;
    }

    static ClickHouseConfig from(Config c) {
        return new ClickHouseConfig(c);
    }

    void validate(List<String> errors) {
        if (!url.startsWith("jdbc:clickhouse:") && !url.startsWith("jdbc:ch:")) {
            errors.add("tmdbwh.clickhouse.url 必须以 jdbc:clickhouse: 或 jdbc:ch: 开头，当前值: " + url);
        }
        ConfigSupport.requireNonBlank(errors, "tmdbwh.clickhouse.user", user);
        ConfigSupport.requirePositive(errors, "tmdbwh.clickhouse.max-pool-size", maxPoolSize);
        ConfigSupport.requirePositive(errors, "tmdbwh.clickhouse.batch-size", batchSize);
        if (!cluster.isEmpty() && !cluster.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
            errors.add("tmdbwh.clickhouse.cluster 名称非法: " + cluster);
        }
    }

    /** 是否为集群模式（DDL 需追加 ON CLUSTER）。 */
    public boolean isClustered() {
        return !cluster.isEmpty();
    }

    public String getUrl() {
        return url;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    public String getCluster() {
        return cluster;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getSocketTimeout() {
        return socketTimeout;
    }

    public int getBatchSize() {
        return batchSize;
    }

    @Override
    public String toString() {
        return "ClickHouseConfig{url=" + Masking.maskUrl(url) + ", user=" + user + ", password="
                + Masking.maskSecret(password) + ", cluster=" + cluster + ", maxPoolSize=" + maxPoolSize
                + ", batchSize=" + batchSize + "}";
    }
}
