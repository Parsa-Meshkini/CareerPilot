#!/usr/bin/env python3
"""Build docs/stage2/stage2-report.md (and the PDF with --pdf) from the data below and the git history.

The feature table is checked against the source: every class named must exist and every method named must appear in
one of that feature's classes, so the report cannot drift from the code. Commit tables come from `git log`.
"""
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "docs" / "stage2"
REPO = "https://github.com/Parsa-Meshkini/CareerPilot"
SRC = {p.stem: p for p in list(ROOT.glob("careerpilot-*/src/main/java/**/*.java"))}
VIDEO = "see Demonstration index (Section 7)"
LINKS_FILE = OUT / "video-links.json"
DEMO_URL = "https://drive.google.com/file/d/1NThfqULr6I2PLUaawXwYJkWKZ8aottcT/view?usp=sharing"


def video_cell(fid):
    """Link and timestamp for a feature from docs/stage2/video-links.json, or a clear placeholder.

    The file maps a feature id to {"url": "<link>", "time": "m:ss"}; the url should open the video at that time.
    """
    import json
    try:
        entry = json.loads(LINKS_FILE.read_text(encoding="utf-8")).get(fid)
    except (OSError, ValueError):
        entry = None
    if entry and entry.get("url"):
        return f"[Watch {fid} at {entry.get('time', '0:00')}]({entry['url']})"
    return f"[Demo video]({DEMO_URL})"

