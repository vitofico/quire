"""Unit tests for opds_sync.core.logging_ctx."""

from __future__ import annotations

import logging
from contextvars import ContextVar

from opds_sync.core.logging_ctx import RequestIdLogFilter, request_id_var


def test_request_id_var_is_contextvar_with_empty_default():
    assert isinstance(request_id_var, ContextVar)
    # default="" means reading outside any explicit `set()` returns "".
    assert request_id_var.get() == ""


def test_filter_injects_request_id_into_record():
    rec = logging.LogRecord(
        name="test",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg="hi",
        args=(),
        exc_info=None,
    )
    token = request_id_var.set("abc123")
    try:
        f = RequestIdLogFilter()
        assert f.filter(rec) is True
        assert rec.request_id == "abc123"
    finally:
        request_id_var.reset(token)


def test_filter_uses_empty_string_when_unset():
    rec = logging.LogRecord(
        name="test",
        level=logging.INFO,
        pathname=__file__,
        lineno=1,
        msg="hi",
        args=(),
        exc_info=None,
    )
    # Fresh context: var is empty.
    f = RequestIdLogFilter()
    assert f.filter(rec) is True
    assert rec.request_id == ""


def test_filter_attached_to_root_handler_applies_to_child_logger_records():
    """Records emitted by a CHILD logger must end up with request_id when the
    filter is attached to the root logger's handler (not the root logger).

    Build a fresh BufferingHandler attached to the root logger — this mirrors
    main.py's production setup (RequestIdLogFilter on root handlers) exactly
    and avoids caplog's installation/teardown idiosyncrasies that can race
    with session-scoped fixtures.
    """
    from logging.handlers import BufferingHandler

    handler = BufferingHandler(capacity=100)
    handler.setLevel(logging.WARNING)
    filt = RequestIdLogFilter()
    handler.addFilter(filt)

    root = logging.getLogger()
    root.addHandler(handler)
    prev_level = root.level
    if root.level == 0 or root.level > logging.WARNING:
        root.setLevel(logging.WARNING)

    child = logging.getLogger("opds_sync.core.ai.service.test_propagation")
    prev_propagate = child.propagate
    child.propagate = True

    token = request_id_var.set("rid-propagation-test")
    try:
        child.warning("hello-from-child-rid-test")
    finally:
        request_id_var.reset(token)
        root.removeHandler(handler)
        root.setLevel(prev_level)
        child.propagate = prev_propagate

    matches = [r for r in handler.buffer if r.getMessage() == "hello-from-child-rid-test"]
    assert matches, (
        f"expected the child-logger record to reach the root handler; got "
        f"{[r.getMessage() for r in handler.buffer]}"
    )
    assert getattr(matches[0], "request_id", "") == "rid-propagation-test"
