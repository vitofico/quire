package io.theficos.ereader.core.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

class DocumentIdentityTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test fun `one identity hint is sufficient and an empty payload is rejected`() {
        val cases: List<Pair<DocumentIdentity, List<String?>>> = listOf(
            DocumentIdentity(contentHash = "abc123") to listOf(null, "abc123", null, null, null, null),
            DocumentIdentity(metadataId = "42", contentHash = "abc123") to listOf("42", "abc123", null, null, null, null),
            DocumentIdentity(opdsHref = "opds-href:deadbeef") to listOf(null, null, "opds-href:deadbeef", null, null, null),
            DocumentIdentity(opdsDcId = "urn:uuid:abc") to listOf(null, null, null, "urn:uuid:abc", null, null),
            DocumentIdentity(calibreBookId = "42") to listOf(null, null, null, null, "42", null),
            DocumentIdentity(isbn = "9780553293357") to listOf(null, null, null, null, null, "9780553293357"),
        )
        for ((identity, fields) in cases) {
            assertThat(listOf(
                identity.metadataId, identity.contentHash, identity.opdsHref,
                identity.opdsDcId, identity.calibreBookId, identity.isbn,
            )).isEqualTo(fields)
        }
        assertThrows(IllegalArgumentException::class.java) { DocumentIdentity() }
    }

    // ---------- Phase-0 / F-2: identity hash version ----------

    @Test fun `identityHashVersion defaults to current version`() {
        val id = DocumentIdentity(contentHash = "abc")
        assertThat(id.identityHashVersion).isEqualTo(CURRENT_IDENTITY_HASH_VERSION)
        assertThat(CURRENT_IDENTITY_HASH_VERSION).isEqualTo(1)
    }

    @Test fun `isStaleHashVersion is no-op at current version`() {
        assertThat(isStaleHashVersion(1)).isFalse()
        // A future v2 row decoded by a v1 client is NOT stale (not the
        // direction the predicate cares about).
        assertThat(isStaleHashVersion(2)).isFalse()
    }

    @Test fun `isStaleHashVersion detects pre-current versions`() {
        // Hypothetical bump to v2: a v1 row is now stale and needs a rehash.
        // Asserted via the underlying inequality so the test is robust against
        // a future constant change.
        assertThat(0 < CURRENT_IDENTITY_HASH_VERSION).isTrue()
        assertThat(isStaleHashVersion(0)).isTrue()
    }

    @Test fun `identity_hash_version round-trips on the wire`() {
        val id = DocumentIdentity(contentHash = "abc", identityHashVersion = 7)
        val encoded = json.encodeToString(DocumentIdentity.serializer(), id)
        assertThat(encoded).contains("\"identity_hash_version\":7")
        val decoded = json.decodeFromString(DocumentIdentity.serializer(), encoded)
        assertThat(decoded.identityHashVersion).isEqualTo(7)
        assertThat(decoded.contentHash).isEqualTo("abc")
    }

    @Test fun `missing identity_hash_version decodes to 1 for back-compat`() {
        // An older server that doesn't yet emit the field (pre-F-1) must
        // still decode cleanly. Every hash on the wire today is v1.
        val legacyWire = """{"content_hash":"abc"}"""
        val decoded = json.decodeFromString(DocumentIdentity.serializer(), legacyWire)
        assertThat(decoded.identityHashVersion).isEqualTo(1)
        assertThat(decoded.contentHash).isEqualTo("abc")
    }
}
