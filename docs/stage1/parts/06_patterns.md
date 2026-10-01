
---

# 6. Design Patterns

Seven course-covered patterns are applied, each to a problem CareerPilot actually has. For each one we give the problem, the participants and their roles, why the pattern fits, what would be harder without it, and how it supports growth. Two supporting techniques are named honestly and **not counted**: the registries `JobSourceRegistry`, `EmailProviderRegistry` and `LLMProviderFactory` are *simple factories*, not the GoF Factory Method; and the `*Repository` classes follow the Repository architectural pattern, which is not in the course list.

| # | Pattern | Where | Problem solved (one line) | Diagrams |
|---|---|---|---|---|
| P1 | Adapter | Job sources, email providers, LLM providers | Heterogeneous external formats and APIs behind one internal interface | CD-3, CD-4, SD02, SD03, SD09 |
| P2 | Strategy | Match scoring; email classification | Interchangeable algorithms chosen at run time by goal or confidence | CD-4, CD-5, SD04, SD09 |
| P3 | State | Application lifecycle | State-dependent legal actions without scattered conditionals | CD-5, SM-1, SD07 |
| P4 | Observer | Domain events | Many independent reactions to one event, without coupling the producer to them | CD-4, SD02, SD07, SD09 |
| P5 | Command | Agent tools | Agent actions as objects that can be planned, approved, logged and undone | CD-3, SD05, SD10 |
| P6 | Template Method | Document generation | Fixed generation pipeline with a mandatory verification step and varying hooks | CD-6, SD06 |
| P7 | Facade | `LLMClient` | One simple entry point to the provider/prompt/validation/budget subsystem | CD-3, SD04 and all LLM calls |

## P1 — Adapter

- **Design problem.** Job postings arrive as Experience York DOM extracts with York-specific labels ("Organization", "Application Deadline", "Work Term"), as pasted free text, or as JSON fixtures. Emails arrive from the Gmail REST API (base64 MIME parts, label ids) or as `.eml` files. LLM vendors expose different SDKs, request shapes, error types and JSON-mode switches. The core must not care which source it is dealing with.
- **Participants and roles.**
  - *Target:* `JobSourceAdapter` (`parse`, `validate`, `sourceType`); `EmailProviderAdapter` (`fetchNewMessages`, `providerName`); `LLMProvider` (`complete`, `name`).
  - *Adapters:* `YorkBrowserImportAdapter`, `LinkedInBrowserImportAdapter` (disabled), `ManualJobImportAdapter`, `MockJobSourceAdapter`; `GmailAdapter`, `EmlFileAdapter`, `MockEmailAdapter`; `GeminiProvider`, `OpenAIProvider`, `MockLLMProvider`.
  - *Adaptees:* the York page payload, pasted text, the Gmail API, `.eml` files, and the Gemini and OpenAI SDKs.
  - *Clients:* `JobImportService`, `EmailSyncService`, `LLMClient`.
- **Why appropriate.** The incompatibility is in *interfaces and formats*, not behaviour: every source ultimately yields `RawJobRecord` / `RawEmail` / `LLMResponse`.
- **Without it.** Services would contain `if source == "york" … elif "linkedin" …` branches in parsing, validation, error handling and tests. Every portal markup change or SDK upgrade would touch core services, and York and Gmail would become hard dependencies of the test suite.
- **Scalability / future.** A new source (an authorized partner API, Outlook through Microsoft Graph, another LLM vendor) is one new class plus one registry line, with no change to services, the agent or the UI. Mock adapters let CI run without network access or credentials.

## P2 — Strategy

- **Design problem (a) — match scoring.** What makes a posting a "good fit" differs by goal. For **co-op**, the work term and co-op eligibility are hard constraints and learning opportunity matters. For **entry-level**, the weight is on skill coverage and seniority fit. For **career switch**, direct skill overlap under-rates candidates, so transferable skills must be mapped (LLM-assisted). These are different algorithms, not just different weights.
- **Design problem (b) — email classification.** Cheap, transparent rules handle most ATS emails; an LLM handles the ambiguous rest. Tests and privacy-conscious users need rules-only mode.
- **Participants and roles.**
  - *Strategy interfaces:* `MatchScoringStrategy` (`score`, `key`); `EmailClassificationStrategy` (`classify`).
  - *Concrete strategies:* `CoopInternshipStrategy`, `EntryLevelStrategy`, `CareerSwitchStrategy`; `RuleBasedClassifier`, `LLMClassifier`, `HybridClassifier` (which composes the other two).
  - *Contexts:* `MatchingService` (selects via `selectStrategy(goal)`); `EmailEventDetector` (holds `strategy`, `setStrategy()`).
