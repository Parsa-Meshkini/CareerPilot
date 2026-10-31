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
            Ui.col("Score", 60, r -> r.match() == null ? "-" : r.match().getScore() + (r.match().isOutdated() ? "*" : "")),
            Ui.col("", 28, r -> r.job().isShortlisted() ? "★" : ""),
            Ui.col("Title", 250, r -> r.job().getTitle()), Ui.col("Company", 150, r -> r.job().getCompany()),
            Ui.col("Deadline", 90, r -> r.job().getDeadline() == null ? "" : r.job().getDeadline().toString()),
            Ui.col("Detail", 70, r -> r.job().getCompleteness().name().toLowerCase()));
    private final TextArea detail = Ui.readOnlyText();
    private final Label status = Ui.muted("");

    JobsView(AppContext app, Consumer<String> navigate) {
        super(app);
        this.navigate = navigate;
        search.setPromptText("Search title, company, location");
        search.textProperty().addListener((o, a, b) -> refresh());
        table.getSelectionModel().selectedItemProperty().addListener((o, a, row) -> showDetail(row));
        SplitPane split = new SplitPane(table, new VBox(8, detail, Ui.row(Ui.primary("Analyze with AI", this::analyze), Ui.button("Track application", this::track),
                Ui.button("Dismiss", this::dismiss))));
        split.setDividerPositions(0.55);
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
        if (row == null) {
            detail.clear();
            return;
        }
        JobPosting j = row.job();
        StringBuilder sb = new StringBuilder();
        sb.append(j.getTitle()).append(" at ").append(j.getCompany()).append('\n')
                .append(j.getLocation()).append(" (").append(j.getWorkModel()).append(")   Term: ").append(j.getWorkTerm())
                .append("   Deadline: ").append(j.getDeadline() == null ? "-" : j.getDeadline()).append('\n')
                .append("Sources: ").append(j.getMergedSources().isEmpty() ? j.getSourceType() : j.getMergedSources())
                .append("   Detail: ").append(j.getCompleteness()).append("\n\n");
        JobAnalysis a = app.bean(JobAnalysisService.class).getAnalysis(j);
        if (a != null) {
            sb.append("Requires: ").append(String.join(", ", a.getRequiredSkills())).append('\n')
                    .append("Nice to have: ").append(String.join(", ", a.getPreferredSkills())).append('\n');
            if (!a.getRedFlags().isEmpty()) sb.append("Red flags: ").append(String.join("; ", a.getRedFlags())).append('\n');
            sb.append("Analysed by ").append(a.getModelUsed()).append(a.isPartial() ? " (summary only: import the full posting for a better result)" : "").append("\n\n");
        }
        JobMatch m = row.match();
        if (m != null) {
            sb.append("Match ").append(m.getScore()).append("/100 (").append(m.getStrategyKey()).append(")\n")
                    .append("  matched: ").append(String.join(", ", m.getMatchedSkills())).append('\n')
                    .append("  missing: ").append(String.join(", ", m.getMissingSkills())).append('\n');
            m.getConstraintNotes().forEach(n -> sb.append("  note: ").append(n).append('\n'));
            m.getTransferable().forEach(t -> sb.append("  transferable ").append(t.get("skill")).append(": ").append(t.get("reason")).append('\n'));
            if (!m.getExplanation().isEmpty()) sb.append('\n').append(m.getExplanation()).append('\n');
            m.getSuggestions().forEach(s -> sb.append("  learn ").append(s.get("skill")).append(": ").append(s.get("suggestion")).append('\n'));
            sb.append('\n');
        }
        sb.append(j.getDescription());
        detail.setText(sb.toString());
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
