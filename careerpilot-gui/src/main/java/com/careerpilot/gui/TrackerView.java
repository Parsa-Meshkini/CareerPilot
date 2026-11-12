package com.careerpilot.gui;

import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.applications.StatusChange;
import com.careerpilot.documents.ApplicationPackage;
import com.careerpilot.documents.DocumentService;
import com.careerpilot.documents.GeneratedDocument;
import com.careerpilot.documents.PackageAssembler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** F13 lifecycle tracking with undo shown as a pipeline board; F10-F12 documents and the application package for the selected application. */
final class TrackerView extends View {
    private static final List<String> LANES = List.of("SAVED", "PREPARED", "APPLIED", "INTERVIEW", "OFFER");

    private final HBox board = new HBox(12);
    private final ScrollPane boardScroll = new ScrollPane(board);
    private final TextField search = new TextField();
    private final Label heading = new Label();
    private final HBox headState = new HBox(8);
    private final FlowPane actions = new FlowPane(8, 8);
    private final ListView<String> timeline = new ListView<>(), docs = new ListView<>();
    private final FlowPane interviews = new FlowPane(6, 6);
    private final TextArea notes = new TextArea();
    private final VBox detail = new VBox(14);
    private final VBox detailBody = new VBox(14);
    private List<Application> all = List.of();
    private UUID selectedId;
    private Application selected;
    private StatusChange lastLive;

    TrackerView(AppContext app) {
        super(app);
        search.setPromptText("Search title or company");
        search.setAccessibleText("Search applications");
        search.setPrefWidth(240);
        search.textProperty().addListener((o, a, b) -> render());

        board.setPadding(new Insets(0, 0, 4, 0));
        boardScroll.setFitToHeight(true);
        boardScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        boardScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        boardScroll.setPrefHeight(236);
        boardScroll.setMinHeight(236);
        boardScroll.viewportBoundsProperty().addListener((o, a, b) -> board.setMinWidth(b.getWidth()));

        heading.getStyleClass().add("heading");
        heading.setWrapText(true);
        headState.setAlignment(Pos.CENTER_LEFT);
        timeline.setPlaceholder(muted("No changes yet. Each state change you make is recorded here."));
        docs.setPlaceholder(muted("No documents yet. Generate a resume or cover letter."));
        timeline.setPrefHeight(132);
        docs.setPrefHeight(108);
        notes.setPromptText("Notes about this application");
        notes.setAccessibleText("Notes");
        notes.setPrefRowCount(4);
        notes.setWrapText(true);

        VBox left = new VBox(14, section("Next step"), actions, section("Timeline"), timeline, quiet("Undo last change", this::undo));
        VBox right = new VBox(14, section("Documents"), docs,
                new FlowPane(8, 8, Ui.button("Generate resume", () -> generate("resume")), Ui.button("Generate cover letter", () -> generate("cover_letter")),
                        Ui.primary("Build package (ZIP)", this::buildPackage)),
                section("Interviews"), interviews, section("Notes"), notes, Ui.button("Save notes", this::saveNotes));
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        left.setPrefWidth(300);
        right.setPrefWidth(300);
        detailBody.getChildren().addAll(new HBox(8, heading, headState), new HBox(24, left, right));
        detail.getStyleClass().add("detail-pane");
        detail.setPadding(new Insets(20));
        detail.getChildren().add(detailBody);
        ScrollPane detailScroll = Ui.scroll(detail);
        detailScroll.setStyle("-fx-background-color: transparent;");
        getChildren().addAll(boardScroll, detailScroll);
        showDetail(null);
    }

    @Override String name() { return "Tracker"; }

    @Override String subtitle() { return "You submit on the employer's site, then mark it submitted here."; }

    @Override List<Node> actions() { return List.of(search); }

    @Override void selectFirstRow() {
        for (String lane : LANES) {
            for (Application a : all) if (laneOf(a).equals(lane)) { select(a); return; }
        }
        if (!all.isEmpty()) select(all.get(0));
    }

    private ApplicationService svc() { return app.bean(ApplicationService.class); }

    private static Label muted(String text) {
        Label l = Ui.muted(text);
        return l;
    }

