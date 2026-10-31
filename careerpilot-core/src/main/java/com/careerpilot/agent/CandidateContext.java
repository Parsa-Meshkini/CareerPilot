package com.careerpilot.agent;

import com.careerpilot.candidates.CandidateProfile;
import com.careerpilot.candidates.CareerGoal;
import com.careerpilot.candidates.FactType;
import com.careerpilot.candidates.ProfileFact;
import com.careerpilot.candidates.StrategyWeights;
import com.careerpilot.users.User;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** The grounded view of the student used by matching, generation and the agent. Only verified facts are in it. */
public class CandidateContext {
    public final User user;
    public final CandidateProfile profile;
    public final List<ProfileFact> facts;
    public final CareerGoal goal;
    public final StrategyWeights weights;
    public final Set<String> skills;
    public final List<String> preferences;

    public CandidateContext(User user, CandidateProfile profile, List<ProfileFact> facts, CareerGoal goal, StrategyWeights weights,
                            Set<String> skills, List<String> preferences) {
        this.user = user;
        this.profile = profile;
        this.facts = facts;
        this.goal = goal;
        this.weights = weights;
        this.skills = skills;
        this.preferences = preferences;
    }

    public List<ProfileFact> factsOf(FactType type) {
        return facts.stream().filter(f -> f.getFactType() == type).toList();
    }

    public Map<String, ProfileFact> factIndex() {
        Map<String, ProfileFact> index = new LinkedHashMap<>();
        facts.forEach(f -> index.put(f.getId().toString(), f));
        return index;
    }

    public List<Map<String, Object>> factsForPrompt(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProfileFact f : facts.stream().limit(limit).toList()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId().toString());
            m.put("type", f.getFactType().name());
            m.put("text", f.toContextText());
            out.add(m);
        }
        return out;
    }

    public String summaryText() {
        String goalText = "none";
        if (goal != null) {
            goalText = goal.getGoalType() + " (" + (goal.getWorkTerm().isEmpty() ? "any term" : goal.getWorkTerm())
                    + ") targeting " + String.join(", ", goal.getTargetRoles());
        }
        String skillText = skills.isEmpty() ? "none" : new TreeSet<>(skills).stream().collect(Collectors.joining(", "));
        return "Goal: " + goalText + ". Skills: " + skillText + ". Verified facts: " + facts.size() + ".";
    }
}
