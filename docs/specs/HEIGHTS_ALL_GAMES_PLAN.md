# 3D heights for the whole world, all three games (plan, 2026-10-20)

The user (2026-10-20): every game gets 3D heights across its whole world space, **interiors and exteriors**, in
Crystal and Emerald, and a check that Blue has them in both too. This work runs in its own session; the model work
(fitted shapes, buildings, warp models) is done first and stays as it is. This plan is what that session starts from:
what exists, the data each game gives, the design, the phases with their checks, and the questions for the user.

## 1. Where things stand

| | Caves | Other interiors | Outdoors, forests, Safari |
|---|---|---|---|
| Blue | 3D since 2026-10-13 (CAVES_3D_PLAN.md H1-H7): a cave area of its own, floors stacked 11 blocks apart, 15 of 37 ladders real shafts (22 fade: 10 loops, 10 moved aside, 2 same floor), 12 of 19 cave maps raised (3,462 cells, 29 stairs), 642 pair edges | flat | flat (CAVES_3D_PLAN.md §8 "later") |
| Crystal | flat interiors under their doors, every ladder and hole a fade; one-way edges as walls (1,535) | flat | flat ("Flat, as Hoenn: no heights or caves in 3D for Johto yet", GEN2_BRIDGE_PLAN.md:523) |
| Emerald | flat, fades; walls where the elevation differs (`PairEdges.elevation`) | flat | flat ("flat with invisible walls on mismatches", GEN3_BRIDGE_PLAN.md:438) |

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
  (`act/kb_climb.lua`): **not yet checked against the Gen 2 and Gen 3 adapters**.
- Gen 2 and Gen 3 never reach this code: `WorldPlanner` hands their exports to `Gen2Planner` / `Gen3Planner` first
  (WorldPlanner.java:94, 98), which place interiors breadth-first under their doors (`InteriorPacker`), caves included
  (cave biome only), with `layout = null`.

## 3. The height data each game gives

| | Raised ground | Ledges, one-way | Warp kind | Notes |
|---|---|---|---|---|
| Blue | tile pairs (land, water: `ExportField.TilePairs`, kb_export.lua:61), the profile's raised pins (Dramaless only) | ledge flags and direction | warp tile (stairs, mats, ladders together), hole, pad: finer kinds only by tile id; now also the agents' kinds | no heights in the game: caves fake them with pairs |
| Crystal | **none exported** | ledges per collision byte; one-way sides 0xb0-b2, 0xc0-c2 (`G2Tiles.Meaning.sides`, → `PairEdges.walls`) | `G2Tiles.Meaning.warp`: door, stairs (ladder 0x72 and staircase 0x7a together), pad, hole, edge; cave 0x7b is "door" | heights must be inferred (cliff edges, one-way walls, stairs) or marked |
| Emerald | **per-cell elevation**, 4 bits, exported and parsed (`G3Map.elev[]`, export.lua:375-383); 0 = connects all, 15 = bridge (both) | ledges per collision byte | behaviours per metatile (`G3Tiles.behavior`, MB_* names: ladders, stairs, escalators, holes), unused outside encounters | the game's own heights; two-floor bridges (elev 15) |

Warp destinations are known in all three (`ExportMap.Warp`, `G2Map.Warp`, `G3Map.Warp`: cell, destination map,
arrival warp); none carries a facing (only the run-time arrival has one).

## 4. Design

### D1. One cave planner for all games
- A game-neutral cave input instead of `ExportMap`: map id, size, cell kinds, the cells' keys, its warps (cell,
  destination, arrival, **kind**), its floor rank, and its raised-ground source. Each family fills it: Gen 1 from the
  export as today, Gen 2 and Gen 3 from their models.
- Which maps are caves: Gen 1 the CAVERN tileset (as today); Gen 2 `G2Map.caveLike()`; Gen 3 `G3Cells.cave()`, plus
  interiors whose door is in a cave (as HD1).
- Floor rank: the id patterns, plus Emerald's (`_1F_1R`, `_B1F_2R`, `MT_PYRE_1F..6F`, `SKY_PILLAR_1F..5F`, Seafloor
  Cavern rooms by warp depth from the entrance) and Crystal's (`UNION_CAVE_B1F/B2F`, `MT_SILVER_*`, `DARK_CAVE_*`);
  where a name gives no rank, the rank follows the warp kind (a ladder down from floor r leads to r - 1).
