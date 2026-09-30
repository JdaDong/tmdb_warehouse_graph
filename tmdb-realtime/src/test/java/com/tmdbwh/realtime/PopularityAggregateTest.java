package com.tmdbwh.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.json.JsonUtils;
import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.common.model.EventEnvelope;
import com.tmdbwh.common.model.EventType;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** 窗口聚合：增量累加与合并。 */
class PopularityAggregateTest {

    private static final Clock CLOCK = Clock.fixed(java.time.Instant.parse("2026-09-30T10:00:00Z"), ZoneOffset.UTC);

    private final PopularityAggregate function = new PopularityAggregate();

    private static EventEnvelope event(double popularity, double voteAverage, long voteCount, String title) {
        JsonNode payload = JsonUtils.readTree("{\"popularity\":" + popularity
                + ",\"vote_average\":" + voteAverage + ",\"vote_count\":" + voteCount
                + ",\"title\":\"" + title + "\",\"list_name\":\"trending\"}");
        return EventEnvelope.create(EventType.POPULARITY_OBSERVED, EntityType.MOVIE, 1L, CLOCK.millis(), payload,
                "test", null, CLOCK);
    }

    @Test
    void accumulatesSumMaxAndAverages() {
        PopularityAggregate.Accumulator acc = function.createAccumulator();
        acc = function.add(event(10.0, 8.0, 100, "A"), acc);
        acc = function.add(event(20.0, 9.0, 200, "A"), acc);

        assertThat(acc.getCount()).isEqualTo(2);
        assertThat(acc.getAvgPopularity()).isEqualTo(15.0);
        assertThat(acc.getMaxPopularity()).isEqualTo(20.0);
        assertThat(acc.getAvgVoteAverage()).isEqualTo(8.5);
        // 评分人数取窗口内最大值：人数只增不减，取平均没有意义
        assertThat(acc.getVoteCount()).isEqualTo(200L);
        assertThat(acc.getTitle()).isEqualTo("A");
        assertThat(acc.getListName()).isEqualTo("trending");
    }

    @Test
    void mergeCombinesTwoAccumulators() {
        PopularityAggregate.Accumulator first = function.add(event(10.0, 8.0, 100, "A"),
                function.createAccumulator());
        PopularityAggregate.Accumulator second = function.add(event(30.0, 7.0, 300, "A"),
                function.createAccumulator());

        PopularityAggregate.Accumulator merged = function.merge(first, second);

        assertThat(merged.getCount()).isEqualTo(2);
        assertThat(merged.getAvgPopularity()).isEqualTo(20.0);
        assertThat(merged.getMaxPopularity()).isEqualTo(30.0);
        assertThat(merged.getVoteCount()).isEqualTo(300L);
    }

    @Test
    void emptyAccumulatorReturnsZeroNotNaN() {
        PopularityAggregate.Accumulator acc = function.createAccumulator();

        // NaN 写进 ClickHouse 会变成 0 或报错，这里必须显式兜底为 0
        assertThat(acc.getAvgPopularity()).isEqualTo(0.0);
        assertThat(acc.getMaxPopularity()).isEqualTo(0.0);
        assertThat(acc.getAvgVoteAverage()).isEqualTo(0.0);
    }

    @Test
    void eventsWithoutPopularityAreCountedButDoNotAffectAverages() {
        JsonNode payload = JsonUtils.readTree("{\"title\":\"A\"}");
        EventEnvelope event = EventEnvelope.create(EventType.POPULARITY_OBSERVED, EntityType.MOVIE, 1L,
                CLOCK.millis(), payload, "test", null, CLOCK);

        PopularityAggregate.Accumulator acc = function.add(event, function.createAccumulator());

        assertThat(acc.getCount()).isEqualTo(1);
        assertThat(acc.getAvgPopularity()).isEqualTo(0.0);
    }

    @Test
    void nonEmptyTitleWinsOverEmptyOne() {
        PopularityAggregate.Accumulator acc = function.add(event(1.0, 1.0, 1, "A"), function.createAccumulator());
        JsonNode empty = JsonUtils.readTree("{\"popularity\":2.0}");
        acc = function.add(EventEnvelope.create(EventType.POPULARITY_OBSERVED, EntityType.MOVIE, 1L,
                CLOCK.millis(), empty, "test", null, CLOCK), acc);

        assertThat(acc.getTitle()).isEqualTo("A");
    }

    @Test
    void averageHelperHandlesZeroCount() {
        assertThat(PopularityAggregate.avg(10.0, 0)).isEqualTo(0.0);
        assertThat(PopularityAggregate.avg(10.0, 4)).isEqualTo(2.5);
    }
}
