package com.tmdbwh.realtime;

import com.tmdbwh.common.model.EventEnvelope;
import org.apache.flink.api.common.functions.AggregateFunction;

/**
 * 窗口内热度聚合：样本数、均值、峰值、评分均值。
 *
 * <p>用 {@link AggregateFunction} 而不是 {@code ProcessWindowFunction} 全量缓存：
 * 后者会把窗口内所有事件攒在状态里，热度事件频率高时状态会非常大；
 * 增量聚合只保存累加器，内存占用与窗口内事件数无关。
 *
 * <p>累加器需要可序列化（Flink 会随 checkpoint 持久化）。
 */
public class PopularityAggregate implements AggregateFunction<EventEnvelope, PopularityAggregate.Accumulator,
        PopularityAggregate.Accumulator> {

    private static final long serialVersionUID = 1L;

    @Override
    public Accumulator createAccumulator() {
        return new Accumulator();
    }

    @Override
    public Accumulator add(EventEnvelope event, Accumulator acc) {
        Double popularity = EventCodec.popularityOf(event).orElse(null);
        if (popularity != null) {
            acc.popularitySum += popularity;
            acc.popularityMax = Math.max(acc.popularityMax, popularity);
        }
        Double voteAverage = EventCodec.voteAverageOf(event).orElse(null);
        if (voteAverage != null) {
            acc.voteAverageSum += voteAverage;
            acc.voteAverageSamples++;
        }
        Long voteCount = EventCodec.voteCountOf(event).orElse(null);
        if (voteCount != null) {
            acc.voteCount = Math.max(acc.voteCount, voteCount);
        }
        // 标题取窗口内最后一条非空值：榜单轮询时标题通常不变，取非空可避免空串覆盖
        String title = EventCodec.titleOf(event);
        if (!title.isEmpty()) {
            acc.title = title;
        }
        String listName = EventCodec.listNameOf(event);
        if (!listName.isEmpty()) {
            acc.listName = listName;
        }
        acc.count++;
        return acc;
    }

    @Override
    public Accumulator merge(Accumulator a, Accumulator b) {
        a.count += b.count;
        a.popularitySum += b.popularitySum;
        a.popularityMax = Math.max(a.popularityMax, b.popularityMax);
        a.voteAverageSum += b.voteAverageSum;
        a.voteAverageSamples += b.voteAverageSamples;
        a.voteCount = Math.max(a.voteCount, b.voteCount);
        if (a.title.isEmpty()) {
            a.title = b.title;
        }
        if (a.listName.isEmpty()) {
            a.listName = b.listName;
        }
        return a;
    }

    @Override
    public Accumulator getResult(Accumulator acc) {
        return acc;
    }

    /** 均值：样本为 0 时返回 0，避免 NaN 传播到 ClickHouse。 */
    public static double avg(double sum, long count) {
        return count == 0 ? 0.0 : sum / count;
    }

    /** 窗口聚合累加器。 */
    public static final class Accumulator {

        private long count;
        private double popularitySum;
        private double popularityMax = Double.NEGATIVE_INFINITY;
        private double voteAverageSum;
        private long voteAverageSamples;
        private long voteCount;
        private String title = "";
        private String listName = "";

        public long getCount() {
            return count;
        }

        public double getAvgPopularity() {
            return avg(popularitySum, count);
        }

        public double getMaxPopularity() {
            return count == 0 ? 0.0 : popularityMax;
        }

        public double getAvgVoteAverage() {
            return avg(voteAverageSum, voteAverageSamples);
        }

        public long getVoteCount() {
            return voteCount;
        }

        public String getTitle() {
            return title;
        }

        public String getListName() {
            return listName;
        }
    }
}
