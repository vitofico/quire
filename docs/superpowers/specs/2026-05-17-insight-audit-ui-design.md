# PR6 — Insight audit UI

> Shipped in 92ca525 on 2026-05-17 as PR #21.

**Date:** 2026-05-17
**Branch:** Android-only (`feat/insight-audit-ui`)
**Effort:** Short
**Deps:** PR-A (mode flags / branch split) — already shipped.
**Status:** Draft — ready for review

## Goal

Give power users (and me) a one-tap way to inspect the metadata of the AI
insight currently cached for a book, and to evict it. No new server endpoint
— the existing body-based `POST /ai/v1/insights/invalidate` is reused. No
"regenerate" affordance — `PR11` explicitly removes the regenerate pattern
because invalidate-and-natural-refresh on the next open of the book detail
covers the same use case at half the AI cost.

The screen is small but load-bearing for diagnosing cache surprises:

- Did this book get the new `prompt_version`?
- Which `model_id` produced this answer?
- Was it generated under my current `tone` / `language` preferences, or am I
  looking at a stale row from before I changed them?
- Are the source citations resolvable (Wikipedia / OpenLibrary URLs that
  actually open)?

## Why now

Three recent PRs added cache-key dimensions that are otherwise invisible to
the user:

- **PR-C** (`ai_generation_log`) — we now want to know `model_id` and
  `prompt_version` at a glance for any cached row.
- **PR4** (language preference) — `language` is now in the cache key. Without
  a UI, a user who set `language="it"` and then reverts to `"auto"` cannot
  tell which insight they are looking at.
- **PR9 / PR3** (themes v3) will land in this batch and bump
  `schema_version` from `2` → `3`. Before that lands, users (me) need a way
  to evict old-schema rows on-demand and confirm the next lookup hits the
  new schema.

Doing this as a tiny audit UI is also the cheapest possible "user-facing
debug surface" — it removes the need for me to `kubectl exec` into a pod
just to see what shipped.

## Non-goals

- **Regenerate button.** Explicitly dropped. PR11 (running in parallel) is
  removing the existing one from the book-detail Regenerate `TextButton`.
  Re-introducing it here would directly contradict the roadmap and double
  the AI cost per "this answer is wrong" interaction.
- **No new server endpoint.** Reuses the existing
  `POST /ai/v1/insights/invalidate` (body-based, already deployed). No
  migration; no Alembic branch touched.
- **No live polling.** The screen reads the cached row once on entry. If the
  cache row changes mid-session (e.g., another device invalidated it), the
  user sees the stale snapshot until they leave and re-enter the screen.
  Acceptable because the screen is a "debug peek", not a live dashboard.
- **No diff against current preferences.** The screen does not compare the
  cached `tone` / `language` against the current `AiStyle` and highlight
  mismatches. That is a "phase 2" usability improvement; for now, both
  values are simply displayed and the user can eyeball the comparison.
- **No GET that returns metadata only.** Reuses `POST /ai/v1/insights/get`
  which returns the full `BookInsightResponse`. Calling it once on screen
  entry is cheap.

## Surface

New Compose screen `InsightAuditScreen.kt` reachable from the book-detail
overflow. Route added to `AppNavGraph.kt`. Visual outline:

```
┌──────────────────────────────────────────────┐
│ ← Inspect insight                            │
├──────────────────────────────────────────────┤
│  Cache key (from server)                     │
│    Model        gpt-4o-mini-2024-07-18       │
│    Prompt ver.  3                            │
│    Schema ver.  2                            │
│                                              │
│  Your current AI style                       │
│    Tone         neutral                      │
│    Language     auto                         │
│                                              │
│  Generated                                   │
│    3 hours ago                               │
│    (2026-05-17T13:42:11Z)                    │
│                                              │
│  Sources (3)                                 │
│    · Wikipedia    https://en.wikipedia.org…  │
│    · OpenLibrary  https://openlibrary.org/…  │
│    · AI model: gpt-4o-mini-2024-07-18        │
│                                              │
│  [ Invalidate cached insight ]               │
└──────────────────────────────────────────────┘
```

**Tone / language rendering — architect callout.** The server response
does NOT carry the `tone` and `language` that produced the cached row,
so the audit screen must NOT label them as cached metadata (would imply
we know which style this row came from). They are shown under a
separate "Your current AI style" section sourced from the live
`AiPreferences.style` snapshot. If preferences haven't loaded yet, show
"—" for both. No "matches preferences" hint — the comparison cannot be
made without server-side `tone` / `language`.

### State machine

