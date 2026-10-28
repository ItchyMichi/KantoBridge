<#
.SYNOPSIS
Starts Kanto Bridge: the hidden Gen1Recomp games (visible windows), waits for their bridge ports, then Minecraft.

.DESCRIPTION
Dev mode (default) runs this repository's dev setup:
  - one player:  one Gen1Recomp process per install (MG4: one hidden process per game, each on its own
                 port: 27151, 27152, ...), then `gradlew runClient -Pkb.links=blue:27151,emerald:27152`;
  - -Players 2:  the same, plus player 2's own Gen1Recomp install (local\gen1recomp-p2) on port 27151 + 10,
                 the dedicated dev server (`gradlew runServer`, online-mode=false), then `runClient` and
                 `runClient2`.
Player mode (-Mode player) runs an installed setup laid out as in docs/INSTALLER.md: Gen1Recomp from
<Root>\gen1recomp and Minecraft through the bundled Prism Launcher instance.

Which games start (docs/specs/MULTI_GAME_PLAN.md §4.11): by default every install of the config whose game has an
adapter in this bridge (protocol/games.json); -Games picks games (their installs are found by detection), -Regions
picks installs (world spaces: kanto, hoenn, ...). Detection reads an install's folder names and the presence of each
game folder's `rom-cache.complete` marker, nothing more (tools/kb_games.py does the same in Python).

Settings come from the parameters, then a JSON config (dev: local\launch.json; player: <Root>\launch.json), then the
defaults. Config (version 2): installs{<space>:{dir, games[], play}}, ports{first, player2}, javaHome, slot, root,
instance; the older keys game, gen1recomp, gen1recompP2, port are still read as the kanto install.

The script never touches Gen1Recomp's data (saves, ROM import, options): it only starts the program.
It sets JAVA_HOME for its own Gradle runs only, never system-wide. It never stops Gen1Recomp, so
unsaved progress is never lost; with -Watch it restarts a Gen1Recomp that exits while Minecraft runs.

-Engines (WORLD_SESSIONS_PLAN WS7, D8): "launcher" (default this release) starts the games as above; "minecraft"
starts Minecraft only and hands the games to the mod, which starts each game a world lists when the world opens and
stops the ones it started after their save at "Save and Quit" (KANTOBRIDGE_ENGINES=minecraft and the config's path reach
the client; -Pkb.engines in dev). -Watch is a launcher-mode feature; -Engines minecraft takes one player.

