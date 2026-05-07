# Phase 1.5 — Readium Reader Integration

**Date:** 2026-04-28
**Status:** Draft, brainstorm-approved
**Scope:** Replace the Phase 1 reader stubs with real Readium 3.0.0 wiring. Ship gate: read an EPUB end-to-end on device, change font size and theme, force-stop the app, re-launch, observe progress survives.

This is a small, focused phase between Phase 1 (local reader, mostly shipped) and Phase 2 (sync server). The Readium reader was stubbed during Phase 1 because the 3.0.0 API surface diverged enough from the original plan to need hands-on iteration on a device. Phase 1.5 closes that gap.

## 1. Goals

- Un-stub `ReadiumFactory` to return a real Readium `Publication`; delete the no-longer-needed `EpubNavigatorHost` stub and have `ReaderScreen` mount the `EpubNavigatorFragment` itself inside the existing Compose UI.
- Wire the navigator's `currentLocator` `StateFlow` into `ProgressTracker` so reading actually generates locator events and progress is persisted.
- Upgrade the persisted locator format from the Phase 1 minimal `{"href":..., "percent":...}` blob to Readium's `Locator.toJSON().toString()`. Phase 2 reuses this string verbatim as the wire format for `/sync/v1/progress`.
- Add a global `ReaderPreferences` model (font size + theme) and a "Reader" subsection in the existing Settings screen. Preferences apply to every book; the reader applies them live whenever they change.

## 2. Non-goals

- **Per-book preference overrides.** Global only.
- **In-reader UX surfaces** beyond rendering pages: no TOC drawer, no search, no bookmarks shortcut, no in-reader settings popup. Settings live on the Settings screen; the reader is purely for reading.
- **Page-turn animations, custom fonts, justification controls, scroll-vs-paginated toggle.** All deferred. Whatever Readium's defaults provide is what we ship.
- **PDF support.** EPUB only — same as Phase 1.
- **TTS, accessibility services, screen brightness controls.** Deferred.
- **Instrumented end-to-end tests.** Manual on-device verification only, plus one JVM unit test for the locator round-trip.

## 3. Architecture & module impact

No new modules. Three modules change:

| Module | Change |
|---|---|
| `:reader` | Un-stub `ReadiumFactory` (returns Readium `Publication`); delete `EpubNavigatorHost.kt` (its job moves into `ReaderScreen`); delete `LocatorUpdate` data class. `ProgressTracker.attach(Flow<Locator>)` and `serialize()` use Readium's `Locator.toJSON().toString()`. Add `ReaderPreferences` data class + `ReaderPreferencesStore` (SharedPreferences-backed) + `flow: StateFlow<ReaderPreferences>`. New JVM unit test for locator round-trip. |
| `:app` | `ReaderViewModel` resolves a Readium `Publication` and exposes preferences via `StateFlow`. `ReaderScreen` switches to `AndroidView { FragmentContainerView }` hosting an `EpubNavigatorFragment`. `MainActivity` reverts to `FragmentActivity`. `SettingsScreen` gets a "Reader" subsection (font size slider + theme radio group). `:app/build.gradle.kts` keeps the existing deps; `androidx.fragment` arrives transitively via `readium-navigator`. |
| `:auth` | Untouched. |

The Phase 1 fixes (Readium 3.0.0 API drift in `:data:opds`, core library desugaring, Docker AAPT2 platform override, `extractIdentity` Android-XML compat, etc.) all stay.

## 4. Readium 3.0.0 wiring

### 4.1 `ReadiumFactory`

Owns the long-lived Readium plumbing — one instance per process, constructed in `AppContainer`.

