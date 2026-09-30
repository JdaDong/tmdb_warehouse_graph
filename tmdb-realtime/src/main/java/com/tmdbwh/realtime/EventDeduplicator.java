package com.tmdbwh.realtime;

import java.time.Duration;
import org.apache.flink.api.common.state.StateTtlConfig;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 实体级去重：同一实体内容未变化时不再向下游发送。
 *
 * <p>为什么在实时链路里做内容级去重：上游 changes 接口在"轮询窗口重叠"或"作业重放"时会重复投递同一实体，
 * 而且 TMDB 的变更标记（如 adult 字段翻转）会产生内容完全相同的两条事件。
 * 只按 eventId 去重无法覆盖"ID 不同但内容相同"的情况，因此这里按 {@code contentHash} 比较。
 *
 * <p>状态与 TTL：状态大小与"实体数"成正比，必须设置 TTL，
 * 否则长期运行会让 RocksDB 状态无限增长、checkpoint 越来越慢。
 * TTL 取"内容变化的最小有意义间隔"（默认 7 天）：超过该时间后即使内容相同也会重新下发，
 * 这让下游能周期性看到"心跳"，便于监控判断链路是否还活着。
 */
public class EventDeduplicator extends KeyedProcessFunction<String, com.tmdbwh.common.model.EventEnvelope,
        com.tmdbwh.common.model.EventEnvelope> {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(EventDeduplicator.class);

    /** 丢弃（重复）事件的侧输出标签。 */
    public static final org.apache.flink.util.OutputTag<com.tmdbwh.common.model.EventEnvelope> DUPLICATE_TAG =
            new org.apache.flink.util.OutputTag<com.tmdbwh.common.model.EventEnvelope>("duplicate-event") {};

    private final long stateTtlMillis;
    private transient ValueState<String> lastContentHash;

    public EventDeduplicator(Duration stateTtl) {
        if (stateTtl == null || stateTtl.isZero() || stateTtl.isNegative()) {
            throw new IllegalArgumentException("状态 TTL 必须为正");
        }
        this.stateTtlMillis = stateTtl.toMillis();
    }

    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<String> descriptor =
                new ValueStateDescriptor<>("last-content-hash", String.class);
        descriptor.enableTimeToLive(StateTtlConfig.newBuilder(Time.milliseconds(stateTtlMillis))
                .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
                // 过期状态对读取不可见：避免"已过期但仍被当作有效"导致的误判丢弃
                .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
                .build());
        lastContentHash = getRuntimeContext().getState(descriptor);
    }

    @Override
    public void processElement(com.tmdbwh.common.model.EventEnvelope event, Context ctx,
            Collector<com.tmdbwh.common.model.EventEnvelope> out) throws Exception {
        String previous = lastContentHash.value();
        if (shouldEmit(previous, event.getContentHash())) {
            lastContentHash.update(event.getContentHash());
            out.collect(event);
        } else {
            // 重复事件不进主流，但保留到侧输出：便于统计重复率、排查上游是否异常重投
            ctx.output(DUPLICATE_TAG, event);
        }
    }

    /**
     * 判定是否下发。
     *
     * @param previousHash 该实体上一次已下发的内容哈希（无状态或已过期时为 null）
     * @param currentHash 本次事件的内容哈希
     * @return true 表示应下发
     */
    public static boolean shouldEmit(String previousHash, String currentHash) {
        if (currentHash == null || currentHash.isEmpty()) {
            // 没有内容哈希（旧版本上游）时无法比较，放行以免丢数据
            return true;
        }
        return previousHash == null || !previousHash.equals(currentHash);
    }

    /** 状态 TTL（毫秒），便于测试与监控展示。 */
    public long getStateTtlMillis() {
        return stateTtlMillis;
    }
}
