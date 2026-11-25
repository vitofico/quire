"""``/convert/v1`` against real Postgres, real native sessions and a job folder.

The worker is played by the tests: they claim, publish and heartbeat in the
folder exactly as ``quire-convert worker`` does (``converter/.../worker.py``).
"""

from __future__ import annotations

import hashlib
import json
import logging
import os
import time
from datetime import timedelta
from uuid import UUID

import pytest
from sqlalchemy import text

from quire_server.core import convert

PASSWORD = "synthetic-password"  # pragma: allowlist secret
PDF = b"%PDF-1.7\n" + b"synthetic page content\n" * 200 + b"%%EOF\n"
EPUB = b"PK\x03\x04 synthetic epub bytes"
JOBS = "/convert/v1/jobs"


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


@pytest.fixture
def folder(tmp_path) -> convert.Folder:
    return convert.Folder(tmp_path / "convert")


@pytest.fixture
def service(client_factory, folder):
    def build(**env):
        return client_factory(
            **{
                "auth_backend": "native",
                "skip_auth_overrides": True,
                "convert_enabled": "true",
                "convert_dir": str(folder.root),
            }
            | env
        )

    return build


async def bearer(app, email="reader@example.test") -> dict[str, str]:
    await app.state.auth_backend.register(email=email, password=PASSWORD)
    token, _ = await app.state.auth_backend.login(email=email, password=PASSWORD)
    return {"Authorization": f"Bearer {token}"}


def heartbeat(folder: convert.Folder, engine="quire-convert 1; calibre 9.19.0") -> None:
    (folder.workers / "w1.json").write_text(json.dumps({"format": 1, "engine": engine}))


async def create(client, auth, data=PDF, key="key-00000001", **extra):
    body = {"idempotency_key": key, "sha256": sha(data), "bytes": len(data)} | extra
    return await client.post(JOBS, headers=auth, json=body)


async def upload(client, auth, job_id, data=PDF, **kwargs):
    headers = auth | {"Content-Type": "application/pdf"} | kwargs.pop("headers", {})
    return await client.put(f"{JOBS}/{job_id}/source", headers=headers, content=data, **kwargs)


async def queued(client, auth, data=PDF, key="key-00000001") -> str:
    created = await create(client, auth, data, key)
    assert created.status_code == 201, created.text
    sent = await upload(client, auth, created.json()["id"], data)
    assert sent.status_code == 202, sent.text
    assert sent.json()["state"] == "queued"
    return created.json()["id"]


def name(job_id: str, attempt: int = 1) -> str:
    return f"{UUID(job_id).hex}-{attempt}"


def claim(folder: convert.Folder, job_id: str, attempt: int = 1) -> None:
    (folder.running / name(job_id, attempt)).mkdir()


def publish(folder: convert.Folder, job_id: str, attempt: int = 1, **result) -> None:
    out = folder.root / "out-tmp"
    out.mkdir()
    epub = result.pop("epub_bytes", EPUB)
    body = {
        "format": 1,
        "job": UUID(job_id).hex,
        "attempt": attempt,
        "engine": "quire-convert 1; calibre 9.19.0; epubcheck 5.4.0",
        "outcome": "ready",
        "code": None,
        "detail": "converted",
        "retryable": False,
        "epub": {"sha256": sha(EPUB), "bytes": len(EPUB), "rendition_id": "urn:uuid:1234"},
        "issue_counts": {"warning": 1},
        "affected_pages": [],
        "issues": [],
        "seconds": 1.0,
    } | result
    if body["epub"] is not None:
        (out / "book.epub").write_bytes(epub)
    (out / "result.json").write_text(json.dumps(body))
    os.rename(out, folder.done / name(job_id, attempt))


async def sweep(app) -> None:
    from quire_server.config import get_settings

    assert await convert.sweep(app.state.identity_factory, app.state.convert_folder, get_settings())


async def shift(app, job_id: str, **columns: timedelta) -> None:
    """Move a job's timestamps into the past."""
    sets = ", ".join(f"{c} = {c} - make_interval(secs => :{c})" for c in columns)
    async with app.state.identity_factory() as session, session.begin():
        await session.execute(
            text(f"UPDATE convert_jobs SET {sets} WHERE id = :id"),
            {"id": UUID(job_id)} | {c: d.total_seconds() for c, d in columns.items()},
        )


