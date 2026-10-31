package com.careerpilot.agent;

import com.careerpilot.candidates.CandidateService;
import com.careerpilot.candidates.CareerGoal;
import com.careerpilot.candidates.FactType;
import com.careerpilot.candidates.ProfileFact;
import com.careerpilot.jobs.Skills;
import com.careerpilot.users.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Retrieval step of the agent: builds the grounded candidate context from verified facts, goal and stored preferences. */
@Component
public class ContextBuilder {
    private final CandidateService candidates;
    private final AgentMemoryRepository memory;

    public ContextBuilder(CandidateService candidates, AgentMemoryRepository memory) {
        this.candidates = candidates;
        this.memory = memory;
    }

    @Transactional(readOnly = true)
    public CandidateContext buildCandidateContext(User user, boolean requireGoal, boolean requireFacts) {
        List<ProfileFact> facts = candidates.listFacts(user, true);
        CareerGoal goal = candidates.getCareerGoal(user);
        if (requireFacts && facts.isEmpty()) throw new ProfileIncompleteException("add or verify at least one profile fact first", "facts");
        if (requireGoal && goal == null) throw new ProfileIncompleteException("set a career goal first", "goal");
        Set<String> skills = new HashSet<>();
        for (ProfileFact f : facts) {
            if (f.getFactType() == FactType.SKILL) skills.add(Skills.canonical(String.valueOf(f.getData().getOrDefault("name", ""))));
            if (f.getData().get("technologies") instanceof List<?> techs) techs.forEach(t -> skills.add(Skills.canonical(String.valueOf(t))));
            StringBuilder text = new StringBuilder();
            if (f.getData().get("bullets") instanceof List<?> bullets) bullets.forEach(b -> text.append(b).append(' '));
            text.append(' ').append(f.getData().getOrDefault("description", ""));
            skills.addAll(Skills.find(text.toString()));
        }
        skills.remove("");
        List<String> prefs = new ArrayList<>();
        memory.findTop10ByUserAndKindOrderByCreatedAtDesc(user, AgentMemoryEntry.Kind.PREFERENCE).forEach(e -> prefs.add(e.getContent()));
        return new CandidateContext(user, candidates.getProfile(user), facts, goal, goal == null ? null : goal.weights(), skills, prefs);
    }

    public CandidateContext buildCandidateContext(User user) { return buildCandidateContext(user, true, true); }
}
