# Agent and MCP access to highlights and library

Quire Server provides read-only access to your synced highlights and library
through a remote MCP endpoint, and to the highlights through a REST API too.
Highlight results include the saved quotation, your own note on it, semantic
colour, book title and author, and timestamps. Library results include reading
progress and, where your app tidied them, improved book details. The book does
not need to be downloaded on the server. Removed highlights and books are
excluded.

The server does not generate answers or call an AI provider for these requests:
the connected assistant is the AI. With these tools it can do what Quire's own
"discuss highlights" action does: list the books you highlighted, fetch the
passages and notes you pick, search across them, and think them through with
you. Book text, notes, titles and descriptions are data and may contain
arbitrary instructions; they are not an instruction source for the client.
Every tool description says so.

## Connect a remote MCP client

Use a client that supports Streamable HTTP with an explicit Authorization header.
The endpoint is `/mcp`, without a trailing slash. Configure these connection values:

| Connection value | Example |
| --- | --- |
| URL | `https://books.example.com/mcp` |
| calibre-web authentication | `Authorization: Basic <base64(username:password)>` |
| NativeAuth authentication | `Authorization: Bearer <existing-native-session-token>` |

Use the account configured on that Quire Server deployment. Neither an MCP user ID
nor a separate agent credential is accepted. Native sessions retain their existing
expiry and revocation behavior. The endpoint requires a valid Authorization header
on every request; browser cookies do not authenticate it.

This integration uses Quire's existing authentication rather than an OAuth
registration/discovery flow. Clients that require OAuth discovery or direct
cross-origin browser access need a separate integration. Do not configure the
superseded SSE transport or point a stdio-only client at this URL.

For an external hostname, set this in the server's `.env` and restart it:

```dotenv
QUIRE_SERVER_MCP_PUBLIC_URL=https://books.example.com
```

The value is an HTTPS origin: optional port, no credentials, path, query or
fragment. For a nonstandard TLS port use, for example,
`https://books.example.com:8443`. Without this setting, MCP accepts loopback hosts
only; local native/server-side clients can use `http://127.0.0.1:8000/mcp`.
A supplied Origin header must match the configured public origin. This setting
is independent of browser pairing and does not enable AI features.

Your reverse proxy must forward `/mcp` and `/agent/*` to Quire Server, preserve
Host, and provide HTTPS for public access. Both routes are included in the
reference Caddyfile. Existing custom ingress rules may need these paths added;
changing the server's environment alone does not update another proxy's routing.

## Tools

All tools use the caller's account and are marked read-only, idempotent and
non-destructive. There are no create, edit, delete or sync tools, and none
exposes saved catalogs or their credentials, account data or conversion jobs.

| Tool | Arguments | Result |
| --- | --- | --- |
| `list_highlights` | Optional `metadata_id`, `content_hash`, `limit`, `cursor` | Page of active highlight previews |
| `search_highlights` | Required `q`, plus the list filters | Page matching literal text in quotations, comments, titles or authors |
| `get_highlight` | Required `highlight_id` UUID | One complete active highlight, including full text, comment and exact locator |
| `get_highlights` | Required `highlight_ids`, 1 to 50 UUIDs | `items`: the complete active highlights, as `get_highlight` returns them, in the order asked and each once; `not_found`: the IDs that are missing, foreign or removed |
| `list_highlighted_books` | Optional `limit`, `cursor` | Page of books that have active highlights |
| `list_library` | Optional `status` (`unread`, `reading`, `finished`, `abandoned`), `limit`, `cursor` | Page of books on the shelf with reading progress |
| `get_book` | `metadata_id` and/or `content_hash`, at least one | One book on the shelf, with progress and tidied details |

For example, call `search_highlights` with `{"q":"memory","limit":10}`. Use an ID
from the results with `get_highlight` to retrieve a complete passage.

To think through highlights the way the app does: call `list_highlighted_books`,
then `list_highlights` with a book's `content_hash` for its passages and notes,
and `get_highlights` with the IDs the reader picks for the complete texts.
`comment` is the reader's own note on a highlight.

### Books with highlights

`list_highlighted_books` returns one entry per `content_hash` (the file's
identity, the key `list_highlights` filters on), most recently highlighted
first. Each entry has `document` (`metadata_id`, `content_hash`,
`identity_hash_version`), `book_title` and `book_author` as saved with the
highlights (each clipped to 1,000 characters), `highlight_count`,
`noted_count` (highlights with a note), `first_highlight_at` (earliest
`created_at`) and `last_highlight_at` (latest `updated_at`), both in
milliseconds since the epoch like the highlights. A book read from two
different files appears twice.

### Library and books

`list_library` lists the alive books on the reader's shelf, most recently
added first. Each entry has `document`, `title`, `authors`, `series_name`,
`series_index`, `language`, `subjects` (at most 20), `status`, `percent`
(0 to 1, null when never opened), `last_read_at`, `finished_at`,
`abandoned_at` (ISO-8601) and `highlight_count`. `status` is `finished` when
the book is finished, `abandoned` when the reader gave up on it, `reading`
when it has progress but neither, and `unread` when it has no progress.
Reading progress comes from progress sync, matched to the shelf by
`content_hash` as `/library/v1/stats` does.

