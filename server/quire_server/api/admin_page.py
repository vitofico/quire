"""HTML rendering of the admin status document (issue #102).

Standard library only: the page is one document built from the same dict
``GET /quire-admin/v1/status`` returns, with no script and no external
resource, which is what lets ``admin.PAGE_HEADERS`` send a strict CSP.
Every value passes through ``_e`` before it reaches the markup.
"""

from __future__ import annotations

from datetime import datetime
from html import escape

_MODE_LABELS = {"progress": "reading progress and library sync", "ai": "AI insights"}

_STYLE = """
:root { color-scheme: light dark; --bg: #fafaf7; --fg: #1d1d1b; --muted: #6b6b66;
  --line: #e2e2dc; --card: #ffffff; --ok: #1f7a3a; --bad: #b3261e; --accent: #3b5bdb;
  --on-accent: #ffffff; }
@media (prefers-color-scheme: dark) {
  :root { --bg: #161615; --fg: #ececea; --muted: #9a9a94; --line: #2e2e2b;
    --card: #1f1f1d; --ok: #6cc585; --bad: #f2877e; --accent: #8ea4ff;
    --on-accent: #10131f; } }
* { box-sizing: border-box; }
body { margin: 0; background: var(--bg); color: var(--fg);
  font: 15px/1.5 system-ui, -apple-system, "Segoe UI", sans-serif; }
main { max-width: 860px; margin: 0 auto; padding: 24px 16px 48px; }
h1 { font-size: 24px; margin: 0 0 4px; }
h2 { font-size: 17px; margin: 0 0 12px; }
section { background: var(--card); border: 1px solid var(--line); border-radius: 10px;
  padding: 16px 18px; margin-top: 16px; }
code { font: 13px/1.4 ui-monospace, SFMono-Regular, Menlo, monospace; overflow-wrap: anywhere; }
.meta, .note { color: var(--muted); font-size: 13px; margin: 4px 0 0; }
.ok { color: var(--ok); }
.bad { color: var(--bad); }
ul { margin: 0; padding-left: 20px; }
li + li { margin-top: 6px; }
dl { display: grid; grid-template-columns: max-content 1fr; gap: 6px 16px; margin: 0; }
dt { color: var(--muted); }
dd { margin: 0; overflow-wrap: anywhere; }
.probe { border-left: 4px solid; padding: 8px 12px; margin: 14px 0 0; }
.probe.ok { border-color: var(--ok); }
.probe.bad { border-color: var(--bad); }
.probe p { margin: 4px 0 0; color: var(--fg); }
form { margin-top: 14px; }
button { font: inherit; padding: 7px 14px; border-radius: 8px; border: 1px solid var(--accent);
  background: var(--accent); color: var(--on-accent); cursor: pointer; }
.table { overflow-x: auto; }
table { border-collapse: collapse; width: 100%; }
th, td { text-align: left; padding: 6px 8px; vertical-align: top;
  border-top: 1px solid var(--line); }
th { color: var(--muted); font-weight: 500; font-size: 13px; border-top: 0; }
td.src { color: var(--muted); font-size: 13px; white-space: nowrap; }
tr.default td:nth-child(2) { color: var(--muted); }
"""


def _e(value: object) -> str:
    return escape(str(value), quote=True)


def _when(iso: str | None) -> str:
    """``2026-09-24T12:34:56+00:00`` as ``2026-09-24 12:34 UTC``."""
    if not iso:
        return "never"
    try:
        return datetime.fromisoformat(iso).strftime("%Y-%m-%d %H:%M UTC")
    except ValueError:
        return iso


def _value(value: object) -> str:
    if value is None:
        return "unset"
    if isinstance(value, bool):
        return "true" if value else "false"
    if value == "":
        return "(empty)"
    return str(value)


def _setting(status: dict, name: str) -> object:
    for row in status["settings"]:
        if row["name"] == name:
            return row["value"]
    return None


def _reachability(reachable: bool | None, checked_at: str | None) -> str:
    if reachable is None:
        return "Not checked since the server started."
    if reachable:
        return f'<span class="ok">Answered</span> at {_e(_when(checked_at))}.'
    return f'<span class="bad">Failed</span> at {_e(_when(checked_at))}.'


def _warnings_section(status: dict) -> str:
    warnings = status["warnings"]
    if not warnings:
        body = '<p class="ok">None. Every setting checked at startup looks right.</p>'
    else:
        items = "".join(f"<li>{_e(w)}</li>" for w in warnings)
        body = f'<ul class="bad">{items}</ul>'
    return f"<section><h2>Problems found at startup</h2>{body}</section>"


