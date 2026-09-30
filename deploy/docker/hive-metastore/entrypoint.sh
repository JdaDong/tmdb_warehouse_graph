#!/usr/bin/env bash
# =============================================================================
# Hive Metastore 幂等启动脚本
#
# 环境变量：
#   HMS_DB_URL       元数据库 JDBC URL（默认 jdbc:postgresql://postgres:5432/metastore）
#   HMS_DB_USER      元数据库用户（默认 hive）
#   HMS_DB_PASSWORD  元数据库密码（必填）
#   HMS_HEAP_MB      Metastore 堆大小，单位 MB（默认 1024）
#
# 非密钥配置（S3A、仓库路径等）见挂载的 /opt/hive/conf/hive-site.xml。
# =============================================================================
set -euo pipefail

HMS_DB_URL="${HMS_DB_URL:-jdbc:postgresql://postgres:5432/metastore}"
HMS_DB_USER="${HMS_DB_USER:-hive}"
: "${HMS_DB_PASSWORD:?必须设置 HMS_DB_PASSWORD}"
export HADOOP_HEAPSIZE="${HMS_HEAP_MB:-1024}"
export HADOOP_CLIENT_OPTS="${HADOOP_CLIENT_OPTS:-} -Duser.timezone=UTC -Dfile.encoding=UTF-8"

SCHEMATOOL=(/opt/hive/bin/schematool -dbType postgres -driver org.postgresql.Driver
            -url "${HMS_DB_URL}" -userName "${HMS_DB_USER}" -passWord "${HMS_DB_PASSWORD}")

log() { echo "[hms-entrypoint] $(date -u +%FT%TZ) $*"; }

if "${SCHEMATOOL[@]}" -info >/dev/null 2>&1; then
    log "元数据库 Schema 已存在，跳过初始化"
else
    log "元数据库 Schema 不存在，开始初始化"
    "${SCHEMATOOL[@]}" -initSchema
    log "Schema 初始化完成"
fi

log "启动 Hive Metastore（port 9083）"
exec /opt/hive/bin/hive --skiphadoopversion --skiphbasecp --service metastore -p 9083 \
    --hiveconf javax.jdo.option.ConnectionURL="${HMS_DB_URL}" \
    --hiveconf javax.jdo.option.ConnectionUserName="${HMS_DB_USER}" \
    --hiveconf javax.jdo.option.ConnectionPassword="${HMS_DB_PASSWORD}"
