package com.tmdbwh.common.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** 演职员表（append_to_response=credits）。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Credits implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<CastMember> cast = new ArrayList<>();
    private List<CrewMember> crew = new ArrayList<>();

    public List<CastMember> getCast() {
        return cast;
    }

    public void setCast(List<CastMember> cast) {
        this.cast = cast == null ? new ArrayList<>() : cast;
    }

    public List<CrewMember> getCrew() {
        return crew;
    }

    public void setCrew(List<CrewMember> crew) {
        this.crew = crew == null ? new ArrayList<>() : crew;
    }
}
