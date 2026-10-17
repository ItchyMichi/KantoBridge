# Fitted shapes

Asked for on 2026-10-16: use an open image-to-3D model on a RunPod GPU to choose each unit's shape. A unit is a 2 × 2
cell drawn as a tree, a sign, a statue and so on. The result is an ordinary table of builder settings with no pixels in
it. Each player's own art still supplies the textures.

## 1. Pipeline

| Step | Where | What |
|---|---|---|
| 1. Dump | CPU pod with the volume | `./gradlew shapeFit -Pkb.fitArgs="dump <bundle> <dump.json>"` plans the export as the pack template does and writes every planned unit once per cell: its drawing's shades, its foreground, and today's pick's voxels. The dump is **ROM-derived**: it stays on the pods or under `local/` |
| 2. Sprite to mesh | GPU pod (≥ 24 GB) | `tools/shape_fit/trellis_targets.py <dump.json> <targets.json>` in TRELLIS.2's venv. Each drawing becomes a 512 px prompt with its foreground as alpha, in four styles (§4). Only TRELLIS.2's first stage runs (its 32³ sparse structure) |
| 3. Model to voxels | same script | The sparse structure's surface voxels are filled solid. TRELLIS's frame is z up with the camera along y, and the side facing the viewer is the one whose south view best matches the drawing. That view is stretched onto the drawing's box, and the depth is scaled like the width and centred in the cell. The runs vote (§4), giving a 16 × 16 × 16 target in the unit's frame |
| 4. Voxels to table | CPU pod | `./gradlew shapeFit -Pkb.fitArgs="fit <bundle> <targets.json> <out.json>"` builds every candidate (§3) with the game's own code and gates, in each of the cell's planned contexts, and scores it by voxel IoU against the target, the target moved along the depth so that both have the same mean depth. It keeps the best only where it beats today's pick by `MARGIN` (0.05) and reaches `MIN_IOU` (0.4) |

A cell gets a target only when at least 3 of its 4 runs survive (§4). The table goes to
`fabric-bridge/src/main/resources/kantobridge/fitted_shapes/<game>.json` and is committed. It holds
cell keys (atlas and tile numbers), a class and its parameters, and the IoU. It holds no pixels. `<out>.report.json` (copies in `tools/shape_fit/reports/`)
lists every fitted cell with today's pick, the best fit and both IoUs. That report is the test set's score against
today's pick.

## 2. Use

`Units.plan` looks up a cell that would become a unit and that the profile doesn't pin (`FittedShapes`). Each game has
its own table: Crystal and Emerald share the atlas name `g3q` with other art behind the same numbers. `ShapePlanner.plan`
says which game it plans, and Red reads Blue's table. `./gradlew shapeFit -Pkb.fitArgs="applied <bundle>"` prints the
planner's unit tally with the table on.

- **A class and its parameters** go into the unit's key variant, as a profile pin's would (`g2_can_k0300080402`). Each
  client therefore builds the fitted shape from its own art without the table. The planner tallies these as `fit`.
- **`columns`** keeps no unit. The cell stays what Tier A made it (`fit_columns`).

The profile's own pins win over a fit. FitTool turns the table off (`FittedShapes.setEnabled(false)`) so that today's
pick is the planner's own.

## 3. Candidates

| Builder | Class and parameters |
|---|---|
| Round hull | `cylinder` |
| Stump | `stump` with cap rows 2, 4, 6, 8, 10 |
| Lathe (can) | `can` with cap 0, 3, 6, 9; base 0, 3, 6; height 4–16 in steps of 2; well 0, 4, 8; taper 0–8 in steps of 2 |
| Standees by depth | `billboard` (10 px), `prop` (5), `signpost` (2), `cutout` (1) |
| Column version | `columns` |

A candidate that fails its gates is not a candidate. The gates are hull cover, standee cover and the 96 quads per
block.

## 4. Limits

- Characters (`sprite:`) aren't covered. Their shape is the player's own style choice (thick, carved, rigged), not a
  builder setting the server picks.
