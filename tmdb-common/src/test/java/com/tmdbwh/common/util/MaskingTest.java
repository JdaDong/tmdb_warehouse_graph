package com.tmdbwh.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MaskingTest {

    @Test
    void maskSecretKeepsEdgesOnlyForLongValues() {
        assertThat(Masking.maskSecret(null)).isNull();
        assertThat(Masking.maskSecret("")).isEmpty();
        assertThat(Masking.maskSecret("short")).isEqualTo("****");
        assertThat(Masking.maskSecret("abcdefghijk")).isEqualTo("****");
        assertThat(Masking.maskSecret("abcdefghijkl")).isEqualTo("abcd****ijkl");
    }

    @Test
    void maskUrlQueryParamsAndUserInfo() {
        assertThat(Masking.maskUrl("https://api.themoviedb.org/3/movie/1?api_key=SECRET123&language=en-US"))
                .isEqualTo("https://api.themoviedb.org/3/movie/1?api_key=****&language=en-US");
        assertThat(Masking.maskUrl("http://h/x?language=en&TOKEN=abc#frag"))
                .isEqualTo("http://h/x?language=en&TOKEN=****#frag");
        assertThat(Masking.maskUrl("jdbc:clickhouse://user:pa55word@ch:8123/db"))
                .isEqualTo("jdbc:clickhouse://user:****@ch:8123/db");
        assertThat(Masking.maskUrl("http://plain/path")).isEqualTo("http://plain/path");
        assertThat(Masking.maskUrl(null)).isNull();
    }

    @ParameterizedTest
    @CsvSource({
        "Authorization, ****",
        "authorization, ****",
        "Cookie, ****",
        "X-Api-Key, ****",
        "Content-Type, application/json"
    })
    void maskHeader(String name, String expected) {
        String value = "Content-Type".equals(name) ? "application/json" : "Bearer abc";
        assertThat(Masking.maskHeader(name, value)).isEqualTo(expected);
    }

    @Test
    void maskHeaderNullSafe() {
        assertThat(Masking.maskHeader(null, "v")).isEqualTo("v");
        assertThat(Masking.maskHeader("Authorization", null)).isNull();
    }

    @Test
    void maskFreeText() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIxMjMifQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String text = "request failed: Authorization: Bearer " + jwt + " url=http://x/y?api_key=k123 password=hunter2";

        String masked = Masking.maskText(text);

        assertThat(masked)
                .doesNotContain(jwt)
                .doesNotContain("k123")
                .doesNotContain("hunter2")
                .contains("Bearer ****")
                .contains("api_key=****")
                .contains("password=****");
        assertThat(Masking.maskText("{\"password\":\"abc\"}")).isEqualTo("{\"password\":\"****\"}");
        assertThat(Masking.maskText(null)).isNull();
        assertThat(Masking.maskText("nothing sensitive")).isEqualTo("nothing sensitive");
    }

    @ParameterizedTest
    @CsvSource({
        "tmdbwh.s3.secret-key, true",
        "tmdbwh.tmdb.bearer-token, true",
        "tmdbwh.clickhouse.password, true",
        "api_key, true",
        "tmdbwh.clickhouse.url, false",
        "language, false"
    })
    void sensitiveKeyDetection(String key, boolean sensitive) {
        assertThat(Masking.isSensitiveKey(key)).isEqualTo(sensitive);
    }

    @Test
    void sensitiveKeyNullSafe() {
        assertThat(Masking.isSensitiveKey(null)).isFalse();
        assertThat(Masking.isSensitiveKey("")).isFalse();
    }
}
