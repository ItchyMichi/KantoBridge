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
