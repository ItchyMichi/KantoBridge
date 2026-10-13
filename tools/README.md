# tools/

Test tooling for Kanto Bridge. Everything runs from a project-local Python venv; nothing is
installed system-wide.

## Setup and the one test command

| Command | What it does |
|---|---|
| `.\tools\setup.ps1` / `tools/setup.sh` | Creates `tools/.venv` from the pinned, wheel-only `requirements.txt` |
| `.\tools\check.ps1` / `tools/check.sh` | Every automated test: pytest (below), then `gradlew build` in `fabric-bridge` (JUnit and GameTests) on JDK 21 |
| `... -E2E` / `... --e2e` | Also the end-to-end tests on the test Gen1Recomp in `local/gen1recomp-e2e` (a window opens) |
| `... -NoGradle` / `... --no-gradle` | Skips Gradle |

Extra arguments go to pytest, e.g. `tools/check.sh --no-gradle -k redact`. Gradle uses
`KB_JAVA_HOME` if set, otherwise the Temurin 21 path from CLAUDE.md.

## Launching (`launch.ps1`)

`.\tools\launch.ps1` starts the hidden Gen1Recomp games (their windows visible), waits until each
gen1-bridge listens, then starts Minecraft. It never touches Gen1Recomp's data and never stops it, so
unsaved progress is never lost. It sets `JAVA_HOME` (a JDK 21) for its own Gradle runs only.

**Several games (MG4, [MULTI_GAME_PLAN §4.11](../docs/specs/MULTI_GAME_PLAN.md)):** one hidden
process per game, each from its own install (one install per world space: `local\gen1recomp` for
Kanto, `local\gen1recomp-hoenn`, `-johto`, `-kanto_frlg`), each on its own port (27151, 27152, ...),
and the dev client gets them all as `-Pkb.links=blue:27151,emerald:27152` (`-Dkantobridge.links`).
Which games an install holds is **detected** from its folder names and each game folder's
`rom-cache.complete` marker, nothing more (the same rule as `kb_games.py`).

