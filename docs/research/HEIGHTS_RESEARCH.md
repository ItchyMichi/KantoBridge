# Research: floors at different heights in Kanto's ROM world

- **Status:** research for your question of 2026-10-12: could the ROM world's floors sit at different heights (a cave
  whose stairs climb to raised ground, places outdoors where a height difference makes sense), how would it be built,
  and what would it change. Nothing is built. Decided so far: 2-block levels with 2 blocks of air on top, and caves
  in an area of their own (§7). The other choices are open.
- **Sources:** a read of today's code, docs and tests (file:line below; Java paths are under
  `fabric-bridge/src/{main,client}/java/dev/kantobridge/`), and the earlier research
  `docs/research/VOXEL_WORLD_REPORT.md` (VR). Nothing under `local/` and nothing ROM-derived was opened. Nothing ran on
  the pod and nothing cost money. Gen 1 facts marked *(general knowledge)* come from no file here; a dry run on your
  export would confirm them (§8).

## 0. Summary

- **Gen 1 has no heights.** Its caves fake raised ground with a rule: the engine refuses a step between certain
  pairs of floor tiles (the *tile pairs*), so only the stairs lead up. Outdoors, height is only drawn (cliffs) or
  implied by one-way ledges.
- **Today every walkable cell of a map is on one floor.** Raised cave floors are flattened on purpose, and nothing in
  Minecraft enforces the tile pairs: you can walk across a raised edge, and the hidden game then refuses the step and
  snaps you back.
- **The data is already there.** `field.tilePairs` comes over with every export but no Java reads it, and Dramaless's
  profile marks the raised floors and the stairs.
- **It is feasible** with a per-cell floor level in the world index, used everywhere a cell becomes a block position.
  The hidden game, gen1-bridge and the protocol don't change: they stay 2D.
- **What makes it safe:** Minecraft must allow exactly the steps the game allows. Jumping is off and Minecraft's
  step is 0.6 blocks, so a 1-block rise can't be walked up, stairs become half-block steps, and every drop the game
  forbids gets an invisible thin wall. The builder checks every edge between cells and keeps a map flat if any edge
  disagrees.
- **What it costs:**
  - it touches about a dozen areas: index format, movement, puppets, clicks, teleports, encounters, builder, shape
    planner, dressing, self-check, spawn rule, new collider blocks;
  - vertical room is tight. All 14 interior layers are used, and the design's 16-block separation leaves each cave
    room 1 spare block upward. That is enough for your choice of 2-block cliffs with 2 blocks of air on top; more
    needs a deeper world (§5.2);
  - the shape map is expected at about 94% with the profile (about 31k characters left);
  - every world needs a rebuild.
- **Recommended order:**
  - E1: groundwork, with no visible change;
  - E2: walls on the tile-pair edges, a fix on its own;
  - E3: levels in caves and the Safari Zone, piloted on one cave with a video;
  - E4: raised ground outdoors;
  - E5 (optional): walk-down shafts between stacked cave floors.

## 1. What the world does today

- **Scale:**
  - a cell is 2×2 blocks;
  - outdoor feet level is Y 64, with the ground blocks at 63;
  - interior layer *k* has its feet level at 64 − 22k (`world/Scale.java:21-33`).
- **One floor per map:** `MapPlacement.y` is the map's feet level (`world/MapPlacement.java:11`). The builder uses
  `feet = p.y()` for every cell (`builder/plan/WorldPlan.java:680`):
  - the floor block goes at feet − 1;
  - ledge colliders and story blocks go at feet;
  - interior lights go at feet + 2 and the ceiling at feet + 3 (`WorldPlan.java:42-46, 683-765`).
- **Shapes:** the shape planner measures every height from that one level. The grid's `baseY` is feet, and the
  ceiling sits at feet + 3 over the whole footprint (`builder/shape/ShapePlanner.java:195-233`). The heights you see
  are solid volumes standing on the floor:
  - walls and trees are 2 blocks, roofs and cliffs 4 (`builder/plan/HeightRules.java:16-25`);
  - measured runs are 1–6 blocks (`builder/shape/Structures.java:19-34`);
  - the profile's class heights apply under the headroom (`builder/shape/ShapeHeights.java:15-31`).
