package com.careerpilot.gui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;

/** The main window: a sidebar of pages and the page being shown. */
final class MainView extends BorderPane {
    private final Map<String, View> pages = new LinkedHashMap<>();
    private final Map<String, Button> buttons = new LinkedHashMap<>();

    MainView(AppContext app, Runnable onSignOut) {
        for (View v : new View[]{new DashboardView(app, this::open), new ProfileView(app), new JobsView(app, this::open), new TrackerView(app),
                new AgentView(app), new EmailView(app), new SettingsView(app, onSignOut)})
            pages.put(v.name(), v);

        VBox sidebar = new VBox(4);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(16, 12, 16, 12));
        sidebar.setPrefWidth(200);
        Label brand = new Label("CareerPilot");
        brand.getStyleClass().add("brand");
        sidebar.getChildren().add(brand);
        Label who = new Label(app.user().getEmail());
        who.getStyleClass().add("who");
        sidebar.getChildren().add(who);
        sidebar.getChildren().add(new Region() {{ setPrefHeight(14); }});
        for (String name : pages.keySet()) {
            Button b = new Button(name);
            b.setMaxWidth(Double.MAX_VALUE);
            b.getStyleClass().add("nav-button");
            b.setOnAction(e -> open(name));
            buttons.put(name, b);
            sidebar.getChildren().add(b);
        }
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        sidebar.getChildren().add(grow);
        setLeft(sidebar);
        open("Dashboard");
    }

    void open(String name) {
        View view = pages.get(name);
        buttons.forEach((n, b) -> {
            b.getStyleClass().remove("active");
            if (n.equals(name)) b.getStyleClass().add("active");
        });
        setCenter(view);
        view.refresh();
    }
}
