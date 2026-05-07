# Phase 1.5 — Readium Reader Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Phase 1 reader stubs with real Readium 3.0.0 wiring (mounted via `EpubNavigatorFragment` inside Compose), upgrade the locator format to `Locator.toJSON().toString()`, add global font-size + theme reader preferences, and verify the ship gate on a real device.

**Architecture:** The `:reader` module un-stubs `ReadiumFactory` (returns Readium `Publication`), deletes `EpubNavigatorHost.kt` (its job moves into `ReaderScreen`), drops the `LocatorUpdate` indirection, and exposes a SharedPreferences-backed `ReaderPreferencesStore`. The `:app` module's `ReaderScreen` mounts the navigator fragment via `AndroidView` + `FragmentContainerView` + `DisposableEffect`, collects `currentLocator` directly, and submits preference changes live. `MainActivity` reverts to `FragmentActivity`. `SettingsScreen` gains a "Reader" subsection.

**Tech Stack:** Kotlin 2.0 + Jetpack Compose, Readium Kotlin Toolkit 3.0.0 (`readium-shared`, `readium-streamer`, `readium-navigator`), AndroidX Fragment 1.8.x (transitive via `readium-navigator`), Robolectric 4.13 + JUnit 4 + Truth for the locator round-trip JVM test, SharedPreferences for the global reader preferences.

**Spec:** `docs/superpowers/specs/2026-04-28-phase-1-5-reader-integration.md`

---

## File structure

Per-module file change summary. Concrete code is in the tasks below.

`:reader`:

- Modified: `reader/src/main/java/io/theficos/ereader/reader/ReadiumFactory.kt` — un-stubbed; now returns Readium `Publication`. Remove the `EpubResource` data class declaration.
- **Removed:** `reader/src/main/java/io/theficos/ereader/reader/EpubNavigatorHost.kt` — stub no longer needed; `ReaderScreen` collects `EpubNavigatorFragment.currentLocator` directly.
- Modified: `reader/src/main/java/io/theficos/ereader/reader/ProgressTracker.kt` — drop `data class LocatorUpdate`; `attach()` takes `Flow<Locator>`; `serialize()` returns `Locator.toJSON().toString()`.
- Created: `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferences.kt` — `ReaderPreferences` data class + `ReaderTheme` enum + `toEpubPreferences()` extension.
- Created: `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferencesStore.kt` — SharedPreferences-backed store with `flow: StateFlow<ReaderPreferences>` and `update {}` mutator.
- Modified: `reader/src/test/java/io/theficos/ereader/reader/ProgressTrackerTest.kt` — switch test events from `LocatorUpdate` to real `Locator` instances.
- Created: `reader/src/test/java/io/theficos/ereader/reader/LocatorSerializationTest.kt` — Robolectric round-trip test (Readium uses `android.net.Uri` internally, so Robolectric is required).
- Modified: `reader/build.gradle.kts` — add `testImplementation(libs.robolectric)` + `testImplementation(libs.androidx.test.core)`.

`:app`:

- Modified: `app/src/main/java/io/theficos/ereader/MainActivity.kt` — extends `FragmentActivity` again.
- Modified: `app/build.gradle.kts` — add `implementation("androidx.fragment:fragment-ktx:1.8.4")` *if and only if* the build complains about `FragmentActivity` / `FragmentContainerView` not resolving (these usually arrive transitively via `readium-navigator`).
- Modified: `app/src/main/java/io/theficos/ereader/di/AppContainer.kt` — instantiate `ReaderPreferencesStore`.
- Modified: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderViewModel.kt` — holds Readium `Publication` (not the dropped `EpubResource`); exposes `preferences: StateFlow<ReaderPreferences>`; `publishLocator(Locator)`.
- Modified: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt` — full rewrite using `AndroidView` + `FragmentContainerView` + `DisposableEffect` to mount `EpubNavigatorFragment`, collect `currentLocator`, and submit live preferences.
- Modified: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt` — also exposes/edits reader preferences.
- Modified: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt` — adds the "Reader" subsection (font-size slider + theme radio group).

---

## Task 1: `ReaderPreferences` model in `:reader`

**Files:**
- Create: `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferences.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.theficos.ereader.reader

import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme

enum class ReaderTheme { LIGHT, DARK, SEPIA }

data class ReaderPreferences(
    val fontScale: Double = 1.0,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
) {
    init {
        require(fontScale in 0.5..2.0) { "fontScale out of range: $fontScale" }
    }
}

fun ReaderPreferences.toEpubPreferences(): EpubPreferences = EpubPreferences(
    fontSize = fontScale,
    theme = when (theme) {
        ReaderTheme.LIGHT -> Theme.LIGHT
        ReaderTheme.DARK -> Theme.DARK
        ReaderTheme.SEPIA -> Theme.SEPIA
    },
)
```

