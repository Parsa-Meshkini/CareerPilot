package com.careerpilot.applications;


public class WithdrawnState extends ApplicationState {
    @Override public String name() { return "WITHDRAWN"; }
    @Override public boolean terminal() { return true; }
}
