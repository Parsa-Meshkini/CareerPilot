
---

# 1. Project Overview

## 1.1 Problem

A university student looking for a co-op term, an internship or a first job typically manages dozens of postings from several places: the university's co-op portal (Experience York), LinkedIn, company career sites and postings forwarded by friends. For each one, they must decide whether it is worth applying, tailor a resume and cover letter, remember deadlines, and later work out which emails ("We'd like to invite you to an interview…", "Unfortunately…") belong to which application. Existing tools cover isolated slices: job boards *list* jobs, spreadsheets *track* them, and generic chatbots *write* text. No tool closes the loop from "I found 40 postings" to "here is what worked and what to change next time", and generic chatbots routinely invent experience a student does not have.

## 1.2 Motivation

- **Volume and fragmentation.** Co-op searches involve high application volume on short deadlines, spread across portals that do not talk to each other.
- **Quality under time pressure.** Tailoring each application is what improves outcomes, and it is the step students skip when rushed.
- **Lost signal.** Outcomes (interview invitations, rejections, silence) arrive by email and are rarely connected back to *which* strategy produced them, so students cannot learn from their own history.
- **Trust.** AI-written application material is only useful if it is *truthful*. A fabricated skill or metric can cost the student an offer.

## 1.3 Target Users

| User group | Needs | Primary use |
|---|---|---|
| York co-op / internship students (primary) | Batch-process Experience York postings; meet deadlines; tailor applications quickly | Extension import, triage, packages, tracking |
| Other university students and new graduates | Manage LinkedIn / company-site postings; prepare for interviews | Manual import, matching, interview preparation |
| Early-career career switchers | Map transferable skills to a new field | `CareerSwitchStrategy` matching, gap analysis |

Out of scope as users: recruiters and employers (CareerPilot is candidate-side), and administrators (operations use the Django admin, which is not a product feature).

## 1.4 Project Goals

1. **G1 — Intake at scale:** import many postings at once from supported sources into one normalized, deduplicated job model.
2. **G2 — Decide well:** analyze each posting, score it against the student's *verified* profile using a goal-appropriate strategy, and explain gaps.
3. **G3 — Prepare truthfully:** generate tailored resumes, cover letters and a complete application package whose claims are traceable to verified facts.
4. **G4 — Track automatically, with the human in control:** keep each application's lifecycle current from student actions and detected emails. The student always performs the final submission, and every automatic change can be undone.
5. **G5 — Learn and adapt:** turn application outcomes into evidence-backed, student-approved strategy changes that alter future ranking and planning.
6. **G6 — Be a real, deployable product design:** multi-user, secure, modular, testable, provider-neutral, and implementable by a student team across Stages 2–3.

## 1.5 Why an AI Agent Is Appropriate

CareerPilot is not "prompt in, text out". Several of its tasks are **multi-step, tool-using and stateful**, which is what an agent architecture is for:

| Agent capability (Stage 1 requirement) | Where it appears in CareerPilot |
|---|---|
| Planning | `Planner.createPlan()` turns a goal ("triage this batch", "prepare my Acme application") into a validated sequence of tool calls (SD05, SD10) |
| Tool use | `ToolManager` executes `AgentCommand`s: search jobs, retrieve context, analyze, match, rank, build a package, update status, prepare an interview |
| Retrieval | `ContextBuilder` retrieves the verified profile facts most relevant to a posting; `MemoryManager.recall()` retrieves preferences and past episodes |
| Memory | `AgentMemoryEntry` (EPISODIC, PREFERENCE, STRATEGY, FEEDBACK) persists across runs and changes later behaviour |
| Reasoning and decision-making | `Reasoner.evaluate()` decides CONTINUE / REPLAN / ASK_USER / ABORT / FINISH after each observation; triage chooses which jobs deserve expensive deep analysis within a budget |
| Multi-step execution and replanning | Bounded loop with replanning (e.g., most postings are summary-only, so deep analysis is restricted to full postings) |
| Human-in-the-loop | `ApprovalPolicy` pauses side-effecting steps for student approval |

**AI/LLM models.** CareerPilot targets a hosted general-purpose LLM with JSON-structured output. The primary choice is **Google Gemini** (a Flash-class model for high-volume extraction and classification; a Pro-class model for generation and planning). **OpenAI** GPT models are the alternative, and `MockLLMProvider` is used for tests. All access goes through the `LLMProvider` interface, so the provider is a deployment setting (`LLM_PROVIDER`, `LLM_MODEL_*`), not an architectural commitment (Section 3.8).

**How the AI interacts with the rest of the system.** The LLM never touches the database, the network or the student's accounts directly. It is called only through `LLMClient` (Facade), with versioned prompt templates and a JSON schema for every structured call, and its output is validated before use. Actions happen only through typed, logged, permission-checked `AgentCommand`s. Deterministic code owns state (State pattern), and the LLM proposes.

## 1.6 Differentiation from the Instructor's Sample Project

