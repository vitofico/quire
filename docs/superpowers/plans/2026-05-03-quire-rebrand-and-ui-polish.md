# Quire Rebrand & UI/UX Polish — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the design from `docs/superpowers/specs/2026-05-03-quire-rebrand-and-ui-polish.md` — Quire identity, hybrid aesthetic with oxblood accent, book covers throughout, real reader chrome with auto-hide, bottom-nav scaffold, settings restyled into cards.

**Architecture:** UI/identity refresh layered on the existing module graph. Data-layer changes are limited to: (a) extracting cover URLs in `:data:opds`, (b) downloading cover bytes alongside the EPUB, (c) one Room migration adding `coverPath` to `documents`. All other work is `:app` (Compose theme + redesigned screens) and `:reader` (chrome + extended preferences). Kotlin package names stay `io.theficos.ereader.*` — only Android `applicationId`/`namespace` and the launcher metadata change.

**Tech Stack:** Kotlin, Jetpack Compose (Material3), Coil (new dependency for cover image loading), Readium 3.0 (unchanged), Room (one migration v1→v2), bundled Lora TTFs.

---

## File map

**New files**

| File | Responsibility |
|---|---|
| `app/src/main/res/values/colors.xml` | Light-theme color tokens (raw hex). |
| `app/src/main/res/values-night/colors.xml` | Dark-theme color tokens. |
| `app/src/main/res/font/lora_regular.ttf` | Bundled Lora Regular 400. |
| `app/src/main/res/font/lora_semibold.ttf` | Bundled Lora SemiBold 600. |
| `app/src/main/res/font/lora.xml` | XML font family declaration. |
| `app/src/main/res/raw/lora_ofl.txt` | Lora Open Font License (compliance). |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Vector — italic lowercase `q`. |
| `app/src/main/res/drawable/ic_launcher_background.xml` | Vector — oxblood radial gradient. |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive icon descriptor. |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` | Round adaptive icon descriptor. |
| `app/src/main/java/io/theficos/ereader/ui/theme/QuireColors.kt` | Compose `lightColorScheme`/`darkColorScheme` from tokens. |
| `app/src/main/java/io/theficos/ereader/ui/theme/QuireTypography.kt` | `Typography` mapping with Lora and system sans. |
| `app/src/main/java/io/theficos/ereader/ui/theme/QuireShapes.kt` | `Shapes` for cards/buttons. |
| `app/src/main/java/io/theficos/ereader/ui/components/CoverImage.kt` | Composable: AsyncImage with deterministic gradient-fallback. |
| `app/src/main/java/io/theficos/ereader/ui/components/QuireCard.kt` | Outlined card wrapper used in Library hero, Settings groups. |
| `app/src/main/java/io/theficos/ereader/ui/components/SectionLabel.kt` | "LIBRARY · 12" small-caps label. |
| `app/src/main/java/io/theficos/ereader/ui/main/MainScaffold.kt` | Scaffold with bottom nav and tab destinations. |
| `app/src/main/java/io/theficos/ereader/ui/library/ContinueReadingCard.kt` | Hero card composable. |
| `app/src/main/java/io/theficos/ereader/ui/reader/ReaderChrome.kt` | Top + bottom bars + auto-hide controller. |
| `app/src/main/java/io/theficos/ereader/ui/reader/FontSettingsSheet.kt` | Bottom-sheet content. |

**Modified files**

| File | Change |
|---|---|
| `app/build.gradle.kts` | `applicationId`/`namespace` → `io.theficos.quire`; add Coil dependency. |
| `gradle/libs.versions.toml` | Add Coil version + library entry. |
| `app/src/main/AndroidManifest.xml` | `android:icon` and `android:roundIcon` references; theme reference. |
| `app/src/main/res/values/strings.xml` | `app_name` → "Quire". |
| `app/src/main/res/values/themes.xml` | `Theme.Quire` parent (no-action-bar, light status bar). |
| `app/src/main/res/values-night/themes.xml` | Dark variant (new file, see plan). |
| `app/src/main/java/io/theficos/ereader/ui/theme/Theme.kt` | Wire color tokens, typography, shapes. |
| `app/src/main/java/io/theficos/ereader/MainActivity.kt` | Set status-bar color from theme. |
| `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt` | Restructure: bottom-nav root vs. fullscreen reader destination. |
| `app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt` | Full redesign — hero + cover grid. |
| `app/src/main/java/io/theficos/ereader/ui/library/LibraryViewModel.kt` | Expose `continueReading: StateFlow<LibraryRow?>`. |
| `app/src/main/java/io/theficos/ereader/ui/catalog/CatalogScreen.kt` | Full redesign — cover grid with download badges. |
| `app/src/main/java/io/theficos/ereader/ui/catalog/CatalogViewModel.kt` | Pass `coverUrl` through download flow. |
| `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt` | Group fields into `QuireCard`s; add About card. |
| `app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt` | Expose new reader-default fields. |
| `app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt` | Add chrome overlay. |
| `app/src/main/java/io/theficos/ereader/ui/reader/ReaderViewModel.kt` | Chrome state, jump-to-locator action. |
| `data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsCatalog.kt` | Rename `coverHref` → `coverUrl`; same nullable `String?` shape. |
| `data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsClient.kt` | Extract cover URL from publication links. |
| `data/opds/src/main/java/io/theficos/ereader/data/opds/BookDownloader.kt` | Add `downloadCover(url, fileName): File?` helper. |
| `data/opds/src/test/java/io/theficos/ereader/data/opds/OpdsClientTest.kt` | Assert `coverUrl` extracted. |
| `data/opds/src/test/java/io/theficos/ereader/data/opds/BookDownloaderTest.kt` | Add cover-download test. |
| `core/model/src/main/java/io/theficos/ereader/core/model/Document.kt` | Add `coverPath: String?` field. |
| `data/local/src/main/java/io/theficos/ereader/data/local/db/DocumentEntity.kt` | Add `coverPath: String?`. |
| `data/local/src/main/java/io/theficos/ereader/data/local/db/EReaderDatabase.kt` | Bump version 1→2; real `Migration(1, 2)` adds column. |
| `data/local/src/main/java/io/theficos/ereader/data/local/DocumentRepository.kt` | Plumb `coverPath`. |
| `data/local/src/test/java/io/theficos/ereader/data/local/db/DocumentDaoTest.kt` | Cover path round-trip. |
| `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferences.kt` | Add `fontFamily: ReaderFontFamily`, `lineSpacing: Double`. |
| `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferencesStore.kt` | Persist new fields. |

---

## Task list (TDD where data-layer; UI tasks are direct-write + visual review)

Tasks are ordered so foundations land first and UI tasks can rebase cleanly. Commit after each task.

---

### Task 1: Add Coil dependency and bundle Lora fonts

**Why first:** Library and Catalog redesigns need both. No code dependency on this — but UI tasks will reach for `coil-compose` and `Font(R.font.lora_semibold)` so we want them already wired.

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/res/font/lora.xml`
- Add (binary): `app/src/main/res/font/lora_regular.ttf`
- Add (binary): `app/src/main/res/font/lora_semibold.ttf`
- Add (text): `app/src/main/res/raw/lora_ofl.txt`

- [ ] **Step 1: Download Lora TTFs and OFL license**

Run from repo root:

```bash
mkdir -p app/src/main/res/font app/src/main/res/raw
curl -L -o /tmp/lora.zip 'https://fonts.google.com/download?family=Lora'
unzip -j /tmp/lora.zip 'static/Lora-Regular.ttf' -d app/src/main/res/font/
unzip -j /tmp/lora.zip 'static/Lora-SemiBold.ttf' -d app/src/main/res/font/
unzip -p /tmp/lora.zip 'OFL.txt' > app/src/main/res/raw/lora_ofl.txt
mv app/src/main/res/font/Lora-Regular.ttf app/src/main/res/font/lora_regular.ttf
mv app/src/main/res/font/Lora-SemiBold.ttf app/src/main/res/font/lora_semibold.ttf
ls -la app/src/main/res/font/
```

Expected: `lora_regular.ttf` and `lora_semibold.ttf` in `app/src/main/res/font/`, each non-empty (~120 KB).

- [ ] **Step 2: Create the font family XML**

