package com.careerpilot.gui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** JavaFX entry point: starts the shared core once, shows the sign-in screen, then the main window. */
public class CareerPilotApp extends Application {
    private AppContext app;
    private Stage stage;

    @Override
    public void init() { app = AppContext.start(); }

    static CareerPilotApp instance;

    java.util.function.Consumer<String> opener() { return url -> getHostServices().showDocument(url); }

    @Override
    public void start(Stage primary) {
        this.stage = primary;
        instance = this;
        Theme.apply();
        app.setLinkOpener(opener());
        stage.setTitle("CareerPilot");
        showLogin();
        stage.show();
    }

    void showLogin() {
        app.signOut();
        setScene(new LoginView(app, this::showMain));
        stage.setWidth(460);
        stage.setHeight(520);
    }

    void showMain() {
        setScene(new MainView(app, this::showLogin));
        stage.setWidth(1180);
        stage.setHeight(760);
        stage.centerOnScreen();
    }

    private void setScene(javafx.scene.Parent root) {
        Scene scene = new Scene(root);
        scene.getStylesheets().addAll(Theme.stylesheets());
        Theme.attach(root);
        stage.setScene(scene);
        stage.focusedProperty().addListener((o, was, now) -> { if (now) Theme.refreshSystem(); });
    }

    @Override
    public void stop() {
        if (app != null) app.close();
        Platform.exit();
    }
}
