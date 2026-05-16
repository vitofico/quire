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
from sqlalchemy.ext.asyncio import AsyncSession

from opds_sync.api.ai_schemas import (
    AiStyle,
    BookInsightPayload,
    BookInsightResponse,
    Citation,
    DocumentIdentity,
    MetadataBundle,
    SeriesInsight,
)
from opds_sync.core.ai.prompts import SYSTEM_PROMPT, compose_user_prompt
from opds_sync.core.logging_ctx import request_id_var
from opds_sync.db.models import AIGenerationLog, AIUsageDaily, BookInsight

logger = logging.getLogger(__name__)


class QuotaExceeded(Exception):
    """Raised when the per-user daily budget is full. Carries values for the 429 body."""

    def __init__(self, *, used: int, limit: int, resets_at: datetime) -> None:
        self.used = used
        self.limit = limit
        self.resets_at = resets_at
        super().__init__(f"daily budget exhausted: {used}/{limit}")


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
            )
            return self._row_to_response(row)

    async def invalidate(self, session: AsyncSession, ident: DocumentIdentity) -> int:
        stmt = delete(BookInsight).where(
            BookInsight.model_id == self.model_id,
            BookInsight.prompt_version == self.prompt_version,
        )
        if ident.metadata_id:
            stmt = stmt.where(
                (BookInsight.metadata_id == ident.metadata_id)
                | (BookInsight.content_hash == ident.content_hash)
            )
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
                raise
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
        row = BookInsight(
            metadata_id=ident.metadata_id,
            content_hash=ident.content_hash,
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
        await self._log_generation(
            session,
            book_insight_id=row.id,
            subject=user_id,
            tenant_id=tenant_id,
            status="miss",
            latency_ms=latency_ms,
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
        retriever = self.retriever_factory(session)
        tasks = []
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

    async def _acquire_identity_lock(
        self, ident: DocumentIdentity, *, tone: str, language: str
    ) -> asyncio.Lock:
        # Per-(identity, tone, language): users hitting the same book with
        # different cache-key dimensions should not serialize through one lock.
        key = f"{ident.metadata_id or ident.content_hash}|{tone}|{language}"
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
