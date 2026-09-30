package com.tmdbwh.common.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tmdbwh.common.exception.JsonCodecException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * JSON 编解码门面。
 *
 * <p>全平台共享同一个线程安全的 {@link ObjectMapper}，配置要点：
 *
 * <ul>
 *   <li>忽略未知字段：TMDB 会不断新增字段，模型不应因此反序列化失败（向前兼容）；
 *   <li>序列化时省略 null：减少原始区存储与 Kafka 消息体积；
 *   <li>java.time 类型输出 ISO-8601 字符串而非时间戳数组；
 *   <li>所有异常统一包装为 {@link JsonCodecException}，异常消息不包含原始报文（避免泄露与刷屏）。
 * </ul>
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = newMapper();

    private JsonUtils() {}

    /** 返回共享的 ObjectMapper，调用方不得修改其配置。 */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 创建一个与平台配置一致的新 ObjectMapper（用于需要额外定制的场景）。 */
    public static ObjectMapper newMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
    }

    /** 对象序列化为 JSON 字符串。 */
    public static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new JsonCodecException("JSON 序列化失败: " + typeName(value), e);
        }
    }

    /** 对象序列化为 UTF-8 字节。 */
    public static byte[] toJsonBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            throw new JsonCodecException("JSON 序列化失败: " + typeName(value), e);
        }
    }

    /** 反序列化为指定类型。 */
    public static <T> T fromJson(String json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new JsonCodecException("JSON 反序列化失败: target=" + type.getSimpleName(), e);
        }
    }

    /** 从 UTF-8 字节反序列化为指定类型。 */
    public static <T> T fromJson(byte[] json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new JsonCodecException("JSON 反序列化失败: target=" + type.getSimpleName(), e);
        }
    }

    /** 反序列化为泛型类型，例如 {@code new TypeReference<PagedResponse<Movie>>() {}}。 */
    public static <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new JsonCodecException("JSON 反序列化失败: target=" + type.getType().getTypeName(), e);
        }
    }

    /** 尝试反序列化，失败返回 empty（用于脏数据容忍场景，如 ODS 入湖时隔离坏行）。 */
    public static <T> Optional<T> tryFromJson(String json, Class<T> type) {
        if (json == null || json.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(MAPPER.readValue(json, type));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** 解析为树模型。 */
    public static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new JsonCodecException("JSON 解析失败", e);
        }
    }

    /** 从 UTF-8 字节解析为树模型。 */
    public static JsonNode readTree(byte[] json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new JsonCodecException("JSON 解析失败", e);
        }
    }

    /** 对象转树模型。 */
    public static JsonNode valueToTree(Object value) {
        try {
            return MAPPER.valueToTree(value);
        } catch (IllegalArgumentException e) {
            throw new JsonCodecException("对象转 JsonNode 失败: " + typeName(value), e);
        }
    }

    /**
     * 树模型转泛型对象，例如 {@code new TypeReference<PagedResponse<Movie>>() {}}。
     *
     * <p>直接转发给 {@link ObjectMapper#readValue}，避免调用方为了取泛型类型而反复构造 mapper。
     */
    public static <T> T readValue(JsonNode node, TypeReference<T> type) {
        try {
            return MAPPER.readValue(MAPPER.treeAsTokens(node), type);
        } catch (IOException e) {
            throw new JsonCodecException("JsonNode 转对象失败: target=" + type.getType().getTypeName(), e);
        }
    }

    /** 树模型转对象。 */
    public static <T> T treeToValue(JsonNode node, Class<T> type) {
        try {
            return MAPPER.treeToValue(node, type);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new JsonCodecException("JsonNode 转对象失败: target=" + type.getSimpleName(), e);
        }
    }

    /**
     * 规范化 JSON：对象字段按字典序递归排序后输出紧凑字符串。
     *
     * <p>用于计算内容哈希——同一份数据无论字段顺序如何，规范化结果都一致，从而实现"内容未变则不重复下发"的去重。
     */
    public static String canonicalJson(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(canonicalize(node));
        } catch (JsonProcessingException e) {
            throw new JsonCodecException("JSON 规范化失败", e);
        }
    }

    static JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return JsonNodeFactory.instance.nullNode();
        }
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            Iterator<String> it = node.fieldNames();
            while (it.hasNext()) {
                names.add(it.next());
            }
            Collections.sort(names);
            ObjectNode sorted = JsonNodeFactory.instance.objectNode();
            for (String name : names) {
                sorted.set(name, canonicalize(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = JsonNodeFactory.instance.arrayNode(node.size());
            for (JsonNode element : node) {
                array.add(canonicalize(element));
            }
            return array;
        }
        return node;
    }

    private static String typeName(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }
}
