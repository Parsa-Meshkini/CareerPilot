package com.careerpilot.gui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.geometry.Pos;
import javafx.util.Duration;
import javafx.geometry.Insets;

/** Short, non-blocking confirmation messages at the bottom of the window (instead of modal pop-ups for good news). */
final class Toast {
    private static StackPane host;

    private Toast() {}

    static void attach(StackPane root) { host = root; }

    static void show(String message) {
        if (host == null) return;
        Platform.runLater(() -> {
            Label l = new Label(message);
            l.getStyleClass().add("toast");
            l.setWrapText(true);
            l.setMaxWidth(560);
            StackPane.setAlignment(l, Pos.BOTTOM_CENTER);
            StackPane.setMargin(l, new Insets(0, 0, 28, 0));
            l.setFocusTraversable(false);
            host.getChildren().add(l);
            PauseTransition wait = new PauseTransition(Duration.seconds(4.5));
            wait.setOnFinished(e -> host.getChildren().remove(l));
            wait.play();
        });
    }
}
