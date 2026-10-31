package com.careerpilot.applications;

import java.time.Instant;

public class InterviewState extends ApplicationState {
    @Override public String name() { return "INTERVIEW"; }
    /** Another round: the state is unchanged, the Interview record is added by the service. */
    @Override public void scheduleInterview(Application app, Instant when) { }
    @Override public void receiveOffer(Application app) { app.setState(new OfferState()); }
    @Override public void reject(Application app) { app.setState(new RejectedState()); }
    @Override public void withdraw(Application app) { app.setState(new WithdrawnState()); }
}
