package com.tmdbwh.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 电影详情（GET /movie/{id}?append_to_response=credits,keywords,release_dates）。
 *
 * <p>字段与 TMDB 响应一一对应（snake_case）。金额字段 budget / revenue 单位为美元，0 表示未知而非真实为 0， 下游 DWD 层需将 0 清洗为 NULL。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class Movie implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private String imdbId;
    private String title;
    private String originalTitle;
    private String originalLanguage;
    private String overview;
    private String tagline;
    private String status;
    private String releaseDate;
    private Integer runtime;
    private Long budget;
    private Long revenue;
    private Boolean adult;
    private Boolean video;
    private Double popularity;
    private Double voteAverage;
    private Integer voteCount;
    private String homepage;
    private String posterPath;
    private String backdropPath;
    private CollectionRef belongsToCollection;
    private List<Genre> genres = new ArrayList<>();
    private List<ProductionCompany> productionCompanies = new ArrayList<>();
    private List<ProductionCountry> productionCountries = new ArrayList<>();
    private List<SpokenLanguage> spokenLanguages = new ArrayList<>();
    private Credits credits;
    private KeywordList keywords;
    private ReleaseDates releaseDates;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getImdbId() {
        return imdbId;
    }

    public void setImdbId(String imdbId) {
        this.imdbId = imdbId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getOriginalTitle() {
        return originalTitle;
    }

    public void setOriginalTitle(String originalTitle) {
        this.originalTitle = originalTitle;
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

    public String getReleaseDate() {
        return releaseDate;
    }

    public void setReleaseDate(String releaseDate) {
        this.releaseDate = releaseDate;
    }

    public Integer getRuntime() {
        return runtime;
    }

    public void setRuntime(Integer runtime) {
        this.runtime = runtime;
    }

    public Long getBudget() {
        return budget;
    }

    public void setBudget(Long budget) {
        this.budget = budget;
    }

    public Long getRevenue() {
        return revenue;
    }

    public void setRevenue(Long revenue) {
        this.revenue = revenue;
    }

    public Boolean getAdult() {
        return adult;
    }

    public void setAdult(Boolean adult) {
        this.adult = adult;
    }

    public Boolean getVideo() {
        return video;
    }

    public void setVideo(Boolean video) {
        this.video = video;
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

    public String getBackdropPath() {
        return backdropPath;
    }

    public void setBackdropPath(String backdropPath) {
        this.backdropPath = backdropPath;
    }

    public CollectionRef getBelongsToCollection() {
        return belongsToCollection;
    }

    public void setBelongsToCollection(CollectionRef belongsToCollection) {
        this.belongsToCollection = belongsToCollection;
    }

    public List<Genre> getGenres() {
        return genres;
    }

    public void setGenres(List<Genre> genres) {
        this.genres = genres == null ? new ArrayList<>() : genres;
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

    public ReleaseDates getReleaseDates() {
        return releaseDates;
    }

    public void setReleaseDates(ReleaseDates releaseDates) {
        this.releaseDates = releaseDates;
    }

    @Override
    public String toString() {
        return "Movie{id=" + id + ", title='" + title + "', releaseDate=" + releaseDate + "}";
    }
}
