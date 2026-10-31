package com.careerpilot.documents;

import com.careerpilot.applications.Application;
import com.careerpilot.applications.ApplicationRepository;
import com.careerpilot.applications.ApplicationService;
import com.careerpilot.applications.StatusChange;
import com.careerpilot.common.StorageService;
import com.careerpilot.jobs.JobAnalysis;
import com.careerpilot.jobs.JobAnalysisRepository;
import com.careerpilot.jobs.JobAnalysisService;
import com.careerpilot.jobs.JobPosting;
import com.careerpilot.llm.TokenBudget;
import com.careerpilot.users.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** F12: one ready-to-submit folder per application, offered as a ZIP. CareerPilot never submits it; the student does. */
@Service
@Transactional
public class PackageAssembler {
    private final DocumentRenderer renderer;
    private final ApplicationRepository applications;
    private final ApplicationService applicationService;
    private final GeneratedDocumentRepository documents;
    private final ApplicationPackageRepository packages;
    private final JobAnalysisRepository analyses;
    private final JobAnalysisService analysisService;
    private final ResumeGenerator resumeGenerator;
    private final CoverLetterGenerator coverLetterGenerator;
    private final StorageService storage;

    public PackageAssembler(DocumentRenderer renderer, ApplicationRepository applications, ApplicationService applicationService,
                            GeneratedDocumentRepository documents, ApplicationPackageRepository packages, JobAnalysisRepository analyses,
                            JobAnalysisService analysisService, ResumeGenerator resumeGenerator, CoverLetterGenerator coverLetterGenerator,
                            StorageService storage) {
        this.renderer = renderer;
        this.applications = applications;
        this.applicationService = applicationService;
        this.documents = documents;
        this.packages = packages;
        this.analyses = analyses;
        this.analysisService = analysisService;
        this.resumeGenerator = resumeGenerator;
        this.coverLetterGenerator = coverLetterGenerator;
        this.storage = storage;
    }

    public ApplicationPackage buildPackage(User user, UUID applicationId, boolean regenerate, String tone, TokenBudget run) {
        Application app = applications.owned(user, applicationId);
        JobPosting job = app.getJob();
        JobAnalysis analysis = analyses.findByJob(job).orElseGet(() -> analysisService.analyzeJob(user, job.getId(), run));
        GeneratedDocument resume = document(user, app, resumeGenerator, regenerate, run, Map.of());
        GeneratedDocument letter = document(user, app, coverLetterGenerator, regenerate, run, Map.of("tone", tone == null ? "formal" : tone));
        List<GeneratedDocument> docs = List.of(resume, letter);
        List<GeneratedDocument> needsReview = docs.stream().filter(d -> GeneratedDocument.NEEDS_REVIEW.equals(d.getVerificationStatus())).toList();
        String folder = folderName(job);

        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("Resume.pdf", storage.get(resume.getFiles().get("pdf").toString()));
        files.put("Resume.docx", storage.get(resume.getFiles().get("docx").toString()));
        files.put("Cover_Letter.pdf", storage.get(letter.getFiles().get("pdf").toString()));
        files.put("Cover_Letter.docx", storage.get(letter.getFiles().get("docx").toString()));
        files.put("Job_Description.pdf", renderer.renderPdf(jobDescriptionBlocks(job)));
        files.put("Application_Checklist.pdf", renderer.renderPdf(buildChecklist(job, analysis, needsReview)));
        files.put("Application_Info.txt", buildInfoText(app, job).getBytes(StandardCharsets.UTF_8));

        ApplicationPackage pkg = new ApplicationPackage();
        pkg.setUser(user);
        pkg.setApplication(app);
        pkg.setFolderName(folder);
        pkg.setStorageKey(storage.put(storage.newKey(user, "packages", folder + ".zip"), zip(folder, files)));
        pkg.setManifest(files.keySet().stream().sorted().toList());
        pkg.setNeedsReview(!needsReview.isEmpty());
        pkg.setDocumentIds(docs.stream().map(d -> d.getId().toString()).toList());
        packages.save(pkg);
        if ("SAVED".equals(app.getStateName()))
            applicationService.transition(user, app.getId(), "mark_prepared", StatusChange.USER, null, "application package built", null, null);
        return pkg;
    }

    public ApplicationPackage buildPackage(User user, UUID applicationId) { return buildPackage(user, applicationId, false, "formal", null); }

    @Transactional(readOnly = true)
    public byte[] download(User user, UUID packageId) { return storage.get(packages.owned(user, packageId).getStorageKey()); }

    @Transactional(readOnly = true)
    public List<ApplicationPackage> list(User user, UUID applicationId) {
        return packages.findByApplicationOrderByCreatedAtDesc(applications.owned(user, applicationId));
    }

    private GeneratedDocument document(User user, Application app, DocumentGenerator generator, boolean regenerate, TokenBudget run, Map<String, Object> options) {
        GeneratedDocument latest = documents.findFirstByApplicationAndDocTypeOrderByCreatedAtDesc(app, generator.docType()).orElse(null);
        if (latest != null && !latest.isStale() && !regenerate) return latest;
        return generator.generate(user, app, run, options);
    }

