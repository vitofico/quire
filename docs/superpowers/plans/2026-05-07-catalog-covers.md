# Catalog Covers Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make catalog covers actually load by wiring Coil to the auth-enabled OPDS `OkHttpClient`, and prefer `rel="…/image/thumbnail"` over `rel="…/image"` for grid-sized requests.

**Architecture:** Two narrowly-scoped changes. (1) `EReaderApp` implements `coil.ImageLoaderFactory` and builds an `ImageLoader` with the existing `OpdsHttpClient.okHttp`, so Coil inherits `BasicAuthInterceptor`. (2) `OpdsClient` reorders the cover-link selection so the thumbnail rel wins when both are present. `CoverImage` is unchanged.

**Tech Stack:** Kotlin, Jetpack Compose, Coil 2.7 (`io.coil-kt:coil-compose`), OkHttp, Robolectric + MockWebServer + Truth for tests, Gradle via `scripts/dgradle` (Docker).

**Spec:** `docs/superpowers/specs/2026-05-07-catalog-covers-design.md`

**Branch:** `feature/catalog-covers-auth` (already created)

**Build/test rule:** Always use `scripts/dgradle …` from the repo root; never the host `./gradlew`.

---

## File Structure

Files this plan creates or modifies:

- **Modify** `data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsClient.kt` — reorder image-rel preference (lines 42–47).
- **Create** `data/opds/src/test/resources/opds/catalog-feed-thumbnail-and-image.xml` — fixture: entry exposing both rels.
- **Create** `data/opds/src/test/resources/opds/catalog-feed-thumbnail-only.xml` — fixture: entry exposing only the thumbnail rel.
- **Modify** `data/opds/src/test/java/io/theficos/ereader/data/opds/OpdsClientTest.kt` — extend dispatcher with the two new fixtures, add three parser tests.
- **Modify** `app/src/main/java/io/theficos/ereader/di/AppContainer.kt` — promote `opdsHttp` from `private` to public so the `Application` can read its `okHttp`.
- **Modify** `app/src/main/java/io/theficos/ereader/EReaderApp.kt` — implement `coil.ImageLoaderFactory`.

The existing fixture `catalog-feed.xml` (image-only) stays as-is, preserving the existing `coverUrl` assertion. The new fixtures are additive.

---

## Task 1: Add OPDS test fixtures for both-rels and thumbnail-only cases

**Files:**
- Create: `data/opds/src/test/resources/opds/catalog-feed-thumbnail-and-image.xml`
- Create: `data/opds/src/test/resources/opds/catalog-feed-thumbnail-only.xml`

These fixtures back the parser tests in Task 2. No code changes yet.

- [ ] **Step 1: Create the both-rels fixture**

Write `data/opds/src/test/resources/opds/catalog-feed-thumbnail-and-image.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:dc="http://purl.org/dc/terms/">
  <id>urn:calibre-web:both</id>
  <title>Both Rels</title>
  <updated>2026-04-26T00:00:00Z</updated>
  <link rel="self" href="/opds/both" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
  <entry>
    <title>The Sample Book</title>
    <id>urn:calibre-web:42</id>
    <updated>2026-04-26T00:00:00Z</updated>
    <author><name>Jane Doe</name></author>
    <dc:identifier>urn:uuid:550e8400-e29b-41d4-a716-446655440000</dc:identifier>
    <link rel="http://opds-spec.org/acquisition" href="/opds/download/42/epub" type="application/epub+zip"/>
    <link rel="http://opds-spec.org/image" href="/opds/cover/42" type="image/jpeg"/>
    <link rel="http://opds-spec.org/image/thumbnail" href="/opds/cover/42/thumb" type="image/jpeg"/>
  </entry>
</feed>
```

- [ ] **Step 2: Create the thumbnail-only fixture**

