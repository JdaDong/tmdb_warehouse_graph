#!/usr/bin/env bash
# =============================================================================
# OpenMetadata 元数据采集（可选组件）
#   前置：.env 中已配置 OM_JWT_TOKEN（UI → 设置 → Bots → ingestion-bot → 生成 JWT）
#   说明：本平台的治理执行（质量 / 标准 / 生命周期 / 权限 / 成本）由自研 tmdb-governance 完成，
#        OpenMetadata 只作为交互式元数据目录与数据发现入口，二者互补。
# =============================================================================
set -euo pipefail
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"

if ! compose ps --status=running --services | grep -qx openmetadata-server; then
    die "OpenMetadata 未运行，请先: make up PROFILES=...,governance"
fi

if [ -z "$(env_get OM_JWT_TOKEN)" ]; then
    warn "未配置 OM_JWT_TOKEN，跳过元数据采集。"
    warn "步骤：打开 http://localhost:8585 → 设置 → Bots → ingestion-bot → 生成 JWT → 写入 .env 的 OM_JWT_TOKEN"
    exit 0
fi

log "触发 ClickHouse 元数据采集"
compose run --rm --profile governance openmetadata-ingest
log "元数据采集已提交，可在 OpenMetadata UI 的 Database Services 中查看"
