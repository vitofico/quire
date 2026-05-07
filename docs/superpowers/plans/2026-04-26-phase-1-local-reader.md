# Phase 1 — Local Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a native Android app that browses a calibre-web OPDS catalog over HTTP Basic, downloads EPUBs, renders them via Readium, and persists per-document reading progress locally in Room. No server-side sync.

**Architecture:** Multi-module Gradle project (Kotlin + Compose). UI in `:app`. Domain types in `:core:model`. Pure identity helpers (content hash + metadata-id normalization) in `:core:identity` so Phase 2 can reuse them. Local persistence in `:data:local` (Room). OPDS HTTP in `:data:opds` (OkHttp + Readium OPDS parser). Reader integration in `:reader`. Keystore-backed Basic creds in `:auth`. State flows: ViewModel → repository → DAO/HTTP. Single source of truth for the library is the Room `documents` table; the OPDS catalog is rendered live and never cached on disk.

**Tech Stack:** Kotlin 2.0, Android Gradle Plugin 8.5, Jetpack Compose (BOM 2024.09), Compose Navigation, Room 2.6, OkHttp 4.12, kotlinx.serialization 1.7, kotlinx.coroutines 1.9, Readium Kotlin Toolkit 3.0 (`shared`, `streamer`, `navigator`, `opds`), AndroidX Security Crypto 1.1 (EncryptedSharedPreferences over Keystore), JUnit 4 + Robolectric 4.13 + Turbine 1.1 + MockWebServer for unit tests, AndroidX Test + Espresso for instrumented tests.

**Package root:** `io.theficos.ereader`

**Ship gate (re-stated from spec §4):** Read a book end-to-end; progress survives app restart.

---

## File structure

Top-level:

- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `local.properties.template`
- Create: `.gitignore`

Per module (each has its own `build.gradle.kts` + `src/main/AndroidManifest.xml` where needed):

```
:app                        Compose UI, navigation, DI wiring
:core:model                 Document, Progress, DocumentIdentity
:core:identity              metadataIdNormalize(), contentHash(File), extractIdentityFromEpub(File)
:data:local                 Room DB (DocumentEntity, ProgressEntity, DAOs), DocumentRepository, ProgressRepository
:data:opds                  OpdsClient, BookDownloader, BasicAuthInterceptor
:reader                     EpubReader, ProgressTracker (wraps Readium navigator)
:auth                       CalibreCredentialStore (EncryptedSharedPreferences)
```

---

## Task 1: Project skeleton — Gradle, version catalog, modules

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `local.properties.template`
- Create: `.gitignore`

- [ ] **Step 1: Write `.gitignore`**

```
.gradle/
build/
.idea/
*.iml
local.properties
.kotlin/
captures/
.cxx/
```

- [ ] **Step 2: Write `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official
```

- [ ] **Step 3: Write `gradle/wrapper/gradle-wrapper.properties`**

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

Run: `gradle wrapper --gradle-version 8.10.2` (if `gradle` not installed on the host, download the wrapper jar from `https://github.com/gradle/gradle/raw/v8.10.2/gradle/wrapper/gradle-wrapper.jar` into `gradle/wrapper/gradle-wrapper.jar` and commit). Also commit `gradlew` and `gradlew.bat` from a fresh `gradle wrapper` invocation. If `gradle` is available, prefer running it.

- [ ] **Step 4: Write `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.5.2"
kotlin = "2.0.20"
ksp = "2.0.20-1.0.25"
compose-bom = "2024.09.02"
compose-compiler = "1.5.15"
nav-compose = "2.8.1"
lifecycle = "2.8.6"
activity-compose = "1.9.2"
coroutines = "1.9.0"
serialization = "1.7.2"
okhttp = "4.12.0"
room = "2.6.1"
work = "2.9.1"
security-crypto = "1.1.0-alpha06"
readium = "3.0.0"
junit = "4.13.2"
robolectric = "4.13"
turbine = "1.1.0"
androidx-test-core = "1.6.1"
androidx-test-runner = "1.6.2"
androidx-test-rules = "1.6.1"
androidx-test-junit = "1.2.1"
truth = "1.4.4"
espresso = "3.6.1"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version = "1.13.1" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activity-compose" }
androidx-lifecycle-runtime-ktx = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "nav-compose" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "compose-bom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons-extended = { module = "androidx.compose.material:material-icons-extended" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
work-runtime-ktx = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
androidx-security-crypto = { module = "androidx.security:security-crypto", version.ref = "security-crypto" }
readium-shared = { module = "org.readium.kotlin-toolkit:readium-shared", version.ref = "readium" }
readium-streamer = { module = "org.readium.kotlin-toolkit:readium-streamer", version.ref = "readium" }
readium-navigator = { module = "org.readium.kotlin-toolkit:readium-navigator", version.ref = "readium" }
readium-opds = { module = "org.readium.kotlin-toolkit:readium-opds", version.ref = "readium" }
junit = { module = "junit:junit", version.ref = "junit" }
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }
androidx-test-core = { module = "androidx.test:core", version.ref = "androidx-test-core" }
androidx-test-runner = { module = "androidx.test:runner", version.ref = "androidx-test-runner" }
androidx-test-rules = { module = "androidx.test:rules", version.ref = "androidx-test-rules" }
androidx-test-junit = { module = "androidx.test.ext:junit", version.ref = "androidx-test-junit" }
espresso-core = { module = "androidx.test.espresso:espresso-core", version.ref = "espresso" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 5: Write `settings.gradle.kts`**

```kotlin
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

rootProject.name = "opds-ereader"

include(
    ":app",
    ":core:model",
    ":core:identity",
    ":data:local",
    ":data:opds",
    ":reader",
    ":auth",
)
```

- [ ] **Step 6: Write root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 7: Write `local.properties.template`**

```
# Copy to local.properties and fill in. Not committed.
sdk.dir=/Users/<you>/Library/Android/sdk
calibreweb.baseUrl=https://library.example.com
calibreweb.username=android-reader
calibreweb.password=replace-me
```

- [ ] **Step 8: Verify Gradle resolves**

Run: `./gradlew help`
Expected: BUILD SUCCESSFUL. (Modules don't exist yet — Gradle will fail to find their build files. Skip until Task 2 lands their `build.gradle.kts` stubs. To make this step pass standalone, add empty `build.gradle.kts` stubs in each module directory now: `app/build.gradle.kts`, `core/model/build.gradle.kts`, `core/identity/build.gradle.kts`, `data/local/build.gradle.kts`, `data/opds/build.gradle.kts`, `reader/build.gradle.kts`, `auth/build.gradle.kts`, each containing only `// stub`.)

- [ ] **Step 9: Commit**

```bash
git add .gitignore settings.gradle.kts build.gradle.kts gradle.properties local.properties.template gradle/ gradlew gradlew.bat app core data reader auth
git commit -m "build: gradle skeleton, version catalog, module layout"
```

---

## Task 2: `:core:model` — domain types

**Files:**
- Create: `core/model/build.gradle.kts`
- Create: `core/model/src/main/java/io/theficos/ereader/core/model/DocumentIdentity.kt`
- Create: `core/model/src/main/java/io/theficos/ereader/core/model/Document.kt`
- Create: `core/model/src/main/java/io/theficos/ereader/core/model/Progress.kt`
- Test: `core/model/src/test/java/io/theficos/ereader/core/model/DocumentIdentityTest.kt`

- [ ] **Step 1: Replace `core/model/build.gradle.kts` stub**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
```

- [ ] **Step 2: Write the failing test**

`core/model/src/test/java/io/theficos/ereader/core/model/DocumentIdentityTest.kt`:

```kotlin
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
```

- [ ] **Step 3: Run the test (should fail to compile)**

Run: `./gradlew :core:model:test`
Expected: FAIL — `DocumentIdentity` unresolved.

- [ ] **Step 4: Implement `DocumentIdentity`, `Document`, `Progress`**

`core/model/src/main/java/io/theficos/ereader/core/model/DocumentIdentity.kt`:

```kotlin
package io.theficos.ereader.core.model

data class DocumentIdentity(
    val metadataId: String?,
    val contentHash: String,
) {
    init {
        require(contentHash.isNotEmpty()) { "contentHash must not be empty" }
    }
}
```

`core/model/src/main/java/io/theficos/ereader/core/model/Document.kt`:

```kotlin
package io.theficos.ereader.core.model

data class Document(
    val id: Long,
    val identity: DocumentIdentity,
    val title: String,
    val author: String?,
    val downloadUrl: String,
    val localPath: String,
    val downloadedAt: Long,
)
```

`core/model/src/main/java/io/theficos/ereader/core/model/Progress.kt`:

```kotlin
package io.theficos.ereader.core.model

data class Progress(
    val documentId: Long,
    val locator: String,
    val percent: Double,
    val updatedAt: Long,
) {
    init {
        require(percent in 0.0..1.0) { "percent must be in [0,1]" }
    }
}
```

- [ ] **Step 5: Run the test**

Run: `./gradlew :core:model:test`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add core/model
git commit -m "feat(core-model): document, progress, document-identity types"
```

---

## Task 3: `:core:identity` — metadata-id normalization + content hash

**Files:**
- Create: `core/identity/build.gradle.kts`
- Create: `core/identity/src/main/java/io/theficos/ereader/core/identity/MetadataIdNormalizer.kt`
- Create: `core/identity/src/main/java/io/theficos/ereader/core/identity/ContentHash.kt`
- Create: `core/identity/src/main/java/io/theficos/ereader/core/identity/EpubIdentityExtractor.kt`
- Test: `core/identity/src/test/java/io/theficos/ereader/core/identity/MetadataIdNormalizerTest.kt`
- Test: `core/identity/src/test/java/io/theficos/ereader/core/identity/ContentHashTest.kt`
- Test: `core/identity/src/test/java/io/theficos/ereader/core/identity/EpubIdentityExtractorTest.kt`
- Create: `core/identity/src/test/resources/identity/fixtures.json`
- Create: `core/identity/src/test/resources/identity/sample.opf`

