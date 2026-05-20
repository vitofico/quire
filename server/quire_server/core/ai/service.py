"""Insight orchestrator.

Responsibilities:
- Cache lookup (metadata_id first, content_hash second) with alias
  reconciliation. Filters out superseded rows.
- Per-identity coalescing via in-process asyncio locks.
- Server-wide concurrency cap via asyncio.Semaphore.
- Process-wide rate-limit via TokenBucket against AI_BASE_URL.
- Per-user daily budget enforced via ai_usage_daily.
- Pre-prompt retrieval from configured sources, in parallel.
- Series override: bundle's series wins over the model's.
- Persist + return.
- Regenerate: marks live row superseded, generates new row with previous_insight_ids set.
"""

from __future__ import annotations

import asyncio
import logging
import time
from collections.abc import Callable
from datetime import UTC, date, datetime, timedelta
from typing import Protocol

from sqlalchemy import delete, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from quire_server.api.ai_schemas import (
    AiStyle,
    BookInsightPayload,
    BookInsightResponse,
    Citation,
    DocumentIdentity,
    MetadataBundle,
    SeriesInsight,
)
from quire_server.core.ai.health_state import AiHealthState
from quire_server.core.ai.identity import (
    IDENTITY_HIERARCHY,
    CanonicalIdentity,
    load_live_insight_ids_for_canonicals,
    reconcile_aliases,
    resolve_identity,
)
from quire_server.core.ai.prompts import SYSTEM_PROMPT, compose_user_prompt
from quire_server.core.ai.themes import normalize_theme
from quire_server.core.logging_ctx import request_id_var
from quire_server.db.models import AIGenerationLog, AIUsageDaily, BookInsight, BookTheme

logger = logging.getLogger(__name__)


class QuotaExceeded(Exception):
    """Raised when the per-user daily budget is full. Carries values for the 429 body."""

    def __init__(self, *, used: int, limit: int, resets_at: datetime) -> None:
        self.used = used
        self.limit = limit
        self.resets_at = resets_at
        super().__init__(f"daily budget exhausted: {used}/{limit}")


class IdentityUnresolvable(Exception):
    """No canonical identity could be derived from the supplied hints.

    Raised on write paths (`generate`, `regenerate`) when neither a
    canonical (metadata_id/content_hash) was supplied directly nor an
    alias hint resolved to one via `insight_identity_aliases`. Read
    paths (`get`, `invalidate`) treat this as a cache miss / no-op
    respectively rather than raising.
    """


class TokenBucket:
    """Process-wide async token bucket. Smooths bursts against AI_BASE_URL."""

    def __init__(self, *, rate_per_min: int) -> None:
        self._capacity = float(max(rate_per_min, 1))
        self._refill_per_s = self._capacity / 60.0
        self._tokens = self._capacity
        self._last = time.monotonic()
        self._lock = asyncio.Lock()

    async def acquire(self) -> None:
        while True:
            async with self._lock:
                now = time.monotonic()
                self._tokens = min(
                    self._capacity, self._tokens + (now - self._last) * self._refill_per_s
                )
                self._last = now
                if self._tokens >= 1.0:
                    self._tokens -= 1.0
                    return
                wait_s = (1.0 - self._tokens) / self._refill_per_s
            await asyncio.sleep(wait_s)


class _AIClientLike(Protocol):
    async def chat_structured(self, *, system: str, user: str, schema: type, timeout_s: float): ...


class _RetrieverLike(Protocol):
    async def lookup_wikipedia(self, *, author: str | None, title: str) -> list[Citation]: ...

    async def lookup_openlibrary(
        self, *, author: str | None, title: str, isbn: str | None
    ) -> list[Citation]: ...


