FROM golang:1.27.3-bookworm@sha256:dc0963f32053e4bc451d24d87e3892d42d01021b61f093e98eb95c0352bb84b0 AS gosu-builder
# Rebuild the unchanged upstream privilege-drop tool with a patched Go toolchain.
ADD --checksum=sha256:2b70da8a5480fafedc16bb1fc91b572f064d03df736cbd91a616af2f1b66ab51 https://codeload.github.com/tianon/gosu/tar.gz/29b939aa5bd4dc588af765b8248f6e39150d2565 /src/gosu.tar.gz
RUN tar -xzf /src/gosu.tar.gz -C /src --strip-components=1
WORKDIR /src
RUN go mod download && go mod verify \
    && CGO_ENABLED=0 go build -trimpath -buildvcs=false -ldflags='-s -w' -o /out/gosu .

FROM postgres:16-trixie@sha256:07abc3f43c9395b0bdbbab36bc25ddb0e8d83cd4fcafb5fb7d6abe14ff156dd8
# Retain official signed APT sources; reject partial/stale index refreshes.
# Release signatures and package hashes remain mandatory (no trusted=yes override).
RUN apt-get -o Acquire::Retries=3 -o APT::Update::Error-Mode=any update \
    && apt-get -o Acquire::Retries=3 upgrade -y \
    && apt-get -o Acquire::Retries=3 install -y --no-install-recommends postgresql-16-cron \
    && rm -rf /var/lib/apt/lists/* \
    && find /usr /bin /sbin -xdev -type f -perm /6000 -exec chmod a-s {} + \
    && rm -f /usr/bin/infocmp
COPY --from=gosu-builder /out/gosu /usr/local/bin/gosu
RUN gosu --version && test "$(gosu postgres id -u)" = 999
COPY --from=items sql /opt/item-sql/
COPY deploy/ai-integration/init-catalog.sh /docker-entrypoint-initdb.d/010-item.sh
