import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// CalVer: versionName = YYYY.MM.DD[.<run>], versionCode = YYMMDD*100 + run%100.
// BUILD_DATE (YYYY-MM-DD) and GITHUB_RUN_NUMBER are set by CI; local builds fall
// back to today's date and run 0.
val buildDate: LocalDate =
    System.getenv("BUILD_DATE")?.takeIf { it.isNotBlank() }
        ?.let(LocalDate::parse)
        ?: LocalDate.now()
val buildRun: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
val calverName: String = buildString {
    append(buildDate.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")))
    if (buildRun > 0) append(".$buildRun")
}
val calverCode: Int =
    buildDate.format(DateTimeFormatter.ofPattern("yyMMdd")).toInt() * 100 +
        (buildRun % 100)

android {
    namespace = "io.theficos.quire"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.theficos.quire"
        minSdk = 26
        targetSdk = 34
        versionCode = calverCode
        versionName = calverName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = false   // Phase 1 only; revisit before publishing
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:identity"))
    implementation(project(":data:local"))
    implementation(project(":data:opds"))
    implementation(project(":auth"))
    implementation(project(":data:sync"))
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
    debugImplementation(libs.compose.ui.tooling)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")
}
