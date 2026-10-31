package com.careerpilot.common;

public class NotFoundException extends DomainException {
    public NotFoundException(String message) { super("not_found", 404, message); }
}
