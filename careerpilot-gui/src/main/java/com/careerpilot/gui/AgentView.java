package com.careerpilot.gui;

import com.careerpilot.agent.AgentActionRecord;
import com.careerpilot.agent.AgentController;
import com.careerpilot.agent.AgentMemoryEntry;
import com.careerpilot.agent.AgentRun;
import com.careerpilot.agent.MemoryManager;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/** F16 goal-driven agent workspace: goal, plan, reasoning, approvals, questions, undo, and what the agent remembers. */
final class AgentView extends View {
    private final TextField goal = new TextField();
    private final TableView<AgentRun> runs = Ui.table(Ui.chipCol("Status", 150, AgentRun::getStatus), Ui.col("Goal", 260, AgentRun::getGoalText));
    private final Label heading = new Label();
    private final Label meta = new Label();
    private final HBox headState = new HBox(8);
    private final TableView<Map<String, Object>> plan = Ui.table(Ui.col("#", 36, m -> String.valueOf(m.getOrDefault("n", ""))), Ui.col("Tool", 150, m -> String.valueOf(m.get("tool"))),
            Ui.chipCol("Status", 110, m -> String.valueOf(m.getOrDefault("status", "")).toUpperCase()), Ui.col("Result", 340, m -> String.valueOf(m.getOrDefault("observation", m.get("intent")))));
    private final TextArea reasoning = Ui.readOnlyText();
    private final VBox pending = new VBox(10);
    private final VBox detail = new VBox(14);
    private final VBox runBody = new VBox(14);
    private final ListView<String> memories = new ListView<>();
    private final TitledPane memoryPane = new TitledPane();

    AgentView(AppContext app) {
        super(app);
        goal.setPromptText("e.g. Prepare my application for the Acme backend co-op");
        goal.setAccessibleText("Goal for the agent");
        HBox.setHgrow(goal, Priority.ALWAYS);
        goal.setOnAction(e -> start());
        HBox goalRow = new HBox(8, goal, Ui.primary("Run", this::start));
        goalRow.setAlignment(Pos.CENTER_LEFT);

        runs.getColumns().get(0).setMinWidth(170);
        runs.setPlaceholder(Ui.emptyState("Agent", "No runs yet", "Give the agent a goal above. It plans, then asks before it does anything that changes your data."));
        runs.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> { if (r != null) show(r); });
        heading.getStyleClass().add("heading");
        heading.setWrapText(true);
        meta.getStyleClass().add("caption");
        pending.getStyleClass().addAll("card", "attention");
        pending.setVisible(false);
        pending.setManaged(false);
        plan.getColumns().get(0).setMinWidth(40);
        plan.getColumns().get(0).setMaxWidth(48);
        plan.setPrefHeight(190);
        plan.setPlaceholder(Ui.muted("No steps yet."));
        reasoning.setPrefRowCount(6);
        runBody.getChildren().addAll(new VBox(4, heading, new HBox(10, headState, meta)), pending, Ui.section("Plan"), plan, Ui.section("Reasoning and result"), reasoning);
        detail.getChildren().add(runBody);
        runBody.setVisible(false);
        runBody.setManaged(false);
        showEmptyDetail();

