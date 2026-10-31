package com.careerpilot.integrations;

import com.careerpilot.applications.Application;
import com.careerpilot.common.JsonConverters;
import com.careerpilot.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Getter @Setter
public class DetectedEmailEvent {
    public static final String PUBLISHED = "PUBLISHED", NEEDS_REVIEW = "NEEDS_REVIEW", CONFIRMED = "CONFIRMED", DISMISSED = "DISMISSED";

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    @ManyToOne(optional = false)
    private EmailMessage email;
    private String eventType;
    private double confidence;
    @Convert(converter = JsonConverters.MapConverter.class) @Column(columnDefinition = "CLOB")
    private Map<String, Object> extractedData = new LinkedHashMap<>();
    /** Which strategy decided: rules, llm or rules+llm. */
    private String classifier = "";
    @ManyToOne
    private Application application;
    private double matchConfidence;
    /** [{application_id, label, score}], best first. */
    @Convert(converter = JsonConverters.MapListConverter.class) @Column(columnDefinition = "CLOB")
    private List<Map<String, Object>> candidates = new ArrayList<>();
    private String reviewStatus = NEEDS_REVIEW;
    private Instant createdAt = Instant.now();

    public boolean requiresReview(double threshold) {
        return !(application != null && confidence >= threshold && matchConfidence >= threshold);
    }
}
