package com.tmdbwh.ingestion.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.common.model.EventEnvelope;
import com.tmdbwh.common.model.EventType;
import com.tmdbwh.common.model.RawRecord;
import com.tmdbwh.common.storage.CheckpointStore;
import com.tmdbwh.common.storage.ObjectStore;
import com.tmdbwh.common.util.LogContext;
import com.tmdbwh.common.util.Hashing;
import com.tmdbwh.common.util.TimeUtils;
import com.tmdbwh.ingestion.client.TmdbClient;
import com.tmdbwh.ingestion.client.TmdbEndpoints;
import com.tmdbwh.ingestion.client.TmdbException;
import com.tmdbwh.ingestion.export.DailyIdExportReader;
import com.tmdbwh.ingestion.export.ExportIdRecord;
import com.tmdbwh.ingestion.metrics.IngestionMetrics;
import com.tmdbwh.ingestion.sink.KafkaEventProducer;
import com.tmdbwh.ingestion.sink.LakeWriter;
import com.tmdbwh.ingestion.sink.LakeWriterOptions;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 全量采集：下载每日 ID 导出文件 → 逐条拉取详情 → 写入数据湖原始区（可选推送 Kafka）。
 *
 * <p>要点：
 *
 * <ul>
 *   <li><b>断点续传</b>：进度存于 {@code _state/full_load_{entity}.json}，每 {@code checkpointEvery} 条推进一次。
 *       {@code resume=false} 时删除旧断点从头开始；导出日期与断点不一致时也从头开始（换了新一天的快照）；
 *   <li><b>限流</b>：并发线程数受 {@code concurrency} 限制，令牌桶由 {@link TmdbClient} 统一把关；
 *   <li><b>容错</b>：单个 ID 的 404（已删除）计入 notFound 并继续；其他异常计入 failed 并继续，
 *       最终失败占比超过阈值时作业整体失败（见 {@link JobResult#isFailure(double)}）；
 *   <li><b>抽样</b>：{@code maxIds > 0} 时只取前 N 个 ID，用于本地联调与冒烟测试。
 * </ul>
 */
public class FullLoadJob {

    private static final Logger LOG = LoggerFactory.getLogger(FullLoadJob.class);

    /** 作业选项。 */
    public static class Options {
        private EntityType entityType = EntityType.MOVIE;
        private int maxIds;
        private LocalDate exportDate;
        private boolean resume = true;
        private boolean emitKafka;
        private int concurrency = 8;
        private int checkpointEvery = 500;

        public Options entityType(EntityType v) {
            this.entityType = Objects.requireNonNull(v, "entityType");
            return this;
        }

        /** 最多采集的 ID 数（&lt;=0 表示不限制）。 */
        public Options maxIds(int v) {
            this.maxIds = v;
            return this;
        }

        /** 导出文件日期（决定分区 dt）。 */
        public Options exportDate(LocalDate v) {
            this.exportDate = Objects.requireNonNull(v, "exportDate");
            return this;
        }

        /** 是否从断点继续。false 表示删除断点重新全量。 */
        public Options resume(boolean v) {
            this.resume = v;
            return this;
        }

        /** 是否向 Kafka 推送 ENTITY_SNAPSHOT 事件。 */
        public Options emitKafka(boolean v) {
            this.emitKafka = v;
            return this;
        }

        public Options concurrency(int v) {
            if (v < 1) {
                throw new IllegalArgumentException("concurrency 必须 >= 1");
            }
            this.concurrency = v;
            return this;
        }

        /** 每处理多少条推进一次断点。 */
        public Options checkpointEvery(int v) {
            if (v < 1) {
                throw new IllegalArgumentException("checkpointEvery 必须 >= 1");
            }
            this.checkpointEvery = v;
            return this;
        }

        public EntityType getEntityType() {
            return entityType;
        }

        public int getMaxIds() {
            return maxIds;
        }

        public LocalDate getExportDate() {
            return exportDate;
        }

        public boolean isResume() {
            return resume;
        }

        public boolean isEmitKafka() {
            return emitKafka;
        }

        public int getConcurrency() {
            return concurrency;
        }

        public int getCheckpointEvery() {
            return checkpointEvery;
        }
    }

    private final AppConfig config;
    private final TmdbClient client;
    private final ObjectStore store;
    private final CheckpointStore<FullLoadState> checkpoints;
    private final KafkaEventProducer producer;
    private final IngestionMetrics metrics;
    private final Clock clock;

    public FullLoadJob(AppConfig config, TmdbClient client, ObjectStore store,
            CheckpointStore<FullLoadState> checkpoints, KafkaEventProducer producer, IngestionMetrics metrics,
            Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.client = Objects.requireNonNull(client, "client");
        this.store = Objects.requireNonNull(store, "store");
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.producer = producer; // 可为 null：不推送事件
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 执行全量采集。 */
    public JobResult run(Options options) {
        Objects.requireNonNull(options, "options");
        EntityType type = options.getEntityType();
        LocalDate dt = options.getExportDate() == null ? TimeUtils.today(clock, config.getBusinessZone())
                : options.getExportDate();
        String exportDate = TimeUtils.exportFileDate(dt);
        String jobName = "full_load_" + type.getApiPath();

        try (LogContext logCtx = LogContext.forJob(jobName)
                .with(LogContext.DT, TimeUtils.formatDt(dt))
                .with(LogContext.ENTITY, type.getApiPath())) {

            List<ExportIdRecord> ids = fetchExportIds(type, exportDate, options.getMaxIds());
            LOG.info("导出文件解析完成: {} 条 ID（limit={}）", ids.size(), options.getMaxIds());

            FullLoadState state = loadState(jobName, type, exportDate, options.isResume());
            int startOffset = Math.min(state.getOffset(), ids.size());
            if (startOffset > 0) {
                LOG.info("从断点继续: offset={}/{}", startOffset, ids.size());
            }

            JobResult result = new JobResult(jobName);
            String runId = state.getRunId() == null ? Hashing.deterministicId(jobName, exportDate, startOffset).substring(0, 12)
                    : state.getRunId();
            LakeWriterOptions writerOptions = LakeWriterOptions.builder(type.getApiPath(), dt, runId).build();

            try (LakeWriter lakeWriter = new LakeWriter(store, writerOptions)) {
                processIds(ids, startOffset, type, dt, state, options, lakeWriter, result);
                lakeWriter.flush();
                result.writtenKeys(lakeWriter.writtenKeys());
            }

            // 完成后清空断点，下次运行从 0 开始（全量快照语义）
            checkpoints.delete();
            result.detail("exportDate=" + exportDate);
            LOG.info("全量采集完成: {}", result.summary());
            return result;
        }
    }

    private void processIds(List<ExportIdRecord> ids, int startOffset, EntityType type, LocalDate dt,
            FullLoadState state, Options options, LakeWriter lakeWriter, JobResult result) {

        int concurrency = options.getConcurrency();
        // 有界队列 + CallerRuns：反压生产者，避免一次性把几十万个任务塞进内存
        ExecutorService pool = new ThreadPoolExecutor(concurrency, concurrency, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(concurrency * 4),
                new ThreadPoolExecutor.CallerRunsPolicy());

        AtomicLong ok = new AtomicLong();
        AtomicLong missing = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        int total = ids.size();

        try {
            for (int i = startOffset; i < total; i++) {
                ExportIdRecord record = ids.get(i);
                int index = i;
                pool.execute(() -> {
                    result.recordAttempted();
                    try {
                        Optional<JsonNode> details = client.getDetails(type, record.getId(), true);
                        if (details.isEmpty()) {
                            missing.incrementAndGet();
                            result.recordNotFound();
                            metrics.recordIngested(type.getApiPath(), "skipped", 1);
                            return;
                        }
                        writeRecord(lakeWriter, type, dt, record.getId(), details.get());
                        if (options.isEmitKafka() && producer != null) {
                            emitSnapshot(type, record.getId(), details.get());
                            result.recordKafkaSent(1);
                        }
                        ok.incrementAndGet();
                        result.recordSuccess();
                        metrics.recordIngested(type.getApiPath(), "ok", 1);
                    } catch (RuntimeException e) {
                        failed.incrementAndGet();
                        result.recordFailed();
                        metrics.recordIngested(type.getApiPath(), "failed", 1);
                        LOG.warn("采集失败 id={}: {}", record.getId(), e.toString());
                    } finally {
                        int processed = index + 1;
                        if (processed % options.getCheckpointEvery() == 0) {
                            saveCheckpoint(state, processed, ok.get(), missing.get(), failed.get());
                            LOG.info("进度 {}/{}", processed, total);
                        }
                    }
                });
            }
        } finally {
            pool.shutdown();
            try {
                if (!pool.awaitTermination(30, TimeUnit.MINUTES)) {
                    LOG.warn("采集线程池在超时时间内未结束，强制关闭");
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
            saveCheckpoint(state, total, ok.get(), missing.get(), failed.get());
        }
    }

    private void writeRecord(LakeWriter lakeWriter, EntityType type, LocalDate dt, long id, JsonNode details) {
        RawRecord record = RawRecord.of(type, id, TimeUtils.formatDt(dt), details, clock.millis());
        lakeWriter.writeObject(record);
    }

    private void emitSnapshot(EntityType type, long id, JsonNode details) {
        EventEnvelope envelope = EventEnvelope.create(EventType.ENTITY_SNAPSHOT, type, id, clock.millis(), details,
                "ingestion.full", null, clock);
        producer.send(config.getKafka().getEntityChangeTopic(), envelope);
    }

    private void saveCheckpoint(FullLoadState state, int offset, long ok, long missing, long failed) {
        state.advance(offset, ok, missing, failed);
        checkpoints.save(state);
    }

    private List<ExportIdRecord> fetchExportIds(EntityType type, String exportDate, int maxIds) {
        String url = config.getTmdb().getExportBaseUrl() + TmdbEndpoints.exportFileName(type, exportDate);
        HttpUrl parsed = HttpUrl.parse(url);
        if (parsed == null) {
            throw new TmdbException("非法的导出文件 URL: " + url, url);
        }
        byte[] bytes = client.downloadExport(parsed);
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            return DailyIdExportReader.read(in, maxIds);
        } catch (IOException e) {
            throw new com.tmdbwh.common.exception.StorageException("读取导出文件失败: " + url, e);
        }
    }

    private FullLoadState loadState(String jobName, EntityType type, String exportDate, boolean resume) {
        if (!resume) {
            checkpoints.delete();
            LOG.info("resume=false，已删除断点，从头开始");
            return newState(type, exportDate);
        }
        Optional<FullLoadState> existing = checkpoints.load(FullLoadState.class);
        if (existing.isEmpty()) {
            return newState(type, exportDate);
        }
        FullLoadState s = existing.get();
        if (!exportDate.equals(s.getExportDate())) {
            LOG.info("导出日期变化（{} -> {}），忽略旧断点从头开始", s.getExportDate(), exportDate);
            return newState(type, exportDate);
        }
        return s;
    }

    private FullLoadState newState(EntityType type, String exportDate) {
        FullLoadState s = new FullLoadState();
        s.setEntityType(type.getApiPath());
        s.setExportDate(exportDate);
        s.setOffset(0);
        s.setRunId(Hashing.deterministicId(type.getApiPath(), exportDate, System.currentTimeMillis()).substring(0, 12));
        return s;
    }
}
