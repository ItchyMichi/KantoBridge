# Kanto Bridge: progress

Where the execution of [PLAN.md](PLAN.md) stands. A new run reads this file and continues at the
first milestone that isn't **done**. Each milestone ends with `tools/check` green, this file updated,
new items in [PLAYTEST.md](PLAYTEST.md) and a commit titled `M<n>: <title>`.

## Milestones

| # | Milestone | Status | Date | Commit |
|---|---|---|---|---|
| M0 | Foundations, tooling and spikes | **done** | 2026-10-03 | `ccade90` |
| M1 | Bridge connection | **done** | 2026-10-03 | `fa15739` |
| M2 | World export and builder (Pallet Town and its interiors) | **done** | 2026-10-03 | `bbad914` |
| M3 | Walking and warps | **done** | 2026-10-03 | `aa725fd` |
| M4 | Dialogue, menus and NPC mirroring | **done** | 2026-10-03 | `c5b39d1` |
| M5 | Opening, Oak's cutscene, starter and rival battle | **done** | 2026-10-03 | `eb9c570` |
| M6 | Route 1, habitat pools, catching, party/Pokédex/bag sync | **done** | 2026-10-03 | `b603f16` |
| M7 | Viridian City (Pokémon Center, Mart, PC, money, trainers, saving) | **done** | 2026-10-03 | `46e5573` |
| M8 | The rest of Kanto (single player) | **done** | 2026-10-03 | wave 1 `20e43b9`, wave 2 `559daa9`, final `M8: the rest of Kanto` |
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
- **M3:** `tools/check.sh --e2e`: pytest **768 passed** (367 Lua; e2e on the real engine: S5,
  handshake, hot reload, export, the Pallet build, and the new walk: upstairs room → stairs → 1F →
  exit mat → Pallet Town → back in through the door → out again → into a wall: 24 steps, 24 answers,
  0 dropped, 4 warps (`stairs`, `edge`, `door`, `edge`), every message schema-valid), then
  `gradlew build`: **235 JUnit** (including a movement integration test against `fake_gen1`:
  join teleport, door warp, exit mat with `sync`+`n`, walk-out), **27/27 GameTests** (rules, warp
  service, build hold), mixin remap check (4 injector targets).
- **M4:** `tools/check.sh --e2e`: pytest **908 passed** (506 Lua; e2e on the real engine: S5,
  handshake, hot reload, export, walk, the Pallet build, and the new UI test: talk to an NPC (a
  2-page text box advanced to its close), the start menu (7 rows, wrapping), the trainer card on the
  mirror (78 frames, 160×144, 98 B–1.7 KB), QUIT's YES/NO answered NO then YES), then
  `gradlew build`: **322 JUnit** (including a UI integration test against `fake_gen1`: puppets from
  the snapshot, talk → text box → advance → close, UIs closed when the link drops), **27/27
  GameTests**, mixin remap check.
- **M5:** `tools/check.sh --e2e`: pytest **972 passed** (566 Lua; e2e on the real engine: all
  earlier ones plus the opening (NEW GAME via the mirror, both names typed on our naming screen, Oak's
  escort: 24 scripted steps, the escort flags), the lab rival battle won and lost (starter
  `party.give` → a made-up-uid `party.sync` → `battle.start` kind `rival` held in `busy`, re-sent
  with the same id after a reconnect → result applied; no blackout on the lab loss; progress flags
  3 → 6)), then `gradlew build`: **361 JUnit**, **40/40 GameTests** (battle proxy, exact moves and
  IVs, a real Showdown forfeit → `lose`, party give/PC overflow/sync, starter lock, Cobblemon
  settings, all 151 species and 165 moves resolving), mixin remap check.
- **M6:** `tools/check.sh --e2e`: pytest **1044 passed** (637 Lua; e2e on the real engine: all
  earlier ones plus Route 1: 201 grass steps with no native battle (`encounterGrass` 201, every
  battle counter 0), a made-up `dex.sync` (4 seen / 2 owned) and `party.sync` (2 members, uids)
  applied, `bag.sync` with only battle items, a refused key-item `bag.use`; and the real **Route 1
  build**: 7 maps, 4 habitat blocks, 46 pools, 0 warnings), then `gradlew build`: **387 JUnit**,
  **53/53 GameTests** (wild spawn with no AI and `pve`, outcome mapping, bag sync without echo,
  `bag.use`, stone/TM/Rare Candy effects, habitats configured, Cobblemon's spawner offering the
  synthetic pool), mixin remap check. The user's `saves/Kanto` was rebuilt in place with the Route 1
  set (player data kept).
- **M7:** `tools/check.sh --e2e`: pytest **1091 passed** (681 Lua; e2e on the real engine: all
  earlier ones plus Viridian: the nurse heal (`party.heal`, the hidden game's own save, then
  `save.begin{center}`), a start-menu save, `session.bound`, a Viridian Forest trainer's sighting →
  walk-up → `battle.start{trainer}` → win (defeated, money and flags up) and a second one → lose
  (blackout warp to VIRIDIAN_CITY, then `party.heal`, money halved), Bill's PC → `pc.open` →
  `pc.closed`, the player's PC native, a Mart buy and sell with exact money and bag changes; and the
  real **Viridian build**: 13 maps, 7 habitat blocks, 46 pools, 0 warnings), then `gradlew build`:
  **419 JUnit**, **63/63 GameTests** (PC sessions, saves, the stable world id, lose → heal → warp,
  the leave-during-battle regressions), mixin remap check (5 targets). The user's `saves/Kanto` was
  rebuilt in place with the Viridian set.
- **M8:** `tools/check.sh --e2e`: pytest **1224 passed** (796 Lua; e2e on the real engine: all
  earlier ones plus field moves (cut, surf, spinners, an elevator, Strength), 9 content scenes, the
  endgame (an Elite Four room, the Champion → Hall of Fame → credits, Snorlax, the Game Corner rocket),
  and the **full real build: 220 of 220 maps, 0 warnings, 0 overlaps, 0 unplaced, in 57 s**), then
  `gradlew build`: **479 JUnit**, **73/73 GameTests**, mixin remap check (6 targets). The user's
  `saves/Kanto` holds all of Kanto.
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
    Seafoam currents go out as `script` until M8. (Superseded in M3: boulder pushes are `push`, the
    Cycling Road roll is `slope`.) The snapshot carries the hidden party (no uids yet) and the current map's NPCs, because
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

**M3**

28. **Warp kinds follow the mechanism** (now in PROTOCOL.md "Movement rules"): leaving Red's house
    is `edge` (an exit mat), not `door` as the plan's e2e line said; `door` is a door tile or a gate
    doorway (covered by walking back in through the house door); `stairs` any other warp tile;
    `elevator` any warp out of an `*_ELEVATOR` map; Dig and Escape Rope report as `teleport` until
    M8's field moves can name them.
29. **One answer per `move.step`**, always carrying its `n` (PROTOCOL.md): a warp without a visible
    step answers with `player.pos` kind `sync`; a boulder push with kind `push` (implemented now
    rather than in M8); steps that can't run get `player.blocked` reason `script` (not idle, dropped
    from the 2-deep queue, no result within 12 logic steps, or at the title screen).