**API caveat for the implementer:** in Readium 3.0.0 `EpubPreferences` is a data class produced via `EpubPreferences(...)` builder-style calls. If the constructor signature differs (e.g., parameter names like `textNormalization` exist and are required, or `fontSize` is wrapped in a `Range<Double>`), adapt the call but keep this file's *own* public API stable: the `ReaderPreferences` data class and the `toEpubPreferences()` mapper. The `Theme` enum has been a stable part of `org.readium.r2.navigator.preferences` since 2.x.

- [ ] **Step 2: Compile-check the module**

Run: `./scripts/dgradle :reader:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add reader/src/main/java/io/theficos/ereader/reader/ReaderPreferences.kt
git commit -m "feat(reader): ReaderPreferences model + Readium EpubPreferences mapping"
```

---

## Task 2: `ReaderPreferencesStore` (SharedPreferences-backed)

**Files:**
- Create: `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferencesStore.kt`

- [ ] **Step 1: Write the file**

```kotlin
package io.theficos.ereader.reader

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ReaderPreferencesStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(load())
    val flow: StateFlow<ReaderPreferences> = _flow.asStateFlow()

    fun update(transform: (ReaderPreferences) -> ReaderPreferences) {
        val next = transform(_flow.value)
        prefs.edit()
            .putFloat(KEY_FONT_SCALE, next.fontScale.toFloat())
            .putString(KEY_THEME, next.theme.name)
            .apply()
        _flow.value = next
    }

    private fun load(): ReaderPreferences {
        val fontScale = prefs.getFloat(KEY_FONT_SCALE, DEFAULT_FONT_SCALE).toDouble()
            .coerceIn(0.5, 2.0)
        val themeName = prefs.getString(KEY_THEME, ReaderTheme.LIGHT.name) ?: ReaderTheme.LIGHT.name
        val theme = runCatching { ReaderTheme.valueOf(themeName) }.getOrDefault(ReaderTheme.LIGHT)
        return ReaderPreferences(fontScale = fontScale, theme = theme)
    }

    private companion object {
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_THEME = "theme"
        const val DEFAULT_FONT_SCALE = 1.0f
    }
}
```

No tests for the store itself — Robolectric SharedPreferences works trivially and the store has no logic worth covering beyond load/save. Ship-gate verifies the round-trip on device.

- [ ] **Step 2: Compile-check**

Run: `./scripts/dgradle :reader:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add reader/src/main/java/io/theficos/ereader/reader/ReaderPreferencesStore.kt
git commit -m "feat(reader): SharedPreferences-backed ReaderPreferencesStore"
```

---

## Task 3: locator round-trip test (failing) + `ProgressTracker` rewrite

**Files:**
- Modify: `reader/build.gradle.kts` — add Robolectric + androidx-test-core to test deps.
- Create: `reader/src/test/java/io/theficos/ereader/reader/LocatorSerializationTest.kt`
- Modify: `reader/src/main/java/io/theficos/ereader/reader/ProgressTracker.kt`
- Modify: `reader/src/test/java/io/theficos/ereader/reader/ProgressTrackerTest.kt`

- [ ] **Step 1: Add Robolectric to `:reader` test deps**

Edit `reader/build.gradle.kts`. Replace the `dependencies { ... }` block:

```kotlin
dependencies {
    api(project(":core:model"))
    api(project(":data:local"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
```

- [ ] **Step 2: Write the failing locator round-trip test**

Create `reader/src/test/java/io/theficos/ereader/reader/LocatorSerializationTest.kt`:

```kotlin
package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocatorSerializationTest {

    @Test fun `serialize then parse yields equivalent locator`() {
        val original = Locator(
            href = Url("/chapter01.xhtml")!!,
            mediaType = MediaType.XHTML,
            title = "Chapter 1",
            locations = Locator.Locations(
                progression = 0.42,
                totalProgression = 0.13,
                position = 7,
            ),
            text = Locator.Text(before = "before", highlight = "hl", after = "after"),
        )

        val encoded = ProgressTracker.serialize(original)
        val parsed = Locator.fromJSON(JSONObject(encoded))

        assertThat(parsed).isNotNull()
        assertThat(parsed!!.href.toString()).isEqualTo("/chapter01.xhtml")
        assertThat(parsed.locations.progression).isEqualTo(0.42)
        assertThat(parsed.locations.totalProgression).isEqualTo(0.13)
        assertThat(parsed.locations.position).isEqualTo(7)
        assertThat(parsed.text.highlight).isEqualTo("hl")
    }

    @Test fun `legacy phase 1 format is rejected`() {
        // {"href":"/x","percent":0.5} is the format the Phase 1 stub wrote; it has no "locations" key.
        val legacy = """{"href":"/x","percent":0.5}"""
        assertThat(ProgressTracker.parseOrNull(legacy)).isNull()
    }

    @Test fun `parseOrNull returns null on garbage input`() {
        assertThat(ProgressTracker.parseOrNull("not json at all")).isNull()
        assertThat(ProgressTracker.parseOrNull("{}")).isNull()
    }
}
```

