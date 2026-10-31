package com.careerpilot.gui;

import com.careerpilot.users.AuthService;
import com.careerpilot.users.User;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.regex.Pattern;

/** First-run and sign-in screen: a brand panel and one form with two paths, sign in or create an account (local accounts, BCrypt passwords). */
final class LoginView extends HBox {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final TextField email = new TextField();
    private final PasswordField password = new PasswordField();
    private final TextField name = new TextField();
    private final Label emailError = new Label(), passwordError = new Label(), nameError = new Label();
    private final VBox nameBox;
    private final Label notice = new Label();
    private final Button submit;
    private final Label formTitle = new Label();
    private final Label formHint = new Label();
    private boolean creating;
    private boolean touched;

    LoginView(AppContext app, Runnable onSignedIn) {
        getStyleClass().add("shell");
        // brand panel
        StackPane mark = new StackPane(Icons.box("Mark", 20));
        mark.getStyleClass().add("brand-mark");
        Label word = new Label("CareerPilot");
        word.getStyleClass().add("brand-word");
        HBox brand = new HBox(10, mark, word);
        brand.setAlignment(Pos.CENTER_LEFT);
        Label heroTitle = new Label("Your job search, organized.");
        heroTitle.getStyleClass().add("hero-title");
        heroTitle.setWrapText(true);
        Label heroBody = new Label("You submit. CareerPilot prepares and tracks.");
        heroBody.getStyleClass().add("hero-body");
        heroBody.setWrapText(true);
        VBox points = new VBox(12, point("Shield", "Every claim in your documents is backed by a fact you verified."),
                point("Check", "The agent plans, then asks before it changes anything."),
                point("Info", "Your data stays on this computer."));
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        VBox hero = new VBox(16, brand, grow, heroTitle, heroBody, points);
        hero.getStyleClass().add("login-hero");
        hero.setPadding(new Insets(36));
        hero.setPrefWidth(360);
        hero.setMinWidth(360);

        // form
        ToggleGroup mode = new ToggleGroup();
        ToggleButton signInTab = segment("Sign in", mode, "first");
        ToggleButton createTab = segment("Create account", mode, "last");
        signInTab.setSelected(true);
        mode.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) was.setSelected(true);
            else setCreating(now == createTab);
        });
        HBox tabs = new HBox(0, signInTab, createTab);
        formTitle.getStyleClass().add("title");
        formHint.getStyleClass().add("page-subtitle");
        formHint.setWrapText(true);

        email.setPromptText("you@my.yorku.ca");
        password.setPromptText("At least 8 characters");
        name.setPromptText("What should we call you?");
        email.setAccessibleText("Email");
        password.setAccessibleText("Password");
        name.setAccessibleText("Name");
        for (Label l : new Label[]{emailError, passwordError, nameError}) {
            l.getStyleClass().add("field-error");
            l.setWrapText(true);
            l.setVisible(false);
            l.setManaged(false);
        }
        nameBox = field("Name", name, nameError);
        notice.getStyleClass().addAll("notice", "notice-danger");
        notice.setGraphic(Icons.box("Alert", 16));
        notice.setWrapText(true);
        notice.setMaxWidth(Double.MAX_VALUE);
        notice.setVisible(false);
        notice.setManaged(false);
        submit = Ui.primary("Sign in", () -> submit(app, onSignedIn));
        submit.setDefaultButton(true);
        submit.setMaxWidth(Double.MAX_VALUE);

        email.textProperty().addListener((o, a, b) -> { if (touched) validate(); });
        password.textProperty().addListener((o, a, b) -> { if (touched) validate(); });
        name.textProperty().addListener((o, a, b) -> { if (touched) validate(); });

        VBox form = new VBox(14, tabs, new VBox(4, formTitle, formHint), field("Email", email, emailError), field("Password", password, passwordError),
                nameBox, notice, submit);
        form.setMaxWidth(380);
        form.setMaxHeight(Region.USE_PREF_SIZE);
        form.setPadding(new Insets(0, 32, 0, 32));
        StackPane right = new StackPane(form);
        right.getStyleClass().add("login-form");
        right.setAlignment(Pos.CENTER);
        HBox.setHgrow(right, Priority.ALWAYS);
        getChildren().addAll(hero, right);
        setCreating(false);
    }

    private static HBox point(String icon, String text) {
        Label l = new Label(text);
        l.getStyleClass().add("hero-point");
        l.setWrapText(true);
        HBox h = new HBox(10, Icons.box(icon, 16), l);
        h.setAlignment(Pos.TOP_LEFT);
        return h;
    }

    private static ToggleButton segment(String text, ToggleGroup group, String position) {
        ToggleButton b = new ToggleButton(text);
        b.setToggleGroup(group);
        b.getStyleClass().addAll("segment", position);
        b.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(b, Priority.ALWAYS);
        return b;
    }

    private static VBox field(String label, javafx.scene.control.TextInputControl input, Label error) {
        Label l = new Label(label);
        l.getStyleClass().add("field-label");
        l.setLabelFor(input);
        return new VBox(4, l, input, error);
    }

    private void setCreating(boolean create) {
        creating = create;
        touched = false;
        formTitle.setText(create ? "Create your account" : "Welcome back");
        formHint.setText(create ? "A local account keeps your profile, jobs and documents on this computer." : "Sign in to pick up where you left off.");
        submit.setText(create ? "Create account" : "Sign in");
        nameBox.setVisible(create);
        nameBox.setManaged(create);
        password.setPromptText(create ? "At least 8 characters" : "Your password");
        showError(emailError, null, email);
        showError(passwordError, null, password);
        showError(nameError, null, name);
        notice.setVisible(false);
        notice.setManaged(false);
    }

    private static void showError(Label label, String text, javafx.scene.control.TextInputControl input) {
        label.setText(text == null ? "" : text);
        label.setVisible(text != null);
        label.setManaged(text != null);
        input.getStyleClass().remove("invalid");
        if (text != null) input.getStyleClass().add("invalid");
    }

    /** Inline validation: says what is wrong next to the field, as soon as the student has tried to continue. */
    private boolean validate() {
        touched = true;
        boolean ok = true;
        String e = email.getText().trim();
        showError(emailError, e.isEmpty() ? "Enter your email." : EMAIL.matcher(e).matches() ? null : "That doesn't look like an email address.", email);
        if (emailError.isVisible()) ok = false;
        String pw = password.getText();
        showError(passwordError, pw.isEmpty() ? "Enter your password." : creating && pw.length() < 8 ? "Use at least 8 characters." : null, password);
        if (passwordError.isVisible()) ok = false;
        if (creating) {
            showError(nameError, name.getText().isBlank() ? "Enter your name." : null, name);
            if (nameError.isVisible()) ok = false;
        }
        return ok;
    }

    private void submit(AppContext app, Runnable onSignedIn) {
        notice.setVisible(false);
        notice.setManaged(false);
        if (!validate()) return;
        AuthService auth = app.bean(AuthService.class);
        boolean create = creating;
        String mail = email.getText().trim(), pw = password.getText(), who = name.getText();
        Async.run(submit, () -> create ? auth.register(mail, pw, who) : auth.login(mail, pw),
                (User user) -> {
                    app.signIn(user);
                    onSignedIn.run();
                }, t -> {
                    notice.setText(t.getMessage() == null ? "Couldn't sign you in. Try again." : t.getMessage());
                    notice.setVisible(true);
                    notice.setManaged(true);
                });
    }
}
