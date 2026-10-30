#!/bin/sh
set -eu
# --askpass consumes stdin; no password in argv or process/container environment.
redis-cli --no-auth-warning --askpass ping < /run/secrets/redis/REDIS_PASSWORD 2>/dev/null | grep -qx PONG
