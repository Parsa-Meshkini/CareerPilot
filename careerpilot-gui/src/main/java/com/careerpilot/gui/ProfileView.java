package com.careerpilot.gui;

import com.careerpilot.candidates.CandidateProfile;
import com.careerpilot.candidates.CandidateService;
import com.careerpilot.candidates.CareerGoal;
import com.careerpilot.candidates.FactType;
import com.careerpilot.candidates.GoalType;
import com.careerpilot.candidates.ProfileFact;
import com.careerpilot.candidates.UploadedResume;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
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

/** F01 profile and verified facts, F02 resume import, F03 career goal and strategy. */
final class ProfileView extends View {
    private final TextField fullName = new TextField(), headline = new TextField(), location = new TextField(), email = new TextField(), phone = new TextField();
    private final TextArea summary = new TextArea();
    private final ChoiceBox<GoalType> goalType = new ChoiceBox<>();
    private final TextField term = new TextField(), roles = new TextField(), locations = new TextField();
    private final ChoiceBox<String> workModel = new ChoiceBox<>();
    private final Spinner<Integer> minScore = new Spinner<>(0, 100, 60, 5);
    private final Label hints = Ui.muted(""), strategy = Ui.muted("");
    private final CheckBox pendingOnly = new CheckBox("Only facts waiting for review");
    private final TableView<ProfileFact> facts = Ui.table(Ui.col("Type", 100, f -> f.getFactType().name()),
            Ui.col("State", 130, f -> f.isVerified() ? "verified" : (f.isNeedsReview() ? "needs a fix" : "to review")),
            Ui.col("Fact", 420, ProfileFact::toContextText), Ui.col("Note", 160, ProfileFact::getReviewNote));

    ProfileView(AppContext app) {
        super(app);
        goalType.getItems().addAll(GoalType.values());
        workModel.getItems().addAll("ANY", "ONSITE", "HYBRID", "REMOTE");
        summary.setPrefRowCount(3);
        summary.setWrapText(true);
        facts.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        pendingOnly.setOnAction(e -> loadFacts());

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(6);
        form.getColumnConstraints().add(new javafx.scene.layout.ColumnConstraints(90, 90, 90));
        int r = 0;
        for (Object[] row : new Object[][]{{"Full name", fullName}, {"Headline", headline}, {"Location", location}, {"Email", email}, {"Phone", phone}}) {
            form.addRow(r++, Ui.label((String) row[0]), (javafx.scene.Node) row[1]);
            GridPane.setHgrow((javafx.scene.Node) row[1], Priority.ALWAYS);
        }
        form.addRow(r, Ui.label("Summary"), summary);

        GridPane goal = new GridPane();
        goal.setHgap(8);
        goal.setVgap(6);
        goal.getColumnConstraints().add(new javafx.scene.layout.ColumnConstraints(110, 110, 110));
        goal.addRow(0, Ui.label("Goal"), goalType);
        goal.addRow(1, Ui.label("Work term"), term);
        goal.addRow(2, Ui.label("Target roles"), roles);
        goal.addRow(3, Ui.label("Locations"), locations);
        goal.addRow(4, Ui.label("Work model"), workModel);
        goal.addRow(5, Ui.label("Min match score"), minScore);
        term.setPromptText("e.g. Summer 2027");
        roles.setPromptText("comma separated");
        GridPane.setHgrow(term, Priority.ALWAYS);

        VBox left = new VBox(10, Ui.heading("Profile"), form, Ui.button("Save profile", this::saveProfile), new javafx.scene.control.Separator(),
                Ui.heading("Career goal"), goal, strategy, Ui.primary("Save goal", this::saveGoal), hints);
        left.setPrefWidth(360);
        ScrollPane leftScroll = new ScrollPane(left);
        leftScroll.setFitToWidth(true);
        leftScroll.setPrefWidth(390);
        leftScroll.setMinWidth(390);

        javafx.scene.layout.FlowPane actions = new javafx.scene.layout.FlowPane(8, 8, Ui.primary("Add fact", this::addFact), Ui.button("Import resume (PDF, DOCX, TXT)", this::importResume),
                Ui.button("Verify selected", this::verifySelected), Ui.button("Delete selected", this::deleteSelected), pendingOnly);
        VBox right = new VBox(10, Ui.heading("Facts"), Ui.muted("Only verified facts are ever used in your documents. Extracted facts must be checked first."), actions, facts);
        HBox.setHgrow(right, Priority.ALWAYS);
        VBox.setVgrow(right, Priority.ALWAYS);
        HBox body = new HBox(18, leftScroll, right);
        VBox.setVgrow(body, Priority.ALWAYS);
        getChildren().addAll(Ui.title("Profile & goal"), body);
    }

    @Override String name() { return "Profile"; }

    private CandidateService svc() { return app.bean(CandidateService.class); }

