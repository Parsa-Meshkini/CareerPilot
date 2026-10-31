package com.careerpilot.applications;


public class PreparedState extends ApplicationState {
    @Override public String name() { return "PREPARED"; }
    @Override public void markPrepared(Application app) { /* rebuilding a package keeps it prepared */ }
    @Override public void submit(Application app) { app.setState(new AppliedState()); }
    @Override public void withdraw(Application app) { app.setState(new WithdrawnState()); }
}