- **Raised floors are flattened on purpose.** The profile pins 16,318 walkable tiles, "mostly the ledge floors of
  caves and the Safari Zone". Dramaless raises them; here they stay flat because "collision decides the floor"
  (`docs/PROGRESS.md:544-547`).
  - The 82 rising stairs are drawn as four steps you walk through, with no collision (`builder/shape/Units.java:171-225`).
  - The 77 sunken stairs stay flat (`docs/specs/PROFILE_PLAN.md:280`).
- **Warps** (doors, stairs, ladders, holes) fade and teleport. The destination is `WorldIndex.toBlock`: the cell's
  centre at the map's feet level (`server/WarpService.java:97-119`; `world/WorldIndex.java:391-406`).
- **Interiors:** each one is placed with its arrival cell right under the door that leads to it, one layer down, when
  there is room (`builder/plan/WorldPlanner.java:388-404`). The real build used all 14 layers, plus spills deeper and
  sideways (VR:551-556).
- **Movement:**
  - jumping is off: jump strength is 0 (`server/Rules.java:104-106`);
  - Minecraft's step height is 0.6 blocks, and fall damage is off;
  - a ledge is a one-way, 1.5-block collider (`block/LedgeColliderBlock.java:32-33`).
- **Tile pairs** are exported (`gen1-bridge/export/kb_export.lua:53`) but never read: `ExportField.parse` reads four
  other tables (`builder/export/ExportField.java:52-83`). So on a flat cave floor:
  1. Minecraft lets you walk across a raised edge;
  2. the hidden game refuses the step;
  3. `MovementSync.onBlocked` snaps you back (`client/move/MovementSync.java:312`).

  This follows from the code; it hasn't been seen in game.

## 2. What the game data offers

- **The tile pairs** are a list of (tileset, tile a, tile b) whose cells may not be stepped between, plus a second list
  for surfing.
  - Only the cave and forest tilesets have entries *(general knowledge)*. The forest tileset is Viridian Forest's and
    the Safari Zone's.
  - Stairs are ordinary walkable tiles in no pair, so they join both floors *(general knowledge)*.
  - Exported as plain JSON (`kb_export.lua:53, 205-260`). The synthetic fixture's shape is
    `{"land":[{"tileset":"CAVERN","a":…,"b":…}],"water":[]}` (`tools/lua/export_world.py:268`).
  - Each cell's collision tile (its bottom-left 8×8 tile) is already worked out (`builder/plan/MapModel.java:251-253`).
- **Ledges:** each cell has a flag and a hop direction (`gen1-bridge/export/kb_cells.lua:128-142`).
- **The profile** exists only when Dramaless is installed. It has:
  - walkable tiles pinned to raised classes, which are read and then dropped (`builder/shape/ClassBoxes.java:18-21, 41-42`);
  - the classes `stair_e`, `stair_w` and `stair_down_*` (`scene/profile/Profile.java:30-66`).
- **Warp kind `stairs`** covers every non-door warp tile, ladders included (`gen1-bridge/lib/kb_engine.lua:1231-1276`).
- **What's missing is which side of a pair is the high one.** Three ways to tell:
  - the profile says so where it is installed;
  - otherwise a rule from the drawing: Game Boy art shows a cliff face south of higher ground, so across a face the
    north side is higher (this holds for pits too);
  - otherwise the map stays flat.
- **Later, Gen 3:** Gen1Recomp's Gen 3 engine stores an elevation per cell (VR:290, 6024-6036). The same per-cell level
  would carry it when FireRed, LeafGreen or Emerald comes (VR:9230, I3).

## 3. How it could work

### 3.1 The rule

Minecraft must allow exactly the moves the game allows. With jumping off and a 0.6-block step:

