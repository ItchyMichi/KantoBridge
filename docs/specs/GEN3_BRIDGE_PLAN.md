# The Gen 3 bridge: Emerald in Kanto Bridge (MG7's spec)

MULTI_GAME_PLAN.md MG7 asks for this spec before any Gen 3 code: the `game3` engine adapter group by group, the Gen 3
export, the builder's `Gen3FrontEnd`, a synthetic Gen 3 bundle, and an e2e on the user's Emerald import. It rests on the
spikes S2–S5 (PROGRESS.md "Several games: spikes", 2026-10-14) and on four research notes with verified `file:line`
citations, kept git-ignored beside the other research (`refs/_research/`):

| Note | Covers |
|---|---|
| `mg7_core_adapter_interface.md` | every adapter function the bridge core calls (166 names), when, guarded or not, and gen1's return shapes; main.lua's tick order and hooks; the lupa harness; `fake_gen1` |
| `mg7_game3_world_state.md` | `game3` observe, move, warps, NPCs, story store, saving, title/boot, parking, healing, hazards |
| `mg7_game3_ui_battles_data.md` | `game3` UI stack and the state outside it, text, battles, party, bag, names |
| `mg7_export_and_builder_map.md` | the Lua export, the Java builder and scene model, B3.3/B7.2 of the voxel report, fixtures and goldens |

Paths below: `R/` = `refs/gen1recomp` (Gen1Recomp v0.3.85, the pin), `GB/` = `gen1-bridge/`, `J/` =
`fabric-bridge/src/main/java/dev/kantobridge/`. The facts the design needs are restated where they are used.

## 0. How to execute this spec

- **Phases G3A–G3G** (§9), each a commit or a few, in order. G3A makes Emerald followable (a client connects, sees the
  hidden player walk, warp and save); G3B its UI; G3C its story; G3D–G3E the world (export and builder); G3F battles,
  party, items and routes; G3G the synthetic bundle, goldens, GameTests and the pod's Hoenn build. MG8 (the pass to
  Hoenn) follows.
- **Tests per group**: lupa tests on a `game3` fake engine (§8.1); the full suite at the checkpoints marked ✔ in §9;
  e2e on the pod's Emerald import (§8.3) at G3A, G3B, G3C and G3F.
- **Defaults**: §10 lists the decisions this spec takes. Each proceeds with its default unless the user changes it;
  none blocks G3A.
- **Rules that hold throughout** (CLAUDE.md): only the engine adapters touch engine internals; nothing ROM-derived in
  the repository, in logs or in test output (types, ids, counts and hashes only); the agent never imports a ROM (the
  Emerald test import is the user's, uploaded 2026-10-13); tests prefer the stand-ins.

## 1. What the spikes settled

- **Identity**: `GameVersion.engine()` is `game3`, layout `rse`, generation 3; map ids are `EM_*`; `mod.world` is the
  Gen 3 WorldAPI (`R/src/mods/Loader.lua:1395-1408`), `mod.input` exists.
- **Movement and warps**: `world.stepped` fires once per completed step with `mapId, x, y, tile, facing`
  (`R/src/core/game3/player.lua:1222-1230`); `warp.destination` fires for doors, exits, stairs, teleports, falls and
  `warpTo`, and its return redirects the warp (`R/src/core/game3/warp.lua:55-77`); a map edge crossing fires
  `map.exited`/`map.entered{via="connection"}` and no warp hook. There is **no** `movement.collision` (Gen3Compat only)
  and no step-start or bump event.
- **The exit for routes**: `world.stepped` + `mod.world:warpTo` (direct or deferred to the next `input.step`);
  `map_scripts` records are never called on Gen 3 and a warp patched onto open ground stays inactive.
  `replaceBlock(x, y, metatile)` opens a blocked cell. A new map can be registered but not entered.
- **Story**: `FLAG_SYS_POKEMON_GET` (0x860) and `FLAG_SYS_GAME_CLEAR` (0x864); badges `FLAG_BADGE01_GET`…`08`
  (0x867–0x86E) counted by `Flags.countBadges(Space.store)`. `flag.changed` names Emerald flags from FireRed's table:
  **only its `id` is reliable**; names come from `Flags.active(session).NAMES`.
- **UI**: `screen.pushed/popped` don't fire on Gen 3; `render.output` does once a mod enables output (the mirror can
  capture frames). The dataset view serves `pokemon`, `moves`, `encounters` only, and reading `maps` through it
  poisons the view: the Gen 3 export reads the live engine.
