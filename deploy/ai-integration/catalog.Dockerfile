FROM postgres:16-bookworm@sha256:cd0e9518bda5f2ea667ff84d22795d089720c883f288e215d97ce57437e8ef37
RUN sed -i 's|http://|https://|g' /etc/apt/sources.list.d/debian.sources \
    && apt-get -o Acquire::Retries=3 update \
    && apt-get -o Acquire::Retries=3 install -y --no-install-recommends postgresql-16-cron \
    && rm -rf /var/lib/apt/lists/*
COPY --from=items sql /opt/item-sql/
COPY deploy/ai-integration/init-catalog.sh /docker-entrypoint-initdb.d/010-item.sh
