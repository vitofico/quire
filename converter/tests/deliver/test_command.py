import logging

import pytest
from deliver_support import make_job
from fake_calibre_web import FakeCalibreWeb

from quire_convert import cli
from quire_convert.models import USAGE_EXIT_CODE, Outcome

PASSWORD = "secret-pw"  # pragma: allowlist secret


@pytest.fixture
def server():
    with FakeCalibreWeb() as fake:
        yield fake


def run(job, server, *extra):
    argv = ["-v", "deliver", str(job.dir), "--url", server.url, "--user", "alice"]
    return cli.main([*argv, "--password-env", "QC_TEST_PW", "--wait", "0.5", *extra])


@pytest.fixture(autouse=True)
def password(monkeypatch):
    monkeypatch.setenv("QC_TEST_PW", PASSWORD)


def test_deliver_saves_and_keeps_secrets_out_of_output(job, server, capsys, caplog):
    caplog.set_level(logging.DEBUG)
    book = server.add_book("The Bridge", pdf=b"%PDF")
    code = run(job, server, "--to", "calibre-web", "--book-id", str(book))
    out, err = capsys.readouterr()
    assert code == 0
    assert out.startswith(f"saved: record {book} on {server.url}")
    assert (job.dir.parent / "deliveries.sqlite").exists()
    for text in (out, err, caplog.text):
        assert PASSWORD not in text and "The Bridge" not in text


def test_exit_codes_follow_the_state(job, server, tmp_path, capsys):
    other = make_job(tmp_path / "other", "urn:uuid:other-edition")
    book = server.add_book("The Bridge", pdf=b"%PDF", epub=other.artifact("epub").read_bytes())
    assert run(job, server, "--to", "calibre-web", "--book-id", str(book)) == 3
    assert capsys.readouterr().out.startswith("conflict:")
    server.users["alice"].roles = {"download"}
    fresh = server.add_book("Another", pdf=b"%PDF")
    assert run(job, server, "--to", "calibre-web", "--book-id", str(fresh)) == 1
    assert "no upload permission" in capsys.readouterr().out


def test_ingest_handoff_reports_submitted(job, server, tmp_path, capsys):
    folder = tmp_path / "ingest"
    folder.mkdir()
    receipts = tmp_path / "custom.sqlite"
    code = run(
        job, server, "--to", "cwa-ingest", "--folder", str(folder), "--receipts", str(receipts)
    )
    assert code == 2
    assert capsys.readouterr().out.startswith("submitted: new")
    assert receipts.exists() and len(list(folder.iterdir())) == 1


@pytest.mark.parametrize(
    "extra",
    [
        ["--to", "calibre-web"],  # no target named
        ["--to", "cwa-ingest", "--new"],  # no folder
        ["--to", "cwa-ingest", "--folder", "/tmp", "--book-id", "3"],  # ingest adds only
        ["--to", "cwa-ingest", "--folder", "/tmp", "--chown", "abc"],
        ["--to", "calibre-web", "--book-id", "3", "--new"],
    ],
)
def test_usage_errors(job, server, extra):
    with pytest.raises(SystemExit) as exc:
        run(job, server, *extra)
    assert exc.value.code == USAGE_EXIT_CODE


def test_missing_password_env_and_review_jobs_are_usage_errors(job, server, monkeypatch, tmp_path):
    monkeypatch.delenv("QC_TEST_PW")
    with pytest.raises(SystemExit) as exc:
        run(job, server, "--to", "calibre-web", "--book-id", "1")
    assert exc.value.code == USAGE_EXIT_CODE
    monkeypatch.setenv("QC_TEST_PW", PASSWORD)
    review = make_job(tmp_path / "review", "urn:uuid:review", outcome=Outcome.REVIEW)
    with pytest.raises(SystemExit) as exc:
        run(review, server, "--to", "calibre-web", "--book-id", "1")
    assert exc.value.code == USAGE_EXIT_CODE
    book = server.add_book("The Bridge", pdf=b"%PDF")
    assert run(review, server, "--to", "calibre-web", "--book-id", str(book), "--allow-review") == 0