- [ ] **Step 1: Replace `core/identity/build.gradle.kts` stub**

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core:model"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
```

- [ ] **Step 2: Write fixture file**

`core/identity/src/test/resources/identity/fixtures.json`:

```json
{
  "cases": [
    { "in": "urn:uuid:550E8400-E29B-41D4-A716-446655440000", "out": "550e8400e29b41d4a716446655440000" },
    { "in": "ISBN: 978-0-14-103614-4", "out": "9780141036144" },
    { "in": "calibre:42", "out": "42" },
    { "in": "  isbn: 978-0-14-103614-4  ", "out": "9780141036144" },
    { "in": "URN:UUID:550e8400-e29b-41d4-a716-446655440000", "out": "550e8400e29b41d4a716446655440000" },
    { "in": "MOBI-ASIN:B000FC0SIM", "out": "b000fc0sim" },
    { "in": "", "out": null },
    { "in": "   ", "out": null },
    { "in": "urn:isbn:", "out": null },
    { "in": "uuid:--- ---", "out": null }
  ]
}
```

- [ ] **Step 3: Write the failing normalizer test**

`core/identity/src/test/java/io/theficos/ereader/core/identity/MetadataIdNormalizerTest.kt`:

```kotlin
package io.theficos.ereader.core.identity

import com.google.common.truth.Truth.assertThat
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
            assertThat(normalizeMetadataId(case.`in`))
                .named("input=${case.`in`}")
                .isEqualTo(case.out)
        }
    }

    @Test fun `null input returns null`() {
        assertThat(normalizeMetadataId(null)).isNull()
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :core:identity:test`
Expected: FAIL — `normalizeMetadataId` unresolved.

- [ ] **Step 5: Implement `MetadataIdNormalizer.kt`**

```kotlin
package io.theficos.ereader.core.identity

private val SCHEME_PREFIXES = listOf("isbn:", "uuid:", "calibre:", "mobi-asin:", "asin:", "doi:", "url:")
private val WHITESPACE_AND_HYPHEN = Regex("[\\s-]")

fun normalizeMetadataId(raw: String?): String? {
    if (raw == null) return null
    var s = raw.trim().lowercase()
    if (s.isEmpty()) return null
    if (s.startsWith("urn:")) s = s.removePrefix("urn:")
    for (p in SCHEME_PREFIXES) {
        if (s.startsWith(p)) { s = s.removePrefix(p); break }
    }
    s = s.replace(WHITESPACE_AND_HYPHEN, "")
    return s.ifEmpty { null }
}
```

- [ ] **Step 6: Run the test**

Run: `./gradlew :core:identity:test --tests *MetadataIdNormalizerTest*`
Expected: PASS.

- [ ] **Step 7: Write the failing content-hash test**

`core/identity/src/test/java/io/theficos/ereader/core/identity/ContentHashTest.kt`:

```kotlin
package io.theficos.ereader.core.identity

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ContentHashTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `matches reference KOReader-style sampled MD5`() {
        val file = tmp.newFile("book.epub")
        val bytes = ByteArray(2 * 1024 * 1024) { (it % 251).toByte() }
        file.writeBytes(bytes)

        val expected = referenceHash(bytes)
        assertThat(contentHash(file)).isEqualTo(expected)
    }

    @Test fun `tiny file is hashed in full when step is 1`() {
        val file = tmp.newFile("tiny.epub")
        val bytes = ByteArray(64) { it.toByte() }
        file.writeBytes(bytes)

        // For a 64-byte file: step = max(1, 64/1024) = 1.
        // Loop reads (i*1, 64) for i in 0..1023. Each iteration after i=0 returns 0 bytes since EOF.
        val expectedBuf = bytes.copyOfRange(0, 64)
        val md = MessageDigest.getInstance("MD5")
        md.update(expectedBuf)
        val expected = md.digest().joinToString("") { "%02x".format(it) }

        assertThat(contentHash(file)).isEqualTo(expected)
    }

    private fun referenceHash(bytes: ByteArray): String {
        val size = bytes.size.toLong()
        val step = maxOf(1L, size / 1024L)
        val buf = ByteArray((1024 * 64).coerceAtMost(Int.MAX_VALUE))
        var len = 0
        for (i in 0 until 1024) {
            val offset = (i * step).toInt()
            if (offset >= bytes.size) break
            val n = minOf(64, bytes.size - offset)
            System.arraycopy(bytes, offset, buf, len, n)
            len += n
        }
        val md = MessageDigest.getInstance("MD5")
        md.update(buf, 0, len)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
```

- [ ] **Step 8: Run the test**

Run: `./gradlew :core:identity:test --tests *ContentHashTest*`
Expected: FAIL — `contentHash` unresolved.

- [ ] **Step 9: Implement `ContentHash.kt`**

```kotlin
package io.theficos.ereader.core.identity

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

fun contentHash(file: File): String {
    require(file.isFile) { "Not a regular file: $file" }
    val size = file.length()
    val step = maxOf(1L, size / 1024L)
    val md = MessageDigest.getInstance("MD5")
    val chunk = ByteArray(64)
    RandomAccessFile(file, "r").use { raf ->
        for (i in 0 until 1024) {
            val offset = i * step
            if (offset >= size) break
            raf.seek(offset)
            val n = raf.read(chunk, 0, 64)
            if (n > 0) md.update(chunk, 0, n)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}
```

- [ ] **Step 10: Run the test**

Run: `./gradlew :core:identity:test`
Expected: PASS (all tests).

- [ ] **Step 11: Write the failing OPF identifier extractor test**

`core/identity/src/test/resources/identity/sample.opf`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="pub-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:identifier id="pub-id" opf:scheme="calibre">42</dc:identifier>
    <dc:identifier opf:scheme="UUID">urn:uuid:550E8400-E29B-41D4-A716-446655440000</dc:identifier>
    <dc:title>Sample</dc:title>
  </metadata>
</package>
```

`core/identity/src/test/java/io/theficos/ereader/core/identity/EpubIdentityExtractorTest.kt`:

```kotlin
package io.theficos.ereader.core.identity

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubIdentityExtractorTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `picks first non-empty dc identifier and normalizes`() {
        val opf = javaClass.getResource("/identity/sample.opf")!!.readBytes()
        val epub = makeEpubWith(opf)

        val id = extractMetadataId(epub)
        assertThat(id).isEqualTo("42")
    }

    @Test fun `returns null when no identifier present`() {
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>x</dc:title></metadata></package>""".toByteArray()
        val epub = makeEpubWith(opf)
        assertThat(extractMetadataId(epub)).isNull()
    }

    private fun makeEpubWith(opfBytes: ByteArray): File {
        val f = tmp.newFile("book.epub")
        ZipOutputStream(f.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write("""
                <?xml version="1.0"?>
                <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>
            """.trimIndent().toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(opfBytes)
            zip.closeEntry()
        }
        return f
    }
}
```

- [ ] **Step 12: Run the test**

Run: `./gradlew :core:identity:test --tests *EpubIdentityExtractorTest*`
Expected: FAIL.

- [ ] **Step 13: Implement `EpubIdentityExtractor.kt`**

```kotlin
package io.theficos.ereader.core.identity

import io.theficos.ereader.core.model.DocumentIdentity
import org.w3c.dom.Element
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

private const val DC_NS = "http://purl.org/dc/elements/1.1/"
private const val CONTAINER_NS = "urn:oasis:names:tc:opendocument:xmlns:container"

fun extractMetadataId(epub: File): String? {
    ZipFile(epub).use { zip ->
        val containerEntry = zip.getEntry("META-INF/container.xml") ?: return null
        val opfPath = zip.getInputStream(containerEntry).use { input ->
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(input)
            val rootfile = doc.getElementsByTagNameNS(CONTAINER_NS, "rootfile").item(0) as? Element
                ?: return null
            rootfile.getAttribute("full-path").ifEmpty { return null }
        }
        val opfEntry = zip.getEntry(opfPath) ?: return null
        return zip.getInputStream(opfEntry).use { input ->
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(input)
            val ids = doc.getElementsByTagNameNS(DC_NS, "identifier")
            for (i in 0 until ids.length) {
                val raw = ids.item(i).textContent?.trim().orEmpty()
                if (raw.isEmpty()) continue
                val normalized = normalizeMetadataId(raw)
                if (!normalized.isNullOrEmpty()) return normalized
            }
            null
        }
    }
}

fun extractIdentity(epub: File): DocumentIdentity =
    DocumentIdentity(metadataId = extractMetadataId(epub), contentHash = contentHash(epub))
```

- [ ] **Step 14: Run all identity tests**

Run: `./gradlew :core:identity:test`
Expected: PASS (all tests across the three test classes).

- [ ] **Step 15: Commit**

```bash
git add core/identity
git commit -m "feat(core-identity): metadata-id normalization, sampled content hash, EPUB OPF extraction"
```

---

## Task 4: `:data:local` — Room database, DAOs, repositories

**Files:**
- Create: `data/local/build.gradle.kts`
- Create: `data/local/src/main/AndroidManifest.xml`
- Create: `data/local/src/main/java/io/theficos/ereader/data/local/db/DocumentEntity.kt`
- Create: `data/local/src/main/java/io/theficos/ereader/data/local/db/DocumentDao.kt`
- Create: `data/local/src/main/java/io/theficos/ereader/data/local/db/ProgressEntity.kt`
- Create: `data/local/src/main/java/io/theficos/ereader/data/local/db/ProgressDao.kt`
- Create: `data/local/src/main/java/io/theficos/ereader/data/local/db/EReaderDatabase.kt`
- Create: `data/local/src/main/java/io/theficos/ereader/data/local/DocumentRepository.kt`
- Create: `data/local/src/main/java/io/theficos/ereader/data/local/ProgressRepository.kt`
- Test: `data/local/src/test/java/io/theficos/ereader/data/local/db/DocumentDaoTest.kt`
- Test: `data/local/src/test/java/io/theficos/ereader/data/local/db/ProgressDaoTest.kt`

- [ ] **Step 1: Replace `data/local/build.gradle.kts` stub**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.theficos.ereader.data.local"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:identity"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
}
```

- [ ] **Step 2: Write `AndroidManifest.xml`**

`data/local/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 3: Write the failing DocumentDao test**

`data/local/src/test/java/io/theficos/ereader/data/local/db/DocumentDaoTest.kt`:

```kotlin
package io.theficos.ereader.data.local.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentDaoTest {
    private lateinit var db: EReaderDatabase
    private lateinit var dao: DocumentDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.documentDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `insert and lookup by metadata id`() = runTest {
        val rowId = dao.insert(DocumentEntity(
            metadataId = "42", contentHash = "abc", title = "T", author = "A",
            downloadUrl = "https://x/y.epub", localPath = "/tmp/y.epub", downloadedAt = 1L
        ))
        val found = dao.findByMetadataId("42")
        assertThat(found?.id).isEqualTo(rowId)
        assertThat(found?.title).isEqualTo("T")
    }

    @Test fun `insert and lookup by content hash`() = runTest {
        dao.insert(DocumentEntity(
            metadataId = null, contentHash = "abc", title = "T", author = null,
            downloadUrl = "u", localPath = "p", downloadedAt = 1L
        ))
        assertThat(dao.findByContentHash("abc")?.contentHash).isEqualTo("abc")
    }

    @Test fun `unique constraint on metadata id`() = runTest {
        dao.insert(DocumentEntity(metadataId = "42", contentHash = "h1", title = "a", author = null, downloadUrl = "u1", localPath = "p1", downloadedAt = 1))
        try {
            dao.insert(DocumentEntity(metadataId = "42", contentHash = "h2", title = "b", author = null, downloadUrl = "u2", localPath = "p2", downloadedAt = 2))
            assertThat("expected unique violation").isEmpty()
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // ok
        }
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :data:local:testDebugUnitTest`
Expected: FAIL — `DocumentEntity`/`DocumentDao`/`EReaderDatabase` unresolved.

- [ ] **Step 5: Implement `DocumentEntity` and `DocumentDao`**

`data/local/src/main/java/io/theficos/ereader/data/local/db/DocumentEntity.kt`:

```kotlin
package io.theficos.ereader.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["metadataId"], unique = true),
        Index(value = ["contentHash"], unique = true),
    ],
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val metadataId: String?,
    val contentHash: String,
    val title: String,
    val author: String?,
    val downloadUrl: String,
    val localPath: String,
    val downloadedAt: Long,
)
```

`data/local/src/main/java/io/theficos/ereader/data/local/db/DocumentDao.kt`:

```kotlin
package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(doc: DocumentEntity): Long

    @Update
    suspend fun update(doc: DocumentEntity)

    @Query("SELECT * FROM documents WHERE metadataId = :id LIMIT 1")
    suspend fun findByMetadataId(id: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE contentHash = :hash LIMIT 1")
    suspend fun findByContentHash(hash: String): DocumentEntity?

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<DocumentEntity>>
}
```

- [ ] **Step 6: Implement `ProgressEntity` and `ProgressDao`**

`data/local/src/main/java/io/theficos/ereader/data/local/db/ProgressEntity.kt`:

```kotlin
package io.theficos.ereader.data.local.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "progress",
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class,
            parentColumns = ["id"],
            childColumns = ["documentId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("documentId", unique = true)],
)
data class ProgressEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val locator: String,
    val percent: Double,
    val updatedAt: Long,
)
```

`data/local/src/main/java/io/theficos/ereader/data/local/db/ProgressDao.kt`:

```kotlin
package io.theficos.ereader.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgressDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE documentId = :docId LIMIT 1")
    suspend fun findByDocument(docId: Long): ProgressEntity?

    @Query("SELECT * FROM progress WHERE documentId = :docId LIMIT 1")
    fun observeByDocument(docId: Long): Flow<ProgressEntity?>
}
```

- [ ] **Step 7: Implement `EReaderDatabase`**

`data/local/src/main/java/io/theficos/ereader/data/local/db/EReaderDatabase.kt`:

```kotlin
package io.theficos.ereader.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [DocumentEntity::class, ProgressEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class EReaderDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun progressDao(): ProgressDao

    companion object {
        fun build(context: Context): EReaderDatabase =
            Room.databaseBuilder(context, EReaderDatabase::class.java, "ereader.db")
                .fallbackToDestructiveMigration() // v1 only — replace with real migrations from v2 onward
                .build()
    }
}
```

Schema export goes to `data/local/schemas/`. Add to module's `build.gradle.kts` `android { defaultConfig { ksp { arg("room.schemaLocation", "$projectDir/schemas") } } }` — append inside the existing `defaultConfig` block.

Update `data/local/build.gradle.kts` `defaultConfig`:

```kotlin
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ksp { arg("room.schemaLocation", "$projectDir/schemas") }
    }
```

- [ ] **Step 8: Run DocumentDao test**

Run: `./gradlew :data:local:testDebugUnitTest --tests *DocumentDaoTest*`
Expected: PASS.

- [ ] **Step 9: Write the failing ProgressDao test**

`data/local/src/test/java/io/theficos/ereader/data/local/db/ProgressDaoTest.kt`:

```kotlin
package io.theficos.ereader.data.local.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProgressDaoTest {
    private lateinit var db: EReaderDatabase
    private lateinit var docs: DocumentDao
    private lateinit var dao: ProgressDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), EReaderDatabase::class.java
        ).allowMainThreadQueries().build()
        docs = db.documentDao()
        dao = db.progressDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `upsert replaces previous progress for same document`() = runTest {
        val docId = docs.insert(DocumentEntity(metadataId = null, contentHash = "h", title = "t", author = null, downloadUrl = "u", localPath = "p", downloadedAt = 0))
        dao.upsert(ProgressEntity(documentId = docId, locator = "loc1", percent = 0.1, updatedAt = 1))
        dao.upsert(ProgressEntity(documentId = docId, locator = "loc2", percent = 0.5, updatedAt = 2))
        val found = dao.findByDocument(docId)
        assertThat(found?.locator).isEqualTo("loc2")
        assertThat(found?.percent).isEqualTo(0.5)
    }

    @Test fun `flow emits updates`() = runTest {
        val docId = docs.insert(DocumentEntity(metadataId = null, contentHash = "h", title = "t", author = null, downloadUrl = "u", localPath = "p", downloadedAt = 0))
        dao.observeByDocument(docId).test {
            assertThat(awaitItem()).isNull()
            dao.upsert(ProgressEntity(documentId = docId, locator = "x", percent = 0.2, updatedAt = 1))
            assertThat(awaitItem()?.locator).isEqualTo("x")
            cancelAndIgnoreRemainingEvents()
        }
    }
}
```

- [ ] **Step 10: Run the test**

Run: `./gradlew :data:local:testDebugUnitTest --tests *ProgressDaoTest*`
Expected: PASS.

- [ ] **Step 11: Implement `DocumentRepository` and `ProgressRepository`**

`data/local/src/main/java/io/theficos/ereader/data/local/DocumentRepository.kt`:

```kotlin
package io.theficos.ereader.data.local

import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.DocumentIdentity
import io.theficos.ereader.data.local.db.DocumentDao
import io.theficos.ereader.data.local.db.DocumentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DocumentRepository(private val dao: DocumentDao) {

    fun observeLibrary(): Flow<List<Document>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    suspend fun findByIdentity(identity: DocumentIdentity): Document? {
        identity.metadataId?.let { dao.findByMetadataId(it)?.let { return it.toDomain() } }
        return dao.findByContentHash(identity.contentHash)?.toDomain()
    }

    suspend fun findById(id: Long): Document? = dao.findById(id)?.toDomain()

    suspend fun insert(
        identity: DocumentIdentity,
        title: String,
        author: String?,
        downloadUrl: String,
        localPath: String,
        downloadedAt: Long,
    ): Long = dao.insert(DocumentEntity(
        metadataId = identity.metadataId,
        contentHash = identity.contentHash,
        title = title,
        author = author,
        downloadUrl = downloadUrl,
        localPath = localPath,
        downloadedAt = downloadedAt,
    ))

    private fun DocumentEntity.toDomain(): Document = Document(
        id = id,
        identity = DocumentIdentity(metadataId = metadataId, contentHash = contentHash),
        title = title,
        author = author,
        downloadUrl = downloadUrl,
        localPath = localPath,
        downloadedAt = downloadedAt,
    )
}
```

`data/local/src/main/java/io/theficos/ereader/data/local/ProgressRepository.kt`:

```kotlin
package io.theficos.ereader.data.local

import io.theficos.ereader.core.model.Progress
import io.theficos.ereader.data.local.db.ProgressDao
import io.theficos.ereader.data.local.db.ProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ProgressRepository(private val dao: ProgressDao) {
    suspend fun get(documentId: Long): Progress? =
        dao.findByDocument(documentId)?.toDomain()

    fun observe(documentId: Long): Flow<Progress?> =
        dao.observeByDocument(documentId).map { it?.toDomain() }

    suspend fun save(progress: Progress) {
        dao.upsert(ProgressEntity(
            documentId = progress.documentId,
            locator = progress.locator,
            percent = progress.percent,
            updatedAt = progress.updatedAt,
        ))
    }

    private fun ProgressEntity.toDomain(): Progress =
        Progress(documentId = documentId, locator = locator, percent = percent, updatedAt = updatedAt)
}
```

- [ ] **Step 12: Run all `:data:local` tests**

Run: `./gradlew :data:local:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 13: Commit**

```bash
git add data/local
git commit -m "feat(data-local): Room DB, document and progress DAOs, repositories"
```

---

## Task 5: `:auth` — Keystore-backed Calibre Basic credential store

**Files:**
- Create: `auth/build.gradle.kts`
- Create: `auth/src/main/AndroidManifest.xml`
- Create: `auth/src/main/java/io/theficos/ereader/auth/CalibreCredentials.kt`
- Create: `auth/src/main/java/io/theficos/ereader/auth/CalibreCredentialStore.kt`
- Test: `auth/src/test/java/io/theficos/ereader/auth/CalibreCredentialStoreTest.kt`

The Phase 1 store only needs Calibre Basic creds. Authentik OIDC tokens are Phase 2.

- [ ] **Step 1: Replace `auth/build.gradle.kts` stub**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.theficos.ereader.auth"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

- [ ] **Step 2: Manifest**

`auth/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 3: Write failing test**

`auth/src/test/java/io/theficos/ereader/auth/CalibreCredentialStoreTest.kt`:

```kotlin
package io.theficos.ereader.auth

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CalibreCredentialStoreTest {
    @Test fun `round trip credentials`() = runTest {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        assertThat(store.get()).isNull()
        store.put(CalibreCredentials(baseUrl = "https://lib.example", username = "u", password = "p"))
        val got = store.get()
        assertThat(got?.baseUrl).isEqualTo("https://lib.example")
        assertThat(got?.username).isEqualTo("u")
        assertThat(got?.password).isEqualTo("p")
    }

    @Test fun `clear removes credentials`() = runTest {
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.put(CalibreCredentials("u", "u", "p"))
        store.clear()
        assertThat(store.get()).isNull()
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :auth:testDebugUnitTest`
Expected: FAIL — types unresolved.

- [ ] **Step 5: Implement**

`auth/src/main/java/io/theficos/ereader/auth/CalibreCredentials.kt`:

```kotlin
package io.theficos.ereader.auth

data class CalibreCredentials(
    val baseUrl: String,
    val username: String,
    val password: String,
)
```

`auth/src/main/java/io/theficos/ereader/auth/CalibreCredentialStore.kt`:

```kotlin
package io.theficos.ereader.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class CalibreCredentialStore(context: Context) {

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "calibre_creds",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun get(): CalibreCredentials? {
        val baseUrl = prefs.getString(KEY_BASE_URL, null) ?: return null
        val user = prefs.getString(KEY_USER, null) ?: return null
        val pass = prefs.getString(KEY_PASS, null) ?: return null
        return CalibreCredentials(baseUrl, user, pass)
    }

    fun put(creds: CalibreCredentials) {
        prefs.edit()
            .putString(KEY_BASE_URL, creds.baseUrl)
            .putString(KEY_USER, creds.username)
            .putString(KEY_PASS, creds.password)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_USER = "username"
        const val KEY_PASS = "password"
    }
}
```

- [ ] **Step 6: Run the test**

Run: `./gradlew :auth:testDebugUnitTest`
Expected: PASS. (Robolectric's keystore shim handles `MasterKey`. If it complains about hardware-backed keys, ensure Robolectric sdk = 33; do not bump.)

- [ ] **Step 7: Commit**

```bash
git add auth
git commit -m "feat(auth): keystore-backed Calibre Basic credential store"
```

---

## Task 6: `:data:opds` — OPDS feed client + EPUB downloader

**Files:**
- Create: `data/opds/build.gradle.kts`
- Create: `data/opds/src/main/AndroidManifest.xml`
- Create: `data/opds/src/main/java/io/theficos/ereader/data/opds/BasicAuthInterceptor.kt`
- Create: `data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsHttpClient.kt`
- Create: `data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsCatalog.kt`
- Create: `data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsClient.kt`
- Create: `data/opds/src/main/java/io/theficos/ereader/data/opds/BookDownloader.kt`
- Test: `data/opds/src/test/java/io/theficos/ereader/data/opds/BasicAuthInterceptorTest.kt`
- Test: `data/opds/src/test/java/io/theficos/ereader/data/opds/OpdsClientTest.kt`
- Test: `data/opds/src/test/java/io/theficos/ereader/data/opds/BookDownloaderTest.kt`
- Create: `data/opds/src/test/resources/opds/catalog-root.xml`
- Create: `data/opds/src/test/resources/opds/catalog-feed.xml`

We use Readium's `opds` parser for OPDS 1 feeds (calibre-web emits OPDS 1.x). Readium returns a `Feed` containing `Publication` entries with download `Link`s.

- [ ] **Step 1: Replace `data/opds/build.gradle.kts` stub**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.theficos.ereader.data.opds"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:identity"))
    implementation(project(":auth"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.readium.shared)
    implementation(libs.readium.opds)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.test.core)
}
```

- [ ] **Step 2: Manifest**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest>
    <uses-permission android:name="android.permission.INTERNET" />
</manifest>
```

- [ ] **Step 3: Write failing BasicAuthInterceptor test**

`data/opds/src/test/java/io/theficos/ereader/data/opds/BasicAuthInterceptorTest.kt`:

```kotlin
package io.theficos.ereader.data.opds

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.CalibreCredentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class BasicAuthInterceptorTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    @Test fun `adds basic auth header`() {
        server.enqueue(MockResponse().setBody("ok"))
        val provider = { CalibreCredentials(server.url("/").toString(), "alice", "s3cret") }
        val client = OkHttpClient.Builder().addInterceptor(BasicAuthInterceptor(provider)).build()
        client.newCall(Request.Builder().url(server.url("/feed")).build()).execute().close()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization"))
            .isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString("alice:s3cret".toByteArray()))
    }

    @Test fun `omits header when no credentials`() {
        server.enqueue(MockResponse().setBody("ok"))
        val client = OkHttpClient.Builder().addInterceptor(BasicAuthInterceptor { null }).build()
        client.newCall(Request.Builder().url(server.url("/feed")).build()).execute().close()

        val recorded = server.takeRequest()
        assertThat(recorded.getHeader("Authorization")).isNull()
    }
}
```

- [ ] **Step 4: Run the test**

Run: `./gradlew :data:opds:testDebugUnitTest --tests *BasicAuthInterceptorTest*`
Expected: FAIL.

- [ ] **Step 5: Implement `BasicAuthInterceptor`**

`data/opds/src/main/java/io/theficos/ereader/data/opds/BasicAuthInterceptor.kt`:

```kotlin
package io.theficos.ereader.data.opds

import io.theficos.ereader.auth.CalibreCredentials
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Base64

class BasicAuthInterceptor(
    private val credentialsProvider: () -> CalibreCredentials?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val creds = credentialsProvider()
        val request = chain.request()
        val withAuth = if (creds != null) {
            val raw = "${creds.username}:${creds.password}"
            val encoded = Base64.getEncoder().encodeToString(raw.toByteArray())
            request.newBuilder().header("Authorization", "Basic $encoded").build()
        } else request
        return chain.proceed(withAuth)
    }
}
```

- [ ] **Step 6: Run the test**

Run: `./gradlew :data:opds:testDebugUnitTest --tests *BasicAuthInterceptorTest*`
Expected: PASS.

- [ ] **Step 7: Implement `OpdsHttpClient`**

`data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsHttpClient.kt`:

```kotlin
package io.theficos.ereader.data.opds

import io.theficos.ereader.auth.CalibreCredentialStore
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class OpdsHttpClient(credentialStore: CalibreCredentialStore) {
    val okHttp: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .addInterceptor(BasicAuthInterceptor { credentialStore.get() })
        .build()
}
```

- [ ] **Step 8: Add fixture feeds**

`data/opds/src/test/resources/opds/catalog-root.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog">
  <id>urn:calibre-web:root</id>
  <title>calibre-web</title>
  <updated>2026-04-26T00:00:00Z</updated>
  <link rel="self" href="/opds" type="application/atom+xml;profile=opds-catalog;kind=navigation"/>
  <entry>
    <title>All Books</title>
    <id>urn:calibre-web:allbooks</id>
    <updated>2026-04-26T00:00:00Z</updated>
    <link rel="subsection" href="/opds/new" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
  </entry>
</feed>
```

`data/opds/src/test/resources/opds/catalog-feed.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:dc="http://purl.org/dc/terms/">
  <id>urn:calibre-web:new</id>
  <title>New Books</title>
  <updated>2026-04-26T00:00:00Z</updated>
  <link rel="self" href="/opds/new" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
  <entry>
    <title>The Sample Book</title>
    <id>urn:calibre-web:42</id>
    <updated>2026-04-26T00:00:00Z</updated>
    <author><name>Jane Doe</name></author>
    <dc:identifier>urn:uuid:550e8400-e29b-41d4-a716-446655440000</dc:identifier>
    <link rel="http://opds-spec.org/acquisition" href="/opds/download/42/epub" type="application/epub+zip"/>
    <link rel="http://opds-spec.org/image" href="/opds/cover/42" type="image/jpeg"/>
  </entry>
</feed>
```

- [ ] **Step 9: Write failing OpdsClient test**

`data/opds/src/test/java/io/theficos/ereader/data/opds/OpdsClientTest.kt`:

```kotlin
package io.theficos.ereader.data.opds

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.CalibreCredentials
import io.theficos.ereader.auth.CalibreCredentialStore
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OpdsClientTest {
    private lateinit var server: MockWebServer
    private lateinit var store: CalibreCredentialStore
    private lateinit var client: OpdsClient

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.put(CalibreCredentials(server.url("/").toString().trimEnd('/'), "u", "p"))
        client = OpdsClient(OpdsHttpClient(store).okHttp, store)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(req: RecordedRequest): MockResponse = when (req.path) {
                "/opds" -> MockResponse().setHeader("Content-Type", "application/atom+xml")
                    .setBody(resource("/opds/catalog-root.xml"))
                "/opds/new" -> MockResponse().setHeader("Content-Type", "application/atom+xml")
                    .setBody(resource("/opds/catalog-feed.xml"))
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @After fun tearDown() { server.shutdown() }

    private fun resource(p: String) = javaClass.getResource(p)!!.readText()

    @Test fun `fetch root catalog returns navigation entries`() = runTest {
        val feed = client.fetch(server.url("/opds").toString())
        assertThat(feed.title).isEqualTo("calibre-web")
        assertThat(feed.navigation).hasSize(1)
        assertThat(feed.navigation[0].title).isEqualTo("All Books")
        assertThat(feed.navigation[0].href).endsWith("/opds/new")
    }

    @Test fun `fetch acquisition feed returns publications with epub links`() = runTest {
        val feed = client.fetch(server.url("/opds/new").toString())
        assertThat(feed.publications).hasSize(1)
        val pub = feed.publications[0]
        assertThat(pub.title).isEqualTo("The Sample Book")
        assertThat(pub.author).isEqualTo("Jane Doe")
        assertThat(pub.epubDownloadHref).endsWith("/opds/download/42/epub")
    }
}
```

- [ ] **Step 10: Run the test**

Run: `./gradlew :data:opds:testDebugUnitTest --tests *OpdsClientTest*`
Expected: FAIL — `OpdsClient`/`OpdsCatalog` unresolved.

- [ ] **Step 11: Implement `OpdsCatalog` and `OpdsClient`**

`data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsCatalog.kt`:

```kotlin
package io.theficos.ereader.data.opds

data class OpdsFeed(
    val title: String,
    val navigation: List<OpdsNavigationLink>,
    val publications: List<OpdsPublication>,
)

data class OpdsNavigationLink(
    val title: String,
    val href: String,
)

data class OpdsPublication(
    val title: String,
    val author: String?,
    val epubDownloadHref: String,
    val coverHref: String?,
)
```

`data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsClient.kt`:

```kotlin
package io.theficos.ereader.data.opds

import io.theficos.ereader.auth.CalibreCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.readium.r2.opds.OPDS1Parser
import java.net.URL

class OpdsClient(
    private val okHttp: OkHttpClient,
    private val credentialStore: CalibreCredentialStore,
) {
    suspend fun fetch(absoluteUrl: String): OpdsFeed = withContext(Dispatchers.IO) {
        val response = okHttp.newCall(Request.Builder().url(absoluteUrl).get().build()).execute()
        response.use {
            require(it.isSuccessful) { "OPDS fetch ${it.code} for $absoluteUrl" }
            val bytes = it.body!!.bytes()
            val parsed = OPDS1Parser.parse(bytes, URL(absoluteUrl))
                ?: error("OPDS1Parser returned null for $absoluteUrl")
            val feed = parsed.feed ?: error("Parsed OPDS payload had no feed")
            OpdsFeed(
                title = feed.metadata.title,
                navigation = feed.navigation.mapNotNull { link ->
                    val href = link.href ?: return@mapNotNull null
                    OpdsNavigationLink(title = link.title.orEmpty(), href = absolutize(absoluteUrl, href))
                },
                publications = feed.publications.mapNotNull { pub ->
                    val epubLink = pub.links.firstOrNull {
                        it.rel.contains("http://opds-spec.org/acquisition") &&
                            it.type == "application/epub+zip"
                    } ?: return@mapNotNull null
                    val cover = pub.images.firstOrNull()?.href
                    OpdsPublication(
                        title = pub.metadata.title,
                        author = pub.metadata.authors.firstOrNull()?.name,
                        epubDownloadHref = absolutize(absoluteUrl, epubLink.href!!),
                        coverHref = cover?.let { absolutize(absoluteUrl, it) },
                    )
                },
            )
        }
    }

    private fun absolutize(base: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val baseUrl = base.toHttpUrl()
        val resolved = baseUrl.resolve(href) ?: return href
        return resolved.toString()
    }
}
```

> **Note for the implementer:** the property names on Readium's `OPDS1Parser` types may differ slightly between point releases. If `feed.publications`, `feed.navigation`, `pub.images`, or `link.rel` don't compile, open the Readium source for the version pinned in `libs.versions.toml` and adapt — the spec coverage matters, not the exact accessor name. Adjust the test fixtures only if Readium's parser legitimately rejects them.

- [ ] **Step 12: Run the test**

Run: `./gradlew :data:opds:testDebugUnitTest --tests *OpdsClientTest*`
Expected: PASS.

- [ ] **Step 13: Write failing BookDownloader test**

`data/opds/src/test/java/io/theficos/ereader/data/opds/BookDownloaderTest.kt`:

```kotlin
package io.theficos.ereader.data.opds

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.CalibreCredentials
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BookDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var downloader: BookDownloader

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        val store = CalibreCredentialStore(ApplicationProvider.getApplicationContext())
        store.put(CalibreCredentials(server.url("/").toString(), "u", "p"))
        downloader = BookDownloader(OpdsHttpClient(store).okHttp, tmp.root)
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun `downloads to file with progress callback`() = runTest {
        val payload = ByteArray(8 * 1024) { (it % 251).toByte() }
        server.enqueue(MockResponse()
            .setHeader("Content-Length", payload.size.toString())
            .setBody(Buffer().write(payload)))

        val updates = mutableListOf<Long>()
        val file = downloader.download(
            url = server.url("/opds/download/42/epub").toString(),
            destFileName = "42.epub",
            onProgress = { sent, total ->
                updates += sent
                assertThat(total).isEqualTo(payload.size.toLong())
            },
        )

        assertThat(file.exists()).isTrue()
        assertThat(file.length()).isEqualTo(payload.size.toLong())
        assertThat(updates).isNotEmpty()
        assertThat(updates.last()).isEqualTo(payload.size.toLong())
    }

    @Test(expected = IllegalStateException::class)
    fun `non-2xx throws`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        downloader.download(server.url("/x").toString(), "x.epub") { _, _ -> }
    }
}
```

- [ ] **Step 14: Run the test**

Run: `./gradlew :data:opds:testDebugUnitTest --tests *BookDownloaderTest*`
Expected: FAIL.

- [ ] **Step 15: Implement `BookDownloader`**

`data/opds/src/main/java/io/theficos/ereader/data/opds/BookDownloader.kt`:

```kotlin
package io.theficos.ereader.data.opds

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

class BookDownloader(
    private val okHttp: OkHttpClient,
    private val booksDir: File,
) {
    init { booksDir.mkdirs() }

    suspend fun download(
        url: String,
        destFileName: String,
        onProgress: (bytesSent: Long, total: Long) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val response = okHttp.newCall(Request.Builder().url(url).get().build()).execute()
        response.use {
            check(it.isSuccessful) { "Download failed ${it.code} for $url" }
            val total = it.body!!.contentLength()
            val out = File(booksDir, destFileName)
            val tmp = File(booksDir, "$destFileName.part")
            it.body!!.byteStream().use { input ->
                tmp.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n <= 0) break
                        sink.write(buffer, 0, n)
                        sent += n
                        onProgress(sent, total)
                    }
                }
            }
            if (out.exists()) out.delete()
            check(tmp.renameTo(out)) { "Failed to rename ${tmp.path} -> ${out.path}" }
            out
        }
    }
}
```

- [ ] **Step 16: Run all `:data:opds` tests**

Run: `./gradlew :data:opds:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 17: Commit**

```bash
git add data/opds
git commit -m "feat(data-opds): OPDS feed client, EPUB downloader, basic auth interceptor"
```

---

## Task 7: `:reader` — Readium navigator wrapper + progress tracking

**Files:**
- Create: `reader/build.gradle.kts`
- Create: `reader/src/main/AndroidManifest.xml`
- Create: `reader/src/main/java/io/theficos/ereader/reader/EpubAsset.kt`
- Create: `reader/src/main/java/io/theficos/ereader/reader/ReadiumFactory.kt`
- Create: `reader/src/main/java/io/theficos/ereader/reader/EpubNavigatorHost.kt`
- Create: `reader/src/main/java/io/theficos/ereader/reader/ProgressTracker.kt`
- Test: `reader/src/test/java/io/theficos/ereader/reader/ProgressTrackerTest.kt`

The reader module exposes:
- `ReadiumFactory` — opens an EPUB asset into a Readium `Publication`.
- `EpubNavigatorHost` — Compose-friendly host that creates Readium's `EpubNavigatorFragment` and exposes a `Flow<Locator>`.
- `ProgressTracker` — translates Readium `Locator` updates into `Progress` and persists via `ProgressRepository`, debounced to once per second to avoid disk thrash.

The instrumented behavior of the navigator fragment is verified manually in Task 10. The unit-testable piece is `ProgressTracker`.

- [ ] **Step 1: Replace `reader/build.gradle.kts` stub**

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.theficos.ereader.reader"
    compileSdk = 34
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = false }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    api(project(":core:model"))
    api(project(":data:local"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
}
```

- [ ] **Step 2: Manifest**

`reader/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 3: Implement `EpubAsset` and `ReadiumFactory`**

`reader/src/main/java/io/theficos/ereader/reader/EpubAsset.kt`:

```kotlin
package io.theficos.ereader.reader

import java.io.File

data class EpubAsset(val documentId: Long, val file: File, val title: String)
```

`reader/src/main/java/io/theficos/ereader/reader/ReadiumFactory.kt`:

```kotlin
package io.theficos.ereader.reader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

class ReadiumFactory(context: Context) {

    private val assetRetriever = AssetRetriever(context.contentResolver, context.cacheDir)
    private val opener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = context,
            httpClient = null,
            assetRetriever = assetRetriever,
            pdfFactory = null,
        ),
    )

    suspend fun open(asset: EpubAsset): Publication = withContext(Dispatchers.IO) {
        val ra = assetRetriever.retrieve(asset.file).getOrElse {
            error("AssetRetriever failed for ${asset.file}: $it")
        }
        opener.open(ra, allowUserInteraction = false).getOrElse {
            error("PublicationOpener failed for ${asset.file}: $it")
        }
    }
}
```

> **Note for the implementer:** the exact factory signature varies between Readium 3.x point releases. If `AssetRetriever`, `PublicationOpener`, or `DefaultPublicationParser` differ in your pinned version, adapt to whatever Readium provides for "open an EPUB file → get a `Publication`." The wrapper exists so the rest of the codebase is shielded from this churn.

- [ ] **Step 4: Write failing ProgressTracker test**

`reader/src/test/java/io/theficos/ereader/reader/ProgressTrackerTest.kt`:

```kotlin
package io.theficos.ereader.reader

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.Progress
import io.theficos.ereader.data.local.ProgressRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressTrackerTest {

    private class FakeRepo : ProgressRepository(dao = throw UnsupportedOperationException()) {
        val saved = mutableListOf<Progress>()
        override suspend fun save(progress: Progress) { saved += progress }
    }

    @Test fun `debounces saves to one per second`() = runTest(StandardTestDispatcher()) {
        val repo = FakeRepo()
        val locators = MutableSharedFlow<LocatorUpdate>(extraBufferCapacity = 16)
        val tracker = ProgressTracker(repo = repo, scope = TestScope(coroutineContext), nowMs = { currentTime })
        tracker.attach(documentId = 1L, locatorUpdates = locators)

        repeat(5) { locators.tryEmit(LocatorUpdate(href = "ch1", positionPercent = 0.10 + it * 0.01)) }
        advanceTimeBy(50)
        assertThat(repo.saved).isEmpty()

        advanceTimeBy(1_000)
        assertThat(repo.saved).hasSize(1)
        assertThat(repo.saved.last().percent).isWithin(0.001).of(0.14)

        repeat(5) { locators.tryEmit(LocatorUpdate(href = "ch1", positionPercent = 0.20 + it * 0.01)) }
        advanceTimeBy(1_000)
        assertThat(repo.saved).hasSize(2)
        assertThat(repo.saved.last().percent).isWithin(0.001).of(0.24)
    }

    @Test fun `flushes immediately on detach`() = runTest(StandardTestDispatcher()) {
        val repo = FakeRepo()
        val locators = MutableSharedFlow<LocatorUpdate>(extraBufferCapacity = 16)
        val tracker = ProgressTracker(repo = repo, scope = TestScope(coroutineContext), nowMs = { currentTime })
        tracker.attach(documentId = 1L, locatorUpdates = locators)
        locators.tryEmit(LocatorUpdate(href = "ch1", positionPercent = 0.5))
        tracker.detach()
        assertThat(repo.saved).hasSize(1)
        assertThat(repo.saved.first().locator).contains("ch1")
        assertThat(repo.saved.first().percent).isEqualTo(0.5)
    }
}
```

The test extends `ProgressRepository` directly, so the constructor must be open to subclassing. To avoid that, refactor `ProgressRepository` to take an interface — simpler: introduce a `ProgressSink` interface here in `:reader` and have `ProgressRepository` implement it (or pass a lambda). We'll go with the lambda approach to avoid adding a cross-module interface for one method.

Replace the test fake usage with a lambda-based sink. Updated test:

```kotlin
package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.Progress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProgressTrackerTest {

    @Test fun `debounces saves to one per second`() = runTest(StandardTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<LocatorUpdate>(extraBufferCapacity = 16)
        val tracker = ProgressTracker(save = { saved += it }, scope = TestScope(coroutineContext), nowMs = { currentTime })
        tracker.attach(documentId = 1L, locatorUpdates = locators)

        repeat(5) { locators.tryEmit(LocatorUpdate(href = "ch1", positionPercent = 0.10 + it * 0.01)) }
        advanceTimeBy(50)
        assertThat(saved).isEmpty()

        advanceTimeBy(1_000)
        assertThat(saved).hasSize(1)
        assertThat(saved.last().percent).isWithin(0.001).of(0.14)
    }

    @Test fun `flushes immediately on detach`() = runTest(StandardTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<LocatorUpdate>(extraBufferCapacity = 16)
        val tracker = ProgressTracker(save = { saved += it }, scope = TestScope(coroutineContext), nowMs = { currentTime })
        tracker.attach(documentId = 1L, locatorUpdates = locators)
        locators.tryEmit(LocatorUpdate(href = "ch1", positionPercent = 0.5))
        tracker.detach()
        assertThat(saved).hasSize(1)
        assertThat(saved.first().locator).contains("ch1")
        assertThat(saved.first().percent).isEqualTo(0.5)
    }
}
```

- [ ] **Step 5: Run the test**

Run: `./gradlew :reader:testDebugUnitTest`
Expected: FAIL — `LocatorUpdate`/`ProgressTracker` unresolved.

- [ ] **Step 6: Implement `ProgressTracker`**

`reader/src/main/java/io/theficos/ereader/reader/ProgressTracker.kt`:

```kotlin
package io.theficos.ereader.reader

