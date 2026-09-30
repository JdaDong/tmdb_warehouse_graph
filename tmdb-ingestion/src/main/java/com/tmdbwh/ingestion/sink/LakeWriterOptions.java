package com.tmdbwh.ingestion.sink;

import java.time.LocalDate;
import java.util.Objects;

/** 原始区写入参数。 */
public final class LakeWriterOptions {

    /** 数据来源名（决定 raw/{source}/dt=... 路径），例如 movie / change_movie / popularity。 */
    private final String source;
    /** 业务分区日期。 */
    private final LocalDate dt;
    /** 本次运行 ID，保证不同批次文件名不冲突。 */
    private final String runId;
    /** 单文件滚动大小（字节）。 */
    private final long rolloverBytes;
    /** 单文件最大行数。 */
    private final int rolloverLines;

    private LakeWriterOptions(Builder b) {
        this.source = b.source;
        this.dt = b.dt;
        this.runId = b.runId;
        this.rolloverBytes = b.rolloverBytes;
        this.rolloverLines = b.rolloverLines;
    }

    public static Builder builder(String source, LocalDate dt, String runId) {
        return new Builder(source, dt, runId);
    }

    public String getSource() {
        return source;
    }

    public LocalDate getDt() {
        return dt;
    }

    public String getRunId() {
        return runId;
    }

    public long getRolloverBytes() {
        return rolloverBytes;
    }

    public int getRolloverLines() {
        return rolloverLines;
    }

    /** 构建器。 */
    public static final class Builder {
        private final String source;
        private final LocalDate dt;
        private final String runId;
        private long rolloverBytes = 64L * 1024 * 1024;
        private int rolloverLines = 200_000;

        private Builder(String source, LocalDate dt, String runId) {
            this.source = Objects.requireNonNull(source, "source");
            this.dt = Objects.requireNonNull(dt, "dt");
            this.runId = Objects.requireNonNull(runId, "runId");
        }

        /** 单文件滚动大小（字节）。 */
        public Builder rolloverBytes(long bytes) {
            if (bytes <= 0) {
                throw new IllegalArgumentException("rolloverBytes 必须为正数");
            }
            this.rolloverBytes = bytes;
            return this;
        }

        /** 单文件最大行数。 */
        public Builder rolloverLines(int lines) {
            if (lines <= 0) {
                throw new IllegalArgumentException("rolloverLines 必须为正数");
            }
            this.rolloverLines = lines;
            return this;
        }

        public LakeWriterOptions build() {
            return new LakeWriterOptions(this);
        }
    }
}
