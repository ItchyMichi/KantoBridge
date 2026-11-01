# Emerald model improvement handoff

**Update, 1 November (evening):** the pass is complete and the user approved the final comparison world. Every original entry has a decision, and the live table is `b91b69d7…`. Every revision is preserved in `local/model-history/emerald-batch-02-claude-final/` (see [MODEL_HISTORY.md](MODEL_HISTORY.md#emerald-full-pass-1-november-2026) and Checkpoint 7 of [the progress record](EMERALD_MODEL_PROGRESS.md)). The text below is history.

Saved on 31 October 2026 so work can resume after clearing conversation context. **The full Emerald improvement pass is unfinished and was paused at the user's request on 1 November, approximately 11:29 UTC.** Read [current batch progress](EMERALD_MODEL_PROGRESS.md) before using the original checkpoints below. The latest exact local snapshot is `local/model-history/emerald-pause-20261101-1129/`;744 original entries remain pending. The remaining sections record the pre-pass state; they are preserved as history. Start by reading [the actual authoring workflow](MODEL_AUTHORING_WORKFLOW.md), then use the progress record for current commands and remaining work.

## User intent and accepted decisions

The user wants every scenery model and complete multi-tile object displayed, spaced apart in a flat Minecraft test world. Each needs centered front and side screenshots beside its reconstructed source sprite. Elevated views were added where they reveal tops and depth. Reconstructing source maps from exported sheets is explicitly allowed; collecting hidden-game screenshots is unnecessary.

The user accepted the Blue Pallet tree, bedroom console, full Blue scenery pass, Emerald Littleroot trees, three Emerald buildings and two Japanese-style Crystal buildings. The next intended scope is **Emerald scenery plus buildings**, followed by user review at the end. Blue's buildings were already satisfactory and were intentionally preserved. This is fitted environment geometry and complete objects; NPCs and Cobblemon species are not added to the scope by the word “models.”

The user also requires the old models to remain available for selective rollback. Preserve both original and improved entries and complete-object membership. A later model swap must not replace unrelated models. Do not revert any accepted pilot merely to start the next pass.

The accepted authoring method is explicit Python geometry plus source-pixel paint, tested in real Minecraft. No Blender/Blockbench/MCP authoring was needed. Those tools remain optional for a difficult object; consult the applicable skills and runtime import path if used. Keep the same visual standard and iterate on actual screenshots.

## Read these first

| File | Purpose |
|---|---|
| `docs/MODEL_AUTHORING_WORKFLOW.md` | Exact scenery/building construction, source reconstruction, merging and visual QA method. |
| `docs/MODEL_GALLERY.md` | Capture/view/sheet commands and preserved comparison evidence. |
| `docs/MODEL_HISTORY.md` | Existing per-model restore interface and archive revisions. |
| `tools/shape_fit/emerald/littleroot_tree.py` | Accepted multi-cell tree geometry and source-local material selection. |
| `tools/shape_fit/emerald/building_trials.py` | Accepted house, lab and Center; use this game's `parts_model.py` for previews. |
| `tools/shape_fit/blue_pass/` | Proven ownership, semantic ledger, guarded merger and final report pattern to adapt. |
| `tools/model_gallery.py`, `tools/model_gallery.ps1` | Catalog, sprite reconstruction, isolated client launch and report validation. |
| `fabric-bridge/src/client/java/dev/kantobridge/client/dev/ModelGallery.java` | Actual Minecraft placement, camera, capture and gallery-only safety guards. |

Read normal repository instructions too. Do not modify `docs/DESIGN.md`, reset unrelated work, change version pins or machine-wide Python/Java settings. The worktree contains substantial uncommitted work from this and other tasks; inspect it before editing. Keep exports, derived images, screenshots, worlds and archives under ignored `local/`; do not commit them. Use existing exported art for this user-authorized comparison workflow, without accessing ROMs or hidden-game saves.

## Exact current checkpoints

The live resources are `fabric-bridge/src/main/resources/kantobridge/fitted_shapes/{blue,emerald,crystal}.json`. On handoff they exactly match the approved gallery snapshots:

| Game | SHA-256 of current fitted table |
|---|---|
| Blue | `53222c138194f382cca00d7786001ac97892bfb01058c912736cb199c2a06c40` |
| Emerald | `7958d43cb8545ae006b3568bb6b86d809c7def79cb128a7789c36016756797d6` |
| Crystal | `390a39cfe65969d0999eb3d272325d05b87c4e574aa72e6c2eba82e19177a422` |

Recheck them rather than assuming nothing changed after this document. An independent edit or selective rollback must be preserved and reconciled.

Emerald's history is:

| Stage | Exact-byte snapshot | SHA-256 |
|---|---|---|
| Original before trials | `local/model-gallery/emerald-trees-baseline.json` | `84bbd903995b9a0aeb4bf57f4262cd69dc7f44f8e7493615dbbc65fc7bb18f61` |
| Trees approved, buildings original | `local/model-gallery/buildings-baseline-emerald.json` | `8a3c79fc6a37db84b6a5fc2ff6dec68d95db9348b1038c07c65831157ea685d8` |
| Trees and three buildings approved | `local/model-gallery/emerald-buildings/fitted-current.json` | `7958d43cb8545ae006b3568bb6b86d809c7def79cb128a7789c36016756797d6` |

The last stage is the baseline for the new full pass. Preserve the accepted ten tree source keys from `littleroot_tree.py::KEYS.values()` (`ROLES` maps their metatile IDs to quadrants) and these three building IDs:

```text
emerald_b000_EM_LITTLEROOT_TOWN
emerald_b002_EM_LITTLEROOT_TOWN
emerald_b003_EM_FORTREE_CITY
```

The separate archive is `local/model-history/2026-10-31/`, with adjacent `2026-10-31.zip` and `.zip.sha256`. It has nine named table revisions across the three games, exact compressed snapshots, per-model indexes and guarded restore commands. Fifteen recovery tests passed; real scratch round trips recovered original and approved tables and complete selected objects without changing any live resource. Evidence: `local/model-history/verification-2026-10-31.json`. This is a local backup, not an off-device copy.

**Archive limitation:** `model_history.py init --archive <new-path>` recreates the fixed October checkpoints. It does not capture the current table or append the future Emerald result. Extend the tool with immutable new revisions and indexes, or create an equivalent separate archive deliberately. Preserve the old revision names/hashes; do not silently redefine `approved` in the October archive.

## Accepted visual evidence

| Directory under `local/model-gallery/` | Completed comparison models | Verified screenshots |
|---|---:|---:|
| `blue-review/` | 560 | 3,345 |
| `emerald-trees/` | 7 | 42 |
| `emerald-buildings/` | 3 | 18 |
| `crystal-buildings/` | 2 | 12 |

All four saved capture reports are complete and their sheet reports have exact coverage, no missing/unexpected/duplicate views. Blue has `review.html`, `review-ledger.json`, `quality-audit.json` and `overview.png`; the pilots have `index.html` and individual/detail sheets. The combined building overview is `local/model-gallery/building-trials/overview.png`.

`blue-review` is the final clean Blue capture. `blue-rollout` is the earlier pass, before the poster correction and capture cleanup. Preserve both, but use the final one as the quality reference.

To view approved Emerald buildings without replacing evidence:

```powershell
.\tools\model_gallery.ps1 -Game emerald -Subset buildings -OutDir local/model-gallery/emerald-buildings -View
```

Use the wrapper to reopen a gallery: its manifest/snapshots provide its geometry. `-View` preserves `capture.json` and writes a separate viewing record. Do not assume any previously launched client or tool session is still running after the context reset; check before starting another.

## Verified Emerald inventory before the full pass

| Inventory | Count |
|---|---:|
| Fitted entries | 4,734 cells: 4,732 voxels and two `columns` entries |
| Current scenery catalog | 4,977 exhibits: 4,732 individual cells and 245 compounds |
| Buildings | 112 templates |
| Exported maps | 519 |
| Distinct exported source cell keys | 9,561 |

The 245 compounds consist of 238 automatic stacks and only seven explicit Littleroot exhibits. This is **not yet a complete, manually audited inventory of Emerald's multi-cell objects**. Identify objects from map context and extend `tools/shape_fit/entities/emerald.json`. The fitted-cell inventory also does not mean every exported terrain cell needs a new fitted model.

Preserve a per-key/per-building decision ledger: semantic label, family, map occurrences, source/assembly identity, changed/retained/added decision and reason. Review all 4,732 existing voxel entries and all 112 buildings; explicitly account for the two non-voxel entries and any justified additions. Existing good models can be retained with a reason. Do not claim full coverage from the current compound count alone.

## Issues to resolve before claiming complete coverage

### Stack cycles and fallback models

Four cells are currently excluded from automatic compound assembly because their links reach a cycle. The last two form the actual two-cell cycle:

```text
g3q/10875-10876-18187-18201
g3q/18199-18200-18187-18201
g3q/18202-18203-18191-18188
g3q/18237-18238-18239-18240
```

Their individual models remain displayed. Reconstruct their map contexts and correct the actual relationship; do not silently exclude them from a claim that every complete object is covered.

Two fitted entries have empty occupancy and currently use fallback rendering:

```text
g3q/10728-10729-19702-11304
g3q/12112-12121-12129-12136
```

The latter also belongs to `emerald_compound_be292bca6465`, which therefore includes an empty fitted section. Review these explicitly; the compound need not be wholly empty.

Three individual keys have no occurrence found by the current export reader:

```text
g3q/10202-10328-10204-10329
g3q/10326-10203-10327-10205
g3q/3656-3656-4546-4546
```

Buildings `emerald_b008_EM_SOOTOPOLIS_CITY` and `emerald_b110_EM_SKY_PILLAR_OUTSIDE` have no exact complete-map occurrence found. Keep them in the catalog and investigate matching/context. Do not invent map locations or assume inferred doors are known.

### Large buildings exceed the current gallery

`ModelGallery.java` currently validates width/depth at most 20 blocks and height at most 40, uses fixed spacing 24 and ten columns, adds pad margins, and culls rendering beyond 100 blocks from an exhibit's centre. These eight buildings exceed its width/depth limit:

| Building ID | Width × depth in blocks |
|---|---:|
| `emerald_b040_EM_EVER_GRANDE_CITY` | 22 × 14 |
| `emerald_b089_EM_BATTLE_FRONTIER_OUTSIDE_WEST` | 26 × 26 |
| `emerald_b090_EM_BATTLE_FRONTIER_OUTSIDE_WEST` | 28 × 18 |
| `emerald_b091_EM_BATTLE_FRONTIER_OUTSIDE_WEST` | 10 × 26 |
| `emerald_b099_EM_BATTLE_FRONTIER_OUTSIDE_EAST` | 34 × 30 |
| `emerald_b103_EM_BATTLE_FRONTIER_OUTSIDE_EAST` | 30 × 22 |
| `emerald_b105_EM_BATTLE_FRONTIER_OUTSIDE_EAST` | 26 × 18 |
| `emerald_b107_EM_BATTLE_FRONTIER_OUTSIDE_EAST` | 22 × 14 |

Extend bounds validation, placement spacing, pads, camera framing and culling together, then capture a largest-building pilot. Raising the bounds limit alone is insufficient. Preserve the marked-world/server guards and the current-building hull gate. A malformed current template can stop a combined capture; fix and review templates in batches instead of allowing silent fallback.

Current scenery fits the bounds gate (maximum 8×6×8 blocks). No existing building exceeds the 30-block carrier-height cap. Resource scale still needs checking: 42,896 scenery fragments and 153,584 building fragments before duplicating baselines; the largest building has 23,800 fragments. The wrapper currently gives the client 4 GB heap.

At today's catalog size, three current views require 14,931 scenery screenshots plus 336 building screenshots. Equal-size before/after sets would require 29,862 plus 672. New compounds/additions will change those counts. Batch authoring/captures with exact coverage, and check runtime, memory and disk consumption before attempting a complete run.

### Full Emerald review tooling is unfinished

`tools/shape_fit/blue_pass/review.py` and `report.py` are Blue-specific. Emerald has the accepted pilot generators and older production scripts, but no equivalent completed full-pass semantic merger/ledger/searchable report. Implement that layer with protected pilots, non-overlapping patch ownership, complete review coverage, preserved interaction/matching metadata, source-hash guards and exact capture provenance.

Some pilot tests assert that only the original trial IDs differ from a pre-trial baseline. Broader accepted changes will require updating that scope assertion to the new audited pass while retaining the pilot geometry and metadata checks. Do not simply delete checks that detect unrelated mutations.

## First commands in a fresh session

Run from `C:\User Apps\Kanto Bridge`. These interpreter/JDK paths existed when recorded; recheck if the environment changes. The old venv executable may reference a removed interpreter, but its packages work with the available Python. Set environment variables only for the current process.

```powershell
$kbPython = 'C:\Users\User\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$kbJdk = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'
$env:PYTHONPATH = Join-Path (Get-Location) 'tools/.venv/Lib/site-packages'

git status --short
& $kbPython tools/model_history.py verify
& $kbPython tools/shape_fit/emerald/littleroot_tree.py --check
& $kbPython tools/shape_fit/emerald/building_trials.py --check
Get-FileHash fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json -Algorithm SHA256
```

Before model edits, create a new exact-byte baseline. This example refuses to overwrite an existing file; if it already exists, verify it and reuse it or choose a new pass name:

```powershell
$kbBaseline = Join-Path (Get-Location) 'local/model-gallery/emerald-full-pass-baseline.json'
[IO.File]::Copy((Join-Path (Get-Location) 'fabric-bridge/src/main/resources/kantobridge/fitted_shapes/emerald.json'), $kbBaseline, $false)
Get-FileHash -LiteralPath $kbBaseline -Algorithm SHA256
```

Also snapshot `tools/shape_fit/entities/emerald.json` before extending it. Create a durable progress/decision record at the start of the pass and update it between batches, so another reset does not lose coverage or unresolved issues.

The following inventory preparations are supported now. They were **not run by this documentation task**, and the folder names are proposed new destinations. Check they are unused before running:

```powershell
.\tools\model_gallery.ps1 -Game emerald -Subset all -Angled `
  -OutDir local/model-gallery/emerald-full-inventory/scenery `
  -Python $kbPython -PrepareOnly

.\tools\model_gallery.ps1 -Game emerald -Subset buildings -Angled `
  -OutDir local/model-gallery/emerald-full-inventory/buildings `
  -Python $kbPython -PrepareOnly
```

Always supply `-Subset`: the wrapper's default is Blue's `pallet-trees`. `all` excludes buildings. Preparation does not mean all-building Minecraft capture is ready; resolve the size/resource issues above first.

For an actual reviewed batch, choose exact IDs using `-Only`, use a new output folder, add `-BaselineTable $kbBaseline -JavaHome $kbJdk`, and omit `-PrepareOnly`. For example, after extending a selected building's generator:

```powershell
.\tools\model_gallery.ps1 -Game emerald -Subset buildings `
  -Only emerald_b008_EM_SOOTOPOLIS_CITY -Angled `
  -BaselineTable $kbBaseline -OutDir local/model-gallery/emerald-pass-b008-v1 `
  -Python $kbPython -JavaHome $kbJdk
```

That is a command template, not a claim that b008 is improved or ready. Select ordinary scenery/compounds with `-Subset all`. `-Map` is optional; omit it for the complete catalog and unresolved matches. It checks complete placement for buildings, but scenery compounds currently only require their source keys to occur somewhere in that map. Inspect the reconstructed map to verify the actual adjacency and layout.

The existing Emerald building generator's authoring loop is:

```powershell
& $kbPython tools/shape_fit/emerald/building_trials.py --preview local/model-gallery/emerald-building-study-v2
& $kbPython tools/shape_fit/emerald/building_trials.py --write-table
& $kbPython tools/shape_fit/emerald/building_trials.py --check
```

These commands operate on the existing three pilot specs. Extend or add a reviewed generator for new IDs; running them unchanged does not improve the remaining buildings. Similarly, `littleroot_tree.py --write-table` only applies its existing ten source keys.

Focused tests currently available:

```powershell
& $kbPython -m pytest -c tools/pytest.ini `
  tools/tests/test_model_gallery.py `
  tools/tests/test_littleroot_tree.py `
  tools/tests/test_emerald_building_trials.py `
  tools/tests/test_model_history.py -q

$env:JAVA_HOME = $kbJdk
Push-Location fabric-bridge
try {
  .\gradlew.bat test --tests '*FittedShapesTest' --tests '*FittedShapesComparisonTest' `
    --tests '*PartsBuildingTest' --tests '*BoxBuildingTest' --tests '*BuildingsTest' --console=plain
  if ($LASTEXITCODE -ne 0) { throw 'Focused Java tests failed.' }
  .\gradlew.bat assemble --console=plain
  if ($LASTEXITCODE -ne 0) { throw 'Mod assembly failed.' }
} finally {
  Pop-Location
}
```

Add meaningful tests for new generators and gallery sizing. A historical full Java run had an unrelated `KbPackReloadTest.modelsReadFitAndStayWithinTheBudget` failure because it expected the intentionally disabled quad-budget rejection; investigate current failures, do not present an old full-suite result as fresh validation. The documentation task rechecked pilot generator consistency and saved capture coverage, not a new full Minecraft run.

## Completion criteria for the Emerald pass

1. Every existing fitted voxel model and building has a semantic review decision; complete objects are identified and displayed, including resolved cycle/fallback cases. Non-voxel entries and additions are explicitly accounted for.
2. Geometry improves recognizable structure and volume, source-local paint avoids background/shadow artifacts, and shared/compound uses are checked for seams and conflicting roles.
3. Accepted pilots and unrelated Blue/Crystal resources remain preserved, except any separately justified and recaptured change.
4. Real Minecraft before/current captures provide front, side and useful angled views, with exact manifest-bound coverage. Every comparison is visually inspected; defects are fixed and recaptured.
5. Focused structural/runtime tests and packaging succeed. Gallery success is not claimed as normal-world collision or a full Hoenn playthrough.
6. Original, pre-pass and final models have an immutable per-model/whole-object restore record. Final sheets, a searchable decision report, and the comparison world are ready for the user's end review.

Suggested next-session instruction:

> Read `docs/EMERALD_MODEL_HANDOFF.md` and `docs/MODEL_AUTHORING_WORKFLOW.md`, then continue the approved model-improvement process for all Emerald scenery, complete objects and buildings. Preserve the accepted pilots and per-model rollback history, and prepare the finished comparisons for my review.
