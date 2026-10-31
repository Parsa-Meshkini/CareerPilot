package com.careerpilot.gui;

import com.careerpilot.agent.AgentController;
import com.careerpilot.agent.AgentRun;
import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.candidates.CandidateService;
import com.careerpilot.integrations.DetectedEmailEvent;
import com.careerpilot.integrations.EmailSyncService;
import com.careerpilot.jobs.JobImportService;
import com.careerpilot.jobs.JobMatch;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.jobs.MatchingService;
import com.careerpilot.notifications.Notification;
import com.careerpilot.notifications.NotificationService;
import com.careerpilot.notifications.ReminderService;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Overview (F09, F16): what needs the student's attention today, a few numbers with context, and the agent's shortlist. */
final class DashboardView extends View {
    private record Item(int rank, String title, String hint, String kind, String page) {}

    private final Consumer<String> navigate;
    private final HBox tiles = new HBox(12);
    private final VBox queue = new VBox(8);
    private final Label queueCount = new Label();
    private final VBox shortlist = new VBox(8);
    private boolean loaded;

    DashboardView(AppContext app, Consumer<String> navigate) {
        super(app);
        this.navigate = navigate;
        queueCount.getStyleClass().add("count-pill");
        HBox queueHead = new HBox(8, Ui.heading("Needs you"), queueCount);
        queueHead.setAlignment(Pos.CENTER_LEFT);
        VBox body = new VBox(20, tiles, new VBox(10, queueHead, queue), new VBox(10, Ui.heading("Shortlist"), shortlist));
        getChildren().add(Ui.scroll(body));
        showLoading();
    }

    @Override String name() { return "Dashboard"; }

    @Override String subtitle() { return "Where your search stands today."; }

    @Override List<Node> actions() { return List.of(Ui.button("Mark notifications read", this::markRead)); }

    private void showLoading() {
        queue.getChildren().setAll(Ui.skeleton(100, 52), Ui.skeleton(100, 52));
        shortlist.getChildren().setAll(Ui.skeleton(100, 56), Ui.skeleton(100, 56));
    }