```kotlin
class ReadiumFactory(context: Context) {

    private val httpClient: HttpClient = DefaultHttpClient()
    private val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    private val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context = context,
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

`Publication` becomes the source of truth for the book — `:reader` re-exposes it to `:app` (it's already an API-level type via Readium's package).

### 4.2 Removing the `LocatorUpdate` indirection

The Phase 1 stub introduced `data class LocatorUpdate(href, positionPercent)` because there was no real `Locator` to pass around. Phase 1.5 retires it: `ProgressTracker.attach(...)` now takes `Flow<Locator>` directly, and `serialize(Locator)` returns `Locator.toJSON().toString()`. The `EpubNavigatorHost` stub is removed entirely — its only consumer was `ReaderScreen`, and once `ReaderScreen` mounts the real fragment it can collect `fragment.currentLocator` itself and forward to `ReaderViewModel.publishLocator(locator: Locator)`.

### 4.3 Mounting the fragment from Compose

Use `AndroidView` + `FragmentContainerView` (the modern Android idiom — `FragmentContainerView` correctly defers fragment commits past inflation).

```kotlin
@Composable
private fun ReaderContent(
    publication: Publication,
    initialLocator: Locator?,
    preferences: EpubPreferences,
    onLocator: (Locator) -> Unit,
) {
    val activity = LocalContext.current as FragmentActivity
    val containerId = rememberSaveable { View.generateViewId() }
    val factory = remember(publication) { EpubNavigatorFactory(publication) }
    val fragmentTag = "reader-${publication.metadata.identifier ?: containerId}"

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            FragmentContainerView(ctx).apply {
                id = containerId
                layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            }
        },
    )

    DisposableEffect(publication) {
        val fm = activity.supportFragmentManager
        val existing = fm.findFragmentByTag(fragmentTag) as? EpubNavigatorFragment
        val fragment = existing ?: run {
            fm.fragmentFactory = factory.createFragmentFactory(
                initialLocator = initialLocator,
                initialPreferences = preferences,
            )
            (fm.fragmentFactory.instantiate(activity.classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment).also {
                fm.beginTransaction().add(containerId, it, fragmentTag).commitNow()
            }
        }
        // collect currentLocator on the activity's lifecycleScope; unsubscribe on dispose
        val job = activity.lifecycleScope.launch {
            fragment.currentLocator.collect(onLocator)
        }
        onDispose {
            job.cancel()
            fm.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
        }
    }
}
```

The `DisposableEffect` keying on `publication` is correct: a new publication means a new fragment. Recompositions for preference changes don't re-key — they just call `fragment.submitPreferences(...)` (handled outside this `AndroidView`).

### 4.4 Live preference updates

`ReaderViewModel` exposes `preferences: StateFlow<EpubPreferences>` derived from `ReaderPreferencesStore.flow`. `ReaderScreen` collects it and, whenever it changes, calls `fragment.submitPreferences(prefs)`. Implementation detail: keep a `remember`-ed reference to the mounted fragment and a `LaunchedEffect(preferences)` that submits.

### 4.5 Locator wire format

`ProgressTracker.serialize(locator: Locator): String` returns `locator.toJSON().toString()`. Restore (`ReaderViewModel.savedToLocator(raw: String)`) calls `Locator.fromJSON(JSONObject(raw))`. If parsing fails *or* the JSON looks like the Phase 1 legacy format (has `"percent"` at the top level, no `"locations"` field), return null and the reader starts from the beginning. Phase 1 legacy rows are silently treated as "no progress" — there are at most a handful of test rows on any device.

The `progress.locator` Room column is `String NOT NULL`. The schema doesn't change. Phase 2's server stores this string opaquely and does its own opaque LWW per §5.4 / §6.3 of the master spec.

## 5. Reader preferences

### 5.1 Data model

```kotlin
data class ReaderPreferences(
    val fontScale: Double,            // 0.5 .. 2.0; default 1.0
    val theme: ReaderTheme,           // LIGHT, DARK, SEPIA; default LIGHT
)

enum class ReaderTheme { LIGHT, DARK, SEPIA }
```

`ReaderPreferences.toEpubPreferences()` maps to Readium's `EpubPreferences(fontSize, theme)`. The Readium `Theme` enum already has `LIGHT`/`DARK`/`SEPIA` values; map 1:1.

### 5.2 Storage

`ReaderPreferencesStore`:

```kotlin
class ReaderPreferencesStore(context: Context) {
    private val prefs = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
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

