#!/usr/bin/env bash
# =============================================================================
# 装载 / 查询 Neo4j 图谱
#
#   scripts/run/load-graph.sh init                 # 初始化约束与索引
#   scripts/run/load-graph.sh load                 # 从数仓装载（幂等 MERGE）
#   scripts/run/load-graph.sh load --clear --yes    # 清空后重建
#   scripts/run/load-graph.sh stats                # 节点与关系统计
#   scripts/run/load-graph.sh analyze --case degree-centrality --param limit=10
#   scripts/run/load-graph.sh cases                # 列出分析用例
# =============================================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/../lib/common.sh"

[ "$#" -ge 1 ] || die "用法: $0 <子命令> [参数...]"
SUB="$1"; shift

log "执行图任务: ${SUB} $*"

compose run --rm --no-deps cli \
    java -Xmx2g -jar /opt/tmdbwh/dist/graph.jar "${SUB}" "$@"

log "图任务完成: ${SUB}"
