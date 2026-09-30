# =============================================================================
# Spark 3.5.1 运行时镜像（Standalone Master / Worker / History Server 共用）
#
# 预置依赖（版本与父 pom.xml 严格一致）：
#   - Iceberg Spark Runtime 1.5.2        湖仓表格式
#   - hadoop-aws 3.3.4 + aws-sdk-bundle  S3A 访问 MinIO（与 Spark 3.5.1 自带 Hadoop 3.3.4 对齐）
#   - Neo4j Spark Connector 5.3.1        离线批量写图
#   - ClickHouse JDBC 0.6.5 (all)        离线同步写 ClickHouse
#
# 构建参数 MAVEN_REPO 可替换为国内镜像：--build-arg MAVEN_REPO=https://maven.aliyun.com/repository/public
# =============================================================================
ARG SPARK_IMAGE=apache/spark:3.5.1-scala2.12-java17-ubuntu
FROM ${SPARK_IMAGE}

ARG MAVEN_REPO=https://repo1.maven.org/maven2
ARG ICEBERG_VERSION=1.5.2
ARG HADOOP_VERSION=3.3.4
ARG AWS_SDK_VERSION=1.12.262
ARG NEO4J_CONNECTOR_VERSION=5.3.1_for_spark_3
ARG CLICKHOUSE_JDBC_VERSION=0.6.5

USER root

ADD ${MAVEN_REPO}/org/apache/iceberg/iceberg-spark-runtime-3.5_2.12/${ICEBERG_VERSION}/iceberg-spark-runtime-3.5_2.12-${ICEBERG_VERSION}.jar /opt/spark/jars/
ADD ${MAVEN_REPO}/org/apache/hadoop/hadoop-aws/${HADOOP_VERSION}/hadoop-aws-${HADOOP_VERSION}.jar /opt/spark/jars/
ADD ${MAVEN_REPO}/com/amazonaws/aws-java-sdk-bundle/${AWS_SDK_VERSION}/aws-java-sdk-bundle-${AWS_SDK_VERSION}.jar /opt/spark/jars/
ADD ${MAVEN_REPO}/org/neo4j/neo4j-connector-apache-spark_2.12/${NEO4J_CONNECTOR_VERSION}/neo4j-connector-apache-spark_2.12-${NEO4J_CONNECTOR_VERSION}.jar /opt/spark/jars/
ADD ${MAVEN_REPO}/com/clickhouse/clickhouse-jdbc/${CLICKHOUSE_JDBC_VERSION}/clickhouse-jdbc-${CLICKHOUSE_JDBC_VERSION}-all.jar /opt/spark/jars/

RUN set -eux; \
    chmod 644 /opt/spark/jars/*.jar; \
    mkdir -p /opt/spark/conf /opt/spark/work-dir /opt/tmdbwh/dist; \
    chown -R spark:spark /opt/spark/conf /opt/spark/work-dir /opt/tmdbwh

ENV SPARK_HOME=/opt/spark \
    PATH=/opt/spark/bin:/opt/spark/sbin:${PATH} \
    SPARK_NO_DAEMONIZE=true

USER spark
WORKDIR /opt/spark/work-dir
