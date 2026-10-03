# Kanto Bridge: progress

Where the execution of [PLAN.md](PLAN.md) stands. A new run reads this file and continues at the
first milestone that isn't **done**. Each milestone ends with `tools/check` green, this file updated,
new items in [PLAYTEST.md](PLAYTEST.md) and a commit titled `M<n>: <title>`.

## Milestones

| # | Milestone | Status | Date | Commit |
|---|---|---|---|---|
| M0 | Foundations, tooling and spikes | **done** | 2026-10-03 | `M0: foundations` |
| M1 | Bridge connection | not started | | |
| M2 | World export and builder (Pallet Town and its interiors) | not started | | |
| M3 | Walking and warps | not started | | |
| M4 | Dialogue, menus and NPC mirroring | not started | | |
| M5 | Opening, Oak's cutscene, starter and rival battle | not started | | |
| M6 | Route 1, habitat pools, catching, party/Pokédex/bag sync | not started | | |
| M7 | Viridian City (Pokémon Center, Mart, PC, money, trainers, saving) | not started | | |
| M8 | The rest of Kanto (single player) | not started | | |
| M9 | Multiplayer | not started | | |
| M10 | Reliability, performance and launch | not started | | |

## Pre-flight (PLAN §0.6)

| Step | State |
|---|---|
| 1. ROM imports | Done by the user in both installs. Listing folder names shows a completed **Blue** import in each (`blue/` with its completion marker), so the game is Blue. |
| 2. Console check | The user ran it from PowerShell inside the install folder as `.\gen1recomp.exe --console --game=blue`: PowerShell needs the `.\` prefix there, and the game is Blue, not Red. Whether the `[gen1-bridge] gen1-bridge loaded` line appeared wasn't reported (PLAYTEST M0 asks). Automation doesn't depend on it: the e2e runner reads piped stdout, and S5 saw the line that way. |
| 3. Minecraft EULA | `local/minecraft/eula.txt` exists (created by the user; never opened or written by the agent). |
| 4. Defaults §1.2 | Unchanged. |

## Spikes (M0)

| Spike | Result | Evidence |
|---|---|---|
| S1: Cobblemon in the GameTest server, mock player | **Pass**, after two dev-environment fixes (see Deviations) | GameTest log: "Showdown has been started!", "Loaded 1025 Pokémon species"; dex 1–151 resolve through Cobblemon's API; a mock `ServerPlayer` joins with an empty Cobblemon party |
| S2: tile-block registration and memory | **Pass** | 32 families × 256 states = 8192 states ("S2: 32 tile blocks, 8192 tile states registered"); full blocks 1.0 high, half 0.5; measured heap cost about 10.1 MB (vs about 95 MB for one 0..255 property) |
| S3: resource-pack injection mixin | **Pass** | JUnit confirms the client mixin applied to `BuiltInPackSource` in dev; in the remapped jar `loadPacks` → `method_14453`. Loom 1.17 writes no refmap, so the new `verifyMixinRemap` task (part of `check`) fails the build if any injector target keeps its Mojang name. The pack-source logic is unit-tested (no folder, invalid `pack.mcmeta`, valid pack required and on top) |
| S4: lupa + sandbox parity + fake socket | **Pass** | `tools/lua/test_lua_s4_spike.py`: modules load in the sandbox copy; `socket` comes through the sandbox `require` (network permission gate exercised); a ping split in two reads gets its `pong` (`re=1`) through a 10-byte send window; without `network` nothing listens; no global leaks |
| S5: e2e runner on the test instance | **Pass** | Only `local/gen1recomp-e2e` launched, Blue auto-detected. Filtered output: `[info] [gen1-bridge] gen1-bridge loaded`, `[info] loaded mod gen1-bridge 0.1.0`, `[info] [gen1-bridge] game.ready api=2 engine=0.3.49 modules=6`, `KB_READY state=loaded engine=0.3.49 ... modules=6 mod_errors=0`; exit 0 in about 1 s; crash log untouched |

## Check results at the M0 gate

- `tools/check.sh --e2e`: pytest **545 passed** (protocol schemas and examples, `kb_protocol`,
  stand-ins, 179 Lua tests under lupa, 2 e2e), then `gradlew build`: **36 JUnit**, **11/11
  GameTests**, mixin remap check, all green.

## Deviations from the plan

1. **Protocol v1 vs the §3 draft** (all listed in PROTOCOL.md "Changes from the draft"): `re` lives
   only in the envelope; `save.done` carries the `save` id; a few optional fields added
   (`move.step.push`, `player.pos.n`, `player.blocked.n`, `act.field.item`, `ui.update.qty`,
   `party.give.dvs`, `debug.quit.code`); `daycare.*` also travel C→S; `party.sync`'s and the
   snapshot's `nick` are marked ROM-derived; the relay-only types are fully defined.
   `protocol/redaction-vectors.json` was added (derived, like `catalog.json`) so Java's `Redact` is
   tested against Python's output exactly.
2. **`fake_gen1`, `fake_minecraft` and `kb_protocol.aio` landed in M0**, ahead of M1, with 13
   contract tests. M1 still owns their JUnit/e2e use.
3. **Two dev-environment fixes for S1** (recorded in VERSIONS.md): the Kotlin Gradle plugin 2.2.20
   (no Kotlin sources; Loom remaps Kotlin metadata only with it) and ICU4J 71.1 on the dev server
   and GameTest classpaths only. Both use versions already pinned through Cobblemon. Neither changes
   what players get.
4. **Cobblemon recipe overrides** (PC, healer, pasture) win only because Fabric orders mod data packs
   by id (`kantobridge` after `cobblemon`). A GameTest checks the three recipes are gone and an
   unrelated one survives.
5. **Lua details:** `kb_json` encodes an unmarked empty table as `[]` (maps that may be empty use
   `json.object()`); it also rejects duplicate keys and integers beyond ±2^53. `kb_log`'s safe-id
   pattern allows `/` so `file.lua:NN` locations print. The sandbox copy in `tools/lua` leaves out
   the engine's legacy compat shims, so any use of them fails tests.
6. **pytest runs with `-p no:faulthandler`**: LuaJIT on Windows raises an SEH exception for every Lua
   `error()`, which faulthandler printed as "Windows fatal exception".
7. **The e2e output filter** passes only `KB_` markers, our own `[gen1-bridge]` log lines and the
   engine's "loaded mod gen1-bridge" line; other engine lines that mention the mod show only their
   `file.lua:NN` locations (`KB_ENGINE_NOTE`).

## Open issues

- Harmless noise: the Kotlin plugin makes Fabric Loader warn "Class path entries reference missing
  files: build/classes/kotlin/…"; Cobblemon logs "No data fixer registered for cobblemon:*" and
  ".old File … corrupt or missing" for brand-new GameTest mock players.
- The GameTest server writes `eula=false` into `fabric-bridge/build/run/gameTest/eula.txt`; Fabric's
  GameTest mode skips the EULA check. No EULA was accepted by the agent.
- `runClient` has never been launched (by design); PLAYTEST M0 asks the user to check it once.
- `modkit lint` (PLAN P7 §2) isn't run: it executes tooling from `refs/gen1recomp`, which needs the
  user's OK. The real loader accepting the mod (S5, `mod_errors=0`) is the stronger check.
- A Python-backed real-TCP shim for lupa (gen1-bridge in lupa ↔ fake_minecraft) is deferred to M1.

## Blocked

Nothing.

## Play-test items waiting on the user

- PLAYTEST.md M0 (optional): the console line, `runClient` reaching the title screen with Cobblemon,
  `tools/check.ps1` on your machine.
