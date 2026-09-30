package com.tmdbwh.ingestion.cli;

import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.common.storage.CheckpointStore;
import com.tmdbwh.common.storage.ObjectStore;
import com.tmdbwh.common.util.TimeUtils;
import com.tmdbwh.ingestion.client.TmdbClient;
import com.tmdbwh.ingestion.job.FullLoadJob;
import com.tmdbwh.ingestion.job.FullLoadState;
import com.tmdbwh.ingestion.job.IncrementalChangesJob;
import com.tmdbwh.ingestion.job.IncrementalState;
import com.tmdbwh.ingestion.job.JobResult;
import com.tmdbwh.ingestion.job.PopularityPollerJob;
import com.tmdbwh.ingestion.metrics.IngestionMetrics;
import com.tmdbwh.ingestion.sink.KafkaEventProducer;
import java.io.File;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * 采集命令行入口。
 *
 * <pre>
 *   ingestion full        --entity movie  --max-ids 200        # 全量（抽样 200 条）
 *   ingestion incremental --entity movie --entity tv           # 增量变更
 *   ingestion popularity  --entity movie --window day          # 热度轮询
 * </pre>
 *
 * <p>退出码：0 成功；1 作业失败（失败占比超阈值或异常）；2 参数 / 配置错误（picocli 默认）。
 */
@Command(name = "ingestion", mixinStandardHelpOptions = true, version = "ingestion 1.0",
        description = "TMDB 数据采集：全量 / 增量变更 / 热度轮询",
        subcommands = {IngestionCli.FullCommand.class, IngestionCli.IncrementalCommand.class,
                IngestionCli.PopularityCommand.class},
        sortOptions = false)