| Command | What it starts |
|---|---|
| `.\tools\launch.ps1` | Every install of `local\launch.json` whose game has an adapter in this bridge (`protocol/games.json`; today Red and Blue, so `local\gen1recomp` with `--game=blue --console`), then `gradlew runClient -Pkb.links=...` in this console |
| `.\tools\launch.ps1 -Games blue,emerald` | The installs that hold those games (a game without an adapter starts too, with a warning: Minecraft can't follow it yet) |
| `.\tools\launch.ps1 -Regions kanto,hoenn` | Those world spaces' installs, each playing its `play` game (else its first game with an adapter) |
| `.\tools\launch.ps1 -Players 2` | The same, plus player 2's own install (`local\gen1recomp-p2` on 27151 + 10 = 27161), the dev server (`runServer`, sets `online-mode=false`), then `runClient` and `runClient2`, each in its own window |
| `.\tools\launch.ps1 -Mode player` | An installed setup (docs/INSTALLER.md): `%LOCALAPPDATA%\KantoBridge\gen1recomp`, then the Prism instance `KantoBridge` |
| `-DryRun` | Prints the plan only (each install, game and port, and the links string) |

Other options: `-Game red` (one game, an alias of `-Games`), `-Slot <slot id>` (Gen1Recomp's
`--slot=`), `-NoConsole`, `-Watch` (restart a Gen1Recomp that closes while Minecraft runs, at most 3
times in 10 minutes; on by default in player mode, `-NoWatch` turns it off), `-Gen1Recomp`/
`-P2Gen1Recomp <folder>`, `-Port <first port>`, `-JavaHome <jdk-21>`, `-TimeoutSec`. Settings can
also live in `local\launch.json` (player mode: `<Root>\launch.json`), version 2:

```json
{
  "installs": {
    "kanto": {"dir": "local\\gen1recomp", "games": ["blue"], "play": "blue"},
    "hoenn": {"dir": "local\\gen1recomp-hoenn", "games": ["emerald"], "play": "emerald"}
  },
  "ports": {"first": 27151, "player2": 10},
  "javaHome": "C:\\Program Files\\Eclipse Adoptium\\jdk-21...", "slot": "", "instance": "KantoBridge"
}
```

(`tools\setup_games.ps1` writes it; the older keys `game`, `gen1recomp`, `gen1recompP2`, `port` are
still read as the `kanto` install.) An install before its first import starts with `--game=blue`
and a warning (Gen1Recomp's launcher asks for the ROM). A Gen1Recomp that's already running from
the same install is reused. If gen1-bridge had to take another port (the configured one was busy),
the script says which and how to point the client's `links` entry at it.

## Setting up installs (`setup_games.ps1`, `setup_games.sh`)

`.\tools\setup_games.ps1` (Linux, the pod's AppImage layout: `tools/setup_games.sh`) takes every file
in `local\rom-inbox\` and, per file: makes a fresh portable install from the pinned Gen1Recomp
release (`installer\pins.json`; the official GitHub release only, its size and SHA-256 checked before
unpacking; `portable.txt`; `mods\gen1-bridge` as a junction), runs **Gen1Recomp's own importer**
headless on the file (`POKEPORT_IMPORT_ONLY=1 POKEPORT_IMPORT_ROM=<path>`), reads the game from the
folder it made (`<game>\rom-cache.complete`, names only), places the install as its world space's
(`local\gen1recomp` for Kanto, `local\gen1recomp-<space>` otherwise; a world space that already
has an install gets the import run into it instead and the fresh one is removed), switches the Union
Room off (`e2e\drivers\kb_union_off.lua`), and finally writes `local\launch.json` and the client's
`links`. It never reads, copies, moves or checks a ROM and never opens an import; you clear the inbox.
`-DryRun` prints the plan. `python -m kb_games` lists what each install holds.

## Installer (skeleton)

`.\tools\installer\install.ps1` prints the install plan from [docs/INSTALLER.md](../docs/INSTALLER.md)
and installs nothing; `-Verify <file> -Id <pin>` checks a downloaded file against
`installer\pins.json` (size and SHA-256).

## Layout

| Path | Contents |
|---|---|
| `kb_protocol/` | Shared protocol helpers: strict NDJSON framing, the schema registry and message checker, envelope builders, redaction, and the generator of `protocol/catalog.json` and `protocol/redaction-vectors.json` |
| `fake_gen1/` | Listens like gen1-bridge with a synthetic world (`synth.py`: a procedurally drawn tileset, TEST_TOWN and TEST_HOUSE built from its blocks, NPCs, a sign, a trainer). Serves the full export, answers `session.bind` (`session.bound` with `switched`, then `save.loaded`), and can send made-up gifts, battles, saves (`save.begin` with a new pair), a reload of the last save, Bill's PC (`pc.open` with an id) and a waiting bind (`session.waiting`). `python -m fake_gen1 --help` for the interactive commands; `--write-bundle DIR` writes the synthetic export bundle |
| `fake_minecraft/` | Connects like the fabric-bridge client, checks every message against the schemas, keeps the heartbeat, and logs only types and sizes (redacted messages with `--verbose`). `python -m fake_minecraft --help`. MG4: `--links blue:27151,emerald:27152 [--follow blue] [--park]` opens one link per hidden game (`links.py`: one followed, the rest parked with `region.park`; a port running another game than its entry is `wrong_game` and never followed) |
| `tests/` | pytest suites for the protocol, `kb_protocol`, the stand-ins (incl. `test_links.py`: two fake games behind the links driver), `kb_games.py`, `launch.ps1` (dry runs and its port probe), the installer skeleton and the engine pin (`test_pins.py`: every place the Gen1Recomp version is written agrees with `Engine.CHECKED` in `gen1-bridge/lib/kb_engine.lua`, the adapter loader, and the Gen 1 adapter `lib/engine/gen1.lua`) |
| `launch.ps1` | The launcher (above) |
| `kb_games.py` | Import detection (MG4): the catalogued games an install holds, from folder names and the `rom-cache.complete` marker only; `python -m kb_games [--install DIR] [--json]`; reads `launch.json` in both versions |
| `setup_games.ps1`, `setup_games.sh` | The install and import tool (above) |
| `installer/` | The installer skeleton and its pins (above) |
| `lua/` | The Lua test harness: gen1-bridge modules run under lupa's LuaJIT 2.1 in a sandbox-parity environment, with a fake LuaSocket. Its pytest files live beside it |
| `e2e/` | End-to-end runner for the test Gen1Recomp instance, our own driver scripts, and e2e tests (marker `e2e`, run only with `--e2e`). `build_world.py` is the headless real-data build: export from the test instance through `ExportCli`, then a headless server build; `--maps pallet\|route1\|viridian\|all\|<ids>`, `--world <name>` (default `Kanto`), `--fresh`, `--skip-export` (with `--game red\|blue`), and (MG3) `--region <id> --slot <n>` for a region build (Gradle `-Pkb.buildRegion`/`-Pkb.buildSlot`; the report's `region` object and `<world>/kantobridge/regions/<id>/` files are checked against their schemas). Automated tests build into `saves/KantoE2E` |
| `pin_check.py` | The first step of a Gen1Recomp pin change ([docs/specs/ENGINE_UPGRADE_PLAN.md](../docs/specs/ENGINE_UPGRADE_PLAN.md)): `python3 -I tools/pin_check.py refs/gen1recomp v0.3.49 v0.3.85 --report local/reports/pin_check.json` classes every `file:line` citation of an engine source in this repository as same, shifted (stale numbers), changed (re-check the behaviour) or missing, reading the clone only through `git`. Exits 1 when a cited file is gone. `--restamp --dry-run` prints how every shifted citation would be renumbered and `--restamp` rewrites them in place, each in its own syntax (`src/x/File.lua:N-M`, `File.lua:N`, `OC:N`, `, :N`, and a `:N` on a comment line that continues the one file its comment paragraph cites above it); the changed, missing and unresolved ones, and the orphan fragments (no file on the line, none certain from the paragraph), are listed for hand editing. A real restamp writes `pin_check.stamp.json` (the tag the citations are at now, so running it twice does nothing). `--since <rev>` leaves alone citing lines edited after `<rev>`; `--root <dir>` scans another checkout |
| `pytest.ini`, `conftest.py` | pytest configuration (`--e2e` option, markers) |
| `requirements.txt` | Exact pins (see `docs/VERSIONS.md`) |

## Protocol helpers

```sh
cd tools
.venv/Scripts/python.exe -m kb_protocol.catalog --check    # are the derived files current?
.venv/Scripts/python.exe -m kb_protocol.catalog --write    # regenerate after a schema change
```

`--write` also regenerates `gen1-bridge/lib/kb_games.lua` from `protocol/games.json`, the game
catalogue (MULTI_GAME_PLAN §4.2); `kb_protocol.games.load_games()` reads it in Python, and
`fake_gen1 --game <id>` accepts any catalogued id. `fake_gen1 --state title|fresh|saved` (MG2) is the
playthrough it starts in: at the title it is not in the game until a `region.start` (CONTINUE, or NEW GAME
with `--no-slot-save`); it answers `region.park`/`region.resume` and sends `game.status` as gen1-bridge does.

## Data safety

The stand-ins and tests never contain ROM data: they use made-up maps, text and flags. Anything that
talks to the real game prints only message types, engine symbols, counts, lengths and short hashes;
ROM-derived fields are redacted through the `x-rom-derived` marks in the schemas
(docs/PLAN.md section 0.3). Reports go to `local/reports/`, which is git-ignored.