```
                   ┌─────────┐
   enter screen →  │ Loading │
                   └────┬────┘
                        ▼
              (call AiRepository.getCachedInsight)
                        │
        ┌───────────────┼──────────────────────┐
        ▼               ▼                      ▼
   ┌─────────┐     ┌──────────┐         ┌──────────┐
   │ Loaded  │     │NotCached │         │  Error   │
   └────┬────┘     └──────────┘         └──────────┘
        │  user taps Invalidate
        ▼
   ┌──────────────┐
   │ ConfirmDialog│
   └──────┬───────┘
          │ confirm
          ▼
   ┌─────────────────┐
   │ Invalidating    │
   └────┬────────┬───┘
        │        │
        ▼        ▼
   ┌────────┐ ┌────────┐
   │Done    │ │Error   │
   │(popBack│ │(stay   │
   │ + flag)│ │ open)  │
   └────────┘ └────────┘
```

- `Loading` — entering the screen before the cached row arrives.
- `Loaded(BookInsightResponse, currentStyle)` — the cached row + the user's
  current `AiStyle` snapshot for the "matches preferences" hint.
- `NotCached` — nothing cached for this identity. Shows a short paragraph
  ("No insight cached for this book yet. Open the book detail to generate
  one.") and disables the Invalidate button.
- `Error(message)` — network failure on the initial fetch. Shows the message
  and a Retry button.
- `Invalidating` — overlay/disabled state while the DELETE-equivalent call
  runs.
- On success: pop back to book detail with a result flag in the savedState
  handle so the book-detail VM re-runs `load()` and naturally either hits a
  cold cache (and regenerates on the next entry if it chooses) or simply
  shows nothing.
- On failure: stay on the audit screen, show a transient Snackbar via a
  `SharedFlow<String>`.

### Confirmation dialog

```
Invalidate insight?
──────────────────
Invalidating this insight removes the cached AI response. Returning to
this book detail will generate a fresh insight, which may take a few
seconds and uses one of your daily generations.

  [ Cancel ]   [ Invalidate ]
```

Copy chosen on architect advice: "uses one of your daily generations" is
clearer than "counts against your daily AI budget"; the "may take a few
seconds" framing surfaces the latency cost alongside the quota cost so
the user can weigh both.

Two-step confirmation matters here because invalidation is silently
expensive: the next book-detail open consumes one generation from the
budget. A casual misclick on a single tap would cost a generation.

### Source list rendering

The cached response has `List<Citation>`. Citation kinds we know about
today (from `InsightCards.kt::SourcesFooter`):

- `wikipedia` → labeled "Wikipedia", URL tappable.
- `openlibrary` → labeled "OpenLibrary", URL tappable.
- `model` → labeled "AI model: <title>", no URL.
- `opf` → labeled "Book metadata", no URL.
- anything else → falls back to `title` as label, URL tappable if present.

In the audit screen each source is rendered as a row:

```
· <label>
  <url, monospace, truncated end-ellipsis if too long>
```

Tapping the URL opens via `LocalUriHandler.current.openUri(url)`, which on
Android resolves to Chrome Custom Tabs when the user has a default browser
configured. Same mechanism `InsightCards::SourcesFooter` already uses; no
new permission, no new manifest entry, no new INTERNET-touching code
(URLs go through the system browser).

## Wiring

### Book-detail overflow menu

`BookDetailScreen.kt` currently has no overflow menu. It exposes:

- "Open in reader" `TextButton`.
- "Not quite right? Regenerate" `TextButton` (slated for removal in PR11).

This PR adds a `MoreVert` `IconButton` to the top app bar with a single
item:

```kotlin
DropdownMenu(expanded = …, onDismissRequest = …) {
    DropdownMenuItem(
        text = { Text("Inspect insight") },
        onClick = {
            menuExpanded = false
            onInspectInsight()
        },
    )
}
```

The menu item is shown unconditionally — even when no insight is cached,
the audit screen handles the `NotCached` state gracefully and is informative
about why.

**Coordination with PR11:** PR11 removes the "Not quite right? Regenerate"
button and its supporting `RegenerateDialog`. Both PRs touch
`BookDetailScreen.kt` but operate on disjoint nodes (PR11 removes a
`TextButton` inside the column; PR6 adds an `IconButton` inside the
`TopAppBar` `actions` slot, plus an overflow `DropdownMenu`). A small
manual merge may be needed in the `TopAppBar { … }` block where PR11
might also want to inject overflow actions — call out in the PR body.

### Navigation argument

The new route needs a `DocumentIdentity`. Existing routes (`reader/{docId}`,
`book/{id}`) pass the local `Long` documentId. The audit screen could:

a. Receive the local `Long` documentId and resolve to identity inside the
   ViewModel via `DocumentRepository.findById(id).identity`.
b. Receive the serialized `DocumentIdentity` in nav args.

**Decision:** (a). Consistent with the existing `book/{id}` route, avoids
URL-encoding two fields (`metadataId` is nullable, `contentHash` is a
hex string), and the resolution is already what `BookDetailViewModel.load`
does. The audit ViewModel takes the same `documentId: Long` constructor
argument and the same `DocumentRepository` and `AiRepository`.

Route: `book/{id}/inspect-insight`.

### Post-invalidate refresh

When the user confirms an invalidate:

1. `InsightAuditViewModel` calls `AiRepository.invalidate(identity)`.
2. On success → set state to `Done`, emit an event.
3. The screen observes the event and calls
   `nav.previousBackStackEntry?.savedStateHandle?.set("insight_invalidated", true)`,
   then `nav.popBackStack()`.
4. `BookDetailScreen` reads the savedState flag in a `LaunchedEffect`. If
   set, it calls `viewModel.retry()` (which already re-runs `load()`) and
   clears the flag.

This pattern is already idiomatic for Android Navigation Compose and adds
zero new infrastructure.

## Constraints

- **F-Droid posture.** Pure UI; no new network destinations; uses the
  user's default browser via `LocalUriHandler` for tappable URLs. No new
  permissions in `AndroidManifest.xml`.
- **No new server endpoint.** Reuses `POST /ai/v1/insights/get` and
  `POST /ai/v1/insights/invalidate`, both already deployed.
- **No DTO mutation required.** The existing `BookInsightResponse` already
  carries `modelId`, `promptVersion`, `generatedAt`, and the nested
  `payload.schemaVersion`. `tone` and `language` are **not** part of the
  server response (they live on the request side as cache-key knobs) — the
  audit screen reads them from the user's current `AiPreferences.style`
  snapshot and labels them clearly as "your current style preference at the
  time this row was last looked up". If `AiStyle` is unavailable
  (preferences not yet loaded), those fields render as "—".
- **DTO additions:** none. If a future server PR adds `tone` / `language`
  to `BookInsightResponse`, the audit screen will surface them via the
  same field placeholders without further code change.

## Test surface

This repo does **not** have Compose UI test infrastructure
(`androidx.compose.ui:ui-test-junit4` is not in the version catalog, no
`androidTest` source set on `:app`). Adding it would be a multi-PR detour
(transitive Robolectric + ActivityScenario setup; F-Droid reproducible-build
implications for `androidTest` configurations). Out of scope for PR6.

Coverage is therefore split across:

1. **`InsightAuditViewModelTest`** (Robolectric, `:app:testDebug`):
   - `loads cached insight into Loaded state when present`.
   - `surfaces NotCached when AiRepository returns null`.
   - `surfaces Error on network failure`.
   - `invalidate success transitions to Done state and emits event`.
   - `invalidate failure leaves state in Loaded and emits error event`.
   - `style snapshot reflects current AiPreferences at time of load`.
2. **No new `AiRepository` test needed.** The existing `invalidate(identity)`
   wraps the existing `client.invalidateInsight`, which is already covered
   in `AiClientTest`. We are not adding new client methods.

The visual layout is verified by manual smoke on the emulator + a Compose
`@Preview` annotated function in `InsightAuditScreen.kt` for at least the
`Loaded` (full payload) and `NotCached` states. Previews are not tests but
they catch the most common Compose regressions (unresolved references,
runtime composition crashes from null-handling bugs) at build time.

## Risks

- **Race vs another device invalidating mid-view.** Acceptable per the
  non-goals; the screen is a snapshot, not a dashboard.
- **`getCachedInsight` blocking.** It is a `POST` to the server (not a
  local cache read). On a slow network, the audit screen may sit in
  `Loading` for several seconds. Mitigation: show a progress indicator
  from the first frame; the call already runs on `Dispatchers.IO` via the
  `AiClient.execute` machinery.
- **PR11 merge conflict in `BookDetailScreen.kt`.** Both PRs touch the
  same `Scaffold { topBar = … }` block. Resolution is mechanical (PR11's
  removal of the regenerate `TextButton` is in the `Column` body, PR6's
  additions are in `TopAppBar.actions`). Call this out in the PR body.

## References

- `.claude/local/quire-ai/2026-05-16-next-deliverables.md` §"PR6 — Insight
  audit UI" and §"PR11 — Drop regenerate".
- `data/ai/src/main/java/io/theficos/ereader/data/ai/AiClient.kt` — existing
  `invalidateInsight` and `getInsight`.
- `app/src/main/java/io/theficos/ereader/ui/bookdetail/BookDetailScreen.kt`
  — current book-detail screen, the integration point for the new overflow.
