# Plan — AI insight language preference (PR4)

> Shipped in e3ab148 on 2026-05-16 as PR #12.

**Date:** 2026-05-16
**Spec:** `docs/superpowers/specs/2026-05-16-ai-language-preference-design.md`
**Branch:** `feat/ai-language-preference` (stacked on `feat/ai-generation-log`)
**Status:** Ready to execute

## Sequencing rationale

Server-side first, in dependency order: schema → model → Pydantic → prompt → orchestrator → tests. Android-side parallelizable once server contract is stable (the kotlinx DTO only needs to mirror the Pydantic schema). Each step is TDD: write the failing test, run it red, implement, run it green.

## Step 1 — Server schema substrate

- [ ] **Test (red):** Add `test_language_column_exists_on_book_insights` to `server/tests/integration/test_schema.py`. Inspect `book_insights` and assert `language` column present, NOT NULL, server_default `'auto'`.
- [ ] **Test (red):** Add `test_book_insights_unique_indexes_include_language` to `server/tests/integration/test_schema.py`. Inspect indexes and assert both `uq_book_insights_*_tone_language` indexes exist with `language` in the column list.
- [ ] **Test (red):** Update `test_ai_generation_log_round_trip` if needed (it constructs a BookInsight; add `language="auto"` if Pydantic requires it — actually the ORM model has a `default="auto"` so no change needed).
- [ ] **Impl:** Write `server/migrations/versions/ai_002_insight_language.py`:
  - `revision = "ai_002"`, `down_revision = "ai_001"`, `branch_labels = None`.
  - `upgrade()`: add column with server_default, drop old indexes, create new indexes.
  - `downgrade()`: inverse.
- [ ] **Impl:** Update `opds_sync/db/models.BookInsight` to add `language` column adjacent to `tone`.
- [ ] **Run:** `cd server && uv run pytest -v tests/integration/test_schema.py` — green.
- [ ] **Run:** `cd server && uv run pytest -v tests/integration/test_cache_key_audit.py` — green (regression: `language` is a cache-key column, not tenant).

## Step 2 — Pydantic AiStyle

- [ ] **Test (red):** Update `test_style_only_tone_remains` → `test_style_has_tone_and_language`:
  - `AiStyle().tone == "neutral"`
  - `AiStyle().language == "auto"`
  - `list(AiStyle.model_fields.keys()) == ["tone", "language"]`
- [ ] **Test (red):** Add `test_style_accepts_iso_639_1_languages` parametrized over `en, it, es, fr, de, pt, nl, zh, ja`.
- [ ] **Test (red):** Add `test_style_rejects_invalid_language` parametrized over `("invalid", "english", "", "EN", "e", "eng")`.
- [ ] **Impl:** Update `opds_sync/api/ai_schemas.AiStyle`:
  - Add `language: str = "auto"`.
  - Add `_ISO_639_1` frozenset module-level constant (~180 codes).
  - Add `@field_validator("language")` that allows `"auto"` and rejects anything not in `_ISO_639_1`.
- [ ] **Run:** `cd server && uv run pytest -v tests/unit/test_ai_schemas.py` — green.

## Step 3 — Prompt composition

- [ ] **Test (red):** Update `test_prompt_version_is_v2` → `test_prompt_version_is_v3`: `assert PROMPT_VERSION == "3"`.
- [ ] **Test (red):** Add `test_language_clause_emitted_when_non_auto`:
  ```python
  text = compose_user_prompt(bundle, citations=[], style=AiStyle(language="it"))
  assert 'ISO 639-1 code "it"' in text
  ```
- [ ] **Test (red):** Add `test_language_clause_omitted_when_auto`:
  ```python
  text_no_style = compose_user_prompt(bundle, citations=[])
  text_auto = compose_user_prompt(bundle, citations=[], style=AiStyle(language="auto"))
  text_explicit_neutral = compose_user_prompt(bundle, citations=[], style=AiStyle())
  assert text_no_style == text_auto == text_explicit_neutral
  ```
