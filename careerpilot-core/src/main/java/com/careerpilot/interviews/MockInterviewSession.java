package com.careerpilot.interviews;

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

/** F18: one text mock interview. Turns are {n, question, answer, scores, average, feedback}; the last turn may still await its answer. */
@Entity
@Getter @Setter
public class MockInterviewSession {
    public enum Status { IN_PROGRESS, PAUSED, FINISHED, ABANDONED }
    public static final int MAX_QUESTIONS = 6;

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    @ManyToOne(optional = false)
    private Application application;
    @Enumerated(EnumType.STRING)
    private Status status = Status.IN_PROGRESS;
    @Convert(converter = JsonConverters.MapListConverter.class) @Column(columnDefinition = "CLOB")
    private List<Map<String, Object>> turns = new ArrayList<>();
    @Convert(converter = JsonConverters.MapConverter.class) @Column(columnDefinition = "CLOB")
    private Map<String, Object> summary = new LinkedHashMap<>();
    private Instant createdAt = Instant.now();

    public Map<String, Object> pendingTurn() {
        if (turns.isEmpty()) return null;
        Map<String, Object> last = turns.get(turns.size() - 1);
        return last.get("answer") == null ? last : null;
    }

    public long answeredCount() { return turns.stream().filter(t -> t.get("answer") != null).count(); }

    public boolean isFinishedByLength() { return answeredCount() >= MAX_QUESTIONS; }
}
