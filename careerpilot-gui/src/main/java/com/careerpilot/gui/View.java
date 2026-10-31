package com.careerpilot.gui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.layout.VBox;

import java.util.List;

/** A page of the main window. {@link #refresh()} reloads its data from the core services. */
abstract class View extends VBox {
    protected final AppContext app;
    private final BooleanProperty loading = new SimpleBooleanProperty();

    protected View(AppContext app) {
        super(16);
        this.app = app;
        setPadding(new Insets(8, 24, 24, 24));
        getStyleClass().addAll("page", "page-body");
    }

    abstract String name();

    abstract void refresh();

    /** One line under the page title in the top bar. */
    String subtitle() { return ""; }

    /** Context actions shown at the trailing end of the top bar. */
    List<Node> actions() { return List.of(); }

    /** True while background work for this page is running; the top bar shows a thin progress bar and the page stays usable. */
    BooleanProperty loadingProperty() { return loading; }

    /** Selects the first row of the page's main list (used by the screenshot test and keyboard shortcuts). */
    void selectFirstRow() { }
}
