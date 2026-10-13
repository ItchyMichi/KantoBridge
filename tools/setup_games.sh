#!/usr/bin/env bash
# Sets up one Gen1Recomp data home per world space from the ROM files in local/rom-inbox and imports each with
# Gen1Recomp's own importer, on Linux (the RunPod pod and the e2e layout: docs/specs/MULTI_GAME_PLAN.md §4.11, MG4;
# tools/e2e/README.md). The Windows counterpart is tools/setup_games.ps1.
#
#   tools/setup_games.sh [--dry-run] [--appimage <file>] [--inbox <dir>] [--local <dir>] [--first-port 27151]
#
# Linux layout: one extracted AppImage, local/gen1recomp-linux-<pin>/ (AppRun), shared by every world space, and one
# data home per world space as its XDG_DATA_HOME: local/gen1recomp-data-<pin>/ for kanto (the e2e runner's name) and
# local/gen1recomp-data-<space>-<pin>/ for the others; the game's folder inside is pokemon-love2d/ (imports, mods,
# saves). For each inbox file:
#   1. the pinned AppImage (tools/installer/pins.json `gen1recomp-linux`: the official GitHub release, SHA-256 and size
#      checked before extraction; never gen1recomp[.]com) is extracted once; a fresh data home gets
#      pokemon-love2d/mods/gen1-bridge as a symlink to this repository's gen1-bridge; nothing else is copied;
#   2. the importer runs headless (POKEPORT_IMPORT_ONLY=1 POKEPORT_IMPORT_ROM=<path> POKEPORT_FORCE_IMPORT=1
#      POKEPORT_CONSOLE=1; under xvfb-run when there is no DISPLAY) and quits by itself;
#   3. the game is read from pokemon-love2d/<game>/rom-cache.complete (folder names and the marker only); its world
#      space is the catalogue's region (protocol/games.json); the fresh home is renamed to the space's home when free,
#      else the importer runs again into the existing home and the fresh one is deleted;
#   4. the Union Room is switched off (tools/e2e/drivers/kb_union_off.lua through POKEPORT_DRIVER);
#   5. local/launch.json (version 2) is written; the client's links go into local/minecraft/config/kantobridge-client.json.
# The script never reads, copies, moves, checks or deletes a ROM, and never opens an import's files: the inbox is yours
# to clear. Output: file names, game ids, folders and exit codes only. --dry-run prints the plan and changes nothing.
set -euo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
repo="$(dirname "$here")"
local_dir="$repo/local"
inbox=""
appimage=""
first_port=27151
dry_run=0
union_off=1
pin="0.3.85"
while [ $# -gt 0 ]; do
	case "$1" in
		--dry-run) dry_run=1 ;;
		--appimage) appimage="$2"; shift ;;
		--inbox) inbox="$2"; shift ;;
		--local) local_dir="$2"; shift ;;
		--first-port) first_port="$2"; shift ;;
		--no-union-room-off) union_off=0 ;;
		-h|--help) sed -n '2,24p' "$0"; exit 0 ;;
		*) echo "setup: unknown option $1" >&2; exit 2 ;;
	esac
	shift
done
[ -n "$inbox" ] || inbox="$local_dir/rom-inbox"
marker="rom-cache.complete"
identity="pokemon-love2d"
pins="$here/installer/pins.json"
driver="$here/e2e/drivers/kb_union_off.lua"

echo "Based on the Pokemon Gen 1 Recompilation Project by BOIS CLUB GAMES, LLC (https://github.com/bryanthaboi/gen1recomp)"
# Messages go to stderr: several functions return a value through stdout (`fresh=$(new_home)`, `code=$(import_into ...)`).
say() { echo "setup: $*" >&2; }
plan() { echo "[plan] $*" >&2; }
fail() { echo "setup: $*" >&2; exit 1; }

py="$here/.venv/bin/python"
[ -x "$py" ] || py="$(command -v python3 || true)"
[ -n "$py" ] || fail "python3 is needed (tools/setup.sh makes the venv)"

# --- the pin and the catalogue (read with Python: no jq dependency) ------------------------------------------
read -r pin_url pin_sha pin_size <<<"$("$py" - "$pins" <<'EOF'
import json, sys
doc = json.load(open(sys.argv[1], encoding="utf-8"))
pin = next(f for f in doc["files"] if f["id"] == "gen1recomp-linux")
assert pin["url"].startswith("https://github.com/bryanthaboi/gen1recomp/releases/download/"), "official release only"
print(pin["url"], pin["sha256"], pin["size"])
EOF
)"
space_of() { "$py" - "$repo/protocol/games.json" "$1" <<'EOF'
import json, sys
games = json.load(open(sys.argv[1], encoding="utf-8"))["games"]
print(games[sys.argv[2]]["region"] if sys.argv[2] in games else "")
EOF
}
has_adapter() { "$py" - "$repo/protocol/games.json" "$1" <<'EOF'
import json, sys
games = json.load(open(sys.argv[1], encoding="utf-8"))["games"]
sys.exit(0 if games.get(sys.argv[2], {}).get("adapter") else 1)
EOF
}
detect() { # the catalogued games with a completed import in a data home (folder names and the marker only)
	PYTHONPATH="$here" "$py" -c 'import sys; from kb_games import detect; print(" ".join(detect(sys.argv[1])))' "$1/$identity"
}
home_of() { # the data home of a world space
	if [ "$1" = "kanto" ]; then echo "$local_dir/gen1recomp-data-$pin"; else echo "$local_dir/gen1recomp-data-$1-$pin"; fi
}

