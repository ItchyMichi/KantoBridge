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

`.\tools\launch.ps1` starts Gen1Recomp (its window visible), waits until gen1-bridge listens, then
starts Minecraft. It never touches Gen1Recomp's data and never stops it, so unsaved progress is never
lost. It sets `JAVA_HOME` (a JDK 21) for its own Gradle runs only.

| Command | What it starts |
|---|---|
| `.\tools\launch.ps1` | `local\gen1recomp` (`--game=blue --console`), then `gradlew runClient` in this console |
| `.\tools\launch.ps1 -Players 2` | Two Gen1Recomp installs (`local\gen1recomp` on 27151, `local\gen1recomp-p2` on 27152), the dev server (`runServer`, sets `online-mode=false`), then `runClient` and `runClient2`, each in its own window |
| `.\tools\launch.ps1 -Mode player` | An installed setup (docs/INSTALLER.md): `%LOCALAPPDATA%\KantoBridge\gen1recomp`, then the Prism instance `KantoBridge` |
| `-DryRun` | Prints the plan only |

Other options: `-Game red|blue`, `-Slot <slot id>` (Gen1Recomp's `--slot=`), `-NoConsole`,
`-Watch` (restart Gen1Recomp if it closes while Minecraft runs, at most 3 times in 10 minutes; on by
default in player mode, `-NoWatch` turns it off), `-Gen1Recomp`/`-P2Gen1Recomp <folder>`, `-JavaHome
<jdk-21>`, `-TimeoutSec`. Settings can also live in `local\launch.json` (player mode:
`<Root>\launch.json`): `game`, `gen1recomp`, `gen1recompP2`, `port`, `javaHome`, `slot`, `instance`.
A Gen1Recomp that's already running from the same install is reused. If gen1-bridge had to take
another port (the configured one was busy), the script says which and how to point Minecraft at it.

## Installer (skeleton)

`.\tools\installer\install.ps1` prints the install plan from [docs/INSTALLER.md](../docs/INSTALLER.md)
and installs nothing; `-Verify <file> -Id <pin>` checks a downloaded file against
`installer\pins.json` (size and SHA-256).

## Layout

| Path | Contents |
|---|---|
| `kb_protocol/` | Shared protocol helpers: strict NDJSON framing, the schema registry and message checker, envelope builders, redaction, and the generator of `protocol/catalog.json` and `protocol/redaction-vectors.json` |
| `fake_gen1/` | Listens like gen1-bridge with a synthetic world (`synth.py`: a procedurally drawn tileset, TEST_TOWN and TEST_HOUSE built from its blocks, NPCs, a sign, a trainer). Serves the full export, answers `session.bind` (`session.bound` with `switched`, then `save.loaded`), and can send made-up gifts, battles, saves (`save.begin` with a new pair), a reload of the last save, Bill's PC (`pc.open` with an id) and a waiting bind (`session.waiting`). `python -m fake_gen1 --help` for the interactive commands; `--write-bundle DIR` writes the synthetic export bundle |
| `fake_minecraft/` | Connects like the fabric-bridge client, checks every message against the schemas, keeps the heartbeat, and logs only types and sizes (redacted messages with `--verbose`). `python -m fake_minecraft --help` |
| `tests/` | pytest suites for the protocol, `kb_protocol`, the stand-ins, `launch.ps1` (dry runs and its port probe) and the installer skeleton |
| `launch.ps1` | The launcher (above) |
| `installer/` | The installer skeleton and its pins (above) |
| `lua/` | The Lua test harness: gen1-bridge modules run under lupa's LuaJIT 2.1 in a sandbox-parity environment, with a fake LuaSocket. Its pytest files live beside it |
| `e2e/` | End-to-end runner for the test Gen1Recomp instance, our own driver scripts, and e2e tests (marker `e2e`, run only with `--e2e`). `build_world.py` is the headless real-data build: export from the test instance through `ExportCli`, then a headless server build; `--maps pallet\|route1\|viridian\|all\|<ids>`, `--world <name>` (default `Kanto`), `--fresh`, `--skip-export` (with `--game red\|blue`). Automated tests build into `saves/KantoE2E` |
| `pin_check.py` | The first step of a Gen1Recomp pin change ([docs/specs/ENGINE_UPGRADE_PLAN.md](../docs/specs/ENGINE_UPGRADE_PLAN.md)): `python3 -I tools/pin_check.py refs/gen1recomp v0.3.49 v0.3.85 --report local/reports/pin_check.json` classes every `file:line` citation of an engine source in this repository as same, shifted (stale numbers), changed (re-check the behaviour) or missing, reading the clone only through `git`. Exits 1 when a cited file is gone |
| `pytest.ini`, `conftest.py` | pytest configuration (`--e2e` option, markers) |
| `requirements.txt` | Exact pins (see `docs/VERSIONS.md`) |

## Protocol helpers

```sh
cd tools
.venv/Scripts/python.exe -m kb_protocol.catalog --check    # are the derived files current?
.venv/Scripts/python.exe -m kb_protocol.catalog --write    # regenerate after a schema change
```

## Data safety

The stand-ins and tests never contain ROM data: they use made-up maps, text and flags. Anything that
talks to the real game prints only message types, engine symbols, counts, lengths and short hashes;
ROM-derived fields are redacted through the `x-rom-derived` marks in the schemas
(docs/PLAN.md section 0.3). Reports go to `local/reports/`, which is git-ignored.