30. **Ledge hops** send one `player.pos` kind `ledge` at the landing cell with all 32 frames; the
    arrival's `to` is the entry cell, and a door walk-out follows as its own `player.pos` kind
    `script`. The order within a logic step is `player.state`, `warp`, then `player.pos`.
31. `debug.status` gains `move*`, `warp*` and `mapsEntered` counters; gen1-bridge now loads 16
    modules. `fake_gen1` mirrors these answer rules (exit mats answer with `sync` + `n`, kind `edge`,
    arrival on the door cell, then a scripted walk-out).
32. **Rules apply on every server running the mod** (GameTest server and non-Kanto worlds too), as
    PLAN §4.4 states them unconditionally. Fixed time is noon (6000).
33. **Gen 1 speeds are 0.1737 (walk) and 0.3474 (bike)** movement attribute: exactly 16 and 8 Gen 1
    frames per 2-block cell under Minecraft's ground physics (PLAN P2 said ≈0.174/≈0.347), checked by
    a physics simulation in JUnit. Sprinting is stopped on the client (a `LocalPlayer` mixin) as well
    as on the server, because a server can't stop a client from sprinting.
34. **Creative and spectator players are never followed or held**, so `/gamemode spectator`
    inspection keeps working; returning to Adventure teleports the player onto the hidden cell.
35. **Teleports and corrections:** joining, re-linking and a resync's snapshot (`ingame` only)
    teleport the player onto the hidden cell at any distance, with a kind-less `warp.request` (the
    client's own sync). Snap-backs, drift steering and door walk-outs are short ForcedMover walks (6
    frames per cell for snaps, 16 for steering); a player still not there 10 ticks after a move
    should have ended is teleported. Drift's "different map" means a different layer: neighbouring
    outdoor maps share one grid, so a seam is steered across, not teleported.
36. **Warps:** the `warp.request` goes out once the fade-out is complete, so the jump is never
    visible; fades are 250 ms (white for fly/teleport/dig/rope/pad/hole); timeouts 10 s for the
    arrival, 5 s for `warp.done`, 3 s for the destination's sections, each fading back in with a
    counted warning.
37. **InputLock** also holds the player during forced moves, at the hidden game's title screen
    (`ingame:false`, with a HUD hint), and when the hidden map isn't built in this world (HUD hint).
    It only applies in Kanto worlds (a WorldIndex is present).
38. **Intent timing:** `move.face` goes out when the player stands still with the camera quadrant
    off the hidden facing for 4 ticks (at most once a second); pushes repeat every 16 ticks while
    pushing, only when no step is waiting; the step timeout is 3 s (counted, warned at most every 10 s).
39. **A minimal `ClientStoryView` landed in M3** so bike speed works: surf/bike go to the local
    `StoryState` first, then `story.state` is relayed (full on join, snapshot and respawn, with `active`
    and `lit` empty until later milestones; deltas after). A malformed `story.state` changes nothing
    and gets `error{bad_message}`. `move.hold` (Cycling Road brake) is M8.

