package com.careerpilot.gui;

import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;

import java.util.Map;

/** Simple filled SVG icons (24x24 grid), coloured by CSS so they follow the theme. No emoji as icons. */
final class Icons {
    private static final Map<String, String> PATHS = Map.ofEntries(
            Map.entry("Dashboard", "M3 3h8v8H3zM13 3h8v5h-8zM13 10h8v11h-8zM3 13h8v8H3z"),
            Map.entry("Profile", "M12 12a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9zM3.5 21c0-4.2 3.8-7 8.5-7s8.5 2.8 8.5 7z"),
            Map.entry("Jobs", "M9 3h6a1 1 0 0 1 1 1v2h4a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h4V4a1 1 0 0 1 1-1zm1 3h4V5h-4z"),
            Map.entry("Tracker", "M9 2h6a1 1 0 0 1 1 1v1h3a1 1 0 0 1 1 1v16a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1h3V3a1 1 0 0 1 1-1zm1 2v2h4V4zM8 11h8v2H8zm0 4h8v2H8z"),
            Map.entry("Interview", "M4 4h16a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H9l-5 4v-4a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1zm3 4v2h10V8zm0 4v2h6v-2z"),
            Map.entry("Agent", "M13 2 4 14h7l-1 8 9-12h-7z"),
            Map.entry("Email", "M3 5h18a1 1 0 0 1 1 1v12a1 1 0 0 1-1 1H3a1 1 0 0 1-1-1V6a1 1 0 0 1 1-1zm1.8 2L12 12.6 19.2 7z"),
            Map.entry("Insights", "M4 20V10h4v10zm6 0V4h4v16zm6 0v-7h4v7z"),
            Map.entry("Settings", "M4 6h10v2H4zm12-1h4v4h-4zM4 11h4v2H4zm6-1h10v4H10zM4 16h10v2H4zm12-1h4v4h-4z"),
            Map.entry("Moon", "M20 14.5A8 8 0 0 1 9.5 4 8 8 0 1 0 20 14.5z"),
            Map.entry("Sun", "M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10zM11 1h2v3h-2zm0 19h2v3h-2zM1 11h3v2H1zm19 0h3v2h-3zM4.2 5.6l1.4-1.4 2.1 2.1-1.4 1.4zm12.1 12.1 1.4-1.4 2.1 2.1-1.4 1.4zM5.6 19.8l-1.4-1.4 2.1-2.1 1.4 1.4zM17.7 7.7l-1.4-1.4 2.1-2.1 1.4 1.4z"),
            Map.entry("Link", "M10 6H6a3 3 0 0 0-3 3v6a3 3 0 0 0 3 3h6a3 3 0 0 0 3-3v-2h-2v2a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1h4zm4-2v2h2.6l-6.3 6.3 1.4 1.4L18 7.4V10h2V4z"));

    private Icons() {}

    static SVGPath of(String name) {
        SVGPath p = new SVGPath();
        p.setContent(PATHS.getOrDefault(name, PATHS.get("Dashboard")));
        p.setFillRule(FillRule.EVEN_ODD);
        p.getStyleClass().add("icon");
        p.setScaleX(0.75);
        p.setScaleY(0.75);
        return p;
    }

    /** Wraps the icon in a fixed 18x18 box so scaling does not disturb layout. */
    static javafx.scene.layout.StackPane box(String name) {
        javafx.scene.layout.StackPane s = new javafx.scene.layout.StackPane(of(name));
        s.setMinSize(18, 18);
        s.setPrefSize(18, 18);
        s.setMaxSize(18, 18);
        return s;
    }
}
