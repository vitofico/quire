# Spec — Structured themes v3 + `book_themes` (PR3)

**Date:** 2026-05-17
**Branch:** `feat/themes-v3` (from `main` after PR-A / PR-C / PR4 / PR-B / PR5 / PR1 / PR2 all merged)
**Roadmap reference:** `.claude/local/quire-ai/2026-05-16-next-deliverables.md` §PR3
**Status:** Architect-reviewed (APPROVE-WITH-CHANGES, 2026-05-17); incorporating findings.

## 1. Motivation

`BookInsightPayload.themes` does NOT exist on the current `main` (it was removed in the schema v2 cleanup; `test_payload_dropped_v1_fields_rejected` actively rejects payloads carrying a `themes` key). PR3 RE-INTRODUCES `themes` as a first-class structured output rooted in a controlled vocabulary, and at the same time normalizes it into a side table.

Previously (pre-v2) the field existed as an unconstrained `list[str] | None`. The problems it had then were:

- **Free-text.** The model emits whatever genre/topic phrases it likes (`"dystopia"`, `"a dystopian future"`, `"dystopia and surveillance"`). No vocabulary, no normalization, so aggregation across books is impossible.
- **Buried.** Themes live inside the JSON payload only; there is no SQL-queryable surface. `top_themes` for PR9 library stats can't be expressed without scanning every payload row in Python.
- **Tone-conflated.** Because themes live in the per-(tone, language) cache row, the same book ends up with subtly different theme lists under each style. There's no canonical "what is this book about" anchored to the identity.

PR3 splits themes off the payload into a normalized side table `book_themes(book_insight_id, theme, confidence)`, hanging off `book_insights.id` (not raw identity). This:

- Lets PR9 (`/library/v1/stats` top_themes) query `book_themes` directly with a `GROUP BY theme`.
- Lets the model output a controlled vocabulary so cross-book aggregation is meaningful.
- Preserves the "free-text raw value" escape hatch via an `other` bucket with `confidence < 1.0` — non-vocab themes still get persisted, just with lower confidence, so vocab evolution doesn't lose data.
- Stays per-cache-row (FK to `book_insights.id`) so tone/language/model variants stay independently queryable. Different tones for the same book produce different theme rows; PR9's `top_themes` query can either dedup by `(metadata_id, content_hash, theme)` later, or just count rows — both are valid v0 choices.

## 2. Identity hierarchy (unchanged)

PR3 introduces no new identity surface. The FK to `book_insights.id` inherits whatever canonical/alias resolution PR2 produces upstream.

## 3. Server changes

### 3.1 Migration `ai_004_themes`

Fourth migration on the `ai` branch. Per `server/migrations/README.md`:

```python
revision = "ai_004"
down_revision = "ai_003"
branch_labels = None     # PR-C owns the "ai" label on ai_001
depends_on = None
```

Upgrade creates the table:

```sql
CREATE TABLE book_themes (
    book_insight_id  bigint   NOT NULL,
    theme            text     NOT NULL,
    confidence       real     NOT NULL DEFAULT 1.0,

    PRIMARY KEY (book_insight_id, theme),
    FOREIGN KEY (book_insight_id) REFERENCES book_insights(id) ON DELETE CASCADE
);

CREATE INDEX ix_book_themes_theme ON book_themes (theme);
```

The PK is composite `(book_insight_id, theme)` — one row per (insight, theme) pair, no duplicates. The single-column `theme` index covers PR9's `GROUP BY theme` / `WHERE theme = ?` query paths without a join (the FK is already an implicit index in Postgres).

`ON DELETE CASCADE` means invalidating an insight automatically drops its theme rows. The orchestrator's existing `invalidate` does a bare `DELETE FROM book_insights`; the FK cascade picks up the side table for free.

Downgrade drops the index and the table.

### 3.2 ORM model `BookTheme`

In `server/opds_sync/db/models.py`:

```python
class BookTheme(Base):
    __tablename__ = "book_themes"
    __table_args__ = (
        Index("ix_book_themes_theme", "theme"),
    )

    book_insight_id: Mapped[int] = mapped_column(
        BigInteger,
        ForeignKey("book_insights.id", ondelete="CASCADE"),
        primary_key=True,
    )
    theme: Mapped[str] = mapped_column(String, primary_key=True)
    confidence: Mapped[float] = mapped_column(
        Float, nullable=False, server_default=text("1.0"), default=1.0
    )
```

Block comment immediately above the class points to the cache-integrity invariant: `book_themes` is SHARED cache (no `user_id`), per the same rule as `book_insights`.

### 3.3 Controlled vocabulary `opds_sync/core/ai/themes.py`

