"""HTTP coverage of shared insights and per-tenant generation audit rows."""

from __future__ import annotations

import asyncio
import json

import pytest
from sqlalchemy import select

from quire_server.db.models import AIGenerationLog, BookInsight

from .test_ai_auth_modes import _AUD, _ISS, _SECRET_1, _good_claims, _sign_token

pytestmark = pytest.mark.requires_ai


def _token_client(client_factory, *, model, prompt_version):
    return client_factory(
        auth_backend="calibreweb",
        ai_enabled=True,
        ai_auth_mode="token",
        ai_token_secrets=json.dumps({"k1": _SECRET_1}),
        ai_token_issuer=_ISS,
        ai_token_audience=_AUD,
        ai_base_url="http://model.example.test/v1",
        ai_model=model,
        ai_prompt_version=prompt_version,
        ai_sources="",
        skip_auth_overrides=True,
    )


async def _opt_in(client, subject, tenant):
    token = _sign_token(_good_claims(sub=subject, tenant=tenant), secret=_SECRET_1)
    headers = {"Authorization": f"Bearer {token}"}
    response = await client.put("/ai/v1/preferences", headers=headers, json={"ai_enabled": True})
    assert response.status_code == 200, response.text
    return headers


async def _lookup(client, headers, identity, title):
    response = await client.post(
        "/ai/v1/insights/lookup",
        headers=headers,
        json={"identity": identity, "bundle": {"title": title}},
    )
    assert response.status_code == 200, response.text
    return response.json()["payload"]


async def _read_audit(session, content_hash):
    insight = (
        await session.scalars(select(BookInsight).where(BookInsight.content_hash == content_hash))
    ).one()
    logs = (await session.scalars(select(AIGenerationLog).order_by(AIGenerationLog.id))).all()
    assert {log.book_insight_id for log in logs} == {insight.id}
    return logs


async def test_two_tenants_share_one_insight_two_log_rows(
    client_factory, app, session, model_transport, monkeypatch
):
    calls = []

    async def count(request):
        calls.append(request.url.path)

    intro = "Two-tenant convergence."
    identity = {"metadata_id": "tenant-shared", "content_hash": "ch-multi"}
    async with _token_client(
        client_factory, model="multi-tenant-model", prompt_version="mt1"
    ) as client:
        monkeypatch.setattr(
            app.state.ai_client,
            "_transport",
            model_transport({"schema_version": 2, "intro": intro, "confidence": "high"}, count),
        )
        alice = await _opt_in(client, "alice@tenant-a", "tenant-a")
        bob = await _opt_in(client, "bob@tenant-b", "tenant-b")

        generated = await _lookup(client, alice, identity, "Shared")
        cached = await _lookup(client, bob, identity, "Shared")

    assert generated["intro"] == cached["intro"] == intro
    assert calls == ["/v1/chat/completions"]
    logs = await _read_audit(session, "ch-multi")
    assert [(log.tenant_id, log.subject, log.status) for log in logs] == [
        ("tenant-a", "alice@tenant-a", "miss"),
        ("tenant-b", "bob@tenant-b", "hit"),
    ]


async def test_concurrent_waiters_coalesce_across_sessions(
    client_factory, app, session, model_transport, monkeypatch
):
    identities = [("u1", "t1"), ("u2", "t2"), ("u3", "t3")]
    calls = []
    provider_entered = asyncio.Event()
    release_provider = asyncio.Event()
    cold_reads = set()
    all_requests_cold = asyncio.Event()

    async def hold_provider(request):
        calls.append(request.url.path)
        provider_entered.set()
        await release_provider.wait()

    intro = "Coalesced."
    identity = {"content_hash": "ch-conc-coalesce"}
    async with _token_client(client_factory, model="coalesce-model", prompt_version="c1") as client:
        monkeypatch.setattr(
            app.state.ai_client,
            "_transport",
            model_transport(
                {"schema_version": 2, "intro": intro, "confidence": "high"}, hold_provider
            ),
        )
        real_cache_lookup = app.state.ai_orchestrator._cache_lookup

        async def observe_cache_lookup(request_session, *args, **kwargs):
            result = await real_cache_lookup(request_session, *args, **kwargs)
            if result is None:
                cold_reads.add(request_session)
                if len(cold_reads) == len(identities):
                    all_requests_cold.set()
            return result

        # Observe the real cache reads so the provider stays blocked until
        # every request has missed with its own request-scoped DB session.
        monkeypatch.setattr(app.state.ai_orchestrator, "_cache_lookup", observe_cache_lookup)
        headers = [await _opt_in(client, subject, tenant) for subject, tenant in identities]
        tasks = [
            asyncio.create_task(_lookup(client, header, identity, "Conc")) for header in headers
        ]
        try:
            async with asyncio.timeout(15):
                await provider_entered.wait()
                await all_requests_cold.wait()
                assert calls == ["/v1/chat/completions"]
                release_provider.set()
                payloads = await asyncio.gather(*tasks)
        finally:
            release_provider.set()
            for task in tasks:
                if not task.done():
                    task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)

    assert len(cold_reads) == len(payloads) == len(identities)
    assert all(payload["intro"] == intro for payload in payloads)
    assert calls == ["/v1/chat/completions"]
    logs = await _read_audit(session, "ch-conc-coalesce")
    assert sorted(log.status for log in logs) == ["hit", "hit", "miss"]
    assert sorted((log.subject, log.tenant_id) for log in logs) == identities
