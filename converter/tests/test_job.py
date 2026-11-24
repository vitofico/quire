import json
import os
import threading

import pytest

from quire_convert import job as job_module
from quire_convert.job import Job, job_id_for, sha256_file
from quire_convert.models import JOB_FORMAT_VERSION, Outcome


def _job(tmp_path, job_id="j1"):
    return Job.open_or_create(tmp_path, job_id)


def _artifacts(attempt, epub=True):
    report = attempt.path("report.json")
    report.write_text("{}")
    out = {"report": report}
    if epub:
        book = attempt.path("book.epub")
        book.write_bytes(b"epub bytes")
        out["epub"] = book
    return out


def test_default_job_id_depends_on_input_and_settings(tmp_path):
    a = Job.open_or_create(tmp_path, input_sha256="aa", settings={"variant": "rules"})
    b = Job.open_or_create(tmp_path, input_sha256="aa", settings={"variant": "rules"})
    c = Job.open_or_create(tmp_path, input_sha256="aa", settings={"variant": "baseline"})
    assert a.id == b.id == job_id_for("aa", {"variant": "rules"})
    assert a.id != c.id
    with pytest.raises(ValueError):
        Job.open_or_create(tmp_path)


def test_attempts_are_numbered_and_laid_out(tmp_path):
    job = _job(tmp_path)
    first, second = job.new_attempt(), job.new_attempt()
    assert (first.number, second.number) == (1, 2)
    assert first.dir == tmp_path / "j1" / "attempts" / "1"
    assert first.work.is_dir() and first.diagnostics.is_dir()


def test_attempt_numbering_survives_a_stale_listing(tmp_path, monkeypatch):
    job = _job(tmp_path)
    job.new_attempt()
    # Another process claimed 1 after this one listed the directory.
    monkeypatch.setattr(type(job.dir), "iterdir", lambda self: iter([]))
    assert job.new_attempt().number == 2


def test_concurrent_attempts_get_unique_numbers(tmp_path):
    job = _job(tmp_path)
    barrier = threading.Barrier(12)
    numbers = []

    def claim():
        barrier.wait()
        numbers.append(job.new_attempt().number)

    threads = [threading.Thread(target=claim) for _ in range(12)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert sorted(numbers) == list(range(1, 13))


def test_manifest_names_artifacts_with_checksums(tmp_path):
    job = _job(tmp_path)
    attempt = job.new_attempt()
    assert job.read_manifest() is None
    artifacts = _artifacts(attempt)
    manifest = job.publish(attempt, Outcome.READY, artifacts, {"reason": "ok"})
    assert manifest == job.read_manifest()
    assert manifest["job_format_version"] == JOB_FORMAT_VERSION
    assert manifest["attempt"] == 1 and manifest["outcome"] == "ready"
    epub = manifest["artifacts"]["epub"]
    assert epub["path"] == "attempts/1/book.epub"
    assert epub["sha256"] == sha256_file(artifacts["epub"])
    assert job.artifact("epub") == artifacts["epub"]


def test_manifest_is_written_after_artifacts_are_flushed(tmp_path, monkeypatch):
    job = _job(tmp_path)
    attempt = job.new_attempt()
    artifacts = _artifacts(attempt)
    events = []
    real_fsync, real_replace = job_module.fsync_path, os.replace
    monkeypatch.setattr(job_module, "fsync_path", lambda p: (events.append(p), real_fsync(p)))
    monkeypatch.setattr(
        job_module.os, "replace", lambda a, b: (events.append(("replace", b)), real_replace(a, b))
    )
    job.publish(attempt, Outcome.READY, artifacts, {})
    replace_at = next(i for i, e in enumerate(events) if isinstance(e, tuple))
    assert artifacts["epub"] in events[:replace_at] and artifacts["report"] in events[:replace_at]
    assert events[replace_at] == ("replace", job.dir / "manifest.json")


def test_failed_rerun_never_exposes_the_older_epub(tmp_path):
    job = _job(tmp_path)
    good = job.new_attempt()
    job.publish(good, Outcome.READY, _artifacts(good), {})
    failed = job.new_attempt()
    job.publish(failed, Outcome.FAILED, _artifacts(failed, epub=False), {})
    manifest = job.read_manifest()
    assert manifest["attempt"] == 2 and manifest["outcome"] == "failed"
    assert "epub" not in manifest["artifacts"]
    assert job.artifact("epub") is None


@pytest.mark.parametrize("outcome", [Outcome.FAILED, Outcome.UNSUPPORTED])
def test_failed_or_unsupported_attempt_cannot_publish_an_epub(tmp_path, outcome):
    job = _job(tmp_path)
    attempt = job.new_attempt()
    with pytest.raises(ValueError):
        job.publish(attempt, outcome, _artifacts(attempt), {})
    assert job.read_manifest() is None


def test_artifacts_must_belong_to_the_attempt(tmp_path):
    job = _job(tmp_path)
    old, new = job.new_attempt(), job.new_attempt()
    with pytest.raises(ValueError):
        job.publish(new, Outcome.READY, _artifacts(old), {})


def test_interrupted_publish_keeps_the_previous_manifest(tmp_path, monkeypatch):
    job = _job(tmp_path)
    first = job.new_attempt()
    job.publish(first, Outcome.READY, _artifacts(first), {})
    before = (job.dir / "manifest.json").read_text()

    def crash(a, b):
        raise OSError("disk full")

    monkeypatch.setattr(job_module.os, "replace", crash)
    second = job.new_attempt()
    with pytest.raises(OSError):
        job.publish(second, Outcome.READY, _artifacts(second), {})
    assert (job.dir / "manifest.json").read_text() == before
    assert json.loads(before)["attempt"] == 1
    assert not [p for p in job.dir.iterdir() if p.name.endswith(".tmp")]