.EXAMPLE
.\tools\launch.ps1
.\tools\launch.ps1 -Game red -Watch
.\tools\launch.ps1 -Games blue,emerald
.\tools\launch.ps1 -Regions kanto,hoenn
.\tools\launch.ps1 -Players 2
.\tools\launch.ps1 -Mode player
.\tools\launch.ps1 -DryRun
.\tools\launch.ps1 -Engines minecraft
#>
[CmdletBinding()]
param(
	[ValidateSet("dev", "player")] [string] $Mode = "dev",
	[ValidateSet(1, 2)] [int] $Players = 1,
	[ValidateSet("launcher", "minecraft")] [string] $Engines = "launcher",
	[string] $Game = "",
	[string[]] $Games = @(),
	[string[]] $Regions = @(),
	[string] $Gen1Recomp = "",
	[string] $P2Gen1Recomp = "",
	[int] $Port = 0,
	[string] $JavaHome = "",
	[string] $Slot = "",
	[string] $Root = "",
	[string] $Instance = "",
	[string] $Config = "",
	[string] $MinecraftDir = "",
	[switch] $Console,
	[switch] $NoConsole,
	[switch] $Watch,
	[switch] $NoWatch,
	[switch] $DryRun,
	[int] $TimeoutSec = 120,
	# Internal, for tests: wait until something listens on -Port (owned by -ProbePid if given), print it, exit.
	[switch] $Probe,
	[int] $ProbePid = 0
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

$Repo = Split-Path -Parent $PSScriptRoot
$PortSpan = 9                  # gen1-bridge takes the first free of the configured port and the next 8
$ServerPort = 25565
$RestartLimit = 3              # -Watch: at most this many restarts per process ...
$RestartWindowSec = 600        # ... within this many seconds
$Marker = "rom-cache.complete" # Gen1Recomp's completion marker inside a game's import folder (names only)
$DefaultGame = "blue"          # when an install shows no completed import yet (its launcher asks for the ROM)

# Gen1Recomp's licence (its LICENSE.MD, additional term 1) asks any front end that starts the game to show this credit.
Write-Host "Based on the Pokemon Gen 1 Recompilation Project by BOIS CLUB GAMES, LLC (https://github.com/bryanthaboi/gen1recomp)"

function Say([string] $text) { Write-Host "launch: $text" }
function Plan([string] $text) { Write-Host "[plan] $text" }
function Fail([string] $text) {
	Write-Host "launch: $text" -ForegroundColor Red
	exit 1
}

# --- listening sockets ---------------------------------------------------------------------------

function Get-Listeners([int] $owner) {
	# Local TCP listeners as @{Port; Pid}; Get-NetTCPConnection where it exists, else netstat.
	$out = @()
	$cmd = Get-Command Get-NetTCPConnection -ErrorAction SilentlyContinue
	if ($cmd) {
		$rows = @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue)
		foreach ($row in $rows) {
			$out += [pscustomobject]@{ Port = [int] $row.LocalPort; Pid = [int] $row.OwningProcess }
		}
	} else {
		foreach ($line in (netstat -ano -p tcp)) {
			if ($line -match '^\s*TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)') {
				$out += [pscustomobject]@{ Port = [int] $Matches[1]; Pid = [int] $Matches[2] }
			}
		}
	}
	if ($owner -gt 0) {
		$out = @($out | Where-Object { $_.Pid -eq $owner })
	}
	return $out
}

function Wait-Listener([int] $first, [int] $span, [int] $owner, [System.Diagnostics.Process] $process, [int] $timeout) {
	# The first port in [first, first + span) that `owner` (or anyone, when 0) listens on; 0 on timeout.
	$deadline = (Get-Date).AddSeconds($timeout)
	while ((Get-Date) -lt $deadline) {
		$ports = @(Get-Listeners $owner | ForEach-Object { $_.Port } | Where-Object { $_ -ge $first -and $_ -lt ($first + $span) } | Sort-Object -Unique)
		if ($ports.Count -gt 0) {
			return [int] $ports[0]
		}
		if ($process -and $process.HasExited) {
			return -1
		}
		Start-Sleep -Milliseconds 500
	}
	return 0
}

if ($Probe) {
	$found = Wait-Listener $Port 1 $ProbePid $null $TimeoutSec
	if ($found -gt 0) {
		Write-Host "listening $found"
		exit 0
	}
	Write-Host "timeout"
	exit 1
}

# --- the game catalogue (protocol/games.json: ids, adapters, world spaces) ---------------------------

$CataloguePath = Join-Path (Join-Path $Repo "protocol") "games.json"
if (-not (Test-Path -LiteralPath $CataloguePath)) {
	Fail "no game catalogue at $CataloguePath (a clone of the repository is needed)"
}
$Catalogue = Get-Content -LiteralPath $CataloguePath -Raw | ConvertFrom-Json
$GameIds = @($Catalogue.games.PSObject.Properties | ForEach-Object { $_.Name })

function Get-GameEntry([string] $id) {
	$property = $Catalogue.games.PSObject.Properties[$id]
	if ($property) { return $property.Value }
	return $null
}
function Test-Adapter([string] $id) {
	$entry = Get-GameEntry $id
	return [bool] ($entry -and $entry.adapter -eq $true)
}

