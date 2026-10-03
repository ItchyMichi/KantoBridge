# Kanto Bridge: progress

Where the execution of [PLAN.md](PLAN.md) stands. A new run reads this file and continues at the
first milestone that isn't **done**. Each milestone ends with `tools/check` green, this file updated,
new items in [PLAYTEST.md](PLAYTEST.md) and a commit titled `M<n>: <title>`.

## Milestones

| # | Milestone | Status | Date | Commit |
|---|---|---|---|---|
| M0 | Foundations, tooling and spikes | **done** | 2026-10-03 | `ccade90` |
| M1 | Bridge connection | **done** | 2026-10-03 | `fa15739` |
| M2 | World export and builder (Pallet Town and its interiors) | **done** | 2026-10-03 | `M2: world export and builder` |
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
- **M2:** `tools/check.sh --e2e`: pytest **704 passed** (304 Lua; e2e on the real engine: S5,
  handshake, hot reload, the export, and the real Pallet build), then `gradlew build`: **172 JUnit**
  (including `BridgeClient` and `ExportClient` integration tests against `fake_gen1`),
  **18/18 GameTests** (the synthetic world build, story-cell and ledge collision, the build hold),
  mixin remap check. **Real export:** 359 resources, 762,457 bytes, in 9 s through `ExportCli`.
  **Real Pallet build** (report, counts only): `maps_built 5`, `overlaps 0`, `edge_mismatches 0`,
  `warnings 0`, `story_cells 24` (8 object, 16 water), 96 story-cell blocks, 17,186 blocks in 20
  chunks, 4 interiors on 4 layers, 78 light blocks, `pack_textures 4273` (3,989 tile, 284 NPC, 23
  animated), slots 3,903 full + 95 half, world index written; planned in 5.2 s, placed in 0.7 s.
  The user's play-test world `saves/Kanto` was built the same way.
