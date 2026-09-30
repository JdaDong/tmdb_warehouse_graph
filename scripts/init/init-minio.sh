#!/usr/bin/env bash
# =============================================================================
# MinIO 初始化（在 minio-init 容器中以 bitnamilegacy/minio-client 执行）
#   1. 等待服务端就绪
#   2. 创建数据湖桶与 ClickHouse 冷存储桶
#   3. 创建 Spark 事件日志目录标记对象（S3 无目录概念，需占位对象）
#   4. 创建应用专用账号并授予最小权限（不使用 root 凭据跑业务）
#   5. 导入生命周期规则（隔离区 / 事件日志 / 孤儿 checkpoint 自动过期）
# 幂等：可重复执行。
# =============================================================================
set -euo pipefail
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"

: "${S3_ENDPOINT:?必须设置 S3_ENDPOINT}"
: "${S3_ACCESS_KEY:?必须设置 S3_ACCESS_KEY}"
: "${S3_SECRET_KEY:?必须设置 S3_SECRET_KEY}"
: "${S3_BUCKET:?必须设置 S3_BUCKET}"
COLD_BUCKET="${CLICKHOUSE_COLD_BUCKET:-${S3_BUCKET}-cold}"

# HOME 在容器里可能不可写，显式指定 mc 配置目录
export MC_CONFIG_DIR="${MC_CONFIG_DIR:-/tmp/.mc}"
mkdir -p "${MC_CONFIG_DIR}"

ALIAS="local"
mc() { command mc --config-dir "${MC_CONFIG_DIR}" "$@"; }

log "等待 MinIO 就绪: ${S3_ENDPOINT}"
retry 30 2 mc alias set "${ALIAS}" "${S3_ENDPOINT}" "${S3_ACCESS_KEY}" "${S3_SECRET_KEY}" >/dev/null
retry 30 2 mc ready "${ALIAS}"

for bucket in "${S3_BUCKET}" "${COLD_BUCKET}"; do
    if mc ls "${ALIAS}/${bucket}" >/dev/null 2>&1; then
        log "桶已存在: ${bucket}"
    else
        mc mb "${ALIAS}/${bucket}"
        log "已创建桶: ${bucket}"
    fi
done

# Spark 事件日志 / Flink checkpoint 目录标记（S3 需占位对象才视为"目录"）
for prefix in spark-events checkpoints savepoints; do
    printf '' | mc pipe "${ALIAS}/${S3_BUCKET}/${prefix}/.keep" >/dev/null 2>&1 || true
done
log "已创建目录标记对象: spark-events/ checkpoints/ savepoints/"

if [ -f /conf/lifecycle.json ]; then
    log "导入生命周期规则"
    mc ilm import "${ALIAS}/${S3_BUCKET}" < /conf/lifecycle.json
else
    warn "未找到 /conf/lifecycle.json，跳过生命周期配置"
fi

# 应用专用账号（最小权限），避免业务使用 root 凭据
if [ -n "${S3_APP_ACCESS_KEY:-}" ] && [ -n "${S3_APP_SECRET_KEY:-}" ]; then
    if [ -f /conf/app-policy.json ]; then
        mc admin policy create "${ALIAS}" tmdbwh-app /conf/app-policy.json
        log "已写入策略 tmdbwh-app"
    else
        warn "未找到 /conf/app-policy.json，跳过策略创建"
    fi
    mc admin user add "${ALIAS}" "${S3_APP_ACCESS_KEY}" "${S3_APP_SECRET_KEY}" || \
        mc admin user info "${ALIAS}" "${S3_APP_ACCESS_KEY}" >/dev/null
    if mc admin policy attach "${ALIAS}" tmdbwh-app --user "${S3_APP_ACCESS_KEY}" 2>/dev/null; then
        log "已绑定策略 tmdbwh-app → ${S3_APP_ACCESS_KEY}"
    else
        log "策略已绑定（跳过）"
    fi
else
    warn "未设置 S3_APP_ACCESS_KEY / S3_APP_SECRET_KEY，跳过应用账号创建"
fi

log "MinIO 初始化完成"
