# PR11 — Drop "Regenerate insights" overflow action

> Shipped in f422845 on 2026-05-17 as PR #19.

**Date:** 2026-05-17
**Branch:** Android-only (server endpoint retained for invalidate-only use by PR6)
**Effort:** Quick
**Deps:** none. Lands any time.
**Status:** Draft — ready for review

## Goal

Remove the in-app "Regenerate" affordance from the book-detail screen. The
server-side `POST /ai/v1/insights/regenerate` endpoint stays — it remains
available to cluster admin tooling and future flows (e.g., the PR6 audit UI
exposes the existing `invalidate` endpoint, not regenerate).

The current implementation in `app/.../bookdetail/BookDetailScreen.kt` is a
`TextButton` reading "Not quite right? Regenerate" plus a `RegenerateDialog`
that captures a free-text reason and POSTs to `/ai/v1/insights/regenerate`.
(The deliverables note describes the affordance as an overflow
`DropdownMenuItem`; that menu does not yet exist on `main`. PR6 adds it. We
delete the button + dialog that exist today.)

## Why now

The regenerate flow issues a forced re-generation with the *same* cache key,
which is wasteful and adds essentially no UX value:

- Legitimate "I want different output" cases (tone, language, model_id,
  prompt_version changes) already invalidate the cache key naturally and
  regenerate via the normal `lookup` path. No explicit user action needed.
- The only remaining use case ("the answer is bad, give me a different one
  with the same parameters") is better served by invalidate-and-reload — one
  AI call instead of two (`DELETE` then `POST`), and the user gets the same
  outcome.
- PR6's audit UI surfaces the server-side `invalidate` endpoint as the
  single, principled way to evict a cached insight. We do not want two ways
  to do this from the app.

## Scope

### Remove

- `BookDetailScreen.kt`: the `TextButton("Not quite right? Regenerate")`, the
  `RegenerateDialog` composable, the `regenDialogOpen` state, and the
  associated dialog block.
- `BookDetailViewModel.kt`: `regenerate(reason: String)` and its supporting
  imports (only those that become unused).
- `AiRepository.kt`: `regenerateInsight()`.
- `AiClient.kt`: `regenerateInsight()` and `InsightRegenerateBody`
  reference. (The `InsightRegenerateBody` data class itself is unused once
  the client method is removed — delete it from `AiDtos.kt`.)
- `AiClientTest.kt`: `regenerateInsight sends reason` test.

### Keep

- Server `POST /ai/v1/insights/regenerate` endpoint (untouched, separate
  repo path).
- `POST /ai/v1/insights/invalidate` plus `AiRepository.invalidate()` and
  `AiClient.invalidateInsight()`. These are PR6's substrate.
- All `BookInsight` payload fields (no schema change). Lineage fields like
  `regenerated_from` on the server live in the audit log, not in this PR's
  surface area.
- `regen_daily_limit` field on `AiConfig` — still relevant for admin tooling
  and future surfaces; removing it would be a breaking server-contract
  change that is out of scope.

### Out of scope

- Any server-side change.
- Touching the AI Settings screen.
- Removing the "regeneration limit" copy from `lookup` error messages — the
  budget still applies to admin/cluster regenerate calls and surfaces here
  via 429.

## Tests

- Update `AiClientTest`: delete `regenerateInsight sends reason`. No
  inverted assertion needed — the API is gone; absence is enforced by the
  type system.
- No Compose UI test currently exists for `BookDetailScreen`. Adding one
  purely to assert the absence of the button is gold-plating; the
  compile-time absence of `viewModel.regenerate(...)` is sufficient.
  Concurrent PR6 adds the overflow menu and will own that test surface.

## Migration / rollout

None. Pure code deletion. Users mid-dialog when they update lose the
unsubmitted reason (acceptable — it never persists).

## Risk / blast radius

- **Merge with PR6:** PR6 (book-detail "Inspect insight" overflow) edits the
  same `BookDetailScreen.kt` to add an overflow `Menu`. PR11 removes the
  separate `TextButton` + dialog block. The two edits are in different
  regions of the file — small conflict surface, mechanical to resolve.
- **Other call sites:** none. Grep confirms `regenerateInsight` is only
  referenced from the call chain listed above.
- **Server endpoint:** unaffected. Calling it still works for anyone with
  basic-auth credentials and a script.
