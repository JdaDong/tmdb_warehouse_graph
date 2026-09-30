package com.tmdbwh.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Objects;

/**
 * 摘要与确定性 ID 生成工具。
 *
 * <p>确定性 ID 是整个平台"幂等"的基础：同一业务事件无论被采集 / 投递多少次，生成的 eventId 都相同， 下游（Flink 去重、ClickHouse
 * ReplacingMergeTree）据此消除重复。
 */
public final class Hashing {

    private static final char[] HEX = "0123456789abcdef".toCharArray();
    /** 组成确定性 ID 时各部分之间的分隔符（不可见字符，避免 "a|b"+"c" 与 "a"+"b|c" 碰撞）。 */
    private static final String PART_SEPARATOR = "\u0001";
    private static final int SHORT_ID_LENGTH = 32;

    private Hashing() {}

    /** 计算 SHA-256 十六进制摘要（64 位小写字符）。 */
    public static String sha256Hex(byte[] data) {
        return toHex(digest("SHA-256", data));
    }

    /** 计算 UTF-8 字符串的 SHA-256 十六进制摘要。 */
    public static String sha256Hex(String data) {
        return sha256Hex(Objects.requireNonNull(data, "data").getBytes(StandardCharsets.UTF_8));
    }

    /** 计算 MD5 并做 Base64 编码（S3 Content-MD5 头所需格式）。 */
    public static String md5Base64(byte[] data) {
        return Base64.getEncoder().encodeToString(digest("MD5", data));
    }

    /**
     * 由多个业务字段生成 32 位确定性 ID。
     *
     * @param parts 组成字段，null 以字面量 "null" 参与计算
     * @return 32 位十六进制字符串
     */
    public static String deterministicId(Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(PART_SEPARATOR);
            }
            sb.append(parts[i]);
        }
        return sha256Hex(sb.toString()).substring(0, SHORT_ID_LENGTH);
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(Objects.requireNonNull(data, "data"));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 不支持摘要算法: " + algorithm, e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX[v >>> 4];
            out[i * 2 + 1] = HEX[v & 0x0F];
        }
        return new String(out);
    }
}
