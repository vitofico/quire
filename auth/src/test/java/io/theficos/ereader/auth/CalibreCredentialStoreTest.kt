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
}
