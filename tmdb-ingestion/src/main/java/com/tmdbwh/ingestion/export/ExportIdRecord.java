package com.tmdbwh.ingestion.export;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;

/**
 * TMDB 每日 ID 导出文件中的一行。
 *
 * <p>字段因实体类型略有差异：电影有 {@code title} / {@code video}，剧集与人物为 {@code name}，人物还有 {@code
 * gender}；未知字段一律忽略。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExportIdRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    private long id;
    private Boolean adult;
    private Double popularity;
    private String title;
    private String name;
    private Boolean video;
    private Integer gender;

    @JsonProperty("known_for_department")
    private String knownForDepartment;

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    public Boolean getAdult() {
        return adult;
    }

    public void setAdult(Boolean adult) {
        this.adult = adult;
    }

    public Double getPopularity() {
        return popularity;
    }

    public void setPopularity(Double popularity) {
        this.popularity = popularity;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getVideo() {
        return video;
    }

    public void setVideo(Boolean video) {
        this.video = video;
    }

    public Integer getGender() {
        return gender;
    }

    public void setGender(Integer gender) {
        this.gender = gender;
    }

    public String getKnownForDepartment() {
        return knownForDepartment;
    }

    public void setKnownForDepartment(String knownForDepartment) {
        this.knownForDepartment = knownForDepartment;
    }

    /** 展示名：电影用 title，其余用 name。 */
    public String displayName() {
        if (title != null && !title.isEmpty()) {
            return title;
        }
        return name;
    }

    @Override
    public String toString() {
        return "ExportIdRecord{id=" + id + ", name=" + displayName() + ", popularity=" + popularity + "}";
    }
}
