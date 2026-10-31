package com.careerpilot.jobs;

import com.careerpilot.agent.CandidateContext;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Co-op / internship: the work term is a hard constraint and the role must suit a student. */
public class CoopInternshipStrategy extends MatchScoringStrategy {
    @Override public String key() { return "coop_internship"; }

    /** TRUE/FALSE when both sides name a term, null when unknown (neutral). */
    Boolean checkWorkTerm(CandidateContext ctx, JobPosting job) {
        Set<String> want = termTokens(ctx.goal.getWorkTerm());
        String source = job.getWorkTerm().isEmpty() ? job.getDescription().substring(0, Math.min(1500, job.getDescription().length())) : job.getWorkTerm();
        Set<String> have = termTokens(source);
        if (want.isEmpty() || have.isEmpty()) return null;
        return want.stream().anyMatch(have::contains);
    }

    @Override
    public MatchResult score(CandidateContext ctx, JobPosting job, AnalysisView analysis) {
        Coverage cov = skillCoverage(ctx, analysis, java.util.Map.of());
        List<String> notes = new ArrayList<>();
        Boolean termOk = checkWorkTerm(ctx, job);
        double constraint = termOk == null ? 0.6 : (termOk ? 1.0 : 0.0);
        if (Boolean.FALSE.equals(termOk))
            notes.add("Work term mismatch: you want " + ctx.goal.getWorkTerm() + ", posting is " + (job.getWorkTerm().isEmpty() ? "different" : job.getWorkTerm()));
        boolean studentRole = Pattern.compile("co-?op|intern|student|summer").matcher((job.getTitle() + " " + job.getJobType()).toLowerCase()).find();
        String seniority = analysis.getSeniority() == null ? "" : analysis.getSeniority();
        double experience = studentRole || List.of("intern", "junior", "").contains(seniority) ? 1.0 : 0.3;
        if ("senior".equals(seniority)) {
            experience = 0.0;
            notes.add("Posting is a senior role");
        }
        if (!analysis.getRedFlags().isEmpty()) constraint = Math.max(0.0, constraint - 0.2);
        double pref = preferenceFit(ctx, job);
        int result = combine(ctx, factors(cov.value(), experience, pref, constraint));
        if (Boolean.FALSE.equals(termOk)) result = Math.min(result, 45);  // hard constraint caps the score
        return new MatchResult(result, key(), breakdown(cov.value(), experience, pref, constraint), cov.matched(), cov.missing(), notes);
    }

    static Set<String> termTokens(String text) {
        String t = text == null ? "" : text.toLowerCase();
        Set<String> seasons = new HashSet<>(), years = new HashSet<>(), out = new HashSet<>();
        Matcher s = Pattern.compile("\\b(winter|summer|fall|autumn|spring)\\b").matcher(t);
        while (s.find()) seasons.add(s.group(1).equals("autumn") ? "fall" : s.group(1));
        Matcher y = Pattern.compile("\\b(20\\d\\d)\\b").matcher(t);
        while (y.find()) years.add(y.group(1));
        for (String season : seasons) for (String year : years) out.add(season + "-" + year);
        return out;
    }
}
