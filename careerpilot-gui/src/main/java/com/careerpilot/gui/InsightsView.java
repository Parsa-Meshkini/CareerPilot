package com.careerpilot.gui;

import com.careerpilot.analytics.AnalyticsService;
import com.careerpilot.analytics.GroupStats;
import com.careerpilot.analytics.StrategyAdjustment;
import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationService;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * F19: the student's own outcome statistics and proposed strategy adjustments they can accept, reject or revert.
 * Charts follow the ui-ux-pro-max guidance: horizontal bars to compare rates across a few groups, values written on the bars
 * (never colour alone), and the numbers are always in the labels, so the chart has a text equivalent.
 */
final class InsightsView extends View {
    private final VBox body = new VBox(18);
    private final VBox proposals = new VBox(10);
    private final TableView<StrategyAdjustment> history = Ui.table(Ui.chipCol("Status", 120, a -> a.getStatus().name()), Ui.col("Weight", 100, StrategyAdjustment::getFactor),
            Ui.col("Change", 120, a -> String.format("%.2f → %.2f", a.getOldValue(), a.getNewValue())), Ui.col("Why", 420, StrategyAdjustment::getRationale));

    InsightsView(AppContext app) {
        super(app);
        history.setPrefHeight(170);
        history.setPlaceholder(Ui.muted("No adjustments yet. Proposals you accept or reject are listed here, and accepted ones can be reverted."));
        getChildren().add(Ui.scroll(body));
    }

    @Override String name() { return "Insights"; }

    @Override String subtitle() { return "From your own applications. Scoring changes are only proposed: you decide."; }

    @Override
    java.util.List<Node> actions() { return List.of(Ui.button("Refresh", this::refresh)); }

    @Override
    void refresh() {
        Async.run(this, () -> new Object[]{app.bean(AnalyticsService.class).getInsights(app.user()), app.bean(AnalyticsService.class).history(app.user()),
                app.bean(ApplicationService.class).listApplications(app.user(), null)}, d -> {
            AnalyticsService.InsightReport r = (AnalyticsService.InsightReport) d[0];
            @SuppressWarnings("unchecked") List<StrategyAdjustment> hist = (List<StrategyAdjustment>) d[1];
            @SuppressWarnings("unchecked") List<Application> apps = (List<Application>) d[2];
            body.getChildren().clear();

            HBox tiles = new HBox(12, plainTile(String.valueOf(r.stats().applied()), "Applied", "applications submitted"),
                    plainTile(String.valueOf(r.stats().withOutcome()), "With an outcome", "response, interview or decision"),
                    plainTile(r.stats().medianDaysToResponse() == null ? "–" : r.stats().medianDaysToResponse() + "d", "Median time to response", "from applying to the first reply"));
            body.getChildren().add(tiles);
            Label story = new Label(r.narrative());
            story.setWrapText(true);
            body.getChildren().add(Ui.card("What your data says", story));

            if (r.stats().withOutcome() == 0) {
                body.getChildren().add(Ui.card(null, Ui.emptyState("Insights", "Insights unlock with outcomes",
                        "After about 10 applications have a result, charts and scoring suggestions appear here. You have " + r.stats().withOutcome() + " so far.")));
            } else {
                body.getChildren().add(legend());
                addChart("By match score", r.stats().byScoreBand());
                addChart("By source", r.stats().bySource());
                addChart("By role category", r.stats().byRoleCategory());
            }
            body.getChildren().add(pipeline(apps));

            proposals.getChildren().clear();
            if (r.proposals().isEmpty()) proposals.getChildren().add(Ui.muted(r.enoughData() ? "No change is justified by your data right now." : "No proposals yet. They appear once there is enough data."));
            for (StrategyAdjustment p : r.proposals()) {
                Label text = new Label(p.getFactor() + " weight " + String.format("%.2f → %.2f", p.getOldValue(), p.getNewValue()) + ": " + p.getRationale());
                text.setWrapText(true);
                proposals.getChildren().add(Ui.card(null, text, Ui.row(Ui.primary("Accept", () -> decide(p, true)), Ui.button("Reject", () -> decide(p, false)))));
            }
            body.getChildren().add(new VBox(10, Ui.heading("Proposed adjustments"), proposals));
            Ui.fill(history, new ArrayList<>(hist.stream().filter(a -> a.getStatus() != StrategyAdjustment.Status.SUPERSEDED).toList()));
            VBox hv = new VBox(10, Ui.heading("History"), history, Ui.button("Revert selected", this::revert));
            body.getChildren().add(hv);
        });
    }

