# Quire — Rebrand & UI/UX Polish

**Date:** 2026-05-03
**Status:** Design approved (user delegated remaining decisions); ready for plan
**Scope:** Identity + library/reader polish before Phase 2 (sync). No backend/server work.
**Supersedes nothing.** Layered on top of `2026-04-26-opds-ereader-design.md`.

---

## 1. Goals

Take the bare-bones Phase 1 UI from "works" to "feels like a real product":

- Replace the placeholder identity ("eReader") with a coherent brand: name, icon, palette, typography.
- Add book covers throughout — currently every list is text-only.
- Give the reader real chrome — currently it's a raw Readium fragment with no top/bottom bars or settings access.
- Replace the top-bar text-button navigation hopping with a proper bottom navigation bar.
- Tighten settings layout into grouped cards.

Out of scope for this round (deferred):
- Onboarding / first-run flow.
- Search.
- Library sort/filter beyond default "recent."
- Custom Compose page-turn animations (Readium's defaults stay).
- Account / login UI (lands with Phase 2 sync).

## 2. Non-goals

- Touching the data layer, identity logic, OPDS client, or Readium integration. UI-only.
- Shipping a new font as the reader body font (reader font is user-controlled via Readium).
- Re-architecting modules. The work fits inside `:app` plus minor additions to `:reader` and `:data:opds` for cover fetching.

## 3. Identity

### 3.1 Name

**Quire.** A bookbinding term — a gathering of folded sheets bound into a section. One syllable, distinctive, unambiguously book-related, and unclaimed in the Android reader space.

- App label: `Quire`
- Android `applicationId` and `namespace` rename: `io.theficos.ereader` → `io.theficos.quire`. (User-facing identifier — what shows up in installs and Play Store.)
- Kotlin package names stay as `io.theficos.ereader.*`. They're internal organization and renaming them across every module/test for cosmetic alignment isn't worth the churn. Engineers reading import lines get the historical name; users get "Quire."
- Tagline (descriptions only, not in-app): "Your library, in margin and ink."

### 3.2 Launcher icon

Adaptive icon, two layers:

- **Background layer:** oxblood radial gradient `#7A2E2A → #4A1A18`.
- **Foreground layer:** italic lowercase serif `q` in cream `#F5EFE3`, ~50% of canvas.

Round, square, and squircle masks all read clean because the `q` is the only mark.

### 3.3 Color tokens

Light theme (primary):

| Token | Hex | Use |
|---|---|---|
| `surface` | `#F8F4EC` | App background (warm off-white / paper) |
| `surfaceContainer` | `#FEFBF4` | Card background (continue-reading, settings groups) |
| `outline` | `#EFE5D2` | Hairlines, dividers, progress track |
| `onSurface` | `#1F1A14` | Primary text |
| `onSurfaceMuted` | `#8A7355` | Secondary text, captions |
| `accent` | `#7A2E2A` | Progress fill, primary buttons, active nav, links, labels |
| `accentDeep` | `#4A1A18` | Pressed state, icon gradient end |
| `onAccent` | `#F5EFE3` | Text/icons on accent |

Dark theme:

| Token | Hex |
|---|---|
| `surface` | `#1A1612` (warm near-black, not pure dark) |
| `surfaceContainer` | `#241F18` |
| `outline` | `#3A322A` |
| `onSurface` | `#EDE5D5` |
| `onSurfaceMuted` | `#9A8B72` |
| `accent` | `#C26A66` (pure oxblood goes muddy on dark — lifted) |
| `accentDeep` | `#A04846` |
| `onAccent` | `#1A1612` |

Tokens map to Material3 `ColorScheme` slots:
- `primary` ← `accent`
- `onPrimary` ← `onAccent`
- `background` and `surface` ← `surface`
- `surfaceVariant` ← `surfaceContainer`
- `onBackground`, `onSurface` ← `onSurface`
- `outline` ← `outline`

### 3.4 Typography

- **UI sans-serif:** system default (Roboto on Android via `-apple-system` chain). No bundled font. Honors user accessibility settings.
- **Serif (titles, wordmarks):** **Lora** — bundled as a font resource in two weights (Regular 400, SemiBold 600). Used for: app wordmark, book titles in lists, "Continue reading" headline, reader top bar book title.
- **Reader body text:** stays Readium-controlled. Default offered list expands from "Lora, Literata, Charter, System Serif, System Sans, OpenDyslexic" (Readium ships these or supports system fallbacks).

Type scale (mapped to `Typography` slots):

| Style | Size / weight / family | M3 slot |
|---|---|---|
| `display` | 28sp / 600 / Lora | `displaySmall` |
| `titleLarge` (book titles in lists) | 16sp / 600 / Lora | `titleMedium` |
| `titleSmall` (section headers) | 14sp / 600 / sans | `titleSmall` |
| `labelSmall` (uppercase eyebrow text e.g. "CONTINUE READING") | 11sp / 700 / sans, tracking 0.14em | `labelSmall` |
| `body` | 14sp / 400 / sans | `bodyMedium` |
| `caption` | 12sp / 500 / sans, muted | `bodySmall` |

### 3.5 Shape & elevation

- Cards: 12dp corner radius, **no shadow** — instead, a 1dp `outline` border. (Shadows fight the paper aesthetic.)
- Book covers: 4dp radius, soft drop-shadow `0 2dp 6dp rgba(80,50,20,.15)`.
- Buttons: 10dp radius, `accent` fill, no elevation.
- Bottom nav bar: `surfaceContainer` background, top hairline of `outline`, no shadow.

## 4. Navigation

Replace the current top-app-bar-with-text-buttons pattern with a **bottom navigation bar** containing three destinations:

| Tab | Icon (filled when active) | Label |
|---|---|---|
| Library | book stack | Library |
| Catalog | grid / cloud-download | Catalog |
| Settings | gear | Settings |

Active tab uses `accent` for icon + label; inactive uses `onSurfaceMuted`.

The Reader screen is a **fullscreen modal destination** — when navigated to (`reader/{docId}`), the bottom nav is hidden. Back button (system or in-bar) returns to Library.

## 5. Library screen

```
┌───────────────────────────────┐
│  Quire                        │  ← display-style wordmark
│                               │
│  CONTINUE READING             │  ← labelSmall, accent color
│  ┌─────────────────────────┐  │
│  │  ╔═╗  Master and        │  │
│  │  ║▓║  Margarita         │  │
│  │  ║▓║  Bulgakov          │  │  ← serif title, muted author
│  │  ║▓║  ▓▓▓▓░░░░░ 62%     │  │  ← oxblood progress
│  │  ╚═╝  ch. 14            │  │
│  └─────────────────────────┘  │
│                               │
│  LIBRARY · 12        Recent ▼ │
│                               │
│  ┌──┐  ┌──┐  ┌──┐             │
│  │▓▓│  │▓▓│  │▓▓│             │  ← 3-col cover grid
│  └──┘  └──┘  └──┘             │
│  Anna   Dune   Piranesi       │
│  Karenina                     │
│                               │
│  ┌──┐  ┌──┐  ┌──┐             │
│  │▓▓│  │▓▓│  │▓▓│             │
│  └──┘  └──┘  └──┘             │
│                               │
├───────────────────────────────┤
│  ▣ Library   Catalog   ⚙      │  ← bottom nav, Library active
└───────────────────────────────┘
```

### 5.1 Continue-reading hero

Shown when the document with the most recent `progress.updatedAt` has `0 < percent < 1`. Tap → opens the reader at the saved locator (existing flow).

If no in-progress book exists, the hero is omitted (not a blank placeholder — just hidden; the section header skips straight to "LIBRARY · N").

### 5.2 Library grid

- 3 columns, equal-width covers with 2:3 aspect ratio.
- Cover image fetched from OPDS publication metadata at download time (see §8).
- Title: serif, 2 lines max, ellipsis. No author shown in grid (saves vertical space; author is in the continue-reading card and book details).
- Tap → open reader. Long-press → bottom-sheet action menu (Read · Mark unread · Delete).
- Delete confirmation flow stays as-is (existing `AlertDialog`).

### 5.3 Empty state

When the local library is empty:

```
       (large serif "q" mark, muted)

    Your shelf is empty.

  Open the Catalog tab to find books.
```

Centered, no illustration, type-led — fits the aesthetic.

## 6. Catalog screen

Two row types from the OPDS feed: **navigation** entries (folders) and **publication** entries (downloadable books).

- Navigation entries render as full-width list rows with a chevron — same as current, restyled.
- Publications render as a **2-column cover grid** (covers are larger here than in Library since the user is actively browsing).
- Title (serif, 2 lines), author (sans muted, 1 line), and a download-state badge:

| State | Badge |
|---|---|
| Not downloaded | Small download arrow icon, oxblood, top-right of cover |
| Downloading | Circular progress ring (oxblood) overlaid on cover, dimmed cover |
| Downloaded | Cream filled circle with oxblood checkmark, top-right of cover |

- Tap on cover (not downloaded) → kicks off download.
- Tap on cover (downloading) → no-op (cancel deferred).
- Tap on cover (downloaded) → snackbar "Already in Library" with a "View" action that switches to the Library tab and scrolls to the book. (Direct open-in-reader from Catalog is intentionally avoided — keeps tab semantics clean.)

Top of screen: "Catalog" wordmark, with a back-chevron when descending into a sub-feed (current breadcrumb behavior is implicit through nav stack).

## 7. Reader screen

The biggest UX change: **chrome**.

### 7.1 Auto-hide chrome

- On entry: chrome is visible for ~2.5 seconds, then auto-hides.
- Tap **center third** of the screen (a vertical band, ~50% of width centered): toggles chrome visibility.
- Tap **left third / right third**: page-turn (Readium's existing behavior — unchanged).
- Swiping pages does not change chrome state.

### 7.2 Top bar (when visible)

- Back arrow → returns to Library (or wherever caller came from).
- Book title, serif, truncated middle if too long.
- Overflow menu (⋮) → bottom sheet with: Table of Contents, Font Settings, Bookmark this position (Phase 4 — disabled with placeholder for now).

### 7.3 Bottom bar (when visible)

- Left: chapter title (truncated) + percent (`62%`).
- Right: page count if Readium reports one (`14 / 22`), else hidden.
- Above the bar: a thin (3dp) `Slider` whose value is current progress; dragging updates Readium's locator. Track is `outline`, fill+thumb are `accent`.

### 7.4 Font settings sheet

Bottom sheet, opened from overflow menu's "Font Settings":

- **Font size slider** (existing — restyled).
- **Theme** segmented control: Light · Sepia · Dark.
  - Light = `surface` background, `onSurface` text.
  - Sepia = `surfaceContainer` cream + warm-dark text `#3A2E1F`. **No accent color shown.** Chrome dims out of the way.
  - Dark = warm dark surface, `onSurface` light text.
- **Font family picker** — list of: Lora, Literata, Charter, System Serif, System Sans, OpenDyslexic.
- **Line spacing** slider — `1.0×` to `1.8×`, step `0.1`.

All settings persist via existing `ReaderPreferencesStore`; new fields (font family, line spacing) extend `ReaderPreferences`.

## 8. Cover fetching

The OPDS client currently extracts publication title, author, and EPUB download href but **not** cover image URL.

Spec change to `:data:opds`:

1. When parsing a publication entry, also extract the cover URL — OPDS Atom `<link rel="http://opds-spec.org/image" type="image/..."/>` (preferred) or `rel="http://opds-spec.org/image/thumbnail"` (fallback). Store in `OpdsPublication.coverUrl: String?`.
2. When the user downloads a book (`BookDownloader`), fetch the cover URL alongside the EPUB. Save to the same per-document directory as `cover.jpg` (no transcoding — write whatever bytes come back, with the original Content-Type extension).
3. Add `coverPath: String?` to the local `Document` row (Room migration). Populated at download time, null if cover wasn't available or fetch failed.
4. Catalog screen renders covers from URL directly via Coil (`AsyncImage`), with the fallback below for failure.
5. Library grid renders from `coverPath` via Coil.

### 8.1 Cover fallback

When a cover is missing (URL absent, fetch failed, file gone):

- A generated 2:3 placeholder card.
- Background: oxblood gradient (slight variation per-title via hash → 4 preset gradients, so the shelf isn't monotonous).
- Two large serif initials of the author's surname (or title's first word if no author), `onAccent` color, centered. Lora 600.

This is rendered in Compose, not pre-baked — keeps the shelf consistent regardless of cover-fetch state.

## 9. Settings screen

Same fields as today, restyled:

- Wordmark "Settings" (display style) at top, no top app bar (bottom nav handles tab switching; nothing else routes here).
- Three grouped cards:
  - **calibre-web** — URL, Username, Password, Save button.
  - **Reader defaults** — font size, theme, font family, line spacing (mirrors what's in the reader's font sheet — these are the *defaults* applied when opening a new book; per-book overrides come later).
  - **About** — "Quire vX.Y · A reader for your shelf."
- Cards use the design-system shape (12dp radius, `outline` border, no shadow), grouped vertically with 16dp spacing.

## 10. Module impact

| Module | Change |
|---|---|
| `:app` | Theme overhaul, all screen refactors, navigation graph change (bottom-nav scaffold), launcher icon assets, app label string, applicationId/namespace rename. |
| `:app/res` | New launcher icons (mipmap-anydpi adaptive), `colors.xml`, `themes.xml`, Lora font resource. |
| `:reader` | `ReaderPreferences` gains `fontFamily` and `lineSpacing`. `ReaderScreen` gains chrome overlay. |
| `:data:opds` | `OpdsPublication.coverUrl` added; parser updated. |
| `:data:local` | Room migration adding `coverPath` to `documents` table. |
| `:data:opds` (or wherever `BookDownloader` lives) | Fetch cover alongside EPUB. |

No changes to `:core:*`, `:auth`, identity logic, or sync code (which doesn't exist yet). Phase 2 sync work begins on top of this.

## 11. Open decisions deferred

- Per-book reader preferences (override defaults). Settings sheet currently writes globals — flagged for a later round.
- Sort order in library beyond "recent" — placeholder dropdown shown but only "Recent" is implemented.
- Book details screen (tap cover → metadata view before opening). Not in this round; tap goes straight to reader.
- Custom Compose-driven page transitions. Not in this round; Readium's defaults stay.

## 12. Decision log

| # | Decision | Rationale |
|---|---|---|
| 1 | Name: Quire | Distinctive, on-theme, single syllable, unclaimed. |
| 2 | Hybrid aesthetic (warm cream + sans UI + serif titles) | Modern Android conventions where they help; literary warmth where it matters (titles, reading surface). |
| 3 | Oxblood accent | Strong character; user picked over forest green knowing the trade-off vs. red book covers. |
| 4 | Bundle Lora, not a custom UI font | Ships small (~120 KB), proven readable at title sizes, FOSS. UI sans stays system to honor accessibility. |
| 5 | Bottom nav with 3 tabs | Replaces ad-hoc top-bar text buttons; matches Android conventions; reader-as-modal preserves immersion. |
| 6 | Auto-hide reader chrome on tap-center | Kindle/Kobo convention; preserves Readium's left/right-third page-turn taps. |
| 7 | Cover fallback rendered in Compose, not pre-baked | Consistency regardless of cover-fetch state; no asset storage churn. |
| 8 | Cards use 1dp outline border, no shadow | Shadows fight the paper aesthetic; outlines feel like a printed frame. |
| 9 | Sepia reader theme suppresses accent color | Preserves immersion — the page should feel like a page, not a UI. |
| 10 | Cover fetch at download time, not lazy from URL in Library | Library works offline; fetch failures are non-blocking. |
