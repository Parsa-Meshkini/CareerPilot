package com.careerpilot.jobs;

import com.careerpilot.common.ValidationException;
import com.careerpilot.users.User;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A JSON list or CSV file of postings (CLI bulk import, fixtures, sample data). */
@Component
public class MockJobSourceAdapter implements JobSourceAdapter {
    @Override public SourceType sourceType() { return SourceType.MOCK; }

    @Override
    @SuppressWarnings("unchecked")
    public List<RawJobRecord> parse(Map<String, Object> payload, User user) {
        Object rows = payload.get("records");
        if (rows == null && payload.get("csv") != null) rows = parseCsv(payload.get("csv").toString());
        if (!(rows instanceof List<?> list)) throw new ValidationException("file must contain a list of postings");
        SourceType type = "MOCK".equalsIgnoreCase(YorkBrowserImportAdapter.str(payload.get("source_type")))
                ? SourceType.MOCK : SourceType.MANUAL;
        List<RawJobRecord> records = new ArrayList<>();
        for (Object o : list) {
            Map<String, Object> row = new LinkedHashMap<>();
            ((Map<String, Object>) o).forEach((k, v) -> row.put(k.strip().toLowerCase(), v));
            RawJobRecord r = new RawJobRecord(type);
            r.title = s(row, "title");
            r.company = s(row, "company");
            r.location = s(row, "location");
            r.description = s(row, "description");
            r.externalId = s(row, "external_id").isEmpty() ? null : s(row, "external_id");
            r.sourceUrl = !s(row, "url").isEmpty() ? s(row, "url") : s(row, "source_url");
            r.applicationUrl = s(row, "application_url");
            r.deadlineText = s(row, "deadline");
            r.workTerm = s(row, "work_term");
            r.jobType = s(row, "job_type");
            r.workModelText = s(row, "work_model");
            r.salaryText = s(row, "salary");
            Object docs = row.get("required_documents");
            if (docs instanceof List<?> l) r.requiredDocuments = l.stream().map(Object::toString).toList();
            else if (docs != null) r.requiredDocuments = Arrays.stream(docs.toString().split(",")).map(String::strip).filter(d -> !d.isEmpty()).toList();
            r.fullDetail = r.description.length() >= 300;
            records.add(r);
        }
        return records;
    }

    private static String s(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? "" : v.toString();
    }

    /** Minimal RFC 4180 parser: quoted fields, doubled quotes and embedded newlines. */
    static List<Map<String, Object>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        try (StringReader in = new StringReader(csv)) {
            int c;
            while ((c = in.read()) != -1) {
                char ch = (char) c;
                if (quoted) {
                    if (ch == '"') {
                        in.mark(1);
                        int next = in.read();
                        if (next == '"') cell.append('"');
                        else { quoted = false; if (next != -1) { in.reset(); } }
                    } else cell.append(ch);
                } else if (ch == '"') quoted = true;
                else if (ch == ',') { row.add(cell.toString()); cell.setLength(0); }
                else if (ch == '\n' || ch == '\r') {
                    if (ch == '\r') { in.mark(1); int n = in.read(); if (n != '\n' && n != -1) in.reset(); }
                    row.add(cell.toString()); cell.setLength(0);
                    if (!(row.size() == 1 && row.get(0).isEmpty())) rows.add(row);
                    row = new ArrayList<>();
                } else cell.append(ch);
            }
        } catch (IOException e) {
            throw new ValidationException("cannot read CSV");
        }
        if (cell.length() > 0 || !row.isEmpty()) { row.add(cell.toString()); rows.add(row); }
        List<Map<String, Object>> out = new ArrayList<>();
        if (rows.isEmpty()) return out;
        List<String> header = rows.get(0);
        for (int i = 1; i < rows.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int j = 0; j < header.size(); j++) m.put(header.get(j), j < rows.get(i).size() ? rows.get(i).get(j) : "");
            out.add(m);
        }
        return out;
    }
}
