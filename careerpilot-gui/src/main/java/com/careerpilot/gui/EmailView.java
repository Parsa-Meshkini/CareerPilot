package com.careerpilot.gui;

import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.integrations.DetectedEmailEvent;
import com.careerpilot.integrations.EmailAccount;
import com.careerpilot.integrations.EmailSyncService;
import com.careerpilot.integrations.EmlFileAdapter;
import com.careerpilot.integrations.EventTypes;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** F14: detect interview, rejection and offer emails from .eml files or the demo inbox, then confirm or dismiss. */
final class EmailView extends View {
    private final TableView<DetectedEmailEvent> table = Ui.table(Ui.col("Status", 130, DetectedEmailEvent::getReviewStatus), Ui.col("Type", 170, DetectedEmailEvent::getEventType),
            Ui.col("Conf.", 50, e -> String.format("%.2f", e.getConfidence())), Ui.col("By", 70, DetectedEmailEvent::getClassifier),
            Ui.col("Subject", 260, e -> e.getEmail().getSubject()),
            Ui.col("Application", 200, e -> e.getApplication() == null ? "(no match)" : e.getApplication().getJob().getTitle() + " at " + e.getApplication().getJob().getCompany()));
    private final CheckBox all = new CheckBox("Show resolved too");
    private final TextArea body = Ui.readOnlyText();
    private final ChoiceBox<Application> appChoice = new ChoiceBox<>();
    private final ChoiceBox<String> typeChoice = new ChoiceBox<>();
    private final Label status = Ui.muted("");

    EmailView(AppContext app) {
        super(app);
        typeChoice.getItems().addAll(EventTypes.ALL.stream().filter(t -> !t.equals(EventTypes.NOT_RELEVANT)).toList());
        appChoice.setPrefWidth(280);
        typeChoice.setPrefWidth(280);
        appChoice.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Application a) { return a == null ? "" : a.getJob().getTitle() + " at " + a.getJob().getCompany(); }
            @Override public Application fromString(String s) { return null; }
        });
        all.setOnAction(e -> refresh());
        table.getSelectionModel().selectedItemProperty().addListener((o, a, e) -> show(e));
        VBox detail = new VBox(8, body, Ui.row(Ui.label("Application"), appChoice), Ui.row(Ui.label("Event type"), typeChoice),
                Ui.row(Ui.primary("Confirm", this::confirm), Ui.button("Dismiss", this::dismiss)));
        SplitPane split = new SplitPane(table, detail);
        split.setDividerPositions(0.6);
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().addAll(Ui.title("Email"),
                Ui.muted("Emails are matched to your applications. Confident matches update the tracker automatically; the rest wait here for you. Gmail sign-in is not part of this build: use exported .eml files or the demo inbox."),
                Ui.row(Ui.primary("Upload .eml files", this::upload), Ui.button("Sync demo inbox", this::syncMock), Ui.spacer(), all), status, split);
    }

    @Override String name() { return "Email"; }

    private EmailSyncService svc() { return app.bean(EmailSyncService.class); }

    @Override
    void refresh() {
        Async.run(null, () -> new Object[]{svc().listEvents(app.user(), all.isSelected() ? null : DetectedEmailEvent.NEEDS_REVIEW),
                app.bean(ApplicationService.class).listApplications(app.user(), null)}, d -> {
            @SuppressWarnings("unchecked") List<DetectedEmailEvent> events = (List<DetectedEmailEvent>) d[0];
            @SuppressWarnings("unchecked") List<Application> apps = (List<Application>) d[1];
            Ui.fill(table, events);
            appChoice.getItems().setAll(apps);
        });
    }

    private void show(DetectedEmailEvent e) {
        if (e == null) {
            body.clear();
            return;
        }
        StringBuilder sb = new StringBuilder("From: " + e.getEmail().getSender() + "\nSubject: " + e.getEmail().getSubject() + "\n\n" + e.getEmail().getExcerpt() + "\n\n");
        sb.append("Detected ").append(e.getEventType()).append(" (").append(String.format("%.2f", e.getConfidence())).append(") by ").append(e.getClassifier()).append('\n');
        if (!e.getExtractedData().isEmpty()) sb.append("Details: ").append(e.getExtractedData()).append('\n');
        e.getCandidates().forEach(c -> sb.append("  candidate: ").append(c.get("label")).append(" (").append(c.get("score")).append(")\n"));
        body.setText(sb.toString());
        typeChoice.setValue(e.getEventType());
        appChoice.getItems().stream().filter(a -> e.getApplication() != null && a.getId().equals(e.getApplication().getId())).findFirst().ifPresent(appChoice::setValue);
    }

    private void summary(Map<String, Object> s) {
        status.setText("Fetched " + s.get("fetched") + ": " + s.get("new") + " new, " + s.get("duplicates") + " duplicate, " + s.get("not_relevant")
                + " not job related, " + s.get("published") + " applied automatically, " + s.get("needs_review") + " waiting for you.");
        refresh();
    }

    private void upload() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Choose exported emails");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("Email files", "*.eml"));
        List<File> files = fc.showOpenMultipleDialog(getScene().getWindow());
        if (files == null || files.isEmpty()) return;
        Async.run(this, () -> {
            List<EmlFileAdapter.Upload> uploads = new ArrayList<>();
            for (File f : files) {
                try {
                    uploads.add(new EmlFileAdapter.Upload(f.getName(), Files.readAllBytes(f.toPath())));
                } catch (java.io.IOException e) {
                    throw new IllegalArgumentException("cannot read " + f);
                }
            }
            return svc().importEmlFiles(app.user(), uploads);
        }, this::summary);
    }

    private void syncMock() {
        Async.run(this, () -> {
            EmailAccount account = svc().connectMock(app.user());
            return svc().syncAccount(app.user(), account.getId());
        }, this::summary);
    }

    private void confirm() {
        DetectedEmailEvent e = table.getSelectionModel().getSelectedItem();
        if (e == null) return;
        Application chosen = appChoice.getValue();
        Async.run(this, () -> svc().confirmEvent(app.user(), e.getId(), chosen == null ? null : chosen.getId(), typeChoice.getValue()), x -> refresh());
    }

    private void dismiss() {
        DetectedEmailEvent e = table.getSelectionModel().getSelectedItem();
        if (e != null) Async.run(this, () -> svc().dismissEvent(app.user(), e.getId()), x -> refresh());
    }
}
