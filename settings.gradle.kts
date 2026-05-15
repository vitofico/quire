pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
    }
}

rootProject.name = "quire"

include(
    ":app",
    ":core:model",
    ":core:identity",
    ":core:metadata",
    ":data:local",
    ":data:opds",
    ":data:sync",
    ":reader",
    ":auth",
)
