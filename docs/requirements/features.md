# CareerPilot Feature Index

Full specifications are in the Stage 1 report, section 4. Traceability is in `docs/stage1/traceability.csv`.

| ID | Feature | Type | Tier | Use case(s) | Sequence diagram(s) | Patterns |
|---|---|---|---|---|---|---|
| F01 | Candidate Profile & Verified Facts | Deterministic | Tier 1 | UC01 | SD01 | Repository (architectural) |
| F02 | Resume Import & AI Fact Extraction | Hybrid | Tier 1 | UC02 | SD01 | Facade (LLMClient), Adapter (LLMProvider) |
| F03 | Career Goal & Strategy Configuration | Deterministic | Tier 1 | UC03 | SD01 | Strategy |
| F04 | Browser-Extension Batch Job Import | Deterministic | Tier 1 | UC04 | SD02 | Adapter, Observer (JobsImportedEvent) |
| F05 | Manual Job Import (paste / file) | Hybrid | Tier 1 | UC05 | SD03 | Adapter, Facade |
| F06 | Job Normalization & Duplicate Detection | Deterministic | Tier 1 | UC06 | SD02, SD03 | Repository (architectural) |
| F07 | AI Job Description Analysis | AI | Tier 1 | UC07 | SD04 | Facade, Adapter |
| F08 | Candidate-Job Matching & Skill-Gap Analysis | Hybrid | Tier 1 | UC07 | SD04 | Strategy |
| F09 | Agent Batch Triage & Shortlist | AI (agent) | Tier 2 | UC08 | SD05 | Command, Observer, Strategy |
| F10 | Tailored Resume Generation with Fabrication Guard | AI/Hybrid | Tier 1 | UC09 | SD06 | Template Method, Facade |
| F11 | Tailored Cover Letter Generation | AI/Hybrid | Tier 2 | UC09 | SD06 | Template Method, Facade |
| F12 | Application Package Builder & ZIP Export | Deterministic | Tier 1 | UC10 | SD06 | Template Method (uses), Command (BuildPackageCommand) |
| F13 | Application Lifecycle Tracking & Undo | Deterministic | Tier 1 | UC11 | SD07 | State, Observer |
| F14 | Email Monitoring & Application Event Detection | Hybrid | Tier 1 (EML/mock) / Tier 2 (Gmail OAuth) | UC12, UC13, UC14 | SD08, SD09 | Adapter, Strategy |
| F15 | Event-Driven Updates Reminders & Notifications | Deterministic | Tier 2 | UC13, UC15 | SD09 | Observer, State, Command (undo) |
| F16 | Goal-Driven Agent Workspace | AI (agent) | Tier 1 | UC16 | SD10 | Command, Facade |
| F17 | Interview Preparation Plan | AI | Tier 2 | UC17 | SD11 | Facade, Observer (auto-trigger from InterviewScheduler) |
| F18 | AI Mock Interview & Feedback | AI | Tier 3 | UC18 | SD11 | Facade |
| F19 | Outcome Analytics & Adaptive Strategy | Hybrid | Tier 2 | UC19 | SD12 | Strategy, Observer (MemoryRecorder feeds data) |
