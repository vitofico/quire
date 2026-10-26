"""Optional generic HTTP AI allowance, disabled for ordinary self-hosting.

Policy is authenticated service-to-service; it cannot identify browser users.
It admits logical generation jobs, including bounded provider retries. Failed
jobs remain charged to make provider spend conservative. Cache hits are free.
"""

import asyncio
import re
from collections import OrderedDict
from datetime import UTC, date, datetime
from urllib.parse import quote, urlsplit

import httpx
from fastapi import HTTPException
from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy import text


class PolicySettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="QUIRE_POLICY_")
    url: str | None = None
    token: str | None = Field(None, repr=False)

    @model_validator(mode="after")
    def validate_policy(self):
        if bool(self.url) != bool(self.token):
            raise ValueError("policy URL and token must be configured together")
        if self.url:
            parts = urlsplit(self.url)
            _ = parts.port
            if (
                parts.scheme not in {"http", "https"}
                or not parts.hostname
                or parts.username
                or parts.password
                or parts.query
                or parts.fragment
                or parts.path not in {"", "/"}
                or len(self.token) < 32
            ):
                raise ValueError("policy requires fixed origin and a strong service token")
            self.url = self.url.rstrip("/")
        return self


class MonthlyPolicy:
    def __init__(self, settings, client, session_factory, clock=lambda: datetime.now(UTC)):
        self.settings, self.client, self.sf, self.clock = settings, client, session_factory, clock
        self.cache = OrderedDict()

    async def limit(self, account_id):
        now = int(self.clock().timestamp())
        cached = self.cache.get(account_id)
        if cached and cached[0] > now:
            return cached[1]
        try:
            async with (
                asyncio.timeout(4),
                self.client.stream(
                    "GET",
                    self.settings.url + "/cloud/internal/policy/" + quote(account_id, safe=""),
                    headers={"Authorization": "Bearer " + self.settings.token},
                    follow_redirects=False,
                    timeout=3,
                ) as response,
            ):
                if response.status_code != 200:
                    raise ValueError("unavailable")
                raw = bytearray()
                async for chunk in response.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw) > 4096:
                        raise ValueError("oversized")
                import json

                policy = json.loads(raw)
            expires = policy["expires_at"]
            limit = policy["limits"]["monthly_jobs"]
            now = int(self.clock().timestamp())
            window = policy.get("quota_window_start")
            window = date.fromisoformat(window) if window is not None else None
            sync_writes = policy.get("sync_writes", True)
            account_closed = policy.get("account_closed", False)
            if (
                policy["version"] != 1
                or policy["account_id"] != account_id
                or type(expires) is not int
                or not now < expires <= now + 120
                or type(limit) is not int
                or not 0 <= limit < 2**31
                or type(sync_writes) is not bool
                or type(account_closed) is not bool
                or (
                    window is not None
                    and not 0 <= (self.clock().astimezone(UTC).date() - window).days <= 31
                )
            ):
                raise ValueError("invalid policy")
        except (httpx.HTTPError, ValueError, KeyError, TypeError, TimeoutError) as exc:
            raise HTTPException(503, "ai_policy_unavailable") from exc
        self.cache[account_id] = (expires, limit, window, sync_writes, account_closed)
        self.cache.move_to_end(account_id)
        while len(self.cache) > 1024:
            self.cache.popitem(last=False)
        return limit

    async def reserve(self, account_id):
        if not isinstance(account_id, str) or not re.fullmatch(r"[\w:@.-]{1,200}", account_id):
            raise HTTPException(503, "ai_policy_identity_invalid")
        limit = await self.limit(account_id)
        if limit == 0:
            raise HTTPException(429, "monthly_ai_quota_exhausted")
        month = self.cache[account_id][2] or self.clock().astimezone(UTC).date().replace(day=1)
        # Atomic admission across processes and distinct books/profile requests.
        async with self.sf() as session:
            row = await session.execute(
                text(
                    "INSERT INTO ai_usage_monthly (user_id,month,count) VALUES (:user,:month,1) "
                    "ON CONFLICT (user_id,month) DO UPDATE SET count=ai_usage_monthly.count+1 "
                    "WHERE ai_usage_monthly.count < :limit RETURNING count"
                ),
                {"user": account_id, "month": month, "limit": limit},
            )
            admitted = row.scalar_one_or_none()
            await session.commit()
        if admitted is None:
            raise HTTPException(429, "monthly_ai_quota_exhausted")

    async def allow_sync(self, account_id):
        await self.limit(account_id)
        return self.cache[account_id][3]

    async def allow_account(self, account_id):
        await self.limit(account_id)
        if self.cache[account_id][4]:
            raise HTTPException(401, "account_closed")