- [ ] **Impl:** Update `opds_sync/core/ai/prompts.py`:
  - `PROMPT_VERSION = "3"`.
  - In `compose_user_prompt`, after the tone block, emit `'Respond in the language identified by ISO 639-1 code "{language}".'` only when `style is not None and style.language != "auto"`.
- [ ] **Run:** `cd server && uv run pytest -v tests/unit/test_ai_prompts.py` — green.

## Step 4 — Orchestrator threading

- [ ] **Test (red):** Add `test_different_languages_generate_separate_cache_rows` to `test_ai_service.py` — mirror of `test_different_tones_*`, with `style=AiStyle(language="auto")` vs `AiStyle(language="it")`, assert 2 rows + 2 AI calls.
- [ ] **Test (red):** Add `test_same_language_shares_cache_across_users` — two users same identity same language → 1 AI call.
- [ ] **Test (red):** Add `test_tone_and_language_orthogonal` — (tone=neutral, language=auto) vs (tone=neutral, language=it) → 2 separate rows; ensure tone-only test still passes.
- [ ] **Impl:** `opds_sync/core/ai/service.py`:
  - Add `_language_of(style: AiStyle | None) -> str` returning `style.language if style else "auto"`.
  - In `get`, `generate`, `regenerate`: compute `language = _language_of(style)` next to `tone = _tone_of(style)`.
  - Update `_acquire_identity_lock` signature: add `language: str` param. Key: `f"{ident.metadata_id or ident.content_hash}|{tone}|{language}"`.
  - Update `_cache_lookup`: add `language: str` param. Both queries gain `BookInsight.language == language`.
  - In `_do_generate`: write `language=language` onto the new row; add `language: str` param.
- [ ] **Run:** `cd server && uv run pytest -v tests/unit/test_ai_service.py` — green (existing tone tests still pass).

## Step 4.5 — Update infrastructure tests that hardcode `ai_001` as ai@head

GPT review (architect) flagged this as critical: tests written against PR-C's world hardcode `ai_001` as the leaf of the ai branch. After `ai_002` lands, the leaf shifts and those tests must be updated.

- [ ] **Impl:** `server/tests/integration/test_health.py` — change `assert body["heads_applied"] == ["ai_001"]` → `["ai_002"]`.
- [ ] **Impl:** `server/tests/integration/test_readyz_migration_state.py` — same: `ai_001` → `ai_002` in `heads_applied` and `missing` assertions.
- [ ] **Impl:** `server/tests/integration/test_migrate_script.py`:
  - `assert versions == {"ai_001"}` → `{"ai_002"}` (both occurrences in `test_default_state_*` and `test_idempotent_*`).
  - Synthetic test revisions chain off `ai_002`: rename `ai_test_002` → `ai_test_003`; `down_revision = "ai_001"` → `"ai_002"`. Update both `test_synthetic_ai_branch_upgrades_when_enabled` and `test_synthetic_ai_branch_skipped_when_disabled`.
  - In `test_synthetic_ai_branch_skipped_when_disabled`: `_downgrade_in_thread(real_cfg, "0004")` still works (downgrades through `ai_002` then `ai_001`). The "ai branch skipped" assertion now needs to check `ai_002` not applied too: `assert "ai_002" not in versions` and `assert "ai_001" not in versions`.
- [ ] **Impl:** `server/tests/unit/test_migrate_logic.py` — synthetic stub revision graphs that mention `ai_001` (label-detection only, so no chain through `ai_002` required). Verify no assertion mentions a specific revision id; if they do, only the label-detection paths matter — leave them as-is unless tests fail.

## Step 4.6 — Extra regression tests (GPT nits)

- [ ] **Test:** Add `test_style_rejects_unknown_iso_code` parametrized over `("zz", "xx", "qq")` — codes that pass `^[a-z]{2}$` but aren't ISO 639-1. Proves the validation is allowlist, not regex.
- [ ] **Test:** Add `test_invalidate_does_not_touch_old_prompt_version_rows` in `test_ai_service.py`. Insert a synthetic `BookInsight` with `prompt_version="2"` (old, neutral, generated_by=…), call `orch.invalidate(ident)` with the v3 orchestrator, assert the old row is preserved.

