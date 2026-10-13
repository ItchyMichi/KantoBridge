# Plan: caves in 3D (raised ground, an area of their own, real ladders and holes)

- **Status:** written on 2026-10-12 from your answers of that day (§2.1); nothing is built yet. The defaults in §2.2
  are the agent's; change any of them and the plan follows.
- **What it delivers:**
  - **Raised ground in caves.** Where the Game Boy fakes a plateau with its tile-pair rule, the cave floor really
    rises 2 blocks, and the cave's stairs climb it. Every cell keeps 3 blocks of air.
  - **Caves in an area of their own,** north of Kanto past the dungeon slots, so they have all the height they need.
    Houses, shops and gyms stay under their doors.
  - **Real ladders and holes.** You climb a ladder down a shaft to the floor below, or drop through a hole, instead of
    a fade. The hidden game makes the warp when you arrive.
  - **No more walking across a raised edge and being pulled back:** invisible walls stand on every edge the game
    blocks, in caves and in the forests.
- **Why:** your question of 2026-10-12 and your answers the same day (§2.1). The research is
  [HEIGHTS_RESEARCH.md](../research/HEIGHTS_RESEARCH.md) (HR below).
- **Sources:** HR; two reads of today's code for this plan (file:line below; Java paths are under
  `fabric-bridge/src/{main,client}/java/dev/kantobridge/`). Gen 1 facts marked *(general knowledge)* come from no
  file here; the dry runs in H4 and H5 check them on your export. Nothing ROM-derived was opened.
- **Size:** seven milestones, H1–H7. H6 is the pilot: one cave on the pod and a video before the rest.

---

## 0. How to execute this plan

- The voxel plan's §0 applies ([VOXEL_BLOCKS_PLAN.md](VOXEL_BLOCKS_PLAN.md) §0.1–§0.4), with the changes below.
  Milestones run in order; one commit each, `H<n>: <title>`, with the harness's attribution trailer.
- **One dimension.** Everything stays in the overworld (U3). Nothing here adds a dimension.
- **The protocol has one owner** (VOXEL_BLOCKS_PLAN §0.2): `docs/PROTOCOL.md` and `protocol/` change first, then the
  catalog, then the code.
- **Feature tests per milestone; the full suite at H1, H5 and H7** (your rule of 2026-10-11). Pods are started and
  stopped per job.
- **The pilot switch.** Until you approve the pilot (H6), raised ground and shafts are built only for the caves named
  in the build option `caves3d.pilot`. Every other cave gets H2's walls and H3's area, and keeps its fades.
- **Dry runs** on the pod (H4, H5) build the real world and report counts and map ids only.
- **Rebuilds:** H2–H7 change the built world. Your world shows them after a rebuild, which you start.
- **Client behaviour is verified by you** (no client GameTests on 1.21.1): climbing and the shaft logic are plain Java
  behind the existing adapters, tested in JUnit, and shown in the pilot video.

## 1. Scope

| Area | What is built | Milestone |
|---|---|---|
| Positions | A block position per cell: pieces (parts of a map placed apart) and levels (raised floors) in the WorldIndex, and every lookup goes through them. Nothing moves yet | H1 |
| Edge walls | Invisible walls on every edge the game's tile pairs block: caves, Viridian Forest and the Safari Zone | H2 |
| The cave area | Cave maps leave the interior layers for an area north of Kanto; their biome, spawns and habitat blocks; cave entrances become fades over the distance | H3 |
| Raised ground | Levels from the tile pairs, stairs that climb, the check, builder columns, shapes, dressing, self-check, step colliders | H4 |
| Ladders and holes | Cave floors split into pieces placed so that ladders and holes line up; shafts; climbing and falling; the hidden warp on arrival (`warp.take`) | H5 |
| Pilot | The real world on the pod with one cave in 3D; a video; your call | H6 |
| All caves | Every cave in 3D; budgets; the full suite; docs; a second video; the play-test list | H7 |

Not in this plan: §8.

## 2. Decisions