- [ ] **Step 3: Run the test (expected fail-compile)**

Run: `./scripts/dgradle :reader:testDebugUnitTest --tests *LocatorSerializationTest*`
Expected: FAIL — `ProgressTracker.serialize` / `ProgressTracker.parseOrNull` unresolved (current implementations are instance-method `serialize(LocatorUpdate)`).

- [ ] **Step 4: Rewrite `ProgressTracker.kt`**

Replace the entire file `reader/src/main/java/io/theficos/ereader/reader/ProgressTracker.kt`:

```kotlin
package io.theficos.ereader.reader

import io.theficos.ereader.core.model.Progress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator

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

    fun attach(documentId: Long, locatorUpdates: Flow<Locator>) {
        this.documentId = documentId
        collectJob = scope.launch {
            locatorUpdates.collect { locator ->
                pending.value = Pending(locator, nowMs())
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
            locator = serialize(p.locator),
            percent = (p.locator.locations.totalProgression
                ?: p.locator.locations.progression
                ?: 0.0).coerceIn(0.0, 1.0),
            updatedAt = p.timestampMs,
        ))
    }

    private data class Pending(val locator: Locator, val timestampMs: Long)

    companion object {
        /** Encodes a Readium [Locator] as a JSON string for persistence and (Phase 2) sync. */
        fun serialize(locator: Locator): String =
            locator.toJSON().toString()

        /**
         * Returns a [Locator] reconstituted from a previously-[serialize]d string, or `null` if
         * the input is the Phase 1 legacy format, malformed JSON, or otherwise un-parseable.
         */
        fun parseOrNull(raw: String): Locator? = try {
            val json = JSONObject(raw)
            // Legacy Phase 1 stub wrote {"href":..., "percent":...} — no "locations" object.
            if (json.has("percent") && !json.has("locations")) null
            else Locator.fromJSON(json)
        } catch (_: Throwable) {
            null
        }
    }
}
```

