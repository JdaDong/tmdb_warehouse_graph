#!/usr/bin/env bash
# =============================================================================
# 按模块名运行平台 fat-jar：run-cli.sh <module> [args...]
#   module ∈ ingestion | graph | governance
# jar 查找路径：${TMDBWH_DIST}/jars/tmdb-<module>-*-all.jar（由 `make dist` 生成）
# 若存在 ${TMDBWH_CONFIG_FILE}（默认 /opt/tmdbwh/config/application.conf），自动以 -Dconfig.file 加载。
# =============================================================================
set -euo pipefail

DIST="${TMDBWH_DIST:-/opt/tmdbwh/dist}"
CONFIG_FILE="${TMDBWH_CONFIG_FILE:-/opt/tmdbwh/config/application.conf}"
MODULES="ingestion graph governance"

usage() {
    cat <<EOF
用法: run-cli.sh <module> [args...]
  module: ${MODULES// / | }
示例:
  run-cli.sh governance migrate
  run-cli.sh ingestion full --entity movie --max-ids 200
EOF
}

if [[ $# -eq 0 || "$1" == "-h" || "$1" == "--help" ]]; then
    usage
    exit 0
fi

module="$1"
shift
if [[ " ${MODULES} " != *" ${module} "* ]]; then
    echo "[run-cli] 未知模块: ${module}" >&2
    usage >&2
    exit 64
fi

shopt -s nullglob
jars=("${DIST}"/jars/tmdb-"${module}"-*-all.jar)
if [[ ${#jars[@]} -eq 0 ]]; then
    echo "[run-cli] 未找到 ${DIST}/jars/tmdb-${module}-*-all.jar，请先在宿主机执行: make dist" >&2
    exit 66
fi
if [[ ${#jars[@]} -gt 1 ]]; then
    echo "[run-cli] 发现多个版本的 jar，请清理 dist/jars：${jars[*]}" >&2
    exit 65
fi

# bash 3.2（macOS 默认）下空数组在 set -u 时会被视为未绑定，需先写入一个占位元素
java_args=("")
if [[ -f "${CONFIG_FILE}" ]]; then
    java_args+=("-Dconfig.file=${CONFIG_FILE}")
fi

# shellcheck disable=SC2086  # JAVA_OPTS 需按空格拆分为多个参数
exec java ${JAVA_OPTS:-} "${java_args[@]}" -jar "${jars[0]}" "$@"