- **Two games at once** (S1): Blue ~175 MB, Emerald ~420 MB RSS; both keep 60 fps together. **Windows import** (S5):
  the setup tool's headless import works on the Windows build.

## 2. The adapter: structure

- `GB/lib/engine/game3.lua` is the adapter's entry, loaded by `kb_engine.lua` when `GameVersion.engine()` is `game3`
  (`Engine.ADAPTERS.game3`). It **starts from the stub** (`Engine.stub(opts)`, exported from `kb_engine.lua`, whose
  every function returns the right type for "no game", `kb_engine.lua:116-188`) and overrides group by group, so a
  function not yet written can never throw where the core calls it unguarded (the core calls many optional-group
  functions every step: `ops`, `gifts`, `takeHeals`, `partyNicks`, `releaseCatches`, `takeItemUses`, `takeRenames`,
  `battlePending`, `pcPending`, `metaOf`, `inGame`, `bagCounts`…; core note §6).
- Its groups live in parts, `GB/lib/engine/game3/<part>.lua`, each loaded with `opts.import` (the sandbox's own `load`
  path, never `require`): `base.lua` (module handles, the session, the field and idle checks shared by every part),
  `boot.lua`, `observe.lua`, `move.lua`, `ui.lua`, `story.lua`, `saves.lua`, `region.lua`, and later `party.lua`,
  `items.lua`, `battle.lua`, `export.lua`, `routes.lua`. One part owns one group; parts share state through the base
  table only. CLAUDE.md's rule becomes "only `lib/engine/**` touches engine internals".
