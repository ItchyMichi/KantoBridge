<#
.SYNOPSIS
Kanto Bridge installer: SKELETON ONLY (docs/INSTALLER.md).

.DESCRIPTION
Prints the install plan for a per-user install in %LOCALAPPDATA%\KantoBridge. Each step is a function
with what it will do. Nothing is downloaded, installed or bundled yet: third-party downloads (Prism
Launcher, Gen1Recomp, Fabric API, Cobblemon) need the user's OK first (docs/PLAN.md M10).

-Verify <file> -Id <pin id> checks a file you already have against tools/installer/pins.json (size
and SHA-256). That part works today.

The installer never reads, copies or checks a ROM: the ROM import happens in Gen1Recomp's own importer.

.EXAMPLE
.\tools\installer\install.ps1
.\tools\installer\install.ps1 -Verify C:\Downloads\gen1recomp-0.3.49-windows.zip -Id gen1recomp
#>
[CmdletBinding()]
param(
	[string] $Root = "",
	[string] $Pins = "",
	[ValidateSet("", "red", "blue")] [string] $Game = "",
	[string] $Verify = "",
	[string] $Id = "",
	[switch] $Execute
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

# Gen1Recomp's licence (its LICENSE.MD, additional term 1) asks any front end that starts the game to show this credit.
Write-Host "Based on the Pokemon Gen 1 Recompilation Project by BOIS CLUB GAMES, LLC (https://github.com/bryanthaboi/gen1recomp)"

if (-not $Root) { $Root = Join-Path $env:LOCALAPPDATA "KantoBridge" }
if (-not $Pins) { $Pins = Join-Path $PSScriptRoot "pins.json" }
$Repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)

function Plan([string] $text) { Write-Host "[plan] $text" }
function Fail([string] $text) {
	Write-Host "install: $text" -ForegroundColor Red
	exit 1
}

$PinData = Get-Content -LiteralPath $Pins -Raw | ConvertFrom-Json

function Get-Pin([string] $pinId) {
	foreach ($file in $PinData.files) {
		if ($file.id -eq $pinId) { return $file }
	}
	return $null
}

function Test-PinnedFile([string] $path, [string] $pinId) {
	# True when the file matches its pin's size (if pinned) and SHA-256.
	$pin = Get-Pin $pinId
	if (-not $pin) { Fail "no pin '$pinId' in $Pins" }
	if (-not $pin.sha256) { Fail "'$pinId' isn't pinned yet (docs/INSTALLER.md)" }
	$item = Get-Item -LiteralPath $path
	if ($pin.size -and ($item.Length -ne [long] $pin.size)) {
		Write-Host "install: $pinId size $($item.Length), pinned $($pin.size)"
		return $false
	}
	$hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
	if ($hash -ne $pin.sha256) {
		Write-Host "install: $pinId sha256 $($hash.Substring(0, 16))..., pinned $($pin.sha256.Substring(0, 16))..."
		return $false
	}
	return $true
}

if ($Verify) {
	if (-not $Id) { Fail "-Verify needs -Id (one of: $(($PinData.files | ForEach-Object { $_.id }) -join ', '))" }
	if (Test-PinnedFile $Verify $Id) {
		Write-Host "install: $Id OK"
		exit 0
	}
	Write-Host "install: $Id doesn't match its pin"
	exit 1
}

# --- the steps (docs/INSTALLER.md "Install steps") -----------------------------------------------

function Step-Preflight {
	Plan "1. preflight: Windows 10/11 x64, ~3 GB free, tar.exe present, $Root writable and not under OneDrive; no admin rights, nothing system-wide"
}

function Step-Downloads {
	foreach ($file in $PinData.files) {
		if ($file.url) {
			Plan "2. download $($file.id) $($file.version) from $($file.url) into $Root\cache, then check its SHA-256 $($file.sha256.Substring(0, 16))... (TODO: needs your OK)"
		} else {
			Plan "2. download $($file.id): not pinned yet ($($file.source))"
		}
	}
	Plan "   Minecraft $($PinData.minecraft.version), Fabric Loader $($PinData.minecraft.fabricLoader) and Java $($PinData.minecraft.java): left to Prism"
}

function Step-Gen1Recomp {
	Plan "3. unpack Gen1Recomp into $Root\gen1recomp, write portable.txt, copy $Repo\gen1-bridge into its mods\gen1-bridge (TODO)"
}

function Step-Prism {
	Plan "4. unpack Prism into $Root\prism, apply prismlauncher.cfg defaults (auto Java, stay open until the game stops), create instance KantoBridge (Minecraft $($PinData.minecraft.version), Fabric Loader $($PinData.minecraft.fabricLoader)), copy Fabric API, Cobblemon and kantobridge into its mods (TODO: templates)"
}

function Step-Launcher {
	Plan "5. copy tools\launch.ps1 to $Root\launch.ps1, write launch.json and the 'Kanto Bridge' Start-menu and desktop shortcuts (TODO)"
}

function Step-SignIn {
	Plan "6. open Prism once so the player signs in with Microsoft (Prism keeps the account; we never see it)"
}

function Step-RomImport {
	Plan "7. start $Root\gen1recomp\gen1recomp.exe --launcher: the player imports their own ROM in Gen1Recomp's importer (the installer never touches the ROM), then picks Red or Blue for launch.json"
}

function Step-FirstRun {
	Plan "8. first launch: launch.ps1 -Mode player; on Minecraft's title screen, Create world builds the world from the player's own games"
}

function Step-Stamp {
	Plan "9. write $Root\install.stamp: the release and the SHA-256 of every managed file (updates replace only these)"
}

Write-Host "install: skeleton (docs/INSTALLER.md): this prints the plan; it installs nothing"
Write-Host "install: target $Root$(if ($Game) { ", game $Game" })"
Step-Preflight
Step-Downloads
Step-Gen1Recomp
Step-Prism
Step-Launcher
Step-SignIn
Step-RomImport
Step-FirstRun
Step-Stamp

if ($Execute) {
	Write-Host "install: -Execute isn't available yet: downloads and bundling need your OK first (docs/INSTALLER.md, questions)"
	exit 2
}
exit 0
