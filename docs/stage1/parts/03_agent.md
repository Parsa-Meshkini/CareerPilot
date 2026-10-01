
---

# 3. AI / Agent Design

## 3.1 Agent Responsibilities

| Responsibility | Component | Deterministic or AI |
|---|---|---|
| Accept a goal and own the run lifecycle, budgets and limits | `AgentController`, `AgentRun` | Deterministic |
| Decompose a goal into tool calls | `Planner` (LLM), `PlaybookLibrary` (fallback) | AI with deterministic validation |
| Execute tools as logged, permission-checked actions | `ToolManager`, `AgentCommand` subclasses, `ApprovalPolicy`, `AgentActionRecord` | Deterministic |
| Interpret results and decide what to do next | `Reasoner` | Rules first; LLM reflection only for ambiguous observations |
| Retrieve relevant knowledge | `ContextBuilder`, `MemoryManager` | Deterministic retrieval |
| Remember across runs | `MemoryManager`, `AgentMemoryEntry`, `MemoryRecorder` (observer) | Deterministic |
| Generate / extract / classify language | `LLMClient` (Facade) → `LLMProvider` (Adapter) | AI |

Agent goals supported in Stage 2: `TRIAGE_BATCH` (automatic after an import, F09), `PREPARE_APPLICATION` and `PREPARE_INTERVIEW` (from the Agent Workspace or CLI, F16), and `FREE_FORM` (planner may use any registered tool, with the same limits and approvals).

## 3.2 Planning

1. `AgentController.runGoal()` creates an `AgentRun` (status PLANNING) with a token budget (default 40k tokens per run, configurable).
2. It gathers context: `MemoryManager.recall()` (up to 10 relevant PREFERENCE / STRATEGY / EPISODIC / FEEDBACK entries) and `ContextBuilder.buildCandidateContext()`.
3. `Planner.createPlan()` sends the goal, the context summary and the **tool catalog** (`ToolManager.listTools()`, each `ToolSpec` with name, description, JSON argument schema and a `sideEffecting` flag) to the LLM using the versioned prompt `agent.plan`, and asks for JSON conforming to `planSchema`.
4. `Planner.validatePlan()` rejects plans that use unknown tools, have arguments that fail the tool's schema, exceed 8 steps, or reference objects the student does not own. After two invalid attempts, the planner uses `PlaybookLibrary.getPlaybook(goalType)`, a hand-written plan per goal type. **The agent therefore always has a correct plan, even if the LLM misbehaves.**

Example plan for "Prepare my application for the Acme backend co-op":
`SearchJobs("Acme backend") → RetrieveContext → AnalyzeJob → MatchJob(deep) → BuildPackage* → UpdateApplicationStatus(markPrepared)*` (\* requires approval).

## 3.3 Tools

| Tool (`AgentCommand` subclass) | Receiver (does the work) | Side-effecting? | Reversible? |
|---|---|---|---|
| `SearchJobsCommand` | `JobRepository.listByUser()` | No | n/a |
| `RetrieveContextCommand` | `ContextBuilder.buildCandidateContext()` | No | n/a |
| `AnalyzeJobCommand` | `JobAnalysisService.analyzeJob()` | Writes analysis (idempotent) | n/a |
| `MatchJobCommand` | `MatchingService.matchJob()` | Writes match (idempotent) | n/a |
| `RankJobsCommand` | `MatchingService.rankJobs()` | Marks shortlist | Yes (unmark) |
| `BuildPackageCommand` | `PackageAssembler.buildPackage()` | Creates documents (LLM cost) | Documents are versioned; an older version can be restored |
| `CreateInterviewPrepCommand` | `InterviewPrepService.preparePlan()` | Creates plan | Yes (delete plan) |
| `UpdateApplicationStatusCommand` | `ApplicationService.transition()` | Changes state | **Yes** (`undo()` → `ApplicationService.undoTransition()`) |

There is deliberately **no** "submit application", "send email" or "log in to portal" tool. The agent cannot take those actions because they do not exist in its tool catalog.

## 3.4 Memory

