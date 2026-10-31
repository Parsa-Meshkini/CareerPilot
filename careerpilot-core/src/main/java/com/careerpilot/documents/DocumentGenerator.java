package com.careerpilot.documents;

import com.careerpilot.agent.CandidateContext;
import com.careerpilot.agent.ContextBuilder;
import com.careerpilot.agent.ProfileIncompleteException;
import com.careerpilot.applications.Application;
import com.careerpilot.candidates.FactType;
import com.careerpilot.common.StorageService;
import com.careerpilot.jobs.JobAnalysis;
import com.careerpilot.jobs.JobAnalysisRepository;
import com.careerpilot.jobs.JobAnalysisService;
import com.careerpilot.llm.LlmClient;
import com.careerpilot.llm.LlmResult;
import com.careerpilot.llm.TokenBudget;
import com.careerpilot.users.User;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Template Method pattern. {@link #generate} is the fixed pipeline: build grounded context, prompt variables (hook),
 * structured LLM call, fabrication verification, one regeneration with feedback, removal of anything still
 * unsupported, rendering (hook) and persistence. Subclasses supply only the hooks. {@code generate} is final, so no
 * generator can skip the {@link FabricationGuard}.
 */
public abstract class DocumentGenerator {
    protected final LlmClient llm;
    protected final ContextBuilder contexts;
    protected final DocumentRenderer renderer;
    protected final StorageService storage;
    protected final GeneratedDocumentRepository documents;
    protected final JobAnalysisRepository analyses;
    protected final JobAnalysisService analysisService;

    protected DocumentGenerator(LlmClient llm, ContextBuilder contexts, DocumentRenderer renderer, StorageService storage,
                                GeneratedDocumentRepository documents, JobAnalysisRepository analyses, JobAnalysisService analysisService) {
        this.llm = llm;
        this.contexts = contexts;
        this.renderer = renderer;
        this.storage = storage;
        this.documents = documents;
        this.analyses = analyses;
        this.analysisService = analysisService;
    }

    // ---------------- template method ----------------

    public final GeneratedDocument generate(User user, Application application, TokenBudget run, Map<String, Object> options) {
        CandidateContext ctx = contexts.buildCandidateContext(user, false, true);
        checkEnoughFacts(ctx);
        JobAnalysis analysis = analyses.findByJob(application.getJob()).orElseGet(
                () -> analysisService.analyzeJob(user, application.getJob().getId(), run));
        Map<String, Object> variables = buildPromptVariables(ctx, application, analysis, options);
        LlmResult result = llm.generateStructured(user, promptKey(), variables, run);
        Map<String, Object> content = result.asMap();
        String model = result.model();

        FabricationGuard guard = new FabricationGuard(ctx, application.getJob());
        VerificationReport report = verify(guard, content);
        boolean regenerated = false;
        if (!report.passed()) {
            regenerated = true;
            Map<String, Object> retryVars = new LinkedHashMap<>(variables);
            retryVars.put("feedback", "Fix these problems; use only the cited facts:\n" + report.feedback());
            content = llm.generateStructured(user, promptKey(), retryVars, run).asMap();
            report = verify(guard, content);
            if (!report.passed()) content = removeClaims(content, report.badLocations());
        }
        String status = report.passed() ? GeneratedDocument.PASSED : GeneratedDocument.NEEDS_REVIEW;
        Map<String, Object> reportMap = new LinkedHashMap<>(report.toMap());
        reportMap.put("regenerated", regenerated);
        return persist(user, application, ctx, content, status, reportMap, model, result.promptVersion());
    }

    public final GeneratedDocument generate(User user, Application application) {
        return generate(user, application, null, Map.of());
    }

    /** Student edits are verified and rendered through the same guard and renderer. */
    public final GeneratedDocument regenerateFromEdit(User user, GeneratedDocument document, Map<String, Object> content) {
        CandidateContext ctx = contexts.buildCandidateContext(user, false, true);
        VerificationReport report = verify(new FabricationGuard(ctx, document.getApplication().getJob()), content);
        String status = report.passed() ? GeneratedDocument.PASSED : GeneratedDocument.NEEDS_REVIEW;
        Map<String, Object> reportMap = new LinkedHashMap<>(report.toMap());
        reportMap.put("edited", true);
        return persist(user, document.getApplication(), ctx, content, status, reportMap, "student-edit", document.getPromptVersion());
    }

    // ---------------- concrete steps ----------------

    private VerificationReport verify(FabricationGuard guard, Map<String, Object> content) {
        VerificationReport report = guard.check(claims(content));
        extraChecks(content, report);
        return report;
    }

    private GeneratedDocument persist(User user, Application application, CandidateContext ctx, Map<String, Object> content,
                                      String status, Map<String, Object> report, String model, int promptVersion) {
        GeneratedDocument previous = documents.findFirstByApplicationAndDocTypeOrderByCreatedAtDesc(application, docType()).orElse(null);
        GeneratedDocument doc = new GeneratedDocument();
        doc.setUser(user);
        doc.setApplication(application);
        doc.setDocType(docType());
        doc.setVersion(previous == null ? 1 : previous.getVersion() + 1);
        doc.setContent(content);
        doc.setVerificationStatus(status);
        doc.setVerificationReport(report);
        doc.setModelUsed(model);
        doc.setPromptVersion(promptVersion);
        Set<String> cited = new TreeSet<>();
        claims(content).forEach(c -> cited.addAll(c.sourceFactIds()));
        doc.setCitedFactIds(List.copyOf(cited));
        List<Block> blocks = render(ctx, application, content);
        String base = fileStem(application);
        doc.getFiles().put("pdf", storage.put(storage.newKey(user, "documents", base + "_v" + doc.getVersion() + ".pdf"), renderer.renderPdf(blocks)));
        doc.getFiles().put("docx", storage.put(storage.newKey(user, "documents", base + "_v" + doc.getVersion() + ".docx"), renderer.renderDocx(blocks)));
        return documents.save(doc);
    }

    private void checkEnoughFacts(CandidateContext ctx) {
        if (ctx.factsOf(FactType.EXPERIENCE).size() + ctx.factsOf(FactType.PROJECT).size() == 0)
            throw new ProfileIncompleteException("add at least one verified experience or project before generating", "experience");
    }

    // ---------------- hooks ----------------

    /** Optional hook for document-specific rules; it can only add violations. */
    protected void extraChecks(Map<String, Object> content, VerificationReport report) { }

    public abstract String docType();

    protected abstract String promptKey();

    protected abstract Map<String, Object> buildPromptVariables(CandidateContext ctx, Application application, JobAnalysis analysis,
                                                                Map<String, Object> options);

    protected abstract List<Claim> claims(Map<String, Object> content);

    protected abstract Map<String, Object> removeClaims(Map<String, Object> content, Set<String> locations);

    protected abstract List<Block> render(CandidateContext ctx, Application application, Map<String, Object> content);

    protected abstract String fileStem(Application application);

    // ---------------- small JSON helpers shared by subclasses ----------------

    @SuppressWarnings("unchecked")
    protected static List<Map<String, Object>> maps(Object value) {
        return value instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    protected static List<String> ids(Object value) {
        return value instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }

    protected static String str(Object value) { return value == null ? "" : value.toString(); }
}
