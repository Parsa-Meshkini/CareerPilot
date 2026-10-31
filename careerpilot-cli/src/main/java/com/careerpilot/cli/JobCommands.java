package com.careerpilot.cli;

import com.careerpilot.common.Json;
import com.careerpilot.jobs.ImportBatch;
import com.careerpilot.jobs.JobAnalysis;
import com.careerpilot.jobs.JobAnalysisService;
import com.careerpilot.jobs.JobImportService;
import com.careerpilot.jobs.JobMatch;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.jobs.MatchingService;
import com.careerpilot.users.User;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/** F05/F06 import and listing, F07 analysis, F08 matching. */
public final class JobCommands {
    private JobCommands() {}

    static JobImportService imports() { return CliRuntime.bean(JobImportService.class); }

    static UUID jobId(User u, String text) { return Ids.resolve(text, imports().listJobs(u, null, null, null, true), JobPosting::getId); }

    @Command(name = "jobs", description = "Import, list, analyze and score job postings (F05-F08).",
            subcommands = {ImportFile.class, ImportPaste.class, ImportYork.class, ListJobs.class, ShowJob.class, Analyze.class, Match.class, Score.class, Dismiss.class})
    public static class Jobs implements Callable<Integer> {
        @Override public Integer call() { new picocli.CommandLine(this).usage(Out.out); return 0; }
    }

    private static void printBatch(ImportBatch b) {
        if (Out.json) {
            Out.json(Map.of("status", b.getStatus().name(), "imported", b.getImportedCount(), "duplicates", b.getDuplicateCount(),
                    "failed", b.getFailedCount(), "results", b.getResults()));
            return;
        }
        Out.line("Batch %s: %d imported, %d duplicate, %d failed.", b.getStatus(), b.getImportedCount(), b.getDuplicateCount(), b.getFailedCount());
        b.getResults().stream().filter(r -> "FAILED".equals(r.get("outcome"))).forEach(r -> Out.line("  record %s skipped: %s", r.get("index"), r.get("reason")));
    }

    @Command(name = "import-file", description = "Import postings from a CSV or JSON file (title, company, location, deadline, description...).")
    static class ImportFile implements Callable<Integer> {
        @Parameters(index = "0") Path file;

        @Override public Integer call() throws IOException {
            String text = Files.readString(file);
            Map<String, Object> payload = new LinkedHashMap<>();
            if (file.toString().toLowerCase().endsWith(".json")) {
                Object parsed = Json.MAPPER.readValue(text, Object.class);
                payload.put("records", parsed instanceof Map<?, ?> m && m.get("records") != null ? m.get("records") : parsed);
            } else payload.put("csv", text);
            printBatch(imports().createBatch(CliRuntime.currentUser(), "file", payload));
            return 0;
        }
    }

    @Command(name = "import-york", description = "Import a payload captured by the browser extension (JSON file with a records list).")
    static class ImportYork implements Callable<Integer> {
        @Parameters(index = "0") Path file;

        @Override public Integer call() throws IOException {
            printBatch(imports().createBatch(CliRuntime.currentUser(), "york", Json.map(Files.readString(file))));
            return 0;
        }
    }

    @Command(name = "import-paste", description = "Import one posting from pasted text (--file or standard input); the AI structures it.")
    static class ImportPaste implements Callable<Integer> {
        @Option(names = "--file") Path file;
        @Option(names = "--url", defaultValue = "") String url;
        @Option(names = "--title", defaultValue = "") String title;
        @Option(names = "--company", defaultValue = "") String company;

        @Override public Integer call() throws IOException {
            String text = file != null ? Files.readString(file) : new String(System.in.readAllBytes());
            Map<String, Object> payload = new LinkedHashMap<>(Map.of("text", text, "url", url));
            if (!title.isEmpty()) payload.put("title", title);
            if (!company.isEmpty()) payload.put("company", company);
            JobImportService.ImportOutcome o = imports().importManual(CliRuntime.currentUser(), payload);
            Out.line("%s: %s at %s (%s)", o.duplicate() ? "Already saved" : "Imported", o.job().getTitle(), o.job().getCompany(), Out.shortId(o.job().getId()));
            o.warnings().forEach(w -> Out.line("  note: %s", w));
            return 0;
        }
    }

