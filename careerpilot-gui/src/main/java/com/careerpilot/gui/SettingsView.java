package com.careerpilot.gui;

import com.careerpilot.candidates.CandidateService;
import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.LlmException;
import com.careerpilot.llm.UsageTracker;
import com.careerpilot.users.AuthService;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;

import java.util.Map;

/** Automation switch, AI connection check and token use, password change, sign out, about. */
final class SettingsView extends View {
    private final CheckBox autoTriage = new CheckBox("Let the agent triage each imported batch automatically");
    private final Label llmStatus = Ui.muted("");
    private final PasswordField oldPw = new PasswordField(), newPw = new PasswordField();

    SettingsView(AppContext app, Runnable onSignOut) {
        super(app);
        oldPw.setPromptText("Current password");
        newPw.setPromptText("New password (8+ characters)");
        autoTriage.setOnAction(e -> Async.run(this, () -> app.bean(CandidateService.class).updateProfile(app.user(), Map.of("auto_triage", autoTriage.isSelected())), p -> { }));
        getChildren().addAll(Ui.title("Settings"), Ui.heading("Automation"), autoTriage,
                Ui.heading("AI model"), Ui.muted("The provider, model and key come from your local .env file; keys are never stored in the app or the repository."),
                Ui.row(Ui.button("Check connection", this::checkLlm)), llmStatus,
                Ui.heading("Password"), Ui.row(oldPw, newPw, Ui.button("Change", this::changePassword)),
                Ui.heading("Account"), Ui.button("Sign out", onSignOut),
                Ui.heading("About"), Ui.muted("CareerPilot 2.0 (EECS 3311 Stage 2, Java). It prepares applications and tracks them; it never submits an application, sends email on your behalf or logs in to a portal for you."));
    }

    @Override String name() { return "Settings"; }

    @Override
    void refresh() {
        Async.run(null, () -> app.bean(CandidateService.class).getProfile(app.user()).isAutoTriage(), autoTriage::setSelected);
        Async.run(null, () -> app.bean(UsageTracker.class).usedToday(app.user()), used -> llmStatus.setText("Tokens used in the last 24 hours: " + used));
    }

    private void checkLlm() {
        Async.run(this, () -> {
            try {
                var r = app.bean(LlmClient.class).generateStructured(app.user(), "system.ping", Map.of("word", "careerpilot"));
                return "Model " + r.model() + " answered (" + r.tokens() + " tokens).";
            } catch (LlmException e) {
                return "Not reachable: " + e.getMessage() + "\nCheck LLM_PROVIDER, LLM_API_KEY and LLM_MODEL in .env.";
            }
        }, llmStatus::setText);
    }

    private void changePassword() {
        Async.run(this, () -> app.bean(AuthService.class).changePassword(app.user(), oldPw.getText(), newPw.getText()), () -> {
            oldPw.clear();
            newPw.clear();
            Async.info("Password changed.");
        });
    }
}
