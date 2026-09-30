#!/usr/bin/env bash
# =============================================================================
# 脚本公共库：日志、docker compose 封装、服务就绪等待
# 用法: source "$(dirname "$0")/../lib/common.sh"   （注意按调用脚本位置调整相对路径）
# =============================================================================
set -euo pipefail

# 项目根目录（本文件位于 <root>/scripts/lib/）
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COMPOSE_DIR="${REPO_ROOT}/deploy/compose"
COMPOSE_FILE="${COMPOSE_DIR}/docker-compose.yml"
ENV_FILE="${ENV_FILE:-${REPO_ROOT}/.env}"

# 启用的 profile 列表（逗号分隔）；make 调用时传入，默认最小组合
PROFILES="${PROFILES:-core,olap,graph,compute,mock}"

if [ -t 1 ]; then
    C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_RED=$'\033[31m'; C_OFF=$'\033[0m'
else
    C_GREEN=""; C_YELLOW=""; C_RED=""; C_OFF=""
fi

log()  { printf '%s[INFO]%s %s\n' "${C_GREEN}" "${C_OFF}" "$*"; }
warn() { printf '%s[WARN]%s %s\n' "${C_YELLOW}" "${C_OFF}" "$*" >&2; }
err()  { printf '%s[ERROR]%s %s\n' "${C_RED}" "${C_OFF}" "$*" >&2; }
die()  { err "$*"; exit 1; }

have() { command -v "$1" >/dev/null 2>&1; }

# docker compose 统一入口：自动带上 env-file 与 -f
compose() {
    docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" "$@"
}

# 组装 --profile 参数
compose_profiles_args() {
    local out=() p
    IFS=',' read -ra out <<<"${PROFILES}"
    for p in "${out[@]}"; do
        [ -n "${p}" ] && printf -- '--profile %s ' "${p}"
    done
}

# 服务进入 running 状态（compose ps --status=running --services 判定）
wait_for_service() {
    local service="$1" max="${2:-60}" n=0
    while [ "${n}" -lt "${max}" ]; do
        if compose ps --status=running --services 2>/dev/null | grep -qx "${service}"; then
            log "服务已就绪: ${service}"
            return 0
        fi
        n=$((n + 1))
        sleep 5
    done
    err "等待服务超时: ${service}（${max} 次尝试）"
    return 1
}

# 通用重试：retry <次数> <间隔秒> <命令...>
retry() {
    local attempts="$1" delay="$2"; shift 2
    local n=1
    until "$@"; do
        if [ "${n}" -ge "${attempts}" ]; then
            err "命令重试 ${attempts} 次仍失败: $*"
            return 1
        fi
        warn "第 ${n}/${attempts} 次失败，${delay}s 后重试: $*"
        n=$((n + 1))
        sleep "${delay}"
    done
}

# 定位某个模块的 fat-jar；找不到时给出明确的后续操作提示（不静默失败）
find_jar() {
    local module="$1"
    local jar
    jar="$(ls "${REPO_ROOT}"/dist/jars/tmdb-"${module}"-*-all.jar 2>/dev/null | head -1)"
    if [ -z "${jar}" ]; then
        return 1
    fi
    printf '%s' "${jar}"
}

# 在容器内执行平台 CLI：run_cli <module> [args...]
run_cli() {
    local module="$1"; shift
    compose run --rm --build --profile tools cli "${module}" "$@"
}

# 读取 .env 中的某个键（不依赖 docker compose）
env_get() {
    local key="$1"
    if [ -f "${ENV_FILE}" ]; then
        sed -n "s/^${key}=\(.*\)\$/\1/p" "${ENV_FILE}" | tail -n 1
    fi
}

# 生成随机口令：random_secret [长度]
random_secret() {
    local len="${1:-32}"
    if have openssl; then
        openssl rand -hex "$((len / 2 + 1))" | head -c "${len}"
    elif have python3; then
        python3 -c "import secrets,string;print(''.join(secrets.choice(string.ascii_lowercase+string.digits) for _ in range(${len})))"
    else
        LC_ALL=C tr -dc 'a-z0-9' </dev/urandom | head -c "${len}"
    fi
}
