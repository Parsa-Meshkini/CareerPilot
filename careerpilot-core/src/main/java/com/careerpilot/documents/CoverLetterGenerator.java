package com.careerpilot.documents;

import com.careerpilot.agent.CandidateContext;
import com.careerpilot.agent.ContextBuilder;
import com.careerpilot.applications.Application;
import com.careerpilot.candidates.CandidateProfile;
import com.careerpilot.common.StorageService;
import com.careerpilot.common.ValidationException;
import com.careerpilot.jobs.JobAnalysis;
import com.careerpilot.jobs.JobAnalysisRepository;
import com.careerpilot.jobs.JobAnalysisService;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.llm.LlmClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** F11: tailored cover letter. Statements about the role may use only the posting, about the student only verified facts. */
@Component
public class CoverLetterGenerator extends DocumentGenerator {
    static final int MAX_WORDS = 350;
    static final List<String> TONES = List.of("formal", "warm");

    public CoverLetterGenerator(LlmClient llm, ContextBuilder contexts, DocumentRenderer renderer, StorageService storage,
                                GeneratedDocumentRepository documents, JobAnalysisRepository analyses, JobAnalysisService analysisService) {
        super(llm, contexts, renderer, storage, documents, analyses, analysisService);
    }

    @Override public String docType() { return GeneratedDocument.COVER_LETTER; }
    @Override protected String promptKey() { return "cover_letter.tailor"; }
    @Override protected String fileStem(Application application) { return "Cover_Letter"; }

    @Override
    protected Map<String, Object> buildPromptVariables(CandidateContext ctx, Application application, JobAnalysis analysis, Map<String, Object> options) {
        String tone = String.valueOf(options.getOrDefault("tone", "formal"));
        if (!TONES.contains(tone)) throw new ValidationException("tone must be formal or warm").with("fields", Map.of("tone", "formal|warm"));
        JobPosting job = application.getJob();
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("job_title", job.getTitle());
        v.put("company", job.getCompany());
        v.put("posting", job.getDescription().isEmpty() ? "(summary only)" : job.getDescription().substring(0, Math.min(6000, job.getDescription().length())));
        v.put("required", analysis.getRequiredSkills());
        v.put("facts", ctx.factsForPrompt(40));
        v.put("name", ctx.profile.getFullName().isEmpty() ? ctx.user.getEmail() : ctx.profile.getFullName());
        v.put("tone", tone);
        v.put("max_words", MAX_WORDS);
        v.put("feedback", options.getOrDefault("feedback", ""));
        return v;
    }

    @Override
    protected List<Claim> claims(Map<String, Object> content) {
        List<Claim> out = new ArrayList<>();
        List<Map<String, Object>> paragraphs = maps(content.get("paragraphs"));
        for (int i = 0; i < paragraphs.size(); i++)
            out.add(new Claim("paragraphs." + i, str(paragraphs.get(i).get("text")), ids(paragraphs.get(i).get("source_fact_ids")), Claim.Kind.PARAGRAPH));
        return out;
    }

    @Override
    protected void extraChecks(Map<String, Object> content, VerificationReport report) {
        int words = maps(content.get("paragraphs")).stream().mapToInt(p -> str(p.get("text")).isBlank() ? 0 : str(p.get("text")).trim().split("\\s+").length).sum();
        if (words > MAX_WORDS) report.violations.add(new Violation("length", "", "too_long", words + " words; the limit is " + MAX_WORDS));
    }

    @Override
    protected Map<String, Object> removeClaims(Map<String, Object> content, Set<String> locations) {
        List<Map<String, Object>> original = maps(content.get("paragraphs"));
        List<Map<String, Object>> paragraphs = new ArrayList<>();
        for (int i = 0; i < original.size(); i++) if (!locations.contains("paragraphs." + i)) paragraphs.add(original.get(i));
        if (locations.contains("length")) {
            List<Map<String, Object>> kept = new ArrayList<>();
            int total = 0;
            for (Map<String, Object> p : paragraphs) {
                String text = str(p.get("text"));
                int n = text.isBlank() ? 0 : text.trim().split("\\s+").length;
                if (total + n > MAX_WORDS) break;
                kept.add(p);
                total += n;
            }
            paragraphs = kept;
        }
        Map<String, Object> out = new LinkedHashMap<>(content);
        out.put("paragraphs", paragraphs);
        return out;
    }

    @Override
    protected List<Block> render(CandidateContext ctx, Application application, Map<String, Object> content) {
        CandidateProfile p = ctx.profile;
        JobPosting job = application.getJob();
        String name = p.getFullName().isEmpty() ? ctx.user.getEmail() : p.getFullName();
        List<Block> blocks = new ArrayList<>();
        blocks.add(Block.of(Block.Kind.TEXT, name));
        List<String> contact = new ArrayList<>();
        for (String s : new String[]{p.getEmail(), p.getPhone(), p.getLocation()}) if (s != null && !s.isEmpty()) contact.add(s);
        if (!contact.isEmpty()) blocks.add(Block.of(Block.Kind.TEXT, String.join(" | ", contact)));
        blocks.add(Block.of(Block.Kind.TEXT, LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.ENGLISH))));
        blocks.add(Block.of(Block.Kind.TEXT, job.getCompany()));
        blocks.add(Block.of(Block.Kind.PARAGRAPH, "Re: " + job.getTitle()));
        String greeting = str(content.get("greeting"));
        blocks.add(Block.of(Block.Kind.PARAGRAPH, greeting.isEmpty() ? "Dear Hiring Manager," : greeting));
        maps(content.get("paragraphs")).forEach(para -> blocks.add(Block.of(Block.Kind.PARAGRAPH, str(para.get("text")))));
        String closing = str(content.get("closing"));
        blocks.add(Block.of(Block.Kind.PARAGRAPH, closing.isEmpty() ? "Sincerely," : closing));
        blocks.add(Block.of(Block.Kind.TEXT, name));
        return blocks;
    }
}
