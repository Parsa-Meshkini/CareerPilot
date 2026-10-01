
### F10 — Tailored Resume Generation with Fabrication Guard

- **Description:** Generates a one-page resume tailored to a specific application. It selects, orders and rephrases the student's **verified** facts to emphasize what the posting asks for. Every bullet must cite the facts it came from, and a deterministic guard rejects anything not supported by them.
- **User interaction:** *Application → Documents → Generate resume* (or as part of the package, F12). A side-by-side view shows each bullet with its source facts highlighted; the student can edit before exporting.
- **Input:** Application (→ job + analysis), `CandidateContext`, template name.
- **Output:** `GeneratedDocument`s: `Resume.pdf` and `Resume.docx` (versioned), `verificationStatus` PASSED or NEEDS_REVIEW.
- **AI involvement:** AI generation + deterministic verification (hybrid).
- **Expected workflow (Template Method `DocumentGenerator.generate()`):** build context → `buildPromptVariables()` (hook) → `LLMClient.generateStructured("resume.tailor")` → `verify()` → `FabricationGuard.check()` → on violations regenerate once with feedback → `render()` (hook) → `DocumentRenderer.renderDocx()/renderPdf()` → `persist()` (storage + repository).
- **Guard rules:** each bullet's `sourceFactIds` must exist, be verified and belong to the student. Any number, percentage, organization name, degree or skill in the bullet must appear in the cited facts. Section headings may only list skills present in verified `Skill` facts.
- **Errors / alternatives:** Violations persist after one regeneration → offending bullets removed and the document marked NEEDS_REVIEW (never silently exported as PASSED). Too few verified facts → generation refused with guidance. Renderer failure → the DOCX is still delivered and the PDF is retried.
- **Design links:** UC09 · `DocumentGenerator`, `ResumeGenerator`, `ContextBuilder`, `LLMClient`, `FabricationGuard`, `VerificationReport`, `DocumentRenderer`, `StorageService`, `DocumentRepository` · SD06 · **Template Method**, Facade.

### F11 — Tailored Cover Letter Generation

- **Description:** Generates a ≤ 350-word cover letter for the application: why this role and company (from the posting only) and why this student (from cited verified facts), in the student's chosen tone.
- **User interaction:** *Application → Documents → Generate cover letter*; tone selector (formal / warm); editable preview.
- **Input:** Same as F10, plus tone.
- **Output:** `Cover_Letter.pdf`, `Cover_Letter.docx`.
- **AI involvement:** AI + deterministic verification.
- **Expected workflow:** Same template as F10 with `CoverLetterGenerator` hooks (`promptKey() = cover_letter.tailor`, its own schema with paragraph-level `sourceFactIds`, letter templates).
- **Errors / alternatives:** Company facts not in the posting are not invented. If the posting lacks company information, the letter stays role-focused. The guard handles length > 350 words or unsupported claims as in F10.
- **Design links:** UC09 · `DocumentGenerator`, `CoverLetterGenerator`, `FabricationGuard`, `LLMClient`, `DocumentRenderer` · SD06 · **Template Method**, Facade.

### F12 — Application Package Builder & ZIP Export

- **Description:** Produces a complete, ready-to-submit folder for one application and offers it as a ZIP download:

```
Acme_Backend_Developer_Coop_2027S/
├── Resume.pdf            ├── Cover_Letter.pdf
├── Resume.docx           ├── Cover_Letter.docx
├── Job_Description.pdf   ├── Application_Checklist.pdf
└── Application_Info.txt  (source URL, application URL, deadline, contact, required documents, notes)
```

- **User interaction:** *Application tracker → Generate package* → progress → *Download ZIP*. CLI `careerpilot package generate <appId> --out ./packages`.
- **Input:** Application id; option to reuse existing documents or regenerate.
- **Output:** `ApplicationPackage` (manifest + storage key) and a 5-minute signed download URL. The application moves to PREPARED if it was SAVED.
- **AI involvement:** Deterministic orchestration (uses F10/F11 outputs).
- **Expected workflow:** `DocumentService.requestPackage()` → enqueue → `PackageAssembler.buildPackage()` → `ResumeGenerator.generate()` + `CoverLetterGenerator.generate()` (if missing or stale) → `buildChecklist()` (from `JobAnalysis` + posting: required documents, deadline, portal steps) → `buildInfoText()` → `zip()` → `StorageService.put()` → `DocumentRepository.savePackage()` → `ApplicationService.transition(markPrepared)`.
- **Errors / alternatives:** A component document is NEEDS_REVIEW → the package is still built but its checklist's first item is "Review flagged resume bullets". Storage failure → task retried (idempotent key). Application already APPLIED → package rebuilt, no state change. **The package is never submitted by CareerPilot.**
- **Design links:** UC10 (+ include UC09) · `ApplicationTrackerPage`, `DocumentAPI`, `DocumentService`, `PackageAssembler`, `ApplicationPackage`, `StorageService`, `DocumentRepository`, `ApplicationService` · SD06 · Template Method (uses), Command (`BuildPackageCommand` when agent-initiated).

