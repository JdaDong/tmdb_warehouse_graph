"""离线主链路（每日）：增量采集 → 离线分层 → ClickHouse 同步 → 质量门禁 → 图装载。

调度策略：
  - 每日 02:10（UTC）触发，处理"昨天"的业务日期（data_interval_start）；
  - catchup=True：缺失的日期会自动补跑（离线链路幂等，补跑安全）；
  - max_active_runs=1：避免多个业务日期并发写同一张维度表。

依赖顺序里最关键的一环是质量门禁：
  上游采集可能只拉到一半数据（例如 TMDB 限流），直接跑分层会让"半份数据"被当成完整数据。
  因此采集后先做行数 / 及时性检查，BLOCKER 失败则阻断下游。
"""

from __future__ import annotations

from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.empty import EmptyOperator

from tmdbwh_common import (
    DEFAULT_ARGS,
    JAR_GRAPH,
    JAR_GOVERNANCE,
    JAR_INGESTION,
    JAR_OFFLINE,
    TAGS_PLATFORM,
    cli_task,
)

with DAG(
    dag_id="tmdb_offline_daily",
    description="TMDB 离线数仓每日全链路",
    schedule="10 2 * * *",
    start_date=datetime(2026, 1, 1),
    catchup=True,
    max_active_runs=1,
    dagrun_timeout=timedelta(hours=4),
    default_args=DEFAULT_ARGS,
    tags=TAGS_PLATFORM + ["offline"],
    doc_md=__doc__,
) as dag:

    start = EmptyOperator(task_id="start")

    # 1) 增量采集：changes 接口 + 水位线推进，结果落对象存储并投递 Kafka
    ingest_incremental = cli_task(
        "ingest_incremental",
        JAR_INGESTION,
        "incremental --date {{ ds }}",
        retries=3,
    )

    # 2) 热度榜单轮询：为实时链路与"飙升检测"提供观测数据
    ingest_popularity = cli_task(
        "ingest_popularity",
        JAR_INGESTION,
        "popularity --date {{ ds }}",
        retries=3,
    )

    # 3) 离线分层：ODS → DWD → DWS → ADS，并同步 ClickHouse
    offline_layers = cli_task(
        "offline_layers",
        JAR_OFFLINE,
        "run --date {{ ds }} --sync",
        execution_timeout=7200,
    )

    # 4) 质量门禁：BLOCKER 失败时任务以非 0 退出，阻断下游图装载
    quality_gate = cli_task(
        "quality_gate",
        JAR_GOVERNANCE,
        "quality --date {{ ds }}",
    )

    # 5) 图装载：MERGE 幂等，失败可安全重跑
    graph_load = cli_task(
        "graph_load",
        JAR_GRAPH,
        "load",
    )

    # 6) 血缘落库：记录本次链路的上下游关系，供影响分析使用
    lineage_persist = cli_task(
        "lineage_persist",
        JAR_GOVERNANCE,
        "lineage --persist",
    )

    end = EmptyOperator(task_id="end")

    start >> [ingest_incremental, ingest_popularity] >> offline_layers
    offline_layers >> quality_gate >> graph_load >> lineage_persist >> end
