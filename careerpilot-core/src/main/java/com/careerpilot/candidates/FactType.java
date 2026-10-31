package com.careerpilot.candidates;

import java.util.List;

/**
 * The five kinds of verifiable profile facts. Stage 1 modelled them as ProfileFact subclasses; they are stored in one
 * table with a type discriminator and a typed JSON payload whose fields are declared here.
 */
public enum FactType {
    EDUCATION(List.of("institution", "program"), List.of("credential", "start_date", "end_date", "gpa")),
    EXPERIENCE(List.of("organization", "title"), List.of("start_date", "end_date", "location", "bullets")),
    PROJECT(List.of("name"), List.of("description", "technologies", "url", "bullets")),
    SKILL(List.of("name"), List.of("category", "proficiency")),
    CERTIFICATION(List.of("name"), List.of("issuer", "issued_on"));

    public final List<String> required;
    public final List<String> optional;

    FactType(List<String> required, List<String> optional) {
        this.required = required;
        this.optional = optional;
    }

    public static FactType parse(String value) {
        try {
            return valueOf(value == null ? "" : value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