- **`Game3.REQUIRES`** lists every module and member the adapter reads (`checkRequired()` → `engine.missing`, as
  gen1's `REQUIRES`, `GB/lib/engine/gen1.lua:62-91`); `pin_check.py` audits every cited engine line in the parts.
- **Lifetimes** (world note §10.12): `Space.store`, object tables and metatile overrides are rebuilt on every map load,
  and Field lock tags are wiped by `Field.start/stop`: the adapter re-reads module state every tick and never caches
  engine tables across `map.entered`. `game.ready` fires once (at the intro): session start is `save.created`/
  `save.loaded` plus `map.entered{via="boot"}`.

## 3. The core changes (small, made before the adapter)

| Change | Why | Where |
|---|---|---|
| `Engine.stub(opts)` exported; `Engine.ADAPTERS.game3`; the loader accepts parts | §2 | `GB/lib/kb_engine.lua` |
| main passes `world = mod.world` (and `input = mod.input`) in the adapter's options | `warpTo`, `getFlag`, `replaceBlock` | `GB/main.lua:86-90` |
| **`engine.saveView()`**: the Gen 1-shaped save facts (`player{map,x,y,facing,name}`, `flags{NAME=true}`, `money`, `coins`, `inventory`, `party`, `pokedex`, `options`, `flashLit`…) for the readers; `engine.save()` stays the save object whose **identity** `kb_save` and `kb_pairing` compare with `save.created`/`save.loaded` payloads (Gen 3 emits `{save = session}`, `R/src/core/Game3.lua:540, 557`). gen1: `saveView = save` | Gen 3's session is not Gen 1-shaped | readers: `observe/kb_snapshot.lua:83-207`, `kb_story.lua:62-71, 288, 325`, `kb_progress.lua:57-80`, `kb_bag.lua:145-146`, main debug.status `:392-501`, `pinDisplay` `:772-773` |
| **`engine.takeVerdicts()`** (optional): main appends the adapter's step verdicts to `events.verdicts` each step, as the `movement.collision` hook does for Gen 1 (`GB/main.lua:1105-1119`) | Gen 3 has no collision hook | `GB/main.lua` step |
| Dex and move ceilings from the catalogue's `compat` (`maxDex`, `maxMove`) instead of 151/165 | Emerald's 386/354 | `kb_snapshot.lua:172`, `kb_ui.lua:266`, `kb_party.lua:195-214, 274-281, 345, 354`, `kb_battle.lua:98-101, 153` (party and battle in G3F) |
| The manifest's `games` gains `emerald`; `protocol/games.json` sets Emerald's `adapter: true` and its markers | the engine's mod gate and the hello gate | `GB/manifest.json:9`; `protocol/games.json`; regenerate `kb_games.lua` |
| `run_gen1recomp.py`: with several adapter games imported, the default is the first Gen 1 game; Emerald tests pass `game="emerald"` | today's e2e tests assume Blue | `tools/e2e/run_gen1recomp.py` `preflight` |

## 4. The adapter, group by group

### 4.1 Boot and identity (link)

`engineId` `game3`, `family` `gen3`, `gameVersion` = `GameVersion.get()` (`emerald`), `version()` = the engine's
version and mod API, `checkPin` as gen1. `setGame(g)` keeps the Game3 object (`game.ready` and every `input.step`
pass it); `game()` returns it. `unionRoomPatched()` → false (Emerald's Union Plaza doesn't touch the exported maps;
recorded, not guarded). `save()` → the live session (`R/src/core/game3/runtime.lua:36`, `getSession()`); `saveView()`
→ §4.7's view.

### 4.2 Observe (link)

`observe()` builds the facts the player observer reads (core note §2 lists them): `inGame` (phase `field`, a session,
`Runtime.isActive()`), `top` (`overworld` when the field is idle or walking; `screen` with a `topName` when a stack
layer, the message box, a choice or a battle is up; `none` in boot), `map` = `Map.current`, `x, y` =
`Player.cellX/cellY`, `facing`, `moving`, `tx, ty` (`targetX/Y`), `frames` (`stepFrames`), `ledge` (`jumping`),
`surf`, `bike`, `runner`, `scripted` (`Player._scriptedStep` or a running VM with a lock), `transitioning`
(`Warp.isBusy()` or a fade), `battle` (`Battle.isActive()`), `npcs` (count). The idle test mirrors `Player.update`'s
gates (`R/src/core/game3/player.lua:1446-1482`; world note §1 "Is the field idle?").

`npcs()` lists `Objects._byId` entries that are `visible and not hidden and not invisible`, skipping localId 127
(camera) and the 0xC0+ seam copies, as `{id = localId, sprite = OBJ_EVENT_GFX_ name, kind, x, y, facing, moving, tx,
ty, progress, frames}`; `kind` = `trainer` when the object has sight, `item` for item balls, else `person`.

### 4.3 Movement and warps (link)

- **Input**: `buttonDown(btn)` / `setFacing(dir)` through `game.input:sourcePress/sourceRelease` with the adapter's
  own source names, inside `input.step` (the press acts in the same step). A tap from rest turns, holding walks
  (`tryMove`'s `turnArmed`, `player.lua:730-856`): the mover's existing hold-until-step logic fits. Emerald bikes read
  `isDown` only (`R/src/core/game3/bike/rse.lua:410-417`): holds, never taps.
- **Verdicts**: the adapter wraps `Player.tryMove` (called through the module table, `player.lua:1503`) once per
  process; a `"blocked", why` return becomes a verdict `{allowed=false, dir, reason}` with `why` mapped to the
  protocol's reasons (`bounds` → `bounds`, `tile`/`elevation`/`water` → `tile`, `entity` → `entity`), taken by
  `takeVerdicts()`.
- **Warps**: `warpDeparture()` from the `warp.destination` context of the last announced warp (kind `door` →
  `door`, `exit_door` → `door`, `stairs`/`escalator` → `stairs`, `warp`/`teleport`/`rse_*` → `pad`, `fall`/`dive` →
  `hole`). Warps without `player.warped` (scripted warps, fly, whiteout) are caught by the observer's map change.

### 4.4 Interaction (link)

`interactedFacts(payload)` from `world.interacted` (`{mapId, x, y, kind, target}`, `R/src/core/game3/field.lua:776-782`);
`cellInMap` from the map's `midLayout` size; `counterCell` from the metatile behaviour (`MB_COUNTER`).

### 4.5 UI (link; G3B)

The adapter synthesises the core's UI stack from Gen 3's scattered state (UI note §2–3): the message box (`Message`),
`Choice` (yes/no, multichoice), the stack layers (`R/src/ui/game3/stack.lua`), the naming layer, and in the boot phase
the title, main menu and Birch's speech. Classes map onto the core's kinds (`GB/observe/kb_ui.lua:144-148`):

| Gen 3 state | Core class | Reading | Answer |
|---|---|---|---|
| `Message.isOpen()` (field, sign, battle) | `TextBox` | `currentPage()` split into lines, colour codes stripped; `waiting` = `isWaiting()` and, on a script's last page, `Hud._waitButton ~= nil` | A |
| `Choice.kind == "yesno"` | `ChoiceBox` | YES/NO, `cursor` | up/down, A, B |
| `Choice.kind == "multi"`, `script_list_menu` | `ListMenu` | `options`/`labels`, cursor | d-pad, A, B |
| stack `start` | `Menu` | `StartMenu.ENTRIES` labels, cursor | up/down, A, B (EXIT is never chosen for the player) |
| stack `naming` | `NamingScreen` | `Naming._state.name`, `maxLen` | `uiSetGlyphs` → `Naming.close(name)` |
| stack `shop` | `Screen` → mirror until G3F (its quantity box and bag projection come with the items; G3B decision) | the frame | taps from the mirror |
| everything else (`party`, `bag`, `summary`, `pc_menu`, `box_storage`, `rse_pokedex`, `rse_region_map`, `trainer`, `option`, scene-kit screens) | `Screen` → mirror | the frame | taps from the mirror |
| a battle | `BattleState` → one mirror, its own text and menus included, until G3F makes battles headless | the frame | taps |
| the boot phase's title, intro and main menu | `Screen` "TitleState" → mirror (as Gen 1's title) | the frame | `region.start` |

Birch's speech is in the boot phase, outside the HUD: its printer lines become `TextBox`es, its gender menu a
`ChoiceBox`, its naming the `NamingScreen` with the automatic name (MULTI_GAME_PLAN D6). The mirror captures 240×160
frames: `mirror.frame` gains Gen 3's size (§7).

**As built (G3B, 2026-10-14; `GB/lib/engine/game3/ui.lua`).** The entries go bottom to top in the HUD's input order
(`R/src/ui/game3/hud.lua:205-313`): the overworld, the message box, a choice, the stack's layers; keys are the
instances (the message's page table, the choice's callback, a layer's table, Birch's printer or menu objects). Text:
`TextIR.toTextBox`'s pages (two lines, `\n` between them) with the 0xFC codes (`TextIR.EXT_ARGS`) and `{…}` tags
removed, `{PK}{MN}` as `<PK><MN>`, a raw `{STR_VAR_n}` filled from the VM. A box is `ready` when its page is shown in
full, it is not a page an auto-scroll turns, and on a stay box's last page only once `Hud._waitButton` is armed.
Birch's printer (`R/src/ui/game3/rse/scene_kit.lua:279-465`) is paged from its tokens (a para, scroll or wait ends a
page); its waits and Birch's own "press before the name" are its stops. The start menu hides EXIT (it asks to quit to
the title, `rse/start_menu_data.lua:143`) and MODS. Three small core changes, inert on Gen 1: a choice's cursor may say
`noByCursor` (Gen 3's NO is the cursor and A: B does nothing on Birch's menus or with `ignoreBPress`); `uiSetGlyphs`
may name the button to tap after it (Gen 3: the cursor put on OK, then A; Gen 1 keeps START); `engine.screenSize()`
(new in the `ui` group) sizes the mirror's canvas and frames.

