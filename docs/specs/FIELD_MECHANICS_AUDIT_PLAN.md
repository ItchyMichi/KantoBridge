# Every script and field mechanic in Minecraft: the audit (Blue, Crystal, Emerald)

**Status: FA0–FA8 done, 2026-10-25 (kb-dev24): the audit's report is §13, FA8's fixes §13.10. Left for later: the levels
pipeline's seams and ramps (Q2, with pictures) and the bike feature (Q4).** It was written
for an agent to execute phase by phase on a pod against the real engine, with the user's decisions in §9 (each a default
the agent proceeds with unless the user changes it).

The user, 2026-10-24: "I want to create a plan that will instruct an AI to audit and check all of the script events in
all three hidden games in KantoBridge. I want to confirm that when playing the game through Cobblemon there is no script
that will not be reflected in the Cobblemon game. An example of this is the cracked floors that lead to warps; if these
are not captured by the mod, we may experience issues. This should also include running and cycling and other gameplay
features, not just story line events."

[STORY_EVENTS_PLAN.md](STORY_EVENTS_PLAN.md) (2026-10-16) already proved one half of this: everything a map's **entry
scripts** change (terrain cells, weather, light, who is visible) reaches Minecraft in all three games. This plan is the
other half: everything that happens **while the player is on the map and moving**: tiles that act on the player, scripts
that move or warp them, step counters, vehicles and field moves, and whether Minecraft's built world agrees with the
hidden game about where a player can stand. It reuses the story check's tools and method wherever it can.

Paths: `GB/` = `gen1-bridge/`, `J/` = `fabric-bridge/src/main/java/dev/kantobridge/`, `C/` =
`fabric-bridge/src/client/java/dev/kantobridge/client/`, `R/` = `refs/gen1recomp/src/` (the engine at v0.3.85), `P/`
= `protocol/`, `T/` = `tools/`. Facts marked *(read)* come from the code survey of 2026-10-24 and were not run; facts
from game knowledge not read in the engine are marked *(unchecked)* and listed in §12.

## 0. The idea in one paragraph

The hidden game is the truth. For Minecraft to "reflect" it, four things must hold at every cell of every map, for every
player: **(A)** Minecraft never lets a player do what the game forbids (no walking through what the game calls solid);
**(B)** Minecraft never stops a player doing what the game allows (no invisible walls, no rubber-banding); **(C)** every
move the game makes on its own (a ledge hop, an ice slide, a fall through a cracked floor, a cutscene walk, a Fly) is
followed by the Minecraft player to the same cell, on the same map; **(D)** every change the game makes to the world
while the player is there (a cracked tile, a cut tree, a pushed boulder, a door that opens) is drawn and collides for that
player. The audit enumerates every mechanism in each game that bears on A–D, drives each one on the real engine with a
stand-in Minecraft client listening, replays what the client heard against the real built world, and walks a sample with
the real client. Gaps are classified (§6) and fixed per game afterwards (§8), as the story check's Emerald fixes were.

## 1. Today (what exists; the survey of 2026-10-24)

**The movement model.** Minecraft moves first; the hidden game follows one cell behind and is the judge
(PROTOCOL.md "Movement rules"). Each cell crossing sends `move.step{dir, n}` (C/`move/MovementSync.java`); gen1-bridge
holds the direction in the hidden game and answers each step exactly once: `player.pos` (kinds `walk`, `bike`, `surf`,
`sync`, `push`) or `player.blocked` (`tile`, `entity`, `bounds`, `script`) (GB/`act/kb_move.lua`). On `blocked` the
client snaps back (MovementSync.java:399-418). Moves the game makes on its own arrive as `player.pos` kinds `ledge`,
`spin`, `current`, `slope`, `script`, walked by `ForcedMover` with the controls locked (`InputLock`), and the mode is
`forced` meanwhile (GB/`observe/kb_player.lua:81-109`). Warps arrive as `warp{start|arrive, kind}` with kinds `door`,
`stairs`, `edge`, `pad`, `hole`, `elevator`, `fly`, `teleport`, `dig`, `rope`, `blackout`, `script`. Collision in
Minecraft is walk / water / solid / ledge (one-way colliders) plus edge walls and per-player story cells.

**What each adapter labels** *(read)*:

| Fact → message | Blue (GB/`lib/engine/gen1.lua:567-641`) | Crystal (GB/`lib/engine/gen2/observe.lua:94-130`) | Emerald (GB/`lib/engine/game3/observe.lua:118-163`) |
|---|---|---|---|
| ledge hop → `ledge` | yes | yes (`jumping`) | yes (`jumping`) |
| spinner → `spin` | yes | n/a | **no**: generic `script` (slide tiles) |
| current → `current` | yes (Seafoam) | **no**: generic `script` | **no**: generic `script` |
| ice slide | n/a | generic `script` (latched direction) | generic `script` (`forced_movement.isForced`) |
| Cycling Road → `slope` + B brake | yes | **no**: the whole ride is `forced`, no brake | no slope fact |
| boulder push → `push` | yes | **no fact**; whatever verdict the engine gives | same |
| departure hint (fly/dig/rope/teleport/hole) | yes | no: named from the map's `setupMethod`, else `script` | no: from the behaviour under or ahead of the player |
| warp kind | map tiles (gen1.lua:1407-1433) | COLL byte (`gen2/move.lua:25-31, 85-100`) | behaviour predicates (`game3/move.lua:107-135`); **everything unmatched is `door`**, so ladders, escalators and Mt. Pyre's hole are reported as doors |
| running shoes | n/a | n/a | **never**: gen1-bridge holds B only for the brake, Emerald runs only with B held (R/`core/game3/player.lua:517`) and Minecraft blocks sprinting |
| bike speed | one speed (8 frames a cell) | the same | the same: Mach bike (4 frames) and Acro tricks not seen |

Two adapter facts to carry into the audit: `f.runner` means "the script runner is running" on Blue (gen1.lua:629) but
"the running shoes" on Emerald (game3/observe.lua:142), and both consumers read it as the script runner
(GB/`observe/kb_story.lua:448`, GB/`act/kb_route.lua:388`); Crystal and Emerald set none of `spinning`, `current`,
`slope`, `boulderPush`, `departure`, `hopLand`.

**What the export carries** *(read)*: Blue a per-cell flag set (walk, water, shore, grass, door, warp tile, counter, cut,
pad, hole, spinner, warp, sign, ledge + direction; GB/`export/kb_cells.lua:10-26`) plus the raw field tables; Crystal
the four COLL bytes per block with the engine's own meaning for each (GB/`lib/engine/gen2/export.lua:130-160`);
Emerald the collision byte, elevation and, per metatile, a behaviour number (GB/`lib/engine/game3/export.lua:383, 650,
676-681`). The builder collapses all three to walk / water / counter / ledge / warp (J/`builder/plan/G3Cells.java:26-80`
reads the **collision byte only**; `G2Cells.java:214-247`); ice, slide, currents, cracked floors, deep water, bike-only
and run-blocked tiles are built as plain ground or water.

**What is tested** *(read)*: Blue's spinner, Seafoam current, elevator, Strength push, cut and surf on the real engine
(`T/e2e/test_field.py`, `test_walk.py`, the simulated `tools/lua/test_lua_forced.py`); Crystal's and Emerald's doors,
stairs, surf and one coord event each (`test_crystal.py`, `test_emerald.py`). **Not tested anywhere:** Crystal's and
Emerald's ice, currents, waterfalls and Strength; Emerald's forced tiles, cracked floors, falls, Dive warps, Mach/Acro
bikes, running; Crystal's Cycling Road; any coord event or step callback beyond the two above; any script-made warp
except Oak's escort and the Elite Four walk-ins. The story check's scan recorded `setstepcallback` (37) and warp setters
(167) in Emerald only statically.

**Left-for-later notes already in PROGRESS.md** (:1123-1127): Emerald's cracked floors keep the fade; Blue's 9 scripted
holes need landings (W7); most of Crystal's in-map ladders stay fades; 200 Hoenn maps the walk never reached, Sootopolis
without a dive or emerge link (:2304-2307); berry trees always show their first look (:2603).

## 2. The contract: what "reflected" means

For every player, at every cell of the current map, with the player's current state (walking, surfing, biking,
underwater):

| | Rule | Breaks as |
|---|---|---|
| **A** | A cell the game refuses (collision, elevation, one-way side, entity) is closed in Minecraft, or the client snaps back within one cell and no step reaches a cell the game never stood on | *rubber-banding* (open where the game is closed) |
| **B** | A cell the game allows is open in Minecraft: not solid, not an edge wall, not a story cell closed for this player; a ledge is passable in its one direction only | *invisible wall* (closed where the game is open) |
| **C** | Every move the game makes on its own ends with the Minecraft player at the game's cell on the game's map, within 2 s of the game's own arrival, with the controls locked meanwhile; a warp the game makes (fall, pad, script, fly…) is a `warp` with the mechanism's kind and `arrive.to` the entry cell | *left behind* (the hidden player moved, Minecraft didn't, or went elsewhere) |
| **D** | Every cell the game changes while the player is on the map is a story cell with that state (drawn and colliding per player) or an NPC/object update, within one logic step of the engine's write | *not shown* (the game changed the world; Minecraft kept the map as built) |
| **E** | Every field feature a player can use in the game has a way to be used from Minecraft (a click, a key, a menu on the mirror), and its effect on speed, water, light and the world follows A–D | *unreachable* (works in the hidden game, no way to trigger it) |
| **F** | A step-driven system (poison, eggs, daycare, Repel, Safari steps, step counters) counts exactly the steps Minecraft makes (one `move.step` = one engine step; forced moves count as the game counts them) | *drift* (different counts, different outcomes) |

Fades in place of animations (a fall shown as a fade, not a drop) are **not** a break; §9 D3 says which should become
real motion later.

## 3. The inventory: what is audited, per game

Every item below gets a row in the inventory file (`local/reports/g{1,2,3}_field_inventory.json`, ROM-derived, never
committed) with: game, map, cell(s), mechanism class, the engine's source (behaviour id / COLL byte / tile class /
script row), the expected Minecraft outcome (message kind, landing cell, closed or open), and the test that covers it.
The lists name what to look for; the agent enumerates the actual cells from the engine's own data, never from memory.

