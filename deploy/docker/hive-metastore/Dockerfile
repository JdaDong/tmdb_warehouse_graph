# =============================================================================
# Hive Metastore 3.1.3（Iceberg HiveCatalog 的元数据服务）
#
# 在官方镜像基础上补齐：
#   1. PostgreSQL JDBC 驱动（元数据库后端）
#   2. S3A 依赖（hadoop-aws + aws-java-sdk-bundle，直接取自镜像内 Hadoop 发行包的 tools/lib，
#      保证与镜像内 Hadoop 版本严格一致，避免手工指定版本导致的 NoSuchMethodError）
#   3. 幂等启动脚本：Schema 不存在时初始化、已存在时直接启动（官方入口在重启时会重复 initSchema 并失败）
# =============================================================================
ARG HIVE_IMAGE=apache/hive:3.1.3
FROM ${HIVE_IMAGE}

ARG MAVEN_REPO=https://repo1.maven.org/maven2
ARG POSTGRES_JDBC_VERSION=42.7.3

USER root

ADD ${MAVEN_REPO}/org/postgresql/postgresql/${POSTGRES_JDBC_VERSION}/postgresql-${POSTGRES_JDBC_VERSION}.jar /opt/hive/lib/

RUN set -eux; \
    cp /opt/hadoop/share/hadoop/tools/lib/hadoop-aws-*.jar /opt/hive/lib/; \
    cp /opt/hadoop/share/hadoop/tools/lib/aws-java-sdk-bundle-*.jar /opt/hive/lib/; \
    chmod 644 /opt/hive/lib/*.jar; \
    ls -1 /opt/hive/lib | grep -E '^(postgresql|hadoop-aws|aws-java-sdk-bundle)-'

COPY --chmod=755 entrypoint.sh /opt/tmdbwh/hms-entrypoint.sh

USER hive
EXPOSE 9083
ENTRYPOINT ["/opt/tmdbwh/hms-entrypoint.sh"]
