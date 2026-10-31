package com.careerpilot.agent;

import com.careerpilot.candidates.CandidateProfile;
import com.careerpilot.candidates.CandidateProfileRepository;
import com.careerpilot.candidates.CareerGoalRepository;
import com.careerpilot.common.AppProperties;
import com.careerpilot.common.TaskQueue;
import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.EventListener;
import com.careerpilot.events.Events;
import com.careerpilot.users.User;
import com.careerpilot.users.UserRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F09: starts an automatic batch triage after an import. An Observer of {@link Events.JobsImported}: the importer never
 * knows the agent exists.
 */
@Component
public class TriageTrigger implements EventListener<Events.JobsImported> {
    static final int MIN_JOBS = 2;
    private final DomainEventBus bus;
    private final AppProperties props;
    private final CandidateProfileRepository profiles;
    private final CareerGoalRepository goals;
    private final UserRepository users;
    private final TaskQueue tasks;
    private final AgentController agent;

    public TriageTrigger(DomainEventBus bus, AppProperties props, CandidateProfileRepository profiles, CareerGoalRepository goals,
                         UserRepository users, TaskQueue tasks, AgentController agent) {
        this.bus = bus;
        this.props = props;
        this.profiles = profiles;
        this.goals = goals;
        this.users = users;
        this.tasks = tasks;
        this.agent = agent;
    }

    @PostConstruct
    void subscribe() { bus.subscribe(Events.JobsImported.class, this); }

    @Override
    public void handle(Events.JobsImported event) {
        if (event.jobIds().size() < MIN_JOBS || !props.getFeatures().isAutoTriage()) return;
        User user = users.findById(event.userId()).orElse(null);
        if (user == null) return;
        CandidateProfile profile = profiles.findByUser(user).orElse(null);
        if ((profile != null && !profile.isAutoTriage()) || goals.findByUser(user).isEmpty()) return;  // disabled, or nothing to score against
        tasks.enqueue("agent.triage_batch", () -> startTriage(user, event.jobIds(), event.batchId()));
    }

    @Transactional
    public AgentRun startTriage(User user, List<UUID> jobIds, UUID batchId) {
        String label = batchId != null ? "batch " + batchId : jobIds.size() + " jobs";
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("batch_job_ids", jobIds.stream().map(UUID::toString).toList());
        ctx.put("batch_id", batchId == null ? null : batchId.toString());
        return agent.runGoal(user, "Triage " + jobIds.size() + " newly imported postings (" + label + ")", "TRIAGE_BATCH", null, ctx);
    }
}
