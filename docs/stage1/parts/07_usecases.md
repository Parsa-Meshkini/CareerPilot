
---

# 7. Use-Case Descriptions

Each major use case follows the structure required by the Stage 1 instructions. Numbering in *Alternative / Exception Flows* refers to the step of the main scenario where the deviation occurs (`*a` = may occur at any step).


### UC01 — Manage Candidate Profile

| Field | Description |
|---|---|
| **Use Case ID** | UC01 |
| **Name** | Manage Candidate Profile |
| **Actor(s)** | Student (primary) |
| **Goal** | Keep an accurate, verified record of education, experience, projects, skills and certifications. |
| **Preconditions** | Student is authenticated. |
| **Trigger** | Student opens Profile and edits a section. |
| **Main Success Scenario** | 1. Student opens the Profile page; system shows facts grouped by type with verification badges.<br>2. Student adds or edits a fact and saves.<br>3. System validates required fields and date order.<br>4. System stores the fact (manual facts are verified on save) and updates profile completeness. |
| **Alternative / Exception Flows** | 3a. Validation fails: system highlights fields; nothing is saved.<br>2a. Student deletes a fact cited by existing documents: system warns and marks those documents stale.<br>*a. Fact id belongs to another user: 404 (tenant isolation). |
| **Postconditions** | Profile updated; verified-fact set changed; dependent matches marked outdated. |
| **Related Feature(s)** | F01 |

### UC02 — Import Resume & Verify Extracted Facts

| Field | Description |
|---|---|
| **Use Case ID** | UC02 |
| **Name** | Import Resume & Verify Extracted Facts |
| **Actor(s)** | Student; LLM Provider |
| **Goal** | Populate the profile quickly from an existing resume without letting unverified information into generated documents. |
| **Preconditions** | Student is authenticated. |
| **Trigger** | Student uploads a resume file. |
| **Main Success Scenario** | 1. Student selects a PDF/DOCX and uploads it.<br>2. System validates type and size, stores the file and queues parsing (202).<br>3. Worker extracts text and asks the LLM for structured facts.<br>4. System stores proposed facts as unverified and notifies the student.<br>5. Student reviews each proposed fact (accept / edit / reject).<br>6. System marks accepted facts verified. |
| **Alternative / Exception Flows** | 2a. Invalid file: 400, nothing stored.<br>3a. No extractable text: status FAILED; student is asked to enter facts manually (UC01).<br>3b. LLM unavailable: status FAILED_RETRYABLE; student may retry.<br>5a. Student leaves facts unreviewed: they remain unverified and unusable by generators. |
| **Postconditions** | Verified facts added to the profile; the file is kept in private storage (deletable). |
| **Related Feature(s)** | F02, F01 |

### UC03 — Configure Career Goals

| Field | Description |
|---|---|
| **Use Case ID** | UC03 |
| **Name** | Configure Career Goals |
| **Actor(s)** | Student |
| **Goal** | Tell CareerPilot what is being sought so scoring and triage use the right strategy. |
| **Preconditions** | Student is authenticated. |
| **Trigger** | Student edits Career goals. |
| **Main Success Scenario** | 1. Student chooses goal type, target roles, locations, work term, work model and minimum match score.<br>2. System validates the goal (e.g., CO-OP requires a work term).<br>3. System selects the matching strategy for the goal type and saves the goal.<br>4. System marks existing matches outdated. |
| **Alternative / Exception Flows** | 2a. Invalid combination: 400 with explanation.<br>1a. Goal type changed: system asks for confirmation and resets strategy weights to defaults. |
| **Postconditions** | Active CareerGoal and strategy stored. |
| **Related Feature(s)** | F03 |

### UC04 — Import Jobs via Browser Extension

