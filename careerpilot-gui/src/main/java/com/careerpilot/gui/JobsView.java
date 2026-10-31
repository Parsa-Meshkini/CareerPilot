package com.careerpilot.gui;

import com.careerpilot.applications.ApplicationService;
import com.careerpilot.common.ValidationException;
import com.careerpilot.common.Json;
import com.careerpilot.jobs.ImportBatch;
import com.careerpilot.jobs.JobAnalysis;
import com.careerpilot.jobs.JobAnalysisService;
import com.careerpilot.jobs.JobImportService;
import com.careerpilot.jobs.JobMatch;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.jobs.MatchingService;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** F04-F08: import (file, paste, extension), normalized duplicate-free list, AI analysis and match with skill gaps. */
final class JobsView extends View {
    private record Row(JobPosting job, JobMatch match) {}

    private final Consumer<String> navigate;
    private final TextField search = new TextField();
    private final TableView<Row> table = Ui.table(
            Ui.col("Score", 64, r -> r.match() == null ? "-" : r.match().getScore() + (r.match().isOutdated() ? "*" : "")),
            Ui.chipCol("Pick", 100, r -> r.job().isShortlisted() ? "SHORTLISTED" : ""),
            Ui.col("Title", 220, r -> r.job().getTitle()), Ui.col("Company", 130, r -> r.job().getCompany()),
            Ui.col("Deadline", 96, r -> r.job().getDeadline() == null ? "" : r.job().getDeadline().toString()),
            Ui.chipCol("Detail", 90, r -> r.job().getCompleteness().name()));
    private final VBox detail = new VBox(10);
    private final javafx.scene.control.ScrollPane detailScroll = new javafx.scene.control.ScrollPane(detail);
    private final Label status = Ui.muted("");

    JobsView(AppContext app, Consumer<String> navigate) {
        super(app);
        this.navigate = navigate;
        search.setPromptText("Search title, company, location");
        search.textProperty().addListener((o, a, b) -> refresh());
        table.getSelectionModel().selectedItemProperty().addListener((o, a, row) -> showDetail(row));
        detail.setPadding(new javafx.geometry.Insets(4, 8, 4, 8));
        detailScroll.setFitToWidth(true);
        VBox.setVgrow(detailScroll, Priority.ALWAYS);
        showDetail(null);
        SplitPane split = new SplitPane(table, new VBox(8, detailScroll, Ui.row(Ui.primary("Analyze with AI", this::analyze), Ui.button("Track application", this::track),
                Ui.button("Dismiss", this::dismiss))));
        split.setDividerPositions(0.6);
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().addAll(Ui.title("Jobs"),
                Ui.row(search, Ui.button("Import CSV/JSON", this::importFile), Ui.button("Paste a posting", this::paste), Ui.button("Score all", this::scoreAll)),
                status, split);
        HBoxGrow.grow(search);
    }

    /** Tiny helper so the search box stretches inside its row. */
    private static final class HBoxGrow {
        static void grow(javafx.scene.Node n) { javafx.scene.layout.HBox.setHgrow(n, Priority.ALWAYS); }
    }

    @Override void selectFirstRow() { table.getSelectionModel().selectFirst(); }

    @Override String name() { return "Jobs"; }

    private JobImportService imports() { return app.bean(JobImportService.class); }

    @Override
    void refresh() {
        String q = search.getText();
        Async.run(null, () -> {
            MatchingService matching = app.bean(MatchingService.class);
            List<Row> rows = new ArrayList<>();
            for (JobPosting j : imports().listJobs(app.user(), q, null, null, false)) rows.add(new Row(j, matching.getMatch(j)));
            rows.sort((a, b) -> Integer.compare(b.match() == null ? -1 : b.match().getScore(), a.match() == null ? -1 : a.match().getScore()));
            return rows;
        }, rows -> {
            Ui.fill(table, rows);
            status.setText(rows.size() + " posting(s). A * after a score means it is outdated because your goal changed.");
        });
    }