### 2.1 Yours (2026-10-12)

| Id | Decision |
|---|---|
| U1 | A level is 2 blocks high: a real cliff above eye height |
| U2 | Caves move to an area of their own in the overworld. Houses, shops and gyms stay under their doors |
| U3 | One dimension. The vanilla world plan (an interiors dimension) is dropped: several dimensions loaded when players are spread out, and puppets across dimensions |
| U4 | Real ladders and shafts between cave floors instead of fades |
| U5 | The defaults offered that day: 3 blocks of air over raised ground too; edge walls in caves and the forests; heights in caves first, the forests and outdoors later; one pilot cave and a video before the rest; a rebuild |

### 2.2 Plan defaults (the agent's; change any)

| Id | Default | Alternatives | Why this one |
|---|---|---|---|
| HD1 | A cave is a map with the CAVERN tileset (`builder/plan/WorldPlanner.java:52-53`, `Env.cave`). A cave complex is the set of caves joined by warps | A list of map ids | The rule CW1's cave rock already uses. Another game's cave tilesets join by name |
| HD2 | The cave area starts 3,000 blocks north of the dungeon region, at Kanto's west edge. One slot per complex, slots 64 blocks apart | West or east (kept for other games); under Kanto (no room) | Past every render distance, like the zones and dungeons (`builder/zone/ZoneRegions.java:15`; `dungeon/DungeonSlots.java:21`) |
| HD3 | Floors of one cave sit 11 blocks apart (feet to feet) where they overlap. A ladder is about 5 s up and 4 s down; a hole about 1 s | 22, today's layer step (about 9 s up) | The least that leaves rock between them with raised ground and keeps each floor's wild Pokémon off the others (HD8). **It bends DESIGN.md:40 (16 blocks) inside one cave.** Different caves stay at least 64 apart |
| HD4 | **Free climb.** You climb or fall yourself; the hidden game warps when you reach the other end; your controls pause for that warp, about half a second | A guided climb: the warp first, then a locked climb | You can stop, look and go back. The hidden game stays on the floor you're on until you arrive |
| HD5 | **Ladders and holes line up by pieces.** A floor is split into its separate rooms, and each room is placed under or over the rooms it links to. A link that still can't line up keeps today's fade, counted in the build report | Tunnels between misaligned ends (a walk of up to about 25 s); whole floors only (most ladders keep the fade) | Gen 1's floors weren't drawn to line up: Rock Tunnel's ladder pairs each need a different offset *(general knowledge)*, but each leads into a separate room |
| HD6 | A shaft is one cell, 2 × 2 blocks. The ladder is on the wall you face as you walk in (opposite the end cell's only walkable neighbour; north if it has several) and looks like Minecraft's ladder. The shaft's walls wear the cave's rock | The cave's own ladder tile | Minecraft's ladder costs no slot and nothing on the shape map, and everyone knows how to climb it |
| HD7 | A hole is an open shaft with no ladder: you fall to the cell below, unhurt. Holes are one-way, as in the game | — | — |
| HD8 | Cave habitat blocks reach 6 blocks instead of 14, so a floor's pool never reaches another floor | Height bands per pool | `RangeOfInfluence` is already set per block (`docs/PLAN.md:910`). Bands need Cobblemon conditions nobody has checked |
| HD9 | Caves in the area get their own biome, `kantobridge:cave_3d`, a copy of `kanto_cave` that spawns at any height. There the air between rock and ceiling is made solid, so a floor is the only place to stand | Keep `(y − 63) mod 22` and 22-block floors | That rule (`data/kantobridge/spawn_rules/walking_level_only.json:8`) can't hold for raised floors or free floor heights. `kanto_cave` also covers the Mansion and other "wild" maps (`WorldPlanner.java:429`), which keep it |
| HD10 | Where the bottom of a ladder doesn't lead back to its top, the shaft works downwards only, and going up keeps the fade | — | The game decides where a warp lands |
| HD11 | The pilot is the complex with the most ladders and raised ground that all pass, from H5's dry run: Mt. Moon if it qualifies | You pick | The first cave you reach, so you can play it soon |
| HD12 | A ladder's top cell is an open hole; its bottom cell is floor under a hole in the ceiling. Walking over a bottom cell without climbing: the hidden player walks round it (up to 4 steps), or you're pulled back | — | The game would warp you on that cell, so the hidden path never crosses it |

## 3. Architecture

### 3.1 A position per cell (H1)

- **Today:** one feet level per map; cell (x, y) is at `map.x + 2x`, `map.z + 2y` (`world/MapPlacement.java:21-22`;
  `world/WorldIndex.java:391-406`). `toCell` takes the first map, in id order, whose column holds the point and whose
  feet − 4 to feet + 5 holds y (`WorldIndex.java:56-58, 376-388`).
- **New per map, optional, in the WorldIndex:**
  - `pieces`: parts of the map placed apart. Each has a cell rectangle, a mask (run-length) when it doesn't fill the
    rectangle, and a block offset dx, dy, dz from the map's placement. A map without `pieces` is one piece at 0.
  - `levels`: each cell's floor above its piece's base, in blocks (run-length; 0 when absent).
  - `ramps`: the stairs cells, with the direction they climb and their rise.
- **New at the root, optional:** `caves`, the cave area's box (H3), so a rebuild can clear it (`volumesOf`,
  `builder/plan/WorldPlan.java:433-449`); `shafts` (H5, §4.1).
