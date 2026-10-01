
---

# 10. GUI Design

The GUI is a React + Vite single-page application that talks only to the REST API. Every page is a boundary class in CD-7, and every button maps to a documented API call. The GUI exposes **all 19 features**.

| Page (boundary class) | Features | Key interactions (methods) |
|---|---|---|
| Dashboard (`DashboardPage`) | F09, F14, F15, F16 | shortlist summary, deadlines, notifications, runs awaiting approval (`loadDashboard()`) |
| Profile (`ProfilePage`) | F01, F02, F03 | edit facts, `uploadResume()`, `verifyFacts()`, `saveGoal()` |
| Jobs (`JobsPage`) | F04, F05, F06, F08 | batch progress `showBatchProgress()`, `pasteJob()`, `filterJobs()`, `compareJobs()` |
| Job detail (`JobDetailPage`) | F07, F08 | `requestAnalysis()`, match and gap card, `createApplication()` |
| Application tracker (`ApplicationTrackerPage`) | F12, F13, F15 | Kanban by state, `changeStatus()`, `undoChange()`, `generatePackage()`, `downloadPackage()` |
| Document center (part of the tracker's application drawer) | F10, F11 | versions, verification status, side-by-side bullet ↔ fact view |
| Review queue (`EventReviewPage`) | F14 | `confirmEvent()`, `dismissEvent()` |
| Interview prep (`InterviewPrepPage`) | F17, F18 | `preparePlan()`, `startMock()`, `sendAnswer()`, `finishMock()` |
| Agent workspace (`AgentWorkspacePage`) | F16, F09 | `submitGoal()`, `approveStep()`, `undoAction()` |
| Insights (`InsightsPage`) | F19 | `loadInsights()`, `acceptAdjustment()` |
| Settings (`SettingsPage`) | F14, privacy | `connectGmail()`, `uploadEml()`, `exportMyData()`, `deleteMyData()`, auto-triage toggle |
| Extension popup (`ExtensionPopup`) | F04 | `showDetected()`, `onImportClicked()` |

**Conceptual wireframes** (low-fidelity; source: `docs/architecture/wireframes/wireframes.html`):

![W1 Dashboard](../architecture/wireframes/wf_dashboard.png)

![W2 Jobs / Job detail](../architecture/wireframes/wf_jobdetail.png)

![W3 Application tracker](../architecture/wireframes/wf_tracker.png)

![W4 Agent workspace](../architecture/wireframes/wf_agent.png)

![W5 Extension popup and W6 Insights](../architecture/wireframes/wf_extension.png)

**GUI principles:** show *why* (score breakdowns, cited facts, reasoning traces, evidence for automatic changes); show only legal actions; put every automatic change next to an Undo; keep AI actions visible and interruptible; state explicitly and repeatedly that the student submits applications.

---

# 11. CLI Design

`careerpilot` is a Python (Typer) command-line client (`CareerPilotCLI` → `CliApiClient`) that calls **the same REST API** as the GUI. The GUI and CLI therefore share authentication, validation, services, agent, events and data, with **no duplicated business logic**. The CLI exists for keyboard-driven and scriptable workflows (bulk file import, cron-style syncing, quick status updates, running the agent from a terminal), not as a copy of every screen.

| Command | Maps to (API → service) | Feature |
|---|---|---|
| `careerpilot login --api https://…` | stores a personal API token in the OS keyring | — |
| `careerpilot profile show` | `ProfileAPI` → `CandidateService.getProfile()` | F01 |
| `careerpilot goal set --type coop --term "Summer 2027" --roles backend,devops` | `ProfileAPI.updateGoal()` → `CandidateService.updateCareerGoal()` | F03 |
| `careerpilot jobs import postings.json --source manual` | `JobImportAPI.importManual()` / `createBatch()` | F05, F06 |
| `careerpilot jobs list --min-score 70 --deadline-before 2026-10-31` | `JobAPI.listJobs()` | F08 |
| `careerpilot jobs analyze <jobId>` | `JobAPI.analyzeJob()` → F07 + F08 | F07, F08 |
| `careerpilot jobs triage <batchId>` | `AgentAPI.startRun()` with goal `TRIAGE_BATCH` | F09 |
| `careerpilot application list --state APPLIED` | `ApplicationAPI.listApplications()` | F13 |
| `careerpilot application update <appId> submit` | `ApplicationAPI.transition()` | F13 |
| `careerpilot package generate <appId> --out ./packages` | `DocumentAPI.requestPackage()`, then polls and downloads the ZIP | F10–F12 |
| `careerpilot email sync` / `careerpilot email import ./mails/*.eml` | `EmailAPI` → `EmailSyncService.syncAccount()` / `importEmlFiles()` | F14, F15 |
| `careerpilot interview prepare <appId>` | `InterviewAPI.preparePlan()` | F17 |
| `careerpilot agent run "Prepare my Acme application"` | `AgentAPI.startRun()`; approval prompts `[y/N]` → `approveStep()` | F16 |
| `careerpilot analytics [--accept <adjustmentId>]` | `AnalyticsAPI.getInsights()` / `acceptAdjustment()` | F19 |

Example session:

```text
$ careerpilot agent run "Prepare my application for the Acme backend co-op"
Plan (6 steps, source: LLM): SearchJobs → RetrieveContext → AnalyzeJob → MatchJob → BuildPackage* → UpdateApplicationStatus*
 ✔ 1 SearchJobs          1 posting found (Acme · Backend Developer Co-op)
 ✔ 4 MatchJob            score 82 (co-op strategy) · missing: AWS
 ? 5 BuildPackage        Generate resume + cover letter and ZIP (~6k tokens). Approve? [y/N] y
 ✔ 5 BuildPackage        package ready (1 bullet flagged for review)
 ? 6 UpdateApplicationStatus markPrepared. Approve? [y/N] y
Run complete. Download: careerpilot package download 7f3c… --out .
```

Output formats: human tables by default, `--json` for scripting. Exit codes are non-zero on errors (401 → "run careerpilot login").