### 3.1 Tiles that act on the player (classes, from the engine's own definitions)

| Class | Blue (R/`world/Collision.lua:33-56`, `Map.lua:122-125, 253-269`, `OverworldController.lua:2107, 4963-5012, 5176`, `FieldDefaults.lua:193`) | Crystal (R/`world/gen2/Permissions.lua`, `core/CollPermissions.lua`) | Emerald (R/`core/game3/constants/emerald/metatile_behaviors.lua`, `mb.lua`, `collision.lua:393-672`, `forced_movement.lua:272-292`, `step_callbacks_rse.lua`) |
|---|---|---|---|
| ledges, 4 directions | `field.ledges` | COLL ledges (:191) | MB_JUMP_* |
| one-way edges (no ledge) | tile pairs | COLL one-way sides (:224-300) | elevation changes, MB_IMPASSABLE_* sides |
| spinners / arrows / slide tiles | `field.spinners` | — | MB_SLIDE_*, MB_WALK_* (Trick House, Mossdeep Gym), MB_ICE *(unchecked: which rooms)* |
| ice | — | COLL ice (:106-111) | MB_ICE, MB_THIN_ICE, MB_CRACKED_ICE (Sootopolis Gym) |
| water currents | Seafoam `seafoam`, `forcedMovement` | COLL current (:135-149) | MB_*_CURRENT (Routes 132-134 *(unchecked)*) |
| waterfall | — | COLL waterfall | MB_WATERFALL |
| whirlpool | — | COLL whirlpool (swap sites) | — |
| dive / emerge | — | — | MB_DEEP_WATER, dive spots, MB_UNDERWATER_* |
| holes and fall warps | `hole` tiles; 9 scripted holes | COLL 0x60/0x68 (pit) | MB_FALL_WARP, MB_CRACKED_FLOOR + MB_CRACKED_FLOOR_HOLE (the step callback cracks, the next step falls), Mt. Pyre's hole |
| warp pads, teleport pads | `pad` tiles (Silph Co, Saffron Gym) | warp panel | MB_WARP_PAD (REGULAR / AQUA), MB_*_WARP by direction |
| doors, stairs, ladders, escalators, exit mats, arrow warps | door/warp tiles, exit mats | COLL door, staircase, cave, carpet | MB_*_DOOR, MB_*_STAIRS, MB_LADDER, MB_*_ESCALATOR, MB_*_ARROW_WARP, MB_NON_ANIMATED_DOOR |
| elevators | `*_ELEVATOR` maps | `*_ELEVATOR` maps | the elevator special (Lilycove Dept. Store, Trainer Hill *(unchecked)*) |
| bike-only and run/bike-blocked | Cycling Road (`bikeRiding`, `slopeMap`) | Cycling Road (`downhill`) | MB_CYCLING_ROAD_*, MB_BUMPY_SLOPE, MB_ISOLATED_*_RAIL, MB_*_RAIL, MB_MUDDY_SLOPE, MB_NO_RUNNING, MB_SEAWEED *(unchecked: the full set)* |
| counters, signs | `counter`, `sign` | COLL counter | MB_COUNTER, MB_*_SIGN |
| tall grass / ash / sand / puddles / bridges | `grass` | COLL grass | MB_TALL_GRASS, MB_ASHGRASS, MB_SAND, MB_PUDDLE, MB_FORTREE_BRIDGE, MB_PACIFIDLOG_*_LOG, MB_SHALLOW_WATER |
| dark | `field.darkMaps` | dark maps | the cave flag, flash levels |

For each class the inventory counts cells per map and marks which have an explicit path to Minecraft today (§1's table),
which go through the generic `script` path, and which have none.

### 3.2 Scripts that move or change things during a visit

- **Coord events / triggers** (every map): Blue's scripted coordinate checks in `R/data/scripts/**`; Crystal's coord
  events; Emerald's coord events with their variable condition. Expected: a `cutscene` or `forced` mode, scripted steps
  as `script`, any warp with its kind, and the world state afterwards.
- **Step callbacks** (Emerald, 37 `setstepcallback` sites in the scan): ash, Fortree bridge, Pacifidlog logs, Sootopolis
  Gym ice, cracked floor, muddy slope, the Trick House, Mirage Tower; (Crystal) the Ruins of Alph floor collapse and the
  Burned Tower floor *(unchecked)*; (Blue) none known.
- **Script-made warps** (Emerald's 167 warp setters; Crystal's and Blue's script warps): `warp`, `warphole`,
  `warpteleport`, `warpdoor`, the elevator specials, cable car, ferry, Magnet Train, S.S. Aqua / Anne / Tidal, Fly, Dig,
  Teleport, Escape Rope, whiteout. Expected: one `warp` with the mechanism's kind, `arrive.to` on the destination.
- **Scripted player movement** (`applymovement` on the player; Blue's `scriptMoves`; Crystal's `applymovement
  PLAYER`): escorts, pushes back off a ledge or out of a door, trainer walk-ups, the Elite Four walk-ins. Expected:
  `script` steps to the game's cells.
- **World writes during a visit**: `setmetatile`, `changeblock`, swap sites, object hide/show, boulders moved by the
  player, trees cut, doors opened by switches, Game Corner / Trick House / gym puzzle states. Already proven at load time
  by the story check; here each is driven **live** (the write happens while the client is on the map) and compared the
  same way (`story.cells` / `story.blocks` / `npc.*` against the engine).

### 3.3 Vehicles, modes and field features (rule E)

| Feature | Blue | Crystal | Emerald | Minecraft today |
|---|---|---|---|---|
| Running shoes | — | — | B held, `canDash` maps | **unreachable** |
| Bicycle | one bike, Cycling Road slope + brake | one bike, Cycling Road downhill (no brake today) | Mach (speed, muddy slope, cracked floor crossing) and Acro (wheelie, bunny hop, rails) | one speed; Acro tricks unreachable |
| Surf, dismount | click water / land | same | same | yes |
| Dive, emerge | — | — | `act.field dive`; the warp observed | kind not dedicated |
| Waterfall | — | field move, scripted climb | same | generic `script` |
| Whirlpool | — | swap site | — | yes |
| Cut, Rock Smash, Headbutt | Cut (click) | Cut (click); Rock Smash and Headbutt from the party menu | Cut (click); Rock Smash from the menu | partly click |
| Strength | push → `push` | engine verdict only | engine verdict only | Blue only explicit |
| Flash | `story.lit` | same | flash levels | yes |
| Fly, Dig, Teleport, Escape Rope | warp kinds | from `setupMethod` | from map change | yes / named loosely |
| Fishing | `act.field fish`, bite → Cobblemon | same | same | yes |
| Itemfinder / Dowsing | native from the bag | same | same | native |
| Repel | bridge applies to water rolls | same | same | no effect on Cobblemon spawns (by design) |

### 3.4 Step-driven systems (rule F)

Poison damage and faint-on-walk, egg hatching, daycare levels, Repel steps, Safari Zone steps, Pokérus *(unchecked)*,
happiness steps, berry/apricorn growth (Crystal, Emerald), the Bug-Catching Contest timer, the Mirage Tower and
abnormal weather timers, Crystal's day/night (export bakes DAY; the server pins noon; time-gated people follow the
engine). The audit counts steps and checks the engine saw exactly what Minecraft sent; it does not redesign day/night
(§9 D6).

### 3.5 Collision parity (rules A and B, independent of scripts)

For every map in every game, for every cell and each of the four directions, and for each player state the engine
distinguishes (walking, surfing, biking, running, underwater): the engine's own verdict (`R/core/game3/collision.lua`'s
move check; `R/world/gen2/Permissions.lua`; `R/world/Collision.lua` with the map's tile pairs and ledges) against the
built world's answer (the cell's blocks and colliders in the real WorldIndex, through the same `StoryRules` /
`CellFlags` code the client runs, in the new game's state and with every story flag set). Every disagreement is an A or B
break with its cell. This is the one systematic check that finds a wrong cell nobody thought to walk to.

## 4. The method: three layers, per game

The same shape as the story check (STORY_EVENTS_PLAN.md §2), with a new tool per game beside it:
**`T/e2e/g{1,2,3}_field_check.py`** with its driver **`T/e2e/drivers/kb_g{1,2,3}field.lua`**, subcommands `inventory`,
`parity`, `sweep`, `features`, `steps`, `report`, `sites`.

### 4.1 Layer 1: the inventory (static, from the engine's own data)

- Walk every map's cells and classify each by §3.1 from the engine's own predicates (never a hand-made table: ask
  `collision.lua` / `Permissions.lua` / the Gen 1 field tables what a cell is).
- Walk every script for §3.2 (Emerald: the story scan's walk in `GB/lib/engine/game3/sites.lua` extended to record coord
  events, step callbacks, `applymovement` on the player and every warp command with its destination; Crystal: the same
  over its script walk in `GB/lib/engine/gen2/`; Blue: the `EVENT_*` and `scriptMoves` inventory of
  `g1_story_check.py inventory`, extended with every `warp`/hole/pad call in `R/data/scripts/**`).
- For every row, derive the **expected Minecraft outcome** from the protocol: the `player.pos` kind and landing cell, the
  `warp` kind and `arrive.to`, the story cells written, or "refused: `blocked tile`".
- Output `local/reports/g*_field_inventory.json`; the summary prints only classes, map symbols, ids and counts.

### 4.2 Layer 2: the live sweep (the real engine, a stand-in client)

Driver pattern as `kb_g3story.lua`: a fresh test slot, a new game, `fake_minecraft` connected as role `test`, cleaned up
after. Per inventory row (every row for rare classes; a sample of N per map for grass, signs and counters):

- **Tiles**: put the hidden player next to the cell in the state the mechanism needs (surfing for water, a bike for
  Cycling Road, flags for puzzles), send `move.step` into it, record every message until idle, and compare: the kind,
  frames, landing cell and map; a warp's kind and `arrive.to`; the mode sequence; that every `n` was answered once; and
  the engine's own player position at the end. A ledge is stepped from both sides (one refused). A cracked floor is
  stepped twice (crack → `story.cells` with the hole's metatile and `pass`; fall → `warp` kind `hole`, `arrive.to` on
  the lower floor at the game's cell). Ice is entered from every open side. A current is entered at every entry cell.
- **Coord events and step callbacks**: warp in beside the trigger with its condition satisfied (flags and vars solved as
  the story sweep does), step onto it, record until idle; compare the scripted moves, warps and writes with the
  inventory's expectation and the engine's state.
- **Script warps**: reach each through its trigger (a coord event, an interaction answered through the UI messages, a
  field move) rather than by calling it; where the trigger is a battle or a long cutscene, the driver may set the state
  the script leaves behind and call the warp command itself, marked `driven` in the report.
- **Features** (§3.3): each used from the client's side (`act.field` for cut/surf/bike/fish/dive; UI picks for the party
  menu ones), then its effect walked: speed (`player.state.bike`, the frames per cell), water (`surf` on, a step on water,
  a dismount), light (`story.lit`), the world (`story.*`), and the refusals (surf without the badge, a bike indoors, Dive
  on shallow water).
- **Steps** (§3.4): a party member poisoned, an egg, a Repel, the Safari Zone: N `move.step`s, then the engine's counters
  read directly; forced moves counted as the game counts them.
- Everything a message says is checked against its schema (strict). Output `local/reports/g*_field_sweep.json`.

### 4.3 Layer 3: Minecraft (the real index; then the real client)

- **Parity** (§3.5): a JUnit replay test per game beside `RealGen{1,2,3}StoryReplayTest`: the real WorldIndex, every
  cell × direction × state, the engine's verdicts from a parity dump the driver writes (`parity` subcommand), through
  `StoryRules` and the built colliders; counts of A and B breaks by map and class; 0 is the gate.
- **Replay**: every recorded sweep stream fed to the client's movement logic (`ForcedMover`'s path, the warp's landing,
  `StoryRules` for the writes) on the real index: the forced path's cells are open, the landing cell is open and on the
  right map, each written cell is a story cell with that state; counts of C and D breaks.
