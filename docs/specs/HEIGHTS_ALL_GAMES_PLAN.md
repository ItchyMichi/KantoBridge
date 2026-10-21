# 3D heights for the whole world, all three games (plan, 2026-10-20)

The user (2026-10-20): every game gets 3D heights across its whole world space, **interiors and exteriors**, in
Crystal and Emerald, and a check that Blue has them in both too. This work runs in its own session; the model work
(fitted shapes, buildings, warp models) is done first and stays as it is. This plan is what that session starts from:
what exists, the data each game gives, the design, the phases with their checks, and the questions for the user.

**Status (2026-10-20, later):** the user restated the vision before implementation (§0). The plan was checked against
it and changed where it fell short (§0.2); the survey W0 asked for, like the caves' dry runs, measured on the real
exports of all three games that the height changes are viable (§5). The user answered §8 the same day ("Q6 was a
typo, go with your picks for the rest"): the classic screen, thick walls in full, the bottom at −352, outdoor maps off
the surface under the sky, no tunnels. Execution started with W0's remaining checks.

## 0. The user's vision (2026-10-20)

### 0.1 What the user asked for

| Id | The user's words, in short |
|---|---|
| V1 | Areas at a different height from the lowest floor are raised, throughout the interiors and exteriors, in all three games |
| V2 | Stairs still work as warp points |
| V3 | Ladders and holes connect vertically to the right area of the next map with a real connection you can climb or fall through; whether they can is first established by inspecting the maps, as was done for Blue's caves, and the same is done for the other games |
| V4 | Today's heights cut what you see: the hidden game shows a few tiles of rock face above a door, Minecraft shows the door and nothing above it; some interiors' ceilings are too low |
| V5 | In every world space a **depth** is calculated from the walkable tiles and the longest chain of tiles up to the screen's edge |
| V6 | Interiors: at least **4 blocks of height above the highest floor**, in every interior (Blue was made "less 3D") |
| V7 | Exteriors: a **check** that every layer the hidden game shows is in the built world |
| V8 | Rebuilds are fine (no save to keep); confirm the height changes are viable before implementing, as for the caves |

### 0.2 How the plan was checked against it, and what changed

| | Before this check | Now |
|---|---|---|
| V1 | D3-D5 cover caves, interiors and outdoors; W5 and D5 allowed **one** raised level per interior | as many levels as the data gives (Q2's deeper world sized by the survey, §5.2) |
| V2 | D5 and Q4: stairs between maps stay a fade and warp | unchanged; also checked that no stairs warp is built as a shaft (D2) |
| V3 | D1-D2: ladders and holes in **caves** only; tunnels for cave mouths and wall exits | every complex joined by a ladder or hole (towers, gyms, wells too: D1); holes the export doesn't carry (scripted drops, cracked floors) added (D2); one map holding several floors (D1); tunnels dropped (not asked for); the inspection is the survey (§5.4) and each phase's dry run |
| V4, V7 | D4: "rock faces as tall as drawn", no check | D9: every visible row built, today's caps lifted, a builder check and a report |
| V5 | not in the plan | D8: the depth of every world space |
| V6 | not in the plan (rooms 3 blocks of air, Blue's caves too) | D5: ceiling at least the highest floor + 4, and at least the depth |
| Levels | D3 "2 blocks a level" against D3b "a cliff as tall as its drawn face" | one rule (D3): each step between two levels as tall as the faces drawn between them, at least 2 |
| W0 | "push the model session's commits" | done before this session (33bc8fb pushed) |

## 1. Where things stand

| | Caves | Other interiors | Outdoors, forests, Safari |
|---|---|---|---|
| Blue | 3D since 2026-10-13 (CAVES_3D_PLAN.md H1-H7): a cave area of its own, floors stacked 11 blocks apart, 15 of 37 ladders real shafts (22 fade: 10 loops, 10 moved aside, 2 same floor), 12 of 19 cave maps raised (3,462 cells, 29 stairs), 642 pair edges; 3 blocks of air | flat, 3 blocks of air | flat (CAVES_3D_PLAN.md §8 "later") |
| Crystal | flat interiors under their doors, every ladder and hole a fade; one-way edges as walls (1,535) | flat | flat ("Flat, as Hoenn: no heights or caves in 3D for Johto yet", GEN2_BRIDGE_PLAN.md:523); 11 outdoor maps off the surface, under the open sky (§5.5) |
| Emerald | flat, fades; walls where the elevation differs (`PairEdges.elevation`) | flat | flat ("flat with invisible walls on mismatches", GEN3_BRIDGE_PLAN.md:438); 18 outdoor maps off the surface, under the open sky (§5.5) |

**Heights today** (the code read for this check, 2026-10-20):
- Interiors: floor block at feet − 1, ceiling at feet + 3 (`WorldPlan.CEILING_ABOVE_FEET`): 3 blocks of air; interior
  walls 2 blocks, raised to 3 on a map's north, west and east edges (`InteriorWalls`); drawn rows past that go on the
  wall's top.
- Outdoors (Tier A, `Structures.measured`): a solid run stands at most `HeightRules.MAX_UNIT` = 6 rows (blocks); a
  repeat read (tree lines, and a cliff body under its rim) stands 2 unless its region votes higher; a roofed run shows
  only its eave rows and roof rows, dropping the middle when the run is taller than h + 1; at a **door fold** the face
  starts with the door's 2 rows, so with h = 2 the rows drawn above the door vanish (the user's rock face over a door);
  the cave-mouth arch model is at most 2 blocks; fitted buildings at most 30 blocks; hand models one cell (2 blocks).
- One tile row (8 px) is one block; a cell is 2 × 2 blocks (`Scale`, `HeightRules`). Buildings' own models use 1.5
  voxels a pixel row (BoxBuilding), approved by the user.
- The world: Y −256 to 319 (`data/minecraft/dimension_type/overworld.json`); 14 interior layers 22 apart per region
  (`Scale.LAYER_STEP`), the same layout under Kanto, Hoenn and Johto; only Blue has a cave area.

What the model session added that this work builds on (2026-10-20):
- **Warp models** (WARP_MODELS.md): every warp-cell drawing of the three games classified by agents (541 drawings:
  stairs up/down, ladder up/down, hole, wall exit, cave mouth, mat, pad, door, plain floor, with a facing;
  `kb-hub/work/warps/kinds.json`), and models for stairs, ladders, holes, exits and cave mouths on decor (no
  collision). Outdoor stairs were left flat on purpose: they are ground steps for this plan.
- `FitTool warps <bundle>`: every warp cell with its key, tags and neighbours, per game.
- Walkable cells can carry a shape on decor (`Units.walkUnit`, table entries marked `"walk"`).

## 2. The code that exists (Blue's caves)

- `builder/plan/CavePlanner.java`: picks caves (`caves()` :83, by `WorldPlanner.CAVE_TILESET = "CAVERN"` :57),
  groups them into complexes by warps, places each complex in a slot of the cave area (GAP 3,000 north of the
  dungeons, SLOT_GAP 64, ROW_WIDTH 2048), floors FLOOR_STEP = 11 apart, floor rank from the id (`_B(\d+)F$`, `_(\d+)F$`).
  Typed on Gen 1's `ExportMap`.
- `CaveRooms.java` (links and shafts from `ExportMap.Warp`, `CellFlags.HOLE`, warp tiles), `CavePieces.java` (rooms
  of a map, `of(ExportMap, sites)`), `CaveLevels.java` (raised ground from Gen 1's tile pairs and the profile),
  `Caves3d.java` (pilot switch), `PairEdges.java` (`of` tile pairs Gen 1, `walls` Gen 2 one-way bytes, `elevation`
  Gen 3), `world/MapLayout.java` (pieces, levels, ramps; MAX_LEVEL 64, MAX_RISE 8), blocks `ShaftLadderBlock`,
  `EdgeWallBlock`, `StepColliderBlock`.
- `world/WorldIndex.java`: `Shaft` (:362), `shaftAt` (:593), `toCell` (:653: footprint plus feet within
  LAYER_REACH_DOWN 4 / UP 6, or PIECE_REACH 3 / 5 for layout pieces), `rampLift` (:710), `levelOrigin` (:752).
- Client: `Spot` keeps the piece's own feet level (`levelOrigin`), so a ramp is not a level change; a refused step
  snaps back (`MovementSync`); inside a shaft the player is in no cell. Climbing a real ladder queues the engine's warp
  (`act/kb_climb.lua`): **not yet checked against the Gen 2 and Gen 3 adapters**. Read for this check: both adapters
  take the same queued `warp` row (map, cell, facing); on Gen 3 the engine's `Warp.request` announces `player.warped`
  itself, so the take's `re` is likely lost (`observe/kb_warp.lua`); W3 fixes and tests it.
- Gen 2 and Gen 3 never reach this code: `WorldPlanner` hands their exports to `Gen2Planner` / `Gen3Planner` first
  (WorldPlanner.java:94, 98), which place interiors breadth-first under their doors (`InteriorPacker`), caves included
  (cave biome only), with `layout = null`.

## 3. The height data each game gives

| | Raised ground | Ledges, one-way | Warp kind | Notes |
|---|---|---|---|---|
| Blue | tile pairs (land, water: `ExportField.TilePairs`, kb_export.lua:61), the profile's raised pins (Dramaless only) | ledge flags and direction | warp tile (stairs, mats, ladders together), hole, pad: finer kinds only by tile id; now also the agents' kinds | no heights in the game: caves fake them with pairs |
| Crystal | **none exported** | ledges per collision byte; one-way sides 0xb0-b2, 0xc0-c2 (`G2Tiles.Meaning.sides`, → `PairEdges.walls`) | `G2Tiles.Meaning.warp`: door, stairs (ladder 0x72 and staircase 0x7a together), pad, hole, edge; cave 0x7b is "door" | heights must be inferred (cliff edges, one-way walls, stairs) or marked |
| Emerald | **per-cell elevation**, 4 bits, exported and parsed (`G3Map.elev[]`, export.lua:375-383); 0 = connects all, 15 = bridge (both) | ledges per collision byte | behaviours per metatile (`G3Tiles.behavior`, MB_* names: ladders, stairs, escalators, fall warps), unused outside encounters | the game's own heights; two-floor bridges (elev 15) |

Warp destinations are known in all three (`ExportMap.Warp`, `G2Map.Warp`, `G3Map.Warp`: cell, destination map,
arrival warp); none carries a facing (only the run-time arrival has one).

**Found by the survey (§5):**
- **Holes with no warp entry.** Blue's 9 hole drawings (Seafoam Islands 1F-B3F, 2 each; Victory Road 3F, 1) are warp
  tiles with no warp: the engine drops you by a script (Gen 1's dungeon warps), so the landing isn't exported.
  Emerald's cracked floors (behaviour 210, `MB_CRACKED_FLOOR`, unnamed in the export's table: Granite Cave B1F 62
  cells, Sky Pillar 2F 38 and 4F 34, Mirage Tower 2F 11 and 3F 3) and Sky Pillar Top's 84 `FALL_WARP` cells drop you
  where a map script's `setholewarp` says, not exported either. Gen 3 falls land on the same cell of the map below.
- **One map holding several floors.** Crystal's Victory Road (7 ladders), Mt. Moon (4), Diglett's Cave (4) and
  Dragon's Den (2) join parts of the same map; Ecruteak Gym's 30 pits lead back to the gym's own entrance.

## 4. Design

### D1. One 3D planner for every complex joined by a ladder or hole
- A game-neutral input instead of `ExportMap`: map id, size, cell kinds, the cells' keys, its warps (cell,
  destination, arrival, **kind**), its floor rank, and its raised-ground source. Each family fills it: Gen 1 from the
  export as today, Gen 2 and Gen 3 from their models.
- **Which maps (V3):** the caves (Gen 1 the CAVERN tileset as today, Gen 2 `G2Map.caveLike()`, Gen 3
  `G3Cells.cave()`, plus interiors whose door is in a cave, as HD1), **and every other interior a ladder or hole joins**,
  with the maps it leads to: the survey found Burned Tower, Olivine Lighthouse, Blackthorn Gym, Mt. Pyre, Lavaridge
  Gym and Sootopolis Gym (§5.4). They leave the layers for the 3D area as Blue's caves did (their doors become long
  fades, as caves' are). A ladder from the surface into a complex (Azalea Town's well) stays a fade: the entrance.
- **Floor rank:** the id patterns, plus Emerald's (`_1F_1R`, `_B1F_2R`, `MT_PYRE_1F..6F`, `SKY_PILLAR_1F..5F`) and
  Crystal's; where a name gives no rank, the rank follows the ladders and holes (a ladder down or a hole from floor r
  leads to r + 1 below), starting from the entrance. **Ranks per room, not per map**, where one map holds several floors
  (Crystal's Victory Road, Mt. Moon, Diglett's Cave, Dragon's Den): each room takes the floor its ladders give it.
- `Gen2Planner` and `Gen3Planner` hand these complexes to the 3D planner, as `WorldPlanner` does for Gen 1, so the
  area exists per region (Kanto slot 0, Hoenn 1, Johto 2; `RegionTable.PITCH` 8,192).
- **Floors apart by their height:** Blue's FLOOR_STEP 11 assumed 3 blocks of air; with D5/D8 each floor sits under the
  one above by the taller room's height + rock (a complex's floors from the survey: Seafoam 61 blocks deep in all,
  Tin Tower 99, Navel Rock 170-178).