    private void showDetail(Row row) {
        detail.getChildren().clear();
        if (row == null) {
            Label hint = new Label("Select a posting to see its analysis, skill gaps and link.");
            hint.getStyleClass().add("empty-state");
            hint.setWrapText(true);
            detail.getChildren().add(hint);
            return;
        }
        JobPosting j = row.job();
        JobMatch m = row.match();
        JobAnalysis a = app.bean(JobAnalysisService.class).getAnalysis(j);

        Label title = new Label(j.getTitle());
        title.getStyleClass().add("detail-title");
        title.setWrapText(true);
        Label meta = new Label(j.getCompany() + (j.getLocation().isEmpty() ? "" : "  -  " + j.getLocation()) + "  -  " + j.getWorkModel().toLowerCase());
        meta.getStyleClass().add("detail-meta");
        meta.setWrapText(true);
        javafx.scene.layout.HBox head = new javafx.scene.layout.HBox(12, new VBox(2, title, meta));
        HBoxGrow.grow(head.getChildren().get(0));
        if (m != null) {
            Label score = new Label(String.valueOf(m.getScore()));
            score.getStyleClass().addAll("score-badge", "chip-" + (m.getScore() >= 70 ? "success" : m.getScore() >= 45 ? "warning" : "danger"));
            head.getChildren().add(score);
        }
        detail.getChildren().add(head);

        javafx.scene.layout.FlowPane facts = new javafx.scene.layout.FlowPane(6, 6);
        if (j.getDeadline() != null) facts.getChildren().add(Ui.chip("deadline " + j.getDeadline(), "warning"));
        if (!j.getWorkTerm().isEmpty()) facts.getChildren().add(Ui.chip(j.getWorkTerm(), "neutral"));
        facts.getChildren().add(Ui.chip(j.getCompleteness() == JobPosting.Completeness.FULL ? "full description" : "summary only", j.getCompleteness() == JobPosting.Completeness.FULL ? "success" : "warning"));
        facts.getChildren().add(Ui.chip((j.getMergedSources().isEmpty() ? j.getSourceType().name() : String.join(" + ", j.getMergedSources())).toLowerCase(), "neutral"));
        if (m != null && m.isOutdated()) facts.getChildren().add(Ui.chip("score outdated: goal changed", "danger"));
        detail.getChildren().add(facts);

        if (!j.getSourceUrl().isEmpty()) detail.getChildren().add(linkButton("Open the original posting", j.getSourceUrl()));
        if (!j.getApplicationUrl().isEmpty() && !j.getApplicationUrl().equals(j.getSourceUrl())) detail.getChildren().add(linkButton("Open the application page", j.getApplicationUrl()));

        if (m != null) {
            javafx.scene.layout.FlowPane have = new javafx.scene.layout.FlowPane(6, 6);
            m.getMatchedSkills().forEach(s -> have.getChildren().add(Ui.chip(s, "success")));
            javafx.scene.layout.FlowPane gaps = new javafx.scene.layout.FlowPane(6, 6);
            m.getMissingSkills().forEach(s -> gaps.getChildren().add(Ui.chip(s, "warning")));
            VBox fit = Ui.card("Fit  (" + m.getStrategyKey().replace('_', ' ') + ")");
            fit.getChildren().add(Ui.muted("You have"));
            fit.getChildren().add(have.getChildren().isEmpty() ? Ui.muted("none of the detected skills yet") : have);
            fit.getChildren().add(Ui.muted("Gaps to close"));
            fit.getChildren().add(gaps.getChildren().isEmpty() ? Ui.muted("no gaps found") : gaps);
            m.getConstraintNotes().forEach(n -> fit.getChildren().add(Ui.chip(n, "danger")));
            m.getTransferable().forEach(t -> fit.getChildren().add(Ui.muted("Transferable: " + t.get("skill") + " - " + t.get("reason"))));
            if (!m.getExplanation().isEmpty()) {
                Label ex = new Label(m.getExplanation());
                ex.setWrapText(true);
                fit.getChildren().add(ex);
            }
            m.getSuggestions().forEach(sg -> fit.getChildren().add(Ui.muted("To learn " + sg.get("skill") + ": " + sg.get("suggestion"))));
            detail.getChildren().add(fit);
        } else {
            detail.getChildren().add(Ui.muted("Not scored yet. Use Score all, or Analyze with AI for a full explanation."));
        }
        if (a != null) {
            VBox ai = Ui.card("What the posting asks for");
            ai.getChildren().add(Ui.muted("Required: " + String.join(", ", a.getRequiredSkills())));
            if (!a.getPreferredSkills().isEmpty()) ai.getChildren().add(Ui.muted("Nice to have: " + String.join(", ", a.getPreferredSkills())));
            a.getRedFlags().forEach(f -> ai.getChildren().add(Ui.chip(f, "danger")));
            ai.getChildren().add(Ui.muted("Analysed by " + a.getModelUsed() + (a.isPartial() ? " (summary only: import the full posting for a better result)" : "")));
            detail.getChildren().add(ai);
        }
        Label desc = new Label(j.getDescription().isEmpty() ? "(no description)" : j.getDescription());
        desc.setWrapText(true);
        detail.getChildren().addAll(Ui.heading("Description"), desc);
    }

