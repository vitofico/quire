"""Functional changes for disposable CI only. Names identify this run's variations."""

PREFIX = "server/quire_server/"
TEST = {
    "schema": "tests/integration/test_schema.py",
    "config": "tests/integration/test_ai_config_timeout.py",
    "readyz": "tests/integration/test_readyz_migration_state.py",
    "prompt": "tests/unit/test_ai_prompts.py",
}

CASES = [
    (
        "request-log-loses-correlation",
        "tests/unit/test_logging_ctx.py::test_request_id_on_child_logs_and_reset_after_response",
        PREFIX + "core/logging_ctx.py",
        "record.request_id = request_id_var.get()",
        'record.request_id = ""',
    ),
    (
        "valid-request-id-replaced",
        "tests/unit/test_middleware_request_id.py::test_request_id_header_and_context[valid]",
        PREFIX + "api/middleware/request_id.py",
        "request_id = _sanitize(incoming) or uuid.uuid4().hex",
        "request_id = uuid.uuid4().hex",
    ),
    (
        "cached-insight-log-gets-wrong-status",
        "tests/integration/test_ai_audit_log.py::test_two_tenants_share_one_insight_two_log_rows",
        PREFIX + "core/ai/service.py",
        'status="hit",\n                latency_ms=0,\n            )\n            await session.commit()\n            return self._row_to_response(row)',
        'status="miss",\n                latency_ms=0,\n            )\n            await session.commit()\n            return self._row_to_response(row)',
    ),
    (
        "profile-config-rounds-down",
        TEST["config"] + "::test_issue_102_config_advertises_timeouts[profile-ceiling]",
        PREFIX + "api/ai.py",
        'math.ceil(runtime.get("ai_profile_timeout_s"))',
        'math.floor(runtime.get("ai_profile_timeout_s"))',
    ),
    (
        "generation-config-rounds-down",
        TEST["config"] + "::test_issue_102_config_advertises_timeouts[generation-ceiling]",
        PREFIX + "api/ai.py",
        'math.ceil(runtime.get("ai_timeout_s"))',
        'math.floor(runtime.get("ai_timeout_s"))',
    ),
    (
        "readyz-loses-applied-revisions",
        TEST["readyz"] + "::test_readyz_200_when_at_ai_head",
        PREFIX + "api/health.py",
        '"heads_applied": heads["applied"],',
        '"heads_applied": [],',
    ),
    (
        "insight-identity-version-default-drifts",
        TEST["schema"] + "::test_insight_language_unique_keys_and_audit_cascade",
        "server/migrations/versions/ai_007_identity_hash_version.py",
        'server_default=sa.text("1"),',
        'server_default=sa.text("2"),',
    ),
    (
        "live-series-index-points-at-deleted-items",
        TEST["schema"] + "::test_library_item_identity_and_live_series_index",
        "server/migrations/versions/progress_001_library_items.py",
        'postgresql_where=sa.text("deleted_at IS NULL"),',
        'postgresql_where=sa.text("deleted_at IS NOT NULL"),',
    ),
    (
        "prompt-wording-drifts-without-cache-version",
        TEST["prompt"] + "::test_prompt_content_matches_cache_version",
        PREFIX + "core/ai/prompts.py",
        "pick the TWO themes most central to this book",
        "pick the two strongest recurring themes in this book",
    ),
    (
        "stored-prompt-version-ignores-override",
        "tests/integration/test_main_prompt_version_runtime.py::test_lookup_persists_runtime_prompt_version[4]",
        PREFIX + "main.py",
        "prompt_version=_resolve_prompt_version(settings.ai_prompt_version),",
        "prompt_version=_resolve_prompt_version(None),",
    ),
    (
        "catalog-volume-omitted",
        "tests/unit/test_ai_retrieval.py::test_volume_number[Saga, Volume IX-9]",
        PREFIX + "core/ai/retrieval.py",
        "return _strip_markers(title)[1]",
        "return None",
    ),
    (
        "empty-log-level-default-drifts",
        "tests/unit/test_settings.py::test_log_level_any_case_and_empty_means_default[-INFO]",
        PREFIX + "config.py",
        'return value.strip().upper() or "INFO"',
        'return value.strip().upper() or "WARNING"',
    ),
    (
        "health-version-ignores-settings",
        "tests/unit/test_main_version.py::test_reported_version[calver]",
        PREFIX + "api/health.py",
        '"version": settings.version,',
        '"version": "dev",',
    ),
]
