#!/usr/bin/env bash
# =============================================================================
# PostgreSQL 初始化：为各组件创建独立的库与账号（最小权限，互不共享）
#   metastore       ← Hive Metastore（Iceberg Catalog）
#   airflow         ← Airflow 元数据库
#   openmetadata_db ← OpenMetadata（可选，governance profile）
#
# 注意：docker-entrypoint-initdb.d 下的脚本只在数据卷首次初始化时执行。
#       修改密码后需同步执行 ALTER ROLE，或 make purge 重建数据卷。
# =============================================================================
set -euo pipefail

require_safe() {
    # 密码与标识符只允许安全字符，避免 SQL 注入（make env-init 生成的随机密码满足该约束）
    if [[ ! "$2" =~ ^[A-Za-z0-9_-]{8,128}$ ]]; then
        echo "[initdb] $1 含非法字符或长度不足 8 位（仅允许 [A-Za-z0-9_-]）" >&2
        exit 1
    fi
}

create_db() {
    local role="$1" password="$2" database="$3"
    require_safe "${role} 密码" "${password}"
    psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname postgres <<-EOSQL
		DO \$\$
		BEGIN
		    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '${role}') THEN
		        CREATE ROLE ${role} LOGIN PASSWORD '${password}';
		    ELSE
		        ALTER ROLE ${role} WITH LOGIN PASSWORD '${password}';
		    END IF;
		END
		\$\$;
	EOSQL
    if ! psql -tA --username "${POSTGRES_USER}" --dbname postgres \
            -c "SELECT 1 FROM pg_database WHERE datname = '${database}'" | grep -q 1; then
        psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname postgres \
            -c "CREATE DATABASE ${database} OWNER ${role} ENCODING 'UTF8' TEMPLATE template0"
    fi
    psql -v ON_ERROR_STOP=1 --username "${POSTGRES_USER}" --dbname "${database}" \
        -c "GRANT ALL ON SCHEMA public TO ${role}"
    echo "[initdb] 已就绪: database=${database} owner=${role}"
}

create_db hive "${HMS_DB_PASSWORD:?}" metastore
create_db airflow "${AIRFLOW_DB_PASSWORD:?}" airflow
create_db openmetadata "${OM_DB_PASSWORD:?}" openmetadata_db
