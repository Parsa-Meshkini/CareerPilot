package com.careerpilot.applications;

import com.careerpilot.jobs.JobPosting;
import com.careerpilot.users.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The State pattern's context: the state object is rebuilt from {@code stateName} whenever it is needed. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(name = "one_application_per_job", columnNames = {"user_id", "job_id"}))
@Getter @Setter
public class Application {
    @Id
    private UUID id = UUID.randomUUID();
    @ManyToOne(optional = false)
    private User user;
    @ManyToOne(optional = false)
    private JobPosting job;
    @Column(nullable = false)
    private String stateName = "SAVED";
    @Version
    private long version;
    private Instant appliedAt;
    @Column(columnDefinition = "CLOB")
    private String notes = "";
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    public ApplicationState state() { return ApplicationState.of(stateName); }

    public void setState(ApplicationState state) {
        this.stateName = state.name();
        if (state.name().equals("APPLIED") && appliedAt == null) appliedAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public List<String> allowedActions() { return state().allowedActions(); }

    /** Dispatches a snake_case action name to the matching state method. */
    public void perform(String action, Instant when) {
        if (!ApplicationState.ACTIONS.contains(action)) throw new IllegalArgumentException("unknown action " + action);
        ApplicationState s = state();
        switch (action) {
            case "mark_prepared" -> s.markPrepared(this);
            case "submit" -> s.submit(this);
            case "schedule_interview" -> s.scheduleInterview(this, when);
            case "receive_offer" -> s.receiveOffer(this);
            case "reject" -> s.reject(this);
            case "accept" -> s.accept(this);
            case "decline" -> s.decline(this);
            default -> s.withdraw(this);
        }
    }
}
