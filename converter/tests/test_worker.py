import hashlib
import json
import os
import subprocess
import sys
import time
import zipfile
from pathlib import Path
from types import SimpleNamespace

import pytest
from lxml import etree

from quire_convert import pipeline, worker
from quire_convert.models import Issue, Outcome, Severity
from quire_convert.source import poppler

PDF = b"%PDF-1.7\n1 0 obj<<>>endobj\n%%EOF\n"


def make_root(tmp_path: Path) -> Path:
    root = tmp_path / "jobs"
    for name in worker.DIRS:
        (root / name).mkdir(parents=True)
    return root


def queue_job(root: Path, attempt: int = 1, data: bytes = PDF, **job) -> str:
    name = f"{'a' * 32}-{attempt}"
    entry = root / "queue" / name
    entry.mkdir()
    (entry / "source.pdf").write_bytes(data)
    job = {
        "format": 1,
        "sha256": hashlib.sha256(data).hexdigest(),
        "max_pages": 50,
        "time_limit_s": 60,
    } | job
    (entry / "job.json").write_text(json.dumps(job))
    return name


def fake_runner(child: dict, epub: bytes | None = b"epub bytes"):
    calls = []

    def run(claim, withdrawn, tick):
        calls.append(claim.name)
        claim.out.mkdir()
        (claim.dir / "work").mkdir()
        if epub is not None:
            (claim.out / "book.epub").write_bytes(epub)
        (claim.out / "child.json").write_text(json.dumps(child))
        return None

    run.calls = calls
    return run


READY = {"outcome": "ready", "code": None, "epub": {"sha256": "x", "bytes": 10}}


def result_of(root: Path, name: str) -> dict:
    return json.loads((root / "done" / name / "result.json").read_text())


def test_publishes_a_result_under_the_attempt_name(tmp_path):
    root = make_root(tmp_path)
    name = queue_job(root, attempt=2)
    w = worker.Worker(root, engine="engine-1", runner=fake_runner(READY), worker_id="w1")
    assert w.run(once=True) == 0
    result = result_of(root, name)
    assert result["outcome"] == "ready"
    assert (result["job"], result["attempt"], result["engine"]) == ("a" * 32, 2, "engine-1")
    assert (root / "done" / name / "book.epub").read_bytes() == b"epub bytes"
    assert not (root / "done" / name / "child.json").exists()
    # The claim stays until the server reconciles; scratch space does not.
    assert json.loads((root / "running" / name / "claim.json").read_text())["worker"] == "w1"
    assert not (root / "running" / name / "work").exists()
    beat = json.loads((root / "workers" / "w1.json").read_text())
    assert beat["engine"] == "engine-1"


def test_a_claimed_attempt_is_never_claimed_again(tmp_path):
    root = make_root(tmp_path)
    queue_job(root)
    first = worker.Worker(root, engine="e", runner=fake_runner(READY))
    second = worker.Worker(root, engine="e", runner=fake_runner(READY))
    claim = first.claim()
    assert claim is not None
    assert second.claim() is None
    first.process(claim)
    # After publishing, the claim folder still blocks a second run of the attempt.
    assert second.claim() is None


def test_claims_the_oldest_queued_attempt_and_skips_foreign_names(tmp_path):
    root = make_root(tmp_path)
    (root / "queue" / ".incoming").mkdir()
    (root / "queue" / "not-a-job").mkdir()
    newer = queue_job(root, attempt=2)
    older = f"{'b' * 32}-1"
    os.rename(root / "queue" / newer, root / "queue" / older)
    queue_job(root, attempt=3)
    os.utime(root / "queue" / older, (1, 1))
    claim = worker.Worker(root, engine="e").claim()
    assert claim is not None and claim.name == older


def test_a_failed_conversion_publishes_no_epub(tmp_path):
    root = make_root(tmp_path)
    name = queue_job(root)
    child = {"outcome": "failed", "code": "encrypted", "detail": "the PDF is encrypted"}
    w = worker.Worker(root, engine="e", runner=fake_runner(child | {"epub": None}))
    w.run(once=True)
    assert result_of(root, name)["code"] == "encrypted"
    assert not (root / "done" / name / "book.epub").exists()