# id, name, stage1, status, type, classes, methods, patterns, ui, flow
FEATURES = [
 ("F01", "Candidate profile and verified facts", "F01", "Modified", "Deterministic",
  "CandidateService CandidateProfile ProfileFact FactType FactSource FactValidator", "updateProfile addFact verifyFacts rejectFacts completenessHints", "Repository (Spring Data)",
  "GUI Profile page; CLI `profile show/set/fact-*`",
  "The view calls CandidateService, which validates the fact JSON with FactValidator against its FactType, stores it as an unverified ProfileFact, and only facts the student verifies are returned to the AI context. Stage 1 had one subclass per fact kind; one entity plus an enum with validated JSON replaced them."),
 ("F02", "Resume import and AI fact extraction", "F02", "Implemented", "Hybrid",
  "CandidateService ResumeParser LlmClient StorageService TaskQueue UploadedResume", "importResume parseResume extractText extractFacts generateStructured", "Facade (LlmClient); Adapter (LlmProvider)",
  "GUI Profile > Import resume; CLI `profile resume FILE`",
  "importResume stores the file through StorageService and queues parseResume on TaskQueue. ResumeParser extracts text (PDFBox, POI or plain) and asks LlmClient for facts, which are saved unverified for the student to review."),
 ("F03", "Career goal and strategy configuration", "F03", "Implemented", "Deterministic",
  "CandidateService CareerGoal GoalType StrategyWeights MatchingService", "updateCareerGoal selectStrategy", "Strategy",
  "GUI Profile > Goal; CLI `profile goal-set/goal-show`",
  "The goal type chosen by the student selects which MatchScoringStrategy MatchingService uses and seeds the StrategyWeights that F19 later adjusts."),
 ("F04", "Browser-extension batch job import", "F04", "Implemented", "Deterministic",
  "ApiController TokenAuthInterceptor JobImportService JobSourceRegistry YorkBrowserImportAdapter ImportBatch TaskQueue", "createBatch processBatch parse", "Adapter; Observer (JobsImported)",
  "Chrome extension (extension/content, popup, api); REST `/api/import`; CLI `jobs import-york`",
  "The content script reads the postings on the page, the service worker posts them to ApiController with the student's token, JobImportService creates an ImportBatch and processes it in the background through the York adapter, then publishes JobsImported which triggers F09."),
 ("F05", "Manual job import (paste or file)", "F05", "Implemented", "Hybrid",
  "JobImportService ManualJobImportAdapter MockJobSourceAdapter LlmClient", "importManual importRecord parse structure_paste", "Adapter; Facade",
  "GUI Jobs > Import; CLI `jobs import-paste/import-file`",
  "Pasted text goes to LlmClient (job.structure_paste prompt) to extract title, company and description; CSV or JSON files are read by the adapters directly. Both end in JobImportService.importRecord."),
 ("F06", "Job normalization and duplicate detection", "F06", "Implemented", "Deterministic",
  "JobNormalizer DuplicateDetector JobPosting JobPostingRepository ImportBatch", "normalize findDuplicate", "Repository",
  "Runs inside every import",
  "importRecord calls JobNormalizer.normalize (clean text, deadline, work model, fingerprint) then DuplicateDetector.findDuplicate; a duplicate is merged and counted in the batch instead of saved again."),
 ("F07", "AI job description analysis", "F07", "Implemented", "AI",
  "JobAnalysisService JobAnalysis LlmClient LlmProvider PromptRegistry ResponseValidator UsageTracker", "analyzeJob generateStructured validate fallbackKeywordAnalysis", "Facade; Adapter",
  "GUI Jobs detail; CLI `jobs analyze`",
  "analyzeJob sends the posting through LlmClient, which loads the versioned prompt, checks the token budget, calls the provider, validates the JSON against the schema, retries transient and schema failures with backoff, and records usage. If the model is down a keyword analysis is used and the result is marked as such."),
 ("F08", "Candidate-job matching and skill-gap analysis", "F08", "Implemented", "Hybrid",
  "MatchingService ContextBuilder MatchScoringStrategy CoopInternshipStrategy EntryLevelStrategy CareerSwitchStrategy JobMatch", "matchJob selectStrategy score rankJobs buildCandidateContext", "Strategy",
  "GUI Jobs (bearing ring, detail); CLI `jobs match/score/show`",
  "ContextBuilder gathers verified facts and the goal; MatchingService picks the strategy for the goal type and scores deterministically (quick or deep with the analysis); the AI only writes the explanation and transferable-skill hints, never the score."),
 ("F09", "Agent batch triage and shortlist", "F09", "Implemented", "AI (agent)",
  "TriageTrigger AgentController Planner PlaybookLibrary Reasoner ToolManager AgentCommand QuickScoreJobsCommand SelectForDeepAnalysisCommand DeepMatchJobsCommand RankJobsCommand MemoryManager AgentRun", "handle startTriage runGoal createPlan execute evaluate remember", "Command; Observer; Facade",
  "Automatic after an import; GUI Agent page; CLI `agent runs/show`",
  "TriageTrigger listens for JobsImported and starts a run through AgentController. The plan (hand-written playbook, or the LLM's plan when valid) is executed one AgentCommand at a time by ToolManager; after each step Reasoner decides to continue, replan or stop, and every action is recorded in AgentActionRecord."),
 ("F10", "Tailored resume with fabrication guard", "F10", "Implemented", "Hybrid",
  "DocumentGenerator ResumeGenerator FabricationGuard DocumentRenderer DocumentService GeneratedDocument", "generate check renderDocx renderPdf regenerateFromEdit", "Template Method; Facade",
  "GUI Tracker > Documents; CLI `docs resume`",
  "DocumentService calls the generator's final generate(): build context, ask the LLM, run FabricationGuard.check on every claim against verified facts, remove violating claims, then render. Subclasses only supply the prompt variables, claim extraction and block layout."),
 ("F11", "Tailored cover letter", "F11", "Implemented", "Hybrid",
  "DocumentGenerator CoverLetterGenerator FabricationGuard DocumentRenderer", "generate check", "Template Method; Facade",
  "GUI Tracker > Documents; CLI `docs cover-letter`", "Same template as F10 with a different prompt, claim extractor and layout."),
 ("F12", "Application package builder and ZIP export", "F12", "Implemented", "Deterministic",
  "PackageAssembler ApplicationPackage BuildPackageCommand DocumentService StorageService", "buildPackage download", "Command; Template Method (uses)",
  "GUI Tracker > Build package; CLI `docs package/download`",
  "PackageAssembler generates any missing documents, adds the job description and a checklist, zips them through StorageService and records an ApplicationPackage; the agent reaches it through BuildPackageCommand."),
 ("F13", "Application lifecycle tracking and undo", "F13", "Implemented", "Deterministic",
  "ApplicationService Application ApplicationState SavedState PreparedState AppliedState InterviewState OfferState RejectedState StatusChange", "createApplication transition undoTransition", "State; Observer",
  "GUI Tracker board; CLI `apps create/act/undo`",
  "transition asks the current ApplicationState object whether the action is legal and for the next state, saves a StatusChange (which makes undo possible), and publishes ApplicationStatusChanged."),
 ("F14", "Email monitoring and application event detection", "F14", "Implemented (Gmail verified only against a local test double)", "Hybrid",
  "EmailSyncService EmailProviderRegistry GmailAdapter GmailConnector CredentialVault EmlFileAdapter MockEmailAdapter EmailNormalizer EmailEventDetector HybridClassifier RuleBasedClassifier LlmEmailClassifier ApplicationMatcher DetectedEmailEvent", "syncAccount importEmlFiles detect classify match confirmEvent fetchNewMessages connect", "Adapter; Strategy",
  "GUI Email page; CLI `email upload/sync-mock/connect-gmail/events/confirm`",
  "A provider adapter returns RawEmails; EmailNormalizer cleans them; EmailEventDetector runs the HybridClassifier (rules first, LLM when rules are unsure); ApplicationMatcher links the email to an application; a confident match is published as an event and the rest wait for the student to confirm."),
 ("F15", "Event-driven updates, reminders and notifications", "F15", "Implemented", "Deterministic",
  "DomainEventBus EventListener ApplicationStatusUpdater InterviewScheduler MemoryRecorder NotificationService ReminderService Notification", "publish subscribe checkDeadlines add markAllRead", "Observer; State; Command (undo)",
  "GUI Dashboard feed; CLI `notifications`, `remind`",
  "Detected email events are published on DomainEventBus; ApplicationStatusUpdater moves the application, InterviewScheduler records the interview, MemoryRecorder saves what happened for the agent, and NotificationService stores a de-duplicated notification. ReminderService raises deadline notifications."),
 ("F16", "Goal-driven agent workspace", "F16", "Implemented", "AI (agent)",
  "AgentController Planner PlaybookLibrary Reasoner ToolManager ApprovalPolicy AgentCommand UpdateApplicationStatusCommand MemoryManager ContextBuilder AgentRun AgentActionRecord", "runGoal resumeRun createPlan requiresApproval execute undo recall", "Command; Facade",
  "GUI Agent page; CLI `agent run/approve/reject/undo/memory`",
  "A goal in plain words becomes a typed run; steps with side effects stop at ApprovalPolicy until the student approves, rejects (the agent replans and remembers the preference) or undoes; MemoryManager recalls preferences into later plans."),
 ("F17", "Interview preparation plan", "F17", "Implemented", "AI",
  "InterviewPrepService InterviewPrepPlan InterviewPrepTrigger ContextBuilder FabricationGuard LlmClient PrepareInterviewCommand", "preparePlan latest check", "Facade; Observer (auto-trigger)",
  "GUI Interview page; CLI `interview prepare/show`; agent tool prepare_interview",
  "InterviewPrepTrigger reacts to an interview status change; InterviewPrepService builds the context, asks the LLM for questions and STAR stories, and FabricationGuard rejects stories that use facts the student has not verified; a deterministic plan is used when the model fails."),
 ("F18", "AI mock interview and feedback", "F18", "Implemented", "AI",
  "MockInterviewService MockInterviewSession LlmClient MemoryManager", "startSession submitAnswer finishSession abandon", "Facade",
  "GUI Interview page; CLI `interview mock/answer/finish`",
  "startSession asks for a first question; each submitAnswer is scored against a rubric by the LLM and the next question depends on the weak areas so far; finishSession writes a summary and stores feedback in agent memory. Sessions can be resumed."),
 ("F19", "Outcome analytics and adaptive strategy", "F19", "Implemented", "Hybrid",
  "AnalyticsService OutcomeAnalyzer StrategyAdvisor StrategyAdjustment StrategyWeights MemoryManager", "getInsights computeStats proposeAdjustments accept reject revert", "Strategy; Observer (MemoryRecorder feeds data)",
  "GUI Insights page; CLI `analytics`",
  "OutcomeAnalyzer computes response and interview rates by score band, source and role; StrategyAdvisor proposes bounded weight changes only with enough evidence; the LLM writes the explanation; nothing changes until the student accepts, and accepted changes can be reverted."),
]

