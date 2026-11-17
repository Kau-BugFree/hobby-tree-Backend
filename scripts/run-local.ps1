param(
    [ValidateRange(0, 65535)]
    [int]$ServerPort = 8080
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $projectRoot '.env'
$previousValues = @{}
$scriptExitCode = 0

try {
    if (-not (Test-Path -LiteralPath $envPath)) {
        throw 'Missing .env. Copy .env.example to .env and set DB_PASSWORD.'
    }

    # Parse values as literal text; never execute or expand dotenv contents.
    $lineNumber = 0
    foreach ($line in Get-Content -LiteralPath $envPath -Encoding UTF8) {
        $lineNumber++
        if ($line -match '^\s*(#.*)?$') { continue }
        if ($line -notmatch '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
            throw "Invalid .env entry at line $lineNumber. Expected NAME=value."
        }
        $variableName = $Matches[1]
        $variableValue = $Matches[2].Trim()
        if ($variableValue.StartsWith('"') -or $variableValue.StartsWith("'")) {
            if ($variableValue.Length -lt 2 -or $variableValue[-1] -ne $variableValue[0]) {
                throw "Unclosed quote in .env at line $lineNumber."
            }
            $variableValue = $variableValue.Substring(1, $variableValue.Length - 2)
        }
        if (-not $previousValues.ContainsKey($variableName)) {
            $previousValues[$variableName] = [Environment]::GetEnvironmentVariable($variableName, 'Process')
        }
        [Environment]::SetEnvironmentVariable($variableName, $variableValue, 'Process')
    }

    foreach ($requiredName in @('DB_URL', 'DB_USERNAME', 'DB_PASSWORD', 'KAKAO_APP_ID', 'GOOGLE_CLIENT_ID', 'JWT_SECRET')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($requiredName, 'Process'))) {
            throw "Missing required environment variable: $requiredName"
        }
    }

    Push-Location -LiteralPath $projectRoot
    try {
        Write-Host 'Starting local backend with .env (values are not printed).'
        & '.\gradlew.bat' '--no-daemon' 'bootRun' "--args=--server.port=$ServerPort"
        $scriptExitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }
} finally {
    foreach ($variableName in $previousValues.Keys) {
        [Environment]::SetEnvironmentVariable($variableName, $previousValues[$variableName], 'Process')
    }
}

exit $scriptExitCode
