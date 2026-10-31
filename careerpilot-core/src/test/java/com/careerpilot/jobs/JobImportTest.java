package com.careerpilot.jobs;

import com.careerpilot.IntegrationTest;
import com.careerpilot.common.Json;
import com.careerpilot.common.ValidationException;
import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.Events;
import com.careerpilot.llm.LlmProviderException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JobImportTest extends IntegrationTest {
    @Autowired JobImportService imports;
    @Autowired DomainEventBus bus;
    @Autowired JobPostingRepository jobs;

    private Map<String, Object> york() throws IOException {
        return Json.map(Files.readString(Path.of("src/test/resources/fixtures/york_list_payload.json")));
    }

    @Test
    void yorkBatchImportsWithPartialFailureAndPublishesEvent() throws IOException {
        List<Events.JobsImported> seen = new ArrayList<>();
        bus.subscribe(Events.JobsImported.class, seen::add);
        ImportBatch b = imports.createBatch(user, "york", york());

        assertEquals(ImportBatch.Status.PARTIAL_FAILED, b.getStatus());
        assertEquals(2, b.getImportedCount());
        assertEquals(1, b.getFailedCount());
        assertTrue(b.getResults().get(2).get("reason").toString().contains("missing title"));
        JobPosting job = jobs.findFirstByUserAndSourceTypeAndExternalId(user, SourceType.YORK, "1101").orElseThrow();
        assertEquals(LocalDate.of(2026, 11, 15), job.getDeadline());
        assertEquals("HYBRID", job.getWorkModel());
        assertEquals(JobPosting.Completeness.SUMMARY, job.getCompleteness());
        assertEquals(1, seen.size());
        assertEquals(2, seen.get(0).jobIds().size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void reimportIsDuplicateAndFullDetailUpgradesSummary() throws IOException {
        imports.createBatch(user, "york", york());
        Map<String, Object> record = new java.util.LinkedHashMap<>(((List<Map<String, Object>>) york().get("records")).get(0));
        record.put("detail_text", "We build APIs in Python and Django. ".repeat(20));
        ImportBatch b = imports.createBatch(user, "york", Map.of("records", List.of(record)));

        assertEquals(1, b.getDuplicateCount());
        assertEquals(0, b.getImportedCount());
        JobPosting job = jobs.findFirstByUserAndSourceTypeAndExternalId(user, SourceType.YORK, "1101").orElseThrow();
        assertEquals(JobPosting.Completeness.FULL, job.getCompleteness());
        assertTrue(job.getDescription().contains("Django"));
        assertEquals(2, jobs.findByUser(user).size());
    }

    @Test
    void manualPasteUsesLlmStructuringAndDetectsDuplicates() {
        mock.script("job.structure_paste", Map.of("title", "Junior DevOps Engineer", "company", "Globex", "location", "Ottawa, ON",
                "work_model", "REMOTE", "deadline_text", "Dec 1, 2026", "required_documents", List.of("Resume")));
        String text = "Globex is hiring a Junior DevOps Engineer. Remote. Apply by Dec 1, 2026. ".repeat(5);
        JobImportService.ImportOutcome first = imports.importManual(user, Map.of("text", text, "url", "https://globex.example/jobs/9"));
        JobPosting job = first.job();
        assertEquals("Junior DevOps Engineer", job.getTitle());
        assertEquals("REMOTE", job.getWorkModel());
        assertEquals(LocalDate.of(2026, 12, 1), job.getDeadline());
        assertEquals(JobPosting.Completeness.FULL, job.getCompleteness());
        assertFalse(first.duplicate());

        assertTrue(imports.importManual(user, Map.of("text", text)).duplicate());
    }

    @Test
    void manualPasteFallsBackToTheFormWhenTheLlmFails() {
        mock.script("job.structure_paste", new LlmProviderException("down", false));
        ValidationException e = assertThrows(ValidationException.class,
                () -> imports.importManual(user, Map.of("text", "Some posting text without fields")));
        @SuppressWarnings("unchecked") Map<String, String> fields = (Map<String, String>) e.details().get("fields");
        assertEquals(java.util.Set.of("title", "company"), fields.keySet());

        JobImportService.ImportOutcome ok = imports.importManual(user, Map.of("text", "Some posting text", "title", "Tester", "company", "Initech"));
        assertFalse(ok.warnings().isEmpty());
    }

    @Test
    void csvFileImportAndFiltering() {
        String csv = "title,company,location,deadline\nQA Intern,Initech,Toronto,2026-11-20\nSRE Intern,Hooli,Remote,\n";
        ImportBatch b = imports.createBatch(user, "file", Map.of("csv", csv));
        assertEquals(2, b.getImportedCount());
        assertEquals(List.of("QA Intern"), imports.listJobs(user, null, LocalDate.of(2026, 11, 25), null, false).stream().map(JobPosting::getTitle).toList());
        assertEquals("REMOTE", imports.listJobs(user, "hooli", null, null, false).get(0).getWorkModel());
    }

    @Test
    void linkedInExtractionIsDisabledAndBatchesAreCapped() throws IOException {
        ValidationException e = assertThrows(ValidationException.class, () -> imports.createBatch(user, "linkedin", york()));
        assertTrue(e.getMessage().contains("paste"));
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 201; i++) many.add(Map.of("fields", Map.of("Job Title", "t" + i, "Organization", "x")));
        assertThrows(ValidationException.class, () -> imports.createBatch(user, "york", Map.of("records", many)));
    }

    @Test
    void normalizerHelpers() {
        assertEquals(LocalDate.of(2026, 11, 15), JobNormalizer.parseDeadline("Friday, November 15th, 2026 at 11:59 PM"));
        assertEquals(LocalDate.of(2026, 11, 15), JobNormalizer.parseDeadline("15/11/2026"));
        assertNull(JobNormalizer.parseDeadline("ASAP"));
        assertEquals("ONSITE", JobNormalizer.detectWorkModel("Toronto (on-site)"));
    }

    @Test
    void fingerprintIgnoresCompanySuffixesAndCase() {
        JobPosting a = new JobPosting(), b = new JobPosting();
        a.setTitle("Dev"); a.setCompany("Acme Corp."); a.setLocation("Toronto");
        b.setTitle("dev"); b.setCompany("ACME"); b.setLocation("toronto");
        assertEquals(a.computeFingerprint(), b.computeFingerprint());
    }
}
