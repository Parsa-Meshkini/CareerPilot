
---

# 14. Risks and Limitations

## 14.1 Risk Register

Likelihood and impact: H (high), M (medium), L (low).

| ID | Risk | Impact | Likelihood | Mitigation |
|---|---|---|---|---|
| R1 | Experience York page structure changes, or access is restricted | M | M | Versioned extractors; field map in backend configuration; paste-import fallback; HTML fixtures and `MockJobSourceAdapter` keep development and demos independent of the live portal |
| R2 | LinkedIn ToS prohibits extension-based extraction | H | H (certain if attempted) | Not implemented: LinkedIn extraction is disabled and LinkedIn postings enter by student paste only; an official API adapter is future work |
| R3 | Browser-extension compatibility (MV3 changes, Chrome only) | M | L | Minimal permissions (`activeTab`); no remote code; Chrome only in scope; Firefox/Edge as future work |
| R4 | Gmail OAuth limits: restricted scope, Testing-mode 7-day tokens, 100-user cap, verification cost | M | H | Reauth flow; `.eml` upload and mock adapter as Tier-1 paths; production verification deferred beyond the course |
| R5 | LLM hallucination in analysis or explanations | M | M | Structured outputs with schema validation; deterministic scoring; explanations labelled as AI-generated; fallbacks |
| R6 | Resume / cover-letter **fabrication** | H | M | Verified-facts-only context; mandatory fact citations; deterministic `FabricationGuard` fixed in the Template Method; NEEDS_REVIEW status; side-by-side review UI |
| R7 | Duplicate or near-duplicate jobs across sources | L | H | Exact id + fingerprint deduplication; merge policy; manual split |
| R8 | Incorrect email classification or matching changes an application's state | M | M | Dual 0.85 thresholds; review queue; State-pattern legality checks; full undo; source and evidence shown in the timeline |
| R9 | LLM API rate limits and outages | M | M | Queues with retry/backoff; per-provider rate limiter; provider swap via configuration; graceful degradation (keyword analysis, rule-only classification) |
| R10 | LLM API cost overrun | M | M | Per-user and per-run token budgets; triage top-K; caching; cheaper models for extraction and classification; mock provider in CI |
| R11 | Large batch processing overloads workers | M | L | Chunking; per-queue concurrency limits; progress UI; idempotent tasks |
| R12 | Privacy / security breach (resumes, email) | H | L | Section 13 controls; data minimization; encryption; isolation tests; retention limits |
| R13 | Document generation failures (fonts, PDF rendering) | L | M | Two renderers (DOCX via python-docx, PDF via WeasyPrint); PDF retry; DOCX delivered even if the PDF fails |
| R14 | Scope overrun within the course timeline | H | M | Tiered features (Tier 1 alone satisfies the requirements); mock adapters remove external blockers; roadmap checkpoints (Section 15) |
| R15 | Agent loops or runaway behaviour | M | L | Step, replan and budget limits; playbook fallback; approval gates; no destructive tools in the catalog |
| R16 | Prompt injection via posting or email content ("ignore previous instructions…") | M | M | Content passed as delimited data; schema-constrained outputs; the agent cannot execute arbitrary tools; guard checks |
| R17 | Match scores over-trusted by students | M | M | Score breakdown and explanation always shown; UI copy frames scores as guidance, not prediction |

## 14.2 Known Limitations (accepted for the course)

- Chrome only; English postings only; text (not voice) mock interviews.
- Scanned (image-only) resumes are not parsed (no OCR).
- Adaptive strategy needs ≥ 10 outcomes, so new users see statistics without recommendations for a while (a deliberate honesty choice).
- Gmail integration runs in Google "Testing" mode for the course (limited test users, weekly reconnection).
- Match scores are heuristic and not validated against hiring outcomes at scale.

---

# 15. Stage 2 Implementation Roadmap

## 15.1 Principles

Build vertical slices end-to-end (GUI + CLI + API + service + tests) in priority order. Use mocks for every external system first, then replace them with real adapters. Keep `tools/check_uml_consistency.py` green and update the UML whenever a class or method changes.

## 15.2 Order of Work