import io.theficos.ereader.core.model.Progress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

data class LocatorUpdate(val href: String, val positionPercent: Double)

class ProgressTracker(
    private val save: suspend (Progress) -> Unit,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val debounceMs: Long = 1_000L,
) {
    private val pending = MutableStateFlow<Pending?>(null)
    private var collectJob: Job? = null
    private var debounceJob: Job? = null
    private var documentId: Long = -1L

    fun attach(documentId: Long, locatorUpdates: Flow<LocatorUpdate>) {
        this.documentId = documentId
        collectJob = scope.launch {
            locatorUpdates.collect { update ->
                pending.value = Pending(update, nowMs())
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(debounceMs)
                    flushOnce()
                }
            }
        }
    }

    fun detach() {
        debounceJob?.cancel()
        runBlocking { flushOnce() }
        collectJob?.cancel()
    }

    private suspend fun flushOnce() {
        val p = pending.value ?: return
        pending.value = null
        save(Progress(
            documentId = documentId,
            locator = serialize(p.update),
            percent = p.update.positionPercent.coerceIn(0.0, 1.0),
            updatedAt = p.timestampMs,
        ))
    }

    private fun serialize(u: LocatorUpdate): String =
        """{"href":"${u.href}","percent":${u.positionPercent}}"""

    private data class Pending(val update: LocatorUpdate, val timestampMs: Long)
}
```

> The serialized locator format here is intentionally minimal for Phase 1 (href + percent). Phase 2 swaps it for Readium's full `Locator.toJSON()` so the server has rich anchoring. The `locator` column in Room is plain text either way.

- [ ] **Step 7: Run the test**

Run: `./gradlew :reader:testDebugUnitTest`
Expected: PASS.

- [ ] **Step 8: Implement `EpubNavigatorHost` (Compose-callable thin wrapper)**

`reader/src/main/java/io/theficos/ereader/reader/EpubNavigatorHost.kt`:

```kotlin
package io.theficos.ereader.reader

