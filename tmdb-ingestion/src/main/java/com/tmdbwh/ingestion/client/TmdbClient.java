package com.tmdbwh.ingestion.client;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.config.TmdbConfig;
import com.tmdbwh.common.json.JsonUtils;
import com.tmdbwh.common.model.ChangeItem;
import com.tmdbwh.common.model.EntityType;
import com.tmdbwh.common.model.Movie;
import com.tmdbwh.common.model.PagedResponse;
import com.tmdbwh.common.model.Person;
import com.tmdbwh.common.model.TvShow;
import com.tmdbwh.common.util.Masking;
import com.tmdbwh.ingestion.metrics.IngestionMetrics;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TMDB HTTP 客户端：限流 + 重试 + 分页 + 指标。
 *
 * <p>三层可靠性设计：
 *
 * <ol>
 *   <li><b>限流</b>（Resilience4j RateLimiter）：令牌桶，默认 40 req/s，低于 TMDB 官方约 50 req/s 的上限，
 *       预留余量避免触发风控；获取许可最多等待 30s，超时抛 {@link
 *       io.github.resilience4j.ratelimiter.RequestNotPermitted}（不重试，说明整体调用速率超配）；
 *   <li><b>重试</b>（Resilience4j Retry）：429 / 5xx / IO 异常按指数退避重试。429 会读取 {@code Retry-After}
 *       响应头并优先按服务端建议等待（见 {@link RateLimitException}），而不是盲目退避；
 *   <li><b>快速失败</b>：其他 4xx（401 凭证无效、404 资源不存在等）直接抛 {@link TmdbClientException}，
 *       不做无意义的重试。
 * </ol>
 *
 * <p>其他要点：
 *
 * <ul>
 *   <li>认证优先 v4 Bearer Token，其次 v3 {@code api_key} 查询参数；两者在日志中均脱敏；
 *   <li>底层 OkHttp 关闭自身重试（{@code retryOnConnectionFailure(false)}），避免与上层重试叠加放大请求量；
 *   <li>线程安全，可在多线程间共享；并发度由调用方线程池控制。
 * </ul>
 */