Write `app/src/main/res/font/lora.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<font-family xmlns:app="http://schemas.android.com/apk/res-auto">
    <font
        app:fontStyle="normal"
        app:fontWeight="400"
        app:font="@font/lora_regular" />
    <font
        app:fontStyle="normal"
        app:fontWeight="600"
        app:font="@font/lora_semibold" />
</font-family>
```

- [ ] **Step 3: Add Coil to the version catalog**

Edit `gradle/libs.versions.toml`:

In `[versions]` add:
```toml
coil = "2.7.0"
```

In `[libraries]` add:
```toml
coil-compose = { module = "io.coil-kt:coil-compose", version.ref = "coil" }
```

- [ ] **Step 4: Reference Coil in `:app`**

Edit `app/build.gradle.kts` — in the `dependencies { ... }` block, after the existing `compose-material-icons-extended` line:

```kotlin
    implementation(libs.coil.compose)
```

- [ ] **Step 5: Build to confirm dependency resolution**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts \
  app/src/main/res/font/ app/src/main/res/raw/lora_ofl.txt
git commit -m "feat: bundle Lora font and add Coil dependency"
```

---

### Task 2: Color tokens and theme XML

**Files:**
- Create: `app/src/main/res/values/colors.xml`
- Create: `app/src/main/res/values-night/colors.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/values-night/themes.xml`

- [ ] **Step 1: Write light-mode tokens**

Create `app/src/main/res/values/colors.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="quire_surface">#FFF8F4EC</color>
    <color name="quire_surface_container">#FFFEFBF4</color>
    <color name="quire_outline">#FFEFE5D2</color>
    <color name="quire_on_surface">#FF1F1A14</color>
    <color name="quire_on_surface_muted">#FF8A7355</color>
    <color name="quire_accent">#FF7A2E2A</color>
    <color name="quire_accent_deep">#FF4A1A18</color>
    <color name="quire_on_accent">#FFF5EFE3</color>
</resources>
```

- [ ] **Step 2: Write dark-mode tokens**

Create `app/src/main/res/values-night/colors.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="quire_surface">#FF1A1612</color>
    <color name="quire_surface_container">#FF241F18</color>
    <color name="quire_outline">#FF3A322A</color>
    <color name="quire_on_surface">#FFEDE5D5</color>
    <color name="quire_on_surface_muted">#FF9A8B72</color>
    <color name="quire_accent">#FFC26A66</color>
    <color name="quire_accent_deep">#FFA04846</color>
    <color name="quire_on_accent">#FF1A1612</color>
</resources>
```

- [ ] **Step 3: Replace the placeholder theme XML**

Replace `app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Quire" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:statusBarColor">@color/quire_surface</item>
        <item name="android:windowLightStatusBar">true</item>
        <item name="android:navigationBarColor">@color/quire_surface_container</item>
        <item name="android:windowLightNavigationBar">true</item>
    </style>
</resources>
```

- [ ] **Step 4: Add a night variant**

Create `app/src/main/res/values-night/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.Quire" parent="android:Theme.Material.NoActionBar">
        <item name="android:statusBarColor">@color/quire_surface</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:navigationBarColor">@color/quire_surface_container</item>
        <item name="android:windowLightNavigationBar">false</item>
    </style>
</resources>
```

- [ ] **Step 5: Update the manifest theme reference**

Edit `app/src/main/AndroidManifest.xml`:

```xml
<application
    android:name=".EReaderApp"
    android:label="@string/app_name"
    android:theme="@style/Theme.Quire">
```

(only `android:theme` changes — the rest stays.)

- [ ] **Step 6: Build to confirm**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/ app/src/main/AndroidManifest.xml
git commit -m "feat: add Quire color tokens and theme"
```

---

### Task 3: Compose theme — colors, typography, shapes

**Files:**
- Create: `app/src/main/java/io/theficos/ereader/ui/theme/QuireColors.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/theme/QuireTypography.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/theme/QuireShapes.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/theme/Theme.kt`

- [ ] **Step 1: Color schemes**

Create `app/src/main/java/io/theficos/ereader/ui/theme/QuireColors.kt`:

```kotlin
package io.theficos.ereader.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

private val Surface          = Color(0xFFF8F4EC)
private val SurfaceContainer = Color(0xFFFEFBF4)
private val Outline          = Color(0xFFEFE5D2)
private val OnSurface        = Color(0xFF1F1A14)
private val OnSurfaceMuted   = Color(0xFF8A7355)
private val Accent           = Color(0xFF7A2E2A)
private val AccentDeep       = Color(0xFF4A1A18)
private val OnAccent         = Color(0xFFF5EFE3)

private val DarkSurface          = Color(0xFF1A1612)
private val DarkSurfaceContainer = Color(0xFF241F18)
private val DarkOutline          = Color(0xFF3A322A)
private val DarkOnSurface        = Color(0xFFEDE5D5)
private val DarkOnSurfaceMuted   = Color(0xFF9A8B72)
private val DarkAccent           = Color(0xFFC26A66)
private val DarkAccentDeep       = Color(0xFFA04846)
private val DarkOnAccent         = Color(0xFF1A1612)

internal val QuireLightColors = lightColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = AccentDeep,
    onPrimaryContainer = OnAccent,
    background = Surface,
    onBackground = OnSurface,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceContainer,
    onSurfaceVariant = OnSurfaceMuted,
    outline = Outline,
    outlineVariant = Outline,
)

internal val QuireDarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    primaryContainer = DarkAccentDeep,
    onPrimaryContainer = DarkOnAccent,
    background = DarkSurface,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceContainer,
    onSurfaceVariant = DarkOnSurfaceMuted,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
)
```

- [ ] **Step 2: Typography**

Create `app/src/main/java/io/theficos/ereader/ui/theme/QuireTypography.kt`:

```kotlin
package io.theficos.ereader.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.theficos.ereader.R

internal val Lora = FontFamily(
    Font(R.font.lora_regular, FontWeight.Normal),
    Font(R.font.lora_semibold, FontWeight.SemiBold),
)

internal val QuireTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Lora,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        letterSpacing = (-0.01).em,
    ),
    titleMedium = TextStyle(
        fontFamily = Lora,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 0.14.em,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
    ),
)
```

- [ ] **Step 3: Shapes**

Create `app/src/main/java/io/theficos/ereader/ui/theme/QuireShapes.kt`:

```kotlin
package io.theficos.ereader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

internal val QuireShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),  // covers
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),     // buttons
    large = RoundedCornerShape(12.dp),      // cards
    extraLarge = RoundedCornerShape(20.dp),
)
```

- [ ] **Step 4: Wire it up**

Replace `app/src/main/java/io/theficos/ereader/ui/theme/Theme.kt`:

```kotlin
package io.theficos.ereader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun EReaderTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) QuireDarkColors else QuireLightColors
    MaterialTheme(
        colorScheme = colors,
        typography = QuireTypography,
        shapes = QuireShapes,
        content = content,
    )
}
```

- [ ] **Step 5: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/theme/
git commit -m "feat: Quire compose theme — colors, typography, shapes"
```

---

### Task 4: Launcher icon — adaptive

**Files:**
- Create: `app/src/main/res/drawable/ic_launcher_background.xml`
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Delete (if present): legacy `mipmap-*/ic_launcher.png` (none in repo currently — safe).

- [ ] **Step 1: Background drawable (oxblood gradient)**

Create `app/src/main/res/drawable/ic_launcher_background.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr xmlns:aapt="http://schemas.android.com/aapt" name="android:fillColor">
            <gradient
                android:type="radial"
                android:centerX="54"
                android:centerY="54"
                android:gradientRadius="64"
                android:startColor="#FF7A2E2A"
                android:endColor="#FF4A1A18" />
        </aapt:attr>
    </path>
</vector>
```

- [ ] **Step 2: Foreground drawable (italic lowercase q)**

Create `app/src/main/res/drawable/ic_launcher_foreground.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- Italic lowercase 'q' — bowl + descender. Centered in the 72dp safe zone. -->
    <path
        android:fillColor="#FFF5EFE3"
        android:pathData="M68,42 C68,34 62,30 55,30 C44,30 36,40 36,52 C36,62 42,68 51,68 C56,68 60,66 64,62 L60,84 C60,86 61,87 63,87 L70,87 C72,87 73,86 73,84 L82,30 C82,29 81,28 79,28 L73,28 C71,28 70,29 70,30 Z M55,38 C60,38 63,42 62,49 C61,56 56,60 51,60 C47,60 45,57 45,52 C45,45 49,38 55,38 Z" />
