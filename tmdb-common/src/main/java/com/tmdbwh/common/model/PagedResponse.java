package com.tmdbwh.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * TMDB 通用分页响应（changes / trending / popular / discover 等）。
 *
 * @param <T> 结果条目类型
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class PagedResponse<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** TMDB 分页接口允许请求的最大页码。 */
    public static final int MAX_PAGE = 500;

    private int page;
    private List<T> results = new ArrayList<>();
    private int totalPages;
    private int totalResults;

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public List<T> getResults() {
        return results;
    }

    public void setResults(List<T> results) {
        this.results = results == null ? new ArrayList<>() : results;
    }

    public int getTotalPages() {
        return totalPages;
    }

    public void setTotalPages(int totalPages) {
        this.totalPages = totalPages;
    }

    public int getTotalResults() {
        return totalResults;
    }

    public void setTotalResults(int totalResults) {
        this.totalResults = totalResults;
    }

    /** 是否还有下一页（受 TMDB 最大 500 页限制）。 */
    @JsonIgnore
    public boolean hasNext() {
        return page < totalPages && page < MAX_PAGE;
    }
}
