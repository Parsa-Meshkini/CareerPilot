# CareerPilot GUI: direction (Phase 1)

Skills used: `apple-design` (platform rules and review), `ui-ux-pro-max` (palette, type, chart and JavaFX guidance), `brand` (voice and mark), `design-system` (three-layer tokens). `frontend-design` was not available in this environment; its job (choosing a non-default aesthetic) is covered by the critique step below.

## One-sentence thesis

**CareerPilot is a quiet instrument panel for a stressful job search: one calm surface, one signal colour, and one memorable gauge, the *bearing ring*, that tells you how well a job fits.**

## Critique of the first plan

The `ui-ux-pro-max` design-system generator returned: flat design, sky blue `#0369A1`, green CTA, "corporate, trustworthy" Lexend. That is the current app. Another palette search returned indigo `#6366F1` ("remote work tool") and teal `#0D9488` ("productivity tool"), which are the two other default looks for this category. I would have produced the same plan for any B2B dashboard, so I rejected all three.

What is specific to *this* product, and therefore what the design is built on:

- The product's best line is a limit: **"It prepares and tracks; you always submit."** The agent proposes and waits for approval. The UI should feel like a *copilot that asks*, not a dashboard that shouts.
- The one number that drives every decision is the **match score**. It is currently plain text in the list.
- Users are students under deadline pressure. The brand job is to lower heart rate, not to add excitement.

So: neutral surfaces tinted toward deep ink-blue (not warm cream, not near-black with a neon accent, not hairline broadsheet), one confident ultramarine accent used only where something is *actionable or active*, amber reserved for "needs you", and the bearing ring as the single piece of boldness. Everything else is deliberately quiet.

## Mood and references

Calm, precise, unhurried. A cockpit checklist rather than a sales dashboard.
References for *feel*, not for copying: Linear (density, keyboard-first), Things (generous space, one task at a time), macOS Mail (sidebar + list + detail, native selection behaviour). Our own point of view: instrument-panel restraint plus the ring gauge and flight-deck phrasing in the microcopy.

## Signature element: the bearing ring

A 270° arc gauge with the score inside, drawn with JavaFX `Arc` (vector, themed via CSS classes, no images).
- Track is a neutral; fill colour follows the band (Strong ≥ 70, Fair 45–69, Low < 45) **and** a word label sits beside it ("Strong fit"), so colour never carries the meaning alone.
- Small (28 px) in list rows, large (72 px) in the job detail header, mini in the Dashboard shortlist.
- Outdated scores get a dashed track and an "Outdated" label instead of a trailing `*`.

Nothing else is allowed to be this loud. Everything else follows from the tokens below.

## Palette

Roles, not hues. Every pair below is measured against its actual background (WCAG 2.1 contrast, computed from the hex values).

### Light ("Paper")

| Token | Hex | Used for | Contrast |
| --- | --- | --- | --- |
| `bg-canvas` | `#F7F8FA` | window / page | |
| `bg-surface` | `#FFFFFF` | cards, tables, inputs | |
| `bg-sidebar` | `#EEF0F5` | sidebar | |
| `bg-sunken` | `#F0F2F6` | inset areas, table header | |
| `bg-hover` | `#E6E9F1` | row/button hover | |
| `bg-selected` | `#E8ECFF` | selected row, active nav | |
| `fg-primary` | `#121827` | body, titles | 17.7:1 on surface |
| `fg-secondary` | `#454F63` | supporting text | 8.2:1 on surface |
| `fg-tertiary` | `#5A657B` | captions, placeholders | 5.9:1 on surface, 4.8:1 on hover |
| `accent` | `#3A4FD8` | primary button, focus ring, active marker | white on it 6.4:1 |
| `accent-fg` | `#3043C4` | links, accent text | 7.7:1 on surface, 6.6:1 on selected |
| `border` | `#DADFE8` | dividers, card edge (decorative) | |
| `border-control` | `#7C869B` | input / checkbox boundary | ≥ 3:1 on surface |
| success `#17653F` / `#E2F4E9` | text / tint | 6.2:1 |
| warning `#855400` / `#FFF1CF` | text / tint | 5.7:1 |
| danger `#B0202F` / `#FDE7E9` | text / tint | 5.8:1 |

### Dark ("Night ink", not pure black, no glow)

| Token | Hex | Contrast |
| --- | --- | --- |
| `bg-canvas` | `#0D111C` | |
| `bg-surface` | `#151B2B` | |
| `bg-sidebar` | `#101522` | |
| `bg-sunken` | `#0A0E17` | |
| `bg-hover` | `#1D2539` | |
| `bg-selected` | `#1F2B5C` | fg-primary 11.5:1 |
| `fg-primary` | `#E9EDF6` | 14.6:1 on surface |
| `fg-secondary` | `#B3BCCF` | 9.0:1 |
| `fg-tertiary` | `#8D99B0` | 6.0:1 |
| `accent` | `#4A5FE6` | white on it 5.2:1 |
| `accent-fg` / `focus-ring` | `#9AA9FF` | 7.8:1 on surface |
| `border` | `#252E46` | decorative |
| `border-control` | `#5E6C8F` | 3.3:1 on surface |
| success `#62D393` / `#10291C` | 8.3:1 |
| warning `#F4BB50` / `#33270C` | 8.4:1 |
| danger `#FF8F99` / `#3A141A` | 7.5:1 |

