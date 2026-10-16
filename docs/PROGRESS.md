# Kanto Bridge: progress

Where the execution of [PLAN.md](PLAN.md) stands. A new run reads this file and continues at the
first milestone that isn't **done**; every planned milestone is done now, so new work starts from
[ROADMAP.md](ROADMAP.md). Each milestone ends with `tools/check` green, this file updated,
new items in [PLAYTEST.md](PLAYTEST.md) and a commit titled `M<n>: <title>`.

## Milestones

| # | Milestone | Status | Date | Commit |
|---|---|---|---|---|
| M0 | Foundations, tooling and spikes | **done** | 2026-10-03 | `ccade90` |
| M1 | Bridge connection | **done** | 2026-10-03 | `fa15739` |
| M2 | World export and builder (Pallet Town and its interiors) | **done** | 2026-10-03 | `bbad914` |
| M3 | Walking and warps | **done** | 2026-10-03 | `aa725fd` |
| M4 | Dialogue, menus and NPC mirroring | **done** | 2026-10-03 | `c5b39d1` |
| M5 | Opening, Oak's cutscene, starter and rival battle | **done** | 2026-10-03 | `eb9c570` |
| M6 | Route 1, habitat pools, catching, party/Pokédex/bag sync | **done** | 2026-10-03 | `b603f16` |
| M7 | Viridian City (Pokémon Center, Mart, PC, money, trainers, saving) | **done** | 2026-10-03 | `46e5573` |
| M8 | The rest of Kanto (single player) | **done** | 2026-10-03 | wave 1 `20e43b9`, wave 2 `559daa9`, final `aaf208c` |
| M9 | Multiplayer | **done** | 2026-10-03 | `24159b2` |
| M10 | Reliability, performance and launch | **done** | 2026-10-03 | `M10: reliability and launch` |

**All milestones are done.** What remains is your play-test ([PLAYTEST.md](PLAYTEST.md)) and the
questions under "Open issues" below. Proposals for later stages (the hidden-player speed-up against rubber-banding,
the generative voxel system, first-person dressing, several regions in one world) are in
[NEXT_STAGES.md](NEXT_STAGES.md).

## Voxel plan

Execution of [specs/VOXEL_BLOCKS_PLAN.md](specs/VOXEL_BLOCKS_PLAN.md) (V1–V9), in its own numbering. The decisions of
its §2.1 use their defaults (nobody answered them before the run): VD1 (a) the hybrid, built directly; VD2 nothing
pinned; VD3 never Dramaless Shape's tables; VD4 export and warn; VD5 thick cards, carved and rigged as options, players
as skins; VD6 all four pack steps; VD7 interior walls and ceilings only; VD8 keep the block outline; VD9 the generated
pack below player packs; VD10 the JSON file.

| # | Milestone | Status | Date | Commit |
|---|---|---|---|---|
| V1a | Measuring tools (V1's first work package) | **done** | 2026-10-04 | `eeda9ee` |
| V1 | Runtime model with the same cubes | **done** (X1 and X4 wait for you) | 2026-10-04 | `9b3a647` |
| V2 | Shape data, sync and per-face art | **done** (play-test waits for you) | 2026-10-04 | `7a364d7` |
| V3 | Carriers and Tier A shapes | **done** (play-test waits for you; rebuild your world first) | 2026-10-04 | `5a1a283` |
| V4 | Tier B shapes | **done** (play-test, X2 and X3 wait for you; rebuild your world first) | 2026-10-04 | `aa706c9` |
| V5 | Story art in the mesh | **done** (play-test waits for you; rebuild your world first) | 2026-10-04 | `144c669` |
| V6 | Texture packs and `.vox` models | **done** (play-test and X5 wait for you; no rebuild needed) | 2026-10-04 | `0df5171` |
| V7 | Structure templates and dressing | **done** (play-test waits for you; rebuild your world first) | 2026-10-04 | `cadd6b3` |
| V8 | Characters | **done** (play-test and X7 wait for you; no rebuild needed) | 2026-10-04 | `7b606a9` |
| V9 | Clean-up, other renderers, docs | **done** (X6 and one question wait for you) | 2026-10-04 | `819b6ae` |

**Measurements waiting on you** (agents never start the client; the plan's §10):

- **X1 baseline:** take it on the commit `V1a: measuring tools`, before V1b's runtime model, on a world built before V3
  (your `saves/Kanto` as it is now). PLAYTEST.md "Voxel plan, V1a" has the steps.
- **X1 with the runtime model and X4** (a pack of your own above the generated one): PLAYTEST.md "V1". V1's gate is X1
  within its thresholds; the run went on without it, because only you can measure it. If X1 fails, the plan's R1 ladder
  applies (§9).
- **X2 and X3** (V4: shapes against cubes, and our share of the mesh time): PLAYTEST.md "V4". The budgets the build
  report can check hold (see the check results); these two are yours. If they fail, R1's ladder applies.
- **X5** (V6: the calibration model's axes): PLAYTEST.md "V6". If the model shows mirrored or turned, the documented
  axes and `VoxMesher` change together.
- **X7** (V8: 64 puppets, flat against thick and carved): PLAYTEST.md "V8". If thick or carved cost more than the
  plan's limits, the quad budgets (256 and 600) come down.
- **X6** (V9, optional: Sodium, Iris, Distant Horizons): PLAYTEST.md "V9". Nothing is pinned; any pin that follows is
  your decision.

### Voxel plan: check results

- **V1a:** `tools/check.sh`: pytest **1366 passed** (37 e2e skipped; the new `tests/test_viewpoints.py`), then
  `gradlew build`: JUnit (the new `MeshStatsTest`, `BuildReportTest#viewpointsAreBlockCoordinatesOnly`), **88/88
  GameTests**, the mixin remap check (**10** injector targets: the two new ones are the dev timing mixin's) and the
  target check.

- **V1:** `tools/check.sh --e2e`: pytest **1405 passed** (every e2e scenario of M9 and M10 unchanged, and the full real
  build: 220 maps, 0 warnings, **2,485,154 blocks** as before, 56.4 s), then `gradlew build`: **584 JUnit** (new:
  `CubeEmitterTest` against vanilla's own `FaceInfo` and `BlockFaceUV` and the jar's half-cube model, `RenderSnapshotTest`
  with four reader threads, `ArtKeysTest`, the carriers in `TileBlockTest`; rewritten: `TextureSyncTest`,
  `HostSlotPackTest`, the pack tests), **88/88 GameTests**, the mixin checks. The real build's generated pack holds only
  `textures/` under the art set `blue`: no blockstate, no model.

- **V2:** `tools/check.sh --e2e`: pytest **1421 passed** (new: `tests/test_shape_gates.py`, the golden shape index
  against its schema, the export additions under lupa; the full real build three times: a filtered Pallet Town build,
  the full build and the full build again over itself), then `gradlew build`: **638 JUnit** (new: `Gen1FrontEndTest`
  with the golden `synthetic_scene.json`, `StructuresTest`, `ShapeKeyTest`, `ShapeIndexTest`, `ShapeMapTest`,
  `ShapePlannerTest`, `ShapePlannerSyntheticTest` with the golden `synthetic_shapes.json`, `NoPixelTest`,
  `ShapeTableTest`, `SelfCheckTest`, `ShapeQuadConventionTest`, `ShapeCheckTest`), **90/90 GameTests** (new:
  `ShapeBuildGameTests.dataFilesOnly`, `MultiplayerGameTests.joiningPlayersGetTheWorldsShapesAfterItsSlots`). The real
  build: 220 maps, 0 warnings, **2,485,154 blocks, as before V2**, 56.2 s; `kb-shapes/1` planned 5,787 cube keys and
  9,717 shapes over 265,229 positions; the shape map is 3.07 MB, 127,580 characters on the wire (2 of the 8 parts
  allowed); equal shape hashes on the second build, which placed and cleared nothing, and the same shape index for the
  filtered build. Self-check (recorded, not gated): 0 holes, ground match 1.0 everywhere, structure match median 1.0,
  5th percentile 0.71, lowest 0.375 (small gate interiors: counters and classified wall heights); occluded ground at
  most 0.66.

- **V3:** `tools/check.sh --e2e`: pytest **1422 passed**, then `gradlew build`: **666 JUnit** (new: `GeometryTest`,
  `FaceMaskTest`, `CarrierChoiceTest`, `WalkabilityTest` (I1, shaped against unshaped, every cell of every map),
  `QuadCounterTest`, `ShapeBlockTest`, `CullingContractTest`, the measured runs in `StructuresTest`, and in
  `SelfCheckTest` a fuzz of 2,000 random layouts with doors that must leave no hole), **90/90 GameTests**
  (`ShapeBuildGameTests.carriers`: every planned position holds exactly its planned state, and a player fits every
  walkable cell and collides in every solid one; `SyntheticBuildGameTests`: the built shape map is the golden one, byte
  for byte). The first run of the real build found 228 self-check holes in 11 maps: the self-check skipped the
  triangular south face of a hipped roof end whose repeated corner came first (a zero first edge for its normal). The
  geometry was right; the projection now takes the normal over all four corners, and a fuzz of 20,000 layouts found no
  hole. The real build: 220 maps, 0 warnings, 2,478,550 blocks (6,604 fewer than V2: measured heights and one-block
  roofs in place of the two height classes), 57.8 s at 48,272 blocks/s;
  `kb-shapes/2` planned 6,065 keys (4,531 cubes, 1,525 roofs, 9 lips), 9,139 `shape_full` blocks, 213,496 shaped
  positions; the shape map is 136,920 characters (3 of 8 parts). Quads after culling: 1.01 per shaped block on
  average, 5 at most (budget 96 and 24), per section 256 median, 1,104 at the 95th percentile, 1,968 at most (budgets
  12,000 and 30,000); counting them took 0.8 s. Self-check: **0 holes**, ground match 1.0 on every map, structure
  match median 0.93, 5th percentile 0.577, lowest 0.375; occluded ground at most 0.66. **Thresholds** (§5.6, in
  `build_world.py`): no hole, ground match 1.0, structure match 5th percentile at least 0.55 and lowest at least 0.35
  per map. The plan's per-type targets (walls 0.99, roofed columns 0.90) aren't separable in a per-map score: walls
  score exactly on fixtures (`SelfCheckTest`), roofs lay their two drawn rows on a one-block slope and score below them.

- **V4:** `tools/check.sh --e2e`: pytest 1,421 passed and 1 failed (the golden shape index test still named planner
  version 2; fixed), then `tools/check.sh`: pytest **1385 passed** (37 e2e skipped), `gradlew build`: **701 JUnit** (new:
  `BackgroundTest`, `ObjectTest`, `HullTest`, `TuftTest`, `QuadMergerTest` (120 random voxel grids: every exposed face
  covered once, showing exactly its voxel's texel), `BudgetTest`, `UnitsTest`, and Tier B cases in `ShapeTableTest`,
  `QuadCounterTest` and `WalkabilityTest`), **91/91 GameTests** (new: `EncounterGameTests.aTuftKeepsItsGrassSpawnable`;
  the synthetic build's grass under tufts still offers its pool's Pokémon, its void ring holds units; the shaped build
  places tufts, ring units and an object, none beyond the ring), the mixin checks. The real build: 220 maps, 0
  warnings, 2,456,469 blocks, 58.5 s at 47,713 blocks/s; `kb-shapes/3` planned 6,435 keys (4,400 cubes, 1,525 roofs,
  496 cell keys (62 units), 9 lips, 5 tufts), 293,510 shaped positions; the shape map is 151,892 characters (3 of 8
  parts). 10,975 tufts on `decor` (73 skipped under story cells), 8,264 void-ring units (none beyond the ring).
  Structures: 1,005 Tier A regions, 10,763 hull cells (19 units), 29 object cells (4 units), 227 column fallbacks (16
  units), no flat unit; no unit failed its gate. Quads after culling: 15.2 per shaped block on average, 69 at most
  (99th percentile 54; budgets 24 and 96), per section 304 median, 6,240 at the 95th percentile, 24,064 at most
  (budgets 12,000 and 30,000); counting them took 0.8 s. Self-check: **0 holes**, ground match 1.0 on every map,
  structure match 5th percentile 0.5645 and lowest 0.375, so V3's thresholds hold (unit drawings are scored per unit).
  Hulls cover 0.52 to 0.63 of their drawing (gate 0.4, fixed from this run), objects match 1.0 (gate 0.95). The first
  run of the real build had failed the section maximum (34,688 quads); deviation 32 has the fix.

- **V5:** `tools/check.sh --e2e`: pytest **1422 passed**, then `gradlew build`: **713 JUnit** (new: `ClosedOverlayTest`,
  `ClosedArtTest`, `StoryDirtyTest`, the story carriers in `CarrierChoiceTest`), **92/92 GameTests** (new:
  `CollisionGameTests.aMeshedStoryTileCollidesAsBefore`; the synthetic build's cut tree is a model story tile with
  `decor_leaves` above it, and `closed_shape_cells` equals its tile story cells), the mixin checks. The real build:
  220 maps, 0 warnings, 2,457,113 blocks (644 more than V4: the carriers above story tiles), 59.3 s at 46,998
  blocks/s; `kb-shapes/4` planned 6,445 keys (4 cubes and 6 slabs more than V4: the explicit plain and flat closed
  columns of gates and scripted swaps; the cut trees' units were planned already); **162 overlays for the 162 tile
  story cells**; 128 `decor_leaves` (the cut trees) and 516 `decor` above story tiles; the shape map is 155,752
  characters (3 parts). Quads, self-check and structures as at V4 (the open world didn't change).
- **V6:** `tools/check.sh`: pytest **1410 passed** (new: `test_pack_schema.py`, the manifest schema, its synthetic
  example and what it refuses), then `gradlew build`: **738 JUnit** (new: `KbPackManifestTest`, `KbPackReloadTest` on
  a made-up resource manager, `PackOverridesTest`, `PackTemplateWriterTest` on the synthetic bundle, `VoxFileTest`,
  `VoxMesherTest`, the pack keys in `ArtKeysTest`, a pack's rules and model in `ShapeTableTest`), **92/92 GameTests**,
  the mixin checks. V6 changes no block, so it has no real build (its gate is "green").
- **V7:** `tools/check.sh --e2e`: pytest **1447 passed** (the end-to-end tests included; new: the template gates in
  `test_shape_gates.py`, the template entries in `test_builder_golden.py`), then `gradlew build`: **753 JUnit** (new:
  `TemplatesTest`, `DressingTest`, `BandsTest`, the open-sky packer in `InteriorPackerTest`, templates and tints in
  `ShapeTableTest`), **92/92 GameTests** (the synthetic buildings are templates; the house's walls reach its ceiling),
  the mixin checks. The real build: 220 maps, 0 warnings, 2,467,050 blocks (9,937 more than V5: interior walls up to
  the ceiling), 60.0 s at 47,229 blocks/s; `kb-shapes/5` planned 18,834 keys (12,753 `tmpl` in 35 templates, 4,250
  cubes, 1,315 roofs, 496 cell keys, 15 slabs, 5 tufts) and 23,557 instances; the shape index is 1.21 MB, the shape map
  211,204 characters (4 parts). Templates: 18 with panes became buildings (22 placements; match 0.978 to 1.0, none
  failed), 17 without a pane kept their columns (22 placements); at most 66 quads in a template block. Self-check:
  0 holes, ground 1.0, structure match 5th percentile 0.5982 and lowest 0.375 (the route gates, as at V4); quads
  9.8 per shaped block on average, 69 at most, sections 6,240 at the 95th percentile and 24,064 at most. Five open-air
  interiors went under the open sky (`notes.open_air_sky`), none kept its ceiling. The new gates `templates_reported`
  and `template_fallbacks` (at most 2 failed templates and at least 20 building placements, set from this run) hold.
  Two earlier runs failed: `selfcheck_structure` (5th percentile 0.4231, lowest 0.2973; deviation 64), and four
  templates under their gate while sunk panes counted as misses and the facade was carved (deviations 60 and 63).
- **V8:** `tools/check.sh`: pytest **1410 passed**, then `gradlew build`: **768 JUnit** (new: `ThickCardMeshTest`,
  `HullCarverTest`, `RigSplitterTest`, `StyleFallbackTest` with the synthetic bundle's report, the `characters`
  settings in `ClientSideStateTest`), **92/92 GameTests**, the mixin checks. The synthetic build's report:
  `sprites_by_frames` 1 × 1, 1 × 3, 2 × 6; `character_fallbacks`: rigged to carved 4 (its made-up sprites aren't
  people by the rig's test), carved to thick 0, rigged to thick 0, thick to flat 0. V8 changes no block, so it has no
  real build (its gate is "green"); the real export's numbers come with V9's `--e2e` run.
- **V9:** `tools/check.sh --e2e`: pytest **1447 passed** (the end-to-end tests included), then `gradlew build`:
  **768 JUnit** (`CubeEmitterTest` now reads the half cube V1 drew from its test resource), **92/92 GameTests**, the
  mixin checks. The real build is V7's, block for block (2,467,050 blocks in 59.9 s at 47,730 blocks/s, 0 warnings,
  every gate held), and now reports the characters: the export's 73 sprites have 1 frame (12), 3 (20) or 6 (41);
  asked for rigged figures, 32 rig, 38 fall back to carved and 3 to thick; asked for carved, 3 fall back to thick;
  thick cards never fall back to flat.

**Where the voxel plan stands:** V1–V9 are done and committed. Waiting on you: the play-tests of PLAYTEST.md's
"Voxel plan" sections (rebuild your world before V3's, V4's, V5's and V7's), the measurements X1–X7 above, and
question 5 below (the fallback for worlds built before V5).

### Voxel plan: deviations

1. **V1 is two commits** (`V1a: measuring tools`, then `V1: runtime model`) instead of one, so the X1 baseline can be
   measured on today's rendering at the V1a commit while the run continues.
2. **MeshStats is two classes:** `client/model/MeshStats` (the counters and the arithmetic, pure and unit-tested) and
   `client/model/MeshStatsRuntime` (the key, the frame clock, the report file). The dev mixin's gate is
   `client/model/MeshStatsMixinPlugin`; its config is `kantobridge.dev.mixins.json`, its package
   `dev.kantobridge.mixin.dev`. The dev client turns it on with `./gradlew runClient -Pkb.meshstats=true`, which also
   points the report at `local/reports/mesh_report.json`; the key is F8.
3. **The viewpoints come from the build report:** the builder adds `viewpoints` (the spawn cell's block and the surface
   box, block coordinates only) to `build_report.json`, and `build_world.py` turns them into
   `local/reports/viewpoints.json`, so no tool parses the world's own files.

4. **The cubes keep vanilla's UV shrink.** The plan's sketch emits the full cube with `square(...).spriteBake(sprite,
   BAKE_LOCK_UV)`, which doesn't shrink UVs. `CubeEmitter` writes the same corners and default UVs from a table and also
   applies vanilla's shrink toward each face's centre, so the runtime cubes are vertex for vertex what the JSON models
   gave (`CubeEmitterTest` checks the table against vanilla's `FaceInfo` and `BlockFaceUV`). The cubes are prebuilt as one
   mesh per slot when a snapshot is built, and `emitBlockQuads` copies the mesh.
5. **Two small helpers the plan doesn't name:** `client/packs/LoadedPack` (the generated pack's info as the last
   resource reload loaded it, so the art set drawn with is the one in the atlas) and `world/Games` (which games share
   their art: the one place that names `red` and `blue`).
6. **`art_incomplete` is re-judged every tick,** so a client without gen1-bridge that lacks a few keys exports its art
   once gen1-bridge connects (once per world), as `no_art` does. The plan's table left that row without a retry.
7. **A world without a slot map** (built before M9) shows placeholders since V1: the model needs the slot map to turn a
   block's slot into a key. Every world of yours has one.
8. **The synthetic bundle grew a fourth map** (V2): `TEST_HILL`, north of the town, holds the plan's new synthetic
   content (a house with a tapered two-row roof and a windowed facade, a cliff mound with a rim, a fence line, a sign on
   a lightest-shade background, a 2×2-tile round tree with a dark outline), so the town and every coordinate-based test
   stay as they were. Counts that cover the whole bundle moved (4 maps, 32 pack textures, 5×6 surface chunks), and the
   GameTest's town-only rebuild now also closes the town's ring with void fill where the hill stood.
9. **I7 for a filtered build is the shape index's hash.** Keys come from the whole export, so a filtered and a full
   build share one shape index; the shape map holds only the placed maps (and names its own WorldIndex), so its hash is
   equal across identical builds only. The e2e gates check exactly that.
10. **East faces follow vanilla's UV lock** (seen from outside, the tile is upright and not mirrored: texture left =
    south on an east face). §5.5 says "texture left = north" for west and east faces, which on an east face would
    mirror vanilla's own cube and break "the same cubes, exactly"; `ShapeQuadConventionTest` pins every face to V1's
    cube.
11. **The self-check sees water at the walking plane** (a flat floor, as the 2D map draws it), not the half block's
    top; water is ground, never a structure, so only its occlusion numbers could move.
12. **V2's client builds geometry without pixels** (`ArtSource.NONE`): cubes need none. Reading this client's own
    export for pixels comes with the first pixel-based shapes (V4).
13. **The shape map lookup keeps per-chunk grid lists** and leaves out §4.3's per-thread "last grid": a chunk column
    touches one to three grids, so a lookup is already a few comparisons, without a thread-local read per block.
14. **Shape reasons:** a shape index that doesn't parse (or has an unknown version) is `geometry_unsupported`; a map
    that doesn't decode or names another index is `shape_map_mismatch`. A client that draws its art but cubes keeps
    `ok:true` and says the reason; `building` (the table still building) isn't reported.
15. **Export additions are optional in the schemas,** so older bundles still read: without `animated`, the pack
    animates water and flowers as before.
16. **Runs (V3) are the repeat rule's pieces,** a non-repeating piece keeping the repeating piece directly south of it (a
    building's roof rows with its plain facade, a cliff's rim with its body); a repeating piece elsewhere (a tree line)
    is its own run. Read literally ("a maximal vertical stretch"), a tree line touching a building would take the
    building's height and the roof's gable would span the trees.
17. **Read as a repeat:** a pure repeat, a head over a repeating body, or the trim rule with a distinct front row
    (`tile(f-1) == tile(f-2) != tile(f)`). The region vote counts only runs measured from their drawing, regions leave
    pure repeats out, and only runs read as repeats that aren't pure repeats adopt the vote: otherwise a region merged
    through a tree wall raised the whole wall to a house's height.
18. **Roofs need four drawn rows** (`Structures.ROOF_MIN_ROWS`, two roof rows over at least two wall rows). With the
    plan's `L >= 2`, every sign, post and lone tree (two rows) would carry a tent until V4 turns them into objects. A
    run raised by the vote isn't a plain repeat, so a building's plain columns get the same roof as its drawn ones.
19. **A folded door's columns are roofed over the depth to their fold origin,** so they rise with the facade beside them.
20. **Flanks and hips:** an exposed side is hipped; a side with a roof next to it keeps its flank, which the face masks
    drop. Besides cell coverage, a quad is also dropped when the neighbour's opposite face holds exactly its outline
    (touching flanks, and the faces between consecutive roof rows): coverage of whole cells alone never hides a triangle.
21. **Ledge lips** are `slab(tile, 4)` on every tile of a ledge cell. The self-check counts ledges with the structures,
    not the ground ("ground_match: walkable and water pixels"), since the lip lifts their art by two pixels.
22. **The self-check samples every face that looks up or south,** sloped roofs and non-rectangular faces included;
    axis-aligned rectangles are projected exactly as before.
23. **Quads are counted over the whole planned world** by `builder/shape/QuadCounter` (pure): Minecraft's own culling
    (opaque tiles; water hides the top of the block under it) and the face masks, per shaped block and per 16³
    section, with the densest section's corner and `quad_count_ms`. The dev-only per-section comparison on the client
    (§6.3) comes with V4's measurements.
24. **`shape_hosts_added`** counts the new carrier blocks placed (`shape_full`, `decor`). `MapModel` keeps no per-tile
    unit: the planner measures from the scene. The void fill and interior rings wear the drawn border in shaped builds
    only; an unshaped build is today's world.
25. **The shape table's geometry version is 2,** so a client's V2 cache files are rebuilt.
26. **A repeating cell is a unit only with the foliage hint (V4).** Read literally ("repeats as the same 2×2 unit across
    its region"), a house's plain wall and a fence would be units too, and the object rule would cut them into thin
    cards. Repeating cells need every tile to wear the front end's foliage hint (the tree wall the game draws around
    outdoor maps, cut trees); lone cells and cut trees always qualify.
27. **Units stand only where Tier A is at most two blocks without a roof.** A unit fills its cell's 2×2 columns two
    blocks high on `shape_full`, so a solid cell's collision height never drops (I1), and its floor wears the unit's
    ground tile.
28. **A unit's context is in its keys' variant** (`g<sides>[i][r][s][t]`: the sides bordering walkable cells, outdoors
    the south side only; indoors; repeating vertically; a sign; foliage): the background seeds and the object rule
    depend on it, and a key must name everything its geometry depends on.
29. **The void fill's ring keeps its collision.** Its border trees stand on `shape_full`, not on `decor` as the carrier
    table's "outside every column" would say, so nobody leaves a map through its border trees or walks from one map to
    another across the void; the ring is two blocks high (the void column beyond it stays three), and I2 holds beyond
    it. The ring's keys come from the whole export (every cell of each outdoor map's drawn border block, on the map's
    most frequent ground tile, context `g0rt`), so a filtered and a full build still share one shape index.
30. **No `decor_leaves` yet.** The foliage hint puts a unit's blocks over walkable cells on `decor_leaves`; V4 has none
    (units stand in solid cells, the ring keeps its collision). V5's closed cut trees are the first.
31. **Hulls are gated by coverage.** A hull stands round in the middle of its cell, so in the game's view each row shows
    higher than it is drawn and the lowest rows show the floor in front of it: no pixel matches by texel (match 0 on
    real art), and a hull covers 0.52 to 0.63 of its drawing on the real build. The gate is coverage ≥ 0.4 (a broken
    hull covers little) and the 96-quad cap; objects keep the plan's 0.95 and score 1.0. The per-map self-check leaves a
    unit's drawn pixels to the unit's own check and counts its background as ground, expecting the unit's ground tile.
32. **Hull runs are half-block steps deep, and caps show the crown.** The first real build failed one budget: its
    densest section held 34,688 quads (30,000), with hulls at 33–94 quads per block. Rounding each run's depth to
    four pixels and capping every run with the row below its top cut hulls by about a third (21–69 per block): the
    section maximum fell to 24,064 and the mean per shaped block to 15.2. No step of R1's ladder was needed.
33. **Void tiles lie as plates 1/16 above the floor,** in units only; Tier A's columns stay opaque cubes (a `tile_full`
    carrier can't hold less than a cube, rule 3).
34. **The shape table cache is keyed by the art too** (the export hash in the cache name's third value), since tufts and
    units are built from pixels; a new export rebuilds the table (`ClientShapes.artChanged`). Geometry version 3.
35. **The dev-only mesh check (§6.3) rides on the measurement:** each F8 run compares up to 256 of its sections' emitted
    quads with `QuadCounter.emittedAt` (the pure-Java count) and records `prediction{sections, equal, max_diff}`.
36. **Report:** `structures_by_kind` adds `flat` (all-void units) to the plan's kinds; `void_ring_units` counts the
    ring's units (`void_hull_cells` those beyond the ring: 0); `units_failed` counts unit cells drawn as columns
    because their candidate failed its gate; per-unit numbers (kind, scores, quads, cells) go to
    `shape_selfcheck.json` under 16-digit signature hashes.
37. **Tufts are `decor`, drawn without ambient occlusion** (a third material); a tile without strokes has no tuft. A
    puppet stands on the colliding blocks of its cell (`ClientShapes.topHeight`), cached per cell once its chunk is
    loaded.
38. **The synthetic round tree stays a column:** it stands beside the cliff, so it is neither alone nor foliage. JUnit
    fixtures cover hulls; the synthetic world covers an object (the hill's sign), column fallbacks, tufts and the ring.
39. **The block-entity renderer stays, as a fallback only (V5).** It draws just the cells the snapshot doesn't: a world
    built before V5 (its shape map has no overlays) or a client without a verified shape map. The plan removed it, but
    then such a world would show only the bottom block of each closed column (the only carrier it has): I3 forbids
    missing art. With overlays but without a shape table (`shapes: off`), the mesh draws each closed level as the plain
    tile cube the story table names (flat art as a layer 1/16 above the floor).
40. **A plain closed column is made explicit** in its overlay (`cube` keys of the closed tile): an overlay's carriers
    (`story_tile`, `decor`) have no cube of their own to fall back to, as `tile_full` has.
41. **The carriers above a story tile** are `decor_leaves` when the closed column is foliage (a cut tree: the first
    `decor_leaves` of a build), else `decor`; a light block never takes their place.
42. **The planner is `kb-shapes/4`** with the hashed parameter `storyArt`, so `kb-shapes/3` keeps planning exactly what
    it did; the overlays add keys for gates and scripted swaps (explicit cubes, slabs for flat art), not for cut trees,
    whose units were planned already.
43. **The mesh threads find a position's overlay by a binary search** over the sorted block columns of all overlays
    (`ClosedArt`), never a boxed map key. The closed set is a bit set over overlays, swapped with a shallow copy of the
    snapshot (`withClosed`) on every story change, then only the changed cells' columns are re-meshed (`StoryDirty`).
44. **`story_tile` is a carrier** (`CarrierKind.STORY_TILE`), drawn by the runtime model; the dev mesh check counts
    closed art too (`QuadCounter.World#closedId`).
45. **Manifests apply by pack priority, then file name.** Minecraft lists resources per file; the reload orders every
    manifest by its pack's place in the load order first, so a higher pack wins whatever its file is called.
46. **A model's colours are tints over one white sprite** (`kantobridge:block/white`, 16 × 16): the shape table gives
    each colour a `color:rrggbb` texture key, the snapshot maps it to that sprite and a tint, and `ShapeEmitter`
    multiplies the tint with the darkening; ambient occlusion and light apply as on any face.
47. **The atlas check assumes an 8192² atlas** (a pack's block textures may take half of it, 32 M pixels) instead of
    asking the GPU: the check runs off the client thread, before a GL call is safe. It can't count vanilla's and other
    mods' sprites; a pack that stays far below the limit can't cause a stitch failure on its own.
48. **Animated block textures are accepted**: a strip of square power-of-two frames with its `.png.mcmeta`, besides
    the plan's "square power of two ≥ 16". A strip without its animation file is `pack.texture_size`.
49. **Structure, placement and sprite models, `struct:` band rules, characters and dressing are read and checked in
    V6, and drawn from V7 (structures, dressing) and V8 (characters).** A play-test `.vox` sprite therefore counts as
    applied but shows from V8. `struct:` and `place:` keys can't be checked against the key index before V7 lists
    structures.
50. **A 32-voxels-per-block model is halved** to 16 (each 2 × 2 × 2 group becomes one voxel of its most frequent
    colour where at least half is filled), because quads live on the 1/16 grid.
51. **What the key index lists:** a tile's palettes with their generated sprite ids, the block columns that show it
    (`uses`) and the maps (`maps`); a sprite's frames; a unit's result (`hull`, `object`, `columns`), its class
    (`tree` for foliage, else `prop`) and the cells that use it. A unit with a missing tile can't be named by a
    `cell:` key and isn't listed. It is written after each pack generation and with every template, so worlds made
    before V6 get one when the template is written; without it keys aren't checked.
52. **The template button stands beside "Create Kanto world"** (on its right when the screen is wide enough, else
    below it), writes and opens the folder, and reports by toast; `/kbpack template` does the same in a world.
    "Prepare for sharing" is `/kbpack share <folder>` (a copy to `kantobridge/shared/`), and `/kbpack` is the check.
53. **`texture.status.packs` is omitted when no manifest applies**, like the other optional counts.
54. **A `cell:` model keys on the unit's four tiles**, so it replaces every context variant of that unit; collision
    is the world's and doesn't change.
55. **Codes for cases the plan's table doesn't name:** a texture or model for a key kind that section can't take, and
    a key that doesn't parse, are `pack.unknown_key`; a block texture outside `block/` or a sheet outside `textures/`
    is `pack.texture_missing`; a model's bad fields are `pack.vox_format`; an origin that moves a cell model out of
    its unit is `pack.vox_size`; an unreadable manifest file is `pack.format`.
56. **`PackOverrides` implements the geometry stage's `ShapeOverrides`** (in `builder/shape/geom`, so the shared
    builder code never sees client classes); the template writes each unit's voxels, kept on `CellUnits.Unit`, in the
    four Game Boy shades.
57. **A template's volume is its Tier A columns** (the plan: its footprint × its drawn rows plus the roof rise). Its
    blocks, and so its collision, stay exactly what V3–V6 built (they turn from `tile_full` into `shape_full`), and its
    fallback is exact. Tier C's geometry stays inside it: a block it leaves empty (above a low flat roof) still
    collides, as an empty template block would in the plan.
58. **Templates are buildings**: a structure region of the measured runs that a door folds into, on an outdoor map,
    within 32 tiles along either side (`templateMax`), touching no swap site and no cell unit. Other regions stay Tier A
    volumes; interiors get dressing, not templates.
59. **A template entry also holds its Tier A keys** (per grid position, bottom-up), so a client rebuilds the fallback
    without planning a fragment of a map, and the signature covers them.
60. **No carving** (the plan: objects split off, a silhouette from the background flood). Carving the roof where the
    drawing is light shows the wall's top from the game's view; carving the facade turned light-painted walls into holes
    the roof floats over (the synthetic house: a light facade without side outlines), and no score sees that because
    the floor under a building shows the same art. So a template's volume is full, as Tier A's is; the background flood
    only tells the panes, and no object is split off.
61. **The bands**: the roof rows are those above the highest pane (or a pack's `struct:` rule); the facade folds
    upright at each run's front exactly as Tier A's bands do (a folded door's columns show the door); panes sink one
    pixel; the roof is one voxel layer on the facade showing, at each depth, the drawing row the game's view sees there
    (the roof rows cycled north of the drawing), so from the game's view it is the drawing. Sides and backs show their
    band's tile and the run's plain back tile, as Tier A's columns do.
62. **Without a pane (and without a rule) a template stays its Tier A columns**, reported as `fallback` without
    `failed` ("keep the generic volume").
63. **The template's self-check** scores every pixel of the structure's tiles from the game's view over its floor
    (texel identity), allowing the door fold's shift in a folded run and a sunk pane's one-row shift; the gate is the
    plan's 0.90 and the 96-quad cap.
64. **Interior walls rise to the ceiling refolded**, not repeated: a wall column (a run along the north, west or east
    edge) is folded as Tier A folds a run of the ceiling's height, so a two-row back wall repeats its top band (the
    plan's words) while a long side wall shows its next row and keeps the game's view. The first real run, which
    repeated the top band's key, lowered the route gates' structure match to 0.3750 and the 5th percentile to 0.4231.
65. **Dressing's rule interface** is `applies(SceneMap)` and `dress(SceneMap, Dress)`; the plan's `StitchedGrid`,
    `ShapeSink` and `PositionHash` don't exist here, and no rule needs randomness.
66. **Open-air interiors** (the forest, and outdoor maps placed as interiors) go under the open sky when the build
    plans shapes: outside the surface's volume, on their usual layer, their footprint reserved on every layer above;
    their placement takes the outdoor biome (sky colour, the outdoor spawn rule), which is how the builder and every
    client know there is no ceiling. A kept ceiling is not raised (the plan said "raised for headroom"): an interior's
    volume follows from its placement alone; `open_air_kept_ceiling` shows whether it ever happens.
67. **A pack's `dressing` is read and checked but not applied**, and `place:` models are read but never drawn: a world's
    dressing is part of its build, and every placement of a structure shares its shapes; a client-local pack can change
    neither.
68. **The tint is two flag bits on the quad** (`ShapeQuad.TINT_SHIFT`): levels 1–3 multiply the vertex colour by
    0.85, 0.69 and 0.53 (with the darkening and a pack model's colour); ceilings use 2. The shape table's geometry
    version is 4.
69. **The styles' pure parts live in `src/main`** (`dev.kantobridge.character`: `SpriteFrames`, `ThickCardMesh`,
    `HullCarver`, `RigSplitter`, `StyleFallback`, `CharacterReport`), not in `client/puppet/style/`: the headless build
    runs them for the report's Characters group, and `src/main` can't see client classes. `client/puppet/style/` holds
    the styles that emit vertices and the per-sheet decisions (`CharacterStyles`).
70. **Rigged figures are quads straight into the sheet**: each box's faces show the frames' own crops (front: the
    standing "down" frame; back: "up", mirrored; sides: "left"; top and bottom: the front's edge rows), and limbs swing
    by turning their quads about the joint. No `ModelPart` is baked and no unwrap texture is made (so there is no
    `RigTextures`): the sheet already holds every face's art. 144 vertices, as planned.
71. **A rig's neck is where the figure narrows and widens again below**: a plain local minimum took a ball's flat
    sides for a neck. A rig still needs walk frames, a neck, a head a quarter to two thirds of the figure, and boxes
    covering 85% of the front.
72. **A client reads a sheet's frame count from its masks** (every frame equal: 1; the walking row equal to the
    standing one: 3; else 6), since it has its resolved sheet, not the export's count; only a 1-frame item or boulder
    revolves. The headless report counts from the export's own frame counts.
73. **Settings**: `characters.style` is unset by default (a pack's suggestion, else thick cards) and
    `characters.packs` on. A pack's own style for a sprite comes first, its `model` before that; the new settings file
    doesn't list `characters`.
74. **`character_fallbacks` also counts `thick_to_flat`** (an empty frame, or more than 256 quads).
75. **The surfing sprite's upright card takes the NPCs' style** (keyed `SURF`); its flat form on the water stays a
    card.
76. **The block-entity renderer and `StoryCellArt`'s slot path stay** (the plan's V9 removes the slot path, and its
    definition of done says the renderer is gone). They are the fallback deviation 39 kept for worlds built before
    V5, whose closed columns have no carrier above feet level: without it, such a world would show its Cut trees and
    locked gates as invisible walls until rebuilt, against I3. A question for you below; removing it is a small change.
77. **Layout 1's code paths were already only "read as no pack"** (`PackInfo`, `LoadedPack`, `TextureSync`): nothing
    else of the old layout was left to remove.
78. **The half cube V1 drew stays as a test resource** (`src/test/resources/kantobridge/v1_half_cube.json`):
    `CubeEmitterTest` still proves the runtime model draws it exactly, now that the jar's file is gone.
79. **Carriers without a slot (decoration, ledges, story tiles) show a transparent particle** through the runtime
    model, as `invisible.json` does for story cells, for distant-terrain mods.

### Voxel plan: open issues

None (V7 put the structure rules and models in the packs' geometry hash, and the key index lists structures).

## Profile plan

Execution of [specs/PROFILE_PLAN.md](specs/PROFILE_PLAN.md) (P1–P4), after your play-test of the voxel plan on
2026-10-04: the trees at Pallet Town and on Route 1 stood as folded cubes, and buildings were painted boxes. Your
decisions (PD1–PD4): use Dramaless Shape's hand-made table, read at runtime from an installed copy of the mod, with our
own rules as the fallback; the tree fallback is option 1; buildings follow the table's band tables.

| # | Milestone | Status | Date | Commit |
|---|---|---|---|---|
| P1 | Profile and outdoor units | **done** (play-test waits for you: install the mod, switched off, then a new world) | 2026-10-04 | `7dab639` |
| P2 | Buildings | **done** (play-test waits for you; a new world) | 2026-10-04 | `5178d71` |
| P3 | Boxes and interiors | **done** (play-test waits for you; a new world) | 2026-10-04 | `6eaa772` |
| P4 | Finish | **done** (the mod installed in your install; switching it off is yours) | 2026-10-04 | `9ff3d74` |

**Why the trees were cubes** (counts from your world, which is identical to the e2e build): V4 made a repeating cell a
unit only when all its tiles were in the map's drawn border block (voxel plan deviation 26). Pallet Town: 123 solid
cells, none with the hint, so no unit; Route 1: 53 units, and 72 more cells whose drawing is round stayed columns;
Route 22: none. Over the 36 outdoor maps, 2,586 of 14,797 solid cells were units; option 1's probe adds 2,378 hulls and
430 objects, and leaves 6,000 low repeating cells (walls, cliffs) as they were.

### Profile plan: check results

- **P1** (the real export with Dramaless Shape 2.0.4 in the test install, switched off; counts only): the export
  carries `profile/dramaless`, byte for byte and reported switched off; the build reads it (`status: ok`, 24 tilesets,
  1,207 pins, 14 conditional pins, 55 building entries, nothing unknown). Units by source: 8,571 pinned, 396 probed, 17
  lone; 235 pins on walkable cells left as they were. Structures: 15,150 hull cells (10,763 before), 1,978 objects
  (29), 35 template buildings (22), 144 fallbacks (249), 0 units or templates failed. 220 maps, 0 warnings, 2,471,966
  blocks in 60.5 s (48,059 blocks/s); 23,016 keys, 30,218 instances, index 1.58 MB, map 243,596 characters; quads per
  block mean 12.5, max 80; per section p95 7,200, max 26,752. Self-check: no hole, ground 1.0, structure p05 0.766
  (0.598 before), min 0.530 (0.375; Pallet Town, whose 45 non-unit solid cells are its houses). Every gate holds.
  `tools/check.sh --e2e`: pytest **1455 passed** (the end-to-end tests included), then `gradlew build`: **796 JUnit**,
  **92/92 GameTests**, the mixin checks.
- **P2** (the same export): 42 of the profile's 55 building entries read (13 furniture entries built from parts wait for
  P3); 235 claims (138 outdoors, the tallest 21 blocks in Saffron City; 97 indoors, in houses, Oak's lab, the Celadon
  Mansion and the Safari Zone, three blocks at most) and one claim-only twin (on Route 10: the Pokémon Tower's other
  half); 82 overhang positions cut (over walls, not ground). 84 band
  templates (236 placements) beside 4 V7 templates (6); structures: 242 buildings (35), 120 fallbacks (144); no template
  failed; a band block holds at most 61 quads. 2,505,147 blocks in 61.5 s (48,415 blocks/s); 58,646 keys, index 2.87 MB
  (4.16 MB before the fallback columns became a height per position), map 392,512 characters of the 8 parts' 524,288.
  Quads per block mean 12.0, max 80; per section p95 7,288, max 26,752. Self-check: no hole, ground 1.0, structure p05
  0.810, min 0.656. Every gate holds.
  `tools/check.sh --e2e`: pytest **1455 passed** (the end-to-end tests included), then `gradlew build`: **810 JUnit**,
  **92/92 GameTests**, the mixin checks. After that, the records were corrected against the build's counts and map ids
  and three tests' assertions tightened (the schema's band columns back to 8, descriptions); `tools/check.sh` again:
  pytest **1418 passed** (37 end-to-end skipped), **92/92 GameTests**.
- **P3** (the same export; planner `kb-shapes/8`): all 55 of the profile's building entries read (the 13
  furniture entries' parts with them): 365 claims, 130 more than P2. Class boxes by tile: counters 4,440, ledges 4,968,
  cliffs 3,952, bookcases 2,308 (1,214 ranks; 1,972 empty rows behind them), tables 800, desks 152, beds 136,
  backrests 48, roofs 192; left flat: 16,318 walkable pins and 1,516 one-way ledges. 182 canopies over 2 × 2 cells in
  Viridian Forest (2 templates); 82 stair and 6 relief units; 77 sunken stairs left flat. Structures: 14,829 hulls,
  1,871 objects, 548 buildings (366 band, 182 hull placements), 127 fallbacks; no template failed; a hull template's
  block holds at most 82 quads. 2,511,236 blocks in 61.2 s; 59,546 keys (1,214 box), index 2.94 MB, map 409,476
  characters. Quads per block mean 11.9, max 87; per section p95 7,304, max 24,064. Self-check (boxes and hulls left
  out of the structure score): no hole, ground 1.0, structure p05 0.730, min 0.530 (Fuchsia City; 0.632 and 0.364 with
  the boxes scored). Every gate holds.
  `tools/check.sh --e2e`: pytest **1455 passed** (the end-to-end tests included), then `gradlew build`: **847 JUnit**,
  **92/92 GameTests**, the mixin checks.
- **P4** (docs; a pack's styles kept off stairs and reliefs; a template's height counts its decor keys; the shape plan
  is P3's): `tools/check.sh`: pytest **1418 passed** (37 end-to-end skipped), then `gradlew build`: **848 JUnit**,
  **92/92 GameTests**, the mixin checks.


### Profile plan: deviations

1. **The voxel plan's I8 (no pixel reaches a key) is narrowed to the probe of PD3.** The planner itself still takes no
   `ArtSource` and its scene holds no image; the builder's own art answers one question per repeating cell (would it
   stand as a hull or an object?), and the answer becomes a unit key. `NoPixelTest` now proves that with the probe
   answering alike, repainted art plans the same shapes. A pixel-free rule would have turned 6,000 plain wall cells
   into units.
2. **The probe runs where the plan is made:** the builder, on the host's export. The client's key index and pack
   template plan with the same probe on the client's own export, so their keys match a world built from that export.
3. **Pinned units are per 16-px cell**: the class of the cell's top-left tile, else its first tile with a unit class.
   Dramaless groups 4-connected tiles of one class; a standee drawn over two cells stands as two units here.
4. **Pinned standees keep our placement** (PD8: the slab's front at the south edge of its lowest pixel row, which the
   self-check needs) with Dramaless's pool depths and segmentation: the background is the shades `prop_bg` names, else
   the non-black shades on the drawing's border, else white; a cutout is strict and keeps its largest piece; a post
   floods through white only. **Their gate is cover, not match:** a thick slab's top hides part of a farther piece in
   the game's top-down view, which is no hole (the first real run: 158 cells, 18 kinds, covered their drawing fully but
   matched 0.82–0.90 by texel, and fell back to cubes). Dramaless has no gate at all; ours keeps "never leave the
   drawing to the floor" and the quad cap.
5. **A pinned cylinder tries the hull even where the round test says no.** The hull's gates stay (cover 0.4, 96
   quads), so one that fails draws its column version; our hulls keep their half-block depth steps (PD9).
6. **Pinned cells are never structural** (the research: authored tiles never join Dramaless's volume regions). A solid
   cell the profile pins to a unit class leaves Tier A's solid tiles before runs and regions are measured, and stands
   as its own unit, two blocks high: a sign or a post beside a house no longer joins the house's region (on the first
   real run, unit-touching regions cost 8 of 22 templates), and a pinned tree in a run Tier A measured taller is a
   two-block hull. Feet-level colliders are the same (I1). A pin on a walkable cell is counted (`pin_not_solid`), never
   forced. Pins of other classes (walls, counters, tables, …) wait for P3.
7. **`figures` and `mounted` are not read** (PD5): their masks are traced from the game's art, and the shape index
   travels.
8. **The export's kind list has one owner:** the client's `ExportClient` now uses `ExportBundle.KINDS`.
9. **gen1-bridge reads the table at mods.loaded** (boot and hot reload) through the loader in the event's payload, an
   engine internal (`engine.modDataFile`): the sandbox keeps `mod:read` inside a mod's own folder. The text is never
   run; Java reads it as data (`scene/profile/LuaData`), which refuses anything but literals.
10. **The test install holds a copy of your mod** (`tools/e2e/install_profile_mod.py`: its runtime files and data only,
    never `assets/`, `tests/` or `tools/`), switched off once by a driver (`drivers/kb_mods_off.lua`); Gen1Recomp keeps
    the switch.
11. **Counters fold from P1** (brought forward from P3, the research's `counter`: one band): a counter's block shows its
    column's front panel on the south and side faces and the row above on top, as Tier A folds a run one block high,
    where before every face showed its own tile. Units took the trees and props out of the maps' structure scores, which
    left the route gates scoring their counters alone (0.375–0.46, under the 0.55 floor); folded, a counter shows its
    drawing in the game's view.
12. **The self-check lets a ledge lip lift its art by its own height** (the lip's 4/16 block: two Game Boy pixels): a
    ledge pixel may also match the texel two rows lower, as a template's pane may shift one. Routes 1, 14 and 17 have no
    structure left but ledges once their trees are units, and scored 0.125 texel for texel.

13. **P2: a band building's volume is its model's bound** from feet level, at least two blocks on a solid tile (under
    the ceiling indoors), not the higher of it and the Tier A height (§5.5): claimed tiles never reach Tier A, so they
    have none, and blocks above the model would be empty carriers. The entry carries the heights per position
    (`band.solid`, `band.air`) instead of spelt-out fallback keys (the index: 2.87 MB, not 4.16 MB).
14. **The plain-cube fallback also covers** a tile this client's art lacks and a model over 16 M voxels, beside the
    96-quad cap.
15. **A claim-only twin keeps two blocks of collision** (§5.5: its Tier A height, which claimed tiles don't have): the
    least a player can't step or jump onto (I1).
16. **An overhang past the map's edge is cut** too (counted), as one over a wall is.
17. **The surface volume reaches ground + 32** (it was ground + 8): the tallest building stands 21 blocks. The placer
    writes only where the world differs (`BlockPlacer.apply`), so the air above costs reads; the build's time didn't
    move (61.5 s against 60.5 s). A rebuild now also clears anything placed between ground + 9 and ground + 32.
18. **Interiors' roof-path entries are built in P2** (97 claims; §5.6 gives them to P3): §5.5's match runs on every map
    whose tileset has buildings, and the heights clamp under the ceiling. P3 keeps the parts path.
19. **P3: `wall` pins stay Tier A's** (measured columns, V7 templates, interior walls up to the ceiling). The profile's
    `wall` is its fallback class for any solid tile (16 pixels everywhere); our own rules know more: roofs on
    buildings the profile doesn't model, and rooms whose walls meet their ceiling.
20. **A pin on a walkable or ledge tile changes nothing** (16,318 walkable tiles, mostly the ledge floors of caves and
    the Safari Zone; 1,516 one-way ledges): Dramaless raises them to their class height, but here collision decides
    the floor, so they stay flat. Likewise a sunken stair (its well would need the floor opened): counted
    (`stair_down`), left flat.
21. **A counter cell's box stands one block** (its collision, V-plan): a table or bookcase pinned on one is cut to 8 px.
22. **Heights clamp to the map's headroom** (`ShapeHeights`; Dramaless has no ceiling): three blocks under an
    interior's ceiling, four in an open-air interior (Viridian Forest, the Safari Zone: a canopy stands whole there),
    the surface volume outdoors. If an open-air interior ever kept its ceiling, `plan()` stops its columns under it.
23. **Every box is culled by height** (Dramaless culls a bookcase's flanks only against other bookcases): a side is left
    out where the neighbour (a box, or a column's full cubes) stands at least as high.
24. **Bookcase panes per tile face**, not per bank face (a pane must not touch its own tile's edges), merged into
    rectangles with their reveals; over 48 quads the face stays flat. Flanks show their band tile 1:1 (Dramaless
    stretches one tile over the box's 16-pixel depth).
25. **A relief's flood runs per cell** (Dramaless: the relief region's bounding box); the same for a one-cell region.
26. **Stairs are voxels**: each the drawing's pixel at its column and height (Dramaless maps treads and risers as
    stretched slivers of the same rows). They stand on decor carriers; a stair whose bottom block a story block takes
    (the boulder maps) stays out (`shape_decor_skipped`).
27. **A can's well walls show the body's art inside** (one texel per voxel; Dramaless lays the mouth's rows there).
28. **The `support` rule is left out** (a pinned standee north of a box, lifted onto it): our units stand in their own
    cell, two blocks high; about 260 cells, 75 of them on counters and tables. An open issue.
29. **The self-check leaves class boxes and two-cell hulls out of the structure score**, as P2's band buildings: their
    heights are the profile's, so their tops show away from their drawn rows in the game's view, by design. Holes
    still count.
30. **A part's `rise` may be negative** (one entry lowers a part under its plane; Dramaless doesn't range-check it): the
    model clips at the floor. `desk.plane`, which the research found mentioned but never read, isn't read either.
31. **P4: a pack's `class:tree`/`class:prop` style doesn't reach stairs and reliefs** (they're neither, and stand on
    walkable ground: a `columns` style would put cubes across the way); stumps and cans follow it as pinned cylinders do.
32. **A template's height counts its decor keys** (P2's doorways and overhangs): `keys.json` reported a band building's
    or hull's height as 0 (its columns are empty since P2), and a pack's `struct:` model couldn't fill a doorway. Both use
    `Templates.Entry.keysAt` now.

### Profile plan: open issues

- **Your own install:** the mod was copied into `local/gen1recomp/mods/DRAMALESS_SHAPE/` on 2026-10-04 at your request
  (59 files from `refs/DRAMALESS_SHAPE-main/`, each checked by hash; no `assets/`, `tests/` or `tools/`). Switching it
  off is yours, in the launcher or with F10 (PLAYTEST.md, "Profile plan, P1"); then a new world shows P1–P3.
- The `support` rule (deviation 28): standees on counters, tables and bookcases stand on the floor behind them.

## Caves and woods

Execution of [specs/CAVES_AND_WOODS_PLAN.md](specs/CAVES_AND_WOODS_PLAN.md) (CW1–CW5), from your choices of 2026-10-11
(CD1–CD6; the defaults CD7–CD15) and 2026-10-12 (CD16: the forest's apron, in Minecraft's own logs and leaves; its
defaults CD17–CD24): Kanto in First Person's cave rock, canopy, vines and apron, re-implemented from its rules (never
installed, nothing copied). Feature tests per milestone; the full suite at CW2, CW5 and CW3.

| # | Milestone | Status | Date | Commit |
|---|---|---|---|---|
| CW1 | Cave rock | **done** (13 new JUnit tests; JUnit 948 green; the GameTests run at CW2) | 2026-10-11 | `CW1: cave rock` |
| CW2 | The forest canopy and vines | **done**; checkpoint: JUnit 958, GameTests 98, Python and Lua 1,484 | 2026-10-11 | `CW2: the forest canopy and vines` |
| CW3 | The forest's apron | **done** in vanilla logs and leaves (CD16); JUnit 968, GameTests 98, Python golden and gates | 2026-10-12 | `CW3: the forest's apron` |
| CW4 | Vines in the wild zone | **done** (zone JUnit 27 green; the zone GameTests run at CW5) | 2026-10-11 | `CW4: vines in the wild zone` |
| CW5 | Finish | **done**: checkpoint JUnit 959, GameTests 98, Python and Lua 1,484; the real world built and filmed on the pod | 2026-10-11 | `5b61348`, `CW5: finish` |

### Caves and woods: CW3 notes

1. **Your call (2026-10-12):** yes to the apron, built from Minecraft's own logs and leaves so it costs the shape map
   nothing and Minecraft's renderer draws it; CD7–CD15 kept. The forest's own canopy keeps the map's art.
2. **What it is** (`Apron`, `kb-shapes/11`): four cells (8 blocks) of wood past Viridian Forest's edge, by the mod's
   rules. Every apron block has a floor block in the forest's grass tile. 62% of the cells mirror the nearest map cell:
   a solid one as a 2 × 2 × 2 bush of leaves (16% carried up to 3–6 blocks), an open one with a log trunk up into the
   canopy 30% of the time. Oak or dark oak per cell, half each. The canopy carries on over the apron, thin: each cell's
   underside block, filled up to where its neighbours' undersides start, and the upper layer at feet + 9. Vanilla vines
   hang on its leaves' sides, one face in nine, 2–4 blocks, never below feet + 2. A moss wall 9 blocks out closes it,
   from the floor block to feet + 9.
3. **The edge you walk to:** the enclosure ring keeps its collision as barriers wherever the apron leaves air (the
   apron's leaves and logs are solid already), so you see through it and can't pass. Its floor block wears the grass.
   The CW2 curtain on the ring is gone: the apron's canopy covers the ring.
4. **Room:** an open-sky interior's volume, shape-map grid and biome box now reach 9 blocks past the map. That is inside
   the packer's 16-block gap, so nothing else can stand there. The Safari Zone keeps its ring; its margin is air.
5. **Leaf colour:** the outdoor biome gets the wild zone's grass and foliage colours. It had none, so Minecraft would
   have tinted the leaves savanna yellow. Nothing vanilla stood in it before, so nothing else changes.
6. **Tests:** `ApronTest` (the box, mirrors, the thin canopy, an invisible wall without a gap, vines out of reach, the
   floor tile, the same apron every time), `CanopyTest` (kb-shapes/11 swaps the curtain for the apron; kb-shapes/10 and
   other maps unchanged), `CanopyPlanTest` (the built ring, every apron block as planned, no shape ids past the map).
   The golden synthetic shapes changed only in planner, parameters and hashes.
7. **The real world** (your Blue on the pod's test install: kb-dev, because kb-dev3's host was full; about 24 minutes,
   stopped after): 220 maps, 0 warnings, built in 75 s. Against the CW5 world (kb-shapes/10):

   | | kb-shapes/10 | kb-shapes/11 | Budget |
   |---|---|---|---|
   | Shape map on the wire (characters) | 303,816 | 305,336 (58%) | 524,288 |
   | Blocks placed | 4,217,788 | 4,362,390 | |
   | Quads per block, max / mean | 76 / 10.41 | 76 / 10.44 | 96 / 24 |
   | Quads per section, P95 / max | 6,416 / 24,064 | 6,416 / 24,064 | 12,000 / 30,000 |
   | Self-check: holes, ground, structure P05 | 0, 1.0, 0.731 | 0, 1.0, 0.731 | 0, 1.0, 0.577 |

   The apron costs the wire 1,520 characters (the wider grid's empty columns). Pieces: 720 apron cells (86 wells), 420
   bushes (65 taller), 2 trunks (the forest's edge is trees almost everywhere; only its exits are open ground), 527
   vines. Every budget gate passes except `template_fallbacks` (18 building placements against a floor of 20). It fails
   the same way on the pod's kb-shapes/8 and kb-shapes/10 worlds: those builds have no Dramaless profile, so it is older
   than this plan (see CW5 note 2).
8. **The video** (16 s, 1280 × 720): `showcase.py --scene tour --stops VIRIDIAN_FOREST`, a look round from beside the
   north exit; sent to you, the original on the volume (`/workspace/videos/kanto-bridge-apron-20261012-003552.mp4`).

### Caves and woods: CW5 notes

1. **Checkpoint:** JUnit 959, GameTests 98 (all on this server), Python and Lua 1,484.
2. **The real world** (your Blue on the pod's test install, all 220 maps, a new pod `kb-dev3` because kb-dev's and
   kb-dev2's hosts were full; about 12 minutes, stopped after): built by the dev client's "Create Kanto world"; the
   headless build (`build_world.py`) needs the Minecraft EULA on the pod, which only you can accept (PLAN §0.6). Every
   gate a build report can check passes (`budget_gates(full_build=False)`). Correction found at CW3: of the full-build
   gates, `template_fallbacks` fails on every pod world, kb-shapes/8 included (18 building placements against a floor
   of 20; no Dramaless profile there), so it is older than this plan. Against the pod's last world (kb-shapes/8):

   | | kb-shapes/8 | kb-shapes/10 | Budget |
   |---|---|---|---|
   | Shape map on the wire (characters) | 219,192 | 303,816 (58%) | 524,288 |
   | Quads per block, max / mean | 76 / 11.6 | 76 / 10.4 | 96 / 24 |
   | Quads per section, P95 / max | 6,416 / 24,064 | 6,416 / 24,064 | 12,000 / 30,000 |
   | Self-check: holes, ground, structure P05 | 0, 1.0, 0.731 | 0, 1.0, 0.731 | 0, 1.0, 0.577 |

   Pieces: 10,677 roofed cave cells, 1,259 stalactites, 77 stalagmites (807 spikes dropped by the clamps), 1,450 canopy
   cells and 182 wells, 165 leaf strands and 288 stem-and-stub strands (12 crowded out).
3. **The wire budget with the profile:** the pod's test install has no Dramaless Shape, so both pod worlds are without
   it. P3's build with the profile measured 409,476 characters (78%, "Profile plan: check results"); this plan adds
   about 85,000, so expect about 94% on a world built with the mod installed: inside the budget, with little room left.
   If a build goes over (the gate `shape_map_fits` says so), the plan's §7 ladder applies (fewer roof steps, then
   client-side variants).
4. **The video** (31 s, 1280 × 720, about 55 fps): `showcase.py --scene tour`, a first-person look round Mt. Moon 1F and
   then Viridian Forest; sent to you, the original on the volume
   (`/workspace/videos/kanto-bridge-caves-woods-20261011-224130.mp4`); this server's copy deleted.

### Caves and woods: CW4 notes

1. **Where:** Minecraft vines (new `ZoneBlock`s, one per facing) on the maze's hedges over walkable columns, from the
   hedge's top down two to four blocks but never below feet + 2, and on the scenery trees: a column just outside a
   crown's four wide faces hangs one from the upper wide layer, two blocks clear of the ground, none where another tree
   is in the way. One face in nine, by the zone's own hash.
2. **Why feet + 2:** vines are climbable from the block a player's feet are in; a player who can't jump never has feet
   there, so the maze stays closed. `ZonePlanTest` checks every vine: out of reach beside the maze, hanging on leaves or
   the vine above, never over a wall, rock or pond. The old check "nothing above head height on a maze cell" now allows
   vines from feet + 2.
3. **Placement:** blocks go in with "known shape" flags (no neighbour updates), so a vine placed before its leaves stays;
   vine spreading is off (`KantoGameRules`).

### Caves and woods: CW2 notes

1. **Which wood:** `Env.canopy`, set by the Gen 1 front end for `VIRIDIAN_FOREST` only: Dramaless Shape's own canopy list
   (`lib/DayNight.lua`, `DayNight.CANOPY`), which Kanto in First Person reads. The Safari Zone shares the forest tileset
   and keeps its sky.
2. **The canopy** is a filled mass (CD13): each cell's columns hold leaves from its underside (40–62 px, levels 5–7) up
   to 80 px (feet + 10), in a leaf tile picked per cell from the wood's five commonest tall tiles; one cell in nine is a
   light well showing the upper layer (74–79 px). The tests found a hole: an upper layer rounding to 80 px left a well
   empty; it is clamped to 79.
3. **Vines:** both of the mod's kinds, from its formulas; a strand's tail stays at head height over a path and at a
   tree's top (`CanopyTest` checks every one). Where the underside lies inside a block, the strand's column leaves that
   block empty instead of a box: the strand hangs from a small hollow, so one block never needs two pieces.
4. **The 1/16 grid** turns the leaf strands to any of 16 directions, but the stem-and-stub strands' one-unit columns
   only along the axes or the diagonals.
5. **The curtain** on the enclosure ring is one key (the commonest leaf, darkened), where the mod picks a leaf per cell.
6. **The volume:** an open-sky interior reaches feet + 11, its biome box feet + 12. A wood that kept its ceiling (no
   sky spot) hangs nothing: under a ceiling a column's hangings stand only if all of them fit (`ShapePlanner.hungFits`).
7. **In a built world** (`CanopyPlanTest`: the synthetic house renamed `VIRIDIAN_FOREST` and opened to the sky): every
   hanging on a foliage carrier with its piece in the shape map, the ring's own blocks kept and the curtain above them.

### Caves and woods: CW1 notes

1. **The position hash** gives the mod's cells: Java's `StrictMath` matches CPython's libm to 1e-12 on six probes and
   picks the same 2,040 "one in five" cells of a 100 × 100 grid (`CellHashTest`).
2. **Hangings are a layer of their own** (`MapShapes.hung`, `hungMask`), planned last over the final columns (after
   P3's class boxes), so the self-check, unit tallies and puppets never see them. `Dress.free` leaves the feet level,
   a column's blocks and any decor carrier alone; swap-site cells get none.
3. **In a built world** (the synthetic house drawn as a cave), every roofed column's block under the ceiling is a decor
   carrier whose piece the shape map holds; carriers on the light grid are `lit` and no light block goes there
   (`CaveRockPlanTest`). Budgets count the pieces (QuadCounter reads the shape map).
4. **Sizes in our three-block caves** (CD8): the rock 16–22 px above the floor (2–2.75 blocks); a stalactite over a path
   at most 6 px long (it ends at head height); stalagmites 2–4 px on rock tops. Most spikes are small: the mod's full
   sizes need its four-block room (your CD5).
5. **No GameTest yet:** the synthetic world has no cave. The build's codes are checked by `CaveRockPlanTest`, the decor
   block's collision and light by `ShapeBlockTest`; the GameTests run at the CW2 checkpoint.
6. **Golden:** `synthetic_shapes.json` changed only in its planner (`kb-shapes/9`), parameters (`caveRock`) and hashes.

## Caves in 3D

Execution of [specs/CAVES_3D_PLAN.md](specs/CAVES_3D_PLAN.md) (H1–H7), approved by you on 2026-10-12 with its
defaults: raised ground in caves, caves in an area of their own, real ladders and holes. Feature tests per milestone;
the full suite at H1, H5 and H7. **All seven are done** (2026-10-13): every cave is built in 3D.

| # | Milestone | Status | Date | Commit |
|---|---|---|---|---|
| H1 | A position per cell | **done**; checkpoint: JUnit 979 (6 skipped), GameTests 98, Python and Lua 1,484 | 2026-10-12 | `H1: a position per cell` |
| H2 | Edge walls on tile-pair edges | **done** (PairEdgesTest 4, a wall GameTest; JUnit builder suite, GameTests 99) | 2026-10-12 | `H2: edge walls on tile-pair edges` |
| H3 | Caves in an area of their own | **done** (CavePlannerTest 8, the synthetic cave; JUnit 991, GameTests 99, Python 1,487); the real world's dry run on the pod: 0 warnings (note 8) | 2026-10-12 | `H3: caves in an area of their own` |
| H4 | Raised ground in caves | **done** (CaveLevelsTest 8, RaisedCaveTest 7, two GameTests; JUnit 1,010, GameTests 100, Python 1,487); the real world's dry run on the pod: note 11 | 2026-10-12 | `H4: raised ground in caves` |
| H5 | Real ladders and holes | **done**; checkpoint: JUnit 1,032 (7 skipped), GameTests 101, Python and Lua 1,516; the real world's dry run: note 13 | 2026-10-13 | `H5 (1/3)`–`(3/3)` |
| H6 | Pilot | **done**: Mt. Moon built in 3D on the pod, every budget holds, the video sent; you said go on (note 9) | 2026-10-13 | `cda5000`–`9546fe1`, `H6: pilot` |
| H7 | Every cave in 3D | **done**; checkpoint: JUnit 1,044 (7 skipped), GameTests 101, Python and Lua 1,518; every budget holds (the shape map about 97% with the profile); the video sent; note 10 | 2026-10-13 | `H7 (1/2)`, `H7: every cave in 3D` |

### Caves in 3D: H1 notes

1. **The index:** `world/MapLayout` holds a map's `pieces` (cell rectangles with an optional run-length mask and a block
   offset), `levels` (run-length, blocks above the piece's feet) and `ramps`. They are optional keys of a map's
   WorldIndex entry, each written only when it differs from the default, so `version` stays 1 and a world without
   layouts keeps its bytes (the goldens didn't change). The schema has the three keys.
2. **Lookups:** `cellOrigin` and `toBlock` go through the piece and the level; `toCell` checks a layout map's pieces
   with the cell's own window (feet − 3 to feet + 4); `levelOrigin` is the piece's feet, which a movement `Spot` now
   keeps as its level, so a ramp won't look like a level change.
3. **Readers moved:** puppets (position from the cell's origin; culling per piece, ±5.5 blocks), clicks (`ground`,
   `ClosedArtPick`, `InteractTarget.cellOf`/`own`), story art, `ClientShapes.topHeight`, the dev autopilot. Server warps,
   battles and encounters already went through `toBlock`. The encounter and proxy guards also refuse a cell more than
   4 blocks above or below the player (another level).
4. **First-match lookups fixed:** the shape map goes on past a grid whose stack doesn't reach y (`Grid.covers`), and
   `ClosedArt.at(x, y, z)` picks, of the overlays in one column, the highest at or below y.
5. **Deviation (plan §3.1, "every caller"):** the builder's own position maths (columns, shape grids, habitats, volumes,
   biome boxes) moves when it starts producing layouts (H3–H5). It produces none yet, so the change would be
   untestable now. `WorldPlan.withZones` already carries layouts over.
6. **New tests:** `MapLayoutTest` (8), plus cases in `ClosedArtTest`, `ShapeMapTest`, `PuppetCullingTest`,
   `EncounterRulesTest` and `BattleRulesTest`.

### Caves in 3D: H2 notes

1. **The table:** `ExportField.TilePairs` reads the field table `tilePairs` (`land` and `water` lists of
   `{tileset, a, b}`, or `[tileset, a, b]`), either order of a pair. A table or entry shaped otherwise is left out, so a
   build goes on without walls rather than failing. The real export's table hasn't been read yet: H3's pod build
   reports the note `tile_pair_edges` (the plan's "a pod build counts the walls", moved there to share one pod run).
2. **The edges:** `PairEdges` closes the edge between two neighbouring cells whose collision tiles (bottom-left tile)
   form a land pair (both walked on) or a water pair (one of them water). Each cell walls its own side of the edge.
3. **The wall:** `kantobridge:edge_wall` (`EdgeWallBlock`): invisible, no outline, plates 2 px thick and 24 px high on
   its set sides, for every entity. `BlockCode.KIND_EDGE` (11); the plan puts it at the cell's feet, else in the block
   above, which still stops a player 1.8 blocks tall (a GameTest checks both).
4. **Deviation (plan §3.2):** no story-cell block carries walls. Feet or the block above is free on every walkable cell
   the planner knows of (boulder and water cells take only feet), and both cells of an edge carry a wall, so one is
   enough. If the pod build ever shows a side without room, the story-cell variant follows.

### Caves in 3D: H3 notes

1. **Which maps:** `CavePlanner.caves`: the requested non-surface maps of the CAVERN tileset, and any interior whose
   door is in one of them. `WorldPlanner` places them before the interior rounds and leaves them out of those.
2. **Complexes and floors:** caves joined by a warp or a door form a complex. A floor's rank comes from its id (`…nF`,
   `…BnF`, else 0). The top floor stands at feet 64 and each lower floor 11 blocks under the one above (HD3). Breadth
   first from the entrance, each floor sits so a warp between it and a floor already placed has both ends in one
   column; a floor that would overlap another of its level moves east (note `cave_floor_moved_aside`).
3. **The area:** slots of a complex's box plus 8 blocks, 64 apart, in rows up to 2,048 blocks wide, from Kanto's west
   edge, whole chunks, the area's south edge 3,000 blocks north of the dungeon region. Caves keep kind INTERIOR;
   their `layer` is 1 + the floor's rank. Notes `cave_complexes` and `cave_maps`.
4. **Biome and spawns:** the caves get `kantobridge:cave_3d`, a copy of `kanto_cave` that the spawn rule's
   walking-level test doesn't list. Two things keep wild Pokémon on floors instead:
   - **the rock fill:** over a rock or counter cell, the air up to the ceiling is solid: a hanging there is a solid
     carrier (`DecorBlock.SOLID`), a light's position a lit solid carrier, anywhere else a barrier;
   - **no ground on the helpers:** Cobblemon 1.8.1 stands a Pokémon only on a block that `isSolid()` (its
     `GroundedSpawnablePositionCalculator`), so the invisible blocks (edge walls, ledge colliders, story cells) are
     `forceSolidOff()`. A GameTest checks it.
5. **Habitat blocks** in the cave area reach 6 (`HabitatPlanner.CAVE_RANGE`, HD8), written as the habitat's `range`
   (WorldIndex and schema; only when it isn't 14). `HabitatService` places, checks and repairs each block with its own
   range. A JUnit test checks that no cave habitat reaches the other floor.
6. **The synthetic cave:** `tools/fake_gen1/synth.py` has a CAVERN tileset and `TEST_CAVE_1F`/`TEST_CAVE_B1F`: a
   plateau with stairs, a pair edge, a ladder that lines up whole, two that need pieces (H5), a hole, and a mouth off
   `TEST_TOWN` (17, 9). The fake engine (`world.py`) knows its warps. The bundle copy and the goldens (index, shapes,
   scene) were regenerated: only the cave's maps and habitats, and the town's new warp, changed.
7. **Deviation (plan §3.1, §4.1):** no `caves` key in the WorldIndex. A rebuild clears the caves from their map
   placements, as any interior's (`volumesOf`), so the key had no reader.
8. **The real world's dry run** (2026-10-12, kb-dev, about 8 minutes): `RealWorldDryRunTest` with
   `-Pkb.realBundle=<the pod's export bundle>` plans the world and writes counts and map ids only to
   `build/reports/caves3d_dry_run.json`. Your game's export:
   - 220 maps (184 interiors, deepest layer 14, the same 1 deep spill and 5 moved sideways), **0 warnings**;
   - **tile pairs:** all 14 read (`{land, water}` as the plan assumed), closing **642 edges** on 17 maps: Mt. Moon 1F
     (131) and B2F (12), Rock Tunnel 1F (107) and B1F (144), Diglett's Cave (30), Cerulean Cave 1F (22) and B1F (23),
     Seafoam B1F–B4F (7–19), Victory Road 1F–3F (8–18), and the Safari Zone's east, north and west areas (20–25);
   - **the cave area:** 6 complexes, 19 maps (Diglett's Cave's two entrance rooms are cave maps and moved aside of
     the cave on its level), floors from feet 64 down to 20 (Seafoam's five);
   - habitat blocks: 117 of reach 14, **752 of reach 6** in the caves (296 in all before); the shape map 302,788
     characters on the wire (58%, the pod has no profile).

### Caves in 3D: H4 notes

1. **Which caves:** `Caves3d`, the pilot switch: the system property `kantobridge.caves3d` (map ids, comma-separated,
   or `all`), else the pilot, Mt. Moon and the synthetic cave. A complex is built in 3D when any of its maps is named.
2. **The levels** (`CaveLevels`, §3.4 steps 1–7): a tileset's land pairs split its tiles into two sides (a 2-colouring;
   tiles in no pair are neutral); a neutral floor cell with one side on one side and the other opposite is a stairs
   cell; regions are a flood fill over the steps the engine allows; the high side comes from the profile's raised pins
   (a walkable tile pinned to an upright or top class, `wall` aside), else the cliff face (across an east–west pair
   edge, the south cell: note 11), else the map stays flat. Levels 0 and 2; rock as high as the highest cell around it; then the
   check. Notes `cave_levels_<outcome>` (with the map, and `high`), `cave_raised_cells`, `cave_ramps`,
   `cave_stairs_profile_confirmed`/`_other`. A flat map says why: `no_pairs`, `flat_one_side`, `flat_ledges`,
   `flat_pairs_not_bipartite`, `flat_stairs_both_axes`, `flat_mixed_region`, `flat_no_high_side` or `flat_check_*`.
   Stairs may stand side by side (a wide stair), not one onto another.
3. **The index** carries a raised map's `levels` and `ramps` (H1's keys).
4. **What gets built:**
   - each cell's floor at its own level, rock in the ceiling's art under a raised floor down to the map's floor;
   - a ceiling 3 blocks over each floor (over a ramp's top), closed up to the highest ceiling of its eight
     neighbours; the enclosure ring and the volume reach the highest ceiling; hangings and lights count from a ramp's
     top;
   - ramps: `kantobridge:step_collider` (`StepColliderBlock`; `BlockCode.KIND_STEP` 12; carrier kind `STEP`, so the
     stair's art draws on it): two half steps facing the climb, 8 px over its front half and 16 px over its back. The
     low row has one on the floor, the high row a full block with one on top;
   - edge walls at each cell's own feet; the low side keeps its own too (harmless). Where neither feet nor the block
     above is free (a swap site's story block under its closed art), the story block carries the wall's plates itself,
     whatever the story says (§3.2's variant, which H2 left for this case; a GameTest checks it);
   - a story block on a ramp stands on its steps (feet + 1 over the low row, + 2 over the high row), so the steps stay:
     in a map with Strength boulders every walkable cell is a story cell. A swap site's can't (its closed art starts at
     the floor), so it takes the first step, with the warning `cave_ramp_swap_site`;
5. **The stairs' art:** every ramp gets a stair unit climbing its way, whatever the profile pins there (`Units`, source
   `ramp`). East and west keep P3's stairs; north and south (`stair_n`, `stair_s`, two new profile classes) are the
   drawing lying flat, so from above they look as in the game (`HullVariants.stairsAlong`).
6. **Shapes:** each cell's keys stand on its level; the dressed ceiling runs from each cell's ceiling up to its top;
   class boxes cull against their neighbours' real heights. **Deviation (§3.4, self-check):** the self-check scores each
   map in its own frame, where floors are flat, so a raised floor can't count as hidden ground. Risers aren't scored.
7. **Habitat blocks**, **deviation (§3.4, "3 under each cell's own feet"):** the placer keeps habitat blocks outside the
   volumes, so they stay 3 under the map's floor. A raised level's cells are covered with the reach left after its
   extra 2 blocks; a block two levels both pick is placed once. They still never reach another floor: the nearest,
   the next floor down's raised ground, is 7 blocks away for a reach of 6.
8. **Positions:** `toBlock` on a ramp stands on its third half step (1.5 up); puppets climb a ramp evenly
   (`WorldIndex.rampLift`); the encounter's ground scan skips edge walls (an H2 gap: a Pokémon could have stood on one).
9. **The synthetic cave:** the profile's CAVERN entry (the raised floor a `ledge` floor, the stairs `stair_e`); 1F's
   plateau is raised by its pins, B1F stays flat (`flat_one_side`). The bundle copy and the goldens were regenerated:
   1F's levels, ramps and habitats, the two stair units.
10. **Tests:** `CaveLevelsTest` (8), `RaisedCaveTest` (7), and cases in `ClassBoxesTest`, `HullVariantsTest`,
    `CavePlannerTest` (both levels within a habitat's reach, from under the floor), `PairEdgesTest` (a story code's
    sides). GameTests: a step collider's half steps climbed and descended, a two-block face not climbed
    (`CollisionGameTests.caveStepsClimbHalfABlockAtATime`); a story cell with sides keeps its story collision; in the
    built synthetic cave, a mock player walks up the stairs, along the floor, but neither up the cliff nor off the
    plateau; the walkability GameTest stands on each cell's own level.
11. **The real world's dry run** (2026-10-12, kb-dev2 then kb-dev, two runs of a few minutes): `RealWorldDryRunTest`
    now plans every cave in 3D (`kantobridge.caves3d=all`) and adds, per cave map, its outcome, regions, raised cells,
    ramps by direction and the pair edges behind the high side; story cells on ramps by type; pair-edge blocks without
    a wall; and the caves' quads.
    - **The first run caught the cliff rule backwards.** Read as "the north side of an east–west pair edge is high",
      all 29 staircases climbed south and most of each floor was raised (Mt. Moon 1F: 907 cells). A raised area's
      south side is a solid cliff in the art, so the floor meets it flush only along its north edge: the high side
      is south of the edge. Fixed (`b2de37c`); the plan's §3.4 says so now.
    - **After the fix:** all 29 staircases climb north into their platforms, and in every cave every east–west pair
      edge agrees on the high side (none the other way round). 12 of the 19 cave maps are raised (3,462 cells; Mt.
      Moon 1F 237, B2F 552 with 6 stairs; Rock Tunnel 1F 305, B1F 206; Victory Road 63, 64, 61; Seafoam B1F–B3F
      102–322; Cerulean Cave B1F 260; Diglett's Cave 1,176). 7 stay flat: Mt. Moon B1F, Cerulean Cave 2F and Diglett's
      two entrance rooms have one side only, Seafoam B4F and Cerulean Cave 1F reach both sides through tiles in no
      pair (`flat_mixed_region`), Seafoam 1F has no east–west pair edge (`flat_no_high_side`). The pod has no profile,
      so every high side came from the cliff faces.
    - **Story cells on stairs:** 20, all boulder areas (Victory Road and Seafoam B1F–B3F, where every walkable cell
      is one): their blocks now stand on the steps. **Walls:** 2,550 wall blocks, none missing (Victory Road 2F's two
      are on their story blocks). 0 warnings.
    - **Load:** habitat blocks 1,038 of reach 6; the caves' shapes 135,527 shaped blocks, at most 7 quads a block,
      sections p95 2,099 and at most 3,452 quads (budgets 12,000 and 30,000); the shape map 311,004 characters on the
      wire.
    - **The pilot** (HD11): Mt. Moon qualifies (1F and B2F raised, B1F flat).

### Caves in 3D: H5 notes

1. **The protocol, first:** `warp.take` (M→G) `{from{map,x,y}, to{map,x,y,facing}, kind: ladder|hole}`;
   `warp.taken` (G→M) `{ok, reason?}` (`busy`, `mode`, `map`, `far`) with `re`; `warp` may carry the take's `re`.
   Schemas, examples, both catalogs, PROTOCOL.md's "Ladders and holes (H5)" and its WorldIndex `shafts`.
2. **gen1-bridge** (`act/kb_climb.lua`): a take is accepted only while idle, on `from`'s map, with the hidden player on
   `from` or beside it; it queues the engine's own `warp` command (as `Routes.warpTo`), a running script answering
   `busy`. The warp observer gives the next script warp the take's kind (`stairs` or `hole`) and `re`; an expectation
   no warp follows lapses after 120 steps. fake_gen1 answers it the same way.
3. **Rooms** (`CavePieces`): a cave map's walk-graph parts (walkable and water cells; a swap site's cells count as
   walked on, so rooms never depend on the story), each with the rock round it.
4. **The layout** (`CaveRooms`): a link per warp between two maps of the complex; a shaft's when it runs between two
   floors from a hole or a ladder (a warp tile, not a door); a ladder works up too when its bottom leads back (HD10).
   Breadth first from the entrance's room, shafts' links first. A room that would clash with another of its floor moves
   east; rooms of one map may share a rock wall but never a floor, another map's keep H3's 16 blocks. Every map of a
   complex built in 3D is drawn as its rooms (pieces). Fades by reason, as notes `cave_fades_<reason>`: `loop` (a room
   placed by another link), `moved_aside`, `same_floor`, `one_way_up`, `hole_up`, `crosses_floor`, `off_floor`.
5. **Deviations (§3.5):**
   - a hole is the export's hole flag (a pad of its own), not a warp tile;
   - a shaft that would cross a room of a floor between its ends keeps its fade (`crosses_floor`) rather than moving
     that room down. Gen 1's ladders join neighbouring floors, and the dry run found none.
6. **What gets built:**
   - each room at its offset with its own ring; other rooms' cells in its rectangle are rock;
   - per shaft: air from over the bottom cell's feet up through the top cell's floor;
   - `kantobridge:shaft_ladder` (`BlockCode.KIND_LADDER` 13) up the wall you face walking into the top cell (HD6),
     from the bottom cell's feet to the top's. It is Minecraft's ladder model, in the `climbable` tag, and stands with
     no wall behind it. A hole has none;
   - rock round the column where neither room's volume reaches, and a light half way unless the cave is dark;
   - shape keys stay out of shaft columns; habitats, shape grids and biome boxes follow the rooms.
7. **The index:** `shafts` (written only when there are any); a block inside one is in no cell; `shaftAt`, `shaftEnd`.
8. **The client** (`MovementSync`): no step onto a shaft's end (a top, or a bottom climbed up too). Walking onto one,
   or dropping in from its top, starts a climb; in the shaft nothing is sent or corrected. At the far end (feet on its
   floor going down, at its feet level going up) it sends `warp.take` and holds the controls (lock reason `WARP`); the
   warp with its `re` needs no fade or teleport. A refusal (`busy` retried every 200 ms for a second) or no warp in 3 s
   sends the player back through the fade. Stepping back to the hidden player ends the climb. **Deviation (HD12):**
   stepping onto another neighbour walks the hidden player round the end (two steps round a corner, four across)
   without checking the cells first, since the client doesn't know which are walkable; a refused step pulls the
   player back, as any refused step does.
9. **The synthetic cave:** B1F's two rooms lie apart (its east room 4 blocks north of its west room); the hole's
   landing moved to (5, 5) so that it lines up with the ladder into the same room: three shafts.
10. **Tests:** `CaveRoomsTest` (6), `ShaftTest` (5), `ShaftClimbTest` (10), a `MoveControllerTest` case, 14 Lua tests
    (`test_lua_climb.py`), a stand-in test; GameTests: a player falls through the hole onto its bottom cell; the
    ladder is climbable; walkability over rooms.
11. **Flaky:** two dungeon GameTests (Wild Routes' monster house and a cleared slot) failed once and passed on the
    next runs; nothing in H5 touches them.
12. **The checkpoint:** JUnit 1,032 (7 skipped), GameTests 101, Python and Lua 1,516 (46 skipped).
13. **The real world's dry run** (2026-10-13, kb-dev2, two runs of about 4 minutes; kb-dev's host had no room):
    - **87 rooms** on the 19 cave maps (Rock Tunnel 21 and 20, Mt. Moon 3, 4 and 3), 0 warnings, no wall missing;
    - **37 ladders** between cave floors, and no hole (no cave warp cell has the hole flag): **15 are real shafts**,
      8 or 10 blocks long. 22 keep their fade: 10 `loop`, 10 `moved_aside`, and Diglett's Cave's two entrance rooms on
      its own floor (`same_floor`);
    - **Mt. Moon (the pilot):** 3 of its 7 ladders are shafts (two from 1F to B1F, one from B1F to B2F);
    - load: habitat blocks 1,059 of reach 6; the caves' sections p95 2,138 and at most 3,479 quads; the shape map
      319,136 characters on the wire.
14. **Fixed while planning H6's video (2026-10-13):** the whole 2 × 2 top cell was open, so a player climbing back up
    had a block of open shaft behind them and no floor to step back onto. A ladder's top cell now keeps its floor in
    the half away from the ladder (as a ladder up through a hole in vanilla); a hole stays open. The index carries each
    ladder's `wall` (HD6) and its `contains` leaves that floor out; the shape map keeps its shapes. Goldens: the index's
    walls, the shape map's hash only. JUnit 1,036, GameTests 101 (the two flaky dungeon tests failed once more, then
    passed), Python 1,516.

### Caves in 3D: H6 notes

1. **The pilot on the pod's test install** (2026-10-13): Mt. Moon in 3D (this build's default: `kantobridge.caves3d`
   unset), built by the dev client's "Create Kanto world" (world "Kanto (3)"), every other cave flat in its slot.
   kb-dev, kb-dev2 and kb-dev3's hosts were full, so two new pods: kb-dev4 (cpu5c ×16, $0.56/hr) and kb-dev5 (cpu5c ×8,
   $0.28/hr). Four runs, about 40 minutes in all (about $0.31), each pod stopped after its run.
2. **Budgets**, the build report against CW3's world ("Kanto (2)"):

   | | CW3 | The pilot | Budget |
   |---|---|---|---|
   | Shape map on the wire (characters) | 305,336 | 307,000 (59%) | 524,288 |
   | Quads per block, max / mean | 76 / 10.44 | 76 / 10.42 | 96 / 24 |
   | Quads per section, P95 / max | 6,416 / 24,064 | 6,524 / 24,064 | 12,000 / 30,000 |
   | Self-check: holes, structure P05 | 0, 0.731 | 0, 0.731 | 0, 0.577 |
   | Place time | 68 s | 73 s | |

   220 maps, 0 warnings; 5,115,727 blocks (+753,337: mostly H3's cave area, 142 more chunks); by kind, edge walls
   2,548, ladder blocks 68, step colliders 24, habitat blocks 970 (+645). With the profile installed (the pod's test
   install has none) the shape map would be about 95% of its budget (CW5 note 3's 94%, plus 1,664); with every cave in
   3D (H7) about 97% (the H5 dry run's 319,136, 13,800 over CW3). Tight: H7 measures it again.
3. **The pilot's own dry run** (`-Pkb.caves3d=pilot`): Mt. Moon 10 rooms, 1F and B2F raised (789 cells), 6 stairs (all
   on B2F), 3 shafts (1F to B1F twice, 10 blocks; B1F to B2F, 8), 4 fades kept (1 `loop`, 3 `moved_aside`); 0 warnings.
4. **The video** (31 s, sent; on the volume as `/workspace/videos/kanto-bridge-caves3d-pilot-20261013-011810.mp4`),
   from the autopilot's log:
   - **the stairs** (B2F): put at (26, 17), feet 42; on the stairs cell 43.5; on the raised ground 44; five steps, every
     one taken by the hidden game; the edge's wall stopped the player at (26, 12), looking down over the drop and along it;
   - **the ladder** (1F's (5, 5), down to B1F): the game refused the cell across from the ladder, (5, 6) (not plain: an
     object, most likely), so the walk in came from (6, 5); down in 4.3 s (the take: no fade, no teleport), a look round
     at the bottom, up in 5.5 s (the take at the top), off onto the floor beside the ladder and a step to (6, 5). In all
     6 steps, no push, no refusal, no correction; 2 takes, none failed.
5. **Found by the runs and fixed** (each with a test):
   - the ladder's top cell keeps its floor beside the ladder (H5 note 14, `7697198`);
   - while a take holds the controls, sneak stays down on a ladder: no slide back into the shaft (`cda5000`);
   - gen1-bridge dropped a take's warp messages (they carried the take's re but not its connection's generation, so
     main.lua sent them nowhere): the client waited 3 s and pulled the player back. A test drives a take through
     main.lua on the simulated game; it fails on the old code (`469ad05`);
   - a late answer about the waiting hidden player teleported a climber out of the shaft, where the player stands in no
     cell (`469ad05`);
   - at a ladder's bottom with open floor behind the ladder (1F's ladder lands so on B1F), pressing on the ladder rose a
     block on the bottom cell, looked like a push, and the game took it, walking the hidden player away. Leaving that
     floor now starts the climb, pressing toward the ladder from its bottom is never a push, and a hop off the bottom is
     an ordinary step (`9546fe1`).
6. **Tooling:** `client/dev/PilotPlan` (the shots from the index and the export's cells, every option in order),
   Autopilot scene `pilot` (requests through files, a clip per shot, a tick trace of the ladder in its log),
   `tools/e2e/drivers/kb_pilot.lua`, `showcase.py --scene pilot`, the dry run's `-Pkb.caves3d=pilot`.
7. **Checks:** JUnit 1,041 (7 skipped) at the end; GameTests 101 after the shaft fix (the two flaky dungeon tests, H5
   note 11, failed once more and passed on the rerun); Python and Lua 1,518.
8. **Not shown:** a hole (Mt. Moon has none, and no cave warp cell in the export has the hole flag: H5 note 13). The
   client's log has two errors at start-up, both from Minecraft's sound engine (no audio device under Xvfb).
9. **Your call** (the plan's H6): go on to H7 (every cave in 3D, the full suite, a second video), or change something
   first. You said "Continue to H7" (2026-10-13), with no change.

### Caves in 3D: H7 notes

1. **Every cave in 3D:** the build's default is now every complex (`kantobridge.caves3d` unset). Map ids name fewer,
   and a value that names no cave (`none`) builds them all flat, as before H4. `Caves3dTest`.
2. **Found and fixed:** Flash's Night Vision (`FlashService`) asked which cell the player stands in, and a shaft is in
   no cell (H5 note 7), so in a lit Rock Tunnel it went dark half way up a ladder. It now follows the shaft's ends
   (`FlashServiceTest`; the old code fails it).
3. **A test brought up to date:** `CaveRockPlanTest` draws the synthetic house as a cave. Built in 3D, the house's
   stairs to its upper floor are a real ladder, and the shaft's column rightly takes no stalactite: the test now checks
   that, where it expected a carrier.
4. **The real world's dry run** (every cave in 3D): the same as H5's (note 13): 87 rooms on the 19 cave maps, 12 maps
   raised (3,462 cells, 29 stairs), 15 real ladders of 37 (22 keep their fade: 10 `loop`, 10 `moved_aside`, Diglett's
   Cave's 2 `same_floor`), no hole, 0 warnings.
5. **The world on the pod's test install** ("Kanto (4)", built by the dev client's "Create Kanto world"):

   | | CW3 | The pilot (H6) | Every cave (H7) | Budget |
   |---|---|---|---|---|
   | Shape map on the wire (characters) | 305,336 | 307,000 | 319,132 (61%) | 524,288 |
   | Quads per block, max / mean | 76 / 10.44 | 76 / 10.42 | 76 / 10.23 | 96 / 24 |
   | Quads per section, P95 / max | 6,416 / 24,064 | 6,524 / 24,064 | 6,424 / 24,064 | 12,000 / 30,000 |
   | Self-check: holes, structure P05 | 0, 0.731 | 0, 0.731 | 0, 0.731 | 0, 0.577 |
   | Plan / place time | 6.5 / 68 s | 7.6 / 73 s | 15.2 / 94 s | |

   220 maps, 0 warnings; 4,195,703 blocks (920,000 fewer than the pilot's world: rooms need less rock than whole flat
   floors), 2,808 chunks; ladder blocks 344, step colliders 116, habitat blocks 1,192. The densest section is the same
   one as in CW3's world, outside the caves.
6. **The shape map with the profile** (the pod's test install has none): P3's 409,476 plus the 99,940 this plan and the
   caves and woods plan added since make about 509,400, **97%** of the budget, with about 14,900 characters to spare.
   Inside, as the plan expected; but the next feature that adds shapes should first send grids on demand (the voxel
   plan's R10).
7. **The video** (2 min 9 s, sent; on the volume as
   `/workspace/videos/kanto-bridge-caves3d-all-20261013-035218.mp4`, sent as a 26.6 MB copy), one shot per cave, each
   with its first option:
   - Rock Tunnel: 1F's ladder down to B1F and back up, lit (the driver sets Flash's flag, as Flash does);
   - Victory Road: 2F's stairs to the edge; 3F's ladder down to 2F and back;
   - Seafoam Islands: B3F's stairs and a 12-cell walk to the edge; 1F's ladder down to B1F and back;
   - Cerulean Cave: B1F's stairs; 2F's ladder down to 1F and back.

   27 steps, 12 climbs and 8 takes, none failed; no push, refusal or correction. Down 3.6–4.3 s, up 4.7–5.5 s. The
   client's log has three errors at start-up, as before: Adorn's optional class and Minecraft's sound engine twice (no
   audio device under Xvfb).
8. **Tooling:** the autopilot's `kantobridge.autopilot.shots` (`showcase.py --shots`: `stairs:<map>`, `ladder:<map>`);
   `kb_pilot.lua` gives a level 100 starter with REPEL steps (no wild battle in a shot) and sets Flash's flag on each
   arrival.
9. **The checkpoint:** JUnit 1,044 (7 skipped), GameTests 101 (the flaky monster house test, H5 note 11, failed once
   more and passed on the rerun), Python and Lua 1,518 (46 skipped). Pod: the hosts of kb-dev to kb-dev5 were all full,
   so a new pod, kb-dev6 (cpu3c ×16, 32 GB, $0.48/hr): one run of 16 minutes (about $0.13), stopped after.
10. **For you:**
    - **Play-test** in a new world: PLAYTEST.md "Caves in 3D" (every cave end to end, Rock Tunnel with and without
      Flash). A world made before H7 keeps its flat caves.
    - **DESIGN.md:39-41 is out of date:** caves no longer sit under their mountains but in an area of their own; rooms
      of one cave may share a rock wall (HD3, under 16 blocks apart); and 15 cave ladders are climbed, not faded. It's
      your file to update.

## Wild Routes

Execution of [specs/WILD_ROUTES_PLAN.md](specs/WILD_ROUTES_PLAN.md) (WR1–WR8), from your choices R1–R9 (2026-10-10)
and answers Q1–Q8 (2026-10-11): one free-roam forest zone off Viridian City first. Pods run without asking inside your
RunPod budget and stop after each job; each phase runs its own tests, the full suite runs after WR2, WR4, WR6 and WR8.

| # | Phase | Status | Date | Commit |
|---|---|---|---|---|
| WR1 | The generators | **done** (19 JUnit tests, 10,000-seed checks on both generators) | 2026-10-11 | `2627e41` |
| WR2 | The exit and the route map | **done**; checkpoint: pytest and JUnit green, GameTests wait for the pod (note 9) | 2026-10-11 | |
| WR3 | Building the zone | done; GameTest and C2 run on the server | 2026-10-11 | 0a7ed03, dd5843f |
| WR4 | Moving through the zone | done; warp GameTest passes; check C5 needs a client | 2026-10-11 | 571a68b |
| WR5 | Trainers and items | done here; the end-to-end session needs your PC | 2026-10-11 | 6d63160, 198600d, 548c2e8 |
| WR6 | Wild Pokémon | done; checkpoint passed (JUnit 913, GameTests 94, Python and Lua 1,479) | 2026-10-11 | e7252f2 |
| WR7 | Dungeons | done here; the end-to-end session needs your PC; quick fixes from your updated PMD report (notes below) | 2026-10-11 | b49a42c, ef4343e, c274bfa, 6b58156 |
| WR8 | Content and play-test | placeholder content in; spot set; checks C5 (a recorded client run) and C6 (real engine) passed on the pod; your play-test and the real content to come | 2026-10-11 | 23e3bbb, 23b6d81, 6523d4e, d8dbf93, 3fa8f5c, e6da2f0 |

### Wild Routes: WR1 notes

1. **Package `dev.kantobridge.routes.gen`**, pure Java with no Minecraft classes: `RouteRng` (xoshiro256** seeded by
   SplitMix64, written out so goldens never drift), `RouteSeeds` (world seed + zone id; visits), our own block-shape
   order and exit weights (DWM's tables from your notes stay in the prototype), `Theme` with `ForestTheme`,
   `AreaGenerator`, `RouteGenerator`/`RouteSpec`/`RouteLayout`, `PmdFloorGenerator`/`PmdFloor`/`PmdParams`,
   `DungeonRules`.
2. **Numbers** (same 300 seeds as the prototype): 169–409 cells end to end (median 268), 2–17 dead ends, 1–10 balls,
   3–14 trainers, at most one retry; 2.4 ms per route and 0.18 ms per floor in Java. 10,000 layouts and 10,000 floors
   pass every invariant (openings meet in exactly 2 cells at every link, nothing overlaps, everything reachable with
   balls and trainers in the way, trainers' sight clear and 8 cells apart, gates touching only their own area).
3. **Fixed on the way:** a far gate could touch another area or the trailhead (a 2-cell shortcut in one route); an
   item could block a lane next to another.
4. **Decisions:** the first area always heads east, so the trailhead's A lies west of x1 as the unturned route map
   `[A][x1]` needs (a connecting route's B lies east of x2); 1–2 balls per area (+1 30% of the time with 3+ dead
   ends) instead of DWM's tier table; water and moats in floors are impassable; no traps (Gen 1 has none); D5 read
   literally: one more floor at each end per **full** 10 levels above 20 (3–5 up to level 29, 4–6 for 30–39, up to
   7–9 from 60).
5. **Where it ran:** the pod had no host capacity (six tries), so the tests ran in a throwaway JDK 21 container on the
   server with JUnit 5.11. The Gradle run is part of the WR2 checkpoint.

### Wild Routes: WR8 notes (so far)

1. **The spot** (`23e3bbb`), at your request "the bottom right of Viridian": a driver, `kb_wildspot.lua`, ran
   gen1-bridge's own exit planner over the town on the real engine and listed the spots the doors reach on foot,
   nearest the corner first. `routes.json` now has `VIRIDIAN_CITY` (35, 29), facing right, 2 blocks deep: a path east
   to the map's edge. The zone is on from now on; a world made before today has none.
2. **Check C6** (`23b6d81`, `tools/e2e/test_wild_routes.py`, on the pod's test install): `routes.installed` with five
   classes; the clearing's last cell warps onto the route map's x1; its lower row is closed; a ball's item through
   give_item once (`taken` the second time), a dungeon's item every time; a zone trainer's battle comes as `scripted`
   (a script-started trainer, as PROTOCOL.md says) with `KB_OPP_YOUNGSTER`, won and counted; stepping onto A goes
   home facing the town. Not checked: saving and loading inside the route map.
3. **Bug found and fixed** (`6523d4e`): a world built with a zone sent no shapes. `WorldPlan.withZones` kept the shape
   map bound to the index without the zones, the server refused it, and every client drew plain cubes. The first
   real-game world showed it; the shape map now names the zoned index (ZonePlanTest checks it; GameTests 97 pass).
4. **Check C5** (`3fa8f5c`, `e6da2f0`): a dev-only client autopilot (`-Pkb.autopilot=wild_zone`) and
   `tools/e2e/showcase.py` recorded the real game on the pod under Xvfb with software GL: Create Kanto world (export,
   pack, build: about 4.5 minutes), the player placed on the spot, the walk into the clearing, the fade and the
   trailhead, then a 120-210 block walk along the lanes and a camera turn. 16-21 fps. Four takes; the last (40 s) is
   the one to watch. The videos stay on the RunPod volume (`/workspace/videos/kanto-bridge-wild-zone-*.mp4`).
5. **The pod's network disk:** gen1-bridge's first export took a quarter of an hour there, because the engine checks
   its import cache (re-reading every decoded module) before each record a dataset view hands out. The test driver
   lets a passed check stand 30 s (`KB_DATASET_MEMO`): 4 s. Only the test driver does this; gen1-bridge is unchanged.
6. **Placeholder content** (`d8dbf93`): six lines of our own per trainer class plus six defaults; the name and item
   tiers stay. Special areas: none yet.
7. **Pods:** kb-dev's host stayed full, so `kb-dev2` (cpu5c x16, $0.56/hr, same volume) was made with your OK; about
   75 minutes in all, stopped. Since 2026-10-11 you allow creating and starting pods as needed.
8. **Check C3b, wild Pokémon really spawn** (asked after the video showed none): `ZoneBuildGameTests` now stands a
   player on the test zone's x1 while Cobblemon's own spawner runs as in a game, until 3 Pokémon spawned (limit 3
   minutes). Every one must come from the zone's pool, at its levels, on the walking level within a habitat block's
   reach. It passes (twice in a row): 3 to 6 a minute, 8 to 32 blocks away, mostly on grass and paths near the tall grass, which the
   hedges hide from a camera on the lanes; the video's half minute in the zone likely just saw none. Two things the
   test needed, neither a bug in the mod: the GameTest server's view distance is 0, so its mock players are never
   ticked and Cobblemon (which only spawns when every chunk within 24 blocks of the spot has its entities loaded) never
   ran; the test keeps 4 chunks around the player loaded, as the recorded run's render distance did. And the test
   level's land is plains, whose own Pokémon (Sentret, Seviper) spawned at the zone's edge; a real Kanto world's land
   is `kanto_outdoor` with no Cobblemon spawns of its own, which the test now gives the zone's surroundings too.

### Wild Routes: your updated PMD report (2026-10-11)

Your updated "PMD Red Dungeon Floor Generation — Technical Report", compared step by step with `PmdFloorGenerator` and
the dungeon code, and measured over 20,000 floors in a scratch copy of the generator. Only your report was used; nothing
from the decompilation itself was read or copied.

1. **Already as the report says:**
   - the grid roll, room sizes (odd, at most 3:2) and anchors;
   - the linking walk, dead-end linking, corridors with one jog on a cell edge, 5% merges and the repair;
   - extra hallways, rivers and lakes;
   - the spawn counts and the way spots are picked;
   - the 10 + 10 retries and the one-room fallback.

   The report's bugs in those parts were already fixed: the dead-end neighbour check, the unset grid, leftover shop and
   Monster House state, structures in deleted rooms, and the left corridor's target. Our reachability check (four
   ways, floor only) gives the same answer as the game's own rule, except at an island's moat (below).
2. **Fixed now** (`6b58156`):
   - **Monster House:** 8–11 Pokémon (your choice) on free cells within 4 of whoever steps in. It was 6 on random
     house cells, which could be the player's own.
   - **No shop rooms** (`PmdParams.standard()` shop chance 0, was 8): nothing filled them, so 8% of floors had an
     empty room, and those floors could have no Monster House.
   - **Nothing on the stairs:** an item landed there on 2% of floors.
   - **Extra hallways stop before row 30**, which the edge wipe clears. A cut tunnel used to force a rebuild on 0.5% of
     floors; now none do.

   Tests: the floor goldens re-pinned, plus nothing on the stairs, plain floors passing their first check, the house
   spots, and the GameTest `aMonsterHouseFillsAroundWhoeverStepsIn`. JUnit 34 in the floor and dungeon packages pass,
   and all 98 GameTests pass.

**To do on your return** (nothing started):

- [ ] **Mart stall**, then shops back on (the shop chance in `PmdParams.standard()`). The report's Kecleon shop
      section has the room's shape, the trimming and the item block; the stairs already never go in a shop room.
- [ ] **A real treasure room:** today the last floor has 4 balls next to the exit. The report's embedded fixed rooms
      show how to set a hand-made room into a random floor: the grid sized so the room fits one cell, the room in a
      room cell, and the linking walk starting from it. A boss room, or a vault opened with an HM later, works the same
      way.
- [ ] **Settings per floor** (the research's dungeon table): every floor uses `PmdParams.standard()` now. For example,
      more Pokémon and items deeper down, and Monster Houses more often near the bottom.
- [ ] **More layouts** (the report's fixed-shape layouts): Small or Medium, which use half or three quarters of the
      width and are quicker to build and walk; Cross; Beetle; Crossroads; Outer Ring; Line.
- [ ] **Room structures** are off (`structures` 0). Before turning them on:
  - Islands are always rejected: our check ignores the moat's exception (the `cut` flags are never read), and 0 of
    6,206 survived. They also lack the report's items and Warp Trap; they could become treasure you reach with Surf.
  - Checkerboards fail the check 99% of the time, as the report says. Putting water only on tiles whose x and y
    offsets are both odd would keep every floor tile reachable.
  - The water maze is missing: a room with both sides odd should get the maze, but ours always gets the plus.
  - The game lets a Monster House take a room picked for a structure and skips that structure; ours never does.
  - The doorway pass should turn water next to a corridor back into floor.
- [ ] **Tunnel turns:** the report says the edge check runs three times; ours runs once. That's harmless now that
      tunnels stop before row 30.
- [ ] **Buried items:** the report's items in the rock, as hidden items, if you want them.

### Wild Routes: WR7 notes

1. **Geometry** (`b49a42c`): `DungeonSlots` (16 slots of 128×80 blocks, 3,000 blocks north of Kanto, floors 22 apart
   from Kanto's walking level); `DungeonFloorPlan` draws a PMD floor 2×2 blocks a cell (stone walls under a stone
   ceiling, coarse dirt floor, water behind barriers, lights, the stairs prop or, on the last floor, the exit prop).
   The first prop block (`kantobridge:prop`: kind × quarter, no collision, built-in models over vanilla textures) marks
   a zone's dungeon entrance. The WorldIndex lists each zone's dungeon (cell, way out, pool, level, allowed items) and
   the dungeon region; a client in the region whose hidden player is on a route map is away, as in a zone.
2. **Visits** (`ef4343e`): `DungeonVisits` (plain Java) and `DungeonService`: a party gathers for 5 s on the entrance
   (faded out at once), takes a free slot, floor 1 is built behind the fade (`DungeonPlacer`, a chunk at a time within
   a tick budget), the next floor as soon as the one before is entered; stairs fade a player down; the last floor's
   exit puts them beside the entrance; anything else that takes a player out of the slot (a blackout's warp, Fly) or
   offline leaves the visit; an empty visit is cleared. New message `route.fade` and the client's `ServerFade`.
3. **Inside** (`c274bfa`): `DungeonContents` (plain Java): Pokémon from the dungeon's habitat pool at PMD's enemy
   spots (levels plus one every second floor), more over time away from players, a Monster House that fills on first
   entry; items at PMD's item spots and a treasure of 4 beside the last floor's exit, by depth from the item tiers, no
   Rare Candy or Master Ball, at most one Nugget a visit. Items are Poké Ball item displays; stepping on one sends
   `route.give` with the new `keep: false` (gen1-bridge gives it without recording it); the display goes on the
   answer. A cleared slot takes its entities; leftovers from before a restart are discarded as they load.
4. **Tests:** `DungeonSlotsTest`, `DungeonFloorPlanTest`, `PropModelsTest`, `DungeonVisitsTest` (5), `DungeonContentsTest`
   (4), `ServerFadeTest`, a Lua test for `keep: false`; GameTests `DungeonGameTests` (in, down two floors and out with
   real blocks built and cleared; two parties in two slots; a floor's Pokémon and items, an item picked up, a blackout
   ending the visit and clearing its Pokémon). JUnit 930, GameTests 97, Python and Lua 1,484.
5. **Not yet:** refusing Fly inside (flying out just ends the visit), and seeing a dungeon in a client (C5); the
   end-to-end session with the real engine needs your PC.

### Wild Routes: WR6 notes

1. **Pools:** one per area with tall grass, `kantobridge:zone_<zone>_a<n>`, from the town's neighbour grass slots
   (species by dex, weights the slots' shares), levels raised by the area's level offset (`ZonePlan.pools`). They join
   Kanto's in the plan, so the world's datapack holds them and the build hands them to Cobblemon at once.
2. **Habitat blocks:** 3 under the feet, covering each area's grass the way Kanto's are planned (`HabitatPlanner.cover`
   over the layout's grid), listed in the WorldIndex's `habitats` with the route map as their map, so `HabitatService`
   checks them at run time like Kanto's.
3. **The spawn rule** `walking_level_only` lists `kantobridge:wild_forest` (floors at Y 63). The zone's biome is in none
   of Cobblemon's spawn tags, so only the pools spawn there.
4. **Blackouts:** a lost natural wild battle (a habitat's Pokémon, in a zone or in Kanto) sends `route.blackout`
   (`WildBlackouts`, on Cobblemon's battle victory event): the hidden game's own loss handling. Not after a catch, and
   not for battles the hidden game started (they answer through `battle.result`).
5. **Check C3, measured** (`ZoneBuildGameTests`, a one-area zone in a real world): 4 habitat blocks, each configured
   with the zone's pool; Cobblemon's own spawner offers the pool's two species at levels 2-5 on 136 grass positions,
   and nothing off the walking level.
6. **Tests:** `ZonePlanTest` (+1: every area's grass inside its own pool's spheres, levels raised per area, a mix of
   offsets), the plan merge, `WildBlackoutsTest` (the rule; the spawn rule lists the biome), C3 above. The event wiring
   of a real lost battle is left to the end-to-end session.

### Wild Routes: WR5 notes

1. **One list for client and server** (`6d63160`): the WorldIndex's zones list their trainers (cell, facing, sight,
   class, sprite, party) and balls (cell, item), in the route map's grid (A is 0,0). The builder makes them from the
   export (`ZoneContent`: the town's neighbour grass slots, each class's most used sprite, the ball sprite, the game's
   own items; `ZoneObjects`: parties of 1-2 then 2-3, 0-3 levels above the area's top wild level; items by tier from
   `data/kantobridge/wild_routes/items.json`). Trainers stand on a barrier; balls on a story cell of the new type
   `route`. The balls' story cells join the plan's table, so Kanto's story codes are re-pointed (each cell keeps its
   entry; tested).
2. **The client** (`198600d`): `RouteProgress` from the snapshot and `route.progress`; `StoryRules` closes a ball's cell
   until it's taken; zone puppets as a second list in `PuppetManager` (the hidden game's lists leave them alone; on a
   route map the culling area is the zone's box); `ZoneRuntime` forwards `route.*` between server and gen1-bridge
   (answers carry the server's own seq as `re`) and sends `route.give` for a ball in front of the player or a clicked
   ball puppet.
3. **Trainers** (`548c2e8`): new message `route.sight` (S→C). The server (`ZoneService`, `ZoneEncounters`) checks Gen 1
   sight every tick for free players in a zone; the client shows "!", holds the player and walks the puppet up
   (`WalkUps`); then `route.battle` with the party and a line (`data/kantobridge/wild_routes/lines.json`, our own text,
   at most 18 characters until check C6 shows how the engine wraps literal text). A win keeps the trainer quiet until
   the player leaves the zone's box (the visit); anything else lets it look again after 5 s; one encounter at a time.
4. **Tests:** `ZoneObjectsTest`, `ZonePlanTest` (+3), `RouteProgressTest`, `StoryRulesTest` (+1), `PuppetManagerTest`
   (+1), `ZoneViewTest`, `WalkUpsTest`, `ZoneEncountersTest` (5), `ZoneLinesTest`; GameTests `ZoneBuildGameTests` (the
   blockers and story cells in a real world) and `ZoneEncounterGameTests` (sight, battle, the relayed win, the
   rematch). JUnit 910, GameTests 94, Python and Lua 1,479.
5. **Still needs you:** the end-to-end session on your PC (a ball taken and a trainer fought through the real engine),
   with check C6 (the clearing, the route map, `give_item` and `start_battle` from a queued script) and C5 (seeing a
   zone). The clearing's spot (F7) comes first.

### Wild Routes: WR4 notes

1. **MovementSync** (client): out in a zone (inside its box, off every map's cells, while the hidden player is on
   that zone's route map) nothing is sent: no steps, turns or drift fixes, and joining, reconnecting or settling after
   a warp doesn't pull you back. Walking back onto x1 needs nothing; A is an ordinary step whose script takes the
   hidden player home. In a zone's box with the hidden player elsewhere (after Fly or a blackout), you're pulled back
   as anywhere else. Counter `zoneTicks`. JUnit: three new cases in `MovementSyncTest`.
2. **The route map's lower row is closed** in the hidden game: main.lua's `movement.collision` wrapper refuses a move
   onto a route map's row 1 as `tile` (`kb_routes.closedMove`, status `routesClosed`). The verdict changes nowhere
   else. Lua test: a step down from x1 is blocked as `tile`; the same step in town walks.
3. **The maze is closed** (generator): each gate is ringed with hedges where it meets the land, and the cell under the
   stand is a hedge, so A and x1 are the route map's only open cells in Minecraft too. Before this, the trailhead's
   plaza opened onto the scenery, and you could have walked off the maze there. `RouteGeneratorTest` gains the rule
   "no walkable cell beside the scenery or on the edge" for every seed (the areas already kept it; only the gates
   leaked), and its golden fingerprints changed. `ZonePlanTest` checks the same in blocks: from every walkable cell,
   each closed neighbour is solid at feet height.
4. **Warps** need no change: `WarpService` finds any placed map, the route map included. Kanto's rules (Adventure, no
   jumping, noon, Peaceful) are server-wide, so they hold in zones. `ZoneBuildGameTests` now also warps a mock player
   onto x1 and back (it runs at the next pod session).
5. **D8 extended:** rocks stay vanilla boulders, and balls are puppets (WR5), so the prop block comes with the first
   thing that needs a model of its own, the dungeon entrance (WR7). Check C5 (a built zone and the fade at the
   trailhead, seen in a client) needs your PC or the pod under Xvfb.
6. **GameTests run on the server now**, no pod needed: `./gradlew runGameTest` in a throwaway container capped at
   2.7 GB (Gradle `-Xmx700m`, the game server at 46% of the cap), about 2 minutes. All 93 pass, including
   `ZoneBuildGameTests` (the zone built block by block, then warps onto x1 and back). Its first run caught a wrong
   check in the test itself: a dirt path is 15/16 of a block, so it isn't a full cube, but you stand on it (cells
   still match: `WorldIndex.toCell` reaches 4 blocks below the feet level).
7. **C2, second half, measured** (the GameTest, a one-area zone beside the synthetic Kanto): planned in 1.9 s; 631,065
   blocks in 140 chunks placed in 152 ticks, 9.3 s of placing (about 68,000 blocks a second), longest tick 588 ms
   (fresh chunks). A full zone (about 2.7 million blocks) is then about 40 s of placing. The long ticks don't matter
   while a build holds players; a live rebuild would want smaller slices.

### Wild Routes: WR3 notes

1. **The WorldIndex** gains `zones` (each zone's box, floor level, theme, biome and route map) and a placement kind
   `route` for route maps, placed at the trailhead so the route map's cells A and x1 are the trailhead's
   (`WorldIndex.toCell` works on them unchanged). Written only when a world has zones, so every existing index and
   golden keeps its bytes. A rebuild clears an older build's zones too.
2. **The builder** reads the export's `routes` (`ExportRoutes`), plans the zones after Kanto (`BuildTask.withZones`,
   `ZonePlanner`) and adds them to the plan (`WorldPlan.withZones`): their volumes, blocks (`renderChunk`), biome and
   index entries. A zone whose town isn't in the build is left out (warning `zone_town_unbuilt`). The report counts
   zones, their trees and walkable cells, and the planning time.
3. **Slots** (`ZoneRegions`): 640×640 blocks, starting 3,000 blocks south of Kanto's box at its west edge, 8 to a row,
   in zone-id order. North of Kanto is for dungeon slots (WR7); west and east are kept for other games' worlds.
4. **Blocks** (`ZonePlan`, `BlockCode.KIND_VANILLA`, `ZoneBlock`): walkable cells flat at Y 63 (feet at Kanto's 64);
   hedges of persistent leaves 3–5 high, now and then with a trunk; boulders 2 high; ponds of water at the floor with a
   barrier above; the land from vanilla noise, with a ridge along the box's edge rising to Y 94 so the void never
   shows; our own trees of vanilla blocks on the land only. The biome `kantobridge:wild_forest` is new.
5. **Decision D8** (plan §9): the forest's walls and trees are vanilla blocks rather than voxel props: the Minecraft
   look, solid collision, no client work. Props stay for rocks, balls, signs and the dungeon entrance.
6. **C2, first half, measured here** (JUnit with Minecraft's built-in registries, `VanillaTerrainTest`): vanilla's
   noise costs 1.1–2.9 ms per column on this server, so a 4-block grid made a zone take 30–75 s. With a 16-block grid,
   sampled in parallel and blended smoothly with a little relief of our own, a zone plans in about 1.4 s. A zone is about
   2.7 million blocks (the ground 10 blocks deep), so placing it takes about a minute at today's 49,000 blocks a second;
   the plan's 5–10 s estimate was low. The second half (placing in a real world, the cost per tick) and a GameTest of a
   built zone wait for a pod.
7. **Tests:** `ZonePlanTest` (12: floors and room above every walkable cell, hedges at least 3 high, barriers over
   ponds, the route map on the trailhead with A west of x1, the ridge, trees only on land, determinism, slots, the
   zone in the plan and its index round trip, a zone without its town), `ExportRoutesTest`, `VanillaTerrainTest`; the
   world, builder, server and movement JUnit packages (387 tests) and the Python schema tests still pass.

### Wild Routes: WR2 notes

1. **The exit needs no warp tile.** As the revised plan says: a step script on the town's map (map scripts compose, the
   first truthy `onStep` wins, mods before the engine's own) queues the engine's own `warp` to the route map's x1; the
   route map's A warps back to the clearing's second-to-last cell, facing the town. The bridge follows both as warp
   kind `script`. `mod.world:queueScript` refuses while a script runs, so the step isn't consumed then and the player
   can step again.
2. **The route map** is one block (2×2 cells) of the block the chosen spot stands in, on the town's tileset (so the
   engine treats it as outdoors) with the town's border block. Nothing else about it comes from a constant.
3. **Registration order** keeps a failure harmless: the route map, the step scripts, the trainer classes and the song,
   then the town's `blocks` patch last. A schema violation throws from the registry call for an api 2 mod
   (`src/mods/Loader.lua:1202-1207`), so every call is guarded; a later failure tombstones the route map (`remove`)
   and leaves the scripts inert. References only name ids that exist (the town's tileset, vanilla trainer fields; the
   song only when `music:get` finds it), since a dangling one fails the whole mod after the merge.
4. **Trainer classes:** `KB_<CLASS>` records copy the vanilla class (name, picture through `basePic`, money, AI) with
   one party, the class's own first, rewritten before each zone battle (`engine.writeTrainerParty`). A class with no
   usable party isn't registered and that zone just can't use it (`unknown_class`).
5. **The battle's outcome** is read from the script's own context (`runner.ctx.lastBattleResult`, set when
   `start_battle` resumes) while the script runs; the queued script's `onDone` (the real runner calls `ctx.onDone`)
   ends the request. A loss blacks out through the engine's own path before the answer goes out.
6. **The export** always carries `routes/all` (empty without zones) and the towns' clearings in their `blocks`
   (the dataset view is the import, so the export applies the patch itself; on the live-copy path the patch is already
   there and applying it again changes nothing). `fabric-bridge`'s bundle reader accepts the new kind; nothing on the
   Java side reads it before WR3. The synthetic bundle (`fake_gen1`) has an empty list; its copy for GameTests was
   regenerated and the goldens' `exportHash` updated (only that hash changed).
7. **Lua tests** run on the server in a throwaway capped container (no venv here).
   `tools/lua/test_lua_routes.py` (44 tests) on `tools/lua/routesim.py`, with new fakes `fake_registry.lua` (the
   registries' fold and the compose rule) and `fake_worldapi.lua`, `give_item`/`warp` in `fake_script.lua`, and
   `kblua.Bridge(files=, registries=, world_api=)`.
8. **Viridian City's zone is listed but unset** (`x`, `y`, `facing` null), so nothing is registered and no world you
   build shows a clearing yet. It waits for your F7 reading.
9. **The checkpoint.** `kb-dev` couldn't start (its host had no free memory; eight tries on 2026-10-11), and
   creating a replacement pod needs your permission in Claude Code. So the checkpoint ran on the server: the whole
   pytest suite (1,474 passed, 45 e2e skipped), and fabric-bridge's JUnit tests through Gradle in a capped JDK 21
   container (867 tests, 6 skipped that need the Python stand-ins; about 4.5 minutes, Gradle's cache in the
   session's scratch folder). The GameTests run a Minecraft server, which doesn't fit beside Gradle in the server's
   memory; they run at the next pod session. Two JUnit expectations followed the new
   resource: the synthetic bundle has 21 resources, and the shapes golden's `mapHash` changed (nothing else in it).
10. **The route list's own schema** is `protocol/world/route_list.schema.json`, checked against the shipped list and
    the Lua tests' list (`tools/tests/test_route_list.py`).

## Engine upgrade (ENGINE_UPGRADE_PLAN, 2026-10-13)

Execution of [specs/ENGINE_UPGRADE_PLAN.md](specs/ENGINE_UPGRADE_PLAN.md) (UP0–UP6, gates G0–G6): the Gen1Recomp pin
moves from v0.3.49 to **v0.3.85** (released 2026-10-12, the latest release when pinned on 2026-10-13; built from `dev`
@ `e50c396`, 2026-10-12; official source only). You answered its decisions on 2026-10-13 with "go with the defaults":
U1 v0.3.85; U2 you re-import (done through the upload page on the pod: Blue, Crystal and Emerald imported for v0.3.85
into their own data home, `local/gen1recomp-data-0.3.85`); U3 the Union Room OFF in every install; U4 fresh folders;
U5 the v0.3.49 folders kept until the last gate; U6 the JUnit literals updated. Feature tests while building; the full
suites at the gates. The gates wait for the pod and your PC: their results are filled in after the runs.

| # | Phase | Status | Date | Commit |
|---|---|---|---|---|
| UP0 | Your pre-flight | the pod's imports done (Blue, Crystal, Emerald, through the upload page); the Windows test install not reported yet | 2026-10-13 | |
| UP1 | Tooling: `pin_check.py --restamp`, `test_pins.py`, the boot check, the Union Room pin, guard and driver | **done** (notes 1–3, 6) | 2026-10-13 | |
| UP2 | The engine seams in gen1-bridge (B3–B6, the re-stamped citations) | **done** (note 4; the press signal, note 9) | 2026-10-13 | |
| UP3 | The fakes and unit tests (lupa; the literals, U6) | **done** (note 5) | 2026-10-13 | |
| UP4 | The pod: the Linux build, gates G1–G4 | **done** (notes 7, 9) | 2026-10-13 | |
| UP5 | Your PC: the Windows build, gate G5 and the playtest | pending | | |
| UP6 | Pins, docs and records | **done** (note 8) | 2026-10-13 | |

**The audit** (`tools/pin_check.py refs/gen1recomp v0.3.49 v0.3.85`): 696 citations in 810 scanned files: 172 same,
474 shifted, 49 changed (on 14 engine files, each with a disposition in the plan's Appendix B), 0 missing, 1 unresolved
(a filter example in a test, not a citation). Upstream: 36 releases and 252 commits between the tags; the mod API is
still 2 and the save format still 5.

| Gate | Where | Passes when | Result |
|---|---|---|---|
| G0 static | this server (JUnit on the pod) | `pin_check.py`: 0 missing files (v0.3.49 → v0.3.85, then v0.3.85 → v0.3.85 on the re-stamped code); every changed row of Appendix B applied; lupa and JUnit counts at least the last checkpoint's (H7: JUnit 1,044, Python and Lua 1,518; the plan's floors 925 Lua / 913 JUnit are WR6's) | **passed 2026-10-13**: `pin_check` v0.3.49 → v0.3.85 893 citations (216 same, 586 shifted, 80 changed, 0 missing; 506 re-stamped by the tool, the rest by hand), then v0.3.85 → v0.3.85 895 same / 0 shifted / 0 changed; pytest tests+lua 1,565 passed, 8 skipped (here and on the pod); JUnit on the pod 1,050 tests, 0 failures, 1 skipped |
| G1 smoke | pod | `test_s5_ready.py` (`KB_READY engine=0.3.85 status_engine=0.3.85 modules=33 mod_errors=0 missing=0 union_room=off`), `test_handshake.py` (`hello.ok` engine and `tested` both 0.3.85, no `engine.untested`), `test_hot_reload.py`; the crash log untouched | **passed 2026-10-13** on kb-import (cpu3c x16, xvfb): 4 passed; `KB_READY state=loaded engine=0.3.85 status_engine=0.3.85 modules=33 mod_errors=0 missing=0 union_room=off` (the module count is derived from `main.lua` now: 33 since Wild Routes, not M10's 30); the first boot after the import ran patched and wrote the Union Room off (`KB_UNION_OFF ok=true was=off patched_this_boot=true`), the next one was clean; the boot check first flagged `Game.logicStep`, a counter the first logic step creates, dropped from `Engine.REQUIRED` |
| G2 export | pod | `test_export.py`: the same counts as today (222 maps, 36 surface, 36 layout, 186 interiors, 242 LAST_MAP warps, 802 warps, 24 tilesets, 151 species, 165 moves), every schema strict, hashes match Python's, the swap audit unchanged; the index and per-resource hashes compared with the v0.3.49 report in `local/reports/` (differences listed by resource name only) | **passed 2026-10-13** on kb-import: the export's index from the v0.3.85 engine (blue, the pod's re-import) against the index from the v0.3.49 engine with its own data home and the pre-upgrade mod (the plan commit, a worktree): 360 resources each, **every hash identical** (222 maps, 24 tilesets, 73 sprites, 34 images, 1 each of layout, palettes, encounters, swaps, routes, names, field): no world rebuild (G6 not needed). The pod builds the export at a few steps a second under Xvfb (463 s new, 452 s old) so `test_export.py`'s default 240 s index wait is too short there on both releases; its assertions (schemas, swap audit, counts) run in G4 with `KB_EXPORT_INDEX_TIMEOUT`/`KB_EXPORT_TEST_TIMEOUT` raised |
| G3 seams | pod | `test_walk.py` (warp kinds, B7), `test_ui.py` and `test_opening.py` (pages, the arrow, naming, B3), `test_battle.py` both outcomes (B5), `test_route1.py` (bag totals, B4), `test_viridian.py`, `test_content.py`, `test_endgame.py` (no map song restored on the champion win), `test_wild_routes.py`; the existing assertions plus the new music and union ones | **passed 2026-10-13** on kb-import, in two runs (note 9): the first failed 20 of 23 on one cause, the client's press signal (v0.3.85 draws no arrow on a `{DONE}` text while still taking the press; `ui.update.waiting` now means "a press does something", as the client and the tests read it); the second passed 17 and timed out 6 (walk, ui, opening, battle ×2, route1) fetching the export, which the pod builds in 7–8 minutes on both releases; those 6 passed in G4 with the waits stretched |
| G4 full | pod | `kb-pod check --e2e` and `gradlew build` with GameTests: pytest, JUnit and GameTests at least the last checkpoint's (H7: 1,518 / 1,044 / 101) plus the new tests; `test_perf.py` within its budgets (gen1-bridge under 1 ms a step, the mirror at most 30 fps) | **passed 2026-10-13** on kb-import (16 vCPU, Xvfb): three full `tools/check.sh --e2e` runs, each fixing what the one before found (note 9): 1,596 passed / 6 failed (the drivers' own time limits during the export, the TIME_WAIT bind), 1,603 / 2 (the option that never took on a `tcp()` socket, the `routes` kind), 1,605 / 2 (the `net.listening` suffix, the `routes` fractions), then a targeted run of the unit and Lua suites with `test_export.py` and `test_multiplayer.py`: 1,573 passed, 0 failed (every e2e test has passed in one of the last two runs); JUnit 1,050 passed, 0 failed, 1 skipped (rerun after the Java change); GameTests 101 passed (16:00 UTC, before the routes change, which touches no GameTest fixture); `test_perf.py` passed within its budgets on the pod. Floors (H7: 1,518 / 1,044 / 101) all cleared |
| G5 Windows | your PC | `tools/check.sh --e2e` on the test install (`test_hidden.py` included); the playtest items of UP5 step 3 ticked in PLAYTEST.md | pending |
| G6 world | your PC | only if G2 found a changed `maps`, `tilesets`, `palettes` or `sprites` hash: `tools/e2e/build_world.py --maps all` within its gates (220 maps placed, 0 warnings, the self-check floors) and the client joins the rebuilt world; otherwise the existing worlds stay valid | pending |

### Engine upgrade: notes

1. **The boot check** (UP1 item 3; ROADMAP Stage 4 item 1): `Engine.REQUIRED` in `kb_engine.lua` lists the engine
   modules `try()` loads and the functions and fields each must have; `engine.checkRequired()` walks it once at
   `game.ready`, logs one `engine.missing` warning with the count, and `debug.status.engineMissing` lists what is
   absent; `KB_READY` prints `missing=<n>` and `test_s5_ready.py` asserts `missing=0`.
2. **The Union Room pin and guard** (U3; UP1 item 4): `Engine.CLASSIC` gains `unionRoom = false`, written by the
   engine's own options write like the display pins; `engine.unionRoomPatched()` (the patch's `POKECENTER_2F` map
   exists) is checked at `game.ready`: a boot that ran patched logs `engine.union_room_on` once,
   `debug.status.unionRoom` says `"on"`, `export.index` is refused with `error{code:"union_room_on"}`, and `hello.ok`
   carries `union_room`, so the client shows one notice ("Launcher Options → Union Room → OFF, then restart"). The
   headless pod can't click the launcher: the driver `tools/e2e/drivers/kb_union_off.lua` sets the option off through
   `SaveData.loadOptions`/`saveOptions` and quits (`KB_UNION_OFF ok= was= patched_this_boot=`); `KB_READY` ends with
   `missing=<n> union_room=<on|off>` and `test_s5_ready.py` asserts `union_room=off`. The rule joins CLAUDE.md's hard
   rules; `mod.card`'s `known` line names it; INSTALL.md has it as step 6.
3. **The pins** (UP1 items 1–2): `tools/pin_check.py` gained `--restamp` (rewrites the line numbers of same and shifted
   citations in place) and `--dry-run`; `tools/tests/test_pins.py` checks that every copy of the pin agrees
   (`Engine.CHECKED`, `manifest.json`'s `game_version`, `pins.json`, VERSIONS.md, INSTALL.md, CLAUDE.md, the e2e and
   lupa literals). ROADMAP Stage 4 items 1 and 2 are done with these.
4. **The seams** (UP2): `Engine.CHECKED = "0.3.85"` (`TESTED` the same; the header names `e50c396`), `manifest.json`
   `game_version` `"0.0.0-dev || >=0.3.85 <0.4.0"`; the shifted citations re-stamped by the tool and the changed ones
   re-read per Appendix B; B3 the text box rules (`shownSource`, the arrow's precedence, `stay.press`), B4
   `Bag.remove` with the engine's data as its fourth argument, B5 the victory jingle's kinds and no map song after a
   won final battle, B6 the indoor-encounter rule (`rollsIndoorEncounters`, the `outdoor` map field).
5. **The fakes and literals** (UP3): the lupa fakes model the new rules (`fake_ui`, `fake_wild`, `fake_content`,
   `fake_center`, `fake_engine` at 0.3.85 with shell 3, `fake_mod`/`sandbox` re-checked against the new tag's loader),
   and the `0.3.49` literals in the lupa, e2e and JUnit tests say `0.3.85` (U6).
6. **The e2e runner:** `PINNED = "0.3.85"`; it prefers the versioned folders when they exist,
   `local/gen1recomp-e2e-0.3.85` (Windows), `local/gen1recomp-linux-0.3.85` (the extracted AppImage) and
   `local/gen1recomp-data-0.3.85` (its data home), over the unversioned names, with the overrides
   `KB_GEN1RECOMP_INSTALL`, `KB_GEN1RECOMP_APP` and `KB_GEN1RECOMP_HOME`; so UP0's rename of the Windows test install
   is optional. A silent ready timeout carries the hint that the import may be stale (B1: a stale cache stops at the
   launcher's re-import prompt).
7. **The pod** (UP4): both builds side by side, no renames: the v0.3.85 AppImage extracted to
   `local/gen1recomp-linux-0.3.85` with the data home `local/gen1recomp-data-0.3.85` (your imports: Blue, Crystal and
   Emerald, through the pod's upload page), the v0.3.49 `local/gen1recomp-linux` and `local/gen1recomp-data`
   untouched (U5). Every ROM import is redone for v0.3.85 (the cache generation changed for every game: B1); zipped or
   7z ROMs import as they are; Ruby and Sapphire became importable.
8. **Records** (UP6, 2026-10-13): VERSIONS.md (the v0.3.85 row with the zip and AppImage hashes from the release's
   `sha256sums.txt`; the v0.3.49 row kept as history), INSTALL.md (v0.3.85, the Union Room step, archive import, the
   re-import), INSTALLER.md and `pins.json`, CREDITS.md, CLAUDE.md (the pin, the Union Room hard rule, the plan's
   status), REFERENCES.md (`e50c396`; its Gen1Recomp line numbers stay at `cb02aeb` with a note pointing at
   `pin_check.py`), ROADMAP.md (the upgrade watch; Stage 4 items 1–2 done), PLAN.md, MULTI_GAME_PLAN.md (§0 and D11
   bumped; A.2: returning to the launcher restarts the process on desktop, B9; §4.11 step 5: archives import
   directly), `tools/e2e/README.md` (the driver, `KB_READY`'s fields, the versioned folders).
9. **What the pod's gates found** (UP4, 2026-10-13), each fixed at its seam: (a) the press signal: v0.3.85's
   `TextBox` hides the arrow on a `{DONE}` ending (`arrowVisible`) while a press still ends the page, and the
   Minecraft client (`TextBoxModel`) and the e2e tests press only while `ui.update.waiting` is true, so following
   the engine's drawn arrow froze every scripted text; `kb_engine`'s `textArrow` now reports whether a press does
   something now (`waiting`, `done` with no choice, a `stay.press` box), the box's `arrowVisible` stays the engine's
   (`test_lua_ui.py`). (b) The pod builds the export at a few steps a second (CPU-bound Lua, LuaJIT on, the volume
   fast; 7–8 minutes on both releases): `KB_EXPORT_INDEX_TIMEOUT`/`KB_EXPORT_TEST_TIMEOUT` raise the tests' waits,
   every export-dependent wait stretches by the same `EXPORT_EXTRA`, and the runner passes it to the drivers as
   `KB_DRIVER_EXTRA` (their own `KB_DRIVER_SECONDS` defaults, 300–420 s, had quit the game mid-export:
   `KB_FAIL timeout phase=serve`). (c) `test_crash.py`'s second launch bound its port `addr_in_use` and fell back to
   the next one: Linux keeps a killed instance's server-closed sockets in TIME_WAIT for a minute (seen with a
   `/proc/net/tcp` sampler: three on the port, 60 s), and the bridge bound without `SO_REUSEADDR`, a rule made for
   Windows, where the option lets a second listener share a port. `kb_net` now takes `opts.reuseaddr`, `main.lua`
   sets it on every platform but Windows (`jit.os`, which the engine's sandbox hands over), the listener is created
   with `socket.tcp4()`: LuaSocket's `socket.tcp()` has no descriptor until its bind picks the family, so an
   option set on it fails silently (`setsockopt failed`, seen on the pod's LÖVE), which is why the first attempt
   changed nothing; a refused option logs `net.option_failed` once and `net.listening` logs `reuseaddr=true`.
   Verified with Python on the pod: the bind over TIME_WAIT needs the flag on both the dead instance's sockets and
   the new one, so the first relaunch after this change waits the minute once. The fake socket mirrors the
   descriptor rule and TIME_WAIT (`net:linger`, `optionFails`), `Bridge(jit_os=)` pins the platform the tests see
   ("Windows" by default, whatever the host). (d) `test_export.py`'s kind list lacked `routes`, exported since Wild
   Routes, and once checked, that resource was the one whose Python canonical form differed from the index's hash:
   it carried the side-area and dungeon chances as fractions (0.5, 0.6), which the protocol's canonical JSON never
   does (protocol/README.md: "no fractional numbers, so the hash is the same in Python, Java and Lua"; Lua spells
   0.6 as 0.59999999999999998, Python as 0.6, Java kept the text it was given). The two chances are whole percent
   now (50, 60) in `data/routes.json`, the `routes` schema, `kb_routes`' check and export, and the Java reader
   (`ExportRoutes`, which still hands the generator its 0..1); the `routes` resource's hash changes, which calls
   for no world rebuild (G6 lists maps, tilesets, palettes and sprites). The test now names a mismatching resource.
   (e) `Game.logicStep` is created lazily (`Game.lua:317`), so the boot check no longer requires it, and
   `test_s5_ready.py` derives the module count from `main.lua`. (f) `test_multiplayer.py`'s port-fallback test
   matched the end of the `net.listening` line, which now ends with `reuseaddr=true` off Windows.

## Several games (MULTI_GAME_PLAN, 2026-10-13)

Execution of [specs/MULTI_GAME_PLAN.md](specs/MULTI_GAME_PLAN.md) (MG0–MG8) from your decisions D1–D14 of 2026-10-13
(plan §9): one bridge mod with an engine adapter per family, one hidden process per game, regions as data in the one
flat overworld, connecting routes with gate houses, per-game story progress and one trainer economy. Protocol and schema
changes come first inside each milestone (PLAN §7), the protocol number stays 1 (D10), feature tests while building and
the full suite at the ✔ checkpoints (MG2, MG3, MG5, MG6). Pods run inside your RunPod budget and stop after each job.

| # | Phase | Status | Date | Commit |
|---|---|---|---|---|
| MG0 | Decisions and spikes | decisions answered (plan §9); **S2, S3, S4 and S5 done on pods; S1 as far as a pod can** ("Several games: spikes", 2026-10-14); left: S1 beside Minecraft on your PC | 2026-10-14 | |
| MG1 | The game catalogue and an open protocol | **done** (notes 1–3); check: pytest and Lua 1,615 passed, JUnit 1,059 (0 failures), GameTests 101 on the pod (kb-dev7); the golden synthetic index byte-identical | 2026-10-13 | |
| MG2 | Core and adapters in the bridge mod; the region acts on Gen 1 | **done** (notes 4–7); checkpoint passed on the pod (kb-dev7): pytest and Lua 1,658, JUnit 1,059 (0 failures), GameTests 101; e2e on the real engine (Blue, v0.3.85): `test_regions.py` 2 passed (a `start` from the title ×3 NEW GAME and ×3 CONTINUE, the park/resume round trip), `test_s5_ready.py`, `test_handshake.py` | 2026-10-13 | |
| MG3 | Regions as data | **done** (notes 8, 10–13); checkpoint passed on the pod (kb-dev7): pytest and Lua 1,703, JUnit 1,122 (0 failures), GameTests 103 (`RegionBuildGameTests`: two regions in one world, the legacy build byte-identical to the kanto region, the join sequence, per-region warps, presence and progress; a legacy world keeps today's wire); pod check passed 2026-10-13 22:54 UTC (you accepted the EULA): the real Kanto built as region `kanto` at slot 0 (`regions/kanto/`, 220 maps, 4 min 36 s) and as a legacy world from the same export agree on every map placement, the dungeons, the shafts, the export hash and the **slot-map slice hash** (`42fd3f4d`, the region's `slotMapHash` = the legacy map's hash), the shape index byte-identical; only the Wild Routes zone differs, laid out from each fresh world's own seed (5 areas vs 4); D9 as an estimate: 32 families are 24,576 tile/shape block states (+12,288), about +15 MB a side at the measured 1.23 KB a state | 2026-10-13 | |
| MG4 | Links | **done** (note 19); check: with one link the e2e scenarios behave as before (pod, real engine: `test_s5_ready`, `test_handshake`, `test_regions`, `test_multiplayer`, `test_crash`, `test_hot_reload` all passed), `tools/tests/test_links.py` (two `fake_gen1`: swap, a parked drop, a wrong game), JUnit `LinksTest` + `LinksFakeGen1IntegrationTest` (two real `fake_gen1` processes); one `test_multiplayer` assertion learnt that an install paired before MG1 keeps its legacy pairing file beside the per-game one. The MG4–MG6 checkpoint on the pod (kb-dev7): pytest and Lua 1,729, JUnit 1,193 (0 failures), GameTests 112 | 2026-10-13 | |
| MG5 | Crossings | **done** (note 20); check: `PassGameTests` (6: the round trip into the far gate house with the exact arrival cell, every refusal, abort, the timeout, the start path, the stash, a built half whose pass is a story cell), `PassCrossingsTest`, `ZonePlanTest`, `WorldIndexTest`, `PassChoreographyTest` (16), the two-`fake_gen1` crossing in `tools/tests/test_stand_ins.py`; a crossing between two real engines waits for a second region whose game has an adapter (MG7/MG8) | 2026-10-13 | |
| MG6 | One trainer economy | **done** (notes 18, 21); check: `LedgerGameTests` (two regions: adoption, stamped read-back, the crossing's projection, `too_many`/`left`, re-projection on an unknown stamp; a legacy world untouched), `BagGameTests` marked stacks, `LedgerTest`/`LedgerCodecTest`/`ItemsTest`/`BagSetRelayTest`, `test_lua_ledger.py`/`test_lua_bag.py`; the real engine's `bag.set` round trip is your play-test (PLAYTEST.md "MG6") | 2026-10-13 | |
| MG7 | The second family (Emerald): adapter and export | **done**: the spec [specs/GEN3_BRIDGE_PLAN.md](specs/GEN3_BRIDGE_PLAN.md) (2026-10-14), phases G3A–G3G; **G3A done** (Emerald followable), **G3B done** (Emerald's UI), **G3C done** (story), **G3D done** (export), **G3E done** (builder), **G3F done** (battles, party, bag; the route exit moved to MG8), **G3G done** (the real Hoenn built in Minecraft) ("Several games: MG7") | 2026-10-14 | |
| MG8 | Hoenn and the pass to it | **done**: the spec [specs/HOENN_PASS_PLAN.md](specs/HOENN_PASS_PLAN.md) (2026-10-14), phases HP1–HP8; the test pass Viridian City ↔ Littleroot Town; Kanto and Hoenn in one world; 20 crossings each way between the real Blue and Emerald ("Several games: MG8") | 2026-10-14 | |
| MG9 | Johto (Gold, Silver, Crystal): the Gen 2 bridge | **in progress**: [specs/GEN2_BRIDGE_PLAN.md](specs/GEN2_BRIDGE_PLAN.md) (2026-10-15), from a static inspection of the Gen 2 engine (research notes `refs/_research/g2_*.md`); phases G2S and G2A–G2H; decisions J1–J14 with defaults. **G2S done** ("Johto (MG9): the inspection G2S"); **G2A done** (Crystal followable: "Johto (MG9): G2A notes"); **G2B done** (Crystal's UI: "Johto (MG9): G2B notes"); **G2C done** (Crystal's story: "Johto (MG9): G2C notes"); **G2D done** (Crystal's export: "Johto (MG9): G2D notes"); **G2E done** (the builder, 112 families: "Johto (MG9): G2E notes"); **G2F done** (battles, party, bag: "Johto (MG9): G2F notes"); **G2G done** (the real Johto at slot 2; indices and slots on demand: "Johto (MG9): G2G notes"); **G2H done** (the Kanto ↔ Johto test pass, crossings between the real Blue and Crystal: "Johto (MG9): G2H notes"): **MG9 complete** | 2026-10-15 | |

### Several games: notes

1. **The game catalogue** (MG1, plan §4.2): `protocol/games.json` (schema `games.schema.json`) names the eleven
   Gen1Recomp game ids with their family, engine, generation, label, default region (the world space: `kanto`,
   `johto`, `kanto_frlg`, `hoenn`), compat profile, art set, adapter flag and story markers (Gen 1: `starter` =
   `EVENT_GOT_STARTER`, `champion` = `EVENT_BEAT_CHAMPION_RIVAL`, the flag the Hall of Fame sets; the other families'
   markers are filled in with their adapters). `defs` gained `gameId`, `regionId`, `family` and `compat`; `hello.ok`,
   `export.index` and the WorldIndex carry an open `game`; `hello.ok` adds `family` and `compat` (optional on the wire,
   the catalogue's when absent); `hello.reject` gained the reason `game`; the WorldIndex an optional `family` (never
   written for a legacy build, so every golden is byte-identical). `kb_protocol.catalog --write` generates
   `gen1-bridge/lib/kb_games.lua`; Gradle bundles the file in the jar beside `catalog.json`; `kb_protocol.games`
   reads it for the stand-ins and tests; `fake_gen1 --game` takes any catalogued id. **Deviation from the plan's
   sketch:** Gen 3's `compat.nick` is 10 (the Gen 3 nickname limit), not the sketch's 7 (its player-name limit);
   the adapter's `hello.ok.compat` wins anyway (MG7).
2. **gen1-bridge** (MG1): `kb_session` sends the running game's id with its family and compat, never coerces to
   `red`, and answers `hello.reject{reason:"game"}` for a game outside the mod manifest's `games` list (the loader
   hands the validated manifest over as `mod.manifest`, its list resolved to version ids; the catalogue's `adapter`
   flag decides when no manifest is reachable); `kb_export` writes the real id and puts the layout resource under the
   game's default region (`layout/kanto` for Red and Blue, unchanged bytes) and fails the build for a game outside the
   catalogue (`export.unknown_game`); the pairing lives in one file per game, `session/pairings.<game>.json`, with the
   legacy install-wide `pairings.json` read once for the game's entry and never rewritten (one process per game must
   never clobber another's). `main.lua` imports 34 modules now (`kb_games`).
3. **fabric-bridge** (MG1): `Games` reads the bundled catalogue (`known`, `ids`, `family`, `compat`, `region`, `label`,
   `artSet`, `adapter`, `withAdapter`, `compatible`); `WorldIndex`, `ExportBundle` and `ExportClient` ask it instead
   of their `{red, blue}` sets; the layout resource id is `Games.region(game)` and the Gen 1 palettes id `sgb` lives in
   one constant (`ExportBundle.GEN1_PALETTES_ID`); `HelloOk` carries `family` and `compat` (parsed from the message,
   else the catalogue's). JUnit: `GamesTest` (the bundled resource equals `protocol/games.json` byte for byte),
   `WorldIndexTest` (any catalogue id accepted, `family` round trip), `ExportBundleTest`, `ClientSideStateTest`,
   `BridgeSessionTest`. Rule from now on (plan §4.2): no new code names `red`, `blue`, `kanto`, `sgb`, 151 or 165.
4. **MG2's schemas** (plan §4.4, written first): `game.status` (G→M, C→S), `region.park`, `region.resume`,
   `region.start` (M→G) answered by `act.result` kinds `park`/`resume`/`start` with a new optional `detail`
   (`new`/`continue`), `snapshot.away`, the route list v2 (`kind: connecting`, two ends with their own region, classes
   and music) and the export's `routes` v2 (`ends`, `end`); PROTOCOL.md "Regions (MG2)". A free-roam list and export
   keep `version: 1` and their bytes.

5. **The adapter split** (MG2, D1): `lib/kb_engine.lua` moved whole to `lib/engine/gen1.lua` (history kept): the Gen 1
   engine adapter, its `Gen1.REQUIRES` table (was `Engine.REQUIRED`; the three Game-object slots documented) and the new
   region group (`holdAway`, `releaseAway`, `awayHeld`, `startFromTitle`, `progressMarkers`). The new `lib/kb_engine.lua`
   is the **loader**: it reads Gen1Recomp's engine id (`GameVersion.engine`) and loads `lib/engine/<engine>.lua` (only
   `gen1` exists); for `gs`, `crystal` and `game3` it hands the core a **stub** that answers every call harmlessly,
   reports `hasAdapter() == false` and logs `engine.no_adapter` once, so a process running such a game answers every
   `hello` with `hello.reject{reason:"game"}`. `Engine.GROUPS` names Appendix B's 14 groups; `engine.groups` logs the
   groups an adapter lacks (`engineGroupsMissing` in the exports). CLAUDE.md's rule reads "only the engine adapters
   `lib/engine/*.lua` touch engine internals"; `tools/tests/test_pins.py` checks both files. `main.lua` imports 36 modules.
6. **The region acts and the progress observer** (MG2): `act/kb_region.lua` answers `region.park` (the hidden player
   idle in the game → a hold state on the engine's stack modelled on Bill's PC hold, kept in the process-lifetime slot so
   a hot reload keeps it; the music fades; `mod.save "region" = {away}`), `region.resume` (the hold popped, the marker
   cleared, the engine's own `warp` queued only onto one of this mod's route maps or the away cell, answered on arrival)
   and `region.start` (from the title only: `SaveData.load` + `Game:restoreSave` for CONTINUE, else `Game:startNewGame`
   with the intro; an open title menu is closed first with `Game:returnToTitle`), one request at a time like the Wild
   Routes acts; a link drop pops the hold and leaves the marker for the next snapshot (`snapshot.away`).
   `observe/kb_progress.lua` builds `game.status` (playthrough, ingame, started, badges, the catalogue's markers resolved
   through the adapter, the save's player name, the active slot) after every snapshot, after `session.bound`, on
   `save.created`/`save.loaded` and when a marker, the badge count or the playthrough changes (checked every 10 steps).
7. **Routes v2** (MG2): `kb_routes` accepts list versions 1 and 2 and `kind: connecting` with `from`/`to` ends (each with
   its own `region`, `classes`, `music`); a game registers only the end(s) whose `games` include it; the export lists one
   item per registered end with `ends` and `end` and writes `version: 2` only when a connecting route is listed, so a
   free-roam export's bytes are unchanged. `fake_gen1` gained `--state title|fresh|saved`, the three acts and
   `game.status`; `tools/e2e/test_regions.py` (driver `kb_regions.lua`) runs the acts on the real engine: a `start` from
   the title on an empty slot (NEW GAME, the intro's first text box, no stray input) and on a saved slot (CONTINUE,
   `save.loaded{fresh:false}`), and a park/resume round trip with the cell unchanged.

8. **MG3's schemas** (plan §4.6, written first): the region table `protocol/world/regions.schema.json`
   (`<world>/kantobridge/regions.json`: `pitch` 8192, entries `{id, game, slot}`), the region record
   `region.schema.json` (`regions/<id>/region.json`), `slot_map.schema.json` version 2 (one map per world; `regions{<id>:
   {set, families[lo,hi]}}`, `region` per entry, families 0..31; version 1 unchanged with families 0..15), the WorldIndex's
   closed-art slot cap 8191 (32 families), `world.regions` (S→C, parts), and an optional `region` on the 33 relayed types
   (`world.index`, `world.shapes`, `texture.status`, `story.state`, `session.*`, `save.*`, `warp.request`, `route.*`,
   `party.*`, `daycare.*`, `bag.*`, `battle.*`, `pc.*`); PROTOCOL.md "Regions (MG3)" (files, the legacy rule, the join
   order, the slice-hash rule: a region's `slotMapHash` is the hash of its own slice of the one slot map, so the legacy
   region's stays exactly today's). `kb_protocol.registry` gained `check_regions`/`check_region`.

9. **MG4's, MG5's and MG6's schemas** (written first, PLAN §7, while MG3's tracks ran): `region.state` (C→S, S→C:
   `followed?`, `parked[]`); `pass.cross{route, dir}`, `pass.abort{route, reason}`, `party.stash{region}` (C→S),
   `act.result` travelling S→C for the stash's answer (`moved`) and C→S for `bag.set`'s (`left`), the WorldIndex zone
   fields `pass{cells, route, toRegion, end}` and `gateHouse{x, y, w, d, facing}`, the story-cell type `pass`
   (PROTOCOL.md "Crossings (MG5)"); `protocol/items.json` + `items.schema.json`, the canonical item catalogue (128 ids:
   the 29 mirrored battle items with their Cobblemon ids, medicine, field items, the 5 stones, the Nugget, 28 key items
   incl. the HMs, 50 TMs by Gen 1 move number; `symbols.gen1` from the engine's own import manifest
   `tools/rom_manifest_blue.json`, i.e. `TM_<MOVE>` with the engine's move symbols such as `SOLARBEAM`, `PSYCHIC_M`),
   generated into `gen1-bridge/lib/kb_items.lua` by `kb_protocol.catalog --write`; `bag.sync` + `money`, `stamp`;
   `bag.set{items, money, stamp}` (S→C, M→G); `act.result.kind` may be dotted (`bag.set`); PROTOCOL.md "One economy
   (MG6)". **Deviations from the plan's sketch:** item classes `field` and `stone` added to the plan's seven (repels,
   ropes and dolls are neither battle items nor valuables; the stones are PartyItem's); `act.result` gains the `c2s`
   leg instead of a separate `bag.result` type.

10. **The region model** (MG3, plan §4.6): `world/RegionTable` (`regions.json`: slot `n` at `x = 8192·n`, D8),
    `world/RegionRecord` (`regions/<id>/region.json`: game, family, export hash, slot, origin, bounds, the four areas,
    the slot-map family range, the three documents with their hashes, generator, rules, namespaces; the implicit legacy
    record for a world without the table), `world/Regions` (`at(x, z)`: bounds then areas; `legacy()`), `SlotMap` v2
    (one map per world; `regions{id:{set, families}}`, keys carry region and art set, `enumerateRegion` assigns a
    region's keys inside its own family range and never renumbers another's; `regionSlice`/`regionHash` give the
    version-1 form of one region's entries, which is what that region's `WorldIndex.slotMapHash` names — the legacy
    region at families 0..15 keeps today's hash to the byte); `FAMILIES_PER_SHAPE` 16 → 32 in `KbBlocks` and `SlotMap`
    (+12,288 block states; the closed-art slot cap 8,191). Version-1 maps stay capped at families 0..15.
11. **The builder** (MG3): `BuildOptions.withRegion(id, slot)` (`defaults()` is a legacy build, byte for byte today's);
    the headless build takes `-Pkb.buildRegion`/`-Pkb.buildSlot` (`kantobridge.build.region`/`.slot`; `build_world.py
    --region --slot`); `RegionBuild` carries the art set, the family range, the previous world slot map and the pool
    prefix (`""` at slot 0, `<id>_` elsewhere) into `ArtCatalog`, `WorldPlanner`, `HabitatPlanner`, `ZonePlan`;
    `WorldPlan.regionBounds()/regionAreas()` (surface, zone, dungeon and cave boxes); `BuildTask` reads the table,
    refuses a legacy build into a region world (`build.region_required`), a taken slot, an overlap
    (`build.region_overlap`) and a full slot map (`build.slot_map_full`), writes `regions/<id>/…`, the world slot map,
    `regions.json` (and the implicit `kanto` record when another region is a legacy world's first region build), merges
    the generated datapack's pools across regions, publishes per region, and reports a `region` object.
12. **The server** (MG3): `server/Regions.of(server)` (`at`, `byId`, `legacy`, `indexOf`, `regionOf(player, data)`: the
    message's `region`, else the player's position, else the legacy region); `WorldIndexSync` publishes per region
    (`publishRegion`, `publishTable`) and sends, in a region world, `world.regions` → each region's `world.index{region}`
    → one `world.slots` → each region's `world.shapes{region}` (a legacy world sends exactly today's sequence); warps,
    zones (visits keyed by region and zone), dungeons (visits and slots per region), habitats, Flash, story state,
    encounters, battles, battle music and texture status read the player's region; every strict reader of a relayed
    type whitelists `region`; `SessionRecord` format 2 (`regions{<id>:{slot}}`, written only once a non-legacy region is
    paired), `kantobridge:save_pairs` for non-legacy regions (the legacy pair stays in `kantobridge:save_pair`, so a
    legacy world's player data is byte-identical), daycare files under `regions/<id>/daycare/`, `kantobridge:progress`
    fed by `game.status` (`ProgressService`), `kantobridge:presence` fed by `region.state` (`RegionPresenceService`,
    which clears the followed region's story state on a swap and sends the stored presence on join in a region world).
13. **The client** (MG3): `client/world/RegionDocuments` (a pure state machine over the `world.regions`, `world.index` and
    `world.shapes` parts, keyed by `region`; a legacy world's untagged index is the implicit `kanto`) behind the static
    `ClientRegions` (`byId`, `byGame`, `at`, `followed()` — the legacy/only region, else the region under the player's
    feet until MG4 binds it to the followed link — `indexOf`, `indexHash`, `shapesOf`, `tagFor`); `ClientWorldIndex`
    is the shim (`current()` = the followed region's index; the 36 call sites stay on it); `ClientShapes` holds one
    geometry set per region, checked against that region's index hash and built from that region's game's export
    (`no_art` placeholders otherwise); `TextureSync` reports `texture.status{region}` with the region's slice hash;
    `ClientRelay` tags every relayed type (`RegionTags`, cross-checked against the schemas by a test) with the followed
    region in a region world only; `GameRelay` forwards `game.status`. The render snapshot still holds one shape
    lookup (the followed region's): other regions draw as textured cubes until followed (a later item).

14. **Crossings, the builder** (MG5 [B], D12): `ExportRoutes` reads the `routes` resource v2 (`connecting`, `ends`,
    `end`); `ZonePlanner` plans a connecting half as a `CONNECTING` layout (levels rise with the layout's `progress`
    toward the pass) whose far gate is a **gate house**: the far plaza is 7×5 cells inside (the trailhead keeps its
    4×6), walls of oak planks with glass panes, a dark-oak roof at feet+4 with lights under it, the near opening and
    the far door walkable; the far door's cells are the **pass**, carried in the WorldIndex zone entry as
    `pass{cells, route, toRegion, end}` with `gateHouse{x, y, w, d, facing}`, and placed as story cells of the new
    type `pass` (closed per player: `pass_closed`). One game holding both ends (a test world) gets `<id>_to` as the
    second zone. `build.region_exists` refuses a second region of one world space (unless
    `-Dkantobridge.testRegions=true`); the report counts `zone_passes`. `RouteGeneratorTest`'s connecting fingerprint
    (`GOLDEN_ROUTE`) changes with the bigger plaza and is re-stamped from the pod run.
15. **Crossings, the server** (MG5 [S], D5): `server/PassCrossings` (pure decisions, the dungeon visits' pattern) and
    `PassService`: `pass.cross` needs the player on a pass cell of the half named, the far region in the table
    (`pass.no_region`), built with a half of that route (`pass.not_built`), a link for it (`pass.no_link`), no crossing
    in progress (`pass.busy`) → `route.fade{ticks:10}` and a 15 s timer; on `region.state{followed: far}` the player
    is teleported one cell inside the far gate house's door (or, after a `start`, left to the client's relink);
    `pass.abort` or the timer → `error{code:"pass.timeout"}`. `pokemon/StashService` answers `party.stash` (every
    party member into Cobblemon's PC, rolled back whole on a full PC: `stash.pc_full`; `stash.busy` in a battle or
    under a build hold) with `act.result{kind:"stash", moved}`. `StoryState`/`StoryRules` close pass cells until the
    client opens the route (the far region built and linked).
16. **One economy, the bridge mod** (MG6 [L], D7): the Gen 1 adapter gains `isKeyItem`, `itemTable` (symbol →
    canonical id through `kb_items.lua`), `tmMove`, `bagLimits`, `bagSet` (clears the catalogue's non-key items, adds
    the projection in class order one `Bag.add` per 99, reports what did not fit); `kb_bag` syncs every non-key
    catalogue symbol with the save's `money` and the ledger `stamp`, mirrors only the 29 Cobblemon items;
    `act/kb_ledger.lua` answers `bag.set` (`not_ingame`, `busy`, `too_many` with `left`) and keeps the stamp in the
    save's mod data. `main.lua` imports 38 modules.
17. **The export driver on a slow disk** (found by the MG3 pod check): on the pod's network volume the engine
    re-checks a dataset view's readiness on every record read and gen1-bridge's export takes a quarter of an hour;
    the Wild Routes drivers' `KB_DATASET_MEMO` memo (seconds; `kb_wild.lua`) now also serves `kb_export.lua`, and
    `build_world.py` passes it (default 30) with the export index timeout (`-Pkb.exportTimeout` from
    `KB_EXPORT_INDEX_TIMEOUT`). gen1-bridge's heartbeat no longer counts a stall of the hidden game itself (over a
    second between two logic steps) as the client's silence (`session.stalled`): a 10.8 s stall had dropped the
    builder session mid-export.

18. **One economy, the server** (MG6 [S]): `world/Items` reads the bundled catalogue; `server/Ledger` (the player's
    wallet and item ledger, `kantobridge:ledger`, persistent, copied on death) with the pure `LedgerRules` (limits
    gen1 20 slots / 99 a stack; `readBack`, `adopt`, `project`, `use`, `mirrorTargets`) and `LedgerService`: in a
    region world every `bag.sync` is read back as the diff since the last projection (prize money, purchases, found
    items, the blackout halving), a save without a stamp is adopted once (by its save pair; a never-saved New Game
    once per region), a stamp the server doesn't remember re-projects, and after a crossing's teleport or relink (and
    on the followed region's `save.loaded`) the ledger is projected into the game with `bag.set`; `too_many`'s `left`
    stays in the ledger ("in your trunk"). `BagService` mirrors from the ledger in a region world and marks every
    stack it creates (counting and taking only marked stacks, in legacy worlds too, which fixes the deletion of items
    found in Minecraft); a use debits the ledger before `bag.use` goes to the followed game. `BagItems` is a view over
    the catalogue's 29 mirrored items.

19. **Links** (MG4, D2, D3): the client holds one `BridgeLink` per configured hidden game (`kantobridge-client.json`
    `links[{game, port}]` or `-Dkantobridge.links=blue:27151,emerald:27152`; the legacy `port` is one link whose game
    is learnt from `hello.ok`), each with its own `BridgeClient`, dispatcher, state, session gate and binder, save and
    PC relays, game relay, story rules and texture sync; `Links` follows one (the first connected link whose game is
    right, or the server's stored presence), parks the rest (connected, pinged, `party.sync`/`dex.sync` fanned out to
    all), and swaps with `Links.follow(game, allowTeleport)`: the body's `FollowedDispatcher` emits a disconnect so
    screens, puppets and the input lock clear themselves, `MovementSync.linkFrom` relinks from the new hidden place
    (one ordinary step at most; a teleport only for a `start`), a `resync` re-seeds the body, `region.state` goes up.
    A link whose `hello.ok.game` isn't its configured game is `wrong_game`: never followed, a HUD line. The pause
    overlay follows the followed link only; F7 lists every link. `BridgeRuntime.client()/dispatcher()/state()/port()`
    stay as shims to the followed link, so the per-body runtimes moved without edits. **"Add a region"** on the title
    screen: a world, a connected link, a region id (the catalogue's default; a second region of one world space is
    refused unless `-Dkantobridge.testRegions=true`) and the next free slot → that link's export → the existing world
    opened with the region build (`withRegion`); the one generated texture pack holds Kanto's art only (the pack
    cannot merge art sets yet: the region's blocks draw as cubes until that lands). Tools: `tools/kb_games.py` (import
    detection: catalogued folder names holding `rom-cache.complete`, nothing read), `tools/setup_games.ps1/.sh`
    (one portable install per world space from the pinned release, Gen1Recomp's own headless importer per inbox
    file, the Union Room switched off, `launch.json` v2 and the client's `links` written; `-DryRun`),
    `launch.ps1 -Games/-Regions` (every install with an adapter game, ports 27151 + i, player 2 at +10,
    `-Pkb.links` to the dev client), `run_gen1recomp.py` detecting any catalogued import, `fake_minecraft --links`
    with `tools/tests/test_links.py` (two `fake_gen1`: the follow/park swap, a parked drop never pauses, a wrong game
    never followed).

20. **Crossings** (MG5, D5, D6, D12): a connecting route is **two halves**, one per region, each ending in a 7×5
    **gate house** (`RouteGenerator`: oak planks, glass panes, a dark-oak roof, lights; `HOUSE_DEPTH 7`, `HOUSE_WIDTH 5`,
    the route goldens regenerated); its far doorway is the **pass**: `WorldIndex` zone entries carry `pass{cells, route,
    toRegion, end}` and `gateHouse{x, y, w, d, facing}`, the pass cells are story cells of type `pass`
    (`StoryCellType.PASS`, closed per player as `pass_closed` while the far region is unbuilt or the player has no link
    to its game; `StoryRules.setOpenPasses`). The builder plans them as `ZonePlan.Crossing` (`ExportRoutes` v2 names
    the connecting routes and their regions). **Server:** `PassCrossings`/`PassService` (`pass.cross` → standing on the
    pass, `pass.no_region`, `pass.not_built`, `pass.no_link`, `pass.busy`, in that order → `route.fade{ticks:10}` and a
    300-tick timer; `region.state` from the client → the teleport just inside the far gate house, or a relink after a
    `start`; `pass.abort` or the timer → `error{pass.timeout}`; counters for F7 and the tests), `StashService`
    (`party.stash` → every party member into Cobblemon's PC, `act.result{kind:"stash", moved}` or `stash.pc_full`).
    **Client:** `PassChoreography` (the pure state machine: IDLE, CROSS_SENT, PARKING, STASHING, STARTING, RESUMING,
    AWAIT_INGAME; one 15 s clock; every failure → `pass.abort{route, reason}`, the fade lifted, one HUD line) and
    `PassRuntime` (its glue on MG4's links), `UiRouter.setAutoName` (D6: the parked save's name answers the started
    game's first naming screen; shown only when cut or when the game asks something else), `ZoneRuntime` forwards a
    half's `route.give`/`route.battle`/`blackout` to that region's link and can extend, restart or lift its fade.
    **Tests:** `PassGameTests` (the round trip into the far gate house with the exact arrival cell, every refusal with
    its `re`, abort, the timeout, the start path, the stash, a built half whose pass is a story cell), `PassCrossingsTest`,
    `ZonePlanTest`, `WorldIndexTest`, `PassChoreographyTest` (16), the two-`fake_gen1` crossing in
    `tools/tests/test_stand_ins.py` (park, stash, start, resume onto the route map; the M→G half). **A GameTest lesson:**
    a batch's tests run at the same time, so tests that reset a service's static state (`PassService.reset`,
    `WorldIndexSync.withdraw`, `DungeonService.reset`) each get **their own batch**; the dungeon tests, in `defaultBatch`
    for a year, flaked that way under the longer run ("the house holds 0") and are batched now too. **Gaps:** a crossing
    between two real engines needs a second region whose game has an adapter: Red and Blue are both Kanto (one region
    per world space), Johto has no adapter, Hoenn is MG7/MG8; the GameTests and the stand-ins cover both halves
    meanwhile. `stash.pc_full` has no test (no Cobblemon API in the tests to fill the PC). `BridgeClientTest`'s backoff
    test raced `state()` against the listener's queue once on the pod: the state is now published after the listener
    hears of it.

21. **One economy, the rest** (MG6, D7; note 18 has the server): **gen1-bridge** `act/kb_ledger.lua` answers `bag.set`
    (M→G) through the adapter's `bagSet` (`lib/engine/gen1.lua`: `isKeyItem`, `itemTable`, `tmMove`, `bagLimits` 20
    slots × 99, `bagSet` replaces the bag's non-key items by the projection, writes `save.money` and keeps the `stamp`
    in the save's mod data) with `act.result{kind:"bag.set"}`, `too_many` and `left` for what did not fit, `not_ingame`
    and `busy` refusals; `observe/kb_bag.lua` was rewritten so every `bag.sync` carries `money` and, once projected,
    the `stamp` (`lib/kb_items.lua` is the generated catalogue copy). **Client:** `BagSetRelay`/`BagSetRuntime` forward
    a `bag.set` to the link of its `region` and the game's `act.result` back under the server's seq; `ActRuntime`
    leaves kind `bag.set` alone; F7 has a `bag` line (money, items, trunk, stamp, counters). **Tests:**
    `tools/lua/test_lua_ledger.py` (the projection, `too_many` with `left`, the stamp echoed, the refusals) and
    `test_lua_bag.py` (money and stamp on the wire; `fake_wild.lua` grew `Bag.capacity`), `BagSetRelayTest`,
    `LedgerTest`/`LedgerCodecTest`/`ItemsTest`, and the GameTests `LedgerGameTests` (two regions: adoption of an
    unstamped save and its projection with a region-tagged `bag.set` and a 16-hex stamp; stamped read-back of a drunk
    Potion and prize money with no re-projection; a crossing's projection into the second region with its own stamp
    and the game's confirming sync changing nothing; `too_many`/`left` shrinking the remembered projection while the
    ledger keeps the trunk; an unknown stamp counted as `reprojected` and projected afresh without a diff; a legacy
    world keeping today's per-game bag with `LedgerService.enabled` false) and `BagGameTests`'
    `thePlayersOwnFindsAreNeitherCountedNorTaken` (an unmarked stack survives the mirror and its use sends no
    `bag.use`). **Not covered:** `stash.pc_full`, and a real engine's `bag.set` round trip beyond the Lua stand-in
    (your play-test: PLAYTEST.md "MG6"). **Commits:** MG4, MG5 and MG6 were built in one working tree and are committed by
    path group (`MG4: links`, `MG5: crossings`, `MG6: one trainer economy`, after `MG3 follow-up: the session stall rule
    and the export memo shim`); the files they share (both entry points, the lang file, `build.gradle`, the GameTest
    `fabric.mod.json`, the docs) are in MG6's commit, so the three roll back together
    (`git revert --no-edit <MG4 commit>^..<MG6 commit>`), and a tree between them need not compile.

### Several games: spikes (2026-10-14)

All five spikes (plan MG0) ran on pods instead of on your PC, at your requests ("Please use a pod to collect information
for these spikes", then "Please complete S4 and any others you can": you are away from a computer for weeks). S2, S3, S4
and S5 are complete. S1 is done as far as a pod can go: what still needs your PC is at its end.

**How.** A throwaway probe mod, `kb-s2-probe` (`games: ["emerald"]`, `engine_internals`), and a test driver ran through
`tools/e2e/run_gen1recomp.py` with `--game emerald` on the v0.3.85 data home; the runner's filter lets only `KB_*` lines
through and counts and hashes the rest. The probe was copied into the test install's `mods/` for each run and removed
after; nothing of it was left behind. The driver started a new game in memory, straight into the field, with the action
Birch's speech ends with (`Game3:_handleBootAction{action = "new_game"}`, no truck scene), never saved, and drove the
hidden player with button holds and the mod's `mod.world` calls. Three runs (a baseline; the flag and dataset
diagnostics fixed; the map patches and a new map on), each 1–3 minutes, exit 0, crash log untouched. Pod: `kb-dev8`
(new: cpu5c ×4, $0.14/hr, the same volume), because kb-dev7's and kb-dev5's hosts were full and kb-dev6 started but had
no container after 10 minutes and was stopped; about $0.11 in all, stopped after. The probe stays out of the repo (the
plan: no spike code in `main`); a copy for re-runs after an engine bump is on the agent's server
(`~/kb-runpod/spike-s2/`), and the filtered logs are on the volume (`/workspace/logs/s2-run{1,2,3}.log`).

**S2: what the Gen 3 adapter can use on Emerald (Gen1Recomp v0.3.85)**

| Asked | Result | For MG7 |
|---|---|---|
| Engine | `GameVersion.engine()` is `game3` (with or without the game id), layout `rse`, generation 3. `mod.world` is the Gen 3 WorldAPI (Emerald has no row of its own: the loader falls back by engine to FireRed's) and `mod.input` is there | the adapter keys on `game3` |
| `mod.datasets:open("emerald")` | Opens. Reads `pokemon` (386), `moves` (354) and `encounters` (348). Empty: `items`, `trainers`, `text`, `map_scripts`, `scripts`, `rom_text`, `type_chart`, `growth_rates` and every Gen 1/Gen 2-only registry. **`maps` fails the registry schema** (a type check): the read returns nothing and marks the whole view invalid, so every later read in the session comes back empty and a new `open` answers `invalid_cache` | the Gen 3 export can't go through the dataset view as Gen 1's does: read the live engine (`game.data`, the native packs), and never read `maps` through the view |
| `mod.world` | `current()` gives `mapId` (`EM_*` ids), `x`, `y` and `facing`. `warpTo(map, x, y, facing)` works, also when called from inside `world.stepped` or on the next `input.step`. `queueScript` runs `setflag`, `clearflag` and `warp` rows and refuses the rest (`heal`: unsupported command). `getFlag`/`setFlag` take Emerald names or numbers. `replaceBlock(x, y, metatile)` sets one 16-px metatile and makes the cell passable: a wall cell opened this way was walked into | movement, warps and story flags without patching internals |
| `maps:patch` on an Emerald map | Accepted (an unknown field, and a whole `warps` list); the merged Littleroot still loads and plays as before. **A warp appended on open ground is not active**: the engine indexes a header warp only on a warp tile behaviour (`src/core/game3/collision.lua`, `installWarps`), and stepping on it did nothing | a map patch can't make an exit |
| `maps:register` of a new map | Accepted (a copy of the truck's record under a new id), but `warpTo` to it answers true and never arrives (the player stays where they were; no crash): a Gen 3 map needs a native layout, which the engine finds by map id | no `KB_ROUTE_*` map of our own: the hidden player's route map must be an existing map, or the player is held (MG7 decides) |
| `map_scripts` | A Gen 1-shaped record (`onStep`, `onEnter`, `onLoad`, `onFrame`) is accepted and lands in `gen3Scripts` (the ROM script bundle) under its id, and is **never called** (0 calls over 11 steps and 12 map entries) | Gen 1's step-script exit doesn't carry over |
| Registries a Gen 3 boot drops | `tilesets`, `field`, `music`, `map_songs`: each write answers nil and the loader notes "has no Gen 3 target" | as plan A.4 says |
| Hooks and events that fire | `world.stepped` (mapId, x, y, tile, facing; one per step), `map.entered` (`via` is `boot`, `warp` or `connection`), `map.exited`, `player.warped` (kinds `warp`, `door`, `exit_door`), `warp.destination` (destMap, x, y, ctx; for doors, exits and `warpTo`, **not** for walking over a map connection; its return **redirects** a door: May's door led into Brendan's house), `input.step` (each fixed step; game, dt), `world.interacted` (kind `npc`) and the `world.talk` hook, `script.command` (23 ops seen), `flag.changed` (see S3's caveat), `world.block_replaced` (twice per `replaceBlock`), `encounter.roll` (Route 101's grass), `render.output_enabled` (every frame) and **`render.output`** (every frame once a mod enables output; plan A.4 had it Gen 1/Gen 2 only), `save.created`, `game.ready`, `mods.loaded` | the mirror can capture frames on Gen 3 |
| Never fired | `movement.collision`, `movement.speed`, `fieldmove.eligibility` (as A.4 says), `screen.pushed`/`screen.popped` (Gen 3's UI has its own stack), `core.update` (as on Gen 1 under a driver). Not exercised: battles, CONTINUE and saving (`save.loaded`, `save.writing`), `trainer.before_battle`, `item.use`, `ui.pc.items` | the UI group reads Gen 3's own UI stack |

**The exit (S2's question): neither `map_scripts` nor a patched warp, but `world.stepped` + `mod.world:warpTo`.** The
probe's trigger cell in Littleroot warped the player into another map, both straight from the event and deferred to the
next `input.step`. `warp.destination` can redirect an existing door or warp, `replaceBlock` opens exit cells at run time
(the part Gen 1's block patch does), and a route reached over a map edge shows as `map.exited` and `map.entered` with
`via = "connection"`.

**S3: Emerald's story flags**

| Asked | Result |
|---|---|
| "Has starter" | `FLAG_SYS_POKEMON_GET` (0x860) |
| "Champion" | `FLAG_SYS_GAME_CLEAR` (0x864) |
| A flag through the `game3` store | `mod.world:getFlag` reads the live store (`Space.store`, `src/core/game3/scripting/space.lua`) by name or number; both flags were set, read and cleared on the new game. Unset reads `nil`, not `false`, and so does an unknown name, so names must be checked against `Flags.active(session).IDS`; `setFlag` refuses an unknown name ("unknown FireRed flag") |
| The badge count | No mod facade. `Flags.countBadges(Space.store)` (`src/core/game3/scripting/flags.lua`) counts Emerald's own badge flags `FLAG_BADGE01_GET`…`FLAG_BADGE08_GET` (0x867–0x86E): 0 on a new game, 1 with badge 1 set, 0 again once cleared; `getBadgesMask` agrees |
| **Caveat** | `flag.changed` names Emerald flags from FireRed's table: of 169 events, 160 carried FireRed's name for that number (id 86 came as `FLAG_HIDE_OLD_AMBER`; Emerald's is `FLAG_HIDE_CONTEST_POKE_BALL`) and 9 a bare number, never Emerald's name. Only `id` is reliable: the adapter names flags with `Flags.active(session).NAMES` |

For MG7: the catalogue's Emerald markers (`protocol/games.json`, empty until the adapter exists) become `starter` =
`FLAG_SYS_POKEMON_GET` and `champion` = `FLAG_SYS_GAME_CLEAR`. FireRed's table in the engine source has the same two
names at 0x828 and 0x82C (not checked live: FireRed isn't imported).

**S4: NEW GAME and CONTINUE from the title (Gen 1, the pod's Blue import), 10 times each.** `tools/e2e/test_regions.py`'s
first test (written for S4 at MG2) with `KB_REGION_REPEATS=10`: 20 launches in 118 s, each driven over TCP by a
`fake_minecraft` test session with `region.start`, each on a fresh labelled test slot deleted after (the install's own
saves unchanged, as the test asserts). Pod `kb-dev9` (below). **S4 passes.**

| | NEW GAME ×10 | CONTINUE ×10 |
|---|---|---|
| `region.start` answer | `{ok, start, new}` every time | `{ok, start, continue}` every time |
| `game.status` after | `fresh`, started, in game, every time | `saved`, started, in game, every time |
| `save.loaded` | — | every time (`fresh: false`, with its pairing) |
| Stray input | none: Oak's first text box stayed open and no step was taken, every time | — |
| Errors, protocol problems | 0, 0 | 0, 0 |

**S1: two hidden games at once, measured on the pod.** A throwaway runner started each game from its own install
(on Linux an install is a data home): A, the volume's test home (Blue, with gen1-bridge); B, a second home with Emerald;
C, a third with Blue again and its own bridge (B and C on the pod's container disk, their import folders linked to A's
by name). Each process ran a meter driver under the engine's own pacing (`POKEPORT_DRIVER_PACED=1`) into the overworld,
with nothing saved (Blue on a throwaway slot, deleted after; Emerald a new game in memory), for six 10-s windows, two of
them walking, while the runner sampled each process's memory and CPU from outside and listed its listening ports. Pod:
cpu5c, 4 CPUs, 8 GB, no GPU (Mesa's software renderer under Xvfb).

| Scenario | Process | Memory, RSS (average, highest) | CPU, of one core | Frames and logic steps per second | Bridge port |
|---|---|---|---|---|---|
| Blue alone | Blue | 173 MB, 179 MB | 85 % | 60, idle and walking | 27230 |
| Emerald alone | Emerald | 418 MB, 431 MB | 89 % | 60, except 52.6 in the first 10 s in the field and 57.8 in one walking window | none (no adapter yet) |
| Blue and Emerald | Blue | 162 MB, 179 MB | 75 % | 60 | 27230 |
| | Emerald | 419 MB, 424 MB | 89 % | 60, except 53.0 and 57.8 in the same windows as alone | none |
| Blue and Blue, two installs | Blue A | 171 MB, 177 MB | 86 % | 60 | 27230 |
| | Blue C | 173 MB, 178 MB | 82 % | 60 | 27231 |

Emerald needs about 2.5 times Blue's memory (Lua heap about 95 MB against 20 MB, textures 17 MB against 0.3 MB), so
Kanto and Hoenn together hidden take about 600 MB. Running together slows neither game: every window matches its run
alone, and Emerald's dips are one-off loads (its texture memory grows in exactly those windows). Two installs give two
bridges, each on its own port. The CPU figures are mostly software rendering on a pod without a GPU, so they overstate
what a PC with a graphics card pays; the memory figures carry over roughly. **Still for your PC:** Minecraft running
beside the two games, and Windows' own memory figures. Minimised windows keeping full speed was shown for Gen 1 at M10.

**S5: the Windows build's headless import, under Wine on the pod, without a ROM.** The pinned Windows zip
(`tools/installer/pins.json`; size and SHA-256 matched, kept on the volume as
`/workspace/local/_downloads/gen1recomp-0.3.85/windows.zip`) ran under Wine 9.0, every case in a fresh throwaway copy
on the container disk, with `portable.txt` unless the case says otherwise:

| Case | Result |
|---|---|
| The launcher, `portable.txt` | its files are written beside the exe (`options.lua`, `launcher-window.cfg`); nothing in `%APPDATA%` |
| The launcher, no `portable.txt` | `%APPDATA%\pokemon-love2d\` |
| `POKEPORT_IDENTITY=kb-s5-hoenn`, no `portable.txt` | `%APPDATA%\kb-s5-hoenn\` |
| The identity with `portable.txt` | beside the exe: portable mode wins and the identity separates nothing |
| Import-only, a file that isn't a ROM | the importer refuses it ("Unsupported ROM") and the exe quits by itself with exit code 1 in about 3 s; nothing written |
| Import-only, a path that doesn't exist | "Could not read the selected file", exit code 1, about 3 s |
| Import-only with no path | the importer waits for a file until stopped (`setup_games.ps1` always passes one, and its `-TimeoutSec` covers a hang) |
| The two refusals on the Linux AppImage | the same messages and exit code 1 |

So the variables reach the fused Windows exe, a refused file ends the run at once with code 1 (which `setup_games.ps1`
already reports as "no completed import"), and D3's alternative, one exe with a data home per region through
`POKEPORT_IDENTITY`, works only without `portable.txt`. A first Wine pass showed window-creation failures; they came from
displays left over between cases, and a re-run on clean displays behaved as above.

**The success path, at your word** ("Also please import the rom for the test"): in a fresh throwaway portable copy of
the Windows build under Wine, `setup_games.ps1`'s variables imported your uploaded Blue zip in 4 s. The exe quit by
itself with exit code 0 and left `blue\rom-cache.complete`, so the tool reads the game from the folder name. The tool's
next step then booted the imported game once with `kb_union_off.lua`: `KB_UNION_OFF ok=true was=on
patched_this_boot=true`, exit code 0, 3 s. One detail: even with `portable.txt` the import made a
`%APPDATA%\pokemon-love2d\` folder (the portable-mode guide keeps the update cache there on purpose). The throwaway
install and the Wine prefix were deleted after; nothing was kept. **S5 passes** on the Windows build under Wine; your
first real `setup_games.ps1` run on Windows is the last word.

Pods for S1, S4 and S5: kb-dev7's, kb-dev5's and kb-dev8's hosts were full, so `kb-dev9` was made (cpu5c ×4, $0.14/hr,
the same volume); about 20 minutes, then 5 more for the import, about $0.06 in all, stopped after each. The runners stay out of the repo, like S2's probe, in
`~/kb-runpod/spike-s1/` and `~/kb-runpod/spike-s5/` on the agent's server; the filtered logs are on the volume
(`/workspace/logs/s4-run1.log`, `s1-run1.log`, `s5-run1.log`, `s5-run2.log`, `s5-import.log`).

### Several games: MG7, the Gen 3 bridge (GEN3_BRIDGE_PLAN.md)

**G3A (2026-10-14): Emerald is followable.** The `game3` adapter, `gen1-bridge/lib/engine/game3.lua`, grows from the
loader's stub (`Engine.stub`, so every function not written yet answers "no game" with the right type) and installs six
parts from `lib/engine/game3/`: `boot` (identity, `REQUIRES`, the hot-reload takeover, `save()` = the live session and
`saveView()` = the Gen 1-shaped facts the readers want: the player, every set flag by its Emerald name, the bag without
`ITEM_`, the badges as `STONEBADGE`…, money, coins), `observe` (the facts and NPCs, ids `O<localId>`), `move` (a
`Player.tryMove` tap queues the step verdicts Gen 3 has no hook for; warp departures from metatile behaviours;
interaction), `saves` (gen1's slot functions on the shared registry, saves only through `quickSaveAllowed`, the nurse
through `special HealPlayerParty` in the `script.command` hook, center saves, the save queue), `region` (the park is a
private `kb_park` field lock, put back after the engine wipes the tags, released with retries; CONTINUE through the boot
action, NEW GAME through the main menu's own `BootModules.startNewGame`) and `reliability`. `protocol/games.json` switches
Emerald on (adapter, markers `FLAG_SYS_POKEMON_GET` and `FLAG_SYS_GAME_CLEAR`) and the manifest lists it.

Core changes (GEN3_BRIDGE_PLAN.md §3): `Engine.stub`; `world` and `input` handed to the adapter; `engine.saveView()` read
by the snapshot, story, progress, bag and debug.status (`save()` keeps the save identity for kb_save and kb_pairing;
gen1's `saveView` is its save); `engine.takeVerdicts()` polled by main each step; the e2e runner defaults to the one Gen 1
game when Emerald is imported beside Blue. **Deviations the real engine showed** (each a small, generic core change; Gen 1
is untouched because its facts never carry the new fields): (1) Gen 3 chains a held direction straight into the next
step, with no standing poll between steps, so one `move.step` walked ten cells: the facts now say `chainsHeld` and the
mover lets go of the press as soon as the step is under way (`act/kb_move.lua`); (2) a NEW GAME's intro, Birch's speech,
runs in the boot phase before any field exists, so the start act timed out as `failed`: the facts say `starting` there
and the act accepts it (`act/kb_region.lua`), and `playthrough()` is `fresh` during the speech; (3) the park stored the
whole playing-song record where `fadeInBgm` wants the song id (fixed in `region.lua`). The dex and move ceilings by
`compat` (§3) move to G3F with party and battles: Gen 3's save view has no party or Pokédex until then.
`Engine.missingGroups` reports five groups missing (battles, export, routes, story, ui): `party` and `items` count as
present through names the core files under them (`bridgeToken`, `nextSerial`, `scriptCommand`, `inGame`).

**Tests.** `tools/lua/fake_engine3.lua` fakes every Gen 3 module and member the parts read (made-up `EM_TEST_*` maps and
flags; a FireRed table with other numbers, so a flag name handed to the engine reads the wrong flag as on the real
engine; held directions chain as on the engine); `kblua.Bridge(family="gen3")`, `rig.Rig(family="gen3")` and
`tools/lua/adapter3.py` boot it; `test_lua_game3_{boot,observe,move,saves,region,reliability,protocol}.py` (136 tests).
e2e `tools/e2e/test_emerald.py` with `drivers/kb_emerald.lua` on the pod's Emerald import (tools/e2e/README.md).

**Check** (pod kb-dev10, new: cpu5c ×4, $0.14/hr, the same volume; kb-dev7, kb-dev8 and kb-dev9's hosts were full):

| Suite | Result |
|---|---|
| pytest and Lua (local) | 1,867 passed, 10 skipped (1,724 before G3A) |
| JUnit / GameTests | 1,193 (0 failures) / 112 (0 failures) |
| e2e Emerald (`test_emerald.py`) | 2 passed: hello.ok `emerald`/`gen3`; the snapshot in Littleroot; `fresh`; a step into the house wall refused `tile`; a step down; the game's save announced (`save.begin`, reason `menu`); two steps up through the door into May's house (`warp` kind `door`); park refuses a step (`script`), resume ok; CONTINUE (`saved`, `save.loaded{fresh:false}`) and NEW GAME (Birch's speech) from the title |
| e2e Gen 1 regression on Blue | 10 passed (`test_s5_ready` ×2, `test_handshake`, `test_regions` ×2, `test_walk`, `test_multiplayer` ×2, `test_crash`, `test_hot_reload`) |

**Not yet** (later phases): the story's cells (G3C), the export and the builder (G3D–G3E), battles, party, items and
routes (G3F). Emerald bikes bypass `tryMove`, so a bike bump gives no verdict yet (the mover answers `script` after its
timeout).

**G3B (2026-10-14): Emerald's UI.** The adapter's `ui` part (`gen1-bridge/lib/engine/game3/ui.lua`) builds the core's
UI stack from Gen 3's scattered state, in the HUD's input order: the message box (`TextBox`: the engine's pages with
its control codes and colour tags taken out; ready on a stay box's last page only once the script's `waitbuttonpress`
is armed), a yes/no (`ChoiceBox`) or multichoice (`ListMenu`, its string variables filled in), and the stack's layers:
the start menu as a `Menu` without EXIT (it asks to quit to the title) and MODS, the scrollable list menu as a
`ListMenu`, the naming screen as a `NamingScreen` (its keyboard pages as the grid), every other screen a mirror. A
battle is one mirror until G3F; so is the Mart. In the boot phase the title and main menu are the mirror
("TitleState", as on Gen 1) and **Birch's speech** is its slide show (mirror) with its printers as text boxes, its
gender menu and the name's yes/no as choices and its naming screen on top. Three small core changes, inert on Gen 1:
a choice's cursor may say `noByCursor` (Gen 3's NO is the cursor and A, `act/kb_uiact.lua`), `uiSetGlyphs` may name
the button to tap after it (Gen 3: the cursor on OK, then A), and `engine.screenSize()` (new in the `ui` group) sizes
the mirror: `mirror.frame` is 160×144 or 240×160 (schema, PROTOCOL.md; the client's `MirrorLink` accepts both, the
mirror screen keeps a texture per size and draws Gen 3's picture under a dialog scaled to the canvas width).

**Check:** pytest and Lua 1,884 passed, 10 skipped (`test_lua_game3_ui.py`: 17; the fake engine's HUD now takes input
as the engine's does); e2e on pod kb-dev10, `test_emerald.py` 3 passed: the G3A field run unchanged; **a Littleroot
sign** read through `act.interact` and the UI (`act.result` kind sign, a text box advanced until it closes, the player
walking again); CONTINUE; and **NEW GAME with Birch's whole speech answered through the UI** (9 text boxes, BOY, the
name KB on the naming screen, YES) until `game.status` says in game with that name. JUnit 1,195 and GameTests 112 (0
failures; `MirrorLinkTest` and `Gen1CanvasTest` cover the new size); the Gen 1 UI regression on Blue, `test_ui.py`
(YES/NO by B and by A, the 160×144 mirror) and `test_opening.py` (Oak's naming screens by START), 2 passed.

**G3C (2026-10-14): Emerald's story.** The adapter's `story` part (`gen1-bridge/lib/engine/game3/story.lua`): flags
were already the save view's (G3A); **toggles** come from every map's object templates and their hide flags (the
script bundle's events, else the game data's maps; read from the live store by number, Emerald's own ids), reported as
`O<localId>` hidden while its flag is set; **cells**: Gen 3's runtime terrain is per cell (metatile ids up to 1023,
rebuilt by the map's scripts on every load, never saved), so the new **`story.cells{map, cells[{x, y, metatile}]}`**
reports the current map's cells whose metatile differs from what the client holds (on entry every changed cell, after
a same-map reload also the cells that went back with their own metatile, then every change), and the snapshot carries
`cells`. Core: `storyCells`/`cellBase` in the story group (Gen 1: none), `kb_story` diffs them per map; protocol:
`story.cells` (schema, example, PROTOCOL.md "Gen 3"), the snapshot's `cells`, catalogue and Lua catalogue
regenerated; client: `StoryRules` keeps the current map's cells for the Gen 3 world (G3E). A 240×160 `mirror.frame`
example came with it (G3B's).

**Check:** pytest and Lua 1,896 passed, 10 skipped (`test_lua_game3_story.py`: 6); on a new pod, kb-dev11 (cpu3g ×2,
$0.08/hr, made when EU-RO-1 had no 4-vCPU stock): e2e `test_emerald.py` 4 passed, among them the new `story` scene (the
snapshot carries `cells`; a cell changed through the engine's `Field.setMetatile` reported, then reported back with
its own metatile; an object's hide flag set: `story.toggles` hides `O5`, `story.flags` lists the flag); JUnit 1,196 and
GameTests 112, no failures.

**G3D (2026-10-14): Emerald's export.** *Measured first* (`tools/e2e/test_g3_measure.py`, numbers only, in
GEN3_BRIDGE_PLAN.md §5): 519 maps, 316,337 cells, 75 tileset pairs, 18,358 distinct flattened 8x8 quarters used by the
maps (26,155 over every metatile), 239 sprite sheets, 116 maps with encounters. **Consequence (E7):** a flat Hoenn needs
about 18,400 full-block texture slots, 72 families of 256 where a region has 16 today; E7's default (raise the families
per shape, to 96: about 90 MB a side instead of 30) is G3E's. *Built:* the adapter's `export` part
(`gen1-bridge/lib/engine/game3/export.lua`) gives the core its own resources (`exportResources`; the core caches,
hashes and serves them as for Gen 1): `g3map` (the layout's whole grid with its true size, connections, warps,
objects), `g3tiles` (each metatile's four quarters in the flattened art: over, else middle, else under; behaviours),
`g3image` (26 pages of 1,024 quarters, RGBA, baked on the CPU from the engine's own prepared atlases), `g3sprite`,
`g3layout` (the engine's own placement), `g3encounters` (national dex), `g3names`: seven new export kinds (schemas,
PROTOCOL.md, catalogues). On the pod: 862 resources, 4.3 MB, built in about 24 s. **For G3E:** Hoenn's surface is 49
maps; three of its loops don't close on a flat plane (Dewford Town and Route 107, Fallarbor Town and Route 114,
Verdanturf Town and Route 116, which also overlap): the builder joins those seams another way.

**Check:** pytest and Lua 1,904 passed, 10 skipped (`test_lua_game3_export.py`: 8, on `tools/lua/fake_export3.lua`);
on pod kb-dev11: `test_g3_measure.py` and `test_emerald_export.py` passed (every resource passes its schema, hashes and
sizes match Python's canonical JSON, counts match the measurement, every quarter is on a page). No Java change: the
builder reads the g3* kinds from G3E on.

**G3E (2026-10-14): Hoenn's builder.** `WorldPlanner.plan` hands a Gen 3 export to `Gen3Planner`; Gen 1's path is
unchanged (its golden index, scene and shapes are byte for byte the same). In short (GEN3_BRIDGE_PLAN.md §6 "As built"
has the details): readers for the g3* kinds; **96 families per shape** (E7: a Gen 1 region takes 16, a Gen 3 region 80);
cells from the collision bytes (`G3Cells`) in a Gen 3 form of `MapModel`; the surface by the export's layout, interiors
breadth-first over warps (0-based arrivals) and then dives and connections; invisible walls on elevation mismatches;
story cells for objects that keep their cell, water and boulder maps; art keyed by quarter page (`g3q.<n>`, palette
`BAKED`) and written baked (`BakedTextures`); habitats on the cells the engine rolls land encounters on, pools by
national dex up to 386 with the engine's slot weights; the Gen 3 front end (`Gen3FrontEnd`, one virtual atlas of
quarter ids) so the shape planner runs on Hoenn too; and **two art sets in one pack** on the client (the player's other
exports join the pack, each region drawn with its own game's set). Three export facts were added for it (`stays`,
`g3tiles.land`, `landWeights`; schemas, PROTOCOL.md). A real Hoenn is a **region build**: its art takes 72 families,
more than a legacy world's 16.

**Check:** pytest and Lua 1,926 passed (the export's new facts on `fake_export3`); JUnit 1,223 (2 skipped: the real-data
dry runs) and GameTests 115, no failures (new: `G3ReadersTest`, `Gen3PlanTest` 15 with a golden index and golden shapes, `Gen3FrontEndTest` 4 with a
golden scene, `TwoArtSetsTest` 5; GameTests `Gen3BuildGameTests` (the synthetic Hoenn as region `hoenn` alone at
families 0..79 and after the synthetic Kanto at 16..95: files, record, slot map, pools, ground, ledge, water, story
cells, the elevation wall, the house) and `all386NationalDexIdsResolve`). On pod kb-dev11 with the real Emerald:
`test_emerald_export.py` passed with the new facts (2,638 objects keep their cell, 304 walk, none unknown; the land
behaviours `TALL_GRASS`, `CAVE` and `INDOOR_ENCOUNTER` on 29,921 cells; the weights 20, 20, 10, 10, 10, 10, 5, 5, 4, 4,
1, 1), and `RealGen3DryRunTest` planned the export as region `hoenn` (§6 "Measured": 319 maps, 72 families, 50,388
water story cells; the plan ran out of a test worker's default heap, about 2 GB on the pod, and with 5 GB held 819 MB
after it). The export driver `kb_export.lua` now runs on Gen 3 too (it read
Gen 1's UI stack at the title).

**Not yet:** NPC and surf sheets for Gen 3 (puppets draw placeholders), animated tiles, darkness; the outdoor groups
entered by a door (the Safari Zone's six areas, Mt Pyre, Sky Pillar) are placed map by map; **200 maps aren't reached**
from the surface by a warp, dive or connection and are left out: those only scripts reach (the ferry's destinations,
the Battle Frontier, the islands) and **Sootopolis City with the maps behind its doors**, which has no dive or emerge link in the
engine's data (nor has its underwater map); G3G or MG8 places them apart. The three seams are walls and overlaps until
MG8 joins them, and the real Hoenn build in Minecraft (G3G) has its heap and its 50,000 water story cells measured.

**G3F (2026-10-14): Emerald's battles, party and bag.** Four new adapter parts, `party`, `battle`, `bag` (the items
group: `items` is a module name Gen1Recomp keeps for itself) and `routes`; every group now has a part.

- **Battles** (`lib/engine/game3/battle.lua`): `BattleBridge.start`, which every Gen 3 battle passes (trainer scripts,
  wild rolls, fishing, the story's specials), is wrapped once per process. While a client follows, a battle is **held**:
  started headless without auto fight, its facts go out as `battle.start` (a trainer's `TRAINER_CLASS_*` role, its
  trainer id as `party`, the team by national dex, level, Showdown moves and IVs; a wild one's national dex, level and
  kind: static, surf or fish). `battle.result` answers it: a **win** pays the engine's own prize and shows the trainer's
  defeat line and the money text in the field's message box (read through the UI); a **loss** runs the engine's
  whiteout (half the money, the heal point); an **abort** stops the script, so the trainer challenges again (Birch's
  first battle goes on as a win); then `Battle.abort` ends it. Link and facility battles, the Safari Zone and the
  tutorials stay native. Land encounter rolls are the habitat pools' while a client follows; water and the rod go on as
  surf and fish battles. **As on Gen 1, no experience, evolution or catch is written into the hidden game**: Cobblemon's
  `party.sync` and `dex.sync` bring them back (a deviation from §4.10's first idea of writing them into the battle).
- **Party** (`party.lua`): internal species and national dex both ways; `party.sync` rebuilds the shadow party in
  place (new members made on a scratch session, so the live Pokédex isn't touched; HP scaled to the engine's maximum;
  the battle's move overlay reset); gifts from the engine's `Party.giveMon` (the starter, `givemon`), their facts read
  when the giving script ends (so its nickname is in), at once while a battle is held (Birch's battle follows the
  starter); a box gift leaves the hidden box (Cobblemon's PC keeps it); Safari catches go out as gifts after their
  battle; heals (the nurse, a blackout's once its warp lands), the Pokédex, nicknames.
- **Bag** (`bag.lua`): Emerald's symbols in `protocol/items.json` (`symbols.gen3` on 101 items, among them the Sun Stone
  and TM01–TM50; 36 new TM entries by move, 14 TMs shared with Gen 1); counts, removals and `bag.set`'s projection into
  the pockets by class (99 a slot, all or nothing per 99, money clamped, key items and HMs untouched). A stone, Rare
  Candy or TM used on a party member is refused while a client follows ("It won't have any effect."): those are used
  in Minecraft. Java's ledger projects up to 128 kinds into a Gen 3 game (`LedgerRules.Limits.GEN3`).
- **Routes** (`routes.lua`): the server's `route.blackout` (half the money and the heal point, the engine's own).
  **Moved to MG8: the Wild Routes zone exit for Emerald** (E6, the hidden player parked on the exit cell): no Emerald
  zone exists until Hoenn's routes data (MG8), so nothing could use or test it now; `writeTrainerParty` and
  `scriptBattleResult` stay the stub's.
- **Core and protocol** (inert on Gen 1): the dex and move ceilings come from the running game's `compat`
  (`hello.ok.compat`) in battles, the party, the snapshot and the UI; defs `nationalDex` and `moveNum` (any number to
  2000) and `ivs` (0–31, beside Gen 1's `dvs`) in `battle.start`, `party.give`, `party.item`, `dex.sync`, the snapshot
  and `ui.open`; a trainer's `party` goes to 1023 (Emerald's trainer ids). **`dex.sync` now carries the first 386
  species and each bridge keeps the ones its game holds** (`Party.withinDex`, counted as `partyDexBeyond`): before, a
  Gen 1 bridge refused the whole message over one later species.
- **Java:** `Gen1Mon` carries national numbers and IVs (`Ivs`); `Gen1Mons` resolves any species and move by number
  (with `NationalDexIds` as the fallback); `ProxyClass` maps Gen 3's roles (`LEADER`, `AQUA_LEADER`, `MAGMA_LEADER`: gym
  skill; `ELITE_FOUR`, `CHAMPION`: elite; `RIVAL`); `PartyGive`, `PartyItem` (and the Sun Stone), `DexSync` and
  `DexService` take national numbers; **the evolution veto uses the followed region's ceiling** (an Emerald player's
  Eevee may become Espeon; Gen 1's 151 without an owner or a region).

**Check:**

| Suite | Result |
|---|---|
| pytest and Lua (local) | 1,952 passed, 10 skipped (new: `test_lua_game3_battle.py` 10, `_party.py` 9, `_items.py` 6 on `tools/lua/fake_battle3.lua`) |
| JUnit / GameTests (pod kb-dev11) | 1,225 (2 skipped) / 118, no failures (new GameTests: every Emerald number resolves; a Gen 3 gift keeps its IVs and the ceilings follow the region; a Gen 3 gym leader's battle in Cobblemon with Hoenn's Pokémon, IVs and moves) |
| e2e Emerald battles (`test_emerald.py`, scene `battle`) | passed on the real engine: a trainer battle held (`battle.start` kind `scripted`, a `TRAINER_CLASS_` role, the team valid), won: the prize paid (money 3,000 → 3,840) and its two texts read through the UI; a wild battle (dex 263, level 3, catch and flee) fled; a second trainer battle lost: the money halved exactly, the warp to the heal point, `party.heal` sent |
| e2e regression | Emerald: all of `test_emerald.py`, 5 passed (field, sign, story, battle, both title starts). Gen 1 on Blue: 14 passed (`test_battle` 2, `test_field` 5, `test_route1`, `test_ui`, `test_walk`, `test_opening`, `test_viridian` 3: among them the rival's battle, a Viridian Forest trainer won with its texts and money and another lost with the blackout to Viridian City) |

The driver's shortcut new game skips the intro, so the blackout's heal point is still the moving truck: in a real
game it is the player's house or the last Pokémon Center. **Two test fixes came with the regression:** the e2e drivers
`kb_battle`, `kb_route1`, `kb_opening`, `kb_perf`, `kb_ui` and `kb_walk` now apply the `KB_DATASET_MEMO` shim as
`kb_export` does (their tests fetch the export; on the pod's network disk that took about 4 minutes a launch, now
seconds), and `test_route1.py` expects MG6's bag rule (`bag.sync` carries every non-key catalogue item, so its field
item too; it hadn't run since MG6). A Gen 3 boot logs `export.live_copy_failed` (the core's Gen 1 dataset path, which
Gen 3's export doesn't use; since G3D).

**Not yet:** the route exit (MG8); in-game trades, the daycare and eggs (the engine changes the shadow party natively and
the next `party.sync` takes it back, as the research note warned); item uses on party members (refused, see above).

**G3G (2026-10-14): the real Hoenn in Minecraft.** The synthetic bundle, its goldens and the GameTests came with G3E
(`tools/fake_gen1/synth3.py`, `synthetic_g3_*`, `Gen3BuildGameTests`); G3G built the real Emerald export as region `hoenn`
on a pod (kb-dev12, new: cpu5g ×4, 16 GB, $0.184/hr; kb-dev5's and kb-dev6's hosts were full), with
`tools/e2e/build_world.py --skip-export --game emerald --maps all --world HoennE2E --fresh --region hoenn --slot 0`:

| Measure | The real Hoenn |
|---|---|
| Maps | 319 placed (49 surface, 270 interiors); the 200 unreachable ones left out |
| Blocks | 6,620,014 placed in 5,821 chunks; 52,987 story cells; 386 habitat blocks on 81 pools |
| Art | 18,358 textures in 18,506 slots (families 0–79) |
| Shape map | 1,682,080 characters: 26 parts of 64 KB on the wire (Kanto's budget is 8) |
| Time | about 9 minutes, 5 of them writing the pack's 18,360 files on the pod's network disk |
| Memory | a 4 GB heap is enough (peak 4.7 GB resident); with 8 GB the JVM grew to 5.5 GB |
| Repeat | the second build gave the same shape index hash, blocks and story cells |

**For MG8:**
- **The shape map is 3.2 times the voxel plan's join budget** (risk R10). It travels (`world.shapes` takes up to 65,536
  parts), but each joining client receives 1.7 MB of it. R10's mitigations (grids on demand per layer, no per-block ids
  for plain repeats) are MG8's to weigh.
- **Memory:** a single-player Hoenn build needs a 4 GB heap: a launcher's memory setting below that is likely too
  small.
- The 43 warnings are the known ones: 6 edge mismatches and 1 overlap (the three seams MG8 joins) and 36 interiors
  reached by special warps (no arrival cell).
- Tools: `build_world.py --heap` (the server's heap, `-Pkb.serverHeap`); its gates know a Gen 3 build: the three known
  warning codes pass until MG8, any other warning fails, and the shape map's budget is 32 parts.

**Check (MG7's last checkpoint, pod kb-dev12):** pytest and Lua 1,954 passed (local); JUnit 1,225 (2 skipped) and
GameTests 118, no failures; the Hoenn build passed its gates (`build OK`).

### Several games: MG8, Hoenn and the pass (HOENN_PASS_PLAN.md)

**The plan** ([specs/HOENN_PASS_PLAN.md](specs/HOENN_PASS_PLAN.md), 2026-10-14): MG5 had built the crossing machinery
and MG7 Hoenn itself; what was missing was the Hoenn half of a route, since Gen 3 takes no route map, map script or
patched exit (spike S2). Decisions K1–K8, phases HP1–HP8.

**The test pass (K1).** You asked for temporary exits in "a corner of the first or second town". A one-off finder ran
the clearing rules over both exports on a pod (reachable on foot, at least six cells from any object, warp, sign or
door): Pallet Town has no spot that fits, so **Kanto's end is Viridian City (6, 4), north through two blocks of trees**
(its north-west corner), and **Hoenn's is Littleroot Town (18, 14), south** (its south-east corner, a side with no
route connection). `routes.json` (v2) holds them as `kanto_hoenn_test`, "TEST PASS", beside the Viridian zone; F7 and
an edit move them.

**Built (commit `622940c`):**
- **Core hooks, inert on Gen 1** (raw lookups of the adapter's functions): `kb_routes` hands an end to the adapter
  (`routeInstall`); `world.stepped` and `map.entered` reach it; main merges its route events into the step's;
  `kb_move` lets it take a step on its route map; `kb_region` resumes onto its route map through it; `kb_route` runs
  give and battle through it; the Gen 3 export carries the `routes` resource.
- **The game3 `routes` part:** the clearing planned on the town's own cells (`Routes.plan`'s arithmetic over 2×2
  blocks of metatiles), opened on every load of the town with the exit's metatiles and elevation, baked into the
  export, counted as the map's own by the story part; the hidden player parked on the trigger cell and **reported as
  the route map at x1**, so the core and the client follow the half as on Gen 1; the step onto A is the way back (a
  warp to the clearing's back cell, facing the town); a resume onto the route map warps to the trigger cell and parks.
  **A real-engine finding:** a warp within one map emits no `map.entered` but still clears the cell overrides
  (`map.lua:776-786`), so the part checks each step that the clearing is open and that its own warp has landed, and
  tells the core of the arrival itself (both are script warps, as on Gen 1). Zone acts: an item into the bag with a
  found line; a battle with the class's first trainer as the template and the request's party, held for Cobblemon
  (an abort answers too).
- **Builder:** Gen 3 zone content (pools from the town's neighbours' land slots, named by the names resource; trainer
  sprites by role, else the trainers' graphic; the game's items); a zone's wild pools name species by national number.
  The synthetic Gen 3 export gained a pass variant (`synthetic_pass/`) for the tests.
- **Tools:** `build_world.py --game` exports any imported game (Emerald beside Blue); the Emerald driver's `route`
  scene.

**Check:**

| Suite | Result |
|---|---|
| pytest and Lua (local) | 1,978 passed (`test_lua_game3_routes.py` 18: the plan and every refusal, the clearing on each load, the park and the route map's facts, the way back, the resume, give and battle, main's walk in and out, the export) |
| JUnit / GameTests (pod kb-dev11) | 1,230 (2 skipped) / 119, no failures (new: `Gen3ZoneTest` 5; `hoennsHalfOfThePassIsBuilt`: the synthetic Hoenn's half with its pass to Kanto and its gate house) |
| e2e Emerald | `test_emerald.py` 6 passed, among them the new **route scene**: five steps through Littleroot's clearing, a script warp onto `KB_ROUTE_KANTO_HOENN_TEST_TO` at x1, a step right refused, a step left home to (18, 18) facing up, a park and a resume landing on the route map again; `test_emerald_export.py`: the `routes` resource names the end, Littleroot's clearing baked in open |
| e2e Gen 1 | `test_wild_routes.py` (no route skipped: the pass's Kanto end installs on the real Blue), `test_regions`, `test_route1`, `test_viridian` 3, `test_battle` 2, `test_walk`: all passed |
| **Two real engines** | `test_crossing_real.py` passed in 41 s: Blue and Emerald side by side (Emerald from a second data home, as spike S1), a fake client holding a link to each and doing what the client and the server do at a crossing. **20 crossings each way** (MULTI_GAME_PLAN §6 check 4): each time the game left parked on its route map, the other resumed onto its own at x1 (the cell it was parked on), and the ledger's projection (money and POTIONs, `bag.set`) landed and came back in `bag.sync`: 40 of 40; home through A on both sides; no error, no schema problem, no warp ended by the observer's timeout. Both hidden processes together: 620 MB at most. **MG5's open gap, a crossing between two real engines, is closed** |
| The world (pod) | One world, `PassE2E`: Kanto (Blue, slot 0) 220 maps in 261 s, no warnings, with the Viridian zone and the pass's Kanto half (4 trainers, 4 balls, its gate house, the pass to Hoenn); Hoenn (Emerald, slot 1) 319 maps in 524 s with a 4 GB heap, the 43 known warnings (G3G), and the pass's Hoenn half: 4 trainers of 3 Emerald classes (10 Pokémon, levels 3–9, from Littleroot's neighbours), 4 balls from the item tiers, its gate house and the pass to Kanto |

**HP6, server and client:** a Hoenn half needs nothing new on either side: the zone service sends the index's class
and species names as they are, the client's ball sends its item as it is, and the game3 part prefixes them
(`TRAINER_CLASS_`, `SPECIES_`, `ITEM_`). The item tiers (`data/kantobridge/wild_routes/items.json`) gained Gen 3's
`PARALYZE_HEAL` (Gen 1 spells it `PARLYZ_HEAL`; a game keeps only the names it has, so Kanto's zones are unchanged).
Checked by the Hoenn half's GameTest: its trainers' classes, its balls' items, and a trainer's `route.battle` to a
player in front of it (Emerald class and species names).

**Budgets per region (MULTI_GAME_PLAN §6 check 5):**

| Budget | Kanto | Hoenn |
|---|---|---|
| Shape map on the wire (524,288 characters) | 319,136 (61%) | **1,682,040 (321%, over)**: sent in 26 parts, the Gen 3 gate allows 32 (G3G); voxel plan R10 is the fix (a region's shape map sent by part on demand) |
| Build | 220 maps, 261 s | 319 maps, 524 s, 4 GB heap (8.2 M blocks) |
| Two hidden processes (S1: about 600 MB) | 620 MB at most together during the crossings, both with gen1-bridge | |
| Block-state memory, 32 families | measured at MG3 (D9), unchanged | |

**What is left after MG8 is one checklist: [SEVERAL_GAMES_FOLLOWUPS.md](SEVERAL_GAMES_FOLLOWUPS.md).**

**Carried over, per map (the user's "each ROM requires its own tweaks"):**
- Hoenn's seams: Dewford Town/Route 107, Fallarbor Town/Route 114, Verdanturf Town/Route 116 (edge mismatches) and
  one overlap; 36 interiors with no arrival cell (G3G's warnings).
- Unplaced Hoenn maps: Sootopolis, the Battle Frontier, the ferry's islands (no warp reaches them in the engine's data).
- Hoenn's shape map size (above).
- Emerald's trades, daycare and eggs are undone by the next party sync; people and the surf blob are placeholders;
  animated tiles and darkness are not mirrored.
- The test pass's exits are temporary (the user's choice, K1); F7 and `routes.json` move them.

**The story video (the user's request, 2026-10-14):** one take in the real dev client: a new game in Hoenn up to
the starter, out through the test pass into Kanto with a new game there, Kanto's starter, and Bill's PC in Viridian
City holding the Hoenn starter. The client is steered by `client/dev/RemotePilot.java` (scene `remote`: commands in
`local/reports/story/commands.jsonl`, its state in `state.json`; a step is a walk to the next cell's middle, a button
press goes into the open screen or the world, names are typed on the grid, Cobblemon battles take the first move with
PP left, a Wild Routes half is walked to its pass in stages as its chunks load), and `tools/e2e/story_video.py` plays
the story through it (walks planned on the export's cells; `--scene zone` starts at Littleroot's clearing for a quick
test of the second half). Filming found and fixed:
- **Emerald's moving truck was never built** (no warp leads in: its door is a dynamic warp). The Gen 3 export now names
  the room a new game starts in (`g3layout.start`, the engine's `newGameStart`) and the builder places it under the
  root; maps only left by a warp are placed under the cell their warp lands on. Hoenn: 357 maps (was 319), the same 43
  known warnings.
- **A hidden battle's result went to the wrong game:** `battle.result` (and `pc.closed`) reached the client untagged, so
  the client handed it to the legacy region's link (Blue) and Emerald waited for ever after Birch's battle. Both now
  carry the region their `battle.start` / `pc.open` came with (`BattleRegionGameTests`).
- A fresh player follows the legacy region's link once it connects; the pilot follows Emerald first, as a player
  choosing to start in Hoenn would.
- The client can't confirm an empty nickname (the hidden game keeps the species name, but `ui.open` sends no default):
  the pilot types the species' name. A gap for the user's play-test.

**After MG8: the user's items 5 and 6** ([SEVERAL_GAMES_FOLLOWUPS.md](SEVERAL_GAMES_FOLLOWUPS.md), 2026-10-14):
- **Item 6, every map built:** the Gen 3 export wrote a warp's landing 1-based (the engine keeps `destWarp = warp_id + 1`,
  `src/import/gba/extract_map_events.lua:313`) where PROTOCOL.md says 0-based, so every Hoenn interior sat one warp off
  under its door and 36 fell off their target's list; it is 0-based now. The builder also places every map a player can
  be put in by a script, a ferry or a dynamic warp (kind `script`, under the root, each group with its warps; the
  decomp's UNUSED and PROTOTYPE maps never). Hoenn: 508 of 519 maps (was 357), warnings 7 (were 43: only the seams
  below), 463 s; the shape map grew to 2,080,948 characters, 32 parts (item 4).
- **Item 5, the seams, checked rather than rebuilt:** Hoenn's connections disagree by two cells between its west (Route
  104, Rustboro City, Dewford Town, Routes 114–116) and the rest, so one side of four joins breaks whatever the layout:
  Petalburg City / Route 104 (a path two cells wide, which a two-cell shift closes), or Dewford Town / Route 107, Fallarbor
  Town / Route 114 and Route 116 / Verdanturf Town (wide ones, and a join nothing crosses). The engine's layout already
  breaks the second group. `tools/e2e/seam_check.py` (drivers/kb_emerald.lua's new `spot` scene, the real client)
  walked across both crossable seams: the client lands the player two cells off and walks them onto the hidden cell
  within two seconds (a forced move; no fade, no teleport). PLAYTEST.md's "walls or overlaps" note was wrong: it is now
  an item to try.

**After MG8: the user's item 11, the ten-second stalls** (2026-10-14): Emerald keeps fading in after the
`map.entered` that ends a warp (and fades for cutscenes, the clock and a battle's end without any map change); gen1-bridge's
warp observer (`observe/kb_warp.lua`) started a `script` warp on that fade, which never arrived: the client faded out
and held the player for its ten-second arrival timeout, after almost every door (14 times in one story run; the bridge
logged `warp.no_arrival` 13 times). Now the transition that carried an arrival starts no warp until it has ended once
(`tails`), and a fade-started script warp whose fade ends without a map change arrives `Warp.SETTLE` (60) steps later
from the player's cell (`settled`); doors, Fly, teleports, cave shafts and blackouts are unchanged. Checks: lupa 3 new
(`test_lua_warp.py`); Emerald's door e2e now asserts one start and one arrival (it fails on the old observer:
`[start door, arrive door, start script]`); Emerald 6 and Gen 1 12 e2e passed; the story run in the real client: no
`warp.no_arrival`, no client `arrive_timeout`, about three minutes faster.

**After MG8: the user's item 7, Emerald's trades, daycare and eggs** (2026-10-15): the engine changed the shadow
party itself and the next `party.sync` took it back. The Gen 3 party part (`lib/engine/game3/party.lua`) now does what
gen1.lua's party operations do, with Gen 3's own seams:
- **Trades:** `Trade.tradeMons` (the in-game trade's swap) is wrapped: the member sent is `party.take{reason:"trade"}`
  by its uid, the one received a gift with source `trade` (nickname and OT, read once the trade's script has ended). A
  sync waits while a trade is armed or its scene is open. The trade's animation is not mirrored (it is no stack layer;
  the client stays on the world for those seconds).
- **The daycare (two):** watched every step as Gen 1's: a member with a uid it holds is `daycare.deposit`, one back in
  the party `daycare.withdraw` with the steps `Daycare.withdraw` was given (wrapped: the first e2e run showed that a
  per-step look misses steps that come faster than it looks). The boarded uids are kept in mod.save with each member's
  personality value (`{ boarded = { { uid, pid } } }`, the adapter's own record, `ev.store` to kb_party), so CONTINUE
  matches them again; `save.loaded` gained `boarded` (every uid when it holds more than one; the server's reconcile keeps
  them all).
- **Eggs:** Cobblemon has none, so they never cross: a gift egg (`Party.giveEgg` gives it through `giveMon`) is dropped
  from the gifts, every sync keeps the party's eggs after Cobblemon's members, an egg with no room waits in a hidden box
  and comes back once there is room, and hatching (`Breeding.hatchMon`, wrapped) is a gift with the new source `egg`,
  sent once the hatch screen and its nickname prompt are done. The hatch screen reaches the client as a mirror (as the
  evolution scene does): A on it answers YES/NO, the naming screen is the client's own.
- Checks: lupa 5 new (`test_lua_game3_party.py`: trades, the daycare with CONTINUE, eggs with parking and hatching,
  the sync's waits, main.lua's messages schema-checked), the full lupa suite 1318 passed; GameTests 2 new
  (`aHatchedEggArrivesAsThePlayersOwn`, `bothOfAGen3DaycaresPokemonStayBoarded`), 122 passed; the real Emerald
  (`test_emerald.py`'s new party scene, the test playing Cobblemon): a trade, a stale sync that changes nothing, two
  boarded, `save.loaded` after a bind and after a save and CONTINUE naming both, a withdrawal with its 10 steps, a gift
  egg kept by a sync, then hatched by one real step (its "Huh?", the mirror, YES, the name KB) into
  `party.give{source:"egg", nick:"KB"}`.

**After MG8: the user's item 4, the shape map's size** (2026-10-15; the voxel plan's R10): measured first (counts and
sizes only, on the pod's PassE2E world). Hoenn's shape map is 11,092,268 bytes, 2,080,948 characters on the wire (31.75
parts): 460 grids, the surface one 1,624 × 792 columns (81% empty, 67,170 distinct stacks, 634 KB deflated, 40%), the
interiors the rest (409 of them up to 64 × 64 make 469 KB). Other encodings of the same content gain little under
deflate (varint columns 9%, byte planes 13%, 2 × 2 patterns and 16 × 16 tile dictionaries 0-6%; xz would halve it but
Java has no xz). The bigger problem was the **shape index**: 8,851,374 characters (135 parts) sent as plain text at
every join. So every join sent about 12.4 million characters of shapes for the two regions (Kanto: index 1,150,960,
map about 319,000), and the build's Gen 3 gate (32 parts) was full.
- **The fix: shapes on demand.** On join (and on publish) the server sends each region's `world.shapes.offer` (each
  document by the SHA-256 of what it holds, and its wire size) in place of the parts; the client takes what it can from
  its cache (`<gameDir>/kantobridge/shapes/docs/`, the deflated documents by hash, checked when read back, at most 64 MB,
  least recently used out first) and asks for the rest with one `world.shapes.get`; the server sends those parts (the
  index deflated too, `enc:"deflate"`), once per 5 s per document. Parts must hold what the offer named. A server from
  before still pushes; the client still takes that.
- **Budgets:** the build gates now check a region's first download per document (map and index, 64 parts each, the same
  for every game: `shape_map_fits`/`shape_index_fits`, `region_shape_map_fits`/`region_shape_index_fits`; the report
  gains `shape_index_wire_chars` and the region's `shape_index_chars`). They replace the join-time 8 parts (Kanto) and
  32 (Gen 3).
- **Measured in the real client** (`tools/e2e/shapes_cache_check.py`, kb-dev16, PassE2E): the first join, cache empty,
  asked for both documents of both regions (Kanto 3 + 5 parts, Hoenn 20 + 32: 3.85 million characters) and had both
  shape tables ready about 10 s after joining; the second join took all four from the cache (2.9 MB on disk), asked for
  nothing and downloaded no shape part. Kanto's table 1.2 s, Hoenn's 4.8-5.2 s (from the table cache).
- **Left:** each join still sends every region's world index (Hoenn 45 parts, Kanto 12) and the slot map (36 parts) as
  plain text; the same treatment would apply to them.
- Checks: JUnit 1,241 (2 skipped, new `ShapeDocsTest`, `ShapeDocCacheTest`, an offer test in `ClientRegionsTest`),
  GameTests 122 (the multiplayer and region join tests now see offers and ask), tools 682 passed.

**After MG8: the user's item 8, Hoenn's people, the surf blob, animated tiles and darkness** (2026-10-15): measured
first on the real Emerald (counts only): 7 of the 519 maps are caves that need Flash (the header's cave flag: Granite
Cave B1F and B2F, Victory Road B1F and B2F, and three unused Cave of Origin maps); 69 of the 75 pairs have tile
animations (202 tile banks over 6,465 metatiles' under planes and 39 over planes, 1,711 partial quarter masks, 1,115
metatiles in several banks; periods of 2 to 64 GBA frames, 2 to 16 frames, two counters of up to 256) and one pair a
palette bank; the surf blob is a field effect (32 × 32, three frames), not an object event.
- **People and objects:** every `g3sprite` sheet goes into the pack in the generated layout (four facings, standing
  and walking) under the one palette `baked`, in square cells of 32 pixels (or the frame's larger side), so a 16 × 32
  person draws one block wide and two tall, the height of Kanto's people. Walkers (9 frames or more) stand on frames
  0-2 and walk on 3, 5 and 7; a sheet of three faces; any other count keeps frame 0, never mirrored (an item ball; a
  tree or rock whose other frames are its cut or smash). The export names every graphics id as the observer names a
  puppet's sprite. The client is unchanged (`SpriteFrames` takes any multiple of 64 × 32).
- **The surf blob:** the export's `SURF_BLOB` sheet (no `gid`; each facing's first frame of the field effect) becomes
  `player/<set>/baked/surf.png`, drawn as Kanto's Seel is.
- **Animated tiles:** the export runs each pair's banks as the engine's `TilesetAnim.stepRse` does (each counter's
  length, a bank's phase, `rseFrame`, a paste only when the frame changed, so a layer shows the bank that pasted last),
  over the counters' least common multiple from 0, twice so what the wrap carries is in place; each look is flattened
  as a still quarter is (the pieces through the pair's colour tables: under opaque, over with index 0 clear). A
  quarter that changes is a quarter of its own (never shared with a static one; its page holds its first look), listed
  in the new `g3anims/all` with its frames (ordinary quarters) and loop; quarters alike in both share one. The pack
  writes it as a strip with an `.mcmeta` in ticks: a step ends on the tick nearest its GBA time (one too short for a
  tick is left out), and a loop that isn't a whole number of ticks runs three times, so loops of different lengths keep
  in step.
- **Darkness:** `g3map.dark` (the cave flag), so the builder places those caves without lights; the adapter's
  `darkMaps()` lists them and `saveView.flashLit` and the facts' `flashLit` follow `FLAG_SYS_USE_FLASH`, so Flash gives
  Night Vision there as in Kanto (`story.lit`, FlashService).
- **Measured** (kb-dev17, PassE2E's Hoenn rebuilt): the export 871 resources, 4.67 MB in 31 s; 15,231 animated quarters
  in the pairs' metatiles are 935 animations (2 to 11 pictures, loops of 256 or 128 GBA frames). The build: 508 maps, 7
  warnings (the seams, as before), 18,691 textures (18,451 tiles, 504 of them animated; 240 sheets with the surf
  blob), slots 18,609 of the region's 20,480 (about 100 more than before), shape map 2,081,068 characters, index
  1,269,104. `test_emerald_export.py` (the real export, its new checks) passed.
- **Left out:** the palette bank (one pair: its colour cycle stays on the first colours); berry trees show their first
  look; a walker uses one step frame a facing (the puppet layout has one); big sprites fit the same 2 × 2-block card;
  the GBA's Flash circle (the light is all or nothing, as in Kanto).
- Also: `gen1-bridge/lib/kb_catalog.lua` was stale since item 4 (the two `world.shapes.*` messages; the bridge never
  sends them); regenerated.
- Checks: lupa and tools 2,005 passed (new: the export's animated quarters with the engine's timing, the surf blob,
  sprite names, dark caves; the adapter's dark maps and Flash; the synthetic export's animated water and sheets),
  JUnit 1,248 (2 skipped; new `BakedTexturesTest`, the Gen 3 pack's strips, sheets and dark caves in `Gen3PlanTest`,
  the readers), GameTests 122.

**After MG8: the user's item 9, an empty nickname** (2026-10-15): the client's naming screen falls back to the
default (`ui.open.value`), then the first preset, and with neither (every nickname) it sent nothing, so a nickname
couldn't be left empty. Both engines take an empty name as their own: Gen1Recomp's `NamingScreen:confirm` gives the
screen's default, else `""` (no nickname: the species' name stays and an evolution renames it; the Name Rater keeps
the old one through its default), and re-prompts where there are presets (the player's and rival's names); Emerald's
naming OK gives the screen's seed (the species' name for a nickname, which the caller keeps as no nickname; the
player's name keeps the one Birch's speech picked). So the fix lets the empty name through rather than sending the
species' name as a typed nickname (which would freeze it through evolutions):
- `ui.name.glyphs` may be empty (0-10); gen1-bridge sets no glyphs and runs the screen's own confirm (START on Gen 1,
  OK on Gen 3) and refuses an empty name where the screen has presets (`bad_message`, as the engine would re-prompt).
- The client sends it empty when nothing is typed and there is neither default nor preset.
- Checks: lupa and tools 2,009 passed (an empty nickname confirmed as the engine's own, a screen's default winning,
  refused with presets; Emerald's empty name finishing as the seed; the Gen 1 fake now re-prompts as v0.3.85 does;
  the redaction vectors' new example), JUnit 1,249 (2 skipped; the model and the router send `glyphs: []`),
  GameTests 122 (on a second run: on a cold pod with JUnit alongside, `twoRegionsShareOneWorld` once failed with
  "build still running" five seconds into its batch, unrelated to this change, which touches no server code); the
  real Blue (`test_battle.py`, both passed; the lose run: YES to the starter's nickname, the naming screen confirmed
  empty, the gift arrives with no nickname). Emerald's empty name was checked against its code and the fake, not in
  the real game.

### Johto (MG9): the inspection G2S (2026-10-15)

The first phase of [specs/GEN2_BRIDGE_PLAN.md](specs/GEN2_BRIDGE_PLAN.md) (§2), at your "Go" (2026-10-15): the questions
the engine's source could not settle, asked of the real Crystal import on the volume. All nine spikes ran; what they
changed in the plan is in its §2.1 ("What the inspection settled").

**How.** As S2 for Emerald: a throwaway probe mod `kb-g2-probe` (`games: ["crystal"]`, `engine_internals`) and a test
driver with five scenes (boot, walk, story, extra, measure) through `tools/e2e/run_gen1recomp.py` with `game="crystal"`
on the v0.3.85 data home, the clock pinned (`POKEPORT_GOLD_HOUR=12`); only `KB_*` lines pass the runner's filter. The
probe was copied into `mods/` for each run and removed after. The boot scene saved once, on a throwaway slot it created
and deleted (`saves/crystal/` empty before and after); the other scenes never saved. A meter (S1's, with a Gen 2 branch)
measured memory and frame rates. Pod: **kb-dev19** (new, `rv19mhmouh340x`: cpu5c ×4 / 8 GB, $0.14/hr, EU-RO-1, the same
volume, 22/tcp), because kb-dev16/17/18 had no free memory, kb-dev9/10/12 no free vCPU, and cpu3g ×4 had no stock; about
21 minutes (about $0.05), stopped after. The spike files stay out of the repo, on the agent's server
(`~/kb-runpod/spike-g2/`: `kb-g2-probe/`, `kb_g2_driver.lua`, `run_g2.py --scene <name>`, `kb_g2_meter.lua`,
`run_g2_meter.py`).

| Spike | Result |
|---|---|
| G2-S1 boot | The probe loads with `games: ["crystal"]` (engine `crystal`, generation 2; `mod.world`, `mod.input`, `mod.battle` tables). Every Gen 2 module a gen2 adapter needs is `require`-able; a `src.core.game3.*` module is denied. With `POKEPORT_BOOT_CINEMA=1`: copyright → Crystal splash → intro → title → main menu (NEW GAME, OPTION, EXIT without a save). `game:newGame()` called at the main menu runs Oak's speech; the `intro.oak_speech.build` hook's `fn` steps set the clock and the name (`KB`) with no screen; Crystal's gender choice comes up as `Gen2GenderSelect`; the field starts at `PLAYERS_HOUSE_2F` (3, 3) facing down, `map.entered` via `boot`. `save.created` fires once for the new game (once more at boot for the skeleton) and carries `game.save`. `game:writeSave()` (allowed by `quickSaveAllowed()`) then `game:continueGame(Save.load("crystal"))` lands on the saved cell (`via` `continue`), and `save.loaded` carries `game.save`. **Union Room OFF**: no `UNION_ROOM` map, `POKECENTER_2F` keeps its width (8 blocks), `UnionCenter2F.isApplied` false |
| G2-S2 walking and warps | A tapped direction neither turns nor moves; a hold of **one** tick already turns (no step); 8 ticks turn and step; a held direction chains steps 16 frames apart with no standing tick. `movement.collision` fires for allowed steps too and for NPC movers (205 NPC calls in the story run): filter the player; a wall is refused as `tile`, an NPC as `entity`. A ledge (Route 29: codes 0xa0, 0xa1, 0xa3 and the diagonals 0xa4, 0xa5) is first a refused `tile`, then a hop; one `world.stepped` for the two cells. Walked warps (stairs, doors) raise `warp.destination` and `player.warped`; `world.setupMethod` is 0xF5 (DOOR) for them and 0xF1 (WARP) for `warpTo`, `queueScript` warps and a whiteout. A same-map `warpTo` reloads the map (`map.exited` and `map.entered`). The seam west of New Bark Town gives `via` `connection` |
| G2-S3 world edits | `maps:patch` of New Bark Town's `blocks` is what the world loads (the changed block is live). **`maps:register` of a new map works**: a copy of `PLAYERS_HOUSE_1F` as `KB_TEST_MAP` is entered by `warpTo` and walked; a door's `warp.destination` redirected into it works. `mod.world:replaceBlock(bx, by, block)` opens a wall block at once (`world.block_replaced`, an entry in `blockEdits`; the player steps in), and a reload restores the original. `warpTo` works direct and from `input.step`; `queueScript{{"warp", …}}` works |
| G2-S4 story | Elm's starter: the `givepoke` op's decoded row (the hook's fifth argument) gives species **155** (the national dex number) and level 5; the ball's script sets flags **26** (Gold's `EVENT_GOT_A_POKEMON_FROM_ELM`: the same number on Crystal), 27 and 1600. A trainer won sets its own flag (1450 on Route 30); an aborted battle sets none. A new game sets 126 flags in one burst. Map callbacks replace blocks on load (`world.block_replaced` 4–5 times a run) |
| G2-S5 UI | Oak's lines, Elm's talk, the starter's script and an NPC's line are shared TextBoxes (the starter's script: 14 boxes, 52 pages, 3 yes/no), yes/no the shared ChoiceBox; Elm's starter picture waits outside the stack (`world.waitButton`) and A goes on. `render.output` (enabled by the hook) gives the window canvas and a 160×144 game rectangle × scale (800×720 at 5), generation 2. No phone call rang in about five minutes of play |
| G2-S6 battles and gifts | **The hold works** (a wrapper on `World.startBattle`, `pushBattleTransition` answering false, a placeholder for `Gen2BattleState`'s `new`): two wild battles (run, win) and three trainers. The engine's own `onDone` ran the rest each time (field idle, `battleActive` cleared). A trainer win with `battle:awardPrizeMoney()` paid 64 and set the trainer's flag; talking again started no battle. `onDone(nil)` aborted the script (no flag) and the trainer battled again on the next talk. A held loss whited out: `world.blacked_out`, money halved (3,000 → 1,500), the spawn at home. `battle.started` fires once per battle (the model's; the screen's never runs under the placeholder), `battle.ended` only when the adapter calls the model's `endBattle`. `encounter.roll` is raised only when an encounter happens (kind `wild`, terrain `grass`, daytime `DAY`). The nurse's `special` is named `HealParty` through the hook's fifth argument |
| G2-S7 parking | Gen 1's hold state pushed on the stack freezes the field: 0 `World.step` calls in 240 frames (60 a second free), no NPC moved (one did while free), `input.step` still runs; popping it resumes. `Music.fadeOut`/`restoreMap` exist |
| G2-S8 the measure | 388 maps (INDOOR 208, ROUTE 54, TOWN 23, CAVE 39, DUNGEON 39, GATE 25; palettes: DAY 270, AUTO 78, NITE 27, DARK 13); 1,312 warps, 6 remembered (0xff); 142 connections. **Two surface areas, not joined**: 31 maps from New Bark Town, 35 from Pallet Town. **Baked day art: 3,526 distinct tiles over the blocks the maps use (14 families), 4,856 over every block of every art variant (19)**; with night 4,221, all three times 4,921; 298 art variants, 31 maps with roofs, the sheets exactly four shades; the count took 3.5 s. 40 tilesets, 36 animated. Collision codes on placed cells include ones the plan's table lacked: 0x21 (32 cells), 0x27 (838) and the one-way 0xb2 (2,343), 0xb0, 0xb1; also ice 0x23 (779), whirlpool 0x24 (6), waterfall 0x33 (164), cut tree 0x12 (40), headbutt tree 0x15 (1,056), long grass 0x14 (528). 1,466 objects (227 sprite/palette pairs, 2 big, 27 variable, 333 trainers, 178 item balls). Wild: 91 grass maps, 62 water, 13 fishing groups, 34 headbutt maps, 4 Rock Smash; 119 species over the grass tables (89 morning, 87 day, 62 night). Swap sites: 884 cut blocks (trees and grass), 6 whirlpools, 232 `changeblock` rows. The dataset view reads `maps` (388) cleanly, unlike Emerald's |
| G2-S9 resources | Crystal alone about 205 MB RSS at 60 frames and 60 logic steps a second; beside Blue (about 175 MB) both at 60; Blue, Emerald and Crystal together about 800 MB, all at 60 once Emerald had loaded (software GL, 4 vCPU) |

**Not checked here** (left to the phases' e2e): a phone call (none rang), the Cherrygrove rival (`CANLOSE`), roamers, a
surf landing, Fly and Escape Rope, the catch tutorial, typed and empty names, Mom's scene downstairs (driver mode's
skeleton game skips it), the caller box, a baked tile against the engine's own frame (G2D), the map ids equal to Gen 1's
(76 by the upstream name tables; G2A counts them live).

### Johto (MG9): G2A notes (2026-10-15)

Crystal is followable (GEN2_BRIDGE_PLAN.md §3–§5, phase G2A), at your "Go" after the inspection.

- **The adapter**: `gen1-bridge/lib/engine/gen2.lua` for both engine ids (`Engine.ADAPTERS.gs` and `.crystal`), grown
  from the stub, with parts `gen2/base`, `boot`, `observe`, `move`, `saves`, `region`. The engine-neutral parts moved
  out of `game3/` into `lib/engine/common/` (`slots`, `savequeue`, `reliability`) and serve both adapters (J1); game3's
  parts are now listed by path (`Game3.PARTS`), its base gained `saveObject`. Emerald's tests were unchanged by the move
  but for the parts list.
- **Map ids (J4)**: every Gen 2 map id leaves the adapter as `G2_<id>` (`b.ext`; our own `KB_*` maps keep their names)
  and comes back without it (`b.int`). The core takes engine payloads through `engine.mapOf` (the `map.entered` record,
  the current map the routes' closed row reads) and hands its acts a **world facade** (`engine.worldFacade(mod.world)`:
  `warpTo`, `queueScript` warp rows and `current` translated; Gen 1 and 3 keep `mod.world`).
- **Core changes, inert on Gen 1 and 3**: `script.command`'s fifth argument (Gen 2's decoded op, which its own command
  needs back) and `encounter.fishing`'s context are passed through, the stub included; the adapter gets `mod.hooks`
  (Gen 2's clock answer goes through `intro.oak_speech.build`); the snapshot keeps up to 16 badges (schema too).
- **Catalogue**: the manifest's games gain `crystal`; `protocol/games.json` switches Crystal's adapter on, markers
  `starter: E26`, `champion: KB_HALL_OF_FAME` (Gold and Silver stay off, J2).
- **Found by the real engine** (the e2e on the pod): the World starts `pendingSceneScript` as `false`, not nil, so
  "not nil" made every field look scripted and the mover refused every step with `script`; the observer now counts
  only set values. And a held direction keeps `turningDirection` set on ordinary steps (found by reading, before the
  run): forced movement is the engine's own test (ice under a latched direction, a current or door push, the Cycling
  Road's roll). The fake engine now starts the field the engine's way.
- **Checks**: local pytest and Lua 2,099 passed (62 e2e skipped), 76 of them the gen2 tests on the new fake
  (`tools/lua/fake_engine2.lua`, `adapter2.py`, `test_lua_gen2_{boot,observe,move,saves,region,protocol}.py`). On the pod
  (kb-dev16, cpu3g ×4, the real Crystal): `tools/e2e/test_crystal.py` 2 passed (followed and driven: hello.ok family
  gen2 with 251/251, the snapshot in `G2_PLAYERS_HOUSE_2F`, steps and a wall's `tile`, a save through the bridge, the
  stairs warp into `G2_PLAYERS_HOUSE_1F`, park and resume; started from the title: CONTINUE, and NEW GAME through Oak's
  speech with the clock answered by the adapter and the rest by the driver until G2B); the regression on Blue and
  Emerald (`test_emerald.py`, `test_regions.py`, `test_handshake.py`, `test_s5_ready.py`) 12 passed. The checkpoint ✔
  (`tools/check.sh` on the pod): pytest and Lua 2,099 passed, JUnit 1,249 (2 skipped, 0 failures), GameTests 122 passed
  on the third run; the first two each lost one region build to "build still running" (Hoenn's half of the pass, then
  two regions in one world: the known timing flake of the build GameTests on a slow 4-vCPU pod; nothing G2A changed is
  in a build). Pod kb-dev16 about 24 minutes (about $0.06), stopped; kb-dev19 and the new cpu5g/cpu5c creates had no room.
- **Still the stub's** (later phases): the UI (G2B: until then a client gets no Gen 2 screens; the e2e driver answers
  Oak's speech), the story (G2C), the export (G2D: `export.live_copy_failed` is logged once at load), battles (G2F),
  routes (G2H).

### Johto (MG9): G2H notes (2026-10-15)

The pass between Kanto and Johto (GEN2_BRIDGE_PLAN.md §5.11, §8, phase G2H), the plan's last phase, in sequence after
G2G as you asked.

- **Where the pass's ends are** (K1's rules: reachable on foot, at least six cells from any object, warp, sign or
  door; a finder ran gen1-bridge's own planners over the real games, `tools/e2e/spot_finder.py`): New Bark Town has no
  spot that fits (its best is 3 cells from its scripted events), so, as Pallet Town for Hoenn's pass, Johto's second
  town takes it: **Cherrygrove City (12, 4), west through two blocks of trees**. **Kanto's end is Viridian City (35, 3),
  east through two blocks to the edge** (its north-east corner; Hoenn's pass keeps the north-west, the Viridian zone
  the south-east). `routes.json` holds them as `kanto_johto_test`, "TEST PASS"; F7 and an edit move them.
- **Crystal's route end** (`gen1-bridge/lib/engine/gen2/routes.lua`): unlike Emerald, Crystal can take a route map of
  our own (spike G2-S3), so its end is Gen 1's shape installed by the adapter in the entry chunk: the clearing planned
  on Cherrygrove's blocks, the route map registered (the town's record with one block of floor) and the town's blocks
  patched; the way out and back are the engine's own warps on `world.stepped`; a zone's item goes into the bag with a
  found line; a zone trainer is the class's first trainer with the zone's party, fought in Cobblemon; a battle lost in
  Cobblemon whites out as the game does (Crystal had no `route.blackout` until now).
- **The Johto half** (Java): `ZoneContent.ofGen2` (the town's neighbour pool from the daytime grass slots by name,
  sprites by class, the ball's, the game's items); built into the three-region world: 1 zone, 4 trainers, 5 balls, its
  pass and gate house. The synthetic Crystal export gained a pass variant (`synthetic_pass/crystal`), with a JUnit test
  and a GameTest of the half (its challenge carries Crystal's class and species names).
- **Found on the real engines and fixed**: a zone act on a pass's far end answered `unknown_route` (the core keys that
  end `<id>:to`; Hoenn's end had the same gap, never exercised before); an adapter's own act counted as stale while its
  line waited for your press; Crystal's export listed our route map as one of its maps.
- **Checks on the pod** (kb-dev25): the Crystal route e2e (into the route map through Cherrygrove's clearing, an item
  given with its line, a Youngster with the zone's party held for Cobblemon and won, the lower row closed, home through
  A, a park and a resume onto the route map) and the crossing between the real Blue and Crystal, **20 crossings each
  way**, every ledger projection (money and POTIONs) landed and read back (40 of 40), the far side home through A and
  back, both processes together 445 MB at most; no error, no schema problem. As regressions, the Gen 1 zone e2e (`test_wild_routes.py`), Emerald's pass end and the Blue ↔ Emerald crossing (20 each way) passed again. The checkpoint's full check: pytest 2,228 (new `test_lua_gen2_routes.py` 23), JUnit 1,286 (new `Gen2ZoneTest`), all 125 GameTests (new `johtosHalfOfThePassIsBuilt`; the two pass-half GameTests now allow 72,000 ticks: the test server's ticks run unthrottled while the builder plans a zone, 12,000 passed in 5 s on this pod). Both halves are built into `ThreeRegionsE2E`: Kanto (220 maps, no warnings, 3 zones: the Viridian zone and both passes' Kanto halves) and Johto (388 maps, its zone with 4 trainers, 5 balls and the pass), every build gate passing.
- **Pods**: every older pod's host was full and 4-vCPU stock was gone in the volume's data centre, so kb-dev25
  (90jwz6qj6f4xbp, cpu5m ×2, 16 GB, $0.13/hr) ran it: about 50 minutes, stopped.
- **Not made**: a video of the pass (the plan's "if you want one"): say so and I'll film it.

### Johto (MG9): G2G notes (2026-10-15)

The real Johto beside Kanto and Hoenn, and the join's documents on demand (GEN2_BRIDGE_PLAN.md §8, phase G2G), in
sequence after G2F as you asked.

- **The world** `ThreeRegionsE2E` (on the volume; a copy of PassE2E with Kanto at slot 0 and Hoenn at 1): Johto built
  at slot 2 from the pod's Crystal export: 388 maps, 4.36 million blocks, 3,825 textures, in 251 s with a 6 GB heap.
  Every build gate passes; its 3 warnings are the ones G2E found (Celadon City and Route 7's loop, an edge each way;
  one object off its map in Goldenrod's Pokémon Center), now Gen 2's known set in `build_world.py` (any other warning
  or any overlap still fails a Gen 2 build).
- **Indices and the slot map on demand** (the leftover of follow-up item 4): they were pushed whole on every join;
  now each region's WorldIndex and the world's slot map are offered by hash (`world.index.offer`, `world.slots.offer`)
  and sent deflated only to a client that lacks them (`world.index.get`, `world.slots.get`), which keeps them beside
  the shapes. Measured on the three-region world in the real client: every join used to send 7.5 million characters
  of indices and slot map (117 parts); the first join now downloads 626,000 of them (11 parts), a rejoin nothing, and
  all three shape tables came up both times (the rejoin took every document from the cache).
- **Budgets** (per region, on the wire): the index 81,416 (Kanto), 246,356 (Hoenn), 105,472 (Johto) characters
  deflated; Johto's shape index and map 414,040 and 648,980 (7 + 10 parts; Hoenn's 20 + 32 stay the largest); the
  slot map for all three 192,736. Each document's budget is 64 parts; the build report's region object gains
  `index_chars` for the index's.
- **Protocol**: four new types (`world.index.offer/get`, `world.slots.offer/get`), `enc` on `world.index` and
  `world.slots`. A client still takes a server's pushed parts from before; a client from before G2G gets no index
  from a G2G server, so a host and its players update together (as for item 4).
- **Checks**: local pytest and Lua 2,205 passed (the Gen 2 gate in `test_build_world.py`). On the pod (kb-dev24): the
  real client check above (`shapes_cache_check.py`, extended to the index and slots), and the full check: pytest 2,205, JUnit 1,281 (new tests for the offers, the cache's kinds, the report's `index_chars`), all 124 GameTests (the join sequences now offers, the documents asked for).
- **Pods**: kb-dev23 and kb-dev22's hosts were full; kb-dev24 (cnax963rwilzko, cpu5g ×4, 16 GB, $0.184/hr) was created:
  about 30 minutes, stopped.

### Johto (MG9): G2F notes (2026-10-15)

Crystal's battles, party and bag reach Cobblemon (GEN2_BRIDGE_PLAN.md §5.10, phase G2F), in sequence after G2E as you
asked.

- **Battles** (`gen1-bridge/lib/engine/gen2/battle.lua`): every Gen 2 battle reaches `World:startBattle`, so the hold
  keeps the engine's own start and swaps only the screen (spike G2-S6): no wipe, a placeholder in place of the battle
  screen, the field and the script frozen under it. The answer is applied inside the engine's step: a trainer win pays
  the prize as the battle's own end does (the wallet and Mom's share) with the defeat and money lines; a loss whites out,
  except the first rival's (CANLOSE: the story goes on where it stands); an abort stops the script, so the trainer
  challenges again. Grass and cave rolls are the habitat pools'; fishing, surfing, headbutt, Rock Smash, statics and
  roamers are held; the Bug-Catching Contest, the catch tutorial and the Battle Tower stay in the hidden game
  (`battle.native` kinds `contest`, `old_man`, `tower`).
- **Party** (`party.lua`): Cobblemon's party is the hidden one, as on Gen 1 and 3. Gen 2 has no single gift seam, so
  the party is diffed every step and the seam that made a new member names its source (the starter, gifts, in-game
  trades with the member sent taken, hatched eggs); eggs stay in the hidden game. The Day-Care's two keepers are
  watched (deposit, withdrawal with the experience it gave). New members get DVs that reproduce Cobblemon's gender,
  shininess and Unown letter.
- **Details on the wire**: team members, wild Pokémon, gifts and party members carry the held item (as the item
  catalogue's canonical id: one `party.sync` reaches every game), shininess, gender, form and friendship; Minecraft
  applies all but the form (Cobblemon's Unown has no letters).
- **Bag and PC** (`bag.lua`): the shared Gen 1 bag with Crystal's money and pockets; `protocol/items.json` gains
  Crystal's symbols (63 shared items, its 50 TMs) and 81 new items; item uses on synced members are refused (Gen 2 has
  no `item.use`: its own methods are guarded); Bill's PC opens Cobblemon's.
- **Field moves** (`fieldmoves.lua`): `act.field` through the hidden game's party-menu path, with Gen 2's Headbutt,
  Rock Smash, Whirlpool, Waterfall and Sweet Scent; the bicycle, rods and the Escape Rope through the PACK's.
- **Java**: `MonDetails` read and applied (`Gen1Mons.applyDetails`), `PartySync` sends them, Gen 2's trainer classes
  in `ProxyClass` (Johto's and Kanto's leaders, the Elite Four with Koga, Red), `LedgerRules.Limits.GEN2`, the new
  native kinds and field actions.
- **Found on the real engine**: Crystal's `givepoke` asks for the nickname inside the op, the script yielding there,
  so the party diff saw the starter before the op returned: the seam is now marked from the op's start.
- **Checks**: local pytest and Lua 2,190 passed (new `test_lua_gen2_battle.py` 11, `test_lua_gen2_party.py` 13,
  `test_lua_gen2_bag.py` 6). On the pod (kb-dev22) the new e2e `test_crystal_battles_...` passed in 71 s: Elm's
  starter (`party.give` source starter, dex 155, with its DVs, gender and friendship) confirmed by a `party.sync`; a
  fished Magikarp held and caught in Cobblemon; a Route 30 Youngster (two Pokémon, DVs 9/8/8/8) held after its
  challenge line, beaten, its flag set and 64 paid with the two lines; the first rival held and lost with no whiteout.
  All six Crystal e2e tests, the Gen 1 battle e2e and Emerald's battles and trades e2e passed. The checkpoint's full
  check: pytest 2,184, the Gradle build with JUnit, and all 124 GameTests (13 minutes on this host: Gradle's 15-minute
  limit for them is now 25 by default, `-Pkb.gameTestMinutes` to change it).
- **Pods**: kb-dev23's host was full; kb-dev22 (cpu3g ×4, $0.16/hr) ran it, about 80 minutes, stopped.

### Johto (MG9): G2E notes (2026-10-15)

Crystal's world in Minecraft's terms: the Java builder for Gen 2 (GEN2_BRIDGE_PLAN.md §7, phase G2E), in sequence after
G2D as you asked.

- **The planner**: `Gen2Planner` (dispatched from `WorldPlanner.plan` for a `g2map` bundle) with the readers `G2Map`,
  `G2Tiles` and `G2Data`, the cell model `G2Cells` (cells from the meanings the export gives each collision byte;
  one-way walls from the side-wall bytes), `G2StoryCells`, `G2PoolWriter` (the three times of day in one pool, J6) and
  the Gen 2 form of `MapModel` (baked tiles, Gen 1's swap sites per block). Gen 3's machinery serves the rest: the
  baked pages (the bundle hands out `g2q.<n>` where Gen 3's code asks for a page), `InteriorPacker`, region builds,
  the shape planner. Both of Crystal's areas lie on the surface, Kanto east of Johto past 32 cells of sea.
- **People and scene**: Crystal's sheets go into the pack in Gen 1's layout and scale (16-pixel frames), and
  `Gen2FrontEnd` gives the shape planner its scene (with the swap sites' both states, as Gen 1's). Baked art only
  draws its own game's world (`Games.compatible`: Gold, Silver and Crystal bake different tiles).
- **J7, measured and done**: Johto's art takes 3,560 full-shape slots (14 families of 256) and 61 half ones, so the
  families per shape go **96 → 112** and a Gen 2 region takes 16 (Kanto 0..15, Hoenn 16..95, Johto 96..111). The
  schemas, `build_world.py`'s range check and PROTOCOL.md follow. Cost: about **+15 MB a side** (12,288 more block
  states at the 1.23 KB each the tile block test measured on the pod); I did not measure it in a running client.
- **The synthetic Crystal**: `tools/fake_gen1/synth2.py` (a town with a house, sign, fence, Cut tree, whirlpool and
  ledges; a route with a gate a script opens and a boulder; a dark cave with ice; a house; Crystal's Kanto's town)
  and its copy `synthetic_bundle/crystal/`; goldens `synthetic_g2_scene.json`, `synthetic_g2_world_index.json`,
  `synthetic_g2_shapes.json`.
- **The real dry run** (`RealGen2DryRunTest`, `-Pkb.realGen2Bundle`, on the pod's Crystal export from the test
  install): **all 388 maps placed** (66 on the surface, 322 interiors, deepest layer 14), 11 outdoor maps reached from
  inside get the sky (Routes 23 and 28, the ports, the National Park, Tin Tower's roof and others), 1,535 one-way
  edges, 179 tile story cells (Cut trees, whirlpools, scripted blocks), 555 habitats, 91 pools, a shape plan of about
  1.0 M blocks, in 3.1 s. Warnings: Celadon City/Route 7's loop (counted both ways; G2D found it) and one object off
  its map in Goldenrod's Pokémon Center (left out, as Gen 1's and 3's are). 20 duplicate sites are the bedroom's
  decorations (one block a script can turn into several; the first wins for now).
- **Checks**: local pytest (new: `test_synth2_export.py`, the Gen 2 goldens in `test_builder_golden.py`, the copy
  check). On the pod (kb-dev23): JUnit 1,273 passed (new `G2ReadersTest`, `Gen2PlanTest`, `Gen2FrontEndTest`,
  `G2PoolWriterTest`; tests for 112 families), GameTests 124 (new `Gen2BuildGameTests`: Johto alone at slot 2, and
  Kanto, Hoenn and Johto in one world with three family ranges and three art sets).
- **Left for later phases**: Johto's real build (G2G), the per-player textures of a palette override in the running
  game, the bedroom decorations beyond the first per block.
- **Pods**: every older pod's host had no free vCPU, so kb-dev23 (46bxqbsf1m6uzs, cpu5g ×4, 16 GB, $0.184/hr) was
  created: about 28 minutes, stopped. The Java compiled cleanly the first time there; the full check took 2 minutes of
  Gradle on it.

### Johto (MG9): G2D notes (2026-10-15)

Crystal's world for the builder (GEN2_BRIDGE_PLAN.md §6, phase G2D), in sequence after G2C as you asked.

- **The part**: `gen1-bridge/lib/engine/gen2/export.lua`, through the core's `exportResources` path as Gen 3's. Eight
  kinds with schemas: `g2map` (blocks from the copy taken at boot, border, art variant, environment, palette, the baked
  daytime, group, area by the engine's landmark rule, connections, warps 0-based with the remembered one as `dynamic`,
  coord and bg events, objects with kinds, flags, hour windows, walkers, variable sprites, big dolls, palette
  overrides), `g2tiles` (a tileset drawn with one palette set and roof: per block 16 baked tile ids and 4 COLL bytes,
  and what the engine's own predicates say each byte means), `g2image` (pages of 1,024 baked tiles), `g2anims` (water,
  flowers, whirlpools, the tower pillar, lava, currents: frames and loops from the tileset timer's 0), `g2sprite` (each
  sheet through its OBJ palette, plus the palettes objects override it with), `g2layout` (one per surface area: Johto
  from New Bark Town, Crystal's Kanto from Pallet Town), `g2encounters` (national dex) and `g2names`; `swaps` carries
  the story's sites (kind `whirlpool` added to the schema, the world index and Java's swap kinds). The art is baked on
  the CPU as `World:bakeMapImage` colours it (the sheets' shade from red, the map's eight BG palettes from the engine's
  own `Palettes.bgSet`, roof sheets over tiles $0a-$12, Crystal's bank-1 tiles), in the cart's colours.
- **Core changes**: `kb_export.KINDS` and `ExportBundle.KINDS` gain the `g2*` kinds (the builder reads them from G2E),
  `defs.exportKind`, the catalogue, PROTOCOL.md.
- **Found on the real export**: the engine places neighbours in table order, so where a loop of maps doesn't close on
  a flat plane, a different edge gave way on each run (Crystal's Kanto: once Route 7/Saffron City, once Celadon
  City/Route 7). The export now walks the directions in a fixed order (the same arithmetic): Celadon City and Route 7
  give way every time, noted for G2E as Hoenn's three loops; Johto closes everywhere. LÖVE writes palette PNGs when the
  colours fit, so the test decodes every PNG colour type (the builder's reader must too).
- **Checks**: local pytest and Lua 2,147 passed (12 new: `tools/lua/test_lua_gen2_export.py` on `fake_export2.lua`). On
  the pod (kb-dev22, cpu3g ×4, the real Crystal) `test_crystal_export.py` passed: 388 maps, 73 variants (11 with a roof
  sheet), 5,097 baked tiles on 5 pages, 63 animated tiles, 238 sprite sheets, 1,312 warps (6 dynamic), 1,466 objects,
  137 swap sites, 1.68 MB in all, built in 3.5 s; and **every one of 1,039 blocks the engine baked itself** (one map per
  tileset, environment, palette and roof, 48 maps) **hashed exactly as the export's tiles for it** (140 blocks with an
  animated tile left out on both sides). The checkpoint's full check (`tools/check.sh` on the pod): pytest 2,147, the
  Gradle build with JUnit 1,249 and the GameTests (all 122) passed.
- **Pods**: kb-dev21 and the older pods' hosts were full and cpu5c had no capacity, so kb-dev22 (fx3wud5n3ufpy2, cpu3g
  ×4, 16 GB, $0.16/hr) was created: about 25 minutes, stopped.

### Johto (MG9): G2C notes (2026-10-15)

Crystal's story state reaches the bridge (GEN2_BRIDGE_PLAN.md §5.6, phase G2C), at your "Go".

- **The part**: `gen1-bridge/lib/engine/gen2/story.lua`. **Flags** need nothing new: the save view already names every
  set event flag `E<number>` (G2A), so `story.flags` and the markers work as they are. **Who stands where**
  (`story.toggles`, ids `O<index>` as the NPC observer's): on the current map the engine's live masks
  (`World.objectMasks`: a plain `setevent` moves nobody until the next load, `appear`/`disappear` do at once); on every
  other map the object's flag and its hour window, as its next load will decide (the engine's own
  `World:objectTimeVisible`, called with the clock read once per diff). The client's default is visible, so the
  toggles are the objects hidden now. **Blocks**: Gen 1's `story.blocks` model; the map data is copied at
  `mods.loaded` (388 maps), the live blocks are the current map's own array, a map load is a new `Map` object, this
  visit's Cut and Whirlpool edits come from the World's own record (`blockEdits`). **Swap sites** (`swapSites`, the
  export's list for G2D): Cut trees and Whirlpools by the tileset's tables (cut grass is cosmetic, no site), the
  `changeblock` rows of the scripts each map's callbacks, scenes, events and objects reach, Crystal's whole-map
  `changemapblocks` (none in Crystal's scripts: 0 of 5,084), and the bedroom's decorations; on the pod 137 sites (40
  Cut trees, 6 Whirlpools, 64 script changes, 27 decoration blocks).
- **Core changes, inert on Gen 1 and 3**: `kb_story` checks every block change against `engine.swapSites()` when the
  adapter has sites of its own (`kb_swapsites.indexSites`), else Gen 1's tables as before; the swap kind `whirlpool`
  (the export's schema gains it with G2D); `swapSites` joins the story group (the stub answers nil).
- **Found in the engine**: a World that Game2 drops (CONTINUE, QUIT to the title) is never told to put its block
  edits back, so the shared map records keep them (`Game2.lua:343-344`, `:376-377`). The story therefore compares
  against the copy taken at boot, never the live records.
- **Checks**: local pytest and Lua 2,134 passed (18 new: `tools/lua/test_lua_gen2_story.py` and the protocol file's
  story tests; the fake gained masks, block edits, decorations and scripts). On the pod (kb-dev21, cpu5c ×4, the real
  Crystal) the new `test_crystal.py` story test passed in 86 s: a NEW GAME through the UI, the bedroom's two
  decoration blocks out as `story.blocks`, Mom's scene, out of the house (a mat: a step onto it and a press in its
  direction) to New Bark Town and Elm's lab, Elm's scene through the UI, a ball taken (its picture a `PicBox` mirror
  answered with A, YES, the nickname KB) until `story.flags` set `E26`; the ball's object went out hidden
  (`story.toggles` `G2_ELMS_LAB` `O3`), the driver saw the flag and the mask, `debug.status` had the map data, 137
  sites and no unknown block. With the rest of `test_crystal.py` and `test_emerald.py`: 11 passed; Gen 1's
  `test_field.py` (Blue: Cut, Surf, the spinners, the elevator, Strength; the story's Gen 1 path) 5 passed. No Java
  or protocol change: Gradle not rerun.
- **Pods**: every existing pod's host was full (and the auto mode stopped further starts until you allowed them);
  kb-dev21 (zyqsf4m2xtauom) was created like kb-dev20 (same volume, cpu5c ×4, $0.14/hr): about 12 minutes, stopped.

### Johto (MG9): G2B notes (2026-10-15)

Crystal's UI goes through the bridge (GEN2_BRIDGE_PLAN.md §5.5, phase G2B), at your "Go".

- **The part**: `gen1-bridge/lib/engine/gen2/ui.lua` (the core's uiStack, uiClass, uiOpenFacts, uiCursor, uiItems,
  uiMoneyFooter, uiSetIndex, uiSetQty, uiSetGlyphs; the mirror stays 160×144). The shared TextBox and ChoiceBox are
  read as gen1.lua reads them (the v0.3.85 arrow rule); `Gen2GenderSelect` is a choice (NO by the cursor);
  `Gen2ScriptMenu` a list (B answers 0); `Gen2StartMenu` a menu without QUIT and MODS; `Gen2NamePick` and the keyboard it
  opens are one naming UI (the pick's names are the presets; a typed name is set on the keyboard, the cursor on END,
  then A), so the client's automatic name (D6) completes Oak's `name_player`; `Gen2InitClock` (Mom's weekday wheel, any
  clock step) is answered by the adapter with the host's time (J5) and opens nothing; Elm's picture wait is a PicBox
  mirror; the boot screens one TitleState mirror; every other `Gen2<Name>` screen a mirror; fades, the caller box and
  the park's hold open nothing. No core change.
- **Found on the real engine**: nothing in the adapter. The e2e answer loop was wrong twice: Gen 2's text has
  continued lines inside a page (a press each), and `ui.update` carries only the fields that changed, so the test now
  presses each time a box's merged state turns `waiting`; and Mom's scene is a coord event in front of the stairs
  (`maps/PlayersHouse1F.asm`'s MeetMom), so the test walks onto it.
- **Checks**: local pytest and Lua 2,115 passed (16 new on the fake: `tools/lua/test_lua_gen2_ui.py` and the protocol
  file's UI tests; `fake_engine2.lua` gained the UI states). On the pod (kb-dev20, cpu5c ×4, the real Crystal):
  `test_crystal.py` 3 passed, the new one in 44 s: a NEW GAME through the UI only (the gender choice, Oak's lines, the
  name KB), down the stairs into Mom's scene (8 text boxes, the yes/no, her weekday wheel answered by the adapter within 2
  frames, no clock screen ever shown), Mom's line through the UI, START without QUIT or MODS closed with B; with
  `test_emerald.py` 10 passed together. No Java or protocol change: Gradle not rerun (G2A's checkpoint stands).
- **Housekeeping**: the first failed runs left six empty Crystal test slots in the pod's registry (no save files):
  deleted, the registry back to no Crystal slots. Pod kb-dev20 about 44 minutes (about $0.10), stopped; every older pod's
  host was full and two creates failed before it.

### Handoff (2026-10-14): after MG6, for whoever continues

**State.** MG1–MG6 of [specs/MULTI_GAME_PLAN.md](specs/MULTI_GAME_PLAN.md) are done and committed locally (never
pushed): `8596e71` MG1, `d46083a` MG2, `f096bcc` MG3, `1598c58` MG3 follow-up, `19d69fd` MG4, `0460d66` MG5, `3a847f6`
MG6. MG4–MG6 share files (both entry points, the lang file, `build.gradle`, the GameTest `fabric.mod.json`, the docs)
and roll back together: `git revert --no-edit 19d69fd^..3a847f6`. Last checkpoint (pod kb-dev7): pytest + Lua 1,729;
JUnit 1,193 (0 failures); GameTests 112; the one-link e2e regression on the real Blue engine (9 scenarios) passed. The
✔ marks on some §5 headings of the plan predate the execution and are **not** completion marks; the table above is the
status. The user has play-tested none of it yet: PLAYTEST.md "Several games" lists their MG3–MG6 items, and the two
`tools/setup_games.ps1` defaults there need their confirmation. Known gaps: no test world for a crossing between two
real engines (Red and Blue are one world space; Johto has no adapter), `stash.pc_full` untested, "Add a region" draws a
second region's blocks as cubes (the texture pack holds one game's art).

**How to work (what worked here).** Python and Lua tests run on this server: `cd tools && .venv/bin/python -m pytest
-c pytest.ini --rootdir . -q` (~50 s; e2e tests need `--e2e` and an install). Java needs a pod (memory rules: create or
start without asking, stop after each job): `~/kb-runpod/kb-pod sync` rsyncs the repo to the `kb-dev` ssh alias
(repoint with `kb-pod host <ip> <port>` after a start; the pod's repo is `/root/kantobridge`, `local` → `/workspace/local`
on the volume), then `cd /root/kantobridge/fabric-bridge && JAVA_HOME=/opt/java/openjdk ./gradlew build` (~1.5 min on 4
vCPU; JUnit XML under `build/test-results/test/`, GameTests in `build/reports/gametest/TEST-gametest.xml`). E2e on the
pod: Xvfb on `:99` (`pgrep -f "[X]vfb :99"`, never `pkill -f` a pattern that matches the ssh shell), `export DISPLAY=:99
KB_DATASET_MEMO=30 KB_EXPORT_INDEX_TIMEOUT=900`, then `tools/.venv/bin/python -m pytest -c pytest.ini --rootdir . -q
--e2e e2e/<test>.py`; the volume's data home holds Blue, Crystal and Emerald imports for v0.3.85 (the pre-flight picks
the one with an adapter). GameTests that reset shared static state need one batch per test. One orchestrator writing
schemas, PROTOCOL.md and PROGRESS.md first, then forks per plan §7 track with exclusive file ownership, then one pod
build per checkpoint, worked.

**What is next, in order.**
1. **The spikes** (plan MG0) ran on pods on 2026-10-14 ("Several games: spikes" above). S2: the Gen 3 exit is
   `world.stepped` + `mod.world:warpTo` (not `map_scripts`, not a patched warp). S3: the flags are `FLAG_SYS_POKEMON_GET`
   and `FLAG_SYS_GAME_CLEAR`. S4 passed 10 + 10. S1: Blue and Emerald together hidden take about 600 MB and keep full
   speed. S5: the Windows build honours the import variables, quits with code 1 on a refused file, and at the user's
   word imported their Blue zip (exit code 0, the marker, the game boots). MG7's spec can start. Left: S1 beside
   Minecraft on the user's PC.
2. **MG7 is done (2026-10-14)**: the spec `fb525bc`, G3A `2138127`, G3B `fc78db1`, G3C `98dd435`, G3D `404a634`,
   G3E `90938d4` and `f3a5ce1`, G3F `c23bd33`, G3G after it ("Several games: MG7" above). What was planned: the `game3` adapter `gen1-bridge/lib/engine/game3.lua` group by group
   (Appendix B is the interface, `lib/engine/gen1.lua` the worked example, `lib/kb_engine.lua` the loader whose stub
   rejects `game3` today; `protocol/games.json` `adapter: true` for `emerald` turns it on and `kb_session` then accepts
   the game), each group with lupa tests on a `game3` fake engine (tools/lua; a `fake_gen1 --game emerald` stand-in with a
   `gen3` family is to be written); the Gen 3 export (scene-shaped resources, metatile quarters per plane, 16×16
   palettes, sprites with frame tables, connections in cells; `protocol/items.json` gains `symbols.gen3`); the builder's
   `Gen3FrontEnd` → `Scene` (D13: 8-px quarters so the pipeline, slot keys and shape planner stay; a flat Hoenn with edge
   walls on elevation mismatches; habitats from Gen 3 encounters, compat 386); a synthetic `gen3` bundle, goldens,
   GameTests for a synthetic Gen 3 region; e2e on the pod's Emerald import (walk Littleroot, a door, a text box, a save).
   Size: at least one session of this one's length after the spec, in several commits by group. Risks (plan §7): `game3`
   is beta upstream and does not raise `movement.collision`/`fieldmove.eligibility`; the adapter's `REQUIRES` must make
   drift loud.
3. **MG8, Hoenn and the pass (L).** `routes.json` Kanto ↔ Hoenn pass (the user picks both exits with F7, as for the
   Viridian zone), the Hoenn pod build at slot 1 (Littleroot and Route 101 first, then all maps), per-region budgets,
   videos, PLAYTEST "Hoenn", per-map tweaks as small PROGRESS items. The two art sets in one pack came with G3E. Carried
   over from MG7: the Wild Routes exit for Emerald (E6, `routes.lua`); Hoenn's three seams (Dewford/Route 107,
   Fallarbor/Route 114, Verdanturf/Route 116); the 200 maps no warp reaches (Sootopolis and its interiors, the
   ferry's and the Frontier's); the shape map's size (1.7 MB, risk R10); Gen 3's in-game trades, daycare and eggs;
   NPC and surf sheets, animated tiles and darkness for Gen 3.
4. **Later** (plan §8, not in this plan): same-process game switching if upstream adds a facade, a Gen 2 adapter, FRLG
   as a second Kanto, heights for Hoenn, spectator visits, Yellow.

**Where things are.** Catalogues: `protocol/games.json` → `world/Games.java` and `gen1-bridge/lib/kb_games.lua`,
`protocol/items.json` → `world/Items.java` and `lib/kb_items.lua` (`python -m kb_protocol.catalog --write` regenerates
them with `catalog.json`; `tools/lua/gen_catalog.py` the Lua message catalog). Regions: `world/{RegionTable, RegionRecord,
Regions, SlotMap}`, `server/WorldIndexSync`, `server/{ProgressService, RegionPresenceService}`. Links: `client/bridge/
{Links, BridgeLink, FollowedDispatcher}`, `client/world/AddRegionFlow`. Crossings: `server/{PassCrossings, PassService}`,
`pokemon/StashService`, `client/zone/{PassChoreography, PassRuntime}`, `builder/zone/ZonePlan`, `routes/gen/
RouteGenerator`. Economy: `server/{Ledger, LedgerRules, LedgerService}`, `bag/BagService`, `gen1-bridge/act/kb_ledger.lua`,
`observe/kb_bag.lua`. Builder per game: `scene/GameFrontEnd` with `FrontEnds.forGame` (only `Gen1FrontEnd` today) and
`Scene`. Tools: `tools/kb_games.py`, `setup_games.ps1|.sh`, `launch.ps1`, `fake_minecraft --links`, `fake_gen1
--game/--state`. Notes 1–21 above record each milestone's decisions; PROTOCOL.md has the wire format.

## Story events (STORY_EVENTS_PLAN, 2026-10-16)

Your ask of 2026-10-15: make sure every story event works in every game, Emerald first, with real tests on RunPod. The
method, Emerald's results and the fix plan are [specs/STORY_EVENTS_PLAN.md](specs/STORY_EVENTS_PLAN.md).

| # | Part | Status | Date | Commit |
|---|---|---|---|---|
| SC-E | Emerald's check: `scan`, `sweep`, `objects` (`tools/e2e/g3_story_check.py`, driver `kb_g3story.lua`) on the pod's real Emerald | **done** (kb-dev24): 519 maps scanned; 323 live cases on 87 maps; 380 object visits on 190 maps. The hidden game does every story change; the bridge relays every metatile change and every object change exactly; **Minecraft shows no terrain change** (Gen 3 builds have no tile story cells and nothing reads `story.cells`), collision-only changes (267 cells) are never sent, weather and Dewford Gym's light are not mirrored. Story blockers in Minecraft: the Elite Four's exit doors, Sky Pillar's floors, the Mauville and Mossdeep Gym puzzles, Shoal Cave's tide, Route 131, Mirage Island, Sootopolis's crisis layout (plan §1) | 2026-10-16 | |
| SE1–SE8 | Emerald's fixes | **in progress** (your answers of 2026-10-16: S1–S7 defaults, S8 weather mirrored per player, S9 Dewford Gym dark until fully lit) | | |
| SC-C | Crystal's check | later (plan §5) | | |
| SC-B | Blue's check | later (plan §5) | | |

## Pre-flight (PLAN §0.6)

| Step | State |
|---|---|
| 1. ROM imports | Done by the user in both installs. Listing folder names shows a completed **Blue** import in each (`blue/` with its completion marker), so the game is Blue. |
| 2. Console check | The user ran it from PowerShell inside the install folder as `.\gen1recomp.exe --console --game=blue`: PowerShell needs the `.\` prefix there, and the game is Blue, not Red. Whether the `[gen1-bridge] gen1-bridge loaded` line appeared wasn't reported (PLAYTEST M0 asks). Automation doesn't depend on it: the e2e runner reads piped stdout, and S5 saw the line that way. |
| 3. Minecraft EULA | `local/minecraft/eula.txt` exists (created by the user; never opened or written by the agent). |
| 4. Defaults §1.2 | Unchanged. |

## Spikes (M0)

| Spike | Result | Evidence |
|---|---|---|
| S1: Cobblemon in the GameTest server, mock player | **Pass**, after two dev-environment fixes (see Deviations) | GameTest log: "Showdown has been started!", "Loaded 1025 Pokémon species"; dex 1–151 resolve through Cobblemon's API; a mock `ServerPlayer` joins with an empty Cobblemon party |
| S2: tile-block registration and memory | **Pass** | 32 families × 256 states = 8192 states ("S2: 32 tile blocks, 8192 tile states registered"); full blocks 1.0 high, half 0.5; measured heap cost about 10.1 MB (vs about 95 MB for one 0..255 property) |
| S3: resource-pack injection mixin | **Pass** | JUnit confirms the client mixin applied to `BuiltInPackSource` in dev; in the remapped jar `loadPacks` → `method_14453`. Loom 1.17 writes no refmap, so the new `verifyMixinRemap` task (part of `check`) fails the build if any injector target keeps its Mojang name. The pack-source logic is unit-tested (no folder, invalid `pack.mcmeta`, valid pack required and on top) |
| S4: lupa + sandbox parity + fake socket | **Pass** | `tools/lua/test_lua_s4_spike.py`: modules load in the sandbox copy; `socket` comes through the sandbox `require` (network permission gate exercised); a ping split in two reads gets its `pong` (`re=1`) through a 10-byte send window; without `network` nothing listens; no global leaks |
| S5: e2e runner on the test instance | **Pass** | Only `local/gen1recomp-e2e` launched, Blue auto-detected. Filtered output: `[info] [gen1-bridge] gen1-bridge loaded`, `[info] loaded mod gen1-bridge 0.1.0`, `[info] [gen1-bridge] game.ready api=2 engine=0.3.49 modules=6`, `KB_READY state=loaded engine=0.3.49 ... modules=6 mod_errors=0`; exit 0 in about 1 s; crash log untouched |

## Check results at the gates

- **M0:** `tools/check.sh --e2e`: pytest **545 passed** (protocol schemas and examples,
  `kb_protocol`, stand-ins, 179 Lua tests under lupa, 2 e2e), then `gradlew build`: **36 JUnit**,
  **11/11 GameTests**, mixin remap check, all green.
- **M1:** `tools/check.sh --e2e`: pytest **645 passed** (253 Lua, 4 e2e on the real engine:
  S5, the handshake, hot reload, the output filter), then `gradlew build`: **74 JUnit** (including the
  `BridgeClient` integration test against `fake_gen1`), **11/11 GameTests**, mixin remap check.
  The real-engine handshake test's filtered output:
  `net.listening port=27199` → `KB_OVERWORLD map=REDS_HOUSE_2F` → `session.established role=test`
  (hello.ok protocol 1, engine 0.3.49, game blue; 3 pongs; schema-valid snapshot) →
  `net.disconnected reason=replaced` → a second session with the same session id →
  `KB_QUIT sessions=2 code=0`.
- **M2:** `tools/check.sh --e2e`: pytest **704 passed** (304 Lua; e2e on the real engine: S5,
  handshake, hot reload, the export, and the real Pallet build), then `gradlew build`: **172 JUnit**
  (including `BridgeClient` and `ExportClient` integration tests against `fake_gen1`),
  **18/18 GameTests** (the synthetic world build, story-cell and ledge collision, the build hold),
  mixin remap check. **Real export:** 359 resources, 762,457 bytes, in 9 s through `ExportCli`.
  **Real Pallet build** (report, counts only): `maps_built 5`, `overlaps 0`, `edge_mismatches 0`,
  `warnings 0`, `story_cells 24` (8 object, 16 water), 96 story-cell blocks, 17,186 blocks in 20
  chunks, 4 interiors on 4 layers, 78 light blocks, `pack_textures 4273` (3,989 tile, 284 NPC, 23
  animated), slots 3,903 full + 95 half, world index written; planned in 5.2 s, placed in 0.7 s.
  The user's play-test world `saves/Kanto` was built the same way.
- **M3:** `tools/check.sh --e2e`: pytest **768 passed** (367 Lua; e2e on the real engine: S5,
  handshake, hot reload, export, the Pallet build, and the new walk: upstairs room → stairs → 1F →
  exit mat → Pallet Town → back in through the door → out again → into a wall: 24 steps, 24 answers,
  0 dropped, 4 warps (`stairs`, `edge`, `door`, `edge`), every message schema-valid), then
  `gradlew build`: **235 JUnit** (including a movement integration test against `fake_gen1`:
  join teleport, door warp, exit mat with `sync`+`n`, walk-out), **27/27 GameTests** (rules, warp
  service, build hold), mixin remap check (4 injector targets).
- **M4:** `tools/check.sh --e2e`: pytest **908 passed** (506 Lua; e2e on the real engine: S5,
  handshake, hot reload, export, walk, the Pallet build, and the new UI test: talk to an NPC (a
  2-page text box advanced to its close), the start menu (7 rows, wrapping), the trainer card on the
  mirror (78 frames, 160×144, 98 B–1.7 KB), QUIT's YES/NO answered NO then YES), then
  `gradlew build`: **322 JUnit** (including a UI integration test against `fake_gen1`: puppets from
  the snapshot, talk → text box → advance → close, UIs closed when the link drops), **27/27
  GameTests**, mixin remap check.
- **M5:** `tools/check.sh --e2e`: pytest **972 passed** (566 Lua; e2e on the real engine: all
  earlier ones plus the opening (NEW GAME via the mirror, both names typed on our naming screen, Oak's
  escort: 24 scripted steps, the escort flags), the lab rival battle won and lost (starter
  `party.give` → a made-up-uid `party.sync` → `battle.start` kind `rival` held in `busy`, re-sent
  with the same id after a reconnect → result applied; no blackout on the lab loss; progress flags
  3 → 6)), then `gradlew build`: **361 JUnit**, **40/40 GameTests** (battle proxy, exact moves and
  IVs, a real Showdown forfeit → `lose`, party give/PC overflow/sync, starter lock, Cobblemon
  settings, all 151 species and 165 moves resolving), mixin remap check.
- **M6:** `tools/check.sh --e2e`: pytest **1044 passed** (637 Lua; e2e on the real engine: all
  earlier ones plus Route 1: 201 grass steps with no native battle (`encounterGrass` 201, every
  battle counter 0), a made-up `dex.sync` (4 seen / 2 owned) and `party.sync` (2 members, uids)
  applied, `bag.sync` with only battle items, a refused key-item `bag.use`; and the real **Route 1
  build**: 7 maps, 4 habitat blocks, 46 pools, 0 warnings), then `gradlew build`: **387 JUnit**,
  **53/53 GameTests** (wild spawn with no AI and `pve`, outcome mapping, bag sync without echo,
  `bag.use`, stone/TM/Rare Candy effects, habitats configured, Cobblemon's spawner offering the
  synthetic pool), mixin remap check. The user's `saves/Kanto` was rebuilt in place with the Route 1
  set (player data kept).
- **M7:** `tools/check.sh --e2e`: pytest **1091 passed** (681 Lua; e2e on the real engine: all
  earlier ones plus Viridian: the nurse heal (`party.heal`, the hidden game's own save, then
  `save.begin{center}`), a start-menu save, `session.bound`, a Viridian Forest trainer's sighting →
  walk-up → `battle.start{trainer}` → win (defeated, money and flags up) and a second one → lose
  (blackout warp to VIRIDIAN_CITY, then `party.heal`, money halved), Bill's PC → `pc.open` →
  `pc.closed`, the player's PC native, a Mart buy and sell with exact money and bag changes; and the
  real **Viridian build**: 13 maps, 7 habitat blocks, 46 pools, 0 warnings), then `gradlew build`:
  **419 JUnit**, **63/63 GameTests** (PC sessions, saves, the stable world id, lose → heal → warp,
  the leave-during-battle regressions), mixin remap check (5 targets). The user's `saves/Kanto` was
  rebuilt in place with the Viridian set.
- **M8:** `tools/check.sh --e2e`: pytest **1224 passed** (796 Lua; e2e on the real engine: all
  earlier ones plus field moves (cut, surf, spinners, an elevator, Strength), 9 content scenes, the
  endgame (an Elite Four room, the Champion → Hall of Fame → credits, Snorlax, the Game Corner rocket),
  and the **full real build: 220 of 220 maps, 0 warnings, 0 overlaps, 0 unplaced, in 57 s**), then
  `gradlew build`: **479 JUnit**, **73/73 GameTests**, mixin remap check (6 targets). The user's
  `saves/Kanto` holds all of Kanto.
- **M9:** `tools/check.sh --e2e`: pytest **1275 passed** (840 Lua; e2e adds per-world save pairing
  on the real test instance: worlds A/B on slots 2/3 across a restart, a saved game switched after
  its own save, an unsaved New Game waiting, singleplayer back to the offline slot whose listing is
  unchanged, and the port fallback; two stand-in pairs side by side), the full real build (220 maps,
  0 warnings), then `gradlew build`: **525 JUnit**, **80/80 GameTests** (authority for every
  client→server type, two players' collision, `world.slots`, the activity sync, a player trade and a
  player battle with real Showdown), mixin remap and target checks.
- **M10:** `tools/check.sh --e2e`: pytest **1400 passed**:
  - **925 Lua tests.**
  - **38 e2e tests on the real test instance:**
    - crash and CONTINUE: the same pair after a kill and relaunch, `ops` back to 0, and a re-sent
      gift not counted twice;
    - handshake hardening;
    - the per-step budget: about 150 µs per logic step on average against 1 ms, with the mirror
      peaking at 30 fps;
    - the hidden and minimised window measurements;
    - the full real build: 220 maps, 0 warnings, 0 overlaps, 0 unplaced, 56.7 s, with
      **throughput 49,237 blocks/s, 42.1 chunks/s, 3.9 maps/s**.
  - **437 in `tools/tests`:** `launch.ps1` dry runs and its port probe under PowerShell 5.1 and 7,
    the installer skeleton, and the stand-ins' M10 messages.

  Then `gradlew build`: **569 JUnit**, **88/88 GameTests**, the mixin remap and target checks.
  The JUnit tests were rerun with `--rerun` against the final fake_gen1, because Gradle doesn't
  track `tools/fake_gen1` as a test input. The first gate run had found the two e2e flakes in
  deviation 110.
- **Early full-Kanto smoke build** (not an M2 gate; M8 owns it), `build_world.py --maps all` into
  `saves/KantoFullE2E`: 219 of 220 reachable maps (36 surface, 183 interiors on 14 layers, 5 moved
  sideways), **2,480,041 blocks in 2,126 chunks in 59 s** (plan 5.9 s, place 53 s), 12,539 story
  cells (9,305 water, 2,439 boulder area, 760 object, 35 tile), 3,032 ledge colliders, 12,033 lights,
  0 overlaps, 0 edge mismatches; the **same pack and slot-map hashes as the Pallet build** (the filter
  doesn't change them). **23 warnings for M8:** `interior_unplaced` ×1 (one interior found no
  room) and `swap_block_mismatch` ×22 (swap sites whose `before` block doesn't match the map). The
  builder logs codes and counts only; M8 needs a symbols-only diagnostic naming the maps.

## Deviations from the plan

**M0**

1. **Protocol v1 vs the §3 draft** (all listed in PROTOCOL.md "Changes from the draft"): `re` lives
   only in the envelope; `save.done` carries the `save` id; a few optional fields added
   (`move.step.push`, `player.pos.n`, `player.blocked.n`, `act.field.item`, `ui.update.qty`,
   `party.give.dvs`, `debug.quit.code`); `daycare.*` also travel C→S; `party.sync`'s and the
   snapshot's `nick` are marked ROM-derived; the relay-only types are fully defined.
   `protocol/redaction-vectors.json` was added (derived, like `catalog.json`) so Java's `Redact` is
   tested against Python's output exactly.
2. **`fake_gen1`, `fake_minecraft` and `kb_protocol.aio` landed in M0**, ahead of M1, with 13
   contract tests. M1 still owns their JUnit/e2e use.
3. **Two dev-environment fixes for S1** (recorded in VERSIONS.md): the Kotlin Gradle plugin 2.2.20
   (no Kotlin sources; Loom remaps Kotlin metadata only with it) and ICU4J 71.1 on the dev server
   and GameTest classpaths only. Both use versions already pinned through Cobblemon. Neither changes
   what players get.
4. **Cobblemon recipe overrides** (PC, healer, pasture) win only because Fabric orders mod data packs
   by id (`kantobridge` after `cobblemon`). A GameTest checks the three recipes are gone and an
   unrelated one survives.
5. **Lua details:** `kb_json` encodes an unmarked empty table as `[]` (maps that may be empty use
   `json.object()`); it also rejects duplicate keys and integers beyond ±2^53. `kb_log`'s safe-id
   pattern allows `/` so `file.lua:NN` locations print. The sandbox copy in `tools/lua` leaves out
   the engine's legacy compat shims, so any use of them fails tests.
6. **pytest runs with `-p no:faulthandler`**: LuaJIT on Windows raises an SEH exception for every Lua
   `error()`, which faulthandler printed as "Windows fatal exception".
7. **The e2e output filter** passes only `KB_` markers, our own `[gen1-bridge]` log lines and the
   engine's "loaded mod gen1-bridge" line; other engine lines that mention the mod show only their
   `file.lua:NN` locations (`KB_ENGINE_NOTE`).

**M1**

8. **Hot reload uses a takeover slot**, not only `collectgarbage`: the first hot-reload e2e failed
   2 of 3 runs because the old instance's sockets closed only when a GC happened to run. Each
   instance now leaves a close function on the Game object (`kb_engine.takeOver`) and the next
   calls it on `game.ready` before binding. This is PLAN §4.1's "process-lifetime slot" pattern, and
   it is a **new engine internal** to re-check on pin changes. It also covers F5 and return to
   launcher.
9. **`snapshot.ingame`** (new required field): at the title screen there is no overworld, so
   `player` is only the save's last position (the boot skeleton's spawn on a fresh start). Clients
   must not move the player to it.
10. **gen1-bridge extras:** a spinner step is reported as kind `spin` already (trivial to detect);
    Seafoam currents go out as `script` until M8. (Superseded in M3: boulder pushes are `push`, the
    Cycling Road roll is `slope`.) The snapshot carries the hidden party (no uids yet) and the current map's NPCs, because
    both were cheap. `debug.status` adds `ingame`, `port`, `accepted`, `sessions`, `steps`,
    `optionsPinned`. A snapshot warning is deferred to the end of the step so no `log` lands
    between `hello.ok` and the snapshot. Lines are flushed at the end of each step.
11. **`gen1-bridge/lib/kb_catalog.lua`** is generated from `protocol/catalog.json` by
    `tools/lua/gen_catalog.py` (a test fails while it is stale), so gen1-bridge checks legs and reply
    rules without parsing JSON at boot.
12. **The display-options pin (P21)** is applied in memory at `game.ready`, `save.created` and
    `save.loaded` (on this install it changed text speed 3 → 1 FAST). It is never written to the
    options file by the bridge.
13. **Client choices:** `send()` returns false and drops the message while not connected (callers
    resend from state after the next snapshot); messages before `hello.ok` are dropped; a `hello.ok`
    with the wrong `re` closes the link, and one with another protocol number counts as REJECTED;
    peer `error`s are never answered with an `error`. The status line sits top-left (title screen and
    in game), fades 5 s after connecting, and shows "not connected, retrying…" or "rejected (reason),
    retrying…" until the next handshake; F1/F3 hide it. Debug overlay key: **F7**. Client config:
    `<gameDir>/config/kantobridge-client.json` (`{"port": 27151}`), `-Dkantobridge.port` overrides.
14. **M2 preparation landed in M1** (protocol owner): the 10 export resource schemas
    (`protocol/export/`), the per-cell flag bits and bundle layout (PROTOCOL.md "Export resources"),
    the WorldIndex schema (`protocol/world/`), `fake_gen1`'s synthetic export (`synth.py`, served
    through `export.index`/`export.get`, and written as a bundle by `--write-bundle`). `fake_gen1`'s
    world now derives its walls, warps and signs from that export, and exit mats warp when you step
    off the edge, as in Gen 1.

**M2**

15. **The export reads through `mod.datasets` (PLAN's primary path; the §8 fallback wasn't needed)**
    and is slow by nature: every record read costs about 5 ms because `DatasetViews:_ready`
    (`src/mods/DatasetViews.lua:209-243`) re-inspects the cache on every read. The full build takes
    about 8 s of game time (474 steps, longest step about 175 ms while the engine decodes a cache
    module), so the first `export.index` answers after 10–15 s of wall time; fetching all resources
    then takes 0.17 s. **Decision: keep the datasets path**: it is a one-off wait at world
    creation, and it gives the pristine game data (other content mods stay off in the hidden game,
    DESIGN §2). Copying the live `Data` before any map loads would be faster; it remains the
    documented fallback.
16. **Real export, counts only** (`local/reports/export_report.json`): 359 resources (map 222,
    tileset 24, image 34, sprite 73, plus palettes, encounters, field, names, layout, swaps), about
    763 KB, largest line 32.6 KB (`encounters`), deterministic across runs, 0 warnings. 36 surface
    maps, 186 interiors (184 with a parent: COLOSSEUM and TRADE_CENTER have none), layout box 170×180
    blocks. **The real data has 78 directed connection edges, not the 72 PLAN §4.5 states**; all
    agree, none overlap. 59 swap sites (35 cut, 20 card-key, 4 scripted).
17. **Export semantics chosen in M2** (now in PROTOCOL.md): pad/hole bits only on warp-entry
    cells (elsewhere those tiles are floor art); the spinner bit marks `field.spinners` cells; the
    warp-tile bit is the tileset's `warp` list; a cut tree is a tree tile in a block that
    `cutTreeSwaps` swaps; `lastMap` is null when it isn't a single outdoor map; interiors take their
    own palette cascade (e.g. CAVERN → CAVE) before the remembered outdoor map's; images also cover
    `fx.*` (overworld effects, emotion bubbles); the field tables are a fixed list of 29.
    Left out for later milestones: cuttable tall grass ($52), script-only block swaps (gym barriers,
    Elite Four doors, Mansion switches, S.S. Anne: M8's audit), ADVANCED per-tile palettes, trainer
    parties (built at battle time instead), text fields.
18. **Story-cell collision by type:** `object` and `tile` cells close while active in the player's
    `StoryState`; `water` closes unless the player surfs (or the cell is active); `boulder_area`
    closes where a boulder stands. A player with no state yet is blocked by water only.
19. **Swap sites are built open** (cut trees, card-key doors); story cells close them per player,
    and the closed art is already in the slot map for M8's per-player overlay.
20. **Builder geometry choices:** heights re-implemented from D_voxel's documented measurement rule
    and rounded to walls 2 and roofs/cliffs 4 (synthetic art is uniform, so its roof measures 2);
    the void fill is a 12-block ring of 3-high border art around the surface maps; every interior
    gets a 1-block border-art ring from floor to ceiling plus a ceiling (Gen 1 interior floors run to
    the map edge, so PLAN's "fully enclosed" needs it); up to 4 chunks may wait on the light engine
    before placement pauses (instead of strictly one).
21. **Layout checks are counted, not fatal:** edge mismatches and overlaps go into the report as
    counts and warning codes (the gate requires both to be 0).
22. **Unreachable maps** (COLOSSEUM, TRADE_CENTER) are left out of a full build with a note and not
    counted in `maps_requested`; requesting one by name is a warning. Items standing on solid cells
    (tables) get no story cell, only a note.
23. **Animations:** water = the tileset's water tiles minus shore; flowers = tile 3 (the engine's
    default) with frames from the `tilesets.flower1..3` images. An explicit animated-tile list in the
    tileset export would remove that assumption (open issue).
24. **Fixed formats:** NPC sheets 64×32; `world.index` parts 64 Ki characters; slot map and pack
    layout documented in PROTOCOL.md (`protocol/world/slot_map.schema.json` added). The synthetic
    build's WorldIndex is a golden file that a GameTest reproduces byte for byte and a pytest checks
    against the schema. Slot map and pack depend on the whole export, never on the build's filter.
25. **Headless build:** `./gradlew runServer -Pkb.build=<bundle> -Pkb.buildMaps=<ids>
    -Pkb.buildReport=<file> -Pkb.buildExit=true --args=nogui` (the build starts on
    `SERVER_STARTED`, writes into the server's own world folder, and exits 0 or 1);
    `tools/e2e/build_world.py` drives export + build + report checks, building automated runs into
    `saves/KantoE2E` so they never touch the user's `saves/Kanto`.
26. **ExportClient and ExportCli:** the export runs on its own short `builder` connection, which
    replaces the player's main link, so the main `BridgeClient` gets a `PAUSED` state
    (`pauseReconnects`, nestable; the HUD shows "exporting…") and reconnects + resyncs afterwards.
    The bundle's `index.json` is pretty-printed (so `ExportCli` and `fake_gen1 --write-bundle` agree
    byte for byte); resource files are canonical. Index checks are stricter than the protocol: ids
    must be file-safe and unique ignoring case; an empty index is rejected. `ExportCli` gives the
    JVM its own log4j2 config (warnings to stderr) so its stdout carries only the `export:` line.
    Optional `-Pkb.exportTimeout` (default 120 s).
27. **"Create Kanto world" flow:** hands the build to the integrated server through a one-shot
    `BuildRequest` (world folder, bundle, all maps, pack already written by the client) claimed on
    `SERVER_STARTED` by a non-dedicated server whose world folder matches (expires after 5 min,
    never in GameTests); the same start path as the headless trigger. **Players who join during the
    build are held as spectators** at the void spawn behind the progress screen (`BuildHold`),
    otherwise they would fall before any ground exists; they get their game mode back and are
    teleported to the spawn when the build ends (also restored before any save). On failure or
    Cancel the flow leaves the world and deletes the folder it created. The flow's world has
    **commands enabled** (to let the user inspect interiors with `/gamemode spectator`; easy to turn
    off), structures and bonus chest off, a random seed. `server/KantoGameRules` holds Appendix D's
    rules for M3's `Rules` service.

**M3**

28. **Warp kinds follow the mechanism** (now in PROTOCOL.md "Movement rules"): leaving Red's house
    is `edge` (an exit mat), not `door` as the plan's e2e line said; `door` is a door tile or a gate
    doorway (covered by walking back in through the house door); `stairs` any other warp tile;
    `elevator` any warp out of an `*_ELEVATOR` map; Dig and Escape Rope report as `teleport` until
    M8's field moves can name them.
29. **One answer per `move.step`**, always carrying its `n` (PROTOCOL.md): a warp without a visible
    step answers with `player.pos` kind `sync`; a boulder push with kind `push` (implemented now
    rather than in M8); steps that can't run get `player.blocked` reason `script` (not idle, dropped
    from the 2-deep queue, no result within 12 logic steps, or at the title screen).
30. **Ledge hops** send one `player.pos` kind `ledge` at the landing cell with all 32 frames; the
    arrival's `to` is the entry cell, and a door walk-out follows as its own `player.pos` kind
    `script`. The order within a logic step is `player.state`, `warp`, then `player.pos`.
31. `debug.status` gains `move*`, `warp*` and `mapsEntered` counters; gen1-bridge now loads 16
    modules. `fake_gen1` mirrors these answer rules (exit mats answer with `sync` + `n`, kind `edge`,
    arrival on the door cell, then a scripted walk-out).
32. **Rules apply on every server running the mod** (GameTest server and non-Kanto worlds too), as
    PLAN §4.4 states them unconditionally. Fixed time is noon (6000).
33. **Gen 1 speeds are 0.1737 (walk) and 0.3474 (bike)** movement attribute: exactly 16 and 8 Gen 1
    frames per 2-block cell under Minecraft's ground physics (PLAN P2 said ≈0.174/≈0.347), checked by
    a physics simulation in JUnit. Sprinting is stopped on the client (a `LocalPlayer` mixin) as well
    as on the server, because a server can't stop a client from sprinting.
34. **Creative and spectator players are never followed or held**, so `/gamemode spectator`
    inspection keeps working; returning to Adventure teleports the player onto the hidden cell.
35. **Teleports and corrections:** joining, re-linking and a resync's snapshot (`ingame` only)
    teleport the player onto the hidden cell at any distance, with a kind-less `warp.request` (the
    client's own sync). Snap-backs, drift steering and door walk-outs are short ForcedMover walks (6
    frames per cell for snaps, 16 for steering); a player still not there 10 ticks after a move
    should have ended is teleported. Drift's "different map" means a different layer: neighbouring
    outdoor maps share one grid, so a seam is steered across, not teleported.
36. **Warps:** the `warp.request` goes out once the fade-out is complete, so the jump is never
    visible; fades are 250 ms (white for fly/teleport/dig/rope/pad/hole); timeouts 10 s for the
    arrival, 5 s for `warp.done`, 3 s for the destination's sections, each fading back in with a
    counted warning.
37. **InputLock** also holds the player during forced moves, at the hidden game's title screen
    (`ingame:false`, with a HUD hint), and when the hidden map isn't built in this world (HUD hint).
    It only applies in Kanto worlds (a WorldIndex is present).
38. **Intent timing:** `move.face` goes out when the player stands still with the camera quadrant
    off the hidden facing for 4 ticks (at most once a second); pushes repeat every 16 ticks while
    pushing, only when no step is waiting; the step timeout is 3 s (counted, warned at most every 10 s).
39. **A minimal `ClientStoryView` landed in M3** so bike speed works: surf/bike go to the local
    `StoryState` first, then `story.state` is relayed (full on join, snapshot and respawn, with `active`
    and `lit` empty until later milestones; deltas after). A malformed `story.state` changes nothing
    and gets `error{bad_message}`. `move.hold` (Cycling Road brake) is M8.

**M4**

40. **Text boxes run ahead locally:** a press at a stop moves on at once and sends exactly one
    `ui.advance`; a press while typing finishes the visible text; `ui.update` only moves a box forward;
    a final press without `ui.close` within 1.5 s may be resent. New optional `ui.open` fields
    `choice` (text: a choice follows, so no ▼ and no advance at the end) and `wrap` (menu/list
    cursor wrapping) close the gap where the client couldn't know a choice was coming.
41. **One Minecraft screen, several UIs:** the client keeps a stack of open UIs (a choice over its
    text box shows as one view; UIs underneath are drawn inactive, including a mirror's last
    picture); answers go only to the top UI, at most once until it changes. Safety nets: a dropped
    link closes every UI; each snapshot rebuilds them; if the hidden player stays idle for 0.5 s,
    anything still open is cleared (counted as stale); our screen lingers 150 ms after its UI closes
    so chained boxes don't flicker. Esc opens the pause menu and our screen comes back after. UIs show
    only while following the hidden game (a Kanto world, not creative or spectator).
42. **Interpretations the protocol leaves open:** Gen 1 menus wrap and lists stop at their ends
    (unless `wrap` says otherwise); left/right pages lists and steps quantities by ±10; Start closes the
    start menu; a click advances text and picks on menus. The A key fires only when the Minecraft
    player's cell matches the hidden player's; targets are 1–2 cells in a straight line (2 across a
    counter). Emote bubbles are white boxes with `!`, `?` or `♥` (not Gen 1 art). The mirror asks for
    30 fps. The dialog area is about half the window wide (1×–2× the GUI scale). The optional Gen 1
    bitmap font was skipped (Minecraft's font, `<PK>`/`<MN>` as small capitals).
43. **Puppets** are `kantobridge:puppet` entities (registered both sides, noSave, noSummon, tracking
    range 0, 1.75×1.9), spawned only on the client with negative ids; 2×2-block camera-facing
    billboards using the map palette's NPC sheet from the generated pack (the M2 pack writer needed no
    change: every object sprite on every map has a sheet); a neutral placeholder covers sprites
    changed at runtime through the mod API (Gen 1 content never does). `UseEntityCallback` and
    `UseBlockCallback` send `act.interact` and return FAIL; left-clicks on puppets are cancelled.
44. **gen1-bridge UI choices:** naming sets `glyphs` and then taps START so the screen's own
    `confirm()` runs from its update (not from our hook); with presets up it picks NEW NAME first, and
    a name equal to a preset picks that preset. PartyMenu stays a mirror screen (P10); the Pokédex
    list, the prize counter and the Fly list are list adapters. The ready gate is a superset of P2
    §3.3 (`stay.prompt` and `auto.promptFirst` boxes take one press at their end); `auto`/`stay` are
    sent only when the box's end takes no press. Text pages longer than 8 lines are split into
    protocol pages. The interaction retry follows a moving NPC to where it stops (`npc_moved` if out of
    reach). Seam targets are refused with `off_map`. NPC diffing pauses while no client is connected.
    gen1-bridge never fills `snapshot.ui`: open UIs follow the snapshot as `ui.open` messages (bottom
    first), then an `npc.set`.
45. **Mirror cost (real engine):** about 1.3–1.5 ms per capture on average (PNG encode about
    0.8 ms), 2.2–4.2 ms worst; frames are 98 B–1.7 KB; about 35% are identical and skipped; frame
    times stay at the 16.7 ms vsync budget with the mirror on or off. No PLAN §8 fallback was needed.

**M5**

46. **Proxy placement and removal:** the hidden proxy stands on the trainer's cell if the WorldIndex
    knows it within 32 blocks, otherwise 4 blocks in front of the player; it faces the player and
    carries the trainer's name; it is discarded once Cobblemon's recall finishes (10 s at most), at
    server stop, and when a crash left one in a loaded chunk.
47. **Cobblemon settings in memory** at `SERVER_STARTING` (Appendix C); `main.json` is never
    rewritten. `shinyRate` is 1e9 (infinity would overflow Cobblemon's fishing reroll, which converts
    the rate to an int and adds 1); passive statuses last 1e9 s. The starter lock lives in
    `CobblemonSettings`, called from `Rules`.
48. **Gifts** keep Cobblemon's random nature and ability (P11 covers trainer Pokémon only); `ot` only
    for trades; one application per `give` id. `party.take` handles trades only (daycare in M8);
    `party.item` is a counted "unsupported" until M6. A repeated move in a team or gift is kept once.
49. **No `party.sync` during a battle**; one follows each battle. **The client relay** answers a
    repeated `battle.start` from its record, never re-sends results unasked (gen1-bridge answers an
    unexpected result with `error battle.unknown`), answers `aborted` at once without a server link,
    aborts pending battles on leaving, queues up to 32 `party.*` requests while offline (flushed
    before any `battle.start`), and re-sends the latest `party.sync`/`dex.sync` after each snapshot.
50. **During any Cobblemon battle the client holds the player** (`InputLock` reason `BATTLE`):
    movement sync unlinks (relinking puts the player back on the hidden cell), our screens wait, and A,
    START and clicks do nothing. **M6 consequence:** Cobblemon's Run (walking away) can't work, so M6
    must end wild battles as `fled` on `BATTLE_FLEE_ATTEMPT`.
51. The `activity` attachment isn't persisted (synced to other clients since M9). Wild kinds are
    answered `aborted` (`unsupported_kind`) until M6.
52. **The opening needed no `intro.oak_speech.build` wrap:** M4's UI observer already turns both
    naming steps into `ui.open{naming}` answered by `ui.name`, and survives hot reload. Oak's escort
    was already reported correctly by M1–M4 (24 scripted player steps, Oak's NPC moves, the escort
    flags), verified on the real game.
53. **Scripted battles wait inside their script** on the runner's own `waitingCheck`, and the result
    goes back through `start_battle`'s checkpoint-resume path, so the engine itself sets
    `lastBattleResult`/`lastCheck` and runs `afterBattle`. Map trainers are deferred through
    `trainer.before_battle` with the world held through `ow.engaging`. Battles are taken over only
    while a client or test session is connected. The held battle and queued gifts/heals live in a
    process-lifetime slot on the Game object (`kantoBridgeState`, a **new engine internal**), so a
    hot reload keeps them.
54. **Gifts run natively** and are matched by dex and level when the sync arrives (no duplicate); an
    optional `give` field on `party.sync` entries would make the match exact (M6 candidate).
    gen1-bridge gives up on a gift after 5 syncs and 2 minutes, conservatively, because the client
    re-sends stale syncs after every snapshot. (M6 added the exact `give` match.)

**M6**

55. **gen1-bridge encounter rules** (now in PROTOCOL.md "Wild encounters, bag and Pokédex"):
    Pokémon Tower ghost battles without the SILPH_SCOPE run natively (P9's "unidentified ghost") with
    `battle.native{ghost}`; `battle.native` comes from watching the stack (so the old man's demo is
    announced too); the surf hook takes the engine's own roll and runs `encounter.species` and REPEL
    itself (returning nil would skip them) and holds the world with `ow.engaging`; fishing keeps the
    bite text and animation native and replaces only the native battle (with no client connected at
    the end of the cast, the engine's own verdict runs).
56. **Item use:** stones and Rare Candy skip their Gen 1 effect (the item is consumed, the party menu
    closes with the bag's white flash) and Cobblemon applies them; TMs/HMs are taught natively (the
    Gen 1 forget prompt is the slot picker), then `party.item` reports the move; a `party.sync` waits
    while a TM/HM is being taught. `party.item` has no id, so it's sent once and queued until a
    client connects (an id like `give` would make re-sending safe).
57. **`bag.sync`** follows every in-game snapshot and every change; `bag.use` of a non-mirrored item
    is refused with `bag.not_mirrored`. `dex.sync` is the only source of the Gen 1 Pokédex (the Gen 1
    side never marks it locally). gen1-bridge now loads 24 modules.

58. **Wild battles in Cobblemon** (`EncounterService`): the Pokémon appears on the given cell (or two
    cells ahead), stands still, can't be battled by anyone else, doesn't count toward the spawn cap,
    and gets **Cobblemon's own moves for its level** (the protocol's `wild` carries no moves); `pve`
    with flee distance 12 (none when `rules.flee` is false); `rules.catch` false makes it
    uncatchable. **Run ends the battle as `fled`, for every wild battle in a Kanto world** (the
    client holds the player still, so Cobblemon's walk-away flee can't work).
59. **Bag mirror:** 29 mirrored items; a matching sync changes nothing; items the mirror itself takes
    are never reported; uses during a battle wait until it ends (Cobblemon refunds unused items);
    in-flight uses are subtracted from crossing syncs and dropped after 60 s if no sync shows them.
    **Appendix B corrections** (checked against Cobblemon 1.8.1): `X_DEFEND` → `x_defence`,
    `X_SPECIAL` → `x_special_attack`.
60. **Habitats:** one pool per wild map of the whole export (like the slot map), handed to Cobblemon
    immediately (a new world datapack loads only on the next world load); blocks buried 2 below the
    ground with a greedy cover that always covers the first uncovered cell (about a third fewer
    blocks on route-shaped grass); checked at runtime as chunks load. **Interiors with a pool get the
    `kanto_cave` biome** (the `no_interior_spawns` rule would block them; the biomes look and sound
    the same), and a new spawn rule **`walking_level_only`** keeps spawns on floor blocks at a map's
    walking level (a block's 14-block range also covers tree tops, roofs and story cells). No PLAN §8
    fallback was needed: the synthetic GameTest runs Cobblemon's own spawner against the test grass.
    **Pokémon Tower gets no habitats or pools for now**, so it has no wild Pokémon once the player has
    the SILPH_SCOPE (the hidden game suppresses Tower encounters then) until M8.
61. **Client clicks:** only plain right-clicks on blocks (empty hand, block items, plain items)
    become `act.interact`; balls, medicine and other usable items pass through to Minecraft.
    `bag.sync` shares the party queue (party → bag → battle order; only the newest bag waits);
    `bag.use` is queued for the next snapshot while gen1-bridge is away.

**M7**

62. **Bill's PC:** `pc.open` opens Cobblemon's PC through our own `BridgePcLink` (away from any PC
    block; never during a battle) plus `OpenPCPacket`. Cobblemon has no "PC closed" event, but its
    PC screen removes the link on every way out, so the server checks the link each tick and sends
    exactly one `pc.closed`; refused opens are answered at once; a second open is ignored. The client
    holds movement and our screens while the PC is open, and if Cobblemon's screen never shows (or
    vanishes unanswered) it sends Cobblemon's own unlink packet and answers gen1-bridge itself after
    5 s. `pc.open` has no id: a close missed during a link drop answers a `pc.open` arriving within
    5 s of the next snapshot (an id would remove that guesswork).
63. **Saving:** `save.begin` saves the player's party and PC and other Cobblemon data (Pokédex, TM
    moves), then the world like `/save-all` without a flush, and answers `save.done{save, ok}`;
    `ok:false` on any exception, while a world holds players for a build, or under `/save-off`; one
    save per id. Cobblemon has no public per-player save, so an accessor mixin
    (`PokemonStoreManagerAccessor`, `remap=false`, not an injector) reaches its store list. A test
    world saves in 16–38 ms; a full world is untested (each save logs its time).
64. **Sessions:** the world id is a UUID in `<world>/kantobridge/session.json` (created on every
    world's first start, including non-Kanto, GameTest and headless worlds; an unreadable file is
    moved to `.broken`), which also records each player's paired slot. `multiplayer` is false only for
    the integrated server's own client. The protocol now relays `session.bind` S→C and
    `session.bound` C→S, so LAN guests and dedicated-server players pair too.
65. **Blackout:** `BattleService` needed no change; lose → (still fainted) → `warp.request{blackout}`
    → `party.heal` leaves the player teleported, healed and out of battle (GameTest). The `blackout`
    warp fades to black over 1 s and back over 0.5 s.
66. **The `writeSave` spike passed** (no PLAN §8 fallback): the nurse heal is spotted through the
    engine's `ow.healAnim` (only her heal sets it), `party.heal` goes out the same step, and the
    center save runs after her farewell lines, behind the engine's quick-save gate (not
    mid-dialogue). gen1-bridge never writes `lastHeal` itself.
67. **Mart money and clerk lines** use a same-id `ui.open` re-send instead of new `ui.update` fields
    (no schema change); the Java `UiRouter` already replaces in place. While a PC hold is active,
    answers to the PC menu wait (and time out with `ui.timeout`).
68. **A blackout lands in front of the Pokémon Center** (the fly spot of the last heal point's town,
    the engine's own behaviour), not inside it. Battle results restore the map's music
    (`Music.restoreMap`), as a battle screen's exit does; otherwise a trainer's sighting theme kept
    playing.
70. **A Cobblemon 1.8.1 crash hazard, fixed:** when a player stops seeing their own sent-out
    Pokémon, Cobblemon's `PokemonEntity.stopSeenByPlayer` (`PokemonEntity.kt:2193`) removes it on the
    spot, from inside Minecraft's `ChunkMap.removeEntity(player)` loop over the tracked entities, which
    corrupts fastutil's iterator (an NPE, only for some map layouts). A real server could crash when a
    player disconnects with a Pokémon out, or warps (or blacks out) right after a battle. The fix: a
    HEAD inject (`PokemonEntityMixin`, remapped to `method_5742`) cancels that in-loop removal of the
    owner's own untethered Pokémon and queues it (`DeferredRemovals`) for `END_SERVER_TICK`. Our own
    removals (proxies, encounter Pokémon) already ran outside the loop. GameTest cleanups now end the
    battle and let Cobblemon's recall finish before removing the mock player, and two regression
    GameTests (leave mid-battle, leave in the tick the battle ends) fail without the mixin and pass
    with it.
69. **Correction to deviation 12:** the engine's `SaveData.save` writes the live options to
    `options.lua` on every progress save, so the pinned display options and FAST text speed **are**
    persisted, since M4 on the player's own saves and now also on the center save (see Open issues).

**M8**, run in three waves, each ending green with an intermediate commit
(`M8 wave <n>: …`); the milestone commit `M8: the rest of Kanto` comes after wave 3:

- **Wave 1 (done):** the full build with clean report gates (items 1); the swap-site audit
  (2); per-player story cells (`ClientStoryView` active cells per PROTOCOL.md "Story rules (M8)",
  `StoryCellRenderer` closed art) and gen1-bridge's story diffs; forced movement (3); field moves
  from Minecraft (4); bike, surf and Flash feel (5); their e2e scenarios.
  Wave 1 Java results (builder, story cells, feel):
  - 71. **`swap_block_mismatch` ×22 was the builder being too strict:** the engine stamps the 20
    card-key doors and the 2 Rocket Hideout lift gates shut on map load, so the map data holds the
    site's `after` (open) block. The builder now accepts either block (noted `swap_site_stamped`).
    Swap cells are built per cell in their walkable state (normally `after`; a cell where only
    `before` is walkable keeps `before`, e.g. the S.S. Anne deck).
  - 72. **`interior_unplaced` ×1** came from `InteriorPacker.place` returning null when the parent
    already sat on the deepest layer; it now uses PLAN §8's fallback (the deepest layer, the nearest
    free spot outside every surface map's footprint, under the void fill; noted
    `interior_deep_spill`).
  - 73. **`build_diagnostics.json`** (beside every copy of the report): per warning code, rows of
    symbols only (map, parent, layer, kind, source, the site's index in the export); report fields
    `maps_unplaced` and `diagnostics`; `build_world.py` gates on `maps_unplaced == 0` and prints the
    diagnostics on failure.
  - 74. **Story cells:** the rules live in common `story/StoryRules` (shared with GameTests); tile
    cells close on their `closed.block` (WorldIndex) and use a new block `kantobridge:story_tile`
    with a block-entity renderer drawing the closed art per player (other story cells cost nothing
    per frame). The WorldIndex story-cell table gained `hidden`, `start`, `water` and `closed`
    (schema updated; golden updated for this change only).
  - 75. **Feel:** the surf sprite comes from the pack (`textures/player/<palette>/surf.png`), a
    billboard in third person and flat in first person, the surfer at Y 63.5; on the Cycling Road
    sneak sends `move.hold{b}` and doesn't crouch; Flash's Night Vision is applied by the server
    (`FlashService`) only on a WorldIndex-dark map the player's state has lit, never touching a
    potion's Night Vision.
  - **Not done in wave 1 (moves to wave 2):** the client side of field moves (right-click a cut tree
    or water → `act.field`; a Fly list); play-test-only: the closed art's lighting, a half-block gap
    under the S.S. Anne hull art, the surf sprite, the Cycling Road feel.
  Wave 1 Lua results (story diffs, swap audit, forced movement, field moves):
  - 76. **Story diffs** per PROTOCOL.md "Story rules (M8)": effective visibility through the engine's
    own `objectVisible`; live blocks against the map data copied at `mods.loaded` (before any map
    loads; the dataset view as fallback); flags every 10 logic steps, at script end and map load;
    an unexplained block change is counted and logged as `story.unknown_block`.
  - 77. **Swap-site audit: 95 sites** (cut 35, card-key 20, Victory Road boulders 4, scripted 36:
    Elite Four exits, Lance's doorway, the Mansion's 13 switches, the Cinnabar Gym gates, the S.S.
    Anne, …). One row was wrong and fixed (the Vermilion Gym door had `before` == `after`); `site()`
    now refuses no-op rows. One site matches neither block in the map data: AGATHAS_ROOM (2,0), which
    the engine overwrites on every entry (`story4.lua:870-873`), so the builder must accept it
    (wave 2).
  - 78. **Forced movement:** spinners `spin` (48 frames a cell), currents `current` (16), the
    Cycling Road `slope` (8, with the B brake), holes (including script-opened ones), pads, boulder
    pushes (`push` + `npc.move push`); warp kinds `dig`, `rope`, `teleport` told apart.
  - 79. **`act.field`** runs every action through the engine's own entry points after its own
    checks (`partyKnows`, the badge, the place); Escape Rope is `dig` + `item:"ESCAPE_ROPE"`; `fish`
    without an item uses the best rod; `fly` without `dest` opens the game's Fly list. Real-engine
    e2e: cut (regrows after leaving), surf, a spinner maze, an elevator, a Strength push.
  - Wave 1 gate: `tools/check.sh --e2e` **1154 passed**, `gradlew build` **451 JUnit**, **65/65
    GameTests**; **full real build**: all **220 of 220** reachable maps (184 interiors on 14 layers,
    1 deep spill, 5 sideways), 2,485,146 blocks in 2,126 chunks in 57 s, 12,664 story cells (160
    tile), 296 habitat blocks on 46 pools, slots 3,926 full + 92 half, 0 overlaps, **1 warning**
    (`swap_block_mismatch`: AGATHAS_ROOM, for wave 2).
- **Wave 2 (done):** static encounters (6); native special battles and the Safari crossover (7); gifts,
  in-game trades, the Name Rater, the Game Corner and prize counter (8); gyms, the Elite Four, the
  Champion, the Hall of Fame and credits (9); the daycare and battle-music polish (10).
  Wave 2 Java results:
  - 80. **Field-move clicks:** A or a plain right-click sends `act.field` (cut on a standing cut tree,
    surf on water, surf → dismount on land while surfing; A also triggers cut and surf, per P20's
    "right-click = A on targets", but never dismounts so shore signs stay readable); a
    `GameRenderer.pick` TAIL mixin lets the crosshair land on closed art as drawn (story blocks have
    no outline and a tree's upper half has no block).
  - 81. **Swap sites:** a `script` site matching neither block (AGATHAS_ROOM (2,0), overwritten by the
    engine on every entry) is built open with tile story cells (`swap_site_overwritten` note); other
    kinds must still match. VERMILION_DOCK needed no change (the hull is water with `water:true`).
  - 82. **Native battles** (Safari, old man, ghost) keep a mirror up between their own UIs (a UI of
    our own, id −1), hold the player, refuse A/START/clicks and report `activity` `battle`; cleared on
    `end`, a snapshot, a dropped link, or 1 s of the hidden player idle.
  - 83. **Daycare** (P15): a per-player store in `<world>/kantobridge/daycare/<uuid>.json` (Cobblemon's
    own JSON, written atomically before the Pokémon leaves the party; party and PC saved right after),
    withdrawals with 1 XP per step (level 100 cap); `party.take{daycare}` = deposit; `party.take`
    recalls a sent-out Pokémon first.
  - 84. **Battle music (P22):** the server clears `PlayerBattleActor.battleTheme` on
    `BATTLE_STARTED_PRE` for bridge battles and natural wild battles in Kanto worlds, so Cobblemon
    sends no theme (other worlds keep Cobblemon's music).
  Wave 2 Lua results:
  - 85. **Statics:** Red/Blue's static objects (Power Plant, the birds, Mewtwo) use hand-ported talk
    scripts ending in `static_battle`, so M5's script path already covers them; the talk-path branch
    (OC:3488-3510, no Red/Blue users), the ghost Marowak and Giovanni's hideout battle push their
    battles directly and are now held through a text box's wrapped `onDone` (shadowing `pushBattle`
    for that one synchronous call; no global patch, hot-reload safe). Giovanni goes out as
    `scripted`; an aborted direct battle ends as a flight.
  - 86. **Every native wild catch crosses over** as `native_catch` (not only Safari ones: also
    catches made with no client connected); trades as `party.take` + `party.give{trade}`; Name Rater
    renames; daycare deposits/withdrawals; the Hall of Fame heal. Gyms, the Elite Four and the
    Champion needed no change (checked against the engine). **P22 is implemented**: the engine's
    music API (`Music.playBattle`, `playVictory`, `restoreMap`) gives the Gen 1 battle themes during
    held battles.
  - Open: (the defeat lines and the endgame e2e were closed in wave 3); a
    trade or daycare change followed by a CONTINUE from an older hidden save leaves the games out of
    step (M10's save pairing); a traded member without a uid gets no `party.take` (counted).
  - Wave 2 gate: `tools/check.sh --e2e` **1207 passed** (783 Lua; e2e adds 9 real-game content
    scenes: a Power Plant static, the ghost Marowak, Giovanni's hideout battle, the Eevee gift, an
    in-game trade, the Name Rater, Brock's gym with its badge, a daycare deposit/withdrawal, a Safari
    catch crossover), `gradlew build` **479 JUnit**, **73/73 GameTests**; **full real build: 220 of
    220 maps, 0 warnings, 0 overlaps, 0 unplaced, 2,485,154 blocks in 56.5 s.**
  Wave 3 results:
  - 87. **Trainers' own defeat lines** (Silph Co Giovanni, the Game Corner rocket, the Karate
    Master, the Nugget Bridge recruiter, every gym leader's badge lines with their jingle) now show
    after a Cobblemon win: the continuation captured at deferral runs the engine's own start on a
    win, with `BattleState.newTrainer` and `pushBattle` shadowed for that one synchronous call (a
    stand-in keeps the substituted line, sound, page and the encounter's own `onFinish`); losses and
    aborts keep M5's path. Gym leaders' badge lines now come as the tagged defeat line before the
    prize (not repeated after it).
  - 88. **Endgame end to end on the real game:** an Elite Four room (Elite Four members don't sight
    the player: entering runs the walk-in, the battle starts on talking; the door opens; no heal
    between rooms), the Champion → the Hall of Fame (heal, save) → credits → the title, Snorlax with
    the flute (abort brings it back, a catch removes it), and the Game Corner rocket's line and exit.
    The Hall of Fame scene writes a save into the test install's own slot.
  - Wave 3 also made the e2e build gate the **full Kanto build** and rebuilt the user's `saves/Kanto`
    as all of Kanto (220 maps, 0 warnings, player data kept).
- **Wave 3 (done):** the remaining e2e scenarios (11), the full real build gate, rebuilding the user's world
  as all of Kanto, and the badge-by-badge play-test checklist.

**M9**

89. **Texture flow for joining players:** `world.slots` sent after `world.index` (only when its hash
    matches the index); a mismatching local pack makes the client re-export its own art through
    `ExportClient` and rebuild against the host's slot map, every time (even with a bundle already on
    disk); a pack it can't rebuild is held back in memory so the world shows the jar's placeholders.
    `texture.status` reasons as in PROTOCOL.md.
90. **Speech bubbles** (signs in a white box: `…` dialogue, `▶` cutscene, `⚔` battle; up to 64
    blocks, hidden by walls and F1) from the `kantobridge:activity` attachment, now synced to other
    clients (`syncWith`, all but the player). **Blockade fading:** other players standing in a cell
    closed for you render at about 55% alpha (vanilla's translucent-body path; held items, cape and
    shoulder Pokémon hidden meanwhile); object, closed-tile and boulder cells count, water doesn't. A
    new `verifyMixinTargets` check (in `check`) covers INVOKE targets the remap check doesn't inspect.
91. **Trades:** after Cobblemon's `TRADE_EVENT_POST` both traded Pokémon lose their `give` id and both
    parties sync; player battles were already covered by the after-battle syncs.
92. **Two dev clients:** a `runClient2` run config (Player2, `local/minecraft-p2`, port 27152).
    **Two dev clients can't share a LAN world** (Minecraft's integrated server authenticates guests
    with Mojang, which dev accounts fail), so the documented multiplayer setup is the local dedicated
    dev server (`runServer`, `online-mode=false`) with two clients.

93. **Per-world save slots switch at runtime** (PLAN §8's `--slot` fallback wasn't needed, though
    the pairing file exists for a launcher: `<install>/mod_cache/gen1-bridge/session/pairings.json`,
    installation-wide in `mod.cache` because `mod.storage` is per playthrough; written via `.tmp`
    with a `.bak`). New slots appear in Gen1Recomp's launcher as `MULTIPLAYER <8 hex>`. A
    singleplayer bind returns from a multiplayer slot to the remembered offline slot. Verified on the
    test instance: worlds A/B → slots 2/3 across a restart, a saved game switched after its own save,
    an unsaved New Game waiting, and the offline save's listing (names, sizes, times) unchanged.
94. **Ports:** the BRIDGE PORT mod option (F10 mod manager, applied at once) plus an automatic
    fallback to the next free of 8 ports after 3 s (also covering Windows' excluded port ranges);
    a second player on one PC uses a **second install** (two processes of one install would share
    the slot registry and pairing file: documented, not prevented).
95. **Red/Blue guests** need nothing more (grass/cave suppressed; surf, fish, palettes, tiles and
    swap sites are identical between Red and Blue); pairings are kept per game version.
96. **Client pairing gate** (`SessionGate`): nothing is relayed between a bind and its matching
    `session.bound`; held story messages replay only when the pairing provably kept the same save,
    otherwise they're dropped and the client resyncs (on a first pairing that can lose a one-shot
    message sent in that window: M10 adds `session.bound.switched`); refused binds retry with
    back-off; `session.no_slots` plays unpaired; a world whose id never arrives opens unpaired after
    10 s; the HUD shows the waiting reason.

**M10**

97. **Protocol additions** (PROTOCOL.md "Crash recovery and save pairs", Handshake "Hardening",
    relay): `save.begin.pair`/`at`, `save.loaded`, `save.mismatch`, `relay.hello`,
    `session.bound.switched`, `session.waiting`, `pc.open`/`pc.closed` ids, `hello.ok.tested`,
    `activity:"paused"`. This is 77 message types. The plan's "save-pair mismatch warning" became
    a pair id plus a count of distinct Pokémon operations on both sides. That catches a hidden game
    that went back to its save (`hidden_behind`) and a Minecraft that went back
    (`world_behind`), not just different saves.
98. **The pause overlay** (`LinkPauseScreen`):
    - It shows only in a Kanto world while following the hidden game (not creative or spectator),
      after 1.5 s.
    - It replaces the bridge's own dialogue and mirror screens, but waits behind any other screen
      (the inventory, Cobblemon's battle screen).
    - "Not answering" covers 10 s without `hello.ok`, or a last drop that was a silence timeout.
99. **Save pairs (server):**
    - The server sends a mismatch on every mismatching `save.loaded`; the client shows each
      distinct kind once per world join.
    - The stored pair changes only on `save.begin` or a counted operation.
    - The daycare reconcile runs on every `save.loaded`, including a fresh New Game. When an entry
      and a party copy both exist after a crash, the entry is dropped; a later withdrawal keeps
      M8's behaviour (the party copy gets the steps).
    - This closes M8's daycare-stranding issue.
100. **Relay handshake:**
    - The missing-mod kick runs on the next server task.
    - A client that never answers `relay.hello` isn't kicked, and a server hello without a usable
      protocol number doesn't make the client leave.
    - GameTests swap the kicker to watch the decision.
101. **Performance (client):**
    - Puppets more than 48 blocks from the camera or outside the hidden player's map area aren't
      drawn, though they still move every tick.
    - Mirror uploads are capped at 30 per second; replaced frames are counted on F7.
    - The HUD, F7, bubbles and puppet frames no longer allocate each frame.
    - `build_report.json` gains `throughput{blocks_per_s, chunks_per_s, maps_per_s}`.
102. **Other players' surfing** is drawn: a synced `kantobridge:surfing` attachment, set from each
     player's `story.state`, with the sprite drawn under surfers within 64 blocks.
103. **Two test fixes:** `BridgeClientTest`'s pause test had a race, now fixed. The fake_gen1
     integration test broke when fake_gen1 began sending `switched`, before the client knew the
     field; that is fixed too.
104. **The save pair id (gen1-bridge)** is the first 16 hex digits of a SHA-256 over:
     - the bridge token;
     - a counter that survives hot reloads;
     - the start's seed;
     - `savedAt`;
     - the clocks;
     - a fresh table address.

     It is written into the save's own mod data (`mod.save`) at `save.writing`, so the same file
     write stores it and CONTINUE restores it. The alternatives didn't work:
     - `mod.storage` writes its own files at once, so a crash could put it out of step with the
       save;
     - the playthrough id names a playthrough, not a save;
     - `savedAt` alone has 1-second resolution.

     A save from before M10 gets a pair derived from its own metadata.
105. **Operations on the gen1-bridge side:**
     - Each is counted once, when first sent.
     - The count resets when `save.begin` is sent, on CONTINUE/F2, and on New Game.
     - An operation after a save that hasn't been announced waits until that save's `save.begin`
       has gone out.
     - The save before a slot switch keeps the leaving world's pair, with `ops` = sent plus
       dropped unsent.
     - The order within a step gains `save.loaded` after `save.begin`.
     - The real-engine crash test confirms it: a reconnect re-sends the unconfirmed gift with the
       same `give` id and `ops` stays 1. After a kill, a relaunch and CONTINUE, the save comes
       back with the same pair and `at`, with `ops` 0 and without the gift.
106. **Handshake (gen1-bridge):**
     - Rejections go in the order `protocol`, then `malformed`, then `role`; an unknown role
       string gets `role`, and fake_gen1 now matches.
     - With no `hello` after 10 s, it sends `error{no_hello, msg:"hello_timeout"}` and closes.
     - A bad first line gets `bad_envelope` and is closed.
     - `tested` is `0.3.49`.
     - PC ids are `pc-<token>-<n>`.
107. **Performance (measured on the real test instance):**
     - **The scenario:** a busy Pallet Town with NPCs, a sign, the start menu, the trainer card and
       the mirror at 30 fps.
     - **gen1-bridge's cost:** 146 µs per logic step on average against the 1 ms budget, 2,485 µs
       at most, and 1 of 2,063 steps over 1 ms.
     - **Mirror capture:** about 725 µs per drawn frame, counted separately as `perfDraw*`.
     - **Mirror rate:** never more than 30 frames within a second. The cap is a sliding window,
       which holds a frame about 4 times a second under real jitter. The client averaged 23.5 fps,
       because identical frames are skipped.
     - The new module is `lib/kb_perf.lua`, making 30 modules.
108. **Running hidden:** minimising was never slow on Windows.
     - **Measured:** 60 logic steps/s minimised, with or without a client. CLAUDE.md's "a third of
       the speed" came from reading the code and was wrong.
     - **The real slowdown** is a window hidden with `SW_HIDE`, which ran at 20.3 steps/s. While a
       client is connected, gen1-bridge now keeps it at 59.8 steps/s by wrapping
       `love.window.isVisible`, and paces the faked frames to 60/s.
     - **How it was measured:** without a test driver (`launch_plain`), because a driver turns
       frame pacing off.
     - **Minimised drawing:** a minimised window drew 118–350 frames/s, which wastes CPU and GPU.
       While a client is connected it is now paced to 60 frames/s at 60 steps/s. Without a client
       the engine's own behaviour is unchanged.
109. **`test_endgame[snorlax]` had a timing race:** whether Snorlax's hide arrives before or after
     `battle.start` depends on the story's 10-step beat. Its check now counts from the second
     flute and requires the last toggle to be hidden.
110. **Two e2e flakes, found by the first M10 gate run and fixed by the orchestrator.** The budget
     itself always passed (142–157 µs per step):
     - **No route to a goal:** the shared e2e walker (`test_walk.py`) failed at once when an NPC
       stood on its goal or in a narrow pass. It now waits and plans again, and fails at once only
       when the goal is unreachable even without NPCs. `test_perf` also reads the next-nearest
       Pallet sign when an idling NPC keeps one busy.
     - **The mirror window:** `test_perf`'s client-side 5-second window counts frames by when the
       harness read them, and it reads in bursts. It now allows 160 instead of 151. The
       authoritative checks are unchanged: gen1-bridge's own `mirrorPeakFps` ≤ 30 and the client's
       average ≤ 30.5.

     After the fixes, `test_perf` passed 6 of 6 runs.
111. **`ReconnectFakeGen1IntegrationTest` had a timing race:** it dropped the link right after handing
     `save.done` to the client, which sends on its writer thread. When fake_gen1 took the `drop`
     first, the answer was lost (as it may be in play: `save.done` is log-only for gen1-bridge,
     which doesn't ask again), and the last check failed. The test now waits for fake_gen1 to
     count `save.done` before the drop. Found on a Linux pod: it failed once in a full check,
     while the GameTest server started alongside. With every core kept busy, the old test failed
     13 of 30 runs and the fixed one passed 20 of 20.

## Open issues

Final state after M10. Issues resolved along the way are gone from this list; the deviations above
say how each was resolved:

- block clicks (61);
- the build hold (`Rules` skips held players);
- the activity sync (90);
- the battles M8 intercepts (85–87);
- Safari and native catches (86);
- PC ids (97);
- daycare stranding (99);
- other players' surfing (102).

### Questions for you

1. **Display options (from M7).** Gen1Recomp writes its live options on every save, so gen1-bridge's
   pinned display options and FAST text speed end up in `local/gen1recomp/options.lua`:
   - the pinned display options are classic: no voxel, tilt, zoom or wide battle;
   - you'll see them there if you also play Gen1Recomp without the bridge.

   Should the bridge restore your own options when it exits?
2. **Switching a multiplayer world while a saved game runs (from M9).** Today gen1-bridge saves that
   game into its own slot first, then returns to the title. Progress is never lost. Keep that, or
   refuse and wait for you to quit to the title?
3. **Standalone Gen1Recomp after a multiplayer session (from M9).** Gen1Recomp remembers the last
   active slot, so it continues that world's slot until a singleplayer world binds again. There are
   two ways out:
   - the bridge restores your offline slot when Minecraft disconnects at the title;
   - you start it with `tools/launch.ps1 -Slot <your slot>`.

   Which do you want?
4. **Two dev clients on LAN (from M9).** Should a dev-only switch let a LAN host accept offline
   guests? Two dev clients share the local dedicated dev server instead (`launch.ps1 -Players 2`),
   which DESIGN's "a server, or LAN" allows.
5. **Worlds built before the voxel plan's V5 (from V9).** Their closed story art (a Cut tree, a locked gate) is
   still drawn by the old block-entity renderer, because their closed columns have no carrier above feet level. The
   plan's V9 removes that renderer. Removing it now would make those cells invisible walls until the world is
   rebuilt. Keep the fallback (today), or remove it and rely on rebuilding? Deviation 76.
6. **The installer (M10)** has four questions in [INSTALLER.md](INSTALLER.md):
   - bundle or download Prism Launcher, and use Prism's Microsoft client ID;
   - download the pinned files at install time;
   - PowerShell or a native installer;
   - also support the official launcher.

   Nothing is downloaded or bundled until you answer.
7. **DESIGN.md:39-41 (from the caves in 3D plan's H7).** It says caves sit under their mountains, 16 blocks apart, and
   every ladder fades. Since H7 caves stand in an area of their own, rooms of one cave may share a rock wall, and 15
   ladders are climbed. Your file: update it when you like.

### Known gaps

- **Pokémon Tower:**
  - It has no wild Pokémon once you hold the SILPH_SCOPE. With the scope, the hidden game
    suppresses Tower rolls like any indoor roll, and the builder gives the Tower no habitat pools.
  - The fix is to gate a Tower pool per player on the SILPH_SCOPE, or to let the hidden game roll
    there and send `battle.start` as for surfing. Either needs a decision.
- **What isn't mirrored:**
  - NPCs on neighbouring maps (the engine's "ghosts" across a seam), so the previous map's puppets
    vanish when you cross.
  - The Pokédex list adapter has no field for owned markers.
  - At the title, TitleState and the intro arrive as mirror UIs and the title menu as a menu.
- **Crash recovery:** it warns rather than undoing anything.
  - When the hidden game goes back to an older save (`hidden_behind`), Pokémon received since stay in
    Minecraft, and the story may give them again.
  - When Minecraft goes back (`world_behind`), the missing Pokémon aren't recreated.
  - A hidden save whose file write fails after `save.writing` has already announced its new pair.
  - Savestate checkpoint restores (a developer feature) send no `save.loaded`.
  - A New Game moved into an empty slot (M9) re-sends its gifts to the new world.
- **Two processes of one Gen1Recomp install** would share its save slots, pairing file and
  `options.lua`. This is documented, not prevented: each player needs their own install.
- **`test_hidden` starts the test install on its configured port.** A Minecraft running at the same
  time could connect to it, so close Minecraft before an automated run (PLAYTEST says so).
- **Slot capacity:** 3,926 of 4,096 full-shape slots are used by the whole export. Rebalance the 32
  families before adding new art kinds (`slot_map.full`).
- **The "Create Kanto world" flow:**
  - Closing the game during a build leaves the half-built world. Only the flow's own Cancel and
    failure paths delete it.
  - A failed resource reload is detected heuristically.
- **Design notes, kept as they are:**
  - The center save relies on the engine's `healAnim` (fine for Red/Blue).
  - Native item effects with no client connected, or on a member without a uid, consume a stone
    without a Cobblemon evolution, which matches the battle rule.
  - `SAFARI_BALL` is mirrored but never appears, because Safari balls live in `save.safari`.
  - Forest and Safari maps got ceilings like any interior; since V7 an open-air interior (the forest, the Safari Zone)
    is placed under the sky instead (voxel deviation 66).
  - The `activity` attachment isn't persisted.

### Play-test only

No client GameTests exist on 1.21.1, and agents never run `runClient`, so everything you see or
feel is checked only by [PLAYTEST.md](PLAYTEST.md):

- the HUD and F7 overlay;
- movement feel, warps and the camera;
- screens, puppets and the mirror;
- how battles look;
- the world's look and sound;
- multiplayer with two clients;
- M10's pause overlay, warning screens, the ⏸ bubble and others' surf sprite.

The step driver's re-press after a focus change is tested only in simulation.

### Notes

- **Harmless noise:**
  - the Kotlin plugin makes Fabric Loader warn "Class path entries reference missing files:
    build/classes/kotlin/…";
  - Cobblemon logs "No data fixer registered for cobblemon:*" and ".old File … corrupt or missing"
    for brand-new GameTest mock players.
- **The GameTest EULA:** the GameTest server writes `eula=false` into
  `fabric-bridge/build/run/gameTest/eula.txt`; Fabric's GameTest mode skips the EULA check. The
  agent accepted no EULA.
- **`modkit lint`** (PLAN P7 §2) isn't run, because it executes tooling from `refs/gen1recomp`. The
  real loader accepting the mod (S5, `mod_errors=0`) is the stronger check.
- **Gradle doesn't track `tools/fake_gen1`** as an input of the JUnit task, so after changing the
  stand-in, run `gradlew test --rerun` (the M10 gate did).
- **The lupa TCP shim** (a Python-backed real socket) was judged unnecessary: the strict lupa rig plus
  the real-engine e2e tests cover that path.
- **Battle logs:** if Cobblemon's battle engine crashes, it writes `battle_logs/<id>.txt` (with
  names) into its run folder (`local/minecraft`, or `fabric-bridge/build/run/gameTest`). Both are
  git-ignored.

## Blocked

Nothing.

## Play-test items waiting on the user

- PLAYTEST.md M0 (optional): the console line, `runClient` reaching the title screen with Cobblemon,
  `tools/check.ps1` on your machine.
- PLAYTEST.md M1: the status line and the F7 overlay following your walk in Gen1Recomp's window.
- PLAYTEST.md M2: look around the headless-built `Kanto` world (Pallet Town, the underground
  interiors, textures); optionally the experimental `KantoFullE2E` and the "Create Kanto world"
  button.
- PLAYTEST.md M3: walk Pallet Town and its houses with Gen1Recomp following; stairs and doors.
- PLAYTEST.md M4: talk to people and signs, the start menu, the bag, YES/NO, the mirror, puppets.
- PLAYTEST.md M5: a new game from Minecraft: names, Oak's escort, the starter, the rival battle.
- PLAYTEST.md M6: wild Pokémon on Route 1, catching, the Pokédex, Poké Ball and Potion counts.
- PLAYTEST.md M7: the nurse and saving, the Mart, Bill's PC, a trainer, one blackout, save/reload.
- PLAYTEST.md M8: the whole story, badge by badge, with field moves and the endgame.
- PLAYTEST.md M9: two players on one PC (a second Gen1Recomp install, the dev server, two clients).
- PLAYTEST.md M10: `tools/launch.ps1`, crash recovery (the pause overlay, CONTINUE, the save-went-back
  warning, the daycare), minimising, and the multiplayer extras (the ⏸ bubble, others' surf sprite).
- PLAYTEST.md "Caves in 3D": in a new world, every cave end to end: raised ground, cliff edges, the real ladders, Rock
  Tunnel with and without Flash.
- ENGINE_UPGRADE_PLAN UP5 (gate G5): on the Windows v0.3.85 test install, `tools/check.sh --e2e`; then the playtest
  items that touch the changed seams (M1's F7 engine line 0.3.85 with no untested-engine toast, M3 doors and stairs, M4
  dialogue pages and the arrow, M5 the rival battle's music and the map song after, M7 the Center, PC and Mart counts,
  M8's Brock and the badge jingle, M10 hidden-window speed, Wild Routes WR3–WR4's warps).
