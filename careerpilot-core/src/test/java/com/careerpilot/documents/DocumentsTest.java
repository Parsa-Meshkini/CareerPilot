package com.careerpilot.documents;

import com.careerpilot.IntegrationTest;
import com.careerpilot.agent.CandidateContext;
import com.careerpilot.agent.ContextBuilder;
import com.careerpilot.agent.ProfileIncompleteException;
import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.candidates.CandidateService;
import com.careerpilot.candidates.FactType;
import com.careerpilot.common.ValidationException;
import com.careerpilot.jobs.JobImportService;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.jobs.RawJobRecord;
import com.careerpilot.jobs.SourceType;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class DocumentsTest extends IntegrationTest {
    @Autowired DocumentService documents;
    @Autowired ResumeGenerator resumeGenerator;
    @Autowired CoverLetterGenerator letterGenerator;
    @Autowired PackageAssembler packager;
    @Autowired ApplicationService applications;
    @Autowired ContextBuilder contexts;
    @Autowired CandidateService candidates;
    @Autowired JobImportService imports;

    Application app;
    String expId;

    static final Map<String, Object> ANALYSIS = Map.of("required_skills", List.of("python", "django"), "preferred_skills", List.of("aws"),
            "responsibilities", List.of(), "seniority", "intern", "keywords", List.of("rest", "api"), "red_flags", List.of());

    @BeforeEach
    void setup() {
        data.makeProfile(user);
        RawJobRecord raw = new RawJobRecord(SourceType.MANUAL);
        raw.title = "Backend Developer Co-op";
        raw.company = "Acme Corp";
        raw.workTerm = "Summer 2027";
        raw.description = "We need Python, Django and AWS experience. Build REST APIs. ".repeat(10);
        raw.fullDetail = true;
        raw.location = "Toronto";
        raw.requiredDocuments = List.of("Resume", "Transcript");
        raw.deadlineText = "2026-11-15";
        raw.applicationUrl = "https://acme.example/apply";
        JobPosting job = imports.importRecord(user, raw, null).job();
        app = applications.createApplication(user, job.getId(), "");
        expId = contexts.buildCandidateContext(user).factsOf(FactType.EXPERIENCE).get(0).getId().toString();
        mock.script("job.analyze", ANALYSIS);
    }

    private Map<String, Object> goodResume() {
        return Map.of("entries", List.of(Map.of("fact_id", expId, "bullets", List.of(
                Map.of("text", "Built a REST API with Docker during a developer internship", "source_fact_ids", List.of(expId))))),
                "skills", List.of("python", "django"));
    }

    private Map<String, Object> letter(String text) {
        return Map.of("greeting", "Dear Hiring Manager,", "closing", "Sincerely,",
                "paragraphs", List.of(Map.of("text", text, "source_fact_ids", List.of(expId))));
    }

    @Test
    void supportedResumePassesAndRendersPdfAndDocx() throws Exception {
        mock.script("resume.tailor", goodResume());
        GeneratedDocument doc = documents.generate(user, app.getId(), "resume");
        assertEquals(GeneratedDocument.PASSED, doc.getVerificationStatus());
        assertTrue(new String(documents.file(user, doc.getId(), "pdf"), 0, 4).startsWith("%PDF"));
        try (XWPFDocument d = new XWPFDocument(new ByteArrayInputStream(documents.file(user, doc.getId(), "docx")))) {
            String text = d.getParagraphs().stream().map(XWPFParagraph::getText).collect(Collectors.joining("\n"));
            assertTrue(text.contains("Developer Intern, Acme"));
            assertTrue(text.contains("Built a REST API with Docker"));
            assertTrue(text.contains("York University"));
        }
    }

    @Test
    void fabricatedClaimsAreRegeneratedThenRemoved() {
        Map<String, Object> bad = Map.of("entries", List.of(Map.of("fact_id", expId, "bullets", List.of(
                Map.of("text", "Cut latency by 70% using Kubernetes", "source_fact_ids", List.of(expId)),
                Map.of("text", "Built a REST API with Docker", "source_fact_ids", List.of(expId)),
                Map.of("text", "Led a team of engineers", "source_fact_ids", List.of())))),
                "skills", List.of("python", "kubernetes"));
        mock.script("resume.tailor", bad);
        GeneratedDocument doc = documents.generate(user, app.getId(), "resume");
        assertEquals(GeneratedDocument.NEEDS_REVIEW, doc.getVerificationStatus());
        assertEquals(true, doc.getVerificationReport().get("regenerated"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> violations = (List<Map<String, Object>>) doc.getVerificationReport().get("violations");
        var rules = violations.stream().map(v -> v.get("rule")).collect(Collectors.toSet());
        assertTrue(rules.containsAll(List.of("unsupported_number", "unsupported_skill", "no_citation", "unverified_skill")), rules.toString());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> bullets = (List<Map<String, Object>>) ((Map<String, Object>) ((List<?>) doc.getContent().get("entries")).get(0)).get("bullets");
        assertEquals(List.of("Built a REST API with Docker"), bullets.stream().map(b -> b.get("text")).toList());
        assertEquals(List.of("python"), doc.getContent().get("skills"));
        assertTrue(mock.calls.get(mock.calls.size() - 1).prompt.contains("Fix these problems"));
    }

    @Test
    void regenerationThatFixesTheProblemsPasses() {
        mock.script("resume.tailor", Map.of("entries", List.of(Map.of("fact_id", expId, "bullets", List.of(
                Map.of("text", "Saved $5000", "source_fact_ids", List.of(expId))))), "skills", List.of()), goodResume());
        GeneratedDocument doc = documents.generate(user, app.getId(), "resume");
        assertEquals(GeneratedDocument.PASSED, doc.getVerificationStatus());
        assertEquals(1, doc.getVersion());
    }

    @Test
    void guardRejectsAnotherUsersFactIds() {
        var other = auth.register("other-" + java.util.UUID.randomUUID() + "@yorku.ca", "password123", "Other");
        data.makeProfile(other);
        String foreign = contexts.buildCandidateContext(other).facts.get(0).getId().toString();
        CandidateContext ctx = contexts.buildCandidateContext(user);
        VerificationReport report = new FabricationGuard(ctx, null).check(List.of(new Claim("x", "Built a REST API", List.of(foreign))));
        assertEquals("unknown_fact", report.violations.get(0).rule());
    }

    @Test
    void versionsEditsAndStaleMarking() {
        mock.script("resume.tailor", goodResume());
        GeneratedDocument first = documents.generate(user, app.getId(), "resume");
        Map<String, Object> edited = new LinkedHashMap<>(goodResume());
        List<Map<String, Object>> entries = new ArrayList<>();
        Map<String, Object> entry = new LinkedHashMap<>((Map<String, Object>) ((List<?>) edited.get("entries")).get(0));
        List<Object> bullets = new ArrayList<>((List<?>) entry.get("bullets"));
        bullets.add(Map.of("text", "Won 3 hackathons", "source_fact_ids", List.of(expId)));
        entry.put("bullets", bullets);
        entries.add(entry);
        edited.put("entries", entries);
        GeneratedDocument second = documents.saveEdit(user, first.getId(), edited);
        assertEquals(2, second.getVersion());
        assertEquals(GeneratedDocument.NEEDS_REVIEW, second.getVerificationStatus());

        candidates.deleteFact(user, java.util.UUID.fromString(expId));
        assertTrue(documents.list(user, app.getId()).stream().allMatch(GeneratedDocument::isStale));
    }

    @Test
    void needsAnExperienceOrProject() {
        var fresh = auth.register("fresh-" + java.util.UUID.randomUUID() + "@yorku.ca", "password123", "Fresh");
        candidates.addFact(fresh, FactType.SKILL, Map.of("name", "python"));
        RawJobRecord raw = new RawJobRecord(SourceType.MANUAL);
        raw.title = "Dev"; raw.company = "X"; raw.description = "Python. ".repeat(50); raw.fullDetail = true;
        Application a = applications.createApplication(fresh, imports.importRecord(fresh, raw, null).job().getId(), "");
        assertThrows(ProfileIncompleteException.class, () -> documents.generate(fresh, a.getId(), "resume"));
    }

    @Test
    void generateIsFinalSoNoGeneratorCanSkipTheGuard() throws Exception {
        assertTrue(Modifier.isFinal(DocumentGenerator.class.getMethod("generate", com.careerpilot.users.User.class, Application.class,
                com.careerpilot.llm.TokenBudget.class, Map.class).getModifiers()));
    }

    @Test
    void coverLetterEnforcesToneLengthAndGrounding() {
        assertThrows(ValidationException.class, () -> documents.generate(user, app.getId(), "cover_letter", null, Map.of("tone", "sarcastic")));
        mock.script("cover_letter.tailor", letter("I built a REST API with Docker at Acme."));
        GeneratedDocument ok = documents.generate(user, app.getId(), "cover_letter");
        assertEquals(GeneratedDocument.PASSED, ok.getVerificationStatus());

        mock.replace("cover_letter.tailor", letter("word ".repeat(400)));
        GeneratedDocument tooLong = documents.generate(user, app.getId(), "cover_letter");
        assertEquals(GeneratedDocument.NEEDS_REVIEW, tooLong.getVerificationStatus());
        assertTrue(((List<?>) tooLong.getContent().get("paragraphs")).isEmpty());
    }

    private void scriptDocs() {
        mock.script("resume.tailor", goodResume());
        mock.script("cover_letter.tailor", letter("I built a REST API with Docker at Acme."));
    }

    @Test
    void packageZipHasEverythingAndMovesTheApplicationToPrepared() throws Exception {
        scriptDocs();
        ApplicationPackage pkg = packager.buildPackage(user, app.getId());
        assertEquals("Acme_Corp_Backend_Developer_Co_op_27S", pkg.getFolderName());
        assertFalse(pkg.isNeedsReview());
        List<String> names = new ArrayList<>();
        String info = "";
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(packager.download(user, pkg.getId())))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                names.add(e.getName().substring(e.getName().indexOf('/') + 1));
                if (e.getName().endsWith("Application_Info.txt")) info = new String(zip.readAllBytes());
            }
        }
        assertEquals(List.of("Application_Checklist.pdf", "Application_Info.txt", "Cover_Letter.docx", "Cover_Letter.pdf",
                "Job_Description.pdf", "Resume.docx", "Resume.pdf"), names.stream().sorted().toList());
        assertTrue(info.contains("Required documents: Resume, Transcript"));
        assertTrue(info.contains("does not submit"));
        assertEquals("PREPARED", applications.get(user, app.getId()).getStateName());
    }

    @Test
    void packageReusesDocumentsUnlessRegenerateIsRequested() {
        scriptDocs();
        packager.buildPackage(user, app.getId());
        int calls = mock.calls.size();
        packager.buildPackage(user, app.getId());
        assertEquals(calls, mock.calls.size());
        packager.buildPackage(user, app.getId(), true, "formal", null);
        assertEquals(2, documents.list(user, app.getId()).stream().filter(d -> d.getDocType().equals(GeneratedDocument.RESUME)).count());
    }

    @Test
    void flaggedDocumentMakesThePackageNeedReview() {
        mock.script("resume.tailor", Map.of("entries", List.of(), "skills", List.of("cobol")));
        mock.script("cover_letter.tailor", letter("I built a REST API with Docker at Acme."));
        assertTrue(packager.buildPackage(user, app.getId()).isNeedsReview());
    }

    @Test
    void packageKeepsAnAlreadyAppliedState() {
        scriptDocs();
        applications.transition(user, app.getId(), "submit");
        packager.buildPackage(user, app.getId());
        assertEquals("APPLIED", applications.get(user, app.getId()).getStateName());
    }
}
