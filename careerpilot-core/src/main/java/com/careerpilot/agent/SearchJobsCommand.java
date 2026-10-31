package com.careerpilot.agent;

import com.careerpilot.jobs.JobPosting;
import com.careerpilot.users.User;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SearchJobsCommand extends AgentCommand {
    static final CommandType TYPE = CommandType.of("search_jobs",
            "Find the student's saved job postings whose title/company/location match a query.",
            "{\"type\":\"object\",\"required\":[\"query\"],\"properties\":{\"query\":{\"type\":\"string\",\"minLength\":1}}}",
            false, false, 0, SearchJobsCommand::new);
    private static final Set<String> STOP = Set.of("the", "a", "an", "my", "for", "to", "of", "at", "in", "and", "job", "jobs", "posting",
            "application", "prepare", "apply", "help", "me", "get", "ready", "please", "role", "position");

    public SearchJobsCommand(AgentServices svc, User user, Map<String, Object> args, AgentRun run) { super(svc, user, args, run); }

    @Override public CommandType type() { return TYPE; }

    @Override
    public Observation execute() {
        String query = String.valueOf(args.get("query")).toLowerCase();
        List<String> words = new ArrayList<>();
        Matcher m = Pattern.compile("[a-z0-9+#.-]+").matcher(query);
        while (m.find()) if (!STOP.contains(m.group()) && m.group().length() > 1) words.add(m.group());
        List<JobPosting> base = svc.imports.listJobs(user, null, null, null, false);
        Map<UUID, int[]> hits = new LinkedHashMap<>();
        Map<UUID, JobPosting> byId = new LinkedHashMap<>();
        for (String word : words) {  // rank postings by how many query words they match
            for (JobPosting j : base) {
                if ((j.getTitle() + " " + j.getCompany() + " " + j.getLocation()).toLowerCase().contains(word)) {
                    hits.computeIfAbsent(j.getId(), k -> new int[1])[0]++;
                    byId.put(j.getId(), j);
                }
            }
        }
        int best = hits.values().stream().mapToInt(a -> a[0]).max().orElse(0);
        List<Map<String, Object>> found = new ArrayList<>();
        List<JobPosting> jobs = new ArrayList<>();
        for (Map.Entry<UUID, int[]> e : hits.entrySet()) {
            if (e.getValue()[0] == best && jobs.size() < 10) {
                JobPosting j = byId.get(e.getKey());
                jobs.add(j);
                found.add(map("id", j.getId().toString(), "label", j.getTitle() + " @ " + j.getCompany()));
            }
        }
        if (jobs.size() == 1) {
            setFocus(jobs.get(0).getId(), null);
            return Observation.ok("1 posting found (" + found.get(0).get("label") + ")", map("jobs", found));
        }
        if (jobs.isEmpty()) return Observation.ok("no saved postings match '" + args.get("query") + "'", map("jobs", List.of()));
        return Observation.choices(jobs.size() + " postings match equally well", map("jobs", found), found);
    }
}
