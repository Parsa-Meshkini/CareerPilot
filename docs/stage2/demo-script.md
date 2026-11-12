# Demo recording script

The full spoken, timed narration is in [`video-narration-script.md`](video-narration-script.md). This file is the short per-feature checklist.

Record with the screen recorder of your choice (QuickTime: File > New Screen Recording). Hide `.env` and any terminal showing the key. Upload as unlisted or public videos, put each link and timestamp into `docs/stage2/video-links.json` (format: `{"F01": {"url": "https://...", "time": "0:42"}, ...}`; one entry per feature F01-F19, several features may share one URL with different times), then run `python tools/build_stage2_report.py --pdf`.

Before recording: `cp .env.example .env`, set a key (or `LLM_PROVIDER=mock` to show the offline fallbacks), `mvn -q -DskipTests install`, and use `samples/`.

Each segment: say the feature ID and name, show the input, run it, show the output, say where the AI is involved, show one error or alternative case.

| Feature | Show in GUI | Show in CLI | Error / alternative |
|---|---|---|---|
| F01 | Profile: add a fact, verify it | `careerpilot profile fact-add`, `fact-list --pending`, `fact-verify` | invalid fact JSON rejected |
| F02 | Profile > Import resume (`samples/`) | `careerpilot profile resume FILE` | unsupported file type |
| F03 | Profile > Goal | `careerpilot profile goal-set`, `goal-show` | missing goal blocks matching with a clear message |
| F04 | Chrome extension Import button, jobs appear | `careerpilot jobs import-york FILE` | not signed in |
| F05 | Jobs > Import (paste) | `careerpilot jobs import-paste`, `import-file samples/jobs.csv` | empty paste |
| F06 | Import the same file twice | same | duplicates counted, not saved |
| F07 | Job detail > Analyze with AI | `careerpilot jobs analyze ID` | model down: keyword fallback is labelled |
| F08 | Bearing ring, skill gaps | `careerpilot jobs match`, `score` | change the goal, scores change |
| F09 | Import a batch, watch the agent triage | `careerpilot agent runs`, `agent show` | |
| F10, F11 | Tracker > Documents | `careerpilot docs resume`, `docs cover-letter` | fabricated claim removed by the guard |
| F12 | Tracker > Build package | `careerpilot docs package`, `docs download` | |
| F13 | Tracker board: move states, undo | `careerpilot apps act`, `apps undo` | illegal transition refused |
| F14 | Email > Sync demo inbox, upload .eml, Connect Gmail (if configured) | `careerpilot email sync-mock`, `upload`, `events`, `confirm`, `connect-gmail` | low-confidence email waits for review |
| F15 | Dashboard feed after confirming an email | `careerpilot notifications`, `remind` | |
| F16 | Agent page: goal, approval card, reject, undo | `careerpilot agent run`, `approve`, `reject`, `undo`, `memory` | reject, agent replans |
| F17 | Interview page: prepare | `careerpilot interview prepare`, `show` | |
| F18 | Interview page: mock interview | `careerpilot interview mock`, `answer`, `finish` | model failure pauses, resume |
| F19 | Insights page: accept, reject, revert | `careerpilot analytics`, `accept`, `reject`, `revert` | too little data: no proposals |
