package com.careerpilot.gui;

import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * The main window: a grouped sidebar (collapsible to icons), a top bar per page with its title and context actions, the page itself,
 * a menu bar (the system menu bar on macOS) and a command palette. Every navigation command is reachable from the keyboard.
 */
final class MainView extends StackPane {
    private static final double SIDEBAR_WIDE = 224, SIDEBAR_NARROW = 56;
    private static final Preferences PREFS = Preferences.userNodeForPackage(MainView.class);
    /** Sidebar sections, in order. The position in this list is also the Cmd/Ctrl+number shortcut. */
    private static final Map<String, List<String>> GROUPS = new LinkedHashMap<>();

    static {
        GROUPS.put("Overview", List.of("Dashboard", "Insights"));
        GROUPS.put("Pipeline", List.of("Jobs", "Tracker", "Email"));
        GROUPS.put("Prepare", List.of("Profile", "Interview", "Agent"));
        GROUPS.put("System", List.of("Settings"));
    }

    private final Map<String, View> pages = new LinkedHashMap<>();
    private final Map<String, Button> buttons = new LinkedHashMap<>();
    private final List<Node> expandedOnly = new ArrayList<>();
    private final BorderPane layout = new BorderPane();
    private final VBox sidebar = new VBox(2);
    private final Label title = new Label();
    private final Label subtitle = new Label();
    private final HBox actionsBox = new HBox(8);
    private final ProgressBar loadingBar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
    private final StackPane content = new StackPane();
    private final Button sidebarToggle = new Button("", Icons.box("Sidebar"));
    private boolean collapsed = PREFS.getBoolean("sidebarCollapsed", false);
    private View current;
    private javafx.beans.value.ChangeListener<Boolean> loadingListener;

    MainView(AppContext app, Runnable onSignOut) {
        getStyleClass().add("shell");
        Toast.attach(this);
        List<View> all = List.of(new DashboardView(app, this::open), new ProfileView(app), new JobsView(app, this::open), new TrackerView(app),
                new InterviewView(app), new AgentView(app), new EmailView(app), new InsightsView(app), new SettingsView(app, onSignOut));
        all.forEach(v -> pages.put(v.name(), v));

        buildSidebar(app);
        VBox right = new VBox(buildTopBar(), loadingBar, content);
        VBox.setVgrow(content, Priority.ALWAYS);
        content.getStyleClass().add("page-body");
        layout.setLeft(sidebar);
        layout.setCenter(right);
        layout.setTop(buildMenuBar());
        getChildren().add(0, layout);
        applyCollapsed(false);
        open("Dashboard");
    }

    View page(String name) { return pages.get(name); }

    javafx.scene.Node center() { return content.getChildren().isEmpty() ? null : content.getChildren().get(0); }

    // ---------- sidebar ----------

    private void buildSidebar(AppContext app) {
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(14, 10, 12, 10));
        Label word = new Label("CareerPilot");
        word.getStyleClass().add("brand-word");
        StackPane mark = new StackPane(Icons.box("Mark", 16));
        mark.getStyleClass().add("brand-mark");
        mark.setAccessibleText("CareerPilot");
        HBox brand = new HBox(10, mark, word);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setPadding(new Insets(0, 0, 10, 4));
        expandedOnly.add(word);
        sidebar.getChildren().add(brand);

