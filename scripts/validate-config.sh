#!/usr/bin/env bash
# =============================================================================
# 配置与 Mock 数据静态校验（不需要 Docker，可在 CI 中作为 lint 步骤）
#   1. compose 文件结构与引用完整性（依赖、卷、构建上下文、挂载源）
#   2. XML 格式（ClickHouse / Keeper / Hadoop / Hive）
#   3. JSON 格式（含 WireMock 模板：把 {{{...}}} 替换为 1 之后再解析）
#   4. YAML 格式（Prometheus / 告警规则 / Grafana provisioning / OpenMetadata 工作流）
#   5. WireMock：mapping 引用的 ID 与 bodyFileName 必须存在，且 ID 需出现在每日导出文件中
#   6. Shell 脚本语法（bash -n）
# 用法: scripts/validate-config.sh
# =============================================================================
set -uo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "$0")/lib/common.sh"

failures=0
check() { # check <说明> <命令...>
    local desc="$1"; shift
    if "$@" >/dev/null 2>&1; then
        log "OK   ${desc}"
    else
        err "FAIL ${desc}"
        failures=$((failures + 1))
    fi
}

log "1) compose 结构与引用完整性"
check "docker-compose.yml 结构" python3 - "${COMPOSE_FILE}" <<'PY'
import sys, yaml, os
d = yaml.safe_load(open(sys.argv[1]))
svcs, vols = d['services'], set(d.get('volumes') or {})
bad = []
for n, s in svcs.items():
    for dep in (s.get('depends_on') or {}):
        if dep not in svcs:
            bad.append(f'{n} -> unknown {dep}')
    for v in s.get('volumes') or []:
        src = v.split(':')[0]
        if src.startswith(('./', '../')):
            if not os.path.exists(os.path.normpath(os.path.join(os.path.dirname(sys.argv[1]), src))):
                bad.append(f'{n} mount source missing: {src}')
        elif not src.startswith('/') and src not in vols:
            bad.append(f'{n} undeclared volume {src}')
    b = s.get('build')
    if b and not os.path.isdir(os.path.normpath(os.path.join(os.path.dirname(sys.argv[1]), b['context']))):
        bad.append(f'{n} build context missing')
for b in bad:
    print(b, file=sys.stderr)
sys.exit(1 if bad else 0)
PY

log "2) XML / JSON / YAML 格式"
while IFS= read -r f; do check "xml  $(basename "$f")" xmllint --noout "$f"; done < <(find "${COMPOSE_DIR}/conf" -name '*.xml')
while IFS= read -r f; do check "yaml $(basename "$f")" python3 -c "import yaml,sys;yaml.safe_load(open(sys.argv[1]))" "$f"; done \
    < <(find "${COMPOSE_DIR}/conf" -name '*.yml' -o -name '*.yaml')
while IFS= read -r f; do
    check "json $(basename "$f")" python3 -c '
import sys, json, re
t = open(sys.argv[1], encoding="utf-8").read()
if "{{" in t:
    # WireMock 模板：{{{raw}}} 与 {{escaped}} 都替换为 1 后再解析
    s = re.sub(r"\{\{\{.*?\}\}\}", "1", t)
    s = re.sub(r"\{\{.*?\}\}", "1", s)
    if "{{" in s:
        sys.exit(1)                 # 模板未闭合
else:
    s = t
json.loads(s)
' "$f"
done < <(find "${COMPOSE_DIR}/conf" -name '*.json')

log "3) NDJSON 导出文件"
while IFS= read -r f; do
    check "ndjson $(basename "$f")" python3 -c "
import sys,json
for line in open(sys.argv[1]):
    json.loads(line)
" "$f"
done < <(find "${COMPOSE_DIR}/conf/wiremock/__files" -name '*.ndjson')

log "4) WireMock 引用完整性"
check "mapping 引用的 ID / 文件齐全，且 ID 出现在导出文件中" python3 - "${COMPOSE_DIR}/conf/wiremock" <<'PY'
import sys, os, re, glob, json
base = sys.argv[1]
bad = []
ids = {}
for p in glob.glob(base + '/mappings/*.json'):
    for kind, group in re.findall(r'"urlPathPattern"\s*:\s*"/3/(movie|tv|person|company|collection)/\(([\d|]+)\)"', open(p).read()):
        ids.setdefault(kind, set()).update(group.split('|'))
    for fn in re.findall(r'"bodyFileName"\s*:\s*"([^"]+)"', open(p).read()):
        if '{{' not in fn and not os.path.isfile(f'{base}/__files/{fn}'):
            bad.append(f'missing body file {fn}')
export = {'movie': 'movie_ids', 'tv': 'tv_series_ids', 'person': 'person_ids',
          'company': 'production_company_ids', 'collection': 'collection_ids'}
for kind, group in ids.items():
    for i in group:
        if not os.path.isfile(f'{base}/__files/{kind}/{i}.json'):
            bad.append(f'missing fixture {kind}/{i}.json')
        have = {json.loads(l)['id'] for l in open(f'{base}/__files/exports/{export[kind]}.ndjson')}
        if int(i) not in have:
            bad.append(f'{kind}/{i} not listed in {export[kind]} export')
for b in bad:
    print(b, file=sys.stderr)
sys.exit(1 if bad else 0)
PY

log "5) Shell 脚本语法"
while IFS= read -r f; do check "bash -n $(basename "$f")" bash -n "$f"; done \
    < <(find "${REPO_ROOT}/scripts" "${COMPOSE_DIR}/../docker" -type f -name '*.sh')

echo
if [ "${failures}" -gt 0 ]; then
    die "校验失败，共 ${failures} 项"
fi
log "全部校验通过"
