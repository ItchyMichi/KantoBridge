# Build an isolated Minecraft gallery and its sprite/front/side comparison sheets.
param(
    [ValidateSet('blue', 'emerald', 'crystal')][string]$Game = 'blue',
    [ValidateSet('pallet-trees', 'littleroot-trees', 'buildings', 'all')][string]$Subset = 'pallet-trees',
    [string[]]$Only,
    [string]$Map,
    [switch]$Angled,
    [switch]$North,
    [string]$OutDir,
    [string]$Bundle,
    [string]$BaselineTable,
    [string]$Python,
    [string]$JavaHome,
    [switch]$PrepareOnly,
    [switch]$SheetsOnly,
    [switch]$KeepOpen,
    [switch]$View,
    [ValidateRange(2, 200)][int]$SettleTicks = 20
)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$selectionName = if ($Only) { 'selection' } else { $Subset }
if ($Game -ne 'blue') { $selectionName = "$Game-$selectionName" }
if (-not $OutDir) { $OutDir = Join-Path $repoRoot "local/model-gallery/$selectionName" }
if (-not $Bundle) { $Bundle = Join-Path $repoRoot "local/minecraft/kantobridge/export/$Game" }
$OutDir = [IO.Path]::GetFullPath($OutDir)
$localRoot = [IO.Path]::GetFullPath((Join-Path $repoRoot 'local')) + [IO.Path]::DirectorySeparatorChar
if (-not $OutDir.StartsWith($localRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Generated gallery art must stay inside this repository''s ignored local/ directory.'
}

# The development venv may refer to a removed interpreter. Probe candidates
# rather than changing Python installations or the user's PATH.
$pythonCandidates = @($Python, $env:KB_PYTHON, (Join-Path $PSScriptRoot '.venv/Scripts/python.exe'))
$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
if ($pythonCommand) { $pythonCandidates += $pythonCommand.Source }
$pythonCandidates += Join-Path $env:USERPROFILE '.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe'
$workingPython = $null
foreach ($candidate in $pythonCandidates) {
    if (-not $candidate -or -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
    & $candidate -c 'from PIL import Image' 2>$null
    if ($LASTEXITCODE -eq 0) { $workingPython = $candidate; break }
}
if (-not $workingPython) { throw 'Python with Pillow is required. Pass -Python <python.exe> or set KB_PYTHON.' }

$manifest = Join-Path $OutDir 'manifest.json'
if ($View -and -not (Test-Path -LiteralPath $manifest)) { throw 'Prepare this gallery first before using -View.' }
if (-not $SheetsOnly -and -not $View) {
    $catalogSubset = if ($Only -and $Subset -ne 'buildings') { 'all' } else { $Subset }
    $gameLabel = (Get-Culture).TextInfo.ToTitleCase($Game)
    # Each evidence directory gets its own scratch world. A later batch must
    # not inherit another selection's chunk-unload queue or unsaved placements.
    $galleryWorldKey = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($OutDir))).Substring(0, 8).ToLowerInvariant()
    $prepareArgs = @((Join-Path $PSScriptRoot 'model_gallery.py'), 'prepare', '--game', $Game, '--bundle', $Bundle,
        '--subset', $catalogSubset, '--out', $OutDir, '--world', "$gameLabel Gallery $selectionName $galleryWorldKey")
    foreach ($selector in $Only) { $prepareArgs += @('--only', $selector) }
    if ($Map) { $prepareArgs += @('--map', $Map) }
    if ($Angled) { $prepareArgs += '--angled' }
    if ($North) { $prepareArgs += '--north' }
    if ($BaselineTable) { $prepareArgs += @('--baseline-table', $BaselineTable) }
    & $workingPython @prepareArgs
    if ($LASTEXITCODE -ne 0) { throw 'Gallery preparation failed.' }
}
if ($PrepareOnly) {
    Write-Host "Gallery prepared: $manifest"
    return
}

if (-not $SheetsOnly) {
    if (-not $JavaHome) {
        $JavaHome = if ($env:KB_JAVA_HOME) { $env:KB_JAVA_HOME } else { 'C:/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot' }
    }
    if (-not (Test-Path (Join-Path $JavaHome 'bin/java.exe'))) { throw 'JDK 21 is required. Pass -JavaHome or set KB_JAVA_HOME.' }
    $oldJavaHome = $env:JAVA_HOME
    $clientDir = Join-Path $repoRoot 'local/model-gallery-client'
    New-Item -ItemType Directory -Force -Path $clientDir | Out-Null
    # Cobblemon's own spawner runs even in peaceful mode. This setting belongs
    # only to the isolated gallery client, never the user's gameplay instance.
    $cobblemonConfig = Join-Path $clientDir 'config/cobblemon/main.json'
    New-Item -ItemType Directory -Force -Path (Split-Path $cobblemonConfig -Parent) | Out-Null
    $galleryConfig = if (Test-Path -LiteralPath $cobblemonConfig) {
        Get-Content -LiteralPath $cobblemonConfig -Raw | ConvertFrom-Json
    } else { [pscustomobject]@{} }
    $galleryConfig | Add-Member -NotePropertyName enableSpawning -NotePropertyValue $false -Force
    [IO.File]::WriteAllText($cobblemonConfig, ($galleryConfig | ConvertTo-Json -Depth 100), [Text.UTF8Encoding]::new($false))
    $optionsFile = Join-Path $clientDir 'options.txt'
    if (-not (Test-Path -LiteralPath $optionsFile)) {
        # This belongs only to our fresh isolated client. Skip its welcome
        # dialog so unattended capture reaches the title screen immediately.
        [IO.File]::WriteAllText($optionsFile, "onboardAccessibility:false`ntutorialStep:none`n")
    }
    Push-Location (Join-Path $repoRoot 'fabric-bridge')
    try {
        $env:JAVA_HOME = $JavaHome
        $clientArgs = @('runClient', "-Pkb.gallery=$manifest",
            "-Pkb.clientRunDir=$clientDir",
            '-Pkb.clientHeap=4g', "-Pkb.gallerySettleTicks=$SettleTicks",
            "-Pkb.galleryCapture=$((-not $View).ToString().ToLowerInvariant())",
            "-Pkb.galleryQuit=$((-not ($KeepOpen -or $View)).ToString().ToLowerInvariant())", '--console=plain')
        & .\gradlew.bat @clientArgs
        if ($LASTEXITCODE -ne 0) { throw 'Minecraft gallery capture failed. Inspect the isolated client logs.' }
    } finally {
        $env:JAVA_HOME = $oldJavaHome
        Pop-Location
    }
}
if ($View) { return }
& $workingPython (Join-Path $PSScriptRoot 'model_gallery.py') sheets --manifest $manifest
if ($LASTEXITCODE -ne 0) { throw 'Some gallery captures are missing or stale; see sheet-report.json.' }
Write-Host "Comparison sheets: $(Join-Path $OutDir 'index.html')"
