# Spec — AI insight language preference (PR4)

**Date:** 2026-05-16
**Branch:** `feat/ai-language-preference` (stacked on `feat/ai-generation-log`)
**Roadmap reference:** `.claude/local/quire-ai/2026-05-16-next-deliverables.md` §PR4
**Template:** PR #9 (`feat: book-insight schema v2 + tone-keyed cache`)
**Status:** Draft for architect review

## 1. Motivation

Today the AI insight cache key is `(metadata_id|content_hash, model_id, prompt_version, tone)`. Every cached row is implicitly English: the prompts contain only English instructions and no language constraint, so non-English readers get English insights regardless of what they were reading. The roadmap calls out PR4 as the ship-first priority because once any new cache rows are seeded (PR-C just landed; PR1/PR2/PR3 are queued), they become English-only by accident and have to be re-generated when language support lands.

This PR adds a `language` dimension to the cache key, mirroring the tone implementation from PR #9. `auto` (default) preserves today's behavior byte-for-byte; an explicit ISO-639-1 code emits `"Respond in {language}."` to the model and gets its own cache row.

## 2. Server changes

### 2.1 Migration `ai_002_insight_language`

Second migration on the `ai` branch. Per PR-A convention:

```python
revision = "ai_002"
down_revision = "ai_001"
branch_labels = None        # PR-C already owns the "ai" label on ai_001
depends_on = None
```

Upgrade:

1. Add `language` column to `book_insights`: `String`, `NOT NULL`, `server_default = 'auto'`. Existing rows backfill to `'auto'` via the server default.
2. Drop the two existing tone-keyed partial unique indexes:
   - `uq_book_insights_content_hash_model_prompt_tone`
   - `uq_book_insights_metadata_id_model_prompt_tone`
3. Recreate both with `language` appended:
   - `uq_book_insights_content_hash_model_prompt_tone_language` on `(content_hash, model_id, prompt_version, tone, language)`, `WHERE superseded_at IS NULL`.
   - `uq_book_insights_metadata_id_model_prompt_tone_language` on `(metadata_id, model_id, prompt_version, tone, language)`, `WHERE metadata_id IS NOT NULL AND superseded_at IS NULL`.

Downgrade is the inverse: drop the language-keyed indexes, recreate the tone-keyed ones, drop the column.

### 2.2 ORM model

`opds_sync/db/models.BookInsight` gets a new column:

```python
language: Mapped[str] = mapped_column(
    String, nullable=False, server_default=text("'auto'"), default="auto"
)
```

Placed adjacent to `tone` so the cache-key dimensions cluster in source.

### 2.3 Pydantic schema

`opds_sync/api/ai_schemas.AiStyle`:

```python
class AiStyle(BaseModel):
    model_config = ConfigDict(extra="forbid")

    tone: Literal["neutral", "enthusiastic", "scholarly", "casual"] = "neutral"
    language: str = "auto"

    @field_validator("language")
    @classmethod
    def _validate_language(cls, v: str) -> str:
        if v == "auto":
            return v
        if not _is_iso_639_1(v):
            raise ValueError(
                "language must be 'auto' or a lowercase ISO 639-1 code (e.g. 'en', 'it')"
            )
        return v
```

`_is_iso_639_1` accepts any two-letter lowercase string from the ISO 639-1 set. We use the controlled list `{"aa", "ab", ..., "zu"}` shipped as a frozen set at module level (~180 codes). Anything else → 422.

Doc-comment on `AiStyle` is updated to:

> `tone` and `language` are the cache-key knobs: they participate in `book_insights.tone` / `book_insights.language` so users with different combinations get separately-cached generations rather than one bleeding into the other.

### 2.4 Prompt composition

`opds_sync/core/ai/prompts.py`:

- `PROMPT_VERSION` bumps **`"2"` → `"3"`**. PR-C did not bump it; this PR does because the prompt body changes (the new "Respond in" clause shifts model output for non-auto languages, and even if all current users stay on `auto`, the cache-key dimension changes mean PR3/PR-A audits of `(model_id, prompt_version, tone, language)` need a fresh substrate).
- `compose_user_prompt` emits a single new line **only when `style.language != "auto"`**:

```python
if style is not None and style.language != "auto":
    lines.append("")
    lines.append(
        f'Respond in the language identified by ISO 639-1 code "{style.language}".'
    )
```

Wording avoids ambiguity for valid 2-letter codes that resemble English words (`it`, `is`, `no`, `as`, `or`).

When `language == "auto"` the prompt body is byte-for-byte identical to today's (modulo the cache-key dimension being added), so no per-token cost for the 99% case.

