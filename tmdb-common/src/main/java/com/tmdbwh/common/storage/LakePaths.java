package com.tmdbwh.common.storage;

import com.tmdbwh.common.util.TimeUtils;
import java.time.LocalDate;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 数据湖对象键（key）规范，所有模块读写湖中文件都必须通过本类生成路径，杜绝硬编码。
 *
 * <pre>
 * s3://{bucket}/
 *   raw/{source}/dt=yyyy-MM-dd/part-{runId}-{seq}.ndjson.gz   原始区：采集结果原样落地（ODS 的输入）
 *   quarantine/{source}/dt=yyyy-MM-dd/...                     隔离区：解析失败 / 校验不通过的脏数据
 *   _state/{job}.json                                         采集状态：断点、水位线
 *   warehouse/                                                Iceberg 仓库根目录
 *   checkpoints/{job}/                                        Flink checkpoint
 * </pre>
 *
 * <p>{@code source} 为数据来源名，如 {@code movie}、{@code tv}、{@code person}、{@code change_movie}、{@code popularity}。
 */
public final class LakePaths {

    /** 原始区前缀。 */
    public static final String RAW = "raw";
    /** 隔离区前缀。 */
    public static final String QUARANTINE = "quarantine";
    /** 状态区前缀。 */
    public static final String STATE = "_state";
    /** Iceberg 仓库前缀。 */
    public static final String WAREHOUSE = "warehouse";
    /** Flink checkpoint 前缀。 */
    public static final String CHECKPOINTS = "checkpoints";
    /** 原始区文件后缀。 */
    public static final String NDJSON_GZ = ".ndjson.gz";

    private static final Pattern SEGMENT = Pattern.compile("^[a-z0-9][a-z0-9_\\-]*$");

    private LakePaths() {}

    /** 原始区分区目录：{@code raw/{source}/dt=yyyy-MM-dd/}。 */
    public static String rawPartition(String source, LocalDate dt) {
        return RAW + "/" + segment(source) + "/dt=" + TimeUtils.formatDt(dt) + "/";
    }

    /**
     * 原始区数据文件。
     *
     * @param runId 本次运行 ID（保证不同批次文件名不冲突）
     * @param seq 同一批次内的滚动序号
     */
    public static String rawFile(String source, LocalDate dt, String runId, int seq) {
        return rawPartition(source, dt) + "part-" + segment(runId) + "-" + String.format("%05d", seq) + NDJSON_GZ;
    }

    /** 隔离区分区目录。 */
    public static String quarantinePartition(String source, LocalDate dt) {
        return QUARANTINE + "/" + segment(source) + "/dt=" + TimeUtils.formatDt(dt) + "/";
    }

    /** 采集作业状态文件：{@code _state/{job}.json}。 */
    public static String state(String job) {
        return STATE + "/" + segment(job) + ".json";
    }

    /** Flink 作业 checkpoint 目录。 */
    public static String checkpoints(String job) {
        return CHECKPOINTS + "/" + segment(job) + "/";
    }

    /** 转换为 Hadoop S3A URI，供 Spark / Flink 读取。 */
    public static String s3a(String bucket, String key) {
        return "s3a://" + Objects.requireNonNull(bucket, "bucket") + "/" + trimLeadingSlash(key);
    }

    /** 从原始区 key 中解析 dt，非分区路径返回 null。 */
    public static LocalDate parseDt(String key) {
        int idx = key.indexOf("/dt=");
        if (idx < 0 || key.length() < idx + 14) {
            return null;
        }
        try {
            return TimeUtils.parseDt(key.substring(idx + 4, idx + 14));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 校验路径段：仅允许小写字母、数字、下划线、中划线，防止目录穿越与非法字符。
     *
     * @throws IllegalArgumentException 非法路径段
     */
    public static String segment(String value) {
        if (value == null || !SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException("非法的路径段（仅允许 [a-z0-9_-]）: " + value);
        }
        return value;
    }

    private static String trimLeadingSlash(String key) {
        String k = Objects.requireNonNull(key, "key");
        while (k.startsWith("/")) {
            k = k.substring(1);
        }
        return k;
    }
}
