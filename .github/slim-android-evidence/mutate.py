"""Run from an expendable Quire checkout containing the Android safety net."""
from dataclasses import dataclass
from pathlib import Path
import argparse
import os
import signal
import subprocess
import xml.etree.ElementTree as ET

prefix = "io.theficos.ereader."

@dataclass(frozen=True)
class Mutant:
    name: str
    module: str
    test_class: str
    method: str
    source: str
    old: str
    new: str

from cases import CASES

mutants = [Mutant(*case) for case in CASES]

def verify(m, label, suite, status, expect_failure):
    test_class = prefix + m.test_class
    cases = suite.findall("testcase")
    assert len(cases) == 1, (label, [case.attrib for case in cases])
    case = cases[0]
    assert (case.attrib["classname"], case.attrib["name"]) == (test_class, m.method), (label, case.attrib)
    assert int(suite.attrib["tests"]) == 1
    assert int(suite.attrib["errors"]) == int(suite.attrib["skipped"]) == 0
    assert case.find("error") is None and case.find("skipped") is None

    failure = case.find("failure")
    if expect_failure:
        assert status == 1 and int(suite.attrib["failures"]) == 1 and failure is not None, label
        if label.startswith("Q3-"):
            failure_type = failure.attrib.get("type", "")
            assert any(marker in failure_type for marker in ("AssertionError", "ComparisonFailure")), failure.attrib
            assert float(case.attrib["time"]) < 30, (label, case.attrib)
    else:
        assert status == int(suite.attrib["failures"]) == 0 and failure is None, label
    print(label, f"{test_class}.{m.method}", f"gradle={status}", f"test_seconds={case.attrib['time']}", flush=True)


def execute(m, label, out, expect_failure):
    test_class = prefix + m.test_class
    task = "test" if m.module == "core/model" else "testDebugUnitTest"
    gradle = ":" + m.module.replace("/", ":") + ":" + task
    report = Path(m.module) / "build/test-results" / task / f"TEST-{test_class}.xml"
    report.unlink(missing_ok=True)
    cmd = ["./gradlew", "--max-workers=3", gradle, "--tests", f"{test_class}.{m.method}", "--rerun"]
    with (out / f"{label}.log").open("w") as log:
        process = subprocess.Popen(cmd, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        try:
            status = process.wait(timeout=420)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait()
            raise RuntimeError(f"{label} hit its process timeout; no mutation result") from None
    xml = report.read_bytes()
    (out / f"{label}.xml").write_bytes(xml)
    verify(m, label, ET.fromstring(xml), status, expect_failure)


def controls_for(mutations):
    controls = {}
    for m in mutations:
        controls.setdefault((m.module, m.test_class, m.method), m)
    return controls.values()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("out", type=Path)
    parser.add_argument("names", nargs="*")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--only-control", action="store_true")
    mode.add_argument("--resume", action="store_true", help="Replay named unfinished mutants and all restored controls")
    options = parser.parse_args()
    out = options.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    selected = [m for m in mutants if not options.names or m.name in options.names]
    assert not set(options.names).difference(m.name for m in selected)
    if options.resume:
        assert options.names, "--resume needs explicit unfinished mutant names"
    else:
        for m in controls_for(selected):
            execute(m, f"control-{m.name}", out, False)
    if options.only_control:
        return
    for m in selected:
        path = Path(m.source)
        original = path.read_bytes()
        assert original.count(m.old.encode()) == 1, m
        try:
            path.write_bytes(original.replace(m.old.encode(), m.new.encode()))
            execute(m, m.name, out, True)
        finally:
            path.write_bytes(original)
    for m in controls_for(mutants if options.resume else selected):
        execute(m, f"restored-{m.name}", out, False)

if __name__ == "__main__":
    main()
