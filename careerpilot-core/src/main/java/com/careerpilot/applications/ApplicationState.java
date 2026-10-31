package com.careerpilot.applications;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * State pattern (Stage 1 SM-1). {@link Application} is the context and delegates every lifecycle action to its current
 * state object. The base class rejects every action; each concrete state overrides only the transitions legal from it.
 * {@link #allowedActions()} is derived from those overrides, so the GUI and CLI can only ever offer legal actions.
 */
public abstract class ApplicationState {
    public static final List<String> ACTIONS = List.of("mark_prepared", "submit", "schedule_interview", "receive_offer",
            "reject", "accept", "decline", "withdraw");

    public abstract String name();

    public boolean terminal() { return false; }

    private void refuse(String action) {
        throw new InvalidTransitionException(name(), action, allowedActions());
    }

    public void markPrepared(Application app) { refuse("mark_prepared"); }
    public void submit(Application app) { refuse("submit"); }
    public void scheduleInterview(Application app, Instant when) { refuse("schedule_interview"); }
    public void receiveOffer(Application app) { refuse("receive_offer"); }
    public void reject(Application app) { refuse("reject"); }
    public void accept(Application app) { refuse("accept"); }
    public void decline(Application app) { refuse("decline"); }
    public void withdraw(Application app) { refuse("withdraw"); }

    /** The actions this state overrides, in lifecycle order. */
    public List<String> allowedActions() {
        List<String> allowed = new ArrayList<>();
        for (String action : ACTIONS) {
            if (overrides(camel(action))) allowed.add(action);
        }
        return allowed;
    }

    private boolean overrides(String method) {
        for (var m : getClass().getMethods()) {
            if (m.getName().equals(method) && m.getDeclaringClass() != ApplicationState.class) return true;
        }
        return false;
    }

    static String camel(String snake) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : snake.toCharArray()) {
            if (c == '_') up = true;
            else { sb.append(up ? Character.toUpperCase(c) : c); up = false; }
        }
        return sb.toString();
    }

    public static ApplicationState of(String name) {
        return switch (name) {
            case "SAVED" -> new SavedState();
            case "PREPARED" -> new PreparedState();
            case "APPLIED" -> new AppliedState();
            case "INTERVIEW" -> new InterviewState();
            case "OFFER" -> new OfferState();
            case "ACCEPTED" -> new AcceptedState();
            case "DECLINED" -> new DeclinedState();
            case "REJECTED" -> new RejectedState();
            case "WITHDRAWN" -> new WithdrawnState();
            default -> throw new IllegalArgumentException("unknown state " + name);
        };
    }

    public static List<String> allStateNames() {
        return List.of("SAVED", "PREPARED", "APPLIED", "INTERVIEW", "OFFER", "ACCEPTED", "DECLINED", "REJECTED", "WITHDRAWN");
    }
}
