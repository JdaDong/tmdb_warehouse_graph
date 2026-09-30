package com.tmdbwh.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;

/** 出品国家 / 地区（ISO 3166-1 alpha-2）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProductionCountry implements Serializable {

    private static final long serialVersionUID = 1L;

    @JsonProperty("iso_3166_1")
    private String iso31661;

    private String name;

    public ProductionCountry() {}

    public ProductionCountry(String iso31661, String name) {
        this.iso31661 = iso31661;
        this.name = name;
    }

    public String getIso31661() {
        return iso31661;
    }

    public void setIso31661(String iso31661) {
        this.iso31661 = iso31661;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
