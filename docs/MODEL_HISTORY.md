# Model history and individual rollback

The improved models remain active. `tools/model_history.py` preserves the earlier fitted tables and lets you restore a single model, a complete multi-part object, or a building without replacing unrelated entries.

The archive defaults to `local/model-history/2026-10-31/`, separate from the comparison galleries. Preserve this folder **and its adjacent portable ZIP and checksum file**. They are local, ignored files; this is not an off-device backup. Keep the galleries too if you want their sprite comparisons and Minecraft screenshots.

## Find and restore a model

Run from the repository root with a working Python interpreter:

```powershell
python tools/model_history.py verify
python tools/model_history.py list --game blue --query bed
```

Use a listed stable exhibit ID, an exact source key, or an exact label. Preview the original Pallet tree restoration:

```powershell
python tools/model_history.py restore --game blue --model 'tilesets.overworld/42-43-58-59' --revision original
```

Restoration previews by default. Read the selected entries and shared uses, then add `--write` to apply that selection:

```powershell
python tools/model_history.py restore --game blue --model 'tilesets.overworld/42-43-58-59' --revision original --write
```

Every write first backs up the complete current table byte-for-byte. It then restores only the selected complete entries, including paint, extra height layers, stacking and walkability metadata. A compound exhibit ID selects all its source keys together. Prefer that ID when judging a multi-tile object; changing one piece alone can leave mismatched joins. Repeat `--model` to select several objects in one operation.

Source keys are shared: changing a model changes every map occurrence of that key and can affect other compound exhibits. The preview exposes those shared uses. A model added during the improvement pass has no original fitted entry; restoring `original` removes its override so the normal fallback can apply.

To put an improvement back, use the same selection with `--revision approved`. Buildings use their stable IDs, for example:

```powershell
python tools/model_history.py restore --game emerald --model emerald_b002_EM_LITTLEROOT_TOWN --revision original
```

## Preserved revisions

| Game | Revision | Contents |
|---|---|---|
| Blue | `original` | Before the Pallet tree and bedroom console pilots: 493 cell entries and 34 buildings. |
| Blue | `tree-approved` | Improved Pallet tree, original console and other models. |
| Blue | `pilots-approved` | Improved tree and console, before the full pass. |
| Blue | `approved` | Full reviewed pass: 319 further cells revised, four added, 497 cell entries total; all 34 buildings unchanged. |
| Emerald | `original` | Before the tree and building trials: 4,734 cell entries and 112 buildings. |
| Emerald | `trees-approved` | Ten improved tree cells, original buildings. |
| Emerald | `approved` | Improved trees and the three reviewed building templates. |
| Crystal | `original` | Before the building trial: 1,238 cell entries and 107 buildings. |
| Crystal | `approved` | The two reviewed Japanese-style building templates; other entries unchanged. |

Blue's cell totals include one non-voxel `columns` entry. `original` goes back before the pilots, whereas `pilots-approved` keeps the approved tree and console when restoring other Blue models.

## Emerald full pass (1 November 2026)

The full Emerald pass has its own archive, so the October archive above keeps its names and bytes: **`local/model-history/emerald-batch-02-claude-final/`**, with the adjacent `.zip` and `.zip.sha256`. Name it with `--archive` for every Emerald command. The live table holds the new models; the archive keeps every earlier version:

| Revision | Contents |
|---|---|
| `original`, `trees-approved`, `approved` | The October checkpoints, byte-identical to the October archive. |
| `pre-pass` | The table before the full pass: the old version of every model the pass changed. |
| `batch-01` … `batch-02-1018` | Codex's earlier pass archive points. |
| `batch-02-pause-1129` | Codex's last table, when the pass was paused. |
| `claude-brazier`, `claude-pyramid-lobby`, `claude-batch-a` | The Claude continuation's intermediate merges. |
| `claude-final` | **The live table:** every new model; the user reviewed the final comparison world on 1 November and approved the models. |
| `capture-<hash>` | The exact tables the comparison galleries captured. |

63 revisions; 6,168 restorable models, objects, room views and buildings; 4,091 differ between `pre-pass` and `claude-final` (`changed_in_pass` in the index; `list` prints only the October `changed` flag).

```powershell
$h = 'local/model-history/emerald-batch-02-claude-final'
python tools/model_history.py verify --archive $h
python tools/model_history.py list --archive $h --game emerald --query EM_TRAINER_HILL_ROOF
# Back to the old model (preview; add --write to apply):
python tools/model_history.py restore --archive $h --game emerald --model 'g3q/10194-10195-10196-10197' --revision pre-pass
# Forward to the new one again:
python tools/model_history.py restore --archive $h --game emerald --model 'g3q/10194-10195-10196-10197' --revision claude-final --write
```

Selectors are the same as above: a source key, a compound object ID, a building ID, or a room view (`emerald_claude_native_<room>`), which selects every source key in that room at once.

## Crystal full pass (2 November 2026)

The full Crystal pass has its own archive, so the October archive keeps its names and bytes:
**`local/model-history/crystal-claude-final/`**, with the adjacent `.zip` and `.zip.sha256`. Name it with `--archive` for
every Crystal command. The live table holds the new models (SHA-256 `26089dca…`); the archive keeps every earlier version:

| Revision | Contents |
|---|---|
| `original`, `approved` | The October checkpoints (before Codex's building trial; with its two accepted buildings), byte-identical. |
| `pre-pass` | The table before the full pass (`crystal-full-pass-baseline.json`): the old version of every model the pass changed. |
| `capture-<hash>` | The exact tables the 40 review galleries captured, one per checkpoint batch (`crystal-review/*`). |
| `claude-final` | **The live table:** every new model; the user reviewed the final comparison world on 2 November and approved the models. |

43 revisions; 1,493 restorable models, objects and buildings; 1,028 differ between `pre-pass` and `claude-final`
(`changed_in_pass`). Every checkpoint's pre-merge table is also kept as `local/model-history/crystal-premerge-<module>/`.

```powershell
$h = 'local/model-history/crystal-claude-final'
python tools/model_history.py verify --archive $h
python tools/model_history.py list --archive $h --game crystal --query b005
# Back to the old model (preview; add --write to apply):
python tools/model_history.py restore --archive $h --game crystal --model crystal_b005_G2_AZALEA_TOWN --revision pre-pass
# Forward to the new one again:
python tools/model_history.py restore --archive $h --game crystal --model crystal_b005_G2_AZALEA_TOWN --revision claude-final --write
```

Selectors are a source key (`g3q/…`), a complete object ID (`crystal_…`, the ids in `tools/shape_fit/entities/crystal.json`)
or a building ID.

## Archive and runtime notes

`python tools/model_history.py init` creates the archive from the preserved source snapshots. `verify` checks archive integrity. `--archive` selects a different archive location; `--table` targets a scratch table for verification or a restoration rehearsal.

Restoring edits the fitted resource table. Rebuild the mod and install the resulting version in the client you use, then rebuild or regenerate the affected world's shapes: existing worlds retain their recorded shape hashes. Restart or reload the relevant client before judging the result. The saved comparison galleries continue to show the revisions they captured.

See [the model gallery workflow](MODEL_GALLERY.md) for the visual evidence and commands to build new comparisons.