### D2. Links between floors by their kind
- Kinds from the game data first (Gen 2 `Meaning.warp`, Gen 3 behaviours `LADDER` and the fall warps, Gen 1 tile ids),
  the agents' review (`kinds.json`) where the data merges kinds (Gen 2's ladder and staircase are one "stairs"; Gen 1's
  warp tiles).
- **Ladder:** a real shaft (as Blue: one cell, a Minecraft ladder, HD6/HD12). **Hole:** a one-way open shaft (HD7).
- **Holes with no warp entry** (§3): the export gains each one's landing from the engine (Gen 1's dungeon-warp table,
  Gen 3's `setholewarp` destinations and the `MB_CRACKED_FLOOR` name), a gen1-bridge export change and a re-export on
  the pod's test install. Emerald's cracked floors already line up with their landings (§5.4): they become real drops
  where the floor cracks in the game (the story site's open state), at the same cell below.
- **Stairs, doors, cave mouths, wall exits, mats and pads stay warps with their fade (V2).** No tunnels (the earlier
  draft's tunnels for cave mouths are dropped). A stairs warp is never built as a shaft: the kind decides, not "a warp
  tile that isn't a door" (CaveRooms today; Blue's cave links are all ladders, so nothing changes there).
- A link that can't line up (a loop that doesn't close, a room moved aside, one going only up, a hole leading up)
  keeps its fade, counted by reason in the build report, as in Blue.
