package com.careerpilot.jobs;

import com.careerpilot.agent.CandidateContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strategy interface of the matching algorithm family (Strategy pattern). {@link MatchingService} is the context:
 * it picks a concrete strategy from the student's career goal and delegates scoring. Each concrete strategy is a
 * different algorithm, not just different weights.
 */
public abstract class MatchScoringStrategy {
    public abstract String key();

    public abstract MatchResult score(CandidateContext ctx, JobPosting job, AnalysisView analysis);

    /** Fraction of required (and half-weighted preferred) skills the student has, plus optional partial credit. */
    protected record Coverage(double value, List<String> matched, List<String> missing) {}

    protected Coverage skillCoverage(CandidateContext ctx, AnalysisView analysis, Map<String, Double> credit) {
        List<String> required = analysis.getRequiredSkills(), preferred = analysis.getPreferredSkills();
        List<String> all = new ArrayList<>(required);
        all.addAll(preferred);
        List<String> matched = all.stream().filter(ctx.skills::contains).toList();
        List<String> missing = required.stream().filter(s -> !ctx.skills.contains(s)).toList();
        double total = required.size() + 0.5 * preferred.size();
        if (total == 0) return new Coverage(0.25, matched, missing);  // no detectable skills: fit is unknown, so it earns little
        double got = required.stream().filter(ctx.skills::contains).count() + 0.5 * preferred.stream().filter(ctx.skills::contains).count();
        for (String m : missing) got += credit.getOrDefault(m, 0.0);
        return new Coverage(Math.min(1.0, got / total), matched, missing);
    }

    protected double preferenceFit(CandidateContext ctx, JobPosting job) {
        List<Double> parts = new ArrayList<>();
        List<String> roles = ctx.goal.getTargetRoles().stream().map(String::toLowerCase).toList();
        String title = (job.getTitle() + " " + job.getJobType()).toLowerCase();
        parts.add(roles.isEmpty() || roles.stream().anyMatch(title::contains) ? 1.0 : 0.3);
        List<String> locs = ctx.goal.getPreferredLocations().stream().map(String::toLowerCase).toList();
        parts.add(locs.isEmpty() || "REMOTE".equals(job.getWorkModel()) || locs.stream().anyMatch(l -> job.getLocation().toLowerCase().contains(l)) ? 1.0 : 0.2);
        String wm = ctx.goal.getWorkModel().name();
        parts.add("ANY".equals(wm) || job.getWorkModel().equals(wm) || "UNKNOWN".equals(job.getWorkModel()) ? 1.0 : 0.4);
        Pattern dislike = Pattern.compile("not interested in (\\w+)");
        boolean disliked = false;
        for (String p : ctx.preferences) {
            Matcher m = dislike.matcher(p.toLowerCase());
            while (m.find()) if (!m.group(1).isEmpty() && title.contains(m.group(1))) disliked = true;
        }
        if (disliked) parts.add(0.0);
        return parts.stream().mapToDouble(Double::doubleValue).sum() / parts.size();
    }

    protected int combine(CandidateContext ctx, Map<String, Double> factors) {
        Map<String, Double> w = ctx.weights.normalized();
        double sum = 0;
        for (Map.Entry<String, Double> e : w.entrySet()) sum += e.getValue() * factors.get(e.getKey());
        return (int) Math.round(100 * sum);
    }

    protected static Map<String, Double> factors(double skill, double experience, double preference, double constraint) {
        Map<String, Double> f = new LinkedHashMap<>();
        f.put("skill", skill);
        f.put("experience", experience);
        f.put("preference", preference);
        f.put("constraint", constraint);
        return f;
    }

    protected static Map<String, Object> breakdown(double skill, double experience, double preference, double constraint) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("skill", Math.round(skill * 100) / 100.0);
        b.put("experience", Math.round(experience * 100) / 100.0);
        b.put("preference", Math.round(preference * 100) / 100.0);
        b.put("constraint", Math.round(constraint * 100) / 100.0);
        return b;
    }
}
