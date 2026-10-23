# Translating Quire

Quire welcomes translation fixes and new languages. English is the source
language in `app/src/main/res/values/strings*.xml`. Spanish, Italian, French and
German are included in `values-es/`, `values-it/`, `values-fr/` and `values-de/`.
You can edit these XML files in any text editor; Android Studio, a server and an
AI API key are not required to contribute wording.

## Improve an existing language

1. Find the English sentence in `app/src/main/res/values/strings*.xml`.
   Its `name` is the stable key. Find that key in the language folder.
2. Change the translated text while keeping the key and resource type.
   A contribution can be as small as one corrected sentence.
3. Open a pull request and explain the wording or regional usage. For layout
   issues, include the screen, device size, font size and a screenshot.

Translations follow the [contribution and license rules](../CONTRIBUTING.md),
including the [CLA](../CLA.md). Native speakers are welcome to review the
included translations and improve wording in context.

## Add a language

From the repository root, with Python 3.9 or newer installed:

```sh
scripts/new-translation pt
```

This copies only translatable English strings and plurals into `values-pt/`.
It refuses to overwrite an existing folder and leaves no partial locale if an
input file cannot be parsed. Translate every entry before submitting; the
copied English is a starting point, not a translation.

Language-only qualifiers such as `pt` usually reach the most people. Use
`pt-rBR` for a regional variant or `b+zh+Hans` for a script qualifier when needed.
Do not include `values-` or a path in the command. You may also create the files
manually, following the existing feature filenames.

A language contribution needs only its complete `values-xx/` XML files.
The build discovers language folders and generates Android's per-app language
configuration automatically. No Kotlin changes, language registry or manually
maintained locale list are needed. English remains the fallback for unsupported
languages. Keep English source copy and new app features complete in all shipped
languages when adding resources.

## Preserve the resource contract

- Keep `name` attributes and resource types unchanged. Include accessibility
  labels, error messages and every other translatable key. Do not copy themes,
  colors or resources marked `translatable="false"`, such as the app name.
- Preserve format arguments such as `%1$s` and `%2$d`. `s` is text and `d` is an
  integer. You can reorder numbered arguments to fit your language, but must
  not drop or change them. Use `%%` for a literal percent in formatted text.
- For `<plurals>`, include `quantity="other"` and every grammatical form needed
  by your language (`zero`, `one`, `two`, `few`, `many`, `other`). Android selects
  the form. Each form preserves its matching English arguments; extra forms
  follow the English `other` argument contract.
- Keep string-array entries in their original order if arrays are added.
  Preserve line breaks and intentional leading/trailing spaces. The outer
  quotes in resource values preserve Android whitespace; retain them.
- Escape straight apostrophes as `\'`, literal double quotes as `\"`, and XML
  characters such as `&` as `&amp;`. Typographic apostrophes such as `’` need no
  Android escaping.
- Use concise buttons, sentence case and familiar verbs. Keep Quire,
  calibre-web, OPDS, EPUB, ISBN, URLs, model identifiers and font names unchanged.
- Keep privacy and deletion instructions precise. Distinguish removing a local
  downloaded file from deleting a library entry and its reading progress.
  Generic OPDS catalogs save progress on the device; sync and AI require a
  Quire server. AI remains opt-in. Do not turn these distinctions into promises
  the app does not make.

## Shared vocabulary

| English | Spanish | Italian | French | German |
|---|---|---|---|---|
| Library | Biblioteca | Biblioteca | Bibliothèque | Bibliothek |
| Catalog | Catálogo | Catalogo | Catalogue | Katalog |
| Insights | Análisis | Approfondimenti | Analyses | Einblicke |
| Reader profile | Perfil lector | Profilo di lettura | Profil de lecture | Leseprofil |
| Finished | Terminados | Completati | Terminés | Beendet |
| Abandoned | Abandonados | Abbandonati | Abandonnés | Abgebrochen |
| Sync | Sincronización | Sincronizzazione | Synchronisation | Synchronisierung |

## Check your changes

The focused check uses Docker and the repository's build wrapper:

```sh
scripts/dgradle :app:testDebugUnitTest --tests '*StringParityTest' --no-watch-fs
```

It discovers every locale and rejects missing or unexpected keys, duplicates,
blank values, mismatched format arguments, missing plural `other` forms and
changed array lengths. Numbered arguments may move. These checks also run with
the normal test suite in CI. They verify structure; native-language review and
reading the whole screen still matter.

For a complete Android check:

```sh
scripts/dgradle :app:assembleDebug test lint --no-watch-fs
```

On Android 13 or newer, select the language in Android Settings → Apps → Quire →
Language. Older Android versions use the device's preferred supported language.
Check welcome/setup, library, catalog, reader controls, scan results, insights
and Settings, including one/many counts and larger font sizes. Restore the
previous language after testing someone else's device.

Interface language and **Settings → Insight language** are independent. The
latter controls generated AI content, with Auto following the book's language.
Books, titles, authors, catalog descriptions, model-generated content and
server-supplied explanations and diagnostic messages retain their original text. Translating the
interface does not translate an EPUB or change stored data. Store-listing copy
in `fastlane/metadata/android/` is a separate contribution.