- a rise of 1 block or more can't be walked up, so the low side needs nothing;
- a rise of half a block can be walked up, which is how stairs climb;
- any drop can be walked off, so a drop the game forbids needs a wall on the high side.

The builder checks every edge between neighbouring cells against the game: walkable, tile pairs, ledges, water.
If any edge disagrees, that map stays flat and the build report gives a warning with counts. This can be tested in
JUnit on synthetic maps.

### 3.2 Levels in caves and the Safari Zone

Each walkable cell gets a level: blocks above the map's floor, where 0 is today. One level is 2 blocks (your choice;
1 block was the other option).

1. Find the collision tile of each cell. Two neighbours whose tiles form a pair have a *split* edge between them.
   That sorts the cells into side A (tiles on one side of the tileset's pairs), side B (the other) and neutral.
2. A neutral cell with an A cell on one side and a B cell on the opposite side is a stairs cell. The profile's stair
   classes confirm it where installed.
3. Flood-fill the other walkable cells without crossing a split edge or a stairs cell. This gives regions, each on side
   A or B.
4. Decide which side is high: from the profile's raised pins, else from the cliff-face rule, else leave the map flat.
5. Each stairs cell becomes a ramp, rising towards its high side.
6. A region reached only by ladders (an island) can take its level freely.
7. Run the check from §3.1. If it fails, the map stays flat.

### 3.3 Stairs

- **With 1-block levels:** half a block, then a full block, along the climb. Our tile blocks already have both shapes:
  water is the half block.
- **With 2-block levels:** four half steps, the shape of Minecraft's own stairs. That needs a new collision shape: an
  invisible step collider like the ledge collider.
- **The art:** the stairs' art goes on the top of each step. The fronts reuse the tile, as walls do today.

### 3.4 Edges

- **The edge wall:** a new invisible block like the ledge collider.
  - It is a thin wall 1.5 blocks tall on chosen sides, held as block-state properties.
  - It sits at the high cell's feet level along the edge.
  - It has no outline, so it never catches the crosshair. With no entity (pathfinding), it is solid.
- **Pair edges at equal height:** the same block fixes today's snap-backs (E2).
- **Solid cells** (rock, trees) stand on their highest walkable neighbour and are filled down to the lowest, so no
  gaps open under them and nothing can step onto them.

### 3.5 Ceilings, lights and cave rock

- **Caves:**
  - low ground keeps 3 blocks of air, with the ceiling at feet + 3 as today;
  - high ground (2 blocks up, your choice) gets 2 blocks of air, so its ceiling is at the map's feet + 4, one block
    above today's (§5.2);
  - lights go at feet + 2 on low ground. On high ground they go at head height (a light block has no collision) or
    are left out, since the low ground's lights reach up;
  - CW1's rock, hangings and stalagmites measure from the cell's floor.
- **Open-air maps** (Viridian Forest, the Safari Zone) have the sky above them.

### 3.6 Outdoors

There are no tile pairs outdoors, so there are two sources:

- **Islands.** Some ground can only be reached by a warp and left by hopping a ledge down. It can be raised one level
  per ledge drop with no ramps, since the game never lets you walk up to it.
  - They are found automatically on the joined surface grid, map seams included.
  - Cut trees, boulders, water and story blockers count as passable, so a height never depends on story state.
  - How many islands Kanto has is for the dry run to show.
- **Places you choose.** You list the areas to raise.
  - Where the game lets you walk freely between raised and normal ground, the builder lays half-block ramps across
    ordinary cells.
  - Those are slopes the game doesn't draw, so it is a matter of taste.
- **Most ledges** have a walk-around back up, so they stay flat lips. A real drop there would need a ramp somewhere
  else.
- **What stands on raised ground:** buildings, signs, trees and the tree-wall fill stand on the higher side. A building
  needs one level under its whole footprint.

### 3.7 Stacked cave floors (optional)

- **The geometry is mostly there.** The first ladder down from each cave floor already sits straight above its arrival
  cell one layer (22 blocks) down, unless the packer had to move the floor (`WorldPlanner.java:388-404`).
- **How it would play:** a shaft is dug between the two. The hidden game warps at once as it does now, and the client
  plays a locked climb or a drop instead of the fade.
- **Limits:**
  - only that one ladder per pair of floors lines up; the others keep the fade;
  - 22 blocks on a ladder take 7–9 s, against under 2 s for a fall (Seafoam's holes);
  - so a real fall through holes, or a short shaft that ends in the fade, is the realistic version.
- **A precedent:** the Wild Routes dungeons already stack floors at one x/z and fade between them
  (`dungeon/DungeonSlots.java:23-25, 65-68`).

## 4. What changes in the code

| Area | Today | Change | Size |
|---|---|---|---|
| World index | One `y` per map; the reader rejects new fields (`world/WorldIndex.java:52, 561`) | Optional per-cell levels per map, run-length encoded (a few KB), plus ramp directions. A new index hash, which the shape map is bound to (`world/ShapeMap.java:28-37`) | M |
| Lookups | `toCell` accepts feet − 4 to feet + 5 (`WorldIndex.java:57-58, 376-384`); `toBlock` and `cellOrigin` use the map's feet | A `floorY(map, x, y)`; `toBlock` uses it; `toCell` tests Y against the cell's own floor | S–M |
| Movement | `Spot.sameLevel` compares Y (`client/move/Spot.java:56-58`); forced moves are horizontal only (`client/move/ForcedMover.java`) | `Spot` keeps the map's base Y as its level, and physics does the climbing. The ±3 destination check (`client/move/MoveRuntime.java:205-206`) gets the cell's floor | S |
| Server teleports | `WarpService` and `BuildHold` go to the map's feet (`server/WarpService.java:112-114`) | Through `floorY` | S |
| Encounters and battles | The proxy at `toBlock` (`battle/ProxyPlacement.java:26-32`). Static, surf and fishing Pokémon go 4 blocks ahead at the player's Y, with a ground scan 6 down (`battle/EncounterPlacement.java:19, 35`) | On the target cell's floor; otherwise one could end up inside a plateau | S |
| Puppets | Y = the map's Y + `topHeight` (`client/puppet/PuppetRuntime.java:279`; `client/shape/ClientShapes.java:293-325`) | From the cell's floor, with Y smoothed along a ramp | S |
| Clicks and effects | A ground click needs `y == map.y − 1` (`client/act/ActRuntime.java:215-219`); the closed-art pick sits at the map's Y (`client/act/ClosedArtPick.java:71`); `BlockadeFade` and `SurfSprite` go through `toCell` | The cell's floor | S |
| Builder columns | `feet = p.y()`; volumes (`WorldPlan.java:396-430`) and biome boxes (`:480-483`) | Per-cell feet and risers. Solids stand on the high side. Volumes and biome boxes grow, or a rebuild leaves blocks behind | M |
| Level planner | None | New: pairs → regions → levels → ramps → edge walls → the check | L |
| Shape planner | Heights from one base (`ShapePlanner.java:195-262`). ClassBoxes' cover and roof hips compare levels from a shared floor (`ClassBoxes.java:175-205`; `ShapePlanner.java:729-730`) | A base per tile, and absolute heights. Making each map's lowest floor its base keeps every level ≥ 0, so the bitmasks and `Dress.free` (`dressing/Dress.java:113-119`) still work | M–L |
| Dressing | One ceiling key per map (`dressing/Ceilings.java:54-55`). CaveRock's lid and clear line, the canopy and the apron count from feet (`dressing/CaveRock.java:23-31, 110-150`) | Per cell | M |
| Self-check | A flat floor at by − 1; only the ledge gets a lift (`builder/shape/PlannedGeometry.java:50-79`; `SelfCheck.java:33-44`; `Reference.java:171-174`) | A lift per pixel, with risers drawn. A raised floor would otherwise count as hidden ground, and a lowered one as holes, which are gated at 0 | M |
| Wild spawns | The spawn rule allows only `(y − 63) mod 22 == 0` (`data/kantobridge/spawn_rules/walking_level_only.json`). That rule also keeps spawns off tree tops | A new test, or raised floors get no wild Pokémon. Habitat blocks go under their patch's floor (`builder/plan/HabitatPlanner.java:23-37`) | M |
| Blocks | The ledge collider | The edge wall; with 2-block levels, a step collider too | S |
| Tests | `WalkabilityTest` (the same colliders at feet and feet + 1), the self-check gates, the spawn GameTest (`SyntheticBuildGameTests.java:345-349`) | Updated, plus the edge-check tests | M |
| gen1-bridge, protocol, hidden game | 2D cells | None: `tilePairs` is already exported | — |

## 5. Consequences

### 5.1 For play

- **Caves and the Safari Zone show raised ground**, and the stairs really climb. Ledges with raised ground behind them
  become real drops.
- **Walking stays exactly the game's.** There are no new shortcuts and no new dead ends: the check guarantees it, or
  the map stays flat.
- **Fewer snap-backs**, because the pair edges become walls.
- **What you can see:**
  - from a platform you look down on the floor below;
  - a 1-block platform comes up to your hip, so you see what's on it;
  - a 2-block one is above eye height and hides what's on it.
- **Puppets and other players** stand at the right height. An NPC walking a ramp needs its Y smoothed; today its height
  would jump mid-step.
- **In battle,** Cobblemon puts the sent-out Pokémon in front of you, so at a platform edge they may drop or stand
  below. That is cosmetic, with no damage.

### 5.2 Technical

- **Every world must be rebuilt:** the index format changes and there is a new `kb-shapes` version.
- **Vertical room** (updated after your answer of 2026-10-12).
  - **What limits it:** interiors are stacked under the surface, one layer every 22 blocks (`world/Scale.java:25`).
    All 14 layers that fit above the world's bottom (Y −256, our override in
    `data/minecraft/dimension_type/overworld.json`) are used. The depth was set by Silph Co.'s 11 floors (PLAN P4).
  - **Each layer is 22 blocks:** a 5-block room (floor block, 3 blocks of air, ceiling at feet + 3,
    `WorldPlan.java:42`) and a 17-block gap down to the ceiling of the room below (VR:576). DESIGN asks for at least
    16 (`docs/DESIGN.md:40`), so each gap has 1 spare block. Only one of the two rooms it separates can spend it.
  - **The rule that makes it work:** raised ground only ever goes up from the map's floor, never down. Each room then
    spends only the gap above it, even where cave floors are stacked.
  - **Your choice fits as it is:** a 2-block cliff with 2 blocks of air on top puts the ceiling over high ground at
    feet + 4, one block above today's. That leaves 16 blocks. Layer 1 under the surface works the same (ground block
    63, ceiling 46).
  - **The limit:** one raised level per cave map. A third level, or 3 blocks of air on top, needs more room.
  - **The gaps are empty air:** the Kanto preset is a void world (`data/kantobridge/worldgen/world_preset/kanto.json`,
    flat with no layers), and each interior is a closed box of floor, ring and ceiling. The distance is what keeps
    sound apart: Minecraft's sounds fade out at 16 blocks.
  - **More room, if wanted later:** lower the world's bottom, as `docs/DESIGN.md:42` allows. Minecraft allows down to
    −2032, in steps of 16. Fourteen layers need `63 − bottom ≥ 14 × step`:
    - a 24-block step (2 more blocks per room) needs a bottom of −288;
    - a 26-block step (4 more) needs −304.
  - **What a deeper world costs:** empty sections cost almost nothing to store or draw. It touches the dimension
    override (it applies to every world opened with the mod, VR L2), `Scale.LAYER_STEP`, the spawn rule's hard-coded 22
    (`walking_level_only.json:8`), the golden test index and the tests that expect 14 layers at −256, and PLAN P4.
  - **Less separation** is the other way to get more room. It is your rule, so it needs your call.
  - Open-air maps and the surface can rise freely. Tall buildings on raised ground need the surface volume's top
    (ground + 32, `WorldPlan.java:34-36`) raised.
  - The voxel plan says to stop and ask before changing the layer layout (`docs/specs/VOXEL_BLOCKS_PLAN.md:1053`).
