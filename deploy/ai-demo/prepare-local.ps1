param(
    [Parameter(Mandatory)][string]$AiRepository,
    [Parameter(Mandatory)][string]$Assets,
    [Parameter(Mandatory)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
$revision = '9364bface2638fb62c54edef6782ea58d5d73df9'
$sourceRepo = (Resolve-Path -LiteralPath $AiRepository).Path
$assetRoot = (Resolve-Path -LiteralPath $Assets).Path
$probeRoot = $PSScriptRoot
foreach ($relative in @('models/embedding/BGE-m3-ko', 'models/reranker/KURE-Reranker-nano', 'backend/var/index/retrieval.sqlite')) {
    if (-not (Test-Path -LiteralPath (Join-Path $assetRoot $relative))) { throw "Missing asset: $relative" }
}
if (Test-Path -LiteralPath $OutputDirectory) { throw 'OutputDirectory exists; preserve it and choose a new path.' }
git -C $sourceRepo cat-file -e "$($revision)^{commit}"
if ($LASTEXITCODE -ne 0) { throw 'Fetch the pinned AI commit before running this script.' }
New-Item -ItemType Directory -Path $OutputDirectory | Out-Null
$runRoot = (Resolve-Path -LiteralPath $OutputDirectory).Path
New-Item -ItemType Directory -Path "$runRoot/source", "$runRoot/state" | Out-Null
git -C $sourceRepo -c core.autocrlf=false archive --format=tar "--output=$runRoot/source.tar" $revision src/activity-coach/backend src/hobby-recommender
if ($LASTEXITCODE -ne 0) { throw 'Git source export failed.' }
tar -xf "$runRoot/source.tar" -C "$runRoot/source"
if ($LASTEXITCODE -ne 0) { throw 'Git source extraction failed.' }
Copy-Item -LiteralPath "$assetRoot/backend/var/index" -Destination "$runRoot/state/index" -Recurse
docker run --rm --pull=never --network none --read-only `
    --mount "type=bind,source=$probeRoot,target=/probe,readonly" `
    --mount "type=bind,source=$runRoot/source/src/activity-coach/backend/data,target=/data" `
    --mount "type=bind,source=$assetRoot/backend/var/index,target=/index,readonly" `
    python:3.12-slim python /probe/prepare-catalog.py --data /data --index /index
if ($LASTEXITCODE -ne 0) { throw 'Catalog/index mismatch. Do not start the server.' }
docker build --target cpu -t hobbytree/coach:9364bfa-cpu-local -f "$probeRoot/coach.Dockerfile" "$runRoot/source/src/activity-coach/backend"
if ($LASTEXITCODE -ne 0) { throw 'Coach image build failed.' }
docker build -t hobbytree/recommender:9364bfa-local "$runRoot/source/src/hobby-recommender"
if ($LASTEXITCODE -ne 0) { throw 'Recommender image build failed.' }
$randomBytes = [byte[]]::new(48)
[System.Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
$tokenPath = "$runRoot/coach-service-token.local"
$tokenBytes = [Text.Encoding]::UTF8.GetBytes([Convert]::ToBase64String($randomBytes))
$tokenOptions = [IO.FileStreamOptions]::new()
$tokenOptions.Mode = [IO.FileMode]::CreateNew
$tokenOptions.Access = [IO.FileAccess]::Write
$tokenOptions.Share = [IO.FileShare]::None
if ($IsLinux) {
    $tokenOptions.UnixCreateMode = [IO.UnixFileMode]::UserRead -bor [IO.UnixFileMode]::UserWrite
}
$tokenStream = [IO.File]::Open($tokenPath, $tokenOptions)
try { $tokenStream.Write($tokenBytes) } finally { $tokenStream.Dispose() }
if ($IsLinux) {
    Write-Output 'Before Compose startup, apply the README Linux UID 10001 permissions to the state and token.'
}
Write-Output 'Local images and disposable state are prepared. No server was deployed.'
Write-Output 'Configure AI_COACH_STATE_DIR, AI_MODELS_DIR and AI_SERVICE_TOKEN_FILE for compose.local.yml.'