The `data class LocatorUpdate(...)` declaration is removed entirely. Any in-tree references (`ProgressTrackerTest`, `EpubNavigatorHost.kt` — which we'll delete in Task 5 — or the Phase 1 `ReaderViewModel`/`ReaderScreen`) will fail compile until subsequent tasks update them.

- [ ] **Step 5: Update `ProgressTrackerTest` to use real `Locator`**

Replace `reader/src/test/java/io/theficos/ereader/reader/ProgressTrackerTest.kt`:

```kotlin
package io.theficos.ereader.reader

import com.google.common.truth.Truth.assertThat
import io.theficos.ereader.core.model.Progress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.mediatype.MediaType
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProgressTrackerTest {

    private fun locatorAt(href: String, totalProgression: Double): Locator =
        Locator(
            href = Url(href)!!,
            mediaType = MediaType.XHTML,
            locations = Locator.Locations(
                progression = totalProgression,
                totalProgression = totalProgression,
            ),
        )

    @Test fun `debounces saves to one per second`() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<Locator>(extraBufferCapacity = 16)
        val tracker = ProgressTracker(
            save = { saved += it },
            scope = backgroundScope,
            nowMs = { testScheduler.currentTime },
        )
        tracker.attach(documentId = 1L, locatorUpdates = locators)

        repeat(5) { locators.tryEmit(locatorAt("/ch1", 0.10 + it * 0.01)) }
        runCurrent()
        advanceTimeBy(50)
        assertThat(saved).isEmpty()

        advanceTimeBy(1_000)
        assertThat(saved).hasSize(1)
        assertThat(saved.last().percent).isWithin(0.001).of(0.14)
    }

    @Test fun `flushes immediately on detach`() = runTest(UnconfinedTestDispatcher()) {
        val saved = mutableListOf<Progress>()
        val locators = MutableSharedFlow<Locator>(extraBufferCapacity = 16)
        val tracker = ProgressTracker(
            save = { saved += it },
            scope = backgroundScope,
            nowMs = { testScheduler.currentTime },
        )
        tracker.attach(documentId = 1L, locatorUpdates = locators)
        locators.tryEmit(locatorAt("/ch1", 0.5))
        runCurrent()
        tracker.detach()
        assertThat(saved).hasSize(1)
        assertThat(saved.first().locator).contains("/ch1")
        assertThat(saved.first().percent).isEqualTo(0.5)
    }
}
```

- [ ] **Step 6: Run all `:reader` tests**

Run: `./scripts/dgradle :reader:testDebugUnitTest`
Expected: PASS (`LocatorSerializationTest` 3 tests + `ProgressTrackerTest` 2 tests).

- [ ] **Step 7: Commit**

```bash
git add reader/build.gradle.kts \
        reader/src/main/java/io/theficos/ereader/reader/ProgressTracker.kt \
        reader/src/test/java/io/theficos/ereader/reader/LocatorSerializationTest.kt \
        reader/src/test/java/io/theficos/ereader/reader/ProgressTrackerTest.kt
git commit -m "feat(reader): Locator-based ProgressTracker with JSON round-trip + legacy detect"
```

---

## Task 4: un-stub `ReadiumFactory`, delete `EpubNavigatorHost`

**Files:**
- Modify: `reader/src/main/java/io/theficos/ereader/reader/ReadiumFactory.kt`
- Delete: `reader/src/main/java/io/theficos/ereader/reader/EpubNavigatorHost.kt`

- [ ] **Step 1: Replace `ReadiumFactory.kt`**

```kotlin
package io.theficos.ereader.reader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

class ReadiumFactory(context: Context) {

    private val appContext = context.applicationContext
    private val httpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(appContext.contentResolver, httpClient)
    private val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = appContext,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = null,
        ),
    )

    suspend fun open(asset: EpubAsset): Publication = withContext(Dispatchers.IO) {
        val readiumAsset = assetRetriever.retrieve(asset.file).getOrNull()
            ?: error("AssetRetriever could not open ${asset.file}")
        publicationOpener.open(readiumAsset, allowUserInteraction = false).getOrNull()
            ?: error("PublicationOpener could not open ${asset.file}")
    }
}
```

The `EpubResource` data class declaration that lived inside the old stub file is removed. Callers (`ReaderViewModel`) will be updated in Task 6.

**API caveats** — these are the same surfaces the Phase 1 spec flagged as risky and the Phase 1 stub avoided:

- `DefaultHttpClient()` may take a `userAgent: String` or a `connectTimeout` argument depending on point release. If the no-arg constructor doesn't compile, pass a sensible default like `DefaultHttpClient(userAgent = "opds-ereader/0.1")`.
- `AssetRetriever(contentResolver, httpClient)` is one of two public constructors in 3.0.0 (we verified during Phase 1 build investigation). The other takes `(ResourceFactory, ArchiveOpener, FormatSniffer)` — only use that if the simpler one disappears.
- `DefaultPublicationParser(context, httpClient, assetRetriever, pdfFactory = null)` — `pdfFactory` is nullable in Kotlin source. If the compiler complains about `null`, the parameter has been made non-null; pass an empty PDF factory or skip the parser by constructing the streamer differently. As a fallback: drop `pdfFactory` entirely if the `null` arg is rejected and a no-pdf overload exists.
- `PublicationOpener.open(asset, allowUserInteraction = false).getOrNull()` returns `Publication?`. The `Try` type from Readium has `getOrNull()` (we verified during Phase 1).

- [ ] **Step 2: Delete `EpubNavigatorHost.kt`**

```bash
git rm reader/src/main/java/io/theficos/ereader/reader/EpubNavigatorHost.kt
```

(After Task 6 the consumers of these files will be updated.)

- [ ] **Step 3: Compile-check (expected fail — `:app` consumers not yet updated)**

Run: `./scripts/dgradle :reader:assembleDebug`
Expected: BUILD SUCCESSFUL for `:reader` itself. `:app:compileDebugKotlin` will fail later because Task 6/7/8 haven't landed; that's fine — we'll fix in those tasks.

- [ ] **Step 4: Commit**

```bash
git add reader/src/main/java/io/theficos/ereader/reader/ReadiumFactory.kt
git commit -m "feat(reader): real Readium publication opener; remove stub EpubNavigatorHost"
```

---

## Task 5: `MainActivity` reverts to `FragmentActivity`; wire `ReaderPreferencesStore` in `AppContainer`

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/MainActivity.kt`
- Modify: `app/src/main/java/io/theficos/ereader/di/AppContainer.kt`

- [ ] **Step 1: Update `MainActivity.kt`**

Replace the entire file:

```kotlin
package io.theficos.ereader

import android.os.Bundle
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

- [ ] **Step 2: Update `AppContainer.kt`**

Replace the entire file:

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
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import java.io.File

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val credentialStore: CalibreCredentialStore = CalibreCredentialStore(appContext)
    private val httpClient = OpdsHttpClient(credentialStore)
    val opdsClient: OpdsClient = OpdsClient(httpClient.okHttp)
    val bookDownloader: BookDownloader = BookDownloader(
        okHttp = httpClient.okHttp,
        booksDir = File(appContext.filesDir, "books"),
    )
    private val db: EReaderDatabase = EReaderDatabase.build(appContext)
    val documentRepository = DocumentRepository(db.documentDao())
    val progressRepository = ProgressRepository(db.progressDao())
    val readiumFactory = ReadiumFactory(appContext)
    val readerPreferencesStore = ReaderPreferencesStore(appContext)
}
```

- [ ] **Step 3: Compile-check (still expected to fail at `:app` until Tasks 6-8)**

Skip running until later tasks land. (You can run `./scripts/dgradle :app:compileDebugKotlin` and see remaining errors are limited to the reader UI files we'll touch next.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/MainActivity.kt \
        app/src/main/java/io/theficos/ereader/di/AppContainer.kt
git commit -m "feat(app): MainActivity FragmentActivity; AppContainer exposes ReaderPreferencesStore"
```

