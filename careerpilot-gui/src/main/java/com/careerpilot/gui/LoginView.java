package com.careerpilot.gui;

import com.careerpilot.users.AuthService;
import com.careerpilot.users.User;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/** Sign in or create an account (local accounts, BCrypt passwords). */
final class LoginView extends VBox {
    private final TextField email = new TextField();
    private final PasswordField password = new PasswordField();
    private final TextField name = new TextField();
    private final Label message = new Label();

    LoginView(AppContext app, Runnable onSignedIn) {
        super(10);
        setPadding(new Insets(36));
        setAlignment(Pos.CENTER_LEFT);
        getStyleClass().add("page");
        email.setPromptText("you@my.yorku.ca");
        password.setPromptText("Password (8+ characters)");
        name.setPromptText("Your name (for new accounts)");
        message.getStyleClass().add("error-text");
        message.setWrapText(true);
        Button signIn = Ui.primary("Sign in", () -> submit(app, false, onSignedIn));
        Button register = Ui.button("Create account", () -> submit(app, true, onSignedIn));
        signIn.setDefaultButton(true);
        getChildren().addAll(Ui.title("CareerPilot"), Ui.muted("Your job search, organized. CareerPilot prepares and tracks; you always submit."),
                new Label("Email"), email, new Label("Password"), password, new Label("Name"), name, Ui.row(signIn, register), message);
    }

    private void submit(AppContext app, boolean create, Runnable onSignedIn) {
        message.setText("");
        AuthService auth = app.bean(AuthService.class);
        Async.run(this, () -> create ? auth.register(email.getText(), password.getText(), name.getText()) : auth.login(email.getText(), password.getText()),
                (User user) -> {
                    app.signIn(user);
                    onSignedIn.run();
                }, t -> message.setText(t.getMessage()));
    }
}
