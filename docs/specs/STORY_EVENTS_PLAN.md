# Story events in every game: the check, and Emerald's fixes

The user, 2026-10-15: "The Kanto bridge code now includes all three regions. I want to go through and make sure all the
story events work in every game. Starting with Emerald, can you check every story event and world change as a result
works (such as the flooding and drought events). Use RunPod for real tests."

This document is the method of that check (reusable for Crystal and Blue), Emerald's results from the real engine on a
pod, and the plan to fix what doesn't work. Paths: `GB/` = `gen1-bridge/`, `J/` =
`fabric-bridge/src/main/java/dev/kantobridge/`, `R/` = `refs/gen1recomp` (the engine source, v0.3.85).

## 1. Summary

| Part | Hidden Emerald | Bridge (gen1-bridge → client) | Minecraft |
|---|---|---|---|
| Terrain (cells a script, a layout swap or the engine changes) | **works** in every case tried | **sends** every metatile change exactly (323 of 323 cases compared); **drops** the 267 cells whose collision alone changes | **not shown, not collided**: `story.cells` is kept (`StoryRules.liveCells`) but nothing reads it; Gen 3 builds have no tile story cells |
| Weather (the map header's, coord weather, `setweather`/`doweather`, the Groudon/Kyogre "abnormal" weather, the Terra/Marine Cave drought and downpour) | **works** | **not sent** | **not shown** (Minecraft's weather is off by design, DESIGN.md) |
| Light (dark caves; Dewford Gym's flash levels) | works | dark caves only (`g3map.dark` + Flash) | dark caves only |
| People and objects appearing and disappearing (hide flags) | works | **works**: the client's NPC list is the engine's visible objects and the story toggles are the flags (372 of 372 compared visits) | works (puppets from the NPC list, object story cells from the toggles) |

What that means in play, in Minecraft today (the hidden game is right; Minecraft keeps the map as built):

- **Story blockers** (Minecraft solid where the game is open): the Elite Four's exit doors never open (each member's room
  opens 2 cells when beaten); Sky Pillar's floors (the "clean" layouts before Rayquaza wakes: 13-32 cells per floor);
  the Mauville and Mossdeep Gym puzzles (barriers and switches); Shoal Cave at high tide (40 cells open up); Route 131's
  layout, which the game swaps on every visit (336 cells, 32 of them water that becomes land); Mirage Island when it
  appears (483 water cells become land); Sootopolis during the crisis (646 cells); the Abandoned Ship's key doors; the
  Terra Cave entrances (Routes 114-118, post-game).
- **Rubber-banding** (Minecraft open where the game is closed; the client snaps back on `player.blocked`): the Regi
  chambers before their puzzles, the Magma Hideout entrance before the story opens it, the Elite Four entrances once
  closed behind you, the Fossil Maniac's tunnel, Shoal Cave's tide pools.
- **Looks wrong** (art only): ash, TVs and PCs, the Lilycove museum's paintings, the Mirage Tower, the Petalburg Gym
  doors (all walkable either way), and every weather and flash change.

## 2. The check (method; rerunnable)

