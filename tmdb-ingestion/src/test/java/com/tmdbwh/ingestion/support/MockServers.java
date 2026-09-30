package com.tmdbwh.ingestion.support;

import com.tmdbwh.common.config.AppConfig;
import com.typesafe.config.ConfigFactory;
import java.util.HashMap;
import java.util.Map;
import okhttp3.mockwebserver.MockWebServer;

/** 测试辅助：构建指向 MockWebServer 的配置，以及常见响应报文。 */
public final class MockServers {

    private MockServers() {}

    /**
     * 构造 AppConfig：base-url 指向给定的 MockWebServer。
     *
     * <p>TMDB 的 base-url 形如 {@code https://api.themoviedb.org/3}，而端点路径同样以 {@code /3} 开头
     * （见 {@code TmdbEndpoints}），因此这里取 {@code /3} 作为基地址并去掉末尾斜杠， 拼出的最终路径才是 {@code /3/movie/1}。
     */
    public static AppConfig configFor(MockWebServer server, Map<String, Object> extra) {
        Map<String, Object> values = new HashMap<>();
        // MockWebServer 监听 IPv4 回环；直接用 "localhost" 会让 OkHttp 先尝试 ::1 导致偶发连接失败，
        // 因此显式换成 127.0.0.1
        String base = server.url("/3").toString().replaceAll("/+$", "").replace("localhost", "127.0.0.1");
        values.put("tmdbwh.tmdb.base-url", base);
        values.put("tmdbwh.tmdb.bearer-token", "test-bearer-token-value");
        values.put("tmdbwh.s3.endpoint", "http://localhost:9000");
        values.put("tmdbwh.s3.access-key", "ak");
        values.put("tmdbwh.s3.secret-key", "sk");
        if (extra != null) {
            values.putAll(extra);
        }
        return AppConfig.from(ConfigFactory.parseMap(values).withFallback(ConfigFactory.defaultReference()));
    }

    /** 构造 AppConfig（附加 tmdb 段参数）。 */
    public static AppConfig configFor(MockWebServer server) {
        return configFor(server, null);
    }

    /** TMDB 风格的 404 报文。 */
    public static String notFoundBody() {
        return "{\"success\":false,\"status_code\":34,\"status_message\":\"The resource you requested could not be found.\"}";
    }

    /** 单页分页响应（page / total_pages / results）。 */
    public static String page(int page, int totalPages, String results) {
        return "{\"page\":" + page + ",\"total_pages\":" + totalPages + ",\"total_results\":"
                + (totalPages * 20) + ",\"results\":" + results + "}";
    }
}
