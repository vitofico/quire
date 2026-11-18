"""One-time PR evidence for isolated, synthetic Quire regression tests."""

from collections import Counter
from dataclasses import dataclass
from pathlib import Path
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / "server"
PURGE = "tests/integration/test_browser_identity.py::test_export_close_purge_isolation_and_shared_cache"
PURGE_SCHEMA = "tests/integration/test_browser_identity.py::test_purge_accounts_for_every_account_keyed_table"
BACKUP = "tests/integration/test_pg_backup.py"
READYZ = (
    "tests/integration/test_readyz_migration_state.py::test_readyz_503_when_db_below_backbone",
    "tests/integration/test_health.py::test_readyz_returns_200_when_db_reachable_and_heads_applied",
)
A4_DOCS = (
    "tests/unit/test_env_docs_drift.py::"
    "test_every_documented_variable_is_read_by_the_server[docs/configuration.md]"
)


@dataclass(frozen=True)
class Mutation:
    label: str
    path: str
    original: str
    replacement: str
    selectors: tuple[str, ...]
    expected: str
    cwd: Path = SERVER
    exact_failed_test: str | None = None
    failure_kind: str = "assert"


MUTATIONS = [
    Mutation(
        "A1-P3-profile-retention",
        "server/quire_server/api/account_lifecycle.py",
        '    "reader_profiles": "subject",\n}',
        '}\nEXPORT_ONLY = {"reader_profiles": "subject"}',
        (PURGE,),
        "assert not any((await private_rows(session, account)).values())",
    ),
    Mutation(
        "A1-P3-schema-map",
        "server/quire_server/api/account_lifecycle.py",
        '    "reader_profiles": "subject",\n}',
        '}\nEXPORT_ONLY = {"reader_profiles": "subject"}',
        (PURGE_SCHEMA,),
        "assert PRIVATE == PRIVATE_ROWS",
    ),
    Mutation(
        "A1-P7-revoked-session-retention",
        "server/quire_server/api/account_lifecycle.py",
        '        await session.execute(text("DELETE FROM native_sessions WHERE user_id=:id"), {"id": number})\n        native.email',
        '        native.email',
        (PURGE,),
        'assert await session.scalar(sessions, {"id": number}) == 0',
    ),
    Mutation(
        "A1-P4-attribution-retention",
        "server/quire_server/api/account_lifecycle.py",
        "UPDATE book_insights SET generated_by='deleted' WHERE generated_by=:id",
        "SELECT 1 WHERE :id IS NOT NULL",
        (PURGE,),
        'assert await session.scalar(attribution) == "deleted"',
    ),
    Mutation(
        "A1-finalize-no-op",
        "server/quire_server/api/account_lifecycle.py",
        "            await session.delete(native)\n            await session.commit()",
        "            await session.commit()",
        (PURGE,),
        "assert await session.get(NativeUser, number, populate_existing=True) is None",
    ),
    Mutation(
        "A1b-expired-transaction-retention",
        "server/scripts/pg_backup.py",
        "DELETE FROM oidc_transactions;",
        "DELETE FROM oidc_transactions WHERE FALSE;",
        (f"{BACKUP}::test_backup_restores_documents_but_discards_auth_state",),
        "AssertionError: oidc_transactions",
    ),
    Mutation(
        "A3-title-limit",
        "server/quire_server/api/ai_schemas.py",
        "title: str = Field(min_length=1, max_length=512)",
        "title: str = Field(min_length=1, max_length=100)",
        ("tests/unit/test_ai_schemas.py::test_metadata_bundle_bounds[title]",),
        "String should have at most 100 characters",
        failure_kind="ValidationError",
    ),
    Mutation(
        "A3-clock-skew-boundary",
        "server/quire_server/api/ai_auth.py",
        "if iat > now + _IAT_CLOCK_SKEW_S:",
        "if iat >= now + _IAT_CLOCK_SKEW_S:",
        ("tests/unit/test_ai_auth.py::test_token_time_window[future-skew-at-limit]",),
        "await auth.authenticate(request)",
        failure_kind="HTTPException",
    ),
    Mutation(
        "A3-lifetime-boundary",
        "server/quire_server/api/ai_auth.py",
        "if exp - iat > _MAX_TOKEN_LIFETIME_S:",
        "if exp - iat >= _MAX_TOKEN_LIFETIME_S:",
        ("tests/unit/test_ai_auth.py::test_token_time_window[lifetime-at-limit]",),
        "await auth.authenticate(request)",
        failure_kind="HTTPException",
    ),
    *(
        Mutation(
            f"A4-doc-{name.lower().split('_')[1]}",
            "docs/configuration.md",
            f"#### `{name}`",
            f"#### `{typo}`",
            (A4_DOCS,),
            f"mentions variables the server does not read: ['{typo}']",
        )
        for name, typo in [
            ("QUIRE_IDENTITY_OIDC_ISSUER", "QUIRE_IDENTITY_OIDC_ISUER"),
            ("QUIRE_POLICY_TOKEN", "QUIRE_POLICY_TKOEN"),
            ("QUIRE_SERVER_AI_TIMEOUT_S", "QUIRE_SERVER_AI_TIMOUT_S"),
        ]
    ),
    Mutation(
        "A4-unversioned-prompt",
        "server/quire_server/core/ai/prompts.py",
        "",
        '\nSYSTEM_PROMPT += "\\nSynthetic fixture change."\n',
        ("tests/unit/test_ai_prompts.py::test_prompt_content_matches_cache_version",),
        "Bump PROMPT_VERSION",
    ),
    Mutation(
        "A7-progress-wire",
        "server/quire_server/api/progress.py",
        "identity_hash_version=d.identity_hash_version,",
        "identity_hash_version=1,",
        ("tests/integration/test_progress.py::test_post_progress_creates_document_and_progress",),
        "identity_hash_version",
    ),
    Mutation(
        "A8-version-monotonicity",
        "scripts/android-release.py",
        "    code = max(previous_code + 1, day_code)",
        "    code = day_code if previous_code == day_code else max(previous_code + 1, day_code)",
        ("scripts/tests/test_android_release.py::AndroidReleaseTest::test_next_version_code_is_monotonic",),
        "260108000 != 260108001",
        cwd=ROOT,
    ),
    Mutation(
        "A9-hosted-sync-denies-valid-write",
        "server/quire_server/main.py",
        "                        and not await hosted_policy.allow_sync(user.user_id)\n",
        "",
        ("tests/integration/test_cloud_contract.py::test_hosted_policy_fences_sync_and_library_writes[allowed]",),
        "assert response.status_code == (200 if sync_writes else 403)",
    ),
    Mutation(
        "A9-monthly-policy-denies-valid-lookup",
        "server/quire_server/core/ai/monthly_policy.py",
        "        if limit == 0:",
        "        if limit <= 1:",
        ("tests/integration/test_cloud_contract.py::test_monthly_policy_refuses_second_lookup_without_new_insight_or_usage",),
        "assert first.status_code == 200",
    ),
    Mutation(
        "A9-affinity-hides-own-book",
        "server/quire_server/api/library.py",
        "        owned=owned,",
        "        owned=None,",
        ("tests/integration/test_affinity_endpoint.py::test_affinity_owned_detection",),
        'assert body["owned"] is not None',
    ),
    Mutation(
        "A9-service-identity-denies-valid-call",
        "server/quire_server/api/account_lifecycle.py",
        "async def identity(account_id: str, request: Request):\n    service(request)",
        'async def identity(account_id: str, request: Request):\n    service(request)\n    raise HTTPException(403, "forbidden")',
        (PURGE,),
        "assert identity_response.json() ==",
    ),
    Mutation(
        "A9-release-repair-refuses-valid-tag",
        "scripts/publish-release.py",
        '    if release["tag_name"] != tag or release.get("draft") or release.get("prerelease"):',
        '    if release["tag_name"] != tag or "draft" in release or release.get("prerelease"):',
        ("scripts/tests/test_publish_release.py::PublishReleaseTest::test_repair_requires_the_requested_stable_release",),
        "Existing release must be a published stable release",
        cwd=ROOT,
        failure_kind="ValueError",
    ),
    Mutation(
        "Q2-readyz-restores-stamps",
        "server/tests/integration/test_readyz_migration_state.py",
        "        await _stamp(postgres_url, revisions)",
        '        await _stamp(postgres_url, ("0004", "auth_003"))',
        READYZ,
        "assert 503 == 200",
        exact_failed_test=READYZ[-1],
    ),
    Mutation(
        "Q3-pagination-inclusive-cursor",
        "server/quire_server/api/highlights.py",
        ".where(Highlight.user_id == user_id, Highlight.revision > cursor)",
        ".where(Highlight.user_id == user_id, Highlight.revision >= cursor)",
        ("tests/integration/test_highlights.py::test_delta_pages_offline_timestamps_mutations_and_download_removal",),
        "Three remaining revisions did not exhaust the feed",
        failure_kind="Failed",
    ),
    Mutation(
        "Q3-token-bucket-refills-too-slowly",
        "server/quire_server/core/ai/service.py",
        "self._tokens = min(self._capacity, self._tokens + (now - self._last) * self._refill_per_s)",
        "self._tokens = min(self._capacity, self._tokens + (now - self._last) * self._refill_per_s / 2)",
        ("tests/unit/test_ai_service.py::test_token_bucket_smooths_bursts",),
        "await asyncio.wait_for(waiting, timeout=2)",
        failure_kind="TimeoutError",
    ),
]

