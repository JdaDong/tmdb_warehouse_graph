package com.tmdbwh.common.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.tmdbwh.common.json.JsonUtils;
import com.tmdbwh.common.testing.Fixtures;
import org.junit.jupiter.api.Test;

/** 以真实 TMDB 报文（裁剪版）验证领域模型的字段映射。 */
class ModelParsingTest {

    @Test
    void movieWithAppendedSubResources() {
        Movie m = JsonUtils.fromJson(Fixtures.read("movie_27205.json"), Movie.class);

        assertThat(m.getId()).isEqualTo(27205L);
        assertThat(m.getImdbId()).isEqualTo("tt1375666");
        assertThat(m.getTitle()).isEqualTo("Inception");
        assertThat(m.getOriginalLanguage()).isEqualTo("en");
        assertThat(m.getBudget()).isEqualTo(160_000_000L);
        assertThat(m.getRevenue()).isEqualTo(839_030_630L);
        assertThat(m.getRuntime()).isEqualTo(148);
        assertThat(m.getVoteAverage()).isEqualTo(8.369);
        assertThat(m.getVoteCount()).isEqualTo(36462);
        assertThat(m.getBelongsToCollection()).isNull();
        assertThat(m.getGenres()).extracting(Genre::getName).containsExactly("Action", "Science Fiction", "Adventure");
        assertThat(m.getProductionCompanies()).extracting(ProductionCompany::getOriginCountry).containsExactly("US", "GB");
        assertThat(m.getProductionCountries()).extracting(ProductionCountry::getIso31661).containsExactly("GB", "US");
        assertThat(m.getSpokenLanguages()).extracting(SpokenLanguage::getIso6391).containsExactly("en", "ja");
        assertThat(m.getSpokenLanguages().get(1).getName()).isEqualTo("日本語");

        assertThat(m.getCredits().getCast()).hasSize(2);
        CastMember lead = m.getCredits().getCast().get(0);
        assertThat(lead.getId()).isEqualTo(6193L);
        assertThat(lead.getCharacter()).isEqualTo("Dom Cobb");
        assertThat(lead.getOrder()).isZero();
        assertThat(lead.getCreditId()).isEqualTo("52fe4534c3a368484e04de03");

        assertThat(m.getCredits().getCrew()).hasSize(2);
        assertThat(m.getCredits().getCrew()).filteredOn(CrewMember::isDirector).hasSize(1);
        assertThat(m.getCredits().getCrew().get(1).getDepartment()).isEqualTo("Writing");

        assertThat(m.getKeywords().all()).extracting(Keyword::getName).containsExactly("loss of loved one", "dream");

        assertThat(m.getReleaseDates().getResults()).hasSize(2);
        ReleaseDates.ReleaseDate us = m.getReleaseDates().getResults().get(0).getReleaseDates().get(0);
        assertThat(us.getCertification()).isEqualTo("PG-13");
        assertThat(us.getType()).isEqualTo(3);
        assertThat(us.getReleaseDate()).isEqualTo("2010-07-16T00:00:00.000Z");
        assertThat(m.toString()).contains("27205").contains("Inception");
    }

    @Test
    void derivedPropertiesAreNotSerialized() {
        Movie m = JsonUtils.fromJson(Fixtures.read("movie_27205.json"), Movie.class);
        JsonNode reserialized = JsonUtils.valueToTree(m);

        JsonNode crew = reserialized.get("credits").get("crew").get(0);
        assertThat(crew.has("director")).isFalse();
        assertThat(crew.get("job").asText()).isEqualTo("Director");
        assertThat(reserialized.get("keywords").has("all")).isFalse();
        // 序列化保持 TMDB 原始 snake_case 字段名，便于下游按 TMDB 文档理解
        assertThat(reserialized.has("vote_average")).isTrue();
        assertThat(reserialized.get("production_countries").get(0).has("iso_3166_1")).isTrue();
    }

