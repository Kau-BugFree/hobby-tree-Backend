#!/bin/bash
set -euo pipefail
password=$(openssl rand -hex 32)
echo "::add-mask::$password"
echo "MIGRATION_TEST_PASSWORD=$password" >> "$GITHUB_ENV"
umask 077
printf 'POSTGRES_PASSWORD=%s\nPOSTGRES_DB=hobbytree_migration_ci\n' "$password" > "$RUNNER_TEMP/hobbytree-migration.env"
image=$(python3 -c "import json; print(next(x for x in json.load(open('deploy/base-image-lock.json')) if x['Repository']=='library/postgres')['IndexDigest'])")
docker run --detach --name hobbytree-migration-ci \
  --env-file "$RUNNER_TEMP/hobbytree-migration.env" \
  --publish 127.0.0.1:15432:5432 \
  --tmpfs /var/lib/postgresql/data:rw,size=256m --memory 512m --cpus 1 \
  "postgres@$image" >/dev/null
for _ in $(seq 1 60); do
  if docker exec hobbytree-migration-ci pg_isready -h 127.0.0.1 -U postgres -d hobbytree_migration_ci --quiet; then
    exit 0
  fi
  sleep 1
done
exit 1
