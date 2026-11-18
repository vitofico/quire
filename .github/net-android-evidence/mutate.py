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

mutants = [
    Mutant("A5-app-M3-retain-book-files", "app", "ui.settings.SettingsViewModelTest",
        "remove all books clears the library and its files without touching siblings",
        "app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt",
        "            documentRepo.deleteAll(booksDir)", '            documentRepo.deleteAll(java.io.File(booksDir, "nonexistent"))'),
    Mutant("A5-library-delete-no-op", "app", "ui.library.LibraryViewModelTest",
        "delete removes only the selected book and its files",
        "app/src/main/java/io/theficos/ereader/ui/library/LibraryViewModel.kt",
        "viewModelScope.launch { docs.delete(document) }", "viewModelScope.launch { Unit }"),
    Mutant("A5-app-M2-ignore-restart-delete-flag", "app", "ui.library.LibraryViewModelTest",
        "restart on success without delete writes reset progress and keeps file",
        "app/src/main/java/io/theficos/ereader/ui/library/LibraryViewModel.kt",
        "if (alsoDeleteFile) docs.delete(document)", "docs.delete(document)"),
    Mutant("A5-libs-M4-delete-bystander", "data/local", "data.local.DocumentRepositoryTest",
        "delete removes the book's cover file along with its EPUB",
        "data/local/src/main/java/io/theficos/ereader/data/local/db/DocumentDao.kt",
        "DELETE FROM documents WHERE id = :id", "DELETE FROM documents WHERE id = :id OR 1 = 1"),
    Mutant("A5-app-M4-skip-recheck-after-download", "app", "di.AppContainerRestoreTest",
        "restore discards a download already present under its extracted identity",
        "app/src/main/java/io/theficos/ereader/di/AppContainer.kt",
        "if (documentRepository.findByIdentity(identity) != null) {", "if (false && documentRepository.findByIdentity(identity) != null) {"),
    Mutant("A6-missing-builder-migration", "data/local", "data.local.db.MigrationTest",
        "production database builder upgrades version two with reading data intact",
        "data/local/src/main/java/io/theficos/ereader/data/local/db/EReaderDatabase.kt",
        "                    MIGRATION_8_9,\n", ""),
    Mutant("A7-progress-finished-field-drift", "data/sync", "data.sync.ProgressWireContractTest",
        "client posts terminal states and reads the shared server progress fixture",
        "data/sync/src/main/java/io/theficos/ereader/data/sync/ProgressDtos.kt",
        '@SerialName("finished_at")', '@SerialName("completed_at")'),
    Mutant("A9-highlight-failure-ignored", "data/sync", "data.sync.SyncWorkerOutcomeTest",
        "worker maps progress and highlight outcomes to work results",
        "data/sync/src/main/java/io/theficos/ereader/data/sync/SyncOrchestrator.kt",
        "        highlights?.runOnce() ?: progress", "        highlights?.runOnce()\n        progress"),
    Mutant("A9-cache-ignores-prompt-version", "data/ai", "data.ai.AiRepositoryCacheKeyTest",
        "local cache requires each model prompt and style key to match",
        "data/local/src/main/java/io/theficos/ereader/data/local/db/InsightDao.kt",
        '"AND promptVersion = :promptVersion " +', '"AND :promptVersion = :promptVersion " +'),
    Mutant("A9-libs-M7-ignore-profile-override", "data/ai", "data.ai.AiClientTest",
        "profile refresh uses its advertised budget then falls back to generation",
        "data/ai/src/main/java/io/theficos/ereader/data/ai/AiClient.kt",
        "get() = profileTimeoutS ?: generationTimeoutS", "get() = generationTimeoutS"),
    Mutant("Q3-debounce-sends-extra-requests", "app", "data.ai.InsightSyncRepositoryTest",
        "requestSync coalesces multiple calls in the debounce window",
        "data/ai/src/main/java/io/theficos/ereader/data/ai/InsightSyncRepository.kt",
        "if (existing != null && existing.isActive) return", "if (existing?.isCancelled == true) return"),
    Mutant("Q3-debounce-sends-no-request", "app", "data.ai.InsightSyncRepositoryTest",
        "requestSync coalesces multiple calls in the debounce window",
        "data/ai/src/main/java/io/theficos/ereader/data/ai/InsightSyncRepository.kt",
        "if (existing != null && existing.isActive) return", "if (existing == null || existing.isActive) return"),
]

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
    task = "testDebugUnitTest"
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
