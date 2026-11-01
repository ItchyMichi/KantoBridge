# Sprite and building model authoring workflow

Recorded on 31 October 2026 after the user accepted the Blue scenery pass, Emerald tree and building trials, and Crystal building trials. This is the process actually used for those results. For the next session's state and first actions, read [the Emerald handoff](EMERALD_MODEL_HANDOFF.md). For capture options and rollback, see [Model gallery](MODEL_GALLERY.md) and [Model history](MODEL_HISTORY.md).

## What produced the accepted results

The models were hand-designed in reproducible Python generators. The generators describe recognizable parts, carve openings, and assign source-sprite pixels to their surfaces. They output the mod's existing fitted-cell or building-parts format. Blender, Blockbench and their MCP tools were not used for these accepted models. Those remain options for difficult future objects, but an editor export still needs conversion into the runtime format and verification in Minecraft.

The workflow was: preserve the old table, reconstruct the source drawing and map context, identify the object, author its geometry and paint, validate the change, then compare old and new versions in real Minecraft. Local Python renders helped during authoring; Minecraft captures supplied the final visual evidence. Automatic extrusion or depth estimation was not used as the replacement strategy for the reviewed pass.

## 1. Preserve identity and establish scope

Before editing, keep an exact-byte copy and SHA-256 of the game's fitted table:

`fabric-bridge/src/main/resources/kantobridge/fitted_shapes/<game>.json`

Also preserve the relevant entity definitions in `tools/shape_fit/entities/<game>.json`, the gallery manifest, and the existing comparison evidence. Use a new directory under ignored `local/model-gallery/` for each pass. Preparing an existing output directory replaces its manifest and fitted snapshots; never prepare over accepted evidence or while its capture is running.

The model's identity is its complete atlas/cell key, such as `tilesets.reds_house/14-15-30-31`, or its stable building ID. Identical tile-number suffixes in different atlases are unrelated. A shared cell key affects every map that uses it, so review its occurrences before choosing a shape. Preserve matching, interaction and placement fields unless map evidence demonstrates a specific error.

The October archive contains originals, intermediate approved pilots, and final approved versions for all three games. `tools/model_history.py` can restore a cell, a complete object's source keys, or a building in either direction. Its `init` command is currently a fixed archive recipe for 2026-10-31, **not a general command to append future passes**. Extend the archive workflow for Emerald before publishing a new approved pass; never overwrite the existing archive or its revision names.

## 2. Reconstruct the source reference

Use the existing exported sheets and map layouts in `local/minecraft/kantobridge/export/<game>/`. The user explicitly allowed rebuilding maps from sprite sheets for this task. ROMs, hidden-game saves and hidden-game screen captures are not inputs to this workflow. Generated art and captures remain local and uncommitted.

The reconstruction code is in `tools/model_gallery.py`:

| Game | Reader | Source interpretation |
|---|---|---|
| Blue | `BlueArt` | Tileset/map data, four 8×8 tile IDs per 16×16 cell, and the selected map's palette. |
| Emerald | `EmeraldArt` | `g3tiles`, `g3map`, `g3image`; a quarter ID selects page `id // 1024` and tile `id % 1024`, with 32 tiles per row. Four quarters form a cell. |
| Crystal | `CrystalArt` | Gen 2 map/block variants and full-colour `g2image` pages. |

`cell()`, `drawing()`, `map_cells()` and `map_image()` reconstruct the cell, assembled object and surroundings at native proportions. Emerald and Crystal use `BAKED` as a marker for full-colour exported pixels. Blue's grayscale `source_shades()` helper must not be used to choose Emerald materials.

Inspect the object with its neighbours. Determine which pixels describe the physical object, which describe grass/floor/shadow, and which are perspective projections of a different cell's object. Do not turn a cast shadow into a pedestal or every row of the source drawing into vertical geometry. Use an explicit semantic label and record why each model is changed or retained.

## 3. Author scenery cells and complete objects

The reusable geometry helper is `tools/shape_fit/blue_pass/common.py`. The game's runtime contract is:

| Property | Convention |
|---|---|
| Coordinates | x east, y up, z south; the front faces south, +z. |
| Arrays | `(height, depth, width)`, indexed `(y,z,x)`. |
| Scale | Eight voxels per Minecraft block; one 16×16 source cell spans 2×2 blocks horizontally. |
| Occupancy `v` | Base64 of 512 bytes for a 16³ layer; flattened index `(y*16+z)*16+x`, little bit order. |
| Paint `t` | Base64 of 4,096 bytes; each byte is `source_y*16+source_x` in that cell's own 16×16 drawing. |
| Height `up` | Additional 16-high occupancy/paint layers; at most 15 layers total, 240 voxels. |

