package com.careerpilot.cli;

import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.integrations.DetectedEmailEvent;
import com.careerpilot.integrations.EmailAccount;
import com.careerpilot.integrations.EmailSyncService;
import com.careerpilot.integrations.EmlFileAdapter;
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

/** F14 email monitoring: upload .eml files or sync the mock inbox, then review what was detected. */
public final class EmailCommands {
    private EmailCommands() {}

    static EmailSyncService sync() { return CliRuntime.bean(EmailSyncService.class); }

    static UUID eventId(User u, String text) { return Ids.resolve(text, sync().listEvents(u, null), DetectedEmailEvent::getId); }

    @Command(name = "email", description = "Detect interview, rejection and offer emails and review them (F14).",
            subcommands = {Upload.class, SyncMock.class, Events.class, Confirm.class, Dismiss.class})
    public static class Email implements Callable<Integer> {
        @Override public Integer call() { new picocli.CommandLine(this).usage(Out.out); return 0; }
    }

    private static void summary(Map<String, Object> s) {
        if (Out.json) {
            Out.json(s);
            return;
        }
        Out.line("Fetched %s: %s new, %s duplicate, %s not job related, %s applied automatically, %s waiting for your review.",
                s.get("fetched"), s.get("new"), s.get("duplicates"), s.get("not_relevant"), s.get("published"), s.get("needs_review"));
        if (s.get("rejected_files") instanceof List<?> bad) bad.forEach(b -> Out.line("  skipped %s: %s", ((Map<?, ?>) b).get("file"), ((Map<?, ?>) b).get("reason")));
    }

    @Command(name = "upload", description = "Analyse exported .eml files.")
    static class Upload implements Callable<Integer> {
        @Parameters(arity = "1..*") List<Path> files;

        @Override public Integer call() throws IOException {
            List<EmlFileAdapter.Upload> uploads = new ArrayList<>();
            for (Path f : files) uploads.add(new EmlFileAdapter.Upload(f.getFileName().toString(), Files.readAllBytes(f)));
            summary(sync().importEmlFiles(CliRuntime.currentUser(), uploads));
            return 0;
        }
    }

    @Command(name = "sync-mock", description = "Sync the scripted demo inbox.")
    static class SyncMock implements Callable<Integer> {
        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            EmailAccount account = sync().connectMock(u);
            summary(sync().syncAccount(u, account.getId()));
            return 0;
        }
    }

    @Command(name = "events", description = "List detected events; by default only the ones needing review.")
    static class Events implements Callable<Integer> {
        @Option(names = "--all") boolean all;

        @Override public Integer call() {
            List<List<String>> rows = new ArrayList<>();
            List<Map<String, Object>> js = new ArrayList<>();
            for (DetectedEmailEvent e : sync().listEvents(CliRuntime.currentUser(), all ? null : DetectedEmailEvent.NEEDS_REVIEW)) {
                String app = e.getApplication() == null ? "(no match)" : e.getApplication().getJob().getTitle() + " at " + e.getApplication().getJob().getCompany();
                rows.add(List.of(Out.shortId(e.getId()), e.getReviewStatus(), e.getEventType(), String.format("%.2f", e.getConfidence()), e.getClassifier(), e.getEmail().getSubject(), app));
                js.add(Map.of("id", e.getId().toString(), "status", e.getReviewStatus(), "type", e.getEventType(), "confidence", e.getConfidence(), "candidates", e.getCandidates()));
            }
            Out.table(List.of("ID", "STATUS", "TYPE", "CONF", "BY", "SUBJECT", "APPLICATION"), rows, js);
            return 0;
        }
    }

    @Command(name = "confirm", description = "Confirm an event, optionally choosing the application and correcting the type.")
    static class Confirm implements Callable<Integer> {
        @Parameters(index = "0") String id;
        @Option(names = "--app") String app;
        @Option(names = "--type") String type;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            UUID appId = app == null ? null : Ids.resolve(app, CliRuntime.bean(ApplicationService.class).listApplications(u, null), Application::getId);
            DetectedEmailEvent e = sync().confirmEvent(u, eventId(u, id), appId, type);
            Out.line("Confirmed %s for %s.", e.getEventType(), e.getApplication().getJob().getTitle());
            return 0;
        }
    }

    @Command(name = "dismiss", description = "Dismiss an event that is not about an application.")
    static class Dismiss implements Callable<Integer> {
        @Parameters(index = "0") String id;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            sync().dismissEvent(u, eventId(u, id));
            Out.line("Dismissed.");
            return 0;
        }
    }
}
