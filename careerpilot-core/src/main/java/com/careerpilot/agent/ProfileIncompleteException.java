package com.careerpilot.agent;

import com.careerpilot.common.DomainException;

public class ProfileIncompleteException extends DomainException {
    public ProfileIncompleteException(String message, String missing) {
        super("profile_incomplete", 400, message);
        with("missing", missing);
    }
}
