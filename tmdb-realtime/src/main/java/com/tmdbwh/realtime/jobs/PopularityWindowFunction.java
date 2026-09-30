package com.tmdbwh.realtime.jobs;

import com.tmdbwh.realtime.PopularityAggregate;
import com.tmdbwh.realtime.WindowedPopularity;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;

/**
 * 把窗口累加器补全为带窗口边界的结果行。
 *
 * <p>单独抽出来是因为 {@code AggregateFunction} 拿不到窗口对象，而下游需要 window_start / window_end
 * 才能写入 {@code rt.rt_movie_popularity} 并生成稳定的告警 ID。
 */
public class PopularityWindowFunction extends org.apache.flink.streaming.api.functions.windowing
        .ProcessWindowFunction<PopularityAggregate.Accumulator, WindowedPopularity, Long, TimeWindow> {

    private static final long serialVersionUID = 1L;

    @Override
    public void process(Long key, Context context, Iterable<PopularityAggregate.Accumulator> elements,
            org.apache.flink.util.Collector<WindowedPopularity> out) {
        PopularityAggregate.Accumulator acc = elements.iterator().next();
        TimeWindow window = context.window();
        out.collect(new WindowedPopularity(
                key,
                window.getStart(),
                window.getEnd(),
                acc.getCount(),
                acc.getAvgPopularity(),
                acc.getMaxPopularity(),
                acc.getAvgVoteAverage(),
                acc.getVoteCount(),
                acc.getTitle(),
                acc.getListName()));
    }
}
