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

Hand models paint themselves: `hand_import.py` gives each voxel the drawing pixel its cube's face shows (the model's
UVs on the reference): the top face when it is open (a table top shows the drawing's top), else the front, sides,
back, bottom. The table entry carries it as `"t"` (one byte per voxel: row * 16 + column of the cell's own drawing);
a pixel from another cell's drawing becomes the nearest pixel of the same colour in the cell's own drawing (the same
place when it is missing art). Depth models keep front projection.

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

## 6. Seats

A stool or a chair the hidden game draws on a **walkable** cell (characters stand on it: Mom on her stool) is a seat:

- the table entry is marked `"seat": true` (`merge_voxels.py --seats <kb-hub maps dir>`: a modelled entity whose cells
  are all walkable and whose note names a stool, chair, seat or bench), its voxels squashed to half a block (4 voxels)
  and painted with its whole drawing;
- the planner puts a seat unit on the walkable cell over a **seat collider** at feet level: half a block high, so the
  player steps onto it (Minecraft's 0.6-block step, no jump);
- NPC figures on the cell stand half a block up, on the seat (the client's top height counts the seat collider).

Other walkable objects (doors, ladders, stairs, floor clutter, grass) are not seats; they stay as they are for now.

## 7. Buildings

`tools/shape_fit/building_model.py` makes a building from its drawing: a **decorated cube** with a **roof topper**.

- The cube is as wide as the walls' dark outline, as high as the facade's rows times 1.5 voxels (a door about two
  blocks; Celadon's Department Store about 20 blocks, the user's choice, 2026-10-19). The facade is on its front; only
  features (10+ touching pixels unlike the wall: windows, doors, signs) stand out a voxel; the door is recessed; porch
  bands stand out 3 voxels. The sides wear the wall colour and its full-width bands.
- The roof is a separate topper as wide as the drawn roof, so it overhangs: a stepped hip, or a flat slab with a
  parapet (a roof garden's lattice repeated at its own scale).
- Detected from the dark outline (Gen 1's white is also the ground's, so colour can't find the edges): the roof ends at
  its first double line; a later double line closes a porch band; the roof is a hip when its outline moves sideways
  down its rows. `--roof-rows`, `--roof` and `--bands` override.
- Checked on Red's house and eleven Celadon buildings (kb-hub viewer, "hand models / blue").

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
  - After the paint (section 3) and seats (section 6): the top is light with the plant on it; the stools are low seats
    beside it. The front spot (3,6) still frames badly: use the side spot (5,6) for this room.
