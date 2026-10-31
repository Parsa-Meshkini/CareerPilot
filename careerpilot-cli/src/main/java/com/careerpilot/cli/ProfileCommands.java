package com.careerpilot.cli;

import com.careerpilot.candidates.CandidateProfile;
import com.careerpilot.candidates.CandidateService;
import com.careerpilot.candidates.CareerGoal;
import com.careerpilot.candidates.FactType;
import com.careerpilot.candidates.ProfileFact;
import com.careerpilot.candidates.UploadedResume;
import com.careerpilot.common.Json;
import com.careerpilot.common.ValidationException;
import com.careerpilot.users.User;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/** F01 profile and facts, F02 resume import, F03 career goal. */
public final class ProfileCommands {
    private ProfileCommands() {}

    static CandidateService svc() { return CliRuntime.bean(CandidateService.class); }

    @Command(name = "profile", description = "Your profile, verified facts, resume import and career goal (F01-F03).",
            subcommands = {Show.class, Set.class, FactAdd.class, FactList.class, FactVerify.class, FactReject.class, FactDelete.class,
                    ResumeImport.class, GoalShow.class, GoalSet.class})
    public static class Profile implements Callable<Integer> {
        @Override public Integer call() { new picocli.CommandLine(this).usage(Out.out); return 0; }
    }