async def job(client, auth, job_id) -> dict:
    response = await client.get(f"{JOBS}/{job_id}", headers=auth)
    assert response.status_code == 200, response.text
    return response.json()


async def test_off_by_default_and_advertised_when_on(client_factory, service, app, folder, session):
    async with client_factory(auth_backend="native", skip_auth_overrides=True) as client:
        auth = await bearer(app)
        assert (await client.get("/convert/v1/capabilities", headers=auth)).status_code == 404
        me = await client.get("/auth/v1/me", headers=auth)
        assert me.json()["capabilities"]["pdf_conversion"] is False

    async with service(convert_max_pages=500) as client:
        auth = await bearer(app, "second@example.test")
        caps = await client.get("/convert/v1/capabilities", headers=auth)
        assert caps.status_code == 200
        assert caps.headers["cache-control"] == "no-store"
        assert caps.json()["available"] is False
        heartbeat(folder)
        caps = (await client.get("/convert/v1/capabilities", headers=auth)).json()
        assert caps == {
            "available": True,
            "engine_revision": "quire-convert 1; calibre 9.19.0",
            "accepted_media_types": ["application/pdf"],
            "max_bytes": 100 * 1024 * 1024,
            "max_pages": 500,
            "time_limit_s": 1200,
            "max_active_jobs": 2,
            "daily_limit": 10,
            "retention_hours": 24,
            "modes": [{"id": "local", "sends_content_off_server": False}],
        }
        me = await client.get("/auth/v1/me", headers=auth)
        assert me.json()["capabilities"]["pdf_conversion"] is True
        assert (await client.get("/convert/v1/capabilities")).status_code == 401


async def test_round_trip(service, app, folder, session, caplog):
    caplog.set_level(logging.DEBUG)
    async with service() as client:
        auth = await bearer(app)
        assert (await create(client, auth)).status_code == 503
        heartbeat(folder)
        created = await create(client, auth)
        assert created.status_code == 201, created.text
        job_id = created.json()["id"]
        assert created.json()["state"] == "created"
        assert created.json()["engine_revision"] == "quire-convert 1; calibre 9.19.0"
        replay = await create(client, auth)
        assert replay.status_code == 200
        assert replay.json()["id"] == job_id
        other = await create(client, auth, PDF + b"\n")
        assert other.status_code == 409
        assert other.json() == {"detail": "idempotency_conflict"}

        sent = await upload(client, auth, job_id)
        assert sent.status_code == 202, sent.text
        entry = folder.queue / name(job_id)
        assert (entry / "source.pdf").read_bytes() == PDF
        spec = json.loads((entry / "job.json").read_text())
        assert spec == {
            "format": 1,
            "job": UUID(job_id).hex,
            "attempt": 1,
            "mode": "local",
            "sha256": sha(PDF),
            "bytes": len(PDF),
            "max_pages": 1000,
            "time_limit_s": 1200,
        }
        again = await upload(client, auth, job_id)
        assert again.status_code == 409

        claim(folder, job_id)
        await sweep(app)
        assert (await job(client, auth, job_id))["state"] == "running"
        early = await client.get(f"{JOBS}/{job_id}/epub", headers=auth)
        assert early.status_code == 409

        publish(folder, job_id)
        await sweep(app)
        done = await job(client, auth, job_id)
        assert done["state"] == "ready"
        assert done["engine_revision"] == "quire-convert 1; calibre 9.19.0; epubcheck 5.4.0"
        assert done["result"] == {
            "sha256": sha(EPUB),
            "bytes": len(EPUB),
            "rendition_id": "urn:uuid:1234",
            "issue_counts": {"warning": 1},
        }
        assert done["review"] is None and done["failure"] is None
        # The source and the worker's folders are cleared once the result is in.
        assert not folder.source(UUID(job_id)).exists()
        assert list(folder.queue.iterdir()) == []
        assert list(folder.done.iterdir()) == []

        first = await client.get(f"{JOBS}/{job_id}/epub", headers=auth)
        second = await client.get(f"{JOBS}/{job_id}/epub", headers=auth)
        assert first.status_code == second.status_code == 200
        assert first.content == second.content == EPUB
        assert first.headers["etag"] == f'"{sha(EPUB)}"'
        assert first.headers["content-type"] == "application/epub+zip"
        assert first.headers["cache-control"] == "no-store"

        listed = await client.get(JOBS, headers=auth)
        assert [j["id"] for j in listed.json()["jobs"]] == [job_id]

        acked = await client.post(f"{JOBS}/{job_id}/acknowledge", headers=auth)
        assert acked.json()["state"] == "acknowledged"
        assert (await client.post(f"{JOBS}/{job_id}/acknowledge", headers=auth)).status_code == 200
        assert not folder.result(UUID(job_id)).exists()
        gone = await client.get(f"{JOBS}/{job_id}/epub", headers=auth)
        assert gone.status_code == 410
    assert "synthetic page" not in caplog.text