import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

class EpubNavigatorHost(
    private val activity: FragmentActivity,
    private val containerId: Int,
) {
    private val _updates = MutableSharedFlow<LocatorUpdate>(extraBufferCapacity = 64)
    val updates: Flow<LocatorUpdate> get() = _updates

    private var navigator: EpubNavigatorFragment? = null

    fun open(publication: Publication, initialLocator: Locator?) {
        val factory = EpubNavigatorFactory(publication)
        val fragmentFactory = factory.createFragmentFactory(initialLocator = initialLocator)
        activity.supportFragmentManager.fragmentFactory = fragmentFactory

        val fragment = activity.supportFragmentManager.fragmentFactory.instantiate(
            activity.classLoader, EpubNavigatorFragment::class.java.name,
        ) as EpubNavigatorFragment

        activity.supportFragmentManager.beginTransaction()
            .replace(containerId, fragment)
            .commitNow()
        navigator = fragment

        fragment.addListener(object : EpubNavigatorFragment.Listener {})
        // EpubNavigatorFragment exposes currentLocator: StateFlow<Locator>
        activity.lifecycle.addObserver(androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START) {
                activity.lifecycleScope().launchWhenStarted {
                    fragment.currentLocator.collect { locator ->
                        val href = locator.href
                        val percent = locator.locations.totalProgression ?: locator.locations.progression ?: 0.0
                        _updates.tryEmit(LocatorUpdate(href.toString(), percent))
                    }
                }
            }
        })
    }

    private fun FragmentActivity.lifecycleScope() = androidx.lifecycle.lifecycleScope
}
```

> Same caveat as `ReadiumFactory`: API surface here is the part of Readium most likely to drift. The contract is "produce `LocatorUpdate`s as the user reads"; adapt the navigator wiring to whatever the pinned Readium version exposes. If the listener API changes, the substitute should still pump `_updates`.

- [ ] **Step 9: Verify the module compiles**

Run: `./gradlew :reader:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 10: Commit**

