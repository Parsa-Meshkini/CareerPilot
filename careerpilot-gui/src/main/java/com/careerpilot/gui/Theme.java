package com.careerpilot.gui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;

import java.util.prefs.Preferences;

/** Light and dark themes (AtlantaFX Primer) with the CareerPilot brand layer in styles.css on top. The choice is remembered. */
final class Theme {
    private static final Preferences PREFS = Preferences.userNodeForPackage(Theme.class);
    private static boolean dark = PREFS.getBoolean("dark", false);

    private Theme() {}

    static boolean isDark() { return dark; }

    static void apply() {
        Application.setUserAgentStylesheet((dark ? new PrimerDark() : new PrimerLight()).getUserAgentStylesheet());
    }

    static void toggle() {
        dark = !dark;
        PREFS.putBoolean("dark", dark);
        apply();
    }
}
