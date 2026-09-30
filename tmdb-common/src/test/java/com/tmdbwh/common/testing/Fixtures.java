package com.tmdbwh.common.testing;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** 测试夹具读取工具：从 classpath:/fixtures/ 读取样例报文。 */
public final class Fixtures {

    private Fixtures() {}

    /** 读取夹具文件为 UTF-8 字符串。 */
    public static String read(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("夹具不存在: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
