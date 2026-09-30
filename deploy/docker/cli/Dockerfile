# =============================================================================
# tmdbwh-cli：平台 Java 命令行工具的统一运行容器
#
# 以只读方式挂载 dist/jars，按模块名分发到对应 fat-jar，例如：
#   docker compose run --rm cli governance migrate
#   docker compose run --rm cli ingestion full --entity movie --max-ids 200
# 这样初始化 / 运维脚本无需依赖宿主机 JDK，且统一使用容器网络内的服务地址。
# =============================================================================
ARG JRE_IMAGE=eclipse-temurin:17-jre-jammy
FROM ${JRE_IMAGE}

RUN set -eux; \
    groupadd --system --gid 1001 tmdbwh; \
    useradd --system --uid 1001 --gid tmdbwh --home-dir /opt/tmdbwh --create-home tmdbwh; \
    mkdir -p /opt/tmdbwh/dist /opt/tmdbwh/config

COPY --chmod=755 run-cli.sh /opt/tmdbwh/bin/run-cli.sh

USER tmdbwh
WORKDIR /opt/tmdbwh
ENV TMDBWH_DIST=/opt/tmdbwh/dist \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=UTC -Dfile.encoding=UTF-8"
ENTRYPOINT ["/opt/tmdbwh/bin/run-cli.sh"]
CMD ["--help"]
