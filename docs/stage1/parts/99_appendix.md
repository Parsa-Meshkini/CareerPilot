
---

# Appendix A. Stage 1 Self-Review Checklist

| Check | Status | Evidence |
|---|---|---|
| At least 10 meaningful, distinct features | ✔ 19 (12 in Tier 1) | §4 |
| GUI exists and exposes major functionality | ✔ | §10, CD-7 |
| CLI exists and exposes major functionality of the agent | ✔ | §11, CD-7 |
| At least one AI/LLM model integrated | ✔ Gemini (primary) / OpenAI, provider-neutral | §1.5, §3.8 |
| Meaningful agent behaviour (planning, reasoning, tools, retrieval, memory, decisions, multi-step) | ✔ | §3, SD05, SD10, AD-1 |
| At least 5 meaningful design patterns, each solving a real problem | ✔ 7 | §6 |
| Class diagram(s) with attributes, methods, associations, dependencies, inheritance, aggregation/composition, multiplicities, AI components | ✔ CD-1…CD-7 | §5.1 |
| Use-case diagram with actors (primary, external services, AI) and include/extend/generalization | ✔ UCD-1, UCD-2 | §5.2 |
| Sequence diagrams with actor, boundary, controller, domain, AI, external services, returns, alt flows | ✔ SD01–SD12 | §5.3 |
| Diagrams mutually consistent; sequence diagrams use class-diagram methods | ✔ automated | §8.2 |
| Use cases cover all features | ✔ 19 UCs ↔ 19 features | §7, §8 |
| Every feature → use case, classes, methods, sequence diagram, patterns | ✔ | §8 |
| Detailed use-case descriptions (all required fields) | ✔ | §7 |
| Feature implementation explanations | ✔ | §9 |
| External integrations behind abstractions; York and LinkedIn not hard dependencies | ✔ | §6 P1, §12 |
| Gmail via OAuth; no password storage | ✔ | §12.4, §13 |
| No unauthorized scraping or access-control bypass | ✔ | §0 A1–A2, §12 |
| Human approval before submission; CareerPilot never submits | ✔ | §3.3, F12, F16 |
| Multi-user, secure, scalable, asynchronous, deployable | ✔ | §2.4, §12, §13 |
| No unnecessary microservices | ✔ modular monolith | §12.6 |
| Clearly differentiated from sample #7 | ✔ | §1.6 |
| No invented course requirements; no unsupported external-API claims | ✔ (claims about Google and LinkedIn policy cited in `docs/requirements/stage1_audit.md`) | §0 |
| Repository structure supports Stages 1–3; report committed in the public repo | ✔ | Appendix B, README |

# Appendix B. Repository Structure

```
CareerPilot/
├── README.md                     project summary, Stage 1 links, how to render/check UML
├── .gitignore  .env.example  docker-compose.yml   (compose = Stage 2 draft, clearly marked)
├── backend/                      Django project (Stage 2) — app folders with README placeholders
│   ├── careerpilot/  users/  candidates/  jobs/  applications/  interviews/  documents/
│   ├── agent/ (llm/, prompts/)  integrations/ (york/, linkedin/, gmail/, eml/)  notifications/  analytics/
│   └── tests/
├── frontend/                     React + Vite SPA (Stage 2)
├── extension/                    Chrome MV3 extension (manifest scaffold; content/, popup/, background/, api/)
├── cli/                          careerpilot CLI (Typer)
├── docs/
│   ├── stage1/                   CareerPilot_Stage1_Report.md / .pdf, traceability.csv, parts/
│   ├── uml/src/{class,usecase,sequence,supplementary}/*.uxf   uml/rendered/*.png|svg
│   ├── architecture/             wireframes/, architecture notes
│   ├── requirements/             stage1_audit.md, features.md (index)
│   └── decisions/                ADR-0001 … ADR-0008
├── deployment/                   production compose / proxy config (Stage 3)
├── tools/check_uml_consistency.py
└── .github/workflows/ci.yml      UML render + consistency check (Stage 1); tests/build added in Stage 2
```

# Appendix C. Requirement-to-Section Map

| Stage 1 instruction item | Where addressed |
|---|---|
| Task 1 / 1.1 Project description (problem, users, agent, why agent, model, AI interaction) | §1.1–1.5, §2, §3 |
| Task 1 / 1.2 Feature specification (8 items per feature) | §4 |
| Task 2 / 2.1 Class diagram (+ AI/agent components) | §5.1 |
| Design patterns (≥ 5; problem, classes, roles, rationale, what is harder without) | §6 |
| Task 2 / 2.2 Use-case diagram and descriptions (all fields) | §5.2, §7 |
| Task 2 / 2.3 Sequence diagrams | §5.3 |
| Task 3 Feature-to-design traceability table | §8 |
| Task 4 Feature realization explanations | §9 |
| Deliverables 1–9 | §1–9 |
| How to submit: public GitHub repository, same repository for all stages, report committed | README, Appendix B |

