# Map marking rules for Emerald (a copy of Crystal's, 2026-10-19)

Crystal's rules (MAP_MARKING_RULES_CRYSTAL.md: Blue's with Crystal's additions, agreed by the user) copied for Emerald, kept separate; Emerald's additions go at the end. How to mark a map on kb-hub's maps page (`/maps.html`) so every cell that isn't floor gets a 3D shape. Written from
the user's first reviews (Blue: BIKE_SHOP, BILLS_HOUSE, BLUES_HOUSE, BRUNOS_ROOM, AGATHAS_ROOM, 2026-10-18). Agents
follow these rules one map at a time and submit as they go; the user spots mistakes in the viewer or in the game and
flags them for resubmission. See docs/specs/FITTED_SHAPES.md sections 14-15 for what happens to a submission.

## What you look at

- `<map>_art.png`: the map as the hidden game draws it, 48 px a cell (16 px of art, 3x).
- `<map>_grid.png`: the same with the cell grid and each cell's (column,row), row 0 at the top.
- `<map>.json`: per cell its key, kind (`S` solid, `W` walkable, `C` counter, `L` ledge), why the planner made it a unit
  or not, the fitted table's class, and `tint`: `v` a voxel model already, `b` another shape, `o` a solid block of an
  object, `s` a solid block of a structure, `d` floor decoration, `-` plain floor.
- The cell's matched sprite: `/images/sprites/<game>/<key with / as _>.png`.

## The rule

Everything that is not floor gets a 3D shape. Floor decoration that would be better raised (a ledge, a step, a raised
edge) gets a slab. Flat floor (plain floor, a rug, a mat, a painted line) stays flat.

## Marking objects: entities

Select every tile of one object as one entity, then pick its type:

| type | use for | examples |
|---|---|---|
| object (model as one) | a free-standing object of one or more tiles | a stool, a computer monitor, a pipe segment, a vase |
| stack (tall object) | one object drawn over a tile and the tile above it (2x1 upright): it becomes one object two cells tall | a potted plant on its stand, a statue on its plinth, a signpost |
| sideways pair | one object over two tiles side by side, seen side-on | a bike, a bench, a sofa |
| make by hand | large or complex furniture and structures the depth model can't do well: it becomes a Blockbench package | a machine room's chamber, a table set with things on it, a market canopy, a building |
| keep as blocks | things that really are solid blocks | a plain wall face, a cliff, a solid cabinet front flush with the wall |
| floor decoration | a raised floor feature | a ledge, a step, a raised border |

- **One copy of repeated objects.** Shapes follow the tiles' keys, so one model covers every copy drawn with the same
  tiles, on every map. If a row of identical shelves or bikes appears, select one (or one of each different one).
- **Only the object's tiles.** Don't include floor between or around it; don't include the wall behind it.
- **Tiles that touch.** An entity is the connected group of tiles you select; a tile tapped by mistake elsewhere is
  dropped (but avoid it).
- **Walls swallow things.** Objects against the back wall of an interior (bikes, shelves, statues) are often drawn as
  part of the wall; mark them as entities anyway: a modelled object now stands free of the wall.

## Background tiles

After selecting an object's tiles, pick one or two **empty floor tiles next to it** (Pick background). The masking
removes exactly that floor from the object's drawing.

- On a patterned floor (a checkerboard), pick one tile of each pattern touching the object.
- Never pick a tile that is part of an object (yours or another entity's): those are ignored.
- If you pick none, the floor around the entity, else the map's most common floor tile, is used.

## Notes

Write a short note saying what the object is and anything about its shape: "Computer monitor sitting on a desk",
"Pokémon statue", "Plant in pot". For "make by hand" describe the whole thing ("A sci-fi rounded chamber"). The note is
the modeller's context.

## Single-cell flags

For a cell that isn't part of a larger object, a single-cell flag is enough: `needs a model`, `keep as a block`,
`floor: no model`, `floor: slab/ledge`, `make by hand`, `model is wrong` (with a note).

## Context from online

Before marking a map, look the location up (Bulbapedia, a game guide, a walkthrough with screenshots) to learn what the
room is and what its objects are: Bill's house holds his Cell Separation System, a gym's statues are Pokémon statues
on plinths, a Poké Mart's counter holds the cash register. Use what you learn in the notes and to decide what is one
object. Keep it short; don't copy text from the sources.

## Lessons from the first agent pass and the blind test (Blue, 2026-10-18)

- Use the kinds' full names exactly as in the table above (agent_merge.py also accepts object, stack, pair, hand,
  blocks, floor).
