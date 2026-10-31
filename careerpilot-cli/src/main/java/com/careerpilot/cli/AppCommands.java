package com.careerpilot.cli;

import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.applications.StatusChange;
import com.careerpilot.documents.ApplicationPackage;
import com.careerpilot.documents.DocumentService;
import com.careerpilot.documents.GeneratedDocument;
import com.careerpilot.documents.PackageAssembler;
import com.careerpilot.users.User;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;

/** F13 lifecycle tracking, F10-F12 documents and packages. */
public final class AppCommands {
    private AppCommands() {}

    static ApplicationService apps() { return CliRuntime.bean(ApplicationService.class); }

    static UUID appId(User u, String text) { return Ids.resolve(text, apps().listApplications(u, null), Application::getId); }

    @Command(name = "apps", description = "Track applications through their lifecycle (F13).",
            subcommands = {Create.class, ListApps.class, ShowApp.class, Act.class, Undo.class, Notes.class})
    public static class Apps implements Callable<Integer> {
        @Override public Integer call() { new picocli.CommandLine(this).usage(Out.out); return 0; }
    }

    @Command(name = "create", description = "Start tracking an application for a job.")
    static class Create implements Callable<Integer> {
        @Parameters(index = "0", description = "job id") String job;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            Application a = apps().createApplication(u, JobCommands.jobId(u, job), "");
            Out.line("Tracking %s at %s (%s), state %s.", a.getJob().getTitle(), a.getJob().getCompany(), Out.shortId(a.getId()), a.getStateName());
            return 0;
        }
    }

    @Command(name = "list", description = "List applications.")
    static class ListApps implements Callable<Integer> {
        @Option(names = "--state") String state;

        @Override public Integer call() {
            List<List<String>> rows = new ArrayList<>();
            List<Map<String, Object>> js = new ArrayList<>();
            for (Application a : apps().listApplications(CliRuntime.currentUser(), state)) {
                rows.add(List.of(Out.shortId(a.getId()), a.getStateName(), a.getJob().getTitle(), a.getJob().getCompany(), String.join(",", a.allowedActions())));
                js.add(Map.of("id", a.getId().toString(), "state", a.getStateName(), "title", a.getJob().getTitle(), "company", a.getJob().getCompany(), "allowed_actions", a.allowedActions()));
            }
            Out.table(List.of("ID", "STATE", "TITLE", "COMPANY", "NEXT ACTIONS"), rows, js);
            return 0;
        }
    }

    @Command(name = "show", description = "Show an application's timeline, interviews and documents.")
    static class ShowApp implements Callable<Integer> {
        @Parameters(index = "0") String id;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            Application a = apps().get(u, appId(u, id));
            Out.line("%s at %s: %s   (allowed: %s)", a.getJob().getTitle(), a.getJob().getCompany(), a.getStateName(), String.join(", ", a.allowedActions()));
            for (StatusChange c : apps().timeline(u, a.getId()))
                Out.line("  %s  %s -> %s  [%s]%s%s", c.getId().toString().substring(0, 8), c.getFromState(), c.getToState(), c.getSource(),
                        c.getReason().isEmpty() ? "" : " " + c.getReason(), c.isReverted() ? " (reverted)" : "");
            apps().interviews(u, a.getId()).forEach(i -> Out.line("  interview round %d %s %s", i.getRound(), i.getScheduledAt() == null ? "" : i.getScheduledAt(), i.getFormat()));
            CliRuntime.bean(DocumentService.class).list(u, a.getId()).forEach(d ->
                    Out.line("  document %s v%d %s%s", d.getDocType(), d.getVersion(), d.getVerificationStatus(), d.isStale() ? " (stale)" : ""));
            if (!a.getNotes().isEmpty()) Out.line("Notes: %s", a.getNotes());
            return 0;
        }
    }

    @Command(name = "act", description = "Apply a lifecycle action: mark_prepared, submit, schedule_interview, receive_offer, reject, accept, decline, withdraw.")
    static class Act implements Callable<Integer> {
        @Parameters(index = "0") String id;
        @Parameters(index = "1") String action;
        @Option(names = "--reason", defaultValue = "") String reason;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            StatusChange c = apps().transition(u, appId(u, id), action, StatusChange.USER, null, reason, null, null);
            Out.line("%s -> %s (change %s; undo with: careerpilot apps undo %s)", c.getFromState(), c.getToState(), Out.shortId(c.getId()), Out.shortId(c.getId()));
            return 0;
        }
    }

    @Command(name = "undo", description = "Undo the most recent status change of an application.")
    static class Undo implements Callable<Integer> {
        @Parameters(index = "0", description = "change id") String change;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            UUID id = Ids.resolve(change, apps().listApplications(u, null).stream().flatMap(a -> apps().timeline(u, a.getId()).stream()).toList(), StatusChange::getId);
            Application a = apps().undoTransition(u, id);
            Out.line("Restored state %s.", a.getStateName());
            return 0;
        }
    }

    @Command(name = "notes", description = "Replace the notes of an application.")
    static class Notes implements Callable<Integer> {
        @Parameters(index = "0") String id;
        @Parameters(index = "1") String text;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            apps().updateNotes(u, appId(u, id), text);
            Out.line("Notes saved.");
            return 0;
        }
    }

    // ---- documents ----

    @Command(name = "docs", description = "Generate tailored documents and application packages (F10-F12).",
            subcommands = {Resume.class, CoverLetter.class, BuildPackage.class, Download.class})
    public static class Docs implements Callable<Integer> {
        @Override public Integer call() { new picocli.CommandLine(this).usage(Out.out); return 0; }
    }

    private static void printDoc(GeneratedDocument d) {
        Out.line("%s v%d: %s (%s)", d.getDocType(), d.getVersion(), d.getVerificationStatus(), Out.shortId(d.getId()));
        @SuppressWarnings("unchecked") List<Map<String, Object>> violations = (List<Map<String, Object>>) d.getVerificationReport().getOrDefault("violations", List.of());
        violations.forEach(v -> Out.line("  removed/flagged [%s] %s: %s", v.get("location"), v.get("rule"), v.get("detail")));
    }

    @Command(name = "resume", description = "Generate a tailored resume for an application.")
    static class Resume implements Callable<Integer> {
        @Parameters(index = "0", description = "application id") String app;
        @Option(names = "--out", description = "write the PDF here") Path out;

        @Override public Integer call() throws IOException {
            User u = CliRuntime.currentUser();
            DocumentService docs = CliRuntime.bean(DocumentService.class);
            GeneratedDocument d = docs.generate(u, appId(u, app), "resume");
            printDoc(d);
            if (out != null) Files.write(out, docs.file(u, d.getId(), "pdf"));
            return 0;
        }
    }

    @Command(name = "cover-letter", description = "Generate a tailored cover letter for an application.")
    static class CoverLetter implements Callable<Integer> {
        @Parameters(index = "0") String app;
        @Option(names = "--tone", defaultValue = "formal", description = "formal or warm") String tone;
        @Option(names = "--out") Path out;

        @Override public Integer call() throws IOException {
            User u = CliRuntime.currentUser();
            DocumentService docs = CliRuntime.bean(DocumentService.class);
            GeneratedDocument d = docs.generate(u, appId(u, app), "cover_letter", null, Map.of("tone", tone));
            printDoc(d);
            if (out != null) Files.write(out, docs.file(u, d.getId(), "pdf"));
            return 0;
        }
    }

    @Command(name = "package", description = "Build the application package ZIP (resume, cover letter, description, checklist).")
    static class BuildPackage implements Callable<Integer> {
        @Parameters(index = "0") String app;
        @Option(names = "--regenerate") boolean regenerate;
        @Option(names = "--tone", defaultValue = "formal") String tone;
        @Option(names = "--out", description = "copy the ZIP to this path") Path out;

        @Override public Integer call() throws IOException {
            User u = CliRuntime.currentUser();
            PackageAssembler packager = CliRuntime.bean(PackageAssembler.class);
            ApplicationPackage p = packager.buildPackage(u, appId(u, app), regenerate, tone, null);
            Out.line("Package %s.zip ready (%s)%s", p.getFolderName(), Out.shortId(p.getId()), p.isNeedsReview() ? " - some content was flagged, check the checklist" : "");
            Out.line("Contents: %s", String.join(", ", p.getManifest()));
            if (out != null) {
                Files.write(out, packager.download(u, p.getId()));
                Out.line("Saved to %s. You submit it; CareerPilot never does.", out);
            }
            return 0;
        }
    }

    @Command(name = "download", description = "Save a generated document (PDF or DOCX).")
    static class Download implements Callable<Integer> {
        @Parameters(index = "0", description = "document id") String id;
        @Parameters(index = "1") Path out;
        @Option(names = "--format", defaultValue = "pdf") String format;

        @Override public Integer call() throws IOException {
            User u = CliRuntime.currentUser();
            DocumentService docs = CliRuntime.bean(DocumentService.class);
            Files.write(out, docs.file(u, UUID.fromString(id), format));
            Out.line("Saved %s.", out);
            return 0;
        }
    }
}
