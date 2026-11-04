package com.careerpilot.interviews;

import com.careerpilot.applications.Application;
import com.careerpilot.common.JsonConverters;
import com.careerpilot.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** F17: questions, STAR stories and a checklist for one application. {@code content} holds questions, star_stories, checklist. */
@Entity
@Getter @Setter
public class InterviewPrepPlan {
    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    @ManyToOne(optional = false)
    private Application application;
    private int version = 1;
    /** LLM or FALLBACK (deterministic question bank used when the model is unavailable). */
    private String source = "LLM";
    @Convert(converter = JsonConverters.MapConverter.class) @Column(columnDefinition = "CLOB")
    private Map<String, Object> content = new LinkedHashMap<>();
    /** Notes about what the FabricationGuard removed. */
    @Column(columnDefinition = "CLOB") private String notes = "";
    private Instant createdAt = Instant.now();
}
