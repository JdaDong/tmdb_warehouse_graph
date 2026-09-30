# =============================================================================
# OpenMetadata ingestion 运行容器（可选组件，governance profile）
#
# 用途：把 ClickHouse 的库 / 表 / 字段元数据采集进 OpenMetadata，与自研治理引擎互补：
#   自研引擎负责 质量 / 标准 / 生命周期 / 权限 / 成本 的执行与门禁；
#   OpenMetadata 负责 交互式元数据目录、术语表、数据发现。
# 运行前需在 OpenMetadata UI（设置 → Bots → ingestion-bot）生成 JWT 并填入 .env 的 OM_JWT_TOKEN。
# =============================================================================
FROM python:3.10-slim

ARG OM_INGESTION_VERSION=1.4.1.0
ARG PIP_INDEX_URL=https://pypi.org/simple

RUN set -eux; \
    pip install --no-cache-dir -i ${PIP_INDEX_URL} \
        "openmetadata-ingestion[clickhouse]==${OM_INGESTION_VERSION}"; \
    metadata --version

COPY --chmod=755 run-ingest.sh /opt/om/bin/run-ingest.sh

ENTRYPOINT ["/opt/om/bin/run-ingest.sh"]