        memories.setPrefHeight(130);
        memories.setPlaceholder(Ui.muted("Nothing yet. The agent remembers outcomes of runs and what you correct."));
        memoryPane.setText("What I remember about you");
        memoryPane.setContent(memories);
        memoryPane.setExpanded(false);
        VBox left = new VBox(12, runs, memoryPane);
        VBox.setVgrow(runs, Priority.ALWAYS);
        SplitPane split = new SplitPane(left, Ui.scroll(detail));
        split.setDividerPositions(0.38);
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().addAll(goalRow, split);
    }

    @Override String name() { return "Agent"; }

    @Override String subtitle() { return "It plans and asks first. It never submits, sends email or logs in."; }

    private void showEmptyDetail() {
        detail.getChildren().removeIf(n -> n != runBody);
        detail.getChildren().add(Ui.emptyState("Agent", "Pick a run", "Select one on the left to see its plan, its reasoning, and anything waiting for your approval."));
    }

    private AgentController agent() { return app.bean(AgentController.class); }

    @Override
    void refresh() {
        AgentRun keep = runs.getSelectionModel().getSelectedItem();
        Async.run(this, () -> new Object[]{agent().listRuns(app.user()), app.bean(MemoryManager.class).all(app.user())}, d -> {
            @SuppressWarnings("unchecked") List<AgentRun> list = (List<AgentRun>) d[0];
            Ui.fill(runs, list);
            List<?> mem = (List<?>) d[1];
            memories.getItems().setAll(mem.stream().map(m -> pretty(((AgentMemoryEntry) m).getKind().toString()) + ": " + ((AgentMemoryEntry) m).getContent()).toList());
            memoryPane.setText("What I remember about you (" + mem.size() + ")");
            AgentRun target = keep == null ? null : list.stream().filter(r -> r.getId().equals(keep.getId())).findFirst().orElse(null);
            // a run that is waiting for the student is the most important thing here, so open it without being asked
            if (target == null) target = list.stream().filter(AgentView::waiting).findFirst().orElse(list.isEmpty() ? null : list.get(0));
            if (target != null) {
                runs.getSelectionModel().select(target);
                show(target);
            }
        });
    }

    private static boolean waiting(AgentRun r) { return r.getStatus().equals(AgentRun.AWAITING_APPROVAL) || r.getStatus().equals(AgentRun.AWAITING_INPUT); }

    private static String pretty(String s) {
        String t = s.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private void start() {
        String text = goal.getText();
        if (text.isBlank()) return;
        Async.run(this, () -> agent().runGoal(app.user(), text), run -> {
            goal.clear();
            refresh();
            show(run);
        });
    }

    private void show(AgentRun run) {
        detail.getChildren().removeIf(n -> n != runBody);
        runBody.setVisible(true);
        runBody.setManaged(true);
        heading.setText(run.getGoalText());
        headState.getChildren().setAll(Ui.chip(pretty(run.getStatus()), Ui.stateKind(run.getStatus())));
        meta.setText(pretty(run.getPlanSource()) + " plan • " + run.getTokensUsed() + " tokens");
        java.util.ArrayList<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < run.getPlan().size(); i++) {
            Map<String, Object> m = new java.util.LinkedHashMap<>(run.getPlan().get(i));
            m.put("n", i + 1);
            if (!m.containsKey("status")) m.put("status", i == run.getCurrentStep() ? "next" : "todo");
            rows.add(m);
        }
        Ui.fill(plan, rows);
        StringBuilder sb = new StringBuilder();
        run.getDecisions().forEach(d -> sb.append("Step ").append(((Number) d.get("step")).intValue() + 1).append(": ").append(d.get("action")).append(" (").append(d.get("source")).append(") ").append(d.get("rationale")).append('\n'));
        if (!run.getSummary().isEmpty()) sb.append("\nResult: ").append(run.getSummary());
        if (!run.getError().isEmpty()) sb.append("\nStopped: ").append(run.getError());
        reasoning.setText(sb.toString());

        pending.getChildren().clear();
        boolean waiting = waiting(run);
        pending.setVisible(waiting);
        pending.setManaged(waiting);
        if (run.getStatus().equals(AgentRun.AWAITING_APPROVAL)) {
            pending.getChildren().addAll(attention("Needs your approval"), body(String.valueOf(run.getPending().get("description"))),
                    Ui.row(Ui.primary("Approve", () -> resume(run, true)), Ui.button("Reject", () -> resume(run, false))));
        } else if (run.getStatus().equals(AgentRun.AWAITING_INPUT)) {
            FlowPane options = new FlowPane(8, 8);
            for (Object o : (List<?>) run.getPending().get("options")) {
                Map<?, ?> opt = (Map<?, ?>) o;
                options.getChildren().add(Ui.button(String.valueOf(opt.get("label")), () -> answer(run, String.valueOf(opt.get("id")))));
            }
            pending.getChildren().addAll(attention("The agent has a question"), body(String.valueOf(run.getPending().get("question"))), options);
        }
        Async.run(null, () -> agent().actions(app.user(), run.getId()), (List<AgentActionRecord> actions) -> {
            for (AgentActionRecord a : actions)
                if (a.isReversible() && AgentActionRecord.EXECUTED.equals(a.getStatus()))
                    pending.getChildren().add(Ui.row(Ui.chip("Done", "success"), new Label(a.getDescription()), Ui.spacer(),
                            Ui.button("Undo", () -> Async.run(this, () -> agent().undoAction(app.user(), a.getId()), x -> refresh()))));
            if (!pending.getChildren().isEmpty()) {
                pending.setVisible(true);
                pending.setManaged(true);
            }
        });
    }

    private static Label attention(String text) {
        Label l = new Label(text, Icons.box("Alert", 16));
        l.getStyleClass().add("heading");
        return l;
    }

    private static Label body(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        return l;
    }

    private void resume(AgentRun run, boolean approve) {
        Async.run(this, () -> agent().resumeRun(app.user(), run.getId(), approve, approve ? "" : "rejected in the GUI"), r -> {
            refresh();
            show(r);
        });
    }

    private void answer(AgentRun run, String choice) {
        Async.run(this, () -> agent().answer(app.user(), run.getId(), choice), r -> {
            refresh();
            show(r);
        });
    }
}