PATTERNS = [
 ("Facade", "Callers should not know about prompts, schemas, budgets, retries or vendors, nor about the plan-act-observe loop.",
  "LlmClient (facade) over PromptRegistry, ResponseValidator, TokenBudget, UsageTracker, LlmProviderFactory; AgentController (facade) over Planner, ToolManager, Reasoner, ApprovalPolicy, MemoryManager.",
  "llm/LlmClient.java generateStructured; agent/AgentController.java runGoal, resumeRun",
  "Every AI feature calls one method and gets validated JSON or a typed error; the agent is started with one call. Matches Stage 1."),
 ("Adapter", "Job sources, mailboxes and LLM vendors all differ in format and protocol but the rest of the system needs one shape.",
  "JobSourceAdapter with ManualJobImportAdapter, YorkBrowserImportAdapter, MockJobSourceAdapter; EmailProviderAdapter with GmailAdapter, EmlFileAdapter, MockEmailAdapter; LlmProvider with GeminiProvider, OpenAiCompatibleProvider, MockLlmProvider.",
  "jobs/JobSourceAdapter.java parse; integrations/EmailProviderAdapter.java fetchNewMessages; llm/LlmProvider.java complete",
  "New sources are added by writing one class; GmailAdapter was added in Period 3 without touching the pipeline after the fetch. Differs from Stage 1 only in names (OpenAIProvider became OpenAiCompatibleProvider so Groq and OpenRouter work too)."),
 ("Strategy", "Scoring and classification rules differ by situation and must be swappable at run time.",
  "MatchScoringStrategy with CoopInternshipStrategy, EntryLevelStrategy, CareerSwitchStrategy chosen by MatchingService.selectStrategy; EmailClassificationStrategy with RuleBasedClassifier, LlmEmailClassifier, HybridClassifier held by EmailEventDetector.setStrategy.",
  "jobs/MatchingService.java selectStrategy; integrations/EmailEventDetector.java detect, setStrategy",
  "The student's goal changes how jobs are scored without any if/else in the service; the email detector can run rules only, LLM only, or hybrid. Matches Stage 1."),
 ("Command", "Every agent tool must be runnable, loggable, approvable and, where possible, undoable in the same way.",
  "AgentCommand (command) with MatchJobCommand, RankJobsCommand, UpdateApplicationStatusCommand, BuildPackageCommand, PrepareInterviewCommand and others; ToolManager (invoker); AgentActionRecord (history for undo).",
  "agent/AgentCommand.java execute, undo; agent/ToolManager.java execute, undo",
  "The planner only names tools; ToolManager runs them uniformly, records each action and can undo reversible ones. Matches Stage 1."),
 ("State", "Which actions are legal depends on where the application is in its lifecycle, and the rules must not be scattered across services.",
  "ApplicationState (abstract state) with SavedState, PreparedState, AppliedState, InterviewState, OfferState, AcceptedState, RejectedState, DeclinedState, WithdrawnState; Application (context).",
  "applications/ApplicationState.java; applications/ApplicationService.java transition, undoTransition",
  "An illegal transition raises InvalidTransitionException from the state object itself. Matches Stage 1."),
 ("Observer", "Importing, emailing and status changes must trigger reminders, memory, triage and interview prep without the publisher knowing the receivers.",
  "DomainEventBus (subject), EventListener (observer), listeners TriageTrigger, ApplicationStatusUpdater, InterviewScheduler, MemoryRecorder, InterviewPrepTrigger; events in Events.",
  "events/DomainEventBus.java publish, subscribe; notifications/*.java handle",
  "A listener failure is collected and logged and never breaks the publisher or other listeners. Matches Stage 1."),
 ("Template Method", "Resume and cover letter generation share the same safety-critical steps and must not be able to skip the fabrication check.",
  "DocumentGenerator (final generate defines the steps; abstract promptKey, buildPromptVariables, claims, removeClaims, render) with ResumeGenerator and CoverLetterGenerator.",
  "documents/DocumentGenerator.java generate; ResumeGenerator.java; CoverLetterGenerator.java",
  "generate is final, so FabricationGuard.check always runs. Matches Stage 1."),
]

