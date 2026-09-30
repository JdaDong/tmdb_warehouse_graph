package com.tmdbwh.common.json;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.exception.JsonCodecException;
import com.tmdbwh.common.model.Genre;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonUtilsTest {

    @Test
    void roundTripAndNullFieldsOmitted() {
        Genre g = new Genre(28, null);
        String json = JsonUtils.toJson(g);

        assertThat(json).isEqualTo("{\"id\":28}");
        assertThat(JsonUtils.fromJson(json, Genre.class)).isEqualTo(g);
        assertThat(JsonUtils.fromJson(json.getBytes(StandardCharsets.UTF_8), Genre.class)).isEqualTo(g);
    }

    @Test
    void unknownFieldsAreIgnoredForForwardCompatibility() {
        Genre g = JsonUtils.fromJson("{\"id\":1,\"name\":\"Drama\",\"brand_new_field\":{\"x\":1}}", Genre.class);
        assertThat(g.getName()).isEqualTo("Drama");
    }

    @Test
    void javaTimeTypesSerializeAsIsoStrings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("d", LocalDate.of(2026, 9, 30));
        m.put("t", Instant.parse("2026-09-30T01:02:03Z"));

        assertThat(JsonUtils.toJson(m)).isEqualTo("{\"d\":\"2026-09-30\",\"t\":\"2026-09-30T01:02:03Z\"}");
    }

    @Test
    void genericTypeReference() {
        List<Genre> genres = JsonUtils.fromJson("[{\"id\":1,\"name\":\"A\"},{\"id\":2,\"name\":\"B\"}]",
                new TypeReference<List<Genre>>() {});
        assertThat(genres).extracting(Genre::getId).containsExactly(1, 2);
    }

    @Test
    void invalidJsonIsWrappedWithoutLeakingPayload() {
        String secretPayload = "{\"api_key\": \"top-secret\"";
        assertThatThrownBy(() -> JsonUtils.fromJson(secretPayload, Genre.class))
                .isInstanceOf(JsonCodecException.class)
                .hasMessageContaining("Genre")
                .hasMessageNotContaining("top-secret");
        assertThatThrownBy(() -> JsonUtils.readTree("{broken"))
                .isInstanceOf(JsonCodecException.class);
    }

    @Test
    void tryFromJsonReturnsEmptyOnGarbage() {
        assertThat(JsonUtils.tryFromJson("not json", Genre.class)).isEmpty();
        assertThat(JsonUtils.tryFromJson("", Genre.class)).isEmpty();
        assertThat(JsonUtils.tryFromJson(null, Genre.class)).isEmpty();
        assertThat(JsonUtils.tryFromJson("{\"id\":7}", Genre.class)).get().extracting(Genre::getId).isEqualTo(7);
    }

    @Test
    void canonicalJsonIsIndependentOfFieldOrder() {
        JsonNode a = JsonUtils.readTree("{\"b\":1,\"a\":{\"y\":[{\"k\":2,\"j\":1}],\"x\":null}}");
        JsonNode b = JsonUtils.readTree("{\"a\":{\"x\":null,\"y\":[{\"j\":1,\"k\":2}]},\"b\":1}");

        String canonical = JsonUtils.canonicalJson(a);
        assertThat(canonical).isEqualTo(JsonUtils.canonicalJson(b));
        assertThat(canonical).isEqualTo("{\"a\":{\"x\":null,\"y\":[{\"j\":1,\"k\":2}]},\"b\":1}");
    }

    @Test
    void canonicalJsonKeepsArrayOrder() {
        assertThat(JsonUtils.canonicalJson(JsonUtils.readTree("[3,1,2]"))).isEqualTo("[3,1,2]");
        assertThat(JsonUtils.canonicalJson(null)).isEqualTo("null");
    }

    @Test
    void treeConversions() {
        JsonNode node = JsonUtils.valueToTree(new Genre(5, "Crime"));
        assertThat(node.get("name").asText()).isEqualTo("Crime");
        assertThat(JsonUtils.treeToValue(node, Genre.class)).isEqualTo(new Genre(5, "Crime"));
        assertThat(JsonUtils.readTree("{\"a\":1}".getBytes(StandardCharsets.UTF_8)).get("a").asInt()).isEqualTo(1);
        assertThat(JsonUtils.toJsonBytes(new Genre(1, "x"))).isNotEmpty();
    }

    @Test
    void sharedMapperAndNewMapperAreConsistent() {
        assertThat(JsonUtils.mapper()).isSameAs(JsonUtils.mapper());
        assertThat(JsonUtils.newMapper()).isNotSameAs(JsonUtils.mapper());
    }
}