class InsightOrchestrator:
    def __init__(
        self,
        *,
        ai: _AIClientLike,
        retriever_factory: Callable[[AsyncSession], _RetrieverLike],
        sources_enabled: tuple[str, ...],
        model_id: str,
        prompt_version: str,
        max_concurrency: int,
        ai_timeout_s: float,
        rate_per_min: int = 10,
        daily_budget: int = 200,
        regen_daily_limit: int = 3,
        health_state: AiHealthState | None = None,
        session_factory: async_sessionmaker[AsyncSession] | None = None,
    ) -> None:
        self.ai = ai
        self.retriever_factory = retriever_factory
        self.sources_enabled = tuple(sources_enabled)
        self.model_id = model_id
        self.prompt_version = prompt_version
        self._sem = asyncio.Semaphore(max_concurrency)
        self._locks: dict[str, asyncio.Lock] = {}
        self._locks_master = asyncio.Lock()
        self._ai_timeout_s = ai_timeout_s
        self._bucket = TokenBucket(rate_per_min=rate_per_min)
        self._daily_budget = daily_budget
        self._regen_daily_limit = regen_daily_limit
        # When None, the orchestrator silently skips health updates. Tests
        # that don't care about reachability state can omit it.
        self._health = health_state
        # When set, `_retrieve` mints a fresh AsyncSession per source-lookup
        # task so the two concurrent retriever calls don't race on a single
        # asyncpg connection (which raises "This session is provisioning a
        # new connection; concurrent operations are not permitted" and gets
        # swallowed by asyncio.gather(..., return_exceptions=True)). When
        # None (legacy / unit-test default), `_retrieve` falls back to the
        # shared request-scoped session — fine for FakeRetriever stubs that
        # never touch the DB.
        self._session_factory = session_factory

    # ------- public API -------

    async def get(
        self,
        session: AsyncSession,
        ident: DocumentIdentity,
        *,
        user_id: str | None = None,
        style: AiStyle | None = None,
        tenant_id: str = "local",
    ) -> BookInsightResponse | None:
        tone = _tone_of(style)
        language = _language_of(style)
        # PR2: resolve any alias hints to canonical(s); also handles the
        # rare collision where two pre-existing insights resolve to the
        # same book by superseding the loser in a single transaction.
        ident, _hints = await self._resolve_canonical(
            session, ident, user_id=user_id, tone=tone, language=language
        )
        if not _has_canonical(ident):
            return None  # treat as cache miss on the read path
        row = await self._cache_lookup(
            session, ident, tone=tone, language=language, allow_backfill=False
        )
        if row is None:
            return None
        if user_id is not None:
            await self._log_generation(
                session,
                book_insight_id=row.id,
                subject=user_id,
                tenant_id=tenant_id,
                status="hit",
                latency_ms=0,
            )
            await session.commit()
        return self._row_to_response(row)

    async def generate(
        self,
        session: AsyncSession,
        ident: DocumentIdentity,
        bundle: MetadataBundle,
        *,
        user_id: str,
        style: AiStyle | None = None,
        tenant_id: str = "local",
    ) -> BookInsightResponse:
        tone = _tone_of(style)
        language = _language_of(style)
        # PR2: pre-resolve aliases. The original hints are captured so the
        # post-generation `reconcile_aliases` step can write alias rows
        # for every non-canonical hint atomically with the insight row.
        original_hints = ident.alias_dict()
        ident, _collided = await self._resolve_canonical(
            session, ident, user_id=user_id, tone=tone, language=language
        )
        if not _has_canonical(ident):
            raise IdentityUnresolvable(
                "no canonical identity (metadata_id or content_hash) supplied "
                "and no alias hint resolved to one"
            )
        row = await self._cache_lookup(
            session, ident, tone=tone, language=language, allow_backfill=True
        )
        if row is not None:
            await self._log_generation(
                session,
                book_insight_id=row.id,
                subject=user_id,
                tenant_id=tenant_id,
                status="hit",
                latency_ms=0,
            )
            await session.commit()
            return self._row_to_response(row)

        lock = await self._acquire_identity_lock(ident, tone=tone, language=language)
        async with lock:
            row = await self._cache_lookup(
                session, ident, tone=tone, language=language, allow_backfill=True
            )
            if row is not None:
                await self._log_generation(
                    session,
                    book_insight_id=row.id,
                    subject=user_id,
                    tenant_id=tenant_id,
                    status="hit",
                    latency_ms=0,
                )
                await session.commit()
                return self._row_to_response(row)

            await self._reserve_budget(session, user_id=user_id, is_regen=False)
            await self._bucket.acquire()
            row = await self._do_generate(
                session,
                ident,
                bundle,
                user_id=user_id,
                tenant_id=tenant_id,
                style=style,
                tone=tone,
                language=language,
                feedback=None,
                previous_insight_ids=None,
                original_hints=original_hints,
            )
            return self._row_to_response(row)

    async def regenerate(
        self,
        session: AsyncSession,
        ident: DocumentIdentity,
        bundle: MetadataBundle,
        *,
        user_id: str,
        reason: str,
        style: AiStyle | None = None,
        tenant_id: str = "local",
    ) -> BookInsightResponse:
        """Supersede the existing live row (if any) and generate a fresh one."""
        tone = _tone_of(style)
        language = _language_of(style)
        # PR2: resolve aliases BEFORE acquiring the lock so two requests
        # for the same book via different alias schemes serialize on the
        # same canonical-keyed lock.
        original_hints = ident.alias_dict()
        ident, _collided = await self._resolve_canonical(
            session, ident, user_id=user_id, tone=tone, language=language
        )
        if not _has_canonical(ident):
            raise IdentityUnresolvable(
                "no canonical identity (metadata_id or content_hash) supplied "
                "and no alias hint resolved to one"
            )
        lock = await self._acquire_identity_lock(ident, tone=tone, language=language)
        async with lock:
            existing = await self._cache_lookup(
                session, ident, tone=tone, language=language, allow_backfill=False
            )
            previous_ids: list[int] = []
            if existing is not None:
                previous_ids = list(existing.previous_insight_ids or [])
                previous_ids.append(existing.id)
                existing.superseded_at = datetime.now(UTC)
                await session.commit()

            await self._reserve_budget(session, user_id=user_id, is_regen=True)
            await self._bucket.acquire()
            row = await self._do_generate(
                session,
                ident,
                bundle,
                user_id=user_id,
                tenant_id=tenant_id,
                style=style,
                tone=tone,
                language=language,
                feedback=reason,
                previous_insight_ids=previous_ids or None,
                original_hints=original_hints,
            )
            return self._row_to_response(row)

    async def invalidate(
        self,
        session: AsyncSession,
        ident: DocumentIdentity,
        *,
        user_id: str | None = None,
    ) -> int:
        # PR2: resolve aliases first so DELETE finds the row even when
        # the caller only knows an alias (catalog-preview invalidate).
        tone_unused = "neutral"
        language_unused = "auto"
        ident, _collided = await self._resolve_canonical(
            session,
            ident,
            user_id=user_id,
            tone=tone_unused,
            language=language_unused,
        )
        if not _has_canonical(ident):
            return 0  # nothing to invalidate
        stmt = delete(BookInsight).where(
            BookInsight.model_id == self.model_id,
            BookInsight.prompt_version == self.prompt_version,
        )
        if ident.metadata_id and ident.content_hash:
            stmt = stmt.where(
                (BookInsight.metadata_id == ident.metadata_id)
                | (BookInsight.content_hash == ident.content_hash)
            )
        elif ident.metadata_id:
            stmt = stmt.where(BookInsight.metadata_id == ident.metadata_id)
        else:
            stmt = stmt.where(BookInsight.content_hash == ident.content_hash)
        result = await session.execute(stmt)
        await session.commit()
        return result.rowcount or 0

    # ------- internals -------

    async def _log_generation(
        self,
        session: AsyncSession,
        *,
        book_insight_id: int,
        subject: str,
        tenant_id: str,
        status: str,
        latency_ms: int | None,
        error_class: str | None = None,
    ) -> None:
        """Stage one ai_generation_log row. Caller commits the surrounding tx."""
        session.add(
            AIGenerationLog(
                book_insight_id=book_insight_id,
                tenant_id=tenant_id,
                subject=subject,
                request_id=(request_id_var.get() or None),
                model_id=self.model_id,
                prompt_version=self.prompt_version,
                latency_ms=latency_ms,
                status=status,
                error_class=error_class,
            )
        )

    async def _do_generate(
        self,
        session: AsyncSession,
        ident: DocumentIdentity,
        bundle: MetadataBundle,
        *,
        user_id: str,
        tenant_id: str,
        style: AiStyle | None,
        tone: str,
        language: str,
        feedback: str | None,
        previous_insight_ids: list[int] | None,
        original_hints: dict[str, str] | None = None,
    ) -> BookInsight:
        async with self._sem:
            citations = await self._retrieve(session, bundle)
            user_prompt = compose_user_prompt(bundle, citations, style=style, feedback=feedback)
            t0 = time.monotonic()
            try:
                payload = await self.ai.chat_structured(
                    system=SYSTEM_PROMPT,
                    user=user_prompt,
                    schema=BookInsightPayload,
                    timeout_s=self._ai_timeout_s,
                )
            except Exception as e:
                # Errors don't produce an ai_generation_log row (no FK target).
                # The structured log line is the operator-facing audit trail;
                # request_id is attached by RequestIdLogFilter (record.request_id).
                latency_ms = int((time.monotonic() - t0) * 1000)
                logger.warning(
                    "event=ai.generate.error tenant_id=%s subject=%s model=%s "
                    "prompt_version=%s latency_ms=%d error_class=%s",
                    tenant_id,
                    user_id,
                    self.model_id,
                    self.prompt_version,
                    latency_ms,
                    type(e).__name__,
                )
                # PR5: surface provider reachability to GET /ai/v1/health.
                if self._health is not None:
                    await self._health.record_provider_failure(error_class=type(e).__name__)
                raise
            # PR5: chat_structured succeeded → provider is reachable now.
            if self._health is not None:
                await self._health.record_provider_success(model_id=self.model_id)
            # PR3: pin schema_version server-side. The model may emit `2` by
            # mistake (or copy it from cached examples); the cache row must
            # always reflect the schema we generated under, not whatever the
            # model guessed.
            payload.schema_version = 3
            latency_ms = int((time.monotonic() - t0) * 1000)
            logger.info(
                "ai.generate content_hash=%s model=%s latency_ms=%d sources=%s regen=%s",
                ident.content_hash,
                self.model_id,
                latency_ms,
                ",".join(sorted({c.kind for c in citations})) or "-",
                bool(feedback),
            )

        if bundle.series_name:
            payload.series = SeriesInsight(
                name=bundle.series_name,
                position=bundle.series_position,
            )

        sources = list(citations)
        sources.append(Citation(kind="model", title=self.model_id, snippet="generated"))

        # `BookInsight.content_hash` is NOT NULL (legacy schema invariant).
        # On the catalog-preview path the caller may have no real content_hash
        # because the EPUB hasn't been downloaded yet. We persist a synthetic
        # placeholder so the row is unique and roundtrippable; when the user
        # later downloads and the real content_hash arrives, the collision-
        # detection path in `_resolve_canonical` finds the synthetic row,
        # marks it superseded, and merges its lineage onto the real row.
        effective_content_hash = ident.content_hash or _synthetic_content_hash(
            ident, original_hints
        )
        row = BookInsight(
            metadata_id=ident.metadata_id,
            content_hash=effective_content_hash,
            model_id=self.model_id,
            prompt_version=self.prompt_version,
            tone=tone,
            language=language,
            sources_used=list({c.kind for c in citations}),
            payload=payload.model_dump(),
            sources=[c.model_dump() for c in sources],
            # generated_by is grandfathered: NOT NULL legacy column. PR-C still
            # WRITES it (to satisfy the constraint) but NEVER READS it. A
            # follow-up PR will null then drop it. The replacement audit trail
            # is AIGenerationLog (FK from book_insight_id).
            generated_by=user_id,
            previous_insight_ids=previous_insight_ids,
        )
        session.add(row)
        await session.flush()  # populate row.id before logging

        # PR3: persist themes as side-table rows. FK + ON DELETE CASCADE means
        # invalidate (DELETE on the parent) drops these for free. Regenerate is
        # supersede-not-delete, so old theme rows survive for audit alongside
        # the new row's themes; PR9's top_themes query must filter
        # `book_insights.superseded_at IS NULL` on the join to avoid double-
        # counting. Dedup via `seen` so model quirks like ["mystery", "Mystery"]
        # don't trip the composite PK constraint with an IntegrityError.
        if payload.themes:
            seen: set[str] = set()
            for raw in payload.themes:
                if not isinstance(raw, str):
                    continue
                normalized, conf = normalize_theme(raw)
                if normalized in seen:
                    continue
                seen.add(normalized)
                session.add(
                    BookTheme(
                        book_insight_id=row.id,
                        theme=normalized,
                        confidence=conf,
                    )
                )

        await self._log_generation(
            session,
            book_insight_id=row.id,
            subject=user_id,
            tenant_id=tenant_id,
            status="miss",
            latency_ms=latency_ms,
        )

        # PR2: write alias rows for every non-canonical original hint in the
        # SAME transaction as the insight row. If any alias write raises
        # (AliasConflict, integrity error), the whole insight insert rolls
        # back. This is the load-bearing atomicity invariant for the
        # catalog-preview-then-download convergence flow.
        if original_hints:
            canonical = _canonical_from_row(row)
            source_tag = "opf_extracted" if ident.metadata_id else "opds_feed"
            await reconcile_aliases(
                session,
                hints=original_hints,
                canonical=canonical,
                source=source_tag,
                user_id=user_id,
            )

        await session.commit()
        await session.refresh(row)
        return row

    async def _reserve_budget(
        self,
        session: AsyncSession,
        *,
        user_id: str,
        is_regen: bool,
    ) -> None:
        if self._daily_budget <= 0 and not is_regen:
            return
        today = datetime.now(UTC).date()
        usage = (
            await session.execute(
                select(AIUsageDaily).where(
                    AIUsageDaily.user_id == user_id, AIUsageDaily.day == today
                )
            )
        ).scalar_one_or_none()
        if usage is None:
            usage = AIUsageDaily(user_id=user_id, day=today, count=0, regen_count=0)
            session.add(usage)
            await session.flush()

        if self._daily_budget > 0 and usage.count >= self._daily_budget:
            raise QuotaExceeded(
                used=usage.count,
                limit=self._daily_budget,
                resets_at=_next_utc_midnight(today),
            )
        if is_regen and usage.regen_count >= self._regen_daily_limit:
            raise QuotaExceeded(
                used=usage.regen_count,
                limit=self._regen_daily_limit,
                resets_at=_next_utc_midnight(today),
            )

        usage.count += 1
        if is_regen:
            usage.regen_count += 1
        await session.commit()

    async def _retrieve(self, session: AsyncSession, bundle: MetadataBundle) -> list[Citation]:
        # Each retrieval task must get its own AsyncSession when a factory is
        # configured: SQLAlchemy AsyncSession (and the underlying asyncpg
        # connection it provisions on first use) is not safe for concurrent
        # `.execute()` calls. Sharing the request-scoped session across the
        # wikipedia + openlibrary lookups under asyncio.gather raises
        # "This session is provisioning a new connection; concurrent
        # operations are not permitted" on the loser, which gather swallows
        # via return_exceptions=True. Net effect in prod: openlibrary always
        # lost the race, never issued an HTTP call, never recorded
        # reachability, and never wrote a row to external_source_cache.
        tasks: list = []
        sessions: list[AsyncSession] = []

        async def _run_with_own_session(coro_factory):
            async with self._session_factory() as s:  # type: ignore[misc]
                sessions.append(s)
                retriever = self.retriever_factory(s)
                return await coro_factory(retriever)

        if self._session_factory is not None:
            if "wikipedia" in self.sources_enabled:
                tasks.append(
                    _run_with_own_session(
                        lambda r: r.lookup_wikipedia(author=bundle.author, title=bundle.title)
                    )
                )
            if "openlibrary" in self.sources_enabled:
                tasks.append(
                    _run_with_own_session(
                        lambda r: r.lookup_openlibrary(
                            author=bundle.author, title=bundle.title, isbn=bundle.isbn
                        )
                    )
                )
        else:
            # Legacy / test fallback: a single shared session. Safe only when
            # the retriever is a stub that doesn't issue concurrent DB calls.
            retriever = self.retriever_factory(session)
            if "wikipedia" in self.sources_enabled:
                tasks.append(retriever.lookup_wikipedia(author=bundle.author, title=bundle.title))
            if "openlibrary" in self.sources_enabled:
                tasks.append(
                    retriever.lookup_openlibrary(
                        author=bundle.author, title=bundle.title, isbn=bundle.isbn
                    )
                )

        if not tasks:
            return []
        results = await asyncio.gather(*tasks, return_exceptions=True)
        out: list[Citation] = []
        for r in results:
            if isinstance(r, Exception):
                logger.info("ai.retrieval.exception err=%s", r)
                continue
            out.extend(r)
        return out

    async def _cache_lookup(
        self,
        session: AsyncSession,
        ident: DocumentIdentity,
        *,
        tone: str,
        language: str,
        allow_backfill: bool,
    ) -> BookInsight | None:
        # Step 1: by metadata_id (live rows only)
        if ident.metadata_id:
            row = (
                await session.execute(
                    select(BookInsight).where(
                        BookInsight.metadata_id == ident.metadata_id,
                        BookInsight.model_id == self.model_id,
                        BookInsight.prompt_version == self.prompt_version,
                        BookInsight.tone == tone,
                        BookInsight.language == language,
                        BookInsight.superseded_at.is_(None),
                    )
                )
            ).scalar_one_or_none()
            if row is not None:
                return row
        # Step 2: by content_hash (live rows only)
        if not ident.content_hash:
            return None
        row = (
            await session.execute(
                select(BookInsight).where(
                    BookInsight.content_hash == ident.content_hash,
                    BookInsight.model_id == self.model_id,
                    BookInsight.prompt_version == self.prompt_version,
                    BookInsight.tone == tone,
                    BookInsight.language == language,
                    BookInsight.superseded_at.is_(None),
                )
            )
        ).scalar_one_or_none()
        if row is None:
            return None
        # Alias reconciliation: backfill metadata_id if we just learned it.
        if allow_backfill and ident.metadata_id and row.metadata_id is None:
            row.metadata_id = ident.metadata_id
            await session.commit()
            await session.refresh(row)
        return row

    async def _resolve_canonical(
        self,
        session: AsyncSession,
        ident: DocumentIdentity,
        *,
        user_id: str | None,
        tone: str,
        language: str,
    ) -> tuple[DocumentIdentity, bool]:
        """Walk all supplied identity hints, resolve aliases, detect collisions.

        Returns the populated `DocumentIdentity` plus a flag indicating
        whether a collision was detected and resolved (superseded a row).

        Algorithm (per spec §3.6):
          1. For each hint in IDENTITY_HIERARCHY, call `resolve_identity`
             and collect canonicals.
          2. If 0 or 1 distinct canonicals: merge into ident and return.
          3. If 2+ distinct canonicals: load live BookInsight rows for
             each. If 2+ live rows, the metadata_id-keyed row wins; the
             rest are marked superseded and their lineage merged onto
             the winner. Commit, then return.
        """
        # Collect canonicals from every supplied hint.
        canonicals: list[CanonicalIdentity] = []
        seen: set[tuple[str, str]] = set()
        for scheme in IDENTITY_HIERARCHY:
            value = getattr(ident, scheme, None)
            if value is None:
                continue
            c = await resolve_identity(
                session, alias_scheme=scheme, alias_value=value, user_id=user_id
            )
            if c is None:
                continue
            key = (c.scheme, c.value)
            if key in seen:
                continue
            seen.add(key)
            canonicals.append(c)

        if not canonicals:
            return ident, False

        if len(canonicals) == 1:
            return _populate_ident(ident, canonicals[0]), False

        # 2+ distinct canonicals: collision-detection.
        rows = await load_live_insight_ids_for_canonicals(
            session,
            canonicals=canonicals,
            model_id=self.model_id,
            prompt_version=self.prompt_version,
            tone=tone,
            language=language,
        )
        if len(rows) <= 1:
            # No collision in cache — just merge the strongest canonical
            # (metadata_id wins over content_hash by hierarchy order).
            best = next(
                (c for c in canonicals if c.scheme == "metadata_id"),
                canonicals[0],
            )
            return _populate_ident(ident, best), False

        # Collision: 2+ live rows for what we now know is the same book.
        winner_pair = next(
            (pair for pair in rows if pair[1].metadata_id is not None),
            rows[0],
        )
        winner_row = winner_pair[1]
        losers = [r for c, r in rows if r.id != winner_row.id]

        # Merge lineage with stable de-dupe.
        merged: list[int] = []
        seen_ids: set[int] = set()

        def _append_unique(seq: list[int] | None) -> None:
            for x in seq or []:
                if x not in seen_ids:
                    seen_ids.add(x)
                    merged.append(x)

        _append_unique(winner_row.previous_insight_ids)
        for loser in losers:
            _append_unique(loser.previous_insight_ids)
            if loser.id not in seen_ids:
                seen_ids.add(loser.id)
                merged.append(loser.id)
            loser.superseded_at = datetime.now(UTC)
            # Also propagate metadata_id onto the winner if the winner lacks it
            # and a loser carries it. (Defensive: in practice the winner is
            # selected because it already has metadata_id.)
            if winner_row.metadata_id is None and loser.metadata_id is not None:
                winner_row.metadata_id = loser.metadata_id
        winner_row.previous_insight_ids = merged or None
        await session.commit()
        await session.refresh(winner_row)

        return _populate_ident(ident, _canonical_from_row(winner_row)), True

    async def _acquire_identity_lock(
        self, ident: DocumentIdentity, *, tone: str, language: str
    ) -> asyncio.Lock:
        # Per-(identity, tone, language): users hitting the same book with
        # different cache-key dimensions should not serialize through one lock.
        # The canonical key is metadata_id-preferred (falls back to content_hash)
        # so two requests for the same book via different alias schemes
        # serialize through one lock after `_resolve_canonical` runs.
        canonical_value = ident.metadata_id or ident.content_hash or ""
        key = f"{canonical_value}|{tone}|{language}"
        async with self._locks_master:
            lock = self._locks.get(key)
            if lock is None:
                lock = asyncio.Lock()
                self._locks[key] = lock
            return lock

    def _row_to_response(self, row: BookInsight) -> BookInsightResponse:
        return BookInsightResponse(
            payload=BookInsightPayload.model_validate(row.payload),
            sources=[Citation.model_validate(c) for c in row.sources],
            model_id=row.model_id,
            prompt_version=row.prompt_version,
            generated_at=row.generated_at.isoformat(),
        )


