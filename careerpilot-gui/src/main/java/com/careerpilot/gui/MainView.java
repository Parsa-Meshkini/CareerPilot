package com.careerpilot.gui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.animation.FadeTransition;
import javafx.util.Duration;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;

/** The main window: a sidebar of pages and the page being shown. */
final class MainView extends StackPane {
    private final Map<String, View> pages = new LinkedHashMap<>();
    private final Map<String, Button> buttons = new LinkedHashMap<>();

    private final BorderPane layout = new BorderPane();

    MainView(AppContext app, Runnable onSignOut) {
        getChildren().add(layout);
        Toast.attach(this);
        for (View v : new View[]{new DashboardView(app, this::open), new ProfileView(app), new JobsView(app, this::open), new TrackerView(app),
                new InterviewView(app), new AgentView(app), new EmailView(app), new InsightsView(app), new SettingsView(app, onSignOut)})
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
            Button b = new Button(name, Icons.box(name));
            b.setMaxWidth(Double.MAX_VALUE);
            b.getStyleClass().add("nav-button");
            b.setOnAction(e -> open(name));
            buttons.put(name, b);
            sidebar.getChildren().add(b);
        }
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        sidebar.getChildren().add(grow);
        Button theme = new Button(Theme.isDark() ? "Light mode" : "Dark mode", Icons.box(Theme.isDark() ? "Sun" : "Moon"));
        theme.getStyleClass().add("theme-toggle");
        theme.setMaxWidth(Double.MAX_VALUE);
        theme.setOnAction(e -> {
            Theme.toggle();
            theme.setText(Theme.isDark() ? "Light mode" : "Dark mode");
            theme.setGraphic(Icons.box(Theme.isDark() ? "Sun" : "Moon"));
        });
        sidebar.getChildren().add(theme);
        layout.setLeft(sidebar);
        open("Dashboard");
    }

    View page(String name) { return pages.get(name); }

    javafx.scene.Node center() { return layout.getCenter(); }

    void open(String name) {
        View view = pages.get(name);
        buttons.forEach((n, b) -> {
            b.getStyleClass().remove("active");
            if (n.equals(name)) b.getStyleClass().add("active");
        });
        layout.setCenter(view);
        view.setOpacity(0);
        view.refresh();
        FadeTransition fade = new FadeTransition(Duration.millis(160), view);  // short, so it never feels slow
        fade.setToValue(1);
        fade.play();
    }
}