def test_a_source_that_does_not_match_the_job_fails_without_converting(tmp_path):
    root = make_root(tmp_path)
    name = queue_job(root)
    (root / "queue" / name / "source.pdf").write_bytes(PDF + b"tampered")
    runner = fake_runner(READY)
    worker.Worker(root, engine="e", runner=runner).run(once=True)
    assert runner.calls == []
    result = result_of(root, name)
    assert (result["outcome"], result["code"]) == ("failed", "worker_error")


def test_a_job_withdrawn_while_converting_is_not_published(tmp_path):
    root = make_root(tmp_path)
    name = queue_job(root)

    def runner(claim, withdrawn, tick):
        fake_runner(READY)(claim, withdrawn, tick)
        # The server cancels (or requeues) while the conversion finishes.
        (root / "queue" / name / "job.json").unlink()
        return None

    worker.Worker(root, engine="e", runner=runner).run(once=True)
    assert not (root / "done" / name).exists()
    assert not (root / "running" / name / "out").exists()


def test_a_stopping_worker_releases_its_claim(tmp_path):
    root = make_root(tmp_path)
    name = queue_job(root)
    w = worker.Worker(root, engine="e", runner=lambda claim, withdrawn, tick: "cancelled")
    w.stopping = True
    w.process(worker.Worker(root, engine="e").claim())
    assert not (root / "running" / name).exists()
    assert worker.Worker(root, engine="e").claim() is not None


@pytest.mark.parametrize(("code", "detail"), [("timed_out", "time limit"), ("worker_error", "")])
def test_a_runner_failure_becomes_a_failed_result(tmp_path, code, detail):
    root = make_root(tmp_path)
    name = queue_job(root)

    def runner(claim, withdrawn, tick):
        claim.out.mkdir()
        (claim.out / "book.epub").write_bytes(b"half")
        return code

    worker.Worker(root, engine="e", runner=runner).run(once=True)
    result = result_of(root, name)
    assert (result["outcome"], result["code"]) == ("failed", code)
    assert detail in result["detail"]
    assert sorted(p.name for p in (root / "done" / name).iterdir()) == ["result.json"]


def test_the_worker_exits_after_each_job(tmp_path):
    # So a conversion that took over the process cannot stay for the next job.
    root = make_root(tmp_path)
    first = queue_job(root, attempt=1)
    second = queue_job(root, attempt=2)
    runner = fake_runner(READY)
    assert worker.Worker(root, engine="e", runner=runner, poll_s=0.01).run() == 0
    assert runner.calls == [first]
    assert (root / "queue" / second).exists() and not (root / "running" / second).exists()


def test_missing_folders_stop_the_worker(tmp_path):
    assert worker.Worker(tmp_path, engine="e").run(once=True) == 1


# ---------------------------------------------------------------------- supervise


def sleeper(seconds: float) -> list[str]:
    return [sys.executable, "-c", f"import time; time.sleep({seconds})"]


def test_supervise_kills_a_conversion_past_its_time_limit(tmp_path):
    started = time.monotonic()
    code = worker.supervise(sleeper(30), tmp_path, 1, lambda: False, lambda: None)
    assert code == "timed_out"
    assert time.monotonic() - started < 10


def test_supervise_stops_when_the_job_is_withdrawn(tmp_path):
    ticks = []
    code = worker.supervise(
        sleeper(30), tmp_path, 60, lambda: len(ticks) >= 1, lambda: ticks.append(1)
    )
    assert code == "cancelled"


def test_supervise_reports_a_crashed_child(tmp_path):
    argv = [sys.executable, "-c", "raise SystemExit(3)"]
    assert worker.supervise(argv, tmp_path, 60, lambda: False, lambda: None) == "worker_error"
    assert worker.supervise(sleeper(0), tmp_path, 60, lambda: False, lambda: None) is None


