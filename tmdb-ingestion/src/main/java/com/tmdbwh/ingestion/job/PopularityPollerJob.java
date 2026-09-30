package com.tmdbwh.ingestion.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.common.model.EventEnvelope;
import com.tmdbwh.common.model.EventType;
import com.tmdbwh.common.model.PopularityEvent;
import com.tmdbwh.common.model.RawRecord;
import com.tmdbwh.common.storage.ObjectStore;
import com.tmdbwh.common.util.Hashing;
import com.tmdbwh.common.util.LogContext;
import com.tmdbwh.common.util.TimeUtils;
import com.tmdbwh.ingestion.client.TmdbClient;
import com.tmdbwh.ingestion.client.TmdbEndpoints;
import com.tmdbwh.ingestion.metrics.IngestionMetrics;
import com.tmdbwh.ingestion.sink.KafkaEventProducer;
import com.tmdbwh.ingestion.sink.LakeWriter;
import com.tmdbwh.ingestion.sink.LakeWriterOptions;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 热度轮询：拉取 trending / popular 榜单，产出热度观测事件。
 *
 * <p>实时链路的输入源：事件以 {@code observedAt} 为事件时间，下游 Flink 做窗口聚合与飙升检测。 榜单结果通常只有 1~2 页，这里按页惰性流式读取，避免一次性载入。
 *
 * <p>名次（{@code rank}）按榜单返回顺序从 1 开始编号，跨页连续——它是"榜单名次"类分析的基础字段。
 */
public class PopularityPollerJob {

    private static final Logger LOG = LoggerFactory.getLogger(PopularityPollerJob.class);

    /** 榜单分页上限。 */
    public static final int MAX_PAGES = 5;

    /** 作业选项。 */
    public static class Options {
        private List<EntityType> entityTypes = List.of(EntityType.MOVIE, EntityType.TV, EntityType.PERSON);
        private List<String> windows = List.of("day", "week");
        private boolean includePopular = true;
        private boolean emitKafka = true;
        private boolean writeLake = true;

        public Options entityTypes(List<EntityType> v) {
            this.entityTypes = Objects.requireNonNull(v, "entityTypes");
            return this;
        }

        /** trending 时间窗：day / week。 */
        public Options windows(List<String> v) {
            this.windows = Objects.requireNonNull(v, "windows");
            return this;
        }

        /** 是否额外拉取 /{type}/popular 榜单。 */
        public Options includePopular(boolean v) {
            this.includePopular = v;
            return this;
        }

        public Options emitKafka(boolean v) {
            this.emitKafka = v;
            return this;
        }

        /** 是否落湖（热度数据也可只走实时链路）。 */
        public Options writeLake(boolean v) {
            this.writeLake = v;
            return this;
        }

        public List<EntityType> getEntityTypes() {
            return entityTypes;
        }

        public List<String> getWindows() {
            return windows;
        }

        public boolean isIncludePopular() {
            return includePopular;
        }

        public boolean isEmitKafka() {
            return emitKafka;
        }

        public boolean isWriteLake() {
            return writeLake;
        }
    }

    private final AppConfig config;
    private final TmdbClient client;
    private final ObjectStore store;
    private final KafkaEventProducer producer;
    private final IngestionMetrics metrics;
    private final Clock clock;

