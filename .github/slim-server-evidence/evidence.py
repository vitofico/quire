"""Temporary hosted replay. Execute from a disposable slim checkout."""

import argparse
import os
from pathlib import Path
import re
import subprocess
import textwrap
import time
from xml.etree import ElementTree as ET

from cases import CASES

PURE = "tests/unit/test_ai_prompts.py::test_prompt_content_matches_cache_version"
DATABASE = "tests/unit/test_ai_retrieval.py::test_lookup_wikipedia_hits_cache_after_first_call"
FIXTURE_PROBE = "tests/unit/test_slim_fixture_evidence.py"
ENV = os.environ | {"PYTHONDONTWRITEBYTECODE": "1"}


def run(cmd, cwd, out, label):
    out.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    with (out / f"{label}.log").open("w") as log:
        result = subprocess.run(cmd, cwd=cwd, env=ENV, stdout=log, stderr=subprocess.STDOUT, timeout=360)
    return result.returncode, time.monotonic() - start


def read_xml(path):
    root = ET.parse(path).getroot()
    cases = {}
    skipped = set()
    errors = failures = 0
    for suite in root.iter("testsuite"):
        errors += int(suite.attrib["errors"])
        failures += int(suite.attrib["failures"])
    for case in root.iter("testcase"):
        name = case.attrib["classname"], case.attrib["name"]
        assert name not in cases, name
        cases[name] = case
        if case.find("skipped") is not None:
            skipped.add(name)
    assert cases
    return cases, skipped, failures, errors


def pytest_run(repo, out, label, *selectors, fail_on=None):
    xml = out / f"{label}.xml"
    xml.unlink(missing_ok=True)
    status, seconds = run(
        ["./.venv/bin/python", "-m", "pytest", "-v", "--color=no", f"--junitxml={xml}", *selectors],
        repo / "server", out, label,
    )
    cases, skipped, failures, errors = read_xml(xml)
    assert errors == 0, (label, errors)
    if fail_on:
        path, method = fail_on.split("::", 1)
        expected = path.removesuffix(".py").replace("/", "."), method
        assert status == 1 and len(cases) == 1 and failures == 1 and not skipped, label
        case = cases[expected]
        assert case.find("failure") is not None and case.find("error") is None, expected
    else:
        assert status == failures == 0, label
    if selectors:
        assert not skipped, (label, skipped)
    print(f"{label}\t{seconds:.3f}s\t{len(cases)} cases\t{len(skipped)} skipped\t{failures} failures", flush=True)
    return cases, skipped, seconds


def setup_plan(repo, out, label, test):
    status, _ = run(
        ["./.venv/bin/python", "-m", "pytest", "--setup-plan", "--color=no", test],
        repo / "server", out, label,
    )
    assert status == 0, label
    plan = (out / f"{label}.log").read_text()
    assert test in plan, label
    return set(re.findall(r"\bSETUP\s+[A-Z]\s+([_\w]+)", plan))


def timed_suites(before, after, out, *, full):
    # Preload the shared package and Docker caches on the same runner before timing either tree.
    for label, repo in (("base", before), ("slim", after)):
        warm, _, _ = pytest_run(repo, out, f"{label}-warmup", "tests/integration/test_schema.py::test_schema_round_trip")
        assert len(warm) == 1
    timing = []
    results = []
    for label, repo in (("base", before), ("slim", after)):
        cases, skipped, seconds = pytest_run(repo, out, f"{label}-full")
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
        timing.append(f"{label}\t{revision}\t{seconds:.3f}\t{len(cases)}\t{len(skipped)}")
        results.append(skipped)
    (out / "timing.tsv").write_text("tree\tsha\tseconds\tcases\tskips\n" + "\n".join(timing) + "\n")
    added_skips = results[1] - results[0]
    assert not added_skips, ("new skipped testcase IDs", sorted(added_skips))
    if full:
        assert not results[0] and not results[1], ("unexpected full-mode skips", results)
        assert {"engine", "postgres_container"} <= setup_plan(before, out, "base-pure-setup", PURE)
        assert not ({"engine", "postgres_container"} & setup_plan(after, out, "slim-pure-setup", PURE))
        assert {"engine", "postgres_container", "session"} <= setup_plan(after, out, "slim-db-setup", DATABASE)
        for label, repo in (("base", before), ("slim", after)):
            pytest_run(repo, out, f"{label}-s1-pure", PURE)