- **The format:** the new keys are written only when present, as `zones` and `dungeons` are
  (`WorldIndex.java:454-528, 580-639`). A world without them keeps its bytes and its goldens, and `version` stays 1.
  `MapPlacement` keeps its 12 fields (44 test constructions use them).
- **One API for every reader** (WorldIndex):
  - `cellOrigin` and `toBlock` go through the piece and the level;
  - `floorY(map, x, y)`: the cell's feet; `feetAt(map, bx, bz)`: the walking height over a block, which ramps need;
  - `toCell(bx, by, bz)`: the piece whose rectangle holds the column and whose cell's window holds y. The window is
    today's; inside the cave area it narrows to the cell's feet − 3 to feet + 4, so stacked pieces never share one;
  - `levelOf(map, x, y)`: the piece's base, which a `Spot` keeps as its level, so walking a ramp stays on one level
    (`client/move/Spot.java:56-58`; `MovementSync.java:415`);
  - `shaftAt(bx, by, bz)` (H5).
- **Every caller moves to it,** about 100 sites: index and lookups 9, client movement 14, puppets 5, clicks and
  effects 13, battles and encounters 6, server warps and holds 5, story cells 8, builder about 28, shape planner 12,
  client shapes 2. Dressing and the self-check work in map cells and don't move.
- **Guards that assume 22 blocks between floors become piece-aware:** puppet culling's ±11
  (`client/puppet/PuppetCulling.java:40-43`), the encounter and proxy distances (`battle/EncounterPlacement.java:15`;
  `battle/ProxyPlacement.java:14`), the biome writer's first match (`builder/place/BiomeWriter.java:50-54`),
  `InteractTarget.inPlayerMap`'s equal-y test (`client/act/InteractTarget.java:103`), `ActRuntime.ground`
  (`client/act/ActRuntime.java:215-219`) and `ClosedArtPick`'s lowest y (`client/act/ClosedArtPick.java:69-72`).
- **Two first-match lookups get fixed:** the shape map skips a grid whose stack doesn't reach y
  (`world/ShapeMap.java:530-536`), and `ClosedArt` indexes by x, y and z instead of x and z
  (`client/model/ClosedArt.java:60, 101-104`). Both could already misfire on stacked interiors; 11 blocks apart they
  would.
- **Lights** keep their 4-block grid, counted from each piece's origin (`WorldPlan.java:743-745`).
- **H1 changes nothing you can see:** every map is one piece at level 0, and the goldens stay byte for byte.

### 3.2 Edge walls (H2)

