FROM python:3.11-slim-trixie@sha256:61e01f72680f86a5279ad510818ab7b2ee9f049822589d4e34e33e044360356c AS driver-build
RUN apt-get -o Acquire::Retries=3 -o APT::Update::Error-Mode=any update \
    && apt-get install -y --no-install-recommends gcc libc6-dev libpq-dev \
    && python -m pip wheel --no-cache-dir --wheel-dir=/wheels 'psycopg[c]==3.2.13'

FROM python:3.11-slim-trixie@sha256:61e01f72680f86a5279ad510818ab7b2ee9f049822589d4e34e33e044360356c AS runtime
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
RUN sed -i 's|http://deb.debian.org|https://deb.debian.org|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=3 -o APT::Update::Error-Mode=any update \
    && apt-get -o Acquire::Retries=3 upgrade -y \
    && apt-get install -y --no-install-recommends libpq5 \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=items . /app/
RUN --mount=from=driver-build,source=/wheels,target=/wheels,ro \
    python -m pip install --no-cache-dir setuptools==80.9.0 \
    && python -m pip install --no-cache-dir --no-build-isolation . \
    && python -m pip install --no-cache-dir --no-index --find-links=/wheels 'psycopg[c]==3.2.13'
COPY deploy/ai-integration/entrypoint.py deploy/ai-integration/import_export.py /integration/
LABEL org.opencontainers.image.source="https://github.com/Kau-BugFree/hobby-tree-AI" \
      org.opencontainers.image.revision="0920a3ada3b50700af61bf792c859c3606a4a1be"
USER 10001:10001
ENTRYPOINT ["python", "/integration/entrypoint.py"]
CMD ["api"]

FROM runtime AS tests
USER 0:0
RUN python -m pip install --no-cache-dir pytest==9.2.0
USER 10001:10001

FROM runtime AS production
USER 0:0
# Build-only vendored dependencies must not survive in the deployed API/worker/sync.
RUN python -m pip uninstall -y setuptools wheel pip \
    && find /usr /bin /sbin -xdev -type f -perm /6000 -exec chmod a-s {} + \
    && rm -f /usr/bin/infocmp
USER 10001:10001
