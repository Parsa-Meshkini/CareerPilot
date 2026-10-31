package com.careerpilot.jobs;

import com.careerpilot.common.JsonConverters;
import com.careerpilot.users.User;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Getter @Setter
public class JobMatch {
    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    @OneToOne(optional = false)
    private JobPosting job;
    private int score;
    private String strategyKey = "";
    @Convert(converter = JsonConverters.MapConverter.class) @Column(columnDefinition = "CLOB")
    private Map<String, Object> breakdown = new LinkedHashMap<>();
    @Convert(converter = JsonConverters.StringListConverter.class) @Column(columnDefinition = "CLOB")
    private List<String> matchedSkills = new ArrayList<>();
    @Convert(converter = JsonConverters.StringListConverter.class) @Column(columnDefinition = "CLOB")
    private List<String> missingSkills = new ArrayList<>();
    @Convert(converter = JsonConverters.MapListConverter.class) @Column(columnDefinition = "CLOB")
    private List<Map<String, Object>> transferable = new ArrayList<>();
    @Convert(converter = JsonConverters.StringListConverter.class) @Column(columnDefinition = "CLOB")
    private List<String> constraintNotes = new ArrayList<>();
    @Column(columnDefinition = "CLOB") private String explanation = "";
    @Convert(converter = JsonConverters.MapListConverter.class) @Column(columnDefinition = "CLOB")
    private List<Map<String, Object>> suggestions = new ArrayList<>();
    private boolean deepAnalysis;
    private boolean outdated;
    private Instant createdAt = Instant.now();

    public String scoreBand() {
        if (score >= 80) return "80-100";
        if (score >= 60) return "60-79";
        if (score >= 40) return "40-59";
        return "0-39";
    }
}