- `Gen2Planner` and `Gen3Planner` hand their caves to the cave planner, as `WorldPlanner` does for Gen 1, so the
  cave area exists per region (Kanto slot 0, Hoenn 1, Johto 2; `RegionTable.PITCH` 8,192).

### D2. Links between floors by their kind
- Kinds from the game data first (Gen 2 `Meaning.warp`, Gen 3 behaviours, Gen 1 tile ids), the agents' review
  (`kinds.json`) where the data merges kinds (Gen 2's ladder and staircase are one "stairs"; Gen 1's warp tiles).
- Ladder: a real shaft (as Blue: one cell, a Minecraft ladder, HD6/HD12). Hole: a one-way open shaft (HD7). Cave
  mouth and wall exit between caves: a tunnel where the rooms line up, else the fade. Stairs inside a building between
  its floors: see D5.
- The warp models stay where a link keeps its fade (they show the way); where a real shaft replaces the warp, the
  shaft's ladder replaces the model.

### D3. Raised ground per game
- **Emerald:** levels from the elevation: per map, the distinct walkable elevations ordered (1-14; 0 joins any,
  15 is a bridge), each a level 2 blocks above the one below (U1); a step where neighbours differ by one (stairs:
  `StepColliderBlock`), a wall where they differ by more or where the game has no step (the existing
  `PairEdges.elevation` walls stay as the check). The builder check stays: Minecraft must allow exactly the game's
  steps, else the map stays flat and is reported.
- **Crystal:** no data, so two sources: (a) inferred: regions bounded by one-way walls, cliff collision rows and
  ledges, ordered by the ledge directions (a ledge drops from high to low) and stairs tiles; (b) where (a) can't
  decide, a marking step: an agent per map marks "raised areas" and their level on the kb-hub pictures (a new
  marking kind, MAP_MARKING_RULES_CRYSTAL.md), reviewed by the user like the other marks. A dry run lists every map
  with what each source found.
- **Blue:** the tile pairs as today, extended past caves (D4).

