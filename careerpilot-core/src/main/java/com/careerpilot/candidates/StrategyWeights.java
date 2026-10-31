package com.careerpilot.candidates;

import java.util.LinkedHashMap;
import java.util.Map;

/** Factor weights inside a MatchScoringStrategy. F19 tunes them within +/- {@link #MAX_STEP} per adjustment. */
public class StrategyWeights {
    public static final double MAX_STEP = 0.10;
    public static final String[] FACTORS = {"skill", "experience", "preference", "constraint"};

    private double skill, experience, preference, constraint;

    public StrategyWeights(double skill, double experience, double preference, double constraint) {
        this.skill = skill;
        this.experience = experience;
        this.preference = preference;
        this.constraint = constraint;
    }

    public double get(String factor) {
        return switch (factor) {
            case "skill" -> skill;
            case "experience" -> experience;
            case "preference" -> preference;
            case "constraint" -> constraint;
            default -> throw new IllegalArgumentException("unknown factor " + factor);
        };
    }

    public void applyAdjustment(String factor, double newValue) {
        double old = get(factor);
        if (Math.abs(newValue - old) > MAX_STEP + 1e-9)
            throw new IllegalArgumentException("adjustment of " + factor + " exceeds +/-" + MAX_STEP);
        double bounded = Math.round(Math.max(0.0, Math.min(1.0, newValue)) * 1000.0) / 1000.0;
        switch (factor) {
            case "skill" -> skill = bounded;
            case "experience" -> experience = bounded;
            case "preference" -> preference = bounded;
            default -> constraint = bounded;
        }
    }

    public Map<String, Double> normalized() {
        double total = skill + experience + preference + constraint;
        if (total == 0) total = 1.0;
        Map<String, Double> out = new LinkedHashMap<>();
        for (String f : FACTORS) out.put(f, get(f) / total);
        return out;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String f : FACTORS) out.put(f, get(f));
        return out;
    }

    public static StrategyWeights fromMap(Map<String, Object> m, StrategyWeights defaults) {
        double[] v = new double[4];
        for (int i = 0; i < 4; i++) {
            Object o = m == null ? null : m.get(FACTORS[i]);
            v[i] = o instanceof Number n ? n.doubleValue() : defaults.get(FACTORS[i]);
        }
        return new StrategyWeights(v[0], v[1], v[2], v[3]);
    }
}
