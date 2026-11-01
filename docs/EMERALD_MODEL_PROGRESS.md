# Emerald improvement pass — active continuation

## Claude continuation (1 November, from approximately 13:50)

The user asked Claude to take over the unfinished entries with the same process.
Codex's paused agents' ownership of the remaining groups (below) is superseded:
Claude now owns every pending entry. Codex's held candidates and resume records stay
untouched as evidence; any reuse is recorded here.

**Checkpoint 1: Battle Pyramid flame brazier merged.** Generator
`tools/shape_fit/emerald_pass/pyramid_brazier.py` (paint helper
`claude_source_paint.py`), tests `tools/tests/test_emerald_pyramid_brazier.py`,
patch/ledger `local/model-gallery/emerald-pass-study/pyramid-brazier/`. Two stacked
keys changed (`g3q/2480-2481-2482-2483`, `g3q/2469-2458-2470-2471`, `on` kept).
Guarded cumulative merge (all 149 prior patches + this one, called in-process because
the Windows command line is too long): **2,392 changed, 1,712 retained, 164 added,
742 pending**. Live table SHA-256
`abc0c786fd32c06e46d0f55b6a79967a8a1f37d46167fd27826d8e21aeba04da`, byte-identical to
the captured table. Pre-merge snapshot: `local/model-history/claude-premerge-brazier/`.
Capture `local/model-gallery/claude-trial/brazier-gallery-v2/` (3 comparisons, 18/18
views, exact coverage, `visual-qa.json` ready for user review; v1 superseded by a
one-voxel support fix). 45 focused Java tests and `assemble` pass. The user viewed
the side-by-side world (`claude-trial/compare-world`, `-View`).

A QC-only recreation of the Rustboro fountain (`claude_qc_rustboro_fountain.py`) is
**not** integrated; Codex's fountain stays live.

Gallery note: `model_gallery.ps1` always prepares from the live table, so scratch
candidates are prepared with `model_gallery.py prepare --table <scratch>` and captured
with the wrapper's exact runClient arguments. Captures use the full-pass baseline as
"before", like Codex's.

**Checkpoint 2: native-context tooling and the wall-row finding.** New generic Java fixture
`EmeraldClaudeNativeContextTest` plans every study folder holding `claude-native.json`
(rectangles; optional candidate `updates.json`; `keep_collision`) on its real map, original/live/
candidate, asserting all unedited columns identical, and writes `runtime-proof.json`.
`claude_native_register.py <folder>` turns it into hash-bound gallery entities
(`native-entities.json`, plus `gallery-entities.json` = registry + these for `--entities`).
`claude_group_study.py` renders a group's sprites, current models, collisions and annotated maps.
First use: `claude-pyramid/` (no candidate), captured in `claude-trial/pyramid-native-current/`
(4 contexts, exact coverage).

Finding (Pyramid lobby, original = current): the back wall is native, 9 blocks high, but every
fitted cell inside its wall rows (four statue columns: keys 0/5/7, the two grand columns, the arch
springings) is a single 2-block layer, so the room shows 7-block notches instead of statues in
front of a wall, and the columns read as low boxes. The x=3/x=9 statues' lower cells (keys
`2484-2485-2531-2532`, `2541-2542-2535-2543`) are exported WALK0/elevation 0, so the planner draws
nothing there. Stacking head/body onto the pedestal would empty those wall cells (stack tops keep
only floor) and is impossible on the WALK bases. Pending the user's choice of approach.

**Checkpoint 3: Pyramid lobby back wall merged** (user decision 1 November: niches, walk-through
statues where the game walks; sequential pace). `tools/shape_fit/emerald_pass/pyramid_lobby.py`,
tests `tools/tests/test_emerald_pyramid_lobby.py`, patch/ledger/native proof in
`emerald-pass-study/claude-pyramid/`. 17 changed, 3 retained: bay walls and column piers are full
72-voxel walls (no notches), round full-height grand columns, four jackal statues on pedestals in
their niches, sills, stepped pyramids. Keys `2484-2485-2531-2532` and `2541-2542-2535-2543` gain
`walk` through two new `source_amendments.json` metadata entries (WALK0 witnesses).
Runtime change: `Units.walkUnit` now stands a layered walk entry on decor carriers as tall as its
layers (unlayered walk entries unchanged; none had layers); test
`FittedShapesTest.aLayeredWalkEntryStandsTallOnDecorWithoutCollision`. Gallery change:
`model_gallery.py` native contexts accept layered fitted cells (layer suffix checked against the
entry). `claude_native_register.py` refuses a proof planned for an earlier candidate (Gradle does
not rerun the fixture by itself: use `cleanTest`). Ledger: **2,409 changed, 1,715 retained, 164
added, 722 pending**; live table SHA-256
`ab825ea1c939b82c0632ca19d7e5862d31bd8c1759e2e71f471612962d19f3ed` (content-identical to the
captured proof table). Snapshot before merge: `local/model-history/claude-premerge-pyramid/`.
Capture `claude-trial/pyramid-lobby-v2/` (21 comparisons incl. the native back wall, exact
coverage, `visual-qa.json` ready for user review; v1 kept as diagnostic).
Remaining Pyramid keys: stairs (3 keys), reception displays and machine (11), Pyramid-top ladder (2).
Post-merge: 48 focused Java tests (cleanTest), `assemble` and 41 Python tests pass.

