package com.tmdbwh.governance.cli;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.tmdbwh.common.config.AppConfig;
import com.tmdbwh.governance.migration.Migration;
import com.tmdbwh.governance.migration.MigrationException;
import com.tmdbwh.governance.migration.MigrationPlan;
import com.tmdbwh.governance.migration.MigrationResult;
import com.tmdbwh.governance.migration.SchemaMigrator;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code governance migrate} 子命令：应用版本化 DDL。 */
@Command(name = "migrate", mixinStandardHelpOptions = true, sortOptions = false,
        description = "应用 ClickHouse 版本化 DDL 迁移")
public class MigrationCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(MigrationCommand.class);

    @ParentCommand
    GovernanceCli parent;

    @Option(names = "--dry-run", description = "只打印将要执行的脚本，不实际执行")
    boolean dryRun;

    @Option(names = "--extra-dir", description = "额外的迁移脚本目录（运维自定义脚本）")
    String extraDir;

    @Option(names = "--info", description = "只显示当前版本与待执行脚本")
    boolean infoOnly;

    @Override
    public Integer call() {
        AppConfig config = parent.config();
        Path extra = GovernanceCli.optionalPath(extraDir);
        try (ClickHouseClient client = ClickHouseClient.create(config.getClickhouse());
                SchemaMigrator migrator = SchemaMigrator.using(client, config.getClickhouse().getCluster(),
                        "governance-cli")) {

            if (infoOnly) {
                MigrationPlan plan = migrator.plan();
                LOG.info("当前版本 V{}，目标版本 V{}，待执行 {} 个脚本", plan.getCurrentVersion(), plan.getTargetVersion(),
                        plan.getPending().size());
                for (Migration script : plan.getPending()) {
                    LOG.info("  待执行 {}", script);
                }
                return 0;
            }

            MigrationResult result = migrator.migrate(dryRun, extra);
            for (MigrationResult.Applied applied : result.getApplied()) {
                LOG.info("  {}", applied);
            }
            LOG.info(result.summary());

            if (!dryRun) {
                List<Map<String, Object>> history = migrator.history();
                LOG.info("迁移历史：共 {} 条记录", history.size());
            }
            return 0;
        } catch (MigrationException e) {
            LOG.error("迁移失败: {}", e.getMessage());
            return 1;
        } catch (RuntimeException e) {
            LOG.error("迁移失败（连接或执行异常）: {}", e.toString());
            return 1;
        }
    }
}
