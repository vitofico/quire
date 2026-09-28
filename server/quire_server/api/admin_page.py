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

_SOURCE_LABELS = {"wikipedia": "Wikipedia", "openlibrary": "Open Library"}

# Label, unit and one line of help for each setting the page can change, in
# the order `runtime_settings.EDITABLE` lists them. The help follows
# docs/configuration.md.
_EDITABLE_TEXT = {
    "QUIRE_SERVER_AI_TIMEOUT_S": (
        "Insight time limit",
        "seconds",
        "How long one insight model call may take. Raise it for a slow local model. "
        "The app waits out up to 285 seconds in full.",
    ),
    "QUIRE_SERVER_AI_PROFILE_TIMEOUT_S": (
        "Reader Profile time limit",
        "seconds",
        "The same for a Reader Profile refresh. Raise it together with the insight limit.",
    ),
    "QUIRE_SERVER_AI_SOURCES": (
        "Look books up on",
        None,
        "Unticking both turns lookups off: shorter, faster prompts, but the model has only "
        "what it already knows about the book.",
    ),
    "QUIRE_SERVER_AI_RATE_PER_MIN": (
        "Generations per minute",
        None,
        "For the whole server. A request over the limit waits for its turn.",
    ),
    "QUIRE_SERVER_AI_DAILY_BUDGET": (
        "Generations per reader per day",
        None,
        "Regenerations included; the day ends at midnight UTC. 0 turns the budget off.",
    ),
    "QUIRE_SERVER_AI_REGEN_DAILY_LIMIT": (
        "Regenerations per reader per day",
        None,
        "0 blocks regeneration.",
    ),
}

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
button.quiet { padding: 2px 10px; font-size: 13px; background: transparent; color: var(--accent); }
.field { padding: 12px 0; border-top: 1px solid var(--line); }
.field:first-of-type { border-top: 0; padding-top: 0; }
.field label, .field legend { display: block; font-weight: 600; padding: 0; }
.field fieldset { border: 0; margin: 0; padding: 0; min-width: 0; }
.field .choice { display: inline-flex; gap: 6px; align-items: center; margin: 6px 18px 0 0;
  font-weight: 400; }
input[type=number] { font: inherit; width: 10em; max-width: 100%; margin-top: 6px;
  padding: 5px 8px; border: 1px solid var(--line); border-radius: 6px; background: var(--bg);
  color: var(--fg); }
input:disabled { opacity: 0.6; }
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


def _plain(value: object) -> str:
    """A number as a person would type it: ``120.0`` as ``120``."""
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value)


def _sources_text(value: str) -> str:
    names = [_SOURCE_LABELS.get(n, n) for n in value.split(",") if n]
    return " and ".join(names) or "none"


def _settings_notice(notice: dict) -> str:
    if not notice["ok"]:
        items = "".join(f"<li>{_e(message)}</li>" for message in notice["errors"])
        return f'<div class="probe bad"><strong>Nothing was saved.</strong><ul>{items}</ul></div>'
    if not notice["changed"]:
        return (
            '<div class="probe ok"><strong>Nothing changed.</strong>'
            "<p>Every value was already the one in force.</p></div>"
        )
    changed = ", ".join(notice["changed"])
    return (
        '<div class="probe ok"><strong>Saved. The next AI request uses the new '
        f"values.</strong><p>Changed: <code>{_e(changed)}</code>.</p></div>"
    )


def _origin(row: dict) -> str:
    """Where the value in force comes from, with the way to change that."""
    name = row["name"]
    default = row["default"]
    shown_default = _sources_text(default) if name == "QUIRE_SERVER_AI_SOURCES" else _plain(default)
    if row["source"] == "set":
        return (
            "Set in the server's environment, which takes precedence. To change it here, "
            "remove it there and restart the server."
        )
    if row["source"] == "saved":
        return (
            f"Saved here by {_e(row['updated_by'])}, {_e(_when(row['updated_at']))}. "
            f"Built-in default: {_e(shown_default)}. "
            f'<button class="quiet" type="submit" form="reset-settings" name="reset" '
            f'value="{_e(name)}">Use default</button>'
        )
    return "Built-in default."