### F13 — Application Lifecycle Tracking & Undo

- **Description:** Tracks each application through a validated lifecycle, keeps a complete timeline of changes with their source (student, email event, agent), and lets the student undo the most recent change.
- **User interaction:** *Application tracker* Kanban (columns = states) with buttons offering only `allowedActions()`, a timeline drawer, deadlines and notes. CLI `careerpilot application update <id> submit`.
- **Input:** Application id + action (`markPrepared`, `submit`, `scheduleInterview`, `receiveOffer`, `reject`, `accept`, `decline`, `withdraw`).
- **Output:** New state, `StatusChange` record, `ApplicationStatusChanged` event.
- **AI involvement:** Deterministic.
- **Expected workflow:** `ApplicationService.transition()` → `Application.submit()` → current `ApplicationState.submit(app)` → `app.setState(AppliedState)` → save + `StatusChange` → `DomainEventBus.publish()`. Undo: `undoTransition()` → `StatusChange.revert()` → `Application.setState(previous)`.
- **Errors / alternatives:** Illegal action (e.g., `accept` from APPLIED) → `InvalidTransitionError` → 409 with allowed actions. Undo of a non-latest change → 409. Concurrent updates → optimistic locking on `Application.version`.
- **Design links:** UC11 · `ApplicationTrackerPage`, `ApplicationAPI`, `ApplicationService`, `Application`, `ApplicationState` (+9 states), `StatusChange`, `ApplicationRepository`, `DomainEventBus` · SD07, SM-1 · **State**, Observer.

### F14 — Email Monitoring & Application Event Detection

- **Description:** Reads *application-related* email (Gmail via OAuth, or uploaded `.eml` files) and detects events: application received, interview invitation, interview scheduled, information requested, rejection, offer and follow-up. It extracts details (e.g., date and time) and links each event to the right application with a confidence score.
- **User interaction:** *Settings → Connect Gmail* (consent screen, read-only) or *Upload .eml*. *Review queue* page for uncertain events (confirm / pick application / dismiss). CLI `careerpilot email sync`.
- **Input:** Gmail messages matching a narrow query (recent, job-related keywords or tracked company domains) or `.eml` files.
- **Output:** `EmailMessage` (4 KB excerpt), `DetectedEmailEvent` (type, confidence, extracted data, matched application, review status).
- **AI involvement:** Hybrid: `HybridClassifier` tries `RuleBasedClassifier` first (subject/sender patterns, ATS domains) and calls `LLMClassifier` only when rule confidence < 0.85.
- **Expected workflow:** Scheduler → `EmailSyncService.syncAccount()` → `EmailProviderRegistry.getAdapter()` → `GmailAdapter.fetchNewMessages()` (token decrypted by `CredentialVault`) → `EmailNormalizer.normalize()` → `EmailEventDetector.detect()` → `ApplicationMatcher.match()` → `DetectedEmailEvent.requiresReview()` → publish, or queue for review.
- **Errors / alternatives:** `invalid_grant` (revoked, or 7-day Testing-mode expiry) → account REAUTH_REQUIRED + notification. Ambiguous company (two open applications at the same company) → NEEDS_REVIEW. NOT_RELEVANT → excerpt deleted. Gmail quota errors → exponential backoff. The student disconnects → token revoked and deleted.
- **Design links:** UC12, UC13, UC14 · `SettingsPage`, `EmailAPI`, `EmailSyncService`, `EmailProviderAdapter` (+ Gmail/Eml/Mock), `CredentialVault`, `EmailNormalizer`, `EmailEventDetector`, `EmailClassificationStrategy` (+ Rule/LLM/Hybrid), `ApplicationMatcher`, `DetectedEmailEvent` · SD08, SD09 · **Adapter**, **Strategy**.

