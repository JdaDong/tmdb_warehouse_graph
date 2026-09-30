package com.tmdbwh.ingestion.job;

import java.time.Instant;

/** 全量采集断点（每处理 N 条推进一次，失败重启后从断点继续）。 */
public class FullLoadState {

    private String entityType;
    private String exportDate;
    private int offset;
    private long processed;
    private long success;
    private long notFound;
    private long failed;
    private String runId;
    private String updatedAt;

    public FullLoadState() {}

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    /** 导出文件日期（MM_dd_yyyy）。 */
    public String getExportDate() {
        return exportDate;
    }

    public void setExportDate(String exportDate) {
        this.exportDate = exportDate;
    }

    /** 已处理的 ID 下标（下次从该位置继续）。 */
    public int getOffset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = offset;
    }

    public long getProcessed() {
        return processed;
    }

    public void setProcessed(long processed) {
        this.processed = processed;
    }

    public long getSuccess() {
        return success;
    }

    public void setSuccess(long success) {
        this.success = success;
    }

    public long getNotFound() {
        return notFound;
    }

    public void setNotFound(long notFound) {
        this.notFound = notFound;
    }

    public long getFailed() {
        return failed;
    }

    public void setFailed(long failed) {
        this.failed = failed;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    /** 推进断点并刷新时间戳。 */
    public void advance(int newOffset, long okDelta, long notFoundDelta, long failDelta) {
        this.offset = newOffset;
        this.processed += okDelta + notFoundDelta + failDelta;
        this.success += okDelta;
        this.notFound += notFoundDelta;
        this.failed += failDelta;
        this.updatedAt = Instant.now().toString();
    }
}
