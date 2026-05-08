data class VersionInfo(val name: String, val code: Int)

object Version {

    private val EXACT = Regex("""^v(\d{4})\.(\d{2})\.(\d{2})\.(\d+)$""")
    private val POST_TAG = Regex("""^v(\d{4})\.(\d{2})\.(\d{2})\.(\d+)-(\d+)-(g[0-9a-f]+)$""")

    fun fromGitDescribe(output: String, fallback: String? = null): VersionInfo {
        val trimmed = output.trim()

        EXACT.matchEntire(trimmed)?.let { m ->
            val (yyyy, mm, dd, run) = m.destructured
            return VersionInfo(
                name = "$yyyy.$mm.$dd.$run",
                code = computeCode(yyyy, mm, dd, run)
            )
        }

        POST_TAG.matchEntire(trimmed)?.let { m ->
            val (yyyy, mm, dd, run, dist, sha) = m.destructured
            return VersionInfo(
                name = "$yyyy.$mm.$dd.$run.dev$dist+$sha",
                code = computeCode(yyyy, mm, dd, run)
            )
        }

        if (fallback != null) {
            return fromGitDescribe("v$fallback")
        }

        error(
            "Could not derive version from git describe output: '$trimmed'. " +
                "Set QUIRE_VERSION_FALLBACK env var to a tag name like '2026.05.08.29' " +
                "(without leading 'v'), or build from a checkout that has at least one " +
                "matching tag in history."
        )
    }

    private fun computeCode(yyyy: String, mm: String, dd: String, run: String): Int {
        val yyMMdd = (yyyy.takeLast(2) + mm + dd).toInt()
        return yyMMdd * 100 + (run.toInt() % 100)
    }
}
