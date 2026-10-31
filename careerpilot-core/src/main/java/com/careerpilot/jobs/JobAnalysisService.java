package com.careerpilot.jobs;

import com.careerpilot.llm.LlmBudgetExceededException;
import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.LlmException;
import com.careerpilot.llm.LlmResult;
import com.careerpilot.llm.TokenBudget;
import com.careerpilot.users.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** F07: LLM analysis of a posting, with a deterministic keyword fallback when the LLM is unavailable. */
@Service
@Transactional
public class JobAnalysisService {
    private static final Map<String, String> RED_FLAGS = new LinkedHashMap<>();

    static {
        RED_FLAGS.put("\\bunpaid\\b", "Unpaid position");
        RED_FLAGS.put("\\b([5-9]|1[0-9])\\+?\\s*years\\b", "Asks for many years of experience");
        RED_FLAGS.put("commission[- ]only", "Commission-only pay");
        RED_FLAGS.put("pay (a|an)? ?(fee|deposit)", "Asks the applicant to pay");
    }

    private final LlmClient llm;
    private final JobPostingRepository jobs;
    private final JobAnalysisRepository analyses;

    public JobAnalysisService(LlmClient llm, JobPostingRepository jobs, JobAnalysisRepository analyses) {
        this.llm = llm;
        this.jobs = jobs;
        this.analyses = analyses;
    }

    public JobAnalysis analyzeJob(User user, UUID jobId, TokenBudget run) {
        JobPosting job = jobs.owned(user, jobId);
        String text = job.getTitle() + "\n" + job.getCompany() + "\n" + job.getLocation() + "\n" + job.getDescription();
        Map<String, Object> data;
        String model;
        boolean fallback;
        try {
            String desc = job.getDescription().isEmpty() ? "(no description: summary only)"
                    : job.getDescription().substring(0, Math.min(12000, job.getDescription().length()));
            LlmResult result = llm.generateStructured(user, "job.analyze", Map.of("title", job.getTitle(), "company", job.getCompany(),
                    "work_term", job.getWorkTerm().isEmpty() ? "n/a" : job.getWorkTerm(), "description", desc), run);
            data = result.asMap();
            model = result.model();
            fallback = false;
        } catch (LlmBudgetExceededException e) {
            throw e;
        } catch (LlmException e) {
            data = fallbackKeywordAnalysis(text);
            model = "keyword-fallback";
            fallback = true;
        }
        JobAnalysis a = analyses.findByJob(job).orElseGet(() -> {
            JobAnalysis fresh = new JobAnalysis();
            fresh.setUser(user);
            fresh.setJob(job);
            return fresh;
        });
        List<String> required = unique(strings(data.get("required_skills")).stream().map(Skills::canonical).toList());
        a.setRequiredSkills(required);
        a.setPreferredSkills(unique(strings(data.get("preferred_skills")).stream().map(Skills::canonical).toList())
                .stream().filter(s -> !required.contains(s)).toList());
        a.setResponsibilities(strings(data.get("responsibilities")).stream().limit(12).toList());
        a.setSeniority(String.valueOf(data.getOrDefault("seniority", "")).toLowerCase());
        a.setYearsRequired(data.get("years_required") instanceof Number n ? n.intValue() : null);
        a.setKeywords(strings(data.get("keywords")).stream().limit(20).toList());
        List<String> flags = new ArrayList<>(strings(data.get("red_flags")));
        flags.addAll(ruleRedFlags(text));
        a.setRedFlags(unique(flags));
        a.setSummary(String.valueOf(data.getOrDefault("summary", "")));
        a.setModelUsed(model);
        a.setFallback(fallback);
        a.setPartial(job.getCompleteness() != JobPosting.Completeness.FULL);
        a.setCreatedAt(java.time.Instant.now());
        return analyses.save(a);
    }

    @Transactional(readOnly = true)
    public JobAnalysis getAnalysis(JobPosting job) { return analyses.findByJob(job).orElse(null); }

    public static Map<String, Object> fallbackKeywordAnalysis(String text) {
        List<String> skills = Skills.find(text);
        String lowered = text.toLowerCase();
        String seniority = Pattern.compile("\\bsenior|lead|principal\\b").matcher(lowered).find() ? "senior"
                : Pattern.compile("\\bintern|co-?op|student\\b").matcher(lowered).find() ? "intern"
                : Pattern.compile("\\bjunior|entry|new grad|graduate\\b").matcher(lowered).find() ? "junior" : "";
        Matcher years = Pattern.compile("(\\d+)\\+?\\s*years").matcher(lowered);
        List<String> bullets = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String t = line.strip();
            if (t.startsWith("-") || t.startsWith("•") || t.startsWith("*")) bullets.add(t.replaceAll("^[-•* ]+", "").strip());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("required_skills", skills);
        out.put("preferred_skills", List.of());
        out.put("responsibilities", bullets.stream().limit(8).toList());
        out.put("seniority", seniority);
        out.put("years_required", years.find() ? Integer.valueOf(years.group(1)) : null);
        out.put("keywords", skills.stream().limit(10).toList());
        out.put("red_flags", List.of());
        out.put("summary", "");
        return out;
    }

    private List<String> ruleRedFlags(String text) {
        String lowered = text.toLowerCase();
        List<String> flags = new ArrayList<>();
        RED_FLAGS.forEach((pattern, label) -> { if (Pattern.compile(pattern).matcher(lowered).find()) flags.add(label); });
        if (Pattern.compile("\\bintern|co-?op\\b").matcher(lowered).find() && flags.contains("Asks for many years of experience"))
            return flags.stream().map(f -> f.equals("Asks for many years of experience") ? "Student role asking for 5+ years" : f).toList();
        return flags;
    }

    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Iterable<?> it) for (Object o : it) if (o != null) out.add(o.toString());
        return out;
    }

    private static List<String> unique(Iterable<String> items) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String s : items) if (s != null && !s.isEmpty()) seen.add(s);
        return new ArrayList<>(seen);
    }
}
