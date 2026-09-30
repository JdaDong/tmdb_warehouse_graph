package com.tmdbwh.governance.cli;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.tmdbwh.governance.ConfigLoader;
import com.tmdbwh.governance.quality.QualityChecker;
import com.tmdbwh.governance.quality.QualityResult;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code governance quality}：数据质量检查。 */
@Command(name = "quality", mixinStandardHelpOptions = true, sortOptions = false,
        description = "执行数据质量规则（唯一性、空值、值域、及时性、参照完整性）")
public class QualityCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(QualityCommand.class);

    @ParentCommand
    com.tmdbwh.governance.cli.GovernanceCli parent;

    @Option(names = "--date", description = "业务日期 yyyy-MM-dd，默认昨天（UTC）")
    String date;

    @Option(names = "--rule", description = "只执行指定规则 ID（可重复）")
    java.util.List<String> rules;

    @Override
    public Integer call() {
        String dt = date != null ? date : LocalDate.now(ZoneOffset.UTC).minusDays(1).toString();
        var config = ConfigLoader.load(parent.configFile, "governance-quality");
        try (ClickHouseClient client = ClickHouseClient.create(config.getClickhouse())) {
            QualityChecker checker = QualityChecker.create(client);
            QualityChecker.QualityReport report = checker.run(dt);
            for (QualityResult result : report.getResults()) {
                LOG.info("{} {} -> {} | {}", result.getSeverity(), result.getRuleId(),
                        result.isPassed() ? "PASS" : "FAIL", result.getMessage());
            }
            LOG.info("质量检查汇总: {} 条，通过 {}，失败 {}（BLOCKER 失败 {}）", report.total(), report.passed(),
                    report.failures(), report.blockerFailures());
            // 阻断语义：BLOCKER 失败时返回非 0，调度系统据此决定是否继续下游
            return checker.shouldBlock(report) ? 1 : 0;
        }
    }
}
