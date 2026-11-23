# Quire Web reader compatibility

The web reader renders local EPUB XHTML in a sandboxed frame and uses standard DOM ranges for selection, painting and navigation. The Android reader uses Readium 3.0. The browser keeps Quire's version 3 structural range wire format independent of the renderer. Readium Web was considered for the production reader. The native browser path used here takes the local ZIP's XHTML as input and exposes the exact DOM endpoints and immutable body tree that the Android adapter verifies.

## Isolation

A trusted HTML shell sets the frame's Content Security Policy before the app creates publication nodes. The policy prohibits scripts, form submissions and base URLs, and permits images and fonts only from blob URLs. Publisher content is reconstructed node by node. The renderer removes scripts, event handlers, embeds, navigation attributes, and other active markup. It parses publisher stylesheets without loading them as browser resources, retains presentation declarations from a small allowlist, and turns approved package-relative images and fonts into temporary blob URLs. Authenticated requests, private account state and storage never enter the frame. External links require confirmation in the app and open with `noopener noreferrer`.

The parent owns the frame's event listeners and needs same-origin DOM access. In WebKit, a sandbox without `allow-scripts` suppresses even trusted parent-owned frame listeners, including click and selection events. The sandbox enables listener execution while the shell's enforced `script-src 'none'` and `script-src-attr 'none'` forbid script execution in the frame. Publisher markup cannot set a frame URL, add an executable child or navigate the shell to another document. Browser isolation tests deliberately insert an inline script after mount and verify that the CSP blocks it. They also exercise external CSS, images, SVG subresources, forms, redirects, nested frames and script URLs. Parent listeners still receive events.

## Locators and layouts

Structural anchors store the UTF-8 SHA-256 fingerprint of the chapter's body tree, UTF-16 endpoint offsets and raw range text. Rendering and highlighting leave the chapter text tree untouched. An exact highlight must match the resource, body fingerprint, endpoints and saved quotation. A changed or structurally incompatible chapter leaves the quote and comment available and keeps the reader at its original position. Legacy or mismatched reading positions can fall back to chapter progression. The initial open does not write a new position.

The reader supports vertically scrolling reflowable chapters, page and chapter controls, right-to-left chapter direction, and fit-to-viewport XHTML pages that declare numeric viewport dimensions. It uses a limited subset of publisher CSS and does not reproduce EPUB spreads, package-only fixed-layout declarations or every publisher layout. Unsupported active content, malformed HTML tree repair and parser differences can change the structural fingerprint. The bundled Literata font is covered by its [SIL Open Font License](../reader/src/main/assets/fonts/OFL-Literata.txt). Package-local fonts can be used by choosing Publisher font.

## Reproduction

From `web`, run `pnpm build` followed by `pnpm exec playwright test e2e/reader.spec.ts`. The browser fixture runs the existing Android JavaScript adapter against the same repeated XHTML paragraphs with inline markup and a long paragraph. It checks the exact second occurrence in both directions and a revised-chapter negative case. Other cases exercise frame isolation, fixed viewport sizing and a local EPUB font, and reload a private pause after a type change and chapter navigation.

This fixture tests the shipped range algorithm in a browser. Android APK round-trip, different Android layout profiles, and physical mobile touch interaction are separate checks.