async def test_another_account_sees_nothing(service, app, folder, session):
    async with service() as client:
        heartbeat(folder)
        alice = await bearer(app, "alice@example.test")
        bob = await bearer(app, "bob@example.test")
        created = await create(client, alice)
        job_id = created.json()["id"]
        # The same key is Bob's own.
        assert (await create(client, bob)).json()["id"] != job_id
        for response in (
            await client.get(f"{JOBS}/{job_id}", headers=bob),
            await upload(client, bob, job_id),
            await client.get(f"{JOBS}/{job_id}/epub", headers=bob),
            await client.post(f"{JOBS}/{job_id}/cancel", headers=bob),
            await client.post(f"{JOBS}/{job_id}/acknowledge", headers=bob),
        ):
            assert response.status_code == 404
            assert response.json() == {"detail": "job_not_found"}
        assert [j["id"] for j in (await client.get(JOBS, headers=alice)).json()["jobs"]] == [job_id]
        assert (await job(client, alice, job_id))["state"] == "created"


async def test_upload_checks(service, app, folder, session):
    async with service(convert_max_bytes=len(PDF) + 10) as client:
        heartbeat(folder)
        auth = await bearer(app)
        too_big = await create(client, auth, PDF + b"x" * 11)
        assert too_big.status_code == 413
        assert too_big.json() == {"detail": "source_too_large"}

        job_id = (await create(client, auth)).json()["id"]
        wrong_type = await upload(client, auth, job_id, headers={"Content-Type": "text/plain"})
        assert wrong_type.status_code == 415

        async def chunks(data):
            yield data[:100]
            yield data[100:]

        # Past the route's ceiling with no Content-Length: the middleware stops it.
        over = await upload(client, auth, job_id, chunks(PDF + b"x" * 11))
        assert over.status_code == 413
        # Under the ceiling but more than the job declared.
        longer = await upload(client, auth, job_id, chunks(PDF + b"x" * 5))
        assert longer.json() == {"detail": "size_mismatch"}
        declared = await upload(client, auth, job_id, PDF + b"x")
        assert declared.json() == {"detail": "size_mismatch"}
        corrupted = await upload(client, auth, job_id, PDF.replace(b"page", b"PAGE", 1))
        assert corrupted.status_code == 422
        assert corrupted.json() == {"detail": "checksum_mismatch"}
        assert (await job(client, auth, job_id))["state"] == "created"
        assert list(folder.incoming.iterdir()) == []
        assert list(folder.queue.iterdir()) == []

        fake = b"<html>" + b"x" * (len(PDF) - 6)
        fake_id = (await create(client, auth, fake, key="key-00000002")).json()["id"]
        refused = await upload(client, auth, fake_id, fake)
        assert refused.json() == {"detail": "not_a_pdf"}

        # Every other route keeps the global 1 MiB limit.
        big = await client.post(JOBS, headers=auth, content=b"x" * (1024 * 1024 + 1))
        assert big.status_code == 413

        # The source arrives chunked, without a Content-Length.
        assert (await upload(client, auth, job_id, chunks(PDF))).status_code == 202


async def test_limits_per_account(service, app, folder, session):
    async with service(convert_max_active_per_owner=2, convert_daily_limit=3) as client:
        heartbeat(folder)
        auth = await bearer(app)
        first = (await create(client, auth, key="key-00000001")).json()["id"]
        second = (await create(client, auth, key="key-00000002")).json()["id"]
        busy = await create(client, auth, key="key-00000003")
        assert busy.status_code == 429
        assert busy.json() == {"detail": "too_many_active_jobs"}
        assert busy.headers["retry-after"] == "60"

        await client.post(f"{JOBS}/{first}/cancel", headers=auth)
        third = (await create(client, auth, key="key-00000003")).json()["id"]
        for job_id in (second, third):
            await client.post(f"{JOBS}/{job_id}/cancel", headers=auth)
        # Cancelled jobs still count against the day.
        await shift(app, first, created_at=timedelta(hours=1))
        daily = await create(client, auth, key="key-00000004")
        assert daily.status_code == 429
        assert daily.json() == {"detail": "daily_limit_reached"}
        # The oldest leaves the rolling window an hour before the others.
        assert 23 * 3600 - 5 < int(daily.headers["retry-after"]) <= 23 * 3600 + 1
        # A replay of an existing job is not a new one.
        assert (await create(client, auth, key="key-00000001")).status_code == 200


