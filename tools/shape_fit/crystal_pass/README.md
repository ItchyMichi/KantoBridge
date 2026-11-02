# Crystal model pass

The Crystal equivalent of the accepted Blue and Emerald passes ([docs/MODEL_AUTHORING_WORKFLOW.md](../../../docs/MODEL_AUTHORING_WORKFLOW.md)):
every fitted cell (1,238) and building template (107) of `fabric-bridge/src/main/resources/kantobridge/fitted_shapes/crystal.json`
gets a semantic decision, and the ones that need it get explicit, hand-designed geometry painted with source addresses.
Progress and decisions: [docs/CRYSTAL_MODEL_PROGRESS.md](../../../docs/CRYSTAL_MODEL_PROGRESS.md).

## Rules

- **Never read a ROM, a save or Gen1Recomp's data.** The only art is the user's export in
  `local/minecraft/kantobridge/export/crystal/` (read through `tools.model_gallery.CrystalArt`). Every generated image,
  table and preview stays under `local/` (git-ignored). Nothing ROM-derived is committed: modules hold numbers, keys and
  source-rectangle addresses only.
- **Never write the live table.** Modules write `local/model-gallery/crystal-pass-study/<module>/`; only
  `crystal_pass.review --write-table` changes the resource, after Minecraft review.
- The immutable input is `local/model-gallery/crystal-full-pass-baseline.json` (SHA-256 `390a39cf…`, Codex's accepted
  trials included). Codex's two accepted buildings `crystal_b010_G2_ECRUTEAK_CITY` and `crystal_b088_G2_VIOLET_CITY`
  are protected: never change them.
- Preserve every entry's metadata (`on`, `walk`, `seat`, `class`) and every building's `id`, `tiles`, `atlas` and
  `terrain`. A metadata correction (a false vertical stack, a walk flag) is a *proposal*: `Pass.drop_stack()` writes it
  to the module's `amendments.json`; the merge accepts it only once it is copied into the checked-in
  `source_amendments.json` (done by the pass owner, with the evidence).
- Each module owns an explicit, disjoint list of keys/buildings, and gives **every** owned one a ledger row
  (`changed` or `retained`, a semantic label, a family, a concrete reason). `Pass.write(owned=...)` refuses gaps.

## Tools

```powershell
$env:PYTHONPATH = '.'          # from the repository root; the system python has numpy and Pillow
python -m tools.shape_fit.crystal_pass.study group --family TILESET_HOUSE_1   # sprites, current models, maps, collision
python -m tools.shape_fit.crystal_pass.study pixels 1918-1919-1922-1923 ...   # a cell's drawing as text with exact colours
python -m tools.shape_fit.crystal_pass.<module>                               # writes updates/ledger/entities + sheets
python -m tools.shape_fit.crystal_pass.review --module <module> --candidate local/model-gallery/crystal-pass-study/_dry-<module>.json `
    [--proposed local/model-gallery/crystal-pass-study/<module>/amendments.json]                    # guarded dry merge