</vector>
```

- [ ] **Step 3: Adaptive icon descriptors**

Create `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

Create `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml` with identical content.

- [ ] **Step 4: Reference the icon in the manifest**

Edit `app/src/main/AndroidManifest.xml`. Inside `<application ...>` add `android:icon` and `android:roundIcon`:

```xml
<application
    android:name=".EReaderApp"
    android:icon="@mipmap/ic_launcher"
    android:roundIcon="@mipmap/ic_launcher_round"
    android:label="@string/app_name"
    android:theme="@style/Theme.Quire">
```

- [ ] **Step 5: Build, install, eyeball the icon**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

Install the APK on a device or emulator and check the launcher. The `q` glyph should be visible centered on the oxblood gradient under round, square, and squircle masks.

If the path data isn't producing a recognizable q, replace `ic_launcher_foreground.xml`'s path with a simpler text-based fallback rendered via Android Studio's Asset Studio (acceptable substitution — record what you used in the commit message).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/res/drawable/ic_launcher_*.xml \
        app/src/main/res/mipmap-anydpi-v26/ \
        app/src/main/AndroidManifest.xml
git commit -m "feat: Quire adaptive launcher icon"
```

---

### Task 5: Rename app — applicationId, namespace, label

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/res/values/strings.xml`

Note: Kotlin packages stay `io.theficos.ereader.*`. Only the Android `applicationId` and `namespace` change.

- [ ] **Step 1: Edit `app/build.gradle.kts`**

Replace lines:

```kotlin
android {
    namespace = "io.theficos.ereader"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.theficos.ereader"
```

with:

```kotlin
android {
    namespace = "io.theficos.quire"
    compileSdk = 34
    defaultConfig {
        applicationId = "io.theficos.quire"
```

- [ ] **Step 2: Update `strings.xml`**

Replace `app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Quire</string>
</resources>
```

- [ ] **Step 3: Build and run all tests**

```bash
./gradlew :app:assembleDebug
./gradlew test
```

Expected: BUILD SUCCESSFUL on both. The `:app` namespace switch will regenerate `R` under `io.theficos.quire.R`. Sources still in `io.theficos.ereader.*` packages will compile fine — they import from the generated `io.theficos.quire.R` class. Update any explicit `import io.theficos.ereader.R` references found by the compiler error to `io.theficos.quire.R`.

If tests fail because some module references the app's namespace: those modules should not depend on `:app` and shouldn't see the namespace.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts app/src/main/res/values/strings.xml
# plus any R-import fixes the compiler surfaced
git commit -m "feat: rename applicationId/namespace to io.theficos.quire"
```

---

### Task 6: OPDS — extract cover URL (TDD)

**Files:**
- Modify: `data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsCatalog.kt`
- Modify: `data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsClient.kt`
- Modify: `data/opds/src/test/java/io/theficos/ereader/data/opds/OpdsClientTest.kt`

Note: the existing test fixture `data/opds/src/test/resources/opds/catalog-feed.xml` already includes `<link rel="http://opds-spec.org/image" href="/opds/cover/42" type="image/jpeg"/>` — no fixture changes needed.

- [ ] **Step 1: Rename the field for accuracy**

Edit `data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsCatalog.kt`:

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
    val coverUrl: String?,
)
```

(`coverHref` → `coverUrl` — naming aligned with the spec; type unchanged.)

Compiler errors will surface in `OpdsClient.kt` and `CatalogViewModel.kt`. Don't fix them yet — Step 2 covers `OpdsClient`, Task 8 covers the VM.

- [ ] **Step 2: Add the failing test**

In `data/opds/src/test/java/io/theficos/ereader/data/opds/OpdsClientTest.kt`, add a new `@Test`:

```kotlin
@Test fun `fetch acquisition feed extracts cover URL`() = runTest {
    val feed = client.fetch(server.url("/opds/new").toString())
    val pub = feed.publications[0]
    assertThat(pub.coverUrl).isNotNull()
    assertThat(pub.coverUrl).endsWith("/opds/cover/42")
}
```

- [ ] **Step 3: Run the new test — expect failure**

Run: `./gradlew :data:opds:test --tests "io.theficos.ereader.data.opds.OpdsClientTest.fetch acquisition feed extracts cover URL"`
Expected: FAIL — `coverUrl` is null because `OpdsClient` is still passing `null`.

- [ ] **Step 4: Implement extraction**

In `data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsClient.kt`, replace the `OpdsPublication(...)` block inside `mapNotNull` with:

```kotlin
                publications = feed.publications.mapNotNull { pub ->
                    val epubLink = pub.links.firstOrNull { link ->
                        link.rels.contains("http://opds-spec.org/acquisition") &&
                            link.mediaType.toString() == "application/epub+zip"
                    } ?: return@mapNotNull null
                    val coverLink = pub.images.firstOrNull { link ->
                        link.rels.contains("http://opds-spec.org/image")
                    } ?: pub.images.firstOrNull { link ->
                        link.rels.contains("http://opds-spec.org/image/thumbnail")
                    }
                    OpdsPublication(
                        title = pub.metadata.title.orEmpty(),
                        author = pub.metadata.authors.firstOrNull()?.name,
                        epubDownloadHref = absolutize(absoluteUrl, epubLink.href.toString()),
                        coverUrl = coverLink?.href?.toString()?.let { absolutize(absoluteUrl, it) },
                    )
                },
```

If `pub.images` does not exist on this Readium version, fall back to scanning `pub.links` for the same rels (replace `pub.images` with `pub.links` in the two `firstOrNull` calls). Run the test after the change to verify whichever variant compiles.

- [ ] **Step 5: Run the test — expect pass**

Run: `./gradlew :data:opds:test`
Expected: All tests PASS, including the new cover test.

- [ ] **Step 6: Commit**

```bash
git add data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsCatalog.kt \
        data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsClient.kt \
        data/opds/src/test/java/io/theficos/ereader/data/opds/OpdsClientTest.kt
git commit -m "feat(opds): extract cover URL from publication entries"
```

---

### Task 7: BookDownloader — cover download helper (TDD)

**Files:**
- Modify: `data/opds/src/main/java/io/theficos/ereader/data/opds/BookDownloader.kt`
- Modify: `data/opds/src/test/java/io/theficos/ereader/data/opds/BookDownloaderTest.kt`

- [ ] **Step 1: Add the failing test**

Append to `data/opds/src/test/java/io/theficos/ereader/data/opds/BookDownloaderTest.kt` inside the test class:

```kotlin
@Test fun `downloadCover writes bytes to the books dir`() = runTest {
    val coverBytes = ByteArray(1024) { (it % 251).toByte() }
    server.enqueue(
        MockResponse()
            .setHeader("Content-Type", "image/jpeg")
            .setBody(okio.Buffer().write(coverBytes))
    )
    val out = downloader.downloadCover(
        server.url("/cover.jpg").toString(),
        "abc.jpg",
    )
    assertThat(out).isNotNull()
    assertThat(out!!.exists()).isTrue()
    assertThat(out.readBytes()).isEqualTo(coverBytes)
}

@Test fun `downloadCover returns null on http error`() = runTest {
    server.enqueue(MockResponse().setResponseCode(404))
    val out = downloader.downloadCover(
        server.url("/cover.jpg").toString(),
        "missing.jpg",
    )
    assertThat(out).isNull()
}
```

- [ ] **Step 2: Run tests — expect failure**

Run: `./gradlew :data:opds:test --tests "io.theficos.ereader.data.opds.BookDownloaderTest"`
Expected: FAIL — `downloadCover` does not exist.

- [ ] **Step 3: Implement `downloadCover`**

In `data/opds/src/main/java/io/theficos/ereader/data/opds/BookDownloader.kt`, after the existing `download(...)` function (still inside the class), add:

```kotlin
    /**
     * Best-effort cover fetch. Returns the file on success or null on any failure
     * (HTTP error, IO error, network). Cover availability must never block book
     * download — a missing cover is not an error.
     */
    suspend fun downloadCover(
        url: String,
        destFileName: String,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val response = okHttp.newCall(Request.Builder().url(url).get().build()).execute()
            response.use {
                if (!it.isSuccessful) return@runCatching null
                val out = File(booksDir, destFileName)
                val tmp = File(booksDir, "$destFileName.part")
                try {
                    it.body!!.byteStream().use { input ->
                        tmp.outputStream().use { sink -> input.copyTo(sink) }
                    }
                    if (out.exists()) out.delete()
                    if (!tmp.renameTo(out)) {
                        tmp.delete()
                        return@runCatching null
                    }
                    out
                } catch (t: Throwable) {
                    tmp.delete()
                    null
                }
            }
        }.getOrNull()
    }
```

- [ ] **Step 4: Run tests — expect pass**

Run: `./gradlew :data:opds:test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add data/opds/src/main/java/io/theficos/ereader/data/opds/BookDownloader.kt \
        data/opds/src/test/java/io/theficos/ereader/data/opds/BookDownloaderTest.kt
git commit -m "feat(opds): add downloadCover helper with best-effort semantics"
```

---

### Task 8: Document model — add coverPath (TDD with Room)

**Files:**
- Modify: `core/model/src/main/java/io/theficos/ereader/core/model/Document.kt`
- Modify: `data/local/src/main/java/io/theficos/ereader/data/local/db/DocumentEntity.kt`
- Modify: `data/local/src/main/java/io/theficos/ereader/data/local/db/EReaderDatabase.kt`
- Modify: `data/local/src/main/java/io/theficos/ereader/data/local/DocumentRepository.kt`
- Modify: `data/local/src/test/java/io/theficos/ereader/data/local/db/DocumentDaoTest.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/catalog/CatalogViewModel.kt` (call site)

- [ ] **Step 1: Add `coverPath` to the domain model**

Edit `core/model/src/main/java/io/theficos/ereader/core/model/Document.kt`:

```kotlin
package io.theficos.ereader.core.model

data class Document(
    val id: Long,
    val identity: DocumentIdentity,
    val title: String,
    val author: String?,
    val downloadUrl: String,
    val localPath: String,
    val coverPath: String?,
    val downloadedAt: Long,
)
```

- [ ] **Step 2: Add `coverPath` to the entity**

Edit `data/local/src/main/java/io/theficos/ereader/data/local/db/DocumentEntity.kt`:

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
    val coverPath: String?,
    val downloadedAt: Long,
)
```

- [ ] **Step 3: Bump DB version with a real migration**

Replace `data/local/src/main/java/io/theficos/ereader/data/local/db/EReaderDatabase.kt`:

```kotlin
package io.theficos.ereader.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [DocumentEntity::class, ProgressEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class EReaderDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun progressDao(): ProgressDao

    companion object {
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN coverPath TEXT")
            }
        }

        fun build(context: Context): EReaderDatabase =
            Room.databaseBuilder(context, EReaderDatabase::class.java, "ereader.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
```

(Note: `fallbackToDestructiveMigration()` is intentionally removed. Phase 1 users — including the developer — have existing libraries; the migration must run cleanly.)

- [ ] **Step 4: Plumb `coverPath` through the repository**

Edit `data/local/src/main/java/io/theficos/ereader/data/local/DocumentRepository.kt`. Replace the `insert` method and the `toDomain` mapper:

```kotlin
    suspend fun insert(
        identity: DocumentIdentity,
        title: String,
        author: String?,
        downloadUrl: String,
        localPath: String,
        coverPath: String?,
        downloadedAt: Long,
    ): Long = dao.insert(DocumentEntity(
        metadataId = identity.metadataId,
        contentHash = identity.contentHash,
        title = title,
        author = author,
        downloadUrl = downloadUrl,
        localPath = localPath,
        coverPath = coverPath,
        downloadedAt = downloadedAt,
    ))

    private fun DocumentEntity.toDomain(): Document = Document(
        id = id,
        identity = DocumentIdentity(metadataId = metadataId, contentHash = contentHash),
        title = title,
        author = author,
        downloadUrl = downloadUrl,
        localPath = localPath,
        coverPath = coverPath,
        downloadedAt = downloadedAt,
    )
```

- [ ] **Step 5: Update existing tests**

`data/local/src/test/java/io/theficos/ereader/data/local/db/DocumentDaoTest.kt` constructs `DocumentEntity` instances. Add `coverPath = null` (or a sample path for one test) to each constructor call. Add this round-trip test:

```kotlin
@Test fun `insert with coverPath round-trips`() = runTest {
    val id = dao.insert(DocumentEntity(
        metadataId = "id-cover",
        contentHash = "hash-cover",
        title = "T",
        author = null,
        downloadUrl = "http://x/y.epub",
        localPath = "/tmp/y.epub",
        coverPath = "/tmp/y.jpg",
        downloadedAt = 0L,
    ))
    val row = dao.findById(id)
    assertThat(row).isNotNull()
    assertThat(row!!.coverPath).isEqualTo("/tmp/y.jpg")
}
```

- [ ] **Step 6: Update the catalog VM call site**

Edit `app/src/main/java/io/theficos/ereader/ui/catalog/CatalogViewModel.kt`. After the existing `downloader.download(...)` call returns the EPUB `file`, fetch the cover (if present) and pass through:

```kotlin
                val file = downloader.download(pub.epubDownloadHref, fileName) { sent, total ->
                    val frac = if (total > 0) sent.toFloat() / total else 0f
                    _state.value = (_state.value as? CatalogUiState.Loaded)?.copy(progress = frac) ?: return@download
                }
                val coverFile = pub.coverUrl?.let { url ->
                    val coverName = fileName.removeSuffix(".epub") + ".cover"
                    downloader.downloadCover(url, coverName)
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
                        coverPath = coverFile?.absolutePath,
                        downloadedAt = System.currentTimeMillis(),
                    )
                } else {
                    file.delete()
                    coverFile?.delete()
                }
```

- [ ] **Step 7: Run the entire test suite**

Run: `./gradlew test`
Expected: PASS across `:core:model`, `:data:local`, `:data:opds`. The Room schema export will fail if `data/local/schemas/` is set up — if that fails, run `./gradlew :data:local:test -PgenerateSchemas` or simply delete the stale v1 schema; the build will write a fresh v2.

- [ ] **Step 8: Commit**

```bash
git add core/model/ data/local/ app/src/main/java/io/theficos/ereader/ui/catalog/
git commit -m "feat: add coverPath to Document, Room v1→v2 migration, plumb through download flow"
```

---

### Task 9: Reader preferences — fontFamily and lineSpacing

**Files:**
- Modify: `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferences.kt`
- Modify: `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferencesStore.kt`

- [ ] **Step 1: Extend the data class and toEpubPreferences mapping**

Replace `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferences.kt`:

```kotlin
package io.theficos.ereader.reader

import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.FontFamily as ReadiumFontFamily
import org.readium.r2.navigator.preferences.Theme

enum class ReaderTheme { LIGHT, DARK, SEPIA }

enum class ReaderFontFamily(val readium: ReadiumFontFamily?) {
    SYSTEM(null),
    LORA(ReadiumFontFamily("Lora")),
    LITERATA(ReadiumFontFamily("Literata")),
    CHARTER(ReadiumFontFamily("Charter")),
    OPEN_DYSLEXIC(ReadiumFontFamily("OpenDyslexic")),
}

data class ReaderPreferences(
    val fontScale: Double = 1.0,
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val fontFamily: ReaderFontFamily = ReaderFontFamily.SYSTEM,
    val lineSpacing: Double = 1.4,
) {
    init {
        require(fontScale in 0.5..2.0) { "fontScale out of range: $fontScale" }
        require(lineSpacing in 1.0..1.8) { "lineSpacing out of range: $lineSpacing" }
    }
}