- Profile buildings (band tables, two-cell hulls) keep their hand-made table.
- Gen 1 art is in four shades, so the prompt is grey. The fit compares occupancy only.
- TRELLIS.2 is unstable on 16 × 16 pixel art: one prompt may give a flat slab and a near one a full cube, and a run can
  come back as the bare 32³ shell. Each cell is therefore prompted in four styles: grey or tinted (green for foliage,
  stone otherwise), plain or Scale2x-smoothed. Shells are dropped, and the target keeps the voxels at least half the
  kept runs agree on. Only TRELLIS's first stage runs; its 32³ sparse structure is twice the unit's resolution.
- A symmetric drawing leaves the model's front and back ambiguous. Hulls and lathes don't care; a standee's slab is
  centred anyway.

## 5. Buildings

A V7 building template has one setting: its roof rows, the top tile rows of its drawing folded back as roof
(`TemplateRule`; 0 is the column version). Its volume is known, so its target is the model stretched into that box:
`w × 8` wide, its tallest column high and `d × 8` deep (`trellis_targets.py --structs`). The fit tries roof rows 0–6
and keeps the best where it beats today's pick by `MARGIN` and reaches `MIN_IOU`. The planner reads a table's `structs`
section, which maps `<atlas>/<sig>` (the signature the planner gives the template) to `roofRows`. It writes them into
the template entry (its `roofRows` field, part of its signature), so clients need no table. A pack's `struct:` rule
still wins.

**FitTool writes buildings to `structs_proposed`, which the planner doesn't read.** In the first run (2026-10-16), Blue's
fits nearly always chose 1 roof row over the pane heuristic's 3. A model seen from the front can't tell a roof row
from a wall row: a boxier volume matches it better, but it would draw roof tiles on upright walls. A proposal goes
live only when someone moves it to `structs` after looking at it.

## 6. First run (2026-10-16)

Run on an A40 pod (about 8 h at $0.59/hr, including setup and one restart after the fixes in §4) and small CPU pods.
TRELLIS.2 at commit 75fbf01, weights `microsoft/TRELLIS.2-4B`, seed 42.

| Game | Cells | With a target (3+ runs) | In the table | Unit placements re-picked | Left to Tier A | Buildings proposed |
|---|---|---|---|---|---|---|
| Blue | 54 | 25 | 16 | 5,735 | 2 | 5 of 29 |
| Crystal | 85 | 37 | 14 | 1,675 | 94 | 19 of 69 |
| Emerald | 379 | 145 | 75 | 391 | 327 | 10 of 58 |

**No model** (fewer than 3 runs kept): Blue 29 cells and 2 buildings, Crystal 48 and 5, Emerald 234 and 5. They are
listed in `tools/shape_fit/failed/<game>.json` (per item its key, today's pick, and each run's style and why it was
dropped), to rerun through another method later.

Mean voxel IoU against the targets: today's pick 0.27–0.37, the best fit 0.48–0.62. Trees mostly move from the round hull
to the can (a fuller lathe). Statues and machines become thin standees (`cutout`, `signpost`). Many of Emerald's
standees become plain blocks: TRELLIS sees them as boxes. Most cells keep today's pick. Either too few runs agreed
(TRELLIS's shell, or a full cube), or no builder beat today's pick by the margin.

## 7. Upscaler test (2026-10-16)

On 24 cells from the failure lists (8 per game; 4 had 2 kept runs, 2 had 1, 2 had none), each drawing went through
Real-ESRGAN twice at x4 (16 → 64 → 256 px, alpha too) before TRELLIS.2's first stage (`tools/shape_fit/upscale_test.py`;
spandrel; weights `RealESRGAN_x4plus_anime_6B.pth` and `RealESRGAN_x4plus.pth` from the official releases, BSD-3). There
were four runs per cell: the anime and the general model, each grey and tinted.

- **23 of 24 cells got a model** (3 or 4 runs kept, depths varied, one full block). In the first run none of them had
  3. The upscaler turns dithered pixel art into smooth painted shapes, which TRELLIS reads as objects.
- **Only 3 of the 23 beat today's pick by the margin.** The models now exist, but the builders seldom match them better.
  The models are in the user's viewer (kb-hub) to inspect before deciding whether to rerun all failures this way.
