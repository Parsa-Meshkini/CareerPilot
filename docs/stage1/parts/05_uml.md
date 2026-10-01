
---

# 5. UML Design

**Notation** follows the EECS 3311 UML lecture notes: `+` public, `-` private, `#` protected; *italic* names are abstract classes and abstract methods; `«interface»` marks interfaces; hollow-triangle solid lines mark inheritance; hollow-triangle dashed lines mark realization; dashed open arrows mark dependency (`«use»`, `«create»`); hollow diamonds mark aggregation; filled diamonds mark composition. Multiplicities appear on association ends. Trivial getters and setters are omitted, and inherited methods are not repeated unless overridden.

**Naming convention.** UML operations use lowerCamelCase to match the course examples (e.g., `createBatch()`). The Python/Django implementation uses the PEP 8 snake_case equivalent (`create_batch()`), a one-to-one mapping that `tools/check_uml_consistency.py` documents. UML type names such as `UUID`, `Map` and `List` are language-neutral.

**Sources and rendering.** Every diagram is drawn in UMLet and saved as a `.uxf` file in `docs/uml/src/`; `tools/render_uml.py` exports each one to PNG and SVG in `docs/uml/rendered/` using UMLet itself. The SVGs are zoomable and recommended for reading the larger diagrams.

## 5.1 Class Diagrams

A single diagram containing all ~170 classes would be unreadable, so the class model is split into one **main class diagram** and six **supporting diagrams**. Together these form one consistent model; every class and method appears with the same name and signature wherever it is shown.

| Diagram | Content | Patterns visible |
|---|---|---|
| **CD-1 Main class diagram** | Key classes of every layer (boundary, control/services, agent, AI, integration, domain) and how they connect | All seven |
| CD-2 Domain model | Persistent entities, attributes, compositions and multiplicities; every entity is owned by one `User` | — |
| CD-3 Agent core & LLM integration | `AgentController`, `Planner`, `Reasoner`, `ToolManager`, `AgentCommand` hierarchy, memory, context, `LLMClient` facade, provider adapters | Command, Facade, Adapter |
| CD-4 Integrations & events | Job-source adapters, email adapters, classification strategies, domain events, event bus and observers | Adapter, Strategy, Observer |
| CD-5 Services, strategies, state & infrastructure | Application services, repositories, `MatchScoringStrategy` family, `ApplicationState` hierarchy, task queue, storage | Strategy, State |
| CD-6 Documents, interviews & analytics | `DocumentGenerator` template, guard, renderer, package assembler, interview and analytics services | Template Method |
| CD-7 Boundary layer | React pages, extension components, CLI and DRF API controllers | — |

### CD-1 Main Class Diagram

![CD-1 Main class diagram](../uml/rendered/cd01_main_class_diagram.svg)

*Reading guide.* Boundary objects (left) call REST controllers, which delegate to services. Services use the agent (bottom), the AI subsystem (`LLMClient` Facade in front of `LLMProvider` Adapters; the `DocumentGenerator` Template Method; the `MatchScoringStrategy` family) and the integration layer (`JobSourceAdapter` / `EmailProviderAdapter` Adapters; the `DomainEventBus` Subject with its `EventListener` Observers). Domain entities (right) include `Application`, the context of the State pattern.

### CD-2 Domain Model

![CD-2 Domain model](../uml/rendered/cd02_domain_model.svg)

Key modelling decisions: `ProfileFact` is abstract with five concrete fact types and a `verified` flag, which the anti-fabrication rule relies on. `CandidateProfile` *composes* its facts and its single `CareerGoal`, and the goal composes its `StrategyWeights`. `JobPosting` composes at most one `JobAnalysis` and one `JobMatch` because both are recomputable caches. `ImportBatch` *aggregates* postings, since postings outlive the batch. `Application` composes its history (`StatusChange`), `Interview`s, documents and package. `DetectedEmailEvent` is associated with 0..1 `Application` because it may be unmatched (the review queue).

### CD-3 Agent Core and LLM Integration

![CD-3 Agent core and LLM](../uml/rendered/cd03_agent_ai.svg)

### CD-4 External Integrations and Domain Events

![CD-4 Integrations and events](../uml/rendered/cd04_integrations_events.svg)

### CD-5 Services, Matching Strategies, Application State and Infrastructure

![CD-5 Services and state](../uml/rendered/cd05_services_state.svg)

### CD-6 Documents, Interviews and Analytics

![CD-6 Documents interviews analytics](../uml/rendered/cd06_documents_interviews_analytics.svg)

### CD-7 Boundary Layer (GUI, Extension, CLI, API)

![CD-7 Boundary layer](../uml/rendered/cd07_boundary_api.svg)

### 5.1.1 Layer and Package Responsibilities

| Layer / Django app | Responsibility | Main classes |
|---|---|---|
| Boundary: `frontend/`, `extension/`, `cli/` | Present data, collect input, call the REST API. No business rules. | `*Page`, `ExtensionPopup`, `ContentScript`, `SiteExtractor`, `CareerPilotCLI`, `CliApiClient` |
| Control: `*/api.py` (DRF) | Authenticate, validate request shape, map to service calls, map errors to HTTP codes | `ProfileAPI`, `JobImportAPI`, `JobAPI`, `ApplicationAPI`, `DocumentAPI`, `EmailAPI`, `AgentAPI`, `InterviewAPI`, `AnalyticsAPI` |
| Application services | Use-case logic and transactions; publish events | `CandidateService`, `JobImportService`, `JobAnalysisService`, `MatchingService`, `ApplicationService`, `DocumentService`, `PackageAssembler`, `EmailSyncService`, `InterviewPrepService`, `MockInterviewService`, `AnalyticsService` |
| Agent (`agent/`) | Plan, act, observe, reason, remember | `AgentController`, `Planner`, `Reasoner`, `ToolManager`, `AgentCommand`, `MemoryManager`, `ContextBuilder` |
| AI (`agent/llm/`) | Provider-neutral LLM access | `LLMClient`, `LLMProvider` + adapters, `PromptRegistry`, `ResponseValidator`, `UsageTracker` |
| Integration (`integrations/`) | Translate external formats and protocols | `JobSourceAdapter` family, `EmailProviderAdapter` family, `CredentialVault`, normalizers |
| Domain + persistence | Entities and invariants; user-scoped repositories | CD-2 entities, `*Repository` |
| Infrastructure | Queues, workers, storage, events | `TaskQueue`, `BackgroundWorker`, `StorageService`, `DomainEventBus` |

