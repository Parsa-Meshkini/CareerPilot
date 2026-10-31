package com.careerpilot.applications;


public class AcceptedState extends ApplicationState {
    @Override public String name() { return "ACCEPTED"; }
    @Override public boolean terminal() { return true; }
}
