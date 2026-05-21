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
import hashlib
import logging
import re
import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, date, datetime, timedelta
from typing import Protocol

from sqlalchemy import (
    Integer,
    and_,
    case,
    delete,
    func,
    literal,
    literal_column,
    or_,
    select,
)
from sqlalchemy.dialects.postgresql import insert as pg_insert
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from quire_server.api.ai_schemas import (
    AiStyle,
    AuthorCount,
    BookInsightPayload,
    BookInsightResponse,
    BookRec,
    Citation,
    DocumentIdentity,
    MetadataBundle,
    ReaderProfilePayload,
    ReaderProfilePromptOutput,
    ReaderStats,
    SeriesInsight,
    _LLMRec,
)
from quire_server.core.ai.health_state import AiHealthState
from quire_server.core.ai.identity import (
    IDENTITY_HIERARCHY,
    CanonicalIdentity,
    load_live_insight_ids_for_canonicals,
    reconcile_aliases,
    resolve_identity,
)
from quire_server.core.ai.prompts import (
    READER_PROFILE_PROMPT,
    READER_PROFILE_PROMPT_VERSION,
    SYSTEM_PROMPT,
    compose_user_prompt,
)
from quire_server.core.ai.themes import normalize_theme
from quire_server.core.logging_ctx import request_id_var
from quire_server.db.models import (
    AIGenerationLog,
    AIUsageDaily,
    BookInsight,
    BookTheme,
    Document,
    InsightIdentityAlias,
    LibraryItem,
    Progress,
    ReaderProfile,
)

logger = logging.getLogger(__name__)


class QuotaExceeded(Exception):
    """Raised when the per-user daily budget is full. Carries values for the 429 body."""

    def __init__(self, *, used: int, limit: int, resets_at: datetime) -> None:
        self.used = used
        self.limit = limit
        self.resets_at = resets_at
        super().__init__(f"daily budget exhausted: {used}/{limit}")


@dataclass(slots=True)
class PromoteResult:
    """Outcome of a successful ``InsightOrchestrator.promote_insight`` call."""

    insight_id: int
    already_promoted: bool


class PromoteOwnershipError(Exception):
    """Caller does not own a ``library_items`` row at the ``to`` identity."""


class ProfileGenerationError(Exception):
    """Raised when ``refresh_profile`` cannot produce a payload.

    Wraps any underlying LLM / model error (timeout, malformed response, …)
    so the route handler can map to 502 without leaking implementation
    details. The ``ai_generation_log`` row with ``status='error'`` is written
    BEFORE this is raised.
    """


@dataclass(slots=True)
class _LibCandidate:
    """One owned-but-not-finished library item, slotted with a ``lib-NNN`` id.

    Materialized server-side from ``library_items`` (filtered against the
    user's finished set). The LLM receives ``candidate_id`` + minimal hints
    only; the server reconstructs trusted ``BookRec`` fields from this row
    when materializing the response.
    """

    candidate_id: str
    metadata_id: str | None
    content_hash: str
    title: str
    author: str
    identity: DocumentIdentity


@dataclass(slots=True)
class _DiscoveryCandidate:
    """One OpenLibrary-seeded discovery candidate, slotted with a ``dis-NNN`` id.

    ``metadata_id`` is set when a normalized (title, author) match is found in
    ``library_items`` for the same user — used for the belt-and-suspenders
    owner-exclusion check at materialize time.
    """

    candidate_id: str
    title: str
    author: str
    work_key: str
    source_url: str
    metadata_id: str | None


@dataclass(slots=True)
class _ProgressRow:
    """Compact projection used by the reading-history digest helpers."""

    metadata_id: str | None
    content_hash: str
    title: str
    author: str
    finished_at: datetime | None = None
    abandoned_at: datetime | None = None
    last_read_at: datetime | None = None


@dataclass(slots=True)
class _ReaderStatsExtended:
    """Server-side companion to the public ``ReaderStats`` payload.

    Carries the data the orchestrator needs for owner exclusion (the
    metadata_id + normalized (title, author) sets), the latest-progress
    timestamp used by the input_fingerprint, and the total library_items
    count — none of which are surfaced in the API but all of which the
    refresh path needs in-memory between stats compute and payload build.
    """

    public: ReaderStats
    library_items_count: int
    latest_progress_updated_at: datetime | None
    finished_metadata_ids: set[str] = field(default_factory=set)
    owned_metadata_ids: set[str] = field(default_factory=set)
    owned_normalized_pairs: set[tuple[str, str]] = field(default_factory=set)
    top_author_names: list[str] = field(default_factory=list)


class _PrincipalLike(Protocol):
    """Minimal contract for the ``principal`` argument to ``refresh_profile``.

    Mirrors ``quire_server.api.ai_auth.AiPrincipal`` without taking the
    runtime import dependency (which would create a service → api cycle).
    """

    @property
    def subject(self) -> str: ...

    @property
    def tenant_id(self) -> str: ...


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