### F15 — Event-Driven Updates, Reminders & Notifications

- **Description:** Reacts to domain events. A confident interview invitation moves the application to INTERVIEW, creates an `Interview` record, schedules an interview-prep plan and notifies the student. Status changes are written to agent memory. Approaching deadlines trigger reminders. Each reaction is an independent observer.
- **User interaction:** Notification bell and dashboard feed; timeline entries labelled "from email — undo".
- **Input:** `DomainEvent`s (`ApplicationEventDetected`, `ApplicationStatusChanged`, `DeadlineApproachingEvent`, `JobsImportedEvent`).
- **Output:** State transitions, `Interview`s, `Notification`s, memory entries.
- **AI involvement:** Deterministic.
- **Expected workflow:** `DomainEventBus.publish(event)` → each subscribed `EventListener.handle()`: `ApplicationStatusUpdater` → `ApplicationService.transition(..., EMAIL_EVENT, evidenceId)`; `InterviewScheduler` → `ApplicationService.addInterview()` (+ enqueue prep plan); `NotificationService.notify()`; `MemoryRecorder` → `MemoryManager.remember()`. Daily `ReminderService.scanDeadlines()` publishes `DeadlineApproachingEvent`.
- **Errors / alternatives:** The email-driven transition is illegal for the current state (e.g., interview invite for a WITHDRAWN application) → no change and a review notification. A listener fails → the other listeners are unaffected (each handled in its own task with retry). Duplicate event (same message processed twice) → idempotent by `detectedEventId`.
- **Design links:** UC13, UC15 · `DomainEventBus`, `EventListener`, `ApplicationStatusUpdater`, `InterviewScheduler`, `NotificationService`, `MemoryRecorder`, `ReminderService`, `Notification` · SD09 · **Observer**, State, Command (undo).

### F16 — Goal-Driven Agent Workspace

- **Description:** The student states a goal in natural language ("Prepare my application for the Acme backend co-op"; "Get me ready for Thursday's interview"). The agent plans, shows the plan, executes tools step by step, pauses for approval before side effects, replans when needed, and keeps a trace with undo for reversible actions.
- **User interaction:** *Agent Workspace*: goal box, live plan with step statuses, approval prompts (step description from `AgentCommand.describe()`), questions from the agent, action log with Undo. CLI `careerpilot agent run "…"` (approval prompts in the terminal).
- **Input:** Goal text (+ optional application/job reference).
- **Output:** `AgentRun` (plan, observations, decisions, result summary), plus whatever the tools produced (documents, analyses, prep plans, state changes).
- **AI involvement:** AI (agent).
- **Expected workflow:** `AgentAPI.startRun()` → enqueue → `AgentController.runGoal()` → recall + context → `Planner.createPlan()` → loop (`createCommand`, `ApprovalPolicy.requiresApproval`, `AgentRun.awaitApproval` / `resumeRun`, `ToolManager.execute`, `Reasoner.evaluate`, `replan`) → `finish()` → `MemoryManager.remember()`.
- **Errors / alternatives:** Goal outside the tool catalog ("submit it for me") → the planner produces a plan without that step and the run explains that CareerPilot never submits applications. The student rejects a step → REPLAN or FINISH. Budget or step limit reached → partial result. LLM plan invalid → playbook.
- **Design links:** UC16 · `AgentWorkspacePage`, `AgentAPI`, `AgentController`, `Planner`, `PlaybookLibrary`, `Reasoner`, `ToolManager`, `ApprovalPolicy`, `AgentCommand` (+ subclasses), `MemoryManager`, `ContextBuilder`, `AgentRun`, `AgentActionRecord` · SD10, AD-1 · **Command**, Facade.

### F17 — Interview Preparation Plan

