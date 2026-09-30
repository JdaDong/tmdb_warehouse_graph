package com.tmdbwh.governance.cli;

import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.governance.ConfigLoader;
import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * 治理命令行入口。
 *
 * <pre>
 *   governance migrate            # 执行待应用的迁移
 *   governance migrate --dry-run  # 只打印计划
 *   governance migrate-info       # 查看当前版本与待执行脚本
 *   governance migrate-history    # 查看已执行记录
 *   governance quality --date 2026-09-30
 *   governance lineage --table dwd.dim_movie --direction downstream
 *   governance lifecycle           # 默认 dry-run，确认后加 --apply
 *   governance access              # 预览授权语句，确认后加 --apply
 *   governance metrics --validate
 * </pre>
 *
 * <p>退出码：0 成功；1 失败（校验和不匹配 / 版本回退 / 执行出错）。
 */
@Command(name = "governance", mixinStandardHelpOptions = true, version = "governance 1.0",
        description = "OLAP 数据治理：迁移 / 元数据 / 标准 / 质量 / 血缘 / 生命周期 / 权限 / 成本",
        subcommands = {MigrationCommand.class, QualityCommand.class, LineageCommand.class,
                LifecycleCommand.class, AccessCommand.class, MetricsCommand.class},
        sortOptions = false)
public class GovernanceCli implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(GovernanceCli.class);

    @Option(names = {"-c", "--config"}, description = "配置文件路径（HOCON，覆盖环境变量与默认值）")
    File configFile;

    /** 共享配置（子命令按需加载）。 */
    AppConfig config() {
        return ConfigLoader.load(configFile, "governance");
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    /** 程序入口。 */
    public static void main(String[] args) {
        int exitCode;
        try {
            exitCode = new CommandLine(new GovernanceCli()).execute(args);
        } catch (Exception e) {
            LOG.error("治理命令执行失败: {}", e.toString());
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    /** 供子命令使用的路径解析。 */
    static Path optionalPath(String value) {
        return value == null || value.isBlank() ? null : Path.of(value.trim());
    }
}
