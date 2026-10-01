
---

# 4. Feature Specifications

19 features, grouped by the agent loop. None of them is a trivial operation (login, logout, about and similar are excluded, per the instructions). **Tier 1** features (12) are the committed Stage 2 core and on their own satisfy every Stage 1 minimum. **Tier 2** (6) complete the differentiators. **Tier 3** (1) is stretch.

| ID | Feature | Type | Tier | Group |
|---|---|---|---|---|
| F01 | Candidate Profile & Verified Facts | Deterministic | 1 | Profile |
| F02 | Resume Import & AI Fact Extraction | Hybrid | 1 | Profile |
| F03 | Career Goal & Strategy Configuration | Deterministic | 1 | Profile |
| F04 | Browser-Extension Batch Job Import | Deterministic | 1 | Intake |
| F05 | Manual Job Import (paste / file) | Hybrid | 1 | Intake |
| F06 | Job Normalization & Duplicate Detection | Deterministic | 1 | Intake |
| F07 | AI Job Description Analysis | AI | 1 | Decide |
| F08 | Candidate–Job Matching & Skill-Gap Analysis | Hybrid | 1 | Decide |
| F09 | Agent Batch Triage & Prioritized Shortlist | AI (agent) | 2 | Decide |
| F10 | Tailored Resume Generation with Fabrication Guard | AI / Hybrid | 1 | Prepare |
| F11 | Tailored Cover Letter Generation | AI / Hybrid | 2 | Prepare |
| F12 | Application Package Builder & ZIP Export | Deterministic | 1 | Prepare |
| F13 | Application Lifecycle Tracking & Undo | Deterministic | 1 | Track |
| F14 | Email Monitoring & Application Event Detection | Hybrid | 1 (EML/mock) · 2 (Gmail OAuth) | Observe |
| F15 | Event-Driven Updates, Reminders & Notifications | Deterministic | 2 | Observe |
| F16 | Goal-Driven Agent Workspace | AI (agent) | 1 | Agent |
| F17 | Interview Preparation Plan | AI | 2 | Interview |
| F18 | AI Mock Interview & Feedback | AI | 3 | Interview |
| F19 | Outcome Analytics & Adaptive Strategy | Hybrid | 2 | Learn |

Each specification below follows the eight items required by the instructions (ID/name, description, user interaction, input, output, AI involvement, expected workflow, error/alternative cases) and adds the design links (use case, classes, methods, sequence diagram, patterns) that Task 3 requires.

---

### F01 — Candidate Profile & Verified Facts

- **Description:** Maintains the student's structured profile: education, experience, projects, skills and certifications. Each item is stored as a `ProfileFact` with a `verified` flag and a `source` (MANUAL or RESUME_EXTRACTION). The set of verified facts is the **only** material CareerPilot may use to describe the student (the foundation of the anti-fabrication rule).
- **User interaction:** *Profile* page: add/edit/delete facts in typed forms; a "Verified" badge shows per fact; bulk-verify extracted facts. CLI: `careerpilot profile show`.
- **Input:** Typed fact fields (e.g., Experience: organization, title, dates, bullets).
- **Output:** Updated `CandidateProfile`; count of verified facts; profile-completeness hints ("no verified projects yet").
- **AI involvement:** Deterministic.
- **Expected workflow:** Student edits → `ProfileAPI` → `CandidateService.updateProfile()/addFact()` → validation (date order, required fields) → `CandidateRepository.saveFacts()`; manually entered facts are verified on save; extracted facts need explicit verification (`verifyFacts()` → `ProfileFact.verify()`).
- **Errors / alternatives:** Invalid dates or empty required fields → 400 with field errors. Deleting a fact that generated documents cite → documents are marked "stale – regenerate". Another user's fact id → 404 (tenant scoping).
- **Design links:** UC01 · `ProfilePage`, `ProfileAPI`, `CandidateService`, `CandidateProfile`, `ProfileFact` (+5 subclasses), `CandidateRepository` · SD01 · Repository (architectural).

### F02 — Resume Import & AI Fact Extraction

