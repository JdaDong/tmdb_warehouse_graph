package com.tmdbwh.common.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tmdbwh.common.config.S3Config;
import com.tmdbwh.common.exception.StorageException;
import com.tmdbwh.common.util.Hashing;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

class S3ObjectStoreTest {

    private S3Client s3;
    private S3ObjectStore store;

    @BeforeEach
    void setUp() {
        s3 = mock(S3Client.class);
        store = new S3ObjectStore(s3, "tmdb-lake");
    }

    private static S3Exception status(int code) {
        return (S3Exception) S3Exception.builder().statusCode(code).message("status " + code).build();
    }

    @Test
    void putBytesSendsContentMd5AndLength() {
        byte[] data = "{\"cursor\":42}".getBytes(StandardCharsets.UTF_8);

        store.putJson("_state/full_load_movie.json", "{\"cursor\":42}");

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(req.capture(), any(RequestBody.class));
        assertThat(req.getValue().bucket()).isEqualTo("tmdb-lake");
        assertThat(req.getValue().key()).isEqualTo("_state/full_load_movie.json");
        assertThat(req.getValue().contentLength()).isEqualTo((long) data.length);
        assertThat(req.getValue().contentMD5()).isEqualTo(Hashing.md5Base64(data));
        assertThat(req.getValue().contentType()).isEqualTo("application/json");
    }

    @Test
    void putFailureIsWrappedWithUri() {
        when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(SdkClientException.create("connection refused"));

        assertThatThrownBy(() -> store.putBytes("raw/x", new byte[] {1}, null))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("s3a://tmdb-lake/raw/x");
    }

    @Test
    void putFileUsesFileSize(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("part.ndjson.gz");
        Files.write(f, new byte[128]);

        store.putFile("raw/movie/dt=2026-09-30/part.ndjson.gz", f, "application/gzip");

        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3).putObject(req.capture(), any(RequestBody.class));
        assertThat(req.getValue().contentLength()).isEqualTo(128L);
        assertThat(req.getValue().contentType()).isEqualTo("application/gzip");
        assertThatThrownBy(() -> store.putFile("k", dir.resolve("missing"), null))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("读取本地文件失败");
    }

    @Test
    void getReturnsEmptyWhenMissing() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("nope").build());
        assertThat(store.getBytes("_state/none.json")).isEmpty();
        assertThat(store.getString("_state/none.json")).isEmpty();
    }

    @Test
    void getReturnsEmptyOn404AndFailsOnOtherErrors() {
        when(s3.getObjectAsBytes(any(GetObjectRequest.class))).thenThrow(status(404)).thenThrow(status(403));

        assertThat(store.getBytes("a")).isEmpty();
        assertThatThrownBy(() -> store.getBytes("a")).isInstanceOf(StorageException.class);
    }

    @Test
    void getStringDecodesUtf8() {
        byte[] body = "热度".getBytes(StandardCharsets.UTF_8);
        when(s3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), body));
        assertThat(store.getString("k")).contains("热度");
    }

    @Test
    void existsSemantics() {
        when(s3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build())
                .thenThrow(NoSuchKeyException.builder().build())
                .thenThrow(status(404))
                .thenThrow(status(500));

        assertThat(store.exists("k")).isTrue();
        assertThat(store.exists("k")).isFalse();
        assertThat(store.exists("k")).isFalse();
        assertThatThrownBy(() -> store.exists("k")).isInstanceOf(StorageException.class);
    }

    @Test
    void listKeysFollowsContinuationTokens() {
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(S3Object.builder().key("raw/a").build(), S3Object.builder().key("raw/b").build())
                        .isTruncated(true).nextContinuationToken("t1").build())
                .thenReturn(ListObjectsV2Response.builder()
                        .contents(S3Object.builder().key("raw/c").build())
                        .isTruncated(false).build());

        assertThat(store.listKeys("raw/")).containsExactly("raw/a", "raw/b", "raw/c");

        ArgumentCaptor<ListObjectsV2Request> req = ArgumentCaptor.forClass(ListObjectsV2Request.class);
        verify(s3, times(2)).listObjectsV2(req.capture());
        assertThat(req.getAllValues().get(0).continuationToken()).isNull();
        assertThat(req.getAllValues().get(1).continuationToken()).isEqualTo("t1");
        assertThat(req.getAllValues()).allSatisfy(r -> assertThat(r.prefix()).isEqualTo("raw/"));
    }

    @Test
    void deletePrefixBatchesByThousand() {
        List<S3Object> objects = new ArrayList<>();
        for (int i = 0; i < 2345; i++) {
            objects.add(S3Object.builder().key("raw/movie/dt=2026-09-30/f" + i).build());
        }
        when(s3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(objects).isTruncated(false).build());

        int deleted = store.deletePrefix("raw/movie/dt=2026-09-30/");

        assertThat(deleted).isEqualTo(2345);
        ArgumentCaptor<DeleteObjectsRequest> req = ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(s3, times(3)).deleteObjects(req.capture());
        assertThat(req.getAllValues()).extracting(r -> r.delete().objects().size()).containsExactly(1000, 1000, 345);
    }

    @Test
    void deletePrefixRefusesEmptyPrefix() {
        assertThatThrownBy(() -> store.deletePrefix("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.deletePrefix("/")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.deletePrefix(null)).isInstanceOf(IllegalArgumentException.class);
        verify(s3, never()).listObjectsV2(any(ListObjectsV2Request.class));
    }

    @Test
    void ensureBucketCreatesOnlyWhenMissing() {
        when(s3.headBucket(any(HeadBucketRequest.class))).thenThrow(status(404));
        store.ensureBucket();
        verify(s3).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void ensureBucketFailsOnForbidden() {
        when(s3.headBucket(any(HeadBucketRequest.class))).thenThrow(status(403));
        assertThatThrownBy(() -> store.ensureBucket()).isInstanceOf(StorageException.class);
        verify(s3, never()).createBucket(any(CreateBucketRequest.class));
    }

    @Test
    void deleteAndClose() {
        store.delete("k");
        verify(s3).deleteObject(any(DeleteObjectRequest.class));
        store.close();
        verify(s3).close();
        assertThat(store.uri("raw/x")).isEqualTo("s3a://tmdb-lake/raw/x");
        assertThat(store.getBucket()).isEqualTo("tmdb-lake");
    }

    @Test
    void factoryBuildsClientFromConfig() {
        S3Config cfg = new S3Config("http://localhost:9000", "us-east-1", "minio", "minio123", "tmdb-lake", true);
        try (S3ObjectStore real = S3ObjectStore.create(cfg)) {
            assertThat(real.getBucket()).isEqualTo("tmdb-lake");
        }
        assertThat(cfg.hasStaticCredentials()).isTrue();
        assertThat(cfg.toString()).doesNotContain("minio123");
    }
}
