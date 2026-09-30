#!/usr/bin/env bash
# =============================================================================
# 端到端冒烟：环境初始化 → 采集 → 离线分层 → 图装载 → 质量检查
#
# 用途：
#   - 新环境搭建后确认"全链路真的能跑通"，而不是只看容器起来了；
#   - 升级后回归验证（尤其是分层口径与图装载）。
#
# 用法：
#   scripts/smoke.sh              # 使用 Mock 数据源（不需要 TMDB API Key）
#   scripts/smoke.sh 2026-09-30   # 指定业务日期
# =============================================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

DATE="${1:-$(date -u -d 'yesterday' +%F 2>/dev/null || date -v-1d +%F)}"
CH_URL="${CLICKHOUSE_HTTP:-http://localhost:8123}"
CH_USER="${CLICKHOUSE_USER:-default}"
CH_PASS="${CLICKHOUSE_PASSWORD:-}"

failures=0
check() { # check <说明> <SQL> <期望最小行数>
    local desc="$1" sql="$2" min="${3:-1}" actual
    actual=$(curl -sS --max-time 30 --user "${CH_USER}:${CH_PASS}" \
        --data-urlencode "query=${sql}" "${CH_URL}/" | tr -d ' \n')
    if [ -z "${actual}" ] || [ "${actual}" -lt "${min}" ]; then
        err "✗ ${desc}: 实际 ${actual:-0}，期望 >= ${min}"
        failures=$((failures + 1))
    else
        log "✓ ${desc}: ${actual} 行"
    fi
}

log "=== 1/6 等待服务就绪 ==="
"$(dirname "${BASH_SOURCE[0]}")/init/init-clickhouse.sh"
"$(dirname "${BASH_SOURCE[0]}")/init/init-minio.sh"
"$(dirname "${BASH_SOURCE[0]}")/init/init-kafka-topics.sh"
"$(dirname "${BASH_SOURCE[0]}")/init/init-neo4j.sh"
"$(dirname "${BASH_SOURCE[0]}")/init/init-iceberg.sh"

log "=== 2/6 初始化表结构 ==="
compose run --rm --no-deps cli \
    java -jar /opt/tmdbwh/dist/governance.jar migrate

log "=== 3/6 采集（Mock 数据源）==="
compose run --rm --no-deps cli \
    java -jar /opt/tmdbwh/dist/ingestion.jar incremental --date "${DATE}"

log "=== 4/6 离线分层 + ClickHouse 同步 ==="
compose run --rm --no-deps cli \
    java -jar /opt/tmdbwh/dist/offline.jar run --date "${DATE}" --sync

log "=== 5/6 校验各层数据 ==="
check "ODS 变更事件" "SELECT count() FROM ods.ods_change_event WHERE dt = '${DATE}'" 1
check "DWD 电影维度" "SELECT count() FROM dwd.dim_movie" 1
check "DWD 演职员事实" "SELECT count() FROM dwd.fact_movie_credit WHERE dt = '${DATE}'" 1
check "DWS 电影指标" "SELECT count() FROM dws.dws_movie_metric_1d WHERE dt = '${DATE}'" 1
check "ADS 热门榜" "SELECT count() FROM ads.ads_top_movie WHERE dt = '${DATE}'" 1

log "=== 6/6 图装载与质量检查 ==="
compose run --rm --no-deps cli java -jar /opt/tmdbwh/dist/graph.jar init
compose run --rm --no-deps cli java -jar /opt/tmdbwh/dist/graph.jar load
compose run --rm --no-deps cli java -jar /opt/tmdbwh/dist/graph.jar stats
compose run --rm --no-deps cli \
    java -jar /opt/tmdbwh/dist/governance.jar quality --date "${DATE}"

if [ "${failures}" -ne 0 ]; then
    die "冒烟测试失败：${failures} 项不达标"
fi
log "冒烟测试全部通过（业务日期 ${DATE}）"