- FitTool's report rows gain `fit_voxels` (the best candidate's occupancy) for the viewer.

## 8. Carving and depth (2026-10-17)

The upscaler test's models of the fence (and other tiled pieces) were unusable: misaligned runs voted into noise, and a
repeating tile isn't an object. Two alternatives were tried on the same 24 cells:

- **Carved** (`tools/shape_fit/carve.py`, plain Python): only runs whose south view matches the drawing (view IoU ≥ 0.75,
  at least the best two), each trimmed to the drawing's silhouette. They vote, and drawn pixels left empty get their
  row's median depth. The front then always matches the sprite. It also flags `tiled_x` / `tiled_y` cells, whose
  drawing continues across opposite edges (fences, walls, ledges: 7 of the 24).
- **Depth** (`tools/shape_fit/depth_test.py`): Depth Anything V2 Small (Apache-2.0; the larger versions are
  non-commercial) on the sprite and its four upscaled prompts, averaged per drawn pixel. Each pixel becomes a column
  centred in the cell, from 2 voxels thick (farthest) to the foreground's widest row (nearest). It runs on a CPU.

| Version | Mean IoU, best fit | Mean IoU, today's pick | Cells kept (of 24) |
|---|---|---|---|
| Vote (section 7) | 0.27 | 0.13 | 3 |
| Carved | 0.41 | 0.28 | 6 |
| Depth | 0.53 | 0.36 | 11 |

The depth relief is clean and aligned, but its thickness rule makes a tiled piece such as a fence a thick block. Tiled
cells should get a thin cap (or be left to rules) before a full run.

## 9. The depth run over every failure (2026-10-17)

`tools/shape_fit/depth_all.py` ran section 8's depth version on all 311 cells without a model, on an 8-vCPU CPU pod
(Real-ESRGAN and Depth Anything V2 Small on CPU, about 15–20 s per cell). Repeating tiles get rules instead of the
free-standing thickness:

| Cell | Rule |
|---|---|
| Free-standing | 2 voxels (farthest) up to the foreground's widest row (nearest), as deep as it is wide |
| Tiled across one axis (fence, rail, ledge, post run) | 2–4 voxels: thin as a standee |
| Tiled across both axes (wall or cliff pattern) | no model; today's pick stands |

Then FitTool fitted the builders and `tools/shape_fit/merge_depth.py` added the kept fits to the tables, marked
`"from": "depth"`. A kept fit beat today's pick by `MARGIN` and reached `MIN_IOU`; no key the first run kept is
replaced. The failure lists gained a `depth` entry per cell.

| Game | Failed cells | Free-standing | Tiled, thin | Pattern (no model) | Added to the table | No better fit |
|---|---|---|---|---|---|---|
| Blue | 29 | 17 | 10 | 2 | 18 | 9 |
| Crystal | 48 | 36 | 5 | 7 | 23 | 18 |
| Emerald | 234 | 153 | 67 | 14 | 103 | 117 |

144 of the 311 now have a fitted shape. With both runs' entries, the planner re-picks 6,166 unit placements in Blue
(5,735 before this run), 8,652 in Crystal (1,675) and 5,052 in Emerald (391), and leaves 2, 102 and 486 cells to Tier
A (`FitTool applied`). The full check passed. 144 keep today's pick because no builder beat it, and 23 are patterns left to
rules. The models, fits and both sprites (masked and whole) are in the user's viewer (kb-hub, set `depth-all`).

## 10. Voxel shapes in the table (2026-10-17)

The builders seldom matched the depth models, though the models looked right, so the table can now hold a cell's shape
itself:

- **The entry** `{"class": "voxels", "v": <base64, 16³ bits, index (y * 16 + z) * 16 + x>, "from": ...}`: shape only, no
  pixels. `FittedShapes` reads it and gives it the first 6 bytes of the shape's SHA-256 (each mod 100) as its
  parameters.
- **The key** carries class `voxels` and that hash (`g2_voxels_k…`). Any client finds the shape in the jar's tables by
  cell and hash (`FittedShapes.voxels`), whichever game the key came from. The hash also tells Crystal's and Emerald's
  `g3q` cells apart.
