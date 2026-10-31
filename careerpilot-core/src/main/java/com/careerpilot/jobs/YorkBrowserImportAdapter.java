package com.careerpilot.jobs;

import com.careerpilot.users.User;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adapts the Experience York page extract sent by the browser extension (F04). The extension sends the label/value
 * pairs it read from the page, e.g. {"Job Title": ..., "Organization": ..., "Application Deadline": ...}.
 */
@Component
public class YorkBrowserImportAdapter implements JobSourceAdapter {
    private static final Map<String, List<String>> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put("title", List.of("job title", "position title", "title"));
        LABELS.put("company", List.of("organization", "organization name", "employer", "company"));
        LABELS.put("location", List.of("job location", "location", "city"));
        LABELS.put("deadlineText", List.of("application deadline", "deadline", "closing date"));
        LABELS.put("workTerm", List.of("work term", "term"));
        LABELS.put("jobType", List.of("job type", "position type"));
        LABELS.put("salaryText", List.of("salary", "compensation", "wage"));
        LABELS.put("externalId", List.of("job id", "posting id", "id"));
        LABELS.put("workModelText", List.of("work arrangement", "work model", "remote/hybrid"));
        LABELS.put("applicationUrl", List.of("application url", "apply url"));
        LABELS.put("contact", List.of("contact", "contact name"));
        LABELS.put("documents", List.of("application documents required", "required documents", "documents required"));
    }

    @Override public SourceType sourceType() { return SourceType.YORK; }

    @Override
    @SuppressWarnings("unchecked")
    public List<RawJobRecord> parse(Map<String, Object> payload, User user) {
        List<RawJobRecord> records = new ArrayList<>();
        Object items = payload.get("records");
        if (!(items instanceof List<?> list)) return records;
        for (Object o : list) {
            Map<String, Object> item = (Map<String, Object>) o;
            Map<String, String> fields = new LinkedHashMap<>();
            Object rawFields = item.get("fields");
            if (rawFields instanceof Map<?, ?> m)
                m.forEach((k, v) -> fields.put(String.valueOf(k).strip().toLowerCase().replaceAll(":+$", ""), String.valueOf(v).strip()));
            RawJobRecord raw = new RawJobRecord(SourceType.YORK);
            raw.sourceUrl = str(item.get("url"));
            for (Map.Entry<String, List<String>> e : LABELS.entrySet()) {
                String value = e.getValue().stream().map(fields::get).filter(v -> v != null && !v.isEmpty()).findFirst().orElse("");
                switch (e.getKey()) {
                    case "title" -> raw.title = value;
                    case "company" -> raw.company = value;
                    case "location" -> raw.location = value;
                    case "deadlineText" -> raw.deadlineText = value;
                    case "workTerm" -> raw.workTerm = value;
                    case "jobType" -> raw.jobType = value;
                    case "salaryText" -> raw.salaryText = value;
                    case "externalId" -> raw.externalId = value.isEmpty() ? null : value;
                    case "workModelText" -> raw.workModelText = value;
                    case "applicationUrl" -> raw.applicationUrl = value;
                    case "contact" -> raw.contact = value;
                    default -> raw.requiredDocuments = Arrays.stream(value.replace(";", ",").split(","))
                            .map(String::strip).filter(d -> !d.isEmpty()).toList();
                }
            }
            String detail = str(item.get("detail_text"));
            raw.description = (detail.isEmpty() ? fields.getOrDefault("job description", "") : detail).strip();
            raw.fullDetail = !detail.isEmpty();
            records.add(raw);
        }
        return records;
    }

    static String str(Object o) { return o == null ? "" : o.toString(); }
}