    private fun load(): ReaderPreferences = ReaderPreferences(
        fontScale = prefs.getFloat(KEY_FONT_SCALE, 1.0f).toDouble(),
        theme = ReaderTheme.valueOf(prefs.getString(KEY_THEME, ReaderTheme.LIGHT.name) ?: ReaderTheme.LIGHT.name),
    )
}
```

Plain `SharedPreferences` (not encrypted — these aren't secrets). One process-wide instance constructed in `AppContainer`.

### 5.3 Settings UI

`SettingsScreen` gains a "Reader" `ListItem` group below the existing calibre-web fields:

```
Reader
├─ Font size       [slider 0.5x ─────●─── 2.0x]   (current value displayed: "1.2x")
└─ Theme           ( ) Light  ( ) Dark  (●) Sepia
```

`SettingsViewModel` collects from `ReaderPreferencesStore.flow` in addition to the existing `CalibreCredentialStore`. New methods: `setFontScale(Double)`, `setTheme(ReaderTheme)`. Both write through the store; the StateFlow re-emits and the `ReaderScreen` (if open in another nav stack) picks up the change live.

## 6. UI flow

The reader screen replaces the Phase 1 stub. No new routes; same `reader/{docId}` path.

```
Library  ──tap a book──▶  Reader (real EPUB rendered)
                              │
                              ◀── back gesture / nav-up ── back to Library, progress saved
                              ◀── force-stop, relaunch ── reader resumes at saved locator
                              ◀── change font/theme in Settings ── reader applies live
```

## 7. Data flow

```
       ReaderPreferencesStore.flow ────────────────────┐
                                                        ▼
[Library] ─→ ReaderViewModel.load(docId) ─→ ReadiumFactory.open() ─→ Publication
                       │                                                    │
                       ▼                                                    ▼
                 ProgressRepository.get(docId)                   ReaderScreen mounts EpubNavigatorFragment
                       │                                                    │
                       ▼                                                    ▼
                 initialLocator                                fragment.currentLocator
                                                                            │
                                                                            ▼
                                                              ReaderViewModel.publishLocator
                                                                            │
                                                                            ▼
                                                                     ProgressTracker
                                                                  (1s debounce, save)
                                                                            │
                                                                            ▼
                                                                  ProgressRepository
