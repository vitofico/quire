# Security Policy

## Supported versions

This project is pre-1.0. Only the latest commit on `main` is supported.
Older releases get no backports.

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security problems.

Use GitHub's private vulnerability reporting:

**https://github.com/vitofico/quire/security/advisories/new**

Include:

- A description of the issue.
- Steps to reproduce, including a minimal proof of concept if possible.
- Affected components (Android app, sync server, both).
- Your assessment of impact (data exposure, auth bypass, RCE, etc.).

You'll get an acknowledgement within **5 business days**. If the report
is accepted, expect a fix or mitigation within **30 days** for high-
severity issues, longer for lower-severity ones.

## Scope

In scope:

- Android app (`app/`, `auth/`, `core/`, `data/`, `reader/`).
- Sync server (`server/`), including the `/sync/v1/*`, `/library/v1/*`,
  and `/ai/v1/*` surfaces, the request-size + request-id middleware, and
  the three deploy modes (full, sync-only, AI-only).
- The AI auth seam (`server/quire_server/api/ai_auth.py`), including both
  `basic` (calibre-web Basic proxy) and `token` (HMAC-SHA256 bearer) modes
  and the `kid`-rotation surface for token secrets.
- The HTTP surface between the app, the sync server, and calibre-web.

The `GET /ai/v1/health` endpoint is **unauthenticated by design** (parity
with the always-on root `/health` and `/readyz` probes). Operators and the
Android Settings status row poll it without going through Basic auth.
Nothing in its body is more sensitive than `/ai/v1/config` already
exposes: tri-state provider reachability, the most recently observed model
id (not the configured value), and per-source retrieval reachability.
Reports of additional fields being added that DO leak sensitive
operational data are in scope.

Out of scope:

- Vulnerabilities in calibre-web itself — report those upstream at
  https://github.com/janeczku/calibre-web.
- Vulnerabilities in third-party dependencies — report those to the
  upstream project. We track them via Renovate / GitHub security
  advisories.
- Misconfigurations of self-hosted deployments.

## Disclosure

Coordinated disclosure: we'll credit reporters in the release notes
unless they prefer to stay anonymous. We do not currently offer a
bounty.