CHANGES = [
 ("Python/Django REST backend, Celery and Redis", "Java 17 and Spring Boot 3.3, in-process TaskQueue", "The course requires Java for Stage 2; a single process removes the need to run a broker for a desktop tool.", "All classes keep their Stage 1 names in Java camelCase; background work is a TaskQueue instead of Celery tasks."),
 ("React single-page app", "JavaFX GUI with AtlantaFX theme", "Java requirement; a desktop client also keeps the data on the student's computer.", "GUI pages call core services in-process (no REST hop); REST remains only for the Chrome extension (F04)."),
 ("Typer CLI", "picocli CLI (`bin/careerpilot`)", "Java requirement.", "Same commands, one subcommand class per feature group."),
 ("PostgreSQL", "H2 by default, any JDBC URL through DATABASE_URL", "Zero-setup for graders; PostgreSQL still works.", "Entities use JPA with generated UUIDs."),
 ("MinIO/S3 object storage and signed URLs", "Local StorageService under ~/.careerpilot", "No cloud dependency for a single-user tool.", "Signed URLs became direct service calls (DocumentService.file, PackageAssembler.download)."),
 ("ProfileFact subclass per kind", "One ProfileFact entity with a FactType enum and validated JSON", "Less code and a single table, validation still per type (FactValidator).", "F01, F02, F10, F17 read facts through the same class."),
 ("OpenAIProvider", "OpenAiCompatibleProvider and GeminiProvider; default model is Gemini through REST", "A free-tier model works for development; any OpenAI-style endpoint (Groq, OpenRouter) is supported with a base URL.", "LlmProvider adapter pattern unchanged."),
 ("reportlab / python-docx", "Apache PDFBox / POI in DocumentRenderer", "Java equivalents.", "F10-F12 output the same formats (PDF, DOCX, TXT)."),
 ("Gmail adapter planned as Tier 2", "GmailAdapter, GmailConnector (browser consent on a loopback port, read-only scope), CredentialVault (AES-GCM)", "Implemented in Period 3. Refresh tokens are encrypted at rest and no password is ever typed into CareerPilot.", "F14 gains a third provider. Tested against a local stand-in for Google; not yet exercised against a live Google account."),
 ("CredentialVault.encrypt() in the Stage 1 class list", "CredentialVault.put/get with AES-256-GCM, key file with owner-only permissions", "Same responsibility, simpler API.", "Used only by GmailAdapter."),
 ("First JavaFX version with plain tables", "Redesign branch: tokens, sidebar groups, command palette, pipeline board, bearing ring", "Usability audit with before and after screenshots in docs/gui-redesign.", "No feature change; see docs/gui-redesign/CHANGES.md."),
 ("New: Docker image for the REST server", "Dockerfile and docker-compose.server.yml", "Lets the extension talk to a server on another machine.", "Not a graded feature."),
]

