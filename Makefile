# =============================================================================
# TMDB Warehouse & Graph Platform - 统一命令入口
#
#   make help                 查看全部命令
#   make build                编译 + 规范检查 + 单元测试 + 覆盖率门禁 + 打包
#   make build MVN_MIRROR=cn  使用国内镜像（无法直连 Maven Central 时）
# =============================================================================
SHELL := /bin/bash
.DEFAULT_GOAL := help

# ---- JDK：Spark 3.5 / Flink 1.18 需要 JDK 11~17 ----
# macOS 下自动探测 JDK 17（其次 11），即使当前 JAVA_HOME 指向更高版本也会被替换；
# 其他系统或需要指定时：make build BUILD_JAVA_HOME=/path/to/jdk17
ifeq ($(shell uname -s),Darwin)
  BUILD_JAVA_HOME ?= $(shell /usr/libexec/java_home -v 17 2>/dev/null || /usr/libexec/java_home -v 11 2>/dev/null)
endif
ifneq ($(BUILD_JAVA_HOME),)
  export JAVA_HOME := $(BUILD_JAVA_HOME)
endif

# ---- Maven ----
MVN        ?= mvn
MVN_MIRROR ?=
MVN_OPTS   := -B
ifeq ($(MVN_MIRROR),cn)
  MVN_OPTS += -s config/maven-settings-cn.xml
endif
MODULE     ?=
ifneq ($(MODULE),)
  MVN_OPTS += -pl $(MODULE) -am
endif

.PHONY: help
help: ## 显示帮助
	@awk 'BEGIN {FS = ":.*##"; printf "\n用法: make \033[36m<target>\033[0m [MODULE=tmdb-common] [MVN_MIRROR=cn]\n\n"} \
		/^[a-zA-Z0-9_-]+:.*?##/ { printf "  \033[36m%-18s\033[0m %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
	@echo ""

.PHONY: env
env: ## 打印构建环境
	@echo "JAVA_HOME=$(JAVA_HOME)"; "$${JAVA_HOME:+$$JAVA_HOME/bin/}java" -version 2>&1 | head -1; $(MVN) -v | head -1

# ============================== 构建与测试 ==============================
.PHONY: build
build: ## 编译 + Checkstyle + 单元测试 + JaCoCo 门禁 + 打包 fat-jar
	$(MVN) $(MVN_OPTS) clean verify

.PHONY: package
package: ## 仅打包（跳过测试与规范检查，本地调试用）
	$(MVN) $(MVN_OPTS) -Pfast clean package

.PHONY: test
test: ## 运行单元测试
	$(MVN) $(MVN_OPTS) test

.PHONY: it
it: ## 运行单元测试 + Testcontainers 集成测试（需要 Docker）
	$(MVN) $(MVN_OPTS) -Pit verify

.PHONY: lint
lint: ## 代码规范检查（Checkstyle）
	$(MVN) $(MVN_OPTS) checkstyle:check

.PHONY: fmt
fmt: ## 自动格式化 Java 代码（google-java-format AOSP 风格）
	$(MVN) $(MVN_OPTS) spotless:apply

.PHONY: fmt-check
fmt-check: ## 检查格式（不修改文件）
	$(MVN) $(MVN_OPTS) spotless:check

.PHONY: coverage
coverage: ## 生成覆盖率报告并打印各模块行覆盖率
	$(MVN) $(MVN_OPTS) verify -DskipITs
	@for f in */target/site/jacoco/jacoco.csv; do \
		awk -F, -v m="$${f%%/*}" 'NR>1 {miss+=$$8; cov+=$$9} END {if (miss+cov>0) printf "  %-18s 行覆盖率 %.1f%%\n", m, 100*cov/(miss+cov)}' $$f; \
	done

.PHONY: clean
clean: ## 清理构建产物
	$(MVN) $(MVN_OPTS) clean

# ============================== 本地环境 ==============================
# profile 组合：core / olap / graph / compute / mock / orchestration / monitoring / governance / tools
PROFILES  ?= core,olap,graph,compute,mock
MINIMAL   ?= core,olap,graph,compute,mock
WAIT_SECS ?= 600
SVC       ?=

.PHONY: validate-config
validate-config: ## 校验 compose / XML / JSON / YAML / Mock 引用 / 脚本语法（无需 Docker）
	scripts/validate-config.sh

