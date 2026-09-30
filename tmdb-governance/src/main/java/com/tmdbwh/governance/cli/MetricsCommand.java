package com.tmdbwh.governance.cli;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.tmdbwh.governance.ConfigLoader;
import com.tmdbwh.governance.metrics.MetricDefinition;
import com.tmdbwh.governance.metrics.MetricRegistry;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code governance metrics}：指标口径清单与一致性校验。 */
@Command(name = "metrics", mixinStandardHelpOptions = true, sortOptions = false,
        description = "指标口径：清单与一致性校验（表 / 字段是否存在）")
public class MetricsCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(MetricsCommand.class);

    @ParentCommand
    com.tmdbwh.governance.cli.GovernanceCli parent;

    @Option(names = "--validate", description = "连接数据库校验表与字段是否存在")
    boolean validate;

    @Option(names = "--metric", description = "只显示指定指标")
    String metric;

    @Override
    public Integer call() {
        MetricRegistry registry = MetricRegistry.load();
        List<MetricDefinition> definitions = metric == null ? registry.getDefinitions()
                : List.of(registry.get(metric));

        System.out.printf("%-20s %-32s %-28s %s%n", "指标", "表", "计算表达式", "负责人");
        for (MetricDefinition definition : definitions) {
            System.out.printf("%-20s %-32s %-28s %s%n", definition.getName(), definition.getTable(),
                    definition.getExpression(), definition.getOwner());
        }

        if (!validate) {
            return 0;
        }
        var config = ConfigLoader.load(parent.configFile, "governance-metrics");
        try (ClickHouseClient client = ClickHouseClient.create(config.getClickhouse())) {
            List<MetricRegistry.Issue> issues = registry.validate(client);
            if (issues.isEmpty()) {
                System.out.println("指标口径校验通过");
                return 0;
            }
            issues.forEach(issue -> System.out.println("  " + issue));
            LOG.error("指标口径存在 {} 个问题", issues.size());
            return 1;
        }
    }
}
