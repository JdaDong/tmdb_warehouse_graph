package com.tmdbwh.common.config;

import com.typesafe.config.Config;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

/** Iceberg 湖仓配置（对应 {@code tmdbwh.iceberg}）。 */
public final class IcebergConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String catalogName;
    private final String catalogType;
    private final String metastoreUri;
    private final String warehouse;

    private IcebergConfig(Config c) {
        this.catalogName = ConfigSupport.trim(c.getString("catalog-name"));
        this.catalogType = ConfigSupport.trim(c.getString("catalog-type"));
        this.metastoreUri = ConfigSupport.trim(c.getString("metastore-uri"));
        this.warehouse = ConfigSupport.trim(c.getString("warehouse"));
    }

    static IcebergConfig from(Config c) {
        return new IcebergConfig(c);
    }

    void validate(List<String> errors) {
        ConfigSupport.requireNonBlank(errors, "tmdbwh.iceberg.catalog-name", catalogName);
        if (!Arrays.asList("hive", "hadoop", "rest").contains(catalogType)) {
            errors.add("tmdbwh.iceberg.catalog-type 仅支持 hive / hadoop / rest，当前值: " + catalogType);
        }
        if ("hive".equals(catalogType)) {
            ConfigSupport.requireUri(errors, "tmdbwh.iceberg.metastore-uri", metastoreUri,
                    new HashSet<>(Arrays.asList("thrift")));
        }
        ConfigSupport.requireUri(errors, "tmdbwh.iceberg.warehouse", warehouse,
                new HashSet<>(Arrays.asList("s3a", "s3", "hdfs", "file")));
    }

    public String getCatalogName() {
        return catalogName;
    }

    public String getCatalogType() {
        return catalogType;
    }

    public String getMetastoreUri() {
        return metastoreUri;
    }

    public String getWarehouse() {
        return warehouse;
    }

    @Override
    public String toString() {
        return "IcebergConfig{catalogName=" + catalogName + ", catalogType=" + catalogType + ", metastoreUri="
                + metastoreUri + ", warehouse=" + warehouse + "}";
    }
}
