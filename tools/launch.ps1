<#
.SYNOPSIS
Starts Kanto Bridge: the hidden Gen1Recomp (visible window), waits for its bridge port, then Minecraft.

.DESCRIPTION
Dev mode (default) runs this repository's dev setup:
  - one player:  local\gen1recomp, then `gradlew runClient`;
  - -Players 2:  local\gen1recomp and a second install (local\gen1recomp-p2), the dedicated dev
                 server (`gradlew runServer`, online-mode=false), then `runClient` and `runClient2`.
Player mode (-Mode player) runs an installed setup laid out as in docs/INSTALLER.md: Gen1Recomp from
<Root>\gen1recomp and Minecraft through the bundled Prism Launcher instance.

Settings come from the parameters, then a JSON config (dev: local\launch.json; player:
<Root>\launch.json), then the defaults. Config keys: game, gen1recomp, gen1recompP2, port, javaHome,
slot, root, instance.

The script never touches Gen1Recomp's data (saves, ROM import, options): it only starts the program.
It sets JAVA_HOME for its own Gradle runs only, never system-wide. It never stops Gen1Recomp, so
unsaved progress is never lost; with -Watch it restarts Gen1Recomp if it exits while Minecraft runs.

.EXAMPLE
.\tools\launch.ps1
.\tools\launch.ps1 -Game red -Watch
.\tools\launch.ps1 -Players 2
.\tools\launch.ps1 -Mode player
.\tools\launch.ps1 -DryRun
#>
[CmdletBinding()]
param(
	[ValidateSet("dev", "player")] [string] $Mode = "dev",
	[ValidateSet(1, 2)] [int] $Players = 1,
	[ValidateSet("", "red", "blue")] [string] $Game = "",
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
$RestartLimit = 3              # -Watch: at most this many restarts per player ...
$RestartWindowSec = 600        # ... within this many seconds

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

$Game = Pick $Game "game" "blue"
if ($Game -notin @("red", "blue")) {
	Fail "game must be red or blue, not '$Game'"
}
$Slot = Pick $Slot "slot" ""
$Port = [int] (Pick $(if ($Port -gt 0) { [string] $Port } else { "" }) "port" "27151")
if ($Mode -eq "player") {
	$Gen1Recomp = Pick $Gen1Recomp "gen1recomp" (Join-Path $Root "gen1recomp")
	$Instance = Pick $Instance "instance" "KantoBridge"
} else {
	$Gen1Recomp = Pick $Gen1Recomp "gen1recomp" (Join-Path (Join-Path $Repo "local") "gen1recomp")
	$P2Gen1Recomp = Pick $P2Gen1Recomp "gen1recompP2" (Join-Path (Join-Path $Repo "local") "gen1recomp-p2")
	if (-not $MinecraftDir) {
		$MinecraftDir = Join-Path (Join-Path $Repo "local") "minecraft"
	}
}
$UseConsole = ($Mode -eq "dev" -and -not $NoConsole) -or $Console
$UseWatch = ($Mode -eq "player" -and -not $NoWatch) -or $Watch

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

$Gen1Exe = Test-Install $Gen1Recomp "Gen1Recomp"
$P2Exe = $null
if ($Mode -eq "dev" -and $Players -eq 2) {
	if (-not (Test-Path -LiteralPath $P2Gen1Recomp)) {
		Fail "player 2's Gen1Recomp install $P2Gen1Recomp is missing (docs/PLAYTEST.md M9 'Setup'; -P2Gen1Recomp local\gen1recomp-e2e works for a quick try)"
	}
	if ((Resolve-Path -LiteralPath $Gen1Recomp).Path -eq (Resolve-Path -LiteralPath $P2Gen1Recomp).Path) {
		Fail "player 2 needs a second Gen1Recomp install (two processes of one install would share its save slots)"
	}
	$P2Exe = Test-Install $P2Gen1Recomp "player 2's Gen1Recomp"
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

Say "mode $Mode, $Players player(s), game $Game, port $Port$(if ($Slot) { ", slot $Slot" })"

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

function Start-Gen1([string] $exe, [string] $who) {
	$arguments = @("--game=$Game")
	if ($UseConsole) { $arguments += "--console" }
	if ($Slot) { $arguments += "--slot=$Slot" }
	$folder = Split-Path -Parent $exe
	if ($DryRun) {
		Plan "start $who`: $exe $($arguments -join ' ') (in $folder, window visible)"
		return $null
	}
	$running = Get-Running $exe
	if ($running) {
		Say "$who is already running (pid $($running.Id)); using it"
		return $running
	}
	Say "starting $who`: $($arguments -join ' ')"
	return Start-Process -FilePath $exe -ArgumentList $arguments -WorkingDirectory $folder -WindowStyle Normal -PassThru
}

function Wait-Gen1([System.Diagnostics.Process] $process, [int] $expected, [string] $who) {
	if ($DryRun) {
		Plan "wait until $who listens on 127.0.0.1:$expected (or the next free of $($PortSpan - 1) after it)"
		return $expected
	}
	Say "waiting for $who's bridge port (keep its window open; the first boot takes a few seconds)"
	$found = Wait-Listener $Port $PortSpan $process.Id $process $TimeoutSec
	if ($found -eq -1) {
		Fail "$who closed before its bridge started (check its window or log; never share its crash log)"
	}
	if ($found -eq 0) {
		Fail "$who didn't open its bridge port within $TimeoutSec s: is the gen1-bridge mod enabled? (Gen1Recomp only starts mods once a game boots)"
	}
	Say "$who is listening on 127.0.0.1:$found"
	if ($found -ne $expected) {
		Say "warning: $who took port $found, but its Minecraft connects to ${expected}: free port $expected, or set BRIDGE PORT in $who's mod settings (F10) and the client's config\kantobridge-client.json {""port"":$found}"
	}
	return $found
}

# --- Minecraft --------------------------------------------------------------------------------------

function Start-Gradle([string] $task, [bool] $ownWindow) {
	if ($DryRun) {
		Plan "run fabric-bridge\gradlew.bat $task with JAVA_HOME=$Jdk (this run only)$(if ($ownWindow) { ' in its own window' })"
		return $null
	}
	$env:JAVA_HOME = $Jdk   # this process and its children only
	$folder = Split-Path -Parent $Gradle
	Say "starting gradlew $task"
	if ($ownWindow) {
		return Start-Process -FilePath $Gradle -ArgumentList @($task) -WorkingDirectory $folder -PassThru
	}
	return Start-Process -FilePath $Gradle -ArgumentList @($task) -WorkingDirectory $folder -NoNewWindow -PassThru
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

$gen1 = @()   # @{Exe; Who; Process; Expected; Restarts}
$gen1 += [pscustomobject]@{ Exe = $Gen1Exe; Who = "Gen1Recomp"; Process = $null; Expected = $Port; Restarts = @() }
if ($P2Exe) {
	$gen1 += [pscustomobject]@{ Exe = $P2Exe; Who = "player 2's Gen1Recomp"; Process = $null; Expected = $Port + 1; Restarts = @() }
}

foreach ($g in $gen1) {
	$g.Process = Start-Gen1 $g.Exe $g.Who
	[void] (Wait-Gen1 $g.Process $g.Expected $g.Who)
}

$minecraft = @()
if ($Mode -eq "player") {
	if ($DryRun) {
		Plan "start Minecraft: $Prism --launch $Instance"
	} else {
		Say "starting Minecraft (Prism Launcher instance $Instance)"
		$minecraft += Start-Process -FilePath $Prism -ArgumentList @("--launch", $Instance) -WorkingDirectory (Split-Path -Parent $Prism) -PassThru
	}
} elseif ($Players -eq 1) {
	$minecraft += Start-Gradle "runClient" $false
} else {
	Set-DevServerProperties
	$alreadyServing = @(Get-Listeners 0 | Where-Object { $_.Port -eq $ServerPort }).Count -gt 0
	if ($alreadyServing -and -not $DryRun) {
		Say "a server is already listening on port $ServerPort; using it"
	} else {
		$server = Start-Gradle "runServer" $true
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
	$minecraft += Start-Gradle "runClient" $true
	$minecraft += Start-Gradle "runClient2" $true
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
				$g.Process = Start-Gen1 $g.Exe $g.Who
				[void] (Wait-Gen1 $g.Process $g.Expected $g.Who)
			}
		}
	}
	Start-Sleep -Seconds 2
}
Say "Minecraft has closed. Gen1Recomp keeps running: save there if you like, then close it."
exit 0
