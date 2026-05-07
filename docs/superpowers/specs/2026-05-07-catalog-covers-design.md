# Catalog covers: authenticated loading + thumbnail-first parsing

Date: 2026-05-07
Status: Approved (design)

## Problem

The catalog UI already renders a `CoverImage` per publication in a 2-column
grid, and `OpdsClient` already extracts an `image`/`image/thumbnail` link into
`OpdsPublication.coverUrl`. In practice users see only the gradient+initials
fallback, never the real cover art.

Root cause: Coil is not configured with the auth-enabled OPDS `OkHttpClient`.
With no `ImageLoaderFactory` registered on the `Application`, Coil uses its
default singleton, which has no `BasicAuthInterceptor`. Cover requests to a
calibre-web instance therefore return 401 and the `error =` branch of
`SubcomposeAsyncImage` falls back to initials.

A secondary issue: when a feed exposes both `rel="…/image"` and
`rel="…/image/thumbnail"`, the parser currently prefers full-resolution. For a
2-column grid the thumbnail is the appropriate choice and saves bandwidth.

## Goals

- Covers visible in the catalog grid for auth-protected calibre-web feeds.
- Use the lightweight thumbnail link when the server provides one.
- No regression for feeds that only emit one of the two image rels.

## Non-goals

- Custom placeholder shimmer, prefetching, image transformations.
- Per-host auth strategies (the OPDS client already encapsulates this).
- Re-styling `CoverImage` or the catalog grid layout.
- Offline cover storage beyond Coil's default disk cache.

## Design

### Change 1 — Coil `ImageLoader` wired to the OPDS `OkHttpClient`

`EReaderApp` (`app/src/main/java/io/theficos/ereader/EReaderApp.kt`)
implements `coil.ImageLoaderFactory`. Its `newImageLoader()` builds an
`ImageLoader` against the existing `OpdsHttpClient.okHttp` instance held by
`AppContainer`, so every Coil request inherits `BasicAuthInterceptor` (and any
future auth additions) automatically.

`AppContainer` currently keeps `opdsHttp` private; expose either `opdsHttp` or
its `okHttp` property so `EReaderApp.newImageLoader()` can read it.
Construction order is unchanged: `AppContainer` is built in
`Application.onCreate()` before any composition runs, and Coil reads
`ImageLoaderFactory` lazily on first `AsyncImage` use, so there is no race.

Coil's default memory and disk caches are sufficient. No custom cache config.

`CoverImage` is unchanged. It already:

- Accepts `Any?` as `source`.
- Renders a gradient+initials fallback on `loading` and `error`.
- Crops with `ContentScale.Crop` at a 2:3 aspect ratio.

### Change 2 — Prefer thumbnail rel in the OPDS parser

In `data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsClient.kt`
(currently lines 37–53), reorder the cover-link selection:

1. `rel="http://opds-spec.org/image/thumbnail"` — preferred for list/grid use.
2. `rel="http://opds-spec.org/image"` — full-resolution fallback.
3. First image link of any rel — last-ditch fallback (unchanged).

URL absolutization (`absolutize(absoluteUrl, …)`) is unchanged. No other
fields on `OpdsPublication` are affected.

## Testing

### Unit tests (`data/opds`)

Add a parser test fixture covering:

- Feed entry with **both** `image` and `image/thumbnail` → `coverUrl` is the
  thumbnail href.
- Feed entry with **only** `image` → `coverUrl` is the `image` href.
- Feed entry with **only** `image/thumbnail` → `coverUrl` is the thumbnail
  href (regression guard).
- Feed entry with **neither** but a generic image link → `coverUrl` is that
  link's href.
- Feed entry with **no images at all** → `coverUrl` is `null`.

### Manual verification

- Point the app at a calibre-web instance that requires Basic auth and exposes
  cover thumbnails. Browse the catalog and confirm real covers populate the
  grid (not the gradient fallback).
- Sign out / clear credentials, reopen the catalog, confirm covers fall back
  to gradient+initials (auth correctly fails — no spurious caching of the
  authenticated bytes under an unauthenticated identity).
- Browse a sub-feed (after navigating into a category) to confirm the same
  works post-navigation, not only on the root feed.

No UI tests required — `CoverImage` and `CatalogScreen` are not modified.

## Risks and edge cases

- **Coil singleton init timing.** Coil reads `ImageLoaderFactory` lazily, so
  the factory is consulted on first `AsyncImage` use — well after
  `Application.onCreate()`. No special handling needed.
- **Cross-host thumbnails.** A few OPDS servers serve thumbnails on a
  separate, unauthenticated host. The shared interceptor only adds the
  `Authorization` header when a credential is stored, and external hosts
  ignore the header gracefully, so a single shared `OkHttpClient` is fine.
- **Coil disk cache and credential changes.** Coil keys cache entries on URL.
  If two users sign in to the same calibre-web URL on the same device, the
  second user could see the first user's cached thumbnail. Acceptable: cover
  images are not sensitive, and the cache lives only on-device. Document but
  do not mitigate.

## Files touched

- `app/src/main/java/io/theficos/ereader/EReaderApp.kt` — implement
  `ImageLoaderFactory`.
- `app/src/main/java/io/theficos/ereader/di/AppContainer.kt` — expose
  `opdsHttp` (or its `okHttp`) for reuse by the image loader.
- `data/opds/src/main/java/io/theficos/ereader/data/opds/OpdsClient.kt` —
  reorder image-rel preference.
- `data/opds/src/test/...` — new parser tests for the rel-preference cases
  above (test directory and existing test class to be located during
  implementation).

## Out of scope / follow-ups

- Cover prefetching as the user scrolls.
- Negotiating thumbnail size with servers that support `?width=` parameters.
- A shared `ImageLoader` instance in `AppContainer` for non-`AsyncImage`
  callers (none exist today).