function Get-ImportedGames([string] $dir) {
	# The catalogued games with a completed import in `dir`: a folder named like the game holding the marker.
	# Folder names and the marker's presence only; nothing inside is ever opened (CLAUDE.md).
	$out = @()
	if (-not (Test-Path -LiteralPath $dir)) { return $out }
	foreach ($id in $GameIds) {
		$folder = Join-Path $dir $id
		if ((Test-Path -LiteralPath $folder -PathType Container) -and (Test-Path -LiteralPath (Join-Path $folder $Marker) -PathType Leaf)) {
			$out += $id
		}
	}
	return $out
}

# --- settings --------------------------------------------------------------------------------------

if ($Mode -eq "player") {
	if (-not $Root) {
		$Root = Join-Path $env:LOCALAPPDATA "KantoBridge"
	}
	if (-not $Config) {
		$Config = Join-Path $Root "launch.json"
	}
} elseif (-not $Config) {
	$Config = Join-Path (Join-Path $Repo "local") "launch.json"
}

$Settings = @{}
if (Test-Path -LiteralPath $Config) {
	try {
		$json = Get-Content -LiteralPath $Config -Raw | ConvertFrom-Json
	} catch {
		Fail "can't read the config $Config ($($_.Exception.Message))"
	}
	foreach ($property in $json.PSObject.Properties) {
		$Settings[$property.Name] = $property.Value
	}
}

function Pick([string] $given, [string] $key, [string] $fallback) {
	if ($given) { return $given }
	if ($Settings.ContainsKey($key) -and $Settings[$key]) { return [string] $Settings[$key] }
	return $fallback
}

function Resolve-InstallDir([string] $dir) {
	if ([System.IO.Path]::IsPathRooted($dir)) { return $dir }
	return Join-Path $Repo $dir
}

if ($Game) { $Games = @($Game) + $Games }
$Slot = Pick $Slot "slot" ""

# Ports: ports.first (version 2) or port (version 1), the -Port parameter first; player 2 at first + ports.player2.
$FirstPort = 27151
$Player2Offset = 10
if ($Settings.ContainsKey("ports") -and $Settings["ports"]) {
	$ports = $Settings["ports"]
	if ($ports.PSObject.Properties["first"] -and $ports.first) { $FirstPort = [int] $ports.first }
	if ($ports.PSObject.Properties["player2"] -and $ports.player2) { $Player2Offset = [int] $ports.player2 }
} elseif ($Settings.ContainsKey("port") -and $Settings["port"]) {
	$FirstPort = [int] $Settings["port"]
}
if ($Port -gt 0) { $FirstPort = $Port }

