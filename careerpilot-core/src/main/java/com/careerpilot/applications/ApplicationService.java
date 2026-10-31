package com.careerpilot.applications;

import com.careerpilot.common.ConflictException;
import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.Events;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.jobs.JobPostingRepository;
import com.careerpilot.users.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** F13: create applications, move them through the State objects, keep the timeline, undo, interviews. */
@Service
@Transactional
public class ApplicationService {
    private final ApplicationRepository applications;
    private final StatusChangeRepository changes;
    private final InterviewRepository interviews;
    private final JobPostingRepository jobs;
    private final DomainEventBus bus;

    public ApplicationService(ApplicationRepository applications, StatusChangeRepository changes, InterviewRepository interviews,
                              JobPostingRepository jobs, DomainEventBus bus) {
        this.applications = applications;
        this.changes = changes;
        this.interviews = interviews;
        this.jobs = jobs;
        this.bus = bus;
    }

    public Application createApplication(User user, UUID jobId, String notes) {
        JobPosting job = jobs.owned(user, jobId);
        var existing = applications.findByUserAndJob(user, job);
        if (existing.isPresent())
            throw new ConflictException("an application for this job already exists").with("application_id", existing.get().getId().toString());
        Application app = new Application();
        app.setUser(user);
        app.setJob(job);
        app.setNotes(notes == null ? "" : notes);
        return applications.save(app);
    }

    public Application getOrCreateForJob(User user, UUID jobId) {
        JobPosting job = jobs.owned(user, jobId);
        return applications.findByUserAndJob(user, job).orElseGet(() -> createApplication(user, jobId, ""));
    }

    @Transactional(readOnly = true)
    public List<Application> listApplications(User user, String state) {
        List<Application> all = state == null || state.isBlank() ? applications.findByUser(user) : applications.findByUserAndStateName(user, state.toUpperCase());
        return all.stream().sorted((a, b) -> b.getUpdatedAt().compareTo(a.getUpdatedAt())).toList();
    }

    @Transactional(readOnly = true)
    public Application get(User user, UUID id) { return applications.owned(user, id); }

    @Transactional(readOnly = true)
    public List<StatusChange> timeline(User user, UUID applicationId) {
        return changes.findByApplicationOrderByCreatedAtAsc(applications.owned(user, applicationId));
    }

    @Transactional(readOnly = true)
    public List<Interview> interviews(User user, UUID applicationId) {
        return interviews.findByApplicationOrderByRoundAsc(applications.owned(user, applicationId));
    }

    public StatusChange transition(User user, UUID applicationId, String action, String source, UUID evidenceId, String reason,
                                   Long expectedVersion, Instant when) {
        if (!ApplicationState.ACTIONS.contains(action)) throw new InvalidTransitionException("?", action, ApplicationState.ACTIONS);
        Application app = applications.owned(user, applicationId);
        if (expectedVersion != null && expectedVersion != app.getVersion())
            throw new ConflictException("the application was changed elsewhere; reload and try again").with("current_version", app.getVersion());
        String before = app.getStateName();
        Instant appliedAt = app.getAppliedAt();
        app.perform(action, when);  // throws InvalidTransitionException (409) when illegal
        applications.save(app);
        StatusChange change = new StatusChange();
        change.setUser(user);
        change.setApplication(app);
        change.setFromState(before);
        change.setToState(app.getStateName());
        change.setAction(action);
        change.setSource(source);
        change.setReason(reason == null ? "" : reason.substring(0, Math.min(300, reason.length())));
        change.setEvidenceId(evidenceId);
        change.setPreviousAppliedAt(appliedAt);
        changes.save(change);
        if (!before.equals(app.getStateName())) publish(user, app, change);
        return change;
    }

    public StatusChange transition(User user, UUID applicationId, String action) {
        return transition(user, applicationId, action, StatusChange.USER, null, "", null, null);
    }

    /** Reverts the most recent live change only; the revert itself is recorded in the timeline. */
    public Application undoTransition(User user, UUID changeId) {
        StatusChange change = changes.owned(user, changeId);
        Application app = change.getApplication();
        StatusChange latest = changes.findFirstByApplicationOrderByCreatedAtDesc(app).orElse(null);
        if (change.isReverted() || latest == null || !latest.getId().equals(change.getId()))
            throw new ConflictException("only the most recent change can be undone");
        app.setStateName(change.getFromState());
        app.setAppliedAt(change.getPreviousAppliedAt());
        app.setUpdatedAt(Instant.now());
        applications.save(app);
        change.setReverted(true);
        changes.save(change);
        StatusChange undo = new StatusChange();
        undo.setUser(user);
        undo.setApplication(app);
        undo.setFromState(change.getToState());
        undo.setToState(change.getFromState());
        undo.setAction("undo");
        undo.setSource(StatusChange.USER);
        undo.setReason("undo of " + change.getAction());
        undo.setReverted(true);
        changes.save(undo);
        if (!change.getFromState().equals(change.getToState())) publish(user, app, undo);
        return app;
    }

    public Interview addInterview(User user, UUID applicationId, Map<String, Object> details) {
        Application app = applications.owned(user, applicationId);
        UUID eventId = details.get("source_event_id") == null ? null : UUID.fromString(details.get("source_event_id").toString());
        if (eventId != null) {
            var existing = interviews.findBySourceEventId(eventId);  // idempotent per detected email event
            if (existing.isPresent()) return existing.get();
        }
        Interview interview = new Interview();
        interview.setUser(user);
        interview.setApplication(app);
        interview.setRound(interviews.findByApplicationOrderByRoundAsc(app).size() + 1);
        interview.setScheduledAt(details.get("scheduled_at") instanceof Instant i ? i : null);
        String format = String.valueOf(details.getOrDefault("format", ""));
        interview.setFormat(format.substring(0, Math.min(60, format.length())));
        interview.setDetailsText(String.valueOf(details.getOrDefault("details_text", "")));
        interview.setSourceEventId(eventId);
        return interviews.save(interview);
    }

    public Application updateNotes(User user, UUID applicationId, String notes) {
        Application app = applications.owned(user, applicationId);
        app.setNotes(notes);
        return applications.save(app);
    }

    private void publish(User user, Application app, StatusChange change) {
        bus.publish(new Events.ApplicationStatusChanged(user.getId(), app.getId(), change.getId(), change.getFromState(),
                change.getToState(), change.getSource()));
    }
}