- The warp models stay where a link keeps its fade (they show the way); where a real shaft replaces the warp, the
  shaft's ladder replaces the model.

### D3. Raised ground per game
- **Emerald:** levels from the elevation: per map, the distinct walkable elevations ordered (1-14; 0 joins any,
  15 is a bridge). A step where neighbours differ and the game lets you walk between them (an elevation-0 cell between
  them: stairs, a ramp: `StepColliderBlock`), a wall where it doesn't (the existing `PairEdges.elevation` walls stay as
  the check). The builder check stays: Minecraft must allow exactly the game's steps, else the map stays flat and is
  reported.
- **Crystal:** no data, so two sources: (a) inferred: regions bounded by one-way walls, cliff collision rows and
  ledges, ordered by the ledge directions (a ledge drops from high to low) and stairs tiles; (b) where (a) can't
  decide, the agents' drawing labels (D3b). A dry run lists every map with what each source found.
- **Blue:** the tile pairs as today, extended past caves (D4).
- **How tall a step is (one rule):** each step between two levels is as tall as the faces drawn between them, one
  block a tile row: the most common face between those two levels in that map, at least 2 blocks (so Minecraft's
  0.6-block step can never climb it). A face drawn taller than its step stands up past the upper floor as a ridge (its
  cells are solid, so nothing walks on it); a shorter one is filled down to the lower floor. Blue's caves (faces of one
  cell) keep their 2 blocks.

