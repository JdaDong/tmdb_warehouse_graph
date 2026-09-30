package com.tmdbwh.common.storage;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

/**
 * 对象存储抽象（MinIO / S3 的最小子集）。
 *
 * <p>存在这层接口的意义：采集进度、水位线等关键状态需要在单元测试中用"真实可读回"的内存实现验证， 而 mock 无法提供这种语义。
 *
 * <p>语义约定（所有实现必须遵守）：
 *
 * <ul>
 *   <li>写入为覆盖写，且对读者原子可见（要么看到旧对象，要么看到完整新对象）；
 *   <li>读取不存在的对象返回 {@link Optional#empty()}，而不是抛异常；
 *   <li>SDK / IO 异常统一转换为 {@link com.tmdbwh.common.exception.StorageException}。
 * </ul>
 */
public interface ObjectStore extends AutoCloseable {

    /** 桶名。 */
    String getBucket();

    /** 写入 UTF-8 JSON 文本（覆盖写，原子可见）。 */
    void putJson(String key, String json);

    /** 写入字节内容（覆盖写，带完整性校验）。 */
    void putBytes(String key, byte[] data, String contentType);

    /** 读取对象全部字节；不存在时返回 empty。 */
    Optional<byte[]> getBytes(String key);

    /** 读取 UTF-8 文本；不存在时返回 empty。 */
    Optional<String> getString(String key);

    /** 以流方式读取对象（调用方负责关闭）。 */
    InputStream openStream(String key);

    /** 对象是否存在。 */
    boolean exists(String key);

    /** 列出前缀下的全部对象键（自动翻页，按字典序）。 */
    List<String> listKeys(String prefix);

    /** 删除单个对象（不存在时静默成功）。 */
    void delete(String key);

    /** 删除前缀下所有对象；返回删除数量。空前缀会被拒绝（防止误清空桶）。 */
    int deletePrefix(String prefix);

    /** 对象的 s3a URI（用于日志与 Spark 读取）。 */
    String uri(String key);

    @Override
    void close();
}
