FROM python:3.12-slim AS base

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    VIRTUAL_ENV=/opt/venv \
    PATH=/opt/venv/bin:$PATH

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
        build-essential libpq-dev \
 && rm -rf /var/lib/apt/lists/*

# uv installs into a project venv (avoids PEP 668 on python:3.12-slim, which
# marks the system Python as externally-managed).
RUN pip install --no-cache-dir uv && uv venv "$VIRTUAL_ENV"

# Source needs to be present so setuptools can discover the package.
COPY pyproject.toml ./
COPY opds_sync ./opds_sync
RUN uv pip install "."

COPY migrations ./migrations
COPY alembic.ini ./

EXPOSE 8000
CMD ["uvicorn", "opds_sync.main:app", "--host", "0.0.0.0", "--port", "8000"]