**Found in W4 (2026-10-21): an elevation is a layer, not a height.** Emerald gives two walkable cells different
elevations to keep them apart, not to say how high they are: Victory Road climbs five tiers with elevations 3 and 4
only, and its stairs from 4 down to 3 are two cells long in some places and three in others; Meteor Falls B1F's 5s are
one stair cell over its 3s and its 4s. So the heights are per **region** (the floor cells of one elevation that touch),
from the stairs between regions: each stair cell climbs 2 blocks (it cuts through a cell of the face, two tile rows),
the higher elevation at the top. Regions no stairs join take their height from a short face (up to 3 solid cells
straight between them, 2 blocks a cell), else the height their elevation has elsewhere in the map. Rock stands on the
floor south of its run of solid cells (a face rises from its lower floor) and the builder fills under it. The rule is
`RaisedRegions`; any map where Minecraft's steps wouldn't match the game's stays flat with its reason (bent stairs, two
stairs of different lengths between one pair of regions, a bridge until the two-floor cells of W6). Of Emerald's 102
caves, 15 have more than one walkable elevation; Pokémon Centres' single elevation-4 cell is the escalator's.

### D3b. Calculated, not estimated (the user's choice, 2026-10-20)
- The levels and heights are **calculated** from the game data (Emerald's elevation, Blue's tile pairs, ledge
  directions, Crystal's one-way edges) and from the drawings (D3's faces, D8's depth), never estimated map by map: the
  same answer every build, matching edges between neighbouring maps, and checkable (the builder check reports any map
  Minecraft can't step like the game).
- Agents only **label tile drawings**, once per drawing, not per map (as the 541 warp drawings were): cliff face,
  cliff top, ground, stairs, ledge, water edge, a few hundred per game; the user reviews the labels on kb-hub. And
  they settle the leftovers: Crystal areas the edges and ledges can't order, a few maps marked as before.

### D4. Outdoors
- Open sky, so room is not the limit. Levels on routes and in towns: Emerald from the elevation (Route 111's
  ledges, Fortree's treetops, Mt. Chimney, Jagged Pass, Route 119/120's raised paths, Sootopolis's rim; the survey's
  Mossdeep has 6 levels, Routes 114 and 120 and Ever Grande 4), Crystal and Blue from ledges and cliff edges
  (HEIGHTS_RESEARCH.md §3.6: islands behind one-way ledges, places the user names).
- Faces as tall as the game shows them: D9.
- Ledges become real drops: the low side one level down, a one-way step collider (the game's hop), the high side's
  edge a wall from below (`CollisionGameTests.ledgesBlockOnlyFromBelow` exists).
- Outdoor stairs (the warp review left them flat) become ramps or steps between the levels they join.
- **Bridges (the user chose two-floor cells, 2026-10-20):** a cell is both the bridge and the ground under it, so the
  player walks on the bridge and under it. Which cells are bridges comes from the data (Emerald's elevation 15: 381
  cells on 10 maps, Route 110's Cycling Road 185 of them) and, where the data can't tell (Crystal, Blue, Emerald
  bridges at elevation 0), from an agent review of every map with a bridge (the user's suggestion): per map, the bridge
  cells, the floor under them and where the bridge meets the ground, reviewed by the user on kb-hub. A cell holds two
  floors (the ground and the deck a level above, with at least 2 blocks clear under the deck), and the position sync
  takes the floor from the game's own state (Emerald's elevation; for the others the side the player stepped on from).
- **Outdoor maps off the surface keep the sky:** 11 Crystal maps (Tin Tower's roof, Routes 23 and 28, the National
  Park, the ports, Mt. Moon Square, the Battle Tower's outside, Silver Cave's outside, the Ruins of Alph's outside) and
  18 Emerald maps (Sootopolis, both Battle Frontier outsides, the Safari Zone's six, Mt. Chimney, Jagged Pass, Mt. Pyre's
  exterior and summit, Petalburg Woods, Sky Pillar's outside and top, Southern Island) are drawn outdoors and placed
  under the open sky by the packer (`placeOpenSky`, note `open_air_sky`) when the build plans shapes, as every real build
  does. They are exteriors for D9 (no ceiling, out of the layer stack's depth); W5 keeps them under the sky with the new
  layer steps, and the build report counts any that fall back to a ceiling (`open_air_kept_ceiling`): the goal is none.
  *Corrected 2026-10-20: the first survey planned without shapes, which skips the sky, and read them as roofed.*
- Viridian Forest, Ilex Forest, Petalburg Woods, the Safari Zones: raised ground where the data has it; the canopy
  and apron dressing (first-person dressing work) must follow the levels.

### D5. Interiors
- **Headroom (V6):** every interior's ceiling stands at least 4 blocks above its highest floor, and at least as high
  as its depth (D8), whichever is higher: `ceiling = max(depth, highest floor + 4)`, in blocks over the room's lowest
  floor. Houses, shops and gyms get one flat ceiling at that height; caves keep a ceiling that follows the floor (cave
  rock), never under 4 blocks over any floor and up to the depth. Walls stand up to the ceiling, the drawn rows from the
  floor up, plain wall above the drawing.
- **Raised platforms and stages** (Lavaridge Gym, Mossdeep Gym, the Pokémon League rooms, Battle Frontier halls,
  Celadon roof, Goldenrod's Radio Tower): Emerald from the elevation (64 interiors have 2+ levels), Crystal/Blue from
  the drawing labels; as many levels as the data gives.
- **Stairs (the user, 2026-10-20):** inside one map with floors at two heights, the stairs the map draws are
  physical stairs between them (steps the player walks up, no warp). Going to another map (a house's 1F to 2F, a
  floor of a tower to the next) stays a fade and warp, with the stair model at the door.
- **A deeper world (the user: "deeper world if possible"):** the layers stay 14 per region, placed by the packer as
  today, but each layer is as deep as its tallest room needs: `step = tallest ceiling + 2 (floor and ceiling blocks) +
  16 (DESIGN.md:40's separation)` instead of a fixed 22. The survey (§5.2) puts the deepest layer's floor at Y −299
  (Johto), −301 (Kanto) and −319 to −346 (Hoenn), so the world's bottom moves from −256 to **−352** (a multiple of 16;
  Minecraft allows down to −2,032). That touches the dimension override, `Scale` (layers become a table per region in
  the WorldIndex, where `layerStep` already lives), the spawn rule's hard-coded `(y − 63) mod 22`, the goldens and the
  tests that assume 22 or −256 (§9), and PLAN P4. The layer layout change is the user's go (VOXEL_BLOCKS_PLAN.md §5.12):
  given in principle by Q2; the size above is for the user to confirm (§8).

### D6. Shape budget and slots first
- The shape documents are offered per region and per document with a budget of 64 parts each since the followups'
  item 4 (SEVERAL_GAMES_FOLLOWUPS.md); the old 97% figure was the single-world budget. Taller faces and levels add
  shape keys: measured after each phase. Block families: 112 (Kanto 0-15, Hoenn 16-95, Johto 96-111): new blocks
  (none planned) would need room.

### D7. What changes for the models
- Fitted units and buildings stand on the cell's own floor level (the planner's `floor` per cell): with levels, each
  unit and building takes its cell's level (one level per building footprint; a building spanning two levels is
  reported and stays on the lower). The warp models for stairs between levels are replaced by the real steps.
- Hand models are capped at one cell high today (HAND_MODELS.md): with taller rooms, the tall ones (big machines,
  statues) can use their layers ("up"): revisit per model after W5.

### D8. Depth: how tall each world space is drawn (V5)
- **The chain:** from every floor cell (walkable or water), the solid cells straight up the screen (north) until an
  open cell or the map's edge (indoors: past the edge is the game's border, not the room; outdoors the joined surface
  goes on past a seam), stopping at the **screen's edge**: the rows the hidden game shows above the player standing on
  that cell. One tile row is one block, so a chain of n cells is 2n blocks. A door cell adds its own two rows. Cells the
  game draws as one flat colour (the black filler round a room) are not drawing and end the chain.
- **The screen** (survey of Gen1Recomp, 2026-10-20): the classic screen (what the mirror shows: Gen 1 and 2 show 4
  cells above the player, 8 rows; Gen 3 4.5 cells, 9 rows) or Gen1Recomp's default window (1024 × 768 at its FIT zoom:
  Gen 1 and 2 4.3 cells, still 8 whole rows; Gen 3 5.5 cells, 11 rows). Proposed: the classic screen (fixed by the game,
  not by window size or zoom); the user decides (§8).
- **The depth of a space** = the highest of (a floor's level + its chain), over its floors. Interiors take it as the
  ceiling's lower bound (D5); outdoors it is the height D9 checks against, face by face.
- Thick walls drawn from above (Silph Co.'s partitions, a gym's maze, a cave's rock between corridors) are chains like
  any other: they stand as tall as the screen shows them, and the room is that tall (§5.1).

### D9. Every visible layer built (V4, V7)
- **Outdoors**, per floor cell: the rows its chain shows (D8) against the built column in front of it. A **face**
  (cliff, rock face, wall, a building's facade without a fitted model) stands as tall as its visible rows. A
  **repeat** (a tree line, a fence: the same art every one or two rows) is rows of separate things, each its own
  2-row unit: two rows of it are enough. Today's caps are lifted for faces: `MAX_UNIT` 6 → the screen's rows; the door
  fold keeps the door's rows **and** the rows above it; a cliff body under its rim stands as tall as drawn; a roofed run
  keeps its middle rows; the cave-mouth and wall-exit models sit in a face that rises past them (the user's Battle
  Frontier mouth).
- **Indoors:** walls as D5 says, every drawn row standing from the floor.
- **The check:** the builder compares, for every floor cell of every map, the rows the hidden game shows (D8) with what
  it built (Tier A columns, fitted buildings' and units' heights), and reports per map the cells short and by how
  many rows (`visible_rows_missing`); a JUnit test on synthetic faces (a door under 3 rows of rock, a 7-row cliff, a
  tree line); the dry runs report it per phase. Goal: none short, or a reason per map.
- Survey of today's build (Tier A only, fitted buildings not counted): outdoor faces short of rows on 35 of 36 Kanto
  maps (998 of 2,877 face cells), 63 of 66 Johto maps (1,980 of 5,424) and all 49 Hoenn maps (3,385 of 9,221); doors
  with rows lost above them: 43, 47 and 83.

## 5. Viability (the survey, 2026-10-20)

**How:** `HeightsSurveyTest` (`-Pkb.heightsSurvey=true` with the three real bundles; pod kb-dev43 then kb-dev42, about
15 minutes in all) plans each real world as a build does and writes per map its placement, cell kinds and flags,
walls, Blue's cave levels, Emerald's elevations and behaviours, Crystal's warp kinds and one-way sides, warps with their
arrival cells, the measured runs and a flat-colour flag per cell (the only thing read from the art). The reports are
ROM-derived layout data: they stay in the user's kb-hub work folder (`work/heights/`), never in the repo; the analysis
(`work/heights/analyze.py`) prints counts and map ids only. Its copy of `CaveRooms`' layout reproduces Blue's real
result exactly (87 rooms, 15 shafts, 10 loops, 10 moved aside), so its Crystal and Emerald figures are the same
algorithm's.

### 5.1 Interiors' ceilings (D5, D8), blocks of air over the lowest floor

| | Rooms | 4 | 6 | 8 | 9-11 | taller | Today |
|---|---|---|---|---|---|---|---|
| Blue (houses, shops, gyms; caves apart) | 165 | 61 | 30 | 74 | | | 3 |
| Blue caves (rock chains + a raised level) | 19 | 3 | | 4 | 12 at 10 | | 3 |
| Crystal (no caves, no outdoor maps) | 228 | 96 | 33 | 99 | | | 3 |
| Emerald, classic screen (no caves, no outdoor maps) | 329 | 86 | 59 | 50 | 132 at 9, 2 at 11 | | 3 |
| Emerald, default window | 329 | 86 | 59 | 50 | 57 at 10, 75 at 11 | 2 at 13 | 3 |

Emerald's caves and outdoor maps with several levels are the tallest spaces (Meteor Falls B1F 1R 21 blocks, Victory
Road B2F and Shoal Cave 15, Battle Frontier east 17): they are in the 3D area or under the sky (D1, D4).

### 5.2 The layers (D5)
With each layer as deep as its tallest room plus 18 (D5), the 14 layers' bottom: **Kanto −301, Johto −299, Hoenn −319
(classic screen) or −346 (window)**; with caves and outdoor maps still in the layers Hoenn would need −371 to −398.
Proposed world bottom: **−352**. Today: −256, every room 3 blocks.

### 5.3 Outdoors (D3, D4, D9)
- Open sky: the tallest outdoor face over its ground plus levels is 33 blocks (Mossdeep, 6 levels); the surface volume's
  top (ground + 32 today, `WorldPlan.SURFACE_MAX_Y`) goes to ground + 48.
- Emerald's elevations: 381 maps with one walkable level, 78 with 2, 7 with 3, 3 with 4, Mossdeep 6; cells at
  elevation 0 (stairs and transitions) 13,348; bridges 381 cells on 10 maps. The faces drawn between two levels: mostly
  2-8 rows (2 rows 1,180 times, 4 rows 664, 6 rows 366), up to 32.

### 5.4 Ladders and holes (D1, D2): the inspection, as for Blue

| | Complexes (maps) | Down links | Real ladders | Real holes | Fades by reason |
|---|---|---|---|---|---|
| Blue | 6 (19) | 37 | 17 (15 today; Diglett's Cave's 2 entrance ladders with ranks from the ladders) | 0 (9 scripted holes: data first, D2) | 10 loop, 10 moved aside |
| Crystal | 25 (84) | 62 | 15 | 18 (Olivine Lighthouse all 12, Blackthorn Gym 2, Ruins of Alph 2, Burned Tower 1, Will's room 1: to check) | 24 loop, 5 moved aside, 13 same floor, 47 within one map (17 ladders: D1's ranks per room; Ecruteak Gym's 30 traps stay fades), 9 off the floor or a hole leading up |
| Emerald | 31 (112) | 77 | 37 (Navel Rock 19 of 19, Granite Cave 5 of 7, Mirage Tower 3 of 3, Victory Road 3) | 26 (Lavaridge Gym 20, Mt. Pyre 6) + the cracked floors | 8 loop, 6 moved aside, 2 one-way up, 14 holes leading up (Lavaridge's geysers), 2 off the floor |

- **Pilots (Q5):** Union Cave 2 of its 3 ladders real (1 loop); Granite Cave 5 of 7 ladders, and all 62 cracked-floor
  cells of B1F land on walkable cells of B2F directly below once the floors are stacked for the ladders. The same holds
  for Sky Pillar 2F (38) and 4F (34) and Mirage Tower 2F (11) and 3F (3): Gen 3 draws them aligned.
- Non-cave maps that join the 3D area: Crystal's Blackthorn Gym (2 maps), Ecruteak Gym, the Indigo Plateau room and
  Will's room (a hole drawing to check in W2); Emerald's Lavaridge Gym, Mt. Pyre 1F-6F and Sootopolis Gym.

### 5.5 Outdoor maps off the surface
11 in Johto and 18 in Hoenn (D4's list), under the open sky in a real build; under a ceiling they would need 11-19
blocks. (The survey planned without shapes, which skips the sky: §5.2's figures leave them out of the stack, as a real
build does.)

### 5.6 Verdict
Viable in all three games with the changes above: the deeper world (−352), the 3D area for every ladder or hole
complex, outdoor maps off the surface kept under the sky, the export's landings for scripted holes, and per-room floors
for maps holding several. Not settled by data: Crystal's raised ground (no heights in the game; W4's dry run
measures how much the edges and labels decide) and Blue's outdoor islands (W1).

## 6. Phases

Each phase ends with its tests green, a dry-run report and pictures where it shows; pushes on the user's go.

| Phase | Work | Check |
|---|---|---|
| W0 | **Done as far as it goes:** the survey (§5). Left: the full check (`tools/check.sh`) as the baseline; the climb (`kb_climb.lua`) against the Gen 2 and Gen 3 adapters (the Gen 3 `re`); the layer sizes the user confirms | full check green; a note on the climb |
| W1 | **Blue audit**: every Blue map the game draws raised but built flat (outdoors, forests, Safari, interiors with platforms), from the tile pairs, ledges and the profile pins; Blue's 9 scripted holes; faces short of rows (D9); a report for the user | report on kb-hub |
| W2 | One 3D planner (D1): the neutral input, every ladder or hole complex of each game, floor ranks per room, the area per region; floors apart by their height; links still fade | dry runs per game (rooms, complexes, slots, floors), Blue's caves unchanged (87 rooms, 15+ shafts) |
| W3 | Links by kind (D2): ladders as shafts, holes, the export's landings for scripted holes and cracked floors; pilots Union Cave (Crystal) and Granite Cave (Emerald) | dry-run counts (real / fade and why, as §5.4), a pilot video each, GameTests: climbing a Gen 2/3 shaft warps; Lua tests for the take on both adapters |
| W4 | Raised ground in caves: Emerald from the elevation, Crystal inferred plus labels; D3's step heights | per-map report (raised, flat and why), RaisedCave-style tests per family, pictures |
| W5 | Interiors: the depth (D8), the headroom (D5), walls standing to the ceiling, levels from the same sources; the deeper world (variable layer steps, the bottom at −352, the spawn rule); outdoor maps off the surface kept under the sky | report (ceilings by room, layer steps), pictures of Lavaridge Gym, the League rooms, a Crystal gym, a house per game |
| W6 | Outdoors: levels, ledges as drops, outdoor stairs as steps, bridges as two-floor cells (after the bridge review), faces to their visible rows and the visible-layers check (D9) | report per region (cells short: none or a reason), CollisionGameTests for drops, pictures of Route 111, Fortree, Route 119, Ilex Forest, a Kanto route, the Battle Frontier mouth |
| W7 | Blue's audit list built (W1) | the audit report re-run: nothing left flat that the game raises, or a reason per map |
| W8 | Every region rebuilt; the play-test list (PLAYTEST.md "Caves in 3D" extended); docs: this plan's status, DESIGN.md (the user's file: a proposed text for :36-42) | full check, videos per region |

## 7. The user's answers (2026-10-20), checked against the vision

| | Answer | Agrees with the vision? |
|---|---|---|
| Q1 | **Method:** calculate (D3b); agents label drawings and settle leftovers only | yes: the depth (D8), the levels (D3) and the visible-layers check (D9) are all calculated; Crystal's heights need the labels |
| Q2 | **Layer layout:** a deeper world, if possible (D5, sized in W0) | yes, and needed: 4 blocks over the highest floor plus the depth make rooms 4-11 blocks; the survey sizes it at −352 (§5.2) |
| Q3 | **Bridges:** two-floor cells, with an agent review of the maps with bridges (D4) | yes: Emerald's 381 bridge cells; the deck keeps 2 blocks clear under it |
| Q4 | **Stairs:** within one map with floors at two heights, physical stairs where the map shows them; moving to another map stays a fade and warp (D5) | yes: V2 ("stairs still function as warp points") is the second half; the first half is stairs inside one map, which are no warp in the game |
| Q5 | **Pilots:** Union Cave and Granite Cave | yes: both have ladders that line up (§5.4), Granite Cave also holes (its cracked floors) |
| Q6 | a typo (the user, 2026-10-20): there are five | — |

## 8. The user's answers to the vision check (2026-10-20: "Q6 was a typo, go with your picks for the rest")

Each point below was decided as proposed.

1. **The depth's screen:** the classic screen (Gen 3 rooms up to 9 blocks) or Gen1Recomp's default window (up to 11)?
   Proposed: classic.
2. **Thick walls count in full:** a room is as tall as its tallest drawn chain up to the screen's edge, so gyms, Silph
   Co.'s floors and most caves become 8 blocks (Gen 1/2) or 9 (Gen 3) tall, houses 4-6 (§5.1). Proposed: yes, as asked.
3. **The world's bottom to −352** (from −256), each layer as deep as its tallest room. Proposed: yes.
4. **Outdoor maps off the surface under the sky** (29 maps, D4). Proposed: yes. *They already are in a real build (the
   survey's reading was wrong, corrected the same day): the decision keeps it so.*
5. **Tunnels dropped**: cave mouths and wall exits keep their fade; links that can't line up keep theirs, as in Blue.
   Proposed: yes.
6. **Q6:** a typo; there are five questions.

## 9. Risks

- Shape budget (D6) and the shape map's wire size: measure after each phase.
- Position sync: more levels per map stress `toCell`'s reach; every level change must stay within the piece reach or
  be a piece of its own (as Blue's pieces).
- Gen 2/3 climb support in the hidden-game adapters (W0, W3: the Gen 3 take's `re`).
- Elevation noise in Emerald (cells marked 0 or 15 inside a level): the builder check reports instead of guessing.
- Dressing (canopies, aprons, rock roofs) and the fitted units assume one floor per map: each needs its level.
- Taller rooms and faces mean more blocks and quads per section: the dry runs report quads per section against the
  budgets (12,000 p95, 30,000 max) per phase.
- The deeper world touches many tests and goldens that assume 22 and −256 (the layer layout survey of 2026-10-20 lists
  them: `InteriorPackerTest`, `SyntheticPlanTest`, `Gen2PlanTest`, `Gen3PlanTest`, `CavePlannerTest`, `WorldIndexTest`,
  `ShapeMapTest`, `PuppetCullingTest`, the fixtures at y 42 and 20, `TestData.MIN_BUILD_HEIGHT`, `StaticDataTests`, the
  three synthetic goldens and the Python golden tests): W5 updates them in one commit.
