#!/usr/bin/env bash
# =============================================================================
# Kafka Topic 初始化（在 kafka-init 容器中执行；镜像为 alpine sh，保持 POSIX 兼容）
#   幂等：已存在的 topic 只做 reconcile（不改动现有分区数），缺失时创建。
#   注意：单 broker 环境 replication-factor=1，生产请按 3 副本配置。
# =============================================================================
set -eu

: "${KAFKA_BOOTSTRAP_SERVERS:?必须设置 KAFKA_BOOTSTRAP_SERVERS}"
BIN="${KAFKA_BIN:-/opt/kafka/bin}"

log() { printf '[INFO] %s\n' "$*"; }
err() { printf '[ERROR] %s\n' "$*" >&2; }

wait_ready() {
    n=0
    while [ "$n" -lt 60 ]; do
        if "$BIN/kafka-topics.sh" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" --list >/dev/null 2>&1; then
            log "Kafka 就绪: $KAFKA_BOOTSTRAP_SERVERS"
            return 0
        fi
        n=$((n + 1))
        sleep 5
    done
    err "等待 Kafka 超时"
    return 1
}

ensure_topic() {
    name="$1"; partitions="$2"; retention_ms="$3"
    if "$BIN/kafka-topics.sh" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" --list 2>/dev/null | grep -qx "$name"; then
        log "topic 已存在: $name"
    else
        "$BIN/kafka-topics.sh" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" --create \
            --topic "$name" --partitions "$partitions" --replication-factor 1 \
            --config "retention.ms=$retention_ms" \
            --config "min.insync.replicas=1"
        log "已创建 topic: $name (partitions=$partitions, retention=${retention_ms}ms)"
    fi
}

wait_ready

# name                   分区  保留时间(ms)
# tmdb.entity.change      6    7 天：实体变更事件，实时链路主输入
ensure_topic "${KAFKA_TOPIC_ENTITY_CHANGE:-tmdb.entity.change}" 6 604800000
# tmdb.popularity         6    3 天：热度观测，短周期窗口聚合
ensure_topic "${KAFKA_TOPIC_POPULARITY:-tmdb.popularity}" 6 259200000
# tmdb.alert              3    7 天：实时告警（如飙升检测）
ensure_topic "${KAFKA_TOPIC_ALERT:-tmdb.alert}" 3 604800000
# tmdb.dlq                3    30 天：死信队列，必须保留足够长以便排查与回溯重放
ensure_topic "${KAFKA_TOPIC_DLQ:-tmdb.dlq}" 3 2592000000

log "Kafka Topic 初始化完成"
"$BIN/kafka-topics.sh" --bootstrap-server "$KAFKA_BOOTSTRAP_SERVERS" --list | sed 's/^/  - /'
