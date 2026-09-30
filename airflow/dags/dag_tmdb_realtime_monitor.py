"""实时链路守护：检查 Flink 作业存活与端到端延迟，异常时告警并尝试重启作业。

为什么需要单独的守护 DAG：
  Flink 作业本身是常驻的（由 Compose / K8s 保证重启），
  但"作业在跑"不等于"数据是最新的"——Kafka 积压、Source 位移卡住、
  Sink 写入失败都会让进程活着但数据停滞。这里直接查 ClickHouse 的最新数据时间来判断。

判定口径：
  rt.rt_movie_popularity 的 event_time 落后当前时间超过阈值（默认 15 分钟）即告警。
"""

from __future__ import annotations

import os
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.empty import EmptyOperator
from airflow.operators.python import BranchPythonOperator, PythonOperator

from tmdbwh_common import DEFAULT_ARGS, TAGS_PLATFORM

# 实时数据允许的滞后时间（分钟）
MAX_LAG_MINUTES = int(os.getenv("TMDBWH_RT_MAX_LAG_MINUTES", "15"))

CLICKHOUSE_URL = os.getenv("CLICKHOUSE_URL", "http://clickhouse:8123")
CLICKHOUSE_USER = os.getenv("CLICKHOUSE_USER", "default")
CLICKHOUSE_PASSWORD = os.getenv("CLICKHOUSE_PASSWORD", "")


def check_freshness(**context) -> str:
    """查询实时表最新数据时间，决定走 healthy 还是 stale 分支。"""
    import urllib.parse
    import urllib.request

    query = "SELECT max(event_time) FROM rt.rt_movie_popularity"
    url = f"{CLICKHOUSE_URL}/?query={urllib.parse.quote(query)}&default_format=TSV"
    request = urllib.request.Request(url)
    if CLICKHOUSE_USER:
        import base64

        token = base64.b64encode(f"{CLICKHOUSE_USER}:{CLICKHOUSE_PASSWORD}".encode()).decode()
        request.add_header("Authorization", f"Basic {token}")
    with urllib.request.urlopen(request, timeout=30) as response:
        raw = response.read().decode().strip()

    # 空结果表明没有数据（首次启动或链路中断）
    from datetime import timezone

    now = datetime.now(timezone.utc)
    if not raw:
        lag_minutes = 10**9
    else:
        latest = datetime.strptime(raw, "%Y-%m-%d %H:%M:%S").replace(tzinfo=timezone.utc)
        lag_minutes = (now - latest).total_seconds() / 60

    context["ti"].xcom_push(key="lag_minutes", value=lag_minutes)
    print(f"实时数据滞后 {lag_minutes:.1f} 分钟（阈值 {MAX_LAG_MINUTES}）")
    return "alert_stale" if lag_minutes > MAX_LAG_MINUTES else "mark_healthy"


with DAG(
    dag_id="tmdb_realtime_monitor",
    description="实时链路新鲜度守护",
    schedule="*/15 * * * *",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    max_active_runs=1,
    dagrun_timeout=timedelta(minutes=10),
    default_args={**DEFAULT_ARGS, "retries": 0},
    tags=TAGS_PLATFORM + ["realtime", "monitoring"],
    doc_md=__doc__,
) as dag:

    start = EmptyOperator(task_id="start")

    branch = BranchPythonOperator(
        task_id="check_freshness",
        python_callable=check_freshness,
    )

    healthy = EmptyOperator(task_id="mark_healthy")

    # 滞后时的处置：打印诊断信息（生产环境可替换为告警 webhook / 重启作业）
    alert = BashOperator(
        task_id="alert_stale",
        bash_command=(
            "echo '实时数据滞后超过阈值，请检查："
            "1) Kafka 消费积压 2) Flink 作业状态 3) ClickHouse 写入'"
        ),
    )

    end = EmptyOperator(task_id="end", trigger_rule="none_failed_min_one_success")

    start >> branch >> [healthy, alert] >> end