- **The shape map budget.**
  - It is expected at about 94% with the profile, leaving about 31k characters.
  - Plain risers cost nothing (id 0), but raised columns share fewer stacks.
  - The dry run measures it.
- **One floor per cell.** A cell can't be walked at two heights, so there are no bridges over passages. Gen 3's bridges
  will need more later.
- **Heights never depend on story state.** The geometry is shared, so a cut tree or a boulder never changes a floor.
- **Unaffected:**
  - texture packs, though risers reuse the tile's art until a fold exists;
  - the hidden game, saves and multiplayer story rules;
  - the Wild Routes zones and dungeons, whose walking cells are flat by design (`builder/zone/ZonePlan.java:38-40`).
- **Build time** grows only a little.

### 5.3 Interiors in an area of their own (your question of 2026-10-12)

- **The idea:** build interiors in a region away from Kanto, the wild zones and the dungeons, instead of in layers
  under their doors. Nothing would stack, so every map could be as tall as it needs.
- **Today's code already allows it:**
  - a warp lands wherever the index says the map is (`WorldIndex.toBlock`);
  - long teleports already happen: Fly, Dig, Teleport, Escape Rope, the wild zones 3,000 blocks south
    (`builder/zone/ZoneRegions.java:15`) and the dungeons 3,000 blocks north (`dungeon/DungeonSlots.java:21`);
  - 6 interiors already sit away from their doors: 5 sideways and 1 deep spill (`docs/PROGRESS.md:1557-1558`);
  - the fade stays dark until the arrival spot is drawn, for 3 s at most (`client/move/WarpFade.java:167-171`,
    `client/move/MoveRuntime.java:199-212`).