Rules: accent is for *actionable or active* things only (primary button, active nav marker, focus ring, links). Amber means "needs you". Green, amber and red are used for status and always travel with a word or glyph. Large surfaces never use accent.

Appearance follows the operating system by default (see AUDIT H1); the user can override it in Settings.

## Type

System font stack: `"SF Pro Text", "Segoe UI", "System"`. No custom web fonts: native feel and no bundling. Personality comes from scale, weight and the ring, not from a display face. Mono (`"SF Mono", "Menlo", "Consolas"`) is used for scores, counts and ids so digits align.

| Style | Size / weight | Use |
| --- | --- | --- |
| display | 28 / bold | big numbers (ring centre in detail, stat) |
| title | 22 / bold | page title |
| heading | 15 / bold | section heading, card title |
| body | 13 / regular | default (macOS desktop default 13 pt, `typography.md › Desktop`) |
| body-strong | 13 / bold | row titles, labels |
| caption | 12 / regular | metadata, hints (floor is 12, nothing smaller) |
| mono | 12 / regular | scores, ids, counts |

## Spacing, radius, elevation, motion

- **Spacing** (4-pt grid): 4, 8, 12, 16, 24, 32, 48. Page gutter 24; card padding 16; row gap 8.
- **Radius:** control 6, card 10, chip pill, ring round. Concentric where nested (card 10 → inner control 6).
- **Elevation:** E0 flat with a 1 px border (cards, tables); E1 `gaussian 6, 6% ink, y1` (hovered/selected card); E2 `gaussian 18, 14% ink, y6` (toast, popover, dialog). No glow, no gradients on surfaces.
- **Motion:** page change = 140 ms fade + 6 px rise, sidebar collapse = 160 ms, ring fill = 300 ms on first show only. Skipped entirely when the OS asks for reduced motion. Nothing animates on frequent interactions (hover, selection).

## Voice (from `brand`)

Calm, plain, second person, present tense. Say what happened, then what to do next. No exclamation marks, no blame, no "oops". Light flight-deck vocabulary *only* where it is accurate and short ("Clear skies", "Cleared to continue", "Needs you"). The key promise stays visible: "You submit. CareerPilot prepares and tracks."

| Where | Line |
| --- | --- |
| Dashboard, nothing waiting | "Clear skies. Nothing needs you right now." |
| Jobs, none saved | "No postings yet. Paste one, import a CSV, or use the browser extension." |
| Agent, no runs | "Give the agent a goal. It plans, then asks before it does anything that changes your data." |
| Insights, too little data | "Insights unlock after 10 applications with an outcome. You have 0." |
| Error, LLM unavailable | "The AI model didn't answer. Your data is unchanged. Check the connection in Settings, then try again." |
| Sign-in | "Your job search, organized. You submit; CareerPilot prepares and tracks." |

Capitalisation: sentence case for buttons, titles and labels ("Add fact", "Paste a posting"), applied consistently.

## Token architecture (implemented in `tokens.css` and `components.css`)

1. **Primitive** (`-cp-ink-*`, `-cp-signal-*`, status ramps): raw values, no meaning.
2. **Semantic** (`-cp-bg-*`, `-cp-fg-*`, `-cp-accent-*`, `-cp-border-*`, `-cp-success/warning/danger-*`, `-cp-focus-ring`): defined once for light on `.root`, overridden on `.root.cp-dark`. These are also mapped onto AtlantaFX's `-color-*` names so every built-in control follows.
3. **Component** (`-cp-card-*`, `-cp-chip-*`, `-cp-nav-*`, `-cp-table-*`, `-cp-ring-*`): small set of derived names used by `components.css` selectors only.

No component selector contains a hex value.

## Platform conventions kept (with the HIG page behind each)

- Sidebar with grouped sections, hideable, never holding critical actions at the bottom: `sidebars.md › Best practices`.
- Concise page titles in a toolbar-like header, no app name as title, primary action on the trailing side: `toolbars.md › Titles, Actions`.
- Every navigation command is also in the menu bar (system menu bar on macOS): `toolbars.md › Desktop (macOS)`.
- Lists/tables: selection highlighted persistently where it drives a detail view, sortable columns, readable truncation: `lists-and-tables.md`.
- Focus: ring on text fields, highlight on list rows: `focus-and-selection.md › Best practices`.
- Colour never alone, text ≥ 4.5:1, controls ≥ 28 pt: `accessibility.md`.
- Follow the system appearance; no app-only mode as the default: `dark-mode.md › Best practices`.
- Show something immediately while loading and keep the app usable: `loading.md`.
- Alerts only for critical, actionable news; feedback in place: `feedback.md`.