- `ExportField` reads `tilePairs`, exported at `gen1-bridge/export/kb_export.lua:53`
  (`builder/export/ExportField.java:52-83` reads four other tables today).
- **A pair edge** joins two neighbouring cells of one map whose collision tiles (`builder/plan/MapModel.java:251-253`)
  form a land pair, or a water pair when surfing.
- **The edge wall,** `kantobridge:edge_wall`: invisible, no outline, collision only, like the ledge collider
  (`block/LedgeColliderBlock.java:32-33`). Four side flags; each side is a plate 2 px thick and 24 px (1.5 blocks)
  high.
  - It goes in a free block at feet or feet + 1 on either side of the edge. Either height stops a player, who is
    1.8 blocks tall.
  - Where all of them are taken (in a map with boulders every walkable cell is a story cell, `docs/PLAN.md` "Story
    cells"), the story cell block carries the sides itself: a state on `StoryCellBlock`, added to its collision
    whatever the story says.
- **Tests:** JUnit for pair edges on a made-up CAVERN grid, the side flags and where the wall goes. A GameTest: a mock
  player stops at a pair edge and crosses every other edge; a story cell with sides keeps its story collision.

### 3.3 The cave area (H3)

- **Which maps:** HD1. `WorldPlanner` leaves them out of the interior rounds (`WorldPlanner.java:161-184`) and places
  them after Kanto through `CaveSlots`, a new placer like `DungeonSlots` (`dungeon/DungeonSlots.java:13-47`).
- **Complexes:** caves joined by any warp form a complex, and each complex gets a slot.
- **Floor order:** a cave's floor comes from its id: `…1F` is 0, `…2F` is +1, `…B1F` is −1, no suffix is 0. On a tie,
  the map with a door or edge to the outside is the higher one.
- **H3's layout, whole floors:**
  - the top floor at feet 64, each lower floor 11 blocks below (HD3);
  - horizontally, each floor lines up its first ladder with a floor already placed, breadth first, as
    `placeInterior` does with doors today (`WorldPlanner.java:383-432`); a floor with no ladder sits beside;
  - ladders and holes still fade.
- **The slot:** the complex's box plus 8 blocks. Slots run east from Kanto's west edge, 64 blocks apart, in rows up to
  2,048 blocks wide.
- **In the index:** caves keep kind INTERIOR, so every interior code path applies; `layer` is 1 + the floor's rank
  (read only by the build report and `inPlayerMap`); the root `caves` box.
- **Biome, spawns and habitat blocks:**
  - the slots' maps get `kantobridge:cave_3d` (HD9), which `walking_level_only.json` doesn't list, so it spawns at any
    height;
  - the rock fill: in those maps the air between a rock cell's top and the ceiling becomes solid. A decor carrier
    there gets collision (a solid `BlockCode.decor`); an empty block gets an invisible full collider. Nothing changes
    to the eye;
  - habitat blocks reach 6 (HD8): `HabitatPlanner` takes a range per map (`builder/plan/HabitatPlanner.java:31-37`).
- **Entrances:** a door or cave mouth from a route into a cave is now a long teleport. The fade waits until the
  arrival is drawn, 3 s at most (`client/move/WarpFade.java:167-171`), as Fly does today.
- **Freed room:** caves leave the packer, so houses that were moved sideways or deep may get their spot under the door
  back.
- **The synthetic cave:** `tools/fake_gen1/synth.py` gains a two-floor cave, `TEST_CAVE_1F` and `TEST_CAVE_B1F`, on a
  CAVERN tileset with tile pairs. It has raised ground and stairs, a ladder that lines up as a whole floor, two that
  need separate pieces, and a hole. The GameTest bundle is regenerated (`tools/tests/test_synthetic_bundle_copy.py`)
  and the goldens follow.
- **Tests:** JUnit for complexes and floor order, slot packing and gaps, the `caves` record, `volumesOf` clearing it,
  biome boxes that never overlap, the habitat range and cover, and the rock fill. GameTests: the synthetic cave builds
  in its slot; a warp from `TEST_TOWN` lands on the right cell; spawns happen on its floor and never on rock.
- **Pod run:** the real world. The report gives complexes, floors, slot sizes, layers freed, cave habitat blocks and
  the shape map's characters on the wire.

### 3.4 Raised ground (H4)

Per piece (one piece per map until H5):

1. Pair edges (H2) split cells into side A, side B and neutral (tiles in no pair).
2. A neutral cell with an A neighbour on one side and a B neighbour on the opposite side is a stairs cell. The
   profile's stair classes confirm it where Dramaless is installed (`scene/profile/Profile.java:30-66`).
3. Regions: a flood fill over walkable and water cells that crosses no pair edge and no stairs cell.
4. The high side: the profile's raised pins (`builder/shape/ClassBoxes.java:18-21, 41-42`), else the cliff-face rule
   (the art draws a face south of higher ground; the face is solid, so the floor meets raised ground flush only along
   its north edge, and across a pair edge running east to west the south side is higher), else the map stays flat.
   *Corrected after H4's dry run: the first version read it the other way round; see PROGRESS, H4 note 11.*
5. Levels: low regions 0, high regions 2 (U1). A stairs cell is a ramp from its low neighbour to its high one.
6. A region reached only by warps takes whatever level fits; after H5 it is a piece of its own.
7. **The check** (HR §3.1). For every pair of neighbouring cells, Minecraft must allow exactly the steps the game
   allows (walkability, pairs, ledges, water). Jumping is off and the step is 0.6 blocks, so a rise of 1 block or
   more stops you, half a block you walk up, and a drop the game forbids needs a wall on the high side. If any edge
   disagrees, the map stays flat and the report says why.

What gets built:

- **Columns:** each cell's floor at its own feet − 1, filled down to the piece's base. Solid cells stand on their
  highest walkable neighbour and are filled down to the lowest.
- **Ramps:** a stairs cell climbs 2 blocks over its 2-block run in four half steps. `kantobridge:step_collider` is
  invisible and holds two half steps (8 px at the front half, 16 px at the back), facing the climb. One sits at the
  low feet, and one on a full block at feet + 1. Today's stair units already draw the steps without collision
  (`builder/shape/Units.java:171-225`); they now stand on the colliders.
- **Edges:** the high side of a cliff gets H2's edge wall. The low side needs nothing, since 2 blocks can't be walked
  up.
- **Ceilings and lights** follow the floor: 3 blocks of air over every cell (U5), lights at feet + 2.
- **Shapes:** a grid per piece with its lowest floor as `baseY` (`builder/shape/ShapePlanner.java:211-219`), and
  heights from each cell's own floor. ClassBoxes' cover and roof hips compare absolute heights
  (`ClassBoxes.java:175-205`; `ShapePlanner.java:729-730`); `Dress.free` keeps levels ≥ 0
  (`builder/shape/dressing/Dress.java:113-119`).
- **Dressing:** CaveRock's lid and clear line, and `Ceilings`, per cell (`dressing/CaveRock.java:23-31, 110-150`;
  `dressing/Ceilings.java:54-55`).