- **Description:** Upload an existing resume (PDF/DOCX). CareerPilot extracts text and uses the LLM to propose structured facts, which the student reviews, edits and verifies before they can be used anywhere.
- **User interaction:** *Profile → Import resume*: drag-and-drop, then a review table of proposed facts with accept/edit/reject per row.
- **Input:** PDF or DOCX ≤ 5 MB.
- **Output:** `UploadedResume` (parse status) + proposed `ProfileFact`s (`verified = false`).
- **AI involvement:** Hybrid: deterministic text extraction (pdfplumber / python-docx), then LLM structured extraction (`resume.extract_facts`, JSON schema).
- **Expected workflow:** Upload → `CandidateService.importResume()` stores the file (`StorageService.put`) and enqueues `parse_resume` → worker: `ResumeParser.extractText()` → `extractFacts()` → `LLMClient.generateStructured()` → `CandidateRepository.saveFacts()` → student verifies (F01).
- **Errors / alternatives:** Wrong type or size → 400. Scanned PDF with no text → FAILED status and a prompt to enter facts manually (OCR is a future extension). LLM unavailable → status FAILED_RETRYABLE, retry button. Extracted date ranges inconsistent → fact flagged for review.
- **Design links:** UC02 · `ProfilePage`, `ProfileAPI`, `CandidateService`, `ResumeParser`, `LLMClient`, `StorageService`, `TaskQueue`, `CandidateRepository` · SD01 · Facade (LLMClient), Adapter (LLMProvider).

### F03 — Career Goal & Strategy Configuration

- **Description:** The student states what they are looking for: goal type (CO-OP, INTERNSHIP, NEW_GRAD, CAREER_SWITCH), target roles, locations, work term, work model and minimum match score. The goal type selects the **matching algorithm** (Strategy), and the goal carries adjustable `StrategyWeights` that F19 can later tune.
- **User interaction:** *Profile → Career goals* form; CLI `careerpilot goal set --type coop --term "Summer 2027" --roles backend,devops`.
- **Input:** Goal form fields.
- **Output:** Saved `CareerGoal` + active strategy key (e.g., `coop_internship`).
- **AI involvement:** Deterministic.
- **Expected workflow:** `CandidateService.updateCareerGoal()` → `MatchingService.selectStrategy(goal)` (validates that a strategy exists for the goal type) → save. Existing matches are marked "outdated", so they are recomputed lazily on the next view or triage.
- **Errors / alternatives:** Unknown goal type → 400. Work term required for CO-OP → 400. Changing the goal type resets `StrategyWeights` to that strategy's defaults (with confirmation).
- **Design links:** UC03 · `ProfilePage`, `ProfileAPI`, `CandidateService`, `CareerGoal`, `StrategyWeights`, `MatchingService`, `MatchScoringStrategy` · SD01 · **Strategy**.

### F04 — Browser-Extension Batch Job Import

- **Description:** On a supported job-list page that the student has open (Experience York in Stage 2), the extension detects the visible postings and, when the student clicks **Import**, sends them to CareerPilot as one batch. The backend processes the batch asynchronously and the dashboard shows progress.
- **User interaction:** Extension popup: "47 postings detected on this page → Import". *Jobs* page: batch progress bar (imported / duplicates / failed). Opening an individual posting and clicking *Import* again upgrades it to full detail.
- **Input:** DOM of the current page (read by the content script only on user action), CareerPilot API token.
- **Output:** `ImportBatch` with counts; new or merged `JobPosting`s; a `JobsImportedEvent` that triggers triage (F09).
- **AI involvement:** Deterministic (DOM parsing and field mapping; no LLM in the extension).
- **Expected workflow:** `ContentScript.detectPostings()` → `YorkExtractor.matches()/extractList()` → `ExtensionPopup.showDetected()` → click → `ExtensionServiceWorker.sendBatch()` → `JobImportAPI.createBatch()` → `JobImportService.createBatch()` (registry → `YorkBrowserImportAdapter.parse()/validate()`) → enqueue → `processBatch()` (F06) → publish event.
- **Errors / alternatives:** Unsupported page → popup offers paste import (F05). Not signed in / token expired → 401 and a sign-in prompt. Portal markup changed → extractor returns 0 records; the popup reports "page layout not recognized" (extractor versions are sent with each batch for diagnosis). Batch > 200 postings → split into chunks client-side. Partial record failures → batch status PARTIAL_FAILED with per-record reasons. **Never:** background crawling, auto-pagination, credential capture or CAPTCHA/MFA handling.
- **Design links:** UC04 (+ include UC06) · `ExtensionPopup`, `ContentScript`, `SiteExtractor`/`YorkExtractor`, `ExtensionServiceWorker`, `JobImportAPI`, `JobImportService`, `JobSourceRegistry`, `YorkBrowserImportAdapter`, `ImportBatch`, `TaskQueue`, `BackgroundWorker` · SD02 · **Adapter**, Observer.

