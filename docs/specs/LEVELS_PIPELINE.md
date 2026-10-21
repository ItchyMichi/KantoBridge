# The outdoor levels pipeline (every game; from the levels pilot, 2026-10-21)

Raised ground on outdoor maps: each walkable cell's level, climbed where the drawing has stairs or slopes, the landscape
models standing on it. This file is the how-to: the steps that took Route 119 from flat ground to the user's marks
(all 535 marked tiles agree), to repeat on any map and in any game. The solver's rules are in
[LANDSCAPE_MODELS.md](LANDSCAPE_MODELS.md) "Levels"; the plan is [HEIGHTS_ALL_GAMES_PLAN.md](HEIGHTS_ALL_GAMES_PLAN.md)
W6. Code: `OutdoorLevels` and `RaisedRegions` (outdoors) in `builder/plan`.

## Units

- **Blocks:** the computer's levels (`OutdoorLevelsDumpTest`, `MapLayout`): blocks over the map's floor, 3 a cell of
  cliff or of stairs (`RaisedRegions.OUTDOOR_RISE`, the models' scale: the user's choice); a ramp climbs its rise across
  its cell.
- **Half cells:** the user's marks: a tile row of cliff, 1.5 blocks, so marks = blocks x 2 / 3 (Route 119's plateau 2,
  the grass above the sand slope 4). The user counts from any ground (Route 119: the camera's); step 3 lines the two up.
- **Voxels:** the models: 8 a block, 1.5 a drawn pixel, so a cell of face is 24 voxels, 3 blocks.
- **Arrows:** the way up, in the drawing's frame (up = north).

## What each game gives the solver

| | Emerald | Crystal | Blue |
|---|---|---|---|
| Areas kept apart by | elevations (the engine's layers, **not** heights) | one-way walls (`G2Cells.oneWay`) | tile pairs (`PairEdges.of`) |
| Drawn stairs and slopes | `floor_kinds/emerald.json` (`<tileset>/<metatile>`), the Mach Bike slope's behaviour (`MB_CYCLING_ROAD_PULL_DOWN`): `RaisedRegions.gen3Stairs` | none outdoors yet (`floor_kinds/crystal.json` is W4's cave stairs, keyed by drawing) | none yet |
| Waterfalls part a river | `MB_WATERFALL` (`gen3Falls`) | not yet | not yet |
| Each cell's tile in the dump (step 4) | `<MAP>_mids.json` | to add | to add |

A map with neither drawn stairs nor marked slopes reads its data alone: the faces between its areas climb a cell each.

## The steps

### 1. The computer's levels (pod)

```
cd /root/kantobridge/fabric-bridge && ./gradlew test --tests dev.kantobridge.builder.plan.OutdoorLevelsDumpTest \
  "-Pkb.outdoorDump=<game>;<export bundle>;<out dir>" "-Pkb.outdoorLevels=<MAP,...>" \
  -Pkb.landscapeModels=<models table> -Pkb.testHeap=10g
```

Once per game (blue, crystal, emerald; the bundle `/workspace/local/minecraft/kantobridge/export/<game>`). Per named map
`<MAP>.json`: w, h, outcome, marked (slopes known), skipped (rules that disagreed), breaks (cells left at an unwalkable
edge), kinds (`.` ground, `~` water, `L` ledge, `#` rock), levels (blocks), max_level, ramps `[x, y, way up, rise]`; for
Emerald also `<MAP>_elev.txt` (the elevations) and `<MAP>_mids.json` (each cell's metatile); `notes_<game>.json`, the
planner's outdoor notes. It also renders every chunk of the named maps as the build does (a gap in the raised ground's
art fails here, not in a two-hour build). Map ids, cells and numbers only: they may leave the pod.

### 2. The marking page (kb-hub `/levels.html`, the user)

`python3 kb-hub/publish_levels.py --solver <dump dir> --source "<what built it>"` (in kb-pyimg:1) crops each map's view
from its art (`kb-hub/work/landscape/art/<MAP>_art.png`, 48 px a cell) and lists it on the page: the window round each
picture spot in `kb-hub/work/landscape/spots.json`, or a view set by hand in the script's `VIEWS`. The user picks a
level (half cells) or an uphill arrow and paints tiles; the page saves to `kb-hub/data/levels/marks/<MAP>.json` (a copy
in `history/` at most every 2 minutes, 60 kept). The 🖥 button lays the computer's levels over the marks, in the same
half cells, a red box where they differ.

What the user found on Route 119: the stairs and the sand slope as arrows, and that the drawing can climb more than its
stairs are long (the "jump from 2 to 4" at the slope's top: each stair or slope cell climbs the cell of cliff beside it).

### 3. Compare

`python3 tools/landscape_sections/compare_marks.py <marks.json> <dump>/<MAP>.json` prints both grids in half cells,
lined up at the offset most cells agree on, and every marked cell (not rock, not an arrow) where they differ.

### 4. Teach the computer what it got wrong

In this order, each with a test where it is code:

1. **A tile the arrows always sit on** is a stair the game's table can name:
   `python3 tools/landscape_sections/stair_tiles.py <marks.json> <dump>/<MAP>.json <dump>/<MAP>_mids.json` lists the
   tiles under the arrows, how many of their cells the user marked as slopes and how many as plain ground. A tile only
   ever marked as a slope goes in `fabric-bridge/src/main/resources/kantobridge/floor_kinds/<game>.json` as
   `"<key>": "stairs_up"` (with the source in its `"source"`); check on the pod how many maps draw it (Emerald's three:
   33 Hoenn maps). A behaviour the engine gives the tile (the Mach Bike slope) goes in the code beside the table.
2. **A rule the solver lacks** (Route 119 taught: rivers flow under bridges, waterfalls part a river, rock stands on its
   nearest ground, a pocket takes its nearest neighbour's height, trees stand per tree): `RaisedRegions` (outdoors),
   with a case in `RaisedRegionsTest` drawn with its `outdoor()` picture helper (`^ v < >` slopes, `B` bridge).
3. **A one-off drawing** no tile or rule covers: the map's marked slopes go in the models table,
   `python3 tools/landscape_sections/slopes_table.py <models table> kb-hub/data/levels/marks <MAP>` (a mark wins over
   the tile table).

Then steps 1 and 3 again, until the marks and the computer agree.

### 5. The packages get the levels

`tools/landscape_sections/package.py ... --levels <dump dir>`: an existing spot package keeps its sections and models
and gains each window cell's level, the ramps, the window's floor and each section's `base` (the level its rock stands
on); its reference shows the levels.

### 6. Remodel what the levels moved (agents)

A model whose base changed, or that stops short of the ground behind it, goes back to a modelling agent with the
package (LANDSCAPE_MODELS.md): its voxels count from its base; a cliff reaches the level above it (Route 119:
`cliff_steps` 62 to 48 voxels over its new base, `cliff_west` to 72). The old specs stay in `specs_before_levels/`.
`terrain_model.py --scene` shows the ground at its levels, each rock model on its base and each tree on its own cell.

### 7. The models table

`tools/landscape_sections/models_table.py ... --slopes kb-hub/data/levels/marks` writes the specs with the marked
slopes; a model whose sections are all trees is flagged `"trees"` (each tree on its own ground; rock models stand whole
on their lowest cell, `OutdoorLevels.rigid`).

### 8. Build and pictures (pod)

After `kb-pod prep`, `tools/landscape_sections/pod/prep_pictures.sh` (Xvfb, Mesa, ffmpeg: after every pod start), then
`TABLE=<models table> OUTDOOR=<MAP,...> REGIONS="hoenn" tools/landscape_sections/pod/levels_build.sh` (world LandLevels,
about 20 minutes a region; ends "ALL DONE") and `tools/landscape_sections/pod/levels_shoot.sh` (the picture spots;
ends "SHOTS DONE"). Phone sheets: `tools/landscape_sections/levels_sheets.py <out> <shots> "<label>=<shots dir>" ...`
(the game, then each build). Pictures and sheets show the user's games: they stay in kb-hub's work folder or on the
volume. Stop the pod after.

### 9. Record

The map's result in LANDSCAPE_MODELS.md "Levels" and a PROGRESS note: marks against the computer, what was taught
(tiles, rules, marked slopes), the remodels, the pictures.

## Where each game stands (2026-10-21)

- **Emerald:** the tile table (the general tileset's grey stairs 175 and 207) and the Mach Bike slope (Route 119's
  sand slope), waterfalls; Route 119 checked against the user's marks, 535 of 535. The Battle Frontier outside east
  has one skipped rule and a break at (39, 32); Route 127's islands stand on the sea.
- **Crystal:** levels from the one-way walls and faces only: Route 26's plateau and a raised area of Blackthorn City,
  not yet checked by marks.
- **Blue:** levels from the tile pairs and faces only: Route 23's and Route 10's raised parts are out of the pictures'
  view; Kanto's test world was built with older rules.

## Open (to expand as they are solved)

1. **Seams between maps.** Each map's levels count from its own floor, and every map's floor is at the surface, so
   where two maps meet their grounds can differ: Route 119's north end stands about 27 blocks over the surface where it
   meets Fortree City.
2. **Crystal's and Blue's stairs.** Steps 2 to 4 on a Crystal and a Blue map: the user marks them, the tiles under the
   arrows become those games' tables (the dump to write each cell's tile key for them first).
3. Smaller: bare ground sides under trees where a level steps down; the Battle Frontier's skipped stair at (39, 33);
   bridges are decks at their ends' height until W6's two-floor cells.
