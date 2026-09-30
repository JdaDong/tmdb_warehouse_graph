# =============================================================================
# Airflow 2.9.2 自定义镜像
#
# 执行方式（DAG 中的公共任务构造会自动适配）：
#   - Java CLI（采集 / 治理 / 图谱）：镜像内置 JRE 17，直接 java -jar /opt/tmdbwh/dist/jars/*.jar
#   - Spark 作业：调用 Spark Standalone REST 提交接口（spark-master:6066，cluster 模式），无需内置 Spark 发行包
#   - Flink 作业：调用 Flink REST API（flink-jobmanager:8081）上传并运行 jar
# 这样镜像体积保持在最小，同时与 K8s 模式（SparkApplication / FlinkDeployment CRD）语义一致。
# =============================================================================
ARG AIRFLOW_IMAGE=apache/airflow:2.9.2-python3.11
FROM ${AIRFLOW_IMAGE}

USER root
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends openjdk-17-jre-headless; \
    apt-get clean; \
    rm -rf /var/lib/apt/lists/*; \
    ln -sfn "$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")" /opt/java-home; \
    /opt/java-home/bin/java -version; \
    mkdir -p /opt/tmdbwh/dist /opt/tmdbwh/config; \
    chown -R airflow:root /opt/tmdbwh

USER airflow
ARG AIRFLOW_VERSION=2.9.2
ARG PYTHON_VERSION=3.11
COPY --chown=airflow:root requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir \
        --constraint "https://raw.githubusercontent.com/apache/airflow/constraints-${AIRFLOW_VERSION}/constraints-${PYTHON_VERSION}.txt" \
        -r /tmp/requirements.txt

# /opt/java-home 为架构无关的软链接（amd64 / arm64 下 JDK 实际目录名不同）
ENV TMDBWH_DIST=/opt/tmdbwh/dist \
    JAVA_HOME=/opt/java-home
