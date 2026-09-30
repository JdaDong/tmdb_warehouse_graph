package com.tmdbwh.realtime;

import java.io.Serializable;

/**
 * 热度飙升检测。
 *
 * <p>为什么需要"基线 + 阈值 + 样本量 + 热度下限"四重判断：只看涨幅会被两类噪声淹没——
 *
 * <ul>
 *   <li>冷门内容：热度从 0.5 涨到 2 就是 4 倍，但没有业务价值；
 *   <li>样本过少：窗口内只有 1 条数据时，均值本身不可信。
 * </ul>
 *
 * <p>判定逻辑抽成静态方法，便于单测覆盖边界条件（这是最容易写错的地方）。
 */
public final class SurgeDetector implements Serializable {

    private static final long serialVersionUID = 1L;

    private final double ratioThreshold;
    private final int minSamples;
    private final double minBaseline;

    public SurgeDetector(double ratioThreshold, int minSamples, double minBaseline) {
        if (ratioThreshold <= 1.0) {
            throw new IllegalArgumentException("涨幅阈值必须大于 1");
        }
        if (minSamples < 1) {
            throw new IllegalArgumentException("最少样本数必须 >= 1");
        }
        if (minBaseline < 0) {
            throw new IllegalArgumentException("基线热度下限不能为负");
        }
        this.ratioThreshold = ratioThreshold;
        this.minSamples = minSamples;
        this.minBaseline = minBaseline;
    }

    /**
     * 判定是否飙升。
     *
     * @param current 当前窗口均值热度
     * @param baseline 基线热度（通常为上一窗口均值或历史均值）
     * @param samples 当前窗口样本数
     * @return 是否触发告警
     */
    public boolean isSurge(double current, double baseline, long samples) {
        if (samples < minSamples) {
            return false;
        }
        if (current < minBaseline) {
            return false;
        }
        if (baseline <= 0) {
            // 没有基线（首次观测）时无从比较，不算飙升
            return false;
        }
        return current / baseline >= ratioThreshold;
    }

    /** 涨幅：基线为 0 或缺失时返回 0（而不是 Infinity，避免污染下游聚合）。 */
    public static double growthRatio(double current, double baseline) {
        if (baseline <= 0) {
            return 0.0;
        }
        return current / baseline;
    }

    /**
     * 告警 ID：实体 + 窗口起始。
     *
     * <p>固定格式保证重算 / 重放时 ID 稳定，写入 {@code rt.rt_surge_alert}（ReplacingMergeTree）
     * 后不会产生重复告警。
     */
    public static String alertId(String entityType, long entityId, long windowStart) {
        return entityType + ":" + entityId + ":" + windowStart;
    }

    public double getRatioThreshold() {
        return ratioThreshold;
    }

    public int getMinSamples() {
        return minSamples;
    }

    public double getMinBaseline() {
        return minBaseline;
    }
}
