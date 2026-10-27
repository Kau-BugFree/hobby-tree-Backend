# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk-jammy@sha256:802bb2b19ccbaf2d18fbcdc0415d6eab4ef6f68b1b1a7ee8943ee4693b5debfd AS build
WORKDIR /workspace
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
COPY src ./src
RUN chmod +x gradlew \
    && ./gradlew --no-daemon --max-workers=2 bootJar -x test \
    && cp build/libs/*.jar /tmp/hobbytree.jar

FROM eclipse-temurin:21-jre-jammy@sha256:3338ccbb726990839ce18c0e77caab082a34175fd11cc78bb860a4187f10bf38 AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates python3 \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 hobbytree \
    && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin hobbytree
WORKDIR /app
COPY --from=build --chown=10001:10001 /tmp/hobbytree.jar /app/hobbytree.jar
COPY --chmod=0555 scripts/docker-entrypoint.sh /app/docker-entrypoint.sh
COPY --chmod=0444 scripts/validate-ec2-config.py /app/validate-ec2-config.py
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD curl --fail --silent --output /dev/null --max-time 4 http://127.0.0.1:8080/actuator/health || exit 1
ENTRYPOINT ["/app/docker-entrypoint.sh"]