fun ReaderPreferences.toEpubPreferences(): EpubPreferences = EpubPreferences(
    fontSize = fontScale,
    theme = when (theme) {
        ReaderTheme.LIGHT -> Theme.LIGHT
        ReaderTheme.DARK -> Theme.DARK
        ReaderTheme.SEPIA -> Theme.SEPIA
    },
    fontFamily = fontFamily.readium,
    lineHeight = lineSpacing,
)
```

If `EpubPreferences` does not accept `fontFamily` / `lineHeight` parameters in this Readium version, omit those two arguments and add a code comment that they're deferred. Run the build to find out.

- [ ] **Step 2: Persist the new fields**

Replace `reader/src/main/java/io/theficos/ereader/reader/ReaderPreferencesStore.kt`:

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
            .putString(KEY_FONT_FAMILY, next.fontFamily.name)
            .putFloat(KEY_LINE_SPACING, next.lineSpacing.toFloat())
            .apply()
        _flow.value = next
    }

    private fun load(): ReaderPreferences {
        val fontScale = prefs.getFloat(KEY_FONT_SCALE, 1.0f).toDouble().coerceIn(0.5, 2.0)
        val themeName = prefs.getString(KEY_THEME, ReaderTheme.LIGHT.name) ?: ReaderTheme.LIGHT.name
        val theme = runCatching { ReaderTheme.valueOf(themeName) }.getOrDefault(ReaderTheme.LIGHT)
        val familyName = prefs.getString(KEY_FONT_FAMILY, ReaderFontFamily.SYSTEM.name)
            ?: ReaderFontFamily.SYSTEM.name
        val family = runCatching { ReaderFontFamily.valueOf(familyName) }
            .getOrDefault(ReaderFontFamily.SYSTEM)
        val lineSpacing = prefs.getFloat(KEY_LINE_SPACING, 1.4f).toDouble().coerceIn(1.0, 1.8)
        return ReaderPreferences(
            fontScale = fontScale,
            theme = theme,
            fontFamily = family,
            lineSpacing = lineSpacing,
        )
    }

    private companion object {
        const val KEY_FONT_SCALE = "font_scale"
        const val KEY_THEME = "theme"
        const val KEY_FONT_FAMILY = "font_family"
        const val KEY_LINE_SPACING = "line_spacing"
    }
}
```

- [ ] **Step 3: Build and run reader tests**

