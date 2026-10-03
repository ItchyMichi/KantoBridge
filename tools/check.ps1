# One entry point for every automated test (docs/PLAN.md section 5):
#   1. pytest: protocol schemas, kb_protocol, gen1-bridge Lua under lupa, stand-ins;
#   2. Gradle build in fabric-bridge: JUnit and server GameTests.
#
# Usage (PowerShell): .\tools\check.ps1 [-E2E] [-NoGradle] [extra pytest args]
#   -E2E       also run end-to-end tests on local\gen1recomp-e2e (a Gen1Recomp window opens)
#   -NoGradle  skip the Gradle build
# Gradle runs on JDK 21 from $env:KB_JAVA_HOME (default: the Temurin 21 path in CLAUDE.md); the
# machine's default Java is left alone.
param(
    [switch]$E2E,
    [switch]$NoGradle,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$PytestArgs
)
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot
$root = Split-Path $here -Parent
$py = Join-Path $here '.venv\Scripts\python.exe'
if (-not (Test-Path $py)) {
    Write-Error 'check: tools\.venv is missing; run .\tools\setup.ps1 first'
    exit 2
}

$env:PYTHONUTF8 = '1'
$pytestArgv = @('-m', 'pytest', '-c', 'pytest.ini', '--rootdir', '.')
if ($E2E) { $pytestArgv += '--e2e' }
if ($PytestArgs) { $pytestArgv += $PytestArgs }

Write-Host '== pytest'
Push-Location $here
try {
    & $py @pytestArgv
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally { Pop-Location }

if (-not $NoGradle) {
    Write-Host '== gradle build (fabric-bridge)'
    $javaHome = if ($env:KB_JAVA_HOME) { $env:KB_JAVA_HOME } else { 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot' }
    $oldJavaHome = $env:JAVA_HOME
    Push-Location (Join-Path $root 'fabric-bridge')
    try {
        $env:JAVA_HOME = $javaHome
        & .\gradlew.bat build
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    } finally {
        $env:JAVA_HOME = $oldJavaHome
        Pop-Location
    }
}
Write-Host 'check: OK'