# The installs: @{Space; Dir; Games (declared, or $null for detection); Play (declared, or "")}, in config order.
$Installs = @()
if ($Mode -eq "player") {
	$dir = Pick $Gen1Recomp "gen1recomp" (Join-Path $Root "gen1recomp")
	$Installs += [pscustomobject]@{ Space = "kanto"; Dir = $dir; Games = $null; Play = (Pick "" "game" "") }
	$Instance = Pick $Instance "instance" "KantoBridge"
} else {
	if ($Settings.ContainsKey("installs") -and $Settings["installs"]) {
		foreach ($property in $Settings["installs"].PSObject.Properties) {
			$entry = $property.Value
			$dir = ""
			if ($entry.PSObject.Properties["dir"] -and $entry.dir) { $dir = Resolve-InstallDir ([string] $entry.dir) }
			if (-not $dir) {
				$name = $(if ($property.Name -eq "kanto") { "gen1recomp" } else { "gen1recomp-$($property.Name)" })
				$dir = Join-Path (Join-Path $Repo "local") $name
			}
			$declared = $null
			if ($entry.PSObject.Properties["games"] -and $entry.games) { $declared = @($entry.games | ForEach-Object { [string] $_ }) }
			$play = ""
			if ($entry.PSObject.Properties["play"] -and $entry.play) { $play = [string] $entry.play }
			$Installs += [pscustomobject]@{ Space = $property.Name; Dir = $dir; Games = $declared; Play = $play }
		}
	} else {
		$dir = Pick $Gen1Recomp "gen1recomp" (Join-Path (Join-Path $Repo "local") "gen1recomp")
		$Installs += [pscustomobject]@{ Space = "kanto"; Dir = $dir; Games = $null; Play = (Pick "" "game" "") }
	}
	if ($Gen1Recomp -and $Installs.Count -gt 0) { $Installs[0].Dir = $Gen1Recomp }   # the parameter beats the config's first install
	$P2Gen1Recomp = Pick $P2Gen1Recomp "gen1recompP2" (Join-Path (Join-Path $Repo "local") "gen1recomp-p2")
	if (-not $MinecraftDir) {
		$MinecraftDir = Join-Path (Join-Path $Repo "local") "minecraft"
	}
}
$UseConsole = ($Mode -eq "dev" -and -not $NoConsole) -or $Console
$UseWatch = (($Mode -eq "player" -and -not $NoWatch) -or $Watch) -and $Engines -eq "launcher"
if ($Engines -eq "minecraft" -and $Players -ne 1) {
	Fail "-Engines minecraft takes one player (player 2's games stay the launcher's: drop -Engines or -Players)"
}

function Find-Jdk21 {
	$given = Pick $JavaHome "javaHome" ""
	if ($given) { return $given }
	$candidates = @()
	foreach ($base in @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft")) {
		if (Test-Path -LiteralPath $base) {
			$candidates += @(Get-ChildItem -LiteralPath $base -Directory -Filter "jdk-21*" -ErrorAction SilentlyContinue)
		}
	}
	$best = $candidates | Sort-Object Name -Descending | Select-Object -First 1
	if ($best) { return $best.FullName }
	return ""
}

# --- which games start -----------------------------------------------------------------------------

function Get-InstallGames($install) {
	# The games an install holds: the config's list when given, else what detection finds.
	if ($install.Games) { return @($install.Games) }
	return @(Get-ImportedGames $install.Dir)
}

function Get-PlayGame($install, [string[]] $held) {
	# What this install plays by default: its `play`, else its first game with an adapter, else its first game,
	# else "" (an install before its first import).
	if ($install.Play) { return [string] $install.Play }
	foreach ($g in $held) { if (Test-Adapter $g) { return $g } }
	if ($held.Count -gt 0) { return [string] $held[0] }
	return ""
}

