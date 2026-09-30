#!/usr/bin/env bash
# =============================================================================
# Iceberg 命名空间初始化（在 spark-master 容器内执行 spark-sql）
#   命名空间：ods / dwd / dws / ads / rt
#   位置：s3a://${S3_BUCKET}/warehouse（由 spark-defaults.conf 的 catalog lake 决定）
# 幂等：CREATE NAMESPACE IF NOT EXISTS。
# =============================================================================
set -euo pipefail
# shellcheck source=../lib/common.sh
source "$(dirname "$0")/../lib/common.sh"

SERVICE="${SPARK_MASTER_SERVICE:-spark-master}"
NAMESPACES="${ICEBERG_NAMESPACES:-ods dwd dws ads rt}"

if ! compose ps --status=running --services | grep -qx "${SERVICE}"; then
    warn "Spark 集群未运行（${SERVICE}），请先: make up PROFILES=...,compute"
    exit 0
fi

statements=""
for ns in ${NAMESPACES}; do
    statements="${statements}CREATE DATABASE IF NOT EXISTS lake.${ns}; "
done

log "在 ${SERVICE} 中执行: ${statements}"
compose exec -T "${SERVICE}" \
    /opt/spark/bin/spark-sql \
    --master "spark://${SERVICE}:7077" \
    --conf spark.sql.extensions=org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions \
    --conf spark.sql.catalog.lake=org.apache.iceberg.spark.SparkCatalog \
    --conf spark.sql.catalog.lake.type=hive \
    --conf spark.sql.catalog.lake.uri="${HIVE_METASTORE_URI:-thrift://hive-metastore:9083}" \
    --conf spark.sql.catalog.lake.warehouse="${ICEBERG_WAREHOUSE:-s3a://tmdb-lake/warehouse}" \
    -e "${statements}"

log "Iceberg 命名空间初始化完成"