The `_TONE_HINT` block continues to be emitted independently of `language`.

### 2.5 Orchestrator

`opds_sync/core/ai/service.py`:

- New helper `_language_of(style: AiStyle | None) -> str` mirroring `_tone_of`.
- `_acquire_identity_lock` key becomes:

```python
key = f"{ident.metadata_id or ident.content_hash}|{tone}|{language}"
```

- All `_cache_lookup` calls thread `language` through, alongside `tone`. The lookup queries gain a `BookInsight.language == language` clause in both the metadata_id and content_hash paths.
- `_do_generate` writes `language=language` onto the new `BookInsight` row.
- Method signatures: `get`, `generate`, `regenerate` keep the same `style: AiStyle | None` parameter — `language` is derived from `style`, not a separate kwarg. This matches how `tone` is threaded.

### 2.6 `_style_from_pref` (router)

`opds_sync/api/ai.py::_style_from_pref` already uses `model_fields` to filter unknown keys, so no change is needed beyond the schema addition: `language` will automatically flow through stored prefs and through the filter.

## 3. Android changes

### 3.1 `AiDtos.kt`

```kotlin
@Serializable
data class AiStyle(
    val tone: String = "neutral",
    val language: String = "auto",
)
```

Default-valued constructor parameter preserves wire compatibility for older server responses that don't include `language` (kotlinx.serialization keeps the default).

### 3.2 `AiRepository.kt`

Add `setStyleLanguage`:

```kotlin
suspend fun setStyleLanguage(language: String) {
    val current = _prefs.value?.style ?: AiStyle()
    val out = client.setPreferences(style = current.copy(language = language))
    _prefs.value = out
}
```

Note: this preserves the user's tone when changing language (and vice-versa for `setStyleTone`, which we should also update). PR #9's `setStyleTone` overwrites style with `AiStyle(tone = ...)` and drops other knobs — that's an actual papercut because changing tone now nukes language. We fix `setStyleTone` here too.

### 3.3 `SettingsViewModel.kt`

```kotlin
fun setStyleLanguage(language: String) {
    viewModelScope.launch {
        runCatching { aiRepository.setStyleLanguage(language) }
    }
}
```

### 3.4 `SettingsScreen.kt`

A new "Insight language" Column placed below the existing "Insight tone" Column. Same `DropdownMenu` pattern with options:

```kotlin
private val LANGUAGE_OPTIONS = listOf(
    "auto" to "Auto (no constraint)",
    "en" to "English",
    "it" to "Italiano",
    "es" to "Español",
    "fr" to "Français",
    "de" to "Deutsch",
    "pt" to "Português",
    "nl" to "Nederlands",
)
```

Label uses the native language name so users in the target locale recognize it; the dropdown trigger displays the same label. English UI strings around the dropdown ("Insight language", "What language the model writes in.") are fine for now because the app isn't localized.

### 3.5 Tests

- `data/ai/.../AiClientTest.kt`: fixture JSON updated to include `"language":"en"` in at least one preferences response; assertions added that `AiStyle.language` round-trips and that `setPreferences(style = AiStyle(language = "it"))` sends `"language":"it"` in the request body.
- New compose UI test in `app` settings: open the language dropdown, click `it`, verify `viewModel.setStyleLanguage("it")` was invoked. Mirrors the existing tone-dropdown test (locate via the same pattern).

## 4. Docs

`docs/sync-api.md`:

- AiStyle table extended:
  > `style.language` (string, default `"auto"`). Accepts `"auto"` plus any ISO 639-1 code. Participates in the cache key alongside `tone`.
- Cache-key note updated:
  > `book_insights` unique key is `(metadata_id|content_hash, model_id, prompt_version, tone, language)`.
- Example body:
  ```json
  {
    "ai_enabled": true,
    "style": { "tone": "neutral", "language": "auto" }
  }
  ```

## 5. Cache-version checklist (per roadmap)

- [x] `PROMPT_VERSION` bumped `"2" → "3"`.
- [N/A] `BookInsightPayload.schema_version` — payload shape unchanged.
- [x] Both unique indexes extended to include `language`.
- [x] `_acquire_identity_lock` key includes `tone` AND `language`.
- [x] Android `AiStyle` deserializes `language` with `"auto"` default for old responses.
- [x] Invalidate stays cache-key-agnostic at the orchestrator level (current `invalidate` deletes by `(model_id, prompt_version, identity)` — that's still correct; the new prompt_version means PR4 invalidate doesn't touch old `prompt_version="2"` rows, which is what we want).
- [x] Tests cover: cache hit at new key; two languages → two rows; `auto` keeps the prompt body byte-identical; 422 on invalid language.
- [x] `docs/sync-api.md` updated.

