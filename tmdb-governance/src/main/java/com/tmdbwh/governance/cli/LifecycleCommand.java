package com.tmdbwh.governance.cli;

import com.tmdbwh.common.clickhouse.ClickHouseClient;
import com.tmdbwh.governance.ConfigLoader;
import com.tmdbwh.governance.lifecycle.LifecycleManager;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

/** {@code governance lifecycle}：TTL / 冷下沉 / 分区清理。 */
@Command(name = "lifecycle", mixinStandardHelpOptions = true, sortOptions = false,
        description = "生命周期：TTL、冷数据下沉、过期分区清理（默认 dry-run）")
public class LifecycleCommand implements Callable<Integer> {

    private static final Logger LOG = LoggerFactory.getLogger(LifecycleCommand.class);

    @ParentCommand
    com.tmdbwh.governance.cli.GovernanceCli parent;

    @Option(names = "--apply", description = "真正执行（不加则只打印计划）")
    boolean apply;

    @Option(names = "--date", description = "基准日期 yyyy-MM-dd，默认今天（UTC）")
    String date;

    @Override
    public Integer call() {
        var config = ConfigLoader.load(parent.configFile, "governance-lifecycle");
        LocalDate today = date != null ? LocalDate.parse(date) : LocalDate.now(ZoneOffset.UTC);
        try (ClickHouseClient client = ClickHouseClient.create(config.getClickhouse())) {
            // dry-run 由配置决定，--apply 可覆盖：不可逆操作默认只看计划
            LifecycleManager manager = apply
                    ? new LifecycleManager(client, policies(), false, config.getClickhouse().getCluster())
                    : LifecycleManager.create(client, config.getClickhouse().getCluster());
            List<com.tmdbwh.governance.lifecycle.LifecycleManager.Action> actions = manager.plan(today);
            System.out.println("生命周期计划（基准日期 " + today + "，共 " + actions.size() + " 项）:");
            actions.forEach(action -> System.out.println("  " + action));
            if (!apply) {
                System.out.println("这是 dry-run，未做任何修改；确认后追加 --apply 执行。");
                return 0;
            }
            List<String> log = manager.apply(today);
            log.forEach(line -> LOG.info("  {}", line));
            return 0;
        }
    }

    private static List<com.tmdbwh.governance.lifecycle.LifecyclePolicy> policies() {
        return com.typesafe.config.ConfigFactory.load()
                .getConfig("tmdbwh.governance.lifecycle")
                .getConfigList("policies").stream()
                .map(com.tmdbwh.governance.lifecycle.LifecyclePolicy::from)
                .collect(java.util.stream.Collectors.toList());
    }
}
