#!/usr/bin/env bash
# =============================================================================
# 提交 Flink 实时作业到集群（ detached 模式）
#
#   scripts/run/submit-flink.sh entity-change
#   scripts/run/submit-flink.sh popularity-trend
#   scripts/run/submit-flink.sh list            # 查看运行中的作业
#
# 说明：jar 已随 flink 镜像打包到 /opt/tmdbwh/dist，提交由 JobManager 执行。
# =============================================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/../lib/common.sh"

JOB="${1:-popularity-trend}"
JAR=/opt/tmdbwh/dist/realtime.jar

case "${JOB}" in
    entity-change|popularity-trend)
        # --parallelism 由作业内配置决定（tmdbwh.realtime.parallelism），这里不覆盖
        log "提交实时作业: ${JOB}"
        compose exec -T flink-jobmanager flink run -d -c com.tmdbwh.realtime.cli.RealtimeCli \
            "${JAR}" run --job "${JOB}"
        ;;
    list)
        compose exec -T flink-jobmanager flink list
        ;;
    *)
        die "未知作业: ${JOB}（可选: entity-change | popularity-trend | list）"
        ;;
esac
