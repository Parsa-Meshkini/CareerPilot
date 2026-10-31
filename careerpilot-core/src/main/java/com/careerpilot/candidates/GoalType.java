package com.careerpilot.candidates;

import java.util.Map;

/** Career goal types and the scoring strategy key each one selects (F03). */
public enum GoalType {
    COOP("coop_internship"), INTERNSHIP("coop_internship"), NEW_GRAD("entry_level"), CAREER_SWITCH("career_switch");

    public final String strategyKey;

    GoalType(String strategyKey) { this.strategyKey = strategyKey; }

    public static final Map<String, StrategyWeights> DEFAULT_WEIGHTS = Map.of(
            "coop_internship", new StrategyWeights(0.45, 0.10, 0.15, 0.30),
            "entry_level", new StrategyWeights(0.50, 0.25, 0.15, 0.10),
            "career_switch", new StrategyWeights(0.55, 0.15, 0.20, 0.10));

    public static StrategyWeights defaultsFor(String strategyKey) {
        StrategyWeights d = DEFAULT_WEIGHTS.get(strategyKey);
        return new StrategyWeights(d.get("skill"), d.get("experience"), d.get("preference"), d.get("constraint"));
    }
}
