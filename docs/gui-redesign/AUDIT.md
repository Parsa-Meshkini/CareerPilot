# CareerPilot GUI audit (Phase 0)

Scope: `careerpilot-gui` at commit `b01f02d` (baseline), JavaFX 21 + AtlantaFX Primer, 1180×760 window.
Evidence: screenshots in `before/` (light and dark, one per page), `styles.css`, and every view class.
Platform lens: macOS desktop conventions, translated for JavaFX with `cross-platform.md` (menu bar → `MenuBar` with system menu, toolbar → page header bar, Dynamic Type → fixed pt sizes, SF Symbols → `Icons.java` SVG set).
Contrast figures are computed from the hex values in the CSS, not estimated.

HIG pages are cited as `file.md › Heading`; anything not covered by a page is marked *judgment*.

## Summary

The app is functional and consistent, but it reads as a stock theme: a dark slate sidebar, a sky-blue accent (`#0369a1`, which is also the default colour the `ui-ux-pro-max` generator returns for "SaaS dashboard"), flat bordered cards and default tables. It has no point of view beyond the name.

Underneath the style there are real defects, in this order of importance:

1. **Dark mode is broken** (Critical). `styles.css` uses `.root:dark`, which is not a pseudo-class that exists in JavaFX or AtlantaFX, so the "dark" overrides never apply and the light accent values leak into dark mode.
2. **Keyboard access is incomplete** (Critical). The most important thing on the dashboard, the "needs your attention" list, is made of clickable `Label`s that cannot take focus.
3. **Key information is hidden or clipped** (High). A run waiting for approval is invisible until you select it; table columns clip dates and chips at the default window size.
4. **Loading disables the whole page** (High) and every error is a modal alert (Medium).

Rating: **Needs work**.

## Cross-cutting findings

### Critical

- **C1. Dark mode leaks light-mode colours.**
  `styles.css:3-14` defines the accent and subtle colours on `.root` and then repeats them under `.root:dark`. JavaFX has no `:dark` pseudo-class on the root (verified: `primer-dark.css` and `primer-light.css` contain zero occurrences of `:dark`), so the second block never matches.
  Measured in `before/*-dark.png`:
  - `.stat-number` is `#0369a1` on the dark card `#161b22`: **2.92:1**, fails 4.5:1 (and 3:1 for 28 px bold only just misses).
  - `.table-row-cell:selected` uses `-color-accent-subtle`, which stays `#e0f2fe` in dark mode. The selected Jobs row is a pale bar holding `#c9d1d9` text: **1.35:1**, effectively unreadable (`jobs-dark.png`).
  - `.link-button` and the `info` chip use `-color-accent-fg` = `#0369a1` on `#0d1117`: **3.19:1**.
  - The sidebar is the same hard-coded navy in both modes, so "dark mode" only changes the content area and the sidebar never adapts.
  - Cite: `dark-mode.md › Dark Mode colors`: "Aim for sufficient color contrast in all appearances… no lower than 4.5:1"; "Embrace colors that adapt to the current appearance."
  - Fix: tokens defined once per theme on `.root.cp-light` / `.root.cp-dark`, with `Theme` adding the class to every scene root.

- **C2. Keyboard-only use is not possible on the Dashboard.**
  `DashboardView.banner()` builds a `Label` with `setOnMouseClicked`. Labels are not focus traversable, so the whole attention list (agent waiting, emails to review, deadlines, profile gaps) can be reached only with a pointer. `.stat-card` has `-fx-cursor: hand` but no handler at all, so it looks clickable and does nothing.
  Cite: `accessibility.md › Speech`: "Let people use the keyboard alone to navigate and interact with your app."; `focus-and-selection.md › Best practices`: "Rely on system-provided focus effects."
  Fix: real `Button`s with a `row` style class; stat cards either navigate (and are buttons) or are plain read-only tiles.

### High

- **H1. App-level appearance switch.** The only way to change appearance is a toggle at the bottom of the sidebar, and the app ignores the OS setting. Cite: `dark-mode.md › Best practices`: "Avoid offering an app-specific appearance setting… they may think your app is broken because it doesn't respond to their systemwide appearance choice." *Trade-off:* JavaFX 21 cannot subscribe to the OS appearance, so the fix is "follow the system at launch and on window focus (macOS `AppleInterfaceStyle`), keep a manual System / Light / Dark override in Settings", and remove the sidebar toggle.

