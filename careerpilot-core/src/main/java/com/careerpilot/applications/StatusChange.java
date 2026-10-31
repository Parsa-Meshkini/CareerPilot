package com.careerpilot.applications;

import com.careerpilot.users.User;
import jakarta.persistence.*;
import org.hibernate.annotations.UuidGenerator;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One entry of an application's timeline. Used for the history view and for undo. */
@Entity
@Getter @Setter
public class StatusChange {
    public static final String USER = "USER", EMAIL_EVENT = "EMAIL_EVENT", AGENT = "AGENT";

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    @ManyToOne(optional = false)
    private Application application;
    private String fromState;
    private String toState;
    private String action;
    private String source = USER;
    @Column(length = 300)
    private String reason = "";
    private UUID evidenceId;
    private Instant previousAppliedAt;
    private boolean reverted;
    private Instant createdAt = Instant.now();
}
