# PR6 — Insight audit UI: implementation plan

> Shipped in 92ca525 on 2026-05-17 as PR #21.

**Date:** 2026-05-17
**Spec:** `docs/superpowers/specs/2026-05-17-insight-audit-ui-design.md`
**Branch:** `feat/insight-audit-ui`
**Effort:** Short (~3h)
**Deps:** PR-A (mode flags) — shipped.

## Scope

Add a `InsightAuditScreen` reachable from a new book-detail overflow menu
("Inspect insight"). The screen displays the cached
`BookInsightResponse` metadata and lets the user invalidate it. No
regenerate button; no new server endpoint; no DTO mutation.

## Inventory of touched files

### New
- `app/src/main/java/io/theficos/ereader/ui/bookdetail/InsightAuditScreen.kt`
- `app/src/main/java/io/theficos/ereader/ui/bookdetail/InsightAuditViewModel.kt`
- `app/src/test/java/io/theficos/ereader/ui/bookdetail/InsightAuditViewModelTest.kt`

### Modified
- `app/src/main/java/io/theficos/ereader/ui/bookdetail/BookDetailScreen.kt`
  — add overflow menu with "Inspect insight"; thread an `onInspectInsight`
  lambda from the caller; read post-invalidate `savedStateHandle` flag and
  re-trigger `viewModel.retry()` on resume.
- `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`
  — add `book/{id}/inspect-insight` composable route; pass
  `onInspectInsight` to `BookDetailScreen`.
- `app/src/main/java/io/theficos/ereader/di/AppContainer.kt`
  — add `insightAuditViewModelFactory` mirroring the existing
  `bookDetailViewModelFactory` pattern.

### Untouched
- Server (`server/`) — zero changes.
- `data/ai/` DTOs — already contain the fields we display.
- `data/ai/` `AiRepository` / `AiClient` — already expose `getInsight` and
  `invalidateInsight`.

## Step-by-step

### Step 1 — Spec + plan committed first

Commit the spec and this plan in their own commit so subsequent
implementation commits are reviewable diffs on top of an agreed design.

Verification: `git log --oneline -1` shows `:memo: docs: PR6 insight
audit UI spec + plan`.

### Step 2 — Failing ViewModel test

Write `InsightAuditViewModelTest.kt` covering:

- `loads cached insight into Loaded state`
- `surfaces NotCached when repository returns null`
- `surfaces Error on network failure`
- `invalidate success transitions to Done and emits Done event`
- `invalidate failure stays in Loaded and emits Error event`
- `style snapshot reflects current AiPreferences at load time`

Use Robolectric for `Dispatchers.setMain(UnconfinedTestDispatcher())`
mirror, and a hand-rolled `FakeAiRepository` that returns canned
`BookInsightResponse`s and throws on demand. The real `AiRepository`
exposes its members as suspend funs over an internal `AiClient`; the
ViewModel will depend on a narrow interface
`InsightAuditViewModel.Source` (declared in the same file) to keep tests
hermetic without a mocking library.

Verification: run the test file once before any production code exists →
compilation fails (intentional: drives the API surface).

### Step 3 — `InsightAuditViewModel`

Implement the ViewModel:

```kotlin
class InsightAuditViewModel(
    private val documentId: Long,
    private val documents: DocumentRepository,
    private val ai: AiRepository,
) : ViewModel() {

    sealed interface State {
        data object Loading : State
        data class Loaded(
            val identity: DocumentIdentity,
            val response: BookInsightResponse,
            val currentStyle: AiStyle?,
        ) : State
        data object NotCached : State
        data class Error(val message: String) : State
        data object Invalidating : State
        data object Done : State
    }

    sealed interface Event {
        data object Invalidated : Event
        data class InvalidateFailed(val message: String) : Event
    }

    val state: StateFlow<State>
    val events: SharedFlow<Event>

    fun retry()
    fun invalidate()
}
```

Implementation notes:

- `init { load() }` issues `documents.findById` then `ai.getCachedInsight`,
  capturing `ai.preferences.value?.style` in the resulting `Loaded` state.