### F05 — Manual Job Import (paste / file)

- **Description:** Import a single posting by pasting its text (the supported path for LinkedIn and company sites), or import several from a JSON/CSV file (CLI). The LLM structures pasted free text into posting fields, which the student can correct.
- **User interaction:** *Jobs → Add job → Paste text* with a source selector (LinkedIn / company site / other) and an editable preview form. CLI: `careerpilot jobs import postings.json`.
- **Input:** Free text (≤ 20k chars) or a JSON/CSV file of postings.
- **Output:** One or more `JobPosting`s (`completeness = FULL` for pasted text).
- **AI involvement:** Hybrid: LLM structuring (`job.structure_paste`) for free text; deterministic for JSON/CSV (`MockJobSourceAdapter` format).
- **Expected workflow:** `JobsPage.pasteJob()` → `JobImportAPI.importManual()` → `JobImportService.importManual()` → `ManualJobImportAdapter.parse()` (LLM) → `validate()` → `JobNormalizer.normalize()` → `DuplicateDetector.findDuplicate()` → `JobRepository.save()`.
- **Errors / alternatives:** LLM unavailable → the raw text is kept as the description and the student fills the fields manually. Missing title or company after structuring → form validation. Duplicate → "already in your jobs" with a link.
- **Design links:** UC05 (+ include UC06) · `JobsPage`, `JobImportAPI`, `JobImportService`, `ManualJobImportAdapter`, `MockJobSourceAdapter`, `LLMClient` · SD03 · Adapter, Facade.

### F06 — Job Normalization & Duplicate Detection

- **Description:** Converts every source-specific record into the canonical `JobPosting` (clean text, parsed dates and deadlines, normalized work model and location, computed `fingerprint`) and prevents duplicates across imports and sources, merging a later full-detail import into an earlier summary.
- **User interaction:** Implicit in F04/F05; the *Jobs* page shows "3 duplicates merged" and a "merged from 2 sources" badge.
- **Input:** `RawJobRecord`.
- **Output:** New or merged `JobPosting`; `ImportBatch.recordResult(IMPORTED | DUPLICATE | FAILED)`.
- **AI involvement:** Deterministic.
- **Expected workflow:** `JobNormalizer.normalize()` → `JobPosting.computeFingerprint()` (normalized company + title + location + posting month) → `DuplicateDetector.findDuplicate()`: exact `(userId, sourceType, externalId)` first, then fingerprint → `mergeFrom()` (fuller and newer fields win) or `save()`.
- **Errors / alternatives:** Unparseable deadline → stored as text with a `deadline = null` warning. Two different jobs sharing a fingerprint (rare) → the student can "split" them in the GUI (future: similarity threshold tuning).
- **Design links:** UC06 · `JobNormalizer`, `DuplicateDetector`, `JobPosting`, `JobRepository`, `ImportBatch` · SD02, SD03 · Repository (architectural).

### F07 — AI Job Description Analysis

- **Description:** Extracts a structured view of a posting: required vs. preferred skills, responsibilities, seniority, keywords and red flags (e.g., unpaid, "5+ years" for an intern role). This analysis feeds matching, generation and interview preparation.
- **User interaction:** *Job detail → Analyze* (automatic for shortlisted jobs); results panel with skill chips. CLI `careerpilot jobs analyze <jobId>`.
- **Input:** `JobPosting` (ideally FULL).
- **Output:** `JobAnalysis`.
- **AI involvement:** AI (`job.analyze` with a JSON schema), with deterministic keyword fallback.
- **Expected workflow:** `JobAPI.analyzeJob()` → enqueue → `JobAnalysisService.analyzeJob()` → `LLMClient.generateStructured()` (budget check, template, provider, validation, usage record) → `JobRepository.saveAnalysis()`.
- **Errors / alternatives:** SUMMARY-only posting → analysis marked *partial* and the student is prompted to import full detail. Provider failure after 2 retries → `fallbackKeywordAnalysis()` (skill dictionary match), labelled as such. Budget exceeded → queued until the next budget window, and the student is informed.
- **Design links:** UC07 · `JobDetailPage`, `JobAPI`, `JobAnalysisService`, `JobAnalysis`, `LLMClient`, `LLMProvider`, `PromptRegistry`, `ResponseValidator`, `UsageTracker` · SD04 · Facade, Adapter.

