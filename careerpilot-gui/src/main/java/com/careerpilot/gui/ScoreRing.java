package com.careerpilot.gui;

import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;

/**
 * The bearing ring: a 270 degree gauge with the match score in the middle. The colour follows the band, and the band is also written
 * as a word beside it by the callers (see {@link #word}), so colour never carries the meaning alone. Outdated scores get a dashed track.
 */
final class ScoreRing extends StackPane {
    ScoreRing(Integer score, boolean outdated, double size) {
        double stroke = size >= 56 ? 6 : 3.5;
        double radius = (size - stroke) / 2;
        Arc track = arc(size / 2, radius, 270, stroke, "ring-track");
        if (outdated) track.getStyleClass().add("outdated");
        Arc fill = arc(size / 2, radius, score == null ? 0 : -270.0 * Math.max(0, Math.min(100, score)) / 100, stroke, "ring-fill", "ring-" + band(score));
        Label number = new Label(score == null ? "–" : String.valueOf(score));
        number.getStyleClass().addAll("ring-number", size >= 56 ? "big" : "small");
        javafx.scene.layout.Pane gauge = new javafx.scene.layout.Pane(track, fill);
        gauge.setPrefSize(size, size);
        gauge.setMaxSize(size, size);
        getChildren().addAll(gauge, number);
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        setAccessibleText(score == null ? "Not scored yet" : "Match score " + score + " of 100, " + word(score) + (outdated ? ", outdated" : ""));
    }

    private static Arc arc(double center, double radius, double length, double stroke, String... classes) {
        Arc a = new Arc(center, center, radius, radius, 225, length == 270 ? -270 : length);
        a.setType(ArcType.OPEN);
        a.setStrokeWidth(stroke);
        a.getStyleClass().addAll(classes);
        return a;
    }

    /** good (70 and up), fair (45 to 69), low, or none when not scored. */
    static String band(Integer score) { return score == null ? "none" : score >= 70 ? "good" : score >= 45 ? "fair" : "low"; }

    static String word(Integer score) {
        return switch (band(score)) {
            case "good" -> "Strong fit";
            case "fair" -> "Fair fit";
            case "low" -> "Low fit";
            default -> "Not scored";
        };
    }
}
