package com.careerpilot.jobs;

import com.careerpilot.common.JsonConverters;
import com.careerpilot.users.User;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Getter @Setter
public class JobAnalysis implements AnalysisView {
    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    @OneToOne(optional = false)
    private JobPosting job;
    @Convert(converter = JsonConverters.StringListConverter.class) @Column(columnDefinition = "CLOB")
    private List<String> requiredSkills = new ArrayList<>();
    @Convert(converter = JsonConverters.StringListConverter.class) @Column(columnDefinition = "CLOB")
    private List<String> preferredSkills = new ArrayList<>();
    @Convert(converter = JsonConverters.StringListConverter.class) @Column(columnDefinition = "CLOB")
    private List<String> responsibilities = new ArrayList<>();
    private String seniority = "";
    private Integer yearsRequired;
    @Convert(converter = JsonConverters.StringListConverter.class) @Column(columnDefinition = "CLOB")
    private List<String> keywords = new ArrayList<>();
    @Convert(converter = JsonConverters.StringListConverter.class) @Column(columnDefinition = "CLOB")
    private List<String> redFlags = new ArrayList<>();
    @Column(columnDefinition = "CLOB") private String summary = "";
    private String modelUsed = "";
    private boolean fallback;
    private boolean partial;
    private Instant createdAt = Instant.now();
}
