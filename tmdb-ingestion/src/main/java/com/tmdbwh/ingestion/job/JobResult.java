package com.tmdbwh.ingestion.job;

import java.util.ArrayList;
import java.util.List;

/**
 * 作业执行结果：CLI 用它决定退出码（0 成功 / 1 失败）与摘要日志。
 *
 * <p>判定规则：请求总数大于 0 且失败占比超过阈值（默认 5%）时视为失败， 避免"个别 ID 404"误判为作业失败，同时能在大面积网络故障时及时暴露。
 */
public class JobResult {

    private final String jobName;
    private long total;
    private long success;
    private long notFound;
    private long failed;
    private long kafkaSent;
    private final List<String> writtenKeys = new ArrayList<>();
    private String detail;

    public JobResult(String jobName) {
        this.jobName = jobName;
    }

    /** 汇总"已尝试"的数量。 */
    public JobResult recordAttempted() {
        total++;
        return this;
    }

    public JobResult recordSuccess() {
        success++;
        return this;
    }

    public JobResult recordNotFound() {
        notFound++;
        return this;
    }

    public JobResult recordFailed() {
        failed++;
        return this;
    }

    public JobResult recordKafkaSent(long n) {
        kafkaSent += n;
        return this;
    }

    public JobResult writtenKeys(List<String> keys) {
        this.writtenKeys.addAll(keys);
        return this;
    }

    public JobResult detail(String text) {
        this.detail = text;
        return this;
    }

    public String getJobName() {
        return jobName;
    }

    public long getTotal() {
        return total;
    }

    public long getSuccess() {
        return success;
    }

    public long getNotFound() {
        return notFound;
    }

    public long getFailed() {
        return failed;
    }

    public long getKafkaSent() {
        return kafkaSent;
    }

    public List<String> getWrittenKeys() {
        return new ArrayList<>(writtenKeys);
    }

    public String getDetail() {
        return detail;
    }

    /** 失败占比（0~1）。 */
    public double failureRatio() {
        return total == 0 ? 0.0 : (double) failed / total;
    }

    /** 是否判定为失败。 */
    public boolean isFailure(double maxFailureRatio) {
        return total > 0 && failureRatio() > maxFailureRatio;
    }

    /** 摘要（单行，便于日志与告警）。 */
    public String summary() {
        return String.format("%s: total=%d success=%d notFound=%d failed=%d kafkaSent=%d files=%d%s",
                jobName, total, success, notFound, failed, kafkaSent, writtenKeys.size(),
                detail == null ? "" : " (" + detail + ")");
    }
}
