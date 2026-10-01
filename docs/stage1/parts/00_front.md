---
title: "CareerPilot — Adaptive AI Career & Application Management Agent"
subtitle: "EECS 3311 Fall 2026 — Course Project Stage 1: Project Design Report"
---

# CareerPilot — Stage 1 Project Design Report

| | |
|---|---|
| **Course** | EECS 3311 Software Design, Fall 2026, York University |
| **Deliverable** | Stage 1: Agent project definition, UML design, design patterns and feature-to-design traceability |
| **Repository** | `https://github.com/Parsa-Meshkini/CareerPilot` (public; used for Stages 1, 2 and 3) |
| **Team** | Parsa Meshkini |
| **Version** | 1.0 (Stage 1 submission) |

> **How to read this report.** Sections 1–3 define the project and its agent. Section 4 specifies the 19 features. Section 5 contains the UML design: class, use-case and sequence diagrams, plus justified supplementary diagrams. Sections 6–9 cover design patterns, use-case descriptions, the traceability matrix and per-feature implementation explanations, as required by Tasks 1–4 of the Stage 1 instructions. Sections 10–16 cover the GUI, the CLI, integrations, deployment, security, risks and the Stage 2/3 roadmaps. Every diagram is drawn in UMLet and has an editable `.uxf` source in `docs/uml/src/`. `tools/check_uml_consistency.py` automatically verifies that the class diagrams, sequence diagrams and traceability matrix are consistent (Section 8.2).

## Table of Contents

