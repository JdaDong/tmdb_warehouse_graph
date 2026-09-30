package com.tmdbwh.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 剧集详情（GET /tv/{id}?append_to_response=credits,keywords）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class TvShow implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String originalName;
    private String originalLanguage;
    private String overview;
    private String tagline;
    private String status;
    private String type;
    private String firstAirDate;
    private String lastAirDate;
    private Boolean inProduction;
    private Boolean adult;
    private Integer numberOfSeasons;
    private Integer numberOfEpisodes;
    private List<Integer> episodeRunTime = new ArrayList<>();
    private Double popularity;
    private Double voteAverage;
    private Integer voteCount;
    private String homepage;
    private String posterPath;
    private List<String> originCountry = new ArrayList<>();
    private List<Genre> genres = new ArrayList<>();
    private List<ProductionCompany> networks = new ArrayList<>();
    private List<ProductionCompany> productionCompanies = new ArrayList<>();
    private List<ProductionCountry> productionCountries = new ArrayList<>();
    private List<SpokenLanguage> spokenLanguages = new ArrayList<>();
    private List<Creator> createdBy = new ArrayList<>();
    private Credits credits;
    private KeywordList keywords;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getOriginalName() {
        return originalName;
    }

    public void setOriginalName(String originalName) {
        this.originalName = originalName;
    }

    public String getOriginalLanguage() {
        return originalLanguage;
    }

    public void setOriginalLanguage(String originalLanguage) {
        this.originalLanguage = originalLanguage;
    }

    public String getOverview() {
        return overview;
    }

    public void setOverview(String overview) {
        this.overview = overview;
    }

    public String getTagline() {
        return tagline;
    }

    public void setTagline(String tagline) {
        this.tagline = tagline;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getFirstAirDate() {
        return firstAirDate;
    }

    public void setFirstAirDate(String firstAirDate) {
        this.firstAirDate = firstAirDate;
    }

    public String getLastAirDate() {
        return lastAirDate;
    }

    public void setLastAirDate(String lastAirDate) {
        this.lastAirDate = lastAirDate;
    }

    public Boolean getInProduction() {
        return inProduction;
    }

    public void setInProduction(Boolean inProduction) {
        this.inProduction = inProduction;
    }

    public Boolean getAdult() {
        return adult;
    }

    public void setAdult(Boolean adult) {
        this.adult = adult;
    }

    public Integer getNumberOfSeasons() {
        return numberOfSeasons;
    }

    public void setNumberOfSeasons(Integer numberOfSeasons) {
        this.numberOfSeasons = numberOfSeasons;
    }

    public Integer getNumberOfEpisodes() {
        return numberOfEpisodes;
    }

    public void setNumberOfEpisodes(Integer numberOfEpisodes) {
        this.numberOfEpisodes = numberOfEpisodes;
    }

    public List<Integer> getEpisodeRunTime() {
        return episodeRunTime;
    }

    public void setEpisodeRunTime(List<Integer> episodeRunTime) {
        this.episodeRunTime = episodeRunTime == null ? new ArrayList<>() : episodeRunTime;
    }

    public Double getPopularity() {
        return popularity;
    }

    public void setPopularity(Double popularity) {
        this.popularity = popularity;
    }

    public Double getVoteAverage() {
        return voteAverage;
    }

    public void setVoteAverage(Double voteAverage) {
        this.voteAverage = voteAverage;
    }

    public Integer getVoteCount() {
        return voteCount;
    }

    public void setVoteCount(Integer voteCount) {
        this.voteCount = voteCount;
    }

    public String getHomepage() {
        return homepage;
    }

    public void setHomepage(String homepage) {
        this.homepage = homepage;
    }

    public String getPosterPath() {
        return posterPath;
    }

    public void setPosterPath(String posterPath) {
        this.posterPath = posterPath;
    }

    public List<String> getOriginCountry() {
        return originCountry;
    }

    public void setOriginCountry(List<String> originCountry) {
        this.originCountry = originCountry == null ? new ArrayList<>() : originCountry;
    }

    public List<Genre> getGenres() {
        return genres;
    }

    public void setGenres(List<Genre> genres) {
        this.genres = genres == null ? new ArrayList<>() : genres;
    }

    public List<ProductionCompany> getNetworks() {
        return networks;
    }

    public void setNetworks(List<ProductionCompany> networks) {
        this.networks = networks == null ? new ArrayList<>() : networks;
    }

    public List<ProductionCompany> getProductionCompanies() {
        return productionCompanies;
    }

    public void setProductionCompanies(List<ProductionCompany> productionCompanies) {
        this.productionCompanies = productionCompanies == null ? new ArrayList<>() : productionCompanies;
    }

    public List<ProductionCountry> getProductionCountries() {
        return productionCountries;
    }

    public void setProductionCountries(List<ProductionCountry> productionCountries) {
        this.productionCountries = productionCountries == null ? new ArrayList<>() : productionCountries;
    }

    public List<SpokenLanguage> getSpokenLanguages() {
        return spokenLanguages;
    }

    public void setSpokenLanguages(List<SpokenLanguage> spokenLanguages) {
        this.spokenLanguages = spokenLanguages == null ? new ArrayList<>() : spokenLanguages;
    }

    public List<Creator> getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(List<Creator> createdBy) {
        this.createdBy = createdBy == null ? new ArrayList<>() : createdBy;
    }

    public Credits getCredits() {
        return credits;
    }

    public void setCredits(Credits credits) {
        this.credits = credits;
    }

    public KeywordList getKeywords() {
        return keywords;
    }

    public void setKeywords(KeywordList keywords) {
        this.keywords = keywords;
    }

    @Override
    public String toString() {
        return "TvShow{id=" + id + ", name='" + name + "', firstAirDate=" + firstAirDate + "}";
    }

    /** 剧集主创（created_by）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public static class Creator implements Serializable {

        private static final long serialVersionUID = 1L;

        private Long id;
        private String creditId;
        private String name;
        private Integer gender;

        public Long getId() {
            return id;
        }

        public void setId(Long id) {
            this.id = id;
        }

        public String getCreditId() {
            return creditId;
        }

        public void setCreditId(String creditId) {
            this.creditId = creditId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getGender() {
            return gender;
        }

        public void setGender(Integer gender) {
            this.gender = gender;
        }
    }
}
