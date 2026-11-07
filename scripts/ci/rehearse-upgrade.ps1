param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
$fixtureKeys = @('POSTGRES_PASSWORD','POSTGRES_DB','MIGRATION_INTEGRATION_TEST','MIGRATION_TEST_JDBC_URL','MIGRATION_TEST_USERNAME','MIGRATION_TEST_PASSWORD','RELEASE_MIGRATION_HISTORY','DB_URL','DB_USERNAME','DB_PASSWORD','REDIS_HOST','REDIS_PORT','JWT_SECRET','KAKAO_APP_ID','GOOGLE_CLIENT_ID','AUTH_TRUSTED_PROXY_CIDRS','AUTH_INTEGRATION_TEST','SPRING_FLYWAY_ENABLED','SPRING_JPA_SHOW_SQL','SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE')
$priorEnvironment = @{}
foreach ($key in $fixtureKeys) { $priorEnvironment[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
$repo = (Resolve-Path (Join-Path $PSScriptRoot ../..)).Path

$evidence = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) } else { Join-Path $repo build/upgrade-rehearsal }
New-Item -ItemType Directory -Force $evidence | Out-Null
Set-Location $repo
$pg = 'hobbytree-readiness-pg-' + [guid]::NewGuid().ToString('N').Substring(0,10)
$redis = 'hobbytree-readiness-redis-' + [guid]::NewGuid().ToString('N').Substring(0,10)
$env:POSTGRES_PASSWORD = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
$env:POSTGRES_DB = 'hobbytree_migration_ci'
try {
  $lock = Get-Content deploy/base-image-lock.json -Raw | ConvertFrom-Json
  $pgImage = 'postgres@' + ($lock | Where-Object Repository -eq 'library/postgres').IndexDigest
  $redisImage = 'redis@' + ($lock | Where-Object Repository -eq 'library/redis').IndexDigest
  docker run -d --name $pg --platform linux/amd64 -e POSTGRES_PASSWORD -e POSTGRES_DB -p 127.0.0.1::5432 --tmpfs /var/lib/postgresql/data:rw,size=768m --memory 1g $pgImage | Out-Null
  if ($LASTEXITCODE) { throw 'Local PostgreSQL start failed' }
  docker run -d --name $redis --platform linux/amd64 -p 127.0.0.1::6379 --memory 256m $redisImage | Out-Null
  if ($LASTEXITCODE) { throw 'Local Redis start failed' }
  $ready = $false
  for ($attempt=0; $attempt -lt 45; $attempt++) {
    docker exec $pg pg_isready -U postgres -d hobbytree_migration_ci --quiet 2>$null
    if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    Start-Sleep -Seconds 1
  }
  if (!$ready) { throw 'Local PostgreSQL not ready' }
  $pgPort = (docker port $pg 5432/tcp).Split(':')[-1]
  $redisPort = (docker port $redis 6379/tcp).Split(':')[-1]
  $env:MIGRATION_INTEGRATION_TEST = 'true'
  $env:MIGRATION_TEST_JDBC_URL = "jdbc:postgresql://127.0.0.1:$pgPort/hobbytree_migration_ci"
  $env:MIGRATION_TEST_USERNAME = 'postgres'
  $env:MIGRATION_TEST_PASSWORD = $env:POSTGRES_PASSWORD
  $env:RELEASE_MIGRATION_HISTORY = Join-Path $evidence 'history.tsv'
  $env:DB_URL = "jdbc:postgresql://127.0.0.1:$pgPort/hobbytree_api_rehearsal"
  $env:DB_USERNAME = 'postgres'
  $env:DB_PASSWORD = $env:POSTGRES_PASSWORD
  $env:REDIS_HOST = '127.0.0.1'
  $env:REDIS_PORT = $redisPort
  $env:JWT_SECRET = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(64))
  $env:KAKAO_APP_ID = '1'
  $env:GOOGLE_CLIENT_ID = 'ci.apps.googleusercontent.com'
  $env:AUTH_TRUSTED_PROXY_CIDRS = '192.0.2.10/32'
  $env:AUTH_INTEGRATION_TEST = 'true'
  $env:SPRING_FLYWAY_ENABLED = 'false'
  $env:SPRING_JPA_SHOW_SQL = 'false'
  $env:SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE = '5'
  docker exec $pg psql -U postgres -d postgres -v ON_ERROR_STOP=1 -c 'CREATE DATABASE hobbytree_api_rehearsal' | Out-Null
  & ./gradlew.bat --offline --no-daemon --max-workers=2 spotlessCheck prepareUpgradeRehearsal *> (Join-Path $evidence 'setup-final.log')
  if ($LASTEXITCODE) { throw 'Schema rehearsal setup failed; see setup.log' }
  & ./gradlew.bat --offline --no-daemon --max-workers=2 test spotlessCheck bootJar *> (Join-Path $evidence 'tests-final.log')
  $testCode = $LASTEXITCODE
  New-Item -ItemType Directory -Force (Join-Path $evidence 'junit-final') | Out-Null
  Copy-Item build/test-results/test/TEST-*.xml (Join-Path $evidence 'junit-final') -Force
  & py -3 scripts/ci/verify-migration-test-results.py
  if ($LASTEXITCODE -or $testCode) { throw 'Tests failed; see junit reports and tests.log' }
  Write-Output 'LOCAL_REHEARSAL_PASSED'
} finally {
  docker rm -f $pg $redis 2>$null | Out-Null
  foreach ($key in $fixtureKeys) { [Environment]::SetEnvironmentVariable($key, $priorEnvironment[$key], 'Process') }
}
