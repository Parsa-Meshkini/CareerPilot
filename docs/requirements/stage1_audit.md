# Stage 1 Architecture & Feasibility Audit — CareerPilot

Performed before the design was written. The inputs were the official *EECS3311 Fall 2026 Course Project Stage-1 Instruction*, the lecture slides *EECS 3311 UML (I)* and *UML (II)*, and the team's original CareerPilot proposal (20 features, 5+ patterns, York/LinkedIn/Gmail integrations).

Severity levels: **CRITICAL BLOCKER** (the project cannot proceed as proposed) · **CRITICAL (fixed)** (would have been a blocker; resolved without changing the core idea) · **MAJOR** · **RECOMMENDATION** · **FUTURE ENHANCEMENT**.

**Result: no remaining critical blockers.** All critical issues were resolved in the design. The report's §0 summarizes the audit; this file gives the reasoning.

## 1. Evaluation against the 13 audit dimensions

| # | Dimension | Finding | Verdict |
|---|---|---|---|
| 1 | Official Stage 1 requirements | GUI, CLI, ≥ 10 non-trivial features, ≥ 5 patterns, an LLM and agent behaviour are all achievable. The original draft lacked an explicit CLI-to-service sharing story and use-case descriptions with every required field. | Met after design (report Appendix A, C) |
| 2 | UML slides | The draft had the extension as an actor (the slides say actors are external) and no plan for include/extend/generalization. Class-diagram notation must use visibility markers, italics for abstract elements, «interface», and multiplicities. | Fixed (A5; §5 notation) |
| 3 | Agent-system requirements | The loop was named but not specified; learn/adapt was vague. | Fixed: planner/reasoner/tools/memory, limits, playbooks, concrete F19 (A7, A8) |
| 4 | OOP / design-pattern quality | Factory Method and MVC were weakly justified. Facade/Template Method fit real problems. | 7 justified patterns; simple factories not counted (A10) |
| 5 | Scalability | Correct instinct (queues). Microservices were not needed. | Modular monolith + Celery queues (A12) |
| 6 | Maintainability | Adapters for every external system; provider-neutral LLM access; prompts versioned in the repository. | Good |
| 7 | Realistic Stage 2 | 20 features plus extension, Gmail and agent is heavy for one term. | Tiering: Tier 1 (12) alone meets the minimums (A11) |
| 8 | Realistic Stage 3 | Gmail restricted-scope verification is not realistic within the term. | Testing mode + .eml path (A3) |
| 9 | Security / privacy | Resumes and email are sensitive. The draft already avoided password storage. | Full controls in §13 |
| 10 | External integration risk | LinkedIn ToS; York has no official API; Gmail scope class. | A1–A3 |
| 11 | Differentiation from sample #7 | ~12/20 features overlapped. | Merged and re-centred (A4, report §1.6) |
| 12 | Feature → design traceability | Several draft features ("job comparison", "career strategy") had no design home. | Every feature is mapped to classes, methods and a sequence diagram; checked automatically |
| 13 | Implementable by a student team | Yes, with tiering, mocks and a modular monolith. | Yes |

## 2. A–G findings

**A. Must be corrected**
1. *CRITICAL (fixed)* LinkedIn extraction by browser extension conflicts with LinkedIn's User Agreement and its "Prohibited software and extensions" policy, which prohibit browser plug-ins or add-ons that scrape or copy the service. → Paste-only import; the extractor and adapter are disabled.
2. *CRITICAL (fixed)* No documented Experience York API may be assumed; the portal is authenticated. → Click-to-import from the open page only; no crawling or credentials; fixtures and a mock for development.
3. *CRITICAL (fixed)* The Gmail read scope is restricted: production needs verification and an annual CASA assessment. Testing-mode tokens expire after ~7 days. → Designed reauth flow; `.eml` and mock adapters.
4. *MAJOR* The extension was modelled as an actor. → Boundary component.
5. *MAJOR* "Needs review" was modelled as an application state. → An email-event review status.
6. *MAJOR* Heavy overlap with sample #7. → Merged features; differentiation table.

**B. Unnecessarily complicated**
- Microservices (none needed); a separate "DecisionManager" class (folded into `Reasoner`/`Decision`); 20 features with thin variants such as a separate "job comparison" (folded into F08) and separate "skill extraction" (folded into F02); vector database for memory (deferred; keyword/recency retrieval first).

**C. Missing**
- Anti-fabrication mechanism (added: verified facts, citations, `FabricationGuard` in the Template Method); agent limits, budgets and fallbacks; undo for automatic changes; SUMMARY vs FULL posting completeness; review queue; data export/deletion; cost control; prompt-injection handling; the CLI ↔ GUI shared-service design; the UML consistency check.

**D. Better architectural decisions adopted**
- The CLI is an API client, not a second backend. The extension has no business logic. Email processing is rules-first with the LLM as fallback (Strategy). Domain events with observers replace direct calls. Commands give approval, logging and undo. Every repository method is user-scoped. JSON-schema-validated LLM output is used everywhere.

**E. Risky assumptions identified**
- York: an API exists (no); automation of login/MFA is acceptable (no); list pages contain full descriptions (usually not).
- LinkedIn: extension extraction is "fine for personal use" (not per LinkedIn's terms).
- Gmail: any student can connect in production (not without verification); tokens last indefinitely (not in Testing mode).
- AI providers: a single provider is always available and cheap (design for switching, budgets and mocks); LLM output is truthful (verify deterministically).
- Browser extensions: Manifest V3 service workers are persistent (they are not; state in `chrome.storage`); broad host permissions are needed (`activeTab` suffices).

**F. Moved to future extension**
- LinkedIn extraction or official API adapter; Outlook adapter; calendar sync; voice mock interviews; pgvector semantic memory; OCR; Firefox/Edge; autofill assistance (never auto-submit); a cross-user analysis cache; SSO.

**G. Are the patterns genuinely necessary?**

| Proposed | Verdict | Reason |
|---|---|---|
| Adapter | Keep | Three families of genuinely incompatible external interfaces |
| Strategy | Keep, re-scoped | Different scoring *algorithms* per goal + classifier selection (not just "career strategies") |
| State | Keep | The lifecycle is the core business rule, with three sources of transitions |
| Observer | Keep | One event, many independent reactions |
| Command | Keep | Approval, logging and undo of agent actions |
| Template Method | Add | Guarantees that the fabrication guard runs in every generator |
| Facade | Add | Twelve LLM call sites need one safe entry point |
| Factory Method | Not counted | The registries are simple factories; claiming GoF Factory Method would be inaccurate |
| MVC | Not claimed | Django MTV + SPA is not classical MVC; claiming it adds nothing |
| Decorator / Iterator | Not used | No problem in the design calls for them |

## 3. Sources consulted for external-integration claims

- LinkedIn Help: "Prohibited software and extensions" — https://www.linkedin.com/help/linkedin/answer/a1341387/prohibited-software-and-extensions
- LinkedIn User Agreement — https://www.linkedin.com/legal/user-agreement
- Google for Developers: Restricted scope verification — https://developers.google.com/identity/protocols/oauth2/production-readiness/restricted-scope-verification
- Google for Developers: Gmail API scopes — https://developers.google.com/workspace/gmail/api/auth/scopes
- Reports of 7-day refresh-token expiry for apps in Google "Testing" status, e.g. https://www.unipile.com/google-oauth-refresh-token/
- York University, Experience York portal (authenticated) — https://experience.yorku.ca/ ; Co-op job postings — https://www.yorku.ca/co-op/searching-for-a-co-op-position/co-op-job-postings/

Before Stage 3, re-verify these policies, because they change over time.