- **Driver** `tools/e2e/drivers/kb_g3story.lua` on the real engine (fresh test save slot, new game, cleaned up after),
  run by **`tools/e2e/g3_story_check.py`**:
  - `scan`: a static walk over every map's scripts in the engine's script bundle (`Space.bundle`: the importer already
    decoded every script into rows; `R/src/import/gba/extract_scripts.lua`), from each map's entry scripts (onTransition,
    onLoad, onResume, onReturnToField, onDiveWarp, the onFrame and onWarpIntoMap tables) and its objects', signs' and coord
    events' scripts, following every branch with the conditions taken on the way (checkflag, the compares, switch/case,
    specialvar results). Per map: every command that can change the world (setmetatile, setmaplayoutindex with the
    alternate layout's differing cells, the weather and flash commands, step callbacks, door states, object visibility and
    placement, warps, specials by name), the header's weather and cave flag, coord weather. Output
    `local/reports/g3_story_scan.json` (ROM-derived, stays in `local/`); the summary prints names, ids and counts.
  - `sweep`: the live check. Cases from the scan: per map whose entry scripts change the world, the new game's state and
    every satisfiable condition path (flags and vars solved; temp and special vars left out; VAR_ABNORMAL_WEATHER_LOCATION
    only with its own route's values; the tide and Mirage Island forced through their natives). Per case the flag store
    goes back to the new game's, the case's flags and vars are set, the game warps in (no fade: `Map.load` runs the entry
    scripts at once), then once idle (onFrame scripts given 6 s, then halted): every layout override against the map's own
    cell (metatile, collision byte, its class walk/water/solid/ledge, elevation), the saved and drawn weather, the flash
    level. A stand-in Minecraft client (`fake_minecraft`, role test) is connected the whole time; the test rebuilds what it
    was told per visit (`story.cells`) and compares it with the engine's cells.
  - `objects`: per map with hideable objects, every hide flag set, then every one clear; per visit the client's NPC list
    (`npc.set`/`add`/`remove`) against the engine's visible objects, and the story toggles against the flags.
- **Safety** (PLAN.md §0.3): the driver prints map symbols, ids, counts and booleans; the per-cell truth stays in
  `local/reports/`; nothing is saved into the install's own slots.
- **Pod**: kb-dev24 (cpu5g x4, 16 GB). Scan ~1 min; sweep 323 cases ~8 min; the sea routes' rerun (they have no warp to
  land on; the driver now picks a walkable or water cell) 1 min.

## 3. Emerald's results (2026-10-16, Gen1Recomp v0.3.85, the pod's Emerald import)

### 3.1 Inventory (the scan)