### 4.6 Story (link; G3C)

- **Flags**: `saveView().flags` = every set flag of `Space.store` by its Emerald name (`Flags.active(session).NAMES`;
  numeric keys normalised). `progressMarkers` reads the catalogue's markers through the same names.
- **Objects**: `storyToggles()` → the current map's objects whose hide flag decides their visibility, keyed by
  localId (`{map = {lid = visible}}`).
- **Cells**: Gen 3 runtime terrain lives in `def.midLayout.overrides` and `Field.metatileOverrides`, rebuilt by map
  scripts on every load and never saved (world note §5). Gen 1's block story (`story.blocks`, ids ≤ 255) does not
  fit: G3C adds **`story.cells{map, cells[{x, y, metatile}]}`** for the current map's overrides against the pristine
  layout, and the export's per-cell swap sites (§5) give the builder both states. Until G3C, `storyMap()` is nil (the
  story module then sends no blocks).

### 4.7 Saves, slots, pairing and the Pokémon Center (link)

- **Slots**: Gen 3 uses the same slot registry as Gen 1 (`SaveData.listSlots/createSlot/setActiveSlot/activeSlot/
  deleteSlot`, `R/src/core/SaveData.lua:1642-1900`, keyed by version; files `saves/emerald/slotN.lua`), so gen1's slot
  and pairing functions carry over with the game id. `slotHasSave` = `Game3:_hasContinueSave()`'s test.
