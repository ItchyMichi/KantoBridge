# The production pipeline for Emerald (a copy of Crystal's, 2026-10-19)

Blue's pipeline (PRODUCTION_PIPELINE_BLUE.md) copied for Crystal, kept separate at the user's wish (2026-10-19): Crystal's
scripts are `tools/shape_fit/emerald/` (a copy of Blue's, changed only there) and its marking rules
MAP_MARKING_RULES_EMERALD.md. Where this file says "Blue" in the steps below, read the Blue run's figures for scale;
Crystal's own differences are in "Crystal's changes" and "The Crystal trial" at the end.

## Blue's artwork: the assumptions

- Game Boy, **four shades**: white (also the ground's colour), light, dark, black. Black is the outline everywhere;
  pure black (0, 0, 0) in the hidden-game mosaic is **missing art**, never a game colour (Blue's black is 25, 16, 16).
- Map pictures on kb-hub: 48 px a cell (16 px art at 3x); cells are 2 x 2 art tiles; keys `tilesets.<name>/a-b-c-d`.
- In the game the drawing comes as shade numbers per tile (`ArtSource.tileShades`), shade 3 black.
- Interiors: black wall tops and void are walls ("keep as blocks").

## 1. Mark (agents, then the user)

- Maps are marked one at a time on kb-hub's maps page (`/maps.html`) or by agents following
  `docs/specs/MAP_MARKING_RULES_EMERALD.md` (context looked up online first), each writing
  `kb-hub/data/files/agent/<game>__<MAP>.json` and submitting with `tools/shape_fit/emerald/agent_merge.py <kb-hub data> <file>`
  (file-locked: agents can submit at once). Inputs per map: `<MAP>_grid.png` and `files/agent/<MAP>_cells.txt`.
- Blue: 220 maps, 1,599 entities, ~11.6M tokens, one workflow (`mark-blue-maps`), two agents at a time on this server.
- Kinds: object, stack (tall object, upright 1 x 2), sideways pair, make by hand, keep as blocks, floor decoration.
  One copy of repeated objects (keys repeat); 1-2 background floor cells each; stools say "stool"/"chair"; a building
  is one "make by hand" entity over its whole drawing, door included.

## 2. Depth models (objects, stacks, pairs) and slabs

- `tools/shape_fit/emerald/batch_entities.sh <game>` on a CPU pod (cpu3g x8): per map `entity_depth.py` (Depth Anything V2
  Small + Real-ESRGAN x4 twice, CPU) then `entity_package.py` (the "make by hand" packages). Blue: 222 maps, 870
  models, ~1.5 h. Set up with `tools/shape_fit/emerald/depth_setup.sh` (a local venv: the network volume is too slow to
  import torch from).
- Mask: a pixel looks like floor when it matches the background cells or is one of the background's main colours
  (a quarter of its pixels or more); floor is only what is reached from outside the object (enclosed floor-coloured
  areas stay the object's unless they match the floor at their place: a wheel's middle). Specks under 4 px dropped.
- Relief: each drawn pixel a column, depth from the averaged depth maps; a sideways pair at most 6 voxels deep.
- Floor decorations: a slab 4 voxels high under every drawn column.
- Merge: `merge_voxels.py <game> shapes_all.json --source entity --replace-first --stacks <flags> --seats <maps dir>`.
  Stacks: an upright 1 x 2 stack's top cell gets `"on"` (the planner puts it on the bottom cell). Seats: a walkable
  entity whose note names a stool, chair, seat or bench (not knocked/overturned/tilted/sign) is squashed to 4 voxels
  and marked `"seat"` (a half-block seat collider; NPCs stand on it). Blue: 336 cells, 184 stack tops, 11 seats.
- Publish: `kb-hub/work/publish_entities.py <game> <batch dir>` (viewer `en_*`).

## 3. Hand models (furniture, machines)

- Packages from step 2 (`hand_models_<game>.zip`): reference, grid, context, spec, starter.bbmodel, README; PROMPT.md.
- A model is boxes in Blockbench units (32 a cell, 16 a block), front +z, sizes in multiples of 2; one cell high
  unless it has its own keys (then layers, HAND_MODELS.md section 8).
- `hand_import.py <package> --out hand.json --viewer <dir>`: sliced per cell, painted from the model's UVs (a voxel's
  top face when open, else front, sides...; another cell's pixel becomes the nearest same colour in its own cell;
  pure black: the same place). Merge with `--source hand --replace-first`. Blue: the Red's House 1F table only so far.

## 4. Buildings

- `building_batch.py <game> <kb-hub data> <out>` (numpy, pillow; a capped container): every "make by hand" entity
  on an outdoor map (towns, cities, routes, islands, the plateau, the Safari Zone) 3 x 2 cells or more, not a cave,
  rock, mound, tree, counter, partition or post; one per design (same keys in the same layout). Blue: 34 designs.
- Detection (`building_model.detect`), from the outline (the darkest colour within 30, pure black left out):
  the roof ends at its first **double line** (two rows 50%+ dark, one row apart, the middle under 30%) below row 4;
  else its first **single line** 75%+ dark below row 8; else 0 (no roof: a tower's lower floors); within 60% of the
  rows. A later double line closes a **porch band** (from the dark row above it, a third dark or more; at most 12
  rows). The roof is a **hip** when the first dark pixel of its rows moves 6+ columns, else **flat**.
- Model: the cube as wide as the facade's outline columns (black down half the facade), 1.5 voxels a facade row
  (a door about 2 blocks; Celadon's Department Store about 20 blocks, the user's choice); its front 4 voxels in from
  the grid's south edge; the door cell's middle (columns 3-12) recessed 3 voxels for its lowest 11 rows; **features**
  (10+ touching pixels unlike the wall, with some black in them, narrower than 60% of the wall: windows, doors,
  signs) out 1 voxel; porch bands out 3; the sides the wall's colour and its full-width bands (70%+ one colour).
  The roof topper as wide as the drawn roof: a hip of 16 voxels in 2-voxel steps (2 in from every side each), or flat:
  a 4-voxel slab with a 2-voxel parapet, its middle rows repeated at their own scale.
- Review: viewer ("buildings / <game> / <map>", `kb-hub/work/publish_buildings.py`); corrections in
  `kb-hub/data/files/building_overrides_<game>.json` by `<map>:<x0>,<y0>` (roof_rows, roof, bands).
- In game: `buildings_table.py <game> <kb-hub data> <out>` writes the table's `"buildings"`; the planner claims each
  grid wherever it appears (`FittedBuildings`) and every client builds it (`geom/BoxBuilding`). Blue: 140 in Kanto.

## 5. Build and check

- Tests at each step (shape/block/plan); the full check (`tools/check.sh`: pytest + Gradle build with JUnit and
  GameTests) before a push. Blue's last: 2,251 passed, 126 GameTests.
- Rebuild a region on a pod: `tools/e2e/build_world.py --skip-export --game blue --region kanto --slot 0 --maps all
  --world FittedE2E --heap 8g` (KB_JAVA_HOME set; kill old Java by name first, a held session.lock fails the build).
- Pictures: `tools/e2e/context_shots.py` with a locations file (map, unit cell, a walkable stand cell, facing);
  ffmpeg, Xvfb :99 and :97 on the pod. A change to client geometry needs `ShapeTable.GEOMETRY` raised, or clients
  reuse their cached meshes.

## Blue's open items (for reference)

- Hand models: only one made; the Blue batch (200 packages, 34 of them buildings now generated) is next.
- To check: the Route 23 gate (only its top on the map), the Route 16 gatehouse (two parts), walking onto seats.

## Crystal's artwork: how it differs

- Game Boy Color: each tile has its own four-colour palette, so a screen mixes several; the outline is still each
  palette's darkest colour, but the darkest colour of a building can be grey (Ecruteak's 58, 58, 58) or green.
- Cells are 2 x 2 tiles as in Blue (16 px), keys `tilesets.<name>/a-b-c-d` with larger tile numbers.
- The cells files carry no tints for Crystal (every cell "-").
- Some exit cells are a red hatched placeholder in the mosaic.

## Crystal's changes (from Blue's)

- Buildings, `tools/shape_fit/emerald/building_model.py` detect(): a run of near-full (80%+) double lines two rows
  apart is one line, the roof ending after it, within 70% of the rows (Ecruteak's striped tile roofs had ended at
  their first stripe); each later run is one band (a storey's eave); a building up to two cells deep with no roof
  line is half roof (a gate), not all wall. Blue's 34 buildings detect the same with it (checked), but Blue keeps
  its own script.

## The Crystal trial (2026-10-19)

Ten maps chosen to stress the rules: New Bark Town, Azalea Town, Ecruteak City, Goldenrod City, Olivine City, the
Ecruteak Gym, the Goldenrod Game Corner, the Dance Theater, Kurt's House, Tin Tower 1F.

- Marking: 133 entities, no agent errors, ~0.7M tokens. What Blue's rules didn't cover (from the agents):
  overlapping forest trees drawn as half-trees over two cells; water, shores, cliffs and plateaus; flowers; a
  building cut off by the map's edge; doorless buildings repeated at different widths; half-cell log-post walls
  (Tin Tower) that "keep as blocks" overfills; floor cushions as seats; a step edge with things on it (shoes);
  back-to-back slot-machine banks; a roof corner tile drawn in tree colours (Goldenrod, 3917-3920-3923-3924).
- Buildings: 37 designs. Simple houses, Marts, Pokémon Centers and Gyms in all five towns came out right with
  Crystal's colours. Ecruteak's tile roofs needed the change above. Hand models: the Burned Tower (a ruin), the
  Radio Tower (drawn from above), the tall Ecruteak building. Crystal's pitched roofs are drawn as rectangles, so
  they come out flat with stripes (a hip needs a sloped outline).
- Depth models (Crystal's copy of the scripts, no changes): 65 models over the ten maps, 74 cells, 10 stack tops,
  2 seats; no errors.
- In game (Johto rebuilt): 84 buildings placed, 0 templates or units failed, 2,990 stacked units (the forest
  half-trees the agents marked as stacks: every forest edge stands two cells tall), 380 seat colliders; 3 warnings,
  layout ones (edge mismatch, an object out of bounds), not shapes'. Pictures: houses, Marts, Centers, the Olivine
  lighthouse, the Game Corner's machines and Kurt's furniture right; the Ecruteak Gym and Tin Tower 1F pictures
  failed (the client didn't follow onto those maps).
- Game code (shared): Crystal's roof rows can have no black pixel, so the generated roof showed the wall's colour;
  `geom/BoxBuilding` now takes such a row's own pixel (a Blue roof row always has its outline, so Blue's are
  unchanged); geometry version 7.

## Emerald's artwork: how it differs

- Game Boy Advance: 16-colour palettes per tile, 8 px tiles, 16 px metatiles (cells), map pictures at 48 px a cell;
  keys `g3q/<a>-<b>-<c>-<d>`.
- No black outline: edges are coloured and shaded. In the game's shades (Textures.shade: mean of R, G, B; 3 under
  43, 2 under 128) Emerald's outlines are mostly shade 2, not 3; in the pictures they only show as "dark" at 85+.
- Roofs are drawn in perspective with eaves, in colours of their own (orange, red, olive, blue) over pale walls;
  walls are 1-3 cells tall; in Gen 3 a building's top roof row is drawn over walkable cells (the player walks behind).
- kb-hub's Emerald pictures (the hidden-game mosaic) have faults the game's own tiles don't: missing tiles as black,
  two-layer tiles drawn wrong (see-through boxes, chairs in pieces), some interiors half a cell off, the map-name
  popup and moving sprites baked in (Littleroot's truck, Birch's aide), garbled text-like tiles. They mislead
  marking and depth models; the game builds from its real tiles.

## Emerald's changes (from Crystal's)

- Buildings, `tools/shape_fit/emerald/building_model.py`: outline() is the game's shade 2 and darker (mean under
  128); detect() splits roof from wall at the sharpest colour change (8 rows above against 8 below, over the middle
  60% of the columns) within 1-3 cells of the drawing's bottom (a drawing up to two cells deep is half roof); no
  bands; roofs flat.
- `buildings_table.py` gives each building `"outline": 2`; the game's `geom/BoxBuilding` (shared) takes a table's
  "outline" shade (absent: 3, so Blue's and Crystal's are unchanged).

## The Emerald trial (2026-10-19)

Ten maps: Littleroot Town, Fortree City (treehouses), Sootopolis City (a crater), Pacifidlog Town (floating houses),
Mauville City, Lilycove City (huge), the Mauville Game Corner, Professor Birch's Lab, the Fortree Decoration Shop,
the Rustboro Pokémon School.

- Marking: 196 entities over the ten maps, no agent errors, ~0.8M tokens. Not covered by the rules (from the
  agents): treehouses on raised decks with rope bridges and ladders; rope bridges; log rafts and floating huts over
  water; roofs drawn over walkable cells; trees as whole 2 x 2 squares (not Crystal's half-trees) with canopy tips
  in the cell above; trees on walkable cells; solid plateau cells drawn with the ground's tile; doorless decorative
  houses; a bank of identical stools; tree-hollow rooms (angled walls, a trunk inside); a desk tile with no sprite
  (black). Most other complaints were the pictures' faults above.
- Buildings: 34 designs. With Crystal's (Blue's) outline detection 30 found no roof; with Emerald's colour split
  most come out right: Lilycove's houses, Pokémon Centers and Marts with their signs, the Mauville and Fortree Gyms,
  Sootopolis's domed houses. Wrong from the pictures: Littleroot's house (black missing tiles), Birch's Lab (the
  truck), Pacifidlog's huts (rafts over water). Littleroot's two-tier roof splits at its upper tier (an override).
- Depth models (Emerald's copy of the scripts, no changes): 84 models over the ten maps, 134 cells, 13 stack tops,
  9 seats; no errors.
- In game (Hoenn rebuilt): 40 buildings placed, 0 templates or units failed, 5 stacked units, 108 seat colliders;
  7 layout warnings (edge mismatch, an overlap), not shapes'. Pictures (11 of 11): the Mauville Gym with its sign,
  Lilycove's houses, the Fortree Pokémon Center read right; interiors (the Game Corner, Birch's Lab, the school,
  the Decoration Shop) have their objects; Sootopolis's crater walls stand as columns.

## Buildings as parts (Emerald's building method, 2026-10-19)

The generated box (section 4: one cube and a roof topper) doesn't fit Emerald's buildings: rounded Pokémon Center
roofs, domes, treehouses, entrances that stand forward, staircases. For Emerald an agent describes each building as
**parts** and `tools/shape_fit/emerald/parts_model.py` builds and paints it. The format, the reading of the drawing
and the lessons are in docs/specs/BUILDING_PARTS.md (shared with the other games' trials); Emerald's renderer is its
own copy. The box generator stays as the fallback (a building with no parts spec).

### Shapes

box, cylinder (round or oval, `radius` r or [rx, rz]; its front art wraps round it: round lower walls, rotundas,
towers), gable, hip, dome, cone, barrel (a half-round vault), profile (a roof cross-section extruded along the
building, optionally a second one along the run for a curved hip: rounded roofs, upswept eaves, steps), and carves
(recessed doors, porches). Each part names the drawing's rectangles that paint its front, top, sides and back; on a
barrel or profile roof the top art follows the curve, so a band across a rounded roof's front (the Poké Ball band)
wraps round it.

### Steps

1. **Package** per building design (after step 4's `building_batch.py` has picked the designs): `drawing.png` (the
   entity's cells at 16 px a cell), `reference_grid.png` (10x, a line every 4 px, pixel numbers every 8),
   `info.json` (game, map, box, size) and `old_box_model.png`; in `kb-hub/work/parts/<name>/`.
2. **Agent** per package (a workflow, general-purpose agents): read BUILDING_PARTS.md and the renderer's docstring,
   look the building up online (1-2 searches), write `spec.json`, build with
   `docker run --rm -m 800m -u $(id -u):$(id -g) -v <repo>/tools/shape_fit/emerald:/sf:ro -v <package>:/pkg kb-pyimg:1
   python /sf/parts_model.py /pkg/spec.json /pkg/drawing.png /pkg/out --name model`
   (the image `kb-pyimg:1` is python:3.12-slim with numpy and Pillow), compare `out/model_view.png` (3D) and
   `out/model_front.png` (straight on) with the drawing, refine: three rounds at most. A few minutes and ~50k tokens
   a building.
3. **Review**: publish to kb-hub's viewer ("buildings as parts / emerald / <name>", the drawing, 3D and front pictures,
   the spec) and a sheet: drawing | old box | parts. The user flags a building; an agent redoes it with the note
   (saving the old spec as `spec_v1.json`).
4. **In game**: to come. A game-side builder for parts specs (pixel-free numbers: part sizes and the drawing
   rectangles that paint them, every client painting from its own tiles), as `geom/BoxBuilding` does for boxes.

### Lessons (the trial)

- Leave out background the marked area includes (rock, cliff, grass, trees, the map-name popup, black missing art):
  model only the building; a dome or roof is as wide as the building under it (Sootopolis's dome house, first
  modelled on a rock slab).
- Split a part to keep faulty art off it (the Museum's lower block is three boxes around a black patch).
- Rounded roofs need barrel/profile parts, not hips (the Fortree Pokémon Center's pointed pyramid stretched the
  Poké Ball; two profile parts fixed it).
- A building on a cliff: the cliff is terrain, not the building; its stairs climb to the door landing.
- Remaining rough spots: sides and backs reuse front art (the drawing shows neither); stray background pixels at a
  roof edge (tighten that part's paint rectangle).

### The trial's buildings

Littleroot's house (two storeys, set-back upper floor, gable roof), Sootopolis's dome house (redone without the rock:
a square house under a stepped pointed roof), a Fortree treehouse (hut on a deck, leafy dome, trunk, ladder), the
Fortree Pokémon Center (rounded roof with the Poké Ball band), the Mauville Gym (a forward entrance block), the
Lilycove Museum (two levels, a raised central section, a recessed porch, the staircase with finials; 20 parts). All
in `kb-hub/work/parts/emerald_*`, on the viewer.