    @Override
    void refresh() {
        Async.run(this, () -> new Object[]{svc().getProfile(app.user()), svc().getCareerGoal(app.user()), svc().completenessHints(app.user())}, d -> {
            CandidateProfile p = (CandidateProfile) d[0];
            fullName.setText(p.getFullName());
            headline.setText(p.getHeadline());
            location.setText(p.getLocation());
            email.setText(p.getEmail());
            phone.setText(p.getPhone());
            summary.setText(p.getSummary());
            CareerGoal g = (CareerGoal) d[1];
            if (g != null) {
                goalType.setValue(g.getGoalType());
                term.setText(g.getWorkTerm());
                roles.setText(String.join(", ", g.getTargetRoles()));
                locations.setText(String.join(", ", g.getPreferredLocations()));
                workModel.setValue(g.getWorkModel().name());
                minScore.getValueFactory().setValue(g.getMinMatchScore());
                strategy.setText("Scoring strategy: " + g.strategyKey() + "   weights " + g.weights().toMap());
            } else {
                goalType.setValue(GoalType.COOP);
                workModel.setValue("ANY");
                strategy.setText("No goal yet: jobs cannot be scored until you save one.");
            }
            @SuppressWarnings("unchecked") List<String> h = (List<String>) d[2];
            hints.setText(h.isEmpty() ? "Profile looks complete." : "To do: " + String.join("; ", h));
            loadFacts();
        });
    }

    private void loadFacts() {
        Async.run(null, () -> svc().listFacts(app.user(), pendingOnly.isSelected() ? Boolean.FALSE : null), list -> Ui.fill(facts, list));
    }

    private void saveProfile() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("full_name", fullName.getText());
        data.put("headline", headline.getText());
        data.put("location", location.getText());
        data.put("email", email.getText());
        data.put("phone", phone.getText());
        data.put("summary", summary.getText());
        Async.run(this, () -> svc().updateProfile(app.user(), data), p -> hints.setText("Profile saved."));
    }

    private void saveGoal() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("goal_type", goalType.getValue().name());
        data.put("work_term", term.getText());
        data.put("target_roles", roles.getText());
        data.put("preferred_locations", locations.getText());
        data.put("work_model", workModel.getValue());
        data.put("min_match_score", minScore.getValue());
        Async.run(this, () -> svc().updateCareerGoal(app.user(), data), g -> refresh());
    }

    private void addFact() {
        Dialog<Map<String, Object>> dialog = new Dialog<>();
        dialog.setTitle("Add a fact");
        ChoiceBox<FactType> type = new ChoiceBox<>();
        type.getItems().addAll(FactType.values());
        type.setValue(FactType.SKILL);
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(6);
        Map<String, javafx.scene.control.TextInputControl> inputs = new LinkedHashMap<>();
        Runnable rebuild = () -> {
            grid.getChildren().clear();
            inputs.clear();
            grid.addRow(0, new Label("Type"), type);
            List<String> names = new ArrayList<>(type.getValue().required);
            names.addAll(type.getValue().optional);
            int row = 1;
            for (String n : names) {
                boolean area = n.equals("bullets") || n.equals("description");
                javafx.scene.control.TextInputControl in = area ? new TextArea() : new TextField();
                if (area) ((TextArea) in).setPrefRowCount(3);
                in.setPromptText(n.equals("bullets") ? "one per line" : n.equals("technologies") ? "comma separated" : n.endsWith("_date") || n.endsWith("_on") ? "YYYY-MM" : "");
                grid.addRow(row++, new Label(n.replace('_', ' ') + (type.getValue().required.contains(n) ? " *" : "")), in);
                inputs.put(n, in);
            }
        };
        type.setOnAction(e -> rebuild.run());
        rebuild.run();
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResultConverter(b -> {
            if (b != ButtonType.OK) return null;
            Map<String, Object> m = new LinkedHashMap<>();
            inputs.forEach((k, v) -> m.put(k, v.getText()));
            return m;
        });
        dialog.showAndWait().ifPresent(data -> Async.run(this, () -> svc().addFact(app.user(), type.getValue(), data), f -> refresh()));
    }

    private void importResume() {
        File file = Ui.openFile(getScene().getWindow(), "Choose your resume", "Resumes", "*.pdf", "*.docx", "*.txt");
        if (file == null) return;
        Async.run(this, () -> {
            try {
                return svc().importResume(app.user(), file.getName(), Files.readAllBytes(file.toPath()));
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot read " + file, e);
            }
        }, (UploadedResume r) -> {
            Async.info(r.getParseStatus() == UploadedResume.ParseStatus.PARSED
                    ? r.getExtractedCount() + " fact(s) were extracted. Review and verify them in the table; nothing is used until you do."
                    : "Import " + r.getParseStatus() + ": " + r.getStatusMessage());
            pendingOnly.setSelected(true);
            refresh();
        });
    }

    private void verifySelected() {
        List<UUID> ids = facts.getSelectionModel().getSelectedItems().stream().map(ProfileFact::getId).toList();
        if (ids.isEmpty()) return;
        Async.run(this, () -> svc().verifyFacts(app.user(), ids, Map.of()), n -> refresh());
    }

    private void deleteSelected() {
        List<ProfileFact> selected = new ArrayList<>(facts.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) return;
        Async.run(this, () -> {
            selected.forEach(f -> svc().deleteFact(app.user(), f.getId()));
            return selected.size();
        }, n -> refresh());
    }
}