- **Where:** the region table already holds the wild zones (south), the dungeons (north) and room for other games
  (west and east, `docs/specs/WILD_ROUTES_PLAN.md` §4.10). A new region could go north, past the dungeon slots.
- **What it gives:** several levels per cave, tall ceilings, and walk-down shafts (E5) with any spacing. One cave's
  floors can still sit near each other, so its ladders stay short teleports.
- **What it costs:**
  - **Doors stop being instant.** Today the room under a door is already loaded, because Minecraft loads whole chunk
    columns (`docs/DESIGN.md:36`). From a far region each door waits for chunks: a longer dark pause going in. Going
    back out, the spot you land on is drawn before the fade lifts, but the rest of the town pops in after it. The
    pause needs measuring on a modest PC.
  - It changes DESIGN §3 (interiors under their buildings, caves under their mountains). That's your file.
  - New placement code (a grid instead of layers), its tests and a rebuild. The index format, the teleports and the
    bridge stay as they are.
  - The world stays deep for now: dungeons stack their floors 22 blocks apart below Y 64
    (`dungeon/DungeonSlots.java:22-25`).
- **Middle ground:** move only the caves. They are the only roofed maps that need the height (the forest maps are open
  to the sky). There are fewer than 20 cave floors *(general knowledge)*, and you go through their entrances far less
  often than through house doors. Houses, shops and gyms stay under their doors, and their doors stay instant.
  **You chose this on 2026-10-12** (§7).