## Step 5 — Server full sweep

- [ ] **Run:** `cd server && uv run pytest -v` — full green across all three CI modes (full / sync_only / ai_only via env matrix). Sync-only run skips `requires_ai` tests; AI-only run skips `requires_progress` tests; full runs all.

## Step 6 — Android DTO

- [ ] **Test (red):** Update `AiClientTest.kt`:
  - `getPreferences parses style`: change fixture body to `"""{"ai_enabled":true,"style":{"tone":"scholarly","language":"it"}}"""` and assert `prefs.style.language == "it"`.
  - Add new test `setPreferences with style includes language`: enqueue, call `setPreferences(style = AiStyle(language = "es"))`, assert request body contains `"language":"es"`.
  - Add test `getPreferences default language is auto when missing` (legacy server): fixture body without `language` key, assert `prefs.style.language == "auto"`.
- [ ] **Impl:** Update `data/ai/.../AiDtos.kt`:
  ```kotlin
  data class AiStyle(
      val tone: String = "neutral",
      val language: String = "auto",
  )
  ```
- [ ] **Run:** `scripts/dgradle :data:ai:testDebugUnitTest` — green.

## Step 7 — AiRepository

- [ ] **Impl:** Update `app/.../AiRepository.kt`:
  - Fix `setStyleTone` to preserve `language` (copy from current style).
  - Add `setStyleLanguage(language: String)` that preserves `tone`.
- [ ] (No dedicated unit test for AiRepository — it's a thin facade; behavior is covered by SettingsViewModel + compose tests.)

## Step 8 — SettingsViewModel + Screen

- [ ] **Impl:** Add `setStyleLanguage(language: String)` to `SettingsViewModel.kt`.
- [ ] **Impl:** Update `SettingsScreen.kt`:
  - After the tone Column, add a "Insight language" Column with the same DropdownMenu pattern.
  - Options list: `LANGUAGE_OPTIONS` constant with (code, label) pairs.
  - `onClick`: `viewModel.setStyleLanguage(option.first)`.
- [ ] **Test (red → green):** Add compose UI test for the language dropdown. Locate existing tone dropdown test pattern and mirror it. If no compose UI test exists for tone (the spec mentions one; verify), at minimum add a junit test that asserts `viewModel.setStyleLanguage("it")` propagates through `aiRepository.setStyleLanguage("it")` (interaction test with a fake repo).
- [ ] **Run:** `scripts/dgradle :app:testDebugUnitTest` — green.

## Step 9 — Docs

- [ ] **Impl:** Update `docs/sync-api.md`:
  - `AiStyle` preferences body example shows `"language": "auto"`.
  - Cache-key note: `(metadata_id|content_hash, model_id, prompt_version, tone, language)`.
  - Language paragraph: accepted values, validation rule, behavior at `"auto"`.

## Step 10 — Verify, commit, push, PR

- [ ] **Run:** `cd server && uv run pytest -v` — full green.
- [ ] **Run:** `scripts/dgradle :data:ai:testDebugUnitTest :app:testDebugUnitTest` — full green.
- [ ] **Run:** `cd server && uv run ruff format` + `uv run ruff check --fix` — clean.
- [ ] **Commit:** Single commit, conventional gitmoji:
  - `:sparkles: feat: AI insight language preference`
  - No `Co-Authored-By` trailer.
- [ ] **Push:** `git push -u origin feat/ai-language-preference`.
- [ ] **PR:** `gh pr create --base feat/ai-generation-log --head feat/ai-language-preference` with body containing summary, what-changed, test plan, cache-version checklist, and GPT review summary. No Claude attribution.

## Risks already considered (no follow-up needed)

- `_style_from_pref` tolerates evolving schemas via `model_fields` — already handles new `language` key automatically.
- Migration backfill: `server_default='auto'` covers existing rows; no explicit `UPDATE` needed.
- Cache-key audit test: `language` is a *cache-key* dimension, not a *tenant* dimension, so the audit allowlist is unchanged.
