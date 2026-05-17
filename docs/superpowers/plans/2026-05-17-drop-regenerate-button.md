# Plan — Drop "Regenerate insights" affordance

> Shipped in f422845 on 2026-05-17 as PR #19.

**Spec:** `docs/superpowers/specs/2026-05-17-drop-regenerate-button-design.md`
**Branch:** `feat/drop-regenerate-button`
**Mode:** TDD-by-deletion (tests removed before implementation; build is the
spec of correctness).

## Steps

### 1. Remove the client test for `regenerateInsight`

- Edit `data/ai/src/test/java/io/theficos/ereader/data/ai/AiClientTest.kt`.
- Delete the `@Test fun \`regenerateInsight sends reason\`` block.
- Verify: `scripts/dgradle :data:ai:testDebugUnitTest` still compiles (it
  will fail until the impl is removed, that is fine; we will re-run after
  step 2).

### 2. Remove the client method + DTO

- Edit `data/ai/src/main/java/io/theficos/ereader/data/ai/AiClient.kt`:
  delete `regenerateInsight()` and its KDoc.
- Edit `data/ai/src/main/java/io/theficos/ereader/data/ai/AiDtos.kt`:
  delete `InsightRegenerateBody`.
- Verify: `scripts/dgradle :data:ai:testDebugUnitTest` green.

### 3. Remove the repository method

- Edit `app/src/main/java/io/theficos/ereader/data/ai/AiRepository.kt`:
  delete `regenerateInsight()` and its KDoc.

### 4. Remove the ViewModel handler

- Edit `app/src/main/java/io/theficos/ereader/ui/bookdetail/BookDetailViewModel.kt`:
  delete `fun regenerate(reason: String)`. The error-mapping `when` inside
  that function uses `AiHttpException` / `AiQuotaException` — those imports
  remain in use by `load()`.

### 5. Remove the UI affordance

- Edit `app/src/main/java/io/theficos/ereader/ui/bookdetail/BookDetailScreen.kt`:
  - Delete the `TextButton(...)` block under `if (state.insight is InsightUiState.Loaded)`.
  - Delete the `regenDialogOpen` state.
  - Delete the trailing `if (regenDialogOpen) { RegenerateDialog(...) }` block.
  - Delete the `RegenerateDialog` composable.
  - Prune now-unused imports (`AlertDialog`, `OutlinedTextField`,
    `mutableStateOf`, `remember`, `setValue` if no other use).

### 6. Verify

- `scripts/dgradle :app:testDebugUnitTest` — green.
- `scripts/dgradle :data:ai:testDebugUnitTest` — green.
- `scripts/dgradle :app:lintDebug` — clean (no new warnings; no orphan
  string references — the screen used inline string literals, no
  `strings.xml` entries existed).

### 7. Commit, push, PR

- Commit subject: `:fire: refactor(android): drop "Regenerate insights" overflow action`
- Body mirrors the spec rationale.
- Push to `origin feat/drop-regenerate-button`.
- Open PR against `main`.

## Criteria

**Clarity:** Every edit lists file + symbol. No ambiguity about scope.

**Verifiability:** Each step ends with a concrete build/test command whose
green status is the success signal.

**Completeness:** Walks the full call chain UI → ViewModel → Repository →
Client → DTO → Test. No reference to `regenerateInsight` will remain in the
Android tree after step 5.

**Big picture:** Server endpoint untouched. PR6's overflow menu (parallel
work) lands on the same screen file but in a different region; conflict is
trivial.

## Rollback

Single commit; `git revert HEAD` restores the button. Server contract
unchanged, so a partial rollback (just the client method) is also safe.
