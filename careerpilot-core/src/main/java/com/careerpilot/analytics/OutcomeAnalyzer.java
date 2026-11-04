package com.careerpilot.analytics;

import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationRepository;
import com.careerpilot.applications.StatusChangeRepository;
import com.careerpilot.jobs.JobMatch;
import com.careerpilot.jobs.JobMatchRepository;
import com.careerpilot.users.User;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** F19, deterministic part: response and interview rates by score band, source and role category, and time to response. */
@Component
@Transactional(readOnly = true)
public class OutcomeAnalyzer {
    static final Set<String> OUTCOME_STATES = Set.of("INTERVIEW", "OFFER", "ACCEPTED", "DECLINED", "REJECTED");
    static final Set<String> INTERVIEW_STATES = Set.of("INTERVIEW", "OFFER", "ACCEPTED", "DECLINED");

    private final ApplicationRepository applications;
    private final StatusChangeRepository changes;
    private final JobMatchRepository matches;

    public OutcomeAnalyzer(ApplicationRepository applications, StatusChangeRepository changes, JobMatchRepository matches) {
        this.applications = applications;
        this.changes = changes;
        this.matches = matches;
    }

    public static String roleCategory(String title) {
        String t = title == null ? "" : title.toLowerCase();
        if (t.matches(".*(data|analyst|machine learning|ml |analytics).*")) return "data";
        if (t.matches(".*(devops|sre|platform|cloud|infrastructure).*")) return "devops";
        if (t.matches(".*(front|ui |ux|web).*")) return "frontend";
        if (t.matches(".*(back|api|server|software|developer|engineer).*")) return "backend";
        return "other";
    }

    public OutcomeStats computeStats(User user) {
        Map<java.util.UUID, JobMatch> scores = new HashMap<>();
        matches.findByUser(user).forEach(m -> scores.put(m.getJob().getId(), m));
        List<Double> responseDays = new ArrayList<>();
        Map<String, int[]> band = new TreeMap<>(), source = new TreeMap<>(), role = new TreeMap<>();
        int[] total = new int[3];
        int applied = 0, withOutcome = 0;
        for (Application a : applications.findByUser(user)) {
            if (a.getAppliedAt() == null) continue;  // never submitted: not an outcome of the search strategy
            applied++;
            boolean responded = OUTCOME_STATES.contains(a.getStateName()) || changes.findByApplicationOrderByCreatedAtAsc(a).stream()
                    .anyMatch(c -> !c.isReverted() && OUTCOME_STATES.contains(c.getToState()));
            boolean interview = INTERVIEW_STATES.contains(a.getStateName()) || changes.findByApplicationOrderByCreatedAtAsc(a).stream()
                    .anyMatch(c -> !c.isReverted() && "INTERVIEW".equals(c.getToState()));
            if (OUTCOME_STATES.contains(a.getStateName())) withOutcome++;
            if (responded) {
                changes.findByApplicationOrderByCreatedAtAsc(a).stream().filter(c -> !c.isReverted() && OUTCOME_STATES.contains(c.getToState())).findFirst()
                        .ifPresent(c -> responseDays.add(Duration.between(a.getAppliedAt(), c.getCreatedAt()).toHours() / 24.0));
            }
            JobMatch m = scores.get(a.getJob().getId());
            tally(band, m == null ? "unscored" : m.scoreBand(), responded, interview);
            tally(source, a.getJob().getSourceType().name(), responded, interview);
            tally(role, roleCategory(a.getJob().getTitle()), responded, interview);
            total[0]++;
            if (responded) total[1]++;
            if (interview) total[2]++;
        }
        responseDays.sort(Double::compare);
        Double median = responseDays.isEmpty() ? null : Math.round(responseDays.get(responseDays.size() / 2) * 10) / 10.0;
        return new OutcomeStats(applied, withOutcome, new GroupStats("all", total[0], total[1], total[2]), groups(band), groups(source), groups(role), median);
    }

    private static void tally(Map<String, int[]> map, String key, boolean responded, boolean interview) {
        int[] c = map.computeIfAbsent(key, k -> new int[3]);
        c[0]++;
        if (responded) c[1]++;
        if (interview) c[2]++;
    }

    private static List<GroupStats> groups(Map<String, int[]> map) {
        List<GroupStats> out = new ArrayList<>();
        map.forEach((k, c) -> out.add(new GroupStats(k, c[0], c[1], c[2])));
        return out;
    }

}