```bash
git add reader
git commit -m "feat(reader): Readium publication factory, navigator host, debounced progress tracker"
```

---

## Task 8: `:app` — Application module skeleton, DI container, settings screen

**Files:**
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/io/theficos/ereader/EReaderApp.kt`
- Create: `app/src/main/java/io/theficos/ereader/MainActivity.kt`
- Create: `app/src/main/java/io/theficos/ereader/di/AppContainer.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/theme/Theme.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`

- [ ] **Step 1: Replace `app/build.gradle.kts` stub**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.theficos.ereader"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.theficos.ereader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-phase1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation(project(":reader"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)
}
```

- [ ] **Step 2: Manifest**

`app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".EReaderApp"
        android:label="@string/app_name"
        android:theme="@style/Theme.EReader">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 3: Resources**

`app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">eReader</string>
</resources>
```

`app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.EReader" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 4: `EReaderApp` and `MainActivity`**

`app/src/main/java/io/theficos/ereader/EReaderApp.kt`:

```kotlin
package io.theficos.ereader

import android.app.Application
import io.theficos.ereader.di.AppContainer

class EReaderApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
```

`app/src/main/java/io/theficos/ereader/MainActivity.kt`:

```kotlin
package io.theficos.ereader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import io.theficos.ereader.ui.AppNavGraph
import io.theficos.ereader.ui.theme.EReaderTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EReaderTheme {
                AppNavGraph(container = (application as EReaderApp).container)
            }
        }
    }
}
```