- **Why appropriate.** The algorithm varies independently of the client and must be chosen at run time (from `CareerGoal.goalType`, or from configuration and confidence).
- **Without it.** `MatchingService.matchJob()` would become a growing conditional mixing three scoring algorithms and their tests. Switching classifiers for a test or a privacy setting would need code edits.
- **Scalability / future.** New goal types (e.g., research-assistant positions, part-time) add a class. F19's adaptation adjusts `StrategyWeights` *inside* a strategy without touching the others. Classification can be A/B tested by swapping strategies.

## P3 — State

- **Design problem.** Whether `submit`, `scheduleInterview`, `receiveOffer`, `accept`, `withdraw` and the other actions are legal depends on the application's current lifecycle state. The actions arrive from three sources (student, email events, agent), and invalid transitions must be rejected consistently, with the GUI offering only legal actions.
- **Participants and roles.** *Context:* `Application` (holds `state`, delegates each action, `setState()`). *State:* abstract `ApplicationState` (every action throws `InvalidTransitionError` by default; `allowedActions()`). *Concrete states:* `SavedState`, `PreparedState`, `AppliedState`, `InterviewState`, `OfferState`, `AcceptedState`, `DeclinedState`, `RejectedState`, `WithdrawnState`, each overriding only its legal transitions (SM-1).
- **Why appropriate.** Behaviour changes with state; the transition rules are the product's core business rules and deserve one class per state.
- **Without it.** Every entry point (`ApplicationService`, `ApplicationStatusUpdater`, `UpdateApplicationStatusCommand`, the CLI) would repeat `if status in (...)` checks. A missed check lets an email move a WITHDRAWN application to INTERVIEW.
- **Scalability / future.** Adding a state (e.g., ONLINE_ASSESSMENT between APPLIED and INTERVIEW) is a new class plus a few overrides. `allowedActions()` drives the GUI buttons and CLI help automatically.

## P4 — Observer

- **Design problem.** One occurrence triggers several independent reactions. A confident interview-invitation email must change the application state, create an `Interview`, schedule preparation, notify the student and record memory. A completed import must start triage. A status change must update memory and notifications. The producers (`EmailSyncService`, `JobImportService`, `ApplicationService`, `ReminderService`) must not know every consumer.
- **Participants and roles.** *Subject:* `DomainEventBus` (`subscribe`, `unsubscribe`, `publish`). *Events:* abstract `DomainEvent` and `JobsImportedEvent`, `ApplicationEventDetected`, `ApplicationStatusChanged`, `DeadlineApproachingEvent`. *Observer interface:* `EventListener.handle()`. *Concrete observers:* `ApplicationStatusUpdater`, `InterviewScheduler`, `NotificationService`, `MemoryRecorder`, `TriageTrigger`.
- **Why appropriate.** It is one-to-many notification with a varying set of subscribers.
- **Without it.** `EmailSyncService` would call `ApplicationService`, `InterviewPrepService`, `NotificationService` and `MemoryManager` directly. Each new reaction (e.g., calendar sync) would modify the email pipeline, and one failing reaction would break the others.
- **Scalability / future.** In Stage 2 the bus dispatches in-process, and listeners that do heavy work enqueue Celery tasks, so each observer runs and retries independently. Because the bus is an interface, it can later be backed by a transactional outbox or message broker without changing producers or observers. Future observers (Google Calendar sync, weekly digest) plug in without touching existing code.

## P5 — Command