- **Saving**: `switchSave`/`centerSaveStep` call `Game3:saveGame()` only when `quickSaveAllowed()` is true
  (`R/src/core/Game3.lua:1019-1072`); `save.writing` drives `savingReason`/`queueSave` as on Gen 1.
- **The nurse**: Emerald heals through `special HealPlayerParty`: the adapter sees it in the `script.command` hook
  (`scriptCommand(nextFn, ctx, op, row)`, `R/src/core/game3/scripting/ops_a.lua:2352-2357`) and arms `nurseHealed()`.
- **The save view** (`saveView()`): `player{map, x, y, facing, name}` from the session (`name` is the player's own
  entry), `flags` (§4.6), `money` = `session.money`, `coins` from `Bag.Coins`, `inventory` from the bag pockets as
  `{item symbol = qty}` (Emerald symbols from `items_data`), `pokedex{seen, owned}` as counts-bearing lists of
  national dex numbers, `party` = [] until G3F (Cobblemon owns the party), `options` = `session.options`.
  The view is built once per logic step (G3A, `boot.lua`); G3F's writes (`bag.set`, money, items) must drop that
  cache, or a reader later in the same step sees the old bag.

### 4.8 Region (MG2 acts on Gen 3)

- `holdAway`: `Field.lock("kb_park")` (stops input, NPC wandering, interaction, START, coord events, trainer sight,
  encounters; world note §8) plus `Audio.fadeOutBgm`; `awayHeld` = the tag present; `releaseAway` unlocks, retrying
  while the engine refuses (fly landing, fall warps), and fades the map music back in. Re-applied after `Field.start`.
- `startFromTitle(mode)`: in the boot phase, `continue` = `Game3:_handleBootAction{action="continue"}` after the
  title reaches its menu; `new` = the main menu's NEW GAME (taps), after which Birch's speech runs as UI (§4.5) and the
  truck holds the field (`Field.lock("truck")`) until it ends.
- Resume warps use `mod.world:queueScript{{"warp", …}}` (works on Gen 3, S2).

### 4.9 Reliability

`metaOf(save)` from the session's `meta`; `persistent(name)` on the Game3 object as gen1 does; `runHidden` (the hidden
window's pacing) as gen1, reading `love.window`; `hiddenStats` counts.

### 4.10 Battles, party, items (G3F)

- **Wild**: grass encounters come from Cobblemon habitats (as Kanto): the `encounter.roll` hook returns nil on grass
  (`R/src/core/game3/encounters.lua:480-497`). Fishing, Rock Smash and Sweet Scent bypass the hook and go to
  `BattleBridge.start`, which the adapter wraps.
- **Trainers and scripted battles**: every battle passes `BattleBridge.start(mod, game, foe, opts)`
  (`R/src/core/game3/battle_bridge.lua:397-743`): the adapter wraps it, starts it **headless** with `autoFight=false`,
  hands the foe's team to the client (`trainerTeam`), and on `battle.result` writes HP/status/PP, money (prize), exp and
  catches into the battle state and calls `Battle.abort(result)` on the next step, so the engine records the outcome,
  runs the whiteout, emits `battle.ended` and resumes the script (UI note §5, "recommended way"). Link, Frontier,
  Safari and Wally's tutorial stay native.
- **Party**: internal species ids ↔ national dex through `Pokemon.national`/`speciesFromNational`
  (`R/src/core/game3/pokemon.lua:281-301`); `applyParty` rebuilds `session.party` with `Party.giveMon` records;
  `move_overlay` reset.