---

## Task 6: rewrite `ReaderViewModel`

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderViewModel.kt`

- [ ] **Step 1: Replace the file**

```kotlin
package io.theficos.ereader.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.core.model.Progress
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import io.theficos.ereader.reader.EpubAsset
import io.theficos.ereader.reader.ProgressTracker
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReadiumFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import java.io.File

class ReaderViewModel(
    private val documentId: Long,
    private val docs: DocumentRepository,
    private val progress: ProgressRepository,
    private val readium: ReadiumFactory,
    preferencesStore: ReaderPreferencesStore,
) : ViewModel() {

    private val _state = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _locatorUpdates = MutableSharedFlow<Locator>(extraBufferCapacity = 64)
    val locatorUpdates: SharedFlow<Locator> = _locatorUpdates.asSharedFlow()

    val preferences: StateFlow<ReaderPreferences> = preferencesStore.flow

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
            val initialLocator = savedProgress?.locator?.let { ProgressTracker.parseOrNull(it) }
            _state.value = ReaderUiState.Open(doc, publication, initialLocator, savedProgress)
            tracker.attach(documentId = doc.id, locatorUpdates = locatorUpdates)
        }
    }

    fun publishLocator(locator: Locator) {
        _locatorUpdates.tryEmit(locator)
    }

    override fun onCleared() {
        tracker.detach()
        super.onCleared()
    }
}

sealed interface ReaderUiState {
    data object Loading : ReaderUiState
    data class Error(val message: String) : ReaderUiState
    data class Open(
        val document: Document,
        val publication: Publication,
        val initialLocator: Locator?,
        val savedProgress: Progress?,
    ) : ReaderUiState
}
```

- [ ] **Step 2: Compile-check (expected: only `ReaderScreen.kt` and `AppNavGraph.kt` still fail)**

Run: `./scripts/dgradle :app:compileDebugKotlin`
Expected: failures contained to `ReaderScreen.kt` and possibly `AppNavGraph.kt`'s `ReaderViewModel(...)` constructor call (we added a new parameter).

- [ ] **Step 3: Update `AppNavGraph.kt` reader composable**

Edit `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`. Find the `composable("reader/{docId}")` block and replace its `remember(docId) { ReaderViewModel(...) }` line with:

```kotlin
            val vm = remember(docId) {
                ReaderViewModel(
                    documentId = docId,
                    docs = container.documentRepository,
                    progress = container.progressRepository,
                    readium = container.readiumFactory,
                    preferencesStore = container.readerPreferencesStore,
                )
            }
```

The surrounding lines (the `composable(...)` declaration and the `ReaderScreen(viewModel = vm)` call) stay identical.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/reader/ReaderViewModel.kt \
        app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt
git commit -m "feat(app): ReaderViewModel returns real Publication, exposes preferences flow"
```

---

## Task 7: rewrite `ReaderScreen` to mount `EpubNavigatorFragment`

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt`
- Possibly modify: `app/build.gradle.kts` (only if fragment APIs don't resolve transitively)

- [ ] **Step 1: Replace `ReaderScreen.kt`**

```kotlin
package io.theficos.ereader.ui.reader

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.toEpubPreferences
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