| Field | Description |
|---|---|
| **Use Case ID** | UC04 |
| **Name** | Import Jobs via Browser Extension |
| **Actor(s)** | Student; Job Portal (Experience York) |
| **Goal** | Bring many postings from the portal page the student is viewing into CareerPilot in one action. |
| **Preconditions** | Student is signed in to CareerPilot in the extension and is legitimately viewing a supported portal page. |
| **Trigger** | Student clicks the CareerPilot extension icon, then Import. |
| **Main Success Scenario** | 1. Extension detects that the page is supported and extracts visible postings.<br>2. Popup shows the number of postings detected.<br>3. Student clicks Import.<br>4. Extension sends the batch to the API; system validates it through the source adapter and queues processing (202).<br>5. Worker normalizes and deduplicates each record (include UC06).<br>6. System shows batch progress and final counts; an import-completed event is published. |
| **Alternative / Exception Flows** | 1a. Unsupported page: popup offers manual paste (UC05).<br>4a. Token expired: 401; popup asks the student to sign in.<br>1b. Page layout not recognized: 0 postings; popup reports it with the extractor version.<br>5a. Some records fail validation: batch completes as PARTIAL_FAILED with reasons. |
| **Postconditions** | New or merged JobPostings stored; ImportBatch complete; triage may start (UC08). |
| **Related Feature(s)** | F04, F06 |

### UC05 — Import Job Manually

| Field | Description |
|---|---|
| **Use Case ID** | UC05 |
| **Name** | Import Job Manually |
| **Actor(s)** | Student; LLM Provider |
| **Goal** | Add a posting from any source (incl. LinkedIn) by pasting its text or uploading a file. |
| **Preconditions** | Student is authenticated. |
| **Trigger** | Student selects Add job → Paste text (or CLI jobs import file). |
| **Main Success Scenario** | 1. Student pastes the posting text and selects its source.<br>2. System asks the LLM to structure the text into posting fields.<br>3. System shows an editable preview; student confirms.<br>4. System normalizes and deduplicates the posting (include UC06) and stores it. |
| **Alternative / Exception Flows** | 2a. LLM unavailable: raw text kept as description; student fills fields manually.<br>4a. Duplicate found: system shows the existing posting. |
| **Postconditions** | JobPosting stored (FULL completeness). |
| **Related Feature(s)** | F05, F06 |

### UC06 — Normalize & Deduplicate Jobs

| Field | Description |
|---|---|
| **Use Case ID** | UC06 |
| **Name** | Normalize & Deduplicate Jobs |
| **Actor(s)** | (included by UC04, UC05) |
| **Goal** | Store every posting once, in canonical form. |
| **Preconditions** | A RawJobRecord has passed adapter validation. |
| **Trigger** | Included step of an import. |
| **Main Success Scenario** | 1. System normalizes text, dates, location and work model and computes a fingerprint.<br>2. System looks for an existing posting by source + external id, then by fingerprint.<br>3. If found, system merges newer/fuller fields into it; otherwise it saves a new posting.<br>4. System records the result in the ImportBatch. |
| **Alternative / Exception Flows** | 1a. Unparseable deadline: stored as text with deadline null and a warning.<br>3a. Normalization error: record counted as FAILED with reason. |
| **Postconditions** | No duplicate postings for the student; batch counts accurate. |
| **Related Feature(s)** | F06 |

### UC07 — Analyze & Match Job

| Field | Description |
|---|---|
| **Use Case ID** | UC07 |
| **Name** | Analyze & Match Job |
| **Actor(s)** | Student; LLM Provider |
| **Goal** | Understand a posting and how well the student fits it, including missing skills. |
| **Preconditions** | Posting exists; profile has at least one verified fact. |
| **Trigger** | Student clicks Analyze on a posting (or UC08 / UC16 includes it). |
| **Main Success Scenario** | 1. System queues analysis (202).<br>2. Worker asks the LLM for a structured analysis and stores it.<br>3. System builds the candidate context from verified facts and preferences.<br>4. System selects the goal's matching strategy and computes the score and breakdown.<br>5. System asks the LLM for an explanation and learning suggestions for missing skills and stores the match.<br>6. Job detail page shows analysis, score, breakdown and gaps. |
| **Alternative / Exception Flows** | 2a. Posting is summary-only: analysis marked partial; student prompted to import full detail.<br>2b. Provider fails after retries: keyword fallback analysis, labelled.<br>3a. No verified facts: student is asked to complete the profile.<br>5a. Explanation fails: score shown without narrative. |
| **Postconditions** | JobAnalysis and JobMatch stored. |
| **Related Feature(s)** | F07, F08 |

### UC08 — Triage Imported Batch

