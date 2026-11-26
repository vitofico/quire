"""The in-process limits of /details/v1: per-reader rate and the cover cache."""

from quire_server.api import details as api
from quire_server.core import rate_limit


def test_the_rate_limit_forgets_idle_readers(monkeypatch):
    now = [1000.0]
    monkeypatch.setattr(rate_limit.time, "monotonic", lambda: now[0])
    limit = rate_limit.RateLimit(max_readers=3)
    for reader in ("a", "b", "c"):
        assert limit.retry_after(reader, 1) is None
    assert limit.retry_after("a", 1) is not None  # one a minute
    now[0] += 61
    # A fourth reader finds the table full of idle ones and clears it.
    assert limit.retry_after("d", 1) is None
    assert len(limit) == 1
    now[0] += 1
    # Readers still inside their minute are kept.
    assert limit.retry_after("e", 1) is None
    assert limit.retry_after("f", 1) is None
    assert limit.retry_after("g", 1) is None
    assert len(limit) == 4


def test_the_cover_cache_keeps_the_most_recent():
    cache = api.CoverCache(size=2)
    cache.put("a", b"1")
    cache.put("b", b"2")
    assert cache.get("a") == b"1"  # now the most recent
    cache.put("c", b"3")
    assert cache.get("b") is None
    assert cache.get("a") == b"1" and cache.get("c") == b"3"
