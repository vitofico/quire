# Quire Web native account qualification

A packaged Quire Server ran against a disposable PostgreSQL 16 database behind Caddy TLS on a dedicated loopback origin. The deployment used native password auth, the explicit web-native opt-in and the sync-only configuration. The image reported build `quire-web-native-qualification` and manifest digest `sha256:cc92d0a0703b702078fe3962ff6402d0e3d850a295b6407b248afe8af3e6ec51`. Readiness confirmed `auth_003` and `progress_006`. Chromium used a certificate exception for the local Caddy certificate, and the packaged service worker controlled the page.

| Scenario | Observed result |
| --- | --- |
| Browser sign-in | Bootstrap advertised native login on the dedicated origin. The remembered session used a secure, HttpOnly, SameSite=Lax cookie with an expiry. The second identity used a session cookie. |
| Offline reading update | The first account synced an imported EPUB, marked it finished offline, and reloaded the shell. Explicitly opening the remembered account restored the EPUB and its single pending update. |
| Lost authority and account switch | Replacing the browser cookie with the second account’s session caused the old account binding to return HTTP 409. Reconnection locked the first account. An explicit sign-in to the second account showed an empty shelf and an export without the first account's library or progress. |
| Shared-device sign-out | The second account synced its own import. Sign-out removed the browser cookie, returned HTTP 401 on the next session check and cleared its downloaded EPUB and queue. The first account retained its EPUB and unsent update. |
| Original account reconnect and export | Explicitly signing back into the first account drained its queue. The downloaded JSON named the first account and contained its synced book and offline completion status. |

This run used two synthetic accounts and a separate database from the Android interoperability run. It exercised native password sessions and Chromium. OIDC return destinations and deletion reauthentication are covered by the server identity integration tests.
