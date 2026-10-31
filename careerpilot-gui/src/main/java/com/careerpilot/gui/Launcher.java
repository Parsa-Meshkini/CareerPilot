package com.careerpilot.gui;

import javafx.application.Application;

/** Plain main class (not an Application subclass) so the packaged jar starts without module-path tricks. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) { Application.launch(CareerPilotApp.class, args); }
}
