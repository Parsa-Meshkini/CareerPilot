package com.careerpilot.jobs;

import com.careerpilot.common.ValidationException;
import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.LlmException;
import com.careerpilot.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Pasted free text to structured fields using the LLM prompt job.structure_paste (F05, hybrid). */
@Component
public class ManualJobImportAdapter implements JobSourceAdapter {
    static final int MAX_CHARS = 20000;
    private final LlmClient llm;

    public ManualJobImportAdapter(LlmClient llm) { this.llm = llm; }

    @Override public SourceType sourceType() { return SourceType.MANUAL; }

    @Override
    public List<RawJobRecord> parse(Map<String, Object> payload, User user) {
        String text = YorkBrowserImportAdapter.str(payload.get("text")).strip();
        if (text.isEmpty()) throw new ValidationException("paste the job posting text").with("fields", Map.of("text", "required"));
        if (text.length() > MAX_CHARS) throw new ValidationException("posting text is limited to 20,000 characters").with("fields", Map.of("text", "too long"));
        RawJobRecord raw = new RawJobRecord(SourceType.MANUAL);
        raw.description = text;
        raw.sourceUrl = YorkBrowserImportAdapter.str(payload.get("url"));
        raw.fullDetail = true;
        raw.title = YorkBrowserImportAdapter.str(payload.get("title"));
        raw.company = YorkBrowserImportAdapter.str(payload.get("company"));
        JsonNode data;
        try {
            data = llm.generateStructured(user, "job.structure_paste", Map.of("text", text)).data();
        } catch (LlmException e) {
            raw.warnings.add("AI structuring unavailable (" + e.getMessage() + "); fill the fields manually");
            return List.of(raw);
        }
        if (raw.location.isEmpty()) raw.location = data.path("location").asText("").strip();
        if (raw.title.isEmpty()) raw.title = data.path("title").asText("").strip();
        if (raw.company.isEmpty()) raw.company = data.path("company").asText("").strip();
        if (raw.deadlineText.isEmpty()) raw.deadlineText = data.path("deadline_text").asText("").strip();
        if (raw.workTerm.isEmpty()) raw.workTerm = data.path("work_term").asText("").strip();
        if (raw.jobType.isEmpty()) raw.jobType = data.path("job_type").asText("").strip();
        if (raw.salaryText.isEmpty()) raw.salaryText = data.path("salary_text").asText("").strip();
        if (raw.applicationUrl.isEmpty()) raw.applicationUrl = data.path("application_url").asText("").strip();
        if (raw.contact.isEmpty()) raw.contact = data.path("contact").asText("").strip();
        raw.workModelText = data.path("work_model").asText("");
        List<String> docs = new ArrayList<>();
        data.path("required_documents").forEach(d -> docs.add(d.asText()));
        raw.requiredDocuments = docs;
        return List.of(raw);
    }
}