## 6. Tests

### 6.1 Unit (`server/tests/unit/`)

- `test_ai_schemas.py`:
  - `test_style_language_defaults_to_auto`
  - `test_style_accepts_iso_639_1_languages` (parametrize over en/it/es/fr/de/pt/nl/zh/ja)
  - `test_style_rejects_invalid_language` (422 / ValidationError on `"invalid"`, `"english"`, `""`, `"EN"`, `"e"`, `"eng"`)
  - Update `test_style_only_tone_remains` → `test_style_has_tone_and_language` (model_fields keys = `["tone", "language"]`).

- `test_ai_prompts.py`:
  - `test_language_clause_emitted_when_non_auto`
  - `test_language_clause_omitted_when_auto` (prompt body byte-equal to default).
  - Update `test_prompt_version_is_v3` (was `_v2`).

- `test_ai_service.py`:
  - `test_different_languages_generate_separate_cache_rows` — same identity + same tone + two languages → two rows, two AI calls.
  - `test_same_language_shares_cache_across_users` (mirror of existing `test_same_tone_shares_cache_across_users`).
  - `test_tone_and_language_orthogonal` — neutral/auto and neutral/it produce separate rows; existing tone tests still pass.

### 6.2 Integration (`server/tests/integration/`)

- `test_cache_key_audit.py` — no change needed; the parametrized check stays green because `language` is a cache-key column, not a tenant column.
- `test_schema.py` — add a check that `language` column exists on `book_insights` and that the new unique indexes cover it.
- The /ai router test (if present for preferences) — add a `language="invalid"` 422 case via FastAPI's PUT /preferences.

### 6.3 Android

- `data/ai/.../AiClientTest.kt` — fixture + assertions per §3.5.
- `app/.../SettingsScreenLanguageDropdownTest.kt` (or extend the existing tone dropdown test file) — compose UI test for the dropdown.

## 7. Out of scope

- Localizing the app's UI strings (the dropdown labels are native; the surrounding "Insight language" header is English — that's intentional and aligned with the rest of the app).
- Validating that the chosen language is one the model actually supports (we trust the user; the model's behavior on an unsupported but valid ISO code is the model's problem).
- Restricting the ISO 639-1 set to the eight dropdown options on the server. Server accepts any ISO 639-1 code; the dropdown is the curated UI surface. A power user setting `language="zh"` via API call works.

## 8. Risks & mitigations

| Risk | Mitigation |
|---|---|
| `language="auto"` cache rows are seeded with `prompt_version="3"` and old `prompt_version="2"` rows linger forever | Acceptable: superseded_at is null on both, but the lookup query filters on `prompt_version == self.prompt_version`, so old rows are never read again. They'll get gardened by a future cleanup PR if storage pressure ever matters. |
| `setStyleTone` overwriting `language` (and vice-versa) — UX papercut | Fix `setStyleTone` to copy from the current style instead of overwriting. New `setStyleLanguage` does the same. |
| ISO 639-1 set drift (new codes added by ISO) | We ship a hardcoded frozen set; drift means "user can't pick a rare new code". Acceptable; refresh on demand. |
| Prompt-version bump invalidates all existing tone-keyed rows (they get re-generated next request) | This is the cost of moving the cache key. Architect-acknowledged in the roadmap as the reason to ship PR4 *before* PR1/PR2/PR3 seed more rows. |

## 9. Deliverables checklist

- [ ] `server/migrations/versions/ai_002_insight_language.py` with up + down.
- [ ] `opds_sync/db/models.BookInsight.language` column.
- [ ] `opds_sync/api/ai_schemas.AiStyle.language` field + validator.
- [ ] `opds_sync/core/ai/prompts.py`: `PROMPT_VERSION = "3"`, "Respond in" clause.
- [ ] `opds_sync/core/ai/service.py`: lock key + cache lookup + generate threading.
- [ ] Server tests per §6.1 + §6.2.
- [ ] `data/ai/.../AiDtos.kt`: `AiStyle.language`.
- [ ] `app/.../AiRepository.kt`: `setStyleLanguage` + fix `setStyleTone` to preserve siblings.
- [ ] `app/.../SettingsViewModel.kt`: `setStyleLanguage`.
- [ ] `app/.../SettingsScreen.kt`: language dropdown.
- [ ] Android tests per §6.3.
- [ ] `docs/sync-api.md` updated.
- [ ] PR body includes the cache-version checklist + GPT review summary.
