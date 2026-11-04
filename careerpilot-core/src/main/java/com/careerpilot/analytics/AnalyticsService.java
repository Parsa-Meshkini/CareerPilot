package com.careerpilot.analytics;

import com.careerpilot.agent.AgentMemoryEntry;
import com.careerpilot.agent.MemoryManager;
import com.careerpilot.candidates.CandidateService;
import com.careerpilot.candidates.CareerGoal;
import com.careerpilot.candidates.StrategyWeights;
import com.careerpilot.common.ConflictException;
import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.Events;
import com.careerpilot.users.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** F19: outcome statistics, bounded evidence-cited proposals, and the accept / reject / revert workflow that tunes StrategyWeights. */
@Service
@Transactional
public class AnalyticsService {
    static final int MIN_OUTCOMES_FOR_ADVICE = 10;

    public record InsightReport(OutcomeStats stats, String narrative, boolean enoughData, List<StrategyAdjustment> proposals) {}

    private final OutcomeAnalyzer analyzer;
    private final StrategyAdvisor advisor;
    private final StrategyAdjustmentRepository adjustments;
    private final CandidateService candidates;
    private final MemoryManager memory;
    private final DomainEventBus bus;

    public AnalyticsService(OutcomeAnalyzer analyzer, StrategyAdvisor advisor, StrategyAdjustmentRepository adjustments, CandidateService candidates,
                            MemoryManager memory, DomainEventBus bus) {
        this.analyzer = analyzer;
        this.advisor = advisor;
        this.adjustments = adjustments;
        this.candidates = candidates;
        this.memory = memory;
        this.bus = bus;
    }

    public InsightReport getInsights(User user) {
        OutcomeStats stats = analyzer.computeStats(user);
        adjustments.findByUserAndStatus(user, StrategyAdjustment.Status.PROPOSED).forEach(p -> {
            p.setStatus(StrategyAdjustment.Status.SUPERSEDED);
            adjustments.save(p);
        });
        CareerGoal goal = candidates.getCareerGoal(user);
        boolean enough = stats.withOutcome() >= MIN_OUTCOMES_FOR_ADVICE && goal != null;
        List<StrategyAdvisor.Proposal> proposed = enough ? advisor.proposeAdjustments(stats, goal.weights()) : List.of();
        StrategyAdvisor.Words words = advisor.describe(user, stats, proposed);
        List<StrategyAdjustment> saved = new ArrayList<>();
        for (int i = 0; i < proposed.size(); i++) {
            StrategyAdvisor.Proposal p = proposed.get(i);
            StrategyAdjustment a = new StrategyAdjustment();
            a.setUser(user);
            a.setFactor(p.factor());
            a.setOldValue(p.oldValue());
            a.setNewValue(p.newValue());
            String text = words.explanations().get(i);
            a.setRationale(text.length() > 600 ? text.substring(0, 600) : text);
            a.setEvidence(p.evidence());
            saved.add(adjustments.save(a));
        }
        String narrative = enough ? words.narrative() : words.narrative() + " Not enough data to recommend changes yet: at least " + MIN_OUTCOMES_FOR_ADVICE
                + " applications with an outcome are needed (you have " + stats.withOutcome() + ").";
        return new InsightReport(stats, narrative, enough, saved);
    }

    @Transactional(readOnly = true)
    public List<StrategyAdjustment> history(User user) { return adjustments.findByUserOrderByCreatedAtDesc(user); }

    public StrategyAdjustment accept(User user, UUID id) {
        StrategyAdjustment a = adjustments.owned(user, id);
        if (a.getStatus() != StrategyAdjustment.Status.PROPOSED) throw new ConflictException("this proposal is already " + a.getStatus().name().toLowerCase());
        CareerGoal goal = candidates.getCareerGoal(user);
        if (goal == null) throw new ConflictException("set a career goal first");
        StrategyWeights w = goal.weights();
        w.applyAdjustment(a.getFactor(), a.getNewValue());  // throws when outside +/-0.10 of the current value
        goal.setWeights(w);
        a.setStatus(StrategyAdjustment.Status.ACCEPTED);
        a.setDecidedAt(Instant.now());
        persistGoal(goal);
        memory.remember(user, AgentMemoryEntry.Kind.STRATEGY, "Accepted: " + a.getFactor() + " weight " + a.getOldValue() + " to " + a.getNewValue() + ". " + a.getRationale(), a.getId(), 0.8);
        bus.publish(new Events.CareerGoalChanged(user.getId(), false));  // old match scores are now outdated
        return adjustments.save(a);
    }

    public StrategyAdjustment reject(User user, UUID id) {
        StrategyAdjustment a = adjustments.owned(user, id);
        if (a.getStatus() != StrategyAdjustment.Status.PROPOSED) throw new ConflictException("this proposal is already " + a.getStatus().name().toLowerCase());
        a.setStatus(StrategyAdjustment.Status.REJECTED);
        a.setDecidedAt(Instant.now());
        memory.remember(user, AgentMemoryEntry.Kind.PREFERENCE, "Rejected a suggestion to change the " + a.getFactor() + " weight.", a.getId(), 0.5);
        return adjustments.save(a);
    }

    public StrategyAdjustment revert(User user, UUID id) {
        StrategyAdjustment a = adjustments.owned(user, id);
        if (a.getStatus() != StrategyAdjustment.Status.ACCEPTED) throw new ConflictException("only an accepted adjustment can be reverted");
        CareerGoal goal = candidates.getCareerGoal(user);
        StrategyWeights w = goal.weights();
        w.applyAdjustment(a.getFactor(), a.getOldValue());
        goal.setWeights(w);
        persistGoal(goal);
        a.setStatus(StrategyAdjustment.Status.REVERTED);
        a.setDecidedAt(Instant.now());
        bus.publish(new Events.CareerGoalChanged(user.getId(), false));
        return adjustments.save(a);
    }

    private void persistGoal(CareerGoal goal) {
        candidates.saveGoalWeights(goal);
    }
}
