# Phase 2.1 — CWA co-deploy & first opds-sync rollout

This app repo no longer hosts the Kubernetes manifests for `opds-sync`.
The cluster repo is the source of truth.

- **Manifests:** [`theficos-cluster/applications/opds-sync/`](https://github.com/vitofico/theficos-cluster/tree/main/applications/opds-sync)
- **Rollout & operations:** [`theficos-cluster/docs/opds-sync-rollout.md`](https://github.com/vitofico/theficos-cluster/blob/main/docs/opds-sync-rollout.md)
- **Deploy:** `make deploy APP=opds-sync` from the cluster repo.

This app repo only owns:

- The **server source** under `server/` — pushed as
  `ghcr.io/vito/opds-sync:<sha>` + `:latest` by the `server-ci` workflow
  on every `main` push.
- The **Android app** that calls `/sync/v1/...` on whatever
  `Settings → calibre-web` URL the user configures.

For the Android-side wiring (no BuildConfig changes — the URL is derived
at runtime from `CalibreCredentialStore.baseUrl`), see the Phase 2.1
spec at `docs/superpowers/specs/2026-05-06-phase-2-1-cwa-auth-proxy.md`.
