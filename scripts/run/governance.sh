#!/usr/bin/env bash
# =============================================================================
# 治理任务统一入口
#
#   scripts/run/governance.sh migrate            # 应用待执行的迁移
#   scripts/run/governance.sh migrate-info       # 查看迁移状态
#   scripts/run/governance.sh quality 2026-09-30 # 质量检查（BLOCKER 失败返回非 0）
#   scripts/run/governance.sh lineage --table dwd.dim_movie
#   scripts/run/governance.sh lifecycle          # 默认 dry-run，加 --apply 才执行
#   scripts/run/governance.sh access             # 预览授权语句
#   scripts/run/governance.sh metrics --validate # 指标口径校验
# =============================================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/../lib/common.sh"

[ "$#" -ge 1 ] || die "用法: $0 <子命令> [参数...]"
SUB="$1"; shift

log "执行治理任务: ${SUB} $*"

# 质量检查允许以非 0 退出（表示 BLOCKER 失败），这里如实向上传递
set +e
compose run --rm --no-deps cli \
    java -Xmx1g -jar /opt/tmdbwh/dist/governance.jar "${SUB}" "$@"
CODE=$?
set -e

if [ "${CODE}" -ne 0 ]; then
    err "治理任务 ${SUB} 以退出码 ${CODE} 结束"
    exit "${CODE}"
fi
log "治理任务完成: ${SUB}"
