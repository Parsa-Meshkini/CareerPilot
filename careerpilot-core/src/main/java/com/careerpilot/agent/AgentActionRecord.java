package com.careerpilot.agent;

import com.careerpilot.common.JsonConverters;
import com.careerpilot.users.User;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Audit log of one tool call made by the agent. Also what makes a reversible action undoable. */
@Entity
@Getter @Setter
public class AgentActionRecord {
    public static final String EXECUTED = "EXECUTED", FAILED = "FAILED", REJECTED = "REJECTED", UNDONE = "UNDONE";

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    @ManyToOne
    private AgentRun run;
    private int stepIndex;
    private String commandName;
    @Convert(converter = JsonConverters.MapConverter.class) @Column(columnDefinition = "CLOB")
    private Map<String, Object> arguments = new LinkedHashMap<>();
    @Column(columnDefinition = "CLOB") private String description = "";
    private String status = EXECUTED;
    @Column(columnDefinition = "CLOB") private String observationSummary = "";
    @Convert(converter = JsonConverters.MapConverter.class) @Column(columnDefinition = "CLOB")
    private Map<String, Object> observation = new LinkedHashMap<>();
    private boolean reversible;
    @Convert(converter = JsonConverters.MapConverter.class) @Column(columnDefinition = "CLOB")
    private Map<String, Object> undoData = new LinkedHashMap<>();
    private Instant executedAt = Instant.now();
    private Instant undoneAt;

    public void markUndone() {
        status = UNDONE;
        undoneAt = Instant.now();
    }
}
