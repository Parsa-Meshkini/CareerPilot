package com.careerpilot.jobs;

import com.careerpilot.common.JsonConverters;
import com.careerpilot.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Entity
@Table(indexes = {@Index(columnList = "user_id, deadline"), @Index(columnList = "fingerprint")})
@Getter @Setter
public class JobPosting {
    public enum Completeness { FULL, SUMMARY }

    @Id
    private UUID id = UUID.randomUUID();
    @ManyToOne(optional = false)
    private User user;
    @Enumerated(EnumType.STRING)
    private SourceType sourceType;
    private String externalId;
    @Column(length = 500) private String sourceUrl = "";
    @Column(length = 200) private String title = "";
    @Column(length = 200) private String company = "";
    @Column(length = 200) private String location = "";
    @Column(length = 60) private String jobType = "";
    private String workModel = "UNKNOWN";
    @Column(length = 120) private String salaryText = "";
    private LocalDate deadline;
    @Column(length = 80) private String deadlineText = "";
    @Column(length = 60) private String workTerm = "";
    @Column(columnDefinition = "CLOB") private String description = "";
    @Column(length = 500) private String applicationUrl = "";
    @Column(length = 200) private String contact = "";
    @Convert(converter = JsonConverters.StringListConverter.class)
    @Column(columnDefinition = "CLOB")
    private List<String> requiredDocuments = new ArrayList<>();
    @Enumerated(EnumType.STRING)
    private Completeness completeness = Completeness.SUMMARY;
    @Column(length = 40) private String fingerprint = "";
    @Convert(converter = JsonConverters.StringListConverter.class)
    @Column(columnDefinition = "CLOB")
    private List<String> mergedSources = new ArrayList<>();
    private boolean shortlisted;
    @Column(length = 300) private String shortlistReason = "";
    private boolean dismissed;
    @ManyToOne
    private ImportBatch batch;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public static String norm(String text) {
        String t = (text == null ? "" : text.toLowerCase()).replaceAll("[^a-z0-9 ]", " ");
        t = t.replaceAll("\\b(inc|ltd|llc|corp|corporation|co|the)\\b", " ");
        return Stream.of(t.split("\\s+")).filter(s -> !s.isEmpty()).collect(Collectors.joining(" "));
    }

    public String computeFingerprint() {
        String month = createdAt.atZone(ZoneOffset.UTC).toLocalDate().toString().substring(0, 7);
        String key = norm(company) + "|" + norm(title) + "|" + norm(location) + "|" + month;
        try {
            fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        return fingerprint;
    }

    /** Fuller and newer fields win; returns the names of the fields that changed. */
    public List<String> mergeFrom(JobPosting other) {
        List<String> changed = new ArrayList<>();
        if (fill(sourceUrl, other.sourceUrl)) { sourceUrl = other.sourceUrl; changed.add("source_url"); }
        if (fill(location, other.location)) { location = other.location; changed.add("location"); }
        if (fill(jobType, other.jobType)) { jobType = other.jobType; changed.add("job_type"); }
        if (fill(salaryText, other.salaryText)) { salaryText = other.salaryText; changed.add("salary_text"); }
        if (other.deadline != null && deadline == null) { deadline = other.deadline; changed.add("deadline"); }
        if (fill(deadlineText, other.deadlineText)) { deadlineText = other.deadlineText; changed.add("deadline_text"); }
        if (fill(workTerm, other.workTerm)) { workTerm = other.workTerm; changed.add("work_term"); }
        if (other.description != null && !other.description.isBlank()
                && (description == null || description.isBlank() || other.description.length() > description.length())) {
            description = other.description;
            changed.add("description");
        }
        if (fill(applicationUrl, other.applicationUrl)) { applicationUrl = other.applicationUrl; changed.add("application_url"); }
        if (fill(contact, other.contact)) { contact = other.contact; changed.add("contact"); }
        if (other.externalId != null && !other.externalId.isBlank() && (externalId == null || externalId.isBlank())) {
            externalId = other.externalId;
            changed.add("external_id");
        }
        if (!"UNKNOWN".equals(other.workModel) && "UNKNOWN".equals(workModel)) { workModel = other.workModel; changed.add("work_model"); }
        if (!other.requiredDocuments.isEmpty() && requiredDocuments.isEmpty()) requiredDocuments = new ArrayList<>(other.requiredDocuments);
        if (other.completeness == Completeness.FULL) completeness = Completeness.FULL;
        if (!mergedSources.contains(other.sourceType.name())) {
            java.util.TreeSet<String> union = new java.util.TreeSet<>(mergedSources);
            union.add(sourceType.name());
            union.add(other.sourceType.name());
            mergedSources = new ArrayList<>(union);
        }
        if (!changed.isEmpty()) updatedAt = Instant.now();
        return changed;
    }

    private static boolean fill(String old, String fresh) {
        return fresh != null && !fresh.isBlank() && (old == null || old.isBlank());
    }
}