    @Override
    void refresh() {
        if (!loaded) showLoading();
        Async.run(this, () -> {
            var user = app.user();
            MatchingService matching = app.bean(MatchingService.class);
            List<JobPosting> jobs = app.bean(JobImportService.class).listJobs(user, null, null, null, false);
            List<Application> apps = app.bean(ApplicationService.class).listApplications(user, null);
            List<DetectedEmailEvent> review = app.bean(EmailSyncService.class).listEvents(user, DetectedEmailEvent.NEEDS_REVIEW);
            List<AgentRun> runs = app.bean(AgentController.class).listRuns(user);
            List<String> hints = app.bean(CandidateService.class).completenessHints(user);
            app.bean(ReminderService.class).checkDeadlines(user, 7);
            List<Notification> feed = app.bean(NotificationService.class).list(user);
            java.util.Map<java.util.UUID, JobMatch> matches = new java.util.HashMap<>();
            for (JobPosting j : jobs) {
                JobMatch m = matching.getMatch(j);
                if (m != null) matches.put(j.getId(), m);
            }
            return new Object[]{jobs, apps, review, runs, hints, feed, matches};
        }, data -> {
            @SuppressWarnings("unchecked") List<JobPosting> jobs = (List<JobPosting>) data[0];
            @SuppressWarnings("unchecked") List<Application> apps = (List<Application>) data[1];
            @SuppressWarnings("unchecked") List<DetectedEmailEvent> review = (List<DetectedEmailEvent>) data[2];
            @SuppressWarnings("unchecked") List<AgentRun> runs = (List<AgentRun>) data[3];
            @SuppressWarnings("unchecked") List<String> hints = (List<String>) data[4];
            @SuppressWarnings("unchecked") List<Notification> feed = (List<Notification>) data[5];
            @SuppressWarnings("unchecked") java.util.Map<java.util.UUID, JobMatch> matches = (java.util.Map<java.util.UUID, JobMatch>) data[6];
            loaded = true;
            LocalDate today = LocalDate.now();
            long outdated = matches.values().stream().filter(JobMatch::isOutdated).count();
            List<JobPosting> picks = jobs.stream().filter(JobPosting::isShortlisted).toList();

            // numbers with context
            JobPosting nextDue = jobs.stream().filter(j -> j.getDeadline() != null && !j.getDeadline().isBefore(today))
                    .min(Comparator.comparing(JobPosting::getDeadline)).orElse(null);
            long interviews = apps.stream().filter(a -> a.getStateName().equals("INTERVIEW")).count();
            tiles.getChildren().setAll(
                    tile(Ui.statTile(String.valueOf(jobs.size()), "Saved postings", picks.size() + " shortlisted", () -> navigate.accept("Jobs"))),
                    tile(Ui.statTile(String.valueOf(apps.size()), "Applications", interviews + " at interview stage", () -> navigate.accept("Tracker"))),
                    tile(Ui.statTile(nextDue == null ? "–" : ChronoUnit.DAYS.between(today, nextDue.getDeadline()) + "d", "Next deadline",
                            nextDue == null ? "No open deadlines" : nextDue.getTitle() + " at " + nextDue.getCompany(), () -> navigate.accept("Jobs"))),
                    tile(Ui.statTile(String.valueOf(review.size()), "Emails to review", review.isEmpty() ? "All clear" : "Waiting for you", () -> navigate.accept("Email"))));

            // the queue
            List<Item> items = new ArrayList<>();
            runs.stream().filter(r -> r.getStatus().equals(AgentRun.AWAITING_APPROVAL) || r.getStatus().equals(AgentRun.AWAITING_INPUT)).forEach(r ->
                    items.add(new Item(0, "The agent is waiting for you", r.getGoalText() + " • Open Agent to approve or answer", "urgent", "Agent")));
            jobs.stream().filter(j -> j.getDeadline() != null && !j.getDeadline().isBefore(today) && ChronoUnit.DAYS.between(today, j.getDeadline()) <= 7)
                    .forEach(j -> {
                        long d = ChronoUnit.DAYS.between(today, j.getDeadline());
                        items.add(new Item(1, j.getTitle() + " at " + j.getCompany(), "Deadline " + (d == 0 ? "today" : d == 1 ? "tomorrow" : "in " + d + " days") + " • Open Jobs", d <= 3 ? "urgent" : "info", "Jobs"));
                    });
            if (!review.isEmpty()) items.add(new Item(2, review.size() + " email" + (review.size() == 1 ? "" : "s") + " to confirm", "Detected interview, offer or rejection emails • Open Email", "info", "Email"));
            if (outdated > 0) items.add(new Item(3, outdated + " match score" + (outdated == 1 ? " is" : "s are") + " outdated", "Your goal changed • Re-score on Jobs", "info", "Jobs"));
            feed.stream().filter(n -> !n.isRead()).limit(4).forEach(n -> {
                String page = n.getKind() == Notification.Kind.INTERVIEW || n.getKind() == Notification.Kind.PREP_READY ? "Interview" : "Tracker";
                items.add(new Item(4, n.getMessage(), "Open " + page, "info", page));
            });
            hints.forEach(h -> items.add(new Item(5, h, "Strengthens your documents • Open Profile", "info", "Profile")));
            items.sort(Comparator.comparingInt(Item::rank));
            queueCount.setText(String.valueOf(items.size()));
            queueCount.setVisible(!items.isEmpty());
            queueCount.setManaged(!items.isEmpty());
            if (items.isEmpty()) queue.getChildren().setAll(Ui.emptyState("Check", "Clear skies", "Nothing needs you right now. New items appear here as the agent and your inbox find them."));
            else {
                queue.getChildren().clear();
                items.forEach(i -> queue.getChildren().add(Ui.queueRow(i.title(), i.hint(), i.kind(), () -> navigate.accept(i.page()))));
            }

            // shortlist as rich rows
            if (picks.isEmpty()) shortlist.getChildren().setAll(Ui.emptyState("Jobs", "No shortlist yet",
                    "Once you set a career goal and import postings, the agent picks the best fits and lists them here.", Ui.primary("Open Jobs", () -> navigate.accept("Jobs"))));
            else {
                shortlist.getChildren().clear();
                picks.stream().sorted(Comparator.comparingInt((JobPosting j) -> matches.containsKey(j.getId()) ? -matches.get(j.getId()).getScore() : 1)).forEach(j -> shortlist.getChildren().add(shortlistRow(j, matches.get(j.getId()))));
            }
        });
    }

    private static Node tile(Button b) {
        HBox.setHgrow(b, Priority.ALWAYS);
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    private Button shortlistRow(JobPosting j, JobMatch m) {
        Integer score = m == null ? null : m.getScore();
        Label title = new Label(j.getTitle());
        title.getStyleClass().add("queue-title");
        Label meta = new Label(j.getCompany() + (j.getShortlistReason().isEmpty() ? "" : " • " + j.getShortlistReason()));
        meta.getStyleClass().add("queue-hint");
        VBox text = new VBox(2, title, meta);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label fit = new Label(ScoreRing.word(score));
        fit.getStyleClass().addAll("fit-word", ScoreRing.band(score));
        HBox box = new HBox(14, new ScoreRing(score, m != null && m.isOutdated(), 40), text, fit);
        if (j.getDeadline() != null) box.getChildren().add(Ui.chip("Due " + j.getDeadline(), "warning"));
        box.getChildren().add(new StackPane(Icons.box("Chevron", 14)) {{ getStyleClass().add("chevron"); }});
        box.setAlignment(Pos.CENTER_LEFT);
        Button b = new Button("", box);
        b.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        b.getStyleClass().add("queue-row");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        b.setAccessibleText(j.getTitle() + " at " + j.getCompany() + ". " + ScoreRing.word(score));
        b.setOnAction(e -> navigate.accept("Jobs"));
        return b;
    }

    private void markRead() {
        Async.run(this, () -> app.bean(NotificationService.class).markAllRead(app.user()), this::refresh);
    }
}
