package com.tmdbwh.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HashingTest {

    @Test
    void sha256KnownVectors() {
        assertThat(Hashing.sha256Hex(""))
                .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(Hashing.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(Hashing.sha256Hex("abc".getBytes(StandardCharsets.UTF_8))).isEqualTo(Hashing.sha256Hex("abc"));
    }

    @Test
    void md5Base64KnownVector() {
        // RFC 1321: MD5("") = d41d8cd98f00b204e9800998ecf8427e
        assertThat(Hashing.md5Base64(new byte[0])).isEqualTo("1B2M2Y8AsgTpgAmY7PhCfg==");
    }

    @Test
    void deterministicIdIsStableAndCollisionResistantAcrossBoundaries() {
        String id = Hashing.deterministicId("ENTITY_CHANGED", "movie", 27205L);
        assertThat(id).hasSize(32).matches("[0-9a-f]{32}");
        assertThat(Hashing.deterministicId("ENTITY_CHANGED", "movie", 27205L)).isEqualTo(id);
        // 分隔符保证 ("ab","c") 与 ("a","bc") 不碰撞
        assertThat(Hashing.deterministicId("ab", "c")).isNotEqualTo(Hashing.deterministicId("a", "bc"));
        assertThat(Hashing.deterministicId((Object) null)).isEqualTo(Hashing.deterministicId("null"));
    }
}
