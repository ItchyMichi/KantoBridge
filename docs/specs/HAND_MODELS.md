# Hand-made models (fitted shapes, "make by hand")

How an entity marked "make by hand" on kb-hub's maps page (docs/specs/MAP_MARKING_RULES.md) becomes a 3D model in the
game. Written from the first one, Blue's Red's House 1F table (`blue_REDS_HOUSE_1F_hand00`, 2026-10-19); updated as
the quality check finds things.

## 1. The package (automatic)

`tools/shape_fit/entity_package.py` (run by `batch_entities.sh` with the depth models) makes one folder per entity:

| file | what it is |
|---|---|
| `reference.png` / `reference_grid.png` | the entity's cells as the hidden game draws them (8x), the grid numbered (column,row) |
| `context.png` | the entity with 3 cells around it, for scale and surroundings |
| `spec.json` | size in cells, every cell's key and kind (S solid, W walkable, C counter, L ledge), `part` (the entity's own cells), the note, the background cells |
| `starter.bbmodel` | a blockout: one box per cell at today's height, the reference as texture |
| `README.md` | the entity in one line |

`PROMPT.md` (next to the game folders) is the modeller's brief.

Check before modelling: cells drawn **black** with no matched sprite are missing art in the hidden-game mosaic (the
table's top right quarter), not part of the object; model them like the cells around them.

## 2. The model (Blockbench, by hand or by an AI agent)

Conventions (PROMPT.md has them in full):

- 32 Blockbench units a cell (16 a block). The footprint of a W x H cell entity is x -16W..16W (west to east), z
  -16H..16H (north to south), ground y = 0, the front facing **+z (south)**.
- Cubes only (rotations in 22.5 degree steps are fine). Sizes in multiples of 2 units: the game's grid is 2 units (one
  voxel), anything finer is lost.
- Heights: a player is 2 blocks (32 units); a table about 1 block (16 units); a chair seat about 8 units.
- **One cell high for now: y 0..32.** Anything above is left out on import (taller structures need the planner to
  stack layers; to do).
- Keep the footprint; walkable cells of the footprint stay open at ground level.
- Save `model.bbmodel` in the package folder. The texture only matters in Blockbench (see colour, below).

An agent can write the model as a list of boxes (the first one: `make_model.py` in its package, 11 boxes).

## 3. Colour (what the game shows)

The game colours a voxel shape by **front projection**: every voxel shows its own cell's drawing at its column (x)
and height (row 15 - y in voxels), the whole depth through. So:

- the model gives the **shape** only; the colours come from the cell's drawing;
- the front row of cells comes out best (its drawing is the object's front);
- shapes in back-row cells take the back cell's drawing at their height (the table's back legs show the top's colour).

## 4. Import and check

```
python3 tools/shape_fit/hand_import.py <package> --out hand.json --viewer <dir>
```

slices the model into its cells (16 x 16 x 16 voxels each, keyed by the cell's key) and writes a viewer file coloured
as the game colours it. Publish it to kb-hub (`data/models/hd_<id>.json`, group `hand models / <game> / <map>`), check
it in the viewer next to the reference, then:

```
python3 tools/shape_fit/merge_voxels.py <game> hand.json --source hand --replace-first --stacks <flags_<game>.json>
```

puts the cells into the game's table (`"from": "hand"`). Rebuild the region (`tools/e2e/build_world.py`) and take
in-game pictures (`tools/e2e/context_shots.py` with a locations file: the unit cell and a walkable cell to stand on).

## 5. Quality check

Viewer (shape, proportions, footprint) and in game (next to the hidden game's screen): see the review notes below.

## Review notes

- Red's House 1F table (2026-10-19), first model, in game:
  - Shape and size are right: legs, apron and top, one block high, standing free between the chairs.
  - The flowerpot was off-centre. The mosaic's black quarter hid where the pot really is; the hidden game's own
    screen showed it centred. Lesson: check the hidden game's screen (context_shots) as well as the mosaic.
  - The top comes out dark. Front projection paints the top surface with the drawing's front-edge rows (the dark
    edge at that height), not the light table top seen from above.
  - The first picture spot failed (the game went to another map first); the second worked.
