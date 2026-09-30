package com.tmdbwh.realtime.cli;

import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.realtime.RealtimeConfig;
import com.tmdbwh.realtime.jobs.EntityChangeJob;
import com.tmdbwh.realtime.jobs.PopularityTrendJob;
import java.io.File;
import java.time.Duration;
import java.util.concurrent.Callable;
import org.apache.flink.api.common.restartstrategy.RestartStrategies;
import org.apache.flink.runtime.state.StateBackend;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * 实时作业命令行入口。
 *
 * <pre>
 *   realtime run  --job entity-change      # 提交到集群（由 conf/flink-conf.yaml 决定 master）
 *   realtime run  --job popularity-trend --local
 *   realtime plan --job popularity-trend   # 只打印装配信息，不启动（发布前检查用）
 * </pre>
 */
@Command(name = "realtime", mixinStandardHelpOptions = true, version = "realtime 1.0",
        description = "Flink 实时数仓：实体变更去重落库、热度窗口聚合与飙升检测",
        subcommands = {RealtimeCli.RunCommand.class, RealtimeCli.PlanCommand.class}, sortOptions = false)
public class RealtimeCli implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(RealtimeCli.class);

    /** 支持的作业名。 */
    public enum JobName {
        ENTITY_CHANGE("entity-change"),
        POPULARITY_TREND("popularity-trend");

        private final String value;

        JobName(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }

        /** 按 CLI 参数值解析。 */
        public static JobName parse(String text) {
            for (JobName job : values()) {
                if (job.value.equalsIgnoreCase(text) || job.name().equalsIgnoreCase(text)) {
                    return job;
                }
            }
            throw new IllegalArgumentException("未知作业: " + text + "（可选: entity-change, popularity-trend）");
        }
    }

    @Option(names = {"-c", "--config"}, description = "配置文件路径（HOCON）")
    File configFile;

    @Option(names = "--local", description = "本地模式运行（调试用）")
    boolean local;

    AppConfig config() {
        return configFile != null ? AppConfig.load(configFile) : AppConfig.load();
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    /**
     * 装配并（可选）执行作业。
     *
     * @param execute true 时触发执行；false 只装配（用于 plan 与测试）
     * @return 作业名
     */
    static String assemble(StreamExecutionEnvironment env, AppConfig appConfig, RealtimeConfig realtimeConfig,
            String jobText, boolean execute) throws Exception {
        JobName job = JobName.parse(jobText);
        switch (job) {
            case ENTITY_CHANGE:
                EntityChangeJob.build(env, appConfig, realtimeConfig);
                env.execute(EntityChangeJob.jobName());
                break;
            case POPULARITY_TREND:
                PopularityTrendJob.build(env, appConfig, realtimeConfig);
                env.execute(PopularityTrendJob.jobName());
                break;
            default:
                throw new IllegalArgumentException("未实现的作业: " + job);
        }
        LOG.info("作业 {} 装配完成", job.getValue());
        return job.getValue();
    }

    /**
     * 统一配置执行环境。
     *
     * <p>这些设置在每个作业里都要做一遍，漏掉任何一项都会在生产上出问题：
     * 不设 checkpoint 就没有精确一次、不设重启策略就会无限重启、不设 checkpoint 存储路径就无法从失败恢复。
     */
    static void configureEnvironment(StreamExecutionEnvironment env, RealtimeConfig config, String checkpointDir) {
        env.enableCheckpointing(config.getCheckpointInterval().toMillis(), config.getCheckpointMode());
        CheckpointConfig checkpointConfig = env.getCheckpointConfig();
        checkpointConfig.setCheckpointTimeout(config.getCheckpointTimeout().toMillis());
        checkpointConfig.setMinPauseBetweenCheckpoints(config.getMinPause().toMillis());
        // 作业取消后保留 checkpoint：升级重启时可以接着跑，而不是从头消费
        checkpointConfig.setExternalizedCheckpointCleanup(config.isExternalizedCheckpoint()
                ? CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION
                : CheckpointConfig.ExternalizedCheckpointCleanup.DELETE_ON_CANCELLATION);
        if (checkpointDir != null && !checkpointDir.isEmpty()) {
            checkpointConfig.setCheckpointStorage(checkpointDir);
        }
        // 固定延迟重启：外部依赖抖动（ClickHouse 重启等）时自动恢复，同时避免无限重启掩盖真实故障
        env.setRestartStrategy(RestartStrategies.fixedDelayRestart(3,
                org.apache.flink.api.common.time.Time.milliseconds(10_000L)));
        env.setParallelism(config.getParallelism());
        LOG.info("执行环境已配置: checkpoint={}{} mode={} 并行度={}", config.getCheckpointInterval(),
                config.getCheckpointMode() == CheckpointingMode.EXACTLY_ONCE ? "(精确一次)" : "(至少一次)",
                config.getCheckpointMode(), config.getParallelism());
    }

    /**
     * 按类名加载状态后端。
     *
     * <p>用反射而不是直接依赖 RocksDB 实现类的原因：不同 Flink 发行版对 RocksDB 后端的打包方式不同
     * （社区版需额外依赖，部分商业发行版内置），直接引用会在某些环境出现 NoClassDefFoundError。
     * 加载失败时回退到集群默认后端，只打印告警——状态后端选型不应成为作业启动的硬阻塞。
     */
    static void configureStateBackend(StreamExecutionEnvironment env, String backendClassName) {
        if (backendClassName == null || backendClassName.isEmpty()) {
            return;
        }
        try {
            Class<?> type = Class.forName(backendClassName);
            StateBackend backend = (StateBackend) type.getDeclaredConstructor().newInstance();
            env.setStateBackend(backend);
            LOG.info("状态后端: {}", backendClassName);
        } catch (ReflectiveOperationException | ClassCastException | RuntimeException e) {
            LOG.warn("状态后端 {} 加载失败，使用集群默认: {}", backendClassName, e.toString());
        }
    }

    /** run 子命令。 */
    @Command(name = "run", mixinStandardHelpOptions = true, sortOptions = false, description = "提交实时作业")
    static class RunCommand implements Callable<Integer> {

        @ParentCommand
        RealtimeCli parent;

        @Option(names = "--job", required = true, description = "作业名：entity-change | popularity-trend")
        String job;

        @Option(names = "--checkpoint-dir", description = "checkpoint 存储路径（如 s3a://tmdb-lake/flink/ck）")
        String checkpointDir;

        @Option(names = "--state-backend",
                description = "状态后端类名（如 org.apache.flink.state.rocksdb.EmbeddedRocksDBStateBackend）")
        String stateBackend;

        @Override
        public Integer call() {
            AppConfig appConfig = parent.config();
            RealtimeConfig realtimeConfig = RealtimeConfig.load(appConfig);
            try {
                StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
                configureEnvironment(env, realtimeConfig, checkpointDir);
                configureStateBackend(env, stateBackend);
                assemble(env, appConfig, realtimeConfig, job, parent.local);
                return 0;
            } catch (IllegalArgumentException e) {
                LOG.error("参数错误: {}", e.getMessage());
                return 2;
            } catch (Exception e) {
                LOG.error("作业提交失败: {}", e.toString());
                return 1;
            }
        }
    }

    /** plan 子命令：只打印装配信息，不启动（用于发布前检查配置）。 */
    @Command(name = "plan", mixinStandardHelpOptions = true, sortOptions = false,
            description = "打印作业装配信息（不启动）")
    static class PlanCommand implements Callable<Integer> {

        @ParentCommand
        RealtimeCli parent;

        @Option(names = "--job", required = true, description = "作业名：entity-change | popularity-trend")
        String job;

        @Override
        public Integer call() {
            AppConfig appConfig = parent.config();
            RealtimeConfig realtimeConfig = RealtimeConfig.load(appConfig);
            JobName parsed = JobName.parse(job);
            System.out.println("作业: " + parsed.getValue());
            System.out.println("  Kafka: " + realtimeConfig.getKafka().getBootstrapServers());
            System.out.println("  窗口: " + realtimeConfig.getWindowSize()
                    + "（乱序容忍 " + realtimeConfig.getAllowedLateness() + "，空闲判定 "
                    + realtimeConfig.getIdleness() + "）");
            System.out.println("  Checkpoint: " + realtimeConfig.getCheckpointInterval()
                    + " " + realtimeConfig.getCheckpointMode());
            System.out.println("  去重状态 TTL: " + Duration.ofMillis(realtimeConfig.getStateTtl().toMillis()));
            System.out.println("  飙升阈值: " + realtimeConfig.getSurgeRatioThreshold()
                    + "（最少样本 " + realtimeConfig.getSurgeMinSamples()
                    + "，基线下限 " + realtimeConfig.getSurgeMinBaseline() + "）");
            System.out.println("  写入表: " + realtimeConfig.getTableChangeEvent() + ", "
                    + realtimeConfig.getTablePopularityEvent() + ", "
                    + realtimeConfig.getTableMoviePopularity() + ", "
                    + realtimeConfig.getTableSurgeAlert());
            return 0;
        }
    }

    /** 程序入口。 */
    public static void main(String[] args) {
        int exitCode = new CommandLine(new RealtimeCli()).execute(args);
        System.exit(exitCode);
    }
}
