#!/usr/bin/env bash
# =============================================================================
# 运行 OpenMetadata ingestion：metadata ingest -c <config>
#   OM_JWT_TOKEN         ingestion-bot 的 JWT（必填，否则跳过并明确报错）
#   OM_CONFIG_FILE       工作流配置文件路径（默认 /opt/om/config/clickhouse_metadata.yaml）
# =============================================================================
set -euo pipefail

if [[ -z "${OM_JWT_TOKEN:-}" ]]; then
    echo "[om-ingest] 未设置 OM_JWT_TOKEN，跳过元数据采集。" >&2
    echo "[om-ingest] 获取方式：OpenMetadata UI → 设置 → Bots → ingestion-bot → 生成 JWT，填入 .env 后重启本服务。" >&2
    exit 0
fi

CONFIG="${OM_CONFIG_FILE:-/opt/om/config/clickhouse_metadata.yaml}"
if [[ ! -f "${CONFIG}" ]]; then
    echo "[om-ingest] 配置文件不存在: ${CONFIG}" >&2
    exit 66
fi

echo "[om-ingest] 开始采集: ${CONFIG}"
exec metadata ingest -c "${CONFIG}"