**Checkpoint 4 (authored, merger-accepted, NOT yet native-proved, captured or merged; the
user's Pyramid view client held Gradle):** `pyramid_lobby_lower.py` (claude-pyramid-lower: 8
changed, 6 retained), `battle_arena.py` (claude-arena: 33/19), `battle_dome.py` (claude-dome:
77/7), `battle_factory.py` (claude-factory: 69/14); shared builders `claude_furniture.py`.
Cumulative dry-run with all four: 2,596 changed, 1,761 retained, 164 added, 489 pending.
New approach for flat wall art over wall rows (shoji, lift frames, pillars, pipes): hand the cell
back to native Tier A columns, `{"class": "columns", "iou": 1.0}` (the form Codex's two existing
column entries use), through allowlisted metadata amendments; `review.validate_cell` now accepts
that change only with such an amendment and no voxel fields. Columns entries are not "fitted" for
run measurement (`FittedShapes.fittedTiles`), so they take the native wall height and fold art.
To verify first: run `EmeraldClaudeNativeContextTest` (cleanTest) for the four folders, register,
capture, inspect. Objects drawn on WALK0 cells (Arena corridor bonsai, Dome pre-battle table,
Factory consoles) get walk flags per the user's rule; Dome PC cap gets a stack amendment onto the
shared PC body (Codex's Contest precedent). `source_amendments.json` backups before each batch:
`local/model-history/claude-premerge-pyramid/source_amendments.before-*.json`.

**Checkpoint 5: batch A merged** (Pyramid lower lobby, Arena, Dome, Factory, Pike, Frontier
lounges, Ranking Hall, Pyramid-top ledger). Generators `pyramid_lobby_lower.py`, `battle_arena.py`,
`battle_dome.py`, `battle_factory.py`, `battle_pike.py`, `frontier_misc.py`, `ranking_hall.py`,
`pyramid_top.py`; union candidate and native proof `emerald-pass-study/claude-combined-a/` (14
contexts). Capture `claude-trial/batch-a-review/` (236 comparisons, 1,413 views, exact coverage);
`visual-qa.json`: 230 ready, 6 repaired after capture (the Dome stanchions showed two posts: the
clasp's diagonal passed the post test; `claude_furniture.stanchions` now needs a vertical shaft run
of 6). The gallery's stack exhibits of the Ranking Hall chairs float the back a cell over the
seat; at runtime both are WALK seat cells drawn in their own cells (proof), so the chairs are
right. Ledger: **2,674 changed, 1,778 retained, 164 added, 394 pending**; live table SHA-256
`88894f43d43d7ecc30522d51d93354a88db79b64f2c6393297aebc56b57eb7f7`. Snapshot before merge:
`local/model-history/claude-premerge-batch-a/` (table, review report, amendments).
Fixture fix: `EmeraldClaudeNativeContextTest` treats a proof without a candidate hash (Gson drops
nulls) as candidate-free.

**Checkpoint 6: batch B merged; no pending entries remain.** Generators `trainer_hill.py`
(claude-trainer: 22 changed, 9 retained), `trainer_roof.py` (claude-trainer-roof: 95/2),
`magma_hideout.py` (claude-magma: 24/31), `outdoor_terrain.py` (claude-outdoor: 28/183).
- Trainer Hill floors: stairwell frames and the wall box as native columns; the 3x3 control
  console as one object (louvred back unit, desk with the button cross in relief, blue-banded
  edge). The entrance lift and information stand lie inside Codex's frozen reception context
  (`emerald_trainer_hill_furniture_east_counter`, whose current proof hash-binds their entries):
  retained, so that proof stays valid. Codex's frozen `trainer-hill-architecture` package stays
  unintegrated.
- Trainer Hill roof: the tower, faceted roof and building edges as native walls around the deck;
  the deck-end bollards are walk-through posts (walk amendment, WALK0 witnesses).
- Magma Hideout: rock edges and facility plates as native columns (the maze's 2 blocks); the
  machines retained.
- Outdoor terrain, read under the default flat planning (`kantobridge.outdoorLevels` unset):
  `claude-outdoor-current` is an identity census of every placement (104 keys never draw their
  fitted entry: walkable, ledge, water, or solid cells claimed by folds/templates/stacks: retained);
  `claude-outdoor-probe` hands the 107 drawn keys to columns and records native against fitted
  height. Changed: Jagged Pass cliff, rock and mud (14) and the ash plains (2) to columns (2-block
  fitted models in 3-8-block cliffs; a thin slab under a 2-block collision); the Route 112 to Mt
  Chimney cable (6) to columns; the cable car pylons stand 6 blocks tall on their base cells, the
  shaft/crossbar cells as plain ash blocks (blue drawing masked). The ash-covered trees and bushes
  (19) were columns in the first capture and merged into an 8-high wall: reverted, retained.
  Gallery limits met: native contexts need documented ground kinds (windows avoid tall grass and
  raw ledge 160) and no template on the before side (the Mt Chimney window avoids the station).
Capture `claude-trial/batch-b-review/` (30 comparisons, exact coverage); `visual-qa.json`: 28
ready, the two Jagged Pass contexts repaired after capture (trees reverted). Re-proved after the
revert against the batch-A live table (`claude-combined-b/runtime-proof.json`: 166 edited keys
planned, 38,896 unrelated quarters, 448 native quarters reflowed by joined wall runs). Fixture fix:
`EmeraldClaudeNativeContextTest` hashes exactly the candidate bytes it planned (a candidate
rewritten during a run had been recorded as proved). Guarded merge with `require_complete`:
**2,843 changed, 2,003 retained, 164 added, 0 pending**; live table SHA-256
`b91b69d7714afeb7b2120558cc2eab8d2a45c45eb2909d5ec27150370daa1fd7`, content-identical to the
proved candidate. Snapshot before merge: `local/model-history/claude-premerge-batch-b/`.
Every original entry now has a ledger decision; the user's visual inspection of the final
comparison world (`claude-trial/final-review/`) is outstanding, and no approval is inferred.
Finished study folders' requests are renamed `claude-native.done.json` so the fixture no longer
re-plans them; `claude-final/` holds the 28 contexts planned against the live table.

**Final comparison world:** `claude-trial/final-review/` (579 models, 119 compound: every voxel
model the continuation changed, their compounds and the 28 native contexts in `claude-final/`,
planned against the live table; before = the full-pass baseline), opened for viewing on the
second client folder `local/model-gallery-client-b` (the Pyramid lobby view held the first).

**Codex exhibits made stale (relinked in Checkpoint 7):** the post-merge runs' `*Buildings*` filter ran
`EmeraldInteriorSourceProofTest.fivePublishedBuildingsReceiveExactOriginalNativeCompanions`, which
rewrites `building-native-followups/runtime-proof.json` with the live table's hash stamped in.
Five registry exhibits bind that file's whole SHA-256 (`c4313ef2...`): the three Birth Island
trees, Dewford house 1 and the island ferry. The original bytes are in no archive and could not be
reproduced (regenerating with Codex's last live table gives another hash), so the gallery refuses
those exhibits until they are re-bound. Regenerations under different live tables differ only in
`current_table_sha256`; every occurrence (layout, original-baseline fragments) is identical. The
file now holds the regeneration for the live table; the registry is untouched; the final world
leaves the five out (`claude-final/gallery-entities-final.json`). Avoid the `*Buildings*` filter
in post-merge runs, or restore that file afterwards.

**Checkpoint 7: user review, relink and the pass archive.** The user inspected the final
comparison world and approved the models on 1 November ("they all look great").
- The five stale Codex exhibits are relinked (the user's decision). Their `evidence_sha256` in
  `tools/shape_fit/entities/emerald.json` is now
  `fc692b2b355f3a1953abe3d6a7fc56b6a97fcca0d4cb16e773c9e4d5f330c839`, the regenerated
  `building-native-followups/runtime-proof.json`. Nothing else changed. A registry scan finds 292
  bound descriptors and none stale. A buildings gallery
  (`claude-trial/relink-check/`) loads all five against the live table. The pre-relink registry
  and the proof bytes are kept in `local/model-history/claude-relink-20261101/`.
- New immutable archive `local/model-history/emerald-batch-02-claude-final/`, with ZIP and
  checksum, built with `archive.py`'s `create_archive` (prior archive `emerald-batch-02-1018`):
  - 63 revisions: all 48 of 1018, `batch-02-pause-1129`, the three Claude intermediate merges,
    10 `capture-<hash>` gallery tables and the latest `claude-final`, the live table
    `b91b69d7…`;
  - 6,168 indexed identities, 4,091 changed in the pass;
  - 16 gallery manifests: Codex's seven post-1018 galleries, the Claude captures, and
    `emerald-pass-history-claude-final/`, a full-catalog membership snapshot of the live table
    whose entities are the relinked registry plus the 28 room views,
    `claude-final/gallery-entities-relinked.json`.
  Verification: `local/model-history/verification-emerald-batch-02-claude-final.json`.
  A scratch round trip ran through the real restore command (`--write` on a scratch copy):
  - the brazier, a roof post, the whole Trainer Hill 1F room view and Codex's Littleroot
    building went to `pre-pass`, then to `claude-final`;
  - the result equals the live table, an unrelated edit survived, and the live table is
    untouched.
  120 Python tests pass (history, archive, batches, native gallery). `docs/MODEL_HISTORY.md`
  documents the archive and its commands. Existing play worlds keep their recorded shapes until
  they are rebuilt.

**Open items after Checkpoint 7:**
- The outdoor terrain decisions were measured under the default flat outdoor planning. With
  `kantobridge.outdoorLevels` (opt-in) the levels and bridge decks change those maps, so the
  Route 110/111/112/117/118, Jagged Pass and Mt Chimney decisions need re-checking there.
- Route 111's 14 keys (Mirage Tower among them) are retained because the census shows them never
  drawn under the default planner; Mirage Tower's story-state geometry was not touched.
- Codex's frozen `trainer-hill-architecture` package remains unintegrated evidence.
- Gallery limitation: stack exhibits compose a top onto its base even where the runtime draws
  both cells separately (walkable seat cells); judge such pairs in native contexts.

## Paused at user request (1 November, approximately 11:29 UTC)

The user asked for a suitable stopping point and a progress report. Stop new
authoring/captures here. The live table is consistent with its decision ledger:
**2,390 changed, 1,712 retained, 164 added, 744 pending original entries**.
That is 4,102 of the original 4,846 cell/building entries with recorded decisions;
semantic review is not final visual or user approval. Current table SHA-256:
`dae40266c38bfe20590693e65668a4bd3bc89f8a900ad00c02661cdda48ad5be`.
The entity registry contains **1,073** entries. Accepted pilots are preserved;
Blue and Crystal hashes were rechecked unchanged at this checkpoint.

An exact local recovery snapshot of the current table, registry, source pins,
ledger, and unintegrated Ranking Hall generator/test is saved at
`local/model-history/emerald-pause-20261101-1129/`. Its `checkpoint.json` SHA is
`f4b4412438686144166688c256f5de04b2889fffc8102207514ee63647895cd2`.
This snapshot does not redefine the historical approved models. The most recent
full selective-restore archive remains `emerald-batch-02-1018`; refresh that
archive after the next integration checkpoint.

Completed since the earlier checkpoint:

- Slateport15 edge decisions and Shore3 bank corrections were guardedly merged.
- Fortree2 raised-terrain tree templates were corrected using exact carrier
  levels. Two old flat native descriptors were replaced by actual source-level
  contexts. Original patches/proofs remain immutable through ownership overlays.
  Integration manifest SHA `634e3da6a179e87918bd3b1755c8d28b2dae1f2e0b7927d7c5b6356a93596f12`;
  exact adapter compatibility supplement SHA
  `4794eb514437501489d4f9824ad37ad0d52c7adcf4ced10bd1c47c7b131f17f3`.
- Acro3 actual source contexts were registered with full original/current
  catalog checks. They make no new cell decisions. Their manifest SHA is
  `68ee4e6226ee927259750db1204cb6a5211f40c164bac8b7d071be499ed66677`.
  The tallest context is36×41×38; validated gallery bounds now admit it within
  the unchanged163,840-block volume budget and existing footprint/camera limits.
- Scenery23 and24 manual review is complete. Scenery32 manual review is also
  complete:144 comparisons/864 views,108 component observations and36 ready at
  the captured revision. Its QA SHA is
  `464d7ecdf26dae74edbe243a57f49063d0b7c794d0b3e7aba96f718f4d3a17ad`.
  The museum tread repair,22 native berry beds,3 native switches and9 Trainer
  furniture assemblies were visually checked in this batch. No later source
  cell drift was found at QA.
- Buildings-final11 completed11 berry-template comparisons/66 views and normal
  shutdown. All11 are ready at the captured revision, QA SHA
  `3ae36ef945ceff6e5eacb0bc43320ec8962124890718700f948e4ece161bdac6`.

Next work and held candidates:

- **Scenery33 has NOT been launched.** Assemble its selection from Slateport15
  cells+5 diagnostic contexts, Shore3 cells+3 actual contexts, raised Fortree2,
  Acro3 and refreshed Route110 bridge3 when frozen. Fortree2 standalone template
  recapture is also pending. Acro9 earlier retained cells need visual adjudication
  in actual native contexts; they are not part of the744 original pending count.
- Manual visual review remains for scenery25–30 (including28-v2). Earlier repair
  captures in27/29/30 must be inspected before closing their findings.
- Root owns the exact160 pending Factory83, Pike55 and Ranking Hall22 keys in
  `remaining-building-groups.json`. Ranking22 is staged in `frontier-ranking`:
 18 changed/4 retained, one complete case and9 native context requests. Its first
  source fixture passed collision/decor/unrelated-column assertions but correctly
  refused an outdoor-only carrier option on the indoor case. Failure artifacts
  are preserved. The case now uses the ordinary indoor path and original16-voxel
  envelope; this revised candidate is **unrun/unintegrated**. Do not treat the old
  proof as current. A source-exact northwest chair alias is proposed but unpinned.
  Factory source page1 and Pike source page1 were seen; further source study and
  their geometry remain pending. Ranking full-map source and initial local
  candidate views were inspected; final corrected case/short-chair views still
  require inspection.
- Buildings owns Lavaridge142, Mauville108 and Slateport4 crates. Crates remain
  held: the first whole-template fixture changed102 unrelated neighbors. A new
  diagnostic uses existing preserveCarriers with56-voxel carrier envelope and
  unchanged8/24-voxel visible wood, but it has NOT run in Java. Exact resume record:
  `slateport-crates/resume-checkpoint.json`, SHA
  `46a5e2aaaab6419fcd5f96d759fa4e28a60f3a37dc24bfea147cc10a8850d262`.
  Lavaridge machinery source study identifies six2×2 Magma rig patterns at25
  placements; geometry remains pending. Mirage Tower12 cells/whole remain held
  for state-aware matching; the collapsed tower must not reappear. The missing
  mixed sea/sand curb at Slateport(2,56) also remains explicitly pending.
- Bounds owns Trainer128, Arena52, Pyramid38 and Frontier16. Trainer architecture31
  cells/8 whole patterns passed the refreshed14-occurrence source fixture, but
  final XML was removed by the next Gradle clean before archival. Preserve the
  parent successful invocation and matching proof; label the saved earlier-paint
  XML honestly. Follow its final resume record before integrating. Trainer roof97
  and remaining interior groups still need authoring.
  Frozen `trainer-hill-architecture/integration-manifest.json` SHA:
  `a218079239576ef3586ffa0bb2ea0e4faa2b6a2c025b74cb91aa3ecb622c0fe7`.
  `gallery-bounds-resume.json` SHA:
  `ec13774d38c89db4a9f8c6f8bc5aa5c1da3dfceea4e3ba0dda05985462c6a788`.
- Scenery owns Battle Dome84 after explicit transfer. Its initial18-prop generator
  is unexecuted/unvalidated; no candidate output or live mutation is implied.
  Route110 source fixture was refreshed after Shore3 changed one source key;
  preserve the old proof and use the newly frozen package on resumption.
  Bridge3 is frozen but **not registered**: `bridge-native-surfaces/integration-manifest.json`
  SHA `d6f008f78405e0cc6d15a36113d3f23e9d2d7902f0274e731bab713cee88881a`.
  The refreshed runtime proof SHA is
  `abda5727c0c56794e93b25c58ad93863e815de2164bab1690c819655b88f54d2`;
  Java source fixture,10 Python checks and all six original/current catalogs pass.
  Original/current visual columns were unchanged; the refresh corrected provenance.

All authoring/capture work was stopped at this request. Before restarting a Java
fixture/client, recheck process state and coordinate a single Java window. The
11:29 checkpoint contains no running Minecraft capture. Resume from the held
candidate and agent checkpoint records, not by rerunning completed integration
or manual-note scripts.

Root's exact unintegrated candidate record is
`frontier-ranking/resume-checkpoint.json`, SHA
`dcc14959b709f81cfe983e6d87f4cc6a7674794bdbfd0963758b4f7000990c31`.

## Earlier continuation checkpoint (1 November, approximately 11:08)

Trainer Hill furniture33, its9 native contexts, New Mauville3 native switch
contexts, the one-key museum tread repair, and11 exact berry-soil templates
are integrated. Current ledger: **2,376 changed,1,711 retained,164 added,
759 pending original entries**. Table SHA-256:
`e1eeb9e306029ed826ba002ea5b33454cbbf8b280d635245fe6818fa92abf8da`.
Current entity registry has1,062 entries:22 old soil contexts were retired
under exact registry guards and verified source-rectangle containment by
the22 new berry contexts. Their old proofs/captures remain immutable.

Scenery32 completed144 comparisons/864 screenshots and exact sheets,
with normal shutdown after474 drain ticks. It includes75 newly retained
cells,33 Trainer furniture cells,9 Trainer native assemblies,3 switches,
the museum native repair plus its cell, and22 berry native contexts.
Manual review is pending. Berry11 standalone building comparisons remain
to capture. Six Trainer/source and four berry/source Python tests pass;
their XML is in the study directory. Berry actual proof verified all22
beds/67 objects and172,640 map quarters without gameplay changes.

Scenery23 manual review is complete (160 comparisons/960 views).
Scenery24 is in manual review: pages1–36 seen, notes1–128 saved; pages33–36
still need their explicit notes. No visual approval is inferred from tests.

Shore-bank3 correction package is frozen but NOT integrated. Its exact
code guard caught a subsequent stricter native-surface floor-completeness
check before any mutation. Scenery is freezing a validation supplement;
the original manifest/proofs remain untouched. Java window currently
belongs to scenery for raised-Fortree and high-elevation Acro proofs,
after buildings completed its Slateport edge/crate fixture. Root next
captures the11 berry buildings. Agents continue Trainer/Frontier,
town architecture, Acro and source-proven native contexts.

## Earlier continuation checkpoint (1 November, approximately 10:48)

Four Slateport canopy variants and three retained original post cells were
guardedly integrated. Current ledger: **2,342 changed, 1,712 retained, 153 added,
792 pending original entries**; 1,039 entities. Current table SHA-256:
`02860220d5eecbdeeb61f47c0e490395d62320338b7b95f25556f744ed60e013`.
Buildings-final10 completed four comparisons/24 views and clean shutdown;
manual page plus four full-size angles found all four ready at capture.
QA `2d6fb4b136783951124c63fc0b9a173386a3e93fe982269a6c2e3f27d2f7aca4`.
Six canopy/source tests pass (`canopies-validation-1045.xml`).

Scenery22 manual review completed160 comparisons/957 views on40 pages plus
three full-size angles:17 ready,140 components and3 native-comparison findings.
QA `5febecdf945000e020c6e8b0a96eb82f60a36355cefacf52102d061864f53033`.
The New Mauville colored WALK0 switches show gray slabs in fitted isolation;
buildings is proving actual native floors and source story/object precedence.
Scenery23 is in manual review (pages1–12 seen, notes1–32 saved).

Museum stair2 native descriptor was replaced under exact preconditions.
Museum-native01 completed1 comparison/6 views and clean exit. Its actual gray
warp floor is now visible, but orange treads remain unreadable. QA
`ae8589f5d84961682184696341f18be1ae6d831b04e9ba0848d0242a547b6d20`
requires an appearance correction. Buildings owns the narrow repair; warp and
collision must remain unchanged. No appearance approval inferred.

Queue1045 verified51 galleries, reused5,111 current exhibits and selected80
at snapshot02860220 (some were subsequently captured as museum/canopy).
Java is with buildings New Mauville fixture after bounds'11 passing tests:
disjoint carrier composition2, ordinary carriers4, narrow berry3, empty mesh1,
Trainer furniture source1. Scenery's actual22-bed fixture rerun follows. Its
initial failure refused4 berry sites in maps with existing preserving templates;
failure evidence is retained and the disjoint-composition correction does not
weaken per-claim eligibility. Trainer furniture33 package is being frozen.

## Continuation checkpoint (1 November, approximately 10:40)

The live table remains `f259e1cb21897b9623921c72ca87e831a7ff006079a22493f83fee296b49e273`
(2,342 changed, 1,709 retained, 149 added, 795 pending original entries;
1,035 current entity definitions). Fortree-native-02-v2 completed all five
comparisons/30 views; buildings-final09 completed all ten comparisons/60 views.
Both clients exited after a clean 100-tick drain. Manual source/before/current
review plus six full-size details found all fifteen ready at their captured
revision. The native contexts establish support for the five forest templates;
the beach chair, mat and three umbrellas have coherent complete geometry.
QA hashes: Fortree `32b9815ceaa46f167b140581170cd392a17dbc5664055b75a7d5c4709f790afc`;
buildings09 `814964a9e3398979786fc4a6d5ebefcf838d19cd4528e53ce7962fdfbd17e86c`.

Report1018 retry completed against its frozen `57616786...` snapshot: 5,102
current exhibits captured, 3,867 with manual notes, 54 reviewed exhibits lacking
capture and 32,481 screenshots. This predates Fortree/beach integration and is
not a full-pass completion report. The previously described archive and actual
selective-restore verification remain valid. Current Fortree/beach/source tests
passed (10 tests, `fortree-beach-validation-1030.xml`).

Java is now with bounds for berry-soil tests, following two successful buildings
fixtures: Museum2F native stair warp/floor preservation and four new exact blue
market canopy variants. Their descriptors/packages are being frozen. Root is
continuing manual scenery22 review; scenery23–30 and later repairs remain.

## Continuation checkpoint (1 November, approximately 10:31)

Guarded ledger: **2,342 changed, 1,709 retained, 149 added, 795 pending
original entries**, with1,035 current entity definitions. Table SHA-256:
`f259e1cb21897b9623921c72ca87e831a7ff006079a22493f83fee296b49e273`.
New exact-source templates include display boat/lighthouse/Abandoned Ship3,
Route109 beach furniture5 (all38 placements) and Fortree native forest5. Beach
templates preserve WALK height/collision and replace only flat object/shadow
floor pixels with inspected native sand10728/10729. Fortree preserves all38,400
tested quarter collision/decor/floor columns and all unclaimed shape data.

Five obsolete Fortree native crops became stale when their new templates became
active. Gallery preparation correctly rejected them before launching a client.
They are now explicitly retired from the current registry, with an exact source
rectangle containment proof and replacement map in
`fortree-native-repairs/predecessor-retirement.json`. Immutable old proofs,
captures and archives remain intact. Gym/bamboo native contexts stay current.
Fortree-native-02-v2 is capturing5 full replacements; next is buildings-final09
with Fortree5 and beach5. Root owns Java/client until these cleanly exit.

Buildings-final07 (coast2), buildings-final08 (boat/lighthouse/ship3), Origin-north01
(3 comparisons/24 views) and scenery31 (26/156) completed with clean exits.
Manual building07/08 and Origin review is complete; scenery31 has24 ready at
capture and2 native soil-bed appearance findings. Trainer Hill's full native
context closes its isolated-building support concern. QA hashes:

- Buildings07: `45ab32e0c08f38ab4fb308896a1ddff85fb48062fe1ac70d977f17a5465cd7e7`.
- Buildings08: `f366e0b964b21ad2129e5392e1dbe4c5bec9701ffea5fa0b622d71854cf7b003`.
- Origin north: `43f3322069b702aeca9304810a92857fb7925999dda5db60ddf3ac4c157fdd1b`.
- Scenery31: `12e0f77ee52ff790ec8ebfe59289817c4c7f95880501ea51111a5b8457f6bd60`.
- Scenery20: `92737b58e4bbfefed92b811d48163b5c0e378e3457b79ee8656a262d2d41eea2`
  (160 comparisons/924 views,60 ready and100 components).
- Scenery21: `fa4271c8da2f39973158a1cf0f2a369c9d626c3dab6a59ad2d0a1c480d359993`
  (93 comparisons/558 views,76 ready,16 components and1 native-context finding).

The new museum finding is Lilycove Museum2F stairs: native WALK114 warp tread
must be included before judging the apparently empty fitted recess. Buildings
agent owns its native floor proof. Scenery/bounds own22 berry-bed appearances,
Acro and shoreline surfaces; bounds continues Trainer/Frontier authorship.

Archive1018 verified48 immutable revisions and6,060 indexed identities at table
`57616786...`, before Fortree/beach integration. A real scratch selective restore
of Trainer's native context plus the added display boat passed both directions
and preserved an unrelated edit; no live table was changed. Report1018 first
attempt rejected the added north view under an obsolete strict-three-view check.
That check now supports optional north while still requiring all standard views,
exact coverage and north sheet provenance;12 focused report tests pass. The
retry runs against frozen1018 table, catalog and decision ledger, allowing later
work without changing its provenance. It has not yet been reported complete.

Ten focused boat/ship/source tests and six coast/source tests pass. Current
Fortree/beach/source validation is running. Manual scenery22 onward and final
backup/report refresh remain outstanding. No full-pass or user approval inferred.

## Latest continuation checkpoint (1 November, approximately 10:04)

Guarded ledger: **2,342 changed, 1,667 retained, 136 added, 837 pending
original entries**, with1,022 entity definitions. Table SHA-256:
`647042012750d18b4617e04e0339189f71d77a05383a2b8813521d6cee95f2cf`.
Slateport harbor and Route109 Seashore House exact-source templates are integrated,
with30 retained facade decisions. Two additive complete native Trainer Hill and
Route112 station contexts preserve actual floors, excluded terrain and current
template carriers. Their source hashes remain equal to the frozen runtime proof.

Scenery30 completed160 comparisons/924 views with a clean client exit.
Buildings-final06 completed2 comparisons/12 views: the station is ready at the
captured revision; Trainer Hill requires its full native terrain comparison before
judging the apparent lower-door/cliff gaps. QA SHA:
`b68d8051992ab464cd7a9d6e8238eb6cfbd83cc618eed621b29c85bb0229e40a`.
Scenery18 and19 manual reviews are complete, each160 comparisons/960 views.
Their QA hashes are `5c8a799dceb7c07d0b25cbf61ae0153dce425ddf1d5a8035fff5ef2596f4ca42`
and `da0349e0b761609c6423a6c439af06420456146df5999ac17e50d9693dbb5dcf`.
Scenery20 review is in progress; later scenery batches remain unreviewed.

Scenery31 is capturing26 followups: normal queue24 plus full Trainer/station2.
Next captures are the two coast buildings and three Origin north views. Root owns
the Java/client window; agents must coordinate fixture runs after clean exit.
The native-context queue now records pending source identities explicitly rather
than silently omitting them; capture never changes a semantic review decision.

Archive0935 remains the latest verified immutable backup. A real scratch restore
of its two Rusturf native contexts passed in both directions, including absent
fitted entries and preserving an unrelated probe; live resources were untouched.
The current table and newest captures require a later archive/report refresh.
Remaining authorship includes Fortree native foliage, Acro/shoreline context,
Mirage Tower story-state geometry, boat/lighthouse/ship templates, remaining towns,
Trainer Hill interiors and Frontier. No full-pass completion or user approval is
inferred from native proofs or component reviews.

## Latest continuation checkpoint (1 November, approximately 09:45)

Guarded ledger: **2,342 changed, 1,637 retained, 134 added, 867 pending
original entries**, with 1,018 entity definitions. Table SHA-256:
`b79ed9a59eb0e2fd4455b96d374d56804d882639323840c18d4c34869865eeb5`.
Integrated Trick House61, Slateport market11/props3, Lavaridge garden8,
Trainer Hill and Route112 cable station complete templates plus68 retained
facade decisions. The narrow Trainer `preserveTerrain:true` opt-in passes
strict native terrain and current-live regression checks. Residential threshold,
Rusturf ledge and north-facing Origin exit repairs are integrated. Existing
whole-object layouts and old proofs remain archived. Lamp and Sootopolis court
comparison descriptors are corrected. All these latest changes need fresh
Minecraft visual review; native proof does not establish appearance approval.

Scenery29 completed81 comparisons/486 views and buildings-final05 completed22
comparisons/132 views, both with clean client exits. Manual buildings05 review
is complete, including the four repaired generic stairs and five original native
companions. Scenery17 review is complete:141 comparisons/843 views,130 ready
at capture,7 components and4 findings (lamp, two threshold wholes, court).
QA SHA `5ae74fbddc6b4b87bde420b5269eff69ca4b8360986abe545f5755c78b1298fb`.
Scenery18 manual review is in progress;19 onward remain outstanding.

Archive0935 is verified:44 immutable revisions and6,043 indexed identities,
with portable ZIP/checksum. It captures `b7ee2d...`, before the latest repairs
and two new buildings. Native source terrain without fitted overrides now has
explicit proof-bound archive membership;15 archive/restore tests pass.
Report0935 is complete:4,914 current exhibits captured,3,270 manual review
records,74 reviewed without capture and31,335 screenshots. This is progress,
not full-pass completion or user approval.

Sixteen focused repair/source/building tests pass. The additional north camera
is compiled and tested for the Origin approach; it records distinct north views.
Queue `capture-queue-1000` is being prepared against the current table. Java
fixtures are released; coordinate the next client with agents. Remaining work
includes Fortree native foliage, Acro/shoreline routing, Mirage Tower story-state
geometry, missing source-proven Slateport/Route109/ship templates, Trainer Hill
interiors, Frontier, remaining towns, all outstanding manual QA and final backup.

## Latest continuation checkpoint (1 November, approximately 09:18)

Guarded ledger: **2,279 changed, 1,561 retained, 120 added, 1,006 pending
original entries**, with975 entity definitions. Table SHA-256:
`bceed6c4d2cc3f127fc527252c0345988a545be3411f7b5725fa3e4e15093fe9`.
New integrations include five Rusturf plateau repairs with two actual native
contexts, eight soil-footprint repairs with two complete fitted beds, seven
Lavaridge fence decisions/four complete runs, and four additive Rustboro fence
detail sections. The original full fence descriptors remain intact.

Scenery28's first attempt failed the old64-block bounds gate before capture;
the failure is preserved. Scenery28-v2 completed147 comparisons/864 views, and
Rustboro-fences-large01 completed the two full fences/12 views after a tested
128-block framing change. Both clients drained100 ticks and exited cleanly.
Manual full-fence overview inspection passed framing and continuity; overlapping
detail captures remain required because the longest views show only15-pixel
fence height. Buildings-final05 is now capturing22 comparisons, including the
five newly available island/Dewford native-before companions.

Scenery15 manual review is complete:200 comparisons/1,194 views,193 components
and seven ready at the captured revision, plus three full-size details. QA SHA:
`2c6e2434c73f61e8db36e138a6bc733c70c89bcb64ecbefede6146289e37fd84`.
Scenery16 is also complete:200 comparisons/1,194 views,189 components and11 ready,
plus one full-size support detail. QA SHA:
`94229aac58884a1e526883c68040c68744018dd77311983baa48502e67901498`.
Scenery17 onward still requires manual review. No user approval is inferred.

Trick House61 decisions and soil-perimeter6/22 native contexts have passing
actual-export proofs and are being frozen for integration. Trainer Hill remains
held: adjacent template claims alter34 excluded cliff collision columns; a
narrow source-pinned inference-preservation correction is needed. Mirage Tower,
Fortree foliage and remaining towns/facilities also remain in scope. Archive0839
and report0839 are still the latest verified backups/reports and need refreshing.

## Latest continuation checkpoint (1 November, approximately 08:57)

Guarded ledger: **2,272 changed, 1,561 retained, 120 added, 1,013 pending
original entries**, with 963 entity definitions. Table SHA-256:
`eef858eee0f0be99c48ec1ce00082542e70a627b10c68c6cac568f1aff003b72`.
Since 08:39, integrated five Mauville fence decisions, four floating timber
bridge repairs, two continuous cave corner/cap repairs, the Mystery House1F
stair correction, four exact island-template interior opt-ins and five native
before companions. All ownership replacements preserve earlier frozen files.
The island opt-ins activate three Birth Island trees and the ferry in Birth
Island/Navel Rock, with actual placement/headroom checks and unchanged collision.
Mystery's current native descriptor is rebound to its corrected stair geometry;
the original before proof is preserved. Seven focused island/stair/cave checks pass.

Scenery27 finished 960 views/160 sheets with a clean client exit. Scenery28 is
running the remaining149 selections from queue0839; coordinate Java fixture
work before launching another client. That queue selected326 exhibits with
4,543 exact evidence reuses. Buildings-final05 still needs its17 selections.
Changes after the queue snapshot need a fresh selection after these captures.

Fortree-native01 finished54 views/nine source-proven native contexts. Four are
ready at the captured revision; five foliage contexts require native appearance
corrections: the normal CUBE/ROOF routes still produce tall leaf walls or hide
rounded crowns. Descriptor placement is now proved, but that is not appearance
approval. QA SHA `53e593cac7ad16c7afb39a6e80051e05401d742b069825228df9597c93e38ee9`.
Scenery14 manual inspection covers200 source comparisons/1,194 views:26 ready,
173 components and one floating-bridge correction already merged for recapture.
Scenery15 onward still needs manual review. Full objects remain a separate review
requirement from component slices. Mirage Tower's story-state rendering remains
unresolved; the exact Mauville floor/template comparison adapter is in progress.

Archive0839 is verified:38 immutable revisions,5,980 indexed identities, adjacent
ZIP and checksum, under `local/model-history/emerald-batch-02-0839`. It snapshots
`ed07aacc...`; later integrations are not yet archived there. The combined report
at `local/model-gallery/emerald-combined-review-0839/review.html` also uses that
snapshot:4,553 current exhibits captured,2,538 with manual notes,223 reviewed
without capture. Its build completed; only printing the large returned result
failed on the console's text encoding. Neither archive nor report implies approval.
Agents continue the remaining towns, Trick House, Trainer Hill, Frontier and
source-proven terrain corrections. The full pass is unfinished.

## Latest continuation checkpoint (1 November, approximately 08:39)

Guarded ledger: **2,264 changed, 1,563 retained, 120 added, 1,019 pending
original entries**, with951 entity definitions. Table SHA-256:
`ed07aacc05e2c912220bc4c30ea7298a0a1a8bf0df17d63ceada0bdb32bdbeb1`.
New integrations: two Mauville-area houses and25 covered facade decisions,
17 Mauville deck decisions, three Verdanturf planters, three berry-bed gap
repairs,31 paint-only timber bridge repairs, and nine guarded Fortree native
comparison descriptor replacements. Timber occupancy/metadata are unchanged.
The planter's single false vertical stack was removed with exact source proof;
actual native checks confirmed independent two-carrier planter cells. Focused
source/house checks passed5, dirt/deck3, planter/source4, bridge-material1.

Scenery25 completed930 views/160 sheets and26 completed414 views/69 sheets,
both with clean exits. The old0745 capture queue is consumed. A fresh0839 queue
is being built with current native-fragment signatures and exact image checks.
No client is currently running; bounds owns the Java fixture window.

QA10-wholes-01 completed42 views/seven source-proved native contexts and exited
cleanly. Manual review: six ready, one requires correction (Mystery House1F's
opaque center hides its stair treads). QA SHA:
`2ef27e04f303fa4c742bb8a87c9c08bb05b494bb765804615f32cc9bff5a4f57`.
The four earlier isolated-cell connectivity findings are resolved separately by
the actual native wholes; historical QA remains unchanged. Scenery13 manual
review covers200 source comparisons/1,200 views: six ready,184 components,
ten material followups. QA SHA:
`1b7cb5d350f23243f30c493df0063d109fe57f5255b8c5f34bdf37d7fc9f18ba`.
Seven lower-timber findings are included in the newly merged paint repairs;
three floating-bridge findings require separate source review and correction.
Scenery14 onward still needs manual inspection.

Native-before diagnosis found the three Birth Island trees and island ferry
templates inactive in normal maps because those exports are indoor/mapType8.
Bounds is checking four exact interior opt-ins and room clearance; do not infer
normal-map success from their earlier gallery captures. Dewford house matches.
Mirage Tower remains held: normal source normalization represents its collapsed
story state and a static template would be wrong. The zero-match regression
passes; a story-aware rendering solution is still needed. The native bridge
comparison adapter also needs exact floor and template carriers for full
Mauville bridge/gate contexts. No global matcher or story rules are weakened.

Archive0817 remains the latest verified immutable backup (35 revisions,
5,943 identities); a new archive and combined report refresh are due. Agents
continue Mauville/Lavaridge/Slateport, Trainer Hill/Frontier and environment
followups. The full pass is unfinished; no user approval is inferred.

## Latest continuation checkpoint (1 November, approximately 08:17)

Current guarded ledger: **2,258 changed, 1,524 retained, 118 added, 1,064 pending
original entries**, with 914 entity definitions. Table SHA-256:
`72a7ef115d26596a132f2f74271965ffe01af2bcb2c65c410fcbffe90e4f7636`.
New integrations include six cave-crystal repairs, eight exact ladder templates
covering21 source occurrences, four corrected interior stair templates, Mystery
House13 decisions, and three Mauville-area buildings with17 covered source cells.
Eight source/Mystery/gate checks passed; five source/ladder/crystal checks passed.
Blue and Crystal full table hashes were rechecked unchanged at08:05.

Scenery23 and24 completed960/954 views and160 sheets each, with clean client exits.
Both exercised the gallery-only generation drain successfully:100 quiet server
ticks, zero pending generation/save dependencies, then normal shutdown. Queue0745
all-04/all-05 remain for scenery25/26. Bounds currently has the Java fixture window;
coordinate before another client launch. Building-final04 manual review covers all
43 models: root3, Route1042, scenery38. Four of the38 failed for missing stair
treads; corrected geometry is merged and requires a new Minecraft comparison.

Scenery11 manual QA is complete: seven preserved Littleroot wholes and five
components; QA SHA `b3546e27ef20c49e0992775c3f6739f44ffe404b706a96b487fa83e7956de1f1`.
Scenery12's remaining191 comparisons have explicit manual notes and1,047 inspected
views:101 ready at the captured revision,82 components,8 followups. QA SHA:
`e4d0231be490151ab02bd5f06ccbe5aaab1fcc0ef68b6aeedef793d305f8957e`.
The earlier nine school-whole QA is preserved separately. Two bridge comparisons
retain blue water paint on their underside, already assigned to scenery. Six
Fortree foliage descriptors require native placement proof: the gym sign cap is
beside its base, and projected tree portions may require stacking. Do not repair
fitted geometry merely to compensate for a gallery descriptor. Three bamboo
descriptors already have explicit placements and are diagnostic-only.

QA10 native routing proved nine occurrences; seven exact comparison contexts are
needed because the four ship-miniature occurrences have three distinct native
fragment layouts. Bounds is extending current-native gallery descriptors with
source-cell and overlapping-template hashes, strict provenance and report/archive
support. Its first29 tests passed. The seven-entity proposal was registered later
in this checkpoint, as recorded below.

The combined report completed against3db7a05e:4,062 current captured exhibits,
2,139 manual-note entries,504 reviewed entries lacking current captures. It predates
new QA and integrations. Archive0817 completed and verified35 immutable Emerald
revisions and5,943 indexed identities from table72a7ef11, inheriting every0740
revision and adding captures22-24/final04. Its portable ZIP is local. Seven QA10
native-context entities were subsequently registered (921 total); their next
capture/archive remains due. The adapter passed29 native and20 report/archive tests.
Buildings-final03's remaining12 comparisons passed current-geometry visual review,
bound to57 views and12 detail sheets (SHA
`ad20a7d720cfc0541417d07b6b7c063bdb034f979fc066e00b94f2d818a235f7`).
Five additions still need original native-before companions and recapture; source
fixture work is queued. Agents continue remaining towns, Trainer Hill/Frontier and environment
followups. All-original building decisions are accounted for; the full pass is
still unfinished and no new user approval is inferred.

## Latest continuation checkpoint (1 November, approximately 07:58)

The guarded table is now **2,245 changed, 1,507 retained, 107 added, 1,094 pending
original entries**, with895 entity definitions. SHA-256:
`3db7a05ed89f956e4138ff37f16dce6ea86ed45f187173d8e9bcca7ebd0231ed`.
Rustboro100 is complete after its31 connected rail sections. New work also includes
Mauville10 bicycle/timber entries, Pretty Petal27 originals plus two missing picture
cells, and23 cave-mass corrections. Pretty Petal has16 complete source-bound native
comparisons and13 individually pinned metadata amendments. Global exact-source and
Pretty Petal checks passed5 tests; cave/Rustboro rail/Mauville tests passed8.

The0740 archive verified31 immutable Emerald revisions and5,889 indexed model
identities with a portable ZIP. Table snapshot844e686a predates Rustboro31, cave23,
Pretty Petal and Mauville10; these newer changes still need the next archive.

Queue0745 contains752 new/unmatched exhibits (709scenery,43buildings), reuses3,883
current exhibits and verifies30 earlier galleries. `buildings-final-04` completed
258views/43sheets but needed exact-client47956 shutdown after the recurring
ChunkMap unload spin. Its thread dump/process evidence are preserved and launch
exit1 is recorded honestly. `scenery-22` completed957views/160sheets with a clean
client exit. `scenery-23` consumes queue0745/all-02 and is the first run of the
gallery-only shutdown drain (100server ticks plus20quiet ticks; separate
shutdown.json, exact world/server guards). Bounds owns this runtime change and
its lifecycle tests. Hold new Java fixture recompilation until that client exits.

Scenery10 manual QA:4ready,192component-only,4initial correction findings across
1,191 captured views; SHA:
`d2c34e971db475a7a8cb2f5cc1b481527a8da90996a26b34cc615eefd60af449`.
Full-size side views confirmed disconnected geometry within isolated source cells.
However, reconstructed whole-source layouts connect those parts through neighbors:
the Contest entrance3x2, ship miniature2x2, and Mossdeep/Mystery stairs3x2. Do not
blindly replace these cells. `qa10-followups/whole/index.json` has five exact layouts
and local renders; bounds is preparing all nine source-occurrence native proofs.
Register complete comparisons and inspect actual Minecraft before resolving these
findings. Historical QA stays immutable; a separate resolution record is required.

The combined review rebuild is running against3db7a05e and all32 complete galleries.
Agent work: buildings continues Mauville then Lavaridge/Slateport; scenery handles
eight exact ladder patterns and cave props (five ladder floor-material changes are
under explicit review); bounds handles Mystery Events, Trainer Hill/Frontier,
source fixtures and gallery shutdown. The pass remains unfinished.

## Latest continuation checkpoint (1 November, approximately 07:40)

Current guarded ledger: **2,156 changed, 1,528 retained, 105 added, 1,162 pending
original entries**, with 851 entity definitions. Table SHA-256:
`844e686a77c05cb4d5f9359c72e38c1075d45a0471908399202470ead70593ab`.
The added count includes source-evidenced cells and whole building/object patterns.
Original building decisions are complete; full scenery coverage and visual QA are
still in progress.

New integrations include Mauville Game Corner32, Rustboro ground25 and props8,
Route10427 source fragments under two exact complete buildings, generic
architecture34 fragments under14 whole templates, harbor rail7, cliff material24,
and ship15 corrections with two open broken-door patterns covering all five warps.
The source fixtures preserve WALK, WARP, story state and original collision.
Fortree low interior counters exposed an existing two-block carrier minimum:
the narrow height-at-most-eight interior COUNTER fix was reproduced failing and
then passed source and WorldPlan regressions. SOLID ends and other games are
unchanged. Sixteen integration/source/structural Python checks passed.

Scenery09 manual QA is complete:24 ready,165 component-only,11 corrections,
bound to1,200 images. SHA:
`2d366502fb7fb57be560d146873bd8ec6c59aa3a19ace69636f08162f0b3edd3`.
Eight findings concern ship cells now repaired; three historical PC assemblies
already have newer source-entry replacements and need current-capture inspection.
Scenery10 manual review is next. Prior correction reports remain immutable.

`buildings-final-03` saved all75 views for15 models and standalone sheets verified
15/15. Shutdown hung in chunk unloading; its thread dump was preserved and only
the manifest-bound client process was stopped. The launch exited nonzero and is
not recorded as a clean client shutdown. Three wide-flag replacements in this
gallery have separate, exact-image manual QA from the scenery agent.

The previous queue is consumed. `capture-queue-0745` is preparing exact-current
capture selections. The0740 archive job captures the new table and memberships
while inheriting all0654 revisions; check its verification JSON before claiming
completion. Root owns the live merger and capture queue; the buildings agent
continues Rustboro rails then the remaining towns, scenery handles environment
followups, and bounds handles small facilities, Trainer Hill/Frontier and native
fixtures. Coordinate Java windows and keep only one gallery client running.

## Latest continuation checkpoint (1 November, approximately 07:23)

Current guarded ledger: **2,053 changed, 1,505 retained, 87 added, 1,288 pending
original entries**, with796 entity definitions. Table SHA-256:
`91b7a41635bfb63e7e0b13354796694f12f832088408c8aa454bf1e05f613967`.
All100 Fallarbor originals are accounted for, including the final tree23 and
cliff9 batches. New integrations also include Seashore20, generic appliance29
under14 exact whole templates, display7, wall32, partition8, and Rustboro fountain9.
The six Seashore metadata amendments and all source pins passed the exact export
checks. Blue and Crystal full hashes were rechecked unchanged at07:19.

Scenery19/20/21 finished cleanly:960/924/558 screenshots and160/160/93 comparison
sheets respectively. `capture-queue-2324` scenery files are consumed;
`buildings-final-03` is capturing its remaining building selection. Reconcile
the current table after this queue, because many new batches postdate its snapshot.
Only one client may run. Bounds coordinates the short Java source-fixture windows.

Scenery08 manual QA is complete and bound to exact image bytes:
80 ready,96 component-only,24 corrections; QA SHA
`84cf305ba031df3593a7e16d584f7d4fa8b1d9901d7896d3e39570fc57a4f499`.
Environment followups are with scenery; ship followups are root-owned.
Scenery09 manual review is underway, with explicit numbered notes saved as inspected.
Root's `ship_qa_corrections.py` currently prepares11 original repairs and two
exact broken-door patterns covering all five source warps; this candidate is NOT
merged. Four structural tests passed. Actual doorway fixtures preserve southern
WARP collision0, render four decor carriers and leave northern WALK projections
empty. Additional ship defects found in scenery09 are being added before freeze.

The Bike Shop blue-workbench descriptor now has the correct2x2 source layout with
an empty upper physical row and explicit lower-row placements. Current geometry
is unchanged; original native-before fragments are proved separately. The canonical
binding is recorded in `bike-shop/blue-workbench-canonical-binding.json`, and the
registration script now reproduces the corrected layout/placements.

The0654 archive remains verified. A real selective scratch restore of the second
Bike Shop desk and Glass Workshop removed the two additions on pre-pass restore
and recovered exact archived semantics on reapply, preserving unrelated entries
and the live table. Evidence:
`local/model-history/scratch-0654-roundtrip/verification.json`.
Newer work since generic soft/cabinets/TV still needs the next archive checkpoint.

Active authoring: buildings agent Rustboro/Route104 exteriors then remaining towns;
scenery agent final34 generic source decisions under whole architecture patterns,
then environment QA corrections; bounds agent Mauville and remaining facilities,
Trainer Hill/Frontier, plus source fixtures. Original building decisions are
complete, but full-model coverage and manual visual QA remain unfinished.

## Latest continuation checkpoint (1 November, approximately 07:00)

The live ledger records **1,945 changed, 1,472 retained, 73 added and 1,429
pending original entries**. SHA-256:
`e26853e6a314d3e3352cb12c9d03d7b9023466c51bd133925d7f0c6522221e4b`.
The entity registry contains716 definitions, including21 gallery-only native
original companions. The full pass remains in progress; all112 original building
templates have decisions, while additional exact source patterns are still being
completed. Blue, Crystal and the accepted Emerald pilots remain protected.

New guarded merges: Fallarbor64 reviewed originals and the Route113 Glass Workshop;
Bike Shop/New Mauville83 originals plus one missing desk half; Day Care20 plus one
missing terminal cap; generic soft furnishings32 plus two missing window cells;
generic cabinets48; and ten exact TV/cabinet templates covering all43 source
occurrences, with five projected lower fragments retained under those templates.
Native source tests verified the new desks, terminal stack, counter collision,
window joins and decor-only furniture projections. All TV south-row projections
remain WALK with zero collision, shape and decor; full TV/cabinet geometry occupies
the northern SOLID cell. Exact source amendment checks passed after integration.

Scenery18 completed960 screenshots and160 comparisons with a clean client exit.
Scenery19 is running from `capture-queue-2324/all-02.json`; remaining queue files
all03/all04 and buildings01 are not yet captured. That queue verified25 earlier
galleries, reused3,343 current captures and selected588 missing exhibits before
the latest merges. Reconcile newer additions afterward. Queue generation now
freezes its review bytes at the beginning as well as its table/entity bytes.

Scenery08 has all200 source/before/current comparisons manually inspected and
explicit notes in eight `manual-notes-root*.json` files. The evidence binder is
running. Findings include ship-wall holes, retained cave ladder/cliff distortions,
Rusturf plateau geometry, a harbor rail and the broken ship doorway. These are
corrections to do, not approved models. Whole lamp, palm, niche, lifebuoy, closed
door, both orb machines, cabinet and summit rail were also inspected. The rail's
whole occupancy is connected; the red pit's full-resolution image confirms its
shallow geometry is visible. Batches09 onward still need manual review, apart from
the previously recorded selected School/building comparisons.

The workbench comparison descriptor had a2x2 source reference but1x2 physical
layout, which the strict preparer rejected. Its invalid `baseline_layout` was
removed from canonical/local assembly definitions, keeping the full source in
`reference_layout`; geometry was unchanged. Original frozen entity bytes and the
exact before/after record are preserved in `bike-shop/pre-baseline-descriptor-correction/`
and `bike-shop/baseline-comparison-correction.json`. A true original native bench
rectangle proof is queued separately. Scenery19 preparation then passed.

Archive `emerald-batch-02-0654` and its ZIP verify26 revisions and5,749 indexed
entries, including all prior revisions and scenery18. It preserves table
`8bd74cd1...` through Day Care/Bike/Glass Workshop, before generic soft/cabinets/TV.
Verification: `local/model-history/verification-emerald-batch-02-0654.json`.
Original archives remain unchanged. A new selective scratch restore check is next.

Active ownership: buildings agent finishes Fallarbor36 and remaining towns;
scenery agent finishes generic appliance patterns and environment QA repairs;
bounds agent continues small facilities, Trainer Hill and Frontier, plus exact
source fixtures. Root owns capture/report/history integration, manual comparison
review and ship-wall/door follow-up repairs. Do not run a second Minecraft client.

## Latest continuation checkpoint (31 October, approximately 23:25)

The live ledger records **1,753 changed, 1,412 retained, 58 added and 1,681
pending original entries**. SHA-256:
`2c7389eedbc8a860ddfe280a79895c24d9da73ffcd1fccc1160a3b467226f1f5`.
The entity registry contains636 definitions, including ten gallery-only original
native companions for added building templates. All original112 buildings have
decisions; remaining scenery and additional source patterns are still in progress.

New guarded merges include the tree/environment corrections, Cable Club/Hall of
Fame/Palace83, generic furniture77, Dewford59 plus five complete objects,
Elite Four85 plus two normalized open arch patterns, and Mossdeep Game Corner22.
The Dewford tree alias opt-in passed all nine actual source pairs; the two island
tree crowns were bounded to their source footprints and reinspected. The Elite
arches match all ten normalized built-state locations. Native per-player closed
door leaves and their collision/state art remain unchanged; they are not fitted
models or covered by a fitted-model approval claim.

Scenery captures01..17 are complete (200 comparisons each except11 has12 and17
has141). Batch17 saved all843 images but hung in chunk unloading on shutdown.
The exact gallery process was stopped after preserving its thread dump, then
the standalone sheet verifier reported141/141 comparisons complete. Its Gradle
exit is a shutdown failure, not a successful client exit. Preserve that distinction.
Manual Minecraft review is recorded for batches01..07; QA06 and07 found retained
cliff, crystal-support, ladder and harbour-rail cases requiring source adjudication
or correction. Source matching proves all33 occurrences of the suspect blue-door
fallback belong to reviewed whole building patterns; normal-map claims remain
a separate runtime check. Batch08 onward still needs manual review, apart from
the previously documented nine School wholes in12 and eight new buildings.

`report_all.py` combines immutable galleries while keeping historical revisions,
missing companions and explicit per-exhibit manual observations separate. Its
eight focused tests include stale geometry, changed placements, native proof
changes and preventing notes from one old revision being attached to another.
The initial report is `local/model-gallery/emerald-combined-review-2300/`, bound
to its earlier table snapshot; regenerate after current merges. The reusable
`capture_queue.py` checks exact current content and all original images before
reusing captures; it never infers visual approval.

Archive `emerald-batch-02-2320` and its ZIP verify24 revisions and5,699 indexed
entries, preserving table `fa2cdfc6...` through Dewford, full membership and
captures14..17. It predates the Elite/Mossdeep merge. Original archives remain
unchanged. Verification is in `verification-emerald-batch-02-2320.json`.

Active candidates: Bike Shop/New Mauville83 originals plus one missing desk cell
(53 revised originals,30 retained, one addition), generic soft furnishings and
remaining cabinets, Fallarbor/town scenery and smaller facilities. Bike machinery
passed all40 actual source carrier checks at headroom9 and twelve false crate
stack-link corrections; the newly completed desks await their additional native
before proof and final local review. Do not count these candidates as merged.

## Latest continuation checkpoint (31 October, approximately 22:48)

The authoritative ledger records **1,524 changed, 1,313 retained, 43 added
and 2,009 pending original entries**, with 527 explicit entity definitions.
Live SHA-256: `a457876964841f6698cf85c469d5ac7dba9da54a55e94ba220c532290406be9d`.
All original112 building templates have individual decisions; source inspection
continues to identify additional missing whole-object patterns. This is not a
completed full-pass approval.

Newly merged batches include Weather Institute scenery54, Pacifidlog54, three
exact Sky Pillar templates, Lilycove Museum65 (27 revised/38 retained and20
complete assemblies), six source-specific Battle Dome fence paint repairs and
the two-cell Weather terminal correction. The terminal's cap now joins its
actual PC, and its WALK foot is decorative floor. Original owner artifacts are
preserved; active report paths select the hash-checked replacement owner ledgers.
Eleven museum/Weather/rail/source-amendment Python checks passed.

Scenery captures01..14 are complete (200 comparisons each except batch11's12).
Batch15 is running. Manual Minecraft QA is recorded for batches01..05; QA02
found the Weather terminal defect, QA04 found fence paint defects, and QA05
found three mineral formations and one flag assembly requiring correction.
QA03 found no defect in its captured revision. These records distinguish whole
objects, components and building fallback fragments, and retain stale-revision
markers. The nine school whole assemblies in batch12 and all eight new building
templates in buildings-final-02 also have hash-bound manual QA; the remaining
batch12 entries are not approved by that selected review.

Actual-export tests found two runtime blockers for pending corrections: indoor
whole templates were skipped and Fortree alias bases remained native roof
columns. Narrow explicit opt-ins (`interior:true` for source-verified templates
and `stackFit:true` for validated local alias pairs) now pass32 focused Java
checks, including all46 proposed mineral/flag placements, all19 tree pairs,
unrelated-map-column preservation and other-game/fallback guards. Geometry
revision is12. Those model candidates remain unmerged pending exact metadata
pins and honest native before-companion support. Normal-map building interfaces
and the combined searchable final report remain outstanding.

The verified immutable archive `local/model-history/emerald-batch-02-2243/`
and adjacent ZIP contain21 revisions and5,605 indexed entries. They preserve
the current table, full current membership catalog and captures10..13 plus the
eight new building captures. Verification is recorded in
`verification-emerald-batch-02-2243.json`; prior archives remain untouched.

Active authoring: Cable Club/Hall of Fame/Battle Palace83 entries, Dewford59
plus exact missing whole objects, Elite Four85 plus three source-specific arch
templates, and generic interiors272. These candidates are not included in the
live counts above until their remaining source/runtime checks and guarded merge.

## Latest continuation checkpoint (31 October, approximately 22:11)

The authoritative ledger now records **1,404 changed, 1,260 retained, 40 added
and 2,182 pending original entries**. Live SHA-256:
`8d20c642f663e4007dcf3f57658f7a936145b8d515ba49ac95be082823f363f3`.
There are 491 explicit entity definitions. New work since the prior checkpoint
includes the corrected Weather Institute, remaining Fortree foliage and two
source-specific palm templates, 76 Center decisions, 33 Fan Club decisions and
16 school revisions plus three missing chalkboard entries.

Nine source-verified Emerald COUNTER keys now explicitly opt into fitted rendering
with `counter:true`. The adapter keeps one collision block, unchanged floor,
source kinds/tags and talk-through behavior; visible upper voxels use a decorative
carrier. Unflagged, malformed, layered, linked, constrained and other-game cases
retain native behavior. Twenty focused Java checks passed, including actual
WorldPlan collider/decor codes. Internal geometry cache version is 11. Source
amendment guards permit the new flag only for the nine exact raw144 keys.

The school now has nine complete assemblies and three source-backed new board
cells. An actual-export planner fixture proves that the old missing keys were
six-block native walls at room headroom six. The gallery now preserves those
exact CUBE columns as `baseline_native_cells` for honest before companions;
the immutable baseline table is untouched. Thirty-seven School/gallery/beam
checks and eighteen Center/museum/FanClub/palm/source checks passed. New gallery
preparations snapshot exact entity-definition bytes too.

Archive `emerald-batch-02-2203` is verified with 18 revisions and 5,554 indexed
entries. It includes the pre-school `0e9578cd...` table, full current membership
catalog and scenery captures06..09. A real scratch round trip selectively
restored the Fan Club sofa, long Fortree palm, shared PC and one museum counter
to original and back, recovering exact bytes. The school's latest additions
postdate that archive and will be included in the next revision.

Scenery01..09 each completed 200 comparisons. Batch10 is running from the
pre-school snapshot with397 subjects; batch11 is next. Visual inspection of
02..04 is assigned to the three workers after their current authoring batches;
root owns05. Selection files are saved beside each gallery. Only batch01's
manual QA is complete so far. New and corrected models still need their
Minecraft comparison captures, and the combined searchable report remains
unfinished. The full pass continues; these counts are not a final approval.

## Latest continuation checkpoint (31 October, approximately 21:53)

The live ledger records **1,247 changed, 1,248 retained, 34 added and 2,351
pending original entries**. Current table SHA-256:
`8a676f1caa675dce19052ab99f0c3dfe60674d9d18a399b56d7425ece868d423`.
There are 433 explicit whole-object definitions. Blue and Crystal hashes still
match the accepted handoff. No original archives or comparison captures were
replaced.

Newly merged work includes Lilycove town scenery, Sootopolis gym, residential
furniture and wall decorations, sixteen source-backed gym WALK corrections,
the six-occurrence Fortree treehouse, 32 Fortree ground/bridge decisions and
57 Oceanic Museum decisions (27 revised, 30 retained, 15 complete assemblies).
Museum stairs, miniature ships and specimen tables were retained after complete
source/front/side/angled inspection. The long museum divider remains four floor
rows; it was not converted into a speculative vertical column. Museum cases,
panels, grille and computers use explicit geometry and native source paint.
Carpet leakage on the computer and an oversized grille were corrected during
local review. Ten museum metadata amendments have exact source witnesses.

Focused checks passed: 13 residential/gym/source/treehouse checks, five Fortree
ground checks and five museum/source-amendment checks. Weather Institute b130
is authored but **not merged**: the real merger rejected a carve beyond its
footprint, and source review identified two further neighboring tree cells to
exclude. A corrected candidate is being prepared separately.

Scenery capture batches 01..08 have completed 200 comparisons each. Batch09 is
running from the `cfa84d14...` snapshot; 10..11 remain queued. Batch01's manual QA
is complete with recorded corrections; 02..08 still require visual inspection.
All newly authored objects and corrections need their actual Minecraft capture
and review. Complete capture coverage never means visual approval.

The new immutable archive `local/model-history/emerald-batch-02-2125/` and its ZIP
preserve 14 revisions and 5,424 indexed entries, including the 21:25 snapshot.
`verification-emerald-batch-02-2125.json` records verified ZIP/snapshots and an
exact selective whole-ship-beam/new-Safari-building scratch round trip. A restore
ordering bug was fixed so reinserted building templates preserve source matching
precedence; 27 archive/history checks passed. This archive predates this latest
checkpoint, so another revision remains necessary before delivery.

Runtime audit also proved that source COUNTER cells intentionally bypass fitted
voxels and use native folded one-block counters. `EmeraldCounterRoutingTest`
preserves collision, floor, tags and talk-through behavior. Catalog-only counter
models are not normal-map validation; a narrow rendering adapter is under
investigation. Fortree bridges retain their native bridge-floor routing without
adding duplicate decorative WALK decks. Pokémon Center fixtures established
that adding decorative feet requires removing the body's false foot link while
preserving the existing cap/body pair; baseline PC pairs already worked.

Remaining work includes all pending scenery, missing complete objects and
buildings, actual source interfaces, new/corrected Minecraft comparisons,
the visual QA backlog, current rollback archive and combined searchable report.

## Latest continuation checkpoint (31 October, approximately 21:25)

The cumulative live ledger records **1,066 changed, 1,181 retained, 31 added
and 2,599 pending original entries**. Its current table SHA-256 is
`2f6d6cc25957949128d308174a92f7c31b72bbde3855fc65d32d366ce1873688`.
There are 362 explicit entity definitions and 125 building/whole-template
entries, including the new Route124 house, ten Sootopolis tower variants,
Route121 Safari exterior and context-specific Devon conference table.
`local/model-gallery/emerald-pass-study/review.json` is authoritative; all
original building decisions are present, but the scenery inventory, complete
object audit and final visual review remain unfinished.

Further merged modules include `interior_details`, `shared_final`,
`mauville_gym`, `gyms_rooms`, `towns_frontier`, `towns_sootopolis`, `retail`,
`fortree_gym`, `indoor_plants` and `ship_beams`. Root alone writes the cumulative
table. The local `merge_batches.py` always replays all recorded disjoint patches
from the immutable pre-pass baseline, with an exact live-byte guard.

Source-pinned metadata now lives in `source_amendments.json`: exact stack
aliases, exact before/after metadata, native pixel hashes, complete source
witnesses, added cells and added building identities. Normal map placement was
checked separately from decorative gallery display. Seven stone stair keys,
six Fortree platform keys, three Sootopolis keys and nine retail projection/toe
keys now opt into WALK decor; source collision and movement data are unchanged.
Actual Units tests cover repeated Shipyard middle sections and aliases, including
baseline omitted WALK projections, termination at BASE, and unchanged floor,
decor, kinds and tags. The contest judge desk's false vertical stack was replaced
with a joined low desk and exact nonblocking rendering permissions.

Gallery custom `placements` map source cells to explicit voxel-cell offsets.
Optional `baseline_layout` and `baseline_placements` preserve evidenced earlier
placement, including source rows that the old planner did not render. The source
drawing remains complete via `reference_layout`. Archive layout records preserve
these fields. Do not flatten these whole-object definitions into ordinary stacks.

`buildings-v3` completed all112 original comparisons/672 screenshots. The ten
east-building issues were corrected and recaptured in
`emerald-batch-02/buildings-final-01`, alongside eleven new buildings and the
Devon table: **22/22 sheets and96 exact screenshots**, all manually inspected.
Its three hash-bound QA files record no remaining isolated-gallery defects.
Actual-map Sootopolis cliff/tower interfaces remain an explicit check. The final
client stalled in OpenGL during shutdown after capture completion; its diagnostic
thread dump and guarded termination record are preserved, and `-SheetsOnly`
verified exact screenshot coverage. Earlier failed captures remain diagnostic.

Scenery batches01..04 each have200 completed comparisons; batch05 is currently
running, with06..11 still queued under `capture-queue`. Batch01's200 exhibits
were manually inspected: root92, gym79 and shared-furniture29. Corrections were
required for gym statues/platforms, ship beams, the judge desk and three indoor
plant crowns; these are merged but need recapture. Initial brass-planter
corrections also still need recapture. Batches02..04 have complete captures but
their visual inspection is outstanding. Capture coverage does not confer
geometry approval. Retail137 decisions/23 assemblies and newly merged objects
also need their complete actual Minecraft comparisons.

The immutable `emerald-batch-02-authoring` archive remains preserved but predates
these changes; create a fresh named revision and ZIP before final delivery.
The searchable combined report still needs the new galleries integrated.
Recent focused validations:58 gallery/archive/merger/shared/gym checks,18
Sootopolis/merger checks and12 retail/Fortree/Safari/source-pin checks passed.
The full pass is still active; user approval remains pending.

## Latest continuation checkpoint (31 October, approximately 20:35)

The cumulative live ledger now records **766 changed, 750 retained, seven added
and 3,330 pending original entries**. `review.json` remains authoritative. The
live hash at this checkpoint is
`7aae568b320d381442c412fea20c3e13ed3f4a9de330bb2d2648f8cadcc669f3`.
All original building decisions are present; remaining original entries are
scenery. Complete-object inventory, runtime captures and final review remain
unfinished. Do not claim a finished Emerald pass from these partial counts.

Further merged modules are `coastal.py` (62 decisions; two roof-occluded
Petalburg quarters still pending), `building_interiors.py` (85),
`towns_ship.py` (108), `public_rooms.py` (16), `shared_furniture.py` (34 original
entries and one added bench cap), and `gyms_west.py` (39 original entries and
six added Lavaridge dais centers). The retained shared-interior registry has
285 entries; its harbor corner #233 was transferred to the new coherent railing.
There are 212 explicit entity definitions at this checkpoint. Root's
`interior_details.py` has 59 authored, locally inspected candidates but is **not
merged yet**; complete assembly registration and tests are outstanding.

Building footprint corrections now include the 14 genuinely SOLID lower facade
cells of b099 and five SOLID rear cells of b103. WALK and door cells remain
excluded. Exact collision/source evidence and 28 passing east-building tests
support these corrections. Source roof projections on WALK cells were checked
separately and remain excluded unless an evidenced overhead arch uses a decor
carrier.

`emerald-batch-02/buildings-v2` stopped at 525/672 screenshots after a ground
readiness timeout on b087's current front view. Normal client shutdown completed,
but capture coverage failed. This run is **diagnostic**, not canonical QA.
The scenery agent now owns the gallery-only readiness fix in `ModelGallery.java`:
replace potentially unschedulable section probes with acknowledged camera/frame
readiness and a Minecraft-rendered uniform gallery floor. Its pilot and a fresh
building capture are still pending. Do not run another client concurrently.

All 63 remaining initial arena/prop comparisons (378 views) were inspected;
`visual-qa-interiors.json` marks 61 ready for user review and two requiring
recapture after the Contest planter's grey bowl was restored to native brass.
Together with the vegetation report, all 140 initial comparisons were inspected.

New archive: `local/model-history/emerald-batch-02-authoring/`, adjacent ZIP and
SHA file. Verified eight immutable revisions, 5,261 indexed entries; original,
approved and batch-01 bytes remain intact. `batch-02-authoring` stores the
**earlier** exact table hash
`088f5767aac1ab1e2f0bbee1f00c9bdfd60a61e21efeff0b4d4d67740912851a`
(before gyms-west), plus the initial scenery and failed building capture table
snapshots. Archive status does not confer visual approval. Eleven focused archive
tests pass, including chaining named archives without losing object membership.

Long-chain stacking and conditional pair termination passed focused Java tests.
Optional `alsoOn` aliases and repeated middle-source handling are now being added
for actual tree/flag/pylon base variants; they still need final Java verification
and exact source-backed merger exceptions. All metadata mutations are narrowly
listed in `review.py`. Seven missing source-backed cells are allowed only with
exact metadata, source-pixel hashes, occurrences and candidate hashes; twelve
focused merger/furniture tests passed. Further justified additions and the
Route 124 house gap remain active work.

## Current checkpoint (31 October, after batch 02 authoring)

The cumulative ledger now records **557 changed, 542 retained and 3,747 pending
entries**. All 112 existing building templates have an explicit decision;
building Minecraft validation is still in progress. The complete scenery and
multi-cell-object audit is unfinished. Do not interpret these counts as final
visual approval or all exported Hoenn architecture being covered.

The authoritative cumulative inputs and exact current-table hash are in
`local/model-gallery/emerald-pass-study/review.json`. Current hash at this
checkpoint: `7ddcf4ed9cfe3638572c7fa19e3d34e1e803753080b2d3e05fc446d9f6e7f24e`.
Always merge every listed patch/ledger from the immutable full-pass baseline;
use the preceding report as the live-byte write guard.

Added modules: `buildings_east.py`, `buildings_west.py`, `vegetation.py`,
`projected_terrain.py`, `environment.py`, `towns.py`, `arena.py`,
`interior_props.py`, `interior_furniture.py` and `interior_retained.py`.
Each owns explicit source keys with local patches, evidence and decision rows.
The entity catalog currently has 114 explicit complete objects. Further town,
building-interior and shared-interior modules are being authored separately.

`coverage_gaps.json` records an exported Route 124 house without an exact
existing whole-building template. Its complete source/warp context is preserved;
it has not yet been added. This remains part of the outstanding building work.

Batch 02 runtime evidence:

- `emerald-batch-02/scenery-initial`: 140 comparisons / 840 screenshots.
  All 77 vegetation-related comparisons (462 screenshots) were visually
  inspected, with hash-bound findings in `visual-qa-vegetation.json`.
  The other 63 comparisons still need visual inspection. The client stalled
  during shutdown after capture completion; its scratch client was stopped,
  thread dumps preserved, and `-SheetsOnly` successfully verified exact coverage.
  This is not a successful normal client shutdown.
- `emerald-batch-02/buildings-east`: diagnostic initial 48 comparisons.
  Findings drove further fixes; these screenshots are not the final models.
- `emerald-readiness-pilot-v3`: five comparisons / 30 screenshots after the
  renderer correction. All ten sheets inspected. Factory walls now close;
  a small roof paint issue and a tiny distant ground wedge in one baseline
  frame still require correction/recapture before final visual approval.

Runtime fixes clip building occupancy to actual carrier columns **before**
meshing, avoiding exposed holes when terrain columns are excluded. Geometry
cache version is now 10. Focused PartsBuilding, BoxBuilding and FittedBuildings
tests passed. Gallery capture waits for visible ground sections and uses a
separate scratch world per output directory. Two-tick captures remain diagnostic;
use a longer settle interval for the next full building run.

Source-verified metadata exceptions now also remove false stacks for two
Contest chairs, a cave-floor pit and tree-base foliage above an independent PC.
Three building roofs/arches occupy verified WALK columns with non-solid decor
carriers; exact exceptions and evidence are checked by the guarded merger.
Longer bookcase-stack runtime support is under focused verification and its
pending lab patches have **not** been merged.

The existing batch 01 report/archive below remains valid for that immutable
checkpoint. The latest continuation has not yet been added to the history
archive or final searchable visual report. Extend the archive with a new named
revision while preserving the original and batch-01 snapshots.

## Historical batch 01 checkpoint

Started 31 October 2026 from the exact handoff baseline. This is a partial pass,
not completion of all Emerald models. Source geometry is authored with explicit
Python parts/volumes and source-local UVs, then checked in Minecraft.

## Preserved inputs

- `local/model-gallery/emerald-full-pass-baseline.json`: SHA-256
  `7958d43cb8545ae006b3568bb6b86d809c7def79cb128a7789c36016756797d6`.
- `local/model-gallery/emerald-full-pass-entities-baseline.json`: original entity catalog.
- `local/model-gallery/emerald-full-inventory/{scenery,buildings}/`: pre-pass
  manifests/reconstructed sprites, 4,977 scenery exhibits and 112 buildings.
- Historical `local/model-history/2026-10-31/` and ZIP remain unchanged.
- Blue and Crystal retain the exact handoff hashes. Ten accepted Littleroot
  tree cells and three accepted Emerald buildings are protected by the merger.

## Current model changes

`tools/shape_fit/emerald_pass/buildings.py` authors 16 buildings: b001, b004,
b006, b009, b010, b027, b038, b044, b045, b048, b049, b059, b064, b065,
b085 and b086. These are the mirrored Littleroot house, nine Centers and six
Marts. Wall materials, independent badges, door recesses and curved roofs follow
the accepted pilot construction, with Mart-specific roof profiles. Source and
complete map occurrences were inspected for every candidate.

`tools/shape_fit/emerald_pass/scenery.py` authors three narrow conifer crowns
and their shared base as a joined, rooted tree split into two vertical pieces.
The base `g3q/18237-18238-18239-18240` incorrectly pointed back to crown
`g3q/18202-18203-18191-18188`. Only that reverse `on` link is removed;
the three crown-to-base links remain. The three complete exhibits are:

- `emerald_compound_ec1887d19146`
- `emerald_compound_94ba2b5bcf67`
- `emerald_compound_26404c10ffe4`

Explicit entity definitions preserve complete before/current comparisons even
though the pre-pass automatic stack builder excluded the cycle.

Current table SHA-256: `65bdff986af1c058f338ec3e9b7edc3bdf9ef410deecf69ba962875e9e009dd5`.

## Reproduction and ledger

Use the working Python and PYTHONPATH from the original handoff, with the repo
root also on PYTHONPATH when invoking these modules. These commands generate
local patches and preserve the live table until the guarded merge:

```powershell
python -m tools.shape_fit.emerald_pass.buildings
python -m tools.shape_fit.emerald_pass.scenery
python -m tools.shape_fit.emerald_pass.review `
  --patch local/model-gallery/emerald-pass-study/buildings/updates.json `
  --ledger local/model-gallery/emerald-pass-study/buildings/ledger.json `
  --patch local/model-gallery/emerald-pass-study/scenery/updates.json `
  --ledger local/model-gallery/emerald-pass-study/scenery/ledger.json `
  --ledger local/model-gallery/emerald-pass-study/preserved-ledger.json --check
```

`--write-table` applies the cumulative patches only when current bytes match
the baseline or the previous report. The merger rejects overlapping ownership,
stale entry hashes, altered pilots, unreviewed patches and interaction/matching
changes outside the single documented stack correction. `--require-complete`
rejects a partial ledger. Add new batches to the cumulative command; do not
replace the report with only a new batch.

The ledger currently records 20 changed entries, 15 retained entries (13 accepted
pilot entries and two non-voxel column overrides), and **4,811 pending entries**.
The pending count is 4,718 voxel cells and 93 buildings. Source contact-sheet
inspection is not approval of the old model geometry. Complete-object inventory
still needs a full semantic audit.

## Runtime and evidence

`GalleryGeometry` and `ModelGallery` now validate dimensions up to 64×40×64,
space padded exhibits by their extents, frame actual geometry, set adequate render
distance and retain the current capture subject beyond the previous 100-block
culling distance. The marked-world/server safety gates and current-hull gate remain.
The unchanged largest building b099 completed all three Minecraft views in
`local/model-gallery/emerald-large-building-pilot/`; this verifies gallery capacity,
not acceptance of its existing architecture.

Batch capture and visual-QA status is recorded in the local batch galleries and
their manifest-bound reports. Use the final evidence paths recorded below rather
than assuming the first capture is final. The initial building capture used five
settle ticks and showed incomplete ground backgrounds in three baseline frames;
it is preserved as diagnostic evidence and must not be presented as clean final QA.

Canonical captures are `local/model-gallery/emerald-batch-01/buildings-v2/`
(16 comparisons, 96 before/current screenshots) and
`local/model-gallery/emerald-batch-01/scenery/` (seven comparisons, 42 screenshots).
Both have exact front/side/elevated coverage and verified snapshot provenance.
All 23 full comparison sheets and 23 elevated detail sheets were visually
inspected in the canonical runs. The three earlier ground artifacts are resolved;
no further geometry corrections were identified for this batch. Each gallery's
`visual-qa.json` binds the observations to its manifest, capture and sheet hashes.
This is agent visual QA; user acceptance remains pending.
The combined searchable partial report is
`local/model-gallery/emerald-pass-review/review.html`. Its status remains
`in_progress`, with the pending inventory visible and separate visual-QA links.

Reopen either batch using its snapshots without replacing capture evidence:

```powershell
.\tools\model_gallery.ps1 -Game emerald -Subset buildings `
  -OutDir local/model-gallery/emerald-batch-01/buildings-v2 -View
.\tools\model_gallery.ps1 -Game emerald -Subset all `
  -OutDir local/model-gallery/emerald-batch-01/scenery -View
```

Regenerate the combined report after a verified capture or QA update:

```powershell
python -m tools.shape_fit.emerald_pass.report `
  --gallery local/model-gallery/emerald-batch-01/buildings-v2 `
  --gallery local/model-gallery/emerald-batch-01/scenery
```

## Remaining issues

- Both original empty entries remain pending. The path entry is projected
  broadleaf foliage over walkable path, not a freestanding object. The water-edge
  crown belongs to a larger repeating forest support chain. Review their families
  and runtime fallback behavior before changing classification or geometry.
- The three keys without static-map occurrences remain pending. Tileset context
  identifies the green-striped pair as Trick House floor variants (metatiles
  592/593), and the dark strip as an Elite Four warp variant (839). This does not
  establish their dynamic placements; see `emerald-pass-study/scenery/pending.json`
  and `source-variants.json`.
- b008 has a 24/30-cell near match at Sootopolis `(28,28)`: all six northern
  cells differ in their upper quarters. b110 has a 28/30-cell near match at
  Sky Pillar Outside `(12,0)`: two door cells differ (open template versus sealed
  map). These are evidence-backed near matches, **not exact occurrences**.
  See `emerald-pass-study/buildings/unmatched-context-analysis.json`.
- All other models/buildings and unrecognized multi-cell assemblies still need
  semantic decisions, authoring as needed, actual Minecraft comparisons and
  individual visual inspection. Do not infer completion from the prepared inventory.

## Archive continuation

`tools/shape_fit/emerald_pass/archive.py` creates a new immutable archive from
the old October revisions, pre-pass bytes and a named `batch-01` revision. It
does not redefine historical `approved`. It accepts repeated `--gallery` paths
and retains complete-object membership and assembly layouts. The existing
`tools/model_history.py restore --archive <new-path> --revision batch-01` can
restore a cell, building or whole object selectively. Archive verification does
not itself establish visual approval.

Created archive: `local/model-history/emerald-batch-01/`, adjacent ZIP and SHA-256.
Its index contains 5,094 entries including all pre-pass automatic assemblies and
the three newly repaired stacks. Historical `approved` still means the accepted
pilots; `batch-01` means this continuation. A real scratch restore of a complete
conifer and b001 to `original` and back to `batch-01` passed, preserved every
unselected entry and did not touch the live table. Evidence:
`local/model-history/verification-emerald-batch-01.json`.

Validation: 95 focused Python checks across generator, merger, gallery, history,
archive and report tests passed; 36 focused Java tests passed, and `assemble`
succeeded with the merged resources. Use a new workspace-local `--basetemp`
when running pytest on this machine; the default user's pytest temp tree is
not accessible in the sandbox. Gradle and the gallery need access to the
existing user Gradle cache. No version pins or machine-wide settings changed.
