package com.tmdbwh.ingestion.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.model.ChangeEvent;
import com.tmdbwh.common.model.ChangeItem;
import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.common.model.EventEnvelope;
import com.tmdbwh.common.model.EventType;
import com.tmdbwh.common.model.RawRecord;
import com.tmdbwh.common.storage.CheckpointStore;
import com.tmdbwh.common.storage.ObjectStore;
import com.tmdbwh.common.util.DateWindow;
import com.tmdbwh.common.util.Hashing;
import com.tmdbwh.common.util.LogContext;
import com.tmdbwh.common.util.TimeUtils;
import com.tmdbwh.ingestion.client.TmdbClient;
import com.tmdbwh.ingestion.metrics.IngestionMetrics;
import com.tmdbwh.ingestion.sink.KafkaEventProducer;
import com.tmdbwh.ingestion.sink.LakeWriter;
import com.tmdbwh.ingestion.sink.LakeWriterOptions;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 增量采集：轮询 TMDB changes 接口，拉取变更实体的最新详情并落湖 + 发事件。
 *
 * <p>水位线语义：状态保存"每个实体类型已采集到的日期"。每次运行从水位线开始，按不超过 14 天的窗口
 * （TMDB 限制）逐段推进到目标日期；<b>只有某个窗口全部处理成功才推进水位线</b>，失败时保留原水位线， 下次运行重放该窗口——配合幂等写入不会产生重复数据。
 *
 * <p>changes 接口只返回"发生过变更的 ID"，不含变更内容，因此这里会回查详情； 同一实体在同一窗口内多次变更只处理一次（按 ID 去重）。
 */
public class IncrementalChangesJob {

    private static final Logger LOG = LoggerFactory.getLogger(IncrementalChangesJob.class);

    /** TMDB changes 接口单次查询最多跨 14 天。 */
    public static final int MAX_WINDOW_DAYS = 14;
    /** changes 分页上限（防止异常数据导致无限翻页）。 */
    public static final int MAX_PAGES = 10;

    /** 作业选项。 */
    public static class Options {
        private List<EntityType> entityTypes = List.of(EntityType.MOVIE, EntityType.TV, EntityType.PERSON);
        private LocalDate endDate;
        private int defaultStartDays = 7;
        private boolean includeAdult = false;
        private boolean emitKafka = true;

        public Options entityTypes(List<EntityType> v) {
            this.entityTypes = Objects.requireNonNull(v, "entityTypes");
            return this;
        }

        /** 采集到的目标日期（含）；为空表示"今天"。 */
        public Options endDate(LocalDate v) {
            this.endDate = v;
            return this;
        }

        /** 首次运行（无水位线）时的回溯天数。 */
        public Options defaultStartDays(int v) {
            if (v < 0) {
                throw new IllegalArgumentException("defaultStartDays 不能为负");
            }
            this.defaultStartDays = v;
            return this;
        }

        /** 是否采集 adult 内容；默认跳过（与 ODS 层数据标准一致）。 */
        public Options includeAdult(boolean v) {
            this.includeAdult = v;
            return this;
        }

        public Options emitKafka(boolean v) {
            this.emitKafka = v;
            return this;
        }

        public List<EntityType> getEntityTypes() {
            return entityTypes;
        }

        public LocalDate getEndDate() {
            return endDate;
        }

        public int getDefaultStartDays() {
            return defaultStartDays;
        }

        public boolean isIncludeAdult() {
            return includeAdult;
        }

        public boolean isEmitKafka() {
            return emitKafka;
        }
    }

    private final AppConfig config;
    private final TmdbClient client;
    private final ObjectStore store;
    private final CheckpointStore<IncrementalState> checkpoints;
    private final KafkaEventProducer producer;
    private final IngestionMetrics metrics;
    private final Clock clock;

