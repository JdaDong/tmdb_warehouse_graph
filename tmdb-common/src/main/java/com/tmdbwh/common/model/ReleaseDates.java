package com.tmdbwh.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 分国家上映信息（append_to_response=release_dates）。
 *
 * <p>type 取值：1 首映 / 2 小范围院线 / 3 院线 / 4 数字 / 5 实体介质 / 6 电视。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReleaseDates implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<CountryRelease> results = new ArrayList<>();

    public List<CountryRelease> getResults() {
        return results;
    }

    public void setResults(List<CountryRelease> results) {
        this.results = results == null ? new ArrayList<>() : results;
    }

    /** 某国家 / 地区的上映记录集合。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class CountryRelease implements Serializable {

        private static final long serialVersionUID = 1L;

        @JsonProperty("iso_3166_1")
        private String iso31661;

        @JsonProperty("release_dates")
        private List<ReleaseDate> releaseDates = new ArrayList<>();

        public String getIso31661() {
            return iso31661;
        }

        public void setIso31661(String iso31661) {
            this.iso31661 = iso31661;
        }

        public List<ReleaseDate> getReleaseDates() {
            return releaseDates;
        }

        public void setReleaseDates(List<ReleaseDate> releaseDates) {
            this.releaseDates = releaseDates == null ? new ArrayList<>() : releaseDates;
        }
    }

    /** 单条上映记录。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReleaseDate implements Serializable {

        private static final long serialVersionUID = 1L;

        private String certification;

        @JsonProperty("iso_639_1")
        private String iso6391;

        @JsonProperty("release_date")
        private String releaseDate;

        private Integer type;
        private String note;

        public String getCertification() {
            return certification;
        }

        public void setCertification(String certification) {
            this.certification = certification;
        }

        public String getIso6391() {
            return iso6391;
        }

        public void setIso6391(String iso6391) {
            this.iso6391 = iso6391;
        }

        public String getReleaseDate() {
            return releaseDate;
        }

        public void setReleaseDate(String releaseDate) {
            this.releaseDate = releaseDate;
        }

        public Integer getType() {
            return type;
        }

        public void setType(Integer type) {
            this.type = type;
        }

        public String getNote() {
            return note;
        }

        public void setNote(String note) {
            this.note = note;
        }
    }
}