- **The builder** `CellUnits.voxels` paints each voxel with the drawing's pixel in front of it (column x, row 15 − y),
  or the nearest drawn pixel in that row, else in that column. A shape whose block passes 96 quads, or whose cell
  lacks a tile, falls back to the other builders (`Kind.VOXELS` otherwise). The self-check's reference treats it like
  a hull.
- `tools/shape_fit/merge_voxels.py` writes shapes into the tables. The depth run's 288 models went in this way,
  replacing its builder fits; first-run entries are kept. `FitTool applied` also prints what the units became.

## 11. Blockbench (2026-10-17)

- `tools/shape_fit/bbmodel.py` exports a shape to a Blockbench generic model: voxels merged into boxes, 2 units per
  voxel, the south side drawn, the whole sprite as texture by front projection. It imports models back, honouring
  rotations. The round trip is exact.
- Blockbench 5.2.2 desktop ran on a CPU pod under Xvfb, with remote debugging on localhost. Scripts load models and
  render sheets (sprite, front, side, three-quarter and top views) for inspection, and noVNC lets a person watch or
  edit.
- **The MCP plugin** (jasonjgardner/blockbench-mcp-plugin) was tried and uninstalled. It needs access to Blockbench's
  process, and its server includes `risky_eval` (arbitrary code). The agent's safety check refused that, so models are
  driven through the remote-debugging connection by the project's own scripts.
- **Edits** are recorded, not drawn: `tools/shape_fit/voxedit.py` applies ops (`keep_largest`, `remove`, `add`,
  `thickness`, `round`, `shift`, `fill_holes`, `drop`), and `tools/shape_fit/edits/<game>.json` keeps each cell's ops
  and why. The first pass:
  - an automatic clean-up of all 288 shapes: the main front-view shape only (no stray strands from the mask), depth
    holes filled;
  - hand edits from the render sheets, starting with the Pokémon Center computer (a box, not a thin tile) and a Crystal
    rock (the dithered ground removed, rounded).
  The edited shapes replaced the depth models in the tables (`"from": "edited"`).

## 12. Review in the game (2026-10-17)

The user found isolated sprites and models hard to judge, so every kept voxel shape (165: the 63 left from the failure
list first, then the 102 left from the first-run cells) was also seen in its scene:

- `FitTool locate` (`UnitLocations`) gives each planned cell one map that plans it (a surface map first, then the most
  uses), its cell, and a walkable cell beside it facing it.
- `tools/e2e/context_shots.py` runs the game with its story-spot driver on its own Xvfb display and the dev client
  following it on another. Per cell it sends the hidden player onto the stand cell, steps the client one cell back,
  turns it to the unit (first person, HUD hidden) and grabs both displays. 161 of 165 cells were captured; two had no
  walkable cell nearby and two timed out.
- Review sheets per cell, labelled with the review number: the hidden game around it, the client's view at the same
  spot, then the sprite and the model's three-quarter view.

What the scenes changed (`tools/shape_fit/edits/<game>.json` keeps each note, marked "in context"):

- **Potted plants** (Emerald #451–481): the dark top is the leaves, not an opening. The well op came off and they
  stay round.
- **Stone bollards** (#303, #314): a lit top, not an opening. Round, no well.
- **Fence posts** (#302, #315): two posts per cell, so the automatic clean-up (keep the largest shape) had dropped
  one. Both are kept, 4 deep, with the clean-up off for these cells (`no_auto`).
- **Wall posters and emblems** (#14, #25, #30): 2 deep, flush against the wall behind them.
- **Auditorium chairs and stone seats** (#387, #388, #445, #446): boxes, not lumpy hulls.
- **Left to today's pick**: cliff-edge fragments that stood out as stubs (Emerald #352–366, #393), diagonal stair-rail
  slices (#71–74), a rock cluster slice, a wall hook, a pylon slice, a flower shelf slice, a hedge fragment, two pads
  that match nothing in the scene, a bent counter piece and a lump in the water.

After this pass the tables hold 142 voxel shapes: Blue 35, Crystal 30, Emerald 77.