async def test_cancel_during_a_run_discards_a_late_result(service, app, folder, session):
    async with service() as client:
        heartbeat(folder)
        auth = await bearer(app)
        job_id = await queued(client, auth)
        claim(folder, job_id)
        await sweep(app)
        cancelled = await client.post(f"{JOBS}/{job_id}/cancel", headers=auth)
        assert cancelled.json()["state"] == "cancelled"
        # The queue entry is the worker's signal to stop.
        assert not (folder.queue / name(job_id)).exists()
        assert not folder.source(UUID(job_id)).exists()
        publish(folder, job_id)
        await sweep(app)
        assert (await job(client, auth, job_id))["state"] == "cancelled"
        assert list(folder.done.iterdir()) == []
        assert not folder.result(UUID(job_id)).exists()
        again = await client.post(f"{JOBS}/{job_id}/cancel", headers=auth)
        assert again.status_code == 200
        assert (await client.post(f"{JOBS}/{job_id}/acknowledge", headers=auth)).status_code == 200


async def test_a_stale_attempt_cannot_publish_over_its_retry(service, app, folder, session):
    async with service() as client:
        heartbeat(folder)
        auth = await bearer(app)
        job_id = await queued(client, auth)
        claim(folder, job_id)
        await sweep(app)
        await shift(app, job_id, started_at=timedelta(seconds=1200 + 301))
        await sweep(app)
        retried = await job(client, auth, job_id)
        assert (retried["state"], retried["attempt"]) == ("queued", 2)
        assert not (folder.queue / name(job_id, 1)).exists()
        assert (folder.queue / name(job_id, 2) / "source.pdf").read_bytes() == PDF

        # The first worker wakes up and publishes: ignored.
        publish(folder, job_id, 1, epub_bytes=b"stale")
        await sweep(app)
        assert (await job(client, auth, job_id))["state"] == "queued"
        assert list(folder.done.iterdir()) == []

        claim(folder, job_id, 2)
        await sweep(app)
        await shift(app, job_id, started_at=timedelta(seconds=1200 + 301))
        await sweep(app)
        lost = await job(client, auth, job_id)
        assert lost["state"] == "failed"
        assert lost["failure"] == {"code": "worker_lost", "retryable": False, "detail": None}
        assert list(folder.queue.iterdir()) == []


async def test_results_the_server_will_not_trust(service, app, folder, session):
    async with service(convert_max_active_per_owner=5) as client:
        heartbeat(folder)
        auth = await bearer(app)
        bad_epub = await queued(client, auth, key="key-00000001")
        claim(folder, bad_epub)
        publish(folder, bad_epub, epub_bytes=b"not what the worker reported")
        wrong_job = await queued(client, auth, PDF + b"\n", key="key-00000002")
        claim(folder, wrong_job)
        publish(folder, wrong_job, job="0" * 32)
        refused = await queued(client, auth, PDF + b"\n\n", key="key-00000003")
        claim(folder, refused)
        publish(folder, refused, outcome="failed", code="encrypted", detail="the PDF is encrypted")
        await sweep(app)
        for job_id in (bad_epub, wrong_job):
            assert (await job(client, auth, job_id))["failure"]["code"] == "invalid_result"
            assert not folder.result(UUID(job_id)).exists()
        failed = await job(client, auth, refused)
        assert failed["failure"] == {
            "code": "encrypted",
            "retryable": False,
            "detail": "the PDF is encrypted",
        }
        assert (await client.get(f"{JOBS}/{refused}/epub", headers=auth)).status_code == 409
        assert (await client.post(f"{JOBS}/{refused}/cancel", headers=auth)).status_code == 409
        assert (await client.post(f"{JOBS}/{refused}/acknowledge", headers=auth)).status_code == 200


