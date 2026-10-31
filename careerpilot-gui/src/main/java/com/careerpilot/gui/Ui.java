package com.careerpilot.gui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.util.List;
import java.util.function.Function;

/** Small widget factories so the views stay short and look the same. */
final class Ui {
    private Ui() {}

    static Label title(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("title");
        return l;
    }

    static Label heading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("heading");
        return l;
    }

    /** A label that never shrinks to an ellipsis inside a narrow pane. */
    static Label label(String text) {
        Label l = new Label(text);
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    /** Page title with a one-line explanation underneath. */
    static VBox pageHeader(String title, String subtitle) {
        Label t = title(title);
        Label sub = muted(subtitle);
        VBox box = new VBox(2, t, sub);
        box.getStyleClass().add("page-header");
        return box;
    }

    /** Small rounded status label. kind: success, warning, danger, info or neutral. */
    static Label chip(String text, String kind) {
        Label l = new Label(text);
        l.getStyleClass().addAll("chip", "chip-" + kind);
        l.setMinWidth(Region.USE_PREF_SIZE);
        return l;
    }

    static String stateKind(String state) {
        return switch (state) {
            case "OFFER", "ACCEPTED", "PUBLISHED", "CONFIRMED", "COMPLETED", "PASSED", "verified", "FULL", "SHORTLISTED" -> "success";
            case "INTERVIEW", "RUNNING", "APPLIED", "PREPARED" -> "info";
            case "AWAITING_APPROVAL", "AWAITING_INPUT", "NEEDS_REVIEW", "NEEDS_REVIEW ", "to review", "needs a fix", "PAUSED", "SUMMARY" -> "warning";
            case "REJECTED", "DECLINED", "FAILED", "ABORTED", "WITHDRAWN" -> "danger";
            default -> "neutral";
        };
    }

    /** A table column that shows its value as a coloured chip. */
    static <T> TableColumn<T, String> chipCol(String title, double width, Function<T, String> value) {
        TableColumn<T, String> c = col(title, width, value);
        c.setCellFactory(column -> new javafx.scene.control.TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setGraphic(null);
                    setText(null);
                } else {
                    setText(null);
                    setGraphic(chip(item.replace('_', ' ').toLowerCase(), stateKind(item)));
                }
            }
        });
        return c;
    }

    /** A bordered card with an optional heading. */
    static VBox card(String heading, Node... content) {
        VBox box = new VBox(8);
        box.getStyleClass().add("card");
        if (heading != null) box.getChildren().add(heading(heading));
        box.getChildren().addAll(content);
        return box;
    }

    static Label muted(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("muted");
        l.setWrapText(true);
        return l;
    }

    static Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.setOnAction(e -> action.run());
        return b;
    }

    static Button primary(String text, Runnable action) {
        Button b = button(text, action);
        b.getStyleClass().add("primary");
        return b;
    }

    static HBox row(Node... nodes) {
        HBox h = new HBox(8, nodes);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    static VBox column(Node... nodes) {
        VBox v = new VBox(10, nodes);
        v.setPadding(new Insets(0));
        return v;
    }

    static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }

    static <T> TableColumn<T, String> col(String title, double width, Function<T, String> value) {
        TableColumn<T, String> c = new TableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(value.apply(cell.getValue())));
        return c;
    }

    @SafeVarargs
    static <T> TableView<T> table(TableColumn<T, String>... columns) {
        TableView<T> t = new TableView<>();
        t.getColumns().addAll(columns);
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        t.setPlaceholder(new Label("Nothing here yet"));
        VBox.setVgrow(t, Priority.ALWAYS);
        return t;
    }

    static <T> void fill(TableView<T> table, List<T> items) { table.setItems(FXCollections.observableArrayList(items)); }

    static TextArea readOnlyText() {
        TextArea t = new TextArea();
        t.setEditable(false);
        t.setWrapText(true);
        VBox.setVgrow(t, Priority.ALWAYS);
        return t;
    }

    static File openFile(Window owner, String title, String description, String... extensions) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(description, extensions));
        return fc.showOpenDialog(owner);
    }

    static File saveFile(Window owner, String title, String initialName) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        fc.setInitialFileName(initialName);
        return fc.showSaveDialog(owner);
    }

    /** Loads the app's stylesheets and theme class into a dialog so it matches the main window. */
    static void style(javafx.scene.control.Dialog<?> dialog) {
        javafx.scene.control.DialogPane pane = dialog.getDialogPane();
        pane.getStylesheets().addAll(Theme.stylesheets());
        Theme.attach(pane);
    }

    static String id8(Object id) { return id == null ? "" : id.toString().substring(0, 8); }
}
