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
import javafx.scene.layout.StackPane;
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
        String glyph = switch (kind) { case "success" -> "Check"; case "warning", "danger" -> "Alert"; case "info" -> "Info"; default -> null; };
        if (glyph != null) l.setGraphic(Icons.box(glyph, 11));  // a glyph as well as colour, so state is never colour alone
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

    /** An empty state: icon, one line saying what is missing, one line saying what to do, optional actions. */
    static VBox emptyState(String icon, String title, String body, Node... actions) {
        StackPane wrap = new StackPane(Icons.box(icon, 24));
        wrap.getStyleClass().add("empty-icon-wrap");
        Label t = new Label(title);
        t.getStyleClass().add("empty-title");
        Label b = new Label(body);
        b.getStyleClass().add("empty-body");
        b.setWrapText(true);
        b.setMaxWidth(380);
        VBox box = new VBox(10, wrap, t, b);
        if (actions.length > 0) {
            HBox buttons = row(actions);
            buttons.setAlignment(Pos.CENTER);
            box.getChildren().add(buttons);
        }
        box.getStyleClass().add("empty-state");
        box.setAlignment(Pos.CENTER);
        return box;
    }

    /** A clickable summary tile (a real button, so it is reachable by keyboard). */
    static Button statTile(String number, String caption, String context, Runnable action) {
        return statTile(number, caption, context, null, "blue", action);
    }

    /** Stat tile with a tinted icon badge. tone: blue, violet, teal, green, amber or red. */
    static Button statTile(String number, String caption, String context, String icon, String tone, Runnable action) {
        Label n = new Label(number);
        n.getStyleClass().add("stat-number");
        Label c = new Label(caption);
        c.getStyleClass().add("stat-caption");
        Label x = new Label(context);
        x.getStyleClass().add("stat-context");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, n, spacer);
        top.setAlignment(Pos.CENTER_LEFT);
        if (icon != null) top.getChildren().add(badge(icon, tone));
        VBox box = new VBox(2, top, c, x);
        Button b = new Button("", box);
        b.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        b.getStyleClass().add("stat-tile");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAccessibleText(number + " " + caption + ". " + context);
        b.setOnAction(e -> action.run());
        return b;
    }

    /** A rounded square with an icon in a soft tint of one hue. Decorative; the text beside it carries the meaning. */
    static StackPane badge(String icon, String tone) {
        StackPane p = new StackPane(Icons.box(icon, 16));
        p.getStyleClass().addAll("tile-badge", "tone-" + tone);
        p.setFocusTraversable(false);
        return p;
    }

    /** Gradient welcome banner: a small label, a headline, one sentence of status and up to two actions. */
    static HBox hero(String eyebrow, String headline, String line, String glyph, Button... actions) {
        Label e = new Label(eyebrow);
        e.getStyleClass().add("hero-eyebrow");
        Label h = new Label(headline);
        h.getStyleClass().add("hero-headline");
        Label l = new Label(line);
        l.getStyleClass().add("hero-line");
        l.setWrapText(true);
        HBox buttons = new HBox(10);
        buttons.getChildren().addAll(actions);
        VBox text = new VBox(4, e, h, l);
        if (actions.length > 0) { VBox.setMargin(buttons, new Insets(10, 0, 0, 0)); text.getChildren().add(buttons); }
        HBox.setHgrow(text, Priority.ALWAYS);
        StackPane g = new StackPane(Icons.box(glyph, 30));
        g.getStyleClass().add("hero-glyph");
        HBox box = new HBox(18, text, g);
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("hero-banner");
        return box;
    }

    /** Hero button: kind is "hero-primary" (white) or "hero-ghost" (translucent). */
    static Button heroButton(String text, boolean primary, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add(primary ? "hero-primary" : "hero-ghost");
        b.setOnAction(e -> action.run());
        return b;
    }

    /** One row of a "needs you" queue. kind: urgent, info or danger (the dot colour; the hint text says the same in words). */
    static Button queueRow(String title, String hint, String kind, Runnable action) {
        Region dot = new Region();
        dot.getStyleClass().addAll("queue-dot", kind);
        Label t = new Label(title);
        t.getStyleClass().add("queue-title");
        t.setMinWidth(0);
        h_ellipsis(t);
        Label h = new Label(hint);
        h.getStyleClass().add("queue-hint");
        h.setMinWidth(0);
        h_ellipsis(h);
        VBox text = new VBox(2, t, h);
        HBox.setHgrow(text, Priority.ALWAYS);
        StackPane chev = new StackPane(Icons.box("Chevron", 14));
        chev.getStyleClass().add("chevron");
        HBox box = new HBox(12, dot, text, chev);
        box.setAlignment(Pos.CENTER_LEFT);
        Button b = new Button("", box);
        b.setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
        b.getStyleClass().add("queue-row");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setMaxHeight(Region.USE_PREF_SIZE);
        b.setAccessibleText(title + ". " + hint);
        b.setOnAction(e -> action.run());
        return b;
    }

    /** Small grey section label used above groups of content. */
    static Label section(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section-label");
        return l;
    }

    private static void h_ellipsis(Label l) { l.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS); }

    static Region skeleton(double width, double height) {
        Region r = new Region();
        r.getStyleClass().add("skeleton");
        r.setPrefSize(width, height);
        r.setMinHeight(height);
        return r;
    }

    /** Wraps a page body so it scrolls vertically and always uses the full width. */
    static javafx.scene.control.ScrollPane scroll(Node body) {
        javafx.scene.control.ScrollPane sp = new javafx.scene.control.ScrollPane(body);
        sp.setFitToWidth(true);
        sp.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(sp, Priority.ALWAYS);
        return sp;
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
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
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
