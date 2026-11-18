"""Collect identical fresh Gradle unit-test runs on two hosted source trees."""

import argparse
from collections import Counter
from pathlib import Path
import subprocess
import time
from xml.etree import ElementTree as ET

FORCE_TEST = Path(__file__).with_name("force-test.gradle").resolve()
GRADLE = ["./gradlew", "--max-workers=3", "--console=plain", "--no-build-cache", "test", "--stacktrace"]


def reports(repo):
    for module in ("app", "auth", "reader", "core/*", "data/*"):
        yield from sorted(repo.glob(module + "/build/test-results/*/TEST-*.xml"))


def run_gradle(repo, out, label, force=False):
    command = GRADLE + (["--init-script", str(FORCE_TEST)] if force else [])
    with (out / f"{label}.log").open("w") as log:
        start = time.monotonic()
        result = subprocess.run(command, cwd=repo, stdout=log, stderr=subprocess.STDOUT, timeout=780)
        elapsed = time.monotonic() - start
    return result.returncode, elapsed


def collect(repo, output):
    cases = Counter()
    skipped = Counter()
    failures = []
    failed_reports = []
    for report in reports(repo):
        relative = report.relative_to(repo)
        suite = ET.parse(report).getroot()
        cases_in_suite = suite.findall("testcase")
        assert int(suite.attrib["tests"]) == len(cases_in_suite), relative
        dest = output / relative
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(report.read_bytes())
        for test in cases_in_suite:
            case = str(relative.parent), test.attrib["classname"], test.attrib["name"]
            cases[case] += 1
            if test.find("skipped") is not None:
                skipped[case] += 1
            if test.find("failure") is not None or test.find("error") is not None:
                failures.append(case)
        if int(suite.attrib["errors"]) or int(suite.attrib["failures"]):
            failed_reports.append(str(relative))
    assert not failures and not failed_reports, (failures, failed_reports)
    assert cases, "No fresh Gradle JUnit reports returned"
    assert all(count == 1 for count in cases.values()), cases
    assert {prefix.split("/")[0] for prefix, _, _ in cases} >= {"app", "auth", "core", "data", "reader"}
    return cases, skipped


def full_gate(repo, output, *, warmup):
    output.mkdir(parents=True, exist_ok=True)
    expected = None
    if warmup:
        status, _ = run_gradle(repo, output, "warmup")
        if status:
            collect(repo, output / "warmup")
            raise SystemExit(f"Warmup failed: {repo}")
        expected = {report.relative_to(repo) for report in reports(repo)}
    for report in reports(repo):
        report.unlink()
    status, elapsed = run_gradle(repo, output, "tests", force=True)
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=repo, text=True).strip()
    message = f"{output.name}\t{revision}\t{elapsed:.3f}s"
    (output / "timing.tsv").write_text(message + "\n")
    cases, skipped = collect(repo, output / "xml")
    if expected is not None:
        assert {report.relative_to(repo) for report in reports(repo)} == expected
    assert status == 0, repo
    print(f"{message}\t{len(cases)} cases\t{len(skipped)} skipped", flush=True)
    return cases, skipped


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("output", type=Path)
    parser.add_argument("--baseline", type=Path)
    args = parser.parse_args()
    current = Path.cwd()
    output = args.output.resolve()
    if args.baseline:
        before, before_skips = full_gate(args.baseline.resolve(), output / "baseline", warmup=True)
        after, after_skips = full_gate(current, output / "slim", warmup=True)
        assert not (after_skips - before_skips), (after_skips - before_skips)
        assert len(after) < len(before), (len(before), len(after))
    else:
        _, skipped = full_gate(current, output / "post-restore", warmup=False)
        assert not skipped, skipped


if __name__ == "__main__":
    main()