# --- the AppImage ----------------------------------------------------------------------------------------------
app="$local_dir/gen1recomp-linux-$pin"
check_pin() { # size and SHA-256 against the pin
	local size; size=$(stat -c %s "$1")
	[ "$size" = "$pin_size" ] || { say "$(basename "$1"): size $size, pinned $pin_size"; return 1; }
	local sum; sum=$(sha256sum "$1" | cut -d' ' -f1)
	[ "$sum" = "$pin_sha" ] || { say "$(basename "$1"): sha256 ${sum:0:16}..., pinned ${pin_sha:0:16}..."; return 1; }
}
ensure_app() {
	if [ -x "$app/AppRun" ]; then return; fi
	local file="$appimage"
	if [ -z "$file" ]; then
		file="$local_dir/_downloads/$(basename "$pin_url")"
		if [ ! -f "$file" ] || ! check_pin "$file"; then
			if [ "$dry_run" = 1 ]; then plan "download $pin_url to $file and check its size ($pin_size) and SHA-256 (${pin_sha:0:16}...)"; else
				mkdir -p "$local_dir/_downloads"
				say "downloading the pinned release $(basename "$pin_url") (official GitHub release)"
				curl -fL --retry 3 -o "$file" "$pin_url"
				check_pin "$file" || { rm -f "$file"; fail "the download doesn't match the pin; removed"; }
			fi
		fi
	else
		[ -f "$file" ] || fail "no file at --appimage $file"
		check_pin "$file" || fail "the AppImage at $file isn't the pinned release"
	fi
	if [ "$dry_run" = 1 ]; then plan "extract $file to $app (AppRun) once"; return; fi
	chmod +x "$file"
	(cd "$local_dir" && "$file" --appimage-extract >/dev/null) || fail "couldn't extract the AppImage"
	mv "$local_dir/squashfs-root" "$app"
	say "extracted the release to $app"
}

run_engine() { # run_engine <home> <args...>: AppRun with the data home, under Xvfb when headless; exit code only
	local home="$1"; shift
	local cmd=("$app/AppRun" "$@")
	if [ -z "${DISPLAY:-}" ] && command -v xvfb-run >/dev/null; then cmd=(xvfb-run -a "${cmd[@]}"); fi
	XDG_DATA_HOME="$home" ALSOFT_DRIVERS=null POKEPORT_CONSOLE=1 timeout 900 "${cmd[@]}" >/dev/null 2>&1 && echo 0 || echo $?
}

new_home() {
	local n=1
	while [ -e "$local_dir/gen1recomp-data-new-$n" ]; do n=$((n + 1)); done
	local home="$local_dir/gen1recomp-data-new-$n"
	if [ "$dry_run" = 1 ]; then plan "make a fresh data home $home with $identity/mods/gen1-bridge -> $repo/gen1-bridge"; echo "$home"; return; fi
	mkdir -p "$home/$identity/mods"
	ln -s "$repo/gen1-bridge" "$home/$identity/mods/gen1-bridge"
	say "made $home"
	echo "$home"
}

import_into() { # import_into <home> <rom path>: Gen1Recomp's own headless import
	local home="$1" rom="$2"
	if [ "$dry_run" = 1 ]; then plan "import $(basename "$rom") into $home with Gen1Recomp's importer (POKEPORT_IMPORT_ONLY=1 POKEPORT_IMPORT_ROM=<the file> POKEPORT_FORCE_IMPORT=1), wait for it to quit"; echo 0; return; fi
	say "importing $(basename "$rom") into $(basename "$home") (Gen1Recomp's importer)"
	local code
	code=$(POKEPORT_IMPORT_ONLY=1 POKEPORT_FORCE_IMPORT=1 POKEPORT_IMPORT_ROM="$rom" run_engine "$home")
	say "importer exit code $code for $(basename "$rom")"
	echo "$code"
}

