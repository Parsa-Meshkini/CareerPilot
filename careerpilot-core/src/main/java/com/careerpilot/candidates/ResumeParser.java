package com.careerpilot.candidates;

import com.careerpilot.common.ValidationException;
import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.LlmResult;
import com.careerpilot.users.User;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Deterministic text extraction (PDFBox, POI) followed by LLM fact extraction (F02). Hybrid feature. */
@Component
public class ResumeParser {
    public static final String PDF = "application/pdf";
    public static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    public static final String TXT = "text/plain";
    public static final Map<String, String> SUPPORTED = Map.of(".pdf", PDF, ".docx", DOCX, ".txt", TXT);
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 15000;

    /** The file has no extractable text (for example a scanned PDF). */
    public static class NoTextException extends RuntimeException {
        public NoTextException(String message) { super(message); }
    }

    private final LlmClient llm;

    public ResumeParser(LlmClient llm) { this.llm = llm; }

    public String extractText(byte[] data, String mimeType) throws IOException {
        String text;
        if (PDF.equals(mimeType)) {
            try (PDDocument doc = Loader.loadPDF(data)) {
                text = new PDFTextStripper().getText(doc);
            }
        } else if (DOCX.equals(mimeType)) {
            try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(data))) {
                List<String> lines = new ArrayList<>();
                for (XWPFParagraph p : doc.getParagraphs()) lines.add(p.getText());
                for (XWPFTable table : doc.getTables())
                    for (XWPFTableRow row : table.getRows())
                        lines.add(row.getTableCells().stream().map(XWPFTableCell::getText).collect(Collectors.joining(" | ")));
                text = String.join("\n", lines);
            }
        } else {
            text = new String(data, StandardCharsets.UTF_8);
        }
        text = text.lines().map(String::stripTrailing).filter(l -> !l.isBlank()).collect(Collectors.joining("\n"));
        if (text.length() < 40) throw new NoTextException("no readable text found; enter your facts manually (OCR is not supported)");
        return text.length() > MAX_TEXT_CHARS ? text.substring(0, MAX_TEXT_CHARS) : text;
    }

    /** Asks the LLM to structure the resume; returns unsaved, unverified facts. */
    public List<ProfileFact> extractFacts(User user, String text) {
        LlmResult result = llm.generateStructured(user, "resume.extract_facts", Map.of("resume_text", text));
        List<ProfileFact> facts = new ArrayList<>();
        for (JsonNode item : result.data().path("facts")) {
            FactType type = FactType.parse(item.path("fact_type").asText(""));
            if (type == null) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> data = item.has("data") ? com.careerpilot.common.Json.convert(item.get("data"), Map.class) : new LinkedHashMap<>();
            ProfileFact fact = new ProfileFact();
            fact.setUser(user);
            fact.setFactType(type);
            fact.setSource(FactSource.RESUME_EXTRACTION);
            try {
                fact.setData(FactValidator.validate(type, data));
            } catch (ValidationException e) {
                boolean usable = type.required.stream().anyMatch(r -> data.get(r) != null && !data.get(r).toString().isBlank());
                if (!usable) continue;  // nothing usable
                // keep it, but the student must fix it before it can be verified
                Map<String, Object> kept = new LinkedHashMap<>();
                data.forEach((k, v) -> { if (v instanceof String || v instanceof List) kept.put(k, v); });
                fact.setData(kept);
                fact.setNeedsReview(true);
                @SuppressWarnings("unchecked")
                Map<String, String> fields = (Map<String, String>) e.details().getOrDefault("fields", Map.of());
                String note = fields.entrySet().stream().map(en -> en.getKey() + ": " + en.getValue()).collect(Collectors.joining("; "));
                fact.setReviewNote(note.length() > 200 ? note.substring(0, 200) : note);
            }
            facts.add(fact);
        }
        return facts;
    }
}
