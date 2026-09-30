package com.tmdbwh.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.Serializable;

/** /{type}/changes 接口返回的单条结果：仅包含实体 ID 与 adult 标记。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ChangeItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private long id;
    private Boolean adult;

    public ChangeItem() {}

    public ChangeItem(long id, Boolean adult) {
        this.id = id;
        this.adult = adult;
    }

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
}
