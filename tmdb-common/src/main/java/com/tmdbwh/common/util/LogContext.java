package com.tmdbwh.common.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;

/**
 * 结构化日志上下文（基于 SLF4J MDC）。
 *
 * <p>平台统一日志字段：{@code job / layer / dt / traceId / entity}，JSON 日志编码器会自动把 MDC 输出为独立字段， 便于在日志平台按作业、分区检索。
 * 使用 try-with-resources 保证退出作用域后恢复原值，不污染线程池中的其他任务。
 *
 * <pre>{@code
 * try (LogContext ctx = LogContext.forJob("dwd_build").with(LogContext.DT, "2026-09-30")) {
 *     LOG.info("开始构建");
 * }
 * }</pre>
 */
public final class LogContext implements AutoCloseable {

    /** 作业名。 */
    public static final String JOB = "job";
    /** 数仓分层（ods/dwd/dws/ads/rt）。 */
    public static final String LAYER = "layer";
    /** 业务日期。 */
    public static final String DT = "dt";
    /** 链路追踪 ID。 */
    public static final String TRACE_ID = "traceId";
    /** 实体类型（movie/tv/person...）。 */
    public static final String ENTITY = "entity";

    /** 记录进入作用域前的旧值，null 表示原先不存在。 */
    private final Map<String, String> previous = new LinkedHashMap<>();

    private LogContext() {}

    /** 以作业名开启上下文，并自动生成 traceId（若当前线程尚无）。 */
    public static LogContext forJob(String job) {
        LogContext ctx = new LogContext().with(JOB, job);
        if (MDC.get(TRACE_ID) == null) {
            ctx.with(TRACE_ID, UUID.randomUUID().toString().replace("-", ""));
        }
        return ctx;
    }

    /** 创建空上下文。 */
    public static LogContext empty() {
        return new LogContext();
    }

    /**
     * 设置一个上下文字段；value 为 null 时移除该字段。
     *
     * @return this，便于链式调用
     */
    public LogContext with(String key, String value) {
        if (!previous.containsKey(key)) {
            previous.put(key, MDC.get(key));
        }
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
        return this;
    }

    /** 当前线程的 traceId，不存在时返回 null。 */
    public static String currentTraceId() {
        return MDC.get(TRACE_ID);
    }

    /** 恢复进入作用域前的 MDC 状态。 */
    @Override
    public void close() {
        for (Map.Entry<String, String> e : previous.entrySet()) {
            if (e.getValue() == null) {
                MDC.remove(e.getKey());
            } else {
                MDC.put(e.getKey(), e.getValue());
            }
        }
        previous.clear();
    }
}
