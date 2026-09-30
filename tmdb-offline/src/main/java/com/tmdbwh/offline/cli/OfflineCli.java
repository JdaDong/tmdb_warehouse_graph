package com.tmdbwh.offline.cli;

import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.offline.IcebergMaintenance;
import com.tmdbwh.offline.OfflinePipeline;
import com.tmdbwh.offline.SparkSupport;
import com.tmdbwh.offline.config.OfflineConfigLoader;
import java.io.File;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/**
 * 离线数仓命令行入口。
 *
 * <pre>
 *   offline run      --date 2026-09-30                 # 全链路
 *   offline run      --date 2026-09-30 --sync          # 并同步 ClickHouse
 *   offline iceberg  --table dwd.fact_movie_credit --orphan --expire
 *   offline date-dim --start-year 2010 --years 20
 * </pre>
 *
 * <p>退出码：0 成功；1 失败。
 */
@Command(name = "offline", mixinStandardHelpOptions = true, version = "offline 1.0",
        description = "Spark 离线数仓：分层建模、SCD2、湖仓写入、ClickHouse 同步、Iceberg 维护",
        subcommands = {OfflineCli.RunCommand.class, OfflineCli.IcebergCommand.class,
                OfflineCli.DateDimCommand.class},
        sortOptions = false)
public class OfflineCli implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(OfflineCli.class);

    @Option(names = {"-c", "--config"}, description = "配置文件路径（HOCON）")
    File configFile;

    @Option(names = "--local", description = "本地模式运行（用 local[*] 启动 Spark，便于联调）")
    boolean local;

    AppConfig config() {
        return OfflineConfigLoader.load(configFile, "offline");
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    /** 业务日期解析：为空时取"当前业务日期"（按配置时区）。 */
    static LocalDate resolveDate(AppConfig config, String date) {
        if (date == null || date.isBlank()) {
            return LocalDate.now(config.getBusinessZone());
        }
        return LocalDate.parse(date.trim());
    }

    // ============================== run ==============================
    @Command(name = "run", mixinStandardHelpOptions = true, sortOptions = false,
            description = "执行 ODS→DWD→DWS→ADS 全链路")
    static class RunCommand implements Callable<Integer> {

        @ParentCommand
        OfflineCli parent;

        @Option(names = "--date", description = "业务日期 yyyy-MM-dd，默认当前业务日期")
        String date;

        @Option(names = "--sync", negatable = true, defaultValue = "false",
                description = "是否同步到 ClickHouse（默认 ${DEFAULT-VALUE}）")
        boolean sync;

        @Override
        public Integer call() {
            AppConfig config = parent.config();
            LocalDate businessDate = resolveDate(config, date);
            try (OfflinePipeline pipeline = new OfflinePipeline(config, businessDate, null, parent.local)) {
                Map<String, Long> counts = pipeline.run(sync);
                LOG.info("离线链路完成: {}", counts);
                return 0;
            } catch (RuntimeException e) {
                LOG.error("离线链路失败: {}", e.toString());
                return 1;
            }
        }
    }

    // ============================== iceberg ==============================
    @Command(name = "iceberg", mixinStandardHelpOptions = true, sortOptions = false,
            description = "Iceberg 表维护：过期快照 / 孤儿文件 / 小文件合并")
    static class IcebergCommand implements Callable<Integer> {

        @ParentCommand
        OfflineCli parent;

        @Option(names = "--table", required = true, description = "表名，形如 dwd.fact_movie_credit")
        String table;

        @Option(names = "--expire", description = "清理过期快照（保留最近 3 个）")
        boolean expire;

        @Option(names = "--orphan", description = "清理孤儿文件")
        boolean orphan;

        @Option(names = "--compact", description = "合并小文件（写操作）")
        boolean compact;

        @Option(names = "--dry-run", description = "只打印将要执行的语句")
        boolean dryRun;

        @Override
        public Integer call() {
            AppConfig config = parent.config();
            if (!expire && !orphan && !compact) {
                LOG.warn("未指定任何维护动作，将只做校验（可用 --expire / --orphan / --compact）");
            }
            try (org.apache.spark.sql.SparkSession spark =
                    SparkSupport.build(config, "tmdbwh-iceberg-maintenance", parent.local)) {
                List<String> output = IcebergMaintenance.run(spark, table, expire, orphan, compact, dryRun);
                output.forEach(line -> LOG.info("  {}", line));
                return 0;
            } catch (IllegalArgumentException e) {
                LOG.error("参数错误: {}", e.getMessage());
                return 1;
            }
        }
    }

    // ============================== date-dim ==============================
    @Command(name = "date-dim", mixinStandardHelpOptions = true, sortOptions = false,
            description = "生成并装载日期维（dwd.dim_date）")
    static class DateDimCommand implements Callable<Integer> {

        @ParentCommand
        OfflineCli parent;

        @Option(names = "--start-year", defaultValue = "2010", description = "起始年份（默认 ${DEFAULT-VALUE}）")
        int startYear;

        @Option(names = "--years", defaultValue = "20", description = "生成年数（默认 ${DEFAULT-VALUE}）")
        int years;

        @Override
        public Integer call() {
            AppConfig config = parent.config();
            if (startYear < 1900 || years < 1 || years > 100) {
                LOG.error("参数不合法: startYear={} years={}", startYear, years);
                return 1;
            }
            LocalDate businessDate = LocalDate.now(config.getBusinessZone());
            try (OfflinePipeline pipeline = new OfflinePipeline(config, businessDate, null, parent.local)) {
                pipeline.spark().sql("CREATE DATABASE IF NOT EXISTS " + SparkSupport.CATALOG + ".dwd");
                long rows = pipeline.generateDateDim(startYear, years).count();
                LOG.info("日期维生成完成: {} 行（{} 年起 {} 年）", rows, startYear, years);
                return 0;
            }
        }
    }

    /** 程序入口。 */
    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = new CommandLine(new OfflineCli()).execute(args);
        } catch (Exception e) {
            LOG.error("离线命令执行失败: {}", e.toString());
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}
