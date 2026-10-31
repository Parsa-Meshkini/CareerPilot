package com.careerpilot.gui;

import com.careerpilot.agent.AgentController;
import com.careerpilot.agent.AgentRun;
import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.candidates.CandidateService;
import com.careerpilot.integrations.DetectedEmailEvent;
import com.careerpilot.integrations.EmailSyncService;
import com.careerpilot.jobs.JobImportService;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.jobs.MatchingService;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.Consumer;

/** Overview (F09, F16): what needs the student's attention today. */
final class DashboardView extends View {
    private final Consumer<String> navigate;
    private final FlowPane stats = new FlowPane(12, 12);
    private final VBox attention = new VBox(8);
    private final TableView<JobPosting> shortlist = Ui.table(
            Ui.col("Title", 260, JobPosting::getTitle), Ui.col("Company", 160, JobPosting::getCompany),
            Ui.col("Deadline", 100, j -> j.getDeadline() == null ? "" : j.getDeadline().toString()),
            Ui.col("Why", 220, JobPosting::getShortlistReason));

    DashboardView(AppContext app, Consumer<String> navigate) {
        super(app);
        this.navigate = navigate;
        shortlist.setPrefHeight(220);
        getChildren().addAll(Ui.title("Dashboard"), stats, Ui.heading("Needs your attention"), attention, Ui.heading("Shortlist chosen by the agent"), shortlist);
    }

    @Override String name() { return "Dashboard"; }

    private Label stat(String number, String caption) {
        Label n = new Label(number);
        n.getStyleClass().add("stat-number");
        Label c = Ui.muted(caption);
        VBox box = new VBox(2, n, c);
        box.getStyleClass().add("card");
        box.setPrefWidth(170);
        stats.getChildren().add(box);
        return n;
    }

    @Override
    void refresh() {
        Async.run(this, () -> {
            var user = app.user();
            List<JobPosting> jobs = app.bean(JobImportService.class).listJobs(user, null, null, null, false);
            List<Application> apps = app.bean(ApplicationService.class).listApplications(user, null);
            List<DetectedEmailEvent> review = app.bean(EmailSyncService.class).listEvents(user, DetectedEmailEvent.NEEDS_REVIEW);
            List<AgentRun> runs = app.bean(AgentController.class).listRuns(user);
            List<String> hints = app.bean(CandidateService.class).completenessHints(user);
            long outdated = jobs.stream().map(j -> app.bean(MatchingService.class).getMatch(j)).filter(m -> m != null && m.isOutdated()).count();
            return new Object[]{jobs, apps, review, runs, hints, outdated};
        }, data -> {
            @SuppressWarnings("unchecked") List<JobPosting> jobs = (List<JobPosting>) data[0];
            @SuppressWarnings("unchecked") List<Application> apps = (List<Application>) data[1];
            @SuppressWarnings("unchecked") List<DetectedEmailEvent> review = (List<DetectedEmailEvent>) data[2];
            @SuppressWarnings("unchecked") List<AgentRun> runs = (List<AgentRun>) data[3];
            @SuppressWarnings("unchecked") List<String> hints = (List<String>) data[4];
            long outdated = (Long) data[5];
            stats.getChildren().clear();
            stat(String.valueOf(jobs.size()), "saved postings");
            stat(String.valueOf(jobs.stream().filter(JobPosting::isShortlisted).count()), "shortlisted");
            stat(String.valueOf(apps.size()), "applications tracked");
            stat(String.valueOf(apps.stream().filter(a -> a.getStateName().equals("INTERVIEW")).count()), "in interview stage");
            stat(String.valueOf(review.size()), "emails to review");

            attention.getChildren().clear();
            runs.stream().filter(r -> r.getStatus().equals(AgentRun.AWAITING_APPROVAL) || r.getStatus().equals(AgentRun.AWAITING_INPUT)).forEach(r ->
                    banner("The agent is waiting for you: " + r.getGoalText(), "Agent"));
            if (!review.isEmpty()) banner(review.size() + " detected email event(s) need confirmation.", "Email");
            if (outdated > 0) banner(outdated + " match score(s) are outdated because your goal changed. Re-score on the Jobs page.", "Jobs");
            LocalDate today = LocalDate.now();
            jobs.stream().filter(j -> j.getDeadline() != null && !j.getDeadline().isBefore(today) && ChronoUnit.DAYS.between(today, j.getDeadline()) <= 7)
                    .forEach(j -> banner("Deadline in " + ChronoUnit.DAYS.between(today, j.getDeadline()) + " day(s): " + j.getTitle() + " at " + j.getCompany(), "Jobs"));
            hints.forEach(h -> banner(h, "Profile"));
            if (attention.getChildren().isEmpty()) attention.getChildren().add(Ui.muted("Nothing needs your attention."));
            Ui.fill(shortlist, jobs.stream().filter(JobPosting::isShortlisted).toList());
        });
    }

    private void banner(String text, String page) {
        Label l = new Label(text + "  (open " + page + ")");
        l.setWrapText(true);
        l.setOnMouseClicked(e -> navigate.accept(page));
        l.getStyleClass().add("banner");
        l.setMaxWidth(Double.MAX_VALUE);
        l.setPadding(new Insets(8));
        attention.getChildren().add(l);
    }

}
