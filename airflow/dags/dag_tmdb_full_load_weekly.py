"""全量采集（每周）：每日 ID 导出文件 + 逐条详情，支持断点续传。

为什么需要全量：changes 接口只覆盖"最近有变更"的实体，
对于首次接入、或历史数据缺失（作业长时间挂掉）的情况，必须靠全量补齐。

断点续传：作业把进度写入对象存储的 _state/ 目录，
重跑时从断点继续，不会因为一次网络抖动就从头再拉几十万条。
"""

from __future__ import annotations

from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.empty import EmptyOperator

from tmdbwh_common import (
    DEFAULT_ARGS,
    JAR_GOVERNANCE,
    JAR_INGESTION,
    JAR_OFFLINE,
    TAGS_PLATFORM,
    cli_task,
)

with DAG(
    dag_id="tmdb_full_load_weekly",
    description="TMDB 全量采集（每周，断点续传）",
    schedule="30 3 * * 0",
    start_date=datetime(2026, 1, 5),
    catchup=False,
    max_active_runs=1,
    dagrun_timeout=timedelta(hours=12),
    default_args=DEFAULT_ARGS,
    tags=TAGS_PLATFORM + ["ingestion", "full-load"],
    doc_md=__doc__,
) as dag:

    start = EmptyOperator(task_id="start")

    # 全量采集耗时较长（几十万条详情），超时与重试都要放宽
    full_load = cli_task(
        "full_load",
        JAR_INGESTION,
        "full --date {{ ds }}",
        retries=5,
        execution_timeout=21600,
    )

    # 全量之后必须重跑离线分层：新增的历史实体会改变维度与汇总结果
    offline_rebuild = cli_task(
        "offline_rebuild",
        JAR_OFFLINE,
        "run --date {{ ds }} --sync",
        execution_timeout=7200,
    )

    quality_gate = cli_task(
        "quality_gate",
        JAR_GOVERNANCE,
        "quality --date {{ ds }}",
    )

    end = EmptyOperator(task_id="end")

    start >> full_load >> offline_rebuild >> quality_gate >> end