- `retry()` re-runs the same flow from `Loading`.
- `invalidate()` requires current state to be `Loaded`; sets `Invalidating`,
  calls `ai.invalidate(identity)`, emits `Invalidated` event and sets
  `Done` on success; emits `InvalidateFailed(...)` and reverts to the
  prior `Loaded` state on failure. **Race handling (architect note):**
  if the underlying call surfaces a 404 (row already evicted by another
  device between this screen's open and the user's tap), treat that as
  success — the user's intent ("there should be no cache row for this
  book") is satisfied. `AiClient.invalidateInsight` currently throws
  `AiHttpException(404)` for 404; the VM swallows the 404 and proceeds
  to `Done`.
- Error message strings mirror the existing patterns in
  `BookDetailViewModel.regenerate` (quota → "today's regeneration limit";
  http → "(code)"; generic → "Couldn't invalidate.").

Verification: test from Step 2 turns green.

### Step 4 — `InsightAuditScreen`

Compose the screen:

- `Scaffold` with `TopAppBar(title = "Inspect insight", navigationIcon = Back)`.
- `LaunchedEffect(state)` collects events; uses a `SnackbarHostState` for
  the `InvalidateFailed` event; on `Done` calls a passed-in `onDone()`
  lambda (which the nav-graph wires to popBackStack + savedStateHandle
  flag set).
- A `LaunchedEffect(state)` reading the `Done` state also calls `onDone()`
  for the success path (so the test surface stays purely on the VM).
- Body branches on `State`:
  - `Loading` → centered `CircularProgressIndicator`.
  - `Loaded` → vertical `Column` with three `QuireCard` sections (Cache
    key, Generated, Sources) and an `OutlinedButton("Invalidate cached
    insight")` at the bottom.
  - `NotCached` → centered text + back affordance.
  - `Error` → centered text + Retry button → `viewModel.retry()`.
  - `Invalidating` → overlay or in-place disabled button + spinner.
  - `Done` → covered by the `LaunchedEffect` above; render nothing
    (transient state immediately before pop).
- A `RememberSaveable` boolean `showConfirm` drives an `AlertDialog`
  with the copy from the spec:
  > "Invalidating this insight removes the cached AI response. The next
  > time you open this book, a fresh insight will be generated (counts
  > against your daily AI budget)."

Source-row rendering reuses the kind→label mapping from
`InsightCards.kt::SourcesFooter`. Extract the mapping into a small
internal helper (`fun Citation.label(): String` + `fun Citation.url():
String?`) inside `InsightCards.kt` so both screens share it without
duplication.

**Tone / language rendering — architect callout.** Do NOT label tone /
language as cached metadata. Render them under a separate "Your current
AI style" card sourced from `AiRepository.preferences.value?.style`. If
preferences are null, show "—" for both. No "matches preferences" hint.

**Confirmation dialog copy** (architect-revised):

> Invalidating this insight removes the cached AI response. Returning to
> this book detail will generate a fresh insight, which may take a few
> seconds and uses one of your daily generations.

Verification: APK builds; preview composables render without crash.

### Step 5 — Wire into book detail

In `BookDetailScreen.kt`:

1. Replace the existing `Scaffold(topBar = { TopAppBar(…) })` so the bar
   exposes `actions = { IconButton + DropdownMenu }`.
2. Thread an `onInspectInsight: () -> Unit` parameter into the
   `BookDetailScreen` signature.
3. Read post-invalidate flag from `savedStateHandle` in a
   `LaunchedEffect(Unit)` keyed on the result key; on observation, call
   `viewModel.retry()` and clear the flag.

In `AppNavGraph.kt`:

1. Pass `onInspectInsight = { nav.navigate("book/$id/inspect-insight") }`
   into `BookDetailScreen`.
2. Add new `composable("book/{id}/inspect-insight", …)` block that
   creates `InsightAuditViewModel` from the factory and constructs
   `InsightAuditScreen` with `onBack = { nav.popBackStack() }` and
   `onDone = { entry -> entry.savedStateHandle… ; nav.popBackStack() }`.

In `AppContainer.kt`:

1. Add `insightAuditViewModelFactory: InsightAuditViewModelFactory =
   InsightAuditViewModelFactory(documentRepository, aiRepository)` and
   the small factory class at the bottom of the file.

Verification: `scripts/dgradle :app:assembleDebug` clean.

### Step 6 — GPT review

Send spec + plan to the architect prompt. Capture the verdict. Apply any
must-fix items.

Specific questions to ask:

1. Is option (a) — pass `Long` documentId, resolve to identity in the VM
   — the right call given the existing nav pattern, or should we
   serialize `DocumentIdentity` into nav args for symmetry with future
   catalog-detail (PR7) which has no local Long?
