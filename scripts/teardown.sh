#!/usr/bin/env bash
# =============================================================================
# 停止并清理本地环境
#   scripts/teardown.sh              停止所有容器（保留数据卷）
#   scripts/teardown.sh --purge      停止并删除数据卷（数据不可恢复，需确认）
#   scripts/teardown.sh --purge -y   不询问直接删除
# =============================================================================
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

PURGE=0
ASSUME_YES=0
for arg in "$@"; do
    case "${arg}" in
        --purge) PURGE=1 ;;
        -y|--yes) ASSUME_YES=1 ;;
        *) warn "忽略未知参数: ${arg}" ;;
    esac
done

# 停止时覆盖所有 profile，避免残留容器
ALL_PROFILES="core,olap,graph,compute,mock,orchestration,monitoring,governance,tools"
PROFILES="${PROFILES:-${ALL_PROFILES}}"

if [ "${PURGE}" = "1" ]; then
    warn "即将删除容器与所有数据卷（MinIO / Kafka / PostgreSQL / ClickHouse / Neo4j 数据将全部丢失）"
    if [ "${ASSUME_YES}" != "1" ]; then
        read -r -p "确认请输入 'yes': " answer
        [ "${answer}" = "yes" ] || die "已取消"
    fi
    log "停止并删除数据卷"
    # shellcheck disable=SC2046
    compose $(compose_profiles_args) down --remove-orphans --volumes
    log "已清理数据卷"
else
    log "停止容器（保留数据卷）"
    # shellcheck disable=SC2046
    compose $(compose_profiles_args) down --remove-orphans
    log "已停止。数据卷保留，可用 'make up' 恢复；彻底清理请执行 scripts/teardown.sh --purge"
fi