| Field | Description |
|---|---|
| **Use Case ID** | UC08 |
| **Name** | Triage Imported Batch |
| **Actor(s)** | Student; LLM Provider |
| **Goal** | Get a prioritized shortlist from a large import without analyzing everything expensively. |
| **Preconditions** | A batch has completed; auto-triage enabled (or started manually). |
| **Trigger** | JobsImportedEvent, or student clicks Triage. |
| **Main Success Scenario** | 1. System starts an agent run with goal TRIAGE_BATCH and retrieves context and memories.<br>2. Agent creates and validates a plan (or uses the playbook).<br>3. Agent quick-scores all postings (include UC07, quick mode).<br>4. Agent decides which postings merit deep analysis within the budget and analyzes/matches them.<br>5. Agent ranks postings, marks the shortlist and records an episodic memory.<br>6. System notifies the student with the shortlist and reasoning trace. |
| **Alternative / Exception Flows** | 4a. Most postings summary-only: agent replans and ranks on quick scores.<br>*a. Budget exhausted: run ends with a labelled partial shortlist.<br>2a. LLM plan invalid twice: playbook plan used. |
| **Postconditions** | Shortlist and AgentRun trace stored. |
| **Related Feature(s)** | F09 |

### UC09 — Generate Tailored Resume & Cover Letter

| Field | Description |
|---|---|
| **Use Case ID** | UC09 |
| **Name** | Generate Tailored Resume & Cover Letter |
| **Actor(s)** | Student; LLM Provider |
| **Goal** | Obtain truthful, job-specific application documents. |
| **Preconditions** | Application exists for a posting; profile has verified facts. |
| **Trigger** | Student clicks Generate resume / cover letter (or UC10 includes it). |
| **Main Success Scenario** | 1. System builds a grounded context of relevant verified facts.<br>2. System asks the LLM for structured content in which every bullet cites fact ids.<br>3. Fabrication guard verifies ids and claims.<br>4. System renders DOCX and PDF and stores a new document version.<br>5. Student reviews and optionally edits the result. |
| **Alternative / Exception Flows** | 3a. Violations: regenerate once with feedback; if still failing, remove offending content and mark NEEDS_REVIEW.<br>1a. Too few verified facts: generation refused with guidance.<br>4a. PDF rendering fails: DOCX delivered; PDF retried. |
| **Postconditions** | GeneratedDocuments stored with verification status. |
| **Related Feature(s)** | F10, F11 |

### UC10 — Build Application Package

| Field | Description |
|---|---|
| **Use Case ID** | UC10 |
| **Name** | Build Application Package |
| **Actor(s)** | Student |
| **Goal** | Download a complete application folder ready for manual submission. |
| **Preconditions** | Application exists. |
| **Trigger** | Student clicks Generate package. |
| **Main Success Scenario** | 1. System queues package building (202).<br>2. Worker generates or reuses resume and cover letter (include UC09).<br>3. Worker builds job description PDF, checklist and info file and zips them.<br>4. System stores the ZIP and moves the application to PREPARED if it was SAVED.<br>5. Student downloads the ZIP via a short-lived signed link and submits the application on the employer's site themselves. |
| **Alternative / Exception Flows** | 2a. A document is NEEDS_REVIEW: package built; checklist starts with a review item.<br>4a. Application already APPLIED: no state change. |
| **Postconditions** | ApplicationPackage stored; application PREPARED (if applicable). |
| **Related Feature(s)** | F12, F10, F11 |

### UC11 — Track Application Status

| Field | Description |
|---|---|
| **Use Case ID** | UC11 |
| **Name** | Track Application Status |
| **Actor(s)** | Student |
| **Goal** | Keep each application's status and history accurate, with the ability to correct mistakes. |
| **Preconditions** | Application exists. |
| **Trigger** | Student chooses an action on the tracker, or UC13 / UC16 triggers one. |
| **Main Success Scenario** | 1. System shows the application with its allowed actions.<br>2. Student chooses an action (e.g., mark submitted).<br>3. System asks the current state to perform the transition.<br>4. System stores the new state and a StatusChange with its source and publishes an event.<br>5. Tracker and timeline update. |
| **Alternative / Exception Flows** | 3a. Action not allowed in this state: 409 with allowed actions.<br>5a. Student undoes the latest change: system restores the previous state and records the reversal.<br>Extension point review uncertain event: UC14. |
| **Postconditions** | Application state and timeline updated. |
| **Related Feature(s)** | F13 |

