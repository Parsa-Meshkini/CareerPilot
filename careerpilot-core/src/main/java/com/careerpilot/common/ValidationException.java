package com.careerpilot.common;

public class ValidationException extends DomainException {
    public ValidationException(String message) { super("validation_failed", 400, message); }
}
