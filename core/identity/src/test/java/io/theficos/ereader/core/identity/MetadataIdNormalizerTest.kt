package io.theficos.ereader.core.identity

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Test

class MetadataIdNormalizerTest {
    @Serializable private data class Case(val `in`: String, val out: String?)
    @Serializable private data class Fixtures(val cases: List<Case>)

    private val fixtures: Fixtures by lazy {
        val text = javaClass.getResource("/identity/fixtures.json")!!.readText()
        Json.decodeFromString(Fixtures.serializer(), text)
    }

    @Test fun `matches every spec fixture`() {
        for (case in fixtures.cases) {
            assertWithMessage("input=${case.`in`}")
                .that(normalizeMetadataId(case.`in`))
                .isEqualTo(case.out)
        }
    }

    @Test fun `null input returns null`() {
        assertThat(normalizeMetadataId(null)).isNull()
    }
}
