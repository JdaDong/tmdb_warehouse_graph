#!/usr/bin/env bash
# =============================================================================
# 同步 Airflow DAG 到 Helm Chart（chart 内的 dags/ 是 airflow/dags/ 的副本）
#
#   scripts/sync-dags.sh          # 同步
#   scripts/sync-dags.sh --check  # 只检查是否一致（CI 用）
# =============================================================================
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

SRC="${REPO_ROOT}/airflow/dags"
DST="${REPO_ROOT}/deploy/helm/tmdbwh/dags"
CHECK="${1:-}"

mkdir -p "${DST}"

if [ "${CHECK}" = "--check" ]; then
    diff -r "${SRC}" "${DST}" >/dev/null 2>&1 \
        || die "Helm Chart 内的 DAG 与 airflow/dags 不一致，请执行 make sync-dags"
    log "DAG 副本一致"
    exit 0
fi

cp -f "${SRC}"/*.py "${DST}"/
log "已同步 DAG: ${SRC} -> ${DST}"