- [ ] **Step 5: Theme + DI container**

`app/src/main/java/io/theficos/ereader/ui/theme/Theme.kt`:

```kotlin
package io.theficos.ereader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme

@Composable
fun EReaderTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    MaterialTheme(colorScheme = colors, content = content)
}
```

`app/src/main/java/io/theficos/ereader/di/AppContainer.kt`:

```kotlin
package io.theficos.ereader.di

import android.content.Context
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.data.local.db.EReaderDatabase
import io.theficos.ereader.data.opds.BookDownloader
import io.theficos.ereader.data.opds.OpdsClient
import io.theficos.ereader.data.opds.OpdsHttpClient
import io.theficos.ereader.reader.ReadiumFactory
import java.io.File

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val credentialStore: CalibreCredentialStore = CalibreCredentialStore(appContext)
    private val httpClient = OpdsHttpClient(credentialStore)
    val opdsClient: OpdsClient = OpdsClient(httpClient.okHttp, credentialStore)
    val bookDownloader: BookDownloader = BookDownloader(
        okHttp = httpClient.okHttp,
        booksDir = File(appContext.filesDir, "books"),
    )
    private val db: EReaderDatabase = EReaderDatabase.build(appContext)
    val documentRepository = DocumentRepository(db.documentDao())
    val progressRepository = ProgressRepository(db.progressDao())
    val readiumFactory = ReadiumFactory(appContext)
}
```

