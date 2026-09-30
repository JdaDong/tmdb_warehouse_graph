package com.tmdbwh.ingestion.metrics;

import com.google.common.util.concurrent.AtomicDouble;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import io.prometheus.client.exporter.PushGateway;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 采集指标。
 *
 * <p>指标约定（Grafana 大盘与 Prometheus 告警规则均依赖这些名称，见
 * deploy/compose/conf/prometheus/rules/tmdbwh-alerts.yml）：
 *
 * <ul>
 *   <li>{@code tmdb_http_requests_total{status}}     TMDB 请求数（含重试）
 *   <li>{@code tmdb_rate_limited_total}              被限流次数
 *   <li>{@code tmdb_request_failure_total}           IO 失败次数
 *   <li>{@code tmdb_request_not_found_total}         404 次数（导出文件中已删除的 ID 属正常现象）
 *   <li>{@code tmdb_http_request_duration_seconds}   请求耗时分布
 *   <li>{@code tmdb_ingested_records_total{entity,result}} 采集记录数（ok / failed / skipped）
 *   <li>{@code tmdb_job_last_run_status}             最近一次运行状态（1 成功 / 0 失败）
 *   <li>{@code tmdb_job_last_success_timestamp_seconds} 最近一次成功时间
 * </ul>
 *
 * <p>批处理作业不常驻，因此通过 Pushgateway 推送：{@link #push(String, String, String)}。
 */
public final class IngestionMetrics implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionMetrics.class);

    /** 指标名前缀（保持与告警规则一致）。 */
    public static final String PREFIX = "tmdb.";

    private final MeterRegistry registry;
    private final boolean owned;
    private final Counter rateLimited;
    private final Counter failures;
    private final Counter notFound;
    private final Timer latency;
    /** 作业状态 gauge 的取值持有器（按 "指标名|job" 缓存，保证重复调用更新同一 gauge）。 */
    private final Map<String, AtomicDouble> gauges = new ConcurrentHashMap<>();

    /**
     * 创建独立的指标收集器。
     *
     * <p>内部使用 {@link PrometheusMeterRegistry}，但不启动内置 HTTP 端点（批处理作业不常驻）， 指标通过 {@link
     * #push} 推给 Pushgateway。
     */
    public static IngestionMetrics create() {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        return new IngestionMetrics(registry, true);
    }

    /**
     * 使用外部注册表。
     *
     * @param registry 外部注册表（不会被本类关闭）
     */
    public static IngestionMetrics of(MeterRegistry registry) {
        return new IngestionMetrics(registry, false);
    }

    /** 便捷方法：挂到 Micrometer 全局注册表。 */
    public static IngestionMetrics global() {
        return new IngestionMetrics(Metrics.globalRegistry, false);
    }

    private IngestionMetrics(MeterRegistry registry, boolean owned) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.owned = owned;
        this.rateLimited = Counter.builder(PREFIX + "rate_limited_total")
                .description("TMDB 限流（429）次数")
                .register(registry);
        this.failures = Counter.builder(PREFIX + "request_failure_total")
                .description("TMDB IO 失败次数")
                .register(registry);
        this.notFound = Counter.builder(PREFIX + "request_not_found_total")
                .description("TMDB 404 次数")
                .register(registry);
        this.latency = Timer.builder(PREFIX + "http_request_duration_seconds")
                .description("TMDB 请求耗时")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
    }

    public MeterRegistry registry() {
        return registry;
    }

    /** 记录一次请求。 */
    public void recordRequest(int status, long costMs) {
        registry.counter(PREFIX + "http_requests_total", "status", String.valueOf(status)).increment();
        latency.record(costMs, TimeUnit.MILLISECONDS);
    }

    /** 记录一次限流。 */
    public void recordRateLimited() {
        rateLimited.increment();
    }

    /** 记录一次 IO 失败。 */
    public void recordFailure() {
        failures.increment();
    }

    /** 记录一次 404。 */
    public void recordNotFound() {
        notFound.increment();
    }

    /** 记录采集结果。result 取 ok / failed / skipped。 */
    public void recordIngested(String entity, String result, long count) {
        registry.counter(PREFIX + "ingested_records_total", "entity", entity, "result", result)
                .increment(Math.max(0, count));
    }

    /**
     * 记录作业运行状态（供 BatchJobFailed / IngestionStale 告警使用）。
     *
     * <p>用 AtomicDouble 持有 gauge 值：Micrometer 的 gauge 只持有"取值函数"，直接传数字不会更新已注册的指标。
     */
    public void recordJobStatus(String job, boolean success) {
        AtomicDouble status = gauges.computeIfAbsent(PREFIX + "job_last_run_status|" + job,
                k -> registry.gauge(PREFIX + "job_last_run_status", Tags.of("job", job), new AtomicDouble(0)));
        status.set(success ? 1.0 : 0.0);
        if (success) {
            AtomicDouble stamp = gauges.computeIfAbsent(PREFIX + "job_last_success_timestamp_seconds|" + job,
                    k -> registry.gauge(PREFIX + "job_last_success_timestamp_seconds", Tags.of("job", job),
                            new AtomicDouble(0)));
            stamp.set(System.currentTimeMillis() / 1000.0);
        }
    }

    /** 已发送的 HTTP 请求总数（含重试，按状态码汇总）。 */
    public long requestCount() {
        return registry.find(PREFIX + "http_requests_total").counters().stream()
                .mapToLong(c -> (long) c.count())
                .sum();
    }

    /** 限流次数。 */
    public long rateLimitedCount() {
        return (long) rateLimited.count();
    }

    /** IO 失败次数。 */
    public long failureCount() {
        return (long) failures.count();
    }

    /** 404 次数。 */
    public long notFoundCount() {
        return (long) notFound.count();
    }

    /** 请求耗时（毫秒）。 */
    public Duration totalLatency() {
        return Duration.ofNanos((long) latency.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS));
    }

    /**
     * 推送指标到 Pushgateway（批处理作业专用）。
     *
     * @param gateway Pushgateway 地址，例如 http://pushgateway:9091；为空则跳过
     * @param job 作业名（Prometheus 的 job 标签）
     * @param groupingKey 分组键（同作业的多次运行合并为一组）
     * @return 是否实际推送
     */
    public boolean push(String gateway, String job, String groupingKey) {
        if (gateway == null || gateway.trim().isEmpty()) {
            LOG.debug("未配置 Pushgateway，跳过指标推送");
            return false;
        }
        if (!(registry instanceof PrometheusMeterRegistry)) {
            LOG.warn("当前注册表不是 PrometheusMeterRegistry，无法推送（class={}）", registry.getClass().getSimpleName());
            return false;
        }
        try {
            PushGateway pg = new PushGateway(gateway.trim());
            io.prometheus.client.CollectorRegistry promRegistry =
                    ((PrometheusMeterRegistry) registry).getPrometheusRegistry();
            pg.pushAdd(promRegistry, job, groupingKey == null ? Map.of() : Map.of("instance", groupingKey));
            LOG.info("指标已推送到 Pushgateway: {} job={}", gateway, job);
            return true;
        } catch (IOException e) {
            LOG.warn("指标推送失败（不影响作业结果）: {}", e.toString());
            return false;
        }
    }

    @Override
    public void close() {
        if (owned) {
            registry.close();
        }
    }
}
