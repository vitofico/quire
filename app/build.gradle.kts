plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.aboutlibraries)
}

// Static version. Source of truth: gradle.properties (VERSION_NAME, VERSION_CODE).
// CI bumps these on every push to main; local devs see the most recent release.
val appVersionName: String = project.property("VERSION_NAME") as String
val appVersionCode: Int = (project.property("VERSION_CODE") as String).toInt()

// AboutLibraries' build-time collector is not reproducible: it scans whatever
// dependency configurations happen to be resolvable in a given build, so the
// generated license JSON (res/raw/aboutlibraries.json, obfuscated to res/M7.json
// by resource shrinking) varies by environment — debug/test artifacts leak in
// locally while F-Droid's isolated builder under-collects. That made F-Droid's
// reproducible-build verification fail on the res/M7.json contents.
//
// Fix: don't generate the resource at build time. We check in a pre-generated,
// release-only JSON and package that fixed file, so every builder (dev, CI,
// F-Droid) embeds byte-identical bytes. `LibrariesContainer` in LicensesScreen
// reads R.raw.aboutlibraries, which resolves to the committed file.
//
// Regenerate after changing dependencies (CI enforces this — see the
// "AboutLibraries JSON drift check" step in android-ci.yaml). exportPath is
// resolved relative to this module, so `src/main/res/raw` lands the file at
// app/src/main/res/raw/aboutlibraries.json:
//   scripts/dgradle :app:exportLibraryDefinitions \
//     -PaboutLibraries.exportVariant=release \
//     -PaboutLibraries.exportPath=src/main/res/raw
//
// `excludeFields=["generated"]` drops the build timestamp (default in
// AboutLibraries 14+; explicit on 11.x); `filterVariants` keeps the
// regenerated file release-only.
aboutLibraries {
    excludeFields = arrayOf("generated")
    filterVariants = arrayOf("release")
    registerAndroidTasks = false
}

android {
    namespace = "io.theficos.quire"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.theficos.quire"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
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
    // Don't embed dependency-metadata in the APK / bundle. AGP 8.x writes
    // this into the v3 signing block; F-Droid's `check apk` rejects it as
    // a privacy leak (it exposes the build's dep graph to scrapers).
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
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
        debug {
            isMinifyEnabled = false
            // Distinct package + label so a debug build installs side-by-side
            // with a signed release "Quire" instead of failing on signature
            // mismatch. Debug-only; release is unaffected.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
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
    implementation(project(":data:library"))
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
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
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

    // Compose UI test harness — runs screen-level tests under Robolectric so
    // `scripts/dgradle :app:testDebugUnitTest` can exercise composables
    // without an emulator. `ui-test-manifest` ships the ComponentActivity
    // stub the harness needs to host content; it has to be on the unit-test
    // classpath alongside `ui-test-junit4`.
    //
    // Tests that depend on these live in `src/testDebug/` so they only build
    // and run against the debug variant — keeps `:app:testReleaseUnitTest`
    // (which CI runs alongside the debug one) from trying to compile against
    // `debugImplementation`-scoped deps and failing.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