- **Spots**: `T/e2e/field_spots_g{1,2,3}.py` with `drivers/kb_g*fieldspots.lua` (the real dev client, as
  `story_spots*.py`): one spot per class per game at least (a cracked floor fall, an ice room, a current, a spinner, a
  waterfall, a dive, Cycling Road, each warp kind, Strength, a coord-event escort), the client's position read after each
  through RemotePilot; a short video per game if wanted.

### 4.4 Safety and cost

PLAN.md §0.3 throughout: the driver prints map symbols, ids, classes, counts, kinds and booleans; per-cell truth stays
in `local/reports/`; no save into the install's own slots; no ROM data in the repo, the logs or this document. Pods as
the story check used (one small CPU pod, kb-dev24's size, stopped after each job; memory says create/start without
asking). Estimates from the story check's figures: inventory ~1 min per game; the Emerald sweep is the long one (≈4,000
tile rows + ≈300 script rows at ~1.5 s each ≈ 2 h; sample grass and signs to keep it under an hour); parity is offline
(minutes).

## 5. Known or suspected gaps the audit must settle first

Named now so the inventory confirms or clears them; each becomes a finding with its evidence, not a fix, in this phase.

| # | Suspected gap | Rule | Where read |
|---|---|---|---|
| K1 | Emerald warps that are not door/pad/stairs/arrow/fall (ladders, escalators, Mt. Pyre's hole, Lavaridge and Mossdeep warps) arrive as kind `door`; the client's fade is right, the kind is wrong (matters for landings and any kind-specific client animation) | C | `game3/move.lua:107-135`, the `or "door"` default |
| K2 | Running shoes unreachable: no way to hold B from Minecraft; the hidden Emerald player never runs, so run-only timings (none known) and the running animation never happen | E | `kb_move.lua` holds B only for the brake |
| K3 | Mach bike speed and Acro bike tricks unreachable; bike speed is one speed; an `acro` refusal is reported as `tile` | E, A | `game3/move.lua:20-21` |
| K4 | Crystal's Cycling Road: the whole descent is `forced`, so the player can't steer or brake as the game allows | B, C | `gen2/observe.lua:69-79` `downhill()` |
| K5 | Crystal and Emerald Strength: no `push` kind and no `npc.move push`; the boulder's move may reach Minecraft only as an object position change, or not at all; a boulder pushed into a hole (Emerald) must become a story cell | C, D | §1's table |
| K6 | Emerald cracked floors: the crack reaches `story.cells`, the fall is a fade; the landing cell must be open and the player must not be left on the upper floor | C, D | PROGRESS.md:1123 |
| K7 | `f.runner` means two different things (Blue: script runner; Emerald: running shoes) and `kb_story.lua:448` / `kb_route.lua:388` read it as the script runner: if K2 is fixed and the player runs, a story flag diff may fire at the wrong time | D | `game3/observe.lua:142` |
| K8 | Minecraft's collision ignores the behaviour byte: ice, slide and current tiles are plain ground or water; bike-only tiles are walkable on foot; `MB_NO_RUNNING` has no meaning (fine while K2 stands); deep water is surfable water (Dive spots open?) | A, B | `G3Cells.java:26-80`, `G2Cells.java:214-247` |
| K9 | Sootopolis has no dive or emerge link and 200 Hoenn maps were never reached by the story walk: a dive-only or script-only map may be built but unreachable, or reachable but not built | B, C | PROGRESS.md:2304-2307 |
| K10 | Blue's 9 scripted holes keep the fade without landings (W7); Crystal's in-map ladders stay fades | C (not a break, §2) | PROGRESS.md:1123-1127 |
| K11 | Crystal's and Emerald's Fly/Dig/Teleport/Escape Rope/whiteout kinds come from the map's `setupMethod` or fall back to `script`; the client's blackout handling keys on `blackout` | C | `gen2/move.lua`, §1 |
| K12 | Repel has no effect on Cobblemon spawns; Safari steps, egg steps and poison count one engine step per Minecraft cell, forced moves included: the counts have never been compared | F | §3.4 |
| K13 | Crystal's day/night: the world is DAY and the server pins noon, but time-gated scripts and people follow the engine's clock: a night-only event may fire in a daytime world (consistent by design, D6; the audit lists which) | D | GEN2_BRIDGE_PLAN J6 |

## 6. Gap classes and what counts as a pass

Every finding is one of: **A** rubber-banding, **B** invisible wall, **C** left behind, **D** not shown, **E**
unreachable, **F** drift, or **N** a note (a wrong kind label with the right outcome; a fade where motion would be
nicer; art only). A game **passes** when the parity count is 0 for A and B, the sweep has 0 C and 0 D, every §3.3
feature is reachable or has a decision in §9 saying it stays unreachable, and the §3.4 counts match. N notes go to the
follow-ups list, not the gate.

## 7. The report (per game, in this document, as STORY_EVENTS_PLAN §3, §5, §6 did)

1. Inventory: cells per class per game; script rows by kind; what has an explicit path, a generic path, no path.
2. Parity: A and B breaks by map and class, with examples (map symbol, cell, class; never text or art).
3. Sweep: rows run, rows passed, rows `driven`, every C and D with its map, cell, class, expected and seen messages.
4. Features and steps: the §3.3 and §3.4 tables filled in with pass / fail / unreachable.
5. The fix plan for that game (§8's shape) with the user's decisions asked where a default would guess.

**Filled in (2026-10-25):** §13.2 Blue, §13.3 Crystal, §13.4 Emerald, each with items 1–5; the builder's breaks shared
by all three in §13.5; K1–K13 in §13.6; pass or fail in §13.7; the fix plan in §13.8; the questions in §13.9.

## 8. Phases

| Phase | Delivers | Check |
|---|---|---|
| **FA0** contracts | This plan's expectations as protocol text: the forced-move kinds Crystal and Emerald should send (`spin`, `current`, `slope`, `ice` new, `push`), warp kinds `ladder`, `escalator` *(or keep `stairs`/`door`, D2)*, `dive`; `player.state.run`; PROTOCOL.md and schemas; catalog regenerated | pytest protocol |
| **FA1** inventory | `g{1,2,3}_field_check.py inventory` and the three drivers' static walks; `local/reports/g*_field_inventory.json`; the §3.1–3.2 counts in this document | on the pod, each game ~1 min; every class of §3.1 appears or is reported absent |
| **FA2** parity | the `parity` dump and `RealGen{1,2,3}FieldParityTest` on the real ThreeRegionsE2E index; the A/B counts | 0 breaks, or every break listed with its cell |
| **FA3** sweep: tiles | `sweep --class` per §3.1 class on the real engine, all three games; C/D findings | every row answered once, schema-strict, kinds as expected |
| **FA4** sweep: scripts | coord events, step callbacks, script warps, scripted moves, live writes | as FA3 |
| **FA5** features and steps | `features` and `steps`; the §3.3 and §3.4 tables | as FA3; counts equal |
| **FA6** Minecraft replay and spots | the replay of every stream on the real index; `field_spots_g*.py` with the real client, one spot per class; videos if wanted | 0 C, 0 D; spots as expected |
| **FA7** report | §7 for each game, in this document; the fix plan per game with its decisions | the user reads it |
| **FA8+** fixes | per game, in the order the user picks: adapters (kinds, facts, `runner`), the builder (behaviour-aware collision where A/B found breaks), the client (running, Acro, Dive kind), each with lupa/JUnit tests and a rerun of the failing sweep rows | the sweep rows that failed pass; the full suite; the spots again |

FA1–FA7 are the audit the user asked for and change no game behaviour (FA0 adds optional enum values only). FA8 is
where fixes start, after the user has read the report.

## 9. Decisions (defaults proceed)

| # | Question | Default |
|---|---|---|
| D1 | Audit first, or fix as found? | **Audit first** (FA1–FA7), one report for all three games, then fixes per game in the user's order. A one-line fix that unblocks the sweep itself (a driver can't reach a class) may be made on the way and listed |
| D2 | New warp kinds for ladders, escalators, dive, or keep the coarse ones? | **Add `ladder`, `escalator`, `dive`** (optional enum values; older clients treat unknown kinds as `script`), because the client's landing and animation rules key on the kind (H5 ladders) |
| D3 | Falls and holes as fades | **Fades stay a pass** for the audit (rule C is about the cell). Making falls real drops (the H5 shaft model) is a separate follow-up per game |
| D4 | Running shoes | **Add them**: Minecraft's sprint key (while not biking or surfing, on maps the game allows) → `move.hold{button:"b"}` held through the step; the server's run speed = the game's (Emerald: twice walking); `player.state.run`; and K7's `runner` split into `scriptRunning` / `run` first. Decided here because rule E calls it unreachable, but built in FA8 |
| D5 | Acro bike tricks (wheelie, bunny hop, rails) and the Mach bike's speed | **Mach speed yes** (a second bike speed in `player.state.bike` → Rules); **Acro tricks: audit only**; whether to add a key for them is the user's call after the report (they gate a few optional items *(unchecked)*) |
| D6 | Crystal's day/night | **Unchanged** (DAY world, noon pinned, the engine's clock for scripts); the audit lists every time-gated mechanic it meets |
| D7 | Behaviour-aware collision in the builder (ice, slide, current, bike-only as their own cell classes) | **Only where FA2 finds A/B breaks**; otherwise the game's verdict plus the snap-back stays the rule, as today |
| D8 | Secret bases, Battle Frontier floors, link rooms, contest halls, the Union Room | **Left out**, as STORY_EVENTS_PLAN S7 |
| D9 | Sampling | **All rows** for every class but tall grass, signs, counters, sand and puddles (10 per map); every coord event, step callback and script warp |
| D10 | Where the report lives | **This document**, §3 filled in and a per-game results section appended, as STORY_EVENTS_PLAN did; PROGRESS.md gets a "Field mechanics audit" section; PLAYTEST.md a checklist per game |

