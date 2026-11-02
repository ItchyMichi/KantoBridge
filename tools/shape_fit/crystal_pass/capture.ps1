# Capture a prepared Crystal review batch (crystal_pass/batch.py) in the isolated gallery client, then
# compose and verify its sheets. Same client arguments as tools/model_gallery.ps1.
#   .\tools\shape_fit\crystal_pass\capture.ps1 -Name batch-01 [-Buildings]
param(
    [Parameter(Mandatory)][string]$Name,
    [switch]$Buildings,
    [string]$JavaHome = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot',
    [ValidateRange(2, 200)][int]$SettleTicks = 20,
    [switch]$View
)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path
$folder = if ($Buildings) { "$Name-buildings" } else { $Name }
$out = Join-Path $repo "local\model-gallery\crystal-review\$folder"
$manifest = Join-Path $out 'manifest.json'
if (-not (Test-Path -LiteralPath $manifest)) { throw "Prepare the batch first: $manifest" }
$clientDir = Join-Path $repo 'local\model-gallery-client'
$log = Join-Path $repo "local\model-gallery\crystal-review\$folder-$(if ($View) {'view'} else {'capture'}).log"
Push-Location (Join-Path $repo 'fabric-bridge')
$old = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $JavaHome
    $capture = (-not $View).ToString().ToLowerInvariant()
    & .\gradlew.bat runClient "-Pkb.gallery=$manifest" "-Pkb.clientRunDir=$clientDir" '-Pkb.clientHeap=4g' `
        "-Pkb.gallerySettleTicks=$SettleTicks" "-Pkb.galleryCapture=$capture" "-Pkb.galleryQuit=$capture" '--console=plain' *> $log
    if ($LASTEXITCODE -ne 0) { throw "Minecraft gallery run failed; see $log" }
} finally {
    $env:JAVA_HOME = $old
    Pop-Location
}
if ($View) { return }
& python (Join-Path $repo 'tools\model_gallery.py') sheets --manifest $manifest
if ($LASTEXITCODE -ne 0) { throw 'Some captures are missing or stale; see sheet-report.json.' }