def _editable_field(row: dict) -> str:
    name = row["name"]
    label, unit, help_text = _EDITABLE_TEXT[name]
    disabled = " disabled" if row["locked"] else ""
    field_id = f"s-{name}"
    # The value as shown, so the server can tell which fields the admin
    # edited from the ones another admin changed since the page loaded.
    was = (
        ""
        if row["locked"]
        else f'<input type="hidden" name="{_e(name)}__was" value="{_e(_plain(row["value"]))}">'
    )
    meta = (
        f'<p class="note"><code>{_e(name)}</code>. {_e(help_text)}</p>'
        f'<p class="note">{_origin(row)}</p>'
    )
    if name == "QUIRE_SERVER_AI_SOURCES":
        ticked = set(filter(None, str(row["value"]).split(",")))
        boxes = "".join(
            f'<label class="choice"><input type="checkbox" name="{_e(name)}" '
            f'value="{_e(source)}"{" checked" if source in ticked else ""}{disabled}>'
            f"{_e(text)}</label>"
            for source, text in _SOURCE_LABELS.items()
        )
        # The empty hidden value tells the server the field was on the form,
        # so no box ticked means "no lookups", not "leave it alone".
        hidden = "" if row["locked"] else f'<input type="hidden" name="{_e(name)}" value="">'
        return (
            f'<div class="field"><fieldset><legend>{_e(label)}</legend>'
            f"{hidden}{was}{boxes}</fieldset>{meta}</div>"
        )
    # The browser's own checks match the server's, so a value the server
    # would accept is never blocked before it gets there.
    step = "any" if unit == "seconds" else "1"
    minimum = "1" if name == "QUIRE_SERVER_AI_RATE_PER_MIN" else "0"
    shown_unit = f" ({_e(unit)})" if unit else ""
    return (
        f'<div class="field"><label for="{_e(field_id)}">{_e(label)}{shown_unit}</label>'
        f'<input type="number" id="{_e(field_id)}" name="{_e(name)}" '
        f'value="{_e(_plain(row["value"]))}" step="{step}" min="{minimum}"{disabled}>'
        f"{was}{meta}</div>"
    )


def _editable_section(status: dict, notice: dict | None) -> str:
    rows = status.get("editable")
    if rows is None:
        return ""
    fields = "".join(_editable_field(row) for row in rows)
    shown_notice = _settings_notice(notice) if notice is not None else ""
    return (
        '<section id="ai-settings"><h2>AI settings</h2>'
        '<p class="note">Changes apply to the next AI request, without a restart, and are '
        "kept in the database. A value set in the server's environment or <code>.env</code> "
        "wins over one saved here.</p>"
        f"{shown_notice}"
        f'<form method="post" action="/quire-admin/settings">{fields}'
        '<button type="submit">Save AI settings</button></form>'
        '<form id="reset-settings" method="post" action="/quire-admin/settings"></form>'
        "</section>"
    )


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
        "environment or <code>.env</code>; <em>saved</em> was saved under AI settings "
        "above; <em>default</em> means nothing set it, so the built-in value applies. "
        "Secrets and URL passwords show as <code>***</code>.</p>"
        '<div class="table"><table><thead><tr><th>Variable</th><th>Value</th><th></th></tr>'
        f"</thead><tbody>{rows}</tbody></table></div></section>"
    )


def render_page(
    status: dict, probe: dict | None = None, settings_notice: dict | None = None
) -> str:
    serving = ", ".join(_MODE_LABELS.get(m, m) for m in status["modes"]) or "health checks only"
    return (
        '<!doctype html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width, initial-scale=1">'
        f"<title>Quire Server status</title><style>{_STYLE}</style></head><body><main>"
        "<header><h1>Quire Server</h1>"
        f'<p class="meta">Build <code>{_e(status["version"])}</code>. '
        f"Serving {_e(serving)}.</p></header>"
        f"{_warnings_section(status)}{_ai_section(status, probe)}"
        f"{_editable_section(status, settings_notice)}"
        f"{_migrations_section(status)}{_settings_section(status)}"
        "</main></body></html>"
    )
