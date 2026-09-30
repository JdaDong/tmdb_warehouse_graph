package com.tmdbwh.common.storage;

import com.tmdbwh.common.config.S3Config;
import com.tmdbwh.common.exception.StorageException;
import com.tmdbwh.common.util.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * MinIO / S3 对象存储封装（单桶）。
 *
 * <p>设计说明：
 *
 * <ul>
 *   <li><b>原子性</b>：S3 / MinIO 的单次 PUT 对读者是原子的（要么看到旧对象，要么看到完整新对象）， 因此状态文件直接覆盖写即可；写入时附带
 *       Content-MD5，服务端校验失败会拒绝写入，保证内容完整；
 *   <li><b>缺失语义</b>：读取不存在的对象返回 {@link Optional#empty()} 而不是抛异常，调用方据此判断"首次运行"；
 *   <li><b>重试</b>：依赖 AWS SDK 内置的指数退避重试（默认 3 次），业务层无需再包一层；
 *   <li>所有 SDK 异常统一转换为 {@link StorageException}，消息中包含 bucket/key 便于排查。
 * </ul>
 *
 * <p>线程安全：底层 {@link S3Client} 线程安全，本类可在多线程间共享。
 */
public class S3ObjectStore implements ObjectStore {

    private static final Logger LOG = LoggerFactory.getLogger(S3ObjectStore.class);

    /** DeleteObjects 单次最多删除 1000 个对象（S3 协议限制）。 */
    static final int DELETE_BATCH = 1000;

    private static final int HTTP_NOT_FOUND = 404;

    private final S3Client client;
    private final String bucket;

    /**
     * 使用外部创建的客户端构造（便于测试注入）。
     *
     * @param client S3 客户端，由本实例负责关闭
     * @param bucket 桶名
     */
    public S3ObjectStore(S3Client client, String bucket) {
        this.client = Objects.requireNonNull(client, "client");
        this.bucket = Objects.requireNonNull(bucket, "bucket");
    }

    /** 按配置创建：路径风格访问 + 轻量 URLConnection HTTP 客户端。 */
    public static S3ObjectStore create(S3Config cfg) {
        AwsCredentialsProvider credentials = cfg.hasStaticCredentials()
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(cfg.getAccessKey(), cfg.getSecretKey()))
                : DefaultCredentialsProvider.create();
        S3Client client = S3Client.builder()
                .endpointOverride(URI.create(cfg.getEndpoint()))
                .region(Region.of(cfg.getRegion()))
                .credentialsProvider(credentials)
                .httpClient(UrlConnectionHttpClient.create())
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(cfg.isPathStyleAccess())
                        .build())
                .build();
        return new S3ObjectStore(client, cfg.getBucket());
    }

    public String getBucket() {
        return bucket;
    }

    /** 桶不存在时创建（幂等）。 */
    public void ensureBucket() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (S3Exception e) {
            if (e.statusCode() != HTTP_NOT_FOUND) {
                throw new StorageException("检查桶失败: " + bucket, e);
            }
            try {
                client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                LOG.info("已创建存储桶 {}", bucket);
            } catch (SdkException ce) {
                throw new StorageException("创建桶失败: " + bucket, ce);
            }
        } catch (SdkException e) {
            throw new StorageException("检查桶失败: " + bucket, e);
        }
    }

    /**
     * 写入字节内容（覆盖写，带 Content-MD5 完整性校验）。
     *
     * @param key 对象键
     * @param data 内容
     * @param contentType MIME 类型，可为 null
     */
    public void putBytes(String key, byte[] data, String contentType) {
        Objects.requireNonNull(data, "data");
        PutObjectRequest.Builder req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentLength((long) data.length)
                .contentMD5(Hashing.md5Base64(data));
        if (contentType != null) {
            req.contentType(contentType);
        }
        try {
            client.putObject(req.build(), RequestBody.fromBytes(data));
        } catch (SdkException e) {
            throw new StorageException("写入对象失败: " + uri(key), e);
        }
    }

    /** 写入 UTF-8 JSON 文本。 */
    public void putJson(String key, String json) {
        putBytes(key, json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    /** 上传本地文件（单次 PUT，适用于 ≤ 5GB 的文件；原始区单文件按 64MB 滚动，满足要求）。 */
    public void putFile(String key, Path file, String contentType) {
        try {
            long size = Files.size(file);
            PutObjectRequest.Builder req = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .contentLength(size);
            if (contentType != null) {
                req.contentType(contentType);
            }
            client.putObject(req.build(), RequestBody.fromFile(file));
        } catch (IOException e) {
            throw new StorageException("读取本地文件失败: " + file, e);
        } catch (SdkException e) {
            throw new StorageException("上传文件失败: " + uri(key), e);
        }
    }

    /** 读取对象全部字节；不存在时返回 empty。 */
    public Optional<byte[]> getBytes(String key) {
        try {
            ResponseBytes<GetObjectResponse> bytes = client.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return Optional.of(bytes.asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (e.statusCode() == HTTP_NOT_FOUND) {
                return Optional.empty();
            }
            throw new StorageException("读取对象失败: " + uri(key), e);
        } catch (SdkException e) {
            throw new StorageException("读取对象失败: " + uri(key), e);
        }
    }

    /** 读取 UTF-8 文本；不存在时返回 empty。 */
    public Optional<String> getString(String key) {
        return getBytes(key).map(b -> new String(b, StandardCharsets.UTF_8));
    }

    /**
     * 以流方式读取大对象（调用方负责关闭）。
     *
     * @throws StorageException 对象不存在或读取失败
     */
    public InputStream openStream(String key) {
        try {
            ResponseInputStream<GetObjectResponse> in = client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            return in;
        } catch (SdkException e) {
            throw new StorageException("打开对象流失败: " + uri(key), e);
        }
    }

    /** 对象是否存在。 */
    public boolean exists(String key) {
        try {
            client.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == HTTP_NOT_FOUND) {
                return false;
            }
            throw new StorageException("检查对象失败: " + uri(key), e);
        } catch (SdkException e) {
            throw new StorageException("检查对象失败: " + uri(key), e);
        }
    }

    /**
     * 列出前缀下的全部对象键（自动翻页，按字典序）。
     *
     * @param prefix 前缀，例如 {@code raw/movie/dt=2026-09-30/}
     */
    public List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        String token = null;
        try {
            do {
                ListObjectsV2Request.Builder req = ListObjectsV2Request.builder().bucket(bucket).prefix(prefix);
                if (token != null) {
                    req.continuationToken(token);
                }
                ListObjectsV2Response resp = client.listObjectsV2(req.build());
                for (S3Object o : resp.contents()) {
                    keys.add(o.key());
                }
                token = Boolean.TRUE.equals(resp.isTruncated()) ? resp.nextContinuationToken() : null;
            } while (token != null);
        } catch (SdkException e) {
            throw new StorageException("列举对象失败: " + uri(prefix), e);
        }
        return keys;
    }

    /** 删除单个对象（不存在时静默成功）。 */
    public void delete(String key) {
        try {
            client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException e) {
            throw new StorageException("删除对象失败: " + uri(key), e);
        }
    }

    /**
     * 删除前缀下所有对象（分批，每批 ≤ 1000）。用于重跑前清理分区。
     *
     * @return 删除的对象数
     */
    public int deletePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty() || "/".equals(prefix)) {
            throw new IllegalArgumentException("拒绝删除空前缀（会清空整个桶）");
        }
        List<String> keys = listKeys(prefix);
        for (int from = 0; from < keys.size(); from += DELETE_BATCH) {
            List<ObjectIdentifier> batch = keys.subList(from, Math.min(from + DELETE_BATCH, keys.size()))
                    .stream()
                    .map(k -> ObjectIdentifier.builder().key(k).build())
                    .collect(Collectors.toList());
            try {
                client.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(batch).quiet(true).build())
                        .build());
            } catch (SdkException e) {
                throw new StorageException("批量删除失败: " + uri(prefix), e);
            }
        }
        if (!keys.isEmpty()) {
            LOG.info("已删除前缀 {} 下的 {} 个对象", uri(prefix), keys.size());
        }
        return keys.size();
    }

    /** 对象的 s3a URI（用于日志与 Spark 读取）。 */
    public String uri(String key) {
        return LakePaths.s3a(bucket, key == null ? "" : key);
    }

    @Override
    public void close() {
        client.close();
    }
}
