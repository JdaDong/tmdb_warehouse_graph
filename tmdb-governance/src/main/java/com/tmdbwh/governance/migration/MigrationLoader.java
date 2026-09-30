package com.tmdbwh.governance.migration;

import com.tmdbwh.common.util.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 迁移脚本加载器：同时支持 classpath 目录（打包进 jar 的默认迁移）与文件系统目录（运维自定义迁移）。
 *
 * <p>加载后按版本号升序返回，并立即校验：文件名不规范、脚本为空、版本号重复都会在"连上数据库之前"暴露，
 * 避免带着错误脚本执行到生产库。
 */
public final class MigrationLoader {

    /** 文件名规范：V1__xxx.sql。 */
    private static final Pattern FILE_NAME = Pattern.compile("^V(\\d+)__([A-Za-z0-9_\\-]+)\\.sql$");
    /** 脚本首行的 -- V<n> ... 说明（可选）。 */
    private static final Pattern HEADER = Pattern.compile("^--\\s*V(\\d+)\\s*(.*)$");
    /** 校验和长度（SHA-256 截断，够用且便于人工比对）。 */
    private static final int CHECKSUM_LENGTH = 16;

    private MigrationLoader() {}

    /** 从 classpath 目录加载（jar 内部的默认迁移）。 */
    public static List<Migration> fromClasspath(String resourceDir) throws IOException {
        Objects.requireNonNull(resourceDir, "resourceDir");
        List<Migration> migrations = new ArrayList<>();
        for (String fileName : listSqlNames(resourceDir)) {
            migrations.add(parse(fileName, readClasspath(resourceDir + "/" + fileName)));
        }
        return finish(migrations, "classpath:" + resourceDir);
    }

    /** 从文件系统目录加载；目录不存在时返回空列表。 */
    public static List<Migration> fromDirectory(Path dir) throws IOException {
        Objects.requireNonNull(dir, "dir");
        List<Migration> migrations = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return migrations;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.sql")) {
            for (Path file : stream) {
                migrations.add(parse(file.getFileName().toString(),
                        new String(Files.readAllBytes(file), StandardCharsets.UTF_8)));
            }
        }
        return finish(migrations, dir.toString());
    }

    private static Migration parse(String fileName, String sql) {
        Matcher matcher = FILE_NAME.matcher(fileName);
        if (!matcher.matches()) {
            throw new IllegalStateException("迁移文件名不符合规范 V<version>__<name>.sql: " + fileName);
        }
        int version = Integer.parseInt(matcher.group(1));
        String name = matcher.group(2);

        String body = sql;
        Matcher header = HEADER.matcher(firstNonEmptyLine(sql));
        if (header.matches() && Integer.parseInt(header.group(1)) == version) {
            // 去掉首行说明注释，让校验和只覆盖真正的 SQL（改注释不算变更）
            int newline = sql.indexOf('\n');
            body = newline < 0 ? "" : sql.substring(newline + 1);
        }
        if (com.tmdbwh.common.clickhouse.SqlScriptSplitter.split(body).isEmpty()) {
            throw new IllegalStateException("迁移脚本中没有任何可执行语句: " + fileName);
        }
        return new Migration(version, name, fileName, body, Hashing.sha256Hex(body).substring(0, CHECKSUM_LENGTH));
    }

    private static List<Migration> finish(List<Migration> migrations, String source) {
        if (migrations.isEmpty()) {
            throw new IllegalStateException("未找到任何迁移脚本: " + source);
        }
        Collections.sort(migrations);
        // 版本号必须唯一：否则执行顺序不确定，也无法判断"已执行的到底是哪一版"
        for (int i = 1; i < migrations.size(); i++) {
            if (migrations.get(i).getVersion() == migrations.get(i - 1).getVersion()) {
                throw new IllegalStateException("迁移版本号重复: V" + migrations.get(i).getVersion()
                        + "（" + migrations.get(i - 1).getFileName() + " 与 " + migrations.get(i).getFileName() + "）");
            }
        }
        return migrations;
    }

    /** 列出 classpath 目录下的 .sql 文件名（兼容目录与 jar 两种形态）。 */
    private static List<String> listSqlNames(String resourceDir) throws IOException {
        java.net.URL resource = MigrationLoader.class.getClassLoader().getResource(resourceDir);
        if (resource == null) {
            throw new IOException("classpath 目录不存在: " + resourceDir);
        }
        URI uri;
        try {
            uri = resource.toURI();
        } catch (URISyntaxException e) {
            throw new IOException("解析 classpath 目录失败: " + resourceDir, e);
        }
        List<String> names = new ArrayList<>();
        if ("jar".equals(uri.getScheme())) {
            names.addAll(listFromJar(uri, resourceDir));
        } else {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(Path.of(uri), "*.sql")) {
                for (Path p : stream) {
                    names.add(p.getFileName().toString());
                }
            }
        }
        Collections.sort(names);
        return names;
    }

    /** jar 内目录无法用 File.list 列举，通过 zip 文件系统扫描。 */
    private static List<String> listFromJar(URI resourceUri, String resourceDir) throws IOException {
        URI jarUri = URI.create(resourceUri.toString().substring(0, resourceUri.toString().indexOf("!/")));
        List<String> names = new ArrayList<>();
        FileSystem fs = null;
        try {
            fs = FileSystems.newFileSystem(jarUri, java.util.Map.of());
        } catch (java.nio.file.FileSystemAlreadyExistsException e) {
            fs = FileSystems.getFileSystem(jarUri);
        }
        try {
            Path dir = fs.getPath("/" + resourceDir);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.sql")) {
                for (Path p : stream) {
                    names.add(p.getFileName().toString());
                }
            }
        } finally {
            if (fs != null && fs != FileSystems.getDefault()) {
                // 首次新建的文件系统需要关闭；已存在的不关，避免影响其他使用者
                try {
                    fs.close();
                } catch (UnsupportedOperationException ignored) {
                    // FileSystems.getDefault() 不可关闭
                }
            }
        }
        return names;
    }

    private static String firstNonEmptyLine(String sql) {
        for (String line : sql.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return "";
    }

    private static String readClasspath(String resource) throws IOException {
        try (InputStream in = MigrationLoader.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("classpath 资源不存在: " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
