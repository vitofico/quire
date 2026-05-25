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
}