def _next_utc_midnight(today: date) -> datetime:
    return datetime.combine(today + timedelta(days=1), datetime.min.time(), tzinfo=UTC)


def _tone_of(style: AiStyle | None) -> str:
    return style.tone if style is not None else "neutral"


def _language_of(style: AiStyle | None) -> str:
    return style.language if style is not None else "auto"


# ---------- PR2 identity-resolution helpers ---------------------------------


def _has_canonical(ident: DocumentIdentity) -> bool:
    return bool(ident.metadata_id or ident.content_hash)


def _populate_ident(ident: DocumentIdentity, canonical: CanonicalIdentity) -> DocumentIdentity:
    """Return a copy of `ident` with the canonical scheme populated.

    Does NOT clobber an already-set canonical field (caller-supplied data
    takes precedence). Preserves all alias fields.
    """
    out = ident.model_copy()
    if canonical.scheme == "metadata_id" and out.metadata_id is None:
        out.metadata_id = canonical.value
    elif canonical.scheme == "content_hash" and out.content_hash is None:
        out.content_hash = canonical.value
    return out


def _canonical_from_row(row: BookInsight) -> CanonicalIdentity:
    """Pick the strongest canonical present on a persisted insight row."""
    if row.metadata_id:
        return CanonicalIdentity(scheme="metadata_id", value=row.metadata_id)
    return CanonicalIdentity(scheme="content_hash", value=row.content_hash)


def _synthetic_content_hash(ident: DocumentIdentity, original_hints: dict[str, str] | None) -> str:
    """Build a deterministic synthetic content_hash for catalog-preview rows.

    The schema's `content_hash NOT NULL` invariant predates PR2's alias-only
    flow. When the caller has no real content_hash (catalog preview, before
    download), we synthesize one from the strongest available alias hint so
    the row is unique and roundtrippable. The collision-detection path will
    later supersede this row when the user downloads and supplies the real
    sha256.

    Format: `synthetic:<scheme>:<value>` — readable, debuggable, and
    obviously not a real sha256.
    """
    if ident.metadata_id:
        return f"synthetic:metadata_id:{ident.metadata_id}"
    if original_hints:
        for scheme in ("opds_dc_id", "isbn", "calibre_book_id", "opds_href"):
            v = original_hints.get(scheme)
            if v:
                return f"synthetic:{scheme}:{v}"
    # Last-resort: title-less, completely unidentifiable. Should be
    # impossible because IdentityUnresolvable would have fired earlier.
    raise IdentityUnresolvable("cannot synthesize content_hash without any identity hint")