519 maps, 4,047 script roots, 407,391 rows walked (no walk truncated). World commands (distinct row × condition path):
setmetatile 3,584; setmaplayoutindex 23; setweather 72, doweather 32; setflashlevel 64, animateflash 7; setstepcallback 37;
object commands (removeobject 724, addobject 302, hideobjectat 442, setobjectxyperm 264, ...); warp setters 167.
Changeable cells (scripts and layout swaps, the Battle Frontier, secret bases and link rooms left out): **4,990 cells on 79
maps**; 4,629 have one alternative state, 325 two, 36 three or four (Trick House puzzle 3, New Mauville, Sootopolis, the
Pokemon Centers' upper floors).

Layout swaps (the game swaps the map's whole layout at load; cells that differ):

| Map | Layout | Cells | When |
|---|---|---|---|
| EM_SHOAL_CAVE_LOW_TIDE_ENTRANCE_ROOM | 168 | 565 | high tide (FLAG_SYS_SHOAL_TIDE, by the clock) |
| EM_SHOAL_CAVE_LOW_TIDE_INNER_ROOM | 169 | 772 | high tide |
| EM_ROUTE130 | 46 | 955 | Mirage Island present |
| EM_SOOTOPOLIS_CITY | 357 | 645 | the Groudon/Kyogre crisis (VAR_SOOTOPOLIS_CITY_STATE) |
| EM_ROUTE131 | 319 | 336 | always |
| EM_SKY_PILLAR_1F..5F, _TOP | 433-438 | 15-163 | VAR_SKY_PILLAR_STATE < 2 |
| EM_ROUTE111 | 392 | 18 | the Mirage Tower gone |
| EM_LITTLEROOT_TOWN_PROFESSOR_BIRCHS_LAB | 432 | 8 | the post-game Johto starters |
| EM_SOOTOPOLIS_CITY_MYSTERY_EVENTS_HOUSE_1F | 441 | 15 | a mystery event |

Weather set by scripts: ABNORMAL (alternating drought and downpour) on Ever Grande City, Lilycove, Mossdeep, Routes
124-131, Sootopolis and outside Sky Pillar during the crisis (FLAG_SYS_WEATHER_CTRL); DROUGHT on Routes 114, 115, 116,
118 and DOWNPOUR on Routes 105, 125, 127, 129 for the post-game Terra and Marine Caves (VAR_ABNORMAL_WEATHER_LOCATION);
VOLCANIC_ASH on Route 113 and the Jagged Pass; SANDSTORM in Route 111's desert; RAIN, SUNNY and clouds on Route 120;
FOG on Mt. Pyre; the daily cycles of Routes 119 and 123; coord weather on Routes 113, 119, 120, 123 and the Jagged
Pass. Map headers: NONE 429, SUNNY 51, SHADE 14, UNDERWATER_BUBBLES 14, FOG_HORIZONTAL 10, VOLCANIC_ASH 1.
Light: Dewford Gym (`setflashlevel` 0-7 as its trainers fall); the seven caves with the cave flag.

### 3.2 The hidden game

Every case did what Emerald does: the tide swaps flood the cave (565 and 776 cells, 135 and 252 of them land turning to
water), Mirage Island rises (955 cells), Sky Pillar's clean floors, Sootopolis's crisis layout with DOWNPOUR or ABNORMAL,
the Terra and Marine Cave entrances with DROUGHT or DOWNPOUR, the Elite Four doors, the gym puzzles, the Regi chambers'
walls, the Magma Hideout entrance, the Lilycove submarine cave, Route 131's layout. 43 cases started an onFrame cutscene
that waited for the player and were halted after 6 s (their cells and weather were recorded at that point).

Engine notes (upstream Gen1Recomp, not ours to change; worth reporting there):

- The post-game abnormal weather never ends by steps: `AbnormalWeatherHasExpired` (pokeemerald, 999 steps) is missing from
  `R/src/core/game3/step_events.lua`.
- Special `StartDroughtWeatherBlend` has no Emerald handler (only Ruby/Sapphire's).
- `Field.setMetatile` recomputes collision from the new metatile's behaviour (the base cell's collision is dropped); a
  layout swap whose size differs does nothing but log; the escalator animation uses FireRed's metatile ids.

### 3.3 The bridge

- `story.cells` matched the engine in **all 323 compared cases** (302 + the 21 sea-route cases): every metatile change, the
  map's own metatile for a cell that went back, nothing extra.
- **Gap B1:** a cell whose collision (or elevation) changes but not its metatile is never sent (`storyCells` keeps only
  `mid ~= base`): 267 such cells across the cases (Route 131's swap 61, Mirage Island 20, Shoal Cave 2-8, the Pokemon
  Centers' doors, the Game Corner).
- **Gap B2:** no weather and no flash level reach the client.
- Out of scope here: the Battle Pyramid and Trainer Hill replace their whole layout object (no overrides to diff).

### 3.4 Minecraft

- `ClientStoryView` hands `story.cells` to `StoryRules.onCells`, which keeps the current map's cells; nothing reads them
  (`StoryRules.liveCells` is called only by its unit test). `G3StoryCells` plans object, water and boulder cells only
  ("Gen 3 has no swap sites"), so no Gen 3 cell has a story block or closed art. Every terrain change above therefore
  neither shows nor collides; 177 cases change a cell's walkability (5,696 cell changes: solid→walk 666, water→walk 132,
  walk→solid 425, walk→water 2,287, solid→water 1,640, water→solid 546).
- Weather: the server forces clear weather; no client weather exists.
- Light: dark maps come from `g3map.dark` (the cave flag) and Flash; Dewford Gym is drawn lit.

### 3.5 People and objects

The `objects` run: 190 maps with hideable objects, two visits each (every hide flag set, then every one clear), 372
visits compared (the 6 unused contest halls have no warp to land on). In every one the client's NPC list was exactly
the engine's visible objects, and the story toggles hid exactly the objects whose flag was set after the load (temp
flags are cleared on every load and entry scripts set or clear some, so the case's own setting is not the truth). One
engine quirk, harmless here: the Trick House's end room shows its object although its flag is set (the client draws it
too, as the engine does). This part needs no fix.

## 4. The fix plan (Emerald)

### 4.1 Decisions

Answered by the user on 2026-10-16: **S1–S7 with their defaults** ("Go with defaults"), **S8: mirror the weather per
player**, **S9: dark until fully lit**.

| # | Question | Default |
|---|---|---|
| S1 | Where the changeable cells come from | **The export**: the adapter enumerates them from the script bundle (the scan's walk, made part of `GB/lib/engine/game3/`), the alternate layouts' diffs and the engine writers with fixed tables (Mauville and Petalburg Gyms, Sootopolis Gym's ice, ash grass, cracked floors, the braille chambers, TVs and PCs, the Mirage Tower). The builder needs them at build time: a cell's story block and its states' art must exist before anyone plays |
| S2 | Changes with no condition (Route 131's layout, a few always-set cells) | **Baked into the export's map**: the world is built as the game always shows it |
| S3 | What a changeable cell is built as | **Its most walkable state** (ground, then water, then solid; the map's own cell on a tie). Every other state is drawn per player and collides for that player, as Gen 1's swap sites are built open and closed per player |
| S4 | Where a cell's collision comes from at run time | **The hidden game's live cell**: `story.cells` gains each cell's pass (`ground`, `water`, `solid`), and cells whose collision alone changes are sent too (gap B1) |
| S5 | How a changed cell's art is drawn | **Per player, the cell's live metatile**, by the story cell renderer (a block-entity renderer, as Gen 1's closed art before V5), from slots the build adds for every state of every site. Any number of states per cell |
| S6 | Maps other than the hidden player's current one | **The map's own cells**, as Gen 1 and the engine itself (it shows neighbours' own layouts too) |
| S7 | Left out | The Battle Frontier's generated floors (Pyramid, Trainer Hill), secret bases' decorations, link rooms; the Fortree bridges' and Pacifidlog logs' step bounce |
| S8 | Weather | **Mirrored per player** (the user, 2026-10-16; DESIGN.md's "weather off" stays true of Minecraft's own weather): the hidden game's weather drawn per player on the client only (rain, thunder and downpour as rain/thunder with the game's lightning; fog; falling ash; sand; snow; drought as a bright warm tint; shade darker; underwater bubbles; ABNORMAL alternating as the game does), the server's weather untouched |
| S9 | Dewford Gym's flash levels | **Dark until fully lit** (the user, 2026-10-16): the gym counts as dark while its flash level is above 0 (no light, as an unlit cave), lit at 0 |

### 4.2 Phases

| Phase | Delivers | Check |
|---|---|---|
| **SE1** bridge | `story.cells` cells carry `pass`; cells whose pass alone changed are sent (S4); schema, PROTOCOL.md | lupa (fake_engine3); the sweep again: 0 collision-only cells missing |
| **SE2** export | `g3sites`: per map the changeable cells, the map's own state and every other state (metatile, pass) with their sources (S1, S7); unconditional changes baked into `g3map` (S2); schema | lupa; the pod's export against the sweep (every changed cell of every case is a site, with the state the case reached) |
| **SE3** builder | `G3Sites` reader; `G3StoryCells` tile cells (built state S3, the other states with art and pass); the cell's model built in its built state; the slot map gains every state's quarters (S5); synthetic bundle, goldens | JUnit; GameTest (a synthetic Gen 3 site opens and closes per player) |
| **SE4** client | `StoryRules`: a Gen 3 tile cell's state from the live metatile and pass (current map) or its own cell (others); active = the pass (solid; water unless surfing); the renderer draws the live state's art | JUnit |
| **SE5** pod | Hoenn re-exported and rebuilt (the three-region world); every sweep case replayed through `StoryRules` on the real index (each changed cell's active state equals the engine's walkability class, and its art exists); the real client at the Elite Four's door and Shoal Cave at high tide | the replay; a short video if wanted |
| **SE6** weather (S8) | `field.weather` (or `player.state.weather`) from the adapter; the client's weather effects | lupa; e2e (the sweep's weather cases reach the client); the real client |
| **SE7** light (S9) | flash level per map in `story.lit` or `player.state` | lupa; e2e |
| **SE8** wrap-up | PROGRESS, PLAYTEST "Story events (Emerald)", the follow-ups list; the method kept for Crystal and Blue | — |

### 4.2.1 As built (2026-10-16)

| Phase | Commits | What it does, as built | Check on the real game (pod kb-dev24) |
|---|---|---|---|
| SE0 | `192decc` | the contracts first: `story.cells` and the snapshot's cells carry `pass`; export kind `g3sites`; WorldIndex story cell type `cell` (`start`, `built{metatile, pass}`, `states[{metatile, pass, height, art}]`); `player.state.weather`; g3map `dark` for maps their scripts darken; four synthetic sites | — |
| SE1, SE2, SE6, SE7 (bridge) | `1242533`, `5900007` | `game3/sites.lua`: the scan's walk inside the adapter (every map's scripts, every branch with its conditions; VAR_ABNORMAL_WEATHER_LOCATION only with its own route's values; S7's places left out); the export's sites from setmetatile (0x07 when impassable, else the engine's own collision for the metatile), alternate layouts' diffs, the engine writers dry-run (Mauville and Petalburg Gyms, the Mirage Tower, the lottery laptop) and cells by behaviour (ash, thin and cracked ice, cracked floors, TVs); a state whose metatile the map's pair lacks is left out (Mossdeep's Game Corner reaching the cable club's doors); unconditional entry changes baked into g3map; `story.cells` with each cell's pass, collision-only cells sent, the map's own cell the exported one; `player.state.weather` from the engine's drawn field weather; Dewford Gym exported dark and lit only at flash level 0 | real export: 4,343 sites on 113 maps, 4,786 states, 343 cells baked, 4 states left out (foreign), 0 dropped, 0 truncated; **every one of the sweep's 14,685 changed cells is baked or a site's state** (`g3_story_check.py sites`); the sweep again: `story.cells` matches in every case, collision-only cells 50 of 50 sent, nothing wrong or extra (Route 131's 275 baked cells correctly not sent); the Emerald e2e suite 8 passed |
| SE3, SE4 (Java) | `dc74a4b` | `G3Sites`, `G3Built` (the most walkable state; the patched maps feed the models, the slot map, the scene and the shapes), `G3StoryCells` `cell` entries (solid-built, ledge-built, under-object and off-grid sites counted and left out), every state's quarters in the slot keys, cell cells placed as story tiles drawn by `StoryCellRenderer`; `StoryRules` keeps live cells' metatile and pass, `ClientStoryView.drawnState`; `FieldClick` sees a flooded cell as water (Surf) | Hoenn rebuilt in ThreeRegionsE2E: 508 maps, **3,756 cell story cells** (566 solid-built and 21 under objects left out), 19,041 textures (was 18,360), 73 of 80 families, shape map 2,073,536 chars (unchanged), build gates OK; **the replay of all 323 cases through `StoryRules` on the real index: 0 missing, 0 colliding unlike the game** (9,002 cells drawn in their state, 2,831 in the built state, 21,315 in their own) |
| SE6 (client weather) | `e8b1be1`, `8ecbc1c` | `client/weather/` and four client mixins (the module's README); the setting `weather` | the real client: rain, drought, downpour, the crisis's abnormal weather (its downpour phase), ash and bubbles drawn as the game draws them; **the first real run crashed the client on the bubbles** (the particle atlas taken from the wrong manager), fixed in `8ecbc1c` |
| SE5 (real client) | `fca11e0`, `739771e`, `8ecbc1c` | `RealGen3StoryReplayTest`; `tools/e2e/story_spots.py` with `drivers/kb_g3spots.lua` (the hidden Emerald put into a sweep case's story state, the real dev client following it, walking into the changed cell; RemotePilot's `story` op reads the cell's blocks) | **16 of 16 spots**: through both Elite Four exits (into the next hall), a Mauville Gym barrier, Sky Pillar's clean floor, Route 131's baked land, Mirage Island, Sootopolis's crisis layout; Shoal Cave's high tide refused by the game and walled by Minecraft (all four blocks of the cell closed); six weathers; Dewford Gym dark at level 7 and lit (Night Vision) at level 0. Video on the volume: `/workspace/videos/kanto-bridge-emerald-story-spots-20261016.mp4` (7.1 MB, sent to the user) |

Left as they are: the Battle Frontier's generated floors, secret bases' decorations and the link rooms (S7); the PC and
elevator lights (their tables are local to the engine: art-only); a state's elevation (edge walls stay the built
cells'); crosshair picking on drawn cell art; the game's weather clouds; lightning on Minecraft's own clock.

### 4.3 Risks

| Risk | Mitigation |
|---|---|
| A script's cell attributed to the wrong map (a shared script reached from several maps) | the scan already filters VAR_ABNORMAL_WEATHER_LOCATION by its routes; SE2's check compares every site with the sweep's live cells, and a cell outside the map or never reached is dropped and counted |
| More slots than the slot map holds | Hoenn uses 18,609 of 20,480; most alternative metatiles share quarters with the map (water, rock); SE3 measures, and the build gate refuses a full map |
| Big swaps (Mirage Island 955 cells, Sootopolis 646) cost many block-entity draws on that map | only the current map's changed cells draw; measured in SE5 |
| The hidden game halts in a cutscene the client can't see | unchanged by this plan; the sweep halts them only as a test device |

## 5. Crystal's results (2026-10-16, Gen1Recomp v0.3.85, the pod's Crystal import)

The same three runs (§2) with Crystal's own tools: `tools/e2e/g2_story_check.py` (`scan`, `summary`, `cases`, `sweep`,
`objects`, `report`, `sites`) and its driver `tools/e2e/drivers/kb_g2story.lua`; the Minecraft side by
`RealGen2StoryReplayTest` (the sweep's cases through `StoryRules` on the real Johto) and `tools/e2e/story_spots_g2.py`
with `drivers/kb_g2spots.lua` (the real dev client). Truth files stay on the volume (`local/reports/g2_story_*.json`).
Crystal already had what Emerald lacked (MG9 G2C): `story.blocks`, swap sites from a walk of every map's scripts, tile
story cells built open and closed per player. So this was a check, and it found one gap and one test-tool fault.

### 5.1 Inventory (the scan)

- 389 maps (Johto and Crystal's Kanto), 2,713 entry points (callbacks by type, scene scripts, coord and bg events,
  objects, trainers), every reachable row walked with its conditions: what the script result register holds
  (checkevent, checkflag, checkscene, checkmapscene, checktime, readvar, readmem, setval). The walk is capped at 48
  condition paths per script and entry point. 17 maps' walks reach the cap: the Battle Tower, the Dragon Shrine's quiz,
  the Bug-Catching Contest gates, the Goldenrod underground switch room's 11 switches and others. The sites check
  (§5.3) shows nothing was lost.
- **Terrain: `changeblock` only** (no `changemapblocks`; no special edits blocks except the bedroom's decorations).
  On map load, on 25 maps:
  - the Elite Four's five rooms (the entrance shut behind the player, the exit opened);
  - the four Ruins of Alph puzzle chambers (the wall, the floor);
  - Burned Tower 1F and B1F;
  - the Goldenrod Dept Store B1F (box layouts) and roof (the sale);
  - the Goldenrod underground (a door) and its switch room (16 shutters);
  - the Mahogany Mart's stairs, the Radio Tower 3F's card-key shutter, the Rocket hideout's B2F and B3F doors;
  - Route 19's rocks, Blackthorn Gym 1F's boulder holes, Tin Tower 1F, the Celadon Dept Store 6F.
  The same maps' events (talking, signs, steps) write blocks too.
- **People:** appear, disappear, moveobject and variablesprite on load on 46 maps (Ilex Forest's Farfetch'd, Tin
  Tower's beasts, Kurt, the Lake of Rage, the ports, weekday people and others).
- **No weather in Gen 2.** Light: 13 dark maps (PALETTE_DARK: Dark Cave, Rock Tunnel, the Whirl Islands, Silver Cave's
  first room), black until Flash.

### 5.2 The hidden game (the sweep)

- 230 cases on 59 maps, covering every distinct load-time command at least once.
- 229 cases stayed on their map; one entered the Bug-Catching Contest. Three long scenes were halted (the Dragon
  Shrine, Elm's lab, Mr. Pokémon's house).
- Every change the cases ask for happens: 329 changed blocks.
- The first run crashed the game in Elm's lab. The cause was the test's own hook, which answered a yes/no inside the
  VM's resume; the sweep now answers a frame later, as a player would, and counts a script error against its case. No
  case hit one.

### 5.3 The bridge

- **`story.blocks` is exact in all 229 cases:** the client holds the engine's changed blocks, nothing missing, wrong or
  extra. No `story.unknown_block` was logged.
- **The export's swap sites:** 137 on 50 maps (40 Cut trees, 6 Whirlpools, 64 script sites, 27 decoration sites).
  - Every one of the scan's 2,077 block writes that changes a block is a site, and so are all 66 distinct changes seen
    live.
  - The other way round, every one of the 64 exported script sites is a write the scan found, so the cap on the
    scan's walk lost nothing.
- **People:** in all 229 cases the client's NPC list matched the engine's live people and the toggles matched the
  engine's masks. The objects run covered every event-flagged person on 160 maps, all flags set and then all clear
  (320 visits). In all 318 visits that stayed on their map the NPC list matched the engine's people and the toggles
  matched its masks; the other 2 left the map (Ecruteak Gym's scene).

### 5.4 Minecraft

- **The replay** (`RealGen2StoryReplayTest`) ran the 229 cases through `StoryRules` on the real Johto (ThreeRegionsE2E,
  built 2026-10-15). Of 609 changed cells:
  - 104 were drawn in their closed state, 352 were open tile cells built as the game shows them, and 153 were built as
    the live block already;
  - 0 were missing, 0 collided unlike the game, and 0 had the wrong art.
  The 1,371 other tile cells of those maps sat in their own state, all colliding as the game does.
- **The real client:** 14 of 14 spots passed:
  - through the five Elite Four exits (each into the next room, Lance's into the Hall of Fame);
  - onto the Mahogany Mart's stairs, and through the Radio Tower's shutter, both Rocket hideout doors and a Goldenrod
    underground shutter;
  - walled off by the Ruins of Alph's closed wall and Route 19's rocks;
  - Dark Cave dark, then lit by Flash.
  The first run passed 9: halting a scene script left the World's text box marker set, so the hidden player stopped
  taking steps. The halt now clears every marker `World:busy` reads, and the next run passed 14 of 14. Video on the
  volume: `/workspace/videos/kanto-bridge-crystal-story-spots-20261016.mp4` (5.5 MB, sent to the user).

### 5.5 Gaps

- **The bedroom's decorations (fixed, your go of 2026-10-16).** 7 blocks of PLAYERS_HOUSE_2F take several decorations
  (each slot's choices: 4 beds, 4 carpets over four blocks, 3 plants, 4 posters; each slot can also be empty). The
  builder kept two states per block (G2Cells: the first site won), so any other decoration was drawn wrong. Now:
  - G2Cells keeps every state of such a block (`MapModel.blockStates`) and builds each cell in its most walkable state
    (on a tie the first site's, as a single site builds it); such a cell never closes.
  - G2StoryCells gives each such cell built as ground or water a `cell` story cell, Emerald's multi-state story cell,
    in block ids: `start` the map data's block, `built`, and every other block with its pass, height and art there.
  - StoryRules reads a Gen 2 `cell` cell's live state from `story.blocks` (its block), so each player's bed, carpet and
    plant are drawn and collide as their own game has them. The bridge and the export needed nothing: the bridge
    already sends the decoration blocks, and the export already lists every decoration as a swap site.
  - Still left: the poster. It hangs on a wall, where no story block can stand, so the wall keeps its built art (the
    first poster, the Town Map a new game starts with) whatever the player hangs.
- Not covered by the check:
  - the Battle Tower's rooms and the link rooms (left out, as S7 left Emerald's out);
  - changes made during a visit that the next load doesn't redo (the bridge relays them live, and every write is a
    site).

## 6. Blue

The same three runs for Blue (Gen 1's curated swap sites, M8): the scan needs Gen 1's script tables; the sweep, the
objects run, the replay and the spots reuse the client side as they are.
