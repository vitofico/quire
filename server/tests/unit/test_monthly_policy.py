from datetime import UTC, datetime

import httpx
import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from quire_server.core.ai.monthly_policy import MonthlyPolicy, PolicySettings

NOW = datetime(2026, 10, 25, tzinfo=UTC)


def test_policy_is_opt_in_and_requires_paired_configuration():
    assert PolicySettings(_env_file=None).url is None
    with pytest.raises(ValidationError):
        PolicySettings(url="http://cloud", token=None)
    with pytest.raises(ValidationError):
        PolicySettings(url="http://cloud/path", token="x" * 32)


@pytest.mark.parametrize("change", ["account", "expiry", "limit", "status", "size"])
async def test_bad_policy_cannot_grant(change):
    data = {
        "version": 1,
        "account_id": "native:1",
        "expires_at": int(NOW.timestamp()) + 120,
        "limits": {"monthly_jobs": 5},
    }
    if change == "account":
        data["account_id"] = "native:2"
    elif change == "expiry":
        data["expires_at"] += 1
    elif change == "limit":
        data["limits"]["monthly_jobs"] = True

    def handler(request):
        assert request.headers["authorization"] == "Bearer " + "x" * 32
        if change == "size":
            return httpx.Response(200, content=b" " * 5000)
        return httpx.Response(503 if change == "status" else 200, json=data)

    async with httpx.AsyncClient(transport=httpx.MockTransport(handler)) as client:
        policy = MonthlyPolicy(
            PolicySettings(url="http://cloud", token="x" * 32), client, None, clock=lambda: NOW
        )
        with pytest.raises(HTTPException) as exc:
            await policy.limit("native:1")
        assert exc.value.status_code == 503


async def test_restored_closed_account_cannot_mint_new_session():
    data = {
        "version": 1,
        "account_id": "native:1",
        "expires_at": int(NOW.timestamp()) + 120,
        "limits": {"monthly_jobs": 0},
        "sync_writes": False,
        "account_closed": True,
    }
    async with httpx.AsyncClient(
        transport=httpx.MockTransport(lambda _: httpx.Response(200, json=data))
    ) as client:
        policy = MonthlyPolicy(
            PolicySettings(url="http://cloud", token="x" * 32), client, None, clock=lambda: NOW
        )
        with pytest.raises(HTTPException) as exc:
            await policy.allow_account("native:1")
        assert exc.value.status_code == 401
        assert await policy.allow_sync("native:1") is False