### D4. Outdoors
- Open sky, so room is not the limit. Levels on routes and in towns: Emerald from the elevation (Route 111's
  ledges, Fortree's treetops, Mt. Chimney, Jagged Pass, Route 119/120's raised paths, Sootopolis's rim), Crystal and
  Blue from ledges and cliff edges (HEIGHTS_RESEARCH.md §3.6: islands behind one-way ledges, places the user names).
- Rock faces and cliffs as tall as the game draws them (the user, 2026-10-20: Battle Frontier's cave mouth, where the
  rock should go on two blocks above the entrance; today the face stops at the arch's top): a cliff's height from
  the rows of face the drawing shows (as buildings take 1.5 voxels a row), its top the level above; a cave mouth's
  or wall exit's model then sits in a face that rises past it.
- Ledges become real drops: the low side one level down, a one-way step collider (the game's hop), the high side's
  edge a wall from below (`CollisionGameTests.ledgesBlockOnlyFromBelow` exists).
- Outdoor stairs (the warp review left them flat) become ramps or steps between the levels they join.
- Emerald's bridges (elevation 15: Route 119, 120, Fortree's walkways): a cell is both the bridge and the ground
  under it. One floor per cell today, so either (a) the bridge is decor over the lower floor (walked on at the upper
  level only where the game is on the bridge) or (b) the later two-floor cell (HEIGHTS_RESEARCH.md: no bridges).
  Proposed: (a) first; (b) only if the user wants walking under bridges.
- Viridian Forest, Ilex Forest, Petalburg Woods, the Safari Zones: raised ground where the data has it; the canopy
  and apron dressing (first-person dressing work) must follow the levels.

### D5. Interiors
- Raised platforms and stages (Lavaridge Gym, Mossdeep Gym, the Pokémon League rooms, Battle Frontier halls, Celadon
  roof, Goldenrod's Radio Tower): Emerald from the elevation, Crystal/Blue from marks. One raised level fits the gap
  above an interior today (HEIGHTS_RESEARCH.md §5.2: layer step 22 = a 5-block room plus a 17-block gap; raised
  ground only goes up).
- Two-storey rooms and stairs between floors of one building (a house's 1F and 2F): real stairs need the floors
  stacked in line (as caves); proposed as a later phase, with the fade and the stair model until then.
- More than one raised level in an interior needs a deeper layer step or a lower world bottom (step 24 → bottom
  -288, 26 → -304). **The voxel plan says to ask before changing the layer layout** (VOXEL_BLOCKS_PLAN.md:1053):
  question Q2.

### D6. Shape budget and slots first
- The shape map is about 97% of its budget with the profile; raised ground adds shape keys. Send grids on demand
  (voxel plan R10, docs/SEVERAL_GAMES_FOLLOWUPS item 4 did it per region: confirm it covers this) before any phase
  that adds shapes. Block families: 112 (Kanto 0-15, Hoenn 16-95, Johto 96-111): new blocks (none planned) would
  need room.

### D7. What changes for the models
- Fitted units and buildings stand on the cell's own floor level (the planner's `floor` per cell): with levels, each
  unit and building takes its cell's level (one level per building footprint; a building spanning two levels is
  reported and stays on the lower). The warp models for stairs between levels are replaced by the real steps.

## 5. Phases

Each phase ends with its tests green, a dry-run report and pictures where it shows; pushes on the user's go.

| Phase | Work | Check |
|---|---|---|
| W0 | Baseline: the full check (`tools/check.sh`), push the model session's commits; confirm the shape grids go on demand per region; the climb (`kb_climb.lua`) against the Gen 2 and Gen 3 adapters | full check green; a note on the climb |
| W1 | **Blue audit**: every Blue map the game draws raised but built flat (outdoors, forests, Safari, interiors with platforms), from the tile pairs, ledges and the profile pins; a report for the user | report on kb-hub |
| W2 | One cave planner (D1): the neutral input, Gen 2/Gen 3 cave selection and floor ranks, the cave area per region; links still fade | dry runs per game (rooms, complexes, slots, floors per cave), existing cave tests unchanged |
| W3 | Links by kind (D2): ladders as shafts, holes, tunnels; pilot caves Union Cave (Crystal) and Granite Cave (Emerald) | dry-run counts (real / fade and why), a pilot video each, GameTests: climbing a Gen 2/3 shaft warps |
| W4 | Raised ground in caves: Emerald from the elevation, Crystal inferred plus marks | per-map report (raised, flat and why), RaisedCave-style tests per family, pictures |
| W5 | Interiors (D5): one raised level from the same sources | report, pictures of Lavaridge Gym, the League rooms, a Crystal gym |
| W6 | Outdoors (D4): levels, ledges as drops, outdoor stairs as steps, bridges as decor | report per region, CollisionGameTests for drops, pictures of Route 111, Fortree, Route 119, Ilex Forest, a Kanto route |
| W7 | Blue's audit list built (W1) | the audit report re-run: nothing left flat that the game raises, or a reason per map |
| W8 | Every region rebuilt; the play-test list (PLAYTEST.md "Caves in 3D" extended); docs: this plan's status, DESIGN.md (the user's file: a proposed text) | full check, videos per region |

## 6. Questions for the user (before W2)

1. **Crystal's heights:** infer from the edges and ledges and mark the rest by agents (proposed), or mark everything?
2. **Layer layout:** keep one raised level per interior and cave map (today's layout), or deepen the world for more
   (ask before changing it, VOXEL_BLOCKS_PLAN.md:1053)?
3. **Bridges:** decor over the ground first (proposed), or two-floor cells to walk under them?
4. **Stairs between a building's floors:** keep the fade with the stair model (proposed for now), or stack the floors
   in line for real stairs (a larger change: interiors move like the caves did)?
5. **Pilots:** Union Cave and Granite Cave (proposed), or others?

## 7. Risks

- Shape budget (D6) and the shape map's wire size: measure after each phase.
- Position sync: more levels per map stress `toCell`'s reach; every level change must stay within the piece reach or
  be a piece of its own (as Blue's pieces).
- Gen 2/3 climb support in the hidden-game adapters (W0).
- Elevation noise in Emerald (cells marked 0 or 15 inside a level): the builder check reports instead of guessing.
- Dressing (canopies, aprons, rock roofs) and the fitted units assume one floor per map: each needs its level.
