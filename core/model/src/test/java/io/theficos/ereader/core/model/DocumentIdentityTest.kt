package io.theficos.ereader.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DocumentIdentityTest {
    @Test fun `requires content_hash`() {
        val id = DocumentIdentity(metadataId = null, contentHash = "abc123")
        assertThat(id.contentHash).isEqualTo("abc123")
        assertThat(id.metadataId).isNull()
    }

    @Test fun `accepts both ids`() {
        val id = DocumentIdentity(metadataId = "42", contentHash = "abc123")
        assertThat(id.metadataId).isEqualTo("42")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects empty content_hash`() {
        DocumentIdentity(metadataId = "42", contentHash = "")
    }
}
