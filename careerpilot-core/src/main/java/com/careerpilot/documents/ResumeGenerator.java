package com.careerpilot.documents;

import com.careerpilot.agent.CandidateContext;
import com.careerpilot.agent.ContextBuilder;
import com.careerpilot.applications.Application;
import com.careerpilot.candidates.CandidateProfile;
import com.careerpilot.candidates.FactType;
import com.careerpilot.candidates.ProfileFact;
import com.careerpilot.common.StorageService;
import com.careerpilot.jobs.JobAnalysis;
import com.careerpilot.jobs.JobAnalysisRepository;
import com.careerpilot.jobs.JobAnalysisService;
import com.careerpilot.llm.LlmClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** F10: tailored resume. Selects and rephrases only verified facts; every bullet must cite them. */
@Component
public class ResumeGenerator extends DocumentGenerator {
    static final int MAX_BULLETS_PER_ENTRY = 4;

    public ResumeGenerator(LlmClient llm, ContextBuilder contexts, DocumentRenderer renderer, StorageService storage,
                           GeneratedDocumentRepository documents, JobAnalysisRepository analyses, JobAnalysisService analysisService) {
        super(llm, contexts, renderer, storage, documents, analyses, analysisService);
    }

    @Override public String docType() { return GeneratedDocument.RESUME; }
    @Override protected String promptKey() { return "resume.tailor"; }
    @Override protected String fileStem(Application application) { return "Resume"; }

    @Override
    protected Map<String, Object> buildPromptVariables(CandidateContext ctx, Application application, JobAnalysis analysis, Map<String, Object> options) {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (ProfileFact f : ctx.facts) {
            if (f.getFactType() != FactType.EXPERIENCE && f.getFactType() != FactType.PROJECT) continue;
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", f.getId().toString());
            e.put("type", f.getFactType().name());
            e.put("heading", f.label());
            e.put("text", f.toContextText());
            entries.add(e);
        }
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("job", application.getJob().getTitle() + " at " + application.getJob().getCompany());
        v.put("required", analysis.getRequiredSkills());
        v.put("preferred", analysis.getPreferredSkills());
        v.put("keywords", analysis.getKeywords());
        v.put("entries", entries);
        v.put("facts", ctx.factsForPrompt(40));
        v.put("skills", new TreeSet<>(ctx.skills));
        v.put("feedback", options.getOrDefault("feedback", ""));
        return v;
    }

    @Override
    protected List<Claim> claims(Map<String, Object> content) {
        List<Claim> out = new ArrayList<>();
        List<Map<String, Object>> entries = maps(content.get("entries"));
        for (int e = 0; e < entries.size(); e++) {
            List<Map<String, Object>> bullets = maps(entries.get(e).get("bullets"));
            for (int b = 0; b < bullets.size(); b++)
                out.add(new Claim("entries." + e + ".bullets." + b, str(bullets.get(b).get("text")), ids(bullets.get(b).get("source_fact_ids"))));
        }
        if (content.get("skills") instanceof List<?> skills)
            for (int s = 0; s < skills.size(); s++) out.add(new Claim("skills." + s, str(skills.get(s)), List.of(), Claim.Kind.SKILL));
        return out;
    }

    @Override
    protected Map<String, Object> removeClaims(Map<String, Object> content, Set<String> locations) {
        List<Map<String, Object>> entries = new ArrayList<>();
        List<Map<String, Object>> original = maps(content.get("entries"));
        for (int e = 0; e < original.size(); e++) {
            List<Map<String, Object>> bullets = maps(original.get(e).get("bullets"));
            List<Map<String, Object>> kept = new ArrayList<>();
            for (int b = 0; b < bullets.size(); b++) if (!locations.contains("entries." + e + ".bullets." + b)) kept.add(bullets.get(b));
            Map<String, Object> copy = new LinkedHashMap<>(original.get(e));
            copy.put("bullets", kept);
            entries.add(copy);
        }
        List<Object> skills = new ArrayList<>();
        if (content.get("skills") instanceof List<?> l)
            for (int s = 0; s < l.size(); s++) if (!locations.contains("skills." + s)) skills.add(l.get(s));
        Map<String, Object> out = new LinkedHashMap<>(content);
        out.put("entries", entries);
        out.put("skills", skills);
        return out;
    }

    @Override
    protected List<Block> render(CandidateContext ctx, Application application, Map<String, Object> content) {
        CandidateProfile p = ctx.profile;
        Map<String, ProfileFact> facts = ctx.factIndex();
        List<String> contactParts = new ArrayList<>();
        for (String s : new String[]{p.getEmail(), p.getPhone(), p.getLocation()}) if (s != null && !s.isEmpty()) contactParts.add(s);
        contactParts.addAll(p.getLinks());
        List<Block> blocks = new ArrayList<>();
        String name = !p.getFullName().isEmpty() ? p.getFullName() : (!ctx.user.getDisplayName().isEmpty() ? ctx.user.getDisplayName() : ctx.user.getEmail());
        blocks.add(Block.of(Block.Kind.TITLE, name));
        if (!contactParts.isEmpty()) blocks.add(Block.of(Block.Kind.SUBTITLE, String.join(" | ", contactParts)));
        List<ProfileFact> education = ctx.factsOf(FactType.EDUCATION);
        if (!education.isEmpty()) {
            blocks.add(Block.of(Block.Kind.HEADING, "Education"));
            for (ProfileFact f : education) {
                Map<String, Object> d = f.getData();
                blocks.add(Block.entry((str(d.get("credential")) + " " + str(d.get("program")) + ", " + str(d.get("institution"))).strip(), dates(d)));
            }
        }
        for (FactType type : List.of(FactType.EXPERIENCE, FactType.PROJECT)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (Map<String, Object> e : maps(content.get("entries"))) {
                ProfileFact f = facts.get(str(e.get("fact_id")));
                if (f != null && f.getFactType() == type && !maps(e.get("bullets")).isEmpty()) rows.add(e);
            }
            if (rows.isEmpty()) continue;
            blocks.add(Block.of(Block.Kind.HEADING, type == FactType.EXPERIENCE ? "Experience" : "Projects"));
            for (Map<String, Object> e : rows) {
                Map<String, Object> d = facts.get(str(e.get("fact_id"))).getData();
                String heading = type == FactType.EXPERIENCE ? str(d.get("title")) + ", " + str(d.get("organization")) : str(d.get("name"));
                blocks.add(Block.entry(heading, dates(d)));
                blocks.add(Block.bullets(maps(e.get("bullets")).stream().limit(MAX_BULLETS_PER_ENTRY).map(b -> str(b.get("text"))).toList()));
            }
        }
        if (content.get("skills") instanceof List<?> skills && !skills.isEmpty()) {
            blocks.add(Block.of(Block.Kind.HEADING, "Skills"));
            blocks.add(Block.of(Block.Kind.TEXT, String.join(", ", skills.stream().map(String::valueOf).toList())));
        }
        List<ProfileFact> certs = ctx.factsOf(FactType.CERTIFICATION);
        if (!certs.isEmpty()) {
            blocks.add(Block.of(Block.Kind.HEADING, "Certifications"));
            blocks.add(Block.bullets(certs.stream().map(c -> (str(c.getData().get("name")) + " (" + str(c.getData().get("issuer")) + ")").replace(" ()", "")).toList()));
        }
        return blocks;
    }

    private static String dates(Map<String, Object> d) {
        String start = str(d.get("start_date")), end = str(d.get("end_date"));
        if (start.isEmpty() && end.isEmpty()) return "";
        return start + " - " + (end.isEmpty() ? "Present" : end);
    }
}
