"""Run Calibre's ebook-convert to make the baseline EPUB, isolated from any user setup."""

from __future__ import annotations

import logging
import os
import shutil
import signal
import subprocess
import tempfile
import time
from dataclasses import dataclass
from pathlib import Path

from ..models import StageError
from ..tools import Toolchain

log = logging.getLogger(__name__)

# Every setting that shapes the baseline is spelled out, even where it equals Calibre's
# default, so a changed default in a later Calibre release cannot silently change output.
BASELINE_ARGS: tuple[str, ...] = (
    "--input-profile=default",
    "--output-profile=default",
    # EPUB 3 output: we serialize EPUB 3.3 ourselves, Calibre's package is only parsed.
    "--epub-version=3",
    # No generated title/author cover: the metadata repair picks a cover from evidence.
    "--no-default-epub-cover",
    "--no-svg-cover",
    # The calibre engine (pdftohtml -xml plus Calibre's reflow) is the documented default
    # and auto-detects repeated headers and footers; the source map later accounts for
    # whatever it dropped, so that removal is checked rather than trusted.
    "--pdf-engine=calibre",
    "--pdf-header-skip=-1",
    "--pdf-footer-skip=-1",
    "--unwrap-factor=0.45",
    # Heuristic processing (dehyphenation, italicizing, heading markup) stays off: it
    # rewrites text without evidence we can audit. Deterministic repairs replace it.
    # (Heuristics are off unless --enable-heuristics is passed, so nothing is added.)
    # Keep full-size images; figure checks and covers need the original pixels.
    "--epub-max-image-size=none",
    # Chapter detection drives Calibre's nav; page breaks before chapters make each
    # detected chapter start a new spine document.
    "--chapter-mark=pagebreak",
    "--flow-size=260",
    # Below this many detected chapters Calibre also adds the book's internal links to
    # the nav (capped below). No inline contents page or metadata jacket is inserted:
    # --epub-inline-toc and --insert-metadata stay off.
    "--toc-threshold=6",
    "--max-toc-links=50",
)


@dataclass
class CalibreRun:
    epub_path: Path
    argv: list[str]
    version: str
    seconds: float
    log_path: Path


def run_calibre(
    pdf: Path,
    out_epub: Path,
    workdir: Path,
    toolchain: Toolchain,
    *,
    timeout: float = 900,
    debug_dir: Path | None = None,
) -> CalibreRun:
    """Convert ``pdf`` to ``out_epub``; the Calibre log lands in ``workdir/calibre.log``.

    ``debug_dir`` turns on --debug-pipeline snapshots for local diagnostics only; the
    product never reads them.
    """
    toolchain.require("ebook_convert")
    workdir.mkdir(parents=True, exist_ok=True)
    log_path = workdir / "calibre.log"
    with tempfile.TemporaryDirectory(prefix="calibre-", dir=workdir) as tmp:
        sandbox = Path(tmp)
        env = _isolated_env(sandbox)
        # Calibre copies the input file name into every content document's <title> and
        # into dc:title when the PDF has none, so hide the caller's path behind a link.
        source = sandbox / "source.pdf"
        source.symlink_to(pdf.resolve())
        target = sandbox / "out.epub"
        argv = [str(toolchain.ebook_convert), source.name, target.name, *BASELINE_ARGS]
        if debug_dir is not None:
            argv.append(f"--debug-pipeline={debug_dir}")
        started = time.monotonic()
        with log_path.open("wb") as log_file:
            code = _run(argv, env, sandbox, log_file, timeout)
        seconds = time.monotonic() - started
        if code != 0 or not target.is_file():
            raise StageError(
                "calibre", f"ebook-convert exited with {code}; see {log_path}", retryable=False
            )
        out_epub.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(target, out_epub)
    log.info("calibre converted in %.1fs", seconds)
    return CalibreRun(
        epub_path=out_epub,
        argv=argv,
        version=toolchain.versions().get("calibre", "unknown"),
        seconds=seconds,
        log_path=log_path,
    )


def _isolated_env(sandbox: Path) -> dict[str, str]:
    # Start from nothing but PATH so no CALIBRE_* or locale setting of the user leaks in.
    dirs = {name: sandbox / name for name in ("home", "config", "cache", "tmp")}
    for path in dirs.values():
        path.mkdir()
    return {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "HOME": str(dirs["home"]),
        "TMPDIR": str(dirs["tmp"]),
        "CALIBRE_CONFIG_DIRECTORY": str(dirs["config"]),
        "CALIBRE_CACHE_DIRECTORY": str(dirs["cache"]),
        "CALIBRE_TEMP_DIR": str(dirs["tmp"]),
        "CALIBRE_OVERRIDE_LANG": "en",
        "LANG": "en_US.UTF-8",
        "LC_ALL": "en_US.UTF-8",
    }


def _run(argv: list[str], env: dict[str, str], cwd: Path, log_file, timeout: float) -> int:
    # Own process group, so a timeout also kills Calibre's worker and pdftohtml children.
    proc = subprocess.Popen(
        argv,
        env=env,
        cwd=cwd,
        stdin=subprocess.DEVNULL,
        stdout=log_file,
        stderr=subprocess.STDOUT,
        start_new_session=True,
    )
    try:
        return proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        os.killpg(proc.pid, signal.SIGKILL)
        proc.wait()
        raise StageError("calibre", f"timed out after {timeout:.0f}s", retryable=True) from None
