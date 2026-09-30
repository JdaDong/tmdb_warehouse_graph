package com.tmdbwh.common.config;

import com.tmdbwh.common.exception.InvalidConfigurationException;
import com.tmdbwh.common.util.Masking;
import com.typesafe.config.Config;
import java.io.Serializable;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** TMDB 数据源配置（对应 {@code tmdbwh.tmdb}）。 */
public final class TmdbConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String baseUrl;
    private final String exportBaseUrl;
    private final String bearerToken;
    private final String apiKey;
    private final String language;
    private final int rateLimitPerSecond;
    private final int maxConcurrency;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int maxRetries;
    private final boolean mockMode;

    private TmdbConfig(Config c) {
        this.baseUrl = stripTrailingSlash(c.getString("base-url"));
        this.exportBaseUrl = stripTrailingSlash(c.getString("export-base-url"));
        this.bearerToken = ConfigSupport.trim(c.getString("bearer-token"));
        this.apiKey = ConfigSupport.trim(c.getString("api-key"));
        this.language = c.getString("language");
        this.rateLimitPerSecond = c.getInt("rate-limit-per-second");
        this.maxConcurrency = c.getInt("max-concurrency");
        this.connectTimeout = c.getDuration("connect-timeout");
        this.readTimeout = c.getDuration("read-timeout");
        this.maxRetries = c.getInt("max-retries");
        this.mockMode = c.getBoolean("mock-mode");
    }

    static TmdbConfig from(Config c) {
        return new TmdbConfig(c);
    }

    void validate(List<String> errors) {
        ConfigSupport.requireUri(errors, "tmdbwh.tmdb.base-url", baseUrl, new HashSet<>(Arrays.asList("http", "https")));
        ConfigSupport.requireUri(errors, "tmdbwh.tmdb.export-base-url", exportBaseUrl,
                new HashSet<>(Arrays.asList("http", "https")));
        ConfigSupport.requirePositive(errors, "tmdbwh.tmdb.rate-limit-per-second", rateLimitPerSecond);
        ConfigSupport.requirePositive(errors, "tmdbwh.tmdb.max-concurrency", maxConcurrency);
        ConfigSupport.requirePositive(errors, "tmdbwh.tmdb.max-retries", maxRetries);
        ConfigSupport.requirePositive(errors, "tmdbwh.tmdb.read-timeout", readTimeout.toMillis());
    }

    /**
     * 采集任务启动前调用：非 Mock 模式下必须提供 bearer-token 或 api-key。
     *
     * @throws InvalidConfigurationException 缺少凭证
     */
    public void requireCredentials() {
        if (!mockMode && !hasCredentials()) {
            throw new InvalidConfigurationException(
                    "缺少 TMDB 凭证：请设置环境变量 TMDB_BEARER_TOKEN（推荐）或 TMDB_API_KEY；本地联调可设置 TMDB_MOCK_MODE=true");
        }
    }

    /** 是否配置了任一凭证。 */
    public boolean hasCredentials() {
        return !bearerToken.isEmpty() || !apiKey.isEmpty();
    }

    /** 优先使用 v4 Bearer Token。 */
    public boolean useBearerAuth() {
        return !bearerToken.isEmpty();
    }

    private static String stripTrailingSlash(String url) {
        String v = ConfigSupport.trim(url);
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getExportBaseUrl() {
        return exportBaseUrl;
    }

    public String getBearerToken() {
        return bearerToken;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getLanguage() {
        return language;
    }

    public int getRateLimitPerSecond() {
        return rateLimitPerSecond;
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public boolean isMockMode() {
        return mockMode;
    }

    @Override
    public String toString() {
        return "TmdbConfig{baseUrl=" + baseUrl + ", bearerToken=" + Masking.maskSecret(bearerToken)
                + ", apiKey=" + Masking.maskSecret(apiKey) + ", language=" + language
                + ", rateLimitPerSecond=" + rateLimitPerSecond + ", maxConcurrency=" + maxConcurrency
                + ", maxRetries=" + maxRetries + ", mockMode=" + mockMode + "}";
    }
}