- An object can stand on walkable cells (stools are walkable in Blue): mark it anyway. A stool or chair on a walkable
  cell becomes a seat that characters stand on (HAND_MODELS.md section 6): say "stool" or "chair" in its note.
- Separate objects stay separate even when they touch: a monitor on a desk, a stool beside it, a plant next to a
  shelf are each their own entity. One entity is one piece of furniture or one machine.
- A table's walkable front edge row belongs to the table only when the art shows it is the same piece (its front
  and legs); a stool or chair in that row is its own object.
- Doorways inside a building's footprint: include the door cell in the building entity so the model keeps its door.
- A building is one "make by hand" entity covering its whole drawing (roof and front); say what it is in the note
  ("Pokémon Center", "two-storey house with a hip roof"). Buildings are generated (HAND_MODELS.md section 7).
- A table or desk with things on it (a book, a vase, a computer on the desk itself) is "make by hand" as one entity;
  a bare table is an object. Something standing on the floor next to it (a stool, a separate monitor stand) is its own.
- Black wall tops and void are "keep as blocks" (one entity of one copy; no background needed when no floor touches it).
- Repeated tiles: an object's copies elsewhere share its keys, so one entity covers them; a 2x2 object whose left and
  right columns share tiles is still one 2x2 entity.

## Submitting

Submit each map when it is marked (`Submit map`). Agents write their marks to
`kb-hub/data/files/agent/<game>__<map>.json` in the maps page's format; `tools/shape_fit/agent_merge.py` adds them to
`flags_<game>.json` (marked `"by": "agent"`) and queues the map in `submissions.json`.

## Crystal additions (from the trial, 2026-10-19)

Agreed by the user (2026-10-19).

- Forest borders: trees drawn as overlapping half-trees over two cells (a top over the trunk of the tree behind)
  are one "stack (tall object)" per drawing pair; a lone tree in one cell is an object.
- Water, shores and shore cells mixing water with land: leave them (flat).
- Cliffs and plateau edges: "keep as blocks"; ledges and the steps through them: floor decoration.
- Flowers: flat (floor).
- A building cut off by the map's edge: mark the part that is on the map, note "cut off by the map edge".
- Doorless buildings repeated at different widths: one entity per width.
- Floor cushions: an object whose note says "cushion used as a seat".
- A step edge with things on it (shoes): floor decoration, the things in the note.
- Back-to-back slot-machine banks: one "make by hand" per bank design.
- Half-cell log-post walls: "keep as blocks", note "half-cell post wall".
- Tints may be all "-": judge from the picture.

## Emerald additions (from the trial; agreed by the user, 2026-10-19)

- Trees are whole 2 x 2 squares: one object per tree design; a canopy tip in the cell above belongs to the tree.
- A building's top roof row drawn over walkable cells belongs to the building.
- Treehouses: one "make by hand" per design (deck, hut and ladder together); rope bridges: floor decoration.
- Log rafts and walkways over water: floor decoration (a raised slab); a hut on a raft: the hut only.
- Trust the cells file over the picture where they disagree: kb-hub's Emerald pictures have faults (black missing
  tiles, see-through two-layer tiles, sprites and the map-name popup baked in, some interiors half a cell off).
  Leave a cell whose art is garbled out, with a note.
- Solid cells drawn with the ground's tile (plateaus): leave them (the planner keeps them as columns).