- **Self-check:** a lift per pixel, with risers drawn (`builder/shape/PlannedGeometry.java:50-79`;
  `builder/shape/SelfCheck.java:33-44`). Otherwise raised floors would count as hidden ground.
- **Habitat blocks** go 3 under each cell's own feet (`HabitatPlanner.java:102`).
- **Puppets** stand on their cell's floor, smoothed along ramps (`client/puppet/PuppetRuntime.java:271-281`;
  `client/shape/ClientShapes.java:298-325`).
- **Encounters** spawn on the target cell's floor (`battle/EncounterService.java:236-266`;
  `battle/BattleService.java:278-288`).
- **Tests:** JUnit for sides, stairs, regions, the high side from each source, levels, the check on made-up grids with
  every edge kind, columns and ceilings, the step collider's shape and habitat heights. GameTests: a mock player
  climbs the synthetic cave's stairs, can't climb its cliff or walk off it, and crosses every other edge as the game
  allows.
- **Dry run on the pod,** per cave map: regions, stairs, raised cells, where the high side came from, and check
  failures with reasons; the shape map on the wire and quads per section.

### 3.5 Pieces and shafts (H5)

- **Links.** A link is a warp between two cave maps whose source cell is a warp tile (cell flag 32) and not a door: a
  hole if the cell has the hole flag (512), else a ladder (`gen1-bridge/export/kb_cells.lua:17-22, 124-127`). The
  exported warps are `{x, y, to, warp}` (`protocol/export/map.schema.json:98-112`). A ladder works both ways when the
  warp at its bottom leads back to its top (HD10). *As built (H5): a hole is the hole flag alone (a pad, not a warp
  tile); PROGRESS, H5 note 5.*
