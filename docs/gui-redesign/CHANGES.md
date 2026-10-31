# GUI redesign: changes

Branch `gui-redesign`. GUI module only (`careerpilot-gui`); core, CLI, server, extension and `.env` were not touched.
Direction and token values: [DIRECTION.md](DIRECTION.md). Problems found before the work: [AUDIT.md](AUDIT.md).

## Before and after

| Page | Before | After |
| --- | --- | --- |
| Login | none (not in the old render test) | [light](after/login.png) · [dark](after/login-dark.png) |
| Dashboard | [light](before/dashboard.png) · [dark](before/dashboard-dark.png) | [light](after/dashboard.png) · [dark](after/dashboard-dark.png) |
| Jobs | [light](before/jobs.png) · [dark](before/jobs-dark.png) | [light](after/jobs.png) · [dark](after/jobs-dark.png) |
| Tracker | [light](before/tracker.png) · [dark](before/tracker-dark.png) | [light](after/tracker.png) · [dark](after/tracker-dark.png) |
| Profile | [light](before/profile.png) · [dark](before/profile-dark.png) | [light](after/profile.png) · [dark](after/profile-dark.png) |
| Interview | [light](before/interview.png) · [dark](before/interview-dark.png) | [light](after/interview.png) · [dark](after/interview-dark.png) |
| Agent | [light](before/agent.png) · [dark](before/agent-dark.png) | [light](after/agent.png) · [dark](after/agent-dark.png) |
| Email | [light](before/email.png) · [dark](before/email-dark.png) | [light](after/email.png) · [dark](after/email-dark.png) |
| Insights | [light](before/insights.png) · [dark](before/insights-dark.png) | [light](after/insights.png) · [dark](after/insights-dark.png) |
| Settings | [light](before/settings.png) · [dark](before/settings-dark.png) | [light](after/settings.png) · [dark](after/settings-dark.png) |

What changed per page:

- **Shell.** Flat sidebar with a hard-coded navy and a bottom theme toggle became grouped sections (Overview, Pipeline, Prepare, System), a clear active state, arrow-key movement, collapse to icons, and a top bar per page (title, one-line context, actions, progress bar). Added a menu bar (system menu bar on macOS) with Go and View menus, Cmd/Ctrl+1 to 9 navigation, and a command palette (Cmd/Ctrl+K).
- **Login.** Split layout with a brand panel, Sign in and Create account as two paths, inline validation (email, 8+ characters, name), error notice with an icon.
- **Dashboard.** Five equal cards and six identical yellow banners became context tiles (each a button), a "Needs you" queue of keyboard-reachable rows ordered by urgency, and a shortlist of rich rows with the bearing ring. Empty state: "Clear skies".
- **Jobs.** Score is now a bearing ring in the list and in a large form in the detail; two-line role cells; no clipped columns; detail pane has its actions on top so they never cover the description; search, import, score-all and paste live in the top bar.
- **Tracker.** One flat table became a pipeline board (Saved, Prepared, Applied, Interview, Offer, Closed) with a search field, and below it the next-step buttons, timeline, documents, interviews and notes of the selected application. "submit" is labelled "Mark as submitted".
- **Profile.** Two grouped cards with labels above fields, a goal card, a trust notice about verified facts, a facts table with an empty state.
- **Interview.** Plan and mock interview as two panels, the current question in a card with a label.
- **Agent.** A run waiting for approval now opens by itself with an amber approval card (Approve / Reject, or the agent's question with option buttons); status as a chip with a glyph; plan table; memories collapsed.
- **Email.** Six crowded columns became five readable ones; confidence as a percentage; the confirm form is a labelled card with a sentence saying what Confirm does.
- **Insights.** The text-area dump became stat tiles, a narrative card, horizontal bar charts (response and interview rate by score band, source, role category, values written on the bars) and a pipeline-now chart, with an honest empty state.
- **Settings.** Grouped cards, a System / Light / Dark control, a real switch for automatic triage, password validation, toasts for confirmation.

## Token system

`tokens.css` (three layers) and `components.css` (component tokens plus selectors, no hex values):

1. Primitives: `-cp-ink-*`, `-cp-night-*`, `-cp-signal-*`, green/amber/red ramps.
2. Semantic: `-cp-bg-*`, `-cp-fg-*`, `-cp-accent-*`, `-cp-border-*`, `-cp-success/warning/danger-*`, `-cp-focus-ring`. Light on `.root`, dark on `.root.cp-dark`.
3. AtlantaFX mapping: the semantic tokens are assigned to Primer's `-color-*` names (including the `base` and `accent` ramps), so built-in controls (tables, combo boxes, switches, dialogs) follow both themes.

`Theme.stylesheets()` is the one list of stylesheets used by the app, dialogs and the screenshot test; `Theme.attach(root)` adds the `cp-dark` class. This replaced `.root:dark`, which never matched anything in JavaFX and was the root cause of the broken dark mode.

## Accessibility pass (Phase 4)

Done in code and checked against the screenshots:

- Contrast: every text/background pair in both themes is listed with its measured ratio in DIRECTION.md (all text pairs at least 4.5:1; input boundaries 3:1 or better via `border-control`; ring colours at least 3.6:1).
- Colour never alone: chips carry a glyph and a word; the score ring has a number and a word ("Strong fit"); outdated scores have a dashed track and a chip; the queue dot is paired with a hint sentence; chart bars carry written values.
- Keyboard: every clickable thing that was a `Label` or a `Region` is now a `Button` (queue rows, tiles, board cards); arrow keys move through the sidebar; Cmd/Ctrl+1 to 9, +K and +\ work from anywhere; Esc closes the palette.
- Focus: one focus ring token on buttons, inputs, combo boxes, check boxes, switch, nav items, table and list rows.
- Targets and text: minimum control height 28; smallest text is 12; weights are regular or bold only.
- Motion: page change 140 ms, sidebar 160 ms; both skipped when macOS Reduce Motion (or `-Dcareerpilot.reduceMotion=true`) is set.
- Feedback: errors are toasts that stay until dismissed; confirmations pause on hover and focus and have a close button; no modal alert for ordinary failures.
- Labels for assistive technology on icon-only controls and on the ring, tiles and rows.

## Trade-offs and decisions

- **Appearance follows the OS** (macOS and Windows are read at launch and when the window regains focus) with a manual override in Settings. JavaFX 21 cannot observe the OS setting, so a change is picked up on the next focus, not instantly. The old sidebar toggle was removed (HIG dark-mode.md).
- **System font stack** `"SF Pro Text", "Segoe UI", "System"`, no bundled fonts. JavaFX only does regular and bold, so there is no semibold.
- **No `javafx.scene.chart`**: charts are plain nodes so they pick up the tokens and have a text equivalent. Pipeline is drawn as bars, not a funnel, because applications leave the pipeline at any stage.
- **Baseline commit.** The GUI module was uncommitted when this started, so `b01f02d` snapshots it before the redesign. Core and CLI work in progress was left unstaged.
- **Dialogs** (paste a posting, add a fact, import report) still use JavaFX `Dialog`, now themed, instead of in-window modals.

## Not done or not verified

- The Insights charts were not seen with real outcome data (the seeded data has none); the code path compiles and the empty state was checked.
- Login validation, the command palette, sidebar collapse and the menu bar were built and run in the render test but not exercised interactively.
- No screen-reader run (VoiceOver) and no Windows or Linux visual check.
- Cards and board cards use JavaFX buttons with a graphic, which can size oddly if long text is added; ellipsis is set but very long company names were not tried.
- Profile and Interview keep their original data flow and are the least changed pages; Interview plan text is still a plain text area.

## Logs

`logs/gui-redesign/` (git-ignored): `render-baseline*.txt`, `render-phase1.txt`, `render-phase2*.txt`, per-page render logs, `render-final.txt`, `full-build.txt`.
