# Kanto Bridge: progress

Where the execution of [PLAN.md](PLAN.md) stands. A new run reads this file and continues at the
first milestone that isn't **done**. Each milestone ends with `tools/check` green, this file updated,
new items in [PLAYTEST.md](PLAYTEST.md) and a commit titled `M<n>: <title>`.

## Milestones

| # | Milestone | Status | Date | Commit |
|---|---|---|---|---|
| M0 | Foundations, tooling and spikes | **done** | 2026-10-03 | `ccade90` |
| M1 | Bridge connection | **done** | 2026-10-03 | `M1: bridge connection` |
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

## Check results at the gates

- **M0:** `tools/check.sh --e2e`: pytest **545 passed** (protocol schemas and examples,
  `kb_protocol`, stand-ins, 179 Lua tests under lupa, 2 e2e), then `gradlew build`: **36 JUnit**,
  **11/11 GameTests**, mixin remap check, all green.
- **M1:** `tools/check.sh --e2e`: pytest **645 passed** (253 Lua, 4 e2e on the real engine:
  S5, the handshake, hot reload, the output filter), then `gradlew build`: **74 JUnit** (including the
  `BridgeClient` integration test against `fake_gen1`), **11/11 GameTests**, mixin remap check.
  The real-engine handshake test's filtered output:
  `net.listening port=27199` → `KB_OVERWORLD map=REDS_HOUSE_2F` → `session.established role=test`
  (hello.ok protocol 1, engine 0.3.49, game blue; 3 pongs; schema-valid snapshot) →
  `net.disconnected reason=replaced` → a second session with the same session id →
  `KB_QUIT sessions=2 code=0`.

## Deviations from the plan

**M0**

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

**M1**

8. **Hot reload uses a takeover slot**, not only `collectgarbage`: the first hot-reload e2e failed
   2 of 3 runs because the old instance's sockets closed only when a GC happened to run. Each
   instance now leaves a close function on the Game object (`kb_engine.takeOver`) and the next
   calls it on `game.ready` before binding. This is PLAN §4.1's "process-lifetime slot" pattern, and
   it is a **new engine internal** to re-check on pin changes. It also covers F5 and return to
   launcher.
9. **`snapshot.ingame`** (new required field): at the title screen there is no overworld, so
   `player` is only the save's last position (the boot skeleton's spawn on a fresh start). Clients
   must not move the player to it.
10. **gen1-bridge extras:** a spinner step is reported as kind `spin` already (trivial to detect);
    Seafoam currents and boulder pushes go out as `script`, and the Cycling Road roll as `bike`, until
    M3/M8. The snapshot carries the hidden party (no uids yet) and the current map's NPCs, because
    both were cheap. `debug.status` adds `ingame`, `port`, `accepted`, `sessions`, `steps`,
    `optionsPinned`. A snapshot warning is deferred to the end of the step so no `log` lands
    between `hello.ok` and the snapshot. Lines are flushed at the end of each step.
11. **`gen1-bridge/lib/kb_catalog.lua`** is generated from `protocol/catalog.json` by
    `tools/lua/gen_catalog.py` (a test fails while it is stale), so gen1-bridge checks legs and reply
    rules without parsing JSON at boot.
12. **The display-options pin (P21)** is applied in memory at `game.ready`, `save.created` and
    `save.loaded` (on this install it changed text speed 3 → 1 FAST). It is never written to the
    options file by the bridge.
13. **Client choices:** `send()` returns false and drops the message while not connected (callers
    resend from state after the next snapshot); messages before `hello.ok` are dropped; a `hello.ok`
    with the wrong `re` closes the link, and one with another protocol number counts as REJECTED;
    peer `error`s are never answered with an `error`. The status line sits top-left (title screen and
    in game), fades 5 s after connecting, and shows "not connected, retrying…" or "rejected (reason),
    retrying…" until the next handshake; F1/F3 hide it. Debug overlay key: **F7**. Client config:
    `<gameDir>/config/kantobridge-client.json` (`{"port": 27151}`), `-Dkantobridge.port` overrides.
14. **M2 preparation landed in M1** (protocol owner): the 10 export resource schemas
    (`protocol/export/`), the per-cell flag bits and bundle layout (PROTOCOL.md "Export resources"),
    the WorldIndex schema (`protocol/world/`), `fake_gen1`'s synthetic export (`synth.py`, served
    through `export.index`/`export.get`, and written as a bundle by `--write-bundle`). `fake_gen1`'s
    world now derives its walls, warps and signs from that export, and exit mats warp when you step
    off the edge, as in Gen 1.

## Open issues

- Harmless noise: the Kotlin plugin makes Fabric Loader warn "Class path entries reference missing
  files: build/classes/kotlin/…"; Cobblemon logs "No data fixer registered for cobblemon:*" and
  ".old File … corrupt or missing" for brand-new GameTest mock players.
- The GameTest server writes `eula=false` into `fabric-bridge/build/run/gameTest/eula.txt`; Fabric's
  GameTest mode skips the EULA check. No EULA was accepted by the agent.
- `runClient` has never been launched (by design); PLAYTEST M0 asks the user to check it once.
- `modkit lint` (PLAN P7 §2) isn't run: it executes tooling from `refs/gen1recomp`, which needs the
  user's OK. The real loader accepting the mod (S5, `mod_errors=0`) is the stronger check.
- A Python-backed real-TCP shim for lupa was judged unnecessary in M1: the strict lupa rig plus the
  real-engine e2e tests cover that path.
- M1's HUD line and debug overlay are only compile-checked (no client GameTests on 1.21.1); PLAYTEST
  M1 covers them.

## Blocked

Nothing.

## Play-test items waiting on the user

- PLAYTEST.md M0 (optional): the console line, `runClient` reaching the title screen with Cobblemon,
  `tools/check.ps1` on your machine.
- PLAYTEST.md M1: the status line and the F7 overlay following your walk in Gen1Recomp's window.