public class TmdbClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(TmdbClient.class);

    private static final String HEADER_RETRY_AFTER = "Retry-After";
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final TmdbConfig config;
    private final OkHttpClient http;
    private final RateLimiter rateLimiter;
    private final Retry retry;
    private final IngestionMetrics metrics;
    private final String language;
    private final boolean bearerAuth;

    /**
     * @param config TMDB 配置（base-url、凭证、限流与重试参数）
     * @param metrics 指标收集器
     */
    public TmdbClient(TmdbConfig config, IngestionMetrics metrics) {
        this.config = Objects.requireNonNull(config, "config");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.language = config.getLanguage();
        this.bearerAuth = config.useBearerAuth();

        this.http = new OkHttpClient.Builder()
                .connectTimeout(config.getConnectTimeout())
                .readTimeout(config.getReadTimeout())
                // callTimeout 覆盖整次调用（含重试与排队），取值需明显大于单次 readTimeout，
                // 否则短 readTimeout 配置会被"整体超时"掩盖，退避重试失去意义
                .callTimeout(config.getReadTimeout().multipliedBy(3).plusSeconds(10))
                // 不复用空闲连接：TMDB 侧可能静默关闭长连接，复用"半开连接"会让偶发失败变成连续失败
                .connectionPool(new ConnectionPool(config.getMaxConcurrency() + 8, 1, TimeUnit.MILLISECONDS))
                // 由上层 Retry 统一处理，避免双层重试放大请求量
                .retryOnConnectionFailure(false)
                .build();

        this.rateLimiter = RateLimiter.of("tmdb", RateLimiterConfig.custom()
                .limitForPeriod(config.getRateLimitPerSecond())
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ofSeconds(30))
                .build());

        this.retry = Retry.of("tmdb", RetryConfig.custom()
                .maxAttempts(config.getMaxRetries())
                .waitDuration(Duration.ofMillis(500))
                .intervalBiFunction((attempt, result) -> retryWait(attempt, result.fold(Throwable.class::cast,
                        Throwable.class::cast)))
                .retryExceptions(RateLimitException.class, TmdbServerException.class, TmdbIOException.class)
                .build());
    }

    public TmdbConfig getConfig() {
        return config;
    }

    /** 限流器（供测试观测）。 */
    public RateLimiter rateLimiter() {
        return rateLimiter;
    }

    /** 已发送的 HTTP 请求总数（含重试）。 */
    public long requestCount() {
        return metrics.requestCount();
    }

    // ============================== 实体详情 ==============================

    /**
     * 获取实体详情（不附带子资源）。
     *
     * @return 详情 JsonNode；404 时返回 empty（调用方据此判定"资源已删除"）
     */
    public Optional<JsonNode> getDetails(EntityType type, long id) {
        return getDetails(type, id, false);
    }

    /**
     * 获取实体详情。
     *
     * @param withAppend 是否附带 append_to_response 子资源（电影：credits + keywords + release_dates）
     */
    public Optional<JsonNode> getDetails(EntityType type, long id, boolean withAppend) {
        HttpUrl.Builder url = urlBuilder(TmdbEndpoints.detail(type, id));
        url.addQueryParameter("language", language);
        if (withAppend) {
            String append = TmdbEndpoints.appendToResponse(type);
            if (!append.isEmpty()) {
                url.addQueryParameter("append_to_response", append);
            }
        }
        return requestJson(url.build(), true);
    }

    /** 获取电影详情（含演职员 / 关键词 / 上映信息）。 */
    public Optional<Movie> getMovie(long id) {
        return getDetails(EntityType.MOVIE, id, true).map(n -> JsonUtils.treeToValue(n, Movie.class));
    }

    /** 获取剧集详情（含演职员 / 关键词）。 */
    public Optional<TvShow> getTvShow(long id) {
        return getDetails(EntityType.TV, id, true).map(n -> JsonUtils.treeToValue(n, TvShow.class));
    }

    /** 获取人物详情。 */
    public Optional<Person> getPerson(long id) {
        return getDetails(EntityType.PERSON, id, false).map(n -> JsonUtils.treeToValue(n, Person.class));
    }

    // ============================== 变更 / 榜单 ==============================

    /**
     * 查询某时间窗内发生变更的实体 ID（单页）。
     *
     * @param startDate 起始日期（yyyy-MM-dd，含）
     * @param endDate 结束日期（yyyy-MM-dd，含）；TMDB 单次最多 14 天，超长区间由调用方切分
     * @param page 页码，从 1 开始
     */
    public PagedResponse<ChangeItem> getChanges(EntityType type, String startDate, String endDate, int page) {
        HttpUrl.Builder url = urlBuilder(TmdbEndpoints.changes(type));
        if (startDate != null && !startDate.isEmpty()) {
            url.addQueryParameter("start_date", startDate);
        }
        if (endDate != null && !endDate.isEmpty()) {
            url.addQueryParameter("end_date", endDate);
        }
        url.addQueryParameter("page", String.valueOf(page));
        JsonNode node = requestJson(url.build(), false)
                .orElseThrow(() -> new TmdbServerException(500, url.build().toString(), "changes 接口返回空响应"));
        return JsonUtils.readValue(node, new TypeReference<PagedResponse<ChangeItem>>() {});
    }

    /** 拉取某时间窗内的全部变更 ID（自动翻页，受 {@code maxPages} 限制）。 */
    public List<ChangeItem> getAllChanges(EntityType type, String startDate, String endDate, int maxPages) {
        List<ChangeItem> all = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            PagedResponse<ChangeItem> resp = getChanges(type, startDate, endDate, page);
            all.addAll(resp.getResults());
            if (!resp.hasNext()) {
                break;
            }
        }
        return all;
    }

    /** 获取榜单单页（trending / popular / now_playing 等）。 */
    public PagedResponse<JsonNode> getListPage(String path, int page) {
        HttpUrl.Builder url = urlBuilder(path);
        url.addQueryParameter("language", language);
        url.addQueryParameter("page", String.valueOf(page));
        return requestJson(url.build(), false)
                .<PagedResponse<JsonNode>>map(n -> JsonUtils.readValue(n, new TypeReference<PagedResponse<JsonNode>>() {}))
                .orElseGet(PagedResponse::new);
    }

    /**
     * 以惰性流遍历榜单全部条目（自动翻页），适合大榜单，避免一次性载入内存。
     *
     * <p>翻页终止条件来自分页元数据（{@code page >= total_pages}）或 {@code maxPages} 上限，
     * 而不是"再取一页看是否为空"——后者会在最后一页之后多发一次请求，若调用方提前终止流（如 limit 截断），
     * 这次多余请求会以异常形式抛出，干扰调用方。
     */
    public Stream<JsonNode> streamList(String path, int maxPages) {
        return StreamSupport.stream(
                Spliterators.spliteratorUnknownSize(new ListIterator(path, maxPages), Spliterator.ORDERED), false);
    }

    /** 榜单惰性翻页迭代器。 */
    private final class ListIterator implements Iterator<JsonNode> {

        private final String path;
        private final int maxPages;
        private int nextPage = 1;
        private Iterator<JsonNode> buffer = java.util.Collections.emptyIterator();

        ListIterator(String path, int maxPages) {
            this.path = path;
            this.maxPages = maxPages;
        }

        @Override
        public boolean hasNext() {
            if (buffer.hasNext()) {
                return true;
            }
            if (nextPage > maxPages) {
                return false;
            }
            PagedResponse<JsonNode> page = getListPage(path, nextPage);
            buffer = page.getResults().iterator();
            boolean last = page.getTotalPages() > 0 && page.getPage() >= page.getTotalPages();
            if (last) {
                nextPage = maxPages + 1; // 已是末页，停止后续请求
            } else {
                nextPage++;
            }
            return buffer.hasNext();
        }

        @Override
        public JsonNode next() {
            if (!hasNext()) {
                throw new NoSuchElementException("榜单已遍历结束: " + path);
            }
            return buffer.next();
        }
    }

    // ============================== 通用请求 ==============================

    /**
     * 执行 GET 请求并解析 JSON。
     *
     * @param allowNotFound 为 true 时，404 返回 empty；为 false 时抛 {@link NotFoundException}
     */
    public Optional<JsonNode> requestJson(HttpUrl url, boolean allowNotFound) {
        Objects.requireNonNull(url, "url");
        String raw = execute(url, allowNotFound);
        if (raw == null) {
            return Optional.empty();
        }
        JsonNode node = JsonUtils.readTree(raw);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return Optional.empty();
        }
        return Optional.of(node);
    }

    /**
     * 执行 GET 并返回响应体字符串。所有限流与重试在此统一生效。
     *
     * @param allowNotFound 为 true 时，404 返回 null；为 false 时抛 {@link NotFoundException}
     */
    public String execute(HttpUrl url, boolean allowNotFound) {
        Callable<String> call = () -> doExecute(url, allowNotFound);
        Callable<String> guarded = Retry.decorateCallable(retry, call);
        Callable<String> limited = RateLimiter.decorateCallable(rateLimiter, guarded);
        try {
            return limited.call();
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new TmdbException("TMDB 请求失败: " + e.getMessage(), url.toString(), e);
        }
    }

    private String doExecute(HttpUrl url, boolean allowNotFound) throws IOException {
        Request.Builder req = new Request.Builder().url(url).get();
        if (bearerAuth) {
            req.header("Authorization", "Bearer " + config.getBearerToken());
        }
        long start = System.nanoTime();
        LOG.debug("TMDB 请求 {}", Masking.maskUrl(url.toString()));
        try (Response response = http.newCall(req.build()).execute()) {
            long costMs = (System.nanoTime() - start) / 1_000_000L;
            int code = response.code();
            metrics.recordRequest(code, costMs);
            if (code == 200) {
                ResponseBody body = response.body();
                if (body == null) {
                    throw new TmdbIOException(url.toString(), new IOException("响应体为空"));
                }
                return body.string();
            }
            String bodyText = readBodySafely(response);
            if (code == HTTP_TOO_MANY_REQUESTS) {
                metrics.recordRateLimited();
                throw new RateLimitException(url.toString(), parseRetryAfter(response));
            }
            if (code == HTTP_NOT_FOUND && allowNotFound) {
                metrics.recordNotFound();
                return null;
            }
            if (code >= 500) {
                throw new TmdbServerException(code, url.toString(), bodyText);
            }
            throw new TmdbClientException(code, url.toString(), bodyText);
        } catch (IOException e) {
            metrics.recordFailure();
            throw new TmdbIOException(url.toString(), e);
        }
    }

    /**
     * 下载每日 ID 导出文件（{@code movie_ids_MM_dd_yyyy.json.gz} 等）。
     *
     * <p>导出文件属于静态资源（files.tmdb.org），与 API 走不同的域名与限流策略，因此这里单独发起请求：
     * 长超时（2 分钟，文件可达数十 MB）、同样受重试保护（5xx / IO），但对 403 / 404 直接给出可操作的错误信息——
     * TMDB 对"尚未生成"的导出文件返回 403 AccessDenied。
     *
     * @param url 完整下载地址
     * @return 文件字节（可能是 gzip 压缩内容，由 {@link
     *         com.tmdbwh.ingestion.export.DailyIdExportReader} 按魔数识别）
     */
    public byte[] downloadExport(HttpUrl url) {
        Objects.requireNonNull(url, "url");
        Callable<byte[]> limited = RateLimiter.decorateCallable(rateLimiter,
                Retry.decorateCallable(retry, () -> doDownload(url)));
        try {
            return limited.call();
        } catch (Exception e) {
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new TmdbException("下载导出文件失败: " + e.getMessage(), url.toString(), e);
        }
    }

    private byte[] doDownload(HttpUrl url) throws IOException {
        OkHttpClient fileClient = http.newBuilder()
                .readTimeout(Duration.ofMinutes(2))
                .callTimeout(Duration.ofMinutes(3))
                .build();
        try (Response response = fileClient.newCall(new Request.Builder().url(url).get().build()).execute()) {
            int code = response.code();
            metrics.recordRequest(code, 0L);
            if (code == 200) {
                ResponseBody body = response.body();
                if (body == null) {
                    throw new TmdbIOException(url.toString(), new IOException("响应体为空"));
                }
                return body.bytes();
            }
            String bodyText = readBodySafely(response);
            if (code == 403) {
                throw new ExportNotReadyException(url.toString(), bodyText);
            }
            if (code == 404) {
                throw new ExportNotReadyException(url.toString(), "404 Not Found");
            }
            if (code >= 500) {
                throw new TmdbServerException(code, url.toString(), bodyText);
            }
            throw new TmdbClientException(code, url.toString(), bodyText);
        }
    }

    /**
     * 构造 URL 并附加公共查询参数。
     *
     * <p>v3 api_key 作为查询参数传递，因此 URL 进入日志前必须经过 {@link Masking#maskUrl}。
     */
    public HttpUrl.Builder urlBuilder(String path) {
        String base = config.getBaseUrl();
        String full = base + path;
        HttpUrl parsed = HttpUrl.parse(full);
        if (parsed == null) {
            throw new TmdbException("非法的 TMDB URL: " + Masking.maskUrl(full), full);
        }
        HttpUrl.Builder builder = parsed.newBuilder();
        if (!bearerAuth && !config.getApiKey().isEmpty()) {
            builder.addQueryParameter("api_key", config.getApiKey());
        }
        return builder;
    }

    private static Duration parseRetryAfter(Response response) {
        String header = response.header(HEADER_RETRY_AFTER);
        if (header == null || header.trim().isEmpty()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(header.trim()));
        } catch (NumberFormatException e) {
            LOG.debug("无法解析 Retry-After 头: {}", header);
            return null;
        }
    }

    private static String readBodySafely(Response response) {
        ResponseBody body = response.body();
        if (body == null) {
            return "";
        }
        try {
            return body.string();
        } catch (IOException e) {
            return "<读取响应体失败>";
        }
    }

    /**
     * 重试等待时长：429 按服务端 Retry-After（另加 100ms 余量），IO 与其他可重试异常按指数退避，上限 30s。
     */
    private long retryWait(int attempt, Throwable failure) {
        if (failure instanceof RateLimitException) {
            Duration hint = ((RateLimitException) failure).getRetryAfter().orElse(Duration.ofSeconds(1));
            return Math.max(200L, hint.toMillis() + 100L);
        }
        // 第 1 次重试等待 base，之后逐步加倍（1s→2s→4s→8s ...），上限 30s
        int shift = Math.min(Math.max(attempt - 1, 0), 6);
        long base = failure instanceof TmdbIOException ? 500L : 1000L;
        return Math.min(30_000L, base * (1L << shift));
    }

    @Override
    public void close() {
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
    }

    /** 404：资源不存在（TMDB 每日导出文件中常包含已删除的 ID）。 */
    public static final class NotFoundException extends TmdbClientException {

        private static final long serialVersionUID = 1L;

        public NotFoundException(String url) {
            super(404, url, "The resource you requested could not be found.");
        }
    }

    /**
     * 导出文件尚未生成或不可访问（HTTP 403 / 404）。
     *
     * <p>TMDB 对"当天的导出文件还没生成"返回 403 AccessDenied；这是可预期的常态（通常在 UTC 早上生成）， 调度侧应重试而不是当作致命错误。
     */
    public static final class ExportNotReadyException extends TmdbException {

        private static final long serialVersionUID = 1L;

        private final String body;

        public ExportNotReadyException(String url, String body) {
            super("导出文件不可访问（尚未生成？）", url);
            this.body = body;
        }

        public String getBody() {
            return body;
        }
    }
}
