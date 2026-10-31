package com.careerpilot.applications;


public class SavedState extends ApplicationState {
    @Override public String name() { return "SAVED"; }
    @Override public void markPrepared(Application app) { app.setState(new PreparedState()); }
    @Override public void submit(Application app) { app.setState(new AppliedState()); }
    @Override public void withdraw(Application app) { app.setState(new WithdrawnState()); }
}