@pytest.mark.skipif(not Path("/proc").is_dir(), reason="descendants are found through /proc")
def test_kill_tree_reaches_a_grandchild_in_its_own_session(tmp_path):
    pidfile = tmp_path / "grandchild.pid"
    script = (
        "import subprocess, sys, time\n"
        f"p = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'],"
        " start_new_session=True)\n"
        f"open({str(pidfile)!r}, 'w').write(str(p.pid))\n"
        "time.sleep(60)\n"
    )
    code = worker.supervise(
        [sys.executable, "-c", script], tmp_path, 60, pidfile.exists, lambda: None
    )
    assert code == "cancelled"
    pid = int(pidfile.read_text())
    time.sleep(0.2)
    state = (
        Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0]
        if Path(f"/proc/{pid}").exists()
        else "gone"
    )
    assert state in ("gone", "Z")


def gone(pid: int) -> bool:
    """Dead, or a zombie nobody has collected yet."""
    for _ in range(50):
        try:
            os.kill(pid, 0)
        except ProcessLookupError:
            return True
        stat = Path(f"/proc/{pid}/stat")
        if stat.exists() and stat.read_text().rsplit(")", 1)[1].split()[0] == "Z":
            return True
        time.sleep(0.05)
    return False


def test_a_child_that_exits_cleanly_takes_its_group_with_it(tmp_path):
    pidfile = tmp_path / "left.pid"
    script = (
        "import subprocess, sys\n"
        "p = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])\n"
        f"open({str(pidfile)!r}, 'w').write(str(p.pid))\n"
    )
    code = worker.supervise(
        [sys.executable, "-c", script], tmp_path, 60, lambda: False, lambda: None
    )
    assert code is None
    assert gone(int(pidfile.read_text()))


@pytest.mark.skipif(not sys.platform.startswith("linux"), reason="PR_SET_CHILD_SUBREAPER")
def test_a_process_that_detached_itself_dies_with_the_job(tmp_path):
    # A PDF that took over the child forks twice and starts its own session.
    pidfile = tmp_path / "left.pid"
    child = (
        "import os, sys, time\n"
        "if os.fork() == 0:\n"
        "    os.setsid()\n"
        "    if os.fork() == 0:\n"
        f"        open({str(pidfile)!r}, 'w').write(str(os.getpid())); time.sleep(60)\n"
        "    os._exit(0)\n"
        "time.sleep(0.5)\n"
    )
    parent = (
        "import sys\n"
        "from pathlib import Path\n"
        "from quire_convert import worker\n"
        "assert worker.become_subreaper()\n"
        f"code = worker.supervise([sys.executable, '-c', {child!r}], Path({str(tmp_path)!r}),"
        " 60, lambda: False, lambda: None)\n"
        "pid = int(Path(" + repr(str(pidfile)) + ").read_text())\n"
        "import os\n"
        "try:\n"
        "    os.kill(pid, 0); print('alive')\n"
        "except ProcessLookupError:\n"
        "    print('gone')\n"
    )
    out = subprocess.run([sys.executable, "-c", parent], capture_output=True, text=True, check=True)
    assert out.stdout.strip() == "gone", out.stderr


# ---------------------------------------------------------------------- the child


@pytest.fixture
def child(monkeypatch, tmp_path):
    """convert_one with pdfinfo and the pipeline replaced."""
    state = SimpleNamespace(info=poppler.PdfInfo(10, False, {}), result=None, raises=None)

    def pdfinfo(binary, pdf, timeout):
        if state.raises:
            raise state.raises
        return state.info

    monkeypatch.setattr(poppler, "pdfinfo", pdfinfo)
    monkeypatch.setattr(
        "quire_convert.tools.find_tools", lambda: SimpleNamespace(pdfinfo=Path("/bin/pdfinfo"))
    )
    monkeypatch.setattr(pipeline, "convert", lambda pdf, job_root: state.result)
    out = tmp_path / "out"
    out.mkdir()
    state.run = lambda: worker.convert_one(tmp_path / "in.pdf", out, 20)
    state.out = out
    return state


def report(outcome, **kw):
    fields = dict(
        reason="why",
        failure=None,
        issues=[],
        issue_counts={},
        affected_pages=[],
        input_sha256="0" * 64,
        settings={"variant": "rules"},
    )
    return SimpleNamespace(**(fields | kw))