- **Missed in the first answer:** the plan of 2026-10-09 (`docs/specs/VANILLA_WORLD_PLAN.md`) moved every interior
  into one dimension of its own. **You dropped it on 2026-10-12** (several dimensions loaded with players spread out,
  puppets across dimensions), so the cave area stays in the overworld.

### 5.4 Growth: new places and other games (your question of 2026-10-12)

- **Space is not the limit.**
  - Minecraft's world is about 60 million blocks across.
  - Kanto is 704 × 744 blocks with its ring (the dropped vanilla plan's WD5). With its wild zones (from 3,000 blocks south,
    rows of 8 slots of 640 blocks) and its dungeons (3,000 blocks north), it spans about 5,000 × 7,500 blocks.
  - West and east of Kanto are kept for other games' worlds (`builder/zone/ZoneRegions.java:9`,
    `docs/specs/WILD_ROUTES_PLAN.md` §4.10).
- **Each region repeats the same parts,** placed from its own origin: its surface, houses in layers under their doors,
  its own cave area, its wild zones and its dungeons.
- **Height:** 14 layers hold Kanto's tallest stack, Silph Co.'s 11 floors (PLAN P4). Johto's tallest is Tin Tower,
  9 floors and a roof *(general knowledge)*. A dry run would show how often the packer spills.