    @Test
    void tvShowKeywordsUseResultsShape() {
        TvShow tv = JsonUtils.fromJson(Fixtures.read("tv_1399.json"), TvShow.class);

        assertThat(tv.getId()).isEqualTo(1399L);
        assertThat(tv.getName()).isEqualTo("Game of Thrones");
        assertThat(tv.getNumberOfSeasons()).isEqualTo(8);
        assertThat(tv.getInProduction()).isFalse();
        assertThat(tv.getEpisodeRunTime()).isEmpty();
        assertThat(tv.getOriginCountry()).containsExactly("US");
        assertThat(tv.getNetworks()).extracting(ProductionCompany::getName).containsExactly("HBO");
        assertThat(tv.getCreatedBy()).extracting(TvShow.Creator::getName).containsExactly("David Benioff", "D. B. Weiss");
        assertThat(tv.getKeywords().all()).extracting(Keyword::getId).containsExactly(818, 4152);
        assertThat(tv.getCredits()).isNull();
    }

    @Test
    void personParsing() {
        Person p = JsonUtils.fromJson(Fixtures.read("person_525.json"), Person.class);

        assertThat(p.getName()).isEqualTo("Christopher Nolan");
        assertThat(p.getBirthday()).isEqualTo("1970-07-30");
        assertThat(p.getDeathday()).isNull();
        assertThat(p.getAlsoKnownAs()).contains("克里斯托弗·诺兰");
        assertThat(p.getKnownForDepartment()).isEqualTo("Directing");
        assertThat(p.getPlaceOfBirth()).startsWith("Westminster");
    }

    @Test
    void pagedChangesResponse() {
        PagedResponse<ChangeItem> page = JsonUtils.fromJson(Fixtures.read("movie_changes_page1.json"),
                new TypeReference<PagedResponse<ChangeItem>>() {});

        assertThat(page.getPage()).isEqualTo(1);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.getTotalResults()).isEqualTo(7);
        assertThat(page.hasNext()).isTrue();
        assertThat(page.getResults()).extracting(ChangeItem::getId).containsExactly(27205L, 155L, 999999901L);
        assertThat(page.getResults().get(2).getAdult()).isNull();
    }

    @Test
    void pagedResponseRespectsTmdbMaxPage() {
        PagedResponse<ChangeItem> page = new PagedResponse<>();
        page.setPage(500);
        page.setTotalPages(1200);
        assertThat(page.hasNext()).isFalse();
        page.setPage(3);
        page.setTotalPages(3);
        assertThat(page.hasNext()).isFalse();
    }

    @Test
    void nullCollectionsAreNormalizedToEmpty() {
        Movie m = JsonUtils.fromJson("{\"id\":1,\"genres\":null,\"production_companies\":null}", Movie.class);
        assertThat(m.getGenres()).isEmpty();
        assertThat(m.getProductionCompanies()).isEmpty();
        assertThat(new KeywordList().all()).isEmpty();
        Credits c = new Credits();
        c.setCast(null);
        assertThat(c.getCast()).isEmpty();
    }

    @Test
    void entityTypeParsingAndSerialization() {
        assertThat(EntityType.fromValue("movie")).isEqualTo(EntityType.MOVIE);
        assertThat(EntityType.fromValue("TV")).isEqualTo(EntityType.TV);
        assertThat(EntityType.fromValue(" person ")).isEqualTo(EntityType.PERSON);
        assertThat(EntityType.TV.getExportName()).isEqualTo("tv_series");
        assertThat(EntityType.MOVIE.supportsChanges()).isTrue();
        assertThat(EntityType.KEYWORD.supportsChanges()).isFalse();
        assertThat(JsonUtils.toJson(EntityType.COMPANY)).isEqualTo("\"company\"");
        assertThat(JsonUtils.fromJson("\"tv\"", EntityType.class)).isEqualTo(EntityType.TV);
        assertThatThrownBy(() -> EntityType.fromValue("episode")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EntityType.fromValue(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void changeEventRoundTrip() {
        ChangeEvent e = new ChangeEvent(EntityType.MOVIE, 27205L, false, "2026-09-29", "2026-09-30", 1_000L);
        String json = JsonUtils.toJson(e);

        assertThat(json).contains("\"entity_type\":\"movie\"").contains("\"window_start\":\"2026-09-29\"");
        assertThat(JsonUtils.fromJson(json, ChangeEvent.class)).isEqualTo(e).hasSameHashCodeAs(e);
        assertThat(e.toString()).contains("MOVIE:27205");
    }
}
