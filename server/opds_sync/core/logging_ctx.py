"""Request-scoped logging context for opds-sync.

The request_id ContextVar is set by RequestIDMiddleware on every inbound HTTP
request and read by RequestIdLogFilter to inject the ID into structured logs.
Stays empty string outside of an HTTP request (e.g. startup, shutdown).
"""

from __future__ import annotations

import logging
from contextvars import ContextVar

# Empty string default makes it safe to read outside a request without raising.
request_id_var: ContextVar[str] = ContextVar("request_id", default="")


class RequestIdLogFilter(logging.Filter):
    """Inject the current request_id into every log record.

    IMPORTANT: attach to HANDLERS, not to loggers. Logger-level filters do
    NOT apply to records propagated up from child loggers — only to records
    logged directly to that logger. The production wiring lives in
    `main.py::create_app()`; mirror it in tests by adding the filter to
    `caplog.handler` or to your own handler instance.
    """

    def filter(self, record: logging.LogRecord) -> bool:
        record.request_id = request_id_var.get()
        return True