**M4**

40. **Text boxes run ahead locally:** a press at a stop moves on at once and sends exactly one
    `ui.advance`; a press while typing finishes the visible text; `ui.update` only moves a box forward;
    a final press without `ui.close` within 1.5 s may be resent. New optional `ui.open` fields
    `choice` (text: a choice follows, so no ▼ and no advance at the end) and `wrap` (menu/list
    cursor wrapping) close the gap where the client couldn't know a choice was coming.
41. **One Minecraft screen, several UIs:** the client keeps a stack of open UIs (a choice over its
    text box shows as one view; UIs underneath are drawn inactive, including a mirror's last
    picture); answers go only to the top UI, at most once until it changes. Safety nets: a dropped
    link closes every UI; each snapshot rebuilds them; if the hidden player stays idle for 0.5 s,
    anything still open is cleared (counted as stale); our screen lingers 150 ms after its UI closes
    so chained boxes don't flicker. Esc opens the pause menu and our screen comes back after. UIs show
    only while following the hidden game (a Kanto world, not creative or spectator).
42. **Interpretations the protocol leaves open:** Gen 1 menus wrap and lists stop at their ends
    (unless `wrap` says otherwise); left/right pages lists and steps quantities by ±10; Start closes the
    start menu; a click advances text and picks on menus. The A key fires only when the Minecraft
    player's cell matches the hidden player's; targets are 1–2 cells in a straight line (2 across a
    counter). Emote bubbles are white boxes with `!`, `?` or `♥` (not Gen 1 art). The mirror asks for
    30 fps. The dialog area is about half the window wide (1×–2× the GUI scale). The optional Gen 1
    bitmap font was skipped (Minecraft's font, `<PK>`/`<MN>` as small capitals).
43. **Puppets** are `kantobridge:puppet` entities (registered both sides, noSave, noSummon, tracking
    range 0, 1.75×1.9), spawned only on the client with negative ids; 2×2-block camera-facing
    billboards using the map palette's NPC sheet from the generated pack (the M2 pack writer needed no
    change: every object sprite on every map has a sheet); a neutral placeholder covers sprites
    changed at runtime through the mod API (Gen 1 content never does). `UseEntityCallback` and
    `UseBlockCallback` send `act.interact` and return FAIL; left-clicks on puppets are cancelled.
44. **gen1-bridge UI choices:** naming sets `glyphs` and then taps START so the screen's own
    `confirm()` runs from its update (not from our hook); with presets up it picks NEW NAME first, and
    a name equal to a preset picks that preset. PartyMenu stays a mirror screen (P10); the Pokédex
    list, the prize counter and the Fly list are list adapters. The ready gate is a superset of P2
    §3.3 (`stay.prompt` and `auto.promptFirst` boxes take one press at their end); `auto`/`stay` are
    sent only when the box's end takes no press. Text pages longer than 8 lines are split into
    protocol pages. The interaction retry follows a moving NPC to where it stops (`npc_moved` if out of
    reach). Seam targets are refused with `off_map`. NPC diffing pauses while no client is connected.
    gen1-bridge never fills `snapshot.ui`: open UIs follow the snapshot as `ui.open` messages (bottom
    first), then an `npc.set`.
45. **Mirror cost (real engine):** about 1.3–1.5 ms per capture on average (PNG encode about
    0.8 ms), 2.2–4.2 ms worst; frames are 98 B–1.7 KB; about 35% are identical and skipped; frame
    times stay at the 16.7 ms vsync budget with the mirror on or off. No PLAN §8 fallback was needed.

**M5**

46. **Proxy placement and removal:** the hidden proxy stands on the trainer's cell if the WorldIndex
    knows it within 32 blocks, otherwise 4 blocks in front of the player; it faces the player and
    carries the trainer's name; it is discarded once Cobblemon's recall finishes (10 s at most), at
    server stop, and when a crash left one in a loaded chunk.
47. **Cobblemon settings in memory** at `SERVER_STARTING` (Appendix C); `main.json` is never
    rewritten. `shinyRate` is 1e9 (infinity would overflow Cobblemon's fishing reroll, which converts
    the rate to an int and adds 1); passive statuses last 1e9 s. The starter lock lives in
    `CobblemonSettings`, called from `Rules`.
48. **Gifts** keep Cobblemon's random nature and ability (P11 covers trainer Pokémon only); `ot` only
    for trades; one application per `give` id. `party.take` handles trades only (daycare in M8);
    `party.item` is a counted "unsupported" until M6. A repeated move in a team or gift is kept once.
49. **No `party.sync` during a battle**; one follows each battle. **The client relay** answers a
    repeated `battle.start` from its record, never re-sends results unasked (gen1-bridge answers an
    unexpected result with `error battle.unknown`), answers `aborted` at once without a server link,
    aborts pending battles on leaving, queues up to 32 `party.*` requests while offline (flushed
    before any `battle.start`), and re-sends the latest `party.sync`/`dex.sync` after each snapshot.