- **Heights carry over.** Gen 3 stores an elevation per cell (§2), which maps straight onto the per-cell floor level.
  Gen 3's bridges need a second floor per cell (§5.2, "One floor per cell").
- **The cave area must be ready for regions:**
  - placed from its region's origin, like `ZoneRegions` and `DungeonSlots`, with the region in its records;
  - filled from the data (roofed maps with raised floors), not from a list of Kanto's caves.
- **What fills up first, with Kanto alone:**
  - **Block slots:** 3,926 of 4,096 full-shape slots (`docs/ROADMAP.md:35`), so Johto's tiles won't fit. The plans
    favour a flexible block with no fixed capacity and textures per region (`docs/NEXT_STAGES.md` §4.2, §5).
  - **The shape map on the wire:** 78% measured and about 94% expected with the profile, of an 8-part budget
    (`docs/ROADMAP.md:34`, `docs/specs/VOXEL_BLOCKS_PLAN.md:295`). The voxel plan's noted fix is sending grids on
    demand (R10, `:1385`). Per region, that means only the region the player is in.
  - **One world, one game:** the WorldIndex, the server rules and the hidden game all assume Kanto.
    `docs/NEXT_STAGES.md` §5 makes regions first-class, each with its own index, textures, rules and hidden game,
    and it needs a Gen 2 bridge. A region-scoped world format doesn't exist yet (ROADMAP Stage 8).
- **New places that aren't from a ROM** get slot areas like the wild zones and the dungeons.

## 6. Phases

1. **E1 Groundwork (M).** Levels in the index, all 0, with `floorY`, `toCell`, `Spot`, teleports, puppets, clicks
   and encounters switched over. The tests show nothing moved. Check: JUnit and GameTests.
2. **E2 Pair walls (S).** Read `tilePairs` and put edge walls on blocked pair edges. This is a visible fix on its own.
   Pod build, plus a short video of a cave edge.
3. **E3 Cave and Safari levels (L):**
   - the level planner and its check;
   - builder columns, shape planner, dressing and self-check;
   - the spawn rule and habitat blocks.

   Pilot it on one cave, which can show both level heights. Then a video; you decide; then all caves and the Safari
   Zone.
4. **E4 Outdoors (M):** the islands, then your list of places. After E3.
5. **E5 Shafts between stacked floors (M–L, optional).**

## 7. Your decisions

1. **How high is one level?** **Decided 2026-10-12: 2 blocks, with 2 blocks of air on top.** A real cliff above eye
   height. It needs step colliders (§3.3) and fits today's layout, keeping 16 blocks of separation (§5.2).
2. **More room:** **decided 2026-10-12: caves move to an area of their own** (§5.3), so they get all the height they
   need. Houses, shops and gyms stay under their doors.
3. **Scope and order** (E1–E4), and which cave to pilot.
4. **Outdoors:** the islands only, or also places you name, with ramps the game doesn't draw.
5. **E5:** later, or never.

## 8. What a dry run would measure

On the pod, with counts and map ids only:

- per map: regions, raised cells, ramps, islands and pair edges, and which maps fail the check and why;
- where the high side came from (profile or face rule), and where the two disagree;
- the shape map's size, quads and self-check scores;
- a video of the pilot cave.