    private javafx.scene.control.Button linkButton(String text, String url) {
        javafx.scene.control.Button b = new javafx.scene.control.Button(text + "  " + (url.length() > 46 ? url.substring(0, 46) + "..." : url), Icons.box("Link"));
        b.getStyleClass().add("link-button");
        b.setOnAction(e -> app.openLink(url));
        b.setMaxWidth(Double.MAX_VALUE);
        return b;
    }

    private Row selected() { return table.getSelectionModel().getSelectedItem(); }

    private void report(ImportBatch b) {
        StringBuilder sb = new StringBuilder(b.getImportedCount() + " imported, " + b.getDuplicateCount() + " already saved, " + b.getFailedCount() + " skipped.");
        b.getResults().stream().filter(r -> "FAILED".equals(r.get("outcome"))).forEach(r -> sb.append("\n  record ").append(r.get("index")).append(": ").append(r.get("reason")));
        sb.append("\nWith a goal set, the agent triages new batches automatically; see Agent.");
        Async.info(sb.toString());
        refresh();
    }

    private void importFile() {
        File file = Ui.openFile(getScene().getWindow(), "Choose a CSV or JSON file", "Postings", "*.csv", "*.json");
        if (file == null) return;
        Async.run(this, () -> {
            try {
                String text = Files.readString(file.toPath());
                Map<String, Object> payload = new LinkedHashMap<>();
                if (file.getName().toLowerCase().endsWith(".json")) {
                    Object parsed = Json.MAPPER.readValue(text, Object.class);
                    payload.put("records", parsed instanceof Map<?, ?> m && m.get("records") != null ? m.get("records") : parsed);
                } else payload.put("csv", text);
                return imports().createBatch(app.user(), "file", payload);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("cannot read " + file + ": " + e.getMessage());
            }
        }, this::report);
    }

    private void paste() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Paste a posting");
        TextArea text = new TextArea();
        text.setPromptText("Paste the full job posting. The AI fills in the title, company, deadline and more.");
        text.setPrefRowCount(14);
        text.setPrefColumnCount(60);
        TextField url = new TextField();
        url.setPromptText("Source URL (optional)");
        dialog.getDialogPane().setContent(new VBox(8, text, url));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        importPasted(text.getText(), url.getText(), "", "");
    }

    private void importPasted(String text, String url, String title, String company) {
        Map<String, Object> payload = new LinkedHashMap<>(Map.of("text", text, "url", url));
        if (!title.isEmpty()) payload.put("title", title);
        if (!company.isEmpty()) payload.put("company", company);
        Async.run(this, () -> imports().importManual(app.user(), payload), o -> {
            Async.info((o.duplicate() ? "Already saved: " : "Imported: ") + o.job().getTitle() + " at " + o.job().getCompany()
                    + (o.warnings().isEmpty() ? "" : "\n" + String.join("\n", o.warnings())));
            refresh();
        }, t -> {
            if (t instanceof ValidationException v && v.details().get("fields") != null) askBasics(text, url, v);
            else Async.showError(t);
        });
    }

    /** The AI could not find the title or company: let the student type them and retry. */
    private void askBasics(String text, String url, ValidationException v) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Fill in the basics");
        TextField title = new TextField(), company = new TextField();
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        grid.addRow(0, new Label(v.getMessage() + ". Please enter:"));
        grid.addRow(1, new Label("Title"), title);
        grid.addRow(2, new Label("Company"), company);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) importPasted(text, url, title.getText(), company.getText());
    }

    private void scoreAll() {
        Async.run(this, () -> {
            List<UUID> ids = imports().listJobs(app.user(), null, null, null, false).stream().map(JobPosting::getId).toList();
            return app.bean(MatchingService.class).rankJobs(app.user(), ids, java.util.Set.of()).size();
        }, n -> refresh());
    }

    private void analyze() {
        Row row = selected();
        if (row == null) return;
        Async.run(this, () -> app.bean(MatchingService.class).matchJob(app.user(), row.job().getId(), true), m -> {
            refresh();
        });
    }

    private void track() {
        Row row = selected();
        if (row == null) return;
        Async.run(this, () -> app.bean(ApplicationService.class).getOrCreateForJob(app.user(), row.job().getId()), a -> navigate.accept("Tracker"));
    }

    private void dismiss() {
        Row row = selected();
        if (row == null) return;
        Async.run(this, () -> imports().dismiss(app.user(), row.job().getId(), true), this::refresh);
    }
}