50. **During any Cobblemon battle the client holds the player** (`InputLock` reason `BATTLE`):
    movement sync unlinks (relinking puts the player back on the hidden cell), our screens wait, and A,
    START and clicks do nothing. **M6 consequence:** Cobblemon's Run (walking away) can't work, so M6
    must end wild battles as `fled` on `BATTLE_FLEE_ATTEMPT`.
51. The `activity` attachment isn't persisted or synced to other clients yet (M9). Wild kinds are
    answered `aborted` (`unsupported_kind`) until M6.
52. **The opening needed no `intro.oak_speech.build` wrap:** M4's UI observer already turns both
    naming steps into `ui.open{naming}` answered by `ui.name`, and survives hot reload. Oak's escort
    was already reported correctly by M1–M4 (24 scripted player steps, Oak's NPC moves, the escort
    flags), verified on the real game.
53. **Scripted battles wait inside their script** on the runner's own `waitingCheck`, and the result
    goes back through `start_battle`'s checkpoint-resume path, so the engine itself sets
    `lastBattleResult`/`lastCheck` and runs `afterBattle`. Map trainers are deferred through
    `trainer.before_battle` with the world held through `ow.engaging`. Battles are taken over only
    while a client or test session is connected. The held battle and queued gifts/heals live in a
    process-lifetime slot on the Game object (`kantoBridgeState`, a **new engine internal**), so a
    hot reload keeps them.
54. **Gifts run natively** and are matched by dex and level when the sync arrives (no duplicate); an
    optional `give` field on `party.sync` entries would make the match exact (M6 candidate).
    gen1-bridge gives up on a gift after 5 syncs and 2 minutes, conservatively, because the client
    re-sends stale syncs after every snapshot. (M6 added the exact `give` match.)

**M6**

55. **gen1-bridge encounter rules** (now in PROTOCOL.md "Wild encounters, bag and Pokédex"):
    Pokémon Tower ghost battles without the SILPH_SCOPE run natively (P9's "unidentified ghost") with
    `battle.native{ghost}`; `battle.native` comes from watching the stack (so the old man's demo is
    announced too); the surf hook takes the engine's own roll and runs `encounter.species` and REPEL
    itself (returning nil would skip them) and holds the world with `ow.engaging`; fishing keeps the
    bite text and animation native and replaces only the native battle (with no client connected at
    the end of the cast, the engine's own verdict runs).
56. **Item use:** stones and Rare Candy skip their Gen 1 effect (the item is consumed, the party menu
    closes with the bag's white flash) and Cobblemon applies them; TMs/HMs are taught natively (the
    Gen 1 forget prompt is the slot picker), then `party.item` reports the move; a `party.sync` waits
    while a TM/HM is being taught. `party.item` has no id, so it's sent once and queued until a
    client connects (an id like `give` would make re-sending safe).
57. **`bag.sync`** follows every in-game snapshot and every change; `bag.use` of a non-mirrored item
    is refused with `bag.not_mirrored`. `dex.sync` is the only source of the Gen 1 Pokédex (the Gen 1
    side never marks it locally). gen1-bridge now loads 24 modules.

58. **Wild battles in Cobblemon** (`EncounterService`): the Pokémon appears on the given cell (or two
    cells ahead), stands still, can't be battled by anyone else, doesn't count toward the spawn cap,
    and gets **Cobblemon's own moves for its level** (the protocol's `wild` carries no moves); `pve`
    with flee distance 12 (none when `rules.flee` is false); `rules.catch` false makes it
    uncatchable. **Run ends the battle as `fled`, for every wild battle in a Kanto world** (the
    client holds the player still, so Cobblemon's walk-away flee can't work).
59. **Bag mirror:** 29 mirrored items; a matching sync changes nothing; items the mirror itself takes
    are never reported; uses during a battle wait until it ends (Cobblemon refunds unused items);
    in-flight uses are subtracted from crossing syncs and dropped after 60 s if no sync shows them.
    **Appendix B corrections** (checked against Cobblemon 1.8.1): `X_DEFEND` → `x_defence`,
    `X_SPECIAL` → `x_special_attack`.
60. **Habitats:** one pool per wild map of the whole export (like the slot map), handed to Cobblemon
    immediately (a new world datapack loads only on the next world load); blocks buried 2 below the
    ground with a greedy cover that always covers the first uncovered cell (about a third fewer
    blocks on route-shaped grass); checked at runtime as chunks load. **Interiors with a pool get the
    `kanto_cave` biome** (the `no_interior_spawns` rule would block them; the biomes look and sound
    the same), and a new spawn rule **`walking_level_only`** keeps spawns on floor blocks at a map's
    walking level (a block's 14-block range also covers tree tops, roofs and story cells). No PLAN §8
    fallback was needed: the synthetic GameTest runs Cobblemon's own spawner against the test grass.
    **Pokémon Tower gets no habitats or pools for now**, so it has no wild Pokémon once the player has
    the SILPH_SCOPE (the hidden game suppresses Tower encounters then) until M8.
61. **Client clicks:** only plain right-clicks on blocks (empty hand, block items, plain items)
    become `act.interact`; balls, medicine and other usable items pass through to Minecraft.
    `bag.sync` shares the party queue (party → bag → battle order; only the newest bag waits);
    `bag.use` is queued for the next snapshot while gen1-bridge is away.

**M7**

62. **Bill's PC:** `pc.open` opens Cobblemon's PC through our own `BridgePcLink` (away from any PC
    block; never during a battle) plus `OpenPCPacket`. Cobblemon has no "PC closed" event, but its
    PC screen removes the link on every way out, so the server checks the link each tick and sends
    exactly one `pc.closed`; refused opens are answered at once; a second open is ignored. The client
    holds movement and our screens while the PC is open, and if Cobblemon's screen never shows (or
    vanishes unanswered) it sends Cobblemon's own unlink packet and answers gen1-bridge itself after
    5 s. `pc.open` has no id: a close missed during a link drop answers a `pc.open` arriving within
    5 s of the next snapshot (an id would remove that guesswork).
63. **Saving:** `save.begin` saves the player's party and PC and other Cobblemon data (Pokédex, TM
    moves), then the world like `/save-all` without a flush, and answers `save.done{save, ok}`;
    `ok:false` on any exception, while a world holds players for a build, or under `/save-off`; one
    save per id. Cobblemon has no public per-player save, so an accessor mixin
    (`PokemonStoreManagerAccessor`, `remap=false`, not an injector) reaches its store list. A test
    world saves in 16–38 ms; a full world is untested (each save logs its time).
