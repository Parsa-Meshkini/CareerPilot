package com.careerpilot.applications;


public class DeclinedState extends ApplicationState {
    @Override public String name() { return "DECLINED"; }
    @Override public boolean terminal() { return true; }
}