- **H2. The sidebar is a flat list of nine items with no grouping, no collapse, no keyboard shortcuts and no menu bar.** `sidebars.md › Best practices`: "Consider letting people hide the sidebar"; "If you need to include two levels… use succinct, descriptive labels to title each group." `toolbars.md › Desktop (macOS)`: "Make every toolbar item available as a command in the menu bar." There is no menu bar at all. Fix: grouped sidebar (Overview / Pipeline / Prepare / System), icon-only collapse, `⌘1…9` navigation, a real `MenuBar` (system menu on macOS) with Go and View menus.

- **H3. Pages have no toolbar and the titles disagree with the navigation.** Sidebar says *Profile / Interview / Agent*; page titles say *Profile & goal / Interview prep / Agent workspace*. Each page renders its title as a 24 px bold label at the top of the content with its actions scattered below. `toolbars.md › Titles`: "Write a concise title… under 15 characters", "Provide a useful title for each window." Fix: one `PageHeader` per page (title, one-line context, trailing primary action, optional search).

- **H4. Whole-page disable while loading.** `Async.run(this, …)` calls `setDisable(true)` on the view, which greys the entire page on every refresh (Dashboard, Profile, Insights), then re-enables it. Cite: `loading.md › Best practices`: "Show something as soon as possible… Let people do other things in your app… while they wait." Fix: a thin indeterminate bar in the page header and a `busy` state; no grey flash.

- **H5. Colour carries state with no second cue in several places.** The match score badge is a coloured number with no label ("87" on green); the Jobs table marks an outdated score with a bare `*`; chips in tables are colour-plus-text (fine) but the Agent plan "status" column is plain text with no glyph. Cite: `accessibility.md › Vision`: "Convey information with more than color alone." Fix: score ring with the number and a band word ("Strong / Fair / Low"), an explicit "outdated" glyph and label, status glyphs inside chips.

- **H6. Default controls fight the brand.** Check boxes where a switch fits (`SettingsView` auto-triage), `ChoiceBox` with an empty value shown as a blank field (Email), raw `Dialog`s that do not load `styles.css` (they render in plain Primer blue, so a styled app opens an unstyled sheet). `toggles.md › Best practices`: use a switch for an immediate on/off setting. Fix: `ToggleSwitch`, themed dialogs.

### Medium

- **M1. Every error is a modal alert.** `Async.showError` and `Async.info` (≥240 chars) call `Alert.showAndWait()`. `feedback.md › Best practices`: "Use alerts to deliver critical — and ideally actionable — information… Alerts can lose their impact if you use them too often." Most failures here are recoverable (bad CSV row, LLM provider down) and belong inline or in a toast with a retry. Keep a dialog only for blocking, multi-line results.
- **M2. Toast auto-dismisses after 4.5 s with no pause or close.** `accessibility.md › Cognitive`: "Minimize use of time-boxed interface elements… Prefer dismissing views with an explicit action." Fix: pause on hover/focus, close button, errors stay until dismissed.
- **M3. Typography has no scale.** Sizes in use: 11, 11.5, 13, 15, 18, 24, 28 px, set per rule, with default weights. `typography.md › Conveying hierarchy`: "Adjust font weight, size, and color… to emphasize important information." `typography.md › Desktop (macOS)`: macOS default 13 pt, minimum 10 pt. 11 px chips and the 11 px email under the brand are below the 12 px comfortable floor on a 1x display. Fix: named scale (display 28, title 22, heading 15, body 13, caption 12, mono 12).
- **M4. Number alignment.** Scores, confidence, counts and dates in tables are left-aligned text, so columns of numbers do not line up. *Judgment* (`lists-and-tables.md › Content` asks for scannable rows): right-align numerics, tabular mono for scores.
- **M5. Copy.** Placeholder "Nothing here yet" is used for every empty table. Buttons mix forms: "Analyze with AI", "Score all", "Mark notifications read", "Revert selected". Statuses are raw enum names in brackets (`[SAVED]`). `writing.md`: labels say what happens; empty screens invite the next action.
- **M6. Hard-coded hex in components.** `.sidebar`, `.toast` and the accent block contain 15 literal hex values. `color.md`: semantic roles, not literals. Fix: tokens only.
- **M7. Focus ring is hand-rolled for the sidebar only** (`.sidebar .nav-button:focused` with a sky-blue border). Other controls use Primer's focus colour, which is overridden to the accent, so the ring appears in different styles in different places. Fix: one `-cp-focus-ring` token applied to every control family.