    public IncrementalChangesJob(AppConfig config, TmdbClient client, ObjectStore store,
            CheckpointStore<IncrementalState> checkpoints, KafkaEventProducer producer, IngestionMetrics metrics,
            Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
        this.store = Objects.requireNonNull(store, "store");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.producer = producer;
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 执行增量采集。 */
    public JobResult run(Options options) {
        Objects.requireNonNull(options, "options");
        LocalDate endDate = options.getEndDate() == null ? TimeUtils.today(clock, config.getBusinessZone())
                : options.getEndDate();
        IncrementalState state = checkpoints.load(IncrementalState.class).orElseGet(IncrementalState::new);

        JobResult result = new JobResult("incremental_changes");
        try (LogContext logCtx = LogContext.forJob("incremental_changes")
                .with(LogContext.DT, TimeUtils.formatDt(endDate))) {

            for (EntityType type : options.getEntityTypes()) {
                if (!type.supportsChanges()) {
                    LOG.info("{} 不支持 changes 接口，跳过", type);
                    continue;
                }
                processEntity(type, endDate, state, options, result);
            }

            checkpoints.save(state);
            result.detail("endDate=" + TimeUtils.formatDt(endDate) + " watermarks=" + state.getWatermarks());
            LOG.info("增量采集完成: {}", result.summary());
            return result;
        }
    }

    private void processEntity(EntityType type, LocalDate endDate, IncrementalState state, Options options,
            JobResult result) {
        LocalDate watermark = state.watermarkOf(type.getApiPath());
        if (watermark == null) {
            watermark = endDate.minusDays(options.getDefaultStartDays());
            LOG.info("{} 无水位线，从 {} 开始（回溯 {} 天）", type, watermark, options.getDefaultStartDays());
        }
        if (watermark.isAfter(endDate)) {
            LOG.info("{} 水位线 {} 已超过目标日期 {}，跳过", type, watermark, endDate);
            return;
        }

        for (DateWindow window : TimeUtils.splitWindows(watermark, endDate, MAX_WINDOW_DAYS)) {
            boolean ok = processWindow(type, window, options, result);
            if (ok) {
                // 只有整段窗口成功才推进水位线，失败则下次重放该窗口
                state.setWatermark(type.getApiPath(), window.getEnd().plusDays(1));
                checkpoints.save(state);
            } else {
                LOG.warn("{} 窗口 {} 处理失败，保留水位线待下次重放", type, window);
                break;
            }
        }
    }

    private boolean processWindow(EntityType type, DateWindow window, Options options, JobResult result) {
        String start = TimeUtils.formatDt(window.getStart());
        String end = TimeUtils.formatDt(window.getEnd());
        List<ChangeItem> items;
        try {
            items = client.getAllChanges(type, start, end, MAX_PAGES);
        } catch (RuntimeException e) {
            LOG.error("查询 changes 失败 {} {}: {}", type, window, e.toString());
            return false;
        }

        Set<Long> ids = new LinkedHashSet<>();
        for (ChangeItem item : items) {
            if (Boolean.TRUE.equals(item.getAdult()) && !options.isIncludeAdult()) {
                continue;
            }
            ids.add(item.getId());
        }
        LOG.info("{} {} 共 {} 条变更（去重后 {} 条）", type, window, items.size(), ids.size());
        if (ids.isEmpty()) {
            return true;
        }

        String runId = Hashing.deterministicId(type.getApiPath(), start, end).substring(0, 12);
        LakeWriterOptions writerOptions = LakeWriterOptions.builder("change_" + type.getApiPath(),
                window.getEnd(), runId).build();

        try (LakeWriter lakeWriter = new LakeWriter(store, writerOptions)) {
            for (long id : ids) {
                result.recordAttempted();
                try {
                    Optional<JsonNode> details = client.getDetails(type, id, true);
                    if (details.isEmpty()) {
                        result.recordNotFound();
                        metrics.recordIngested(type.getApiPath(), "skipped", 1);
                        continue;
                    }
                    lakeWriter.writeObject(RawRecord.of(type, id, TimeUtils.formatDt(window.getEnd()),
                            details.get(), clock.millis()));
                    if (options.isEmitKafka() && producer != null) {
                        emit(type, id, window, details.get(), result);
                    }
                    result.recordSuccess();
                    metrics.recordIngested(type.getApiPath(), "ok", 1);
                } catch (RuntimeException e) {
                    result.recordFailed();
                    metrics.recordIngested(type.getApiPath(), "failed", 1);
                    LOG.warn("处理变更失败 {}#{}: {}", type, id, e.toString());
                }
            }
            lakeWriter.flush();
            result.writtenKeys(lakeWriter.writtenKeys());
        }
        return true;
    }

    private void emit(EntityType type, long id, DateWindow window, JsonNode details, JobResult result) {
        ChangeEvent change = new ChangeEvent(type, id, null, TimeUtils.formatDt(window.getStart()),
                TimeUtils.formatDt(window.getEnd()), clock.millis());
        EventEnvelope changeEvent = EventEnvelope.create(EventType.ENTITY_CHANGED, type, id, clock.millis(), change,
                "ingestion.changes", null, clock);
        EventEnvelope snapshot = EventEnvelope.create(EventType.ENTITY_SNAPSHOT, type, id, clock.millis(), details,
                "ingestion.changes", changeEvent.getTraceId(), clock);
        List<EventEnvelope> batch = new ArrayList<>(2);
        batch.add(changeEvent);
        batch.add(snapshot);
        // 只统计"实际投递成功"的条数，broker 失败时不应计入
        result.recordKafkaSent(producer.sendBatch(config.getKafka().getEntityChangeTopic(), batch));
    }
}
