package io.theficos.ereader.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DocumentIdentityTest {
    @Test fun `accepts contentHash-only`() {
        val id = DocumentIdentity(metadataId = null, contentHash = "abc123")
        assertThat(id.contentHash).isEqualTo("abc123")
        assertThat(id.metadataId).isNull()
    }

    @Test fun `accepts metadataId plus contentHash`() {
        val id = DocumentIdentity(metadataId = "42", contentHash = "abc123")
        assertThat(id.metadataId).isEqualTo("42")
        assertThat(id.contentHash).isEqualTo("abc123")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects all-null payload`() {
        DocumentIdentity()
    }

    @Test fun `accepts opdsHref-only alias payload`() {
        val id = DocumentIdentity(opdsHref = "opds-href:deadbeef")
        assertThat(id.opdsHref).isEqualTo("opds-href:deadbeef")
        assertThat(id.contentHash).isNull()
    }

    @Test fun `accepts opdsDcId-only alias payload`() {
        val id = DocumentIdentity(opdsDcId = "urn:uuid:abc")
        assertThat(id.opdsDcId).isEqualTo("urn:uuid:abc")
    }

    @Test fun `accepts calibreBookId-only alias payload`() {
        val id = DocumentIdentity(calibreBookId = "42")
        assertThat(id.calibreBookId).isEqualTo("42")
    }

    @Test fun `accepts isbn-only alias payload`() {
        val id = DocumentIdentity(isbn = "9780553293357")
        assertThat(id.isbn).isEqualTo("9780553293357")
    }
}
