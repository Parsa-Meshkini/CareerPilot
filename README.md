# CareerPilot

**Adaptive AI Career & Application Management Agent**

CareerPilot is an AI-agent-based system that manages a student's job search from end to end. It imports and organizes job postings, scores them against the candidate's goals, prepares tailored application documents, tracks each application through its lifecycle, and adapts its strategy based on real outcomes. The user approves every consequential action.

> **Human control is a product rule.** CareerPilot prepares, organizes, tracks and recommends. It never submits an application on the user's behalf.

This repository hosts the course project for EECS 3311 Software Design (Fall 2026, York University) across Stages 1, 2 and 3.

## Contents

- [Key capabilities](#key-capabilities)
- [Running locally](#running-locally)
- [Project status](#project-status)
- [Stage 1 deliverables](#stage-1-deliverables)
- [Features](#features)
- [Architecture](#architecture)
- [Repository layout](#repository-layout)
- [Working with the design artefacts](#working-with-the-design-artefacts)
- [Integrations and responsible use](#integrations-and-responsible-use)
- [Team](#team)

## Key capabilities

- **Import:** batch-import postings from the page being viewed through a browser extension, or add them manually.
- **Normalize:** clean and deduplicate postings into a consistent format.
- **Analyze:** score each posting with a goal-specific strategy and identify skill gaps.
- **Triage:** let the agent reduce a batch of postings to a shortlist.
- **Prepare:** generate a fabrication-guarded tailored resume, a cover letter and a downloadable application package.
- **Track:** follow each application through a validated lifecycle, with undo.
- **Detect:** recognize interview invitations, rejections and offers in email.
- **Adapt:** learn from the user's own outcomes to adjust future strategy.

## Running locally

### Prerequisites

- JDK 17 or newer and Maven 3.9 or newer (`brew install openjdk@17 maven` on macOS)
- An LLM API key. A free Google Gemini key from Google AI Studio works. Any OpenAI-compatible endpoint (OpenAI, Groq, OpenRouter) works too.
- Node.js 18 or newer, only to run the Chrome extension's unit tests

### 1. Configure your key (local only, never committed)

```bash
cp .env.example .env
# edit .env and set LLM_API_KEY=<your key>   (LLM_PROVIDER=gemini, LLM_MODEL=gemini-2.5-flash by default)
```

`.env` is listed in `.gitignore`. Only `.env.example`, which holds placeholders, is in the repository.
Data (an H2 database and generated documents) lives in `~/.careerpilot`, shared by the GUI, CLI and server.

### 2. Build

```bash
mvn -q install -DskipTests        # builds core, server, CLI and GUI
```

### 3. GUI (JavaFX desktop app)

```bash
bin/careerpilot-gui               # or: mvn -q -pl careerpilot-gui javafx:run
```

Create an account on the sign-in screen, then work through Profile, Jobs, Tracker, Agent and Email.

### 4. CLI

```bash
bin/careerpilot --help
bin/careerpilot register --email me@my.yorku.ca --name "Me"          # prompts for a password
bin/careerpilot profile goal-set --type COOP --term "Summer 2027" --roles backend
bin/careerpilot profile fact-add SKILL name=python
bin/careerpilot profile resume samples/resume.txt                    # AI extracts facts to review
bin/careerpilot jobs import-file samples/jobs.csv                    # the agent triages the batch
bin/careerpilot jobs list
bin/careerpilot agent run "Prepare my application for the Acme backend co-op"
bin/careerpilot agent approve <run id>                               # you approve each side-effecting step
bin/careerpilot docs package <application id> --out acme.zip
bin/careerpilot email upload samples/emails/*.eml && bin/careerpilot email events
bin/careerpilot llm-check                                            # confirms the key and model respond
```

Ids are printed as 8 characters; type those. Add `--json` for machine-readable output.

### 5. Chrome extension (F04)

```bash
bin/careerpilot-server            # REST API on http://127.0.0.1:8000
```

In Chrome open `chrome://extensions`, enable developer mode, choose **Load unpacked** and select the `extension/` folder.
Sign in from the popup (the same account as the GUI), open a posting list, and click **Import**. Nothing is read until you click.

### 6. Try it (about two minutes)

1. **Profile:** set a career goal, add a few facts or import a resume and verify the extracted facts.
2. **Jobs:** import `samples/jobs.csv` or paste a posting, then select a job and click **Analyze with AI**.
3. **Agent:** enter "Prepare my application for the Acme backend co-op", then approve or reject each step.
4. **Tracker:** generate the resume and cover letter, build the package ZIP, and move the application through its states (with undo).
5. **Email:** sync the demo inbox or upload `samples/emails/*.eml`, then confirm or dismiss the detected events.

### Tests

```bash
mvn -q verify                                      # offline: mock LLM and in-memory database, no key needed
node --test extension/tests/extractor.test.mjs     # extension unit tests
```

The GUI render test writes a screenshot of every page to `careerpilot-gui/target/screenshots` and is skipped on machines without a display.

## Project status

| Stage | Scope | Status |
|---|---|---|
| **Stage 1** | Design: features, UML, patterns, traceability | Complete |
| Stage 2 | Implementation in Java: core services, JavaFX GUI, CLI, agent, REST server and extension | In progress (F01-F14, F16 working; F15, F17-F19 planned) |
| Stage 3 | Testing, deployment, demonstration | Planned (report §16) |

## Stage 1 deliverables

| Deliverable | Location |
|---|---|
| Project Design Report (Markdown) | [`docs/stage1/CareerPilot_Stage1_Report.md`](docs/stage1/CareerPilot_Stage1_Report.md) |
| Project Design Report (PDF) | [`docs/stage1/CareerPilot_Stage1_Report.pdf`](docs/stage1/CareerPilot_Stage1_Report.pdf) |
| Feature-to-design traceability (CSV) | [`docs/stage1/traceability.csv`](docs/stage1/traceability.csv) |
| Architecture and feasibility audit | [`docs/requirements/stage1_audit.md`](docs/requirements/stage1_audit.md) |
| Feature index | [`docs/requirements/features.md`](docs/requirements/features.md) |
| Architecture decision records | [`docs/decisions/`](docs/decisions/) |
| UML sources (UMLet `.uxf`) | [`docs/uml/src/`](docs/uml/src/) |
| Rendered UML (PNG and zoomable SVG) | [`docs/uml/rendered/`](docs/uml/rendered/) |
| GUI wireframes | [`docs/architecture/wireframes/`](docs/architecture/wireframes/) |
| UML consistency checker | [`tools/check_uml_consistency.py`](tools/check_uml_consistency.py) |
| UML render script | [`tools/render_uml.py`](tools/render_uml.py) |

### UML overview

All diagrams are drawn in [UMLet](https://www.umlet.com). Open any `.uxf` file in `docs/uml/src/` with UMLet to view or edit it.

| Diagram type | Diagrams |
|---|---|
| Class | CD-1 main diagram, CD-2 domain, CD-3 agent and LLM, CD-4 integrations and events, CD-5 services and state, CD-6 documents, interviews and analytics, CD-7 boundary layer |
| Use case | UCD-1 and UCD-2 (19 use cases) |
| Sequence | SD01 to SD12 (covering all 19 features) |
| Supplementary | SM-1 application state machine, AD-1 agent loop, DD-1 deployment |

### Design patterns

The design applies seven patterns, each tied to a concrete problem (report §6): Adapter, Strategy, State, Observer, Command, Template Method and Facade.

## Features

The system defines 19 features across three tiers.

| Tier | Features |
|---|---|
| **Tier 1** (committed core) | F01 Profile and verified facts · F02 Resume import and AI fact extraction · F03 Career goals and strategy · F04 Browser-extension batch import · F05 Manual import · F06 Normalization and deduplication · F07 AI job analysis · F08 Matching and skill gap · F10 Tailored resume with fabrication guard · F12 Application package (ZIP) · F13 Lifecycle tracking and undo · F14 Email event detection (.eml / mock) · F16 Goal-driven agent workspace |
| **Tier 2** | F09 Agent batch triage · F11 Cover letter · F14 Gmail OAuth adapter · F15 Event-driven updates and reminders · F17 Interview preparation · F19 Outcome analytics and adaptive strategy |
| **Tier 3** | F18 AI mock interview |

## Architecture

CareerPilot is a modular monolith in Java (Stage 2). One shared core is used by every front end.

| Layer | Technology |
|---|---|
| Clients | JavaFX desktop GUI, picocli command line, Chrome MV3 extension |
| API | Spring Boot REST server (extension and external clients) |
| Application | Spring services in `careerpilot-core`: candidates, jobs, applications, documents, agent, integrations |
| Data | Spring Data JPA on H2 (any JDBC database through `DATABASE_URL`), local object storage |
| Background work | In-process task queue |
| LLM | Provider-neutral `LlmClient` facade (Gemini primary, OpenAI-compatible alternative, Mock for tests) |
| Delivery | Maven multi-module build, GitHub Actions CI |

## Repository layout

```
pom.xml                  Maven parent (Spring Boot 3, Java 17)
careerpilot-core/        domain, services, LLM subsystem, agent, integrations (+ the automated test suite)
careerpilot-server/      REST API for the Chrome extension
careerpilot-cli/         picocli command-line interface
careerpilot-gui/         JavaFX desktop application
samples/                 safe sample data: jobs, resume, emails, extension payload
extension/               Chrome MV3 extension (JavaScript, as required by the browser)
bin/                     careerpilot, careerpilot-gui and careerpilot-server launchers
docs/                    Stage 1 report, UML, requirements, decisions, architecture
deployment/              Production configuration (Stage 3)
tools/                   UML consistency checker, UML render script, report builder
```

## Working with the design artefacts

```bash
# Verify UML / traceability consistency (also runs in CI)
python tools/check_uml_consistency.py

# Re-render diagrams to PNG and SVG (requires Java + UMLet; set UMLET_JAR to umlet.jar)
python tools/render_uml.py

# Rebuild the report from docs/stage1/parts (+ PDF: requires pandoc and playwright)
python tools/build_report.py --pdf
```

## Integrations and responsible use

- **Experience York:** the extension reads only the page the user already has open, and only when they click Import. It performs no crawling and handles no credentials, CAPTCHA or MFA. Development uses saved fixtures and a mock adapter.
- **LinkedIn:** paste-import only. Automated extraction is disabled in line with LinkedIn's terms.
- **Gmail:** OAuth with the `gmail.readonly` scope. Tokens are encrypted, and `.eml` upload is available as an alternative.
- **Secrets:** never committed. Copy `.env.example` to `.env` locally.

## Team

| Name | Role |
|---|---|
| Parsa Meshkini | Full-stack |