- **Design problem.** The agent must turn an LLM-produced plan into actions that can be (i) validated against a known catalog, (ii) paused for human approval, (iii) described to the student before execution, (iv) logged with arguments and results, and (v) undone when reversible, especially automatic status changes.
- **Participants and roles.** *Command:* abstract `AgentCommand` (`execute`, `undo`, `isReversible`, `describe`). *Concrete commands:* `SearchJobsCommand`, `RetrieveContextCommand`, `AnalyzeJobCommand`, `MatchJobCommand`, `RankJobsCommand`, `BuildPackageCommand`, `CreateInterviewPrepCommand`, `UpdateApplicationStatusCommand`. *Invoker:* `ToolManager` (`createCommand`, `execute`, `undo`; writes `AgentActionRecord`). *Client:* `AgentController` (builds commands from `PlanStep`s and asks `ApprovalPolicy`). *Receivers:* the services (`MatchingService`, `PackageAssembler`, `ApplicationService`, and others).
- **Why appropriate.** Requests must be parameterized, queued (awaiting approval), logged and undone, which is the Command pattern's stated intent.
- **Without it.** The agent would call services directly from a `switch(toolName)`. There would be no uniform place for approval, logging, budget accounting or undo, and the "the agent cannot submit applications" rule would be a convention rather than a structural fact.
- **Scalability / future.** New tools are new command classes registered with a `ToolSpec`. Because commands are serializable, runs can resume on any worker after approval.

## P6 — Template Method

- **Design problem.** Resume and cover-letter generation share an invariant pipeline (build grounded context → prompt → structured LLM call → **fabrication verification** → render → persist) but differ in prompt, output schema and rendering. The verification step must be impossible to skip.
- **Participants and roles.** *Abstract class:* `DocumentGenerator`, whose `generate()` is the template method, marked `{leaf}` (final). It defines the primitive hooks `promptKey()`, `buildPromptVariables()`, `outputSchema()` and `render()`, plus the concrete steps `verify()` and `persist()`. *Concrete classes:* `ResumeGenerator`, `CoverLetterGenerator`. *Collaborators:* `FabricationGuard`, `LLMClient`, `DocumentRenderer`, `ContextBuilder`.
- **Why appropriate.** The algorithm skeleton is fixed and its steps vary, which is the textbook case for Template Method.
- **Without it.** Each generator would re-implement the pipeline, and a new generator (e.g., a "follow-up email" draft) could forget the guard, the single most important safety property of the product.
- **Scalability / future.** New document types only implement four hooks and inherit grounding, verification, versioning and storage.

## P7 — Facade

- **Design problem.** Twelve classes call the LLM: `ResumeParser`, `ManualJobImportAdapter`, `JobAnalysisService`, `MatchingService`, `CareerSwitchStrategy`, `Planner`, `Reasoner`, `DocumentGenerator`, `LLMClassifier`, `InterviewPrepService`, `MockInterviewService` and `StrategyAdvisor`. Each call needs prompt lookup and rendering, per-user budget checks, provider invocation, retries, JSON-schema validation and usage accounting.
- **Participants and roles.** *Facade:* `LLMClient` (`generateStructured`, `generateText`). *Subsystem:* `PromptRegistry`/`PromptTemplate`, `UsageTracker`, `LLMProvider` (and its adapters), `ResponseValidator`, `LLMRequest`/`LLMResponse`, `LLMProviderFactory`.
- **Why appropriate.** Clients need a simple interface to a complex subsystem, and the subsystem's parts must stay independently testable.
- **Without it.** Budget checks, retries and validation would be duplicated twelve times (or forgotten), and changing the retry policy or adding caching would touch every caller.
- **Scalability / future.** Response caching, per-prompt model routing (cheap model for classification, stronger model for generation), rate limiting and tracing can be added inside the facade with no client changes.

### Pattern interplay (example: interview-invitation email)

`GmailAdapter` (**Adapter**) → `HybridClassifier` (**Strategy**) → `DomainEventBus.publish` (**Observer**) → `ApplicationStatusUpdater` → `ApplicationService.transition` → `InterviewState` (**State**). The prep plan calls the LLM through `LLMClient` (**Facade**). Had the agent made the change instead, `UpdateApplicationStatusCommand.undo()` (**Command**) would reverse it.