foreach ($g in $Games) {
	if ($g -notin $GameIds) { Fail "unknown game '$g' (protocol/games.json lists: $($GameIds -join ', '))" }
}
$Selected = @()   # @{Space; Dir; Play}, then Who, Port and Exe
if ($Regions.Count -gt 0) {
	foreach ($space in $Regions) {
		$install = @($Installs | Where-Object { $_.Space -eq $space })
		if ($install.Count -eq 0) {
			Fail "no install for region '$space' (installs in $Config`: $(($Installs | ForEach-Object { $_.Space }) -join ', '); tools\setup_games.ps1 adds one)"
		}
		$held = @(Get-InstallGames $install[0])
		$play = Get-PlayGame $install[0] $held
		if (-not $play) {
			Say "warning: no completed import in $($install[0].Dir); starting --game=$DefaultGame anyway (Gen1Recomp's launcher asks for the ROM)"
			$play = $DefaultGame
		}
		$Selected += [pscustomobject]@{ Space = $space; Dir = $install[0].Dir; Play = $play }
	}
} elseif ($Games.Count -gt 0) {
	foreach ($g in $Games) {
		$found = $null
		foreach ($install in $Installs) {
			if (@(Get-InstallGames $install) -contains $g) { $found = $install; break }
		}
		if (-not $found) {
			if ($Installs.Count -eq 1 -and @(Get-InstallGames $Installs[0]).Count -eq 0) {
				# the one install shows no completed import yet: start it with the asked game (the launcher asks for the ROM)
				$found = $Installs[0]
				Say "warning: no completed import in $($found.Dir); starting --game=$g anyway (Gen1Recomp's launcher asks for the ROM)"
			} else {
				Fail "no install holds '$g': import it first (tools\setup_games.ps1 with the ROM in local\rom-inbox), or list it under installs.<space>.games in $Config"
			}
		}
		$same = @($Selected | Where-Object { $_.Dir -eq $found.Dir })
		if ($same.Count -gt 0) {
			Fail "games '$g' and '$($same[0].Play)' live in one install ($($found.Dir)): two processes of one install would share its save slots and options; they are one world space, pick one"
		}
		$Selected += [pscustomobject]@{ Space = $found.Space; Dir = $found.Dir; Play = $g }
	}
} else {
	foreach ($install in $Installs) {
		if ($Mode -eq "dev" -and $install.Space -like "*_p2") { continue }   # a version-1 player-2 install is player 2's
		$held = @(Get-InstallGames $install)
		$play = Get-PlayGame $install $held
		if (-not $play) {
			if ($Installs.Count -eq 1) {
				Say "warning: no completed import in $($install.Dir); starting --game=$DefaultGame anyway (Gen1Recomp's launcher asks for the ROM)"
				$play = $DefaultGame
			} else {
				Say "skipping $($install.Space) ($($install.Dir)): no completed import"
				continue
			}
		}
		if (-not (Test-Adapter $play) -and $Installs.Count -gt 1) {
			Say "skipping $($install.Space) ($play): the bridge has no adapter for it yet (protocol/games.json); -Games $play or -Regions $($install.Space) starts it anyway"
			continue
		}
		$Selected += [pscustomobject]@{ Space = $install.Space; Dir = $install.Dir; Play = $play }
	}
}
if ($Selected.Count -eq 0) {
	Fail "nothing to start: no install holds a game this bridge has an adapter for (installs: $(($Installs | ForEach-Object { $_.Space }) -join ', '))"
}
foreach ($s in $Selected) {
	if (-not (Test-Adapter $s.Play)) {
		Say "warning: the bridge has no adapter for $($s.Play) yet (protocol/games.json): its process starts, but Minecraft can't follow it (hello.reject game)"
	}
}

# --- checks ----------------------------------------------------------------------------------------

function Test-Install([string] $dir, [string] $who) {
	$exe = Join-Path $dir "gen1recomp.exe"
	if (-not (Test-Path -LiteralPath $exe)) {
		Fail "$who`: no gen1recomp.exe in $dir (see README 'Setting up a new machine')"
	}
	if (-not (Test-Path -LiteralPath (Join-Path $dir "portable.txt"))) {
		Say "warning: $who has no portable.txt beside gen1recomp.exe, so its data goes to %APPDATA%"
	}
	if (-not (Test-Path -LiteralPath (Join-Path (Join-Path $dir "mods") "gen1-bridge"))) {
		Fail "$who`: gen1-bridge isn't installed in $dir\mods\gen1-bridge"
	}
	return $exe
}