    @Command(name = "show", description = "Show the profile and what is missing from it.")
    static class Show implements Callable<Integer> {
        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            CandidateProfile p = svc().getProfile(u);
            if (Out.json) {
                Out.json(Map.of("full_name", p.getFullName(), "headline", p.getHeadline(), "location", p.getLocation(),
                        "hints", svc().completenessHints(u)));
                return 0;
            }
            Out.line("Name:      %s", p.getFullName());
            Out.line("Headline:  %s", p.getHeadline());
            Out.line("Location:  %s", p.getLocation());
            Out.line("Email:     %s   Phone: %s", p.getEmail(), p.getPhone());
            Out.line("Auto-triage after imports: %s", p.isAutoTriage() ? "on" : "off");
            svc().completenessHints(u).forEach(h -> Out.line("  * %s", h));
            return 0;
        }
    }

    @Command(name = "set", description = "Update profile fields.")
    static class Set implements Callable<Integer> {
        @Option(names = "--name") String name;
        @Option(names = "--headline") String headline;
        @Option(names = "--summary") String summary;
        @Option(names = "--location") String location;
        @Option(names = "--email") String email;
        @Option(names = "--phone") String phone;
        @Option(names = "--links", description = "comma separated") String links;
        @Option(names = "--auto-triage", description = "true|false") Boolean autoTriage;

        @Override public Integer call() {
            Map<String, Object> data = new LinkedHashMap<>();
            if (name != null) data.put("full_name", name);
            if (headline != null) data.put("headline", headline);
            if (summary != null) data.put("summary", summary);
            if (location != null) data.put("location", location);
            if (email != null) data.put("email", email);
            if (phone != null) data.put("phone", phone);
            if (links != null) data.put("links", links);
            if (autoTriage != null) data.put("auto_triage", autoTriage);
            svc().updateProfile(CliRuntime.currentUser(), data);
            Out.line("Profile updated.");
            return 0;
        }
    }

    @Command(name = "fact-add", description = "Add a verified fact. Fields as key=value, e.g. title=Intern organization=Acme start_date=2025-05.")
    static class FactAdd implements Callable<Integer> {
        @Parameters(index = "0", description = "EDUCATION, EXPERIENCE, PROJECT, SKILL or CERTIFICATION") String type;
        @Parameters(index = "1..*", arity = "1..*", description = "key=value (use ; between several bullets)") List<String> fields;

        @Override public Integer call() {
            FactType t = FactType.parse(type);
            if (t == null) throw new ValidationException("unknown fact type '" + type + "'");
            Map<String, Object> data = new LinkedHashMap<>();
            for (String f : fields) {
                int eq = f.indexOf('=');
                if (eq < 1) throw new ValidationException("write fields as key=value, got '" + f + "'");
                String key = f.substring(0, eq), value = f.substring(eq + 1);
                data.put(key, key.equals("bullets") ? List.of(value.split(";")) : value);
            }
            ProfileFact fact = svc().addFact(CliRuntime.currentUser(), t, data);
            Out.line("Added %s %s (%s).", t, fact.label(), Out.shortId(fact.getId()));
            return 0;
        }
    }

    @Command(name = "fact-list", description = "List facts. Use --pending to show the ones waiting for review.")
    static class FactList implements Callable<Integer> {
        @Option(names = "--pending") boolean pending;

        @Override public Integer call() {
            List<ProfileFact> facts = svc().listFacts(CliRuntime.currentUser(), pending ? Boolean.FALSE : null);
            List<List<String>> rows = new ArrayList<>();
            List<Map<String, Object>> js = new ArrayList<>();
            for (ProfileFact f : facts) {
                rows.add(List.of(Out.shortId(f.getId()), f.getFactType().name(), f.isVerified() ? "verified" : (f.isNeedsReview() ? "fix: " + f.getReviewNote() : "pending"), f.toContextText()));
                js.add(Map.of("id", f.getId().toString(), "type", f.getFactType().name(), "verified", f.isVerified(), "data", f.getData()));
            }
            Out.table(List.of("ID", "TYPE", "STATE", "TEXT"), rows, js);
            return 0;
        }
    }

    @Command(name = "fact-verify", description = "Verify extracted facts, optionally applying edits (key=value after --set).")
    static class FactVerify implements Callable<Integer> {
        @Parameters(arity = "1..*") List<String> ids;
        @Option(names = "--set", split = ",", description = "key=value edits applied to every listed fact") List<String> edits;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            List<ProfileFact> all = svc().listFacts(u, null);
            List<UUID> uuids = ids.stream().map(i -> Ids.resolve(i, all, ProfileFact::getId)).toList();
            Map<UUID, Map<String, Object>> byFact = new LinkedHashMap<>();
            if (edits != null) {
                Map<String, Object> edit = new LinkedHashMap<>();
                for (String e : edits) edit.put(e.substring(0, e.indexOf('=')), e.substring(e.indexOf('=') + 1));
                uuids.forEach(id -> byFact.put(id, edit));
            }
            Out.line("%d fact(s) verified.", svc().verifyFacts(u, uuids, byFact));
            return 0;
        }
    }

    @Command(name = "fact-reject", description = "Delete extracted facts that are wrong.")
    static class FactReject implements Callable<Integer> {
        @Parameters(arity = "1..*") List<String> ids;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            List<ProfileFact> all = svc().listFacts(u, null);
            Out.line("%d fact(s) rejected.", svc().rejectFacts(u, ids.stream().map(i -> Ids.resolve(i, all, ProfileFact::getId)).toList()));
            return 0;
        }
    }

    @Command(name = "fact-delete", description = "Delete a fact. Documents that cited it are marked stale.")
    static class FactDelete implements Callable<Integer> {
        @Parameters(index = "0") String id;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            svc().deleteFact(u, Ids.resolve(id, svc().listFacts(u, null), ProfileFact::getId));
            Out.line("Deleted.");
            return 0;
        }
    }

    @Command(name = "resume", description = "Import a PDF, DOCX or TXT resume; the AI extracts facts for you to review.")
    static class ResumeImport implements Callable<Integer> {
        @Parameters(index = "0") Path file;

        @Override public Integer call() throws IOException {
            UploadedResume r = svc().importResume(CliRuntime.currentUser(), file.getFileName().toString(), Files.readAllBytes(file));
            Out.line("%s: %s%s", r.getOriginalName(), r.getParseStatus(), r.getStatusMessage().isEmpty() ? "" : " - " + r.getStatusMessage());
            if (r.getExtractedCount() > 0) Out.line("%d fact(s) extracted. Review them with: careerpilot profile fact-list --pending", r.getExtractedCount());
            return r.getParseStatus() == UploadedResume.ParseStatus.PARSED ? 0 : 1;
        }
    }

    @Command(name = "goal-show", description = "Show the career goal and scoring weights.")
    static class GoalShow implements Callable<Integer> {
        @Override public Integer call() {
            CareerGoal g = svc().getCareerGoal(CliRuntime.currentUser());
            if (g == null) {
                Out.line("No goal yet. Set one with: careerpilot profile goal-set --type COOP --term \"Summer 2027\"");
                return 0;
            }
            if (Out.json) {
                Out.json(Map.of("goal_type", g.getGoalType().name(), "strategy", g.strategyKey(), "work_term", g.getWorkTerm(),
                        "target_roles", g.getTargetRoles(), "min_match_score", g.getMinMatchScore(), "weights", g.weights().toMap()));
                return 0;
            }
            Out.line("Goal:      %s (strategy %s)", g.getGoalType(), g.strategyKey());
            Out.line("Work term: %s   Work model: %s", g.getWorkTerm(), g.getWorkModel());
            Out.line("Roles:     %s", String.join(", ", g.getTargetRoles()));
            Out.line("Locations: %s", String.join(", ", g.getPreferredLocations()));
            Out.line("Minimum match score: %d", g.getMinMatchScore());
            Out.line("Weights:   %s", Json.write(g.weights().toMap()));
            return 0;
        }
    }

    @Command(name = "goal-set", description = "Set the career goal; it selects the job-scoring strategy.")
    static class GoalSet implements Callable<Integer> {
        @Option(names = "--type", required = true, description = "COOP, INTERNSHIP, NEW_GRAD or CAREER_SWITCH") String type;
        @Option(names = "--term", defaultValue = "") String term;
        @Option(names = "--roles", defaultValue = "", description = "comma separated") String roles;
        @Option(names = "--locations", defaultValue = "") String locations;
        @Option(names = "--work-model", defaultValue = "ANY") String workModel;
        @Option(names = "--min-score", defaultValue = "60") int minScore;

        @Override public Integer call() {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("goal_type", type.toUpperCase());
            data.put("work_term", term);
            data.put("target_roles", roles);
            data.put("preferred_locations", locations);
            data.put("work_model", workModel.toUpperCase());
            data.put("min_match_score", minScore);
            CareerGoal g = svc().updateCareerGoal(CliRuntime.currentUser(), data);
            Out.line("Goal saved: %s (strategy %s). Scores will be recomputed.", g.getGoalType(), g.strategyKey());
            return 0;
        }
    }
}
