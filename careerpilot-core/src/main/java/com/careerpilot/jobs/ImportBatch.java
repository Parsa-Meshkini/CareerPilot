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
public class ImportBatch {
    public enum Status { RECEIVED, PROCESSING, COMPLETED, PARTIAL_FAILED, FAILED }

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    @Enumerated(EnumType.STRING)
    private SourceType sourceType;
    @Enumerated(EnumType.STRING)
    private Status status = Status.RECEIVED;
    private int totalCount;
    private int importedCount;
    private int duplicateCount;
    private int failedCount;
    @Convert(converter = JsonConverters.MapListConverter.class)
    @Column(columnDefinition = "CLOB")
    private List<Map<String, Object>> results = new ArrayList<>();
    @Convert(converter = JsonConverters.MapConverter.class)
    @Column(columnDefinition = "CLOB")
    private Map<String, Object> payload = new LinkedHashMap<>();
    private String extractorVersion = "";
    private Instant createdAt = Instant.now();
    private Instant completedAt;

    public void recordResult(int index, String outcome, UUID jobId, String reason) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("index", index);
        r.put("outcome", outcome);
        r.put("job_id", jobId == null ? null : jobId.toString());
        r.put("reason", reason == null ? "" : reason);
        results.add(r);
        switch (outcome) {
            case "IMPORTED" -> importedCount++;
            case "DUPLICATE" -> duplicateCount++;
            default -> failedCount++;
        }
    }

    public double progress() {
        int done = importedCount + duplicateCount + failedCount;
        return totalCount == 0 ? 1.0 : Math.round(1000.0 * done / totalCount) / 1000.0;
    }

    public void complete() {
        if (failedCount > 0 && failedCount == totalCount) status = Status.FAILED;
        else if (failedCount > 0) status = Status.PARTIAL_FAILED;
        else status = Status.COMPLETED;
        completedAt = Instant.now();
    }

    public List<UUID> importedJobIds() {
        return results.stream().filter(r -> "IMPORTED".equals(r.get("outcome")) && r.get("job_id") != null)
                .map(r -> UUID.fromString(r.get("job_id").toString())).toList();
    }
}
