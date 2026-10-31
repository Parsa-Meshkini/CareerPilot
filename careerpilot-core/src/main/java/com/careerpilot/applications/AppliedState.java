package com.careerpilot.applications;

import java.time.Instant;

public class AppliedState extends ApplicationState {
    @Override public String name() { return "APPLIED"; }
    @Override public void scheduleInterview(Application app, Instant when) { app.setState(new InterviewState()); }
    @Override public void receiveOffer(Application app) { app.setState(new OfferState()); }
    @Override public void reject(Application app) { app.setState(new RejectedState()); }
    @Override public void withdraw(Application app) { app.setState(new WithdrawnState()); }
}
