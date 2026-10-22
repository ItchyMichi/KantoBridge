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

Once per game (blue, crystal, emerald; the bundle `/workspace/local/minecraft/kantobridge/export/<game>`); every outdoor
map at once: `tools/landscape_sections/pod/levels_dump.sh` (`OUT`, `OUTDOOR=all`, `CHAIN=1`, `TABLE`). Per named map
`<MAP>.json`: w, h, outcome, marked (slopes known), skipped (rules that disagreed), breaks (cells left at an unwalkable
edge), kinds (`.` ground, `~` water, `L` ledge, `#` rock), levels (blocks), max_level, ramps `[x, y, way up, rise]`; for
Emerald also `<MAP>_elev.txt` (the elevations) and `<MAP>_mids.json` (each cell's metatile); `notes_<game>.json`, the
planner's outdoor notes. It also renders every chunk of the named maps as the build does (a gap in the raised ground's
art fails here, not in a two-hour build). Map ids, cells and numbers only: they may leave the pod.

### 2. The review (an agent; the user's marks only as an answer key)

The user's wish (2026-10-21): the process runs without their input. An agent reviews the computer's levels against the
drawing, as the user did on Route 119:

- `python3 tools/landscape_sections/levels_overlay.py <dump>/<MAP>.json <art> <review>/<MAP> --chunk 20 --blocks` draws
  the computer's levels over the art in chunks (blocks over the view's lowest level, `floor` in `<MAP>_levels.json`:
  3 a cell of cliff, 1 a ledge's drop; `^ v < >` a ramp at its foot's level; a red box a break); with `--plain` the same
  chunks without the numbers. Every outdoor map at once: kb-hub's `work/landscape/pick/render_reviews.py` (a manifest).
