# Every script and field mechanic in Minecraft: the audit (Blue, Crystal, Emerald)

**Status: proposed, 2026-10-24.** Nothing in this plan has run yet. It is written for an agent to execute phase by
phase on a pod against the real engine, with the user's decisions in §9 (each a default the agent proceeds with unless
the user changes it).

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

- Which Emerald rooms use MB_ICE and MB_SLIDE_*; the exact set of bike-only behaviours; the current routes (132–134);
  Trainer Hill's elevator.
- Crystal's Ruins of Alph floor collapse and Burned Tower fall are step-driven falls (or script warps).
- Pokérus and happiness count overworld steps in the engine's port.
- Which optional items the Acro bike's tricks gate.