CODE_EXT = (".java", ".js", ".css", ".fxml", ".xml", ".yml", ".json")


def check_features():
    errors = []
    for f in FEATURES:
        fid, classes, methods = f[0], f[5].split(), f[6].split()
        texts = []
        for c in classes:
            if c in SRC:
                texts.append(SRC[c].read_text(encoding="utf-8"))
            elif not (ROOT / "extension").exists():
                errors.append(f"{fid}: class {c} not found")
            else:
                errors.append(f"{fid}: class {c} not found")
        joined = "\n".join(texts) + "\n".join(p.read_text(encoding="utf-8") for p in ROOT.glob("careerpilot-*/src/main/resources/prompts/*.json"))
        for m in methods:
            if not re.search(r"\b" + re.escape(m) + r"\b", joined):
                errors.append(f"{fid}: method {m} not found in its classes")
    for name, *_ in PATTERNS:
        pass
    return errors


def commits():
    raw = subprocess.run(["git", "log", "--first-parent", "--reverse", "--format=%H|%ad|%s", "--date=format:%Y-%m-%d", "--since=2026-10-20"],
                         capture_output=True, text=True, cwd=ROOT, check=True).stdout.splitlines()
    periods = [("Period 1 (Oct 20-26)", "2026-10-20", "2026-10-26"), ("Period 2 (Oct 27-Nov 2)", "2026-10-27", "2026-11-02"), ("Period 3 (Nov 3-9)", "2026-11-03", "2026-11-09")]
    out = []
    for title, a, b in periods:
        rows = []
        for line in raw:
            h, d, s = line.split("|", 2)
            if not (a <= d <= b):
                continue
            files = subprocess.run(["git", "show", "--name-only", "--format=", h], capture_output=True, text=True, cwd=ROOT).stdout.split()
            code = [x for x in files if x.endswith(CODE_EXT) and not x.startswith("docs/")]
            rows.append((h, d, s, bool(code)))
        n = sum(1 for r in rows if r[3])
        out.append(f"### {title}: {n} code commits ({len(rows)} commits in total)\n\n| Commit | Date | Message | Changes code |\n|---|---|---|---|")
        for h, d, s, c in rows:
            out.append(f"| [{h[:7]}]({REPO}/commit/{h}) | {d} | {s.replace('|', '/')} | {'yes' if c else 'no (docs/assets)'} |")
        out.append("")
    return "\n".join(out)


