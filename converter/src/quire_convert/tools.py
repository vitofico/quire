"""Locate the external tools the converter shells out to and report their versions."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
from dataclasses import dataclass, field, fields
from pathlib import Path

from .models import StageError

CALIBRE_PIN = "9.19"  # major.minor; any 9.19.x patch release is accepted
EPUBCHECK_PIN = "5.4.0"

_CALIBRE_APP = Path("/Applications/calibre.app/Contents/MacOS")
_JAVA_DIRS = (
    Path("/opt/homebrew/opt/openjdk@21/bin"),
    Path("/opt/homebrew/opt/openjdk@17/bin"),
)
_POPPLER = ("pdftotext", "pdftohtml", "pdftoppm", "pdftocairo", "pdfimages", "pdfinfo")


@dataclass
class Toolchain:
    ebook_convert: Path | None = None
    pdftotext: Path | None = None
    pdftohtml: Path | None = None
    pdftoppm: Path | None = None
    pdftocairo: Path | None = None
    pdfimages: Path | None = None
    pdfinfo: Path | None = None
    java: Path | None = None
    epubcheck_jar: Path | None = None
    _versions: dict[str, str] | None = field(default=None, repr=False, compare=False)

    def require(self, *names: str) -> None:
        """Raise a non-retryable StageError naming every requested tool that is missing."""
        known = {f.name for f in fields(self) if not f.name.startswith("_")}
        unknown = [n for n in names if n not in known]
        if unknown:
            raise ValueError(f"unknown tool names: {', '.join(unknown)}")
        missing = [n for n in names if getattr(self, n) is None]
        if missing:
            raise StageError(
                "tools",
                f"missing tools: {', '.join(missing)} "
                "(set QC_EBOOK_CONVERT, QC_JAVA or EPUBCHECK_JAR, or run "
                "scripts/fetch-epubcheck.sh)",
                retryable=False,
            )

    def versions(self) -> dict[str, str]:
        """Versions of the tools that were found: calibre, poppler, java, epubcheck."""
        if self._versions is None:
            found: dict[str, str] = {}
            if self.ebook_convert:
                found["calibre"] = _version(
                    [str(self.ebook_convert), "--version"], r"calibre ([\d.]+)"
                )
            if self.pdfinfo:
                found["poppler"] = _version([str(self.pdfinfo), "-v"], r"version ([\d.]+)")
            if self.java:
                found["java"] = _version([str(self.java), "-version"], r'version "([^"]+)"')
            if self.java and self.epubcheck_jar:
                argv = [str(self.java), "-jar", str(self.epubcheck_jar), "--version"]
                found["epubcheck"] = _version(argv, r"EPUBCheck v([\d.]+)")
            self._versions = found
        return dict(self._versions)

    def pin_mismatches(self) -> list[str]:
        """Human-readable notes for found tools whose version differs from the pin."""
        versions = self.versions()
        notes = []
        calibre = versions.get("calibre")
        if calibre is not None and not (
            calibre == CALIBRE_PIN or calibre.startswith(f"{CALIBRE_PIN}.")
        ):
            notes.append(f"calibre {calibre} differs from pinned {CALIBRE_PIN}.x")
        epubcheck = versions.get("epubcheck")
        if epubcheck is not None and epubcheck != EPUBCHECK_PIN:
            notes.append(f"epubcheck {epubcheck} differs from pinned {EPUBCHECK_PIN}")
        return notes


def find_tools() -> Toolchain:
    """Find every tool: env override first, then PATH, then known install locations.

    An override that is set but unusable counts as missing; the search never falls back
    past it, so a pinned path cannot be silently swapped for another install.
    """
    tools = Toolchain(
        ebook_convert=_located(
            "QC_EBOOK_CONVERT", [_which("ebook-convert"), _CALIBRE_APP / "ebook-convert"]
        ),
        java=_located("QC_JAVA", _java_candidates(), check=_java_runs),
        epubcheck_jar=_located("EPUBCHECK_JAR", [_default_jar()]),
    )
    for name in _POPPLER:
        setattr(tools, name, _which(name))
    return tools


def _located(var: str, candidates: list[Path | None], check=None) -> Path | None:
    def usable(path: Path | None) -> bool:
        return path is not None and path.is_file() and (check is None or check(path))

    if value := os.environ.get(var):
        path = Path(value).expanduser()
        return path if usable(path) else None
    return next((p for p in candidates if usable(p)), None)


def _which(name: str) -> Path | None:
    found = shutil.which(name)
    return Path(found) if found else None


def _default_jar() -> Path:
    cache = Path(os.environ.get("XDG_CACHE_HOME") or Path.home() / ".cache")
    return cache / "quire-convert" / f"epubcheck-{EPUBCHECK_PIN}" / "epubcheck.jar"


def _java_candidates() -> list[Path | None]:
    # The macOS /usr/bin/java stub exists but fails without a JDK, hence the run check.
    candidates = [_which("java")]
    if java_home := os.environ.get("JAVA_HOME"):
        candidates.append(Path(java_home) / "bin" / "java")
    return candidates + [d / "java" for d in _JAVA_DIRS]


def _java_runs(path: Path) -> bool:
    try:
        return (
            subprocess.run([str(path), "-version"], capture_output=True, timeout=30).returncode == 0
        )
    except (OSError, subprocess.TimeoutExpired):
        return False


def _version(argv: list[str], pattern: str) -> str:
    try:
        proc = subprocess.run(argv, capture_output=True, text=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return "unknown"
    match = re.search(pattern, proc.stdout + proc.stderr)
    return match.group(1) if match else "unknown"