### F08 — Candidate–Job Matching & Skill-Gap Analysis

- **Description:** Scores the fit between the student's verified profile and a posting (0–100, with a breakdown by skills, experience, preferences and constraints) using the goal-appropriate strategy. It explains the score and lists missing skills with concrete learning suggestions.
- **User interaction:** *Job detail* match card; *Jobs* list sorted by score; compare up to 4 jobs side by side (scores, breakdowns, deadlines).
- **Input:** `CandidateContext` (verified facts, goal, weights, preferences), `JobPosting`, `JobAnalysis`.
- **Output:** `JobMatch` (score, breakdown, matched/missing skills, explanation).
- **AI involvement:** Hybrid: deterministic scoring in `CoopInternshipStrategy` / `EntryLevelStrategy`; `CareerSwitchStrategy` additionally asks the LLM to map transferable skills; the explanation and gap suggestions are LLM-generated.
- **Expected workflow:** `MatchingService.matchJob()` → `ContextBuilder.buildCandidateContext()` → `selectStrategy(goal)` → `MatchScoringStrategy.score()` → `explainMatch()` (LLM) → `JobRepository.saveMatch()`.
- **Errors / alternatives:** No verified facts → `ProfileIncompleteError` and a GUI prompt. Co-op work-term mismatch → hard-constraint penalty shown explicitly. Explanation LLM failure → score shown without the narrative.
- **Design links:** UC07 · `MatchingService`, `ContextBuilder`, `MatchScoringStrategy`, `CoopInternshipStrategy`, `EntryLevelStrategy`, `CareerSwitchStrategy`, `JobMatch`, `JobRepository` · SD04 · **Strategy**.

### F09 — Agent Batch Triage & Prioritized Shortlist

- **Description:** After a batch import, the agent automatically plans and executes a triage. It quick-scores every posting, decides which postings deserve expensive deep analysis within the budget, deep-analyzes those, ranks everything, and produces a shortlist with reasons and deadlines.
- **User interaction:** Automatic after import; *Dashboard* card "12 of 47 shortlisted — view reasoning". The student can open the run trace in the Agent Workspace. CLI `careerpilot jobs triage <batchId>`.
- **Input:** `JobsImportedEvent` (batch id), profile context, memories.
- **Output:** Updated `JobMatch`es, shortlisted postings, `AgentRun` trace, notification.
- **AI involvement:** AI (agent): LLM planning, rule-based decisions, LLM analysis for top-K.
- **Expected workflow:** `TriageTrigger.handle()` → enqueue → `AgentController.runGoal(TRIAGE_BATCH)` → `Planner.createPlan()` (or playbook) → loop `ToolManager.execute(MatchJobCommand / AnalyzeJobCommand / RankJobsCommand)` → `Reasoner.evaluate()` → `replan()` if needed → `MemoryManager.remember()` → `NotificationService.notify()`.
- **Errors / alternatives:** Budget exhausted → run ends with a partial shortlist, clearly labelled. Most postings SUMMARY-only → replan: rank on quick score and ask the student to open the top postings for full import. The student disabled auto-triage in Settings → no run; manual trigger remains.
- **Design links:** UC08 (+ include UC07) · `TriageTrigger`, `AgentController`, `Planner`, `PlaybookLibrary`, `Reasoner`, `ToolManager`, `AgentCommand`, `MatchJobCommand`, `AnalyzeJobCommand`, `RankJobsCommand`, `MemoryManager`, `AgentRun` · SD05 · **Command**, **Observer**, Strategy.

