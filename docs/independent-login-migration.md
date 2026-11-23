# Migrate Quire login while keeping Calibre-Web for books

## Choose the right path

| Reader | Operator action | Reader action |
| --- | --- | --- |
| Already syncs to Quire using Calibre-Web credentials | Use the migration command below to create a native account **and bind it to the historical username in one operation**. | Upgrade the app in place, then sign in to Quire on each device. Keep its existing catalog connection. |
| New Quire reader, including someone who has only browsed OPDS | Create a fresh account with `python /app/scripts/create_native_account.py <email>`. | Connect to Quire for sync. Configure the catalog with their Calibre-Web credentials. |
| Existing native Quire reader | Keep the same Quire account. | Add their Calibre-Web catalog independently. |

An account migration makes a new Quire login the owner of the reader's existing server data. An ordinary new-account command creates an empty account. For a returning reader, run the migration first. The tool refuses an email already assigned to a native account and does not combine two populated libraries. Resolve any conflicting identity privately before proceeding.

## Prepare the installation

1. Record the live image references for the Quire runtime and database migration container, their configuration, and the ingress. Check readiness and take an access-controlled database backup. Restore it to a disposable database and compare the Alembic heads and aggregate ownership counts. The server includes `scripts/pg_backup.py` for PostgreSQL backup and restore. Keep its output on private storage.
2. Deploy the new server with `QUIRE_SERVER_AUTH_BACKEND=transition`. Point `QUIRE_SERVER_CWA_BASE_URL` at the existing Calibre-Web instance so legacy readers can still authenticate. Run `python /app/scripts/migrate.py` with the installation's existing feature flags. Confirm readiness after migration. Leave `QUIRE_IDENTITY_PUBLIC_URL` unset on a shared ebook hostname.
3. Expose `POST /auth/v1/login`, `GET /auth/v1/me`, `POST /auth/v1/logout` and `GET /auth/v1/account/export` to Quire on the mobile service origin. Existing reading, library, AI and agent routes continue to target Quire. Preserve Calibre-Web routing for `/`, OPDS, covers and downloads. Keep `/internal/v1/` inaccessible from the public ingress. The Theficos manifests use exact path matches for the four account operations.
4. Verify login with a dedicated test reader. Check the library, progress, an authenticated catalog feed and an EPUB acquisition. Verify that a Quire logout ends the Quire session and the catalog still works. Avoid generating a paid AI result for a health check.

## Migrate an existing reader

Arrange this with the reader. Verify control of both the historical Calibre account and their chosen email using your existing account administration process. Matching names or emails alone do not establish ownership. Use a fresh Quire password. Enter passwords interactively through a trusted terminal, outside shell history and chat.

1. Upgrade every device that will sync, preserving its app data. Let queued changes sync on the legacy account where possible. Keep the original server URL during the change so the app can match the existing local highlight owner. Record an in-progress title and a highlight the reader can recognize afterwards.
2. Run the read-only preview. These examples use a Theficos shell with the MicroK8s CLI. Replace the two quoted placeholders with the verified mapping. For Docker Compose, replace the `microk8s kubectl ... --` portion with `docker compose exec quire-server`.

   ```sh
   microk8s kubectl -n calibre-web exec deploy/quire-server -c quire-server -- \
     python /app/scripts/migrate_calibre_account.py '<calibre-username>' '<quire-email>'
   ```

   Compare the per-table counts to the expected reader. Unexpectedly empty counts for an active reader, an already reserved name, or an existing native email warrant investigation before proceeding.

3. Apply the same mapping, confirming the historical username and entering the new password at the prompts.

   ```sh
   microk8s kubectl -n calibre-web exec -it deploy/quire-server -c quire-server -- \
     python /app/scripts/migrate_calibre_account.py '<calibre-username>' '<quire-email>' --apply
   ```

   Retain the returned `native:<id>` in your private account inventory. The reservation keeps the library, progress, highlight revisions, tombstones and personal AI records under their existing storage keys. At this point Basic authentication for this reader on Quire stops. Their credentials still work on Calibre-Web itself. Other unlinked readers can keep using Basic on Quire during transition.

4. If the reader administered Quire, the operator must add their verified `native:<id>` to `QUIRE_SERVER_ADMIN_USERS` and verify access under that principal. Explicit admin grants use the Quire identity. Handle pre-existing grants for remaining administrators separately.
5. On Android, open **Settings → Quire account → Sign in to Quire**. Enter the same Quire service URL the device previously synced to, the chosen email and the new password. Keep the catalog entry pointing to the Calibre-Web feed. For Theficos, the service URL is `https://ebooks.theficos.dedyn.io` and the catalog URL is `https://ebooks.theficos.dedyn.io/opds`. Re-enter the catalog password in the catalog settings only if needed.
6. Open the recorded book, confirm its position and highlight, then make a new reading change and check it on another device. Browse the catalog and open a book that was not downloaded previously. On a fresh device a sideloaded book may need its original EPUB again; the server mirror contains metadata and progress.

## Troubleshooting and rollback

| Symptom | Check |
| --- | --- |
| Catalog works but Quire shows an empty library | Verify the Quire origin, authenticated native ID and the intended binding. Pause new sync writes on that device while checking ownership. |
| Quire works but downloads challenge for credentials | Confirm the full catalog URL and reauthenticate Calibre-Web in catalog settings. A Quire password will not authenticate the reader to their catalog. |
| The old app stops syncing just after binding | Install the compatible app over the existing installation and use native Quire login. Preserve app data so offline edits remain available for reconciliation. |
| A server-only rollback is needed before the first binding | With no active native readers or writes to preserve, restore the captured image/configuration and route set. The added bindings table can remain. Verify both Calibre and Quire again. |
| A regression appears after binding or native adoption | Keep reservations intact. Use a binding-aware server in transition or fix forward. An older server may accept Basic for a reserved username and expose that reader's data. Reverting a live database requires a quiesced system, an explicit recovery decision, and reconciliation of writes and closures since backup. |

After all readers and integrations have moved, switch Quire to `native`, verify sync and the catalog again, and remove the server's legacy Calibre authentication dependency. The phone still reaches Calibre-Web for books. The [implementation plan](independent-login-plan.md) describes ownership, web coordination and acceptance checks.
