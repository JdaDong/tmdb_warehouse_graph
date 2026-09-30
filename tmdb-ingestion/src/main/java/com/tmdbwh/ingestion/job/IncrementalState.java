package com.tmdbwh.ingestion.job;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 增量采集水位线：每个实体类型一个"已采集到的日期"。
 *
 * <p>推进策略：成功处理完某个日期窗口后才推进，失败不推进（下次运行会重放该窗口，配合幂等写入不会重复）。 水位线以字符串存储（yyyy-MM-dd），避免 JSON 中的日期格式歧义。
 */
public class IncrementalState {

    private final Map<String, String> watermarks = new LinkedHashMap<>();

    /** 查询某实体类型的水位线。 */
    @JsonIgnore
    public LocalDate watermarkOf(String entityType) {
        String v = watermarks.get(entityType);
        return v == null ? null : LocalDate.parse(v);
    }

    /** 设置某实体类型的水位线。 */
    @JsonIgnore
    public void setWatermark(String entityType, LocalDate date) {
        if (date == null) {
            watermarks.remove(entityType);
        } else {
            watermarks.put(entityType, date.toString());
        }
    }

    public Map<String, String> getWatermarks() {
        return watermarks;
    }

    public void setWatermarks(Map<String, String> watermarks) {
        this.watermarks.clear();
        if (watermarks != null) {
            this.watermarks.putAll(watermarks);
        }
    }

    @Override
    public String toString() {
        return "IncrementalState" + watermarks;
    }
}