$index = 0
foreach ($s in $Selected) {
	$who = $(if ($Selected.Count -eq 1) { "Gen1Recomp" } else { "Gen1Recomp ($($s.Space))" })
	$s | Add-Member -NotePropertyName Who -NotePropertyValue $who
	$s | Add-Member -NotePropertyName Port -NotePropertyValue ($FirstPort + $index)
	$s | Add-Member -NotePropertyName Exe -NotePropertyValue (Test-Install $s.Dir $who)
	$index++
}
$P2 = $null
if ($Mode -eq "dev" -and $Players -eq 2) {
	if (-not (Test-Path -LiteralPath $P2Gen1Recomp)) {
		Fail "player 2's Gen1Recomp install $P2Gen1Recomp is missing (docs/PLAYTEST.md M9 'Setup'; -P2Gen1Recomp local\gen1recomp-e2e works for a quick try)"
	}
	foreach ($s in $Selected) {
		if ((Resolve-Path -LiteralPath $s.Dir).Path -eq (Resolve-Path -LiteralPath $P2Gen1Recomp).Path) {
			Fail "player 2 needs a second Gen1Recomp install (two processes of one install would share its save slots)"
		}
	}
	$p2Held = @(Get-ImportedGames $P2Gen1Recomp)
	$p2Play = [string] $Selected[0].Play
	if ($p2Held.Count -gt 0 -and ($p2Held -notcontains $p2Play)) { $p2Play = [string] $p2Held[0] }
	$P2 = [pscustomobject]@{ Space = $Selected[0].Space; Dir = $P2Gen1Recomp; Play = $p2Play; Who = "player 2's Gen1Recomp"; Port = ($FirstPort + $Player2Offset) }
	$P2 | Add-Member -NotePropertyName Exe -NotePropertyValue (Test-Install $P2Gen1Recomp $P2.Who)
}

$Gradle = Join-Path (Join-Path $Repo "fabric-bridge") "gradlew.bat"
$Jdk = ""
$Prism = ""
if ($Mode -eq "dev") {
	if (-not (Test-Path -LiteralPath $Gradle)) {
		Fail "no fabric-bridge\gradlew.bat in $Repo"
	}
	$Jdk = Find-Jdk21
	if (-not $Jdk -or -not (Test-Path -LiteralPath (Join-Path (Join-Path $Jdk "bin") "java.exe"))) {
		Fail "no JDK 21 found: pass -JavaHome <jdk-21 folder> or set javaHome in $Config"
	}
} else {
	$Prism = Join-Path (Join-Path $Root "prism") "prismlauncher.exe"
	if (-not (Test-Path -LiteralPath $Prism)) {
		Fail "no Prism Launcher at $Prism (see docs/INSTALLER.md)"
	}
}

# The client's links (MG4): game:port per hidden process, in the installs' order.
$Links = ($Selected | ForEach-Object { "$($_.Play):$($_.Port)" }) -join ","
$P2Links = $(if ($P2) { "$($P2.Play):$($P2.Port)" } else { "" })

Say "mode $Mode, $Players player(s): $(($Selected | ForEach-Object { "$($_.Space) $($_.Play) on $($_.Port)" }) -join ', ')$(if ($Slot) { ", slot $Slot" })"
if ($P2) { Say "player 2: $($P2.Play) on $($P2.Port)" }

# --- Gen1Recomp --------------------------------------------------------------------------------

function Get-Running([string] $exe) {
	$full = (Resolve-Path -LiteralPath $exe).Path
	foreach ($process in @(Get-Process -Name "gen1recomp" -ErrorAction SilentlyContinue)) {
		try {
			if ($process.Path -and ((Resolve-Path -LiteralPath $process.Path).Path -eq $full)) {
				return $process
			}
		} catch {
		}
	}
	return $null
}

function Start-Gen1($g) {
	$arguments = @("--game=$($g.Play)")
	if ($UseConsole) { $arguments += "--console" }
	if ($Slot) { $arguments += "--slot=$Slot" }
	$folder = Split-Path -Parent $g.Exe
	if ($DryRun) {
		Plan "start $($g.Who)`: $($g.Exe) $($arguments -join ' ') (in $folder, window visible)"
		return $null
	}
	$running = Get-Running $g.Exe
	if ($running) {
		Say "$($g.Who) is already running (pid $($running.Id)); using it"
		return $running
	}
	Say "starting $($g.Who)`: $($arguments -join ' ')"
	return Start-Process -FilePath $g.Exe -ArgumentList $arguments -WorkingDirectory $folder -WindowStyle Normal -PassThru
}

