# CareerPilot: presentation video script (narration + screen actions)

One continuous recording of about 15 minutes. Each feature is a labelled segment so the report can link to a timestamp
(see "After recording" at the end). Speak the lines in quotes; the bullets are what to do on screen.
Every segment follows the four beats the course asks for: say the feature ID and name, show the input, show the
running application and its output, and show one error or alternative case. Where the AI is involved, say so.
The table in `demo-script.md` is the short checklist; this file is the full spoken version.

## Before you press record

1. Use a real model key so the AI features show real output: `cp .env.example .env`, set `LLM_API_KEY`, keep `.env` closed.
   Never `cat .env` or show an editor with the key. If the model is busy, set `LLM_PROVIDER=mock` in `.env` and say "this is the offline fallback".
2. Start from empty data: close the app, then `mv ~/.careerpilot ~/.careerpilot.backup` (move it back afterwards).
3. Build once: `mvn -q install -DskipTests`.
4. Two windows side by side: the GUI (`bin/careerpilot-gui`) and a terminal (font size 18 or larger, `cd` to the repo).
   For the extension segment: Chrome with the extension loaded from `extension/`, and `bin/careerpilot-server` running.
5. Have `samples/jobs.csv`, `samples/resume.txt` and `samples/emails/*.eml` ready in Finder. Turn on Do Not Disturb.
6. Record the whole screen at 1080p with your microphone (QuickTime: File > New Screen Recording).
7. Upload as Unlisted or Public on YouTube, or a Drive link set to "Anyone with the link". Open it in a private window to check.

Rehearse once. If you flub a line, pause two seconds and say it again; trim it afterwards.

---

## 0:00 Opening (about 50 s)

On screen: the GitHub repo page, then the GUI sign-in screen.

> "Hi, I'm Parsa Meshkini. This is CareerPilot, my EECS 3311 Stage 2 project: an AI career and application agent for students looking for co-op and internship jobs.
> It imports job postings, scores them against my goal, prepares tailored documents, tracks every application, reads job emails, and helps me prepare for interviews.
> One rule runs through the whole system: CareerPilot prepares and recommends, but it never submits anything for me, and every claim in a document is checked against facts I verified myself.
> It's written in Java 17 with Spring Boot, a JavaFX desktop GUI, a picocli command line, a small REST server for a Chrome extension, and an LLM facade that works with Gemini or any OpenAI-compatible model.
> All 19 features from my Stage 1 design are implemented. I'll demonstrate each one in the GUI and the CLI, and say where the AI is involved."

## 0:50 Setup and launch (about 50 s)

- Show `.env.example` (placeholders only) and say the real `.env` is git-ignored. Do not open `.env`.
- Terminal: `bin/careerpilot --help`, then `bin/careerpilot-gui`.

> "Setup is three steps from the README: copy `.env.example` to `.env`, put my key in locally, and build with Maven. The key is never committed; `.gitignore` covers `.env`.
> The command line lists every feature group, and the GUI is the same core running in-process."

On the sign-in screen: click **Create account**, enter a name, a `@my.yorku.ca` email and a password, submit. Show the empty **Dashboard**.

> "I create an account and land on the dashboard. It's empty because there is no data yet."

---

## 1:40 F01 Candidate profile and verified facts (about 40 s)

GUI: **Profile** > **Add fact**, choose Skill, name `python`, add. Then add an Experience with the organization left empty.

> "F01, candidate profile and verified facts. I add facts about myself, and only facts I've verified are ever used to generate documents.
> Here is the error case: an experience without an organization is rejected with a clear message, so bad data never reaches the AI. This feature is deterministic."

CLI: `bin/careerpilot profile fact-add SKILL name=django`, then `bin/careerpilot profile fact-add EXPERIENCE title=Intern organization=Initech start_date=2025-05 end_date=2025-08 bullets="Built a REST API with Docker"`.

## 2:20 F02 Resume import and AI fact extraction (about 40 s)

GUI: **Profile** > **Import resume**, choose `samples/resume.txt`. Show the extracted facts as pending, select some and click **Verify selected**.

> "F02, resume import with AI fact extraction. The AI reads my resume and proposes facts, but they are saved as unverified. Nothing is trusted until I verify it, so a wrong guess can't leak into a document. This feature is hybrid: reading the file is deterministic, extracting the facts is AI."

CLI: `bin/careerpilot profile resume samples/resume.txt`, then `bin/careerpilot profile fact-list --pending`.
Error case: `bin/careerpilot profile resume nonexistent.pdf` and read the message aloud.

## 3:00 F03 Career goal and strategy configuration (about 30 s)

GUI: **Profile** > goal section: type Co-op, term `Summer 2027`, roles `backend`, **Save goal**.

> "F03, career goal. The goal I pick selects the scoring strategy: co-op, entry-level or career switch. That's the Strategy design pattern, and it changes how every job is scored."

CLI: `bin/careerpilot profile goal-set --type COOP --term "Summer 2027" --roles backend`, then `bin/careerpilot profile goal-show`.

## 3:30 F04 Browser-extension batch job import (about 60 s)

