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

    @Override
    public void start(Stage primary) {
        this.stage = primary;
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
        scene.getStylesheets().add(getClass().getResource("/gui/styles.css").toExternalForm());
        stage.setScene(scene);
    }

    @Override
    public void stop() {
        if (app != null) app.close();
        Platform.exit();
    }
}