- **Bag and money**: `session.bag.pockets`, `Bag.add/remove/set`; `protocol/items.json` gains `symbols.gen3`
  (Emerald's `ITEM_*` names); the MG6 ledger projects into it.
- **Routes** (§1): the zone's exit is `world.stepped` at the exit cell → the pass; the hidden player's stand-in for the
  zone is the **parked exit cell** (no route map of its own, §10 E6); `replaceBlock` opens the exit cells.

## 5. The Gen 3 export (G3D)

The world note and the export note give the facts; the shape is the scene draft's (B3.3) with D13's quarters:

- **New kinds**, served beside today's (protocol §7): `g3map/<EM_ID>` — `w, h` in cells; per cell `mt` (metatile id),
  `coll`, `elev`, `beh` (behaviour name index); `border`; `connections{dir:{map, offset}}` in cells; `warps`; `objects`
  (localId, gfx, x, y, elev, flag-hidden); `mapType`, `music`; `g3tiles/<pair>` — the pair's metatiles as **RGBA
  quarters per plane** (under, middle, over), deduplicated into atlas pages of ≤ 1024 8-px quarters
  (`image/<pair>.<plane>.<n>`), each metatile listing its 4 quarter ids per plane; `g3sprites/all` — object graphics as
  RGBA sheets with frame tables (standing 0-2, walking 3-8, running 9-17; export note §3.1), keyed by `OBJ_EVENT_GFX_*`; `layout/hoenn` in cells;
  `encounters/all` in Gen 3's shape (land 12, water 5, rock 5, fishing 10 slots, national dex); `names/all` for Gen 3.
- **Art is baked in Lua**: the adapter composes each quarter from the pair's indexed atlases and palettes
  (`NativePack.palsToRgb8`, `def.midLayout`; export note §3.1 B7.1) into RGBA, so Java never sees Gen 3 palettes
  (`paletteModel = "baked"`). Animated tiles (water, flowers) export their frames as extra quarters.
- **Measure first**: before the builder work, a pod driver counts (numbers only) the unique quarters per plane and pair,
  the maps, and the quarters Hoenn's maps use, to size atlas pages and slot capacity (§10 E7).

## 6. The builder (G3E)

- `FrontEnds.forGame` returns **`Gen3FrontEnd`** for family `gen3` (`J/scene/FrontEnds.java:13-19`): it builds the
  `Scene` from the `g3*` resources: `perCell` 2 (quarters), `artPx` 8, `paletteModel` baked, kinds from behaviours
  and collision (WALK, SOLID, WATER, LEDGE), tags (grass, door, warp…), `env` from `mapType`.
- **WorldPlanner reads the Scene for Gen 3**: a Gen 3 branch places maps by `layout/hoenn` (cells), builds floor and
  walls from the scene's kinds (flat; invisible edge walls where an elevation mismatch blocks a step, MULTI_GAME_PLAN
  §4.10), puts habitats on grass cells, and feeds the shape planner as Kanto does. Gen 1 keeps today's path untouched.
- **Textures**: a baked-art `ArtSource` and texture writer (RGBA tiles, no shades); slot keys use the atlas page and
  quarter id (palette 0).
