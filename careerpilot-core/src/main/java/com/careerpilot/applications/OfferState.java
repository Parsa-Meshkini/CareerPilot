package com.careerpilot.applications;


public class OfferState extends ApplicationState {
    @Override public String name() { return "OFFER"; }
    @Override public void accept(Application app) { app.setState(new AcceptedState()); }
    @Override public void decline(Application app) { app.setState(new DeclinedState()); }
}