@Composable
fun ReaderScreen(viewModel: ReaderViewModel) {
    val state by viewModel.state.collectAsState()
    val preferences by viewModel.preferences.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }
    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            ReaderUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is ReaderUiState.Error -> Text(s.message, Modifier.align(Alignment.Center))
            is ReaderUiState.Open -> ReaderContent(
                publication = s.publication,
                initialLocator = s.initialLocator,
                preferences = preferences,
                onLocator = viewModel::publishLocator,
            )
        }
    }
}

@Composable
private fun ReaderContent(
    publication: Publication,
    initialLocator: Locator?,
    preferences: ReaderPreferences,
    onLocator: (Locator) -> Unit,
) {
    val activity = LocalContext.current as FragmentActivity
    val containerId = rememberSaveable { View.generateViewId() }
    val tag = "reader-${publication.metadata.identifier ?: containerId}"
    var fragment by remember { mutableStateOf<EpubNavigatorFragment?>(null) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            FragmentContainerView(ctx).apply {
                id = containerId
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        },
    )

    DisposableEffect(publication) {
        val fm = activity.supportFragmentManager
        val factory = EpubNavigatorFactory(publication)
        fm.fragmentFactory = factory.createFragmentFactory(
            initialLocator = initialLocator,
            initialPreferences = preferences.toEpubPreferences(),
        )
        val nav = (fm.fragmentFactory.instantiate(
            activity.classLoader,
            EpubNavigatorFragment::class.java.name,
        ) as EpubNavigatorFragment)
        fm.beginTransaction()
            .replace(containerId, nav, tag)
            .commitNow()
        fragment = nav

        val job = activity.lifecycleScope.launch {
            nav.currentLocator.collect { onLocator(it) }
        }

        onDispose {
            job.cancel()
            fragment = null
            fm.beginTransaction()
                .remove(nav)
                .commitNowAllowingStateLoss()
        }
    }

    LaunchedEffect(preferences) {
        fragment?.submitPreferences(preferences.toEpubPreferences())
    }
}
```

**API caveats — adapt if Readium 3.0.0 differs:**

- `EpubNavigatorFactory(publication).createFragmentFactory(initialLocator, initialPreferences, ...)` — the parameter set in 3.0.0 includes `initialLocator: Locator?`, `readingOrder: List<Link>? = null`, `initialPreferences: EpubPreferences = EpubPreferences()`, plus optional listeners. Pass only the two we use; the rest take their defaults.
- `EpubNavigatorFragment.currentLocator: StateFlow<Locator>` — non-nullable in 3.0.0. If your point release has it nullable, change `nav.currentLocator.collect { onLocator(it) }` to `nav.currentLocator.filterNotNull().collect { onLocator(it) }`.
- `fragment.submitPreferences(EpubPreferences)` — a member function on `EpubNavigatorFragment` since 3.0.0. If renamed to `applyPreferences` or moved to a `preferencesEditor`, adapt.
- The `EpubNavigatorFragment.Listener` interface had a required `onExternalLinkActivated` method that broke the Phase 1 stub when we tried to pass an empty implementation. We now pass *no* listener (the `createFragmentFactory` listener parameter has a default of `null`), so that whole method-set is skipped. If 3.0.0 made a listener required, supply one with an empty `onExternalLinkActivated` body.

- [ ] **Step 2: Build the app**

Run: `./scripts/dgradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

If the build fails complaining that `androidx.fragment.app.FragmentContainerView` or `androidx.fragment.app.FragmentActivity` cannot be resolved, add this line to `app/build.gradle.kts` inside the `dependencies { ... }` block:

```kotlin
    implementation("androidx.fragment:fragment-ktx:1.8.4")
```