## Per page

### Login (not in the render test)
- Form is top-left in a 460×520 window with bare `Label`s above fields; no brand moment, nothing says what the app does beyond one muted sentence. `onboarding.md › Best practices`: "Keep onboarding content focused on the experience you provide"; `managing-accounts.md`: sign-in should be quick and clear.
- "Name" field is shown for sign-in although it is only needed for new accounts. Sign in and Create account are two equal-looking buttons with the same data; no inline validation (email format, 8+ characters) until the server rejects.
- Errors render as red text only with no icon (`error-text`).
- Window height is fixed at 520 and the form is not centred.

### Dashboard (`before/dashboard.png`)
- **Hierarchy:** five equal stat cards, then up to six identical yellow banners, then a table. Nothing says which item is most urgent; the agent-approval banner and "No verified projects yet" have the same weight. `design-principles.md › Simplicity`: every element earns its place.
- **Density/alignment:** stat cards are a fixed 176 px wide in a `FlowPane`, leaving a ragged gap on the right at 1180 px and wrapping unpredictably when narrower.
- **Banners:** copy ends with "(open Agent)" as the only affordance; amber is used for informational hints (profile gaps) and urgent items (approval) alike. Colour means two things.
- **Stats:** numbers have no context (no trend, no "of N"), and the "emails to review" card shows 1 while the banner list already says it.
- **Table:** "Why" column shows `score 87 >= your minimum 60` as raw text; the table has no score column, no deadline urgency, and leaves a large blank area under 2 rows.
- **States:** empty case is a muted sentence ("Nothing needs your attention."), no brand voice, no loading state besides the grey flash (H4).
- **Dark:** stat numbers fail contrast (C1); amber banners read as brown boxes.

### Jobs (`before/jobs.png`)
- **Table readability:** "Deadline" clips to `2026-11…`, "Detail" chip clips (`summary` touches the edge) at the default width; the detail pane squeezes the table because the split divider is fixed at 0.6 and the columns have fixed pixel widths.
- **Score** is plain text; the colour badge appears only in the detail. The list (the place you compare jobs) has no visual score at all. This is the product's core number.
- **Detail pane:** a long vertical stack of chips, two cards and an unbounded description; action buttons are pinned under the scroll area, so on a short window they sit on top of description text (`jobs.png`, bottom right).
- **Toolbar:** search, import, paste and score-all are four unrelated controls in one row; the primary action ("Paste a posting") is not distinguished.
- **Status line:** "A * after a score means it is outdated" is explanatory text that exists only because the table cell is cryptic.
- **Empty/loading:** no empty state for "no postings yet" (blank table with "Nothing here yet").
- **Dark:** selected row illegible (C1).

### Tracker (`before/tracker.png`)
- The page is a table plus a long form. A lifecycle (Saved → Prepared → Applied → Interview → Offer / Rejected) is the natural kanban, and the app shows it as one flat list.
- Heading shows `[SAVED]` in brackets, timeline is an empty bordered list with no explanation, and the Documents list also shows "interview round 1" (a different kind of record in the same list).
- Allowed actions are plain buttons with enum text (`mark prepared`, `submit`, `withdraw`); "submit" in a product that never submits applications is easy to misread. *Judgment:* label as "Mark as submitted" with the existing explainer attached.
- Notes box has a placeholder instead of a label and the Save button sits far below.
- "Deadline" clips (`2026-1…`).

### Profile (`before/profile.png`)
- Two columns of unequal weight: a 390 px scroll pane with two stacked forms and a Facts table. "Type" clips (`EXPERI…`). The scroll pane is shorter than its content and cuts the strategy text mid-line.
- Form labels sit on the left of every field, 90–110 px wide, with inconsistent widths between the two forms (90 and 110). Save buttons are far from the fields they save.
- Facts table: ProfileFact text, a note column that is almost always empty, and multi-select with no visible hint that multi-select works.
- The explainer "Only verified facts are ever used in your documents" is the most important trust message in the product and is set in muted 13 px grey.

