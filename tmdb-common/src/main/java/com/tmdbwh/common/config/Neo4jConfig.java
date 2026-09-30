package com.tmdbwh.common.config;

import com.tmdbwh.common.util.Masking;
import com.typesafe.config.Config;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Neo4j 配置（对应 {@code tmdbwh.neo4j}）。 */
public final class Neo4jConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String uri;
    private final String user;
    private final String password;
    private final String database;
    private final int batchSize;
    private final int maxConnectionPoolSize;

    private Neo4jConfig(Config c) {
        this.uri = ConfigSupport.trim(c.getString("uri"));
        this.user = ConfigSupport.trim(c.getString("user"));
        this.password = c.getString("password");
        this.database = ConfigSupport.trim(c.getString("database"));
        this.batchSize = c.getInt("batch-size");
        this.maxConnectionPoolSize = c.getInt("max-connection-pool-size");
    }

    static Neo4jConfig from(Config c) {
        return new Neo4jConfig(c);
    }

    void validate(List<String> errors) {
        ConfigSupport.requireUri(errors, "tmdbwh.neo4j.uri", uri,
                new HashSet<>(Arrays.asList("bolt", "bolt+s", "bolt+ssc", "neo4j", "neo4j+s", "neo4j+ssc")));
        ConfigSupport.requireNonBlank(errors, "tmdbwh.neo4j.user", user);
        ConfigSupport.requireNonBlank(errors, "tmdbwh.neo4j.database", database);
        ConfigSupport.requirePositive(errors, "tmdbwh.neo4j.batch-size", batchSize);
        ConfigSupport.requirePositive(errors, "tmdbwh.neo4j.max-connection-pool-size", maxConnectionPoolSize);
    }

    public String getUri() {
        return uri;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    public String getDatabase() {
        return database;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public int getMaxConnectionPoolSize() {
        return maxConnectionPoolSize;
    }

    @Override
    public String toString() {
        return "Neo4jConfig{uri=" + uri + ", user=" + user + ", password=" + Masking.maskSecret(password)
                + ", database=" + database + ", batchSize=" + batchSize + "}";
    }
}