    private static Node plainTile(String number, String caption, String context) {
        Label n = new Label(number);
        n.getStyleClass().add("stat-number");
        Label c = new Label(caption);
        c.getStyleClass().add("stat-caption");
        Label x = new Label(context);
        x.getStyleClass().add("stat-context");
        VBox v = new VBox(2, n, c, x);
        v.getStyleClass().add("card");
        HBox.setHgrow(v, Priority.ALWAYS);
        v.setMaxWidth(Double.MAX_VALUE);
        v.setAccessibleText(number + " " + caption + ", " + context);
        return v;
    }

    private static Node legend() {
        Region a = new Region(), b = new Region();
        a.getStyleClass().addAll("legend-swatch", "primary");
        b.getStyleClass().addAll("legend-swatch", "secondary");
        Label la = new Label("Got a response"), lb = new Label("Reached an interview");
        la.getStyleClass().add("caption");
        lb.getStyleClass().add("caption");
        HBox h = new HBox(8, a, la, new Region() {{ setMinWidth(12); }}, b, lb);
        h.setAlignment(Pos.CENTER_LEFT);
        return h;
    }

    /** A card with one row per group: name and sample size, two bars with the percentage written at the end of each. */
    private void addChart(String title, List<GroupStats> groups) {
        if (groups.isEmpty()) return;
        VBox rows = new VBox(14);
        for (GroupStats g : groups) {
            Label name = new Label(g.label());
            name.getStyleClass().add("body-strong");
            Label n = new Label("n=" + g.applied());
            n.getStyleClass().add("caption");
            HBox head = new HBox(8, name, n);
            head.setAlignment(Pos.CENTER_LEFT);
            long resp = Math.round(g.responseRate() * 100), iv = Math.round(g.interviewRate() * 100);
            VBox row = new VBox(5, head, bar(g.responseRate(), resp + "%", false), bar(g.interviewRate(), iv + "%", true));
            row.setAccessibleText(g.label() + ", " + g.applied() + " applications: " + resp + " percent responded, " + iv + " percent reached an interview");
            rows.getChildren().add(row);
        }
        VBox card = Ui.card(title, rows);
        body.getChildren().add(card);
    }

    private static Node bar(double rate, String text, boolean second) {
        StackPane track = new StackPane();
        track.getStyleClass().add("bar-track");
        track.setAlignment(Pos.CENTER_LEFT);
        Region fill = new Region();
        fill.getStyleClass().add("bar-fill");
        if (second) fill.getStyleClass().add("second");
        fill.setMaxWidth(Region.USE_PREF_SIZE);
        fill.prefWidthProperty().bind(track.widthProperty().multiply(Math.max(0, Math.min(1, rate))));
        fill.setPrefHeight(10);
        track.getChildren().add(fill);
        Label value = new Label(text);
        value.getStyleClass().addAll("mono");
        value.setMinWidth(40);
        value.setAlignment(Pos.CENTER_RIGHT);
        HBox h = new HBox(10, track, value);
        h.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(track, Priority.ALWAYS);
        return h;
    }

    /** Where applications are right now. Bars, not a funnel: an application leaves the pipeline at any stage, so the counts do not shrink in order. */
    private Node pipeline(List<Application> apps) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String s : List.of("SAVED", "PREPARED", "APPLIED", "INTERVIEW", "OFFER")) counts.put(s, 0);
        int closed = 0;
        for (Application a : apps) {
            if (counts.containsKey(a.getStateName())) counts.merge(a.getStateName(), 1, Integer::sum);
            else closed++;
        }
        int max = Math.max(1, Math.max(closed, counts.values().stream().max(Integer::compare).orElse(1)));
        VBox rows = new VBox(10);
        for (var e : counts.entrySet()) rows.getChildren().add(pipelineRow(pretty(e.getKey()), e.getValue(), max));
        rows.getChildren().add(pipelineRow("Closed", closed, max));
        return Ui.card("Where your applications are now", rows);
    }

    private static Node pipelineRow(String label, int count, int max) {
        Label l = new Label(label);
        l.setMinWidth(80);
        HBox h = new HBox(10, l, bar((double) count / max, String.valueOf(count), false));
        h.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(h.getChildren().get(1), Priority.ALWAYS);
        h.setAccessibleText(label + ": " + count);
        return h;
    }

    private static String pretty(String s) { return s.charAt(0) + s.substring(1).toLowerCase(); }

    private void decide(StrategyAdjustment p, boolean accept) {
        Async.run(this, () -> accept ? app.bean(AnalyticsService.class).accept(app.user(), p.getId()) : app.bean(AnalyticsService.class).reject(app.user(), p.getId()), a -> refresh());
    }

    private void revert() {
        StrategyAdjustment a = history.getSelectionModel().getSelectedItem();
        if (a != null) Async.run(this, () -> app.bean(AnalyticsService.class).revert(app.user(), a.getId()), x -> refresh());
    }
}