Per architect feedback (2026-05-17), the vocabulary expands to cover broad library-shelf buckets (`science_fiction`, `fantasy`, `young_adult`, `middle_grade`, `poetry`, `drama`, `short_stories`, `graphic_novel`, `western`, `contemporary_fiction`, `crime`, `health`) and merges `science` + `popular_science` to avoid hairline splits. `other` is REMOVED from the model-visible vocab — non-vocab passthroughs still go to the side table at confidence 0.5, but the model is never instructed to emit the literal token `"other"` (which would otherwise pollute PR9's controlled-vocab top-N query). The full list lives in code; the count target is ~55 entries.

New module exports:

```python
CONTROLLED_THEMES: frozenset[str] = frozenset({
    # Fiction — broad buckets
    "science_fiction", "fantasy", "literary_fiction", "contemporary_fiction",
    "historical_fiction", "young_adult", "middle_grade", "children",
    "poetry", "drama", "short_stories", "graphic_novel",
    # Fiction — speculative subgenres
    "dystopia", "post_apocalyptic", "cyberpunk", "space_opera",
    "first_contact", "time_travel", "alternate_history", "magical_realism",
    "epic_fantasy", "urban_fantasy", "mythology", "superheroes",
    # Fiction — genre
    "mystery", "thriller", "noir", "horror", "romance", "crime",
    "coming_of_age", "war_fiction", "adventure", "satire", "western",
    # Nonfiction
    "biography", "memoir", "history", "science", "philosophy",
    "essays", "journalism", "business", "economics", "psychology",
    "self_help", "travel", "cooking", "politics", "religion",
    "true_crime", "nature", "art", "music", "sports", "education",
    "technology", "medicine", "health",
})  # ~57 entries; deliberately no `other` sentinel.

VOCAB_CONFIDENCE = 1.0
OTHER_CONFIDENCE = 0.5


def normalize_theme(raw: str) -> tuple[str, float]:
    """Map a raw model-emitted theme to (canonical_theme, confidence).

    Rules:
      - Strip whitespace; lowercase.
      - Compute a candidate by additionally replacing spaces/hyphens with
        underscores.
      - If the candidate is in CONTROLLED_THEMES, return
        (candidate, VOCAB_CONFIDENCE).
      - If the raw input (post-strip) is empty, return
        ("other", OTHER_CONFIDENCE).
      - Otherwise return (lowercased_stripped, OTHER_CONFIDENCE) —
        preserving spaces so the human-readable raw token survives the
        round trip.

    The literal token `"other"` is reserved for empty/null inputs and is
    written at 0.5 confidence so it falls out of PR9's `WHERE confidence
    >= 1.0` controlled-vocab queries cleanly. Real raw passthroughs (like
    `"noir western"`) survive in the index at 0.5 confidence with their
    spaces intact.
    """
```

Decision (locked):
- Confidence is a NORMALIZATION BAND, not epistemic confidence. `1.0` means "the model picked a vocab term"; `0.5` means "the model emitted something off-vocab and we preserved it".
- Literal `"other"` exists ONLY as the empty-input fallback at 0.5 confidence. The model never sees `"other"` in the vocab list, so it has no reason to emit it.
- Raw passthroughs keep their spaces (`"noir western"`) rather than collapsing onto the `"other"` sentinel — strictly more information, and PR9 filters by confidence band anyway.

### 3.4 Re-introduce `themes` and bump `BookInsightPayload.schema_version` to 3

In `opds_sync/api/ai_schemas.py`:

- ADD `themes: list[str] | None = None` to `BookInsightPayload` (the field does not exist on the current `main`; it was stripped in the schema v2 cleanup and `test_payload_dropped_v1_fields_rejected` actively rejects it as a stale v1 field).
- Update `test_payload_dropped_v1_fields_rejected` to remove `themes` from the rejected-fields list.
- Update `test_payload_key_order_matches_reading_order` to include `themes` immediately after `content_warnings` (themes are reader-facing topic tags, naturally grouped with safety tags in the reading order).
- `schema_version: int = 3` (was 2).
- Old (cached) v2 payloads deserialize cleanly because v2 had no `themes` (the field defaults to `None`) and `extra="forbid"` does not catch missing fields.
- IMPORTANT (per architect feedback): `_do_generate` MUST force `payload.schema_version = 3` after the model returns, before `model_dump()`. The schema has `schema_version: int = 3` as the default, but the model can still produce `2` by mistake — we don't want pre-bumped values winning by accident. This is a server-side normalization; the model's free choice of `schema_version` is irrelevant.

### 3.5 Bump `PROMPT_VERSION` to "4"

In `opds_sync/core/ai/prompts.py`:

- `PROMPT_VERSION = "4"` (was "3").
- Prompt body gets a new section under "Rules":
  - `- "themes": pick 1-5 tags from the controlled vocabulary below. If a clearly-applicable concept is missing from the vocab, you may emit your own short snake_case string; the server will preserve it with reduced confidence."`
  - The vocab list is inlined as a comma-separated string for the model. (~50 strings = ~150 tokens; negligible against the prompt body.)

Existing cache rows at `prompt_version="3"` are not deleted; they simply stop being returned because every new lookup runs at version "4". Old rows are still queryable by audit tooling. No backfill in this PR.

### 3.6 Orchestrator: write `book_themes` rows on insight write

In `service.py::_do_generate`, after `payload = await self.ai.chat_structured(...)` and BEFORE `session.add(row)`, force the schema version:

```python
# PR3: server-side schema_version pin. The model may emit `2` by mistake;
# the cache row must always reflect the server-side schema we generated under.
payload.schema_version = 3
```

After `session.add(row)` and `session.flush()` (which populates `row.id`) and BEFORE `await self._log_generation(...)`:

```python
# PR3: persist themes as side-table rows. The FK + ON DELETE CASCADE means we
# never need to clean these up explicitly — invalidate deletes the parent
# insight and Postgres drops the children. Regenerate marks the old row as
# superseded (not deleted), so its theme rows survive for audit alongside the
# new row's themes.
if payload.themes:
    seen: set[str] = set()
    for raw in payload.themes:
        if not isinstance(raw, str):
            continue
        normalized, conf = normalize_theme(raw)
        if normalized in seen:
            continue
        seen.add(normalized)
        session.add(BookTheme(
            book_insight_id=row.id,
            theme=normalized,
            confidence=conf,
        ))
```

The `seen` set guards against the model emitting duplicates (`["mystery", "Mystery"]` → both normalize to `mystery`). The PK constraint would catch this too, but explicit dedup avoids integrity-error rollbacks for model quirks.

`regenerate` reuses `_do_generate`: the old live row is marked `superseded_at` (not deleted), so its `book_themes` children survive for audit history. The new live row gets its own fresh theme set. PR9 (top_themes) MUST filter for `superseded_at IS NULL` on the join to avoid double-counting regenerated insights.

### 3.7 Cache-key audit: add `BookTheme` to SHARED list

In `server/tests/integration/test_cache_key_audit.py`:

```python
SHARED_CACHE_TABLES = [
    pytest.param(BookInsight, id="book_insights"),
    pytest.param(ExternalSourceCacheEntry, id="external_source_cache"),
    pytest.param(BookTheme, id="book_themes"),  # PR3
]
```

`book_themes` carries no `user_id` / `tenant_id` / `subject` / `principal_id`. The existing parametrized test covers it without other changes.

### 3.8 Docs

- `docs/sync-api.md` AiStyle table footnote: bump `PROMPT_VERSION` to `"4"`, bump `schema_version` example to `3`, mention the controlled vocab. Add a short note explaining that themes now also live in `book_themes`.

## 4. Cache-version checklist

- [x] `PROMPT_VERSION` → `"4"`.
- [x] `BookInsightPayload.schema_version` → `3` (payload shape changed: `themes: list[str] | None` ADDED back as a first-class output — it did not exist in v2).
- [x] `_do_generate` pins `payload.schema_version = 3` server-side after model return.
- [x] `book_insights` unique indexes unchanged (themes go to a side table, not new cache-key dimensions).
- [x] Lock key in `service.py` unchanged (no new cache-key dimension).
- [x] Android `:data:ai` DTOs already handle `themes: List<String>?`; no change.
- [x] Invalidate-by-cache-key still works: FK cascade deletes side rows automatically.
- [x] Tests cover: new insight populates `book_themes`; non-vocab → `other` confidence band; cascade on delete; old v2 cache rows still deserialize; PR4-prompt-version-3 rows survive coexistence with v4 rows.
- [x] Docs: `docs/sync-api.md` updated.

## 5. Test plan

All under `server/tests/integration/`.

1. **`test_book_themes_persisted_on_generate`** — `generate(...)` for a fresh identity → assert one `BookTheme` row per payload theme, all with `confidence==1.0` (assuming vocab themes).
2. **`test_book_themes_non_vocab_falls_to_other_confidence`** — fake `chat_structured` returns themes `["dystopia", "noir western", "Cyberpunk"]` → rows `dystopia` (1.0), `cyberpunk` (1.0, normalized), `noir western` (0.5).
3. **`test_book_themes_cascade_on_insight_delete`** — generate → invalidate → assert no `BookTheme` rows survive (FK cascade).
4. **`test_book_themes_cascade_on_regenerate_supersede`** — generate (5 themes), regenerate (3 different themes) → superseded row keeps its themes, new live row has the new themes; both row sets coexist because the original is superseded-not-deleted.
5. **`test_book_themes_dedup_on_model_duplicate`** — model returns `["mystery", "Mystery", "MYSTERY"]` → exactly one row `(insight_id, "mystery", 1.0)`.
6. **`test_old_prompt_version_3_rows_still_queryable`** — seed a row at `prompt_version="3"` with NO `book_themes` children → row remains readable by direct SQL (it's just not returned to clients whose lookup is at `prompt_version="4"`).
7. **`test_old_schema_version_2_payload_deserializes`** — load a v2 payload (no `themes` field) into `BookInsightPayload.model_validate(...)` → succeeds; `themes` defaults to None; schema_version reads back as 2.
8. **`test_book_themes_literal_other_low_confidence`** — fake model returns `themes=["", "  ", "other"]` → expect rows for `("other", 0.5)` only (empties collapse to literal "other"). Literal `"other"` from the model is NOT confidence-1.0; it survives at 0.5.
9. **`test_schema_version_pinned_to_3_server_side`** — fake model returns a payload with `schema_version=2` → persisted row has `payload["schema_version"] == 3`.
10. **Existing `test_cache_key_audit.py`** runs against `BookTheme` parametrize entry → green.

Mode matrix:
- `requires_ai` markers on all new tests → run in full-stack and AI-only modes, skip in sync-only mode (no AI tables migrated).

## 6. Non-goals

- **No backfill.** Pre-existing `book_insights` rows do NOT retroactively get `book_themes` populated. Their `payload.themes` (if any) stays inside the payload; PR9 stats will report 0 themes for them. A separate optional follow-up PR can backfill if it turns out users care.
- **No `raw_string` column.** Spec explicitly says confidence band only. The raw string IS the `theme` column value for non-vocab entries.
- **No Android changes.** The Android `:data:ai` layer already deserializes `themes: List<String>?`; the controlled vocab is server-internal.
- **No PR9 work.** Library stats top_themes is a separate PR; PR3 just makes the SQL surface available.
- **No vocabulary versioning column.** If the vocab evolves, old rows under the old vocab stay valid (they're just snake_case strings). A `vocab_version` column would be over-engineering for v0.

## 7. Risks & mitigations

- **Vocabulary lock-in.** Once `book_themes.theme` rows accumulate, renaming a vocab entry (e.g. `science_fiction` → `sf`) requires a data migration. Mitigation: pick names carefully now; if a rename happens later, it's a short follow-up migration. Document the vocab in `themes.py` with comments so renames are deliberate.
- **`other` cardinality explosion.** If the model emits 10 distinct free-text strings per book, the `theme` index could bloat. Mitigation: low-confidence rows still cost only ~30 bytes apiece; ~10k books * 5 rows = 50k rows, trivial for Postgres. If it becomes a problem, a follow-up can collapse all `confidence < 1.0` rows into a single literal `"other"` row.
- **Schema_version semantics (resolved).** PR3 actually ADDS the `themes` field (it does not exist on the current `main`; v2's payload had no themes). So this is a real shape change, not just a semantic one. Bumping to 3 is justified. `_do_generate` pins `payload.schema_version = 3` server-side after model return so cache rows can never be poisoned by a model emitting `2`.

## 8. Downstream notes

- **PR9 (library stats v0):** queries `book_themes` joined to `book_insights` filtered on the user's library items. Per architect feedback the query MUST filter `book_insights.superseded_at IS NULL` to avoid double-counting regenerated insights, AND should dedup per (book, theme) so a book with two tone variants doesn't count its themes twice. Suggested shape:
  ```sql
  SELECT theme, COUNT(DISTINCT li.pk) AS book_count
  FROM book_themes bt
  JOIN book_insights bi ON bi.id = bt.book_insight_id
  JOIN library_items  li ON (
        (bi.metadata_id  IS NOT NULL AND li.metadata_id  = bi.metadata_id)
     OR (bi.content_hash IS NOT NULL AND li.content_hash = bi.content_hash))
  WHERE li.user_id = :uid
    AND li.deleted_at IS NULL
    AND bi.superseded_at IS NULL
    AND bt.confidence >= 1.0
  GROUP BY theme
  ORDER BY book_count DESC
  LIMIT 10;
  ```
  Confidence-band filter (`>= 1.0`) restricts to controlled-vocab themes; PR9 can choose to also include lower-confidence raw passthroughs in a "raw themes" section if desired.
- **PR7 (catalog detail screen):** no impact. Themes show up in `payload.themes` exactly as before, just now drawn from a controlled vocab. The pre-download catalog-preview flow generates an insight under a synthetic content_hash; that insight's `book_themes` children live alongside the post-download convergence.
- **F-Droid posture:** untouched. Server-only PR.
