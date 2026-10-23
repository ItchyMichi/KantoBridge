# Landscape sections as models (the user's choice, 2026-10-21)

Stage 2 of [LANDSCAPE_SECTIONS_PLAN.md](LANDSCAPE_SECTIONS_PLAN.md). The agents' marks
([LANDSCAPE_MARKING_RULES.md](LANDSCAPE_MARKING_RULES.md)) say what each part of a map's solid ground is. Here a
modelling agent makes a model of each big section the way [BUILDING_PARTS.md](BUILDING_PARTS.md) makes a building:
parts (boxes, sloped profiles, domes, cones, cylinders, carves), painted from the drawing, checked in pictures and in
kb-hub's viewer before anything is built.

## The package (`tools/landscape_sections/package.py`)

One per picture spot: the window of the map its camera looks over (12 cells either side, 24 north).

- `drawing.png`: the window, the game's own pixels, **16 px a cell**.
- `reference.png`: the same at 3x. A line round every cell, the window's pixel numbers every 2 cells along the top
  (x) and left (z), each marked section outlined in its kind's colour with its id (face red, mass purple, mouth cyan,
  trees green, objects yellow, building grey; a white dot: a cell drawn as a top), the camera's cell in magenta.
- `package.json`: the window (`[x0, y0, w, h]` in map cells), the doors (window cells), every marked section in the
  window (id, kind, note, rows, its cells and top cells in window cells), and `model`: the sections to model.

## What to make

- A model per section in `model`, or one model for sections that belong together (a mountain and the mouth in its
  front, a cliff and the bands either side of it). Objects, buildings and the sections not listed stay as they are.