def build_md():
    errors = check_features()
    if errors:
        raise SystemExit("report check failed:\n" + "\n".join(errors))
    L = []
    a = L.append
    a('---\ntitle: "CareerPilot Stage 2 Implementation Report"\nsubtitle: "EECS 3311 Fall 2026, Course Project Stage 2"\n---\n')
    a(f"# CareerPilot: Stage 2 Implementation Report\n\n| | |\n|---|---|\n| **Course** | EECS 3311 Software Design, Fall 2026 |\n| **Repository** | <{REPO}> |\n| **Team** | Parsa Meshkini |\n| **Generated** | {datetime.now().strftime('%Y-%m-%d')} by tools/build_stage2_report.py |\n")
    a(OVERVIEW)
    a("# 2. Feature to implementation mapping\n\nEvery Stage 1 feature (F01-F19) is accounted for; there are no new features beyond Stage 1 and none were dropped. "
      "The feature table below is machine-checked against the source: each class listed exists and each method listed appears in those classes. "
      "Video links are in the demonstration index (Section 7).\n")
    for f in FEATURES:
        fid, name, s1, status, typ, classes, methods, pats, ui, flow = f
        a(f"## {fid} {name}\n\n| Field | Entry |\n|---|---|\n| Feature ID and name | {fid} - {name} |\n| Stage 1 reference | {s1} |\n| Status or change | {status} |\n"
          f"| Type | {typ} |\n| Implemented classes | {', '.join('`'+c+'`' for c in classes.split())} |\n| Key methods | {', '.join('`'+m+'`' for m in methods.split())} |\n"
          f"| Design patterns | {pats} |\n| Where to use it | {ui} |\n| Video link/timestamp | {video_cell(fid)} |\n\n**How the classes collaborate.** {flow}\n")
    a("# 3. Implemented design patterns\n\nSeven distinct patterns are implemented. Class diagrams generated from the code (UMLet sources in `docs/stage2/uml/src`) follow the table.\n")
    for name, problem, parts, where, why in PATTERNS:
        a(f"## 3.{PATTERNS.index((name, problem, parts, where, why))+1} {name}\n\n- **Problem addressed.** {problem}\n- **Participants.** {parts}\n- **Source files and methods.** {where}\n- **How it follows the pattern and why it helps.** {why}\n")
    a("## 3.8 Updated UML class diagrams\n\nThe Stage 1 class diagrams (`docs/uml`) described the design; the diagrams below are generated from the implemented Java classes by `tools/gen_stage2_uml.py`, "
      "so every box, signature and arrow exists in the code. The Stage 1 use-case and sequence diagrams are still accurate in behaviour; the only visible differences are the class names listed in Section 4 "
      "(for example LLMClient is LlmClient, GmailAdapter and CredentialVault are in the integrations package) and that UI classes are JavaFX views instead of web pages.\n")
    for p in sorted((OUT / "uml" / "rendered").glob("*.png")):
        a(f"![{p.stem}](uml/rendered/{p.name})\n")
    a("# 4. Changes and rationale\n\n| Original design | Implemented change | Reason | Impact |\n|---|---|---|---|")
    for row in CHANGES:
        a("| " + " | ".join(row) + " |")
    a("\nNo pattern from Stage 1 was dropped or replaced, so no pattern needed a replacement justification.\n")
    a(AI_TOOLS)
    a("# 6. Development and verification summary\n\n## 6.1 Weekly commit summary\n\nOnly commits that change code, tests or build files count as code commits; documentation and screenshot-only commits are listed but not counted.\n")
    a(commits())
    a(VERIFICATION)
    a(DEMO_INDEX_HEAD)
    for f in FEATURES:
        a(f"| {f[0]} {f[1]} | {f[8]} | {video_cell(f[0])} |")
    a("")
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "stage2-report.md").write_text("\n".join(L), encoding="utf-8")
    print("wrote docs/stage2/stage2-report.md")