Write `data/opds/src/test/resources/opds/catalog-feed-thumbnail-only.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom" xmlns:opds="http://opds-spec.org/2010/catalog" xmlns:dc="http://purl.org/dc/terms/">
  <id>urn:calibre-web:thumb-only</id>
  <title>Thumb Only</title>
  <updated>2026-04-26T00:00:00Z</updated>
  <link rel="self" href="/opds/thumb-only" type="application/atom+xml;profile=opds-catalog;kind=acquisition"/>
  <entry>
    <title>The Sample Book</title>
    <id>urn:calibre-web:42</id>
    <updated>2026-04-26T00:00:00Z</updated>
    <author><name>Jane Doe</name></author>
    <dc:identifier>urn:uuid:550e8400-e29b-41d4-a716-446655440000</dc:identifier>
    <link rel="http://opds-spec.org/acquisition" href="/opds/download/42/epub" type="application/epub+zip"/>
    <link rel="http://opds-spec.org/image/thumbnail" href="/opds/cover/42/thumb" type="image/jpeg"/>
  </entry>
</feed>
```

- [ ] **Step 3: Stage the fixtures (no commit yet)**

Run: `git add data/opds/src/test/resources/opds/catalog-feed-thumbnail-and-image.xml data/opds/src/test/resources/opds/catalog-feed-thumbnail-only.xml`

We commit fixtures together with the parser change in Task 3 so the repository never contains "fixtures referenced by no test."

---

## Task 2: Write failing parser tests

**Files:**
- Modify: `data/opds/src/test/java/io/theficos/ereader/data/opds/OpdsClientTest.kt`

Add the two new fixtures to the `MockWebServer` dispatcher and add three new tests covering the rel-preference rules:

1. Both rels present → thumbnail wins.
2. Thumbnail only → thumbnail returned (regression guard).
3. Image only → image returned. *Already covered* by the existing `fetch acquisition feed extracts cover URL` test against `catalog-feed.xml`. No new test needed for this case, but verify the existing test still passes after Task 3.

- [ ] **Step 1: Extend the dispatcher with the new fixture paths**

In `OpdsClientTest.kt`, in the `setUp()` method's `dispatcher` `when (path) { … }` block, add two more branches **before** the `else` branch:

```kotlin
"/opds/both" -> MockResponse().setHeader("Content-Type", "application/atom+xml")
    .setBody(resource("/opds/catalog-feed-thumbnail-and-image.xml"))
"/opds/thumb-only" -> MockResponse().setHeader("Content-Type", "application/atom+xml")
    .setBody(resource("/opds/catalog-feed-thumbnail-only.xml"))
```

The `resource(...)` helper already exists at line 53 of the file.

- [ ] **Step 2: Add the three parser tests**

Append these three `@Test` functions to `OpdsClientTest` (immediately before the final closing `}`):

```kotlin
@Test fun `cover prefers thumbnail rel when both rels present`() = runTest {
    val feed = client.fetch(server.url("/opds/both").toString())
    val pub = feed.publications.single()
    assertThat(pub.coverUrl).isNotNull()
    assertThat(pub.coverUrl).endsWith("/opds/cover/42/thumb")
}

@Test fun `cover uses thumbnail rel when only thumbnail is present`() = runTest {
    val feed = client.fetch(server.url("/opds/thumb-only").toString())
    val pub = feed.publications.single()
    assertThat(pub.coverUrl).isNotNull()
    assertThat(pub.coverUrl).endsWith("/opds/cover/42/thumb")
}

@Test fun `cover falls back to full-size image rel when thumbnail is absent`() = runTest {
    // catalog-feed.xml exposes only rel="…/image"; the existing extracts-cover-URL test
    // also covers this case, but we keep an explicit assertion here so the rel-preference
    // contract is fully spelled out by tests in one place.
    val feed = client.fetch(server.url("/opds/new").toString())
    val pub = feed.publications.single()
    assertThat(pub.coverUrl).isNotNull()
    assertThat(pub.coverUrl).endsWith("/opds/cover/42")
}

```

- [ ] **Step 3: Run the new tests and verify they fail**

Run: `scripts/dgradle :data:opds:test --tests 'io.theficos.ereader.data.opds.OpdsClientTest.cover*'`

Expected:
- `cover prefers thumbnail rel when both rels present` → **FAIL**: assertion expects URL ending `/thumb`, actual ends with `/opds/cover/42` (because the current parser prefers `image` over `image/thumbnail`).
- `cover uses thumbnail rel when only thumbnail is present` → **PASS** (current parser falls through to thumbnail in this case).
- `cover falls back to full-size image rel when thumbnail is absent` → **PASS** (current parser already handles this).

