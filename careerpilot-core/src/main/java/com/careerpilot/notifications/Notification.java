package com.careerpilot.notifications;

import com.careerpilot.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/** One item of the student's notification feed (bell and dashboard). */
@Entity
@Getter @Setter
public class Notification {
    public enum Kind { STATUS_CHANGE, EMAIL_EVENT, INTERVIEW, DEADLINE, REVIEW_NEEDED, PREP_READY, INFO }

    @Id
    @GeneratedValue
    @UuidGenerator
    private UUID id;
    @ManyToOne(optional = false)
    private User user;
    @Enumerated(EnumType.STRING)
    private Kind kind = Kind.INFO;
    @Column(length = 500)
    private String message;
    private UUID applicationId;
    private UUID jobId;
    /** Used to avoid repeating the same reminder, e.g. "deadline:<jobId>:2026-11-20". */
    private String dedupeKey;
    private boolean read;
    private Instant createdAt = Instant.now();
}