| Kind | Written by | Example | Read by |
|---|---|---|---|
| EPISODIC | `AgentController.finish()`, `MemoryRecorder` (on `ApplicationStatusChanged`) | "Applied to Backend Intern @ Acme (source YORK, score 78)"; "Triaged 47 jobs, 12 shortlisted" | Planner (avoid repeating work), OutcomeAnalyzer context |
| PREFERENCE | Student feedback (dismissing a job with a reason, rejecting an agent step) | "Not interested in QA roles" | ContextBuilder → matching and triage |
| STRATEGY | `AnalyticsService.acceptAdjustment()` | "Raise minMatchScore to 65: 0/9 interviews below 60" | Planner, MatchingService (via `StrategyWeights`) |
| FEEDBACK | `MockInterviewService.finishSession()` | "Weak at quantifying impact in STAR answers" | InterviewPrepService (targets weaknesses) |

Memory is stored in PostgreSQL (`AgentMemoryEntry`). `recall()` uses kind filters, keyword/full-text match and recency × importance ranking. Semantic (vector) retrieval with pgvector is a future extension. The design isolates retrieval behind `MemoryManager.recall()`, so adding it later changes no callers.

## 3.5 Reasoning

`Reasoner.evaluate(run, step, observation)` returns a `Decision`:

1. **Rules first (deterministic, cheap):** tool error ⇒ retry once, then REPLAN; budget or step limit reached ⇒ ABORT with a partial result; an observation containing multiple candidate objects ⇒ ASK_USER; plan exhausted ⇒ FINISH.
2. **LLM reflection (only if rules are inconclusive and confidence < 0.7):** a short `agent.reflect` prompt asks whether the observation satisfies the step's intent, returning `{action, rationale}`.

Examples: most imported postings are SUMMARY-only ⇒ REPLAN to restrict deep analysis to FULL postings. A search returns two "Acme" postings ⇒ ASK_USER.

## 3.6 Decision Making

Concrete decisions made by the agent (and their deterministic guard rails):

- **Where to spend LLM budget (triage, F09):** deep-analyze only postings whose quick score ≥ `CareerGoal.minMatchScore`, taking the top-K where K = min(10, remaining budget / estimated cost per job). Postings with deadlines within 21 days get a priority boost.
- **Which scoring algorithm applies (F08):** `MatchingService.selectStrategy(goal)` chooses the `MatchScoringStrategy` from the student's goal type.
- **Whether an email may change state automatically (F14/F15):** only if classification confidence ≥ 0.85 **and** application-match confidence ≥ 0.85; otherwise the event is queued for review.
- **Whether a strategy change is proposed (F19):** only with ≥ 10 outcomes overall and ≥ 5 observations behind the specific metric, with each weight change bounded to ±0.10.

## 3.7 Replanning

At most two replans per run. `Planner.replan(run, observation)` receives the completed steps, the failing or surprising observation and the remaining budget, and returns replacement steps. These replace only the *remaining* part of the plan (`Plan.replaceRemaining()`), so completed, logged actions are never re-executed. If the third plan attempt would be needed, the run finishes with a partial result and explains why.

## 3.8 LLM Integration

```
callers ─► LLMClient (Facade) ─► PromptRegistry / PromptTemplate (versioned prompts)
                               ─► UsageTracker.checkBudget / record
                               ─► LLMProvider.complete  (Adapter: Gemini | OpenAI | Mock)
                               ─► ResponseValidator.validate (JSON schema) ─► retry ≤ 2
```

- **Provider abstraction** (Adapter): the vendor SDKs have different request/response shapes, error types and JSON-mode flags. Each `*Provider` adapts one SDK to `complete(LLMRequest): LLMResponse`. `LLMProviderFactory.create(config)` picks one from environment variables. Benefits: no vendor lock-in; a cheaper model can be chosen per prompt; tests run offline with `MockLLMProvider` fixtures; and an outage can be handled by switching providers without code changes.
- **Structured output everywhere:** every extraction, classification and generation call has a JSON schema, and invalid output is retried, then fails safely (keyword fallback for analysis, NEEDS_REVIEW for generation, rule-only classification for email).
- **Prompt versioning:** `PromptTemplate(key, version)` is stored in the repository (`backend/agent/prompts/`), so prompt changes are reviewed like code and recorded in `JobAnalysis.modelUsed` / document metadata.
- **Grounding rules in prompts:** "use only the provided facts; cite `factId` for each bullet; if information is missing, omit it." The prompts are backed by the deterministic `FabricationGuard`, because prompts alone are not a guarantee.
- **Data minimization:** prompts contain only the facts relevant to the posting, never OAuth tokens and never full email threads (4 KB excerpt maximum).