- Each model is `specs/<name>.json`: `"sections"`, the ids it stands on; `"height"`, its voxels at most; `"parts"`,
  BUILDING_PARTS.md's parts in the window's frame (x 0..W west to east and z 0..D north to south in drawing pixels; y
  up in voxels, 8 a block), and for trees the `trees` helper (terrain_model.py's docstring).
- **The footprint:** a model stands only on its sections' cells. The tool clears every voxel over another cell (they
  are walkable, or another thing's), over a door's cell those under 24 voxels (a cave mouth keeps 3 blocks of
  headroom; an arch may reach over it above that). It prints how much it cleared: a lot means a part reaches too far.

## Reading the drawing (as BUILDING_PARTS.md)

- The game's three-quarter view: a thing's lower rows are its front (south) face, its upper rows its top seen from
  above. Sides and backs aren't drawn: make them plausible (rock, earth).
- A cliff or wall: the band drawn as a face (rock, earth, a wall's stones) is its front; the rows over it (grass, the
  rock's top, a rim seen from above) are its top.
- A mass (a mountain, a plateau, an island): its south edge drawn as a face (often one cell, a rim), its other cells
  its top seen from above (grass, rock, ridges). It may rise inward where the drawing shows a mountain (ridges, a
  peak); an island's top is flat or gently rounded.
- A cave mouth: the dark opening is the door's cell; the face round it is the mouth's section. Leave the door's cell
  open to 24 voxels; the face's rows over the opening make its arch.
- Trees: each tree drawn on a cell (in some games its crown over the cell above): a trunk and a crown, the crown about
  a cell wide and as tall as drawn.

## Heights

- 8 voxels a block. The buildings' scale: **1.5 voxels for each drawn row (pixel) of a face**. A face drawn one cell
  tall (16 px) stands 24 voxels (3 blocks), two cells 48 (6 blocks).
- The marks' `rows` (tile rows of 8 px) are the marking agent's reading of a face's height: rows x 8 x 1.5 voxels,
  unless the drawing shows otherwise.
- A mass's top: flat at its front's height, or rising inward where the drawing shows a mountain; at most 128 voxels
  (16 blocks).
- Trees: a trunk about 8 voxels, the crown as drawn (a cell: about 24).

## Paint

As BUILDING_PARTS.md: `front` is a rectangle of the drawing (window pixels, last excluded) stretched over the part's
south face, `top` over its upward faces seen from above, `side` over east and west, `back` over north. Pick the
rectangles so each face shows the right art: the face's drawn rows on its front, the top's rows on its top. Where a
long face's drawing changes along it, use several parts, each painted with its own stretch.

## Building and checking

```
flock /home/claude/kb-hub/work/landscape/.terrain.lock docker run --rm -m 1g -u $(id -u):$(id -g) \
  -v /home/claude/kantobridge/tools:/tools:ro -v <package dir>:/pkg kb-pyimg:1 \
  python /tools/landscape_sections/terrain_model.py /pkg --model <name>
```

One run at a time (the lock waits for other runs; memory is tight). It writes `out/<name>_view.png` (3D from the
south-east, above), `<name>_front.png` (from the south) and `<name>_game.png`: the model drawn in the game's own view
over the drawing, where it should read like the drawing (the face's rows where they are drawn, the top above them).
Look at all three; fix and rebuild, three rounds a model at most. Last, `--scene` builds every model on the window's
ground: `out/scene_view.png`, `out/scene_game.png`, `out/scene.json` (kb-hub's viewer).

Write `specs/*.json` and `notes.md`: a line per model (what it is, its height, what you weren't sure of).

## In the game (`LandscapeModels`, the pilot)

`tools/landscape_sections/models_table.py` writes every spot's specs as the planner places them: per model its grid
(the tiles its cells, its doors, its parts and its paint cover), its claim (its cells' tiles and the doors its arch
reaches over) and its spec in the grid's pixels: the trees helper expanded, the footprint (`keep`, `doors`,
`doorClear`, which `PartsBuilding` applies as terrain_model.py does), `height`, `quadCap` and `roofRows`. A build given
the table (`build_world.py --landscape-models`, `-Pkb.landscapeModels`) claims each model's tiles after the buildings
(a building keeps its own) and places it as a parts template (`FittedBuildings.place`): every client builds it from its
own tiles. An outdoor map, on the surface or under the open sky, sets no height limit but a column's block mask (30
blocks; the user's rule, 2026-10-21: only interiors are limited); a door's cell stays walkable, the arch over it decor.

Quads per block, built as the client builds them (`LandscapeModelsQuadsTest`, pod only): 25 of the 29 pilot models
within the budget's 96 in every block; four over it in a few blocks (Route 23's Victory Road front and west band, at
most 100; Route 127's islet, 143, and one block of its islands, 102); none over 256. Means: rock 6 to 30, trees about 71.

## The frame-rate test (the user's idea, 2026-10-21)

A GPU pod near an average PC (an NVIDIA L4, about an RTX 3060's rendering, on 6 server vCPUs), the dev client at
1920 x 1080 full screen under Xvfb and VirtualGL (`vglrun -d egl`), vsync and the frame cap off; `tools/e2e/frame_rate.py`
at the seven picture viewpoints of world LandModels (the 29 models) and of HeightsW6 (the narrowed build, no models),
render distance 16 and 32, meshstats' 30 s of frames once per viewpoint. Mean frame rate, with the models | without:

| Viewpoint | 16 chunks | 32 chunks |
|---|---|---|
| Route 23 | 332 \| 307 | 216 \| 200 |
| Route 10 | 301 \| 331 | 200 \| 193 |
| Blackthorn City | 346 \| 357 | 179 \| 214 |
| Route 26 | 279 \| 253 | 174 \| 133 |
| Route 119 | 337 \| 337 | 179 \| 209 |
| Route 127 | 269 \| 281 | 162 \| 130 |
| Battle Frontier | 341 \| 333 | 111 \| 108 |

No cost the test can see: the differences go both ways and lie within one run's spread (the 1% lows, 26 to 59 fps,
vary as much in either world); a full re-mesh took the same (0.7 to 1.0 s at 16 chunks, 2 to 5 s at 32), the heap 70 MB
more. The heaviest is Route 119's 91 trees: a section of 45,239 quads (11,340 without), over the budget's 30,000 per
section (VOXEL_BLOCKS_PLAN.md section 3.8), Route 26's trees 25,944. Absolute numbers include VirtualGL's frame copy; the
comparison is like for like.


## The rollout

Every outdoor map's rock (faces of 3 cells or more, masses, mouths), in units of touching sections with a
window each (`tools/landscape_sections/units.py`): [LANDSCAPE_ROLLOUT_PLAN.md](LANDSCAPE_ROLLOUT_PLAN.md).

Run 2026-10-22/23 (rock only, all regions):
- **Marked:** all 177 maps left (18 workflows, one agent a map, every `table.py` check clean), 185 with the pilot's.
- **Units and models:** 920 units (Hoenn 504, Johto 317, Kanto 99), every one with a model (26 workflows, the 171 units
  a full server disk cut off rerun); the table (`models_table.py`) holds 1,098 models on 138 maps, no name or section
  twice; the game's table v14 is v13's pins and slopes with these models.
- **The 160-voxel cap (20 blocks):** big Hoenn masses standing at base 0 with ground far above them stop short and stay
  partly buried: Ever Grande's west range and south cliffs (up to 28 blocks short), Mt. Pyre's two flanks, Mt.
  Chimney's east flank, Sootopolis's north crater and grey terraces, Route 112's south-west mountain, Route 115's
  mountain (4 blocks). Needs a higher cap or the masses split into bands with their own bases.
- **Scenes not checked** (the scene runs out of memory at 1.5 GB): Ever Grande u6 and u7, Mt. Chimney u0; the models
  are built.
- **Quads:** 110 models have blocks over the 96-quad budget (819 blocks; Hoenn 730, Kanto 88, Johto 1), the most 153,
  none over 256; the gate is lifted, so they build.
- **Build** (world LandRollout): Kanto, Hoenn and Johto build with their known warnings (0, 7, 3). Hoenn's 597 models
  need a 20 GB heap (`levels_build.sh` `HEAP`; 8 GB runs out) and take its first shape download to 7.5M characters
  (map) and 12.5M (index), over the 64-part budget: the user chose to raise it to 256 parts
  (`build_world.MAX_SHAPE_DOC_PARTS`) over slimming the models.
- **Pictures** (2026-10-23): 14 before/after sheets (Route 115's spot dropped: a trainer's dialogue covered the shots
  after it), each with a fourth panel at the user's ask: the map's art north of the player (what the camera faces),
  the player and the spot's unit boxed. `levels_shoot.sh` starts its own 1280x720 Xvfb: don't start one at another size.

## Levels (the levels pilot, 2026-10-21)

The steps, to repeat this on other maps and games: [LEVELS_PIPELINE.md](LEVELS_PIPELINE.md).

The user's Route 119: "the model is perfect but there should be a sand slope up to a higher level"; both the slope and
the ground after it stood on the floor. Their choices: both halves (the levels given to the modelling agents, and the
walkable ground raised in the build, slopes as ramps), at the models' scale (3 blocks a cell of cliff, 1.5 a tile row;
a cell of stairs or slope climbs 3). `-Pkb.outdoorLevels=<map,...>` (`build_world.py --outdoor-levels`) puts the named
outdoor maps on raised ground (`OutdoorLevels`, `RaisedRegions` outdoors):

- **The drawing decides.** Emerald's elevations are the engine's layers, not heights: Route 119's floor, the top of its
  sand slope and its plateau are all elevation 3, the grass above them 4, and the step the data shows (a strip of
  elevation 0) is plain ground in the drawing. So a map whose slopes and stairs are marked (the models table's
  `slopes`, `tools/landscape_sections/slopes_table.py`: map cells and their way up) climbs where they are, 3 blocks a
  cell; a strip of elevation 0 that no mark names keeps one height where it can, else climbs as a stair. A map not
  marked reads its data as before (Crystal and Blue: the faces between their areas).
- **Soft rules.** Outdoors a rule that disagrees with those before it is skipped and counted
  (`outdoor_levels_skipped`), and the edges it leaves unwalkable are counted (`outdoor_levels_breaks`), instead of
  leaving the whole map flat. Water stands with the bank it touches most; Emerald's waterfalls (`MB_WATERFALL`) part a
  river, so the water above one has its own banks' height. A bridge is a deck at its ends' height (until the two-floor
  cells of W6).
- **In the build:** a raised cell's ground is filled down to the map's floor; a ramp climbs 3 blocks on the step
  colliders' new stages (`LOW3` to a block and a half, then two full blocks under `HIGH3`), its stair art 24 voxels
  tall; each landscape model's rock stands on one level, its lowest cell's (`OutdoorLevels.rigid`).
- **For the agents:** `package.py --levels` (from `OutdoorLevelsDumpTest`, pod only: per map its levels, ramps, cell
  kinds, outcome, skips and breaks) gives each window cell's level, the ramps and each section's `base`; the reference
  shows the levels, and `terrain_model.py --scene` stands the ground at its levels and each model on its base.

My reading of Route 119 (the sand slope, the plateau's two stairs, the east terraces' two stairs): floor 0, plateau
one step (3 blocks), the grass above the slope two (6 blocks), nothing skipped. The cliff models there are 3.5 blocks
tall, so they stop short of the 6-block ground behind them: the agents' remodel.

**The user's marks** (kb-hub's `/levels.html`: each tile's level, slopes as uphill arrows; `kb-hub/data/levels/marks/
<MAP>.json`) are the answer key. On Route 119 the user counts half cells (a tile row, 1.5 blocks: the plateau 2, the
grass above the slope 4), the same scale as the solver's: of the 531 walkable tiles marked, 522 agreed with my reading,
the other 9 a staircase I had missed and the terrace over it. The user's 11 stair arrows are exactly the cells of three
metatiles: the general tileset's grey stairs (175 and 207, plain `NORMAL` behaviour) and the sand slope (232,
`MB_CYCLING_ROAD_PULL_DOWN`: Emerald's Mach Bike slope). So Emerald's drawn stairs come from the jar's table
`floor_kinds/emerald.json` (keyed `<tileset>/<metatile>`) and from that behaviour (`RaisedRegions.gen3Stairs`), marks
on top; 33 Hoenn maps draw those three (Mt. Pyre's outside 39 cells, Route 119 27, Mossdeep 26, the Battle Frontier's
outsides 25 and 23). A drawing can climb more than its stairs are long (the user's "jump from 2 to 4" at the sand
slope's top, marked 1 and 2): the solver gives each stair or slope cell the cell of cliff it climbs, so the two-cell
slope climbs 0 to 3 to 6 blocks.