@pytest.mark.parametrize(
    ("raises", "info", "code"),
    [
        (poppler.PasswordRequired(), None, "encrypted"),
        (None, poppler.PdfInfo(10, True, {}), "encrypted"),
        (None, poppler.PdfInfo(21, False, {}), "too_many_pages"),
        (worker.StageError("preflight", "pdfinfo failed: x"), None, "unreadable_pdf"),
    ],
)
def test_the_child_refuses_before_converting(child, raises, info, code):
    child.raises, child.info = raises, info
    child.result = "never used"
    assert child.run()["code"] == code


def test_the_child_maps_unsupported_and_failed_outcomes(child):
    child.result = SimpleNamespace(
        outcome=Outcome.UNSUPPORTED, report=report(Outcome.UNSUPPORTED, reason="scanned book")
    )
    assert (child.run()["code"], child.run()["detail"]) == ("unsupported", "scanned book")
    failure = {"stage": "calibre", "reason": "exited with 1; see /x/calibre.log", "retryable": 1}
    child.result = SimpleNamespace(outcome=Outcome.FAILED, report=report(None, failure=failure))
    result = child.run()
    # The stage only: a tool's message can name internal paths.
    assert (result["code"], result["detail"], result["retryable"]) == (
        "conversion_failed",
        "failed at calibre",
        True,
    )


def test_the_child_copies_a_review_epub_with_its_blocking_issues(child, tmp_path):
    epub = tmp_path / "attempt.epub"
    epub.write_bytes(b"the book")
    issues = [
        Issue("figure.lost", Severity.MAJOR, "figure not found", page=3, page_label="iii"),
        Issue("meta.filled", Severity.INFO, "title from the title page"),
    ]
    child.result = SimpleNamespace(
        outcome=Outcome.REVIEW,
        epub_path=epub,
        report=report(
            Outcome.REVIEW,
            issues=issues,
            affected_pages=[{"page": 3, "label": "iii"}],
            issue_counts={"major": 1},
        ),
    )
    result = child.run()
    assert result["outcome"] == "review"
    assert result["epub"]["sha256"] == hashlib.sha256(b"the book").hexdigest()
    assert result["epub"]["rendition_id"] == pipeline.rendition_identifier(
        "0" * 64, {"variant": "rules"}
    )
    assert result["issues"] == [
        {
            "code": "figure.lost",
            "severity": "major",
            "page": 4,
            "page_label": "iii",
            "message": "figure not found",
        }
    ]
    # Counted from 1 for the server, from 0 in the pipeline.
    assert result["affected_pages"] == [{"page": 4, "label": "iii"}]
    assert (child.out / "book.epub").read_bytes() == b"the book"


# ---------------------------------------------------------------------- end to end


def first_identifier(epub: Path) -> str:
    with zipfile.ZipFile(epub) as zf:
        container = etree.fromstring(zf.read("META-INF/container.xml"))
        opf_path = container.find(".//{*}rootfile").get("full-path")
        opf = etree.fromstring(zf.read(opf_path))
    ids = [el.text.strip() for el in opf.iter("{http://purl.org/dc/elements/1.1/}identifier")]
    return next(i for i in ids if i)


@pytest.mark.tools
def test_end_to_end_the_same_pdf_gives_the_same_book(tmp_path, fixture_pdfs):
    from quire_convert.tools import find_tools

    tools = find_tools()
    if any(
        getattr(tools, n) is None for n in ("ebook_convert", "java", "epubcheck_jar", "pdfinfo")
    ):
        pytest.skip("needs calibre, poppler, java and EPUBCheck")
    root = make_root(tmp_path)
    data = fixture_pdfs["bridge"][0].read_bytes()
    w = worker.Worker(root, engine=worker.engine_revision(tools.versions()))
    results = []
    for attempt in (1, 2):
        name = queue_job(root, attempt=attempt, data=data, time_limit_s=600)
        assert w.run(once=True) == 0
        result = result_of(root, name)
        assert result["outcome"] in ("ready", "review"), result
        epub = root / "done" / name / "book.epub"
        assert hashlib.sha256(epub.read_bytes()).hexdigest() == result["epub"]["sha256"]
        assert first_identifier(epub) == result["epub"]["rendition_id"]
        results.append(result)
    # Quire keys a book on its first identifier and its bytes: both repeat.
    assert results[0]["epub"] == results[1]["epub"]
    assert results[0]["epub"]["rendition_id"].startswith("urn:uuid:")


