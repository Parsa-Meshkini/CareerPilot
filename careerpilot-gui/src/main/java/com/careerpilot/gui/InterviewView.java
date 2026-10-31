package com.careerpilot.gui;

import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.interviews.InterviewPrepPlan;
import com.careerpilot.interviews.InterviewPrepService;
import com.careerpilot.interviews.MockInterviewService;
import com.careerpilot.interviews.MockInterviewSession;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/** F17 preparation plan and F18 mock interview for one tracked application. */
final class InterviewView extends View {
    private final ChoiceBox<Application> apps = new ChoiceBox<>();
    private final TextArea plan = Ui.readOnlyText();
    private final Label question = new Label("Start a mock interview to get the first question.");
    private final TextArea answer = new TextArea();
    private final TextArea feedback = Ui.readOnlyText();
    private MockInterviewSession session;

    InterviewView(AppContext app) {
        super(app);
        apps.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Application a) { return a == null ? "" : a.getJob().getTitle() + " at " + a.getJob().getCompany() + " [" + a.getStateName() + "]"; }
            @Override public Application fromString(String s) { return null; }
        });
        apps.setPrefWidth(480);
        apps.setOnAction(e -> loadPlan());
        question.setWrapText(true);
        question.getStyleClass().add("detail-title");
        answer.setPromptText("Type your answer. Use the situation, task, action and result.");
        answer.setPrefRowCount(5);
        answer.setWrapText(true);
        feedback.setPrefRowCount(8);
        plan.setPromptText("No plan yet");
        Label qBadge = new Label("Question");
        qBadge.getStyleClass().add("section-label");
        VBox left = new VBox(12, Ui.heading("Preparation plan"), Ui.muted("Questions come from the posting. STAR stories use only your verified facts."),
                Ui.row(Ui.primary("Prepare plan", this::prepare)), plan);
        VBox mock = new VBox(10, qBadge, question, answer, Ui.row(Ui.primary("Submit answer", this::submit)));
        VBox right = new VBox(12, Ui.heading("Mock interview"), Ui.row(Ui.primary("Start mock interview", this::start), Ui.button("Finish and get summary", this::finish)),
                Ui.card(null, mock), Ui.heading("Feedback"), feedback);
        VBox.setVgrow(plan, Priority.ALWAYS);
        VBox.setVgrow(feedback, Priority.ALWAYS);
        HBox.setHgrow(left, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        left.setPrefWidth(400);
        right.setPrefWidth(400);
        HBox body = new HBox(20, Ui.card(null, left), right);
        HBox.setHgrow(body.getChildren().get(0), Priority.ALWAYS);
        VBox.setVgrow(body, Priority.ALWAYS);
        apps.setMaxWidth(Double.MAX_VALUE);
        HBox pick = new HBox(12, Ui.label("Application"), apps);
        pick.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        HBox.setHgrow(apps, Priority.ALWAYS);
        getChildren().addAll(pick, body);
    }

    @Override String name() { return "Interview"; }

    @Override String subtitle() { return "A plan from the posting, then a mock interview with feedback."; }

    @Override
    void refresh() {
        Async.run(null, () -> app.bean(ApplicationService.class).listApplications(app.user(), null), list -> {
            Application keep = apps.getValue();
            apps.getItems().setAll(list);
            if (keep != null) list.stream().filter(a -> a.getId().equals(keep.getId())).findFirst().ifPresent(apps::setValue);
            else if (!list.isEmpty()) apps.setValue(list.get(0));
            loadPlan();
        });
    }

    private Application chosen() { return apps.getValue(); }

    private void loadPlan() {
        Application a = chosen();
        session = null;
        feedback.clear();
        question.setText("Start a mock interview to get the first question.");
        if (a == null) {
            plan.clear();
            return;
        }
        Async.run(null, () -> app.bean(InterviewPrepService.class).latest(app.user(), a.getId()), p -> plan.setText(p == null ? "No plan yet. Click Prepare plan." : render(p)));
    }

    private void prepare() {
        Application a = chosen();
        if (a != null) Async.run(this, () -> app.bean(InterviewPrepService.class).preparePlan(app.user(), a.getId()), p -> plan.setText(render(p)));
    }

    static String render(InterviewPrepPlan p) {
        StringBuilder sb = new StringBuilder("Plan v" + p.getVersion() + " (" + p.getSource() + ")\n\n");
        for (Object o : (List<?>) p.getContent().getOrDefault("questions", List.of())) {
            Map<?, ?> q = (Map<?, ?>) o;
            sb.append("[").append(q.get("category")).append("] ").append(q.get("question")).append("\n    why: ").append(q.get("rationale")).append("\n");
        }
        for (Object o : (List<?>) p.getContent().getOrDefault("star_stories", List.of())) {
            Map<?, ?> s = (Map<?, ?>) o;
            sb.append("\nSTAR: ").append(s.get("title")).append("\n  S: ").append(s.get("situation")).append("\n  T: ").append(s.get("task"))
                    .append("\n  A: ").append(s.get("action")).append("\n  R: ").append(s.get("result")).append("\n");
        }
        sb.append("\nChecklist\n");
        for (Object c : (List<?>) p.getContent().getOrDefault("checklist", List.of())) sb.append("  [ ] ").append(c).append("\n");
        if (!p.getNotes().isEmpty()) sb.append("\nNote: ").append(p.getNotes());
        return sb.toString();
    }

    private void start() {
        Application a = chosen();
        if (a == null) return;
        Async.run(this, () -> app.bean(MockInterviewService.class).startSession(app.user(), a.getId()), s -> {
            session = s;
            feedback.clear();
            showQuestion();
        });
    }

    private void showQuestion() {
        Map<String, Object> pending = session == null ? null : session.pendingTurn();
        question.setText(pending == null ? "Session complete." : "Question " + pending.get("n") + " of " + MockInterviewSession.MAX_QUESTIONS + ": " + pending.get("question"));
    }

    private void submit() {
        if (session == null) return;
        String text = answer.getText();
        MockInterviewSession s = session;
        Async.run(this, () -> app.bean(MockInterviewService.class).submitAnswer(app.user(), s.getId(), text), r -> {
            session = r.session();
            feedback.appendText((r.scored() ? "Feedback: " : "") + r.feedback() + "\n\n");
            if (r.scored()) answer.clear();
            if (r.finished()) showSummary(r.session()); else showQuestion();
        });
    }

    private void finish() {
        if (session == null) return;
        MockInterviewSession s = session;
        Async.run(this, () -> app.bean(MockInterviewService.class).finishSession(app.user(), s.getId()), this::showSummary);
    }

    private void showSummary(MockInterviewSession s) {
        session = null;
        question.setText("Session finished.");
        feedback.appendText("SUMMARY\n" + s.getSummary().get("overall") + "\nAverages: " + s.getSummary().get("averages") + "\nImprove: "
                + String.join("; ", ((List<?>) s.getSummary().getOrDefault("improvements", List.of())).stream().map(String::valueOf).toList()));
    }
}
