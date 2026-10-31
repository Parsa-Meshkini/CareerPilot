package com.careerpilot.gui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Non-blocking messages at the bottom of the window. Confirmations fade after 6 s but pause while the pointer or keyboard focus is on them
 * and always have a close button; errors stay until dismissed (HIG accessibility.md: avoid time-boxed interface elements).
 */
final class Toast {
    private static VBox stack;

    private Toast() {}

    static void attach(StackPane root) {
        stack = new VBox(8);
        stack.setAlignment(Pos.BOTTOM_CENTER);
        stack.setPickOnBounds(false);
        stack.setPadding(new Insets(0, 24, 24, 24));
        StackPane.setAlignment(stack, Pos.BOTTOM_CENTER);
        root.getChildren().add(stack);
    }

    static void show(String message) { post(message, false); }

    static void error(String message) { post(message, true); }

    private static void post(String message, boolean error) {
        Platform.runLater(() -> {
            if (stack == null) return;
            Label text = new Label(message);
            text.setWrapText(true);
            text.setMaxWidth(520);
            HBox.setHgrow(text, Priority.ALWAYS);
            Button close = new Button("", Icons.box("Close", 14));
            close.getStyleClass().addAll("quiet", "icon-button");
            close.setAccessibleText("Dismiss message");
            HBox box = new HBox(10, Icons.box(error ? "Alert" : "Check", 16), text, close);
            box.setAlignment(Pos.CENTER_LEFT);
            box.getStyleClass().add("toast");
            box.setMaxWidth(Region_USE_PREF);
            box.setAccessibleRole(javafx.scene.AccessibleRole.TEXT);
            box.setAccessibleText((error ? "Error: " : "") + message);
            Runnable dismiss = () -> stack.getChildren().remove(box);
            close.setOnAction(e -> dismiss.run());
            stack.getChildren().add(box);
            if (!error) {
                PauseTransition wait = new PauseTransition(Duration.seconds(6));
                wait.setOnFinished(e -> dismiss.run());
                box.hoverProperty().addListener((o, was, now) -> { if (now) wait.pause(); else wait.playFromStart(); });
                close.focusedProperty().addListener((o, was, now) -> { if (now) wait.pause(); else wait.playFromStart(); });
                wait.play();
            }
        });
    }

    private static final double Region_USE_PREF = javafx.scene.layout.Region.USE_PREF_SIZE;
}