## 5.2 Use-Case Diagrams

Nineteen use cases cover all 19 features. To stay readable (per the lecture guidance on granularity), they are drawn in two diagrams of one model. The actors are external to CareerPilot: **Student** (primary); **Job Portal**, a generalization specialized by **Experience York** and **LinkedIn**; **Email Provider (Gmail)**; **LLM Provider**; and **Scheduler** (a timer that initiates periodic use cases). The GUI, CLI and browser extension are *part of* the system and therefore not actors.

Relationships used: `«include»` where the base use case cannot complete without the included one (import always normalizes/deduplicates; triage always analyzes and matches; a package always contains generated documents; processing emails always sends the resulting notifications). `«extend»` where optional behaviour is inserted at an extension point (reviewing an uncertain event extends *Track Application Status*; a mock interview extends *Prepare for Interview*). Actor generalization is used for job portals.

![UCD-1 Use cases part 1](../uml/rendered/ucd1_profile_jobs_preparation.svg)

![UCD-2 Use cases part 2](../uml/rendered/ucd2_tracking_agent_insights.svg)

## 5.3 Sequence Diagrams

Twelve sequence diagrams cover every feature (several closely related features share one diagram, as the instructions allow). Each shows the initiating actor, boundary objects, controllers, services, domain objects, agent components, repositories and external services, with numbered messages, returns and `alt`/`opt`/`loop` fragments for alternative and error flows. **Every message sent to a class lifeline is a method declared in the class diagrams.** The automated checker verified all 287 such messages (Section 8.2).

| SD | Title | Features | Use cases |
|---|---|---|---|
| SD01 | Import resume, verify facts, configure goal | F01, F02, F03 | UC01–UC03 |
| SD02 | Browser-extension batch import | F04, F06 | UC04, UC06 |
| SD03 | Manual import (e.g., pasted LinkedIn posting) | F05, F06 | UC05, UC06 |
| SD04 | Analyze and match job (incl. skill gap) | F07, F08 | UC07 |
| SD05 | Agent batch triage | F09 | UC08 |
| SD06 | Generate resume, cover letter and package | F10, F11, F12 | UC09, UC10 |
| SD07 | Update application status and undo | F13 | UC11 |
| SD08 | Connect email account (OAuth) / upload .eml | F14 | UC12 |
| SD09 | Process emails, auto-update, notify, reminders | F14, F15 | UC13–UC15 |
| SD10 | Goal-driven agent run with approval and replanning | F16 | UC16 |
| SD11 | Interview preparation and mock interview | F17, F18 | UC17, UC18 |
| SD12 | Outcome analysis and adaptive strategy | F19 | UC19 |

### SD01 — Import Resume, Verify Facts, Configure Goal
![SD01](../uml/rendered/sd01_resume_profile.svg)

### SD02 — Import Jobs through the Browser Extension
![SD02](../uml/rendered/sd02_extension_batch_import.svg)

### SD03 — Manual Job Import
![SD03](../uml/rendered/sd03_manual_import.svg)

### SD04 — Analyze Job and Match Candidate
![SD04](../uml/rendered/sd04_analyze_match.svg)

### SD05 — Agent Batch Triage
![SD05](../uml/rendered/sd05_agent_batch_triage.svg)

### SD06 — Generate Tailored Documents and Application Package
![SD06](../uml/rendered/sd06_application_package.svg)

### SD07 — Update Application Status and Undo
![SD07](../uml/rendered/sd07_application_status_undo.svg)

### SD08 — Connect Email Account
![SD08](../uml/rendered/sd08_connect_email.svg)

### SD09 — Process Application Emails
![SD09](../uml/rendered/sd09_process_emails.svg)

### SD10 — Goal-Driven Agent Run
![SD10](../uml/rendered/sd10_agent_goal_run.svg)

### SD11 — Interview Preparation and Mock Interview
![SD11](../uml/rendered/sd11_interview_prep_mock.svg)

### SD12 — Outcome Analysis and Adaptive Strategy
![SD12](../uml/rendered/sd12_outcome_adaptation.svg)

## 5.4 Supplementary Diagrams (justified additions; they do not replace the required ones)

- **SM-1 Application state machine.** This is the specification that the State pattern classes in CD-5 implement. It is the clearest way to show which transitions are legal, and the implementation's unit tests are written against it.
- **AD-1 Agent control loop.** This activity diagram (covered in UML II) shows the loop's decisions, limits and approval gate compactly. It complements SD05 and SD10.
- **DD-1 Deployment.** This shows the modular-monolith runtime (web, workers, beat, PostgreSQL, Redis, object storage) that Sections 12 and 16 refer to.

![SM-1](../uml/rendered/sm01_application_state.svg)

![AD-1](../uml/rendered/ad01_agent_loop.svg)

![DD-1](../uml/rendered/dd01_deployment.svg)