Paint stores source addresses, not baked RGB. Each voxel has one source texel shared by its exposed faces. Choose appropriate native material pixels for side, top and back surfaces; a silhouette outline, floor checkerboard or cast-shadow pixel usually does not belong there. Front projection remains useful for lettering, displays, labels and cabinet faces. Floor-like art should project across x/z on the top surface.

`Model.box()`, `ellipsoid()`, `cylinder()` and `put(..., cut=True)` provide explicit solid parts and cuts. `project()` and `shade_uv()` support paint selection. `encode()` copies the original entry, preserves metadata, replaces geometry, and optionally trims empty upper layers. The full Blue pass used explicit per-key registries and semantic families, with a decision for every fitted voxel cell.

For a multi-cell object, design one coherent object in global voxel coordinates first. Slice that result into its 16×16 footprint cells or genuine vertical layers, and remap paint into each slice's own source drawing. This keeps stems, rails, rims, roofs and silhouettes aligned at seams. Do not independently sculpt each tile of one tree or cabinet.

The gallery assembles true `on` stacks and explicit definitions from `tools/shape_fit/entities/<game>.json`. Ordinary layouts occupy the x/z floor; kinds beginning with `stack` use vertical placement. `reference_layout` can show projected source artwork that is not part of the physical footprint. The complete object and its individual cells both need inspection.

Retain `walk`, `seat`, `on`, class and matching data unless the pass explicitly allows an evidenced correction. Blue corrected three false `on` relationships: the Facility bed, Ship bed and a Pokémon Center floor fragment; the exact exceptions are in `blue_pass/review.py::CORRECTED_STACKS`. Do not remove stack flags wholesale. Existing Emerald cyclic links require individual review.

## 4. The accepted scenery examples

| Generator | Construction and correction |
|---|---|
| `tools/shape_fit/pallet_tree.py` | Round, tapered crown and narrow stem from a hand-authored radius profile. Removed the extruded cast-shadow platform and wrapped interior foliage texels around the crown instead of spreading the black 2D outline over its sides. |
| `tools/shape_fit/bedroom_console.py` | Low chamfered shell with feet and air beneath, recessed cartridge opening, raised controls, vents/socket, separate diagonal controller and open cable loop. Interpreted the sprite's vertical dimension as floor depth and excluded floor shadow. |
| `tools/shape_fit/emerald/littleroot_tree.py` | One 32×32 tree, 36 voxels/4.5 blocks high, with six intersecting foliage lobes, trunk, two branches and root collar. Split into ten source-key variants using shared quadrant geometry. Source-local foliage/bark texels exclude grass and turquoise shadow. |
| `tools/shape_fit/blue_pass/outdoors.py` | Explicit rocks, trees, plants, signs, fences, stairs and related scenery; complete trees and true statue/plinth stacks are authored before slicing. |
| `tools/shape_fit/blue_pass/props.py` | Explicit furniture/devices including complete bicycle and transporter geometry, preserving openings and supports. |
| `tools/shape_fit/blue_pass/interiors.py` | Explicit interior families and museum cabinet/specimen assemblies; whole objects split and repainted per cell. Four missing mineral-case rear overrides were added. |

Littleroot's source drawing projects a crown tip into two walkable grass cells north of the solid 2×2 footprint. The gallery includes those pixels through `reference_layout`, without creating floating grass-cell geometry. Seven exhibits cover front/repeating trees, edges, corners and a four-tree grove for seam review.

The first Emerald tree capture showed foliage that was too uniform. The generator added clustered tone variation and revised the sculpt. Bright lime pixels exist in northern source cells but not southern ones, so highlights fade before z=16 to prevent a hard colour seam. This correction was recaptured in Minecraft.

The final Blue review found that `tilesets.lobby/72-73-88-89` had been misidentified as a floating table. Reconstructed map occurrences showed advertisements/posters. It became a grounded shallow plaque with floor pixels excluded and was recaptured. This is why semantic identification and map context remain required after procedural generation.

## 5. Author buildings as meaningful parts

Buildings use the fitted table's `buildings` list. Keep the stable `id`, original quarter-tile drawing, terrain exclusions and matching data. Emerald's trial changed only `height` and `parts` for its selected IDs; Crystal's also removed obsolete roof fields when applying the parts model:

- `tools/shape_fit/emerald/building_trials.py`: `house()`, `lab()`, `center()`, `generate()` and `drawing()`.
- `tools/shape_fit/crystal/building_trials.py`: the Ecruteak house and Sprout Tower specifications.

Reconstruct the complete building drawing. Describe its volumes in that drawing's pixel coordinates: x west/east, z north/south, y up, eight voxels per block. Parts are applied in order; later parts add material or carve earlier parts. Supported forms include boxes, cylinders, gables, hips, domes, cones, barrels and profile roofs. A `profile` gives heights above `y[0]` across the axis perpendicular to `along`; an optional `cross` constrains the other axis by taking the minimum of the two profiles. This supports curved eaves and curved hip roofs.

Use the game's own `parts_model.py` for working previews, for example `tools/shape_fit/emerald/parts_model.py`. The root helper has historical differences. The game-local helper and Java `PartsBuilding` are the relevant reference for the accepted trials.

Each part's `paint` has source-drawing rectangles for `front`, `top`, `side` and `back`. Front art projects onto the south face. Side/back material repeats horizontally at its own scale, with height mapped to the part. Curved roof top paint follows surface distance; round parts can wrap front paint around their visible half, or set `wrap: false` inside `paint`. Choose wall swatches without doorways, signage or grass, then model the windows/sign separately. This avoids repeated front-door textures along an entire side wall.

The accepted changes were:

| Building ID | Specific construction |
|---|---|
| `emerald_b000_EM_LITTLEROOT_TOWN` | Coherent house mass and layered roof, separate windows, recessed door and threshold; removed the strange extra upper-storey appearance. |
| `emerald_b002_EM_LITTLEROOT_TOWN` | Birch's laboratory with appropriate wall material, separate windows, recessed entrance and a genuinely hollow dish with supported receiver. |
| `emerald_b003_EM_FORTREE_CITY` | Curved Pokémon Center roof with a separate square badge to avoid stretching; wall swatch excludes the sign/background; recessed entrance. |
| `crystal_b010_G2_ECRUTEAK_CITY` | Traditional house with curved eaves and raised ridge. |
| `crystal_b088_G2_VIOLET_CITY` | Sprout Tower with three inset storeys and independent overhanging curved roofs. |

Test occupied volume, grounding, clear door recesses, hollow areas, supported details, roof headroom, footprint bounds and source rectangle bounds. Preserve the source identity and verify that only intended building entries changed.

Minecraft must render every current building through its intended hull. The gallery rejects a current template if the build is absent, failed, or its kind is not `hull`; the baseline may retain its old fallback for honest comparison. `template_results` records kind, match and maximum per-block quads. The generic `TemplateRule.MATCH` threshold is a different path. Quad-budget enforcement is currently disabled (`CellUnits.MAX_QUADS` is `Integer.MAX_VALUE`), so do not claim a finite budget is enforced merely because a capture passed.

Older `HAND_MODELS.md` and `BUILDING_PARTS.md` contain earlier-stage constraints such as one-cell height, very few parts, paint-only details or limited iteration rounds. The accepted generators and current runtime support taller layered models and explicit detail geometry. Read their current code before applying an old limit to this workflow.

## 6. Merge deliberately and preserve a review ledger

For Blue, the work was divided into non-overlapping outdoor, prop and interior ownership. Each module emitted patches and a ledger; one merger owned the resource-table write. Every row had a source key, semantic label/family, `changed`, `retained` or `added` decision, and a concrete reason. Retaining a good model was a valid reviewed outcome.

The existing Blue commands, from the repository root, are:

```powershell
python -m tools.shape_fit.blue_pass.outdoors
python -m tools.shape_fit.blue_pass.props
python tools/shape_fit/blue_pass/interiors.py --previews
python tools/shape_fit/blue_pass/review.py `
  --patch local/model-gallery/blue-rollout-study/outdoors/updates.json `
  --ledger local/model-gallery/blue-rollout-study/outdoors/review.json `
  --patch local/model-gallery/blue-rollout-study/props/updates.json `
  --ledger local/model-gallery/blue-rollout-study/props/ledger.json `
  --patch local/model-gallery/blue-rollout-study/interiors/updates.json `
  --ledger local/model-gallery/blue-rollout-study/interiors/review-ledger.json `
  --patch local/model-gallery/blue-rollout-study/interiors/additions.json `
  --ledger local/model-gallery/blue-rollout-study/interiors/additions-ledger.json `
  --check
