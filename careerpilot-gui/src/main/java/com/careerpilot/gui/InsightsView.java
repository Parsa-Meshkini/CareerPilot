package com.careerpilot.gui;

import com.careerpilot.analytics.AnalyticsService;
import com.careerpilot.analytics.GroupStats;
import com.careerpilot.analytics.StrategyAdjustment;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** F19: the student's own outcome statistics and proposed strategy adjustments they can accept, reject or revert. */
final class InsightsView extends View {
    private final Label narrative = new Label();
    private final TextArea stats = Ui.readOnlyText();
    private final VBox proposals = new VBox(8);
    private final TableView<StrategyAdjustment> history = Ui.table(Ui.chipCol("Status", 110, a -> a.getStatus().name()), Ui.col("Weight", 100, StrategyAdjustment::getFactor),
            Ui.col("Change", 120, a -> String.format("%.2f -> %.2f", a.getOldValue(), a.getNewValue())), Ui.col("Why", 420, StrategyAdjustment::getRationale));

    InsightsView(AppContext app) {
        super(app);
        narrative.setWrapText(true);
        stats.setPrefRowCount(11);
        history.setPrefHeight(160);
        getChildren().addAll(Ui.title("Insights"), Ui.muted("Statistics come from your own applications. Changes to how jobs are scored are only ever proposed: you decide."), narrative,
                stats, Ui.heading("Proposed adjustments"), proposals, Ui.heading("History"), history,
                Ui.row(Ui.button("Revert selected", this::revert), Ui.button("Refresh", this::refresh)));
    }

    @Override String name() { return "Insights"; }

    @Override
    void refresh() {
        Async.run(this, () -> new Object[]{app.bean(AnalyticsService.class).getInsights(app.user()), app.bean(AnalyticsService.class).history(app.user())}, d -> {
            AnalyticsService.InsightReport r = (AnalyticsService.InsightReport) d[0];
            narrative.setText(r.narrative());
            StringBuilder sb = new StringBuilder("Applied: " + r.stats().applied() + "   With an outcome: " + r.stats().withOutcome()
                    + "   Median days to response: " + (r.stats().medianDaysToResponse() == null ? "-" : r.stats().medianDaysToResponse()) + "\n");
            group(sb, "By match score", r.stats().byScoreBand());
            group(sb, "By source", r.stats().bySource());
            group(sb, "By role category", r.stats().byRoleCategory());
            stats.setText(sb.toString());
            proposals.getChildren().clear();
            if (r.proposals().isEmpty()) proposals.getChildren().add(Ui.muted(r.enoughData() ? "No change is justified by your data right now." : "No proposals yet."));
            for (StrategyAdjustment p : r.proposals()) {
                Label text = new Label(p.getFactor() + " weight " + String.format("%.2f -> %.2f", p.getOldValue(), p.getNewValue()) + ": " + p.getRationale());
                text.setWrapText(true);
                proposals.getChildren().add(new VBox(6, text, Ui.row(Ui.primary("Accept", () -> decide(p, true)), Ui.button("Reject", () -> decide(p, false)))));
            }
            @SuppressWarnings("unchecked") List<StrategyAdjustment> hist = (List<StrategyAdjustment>) d[1];
            Ui.fill(history, new ArrayList<>(hist.stream().filter(a -> a.getStatus() != StrategyAdjustment.Status.SUPERSEDED).toList()));
        });
    }

    private static void group(StringBuilder sb, String title, List<GroupStats> groups) {
        if (groups.isEmpty()) return;
        sb.append("\n").append(title).append(":\n");
        groups.forEach(g -> sb.append(String.format("  %-10s n=%-3d responded %3d%%  interviews %3d%%%n", g.label(), g.applied(), Math.round(g.responseRate() * 100), Math.round(g.interviewRate() * 100))));
    }

    private void decide(StrategyAdjustment p, boolean accept) {
        Async.run(this, () -> accept ? app.bean(AnalyticsService.class).accept(app.user(), p.getId()) : app.bean(AnalyticsService.class).reject(app.user(), p.getId()), a -> refresh());
    }

    private void revert() {
        StrategyAdjustment a = history.getSelectionModel().getSelectedItem();
        if (a != null) Async.run(this, () -> app.bean(AnalyticsService.class).revert(app.user(), a.getId()), x -> refresh());
    }
}