BLOCKED = [
    "A1 P1/P2: exact variants erase non-target rows; retained historical results only.",
    "A1 P5/P6: exact variants remove closure/purge preconditions; positive refusal tests and a post-purge no-op variant run.",
    "A2 D1/D2: exact variants remove tenant or subject scoping; the ordinary synthetic fixture verifies isolation.",
    "A3 M1/M2: exact variants loosen signed-token limits; restrictive boundary mutations run instead.",
]

SMOKE = [
    ("A1b-refuses-nonempty-target", (f"{BACKUP}::test_restore_refuses_nonempty_database",), SERVER, ()),
    (
        "A2-tenant-profile-positive",
        ("tests/integration/test_ai_auth_modes.py::test_token_auth_scopes_generation_log_and_profile_deletion",),
        SERVER,
        (),
    ),
    ("Q2-progress-reversed", ("tests/integration/test_progress.py",), SERVER, ("-p", "reverse_progress_order")),
]


def execute(out, label, selectors, cwd, extra=()):
    xml = out / f"{label}.xml"
    log = out / f"{label}.log"
    env = dict(os.environ, PYTHONPATH=os.pathsep.join((str(out), str(cwd))))
    command = [sys.executable, "-m", "pytest", "-q", f"--junitxml={xml}", *extra, *selectors]
    try:
        with log.open("wb") as stream:
            completed = subprocess.run(
                command, cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT, timeout=150
            )
    except subprocess.TimeoutExpired:
        raise RuntimeError(f"pytest exceeded 150 seconds (see {log.name})") from None
    if not xml.is_file():
        raise RuntimeError(f"pytest exited {completed.returncode} without XML (see {log.name})")
    root = ET.parse(xml).getroot()
    suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
    counts = Counter()
    for suite in suites:
        counts.update({name: int(suite.attrib.get(name, 0)) for name in ("tests", "failures", "errors", "skipped")})
    print(f"{label}: exit={completed.returncode} {dict(counts)}", flush=True)
    return completed.returncode, counts, root


