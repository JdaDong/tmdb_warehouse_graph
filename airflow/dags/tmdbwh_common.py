"""TMDB 数仓平台 DAG 公共定义。

放在 dags 目录之外的命名约定：本文件不定义 DAG（无 dag 对象），仅提供
任务构造与常量，供各 DAG 复用，避免每个 DAG 各自拼命令导致口径漂移。

为什么用 BashOperator 而不是 DockerOperator / KubernetesPodOperator：
  - 本地与演示环境用 Compose 部署，Airflow 镜像内已安装 JDK 与平台 jar；
  - 生产 K8s 环境只需把 tmdbwh_cmd 换成 KubernetesPodOperator 即可，
    任务依赖结构不用改（见 docs/07-deployment.md）。
"""

from __future__ import annotations

import os
from typing import List

from airflow.operators.bash import BashOperator

# ---- 平台 jar 位置（由 airflow/Dockerfile 构建时拷贝）----
DIST_DIR = os.getenv("TMDBWH_DIST", "/opt/tmdbwh/dist")
JAVA_BIN = os.getenv("TMDBWH_JAVA", "java")
JAVA_OPTS = os.getenv("TMDBWH_JAVA_OPTS", "-Xmx1g")

# ---- 各模块 jar ----
JAR_INGESTION = f"{DIST_DIR}/ingestion.jar"
JAR_OFFLINE = f"{DIST_DIR}/offline.jar"
JAR_REALTIME = f"{DIST_DIR}/realtime.jar"
JAR_GRAPH = f"{DIST_DIR}/graph.jar"
JAR_GOVERNANCE = f"{DIST_DIR}/governance.jar"

# ---- 默认参数 ----
DEFAULT_ARGS = {
    "owner": "data-platform",
    "depends_on_past": False,
    # 离线链路重跑是幂等的（分区覆盖 + ReplacingMergeTree），因此可以安全重试
    "retries": 2,
    "retry_delay": 300,
    "retry_exponential_backoff": True,
    "max_retry_delay": 1800,
    "email_on_failure": False,
    "email_on_retry": False,
}

# 标签：便于在 Airflow UI 按主题筛选
TAGS_PLATFORM: List[str] = ["tmdbwh", "platform"]


def tmdbwh_cmd(jar: str, args: str) -> str:
    """构造平台 CLI 命令。

    :param jar: jar 绝对路径
    :param args: CLI 参数（可含 Jinja 模板，如 --date {{ ds }}）
    """
    return f"{JAVA_BIN} {JAVA_OPTS} -jar {jar} {args}"


def cli_task(task_id: str, jar: str, args: str, **kwargs) -> BashOperator:
    """构造一个执行平台 CLI 的 BashOperator。

    统一设置：
      - append_env=True：让容器内的 Kafka / ClickHouse / MinIO 等变量生效；
      - execution_timeout：避免作业卡死占着 slot 不放；
      - trigger_rule 默认 all_success（保持"上游失败就不跑"）。
    """
    kwargs.setdefault("append_env", True)
    kwargs.setdefault("execution_timeout", 3600)
    return BashOperator(
        task_id=task_id,
        bash_command=tmdbwh_cmd(jar, args),
        **kwargs,
    )