union_off() { # the e2e driver writes the setting off through the engine's own options API and quits
	[ "$union_off" = 1 ] || return 0
	if [ "$dry_run" = 1 ]; then plan "switch the Union Room off in $1 (AppRun --game=$2 with POKEPORT_DRIVER=$driver)"; return; fi
	local code
	code=$(POKEPORT_DRIVER="$driver" run_engine "$1" "--game=$2")
	say "Union Room off in $(basename "$1") (driver exit code $code)"
}

# --- run ---------------------------------------------------------------------------------------------------
[ -d "$inbox" ] || fail "no inbox at $inbox (make it and put your ROM files in it)"
mapfile -t files < <(find "$inbox" -maxdepth 1 -type f ! -name '.*' | sort)
[ ${#files[@]} -gt 0 ] || fail "the inbox $inbox is empty"
say "inbox: ${#files[@]} file(s): $(for f in "${files[@]}"; do basename "$f"; done | paste -sd, -)"
ensure_app
for rom in "${files[@]}"; do
	fresh=$(new_home)
	code=$(import_into "$fresh" "$rom")
	if [ "$dry_run" = 1 ]; then
		plan "read the game from $fresh/$identity/<game>/$marker (folder names only), place the home as local/gen1recomp-data[-<space>]-$pin, re-import into an existing home of that world space and remove the fresh one"
		continue
	fi
	games=$(detect "$fresh")
	if [ "$code" != 0 ] || [ -z "$games" ]; then
		say "warning: no completed import for $(basename "$rom") (exit code $code); the fresh home $fresh is left for you"
		continue
	fi
	game="${games%% *}"
	space=$(space_of "$game")
	target=$(home_of "$space")
	if [ -e "$target" ]; then
		say "$game belongs to the world space $space, which already has a home at $target: importing there too"
		again=$(import_into "$target" "$rom")
		if [ "$again" != 0 ] || ! detect "$target" | tr ' ' '\n' | grep -qx "$game"; then
			say "warning: the import into $target didn't complete (exit code $again); the fresh home $fresh is kept"
			continue
		fi
		rm -rf "$fresh"
		say "removed the temporary home $fresh"
	else
		mv "$fresh" "$target"
		say "placed $game's home at $target ($space)"
	fi
	union_off "$target" "$game"
done

# --- launch.json and the client's links --------------------------------------------------------------------
if [ "$dry_run" = 1 ]; then
	plan "write $local_dir/launch.json (version 2: installs per world space, ports) and local/minecraft/config/kantobridge-client.json links (game:port per install, $first_port upwards)"
	plan "done (the inbox is yours to clear)"
	exit 0
fi
PYTHONPATH="$here" "$py" - "$local_dir" "$pin" "$first_port" "$repo" <<'EOF'
import json, os, sys
from pathlib import Path
from kb_games import detect, load_games
local_dir, pin, first_port, repo = Path(sys.argv[1]), sys.argv[2], int(sys.argv[3]), Path(sys.argv[4])
games = load_games()
installs = {}
for home in sorted(local_dir.glob("gen1recomp-data*")):
    name = home.name
    if not name.endswith("-" + pin) or "-new-" in name:
        continue
    space = "kanto" if name == f"gen1recomp-data-{pin}" else name[len("gen1recomp-data-"):-len("-" + pin)]
    held = detect(home / "pokemon-love2d")
    if not held:
        continue
    play = next((g for g in held if games.adapter(g)), held[0])
    installs[space] = {"dir": os.path.relpath(home, repo), "games": held, "play": play}
ordered = {k: installs[k] for k in (["kanto"] if "kanto" in installs else []) + sorted(k for k in installs if k != "kanto")}
if not ordered:
    print("setup: no data home with a completed import; launch.json left as it was"); sys.exit(0)
launch_file = local_dir / "launch.json"
launch = {}
if launch_file.is_file():
    launch = {k: v for k, v in json.load(open(launch_file, encoding="utf-8")).items()
              if k not in ("installs", "ports", "game", "gen1recomp", "port")}
launch["installs"] = ordered
launch["ports"] = {"first": first_port, "player2": 10}
launch_file.write_text(json.dumps(launch, indent=2) + "\n", encoding="utf-8")
links = [{"game": v["play"], "port": first_port + i} for i, v in enumerate(ordered.values())]
config_dir = local_dir / "minecraft" / "config"
config_dir.mkdir(parents=True, exist_ok=True)
client_file = config_dir / "kantobridge-client.json"
client = {}
if client_file.is_file():
    client = {k: v for k, v in json.load(open(client_file, encoding="utf-8")).items() if k not in ("port", "links")}
client["links"] = links
client.setdefault("shapes", "on")
client_file.write_text(json.dumps(client, indent=2) + "\n", encoding="utf-8")
print(f"setup: wrote {launch_file} ({len(ordered)} install(s)) and {client_file} links: " + ",".join(f"{l['game']}:{l['port']}" for l in links))
EOF
say "done. Clear $inbox yourself when you no longer need the files there"
