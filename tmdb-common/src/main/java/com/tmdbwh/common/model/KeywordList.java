package com.tmdbwh.common.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * append_to_response=keywords 的返回包装。
 *
 * <p>TMDB 的不一致之处：电影返回 {@code {"keywords": [...]}}，剧集返回 {@code {"results": [...]}}， 通过 {@link #all()} 统一读取。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KeywordList implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<Keyword> keywords;
    private List<Keyword> results;

    public List<Keyword> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<Keyword> keywords) {
        this.keywords = keywords;
    }

    public List<Keyword> getResults() {
        return results;
    }

    public void setResults(List<Keyword> results) {
        this.results = results;
    }

    /** 合并两种形态的关键词列表，永不返回 null。 */
    @JsonIgnore
    public List<Keyword> all() {
        if (keywords == null && results == null) {
            return Collections.emptyList();
        }
        List<Keyword> merged = new ArrayList<>();
        if (keywords != null) {
            merged.addAll(keywords);
        }
        if (results != null) {
            merged.addAll(results);
        }
        return merged;
    }
}