- **Description:** For an application in (or approaching) INTERVIEW, builds a targeted preparation plan: likely behavioural, technical and role questions derived from the job analysis, STAR stories built only from the student's verified experiences (each citing facts), a checklist, and focus areas from past mock-interview feedback.
- **User interaction:** *Interview prep* page for the application; generated automatically after an interview invitation (F15) or on demand. CLI `careerpilot interview prepare <appId>`.
- **Input:** Application (job, analysis), `CandidateContext`, FEEDBACK memories.
- **Output:** `InterviewPrepPlan` with `PrepQuestion`s (category, rationale, linked facts), STAR stories and a checklist.
- **AI involvement:** AI + `FabricationGuard` verification for STAR stories.
- **Expected workflow:** `InterviewAPI.preparePlan()` → `InterviewPrepService.preparePlan()` → `ContextBuilder.buildCandidateContext()` → `LLMClient.generateStructured("interview.plan")` → `FabricationGuard.check()` → save.
- **Errors / alternatives:** No job analysis yet → analysis is run first (F07). A STAR story is unsupported → removed, with a suggestion to add detail to the profile. LLM failure → a generic question bank for the role category (deterministic).
- **Design links:** UC17 · `InterviewPrepPage`, `InterviewAPI`, `InterviewPrepService`, `InterviewPrepPlan`, `PrepQuestion`, `ContextBuilder`, `FabricationGuard`, `LLMClient` · SD11 · Facade, Observer (auto-trigger).

### F18 — AI Mock Interview & Feedback

- **Description:** A text-based mock interview for a specific application. The agent asks up to 6 questions (with follow-ups), scores each answer against a rubric (structure/STAR, relevance, specificity, impact, clarity), gives feedback, and stores recurring weaknesses as FEEDBACK memory, which future prep plans target.
- **User interaction:** *Interview prep → Start mock interview* chat-style panel; summary report at the end.
- **Input:** Application id; the student's typed answers.
- **Output:** `MockInterviewSession` with `InterviewTurn`s (question, answer, score, feedback) and a summary.
- **AI involvement:** AI.
- **Expected workflow:** `MockInterviewService.startSession()` → loop `submitAnswer()` (LLM evaluation, `MockInterviewSession.addTurn()`, `isFinished()`) → `finishSession()` (LLM summary) → `MemoryManager.remember(FEEDBACK)`.
- **Errors / alternatives:** Empty or very short answer → feedback asks the student to elaborate (no score). The student abandons the session → saved as incomplete. LLM failure mid-session → session paused and resumable. Voice interviews are a future extension.
- **Design links:** UC18 (extends UC17) · `InterviewPrepPage`, `InterviewAPI`, `MockInterviewService`, `MockInterviewSession`, `InterviewTurn`, `LLMClient`, `MemoryManager` · SD11 · Facade.

### F19 — Outcome Analytics & Adaptive Strategy

- **Description:** Computes the student's own outcome statistics (response and interview rates by role category, source, match-score band and time to response), explains them, and proposes **bounded, evidence-cited** changes to the job-search strategy (e.g., "raise minimum match score from 60 to 65: 0 of 9 applications scoring below 60 received a response"). Accepted adjustments update `StrategyWeights` and agent memory, which changes future matching and triage. This closes the LEARN → ADAPT loop.
- **User interaction:** *Insights* page: charts, narrative, proposed adjustments with Accept / Reject, history of accepted adjustments (each revertible). CLI `careerpilot analytics`.
- **Input:** The student's applications, status changes and matches.
- **Output:** `InsightReport` (`OutcomeStats`, narrative, `StrategyAdjustment` proposals); on accept, updated `StrategyWeights` and a STRATEGY memory.
- **AI involvement:** Hybrid: deterministic statistics and guard rails; LLM narrative and proposal wording.
- **Expected workflow:** `AnalyticsService.getInsights()` → `OutcomeAnalyzer.computeStats()` → (if sufficient data) `StrategyAdvisor.proposeAdjustments()` (LLM + rules) → display → `acceptAdjustment()` → `StrategyAdjustment.accept()` → `StrategyWeights.applyAdjustment()` → save → `MemoryManager.remember(STRATEGY)`.
- **Errors / alternatives:** Fewer than 10 applications with an outcome → statistics only, with "not enough data to recommend changes" (no invented insights). A proposal lacks evidence (n < 5) or exceeds bounds → discarded before display. The student rejects all → nothing changes; the rejection is stored as PREFERENCE.
- **Design links:** UC19 · `InsightsPage`, `AnalyticsAPI`, `AnalyticsService`, `OutcomeAnalyzer`, `OutcomeStats`, `StrategyAdvisor`, `StrategyAdjustment`, `StrategyWeights`, `MemoryManager` · SD12 · Strategy (tunes it), Observer (`MemoryRecorder` feeds it).

