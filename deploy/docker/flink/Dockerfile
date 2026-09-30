# =============================================================================
# Flink 1.18.1 运行时镜像（JobManager / TaskManager 共用）
#
# 预置依赖：
#   - flink-s3-fs-hadoop 插件（官方发行包自带，放入 plugins/ 启用）：checkpoint / savepoint 写 MinIO（s3://）
#   - Iceberg Flink Runtime 1.5.2 + flink-sql-connector-hive-3.1.3：实时写 Iceberg（HiveCatalog）
#   - hadoop-client-api/runtime + hadoop-aws 3.3.4 + aws-sdk-bundle：Iceberg HadoopFileIO 通过 s3a:// 读写
#   - Prometheus Reporter：官方发行包已内置于 plugins/metrics-prometheus
# Kafka 连接器随作业 fat-jar 打包，不放入 lib，避免与作业版本冲突。
# =============================================================================
ARG FLINK_IMAGE=flink:1.18.1-scala_2.12-java11
FROM ${FLINK_IMAGE}

ARG MAVEN_REPO=https://repo1.maven.org/maven2
ARG FLINK_VERSION=1.18.1
ARG ICEBERG_VERSION=1.5.2
ARG HADOOP_VERSION=3.3.4
ARG AWS_SDK_VERSION=1.12.262

ADD ${MAVEN_REPO}/org/apache/iceberg/iceberg-flink-runtime-1.18/${ICEBERG_VERSION}/iceberg-flink-runtime-1.18-${ICEBERG_VERSION}.jar /opt/flink/lib/
ADD ${MAVEN_REPO}/org/apache/flink/flink-sql-connector-hive-3.1.3_2.12/${FLINK_VERSION}/flink-sql-connector-hive-3.1.3_2.12-${FLINK_VERSION}.jar /opt/flink/lib/
ADD ${MAVEN_REPO}/org/apache/hadoop/hadoop-client-api/${HADOOP_VERSION}/hadoop-client-api-${HADOOP_VERSION}.jar /opt/flink/lib/
ADD ${MAVEN_REPO}/org/apache/hadoop/hadoop-client-runtime/${HADOOP_VERSION}/hadoop-client-runtime-${HADOOP_VERSION}.jar /opt/flink/lib/
ADD ${MAVEN_REPO}/org/apache/hadoop/hadoop-aws/${HADOOP_VERSION}/hadoop-aws-${HADOOP_VERSION}.jar /opt/flink/lib/
ADD ${MAVEN_REPO}/com/amazonaws/aws-java-sdk-bundle/${AWS_SDK_VERSION}/aws-java-sdk-bundle-${AWS_SDK_VERSION}.jar /opt/flink/lib/

RUN set -eux; \
    mkdir -p /opt/flink/plugins/s3-fs-hadoop /opt/flink/usrlib /opt/flink/uploads /opt/tmdbwh/dist /opt/hadoop-conf; \
    cp /opt/flink/opt/flink-s3-fs-hadoop-${FLINK_VERSION}.jar /opt/flink/plugins/s3-fs-hadoop/; \
    if [ ! -d /opt/flink/plugins/metrics-prometheus ]; then \
        echo "警告: 镜像发行包中未找到 metrics-prometheus 插件，Flink 指标将无法暴露给 Prometheus" >&2; \
    fi; \
    chmod 644 /opt/flink/lib/*.jar; \
    chown -R flink:flink /opt/flink/lib /opt/flink/plugins /opt/flink/usrlib /opt/flink/uploads /opt/tmdbwh /opt/hadoop-conf

# Iceberg HiveCatalog 通过 HADOOP_CONF_DIR 读取 core-site.xml（S3A 端点与凭证提供者）
ENV HADOOP_CONF_DIR=/opt/hadoop-conf