2. Confirmation dialog copy: is "counts against your daily AI budget"
   the clearest framing? Alternatives: "uses one of your daily
   generations" / "the next open may take a few seconds" / silence on
   cost entirely.
3. Behaviour when the cache row is invalidated by another device
   between screen open and the user tapping Invalidate. Current spec
   says "show snapshot, no refresh". Should we re-fetch on resume?
4. Tappable URLs: `LocalUriHandler.openUri` (Chrome Custom Tab on
   modern Android) vs an explicit `Intent.ACTION_VIEW`. Any F-Droid
   surface concern?

Cap: 3 rounds.

### Step 7 — Verify

Run, in order:

```bash
scripts/dgradle :app:testDebugUnitTest
scripts/dgradle :data:ai:testDebugUnitTest
scripts/dgradle :app:lintDebug
```

All three must be green.

### Step 8 — Commit + push + PR

- Single feature commit on top of the spec/plan commit:
  `:sparkles: feat: insight audit UI`.
- `git push -u origin feat/insight-audit-ui`.
- `gh pr create --base main --head feat/insight-audit-ui --title 'feat:
  insight audit UI' --body @body.md`.
- Body sections: Summary, Screen mock (ASCII from spec), Navigation
  wiring, Test plan, GPT verdict, Coordination notes (PR11 overlap),
  No Claude attribution.

## Definition of done

- [ ] Spec + plan committed in their own commit.
- [ ] `InsightAuditViewModel` + tests passing.
- [ ] `InsightAuditScreen` builds, previews render, manual smoke clean.
- [ ] Book-detail overflow exposes "Inspect insight".
- [ ] `scripts/dgradle :app:testDebugUnitTest` green.
- [ ] `scripts/dgradle :data:ai:testDebugUnitTest` green.
- [ ] `scripts/dgradle :app:lintDebug` clean.
- [ ] No regenerate button anywhere in PR diff.
- [ ] GPT verdict captured in the PR body.
- [ ] PR opened against `main`. URL recorded.

## Out of scope

- Adding Compose UI test infrastructure.
- Mutating `BookInsightResponse` to include `tone` / `language`.
- Active "diff against current preferences" highlighting.
- Auto-refresh-on-resume of the audit screen.
- Migration / server endpoint work of any kind.

## Risks

1. **Conflict with PR11.** Likely mechanical in `BookDetailScreen.kt`.
   Resolution: keep PR6's `TopAppBar.actions` block; drop PR11's
   removed `RegenerateDialog` and `Not quite right?` `TextButton`.
2. **Robolectric flakiness from `Dispatchers.setMain`.** Mitigated by
   the pattern already used in `LibraryViewModelTest`
   (`UnconfinedTestDispatcher`).
3. **Stale cached row mid-view.** Acceptable per non-goals; documented.

## GPT architect verdict

> **APPROVE with one copy tweak.** Design fits existing Android patterns
> and stays within the Android-only / F-Droid constraints.
>
> - **Q1 nav arg.** Pass local `Long documentId`. Matches existing
>   `book/{id}` / `reader/{docId}` routes; keeps identity resolution in
>   the VM; avoids encoding nullable identity fields into nav args. PR7
>   can solve catalog-detail separately when it actually lacks a local
>   row.
> - **Q2 dialog copy.** Keep the cost warning but say "uses one of your
>   daily generations" instead of "counts against your daily AI
>   budget". Recommended: "Invalidating this insight removes the cached
>   AI response. Returning to this book detail will generate a fresh
>   insight, which may take a few seconds and uses one of your daily
>   generations." → **applied**.
> - **Q3 stale-row race.** Keep snapshot-only; do not re-fetch on
>   `ON_RESUME`. Resume refresh adds network churn and still cannot
>   close the tap-time race. If invalidate returns 404, treat that as
>   success. → **applied** (404 swallowed as success in the VM).
> - **Q4 tappable URLs.** Use `LocalUriHandler.current.openUri(url)`.
>   Matches existing `SourcesFooter`; adds no app HTTP path or manifest
>   permission.
> - **Risk callout.** Do not label tone/language as cached metadata or
>   "matches preferences"; the DTO does not support it. → **applied**;
>   spec now renders them under a separate "Your current AI style"
>   card sourced from live `AiPreferences.style`.
