package com.careerpilot.llm;

import com.careerpilot.users.User;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One LLM call, recorded by UsageTracker for per-user budgets and the Stage 2 token and cost report. */
@Entity
@Table(indexes = @Index(columnList = "user_id, createdAt"))
@Getter @Setter
public class LlmUsageRecord {
    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    private String promptKey;
    private int promptVersion = 1;
    private String provider;
    private String model;
    private int inputTokens;
    private int outputTokens;
    private boolean success = true;
    private Instant createdAt = Instant.now();

    public int totalTokens() { return inputTokens + outputTokens; }
}
