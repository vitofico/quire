# The worker that converts the PDFs Quire Server queues (docs/configuration.md,
# "PDF conversion"). Build from the repository root:
#   docker build --platform linux/amd64 -t quire-convert converter
# Calibre publishes this pinned release for x86_64, so the image is amd64 only.

FROM python:3.12-slim-bookworm AS tools
# Keep in step with CALIBRE_VERSION and CALIBRE_SHA512 in
# .github/workflows/converter-ci.yaml and CALIBRE_PIN in src/quire_convert/tools.py.
ARG CALIBRE_VERSION=9.19.0
# From https://calibre-ebook.com/signatures/calibre-9.19.0-x86_64.txz.sha512
ARG CALIBRE_SHA512=907e477a74f1292deaea822a1dc110fd420fcfc6d4e62746edbfd9749b15a87513fb4c8047f1e9dd39726a980fbe80de1cf74ce1868f7943268716840ab278e7
# Keep in step with scripts/fetch-epubcheck.sh and EPUBCHECK_PIN.
ARG EPUBCHECK_VERSION=5.4.0
ARG EPUBCHECK_SHA256=33350c61038e71dfb3d45a76aed04bf5481e6d5500cb780f6e98db8bbd15a28c
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl unzip xz-utils \
 && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL -o /tmp/calibre.txz \
        "https://download.calibre-ebook.com/${CALIBRE_VERSION}/calibre-${CALIBRE_VERSION}-x86_64.txz" \
 && echo "${CALIBRE_SHA512}  /tmp/calibre.txz" | sha512sum -c - \
 && mkdir /opt/calibre \
 && tar -xJf /tmp/calibre.txz -C /opt/calibre \
 && rm /tmp/calibre.txz
RUN curl -fsSL -o /tmp/epubcheck.zip \
        "https://github.com/w3c/epubcheck/releases/download/v${EPUBCHECK_VERSION}/epubcheck-${EPUBCHECK_VERSION}.zip" \
 && echo "${EPUBCHECK_SHA256}  /tmp/epubcheck.zip" | sha256sum -c - \
 && unzip -q /tmp/epubcheck.zip -d /tmp \
 && mv "/tmp/epubcheck-${EPUBCHECK_VERSION}" /opt/epubcheck \
 && rm /tmp/epubcheck.zip

FROM python:3.12-slim-bookworm

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_NO_CACHE_DIR=1 \
    VIRTUAL_ENV=/opt/venv \
    PATH=/opt/venv/bin:$PATH

# Poppler, a Java runtime for EPUBCheck, and the libraries Calibre's bundled Qt
# loads when it renders offscreen.
RUN apt-get update && apt-get install -y --no-install-recommends \
        poppler-utils openjdk-17-jre-headless \
        libegl1 libopengl0 libglx0 libxcb-cursor0 libxkbcommon0 libnss3 libfontconfig1 \
 && rm -rf /var/lib/apt/lists/*

COPY --from=tools /opt/calibre /opt/calibre
COPY --from=tools /opt/epubcheck /opt/epubcheck

WORKDIR /app
RUN python -m venv "$VIRTUAL_ENV"
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir . && rm -rf /app/src /app/build

COPY LICENSE /licenses/quire-convert-LICENSE
RUN cp /opt/calibre/LICENSE /licenses/calibre-LICENSE 2>/dev/null || true

# No home directory: Calibre and Java keep their caches under /tmp, so the
# root filesystem can be mounted read-only with a tmpfs on /tmp.
ENV HOME=/tmp \
    QT_QPA_PLATFORM=offscreen \
    QC_EBOOK_CONVERT=/opt/calibre/ebook-convert \
    QC_JAVA=/usr/bin/java \
    EPUBCHECK_JAR=/opt/epubcheck/epubcheck.jar

RUN useradd --uid 1000 --no-create-home --home-dir /tmp --shell /usr/sbin/nologin convert
USER 1000:1000

LABEL org.opencontainers.image.title="quire-convert" \
      org.opencontainers.image.source="https://github.com/vitofico/quire" \
      org.opencontainers.image.description="Quire's PDF to EPUB conversion worker" \
      org.opencontainers.image.licenses="AGPL-3.0-only"

# The folder Quire Server shares (QUIRE_SERVER_CONVERT_DIR on the server side).
CMD ["quire-convert", "worker", "--jobs", "/jobs"]