### UC12 — Connect Email Account

| Field | Description |
|---|---|
| **Use Case ID** | UC12 |
| **Name** | Connect Email Account |
| **Actor(s)** | Student; Email Provider (Gmail) |
| **Goal** | Allow CareerPilot to read application-related email with least privilege. |
| **Preconditions** | Student is authenticated. |
| **Trigger** | Student clicks Connect Gmail (or Upload .eml). |
| **Main Success Scenario** | 1. System generates an authorization URL (read-only scope, state, PKCE).<br>2. Student reviews and grants consent on Google's page.<br>3. Google redirects back with an authorization code.<br>4. System exchanges the code for tokens, encrypts the refresh token and stores the account. |
| **Alternative / Exception Flows** | 2a. Student denies consent / state mismatch: nothing stored.<br>1a. Student uploads .eml files instead: files are processed by UC13 without OAuth.<br>*a. Student disconnects later: token revoked and deleted. |
| **Postconditions** | Active EmailAccount with encrypted token. |
| **Related Feature(s)** | F14 |

### UC13 — Process Application Emails

| Field | Description |
|---|---|
| **Use Case ID** | UC13 |
| **Name** | Process Application Emails |
| **Actor(s)** | Scheduler (initiator); Email Provider; LLM Provider |
| **Goal** | Detect application events in new emails and apply confident ones automatically. |
| **Preconditions** | An active email account or uploaded .eml files exist. |
| **Trigger** | Scheduled sync (every 15 min) or manual sync. |
| **Main Success Scenario** | 1. System fetches new messages matching a narrow job-related query.<br>2. System normalizes each message into a short excerpt.<br>3. System classifies the message with rules, falling back to the LLM when unsure.<br>4. System matches the event to an application.<br>5. If both confidences ≥ 0.85, system publishes the event; observers update state, create interview records and notify (include UC15).<br>6. Otherwise system queues the event for review and notifies the student. |
| **Alternative / Exception Flows** | 1a. Token expired or revoked: account set to REAUTH_REQUIRED; student notified.<br>3a. Not relevant: excerpt deleted.<br>5a. Transition illegal for current state: no change; review notification. |
| **Postconditions** | DetectedEmailEvents stored; confident events applied; others in review queue. |
| **Related Feature(s)** | F14, F15 |

### UC14 — Review Detected Event

| Field | Description |
|---|---|
| **Use Case ID** | UC14 |
| **Name** | Review Detected Event |
| **Actor(s)** | Student |
| **Goal** | Resolve uncertain email events safely. |
| **Preconditions** | A DetectedEmailEvent is NEEDS_REVIEW. |
| **Trigger** | Student opens the review queue (extends UC11 at 'review uncertain event'). |
| **Main Success Scenario** | 1. System shows the email excerpt, proposed event type, extracted details and candidate applications.<br>2. Student confirms and selects the application (or corrects the type).<br>3. System marks the event confirmed and publishes it; the normal observers apply it. |
| **Alternative / Exception Flows** | 2a. Student dismisses the event: marked DISMISSED; no change. |
| **Postconditions** | Event confirmed or dismissed; application updated if confirmed. |
| **Related Feature(s)** | F14, F15 |

### UC15 — Send Reminders & Notifications

| Field | Description |
|---|---|
| **Use Case ID** | UC15 |
| **Name** | Send Reminders & Notifications |
| **Actor(s)** | Scheduler; Student (recipient) |
| **Goal** | Make sure the student sees deadlines and important changes in time. |
| **Preconditions** | Open applications or domain events exist. |
| **Trigger** | Daily deadline scan; any domain event. |
| **Main Success Scenario** | 1. System finds SAVED/PREPARED applications with deadlines within 3 days (or receives a domain event).<br>2. System publishes the corresponding event.<br>3. NotificationService creates notifications shown in the GUI. |
| **Alternative / Exception Flows** | 1a. Student muted a notification type: stored but not surfaced. |
| **Postconditions** | Notifications created. |
| **Related Feature(s)** | F15 |

### UC16 — Run Agent Goal