Run: `./gradlew :reader:test`
Expected: PASS. (No new tests yet — the existing `ProgressTrackerTest` and `LocatorSerializationTest` don't touch preferences.)

- [ ] **Step 4: Commit**

```bash
git add reader/src/main/java/io/theficos/ereader/reader/ReaderPreferences.kt \
        reader/src/main/java/io/theficos/ereader/reader/ReaderPreferencesStore.kt
git commit -m "feat(reader): add fontFamily and lineSpacing preferences"
```

---

### Task 10: Shared UI components — CoverImage, QuireCard, SectionLabel

**Files:**
- Create: `app/src/main/java/io/theficos/ereader/ui/components/CoverImage.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/components/QuireCard.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/components/SectionLabel.kt`

- [ ] **Step 1: SectionLabel**

Create `app/src/main/java/io/theficos/ereader/ui/components/SectionLabel.kt`:

```kotlin
package io.theficos.ereader.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    )
}
```

- [ ] **Step 2: QuireCard**

Create `app/src/main/java/io/theficos/ereader/ui/components/QuireCard.kt`:

```kotlin
package io.theficos.ereader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun QuireCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable () -> Unit,
) {
    OutlinedCard(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.outlinedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        androidx.compose.foundation.layout.Box(modifier = Modifier.padding(contentPadding)) {
            content()
        }
    }
}
```

- [ ] **Step 3: CoverImage with deterministic fallback**

Create `app/src/main/java/io/theficos/ereader/ui/components/CoverImage.kt`:

```kotlin
package io.theficos.ereader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import io.theficos.ereader.ui.theme.Lora

private val FallbackPalettes = listOf(
    Color(0xFF7A2E2A) to Color(0xFF4A1A18),  // oxblood
    Color(0xFF3B5A4A) to Color(0xFF1F3B2C),  // forest
    Color(0xFF1F2B4A) to Color(0xFF0F1530),  // ink
    Color(0xFF6A4A2A) to Color(0xFF3F2A18),  // tobacco
)

@Composable
fun CoverImage(
    source: Any?,                 // String URL, File, Uri, or null for fallback
    title: String,
    author: String?,
    modifier: Modifier = Modifier,
) {
    val initials = remember(title, author) { computeInitials(title, author) }
    val palette = remember(title) {
        FallbackPalettes[(title.hashCode() and 0x7FFFFFFF) % FallbackPalettes.size]
    }
    val shape = RoundedCornerShape(4.dp)

    Box(
        modifier = modifier.clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (source != null) {
            SubcomposeAsyncImage(
                model = source,
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { Fallback(initials, palette) },
                error = { Fallback(initials, palette) },
            )
        } else {
            Fallback(initials, palette)
        }
    }
}

@Composable
private fun Fallback(initials: String, palette: Pair<Color, Color>) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(palette.first, palette.second),
                    start = Offset(0f, 0f),
                    end = Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
                )
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.displaySmall,
            fontFamily = Lora,
            fontWeight = FontWeight.SemiBold,
            fontSize = 36.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(8.dp),
        )
    }
}

private fun computeInitials(title: String, author: String?): String {
    val source = author?.takeIf { it.isNotBlank() } ?: title
    val parts = source.trim().split(Regex("\\s+"))
    return when {
        parts.isEmpty() -> "·"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts.first().firstOrNull()?.toString().orEmpty() +
                 parts.last().firstOrNull()?.toString().orEmpty()).uppercase()
    }
}

@Composable
private fun <T> remember(key1: Any?, key2: Any?, calculation: () -> T): T =
    androidx.compose.runtime.remember(key1, key2, calculation)

@Composable
private fun <T> remember(key: Any?, calculation: () -> T): T =
    androidx.compose.runtime.remember(key, calculation)
```

- [ ] **Step 4: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/components/
git commit -m "feat: shared UI components — CoverImage, QuireCard, SectionLabel"
```

---

### Task 11: Library — continueReading flow + redesigned screen

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/library/LibraryViewModel.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/library/ContinueReadingCard.kt`

- [ ] **Step 1: VM exposes continueReading**

Replace the body of `LibraryViewModel` in `app/src/main/java/io/theficos/ereader/ui/library/LibraryViewModel.kt`:

```kotlin
package io.theficos.ereader.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.data.local.DocumentRepository
import io.theficos.ereader.data.local.ProgressRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val docs: DocumentRepository,
    private val progress: ProgressRepository,
) : ViewModel() {

    private val rows: StateFlow<List<LibraryRow>> =
        docs.observeLibrary()
            .flatMapLatest { docList ->
                if (docList.isEmpty()) flowOf(emptyList())
                else combine(docList.map { d -> progress.observe(d.id).map { d to it } }) { it.toList() }
            }
            .map { pairs ->
                pairs.map { (d, p) ->
                    LibraryRow(
                        document = d,
                        percent = p?.percent ?: 0.0,
                        progressUpdatedAt = p?.updatedAt ?: 0L,
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val items: StateFlow<List<LibraryRow>> = rows

    val continueReading: StateFlow<LibraryRow?> = rows
        .map { list ->
            list
                .filter { it.percent in 0.0001..0.9999 }
                .maxByOrNull { it.progressUpdatedAt }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun delete(document: Document) {
        viewModelScope.launch { docs.delete(document) }
    }
}

data class LibraryRow(
    val document: Document,
    val percent: Double,
    val progressUpdatedAt: Long,
)
```

(`progress.observe(id)` returns `Flow<Progress?>`. Confirm in `ProgressRepository`; if it returns `Flow<Double?>` instead, adjust the `map` accordingly. The existing call site in this VM previously ignored `updatedAt`, so this is a real expansion.)

- [ ] **Step 2: ContinueReadingCard composable**

Create `app/src/main/java/io/theficos/ereader/ui/library/ContinueReadingCard.kt`:

```kotlin
package io.theficos.ereader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.ui.components.CoverImage
import io.theficos.ereader.ui.components.QuireCard
import io.theficos.ereader.ui.components.SectionLabel

@Composable
fun ContinueReadingCard(row: LibraryRow, onClick: () -> Unit) {
    val percentInt = (row.percent * 100).toInt().coerceIn(0, 100)
    QuireCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
    ) {
        Column {
            SectionLabel("Continue reading")
            Row(
                modifier = Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                CoverImage(
                    source = row.document.coverPath,
                    title = row.document.title,
                    author = row.document.author,
                    modifier = Modifier.size(width = 64.dp, height = 96.dp),
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = row.document.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (row.document.author != null) {
                        Text(
                            text = row.document.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .height(3.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outline),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(row.percent.toFloat())
                                .height(3.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Text(
                        text = "$percentInt%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 3: Library screen redesign**

Replace `app/src/main/java/io/theficos/ereader/ui/library/LibraryScreen.kt`:

```kotlin
package io.theficos.ereader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.core.model.Document
import io.theficos.ereader.ui.components.CoverImage
import io.theficos.ereader.ui.components.SectionLabel
import io.theficos.ereader.ui.theme.Lora

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onOpenBook: (documentId: Long) -> Unit,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    val items by viewModel.items.collectAsState()
    val cont by viewModel.continueReading.collectAsState()
    var pendingDelete by remember { mutableStateOf<Document?>(null) }

    if (items.isEmpty()) {
        EmptyState(modifier = Modifier.padding(contentPadding))
        return
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            Text(
                text = "Quire",
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        cont?.let { row ->
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                ContinueReadingCard(row = row, onClick = { onOpenBook(row.document.id) })
            }
        }
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            SectionLabel("Library · ${items.size}")
        }
        itemsIndexed(items, key = { _, r -> r.document.id }) { _, row ->
            Column(
                modifier = Modifier.combinedClickable(
                    onClick = { onOpenBook(row.document.id) },
                    onLongClick = { pendingDelete = row.document },
                ),
            ) {
                CoverImage(
                    source = row.document.coverPath,
                    title = row.document.title,
                    author = row.document.author,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(2f / 3f),
                )
                Text(
                    text = row.document.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    pendingDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete book?") },
            text = { Text("\"${doc.title}\" will be removed from your library and the downloaded file deleted. Reading progress will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(doc)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "q",
                fontFamily = Lora,
                style = MaterialTheme.typography.displaySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.padding(bottom = 16.dp),
            )
            Text(
                text = "Your shelf is empty.",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Open the Catalog tab to find books.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
```

(The screen now takes `contentPadding` so the bottom-nav scaffold can offset it. Task 13 wires that.)

- [ ] **Step 4: Update existing call site temporarily**

`AppNavGraph.kt` currently calls `LibraryScreen(viewModel, onOpenCatalog, onOpenBook)`. Task 13 will rewrite the nav graph entirely. For now, to keep the project compiling: in `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`, update the `LibraryScreen` call to:

```kotlin
LibraryScreen(
    viewModel = vm,
    onOpenBook = { id -> nav.navigate("reader/$id") },
    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
)
```

(The `onOpenCatalog` parameter is gone; routing is via bottom nav post-Task-13.)

- [ ] **Step 5: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/library/ \
        app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt
git commit -m "feat(library): cover grid, continue-reading hero, empty state"
```

---

### Task 12: Catalog — cover grid with download badges

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/catalog/CatalogScreen.kt`

- [ ] **Step 1: Replace the screen**

Replace `app/src/main/java/io/theficos/ereader/ui/catalog/CatalogScreen.kt`:

```kotlin
package io.theficos.ereader.ui.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.theficos.ereader.ui.components.CoverImage
import io.theficos.ereader.ui.components.SectionLabel

@Composable
fun CatalogScreen(
    viewModel: CatalogViewModel,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
) {
    val state by viewModel.state.collectAsState()
    val downloadedUrls by viewModel.downloadedUrls.collectAsState()
    LaunchedEffect(Unit) { if (state == CatalogUiState.Idle) viewModel.loadRoot() }

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        when (val s = state) {
            CatalogUiState.Idle -> {}
            CatalogUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is CatalogUiState.Error -> Text(
                s.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
            is CatalogUiState.Loaded -> Loaded(
                state = s,
                downloadedUrls = downloadedUrls,
                onNavigate = viewModel::load,
                onDownload = viewModel::download,
            )
        }
    }
}

@Composable
private fun Loaded(
    state: CatalogUiState.Loaded,
    downloadedUrls: Set<String>,
    onNavigate: (String) -> Unit,
    onDownload: (io.theficos.ereader.data.opds.OpdsPublication) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = "Catalog",
                style = MaterialTheme.typography.displaySmall,
            )
        }
        if (state.error != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = "Download error: ${state.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (state.feed.navigation.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Sections") }
            items_navigation(state, onNavigate)
        }
        if (state.feed.publications.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Books · ${state.feed.publications.size}") }
            itemsIndexed(state.feed.publications, key = { _, p -> p.epubDownloadHref }) { _, pub ->
                val downloaded = pub.epubDownloadHref in downloadedUrls
                val downloading = state.downloading == pub.epubDownloadHref
                Column(
                    modifier = Modifier.clickable(enabled = !downloading) {
                        if (!downloaded) onDownload(pub)
                    },
                ) {
                    Box {
                        CoverImage(
                            source = pub.coverUrl,
                            title = pub.title,
                            author = pub.author,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(2f / 3f),
                        )
                        when {
                            downloading -> CircularProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .size(36.dp),
                                color = MaterialTheme.colorScheme.primary,
                            )
                            downloaded -> Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            ) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = "Downloaded",
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.align(Alignment.Center).size(14.dp),
                                )
                            }
                            else -> Icon(
                                Icons.Default.FileDownload,
                                contentDescription = "Download",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(6.dp)
                                    .size(20.dp),
                            )
                        }
                    }
                    Text(
                        text = pub.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (pub.author != null) {
                        Text(
                            text = pub.author,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.grid.LazyGridScope.items_navigation(
    state: CatalogUiState.Loaded,
    onNavigate: (String) -> Unit,
) {
    items(state.feed.navigation.size, span = { GridItemSpan(maxLineSpan) }) { idx ->
        val nav = state.feed.navigation[idx]
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigate(nav.href) }
                    .padding(vertical = 14.dp, horizontal = 4.dp),
            ) {
                Text(
                    text = nav.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}
```

- [ ] **Step 2: Update the call site temporarily**

In `AppNavGraph.kt`, the `composable("catalog")` block. Replace the `CatalogScreen(...)` call with:

```kotlin
CatalogScreen(
    viewModel = vm,
    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
)
```

(`onOpenLibrary`/`onOpenSettings` parameters are gone — routing via bottom nav, Task 13.)

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/catalog/CatalogScreen.kt \
        app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt
git commit -m "feat(catalog): cover grid with download-state badges"
```

---

### Task 13: Bottom navigation scaffold + restructured nav graph

**Files:**
- Create: `app/src/main/java/io/theficos/ereader/ui/main/MainScaffold.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`
- Modify: `app/src/main/java/io/theficos/ereader/MainActivity.kt`

- [ ] **Step 1: Define the tab destinations and main scaffold**

Create `app/src/main/java/io/theficos/ereader/ui/main/MainScaffold.kt`:

```kotlin
package io.theficos.ereader.ui.main

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

enum class Tab(val label: String, val icon: ImageVector) {
    LIBRARY("Library", Icons.Outlined.LibraryBooks),
    CATALOG("Catalog", Icons.Outlined.Storefront),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@Composable
fun MainScaffold(
    initial: Tab = Tab.LIBRARY,
    content: @Composable (Tab, PaddingValues) -> Unit,
) {
    var current by remember { mutableStateOf(initial) }
    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Tab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = current == tab,
                        onClick = { current = tab },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        content(current, padding)
    }
}
```

- [ ] **Step 2: Restructure the nav graph**

Replace `app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt`:

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
import io.theficos.ereader.ui.main.MainScaffold
import io.theficos.ereader.ui.main.Tab
import io.theficos.ereader.ui.reader.ReaderScreen
import io.theficos.ereader.ui.reader.ReaderViewModel
import io.theficos.ereader.ui.settings.SettingsScreen
import io.theficos.ereader.ui.settings.SettingsViewModel

@Composable
fun AppNavGraph(container: AppContainer) {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "home") {
        composable("home") {
            val libVm = remember { LibraryViewModel(container.documentRepository, container.progressRepository) }
            val catVm = remember {
                CatalogViewModel(container.opdsClient, container.bookDownloader, container.documentRepository, container.credentialStore)
            }
            val setVm = remember {
                SettingsViewModel(
                    store = container.credentialStore,
                    readerStore = container.readerPreferencesStore,
                )
            }
            MainScaffold { tab, padding ->
                when (tab) {
                    Tab.LIBRARY -> LibraryScreen(
                        viewModel = libVm,
                        onOpenBook = { id -> nav.navigate("reader/$id") },
                        contentPadding = padding,
                    )
                    Tab.CATALOG -> CatalogScreen(
                        viewModel = catVm,
                        contentPadding = padding,
                    )
                    Tab.SETTINGS -> SettingsScreen(
                        viewModel = setVm,
                        contentPadding = padding,
                    )
                }
            }
        }
        composable(
            "reader/{docId}",
            arguments = listOf(navArgument("docId") { type = NavType.LongType }),
        ) { backStack ->
            val docId = backStack.arguments!!.getLong("docId")
            val vm = remember(docId) {
                ReaderViewModel(
                    documentId = docId,
                    docs = container.documentRepository,
                    progress = container.progressRepository,
                    readium = container.readiumFactory,
                    preferencesStore = container.readerPreferencesStore,
                )
            }
            ReaderScreen(viewModel = vm, onClose = { nav.popBackStack() })
        }
    }
}
```

- [ ] **Step 3: Update SettingsScreen signature placeholder**

`SettingsScreen` is rewritten in Task 14, but for the build to pass *now* its signature must accept `contentPadding`. Open `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt` and change the function signature:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    contentPadding: androidx.compose.foundation.layout.PaddingValues,
) {
```

Remove the `onBack: () -> Unit` parameter and the `navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }` line. Apply `contentPadding` to the existing `Scaffold` instead of its old `padding`. (Task 14 is a full rewrite; this is just to compile.)

- [ ] **Step 4: ReaderScreen onClose placeholder**

`ReaderScreen` is rewritten with chrome in Task 15, but its signature must already accept `onClose` for the nav graph to compile. Open `app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt` and change:

```kotlin
@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onClose: () -> Unit) {
```

The body doesn't need to use `onClose` yet — Task 15 wires it.

- [ ] **Step 5: Build and run app**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:installDebug` (if a device is attached).
Expected: BUILD SUCCESSFUL. Visually confirm the bottom nav appears with three tabs and switches between Library/Catalog/Settings cleanly. Reader still opens fullscreen without bottom nav.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/main/ \
        app/src/main/java/io/theficos/ereader/ui/AppNavGraph.kt \
        app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt \
        app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt
git commit -m "feat: bottom-nav scaffold; reader as fullscreen modal destination"
```

---

### Task 14: Settings — grouped cards

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt`

- [ ] **Step 1: Extend the VM for new reader-default fields**

Open `app/src/main/java/io/theficos/ereader/ui/settings/SettingsViewModel.kt`. The current VM exposes `setFontScale` and `setTheme`. Add:

```kotlin
    fun setFontFamily(family: io.theficos.ereader.reader.ReaderFontFamily) {
        readerStore.update { it.copy(fontFamily = family) }
    }

    fun setLineSpacing(value: Double) {
        readerStore.update { it.copy(lineSpacing = value.coerceIn(1.0, 1.8)) }
    }
```

- [ ] **Step 2: Rewrite the screen**

Replace `app/src/main/java/io/theficos/ereader/ui/settings/SettingsScreen.kt`:

```kotlin
package io.theficos.ereader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.theficos.ereader.reader.ReaderFontFamily
import io.theficos.ereader.reader.ReaderTheme
import io.theficos.ereader.ui.components.QuireCard
import io.theficos.ereader.ui.components.SectionLabel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    contentPadding: PaddingValues,
) {
    val calibre by viewModel.calibre.collectAsState()
    val reader by viewModel.readerPreferences.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.displaySmall)

        SectionLabel("calibre-web")
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
        }

        SectionLabel("Reader defaults")
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column {
                    Text("Font size: ${"%.1fx".format(reader.fontScale)}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = reader.fontScale.toFloat(),
                        onValueChange = { viewModel.setFontScale(it.toDouble()) },
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column {
                    Text("Line spacing: ${"%.1f".format(reader.lineSpacing)}", style = MaterialTheme.typography.bodyMedium)
                    Slider(
                        value = reader.lineSpacing.toFloat(),
                        onValueChange = { viewModel.setLineSpacing(it.toDouble()) },
                        valueRange = 1.0f..1.8f,
                        steps = 7,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column {
                    Text("Theme", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ReaderTheme.values().forEach { t ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = 16.dp),
                            ) {
                                RadioButton(selected = reader.theme == t, onClick = { viewModel.setTheme(t) })
                                Text(t.name.lowercase().replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                }
                Column {
                    Text("Font family", style = MaterialTheme.typography.bodyMedium)
                    Column {
                        ReaderFontFamily.values().forEach { f ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 2.dp),
                            ) {
                                RadioButton(selected = reader.fontFamily == f, onClick = { viewModel.setFontFamily(f) })
                                Text(f.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
                            }
                        }
                    }
                }
            }
        }

        SectionLabel("About")
        QuireCard(modifier = Modifier.fillMaxWidth()) {
            Column {
                Text("Quire", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A reader for your shelf.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
```

- [ ] **Step 3: Build**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/settings/
git commit -m "feat(settings): grouped cards with Reader defaults and About"
```

---

### Task 15: Reader chrome — top + bottom bars + auto-hide

**Files:**
- Create: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderChrome.kt`
- Create: `app/src/main/java/io/theficos/ereader/ui/reader/FontSettingsSheet.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt`
- Modify: `app/src/main/java/io/theficos/ereader/ui/reader/ReaderViewModel.kt`

- [ ] **Step 1: VM — chrome visibility + jump-to**

Add to `ReaderViewModel`:

```kotlin
    private val _chromeVisible = MutableStateFlow(true)
    val chromeVisible: StateFlow<Boolean> = _chromeVisible.asStateFlow()

    fun setChromeVisible(visible: Boolean) {
        _chromeVisible.value = visible
    }

    fun toggleChrome() {
        _chromeVisible.value = !_chromeVisible.value
    }
```

(Imports: `kotlinx.coroutines.flow.MutableStateFlow`, already present; ensure `asStateFlow` import.)

The auto-hide-after-N-seconds is driven from the screen via a `LaunchedEffect`, not the VM, because it depends on Compose lifecycle. The VM just owns the boolean.

- [ ] **Step 2: ReaderChrome composable**

Create `app/src/main/java/io/theficos/ereader/ui/reader/ReaderChrome.kt`:

```kotlin
package io.theficos.ereader.ui.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.readium.r2.shared.publication.Locator

@Composable
fun ReaderTopBar(
    visible: Boolean,
    title: String,
    onBack: () -> Unit,
    onOverflow: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
            IconButton(onClick = onOverflow) {
                Icon(Icons.Default.MoreVert, contentDescription = "Menu")
            }
        }
    }
}

@Composable
fun ReaderBottomBar(
    visible: Boolean,
    chapterTitle: String?,
    percent: Double,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pct = (percent * 100).toInt().coerceIn(0, 100)
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Slider(
                value = percent.toFloat().coerceIn(0f, 1f),
                onValueChange = { onSeek(it.toDouble()) },
                valueRange = 0f..1f,
                modifier = Modifier.fillMaxWidth().height(24.dp),
            )
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chapterTitle.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "$pct%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/**
 * Splits taps in the page area into:
 *   - left third / right third: page-turn (delegated to Readium — we don't intercept).
 *   - center third: toggle chrome.
 *
 * Returns true when the tap was a center-tap and should be consumed.
 * Caller wraps the navigator surface with this modifier and stops propagation
 * only on `true`. (In practice we use Box overlays — see ReaderScreen.)
 */
fun isCenterTap(xFraction: Float): Boolean = xFraction in 0.33f..0.67f
```

- [ ] **Step 3: FontSettingsSheet**

Create `app/src/main/java/io/theficos/ereader/ui/reader/FontSettingsSheet.kt`:

```kotlin
package io.theficos.ereader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.theficos.ereader.reader.ReaderFontFamily
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.ReaderTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FontSettingsSheet(
    prefs: ReaderPreferences,
    onChange: (ReaderPreferences) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Font size: ${"%.1fx".format(prefs.fontScale)}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = prefs.fontScale.toFloat(),
                onValueChange = { onChange(prefs.copy(fontScale = it.toDouble().coerceIn(0.5, 2.0))) },
                valueRange = 0.5f..2.0f,
                steps = 14,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Line spacing: ${"%.1f".format(prefs.lineSpacing)}", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = prefs.lineSpacing.toFloat(),
                onValueChange = { onChange(prefs.copy(lineSpacing = it.toDouble().coerceIn(1.0, 1.8))) },
                valueRange = 1.0f..1.8f,
                steps = 7,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("Theme", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReaderTheme.values().forEach { t ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 16.dp),
                    ) {
                        RadioButton(
                            selected = prefs.theme == t,
                            onClick = { onChange(prefs.copy(theme = t)) },
                        )
                        Text(t.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
            Text("Font family", style = MaterialTheme.typography.bodyMedium)
            Column {
                ReaderFontFamily.values().forEach { f ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        RadioButton(
                            selected = prefs.fontFamily == f,
                            onClick = { onChange(prefs.copy(fontFamily = f)) },
                        )
                        Text(f.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 4: ReaderScreen overlays + auto-hide + tap zones**

Replace `app/src/main/java/io/theficos/ereader/ui/reader/ReaderScreen.kt`:

```kotlin
package io.theficos.ereader.ui.reader

import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import io.theficos.ereader.reader.ReaderPreferences
import io.theficos.ereader.reader.toEpubPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication

@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val preferences by viewModel.preferences.collectAsState()
    val chromeVisible by viewModel.chromeVisible.collectAsState()
    var showFontSheet by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(chromeVisible) {
        if (chromeVisible) {
            delay(2_500)
            viewModel.setChromeVisible(false)
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            ReaderUiState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is ReaderUiState.Error -> Text(s.message, Modifier.align(Alignment.Center))
            is ReaderUiState.Open -> {
                ReaderContent(
                    publication = s.publication,
                    initialLocator = s.initialLocator,
                    preferences = preferences,
                    onLocator = viewModel::publishLocator,
                )
                // Center-third tap target overlay — toggles chrome.
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxHeight()
                        .fillMaxWidth(0.34f)
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { viewModel.toggleChrome() })
                        }
                )

                ReaderTopBar(
                    visible = chromeVisible,
                    title = s.document.title,
                    onBack = onClose,
                    onOverflow = { showFontSheet = true },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
                ReaderBottomBar(
                    visible = chromeVisible,
                    chapterTitle = s.savedProgress?.locator?.let {
                        // Locator JSON contains a title for the spine item; we prefer that
                        // if present, otherwise show nothing.
                        runCatching {
                            io.theficos.ereader.reader.ProgressTracker.parseOrNull(it)?.title
                        }.getOrNull()
                    },
                    percent = s.savedProgress?.percent ?: 0.0,
                    onSeek = { /* deferred — Phase 2 wires actual jump */ },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )

                if (showFontSheet) {
                    FontSettingsSheet(
                        prefs = preferences,
                        onChange = { next -> viewModel.updatePreferences(next) },
                        onDismiss = { showFontSheet = false },
                    )
                }
            }
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
        fm.beginTransaction().replace(containerId, nav, tag).commitNow()
        fragment = nav

        val job = activity.lifecycleScope.launch {
            nav.currentLocator.collect { onLocator(it) }
        }

        onDispose {
            job.cancel()
            fragment = null
            fm.beginTransaction().remove(nav).commitNowAllowingStateLoss()
        }
    }

    LaunchedEffect(preferences) {
        fragment?.submitPreferences(preferences.toEpubPreferences())
    }
}
```

- [ ] **Step 5: VM — updatePreferences passthrough**

Add to `ReaderViewModel`:

```kotlin
    fun updatePreferences(next: ReaderPreferences) {
        preferencesStoreRef.update { next }
    }
```

…and capture the store in a member: change the constructor parameter from `preferencesStore: ReaderPreferencesStore` to `private val preferencesStoreRef: ReaderPreferencesStore`. Update the existing line `val preferences: StateFlow<ReaderPreferences> = preferencesStore.flow` to `val preferences: StateFlow<ReaderPreferences> = preferencesStoreRef.flow`.

- [ ] **Step 6: Locator title via ProgressTracker**

The chrome's bottom bar uses `ProgressTracker.parseOrNull(...)?.title`. Confirm `ProgressTracker.parseOrNull` already exists and returns a `Locator` (which has a `title` property). If not, replace the chapter-title argument with `null` and add a TODO comment in the file.

- [ ] **Step 7: Build and visually check on device**

Run: `./gradlew :app:assembleDebug && ./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL. Open a book — chrome appears for ~2.5s then fades. Tap center: chrome reappears. Tap left/right thirds: pages turn (Readium native). Tap overflow: font sheet opens.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/io/theficos/ereader/ui/reader/
git commit -m "feat(reader): chrome with auto-hide, font settings sheet, tap zones"
```

---

### Task 16: End-to-end smoke test and cleanup

**Files:**
- Verify all screens function on a device or emulator.
- Modify (if needed): any rough edges discovered during smoke.

- [ ] **Step 1: Full build, lint, test**

Run, in this exact order:

```bash
./gradlew clean
./gradlew :app:assembleDebug
./gradlew test
./gradlew :app:lintDebug
```

Expected: all green. Read any new lint warnings introduced by this work; fix any in scope (unused imports, contentDescription gaps).

- [ ] **Step 2: Manual smoke on device or emulator**

Install and verify, in order:

1. Launcher icon: oxblood `q` mark visible under round + square masks.
2. App label reads "Quire" in launcher.
3. Library: empty state shows. Bottom nav has three tabs.
4. Catalog tab: configure calibre-web in Settings, return, browse. Cover thumbnails appear (or fallback gradients).
5. Download a book. Badge transitions: download arrow → progress ring → checkmark.
6. Library tab: book appears with cover. "CONTINUE READING" hero appears after opening (and reading a few pages of) one book.
7. Open the book. Chrome auto-hides at ~2.5s. Center tap toggles. Left/right tap turns pages. Overflow opens font sheet. Theme/font/spacing changes live-update.
8. Settings: cards render. Calibre-web fields persist. Reader defaults persist after relaunch.
9. Dark theme: toggle device dark mode. Surfaces flip warm-near-black; oxblood lifts to terracotta.

Record any defects found. Fix any in scope; defer the rest with notes appended to the spec's §11 (Open decisions deferred).

- [ ] **Step 3: Final commit**

If fixes were needed:

```bash
git add <fixed files>
git commit -m "chore: smoke-test fixes"
```

If nothing to fix, skip the commit. Update tasks: all complete.

- [ ] **Step 4: Final summary**

The work is done when:
- All builds green.
- Manual smoke checklist all pass.
- The rebrand is visible from the launcher onward.

---

## Self-review

Spec coverage check (each spec section → task):
- §3.1 Name/applicationId/label → Task 5
- §3.2 Launcher icon → Task 4
- §3.3 Color tokens (light + dark) → Tasks 2, 3
- §3.4 Typography (Lora + system sans) → Tasks 1, 3
- §3.5 Shape & elevation → Task 3
- §4 Bottom-nav structure → Task 13
- §5 Library (hero + cover grid + empty state + long-press) → Task 11
- §6 Catalog (cover grid + download badges + nav rows) → Task 12
- §7 Reader chrome (top, bottom, slider, auto-hide, tap zones) → Task 15
- §7.4 Font settings sheet → Task 15
- §8 Cover fetching (URL extraction + download + Room) → Tasks 6, 7, 8
- §8.1 Cover fallback rendering → Task 10
- §9 Settings restyle → Task 14
- ReaderPreferences extension (fontFamily, lineSpacing) → Task 9

Type/method consistency:
- `coverUrl` (data class field, OPDS) ↔ `coverPath` (domain/Room field) — distinct on purpose: URL is remote, path is local-on-disk.
- `OpdsPublication.coverHref` removed → renamed to `coverUrl`. Only consumer is `CatalogViewModel`, fixed in Task 8.
- `Document.coverPath` added; `DocumentRepository.insert` and `toDomain` updated together; one Room migration ships with it.
- `ReaderPreferences.fontFamily: ReaderFontFamily`, `ReaderPreferences.lineSpacing: Double` introduced in Task 9, consumed in Tasks 14 and 15.
- `ReaderViewModel.chromeVisible: StateFlow<Boolean>` and `toggleChrome()`/`setChromeVisible(Boolean)` introduced in Task 15, called from `ReaderScreen`.
- `ReaderScreen(viewModel, onClose)` signature change — call site updated in Task 13.

No placeholders detected on a final pass.
