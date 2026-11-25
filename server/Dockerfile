FROM node:26-bookworm-slim AS web_build
WORKDIR /app/web
RUN npm install --global pnpm@9.15.9
COPY web/package.json web/pnpm-lock.yaml ./
RUN pnpm install --frozen-lockfile
COPY web/ ./
COPY core/identity/src/test/resources/identity/ /app/core/identity/src/test/resources/identity/
COPY reader/src/main/assets/ /app/reader/src/main/assets/
COPY branding/ /app/branding/
ARG QUIRE_VERSION=dev
RUN QUIRE_VERSION="$QUIRE_VERSION" pnpm build \
 && mkdir /app/web-licenses \
 && cp node_modules/react/LICENSE /app/web-licenses/react-LICENSE \
 && cp node_modules/react-dom/LICENSE /app/web-licenses/react-dom-LICENSE \
 && cp node_modules/.pnpm/scheduler@*/node_modules/scheduler/LICENSE /app/web-licenses/scheduler-LICENSE \
 && cp node_modules/fflate/LICENSE /app/web-licenses/fflate-LICENSE \
 && cp node_modules/@noble/hashes/LICENSE /app/web-licenses/noble-hashes-LICENSE

FROM postgres:16-bookworm AS postgres_tools

FROM python:3.12-slim AS base

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    VIRTUAL_ENV=/opt/venv \
    PATH=/opt/venv/bin:$PATH

WORKDIR /app

# Pillow's wheels carry Raqm but load FriBiDi at run time; with it the
# designed covers shape Arabic and other right-to-left scripts.
RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential libpq-dev postgresql-client libfribidi0 \
 && rm -rf /var/lib/apt/lists/*

# Match the supported PostgreSQL 16 server; newer dump output may not restore
# to an older major (for example transaction_timeout emitted by version 17).
COPY --from=postgres_tools /usr/lib/postgresql/16/bin/ /usr/local/pg16/bin/
ENV PATH="/usr/local/pg16/bin:${PATH}"

# uv installs into a project venv (avoids PEP 668 on python:3.12-slim, which
# marks the system Python as externally-managed).
RUN pip install --no-cache-dir uv && uv venv "$VIRTUAL_ENV"

# Source needs to be present so setuptools can discover the package.
COPY server/pyproject.toml ./
COPY server/quire_server ./quire_server
RUN uv pip install "."

COPY server/migrations ./migrations
COPY server/scripts ./scripts
COPY server/alembic.ini ./

COPY --from=web_build /app/web/dist /app/web-dist
COPY --from=web_build /app/web-licenses/ /app/licenses/
COPY server/LICENSE /app/licenses/quire-server-LICENSE
COPY LICENSE NOTICE /app/licenses/
COPY reader/src/main/assets/fonts/*.txt /app/licenses/

# Use the same build argument for API and browser reporting.
ARG QUIRE_VERSION=dev
ENV QUIRE_SERVER_VERSION=$QUIRE_VERSION

LABEL org.opencontainers.image.title="quire-server" \
      org.opencontainers.image.source="https://github.com/vitofico/quire" \
      org.opencontainers.image.description="Quire Server" \
      org.opencontainers.image.licenses="AGPL-3.0-only"

EXPOSE 8000
# Entrypoint: run the migrate wrapper (per-branch, mode-aware) then start the app.
# The wrapper reads QUIRE_SERVER_PROGRESS_ENABLED / QUIRE_SERVER_AI_ENABLED.
CMD ["sh", "-c", "python /app/scripts/migrate.py && exec uvicorn quire_server.main:app --host 0.0.0.0 --port 8000 --no-access-log"]
