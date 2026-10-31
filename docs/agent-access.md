# Agent and MCP access to highlights

Quire Server provides read-only access to your synced highlights through a remote
MCP endpoint and a REST API. Results include the saved quotation, semantic colour,
book title and author. The book does not need to be downloaded on the server.
Removed highlights are excluded.

The server does not generate answers or call an AI provider for these requests.
Your chosen client receives the returned passages. Book text is data and may
contain arbitrary instructions; it is not an instruction source for the client.

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

All three tools use the caller's account and are marked read-only, idempotent
and non-destructive. There are no create, edit, delete or sync tools.

| Tool | Arguments | Result |
| --- | --- | --- |
| `list_highlights` | Optional `metadata_id`, `content_hash`, `limit`, `cursor` | Page of active highlight previews |
| `search_highlights` | Required `q`, plus the list filters | Page matching literal text in quotations, titles or authors |
| `get_highlight` | Required `highlight_id` UUID | One complete active highlight, including full text and exact locator |

For example, call `search_highlights` with `{"q":"memory","limit":10}`. Use an ID
from the results with `get_highlight` to retrieve a complete passage.

Search is a case-insensitive literal substring match according to the database
locale. `%`, `_` and backslashes are text, not wildcard syntax. It does not use
embeddings, external retrieval or an LLM.

## Previews and pagination

List and search responses contain `items`, `next_cursor` and `has_more`. Each
preview includes `id`, `document`, `text`, `book_title`, `book_author`, `colour`,
`created_at` and `updated_at`.

To bound retrieved data and response size, preview text is limited to 2,000
characters; title and author to 1,000 each; metadata identifier to 1,024.
`text_truncated`, `book_title_truncated`, `book_author_truncated` and
`metadata_id_truncated` explicitly indicate clipping. Previews do not include
locators. `get_highlight` returns the exact full saved text, attribution, identity
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
read failures. MCP returns tool errors for invalid queries and unavailable
highlights; authentication failures remain HTTP errors. Error responses do not
include another account's records or SQL exception details.

## Deployment and storage

Both interfaces are mounted when `QUIRE_SERVER_PROGRESS_ENABLED=true` (full or
sync-only mode). They are absent in AI-only mode. Apply the existing highlight
sync migration `progress_004` and sync the Android device before querying its
passages. This addition requires no further database migration and no Android
update beyond highlight sync support.

Requests do not mutate highlights, tombstones or sync revision state. Account
isolation is enforced by the same server principal used by sync, independently
of any MCP session ID. The transport is stateless and JSON responses are used.
Quire disables the SDK's tracing middleware and configures no telemetry provider
or exporter. Do not enable verbose client/proxy body logging for private passages.

Protocol framing and negotiation use the [official Python SDK](https://github.com/modelcontextprotocol/python-sdk).
See its [ASGI deployment guide](https://py.sdk.modelcontextprotocol.io/run/asgi/) for
transport background. Quire's existing-header authentication profile is documented
above; it does not implement the [MCP OAuth authorization flow](https://modelcontextprotocol.io/specification/latest/basic/authorization).