```

`ReaderViewModel.preferences` is collected by `ReaderScreen`, which calls `fragment.submitPreferences()` whenever it changes.

## 8. Lifecycle and edge cases

- **Background → foreground inside the reader:** `AndroidView` + fragment survive normally; the `currentLocator` flow re-emits the latest position on `STARTED`. No special handling needed.
- **Configuration change (rotation):** `FragmentContainerView` + `commitNow` handle this; the fragment is re-attached automatically. The `EpubNavigatorFragment` retains its WebView state across rotation.
- **Process death while reading:** unsaved progress within the last 1s debounce window is lost. Acceptable. On restart, the last persisted locator is restored from Room.
- **Force-stop:** same as process death. The 1s debounce window is the worst-case data loss.
- **Corrupt EPUB:** `PublicationOpener.open(...)` returns `null`; `ReaderViewModel` surfaces `ReaderUiState.Error("Failed to open book")`. User can navigate back; the bad row remains in the library (Phase 2 / later UX adds a "remove from library" action).
- **Legacy locator JSON in Room:** silently treated as "no saved position" per §4.5. Reader starts at first chapter.

## 9. Testing

- **JVM unit test in `:reader`** — `LocatorSerializationTest`: build a synthetic `Locator` (any href + locations.progression + total + a text fragment), call `ProgressTracker.serialize()`, parse it back via `Locator.fromJSON(JSONObject(...))`, assert all fields round-trip. ~15 lines.
- **Manual device verification** — the ship-gate scenario in §11 below. No instrumented tests.

## 10. Out of scope (deferred)

- Bookmarks, highlights, notes (Phases 3–4 per master spec).
- TOC / chapter-list navigation drawer (next phase per §1 of this spec).
- PDF support (Phase 5 per master spec, may be dropped).
- Sync of progress to a server (Phase 2 — what comes next).
- Per-book preference overrides.
- Settings UI in-reader (popup / drawer).

## 11. Ship gate

On a real Android device, with calibre-web reachable:

1. Fresh install of the Phase 1.5 APK; existing Phase 1 install upgraded in place is also acceptable (legacy locators reset to "from beginning" — see §4.5).
2. Open Catalog → download an EPUB → it appears in Library marked "Downloaded".
3. Tap the book → the EPUB renders in the Readium navigator. Swipe pages forward and back. Reach roughly 30%.
4. Press back → Library shows progress around 30%.
5. Open Settings → Reader → drag font-size slider to ~1.4x; tap "Sepia" theme.
6. Tap the same book again → reader opens at the previously-saved position **and** with the new font size and sepia theme applied immediately.
7. Force-stop the app: `adb shell am force-stop io.theficos.ereader`.
8. Re-launch from the launcher → Library shows the same progress.
9. Tap the book → reader opens at (approximately) the saved locator.

If all nine steps pass, Phase 1.5 ships and Phase 2 begins.

## 12. Open risks

- **Readium `EpubPreferences` API surface.** The 3.0.0 release notes mention a preferences refactor; the actual class name and constructor for theme + font might differ slightly from `EpubPreferences(fontSize = ..., theme = ...)`. Resolved hands-on; the spec's data model (`ReaderPreferences` with two fields) is stable regardless.
- **WebView fonts and dark mode interaction.** Readium's "Dark" theme on some devices triggers Android's automatic WebView dark mode and renders illegibly. If observed during ship-gate verification, disable the Android-level WebView force-dark in the navigator config.
- **Rotation while reading.** Not a documented issue but historically a source of fragment-restoration bugs in Readium projects. If observed, add `android:configChanges="orientation|screenSize|screenLayout"` to `MainActivity` in the manifest as a workaround (Compose handles the rest).

## Appendix A — file change list

- Modified: `reader/src/main/java/io/theficos/ereader/reader/ReadiumFactory.kt` (un-stub; returns `Publication`).
- Removed: `reader/src/main/java/io/theficos/ereader/reader/EpubNavigatorHost.kt` — the stub had no real responsibilities. `ReaderScreen` now collects `EpubNavigatorFragment.currentLocator` directly.
- Modified: `reader/src/main/java/io/theficos/ereader/reader/ProgressTracker.kt` — `attach()` now takes `Flow<Locator>`; `serialize()` is now `(Locator) -> String` returning `Locator.toJSON().toString()`. The `LocatorUpdate` data class is deleted; tests construct real `Locator` instances.
- Modified: `reader/src/main/java/io/theficos/ereader/reader/EpubAsset.kt` — unchanged (already minimal).
- Removed: the `EpubResource` data class declaration inside `ReadiumFactory.kt`. The stub-only intermediary is gone; callers receive `Publication` directly.
- Added: `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferences.kt`.
- Added: `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferencesStore.kt`.
- Added: `reader/src/test/java/io/theficos/ereader/reader/LocatorSerializationTest.kt`.
- Modified: `app/src/main/java/io/theficos/ereader/MainActivity.kt` — back to `FragmentActivity`.
- Modified: `app/src/main/java/io/theficos/ereader/di/AppContainer.kt` — instantiate `ReaderPreferencesStore`.
- Modified: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderViewModel.kt` — holds `Publication`, exposes `preferences: StateFlow`.
- Modified: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt` — full rewrite: `AndroidView` + `FragmentContainerView` + `DisposableEffect`.
- Modified: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt` — also exposes/edits reader preferences.
- Modified: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt` — adds the "Reader" subsection.

## Appendix B — Phase-1-to-Phase-1.5 carry-overs

Confirmed staying in place:

- Docker / `linux/amd64` build via `scripts/dgradle`.
- Core library desugaring on `:app`, `:data:opds`, `:reader`.
- `extractIdentity` Android-XML compatibility.
- Catalog "Downloaded" marker and visible download error.
- `:auth:testDebugUnitTest` deferred (Robolectric Keystore setup not yet wired).
- All Phase 1 unit tests still pass after Phase 1.5 lands.