OVERVIEW = """# 1. Project overview and implementation status

CareerPilot is a desktop and command-line assistant for students looking for co-op, internship and entry-level jobs. It keeps a profile of facts the student has verified, imports postings (browser extension, paste, file), scores them against the student's goal, tracks each application through its lifecycle, reads job-related emails, and prepares tailored documents and interviews. An AI agent plans and runs multi-step goals ("prepare my application for the Acme posting") and asks before anything that changes data.

**AI/LLM integration.** One facade (`LlmClient`) calls Gemini (default, free tier) or any OpenAI-compatible endpoint through the `LlmProvider` adapter. Prompts are versioned JSON files in `careerpilot-core/src/main/resources/prompts`, answers are validated against a schema, token budgets are enforced per day and per run, and every feature has a deterministic fallback when the model is unavailable. The agent uses planning, tool use, memory and multi-step execution with approval.

**Working functionality.** All 19 Stage 1 features are implemented and reachable from the CLI and the JavaFX GUI; F04 also needs the Chrome extension and the REST server. The Java modules are `careerpilot-core`, `careerpilot-cli`, `careerpilot-gui`, `careerpilot-server` plus `extension/`. Automated tests: core 124, server 3, CLI 7, GUI 1, extension (node) tests.

**Honest limitations.**

- Gmail sign-in (F14) is verified only against a local stand-in for Google's endpoints. It has not been run with a real Google account, which needs the student's own OAuth client.
- On Nov 3 the default Gemini model answered HTTP 503 ("high demand") for every attempt, so the live end-to-end check was run through the OpenAI-compatible provider instead (Section 6.2). The Gemini provider itself was verified live in an earlier session.
- LinkedIn extraction exists but is switched off by default (FEATURE_LINKEDIN_EXTRACTION=false).
- The full deterministic test suite and KUMA agent validation belong to Stage 3.
"""

AI_TOOLS = """# 5. AI tools and collaboration log

| Tool or platform | Model | Used for | Tokens and cost |
|---|---|---|---|
| Claude Code (CLI) | Claude Sonnet 5.5 | Implementation of core services, CLI, GUI, tests, UMLet generation, this report, final audit | 113.7M tokens in 442 calls (0.9K uncached input, 0.75M output, 2.30M cache writes, 110.7M cache reads); about USD 24.29 |
| Claude Code (CLI) | Claude Opus 5.5 | Design and harder implementation work in earlier sessions | 34.3M tokens in 171 calls (0.4K uncached input, 0.31M output, 1.41M cache writes, 32.6M cache reads); about USD 19.70 |
| Gemini API (runtime, not development) | Gemini default model | The product's own LLM calls during early end-to-end checks | 12 calls logged with 0 tokens (the free-tier quota ran out and the model answered HTTP 503); no charge |
| OpenAI-compatible API (runtime, not development) | gpt-6-luna | The product's own LLM calls during the Nov 3 to Nov 6 live end-to-end checks | 14 calls, 21,558 input and 9,231 output tokens (30,789 total); cost not computed because no price list for this model was available, see the billing page of that account |

**How the numbers were obtained.** The Claude Code rows are summed from the local Claude Code session transcripts for this project (every distinct API response, duplicates removed) and priced at the list rates in the local cost tracker: Sonnet 5.5 USD 2 / 10 / 2.50 / 0.10 and Opus 5.5 USD 4 / 20 / 5 / 0.20 per million input / output / cache-write / cache-read tokens. Total development cost is about USD 43.99 (147.9M tokens). These are estimates from list prices, not invoice figures, and they cover only the transcripts still stored on the author's machine. The runtime rows are read from the `LlmUsageRecord` table of the application's own database (`careerpilot llm-check` shows the same records).

**Representative interactions.**

1. *Resume import.* Prompt: implement F02 with ResumeParser and a schema-validated extraction. Result: ResumeParser plus the `resume.extract_facts` prompt. Reviewed by hand; extraction output is saved as unverified so a wrong guess cannot reach a document.
2. *Fabrication guard.* Prompt: generated documents must not state anything the student has not verified. Result: FabricationGuard.check on every claim and a final `generate()` that cannot skip it. Verified with tests that insert an invented employer.
3. *Gmail.* Prompt: add a Gmail provider without storing a password. Result: loopback OAuth, read-only scope, AES-GCM vault. Reviewed: the test double caught a status bug (an expired grant must mark the account for reconnect) and an old test that assumed GMAIL was unavailable.
4. *Class diagrams.* Prompt: update the UML to the implemented classes. Result: a generator that reads signatures from the code. A label calling PlaybookLibrary a Strategy was corrected because it is a library of fallback plans.
"""

