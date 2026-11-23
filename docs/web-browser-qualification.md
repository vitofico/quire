# Quire Web browser qualification

## Accessibility and offline

The Playwright checks run the production shell and reader with an imported Arabic EPUB. The keyboard path passed in desktop Chromium, Firefox and WebKit in the Linux browser suite. It reached the skip link, opened import, entered the reader, moved focus into panels, dismissed them with Escape, and returned to the book details landmark. The mock sign-in test typed and submitted credentials with the keyboard after focusing the email field. The annotation test set an EPUB text range programmatically and used the keyboard to comment and save. It checked that the frame leaves Shift+Arrow available to the browser and that ArrowLeft advances this RTL book. After the preview origin dropped the browser’s requests, an offline reload restored the second chapter and preserved the saved comment using the built service worker and IndexedDB.

The library, settings, discovery and reader fit a 320 CSS px viewport. The reader remained usable at 200% CSS layout zoom on a desktop viewport. These checks use responsive layout and CSS zoom emulation. Reduced-motion emulation preserved immediate scrolling. Normal text and links met 4.5:1 in the tested shell and reader palettes. Interactive outlines met 3:1 against the shell surfaces. Publisher-supplied chapter styling can differ from these palettes.

The French discovery browser test verifies explicit AI opt-in, metadata-only generation and localized quota feedback in AI-only mode. Unit tests cover translated discovery headings in Spanish, Italian, French and German. English is used throughout the keyboard test.

Physical mobile Safari and Android Chrome were unavailable for this run. Touch selection, native browser zoom, installed-app cold restart and memory on a phone remain open. Screen-reader navigation, native keyboard caret selection in the EPUB and an APK round trip also remain separate release gates.

## Performance baseline

Desktop Chromium on macOS arm64 and in the Linux browser test environment, using Playwright 1.64. The shelf uses a populated IndexedDB with 1,500 books, 750 progress records and 5,000 passages. The first shelf measurement starts at navigation in a new tab with HTTP cache and the service worker disabled. Search includes automation and render time. The scroll measurement is the p95 frame interval for 30 scripted scroll steps. The EPUB contains 32 illustrated chapters. Chapter timing starts after EPUB import and includes reading it back, opening it and decoding the first image. Memory is the Chromium renderer’s post-GC JavaScript heap plus backing-storage increase. Native image caches, total process RSS and peak import memory are outside that figure.

| Measurement | macOS | Linux | Desktop Chromium budget |
| --- | ---: | ---: | ---: |
| Cold shelf render | 188 ms | 209 ms | < 1,500 ms |
| Shelf search | 37 ms | 43 ms | < 500 ms |
| Highlight and comment search | 132 ms | 179 ms | < 700 ms |
| Long-shelf scroll p95 | 17 ms | 17 ms | < 55 ms |
| First chapter of a 32.06 MiB EPUB | 871 ms | 851 ms | < 3,500 ms |
| Retained renderer heap and backing storage increase | 34.6 MiB | 34.6 MiB | < 150 MiB |

The browser CI suite runs the accessibility checks and enforces the Chromium budgets. To reproduce from `web/` after installing the Playwright browsers, run `pnpm build` and `pnpm test:e2e accessibility.spec.ts performance.spec.ts discovery.spec.ts`.
