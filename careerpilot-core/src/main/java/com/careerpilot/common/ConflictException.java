package com.careerpilot.common;

public class ConflictException extends DomainException {
    public ConflictException(String code, String message) { super(code, 409, message); }
    public ConflictException(String message) { this("conflict", message); }
}
