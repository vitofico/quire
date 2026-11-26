# Server configuration

This page lists every setting `quire-server` reads, what each one does, and
how to check that a change took effect. It is written for someone running
the server with Docker Compose for the first time. To get a server running,
read [Required](#required), pick a mode in [Deploy modes](#deploy-modes),
copy the matching `.env` block, and come back here when something needs
changing.

Every name and default on this page comes from the server, identity and policy
settings models, the compose files
`server/docker-compose.yml` and `server/docker-compose.full.yml`, and the
migration step `server/scripts/migrate.py`. A unit test
(`server/tests/unit/test_env_docs_drift.py`) fails when a setting exists in
the code without an entry here, or when this page names one that no longer
exists.

- [How settings reach the server](#how-settings-reach-the-server)
- [Required](#required)
- [Deploy modes](#deploy-modes)
- [Remote MCP access](#remote-mcp-access)
- [AI provider](#ai-provider)
- [Synced catalogs](#synced-catalogs)
- [PDF conversion](#pdf-conversion)
- [Status page](#status-page)
- [Docker Compose variables](#docker-compose-variables)
- [Advanced](#advanced)
- [Recipes](#recipes)
- [Did my change take effect?](#did-my-change-take-effect)
- [Reading AI errors](#reading-ai-errors)
- [Which releases change the server](#which-releases-change-the-server)

## How settings reach the server

The server is configured through environment variables: named values, such
as `QUIRE_SERVER_AI_MODEL=gpt-oss:120b-cloud`, that a program reads from its
surroundings when it starts. With Docker Compose, the tool that starts the
containers described in a `docker-compose.yml` file, you write them in a file
called `.env` in the same folder as the compose file. Start from the
template:

```sh
cd server
cp .env.example .env
```

Docker Compose uses `.env` in two ways:

1. It fills the `${NAME}` placeholders inside the compose file, such as the
   published port or the database password. The variables in
   [Docker Compose variables](#docker-compose-variables) work only this way.
2. It hands every line of `.env` to the `quire-server` container as
   environment variables (the `env_file:` block in both compose files). The
   server picks out the names that start with `QUIRE_SERVER_`.

Two server settings are fixed by the compose files under `environment:`,
which wins over `.env`: `QUIRE_SERVER_DATABASE_URL` in both files, and
`QUIRE_SERVER_CWA_BASE_URL` in `docker-compose.full.yml`. A line for them in
`.env` has no effect there. Both compose files need Docker Compose v2.24.0
or newer to load `.env` this way.

A variable exported only in your shell, for example
`QUIRE_SERVER_AI_ENABLED=false docker compose up`, does not reach the server.
Put it in `.env`. When you run the server without Docker (see
`docs/development.md`), it reads `.env` from the current folder itself.

### Writing values

- **Names spelled as on this page.** The server matches names in any case,
  so `quire_server_ai_enabled` works too, but capitals are the convention.
- **`true` or `false` for on/off settings.** The server also accepts `1`/`0`,
  `yes`/`no`, `y`/`n`, `on`/`off` and `t`/`f`, in any case, and refuses to
  start on anything else. The migration step that runs before it reads
  `QUIRE_SERVER_PROGRESS_ENABLED` and `QUIRE_SERVER_AI_ENABLED` through the
  server's own settings, so the two always agree.
- **To go back to a default, delete the line or put `#` in front of it.** A
  line with nothing after the `=` is not the same as no line; see
  [Empty values](#empty-values).
- **A misspelled name is not an error.** The server starts, ignores the line,
  and lists it under `warnings` in `GET /health`; see
  [Did my change take effect?](#did-my-change-take-effect).

### Empty values

A line such as `QUIRE_SERVER_AI_SOURCES=` gives the variable an empty value.
What that does depends on the setting:

| Setting | An empty value means |
| --- | --- |
| `QUIRE_SERVER_AI_BASE_URL`, `QUIRE_SERVER_AI_MODEL`, `QUIRE_SERVER_AI_API_KEY` | Unset, the same as no line. Spaces only count as empty too. |
| `QUIRE_SERVER_AI_TEMPERATURE` | Omit `temperature` from provider requests. `none` (case-insensitive) does the same. |
| `QUIRE_SERVER_AI_SOURCES` | Retrieval off. The default applies only when the line is missing. |
| `QUIRE_SERVER_AI_PROMPT_VERSION` | The built-in prompt version, the same as the default. |
| Numbers, `true`/`false` settings, fixed-choice settings, `QUIRE_SERVER_AI_TOKEN_SECRETS` | The server refuses to start. |
| `QUIRE_SERVER_CATALOG_KEYS` | Unset, the same as no line. |
| `QUIRE_SERVER_LOG_LEVEL` | `INFO`, the same as the default. |
| Other text settings | Empty text, taken literally, which breaks whatever uses it. |

"Empty counts as unset" is the rule only for the three AI provider settings
in the first row (`_blank_means_unset` in `server/quire_server/config.py`)
and for the log level.
When the server refuses to start, its container stops and Docker restarts it
over and over. `docker compose logs quire-server` then shows
`validation error for Settings` followed by the setting's name in lower case
without the prefix, for example `ai_timeout_s` for
`QUIRE_SERVER_AI_TIMEOUT_S`.

### Applying a change

The server reads its settings once, when its container starts. After
editing `.env`, recreate the container so it starts from the new file:

```sh
docker compose up -d --force-recreate quire-server
# Full stack:
docker compose -f docker-compose.full.yml up -d --force-recreate quire-server
```

`docker compose restart quire-server` is not enough: it restarts the
existing container, which keeps the settings it was created with. A change
to a [Docker Compose variable](#docker-compose-variables) needs the service
that uses it recreated, for example `caddy` for `QUIRE_SITE_ADDRESS`. Then
[check that the change took effect](#did-my-change-take-effect).

## Required

Every install needs these two. With the full-stack compose
(`docker-compose.full.yml`), only the first: that file runs its own
calibre-web and points the server at it.

### `POSTGRES_PASSWORD`

- Read by: both compose files, not by the server
- Type: text; letters, digits, `-` and `_` are safe
- Default: `changeme` when unset (the compose files' fallback); `.env.example`
  ships `change-me`
- Example: `POSTGRES_PASSWORD=replace-with-a-long-random-string`

The password of the Postgres database that the compose files start next to
the server. Compose gives it to the `postgres` container and also writes it
into the database address it hands the server, so characters that mean
something in an address (`@`, `:`, `/`, `%`, `#`, `?`) break the connection.
`openssl rand -hex 24` prints a safe random one.

When to change it: set it once, before the first `docker compose up`.
Postgres stores the password when it creates its data volume and ignores this
variable afterwards. Changing it later leaves the database on the old
password while the server tries the new one, and every request fails. To
change it on an existing install, change it inside Postgres first, then in
`.env`, then recreate `quire-server`. This opens a database prompt inside
the `postgres` container, logged in as the server's database user:

```sh
docker compose exec postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

At the prompt, type `\password`, enter the new password twice, then `\q` to
leave.

### `QUIRE_SERVER_CWA_BASE_URL`

- Read by: the server. `docker-compose.yml` refuses to start without it;
  `docker-compose.full.yml` sets it to `http://calibre-web:8083` and ignores
  `.env`
- Type: URL
- Default: `http://calibre-web.calibre-web.svc.cluster.local:8083`, an
  address that only resolves inside a Kubernetes cluster
- Example: `QUIRE_SERVER_CWA_BASE_URL=https://calibre.example.com`

The address of your calibre-web. Quire has no accounts of its own: when the
app sends a username and password, the server asks calibre-web whether they
are valid by requesting this address followed by
[`QUIRE_SERVER_CWA_PROBE_PATH`](#quire_server_cwa_probe_path) (`/opds`) with
the same credentials. A 200 from calibre-web means yes and 401 means no.
Anything else, or no answer within
[`QUIRE_SERVER_CWA_PROBE_TIMEOUT_S`](#quire_server_cwa_probe_timeout_s),
makes the server answer 503 `upstream auth unavailable` and log either
`CWA returned <status> on auth probe` or `upstream auth unavailable: <reason>`.

> **Calibre-Web's Guest account cannot sign in to Quire.** With anonymous
> browsing turned on, Calibre-Web lets anyone in as `Guest` whatever password
> they type, so a Guest login says nothing about who is reading. The server
> refuses it with 401 `calibre_web_guest_login` and logs
> `event=auth.guest_refused` once. Every other Calibre-Web account is still
> checked against its password, with or without anonymous browsing.
> If readers used to sign in to Quire as Guest, their sync stops after this
> upgrade. Give each of them their own Calibre-Web account and have them sign
> in with it. Their old reading data stays under the shared `guest` name; if
> only one person used it, you can hand it to them with
> `scripts/migrate_calibre_account.py guest <their email>` (see the
> [migration guide](independent-login-migration.md#migrate-an-existing-reader)).

Common mistakes:

- `localhost` inside a container means the container itself, not your
  machine. If calibre-web runs on the same machine but outside this compose
  file, use `http://host.docker.internal:8083` (with calibre-web's port);
  both compose files map that name to the machine running Docker.
- The server does not follow redirects. If `http://` redirects to
  `https://`, or your reverse proxy serves calibre-web under a sub-path, use
  the final address, including the sub-path.
- A trailing `/` is harmless; the server strips it.

Because `docker-compose.yml` checks for this variable, every compose command
with that file, including `ps`, `logs` and `down`, needs `.env` present.

When to change it: when calibre-web moves.

## Deploy modes

The same server image runs in three modes. Two on/off settings choose which
parts are switched on (`create_app` in `server/quire_server/main.py`):

| Mode | `QUIRE_SERVER_PROGRESS_ENABLED` | `QUIRE_SERVER_AI_ENABLED` | Serves |
| --- | --- | --- | --- |
| Full stack (default) | `true` | `true` | Reading progress (`/sync/v1`), library (`/library/v1`), AI (`/ai/v1`) |
| Sync only | `true` | `false` | Reading progress, library |
| AI only | `false` | `true` | AI |

`/health` and `/readyz` answer in every mode, and so does the status page
once [`QUIRE_SERVER_ADMIN_USERS`](#quire_server_admin_users) names someone.
With both settings `false`, only those answer and the server warns about it
at boot. The mode also
decides which database tables the migration step creates when the container
starts. Both default to `true`, so a `.env` that sets neither runs the full
stack, and AI then needs a provider (see [AI provider](#ai-provider)).

### `QUIRE_SERVER_PROGRESS_ENABLED`

- Type: `true` or `false`
- Default: `true`
- Example: `QUIRE_SERVER_PROGRESS_ENABLED=false`

Switches reading-progress sync and the library endpoints (`/sync/v1/*` and
`/library/v1/*`, including library stats). The AI Reader Profile, a summary
of your reading with recommendations, is built from reading progress: with
this off, `POST /ai/v1/profile/refresh` answers 503
`profile_requires_progress_data`.

When to change it: set `false` only for an AI-only server.

### `QUIRE_SERVER_AI_ENABLED`

- Type: `true` or `false`
- Default: `true`
- Example: `QUIRE_SERVER_AI_ENABLED=false`

Switches the AI endpoints (`/ai/v1/*`). When it is `true` but
[`QUIRE_SERVER_AI_BASE_URL`](#quire_server_ai_base_url) or
[`QUIRE_SERVER_AI_MODEL`](#quire_server_ai_model) is missing, the server
still starts, with a warning; the app shows AI as not configured and insight
requests answer 503 `ai_disabled`. With `false`, every `/ai/v1` address
answers 404 and no AI request leaves the machine. Each reader also has to
turn AI on in the app before the server generates anything for them.

When to change it: set `false` if you do not want AI features.

### Minimum `.env` per mode

These blocks are for `docker-compose.yml`, the compose file for people who
already run calibre-web. For `docker-compose.full.yml`, leave out
`QUIRE_SERVER_CWA_BASE_URL` and look at
[Docker Compose variables](#docker-compose-variables) for the hostname and
file-ownership settings that file uses.

Full stack, with Ollama's hosted models as the AI provider
([Recipes](#recipes) has others):

```dotenv
POSTGRES_PASSWORD=replace-with-a-long-random-string
QUIRE_SERVER_CWA_BASE_URL=https://calibre.example.com
QUIRE_SERVER_AI_BASE_URL=https://ollama.com/v1
QUIRE_SERVER_AI_MODEL=gpt-oss:120b-cloud
QUIRE_SERVER_AI_API_KEY=your-ollama-api-key
```

Sync only:

```dotenv
POSTGRES_PASSWORD=replace-with-a-long-random-string
QUIRE_SERVER_CWA_BASE_URL=https://calibre.example.com
QUIRE_SERVER_AI_ENABLED=false
```

AI only:

```dotenv
POSTGRES_PASSWORD=replace-with-a-long-random-string
QUIRE_SERVER_CWA_BASE_URL=https://calibre.example.com
QUIRE_SERVER_PROGRESS_ENABLED=false
QUIRE_SERVER_AI_BASE_URL=https://ollama.com/v1
QUIRE_SERVER_AI_MODEL=gpt-oss:120b-cloud
QUIRE_SERVER_AI_API_KEY=your-ollama-api-key
```

An AI-only server still checks logins against calibre-web, so it still needs
`QUIRE_SERVER_CWA_BASE_URL`. Running without calibre-web would need
[`QUIRE_SERVER_AUTH_BACKEND=native`](#quire_server_auth_backend), which has
no way to create accounts yet.

## Remote MCP access

### `QUIRE_SERVER_MCP_PUBLIC_URL`

| Property | Value |
| --- | --- |
| Default | unset (loopback-only MCP) |
| Applies to | Remote `/mcp` endpoint when progress is enabled |
| Example | `https://books.example.com` |
| Takes effect | Server restart |

Set the public HTTPS origin through which clients reach Quire Server. An optional
port is allowed; paths, credentials, query strings and fragments are rejected.
A trailing slash and an explicit standard port `:443` are normalized away.
The accepted Host and supplied Origin must match the configured authority/origin.
Loopback hosts remain available for local native/server-side clients.

Add the value to `.env`, restart Quire Server, and have your reverse proxy
forward `/mcp` and `/agent/*` to it with Host preserved. The reference Caddyfile
includes these routes. Existing custom proxies or ingress rules need their own
routing update; this setting does not configure them.

Authentication still uses an explicit existing Basic or NativeAuth Bearer
Authorization header. Cookie-only clients and OAuth-discovery-only clients
cannot use this endpoint. This setting does not enable browser pairing or AI.
See [Agent and MCP access](agent-access.md) for tools and examples.

## AI provider

The AI features send a prompt about a book to a language model and get back
a structured card. The server talks to the model through an
OpenAI-compatible endpoint: any server that speaks the same HTTP API as
OpenAI's chat completions, such as Ollama, vLLM, llama.cpp's server,
OpenRouter, or OpenAI itself. Before asking the model, it looks the book up
on Wikipedia and Open Library and adds what it finds to the prompt. That
step is called retrieval, and it grounds the card in real text rather than
only in what the model remembers.

What happens when the app asks for a card the server has not generated yet
(`generate` in `server/quire_server/core/ai/service.py`):

1. The reader's [daily budget](#quire_server_ai_daily_budget) is checked.
2. The request waits for the [rate limit](#quire_server_ai_rate_per_min) and
   a [free generation slot](#quire_server_ai_max_concurrency).
3. Retrieval runs, Wikipedia and Open Library at the same time.
4. One call to the model, limited by
   [`QUIRE_SERVER_AI_TIMEOUT_S`](#quire_server_ai_timeout_s). If the answer
   does not fit the card's structure, one more call with the error attached.
5. The card is stored. Later requests for the same book, model and style
   get it from the database without a model call, and do not count against
   any budget.

### Connection

#### `QUIRE_SERVER_AI_BASE_URL`

- Type: URL, usually ending in `/v1`
- Default: unset. Empty counts as unset.
- Example: `QUIRE_SERVER_AI_BASE_URL=http://host.docker.internal:11434/v1`

Where to send model requests; the server appends `/chat/completions`.

| Provider | Value |
| --- | --- |
| Ollama's hosted models | `https://ollama.com/v1` |
| Ollama on the machine running Docker | `http://host.docker.internal:11434/v1` |
| Ollama as a service named `ollama` in the same compose file | `http://ollama:11434/v1` |
| OpenAI | `https://api.openai.com/v1` |

`localhost` or `127.0.0.1` here means the quire-server container itself,
never your machine; see [Ollama on the same machine](#ollama-on-the-same-machine).
Connecting must succeed within 10 seconds (or within
`QUIRE_SERVER_AI_TIMEOUT_S`, if that is lower), otherwise the call fails as
`provider_unreachable`.

The server warns at boot when the value does not end in `/v1`. The warning
is advice: if your provider documents an OpenAI-compatible address without
`/v1`, use theirs and ignore it.

#### `QUIRE_SERVER_AI_MODEL`

- Type: text, the model's name as the provider lists it
- Default: unset. Empty counts as unset.
- Example: `QUIRE_SERVER_AI_MODEL=gpt-oss:120b-cloud`

Which model to ask. For a local Ollama it is a name from `ollama list`; pull
it first with `ollama pull <name>`. A name the provider does not know fails
as `provider_rejected` with `provider_status` 404.

Cards are stored per model. After switching, the server generates a new card
the next time one is requested, and that generation counts against the daily
budget. Very small models sometimes cannot produce the card's structure
(`provider_invalid_output`); the server's own hint names
`gpt-oss:120b-cloud` on Ollama as a model known to work.

#### `QUIRE_SERVER_AI_TEMPERATURE`

- Type: number, decimals allowed, or empty / `none` (case-insensitive)
- Default: `0.2`
- Example: `QUIRE_SERVER_AI_TEMPERATURE=none`

The sampling temperature sent with each chat-completions request. Set it to
an empty value or `none` to omit `temperature` entirely for providers that
reject it. A missing variable keeps the default of `0.2`.

#### `QUIRE_SERVER_AI_API_KEY`

- Type: text
- Default: unset. Empty counts as unset.
- Example: `QUIRE_SERVER_AI_API_KEY=your-provider-api-key`

Sent to the provider as `Authorization: Bearer <key>`. When unset, no
`Authorization` header is sent, which is right for a local Ollama. The key is
never logged and no endpoint returns it. A key the provider refuses fails as
`provider_rejected` with `provider_status` 401 or 403.

### Time limits

#### `QUIRE_SERVER_AI_TIMEOUT_S`

- Type: seconds, decimals allowed
- Default: `120`
- Example: `QUIRE_SERVER_AI_TIMEOUT_S=285`

How long the server waits for the model to answer one insight call. The
server retries once when the answer does not fit the card's structure, so
one card can take up to twice this, plus retrieval. When the wait runs out,
the app shows the `provider_timeout` message and the log says
`error_class=ProviderTimeout`.

The app needs no matching change. It reads this value from the server
(`generation_timeout_s` in `GET /ai/v1/config`) and waits twice as long plus
30 seconds, but never more than 10 minutes
(`computeLongCallTimeoutS` in
`data/ai/src/main/java/io/theficos/ereader/data/ai/AiClient.kt`). So 285 is
the largest value the app always waits out in full; above it, the app only
gets the card if the model finishes within those 10 minutes.

When to change it: raise it for slow local models, above all on a CPU
without a GPU, where a prompt of a few thousand characters can take minutes.
Hosted models usually answer well within the default.

You can also change it on the [status page](#changing-ai-settings-on-the-page),
without a restart, unless the environment sets it.

#### `QUIRE_SERVER_AI_PROFILE_TIMEOUT_S`

- Type: seconds, decimals allowed
- Default: `90`
- Example: `QUIRE_SERVER_AI_PROFILE_TIMEOUT_S=285`

The same limit for the Reader Profile (`POST /ai/v1/profile/refresh`). The
server retries once here too, so a refresh can take up to twice this. The app
reads it as `profile_timeout_s` and sizes its wait the same way. A timeout
here names this variable in its hint.

When to change it: together with `QUIRE_SERVER_AI_TIMEOUT_S` on slow
hardware.

You can also change it on the [status page](#changing-ai-settings-on-the-page),
without a restart, unless the environment sets it.

### Retrieval sources

#### `QUIRE_SERVER_AI_SOURCES`

- Type: comma-separated list. The recognised names are `wikipedia` and
  `openlibrary`; case and spaces around them do not matter
- Default: `wikipedia,openlibrary`
- Example: `QUIRE_SERVER_AI_SOURCES=openlibrary`

Which public sites the server looks a book up on before asking the model.
What they return goes into the prompt, which makes the card more accurate
and the prompt longer. These lookups send the book's title, author, series
and ISBN to those sites, and nothing about the reader. Results are cached in
the database.

`openlibrary` also feeds the Reader Profile. When a reader refreshes it, the
server asks Open Library for the works of up to five of the authors that
appear most in that reader's library, and offers the ones the reader does not
own as discovery picks. Each request carries only an author's name, but
together the names reflect what the reader keeps. Results are cached for 30
days. Without `openlibrary`, the profile has no discovery picks.

An empty value, `QUIRE_SERVER_AI_SOURCES=`, turns retrieval off. The prompt
then holds only the book's own details, which makes it much shorter and
faster on slow hardware. The cost is accuracy: the model has only what it
already knows about the book, and small models make things up more often.
Deleting the line is different: it brings back the default.

`Wikipedia, OpenLibrary` works the same as the default. A name the server
does not recognise, such as `open-library`, is ignored, and the server
reports it among its configuration warnings at boot and in `GET /health`
(see [Did my change take effect?](#did-my-change-take-effect)). The warning
names the variable, not the value, so check the spelling against the two
names above. If no name is recognised, retrieval is off, as with an empty
value. `GET /ai/v1/config` lists the sources in use under
`sources_enabled`, and the `ai.generate` log line lists the sources that
actually contributed to a card (`sources=-` for none).

When to change it: turn retrieval off to test whether a slow model copes
without the extra context, or drop a site you do not want contacted.

You can also change it on the [status page](#changing-ai-settings-on-the-page),
without a restart, unless the environment sets it.

#### `QUIRE_SERVER_AI_RETRIEVAL_TIMEOUT_S`

- Type: seconds, decimals allowed
- Default: `8`
- Example: `QUIRE_SERVER_AI_RETRIEVAL_TIMEOUT_S=15`

The limit for each request to Wikipedia or Open Library; connecting is
capped at 5 seconds. One lookup can make a few requests and gives up after
twice this in total. A source that fails or runs out of time is skipped, and
the card is generated without it.

When to change it: raise it if your connection to those sites is slow and
the log shows `ai.retrieval.<source>.over_budget` or `.fail` lines; lower it
to shorten the delay retrieval can add to a card.

### Load, budgets and rate limits

These protect a metered provider, one that bills per request, or a small
machine from too many generations. Only generations count: a stored card
served again costs nothing.

#### `QUIRE_SERVER_AI_MAX_CONCURRENCY`

- Type: whole number, 1 or more
- Default: `4`
- Example: `QUIRE_SERVER_AI_MAX_CONCURRENCY=1`

How many cards the server generates at the same time; further requests wait
their turn. `0` makes every generation wait forever.

When to change it: `1` on a CPU-only machine, so two books opened at once do
not split the CPU between them and both run out of time.

#### `QUIRE_SERVER_AI_RATE_PER_MIN`

- Type: whole number
- Default: `10`
- Example: `QUIRE_SERVER_AI_RATE_PER_MIN=30`

A ceiling on card generations per minute for the whole server, across all
readers. Up to this many can start at once; after that, one more is allowed
every 60/N seconds. A request over the ceiling waits instead of failing, and
the wait counts against the app's patience. `0` does not turn it off:
anything below 1 behaves as 1 per minute.

When to change it: raise it for a household that opens many new books at
once; lower it to stay under a provider's per-minute quota.

You can also change it on the [status page](#changing-ai-settings-on-the-page),
without a restart, unless the environment sets it.

#### `QUIRE_SERVER_AI_DAILY_BUDGET`

- Type: whole number
- Default: `200`
- Example: `QUIRE_SERVER_AI_DAILY_BUDGET=50`

How many card generations, regenerations included, each reader may trigger
per day. Days are counted in UTC (Coordinated Universal Time, the clock at
longitude 0), so the budget resets at midnight UTC, not at your midnight.
The count is kept in the database and survives restarts. Over the budget,
the server answers 429 with `used`, `limit` and `resets_at`. `0` turns the
budget off.

When to change it: lower it on a paid provider to cap what each reader can
spend.

You can also change it on the [status page](#changing-ai-settings-on-the-page),
without a restart, unless the environment sets it.

#### `QUIRE_SERVER_AI_REGEN_DAILY_LIMIT`

- Type: whole number
- Default: `3`
- Example: `QUIRE_SERVER_AI_REGEN_DAILY_LIMIT=10`

How many times per UTC day each reader may regenerate a card, that is, ask
for a new one for a book that already has one. Regenerations also count
against `QUIRE_SERVER_AI_DAILY_BUDGET`. Unlike the other limits, `0` does
not turn it off: it blocks regeneration completely.

When to change it: raise it while you try out models or styles.

You can also change it on the [status page](#changing-ai-settings-on-the-page),
without a restart, unless the environment sets it.

#### `QUIRE_SERVER_AI_PROMOTE_DAILY_LIMIT`

- Type: whole number
- Default: `100`
- Example: `QUIRE_SERVER_AI_PROMOTE_DAILY_LIMIT=0`

How many times per UTC day each reader's app may promote a card: copy a card
made for a book's catalogue entry onto the downloaded copy of the same book.
Promoting makes no model call. The count is kept in memory and starts again
at zero when the container restarts. `0` turns the limit off.

When to change it: only if readers get 429 answers from
`/ai/v1/insights/promote`.

#### `QUIRE_SERVER_AI_DISCUSS_RATE_PER_MIN`

- Type: whole number, 1 or more
- Default: `10`
- Example: `QUIRE_SERVER_AI_DISCUSS_RATE_PER_MIN=20`

How many questions each reader may ask per minute about highlights they
picked (`POST /ai/v1/highlights/discuss`). Each question is one model call
and also counts against `QUIRE_SERVER_AI_DAILY_BUDGET`. The count is kept in
memory in each server process. Over it, the server answers 429 with a
`Retry-After` header. The apps read the value from `GET /ai/v1/config`.

When to change it: rarely. A conversation is at most six questions, so the
default only stops a runaway client.

#### `QUIRE_SERVER_AI_PROFILE_REFRESH_DAILY_LIMIT`

- Type: whole number
- Default: `3`
- Example: `QUIRE_SERVER_AI_PROFILE_REFRESH_DAILY_LIMIT=5`

How many times per UTC day each reader may refresh their Reader Profile
(`POST /ai/v1/profile/refresh`). The count is kept in the database and
survives restarts. Over the limit, the server answers 429 with `used`,
`limit` and `resets_at`. A reader with no finished books gets a profile of
reading statistics only, which makes no model call and does not count.
Refreshes do not count against `QUIRE_SERVER_AI_DAILY_BUDGET`. `0` turns the
limit off.

When to change it: raise it while you try out models; lower it on a paid
provider to cap what each reader can spend.

### Book details and covers

When a book has no cover, no description or a title that is really a file
name, the server can look it up on Open Library, tidy its details with one
model call and draw a plain typographic cover for it
(`/details/v1/*`, see [the API reference](sync-api.md#book-details-and-covers-details-v1)).
A tidy counts as one generation against
[`QUIRE_SERVER_AI_DAILY_BUDGET`](#quire_server_ai_daily_budget); a book
tidied once is served from the database to every reader with the same
details. The Open Library lookup makes no model call and costs nothing.

#### `QUIRE_SERVER_DETAILS_ENABLED`

- Type: `true` or `false`
- Default: `true`
- Example: `QUIRE_SERVER_DETAILS_ENABLED=false`

Switches the book details endpoints. They are part of AI, so they exist only
when [`QUIRE_SERVER_AI_ENABLED`](#quire_server_ai_enabled) is `true` too.
With `false`, every `/details/v1` address answers 404 and `/ai/v1/config`
reports `details_supported: false`, so the apps hide the feature.

When to change it: set `false` to keep AI insights without spending model
calls on book details.

#### `QUIRE_SERVER_DETAILS_COVER_RATE_PER_MIN`

- Type: whole number, 1 or more
- Default: `30`
- Example: `QUIRE_SERVER_DETAILS_COVER_RATE_PER_MIN=120`

How many drawn covers each reader may fetch per minute. Drawing one takes
the server a fraction of a second, and the apps keep every cover they get,
so a reader only reaches this when a whole library without covers loads at
once. Over it, the server answers 429 with a `Retry-After` header.

When to change it: raise it if the apps show missing covers on a first
sync of a large library.

#### `QUIRE_SERVER_DETAILS_LOOKUP_RATE_PER_MIN`

- Type: whole number, 1 or more
- Default: `30`
- Example: `QUIRE_SERVER_DETAILS_LOOKUP_RATE_PER_MIN=60`

How many book details requests (`/details/v1/lookup` and
`/details/v1/tidy` together) each reader may make per minute. Each one can
reach Open Library, and a tidy spends a generation, so the ceiling is
checked before either. The apps ask for the details of one book at a time,
as it is opened or imported. Over it, the server answers 429 with a
`Retry-After` header.

When to change it: raise it if a first sync of a large library is slow to
fill in details.

Both ceilings are counted in each server process, in memory: with several
workers or replicas a reader can reach the limit once per process, and a
restart starts the count afresh.

### Painted cover artwork

Generated covers can be drawn on a painted background from an image model.
It is off by default and needs both
[`QUIRE_SERVER_AI_ENABLED`](#quire_server_ai_enabled) and
[`QUIRE_SERVER_DETAILS_ENABLED`](#quire_server_details_enabled). The image
provider is set separately from the chat provider: any service with an
OpenAI-compatible `POST {base}/images/generations` endpoint that answers
with base64 image data (`b64_json`), as the GPT image models do. An answer
that gives an image address instead is recorded as a failed painting: the
server never fetches an address a provider points it at. The server
paints in the background, one cover at a time and once per book, and
only when a signed-in reader who opted in to AI asks for a painted cover.
The title and author are still set by the server, never by the model. How clients see
it is in [the API reference](sync-api.md#painted-cover-artwork).

What leaves the server: each painting sends the book's title, up to eight
subjects and the first 600 characters or so of its description to the
image provider, inside a prompt asking for an illustration with no text.
Nothing about the reader is sent: no account, name or address. Tell your
readers if your privacy notice lists where book data goes.

Cost: at most [`QUIRE_SERVER_COVER_ART_DAILY_BUDGET`](#quire_server_cover_art_daily_budget)
paintings a day (UTC) across the whole server, counting failures and
paintings still in progress. The budget is shared, not per reader, so one
reader asking for many painted covers can use up the day's budget. They
are logged in `ai_generation_log` with kind `cover_art` and are not
charged to any reader's allowance. Artwork is stored in the database (one
JPEG of at most about 400 KB per book) for as long as the book's details
record exists, and goes with it.

#### `QUIRE_SERVER_COVER_ART_ENABLED`

- Type: `true` or `false`
- Default: `false`
- Example: `QUIRE_SERVER_COVER_ART_ENABLED=true`

Turns on painted artwork for generated covers. With `false`, or when the
provider below is not set, covers are drawn exactly as without the feature.
Artwork painted earlier stays stored but is no longer offered.

When to change it: set `true`, with the three settings below, to give
generated covers a painted background.

#### `QUIRE_SERVER_COVER_ART_BASE_URL`

- Type: URL
- Default: none
- Example: `QUIRE_SERVER_COVER_ART_BASE_URL=https://api.openai.com/v1`

The image provider's base address; the server calls
`{base}/images/generations`. Required for the feature.

#### `QUIRE_SERVER_COVER_ART_API_KEY`

- Type: secret
- Default: none
- Example: `QUIRE_SERVER_COVER_ART_API_KEY=sk-...`

Sent as `Authorization: Bearer <key>` to the image provider only. Leave it
unset for a provider that needs no key.

#### `QUIRE_SERVER_COVER_ART_MODEL`

- Type: text
- Default: none
- Example: `QUIRE_SERVER_COVER_ART_MODEL=gpt-image-1.5`

The image model to ask for. Required for the feature.

#### `QUIRE_SERVER_COVER_ART_SIZE`

- Type: `WIDTHxHEIGHT` or `auto`
- Default: `1024x1536`
- Example: `QUIRE_SERVER_COVER_ART_SIZE=1024x1024`

The size to request. Covers are portrait, so pick the provider's portrait
size; whatever comes back is cropped to the cover's 2:3 shape. With `auto`
no size is sent.

#### `QUIRE_SERVER_COVER_ART_QUALITY`

- Type: lowercase word
- Default: none (the provider's default)
- Example: `QUIRE_SERVER_COVER_ART_QUALITY=low`

Passed through as the request's `quality`. A lower quality is cheaper and
is usually enough under a title band.

#### `QUIRE_SERVER_COVER_ART_DAILY_BUDGET`

- Type: whole number, 0 or more
- Default: `20`
- Example: `QUIRE_SERVER_COVER_ART_DAILY_BUDGET=100`

How many paintings the server attempts per UTC day, across all readers.
Books looked up after it is spent keep their drawn covers and may be painted
on a later day. `0` paints nothing.

When to change it: raise it once you know what a painting costs with your
provider.

#### `QUIRE_SERVER_COVER_ART_TIMEOUT_S`

- Type: seconds, more than 0 and at most 600
- Default: `120`
- Example: `QUIRE_SERVER_COVER_ART_TIMEOUT_S=60`

How long one painting may take. Image models are slow, and the painting
never holds up a request, so the default is generous. A painting that
times out may be tried again a day later.

### What has no setting

These are fixed in the code today, so there is no variable to look for:

- **Retries.** One retry when the model's answer does not fit the card's
  structure. None after a timeout or a failed connection.
- **Structured output.** The server first asks the provider to enforce the
  card's JSON schema itself (a `response_format` of type `json_schema`). If
  the provider answers that it does not support that, the server switches to
  asking for plain JSON with the schema written into the prompt, for as long
  as the container runs. When a retry happens, the
  `ai.client.validation_retry` log line shows which is in use:
  `native_schema=True` or `native_schema=False`.
- **Connection limits.** 10 seconds to the AI provider, 5 seconds to
  Wikipedia and Open Library.
- **Sampling.** Temperature 0.2, no streaming.
- **Other retrieval sources.** Only Wikipedia and Open Library exist.
- **The model server's own behaviour,** such as how long Ollama keeps a model
  loaded. Set that on the model server (see
  [CPU-only local models](#cpu-only-local-models)).

## Synced catalogs

A reader signed in with a Quire account can save several book catalogs
(an address, plus a login when the catalog needs one) and find them on every
device. The server stores each address and login encrypted with a key that
is kept outside the database, so a database dump or backup on its own
exposes nothing usable. Someone who takes over the running server has the
key too. The status page shows where the key comes from and how many
catalogs are saved; the routes are in
[Saved catalogs](sync-api.md#saved-catalogs-catalogsv1).

Nothing needs setting up. At start the server takes the key from
`QUIRE_SERVER_CATALOG_KEYS` if it is set, otherwise from the key file at
`QUIRE_SERVER_CATALOG_KEY_FILE`. If neither exists and the file's folder is
writable, it creates a random key there, readable by the server's own user
only, and uses it from then on. Both compose files mount a small volume,
`quire_keys`, at `/var/lib/quire` for this, so the key appears on first
start and survives upgrades. With no key, or when creating one fails (the
database cannot be reached, the file cannot be written), the server still
starts, catalog sync is off, and the server
logs one line at start that begins `Catalog sync is off` and names the
setting; nothing is ever stored unencrypted.

**Back up the key file together with the database.** A database restored
without it still works, but every saved catalog login is unreadable and
readers are asked to enter them again. When the server finds saved catalogs
and no key, it does not create a new one: restore the file, or supply the
key through `QUIRE_SERVER_CATALOG_KEYS`.

**Several server copies need the environment route.** Each copy would
otherwise create its own key file and could not read the others' rows. Give
every copy the same `QUIRE_SERVER_CATALOG_KEYS` from your secret store.

### `QUIRE_SERVER_CATALOG_KEYS`

- Type: JSON object that maps a key id (1 to 32 letters, digits, `-` or `_`)
  to a base64-encoded 32-byte key
- Default: unset
- Example: `QUIRE_SERVER_CATALOG_KEYS='{"key-2026-11": "<output of openssl rand -base64 32>"}'`

The keys for saved catalog logins. When set, the key file is not read or
created. The first entry encrypts; every entry can decrypt. To rotate, put
the new key first and keep the old ones: a catalog moves to the first key
the next time a reader saves it, even with no change. A catalog nobody saves
stays on its old key, so remove an old key only once no row uses it; this
query lists the key ids still in use:
`SELECT split_part(secret, ':', 1) AS key_id, count(*) FROM account_catalogs GROUP BY 1`.
A catalog whose key was removed is listed with `needs_login` until its login
is entered again. A value that does not parse stops the server at boot with a message
naming this setting (never the key). An empty line counts as unset. To move
from the key file to this setting, its content is already in this format:
`QUIRE_SERVER_CATALOG_KEYS=$(cat /var/lib/quire/catalog.key)`.

When to change it: on Kubernetes, for several server copies, or to keep the
key in a secret store.

### `QUIRE_SERVER_CATALOG_KEY_FILE`

- Type: file path inside the container
- Default: `/var/lib/quire/catalog.key`
- Example: `QUIRE_SERVER_CATALOG_KEY_FILE=/var/lib/quire/catalog.key`

Where the server reads, and on first start creates, its catalog key when
`QUIRE_SERVER_CATALOG_KEYS` is unset. The file holds the same JSON as that
setting. The server never overwrites it. If it exists but cannot be read or
parsed, the server stops at boot. Empty means no key file, so the key must
come from `QUIRE_SERVER_CATALOG_KEYS`.

When to change it: only to keep the key on a volume mounted elsewhere.

### `QUIRE_SERVER_CATALOG_ALLOW_HTTP`

- Type: `true` or `false`
- Default: `false`
- Example: `QUIRE_SERVER_CATALOG_ALLOW_HTTP=true`

Saved catalog addresses must use `https`, because the login travels with
every request. `true` also accepts plain `http`, for a catalog on your home
network.

When to change it: only for a catalog you reach over a network you trust.

### `QUIRE_SERVER_CATALOG_PROXY_ENABLED`

- Type: `true` or `false`
- Default: `true`
- Example: `QUIRE_SERVER_CATALOG_PROXY_ENABLED=false`

A browser cannot read most third-party catalogs, so Quire Web opens a saved
catalog through the server: the server fetches each page, cover and book with
the saved login and passes the answer on
([`/fetch`](sync-api.md#get-catalogsv1catalogsidfetch)). Every book a reader
downloads that way passes through the server's bandwidth. `false` turns that
off on its own: phones keep syncing their catalogs, and Quire Web shows only
the server's own catalog. The catalog key must be present either way.

With it on, any signed-in account can make the server fetch from any public
host it saves as a catalog, with the server's own address as the sender. On a
public service where anyone can sign up, weigh keeping it off.

When to change it: on a server that should not carry readers' downloads from
other sites, or one open to accounts you do not know.

### `QUIRE_SERVER_CATALOG_PROXY_ALLOW_PRIVATE`

- Type: `true` or `false`
- Default: `false`
- Example: `QUIRE_SERVER_CATALOG_PROXY_ALLOW_PRIVATE=true`

By default the server fetches a saved catalog only from a globally routable
address, so a reader cannot use it to reach machines inside the server's own
network. `true` also allows exactly these ranges, for a catalog on your home
network or a Tailscale address:

- RFC 1918 private IPv4: `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`
- CGNAT: `100.64.0.0/10`
- IPv6 unique local: `fc00::/7`

Everything else that is not globally routable stays refused whatever this
says: loopback (`127.0.0.0/8`, `::1`), link-local (`169.254.0.0/16`,
`fe80::/10`, which holds the cloud metadata services), IPv6 site-local
(`fec0::/10`), IPv4-compatible and IPv4-mapped IPv6 (`::/96`,
`::ffff:0:0/96`), documentation and benchmark ranges, multicast, unspecified
and broadcast addresses, and the metadata services that sit inside an allowed
range (`100.100.100.200`, `fd00:ec2::254`). So a catalog in another container
on the same Docker network works with `true`, while one on the host's own
loopback (`localhost`) does not: publish it on a private address instead. A
catalog on plain `http` also needs
[`QUIRE_SERVER_CATALOG_ALLOW_HTTP`](#quire_server_catalog_allow_http).

When to change it: on a self-hosted server whose readers save catalogs on the
local network. Leave it `false` on a server open to people you do not know.

### `QUIRE_SERVER_CATALOG_PROXY_RATE_PER_MIN`

- Type: whole number, 1 or more
- Default: `120`
- Example: `QUIRE_SERVER_CATALOG_PROXY_RATE_PER_MIN=60`

How many saved-catalog fetches one account may make per minute, counted per
server process. A catalog page costs one fetch plus one per cover on it.
Past the limit the server answers `429` with `Retry-After` until the minute
is over.

When to change it: lower it on a shared server to bound what one account can
make the server fetch.

### `QUIRE_SERVER_CATALOG_PROXY_MAX_CONCURRENT`

- Type: whole number, 1 or more
- Default: `8`
- Example: `QUIRE_SERVER_CATALOG_PROXY_MAX_CONCURRENT=4`

How many saved-catalog fetches the server runs at once, all accounts
together, counted per server process. One account may run 4 at a time. Past
either limit the server answers `429` (`too_many_fetches`) at once instead of
queueing. A fetch holds no database connection while it waits for the
catalog, so this bounds open connections to other sites, not the database.

When to change it: lower it on a small server, raise it on one with many
readers browsing saved catalogs at once.

## PDF conversion

Readers can turn a PDF into an EPUB from the app: the app uploads the PDF,
the server queues it, and a separate worker container converts it with
Calibre, Poppler and EPUBCheck (`quire-convert` from `converter/`). Nothing
leaves the server and no model is used, so it works with AI switched off.
The routes are in [PDF conversion](sync-api.md#pdf-conversion-convert-v1).
It is off by default.

**How the two containers work together.** The server and the worker share
one folder and nothing else. The worker has no network, no database and no
login of any account: it picks up a queued PDF from the folder, converts it,
leaves the EPUB and a short report there, and exits; the container restarts
it for the next job. The server keeps every job's state in the database and
clears the folder: the PDF goes as soon as the job finishes, the EPUB when
the reader acknowledges it or 24 hours after the job was created. A job
nobody uploads a PDF for expires after an hour.

**What the server checks, and what it does not.** The server treats
everything the worker writes as untrusted, because a crafted PDF could take
over Calibre inside the worker. It reads only regular files of a bounded
size (never a link, a FIFO or a folder), checks the report field by field,
copies the EPUB into a file of its own while hashing it, and keeps it only if
the size and SHA-256 match the report and the zip looks like a plain EPUB:
`mimetype` first with the EPUB media type, no entry name that leaves the
book, at most 10,000 entries, and an unpacked size of at most 5 times the
EPUB size ceiling (which is twice `QUIRE_SERVER_CONVERT_MAX_BYTES`). It does
not check the book's content: an EPUB that passes is still a file from an
untrusted PDF, and the apps open it like any sideloaded book.

The worker exits after each job so that nothing a PDF starts outlives its
job; on Linux it also adopts and kills every process the conversion leaves.
What remains: while one job runs, a conversion that took over the worker can
read the other PDFs queued at that moment and write results for them (which
the server then checks as above). Running a container or a user id per job
would close that; Quire does not do it yet, so keep the caps per account and
the daily limit low on a server with untrusted accounts.

**Turning it on with Docker Compose.** Both compose files already mount the
`quire_convert` volume on the server and define the worker under the
`convert` profile, with `restart: always` (the worker stops after every
job). Add `QUIRE_SERVER_CONVERT_ENABLED=true` to `.env`, then start with the
profile:

```sh
docker compose --profile convert up -d
```

The app shows the option once the worker has checked in (within a few
seconds). The server logs one line at start that begins
`PDF conversion is off` when the folder cannot be used, and `/health` repeats
it. The compose default pulls `ghcr.io/vitofico/quire-convert:latest`, which
works only while that package is public on GitHub; a fork publishes its own
and sets `QUIRE_CONVERT_IMAGE`.

**The worker container** (`ghcr.io/vitofico/quire-convert`, built from
`converter/Dockerfile`, amd64 only) runs as user 1000 with a read-only root
filesystem. It needs the shared folder mounted at `/jobs` (writable) and a
writable `/tmp` (a 512 MB tmpfs is enough). It needs no network at all:
`network_mode: none` in compose. It converts one book and exits; give it
2 CPUs and 3 GB of memory (the compose files do), more for very long books.
For more throughput, run more worker containers on the same folder: each
job is claimed by exactly one of them. Docker waits a little longer before
each restart when containers stop within ten seconds of starting, so a run
of quick failures (encrypted PDFs, say) is picked up seconds to a minute
apart.

**Folder ownership and size.** The server creates the layout on start. The
three subfolders the worker writes to (`running`, `done`, `workers`) are
open to every user and sticky, so the worker can run as any user id and
nobody removes another's entries; the uploads and finished books
(`incoming`, `store`) are readable by the server's user only. The server
image runs as root, so it can always clear what the worker leaves. If you
run the server as another user, give it and the worker the same group (the
worker makes its files group-writable). Mount the folder into these two
containers only, and prefer a volume with a size limit, so a worker that
fills `done/` cannot fill the server's disk.

**On Kubernetes or another proxy.** Run the worker as a second container in
the server's pod: the pod's `restartPolicy: Always` restarts it after every
job the same way compose does, though Kubernetes waits longer between
restarts (from ten seconds up to five minutes) when they come quickly, so
`available` may read `false` for a while after a run of quick jobs. Do not
set `shareProcessNamespace` on that pod: the worker's processes must end
with its container. Mount one `ReadWriteOnce` volume into both containers
(or a `ReadWriteMany` volume when they run apart), and let the reverse proxy
pass request bodies up to `QUIRE_SERVER_CONVERT_MAX_BYTES` on
`PUT /convert/v1/jobs/*/source` (nginx: `client_max_body_size`; Caddy has no
limit by default). The server drops an upload that sends nothing for 60
seconds; the proxy's read timeout should be at least that. The worker's
memory and CPU limits go on its container; it needs no Service, ingress,
Secret or network policy beyond denying all traffic.

### `QUIRE_SERVER_CONVERT_ENABLED`

- Type: `true` or `false`
- Default: `false`
- Example: `QUIRE_SERVER_CONVERT_ENABLED=true`

Mounts `/convert/v1` and starts the server's clean-up pass. Off, every
conversion route is a 404 and the apps do not show the option. The table
`convert_jobs` exists either way, because account export and deletion read
it. With conversion off nothing clears the job folder: deleting an account
removes its job rows, but files from before (at most a day's worth) stay
until conversion is on again or the folder is deleted.

When to change it: to offer PDF conversion, together with running the
worker.

### `QUIRE_SERVER_CONVERT_DIR`

- Type: folder path inside the container
- Default: `/var/lib/quire-convert`
- Example: `QUIRE_SERVER_CONVERT_DIR=/var/lib/quire-convert`

The folder shared with the worker. The worker sees the same folder at
`/jobs`. If the server cannot create its layout there, conversion stays off
and the server logs why.

When to change it: only to mount the shared volume elsewhere.

### `QUIRE_SERVER_CONVERT_MAX_BYTES`

- Type: whole number of bytes
- Default: `104857600` (100 MiB)
- Example: `QUIRE_SERVER_CONVERT_MAX_BYTES=209715200`

The largest PDF the server accepts. The upload route alone is held to this
instead of `QUIRE_SERVER_MAX_REQUEST_BYTES`, with or without a
`Content-Length` header. A reverse proxy in front must allow bodies this
large on that route. The EPUB a worker returns may be at most twice this
size; a larger one fails the job with `invalid_result`.

When to change it: for readers with larger scans, or to save disk space.

### `QUIRE_SERVER_CONVERT_MAX_PAGES`

- Type: whole number
- Default: `1000`
- Example: `QUIRE_SERVER_CONVERT_MAX_PAGES=2000`

The most pages a PDF may have. The worker checks it before converting and
fails the job with `too_many_pages`.

When to change it: together with the time limit, for very long books.

### `QUIRE_SERVER_CONVERT_TIME_LIMIT_S`

- Type: whole number of seconds, at least 60
- Default: `1200` (20 minutes)
- Example: `QUIRE_SERVER_CONVERT_TIME_LIMIT_S=1800`

How long one conversion may run. Past it the worker stops the conversion
and the job fails with `timed_out`. A worker that goes silent for this long
plus five minutes is presumed gone: the job runs once more, then fails with
`worker_lost`.

When to change it: when long books fail with `timed_out` on a slow machine.

### `QUIRE_SERVER_CONVERT_MAX_ACTIVE_PER_OWNER`

- Type: whole number, at least 1
- Default: `2`
- Example: `QUIRE_SERVER_CONVERT_MAX_ACTIVE_PER_OWNER=3`

How many unfinished conversions (waiting for the PDF, queued or running) one
account may have at once. One more gets `429 too_many_active_jobs`.

When to change it: rarely; the worker converts one book at a time anyway.

### `QUIRE_SERVER_CONVERT_DAILY_LIMIT`

- Type: whole number, at least 1
- Default: `10`
- Example: `QUIRE_SERVER_CONVERT_DAILY_LIMIT=25`

How many conversions one account may start in any 24 hours, cancelled and
failed ones included. One more gets `429 daily_limit_reached` with the wait
in `Retry-After`.

When to change it: to share the worker more or less generously.

## Status page

`/quire-admin` shows in one browser page what the checks in
[Did my change take effect?](#did-my-change-take-effect) show piece by
piece: the boot warnings, whether the AI provider answered, whether the
database is migrated, and every setting with the value the server runs
with, marked `set`, `saved` or `default`. A **Test AI connection** button
sends the provider one short request and shows the same message and hint
the app would get. Keys and URL passwords show as `***`. `server/README.md`
("Server status page") covers the JSON endpoints and reverse proxies.

### Changing AI settings on the page

The page's **AI settings** section changes six settings without editing
`.env` or restarting:
[`QUIRE_SERVER_AI_TIMEOUT_S`](#quire_server_ai_timeout_s),
[`QUIRE_SERVER_AI_PROFILE_TIMEOUT_S`](#quire_server_ai_profile_timeout_s),
[`QUIRE_SERVER_AI_SOURCES`](#quire_server_ai_sources),
[`QUIRE_SERVER_AI_RATE_PER_MIN`](#quire_server_ai_rate_per_min),
[`QUIRE_SERVER_AI_DAILY_BUDGET`](#quire_server_ai_daily_budget) and
[`QUIRE_SERVER_AI_REGEN_DAILY_LIMIT`](#quire_server_ai_regen_daily_limit).
The next AI request uses the new value, and the app picks up the time
limits the next time it reads `GET /ai/v1/config`.

For each of them, the value in force is the first of:

1. the environment, `.env` included. The page shows such a setting greyed
   out, because a value saved on the page would silently disagree with the
   file you manage the server from. To change it on the page, delete the
   line from `.env` and recreate the container;
2. a value saved on the page, which is kept in the database and survives
   restarts. The page says who saved it and when, and **Use default** removes
   it;
3. the built-in default.

In the settings table a saved value is marked `saved`. The page accepts
time limits up to 3600 seconds and the other numbers up to 1000000, which
only keeps out typos; the environment has no such ceiling. Saving changes
only the fields you edited, so a page left open while another admin saved
does not undo their change. The model, the provider address and the API key
stay in `.env`: the server decides at startup whether AI is configured from
them. With more than one server process, the others pick up a saved value
within 30 seconds.

### `QUIRE_SERVER_ADMIN_USERS`

- Type: comma-separated authenticated user IDs, any case
- Default: empty
- Example: `QUIRE_SERVER_ADMIN_USERS=alice`

Who may open the status page. Log in with the calibre-web account when the
browser asks; anyone not on the list gets `403`. Empty, the default, keeps
the page switched off: every `/quire-admin` path answers `404`. Under
`QUIRE_SERVER_AUTH_BACKEND=native` the entries are `native:<id>`. In
`transition` mode, use the Calibre username for a Basic admin or the
canonical `native:<id>` for a native admin. A migrated account uses its
native ID for admin access.

When to change it: set it to your own calibre-web username when you want the
page. The full-stack compose's Caddy already routes `/quire-admin` to the
server; behind your own reverse proxy, forward it the same way as `/ai/`.

## Docker Compose variables

Docker Compose, or another container in the stack, reads these to fill in
the compose files. They are not server settings. `POSTGRES_PASSWORD` is under
[Required](#required).

| Variable | Read by | Default |
| --- | --- | --- |
| `QUIRE_SERVER_PORT` | `docker-compose.yml` | `8000` |
| `QUIRE_SITE_ADDRESS` | `docker-compose.full.yml`, for Caddy | `localhost` |
| `CADDY_HTTPS_PORT`, `CADDY_HTTP_PORT` | `docker-compose.full.yml` | `443`, `80` |
| `PUID`, `PGID` | `docker-compose.full.yml`, for calibre-web | `1000` |
| `TZ` | `docker-compose.full.yml` for calibre-web; the server, through `.env` | `UTC` |
| `QUIRE_CONVERT_IMAGE` | both compose files, for the conversion worker | `ghcr.io/vitofico/quire-convert:latest` |

### `QUIRE_SERVER_PORT`

- Read by: `docker-compose.yml`
- Type: port number
- Default: `8000`
- Example: `QUIRE_SERVER_PORT=8080`

The port on your machine where `docker-compose.yml` publishes the server, so
the app reaches it at `http://<your-machine>:8000` by default. Inside the
container the server always listens on 8000; this changes only the outside
number. It shares the `QUIRE_SERVER_` prefix, but the server knows it is not
one of its settings and does not warn about it. `docker-compose.full.yml`
publishes no server port, because Caddy handles all traffic there.

When to change it: when port 8000 is already taken on your machine.

### `QUIRE_SITE_ADDRESS`

- Read by: `docker-compose.full.yml`, which passes it to the Caddy container
  for `server/caddy/Caddyfile`
- Type: hostname
- Default: `localhost`
- Example: `QUIRE_SITE_ADDRESS=books.example.com`

The name the full stack answers to. Caddy is the web server at the front of
the full stack: it handles HTTPS and sends `/sync`, `/library`, `/ai`,
`/health` and `/readyz` to quire-server and everything else to calibre-web.
With the default, Caddy uses a certificate it signs itself, which phones
reject until you install Caddy's root certificate on them. For a real
certificate, set your public hostname and also remove the `tls internal` line
from `server/caddy/Caddyfile`; `server/README.md` ("`tls internal` caveat")
has the steps.

When to change it: when you expose the full stack under your own domain.
Recreate `caddy` afterwards.

### `CADDY_HTTPS_PORT` and `CADDY_HTTP_PORT`

- Read by: `docker-compose.full.yml`
- Type: port numbers
- Default: `443` and `80`
- Example: `CADDY_HTTPS_PORT=8443`

The ports on your machine where Caddy listens for HTTPS and HTTP.

When to change it: when another web server on the machine already uses 443
or 80. A real certificate from Let's Encrypt needs port 80 reachable from the
internet, so move that one only if something still forwards port 80 to it.

### `PUID` and `PGID`

- Read by: `docker-compose.full.yml`, for the calibre-web container
- Type: numeric user and group id
- Default: `1000`
- Example: `PUID=1001`

The user and group calibre-web runs as, so the files it writes in your
library belong to you. `id -u` and `id -g` print yours.

When to change it: when your user is not 1000, or calibre-web cannot write
to the library folder.

### `TZ`

- Read by: `docker-compose.full.yml`, for calibre-web; both compose files
  also pass it to the server when it is in `.env`
- Type: time zone name
- Default: `UTC`
- Example: `TZ=Europe/Rome`

The clock zone for the containers that read it. In the server it changes one
thing: the `server_time` that `GET /sync/v1/progress` returns is written at
this offset (`server/quire_server/api/progress.py`). Everything else the
server does is in UTC, including when the AI budgets reset.

When to change it: for calibre-web's sake. The server works the same either
way.

### `QUIRE_CONVERT_IMAGE`

- Read by: `docker-compose.yml` and `docker-compose.full.yml`
- Type: image reference
- Default: `ghcr.io/vitofico/quire-convert:latest`
- Example: `QUIRE_CONVERT_IMAGE=ghcr.io/vitofico/quire-convert@sha256:<digest>`

The image of the PDF conversion worker, used only with `--profile convert`
(see [PDF conversion](#pdf-conversion)). Every build on `main` that changes
the converter is published under its commit sha and `latest`.

When to change it: to pin a release by digest, like `QUIRE_SERVER_IMAGE`.

## Advanced

Leave these at their defaults unless you know why you are changing them.
They cover logging, the database, the calibre-web login check, the reverse
proxy, request size limits, login modes, and deprecated switches.

### Logging

#### `QUIRE_SERVER_LOG_LEVEL`

- Type: `DEBUG`, `INFO`, `WARNING`, `ERROR` or `CRITICAL`, in any case
- Default: `INFO`
- Example: `QUIRE_SERVER_LOG_LEVEL=DEBUG`

How much the server logs. At `INFO` you see each generated card
(`ai.generate`), retries, retrieval problems and the server's outgoing HTTP
requests; at `WARNING`, only problems. Any other word stops the server at
boot with `validation error for Settings` naming `log_level`. It does not
affect the one line per request, such as `"GET /health HTTP/1.1" 200 OK`,
that uvicorn, the web server inside the container, prints.

When to change it: keep `INFO` while setting up; most of the evidence in
[Reading AI errors](#reading-ai-errors) is logged at that level.

### Database

#### `QUIRE_SERVER_DATABASE_URL`

- Type: SQLAlchemy database URL for the `asyncpg` driver, starting with
  `postgresql+asyncpg://`
- Default: user and password `postgres` on `localhost:5432`, with the
  database name the compose files use (in `server/quire_server/config.py`).
  Both compose files point it at their `postgres` service, with that
  service's `POSTGRES_USER` and `POSTGRES_DB` and your `POSTGRES_PASSWORD`
- Example: `postgresql+asyncpg://` followed by `user:password@host:5432/database`

Where the server keeps its data; the migration step uses the same address.
Both compose files set it under `environment:`, which wins over `.env`, so a
line in `.env` is ignored. To use a database outside the compose file, edit
that line in the compose file. The database user and database names in the
compose files are historical and kept on purpose: renaming them on an
existing install points the server at an empty database.

When to change it: only to move the data to a Postgres you run elsewhere.

### Calibre-web login check

#### `QUIRE_SERVER_CWA_PROBE_PATH`

- Type: path starting with `/`
- Default: `/opds`
- Example: `QUIRE_SERVER_CWA_PROBE_PATH=/opds`

The calibre-web page the server requests to check a reader's username and
password (see [`QUIRE_SERVER_CWA_BASE_URL`](#quire_server_cwa_base_url)). It
must answer 200 for good credentials and 401 for bad ones.

When to change it: only if your calibre-web, or a proxy in front of it,
serves the OPDS catalogue somewhere else.

#### `QUIRE_SERVER_CWA_PROBE_TIMEOUT_S`

- Type: seconds, decimals allowed
- Default: `3.0`
- Example: `QUIRE_SERVER_CWA_PROBE_TIMEOUT_S=10`

How long that check may take. A slower answer makes the request fail with 503
`upstream auth unavailable`.

When to change it: raise it if calibre-web is slow to answer and the log
shows `upstream auth unavailable` lines.

#### `QUIRE_SERVER_AUTH_CACHE_POSITIVE_TTL_S` and `QUIRE_SERVER_AUTH_CACHE_NEGATIVE_TTL_S`

- Type: whole seconds
- Default: `60` and `10`
- Example: `QUIRE_SERVER_AUTH_CACHE_POSITIVE_TTL_S=300`

How long the server remembers a successful and a failed check, so it does not
ask calibre-web on every request. An old password, or an account removed in
calibre-web, keeps working against the server for up to the first value.

When to change it: raise the first to spare a slow calibre-web; lower it if
access must end sooner after you remove an account.

#### `QUIRE_SERVER_AUTH_CACHE_MAX_ENTRIES`

- Type: whole number
- Default: `1024`
- Example: `QUIRE_SERVER_AUTH_CACHE_MAX_ENTRIES=4096`

How many remembered checks the server keeps, one per username and password
pair; the least recently used is dropped first.

When to change it: practically never on a household server.

### Reverse proxy

#### `FORWARDED_ALLOW_IPS`

- Read by: uvicorn, the web server inside the Quire Server container
- Type: comma-separated IP addresses or networks, or `*`
- Default: `127.0.0.1,::1`; `docker-compose.full.yml` sets `*`
- Example: `FORWARDED_ALLOW_IPS=10.1.0.0/16`

Which direct callers the server believes when they say, in
`X-Forwarded-For`, who the real client is. Sign-in, self-service migration and
device claims allow 8 attempts per client address per minute. Behind a proxy
that the server does not trust, every reader shares the proxy's address, so
one client can use up those attempts for everyone. Name only your proxy:
anything on the list can claim to be any address and so dodge the limit.

The full-stack compose sets `*` because quire-server publishes no port there
and Caddy, which replaces any `X-Forwarded-For` a client sends, is the only
way in. If you publish quire-server's port in that file, set Caddy's address
instead. With `docker-compose.yml` behind your own proxy, put the proxy's
address in `.env`. On Kubernetes, set it on the quire-server container to the
network your ingress controller's pods use (for example the cluster's pod
range), keep other workloads from reaching the server's port directly, and
make sure the controller sees the real client address: behind a cloud load
balancer that usually means `externalTrafficPolicy: Local` or the PROXY
protocol.

When to change it: whenever a reverse proxy or ingress sits in front of
Quire Server.

### Request limits

#### `QUIRE_SERVER_MAX_REQUEST_BYTES`

- Type: whole number of bytes
- Default: `1048576` (1 MiB)
- Example: `QUIRE_SERVER_MAX_REQUEST_BYTES=4194304`

The largest request body the server accepts; larger ones get 413. GET, HEAD,
OPTIONS and DELETE requests are not checked. The PDF upload of
[PDF conversion](#pdf-conversion) has its own limit,
`QUIRE_SERVER_CONVERT_MAX_BYTES`.

When to change it: if a library sync from the app fails with 413.

#### `QUIRE_SERVER_LIBRARY_SYNC_MAX_ITEMS`

- Type: whole number
- Default: `500`
- Example: `QUIRE_SERVER_LIBRARY_SYNC_MAX_ITEMS=1000`

The most books one `POST /library/v1/sync` call may carry. More gets 422
`too_many_items`, with the limit in the answer.

When to change it: if library sync fails with that error. A larger batch may
also need `QUIRE_SERVER_MAX_REQUEST_BYTES` raised.

### Login modes

Readers can sign in with their calibre-web login or, in `transition` and
`native` mode, with a Quire account. `server/.env.example` starts new
installations in `transition` with self-service migration on. A server whose
`.env` does not name a mode keeps the `calibreweb` default, so an upgrade
alone changes nothing. `server/README.md` ("AI auth mode") explains the token
format and key rotation.

#### `QUIRE_SERVER_AUTH_BACKEND`

- Type: `calibreweb`, `native` or `transition`, in lower case
- Default: `calibreweb` (`server/.env.example` sets `transition`)
- Example: `QUIRE_SERVER_AUTH_BACKEND=transition`

`calibreweb` checks logins through Calibre-Web. `native` checks account
email and password on Quire and issues bearer sessions. A server operator
can provision accounts using `scripts/create_native_account.py`.
`transition` offers native sign-in while keeping Calibre Basic available
for accounts whose reading scope has yet to be bound to a native account.
`transition` does not start with `QUIRE_SERVER_AI_AUTH_MODE=token` or with
`QUIRE_POLICY_URL` set.
See the [migration procedure](../server/README.md#moving-an-existing-calibre-reader-to-native-login).

#### `QUIRE_SERVER_NATIVE_SESSION_TTL_S`

- Type: whole seconds
- Default: `2592000` (30 days)
- Example: `QUIRE_SERVER_NATIVE_SESSION_TTL_S=604800`

How long a native login lasts in `native` or `transition` mode.

#### `QUIRE_SERVER_SELF_MIGRATION_ENABLED`

- Type: `true` or `false`
- Default: `false` (`server/.env.example` sets `true`)
- Example: `QUIRE_SERVER_SELF_MIGRATION_ENABLED=true`

Only read in `transition` mode. When `true`, a reader who still syncs with
their Calibre-Web login can create their own Quire account from the app. The
app sends that Calibre login once more, the server checks it with Calibre-Web,
and the reader's existing library, progress and highlights move to the new
account in one step. No operator command is needed. Anyone whose Calibre-Web
login works can create an account this way, and an Android app still on a
Calibre-Web login opens **Create your Quire account** once by itself. Set it
`false` if you prefer to verify and migrate each reader yourself.

#### `QUIRE_SERVER_CANONICAL_URL`

- Type: `http(s)` URL without a query
- Default: unset
- Example: `QUIRE_SERVER_CANONICAL_URL=https://quire.example.org`

The one address apps should use for this server. Set it when the server is
reachable under more than one address and you want readers on a single one,
for example after giving Quire its own hostname. An Android app signed in to a
Quire account at any other address checks on launch, confirms the same account
answers at this address, and moves itself there. Reading progress, highlights
and notes stay with the reader and the catalog connection is untouched. The
address must serve the sync, library, AI and account routes before you set it.
Devices still on a Calibre-Web login are not moved.

#### `QUIRE_SERVER_LEGACY_CLAIM_DAYS`

- Type: whole days, `0` to `365`
- Default: `30`
- Example: `QUIRE_SERVER_LEGACY_CLAIM_DAYS=7`

Only read when self-migration is enabled. For this many days after a reader
migrates themselves, their other devices can exchange the old Calibre login
for a Quire session without typing anything. During that window the Calibre
password still opens the Quire account, and a claim needs Calibre-Web to be
reachable. `0` turns claims off, so other devices sign in with the new
password or by scanning a pairing code. Accounts migrated by an operator
command are never claimable.

#### `QUIRE_SERVER_AI_AUTH_MODE`

- Type: `basic` or `token`
- Default: `basic`
- Example: `QUIRE_SERVER_AI_AUTH_MODE=basic`

How `/ai/v1` requests prove who is asking. `basic` uses the same login as
the rest of the server. `token` accepts signed bearer tokens instead: it is
deprecated, logs a warning at boot, and refuses to start unless the token
settings below are valid.

When to change it: don't.

#### `QUIRE_SERVER_AI_TOKEN_SECRETS`

- Type: JSON object that maps a key id to a secret of at least 32 bytes
- Default: unset
- Example: `QUIRE_SERVER_AI_TOKEN_SECRETS='{"key-2026-09": "<32 or more random characters>"}'`

The signing keys for `token` mode; listing several ids allows rotation. Only
`token` mode uses it, but an empty line, `QUIRE_SERVER_AI_TOKEN_SECRETS=`,
stops the server at boot in any mode, so comment it out instead.

When to change it: only with `token` mode.

#### `QUIRE_SERVER_AI_TOKEN_ISSUER` and `QUIRE_SERVER_AI_TOKEN_AUDIENCE`

- Type: text
- Default: unset
- Example: `QUIRE_SERVER_AI_TOKEN_ISSUER=https://issuer.example.com` and
  `QUIRE_SERVER_AI_TOKEN_AUDIENCE=quire-server`

The `iss` and `aud` values every token must carry in `token` mode, where both
are required. Unused otherwise.

When to change it: only with `token` mode.

### Browser identity and OIDC

These settings are optional. Set them in the server process environment. Docker
Compose also reads them from its `.env` file.

#### `QUIRE_IDENTITY_PUBLIC_URL`

The fixed public origin for browser sign-in and the OIDC callback. Use an HTTPS
origin without a route, query, or fragment. The default is unset.

#### `QUIRE_IDENTITY_ALLOW_INSECURE_LOOPBACK`

Set to `true` to allow HTTP on a loopback origin for local testing. Defaults to
`false`.

#### `QUIRE_IDENTITY_OIDC_ISSUER`

The OpenID Connect provider's issuer URL. Set it together with the client ID
and public origin to enable OIDC. The default is unset.

#### `QUIRE_IDENTITY_OIDC_CLIENT_ID`

The client ID registered with the OIDC provider. The default is unset.

#### `QUIRE_IDENTITY_OIDC_CLIENT_SECRET`

The OIDC client secret, when required by the provider. The default is unset.

#### `QUIRE_IDENTITY_SERVICE_TOKEN`

The service-to-service token for identity operations. A configured token must
be at least 32 characters. The default is unset.

### External AI allowance policy

These optional settings enable a separate service to apply AI allowances. Set
them together in the server process environment or Compose `.env`.

#### `QUIRE_POLICY_URL`

The fixed HTTP or HTTPS origin of the policy service. The default is unset.

#### `QUIRE_POLICY_TOKEN`

The service-to-service token for the policy API. A configured token must be at
least 32 characters. The default is unset.

### Rollback and deprecated switches

#### `QUIRE_SERVER_AI_PROMPT_VERSION`

- Type: text
- Default: `1`, which means "the built-in version": currently `8`, the
  `PROMPT_VERSION` constant in `server/quire_server/core/ai/prompts.py`. An
  empty value means the same.
- Example: `QUIRE_SERVER_AI_PROMPT_VERSION=7`

The label cards are stored and looked up under. The prompt the server sends
does not change with it. Setting an older label makes the server serve the
cards it stored under that label instead of generating new ones; cards for
books without one are still written with the current prompt, filed under the
older label. `GET /ai/v1/config` shows the label in use as `prompt_version`.

When to change it: only as an emergency rollback when a new prompt
misbehaves, and remove it afterwards.

#### `QUIRE_SERVER_AI_METADATA_SERVER_LOOKUP_ENABLED`

- Type: `true` or `false`
- Default: `false`
- Example: `QUIRE_SERVER_AI_METADATA_SERVER_LOOKUP_ENABLED=false`

Deprecated. When an insight request arrives without the book's details,
`true` makes the server fill them in from that reader's synced library
instead of answering 400 `metadata_required`. Current apps always send the
details. The server logs a deprecation warning at boot when this is `true`;
`server/README.md` ("Push-model API") has the background.

When to change it: don't.

### Quire Web

#### `QUIRE_SERVER_WEB_ENABLED`

Defaults to `true`. Set to `false` to stop serving the browser shell and its
assets at `/quire/`. Already installed offline clients can retain their public
shell in browser storage.

#### `QUIRE_SERVER_WEB_AT_ROOT`

Defaults to `false`. Set to `true` on a hostname that serves only Quire, so
readers open `https://quire.example.org/` and not `/quire/`. Old `/quire/`
links and installed apps are redirected and offline books stay, because the
browser keeps them per hostname. Route `/` to Quire as an exact path. Leave it
`false` when Quire shares a hostname with Calibre-Web, where `/` is Calibre's.

#### `QUIRE_SERVER_WEB_NATIVE_AUTH_ENABLED`

Defaults to `false`. Set to `true` with native authentication and
`QUIRE_IDENTITY_PUBLIC_URL` on a dedicated Quire HTTPS origin. Keep
calibre-web and other applications on separate origins. See the
[isolated origin reference](../server/README.md#native-browser-authentication-on-an-isolated-origin).

#### `QUIRE_SERVER_WEB_CATALOG_URL`

Public browser OPDS feed URL. Use a same-origin path such as `/opds` or an
absolute HTTPS URL for a catalogue that allows cross-origin browser requests.
The full-stack Compose defaults to `/opds`. Leave unset in the minimal stack
to let readers configure their own catalogue. Credentials belong in the
browser's catalogue settings.

#### `QUIRE_SERVER_WEB_GUEST_READING`

Defaults to `true`. When the server offers sign-in, signed-out visitors first
see a welcome page with the sign-in form. With `true` that page also offers
"Read on this device without an account": the visitor's books then stay in
their own browser and nothing is sent to the server. Set to `false` on a
private instance so the page offers sign-in only. Servers without browser
sign-in always open the local reader.

### Build and tooling

#### `QUIRE_SERVER_IMAGE`

Image reference used by both reference Compose deployments. Defaults to
`ghcr.io/vitofico/quire-server:latest`. Set an image digest to pin a deployment
or point to a previously verified build for rollback.

#### `QUIRE_SERVER_VERSION`

- Type: text
- Default: `dev`; published images set it to the commit they were built from
- Example: `GET /health` shows `"version": "dev"` for a local build, and the
  40-character commit id for a published image

The build identity that `GET /health` reports as `version`. `server/Dockerfile`
sets it from the `QUIRE_VERSION` build argument, which the image job in
`.github/workflows/server-ci.yaml` fills with the commit id. A line in `.env`
would override the image's value and make `/health` report the wrong build.

When to change it: never; leave it out of `.env`.

#### `ALEMBIC_INI`

- Read by: `server/scripts/migrate.py`, the migration step that runs before
  the server starts; not by the server
- Type: file path
- Default: `alembic.ini`, relative to the working folder (`/app` in the image)
- Example: `ALEMBIC_INI=/app/alembic.ini`

Where the migration step finds its configuration file.

When to change it: only in a custom image that moves that file.

## Recipes

Each block goes into `.env` together with the lines from
[Minimum `.env` per mode](#minimum-env-per-mode). Then recreate the server as
in [Applying a change](#applying-a-change).

### Ollama on the same machine

Here Ollama runs directly on the machine that runs Docker, while the server
runs in a container. From inside a container, `localhost` is the container
itself, so `http://localhost:11434` reaches nothing. Docker offers the name
`host.docker.internal` for the machine instead. Both compose files already
map it with these lines under `quire-server`, so there is nothing to add; if
you wrote your own compose file, add them:

```yaml
extra_hosts:
  - "host.docker.internal:host-gateway"
```

```dotenv
QUIRE_SERVER_AI_BASE_URL=http://host.docker.internal:11434/v1
# Any model that `ollama list` shows on that machine.
QUIRE_SERVER_AI_MODEL=llama3.2:1b
```

Leave `QUIRE_SERVER_AI_API_KEY` out: a local Ollama needs no key.

On Linux, Ollama listens only on `127.0.0.1` by default, which a container
cannot reach. Make it listen on every interface by setting
`OLLAMA_HOST=0.0.0.0:11434` in Ollama's own environment: for the systemd
service, run `sudo systemctl edit ollama`, add
`Environment="OLLAMA_HOST=0.0.0.0:11434"` under `[Service]`, then
`sudo systemctl daemon-reload` and `sudo systemctl restart ollama`. Allow
Docker's network through your firewall too. Ollama is then reachable from
your local network, so keep port 11434 closed to the internet. Docker
Desktop on macOS and Windows routes `host.docker.internal` to the machine's
`localhost` and needs neither step.

To test the connection from inside the container, with the address you
configured:

```sh
docker compose exec quire-server python -c "import os, urllib.request; print(urllib.request.urlopen(os.environ['QUIRE_SERVER_AI_BASE_URL'] + '/models', timeout=10).status)"
```

`200` means the server can reach Ollama. `HTTP Error 404` means it reached
something but at the wrong path, usually a base URL without `/v1`. A
timeout or `Connection refused` points at the address, at where Ollama
listens, or at the firewall; the app would show `provider_unreachable`.

### CPU-only local models

A model running on a CPU without a GPU works, but slowly. Quire's prompt is
a few thousand characters (the book's details, retrieved text and
instructions), and on a CPU reading that prompt is most of the time. A quick
answer in `ollama run` proves little, because that prompt is a few words.

```dotenv
QUIRE_SERVER_AI_BASE_URL=http://host.docker.internal:11434/v1
QUIRE_SERVER_AI_MODEL=llama3.2:1b
# 285 s per call: the app then waits 2 x 285 + 30 = 600 s, its maximum.
QUIRE_SERVER_AI_TIMEOUT_S=285
QUIRE_SERVER_AI_PROFILE_TIMEOUT_S=285
# One card at a time, so two books do not share the CPU and both time out.
QUIRE_SERVER_AI_MAX_CONCURRENCY=1
```

If cards still time out, work down this list:

1. Read `latency_ms` and `prompt_chars` on the `event=ai.generate.error` log
   line. A `latency_ms` close to your timeout means the model is too slow
   for it.
2. Turn retrieval off to shrink the prompt:

   ```dotenv
   QUIRE_SERVER_AI_SOURCES=
   ```

   If cards now arrive, the model cannot read the extra context in time.
   Without Wikipedia and Open Library, though, a small model makes things up
   more often. `QUIRE_SERVER_AI_SOURCES=openlibrary` keeps one source.
3. Ollama unloads a model after five idle minutes by default, so the first
   card after a pause also pays the loading time. `OLLAMA_KEEP_ALIVE=30m` in
   Ollama's environment keeps it loaded longer.
4. Try a hosted model (next recipe). If that works, your setup is right and
   the CPU is the limit.

A small model can also fail on the card's structure
(`provider_invalid_output`). That is not a timeout; a larger model is the
fix.

### Ollama's hosted models

Ollama also runs models on its own servers, with a free tier. Nothing runs
on your hardware and a card comes back in seconds. The trade-off is privacy:
the prompt, with the book's details and retrieved text, leaves your machine.

```dotenv
QUIRE_SERVER_AI_BASE_URL=https://ollama.com/v1
QUIRE_SERVER_AI_MODEL=gpt-oss:120b-cloud
QUIRE_SERVER_AI_API_KEY=your-ollama-api-key
```

Create the key in your ollama.com account. Hosted usage counts against your
Ollama plan, free tier included, so keep
[`QUIRE_SERVER_AI_DAILY_BUDGET`](#quire_server_ai_daily_budget) and
[`QUIRE_SERVER_AI_RATE_PER_MIN`](#quire_server_ai_rate_per_min) at values you
are comfortable with.

### Any OpenAI-compatible provider

Any service that implements OpenAI's chat completions API works: OpenAI,
OpenRouter, a vLLM or llama.cpp server, and others. You need three things
from its documentation: the base URL (usually ending in `/v1`), a model
name, and an API key if it requires one.

```dotenv
QUIRE_SERVER_AI_BASE_URL=https://api.openai.com/v1
QUIRE_SERVER_AI_MODEL=the-model-name-your-provider-lists
QUIRE_SERVER_AI_API_KEY=your-provider-api-key
```

For a model server on your own machine, use
`http://host.docker.internal:<port>/v1` as in
[Ollama on the same machine](#ollama-on-the-same-machine). A provider that
does not support the structured-output mode the server asks for first still
works: the server falls back to plain JSON on its own (see
[What has no setting](#what-has-no-setting)). On a metered provider, lower
[`QUIRE_SERVER_AI_DAILY_BUDGET`](#quire_server_ai_daily_budget) to cap what
each reader can spend.

## Did my change take effect?

Five checks, from quickest to most detailed. With
[`QUIRE_SERVER_ADMIN_USERS`](#quire_server_admin_users) set, the
[status page](#status-page) shows checks 2 to 4 on one page. The examples use
`docker-compose.yml` on port 8000. With the full stack, add
`-f docker-compose.full.yml` to the compose commands and use
`https://<your-host>` in the `curl` commands (`curl -k https://localhost`
for the self-signed default).

**1. Did the container get the value?** Print it from inside the container:

```sh
docker compose exec quire-server printenv QUIRE_SERVER_AI_TIMEOUT_S
```

Nothing printed means the container does not have it: check the name in
`.env`, and that you recreated the container.

**2. Did the server accept it?** `GET /health` needs no login and repeats
every configuration warning the server logged at boot:

```sh
curl -s http://localhost:8000/health
```

```json
{
  "ready": true,
  "version": "dev",
  "modes": ["progress", "ai"],
  "warnings": [
    "Unknown setting QUIRE_SERVER_AI_TIMEOUT is ignored; check the spelling against docs/configuration.md",
    "AI is enabled but QUIRE_SERVER_AI_MODEL is not set; the app will report AI as unconfigured. Set it or set QUIRE_SERVER_AI_ENABLED=false"
  ]
}
```

- `modes` lists the parts that are on: `progress`, `ai`, or both.
- `warnings` is empty when the configuration is clean. The server warns
  about five things (`config_warnings` and `unknown_env_vars` in
  `server/quire_server/config.py`): a `QUIRE_SERVER_*` name it does not know
  (except the variables Kubernetes adds for a Service named `quire-server`,
  such as `…_SERVICE_HOST` and `…_PORT_80_TCP`), AI enabled without a base URL or model, a base URL that does not end in
  `/v1`, a retrieval source name in `QUIRE_SERVER_AI_SOURCES` it does not
  recognise, and both modes off. The warnings name variables and never show
  values, because `/health` is public.
- `version` is the build you run; see
  [Which releases change the server](#which-releases-change-the-server).

`/health` does not touch the database. `curl -s http://localhost:8000/readyz`
does: it answers 503 with `"detail": "db unreachable"` when the server cannot
reach Postgres, for example after a `POSTGRES_PASSWORD` change.

**3. Did the AI settings land?** `GET /ai/v1/config` shows the AI settings
the server runs with. It needs a calibre-web login; `curl` asks for the
password:

```sh
curl -s -u your-calibre-username http://localhost:8000/ai/v1/config
```

```json
{
  "configured": true,
  "base_url_host": "host.docker.internal",
  "model_id": "llama3.2:1b",
  "sources_enabled": [],
  "daily_budget": 200,
  "regen_daily_limit": 3,
  "prompt_version": "8",
  "progress_supported": true,
  "generation_timeout_s": 285,
  "profile_timeout_s": 285
}
```

`configured` is `true` when AI is on and both base URL and model are set.
`base_url_host` is only the host part of `QUIRE_SERVER_AI_BASE_URL`.
`sources_enabled` lists the retrieval sources in use, without names the
server does not recognise. The two timeouts are rounded up, and they are what
the app sizes its waits from.

**4. Is the provider reachable?** `GET /ai/v1/health` needs no login. It
reports what the server has seen since it started; it never probes on its
own, so everything is `null` until the first card is requested or the status
page's **Test AI connection** button is pressed:

```sh
curl -s http://localhost:8000/ai/v1/health
```

`provider_reachable` becomes `true` after a successful generation and
`false` after a failed one, with the failure's `error_class` (see below) in
`last_failure_class`. `retrieval_sources` shows the same for Wikipedia and
Open Library.

**5. Read the logs.**

```sh
docker compose logs --tail=100 quire-server
```

At startup the migration step prints the modes it read, for example
`[migrate] modes: progress=True ai=False`, and the server prints each
configuration warning as `event=config.warning msg=...`.

## Reading AI errors

When a card fails, the failure shows up in three places: the app shows a
sentence, the AI endpoint answers with a code, and the server log has a line
with an `error_class`.

### The log line

```text
WARNING:quire_server.core.ai.service:event=ai.generate.error tenant_id=local subject=alice model=llama3.2:1b prompt_version=8 latency_ms=120011 error_class=ProviderTimeout prompt_chars=3412 hint=Raise QUIRE_SERVER_AI_TIMEOUT_S for slow local models, or pick a faster model.
```

- `subject`: the reader, by calibre-web username. `tenant_id` is always
  `local` on a self-hosted server.
- `latency_ms`: how long the server waited, in milliseconds.
- `prompt_chars`: how long the prompt was. Turning retrieval off shrinks it.
- `hint`: the same advice the app receives.

### `error_class` values

| `error_class` | HTTP answer | What happened |
| --- | --- | --- |
| `ProviderTimeout` | 504 `provider_timeout` | Connected, but the model did not answer within `QUIRE_SERVER_AI_TIMEOUT_S`. |
| `ProviderUnreachable` | 502 `provider_unreachable` | No connection within 10 seconds, connection refused, unknown host name, or the provider answered with a server error (HTTP 5xx). |
| `ProviderRejected` | 502 `provider_rejected` | The provider refused the request; `provider_status` says why. |
| `ProviderParseError` | 502 `provider_invalid_output` | The model answered, twice, but not in the card's structure; or something other than the provider answered, such as a web page from a proxy. |
| anything else | 500 | An unexpected error; a traceback follows in the log. |

What to do about each:

- `ProviderTimeout`: see [CPU-only local models](#cpu-only-local-models).
- `ProviderUnreachable`: check `QUIRE_SERVER_AI_BASE_URL` and test the
  connection from inside the container (see
  [Ollama on the same machine](#ollama-on-the-same-machine)). Images built
  before 21 September 2026 reported a failed connection as
  `ProviderTimeout` with `latency_ms` near 10000; update the image.
- `ProviderRejected`: `provider_status` 401 or 403 means check
  `QUIRE_SERVER_AI_API_KEY`; 404 means check `QUIRE_SERVER_AI_MODEL`
  (`ollama pull <model>` for a local Ollama). For anything else, look at the
  provider's own logs.
- `ProviderParseError`: try a larger model, and check that
  `QUIRE_SERVER_AI_BASE_URL` points at the provider and not at a web page.
- Anything else: open an issue with the traceback.

The Reader Profile logs its failures as
`profile.refresh.error tenant=... subject=... latency_ms=... err=<class>`
with the same classes, plus `TimeoutError` when the server's outer safety
limit (twice `QUIRE_SERVER_AI_PROFILE_TIMEOUT_S` plus a second) runs out; that
one is answered as `provider_timeout` too.

### The HTTP answer

The app shows `message` and keeps `hint` for whoever runs the server
(`server/quire_server/core/ai/provider_errors.py`):

```json
{
  "detail": {
    "code": "provider_unreachable",
    "message": "The server could not reach the AI provider.",
    "hint": "Check QUIRE_SERVER_AI_BASE_URL and that the provider is running and reachable from the quire-server container.",
    "provider_status": null
  }
}
```

`provider_status` is the provider's own HTTP status, set only for
`provider_rejected`. The code also defines a catch-all, 502 `provider_error`
with no hint, which no current failure produces.

### Other log lines worth knowing

- `ai.generate content_hash=... model=... latency_ms=... sources=openlibrary,wikipedia regen=False`:
  a card was generated. `sources` lists the retrieval sources that
  contributed, in alphabetical order, `-` for none.
- `ai.client.validation_retry error_class=ValidationError errors=1 chars=... native_schema=True kinds=...`:
  the first answer did not fit the card's structure and the server is
  retrying. Here `error_class` is `ValidationError` (wrong or missing
  fields) or `JSONDecodeError` (not JSON at all). `kinds` says what was
  wrong and where without quoting the model, for example
  `string_too_long@analysis`, or `json_invalid@root[...]` for an answer cut
  off halfway.
- `ai.client.validation_failed ...`: the retry failed too, and a
  `ProviderParseError` follows.
- `ai.retrieval.wikipedia.fail`, `.status` or `.over_budget`, and the same
  for `openlibrary`: a retrieval source could not be reached, answered with
  an error, or ran out of time. The card is generated without it.
- `upstream auth unavailable: ...` or `CWA returned 302 on auth probe`: the
  login check against calibre-web failed; see
  [`QUIRE_SERVER_CWA_BASE_URL`](#quire_server_cwa_base_url).

### Other answers from the AI endpoints

| Answer | Meaning |
| --- | --- |
| 404 on every `/ai/v1` address | AI is off: `QUIRE_SERVER_AI_ENABLED=false`. |
| 503 `ai_disabled` | AI is on, but `QUIRE_SERVER_AI_BASE_URL` or `QUIRE_SERVER_AI_MODEL` is missing. `/health` warns about it. |
| 409 `ai_not_opted_in` | This reader has not turned AI on in the app. |
| 429 with `used`, `limit` and `resets_at` | One of the daily budgets ran out; it resets at midnight UTC. |
| 400 `metadata_required` | The request carried no book details; current apps always send them. |
| 503 `upstream auth unavailable` | The login check against calibre-web failed. |

## Which releases change the server

The app and the server ship separately.

- **The app.** A GitHub release (`vYYYY.MM.DD.N`, with an APK that F-Droid
  then builds too) is cut only when app code changes (`docs/release.md`). A
  change that touches only the server cuts no release.
- **The server image.** Every change merged into `server/` publishes a new
  `ghcr.io/vitofico/quire-server:latest` as soon as the server tests pass,
  also tagged with the commit id (the `image` job in
  `.github/workflows/server-ci.yaml`). It has no version number of its own
  and does not wait for an app release. A change to the server's CI workflow
  or to the identity test fixtures it shares with the app also publishes a
  new image, with no change in behaviour.

To tell whether a release touched the server, read its notes: they list
every pull request merged since the previous release, and titles that start
with `feat(server)` or `fix(server)` changed the server. Those changes reached
`latest` when they merged, usually before the release that lists them. Server
changes merged after the latest release appear in no release notes until the
next one.

To see which build you run, read `version` from `GET /health`: the commit id
the image was built from, or `dev` for an image you built yourself. Images
built before 22 September 2026 have no `version` field. Compare it with the
newest commit under
[`server/` on main](https://github.com/vitofico/quire/commits/main/server)
or with the tags on the
[image's package page](https://github.com/vitofico/quire/pkgs/container/quire-server).
To update, pull and recreate; migrations run on their own at start:

```sh
docker compose pull quire-server
docker compose up -d quire-server
```

There is no server changelog today, and the server does not tell you when a
newer image exists.


### `QUIRE_SERVER_AI_PROVIDERS`

Optional JSON array of additional provider connections. Keep the existing
`AI_BASE_URL`, `AI_MODEL` and `AI_API_KEY` as the server default. Each additional
connection has `id`, `label`, `base_url`, `model`, `key_env` and an optional
`temperature` (omitted by default). IDs must be unique, lowercase, at most 32
characters, and cannot be `default`. Set `key_env` to the name of a separate
server environment variable holding that provider's key. Never put keys in the
JSON, the app, or source control. At most 15 additional connections are allowed.

For example, with a separately supplied `OPENAI_API_KEY`:

```json
[{"id":"openai","label":"OpenAI · GPT-6.1 Terra","base_url":"https://api.openai.com/v1","model":"gpt-6.1-terra","key_env":"OPENAI_API_KEY"}]
```

In **Settings → AI features → AI provider**, choose a connection for the signed-in
account. Selection persists on the server and applies across devices. The app
shows the selected service before AI use; selecting a provider does not enable
AI or send book content. Existing clients keep using the default unless that
account has selected another provider. Removing a selected connection makes AI
requests fail with `ai_provider_unavailable` until the user chooses another.

`GET /ai/v1/config` lists approved connections without credentials and reports
the account's `provider_id`, model and service host. `PUT /ai/v1/preferences`
accepts a configured `provider_id`; omitted or null preserves the selection and
`default` restores the server default. Additional connections qualify the cache
`model_id` as `provider_id:model` so identically named models at different
endpoints cannot share an insight accidentally. Rate limits, concurrency and
quota guards remain shared. Migration `ai_010` adds the nullable provider choice;
apply the normal migration wrapper before starting the updated server.
