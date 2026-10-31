package com.careerpilot.gui;

import atlantafx.base.controls.ToggleSwitch;
import com.careerpilot.candidates.CandidateService;
import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.LlmException;
import com.careerpilot.llm.UsageTracker;
import com.careerpilot.users.AuthService;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Map;

/** Appearance, automation switch, AI connection check and token use, password change, sign out, about. */
final class SettingsView extends View {
    private final ToggleSwitch autoTriage = new ToggleSwitch();
    private final Label llmStatus = Ui.muted("");
    private final PasswordField oldPw = new PasswordField(), newPw = new PasswordField();
    private final Label pwError = new Label();
    private final Label tokens = new Label();

    SettingsView(AppContext app, Runnable onSignOut) {
        super(app);
        oldPw.setPromptText("Current password");
        oldPw.setAccessibleText("Current password");
        newPw.setPromptText("At least 8 characters");
        newPw.setAccessibleText("New password");
        pwError.getStyleClass().add("field-error");
        pwError.setVisible(false);
        pwError.setManaged(false);
        autoTriage.setAccessibleText("Let the agent triage each imported batch automatically");
        autoTriage.selectedProperty().addListener((o, was, now) -> {
            if (suppress) return;
            Async.run(this, () -> app.bean(CandidateService.class).updateProfile(app.user(), Map.of("auto_triage", now)), p -> Toast.show(now ? "Automatic triage is on." : "Automatic triage is off."));
        });

        ToggleGroup mode = new ToggleGroup();
        HBox seg = new HBox(0);
        Theme.Mode[] modes = Theme.Mode.values();
        for (int i = 0; i < modes.length; i++) {
            Theme.Mode m = modes[i];
            ToggleButton b = new ToggleButton(m.name().charAt(0) + m.name().substring(1).toLowerCase());
            b.setToggleGroup(mode);
            b.getStyleClass().addAll("segment", i == 0 ? "first" : i == modes.length - 1 ? "last" : "middle");
            b.setSelected(Theme.mode() == m);
            b.setOnAction(e -> { if (b.isSelected()) Theme.setMode(m); else b.setSelected(true); });
            seg.getChildren().add(b);
        }

        Button check = Ui.button("Check connection", this::checkLlm);
        VBox ai = new VBox(8, row("Connection", "The provider, model and key come from your local .env file. Keys are never stored in the app or the repository.", check), llmStatus);
        VBox changePw = new VBox(8, row("Password", "Use at least 8 characters.", new HBox(8, oldPw, newPw, Ui.button("Change", this::changePassword))), pwError);
        tokens.getStyleClass().add("caption");

        VBox body = new VBox(16,
                card("Appearance", row("Theme", "System follows your operating system and changes with it.", seg)),
                card("Automation", row("Triage imports automatically", "When you import postings, the agent scores and shortlists them for you.", autoTriage)),
                card("AI model", ai, tokens),
                card("Account", changePw, row("Signed in as " + app.user().getEmail(), "Signing out returns you to the sign-in screen.", Ui.button("Sign out", onSignOut))),
                card("About", Ui.muted("CareerPilot 2.0 (EECS 3311 Stage 2, Java). It prepares applications and tracks them. It never submits an application, sends email on your behalf or logs in to a portal for you.")));
        body.setMaxWidth(720);
        getChildren().add(Ui.scroll(body));
    }

    private boolean suppress;

    @Override String name() { return "Settings"; }

    @Override String subtitle() { return "Appearance, automation and your account."; }

    private static VBox card(String title, Node... content) {
        VBox c = Ui.card(title, content);
        c.setSpacing(12);
        return c;
    }

    /** A setting: what it is and what it does on the left, the control on the right. */
    private static HBox row(String title, String description, Node control) {
        Label t = new Label(title);
        t.getStyleClass().add("body-strong");
        Label d = Ui.muted(description);
        VBox text = new VBox(2, t, d);
        HBox.setHgrow(text, Priority.ALWAYS);
        if (control instanceof javafx.scene.layout.Region r) r.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
        HBox h = new HBox(16, text, control);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    @Override
    void refresh() {
        Async.run(null, () -> app.bean(CandidateService.class).getProfile(app.user()).isAutoTriage(), on -> {
            suppress = true;
            autoTriage.setSelected(on);
            suppress = false;
        });
        Async.run(null, () -> app.bean(UsageTracker.class).usedToday(app.user()), used -> tokens.setText("Tokens used in the last 24 hours: " + used));
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
        if (newPw.getText().length() < 8) {
            pwError.setText("Use at least 8 characters for the new password.");
            pwError.setVisible(true);
            pwError.setManaged(true);
            return;
        }
        pwError.setVisible(false);
        pwError.setManaged(false);
        Async.run(this, () -> app.bean(AuthService.class).changePassword(app.user(), oldPw.getText(), newPw.getText()), () -> {
            oldPw.clear();
            newPw.clear();
            Toast.show("Password changed.");
        });
    }
}
