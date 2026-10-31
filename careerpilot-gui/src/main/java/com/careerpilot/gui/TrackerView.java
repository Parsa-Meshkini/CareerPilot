package com.careerpilot.gui;

import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.applications.StatusChange;
import com.careerpilot.documents.ApplicationPackage;
import com.careerpilot.documents.DocumentService;
import com.careerpilot.documents.GeneratedDocument;
import com.careerpilot.documents.PackageAssembler;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/** F13 lifecycle tracking with undo; F10-F12 documents and the application package for the selected application. */
final class TrackerView extends View {
    private final TableView<Application> table = Ui.table(Ui.col("State", 100, Application::getStateName), Ui.col("Title", 240, a -> a.getJob().getTitle()),
            Ui.col("Company", 150, a -> a.getJob().getCompany()), Ui.col("Deadline", 90, a -> a.getJob().getDeadline() == null ? "" : a.getJob().getDeadline().toString()));
    private final Label heading = Ui.heading("Select an application");
    private final FlowPane actions = new FlowPane(8, 8);
    private final ListView<String> timeline = new ListView<>(), docs = new ListView<>();
    private final TextArea notes = new TextArea();
    private StatusChange lastLive;

    TrackerView(AppContext app) {
        super(app);
        table.getSelectionModel().selectedItemProperty().addListener((o, a, sel) -> showDetail(sel));
        notes.setPromptText("Notes");
        notes.setPrefRowCount(3);
        timeline.setPrefHeight(150);
        docs.setPrefHeight(110);
        VBox detail = new VBox(8, heading, Ui.muted("Only the actions that are legal from this state are offered."), actions, Ui.heading("Timeline"), timeline,
                Ui.row(Ui.button("Undo last change", this::undo)), Ui.heading("Documents"), docs,
                Ui.row(Ui.button("Generate resume", () -> generate("resume")), Ui.button("Generate cover letter", () -> generate("cover_letter")),
                        Ui.primary("Build package (ZIP)", this::buildPackage)), notes, Ui.button("Save notes", this::saveNotes));
        SplitPane split = new SplitPane(table, detail);
        split.setDividerPositions(0.5);
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().addAll(Ui.title("Tracker"), Ui.muted("CareerPilot never submits an application. You submit on the employer's site, then mark it submitted here."), split);
    }

    @Override String name() { return "Tracker"; }

    private ApplicationService svc() { return app.bean(ApplicationService.class); }

    private Application selected() { return table.getSelectionModel().getSelectedItem(); }

    @Override
    void refresh() {
        Application keep = selected();
        Async.run(null, () -> svc().listApplications(app.user(), null), list -> {
            Ui.fill(table, list);
            if (keep != null) list.stream().filter(a -> a.getId().equals(keep.getId())).findFirst().ifPresent(a -> table.getSelectionModel().select(a));
        });
    }

    private void showDetail(Application a) {
        actions.getChildren().clear();
        timeline.getItems().clear();
        docs.getItems().clear();
        if (a == null) {
            heading.setText("Select an application");
            return;
        }
        Async.run(null, () -> {
            Application fresh = svc().get(app.user(), a.getId());
            return new Object[]{fresh, svc().timeline(app.user(), a.getId()), app.bean(DocumentService.class).list(app.user(), a.getId()), svc().interviews(app.user(), a.getId())};
        }, d -> {
            Application fresh = (Application) d[0];
            @SuppressWarnings("unchecked") List<StatusChange> changes = (List<StatusChange>) d[1];
            @SuppressWarnings("unchecked") List<GeneratedDocument> documents = (List<GeneratedDocument>) d[2];
            heading.setText(fresh.getJob().getTitle() + " at " + fresh.getJob().getCompany() + "  [" + fresh.getStateName() + "]");
            for (String action : fresh.allowedActions())
                actions.getChildren().add(Ui.button(action.replace('_', ' '), () -> act(fresh, action)));
            if (fresh.allowedActions().isEmpty()) actions.getChildren().add(Ui.muted("Final state: no further actions."));
            lastLive = null;
            for (StatusChange c : changes) {
                timeline.getItems().add(c.getFromState() + " -> " + c.getToState() + "  [" + c.getSource() + "] " + c.getAction() + (c.getReason().isEmpty() ? "" : " - " + c.getReason()) + (c.isReverted() ? " (reverted)" : ""));
                lastLive = c;
            }
            documents.forEach(doc -> docs.getItems().add(doc.getDocType() + " v" + doc.getVersion() + "  " + doc.getVerificationStatus() + (doc.isStale() ? "  (stale: a cited fact was deleted)" : "")));
            ((List<?>) d[3]).forEach(i -> docs.getItems().add("interview round " + ((com.careerpilot.applications.Interview) i).getRound()));
            notes.setText(fresh.getNotes());
        });
    }

    private void act(Application a, String action) {
        Async.run(this, () -> svc().transition(app.user(), a.getId(), action), c -> refresh());
    }

    private void undo() {
        Application a = selected();
        if (a == null || lastLive == null) return;
        StatusChange change = lastLive;
        Async.run(this, () -> svc().undoTransition(app.user(), change.getId()), x -> refresh());
    }

    private void saveNotes() {
        Application a = selected();
        if (a != null) Async.run(this, () -> svc().updateNotes(app.user(), a.getId(), notes.getText()), x -> { });
    }

    private void generate(String kind) {
        Application a = selected();
        if (a == null) return;
        Async.run(this, () -> app.bean(DocumentService.class).generate(app.user(), a.getId(), kind, null, Map.of("tone", "formal")), (GeneratedDocument d) -> {
            Async.info(d.getDocType() + " v" + d.getVersion() + ": " + d.getVerificationStatus()
                    + (GeneratedDocument.NEEDS_REVIEW.equals(d.getVerificationStatus()) ? "\nUnsupported lines were removed. Review before using." : "\nEvery claim is backed by your verified facts."));
            showDetail(selected());
        });
    }

    private void buildPackage() {
        Application a = selected();
        if (a == null) return;
        Async.run(this, () -> app.bean(PackageAssembler.class).buildPackage(app.user(), a.getId()), (ApplicationPackage p) -> {
            File out = Ui.saveFile(getScene().getWindow(), "Save the application package", p.getFolderName() + ".zip");
            if (out != null) {
                try {
                    Files.write(out.toPath(), app.bean(PackageAssembler.class).download(app.user(), p.getId()));
                } catch (java.io.IOException e) {
                    Async.showError(e);
                }
            }
            refresh();
        });
    }
}
