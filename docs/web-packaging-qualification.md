# Quire Web packaging qualification

Qualification ran against disposable local Docker Compose projects. The
pre-web image was built from `8c965363658b`. The web candidate was built from
the committed source archive at `5615e60a09add3d48440c54305f672f8b418fb1a`
and retained locally as `quire-server:quire-web-final` with image ID
`sha256:bddd7856ccc9c1958a79a9ba375e9fd2eccbd377b20d20edb9c2c03d9083a475`.

| Check | Result |
| --- | --- |
| Server regression | Full 952, sync-only 93, AI-only 23 passed against PostgreSQL 16. |
| Minimal Compose | Ready on PostgreSQL. Shell, deep link, worker, asset headers, and browser/API build markers passed. Unknown API and asset paths returned 404. The runtime image has no Node executable. |
| Full Compose | Verified Caddy TLS using the stack CA. Calibre-Web stayed at `/`. Chromium authenticated with a demo Calibre-Web account, browsed OPDS, acquired a fixture EPUB and found it on the library shelf. The download response was `private, no-store`. |
| Chromium offline restart | Closed Chromium after importing and opening the EPUB. Reopened the same profile offline, restored the reader and navigated to a different chapter. Cache Storage contained the public shell and assets. The bootstrap request failed offline. |
| Open tab during upgrade | Chromium reloaded an existing tab and opened a second tab while worker B waited. Both tabs remained on A. The second tab opened the lazy Reader and its local EPUB offline after the server had switched to B. A test-only MessageChannel verified the controlling worker. After both old tabs closed, B activated in about one second on all three repeats and reopened offline. The active worker honored the online 404 after the web mount was disabled. |
| Database upgrade and rollback | The pre-web library and progress rows survived the upgrade to `progress_006`. Downgrading with `alembic downgrade progress@-1` and restarting the pre-web image returned readiness at `progress_005`. The original rows were still readable over the authenticated APIs. |

## Reproduce the package checks

Run from the repository root. Build with `server/Dockerfile` and start the
minimal reference Compose with a test Calibre-Web URL. The server deployment guide also shows the
full-stack setup. For the full stack, trust the stack CA and
use the HTTPS origin when running `smoke_web.py`.

```sh
docker build -f server/Dockerfile --build-arg QUIRE_VERSION=local-check -t quire-server:check .
(cd server && QUIRE_SERVER_IMAGE=quire-server:check \
  QUIRE_SERVER_CWA_BASE_URL=http://localhost:8083 docker compose up -d)
python3 server/scripts/smoke_web.py http://localhost:8000 local-check
pnpm --dir web install --frozen-lockfile
pnpm --dir web exec playwright install chromium
node web/scripts/smoke-offline.mjs http://localhost:8000
```

The rollout and downgrade steps are in the
[Quire Server deployment guide](../server/README.md#quire-web). The
[server CI workflow](../.github/workflows/server-ci.yaml) runs the package
checks, server modes, web unit tests and desktop Playwright suite.

## Reproduce an open-tab upgrade

Build disposable A and B copies of the candidate. Add a test marker to A's
entry point and lazy Reader so its asset filenames differ. In each worker,
add a test-only `MessageChannel` handler that reports its A or B label.
The retained image was built without those probes.

1. Serve A on a disposable Compose project. Open it in Chromium and import
   the selection fixture. Confirm controller A before replacing the image
   with B on the same origin.
2. Reload the original Quire tab, then open another. Both should receive
   shell A while the direct server response identifies B. Drop network
   access and open the EPUB from the second tab, which exercises A's lazy
   Reader bundle.
3. Restore network access. If the registration has no waiting worker, run
   `registration.update()`. Verify waiting B with one message. In the same
   browser profile, open `/health` as an out-of-scope observer.
4. Retain the verified waiting object in the observer, close both Quire
   tabs, and wait for its state to become `activated` as shown below. Read
   the state without sending messages to A during the wait. Preliminary runs that polled A with
   `MessageChannel` timed out during activation.
5. Navigate the observer to `/quire/`. Verify controller B with the test
   message, then reopen B offline. With the server back online, disable
   `QUIRE_SERVER_WEB_ENABLED` and confirm the active worker passes through
   the server's 404.

```js
await sentinel.evaluate(async () => {
  const registration = await navigator.serviceWorker.getRegistration('/quire/')
  window.quireExpectedWorker = registration.waiting
})
await oldTab.close()
await secondTab.close()
await sentinel.waitForFunction(
  () => window.quireExpectedWorker.state === 'activated',
  {},
  { timeout: 90000 },
)
```

The harness and local logs from the qualification run are stored under
`/tmp/quire-web-open-tab-upgrade-idle.mjs` and
`/tmp/quire-web-final-{1,2,3}-probe.log`.

## Privacy review

The trusted reader shell places its script-denying content security policy
before copied publication nodes. Publication scripts, nested browsing
contexts, script-bearing attributes and active SVG elements are dropped.
Package-local media use Blob URLs. Links to external pages require reader
confirmation. Verified renderer tests cover attempted script execution
and external resource loads.

The service worker accepts only build output marked public by the server.
Authenticated catalog requests omit cookies, refuse redirects and restrict
credentials to the catalog origin. Same-origin catalog requests also reject
decoded and normalized Quire routes. Native browser login requires the
operator to configure a Quire-only origin and opt in explicitly. The shared Calibre-Web topology depends on trusting
both apps with the browser origin. Connected browser requests
are bound to the verified account namespace.

This record covers desktop packaging, cache boundaries, and rollback.
