#!/usr/bin/env bash
# =============================================================================
# ClickHouse 初始化
#   1. 等待 HTTP 接口就绪
#   2. 创建分层数据库（ON CLUSTER，与集群拓扑一致）
#   3. 若 tmdb-governance 已构建（dist/jars 中有 governance jar），执行版本化迁移
#      （migrate 在 governance.schema_migrations 记录版本与校验和，禁止手工 DDL）
# 幂等：可重复执行。
# =============================================================================
set -euo pipefail
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"

SERVICE="${CLICKHOUSE_SERVICE:-clickhouse}"
USER="${CLICKHOUSE_USER:-default}"
PASSWORD="${CLICKHOUSE_PASSWORD:-}"
CLUSTER="${CLICKHOUSE_CLUSTER:-tmdb_cluster}"
DATABASES="${CLICKHOUSE_DATABASES:-ods dwd dws ads rt governance}"

if ! compose ps --status=running --services | grep -qx "${SERVICE}"; then
    warn "ClickHouse 未运行（${SERVICE}），请先: make up PROFILES=...,olap"
    exit 0
fi

ch() {
    if [ -n "${PASSWORD}" ]; then
        compose exec -T -e CLICKHOUSE_PASSWORD="${PASSWORD}" "${SERVICE}" \
            clickhouse-client --user "${USER}" --password "${PASSWORD}" "$@"
    else
        compose exec -T "${SERVICE}" clickhouse-client --user "${USER}" "$@"
    fi
}

log "等待 ClickHouse 就绪"
retry 30 3 ch --query "SELECT 1" >/dev/null

for db in ${DATABASES}; do
    ch --query "CREATE DATABASE IF NOT EXISTS ${db} ON CLUSTER ${CLUSTER}"
    log "数据库已就绪: ${db}"
done

if jar="$(find_jar governance)"; then
    log "执行版本化迁移: $(basename "${jar}")"
    run_cli governance migrate
else
    warn "未找到 dist/jars/tmdb-governance-*-all.jar，跳过建表迁移。"
    warn "tmdb-governance 模块完成后执行 'make dist' 再运行本脚本即可创建 ods/dwd/dws/ads/rt/governance 全部表与物化视图。"
fi

log "ClickHouse 初始化完成"
