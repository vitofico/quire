# Security Policy

## Supported versions

This project is pre-1.0. Only the latest commit on `main` is supported.
Older releases get no backports.

## Reporting a vulnerability

Please **do not** open a public GitHub issue for security problems.

Email **vito.fico@hivepower.tech** with:

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
- Sync server (`server/`).
- The HTTP surface between them and calibre-web.

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
