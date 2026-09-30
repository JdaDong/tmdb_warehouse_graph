#!/usr/bin/env bash
# =============================================================================
# 运行采集：incremental（增量）/ full（全量）/ popularity（热度轮询）
#
#   scripts/run/run-ingestion.sh incremental 2026-09-30
#   scripts/run/run-ingestion.sh full 2026-09-30
#   scripts/run/run-ingestion.sh popularity 2026-09-30
# =============================================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/../lib/common.sh"

MODE="${1:-incremental}"
DATE="${2:-$(date -u -d 'yesterday' +%F 2>/dev/null || date -v-1d +%F)}"

case "${MODE}" in
    incremental|full|popularity) ;;
    *) die "未知采集模式: ${MODE}（可选: incremental | full | popularity）" ;;
esac

log "运行采集: mode=${MODE} dt=${DATE}"

compose run --rm --no-deps cli \
    java -Xmx2g -jar /opt/tmdbwh/dist/ingestion.jar "${MODE}" --date "${DATE}"

log "采集完成: mode=${MODE} dt=${DATE}"