- [ ] **Step 6: Settings ViewModel + Screen**

`app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt`:

```kotlin
package io.theficos.ereader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.CalibreCredentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(private val store: CalibreCredentialStore) : ViewModel() {
    private val _state = MutableStateFlow(loadInitial())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private fun loadInitial(): SettingsUiState {
        val creds = store.get()
        return SettingsUiState(
            baseUrl = creds?.baseUrl.orEmpty(),
            username = creds?.username.orEmpty(),
            password = creds?.password.orEmpty(),
            saved = creds != null,
        )
    }

    fun onBaseUrlChange(value: String) { _state.value = _state.value.copy(baseUrl = value, saved = false) }
    fun onUsernameChange(value: String) { _state.value = _state.value.copy(username = value, saved = false) }
    fun onPasswordChange(value: String) { _state.value = _state.value.copy(password = value, saved = false) }

    fun save() {
        val s = _state.value
        if (s.baseUrl.isBlank() || s.username.isBlank() || s.password.isBlank()) return
        viewModelScope.launch {
            store.put(CalibreCredentials(s.baseUrl.trim().trimEnd('/'), s.username, s.password))
            _state.value = s.copy(saved = true)
        }
    }
}

data class SettingsUiState(
    val baseUrl: String,
    val username: String,
    val password: String,
    val saved: Boolean,
)
```

`app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt`:

```kotlin
package io.theficos.ereader.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = {
        TextButton(onClick = onBack) { Text("Back") }
    }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(value = state.baseUrl, onValueChange = viewModel::onBaseUrlChange, label = { Text("calibre-web URL") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = state.username, onValueChange = viewModel::onUsernameChange, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = state.password, onValueChange = viewModel::onPasswordChange, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Button(onClick = viewModel::save, enabled = state.baseUrl.isNotBlank() && state.username.isNotBlank() && state.password.isNotBlank()) {
                Text(if (state.saved) "Saved" else "Save")
            }
        }
    }
}
```

- [ ] **Step 7: Stub `AppNavGraph` (settings only for now)**

`app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`:

```kotlin
package io.theficos.ereader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.theficos.ereader.di.AppContainer
import io.theficos.ereader.ui.settings.SettingsScreen
import io.theficos.ereader.ui.settings.SettingsViewModel

@Composable
fun AppNavGraph(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "settings") {
        composable("settings") {
            val vm = remember { SettingsViewModel(container.credentialStore) }
            SettingsScreen(viewModel = vm, onBack = { /* no-op for now */ })
        }
    }
}
```

- [ ] **Step 8: Build the app**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add app
git commit -m "feat(app): application skeleton, DI container, settings screen"
```

---

## Task 9: `:app` — Catalog browse screen

**Files:**
- Create: `app/src/main/java/io/theficos/ereader/ui/catalog/CatalogViewModel.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/catalog/CatalogScreen.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`

The catalog screen takes an OPDS URL (default: `<baseUrl>/opds`), fetches it, and renders navigation entries (drill in) and publications (download button). On download: stream to disk, compute identity, insert `DocumentEntity`, navigate back to library.

- [ ] **Step 1: Implement `CatalogViewModel`**

`app/src/main/java/io/theficos/ereader/ui/catalog/CatalogViewModel.kt`:

```kotlin
package io.theficos.ereader.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.core.identity.extractIdentity
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.opds.BookDownloader
import io.theficos.ereader.data.opds.OpdsClient
import io.theficos.ereader.data.opds.OpdsFeed
import io.theficos.ereader.data.opds.OpdsPublication
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.util.UUID

class CatalogViewModel(
    private val client: OpdsClient,
    private val downloader: BookDownloader,
    private val docs: DocumentRepository,
    private val credentialStore: CalibreCredentialStore,
) : ViewModel() {

    private val _state = MutableStateFlow<CatalogUiState>(CatalogUiState.Idle)
    val state: StateFlow<CatalogUiState> = _state.asStateFlow()

    fun loadRoot() {
        val baseUrl = credentialStore.get()?.baseUrl
        if (baseUrl.isNullOrBlank()) {
            _state.value = CatalogUiState.Error("Configure calibre-web in Settings first.")
            return
        }
        load("${baseUrl.trimEnd('/')}/opds")
    }

    fun load(url: String) {
        _state.value = CatalogUiState.Loading
        viewModelScope.launch {
            runCatching { client.fetch(url) }
                .onSuccess { _state.value = CatalogUiState.Loaded(url, it) }
                .onFailure { _state.value = CatalogUiState.Error(it.message ?: "Fetch failed") }
        }
    }

    fun download(pub: OpdsPublication) {
        val current = _state.value as? CatalogUiState.Loaded ?: return
        viewModelScope.launch {
            _state.value = current.copy(downloading = pub.epubDownloadHref, progress = 0f)
            runCatching {
                val fileName = "${UUID.randomUUID()}.epub"
                val file = downloader.download(pub.epubDownloadHref, fileName) { sent, total ->
                    val frac = if (total > 0) sent.toFloat() / total else 0f
                    _state.value = (_state.value as? CatalogUiState.Loaded)?.copy(progress = frac) ?: return@download
                }
                val identity = extractIdentity(file)
                val existing = docs.findByIdentity(identity)
                if (existing == null) {
                    docs.insert(
                        identity = identity,
                        title = pub.title,
                        author = pub.author,
                        downloadUrl = pub.epubDownloadHref,
                        localPath = file.absolutePath,
                        downloadedAt = System.currentTimeMillis(),
                    )
                } else {
                    file.delete()
                }
            }.onSuccess {
                _state.value = current.copy(downloading = null, progress = 0f, lastDownloaded = pub.title)
            }.onFailure {
                _state.value = current.copy(downloading = null, progress = 0f, error = it.message)
            }
        }
    }
}