    @Command(name = "list", description = "List saved postings, best match first.")
    static class ListJobs implements Callable<Integer> {
        @Option(names = "--query") String query;
        @Option(names = "--before", description = "deadline on or before YYYY-MM-DD") LocalDate before;
        @Option(names = "--shortlisted") boolean shortlisted;
        @Option(names = "--all", description = "include dismissed") boolean all;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            MatchingService matching = CliRuntime.bean(MatchingService.class);
            List<JobPosting> jobs = new ArrayList<>(imports().listJobs(u, query, before, shortlisted ? Boolean.TRUE : null, all));
            Map<UUID, Integer> scores = new LinkedHashMap<>();
            jobs.forEach(j -> { JobMatch m = matching.getMatch(j); if (m != null) scores.put(j.getId(), m.getScore()); });
            jobs.sort((a, b) -> Integer.compare(scores.getOrDefault(b.getId(), -1), scores.getOrDefault(a.getId(), -1)));
            List<List<String>> rows = new ArrayList<>();
            List<Map<String, Object>> js = new ArrayList<>();
            for (JobPosting j : jobs) {
                String score = scores.containsKey(j.getId()) ? String.valueOf(scores.get(j.getId())) : "-";
                rows.add(List.of(Out.shortId(j.getId()), score, j.isShortlisted() ? "*" : "", j.getTitle(), j.getCompany(),
                        j.getDeadline() == null ? "" : j.getDeadline().toString(), j.getCompleteness().name().toLowerCase()));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", j.getId().toString());
                m.put("score", scores.get(j.getId()));
                m.put("title", j.getTitle());
                m.put("company", j.getCompany());
                m.put("shortlisted", j.isShortlisted());
                js.add(m);
            }
            Out.table(List.of("ID", "SCORE", "SL", "TITLE", "COMPANY", "DEADLINE", "DETAIL"), rows, js);
            return 0;
        }
    }

    @Command(name = "show", description = "Show a posting with its analysis and match.")
    static class ShowJob implements Callable<Integer> {
        @Parameters(index = "0") String id;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            JobPosting j = imports().getJob(u, jobId(u, id));
            JobAnalysis a = CliRuntime.bean(JobAnalysisService.class).getAnalysis(j);
            JobMatch m = CliRuntime.bean(MatchingService.class).getMatch(j);
            Out.line("%s at %s", j.getTitle(), j.getCompany());
            Out.line("Location: %s (%s)   Term: %s   Deadline: %s", j.getLocation(), j.getWorkModel(), j.getWorkTerm(), j.getDeadline());
            if (!j.getSourceUrl().isEmpty()) Out.line("Posting link: %s", j.getSourceUrl());
            if (!j.getApplicationUrl().isEmpty()) Out.line("Apply at:      %s", j.getApplicationUrl());
            Out.line("Sources: %s   Detail: %s", j.getMergedSources().isEmpty() ? j.getSourceType() : j.getMergedSources(), j.getCompleteness());
            if (a != null) {
                Out.line("Requires: %s", String.join(", ", a.getRequiredSkills()));
                Out.line("Nice to have: %s", String.join(", ", a.getPreferredSkills()));
                if (!a.getRedFlags().isEmpty()) Out.line("Red flags: %s", String.join("; ", a.getRedFlags()));
                Out.line("Analysis by %s%s", a.getModelUsed(), a.isPartial() ? " (summary only: import the full posting for a better result)" : "");
            }
            if (m != null) {
                Out.line("Match: %d/100 (%s)%s", m.getScore(), m.getStrategyKey(), m.isOutdated() ? " - outdated, goal changed" : "");
                Out.line("  matched: %s", String.join(", ", m.getMatchedSkills()));
                Out.line("  missing: %s", String.join(", ", m.getMissingSkills()));
                m.getConstraintNotes().forEach(n -> Out.line("  note: %s", n));
                if (!m.getExplanation().isEmpty()) Out.line("  %s", m.getExplanation());
                m.getSuggestions().forEach(s -> Out.line("  learn %s: %s", s.get("skill"), s.get("suggestion")));
            }
            return 0;
        }
    }

    @Command(name = "analyze", description = "Run AI analysis and a deep match for a posting.")
    static class Analyze implements Callable<Integer> {
        @Parameters(index = "0") String id;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            JobMatch m = CliRuntime.bean(MatchingService.class).matchJob(u, jobId(u, id), true);
            Out.line("Score %d/100 (%s). Missing: %s", m.getScore(), m.getStrategyKey(), m.getMissingSkills().isEmpty() ? "nothing" : String.join(", ", m.getMissingSkills()));
            if (!m.getExplanation().isEmpty()) Out.line(m.getExplanation());
            return 0;
        }
    }

    @Command(name = "match", description = "Quick keyword score for a posting (no AI tokens).")
    static class Match implements Callable<Integer> {
        @Parameters(index = "0") String id;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            JobMatch m = CliRuntime.bean(MatchingService.class).matchJob(u, jobId(u, id), false);
            Out.line("Quick score %d/100 (%s)", m.getScore(), m.getStrategyKey());
            return 0;
        }
    }

    @Command(name = "score", description = "Quick-score every saved posting.")
    static class Score implements Callable<Integer> {
        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            List<UUID> ids = imports().listJobs(u, null, null, null, false).stream().map(JobPosting::getId).toList();
            List<JobMatch> ranked = CliRuntime.bean(MatchingService.class).rankJobs(u, ids, java.util.Set.of());
            Out.line("Scored %d posting(s).", ranked.size());
            return 0;
        }
    }

    @Command(name = "dismiss", description = "Hide a posting from the list.")
    static class Dismiss implements Callable<Integer> {
        @Parameters(index = "0") String id;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            imports().dismiss(u, jobId(u, id), true);
            Out.line("Dismissed.");
            return 0;
        }
    }
}