    public PopularityPollerJob(AppConfig config, TmdbClient client, ObjectStore store, KafkaEventProducer producer,
            IngestionMetrics metrics, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
        this.store = store; // writeLake=false 时可为 null
        this.producer = producer;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 执行一次热度轮询。 */
    public JobResult run(Options options) {
        Objects.requireNonNull(options, "options");
        LocalDate dt = TimeUtils.today(clock, config.getBusinessZone());
        long observedAt = clock.millis();
        JobResult result = new JobResult("popularity_poll");

        try (LogContext logCtx = LogContext.forJob("popularity_poll")
                .with(LogContext.DT, TimeUtils.formatDt(dt))) {

            Map<String, String> lists = buildLists(options);
            for (Map.Entry<String, String> entry : lists.entrySet()) {
                String listName = entry.getKey();
                EntityType type = EntityType.fromValue(entry.getValue());
                String path = listName.startsWith("trending_") && listName.contains("_")
                        ? TmdbEndpoints.trending(type, listName.substring(listName.lastIndexOf('_') + 1))
                        : TmdbEndpoints.popular(type);
                pollList(type, path, listName, dt, observedAt, options, result);
            }

            result.detail("lists=" + lists.size());
            LOG.info("热度轮询完成: {}", result.summary());
            return result;
        }
    }

    /** 构造"榜单名 → 实体类型"的映射，保证顺序稳定。 */
    private Map<String, String> buildLists(Options options) {
        Map<String, String> lists = new LinkedHashMap<>();
        for (EntityType type : options.getEntityTypes()) {
            for (String window : options.getWindows()) {
                lists.put("trending_" + type.getApiPath() + "_" + window, type.getApiPath());
            }
            if (options.isIncludePopular()) {
                lists.put("popular_" + type.getApiPath(), type.getApiPath());
            }
        }
        return lists;
    }

    private void pollList(EntityType type, String path, String listName, LocalDate dt, long observedAt,
            Options options, JobResult result) {
        String runId = Hashing.deterministicId("popularity", listName, dt).substring(0, 12);
        LakeWriter lakeWriter = options.isWriteLake() && store != null
                ? new LakeWriter(store, LakeWriterOptions.builder("popularity", dt, runId).build())
                : null;
        int rank = 0;
        try {
            List<EventEnvelope> batch = new ArrayList<>();
            try (java.util.stream.Stream<JsonNode> stream = client.streamList(path, MAX_PAGES)) {
                Iterable<JsonNode> iterable = stream::iterator;
                for (JsonNode node : iterable) {
                    result.recordAttempted();
                    rank++;
                    try {
                        PopularityEvent event = toEvent(type, node, rank, listName, observedAt);
                        if (event == null) {
                            result.recordNotFound();
                            continue;
                        }
                        if (lakeWriter != null) {
                            lakeWriter.writeObject(RawRecord.of(type, event.getEntityId(),
                                    TimeUtils.formatDt(dt), node, observedAt));
                        }
                        if (options.isEmitKafka() && producer != null) {
                            batch.add(EventEnvelope.create(EventType.POPULARITY_OBSERVED, type,
                                    event.getEntityId(), observedAt, event, "ingestion.popularity", null, clock));
                        }
                        result.recordSuccess();
                        metrics.recordIngested(type.getApiPath(), "ok", 1);
                    } catch (RuntimeException e) {
                        result.recordFailed();
                        LOG.warn("解析榜单条目失败 list={} rank={}: {}", listName, rank, e.toString());
                    }
                }
            }
            if (!batch.isEmpty() && producer != null) {
                producer.sendBatch(config.getKafka().getPopularityTopic(), batch);
                result.recordKafkaSent(batch.size());
            }
            if (lakeWriter != null) {
                lakeWriter.flush();
                result.writtenKeys(lakeWriter.writtenKeys());
            }
            LOG.info("榜单 {} 采集完成: {} 条", listName, rank);
        } finally {
            if (lakeWriter != null) {
                lakeWriter.close();
            }
        }
    }

    /** 把榜单条目转换为热度事件；缺少 ID 时返回 null。 */
    static PopularityEvent toEvent(EntityType type, JsonNode node, int rank, String listName, long observedAt) {
        JsonNode idNode = node.get("id");
        if (idNode == null || !idNode.isNumber()) {
            return null;
        }
        PopularityEvent event = new PopularityEvent();
        event.setEntityType(type);
        event.setEntityId(idNode.asLong());
        event.setRank(rank);
        event.setListName(listName);
        event.setObservedAt(observedAt);
        JsonNode popularity = node.get("popularity");
        event.setPopularity(popularity == null ? 0.0 : popularity.asDouble());
        JsonNode voteAverage = node.get("vote_average");
        if (voteAverage != null && voteAverage.isNumber()) {
            event.setVoteAverage(voteAverage.asDouble());
        }
        JsonNode voteCount = node.get("vote_count");
        if (voteCount != null && voteCount.isNumber()) {
            event.setVoteCount(voteCount.asInt());
        }
        JsonNode title = node.get("title");
        JsonNode name = node.get("name");
        if (title != null) {
            event.setTitle(title.asText());
        } else if (name != null) {
            event.setTitle(name.asText());
        }
        return event;
    }
}
