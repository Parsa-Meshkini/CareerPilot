package com.careerpilot.applications;

import com.careerpilot.common.ConflictException;

import java.util.List;

public class InvalidTransitionException extends ConflictException {
    public InvalidTransitionException(String fromState, String action, List<String> allowed) {
        super("invalid_transition", "cannot " + action.replace('_', ' ') + " an application that is " + fromState);
        with("from_state", fromState).with("action", action).with("allowed_actions", allowed);
    }
}
