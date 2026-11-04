package com.careerpilot.cli;

import com.careerpilot.analytics.AnalyticsService;
import com.careerpilot.analytics.GroupStats;
import com.careerpilot.analytics.StrategyAdjustment;
import com.careerpilot.notifications.Notification;
import com.careerpilot.notifications.NotificationService;
import com.careerpilot.notifications.ReminderService;
import com.careerpilot.users.User;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/** F15 notifications and reminders, F19 outcome analytics and adaptive strategy. */
public final class InsightCommands {
    private InsightCommands() {}

    @Command(name = "notifications", description = "Your notification feed (status changes from emails, interviews, deadlines) (F15).",
            subcommands = {Remind.class, Read.class})
    public static class Notifications implements Callable<Integer> {
        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            List<List<String>> rows = new ArrayList<>();
            List<Map<String, Object>> js = new ArrayList<>();
            for (Notification n : CliRuntime.bean(NotificationService.class).list(u)) {
                rows.add(List.of(n.isRead() ? "" : "new", n.getKind().name(), n.getCreatedAt().toString().substring(0, 16), n.getMessage()));
                js.add(Map.of("kind", n.getKind().name(), "read", n.isRead(), "message", n.getMessage()));
            }
            Out.table(List.of("", "KIND", "WHEN", "MESSAGE"), rows, js);
            return 0;
        }
    }

    @Command(name = "remind", description = "Check deadlines of postings you have not applied to yet.")
    static class Remind implements Callable<Integer> {
        @Option(names = "--days", defaultValue = "7") int days;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            Out.line("%d posting(s) with a deadline in the next %d days.", CliRuntime.bean(ReminderService.class).checkDeadlines(u, days), days);
            return 0;
        }
    }

    @Command(name = "read", description = "Mark every notification as read.")
    static class Read implements Callable<Integer> {
        @Override public Integer call() {
            CliRuntime.bean(NotificationService.class).markAllRead(CliRuntime.currentUser());
            Out.line("All read.");
            return 0;
        }
    }

    static AnalyticsService svc() { return CliRuntime.bean(AnalyticsService.class); }

    @Command(name = "analytics", description = "Outcome statistics and proposed strategy adjustments (F19).",
            subcommands = {Accept.class, Reject.class, Revert.class, History.class})
    public static class Analytics implements Callable<Integer> {
        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            AnalyticsService.InsightReport r = svc().getInsights(u);
            if (Out.json) {
                Out.json(Map.of("stats", r.stats().toMap(), "narrative", r.narrative(), "enough_data", r.enoughData(),
                        "proposals", r.proposals().stream().map(p -> Map.of("id", p.getId().toString(), "factor", p.getFactor(), "from", p.getOldValue(), "to", p.getNewValue())).toList()));
                return 0;
            }
            Out.line("%s", r.narrative());
            Out.line("Applied: %d   With an outcome: %d   Median days to response: %s", r.stats().applied(), r.stats().withOutcome(), r.stats().medianDaysToResponse() == null ? "-" : r.stats().medianDaysToResponse());
            group("By match score", r.stats().byScoreBand());
            group("By source", r.stats().bySource());
            group("By role category", r.stats().byRoleCategory());
            for (StrategyAdjustment p : r.proposals()) {
                Out.line("Proposal %s: %s weight %.2f -> %.2f", Out.shortId(p.getId()), p.getFactor(), p.getOldValue(), p.getNewValue());
                Out.line("  %s", p.getRationale());
                Out.line("  accept: careerpilot analytics accept %s   reject: careerpilot analytics reject %s", Out.shortId(p.getId()), Out.shortId(p.getId()));
            }
            return 0;
        }

        private static void group(String title, List<GroupStats> groups) {
            if (groups.isEmpty()) return;
            Out.line("%s:", title);
            groups.forEach(g -> Out.line("  %-10s n=%-3d responded %3d%%  interviews %3d%%", g.label(), g.applied(), Math.round(g.responseRate() * 100), Math.round(g.interviewRate() * 100)));
        }
    }

    private static java.util.UUID adjustmentId(User u, String text) {
        return Ids.resolve(text, svc().history(u), StrategyAdjustment::getId);
    }

    @Command(name = "accept", description = "Accept a proposed weight change.")
    static class Accept implements Callable<Integer> {
        @Parameters(index = "0") String id;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            StrategyAdjustment a = svc().accept(u, adjustmentId(u, id));
            Out.line("Accepted: %s weight is now %.2f. Match scores will be recomputed. Undo with: careerpilot analytics revert %s", a.getFactor(), a.getNewValue(), Out.shortId(a.getId()));
            return 0;
        }
    }

    @Command(name = "reject", description = "Reject a proposed weight change.")
    static class Reject implements Callable<Integer> {
        @Parameters(index = "0") String id;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            svc().reject(u, adjustmentId(u, id));
            Out.line("Rejected.");
            return 0;
        }
    }

    @Command(name = "revert", description = "Revert an accepted adjustment.")
    static class Revert implements Callable<Integer> {
        @Parameters(index = "0") String id;

        @Override public Integer call() {
            User u = CliRuntime.currentUser();
            StrategyAdjustment a = svc().revert(u, adjustmentId(u, id));
            Out.line("Reverted: %s weight is back to %.2f.", a.getFactor(), a.getOldValue());
            return 0;
        }
    }

    @Command(name = "history", description = "All proposals and what you decided.")
    static class History implements Callable<Integer> {
        @Override public Integer call() {
            List<List<String>> rows = new ArrayList<>();
            List<Map<String, Object>> js = new ArrayList<>();
            for (StrategyAdjustment a : svc().history(CliRuntime.currentUser())) {
                rows.add(List.of(Out.shortId(a.getId()), a.getStatus().name(), a.getFactor(), String.format("%.2f -> %.2f", a.getOldValue(), a.getNewValue())));
                js.add(Map.of("id", a.getId().toString(), "status", a.getStatus().name(), "factor", a.getFactor()));
            }
            Out.table(List.of("ID", "STATUS", "FACTOR", "CHANGE"), rows, js);
            return 0;
        }
    }
}