| Field | Description |
|---|---|
| **Use Case ID** | UC16 |
| **Name** | Run Agent Goal |
| **Actor(s)** | Student; LLM Provider |
| **Goal** | Delegate a multi-step preparation task to the agent while keeping control. |
| **Preconditions** | Student is authenticated. |
| **Trigger** | Student submits a goal in the Agent Workspace or CLI. |
| **Main Success Scenario** | 1. System starts an agent run and recalls relevant memories and context.<br>2. Agent produces a validated plan and shows it.<br>3. For each step, the agent creates a command; side-effecting commands wait for approval.<br>4. Agent executes the command, observes the result and decides to continue, replan, ask or finish.<br>5. Agent records a summary in memory and completes the run; the student can undo reversible actions. |
| **Alternative / Exception Flows** | 3a. Student rejects a step: agent replans or finishes.<br>4a. Ambiguous result: agent asks the student a question.<br>*a. Budget or step limit: partial result with explanation.<br>1a. Goal requests a forbidden action (e.g., submit): plan excludes it and the run explains why. |
| **Postconditions** | AgentRun and action log stored; approved actions applied. |
| **Related Feature(s)** | F16 |

### UC17 — Prepare for Interview

| Field | Description |
|---|---|
| **Use Case ID** | UC17 |
| **Name** | Prepare for Interview |
| **Actor(s)** | Student; LLM Provider |
| **Goal** | Practise the right things for a specific interview. |
| **Preconditions** | Application has a job analysis (or one is created). |
| **Trigger** | Student opens Interview prep, or an interview invitation is detected. |
| **Main Success Scenario** | 1. System builds grounded context including past mock-interview feedback.<br>2. System asks the LLM for likely questions, STAR stories citing facts and a checklist.<br>3. Fabrication guard verifies STAR stories.<br>4. System stores and displays the plan.<br>5. Extension point practice: UC18. |
| **Alternative / Exception Flows** | 3a. Unsupported story: removed with a suggestion to enrich the profile.<br>2a. LLM fails: deterministic generic question bank for the role category. |
| **Postconditions** | InterviewPrepPlan stored. |
| **Related Feature(s)** | F17 |

### UC18 — Practice Mock Interview

| Field | Description |
|---|---|
| **Use Case ID** | UC18 |
| **Name** | Practice Mock Interview |
| **Actor(s)** | Student; LLM Provider |
| **Goal** | Rehearse answers and get actionable feedback. |
| **Preconditions** | Application exists. |
| **Trigger** | Student starts a mock interview (extends UC17). |
| **Main Success Scenario** | 1. System starts a session and asks the first question.<br>2. Student answers; system scores the answer against the rubric and gives feedback and the next question.<br>3. Repeat until the session is finished (max 6 questions).<br>4. System produces a summary and stores recurring weaknesses as memory. |
| **Alternative / Exception Flows** | 2a. Very short answer: system asks the student to elaborate.<br>*a. Student leaves: session saved as incomplete and resumable. |
| **Postconditions** | MockInterviewSession stored; FEEDBACK memory updated. |
| **Related Feature(s)** | F18 |

### UC19 — Analyze Outcomes & Adapt Strategy

| Field | Description |
|---|---|
| **Use Case ID** | UC19 |
| **Name** | Analyze Outcomes & Adapt Strategy |
| **Actor(s)** | Student; LLM Provider |
| **Goal** | Learn from past applications and improve future targeting. |
| **Preconditions** | Student has applications with outcomes. |
| **Trigger** | Student opens Insights. |
| **Main Success Scenario** | 1. System computes outcome statistics from the student's own data.<br>2. If data is sufficient, system proposes bounded, evidence-cited strategy adjustments.<br>3. Student accepts or rejects each proposal.<br>4. On acceptance, system updates strategy weights and records a STRATEGY memory used by future matching and triage. |
| **Alternative / Exception Flows** | 2a. Insufficient data (< 10 outcomes): statistics only, no proposals.<br>2b. Proposal lacks evidence or exceeds bounds: discarded before display.<br>3a. Student reverts a previously accepted adjustment: weights restored. |
| **Postconditions** | InsightReport shown; accepted adjustments applied. |
| **Related Feature(s)** | F19 |
