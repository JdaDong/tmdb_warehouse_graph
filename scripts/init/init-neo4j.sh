#!/usr/bin/env bash
# =============================================================================
# Neo4j 初始化：唯一约束与索引
#   优先使用 tmdb-graph 的 CLI（init-schema）；CLI 不可用时回退到 cypher-shell 执行
#   tmdb-graph/src/main/resources/cypher/schema/*.cypher。
#   约束是"批量 MERGE 幂等"的前提：没有唯一约束，重复加载会产生重复节点。
# 幂等：CREATE CONSTRAINT IF NOT EXISTS。
# =============================================================================
set -euo pipefail
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"

SERVICE="${NEO4J_SERVICE:-neo4j}"
USER="${NEO4J_USER:-neo4j}"
PASSWORD="${NEO4J_PASSWORD:-}"
DATABASE="${NEO4J_DATABASE:-neo4j}"
SCHEMA_DIR="${REPO_ROOT}/tmdb-graph/src/main/resources/cypher/schema"

if ! compose ps --status=running --services | grep -qx "${SERVICE}"; then
    warn "Neo4j 未运行（${SERVICE}），请先: make up PROFILES=...,graph"
    exit 0
fi

log "等待 Neo4j 就绪（Bolt 7687 / HTTP 7474）"
retry 40 3 compose exec -T "${SERVICE}" bash -c 'exec 3<>/dev/tcp/127.0.0.1/7687' >/dev/null

if jar="$(find_jar graph)"; then
    log "使用图谱 CLI 初始化约束: $(basename "${jar}")"
    run_cli graph init-schema
else
    warn "未找到 dist/jars/tmdb-graph-*-all.jar，回退到 cypher-shell 执行约束脚本"
fi

if [ -d "${SCHEMA_DIR}" ]; then
    shopt -s nullglob
    files=("${SCHEMA_DIR}"/*.cypher)
    if [ "${#files[@]}" -eq 0 ]; then
        warn "${SCHEMA_DIR} 下暂无 .cypher 脚本（tmdb-graph 模块完成后补充）"
    else
        for f in "${files[@]}"; do
            log "执行约束脚本: $(basename "${f}")"
            if [ -n "${PASSWORD}" ]; then
                compose exec -T "${SERVICE}" cypher-shell -u "${USER}" -p "${PASSWORD}" -d "${DATABASE}" --format plain < "${f}"
            else
                compose exec -T "${SERVICE}" cypher-shell -u "${USER}" -d "${DATABASE}" --format plain < "${f}"
            fi
        done
    fi
else
    warn "未找到 ${SCHEMA_DIR}，跳过约束初始化"
fi

log "Neo4j 初始化完成"
