#!/usr/bin/env bash
# =============================================================================
# 运行离线链路（ODS → DWD → DWS → ADS → ClickHouse）
#
#   scripts/run/run-offline.sh                 # 处理昨天
#   scripts/run/run-offline.sh 2026-09-30      # 指定业务日期
#   scripts/run/run-offline.sh 2026-09-30 --no-sync   # 不同步 ClickHouse
#
# 说明：在 cli 容器内执行（容器内已安装 JDK 与各模块 fat-jar），
#       配置通过环境变量注入（见 .env），因此不需要在宿主机准备 JDK。
# =============================================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/../lib/common.sh"

DATE="${1:-$(date -u -d 'yesterday' +%F 2>/dev/null || date -v-1d +%F)}"
SYNC="${2:---sync}"

log "运行离线链路: dt=${DATE} ${SYNC}"

compose run --rm --no-deps cli \
    java -Xmx2g -jar /opt/tmdbwh/dist/offline.jar run --date "${DATE}" ${SYNC}

log "离线链路完成: dt=${DATE}"