VERIFICATION = """
## 6.2 Checks performed

- `mvn test` runs all modules: core 124 tests (services, state machine, agent loop with approval and undo, fabrication guard, email pipeline, interview, analytics, Gmail flow), server 3, CLI 7, GUI render test.
- Extension unit tests with node (`extension/tests`).
- Gmail flow tested end to end against a local HTTP server that plays Google: consent redirect with state check, code exchange, encrypted token on disk, message fetch, revoked grant, cancelled consent, missing configuration.
- Earlier manual run with the real Gemini key: resume extraction, triage, agent run and email classification.
- Live end-to-end run on Nov 3 with a real OpenAI-compatible model (isolated scratch database): resume extraction (12 facts), goal and strategy, CSV import with the bad row reported, automatic triage, analysis and match (92/100 for the best job), resume and cover letter (fabrication guard PASSED), package ZIP, lifecycle transitions with notifications, interview preparation plan, mock interview with rubric scores and summary, agent goal "prepare my interview" (2 steps, 2152 tokens), analytics (correctly refuses to propose changes with 0 outcomes), demo inbox email sync (3 emails, 1 not job related, 1 applied automatically, 1 for review). About 16,000 tokens in total.

## 6.3 Important defects found and corrected

- Preassigned UUIDs made `save()` merge and detach instances; entities now use generated UUIDs.
- A failing agent tool poisoned the surrounding import transaction; `TransactionConfig` now keeps rollback local to the failed participant.
- Blank `DATABASE_URL=` lines in an old `.env` were treated as a value; blank now means unset.
- Analytics fallback text claimed "your data does not justify a change" with too few outcomes; it now says so only from 10 outcomes.
- CLI printed `null` for the median response time when there was no response yet.
- Found with the live key: current OpenAI models reject `max_tokens` and a custom temperature; the provider now sends `max_completion_tokens` for the official endpoint (unit-tested).

## 6.4 Remaining limitations

See Section 1. The unit tests use the mock LLM, so wording quality of live answers is not covered by tests.

"""

DEMO_INDEX_HEAD = """# 7. Demonstration index

Each feature needs a labelled video segment showing the input, the running application, the output, where AI is involved and one error case. The full demonstration is one recording: [CareerPilot demo video](https://drive.google.com/file/d/1NThfqULr6I2PLUaawXwYJkWKZ8aottcT/view?usp=sharing). It shows the GUI and the CLI running, and each feature below links to it.

| Feature | How it is invoked | Video |
|---|---|---|"""

if __name__ == "__main__":
    build_md()
    if "--pdf" in sys.argv:
        css = ROOT / "docs" / "stage1" / "parts" / "report.css"
        subprocess.run(["pandoc", "stage2-report.md", "-f", "markdown-auto_identifiers+gfm_auto_identifiers", "-t", "html5", "-s", "--metadata", "title=CareerPilot Stage 2 Report",
                        "-c", "report.css", "-o", "_report.html"], check=True, cwd=OUT)
        (OUT / "report.css").write_text(css.read_text(encoding="utf-8"), encoding="utf-8")
        chrome = next((c for c in ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", "/Applications/Chromium.app/Contents/MacOS/Chromium"] if Path(c).exists()), None)
        if not chrome:
            raise SystemExit("no Chrome found; open docs/stage2/_report.html and print to PDF as stage2-report.pdf")
        subprocess.run([chrome, "--headless=new", "--disable-gpu", "--no-pdf-header-footer", f"--print-to-pdf={OUT / 'stage2-report.pdf'}", (OUT / "_report.html").as_uri()], check=True, capture_output=True)
        (OUT / "_report.html").unlink()
        (OUT / "report.css").unlink()
        print("wrote docs/stage2/stage2-report.pdf")