    String folderName(JobPosting job) {
        Matcher m = Pattern.compile("(?i)(winter|summer|fall|spring)\\s*(20)?(\\d\\d)").matcher(job.getWorkTerm());
        String term = m.replaceAll(r -> r.group(3) + Character.toUpperCase(r.group(1).charAt(0)));
        List<String> parts = new ArrayList<>();
        for (String p : new String[]{job.getCompany(), job.getTitle(), term}) {
            if (p == null || p.isEmpty()) continue;
            parts.add(p.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", ""));
        }
        String name = String.join("_", parts);
        return name.isEmpty() ? "Application" : name.substring(0, Math.min(120, name.length()));
    }

    private List<Block> jobDescriptionBlocks(JobPosting job) {
        List<Block> blocks = new ArrayList<>();
        blocks.add(Block.of(Block.Kind.TITLE, job.getTitle()));
        blocks.add(Block.of(Block.Kind.SUBTITLE, job.getCompany() + " - " + job.getLocation()));
        String deadline = !job.getDeadlineText().isEmpty() ? job.getDeadlineText() : (job.getDeadline() == null ? "" : job.getDeadline().toString());
        String wm = job.getWorkModel().charAt(0) + job.getWorkModel().substring(1).toLowerCase();
        String[][] meta = {{"Deadline", deadline}, {"Work term", job.getWorkTerm()}, {"Work model", wm}, {"Salary", job.getSalaryText()}, {"Source", job.getSourceUrl()}};
        for (String[] kv : meta) if (!kv[1].isEmpty()) blocks.add(Block.of(Block.Kind.TEXT, kv[0] + ": " + kv[1]));
        blocks.add(Block.of(Block.Kind.HEADING, "Description"));
        String description = job.getDescription().isEmpty() ? "(no description)" : job.getDescription();
        for (String para : description.split("\n\n")) blocks.add(Block.of(Block.Kind.PARAGRAPH, para));
        return blocks;
    }

    private List<Block> buildChecklist(JobPosting job, JobAnalysis analysis, List<GeneratedDocument> needsReview) {
        List<String> items = new ArrayList<>();
        if (!needsReview.isEmpty()) {
            List<String> names = needsReview.stream().map(d -> titleCase(d.getDocType().replace('_', ' '))).toList();
            items.add("Review flagged content in: " + String.join(", ", names) + " (some unsupported lines were removed)");
        }
        List<String> required = job.getRequiredDocuments().isEmpty() ? List.of("Resume", "Cover Letter") : job.getRequiredDocuments();
        required.forEach(d -> items.add("Prepare: " + d));
        if (job.getDeadline() != null || !job.getDeadlineText().isEmpty())
            items.add("Submit before the deadline: " + (job.getDeadlineText().isEmpty() ? job.getDeadline().toString() : job.getDeadlineText()));
        if (!job.getApplicationUrl().isEmpty() || !job.getSourceUrl().isEmpty())
            items.add("Apply at: " + (job.getApplicationUrl().isEmpty() ? job.getSourceUrl() : job.getApplicationUrl()));
        if (analysis != null && !analysis.getKeywords().isEmpty())
            items.add("Mirror these keywords where truthful: " + String.join(", ", analysis.getKeywords().stream().limit(8).toList()));
        if (analysis != null && !analysis.getRedFlags().isEmpty())
            items.add("Double-check concerns: " + String.join("; ", analysis.getRedFlags()));
        items.addAll(List.of("Proofread every document", "Submit the application yourself on the employer's site",
                "Mark the application as submitted in CareerPilot"));
        return List.of(Block.of(Block.Kind.TITLE, "Application Checklist"),
                Block.of(Block.Kind.SUBTITLE, job.getTitle() + " - " + job.getCompany()), Block.bullets(items));
    }

    private static String titleCase(String s) {
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ")) sb.append(sb.length() > 0 ? " " : "").append(w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1).toLowerCase());
        return sb.toString();
    }

    private String buildInfoText(Application app, JobPosting job) {
        String deadline = !job.getDeadlineText().isEmpty() ? job.getDeadlineText() : (job.getDeadline() == null ? "" : job.getDeadline().toString());
        String[][] rows = {{"Job", job.getTitle()}, {"Company", job.getCompany()}, {"Location", job.getLocation()},
                {"Source URL", job.getSourceUrl()}, {"Application URL", job.getApplicationUrl()}, {"Deadline", deadline},
                {"Work term", job.getWorkTerm()}, {"Contact", job.getContact()},
                {"Required documents", String.join(", ", job.getRequiredDocuments())}, {"Status", app.getStateName()}, {"Notes", app.getNotes()}};
        List<String> lines = new ArrayList<>();
        for (String[] r : rows) if (r[1] != null && !r[1].isEmpty()) lines.add(r[0] + ": " + r[1]);
        lines.add("");
        lines.add("CareerPilot prepared this package. It does not submit applications; you do.");
        return String.join("\n", lines) + "\n";
    }

    private byte[] zip(String folder, Map<String, byte[]> files) {
        try (ByteArrayOutputStream buf = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(buf)) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(folder + "/" + e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
            zip.finish();
            return buf.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