    private static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-label");
        return l;
    }

    private static Button quiet(String text, Runnable r) {
        Button b = Ui.button(text, r);
        b.getStyleClass().add("quiet");
        return b;
    }

    private static String laneOf(Application a) { return LANES.contains(a.getStateName()) ? a.getStateName() : "CLOSED"; }

    private static String pretty(String state) {
        String t = state.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    @Override
    void refresh() {
        Async.run(this, () -> svc().listApplications(app.user(), null), list -> {
            all = list;
            render();
            if (selectedId != null) list.stream().filter(a -> a.getId().equals(selectedId)).findFirst().ifPresent(this::showDetail);
        });
    }

    /** Builds one lane per state; applications are cards you can open with the pointer or the keyboard. */
    private void render() {
        String q = search.getText() == null ? "" : search.getText().trim().toLowerCase();
        Map<String, List<Application>> byLane = new LinkedHashMap<>();
        LANES.forEach(l -> byLane.put(l, new ArrayList<>()));
        byLane.put("CLOSED", new ArrayList<>());
        for (Application a : all) {
            if (!q.isEmpty() && !(a.getJob().getTitle() + " " + a.getJob().getCompany()).toLowerCase().contains(q)) continue;
            byLane.get(laneOf(a)).add(a);
        }
        board.getChildren().clear();
        if (all.isEmpty()) {
            VBox empty = Ui.emptyState("Tracker", "No applications yet", "Track a posting from Jobs and it lands in Saved. Move it forward as you prepare and apply.",
                    Ui.primary("Open Jobs", () -> { }));
            empty.getChildren().remove(empty.getChildren().size() - 1);
            HBox.setHgrow(empty, Priority.ALWAYS);
            board.getChildren().add(empty);
            return;
        }
        byLane.forEach((lane, apps) -> {
            Label t = new Label(lane.equals("CLOSED") ? "Closed" : pretty(lane));
            t.getStyleClass().add("lane-title");
            Label count = new Label(String.valueOf(apps.size()));
            count.getStyleClass().add("count-pill");
            HBox head = new HBox(8, t, count);
            head.setAlignment(Pos.CENTER_LEFT);
            VBox cards = new VBox(8);
            if (apps.isEmpty()) {
                Label none = new Label("Nothing here");
                none.getStyleClass().add("lane-empty");
                cards.getChildren().add(none);
            }
            apps.forEach(a -> cards.getChildren().add(card(a)));
            ScrollPane sp = Ui.scroll(cards);
            Region accent = new Region();
            accent.getStyleClass().addAll("lane-accent", "tone-" + switch (lane) {
                case "SAVED" -> "neutral"; case "PREPARED" -> "blue"; case "APPLIED" -> "violet"; case "INTERVIEW" -> "teal"; case "OFFER" -> "green"; default -> "red"; });
            VBox lanePane = new VBox(8, accent, head, sp);
            lanePane.getStyleClass().add("lane");
            lanePane.setPrefWidth(150);
            lanePane.setMinWidth(140);
            HBox.setHgrow(lanePane, Priority.ALWAYS);
            board.getChildren().add(lanePane);
        });
    }

    private Button card(Application a) {
        Label title = new Label(a.getJob().getTitle());
        title.getStyleClass().add("body-strong");
        title.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        title.setMinWidth(0);
        Label company = new Label(a.getJob().getCompany());
        company.getStyleClass().add("caption");
        VBox box = new VBox(3, title, company);
        if (a.getJob().getDeadline() != null) box.getChildren().add(Ui.chip("Due " + a.getJob().getDeadline().format(java.time.format.DateTimeFormatter.ofPattern("MMM d")), "warning"));
        Button b = new Button("", box);
        b.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        b.getStyleClass().add("lane-card");
        if (a.getId().equals(selectedId)) b.getStyleClass().add("selected");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setMaxHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        b.setAccessibleText(a.getJob().getTitle() + " at " + a.getJob().getCompany() + ", " + pretty(a.getStateName()));
        b.setOnAction(e -> select(a));
        return b;
    }

    private void select(Application a) {
        selectedId = a.getId();
        render();
        showDetail(a);
    }

    private void showDetail(Application a) {
        selected = a;
        actions.getChildren().clear();
        timeline.getItems().clear();
        docs.getItems().clear();
        interviews.getChildren().clear();
        detailBody.setVisible(a != null);
        detailBody.setManaged(a != null);
        detail.getChildren().removeIf(n -> n != detailBody);
        if (a == null) {
            VBox empty = Ui.emptyState("Tracker", "Pick an application", "Select a card to see what you can do next, its timeline and its documents.");
            detail.getChildren().add(empty);
            return;
        }
        Async.run(null, () -> {
            Application fresh = svc().get(app.user(), a.getId());
            return new Object[]{fresh, svc().timeline(app.user(), a.getId()), app.bean(DocumentService.class).list(app.user(), a.getId()), svc().interviews(app.user(), a.getId())};
        }, d -> {
            Application fresh = (Application) d[0];
            @SuppressWarnings("unchecked") List<StatusChange> changes = (List<StatusChange>) d[1];
            @SuppressWarnings("unchecked") List<GeneratedDocument> documents = (List<GeneratedDocument>) d[2];
            heading.setText(fresh.getJob().getTitle() + " at " + fresh.getJob().getCompany());
            headState.getChildren().setAll(Ui.chip(pretty(fresh.getStateName()), Ui.stateKind(fresh.getStateName())));
            for (String action : fresh.allowedActions()) {
                String label = action.equals("submit") ? "Mark as submitted" : pretty(action);
                Button b = Ui.button(label, () -> act(fresh, action));
                if (action.equals("submit")) b.getStyleClass().add("primary");
                actions.getChildren().add(b);
            }
            if (fresh.allowedActions().isEmpty()) actions.getChildren().add(muted("Final state: no further actions."));
            lastLive = null;
            for (StatusChange c : changes) {
                timeline.getItems().add(pretty(c.getFromState()) + " → " + pretty(c.getToState()) + "  •  " + c.getAction().replace('_', ' ') + " by " + c.getSource().toString().toLowerCase()
                        + (c.getReason().isEmpty() ? "" : " – " + c.getReason()) + (c.isReverted() ? " (reverted)" : ""));
                lastLive = c;
            }
            documents.forEach(doc -> docs.getItems().add(pretty(doc.getDocType()) + " v" + doc.getVersion() + "  •  " + doc.getVerificationStatus().toLowerCase().replace('_', ' ')
                    + (doc.isStale() ? "  (stale: a cited fact was deleted)" : "")));
            ((List<?>) d[3]).forEach(i -> interviews.getChildren().add(Ui.chip("Round " + ((com.careerpilot.applications.Interview) i).getRound(), "info")));
            if (interviews.getChildren().isEmpty()) interviews.getChildren().add(muted("None scheduled."));
            notes.setText(fresh.getNotes());
        });
    }

    private void act(Application a, String action) {
        Async.run(this, () -> svc().transition(app.user(), a.getId(), action), c -> refresh());
    }

    private void undo() {
        if (selected == null || lastLive == null) return;
        StatusChange change = lastLive;
        Async.run(this, () -> svc().undoTransition(app.user(), change.getId()), x -> refresh());
    }

    private void saveNotes() {
        Application a = selected;
        if (a != null) Async.run(this, () -> svc().updateNotes(app.user(), a.getId(), notes.getText()), x -> Toast.show("Notes saved."));
    }

    private void generate(String kind) {
        Application a = selected;
        if (a == null) return;
        Async.run(this, () -> app.bean(DocumentService.class).generate(app.user(), a.getId(), kind, null, Map.of("tone", "formal")), (GeneratedDocument d) -> {
            Async.info(d.getDocType() + " v" + d.getVersion() + ": " + d.getVerificationStatus()
                    + (GeneratedDocument.NEEDS_REVIEW.equals(d.getVerificationStatus()) ? "\nUnsupported lines were removed. Review before using." : "\nEvery claim is backed by your verified facts."));
            showDetail(selected);
        });
    }

    private void buildPackage() {
        Application a = selected;
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