- [0. Stage 1 Architecture & Feasibility Audit (summary)](#0-stage-1-architecture-feasibility-audit-summary)
- [1. Project Overview](#1-project-overview)
- [2. System Overview](#2-system-overview)
- [3. AI / Agent Design](#3-ai-agent-design)
- [4. Feature Specifications](#4-feature-specifications)
- [5. UML Design](#5-uml-design)
- [6. Design Patterns](#6-design-patterns)
- [7. Use-Case Descriptions](#7-use-case-descriptions)
- [8. Feature-to-Design Traceability](#8-feature-to-design-traceability)
- [9. Feature Implementation Explanations](#9-feature-implementation-explanations)
- [10. GUI Design](#10-gui-design)
- [11. CLI Design](#11-cli-design)
- [12. Integration, Scalability and Deployment Architecture](#12-integration-scalability-and-deployment-architecture)
- [13. Security and Privacy](#13-security-and-privacy)
- [14. Risks and Limitations](#14-risks-and-limitations)
- [15. Stage 2 Implementation Roadmap](#15-stage-2-implementation-roadmap)
- [16. Stage 3 Testing and Deployment Roadmap](#16-stage-3-testing-and-deployment-roadmap)
- [Appendix A. Stage 1 Self-Review Checklist](#appendix-a-stage-1-self-review-checklist)
- [Appendix B. Repository Structure](#appendix-b-repository-structure)
- [Appendix C. Requirement-to-Section Map](#appendix-c-requirement-to-section-map)

---

# 0. Stage 1 Architecture & Feasibility Audit (summary)

Before designing anything, the team audited the original CareerPilot idea against the Stage 1 instructions, the UML lecture notes (EECS 3311 "UML" I and II), agent-system expectations, and realistic Stage 2/3 constraints. The full audit, with severities, is in `docs/requirements/stage1_audit.md`. The decisions it produced are summarized here because they shape the rest of the design.

| # | Finding | Severity | Resolution adopted in this design |
|---|---|---|---|
| A1 | LinkedIn's User Agreement and its "Prohibited software and extensions" policy forbid browser plug-ins and add-ons that scrape or copy LinkedIn data. Batch extraction from LinkedIn pages is therefore not acceptable. | Critical (fixed) | LinkedIn is an **integration slot, not a dependency**. Core scope: the student pastes a LinkedIn posting's text (`ManualJobImportAdapter`, F05). `LinkedInBrowserImportAdapter` / `LinkedInExtractor` exist in the design but are **disabled** until a ToS review or an official partner API allows them. |
| A2 | No public, documented Experience York job API is known. The portal requires York authentication, and its list pages generally show only posting summaries. | Critical (fixed) | The extension reads **only the page the student already has open, only when the student clicks**. It never crawls, paginates automatically, sends background requests, or handles York credentials. Batch-imported postings are stored with `completeness = SUMMARY` and are upgraded to `FULL` when the student opens a posting. Stage 2/3 tests and demos use a saved HTML fixture and `MockJobSourceAdapter`, so the project never depends on the live portal. |
| A3 | `gmail.readonly` is a Google **restricted** scope. Public production use requires OAuth verification and an annual third-party (CASA) security assessment. In Google's "Testing" publishing status, refresh tokens expire after about 7 days and the user count is capped. | Critical (fixed) | Gmail uses OAuth only; passwords are never stored. For the course, the app stays in Testing mode with listed test users, and **re-authorization is a designed flow** (`EmailAccount.status = REAUTH_REQUIRED`). `EmlFileAdapter` (upload `.eml`) and `MockEmailAdapter` make email event detection a Tier-1 feature without depending on Google verification. |
| A4 | About 12 of the 20 proposed features duplicate the instructor's sample "AI Job Search and Career Agent" (profile, resume import, skill extraction, job import, JD analysis, skill gap, matching, comparison, resume suggestions, cover letter, interview questions, mock interview, tracking). | Major (fixed) | Commodity features were **merged** (F01/F02 profile + resume + fact extraction; F07/F08 analysis + match + gap). The design is centred on what the sample lacks: extension batch import with normalization and deduplication, agent triage, a **fabrication-guarded** application package, email-driven state changes, and outcome-driven strategy adaptation. See Section 1.6. |
| A5 | The original draft listed "Chrome Extension" as a use-case actor. Per the UML notes, an actor is *external* to the system being modeled; our extension is part of our system. | Major (fixed) | The extension is a **boundary component** (CD-7). Actors: Student; Job Portal (specialized by Experience York and LinkedIn); Email Provider (Gmail); LLM Provider; Scheduler. |
| A6 | Email-classification uncertainty was proposed as an application state ("needs review"). | Major (fixed) | `NEEDS_REVIEW` belongs to `DetectedEmailEvent.reviewStatus`. Application states are SAVED, PREPARED, APPLIED, INTERVIEW, OFFER, and the terminal states ACCEPTED, DECLINED, REJECTED and WITHDRAWN. Every automatic change is recorded as an undoable `StatusChange`. |
| A7 | "Learn / adapt" was undefined and risked being hand-waving. | Major (fixed) | Adaptation is concrete (F19): deterministic outcome statistics → bounded, evidence-cited `StrategyAdjustment` proposals → **explicit student acceptance** → updated `StrategyWeights` and a STRATEGY memory, which later matching and agent planning read. Below 10 outcomes the system reports "insufficient data" rather than inventing insights. |
| A8 | Agent loop unbounded (cost, loops, silent side effects). | Major (fixed) | `AgentController` enforces max 8 steps, max 2 replans and a per-run token budget. It falls back to a deterministic playbook when the LLM's plan fails validation. `ApprovalPolicy` gates every side-effecting command, and every command is logged and, where reversible, undoable (Command pattern). |
| A9 | Resume/cover-letter fabrication is the main product risk. | Major (fixed) | Only student-**verified** `ProfileFact`s reach generators. The LLM must cite fact ids for every bullet. `FabricationGuard` deterministically rejects unknown ids and numbers, organizations or skills absent from the cited facts. This step is fixed inside the `DocumentGenerator` Template Method, so no subclass can skip it. |
| A10 | Pattern list included patterns chosen to "hit the number" (Factory Method, MVC). | Recommendation | Seven patterns are counted, each tied to a concrete problem: Adapter, Strategy, State, Observer, Command, Template Method and Facade. Provider registries are described honestly as *simple factories* and are not counted. MVC is not claimed (Django's MTV plus a React SPA is not classical MVC). |
| A11 | 20 features + extension + Gmail + Celery is heavy for one term. | Recommendation | Features are tiered: **Tier 1** (12 features) alone satisfies every Stage 1 minimum. Tier 2 (6) completes the differentiators; Tier 3 (1) is stretch. Future extensions are listed separately (Section 15.4). |
| A12 | Microservices were implied by "scalable". | Recommendation | **Modular monolith**: one Django codebase with strict app boundaries, horizontally scaled web and Celery worker containers, PostgreSQL, Redis and S3-compatible object storage. |

**Blockers requiring the team's input:** none. All critical issues were resolved without changing the core product idea. Two items remain for the team to confirm before Stage 3 (not Stage 1): (i) whether York's Co-op & Career Centre is comfortable with the click-to-import extension being demonstrated against the live portal (otherwise the demo uses the fixture); and (ii) which LLM provider account will be used for the demo (the design is provider-neutral).