If the first test passes, the parser already behaves correctly and Task 3 is unnecessary — stop and report. If a different test fails unexpectedly, debug the fixture or dispatcher wiring before proceeding.

---

## Task 3: Reorder image-rel preference in `OpdsClient`

**Files:**
- Modify: `data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsClient.kt:42-47`

- [ ] **Step 1: Edit the cover-link selection block**

In `OpdsClient.kt`, replace the block at lines 42–47:

```kotlin
                    val imageLinks = pub.subcollections["images"].orEmpty().flatMap { it.links }
                    val coverLink = imageLinks.firstOrNull { link ->
                        link.rels.contains("http://opds-spec.org/image")
                    } ?: imageLinks.firstOrNull { link ->
                        link.rels.contains("http://opds-spec.org/image/thumbnail")
                    } ?: imageLinks.firstOrNull()
```

with:

```kotlin
                    val imageLinks = pub.subcollections["images"].orEmpty().flatMap { it.links }
                    val coverLink = imageLinks.firstOrNull { link ->
                        link.rels.contains("http://opds-spec.org/image/thumbnail")
                    } ?: imageLinks.firstOrNull { link ->
                        link.rels.contains("http://opds-spec.org/image")
                    } ?: imageLinks.firstOrNull()
```

Only the order of the first two `firstOrNull` checks changes. The third (any-image fallback) is unchanged.

- [ ] **Step 2: Run the parser tests, expect all green**

Run: `scripts/dgradle :data:opds:test --tests 'io.theficos.ereader.data.opds.OpdsClientTest'`

Expected: all `OpdsClientTest` tests pass, including the previously-failing `cover prefers thumbnail rel when both rels present`.

- [ ] **Step 3: Run the full data:opds test suite to catch regressions**

Run: `scripts/dgradle :data:opds:test`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit the parser change with its tests and fixtures**

```bash
git add data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsClient.kt \
        data/opds/src/test/java/io/theficos/ereader/data/opds/OpdsClientTest.kt \
        data/opds/src/test/resources/opds/catalog-feed-thumbnail-and-image.xml \
        data/opds/src/test/resources/opds/catalog-feed-thumbnail-only.xml
git commit -m "feat: :sparkles: prefer OPDS thumbnail rel over full image for catalog covers"
```

