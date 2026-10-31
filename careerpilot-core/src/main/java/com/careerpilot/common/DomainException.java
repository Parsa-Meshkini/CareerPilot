package com.careerpilot.common;

import java.util.LinkedHashMap;
import java.util.Map;

/** Base class for business-rule violations raised by services. Clients map {@link #code()} to their own messages. */
public class DomainException extends RuntimeException {
    private final String code;
    private final int httpStatus;
    private final Map<String, Object> details = new LinkedHashMap<>();

    public DomainException(String code, int httpStatus, String message) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public DomainException with(String key, Object value) {
        details.put(key, value);
        return this;
    }

    public String code() { return code; }
    public int httpStatus() { return httpStatus; }
    public Map<String, Object> details() { return details; }

    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", code);
        out.put("message", getMessage());
        out.putAll(details);
        return out;
    }
}
