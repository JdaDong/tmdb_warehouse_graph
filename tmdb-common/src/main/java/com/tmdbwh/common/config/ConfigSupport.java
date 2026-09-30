package com.tmdbwh.common.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Set;

/** 配置校验辅助方法（包内使用）。 */
final class ConfigSupport {

    private ConfigSupport() {}

    /** 校验取值为正数。 */
    static void requirePositive(List<String> errors, String key, long value) {
        if (value <= 0) {
            errors.add(key + " 必须为正数，当前值: " + value);
        }
    }

    /** 校验字符串非空。 */
    static void requireNonBlank(List<String> errors, String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            errors.add(key + " 不能为空");
        }
    }

    /** 校验 URI 语法合法且 scheme 在允许范围内。 */
    static void requireUri(List<String> errors, String key, String value, Set<String> schemes) {
        if (value == null || value.trim().isEmpty()) {
            errors.add(key + " 不能为空");
            return;
        }
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !schemes.contains(scheme)) {
                errors.add(key + " 的协议必须是 " + schemes + "，当前值: " + value);
            }
        } catch (URISyntaxException e) {
            errors.add(key + " 不是合法的 URI: " + value);
        }
    }

    /** 去除首尾空白，null 视为空串。 */
    static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