64. **Sessions:** the world id is a UUID in `<world>/kantobridge/session.json` (created on every
    world's first start, including non-Kanto, GameTest and headless worlds; an unreadable file is
    moved to `.broken`), which also records each player's paired slot. `multiplayer` is false only for
    the integrated server's own client. The protocol now relays `session.bind` S→C and
    `session.bound` C→S, so LAN guests and dedicated-server players pair too.
65. **Blackout:** `BattleService` needed no change; lose → (still fainted) → `warp.request{blackout}`
    → `party.heal` leaves the player teleported, healed and out of battle (GameTest). The `blackout`
    warp fades to black over 1 s and back over 0.5 s.
66. **The `writeSave` spike passed** (no PLAN §8 fallback): the nurse heal is spotted through the
    engine's `ow.healAnim` (only her heal sets it), `party.heal` goes out the same step, and the
    center save runs after her farewell lines, behind the engine's quick-save gate (not
    mid-dialogue). gen1-bridge never writes `lastHeal` itself.
67. **Mart money and clerk lines** use a same-id `ui.open` re-send instead of new `ui.update` fields
    (no schema change); the Java `UiRouter` already replaces in place. While a PC hold is active,
    answers to the PC menu wait (and time out with `ui.timeout`).
68. **A blackout lands in front of the Pokémon Center** (the fly spot of the last heal point's town,
    the engine's own behaviour), not inside it. Battle results restore the map's music
    (`Music.restoreMap`), as a battle screen's exit does; otherwise a trainer's sighting theme kept
    playing.
70. **A Cobblemon 1.8.1 crash hazard, fixed:** when a player stops seeing their own sent-out
    Pokémon, Cobblemon's `PokemonEntity.stopSeenByPlayer` (`PokemonEntity.kt:2193`) removes it on the
    spot, from inside Minecraft's `ChunkMap.removeEntity(player)` loop over the tracked entities, which
    corrupts fastutil's iterator (an NPE, only for some map layouts). A real server could crash when a
    player disconnects with a Pokémon out, or warps (or blacks out) right after a battle. The fix: a
    HEAD inject (`PokemonEntityMixin`, remapped to `method_5742`) cancels that in-loop removal of the
    owner's own untethered Pokémon and queues it (`DeferredRemovals`) for `END_SERVER_TICK`. Our own
    removals (proxies, encounter Pokémon) already ran outside the loop. GameTest cleanups now end the
    battle and let Cobblemon's recall finish before removing the mock player, and two regression
    GameTests (leave mid-battle, leave in the tick the battle ends) fail without the mixin and pass
    with it.
69. **Correction to deviation 12:** the engine's `SaveData.save` writes the live options to
    `options.lua` on every progress save, so the pinned display options and FAST text speed **are**
    persisted, since M4 on the player's own saves and now also on the center save (see Open issues).

**M8**, run in three waves, each ending green with an intermediate commit
(`M8 wave <n>: …`); the milestone commit `M8: the rest of Kanto` comes after wave 3:

- **Wave 1 (done):** the full build with clean report gates (items 1); the swap-site audit
  (2); per-player story cells (`ClientStoryView` active cells per PROTOCOL.md "Story rules (M8)",
  `StoryCellRenderer` closed art) and gen1-bridge's story diffs; forced movement (3); field moves
  from Minecraft (4); bike, surf and Flash feel (5); their e2e scenarios.
  Wave 1 Java results (builder, story cells, feel):
  - 71. **`swap_block_mismatch` ×22 was the builder being too strict:** the engine stamps the 20
    card-key doors and the 2 Rocket Hideout lift gates shut on map load, so the map data holds the
    site's `after` (open) block. The builder now accepts either block (noted `swap_site_stamped`).
    Swap cells are built per cell in their walkable state (normally `after`; a cell where only
    `before` is walkable keeps `before`, e.g. the S.S. Anne deck).
  - 72. **`interior_unplaced` ×1** came from `InteriorPacker.place` returning null when the parent
    already sat on the deepest layer; it now uses PLAN §8's fallback (the deepest layer, the nearest
    free spot outside every surface map's footprint, under the void fill; noted
    `interior_deep_spill`).
  - 73. **`build_diagnostics.json`** (beside every copy of the report): per warning code, rows of
    symbols only (map, parent, layer, kind, source, the site's index in the export); report fields
    `maps_unplaced` and `diagnostics`; `build_world.py` gates on `maps_unplaced == 0` and prints the
    diagnostics on failure.
  - 74. **Story cells:** the rules live in common `story/StoryRules` (shared with GameTests); tile
    cells close on their `closed.block` (WorldIndex) and use a new block `kantobridge:story_tile`
    with a block-entity renderer drawing the closed art per player (other story cells cost nothing
    per frame). The WorldIndex story-cell table gained `hidden`, `start`, `water` and `closed`
    (schema updated; golden updated for this change only).
  - 75. **Feel:** the surf sprite comes from the pack (`textures/player/<palette>/surf.png`), a
    billboard in third person and flat in first person, the surfer at Y 63.5; on the Cycling Road
    sneak sends `move.hold{b}` and doesn't crouch; Flash's Night Vision is applied by the server
    (`FlashService`) only on a WorldIndex-dark map the player's state has lit, never touching a
    potion's Night Vision.
  - **Not done in wave 1 (moves to wave 2):** the client side of field moves (right-click a cut tree
    or water → `act.field`; a Fly list); play-test-only: the closed art's lighting, a half-block gap
    under the S.S. Anne hull art, the surf sprite, the Cycling Road feel.
  Wave 1 Lua results (story diffs, swap audit, forced movement, field moves):
  - 76. **Story diffs** per PROTOCOL.md "Story rules (M8)": effective visibility through the engine's
    own `objectVisible`; live blocks against the map data copied at `mods.loaded` (before any map
    loads; the dataset view as fallback); flags every 10 logic steps, at script end and map load;
    an unexplained block change is counted and logged as `story.unknown_block`.
  - 77. **Swap-site audit: 95 sites** (cut 35, card-key 20, Victory Road boulders 4, scripted 36:
    Elite Four exits, Lance's doorway, the Mansion's 13 switches, the Cinnabar Gym gates, the S.S.
    Anne, …). One row was wrong and fixed (the Vermilion Gym door had `before` == `after`); `site()`
    now refuses no-op rows. One site matches neither block in the map data: AGATHAS_ROOM (2,0), which
    the engine overwrites on every entry (`story4.lua:870-873`), so the builder must accept it
    (wave 2).
  - 78. **Forced movement:** spinners `spin` (48 frames a cell), currents `current` (16), the
    Cycling Road `slope` (8, with the B brake), holes (including script-opened ones), pads, boulder
    pushes (`push` + `npc.move push`); warp kinds `dig`, `rope`, `teleport` told apart.
  - 79. **`act.field`** runs every action through the engine's own entry points after its own
    checks (`partyKnows`, the badge, the place); Escape Rope is `dig` + `item:"ESCAPE_ROPE"`; `fish`
    without an item uses the best rod; `fly` without `dest` opens the game's Fly list. Real-engine
    e2e: cut (regrows after leaving), surf, a spinner maze, an elevator, a Strength push.
  - Wave 1 gate: `tools/check.sh --e2e` **1154 passed**, `gradlew build` **451 JUnit**, **65/65
    GameTests**; **full real build**: all **220 of 220** reachable maps (184 interiors on 14 layers,
    1 deep spill, 5 sideways), 2,485,146 blocks in 2,126 chunks in 57 s, 12,664 story cells (160
    tile), 296 habitat blocks on 46 pools, slots 3,926 full + 92 half, 0 overlaps, **1 warning**
    (`swap_block_mismatch`: AGATHAS_ROOM, for wave 2).
- **Wave 2 (done):** static encounters (6); native special battles and the Safari crossover (7); gifts,
  in-game trades, the Name Rater, the Game Corner and prize counter (8); gyms, the Elite Four, the
  Champion, the Hall of Fame and credits (9); the daycare and battle-music polish (10).
  Wave 2 Java results:
  - 80. **Field-move clicks:** A or a plain right-click sends `act.field` (cut on a standing cut tree,
    surf on water, surf → dismount on land while surfing; A also triggers cut and surf, per P20's
    "right-click = A on targets", but never dismounts so shore signs stay readable); a
    `GameRenderer.pick` TAIL mixin lets the crosshair land on closed art as drawn (story blocks have
    no outline and a tree's upper half has no block).
  - 81. **Swap sites:** a `script` site matching neither block (AGATHAS_ROOM (2,0), overwritten by the
    engine on every entry) is built open with tile story cells (`swap_site_overwritten` note); other
    kinds must still match. VERMILION_DOCK needed no change (the hull is water with `water:true`).
  - 82. **Native battles** (Safari, old man, ghost) keep a mirror up between their own UIs (a UI of
    our own, id −1), hold the player, refuse A/START/clicks and report `activity` `battle`; cleared on
    `end`, a snapshot, a dropped link, or 1 s of the hidden player idle.
  - 83. **Daycare** (P15): a per-player store in `<world>/kantobridge/daycare/<uuid>.json` (Cobblemon's
    own JSON, written atomically before the Pokémon leaves the party; party and PC saved right after),
    withdrawals with 1 XP per step (level 100 cap); `party.take{daycare}` = deposit; `party.take`
    recalls a sent-out Pokémon first.
  - 84. **Battle music (P22):** the server clears `PlayerBattleActor.battleTheme` on
    `BATTLE_STARTED_PRE` for bridge battles and natural wild battles in Kanto worlds, so Cobblemon
    sends no theme (other worlds keep Cobblemon's music).
  Wave 2 Lua results:
  - 85. **Statics:** Red/Blue's static objects (Power Plant, the birds, Mewtwo) use hand-ported talk
    scripts ending in `static_battle`, so M5's script path already covers them; the talk-path branch
    (OC:3488-3510, no Red/Blue users), the ghost Marowak and Giovanni's hideout battle push their
    battles directly and are now held through a text box's wrapped `onDone` (shadowing `pushBattle`
    for that one synchronous call; no global patch, hot-reload safe). Giovanni goes out as
    `scripted`; an aborted direct battle ends as a flight.
  - 86. **Every native wild catch crosses over** as `native_catch` (not only Safari ones: also
    catches made with no client connected); trades as `party.take` + `party.give{trade}`; Name Rater
    renames; daycare deposits/withdrawals; the Hall of Fame heal. Gyms, the Elite Four and the
    Champion needed no change (checked against the engine). **P22 is implemented**: the engine's
    music API (`Music.playBattle`, `playVictory`, `restoreMap`) gives the Gen 1 battle themes during
    held battles.
  - Open: (the defeat lines and the endgame e2e were closed in wave 3); a
    trade or daycare change followed by a CONTINUE from an older hidden save leaves the games out of
    step (M10's save pairing); a traded member without a uid gets no `party.take` (counted).
  - Wave 2 gate: `tools/check.sh --e2e` **1207 passed** (783 Lua; e2e adds 9 real-game content
    scenes: a Power Plant static, the ghost Marowak, Giovanni's hideout battle, the Eevee gift, an
    in-game trade, the Name Rater, Brock's gym with its badge, a daycare deposit/withdrawal, a Safari
    catch crossover), `gradlew build` **479 JUnit**, **73/73 GameTests**; **full real build: 220 of
    220 maps, 0 warnings, 0 overlaps, 0 unplaced, 2,485,154 blocks in 56.5 s.**
  Wave 3 results:
  - 87. **Trainers' own defeat lines** (Silph Co Giovanni, the Game Corner rocket, the Karate
    Master, the Nugget Bridge recruiter, every gym leader's badge lines with their jingle) now show
    after a Cobblemon win: the continuation captured at deferral runs the engine's own start on a
    win, with `BattleState.newTrainer` and `pushBattle` shadowed for that one synchronous call (a
    stand-in keeps the substituted line, sound, page and the encounter's own `onFinish`); losses and
    aborts keep M5's path. Gym leaders' badge lines now come as the tagged defeat line before the
    prize (not repeated after it).
  - 88. **Endgame end to end on the real game:** an Elite Four room (Elite Four members don't sight
    the player: entering runs the walk-in, the battle starts on talking; the door opens; no heal
    between rooms), the Champion → the Hall of Fame (heal, save) → credits → the title, Snorlax with
    the flute (abort brings it back, a catch removes it), and the Game Corner rocket's line and exit.
    The Hall of Fame scene writes a save into the test install's own slot.
  - Wave 3 also made the e2e build gate the **full Kanto build** and rebuilt the user's `saves/Kanto`
    as all of Kanto (220 maps, 0 warnings, player data kept).
- **Wave 3 (done):** the remaining e2e scenarios (11), the full real build gate, rebuilding the user's world
  as all of Kanto, and the badge-by-badge play-test checklist.

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
- **For M10 (from M8 wave 2):** if Gen1Recomp is closed without saving after a daycare deposit, the
  hidden save still has the Pokémon in its party while the server keeps it in the daycare; the next
  `party.sync` drops it from the hidden party, so it's stranded in the daycare file. A protocol field
  (the hidden daycare's uid in the snapshot) would let the server return it.
- **Slot usage on the real export: 3,903 of 4,096 full-shape slots, 95 half** (the whole export, so
  the same for every build). It fits, but M8's closed-art overlays or new art kinds may need more
  families; rebalance before adding art.
- Closing the game during a "Create Kanto world" build leaves the half-built world (only the flow's
  own Cancel and failure paths delete it). A failed resource reload is detected heuristically (the
  reload future never completes on failure).
- M3's `Rules` service ("Adventure on join") must skip players held by `BuildHold.isHeld(player)`.
- The "Create Kanto world" flow is compile- and unit-tested only; it builds all of Kanto (PLAYTEST M2
  optional item).
- The step driver's re-press after a focus change (`Input:reset`) is tested only in simulation: no
  focus change happened during the real runs.
- M3 movement is unit-, GameTest- and stand-in-tested, but **only the play-test can judge the feel**:
  speed match with Gen1Recomp's window, Minecraft's acceleration, corner cuts, ledge hops, the warp
  fade and destination-ready check, the camera turning on warps, the walk-out during the fade-in.
  Until M4's puppets exist, walking into an NPC's cell is refused and snapped back (repeatedly while
  the key is held). A pause menu held during a warp can trip a timeout (it fades back in with a
  warning).
- **For M6:** M4's `UseBlockCallback` returns FAIL for every block click while the player follows
  the hidden game, whatever they hold, which would stop Cobblemon Poké Ball throws aimed at blocks.
  M6 must let item use through (only plain right-clicks become `act.interact`).
- M4's screens, puppets (facing, walking, collision, crosshair), emotes and the mirror picture are
  only unit-tested; the play-test judges their look and feel.
- **M5 battles, play-test only:** the trainer's throw animation is invisible (the proxy is hidden);
  its Pokémon appear at Cobblemon's send-out spot and may clip into walls in small rooms like Oak's
  lab; Cobblemon's NPC battle theme plays (P22 in M8); a sent-out Pokémon pushing the player off
  their cell is fixed by a fade and teleport afterwards. If Cobblemon's battle engine crashes it
  writes `battle_logs/<id>.txt` (with names) into the run folder (`local/minecraft`, or
  `fabric-bridge/build/run/gameTest` for GameTests: both git-ignored).
- **Question for the user (from M7):** because Gen1Recomp writes its live options on every save,
  gen1-bridge's pinned display options (classic: no voxel, tilt, zoom or wide battle) and FAST text
  speed end up in `local/gen1recomp/options.lua`. If you also play Gen1Recomp without the bridge,
  you'll see those settings there too. The bridge could restore your own options on exit, if you
  want that.
- `pc.open`/`pc.closed` have no id, so a stale `pc.closed` could end a later hold (an id would make it
  exact; M9 candidate). The center save relies on the `healAnim` heuristic (fine for Red/Blue).
- **For M8 (from M6):** Pokémon Tower habitat pools must stay off until the player has the
  SILPH_SCOPE (the hidden game runs its own ghost battles there; otherwise they'd double up); Safari
  catches stay in the Gen 1 party/box until M8 crosses them over as `native_catch` gifts; a native
  item effect (no client connected, or a member without a uid) consumes a stone without a Cobblemon
  evolution, matching the battle rule; `SAFARI_BALL` is mirrored but never appears (Safari balls live
  in `save.safari`).
- **Battles not intercepted until M8:** talk-path static objects (OC:3488-3510), direct
  `pushBattle` calls (Giovanni `story3.lua:481-510`, the ghost Marowak `story3.lua:143-194`).
  Not handled yet: trades, native catches (`catch.nickname` → `native_catch`), the Name Rater, the
  Pokémon Center heal (M7). Loss lines that `engageTrainer` callers pass in themselves (e.g. the Game
  Corner rocket) aren't shown. The map-trainer path is covered only in lupa until M7's e2e.
- NPCs on neighbouring maps (the engine's "ghosts" across a seam) aren't mirrored, so the previous
  map's puppets vanish when you cross. The Pokédex list adapter has no field for owned markers. At the
  title, TitleState and the intro arrive as mirror UIs and the title menu as a menu; battles are mirror
  UIs until M5/M6.

## Blocked

Nothing.

## Play-test items waiting on the user

- PLAYTEST.md M0 (optional): the console line, `runClient` reaching the title screen with Cobblemon,
  `tools/check.ps1` on your machine.
- PLAYTEST.md M1: the status line and the F7 overlay following your walk in Gen1Recomp's window.
- PLAYTEST.md M2: look around the headless-built `Kanto` world (Pallet Town, the underground
  interiors, textures); optionally the experimental `KantoFullE2E` and the "Create Kanto world"
  button.
- PLAYTEST.md M3: walk Pallet Town and its houses with Gen1Recomp following; stairs and doors.
- PLAYTEST.md M4: talk to people and signs, the start menu, the bag, YES/NO, the mirror, puppets.
- PLAYTEST.md M5: a new game from Minecraft: names, Oak's escort, the starter, the rival battle.
- PLAYTEST.md M6: wild Pokémon on Route 1, catching, the Pokédex, Poké Ball and Potion counts.
- PLAYTEST.md M7: the nurse and saving, the Mart, Bill's PC, a trainer, one blackout, save/reload.
- PLAYTEST.md M8: the whole story, badge by badge, with field moves and the endgame.