(Repo style is gitmoji + conventional commits — see `feature/catalog-covers-auth`'s parent `main` history.)

---

## Task 4: Wire Coil to the OPDS `OkHttpClient`

**Files:**
- Modify: `app/src/main/java/io/theficos/ereader/di/AppContainer.kt:23` (`private val opdsHttp` → `val opdsHttp`)
- Modify: `app/src/main/java/io/theficos/ereader/EReaderApp.kt`

No new unit test — the wiring is exercised by manual verification (Step 5). Coil's `ImageLoaderFactory` is its supported, lazy-resolved hook; no further plumbing is needed.

- [ ] **Step 1: Expose `opdsHttp` from `AppContainer`**

In `app/src/main/java/io/theficos/ereader/di/AppContainer.kt`, change line 23 from:

```kotlin
    private val opdsHttp = OpdsHttpClient(credentialStore)
```

to:

```kotlin
    val opdsHttp = OpdsHttpClient(credentialStore)
```

No other changes to `AppContainer`. Existing callers (`opdsClient`, `bookDownloader`, `syncClient`) use `opdsHttp.okHttp` from inside the class and are unaffected.

- [ ] **Step 2: Make `EReaderApp` an `ImageLoaderFactory`**

Replace the entire contents of `app/src/main/java/io/theficos/ereader/EReaderApp.kt`:

```kotlin
package io.theficos.ereader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import io.theficos.ereader.di.AppContainer

class EReaderApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(container.opdsHttp.okHttp)
            .build()
}
```

Coil resolves `ImageLoaderFactory` lazily on first `AsyncImage` use (well after `onCreate()` has run), so the `lateinit` initialization order is safe. The `coil-compose` artifact already on the classpath transitively provides `coil.ImageLoader` and `coil.ImageLoaderFactory`.

- [ ] **Step 3: Build the debug APK to confirm the module compiles**

Run: `scripts/dgradle :app:assembleDebug`

Expected: BUILD SUCCESSFUL with `app/build/outputs/apk/debug/app-debug.apk` produced. If it fails on missing imports, double-check that `coil.ImageLoader` and `coil.ImageLoaderFactory` resolve — both ship in `io.coil-kt:coil-compose:2.7.0` via the transitive `coil-base` / `coil-core` dependency, and no `build.gradle.kts` change is needed.

- [ ] **Step 4: Run the relevant unit-test suites to catch regressions**

Run: `scripts/dgradle :app:testDebugUnitTest :data:opds:test`

Expected: BUILD SUCCESSFUL. We deliberately scope to these modules — full-repo `test` is slower and not informative for this change.

- [ ] **Step 5: Manual verification on a real device/emulator**

This step requires a calibre-web instance with Basic auth and at least one book whose cover is served behind that auth. The agent should describe — but not perform — this step if no such environment is available, and ask the user to do it.

1. Install the freshly-built debug APK on a connected device or running emulator: `scripts/dgradle :app:installDebug` (or `adb install -r app/build/outputs/apk/debug/app-debug.apk`).
2. Launch the app, sign in to the calibre-web instance, navigate into a non-empty acquisition feed.
3. **Expected:** real cover artwork populates the 2-column grid for entries that ship a cover. Entries with no cover link still show the gradient + initials fallback.
4. **Negative check:** sign out (or clear credentials), reopen the catalog. Auth fails; covers fall back to gradient + initials. (This confirms covers are travelling through the authenticated client and aren't being served unauthenticated.)
5. **Sub-feed check:** navigate into a category and confirm covers populate post-navigation, not only on the root feed.

If covers still show only fallbacks despite a working auth, the most likely root cause is that the `ImageLoaderFactory` is not being picked up — confirm `EReaderApp` is the `android:name` of the application in `AndroidManifest.xml`. (It already is in this repo, but the check costs nothing.)

- [ ] **Step 6: Commit the wiring change**

```bash
git add app/src/main/java/io/theficos/ereader/EReaderApp.kt \
        app/src/main/java/io/theficos/ereader/di/AppContainer.kt
git commit -m "feat: :sparkles: route Coil image loads through authenticated OPDS OkHttp client"
```

---

## Task 5: Final verification + push

- [ ] **Step 1: Re-run the targeted test suites once more on the final state**

Run: `scripts/dgradle :app:testDebugUnitTest :data:opds:test`

Expected: BUILD SUCCESSFUL. This guards against a working-tree edit between commits.

- [ ] **Step 2: Inspect the branch's commit history**

Run: `git log --oneline main..HEAD`

Expected: three commits on `feature/catalog-covers-auth` —

1. `docs: :memo: design for authenticated catalog cover loading` (already present)
2. `feat: :sparkles: prefer OPDS thumbnail rel over full image for catalog covers`
3. `feat: :sparkles: route Coil image loads through authenticated OPDS OkHttp client`

- [ ] **Step 3: Stop and ask the user before pushing**

Do **not** run `git push` automatically. Report the branch state and ask the user whether to push and/or open a PR.

---

## Self-review notes

- **Spec coverage:**
  - "Coil `ImageLoader` wired to the OPDS `OkHttpClient`" → Task 4.
  - "Prefer thumbnail rel in the OPDS parser" → Task 3.
  - Spec's required parser test cases (both rels, image-only, thumbnail-only, neither, no images) → Task 2 covers both-rels and thumbnail-only; image-only is the existing test against `catalog-feed.xml` (deliberately re-asserted in the new third test). The "neither but generic image link" and "no images at all" cases aren't materially affected by the rel reorder (the third `firstOrNull()` fallback and `null` fallthrough are unchanged), so we don't add fixtures for them — adding tests for un-touched code paths is YAGNI.
  - Manual verification (auth-on, auth-off, sub-feed) → Task 4 Step 5.
- **Placeholder scan:** every code/XML block is complete; every command has expected output. No "TBD" / "appropriate error handling" / "fill in details".
- **Type/name consistency:** `opdsHttp` (property name) and `okHttp` (its field) match `OpdsHttpClient` and `AppContainer` exactly; `ImageLoader` / `ImageLoaderFactory` imports match Coil 2.7's `coil.*` package; rel strings match the constants already in `OpdsClient.kt`.