…and rebuild. (Usually unnecessary because Readium navigator pulls fragment-ktx transitively, but check just in case.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt app/build.gradle.kts
git commit -m "feat(app): ReaderScreen mounts EpubNavigatorFragment + live preference updates"
```

---

## Task 8: Settings screen "Reader" subsection

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`

- [ ] **Step 1: Replace `SettingsViewModel.kt`**

```kotlin
package io.theficos.ereader.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.auth.CalibreCredentialStore
import io.theficos.ereader.auth.CalibreCredentials
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.ReaderPreferencesStore
import io.theficos.ereader.reader.ReaderTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val store: CalibreCredentialStore,
    private val readerStore: ReaderPreferencesStore,
) : ViewModel() {
    private val _calibre = MutableStateFlow(loadInitialCalibre())
    val calibre: StateFlow<CalibreUiState> = _calibre.asStateFlow()

    val readerPreferences: StateFlow<ReaderPreferences> = readerStore.flow

    private fun loadInitialCalibre(): CalibreUiState {
        val creds = store.get()
        return CalibreUiState(
            baseUrl = creds?.baseUrl.orEmpty(),
            username = creds?.username.orEmpty(),
            password = creds?.password.orEmpty(),
            saved = creds != null,
        )
    }

    fun onBaseUrlChange(value: String) { _calibre.value = _calibre.value.copy(baseUrl = value, saved = false) }
    fun onUsernameChange(value: String) { _calibre.value = _calibre.value.copy(username = value, saved = false) }
    fun onPasswordChange(value: String) { _calibre.value = _calibre.value.copy(password = value, saved = false) }

    fun saveCalibre() {
        val s = _calibre.value
        if (s.baseUrl.isBlank() || s.username.isBlank() || s.password.isBlank()) return
        viewModelScope.launch {
            store.put(CalibreCredentials(s.baseUrl.trim().trimEnd('/'), s.username, s.password))
            _calibre.value = s.copy(saved = true)
        }
    }

    fun setFontScale(value: Double) {
        readerStore.update { it.copy(fontScale = value.coerceIn(0.5, 2.0)) }
    }

    fun setTheme(theme: ReaderTheme) {
        readerStore.update { it.copy(theme = theme) }
    }
}

data class CalibreUiState(
    val baseUrl: String,
    val username: String,
    val password: String,
    val saved: Boolean,
)
```

- [ ] **Step 2: Replace `SettingsScreen.kt`**

```kotlin
package io.theficos.ereader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.theficos.ereader.reader.ReaderTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val calibre by viewModel.calibre.collectAsState()
    val reader by viewModel.readerPreferences.collectAsState()
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
        )
    }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("calibre-web", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = calibre.baseUrl,
                onValueChange = viewModel::onBaseUrlChange,
                label = { Text("calibre-web URL") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = calibre.username,
                onValueChange = viewModel::onUsernameChange,
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = calibre.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = viewModel::saveCalibre,
                enabled = calibre.baseUrl.isNotBlank() && calibre.username.isNotBlank() && calibre.password.isNotBlank(),
            ) {
                Text(if (calibre.saved) "Saved" else "Save")
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))

            Text("Reader", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)

            Text("Font size: ${"%.1fx".format(reader.fontScale)}")
            Slider(
                value = reader.fontScale.toFloat(),
                onValueChange = { viewModel.setFontScale(it.toDouble()) },
                valueRange = 0.5f..2.0f,
                steps = 14, // (2.0 - 0.5) / 0.1 - 1
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Theme")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReaderTheme.values().forEach { t ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 16.dp),
                    ) {
                        RadioButton(
                            selected = reader.theme == t,
                            onClick = { viewModel.setTheme(t) },
                        )
                        Text(t.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 3: Update Settings construction in `AppNavGraph.kt`**

Edit `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`. Find the `composable("settings") { ... }` block and replace the body with:

```kotlin
        composable("settings") {
            val vm = remember {
                SettingsViewModel(
                    store = container.credentialStore,
                    readerStore = container.readerPreferencesStore,
                )
            }
            SettingsScreen(viewModel = vm, onBack = { nav.popBackStack() })
        }
```

- [ ] **Step 4: Build**

Run: `./scripts/dgradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt \
        app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt \
        app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt
git commit -m "feat(app): Settings screen Reader subsection (font scale + theme)"
```

---

## Task 9: full test suite + ship-gate verification on a device

**Files:** none (verification only).

- [ ] **Step 1: Run all unit tests in Docker**

Run: `./scripts/dgradle :core:model:test :core:identity:test :data:local:testDebugUnitTest :data:opds:testDebugUnitTest :reader:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. (`:auth:testDebugUnitTest` is still deferred per Phase 1 carry-overs.)

- [ ] **Step 2: Build the APK**

Run: `./scripts/dgradle :app:assembleDebug`
Expected: BUILD SUCCESSFUL. APK at `app/build/outputs/apk/debug/app-debug.apk`.

- [ ] **Step 3: Install on the connected device**

Run: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
Expected: `Success`.

Run: `adb shell am start -n io.theficos.ereader/.MainActivity`
Expected: app launches.

- [ ] **Step 4: Walk the spec ship-gate (§11), step by step**

Each sub-step is observed on the phone:

1. App opens to **Library**. Either empty (fresh install) or showing previously-downloaded books (upgrade).
2. Tap **Catalog**. If creds are set, OPDS feed renders. Otherwise: tap **Settings**, fill in calibre creds, **Save**, back to Catalog.
3. Drill into a sub-feed. Tap **Download** on a book. Spinner advances; row resets to **Downloaded** when done. Open Library — book appears.
4. Tap the book. The **Readium navigator renders the EPUB**. Swipe pages forward and back. Read until roughly 30% of the way through.
5. Press the back gesture / system back. Library shows the row updated to ~30%.
6. Tap **Settings → Reader**. Drag font-size slider to ~1.4x. Tap **Sepia** theme.
7. Tap the same book. The reader opens at ~30% **and** the new font size + sepia theme are visible immediately.
8. Force-stop: `adb shell am force-stop io.theficos.ereader`.
9. Re-launch from the launcher. Library shows ~30%.
10. Tap the book. Reader opens at the saved locator with the chosen font size + sepia theme.

If any sub-step fails, capture `adb logcat -d --pid=$(adb shell pidof io.theficos.ereader) | tail -200` into a scratch file and fix iteratively. Common likely failure modes:

- **Crash mounting `EpubNavigatorFragment`** — usually `EpubNavigatorFragment.Listener` required-method issue, or `submitPreferences` rename. Adjust per the Task 7 caveats.
- **Navigator opens but no pages render** — Readium 3.0.0 dark-mode WebView interaction (see spec §12). Try `LIGHT` theme first; if that works and `DARK` doesn't, configure the navigator to opt out of automatic WebView force-dark.
- **Locator parsed back as `null` from a known-good progress row** — the legacy-detect heuristic in `ProgressTracker.parseOrNull` is too aggressive. Print the `raw` string for the affected row and adjust the legacy check.

- [ ] **Step 5: Tag the Phase 1.5 release**

```bash
git tag -a v0.1.5-phase1.5 -m "Phase 1.5: Readium reader integration + global font/theme prefs"
```

- [ ] **Step 6: Done**

Phase 1.5 ships when all 10 sub-steps of step 4 pass on a real device. Ready to begin Phase 2 brainstorming next.

---

## Self-review

Spec coverage:

- §1 goals (un-stub factory, mount fragment, locator format, prefs) → Tasks 1, 2, 3, 4, 6, 7, 8.
- §2 non-goals → no tasks (correctly out of scope).
- §3 architecture & module impact → all module-level changes are accounted for in Tasks 1–8 file lists.
- §4.1 ReadiumFactory → Task 4 step 1.
- §4.2 LocatorUpdate removal → Task 3.
- §4.3 fragment hosting → Task 7.
- §4.4 live preference updates → Task 7 step 1 (the trailing `LaunchedEffect(preferences)`).
- §4.5 locator wire format + legacy detect → Task 3 step 4 (`serialize`, `parseOrNull`).
- §5.1 ReaderPreferences model → Task 1.
- §5.2 SharedPreferences storage → Task 2.
- §5.3 Settings UI → Task 8.
- §6 UI flow → Task 7 + Task 9 step 4.
- §7 data flow → covered implicitly by Tasks 6, 7.
- §8 lifecycle/edge cases → Task 7 (`DisposableEffect`, `commitNowAllowingStateLoss`) + Task 6 (`parseOrNull` returning null on legacy).
- §9 testing → Tasks 3 (locator round-trip) and 9 (manual ship-gate).
- §10 out-of-scope → no tasks needed.
- §11 ship gate → Task 9 step 4.
- §12 risks → Task 7 caveats + Task 9 step 4 fallbacks.
- Appendix A file list → matches Tasks 1–8 file lists.
- Appendix B carry-overs → confirmed staying in place; no task needed.

Placeholder scan: clean — no "TBD"/"TODO" except the genuine API caveats clearly marked as such, with concrete fallback instructions.

Type consistency:

- `ReaderPreferences` is the type referenced in Tasks 1, 2, 6, 8.
- `ProgressTracker.serialize(Locator): String` and `ProgressTracker.parseOrNull(String): Locator?` defined Task 3, used Tasks 6 (ViewModel restore) and 3 (test).
- `ReadiumFactory.open(EpubAsset): Publication` defined Task 4, used Task 6.
- `ReaderViewModel(documentId, docs, progress, readium, preferencesStore)` constructor signature defined Task 6, called Task 6 step 3.
- `SettingsViewModel(store, readerStore)` defined Task 8, called Task 8 step 3.
- `ReaderUiState.Open(document, publication, initialLocator, savedProgress)` defined Task 6, deconstructed Task 7 (`s.publication`, `s.initialLocator`).
- `ReaderTheme` enum with `LIGHT/DARK/SEPIA` defined Task 1, used Tasks 1, 2, 8.

All references resolved. Plan ready.