class _ProfileRetrieverLike(Protocol):
    """Minimal contract for the retriever used by ``refresh_profile``.

    Distinct from ``_RetrieverLike`` so unit tests can stub a simpler
    bibliography-only fake without satisfying the wikipedia / openlibrary
    methods (which the profile path never calls).
    """

    async def author_bibliography(self, name: str): ...


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
        # pr-β: optional profile-only retriever factory (author bibliography).
        # When None, ``refresh_profile`` skips discovery candidates entirely.
        profile_retriever_factory: Callable[[AsyncSession], _ProfileRetrieverLike] | None = None,
        # pr-β: per-user-per-UTC-day cap on /ai/v1/profile/refresh. 0 disables.
        profile_refresh_daily_limit: int = 3,
        # pr-β: overall wall-clock cap on one /profile/refresh model call.
        profile_timeout_s: float = 90.0,
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
        # PR-ζ: per-user, per-UTC-day promote-call counter. Process-local
        # (no DB row) — pod restart resets, accepted because promote has
        # no LLM cost. Keyed by (user_id, day) -> count.
        self._promote_counter: dict[tuple[str, date], int] = {}
        self._promote_counter_lock = asyncio.Lock()
        # pr-β: per-(tenant_id, subject) singleflight locks for the reader
        # profile refresh path. Mirrors the per-identity insight locks; the
        # collapse semantics (each waiter still writes a kind='profile'
        # status='hit' row) are documented in refresh_profile().
        self._profile_locks: dict[tuple[str, str], asyncio.Lock] = {}
        self._profile_locks_master = asyncio.Lock()
        self._profile_retriever_factory = profile_retriever_factory
        self._profile_refresh_daily_limit = profile_refresh_daily_limit
        self._profile_timeout_s = profile_timeout_s

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
        kind: str = "insight",
        book_insight_id: int | None,
        subject: str,
        tenant_id: str,
        status: str,
        latency_ms: int | None,
        error_class: str | None = None,
        model_id: str | None = None,
        prompt_version: str | None = None,
    ) -> None:
        """Stage one ai_generation_log row. Caller commits the surrounding tx.

        ``kind`` (ai_006): row family discriminator. Defaults to ``'insight'``
        for back-compat with the original PR-C call sites; pr-β explicitly
        passes ``'profile'`` for refresh-profile rows and ``'promote'`` for
        catalog→EPUB copy rows. Schema invariant enforced both at the helper
        boundary (ValueError BEFORE the DB round-trip) AND at the table
        (``ck_ai_generation_log_kind_fk``):

          * ``kind='insight'`` or ``'promote'`` → ``book_insight_id`` MUST be
            non-null.
          * ``kind='profile'`` → ``book_insight_id`` MUST be null.

        ``model_id`` / ``prompt_version`` override the orchestrator's
        defaults — used for profile rows that carry the reader-profile prompt
        version, not the per-book one.
        """
        if kind in ("insight", "promote") and book_insight_id is None:
            raise ValueError(f"kind={kind!r} requires non-null book_insight_id")
        if kind == "profile" and book_insight_id is not None:
            raise ValueError("kind='profile' requires book_insight_id IS NULL")
        session.add(
            AIGenerationLog(
                kind=kind,
                book_insight_id=book_insight_id,
                tenant_id=tenant_id,
                subject=subject,
                request_id=(request_id_var.get() or None),
                model_id=model_id if model_id is not None else self.model_id,
                prompt_version=(
                    prompt_version if prompt_version is not None else self.prompt_version
                ),
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
            # PR-ε (schema v4): pin schema_version server-side. The model may
            # emit ``2`` or ``3`` by mistake (or copy it from cached examples);
            # the cache row must always reflect the schema we generated under,
            # not whatever the model guessed.
            payload.schema_version = 4
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

    # ------- PR-ζ promote --------------------------------------------------

    async def reserve_promote_budget(self, *, user_id: str, limit: int) -> None:
        """Per-user, per-UTC-day promote rate limit (process-local).

        ``limit <= 0`` disables the check entirely. The counter increments
        on every accepted reservation (idempotent re-promotes count too —
        the cost we are bounding is the request volume, not the DB write).
        Raises ``QuotaExceeded`` when the limit is hit.
        """
        if limit <= 0:
            return
        today = datetime.now(UTC).date()
        async with self._promote_counter_lock:
            used = self._promote_counter.get((user_id, today), 0)
            if used >= limit:
                raise QuotaExceeded(
                    used=used,
                    limit=limit,
                    resets_at=_next_utc_midnight(today),
                )
            self._promote_counter[(user_id, today)] = used + 1

    async def _assert_owns(
        self,
        session: AsyncSession,
        *,
        identity: DocumentIdentity,
        user_id: str,
    ) -> None:
        """Ownership gate: ``to`` must match a live ``library_items`` row.

        Lock #13 sequences the caller so the library PUT lands before promote
        fires; this check guarantees we don't copy an insight under a row the
        user doesn't own (cross-user defense).
        """
        clauses = []
        if identity.metadata_id is not None:
            clauses.append(LibraryItem.metadata_id == identity.metadata_id)
        if identity.content_hash is not None:
            clauses.append(LibraryItem.content_hash == identity.content_hash)
        if not clauses:
            raise PromoteOwnershipError("`to` has no canonical identity")
        stmt = (
            select(LibraryItem.pk)
            .where(
                LibraryItem.user_id == user_id,
                LibraryItem.deleted_at.is_(None),
                or_(*clauses),
            )
            .limit(1)
        )
        if (await session.execute(stmt)).first() is None:
            raise PromoteOwnershipError("no matching library_items row")

    async def promote_insight(
        self,
        session: AsyncSession,
        *,
        from_identity: DocumentIdentity,
        to_identity: DocumentIdentity,
        user_id: str,
        tenant_id: str,
        tone: str,
        language: str,
    ) -> PromoteResult | None:
        """Row-copy + alias-link promote (Lock #1).

        Returns:
          * ``PromoteResult`` on a fresh copy or an idempotent hit.
          * ``None`` when nothing to promote (no source row at ``from`` for
            this variant). Caller maps that to ``204``.

        Algorithm:
          1. Ownership gate (``library_items`` join).
          2. Alias-row idempotency anchor (Lock #1): identity-level "already
             promoted" sentinel; NOT keyed on (tone, language).
          3. Look up the source ``book_insights`` row at ``from`` for the
             requested (model, prompt_version, tone, language).
          4. Look up whether the ``to``-side row at that variant exists.
          5. Idempotent fast-path: alias AND ``to``-side variant both
             present → return existing id.
          6. Row-copy with ``generated_at = NOW()`` (Lock #23) and
             ``previous_insight_ids=[src.id]`` for audit lineage.
          7. ``book_themes`` copy with ON CONFLICT DO NOTHING.
          8. Alias write (audit; ``source='promoted_on_download'``).
          9. Stdout audit (Lock #11 amendment — NO DB row in PR-ζ).
        """
        t0 = time.monotonic()
        await self._assert_owns(session, identity=to_identity, user_id=user_id)

        from_scheme, from_value = _canonical_scheme_value(from_identity)
        to_scheme, to_value = _canonical_scheme_value(to_identity)

        # Step 2: alias-row idempotency anchor.
        alias_q = (
            select(InsightIdentityAlias.id)
            .where(
                InsightIdentityAlias.alias_scheme == from_scheme,
                InsightIdentityAlias.alias_value == from_value,
                InsightIdentityAlias.canonical_scheme == to_scheme,
                InsightIdentityAlias.canonical_value == to_value,
                InsightIdentityAlias.user_id == user_id,
                InsightIdentityAlias.source == "promoted_on_download",
            )
            .limit(1)
        )
        alias_row_exists = (await session.execute(alias_q)).first() is not None

        # Step 3: source row for this (model, prompt_version, tone, language).
        # Pick on whichever canonical scheme `from` carries.
        src_filters = [
            BookInsight.model_id == self.model_id,
            BookInsight.prompt_version == self.prompt_version,
            BookInsight.tone == tone,
            BookInsight.language == language,
            BookInsight.superseded_at.is_(None),
        ]
        if from_scheme == "metadata_id":
            src_filters.append(BookInsight.metadata_id == from_value)
        else:
            src_filters.append(BookInsight.content_hash == from_value)
        src_q = select(BookInsight).where(*src_filters).limit(1)
        src_row = (await session.execute(src_q)).scalar_one_or_none()
        if src_row is None:
            # Nothing to promote for this variant. Caller maps to 204.
            return None

        # Step 4: destination-side existence check for the same variant.
        dst_filters = [
            BookInsight.model_id == self.model_id,
            BookInsight.prompt_version == self.prompt_version,
            BookInsight.tone == tone,
            BookInsight.language == language,
            BookInsight.superseded_at.is_(None),
        ]
        if to_scheme == "metadata_id":
            dst_filters.append(BookInsight.metadata_id == to_value)
        else:
            dst_filters.append(BookInsight.content_hash == to_value)
        dst_q = select(BookInsight.id).where(*dst_filters).limit(1)
        existing_to_id = (await session.execute(dst_q)).scalar_one_or_none()

        # Step 5: idempotent fast-path.
        if existing_to_id is not None and alias_row_exists:
            latency_ms = int((time.monotonic() - t0) * 1000)
            self._log_promote_event(
                source_id=src_row.id,
                copied_id=existing_to_id,
                user_id=user_id,
                tenant_id=tenant_id,
                source_generated_at=src_row.generated_at,
                latency_ms=latency_ms,
                outcome="idempotent",
            )
            # pr-β / Lock #11 amendment: also write a kind='promote' DB row.
            await self._log_generation(
                session,
                kind="promote",
                book_insight_id=existing_to_id,
                tenant_id=tenant_id,
                subject=user_id,
                status="hit",
                latency_ms=latency_ms,
                model_id=src_row.model_id,
                prompt_version=src_row.prompt_version,
            )
            await session.commit()
            return PromoteResult(insight_id=existing_to_id, already_promoted=True)

        # Step 6: row-copy.
        new_row = BookInsight(
            metadata_id=to_identity.metadata_id,
            content_hash=to_identity.content_hash or src_row.content_hash,
            model_id=src_row.model_id,
            prompt_version=src_row.prompt_version,
            tone=src_row.tone,
            language=src_row.language,
            sources_used=list(src_row.sources_used or []),
            payload=dict(src_row.payload),
            sources=list(src_row.sources or []),
            generated_at=func.now(),
            generated_by=f"promote:{user_id}",
            previous_insight_ids=[src_row.id],
            superseded_at=None,
        )
        session.add(new_row)
        try:
            await session.flush()
            new_id = new_row.id
            copied = True
        except IntegrityError:
            # Race: another writer created the `to`-side variant in parallel.
            # Roll back the savepoint, re-resolve the winning id, treat as
            # idempotent and skip theme-copy (the winner owns its own themes).
            await session.rollback()
            winner_id = (await session.execute(dst_q)).scalar_one_or_none()
            if winner_id is None:
                # Conflict was on something else (defensive).
                raise
            await self._maybe_register_alias(
                session,
                from_scheme=from_scheme,
                from_value=from_value,
                to_scheme=to_scheme,
                to_value=to_value,
                user_id=user_id,
                alias_already=alias_row_exists,
            )
            latency_ms = int((time.monotonic() - t0) * 1000)
            self._log_promote_event(
                source_id=src_row.id,
                copied_id=winner_id,
                user_id=user_id,
                tenant_id=tenant_id,
                source_generated_at=src_row.generated_at,
                latency_ms=latency_ms,
                outcome="race_lost",
            )
            # pr-β / Lock #11 amendment: DB row for the race-lost promote.
            await self._log_generation(
                session,
                kind="promote",
                book_insight_id=winner_id,
                tenant_id=tenant_id,
                subject=user_id,
                status="hit",
                latency_ms=latency_ms,
                model_id=src_row.model_id,
                prompt_version=src_row.prompt_version,
            )
            await session.commit()
            return PromoteResult(insight_id=winner_id, already_promoted=True)

        # Step 7: book_themes copy (Core INSERT ... SELECT, ON CONFLICT DO NOTHING).
        theme_copy = pg_insert(BookTheme).from_select(
            ["book_insight_id", "theme", "confidence"],
            select(
                literal(new_id).label("book_insight_id"),
                BookTheme.theme,
                BookTheme.confidence,
            ).where(BookTheme.book_insight_id == src_row.id),
        )
        theme_copy = theme_copy.on_conflict_do_nothing(
            index_elements=["book_insight_id", "theme"],
        )
        await session.execute(theme_copy)

        # Step 8: alias write (audit anchor).
        await self._maybe_register_alias(
            session,
            from_scheme=from_scheme,
            from_value=from_value,
            to_scheme=to_scheme,
            to_value=to_value,
            user_id=user_id,
            alias_already=alias_row_exists,
        )

        latency_ms = int((time.monotonic() - t0) * 1000)
        # pr-β / Lock #11 amendment: kind='promote' audit row alongside the
        # stdout structured log. Both are kept — stdout for operator-grep
        # convenience, DB for the authoritative audit trail.
        await self._log_generation(
            session,
            kind="promote",
            book_insight_id=new_id,
            tenant_id=tenant_id,
            subject=user_id,
            status="hit",
            latency_ms=latency_ms,
            model_id=src_row.model_id,
            prompt_version=src_row.prompt_version,
        )
        await session.commit()

        # Step 9: stdout audit (retained from PR-ζ; the DB row above is the
        # primary record post-ai_006).
        self._log_promote_event(
            source_id=src_row.id,
            copied_id=new_id,
            user_id=user_id,
            tenant_id=tenant_id,
            source_generated_at=src_row.generated_at,
            latency_ms=latency_ms,
            outcome="copied" if copied else "race_lost",
        )
        return PromoteResult(insight_id=new_id, already_promoted=False)

    async def _maybe_register_alias(
        self,
        session: AsyncSession,
        *,
        from_scheme: str,
        from_value: str,
        to_scheme: str,
        to_value: str,
        user_id: str,
        alias_already: bool,
    ) -> None:
        """Write the ``promoted_on_download`` audit alias as a user-scoped row.

        We bypass ``register_alias`` because that helper applies
        ``SCOPE_BY_SCHEME`` and would store ``metadata_id`` aliases globally
        (user_id=NULL). The promote anchor MUST be user-scoped (Lock #1):
        different users promote the same OPDS href onto their own canonical
        identity independently, and the idempotency check is per-user.
        """
        if alias_already:
            return
        from sqlalchemy import text as _text

        stmt = (
            pg_insert(InsightIdentityAlias)
            .values(
                alias_scheme=from_scheme,
                alias_value=from_value,
                canonical_scheme=to_scheme,
                canonical_value=to_value,
                source="promoted_on_download",
                user_id=user_id,
            )
            .on_conflict_do_nothing(
                index_elements=["alias_scheme", "alias_value", "user_id"],
                index_where=_text("user_id IS NOT NULL"),
            )
        )
        await session.execute(stmt)

    def _log_promote_event(
        self,
        *,
        source_id: int,
        copied_id: int,
        user_id: str,
        tenant_id: str,
        source_generated_at: datetime | None,
        latency_ms: int,
        outcome: str,
    ) -> None:
        """Structured stdout audit (Lock #11 amendment). NO DB row in PR-ζ.

        PR-β later extends ``_log_generation`` to accept ``kind='promote'``
        and the promote path begins writing a DB row alongside this line.
        Strictly an additive PR-β edit; PR-ζ does not retrofit.
        """
        logger.info(
            "event=ai.promote tenant_id=%s subject=%s model=%s "
            "prompt_version=%s source_insight_id=%s copied_insight_id=%s "
            "source_generated_at=%s outcome=%s latency_ms=%d",
            tenant_id,
            user_id,
            self.model_id,
            self.prompt_version,
            source_id,
            copied_id,
            source_generated_at.isoformat() if source_generated_at else None,
            outcome,
            latency_ms,
        )

    # ------- pr-β reader-profile orchestrator -----------------------------

    async def _acquire_profile_lock(self, *, tenant_id: str, subject: str) -> asyncio.Lock:
        """Per-(tenant_id, subject) singleflight lock for refresh_profile.

        Mirrors ``_acquire_identity_lock``; the master mutex protects the
        dict, the returned lock serializes a single user's concurrent
        refresh calls. Each collapsed waiter still writes a
        ``kind='profile'`` audit row before returning the existing row
        (Critical Gap #3 from the v2 plan review).
        """
        key = (tenant_id, subject)
        async with self._profile_locks_master:
            lock = self._profile_locks.get(key)
            if lock is None:
                lock = asyncio.Lock()
                self._profile_locks[key] = lock
            return lock

    async def refresh_profile(
        self,
        *,
        principal: _PrincipalLike,
        session: AsyncSession,
    ) -> ReaderProfilePayload:
        """Compute (or short-circuit) the reader profile and persist it.

        Control flow (pr-β plan §4.7.3, with Critical Gap #3/#4 fixes):

          1. Acquire per-user singleflight lock.
          2. Inside the lock, re-read the existing reader_profiles row. If
             a fresh row exists (generated_at >= entry time), write a
             ``kind='profile'`` status='hit' audit row for THIS waiter and
             return the existing payload (collapsed waiter contract).
          3. Compute stats UNCONDITIONALLY (before any cap check).
          4. LOW-DATA short-circuit: if finished_count == 0, persist a
             stats-only payload with weight=0 (profile_count NOT
             incremented) and return. This branch runs even when the daily
             cap is already hit.
          5. Daily-cap enforcement (raises QuotaExceeded → 429).
          6. Build digest + candidate maps; call the model under the global
             semaphore; materialize ``BookRec`` entries from the trusted
             maps; persist; bump ``profile_count``; write status='miss'
             audit row.
        """
        log = logger
        started_at = datetime.now(UTC)

        # 1. Singleflight.
        lock = await self._acquire_profile_lock(
            tenant_id=principal.tenant_id, subject=principal.subject
        )
        async with lock:
            # 2. Collapse check.
            existing = await self._read_reader_profile(session, principal)
            if existing is not None and existing.generated_at >= started_at:
                latency_ms = _ms_since(started_at)
                await self._write_profile_log(
                    session,
                    principal=principal,
                    status="hit",
                    latency_ms=latency_ms,
                    error_class=None,
                )
                await session.commit()
                log.info(
                    "profile.refresh.singleflight_collapse tenant=%s subject=%s",
                    principal.tenant_id,
                    principal.subject,
                )
                return _row_to_payload(existing)

            # 3. Stats — unconditional.
            extended = await self._compute_extended_stats(session, principal)
            stats = extended.public

            # 4. Low-data short-circuit BEFORE cap enforcement.
            if stats.finished_count == 0:
                payload = _build_low_data_payload(
                    stats,
                    extended,
                    model_id=self.model_id,
                )
                await self._upsert_reader_profile(session, principal, payload)
                await self._write_profile_log(
                    session,
                    principal=principal,
                    status="hit",
                    latency_ms=0,
                    error_class=None,
                )
                # WEIGHT=0: profile_count is NOT incremented in low-data
                # mode (Critical Gap #4). This is what lets a 0-finished-
                # books user receive a stats-only response even at quota
                # cap.
                await session.commit()
                log.info(
                    "profile.refresh.low_data_short_circuit tenant=%s subject=%s",
                    principal.tenant_id,
                    principal.subject,
                )
                return payload

            # 5. Daily cap (after low-data short-circuit).
            await self._enforce_profile_daily_cap(session, principal)

            # 6. Build digest + candidates and call the LLM under the global
            #    semaphore.
            async with self._sem:
                finished = await self._list_finished(session, principal, limit=50)
                abandoned = await self._list_abandoned(session, principal, limit=20)
                in_progress = await self._list_in_progress(session, principal, limit=10)
                themes_by_metadata = await self._themes_by_metadata(session, principal)
                digest = _build_reading_history_digest(
                    finished=finished,
                    abandoned=abandoned,
                    in_progress=in_progress,
                    themes_by_metadata=themes_by_metadata,
                )

                lib_candidates = await self._build_in_library_candidates(
                    session,
                    principal,
                    limit=30,
                    finished_metadata_ids=extended.finished_metadata_ids,
                )
                dis_candidates = await self._build_discovery_seed(
                    session,
                    principal,
                    authors=extended.top_author_names[:5],
                    owned_metadata_ids=extended.owned_metadata_ids,
                    owned_norm_pairs=extended.owned_normalized_pairs,
                )

                user_prompt = _serialize_profile_user_prompt(
                    stats=stats,
                    digest=digest,
                    lib_candidates=lib_candidates,
                    dis_candidates=dis_candidates,
                )

                try:
                    llm_output: ReaderProfilePromptOutput = await asyncio.wait_for(
                        self.ai.chat_structured(
                            system=READER_PROFILE_PROMPT,
                            user=user_prompt,
                            schema=ReaderProfilePromptOutput,
                            timeout_s=self._profile_timeout_s,
                        ),
                        timeout=self._profile_timeout_s,
                    )
                except Exception as exc:
                    latency_ms = _ms_since(started_at)
                    await self._write_profile_log(
                        session,
                        principal=principal,
                        status="error",
                        latency_ms=latency_ms,
                        error_class=type(exc).__name__,
                    )
                    await session.commit()
                    log.warning(
                        "profile.refresh.error tenant=%s subject=%s latency_ms=%d err=%s",
                        principal.tenant_id,
                        principal.subject,
                        latency_ms,
                        type(exc).__name__,
                    )
                    raise ProfileGenerationError(str(exc)) from exc

                latency_ms = _ms_since(started_at)
                lib_map = {c.candidate_id: c for c in lib_candidates}
                dis_map = {c.candidate_id: c for c in dis_candidates}
                in_lib_recs = self._materialize_lib_recs(
                    llm_output.in_library_recommendations,
                    lib_map,
                    finished_metadata_ids=extended.finished_metadata_ids,
                )
                discovery_recs = self._materialize_discovery_recs(
                    llm_output.discovery_recommendations,
                    dis_map,
                    owned_metadata_ids=extended.owned_metadata_ids,
                    owned_norm_pairs=extended.owned_normalized_pairs,
                )
                ai_suggested_recs = self._materialize_ai_suggested(
                    llm_output.ai_suggested_recommendations,
                )

                fingerprint = _compute_input_fingerprint(
                    stats,
                    library_items_count=extended.library_items_count,
                    latest_progress_updated_at=extended.latest_progress_updated_at,
                )
                payload = ReaderProfilePayload(
                    schema_version=1,
                    stats=stats,
                    narrative=llm_output.narrative,
                    confidence=llm_output.confidence,
                    in_library_recommendations=in_lib_recs,
                    discovery_recommendations=discovery_recs,
                    ai_suggested_recommendations=ai_suggested_recs,
                    input_fingerprint=fingerprint,
                )

                await self._upsert_reader_profile(session, principal, payload)
                await self._write_profile_log(
                    session,
                    principal=principal,
                    status="miss",
                    latency_ms=latency_ms,
                    error_class=None,
                )
                await self._increment_profile_count(session, principal)
                await session.commit()
                log.info(
                    "profile.refresh.success tenant=%s subject=%s latency_ms=%d "
                    "in_lib=%d discovery=%d ai_suggested=%d confidence=%s",
                    principal.tenant_id,
                    principal.subject,
                    latency_ms,
                    len(in_lib_recs),
                    len(discovery_recs),
                    len(ai_suggested_recs),
                    llm_output.confidence,
                )
                return payload

    async def _write_profile_log(
        self,
        session: AsyncSession,
        *,
        principal: _PrincipalLike,
        status: str,
        latency_ms: int,
        error_class: str | None,
    ) -> None:
        await self._log_generation(
            session,
            kind="profile",
            book_insight_id=None,
            tenant_id=principal.tenant_id,
            subject=principal.subject,
            status=status,
            latency_ms=latency_ms,
            error_class=error_class,
            model_id=self.model_id,
            prompt_version=READER_PROFILE_PROMPT_VERSION,
        )

    async def _read_reader_profile(
        self,
        session: AsyncSession,
        principal: _PrincipalLike,
    ) -> ReaderProfile | None:
        return (
            await session.execute(
                select(ReaderProfile).where(
                    ReaderProfile.tenant_id == principal.tenant_id,
                    ReaderProfile.subject == principal.subject,
                )
            )
        ).scalar_one_or_none()

    async def _upsert_reader_profile(
        self,
        session: AsyncSession,
        principal: _PrincipalLike,
        payload: ReaderProfilePayload,
    ) -> None:
        """Insert or replace the reader_profiles row.

        Keys on ``(tenant_id, subject)``. The payload's ``input_fingerprint``
        is mirrored to the column so the cheap WHERE-filter / Android client
        staleness compare doesn't require unpacking the JSONB blob.
        """
        now = datetime.now(UTC)
        stmt = (
            pg_insert(ReaderProfile)
            .values(
                tenant_id=principal.tenant_id,
                subject=principal.subject,
                payload=payload.model_dump(),
                schema_version=payload.schema_version,
                model_id=self.model_id,
                prompt_version=READER_PROFILE_PROMPT_VERSION,
                input_fingerprint=payload.input_fingerprint,
                generated_at=now,
            )
            .on_conflict_do_update(
                index_elements=["tenant_id", "subject"],
                set_={
                    "payload": payload.model_dump(),
                    "schema_version": payload.schema_version,
                    "model_id": self.model_id,
                    "prompt_version": READER_PROFILE_PROMPT_VERSION,
                    "input_fingerprint": payload.input_fingerprint,
                    "generated_at": now,
                },
            )
        )
        await session.execute(stmt)

    async def _enforce_profile_daily_cap(
        self,
        session: AsyncSession,
        principal: _PrincipalLike,
    ) -> None:
        if self._profile_refresh_daily_limit <= 0:
            return
        today = datetime.now(UTC).date()
        usage = (
            await session.execute(
                select(AIUsageDaily).where(
                    AIUsageDaily.user_id == principal.subject,
                    AIUsageDaily.day == today,
                )
            )
        ).scalar_one_or_none()
        if usage is None:
            usage = AIUsageDaily(user_id=principal.subject, day=today)
            session.add(usage)
            await session.flush()
        if usage.profile_count >= self._profile_refresh_daily_limit:
            raise QuotaExceeded(
                used=usage.profile_count,
                limit=self._profile_refresh_daily_limit,
                resets_at=_next_utc_midnight(today),
            )

    async def _increment_profile_count(
        self,
        session: AsyncSession,
        principal: _PrincipalLike,
    ) -> None:
        today = datetime.now(UTC).date()
        usage = (
            await session.execute(
                select(AIUsageDaily).where(
                    AIUsageDaily.user_id == principal.subject,
                    AIUsageDaily.day == today,
                )
            )
        ).scalar_one_or_none()
        if usage is None:
            usage = AIUsageDaily(user_id=principal.subject, day=today, profile_count=1)
            session.add(usage)
            await session.flush()
            return
        usage.profile_count += 1

    async def _compute_extended_stats(
        self,
        session: AsyncSession,
        principal: _PrincipalLike,
    ) -> _ReaderStatsExtended:
        """Compute the public ``ReaderStats`` plus the in-memory extras.

        ``books_with_themes_count`` is now populated by
        ``_compute_reader_stats`` (pr-β, per Lock #15) so this method just
        layers the orchestrator-only sets on top.
        """
        public = await _compute_reader_stats(session, principal.subject)

        # finished_metadata_ids — needed by owner-state in materialize.
        finished_metadata_rows = (
            await session.execute(
                select(LibraryItem.metadata_id)
                .select_from(LibraryItem)
                .join(
                    Document,
                    and_(
                        Document.user_id == LibraryItem.user_id,
                        Document.content_hash == LibraryItem.content_hash,
                    ),
                )
                .join(Progress, Progress.document_pk == Document.pk)
                .where(
                    LibraryItem.user_id == principal.subject,
                    LibraryItem.deleted_at.is_(None),
                    Progress.finished_at.is_not(None),
                    LibraryItem.metadata_id.is_not(None),
                )
            )
        ).all()
        finished_metadata_ids = {row[0] for row in finished_metadata_rows if row[0]}

        # owned_metadata_ids + owned_normalized_pairs — covers
        # owner-exclusion for discovery_recs.
        owned_rows = (
            await session.execute(
                select(LibraryItem.metadata_id, LibraryItem.title, LibraryItem.authors).where(
                    LibraryItem.user_id == principal.subject,
                    LibraryItem.deleted_at.is_(None),
                )
            )
        ).all()
        owned_metadata_ids: set[str] = set()
        owned_normalized_pairs: set[tuple[str, str]] = set()
        for mid, title, authors in owned_rows:
            if mid:
                owned_metadata_ids.add(mid)
            if title and authors:
                first_author = authors[0] if isinstance(authors, list) and authors else ""
                if first_author:
                    owned_normalized_pairs.add((_norm_text(title), _norm_text(first_author)))

        # library_items_count — total alive count (input to fingerprint).
        library_items_count = (
            await session.scalar(
                select(func.count())
                .select_from(LibraryItem)
                .where(
                    LibraryItem.user_id == principal.subject,
                    LibraryItem.deleted_at.is_(None),
                )
            )
        ) or 0

        # latest_progress_updated_at — fingerprint input.
        latest_dt = await session.scalar(
            select(func.max(Progress.client_updated_at))
            .select_from(Progress)
            .join(Document, Document.pk == Progress.document_pk)
            .where(Document.user_id == principal.subject)
        )

        return _ReaderStatsExtended(
            public=public,
            library_items_count=int(library_items_count),
            latest_progress_updated_at=latest_dt,
            finished_metadata_ids=finished_metadata_ids,
            owned_metadata_ids=owned_metadata_ids,
            owned_normalized_pairs=owned_normalized_pairs,
            top_author_names=[a.name for a in public.most_read_authors],
        )

    async def _list_finished(
        self,
        session: AsyncSession,
        principal: _PrincipalLike,
        *,
        limit: int,
    ) -> list[_ProgressRow]:
        rows = (
            await session.execute(
                select(
                    LibraryItem.metadata_id,
                    LibraryItem.content_hash,
                    LibraryItem.title,
                    LibraryItem.authors,
                    Progress.finished_at,
                )
                .select_from(LibraryItem)
                .join(
                    Document,
                    and_(
                        Document.user_id == LibraryItem.user_id,
                        Document.content_hash == LibraryItem.content_hash,
                    ),
                )
                .join(Progress, Progress.document_pk == Document.pk)
                .where(
                    LibraryItem.user_id == principal.subject,
                    LibraryItem.deleted_at.is_(None),
                    Progress.finished_at.is_not(None),
                )
                .order_by(Progress.finished_at.desc(), LibraryItem.content_hash.asc())
                .limit(limit)
            )
        ).all()
        return [
            _ProgressRow(
                metadata_id=r.metadata_id,
                content_hash=r.content_hash,
                title=r.title,
                author=_first_author(r.authors),
                finished_at=r.finished_at,
            )
            for r in rows
        ]

    async def _list_abandoned(
        self,
        session: AsyncSession,
        principal: _PrincipalLike,
        *,
        limit: int,
    ) -> list[_ProgressRow]:
        rows = (
            await session.execute(
                select(
                    LibraryItem.metadata_id,
                    LibraryItem.content_hash,
                    LibraryItem.title,
                    LibraryItem.authors,
                    Progress.abandoned_at,
                )
                .select_from(LibraryItem)
                .join(
                    Document,
                    and_(
                        Document.user_id == LibraryItem.user_id,
                        Document.content_hash == LibraryItem.content_hash,
                    ),
                )
                .join(Progress, Progress.document_pk == Document.pk)
                .where(
                    LibraryItem.user_id == principal.subject,
                    LibraryItem.deleted_at.is_(None),
                    Progress.finished_at.is_(None),
                    Progress.abandoned_at.is_not(None),
                )
                .order_by(Progress.abandoned_at.desc(), LibraryItem.content_hash.asc())
                .limit(limit)
            )
        ).all()
        return [
            _ProgressRow(
                metadata_id=r.metadata_id,
                content_hash=r.content_hash,
                title=r.title,
                author=_first_author(r.authors),
                abandoned_at=r.abandoned_at,
            )
            for r in rows
        ]

    async def _list_in_progress(
        self,
        session: AsyncSession,
        principal: _PrincipalLike,
        *,
        limit: int,
    ) -> list[_ProgressRow]:
        rows = (
            await session.execute(
                select(
                    LibraryItem.metadata_id,
                    LibraryItem.content_hash,
                    LibraryItem.title,
                    LibraryItem.authors,
                    Progress.client_updated_at,
                )
                .select_from(LibraryItem)
                .join(
                    Document,
                    and_(
                        Document.user_id == LibraryItem.user_id,
                        Document.content_hash == LibraryItem.content_hash,
                    ),
                )
                .join(Progress, Progress.document_pk == Document.pk)
                .where(
                    LibraryItem.user_id == principal.subject,
                    LibraryItem.deleted_at.is_(None),
                    Progress.finished_at.is_(None),
                    Progress.abandoned_at.is_(None),
                    Progress.percent > 0,
                )
                .order_by(Progress.client_updated_at.desc(), LibraryItem.content_hash.asc())
                .limit(limit)
            )
        ).all()
        return [
            _ProgressRow(
                metadata_id=r.metadata_id,
                content_hash=r.content_hash,
                title=r.title,
                author=_first_author(r.authors),
                last_read_at=r.client_updated_at,
            )
            for r in rows
        ]

    async def _themes_by_metadata(
        self,
        session: AsyncSession,
        principal: _PrincipalLike,
    ) -> dict[str, list[str]]:
        """Map ``metadata_id`` → up to 5 themes (confidence >= 0.5).

        Skips legacy v3-and-below BookInsight rows by filtering on
        ``schema_version >= 4``. The metadata_id key lets the digest
        helpers attach themes per book without a second DB hop.
        """
        rows = (
            await session.execute(
                select(LibraryItem.metadata_id, BookTheme.theme, BookTheme.confidence)
                .select_from(LibraryItem)
                .join(
                    BookInsight,
                    and_(
                        BookInsight.superseded_at.is_(None),
                        func.cast(
                            func.json_extract_path_text(BookInsight.payload, "schema_version"),
                            Integer,
                        )
                        >= 4,
                        or_(
                            and_(
                                BookInsight.metadata_id.is_not(None),
                                BookInsight.metadata_id == LibraryItem.metadata_id,
                            ),
                            BookInsight.content_hash == LibraryItem.content_hash,
                        ),
                    ),
                )
                .join(BookTheme, BookTheme.book_insight_id == BookInsight.id)
                .where(
                    LibraryItem.user_id == principal.subject,
                    LibraryItem.deleted_at.is_(None),
                    LibraryItem.metadata_id.is_not(None),
                    BookTheme.confidence >= 0.5,
                )
            )
        ).all()
        out: dict[str, list[str]] = {}
        for mid, theme, _conf in rows:
            if mid is None:
                continue
            bucket = out.setdefault(mid, [])
            if theme not in bucket and len(bucket) < 5:
                bucket.append(theme)
        return out

    async def _build_in_library_candidates(
        self,
        session: AsyncSession,
        principal: _PrincipalLike,
        *,
        limit: int,
        finished_metadata_ids: set[str],
    ) -> list[_LibCandidate]:
        """Owned-but-not-finished library items, ordered for stable IDs.

        Deterministic tie-breaker (architect Finding #3): primary order by
        ``created_at DESC`` (a proxy for the spec's ``acquired_at`` — the
        existing schema lacks an explicit acquired-at column), secondary by
        ``content_hash ASC`` so duplicate timestamps don't cause
        candidate_id drift across reruns.
        """
        rows = (
            await session.execute(
                select(
                    LibraryItem.metadata_id,
                    LibraryItem.content_hash,
                    LibraryItem.title,
                    LibraryItem.authors,
                )
                .select_from(LibraryItem)
                .where(
                    LibraryItem.user_id == principal.subject,
                    LibraryItem.deleted_at.is_(None),
                )
                .order_by(LibraryItem.created_at.desc(), LibraryItem.content_hash.asc())
            )
        ).all()
        out: list[_LibCandidate] = []
        idx = 1
        for r in rows:
            if r.metadata_id is not None and r.metadata_id in finished_metadata_ids:
                continue
            identity = DocumentIdentity(metadata_id=r.metadata_id, content_hash=r.content_hash)
            out.append(
                _LibCandidate(
                    candidate_id=f"lib-{idx:03d}",
                    metadata_id=r.metadata_id,
                    content_hash=r.content_hash,
                    title=r.title,
                    author=_first_author(r.authors),
                    identity=identity,
                )
            )
            idx += 1
            if len(out) >= limit:
                break
        return out

    async def _build_discovery_seed(
        self,
        session: AsyncSession,
        principal: _PrincipalLike,
        *,
        authors: list[str],
        owned_metadata_ids: set[str],
        owned_norm_pairs: set[tuple[str, str]],
    ) -> list[_DiscoveryCandidate]:
        """Sequential per-author OpenLibrary fetches → up to 25 discovery candidates.

        Sequential by design (architect Finding #4 closes OQ 11.3) — at
        most 5 authors × ~8s comfortably fits under the 90s profile timeout
        and avoids fan-out load on OpenLibrary's free tier.
        """
        if self._profile_retriever_factory is None:
            return []
        out: list[_DiscoveryCandidate] = []
        seen_work_keys: set[str] = set()
        retriever = self._profile_retriever_factory(session)
        idx = 1
        for author in authors:
            if not author:
                continue
            try:
                books = await retriever.author_bibliography(author)
            except Exception as exc:  # noqa: BLE001 — never let one author bring down the refresh
                logger.info(
                    "profile.refresh.author_bibliography_failed author=%s err=%s",
                    author,
                    type(exc).__name__,
                )
                continue
            for book in books:
                if book.work_key in seen_work_keys:
                    continue
                seen_work_keys.add(book.work_key)
                norm_pair = (_norm_text(book.title), _norm_text(book.author))
                # Cheap norm-pair owner exclusion at seed time so we don't
                # waste a candidate slot on a book the user already owns.
                # Materialize-time check stays as belt-and-suspenders.
                if norm_pair in owned_norm_pairs:
                    continue
                out.append(
                    _DiscoveryCandidate(
                        candidate_id=f"dis-{idx:03d}",
                        title=book.title,
                        author=book.author,
                        work_key=book.work_key,
                        source_url=book.source_url,
                        metadata_id=None,
                    )
                )
                idx += 1
                if len(out) >= 25:
                    return out
        return out

    def _materialize_lib_recs(
        self,
        llm_recs: list[_LLMRec],
        lib_map: dict[str, _LibCandidate],
        *,
        finished_metadata_ids: set[str],
    ) -> list[BookRec]:
        out: list[BookRec] = []
        for rec in llm_recs:
            cand = lib_map.get(rec.candidate_id or "")
            if cand is None:
                logger.info(
                    "profile.refresh.dropped_unknown_lib_candidate cid=%s",
                    rec.candidate_id,
                )
                continue
            owned_state = (
                "owned_read"
                if cand.metadata_id is not None and cand.metadata_id in finished_metadata_ids
                else "owned_unread"
            )
            out.append(
                BookRec(
                    candidate_id=cand.candidate_id,
                    title=cand.title,
                    author=cand.author,
                    identity=cand.identity,
                    source_type="in_library",
                    source_url=None,
                    owned_state=owned_state,
                    rationale=(rec.rationale or "").strip(),
                    sources=None,
                )
            )
        return out

    def _materialize_discovery_recs(
        self,
        llm_recs: list[_LLMRec],
        dis_map: dict[str, _DiscoveryCandidate],
        *,
        owned_metadata_ids: set[str],
        owned_norm_pairs: set[tuple[str, str]],
    ) -> list[BookRec]:
        out: list[BookRec] = []
        for rec in llm_recs:
            cand = dis_map.get(rec.candidate_id or "")
            if cand is None:
                logger.info(
                    "profile.refresh.dropped_unknown_dis_candidate cid=%s",
                    rec.candidate_id,
                )
                continue
            norm_pair = (_norm_text(cand.title), _norm_text(cand.author))
            if (
                cand.metadata_id is not None and cand.metadata_id in owned_metadata_ids
            ) or norm_pair in owned_norm_pairs:
                logger.info(
                    "profile.refresh.dropped_owned_discovery cid=%s title=%s",
                    rec.candidate_id,
                    cand.title,
                )
                continue
            out.append(
                BookRec(
                    candidate_id=cand.candidate_id,
                    title=cand.title,
                    author=cand.author,
                    identity=None,
                    source_type="discovery_openlibrary",
                    source_url=cand.source_url,
                    owned_state="not_owned",
                    rationale=(rec.rationale or "").strip(),
                    sources=[
                        Citation(
                            kind="openlibrary",
                            title=cand.title,
                            url=cand.source_url,
                        )
                    ],
                )
            )
        return out

    def _materialize_ai_suggested(self, llm_recs: list[_LLMRec]) -> list[BookRec]:
        out: list[BookRec] = []
        for rec in llm_recs:
            title = (rec.title or "").strip()
            author = (rec.author or "").strip()
            if not title or not author:
                logger.info("profile.refresh.dropped_ai_suggested_missing_fields")
                continue
            out.append(
                BookRec(
                    candidate_id=None,
                    title=title,
                    author=author,
                    identity=None,
                    source_type="ai_suggested",
                    source_url=None,
                    owned_state="not_owned",
                    rationale=(rec.rationale or "").strip(),
                    sources=None,
                )
            )
        return out

    # ------- private helpers ----------------------------------------------

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


def _canonical_scheme_value(identity: DocumentIdentity) -> tuple[str, str]:
    """Return the strongest canonical (scheme, value) pair on a DocumentIdentity.

    Prefers ``metadata_id``; falls back to ``content_hash``. Raises
    ``ValueError`` if neither is present (callers must pre-resolve aliases).
    """
    if identity.metadata_id is not None:
        return ("metadata_id", identity.metadata_id)
    if identity.content_hash is not None:
        return ("content_hash", identity.content_hash)
    raise ValueError("identity has no canonical scheme")


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


# ===========================================================================
# Reader Profile — deterministic stats computation (pr-α / Bundle 3).
# ---------------------------------------------------------------------------
# `_compute_reader_stats` mirrors PR9's `pick_priority` pattern at
# `quire_server/api/library.py:get_stats` exactly. Both call sites MUST stay
# in lockstep: the case-expression form was chosen over the spec's
# `(metadata_id IS NOT NULL) DESC` after PR9 flagged the latter as a
# tie-prone false ordering (coordinator §3.4 REJECT (e)). Any change to the
# pick-priority shape here MUST also update `get_stats` (and vice versa).
#
# pr-α is a no-op caller — `GET /ai/v1/profile` does NOT invoke this
# function (it's a cache-only read). pr-β's `POST /ai/v1/profile/refresh`
# is the first real caller.
# ===========================================================================


async def _compute_reader_stats(session: AsyncSession, user_id: str) -> ReaderStats:
    """Deterministic per-user library statistics for the Reader Profile.

    Mirrors PR9's `pick_priority` pattern at `library.py:get_stats` for the
    `finish_rate_by_theme` sub-query (REJECT (e) regression site). Other
    counts ride on top of the same `library_items LEFT JOIN documents JOIN
    progress` bridge that PR9 uses.

    The terminal-state invariant (coordinator §3.10) is honored on read:
    `finished_at IS NOT NULL` → counts as finished; `abandoned_at IS NOT
    NULL AND finished_at IS NULL` → counts as abandoned; everything else
    (including the corrupt-row case where both are set, which the DB
    constraint forbids on new writes) → ride finish_count.
    """

    # --------------------------------------------------------------
    # 1. total_books — alive library items for this user.
    # --------------------------------------------------------------
    total_books = (
        await session.scalar(
            select(func.count())
            .select_from(LibraryItem)
            .where(LibraryItem.user_id == user_id, LibraryItem.deleted_at.is_(None))
        )
    ) or 0

    # --------------------------------------------------------------
    # 2a. finished_count — finished_at IS NOT NULL.
    # --------------------------------------------------------------
    finished_count = (
        await session.scalar(
            select(func.count())
            .select_from(LibraryItem)
            .join(
                Document,
                and_(
                    Document.user_id == LibraryItem.user_id,
                    Document.content_hash == LibraryItem.content_hash,
                ),
            )
            .join(Progress, Progress.document_pk == Document.pk)
            .where(
                LibraryItem.user_id == user_id,
                LibraryItem.deleted_at.is_(None),
                Progress.finished_at.is_not(None),
            )
        )
    ) or 0

    # --------------------------------------------------------------
    # 2b. abandoned_count — abandoned_at set AND finished_at unset.
    #     Defensive read (§3.10): if both are non-null on a legacy row,
    #     finished wins — that row contributes to finished_count above
    #     and NOT to abandoned_count here.
    # --------------------------------------------------------------
    abandoned_count = (
        await session.scalar(
            select(func.count())
            .select_from(LibraryItem)
            .join(
                Document,
                and_(
                    Document.user_id == LibraryItem.user_id,
                    Document.content_hash == LibraryItem.content_hash,
                ),
            )
            .join(Progress, Progress.document_pk == Document.pk)
            .where(
                LibraryItem.user_id == user_id,
                LibraryItem.deleted_at.is_(None),
                Progress.finished_at.is_(None),
                Progress.abandoned_at.is_not(None),
            )
        )
    ) or 0

    # --------------------------------------------------------------
    # 2c. in_progress_count — neither terminal flag, percent>0.
    # --------------------------------------------------------------
    in_progress_count = (
        await session.scalar(
            select(func.count())
            .select_from(LibraryItem)
            .join(
                Document,
                and_(
                    Document.user_id == LibraryItem.user_id,
                    Document.content_hash == LibraryItem.content_hash,
                ),
            )
            .join(Progress, Progress.document_pk == Document.pk)
            .where(
                LibraryItem.user_id == user_id,
                LibraryItem.deleted_at.is_(None),
                Progress.finished_at.is_(None),
                Progress.abandoned_at.is_(None),
                Progress.percent > 0,
            )
        )
    ) or 0

    # --------------------------------------------------------------
    # 3. most_read_authors — unnest `authors` jsonb and group, top 5.
    #    Mirrors library.py's top_authors verbatim.
    # --------------------------------------------------------------
    author_col = (
        func.jsonb_array_elements_text(LibraryItem.authors)
        .table_valued("value")
        .render_derived(name="author")
    )
    author_value = literal_column("author.value")
    author_count_expr = func.count(func.distinct(LibraryItem.pk))
    author_rows = (
        await session.execute(
            select(author_value.label("name"), author_count_expr.label("c"))
            .select_from(LibraryItem)
            .join(author_col, literal_column("true"))
            .where(
                LibraryItem.user_id == user_id,
                LibraryItem.deleted_at.is_(None),
            )
            .group_by(author_value)
            .order_by(author_count_expr.desc(), author_value.asc())
            .limit(5)
        )
    ).all()
    most_read_authors = [AuthorCount(name=row.name, count=int(row.c)) for row in author_rows]

    # --------------------------------------------------------------
    # 4. finish_rate_by_theme — REJECT-(e) regression site.
    #    Mirrors library.py:get_stats pick_priority — see coordinator
    #    §3.4 REJECT (e). DO NOT change without updating get_stats too.
    # --------------------------------------------------------------
    pick_priority = case(
        (
            and_(
                BookInsight.metadata_id.is_not(None),
                BookInsight.metadata_id == LibraryItem.metadata_id,
            ),
            0,
        ),
        else_=1,
    )

    picked = (
        select(
            LibraryItem.pk.label("library_item_pk"),
            LibraryItem.content_hash.label("library_item_content_hash"),
            LibraryItem.user_id.label("library_item_user_id"),
            BookInsight.id.label("book_insight_id"),
        )
        .select_from(LibraryItem)
        .join(
            BookInsight,
            and_(
                BookInsight.superseded_at.is_(None),
                (
                    (
                        BookInsight.metadata_id.is_not(None)
                        & (BookInsight.metadata_id == LibraryItem.metadata_id)
                    )
                    | (BookInsight.content_hash == LibraryItem.content_hash)
                ),
            ),
        )
        .where(
            LibraryItem.user_id == user_id,
            LibraryItem.deleted_at.is_(None),
        )
        .order_by(LibraryItem.pk, pick_priority, BookInsight.generated_at.desc())
        .distinct(LibraryItem.pk)
        .subquery("picked_insight")
    )

    # For each picked (library_item, theme): is the book finished?
    # The picked CTE already carries user_id+content_hash so the JOIN to
    # documents stays user-scoped.
    finished_expr = case(
        (Progress.finished_at.is_not(None), 1),
        else_=0,
    )
    count_total = func.count(func.distinct(picked.c.library_item_pk))
    count_finished = func.sum(finished_expr)
    theme_rows = (
        await session.execute(
            select(
                BookTheme.theme.label("theme"),
                count_total.label("total"),
                count_finished.label("finished"),
            )
            .select_from(picked)
            .join(BookTheme, BookTheme.book_insight_id == picked.c.book_insight_id)
            .join(
                Document,
                and_(
                    Document.user_id == picked.c.library_item_user_id,
                    Document.content_hash == picked.c.library_item_content_hash,
                ),
                isouter=True,
            )
            .join(Progress, Progress.document_pk == Document.pk, isouter=True)
            .where(BookTheme.confidence >= 1.0)
            .group_by(BookTheme.theme)
            .order_by(BookTheme.theme.asc())
            .limit(10)
        )
    ).all()
    finish_rate_by_theme: dict[str, float] = {}
    for row in theme_rows:
        total = int(row.total or 0)
        finished = int(row.finished or 0)
        if total == 0:
            continue
        finish_rate_by_theme[row.theme] = finished / total

    # --------------------------------------------------------------
    # 5. books_with_themes_count (Lock #15 / coordinator §3.6).
    # --------------------------------------------------------------
    # Count of LibraryItem rows for this user with at least one BookInsight
    # at schema_version >= 4 AND superseded_at IS NULL. pr-α shipped this
    # at 0; pr-β populates it via the JSONB schema_version cast. The JOIN
    # mirrors PR9's alias-aware pattern (metadata-id first, content-hash
    # fallback). Used by pr-β's input_fingerprint and pr-γ's coverage
    # meter ("themes available for N of M books").
    books_with_themes_count = (
        await session.scalar(
            select(func.count(func.distinct(LibraryItem.pk)))
            .select_from(LibraryItem)
            .join(
                BookInsight,
                and_(
                    BookInsight.superseded_at.is_(None),
                    or_(
                        and_(
                            BookInsight.metadata_id.is_not(None),
                            BookInsight.metadata_id == LibraryItem.metadata_id,
                        ),
                        BookInsight.content_hash == LibraryItem.content_hash,
                    ),
                ),
            )
            .where(
                LibraryItem.user_id == user_id,
                LibraryItem.deleted_at.is_(None),
                func.cast(
                    func.json_extract_path_text(BookInsight.payload, "schema_version"),
                    Integer,
                )
                >= 4,
            )
        )
    ) or 0

    return ReaderStats(
        total_books=int(total_books),
        finished_count=int(finished_count),
        in_progress_count=int(in_progress_count),
        abandoned_count=int(abandoned_count),
        avg_session_minutes=None,
        finish_rate_by_theme=finish_rate_by_theme,
        most_read_authors=most_read_authors,
        books_with_themes_count=books_with_themes_count,
    )


# ===========================================================================
# Reader Profile — module-level helpers (pr-β / Bundle 3).
# ===========================================================================


def _ms_since(t0: datetime) -> int:
    """Return whole milliseconds elapsed since ``t0`` (UTC-aware)."""
    delta = datetime.now(UTC) - t0
    return max(int(delta.total_seconds() * 1000), 0)


_NORM_WS_RE = re.compile(r"\s+")


def _norm_text(s: str) -> str:
    """Lowercase + collapse whitespace + strip. For owner-exclusion compares."""
    if not s:
        return ""
    return _NORM_WS_RE.sub(" ", s.strip().lower())


def _first_author(authors) -> str:
    """Pick the first author name from a JSONB list. Empty string when absent."""
    if isinstance(authors, list) and authors:
        first = authors[0]
        if isinstance(first, str):
            return first
    return ""


def _compute_input_fingerprint(
    stats: ReaderStats,
    *,
    library_items_count: int,
    latest_progress_updated_at: datetime | None,
) -> str:
    """Soft staleness hint (Lock #12, coordinator §3.6). NOT a security primitive.

    16-hex-char SHA-256 prefix over the deterministic stats blob. Per-user
    only; collision risk is acknowledged as acceptable freshness metadata.
    Inputs MUST include ``books_with_themes_count`` (Lock #15 — pr-γ uses
    the fingerprint to detect when v4+ theme coverage shifted under the
    profile).

    Canonical representation contract (server + Android MUST agree):

      * ``latest_progress_updated_at`` is serialized as epoch milliseconds
        when present, or the literal string ``"none"`` when absent. This
        avoids ISO-8601 trailing-offset divergence (``+00:00`` on the
        server vs ``Z`` from ``java.time.Instant.toString()``) which
        previously caused gratuitous staleness banners.
    """
    if latest_progress_updated_at is None:
        progress_token = "none"
    else:
        progress_token = str(int(latest_progress_updated_at.timestamp() * 1000))
    raw = (
        f"{stats.finished_count}|{stats.in_progress_count}|{stats.abandoned_count}|"
        f"{progress_token}|"
        f"{library_items_count}|{stats.books_with_themes_count}"
    )
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:16]


def _build_low_data_payload(
    stats: ReaderStats,
    extended: _ReaderStatsExtended,
    *,
    model_id: str,
) -> ReaderProfilePayload:
    """Stats-only payload for the low-data short-circuit (NTH #6).

    No narrative, no recs, ``confidence='low'``. ``input_fingerprint`` is
    still computed so pr-γ's freshness compare works even from a low-data
    user's first refresh.
    """
    _ = model_id  # informational; the row's model_id column is filled by _upsert
    return ReaderProfilePayload(
        schema_version=1,
        stats=stats,
        narrative=None,
        in_library_recommendations=[],
        discovery_recommendations=[],
        ai_suggested_recommendations=[],
        confidence="low",
        input_fingerprint=_compute_input_fingerprint(
            stats,
            library_items_count=extended.library_items_count,
            latest_progress_updated_at=extended.latest_progress_updated_at,
        ),
    )


def _row_to_payload(row: ReaderProfile) -> ReaderProfilePayload:
    """Deserialize a persisted ``reader_profiles.payload`` blob.

    The payload column is the authoritative source for the response shape
    (the column mirrors top-level metadata for cheap WHERE filtering).
    """
    return ReaderProfilePayload.model_validate(row.payload)


def _build_reading_history_digest(
    *,
    finished: list[_ProgressRow],
    abandoned: list[_ProgressRow],
    in_progress: list[_ProgressRow],
    themes_by_metadata: dict[str, list[str]],
) -> dict:
    """Flatten three pre-ordered progress lists into the prompt's digest shape.

    Themes are attached from the ``metadata_id`` map (top 5 already capped
    upstream). Books with no themed insight surface ``themes=[]``.
    """

    def _row(p: _ProgressRow, status: str) -> dict:
        themes: list[str] = []
        if p.metadata_id is not None:
            themes = themes_by_metadata.get(p.metadata_id, [])[:5]
        return {
            "title": p.title,
            "author": p.author,
            "themes": themes,
            "status": status,
        }

    return {
        "finished": [_row(p, "finished") for p in finished],
        "abandoned": [_row(p, "abandoned") for p in abandoned],
        "in_progress": [_row(p, "in_progress") for p in in_progress],
    }


def _serialize_profile_user_prompt(
    *,
    stats: ReaderStats,
    digest: dict,
    lib_candidates: list[_LibCandidate],
    dis_candidates: list[_DiscoveryCandidate],
) -> str:
    """Render the JSON-ish user prompt the profile system prompt expects.

    The model expects sections labeled `stats`, `reading_history`,
    `in_library_candidates`, `discovery_candidates`. We emit them as
    indented JSON so the model's structured-output decoder doesn't have
    to parse free-form text.
    """
    import json as _json

    stats_block = {
        "total_books": stats.total_books,
        "finished_count": stats.finished_count,
        "in_progress_count": stats.in_progress_count,
        "abandoned_count": stats.abandoned_count,
        "books_with_themes_count": stats.books_with_themes_count,
        "top_authors": [{"name": a.name, "count": a.count} for a in stats.most_read_authors],
        "top_themes_finish_rate": stats.finish_rate_by_theme,
    }
    lib_block = [
        {
            "candidate_id": c.candidate_id,
            "title": c.title,
            "author": c.author,
        }
        for c in lib_candidates
    ]
    dis_block = [
        {
            "candidate_id": c.candidate_id,
            "title": c.title,
            "author": c.author,
            "source_url": c.source_url,
        }
        for c in dis_candidates
    ]
    body = {
        "stats": stats_block,
        "reading_history": digest,
        "in_library_candidates": lib_block,
        "discovery_candidates": dis_block,
    }
    return _json.dumps(body, indent=2, ensure_ascii=False)
