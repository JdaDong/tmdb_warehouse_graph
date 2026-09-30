package com.tmdbwh.realtime.jobs;

import com.tmdbwh.realtime.SurgeDetector;
import com.tmdbwh.realtime.WindowedPopularity;
import java.time.Duration;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;

/**
 * 基于"上一窗口均值"的飙升检测。
 *
 * <p>状态里只保存每个实体的上一个窗口均值（一个 double），因此状态规模与实体数成正比、与事件量无关。
 *
 * <p>为什么不用"历史全窗口"：实时链路只需要发现"突然变热"，长周期趋势由离线 DWS 负责，
 * 在实时侧保存长历史会让状态与恢复时间都不可控。
 */
public class SurgeDetectorFunction extends KeyedProcessFunction<Long, WindowedPopularity, SurgeAlert> {

    private static final long serialVersionUID = 1L;

    private final SurgeDetector detector;
    private final String entityType;
    private final Duration stateTtl;

    private transient ValueState<Double> previousAvg;

    public SurgeDetectorFunction(SurgeDetector detector, String entityType, Duration stateTtl) {
        this.detector = java.util.Objects.requireNonNull(detector, "detector");
        this.entityType = entityType;
        this.stateTtl = stateTtl == null ? Duration.ofDays(7) : stateTtl;
    }

    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<Double> descriptor = new ValueStateDescriptor<>("previous-avg-popularity", Double.class);
        descriptor.enableTimeToLive(org.apache.flink.api.common.state.StateTtlConfig
                .newBuilder(org.apache.flink.api.common.time.Time.milliseconds(stateTtl.toMillis()))
                .setUpdateType(org.apache.flink.api.common.state.StateTtlConfig.UpdateType.OnCreateAndWrite)
                .build());
        previousAvg = getRuntimeContext().getState(descriptor);
    }

    @Override
    public void processElement(WindowedPopularity current, Context ctx, Collector<SurgeAlert> out)
            throws Exception {
        Double baseline = previousAvg.value();
        double baselineValue = baseline == null ? 0.0 : baseline;
        previousAvg.update(current.getAvgPopularity());
        if (detector.isSurge(current.getAvgPopularity(), baselineValue, current.getSampleCount())) {
            out.collect(new SurgeAlert(
                    SurgeDetector.alertId(entityType, current.getEntityId(), current.getWindowStart()),
                    entityType,
                    current.getEntityId(),
                    current.getTitle(),
                    current.getWindowStart(),
                    current.getWindowEnd(),
                    baselineValue,
                    current.getAvgPopularity(),
                    SurgeDetector.growthRatio(current.getAvgPopularity(), baselineValue),
                    ctx.timerService().currentProcessingTime()));
        }
    }

    /** 供测试复用的判定入口。 */
    public boolean wouldAlert(WindowedPopularity current, Double baseline) {
        return detector.isSurge(current.getAvgPopularity(), baseline == null ? 0.0 : baseline,
                current.getSampleCount());
    }
}