The Stage 1 instructions list "AI Job Search and Career Agent" (sample #7). CareerPilot shares its domain but not its design centre:

| Aspect | Sample #7 (as listed) | CareerPilot |
|---|---|---|
| Job intake | "job import" | Browser-extension **batch** import from the open portal page; source **adapters**; normalization to a canonical `JobPosting`; exact + fingerprint **deduplication**; summary→full completeness model |
| Decision support | job matching, comparison | Goal-specific **Strategy** scoring (co-op vs. entry-level vs. career-switch) + **agent triage** that chooses where to spend LLM budget and produces a prioritized shortlist |
| Documents | resume *suggestions*, cover-letter drafting | Complete **application package** (resume + cover letter in PDF/DOCX, job description, checklist, info file, ZIP) with a mandatory **fabrication guard** that cites verified facts |
| Tracking | manual tracker, deadlines | **State-pattern** lifecycle driven by student actions *and* **detected emails** (Gmail OAuth or .eml), confidence thresholds, review queue, full undo |
| Learning | career-development plan | **Outcome analytics → bounded strategy adjustments → student approval → changed future behaviour** |
| Agent | "AI assistant" | Explicit planner / reasoner / tools / memory with budgets, approval gates, replanning and fallback playbooks |
| Human control | not specified | Deliberate product rule: **CareerPilot never submits an application** |

---

# 2. System Overview

## 2.1 CareerPilot Overview

CareerPilot has four client surfaces and one backend:

- **Web GUI** (React + Vite SPA): dashboard, profile, jobs, job detail, application tracker, document center, interview preparation, agent workspace, insights, event review and settings.
- **Browser extension** (Chrome Manifest V3): detects postings on supported pages, extracts them on click, and sends a batch to the API. It contains **no AI logic** and **no credentials** other than a CareerPilot API token.
- **CLI** (`careerpilot`, Python/Typer): a scripted interface to the same REST API (Section 11).
- **Backend** (Django + Django REST Framework, modular monolith) with **Celery workers** for all slow work (imports, LLM calls, document rendering, email sync) and **Celery beat** for schedules.

The agent loop that runs through the whole product is:

```
DISCOVER / IMPORT ─► NORMALIZE & DEDUPE ─► ANALYZE ─► MATCH ─► TRIAGE / PLAN
        ▲                                                        │
        │                                                        ▼
  ADAPT STRATEGY ◄─ LEARN (outcome stats) ◄─ OBSERVE (emails) ◄─ PREPARE (package) ─► student APPLIES (manual)
```

## 2.2 Agent Architecture (summary)

```
AgentGoal ─► AgentController ──► MemoryManager.recall  +  ContextBuilder (retrieval)
                  │
                  ├─► Planner.createPlan  (LLM via LLMClient; validated; PlaybookLibrary fallback)
                  │
                  └─► loop (≤ 8 steps, ≤ 2 replans, token budget):
                         ToolManager.createCommand(step) ─► ApprovalPolicy? ─► ToolManager.execute(cmd)
                         ─► Observation ─► Reasoner.evaluate ─► CONTINUE | REPLAN | ASK_USER | ABORT | FINISH
                  └─► MemoryManager.remember (episodic summary) ─► AgentRun.complete
```

Details are in Section 3, and the classes are in CD-3.

## 2.3 External Integrations

| Integration | Mechanism | Abstraction | Hard dependency? |
|---|---|---|---|
| Experience York | Extension reads the student's open page on click → `ImportPayload` | `JobSourceAdapter` → `YorkBrowserImportAdapter` (+ `YorkExtractor` in the extension) | **No** (fixture + `MockJobSourceAdapter`) |
| LinkedIn | Student pastes posting text (core); extension extraction disabled | `ManualJobImportAdapter`; `LinkedInBrowserImportAdapter` (disabled) | **No** |
| Gmail | OAuth 2.0, `gmail.readonly`, filtered query | `EmailProviderAdapter` → `GmailAdapter` | **No** (`EmlFileAdapter`, `MockEmailAdapter`) |
| LLM | HTTPS via vendor SDK | `LLMProvider` → `GeminiProvider` / `OpenAIProvider` / `MockLLMProvider`, behind `LLMClient` | **No** (swap via config; mock for tests) |
| Object storage | S3 API | `StorageService` | No (local filesystem / MinIO in dev) |

## 2.4 Scalability Architecture (summary)

- **Stateless web tier:** any number of Django containers behind a reverse proxy; sessions/tokens validated per request.
- **Asynchronous by default for slow work:** HTTP requests that trigger imports, LLM calls, document generation or email sync return `202 Accepted` with a task/batch id. Workers process tasks from **separate Celery queues** (`import`, `ai`, `docs`, `email`), which scale independently.
- **Idempotent tasks:** batch processing and email sync are safe to retry (dedup by `(user, source, externalId)` and by provider message id).
- **Cost and rate control:** `UsageTracker` enforces per-user token budgets. Redis-backed rate limits protect provider quotas, and triage limits deep analysis to the top-K postings.
- **Data growth:** all tables are indexed by `user_id`. Email bodies are truncated to 4 KB excerpts and deleted for irrelevant messages, and documents live in object storage, not the database.

Full plan: Section 12.

## 2.5 Security and Privacy (summary)

OAuth only (no third-party passwords stored); refresh tokens encrypted at rest (`CredentialVault`); secrets only in environment variables (`.env.example` committed, `.env` git-ignored); every query scoped by the authenticated user (`userId` is a parameter of every repository method); signed, short-lived download URLs; least-privilege scopes (`gmail.readonly` only); resume uploads type- and size-checked; audit trail through `StatusChange` and `AgentActionRecord`; account data export and deletion (`SettingsPage.deleteMyData()`). Full treatment: Section 13.