## 10. Risks

| Risk | Mitigation |
|---|---|
| The engine's forced moves run on their own clock; a stand-in client that answers late makes a false C | the driver waits for idle with the story sweep's 6 s budget, then reads the engine's position; a timing-only miss is an N note with its latency |
| A mechanism needs a story state the driver can't set cleanly (a gym puzzle mid-way) | solve flags and vars as the story sweep did; otherwise `driven` and listed |
| The Emerald sweep is long and the pod small | class by class, resumable from the inventory (`--class`, `--map`), one pod at a time |
| Parity's engine verdicts need a player object in each state | the driver sets the engine's own player fields (surfing, biking, running, underwater) and restores them; the dump records which state each verdict used |
| A behaviour id ≥ 0x100 (RSE-only) aliases a lower one in the export (`game3/export.lua:650`, `% 256`) and misclassifies cells in FA1 | FA1 verifies the alias question first, with counts per raw and canonical id, and fixes the export if it aliases |
| The audit's new enum values reach a client before the adapters send them | optional values only; nothing sends them until FA8 |

## 11. Not in this plan

Battles and their rules, Pokémon data, trades, the PC, the bag's native effects, dialogue content and UI mirroring,
multiplayer visibility, performance, the voxel look of any tile, Minecraft's own weather and day/night, and new game
content. Foreign Pokémon's field moves are FOREIGN_POKEMON_PLAN.md §4.2's.

## 12. Unchecked facts (to verify in FA1, never to build on)

*Verified in FA1: the answers are in §13.2–§13.4 (item 1 of each game); the Acro items remain open.*

- Which Emerald rooms use MB_ICE and MB_SLIDE_*; the exact set of bike-only behaviours; the current routes (132–134);
  Trainer Hill's elevator.
- Crystal's Ruins of Alph floor collapse and Burned Tower fall are step-driven falls (or script warps).
- Pokérus and happiness count overworld steps in the engine's port.
- Which optional items the Acro bike's tricks gate.

## 13. Results (FA0–FA7: run 2026-10-24/25)

