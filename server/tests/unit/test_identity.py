import json
from pathlib import Path

import pytest

from opds_sync.core.identity import normalize_metadata_id

FIXTURES = Path(__file__).resolve().parents[2] / "fixtures" / "identity" / "fixtures.json"


@pytest.fixture(scope="module")
def cases() -> list[dict]:
    with FIXTURES.open() as f:
        return json.load(f)["cases"]


def test_parity_with_kotlin_fixtures(cases: list[dict]) -> None:
    for case in cases:
        got = normalize_metadata_id(case["in"])
        expected = case["out"]
        assert got == expected, f"input={case['in']!r} expected={expected!r} got={got!r}"


def test_none_returns_none() -> None:
    assert normalize_metadata_id(None) is None
