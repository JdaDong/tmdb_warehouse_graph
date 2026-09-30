#!/usr/bin/env bash
# =============================================================================
# 一键启动：环境检查 → 生成 .env → 构建 jar → 启动容器 → 初始化 → 打印访问地址
#   scripts/bootstrap.sh               启动最小组合（core,olap,graph,compute,mock）
#   PROFILES=...,orchestration,monitoring scripts/bootstrap.sh
#   SKIP_BUILD=1 scripts/bootstrap.sh  跳过 Maven 构建（已有 dist/jars 时）
# =============================================================================
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

log "=== TMDB Warehouse 平台启动 ==="
log "仓库根目录: ${REPO_ROOT}"

# ---------------- 1. 环境检查 ----------------
log "检查依赖"
have docker || die "未找到 docker：请安装 Docker Desktop（https://www.docker.com/products/docker-desktop/）"
docker compose version >/dev/null 2>&1 || die "未找到 'docker compose'（需要 Compose v2 插件）"

if ! docker info >/dev/null 2>&1; then
    die "Docker 守护进程不可用，请先启动 Docker Desktop"
fi

if have /usr/libexec/java_home; then
    JAVA_17="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    [ -n "${JAVA_17}" ] || warn "未检测到 JDK 17，Maven 构建可能失败（make 会自动选择 JDK 17）"
fi
have mvn || warn "未找到 mvn，将跳过构建（需要 JDK 11~17 + Maven 3.8+）"

# 内存与磁盘提示（完整环境约需 12~16GB）
if have sysctl && [ "$(uname -s)" = "Darwin" ]; then
    MEM_GB=$(( $(sysctl -n hw.memsize) / 1024 / 1024 / 1024 ))
    log "主机内存: ${MEM_GB}GB"
    [ "${MEM_GB}" -ge 16 ] || warn "内存小于 16GB，建议只启动最小组合：make up-minimal"
fi

# ---------------- 2. 生成 .env ----------------
if [ ! -f "${ENV_FILE}" ]; then
    log "生成 ${ENV_FILE}（随机口令）"
    cp "${REPO_ROOT}/.env.example" "${ENV_FILE}"
    tmp="${ENV_FILE}.tmp"
    while IFS= read -r line; do
        case "${line}" in
            *CHANGE_ME*) line="${line//CHANGE_ME/$(random_secret 32)}" ;;
            *AIRFLOW_FERNET_KEY_PLACEHOLDER*)
                if have python3; then
                    line="AIRFLOW_FERNET_KEY=$(python3 -c 'from cryptography.fernet import Fernet;print(Fernet.generate_key().decode())' 2>/dev/null || echo "$(random_secret 32)")"
                else
                    line="AIRFLOW_FERNET_KEY=$(random_secret 32)"
                fi ;;
        esac
        printf '%s\n' "${line}"
    done < "${ENV_FILE}" > "${tmp}"
    mv "${tmp}" "${ENV_FILE}"
    chmod 600 "${ENV_FILE}"
    log "已生成 .env（已设置为仅当前用户可读）"
else
    log "已存在 ${ENV_FILE}，保持不变"
fi

# ---------------- 3. 构建 ----------------
if [ "${SKIP_BUILD:-0}" != "1" ] && have mvn; then
    log "构建各模块 fat-jar 到 dist/jars（首次较慢）"
    (cd "${REPO_ROOT}" && make dist MVN_MIRROR="${MVN_MIRROR:-}")
else
    warn "跳过构建（SKIP_BUILD=1 或缺少 mvn）"
fi

# ---------------- 4. 启动 ----------------
log "启动容器（profiles: ${PROFILES}）"
# shellcheck disable=SC2046
compose $(compose_profiles_args) up -d --build --wait --wait-timeout "${WAIT_TIMEOUT:-600}" || \
    warn "部分服务在超时时间内未达到 healthy，继续初始化（可用 make ps / make logs 排查）"

# ---------------- 5. 初始化 ----------------
log "初始化 Iceberg 命名空间"
"$(dirname "$0")/init/init-iceberg.sh" || warn "Iceberg 初始化未完成"
log "初始化 ClickHouse"
"$(dirname "$0")/init/init-clickhouse.sh" || warn "ClickHouse 初始化未完成"
log "初始化 Neo4j 约束"
"$(dirname "$0")/init/init-neo4j.sh" || warn "Neo4j 初始化未完成"

# ---------------- 6. 输出 ----------------
cat <<EOF

${C_GREEN}=== 启动完成 ===${C_OFF}

  组件           地址                                     账号
  ------------------------------------------------------------------------
  MinIO 控制台    http://localhost:9001                    $(env_get S3_ACCESS_KEY)
  Kafka          localhost:9095（容器内 kafka:9092）
  ClickHouse     http://localhost:8123 / native:9002       $(env_get CLICKHOUSE_USER)
  Neo4j          http://localhost:7474  bolt://7687        $(env_get NEO4J_USER)
  TMDB Mock      http://localhost:8089/__admin/mappings    （TMDB_MOCK_MODE=true 时生效）
  Spark Master   http://localhost:8085
  Spark History  http://localhost:18080
  Flink UI       http://localhost:8081
  Grafana        http://localhost:3000                     admin / admin
  Prometheus     http://localhost:9090

  常用命令：
    make ps                 查看服务状态
    make logs SVC=clickhouse 查看日志
    make cli CLI_ARGS='governance migrate'  运行平台命令行工具
    scripts/teardown.sh     停止并清理
EOF