- **Habitats**: Gen 3 land slots → pools of `cobblemon:` species by national dex (Cobblemon's own registry, up to 386).
- **Two art sets in one pack** (MG8's prerequisite, MULTI_GAME_PLAN): `PackGenerator` merges Kanto's and Hoenn's sets.

## 7. Protocol changes

| Phase | Change |
|---|---|
| G3A | none on the wire (Emerald's `hello.ok` already carries `family`/`compat`; `EM_*` ids fit the map-id pattern) |
| G3B | `mirror.frame`: `w`/`h` either 160×144 or 240×160 |
| G3C | `story.cells{map, cells[{x, y, metatile}]}` (Gen 3 runtime terrain) |
| G3D | export kinds `g3map`, `g3tiles`, `g3sprites`; `encounters` and `names` gain Gen 3 shapes; `exportKind` enum |
| G3F | dex/move ceilings by `compat` in `party.sync`, `battle.*`, the snapshot; `items.json` `symbols.gen3` |

Every change: schema, PROTOCOL.md, `kb_protocol.catalog --write`, the Java readers, as MG1–MG6 did.

## 8. Tests

### 8.1 The `game3` fake engine (lupa)

`tools/lua/fake_engine3.lua` fakes what the parts read: `src.core.game3.{runtime, player, field, map, warp, objects,
collision, scripting.space, scripting.flags, battle.init, battle_bridge}`, `src.ui.game3.{message, choice, stack,
naming, hud, fade}`, `src.core.SaveData` slots, `src.core.GameVersion` (engine `game3`, game `emerald`, generation
3) and a Game3 object (`phase`, `boot`, `input`, `session`). Made-up maps, flags and text only. `kblua.Bridge` gains
`family="gen3"` to load it; `rig.Rig(family="gen3")` boots main.lua on it.

### 8.2 Unit tests per part

`tools/lua/test_lua_game3_<part>.py`: boot and identity (catalogue, hello.ok on Emerald, REQUIRES), observe (idle
gates, a step, a warp, NPCs), move (taps vs holds, verdicts from `tryMove`), UI (message pages and waiting, yes/no,
lists, naming), story (flag names, toggles), saves (slots, the nurse, guarded saves), region (park/resume/start).

### 8.3 e2e on the pod's Emerald import

A driver `tools/e2e/drivers/kb_emerald.lua` (scenes: `title`, `field` — a new game straight into the field on a
throwaway save slot, deleted after) and `tools/e2e/test_emerald.py`: hello.ok `family gen3`; the snapshot in
Littleroot; `move.step` walks and a blocked step's reason; a door warp; a text box (an NPC); a save
(`switchSave`); `region.start` CONTINUE and NEW GAME from the title; park/resume. Output: types, ids, counts only.

### 8.4 Java

`Gen3FrontEndTest` on a synthetic Gen 3 bundle (`fake_gen1 --family gen3` writes it: made-up metatiles, colours,
maps `G3_TEST_*`, `layout/hoenn`); a golden scene; a GameTest building a synthetic Gen 3 region at slot 1 with shapes.

## 9. Phases

| Phase | Delivers | Check |
|---|---|---|
| **G3A** core changes; adapter base, boot, observe, NPCs, movement, warps, interaction, saves and slots, region, reliability; catalogue and manifest; the fake engine | Emerald followable: hello.ok, snapshot, walking, warps, saves, game.status, region acts | lupa per part; e2e `test_emerald.py` (all but the text box) ✔ — **done 2026-10-14** (PROGRESS.md "Several games: MG7": the deviations `chainsHeld` and `starting`) |
| **G3B** UI | text boxes, choices, lists, menus, naming, Birch's speech, the 240×160 mirror (Lua, schema, client) | lupa UI; e2e text box ✔ — **done 2026-10-14** (a Littleroot sign read through the UI; Birch's whole speech answered through the UI into the field) |
| **G3C** story | flags, toggles, `story.cells` | lupa; e2e a flag and an override |
| **G3D** export | `g3*` resources, baked quarters, encounters, layout; the measurement driver first | lupa on the fake; pod export counts and hashes ✔ |
| **G3E** builder | `Gen3FrontEnd`, the Gen 3 planner path, baked textures, habitats, two art sets in one pack | JUnit, golden scene, GameTest ✔ |
| **G3F** battles, party, items, routes | headless battles with `Battle.abort`, party sync, bag projection, the route exit | lupa; e2e a wild and a trainer battle ✔ |
| **G3G** | synthetic bundle, goldens, full suites; the pod's Hoenn build (MG8's first step) | full suite ✔ |

## 10. Decisions (defaults proceed)

| # | Question | Default |
|---|---|---|
| E1 | One adapter file or parts | **parts** under `lib/engine/game3/`, one group each, starting from the stub |
| E2 | Birch's intro | **plays as UI** (text, the gender choice, automatic name), as MULTI_GAME_PLAN §2 describes |
| E3 | Battles | **headless with `Battle.abort`**, the client deciding the result (as Kanto's battles); grass from habitats |
| E4 | Gen 3 art | **baked RGBA quarters** in the export (Java never sees Gen 3 palettes) |
| E5 | The `over` plane | composited into the cell's art for the first build; a separate cutout layer later |
| E6 | The hidden player during a route zone | **parked at the exit cell** (Gen 3 can't enter a new map) |
| E7 | Slot capacity for Hoenn | **measure first** (G3D); if Hoenn needs more than its 16 families, raise the families per shape (memory cost noted at MG3: ~15 MB a side per 16) |
| E8 | Elevation | **flat** with invisible walls on mismatches (MULTI_GAME_PLAN §4.10) |
| E9 | The mirror's size | **240×160** frames for Gen 3, scaled by the client |

## 11. Risks

| Risk | Mitigation |
|---|---|
| `game3` is beta upstream and its internals move | `REQUIRES` and `pin_check.py`; the pin stays v0.3.85 |
| Many optional functions are called unguarded every step | the stub as the base (§2); a lupa test runs main.lua's step on the fake with every part missing |
| Scripted warps, fly and whiteout give no `player.warped` | the observer's map and cell are the truth each tick (world note §10.3) |
| Text on the last page needs `_waitButton` armed; Emerald draws no arrow there | `waiting` from the engine's own state, not the arrow (§4.5) |
| Slot capacity | §10 E7 |
| Battles' side effects happen inside the battle (exp, prize, evolution, dex, catches) | the adapter applies them into the battle state before `Battle.abort` (§4.10); e2e per battle kind |