```

These are a reproduction reference for Blue, not commands to run against Emerald. The first three write local authoring artifacts. The merger's `--check` compares the result with the resource; `--write-table` applies it. `--replace-reviewed` allows an iterative correction only when the current table bytes and baseline hash match the previous report, so independent edits or selective rollbacks cannot be silently replaced.

The merger rejects duplicate ownership, unreviewed cells, contradictory decisions, invalid layer/paint lengths, empty revised layers, unintended metadata changes and changes to the accepted tree/console pilots. It preserves all Blue buildings. An Emerald equivalent does **not** yet exist; build game-specific coverage and merge guards instead of weakening Blue's guards or relabeling its grayscale generators.

## 7. Capture actual Minecraft comparisons

`tools/model_gallery.ps1` prepares references and manifests, runs the development client, captures views, then composes sheets. `fabric-bridge/src/client/java/dev/kantobridge/client/dev/ModelGallery.java` automates the flat world and camera directly. This avoids repetitive computer-use clicks or an external Minecraft MCP dependency. The older mob-capture project suggested by the user is not required to run the resulting workflow.

Use `-Game` explicitly. `-Subset all` selects scenery and complete objects; `-Subset buildings` selects building templates separately. `-Only` accepts exact catalog IDs/labels/cell keys. `-Map` selects map context and palette: it verifies complete placement for buildings, but for scenery compounds it only requires the source keys to occur in that map. Verify their adjacency in the reconstructed map. Do not restrict a game-wide catalog to one town.

Front faces north toward the model's south face; side faces west toward its east face. Bounds determine framing at a consistent field of view. The HUD/labels are hidden, the model is centered, and cameras settle before capture. `-Angled` adds a 30-degree elevated view and detail sheet, particularly useful for floor objects, tops, curved roofs and connected assemblies. Sprite panels use nearest-neighbour scaling without stretching.

The client runs in `local/model-gallery-client/`. Cobblemon spawning is disabled only there, vanilla mob spawning is disabled in the gallery, and non-player entities are removed from the bound gallery world. Safety checks bind cleanup/capture to the exact marked save and Minecraft server instance. Preserve those guards when extending the gallery.

For each pass retain:

- `manifest.json`: identities, layouts, occurrences, geometry hashes and subjects.
- `fitted-current.json`, `fitted-baseline.json`: exact-byte tables used for this capture.
- `capture.json`: completion, manifest hash, view records, camera poses and building results.
- `sprites/`, `screenshots/`, `sheets/`, `contact-sheets/`, `index.html` and optional detail sheets.
- `sheet-report.json`: image hashes, completeness and exact requested-view coverage.
- The per-model decision ledger and visual QA record.

Accept a capture only when its status is complete, its manifest hash matches, and expected/recorded subject-view sets match exactly with no missing, unexpected or duplicate views. Existing PNG files alone are not evidence of a completed current run. Inspect all comparison cards; use contact pages for coverage, then full-size sheets for small details or questionable geometry. Record issues, revise the generator, and recapture corrected geometry before marking it accepted.

Use `-View` to revisit a prepared world without altering its capture evidence; it writes `view-session.json`. Use `-SheetsOnly` to recompose valid captures. Use a fresh output folder when geometry changes.

## 8. Verify and finish the pass

Run focused structural Python tests, relevant Java fitted/building tests, and `assemble` after runtime or resource changes. Geometry tests should check real openings, supports, material choices, seams, height/footprint bounds and preserved interactions; they do not establish visual quality. The gallery is decorative and does not establish normal-world collision or a full playthrough. For example, the visible 4.5-block Littleroot tree still reserves three two-block collision layers in normal worlds.

The Blue final result was 496 voxel cells and 64 complete objects, 560 comparison models and 3,345 verified screenshots including available before companions and three views. It revised 319 cells after the pilots, added four, retained 173, and preserved all 34 buildings. See `local/model-gallery/blue-review/review.html`, `review-ledger.json` and `quality-audit.json` for the accepted result.

The Emerald pilots have 42/42 tree screenshots and 7/7 sheets, plus 18/18 building screenshots and 3/3 sheets. Crystal's building trial has 12/12 screenshots and 2/2 sheets. These counts were rechecked against the saved reports when this document was written. They do not mean the remaining Emerald/Crystal models have been reviewed.

Finish future work with an indexed old/current archive supporting per-model and whole-object restoration, a searchable comparison report, a complete decision ledger, and a gallery the user can open for final review. Keep accepted pilots unchanged unless a new comparison demonstrates a needed correction. Saved normal worlds retain geometry hashes; rebuilding the mod alone does not update already-generated shapes.
