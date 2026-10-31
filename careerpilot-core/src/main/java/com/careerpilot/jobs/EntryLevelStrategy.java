package com.careerpilot.jobs;

import com.careerpilot.agent.CandidateContext;
import com.careerpilot.candidates.FactType;
import com.careerpilot.candidates.ProfileFact;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** New graduate: skill coverage plus seniority and years-of-experience fit. */
public class EntryLevelStrategy extends MatchScoringStrategy {
    @Override public String key() { return "entry_level"; }

    @Override
    public MatchResult score(CandidateContext ctx, JobPosting job, AnalysisView analysis) {
        Coverage cov = skillCoverage(ctx, analysis, java.util.Map.of());
        List<String> notes = new ArrayList<>();
        int yearsHave = yearsOfExperience(ctx);
        int yearsNeed = analysis.getYearsRequired() == null ? 0 : analysis.getYearsRequired();
        double experience = yearsNeed <= Math.max(yearsHave, 1) ? 1.0 : Math.max(0.0, 1 - (yearsNeed - yearsHave) / 5.0);
        String seniority = analysis.getSeniority() == null ? "" : analysis.getSeniority();
        if (List.of("senior", "lead", "principal").contains(seniority)) {
            experience = Math.min(experience, 0.2);
            notes.add("Posting is above entry level");
        }
        if (yearsNeed > 2) notes.add("Asks for " + yearsNeed + "+ years; you have about " + yearsHave);
        double constraint = 1.0 - Math.min(1.0, 0.35 * analysis.getRedFlags().size());
        double pref = preferenceFit(ctx, job);
        return new MatchResult(combine(ctx, factors(cov.value(), experience, pref, constraint)), key(),
                breakdown(cov.value(), experience, pref, constraint), cov.matched(), cov.missing(), notes);
    }

    static int yearsOfExperience(CandidateContext ctx) {
        int months = 0;
        for (ProfileFact f : ctx.factsOf(FactType.EXPERIENCE)) {
            String start = String.valueOf(f.getData().getOrDefault("start_date", ""));
            String end = String.valueOf(f.getData().getOrDefault("end_date", ""));
            try {
                int sy = Integer.parseInt(start.substring(0, 4));
                int sm = start.length() >= 7 ? Integer.parseInt(start.substring(5, 7)) : 1;
                int ey, em;
                if (!end.isEmpty()) {
                    ey = Integer.parseInt(end.substring(0, 4));
                    em = end.length() >= 7 ? Integer.parseInt(end.substring(5, 7)) : 12;
                } else {
                    ey = LocalDate.now().getYear();
                    em = LocalDate.now().getMonthValue();
                }
                months += Math.max(0, (ey - sy) * 12 + em - sm);
            } catch (RuntimeException e) {
                // unparseable dates contribute nothing
            }
        }
        return (int) Math.round(months / 12.0);
    }
}