# ---------------------------------------------------------------------- the server's contract

# What the worker publishes for each outcome, read by the server's tests
# (server/tests/unit/test_convert_folder.py), so the two sides cannot drift.
# QUIRE_UPDATE_FIXTURES=1 rewrites it from the code.
WORKER_RESULTS = Path(__file__).parent / "fixtures" / "worker-results.json"


def published_by_the_worker(monkeypatch, tmp_path, outcome: Outcome) -> dict:
    """result.json as the real worker writes it, with only Calibre and pdfinfo replaced."""
    from quire_convert.report import Report, affected_pages, count_issues

    issues = {
        Outcome.READY: [Issue("meta.filled", Severity.INFO, "title from the title page")],
        Outcome.REVIEW: [
            # The first physical page, the one a scanned cover lands on.
            Issue("figure.facsimile_page", Severity.MAJOR, "kept as an image", page=0),
            Issue("figure.lost", Severity.MAJOR, "figure not found", page=4, page_label="iii"),
            Issue("meta.missing_title", Severity.MAJOR, "no source names the title"),
            Issue("nav.short", Severity.MINOR, "short chapter", page=6),
        ],
        Outcome.UNSUPPORTED: [],
    }[outcome]
    tmp_path.mkdir()
    epub = tmp_path / "attempt.epub"
    epub.write_bytes(b"the book")
    result = SimpleNamespace(
        outcome=outcome,
        epub_path=epub,
        report=Report(
            input_sha256="0" * 64,
            outcome=outcome,
            reason={"ready": "all checks passed", "review": "3 issues need review"}.get(
                outcome.value, "the PDF is a scanned book"
            ),
            settings={"variant": "rules"},
            issues=issues,
            issue_counts=count_issues(issues),
            affected_pages=affected_pages(issues, None),
        ),
    )
    monkeypatch.setattr(
        poppler, "pdfinfo", lambda binary, pdf, timeout: poppler.PdfInfo(9, False, {})
    )
    monkeypatch.setattr(
        "quire_convert.tools.find_tools", lambda: SimpleNamespace(pdfinfo=Path("/bin/pdfinfo"))
    )
    monkeypatch.setattr(pipeline, "convert", lambda pdf, job_root: result)

    def runner(claim, withdrawn, tick):
        argv = [str(claim.source), str(claim.out), str(claim.job["max_pages"])]
        return None if worker._child_main(argv) == 0 else "worker_error"

    root = make_root(tmp_path)
    name = queue_job(root)
    worker.Worker(root, engine="quire-convert 0.1.0; calibre 9.19.0", runner=runner).run(once=True)
    published = result_of(root, name)
    return published | {"seconds": 0.0}


def test_the_published_results_match_the_servers_fixture(monkeypatch, tmp_path):
    produced = {}
    for outcome in (Outcome.READY, Outcome.REVIEW, Outcome.UNSUPPORTED):
        with monkeypatch.context() as patch:
            produced[outcome.value] = published_by_the_worker(
                patch, tmp_path / outcome.value, outcome
            )
    produced["failed"] = produced.pop("unsupported")
    assert produced["review"]["affected_pages"] == [
        {"page": 1, "label": None},
        {"page": 5, "label": "iii"},
    ]
    if os.environ.get("QUIRE_UPDATE_FIXTURES"):
        WORKER_RESULTS.write_text(json.dumps(produced, indent=2, sort_keys=True) + "\n")
    assert json.loads(WORKER_RESULTS.read_text()) == produced, (
        "the worker's result.json changed: run with QUIRE_UPDATE_FIXTURES=1 and check that "
        "the server's tests still accept it"
    )
