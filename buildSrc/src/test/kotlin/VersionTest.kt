import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.Assert.assertThrows

class VersionTest {

    @Test
    fun exactTag_parsesNameAndCode() {
        val result = Version.fromGitDescribe("v2026.05.08.29")
        assertThat(result.name).isEqualTo("2026.05.08.29")
        assertThat(result.code).isEqualTo(26050829)
    }

    @Test
    fun exactTag_yearEndRollover() {
        val result = Version.fromGitDescribe("v2026.12.31.99")
        assertThat(result.name).isEqualTo("2026.12.31.99")
        assertThat(result.code).isEqualTo(26123199)
    }

    @Test
    fun exactTag_runOver99_wrapsModulo() {
        // Run-number byte is mod-100 to fit in Int safely; documented in spec.
        val result = Version.fromGitDescribe("v2026.05.08.103")
        assertThat(result.name).isEqualTo("2026.05.08.103")
        assertThat(result.code).isEqualTo(26050803)
    }

    @Test
    fun postTag_appendsDevSuffix_keepsBaseVersionCode() {
        val result = Version.fromGitDescribe("v2026.05.08.29-3-gabcdef0")
        assertThat(result.name).isEqualTo("2026.05.08.29.dev3+gabcdef0")
        assertThat(result.code).isEqualTo(26050829)
    }

    @Test
    fun postTag_trimsLeadingTrailingWhitespace() {
        val result = Version.fromGitDescribe("  v2026.05.08.29-1-gdeadbee\n")
        assertThat(result.name).isEqualTo("2026.05.08.29.dev1+gdeadbee")
    }

    @Test
    fun bareSha_withFallback_treatsFallbackAsExactTag() {
        val result = Version.fromGitDescribe("abcdef0", fallback = "2026.05.08.29")
        assertThat(result.name).isEqualTo("2026.05.08.29")
        assertThat(result.code).isEqualTo(26050829)
    }

    @Test
    fun bareSha_noFallback_throwsWithGuidance() {
        val ex = assertThrows(IllegalStateException::class.java) {
            Version.fromGitDescribe("abcdef0")
        }
        assertThat(ex).hasMessageThat().contains("QUIRE_VERSION_FALLBACK")
    }

    @Test
    fun emptyOutput_noFallback_throws() {
        assertThrows(IllegalStateException::class.java) {
            Version.fromGitDescribe("")
        }
    }
}