- **Pieces.** A cave map's pieces are the connected parts of its walk graph: walkable and water cells joined by any
  edge the game could ever use. Pairs, ledges and story blockers count as joined, so pieces never depend on the story.
  Rock cells belong to the pieces they border. Inside its rectangle, a piece draws other pieces' cells as rock.
- **Layout, per complex:**
  1. **Across:** starting from the entrance's piece, breadth first, each link places the piece it leads to so that
     the two end cells share a column. A link that disagrees with a piece already placed (a loop that doesn't close)
     keeps the fade (HD5).
  2. **Down:** pieces go in floor order, with the top floor at feet 64. A piece sits at least 11 blocks (HD3) below
     every piece above that it overlaps or links to. A piece in the way of a shaft moves down until it isn't. *As
     built (H5): that shaft keeps its fade instead; the real world has none (PROGRESS, H5 notes 5 and 13).*
  3. Pieces no link joins (a part with no ladder, or one whose links kept the fade) keep their map's arrangement among
     themselves and sit beside the rest.
  4. A complex that can't be laid out, deeper than Y −200 or wider than its slot rules allow, falls back to H3's whole
     floors and fades, with a warning.
- **A shaft** is built as:
  - a column of the two end cells' 2 × 2 blocks, from the bottom cell's feet up to the top cell's floor, with air
    inside;
  - a ring of the cave's rock round it wherever it runs between pieces;
  - an open floor in the top cell and an open ceiling over the bottom cell;
  - ladders (HD6) on one wall, from the bottom cell's feet to the top cell's feet. `kantobridge:shaft_ladder` uses
    Minecraft's ladder model and texture, is in the `climbable` tag, and stands without support;
  - a light halfway up, unless the cave is dark (Rock Tunnel until Flash, as today);
  - for a hole, the same column with no ladder.
- **In the index:** each shaft (§4.1). `toCell` treats a shaft's inside as no cell, and `shaftAt` names the shaft.
- **Tests:** JUnit for links, pieces and masks, and the layout on made-up complexes (a chain, a loop that closes, one
  that doesn't, a piece in a shaft's way, the fallback), the shaft's blocks and the index round trip. GameTests on the
  synthetic cave: a mock player falls through the hole onto the right cell; ladder blocks are climbable; no shaft
  crosses another piece.
- **Dry run on the pod,** per complex: pieces, links, those lined up, fades kept and why, depth and shaft lengths.

### 3.6 Climbing (H5)

**What you do:** walk into a ladder's top cell and climb or drop down, or walk onto its bottom cell and climb up; fall
through a hole. The hidden game warps when you reach the other end.

**On the client** (`MovementSync`, plain Java behind the existing adapters):

- It never sends a step onto a shaft's end cell. Today such a step makes the hidden game warp at once
  (`gen1-bridge/lib/kb_engine.lua:4989-4991`).
- **At an end:** you stand on a bottom cell, or hang in a top cell, while the hidden player waits where it was.
  Stepping back to where you came from changes nothing; stepping off to another neighbour is HD12.
- **In a shaft:** no steps, and drift corrections are off (`client/move/MovementSync.java:513-529`).
- **Arriving:** your feet on the far floor (going down), or at the far end's feet level (going up). The client sends
  `warp.take` and holds your controls (`InputLock.holdWhile`, `client/move/InputLock.java:72-88`).
- **The warp comes back** as `warp{start}` and `warp{arrive}`, carrying the take's `re`, with `to` where you already
  stand: no fade and no teleport. The hold ends at the arrival. Puppets and story blocks switch floors then, as at
  any arrival (`client/puppet/PuppetManager.java:84-111`; `story/StoryRules.java:217-230`).
- **If it fails** (refused, or no arrival within 3 s): a fade and a teleport back to the hidden player's cell, as
  `correct()` does today (`MovementSync.java:606-625`).
- **Anything unexpected** (a hidden warp nobody asked for, a script or battle while you're in a shaft, a relink) is
  handled as today, which puts you back with the hidden player.

**On gen1-bridge** (`warp.take`):

- It is accepted only in mode idle, on the take's `from` map, with the hidden player on the near end cell or next to
  it.
- It queues the engine's own `warp` command to the far end, with your facing, as the Wild Routes clearing does
  (`Routes.warpTo`, `gen1-bridge/lib/kb_routes.lua:483-505`). A busy world answers "busy", and the client retries for
  1 s.
- It reports the warp with kind `stairs` or `hole` and the take's `re`, instead of `script`.
- The warp is the engine's own: NPCs go back to their spawns, the map's enter script runs, and the arrival cell can't
  warp again until you step off it (`tools/lua/fake_world.lua:628-634`).

**Tests:** JUnit for the client's states with a fake index and port: in and out of a shaft at both ends, arriving, a
refusal, a timeout, a battle mid-shaft, stepping off to another neighbour. Lua tests in the fake world: it accepts,
refuses when busy or too far, and reports `re` and the kind.

### 3.7 Invariants

| Id | Invariant |
|---|---|
| I1 | Minecraft allows exactly the game's steps (§3.4's check); where it can't, the map stays flat |
| I2 | No two pieces share a lookup window or a block, and no shaft passes through a piece |
| I3 | A floor's habitat blocks reach no other floor's cells |
| I4 | Heights and pieces never depend on story state |
| I5 | A world built without the new keys reads, builds and plays as today: goldens byte for byte through H2 |

## 4. Formats

### 4.1 WorldIndex (optional keys; `version` stays 1)

- Per map: `pieces` `[{x, y, w, h, mask?, dx, dy, dz}]`, `levels` (run-length), `ramps` `[{x, y, dir, rise}]`.
- Root: `caves` `{x, z, w, d}`; `shafts` `[{kind, top{map, x, y}, bottom{map, x, y}, up, x, z, y0, y1}]`, where
  `up` is false for a hole or a one-way ladder.
- Schema: `protocol/world/world_index.schema.json`; the Python validator follows (`tools/kb_protocol/registry.py`).

### 4.2 Protocol

- `warp.take`, client → gen1-bridge: `{seq, from{map, x, y}, to{map, x, y, facing}, kind: "ladder" | "hole"}`.
- `warp.taken`, gen1-bridge → client: `{re, ok, reason?}`, with reason `busy`, `mode`, `map` or `far`.
- `warp` gains an optional `re`.
- In `docs/PROTOCOL.md`'s "Movement" section; the catalog is regenerated.

### 4.3 Blocks

`kantobridge:edge_wall` (H2); the rock fill's solid decor and invisible collider (H3); `kantobridge:step_collider`
(H4); `kantobridge:shaft_ladder` (H5).

### 4.4 Build option and report

- The option `caves3d.pilot`: complexes named by any of their map ids, or `all` (H7's default).
- New report fields, as counts and map ids only:
  - `caves`: complexes, maps, slots, layers freed;
  - `levels`: maps raised, maps left flat by reason, stairs, raised cells, high side by source;
  - `pieces`, per complex: pieces, links, lined up, fades kept by reason, depth;
  - `shafts`: ladders, holes, both ways;
  - `habitats`: cave blocks.

## 5. Milestones

### H1: A position per cell

- §3.1: the index keys and API, every caller, the guards, the two first-match lookups, lights per piece.
- **Tests:** JUnit for the index: pieces and levels round-trip; `toCell` inverts `cellOrigin` over made-up maps with
  pieces, levels and ramps; windows never overlap; old JSON reads unchanged. Every existing test passes unchanged.
  **Full suite** (checkpoint).
- **Commit "H1: a position per cell".**

### H2: Edge walls

- §3.2. A pod build counts the walls.
- **Commit "H2: edge walls on tile-pair edges".**

### H3: The cave area

- §3.3, with the synthetic cave. A pod build of the real world.
- **Commit "H3: caves in an area of their own".**

### H4: Raised ground

- §3.4, for the pilot list only, with its dry run on the pod.
- **Commit "H4: raised ground in caves".**

### H5: Ladders and holes

- §3.5–§3.6: the protocol first, then gen1-bridge, the builder and the client. Its dry run picks the pilot (HD11).
  **Full suite** (checkpoint).
- **Commit "H5: real ladders and holes".**

### H6: Pilot

- The pilot built on the pod's test install. Budgets: quads per block and section, the shape map on the wire.
- **A video:** up the stairs to raised ground, the cliff's edge, down a ladder and back up, and through a hole if the
  pilot has one.
- **You decide:** go on, or change something first.
- **Commit "H6: pilot"** (report and notes).

### H7: All caves

- `caves3d.pilot` defaults to `all`. The real world, budgets, the full suite and a second video.
- **Docs:** PROGRESS, ROADMAP, PLAYTEST, PROTOCOL, NEXT_STAGES, and REFERENCES if needed.
- **A note for you:** DESIGN.md:39-41 says caves sit under their mountains and every ladder fades. That's your file to
  update.
- **Play-test (you):** every cave end to end: Mt. Moon, Rock Tunnel with and without Flash, Seafoam's currents and
  boulders, Victory Road's boulders, Cerulean Cave, Diglett's Cave.
- **Commit "H7: every cave in 3D".**

## 6. Risks

| Risk | Answer |
|---|---|
| Links that don't line up (loops that don't close) | Those keep the fade. H5's dry run counts them before the pilot; tunnels are a later option (§8) |
| A floor of many rooms ends up deep | Bounded at Y −200, else whole floors and fades for that cave (§3.5) |
| The shape map on the wire (about 94% expected with the profile) | Pieces change no shape keys, only grids; raised ground adds a few riser keys. Measured from H4. If over: the voxel plan's R10, grids sent on demand |
| Quads per section (24,064 of 30,000) | Shafts are plain blocks and Minecraft's ladders; measured |
| Stuck in a shaft (a refused warp, a crash) | The fallback teleport to the hidden player; a relink works as today |
| Climbing feels slow | About 5 s up a ladder, 1 s down a hole. Shorter needs less rock between floors or a smaller habitat range |
| A turning trainer spots the waiting hidden player | You're put back with the hidden player, and the battle starts as usual |
| Wild Pokémon on rock tops | The rock fill (HD9), tested |
| Stairs with a low neighbour at their side fail the check | That map stays flat; the dry run counts them |

## 7. What changes for you

- Entering a cave is a fade over a long distance, up to 3 s, as Fly is.
- Inside, ladders and holes are real, except links that kept the fade.
- Raised ground with stairs, and cliffs you can't walk off.
- A new world after the milestones you want to see; at the latest, after H7.
- DESIGN.md:39-41 goes out of date (caves under mountains, ladders fade). That's yours to change.

## 8. Later (not in this plan)

- Raised ground in Viridian Forest and the Safari Zone (open sky, so there is room already), and outdoors (HR §3.6).
- Tunnels for links that can't line up; a guided climb as an option.
- Gen 3's bridges (two floors in one cell).
- Real stairs between building floors.