.PHONY: env-init
env-init: ## 由 .env.example 生成 .env（自动填充随机口令）
	@if [ -f .env ]; then echo ".env 已存在，保持不变（如需重建请先 rm .env）"; else \
		cp .env.example .env && chmod 600 .env && \
		perl -pi -e 's/CHANGE_ME/"".join(map { ("a".."z","0".."9")[rand(36)] } 1..32)/ge' .env && \
		KEY=$$(python3 -c "from cryptography.fernet import Fernet;print(Fernet.generate_key().decode())" 2>/dev/null || true); \
		[ -n "$$KEY" ] || KEY=$$(openssl rand -base64 32); \
		perl -pi -e "s{^AIRFLOW_FERNET_KEY=.*}{AIRFLOW_FERNET_KEY=$$KEY}" .env && \
		echo ".env 已生成"; \
	fi

.PHONY: dist
dist: ## 构建各模块 fat-jar 到 dist/jars
	$(MVN) $(MVN_OPTS) -DskipTests -Pfast clean package
	@mkdir -p dist/jars && rm -f dist/jars/*.jar
	@for j in tmdb-*/target/tmdb-*-all.jar; do cp "$$j" dist/jars/; done
	@ls -1 dist/jars

.PHONY: up
up: env-init dist ## 启动（默认最小组合；PROFILES=... 可覆盖）
	PROFILES="$(PROFILES)" scripts/bootstrap.sh

.PHONY: up-minimal
up-minimal: ## 启动最小可用组合（core,olap,graph,compute,mock）
	PROFILES="$(MINIMAL)" scripts/bootstrap.sh

.PHONY: wait
wait: ## 等待服务健康（最多 WAIT_SECS 秒）
	docker compose --env-file .env -f deploy/compose/docker-compose.yml \
		$(foreach p,$(PROFILES),--profile $(p)) \
		up -d --wait --wait-timeout $(WAIT_SECS)

.PHONY: ps
ps: ## 查看服务状态
	docker compose --env-file .env -f deploy/compose/docker-compose.yml \
		--profile core --profile olap --profile graph --profile compute --profile mock \
		--profile orchestration --profile monitoring --profile governance ps

.PHONY: logs
logs: ## 查看日志（SVC=服务名）
	docker compose --env-file .env -f deploy/compose/docker-compose.yml logs -f --tail=200 $(SVC)

.PHONY: cli
cli: ## 运行平台 CLI：make cli CLI_ARGS='governance migrate'
	docker compose --env-file .env -f deploy/compose/docker-compose.yml run --rm --build --profile tools \
		cli $(CLI_ARGS)

.PHONY: mock-reset
mock-reset: ## 重置 TMDB Mock 的限流/故障场景状态
	docker compose --env-file .env -f deploy/compose/docker-compose.yml run --rm --profile mock \
		--entrypoint sh wiremock -c "wget -q -O- --post-data '' http://localhost:8080/__admin/scenarios/reset >/dev/null && echo 'scenarios reset'"

.PHONY: init
init: ## 初始化 Iceberg / ClickHouse / Neo4j
	scripts/init/init-iceberg.sh
	scripts/init/init-clickhouse.sh
	scripts/init/init-neo4j.sh

.PHONY: om-ingest
om-ingest: ## 触发 OpenMetadata 元数据采集（需先配置 OM_JWT_TOKEN）
	scripts/init/init-openmetadata.sh

.PHONY: down
down: ## 停止容器（保留数据卷）
	scripts/teardown.sh

.PHONY: purge
purge: ## 停止并删除数据卷（数据不可恢复）
	scripts/teardown.sh --purge

# ============================== 运行（在 Compose 环境内执行）==============================
DATE ?=
MODE ?= incremental
JOB  ?= popularity-trend
SUB  ?= migrate
ARGS ?=

.PHONY: ingest
ingest: ## 采集：make ingest MODE=incremental DATE=2026-09-30
	scripts/run/run-ingestion.sh $(MODE) $(DATE)

.PHONY: offline
offline: ## 离线链路：make offline DATE=2026-09-30 SYNC=--sync
	scripts/run/run-offline.sh $(DATE) $(SYNC)

.PHONY: realtime
realtime: ## 实时作业：make realtime JOB=entity-change|popularity-trend|list
	scripts/run/submit-flink.sh $(JOB)

.PHONY: graph
graph: ## 图任务：make graph SUB=stats ARGS='--table x'
	scripts/run/load-graph.sh $(SUB) $(ARGS)

.PHONY: governance
governance: ## 治理任务：make governance SUB=quality ARGS=2026-09-30
	scripts/run/governance.sh $(SUB) $(ARGS)

.PHONY: smoke
smoke: ## 端到端冒烟：make smoke [DATE=2026-09-30]
	scripts/smoke.sh $(DATE)

.PHONY: helm-lint
helm-lint: ## 校验 Helm Chart（需要 helm CLI）
	helm lint deploy/helm/tmdbwh

.PHONY: ci
ci: build validate-config ## CI 等价流程：构建 + 静态校验
