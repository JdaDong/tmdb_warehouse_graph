package com.tmdbwh.common.config;

import com.tmdbwh.common.util.Masking;
import com.typesafe.config.Config;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** 对象存储配置（对应 {@code tmdbwh.s3}）。 */
public final class S3Config implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String endpoint;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final String bucket;
    private final boolean pathStyleAccess;
    private final String lakeUrl;

    private S3Config(Config c) {
        this.endpoint = ConfigSupport.trim(c.getString("endpoint"));
        this.region = ConfigSupport.trim(c.getString("region"));
        this.accessKey = ConfigSupport.trim(c.getString("access-key"));
        this.secretKey = ConfigSupport.trim(c.getString("secret-key"));
        this.bucket = ConfigSupport.trim(c.getString("bucket"));
        this.pathStyleAccess = c.getBoolean("path-style-access");
        this.lakeUrl = ConfigSupport.trim(c.getString("lake-url"));
    }

    /** 直接构造（测试 / 工具场景）。 */
    public S3Config(String endpoint, String region, String accessKey, String secretKey, String bucket,
            boolean pathStyleAccess) {
        this(endpoint, region, accessKey, secretKey, bucket, pathStyleAccess, "");
    }

    /** 直接构造（含湖仓地址）。 */
    public S3Config(String endpoint, String region, String accessKey, String secretKey, String bucket,
            boolean pathStyleAccess, String lakeUrl) {
        this.endpoint = endpoint;
        this.region = region;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.bucket = bucket;
        this.pathStyleAccess = pathStyleAccess;
        this.lakeUrl = lakeUrl == null ? "" : lakeUrl;
    }

    static S3Config from(Config c) {
        return new S3Config(c);
    }

    void validate(List<String> errors) {
        ConfigSupport.requireUri(errors, "tmdbwh.s3.endpoint", endpoint, new HashSet<>(Arrays.asList("http", "https")));
        ConfigSupport.requireNonBlank(errors, "tmdbwh.s3.region", region);
        ConfigSupport.requireNonBlank(errors, "tmdbwh.s3.bucket", bucket);
        if (!bucket.isEmpty() && !bucket.matches("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")) {
            errors.add("tmdbwh.s3.bucket 不符合 S3 桶命名规范: " + bucket);
        }
    }

    /** 是否显式配置了访问密钥；未配置时 SDK 走默认凭证链（IRSA / 实例角色）。 */
    public boolean hasStaticCredentials() {
        return !accessKey.isEmpty() && !secretKey.isEmpty();
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getRegion() {
        return region;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public String getBucket() {
        return bucket;
    }

    public boolean isPathStyleAccess() {
        return pathStyleAccess;
    }

    /**
     * 湖仓根地址（Spark 写出、ClickHouse 用 s3() 表函数读回时使用）。
     *
     * <p>未显式配置时由 endpoint + bucket + warehouse/ 推导；ClickHouse 在容器内访问 MinIO
     * 必须用容器网络地址，因此生产环境建议显式配置 {@code S3_LAKE_URL}。
     */
    public String getLakeUrl() {
        if (!lakeUrl.isEmpty()) {
            return lakeUrl.endsWith("/") ? lakeUrl : lakeUrl + "/";
        }
        String base = endpoint.endsWith("/") ? endpoint : endpoint + "/";
        return base + bucket + "/warehouse/";
    }

    /** endpoint 是否为 https（用于 Spark 的 S3A SSL 配置）。 */
    public boolean isSslEnabled() {
        return endpoint.toLowerCase(java.util.Locale.ROOT).startsWith("https://");
    }

    @Override
    public String toString() {
        return "S3Config{endpoint=" + endpoint + ", region=" + region + ", accessKey=" + Masking.maskSecret(accessKey)
                + ", secretKey=" + Masking.maskSecret(secretKey) + ", bucket=" + bucket
                + ", pathStyleAccess=" + pathStyleAccess + "}";
    }
}
