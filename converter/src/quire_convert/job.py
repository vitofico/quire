"""Job directories with numbered attempts and an atomically published manifest.

Layout::

    <root>/<job_id>/manifest.json          written last, via temp file + fsync + os.replace
    <root>/<job_id>/attempts/<n>/work/      private scratch space
    <root>/<job_id>/attempts/<n>/book.epub
    <root>/<job_id>/attempts/<n>/report.json
    <root>/<job_id>/attempts/<n>/diagnostics/

Consumers read only the manifest and follow its artifact paths. Every attempt publishes
its own manifest, and a failed or unsupported attempt names no EPUB, so a failed rerun
can never expose an older attempt's EPUB as its result.
"""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .models import JOB_FORMAT_VERSION, Outcome

MANIFEST = "manifest.json"
EPUB_OUTCOMES = (Outcome.READY, Outcome.REVIEW)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def job_id_for(input_sha256: str, settings: dict[str, Any]) -> str:
    """Short, stable id for one input under one set of effective settings."""
    payload = json.dumps({"input": input_sha256, "settings": settings}, sort_keys=True)
    return hashlib.sha256(payload.encode()).hexdigest()[:16]


def fsync_path(path: Path) -> None:
    """Flush a file or directory to disk."""
    fd = os.open(path, os.O_RDONLY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic_write_bytes(path: Path, data: bytes) -> None:
    """Write ``data`` to ``path`` so readers see either the old or the new file, never half."""
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
    try:
        with os.fdopen(fd, "wb") as fh:
            fh.write(data)
            fh.flush()
            os.fsync(fh.fileno())
        os.replace(tmp, path)
    except BaseException:
        Path(tmp).unlink(missing_ok=True)
        raise
    fsync_path(path.parent)


@dataclass(frozen=True)
class Attempt:
    number: int
    dir: Path

    def path(self, name: str) -> Path:
        return self.dir / name

    @property
    def work(self) -> Path:
        return self.dir / "work"

    @property
    def diagnostics(self) -> Path:
        return self.dir / "diagnostics"


@dataclass(frozen=True)
class Job:
    id: str
    dir: Path

    @classmethod
    def open_or_create(
        cls,
        root: Path,
        job_id: str | None = None,
        *,
        input_sha256: str | None = None,
        settings: dict[str, Any] | None = None,
    ) -> Job:
        """Open ``<root>/<job_id>``, creating it. Without ``job_id`` the id is derived from
        the input checksum and effective settings."""
        if job_id is None:
            if input_sha256 is None:
                raise ValueError("job_id or input_sha256 is required")
            job_id = job_id_for(input_sha256, settings or {})
        job_dir = Path(root) / job_id
        (job_dir / "attempts").mkdir(parents=True, exist_ok=True)
        return cls(job_id, job_dir)

    def new_attempt(self) -> Attempt:
        """Claim the next attempt number. ``mkdir`` is atomic, so two processes racing for
        the same number cannot both win; the loser moves on to the next number."""
        attempts = self.dir / "attempts"
        numbers = [int(p.name) for p in attempts.iterdir() if p.name.isdigit()]
        number = max(numbers, default=0) + 1
        while True:
            attempt_dir = attempts / str(number)
            try:
                attempt_dir.mkdir()
            except FileExistsError:
                number += 1
                continue
            break
        attempt = Attempt(number, attempt_dir)
        attempt.work.mkdir()
        attempt.diagnostics.mkdir()
        return attempt

    def publish(
        self,
        attempt: Attempt,
        outcome: Outcome,
        artifacts: dict[str, Path],
        summary: dict[str, Any],
    ) -> dict[str, Any]:
        """Publish ``attempt`` as the job's result. Artifacts are flushed before the
        manifest that names them is atomically put in place."""
        if "epub" in artifacts and outcome not in EPUB_OUTCOMES:
            raise ValueError(f"a {outcome.value} attempt cannot publish an EPUB")
        entries = {}
        for role, path in sorted(artifacts.items()):
            path = Path(path)
            if not path.resolve().is_relative_to(attempt.dir.resolve()):
                raise ValueError(f"artifact {role} is outside attempt {attempt.number}")
            fsync_path(path)
            entries[role] = {
                "path": path.resolve().relative_to(self.dir.resolve()).as_posix(),
                "sha256": sha256_file(path),
            }
        fsync_path(attempt.dir)
        manifest = {
            "job_format_version": JOB_FORMAT_VERSION,
            "job_id": self.id,
            "attempt": attempt.number,
            "outcome": outcome.value,
            "artifacts": entries,
            "summary": summary,
        }
        data = json.dumps(manifest, indent=2, sort_keys=True).encode() + b"\n"
        atomic_write_bytes(self.dir / MANIFEST, data)
        return manifest

    def read_manifest(self) -> dict[str, Any] | None:
        path = self.dir / MANIFEST
        if not path.exists():
            return None
        return json.loads(path.read_text())

    def artifact(self, role: str) -> Path | None:
        """Path of a published artifact, or None when the manifest names none."""
        manifest = self.read_manifest()
        entry = (manifest or {}).get("artifacts", {}).get(role)
        return self.dir / entry["path"] if entry else None