- The review agent (prompt: the map, these files, this section) reads every chunk beside the plain art and writes
  `<review>/<MAP>.json`: `{"map", "unit": "blocks", "floor", "pins": [[x, y, n], ...], "slopes": [[x, y, way up], ...],
  "notes"}`: per area the computer has wrong, one of its cells and its right level `n` (blocks over `floor`); the stairs
  and slopes it missed; what it wasn't sure of. Only what is clearly wrong; doubts go in the notes. A pin moves only
  ground the computer already keeps apart (cut off by rock, cliffs or water): on ground that joins the rest of the map it
  would move the whole map, so on a map the computer left flat the stairs and slopes come first (marked, they let the
  computer raise the ground behind them). On Crystal and Blue, which have no stair tables yet, it marks every stair and
  slope it sees: they become those games' tables (step 4). The first run (2026-10-22): every outdoor map of the three
  games, one agent each, five workflows side by side (the user's choice).
- The rules it reads the drawing by (Gen 3's three-quarter view; the same for the others):
  - a cliff face (a band of rock, a cell or two tall) is drawn below the ground it holds up: that ground stands over the
    ground in front of the face by the face's height (a cell of face: 2 half cells, 3 blocks); a side cliff (a strip of
    rock on a raised area's west or east edge) drops toward the side it faces;
  - ground next to ground with no face drawn between them is one level; a house, a tree, a fence or a rock is no face:
    the ground behind it stands with the ground in front;
  - stairs and slopes climb to the higher ground; ledges hop down (one way) and stay on one level for now;
  - the sea is one level, the lowest, behind reefs and round islands too; a pond stands on its terrace; a river above a
    waterfall stands over the water under it;
  - ledges and terraces nobody reaches (a hill's flanks) climb toward the hill's top, each over the one in front of it.
- `python3 tools/landscape_sections/pins_table.py <models table> <review> <MAP>` and `slopes_table.py` (its slopes) put
  them in the models table: the planner sets the ground round each pinned cell, as far as it stands level with it, to
  that level (`OutdoorLevels.pinned`), and climbs the slopes. Then step 1 again and a second look.

The user's own marks (kb-hub `/levels.html`, `publish_levels.py --solver`) stay the answer key for testing the rules
(Route 119's); the user marks only when asked.

### 3. Compare (where the user marked a map)

`python3 tools/landscape_sections/compare_marks.py <marks.json> <dump>/<MAP>.json` prints both grids in half cells,
lined up at the offset most cells agree on, and every marked cell (not rock, not an arrow) where they differ. After any
change to the rules, Route 119 must still agree (524 of 524 level cells).

### 4. Teach the computer what it got wrong

From the review's notes and pins (or the user's marks), in this order, each with a test where it is code; a rule that
changes a map nobody reviewed is checked over every map (dump all, compare before and after: what moved, and why):

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
4. **A game's faces** (where the reviews find fences, posts, trees or houses read as cliffs): `python3
   tools/landscape_sections/face_drawings.py <survey.json> <art dir> <out>` lists every solid drawing in a run the solver
   reads as a face (1 to 3 solid cells between two ground cells) and draws each in its map; the ones that are a cliff's
   face (a rim, a band of rock) go in `floor_kinds/<game>.json` `"faces"`, and only they climb (`RaisedRegions.tableFaces`;
   a game whose table has no `"faces"` keeps every solid cell a face).

With every map reviewed: `python3 tools/landscape_sections/apply_reviews.py <reviews> <dump> <table> <table out>
--stairs <out.json>` puts every review's pins and slopes in the table and lists, for Crystal and Blue, the drawings the
reviews marked as stairs nine cells in ten (at least twice, one way): the candidates for the game's stair table.

**After a change to the rules: which reviews still hold.** Dump every map twice with the new rules (step 1): with the
table less its pins, then `python3 tools/landscape_sections/rules_check.py translate <old dump> <table> <new dump> <table
out>` (a pin is a level in its map's frame: where the new rules move a map's floor, each pin moves with it) and with that
table. `python3 tools/landscape_sections/rules_check.py <old dump> <table> <new dump, no pins> <new dump, pins> --json
<out> --final <table out>` compares, per map, the old levels with their pins (what the review meant) to the new ones:
`same` (the rules didn't move it), `fixed` (the rules alone give what the review meant: its pins are dropped), `pins`
(with its pins it is what the review meant), `look` (neither: a second look, an agent with the new pictures and the first
review's notes). Route 119 must still agree with the user's marks.

**The second look.** The `look` maps' pictures from the new dump with pins (kb-hub's `work/landscape/pick/render_round.py
<dump> r2 <maps.json>`), and beside them `python3 tools/landscape_sections/second_look.py inputs <old dump> <old table>
<new dump, no pins> <new dump, pins> <new table> <maps.json> <review>/{map}/r2 <reviews>`: the pins applied now (in the
pictures' blocks), the levels the first review meant and every cell where the two differ, and the levels with no pins.
The agent's pins and slopes are complete lists (they replace the first review's); `second_look.py try <MAP>_raw.json
<review.json>` shows the levels its pins give before it writes them. `apply_reviews.py <second reviews> <dump> <table>
<table out> --replace` puts them in the table (a map with none loses its old ones).

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
  sand slope), waterfalls, one sea per map; Route 119 checked against the user's marks, 535 of 535. Mossdeep City run
  through every step with no input from the user (2026-10-22): a review agent's 22 pins, a marking agent's 70 sections,
  7 models on the levels, in game from the foot of its stairs (the stairs climb the hill to the Space Center). The Battle Frontier outside east
  has one skipped rule and a break at (39, 32); Route 127's islands stand on the sea.
- **Crystal:** levels from the one-way walls and faces only: Route 26's plateau and a raised area of Blackthorn City,
  not yet checked by marks.
- **Blue:** levels from the tile pairs and faces only: Route 23's and Route 10's raised parts are out of the pictures'
  view; Kanto's test world was built with older rules.

## The first review of every map (2026-10-22)

184 outdoor maps, one agent each (five workflows, the user's choice), against the dump of 0cb1a5e's rules: 112 reviews
with pins (1,185), 37 with slopes (197 cells). What their notes asked the computer to learn, and what it learned (step 4):

- **Fences, posts, trees and houses are no faces** (90 reviews, most of Crystal's and Blue's): the faces tables. Crystal:
  of the 247 solid drawings in a face run, 38 are a cliff's (its rocky rims, dark bands of rock, a mountain's corners);
  Blue: none of 43 (Kanto's raised ground is its tile pairs and ledges).
- **A door cut into a face joins only the ground in front of it** (21: Routes 31, 32, 41, Silver Cave, Blackthorn):
  `RaisedRegions.doorsInFaces`, a warp cell with rock on both sides and ground behind it closed on its north side.
- **A pin stops at the game's walls** (23: Crystal's one-way walls, the doors above), as the solver's areas do.
- **A pin applies on a map the solver left flat** (Routes 126, 129, 132's rock tops).
- **The floor** (31: Cianwood flattened under its mountain, Route 123's descent): the lowest ground is the floor; only a
  pit (an area under the floor along the map's edge, fewer than 24 cells) stands at that edge floor. The chain keeps
  every map's floor at or over the surface (a review's pins may lift a whole map).
- **Crystal's and Blue's stairs:** the drawings the reviews marked (four of Crystal's, the Safari Zone's in Blue).

`rules_check.py` after them (Route 119 still 524 of 524): 90 maps as their reviews left them, 23 right from the rules
alone (their pins dropped: Vermilion, Saffron, Routes 14 to 16 and more, where fences had stood as cliffs), 7 right
with their pins, 65 for a second look (the descents the floor rule shows, Crystal's mountains the doors free).

Not learned then (the second look pins round them where it can): a ledge step (a stair in a ledge row climbing a ledge's
block, Blue and Crystal); Crystal's ledges (its hop cell is the one before the lip, so its drops never apply); water under
a log bridge with its own banks; a cascade parting the sea from a pool (the Battle Frontier, Ever Grande); a raised pier.

## The second look (2026-10-22)

70 maps, one agent each (five workflows): the 68 `look` maps and the two the first round never reached, against the dump
of c9b9d8b's rules with the first round's pins (`levels_r4pin`), each writing complete lists (`reviews_r2/`,
`apply_reviews.py --replace`): 21 fit as they stood, 48 fixed, 1 unsure (Route 130, whose sea stands with its upland 3
blocks over the beach); 52 with pins (623), 39 with slopes (184 cells). With 2f4fd54's rules and those lists: Route 119
524 of 524; of the 25 Hoenn and Blue maps looked at, 22 stand exactly as their pins meant, the other three gained stairs
(Route 110's cycling road climbs its 9 blocks, Cerulean's and Route 7's ledge steps below). What the second look taught:

- **A ledge step climbs a ledge's drop** (25 reviews: Celadon, Pewter, Fuchsia, Viridian, Azalea, Cinnabar, both
  Ceruleans, Mt. Moon's square, Silver Cave, Routes 1 to 4, 6, 7, 9, 10, 17, 22, 24, 25, 28, 43, Blue's Route 7): on
  Crystal's and Blue's maps a stair one cell long set in a row of ledges (beside it, Blue's; beside its top, where
  Crystal's hop cells stand before the lip it cuts) climbs `LEDGE_DROP`, the yard behind it a ledge's height over the
  ground in front (`RaisedRegions.ledgeStep`); the world builds it as one block's two half steps in the cell's upper
  half. Climbing a cell, the ledges beside it couldn't drop (Route 7's 14 breaks). Emerald's ramps beside a ledge
  climb a cell (Safari Zone North's).
- **A stair goes with the ground at its ends** (Route 109's beach chairs, Mt. Pyre's summit, Route 110): a pin can't
  move a stair, so pins that moved both its ends alike left it hanging, and pins that left its ends level left a ring
  of steps round a chair. After the pins each flight follows its ends, or is flat ground where they meet
  (`OutdoorLevels.stairsWithTheirEnds`, `rules_check.pinned` the same); any other is a loose end, which
  `rules_check.py` counts and sends back for a look.

**The third look** (the same day, the 26 maps those two rules moved, `reviews_r3/`, the latest review of each as its
guide): 11 fit as they were, 15 had their pins set to the 1-block steps (Pewter's strip, Route 1's pocket, Route 4's
terraces, Route 9's fields) or re-pinned a stair's end (Route 110's yards, Route 28's terraces). With them: no stair
is left off the ground.

**Blue's plank ledge steps** (436ba8e): the drawing the second look marked on Cerulean City and Route 7 (tilesets.overworld
44-44-60-60 and 57-57-60-60, the plank band where a ledge's lip would be) stands in a row of ledges on all 54 of its
walkable cells on Blue's outdoor maps, so Blue's stair table takes it: 16 maps' yards and terraces 1 block over the
ground in front, as in Crystal's Kanto. A fourth look at the 13 maps it moved (`reviews_r4/`): 2 fit, 10 fixed (their
steps marked, a few pins set to the new levels), 1 unsure (Route 22: its pond joins the corridor, 24 red boxes, for the
water rule below).

Where the levels are still wrong or doubtful, map by map, for a person to check: LEVELS_PROBLEM_AREAS.md.

Not learned yet: Crystal's ledges (above); water standing with its lowest bank (Route 130, Slateport); water under a log
bridge with its own banks; a cascade parting the sea from a pool (Ever Grande, the Safari Zone's south-east lake); a
raised pier; a door at the top of a one-row stair (Sootopolis, the door joins any level so the stair finds no top); a
ledge in a one-way loop (Route 120); a pit's exit ledges dropping two blocks (the Safari Zone's north-east); a cliff
face drawn as two half-cell ledge rims (Lilycove).

## Open (to expand as they are solved)

1. **Seams between maps** (below: the check, the user's choice, the results). Each map's levels count from its own
   floor, and every map's floor is at the surface, so where two maps meet their grounds can differ: Route 119's east exit
   stands 27 blocks over the surface where it meets Fortree City.
2. **Crystal's and Blue's stairs.** Steps 2 to 4 on a Crystal and a Blue map: the user marks them, the tiles under the
   arrows become those games' tables (the dump to write each cell's tile key for them first).
3. Smaller: bare ground sides under trees where a level steps down; the Battle Frontier's skipped stair at (39, 33);
   bridges are decks at their ends' height until W6's two-floor cells.

## Seams between maps (the check, 2026-10-21)

**The check:** `OutdoorLevelsDumpTest` with `all` (`-Pkb.outdoorDump="<game>;<bundle>;<dir>;all"`, a minute or so a game)
dumps every outdoor map's levels and its place in the world; `python3 tools/landscape_sections/seams.py <dir> <game>`
finds every pair of touching cells between two maps (both walkable, or both water), each seam's step today, and the
chain: each map's floor at the height where its neighbour's ground leaves off (the user's wish), the seams with the
most pairs first; a seam left over closes a loop, and what it doesn't close is the loop's leftover. It reports the
seams whose cells don't all meet, and the highest ground over the surface after the chain.

**The user's choice:** chain the maps; where a seam can't meet, a generated route between them (sparingly); the final
heights must stay feasible.

**Results** (the rules of 0cb1a5e):

| Region | Outdoor maps | Seams | Step today | Loops (closed) | Open loops | Ragged seams | Highest ground |
|---|---|---|---|---|---|---|---|
| Hoenn | 67 | 53 | 5 | 5 (3) | Route 118/119 18 blocks; Petalburg/104 3 | 6 | 42 (Mossdeep, its floor 18 up) |
| Johto | 77 | 60 | 9 | 0 | none | 19 | 21 (Route 5) |
| Kanto | 41 | 38 | 11 | 4 (1) | Routes 10/9 9; Celadon/16 6; Lavender/8 6 | 9 | 21 (Cerulean) |

- Route 119 climbs 27 blocks to Fortree; the way back (Routes 120, 121, the sea, 123, 118) comes down 9: Routes 118 and
  123 descend by ledges (84 and 80 ledge cells) that stand flat today. Ledges as drops (W6) should take most of the 18.
- Johto's and Kanto's ragged seams follow from their levels having no stair tables yet (open item 2).
- **Feasible heights:** Minecraft builds to Y 319, 255 blocks over the surface (Y 64); the highest chained ground is 42,
  under 70 with the tallest models on it. The build's surface box (32 blocks over the ground today) grows to fit.
- **Ledges as drops (579036e, the user's "small drops"):** 1 block, applied after every other rule. Kanto's open loops
  9/6/6 -> 5/4/2/2 blocks; Hoenn's ring unchanged: Route 123's ledges can be walked round, so their sides stay level, and
  the 18 blocks more likely lie in Routes 120 and 121's levels (stairs or cliffs the computer misses), for their reviews.
- **What closes a loop left open (the user's question, 2026-10-22: a gradient across the maps, or a generated route):**
  a short gentle slope where the two maps meet: a few open path cells by the join climbing a block a cell (the ramps the
  stairs use), the maps, their models and the hidden game untouched. Not a tilt across whole maps (water, buildings and
  the rigid landscape models need level ground, and the game draws everything flat, so a tilt reads as stray steps), and
  a generated route only as a last resort (it moves maps apart and needs the region crossings' machinery).
- **The chain in the planner** (`MapChain`, `-Pkb.chainLevels=true`, `build_world.py --chain-levels`): after every
  map's levels, the same seams and chain as `seams.py`; each surface map's placement at the surface plus its base (its
  floor where its neighbour's ground leaves off), so everything after it (the void fill, the story cells, the spawn, the
  index the hidden game's moves follow, the shapes) takes the new height; under a lifted map its ground goes down to the
  world's (`WorldPlan`), the void ring round it stands at its floor, and the surface volume grows by the highest base.
  The build's notes: `chain_maps_lifted`, `chain_highest_base`, `chain_seams`, `chain_loops_open` (a detail per open loop:
  its seam and step, where a slope at the join goes). Wild Routes zones keep their own areas; a zone's gate is its town's.
- **A stair at each open join** (`MapChain.joins`, right after the chain, 2026-10-22): the straight corridor each of the
  seam's two maps runs up to it (level ground, rock or the map's edge beside it all the way) climbs from the lower map's
  ground to the higher's, 3 blocks a cell (the last step what is left: a ledge step's 1 or a cave's 2), in the lower
  map's corridor first, out to the seam; the higher map's takes the rest, climbing in from the seam, on a floor lowered
  for it where its own would stand over them (its placement lower, its levels higher by as much, as far as its base
  allows). A loop whose corridors are too short, or with no corridor, stays open. Notes `chain_join_<outcome>` (`stair`,
  `no_corridor`, `level`, `too_short`, `under_the_surface`, `too_high`) and a `chain_join` detail per loop. Both checks
  count a step climbing to a seam at its top. Hoenn's two (the rules of 4362ce0 and the second look's pins): Route 118 |
  119, 21 blocks, seven steps (five in Route 119's corridor, two in Route 118's on a floor 6 lower); Route 118 | 123, 7
  blocks, three (3 in Route 123's corridor, 3 and 1 in Route 118's): all five of Hoenn's loops meet.