def rename_checks(repo, out):
    # Exercise the actual maintained workflow guard and its original positive control.
    workflow = (repo / ".github/workflows/server-ci.yaml").read_text()

    def script(name):
        step = workflow.split(f"      - name: {name}\n", 1)[1].split("\n      - ", 1)[0]
        return textwrap.dedent(step.split("        run: |\n", 1)[1])

    def check(name, label, expected):
        status, _ = run(["bash", "-e", "-o", "pipefail", "-c", script(name)], repo, out, label)
        assert status == expected, (label, status)

    check("Rename guard self-test", "rename-regex-positive-control", 0)
    check("Rename guard", "rename-tree-control", 0)
    probe = repo / "server/tests/unit/slim_rename_guard_probe.py"
    assert not probe.exists()
    try:
        probe.write_text("SYNTHETIC = '" + "OPDS" + "_SYNC_FIXTURE'\n")
        check("Rename guard", "rename-tree-rejects-legacy-name", 1)
        assert probe.name in (out / "rename-tree-rejects-legacy-name.log").read_text()
    finally:
        probe.unlink()
    check("Rename guard", "rename-tree-restored", 0)


def replay(repo, out):
    for name, test, path, old, new in CASES:
        source = repo / path
        assert source.read_bytes().count(old.encode()) == 1, (name, path)
        tests, _, _ = pytest_run(repo, out, f"{name}-control", test)
        assert len(tests) == 1, (name, tests)
    for name, test, path, old, new in CASES:
        source = repo / path
        original = source.read_bytes()
        try:
            source.write_bytes(original.replace(old.encode(), new.encode()))
            pytest_run(repo, out, name, test, fail_on=test)
        finally:
            source.write_bytes(original)

    for name, test, _, _, _ in CASES:
        pytest_run(repo, out, f"{name}-restored", test)


def fixture_replay(repo, out):
    probe = repo / "server" / FIXTURE_PROBE
    fixture = repo / "server/tests/unit/conftest.py"
    original = fixture.read_bytes()
    assert not probe.exists()
    probe.write_text(textwrap.dedent('''
        from sqlalchemy import text

        from quire_server.db.models import ExternalSourceCacheEntry

        def test_pure_unit_case_has_no_database_fixtures(request):
            assert not ({"engine", "postgres_container"} & set(request.fixturenames))

        async def test_seed_cache_and_commit(session):
            session.add(ExternalSourceCacheEntry(source="s1-fixture", key="seed", payload={"check": True}))
            await session.commit()
            assert await session.scalar(text("SELECT count(*) FROM external_source_cache WHERE source='s1-fixture'")) == 1

        async def test_following_unit_session_starts_clean(session):
            assert await session.scalar(text("SELECT count(*) FROM external_source_cache WHERE source='s1-fixture'")) == 0
    ''').lstrip())
    try:
        pytest_run(repo, out, "s1-fixture-control", FIXTURE_PROBE)
        mutation = b'\n\n@pytest.fixture(autouse=True)\ndef load_database_for_each_unit_case(engine):\n    pass\n'
        fixture.write_bytes(original + mutation)
        pure = FIXTURE_PROBE + "::test_pure_unit_case_has_no_database_fixtures"
        pytest_run(repo, out, "s1-eager-engine-regression", pure, fail_on=pure)
        fixture.write_bytes(original)
        seed = FIXTURE_PROBE + "::test_seed_cache_and_commit"
        clean = FIXTURE_PROBE + "::test_following_unit_session_starts_clean"
        # The seed must run beside the failing assertion in the same pytest session.
        marker = b'"TRUNCATE TABLE ai_generation_log, external_source_cache, "'
        assert original.count(marker) == 1
        fixture.write_bytes(original.replace(marker, b'"TRUNCATE TABLE ai_generation_log, "'))
        status, _ = run(
            ["./.venv/bin/python", "-m", "pytest", "-v", "--color=no",
             f"--junitxml={out / 's1-cleanup-regression.xml'}", seed, clean],
            repo / "server", out, "s1-cleanup-regression",
        )
        cases, skipped, failures, errors = read_xml(out / "s1-cleanup-regression.xml")
        assert status == failures == 1 and errors == 0 and not skipped and len(cases) == 2
        for (_, name), case in cases.items():
            assert case.find("error") is None
            assert (case.find("failure") is not None) == (name == "test_following_unit_session_starts_clean")
        fixture.write_bytes(original)
        pytest_run(repo, out, "s1-fixture-restored", FIXTURE_PROBE)
    finally:
        fixture.write_bytes(original)
        probe.unlink()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out", type=Path)
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--mode", choices=("full", "sync_only", "ai_only"), default="full")
    args = parser.parse_args()
    repo = Path.cwd()
    out = args.out.resolve()
    if args.baseline:
        timed_suites(args.baseline.resolve(), repo, out, full=args.mode == "full")
    else:
        rename_checks(repo, out)
        replay(repo, out)
        fixture_replay(repo, out)
        _, skipped, _ = pytest_run(repo, out, "post-restore-full")
        assert not skipped, skipped


if __name__ == "__main__":
    main()
