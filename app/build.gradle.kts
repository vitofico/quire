plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries)
}

// Tag-driven CalVer. The build reads `git describe --tags --match 'v*'`
// and parses it via buildSrc/Version.kt. Set QUIRE_VERSION_FALLBACK
// (e.g. "2026.05.08.29") if building from a shallow clone with no tags.
fun gitDescribe(): String =
    try {
        val process = ProcessBuilder("git", "describe", "--tags", "--match", "v*", "--always")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        if (process.waitFor() == 0) output else ""
    } catch (_: Exception) {
        ""
    }

val versionInfo = Version.fromGitDescribe(
    output = gitDescribe(),
    fallback = System.getenv("QUIRE_VERSION_FALLBACK")
)

// Drop the build timestamp from the AboutLibraries-generated license JSON
// so the resource is byte-identical across rebuilds (matters for F-Droid's
// reproducible-build verification). Disabled by default in AboutLibraries
// 14+; explicit on 11.x.
aboutLibraries {
    excludeFields = arrayOf("generated")
}

android {
    namespace = "io.theficos.quire"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.theficos.quire"
        minSdk = 26
        targetSdk = 34
        versionCode = versionInfo.code
        versionName = versionInfo.name
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "21" }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    signingConfigs {
        create("release") {
            val storePath = System.getenv("QUIRE_RELEASE_KEYSTORE")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("QUIRE_RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("QUIRE_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("QUIRE_RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false   // Phase 1 only; revisit before publishing
            // AGP 8.3+ embeds git origin/branch/SHA into the APK by default,
            // which makes F-Droid reproducible builds fail (their checkout's
            // origin URL differs from ours). The tag itself already pins the
            // commit, so we don't lose useful info by disabling this.
            vcsInfo.include = false
            signingConfig =
                if (System.getenv("QUIRE_RELEASE_KEYSTORE").isNullOrBlank())
                    signingConfigs.getByName("debug")
                else
                    signingConfigs.getByName("release")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:identity"))
    implementation(project(":core:metadata"))
    implementation(project(":data:local"))
    implementation(project(":data:opds"))
    implementation(project(":auth"))
    implementation(project(":data:sync"))
    implementation(project(":data:ai"))
    implementation(project(":reader"))
    implementation(libs.work.runtime.ktx)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.ktx)
    implementation("androidx.fragment:fragment-ktx:1.8.4")
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.coil.compose)
    implementation(libs.aboutlibraries.compose)
    debugImplementation(libs.compose.ui.tooling)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
}