def require_green(result):
    code, counts, _ = result
    if code or not counts["tests"] or any(counts[key] for key in ("errors", "failures", "skipped")):
        raise RuntimeError("control did not pass every selected test")


def require_killed(result, mutation):
    code, counts, root = result
    failures = [
        (case.attrib["name"], failure.attrib.get("message", "").strip(), failure.text or "")
        for case in root.iter("testcase")
        for failure in case.findall("failure")
    ]
    expected_name = (mutation.exact_failed_test or mutation.selectors[0]).rsplit("::", 1)[-1]
    expected_failure = False
    for name, message, trace in failures:
        first_line = message.partition("\n")[0]
        if mutation.failure_kind == "assert":
            expected_kind = first_line.startswith(("assert ", "AssertionError"))
        else:
            expected_kind = mutation.failure_kind in first_line
        if name.startswith(expected_name) and expected_kind and mutation.expected in message + trace:
            expected_failure = True
    if (
        code != 1
        or not counts["failures"]
        or counts["errors"]
        or counts["skipped"]
        or not all(name.startswith(expected_name) for name, _, _ in failures)
        or not expected_failure
    ):
        raise RuntimeError(f"did not observe the named behavior failure: {mutation.expected}")


def run(out):
    out.mkdir(parents=True, exist_ok=True)
    (out / "reverse_progress_order.py").write_text(
        "import pytest\n\n"
        "@pytest.hookimpl(trylast=True)\n"
        "def pytest_collection_modifyitems(items):\n"
        "    items.reverse()\n"
        "    assert items[0].nodeid != 'tests/integration/test_progress.py::test_post_progress_creates_document_and_progress'\n"
    )
    unexpected = Counter()
    summary = [f"BLOCKED {reason}" for reason in BLOCKED]

    for label, selectors, cwd, extra in SMOKE:
        try:
            require_green(execute(out, label, selectors, cwd, extra))
            summary.append(f"PASS {label}")
        except (OSError, ValueError, ET.ParseError, RuntimeError) as exc:
            summary.append(f"FAIL {label}: {exc}")
            unexpected[label.split("-")[0]] += 1

    for mutation in MUTATIONS:
        packet = mutation.label.split("-")[0]
        if unexpected[packet] >= 2:
            summary.append(f"SKIP {mutation.label}: two unexpected failures in {packet}")
            continue
        try:
            require_green(execute(out, f"{mutation.label}-control", mutation.selectors, mutation.cwd))
            path = ROOT / mutation.path
            original = path.read_bytes()
            source = original.decode()
            if mutation.original:
                if source.count(mutation.original) != 1:
                    raise RuntimeError(f"mutation anchor not unique in {mutation.path}")
                updated = source.replace(mutation.original, mutation.replacement, 1)
            else:
                updated = source + mutation.replacement
            try:
                path.write_text(updated)
                result = execute(out, f"{mutation.label}-mutant", mutation.selectors, mutation.cwd)
            finally:
                path.write_bytes(original)
            require_killed(result, mutation)
            summary.append(f"KILLED {mutation.label}")
        except (OSError, ValueError, ET.ParseError, RuntimeError) as exc:
            summary.append(f"FAIL {mutation.label}: {exc}")
            unexpected[packet] += 1

    output = "\n".join(summary) + "\n"
    (out / "status.txt").write_text(output)
    print(output, end="")
    if unexpected:
        raise SystemExit(1)


if __name__ == "__main__":
    if os.environ.get("GITHUB_HEAD_REF") != "test/lean-safety-net":
        raise SystemExit("This one-time evidence run is restricted to the Quire test branch.")
    run(Path(sys.argv[1]))
