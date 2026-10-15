# The Gen 2 bridge: Johto in Kanto Bridge (MG9's plan)

Gold, Silver and Crystal run on Gen1Recomp's second engine (`gs` for Gold and Silver, `crystal` for Crystal; family
`gen2`, region `johto` in `protocol/games.json`). This plan does for them what GEN3_BRIDGE_PLAN.md and
HOENN_PASS_PLAN.md did for Emerald: inspect the engine, then build the `gen2` engine adapter (the hidden game followed and
driven from Minecraft), the Gen 2 export and builder (Johto built as a region of the one flat world), and the pass that
puts Johto beside Kanto and Hoenn.

It rests on a static inspection of the engine's source (2026-10-15) written up in five research notes with verified
`file:line` citations, kept git-ignored beside the other research (`refs/_research/`), and on the runtime checks of §2,
which come first:

| Note | Covers |
|---|---|
| `g2_mod_platform.md` | the mod platform on a Gen 2 boot: loading, every hook and event the bridge uses and where Gen 2 raises it, registries, `mod.world`, the script VM's seams, the Union Room, the Wild Routes exit, upstream's maturity |
| `g2_world_state.md` | identity, the tick, observe, input, verdicts, warps, interaction, story state, saving, title and boot, parking, healing, the clock, field interrupts, lifetimes |
| `g2_ui_battles_party.md` | the UI model and catalogue, text, the one battle seam and how to hold it, party data and every path that adds a Pokémon, the bag, money and Mom's savings, the modes that stay native |
| `g2_export_data_model.md` | where the data lives, the map record, tilesets, the collision vocabulary, palettes and time of day, how to bake the art, sprites, wild data, placement, terrain changes, a capacity estimate |
| `g2_kb_reuse.md` | our side: what the adapter, the core, the protocol and the Java builder can reuse as-is, extend, or need new; capacity; tests and tools |

Paths: `R/` = `refs/gen1recomp` (Gen1Recomp v0.3.85, the pin), `GB/` = `gen1-bridge/`, `J/` =
`fabric-bridge/src/main/java/dev/kantobridge/`, `P/` = `protocol/`, `T/` = `tools/`.

## 0. How to execute this plan

- **Phases** (§11): **G2S** the inspection on a pod (spikes and a measurement, §2), then **G2A–G2G** as Emerald's
  G3A–G3G (followable, UI, story, export, builder, battles/party/bag, the real build), then **G2H** the pass (Johto in one
  world with Kanto and Hoenn). Each phase is a commit or a few, titled `MG9 G2<x>: …`, recorded in PROGRESS.md
  ("Johto (MG9)") with "As built" notes added here, as MG7 did.
- **Tests**: lupa tests per part on a Gen 2 fake engine; the full suite at the ✔ checkpoints of §11; e2e on the pod's
  Crystal import at G2A, G2B, G2C and G2F. Pods per the standing rule: small 22-only pods, stopped after each job.
- **Defaults**: §12 lists decisions J1–J14. Each proceeds with its default unless you change it. J3–J6 (the world space,
  map names, the clock, time of day) are the ones worth a look first.
- **The pin stays v0.3.85.** Upstream released v0.3.86–v0.3.88 since; for Gen 2 they changed only the Pokédex text
  (`R/src/core/gen2/PokedexText.lua`).
- **Rules that hold throughout** (CLAUDE.md): only `GB/lib/engine/**` touches engine internals; nothing ROM-derived in
  the repository, logs or test output (types, ids, counts and hashes only); the agent never imports a ROM (Crystal was
  imported through the upload page on 2026-10-13; Gold or Silver would need your upload, J2); tests prefer the stand-ins.

## 1. What the inspection of the code found

**Gen 2 sits between Gen 1 and Gen 3.** It is a second engine (about 100,000 lines: `R/src/core/Game2.lua`,
`R/src/{core,world,script,ui,battle}/gen2/`), and one engine for both ids: Crystal is about 48 small branches, not a
fork. Where the bridge reads it, it is mostly Gen 1-shaped:

| | Gen 1 | **Gen 2** | Gen 3 |
|---|---|---|---|
| Step verdicts | `movement.collision` | **`movement.collision`, Gen 1's contract** (also raised for NPCs: filter the mover) | none (adapter wraps `tryMove`) |
| Dialogue | shared TextBox/ChoiceBox on the StateStack | **the same shared classes and stack**; other screens `Gen2<Name>` by `screenId` | scattered state, no `screen.pushed` |
| Mirror | 160×144 | **160×144** (`render.output`) | 240×160 |
| Map geometry | 32-px blocks of 4×4 tiles, connections in blocks | **the same** (byte block ids, cells are block quadrants, 1-based warps) | cells, metatiles, elevation |
| Collision | tile lists | **a `COLL_*` byte per cell** (the vocabulary Gen 3's seeded codes borrowed, but wider) | the seeded byte |
| Art | 4 shades + one SGB palette per map | **GBC colour**: per tile one of 8 palettes, chosen by environment × time of day, roofs per map group | indexed atlases, 16 palettes |
| Terrain changes | whole blocks (`story.blocks`) | **whole blocks**, ids ≤ 255 | per-cell overrides (`story.cells`) |
| Flags | `EVENT_*` names | **numbers**; names only for Gold | numbers (names from the engine) |
| Scripts | hand-ported Lua | **the cart's bytecode in a VM**; `script.command` sees every op | the cart's bytecode |
| Parking the hidden player | a hold state pushed on the stack | **no field lock, but any stack state freezes the whole field step**, so Gen 1's hold state carries over | `Field.lock` |
| Battles | `trainer.before_battle` defers trainers; other seams for the rest | **one seam, `World:startBattle`**, no headless or abort API | `BattleBridge.start` headless + `Battle.abort` |
| Clock | none | **a real-time clock** (palettes, encounters, who stands where, phone calls, weekly events) | not read by the bridge |

The facts the design needs (citations in the notes):

- **Loading.** A mod loads on Gen 2 only if its manifest's `games` names `gold`/`silver`/`crystal` or `gen2`
  (`R/src/mods/ModTargets.lua:39-53`); today's `GB/manifest.json` (red, blue, emerald) is **not loaded on Crystal at
  all**. `mod.world` is the Gen 2 WorldAPI, `mod.input` the shared one; a Gen 2 adapter may require `src.*.gen2.*`
  modules directly (the cross-generation denial only stops other generations', `R/src/mods/Loader.lua:129-150`).
- **Hooks and events.** Raised on Gen 2: `movement.collision`, `script.command` (with a fifth argument, the decoded
  op; `R/src/script/gen2/Vm.lua:1824-1851`), `screen.pushed/popped`, `render.output`, `world.stepped` (its `tile` is the
  cell's COLL byte), `map.entered` (via `boot`, `warp`, `connection`, `continue`), `player.warped` and
  `warp.destination` (walked warps only; the hook can redirect), `world.interacted`, `world.blacked_out`, `save.*`,
  `input.step`, `game.ready`, `flag.changed` (a bare number), `encounter.roll` (its first argument is the table set, and
  headbutt, Rock Smash and roamers bypass it), `encounter.fishing`, `battle.started/ended` (twice per battle: model and
  screen), `pokemon.caught`, `egg.hatched`, and Gen 2's own `world.block_replaced`, `world.tod`, `phone.call_received`,
  `intro.oak_speech.*`. **Not raised:** `trainer.before_battle`, `world.talk`, `item.use`, `pokemon.before_give/
  received`; the `map_scripts` registry is dropped.
- **`mod.world`** (`R/src/world/gen2/WorldAPI.lua`): `current()`, `warpTo(map, x, y, facing)` to any loaded map
  (registered ones included), numeric `getFlag/setFlag`, `replaceBlock(bx, by, block)` (block units, undone by the next
  map load), `toggleObject`, `spawnNpc`, `queueScript` (only wild battles, warps, text and flags), `invalidateMap`.
  The `maps` registry writes into the very table the world reads (`data.gen2Maps`), so a `maps:patch` of a town's blocks
  is the town's own from then on.
- **Map ids are bare names, and collide with Gen 1's.** Crystal has 388 maps, Gold 368; **76 of Crystal's ids are the
  same strings as Gen 1 ids** (its Kanto, `PALLET_TOWN`, `ROUTE_1`…, and shared names such as `POKECENTER_2F`). Emerald's
  `EM_` prefix hid this problem. (J4.)
- **Input.** The field reads directions with `isDown` only (`R/src/world/gen2/World.lua:10368-10381, 11553`): a tapped
  direction neither turns nor walks; holding for 1–4 ticks turns, 5 or more turns then walks, and a held direction
  chains into the next step with no standing tick (Gen 3's `chainsHeld`). A, B, START and SELECT taps work.
- **No field lock, but the stack freezes the field.** Each tick updates the stack's top state and skips the whole field
  step while one is up (`R/src/core/Game2.lua:1314-1318`): the VM, NPCs, phone calls and time events stop with it. So
  Gen 1's park (a hold state pushed on the stack, `GB/lib/engine/gen1.lua:3782`) carries over; a wrapper on
  `World.step` is the fallback.
- **Live map data mutates in place.** `World:replaceBlock` edits the shared map record and keeps the originals in
  `world.blockEdits` until the next load restores them; the map's callbacks then re-apply its story edits. An export must
  read pristine blocks; the story reads the edits.
- **Story state.** Event flags are numbered bits in `world.events`; only Gold has a name table
  (`R/src/core/gen2/FlagNames.lua`, generated from pret/pokegold), Crystal has none anywhere. Badges live in
  `save.player.badges` and `kantoBadges` (16 in all) and change with no event. Who stands where depends on the object's
  event flag, its **hour window** and the live masks.
- **The clock** is the host's local time plus offsets saved when the player answers the clock screen
  (`R/src/core/gen2/Clock.lua`); morning 04–09, day 10–17, night 18–03. It changes the map's colours, the wild tables,
  who stands where, phone calls and weekly events; drivers and mods can pin it (`world.clockHour`, the `world.tod` hook,
  `POKEPORT_GOLD_HOUR`).
- **Boot.** `game:newGame()` and `game:continueGame(Save.load(v))` are the direct controls (`continueGame(nil)` starts
  a new game). Oak's speech is a step list (Crystal's gender choice, the clock, the name) that the
  `intro.oak_speech.build` hook can rewrite with `fn` steps, so the name and clock can be answered automatically. In
  driver mode Gen 2 skips the boot cinema unless `POKEPORT_BOOT_CINEMA=1` (our e2e runner is in driver mode).
- **Saving** uses the shared slot registry keyed by version (as Gen 1 and 3); `game:writeSave()` behind
  `quickSaveAllowed()`; `save.position` and `save.events` go stale between saves (read the live world). The nurse is
  `special HealParty`, seen in `script.command`.
- **UI.** Script text, signs, phone calls, Oak's lines and yes/no are the shared TextBox and ChoiceBox, so gen1's readers
  apply; lists are `Gen2ScriptMenu`; START's QUIT and the main menu's EXIT GAME end the session and must be hidden; many
  conversation screens (Mart, PCs, Day-Care, trades) type their own text and are mirror-only at first; Elm's starter
  picture waits outside the stack (`world.waitButton`).
- **Battles.** Every battle (grass, water, fishing, headbutt, Rock Smash, Sweet Scent, roamers, trainers, statics, the
  Bug-Catching Contest, the catch tutorial, the Battle Tower) passes `World:startBattle(opts, onDone)`
  (`R/src/world/gen2/World.lua:6957`), whose `onDone` closure runs the whole after-battle tail (whiteout, money, roamers,
  music, Mom's purchases, the script's resume). There is no headless mode: the hold keeps the original and swaps the
  battle screen for a placeholder (upstream's own `R/tests/gen2_canlose_test.lua` swaps it the same way); `onDone(nil)`
  aborts the script so the trainer is not marked beaten.
- **Pokémon and items.** Species and moves are numbered by national dex (1–251). DVs decide gender, shininess and the
  Unown letter; held items, happiness, Pokérus and eggs are new. At least eleven code paths add to the party (no single
  gift seam). The bag is the shared Gen 1 Bag (pockets 20/12/25/57); money is `save.player.money`, and **Mom's savings**
  are a second account a won prize is split into.
- **Union Room.** The same `unionRoom=false` option governs Gen 2; when on it widens `POKECENTER_2F` and adds a
  `UNION_ROOM` map before mods load (`R/src/world/gen2/UnionCenter2F.lua:226-260`). Our pinned OFF leaves the cart's
  maps; the guard must cover Gen 2 too.
- **Placement.** No engine-wide layout like Gen 3's `computeWorld`; `World.computeNeighbors` is Gen 1's strip BFS in
  blocks, so the Gen 1 export's `layout()` fits. Johto and Crystal's Kanto are two surface areas (31 and 35 maps) that no map
  connection joins: the Victory Road gate (an interior), the train and the boat join them (measured, §2.1).
- **Capacity.** The block families per shape are full: `FAMILIES_PER_SHAPE = 96` (`J/world/SlotMap.java:46`), Kanto takes
  16 and Hoenn 80. Johto's day art measured 3,526 baked tiles over the blocks its maps use, 14 families of 256 (4,856 and
  19 over every block; §2.1).
- **Maturity.** The README still says "Gen 2 support is still under construction", but the code carries 162 ROM-free
  test suites and about 290 real-game drivers (mostly Crystal bug repros), and activity has slowed to bug fixing. Not
  ported: the link cable and mobile features (cable trades and battles, Mystery Gift, the printer); Crystal's own
  extras (the Odd Egg, the Battle Tower, the Ruins of Alph's chambers, Buena's show) are in Crystal-only modules
  (`R/src/script/gen2/specials/`).

## 2. The inspection on the pod (G2S)

The code answers most questions; these need the real engine. A probe mod, a driver and a runner, kept **out of the
repository** (`~/kb-runpod/spike-g2/`, as S2's were), run on one small pod against the volume's v0.3.85 data home
(Crystal imported), on a throwaway portable copy and slot, under Xvfb, with the clock pinned. Output: types, ids, counts
and hashes only. Results go to PROGRESS.md "Johto: inspection", and this plan is corrected where they disagree (as
GEN3_BRIDGE_PLAN §1 recorded S2–S5).

| Spike | Settles | Feeds |
|---|---|---|
| **G2-S1 Boot** | a manifest with `crystal` loads (`mods.loaded`, `game.ready`, engine id); where driver mode lands; with `POKEPORT_BOOT_CINEMA=1`: title → `newGame()` with Oak's speech rewritten (name and clock automatic, gender asked) → `PLAYERS_HOUSE_2F`; `continueGame` on a second boot; `save.created/loaded` identity; Union Room OFF (`UNION_ROOM` absent, `POKECENTER_2F` its own width) | G2A, J5 |
| **G2-S2 Walking and warps** | tap vs hold timing; the verdict stream for a wall, an NPC, a ledge, a seam and a surf landing; `world.stepped` COLL bytes; a door (`player.warped`, a redirect); `world.setupMethod` per warp kind; a same-map `warpTo` | G2A |
| **G2-S3 World edits** (the route end) | `warpTo` direct and deferred; `replaceBlock` opens a tree block; a `maps:patch` of a town's blocks persists across loads and draws; a registered `KB_TEST` map loads, draws and walks (or not) | J9, G2H |
| **G2-S4 Story** | the `flag.changed` numbers through Elm's lab (the starter marker); badges; `world.block_replaced` and `blockEdits` after Cut and a script's `changeblock`; object masks and hour windows | G2C, J11 |
| **G2-S5 UI** | gen1's TextBox/ChoiceBox readers on Gen 2's boxes (prompt, done, stay, auto; yes/no over a held box); a phone call; Elm's picture wait; naming (player, nickname, empty); the mirror's frame at 160×144 | G2B |
| **G2-S6 Battles and gifts** | the hold end to end: a trainer win marks the trainer, a loss whites out, the Cherrygrove rival (a loss continues), a run, a roamer; `onDone(nil)` and how soon a trainer re-engages; `battle.started/ended` counts; the starter's `givepoke`; a full party; `HealParty` | G2F |
| **G2-S7 Parking** | gen1's hold state on the stack (the `World.step` wrapper as the fallback): no NPC motion, no phone call, music fade, a clean release | G2A |
| **G2-S8 The measure** (`kb_g2measure.lua`, counts only, as G3D's) | maps by environment, palette and area; surface components from `NEW_BARK_TOWN`; ids equal to Gen 1's; the COLL byte histogram; **distinct baked tiles** (day; day+night; all three); roof variants; animated tiles; (sprite, palette) pairs, big objects; encounter tables; swap sites by kind; whether the dataset view reads `gen2Maps`; a baked tile against the engine's own frame (hash); export time and size | J7, G2D, G2E |
| **G2-S9 Resources** | memory and frame rate of Crystal alone, beside Blue, and beside Blue and Emerald | G2H |

About one to two hours on a cpu3g ×4 pod ($0.16/hr). **Done 2026-10-15** (about 21 minutes on kb-dev19, PROGRESS.md
"Johto (MG9): the inspection G2S").

### 2.1 What the inspection settled (2026-10-15)

- **Confirmed as planned**: the mod loads with `games: ["crystal"]`; `game:newGame()` with Oak's speech rewritten by
  `fn` steps (clock, name) and Crystal's gender asked as `Gen2GenderSelect`; `save.created`/`save.loaded` carry
  `game.save`; `writeSave` then `continueGame(Save.load(v))` lands on the saved cell; the Union Room is OFF on the
  test install; taps don't walk; `movement.collision` gives Gen 1's verdicts (walls `tile`, NPCs `entity`, ledges a
  refused `tile` then a hop); walked warps raise `warp.destination` (which redirects) and `player.warped`, every other
  load shows as `map.entered` with `world.setupMethod` (0xF5 door, 0xF1 warp); a same-map warp reloads the map; the
  battle hold works for wild and trainer battles, and `onDone(nil)` aborts without marking the trainer; a held loss
  whites out with the money halved; the nurse is `HealParty` through `script.command`'s fifth argument; the mirror is
  160×144; Johto and Crystal's Kanto are two surface areas that no map connection joins.
- **Corrections**:
  - **Parking**: no `World.step` wrapper is needed. Gen 1's hold state pushed on the shared stack freezes the whole
    field step (no VM, NPCs or time events) and is the default; the wrapper stays the fallback (§5.8).
  - **A turn is a hold of one tick** (§5.3).
  - **A registered map can be entered and walked** (`KB_TEST_MAP`), so Gen 1's own route map carries over to Gen 2:
    J9's default changes to it (§5.11, §12).
  - **The starter marker is flag 26** on Crystal too (Gold's `EVENT_GOT_A_POKEMON_FROM_ELM`); `givepoke` names the
    species by national dex number (155 for the fire starter) (J11).
  - **`battle.started` fires once per held battle** (the model's), and `battle.ended` only when the adapter calls the
    model's `endBattle`: the adapter calls it with the outcome before `onDone` (§5.10).
  - **Texture slots: 14–19 families, not 20–30.** Day art over the blocks the maps use is 3,526 tiles (14 families);
    over every block of every art variant 4,856 (19). The export bakes the blocks the maps use plus the swap sites'
    blocks (J7).
  - **The collision vocabulary is wider than §7's table** (0x21, 0x27 and the one-way 0xb2 on 2,343 cells, among
    others): the export names each code's meaning from the engine's own predicates, and `G2Cells` reads those (§6, §7).
  - **The dataset view reads `maps` cleanly** on Crystal (388), unlike Emerald's; the export still reads the live
    tables (its other readers came back empty), with the view as a pristine source for map records.
  - **Resources**: Crystal about 205 MB RSS; Blue, Emerald and Crystal together about 800 MB, all at 60 frames a second.
- **Not checked** (the phases' e2e covers them): a phone call (none rang), the Cherrygrove rival, roamers, a surf
  landing, Fly and Escape Rope, the catch tutorial, typed and empty names, Mom's scene downstairs (driver mode's
  skeleton game skips it), a baked tile against the engine's own frame (G2D's export check), and the map ids equal to
  Gen 1's (76 by the upstream name tables; G2A counts them live).

## 3. The adapter: structure

- `GB/lib/engine/gen2.lua` is the entry, loaded by `kb_engine.lua` for **both** engine ids (`Engine.ADAPTERS.gs` and
  `.crystal`); `engineId()` stays the stub's (it returns the live id; game3's boot hard-codes its own, which a copy must
  not). It **starts from the stub** (`Engine.stub(opts)`) and installs parts in `GB/lib/engine/gen2/`, one group each:
  `base`, `boot`, `observe`, `move`, `ui`, `story`, `saves`, `region`, `reliability`, then `party`, `battle`, `bag`,
  `export`, `routes` (J1).
- **Shared parts.** `game3`'s `reliability.lua`, the slot half of `saves.lua`, the save queue and `Routes3.plan` are
  engine-neutral: they move to `GB/lib/engine/common/` and both adapters use them, guarded by game3's own tests (J1).
- **`Gen2.REQUIRES`** lists every module and member the parts read (`checkRequired()` → `engine.missing`, as gen1's and
  game3's); `tools/pin_check.py` audits every cited engine line.
- **Map ids** (J4): the base part owns `ext(id)` (`G2_` + the engine's id) and `int(id)`; every id the adapter reports
  or accepts (observe, warps, story, saves, region acts, export keys, route ends, F7) goes through them.
- **Lifetimes** (world note §13): re-read `game.world` every tick (rebuilt on CONTINUE, dropped at the title); never
  cache NPCs or the map across `map.entered`; take position and flags from the live world, not the save; remember that
  block edits mutate the shared map table.

## 4. The core changes (small, made first)

| Change | Why | Where |
|---|---|---|
| The manifest's `games` gains `crystal` (later `gold`, `silver`); `games.json` sets Crystal's `adapter: true` and its markers; `kb_games.lua` regenerated; the tests that pin today's adapter set updated (the stub's test moves to an engine id with no adapter) | the mod is not even loaded on Crystal today | `GB/manifest.json:9`, `P/games.json`, `T/lua/test_lua_sandbox_main.py:303, 334, 358, 376`, `T/tests/test_games.py:48-50` |
| `Engine.ADAPTERS.gs` and `.crystal` → `gen2.lua` | §3 | `GB/lib/kb_engine.lua:38-43` |
| main forwards `script.command`'s fifth argument (the decoded op) to the adapter | specials (`HealParty`), `givepoke` fields | `GB/main.lua:716-718` |
| main routes every engine payload that carries a map id through the adapter (`engine.mapOf(id)`, the identity on Gen 1 and 3) | J4's prefix reaches the core's own subscriptions (`world.stepped` → routes, `map.entered`) | `GB/main.lua` hook and event handlers |
| `engine.swapSites()` when the adapter has it, else today's Gen 1 table | Gen 2's swap sites (cut trees, whirlpools, scripts' block changes) | `GB/observe/kb_story.lua:112` |
| Badges: the snapshot keeps up to 16 | Johto's 8 and Kanto's 8 | `GB/observe/kb_snapshot.lua:127-131`, `P` snapshot schema |
| The e2e runner: Gen 2 title scenes set `POKEPORT_BOOT_CINEMA=1`; every Gen 2 run pins the clock (`POKEPORT_GOLD_HOUR=12`, `POKEPORT_GOLD_DAY`) | §1 boot, clock | `T/e2e/run_gen1recomp.py:325-339` |

## 5. The adapter, group by group

### 5.1 Boot and identity (link)

`engineId` = the live `gs`/`crystal`, `family` `gen2`, `gameVersion` = the game's id, `version()`/`checkPin` as gen1.
`setGame(g)` keeps the Game2 instance from `game.ready`; `save()` = `game.save` (the table `save.created`/`save.loaded`
carry, so pairing's identity test holds; `save.created` fires twice in a new-game session). `saveView()` builds the Gen
1-shaped view once per step: `player{map, x, y, facing, name}` from the live world, `flags` as `E<number>` for every set
event flag plus the synthetic `KB_HALL_OF_FAME` (Hall of Fame count > 0, J11), `money` = `save.player.money`, `coins`,
`inventory` by item symbol, `badges` from `save.player.badges/kantoBadges`, `pokedex{seen, owned}`, `options`.
`unionRoomPatched()` is real: `data.gen2Maps.UNION_ROOM ~= nil` or `UnionCenter2F.isApplied` → the export is refused, as
on Gen 1.

### 5.2 Observe (link)

Facts from `game.world` each tick: `inGame` (`phase == "play"`, a world, a map, a player), `top` (`overworld`, or
`screen` with the stack top's `screenId`/class), `map` (prefixed), `x, y` (`player.cellX/cellY`), `facing`, `moving`,
`tx, ty`, `frames`, `ledge` (`player.jumping`), `surf`/`bike` (`world.playerState`), `scripted` (the VM running or a
movement stream), `transitioning` (`world.mapSetup`), `battle` (`world.battleActive`), `chainsHeld = true`. The idle
test follows the engine's own gate (`World:acceptsMenuInput()`) plus a turn in progress, pending scene scripts and warps,
and forced movement (ice, currents, the Cycling Road). `npcs()` lists `world.npcs` that are visible (masks), with
`kind` trainer/item/boulder/person, the 2×2 big objects flagged, the optional follower left out.

### 5.3 Movement and warps (link)

- **Input**: directions are held through `mod.input:press/release` inside `input.step` (never tapped); a turn is a
  hold of one or two ticks (the 4-frame turn waits, so no step follows; measured, §2.1); the mover releases once the step has started
  (`chainsHeld`). A, B, START, SELECT are taps. AutoInput and focus loss reset every source: the mover re-presses.
- **Verdicts**: main's `movement.collision` wrapper and gen1's `playerVerdict`, filtered to `ctx.mover ==
  world.player`; a ledge arrives as a refused `tile` and then hops (the mover treats a refused step onto a ledge it
  is facing as pending). No `takeVerdicts`.
- **Warps**: walked warps give `warp.destination`/`player.warped` (the kind from the COLL byte: door, staircase, cave,
  warp panel, pit, carpet); every other load (script warp, Fly, Teleport, Escape Rope, whiteout, the Magnet Train and
  the S.S. Aqua) is caught by the observer's map change with `world.setupMethod` for the kind. A same-map warp reloads
  the map fully (exited and entered fire), unlike Gen 3.

### 5.4 Interaction (link)

`world.interacted {mapId, x, y, kind, target}` (kinds trainer, boulder, itemball, npc, sign, hidden, std, fieldmove,
none) for every A press; counters double the reach. `cellInMap` from the map's size in blocks; `counterCell` from the
COLL byte (0x90).

### 5.5 UI (G2B)

The stack is the shared StateStack, so the UI part starts from gen1's readers (`GB/lib/engine/gen1.lua:783-1332`):

| Gen 2 state | Core class | Answer |
|---|---|---|
| shared TextBox (script text, signs, Oak's lines, phone calls under their caller box) | `TextBox` (gen1's reading, the v0.3.85 arrow rule) | A |
| shared ChoiceBox (yes/no), `Gen2GenderSelect` (Crystal) | `ChoiceBox` | up/down, A |
| `Gen2ScriptMenu` (vending machines, prize counters, menus in scripts) | `ListMenu` (1-based; B = 0) | d-pad, A, B |
| `Gen2StartMenu` (QUIT and MODS hidden), `Gen2NamePick` | `Menu` | up/down, A, B |
| `Gen2NamingScreen` (player 7, rival 7, box 8, nickname 10) | `NamingScreen` | set `text`, cursor on END, A; empty = the caller's own default |
| Elm's starter picture (`world.waitButton`, outside the stack) | `Screen` (picture mirror) | A |
| `Gen2InitClock` | answered by the adapter (J5) | — |
| everything else (party, summary, pack, Pokédex, Pokégear, trainer card, options, save, Mart, PCs, Day-Care, trades, mini-games, cutscenes, native battles) | `Screen` → mirror (160×144) | taps from the mirror |
| the boot (copyright, intro, title, main menu) | `Screen` "TitleState" → mirror | `region.start` |

Oak's speech: the build hook replaces `name_player` (and Mom's later weekday wheel and the clock) with `fn` steps that
answer automatically (MULTI_GAME_PLAN D6; J5); Crystal's gender choice is asked through the UI, as Emerald's was. The
main menu's EXIT GAME is never chosen. A generic reader for the self-typed conversation screens (Typer) is later work.

### 5.6 Story (G2C)

- **Flags**: `saveView().flags` from the live `world.events` by number (`E26`…); `progressMarkers` reads the catalogue's
  markers through the same names (J11).
- **Objects**: `storyToggles()` per map from each object's event flag and hour window, and the live masks on the
  current map (`O<index>` as on Gen 3).
- **Blocks**: Gen 1's model. Live blocks are the current map's `def.blocks` against the originals in
  `world.blockEdits` (plus `world.block_replaced` for timing); `story.blocks{bx, by, block}` as Gen 1 sends them;
  Crystal's whole-map `changemapblocks` is compared block by block. `swapSites()` gives the export's sites (§6) so the
  client draws closed and open art, as in Kanto. (Gen 3's `story.cells` is not used: the client keeps those cells but
  draws none of them.)

### 5.7 Saves, slots, pairing and the nurse (link)

Slots, the save queue and pairing from the common parts (the version id is the game's). `switchSave`/`centerSaveStep`
call `game:writeSave()` only when `quickSaveAllowed()`; `save.writing` drives `savingReason` as on Gen 1. The nurse:
`script.command` with `op == "special"` and the special named `HealParty` arms `nurseHealed()` (wrapping
`World.healParty` would also count whiteouts and Sacred Ash).

### 5.8 Region (park, start, resume)

- `holdAway`: gen1's hold state pushed on the shared stack (`GB/lib/engine/gen1.lua:3782, 3809`): while it is on top
  the field step is skipped (no VM, NPCs, phone calls or time events; while parked the client follows another game, so
  nothing reads this link's screens), and the music fades (`R/src/core/Music.lua`). `releaseAway` pops it and restores
  the map's music. Fallback: a wrapper on `World.step`. (G2-S7: 0 world steps and no NPC motion while it is up.)
- `startFromTitle(mode)`: `continue` = `SaveData.setActiveSlot` then `game:continueGame(Save.load(v))` (only when
  `Save.exists`); `new` = `game:newGame()`, Oak's speech as §5.5, then the bedroom.
- Resume warps: `mod.world:warpTo(map, x, y, facing)`.

### 5.9 Time (J5, J6)

The hidden game keeps the real local clock, as the cart's own clock does: its weekday events, phone calls and people
follow your time of day. The new-game clock and Mom's weekday are answered with the real time. Minecraft stays at noon;
the world is built with day colours (J6). Tests pin the clock.

### 5.10 Battles, party, items (G2F)

- **Battles** (J10): a wrapper on `World.startBattle` decides; a held battle skips the wipe (`pushBattleTransition`
  returns false) and gets a placeholder in place of `Gen2BattleState`, which freezes the field as any stack state does.
  The client fights it in Cobblemon; on `battle.result` the placeholder sets the model's outcome (the model's
  `endBattle`, which raises `battle.ended`; nothing else does under the hold), on a trainer win pays
  the prize through the engine (`battle:awardPrizeMoney()`, Mom's share included) and shows the defeat and money lines as
  TextBoxes, then calls the engine's own `onDone(outcome)`, which runs the whole tail. Abort = `onDone(nil)`. Kinds: grass
  and cave rolls → none (the hook returns nil; habitats), surfing → `surf`, fishing → `fish`, headbutt, Rock Smash, Sweet
  Scent, statics and roamers → `static`, RIVAL1/RIVAL2 → `rival` (a lost first rival battle continues), others →
  `trainer`; the Bug-Catching Contest, the catch tutorial and the Battle Tower stay native (mirror). Teams: dex, level,
  moves, DVs (trainers' are always 9/8/8/8, Gen 1's four-DV shape), held item. As on Gen 1 and 3, no experience,
  evolution or catch is written into the hidden game; Cobblemon's `party.sync` and `dex.sync` bring them back.
- **Party** (J12): `applyParty` builds `Mon.new` records stamped with the player as trainer; DVs from Cobblemon's IVs,
  adjusted so gender, shininess and the Unown letter match; held items both ways; friendship as happiness. Gifts: a
  per-step party diff as the safety net, tagged by the seam that ran (`givepoke`/`giveegg` in `script.command`, wrapped
  `NpcTrade.perform`, `Breeding.withdraw/collectEgg/hatch` and `egg.hatched`, the Shuckle special, contest and Game Corner
  prizes, `pokemon.caught`). Trades, the Day-Care and eggs follow Emerald's design (follow-up item 7): eggs stay in the
  hidden game and cross when they hatch. Item uses on synced members are refused (wrap `Game2:usePartyItem`,
  `afterRareCandy`, `learnMoveOn`: there is no `item.use`).
- **Bag and money**: gen1's `bagCounts/bagSet/bagRemove` over the shared Bag with Gen 2's money accessor, key items by
  pocket, the pocket limits; `P/items.json` gains `symbols.gen2` and Gen 2's new canonical items (held items, berries,
  apricorns and Kurt's balls, which Cobblemon has, evolution items, Gen 2's TMs). Mom's savings stay Crystal's (J13).
  Bill's PC opens Cobblemon's PC (a wrapper on `CenterPcMenu:choose` for its row), as on Gen 1.

### 5.11 Routes (G2H)

The Gen 2 end of a connecting route follows **Gen 1's** (J9, changed by G2-S3: a registered map can be entered and
walked on Gen 2): the route map `KB_ROUTE_<ID>` is registered (`maps:register`, on an existing tileset), the clearing is a
`maps:patch` of the town's blocks made at load (the town's own art, exported with it; Gen 2's blocks are the route
planner's own unit), the trigger is `world.stepped` on the clearing's last cell (Gen 2 has no `map_scripts`) followed
by `mod.world:warpTo` into the route map, and the way back is a `warpTo` to the town. The closed lower row comes from
`movement.collision`, as on Gen 1. Gen 3's design (the hidden player parked on the trigger cell and reported as a
virtual route map) is the fallback. The acts: give = `Bag.add` and a TextBox line; battle = a held
battle built from the class's first trainer as a template with the request's party; blackout = the engine's whiteout.

## 6. The Gen 2 export (G2D)

From the adapter's `exportResources(sink)` (the core caches, hashes and indexes, as for Gen 3); measured first (G2-S8):

| Kind | Holds |
|---|---|
| `g2map/<G2_MAP>` | size in blocks; blocks (pristine plus our route clearings); border block; art variant and tileset; environment; palette kind; map group; area (`johto`/`kanto`, from the landmark); `dark`; connections (direction, map, offset in blocks); warps (cells, arrival 0-based, `dynamic` for the remembered warp 0xff); coord and sign events by position and kind; objects (index, sprite, palette, cell, movement, kind, hide flag, hour window, `stays`, `big`) |
| `g2tiles/<variant>` | per block (128): 16 baked tile ids and 4 COLL bytes; the variant = tileset, roof sheet and colours of the group, palette row, daytime, Crystal's special set |
| `g2image/g2q.<n>` | 256×256 RGBA pages of 1,024 baked 8×8 tiles (`g3image`'s format) |
| `g2anims/all` | animated tiles (water, flowers, whirlpools, the tower pillar, lava, scrolling waterfalls and currents, Crystal's fountain and forest trees): frames and loops; palette animations left out |
| `g2sprite/<SPRITE>[.p<n>]` | RGBA strips per (sprite, palette) used: 16×16 frames (walking 6, standing 3, still 1), big objects, Pokémon dolls from the menu icons |
| `g2layout/<area>` | Gen 1's `layout()` shape in blocks, one per surface area: roots `G2_NEW_BARK_TOWN` and Crystal's Pallet Town |
| `g2encounters/all` | grass (rate and 7 slots per time of day), water, fishing groups (with their time rows), headbutt sets, rocks, the contest, swarms; national dex numbers |
| `swaps` (today's kind) | block swap sites `{map, bx, by, kind, before, after}`: cut trees, whirlpools (a new kind), the blocks scripts change, boulders and rocks from objects |
| `g2names/all` | species, moves, items |

- **Art is baked in Lua, on the CPU**: each sheet through `Assets.imageData` (with the roof overlay for its group),
  each tile with its palette from the engine's own `Palettes.bgSet(data, map, daytime)`, the GBC colours as the engine
  scales them, deduplicated by pixel hash and paged as Gen 3's. Day colours (J6): `PALETTE_AUTO` maps at DAY, pinned
  maps their own, dark maps their after-Flash look (flagged `dark`).
- **Pristine blocks**: from a fresh `CacheFs.loadActive` of the map table plus our own patches, never the live
  `def.blocks` (a loaded map's Cut trees and story edits are in it).
- **Collision codes carry their meaning**: `g2tiles` lists, for each COLL byte its blocks use, what the engine's own
  predicates say about it (walkable, water, grass, ice, warp and its kind, ledge and its direction, one-way side,
  counter, solid furniture), so the builder needs no table of its own (§2.1).
- **Map ids** are prefixed (J4); the Union guard refuses the export as on Gen 1.

## 7. The builder (G2E)

- **`Gen2Planner`** (`J/builder/plan/`, beside `Gen3Planner`), dispatched from `WorldPlanner.plan` on a `g2map` bundle
  (an `isGen2()` beside each of the six `isGen3()` sites) (J8). It takes Gen 3's machinery (baked art, quarter pages, two
  art sets in one pack, region builds, `InteriorPacker`, the shape planner) and Gen 1's block-level parts (layout in
  blocks, swap sites and closed art per block, a one-block border). Gen 1's path stays untouched (its goldens byte for
  byte).
- **`G2Cells`** builds cells from the meanings the export gives each code (§6); the table below is what those meanings
  come to (the vocabulary is wider than `G3Cells`, and 0x2B and 0x82 mean other things in Gen 2; the inspection also
  found 0x21, 0x27 and the one-way 0xb2, the commonest one-way side, on 2,343 cells):

  | COLL bytes | Built as |
  |---|---|
  | 0x00 floor; carpets 0x70/76/78/7e | walk (carpets are warps entered by a press) |
  | 0x18 tall grass, 0x14 long grass | walkable grass (habitats) |
  | 0x29 water; 0x33 waterfall, 0x3x currents | water (the forced motion is the hidden game's; the client follows the hidden player) |
  | 0x24 whirlpool | water, closed until the story opens it (a swap site) |
  | 0x23/0x2b ice | walkable floor, no habitats (the slide is followed) |
  | 0x07 wall, 0x12 cut tree (a swap site), 0x15 headbutt tree, 0x90 counter, 0x91–0x9f furniture | solid |
  | 0x71 door, 0x72 ladder, 0x7a staircase, 0x7b cave, 0x7c warp panel, 0x60/0x68 pits | warps |
  | 0xa0–0xa5 ledges (incl. the two diagonals) | ledges |
  | 0xb0–0xb2 side walls, 0xc0–0xc2 buoys (and 0x27, per the engine's predicates) | one-way edges (`PairEdges`-style walls; the neighbour rule per game, `sideWallArms`) |
  | 0xff (block 0, off-map) | solid border |

  Any other byte is built solid and warned. `CellFlags` has no free bit, so the Gen 2 form of `MapModel` carries its
  own tags.
- **Placement**: `g2layout` in blocks (cells × 2 blocks as everywhere); the two surface areas side by side, Crystal's
  Kanto east of Johto with a sea gap (J3); edge mismatches and overlaps warned as Gen 1's. Interiors under their doors
  as Gen 1's and 3's; the one shared `POKECENTER_2F` is placed under the first Pokémon Center, and the stairs of the
  others arrive there with the fade (it is one map in the game); outdoor maps inside indoor ones (`MOUNT_MOON_SQUARE`,
  `TIN_TOWER_ROOF`) get the sky, as G3E's.
- **Story cells**: `StoryCellPlanner` over the swap sites, with closed art through the quarter pages (its art lambda's
  ≤ 1023 cap goes through `QuarterPages`).
- **Art**: `ArtCatalog.baked`, `BakedTextures`, the pack writer's baked branch and the quarter pages as Gen 3's, with
  Gen 2 page names; `Games.compatible` compares the exports' art (Gold, Silver and Crystal bake different tiles), not
  only the family.
- **People**: `g2sprite` → pack sheets at Gen 1's scale (16-px frames; today's baked sheets use 32-px cells, which would
  draw Gen 2 people at half size).
- **Animated tiles** as `g3anims` (strips and `.mcmeta`); **darkness**: dark maps unlit until Flash, then Night Vision,
  as Kanto and Hoenn.
- **Habitats**: wild cells are tall and long grass outdoors and every non-ice floor cell in caves and dungeons (the
  engine's own rule); pools from the grass tables, the three times of day merged into one pool (J6), Cobblemon ids by
  national dex (`NationalDexIds`, 251 fits); water, fishing, headbutt and Rock Smash stay the hidden game's rolls.
- **Capacity** (J7): measured 14 families for the blocks the maps use (§2.1), so `FAMILIES_PER_SHAPE` 96 → 112 with
  `familiesFor("gen2")` = 16, if the blocks used plus the swap sites' blocks and the water halves fit 4,096 slots; else
  128 with 32.
  Changes with it: `SlotMap`, `KbBlocks`, `SlotMapTest`, the family maxima in `P/world/slot_map.schema.json`,
  `region.schema.json` and `world_index.schema.json`, `T/e2e/build_world.py`'s range check, PROTOCOL.md. The cost (about
  1 MB of block states a side per family, extrapolated from MG3's measure: +16 families about +15 MB) is measured on the
  pod's client at G2E.
- **Flat**, as Hoenn (E8): no heights or caves in 3D for Johto yet.
- **The real dry run**: `RealGen2DryRunTest` (`-Pkb.realGen2Bundle`) on the pod's Crystal export: maps placed, warnings,
  slots and families, story cells, habitats, shape-plan size.

## 8. Johto in the world (G2G, G2H)

- **Region `johto` at slot 2**, beside Kanto at 0 and Hoenn at 1 (`RegionTable` pitch 8,192 blocks; Johto and its Kanto
  fit in one slot with room to spare). Built with `build_world.py --game crystal --region johto --slot 2 --maps all`.
- **A three-region test world** on the volume (Kanto, Hoenn, Johto), as PassE2E was for two.
- **Join sizes**: each join still sends every region's `world.index` and `world.slots` as plain text (Hoenn alone 45 and
  36 parts); a third region adds roughly 25–35 more. G2G offers them by hash as the shapes already are (the leftover of
  follow-up item 4).
- **The pass** (J14): `routes.json` gains `kanto_johto_test`, a temporary test pass like Hoenn's: a Kanto corner other
  than the Hoenn test's and a New Bark Town corner, chosen on the pod by HOENN_PASS_PLAN K1's rules (reachable on foot, at
  least six cells from any object, warp, sign or door), depth 2, Gen 2 trainer classes on the Johto half. You move
  either end later with F7.
- **The Johto half's content**: `ZoneContent.ofGen2` (pools from the town's connected maps, Gen 2 classes), route
  trainers on the server by Gen 2 class symbols (`ProxyClass`), items by Gen 2 symbols, `LedgerRules.Limits` for Gen 2's
  pockets.

## 9. Protocol changes

| Phase | Change |
|---|---|
| G2A | none on the wire (Crystal's `hello.ok` already carries family and compat; `G2_*` ids fit the map-id pattern); `games.json` Crystal `adapter: true` |
| G2C | `snapshot.badges` up to 16; Crystal's markers (`E<n>`, `KB_HALL_OF_FAME`) |
| G2D | export kinds `g2map`, `g2tiles`, `g2image`, `g2anims`, `g2sprite`, `g2layout`, `g2encounters`, `g2names` (`exportKind` enum, schemas, `ExportBundle.KINDS`, `kb_export.KINDS`); `swaps` kind `whirlpool` |
| G2E | the family maxima follow `FAMILIES_PER_SHAPE` (slot map, region record, WorldIndex closed art) |
| G2F | `items.json` `symbols.gen2` and Gen 2's new items; team and party members: optional held `item`, `shiny`, `gender`, `form` (the Unown letter), `friendship`; `battle.native.kind` `contest` and `tower` (the tutorial maps to `old_man`); `act.field` gains `headbutt`, `rock_smash`, `whirlpool`, `waterfall`, `sweet_scent` |
| G2G | `world.index` and `world.slots` offered by hash (as `world.shapes.offer`) |
| G2H | `routes.json` `kanto_johto_test` |

Every change: schema, PROTOCOL.md, `python -m kb_protocol.catalog --write`, the Java readers, both stand-ins.

## 10. Tests

- **The `gen2` fake engine (lupa)**: `T/lua/fake_engine2.lua` (+ export and battle fakes) fakes what the parts read:
  Game2 (phase, stack, save, `newGame`/`continueGame`), the World (map, player, NPCs, events, blockEdits,
  `startBattle`), the VM's `script.command`, the shared TextBox/ChoiceBox/StateStack, Save and the slot registry,
  GameVersion (`crystal`, generation 2), Palettes and a made-up tileset. Made-up maps, flags and text only.
  `kblua.Bridge(family="gen2")` and `Rig(family="gen2")` load it; `T/lua/adapter2.py`.
- **Unit tests per part**: `T/lua/test_lua_gen2_<part>.py` (boot and identity, observe and idle, held input and
  verdicts, warps, UI readers, story flags/toggles/blocks, saves and the nurse, park/start/resume, held battles, gifts,
  bag, export on the fake, the route end).
- **The synthetic bundle**: `T/fake_gen1/synth2.py` (made-up `G2_TEST_*` maps, blocks, COLL bytes, a cut tree, a
  scripted block change, flagged objects, a time-of-day grass table) and its checked copy `synthetic_bundle/crystal/`.
- **Java**: `G2ReadersTest`, `Gen2PlanTest` with golden index, scene and shapes, `Gen2FrontEndTest`,
  `G2PoolWriterTest`, `RealGen2DryRunTest`; GameTest `Gen2BuildGameTests` (Johto at slot 2 beside a synthetic Kanto and
  Hoenn: three ranges, three art sets), in its own batch.
- **e2e on the pod's Crystal**: `T/e2e/test_crystal.py` + `drivers/kb_crystal.lua` (title, a new game through Oak's
  speech, the field on a throwaway slot, walking and verdicts, a door, a sign, a save, CONTINUE, park and resume, a
  phone call, battles, the starter and other gifts); `test_g2_measure.py` + `kb_g2measure.lua` (G2-S8);
  `test_crystal_export.py` (counts and hashes); Blue ↔ Crystal crossings in `test_crossing_real.py`. Every driver that
  fetches an export applies `memoDatasetChecks` (`KB_DATASET_MEMO=30`).

## 11. Phases

| Phase | Delivers | Check |
|---|---|---|
| **G2S** inspection | spikes G2-S1–S9 and the measure (§2); PROGRESS "Johto (MG9): the inspection G2S"; this plan corrected (§2.1) | numbers on the pod — **done 2026-10-15** |
| **G2A** followable | the core changes (§4); adapter base, boot, observe, NPCs, held input and verdicts, warps, interaction, saves and slots, region acts, reliability; `common/` parts; map-id prefix; the fake engine | lupa per part; e2e `test_crystal.py` (title, new game, walk, door, save, CONTINUE, park/resume) ✔ |
| **G2B** UI | text, yes/no, lists, START menu, naming, Oak's speech, phone calls, Elm's picture, mirrors | lupa UI; e2e (a sign, Oak's speech through the UI, a call) ✔ |
| **G2C** story | numeric flags, toggles, `story.blocks`, swap sites, markers | lupa; e2e (the starter flag, a block change) |
| **G2D** export | the `g2*` kinds, baked tiles, pristine blocks, layout, encounters, sprites, animations, swaps | lupa on the fake; the pod's export, counts and hashes ✔ |
| **G2E** builder | families raised; `Gen2Planner`, `G2Cells`, `Gen2FrontEnd`, the Gen 2 `MapModel` form, placement, interiors, story cells, art, people, animations, darkness, habitats | JUnit, golden scene, GameTest; the pod's dry run ✔ |
| **G2F** battles, party, bag | held battles, party sync, gifts, trades, Day-Care and eggs, bag and money, Bill's PC, field moves | lupa; e2e (a wild, a trainer and the first rival battle; the starter) ✔ |
| **G2G** the real Johto | synthetic bundle, goldens, full suites; Johto built at slot 2 beside Kanto and Hoenn; index and slots by hash; budgets | full suite ✔; build gates |
| **G2H** the pass | `kanto_johto_test`; the Gen 2 route end; the Johto half's content; crossings between the real Blue and Crystal (20 each way); PLAYTEST "Johto"; a video if you want one | e2e ✔ |

For scale: Emerald's seven phases and its pass (MG7, MG8) took about a day of sessions. Gen 2's adapter is closer to
Gen 1's where it reads the game, but its battles and gifts need more engine seams.

## 12. Decisions (defaults proceed)

| # | Question | Default | Alternatives |
|---|---|---|---|
| J1 | Adapter shape | **one `gen2` adapter** for `gs` and `crystal`, parts under `lib/engine/gen2/`, from the stub; engine-neutral parts moved to `lib/engine/common/` | copy game3's neutral parts instead of moving them |
| J2 | Which games | **Crystal** (imported). Gold and Silver go through the same adapter but are switched on only after one of them is imported and passes the e2e: you can upload Gold or Silver through the ROM upload page from your phone. A Johto world is built from one game's export (their maps and art differ) | Gold/Silver left off |
| J3 | The world space | **the whole of Crystal's world as region `johto`**: Johto and its own Kanto ("three years later") side by side, joined as the game joins them (the Victory Road gate, the Magnet Train, the S.S. Aqua) | Johto only (the hidden player could walk into a Kanto the client can't show); Crystal's Kanto as a region of its own (new machinery: two regions for one game) |
| J4 | Map names | **`G2_` before every Gen 2 map id** outside the adapter (76 of Crystal's ids are Gen 1's too): messages, logs, exports, route ends and F7 are never ambiguous, as Emerald's `EM_` ids | bare ids, with every map-keyed cache checked for its region |
| J5 | The clock | **the real local time** in the hidden game (the cart's own behaviour); the clock and weekday questions answered automatically; tests pin it | the hidden clock driven by Minecraft's time (with day and night on in Johto) |
| J6 | Time of day in the world | **day colours** baked (dark maps their after-Flash look); **one wild pool per place** with the morning, day and night Pokémon merged | per-time pools on Minecraft's clock (Cobblemon's `timeRange`); night art baked too (two to three times the texture slots) |
| J7 | Texture slots | measured (G2-S8): 14 families for the blocks the maps use, 19 for every block. **Raise the families per shape 96 → 112 (Johto 16)**, about +15 MB of block states a side, if the blocks used plus the swap sites' fit; else 128 (Johto 32, about +30 MB) | bake every block (19 families, so 128) |
| J8 | Builder path | **a `Gen2Planner`**: Gen 3's baked art and regions, Gen 1's blocks and story | export Gen 3-shaped cells and build Johto with `Gen3Planner` first (quicker first look, but terrain changes would not be drawn) |
| J9 | The Wild Routes end | **Gen 1's own route map** (changed by G2-S3: a registered map loads and walks on Gen 2): `KB_ROUTE_<ID>` registered, the clearing a patch of the town's blocks, the trigger `world.stepped` | Gen 3's design (the hidden player parked on the trigger cell, reported as a virtual route map) |
| J10 | Battles | **held for Cobblemon** through `World.startBattle`, the engine's own ending run afterwards; grass and caves from habitats; the Bug-Catching Contest, the catch tutorial and the Battle Tower native (mirror) | the contest in Cobblemon too |
| J11 | Story markers | starter = **flag 26** (`E26`, measured in G2-S4: the same number as Gold's); champion = **Hall of Fame count > 0** (`KB_HALL_OF_FAME`; measuring the champion flag would mean beating the League on a pod); badges from the save (16) | our own name table for Crystal's flags (identifiers from the disassembly; a licence question first) |
| J12 | Pokémon details | DVs and Cobblemon's IVs **reproduce gender, shininess and the Unown letter**; held items cross both ways; friendship carried; Pokérus dropped (Cobblemon has none); eggs as Emerald's; wild habitats stay never-shiny, as Kanto's | no held items in the first cut |
| J13 | Money and PCs | Mom's savings **stay Crystal's** (her share of a prize leaves the shared wallet like spending); her question left to you in the UI; Bill's PC opens Cobblemon's PC; the item PC stays Crystal's | Mom's question answered NO automatically |
| J14 | The pass | a **temporary test pass** `kanto_johto_test` (a Kanto corner not used by the Hoenn test, a New Bark Town corner), chosen on the pod; one world with Kanto at slot 0, Hoenn 1, Johto 2; the real exits later with F7 at your PC | a natural spot now (Route 22 or 27, your pick) |

## 13. Risks

| Risk | Mitigation |
|---|---|
| Gen 2 is "under construction" upstream and its internals move | the pin stays v0.3.85; `REQUIRES` and `pin_check.py`; the activity on Gen 2 has slowed to bug fixes |
| The battle hold leans on four internals (`World.startBattle`, `pushBattleTransition`, the battle screen's constructor, the ending closure) | `REQUIRES`; an e2e per battle kind (G2-S6, G2F) |
| Gifts come by eleven paths and a full party drops one | the per-step party diff as a net; the seams tag it; G2-S6 checks the full-party case |
| No field lock | gen1's hold state on the stack; a `World.step` wrapper as the fallback (G2-S7) |
| Map ids shared with Gen 1 | J4 |
| The clock changes colours, wild Pokémon and people by real time | J5, J6; tests pin the clock |
| Texture slots and client memory | J7: measure first; the raise measured on the client |
| Block edits in the shared map table | pristine blocks for the export; the edits read only by the story |
| Many conversation screens are mirror-only | the mirror works; a Typer reader later |
| START's QUIT and EXIT GAME end the session | hidden, never chosen |
| No Crystal flag names | numbers measured (J11) |
| Join sizes with a third region | index and slots by hash (G2G) |
| Two Kantos in one world could confuse | J4 keeps them apart in every message; J3's alternatives if you'd rather not build Crystal's Kanto |

## 14. What this needs from you

- Nothing to start: G2S runs on a pod against the Crystal you uploaded, and every decision proceeds with its default.
- If you want Gold or Silver covered, upload one through the ROM page (from your phone) at any point (J2).
- Later, at your PC: choose the pass's real exits with F7, and the play-test (PLAYTEST.md "Johto").
