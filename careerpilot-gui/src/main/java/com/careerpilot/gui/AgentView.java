package com.careerpilot.gui;

import com.careerpilot.agent.AgentActionRecord;
import com.careerpilot.agent.AgentController;
import com.careerpilot.agent.AgentMemoryEntry;
import com.careerpilot.agent.AgentRun;
import com.careerpilot.agent.MemoryManager;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/** F16 goal-driven agent workspace: goal, plan, reasoning, approvals, questions, undo, and what the agent remembers. */
final class AgentView extends View {
    private final TextField goal = new TextField();
    private final TableView<AgentRun> runs = Ui.table(Ui.col("Status", 160, AgentRun::getStatus), Ui.col("Goal", 300, AgentRun::getGoalText));
    private final Label heading = Ui.heading("Select or start a run");
    private final TableView<Map<String, Object>> plan = Ui.table(Ui.col("#", 30, m -> String.valueOf(m.getOrDefault("n", ""))), Ui.col("Tool", 160, m -> String.valueOf(m.get("tool"))),
            Ui.col("Status", 80, m -> String.valueOf(m.getOrDefault("status", ""))), Ui.col("Result", 360, m -> String.valueOf(m.getOrDefault("observation", m.get("intent")))));
    private final TextArea reasoning = Ui.readOnlyText();
    private final VBox pending = new VBox(8);
    private final ListView<String> memories = new ListView<>();

    AgentView(AppContext app) {
        super(app);
        goal.setPromptText("e.g. Prepare my application for the Acme backend co-op");
        HBox.setHgrow(goal, Priority.ALWAYS);
        goal.setOnAction(e -> start());
        runs.getSelectionModel().selectedItemProperty().addListener((o, a, r) -> { if (r != null) show(r); });
        plan.setPrefHeight(200);
        reasoning.setPrefRowCount(6);
        pending.getStyleClass().add("card");
        pending.setVisible(false);
        pending.setManaged(false);
        VBox right = new VBox(8, heading, pending, plan, Ui.heading("Reasoning and result"), reasoning);
        SplitPane split = new SplitPane(new VBox(8, runs, Ui.heading("What I remember about you"), memories), right);
        split.setDividerPositions(0.38);
        VBox.setVgrow(split, Priority.ALWAYS);
        memories.setPrefHeight(150);
        getChildren().addAll(Ui.title("Agent workspace"),
                Ui.muted("Give the agent a goal. It plans, uses tools, and pauses for your approval before anything with side effects. It can never submit, send email or log in."),
                Ui.row(goal, Ui.primary("Run", this::start)), split);
    }

    @Override String name() { return "Agent"; }

    private AgentController agent() { return app.bean(AgentController.class); }

    @Override
    void refresh() {
        AgentRun keep = runs.getSelectionModel().getSelectedItem();
        Async.run(null, () -> new Object[]{agent().listRuns(app.user()), app.bean(MemoryManager.class).all(app.user())}, d -> {
            @SuppressWarnings("unchecked") List<AgentRun> list = (List<AgentRun>) d[0];
            Ui.fill(runs, list);
            memories.getItems().setAll(((List<?>) d[1]).stream().map(m -> ((AgentMemoryEntry) m).getKind() + ": " + ((AgentMemoryEntry) m).getContent()).toList());
            if (keep != null) list.stream().filter(r -> r.getId().equals(keep.getId())).findFirst().ifPresent(r -> { runs.getSelectionModel().select(r); show(r); });
        });
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
        heading.setText(run.getGoalText() + "  [" + run.getStatus() + ", " + run.getPlanSource() + " plan, " + run.getTokensUsed() + " tokens]");
        java.util.ArrayList<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (int i = 0; i < run.getPlan().size(); i++) {
            Map<String, Object> m = new java.util.LinkedHashMap<>(run.getPlan().get(i));
            m.put("n", i + 1);
            if (!m.containsKey("status")) m.put("status", i == run.getCurrentStep() ? "next" : "todo");
            rows.add(m);
        }
        Ui.fill(plan, rows);
        StringBuilder sb = new StringBuilder();
        run.getDecisions().forEach(d -> sb.append("step ").append(((Number) d.get("step")).intValue() + 1).append(": ").append(d.get("action")).append(" (").append(d.get("source")).append(") ").append(d.get("rationale")).append('\n'));
        if (!run.getSummary().isEmpty()) sb.append("\nResult: ").append(run.getSummary());
        if (!run.getError().isEmpty()) sb.append("\nStopped: ").append(run.getError());
        reasoning.setText(sb.toString());
        pending.getChildren().clear();
        boolean waiting = run.getStatus().equals(AgentRun.AWAITING_APPROVAL) || run.getStatus().equals(AgentRun.AWAITING_INPUT);
        pending.setVisible(waiting);
        pending.setManaged(waiting);
        if (run.getStatus().equals(AgentRun.AWAITING_APPROVAL)) {
            Label l = new Label("Approval needed: " + run.getPending().get("description"));
            l.setWrapText(true);
            pending.getChildren().addAll(l, Ui.row(Ui.primary("Approve", () -> resume(run, true)), Ui.button("Reject", () -> resume(run, false))));
        } else if (run.getStatus().equals(AgentRun.AWAITING_INPUT)) {
            Label l = new Label(String.valueOf(run.getPending().get("question")));
            l.setWrapText(true);
            FlowPane options = new FlowPane(8, 8);
            for (Object o : (List<?>) run.getPending().get("options")) {
                Map<?, ?> opt = (Map<?, ?>) o;
                options.getChildren().add(Ui.button(String.valueOf(opt.get("label")), () -> answer(run, String.valueOf(opt.get("id")))));
            }
            pending.getChildren().addAll(l, options);
        }
        Async.run(null, () -> agent().actions(app.user(), run.getId()), (List<AgentActionRecord> actions) -> {
            for (AgentActionRecord a : actions)
                if (a.isReversible() && AgentActionRecord.EXECUTED.equals(a.getStatus()))
                    pending.getChildren().add(Ui.row(new Label(a.getDescription()), Ui.button("Undo", () -> Async.run(this, () -> agent().undoAction(app.user(), a.getId()), x -> refresh()))));
            if (!pending.getChildren().isEmpty()) {
                pending.setVisible(true);
                pending.setManaged(true);
            }
        });
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
