package com.careerpilot.gui;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Parent;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

/**
 * Light and dark appearance. The app follows the operating system (HIG dark-mode.md: "Avoid offering an app-specific appearance setting"),
 * and Settings offers a manual override for platforms where the system setting cannot be read. AtlantaFX Primer supplies the built-in
 * control styles; tokens.css and components.css (this app's design tokens) sit on top. JavaFX has no ":dark" pseudo-class, so the
 * dark tokens are switched with the style class {@value #DARK_CLASS} on each scene root.
 */
final class Theme {
    enum Mode { SYSTEM, LIGHT, DARK }

    static final String DARK_CLASS = "cp-dark";
    private static final Preferences PREFS = Preferences.userNodeForPackage(Theme.class);
    private static final List<WeakReference<Parent>> ROOTS = new ArrayList<>();
    private static Mode mode = readMode();
    private static boolean systemDark = detectSystemDark();

    private Theme() {}

    private static Mode readMode() {
        try {
            return Mode.valueOf(PREFS.get("appearance", Mode.SYSTEM.name()));
        } catch (IllegalArgumentException e) {
            return Mode.SYSTEM;
        }
    }

    static Mode mode() { return mode; }

    /** Whether the dark appearance is in effect now (the override, or the operating system when the mode is SYSTEM). */
    static boolean isDark() { return mode == Mode.DARK || (mode == Mode.SYSTEM && systemDark); }

    /** The stylesheets every scene and dialog loads, in order. */
    static List<String> stylesheets() {
        return List.of(Theme.class.getResource("/gui/tokens.css").toExternalForm(), Theme.class.getResource("/gui/components.css").toExternalForm());
    }

    /** Sets the Primer base theme for the current appearance and refreshes every attached root. */
    static void apply() {
        Application.setUserAgentStylesheet((isDark() ? new PrimerDark() : new PrimerLight()).getUserAgentStylesheet());
        ROOTS.removeIf(r -> r.get() == null);
        ROOTS.forEach(r -> mark(r.get()));
    }

    /** Chooses the appearance. {@code persist} is false for previews and the screenshot test, so a developer's saved choice is untouched. */
    static void setMode(Mode m, boolean persist) {
        mode = m;
        if (persist) PREFS.put("appearance", m.name());
        apply();
    }

    static void setMode(Mode m) { setMode(m, true); }

    /** Kept so the current sidebar button compiles until the Phase 2 shell replaces it. */
    static void toggle() { setMode(isDark() ? Mode.LIGHT : Mode.DARK); }

    /** Style classes and stylesheets for a scene root or a dialog pane. */
    static void attach(Parent root) {
        ROOTS.add(new WeakReference<>(root));
        mark(root);
    }

    private static void mark(Parent root) {
        root.getStyleClass().remove(DARK_CLASS);
        if (isDark()) root.getStyleClass().add(DARK_CLASS);
    }

    /** Re-reads the operating system appearance (called when the window regains focus, so a change made in System Settings is picked up). */
    static void refreshSystem() {
        if (mode != Mode.SYSTEM) return;
        Thread t = new Thread(() -> {
            boolean now = detectSystemDark();
            Platform.runLater(() -> {
                if (now != systemDark) {
                    systemDark = now;
                    apply();
                }
            });
        }, "careerpilot-appearance");
        t.setDaemon(true);
        t.start();
    }

    private static boolean detectSystemDark() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("mac")) return run("defaults", "read", "-g", "AppleInterfaceStyle").trim().equalsIgnoreCase("dark");
            if (os.contains("win"))
                return run("reg", "query", "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "/v", "AppsUseLightTheme").contains("0x0");
        } catch (Exception e) {
            // unreadable: fall back to light
        }
        return false;
    }

    private static String run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        p.waitFor(2, TimeUnit.SECONDS);
        return out;
    }

    /** True when the operating system asks for reduced motion; animations are then skipped. */
    static boolean reduceMotion() { return REDUCE_MOTION; }

    private static final boolean REDUCE_MOTION = detectReduceMotion();

    private static boolean detectReduceMotion() {
        if (Boolean.getBoolean("careerpilot.reduceMotion")) return true;
        if (!System.getProperty("os.name", "").toLowerCase().contains("mac")) return false;
        try {
            return run("defaults", "read", "com.apple.universalaccess", "reduceMotion").trim().equals("1");
        } catch (Exception e) {
            return false;
        }
    }
}
