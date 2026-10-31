package com.careerpilot.applications;


public class RejectedState extends ApplicationState {
    @Override public String name() { return "REJECTED"; }
    @Override public boolean terminal() { return true; }
}
