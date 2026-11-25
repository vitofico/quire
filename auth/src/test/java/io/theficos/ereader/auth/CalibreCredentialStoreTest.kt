package io.theficos.ereader.auth

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CalibreCredentialStoreTest {
    @Before fun setUp() { FakeAndroidKeyStore.setup() }

    @Test fun `round trip credentials`() = runTest {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        assertThat(store.get()).isNull()
        store.put(CalibreCredentials(baseUrl = "https://lib.example", username = "u", password = "p"))
        val got = store.get()
        assertThat(got?.baseUrl).isEqualTo("https://lib.example")
        assertThat(got?.username).isEqualTo("u")
        assertThat(got?.password).isEqualTo("p")
    }

    @Test fun `clear removes credentials`() = runTest {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.put(CalibreCredentials("u", "u", "p"))
        store.clear()
        assertThat(store.get()).isNull()
    }

    @Test fun `flow emits null when nothing stored`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        assertThat(store.flow.value).isNull()
    }

    @Test fun `put updates flow synchronously`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.put(CalibreCredentials("https://example", "u", "p"))
        assertThat(store.flow.value).isEqualTo(CalibreCredentials("https://example", "u", "p"))
    }

    @Test fun `clear emits null`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.put(CalibreCredentials("https://example", "u", "p"))
        store.clear()
        assertThat(store.flow.value).isNull()
    }

    @Test fun `flow value matches get`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.put(CalibreCredentials("https://example", "u", "p"))
        assertThat(store.flow.value).isEqualTo(store.get())
    }

    // ---------- A-1: scheme-aware account API ----------

    @Test fun `saveBasicAccount round trips through accountFlow`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBasicAccount("https://lib.example", "alice", "s3cret")
        val account = store.getAccount()
        assertThat(account).isInstanceOf(AccountCredentials.Basic::class.java)
        val basic = account as AccountCredentials.Basic
        assertThat(basic.baseUrl).isEqualTo("https://lib.example")
        assertThat(basic.username).isEqualTo("alice")
        assertThat(basic.password).isEqualTo("s3cret")
        assertThat(store.accountFlow.value).isEqualTo(basic)
    }

    @Test fun `saveBearerAccount round trips through accountFlow`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBearerAccount("https://cloud.quire.app", "alice@example.com", "tok_abc123")
        val account = store.getAccount()
        assertThat(account).isInstanceOf(AccountCredentials.Bearer::class.java)
        val bearer = account as AccountCredentials.Bearer
        assertThat(bearer.baseUrl).isEqualTo("https://cloud.quire.app")
        assertThat(bearer.email).isEqualTo("alice@example.com")
        assertThat(bearer.token).isEqualTo("tok_abc123")
        assertThat(store.accountFlow.value).isEqualTo(bearer)
    }

    @Test fun `legacy get returns null for bearer accounts`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBearerAccount("https://cloud.quire.app", "alice@example.com", "tok_xyz")
        // The legacy CalibreCredentials view doesn't apply to bearer accounts;
        // unconverted callers see "no credentials" rather than fabricated ones.
        assertThat(store.get()).isNull()
        assertThat(store.flow.value).isNull()
    }

    @Test fun `legacy get returns the basic view for basic accounts`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBasicAccount("https://lib.example", "alice", "s3cret")
        assertThat(store.get())
            .isEqualTo(CalibreCredentials("https://lib.example", "alice", "s3cret"))
    }

    @Test fun `scheme switch removes inverse keys`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBasicAccount("https://lib.example", "alice", "s3cret")
        store.saveBearerAccount("https://cloud.quire.app", "alice@example.com", "tok_abc")
        // After switch: legacy view is null, account is bearer, no residue.
        assertThat(store.get()).isNull()
        assertThat(store.getAccount()).isInstanceOf(AccountCredentials.Bearer::class.java)
        // And back again.
        store.saveBasicAccount("https://lib.example", "alice", "new-pw")
        val basic = store.getAccount() as AccountCredentials.Basic
        assertThat(basic.password).isEqualTo("new-pw")
        // Re-reading should also yield BASIC (i.e. token-side keys are gone).
        val reread = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        assertThat(reread.getAccount()).isInstanceOf(AccountCredentials.Basic::class.java)
    }

    @Test fun `pre-migration record without scheme key is read as basic`() {
        // Simulate a pre-A-1 record by writing the legacy keys directly via
        // the older put() entry-point and then constructing a fresh store.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val seed = CalibreCredentialStore(ctx)
        seed.clear()
        seed.put(CalibreCredentials("https://lib.example", "alice", "s3cret"))
        // Strip the scheme key out-of-band to simulate a pre-A-1 install.
        val prefs = androidx.security.crypto.EncryptedSharedPreferences.create(
            ctx,
            "calibre_creds",
            androidx.security.crypto.MasterKey.Builder(ctx)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                .build(),
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        prefs.edit().remove("scheme").commit()

        // Fresh store should infer BASIC and surface the existing creds.
        val store = CalibreCredentialStore(ctx)
        val account = store.getAccount()
        assertThat(account).isInstanceOf(AccountCredentials.Basic::class.java)
        assertThat((account as AccountCredentials.Basic).username).isEqualTo("alice")
        // And the scheme key should now be backfilled.
        assertThat(prefs.getString("scheme", null)).isEqualTo("BASIC")
    }

    @Test fun `saveBearerAccount rejects whitespace token`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        var threw = false
        try {
            store.saveBearerAccount("https://cloud.quire.app", "alice@example.com", "tok with space")
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertThat(threw).isTrue()
    }

    @Test fun `saveBearerAccount rejects blank fields`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        var threwBaseUrl = false
        try { store.saveBearerAccount("", "alice@example.com", "tok") }
        catch (e: IllegalArgumentException) { threwBaseUrl = true }
        var threwEmail = false
        try { store.saveBearerAccount("https://x", "", "tok") }
        catch (e: IllegalArgumentException) { threwEmail = true }
        var threwToken = false
        try { store.saveBearerAccount("https://x", "a@b", "") }
        catch (e: IllegalArgumentException) { threwToken = true }
        assertThat(threwBaseUrl).isTrue()
        assertThat(threwEmail).isTrue()
        assertThat(threwToken).isTrue()
    }

    @Test fun `subject derives from scheme`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBasicAccount("https://lib.example", "Alice", "p")
        assertThat(store.getAccount()?.subject).isEqualTo("alice")
        store.saveBearerAccount("https://cloud.quire.app", "Alice@Example.com", "tok")
        assertThat(store.getAccount()?.subject).isEqualTo("alice@example.com")
    }

    @Test fun `accountFlow tracks saves and clears`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        assertThat(store.accountFlow.value).isNull()
        store.saveBasicAccount("https://lib.example", "alice", "s3cret")
        assertThat(store.accountFlow.value).isInstanceOf(AccountCredentials.Basic::class.java)
        store.saveBearerAccount("https://cloud.quire.app", "alice@example.com", "tok")
        assertThat(store.accountFlow.value).isInstanceOf(AccountCredentials.Bearer::class.java)
        store.clear()
        assertThat(store.accountFlow.value).isNull()
    }

    @Test fun `toString does not leak password or token`() {
        val basic = AccountCredentials.Basic("https://lib", "alice", "supersecret")
        assertThat(basic.toString()).doesNotContain("supersecret")
        val bearer = AccountCredentials.Bearer("https://cloud", "a@b", "tok_secret_xyz")
        assertThat(bearer.toString()).doesNotContain("tok_secret_xyz")
    }

    // ---------- Tier 1: optional Quire server URL override ----------

    @Test fun `saveBasicAccount persists optional quireServerUrl`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBasicAccount(
            baseUrl = "https://lib.example",
            username = "alice",
            password = "s3cret",
            quireServerUrl = "https://quire.example",
        )
        val basic = store.getAccount() as AccountCredentials.Basic
        assertThat(basic.quireServerUrl).isEqualTo("https://quire.example")
        // Survives a fresh load (round-trips through EncryptedSharedPreferences).
        val reread = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        val rereadBasic = reread.getAccount() as AccountCredentials.Basic
        assertThat(rereadBasic.quireServerUrl).isEqualTo("https://quire.example")
    }

    @Test fun `saveBasicAccount without quireServerUrl stores null`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBasicAccount("https://lib.example", "alice", "s3cret")
        val basic = store.getAccount() as AccountCredentials.Basic
        assertThat(basic.quireServerUrl).isNull()
    }

    @Test fun `saveBasicAccount canonicalizes quireServerUrl trailing slash`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBasicAccount(
            baseUrl = "https://lib.example",
            username = "alice",
            password = "s3cret",
            quireServerUrl = "  https://quire.example/  ",
        )
        val basic = store.getAccount() as AccountCredentials.Basic
        assertThat(basic.quireServerUrl).isEqualTo("https://quire.example")
    }

    @Test fun `blank quireServerUrl persists as null`() {
        // Settings will pass "" when the user clears the field; treat that
        // as "no override" rather than persisting an empty string.
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBasicAccount(
            baseUrl = "https://lib.example",
            username = "alice",
            password = "s3cret",
            quireServerUrl = "   ",
        )
        val basic = store.getAccount() as AccountCredentials.Basic
        assertThat(basic.quireServerUrl).isNull()
    }

    @Test fun `switch to bearer removes quireServerUrl key`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBasicAccount("https://lib.example", "alice", "s3cret", "https://quire.example")
        store.saveBearerAccount("https://cloud.quire.app", "alice@example.com", "tok_abc")
        // Re-read from a fresh store. The override key must be gone — a future
        // switch back to BASIC without an override must NOT resurrect it.
        val reread = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        assertThat(reread.getAccount()).isInstanceOf(AccountCredentials.Bearer::class.java)
        reread.saveBasicAccount("https://lib.example", "alice", "new-pw")
        val basic = reread.getAccount() as AccountCredentials.Basic
        assertThat(basic.quireServerUrl).isNull()
    }

    @Test fun `pre-tier-1 record without quireServerUrl key loads as null`() {
        // Seed a BASIC account via the older 3-arg API (no override key written).
        val seed = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        seed.clear()
        seed.saveBasicAccount("https://lib.example", "alice", "s3cret")
        // Fresh store load — quireServerUrl must surface as null, not as an
        // empty string or a default-baseUrl fallback.
        val reread = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        val basic = reread.getAccount() as AccountCredentials.Basic
        assertThat(basic.quireServerUrl).isNull()
    }

    // ---------- B2: Bearer session expiry + re-auth signal ----------

    @Test fun `saveBearerAccount persists expiresAtEpochMs and round-trips`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        val exp = 1_900_000_000_000L
        store.saveBearerAccount("https://cloud.quire.app", "a@b", "tok", expiresAtEpochMs = exp)
        assertThat((store.getAccount() as AccountCredentials.Bearer).expiresAtEpochMs).isEqualTo(exp)
        // Survives a fresh load through EncryptedSharedPreferences.
        val reread = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        assertThat((reread.getAccount() as AccountCredentials.Bearer).expiresAtEpochMs).isEqualTo(exp)
    }

    @Test fun `saveBearerAccount without expiry stores null`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBearerAccount("https://cloud.quire.app", "a@b", "tok")
        assertThat((store.getAccount() as AccountCredentials.Bearer).expiresAtEpochMs).isNull()
        val reread = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        assertThat((reread.getAccount() as AccountCredentials.Bearer).expiresAtEpochMs).isNull()
    }

    @Test fun `switching away from bearer removes the expiry key`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBearerAccount("https://cloud.quire.app", "a@b", "tok", expiresAtEpochMs = 123L)
        store.saveBasicAccount("https://lib.example", "alice", "s3cret")
        // Switch back to a bearer with no expiry: the stale key must not resurrect.
        val reread = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        reread.saveBearerAccount("https://cloud.quire.app", "a@b", "tok2")
        val fresh = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        assertThat((fresh.getAccount() as AccountCredentials.Bearer).expiresAtEpochMs).isNull()
    }

    @Test fun `notifyUnauthorized raises needsReauth only for bearer`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        // No account → no-op.
        store.notifyUnauthorized()
        assertThat(store.needsReauth.value).isFalse()
        // Basic → no-op (a 401 there is a wrong-password condition).
        store.saveBasicAccount("https://lib.example", "alice", "s3cret")
        store.notifyUnauthorized()
        assertThat(store.needsReauth.value).isFalse()
        // Bearer → raises the signal.
        store.saveBearerAccount("https://cloud.quire.app", "a@b", "tok")
        store.notifyUnauthorized()
        assertThat(store.needsReauth.value).isTrue()
        // notifyUnauthorized never mutates the stored credential.
        assertThat(store.getAccount()).isInstanceOf(AccountCredentials.Bearer::class.java)
    }

    @Test fun `checkSessionExpiry raises needsReauth when the token has lapsed`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBearerAccount("https://cloud.quire.app", "a@b", "tok", expiresAtEpochMs = 1_000L)
        // now before expiry → no signal.
        assertThat(store.checkSessionExpiry(nowEpochMs = 999L)).isFalse()
        assertThat(store.needsReauth.value).isFalse()
        // now at/after expiry → signal.
        assertThat(store.checkSessionExpiry(nowEpochMs = 1_000L)).isTrue()
        assertThat(store.needsReauth.value).isTrue()
    }

    @Test fun `checkSessionExpiry is a no-op when expiry is unknown`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBearerAccount("https://cloud.quire.app", "a@b", "tok") // null expiry
        assertThat(store.checkSessionExpiry(nowEpochMs = Long.MAX_VALUE)).isFalse()
        assertThat(store.needsReauth.value).isFalse()
    }

    @Test fun `saving a new account clears a pending re-auth signal`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBearerAccount("https://cloud.quire.app", "a@b", "tok")
        store.notifyUnauthorized()
        assertThat(store.needsReauth.value).isTrue()
        // A successful re-login (new save) resolves the signal.
        store.saveBearerAccount("https://cloud.quire.app", "a@b", "tok-fresh")
        assertThat(store.needsReauth.value).isFalse()
    }

    @Test fun `clear resets the re-auth signal`() {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.clear()
        store.saveBearerAccount("https://cloud.quire.app", "a@b", "tok")
        store.notifyUnauthorized()
        store.clear()
        assertThat(store.needsReauth.value).isFalse()
    }

    @Test fun `an already-expired stored session starts in the re-auth state on cold launch`() {
        val seed = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        seed.clear()
        seed.saveBearerAccount("https://cloud.quire.app", "a@b", "tok", expiresAtEpochMs = 1_000L)
        // A fresh store (process restart) whose stored expiry is already past.
        val reread = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        // Sanity: the stored expiry is in the past relative to "now".
        assertThat((reread.getAccount() as AccountCredentials.Bearer).isExpiredAt(System.currentTimeMillis()))
            .isTrue()
        assertThat(reread.needsReauth.value).isTrue()
    }

    @Test fun `isExpiredAt is fail-open when expiry is unknown`() {
        val bearer = AccountCredentials.Bearer("https://cloud", "a@b", "tok", expiresAtEpochMs = null)
        assertThat(bearer.isExpiredAt(Long.MAX_VALUE)).isFalse()
        val withExp = bearer.copy(expiresAtEpochMs = 10L)
        assertThat(withExp.isExpiredAt(9L)).isFalse()
        assertThat(withExp.isExpiredAt(10L)).isTrue()
    }

    // ---------- saved catalogs ----------

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun freshStore() = CalibreCredentialStore(context).apply { clear() }

    private fun entry(url: String, user: String? = null, pass: String? = null, name: String = "Books") =
        SavedCatalog(SavedCatalog.newId(), name, AccountCredentials.OpdsOnly(url, user, pass))

    /** The store's own file, to write what an older version left behind. */
    private fun rawPrefs() = androidx.security.crypto.EncryptedSharedPreferences.create(
        context,
        "calibre_creds",
        androidx.security.crypto.MasterKey.Builder(context)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build(),
        androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    @Test fun `catalogs round trip across a restart with and without a login`() {
        val store = freshStore()
        val kavita = entry("https://kavita.example/api/opds/secret-key", name = "Kavita")
        val feed = entry("https://feed.example/opds/?apiKey=abc", "alice", "hunter2")
        store.saveCatalog(kavita)
        store.saveCatalog(feed)

        val reread = CalibreCredentialStore(context)
        assertThat(reread.catalogs.value.map { it.id }).containsExactly(kavita.id, feed.id).inOrder()
        assertThat(reread.catalogs.value[0].catalog).isEqualTo(kavita.catalog)
        assertThat(reread.catalogs.value[1].catalog).isEqualTo(feed.catalog)
        assertThat(reread.activeCatalog.value?.id).isEqualTo(kavita.id)
        // A catalog is not an account.
        assertThat(reread.getAccount()).isNull()
        assertThat(reread.get()).isNull()
        reread.notifyUnauthorized()
        assertThat(reread.needsReauth.value).isFalse()
    }

    @Test fun `a catalog's string form never shows its address or password`() {
        val saved = entry("https://kavita.example/api/opds/secret-key", "alice", "hunter2")
        assertThat(saved.toString()).doesNotContain("secret-key")
        assertThat(saved.toString()).doesNotContain("hunter2")
        assertThat(saved.host).isEqualTo("https://kavita.example")
    }

    @Test fun `setCatalog adds the first catalog and then edits the active one`() {
        val store = freshStore()
        store.setCatalog(AccountCredentials.OpdsOnly("https://feed.example/opds", "alice", "one"))
        val first = store.activeCatalog.value!!
        assertThat(first.name).isEqualTo("feed.example")
        store.setCatalog(AccountCredentials.OpdsOnly("https://other.example/opds", "alice", "two"))
        assertThat(store.catalogs.value).hasSize(1)
        assertThat(store.activeCatalog.value?.id).isEqualTo(first.id)
        assertThat(store.activeCatalog.value?.name).isEqualTo("other.example")
        assertThat(store.catalogAccount()?.password).isEqualTo("two")

        store.setCatalog(null)
        assertThat(store.catalogs.value).isEmpty()
    }

    @Test fun `setCatalog never edits a catalog on the account`() {
        val store = freshStore()
        store.saveBearerAccount("https://quire.example", "a@b.c", "tok")
        val native = store.getAccount() as AccountCredentials.Bearer
        val synced = entry("https://books.example/opds").copy(remoteUpdatedAt = "t1")
        store.applyAccountCatalogs(native, "rev") { listOf(synced) }

        store.setCatalog(AccountCredentials.OpdsOnly("https://books.example/opds", "alice", "pw"))
        assertThat(store.catalogs.value.map { it.id }).hasSize(2)
        assertThat(store.catalogs.value.first().catalog?.username).isNull()
        assertThat(store.activeCatalog.value?.onAccount).isFalse()
    }

    @Test fun `catalog validation rejects a blank address, half a login and a bad name`() {
        val store = freshStore()
        for (bad in listOf(
            entry("   "),
            entry("https://feed.example/opds", "alice", null),
            entry("https://feed.example/opds", name = " "),
            entry("https://feed.example/opds", name = "x".repeat(61)),
            entry("https://feed.example/opds").copy(id = "not-a-uuid"),
        )) {
            val failure = runCatching { store.saveCatalog(bad) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(store.catalogs.value).isEmpty()
    }

    @Test fun `switching, reordering and removing catalogs`() {
        val store = freshStore()
        val a = entry("https://a.example/opds")
        val b = entry("https://b.example/opds")
        val c = entry("https://c.example/opds")
        listOf(a, b, c).forEach(store::saveCatalog)
        store.setActiveCatalog(b.id)
        store.setActiveCatalog("unknown")
        assertThat(CalibreCredentialStore(context).activeCatalog.value?.id).isEqualTo(b.id)

        store.reorderCatalogs(listOf(c.id, a.id))
        assertThat(store.catalogs.value.map { it.id }).containsExactly(c.id, a.id, b.id).inOrder()

        store.removeCatalog(b.id)
        assertThat(store.activeCatalog.value?.id).isEqualTo(c.id)
        assertThat(store.catalogSession()?.catalogs).containsExactly(c.catalog, a.catalog).inOrder()
        assertThat(store.catalogSession()?.active).isEqualTo(c.catalog)
    }

    @Test fun `the legacy sign-in's catalog is pinned first and only goes with the sign-in`() {
        val store = freshStore()
        store.saveBasicAccount("https://books.example", "reader", "pw")
        val extra = entry("https://kavita.example/api/opds/KEY")
        store.saveCatalog(extra)
        val pinned = store.catalogs.value.first()
        assertThat(pinned.pinned).isTrue()
        assertThat(pinned.catalog).isEqualTo(
            AccountCredentials.OpdsOnly("https://books.example/opds", "reader", "pw", calibre = true),
        )
        assertThat(store.activeCatalog.value).isEqualTo(pinned)
        assertThat(runCatching { store.saveCatalog(pinned.copy(name = "x")) }.isFailure).isTrue()
        store.removeCatalog(pinned.id)
        store.setCatalog(null)
        assertThat(store.catalogs.value.first()).isEqualTo(pinned)
        // Editing from the old single-catalog form adds a catalog instead.
        store.setCatalog(AccountCredentials.OpdsOnly("https://feed.example/opds"))
        assertThat(store.catalogs.value).hasSize(3)

        // A password change keeps the same row.
        store.saveBasicAccount("https://books.example", "reader", "new")
        assertThat(store.catalogs.value.first().id).isEqualTo(pinned.id)
        assertThat(store.catalogs.value.count { it.id == pinned.id }).isEqualTo(1)
    }

    @Test fun `moving from the legacy sign-in to a Quire login keeps its catalog and books' catalog id`() {
        val store = freshStore()
        store.saveBasicAccount("https://books.example", "reader", "catalog-password")
        val pinnedId = store.catalogs.value.single().id
        store.saveBearerAccount("https://quire.example", "a@b.c", "quire-token", accountId = "native:7")

        val reread = CalibreCredentialStore(context)
        val kept = reread.catalogs.value.single()
        assertThat(kept.id).isEqualTo(pinnedId)
        assertThat(kept.pinned).isFalse()
        assertThat(kept.awaitingUpload).isTrue()
        assertThat(kept.catalog?.calibre).isTrue()
        assertThat(reread.catalogAccount()?.password).isEqualTo("catalog-password")

        reread.notifyUnauthorized(reread.getAccount() as AccountCredentials.Bearer)
        reread.setCatalog(kept.catalog!!.copy(password = "second-password"))
        assertThat(reread.needsReauth.value).isTrue()

        reread.signOut(keepCatalogs = true)
        val after = CalibreCredentialStore(context)
        assertThat(after.getAccount()).isNull()
        assertThat(after.catalogAccount()?.password).isEqualTo("second-password")
        assertThat(reread.needsReauth.value).isFalse()
    }

    @Test fun `signing out keeps catalogs as the device's own or removes them all`() {
        val store = freshStore()
        store.saveBearerAccount("https://quire.example", "a@b.c", "tok")
        val native = store.getAccount() as AccountCredentials.Bearer
        val synced = entry("https://books.example/opds").copy(remoteUpdatedAt = "t1")
        val unreadable = SavedCatalog(SavedCatalog.newId(), "Lost", null, remoteUpdatedAt = "t2", needsLogin = true)
        val deviceOnly = entry("https://home.example/opds").copy(deviceOnly = true)
        store.saveCatalog(deviceOnly)
        assertThat(store.applyAccountCatalogs(native, "rev") { listOf(synced, unreadable) + it }).isTrue()
        assertThat(store.catalogRevision.value).isEqualTo("rev")

        store.signOut(keepCatalogs = true)
        val kept = CalibreCredentialStore(context).catalogs.value
        assertThat(kept.map { it.id }).containsExactly(synced.id, deviceOnly.id).inOrder()
        assertThat(kept.none { it.onAccount || it.deviceOnly || it.needsLogin }).isTrue()
        assertThat(store.catalogRevision.value).isNull()

        store.saveBearerAccount("https://quire.example", "a@b.c", "tok")
        store.signOut(keepCatalogs = false)
        val reread = CalibreCredentialStore(context)
        assertThat(reread.catalogs.value).isEmpty()
        assertThat(reread.getAccount()).isNull()
    }

    @Test fun `another Quire account does not inherit the list's sync state`() {
        val store = freshStore()
        store.saveBearerAccount("https://quire.example", "a@b.c", "tok", accountId = "native:1")
        val first = store.getAccount() as AccountCredentials.Bearer
        store.applyAccountCatalogs(first, "rev") { listOf(entry("https://books.example/opds").copy(remoteUpdatedAt = "t")) }

        // A fresh token for the same reader keeps it.
        store.saveBearerAccount("https://quire.example", "a@b.c", "tok2", accountId = "native:1")
        assertThat(store.catalogRevision.value).isEqualTo("rev")
        assertThat(store.catalogs.value.single().onAccount).isTrue()

        store.saveBearerAccount("https://quire.example", "b@b.c", "tok3", accountId = "native:2")
        assertThat(store.catalogRevision.value).isNull()
        assertThat(store.catalogs.value.single().awaitingUpload).isTrue()
        // A list fetched for the earlier session is not applied.
        assertThat(store.applyAccountCatalogs(first, "late") { emptyList() }).isFalse()
        assertThat(store.catalogs.value).hasSize(1)
    }

    @Test fun `an unreadable catalog list starts empty instead of crashing`() {
        freshStore()
        rawPrefs().edit().putString("catalog_list", "{not json").commit()
        val store = CalibreCredentialStore(context)
        assertThat(store.catalogs.value).isEmpty()
        store.saveCatalog(entry("https://feed.example/opds"))
        assertThat(CalibreCredentialStore(context).catalogs.value).hasSize(1)
    }

    // ---------- migration from the single catalog ----------

    @Test fun `a catalog-only device becomes a device with one catalog and no account`() {
        freshStore()
        rawPrefs().edit()
            .putString("base_url", "https://kavita.example/api/opds/KEY")
            .putString("scheme", "OPDS")
            .putString("username", "alice")
            .putString("password", "hunter2")
            .putBoolean("catalog_calibre", false)
            .commit()

        val store = CalibreCredentialStore(context)
        assertThat(store.getAccount()).isNull()
        val migrated = store.catalogs.value.single()
        assertThat(migrated.catalog).isEqualTo(
            AccountCredentials.OpdsOnly("https://kavita.example/api/opds/KEY", "alice", "hunter2"),
        )
        assertThat(migrated.name).isEqualTo("kavita.example")
        assertThat(migrated.awaitingUpload).isTrue()
        assertThat(store.activeCatalog.value).isEqualTo(migrated)
        val owner = store.legacyCatalogOwner()!!
        assertThat(owner.subject).isEqualTo(AccountCredentials.OpdsOnly("https://kavita.example/api/opds/KEY").subject)
        assertThat(owner.username).isEqualTo("alice")
        assertThat(rawPrefs().all.keys).containsNoneOf("base_url", "scheme", "username", "password", "catalog_calibre")

        // Again on the next start: nothing changes.
        assertThat(CalibreCredentialStore(context).catalogs.value).containsExactly(migrated)
        store.forgetLegacyCatalogOwner()
        assertThat(CalibreCredentialStore(context).legacyCatalogOwner()).isNull()
    }

    @Test fun `a Quire login's catalog joins the list and the login stays`() {
        freshStore()
        rawPrefs().edit()
            .putString("base_url", "https://quire.example")
            .putString("scheme", "BEARER")
            .putString("email", "a@b.c")
            .putString("token", "tok")
            .putString("catalog_url", "https://books.example/opds")
            .putString("catalog_username", "reader")
            .putString("catalog_password", "pw")
            .putBoolean("catalog_calibre", true)
            .commit()

        val store = CalibreCredentialStore(context)
        assertThat((store.getAccount() as AccountCredentials.Bearer).token).isEqualTo("tok")
        assertThat(store.catalogs.value.single().catalog).isEqualTo(
            AccountCredentials.OpdsOnly("https://books.example/opds", "reader", "pw", calibre = true),
        )
        assertThat(store.legacyCatalogOwner()).isNull()
        assertThat(rawPrefs().all.keys.filter { it.startsWith("catalog_") }).containsExactly("catalog_list", "catalog_active")
    }

    @Test fun `a catalog written back by an older version merges by address instead of duplicating`() {
        freshStore()
        rawPrefs().edit().putString("base_url", "https://quire.example").putString("scheme", "BEARER")
            .putString("email", "a@b.c").putString("token", "tok")
            .putString("catalog_url", "https://books.example/opds").commit()
        val first = CalibreCredentialStore(context).catalogs.value.single()
        // A downgrade wrote the single catalog again, with a new login.
        rawPrefs().edit().putString("catalog_url", "https://books.example/opds")
            .putString("catalog_username", "reader").putString("catalog_password", "new").commit()

        val again = CalibreCredentialStore(context).catalogs.value.single()
        assertThat(again.id).isEqualTo(first.id)
        assertThat(again.catalog?.password).isEqualTo("new")
    }

    @Test fun `a legacy Calibre-Web sign-in migrates to its pinned row only`() {
        freshStore()
        rawPrefs().edit().putString("base_url", "https://books.example").putString("username", "reader")
            .putString("password", "pw").commit()

        val store = CalibreCredentialStore(context)
        assertThat(store.getAccount()).isInstanceOf(AccountCredentials.Basic::class.java)
        assertThat(store.catalogs.value.single().pinned).isTrue()
        assertThat(rawPrefs().getString("catalog_list", null)).isNull()
    }

    // ---------- Restored from a backup without its Keystore key ----------

    @Test fun `a store that can't be decrypted starts over signed out instead of crashing`() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // An empty keyset fails Tink's checks, unreadable hex fails its parser: the security and
        // the I/O side of what a restored file encrypted under a lost Keystore key can throw.
        listOf("", "not a keyset").forEach { keyset ->
            CalibreCredentialStore(ctx).saveBasicAccount("https://lib.example", "alice", "s3cret")
            ctx.getSharedPreferences("calibre_creds", android.content.Context.MODE_PRIVATE).edit()
                .putString("__androidx_security_crypto_encrypted_prefs_key_keyset__", keyset)
                .commit()

            val store = CalibreCredentialStore(ctx)
            assertThat(store.getAccount()).isNull()
            // And it works again, across a reload.
            store.saveBasicAccount("https://lib.example", "alice", "s3cret")
            assertThat(CalibreCredentialStore(ctx).getAccount()).isInstanceOf(AccountCredentials.Basic::class.java)
        }
    }

    @Test fun `login keeps pending ownership transfer until migration is completed`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = CalibreCredentialStore(context)
        store.clear()
        store.saveBearerAccount("https://quire.example", "a@b.c", "quire-token", previousHighlightScope = "abc")
        val reread = CalibreCredentialStore(context)
        val native = reread.getAccount() as AccountCredentials.Bearer
        assertThat(native.previousHighlightScopes).containsExactly("abc")
        assertThat(reread.catalogAccount()).isNull()
        reread.completeHighlightMigration(native, listOf("abc"))
        assertThat((CalibreCredentialStore(context).getAccount() as AccountCredentials.Bearer).previousHighlightScopes).isEmpty()
    }
}
