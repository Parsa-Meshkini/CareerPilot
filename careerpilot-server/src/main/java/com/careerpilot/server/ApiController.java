package com.careerpilot.server;

import com.careerpilot.jobs.ImportBatch;
import com.careerpilot.jobs.JobImportService;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.users.AuthService;
import com.careerpilot.users.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The HTTP surface of CareerPilot: sign-in, and the browser-extension batch import (F04). Everything else is reached
 * through the CLI and the JavaFX GUI, which embed the same core services.
 */
@RestController
@RequestMapping("/api")
public class ApiController {
    private final AuthService auth;
    private final JobImportService imports;

    public ApiController(AuthService auth, JobImportService imports) {
        this.auth = auth;
        this.imports = imports;
    }

    private static User user(HttpServletRequest request) { return (User) request.getAttribute(TokenAuthInterceptor.USER_ATTRIBUTE); }

    @GetMapping("/health")
    public Map<String, Object> health() { return Map.of("status", "ok"); }

    @PostMapping("/auth/register")
    public Map<String, Object> register(@RequestBody Map<String, String> body) {
        User u = auth.register(body.get("email"), body.get("password"), body.getOrDefault("display_name", ""));
        return session(u);
    }

    @PostMapping("/auth/login")
    public Map<String, Object> login(@RequestBody Map<String, String> body) {
        return session(auth.login(body.get("email"), body.get("password")));
    }

    @GetMapping("/auth/me")
    public Map<String, Object> me(HttpServletRequest request) { return userJson(user(request)); }

    private Map<String, Object> session(User u) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", auth.issueToken(u));
        out.put("user", userJson(u));
        return out;
    }

    private static Map<String, Object> userJson(User u) {
        return Map.of("id", u.getId().toString(), "email", u.getEmail(), "display_name", u.getDisplayName());
    }

    /** F04: the extension posts the postings it read from the page the student is viewing. */
    @PostMapping("/jobs/batches")
    public Map<String, Object> createBatch(HttpServletRequest request, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked") Map<String, Object> payload = (Map<String, Object>) body.getOrDefault("payload", Map.of());
        return batchJson(imports.createBatch(user(request), String.valueOf(body.getOrDefault("source", "york")), payload));
    }

    @GetMapping("/jobs/batches/{id}")
    public Map<String, Object> batch(HttpServletRequest request, @PathVariable UUID id) { return batchJson(imports.getBatch(user(request), id)); }

    @GetMapping("/jobs")
    public List<Map<String, Object>> jobs(HttpServletRequest request, @RequestParam(required = false) String q) {
        return imports.listJobs(user(request), q, null, null, false).stream().map(ApiController::jobJson).toList();
    }

    static Map<String, Object> batchJson(ImportBatch b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId().toString());
        m.put("status", b.getStatus().name());
        m.put("source_type", b.getSourceType().name());
        m.put("total", b.getTotalCount());
        m.put("imported", b.getImportedCount());
        m.put("duplicates", b.getDuplicateCount());
        m.put("failed", b.getFailedCount());
        m.put("progress", b.progress());
        m.put("results", b.getResults());
        return m;
    }

    static Map<String, Object> jobJson(JobPosting j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.getId().toString());
        m.put("title", j.getTitle());
        m.put("company", j.getCompany());
        m.put("location", j.getLocation());
        m.put("deadline", j.getDeadline() == null ? null : j.getDeadline().toString());
        m.put("work_model", j.getWorkModel());
        m.put("completeness", j.getCompleteness().name());
        m.put("shortlisted", j.isShortlisted());
        return m;
    }
}