```

- `study.py families` wrote `crystal-pass-study/families.json`: each key's family is the tileset variant it occurs in
  most. `group` renders `groups/<family>/cells-N.png` (sprite | current model, `h=` its top in voxels, `c=` the exported
  collision codes at its placements, placement count and maps), `map-<MAP>.png` (each map with the studied cells
  numbered) and `cells.json`.
- `common.Pass` collects a module's output; `furniture.py` has the face-aware paint and builders; `building_parts.py`
  the parts helpers (`box`, `profile`, `paint`, `swept_eave`, `hip_and_gable`, `drawing`, `check_spec`).
- `Pass.write()` renders `compare-N.png` (sprite | old model | new model angled | new model from the south),
  `object-<id>.png` for every complete object (drawing | old | new) and, for buildings, `buildings/<id>-{source,view,
  front,east}.png`. **Look at every one** (Read the PNG) and fix what is wrong before reporting.
- `players_house.py` is the reference module (reviewed in Minecraft: `local/model-gallery/crystal-review/players-house-v1/`).
  `claude_qc_buildings.py` is a building reference (a hip-and-gable house and a three-tier pagoda).

## Geometry contract

- Cell arrays are `(y, z, x)`: x east, y up, z south; 16 voxels a cell horizontally (2 blocks), 8 a block. A model is
  16 high (one layer, 2 blocks) unless it needs more: `Pass.cell` accepts up to 240 (15 layers), but **extra layers only
  for SOLID cells of genuinely tall objects** (trees, tall statues, lamp posts), and **never for a cell with `on` or a
  cell another cell stands `on`** (layered units do not stack). Each extra layer reserves 2 more blocks of collision.
- Paint is a source address (`v*16+u`) in **that cell's own 16x16 drawing**. Pick exact source colours
  (`Cell.pick([colour, fallback...], u, v)`): a material list falls back to its next colour when a cell lacks one.
- Gen 2's 3/4 view: in a furniture sprite the upper rows are the top surface and the lower rows the south face. Use
  `top_rect(rect, x, z)` for up faces and `front_rect(rect, x, y)` for south faces; give east/west/north faces a
  material (wood, metal, plaster...), never the black outline, the floor pattern or a cast shadow.
- Model what the object is: parts with real supports (legs, posts, stems), openings carved (`Build.cut`), small
  details in relief (handles, knobs, burners), floor art left on the floor. Do not extrude a sprite, do not turn every
  sprite row into height, do not build a pedestal out of a shadow.
- A multi-cell object is designed once in global coordinates and sliced into its cells (see `players_house.bed()`),
  so joins line up. Register it with `Pass.entity(id, layout, note)` (`kind="stack (model as one)"` for a vertical
  stack, layout rows top to bottom) so the gallery shows it whole.

## Runtime facts that decide models

- **The census decides first.** `census.py` (from FitTool's survey of every map, see docs/CRYSTAL_MODEL_PROGRESS.md)
  says where each key is drawn; the study sheets print it in green (drawn n/m) or red (never drawn: reasons). A key
  never drawn has no runtime effect: retain it, citing the census. In Crystal, counter cells (144, 152) never draw
  their entries (only Emerald has fitted counters), and solid cells Tier A raised and folded (`not_low`) do not either.
- Shared objects: `interiors.py` holds the table system (every table in every family uses it: same height, legs only
  at drawn outer corners), device-on-cabinet stacks, bookcases and stools, and `paint_global` for objects drawn across
  several cells.

- Collision codes (`c=` in the study) are explained by `meanings` in `g2tiles/<variant>.json`. Solid ("wall"): 7, 15,
  18 and 26 (cut trees), 21 and 29 (headbutt trees), 39, 47, 98, 106, 128-132, 136-140, 144-159 (144 and 152 are
  counters). Warps: 112-127 (doors, stairs, ladders, mats). Ledges 160-175, water 32-63 and 192-207, ice 35/43.
  The rest are walkable.
- **A fitted entry on a walkable (WALK) cell is not drawn unless it has `walk` or `seat`.** If every placement of a
  key is walkable and it has neither flag, its model never shows: retain it (reason: "never drawn").
- Warp cells (doors, stairs, ladders, mats: 113-127) are drawn by the planner's own handling: retain. An entry with
  `walk` on a warp cell is the docs/specs/WARP_MODELS.md model, the user's design (stairs up rise in four steps toward
  the wall; stairs down and doors keep the drawn floor behind a two-block doorway frame with a dark opening): keep it
  with `Pass.keep_warp`, never flatten it into a plate or close it with a panel (corrected 2026-11-02: 32 such cells
  re-authored at checkpoints 2-18 were restored, see docs/CRYSTAL_MODEL_PROGRESS.md checkpoint 19).
- `seat` cells are drawn and sat on: keep the seat surface at the existing height (look at the current `h=`).
- A stack: a cell with `on: K` moves on top of the cell south of it **only where that cell's key is K**; its own cell
  then keeps only floor. Check each placement in the map images. Design the top cell to continue the object standing
  on its base (bookcase upper shelves, a monitor on its desk) and drop rows that only show wall or floor. A false stack
  (two halves of something lying flat: a bed, a rug, a table) is proposed for removal with `drop_stack`.
- A key used both where its stack matches and where it does not (or in several roles) must suit every placement; if
  it cannot, retain it with that reason.
- Interior wall rows (the top row of a room): a cell drawn **in place** there replaces part of the back wall, so its
  model must keep the wall closed: a full-depth block (`interiors.wall_relief`: the wall art on its face, mounted or
  carved objects standing a voxel or two proud, windows recessed). A wall-row stack top drawn in place over a base
  *variant* its link does not name should stack there too: add the variant with `Pass.also_on` (the runtime's
  `alsoOn`), as Elm's bookcases, Mr Fuji's bookcases and the Fuchsia statue do. A wall-row "top" that is really just
  the top surface of whatever stands below it is a wall cell (drop its link), as the traditional chest top is.
- Outdoors: trees are single 16x16 cells (crown, trunk, no shadow slab; may take a second layer, up to ~32 high, as
  Emerald's accepted Littleroot tree did at 36); rocks, signs, fences, bollards, lamps, flowers, ledges and water edges
  follow the same rules. Ground-only art (paths, grass, sand) needs no volume.

## Buildings (`Pass.building(id, spec, ...)`)

Same contract as Codex's accepted trials (`tools/shape_fit/crystal/building_trials.py`) and the QC recreation: the
building's drawing (`building_parts.drawing(entry)`, 8 px a quarter tile, one px = one voxel) is the coordinate
system; describe volumes (walls, roofs as `profile` parts with upturned eaves or curved/hipped shapes, doors carved as
real recesses with the door art on their back, windows and signs as separate thin parts, chimneys, posts) and paint
each part with source rectangles. Wall swatches avoid doors and signs; roofs take the roof courses with
`top` laid along the curve. Parts stay inside the drawing; heights and parts are the only fields that change (old
`roofRows`/`roof`/`bands` are dropped by `Pass.building`). Keep the door on the drawing's door cell (the warp). Check
`buildings/<id>-*.png` for every building.

## Building kit (`building_kit.py`)

The pass's buildings come from archetype builders that read each drawing (checkpoints 28-33): `read()` (facade line, door,
windows, signs by Gen 2's standard colours) and `johto()` (Johto towns); `kanto_read()`/`kanto()` (Kanto hip roofs: the
window band as a dormer); `flat_eave()`/`kanto_flat()` (Kanto flat roofs with a parapet; `walls_only`, `roof_only`). Specials
are written in their modules (`buildings_ecruteak.py`: Codex's b010 machiya parameterised, the Burned Tower, the Bell Tower;
`buildings_landmarks.py`). Before authoring a building, check that the runtime applies it (`census/buildings-applied-cp26.json`:
the survey's templates matched against its tiles): three never are. The runtime collides every solid cell of a building's
footprint at the building's full height, whatever the model's shape, and leaves door cells walkable decor: carve the door.

## Output of a module

`updates.json` (`{"cells": {key: entry}, "buildings": {id: entry}}`), `ledger.json` (rows), `entities.json` (complete
objects), `amendments.json` (proposals), the review sheets. The pass owner merges modules into candidate tables
(`batch.py`), captures them in Minecraft (`model_gallery.py`, before = baseline), inspects every comparison, and merges
the accepted ones into the live table with the guarded `review.py --write-table`.
