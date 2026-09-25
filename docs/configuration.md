# Server configuration

This page lists every setting `quire-server` reads, what each one does, and
how to check that a change took effect. It is written for someone running
the server with Docker Compose for the first time. To get a server running,
read [Required](#required), pick a mode in [Deploy modes](#deploy-modes),
copy the matching `.env` block, and come back here when something needs
changing.

Every name and default on this page comes from the code: the settings class
in `server/quire_server/config.py`, the compose files
`server/docker-compose.yml` and `server/docker-compose.full.yml`, and the
migration step `server/scripts/migrate.py`. A unit test
(`server/tests/unit/test_env_docs_drift.py`) fails when a setting exists in
the code without an entry here, or when this page names one that no longer
exists.

- [How settings reach the server](#how-settings-reach-the-server)
- [Required](#required)
- [Deploy modes](#deploy-modes)
- [AI provider](#ai-provider)
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
| `QUIRE_SERVER_AI_SOURCES` | Retrieval off. The default applies only when the line is missing. |
| `QUIRE_SERVER_AI_PROMPT_VERSION` | The built-in prompt version, the same as the default. |
| Numbers, `true`/`false` settings, fixed-choice settings, `QUIRE_SERVER_AI_TOKEN_SECRETS` | The server refuses to start. |
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

`/health` and `/readyz` answer in every mode. With both settings `false`,
only those two answer and the server warns about it at boot. The mode also
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

#### `QUIRE_SERVER_AI_REGEN_DAILY_LIMIT`

- Type: whole number
- Default: `3`
- Example: `QUIRE_SERVER_AI_REGEN_DAILY_LIMIT=10`

How many times per UTC day each reader may regenerate a card, that is, ask
for a new one for a book that already has one. Regenerations also count
against `QUIRE_SERVER_AI_DAILY_BUDGET`. Unlike the other limits, `0` does
not turn it off: it blocks regeneration completely.

When to change it: raise it while you try out models or styles.

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

## Advanced

Leave these at their defaults unless you know why you are changing them.
They cover logging, the database, the calibre-web login check, request size
limits, login modes built for a future hosted service, and deprecated
switches.

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

### Request limits

#### `QUIRE_SERVER_MAX_REQUEST_BYTES`

- Type: whole number of bytes
- Default: `1048576` (1 MiB)
- Example: `QUIRE_SERVER_MAX_REQUEST_BYTES=4194304`

The largest request body the server accepts; larger ones get 413. GET, HEAD,
OPTIONS and DELETE requests are not checked.

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

Quire's login is calibre-web's. The settings below exist for a future hosted
service and for older deployments, and a self-hosted server leaves them
alone. `server/README.md` ("AI auth mode") explains the token format and key
rotation.

#### `QUIRE_SERVER_AUTH_BACKEND`

- Type: `calibreweb` or `native`, in lower case
- Default: `calibreweb`
- Example: `QUIRE_SERVER_AUTH_BACKEND=calibreweb`

Who checks logins. `calibreweb` asks calibre-web, as described above.
`native` makes the server keep its own email-and-password accounts and adds
`/auth/v1/login` and `/auth/v1/logout`, but there is no way to create an
account yet (`docs/architecture.md`, "NativeAuth"), so it cannot serve
readers today. Any other value, an empty one included, stops the server at
boot.

When to change it: not on a self-hosted server.

#### `QUIRE_SERVER_NATIVE_SESSION_TTL_S`

- Type: whole seconds
- Default: `2592000` (30 days)
- Example: `QUIRE_SERVER_NATIVE_SESSION_TTL_S=604800`

How long a login lasts under `QUIRE_SERVER_AUTH_BACKEND=native`. Unused with
`calibreweb`.

When to change it: not on a self-hosted server.

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

### Build and tooling

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

Five checks, from quickest to most detailed. The examples use
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
  `server/quire_server/config.py`): a `QUIRE_SERVER_*` name it does not know,
  AI enabled without a base URL or model, a base URL that does not end in
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
own, so everything is `null` until the first card is requested:

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
