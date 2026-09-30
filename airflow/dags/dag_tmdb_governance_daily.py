"""每日治理：指标口径校验 → 血缘落库 → 生命周期（dry-run）→ 成本分析。

注意生命周期任务默认是 dry-run：
  TTL 与分区清理会真的删数据，自动化任务只生成计划并写入治理表，
  由运维在治理看板上确认后再手动执行（governance lifecycle --apply）。
  这条边界很重要——历史上"自动清理误删"的事故基本都源于把不可逆操作做成了定时任务。
"""

from __future__ import annotations

from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.empty import EmptyOperator

from tmdbwh_common import (
    DEFAULT_ARGS,
    JAR_GOVERNANCE,
    TAGS_PLATFORM,
    cli_task,
)

with DAG(
    dag_id="tmdb_governance_daily",
    description="数据治理日常任务",
    schedule="0 5 * * *",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    max_active_runs=1,
    dagrun_timeout=timedelta(hours=2),
    default_args=DEFAULT_ARGS,
    tags=TAGS_PLATFORM + ["governance"],
    doc_md=__doc__,
) as dag:

    start = EmptyOperator(task_id="start")

    # 指标口径校验：表 / 字段是否与定义一致（表结构变更后最容易出问题）
    metrics_validate = cli_task(
        "metrics_validate",
        JAR_GOVERNANCE,
        "metrics --validate",
    )

    lineage_persist = cli_task(
        "lineage_persist",
        JAR_GOVERNANCE,
        "lineage --persist",
    )

    # dry-run：只生成计划并写治理表，不执行 DDL
    lifecycle_plan = cli_task(
        "lifecycle_plan",
        JAR_GOVERNANCE,
        "lifecycle",
    )

    quality_check = cli_task(
        "quality_check",
        JAR_GOVERNANCE,
        "quality --date {{ ds }}",
        # 治理 DAG 里的质量检查只做记录，不阻断（阻断发生在离线链路 DAG）
        retries=1,
    )

    end = EmptyOperator(task_id="end")

    start >> [metrics_validate, lineage_persist] >> lifecycle_plan >> quality_check >> end