function Wait-Gen1([System.Diagnostics.Process] $process, $g) {
	$expected = [int] $g.Port
	$who = $g.Who
	if ($DryRun) {
		Plan "wait until $who listens on 127.0.0.1:$expected (or the next free of $($PortSpan - 1) after it)"
		return $expected
	}
	Say "waiting for $who's bridge port (keep its window open; the first boot takes a few seconds)"
	$found = Wait-Listener $expected $PortSpan $process.Id $process $TimeoutSec
	if ($found -eq -1) {
		Fail "$who closed before its bridge started (check its window or log; never share its crash log)"
	}
	if ($found -eq 0) {
		Fail "$who didn't open its bridge port within $TimeoutSec s: is the gen1-bridge mod enabled? (Gen1Recomp only starts mods once a game boots)"
	}
	Say "$who is listening on 127.0.0.1:$found"
	if ($found -ne $expected) {
		Say "warning: $who took port $found, but its Minecraft connects to ${expected}: free port $expected, or set BRIDGE PORT in $who's mod settings (F10) to $expected and the client's config\kantobridge-client.json links entry {""game"":""$($g.Play)"",""port"":$found}"
	}
	return $found
}

# --- Minecraft --------------------------------------------------------------------------------------

function Start-Gradle([string] $task, [bool] $ownWindow, [string] $links) {
	# -Pkb.links reaches the dev client as -Dkantobridge.links (fabric-bridge/build.gradle maps it).
	$arguments = @($task)
	if ($links) { $arguments += "-Pkb.links=$links" }
	if ($Engines -eq "minecraft" -and $task -eq "runClient") {
		$arguments += "-Pkb.engines=minecraft"
		if ($Config) { $arguments += "-Pkb.enginesLaunch=$Config" }
	}
	if ($DryRun) {
		Plan "run fabric-bridge\gradlew.bat $($arguments -join ' ') with JAVA_HOME=$Jdk (this run only)$(if ($ownWindow) { ' in its own window' })"
		return $null
	}
	$env:JAVA_HOME = $Jdk   # this process and its children only
	$folder = Split-Path -Parent $Gradle
	Say "starting gradlew $($arguments -join ' ')"
	if ($ownWindow) {
		return Start-Process -FilePath $Gradle -ArgumentList $arguments -WorkingDirectory $folder -PassThru
	}
	return Start-Process -FilePath $Gradle -ArgumentList $arguments -WorkingDirectory $folder -NoNewWindow -PassThru
}

function Set-DevServerProperties {
	$file = Join-Path $MinecraftDir "server.properties"
	if (-not (Test-Path -LiteralPath $file)) {
		Fail "no server.properties in $MinecraftDir`: build the world first (tools\e2e\build_world.py --maps all writes it)"
	}
	$lines = @(Get-Content -LiteralPath $file)
	if ($lines -contains "online-mode=false") {
		return
	}
	if ($DryRun) {
		Plan "set online-mode=false in $file (dev accounts can't sign in to an online-mode server)"
		return
	}
	$lines = @($lines | Where-Object { $_ -notmatch '^online-mode=' }) + "online-mode=false"
	Set-Content -LiteralPath $file -Value $lines -Encoding ASCII
	Say "set online-mode=false in $file (dev accounts can't sign in otherwise)"
}

# --- run -------------------------------------------------------------------------------------------

$gen1 = @()   # @{Exe; Who; Play; Port; Process; Restarts}
foreach ($s in $Selected) {
	$gen1 += [pscustomobject]@{ Exe = $s.Exe; Who = $s.Who; Play = $s.Play; Port = $s.Port; Process = $null; Restarts = @() }
}
if ($P2) {
	$gen1 += [pscustomobject]@{ Exe = $P2.Exe; Who = $P2.Who; Play = $P2.Play; Port = $P2.Port; Process = $null; Restarts = @() }
}

