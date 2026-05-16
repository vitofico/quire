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
