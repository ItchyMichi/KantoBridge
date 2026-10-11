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