public class IngestionCli implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionCli.class);

    /** 默认失败占比阈值：超过则判定作业失败。 */
    public static final double DEFAULT_MAX_FAILURE_RATIO = 0.05;

    /** 供测试注入：覆盖配置加载。 */
    static AppConfig injectedConfig;
    /** 供测试注入：覆盖时钟。 */
    static Clock injectedClock;

    @Option(names = {"-c", "--config"}, description = "配置文件路径（HOCON，覆盖环境变量与默认值）")
    File configFile;

    @Option(names = "--pushgateway", description = "Prometheus Pushgateway 地址（批处理指标推送）")
    String pushgateway;

    @Option(names = "--max-failure-ratio", defaultValue = "0.05",
            description = "允许的请求失败占比，超过则作业失败（默认 ${DEFAULT-VALUE}）")
    double maxFailureRatio = DEFAULT_MAX_FAILURE_RATIO;

    private AppConfig config;
    private Clock clock;

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    /** 共享上下文：一次命令执行期间持有的全部资源。 */
    static final class Context {
        AppConfig config;
        Clock clock;
        IngestionMetrics metrics;
        ObjectStore store;
        TmdbClient client;
        KafkaEventProducer producer;
        double maxFailureRatio;
        String pushgateway;
        String jobName = "ingestion";
        String groupingKey = "default";
        boolean closed;

        void close(boolean success) {
            if (closed) {
                return;
            }
            closed = true;
            IngestionComponents.shutdown(config, metrics, client, producer, store, success, jobName, groupingKey,
                    pushgateway);
        }

        /** 按结果判定退出码（0 / 1）。 */
        int exitCode(JobResult result) {
            boolean ok = !result.isFailure(maxFailureRatio);
            LOG.info("作业结果 {} -> {}", result.summary(), ok ? "成功" : "失败");
            return ok ? 0 : 1;
        }
    }

    Context context() {
        if (config == null) {
            config = injectedConfig != null ? injectedConfig
                    : (configFile != null ? AppConfig.load(configFile) : AppConfig.load());
        }
        if (clock == null) {
            clock = injectedClock != null ? injectedClock : Clock.systemUTC();
        }
        Context ctx = new Context();
        ctx.config = config;
        ctx.clock = clock;
        ctx.maxFailureRatio = maxFailureRatio;
        ctx.pushgateway = pushgateway;
        ctx.metrics = IngestionMetrics.create();
        ctx.store = IngestionComponents.objectStore(config);
        ctx.client = IngestionComponents.tmdbClient(config, ctx.metrics);
        return ctx;
    }

    /** 解析实体类型列表（支持多次传入，逗号分隔）。 */
    static List<EntityType> parseEntities(String[] values) {
        List<EntityType> types = new ArrayList<>();
        if (values == null || values.length == 0) {
            types.add(EntityType.MOVIE);
            return types;
        }
        for (String value : values) {
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    types.add(EntityType.fromValue(trimmed));
                }
            }
        }
        return types;
    }

    static LocalDate parseDate(String value) {
        return value == null || value.isBlank() ? null : TimeUtils.parseDt(value);
    }

    // ============================== full ==============================
    @Command(name = "full", mixinStandardHelpOptions = true, description = "全量采集：每日 ID 导出文件 + 逐条详情，支持断点续传", sortOptions = false)
    static class FullCommand implements Callable<Integer> {

        @CommandLine.ParentCommand
        IngestionCli parent;

        @Option(names = {"-e", "--entity"}, description = "实体类型（movie / tv / person），可重复或逗号分隔")
        String[] entities;

        @Option(names = "--max-ids", defaultValue = "0", description = "最多采集的 ID 数，0 表示不限（默认 ${DEFAULT-VALUE}）")
        int maxIds;

        @Option(names = "--date", description = "导出文件与分区日期 yyyy-MM-dd，默认今天")
        String date;

        @Option(names = "--resume", negatable = true, defaultValue = "true",
                description = "是否从断点继续（--no-resume 表示重新全量，默认 ${DEFAULT-VALUE}）")
        boolean resume = true;

        @Option(names = "--concurrency", defaultValue = "8", description = "并发请求线程数（默认 ${DEFAULT-VALUE}）")
        int concurrency = 8;

        @Option(names = "--emit-kafka", negatable = true, defaultValue = "false",
                description = "是否推送 ENTITY_SNAPSHOT 事件到 Kafka（默认 ${DEFAULT-VALUE}）")
        boolean emitKafka;

        @Override
        public Integer call() {
            Context ctx = parent.context();
            ctx.jobName = "tmdb_ingestion_full";
            boolean success = false;
            try {
                LocalDate dt = parseDate(date);
                if (dt == null) {
                    dt = TimeUtils.today(ctx.clock, ctx.config.getBusinessZone());
                }
                ctx.groupingKey = dt.toString();
                if (emitKafka) {
                    ctx.producer = IngestionComponents.kafkaProducer(ctx.config, ctx.metrics);
                }
                JobResult result = new JobResult(ctx.jobName);
                for (EntityType type : parseEntities(entities)) {
                    CheckpointStore<FullLoadState> checkpoints =
                            IngestionComponents.checkpoints(ctx.store, "full_load_" + type.getApiPath());
                    FullLoadJob job = new FullLoadJob(ctx.config, ctx.client, ctx.store, checkpoints, ctx.producer,
                            ctx.metrics, ctx.clock);
                    JobResult one = job.run(new FullLoadJob.Options()
                            .entityType(type)
                            .maxIds(maxIds)
                            .exportDate(dt)
                            .resume(resume)
                            .emitKafka(emitKafka)
                            .concurrency(concurrency));
                    result.writtenKeys(one.getWrittenKeys());
                    merge(result, one);
                }
                success = !result.isFailure(ctx.maxFailureRatio);
                LOG.info("全量采集结果 {}", result.summary());
                return success ? 0 : 1;
            } finally {
                ctx.close(success);
            }
        }
    }

    // ============================== incremental ==============================
    @Command(name = "incremental", mixinStandardHelpOptions = true, description = "增量采集：changes 接口 + 水位线推进", sortOptions = false)
    static class IncrementalCommand implements Callable<Integer> {

        @CommandLine.ParentCommand
        IngestionCli parent;

        @Option(names = {"-e", "--entity"}, description = "实体类型（movie / tv / person），默认三者全部")
        String[] entities;

        @Option(names = "--end-date", description = "采集到的日期 yyyy-MM-dd，默认今天")
        String endDate;

        @Option(names = "--default-start-days", defaultValue = "7",
                description = "首次运行的回溯天数（默认 ${DEFAULT-VALUE}）")
        int defaultStartDays = 7;

        @Option(names = "--include-adult", description = "采集 adult 内容（默认跳过）")
        boolean includeAdult;

        @Option(names = "--emit-kafka", negatable = true, defaultValue = "true",
                description = "是否推送事件到 Kafka（默认 ${DEFAULT-VALUE}）")
        boolean emitKafka = true;

        @Override
        public Integer call() {
            Context ctx = parent.context();
            ctx.jobName = "tmdb_ingestion_incremental";
            boolean success = false;
            try {
                LocalDate end = parseDate(endDate);
                if (end == null) {
                    end = TimeUtils.today(ctx.clock, ctx.config.getBusinessZone());
                }
                ctx.groupingKey = end.toString();
                if (emitKafka) {
                    ctx.producer = IngestionComponents.kafkaProducer(ctx.config, ctx.metrics);
                }
                CheckpointStore<IncrementalState> checkpoints =
                        IngestionComponents.checkpoints(ctx.store, "incremental_changes");
                IncrementalChangesJob job = new IncrementalChangesJob(ctx.config, ctx.client, ctx.store, checkpoints,
                        ctx.producer, ctx.metrics, ctx.clock);
                JobResult result = job.run(new IncrementalChangesJob.Options()
                        .entityTypes(parseEntities(entities))
                        .endDate(end)
                        .defaultStartDays(defaultStartDays)
                        .includeAdult(includeAdult)
                        .emitKafka(emitKafka));
                success = !result.isFailure(ctx.maxFailureRatio);
                return success ? 0 : 1;
            } finally {
                ctx.close(success);
            }
        }
    }

    // ============================== popularity ==============================
    @Command(name = "popularity", mixinStandardHelpOptions = true, description = "热度轮询：trending / popular 榜单", sortOptions = false)
    static class PopularityCommand implements Callable<Integer> {

        @CommandLine.ParentCommand
        IngestionCli parent;

        @Option(names = {"-e", "--entity"}, description = "实体类型（movie / tv / person）")
        String[] entities;

        @Option(names = {"-w", "--window"}, description = "trending 时间窗（day / week），可重复")
        String[] windows;

        @Option(names = "--include-popular", negatable = true, defaultValue = "true",
                description = "是否同时拉取 popular 榜单（默认 ${DEFAULT-VALUE}）")
        boolean includePopular = true;

        @Option(names = "--write-lake", negatable = true, defaultValue = "true",
                description = "是否落湖（默认 ${DEFAULT-VALUE}）")
        boolean writeLake = true;

        @Option(names = "--emit-kafka", negatable = true, defaultValue = "true",
                description = "是否推送事件到 Kafka（默认 ${DEFAULT-VALUE}）")
        boolean emitKafka = true;

        @Override
        public Integer call() {
            Context ctx = parent.context();
            ctx.jobName = "tmdb_ingestion_popularity";
            boolean success = false;
            try {
                LocalDate dt = TimeUtils.today(ctx.clock, ctx.config.getBusinessZone());
                ctx.groupingKey = dt.toString();
                if (emitKafka) {
                    ctx.producer = IngestionComponents.kafkaProducer(ctx.config, ctx.metrics);
                }
                List<String> windowList = windows == null || windows.length == 0
                        ? List.of("day", "week")
                        : List.of(windows);
                PopularityPollerJob job = new PopularityPollerJob(ctx.config, ctx.client, ctx.store, ctx.producer,
                        ctx.metrics, ctx.clock);
                JobResult result = job.run(new PopularityPollerJob.Options()
                        .entityTypes(parseEntities(entities))
                        .windows(windowList)
                        .includePopular(includePopular)
                        .writeLake(writeLake)
                        .emitKafka(emitKafka));
                success = !result.isFailure(ctx.maxFailureRatio);
                return success ? 0 : 1;
            } finally {
                ctx.close(success);
            }
        }
    }

    /** 汇总多个实体类型的结果。 */
    private static void merge(JobResult target, JobResult source) {
        for (int i = 0; i < source.getTotal(); i++) {
            target.recordAttempted();
        }
        for (int i = 0; i < source.getSuccess(); i++) {
            target.recordSuccess();
        }
        for (int i = 0; i < source.getNotFound(); i++) {
            target.recordNotFound();
        }
        for (int i = 0; i < source.getFailed(); i++) {
            target.recordFailed();
        }
        target.recordKafkaSent(source.getKafkaSent());
    }

    /** 程序入口。 */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new IngestionCli()).execute(args);
        System.exit(exitCode);
    }
}