| Step | Work item | Features | Tier | Depends on |
|---|---|---|---|---|
| 1 | Project skeleton: Docker Compose, Django apps, React shell, CI, auth + user model, tenant-scoped repository base | — | Essential | — |
| 2 | Candidate profile + verified facts | F01 | 1 | 1 |
| 3 | LLM subsystem: `LLMClient` facade, `MockLLMProvider`, `GeminiProvider`, prompt registry, validator, usage tracker | (enables AI) | Essential | 1 |
| 4 | Resume import + fact extraction; career goals + strategies | F02, F03 | 1 | 2, 3 |
| 5 | Job model, adapters (Manual, Mock), normalizer, dedup, import service + Celery | F05, F06 | 1 | 1 |
| 6 | Browser extension + `YorkBrowserImportAdapter` (fixture-tested) | F04 | 1 | 5 |
| 7 | Job analysis, matching strategies, gap explanation | F07, F08 | 1 | 3, 4, 5 |
| 8 | Application model + State pattern + timeline/undo | F13 | 1 | 5 |
| 9 | Document generation template + FabricationGuard + resume; package builder | F10, F12 | 1 | 7, 8 |
| 10 | Agent core: controller, planner + playbooks, reasoner, tool manager, commands, memory; Agent Workspace | F16 | 1 | 7, 8, 9 |
| 11 | Email pipeline with `EmlFileAdapter` / mock, classifiers, matcher | F14 | 1 | 8 |
| 12 | CLI covering the commands in Section 11 | (all Tier 1) | Essential | 2–11 |
| 13 | Event bus + observers, reminders, notifications; agent triage | F15, F09 | 2 | 10, 11 |
| 14 | Cover letter; Gmail OAuth adapter | F11, F14 (Gmail) | 2 | 9, 11 |
| 15 | Interview preparation plan; outcome analytics + adaptive strategy | F17, F19 | 2 | 10, 13 |
| 16 | Mock interview | F18 | 3 | 15 |

**Checkpoint rule:** after step 12, the system already meets every stated minimum (GUI, CLI, ≥ 10 features, 7 patterns, an LLM, agent behaviour). Steps 13–16 deepen differentiation.

## 15.3 Team Split (suggested for four members)

(1) Profile, documents and guard; (2) jobs, extension and matching; (3) applications, email and events; (4) agent, LLM, CLI and CI. Each owner writes unit tests for their slice; integration is weekly.

## 15.4 Future Extensions (explicitly out of Stage 2 scope)

Official LinkedIn or other partner API adapters; Microsoft Outlook (Graph) adapter; Google Calendar sync of interviews (as a new observer); semantic retrieval with pgvector; OCR for scanned resumes; voice mock interviews; Firefox/Edge extension; form **autofill assistance** that still leaves submission to the student (never auto-submission); a shared analysis cache across users for identical postings; university SSO.

---

# 16. Stage 3 Testing and Deployment Roadmap

| Area | Plan | Tooling |
|---|---|---|
| Unit tests | State transitions (every legal and illegal pair in SM-1); strategies; normalizer, fingerprint and merge; FabricationGuard (adversarial cases: invented metric, unknown org, unverified fact, another user's fact id); classifiers with a labelled email fixture set; planner validation; reasoner rules | pytest, pytest-django, factory_boy; Vitest for React |
| Integration tests | Import → dedup → triage; package build end-to-end with `MockLLMProvider`; email `.eml` → event → state → notification; agent run with approval and undo | pytest + Celery eager mode; docker-compose test profile |
| Contract tests | Extension payloads against the API schema; York extractor against HTML fixtures | Jest (extension), JSON schema tests |
| AI quality evaluation | Small gold sets: 30 postings (analysis F1 on skills), 60 emails (classification precision/recall; target precision ≥ 0.95 for auto-applied events), 20 generated resumes (0 guard escapes) | Evaluation scripts in `backend/tests/eval/` |
| Security testing | Tenant-isolation tests on every endpoint; OWASP ZAP baseline scan; dependency audit (pip-audit, npm audit); secret scanning; upload fuzzing; prompt-injection test cases | GitHub Actions |
| Performance testing | 500-posting batch import; 50 concurrent users on core endpoints; worker throughput per queue | Locust |
| UI / E2E | Critical journeys: import → shortlist → package → mark applied → email → interview prep | Playwright |
| Deployment | Production compose + reverse proxy + TLS on a Docker-capable host; managed or containerized PostgreSQL with backups; environment secrets; health checks | Docker, GitHub Actions CD |
| Documentation | Updated UML (checker green), API docs (OpenAPI via drf-spectacular), user guide, runbook, known limitations | `docs/` |
| Demonstration | Scripted demo on seeded, fictional data: extension import from the York fixture page, triage, fabrication guard catching an invented metric, email-driven interview with undo, agent run with approval, insights adjustment | Demo script in `docs/stage3/` |

