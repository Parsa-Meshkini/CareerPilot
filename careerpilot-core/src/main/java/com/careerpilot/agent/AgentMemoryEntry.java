package com.careerpilot.agent;

import com.careerpilot.users.User;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** Long-term agent memory: what happened (episodic), what the student likes (preference), what worked (strategy). */
@Entity
@Table(indexes = @Index(columnList = "user_id, kind, createdAt"))
@Getter @Setter
public class AgentMemoryEntry {
    public enum Kind { EPISODIC, PREFERENCE, STRATEGY, FEEDBACK }

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    @Enumerated(EnumType.STRING)
    private Kind kind;
    @Column(columnDefinition = "CLOB")
    private String content;
    private UUID relatedObjectId;
    private double importance = 0.5;
    private Instant createdAt = Instant.now();
}
