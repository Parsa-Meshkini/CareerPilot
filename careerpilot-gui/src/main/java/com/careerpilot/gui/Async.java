package com.careerpilot.gui;

import com.careerpilot.common.DomainException;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;

import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs slow work (database, LLM calls) off the JavaFX thread and reports failures in a dialog. */
final class Async {
    private Async() {}

    static <T> void run(Node busy, Supplier<T> work, Consumer<T> onSuccess) {
        run(busy, work, onSuccess, Async::showError);
    }

    static <T> void run(Node busy, Supplier<T> work, Consumer<T> onSuccess, Consumer<Throwable> onError) {
        Task<T> task = new Task<>() {
            @Override protected T call() { return work.get(); }
        };
        if (busy != null) {
            busy.setDisable(true);
            busy.setCursor(Cursor.WAIT);
        }
        task.setOnSucceeded(e -> {
            release(busy);
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(e -> {
            release(busy);
            onError.accept(task.getException());
        });
        Thread t = new Thread(task, "careerpilot-gui-work");
        t.setDaemon(true);
        t.start();
    }

    static void run(Node busy, Runnable work, Runnable onSuccess) {
        run(busy, () -> { work.run(); return Boolean.TRUE; }, ok -> onSuccess.run());
    }

    private static void release(Node busy) {
        if (busy != null) {
            busy.setDisable(false);
            busy.setCursor(Cursor.DEFAULT);
        }
    }

    static void showError(Throwable t) {
        Platform.runLater(() -> {
            StringBuilder msg = new StringBuilder(t.getMessage() == null ? t.toString() : t.getMessage());
            if (t instanceof DomainException d && d.details().get("fields") instanceof Map<?, ?> fields)
                fields.forEach((k, v) -> msg.append("\n  ").append(k).append(": ").append(v));
            if (t instanceof DomainException d && d.details().get("allowed_actions") != null)
                msg.append("\nAllowed now: ").append(d.details().get("allowed_actions"));
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("CareerPilot");
            alert.setHeaderText(null);
            alert.getDialogPane().setContent(new Label(msg.toString()));
            alert.getButtonTypes().setAll(ButtonType.OK);
            alert.showAndWait();
        });
    }

    static void info(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("CareerPilot");
        alert.setHeaderText(null);
        alert.getDialogPane().setContent(new Label(message));
        alert.showAndWait();
    }
}
