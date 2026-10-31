package com.careerpilot.llm;

/** Any failure inside the LLM subsystem. Callers catch this to run their deterministic fallback. */
public class LlmException extends RuntimeException {
    public LlmException(String message) { super(message); }
    public LlmException(String message, Throwable cause) { super(message, cause); }
}