### Interview (`before/interview.png`)
- The preparation plan is dumped into a read-only `TextArea` as `[category] question / why:` text, so questions, rationale, STAR stories and checklist have the same visual weight. Two large empty bordered boxes are visible before anything is generated.
- "Start a mock interview to get the first question." uses the *heading* style, so the placeholder looks like real content.
- No indication of progress through the session (question n of 5 only appears in the question text).

### Agent (`before/agent.png`)
- **A run that is waiting for approval is not shown.** The first screenshot shows an `awaiting approval` run in the list, yet the right pane says "Select or start a run" with an empty plan table ("Nothing here yet"); the Approve/Reject card appears only after a row is selected. This is the highest-stakes affordance in the app, and it is hidden.
- The status is a chip, but the run's stage, steps, tokens and plan source are crammed into one bracketed heading line.
- "What I remember about you" is a `ListView` with raw `EPISODIC: Agent run '…` strings and a horizontal scrollbar.
- Result and reasoning are one monospace-looking text area.
- Primary action "Run" has no keyboard hint; the goal field is the only entry and has an example only as a placeholder.

### Email (`before/email.png`)
- Right pane is an empty grey box until a row is selected; then body text, then two labelled choice boxes with different label widths (misaligned), then Confirm/Dismiss. No explanation of what Confirm does to the tracker.
- Column "Conf." header collapses to `…` at 50 px; "Type" shows enum names (`REJECTION`); "Application" shows "(no match)" as plain text.
- The explainer is two lines of muted text that includes a build limitation ("Gmail sign-in is not part of this build").

### Insights (`before/insights.png`)
- **The statistics are a monospace-style `TextArea`** (`String.format("%-10s n=%-3d responded %3d%%…")`). There are no charts for data that is naturally a comparison of rates across groups (score band, source, role category) and a pipeline. The `ui-ux-pro-max` chart guidance recommends a funnel for staged conversion and horizontal bars for comparing rates across a few categories, with direct labels and a table fallback.
- When there is no data (the common first-week state) the page shows "Applied: 0 …" and an empty box, rather than telling the student what produces insights.
- History table and proposals are separated by headings only; "Revert selected" acts on a table row but sits at the bottom of the page.

### Settings (`before/settings.png`)
- A long-left aligned column of headings on a page that is three-quarters empty. Placeholder "New password (8+ charac…" is truncated.
- "Let the agent triage…" is a checkbox where an immediate on/off setting should be a switch (`toggles.md`). No visible saved confirmation.
- Sign out sits in the middle of the page; About text is body-size muted.
- No appearance control (see H1).

## What works (keep)

- **Architecture for a restyle.** `View` + `name()` + `refresh()`, `Async.run` and `MainView.open` are small and clear, so the redesign can be mostly CSS plus layout.
- **Honest copy about the product's limits** ("CareerPilot never submits an application"). This is the best brand line the app has; the redesign should use it deliberately.
- **Chips carry both text and colour** (`Ui.chip`), so state is not colour-only in most tables.
- **SVG icon set** in one style, no emoji.
- **Short transitions** (160 ms fade) and background loading via `Async`.
- **Table rows are 36 px**, which clears the 28 px desktop control minimum (`accessibility.md › Mobility`).
- **A render test that builds every page against seeded data**, which makes visual regression practical. It now captures light and dark.

## Craft notes

- **Point of view:** none. The look is "SaaS admin template, blue". The one distinctive idea in the product (a goal-driven agent that plans and asks permission) never shows up visually.
- **Template check:** sky blue `#0369a1` + slate sidebar + white cards is exactly what the `ui-ux-pro-max` generator returns for "job search tracker productivity", so it is the default, not a choice.
- **Boldness is spent nowhere.** Every element is the same size of card.
- **Remove one accessory:** the five-stat row duplicates counts that are visible one click away; fold into a single summary line and spend the space on what needs attention.

## Sequence

Accessibility and dark-mode correctness first (C1, C2, H4), then shell and navigation (H2, H3), then page structure, then craft (point of view, signature element) and copy.
