package com.careerpilot.server;

import com.careerpilot.common.DomainException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** Maps domain errors to the same JSON shape the CLI and GUI show: {error, message, ...details}. */
@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(DomainException.class)
    public ResponseEntity<Map<String, Object>> domain(DomainException e) {
        return ResponseEntity.status(e.httpStatus()).body(e.toMap());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "bad_request", "message", String.valueOf(e.getMessage())));
    }
}
