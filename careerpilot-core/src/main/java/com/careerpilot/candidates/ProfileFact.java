package com.careerpilot.candidates;

import com.careerpilot.common.JsonConverters;
import com.careerpilot.users.User;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One verifiable item about the student. Only verified facts may be used in generated documents. */
@Entity
@Table(indexes = @Index(columnList = "user_id, verified"))
@Getter @Setter
public class ProfileFact {
    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    @Enumerated(EnumType.STRING)
    private FactType factType;
    @Convert(converter = JsonConverters.MapConverter.class)
    @Column(columnDefinition = "CLOB")
    private Map<String, Object> data = new LinkedHashMap<>();
    @Enumerated(EnumType.STRING)
    private FactSource source = FactSource.MANUAL;
    private boolean verified;
    private boolean needsReview;
    private String reviewNote = "";
    private Instant createdAt = Instant.now();

    public void verify() {
        verified = true;
        needsReview = false;
        reviewNote = "";
    }

    private String str(String key) {
        Object v = data.get(key);
        return v == null ? "" : v.toString();
    }

    @SuppressWarnings("unchecked")
    private List<String> strings(String key) {
        Object v = data.get(key);
        return v instanceof List<?> l ? (List<String>) l : List.of();
    }

    private String span() {
        String start = str("start_date").isEmpty() ? "?" : str("start_date");
        String end = str("end_date").isEmpty() ? "present" : str("end_date");
        return start + " to " + end;
    }

    /** Plain-text rendering used in LLM prompts and for matching. */
    public String toContextText() {
        return switch (factType) {
            case EXPERIENCE -> (str("title") + " at " + str("organization") + " (" + span() + "). " + String.join("; ", strings("bullets"))).trim();
            case EDUCATION -> (str("credential") + " " + str("program") + " at " + str("institution") + " (" + span() + ")").trim();
            case PROJECT -> ("Project " + str("name") + ": " + str("description") + " [" + String.join(", ", strings("technologies")) + "]").trim();
            case SKILL -> "Skill: " + str("name") + (str("proficiency").isEmpty() ? "" : " (" + str("proficiency") + ")");
            case CERTIFICATION -> ("Certification: " + str("name") + " from " + str("issuer")).trim();
        };
    }

    public String label() {
        for (String key : new String[]{"title", "name", "program"}) if (!str(key).isEmpty()) return str(key);
        return factType.name().charAt(0) + factType.name().substring(1).toLowerCase();
    }
}
