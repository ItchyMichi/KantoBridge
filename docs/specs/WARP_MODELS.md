# Warp models: stairs, ladders, holes and wall exits in 3D (design, 2026-10-20)

The user (2026-10-20): walkable warp tiles (stairs, ladders, holes) need a model, and exits the games draw from above
(a doorway in a room's top wall, a cave exit drawn as a white glow) must read front-on in the world; walking onto the
cell must still trigger the warp. This file is the model side; floors at different heights (a stair that really goes
down a level, caves stacked in 3D) are the 3D world plan's (HEIGHTS_ALL_GAMES_PLAN.md), a separate session.

## What the code has (survey, 2026-10-20)

- Every game maps its warp cells to the same cell tags (`Gen1FrontEnd.tags`): DOOR, WARP_TILE (stairs, mats,
  ladders; Gen 3's "warp keeping facing" exits and holes), PAD, HOLE (Gen 1 and 2), and WARP (a warp entry stands
  here). The finer kind is lost after the export: Gen 2's `G2Tiles.Meaning` keeps door/stairs/pad/hole/edge and a mat
  direction, unread; ladder and staircase are one "stairs" already in the export; Gen 3's behaviour per metatile
  (`G3Tiles.behavior`) is kept but read only for encounters; Gen 1 tells them apart only by tile id.
- Destinations are known (`ExportMap.Warp`, `G2Map.Warp`, `G3Map.Warp`: cell, destination map, arrival warp), so a warp
  cell can be joined to the map it leads to; floor names (`…2F`, `…B1F`) give up or down (`CavePlanner`'s ranks).
- Today a warp cell is plain ground: one floor block with the cell's art lying flat, air above
  (`WorldPlan.renderMapColumn`). A door in a building's wall is handled by the facade fold (`Structures`) and building
  templates (`Buildings`, `FittedBuildings`).
- A warp triggers by position, never by block: the client reports each new cell (`MovementSync`), the hidden game
  decides the warp, `WarpService` teleports. So a model on the cell is safe if it has **no collision** (a decor
  carrier, `DecorBlock` without its SOLID bit) and stays above the floor: the feet must stay in the cell's reach.

## The models

All are cell units on decor carriers (no collision), painted from the cell's own art (and its wall neighbour's), built
on every client like the other fitted units. 16 voxels a cell edge, 8 a block.

| Kind | When | Model |
|---|---|---|
| Stairs up | a stairs/ladder warp tile whose destination is a floor above (name rank), or unknown and drawn light | 4 steps rising toward the wall behind it (the solid neighbour; else north), 4 voxels each, to 16 (2 blocks); each step's top and front painted from the art's rows in order |
| Stairs down | the destination is a floor below (or drawn dark) | the stairs as drawn stay on the floor; a frame 2 blocks tall stands at the cell's back edge (jambs and lintel in the wall neighbour's art, the opening dark), so it reads as steps going down through a doorway. A real recess needs a lower floor: the 3D world session |
| Ladder | Gen 1/2 ladder tiles, Gen 3 ladder behaviour | against a wall: rails and rungs standing 2 blocks up the wall, from the art; in open floor (a hole with a ladder, Emerald's caves): a dark ring rim 2 voxels high round the drawn hole, the ladder's top rungs poking out |
| Hole | HOLE, or a dark round hole drawing | a rim 2 voxels high round the drawn hole; the hole's art stays on the floor |
| Wall exit | a WARP cell on walkable ground whose neighbour toward the room's wall is solid and has no door drawn on it (Abandoned Ship's corridors, rooms' top exits) | a doorway at the cell's wall edge, 2 blocks tall and the drawn opening's width: jambs and lintel in the wall's art, the opening dark (black fading to the room's colour at the bottom), so the player walks into it front-on |
| Cave mouth | a WARP cell in a cave whose drawing is the white glow | an arch in the rock face at the cell's edge, rock-painted, 2 blocks tall, its opening filled with the glow's colours (bright at the back, fading forward) |
| Mats, pads | edge mats, warp pads | unchanged (flat art) |

- Direction: the edge the model faces away from is the walkable cell's solid neighbour (north, then east/west, then
  south); a walk-down exit at a room's top wall faces south.
- The kind needs keeping: the front ends pass a warp kind per cell (Gen 2's `G2Tiles.Meaning.warp`, the Gen 3
  behaviour, Gen 1's tileset warp-tile ids split into stairs and ladders) as a new cell detail beside the tags, and the
  destination's floor rank from the warp list.
- Collision: none. Stair tops are visual only; the player walks through at floor level as today.

## Steps

1. Keep the kind: a per-cell warp kind (none, stairs, ladder, hole, pad, mat, exit) from each front end, and up/down
   from the destination. A dry run lists every warp cell per game by kind, with counts, for a check.
2. Models: `geom/WarpModels` builds each kind's voxels from the cell's art (and the wall neighbour's), a test per kind.
3. Planner: warp cells get the model as a decor unit (no collision, warp untouched); a GameTest that walking onto a
   modelled stairs cell still warps.
4. Pictures per game (Sprout Tower's stairs, Abandoned Ship's corridor exit, a Granite Cave ladder hole and exit, a
   Pokémon Center's stairs) beside the hidden game; the user reviews.
