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

Quads per block, built as the client builds them (`LandscapeModelsQuadsTest`, pod only): 24 of the 28 pilot models
within the budget's 96 in every block; four over it in a few blocks (Route 23's Victory Road front and west band, at
most 100; Route 127's islet, 143, and one block of its islands, 102); none over 256. Means: rock 6 to 30, trees about 71.