Everything below ran on the pod **kb-dev24** (cpu5g x4, 16 GB, EU-RO-1, the volume's imports, Gen1Recomp v0.3.85),
except where a line says *(read)* (a code reading, not run) or *(inference)*. The per-cell truth stays on the volume in
`local/reports/g{1,2,3}_field_*.json`; this document names only map symbols, classes, kinds and counts (§4.4). Rule
letters as §2 and §6: **A** the game refuses and Minecraft lets the player through (the client then snaps back);
**B** the game allows and Minecraft stops the player (an invisible wall); **C** the client doesn't end where the hidden
player does; **D** the game changes a cell the client isn't told about; **E** a feature the client can't trigger;
**F** step counts that drift; **N** a note (a wrong kind with the right outcome, a fade instead of motion).

### 13.1 What ran, and how to read the numbers

| Phase | Tool (all committed) | Blue | Crystal | Emerald |
|---|---|---|---|---|
| FA0 | `protocol/`: warp kinds `ladder`, `escalator`, `dive`; `player.pos` kind `ice`; `player.state.run` (optional, nobody sends them yet) | — | — | — |
| FA1 inventory, FA2 dump | `g{1,2,3}_field_check.py survey`, `scripts`; drivers `kb_g{1,2,3}field.lua` | 225 maps, 218 live | 389 maps, 380 live | 519 maps, 401 live |
| FA2 parity | `RealGen{1,2,3}FieldParityTest` (`field/FieldParity.java`) | 187,800 + 37,156 | 300,069 + 55,710 | 328,047 + 213,988 cell-directions (walking + surfing) |
| FA3/FA4 sweep | `… sweep --class C` (shared client `field_common.py`) | 3,515 rows | 7,962 rows | 6,147 rows |
| FA5 | `… features` | 26 rows | 48 rows | 41 rows |
| FA6 replay | `RealGen{1,2,3}FieldReplayTest` (`field/FieldReplay.java`, `PlanPhysics.java`) | 3,625 rows | 8,093 rows | 5,126 rows |
| FA6 spots | `field_spots.py --game G` (the real dev client, RemotePilot), world LandSky3 | 11 of 11 | 12 of 13 | 25 of 28 |

**How parity works (FA2).** The engine's own verdict for every cell, direction and state was asked without moving
anyone, in the engine's order: Blue `Collision.canMove` with the ledge rows and the connection landing; Crystal
`Permissions.stepPermitted`, walkable or surfable, the ledge rule, the seam; Emerald `Collision.ledgeLanding`,
`Collision.canEnter`, the rotating gate, the Acro check and the read-only half of `tryConnection`; objects left out
(terrain), after a warp into the map in the new game's state. Minecraft's side is the real export planned as a build
plans it today (`kantobridge.outdoorLevels=all`, `chainLevels=true`, the landscape models table v14, every cave in 3D:
the rollout worlds' options), and a 0.6 × 1.8 player box with Minecraft's 0.6 step walked two blocks from the cell's
middle through the planned blocks' real collision shapes, story cells in the new game's state with the visit's
overrides fed through `StoryRules`. **Checked against a world on disk:** example cells were read back from LandSky3's
region files (built 2026-10-23) with a small Anvil reader on the pod; they held the plan's blocks.

**How the sweep works (FA3–FA5).** Per row the driver puts the hidden player on the row's start (a warp, or a move on
the same map), in its vehicle; a stand-in client (`fake_minecraft`, schema-strict) sends the step (or a field action,
held B, several steps) and the driver records the engine's end once the field is quiet. Checks: one answer per step;
the client's last known cell (from `player.pos` / `warp.arrive`) is the engine's end (C); every cell the engine changed
was told (D); the warp kind against the mechanism's (D2: N notes). "Not started" rows couldn't be put in place (a cell
inside a current, an NPC on it); "halted" rows ran into a scene the sweep cuts off after its budget.

**How the replay works (FA6).** Every sweep row's messages go through the client's own `MoveController` (MovementSync,
ForcedMover, WarpFade, the input lock) on the planned world, the player's box moved through the colliders and the
server's teleports answered as `WarpService` does; the step is walked as a player walks it. After 4 s the cell under
the player must be the engine's end cell.

### 13.2 Blue

**1. Inventory.** 225 maps (5 left out: link rooms, KantoBridge's own test routes), 218 live (Lance's room and Viridian
City sent the player on at entry). The export's per-cell flags (`kb_cells`) agree with the engine's predicates on every
cell of every map. Classes: ledges 758 cells on 22 maps; tile pairs (two-way walls; §3.1's "one-way edges" is wrong for
Gen 1) 577 land + 65 water cells on 16/5 maps; spinners 71 on 3 maps; warp pads 64 on 11 maps; doors 205, warp tiles
257, exit mats and edge warps 328 warps; cut trees 35; boulders 25 on 9 maps; badge-gate cells 136 (Route 22's gate,
Route 23); forced bike and surf tiles 4 + 4 (Routes 16, 18; Seafoam B3F, B4F); Seafoam currents 4 + 1 entry current +
4 forced-exit cells; Cycling Road (Route 17); dark maps 2 (Rock Tunnel). **12 scripted holes on 6 maps** (not 9:
Seafoam 1F, B1F, B2F, B3F two each, Victory Road 3F one, Pokémon Mansion 3F three), found by probing every map's
`onStep` with a stand-in overworld; none is marked a hole in the export. Scripts: `onStep` on 38 maps, `onEnter` on 51,
19 `move_player` rows and 37 `scriptMove` calls, 5 warp rows, 20 block writes.

**2. Parity.**

| | Break | Walking | Surfing | Maps | Where (most) |
|---|---|---|---|---|---|
| A | a solid cell built with an empty column | 841 | 8 | 93 | Safari Zone north 68, west 58, east 46, Mansion B1F 34 |
| A | onto a ledge cell from the side | 278 | 3 | 20 | Routes 4, 5, 1, 2 |
| A | off an edge with no connection, into the void | 67 | — | 2 | Routes 2, 6 |
| A | onto a counter, a crossing the game refuses, onto water from higher ground | 39 | — | 9 | Game Corner, Celadon Diner, Route 18 |
| B | a connection walled at the seam | 98 | 26 | 9 | Route 13, Cerulean, Vermilion, Saffron |
| | **Total** | **A 1,225, B 98** | **A 11, B 26** | | |

Tile pairs, spinners and pads agree everywhere.

**3. Sweep.** 3,515 rows: 3,406 passed, 61 not started (an NPC on the cell), 35 halted, **0 C, 0 D**; 2 notes (Seafoam's
two edge warps announced `door`). Ledges 719/722 (`ledge`, the landing two cells on), refused from below 713/713, tile
pairs 146/147, spinners 71/71 (`spin`), Seafoam's currents and forced exit (`current`), the Cycling Road's roll
(`slope`), the 12 scripted holes (warp kind `hole`, landing on the floor below; 2 landed on another hole's cell than the
probe's), boulders 16/17 (`push`), warp pads 64/64 (`pad`), doors 246, stairs 139, edges 308, elevators 6/6
(`elevator`), coordinate triggers (scripted moves as `script`).

**4. Features and steps.** Surf on and off (`act.field surf`, `dismount`; refused `no_badge` without it), the Old and
Super Rods (a bite and a battle), the bicycle on and off (walking 16 frames a cell, the bike 8; refused indoors), the
Cycling Road brake (B held through `move.hold`: the roll stops; let go, it rolls on), Cut, Strength (`push`), Flash in
Rock Tunnel (the map lit), Dig, Escape Rope, Teleport and Fly (each its own warp kind) — all reachable from the client
and all behaving as the game does. Steps (20 there and back, rule F): `todSteps` +20, daycare +20, Repel −20, Safari
−20, poison every fourth step (−5 HP): **equal to the steps sent**.

**5. Replay and spots.** Replay: 3,610 of 3,625 rows end in the engine's cell; 15 long spinner chains in Rocket Hideout
B2F end 2–3 cells short in the replay model (the forced mover keeps at most 16 legs *(read)*), but the real client's
spot on a B2F spinner passed, so this is the replay's limit, not the client's *(inference)*. Spots (real client):
**11 of 11**: Route 18's forced bike, Seafoam's forced surf and current, a Celadon ledge, Mansion 3F's hole, a Rocket
Hideout spinner, stairs, an edge warp, a Silph Co door and pad, the Celadon Mart elevator. Video:
`/workspace/videos/kanto-bridge-blue-field-spots-20261024.mp4` (8.7 MB, on the volume).

**Blue passes rules C, D, E and F.** Its A and B breaks are the builder's (§13.5).

### 13.3 Crystal

**1. Inventory.** 389 maps (8 left out: the Battle Tower's rooms, link and mobile rooms), 380 live (Ecruteak Gym's scene
sent the player on; 46 entry scenes halted).

| Class (COLL, the engine's `Permissions`) | Cells | Maps | Path today |
|---|---|---|---|
| one-way side walls | 2,355 | 81 | built as `PairEdges.walls`; the engine refuses |
| ledges / with two facings | 1,318 / 62 | 72 / 29 | `ledge` / the export keeps the first facing only |
| headbutt / cut trees | 1,056 / 40 | 26 / 23 | `act.field headbutt` / `cut` + `story.blocks` |
| ice | 779 | Ice Path (4 floors), Mahogany Gym | `script` |
| door pushes (the step off a door) | 387 | 139 | `script` |
| currents / waterfalls | 164 / 164 | Mt. Mortar outside, Silver Cave room 2, Tohjo Falls, Whirl Islands B2F | `script` |
| whirlpools | 6 | Dragon's Den B1F, Routes 27, 41 | `act.field whirlpool` + `story.blocks` |
| warps: carpets 579, doors 217, ladders 200, staircases 74, pits 70, caves 51, panels 47 | | | `edge`, `door`, `stairs`, `stairs`, `hole`, `door`, `pad` |
| counters / grass / super tall grass | 837 / 3,404 / 528 | | plain |

Absent: buoys, the walk tiles 0x40–0x5F (no arm in the port; none on the cart), unused warp bytes. 94 in-map ladders
on 14 maps. **§12:** the Ruins of Alph floor collapse (each chamber's puzzle: `changeblock` then `warpcheck`) and Burned
Tower 1F's fall (a coord event: `changeblock`, `applymovement` on the player, `warpcheck`) are **script-made**, not
step-driven. Scripts: 2,713 roots, 70 warp commands, 214 player moves on 55 maps, 331 `changeblock`, 114 coord events
on 44 maps, `ENGINE_ALWAYS_ON_BIKE` set on Routes 16 and 17.

**2. Parity.**

| | Break | Walking | Surfing | Maps | Where (most) |
|---|---|---|---|---|---|
| A | a solid cell built with an empty column | 4,190 | 112 | 194 | Route 45 377, Blackthorn 210, Route 9 204, Route 32 154 |
| A | off an edge with no connection, into the void | 1,277 | 42 | 30 | Routes 44, 45, 3, 22 |
| A | a crossing the game refuses | 142 | — | 11 | Route 26, Pewter, Route 12, Cinnabar |
| A | onto a counter (the shelf and table bytes 0x91–0x9F) | 64 | — | 12 | the Game Corners, Celadon Café |
| A | onto water from higher ground | 4 | — | 1 | Cerulean Gym |
| B | **a ledge hop** | **1,349** | — | 72 | Routes 4, 45, 9, 26; Dance Theater, Victory Road, the Whirl Islands, Dark Cave |
| B | a connection walled at the seam | 190 | 62 | 22 | Cerulean, Route 13, Viridian, Route 25 |
| B | a height step with no ramp | 39 | — | 22 | the Ruins of Alph outside, Routes 41, 42 |
| | **Total** | **A 5,677, B 1,578** | **A 154, B 62** | | |

The ledge hop: Gen 2's hop starts **on** the ledge cell (land) and jumps the next cell, which the engine calls a wall;
the builder builds the ledge cell as Gen 1's one-way drop and the next cell as rock two blocks high, so walking can't make
the hop (in-game it ends as a fade, below).

**3. Sweep.** 7,962 rows: 7,528 passed, 1 not started, 86 halted, **0 D**; the 8 C rows are not breaks (4 Cycling Road
rows the sweep cut off mid-roll, 3 refused steps on Route 17 while it rolled, one E4 walk-in snapshot with the hidden
player off the map); 226 notes (ladders `stairs` 197/197, carpets into elevators `elevator`). Ledges 1,349/1,363 (the hop
answered `ledge` on the far cell), refused from below 1,360/1,363, side walls 1,228/1,228 and entries 1,224/1,228, ice
135/141 (the slide as `script` steps), currents and waterfalls 30/64 each (the others pushed straight back, as the game
does), whirlpools 12/12, door pushes 36 (each pushed straight out, as the game does), carpets 543/565, doors 202/208,
caves 47/47, staircases 72/74, pits 32/41 (`hole`; 9 didn't fall in the new game's state), panels 47/47 (`pad`), grass
440/450, counters 608/608, coord events 81/112 (the rest a scene the sweep halted or a branch that doesn't warp).

**4. Features and steps.** Reachable and right: Surf on (`act.field surf`) and off (a step onto land; `act.field surf`
toward land is refused `no_target`), the bicycle on and off (16 → 8 frames; refused without the bike or indoors),
Waterfall (the climb as `script` steps), Whirlpool (the swap as `story.blocks`), Headbutt, Cut, Flash in Dark Cave.
**Not right:** Strength (the push is answered `blocked: entity`, the boulder moves only as a person; no `push`: K5); **Rock
Smash from Minecraft does nothing** (`act.result ok`, the rock stays: the bridge's path, `World:useFieldMove`, has no
arm for it *(read)*); the Cycling Road (steering, the brake and getting off refused while it rolls: K4); Teleport, Dig
and Escape Rope arrive as warp kind `script` (K11); Fly opens the game's own map and a `dest` is refused (`no_target`).
Steps: 20 walking and 20 on the bike: `stepCount` +20 each, `player.pos` 20, **equal**.

**5. Replay and spots.** Replay: 8,076 of 8,093 rows end in the engine's cell (the 17 others are the cut-off rolls and
off-map snapshots above); **every one of the 1,305 replayed ledge hops ends by a teleport**, because the Minecraft
player can't walk the hop (FA2's B). Spots: **12 of 13** — the coord event, a current, Ice Path's ice, a ledge (the hop
made by the client's push step, then a fade), a pit, a door, a carpet, a cave, a staircase, Saffron Gym's panel, a
waterfall; the one failure is a ladder in a 3D cave, where the client climbs a shaft instead of stepping (H5: by design,
the spot's single step can't climb). Video: `/workspace/videos/kanto-bridge-crystal-field-spots-20261024.mp4` (10.0 MB).

### 13.4 Emerald

**1. Inventory.** 519 maps, 110 left out by D8, 401 live (2 sent the player on). Classes by the engine's predicates
(`collision.lua`, `forced_movement.lua`, `dive.lua`, `bike/rse.lua`):

| Class | Cells | Maps | Path today (as the sweep saw it) |
|---|---|---|---|
| currents | 4,384 | Routes 132, 133, 134; Seafloor Cavern rooms 6, 7 | `script` |
| dive spots / no-surfacing water | 3,902 / 1,636 | 26 / 8 | `act.field dive`; warp kind `door` |
| one-way sides (`IMPASSABLE_*`) | 2,655 | 48 | the engine's refusal; no collider |
| bike-blocked (long grass, hot springs, logs, Fortree bridges, no-running) | 1,574 | 6 | none |
| ledges / diagonal jumps | 946 / 77 | 42 / 22 | `ledge` / none |
| ice / thin ice / slide tiles / the Trick House's slippery floor | 340 / 64 / 12 / 186 | 2 / 1 / 2 / 1 | `script` (thin ice also `story.cells`) |
| waterfalls | 277 | 7 | `act.field waterfall` (climb as `surf`); the fall as `script` |
| ash grass / Fortree bridges / Pacifidlog logs | 287 / 27 / 38 | 2 / 1 / 1 | `story.cells` |
| cracked floors / their holes | 190 / 80 | 6 / 1 | `story.cells`; the fall as warp kind `door` |
| rails / bumpy / muddy slopes | 68 / 17 / 12 | 2 / 2 / 5 | refused `tile` on foot |
| warps by behaviour: arrows 527, cave doors 217, warp doors 195, ladders 100, the Aqua Hideout's pads 28, Mossdeep Gym 22, Lavaridge 35, escalators 34, Mt. Pyre's holes 8, water doors and arrows 14 | | | see the sweep |
| counters, tall grass, sand, puddles, shallow water | 566 / 2,801 / 2,932 / 646 / 3,683 | | plain (sampled) |

Absent from the data: walk tiles, spin tiles, cracked ice, `MB_FALL_WARP` (an FRLG id: `isFallWarp` and
`isStairWarpBehavior` never match Emerald *(read)*). **§12:** MB_ICE is in two rooms (the Hall of Fame and Shoal Cave's
low-tide ice room); currents are Routes 132–134 and Seafloor Cavern rooms 6, 7; bike-only tiles are rails (Route 119,
Safari Zone south), bumpy slopes (Jagged Pass, Safari Zone north) and muddy slopes (5 maps); bike-blocked are long grass
(Routes 119, 120, Faraway Island), hot springs (Lavaridge), the logs (Pacifidlog), the Fortree bridges at an even
elevation and `NO_RUNNING` (Fortree, Lavaridge, Pacifidlog); Trainer Hill's elevator is plain script warps *(read)*, and
Trainer Hill is left out (D8). Which items the Acro tricks gate: **not settled** (needs a reachability walk over the
object list; not run). **§10's alias question:** 74 Emerald-only canonical ids (≥ 0x100) on 7,852 cells; the export's
`% 256` (GB/`lib/engine/game3/export.lua:662`) stays one-to-one inside Emerald, so FA1 didn't misclassify anything, but
the names it writes are FRLG's (0x166, the cracked-floor hole, is written `FALL_WARP`) and its land rule misses long
grass, ash grass and deep sand *(read; no builder code reads the names)*. Scripts: 4,047 roots; 405 warp commands, 115
warp setters, 2,331 player moves on 218 maps, 37 `setstepcallback` (ash: Jagged Pass, Mt. Chimney, Route 113; Fortree;
Pacifidlog; Sootopolis Gym's ice; the truck; secret bases; cracked floors: Granite Cave B1F, Mirage Tower 2F/3F, Mt.
Pyre 2F, Sky Pillar 2F/4F), 289 coord events with a script on 62 maps.

**2. Parity.**

| | Break | Walking | Surfing | Maps | Where (most) |
|---|---|---|---|---|---|
| A | a solid cell built with an empty column | 1,183 | 14 | 77 | Slateport 134, Route 130 94, Route 119 84, Lilycove 76 |
| A | onto a ledge cell from the side | 764 | 12 | 39 | Routes 118, 115, Meteor Falls 1F 2R, Route 114 |
| A | off an edge with no connection, into the void | 118 | 268 | 17 | Route 130 155, Rustboro 50, Route 118 48, Route 120 42 |
| A | one-way sides | 212 | 7 | 5 | Lilycove's contest hall 80, the Ancient Tomb, Desert Ruins, Island Cave 44 each |
| A | walking onto water from a higher cliff (the walker stands on the closed water block's top) | 197 | — | 12 | Routes 110, 120, Mossdeep, Aqua Hideout 1F |
| A | rails and bumpy slopes on foot | 166 | — | 4 | Route 119 101, Jagged Pass 33, the Safari Zone |
| A | surfing across elevations (water gets no elevation walls) | — | 94 | 8 | Route 106 70, Route 121, Meteor Falls, Ever Grande |
| A | a crossing the game refuses | 18 | 41 | 16 | Routes 133, 132, 115, Rustboro |
| A | rotating gates; lower ground of another elevation; a counter; other | 64 | 4 | 11 | the Trick House 6, Fortree Gym, Mossdeep |
| B | a wall or a height step with no ramp | 92 | 164 | 10 | Route 110 150 (the Cycling Road over the water), Route 120 49, Route 119, Ever Grande |
| B | a connection walled at the seam | 121 | 14 | 16 | the Safari Zone (four maps), Dewford, Mossdeep, Routes 107, 114, 126, 127 |
| B | a drop of more than 8 blocks | 77 | 16 | 4 | Route 110 71, Ever Grande, Mossdeep |
| B | Mirage Island's story cells, closed to a surfer | 3 | 55 | 2 | Route 130 |
| B | other | 2 | 2 | 2 | |
| | **Total** | **A 2,722, B 295** | **A 440, B 251** | | |

**The bike** (from the dump: Minecraft has one set of colliders for every vehicle): **5,761 cell-directions the game
refuses a bike and lets a walker through** (Route 119 2,887, Route 120 1,654, Faraway Island 646, Pacifidlog 242,
Fortree 140, warp doors refused to bikes) — A for a bike. 109 rail cell-directions only the Acro bike may ride.

**3. Sweep.** 6,147 rows: 4,768 passed, 333 not started (mostly start cells inside a current), 88 halted, **0 D** (the
14 flagged rows passed on a rerun with the map's own cells recorded, or were cells told just after the row ended),
**6 C** (below), 739 notes (warp kinds). By class: ledges 746/760, refused from below 757/760, one-way sides 260/260,
currents 681/957 (each step `script`; 19 crossed a map seam as a `script` warp), ice 68/68, waterfalls 78 (the push
back down, as the game does), slides and the slippery floor, thin ice 64/64 (each crack told), cracked floors 87 falls
(each crack in `story.cells`, the fall warp kind **`door`**, landing on the floor below), muddy slopes (pushed back),
rails and bumpy slopes refused on foot 81/81, ash grass 272/287, the logs and bridges, counters 337/344, grass, sand,
puddles, shallow water, coord events 182/244 (the rest a scene the sweep halted or a branch that didn't warp), driven
script warps, entry scripts. **Warp kinds seen** (the mechanism's in brackets): ladders `script` 100/100 [ladder],
cave doors `script` 183/183 [door], arrow warps `edge` 239 / `script` 175 [edge], water doors and arrows `script` 11
[door/edge], escalators `door` 17 / `script` 15 [escalator], Mt. Pyre's holes `door` 4 / `script` 4 [hole], Lavaridge
`door` 13 / `script` 13, the Aqua Hideout's pads `pad` 3 / `script` 3 [pad], warp doors `door` 160/162: the landing
(`arrive.to`) was right every time. **The 6 C rows:** a step onto Route 113's ash grass that starts a wild battle: the
battle's closing warp puts the client on the cell before the step while the hidden player stands on the new one (the
replay shows the client doesn't recover).

**4. Features and steps.** Reachable and right: Surf on (the hop sent as `ledge`) and off (`dismount`), Waterfall
(the climb as `surf` steps), Dive and emerge (`act.field dive`: landing right, warp kind `door`: K1), Cut and Rock
Smash (the objects gone), the bikes' basic riding. **Not right:** **running** (with `move.hold{b}` held the hidden
player runs, 8 frames a cell, but the client never sends it: K2), the **Mach Bike** never speeds up (16 frames a step at
the client's cadence) and the **Acro bike's** tricks can't be asked for (K3), **Strength** (the push answered
`blocked: entity`, the boulder doesn't move, on 4 of 4 maps: K5, cause unresolved), Flash refused in Granite Cave 1F in
the new game's state (`not_here`), Dig and Teleport arrive as `script` (K11), Fly opens the game's own map. Steps: 20
walking steps: the step total +20, Repel −20, the daycare counter +20, **equal**.

**5. Replay and spots.** Replay: 5,097 of 5,126 rows end in the engine's cell. The 29 others: the 6 ash-grass battles;
**in-map warps** — the Aqua Hideout's pads (3), Mossdeep Gym's warp tiles (2), the Regi chambers' south arrow warp (3),
Lavaridge B1F's sand warp to 1F (1); 12 rows on Mt. Pyre's exterior (the client a cell short: unexplained, worth a spot);
a cut-off boat ride; one waterfall. Spots: **25 of 28** — the coord event, cracked floor and its hole, a current, ice, a
ledge, the muddy slope, a slide tile, the slippery floor, thin ice, a cave door, Mt. Pyre's hole, the arrow warps
(south, east, north, water), a warp door, a water door, both escalators, both Lavaridge warps, a Petalburg Gym door,
Route 131's deep-water warp, a waterfall. **Failed: the Aqua Hideout's pad and Mossdeep Gym's warp tile** (the client
stays where the warp started while the hidden player is across the room: **C confirmed in the real client**), and a 3D
cave's ladder (the client climbs instead of stepping: by design). Video:
`/workspace/videos/kanto-bridge-emerald-field-spots-20261024.mp4` (7.9 MB).

### 13.5 Across the games: the builder's breaks (A, B)

Four builder causes make most of the A and B counts, in every game:

1. **Solid cells built with an empty column** (A: Blue 849, Crystal 4,302, Emerald 1,197): the shape plan's
   `blocks()` gives 0 for cells the model calls solid (2–4 high), so nothing collides; the player walks into rock and
   furniture until the client snaps back. Read back in LandSky3's region files: floor + air.
2. **The heights pipeline at map edges and cliffs**: the void fill doesn't reach a raised map's height (A: Blue 67,
   Crystal 1,319, Emerald 386), seams between raised maps are walled (B: Blue 124, Crystal 252, Emerald 135), cliffs
   between cells the game joins have no ramp (B: Crystal 39, Emerald 256 + 93 drops), and a walker can drop from a cliff
   onto the top of a closed water block and stand on the water (A: Emerald 197).
3. **One-way rules without colliders**: the ledge collider is open from its sides (A: Blue 281, Emerald 776), Emerald's
   one-way sides (219) and Acro tiles (166), surfing across elevations (94), counters built walkable in Crystal and Blue
   (91). These rely on the client's snap-back today.
4. **Crystal's ledges** (B 1,349): built the Gen 1 way, one cell off from Gen 2's hop.

### 13.6 The suspected gaps K1–K13

| # | Verdict | Evidence |
|---|---|---|
| K1 | **Confirmed, wider than §5 said** | Emerald's ladders, cave doors, water warps and half the arrow warps arrive as `script`; escalators, Mt. Pyre's holes and Lavaridge split `door`/`script`; cracked-floor falls and Dive are `door`. Landings right every time. *(read)* `game3/move.lua:132` turns the engine's announced kind `warp` into `script`, `:133` makes the rest `door`, and the engine's own name (`fall`, `dive`, `escalator`) is never used. Crystal's ladders arrive as `stairs` (D2 wants `ladder`). |
| K2 | **Confirmed: a client gap only** | B held through four steps made the hidden player run (8 frames a cell); the client never sends that hold off the Cycling Road and no `run` fact is sent. |
| K3 | **Confirmed** | The Mach Bike stays at 16 frames a step at a player's step cadence *(inference: the sweep's steps came one at a time, as a player's do)*; one bike speed in the protocol; Acro tricks can't be asked for; rails and bumpy slopes are refused `tile` on foot and open in Minecraft. Bike steps never pass the bridge's tryMove tap *(read)*. |
| K4 | **Confirmed** | Route 17: steering, the brake and dismounting are refused (`blocked: script`, `not_idle`) while the road rolls the player as `script` steps; the engine lets the player steer *(read)*. |
| K5 | **Confirmed in Crystal; not reached in Emerald** | Crystal: the push answered `blocked: entity`, the boulder moves as a person, no `push`. Emerald: the boulder didn't move at all in 4 of 4 maps (engine or setup: unresolved). Blue: `push`, as designed. |
| K6 | **Confirmed** | 87 falls: the crack in `story.cells`, the fall `door`, the landing right. The fade stays (D3: a pass). |
| K7 | **Confirmed by reading; no misfire seen** | While the hidden player ran, `f.runner` held the running shoes' state; nothing in those rows would show a wrong story diff. |
| K8 | **Confirmed where it breaks** | Ice, slides and currents built as plain ground or water make no parity break; one-way sides, Acro tiles, bike-blocked cells and elevations do (above). |
| K9 | **Partly settled** | 5 unused Emerald prototype maps aren't built; Dive and emerge land right where tried (the Abandoned Ship, the Aqua Hideout, Marine Cave); Sootopolis' own dive was not tried. |
| K10 | **Confirmed: fades, not breaks** | Blue's 12 scripted holes land right as `hole` fades; Crystal's 94 in-map ladders are fades. |
| K11 | **Confirmed in Crystal and Emerald** | Teleport, Dig, Escape Rope arrive as `script`; Blue's have their own kinds. |
| K12 | **Counts equal** | 20 steps: equal in all three games (totals, Repel, daycare, Safari, poison). Repel doesn't touch Cobblemon's spawns, by design. |
| K13 | **Listed** | Time-conditioned script commands on 29 Crystal maps: the 22 Pokémon Centers' 1F (the greeting), the Goldenrod underground, Mt. Moon Square, Routes 34, 35, 39, 45, Celadon Mansion's roof house. D6 keeps the DAY world. |

**New findings outside K1–K13:** Emerald's in-map warps (pads, Mossdeep Gym, the Regi chambers' arrow) leave the client
behind (C); a wild battle started by a step leaves the Emerald client a cell behind (C); Crystal's Rock Smash from
Minecraft does nothing (E); Crystal's ledges can't be walked (B, ends as a fade); the shape plan's empty solid columns
(A, all games); Gen 3 surfers cross elevations (A); the engine crashes when an `onFrame` script warps into a Battle Tent
(`rse/frontier/factory.lua:605`, upstream; D8 leaves those maps out).

### 13.7 Pass or fail (§6)

| Game | A = 0, B = 0 | C = 0 | D = 0 | Features reachable | Steps equal | Verdict |
|---|---|---|---|---|---|---|
| Blue | no (A 1,236, B 124) | **yes** | **yes** | **yes** | **yes** | fails on the builder's A/B only |
| Crystal | no (A 5,831, B 1,640) | yes (no real C) | **yes** | no (Rock Smash; Cycling Road steering; Fly's destination) | **yes** | fails |
| Emerald | no (A 3,162, B 546) | **no** (in-map warps, a battle on a step) | **yes** | no (running, Mach speed, Acro tricks, Strength) | **yes** | fails |

### 13.8 The fix plan (FA8), per game, with the questions it needs

Ordered by what a player meets first. Each item names its rule and size; "Q" items need your answer (§13.9).

**All games (the builder; one change helps all three):**
1. **Never build a solid cell without a collider** (A, ~6,300): when the shape plan gives a solid cell no blocks, keep
   an invisible barrier (or the plain column) at feet level. Small. Q1.
2. **The heights pipeline**: carry the void fill up to a raised map's height; open seams the game crosses (a ramp or a
   matching height); a ramp or wall where two joined cells sit far apart; keep walkers off water tops (a barrier over
   closed water blocks next to higher ground). Medium; touches LEVELS_PIPELINE. Q2.
3. **One-way rules**: ledge colliders closed from their sides; Emerald's one-way sides as edge plates; counters built
   solid in Crystal and Blue; Emerald's water elevation walls. Small to medium. D7's default applies (breaks found);
   Q3 asks whether to collide or keep relying on the snap-back.

**Emerald (adapter and client):**
4. **Warp kinds from the engine's own name** (K1, D2): `payload.warp.kind` → `ladder`, `escalator`, `dive`, `hole`
   (falls), `pad`, `door`, `edge`; FA0's values. Small.
5. **In-map warps** (C): the client must follow a warp whose destination is its own map (pads, Mossdeep Gym, the Regi
   chambers' arrow). Medium (find the cause first: the warp's start/arrive pair or the fade's same-map rule).
6. **A battle on a step** (C): after the battle's closing warp, the client must settle on the hidden player's cell.
   Small to medium.
7. **Running** (K2, D4: add): the sprint key → `move.hold{b}` through the step on maps the game allows; `player.state.run`;
   the run speed in Minecraft; first split `f.runner` into script-running and running (K7). Medium.
8. **The Mach Bike's speed** (K3, D5: yes): chain held steps so it accelerates; a second bike speed. Acro tricks: Q4.
9. **Strength** (K5): find why the push is refused, then a `push` answer and the boulder's move. Small to medium.
10. **Bike-blocked cells** (5,761 A for a bike): a story-state rule that closes them for a biking player. Q5.
11. Kinds for forced moves (`current`, `ice`, `spin` for slides; N notes) and the export's behaviour names (§10). Small.

**Crystal:**
12. **Ledges** (B 1,349): build Gen 2's ledge cell as standable land and make the jumped cell passable downhill only
    (a one-way drop one cell on). Medium; Q6.
13. **The Cycling Road** (K4): stop counting the downhill as forced; answer steering steps; `slope` for the roll (Gen 2
    has no brake). Small.
14. **Rock Smash from Minecraft** (E): go through the party menu's path (`PartyMenu:useFieldMove`). Small.
15. **Strength** (K5): a `push` answer and `npc.move push` for the boulder. Small.
16. **Warp kinds**: `ladder` for ladders (D2); Fly, Dig, Teleport, Escape Rope from `setupMethod` (K11). Small.
17. Two-facing ledges: export both facings. Small. Fly's destination from Minecraft: Q7.

**Blue:**
18. Only the builder's items 1–3. The 12 scripted holes' landings in the export (K10) stay a fade (D3) unless you want
    the H5 shaft model for them: Q8.

### 13.9 Decisions needed before FA8

| # | Question | Default if you say "go" |
|---|---|---|
| Q0 | Which game first, and builder fixes before adapter fixes? | the builder's items 1–3 first (they help all three), then Emerald, Crystal, Blue |
| Q1 | Empty solid columns: an invisible barrier, or bring the plain column back where the shape plan leaves none? | an invisible barrier at feet level (keeps the look) |
| Q2 | The heights fixes now (they touch the levels pipeline and need a rebuilt world and pictures), or after the adapter fixes? | after the adapter fixes, as their own pass |
| Q3 | One-way rules: real colliders (ledge sides, one-way sides, counters, water elevations), or keep the snap-back? | real colliders (D7: breaks were found) |
| Q4 | The Acro bike's tricks (wheelie, bunny hop, rails): add a key, or leave them unreachable? | leave them (D5). **Answer: not the default; see below** |
| Q5 | Bike-blocked cells: closed for a biking player (a story-state rule), or keep the snap-back? | closed for a biking player |
| Q6 | Crystal's ledges: rebuild them the Gen 2 way (a medium builder change and a Johto rebuild), or keep today's fade? | rebuild |
| Q7 | Fly from Minecraft in Crystal and Emerald opens the game's own map: keep that, or add a destination list on our side? | keep the game's map |
| Q8 | Blue's scripted holes: real drops (the H5 shaft model) or fades? | fades (D3) |

**Answered by the user, 2026-10-25:** the default for Q0–Q3 and Q5–Q8. **Q4 is not the default.** The user wants a
Minecraft bike feature, a real bike with the Acro tricks (wheelie, bunny hop, rails) and the Mach Bike's speed, planned
in a separate session. FA8 leaves the tricks alone and does not design a bike key or bike speeds of its own: item 8's
Mach speed and Q4 wait for that plan. FA8 item 10 (bike-blocked cells closed to a biking player) goes ahead.

### 13.10 FA8: the fixes (2026-10-25, kb-dev24)

Every item of §13.8 the answers left in FA8, in the order of Q0, each with its tests and a rerun on the real engines
(the sweep rows that failed, the features, the replay through the client, the real-client spots). Commits 6985aaf to
236834c, all local. The builder's fixes change the **planned** world (the parity numbers below are the plan's); a world
built before them keeps the old blocks until it is rebuilt.

| # | Fix | Where | Shown by |
|---|---|---|---|
| 1 | an empty solid column keeps an invisible barrier (or a solid hanging); a solid cell lower than ground beside it reaches that ground in barriers | `WorldPlan` | parity: solid-cell A Blue 849 → 3, Crystal 4,302 → 6, Emerald 1,197 → 7 |
| 3 | ledges walled from their sides (Gen 1, Gen 3) and open in Gen 2 (`LedgeColliderBlock.MODE`; worlds built before keep the old rule); Emerald's one-way sides and water across elevations walled (`PairEdges`); a plate on a counter's seat sides | `LedgeColliderBlock`, `PairEdges`, `WorldPlan` | parity: ledge side entries Blue 281 → 0, Emerald 776 → 0; Emerald's one-way sides 219 → 7, water across elevations 94 → 8; counters Blue 27 → 0, Crystal 64 → 0 |
| 12, 17 | Crystal's hop passes the cliff cell it jumps (a one-way ledge wall at feet, decoration above, the art kept), both facings of a corner ledge | `WorldPlan`, `G2Cells`, `CellFlags` | parity: Crystal's hop walls 1,349 → 38 (cells drawn as plain tiles keep the fade) |
| 2 (part) | a surface map's side with no connection walled; water under a cliff gets barriers above a surfer's head | `PairEdges.withBorders`, `WorldPlan` | parity: off the map into the void Blue 67 → 0, Crystal 1,319 → 0, Emerald 386 → 55; Emerald's walkers on water 117 → 0 |
| 4 | Emerald's warps named by the tile (ladder, door, edge, pad, hole, escalator) and else the engine's own announced kind (fall, dive, escalator, teleport, rope); a cave shaft's take still claims its warp | `game3/move.lua`, `kb_warp.lua` | real engine, warp sweep 1,091 rows: 0 C, 0 D; ladders `ladder` 86/100, cave doors `door` 142/187, pads `pad` 28/28, Mossdeep `pad` 21/22; replay 1,075/1,077 |
| 5 | a warp within its map arrives (settled where the player stands) | `kb_warp.lua`, `game3/move.lua` | real client: the Aqua Hideout's pads and Mossdeep's tiles 6/6 (0/6 before) |
| 6 | a battle's fade settles after the battle, where the step landed | `kb_warp.lua` | real engine's ash grass replayed: 281/281 (6 C before) |
| 7 | running: the sprint key holds B on foot in Emerald (the brake's hold), `player.state.run`, `story.state.run`, the running speed; K7's `runner` is a script running again | client `MoveRuntime`, `SlopeBrake`; server `Rules`; protocol; `game3/observe.lua` | real engine: B held, 8 frames a cell; Lua, JUnit |
| 9 | Emerald's Strength push observed and answered `push` (FA5's refusals were the driver's: a push direction the engine refuses) | `game3/observe.lua`, driver | real engine: `push` on 4 of 4 maps |
| 10 | Emerald's bike-blocked cells (long grass, no running, hot springs, warp doors) close for bikers: a new invisible `bike_wall` | `BikeWallBlock`, `G3Cells`, `WorldPlan` | GameTest `bikeWallsStopOnlyAPlayerOnABike` (133/133) |
| 11 | forced moves by kind (Emerald by the engine's table entry: current, ice, spin, slope; Crystal: current, ice); Dig, Teleport, Fly, the rope name their warps in both games; the Emerald export names behaviours by the engine's full id | `game3/`, `gen2/` adapters, `kb_player.lua`, `game3/export.lua` | real engine: Emerald ice 159 `ice`, currents 1,461 `current`; Crystal ice 777 `ice`, currents 174 `current`; Teleport `teleport`, Dig `dig` |
| 13 | Crystal's Cycling Road steers: the roll is no forced move, its steps are `slope` | `gen2/observe.lua` | real engine: steering answered as steps, the roll `slope` |
| 14 | Crystal's Rock Smash from Minecraft queues the smash script, as the party menu does | `gen2/fieldmoves.lua` | real engine: the rock gone on 3 of 3 maps |
| 15 | Crystal's Strength push observed and answered `push` | `gen2/observe.lua` | real engine: `push` on 3 of 3 maps |
| 16 | Crystal's ladders `ladder`; its field moves' warps named | `gen2/move.lua`, `gen2/fieldmoves.lua` | real engine: Teleport `teleport`, Dig `dig`, rope `rope` |

**Parity now** (the planned world, cell-directions; the audit's numbers in brackets):

| Game | Walking A | Walking B | Surfing A | Surfing B |
|---|---|---|---|---|
| Blue | 3 (1,225) | 98 (98) | 3 (11) | 26 (26) |
| Crystal | 146 (5,677) | 267 (1,578) | 0 (154) | 62 (62) |
| Emerald | 277 (2,722) | 295 (295) | 76 (440) | 251 (251) |

**Real-client spots again** (LandSky3, built before the builder's fixes): Blue 11 of 11, Crystal 12 of 13, Emerald 28 of
29 (25 of 28 before: the pads and Mossdeep's tiles pass); the two left are ladders in 3D caves, where the client climbs a
shaft by design (H5). Videos on the volume: `/workspace/videos/kanto-bridge-{blue,crystal,emerald}-field-spots-fa8-20261025.mp4`.

**Left for later, on purpose:**
- **The seams between raised maps and the cliffs with no ramp** (most of the B left: Blue 124, Crystal ~300, Emerald
  ~500): the levels pipeline's ragged seams and open loops (LEVELS_PIPELINE.md "Seams between maps"), which need its
  stair tables for Johto and Kanto and the user's review of pictures (Q2: their own pass).
- **The Mach Bike's speed, the Acro tricks, rails and bumpy slopes** (Emerald's 166 rail A): the Minecraft bike feature,
  planned in its own session (Q4).
- **Bikes on Fortree's bridges and Pacifidlog's logs** (the bridges depend on the player's elevation, the logs are story
  cells).
- ~~A world rebuild~~ **done 2026-10-25**: world **LandSky4** (LandSky3's recipe: models table v14, every outdoor map,
  chained, 20 GB heap; kb-dev50, 50 minutes), all three regions `build OK` with the same warnings as LandSky3 (Kanto 0,
  Hoenn 7, Johto 3). Blocks, LandSky3 -> LandSky4: edge walls Kanto 2,548 -> 4,596, Hoenn 1,839 -> 7,046, Johto 6,120 ->
  11,406; Johto's ledges 5,232 -> 10,792 (two-facing). Real-client spots on LandSky4: Blue 11 of 11, Crystal 12 of 13
  (the Ice Path ladder, as before), Emerald **29 of 29** (Artisan Cave's ladder now passes). Videos
  `/workspace/videos/kanto-bridge-{blue,crystal,emerald}-field-spots-sky4-20261025.mp4`; FA8's LandSky3 results kept
  in `/workspace/videos/landscape/spots_landsky3_fa8/`.
- Small ones seen on the way: Lavaridge's B1F pop-up lands over a sand hole the builder draws as a drop (the client falls
  through, one replay row); an Emerald Dig shows a fade the bridge names `script` before the `dig` warp.