def _probe_box(probe: dict) -> str:
    seconds = probe["elapsed_ms"] / 1000
    if probe["ok"]:
        return (
            '<div class="probe ok"><strong>The AI provider answered in '
            f"{seconds:.1f} s.</strong><p>Model {_e(probe['model'])} "
            "returned a valid structured answer.</p></div>"
        )
    error = probe["error"]
    hint = f"<p>{_e(error['hint'])}</p>" if error.get("hint") else ""
    upstream = (
        f", provider answered HTTP {_e(error['provider_status'])}"
        if error.get("provider_status")
        else ""
    )
    return (
        f'<div class="probe bad"><strong>{_e(error["message"])}</strong>{hint}'
        f'<p class="meta">Code {_e(error["code"])}{upstream}, after {seconds:.1f} s.</p></div>'
    )


def _ai_section(status: dict, probe: dict | None) -> str:
    ai = status["ai"]
    if ai is None:
        return (
            "<section><h2>AI provider</h2>"
            "<p>AI insights are switched off (<code>QUIRE_SERVER_AI_ENABLED=false</code>).</p>"
            "</section>"
        )
    parts = ["<section><h2>AI provider</h2>"]
    if not ai["configured"]:
        parts.append(
            '<p class="bad">AI insights are on, but no provider is configured, so the app '
            "reports AI as unavailable. Set <code>QUIRE_SERVER_AI_BASE_URL</code> and "
            "<code>QUIRE_SERVER_AI_MODEL</code>.</p>"
        )
    else:
        health = ai["health"]
        rows = [
            ("Model", f"<code>{_e(_setting(status, 'QUIRE_SERVER_AI_MODEL'))}</code>"),
            ("Endpoint", f"<code>{_e(_setting(status, 'QUIRE_SERVER_AI_BASE_URL'))}</code>"),
            (
                "Last model call",
                _reachability(health["provider_reachable"], health["provider_last_checked_at"]),
            ),
        ]
        if health["provider_reachable"] is False and health["last_failure_class"]:
            rows.append(("Last failure", f"<code>{_e(health['last_failure_class'])}</code>"))
        for source in health["retrieval_sources"]:
            rows.append(
                (
                    f"Source: {_e(source['name'])}",
                    _reachability(source["reachable"], source["last_checked_at"]),
                )
            )
        cells = "".join(f"<dt>{label}</dt><dd>{value}</dd>" for label, value in rows)
        parts.append(f"<dl>{cells}</dl>")
    if probe is not None:
        parts.append(_probe_box(probe))
    timeout = _setting(status, "QUIRE_SERVER_AI_TIMEOUT_S")
    seconds = f"{timeout:g}" if isinstance(timeout, int | float) else _value(timeout)
    parts.append(
        '<form method="post" action="/quire-admin/probe">'
        '<button type="submit">Test AI connection</button>'
        f'<p class="note">Sends a short test request to the provider and waits up to '
        f"{_e(seconds)} seconds in total (<code>QUIRE_SERVER_AI_TIMEOUT_S</code>).</p></form>"
    )
    parts.append("</section>")
    return "".join(parts)


def _migrations_section(status: dict) -> str:
    migrations = status["migrations"]
    if "error" in migrations:
        body = (
            f'<p class="bad">Could not read the migration state: {_e(migrations["error"])}. '
            "Check that the database is reachable.</p>"
        )
    elif migrations["missing"]:
        missing = ", ".join(migrations["missing"])
        body = (
            f'<p class="bad">Migrations are behind: {_e(missing)} not applied. Run the '
            'migration step described in the server README, "Deploy-time migrations".</p>'
        )
    else:
        body = '<p class="ok">Up to date.</p>'
    applied = ", ".join(migrations.get("applied", [])) or "none"
    return (
        f"<section><h2>Database</h2>{body}"
        f'<p class="meta">Applied migration heads: <code>{_e(applied)}</code></p></section>'
    )


def _settings_section(status: dict) -> str:
    rows = "".join(
        f'<tr class="{_e(row["source"])}"><td><code>{_e(row["name"])}</code></td>'
        f"<td><code>{_e(_value(row['value']))}</code></td>"
        f'<td class="src">{_e(row["source"])}</td></tr>'
        for row in status["settings"]
    )
    return (
        "<section><h2>Settings</h2>"
        '<p class="note">What this server is running with. <em>set</em> came from the '
        "environment or <code>.env</code>; <em>default</em> means nothing set it, so the "
        "built-in value applies. Secrets and URL passwords show as <code>***</code>.</p>"
        '<div class="table"><table><thead><tr><th>Variable</th><th>Value</th><th></th></tr>'
        f"</thead><tbody>{rows}</tbody></table></div></section>"
    )


def render_page(status: dict, probe: dict | None = None) -> str:
    serving = ", ".join(_MODE_LABELS.get(m, m) for m in status["modes"]) or "health checks only"
    return (
        '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        f"<title>Quire Server status</title><style>{_STYLE}</style></head><body><main>"
        "<header><h1>Quire Server</h1>"
        f'<p class="meta">Build <code>{_e(status["version"])}</code>. '
        f"Serving {_e(serving)}.</p></header>"
        f"{_warnings_section(status)}{_ai_section(status, probe)}"
        f"{_migrations_section(status)}{_settings_section(status)}"
        "</main></body></html>"
    )
