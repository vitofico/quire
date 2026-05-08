data class VersionInfo(val name: String, val code: Int)

object Version {
    fun fromGitDescribe(output: String, fallback: String? = null): VersionInfo {
        TODO("Task 2 implements this")
    }
}
