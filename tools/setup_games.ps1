<#
.SYNOPSIS
Sets up one Gen1Recomp install per world space from the ROM files in local\rom-inbox and imports each with
Gen1Recomp's own importer (docs/specs/MULTI_GAME_PLAN.md §4.11, MG4).

.DESCRIPTION
For each file in local\rom-inbox\ (any name; a .zip or .7z is handed to the importer as it is):
  1. a fresh portable install is made from the pinned Gen1Recomp release (tools\installer\pins.json: the official
     GitHub release zip, its size and SHA-256 checked before unpacking; never gen1recomp[.]com): the files of the zip's
     gen1recomp-win64\ folder, an empty portable.txt, and mods\gen1-bridge as a junction to this repository's
     gen1-bridge; nothing else is copied;
  2. Gen1Recomp's importer runs headless on the file (POKEPORT_IMPORT_ONLY=1, POKEPORT_IMPORT_ROM=<path>,
     POKEPORT_FORCE_IMPORT=1, POKEPORT_CONSOLE=1) and quits by itself;
  3. the game is read from the folder the importer made (<game>\rom-cache.complete: folder names and the marker's
     presence only); its world space is the game catalogue's region (protocol\games.json): red/blue/yellow -> kanto
     (local\gen1recomp, today's install), gold/silver/crystal -> johto, firered/leafgreen -> kanto_frlg,
     ruby/sapphire/emerald -> hoenn (local\gen1recomp-<space>). The fresh install is renamed to that folder when it is
     free; when the world space already has an install, the importer runs again on the same file INTO it and the fresh
     install is deleted (two versions of one world space share one install: they are never played at once);
  4. the Union Room is switched off in the install (tools\e2e\drivers\kb_union_off.lua through POKEPORT_DRIVER: a
     window opens for a few seconds and closes; every Kanto Bridge install runs with it off);
  5. local\launch.json (version 2: installs{<space>:{dir, games, play}}, ports) and the client's
     config\kantobridge-client.json `links` (one game:port per install, 27151 upwards) are written.

The script never reads, copies, moves, checks or deletes a ROM, and never opens an import's files: you clear the inbox
yourself. Its output names files, game ids, folders and exit codes only. -DryRun prints the plan and changes nothing
(no download either).

.EXAMPLE
.\tools\setup_games.ps1 -DryRun
.\tools\setup_games.ps1
.\tools\setup_games.ps1 -Zip C:\Downloads\gen1recomp-0.3.85-windows.zip
#>
[CmdletBinding()]
param(
	[string] $Inbox = "",
	[string] $Local = "",
	[string] $Zip = "",
	[string] $ConfigDir = "",
	[int] $FirstPort = 27151,
	[int] $TimeoutSec = 900,
	[switch] $NoUnionRoomOff,
	[switch] $DryRun
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

$Repo = Split-Path -Parent $PSScriptRoot
if (-not $Local) { $Local = Join-Path $Repo "local" }
if (-not $Inbox) { $Inbox = Join-Path $Local "rom-inbox" }
if (-not $ConfigDir) { $ConfigDir = Join-Path (Join-Path $Local "minecraft") "config" }
$Downloads = Join-Path $Local "_downloads"
$Marker = "rom-cache.complete"
$PinId = "gen1recomp"
$Pins = Join-Path (Join-Path $PSScriptRoot "installer") "pins.json"
$UnionDriver = Join-Path (Join-Path (Join-Path $PSScriptRoot "e2e") "drivers") "kb_union_off.lua"

# Gen1Recomp's licence (its LICENSE.MD, additional term 1): any front end that starts the game shows this credit.
Write-Host "Based on the Pokemon Gen 1 Recompilation Project by BOIS CLUB GAMES, LLC (https://github.com/bryanthaboi/gen1recomp)"

function Say([string] $text) { Write-Host "setup: $text" }
function Plan([string] $text) { Write-Host "[plan] $text" }
function Fail([string] $text) {
	Write-Host "setup: $text" -ForegroundColor Red
	exit 1
}

# --- the catalogue and the pin ----------------------------------------------------------------------

$Catalogue = Get-Content -LiteralPath (Join-Path (Join-Path $Repo "protocol") "games.json") -Raw | ConvertFrom-Json
$GameIds = @($Catalogue.games.PSObject.Properties | ForEach-Object { $_.Name })
function Get-GameEntry([string] $id) {
	$property = $Catalogue.games.PSObject.Properties[$id]
	if ($property) { return $property.Value }
	return $null
}
function Get-Space([string] $id) { return [string] (Get-GameEntry $id).region }
function Test-Adapter([string] $id) { return [bool] ((Get-GameEntry $id).adapter -eq $true) }
function Get-InstallDir([string] $space) {
	if ($space -eq "kanto") { return Join-Path $Local "gen1recomp" }
	return Join-Path $Local "gen1recomp-$space"
}

$Pin = (Get-Content -LiteralPath $Pins -Raw | ConvertFrom-Json).files | Where-Object { $_.id -eq $PinId } | Select-Object -First 1
if (-not $Pin -or -not $Pin.sha256) { Fail "no complete pin '$PinId' in $Pins" }
if ($Pin.url -notmatch '^https://github\.com/bryanthaboi/gen1recomp/releases/download/') {
	Fail "the pin's URL isn't the official GitHub release ($($Pin.url)); refusing"
}

function Test-PinnedFile([string] $path) {
	# True when the file matches the pin's size and SHA-256 (tools\installer\install.ps1's rule).
	$item = Get-Item -LiteralPath $path
	if ($Pin.size -and ($item.Length -ne [long] $Pin.size)) {
		Say "$($item.Name): size $($item.Length), pinned $($Pin.size)"
		return $false
	}
	$hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
	if ($hash -ne $Pin.sha256) {
		Say "$($item.Name): sha256 $($hash.Substring(0, 16))..., pinned $($Pin.sha256.Substring(0, 16))..."
		return $false
	}
	return $true
}

function Get-ReleaseZip {
	# The pinned release zip: -Zip, else the cached download, else a download from the official release; checked.
	if ($Zip) {
		if (-not (Test-Path -LiteralPath $Zip)) { Fail "no file at -Zip $Zip" }
		if (-not (Test-PinnedFile $Zip)) { Fail "the zip at $Zip isn't the pinned release" }
		return $Zip
	}
	$name = [System.IO.Path]::GetFileName(([uri] $Pin.url).AbsolutePath)
	$cached = Join-Path $Downloads $name
	if ((Test-Path -LiteralPath $cached) -and (Test-PinnedFile $cached)) { return $cached }
	if ($DryRun) {
		Plan "download $($Pin.url) to $cached and check its size ($($Pin.size)) and SHA-256 ($($Pin.sha256.Substring(0, 16))...)"
		return $cached
	}
	New-Item -ItemType Directory -Force -Path $Downloads | Out-Null
	Say "downloading the pinned release $name (official GitHub release)"
	Invoke-WebRequest -Uri $Pin.url -OutFile $cached -UseBasicParsing
	if (-not (Test-PinnedFile $cached)) {
		Remove-Item -LiteralPath $cached -Force
		Fail "the download doesn't match the pin; removed. Check docs/VERSIONS.md and the release's sha256sums.txt"
	}
	return $cached
}

# --- one install ----------------------------------------------------------------------------------------

function Get-ImportedGames([string] $dir) {
	$out = @()
	if (-not (Test-Path -LiteralPath $dir)) { return $out }
	foreach ($id in $GameIds) {
		$folder = Join-Path $dir $id
		if ((Test-Path -LiteralPath $folder -PathType Container) -and (Test-Path -LiteralPath (Join-Path $folder $Marker) -PathType Leaf)) { $out += $id }
	}
	return $out
}

function New-FreshInstall([string] $zip) {
	# local\gen1recomp-new-<n>: the release files, portable.txt and the mod junction; nothing else.
	$n = 1
	while (Test-Path -LiteralPath (Join-Path $Local "gen1recomp-new-$n")) { $n++ }
	$dir = Join-Path $Local "gen1recomp-new-$n"
	if ($DryRun) {
		Plan "make a fresh portable install in $dir from $zip (the zip's gen1recomp-win64\ files, portable.txt, mods\gen1-bridge -> $(Join-Path $Repo 'gen1-bridge'))"
		return $dir
	}
	$temp = Join-Path $Local "_unpack-$n"
	if (Test-Path -LiteralPath $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
	Expand-Archive -LiteralPath $zip -DestinationPath $temp -Force
	$inner = Join-Path $temp "gen1recomp-win64"
	if (-not (Test-Path -LiteralPath (Join-Path $inner "gen1recomp.exe"))) { Fail "the release zip has no gen1recomp-win64\gen1recomp.exe" }
	Move-Item -LiteralPath $inner -Destination $dir
	Remove-Item -LiteralPath $temp -Recurse -Force
	New-Item -ItemType File -Path (Join-Path $dir "portable.txt") | Out-Null
	New-Item -ItemType Directory -Force -Path (Join-Path $dir "mods") | Out-Null
	$link = Join-Path (Join-Path $dir "mods") "gen1-bridge"
	& cmd /c mklink /J "$link" "$(Join-Path $Repo 'gen1-bridge')" | Out-Null
	if (-not (Test-Path -LiteralPath $link)) { Fail "couldn't make the junction $link" }
	Say "made $dir"
	return $dir
}

function Invoke-Importer([string] $dir, [System.IO.FileInfo] $rom) {
	# Gen1Recomp's own headless import; this script never touches the file itself. Exit code only.
	$exe = Join-Path $dir "gen1recomp.exe"
	if ($DryRun) {
		Plan "import $($rom.Name) into $dir with Gen1Recomp's importer (POKEPORT_IMPORT_ONLY=1 POKEPORT_IMPORT_ROM=<the file> POKEPORT_FORCE_IMPORT=1 POKEPORT_CONSOLE=1), wait for it to quit"
		return 0
	}
	$env:POKEPORT_IMPORT_ONLY = "1"
	$env:POKEPORT_FORCE_IMPORT = "1"
	$env:POKEPORT_IMPORT_ROM = $rom.FullName
	$env:POKEPORT_CONSOLE = "1"
	try {
		Say "importing $($rom.Name) into $(Split-Path -Leaf $dir) (Gen1Recomp's importer; a window may open and close)"
		$process = Start-Process -FilePath $exe -WorkingDirectory $dir -PassThru -WindowStyle Minimized
		if (-not $process.WaitForExit($TimeoutSec * 1000)) {
			$process.Kill()
			Say "the importer didn't finish within $TimeoutSec s for $($rom.Name); stopped"
			return 124
		}
		Say "importer exit code $($process.ExitCode) for $($rom.Name)"
		return $process.ExitCode
	} finally {
		foreach ($name in @("POKEPORT_IMPORT_ONLY", "POKEPORT_FORCE_IMPORT", "POKEPORT_IMPORT_ROM", "POKEPORT_CONSOLE")) {
			Remove-Item -Path "Env:$name" -ErrorAction SilentlyContinue
		}
	}
}

function Set-UnionRoomOff([string] $dir, [string] $game) {
	# The e2e driver writes the setting off through the engine's own options API and quits (ENGINE_UPGRADE_PLAN U3).
	if ($NoUnionRoomOff) { return }
	if ($DryRun) {
		Plan "switch the Union Room off in $dir (gen1recomp.exe --game=$game with POKEPORT_DRIVER=$UnionDriver; it quits by itself)"
		return
	}
	$env:POKEPORT_DRIVER = $UnionDriver
	$env:POKEPORT_CONSOLE = "1"
	try {
		$process = Start-Process -FilePath (Join-Path $dir "gen1recomp.exe") -ArgumentList @("--game=$game") -WorkingDirectory $dir -PassThru -WindowStyle Minimized
		if (-not $process.WaitForExit(120000)) {
			$process.Kill()
			Say "warning: the Union Room driver didn't finish in $dir; switch it off in the launcher (Launcher Options > Union Room > OFF)"
			return
		}
		Say "Union Room off in $(Split-Path -Leaf $dir) (driver exit code $($process.ExitCode))"
	} finally {
		Remove-Item -Path "Env:POKEPORT_DRIVER" -ErrorAction SilentlyContinue
		Remove-Item -Path "Env:POKEPORT_CONSOLE" -ErrorAction SilentlyContinue
	}
}

# --- run ------------------------------------------------------------------------------------------------

if (-not (Test-Path -LiteralPath $Inbox)) { Fail "no inbox at $Inbox (make it and put your ROM files in it)" }
$files = @(Get-ChildItem -LiteralPath $Inbox -File | Where-Object { -not $_.Name.StartsWith(".") } | Sort-Object Name)
if ($files.Count -eq 0) { Fail "the inbox $Inbox is empty" }
Say "inbox: $($files.Count) file(s): $(($files | ForEach-Object { $_.Name }) -join ', ')"

$zip = Get-ReleaseZip
$placed = @()   # @{Space; Dir; Game}
foreach ($rom in $files) {
	$fresh = New-FreshInstall $zip
	$code = Invoke-Importer $fresh $rom
	$games = @(Get-ImportedGames $fresh)
	if ($DryRun) {
		Plan "read the game from $fresh\<game>\$Marker (folder names only), then place the install: local\gen1recomp (kanto) or local\gen1recomp-<space>; re-import into an existing install of that world space and remove the fresh one"
		continue
	}
	if ($code -ne 0 -or $games.Count -eq 0) {
		Say "warning: no completed import for $($rom.Name) (exit code $code); the fresh install $fresh is left for you to look at or remove"
		continue
	}
	$game = [string] $games[0]
	$space = Get-Space $game
	$target = Get-InstallDir $space
	if (Test-Path -LiteralPath $target) {
		Say "$game belongs to the world space $space, which already has an install at $target`: importing there too"
		$again = Invoke-Importer $target $rom
		if ($again -ne 0 -or (@(Get-ImportedGames $target) -notcontains $game)) {
			Say "warning: the import into $target didn't complete (exit code $again); the fresh install $fresh is kept"
			continue
		}
		Remove-Item -LiteralPath $fresh -Recurse -Force
		Say "removed the temporary install $fresh"
	} else {
		Move-Item -LiteralPath $fresh -Destination $target
		Say "placed $game's install at $target ($space)"
	}
	Set-UnionRoomOff $target $game
	$placed += [pscustomobject]@{ Space = $space; Dir = $target; Game = $game }
}

# --- launch.json and the client's links ---------------------------------------------------------------------

$installs = [ordered]@{}
foreach ($dir in @((Get-ChildItem -LiteralPath $Local -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -eq "gen1recomp" -or $_.Name -like "gen1recomp-*" }))) {
	if ($dir.Name -match '^gen1recomp-(new-\d+|e2e.*|p2|linux.*|data.*)$') { continue }   # test, player-2 and Linux folders
	$space = $(if ($dir.Name -eq "gen1recomp") { "kanto" } else { $dir.Name.Substring("gen1recomp-".Length) })
	$held = @(Get-ImportedGames $dir.FullName)
	if ($held.Count -eq 0) { continue }
	$play = $null
	foreach ($g in $held) { if (Test-Adapter $g) { $play = $g; break } }
	if (-not $play) { $play = [string] $held[0] }
	$relative = "local\" + $dir.Name
	$installs[$space] = [ordered]@{ dir = $relative; games = @($held); play = $play }
}
# kanto first, then the others in name order
$ordered = [ordered]@{}
if ($installs.Contains("kanto")) { $ordered["kanto"] = $installs["kanto"] }
foreach ($key in ($installs.Keys | Where-Object { $_ -ne "kanto" } | Sort-Object)) { $ordered[$key] = $installs[$key] }

$launchFile = Join-Path $Local "launch.json"
$launch = [ordered]@{}
if (Test-Path -LiteralPath $launchFile) {
	$existing = Get-Content -LiteralPath $launchFile -Raw | ConvertFrom-Json
	foreach ($property in $existing.PSObject.Properties) {
		if ($property.Name -notin @("installs", "ports", "game", "gen1recomp", "port")) { $launch[$property.Name] = $property.Value }
	}
}
$launch["installs"] = $ordered
$launch["ports"] = [ordered]@{ first = $FirstPort; player2 = 10 }
$links = @()
$i = 0
foreach ($key in $ordered.Keys) {
	$links += [ordered]@{ game = $ordered[$key].play; port = ($FirstPort + $i) }
	$i++
}
if ($DryRun) {
	Plan "write $launchFile (version 2: installs per world space, ports) and $(Join-Path $ConfigDir 'kantobridge-client.json') links: $(($links | ForEach-Object { "$($_.game):$($_.port)" }) -join ',')"
	Plan "done (the inbox is yours to clear)"
	exit 0
}
if ($ordered.Count -eq 0) {
	Say "no install with a completed import found under $Local; launch.json and the client's links left as they were"
	exit 0
}
($launch | ConvertTo-Json -Depth 6) | Set-Content -LiteralPath $launchFile -Encoding UTF8
Say "wrote $launchFile ($($ordered.Count) install(s))"

$clientFile = Join-Path $ConfigDir "kantobridge-client.json"
$client = [ordered]@{}
if (Test-Path -LiteralPath $clientFile) {
	$existing = Get-Content -LiteralPath $clientFile -Raw | ConvertFrom-Json
	foreach ($property in $existing.PSObject.Properties) {
		if ($property.Name -notin @("port", "links")) { $client[$property.Name] = $property.Value }
	}
}
$client["links"] = $links
if (-not $client.Contains("shapes")) { $client["shapes"] = "on" }
New-Item -ItemType Directory -Force -Path $ConfigDir | Out-Null
($client | ConvertTo-Json -Depth 6) | Set-Content -LiteralPath $clientFile -Encoding UTF8
Say "wrote $clientFile links: $(($links | ForEach-Object { "$($_.game):$($_.port)" }) -join ',')"
Say "done. Clear $Inbox yourself when you no longer need the files there; .\tools\launch.ps1 starts every install with an adapter"
exit 0