Chrome: open `samples/pages/york-list.html` from disk, open the extension popup, sign in with the same account, click **Import**.

> "F04, batch import from the browser. The extension reads only the page I have open, and only when I click Import. It sends the postings to the local REST server with my token, and they appear in the GUI.
> This is deterministic and uses the Adapter pattern: each source has an adapter that converts its format into one posting shape."

Show the postings in **Jobs**. Error case: sign out in the popup, click Import, and read the "not signed in" message.
CLI alternative: `bin/careerpilot jobs import-york samples/york_payload.json`.

## 4:30 F05 Manual job import: paste or file (about 30 s)

GUI: **Jobs** > **Import CSV/JSON**, choose `samples/jobs.csv`. Then **Paste a posting** with a short description.

> "F05, manual import. I can import a CSV or JSON file, or paste a single posting; for pasted text the AI extracts the title and company. The CSV has one bad row with no title, and the importer reports it and keeps the others."

CLI: `bin/careerpilot jobs import-file samples/jobs.csv` (point at "record 4 skipped: missing title"). Error case: `bin/careerpilot jobs import-paste --file samples/resume.txt` shows "could not determine title and company".

## 5:00 F06 Normalization and duplicate detection (about 20 s)

CLI: run `bin/careerpilot jobs import-file samples/jobs.csv` a second time.

> "F06, normalization and duplicates. Importing the same file again saves nothing new: all four postings are recognised as duplicates and counted, not saved twice."

## 5:20 F07 AI job description analysis (about 35 s)

GUI: **Jobs**, select Backend Developer Co-op, click **Analyze with AI**. Show required skills, nice-to-haves and the "Analysed by" model line.

> "F07, AI analysis. The model reads the posting and returns structured skills, validated against a schema before I see them. If the model is down, a keyword analysis takes over and is clearly labelled as the fallback."

CLI: `bin/careerpilot jobs list`, then `bin/careerpilot jobs analyze <job id>`.

## 5:55 F08 Matching and skill-gap analysis (about 35 s)

GUI: point at the score ring and the "You have" and "Gaps to close" chips.

> "F08, matching. The score is calculated deterministically by the strategy for my goal. The AI only writes the explanation and a hint for each gap; it never decides the score. If I change the goal, the scores update."

CLI: `bin/careerpilot jobs match <job id>` and `bin/careerpilot jobs score`.

## 6:30 F09 Agent batch triage and shortlist (about 40 s)

GUI: open **Agent** and select the "Triage" run created by the import, then show the shortlist on the **Dashboard**.

> "F09, batch triage. When an import finishes, the agent automatically quick-scores the whole batch, picks the best candidates for a deeper look within a token budget, and builds my shortlist. It works through tools using the Command pattern, and I can read its reasoning."

CLI: `bin/careerpilot agent runs`, then `bin/careerpilot agent show <run id>`.

## 7:10 F10 Tailored resume and F11 Tailored cover letter (about 60 s)

GUI: **Jobs** > select Backend Developer Co-op > **Track application**; open **Tracker**, select it, click **Generate resume**, then **Generate cover letter**.

> "F10 and F11, tailored resume and cover letter. The model drafts them from my verified facts. Then the fabrication guard checks every claim against those facts and removes anything I haven't verified. Both documents share one template, the Template Method pattern, whose generate step is final so the guard can never be skipped. These are hybrid features."

Show the PASSED status. Error case, on a fresh account before any facts exist: `bin/careerpilot docs resume <app id>` prints "add at least one verified experience or project before generating".
CLI: `bin/careerpilot docs resume <app id>` and `bin/careerpilot docs cover-letter <app id>`.

## 8:10 F12 Application package and ZIP export (about 30 s)

GUI: **Build package (ZIP)**, then open the ZIP to show resume, cover letter, job description and checklist.

> "F12, the application package. One click assembles the resume, cover letter, job description and a checklist into a ZIP. This is deterministic. And it reminds me that I submit it; CareerPilot never does."

CLI: `bin/careerpilot docs package <app id> --out acme.zip`.

## 8:40 F13 Application lifecycle tracking and undo (about 45 s)

GUI: **Tracker** > **Mark prepared**, **Mark as submitted**; show the card moving across lanes and the timeline. Click **Undo last change**.

> "F13, lifecycle tracking. Each application moves through states, and each state object decides which actions are legal; that's the State pattern. Every change is recorded, and the latest one can be undone."

CLI: `bin/careerpilot apps act <app id> submit`, then the illegal `bin/careerpilot apps act <app id> accept` (read the "allowed" list), then `bin/careerpilot apps undo <app id>`.

## 9:25 F14 Email monitoring and event detection (about 60 s)

GUI: **Email** > **Sync demo inbox**, then **Upload .eml files** with `samples/emails/*.eml`. Show the detected events; **Confirm** one and **Dismiss** another. Point at **Connect Gmail**.

> "F14, email monitoring. Emails come in through adapters: a mock inbox, .eml files, or Gmail with read-only sign-in. A rules classifier runs first and the AI is only used when the rules are unsure; that's the Strategy pattern again. A confident match updates my application automatically. Anything uncertain waits here for me to confirm. Gmail uses my browser for consent, and CareerPilot never sees my password."