if ($Engines -eq "minecraft") {
	# D8: the mod starts the games a world lists (and stops those it started at Save and Quit)
	if ($DryRun) {
		Plan "engines: Minecraft starts $(($gen1 | ForEach-Object { $_.Play }) -join ', ') when a world lists them (launch.json $Config)"
	} else {
		Say "engines: Minecraft starts the games when a world opens"
		$env:KANTOBRIDGE_ENGINES = "minecraft"   # this process and its children only (Prism, its Minecraft)
		if ($Config) { $env:KANTOBRIDGE_ENGINES_LAUNCH = $Config }
	}
	$gen1 = @()
}
foreach ($g in $gen1) {
	$g.Process = Start-Gen1 $g
	[void] (Wait-Gen1 $g.Process $g)
}
if ($DryRun) { Plan "links: $Links$(if ($P2Links) { " (player 2: $P2Links)" })" }

$minecraft = @()
if ($Mode -eq "player") {
	if ($DryRun) {
		Plan "start Minecraft: $Prism --launch $Instance"
	} else {
		Say "starting Minecraft (Prism Launcher instance $Instance)"
		$minecraft += Start-Process -FilePath $Prism -ArgumentList @("--launch", $Instance) -WorkingDirectory (Split-Path -Parent $Prism) -PassThru
	}
} elseif ($Players -eq 1) {
	$minecraft += Start-Gradle "runClient" $false $Links
} else {
	Set-DevServerProperties
	$alreadyServing = @(Get-Listeners 0 | Where-Object { $_.Port -eq $ServerPort }).Count -gt 0
	if ($alreadyServing -and -not $DryRun) {
		Say "a server is already listening on port $ServerPort; using it"
	} else {
		$server = Start-Gradle "runServer" $true ""
		if ($DryRun) {
			Plan "wait until the dev server listens on port $ServerPort"
		} else {
			Say "waiting for the dev server (the first start takes a minute or two)"
			$found = Wait-Listener $ServerPort 1 0 $server ([Math]::Max($TimeoutSec, 300))
			if ($found -le 0) {
				Fail "the dev server didn't start (see its window)"
			}
		}
	}
	$minecraft += Start-Gradle "runClient" $true $Links
	$minecraft += Start-Gradle "runClient2" $true $P2Links
	Say "in both clients: Multiplayer > Direct Connection > localhost"
}

if ($DryRun) {
	if ($UseWatch) {
		Plan "watch: restart Gen1Recomp if it closes while Minecraft runs (at most $RestartLimit times in $($RestartWindowSec / 60) minutes)"
	}
	Plan "done"
	exit 0
}

# Wait while Minecraft runs; with -Watch, bring a closed Gen1Recomp back.
while (@($minecraft | Where-Object { $_ -and -not $_.HasExited }).Count -gt 0) {
	if ($UseWatch) {
		foreach ($g in $gen1) {
			if ($g.Process -and $g.Process.HasExited) {
				$now = Get-Date
				$g.Restarts = @($g.Restarts | Where-Object { ($now - $_).TotalSeconds -lt $RestartWindowSec })
				if ($g.Restarts.Count -ge $RestartLimit) {
					Say "$($g.Who) closed again; not restarting it more than $RestartLimit times in $($RestartWindowSec / 60) minutes"
					$g.Process = $null
					continue
				}
				$g.Restarts += $now
				Say "$($g.Who) closed while Minecraft runs; restarting it (Minecraft waits paused; pick CONTINUE when it's back)"
				$g.Process = Start-Gen1 $g
				[void] (Wait-Gen1 $g.Process $g)
			}
		}
	}
	Start-Sleep -Seconds 2
}
if ($Engines -eq "minecraft") {
	Say "Minecraft has closed. The games it started were saved and stopped; any you started yourself keep running."
} else {
	Say "Minecraft has closed. Gen1Recomp keeps running: save there if you like, then close it."
}
exit 0