async def test_review_reports_its_pages(service, app, folder, session):
    async with service() as client:
        heartbeat(folder)
        auth = await bearer(app)
        job_id = await queued(client, auth)
        claim(folder, job_id)
        issue = {
            "code": "ocr.low_confidence",
            "severity": "error",
            "page": 3,
            "page_label": "iii",
            "message": "low OCR confidence",
        }
        publish(
            folder,
            job_id,
            outcome="review",
            detail="1 page needs a look",
            affected_pages=[{"page": 3, "label": "iii"}],
            issues=[issue],
        )
        await sweep(app)
        body = await job(client, auth, job_id)
        assert body["state"] == "review"
        assert body["review"] == {
            "reason": "1 page needs a look",
            "affected_pages": [{"page": 3, "label": "iii"}],
            "issues": [issue],
        }
        assert (await client.get(f"{JOBS}/{job_id}/epub", headers=auth)).content == EPUB


async def test_a_worker_that_stops_puts_the_attempt_back(service, app, folder, session):
    async with service() as client:
        heartbeat(folder)
        auth = await bearer(app)
        job_id = await queued(client, auth)
        claim(folder, job_id)
        await sweep(app)
        (folder.running / name(job_id)).rmdir()
        await sweep(app)
        body = await job(client, auth, job_id)
        assert (body["state"], body["attempt"]) == ("queued", 1)
        assert (folder.queue / name(job_id) / "job.json").is_file()


async def test_expiry_and_cleanup(service, app, folder, session):
    async with service(convert_max_active_per_owner=5) as client:
        heartbeat(folder)
        auth = await bearer(app)
        ready = await queued(client, auth, key="key-00000001")
        claim(folder, ready)
        publish(folder, ready)
        await sweep(app)
        never_sent = (await create(client, auth, key="key-00000002")).json()["id"]
        waiting = await queued(client, auth, PDF + b"\n", key="key-00000003")

        await shift(app, never_sent, created_at=timedelta(minutes=61))
        await shift(app, ready, expires_at=timedelta(hours=24))
        await shift(app, waiting, expires_at=timedelta(hours=24))
        stray = folder.store / f"{'a' * 32}.epub"
        stray.write_bytes(b"left behind")
        old_claim = folder.running / f"{'b' * 32}-1"
        old_claim.mkdir()
        past = time.time() - 120
        os.utime(old_claim, (past, past))
        await sweep(app)

        for job_id in (ready, never_sent, waiting):
            assert (await job(client, auth, job_id))["state"] == "expired"
        assert (await client.get(f"{JOBS}/{ready}/epub", headers=auth)).status_code == 410
        assert list(folder.store.iterdir()) == []
        assert list(folder.queue.iterdir()) == []
        assert not old_claim.exists()

        # Rows go a day after their files.
        await shift(app, ready, created_at=timedelta(hours=49))
        await sweep(app)
        assert (await client.get(f"{JOBS}/{ready}", headers=auth)).status_code == 404


async def test_account_purge_and_export_include_jobs(service, app, folder, session):
    from quire_server.api import account_lifecycle

    assert account_lifecycle.PRIVATE["convert_jobs"] == "user_id"
    async with service() as client:
        heartbeat(folder)
        auth = await bearer(app)
        job_id = (await create(client, auth)).json()["id"]
        exported = await client.get("/auth/v1/account/export", headers=auth)
        assert exported.status_code == 200, exported.text
        assert [row["id"] for row in exported.json()["convert_jobs"]] == [job_id]


def test_folder_layout(folder):
    folder.prepare()
    modes = {p.name: p.stat().st_mode & 0o777 for p in folder.root.iterdir()}
    assert modes == {
        "incoming": 0o700,
        "store": 0o700,
        "queue": 0o755,
        "running": 0o777,
        "done": 0o777,
        "workers": 0o777,
    }
    assert folder.engine() is None
    heartbeat(folder)
    stale = time.time() - convert.WORKER_FRESH_S - 1
    os.utime(folder.workers / "w1.json", (stale, stale))
    assert folder.engine() is None


async def test_an_unusable_folder_leaves_conversion_off(client_factory, app, tmp_path, session):
    (tmp_path / "file").write_text("")
    async with client_factory(
        auth_backend="native",
        skip_auth_overrides=True,
        convert_enabled="true",
        convert_dir=str(tmp_path / "file" / "convert"),
    ) as client:
        auth = await bearer(app)
        assert (await client.get("/convert/v1/capabilities", headers=auth)).status_code == 404
    assert any("PDF conversion is off" in w for w in app.state.config_warnings)