sealed interface CatalogUiState {
    data object Idle : CatalogUiState
    data object Loading : CatalogUiState
    data class Error(val message: String) : CatalogUiState
    data class Loaded(
        val url: String,
        val feed: OpdsFeed,
        val downloading: String? = null,
        val progress: Float = 0f,
        val lastDownloaded: String? = null,
        val error: String? = null,
    ) : CatalogUiState
}
```

- [ ] **Step 2: Implement `CatalogScreen`**

`app/src/main/java/io/theficos/ereader/ui/catalog/CatalogScreen.kt`:

```kotlin
package io.theficos.ereader.ui.catalog

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { if (state == CatalogUiState.Idle) viewModel.loadRoot() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Catalog") },
            actions = {
                TextButton(onClick = onOpenLibrary) { Text("Library") }
                TextButton(onClick = onOpenSettings) { Text("Settings") }
            },
        )
    }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (val s = state) {
                CatalogUiState.Idle -> {}
                CatalogUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is CatalogUiState.Error -> Text(s.message, Modifier.align(Alignment.Center))
                is CatalogUiState.Loaded -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(s.feed.navigation) { nav ->
                            ListItem(
                                headlineContent = { Text(nav.title) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                            )
                            HorizontalDivider()
                        }
                        items(s.feed.publications) { pub ->
                            ListItem(
                                headlineContent = { Text(pub.title) },
                                supportingContent = pub.author?.let { { Text(it) } },
                                trailingContent = {
                                    if (s.downloading == pub.epubDownloadHref) {
                                        CircularProgressIndicator(progress = { s.progress })
                                    } else {
                                        Button(onClick = { viewModel.download(pub) }) { Text("Download") }
                                    }
                                },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}
```

For nav-entry click-through (drill into sub-feeds), update the nav `ListItem`:

```kotlin
ListItem(
    headlineContent = { Text(nav.title) },
    modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 8.dp)
        .androidx.compose.foundation.clickable { viewModel.load(nav.href) },
)
```

(Use `Modifier.clickable` — import `androidx.compose.foundation.clickable`.)

- [ ] **Step 3: Wire `catalog` route into `AppNavGraph`**

Replace `AppNavGraph.kt` with:

```kotlin
package io.theficos.ereader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.theficos.ereader.di.AppContainer
import io.theficos.ereader.ui.catalog.CatalogScreen
import io.theficos.ereader.ui.catalog.CatalogViewModel
import io.theficos.ereader.ui.settings.SettingsScreen
import io.theficos.ereader.ui.settings.SettingsViewModel

@Composable
fun AppNavGraph(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "catalog") {
        composable("catalog") {
            val vm = remember {
                CatalogViewModel(container.opdsClient, container.bookDownloader, container.documentRepository, container.credentialStore)
            }
            CatalogScreen(
                viewModel = vm,
                onOpenLibrary = { nav.navigate("library") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            val vm = remember { SettingsViewModel(container.credentialStore) }
            SettingsScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
        composable("library") {
            // placeholder; filled in next task
            androidx.compose.material3.Text("Library — coming in next task")
        }
    }
}
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app
git commit -m "feat(app): OPDS catalog browse + EPUB download"
```

---

## Task 10: `:app` — Library, Reader screen, end-to-end manual verification

**Files:**
- Create: `app/src/main/java/io/theficos/ereader/ui/library/LibraryViewModel.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderViewModel.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`

- [ ] **Step 1: Implement `LibraryViewModel`**

`app/src/main/java/io/theficos/ereader/ui/library/LibraryViewModel.kt`:

```kotlin
package io.theficos.ereader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

class LibraryViewModel(
    private val docs: DocumentRepository,
    private val progress: ProgressRepository,
) : ViewModel() {

    val items: StateFlow<List<LibraryRow>> =
        docs.observeLibrary()
            .flatMapLatest { docList ->
                if (docList.isEmpty()) flowOf(emptyList())
                else combine(docList.map { d -> progress.observe(d.id).map { d to it?.percent } }) { it.toList() }
            }
            .map { pairs -> pairs.map { (d, pct) -> LibraryRow(d, pct ?: 0.0) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

data class LibraryRow(val document: Document, val percent: Double)
```

Add the missing imports:

```kotlin
import kotlinx.coroutines.flow.map
```

- [ ] **Step 2: Implement `LibraryScreen`**

`app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt`:

```kotlin
package io.theficos.ereader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenCatalog: () -> Unit,
    onOpenBook: (documentId: Long) -> Unit,
) {
    val items by viewModel.items.collectAsState()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Library") }, actions = {
            TextButton(onClick = onOpenCatalog) { Text("Catalog") }
        })
    }) { padding ->
        if (items.isEmpty()) {
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No books yet. Download from the Catalog.")
            }
        } else {
            LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(items) { row ->
                    ListItem(
                        headlineContent = { Text(row.document.title) },
                        supportingContent = { Text("${(row.percent * 100).toInt()}%") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenBook(row.document.id) }
                            .padding(horizontal = 8.dp),
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
```

- [ ] **Step 3: Implement `ReaderViewModel`**

`app/src/main/java/io/theficos/ereader/ui/reader/ReaderViewModel.kt`:

```kotlin
package io.theficos.ereader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.Progress
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.reader.EpubAsset
import io.theficos.ereader.reader.LocatorUpdate
import io.theficos.ereader.reader.ProgressTracker
import io.theficos.ereader.reader.ReadiumFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Publication
import java.io.File

class ReaderViewModel(
    private val documentId: Long,
    private val docs: DocumentRepository,
    private val progress: ProgressRepository,
    private val readium: ReadiumFactory,
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _locatorUpdates = MutableSharedFlow<LocatorUpdate>(extraBufferCapacity = 64)
    val locatorUpdates: SharedFlow<LocatorUpdate> = _locatorUpdates.asSharedFlow()

    private val tracker = ProgressTracker(
        save = { progress.save(it) },
        scope = viewModelScope,
    )

    fun load() {
        viewModelScope.launch {
            val doc = docs.findById(documentId) ?: run {
                _state.value = ReaderUiState.Error("Document not found")
                return@launch
            }
            val publication = runCatching {
                readium.open(EpubAsset(doc.id, File(doc.localPath), doc.title))
            }.getOrElse {
                _state.value = ReaderUiState.Error(it.message ?: "Failed to open book")
                return@launch
            }
            val savedProgress = progress.get(doc.id)
            _state.value = ReaderUiState.Open(doc, publication, savedProgress)
            tracker.attach(documentId = doc.id, locatorUpdates = locatorUpdates)
        }
    }

    fun publishLocator(update: LocatorUpdate) {
        _locatorUpdates.tryEmit(update)
    }

    override fun onCleared() {
        tracker.detach()
        super.onCleared()
    }
}

sealed interface ReaderUiState {
    data object Loading : ReaderUiState
    data class Error(val message: String) : ReaderUiState
    data class Open(val document: Document, val publication: Publication, val savedProgress: Progress?) : ReaderUiState
}
```

- [ ] **Step 4: Implement `ReaderScreen`**

`app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt`:

```kotlin
package io.theficos.ereader.ui.reader

import android.view.View
import android.widget.FrameLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import io.theficos.ereader.reader.EpubNavigatorHost
import io.theficos.ereader.reader.LocatorUpdate
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator

@Composable
fun ReaderScreen(viewModel: ReaderViewModel) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }
    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            ReaderUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is ReaderUiState.Error -> Text(s.message, Modifier.align(Alignment.Center))
            is ReaderUiState.Open -> ReaderContent(state = s, onLocator = viewModel::publishLocator)
        }
    }
}

@Composable
private fun ReaderContent(state: ReaderUiState.Open, onLocator: (LocatorUpdate) -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context as FragmentActivity }
    val containerId = remember { View.generateViewId() }
    val host = remember(state.document.id) { EpubNavigatorHost(activity = activity, containerId = containerId) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            FrameLayout(ctx).apply { id = containerId; layoutParams = FrameLayout.LayoutParams(-1, -1) }
        },
        update = {
            // No-op; navigator is mounted in LaunchedEffect below.
        },
    )

    LaunchedEffect(state.document.id) {
        val initial = state.savedProgress?.let { savedToLocator(state, it.locator) }
        host.open(state.publication, initial)
        launch { host.updates.collect { onLocator(it) } }
    }
}

private fun savedToLocator(state: ReaderUiState.Open, raw: String): Locator? {
    // Phase 1 stored format: {"href":"...","percent":...}. Find the matching link by href; let Readium's percent positioner handle the rest.
    val hrefMatch = Regex("\"href\":\"([^\"]+)\"").find(raw) ?: return null
    val pctMatch = Regex("\"percent\":([0-9.eE+-]+)").find(raw)
    val href = hrefMatch.groupValues[1]
    val percent = pctMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    val link = state.publication.linkWithHref(href) ?: state.publication.readingOrder.firstOrNull() ?: return null
    return Locator(
        href = link.href,
        type = link.type ?: "application/xhtml+xml",
        locations = Locator.Locations(progression = percent, totalProgression = percent),
    )
}
```

> Method names like `linkWithHref` may differ in your Readium version. The intent is "look up a `Link` in the publication by href"; if the API has been renamed, swap accordingly.

- [ ] **Step 5: Wire library + reader into `AppNavGraph`**

Replace `AppNavGraph.kt`:

```kotlin
package io.theficos.ereader.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import io.theficos.ereader.di.AppContainer
import io.theficos.ereader.ui.catalog.CatalogScreen
import io.theficos.ereader.ui.catalog.CatalogViewModel
import io.theficos.ereader.ui.library.LibraryScreen
import io.theficos.ereader.ui.library.LibraryViewModel
import io.theficos.ereader.ui.reader.ReaderScreen
import io.theficos.ereader.ui.reader.ReaderViewModel
import io.theficos.ereader.ui.settings.SettingsScreen
import io.theficos.ereader.ui.settings.SettingsViewModel

@Composable
fun AppNavGraph(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "library") {
        composable("library") {
            val vm = remember { LibraryViewModel(container.documentRepository, container.progressRepository) }
            LibraryScreen(
                viewModel = vm,
                onOpenCatalog = { nav.navigate("catalog") },
                onOpenBook = { id -> nav.navigate("reader/$id") },
            )
        }
        composable("catalog") {
            val vm = remember {
                CatalogViewModel(container.opdsClient, container.bookDownloader, container.documentRepository, container.credentialStore)
            }
            CatalogScreen(
                viewModel = vm,
                onOpenLibrary = { nav.popBackStack("library", inclusive = false) },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            val vm = remember { SettingsViewModel(container.credentialStore) }
            SettingsScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
        composable(
            "reader/{docId}",
            arguments = listOf(navArgument("docId") { type = NavType.LongType }),
        ) { backStack ->
            val docId = backStack.arguments!!.getLong("docId")
            val vm = remember(docId) {
                ReaderViewModel(docId, container.documentRepository, container.progressRepository, container.readiumFactory)
            }
            ReaderScreen(viewModel = vm)
        }
    }
}
```

- [ ] **Step 6: Build and run on a device or emulator**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Install on a device with developer mode enabled:

Run: `./gradlew :app:installDebug`
Expected: success.

Launch the app and walk through the ship-gate scenario:

1. App opens to Library — empty.
2. Tap **Catalog** → see "Configure calibre-web in Settings first."
3. Tap **Settings** → enter your `calibreweb.baseUrl`, `calibreweb.username`, `calibreweb.password` from `local.properties`. Save.
4. Tap **Catalog** → root OPDS feed renders.
5. Drill into "All Books" (or wherever your calibre-web shows acquisitions).
6. Tap **Download** on any book. Progress indicator advances; row resets when done.
7. Back to **Library** → book appears.
8. Tap the book → reader opens, EPUB renders, you can swipe pages.
9. Read for a minute.
10. Press back to library → progress percentage is non-zero.
11. **Force-stop the app** (`adb shell am force-stop io.theficos.ereader`).
12. Re-launch → Library still shows the book with the same progress percentage.
13. Tap the book → reader opens to (approximately) the saved location.

If any step fails, fix and retry. The most likely sources of churn are the Readium API surface adaptations called out in Tasks 7 and 10.

- [ ] **Step 7: Run the full unit-test suite as a smoke check**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Commit**

```bash
git add app
git commit -m "feat(app): library + reader screens, end-to-end Phase 1 wiring"
```

- [ ] **Step 9: Tag the Phase 1 release**

```bash
git tag -a v0.1.0-phase1 -m "Phase 1: local reader (OPDS browse + download + Readium + local progress)"
```

---

## Phase 1 ship gate (verbatim from spec §4)

> Read a book end-to-end; progress survives app restart.

Demonstrated by Task 10 Step 6 (manual scenario). If that passes on a real device against the user's calibre-web instance, Phase 1 ships.

## Out of scope for Phase 1 (deferred to Phase 2+)

- Sync server (`opds-sync`) and any HTTP traffic to it.
- Authentik OIDC + PKCE (`AppAuth` integration).
- `pending_sync_ops` outbox table and `WorkManager` sync job.
- `POST /sync/v1/documents/alias` reconciliation.
- Annotations (highlights, notes, bookmarks).
- Phase 6 Calibre plugin.

These references appear here only so the engineer knows what *not* to build now. Phase 2 will add `:data:sync`, an Authentik-aware token store inside `:auth`, the outbox tables, and the WorkManager job.
