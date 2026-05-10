package io.theficos.ereader.ui.catalog

import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Mirror of `auth`'s `FakeAndroidKeyStore` test helper. We copy it here because
 * Robolectric tests in this module exercise [io.theficos.ereader.auth.CalibreCredentialStore]
 * (via [io.theficos.ereader.ui.catalog.CatalogViewModel])—which requires an
 * "AndroidKeyStore" provider—and the auth module's test sources aren't visible.
 */
internal object FakeAndroidKeyStore {

    @Volatile private var installed = false

    fun setup() {
        if (installed) return
        synchronized(this) {
            if (installed) return
            Security.removeProvider("AndroidKeyStore")
            Security.insertProviderAt(FakeProvider(), 1)
            installed = true
        }
    }

    private class FakeProvider : Provider(
        "AndroidKeyStore", 1.0,
        "In-memory AndroidKeyStore for Robolectric tests"
    ) {
        init {
            putService(Service(this, "KeyStore", "AndroidKeyStore",
                FakeKeyStoreSpi::class.java.name, emptyList(), emptyMap()))
            putService(Service(this, "KeyGenerator", "AES",
                FakeAesKeyGeneratorSpi::class.java.name, emptyList(), emptyMap()))
        }
    }

    class FakeKeyStoreSpi : KeyStoreSpi() {
        private data class Entry(val key: Key, val certs: Array<Certificate>?)
        private val store = ConcurrentHashMap<String, Entry>()
        override fun engineGetKey(alias: String, password: CharArray?): Key? = store[alias]?.key
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = store[alias]?.certs
        override fun engineGetCertificate(alias: String): Certificate? = store[alias]?.certs?.firstOrNull()
        override fun engineGetCreationDate(alias: String): Date = Date()
        override fun engineSetKeyEntry(
            alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?
        ) { store[alias] = Entry(key, chain?.let { @Suppress("UNCHECKED_CAST") (it as Array<Certificate>) }) }
        override fun engineSetKeyEntry(
            alias: String, key: ByteArray, chain: Array<out Certificate>?
        ) = throw UnsupportedOperationException("raw key bytes not supported")
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) {
            store[alias] = Entry(cert.publicKey, arrayOf(cert))
        }
        override fun engineDeleteEntry(alias: String) { store.remove(alias) }
        override fun engineAliases(): Enumeration<String> = java.util.Collections.enumeration(store.keys)
        override fun engineContainsAlias(alias: String): Boolean = store.containsKey(alias)
        override fun engineSize(): Int = store.size
        override fun engineIsKeyEntry(alias: String): Boolean = store[alias]?.key != null
        override fun engineIsCertificateEntry(alias: String): Boolean = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: java.io.OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: java.io.InputStream?, password: CharArray?) = Unit
        override fun engineLoad(param: KeyStore.LoadStoreParameter?) = Unit
    }

    class FakeAesKeyGeneratorSpi : KeyGeneratorSpi() {
        private var keySize = 256
        private var random: SecureRandom = SecureRandom()
        override fun engineInit(random: SecureRandom) { this.random = random }
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            if (random != null) this.random = random
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) {
            this.keySize = keysize
            if (random != null) this.random = random
        }
        override fun engineGenerateKey(): SecretKey {
            val bytes = ByteArray(keySize / 8)
            random.nextBytes(bytes)
            return SecretKeySpec(bytes, "AES")
        }
    }
}