- **Early full-Kanto smoke build** (not an M2 gate; M8 owns it), `build_world.py --maps all` into
  `saves/KantoFullE2E`: 219 of 220 reachable maps (36 surface, 183 interiors on 14 layers, 5 moved
  sideways), **2,480,041 blocks in 2,126 chunks in 59 s** (plan 5.9 s, place 53 s), 12,539 story
  cells (9,305 water, 2,439 boulder area, 760 object, 35 tile), 3,032 ledge colliders, 12,033 lights,
  0 overlaps, 0 edge mismatches; the **same pack and slot-map hashes as the Pallet build** (the filter
  doesn't change them). **23 warnings for M8:** `interior_unplaced` ×1 (one interior found no
  room) and `swap_block_mismatch` ×22 (swap sites whose `before` block doesn't match the map). The
  builder logs codes and counts only; M8 needs a symbols-only diagnostic naming the maps.

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

**M2**

15. **The export reads through `mod.datasets` (PLAN's primary path; the §8 fallback wasn't needed)**
    and is slow by nature: every record read costs about 5 ms because `DatasetViews:_ready`
    (`src/mods/DatasetViews.lua:209-243`) re-inspects the cache on every read. The full build takes
    about 8 s of game time (474 steps, longest step about 175 ms while the engine decodes a cache
    module), so the first `export.index` answers after 10–15 s of wall time; fetching all resources
    then takes 0.17 s. **Decision: keep the datasets path**: it is a one-off wait at world
    creation, and it gives the pristine game data (other content mods stay off in the hidden game,
    DESIGN §2). Copying the live `Data` before any map loads would be faster; it remains the
    documented fallback.
16. **Real export, counts only** (`local/reports/export_report.json`): 359 resources (map 222,
    tileset 24, image 34, sprite 73, plus palettes, encounters, field, names, layout, swaps), about
    763 KB, largest line 32.6 KB (`encounters`), deterministic across runs, 0 warnings. 36 surface
    maps, 186 interiors (184 with a parent: COLOSSEUM and TRADE_CENTER have none), layout box 170×180
    blocks. **The real data has 78 directed connection edges, not the 72 PLAN §4.5 states**; all
    agree, none overlap. 59 swap sites (35 cut, 20 card-key, 4 scripted).
17. **Export semantics chosen in M2** (now in PROTOCOL.md): pad/hole bits only on warp-entry
    cells (elsewhere those tiles are floor art); the spinner bit marks `field.spinners` cells; the
    warp-tile bit is the tileset's `warp` list; a cut tree is a tree tile in a block that
    `cutTreeSwaps` swaps; `lastMap` is null when it isn't a single outdoor map; interiors take their
    own palette cascade (e.g. CAVERN → CAVE) before the remembered outdoor map's; images also cover
    `fx.*` (overworld effects, emotion bubbles); the field tables are a fixed list of 29.
    Left out for later milestones: cuttable tall grass ($52), script-only block swaps (gym barriers,
    Elite Four doors, Mansion switches, S.S. Anne: M8's audit), ADVANCED per-tile palettes, trainer
    parties (built at battle time instead), text fields.
18. **Story-cell collision by type:** `object` and `tile` cells close while active in the player's
    `StoryState`; `water` closes unless the player surfs (or the cell is active); `boulder_area`
    closes where a boulder stands. A player with no state yet is blocked by water only.
19. **Swap sites are built open** (cut trees, card-key doors); story cells close them per player,
    and the closed art is already in the slot map for M8's per-player overlay.
20. **Builder geometry choices:** heights re-implemented from D_voxel's documented measurement rule
    and rounded to walls 2 and roofs/cliffs 4 (synthetic art is uniform, so its roof measures 2);
    the void fill is a 12-block ring of 3-high border art around the surface maps; every interior
    gets a 1-block border-art ring from floor to ceiling plus a ceiling (Gen 1 interior floors run to
    the map edge, so PLAN's "fully enclosed" needs it); up to 4 chunks may wait on the light engine
    before placement pauses (instead of strictly one).
21. **Layout checks are counted, not fatal:** edge mismatches and overlaps go into the report as
    counts and warning codes (the gate requires both to be 0).
22. **Unreachable maps** (COLOSSEUM, TRADE_CENTER) are left out of a full build with a note and not
    counted in `maps_requested`; requesting one by name is a warning. Items standing on solid cells
    (tables) get no story cell, only a note.
23. **Animations:** water = the tileset's water tiles minus shore; flowers = tile 3 (the engine's
    default) with frames from the `tilesets.flower1..3` images. An explicit animated-tile list in the
    tileset export would remove that assumption (open issue).
24. **Fixed formats:** NPC sheets 64×32; `world.index` parts 64 Ki characters; slot map and pack
    layout documented in PROTOCOL.md (`protocol/world/slot_map.schema.json` added). The synthetic
    build's WorldIndex is a golden file that a GameTest reproduces byte for byte and a pytest checks
    against the schema. Slot map and pack depend on the whole export, never on the build's filter.
25. **Headless build:** `./gradlew runServer -Pkb.build=<bundle> -Pkb.buildMaps=<ids>
    -Pkb.buildReport=<file> -Pkb.buildExit=true --args=nogui` (the build starts on
    `SERVER_STARTED`, writes into the server's own world folder, and exits 0 or 1);
    `tools/e2e/build_world.py` drives export + build + report checks, building automated runs into
    `saves/KantoE2E` so they never touch the user's `saves/Kanto`.
26. **ExportClient and ExportCli:** the export runs on its own short `builder` connection, which
    replaces the player's main link, so the main `BridgeClient` gets a `PAUSED` state
    (`pauseReconnects`, nestable; the HUD shows "exporting…") and reconnects + resyncs afterwards.
    The bundle's `index.json` is pretty-printed (so `ExportCli` and `fake_gen1 --write-bundle` agree
    byte for byte); resource files are canonical. Index checks are stricter than the protocol: ids
    must be file-safe and unique ignoring case; an empty index is rejected. `ExportCli` gives the
    JVM its own log4j2 config (warnings to stderr) so its stdout carries only the `export:` line.
    Optional `-Pkb.exportTimeout` (default 120 s).
27. **"Create Kanto world" flow:** hands the build to the integrated server through a one-shot
    `BuildRequest` (world folder, bundle, all maps, pack already written by the client) claimed on
    `SERVER_STARTED` by a non-dedicated server whose world folder matches (expires after 5 min,
    never in GameTests); the same start path as the headless trigger. **Players who join during the
    build are held as spectators** at the void spawn behind the progress screen (`BuildHold`),
    otherwise they would fall before any ground exists; they get their game mode back and are
    teleported to the spawn when the build ends (also restored before any save). On failure or
    Cancel the flow leaves the world and deletes the folder it created. The flow's world has
    **commands enabled** (to let the user inspect interiors with `/gamemode spectator`; easy to turn
    off), structures and bonus chest off, a random seed. `server/KantoGameRules` holds Appendix D's
    rules for M3's `Rules` service.

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
- **Slot capacity** is 4,096 per shape (16 families). The builder estimated 3,000–4,000 full-shape
  keys for the whole real export; if it overflows, the build fails with `slot_map.full` and the fix
  is rebalancing the 32 families (e.g. 28 full + 4 half). The real report's `slots_full`/`slots_half`
  show the usage.
- Forest and Safari maps get ceilings like any interior (M8).
- **Slot usage on the real export: 3,903 of 4,096 full-shape slots, 95 half** (the whole export, so
  the same for every build). It fits, but M8's closed-art overlays or new art kinds may need more
  families; rebalance before adding art.
- Closing the game during a "Create Kanto world" build leaves the half-built world (only the flow's
  own Cancel and failure paths delete it). A failed resource reload is detected heuristically (the
  reload future never completes on failure).
- M3's `Rules` service ("Adventure on join") must skip players held by `BuildHold.isHeld(player)`.
- The "Create Kanto world" flow is compile- and unit-tested only; it builds all of Kanto (PLAYTEST M2
  optional item).

## Blocked

Nothing.

## Play-test items waiting on the user

- PLAYTEST.md M0 (optional): the console line, `runClient` reaching the title screen with Cobblemon,
  `tools/check.ps1` on your machine.
- PLAYTEST.md M1: the status line and the F7 overlay following your walk in Gen1Recomp's window.
- PLAYTEST.md M2: look around the headless-built `Kanto` world (Pallet Town, the underground
  interiors, textures); optionally the experimental `KantoFullE2E` and the "Create Kanto world"
  button.
