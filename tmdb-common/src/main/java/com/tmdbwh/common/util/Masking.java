package com.tmdbwh.common.util;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 敏感信息脱敏工具。
 *
 * <p>使用场景：日志输出、异常消息、配置打印（toString）。规则：
 *
 * <ul>
 *   <li>密钥：长度 ≥ 12 时保留首尾各 4 位，中间以 {@code ****} 替换；否则整体替换为 {@code ****}；
 *   <li>URL：敏感查询参数（api_key / token / password 等）的值替换为 {@code ****}，userinfo 中的密码替换为 {@code ****}；
 *   <li>自由文本：{@code Bearer xxx}、JWT 形态字符串、{@code password=xxx} 等模式替换为 {@code ****}。
 * </ul>
 */
public final class Masking {

    /** 脱敏占位符。 */
    public static final String MASK = "****";

    private static final int MIN_PARTIAL_LENGTH = 12;
    private static final int KEEP = 4;

    private static final Set<String> SENSITIVE_KEYS = caseInsensitiveSet(
            "api_key", "apikey", "api-key", "token", "access_token", "refresh_token",
            "password", "passwd", "pwd", "secret", "secret_key", "secret-key", "session_id");

    private static final Set<String> SENSITIVE_HEADERS = caseInsensitiveSet(
            "authorization", "proxy-authorization", "cookie", "set-cookie", "x-api-key", "x-clickhouse-key");

    /** 查询参数：键值均不跨越空白，避免在自由文本中吞掉参数之后的内容。 */
    private static final Pattern QUERY_PARAM = Pattern.compile("([?&])([^=&#\\s]+)=([^&#\\s]*)");
    private static final Pattern URL_USERINFO = Pattern.compile("(://[^:/@\\s]+):([^@/\\s]+)@");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]+");
    private static final Pattern KV_SECRET = Pattern.compile(
            "(?i)(\"?(?:password|passwd|secret|secret_key|api_key|apikey|token|access_token)\"?\\s*[=:]\\s*\"?)([^\"&,\\s}]+)");

    private Masking() {}

    /**
     * 对密钥做部分遮盖。
     *
     * @param secret 原始密钥，可为 null
     * @return 脱敏结果；null 返回 null，空串返回空串
     */
    public static String maskSecret(String secret) {
        if (secret == null || secret.isEmpty()) {
            return secret;
        }
        if (secret.length() < MIN_PARTIAL_LENGTH) {
            return MASK;
        }
        return secret.substring(0, KEEP) + MASK + secret.substring(secret.length() - KEEP);
    }

    /**
     * 对 URL 中的敏感查询参数与 userinfo 密码脱敏。
     *
     * @param url 原始 URL，可为 null
     * @return 脱敏后的 URL
     */
    public static String maskUrl(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        String masked = URL_USERINFO.matcher(url).replaceAll("$1:" + MASK + "@");
        Matcher m = QUERY_PARAM.matcher(masked);
        StringBuffer sb = new StringBuffer(masked.length());
        while (m.find()) {
            String replacement = SENSITIVE_KEYS.contains(m.group(2))
                    ? m.group(1) + m.group(2) + "=" + MASK
                    : m.group(0);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 对 HTTP 头取值脱敏；非敏感头原样返回。
     *
     * @param headerName 头名称（大小写不敏感）
     * @param value 头取值
     * @return 脱敏后的取值
     */
    public static String maskHeader(String headerName, String value) {
        if (headerName == null || value == null) {
            return value;
        }
        return SENSITIVE_HEADERS.contains(headerName) ? MASK : value;
    }

    /**
     * 对自由文本（日志、异常消息、响应摘要）中的常见密钥模式脱敏。
     *
     * @param text 原始文本，可为 null
     * @return 脱敏后的文本
     */
    public static String maskText(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = BEARER.matcher(text).replaceAll("$1" + MASK);
        result = JWT.matcher(result).replaceAll(MASK);
        result = KV_SECRET.matcher(result).replaceAll("$1" + MASK);
        return maskUrl(result);
    }

    /**
     * 判断一个配置键 / 参数名是否属于敏感项。
     *
     * <p>对层级配置键只看最后一段，例如 {@code tmdbwh.s3.secret-key} → {@code secret-key}。
     */
    public static boolean isSensitiveKey(String key) {
        if (key == null || key.isEmpty()) {
            return false;
        }
        String leaf = key.substring(key.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return SENSITIVE_KEYS.contains(leaf)
                || leaf.endsWith("password")
                || leaf.endsWith("secret")
                || leaf.endsWith("token");
    }

    private static Set<String> caseInsensitiveSet(String... values) {
        Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(Arrays.asList(values));
        return Collections.unmodifiableSet(set);
    }
}
