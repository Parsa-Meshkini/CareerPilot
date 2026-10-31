package com.careerpilot.jobs;

import com.careerpilot.common.TaskQueue;
import com.careerpilot.common.ValidationException;
import com.careerpilot.events.DomainEventBus;
import com.careerpilot.events.Events;
import com.careerpilot.users.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Batch (extension, file) and manual (paste) import, normalization and de-duplication (F04, F05, F06). */
@Service
@Transactional
public class JobImportService {
    public static final int MAX_BATCH = 200;
    private final JobSourceRegistry registry;
    private final JobNormalizer normalizer;
    private final DuplicateDetector detector;
    private final JobPostingRepository jobs;
    private final ImportBatchRepository batches;
    private final DomainEventBus bus;
    private final TaskQueue tasks;

    public JobImportService(JobSourceRegistry registry, JobNormalizer normalizer, DuplicateDetector detector,
                            JobPostingRepository jobs, ImportBatchRepository batches, DomainEventBus bus, TaskQueue tasks) {
        this.registry = registry;
        this.normalizer = normalizer;
        this.detector = detector;
        this.jobs = jobs;
        this.batches = batches;
        this.bus = bus;
        this.tasks = tasks;
    }

    public ImportBatch createBatch(User user, String source, Map<String, Object> payload) {
        JobSourceAdapter adapter = registry.get(source);
        if (payload.get("records") instanceof List<?> records && records.size() > MAX_BATCH)
            throw new ValidationException("a batch may contain at most " + MAX_BATCH + " postings; split it");
        List<RawJobRecord> raw = adapter.parse(payload, user);  // parse early so format errors are reported immediately
        if (raw.isEmpty()) throw new ValidationException("no postings found in the payload (page layout not recognized?)");
        ImportBatch batch = new ImportBatch();
        batch.setUser(user);
        batch.setSourceType(raw.get(0).sourceType);
        batch.setTotalCount(raw.size());
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("source", source);
        stored.putAll(payload);
        batch.setPayload(stored);
        String version = String.valueOf(payload.getOrDefault("extractor_version", ""));
        batch.setExtractorVersion(version.length() > 40 ? version.substring(0, 40) : version);
        batches.save(batch);
        tasks.enqueue("jobs.process_batch", () -> processBatch(batch.getId()));
        return batches.findById(batch.getId()).orElse(batch);
    }

    public ImportBatch processBatch(UUID batchId) {
        ImportBatch batch = batches.findById(batchId).orElseThrow();
        batch.setStatus(ImportBatch.Status.PROCESSING);
        JobSourceAdapter adapter = registry.get(String.valueOf(batch.getPayload().get("source")));
        List<RawJobRecord> raws = adapter.parse(batch.getPayload(), batch.getUser());
        for (int i = 0; i < raws.size(); i++) importOne(i, raws.get(i), batch, adapter);
        batch.complete();
        batches.save(batch);
        bus.publish(new Events.JobsImported(batch.getUser().getId(), batch.getId(), batch.importedJobIds(), batch.getSourceType().name()));
        return batch;
    }

    private void importOne(int index, RawJobRecord raw, ImportBatch batch, JobSourceAdapter adapter) {
        List<String> problems = adapter.validate(raw);
        if (!problems.isEmpty()) {
            batch.recordResult(index, "FAILED", null, String.join("; ", problems));
            return;
        }
        try {
            ImportOutcome outcome = importRecord(batch.getUser(), raw, batch);
            batch.recordResult(index, outcome.duplicate() ? "DUPLICATE" : "IMPORTED", outcome.job().getId(), "");
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            batch.recordResult(index, "FAILED", null, msg.length() > 200 ? msg.substring(0, 200) : msg);
        }
    }

    public record ImportOutcome(JobPosting job, boolean duplicate, List<String> warnings) {}

    /** Normalize, de-duplicate and save one record. A duplicate is merged into the existing posting. */
    public ImportOutcome importRecord(User user, RawJobRecord raw, ImportBatch batch) {
        JobNormalizer.Result normalized = normalizer.normalize(raw, user);
        JobPosting job = normalized.job();
        JobPosting existing = detector.findDuplicate(user, job).orElse(null);
        if (existing != null) {
            if (!existing.mergeFrom(job).isEmpty()) jobs.save(existing);
            return new ImportOutcome(existing, true, normalized.warnings());
        }
        job.setBatch(batch);
        jobs.save(job);
        return new ImportOutcome(job, false, normalized.warnings());
    }

    public ImportOutcome importManual(User user, Map<String, Object> payload) {
        JobSourceAdapter adapter = registry.get("manual");
        RawJobRecord raw = adapter.parse(payload, user).get(0);
        List<String> problems = adapter.validate(raw);
        if (!problems.isEmpty()) {
            // the structuring step could not find the basics: the student fills them in the preview form
            Map<String, String> fields = new LinkedHashMap<>();
            problems.forEach(p -> fields.put(p.replace("missing ", ""), "required"));
            throw new ValidationException("could not determine " + String.join(" and ", fields.keySet()))
                    .with("fields", fields)
                    .with("draft", Map.of("title", raw.title, "company", raw.company, "description", raw.description))
                    .with("warnings", raw.warnings);
        }
        ImportOutcome outcome = importRecord(user, raw, null);
        if (!outcome.duplicate())
            bus.publish(new Events.JobsImported(user.getId(), null, List.of(outcome.job().getId()), "MANUAL"));
        return outcome;
    }

    @Transactional(readOnly = true)
    public ImportBatch getBatch(User user, UUID id) { return batches.owned(user, id); }

    @Transactional(readOnly = true)
    public List<JobPosting> listJobs(User user, String q, java.time.LocalDate deadlineBefore, Boolean shortlisted, boolean includeDismissed) {
        String needle = q == null ? "" : q.toLowerCase();
        return jobs.findByUser(user).stream()
                .filter(j -> includeDismissed || !j.isDismissed())
                .filter(j -> needle.isEmpty() || (j.getTitle() + " " + j.getCompany() + " " + j.getLocation()).toLowerCase().contains(needle))
                .filter(j -> deadlineBefore == null || (j.getDeadline() != null && !j.getDeadline().isAfter(deadlineBefore)))
                .filter(j -> shortlisted == null || j.isShortlisted() == shortlisted)
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public JobPosting getJob(User user, UUID id) { return jobs.owned(user, id); }

    public void dismiss(User user, UUID id, boolean dismissed) {
        JobPosting job = jobs.owned(user, id);
        job.setDismissed(dismissed);
        jobs.save(job);
    }
}