CLI: `bin/careerpilot email sync-mock`, then `bin/careerpilot email events`. Alternative case: point at the event that is "waiting for review".

## 10:25 F15 Event-driven updates, reminders and notifications (about 30 s)

GUI: **Dashboard**, the "Needs you" feed.

> "F15, event-driven updates. Imports, emails and status changes publish events; listeners update the application, record memory, schedule interviews and create notifications, without the publisher knowing about them. That's the Observer pattern. Deadline reminders come from the same system."

CLI: `bin/careerpilot notifications` and `bin/careerpilot notifications remind --days 30`.

## 10:55 F16 Goal-driven agent workspace (about 80 s)

GUI: **Agent**, type `Prepare my application for the Acme backend co-op`, click **Run**. Show the plan and the "Needs your approval" card, then **Approve**. Run a second goal and click **Reject**. Open "What I remember about you". Click **Undo** where offered.

> "F16, the agent workspace. I give it a goal in plain words. It plans the steps, runs them as tools, and after each step decides whether to continue, replan or stop. It never changes anything without my approval: here it stops before building the package. If I reject, it replans and remembers my preference for next time. This is planning, tool use, memory and multi-step behaviour, not a single prompt forwarded to a model."

CLI: `bin/careerpilot agent run "Prepare my application for the Acme backend co-op"`, `bin/careerpilot agent approve <run id>`, `bin/careerpilot agent memory`.

## 12:15 F17 Interview preparation plan (about 35 s)

GUI: **Interview**, select the application, click **Prepare plan**.

> "F17, interview preparation. The AI proposes likely questions and STAR stories built only from my verified facts; the fabrication guard rejects any story that uses something I haven't verified. If the model fails, a deterministic plan is used. It also triggers automatically when an application reaches the interview stage."

CLI: `bin/careerpilot interview prepare <app id>`, then `bin/careerpilot interview show <app id>`.

## 12:50 F18 AI mock interview and feedback (about 45 s)

GUI: **Interview** > **Start mock interview**, type an answer, **Submit answer**, then **Finish and get summary**.

> "F18, mock interview. The AI asks a question, scores my answer against a rubric, and picks the next question based on my weak areas. At the end I get a summary, and the feedback is stored in the agent's memory. A session can be resumed if the model fails midway."

CLI: `bin/careerpilot interview mock <app id>`, `bin/careerpilot interview answer <session id> "my answer"`, `bin/careerpilot interview finish <session id>`.

## 13:35 F19 Outcome analytics and adaptive strategy (about 35 s)

GUI: **Insights**; point at "What your data says" and the bars.

> "F19, outcome analytics. It measures which kinds of applications get responses and proposes small, bounded changes to my scoring weights, citing the evidence. It refuses to propose anything until there are enough outcomes, ten here, so it can't draw conclusions from noise. I accept, reject or revert each proposal; nothing changes on its own."

CLI: `bin/careerpilot analytics`, then `bin/careerpilot analytics history`.

---

## 14:10 Design patterns and architecture (about 45 s)

On screen: the report section 3 and one UML class diagram from `docs/stage2/uml/rendered/`.

> "The system uses seven design patterns, each solving a concrete problem: Facade for the LLM client and the agent, Adapter for job sources, mailboxes and model vendors, Strategy for scoring and email classification, Command for agent tools, State for the application lifecycle, Observer for events, and Template Method for document generation.
> The report maps every feature to its classes, methods and patterns, and explains each change from my Stage 1 design."

## 14:55 Closing (about 25 s)

On screen: the GitHub repo and its commit history.

> "To recap: all 19 features, a GUI and a CLI, an LLM with real agent behaviour, seven design patterns, API keys kept out of the repository, and more than five code commits in every reporting period. The code, the report and a README with setup instructions are all in the public repository. Thanks for watching."

---

## Quick reference: error cases to show

| Feature | Error / alternative |
|---|---|
| F01 | Experience without organization is rejected |
| F02 | Missing or unsupported file |
| F04 | Not signed in on the extension |
| F05 | CSV row without a title is skipped and reported |
| F06 | Second import: all duplicates |
| F07 | Model down: keyword fallback is labelled |
| F10, F11 | Documents refused until a verified experience or project exists |
| F13 | Illegal transition lists the allowed actions |
| F14 | Low-confidence email waits for review |
| F16 | Reject: the agent replans |
| F19 | Too little data: no proposals |

## After recording

1. Upload the video and copy the link.
2. Note the real timestamp where each feature's segment starts (the headings above are targets).
3. Edit `docs/stage2/video-links.json`:

   ```json
   {
     "F01": {"url": "https://www.youtube.com/watch?v=XXXX&t=100s", "time": "1:40"},
     "F02": {"url": "https://www.youtube.com/watch?v=XXXX&t=140s", "time": "2:20"}
   }
   ```

   One entry for each of F01 to F19; they can all share one video with different times.
4. Run `python tools/build_stage2_report.py --pdf`, then commit and push.
5. Open every link in a private window to confirm it plays without a sign-in or access request, and that the video never shows your key.