        int n = 1;
        for (var group : GROUPS.entrySet()) {
            Label label = new Label(group.getKey());
            label.getStyleClass().add("nav-group-label");
            expandedOnly.add(label);
            sidebar.getChildren().add(label);
            for (String name : group.getValue()) {
                Button b = new Button(name, Icons.box(name));
                b.setMaxWidth(Double.MAX_VALUE);
                b.getStyleClass().add("nav-item");
                b.setAccessibleText(name);
                b.setTooltip(new Tooltip(name + "  (" + (System.getProperty("os.name").toLowerCase().contains("mac") ? "⌘" : "Ctrl+") + n + ")"));
                b.setOnAction(e -> open(name));
                b.setOnKeyPressed(e -> {  // arrow keys move through the list like a native sidebar
                    if (e.getCode() == KeyCode.DOWN || e.getCode() == KeyCode.UP) {
                        List<Button> list = new ArrayList<>(buttons.values());
                        int i = list.indexOf(b) + (e.getCode() == KeyCode.DOWN ? 1 : -1);
                        if (i >= 0 && i < list.size()) list.get(i).requestFocus();
                        e.consume();
                    }
                });
                buttons.put(name, b);
                sidebar.getChildren().add(b);
                n++;
            }
        }
        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);
        Label who = new Label(app.user().getEmail());
        who.getStyleClass().add("who");
        who.setMaxWidth(SIDEBAR_WIDE - 28);
        who.setPadding(new Insets(0, 0, 0, 6));
        expandedOnly.add(who);
        sidebar.getChildren().addAll(grow, who);
    }

    private Node buildTopBar() {
        sidebarToggle.getStyleClass().addAll("quiet", "icon-button");
        sidebarToggle.setAccessibleText("Show or hide the sidebar");
        sidebarToggle.setTooltip(new Tooltip("Show or hide the sidebar"));
        sidebarToggle.setOnAction(e -> toggleSidebar());
        title.getStyleClass().add("page-title");
        subtitle.getStyleClass().add("page-subtitle");
        subtitle.setMinWidth(0);
        VBox titles = new VBox(2, title, subtitle);
        titles.setAlignment(Pos.CENTER_LEFT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button go = new Button("Go to…", Icons.box("Search", 16));
        go.getStyleClass().add("quiet");
        go.setTooltip(new Tooltip("Jump to a page or run a command"));
        go.setOnAction(e -> showPalette());
        actionsBox.setAlignment(Pos.CENTER_RIGHT);
        HBox bar = new HBox(12, sidebarToggle, titles, spacer, actionsBox, go);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("topbar");
        loadingBar.getStyleClass().add("thin");
        loadingBar.setMaxWidth(Double.MAX_VALUE);
        loadingBar.setVisible(false);
        return bar;
    }

    private void toggleSidebar() {
        collapsed = !collapsed;
        PREFS.putBoolean("sidebarCollapsed", collapsed);
        applyCollapsed(true);
    }

    private void applyCollapsed(boolean animate) {
        double target = collapsed ? SIDEBAR_NARROW : SIDEBAR_WIDE;
        sidebar.setMinWidth(Region.USE_PREF_SIZE);
        sidebar.setMaxWidth(Region.USE_PREF_SIZE);
        if (!collapsed) expandedOnly.forEach(x -> { x.setVisible(true); x.setManaged(true); });
        buttons.values().forEach(b -> b.setContentDisplay(collapsed ? ContentDisplay.GRAPHIC_ONLY : ContentDisplay.LEFT));
        Runnable settle = () -> {
            if (collapsed) expandedOnly.forEach(x -> { x.setVisible(false); x.setManaged(false); });
        };
        if (animate && !Theme.reduceMotion()) {
            Timeline t = new Timeline(new KeyFrame(Duration.millis(160), new KeyValue(sidebar.prefWidthProperty(), target)));
            t.setOnFinished(e -> settle.run());
            t.play();
        } else {
            sidebar.setPrefWidth(target);
            settle.run();
        }
    }

    // ---------- menu bar ----------

    private MenuBar buildMenuBar() {
        boolean mac = System.getProperty("os.name").toLowerCase().contains("mac");
        KeyCombination.Modifier cmd = KeyCombination.SHORTCUT_DOWN;
        Menu go = new Menu("Go");
        int n = 1;
        for (var group : GROUPS.values()) {
            for (String name : group) {
                MenuItem item = new MenuItem(name);
                item.setAccelerator(new KeyCodeCombination(KeyCode.valueOf("DIGIT" + n), cmd));
                item.setOnAction(e -> open(name));
                go.getItems().add(item);
                n++;
            }
        }
        Menu view = new Menu("View");
        MenuItem side = new MenuItem("Show or Hide Sidebar");
        side.setAccelerator(new KeyCodeCombination(KeyCode.BACK_SLASH, cmd));
        side.setOnAction(e -> toggleSidebar());
        MenuItem palette = new MenuItem("Go to…");
        palette.setAccelerator(new KeyCodeCombination(KeyCode.K, cmd));
        palette.setOnAction(e -> showPalette());
        Menu appearance = new Menu("Appearance");
        ToggleGroup group = new ToggleGroup();
        for (Theme.Mode m : Theme.Mode.values()) {
            RadioMenuItem r = new RadioMenuItem(m.name().charAt(0) + m.name().substring(1).toLowerCase());
            r.setToggleGroup(group);
            r.setSelected(Theme.mode() == m);
            r.setOnAction(e -> Theme.setMode(m));
            appearance.getItems().add(r);
        }
        view.getItems().addAll(side, palette, appearance);
        MenuBar bar = new MenuBar(go, view);
        bar.setUseSystemMenuBar(true);
        if (mac) {
            bar.setManaged(false);
            bar.setVisible(false);
        }
        return bar;
    }

    // ---------- navigation ----------

    void open(String name) {
        View view = pages.get(name);
        if (view == null) return;
        buttons.forEach((n, b) -> {
            b.getStyleClass().remove("active");
            if (n.equals(name)) b.getStyleClass().add("active");
        });
        if (current != null && loadingListener != null) current.loadingProperty().removeListener(loadingListener);
        current = view;
        loadingListener = (o, was, now) -> loadingBar.setVisible(now);
        view.loadingProperty().addListener(loadingListener);
        loadingBar.setVisible(view.loadingProperty().get());
        title.setText(name);
        subtitle.setText(view.subtitle());
        subtitle.setVisible(!view.subtitle().isEmpty());
        subtitle.setManaged(!view.subtitle().isEmpty());
        view.actions().forEach(n -> { if (n instanceof Button b) b.setMinWidth(Region.USE_PREF_SIZE); });
        actionsBox.getChildren().setAll(view.actions());
        content.getChildren().setAll(view);
        view.refresh();
        if (Theme.reduceMotion()) return;
        view.setOpacity(0);
        view.setTranslateY(6);
        FadeTransition fade = new FadeTransition(Duration.millis(140), view);  // short, so it never feels slow
        fade.setToValue(1);
        TranslateTransition rise = new TranslateTransition(Duration.millis(140), view);
        rise.setToY(0);
        new ParallelTransition(fade, rise).play();
    }

    // ---------- command palette ----------

    private record Command(String label, Runnable action) {
        @Override public String toString() { return label; }
    }

    private void showPalette() {
        List<Command> all = new ArrayList<>();
        pages.keySet().forEach(p -> all.add(new Command("Go to " + p, () -> open(p))));
        all.add(new Command("Show or hide the sidebar", this::toggleSidebar));
        for (Theme.Mode m : Theme.Mode.values())
            all.add(new Command("Appearance: " + m.name().charAt(0) + m.name().substring(1).toLowerCase(), () -> Theme.setMode(m)));

        TextField query = new TextField();
        query.setPromptText("Type a page or command");
        query.setAccessibleText("Command search");
        ListView<Command> list = new ListView<>();
        list.getItems().setAll(all);
        list.getSelectionModel().selectFirst();
        list.setPrefHeight(Math.min(all.size(), 8) * 36 + 4);
        list.setFocusTraversable(false);
        VBox card = new VBox(8, query, list);
        card.getStyleClass().add("popup-card");
        card.setPadding(new Insets(12));
        card.setMaxWidth(520);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane scrim = new StackPane(card);
        scrim.getStyleClass().add("scrim");
        scrim.setAlignment(Pos.TOP_CENTER);
        StackPane.setMargin(card, new Insets(96, 0, 0, 0));
        Runnable close = () -> getChildren().remove(scrim);
        scrim.setOnMouseClicked(e -> { if (e.getTarget() == scrim) close.run(); });
        Runnable run = () -> {
            Command c = list.getSelectionModel().getSelectedItem();
            close.run();
            if (c != null) c.action().run();
        };
        query.textProperty().addListener((o, a, q) -> {
            list.getItems().setAll(all.stream().filter(c -> c.label().toLowerCase().contains(q.trim().toLowerCase())).toList());
            list.getSelectionModel().selectFirst();
        });
        query.setOnKeyPressed(e -> {
            int i = list.getSelectionModel().getSelectedIndex();
            switch (e.getCode()) {
                case ESCAPE -> close.run();
                case ENTER -> run.run();
                case DOWN -> list.getSelectionModel().select(Math.min(i + 1, list.getItems().size() - 1));
                case UP -> list.getSelectionModel().select(Math.max(i - 1, 0));
                default -> { return; }
            }
            e.consume();
        });
        list.setOnMouseClicked(e -> { if (e.getClickCount() == 1) run.run(); });
        getChildren().add(scrim);
        query.requestFocus();
    }
}
