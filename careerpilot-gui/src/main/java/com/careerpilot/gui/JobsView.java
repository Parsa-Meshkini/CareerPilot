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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
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

/** F04-F08: import (file, paste, extension), normalized duplicate-free list, AI analysis and match with skill gaps. A list on the left, the selected posting on the right. */
final class JobsView extends View {
    private record Row(JobPosting job, JobMatch match) {}

    private final Consumer<String> navigate;
    private final TextField search = new TextField();
    private final TableView<Row> table = new TableView<>();
    private final VBox detail = new VBox(14);
    private int total;

    JobsView(AppContext app, Consumer<String> navigate) {
        super(app);
        this.navigate = navigate;
        search.setPromptText("Search title, company, location");
        search.setAccessibleText("Search postings");
        search.setPrefWidth(260);
        search.textProperty().addListener((o, a, b) -> refresh());

        table.getColumns().addAll(fitColumn(), roleColumn(), deadlineColumn(), flagsColumn());
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(Ui.emptyState("Jobs", "No postings yet", "Paste a posting, import a CSV or JSON file, or send one from the browser extension.",
                Ui.primary("Paste a posting", this::paste), Ui.button("Import CSV/JSON", this::importFile)));
        table.getSelectionModel().selectedItemProperty().addListener((o, a, row) -> showDetail(row));
        VBox.setVgrow(table, Priority.ALWAYS);

        detail.setPadding(new Insets(20));
        javafx.scene.control.ScrollPane scroll = Ui.scroll(detail);
        scroll.getStyleClass().add("detail-pane");
        showDetail(null);
        SplitPane split = new SplitPane(table, scroll);
        split.setDividerPositions(0.56);
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().add(split);
    }

    @Override String name() { return "Jobs"; }

    @Override String subtitle() { return total == 0 ? "Ranked by fit with your goal." : total + " saved, ranked by fit with your goal."; }

    @Override
    java.util.List<Node> actions() {
        return List.of(search, Ui.button("Import", this::importFile), Ui.button("Score all", this::scoreAll), Ui.primary("Paste a posting", this::paste));
    }

    @Override void selectFirstRow() { table.getSelectionModel().selectFirst(); }

    private JobImportService imports() { return app.bean(JobImportService.class); }

    // ---------- list ----------

    private TableColumn<Row, Row> cell(String title, double width, java.util.function.Function<Row, Node> graphic, boolean numeric) {
        TableColumn<Row, Row> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(v -> new javafx.beans.property.SimpleObjectProperty<>(v.getValue()));
        c.setCellFactory(col -> new TableCell<>() {
            @Override protected void updateItem(Row r, boolean empty) {
                super.updateItem(r, empty);
                setText(null);
                setGraphic(empty || r == null ? null : graphic.apply(r));
            }
        });
        if (numeric) c.getStyleClass().add("numeric");
        return c;
    }

    private TableColumn<Row, Row> fitColumn() {
        TableColumn<Row, Row> c = cell("Fit", 64, r -> new ScoreRing(r.match() == null ? null : r.match().getScore(), r.match() != null && r.match().isOutdated(), 34), false);
        c.setMinWidth(64);
        c.setMaxWidth(72);
        return c;
    }

    private TableColumn<Row, Row> roleColumn() {
        return cell("Role", 260, r -> {
            Label t = new Label(r.job().getTitle());
            t.getStyleClass().add("body-strong");
            t.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            Label m = new Label(r.job().getCompany() + (r.job().getLocation().isEmpty() ? "" : " \u2022 " + r.job().getLocation()));
            m.getStyleClass().add("caption");
            m.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
            VBox v = new VBox(1, t, m);
            v.setAlignment(Pos.CENTER_LEFT);
            return v;
        }, false);
    }

    private TableColumn<Row, Row> deadlineColumn() {
        TableColumn<Row, Row> c = cell("Deadline", 100, r -> {
            Label l = new Label(r.job().getDeadline() == null ? "\u2013" : r.job().getDeadline().toString());
            l.getStyleClass().addAll("mono", "muted");
            return l;
        }, false);
        c.setMinWidth(96);
        return c;
    }

    private TableColumn<Row, Row> flagsColumn() {
        TableColumn<Row, Row> c = cell("Status", 130, r -> {
            HBox h = new HBox(4);
            h.setAlignment(Pos.CENTER_LEFT);
            if (r.job().isShortlisted()) h.getChildren().add(Ui.chip("Shortlisted", "success"));
            else if (r.job().getCompleteness() != JobPosting.Completeness.FULL) h.getChildren().add(Ui.chip("Summary only", "warning"));
            return h;
        }, false);
        c.setMinWidth(110);
        return c;
    }

    @Override
    void refresh() {
        String q = search.getText();
        Async.run(this, () -> {
            MatchingService matching = app.bean(MatchingService.class);
            List<Row> rows = new ArrayList<>();
            for (JobPosting j : imports().listJobs(app.user(), q, null, null, false)) rows.add(new Row(j, matching.getMatch(j)));
            rows.sort((a, b) -> Integer.compare(b.match() == null ? -1 : b.match().getScore(), a.match() == null ? -1 : a.match().getScore()));
            return rows;
        }, rows -> {
            Row keep = selected();
            Ui.fill(table, rows);
            total = rows.size();
            if (keep != null) rows.stream().filter(r -> r.job().getId().equals(keep.job().getId())).findFirst().ifPresent(r -> table.getSelectionModel().select(r));
            if (!q.isBlank() && rows.isEmpty())
                table.setPlaceholder(Ui.emptyState("Search", "No match for \u201c" + q + "\u201d", "Try a company name or a shorter title."));
            else table.setPlaceholder(Ui.emptyState("Jobs", "No postings yet", "Paste a posting, import a CSV or JSON file, or send one from the browser extension.",
                    Ui.primary("Paste a posting", this::paste), Ui.button("Import CSV/JSON", this::importFile)));
        });
    }

