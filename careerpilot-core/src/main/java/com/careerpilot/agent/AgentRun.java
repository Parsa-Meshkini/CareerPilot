package com.careerpilot.agent;

import com.careerpilot.common.JsonConverters;
import com.careerpilot.llm.TokenBudget;
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

/** One execution of the agent loop for a goal: plan, working memory, decisions and the pending approval or question. */
@Entity
@Getter @Setter
public class AgentRun implements TokenBudget {
    public static final String PLANNING = "PLANNING", RUNNING = "RUNNING", AWAITING_APPROVAL = "AWAITING_APPROVAL",
            AWAITING_INPUT = "AWAITING_INPUT", COMPLETED = "COMPLETED", FAILED = "FAILED", ABORTED = "ABORTED";
    public static final List<String> GOAL_TYPES = List.of("TRIAGE_BATCH", "PREPARE_APPLICATION", "PREPARE_INTERVIEW", "FREE_FORM");

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    private String goalType = "FREE_FORM";
    @Column(columnDefinition = "CLOB")
    private String goalText;
    private String status = PLANNING;
    /** [{tool, args, intent, status}] */
    @Convert(converter = JsonConverters.MapListConverter.class) @Column(columnDefinition = "CLOB")
    private List<Map<String, Object>> plan = new ArrayList<>();
    private String planSource = "";
    @Convert(converter = JsonConverters.MapListConverter.class) @Column(columnDefinition = "CLOB")
    private List<Map<String, Object>> planHistory = new ArrayList<>();
    private int currentStep;
    private int stepCount;
    private int replanCount;
    private int tokenBudget = 40000;
    private int tokensUsed;
    /** Working memory: focus job/application, batch ids, quick scores. */
    @Convert(converter = JsonConverters.MapConverter.class) @Column(columnDefinition = "CLOB")
    private Map<String, Object> context = new LinkedHashMap<>();
    @Convert(converter = JsonConverters.MapListConverter.class) @Column(columnDefinition = "CLOB")
    private List<Map<String, Object>> decisions = new ArrayList<>();
    @Convert(converter = JsonConverters.MapConverter.class) @Column(columnDefinition = "CLOB")
    private Map<String, Object> pending = new LinkedHashMap<>();
    @Column(columnDefinition = "CLOB") private String summary = "";
    @Column(columnDefinition = "CLOB") private String error = "";
    private Instant createdAt = Instant.now();
    private Instant finishedAt;
    /** Bumped on every save so in-place edits to the JSON columns are always written. */
    private Instant touchedAt = Instant.now();

    public void touch() { touchedAt = Instant.now(); }

    @Override public boolean withinBudget(int estimate) { return tokensUsed + estimate <= tokenBudget; }
    public boolean withinBudget() { return withinBudget(0); }
    @Override public void addTokens(int tokens) { tokensUsed += tokens; }

    public void recordDecision(int stepIndex, String action, String rationale, String source) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("step", stepIndex);
        d.put("action", action);
        d.put("rationale", rationale);
        d.put("source", source);
        decisions.add(d);
    }

    public void awaitApproval(int stepIndex, String description) {
        status = AWAITING_APPROVAL;
        pending = new LinkedHashMap<>(Map.of("type", "approval", "step", stepIndex, "description", description));
    }

    public void askUser(int stepIndex, String question, List<Map<String, Object>> options) {
        status = AWAITING_INPUT;
        pending = new LinkedHashMap<>(Map.of("type", "question", "step", stepIndex, "question", question, "options", options));
    }

    public void complete(String summary) {
        status = COMPLETED;
        this.summary = summary;
        pending = new LinkedHashMap<>();
        finishedAt = Instant.now();
    }

    public void fail(String reason, String newStatus) {
        status = newStatus == null ? FAILED : newStatus;
        error = reason;
        pending = new LinkedHashMap<>();
        finishedAt = Instant.now();
    }

    public boolean isActive() { return List.of(PLANNING, RUNNING, AWAITING_APPROVAL, AWAITING_INPUT).contains(status); }
}