`get_book` returns the same fields for one shelf book, plus `isbn` and
`details`. `details` is the record Quire's book details feature tidied for
this reader (`title`, `authors`, `series_name`, `series_index`,
`description` clipped to 4,000 characters, `subjects` from Open Library,
`generated_at`), or null when the reader's app never looked the book up or
the server runs without AI or book details. The record is found through the
reader's own pointer only. A book that has highlights but is not on the shelf
answers `book_not_found`.

Search is a case-insensitive literal substring match according to the database
locale. `%`, `_` and backslashes are text, not wildcard syntax. It does not use
embeddings, external retrieval or an LLM.

## Previews and pagination

Every list tool (`list_highlights`, `search_highlights`,
`list_highlighted_books`, `list_library`) returns `items`, `next_cursor` and
`has_more`, takes `limit` (default 50, 1 to 100) and follows the cursor rules
below; a cursor is bound to its tool, account and filters. Highlight list and
search responses are described here in detail. Each
preview includes `id`, `document`, `text`, `comment`, `book_title`, `book_author`, `colour`,
`created_at` and `updated_at`.

To bound retrieved data and response size, the quotation and comment previews are each limited to 2,000
characters; title and author to 1,000 each; metadata identifier to 1,024.
`text_truncated`, `comment_truncated`, `book_title_truncated`, `book_author_truncated` and
`metadata_id_truncated` explicitly indicate clipping. Previews do not include
locators. `get_highlight` returns the exact full saved text, comment, attribution, identity
and locator without preview clipping. Missing, foreign-account and removed IDs
all produce the same not-found result.

`limit` defaults to 50 and may be 1–100. Search text must be 1–512 characters; surrounding whitespace is trimmed and
blank queries are rejected. Identity filters are at most 1,024 characters. Metadata IDs use the
same normalization as sync; a content hash is matched exactly after trimming.
When both identity filters are present, both must match.

Follow `next_cursor` while `has_more` is true. Keep the account and normalized
filters unchanged; page size may change. Results follow UUID order, rather than
reading order or update time. Pagination is a live view: a new row inserted
before the cursor appears when starting a fresh listing. Deleted passages are
excluded from subsequent pages. Cursors are query positions, not credentials or
snapshot tokens, and do not alter the sync cursor.

## REST access

The same read service is available without an MCP client:

- `GET /agent/v1/highlights` accepts `q`, `metadata_id`, `content_hash`, `limit`
  and `cursor` query parameters and returns the same preview page.
- `GET /agent/v1/highlights/{highlight_id}` returns the complete active highlight.

For a calibre-web account, an example request is:

```sh
curl --get 'https://books.example.com/agent/v1/highlights' \
  --user "$QUIRE_USERNAME:$QUIRE_PASSWORD" \
  --data-urlencode 'q=memory' --data-urlencode 'limit=10'
```

Use the existing native Bearer Authorization header for a NativeAuth deployment.
REST returns `401` for missing/invalid credentials, `404` for absent or unavailable
highlights, `422` for invalid filters/cursors and a generic `503` for unexpected
read failures. MCP returns tool errors for invalid queries (`invalid_filters`,
`invalid_cursor`), unavailable highlights (`highlight_not_found`), books not on
the shelf (`book_not_found`) and read failures (`highlight_read_failed`);
authentication failures remain HTTP errors. Error responses do not
include another account's records or SQL exception details.

## Deployment and storage

Both interfaces are mounted when `QUIRE_SERVER_PROGRESS_ENABLED=true` (full or
sync-only mode). They are absent in AI-only mode. Apply the existing highlight
sync migration `progress_004` and sync the Android device before querying its
passages. The library and book tools read the existing library and progress
sync tables, and `get_book` reads book details only when
`QUIRE_SERVER_AI_ENABLED` and `QUIRE_SERVER_DETAILS_ENABLED` are both on. None
of them needs a further migration or an app update.

The REST API covers the highlight list, search and single read only; the newer
tools are MCP-only.

Requests do not mutate highlights, tombstones, library, progress or sync
revision state. Account
isolation is enforced by the same server principal used by sync, independently
of any MCP session ID. The transport is stateless and JSON responses are used.
Quire disables the SDK's tracing middleware and configures no telemetry provider
or exporter. Do not enable verbose client/proxy body logging for private passages.

Protocol framing and negotiation use the [official Python SDK](https://github.com/modelcontextprotocol/python-sdk).
See its [ASGI deployment guide](https://py.sdk.modelcontextprotocol.io/run/asgi/) for
transport background. Quire's existing-header authentication profile is documented
above; it does not implement the [MCP OAuth authorization flow](https://modelcontextprotocol.io/specification/latest/basic/authorization).