    // ---------- detail ----------

    private void showDetail(Row row) {
        detail.getChildren().clear();
        if (row == null) {
            VBox empty = Ui.emptyState("Jobs", "Pick a posting", "Select one on the left to see how well it fits, which skills are missing, and the original link.");
            empty.setPrefHeight(360);
            detail.getChildren().add(empty);
            return;
        }
        JobPosting j = row.job();
        JobMatch m = row.match();
        JobAnalysis a = app.bean(JobAnalysisService.class).getAnalysis(j);
        Integer score = m == null ? null : m.getScore();

        Label title = new Label(j.getTitle());
        title.getStyleClass().add("detail-title");
        title.setWrapText(true);
        Label meta = new Label(j.getCompany() + (j.getLocation().isEmpty() ? "" : " \u2022 " + j.getLocation()) + (j.getWorkModel().isEmpty() ? "" : " \u2022 " + j.getWorkModel().toLowerCase()));
        meta.getStyleClass().add("detail-meta");
        meta.setWrapText(true);
        Label fit = new Label(ScoreRing.word(score));
        fit.getStyleClass().addAll("fit-word", ScoreRing.band(score));
        VBox titles = new VBox(3, title, meta, fit);
        HBox.setHgrow(titles, Priority.ALWAYS);
        HBox head = new HBox(16, new ScoreRing(score, m != null && m.isOutdated(), 72), titles);
        head.setAlignment(Pos.CENTER_LEFT);
        detail.getChildren().add(head);

        FlowPane actions = new FlowPane(8, 8, Ui.primary(m == null ? "Score with AI" : "Analyze with AI", this::analyze), Ui.button("Track application", this::track), quiet("Dismiss", this::dismiss));
        detail.getChildren().add(actions);

        FlowPane facts = new FlowPane(6, 6);
        if (j.getDeadline() != null) facts.getChildren().add(Ui.chip("Deadline " + j.getDeadline(), "warning"));
        if (!j.getWorkTerm().isEmpty()) facts.getChildren().add(Ui.chip(j.getWorkTerm(), "neutral"));
        facts.getChildren().add(Ui.chip(j.getCompleteness() == JobPosting.Completeness.FULL ? "Full description" : "Summary only", j.getCompleteness() == JobPosting.Completeness.FULL ? "success" : "warning"));
        facts.getChildren().add(Ui.chip((j.getMergedSources().isEmpty() ? j.getSourceType().name() : String.join(" + ", j.getMergedSources())).toLowerCase(), "neutral"));
        if (m != null && m.isOutdated()) facts.getChildren().add(Ui.chip("Score outdated: your goal changed", "danger"));
        detail.getChildren().add(facts);

        if (!j.getSourceUrl().isEmpty()) detail.getChildren().add(linkButton("Open the original posting", j.getSourceUrl()));
        if (!j.getApplicationUrl().isEmpty() && !j.getApplicationUrl().equals(j.getSourceUrl())) detail.getChildren().add(linkButton("Open the application page", j.getApplicationUrl()));

        if (m != null) {
            FlowPane have = new FlowPane(6, 6);
            m.getMatchedSkills().forEach(s -> have.getChildren().add(Ui.chip(s, "success")));
            FlowPane gaps = new FlowPane(6, 6);
            m.getMissingSkills().forEach(s -> gaps.getChildren().add(Ui.chip(s, "warning")));
            VBox fitCard = Ui.card("How you fit  (" + m.getStrategyKey().replace('_', ' ') + ")");
            fitCard.getChildren().add(section("You have"));
            fitCard.getChildren().add(have.getChildren().isEmpty() ? Ui.muted("None of the detected skills yet.") : have);
            fitCard.getChildren().add(section("Gaps to close"));
            fitCard.getChildren().add(gaps.getChildren().isEmpty() ? Ui.muted("No gaps found.") : gaps);
            m.getConstraintNotes().forEach(n -> fitCard.getChildren().add(Ui.chip(n, "danger")));
            m.getTransferable().forEach(t -> fitCard.getChildren().add(Ui.muted("Transferable: " + t.get("skill") + " \u2013 " + t.get("reason"))));
            if (!m.getExplanation().isEmpty()) {
                Label ex = new Label(m.getExplanation());
                ex.setWrapText(true);
                fitCard.getChildren().add(ex);
            }
            m.getSuggestions().forEach(sg -> fitCard.getChildren().add(Ui.muted("To learn " + sg.get("skill") + ": " + sg.get("suggestion"))));
            detail.getChildren().add(fitCard);
        } else {
            detail.getChildren().add(Ui.card(null, Ui.muted("Not scored yet. Use Score all, or Score with AI for a full explanation.")));
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
        detail.getChildren().addAll(section("Description"), desc);
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-label");
        return l;
    }

    private static Button quiet(String text, Runnable action) {
        Button b = Ui.button(text, action);
        b.getStyleClass().add("quiet");
        return b;
    }

    private Button linkButton(String text, String url) {
        Button b = new Button(text + "  " + (url.length() > 46 ? url.substring(0, 46) + "..." : url), Icons.box("Link"));
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
        Ui.style(dialog);
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
        Ui.style(dialog);
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
