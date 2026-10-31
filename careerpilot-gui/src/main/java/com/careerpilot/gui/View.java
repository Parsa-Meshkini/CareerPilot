package com.careerpilot.gui;

import javafx.geometry.Insets;
import javafx.scene.layout.VBox;

/** A page of the main window. {@link #refresh()} reloads its data from the core services. */
abstract class View extends VBox {
    protected final AppContext app;

    protected View(AppContext app) {
        super(12);
        this.app = app;
        setPadding(new Insets(18));
        getStyleClass().add("page");
    }

    abstract String name();

    abstract void refresh();

    /** Selects the first row of the page's main list (used by the screenshot test and keyboard shortcuts). */
    void selectFirstRow() { }
}
