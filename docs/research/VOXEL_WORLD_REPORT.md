# Research report: Kanto inside a vanilla overworld, and region-aware voxel art

The answer to [VOXEL_WORLD_SPEC.md](VOXEL_WORLD_SPEC.md), written on 2026-10-04. **Research only:** nothing in
the repository was changed except this file and its three draft companions; nothing was built, launched or
run; and nothing under `local/` was opened except the count reports in `local/reports/`.

- **Companions (drafts, synthetic values only):** [`region_record.draft.json`](region_record.draft.json),
  [`scene_description.draft.json`](scene_description.draft.json),
  [`texture_pack_manifest.draft.json`](texture_pack_manifest.draft.json).
- **Background:** [NEXT_STAGES.md](../NEXT_STAGES.md) sections 2–5, [PLAN.md](../PLAN.md),
  [PROGRESS.md](../PROGRESS.md), [PROTOCOL.md](../PROTOCOL.md).

## How to read this report

| You want | Read |
|---|---|
| The recommendation in one page | [1. Summary](#1-summary) |
| What you have to decide | [5. Decisions for the user](#5-decisions-for-the-user) |
| What to build first | [6. Milestone plan](#6-milestone-plan), milestone M11 |
| The integrated designs with data formats | [4. Designs](#4-designs) |
| The evidence for one question | [3. Answers](#3-answers), by question id (search for `### A2.1`, `### B2.8`, …) |
| How the code works today | [2. Facts](#2-facts-a1-and-b1-and-corrections-to-4) and the A1/B1 answers |

**How it was produced.** Four researchers worked in parallel, each with the spec's rules: world generation
and the builder (A1.1–A1.4, A2, A3, A6); runtime scoping and the hidden game (A1.5–A1.7, A4, A5, A7, A8);
the block approach and rendering runtime (B1.1–B1.3, B1.6, B1.7, B2, B6); the shape generator, packs,
characters and multi-game (B1.4, B1.5, B3, B4, B5, B7, B8). The rendering track of the spec was split in two
because it holds 43 of the 97 questions. Section 3 keeps their answers in full, in the spec's answer format.
An integrator then read all of it, re-read in the sources the claims the designs rest on (38 checks, listed
in [10.3](#103-verification-log); every one matched), settled the points where two tracks differed (marked
**Integrator note** under the answer concerned), and wrote sections 1, 2.1 and 4–10. Claims that were not
re-checked stand on the researchers' own citations.

**Data safety.** Under `local/` only names were listed and `local/reports/*.json` read: counts, hashes,
timings and engine symbols. Real-Kanto numbers come from `build_report.json`. A few sizing counts (cells
per kind, the layout box, swap sites, palettes, sprites) come from `export_report.json` in the same folder
and are cited as such; no design depends on them. `refs/personal rom dumps` and `testing images/` were not
opened, and no script from `refs/` was run. Dramaless Shape and Kanto in First Person are described in the
report's own words; no code, table value or asset of theirs appears.

**Words used in the answers.** "Track 1", "track 2", "3a" and "3b" are the four researchers above. In the
runtime answers a dagger (†) after a citation marks a line that was read by a helper of that researcher and
not re-read by the researcher; everything a recommendation depends on carries no dagger.

**Vocabulary (one set for the whole report).**

| Term | Meaning |
|---|---|
| **Region** | One game's world placed in the Minecraft world: an id (`kanto`), the game that runs its story (`blue`), a footprint, an index, rules |
| **Core** | The block columns the region owns from the bottom of the world to the sky: its maps, the ring around them, the filled gaps, and the columns over every interior. Only followed players, creative players and spectators may stand there |
| **Apron** | The flat strip of generated ground just outside the core (8 blocks), plus each gate's corridor. Anyone may walk there; the region's movement and protection rules already apply |
| **Footprint** | Core plus apron: everything the region's rules cover. "Inside" means inside the footprint |
| **Zone** | The class of a column: `OUT`, `APRON` or `CORE` |
| **Ramp**, **belt** | Beyond the apron: vanilla terrain eased to the region's level over about 96 blocks, optionally with a biome chosen by the kind of edge. Ordinary vanilla land for every rule |
| **Gate** | A designated opening: inside cells on a map, an outward direction, and a walkable corridor through the ring to the apron |
| **Following** | The Minecraft player and the hidden player move as one (today's behaviour) |
| **Away hold** | A state gen1-bridge pushes onto the hidden game while the player is outside; the hidden game stands still under it |
| **Presence** | A per-player server record: `NEW`, `INSIDE` or `OUTSIDE`, with the gate left by |
| **Carrier** | A block whose only visual job is to give the chunk mesher a position at which our model runs |
| **Shape id / map / table** | A world-scoped integer naming a shape key / position → shape id (ids only, synced) / shape id → geometry and texture keys (built on each client from its own export) |
| **Art set** | The art of one game export on one player's machine (`blue`) |

---

## 1. Summary

### Part A: Kanto inside a vanilla overworld

- **One overworld, a new world preset.** `kantobridge:kanto_overworld` uses our own chunk generator, a subclass
  of vanilla's noise generator (one access-widener line, because the vanilla class is `final`). It uses
  vanilla's noise settings by reference and gates them in memory, so vanilla terrain, biomes, caves and
  structures generate everywhere except in the footprint. No vanilla JSON is copied and generation needs no
  mixin ([A2.1](#a21-architecture), [4.1](#41-the-world-generation-design)).
- **The reserved core is solid, not void.** Under Kanto an unbreakable foundation block fills every core
  column up to Y 62; interiors are air boxes inside it; above is air. Caves, lava, water and miners end at a
  wall. The builder then places exactly today's blocks; a fresh build must report `blocks_cleared 0` and
  `foreign_blocks 0` ([A2.4](#a24-underground), [A6.1](#a61-when-the-build-runs)).
- **The footprint is a per-cell hull,** planned from the whole export before the first chunk generates (on
  `SERVER_STARTING`), stored with its hash in the world folder, fixed for the life of the world, and sent to
  clients ([A3.1](#a31-the-footprint), [4.2](#42-the-footprint-and-edge-design)).
- **Seamless edges.** An 8-block flat apron at Kanto's level, then a ramp into the seed's own terrain, with
  the biome and target height chosen by the kind of edge (forest behind tree walls, ocean behind sea, hills
  behind cliffs). Structures whose bounding box comes within 32 blocks are dropped as a whole
  ([A2.2](#a22-structures), [A3.3](#a33-transitions-per-edge-kind)).
- **No magic warp: gates.** A player leaves and enters at designated gates. Leaving and entering are requests
  to the hidden game (`region.leave`, `region.enter`); the hidden player is parked at the gate under an away
  hold; a per-player gate block and a server-side bounce close every other way in. A normal crossing has no
  teleport and no game-mode change. Entry is allowed at the gate you left by and, later, at gates this save
  has already used. Computing "reachability" was rejected: the story's gates are scripts, not data
  ([A4.2](#a42-leaving--the-gate-model-and-the-border-state-machine), [A4.3](#a43-entering),
  [4.3](#43-the-border-state-machine)).
- **Rules by zone, not by world.** A constant-time `OUT`/`APRON`/`CORE` lookup replaces "this server" on the
  server; on the client the single flag "following" gains the border state. Protection is one guard at
  `Level#setBlock` plus Fabric's player events. Flat worlds built before this keep today's code path
  ([A1.5](#a15-every-behaviour-that-is-world-scoped-or-server-scoped-today), [4.4](#44-region-scoped-rules)).
- **A region record wraps the unchanged WorldIndex,** so a second region later adds a record, not a redesign
  ([A8.1](#a81-the-region-record), [4.5](#45-the-region-record)).

### Part B: region-aware voxel art

- **A hybrid block approach.** Keep today's tile blocks, slots, story blocks and collision. Replace the
  generated blockstate and model JSON by one runtime model (Fabric's model-loading and rendering APIs). The
  block state keeps the base tile, which also keeps distant-terrain mods, shader packs and the fallback look
  working; the shape comes from a synced per-position shape map; geometry is built on each client from its
  own export. Shaped art lives in new carrier blocks. Five migration steps; the first changes no world and no
  look ([B2.8](#b28-recommendation-and-migration), [4.6](#46-the-block-approach)).
- **A generator in tiers, with no third-party tables.** A neutral scene description feeds a planner that
  names shapes by pixel-free keys (hashed like the slot map). Tier A needs tile ids only (art folded upright,
  measured heights, roofs, ledge lips); tier B adds cell-sized pixel work (tufts, signs, tree hulls); tier C
  models whole structures and is gated by a self-check that re-projects the result into the game's own view
  ([B3.1](#b31-dramaless-shapes-geometry-inventory-of-archetypes-and-rules-ideas-only),
  [4.7](#47-the-shape-generator-pipeline)).
- **Texture packs are ordinary resource packs** with a small manifest, readable keys, optional `.vox` models
  and rule overrides; nothing ships with the mod ([B4](#b41-stable-readable-keys), [4.8](#48-the-texture-pack-format)).
- **Characters:** thick cards by default; carved and rigged figures as options with automatic fallbacks;
  players keep their Minecraft skins ([B5](#b52-thick-cards), [4.9](#49-character-styles)).

### How the two parts meet

One region record. Part A reads its footprint, gates and rules; Part B reads its game, hashes and generator
version to pick the art set, the shape map and the shape table ([4.10](#410-how-parts-a-and-b-meet)).

### The five biggest risks

| # | Risk | Why it matters | First defence |
|---|---|---|---|
| 1 | The generator leaks at the edge (fluids, decoration spill, structures) or breaks on a Minecraft update | It would corrupt Kanto's blocks or flood interiors | Unbreakable foundation, post-decoration repair, counted gates in the build report, direct generator GameTests; every dependency fails at compile time |
| 2 | A way into Kanto that skips a story gate (mounts, pearls, elytra, other mods) | Sequence breaks; the two games diverge | Gates as the only openings, entry decided by the hidden game, server bounce, ride and elytra vetoes |
| 3 | Scoping 37 world-wide behaviours without regressions | "Real gameplay outside" depends on all of them; one guard mixin sits on a hot path | One zone lookup, one client flag, a legacy switch for old worlds, GameTests per rule, a measured budget for the guard |
| 4 | Rendering cost and third-party renderers are unmeasured | The flexible model could be too slow, or wrong under Sodium, Iris or Distant Horizons | A four-variant prototype with pass thresholds before committing; step 1 is also the control |
| 5 | Shape heuristics are judged on art no agent may look at | Trees and houses may look wrong | Scores only: the self-check, report counts, automatic fallback to plain columns, your play-test |

Two smaller ones worth knowing: the dimension-type override applies to every world opened with the mod and
may thin vanilla mob spawning outside Kanto (an inference from the code, with an experiment designed), and
full slots are 96% used, which the block approach stops spending.

---

## 2. Facts (A1 and B1) and corrections to §4

This section has three parts: the corrections to the spec's starting leads in one table (2.1), every lead as
each researcher checked it (2.2), and the facts tables of the as-is audit (2.3 for Part A, 2.4 for Part B).
The audit *answers* (A1.1–A1.7, B1.1–B1.7), including the table of all 37 world-scoped behaviours
([A1.5](#a15-every-behaviour-that-is-world-scoped-or-server-scoped-today)), are in section 3.

### 2.1 Corrections to the spec's §4 and §9

Leads not listed here were confirmed as written (see 2.2). `mc →` is Minecraft 1.21.1; citation conventions
are in [10.1](#101-conventions).

| # | Lead in the spec | Correction | Evidence |
|---|---|---|---|
| 1 | Vanilla's ocean surface is "about 63.0" | The top water block is Y 62 and its surface is at **Y 62.889**. Kanto's water (top 63.5) stands 0.611 above it and Kanto's ground (top 64.0) 1.111 above | mc → `Aquifer.FluidStatus#at` (Aquifer.java:86-88); `FlowingFluid#getOwnHeight` (FlowingFluid.java:452-459); `Scale.java:21-23` |
| 2 | §9: `class KantoNoiseGenerator extends NoiseBasedChunkGenerator` | The class is `final`. Extending it needs one access-widener line. A plain delegating wrapper is not equivalent: `ChunkMap` builds the world's `RandomState` from real noise settings only for `instanceof NoiseBasedChunkGenerator` | mc → NoiseBasedChunkGenerator.java:49; ChunkMap.java:182-186 |
| 3 | §9: `FootprintMask … implements DensityFunction.SimpleFunction` | A `SimpleFunction` never maps its children, so the wrapped vanilla density would have no noises (it would return 0). The wrapper must implement `DensityFunction` and map its child | mc → DensityFunction.java:78-105; RandomState.java:45-91 |
| 4 | "Carvers (up to 8 chunks)" | Carver *starts* are scanned in ±8 chunks, but a carver writes only the chunk being generated, and a tunnel reaches at most 112 blocks | mc → NoiseBasedChunkGenerator.java:263-279; WorldCarver.java:57-59; ChunkPyramid.java:31 |
| 5 | Option (d): treat Kanto's chunks as "old" for vanilla's `Blender` | Whether to blend is decided from **region files on disk**, cached for the session; `BlendingData` cannot be built directly. Unusable in a fresh world | mc → IOWorker.java:45-116; Blender.java:55-75; BlendingData.java:51-110 |
| 6 | The dimension-type override: `min_y -256`, `height 576` | Also `logical_height 576`. It applies to every world opened with the mod, including existing ones. Between −256 and −65 nothing generates (air); bedrock stays at −64…−60. Side effects: void death at Y < −320, 36 chunk sections, and (inferred from the code) fewer useful natural-spawn attempts | `…/dimension_type/overworld.json:9-12`; mc → NoiseSettings.java:54-58; NaturalSpawner.java:284-291 |
| 7 | `SURFACE_MAX_Y` | It is 68 and exclusive: the surface volume is Y 63…67 | `WorldPlan.java:26-27` |
| 8 | `fabric-dimensions-v1` among "world and data" modules | It is not a preset or generator API; generators, density functions and biome sources are registered through vanilla's built-in registries | mc → BuiltInRegistries.java:225-235 |
| 9 | `server/Rules`: adventure on join, speeds, jump zeroed | Incomplete: also on respawn and on `BuildHold` release; Peaceful, locked; the default game type; clear weather and noon at **every** server start; a veto on **all** player damage; Cobblemon's settings | `Rules.java:59-79, 85-119` |
| 10 | `server/KantoGameRules` (daylight, weather, mob spawning, four damage rules, keepInventory, immediate respawn, griefing, fire, vines) | Incomplete: also insomnia, patrols, traders, wardens off and raids disabled; **`doTileDrops`, `doEntityDrops`, `doMobLoot` off**; `projectilesCanBreakBlocks` off; three chat rules; `spawnRadius 0`; **`randomTickSpeed 0`**. All re-applied at every start | `KantoGameRules.java:23-54`; `Rules.java:63, 72` |
| 11 | "Joining, re-linking and snapshots teleport the player onto the hidden cell" | Incomplete: the same teleport follows every battle, every return from creative or spectator, every reconnect, every finished pairing and every new index; and a player standing on no map is teleported back after 500 ms, then every 3 s | `MovementSync.java:71, 83, 153-185, 229-241, 483-514`; `MoveController.java:93` |
| 12 | §9 border state machine | `region.leave` must go to the **hidden game** (it has to be parked and held), not to the server; entering starts at a **gate cell**, not at the footprint's edge; `reachable(C, storyState)` cannot be computed soundly; stopping `move.step` is not enough | [A4.2](#a42-leaving--the-gate-model-and-the-border-state-machine), [A4.3](#a43-entering) |
| 13 | §9 region record: `"id": "kanto@blue"`, gates as `{cell, dir}` | `@` fits neither the protocol's `id` pattern nor a resource path. Use `"id": "kanto"` with `game` as its own field. A gate needs an id, a kind, a cell span and a corridor | `protocol/defs.schema.json:56-62`; mc → ResourceLocation.java:284-286 |
| 14 | "Each slot has a `cube_all` model" | Only **full** slots. Half slots (water) use `tile_half_placeholder`: one 16×8×16 element | `ResourcePackWriter.java:156-163` |
| 15 | "32 families × 256 states (16 full, 16 half)" | Right, with the consequence spelled out: capacity is 4,096 **per shape**; half slots are almost unused (92) while full slots are 96% used (3,926) | `KbBlocks.java:26-27`; `SlotMap.java:34-37`; `build_report.json` |
| 16 | "Effects: `client/fx/` (`SpeechBubbles`, `BlockadeFade`) and `SurfSprite`" | `SurfSprite` is in `client/fx/` too. `BlockadeFade` has no render hook of its own (a per-tick set read by `LivingEntityRendererMixin`) | `client/fx/SurfSprite.java`; `BlockadeFade.java:42-66` |
| 17 | Puppets "turn to face the camera" | About the vertical axis only (yaw); they never pitch | `PuppetRenderer.java:61-62` |
| 18 | §9 flexible-model sketch | The signature is right. It leaves out: `isVanillaAdapter()` must return `false`; it runs on mesh worker threads, so lookups must read immutable snapshots; `pos` is the mesher's mutable cursor; cached meshes hold atlas UVs and must be rebuilt after every resource reload | Fabric `FabricBakedModel.java:51-94`; `TerrainRenderContext.java:83-96` |
| 19 | PLAN §4.3: the story renderer draws "from slot-mapped textures" | It re-renders the tile block state with `renderSingleBlock`, which never reaches the Fabric rendering API | `StoryCellRenderer.java:56-70`; mc → BlockRenderDispatcher.java:89-108 |
| 20 | (not a lead) | All 50,664 story-cell blocks are block entities; the generated pack sits **above** player packs by default (required, `TOP`, not fixed) | `build_report.json`; `GeneratedPack.java:51-53` |
| 21 | §9 manifest: `"textures": {"blue/OVERWORLD/44": …}`, `"sprite:OAK"` | A tile texture is identified by (atlas image, palette, tile), not (game, tileset, tile); sprite ids are engine symbols (`SPRITE_OAK`) | `ResourcePackWriter.java:239-241`; `SlotMap.java:71`; `docs/PROTOCOL.md:291` |
| 22 | §9 carving: `front(x,y) and side(z,y)` | The back view exists and must be used; single-frame sprites need their own rule; the fallback needs measurable criteria | `protocol/export/sprite.schema.json:14-19`; [B5.3](#b53-carved-voxel-figures) |
| 23 | "Does our export carry Gen 1's per-map border block?" | Yes, and the connections. But outdoors the engine draws a *substitute* for it (a tree-wall block by default), which is not exported | `gen1-bridge/export/kb_export.lua:695-704`; `refs/gen1recomp/src/render/TileRenderer.lua:11-32` |
| 24 | Gen1Recomp runs FireRed, LeafGreen and Emerald | They are flagged `beta` | `refs/gen1recomp/src/core/GameVersion.lua:119, 138, 153` |
| 25 | Dramaless Shape's flat south-facing cards | They rely on that renderer's fixed camera yaw; ours turns freely, so the argument only partly transfers | `refs/DRAMALESS_SHAPE-main/lib/SpriteBillboards.lua:1-29` |
| 26 | Cobblemon's riding API exists | And nothing in our code handles riding today: a land ride steps 1.2 blocks, over one-block story cells (not play-tested) | `HorseBehaviour.kt:534-540`; `WorldPlan.java:452-457` |
| 27 | Builder and export class lists | Incomplete: also `BuildOptions`, `BuildRequest`, `BuildStatus`, `BuildTasks`, `BuildReport`, `BuildDiagnostics`; `plan/Box3`, `Warnings`; `pack/PackFiles`; `export/ExportBundle`, `ExportEncounters`, `BundleException` | directory listing |
| 28 | Spawn rules | `walking_level_only.json` hard-codes the ground Y (63) and the layer step (22); it must follow any vertical shift | `walking_level_only.json:7-8` |

### 2.2 Every lead, as each researcher checked it

The four tables below are kept as written by the researchers; some leads were checked by two of them
independently.

#### Leads checked by the world-generation researcher (track 1)

| # | Lead (spec §4 / §9) | Verdict | Evidence / correction |
|---|---|---|---|
| L1 | `kanto.json` is an empty flat overworld (no layers, features or structures; biome `kantobridge:kanto_outdoor`); `#minecraft:normal` lists it | **Confirmed** | `fabric-bridge/src/main/resources/data/kantobridge/worldgen/world_preset/kanto.json:5-13` (`minecraft:flat`, `layers: []`, `lakes: false`, `features: false`, `structure_overrides: []`); tag `…/data/minecraft/tags/worldgen/world_preset/normal.json:2-4`. Additions: nether and end stems are vanilla noise generators (`kanto.json:16-36`); structures are also switched off by `new WorldOptions(randomSeed, false, false)` (`fabric-bridge/src/client/java/dev/kantobridge/client/world/CreateKantoWorldFlow.java:422`) and `generate-structures=false` (`tools/e2e/build_world.py:71`). A flat generator also sets the world's `SpecialWorldProperty.FLAT` (mc → `WorldDimensions#specialWorldProperty` (WorldDimensions.java:104-113)), which changes the client's horizon and clear-colour scale (mc → `ClientLevel.ClientLevelData#getHorizonHeight/getClearColorScale` (ClientLevel.java:925-931)). |
| L2 | `data/minecraft/dimension_type/overworld.json` overrides `minecraft:overworld` with `min_y -256`, `height 576`; applies to every overworld while the mod is loaded | **Confirmed**, with an addition | `fabric-bridge/src/main/resources/data/minecraft/dimension_type/overworld.json:9,11,12` (`height 576`, `logical_height 576`, `min_y -256`). Every other field equals vanilla (mcdata → `dimension_type/overworld.json:2-22`, where `height 384`, `logical_height 384`, `min_y -64`). The purpose is PLAN P4 (`docs/PLAN.md:143`): `Scale.maxLayer(-256)` = 14 layers (`fabric-bridge/src/main/java/dev/kantobridge/world/Scale.java:39-42`). It is in the jar's always-on `data/`, so it applies to every world opened with the mod (new **and existing**): level.dat stores the stem's type by id (mc → `LevelStem.CODEC` (LevelStem.java:12-17)). |
| L3 | Vanilla overworld noise settings: `sea_level 63`, `min_y -64`, `height 384`, aquifers and ore veins on | **Confirmed** | mcdata → `worldgen/noise_settings/overworld.json:2` (`aquifers_enabled`), `:15-16` (`height 384`, `min_y -64`), `:334-335` (`ore_veins_enabled`, `sea_level 63`). |
| L4 | Kanto ground at Y 63 (feet 64), water half blocks top out at 63.5; vanilla ocean surface "about 63.0" | Kanto side **confirmed**; vanilla side **corrected** | Kanto: `Scale.java:21-23` (`SURFACE_Y 64`, `GROUND_Y 63`); water = a half tile block at `feet-1` (`fabric-bridge/src/main/java/dev/kantobridge/builder/plan/WorldPlan.java:440`). **Vanilla: the topmost water block is Y 62 and its surface is at Y 62.889, not 63.0 (nor 62.875).** `Aquifer.FluidStatus#at` returns fluid only for `y < fluidLevel` (mc → Aquifer.java:86-88) with `fluidLevel = seaLevel` (mc → `NoiseBasedChunkGenerator#createFluidPicker` (NoiseBasedChunkGenerator.java:67-73)); a source block's own height is `amount / 9` = 8/9 (mc → `FlowingFluid#getOwnHeight`/`getHeight` (FlowingFluid.java:452-459)). So Kanto's water top (63.5) is **0.611** above the vanilla sea surface and Kanto's ground top (64.0) is **1.111** above it. |
| L5 | Kanto biomes: no precipitation, empty spawners, no carvers or features, silent music | **Confirmed** | `…/data/kantobridge/worldgen/biome/kanto_outdoor.json:2,10-15,17-28` (same in `kanto_interior.json`, `kanto_cave.json`). Additions: no `grass_color`/`foliage_color`; outdoor `fog_color 12638463`, `sky_color 7907327`, interior and cave `0`/`0`; the mod ships **no biome tags**, so no structure's `biomes` tag contains a Kanto biome. |
| L6 | Spawn rules `no_interior_spawns.json`, `walking_level_only.json` | **Confirmed**, with a catch | `…/data/kantobridge/spawn_rules/walking_level_only.json:8` hard-codes the ground Y and the layer step: `math.mod(v.spawnable_position.y - 63, 22) == 0` (it must change if Kanto's level ever shifts, A2.6). |
| L7 | Builder classes (`KantoWorldBuilder`, `BuildTask`, `HeadlessBuild`, `plan/`, `place/`, `pack/`, `export/`) | **Confirmed** (all exist); list incomplete | Also present: `builder/BuildOptions`, `BuildRequest`, `BuildStatus`, `BuildTasks`, `BuildReport`, `BuildDiagnostics`, `BuildPhase`, `BuildListener`; `plan/Box3`, `Warnings`, `PlanException`; `pack/PackFiles`; `export/ExportBundle`, `ExportEncounters`, `BundleException`. `WorldPlan.SURFACE_MAX_Y` = `GROUND_Y + ROOF + 1` = **68, exclusive** (`WorldPlan.java:26-27`), so the surface volume is Y 63…67. |
| L8 | Real full build numbers | **Confirmed** | `local/reports/build_report.json`: `maps_built 220`, `surface_maps 36`, `interiors 184`, `layers 14`, `chunks 2126`, `notes.void_fill_cells 87564`, `story_cells 12666`, `ledge_colliders 3032`, `light_blocks 12114`, `blocks_placed 2485154`, `elapsed_ms 56724`, `throughput.blocks_per_s 49237.3`, `slots_full 3926`, `slots_half 92`, `pack_textures 4301`. Additions: `blocks_cleared 0`, `biome_chunks 2126`. |
| L9 | `ChunkGenerator` has `fillFromNoise(Blender, RandomState, StructureManager, ChunkAccess)`, `buildSurface`, `applyCarvers`, `applyBiomeDecoration`, `createStructures`, `getBaseHeight`, `getBaseColumn`, `getSeaLevel`, `getMinY`, `getGenDepth` | **Confirmed** by `javap` | Exact signatures in A2.1. |
| L10 | "also `NoiseBasedChunkGenerator` …" and §9's `class KantoNoiseGenerator extends NoiseBasedChunkGenerator` | **Corrected: the class is `final`** | `javap`: `public final class net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator` (also mc → NoiseBasedChunkGenerator.java:49). Extending it needs an access widener (`extendable class …`). A plain wrapper (a `ChunkGenerator` that delegates) is **not** equivalent: `ChunkMap` builds the world's `RandomState` from the real noise settings only when `generator instanceof NoiseBasedChunkGenerator`, otherwise from `NoiseGeneratorSettings.dummy()` (mc → `ChunkMap#<init>` (ChunkMap.java:182-186)), and that `RandomState` is what vanilla uses for the spawn search, `/locate`, structure checks and unloaded-biome lookups (mc → MinecraftServer.java:424; ServerLevel.java:283, 319, 1280). |
| L11 | §9 sketch `record FootprintMask(DensityFunction vanilla, …) implements DensityFunction.SimpleFunction` | **Corrected: must not be a `SimpleFunction`** | `SimpleFunction#mapAll` returns `visitor.apply(this)` and never maps children (mc → DensityFunction.java:95-105). `RandomState` wires every noise through `router.mapAll(visitor)` (mc → RandomState.java:45-91); an unmapped child keeps `NoiseHolder.noise == null`, whose `getValue` returns 0.0 (mc → DensityFunction.java:78-93). The wrapped vanilla density would be noise-less. A wrapper must implement `DensityFunction` itself and map its child (as `DensityFunctions.Clamp#mapAll` does, DensityFunctions.java:453-456), and must return a `KeyDispatchDataCodec` from `codec()`. |
| L12 | "Carvers (up to 8 chunks)" (A2.3) | **Made precise** | The generator scans carver *start* chunks in ±8 chunks (mc → `NoiseBasedChunkGenerator#applyCarvers` (NoiseBasedChunkGenerator.java:263-264)), but `WorldCarver#getRange` is 4 (WorldCarver.java:57-59), so a tunnel reaches at most (4·2−1)·16 = 112 blocks (CaveWorldCarver.java:47, CanyonWorldCarver.java:46), and a carver only ever writes into the chunk being generated (`blockStateWriteRadius(0)`, mc → ChunkPyramid.java:31; `configuredWorldCarver.carve(carvingContext, chunk, …)`, NoiseBasedChunkGenerator.java:279). |
| L13 | `Blender`/`BlendingData` (A2.1 option d) | **Confirmed to exist; not usable as-is** | A chunk is "old" when it carries `BlendingData` (mc → `ChunkAccess#isOldNoiseGeneration` (ChunkAccess.java:355-357)); whether to blend at all is decided from **region files on disk** (`blending_data` tag, or a pre-1.18 `DataVersion`), cached per region file and never invalidated in a session (mc → `IOWorker#isOldChunkAround`/`createOldDataForRegion`/`isOldChunk` (IOWorker.java:45-116)). Details in A2.1. |
| L14 | Fabric modules `fabric-biome-api-v1`, `fabric-dimensions-v1`, `fabric-data-attachment-api-v1`, `fabric-game-rule-api-v1` present | **Confirmed** (jars in `fabric-bridge/.gradle/loom-cache/remapped_mods/remapped/net/fabricmc/fabric-api/`) | `fabric-dimensions-v1` (4.0.1) is not a world-preset or generator API; presets, generators, density functions and biome sources are registered through vanilla registries (A2.1). |
| L15 | PLAN §2.2 "340×360-cell bounding box" | **Confirmed** | `local/reports/export_report.json` `layout_box_blocks [170, 180]` (Gen 1 blocks; one is 2×2 cells = 4×4 Minecraft blocks, `Scale.java:15-19`). |

Leads about rules, client movement, story collision, the art pipeline, riding and Gen1Recomp belong
to the other tracks and were not checked here.

#### Leads checked by the runtime researcher (track 2)

Verdicts: **Confirmed** · **Confirmed, incomplete** (true, but it leaves out something that matters here) ·
**Corrected** · **Not mine** (another track owns it; I say what I happened to verify). † = read by a sub-agent of mine
and not re-read by me.

| # | Lead (spec §4 / §9) | Verdict | Evidence, and the correction |
|---|---|---|---|
| 1 | World preset is an empty flat overworld with biome `kanto_outdoor`; `normal.json` lists it | Confirmed | `fabric-bridge/src/main/resources/data/kantobridge/worldgen/world_preset/kanto.json:5-13` (`minecraft:flat`, biome :8, `layers: []` :9, `lakes`/`features` false :10-11, `structure_overrides: []` :12); Nether and End are the normal generators (`:16-36`). `…/data/minecraft/tags/worldgen/world_preset/normal.json:2-4` |
| 2 | The dimension-type override of `minecraft:overworld` (`min_y -256`, `height 576`) | Confirmed | `…/data/minecraft/dimension_type/overworld.json:9`, `:11` (`logical_height` 576), `:12`. Consequences are track 1's (A1.2) |
| 3 | Vanilla noise settings: `sea_level 63`, `min_y -64`, `height 384`, aquifers and ore veins on | Confirmed | extracted vanilla data `data/minecraft/worldgen/noise_settings/overworld.json:2`, `:15-16`, `:334-335` |
| 4 | Kanto's ground at Y 63 (feet 64); water cells are half blocks topping out at 63.5; they don't meet the vanilla sea flush | Confirmed | `fabric-bridge/src/main/java/dev/kantobridge/world/Scale.java:21-23` (`SURFACE_Y` 64, `GROUND_Y` 63); water is a half block one below the feet (`…/builder/plan/WorldPlan.java:440`; half shape `…/block/KbBlocks.java:30`). A vanilla water source is drawn 8/9 of a block high (mc → net.minecraft.world.level.material.FlowingFluid, FlowingFluid.java:72). ⚠ Not confirmed by me: that the sea's top water block is at Y 62 (track 1 owns the generator) |
| 5 | Kanto biomes: no precipitation, empty spawners, no carvers or features, silent music | Confirmed | `…/data/kantobridge/worldgen/biome/kanto_outdoor.json:2`, `:10-15`, `:17-18`, `:19-28`. Addition: these biomes are the **only** thing that silences Minecraft's music (no client code does), so it plays outside (A1.5 row 9) |
| 6 | Spawn rules `no_interior_spawns.json`, `walking_level_only.json` | Confirmed | Both exist under `…/data/kantobridge/spawn_rules/`. Addition: the second hard-codes `math.mod(y - 63, 22)` (`walking_level_only.json:7-8`), i.e. surface Y and layer step |
| 7 | Builder classes (`KantoWorldBuilder`, `plan/`, `place/`, `pack/`, `export/`) | Not mine | I used and confirmed `BuildTask`, `HeadlessBuild`, `plan/WorldPlan`, `plan/HeightRules`, `plan/HabitatPlanner` |
| 8 | The real full build's numbers | Confirmed | `local/reports/build_report.json`: maps 220 (36 surface, 184 interior, 14 layers), chunks 2,126, void-fill cells 87,564, story cells 12,666, ledge colliders 3,032, light blocks 12,114, blocks placed 2,485,154 in 56,724 ms (49,237 blocks/s), slots 3,926 full and 92 half, 4,301 pack textures. Also there and used below: 50,664 story-cell blocks, 296 habitat blocks, 46 pools |
| 9 | `server/Rules`: adventure on join, walk 0.1737, bike 0.3474, jump zeroed | Confirmed, incomplete | `…/server/Rules.java:45-47`, `:85-110`. It also: applies on **respawn** and on `BuildHold` release (`:65`, `:67`); sets the `walkingSpeed` ability and clears sprinting (`:99`, `:108`); locks the difficulty at **Peaceful** (`:74-75`); sets the server's default game type (`:76`); sets clear weather and noon at **every** server start (`:77-79`); **vetoes all damage to every player** except what bypasses invulnerability (`:66`, `:117-119`), which is wider than the four damage game rules; applies Cobblemon's settings (`:59-62`, `:73`). "The rules hold on every server running this mod" (`:38`) |
| 10 | `server/KantoGameRules`: daylight, weather, mob spawning off; four damage rules off; keepInventory, immediate respawn; mob griefing, fire tick, vine spread off | Confirmed, incomplete | `…/server/KantoGameRules.java:23-49`, `:53-54`. Left out: `doInsomnia`, patrols, traders, wardens off and `disableRaids` on (`:26-30`); **`doTileDrops`, `doEntityDrops`, `doMobLoot` off** and `projectilesCanBreakBlocks` off (`:42-45`); three chat rules (`:47-49`); `spawnRadius` 0 and **`randomTickSpeed` 0** (`:53-54`). They are re-applied at every server start (`Rules.java:63`, `:72`), so a `/gamerule` change does not survive a restart |
| 11 | Also `CobblemonSettings`, `BuildHold`, `WarpService`, `SaveService`, `SessionService`, `SavePairService` | Confirmed | All in `…/server/`; what each scopes is in A1.5 rows 13, 18, 20–23 |
| 12 | Client movement follows the hidden game only in "Kanto worlds" (index present), never in creative or spectator | Confirmed | `fabric-bridge/src/client/java/dev/kantobridge/client/move/MoveController.java:89-90`; `…/move/MoveRuntime.java:123` |
| 13 | Joining, re-linking and snapshots teleport the player onto the hidden cell (`warp.request`) | Confirmed, incomplete | `…/move/MovementSync.java:179-185` (join), `:166-176` (re-link), `:229-241` (snapshot — only one with `ingame`), `:505-514`, `:602-618` → `warp.request`. Also: a new index (`:153-162`); and "linked" is `following ∧ connected ∧ ingame ∧ ¬battle` (`MoveController.java:93`), so the same teleport follows **every battle**, every return from creative or spectator, every reconnect and every finished pairing. And the drift rule teleports a player who stands on no map after 500 ms (`MovementSync.java:71`, `:483-499`; A1.6) |
| 14 | The client holds the player when the hidden map isn't built | Confirmed | `…/move/InputLock.java:121-123` (`UNBUILT_MAP`); `MovementSync.java:385-388` |
| 15 | Story collision: `story/StoryState`, `story/StoryRules`, blocks `StoryCellBlock`, `StoryTileBlock`, `LedgeColliderBlock` | Confirmed | All exist (`…/story/`, `…/block/`). The per-player decision is `StoryCellBlock.closedFor` (`StoryCellBlock.java:67-80`) through `StoryStates.governingPlayer` (`StoryStates.java:45-50`) |
| 16 | Art pipeline: slot map path, generated pack, `ClientWorldIndex`, puppets, `client/fx/` | Not mine | Confirmed in passing: `<world>/kantobridge/slot_map.json` (`…/builder/BuildTask.java:47-49`); `…/client/world/ClientWorldIndex.java:26-27`; `client/fx/` holds `SpeechBubbles`, `BlockadeFade` **and** `SurfSprite` |
| 17 | Fabric API modules present (events-interaction, entity-events, data-attachment, game-rule, dimensions, biome) | Confirmed | Sources jars under `fabric-bridge/.gradle/loom-cache/remapped_mods/remapped/net/fabricmc/fabric-api/`: `fabric-events-interaction-v0` 0.7.14+ba9dae0619, `fabric-entity-events-v1` 1.8.0+2b27e0a419, `fabric-lifecycle-events-v1` 2.6.0+0865547519, `fabric-data-attachment-api-v1` 1.4.7+5b36e0f719, `fabric-game-rule-api-v1` 1.0.53+6ced4dd919, `fabric-dimensions-v1` 4.0.1+65213ef819, `fabric-biome-api-v1` 13.0.31+d527f9fd19. Additions that shape the design: there is an elytra veto (`EntityElytraEvents.ALLOW`); there is **no** event that cancels an entity spawn, a status effect or a teleport†; `fabric-dimensions-v1` offers no teleport helper in this version† |
| 18 | `Attributes.SCALE` and `STEP_HEIGHT` exist | Confirmed | mc → net.minecraft.world.entity.ai.attributes.Attributes (Attributes.java:120-122, :132-134). Addition: a living mount controlled by a player steps up at least 1.0 regardless (mc → net.minecraft.world.entity.LivingEntity#maxUpStep, LivingEntity.java:3645-3648) |
| 19 | Cobblemon 1.8.1 has a riding API (land, liquid, air); Lapras has riding data | Confirmed | `refs/cobblemon/common/src/main/kotlin/com/cobblemon/mod/common/api/riding/RidingStyle.kt:18-21`; `refs/cobblemon/common/src/main/resources/data/cobblemon/species/generation1/lapras.json:53-103`†. Additions: mounting posts the cancelable `RIDE_EVENT_PRE` (`…/api/events/CobblemonEvents.kt:174`); **nothing in our code handles riding today** (A4.8) |
| 20 | Gen1Recomp runs many games; its Gen 3 engine has per-cell elevation | Not mine | The file exists (`refs/gen1recomp/src/core/game3/collision.lua`); not read |
| 21 | Prior art (Dramaless Shape, Kanto in First Person) | Not mine | Not read |
| 22 | §9 border state machine | **Corrected** | (a) "`region.leave` to the server" — leaving is a request to the **hidden game** (M→G `region.leave`, answered by the existing `act.result`), because the hidden player has to be parked and held; the server learns it from a separate C→S `region.state` (A4.2). (b) "OUTSIDE → ENTERING when the feet cross into footprint cell C" — the footprint has an apron anyone may walk on; entering starts at a **gate cell**, not at the footprint's edge. (c) "a hidden-only warp to C when `reachable(C, storyState)`" — reachability cannot be computed soundly by the server or by gen1-bridge: badge guards and step triggers are script hooks run on step completion (`refs/gen1recomp/src/world/OverworldController.lua:4691-4707`, `:4909-4967`), not data. Replaced by "the gate you left through, plus gates this save has already used" (A4.3). (d) "stop sending `move.step`" is necessary but not sufficient: an unconnected edge refuses the step (`refs/gen1recomp/src/world/Collision.lua:56-59`), and the hidden game must also be held (wild encounters, trainers, day-care steps) while the player is away |
| 23 | §9 region record | **Corrected** | The id `kanto@blue` fits neither the protocol's `id` pattern `^[A-Za-z0-9_.:-]+$` (`protocol/defs.schema.json:56-62`) nor a resource path (mc → net.minecraft.resources.ResourceLocation#validPathChar, ResourceLocation.java:284-286). Use `"id": "kanto"` with `game` as its own field; a gate needs an id, a kind and a cell span, not just `{cell, dir}` (A8.1) |
| 24 | A4.1's premise: an outer edge without a connection refuses the step (`player.blocked{bounds}`) | Confirmed | `refs/gen1recomp/src/world/Collision.lua:56-59` (verdict `bounds`), `refs/gen1recomp/src/world/OverworldController.lua:1733-1737`, `:2047-2068`; `gen1-bridge/act/kb_move.lua:57`, `:160-171` (A4.1) |
| 25 | A7.2's premise: only `party.give`, `party.take` and the daycare messages count as save-pair operations | Confirmed | The only callers of `SavePairService.operation` are `fabric-bridge/src/main/java/dev/kantobridge/pokemon/PartyService.java:246`, `:337` and `…/pokemon/DaycareService.java:135`, `:199` (A7.2) |

**Two names that are easy to assume and do not exist in 1.21.1** (found while answering A5.7; not leads): there is no
`FireBlock#tryCatchFire` — the method is the private `FireBlock#checkBurnOut(Level, BlockPos, int, RandomSource, int)`
(FireBlock.java:239) — and no `tntExplodes` game rule (only `tntExplosionDropDecay`, GameRules.java:176-177).

#### Leads checked by the block-approach researcher (3a)

Only the leads that touch this track. "✔" = confirmed, "✎" = corrected.

| # | Lead (spec §4) | Result | Evidence |
|---|---|---|---|
| L1 | "Tile blocks: 32 families × 256 states (16 full, 16 half)" | ✔ with a wording fix: **32 blocks = 16 full + 16 half families**, 256 states each = 8,192 states. Capacity **per shape** is 16 × 256 = **4,096** | `fabric-bridge/src/main/java/dev/kantobridge/block/KbBlocks.java:26-27,52-55`; `block/TileBlock.java:21-22`; `world/SlotMap.java:34-37`; JUnit `src/test/java/dev/kantobridge/block/TileBlockTest.java` (`thirtyTwoFamiliesOf256States`) |
| L2 | "Each slot has a `cube_all` model, the same tile art on all six faces (`ResourcePackWriter`)" | ✎ **Only FULL slots are `cube_all`.** HALF slots use parent `kantobridge:block/tile_half_placeholder`: one 16×8×16 element, top/bottom show the whole tile, the four sides show its lower half (`uv 0,8,16,16`), the top face has no `cullface` | `builder/pack/ResourcePackWriter.java:156-163`; `src/main/resources/assets/kantobridge/models/block/tile_half_placeholder.json:7-80` |
| L3 | "The slot map: `<world>/kantobridge/slot_map.json`, computed from the whole export" | ✔ | `builder/BuildTask.java:47-49,260-263`; `builder/plan/ArtCatalog.java:55-140` (iterates `bundle.ids("map")`, never the build filter) |
| L4 | "The generated pack: `<gameDir>/kantobridge/resourcepack/`, injected by `mixin/client/BuiltInPackSourceMixin` and managed by `client/packs/`" | ✔ | `client/packs/GeneratedPack.java:37-39,46-62`; `mixin/client/BuiltInPackSourceMixin.java:20-27` |
| L5 | "The world index on the client: `client/world/ClientWorldIndex`" | ✔ and it also holds the world's **slot map** (`world.slots`) | `client/world/ClientWorldIndex.java:26-27,40-47,79-99` |
| L6 | "Effects: `client/fx/` (`SpeechBubbles`, `BlockadeFade`) and `SurfSprite`" | ✎ `SurfSprite` is in `client/fx/` too. `BlockadeFade` has no render hook of its own: it is a per-tick set consumed by `LivingEntityRendererMixin` | `client/fx/SurfSprite.java:1,81`; `client/fx/BlockadeFade.java:42-44,64-66`; `mixin/client/LivingEntityRendererMixin.java:26-48` |
| L7 | "Real full build: slots 3,926 of 4,096 full and 92 half; 4,301 pack textures" | ✔ plus: 4,301 = 4,005 tile + 296 NPC/surf textures; 23 animated; **half capacity is also 4,096** (92 used) | `report → slots_full, slots_half, pack_textures, tile_textures, npc_textures, animated_textures` |
| L8 | "12,666 story cells and 3,032 ledge colliders; 12,114 light blocks; 2,485,154 blocks placed" | ✔ plus `story_cell_blocks` 50,664 (= 12,666 × 4: every story cell is 2×2 blocks, **each a block entity**); by type: water 9,301, boulder_area 2,438, object 765, tile 162 | `report → story_cells, story_cells_by_type, story_cell_blocks, ledge_colliders, light_blocks, blocks_placed`; `builder/BuildTask.java:371` |
| L9 | "Fabric API 0.116.17 modules present: `fabric-renderer-api-v1` (`FabricBakedModel`, `QuadEmitter`, `RenderContext`), `fabric-renderer-indigo`, `fabric-block-view-api-v2` (`FabricBlockView`, `RenderDataBlockEntity`), `fabric-model-loading-api-v1`" | ✔ (versions above). Also present and useful: `fabric-blockrenderlayer-v1`, `fabric-rendering-v1` (`WorldRenderEvents`, `InvalidateRenderStateCallback`, `AtlasSourceTypeRegistry`), `fabric-data-attachment-api-v1`. The mod depends on `fabric-api: *`, so all are available at runtime | directory listing of `remapped_mods/remapped/net/fabricmc/fabric-api/`; `fabric-bridge/src/main/resources/fabric.mod.json` (`depends`) |
| L10 | §9 seed sketch "A flexible block's model": `emitBlockQuads(BlockAndTintGetter view, BlockState state, BlockPos pos, Supplier<RandomSource> random, RenderContext ctx)` with `mesh.outputTo(ctx.getEmitter())` | ✔ signature and calls exist exactly so. ✎ Four things the sketch leaves out: (a) the model must override `isVanillaAdapter()` to return `false`; (b) it runs on chunk-mesh worker threads, so `ClientRegions.shapeAt`/`ClientStory.closed` must read immutable snapshots; (c) `pos` is the mesher's *mutable* cursor (never retain it); (d) cached meshes hold atlas UVs, so they must be rebuilt after every resource reload | `fapi:fabric-renderer-api-v1 → FabricBakedModel#emitBlockQuads (FabricBakedModel.java:94)`, `#isVanillaAdapter (:51-53)` *javap*; `Mesh#outputTo (Mesh.java:46)`; `RenderContext#getEmitter (RenderContext.java:56)`; `fapi:fabric-renderer-indigo → TerrainRenderContext#tessellateBlock (TerrainRenderContext.java:83-96)`; `mc → SectionCompiler#compile (SectionCompiler.java:55)` |
| L11 | "Puppets … `FacingFrames`" (3b's) | not audited here; note only that `SurfSprite` reuses `FacingFrames` and `Puppet` constants | `client/fx/SurfSprite.java:155-163` |

New facts that correct assumptions elsewhere in the spec (details in the sections named):

- **PLAN §4.3 says the story renderer draws "from slot-mapped textures"**; precisely, it re-renders the *tile block state*
  of the closed art's slot with `BlockRenderDispatcher#renderSingleBlock`, which bypasses the Fabric Rendering API
  (B1.3, B2.6).
- **"No client GameTests on 1.21.1"** — confirmed (the tests note after B8.2).
- **`ModelManager#requiresRender`** skips re-meshing when two states share a model group; resolver-driven states have no
  group, so every state change re-meshes (B2.1).
- **Face culling is cached per state pair**, so a block cannot have per-position occlusion (B2.4).

#### Leads checked by the shapes, packs and characters researcher (3b)

| # | Lead (spec §4) | Verdict | Evidence / correction |
|---|---|---|---|
| L1 | Tile blocks: 32 families × 256 states (16 full, 16 half) | **Confirmed** | `fabric-bridge/src/main/java/dev/kantobridge/block/KbBlocks.java:27` (`FAMILIES_PER_SHAPE = 16`), `:52-55`; properties `hi`/`lo` 0..15 `block/TileBlock.java:21-22` |
| L2 | "Each slot has a `cube_all` model, the same tile art on all six faces" | **Corrected (half true)** | Only **full**-shape slots use `minecraft:block/cube_all` (`builder/pack/ResourcePackWriter.java:156-158`). **Half** slots use parent `kantobridge:block/tile_half_placeholder` with textures `particle` + `tile` (`ResourcePackWriter.java:159-163`); that parent is one 16×8×16 element whose side faces show the lower half of the tile (`src/main/resources/assets/kantobridge/models/block/tile_half_placeholder.json`) |
| L3 | Slot map `<world>/kantobridge/slot_map.json`, computed from the whole export | **Confirmed** | `builder/plan/ArtCatalog.java:24-26, 55-140`; `world/SlotMap.java:27-29, 157-170`; `docs/PROTOCOL.md:720-725` |
| L4 | Generated pack at `<gameDir>/kantobridge/resourcepack/`, injected by `BuiltInPackSourceMixin`, managed by `client/packs/` | **Confirmed** | `client/packs/GeneratedPack.java:37-39, 46-53, 79-87`; `mixin/client/BuiltInPackSourceMixin.java:20-27`. Added detail: the pack is `required`, default position `TOP`, **not** fixed-position (`GeneratedPack.java:51-53`; `mc → net.minecraft.server.packs.PackSelectionConfig (PackSelectionConfig.java:5)`) |
| L5 | Puppets: upright 2×2-block billboards that turn to face the camera, frame picked relative to the camera | **Confirmed**, with one precision | `client/puppet/PuppetRenderer.java:30-31, 56-68`; `client/puppet/FacingFrames.java:32-42`. The card turns about the **vertical axis only** (yaw); it never pitches (`PuppetRenderer.java:61-62`) |
| L6 | "Effects: `client/fx/` (`SpeechBubbles`, `BlockadeFade`) and `SurfSprite`" | **Corrected (location)** | `SurfSprite` is also in `client/fx/`: `fabric-bridge/src/client/java/dev/kantobridge/client/fx/SurfSprite.java` |
| L7 | Builder `export/` holds `ExportMap, ExportTileset, ExportImage, ExportPalettes, ExportField, ExportLayout, ExportSwaps, CellFlags` | **Confirmed, incomplete** | The package also holds `ExportBundle` (the reader, `builder/export/ExportBundle.java`), `ExportEncounters` and `BundleException` |
| L8 | Real full build numbers (220 maps, 36 surface, 184 interiors, 14 layers, 2,126 chunks, 87,564 void-fill cells, 12,666 story cells, 3,032 ledge colliders, 12,114 light blocks, 2,485,154 blocks in ~57 s, 49k blocks/s, slots 3,926 full + 92 half, 4,301 pack textures) | **Confirmed** | BR:5-10, 13, 21-22, 34, 36, 40-41, 45, 47, 49, 53 |
| L9 | Fabric rendering modules present (`fabric-renderer-api-v1`, `fabric-renderer-indigo`, `fabric-block-view-api-v2`, `fabric-model-loading-api-v1`) | **Confirmed** (presence only; 3a owns their use) | Remapped jars under `fabric-bridge/.gradle/loom-cache/remapped_mods/remapped/net/fabricmc/fabric-api/`: `fabric-renderer-api-v1-…-client/3.4.1+b4d813fc19`, `fabric-renderer-indigo-…-client`, `fabric-block-view-api-v2-…`, `fabric-model-loading-api-v1-…-client/2.1.0+b4d813fc19`; also `fabric-rendering-v1-…-client/5.2.1+f44c2eea19` and `fabric-transitive-access-wideners-v1-…/6.2.0+45b9699719` |
| L10 | Gen1Recomp runs Red/Blue/Yellow, Gold/Silver/Crystal, FireRed/LeafGreen/Emerald | **Confirmed**, with a caveat | `refs/gen1recomp/src/core/GameVersion.lua:22-169`. FireRed, LeafGreen and Emerald carry `beta = true` (`:119, :138, :153`). Engines: `gen1`, `gs`, `crystal`, `game3` (`:204-207`) |
| L11 | Gen 3 engine has a per-cell elevation (`core/game3/collision.lua`) | **Confirmed** | `refs/gen1recomp/src/core/game3/collision.lua:926-952` (`elevationOn`, `elevationMismatchOn`, `nextElevation`); the value lives in the layout cell `{mid, coll, elev}` (`refs/gen1recomp/src/core/game3/layout_native.lua:26-27, 53-67, 96-98`) |
| L12 | Dramaless Shape draws characters as flat south-facing cards on purpose (`SpriteBillboards.lua:1-30`) | **Confirmed** | `refs/DRAMALESS_SHAPE-main/lib/SpriteBillboards.lua:1-29`. Precision: the card "always faces south" and only leans back by the camera's pitch (`:23-26`), which works because that renderer's camera yaw is fixed; ours turns freely (see B5.5) |
| L13 | Kanto in First Person textures risers and ceilings from each room's own art (`payload_ceiling.lua:1-40`) | **Confirmed** | `refs/kanto-first-person/payload_ceiling.lua:11-21, 30-33` |
| L14 | `Attributes.SCALE` and `STEP_HEIGHT` exist (touches B5.6) | **Confirmed** | `mc → net.minecraft.world.entity.ai.attributes.Attributes#SCALE (Attributes.java:120)`, `#STEP_HEIGHT (:132)` |
| S1 | §9 manifest sketch: `"textures": {"blue/OVERWORLD/44": …}` | **Corrected** | Today a tile texture is identified by **(atlas image id, palette, tile)**, not (game, tileset, tile): `ResourcePackWriter.java:239-241`, `SlotMap.java:71`. Several tilesets share one atlas image and every texture is baked once per SGB palette, so a key needs the atlas (not the tileset) and either a palette qualifier or a palette-free override rule (B4.1) |
| S2 | §9 manifest sketch: `"models": {"sprite:OAK": …}` | **Corrected** | Sprite ids are engine symbols `SPRITE_*` (`docs/PROTOCOL.md:291`; `protocol/export/sprite.schema.json:11`), so the key is `sprite:SPRITE_OAK` |
| S3 | §9 carving sketch: `voxel = front(x,y) and side(z,y)`; fall back "when thinner than 2 voxels across more than N rows" | **Replaced** in B5.3 | The sheet gives three views per pose (down, up, left; right is left mirrored: `protocol/export/sprite.schema.json:14-19`), so the back view is available and must be used both as a constraint and as the paint source for the back; single-frame sprites (item ball, boulder, …) have no side view at all and need their own rule; the fallback needs measurable criteria |

### 2.3 Part A facts: the builder and world creation

The facts table, the two sequence diagrams asked for by A1.1, and every assumption of the empty flat
world. The table of all world-scoped behaviours is the answer to
[A1.5](#a15-every-behaviour-that-is-world-scoped-or-server-scoped-today).

#### Facts table

| Topic | Fact | Source |
|---|---|---|
| Preset → generator chain | A world preset is a map `dimensions: {level stem key → LevelStem{type, generator}}` and must contain the overworld | mc → `WorldPreset.DIRECT_CODEC`/`requireOverworld` (WorldPreset.java:19-25, 52-54); `LevelStem.CODEC` (LevelStem.java:11-17) |
| | `generator` is decoded by `ChunkGenerator.CODEC` = `BuiltInRegistries.CHUNK_GENERATOR.byNameCodec().dispatchStable(ChunkGenerator::codec, identity)`; vanilla registers `noise`, `flat`, `debug` | mc → ChunkGenerator.java:81; ChunkGenerators.java:10-14; BuiltInRegistries.java:226 |
| | New world (client): `createFreshLevel(name, LevelSettings, WorldOptions, Function<RegistryAccess, WorldDimensions>, Screen)` bakes the dimensions returned by the function; ours returns the preset's `createWorldDimensions()` | mc → WorldOpenFlows.java:87-116; `CreateKantoWorldFlow.java:427, 436-438` |
| | New world (dedicated): `level-type` names a preset; `generator-settings` is honoured **only** for `minecraft:flat` | mc → `DedicatedServerProperties.WorldDimensionData#create` (DedicatedServerProperties.java:208-233) |
| | Existing world: dimensions are decoded from level.dat `WorldGenSettings` (`WorldOptions` + `dimensions`) against the freshly loaded registries | mc → LevelStorageSource.java:130-141; WorldGenSettings.java:10-14 |
| | The overworld generator instance is the one in the `LEVEL_STEM` registry; `createLevels` builds the `ServerLevel` from it; `ChunkMap` creates `RandomState` and calls `generator.createState(...)` | mc → MinecraftServer.java:356-370; ChunkMap.java:182-188 |
| Today's generator | Flat, void, one biome; flat ⇒ `SpecialWorldProperty.FLAT` | `kanto.json:5-13`; mc → WorldDimensions.java:104-113 |
| Dimension type | `min_y -256`, `height 576`, `logical_height 576` for `minecraft:overworld` in every world | `…/dimension_type/overworld.json:9-12` |
| Scale | cell = 2×2 blocks; Gen 1 block = 4×4 blocks; `SURFACE_Y 64`; `GROUND_Y 63`; `LAYER_STEP 22`; `feetY(k) = 64 − 22k`; `maxLayer(minY) = floorDiv(63 − minY, 22)` | `Scale.java:15-42` |
| Heights | wall/tree 2, roof/cliff 4, void fill 3, counter 1, interior wall 2 | `fabric-bridge/src/main/java/dev/kantobridge/builder/plan/HeightRules.java:17-25` |
| Surface volume | bounding box of the built surface maps, inflated by `SURFACE_RING` 12 blocks, Y 63…67 | `WorldPlan.java:24-27, 254-265` |
| Interior volume | map footprint + 1-block ring, from the floor block (`feet−1`) to the ceiling (`feet+3`) | `WorldPlan.java:29, 248-251`; `fabric-bridge/src/main/java/dev/kantobridge/builder/plan/InteriorPacker.java:26, 78-80` |
| Layout root | `PALLET_TOWN` cell (0,0) at block (`originX`, `originZ`) = (0, 0) by default | `fabric-bridge/src/main/java/dev/kantobridge/builder/plan/WorldPlanner.java:40, 97-117`; `fabric-bridge/src/main/java/dev/kantobridge/builder/BuildOptions.java:35` |
| Placer | flags 18 (seen) / 16 (unseen); one FULL-level region ticket per chunk; ≤ 4 chunks waiting for light; biomes rewritten per chunk | `fabric-bridge/src/main/java/dev/kantobridge/builder/place/BlockPlacer.java:49-53, 207-216, 274-280` |
| Report | real build: 2,126 chunks, 2,485,154 blocks placed, 0 cleared, 87,564 void-fill cells, 14 layers | `local/reports/build_report.json` |

#### A1.1 sequence diagrams (as the code runs today)

**(a) The user's flow: title-screen "Create Kanto world"** (`CreateKantoWorldFlow.java`)

```
Client thread                               Worker thread            Integrated server thread
-------------                               -------------            ------------------------
CHECK   bridge link connected? (296-313)
EXPORT  startWorker (316-326) ------------> ExportClient.run -> <gameDir>/kantobridge/export/<game>/ (328-347)
PACK                                        PackGenerator.generate(bundle, packDir) (351-352)
RELOAD  PackReload.reload (387-388); wait (391-407)
CREATE  createLevel (410-434):
          folder = KantoWorldNames.free(...)                                   (417)
          LevelSettings(folder, ADVENTURE, hardcore=false, PEACEFUL,
                        commands=true, KantoGameRules.create(), DEFAULT)       (420-421)
          WorldOptions(randomSeed, generateStructures=false, bonusChest=false) (422)
          BuildRequest.submit(folder, bundle, maps=null, defaults.withWritePack(false))  (424)  [static slot]
          createFreshLevel(folder, settings, options, kantoDimensions, screen) (427)
            -> preset kantobridge:kanto .createWorldDimensions()               (436-438)
            -> WorldLoader: registries; dimensions baked; level.dat data; MinecraftServer constructed
                                                                     runServer:
                                                                       SERVER_STARTING  (fapi MinecraftServerMixin.java:45-48)
                                                                         Rules: CobblemonSettings.apply (Rules.java:59)
                                                                       initServer -> loadLevel (mc MinecraftServer.java:329-339)
                                                                         createLevels: new ServerLevel(flat generator)   (356-370)
                                                                           ChunkMap: RandomState.create(dummy), createState (ChunkMap.java:182-188)
                                                                           ensureStructuresGenerated                     (ServerLevel.java:266)
                                                                         setInitialSpawn (void world: no ground found)   (377, 419-463)
                                                                         prepareLevels: spawn chunks (void)              (479-…)
                                                                       SERVER_STARTED   (fapi MinecraftServerMixin.java:50-53)
                                                                         HeadlessBuild.onStarted (HeadlessBuild.java:54-67):
                                                                           BuildRequest.take(levelId) ; startRequested (104-137):
                                                                             difficulty PEACEFUL locked ; BuildHold.begin(server)
                                                                             ExportBundle.open ; KantoWorldBuilder.start (143-161)
                                                                         Rules.applyServer, WorldIndexSync.load, HabitatService.onStarted …
LOADING player joins as a held spectator (BuildHold.java:98-111)
BUILD   progress screen (463-494)                        "kantobridge-builder" thread:  BuildTask.prepare (BuildTask.java:153-170)
                                                           WorldPlanner.plan(bundle, Options(maps, originX, originZ, minBuildHeight))
                                                           (pack skipped: writePack=false) ; previousIndex(worldDir)
                                                                     tickPlanning (207-238): HabitatService.ensurePools ; new BlockPlacer ;
                                                                        placeSpawnPlatform ; level.setDefaultSpawnPos(spawn)
                                                                     tickPlacing (240-253): BlockPlacer.tick, 25 ms per tick, per chunk:
                                                                        addRegionTicket ; level.getChunk (generates the void chunk to FULL) ;
                                                                        BiomeWriter.apply ; setBlock(…, 18|16) top-down ; habitats ;
                                                                        lightEngine.waitForPendingTasks ; removeRegionTicket
                                                                     finish (255-290): write slot_map.json, world_index.json, datapack,
                                                                        build_report.json(+diagnostics) ; WorldIndexSync.publish
                                                                     listener.onFinished -> BuildHold.release: teleport to spawn, game mode back
DONE    screen closes (470-484)
```

**(b) The agent's headless build** (`tools/e2e/build_world.py`)

```
build_world.py                                         Dedicated dev server (gradlew runServer)
--------------                                         ---------------------------------------
export_bundle(): launch test Gen1Recomp (driver),      
  gradlew exportCli -> local/minecraft/kantobridge/export/<game>/   (131-150)
_write_server_properties(world)  (103-108):
  level-name=saves/<world>, level-type=kantobridge\:kanto, generate-structures=false,
  gamemode=adventure, difficulty=peaceful, online-mode=false, spawn-protection=0, max-tick-time=-1 (64-73)
gradlew runServer -Pkb.build=<bundle> [-Pkb.buildMaps=…] -Pkb.buildReport=<file> -Pkb.buildExit=true (160-164)
                                                        Main: WorldLoader; level-type -> preset.createWorldDimensions()
                                                              (mc DedicatedServerProperties.java:208-233)
                                                        SERVER_STARTING ; initServer -> loadLevel (void world, spawn chunks)
                                                        SERVER_STARTED -> HeadlessBuild.onStarted (HeadlessBuild.java:68-91):
                                                              reads -Dkantobridge.build(.maps/.report/.exit) ; start(...)
                                                        BuildTask as in (a) (pack written here: writePack=true)
                                                        onFinished/onFailed -> finish(): server.halt ; exit code 0/1 (174-201)
check_report(): maps_built, overlaps, story_cells, warnings, pack_textures, world_index, maps_unplaced,
                habitat_blocks, pools (171-187)
```

#### Every assumption of the empty flat world (A1.1)

| # | Assumption | Where | What breaks in a vanilla-noise world |
|---|---|---|---|
| F1 | The overworld generator is the void flat preset | `kanto.json:5-13`; `CreateKantoWorldFlow.java:93-94, 436-438`; `tools/e2e/build_world.py:65` | A new preset and (for the server) a new `level-type` are needed |
| F2 | No structures anywhere | `CreateKantoWorldFlow.java:422`; `build_world.py:71`; `kanto.json:12` | Must become `generateStructures=true`, with a filter around Kanto (A2.2) |
| F3 | The world is void until built, so joining players would fall | `fabric-bridge/src/main/java/dev/kantobridge/server/BuildHold.java:20-28`; spawn platform `BlockPlacer.java:153-162`, `WorldPlan.java:367-385` | Still useful (Kanto isn't built yet) but the reason changes |
| F4 | Nothing exists outside the builder's volumes, so only the volumes are cleared | `BlockPlacer.java:39-40`; `WorldPlan.java:233-245, 324-341` (`own` only inside volumes); real `blocks_cleared 0` | Terrain above Y 67, below Y 63 and around interior boxes would remain |
| F5 | Kanto's ground is a one-block sheet over void; interiors float in void | `WorldPlan.java:439` (one block at `feet−1`); nothing is planned below it | Under-ground must be defined (A2.4) |
| F6 | The whole column from `min_y` up is free for interior layers | `fabric-bridge/src/main/java/dev/kantobridge/builder/BuildTask.java:97, 156-157`; `WorldPlanner.java:124`; `Scale.java:39-42` | Vanilla terrain occupies −64…~320; below −64 stays empty (A6.3) |
| F7 | Interiors may be placed anywhere horizontally (under the door, sideways in 16-block rings up to 64 rings, deep spill up to 128 rings); nothing ties them to the surface box | `InteriorPacker.java:28-32, 86-126`; `WorldPlanner.java:321-322` | Interiors can lie under vanilla terrain outside Kanto (A1.3) |
| F8 | Every biome cell not rewritten is already `kanto_outdoor` (the flat generator's `FixedBiomeSource`) | `fabric-bridge/src/main/java/dev/kantobridge/builder/place/BiomeWriter.java:46-56` (falls back to `chunk.getNoiseBiome`); `WorldPlan.java:300-316` (only Y 56…87 on the surface, `feet−4…feet+7` per interior) | Cells outside those boxes would keep vanilla biomes (mob and Pokémon spawning, rain, sky) |
| F9 | Habitat blocks sit in void 2 blocks under the ground; removing one leaves air | `fabric-bridge/src/main/java/dev/kantobridge/builder/plan/HabitatPlanner.java:31-35, 102`; `fabric-bridge/src/main/java/dev/kantobridge/server/HabitatService.java:173-186, 215-221` | They would replace whatever fills the under-ground; removal must restore the fill |
| F10 | The initial vanilla spawn search finds nothing; the builder sets the spawn | `BuildTask.java:227-231`; mc → MinecraftServer.java:419-463 | In a noise world vanilla searches for (and generates) a spawn first (A2.7) |
| F11 | Placement cost = block writes only (chunks generate instantly) | `BlockPlacer.java:207-216` (`level.getChunk` on the server thread) | Each FULL chunk now needs real generation of itself and its neighbours (A2.10, A6.1) |
| F12 | Flat flag on the client (horizon at the world bottom, clear-colour scale 1.0) | mc → WorldDimensions.java:104-113; ClientLevel.java:925-931 | A noise world has horizon 63 and scale 1/32 (rendering track) |
| F13 | Server rules, game rules, difficulty and time are world-wide and applied on every server | `fabric-bridge/src/main/java/dev/kantobridge/server/Rules.java:59-79`; `…/server/KantoGameRules.java:20-56` | Track 2 (A1.5, A5) |
| F14 | The GameTest world is vanilla's flat preset; the synthetic build runs at origin 3,200,000 in it | mc → GameTestServer.java:99-105; `fabric-bridge/src/gametest/resources/kantobridge/golden/synthetic_world_index.json` | A noise generator can't be the GameTest world (A6.4) |
| F15 | Spawn rule data hard-codes ground Y 63 and step 22 | `walking_level_only.json:8` | Only if Kanto's level shifts (A2.6) |

No Java code names `FlatLevelSource` or tests `isFlat` (grep over `fabric-bridge/src/main` and
`src/client`): the flat world is assumed through data, properties and behaviour, not through type
checks.

### 2.4 Part B facts: blocks, packs, puppets and the export

#### Tile blocks, the pack and story art (B1.1–B1.3, B1.6, B1.7)

| Topic | Fact | Evidence |
|---|---|---|
| Tile block registration | `tile_full_00..15` with shape `Shapes.block()`, `tile_half_00..15` with `Block.box(0,0,0,16,8,16)`; registered in `KbBlocks.register()` from `KantoBridge#onInitialize` | `KbBlocks.java:29-30,47-64`; `KantoBridge.java:48` |
| Properties `hi`/`lo` | Two `IntegerProperty` 0..15; slot = `hi*16+lo`; a single 0..255 property would cost ≈95 MB instead of ≈5 MB | `TileBlock.java:21-22,44-54`; `docs/PROGRESS.md:42` (S2: 10.1 MB measured for all 8,192 states) |
| Block properties | `MapColor.STONE`, `strength(-1, 3_600_000)`, `noLootTable()`, `SoundType.STONE`, `PushReaction.BLOCK`; no light emission; `canOcclude` left at its default `true` | `KbBlocks.java:86-93`; `mc → BlockBehaviour.Properties (BlockBehaviour.java:1015,1027)` |
| Shapes | Only `getShape` is overridden (per family). Collision, occlusion, visual and support shapes follow it through vanilla defaults | `TileBlock.java:38-41`; `mc → BlockBehaviour#getCollisionShape (:352-354)`, `#getOcclusionShape (:308-310)`, `#getVisualShape (:364-366)`, `#getBlockSupportShape (:312-314)` |
| Light and occlusion that follow | Full: `isSolidRender` true → light block 15, neighbours' faces culled, AO shade 0.2. Half: not solid-render, `propagatesSkylightDown` true → light block 0, AO shade 1.0, still `canOcclude` (its full bottom face culls the block below) | `mc → BlockStateBase#isSolidRender (:677-684)`, `BlockBehaviour#getLightBlock (:320-326)`, `#propagatesSkylightDown (:436-438)`, `#getShadeBrightness (:337-339)`, `Block#shouldRenderFace (Block.java:211-241)` |
| Render layer | None registered (no `BlockRenderLayerMap` call anywhere in `src`), so every tile block is `RenderType.solid()`; tile textures have no transparency | `mc → ItemBlockRenderTypes#getChunkRenderType (ItemBlockRenderTypes.java:344-352)`; `builder/pack/Textures.java:48-51` |
| Invisible blocks | `story_cell`, `story_tile`, `ledge_collider`: `RenderShape.INVISIBLE`, empty outline and visual shape, `noOcclusion`, shade 1.0, skylight passes; collision depends on the entity | `KbBlocks.java:56-58,96-107`; `StoryCellBlock.java:46-91`; `LedgeColliderBlock.java:50-93` |
| Encoding of a placed block | Plan code = `kind<<24 \| value`; `KIND_FULL`/`KIND_HALF` carry the slot **index** (0..4095); state = family `index/256`, `hi=(index%256)>>4`, `lo=(index%256)&15` | `builder/plan/BlockCode.java:13-22,28-35`; `builder/place/CodeStates.java:30-39`; `TileBlock.java:49-54` |
| Which slot | `slot(map, tile, half)`: key `(map's tileset atlas image, map's palette, tile index, FULL\|HALF)`; HALF iff the cell is water | `builder/plan/WorldPlan.java:110-114,438-451,466-474`; `ArtCatalog.java:100-108` |
| Columns | ground 1 block at feet−1; water 1 half block; ledge = ground + collider at feet; counter/solid = floor + `height` blocks of **the same tile**; void ring = 4 blocks of border art (Y 63..66); interiors add a ring to the ceiling, a ceiling at feet+3 and `minecraft:light` 15 at feet+2 on a 4-block grid | `WorldPlan.java:396-427,429-464`; `HeightRules.java:16-25` |
| Slot map | Keys sorted by atlas, palette, tile, shape; numbered per shape; `FullException` → `PlanException("slot_map.full")` when a shape needs more than 4,096 | `world/SlotMap.java:42-45,157-170`; `ArtCatalog.java:132-137` |
| Slot keys collected | per map: every tile its cells show (HALF where water), the 16 tiles of its border block (FULL), and the 16 tiles of each swap site's `before`, `after` and data block (FULL) | `ArtCatalog.java:98-124` |
| Real numbers | full 3,926 (95.8 %; 170 free; 16 of 16 families in use), half 92 (1 family); tile textures 4,005 (13 shared by both shapes); models 4,018; blockstate files 17; animated 23 | `report`; arithmetic on `SlotMap.java:34-37`; `ResourcePackWriter.java:147-182` |
| Tile blocks placed | ≈2,419,048 = 2,485,154 − 50,664 story − 3,032 ledge − 12,114 light − 296 habitat (**I**, from how the counters are incremented) | `builder/place/BlockPlacer.java:245-272,302-306`; `report` |
| Pack location and identity | `<gameDir>/kantobridge/resourcepack/`, pack id `kantobridge_generated`, `PackSource.BUILT_IN`, `PackSelectionConfig(required=true, TOP, fixedPosition=false)` | `GeneratedPack.java:22,37-53` |
| Story art today | block-entity renderer on `story_tile` only; draws `tileFull[family].withSlot(state)` with `renderSingleBlock`, repeated `height` times or as a 0.01-thick layer | `client/story/StoryCellRenderer.java:39-71`; `KantoBridgeClient.java:39` |
| Custom rendering in use | 2 `WorldRenderEvents.AFTER_ENTITIES` hooks, 3 `HudRenderCallback`s, 1 block-entity renderer, 1 entity renderer, 5 client mixins; **no** model-loading plugin, renderer-API, render-layer or colour-provider use | B1.7 |

#### Puppets, surf sprite, other players (B1.4)

| Topic | Fact | Evidence |
|---|---|---|
| Entity type | `kantobridge:puppet`, plain `Entity`, `MobCategory.MISC`, `noSave().noSummon().clientTrackingRange(0)`, bounding box 1.75 × 1.9 blocks; registered in common init, spawned only on the client | `entity/KbEntities.java:16-17, 35-41`; `entity/PuppetEntity.java:21-35` |
| Ids and life cycle | Negative entity ids from −1,000,000 downwards, kept for the puppet's life; re-added when the client level changes; at most 64 NPCs in one `npc.set` | `client/puppet/PuppetManager.java:30, 78, 252-255`; `client/puppet/PuppetRuntime.java:142-157, 197-218` |
| Position | Cell centre on the map's walking surface from the WorldIndex: `(map.x + cellX·2 + 1, map.y, map.z + cellY·2 + 1)`; the cell slides during a step | `PuppetRuntime.java:238-246`; `client/puppet/Puppet.java:127-134, 162-167` |
| Tick | Each entity tick advances its puppet 3 Gen 1 frames and moves the entity (Minecraft interpolates between ticks) | `PuppetRuntime.java:221-235`; `Puppet.java:17, 94-108` |
| Renderer | `PuppetRenderer extends EntityRenderer<PuppetEntity>`, registered with `EntityRendererRegistry.register(KbEntities.PUPPET, PuppetRenderer::new)`; no shadow (`shadowRadius = 0`), no name tag | `client/puppet/PuppetRenderer.java:28, 34-37`; `PuppetRuntime.java:74` |
| Vertex path | One quad, four `VertexConsumer` vertices per puppet per frame: `buffers.getBuffer(RenderType.entityCutoutNoCull(texture))`, then `addVertex(pose, x, y, 0).setColor(-1).setUv(u, v).setOverlay(NO_OVERLAY).setLight(packedLight).setNormal(pose, 0, 1, 0)` | `PuppetRenderer.java:64-68, 107-114`; signatures `mc → com.mojang.blaze3d.vertex.VertexConsumer (VertexConsumer.java:16-26, 41-55, 136-148)`, `mc → net.minecraft.client.renderer.RenderType#entityCutoutNoCull(ResourceLocation) (RenderType.java:824)` |
| Orientation | `poseStack.mulPose(Axis.YP.rotation(π − cameraYaw))`: yaw-only billboard; the normal is always "up" (inference: so the card is shaded like a floor and its brightness does not change as it turns) | `PuppetRenderer.java:61-62, 113` |
| Size | 2 blocks wide (`HALF_WIDTH` 1.0) × 2 blocks tall (`HEIGHT` 2.0): one 16-px frame on one 2×2-block cell, i.e. 8 Gen 1 px per block | `PuppetRenderer.java:29-31` |
| Textures | `kantobridge:textures/npc/<palette>/<sprite>.png` from the generated pack, the palette being the **map's** SGB palette (`MapPlacement.palette()`); looked up once per (palette, sprite) and cached until a resource reload; a missing sheet falls back to the jar's `textures/entity/puppet_placeholder.png` | `client/puppet/PuppetTextures.java:19-41, 52-61`; `ResourcePackWriter.java:184-207, 244-246`; reload hook `PuppetRuntime.java:93-103` |
| Sheet layout | 64×32: 4 columns (down, up, left, right) × 2 rows (standing, walking) of 16×16; right = left mirrored when the sheet is built; 3-frame sheets reuse standing frames for walking, 1-frame sheets use the frame everywhere; shade 0 is transparent | `builder/pack/Textures.java:22-24, 126-161` |
| Frame selection | Column from `relative = wrapDegrees(facing.yaw − cameraYaw)`: `abs(relative) ≤ 45°` → UP (its back), `abs(relative) ≥ 135°` → DOWN (its front), otherwise RIGHT when `relative > 0`, else LEFT. Row: walking frame during frames 4–11 of each 16-frame step cycle. For up/down every other step mirrors the walking frame (UVs swapped) | `client/puppet/FacingFrames.java:32-42, 44-63`; `Puppet.java:19, 142-160`; `PuppetRenderer.java:56-59` |
| Culling (M10) | `shouldRender` first asks `PuppetCulling.visible`: not drawn beyond 48 blocks from the camera, nor outside the hidden player's map footprint ± 4 blocks, nor on another layer (`abs(y − mapY) < LAYER_STEP/2`); then vanilla's frustum check. Culled puppets still tick and stay solid | `PuppetRenderer.java:39-47`; `client/puppet/PuppetCulling.java:16-20, 27-37, 49-57`; `PuppetRuntime.java:165-176`; `docs/PROGRESS.md:699-701` |
| Collision and picking | `canBeCollidedWith()` true (solid for the local player only), `isPickable()` true, not pushable, not attackable | `entity/PuppetEntity.java:70-93` |
| Emote bubbles | NPC: a white text box above the card via `Font#drawInBatch`, facing the camera fully. Player: drawn on the HUD over the crosshair | `PuppetRenderer.java:85-100`; `PuppetRuntime.java:250-269` |
| Surf sprite | `WorldRenderEvents.AFTER_ENTITIES`; texture `kantobridge:textures/player/<palette>/surf.png` (NPC-style sheet of `field.playerSprites.surf`), palette of the map under the surfer; third person and other players: upright yaw-billboard 2×2 blocks, sunk 0.75 and pushed 0.35 toward the camera; local first person: flat quad on the water turned with the player; same `entityCutoutNoCull` vertex path; light from `LevelRenderer.getLightColor`; others drawn within 64 blocks from the synced `kantobridge:surfing` attachment | `client/fx/SurfSprite.java:57-70, 80-93, 108-133, 136-202`; `ResourcePackWriter.java:209-226, 249-251`; `builder/export/ExportField.java:58-68` |
| Other players | Drawn by vanilla's player renderer with their Minecraft skins. Our additions only: (1) translucent body (`0x8CFFFFFF`, layers skipped) while they stand in a cell that is closed for the local player (`LivingEntityRendererMixin` on `isBodyVisible` + the `render` constant + `RenderLayer.render` wrap); (2) a speech-bubble sign above the name tag from the synced `activity` attachment, within 64 blocks; (3) the surf sprite under surfers | `mixin/client/LivingEntityRendererMixin.java:26-48`; `client/fx/BlockadeFade.java:32-90`; `client/fx/SpeechBubbles.java:34-41, 80-135`; `SurfSprite.java:123-131` |
| The local player | Vanilla player model and skin; nothing of ours replaces it. Only the surf sprite is drawn under it | `client/KantoBridgeClient.java:25-64` (no player-renderer registration); `SurfSprite.java:118-122` |
| Closed story art (for B3.7) | A block-entity renderer on `kantobridge:story_tile` draws, for the local player only, the closed art as the **tile block's own baked model** (`renderSingleBlock`) stacked `closed.height` times, or as a 0.01-thick layer when the height is 0; view distance 256 | `client/story/StoryCellRenderer.java:26-82`; `client/story/StoryCellArt.java:22-51`; `world/StoryCellTable.java:97-131` |

#### What a Java generator can read from the export today (B1.5)

`ExportBundle.open(dir)` reads every resource listed in `index.json` and verifies size and SHA-256
(`builder/export/ExportBundle.java:74-160`). Typed accessors exist for map, tileset, image, sprite, palettes,
layout, swaps, encounters and field (`ExportBundle.java:212-249`); anything else is reachable as raw JSON
through `bundle.json(kind, id)` (`ExportBundle.java:207-210`).

| Data | In the export (Lua → schema) | Java accessor today | Notes |
|---|---|---|---|
| Map size | `width`, `height` in 32-px blocks (`gen1-bridge/export/kb_export.lua:703`; `protocol/export/map.schema.json:33-34`) | `ExportMap.width()/height()`, `cellWidth()/cellHeight()` (`builder/export/ExportMap.java:23, 79-87`) | |
| Block ids | `blocks`: width×height ids 0..255, row-major (`kb_export.lua:697-704`; `map.schema.json:35-40`) | `ExportMap.blocks()`, `block(bx, by)` (`ExportMap.java:97-100, 111-114`) | |
| Border block | `border` (the map record's `borderBlock`; 0 with a warning when absent) (`kb_export.lua:695-696, 704`; `map.schema.json:41`) | `ExportMap.border()` (`ExportMap.java:23, 167`); `MapModel.borderTile(tx, ty)` (`builder/plan/MapModel.java:322-326`) | **Yes, the per-map border block is exported.** What the engine draws *instead* of it outdoors (the tree-wall fill) is **not** exported: see "missing" below |
| Per-cell flags | `cells`: one 16-bit integer per 16-px cell (`gen1-bridge/export/kb_cells.lua:8-27, 84-149`; `map.schema.json:159-168`) | `ExportMap.flags(cx, cy)` (`ExportMap.java:89-95`); bits in `builder/export/CellFlags.java:8-35` | walkable, water, shore, grass, door, warp tile, counter, cut tree, pad, hole, spinner, warp entry, sign, ledge + direction |
| Tile ids per cell | Not stored per cell; derived: block id → 16 tile ids (4×4) through the tileset's `blocks` (`kb_export.lua:571-581, 588`; `protocol/export/tileset.schema.json:40-50`) | `ExportTileset.tile(block, tx, ty)` (`builder/export/ExportTileset.java:37-43`); `MapModel.tile(tx, ty)` gives the tile under every Minecraft block, swap sites applied (`MapModel.java:191-208, 317-320`) | A cell is 2×2 tiles; its behaviour comes from the bottom-left one (`docs/PROTOCOL.md:644-645`) |
| Tile classes | `walkable, counter, grass, door, warp, water, shore, cutTree, spinner, pads` (`kb_export.lua:585-595`; `tileset.schema.json:55-116`) | `ExportTileset` parses all **except `spinner` and `pads`** (`ExportTileset.java:19-21, 67-70`) | `spinner`/`pads` are in the bundle JSON but have no typed accessor |
| Atlas pixels | `image` resource: greyscale PNG (4 shades), base64 (`gen1-bridge/export/kb_images.lua:69-75`; `protocol/export/image.schema.json`) | `bundle.image(id).png()` → `Png.decode` → `Png.Image.get(x, y)` ARGB; shade via `Textures.shade(argb)`; one tile via `Textures.tileShades(atlas, tilesPerRow, tile)` (`builder/export/ExportImage.java:15-20`; `builder/pack/Textures.java:32-67`; used at `ResourcePackWriter.java:119-122, 269-279`) | Pixel access is available **wherever the bundle is**: builder (server) and client pack generator both hold it |
| Palettes | `palettes/sgb`: name → 4 RGB, lightest first; one palette name per map (`kb_export.lua:748-760, 693-694, 710`; `protocol/export/palettes.schema.json`) | `ExportPalettes.colours(name)` (`builder/export/ExportPalettes.java:17-24`); `ExportMap.palette()` | SGB mode only (`ExportPalettes.java:27-28`). No per-tile (GBC-style) groups (`docs/PROGRESS.md:241-243`) |
| Animation | Tileset `animation` symbol (`TILEANIM_NONE`, `TILEANIM_WATER` or `TILEANIM_WATER_FLOWER`) (`kb_export.lua:589`); frame images are exported as `image` resources because `engine.animationImages` adds their paths (`kb_export.lua:563`; `gen1-bridge/lib/kb_engine.lua:5081-5098`) | `ExportTileset.animatesWater()/animatesFlowers()` (`ExportTileset.java:45-51`); flower frames found **by image name** `flower1..3`, flower tile assumed to be tile 3 (`ResourcePackWriter.java:47-53, 281-309`); water sway offsets are constants in Java (`Textures.java:13-21`) | No explicit animated-tile list (which tile, which frames, which kind): an open issue since M2 (`docs/PROGRESS.md:260-262`) |
| Sprites | `sprite` resource: 16-px-wide vertical strip, `frames`, `walker`, PNG (`kb_images.lua:82-94`; `protocol/export/sprite.schema.json`) | `bundle.sprite(id)` → `ExportImage(id, w, h, frames, png)` (`ExportImage.java:22-25`) | `walker` is not parsed (frames ≥ 6 implies it) |
| Objects | `objects[]`: index, name, sprite, x, y, movement, range, hidden, optional item / trainer / pokemon (`kb_export.lua:655-680`; `map.schema.json:120-158`) | `ExportMap.objects()`; trainer and pokemon only as booleans (`ExportMap.java:63-68, 132-142`) | |
| Warps, signs | `warps[]` (x, y, to, warp), `signs[]` (x, y) (`kb_export.lua:643-654`) | `ExportMap.warps()/signs()` (`ExportMap.java:52-57, 123-131`) | Sign text is not exported |
| Connections | `connections.{north,south,west,east} = {map, offset}` in blocks (`kb_export.lua:632-642`) | `ExportMap.connections()` (`ExportMap.java:27-50, 115-122`) | |
| Layout | `layout/kanto`: block origins of surface maps from the engine's neighbour BFS (`kb_export.lua:373-424, 859`) | `bundle.layout()` (`builder/export/ExportLayout.java`) | Root is hard-coded `PALLET_TOWN` (`kb_export.lua:31`) |
| Swap sites | `swaps/all`: `{map, bx, by, kind, before, after, source?}` (`gen1-bridge/export/kb_swapsites.lua:1-53`; `protocol/export/swaps.schema.json`) | `bundle.swaps()`; `MapModel` builds the open state and `closedModel()` gives the closed one (`MapModel.java:91-157, 373-388`) | 95 sites: cut 35, card_key 20, script 36, boulder 4 (ER:231-237) |
| Parent, lastMap, dark, surface, outside | derived by gen1-bridge (`kb_export.lua:280-357, 699-711`) | `ExportMap.parent()/lastMap()/dark()/surface()/outside()` | |
| Field tables | 29 engine tables copied as JSON (`kb_export.lua:41-47, 817-825`) | typed: only `indoorEncounters`, `safari.stepMaps`, `playerSprites.surf` (`builder/export/ExportField.java:21-69`); the rest raw via `bundle.json("field","all")` | Includes `ledges`, `tilePairs`, `cutTreeSwaps`, `bookshelves`, `hiddenItems`, `outsideTilesets`, `waterTilesets`, `warpCarpets` |

**Missing for Dramaless-style analysis** (details and the fix for each are in B1.5 below): the engine's
outdoor border override; an explicit animated-tile list; nothing else is missing from the *export* — measured
heights, background detection, void tiles, band classification and silhouettes are all computable in Java from
data the bundle already holds.

**ROM-derived (stays local):** every export resource (`docs/PROTOCOL.md:613-615`), the bundle
(`docs/PROTOCOL.md:665-667`), the generated pack (`docs/PROTOCOL.md:727-728`), the WorldIndex and the slot
map as a whole (`protocol/world/world_index.schema.json:5`; `docs/PROTOCOL.md:701-702` mark both relay
messages ROM-derived). Anything computed from atlas or sprite **pixels** (masks, hulls, silhouettes, panes,
re-projection images) is art-derived and must be generated on each client and never sent.

---

## 3. Answers

Every question of the spec's §5 and §6, in the spec's answer format (Answer, Options, Recommendation,
Sketch, Open), as written by the researcher who owned it. An **Integrator note** under an answer says
where the integrated design of section 4 differs or adds something.

---

**A1. As-is audit**

### A1.1 World creation, end to end

**Answer (facts).**
- The two creation paths are the sequence diagrams in [section 2.3](#23-part-a-facts-the-builder-and-world-creation). Both end in the same code: `HeadlessBuild.start` →
  `KantoWorldBuilder.start` → `BuildTask` (`fabric-bridge/src/main/java/dev/kantobridge/builder/HeadlessBuild.java:143-161`;
  `KantoWorldBuilder.java:32-41`).
- **Where the builder gets the export today:**
  - client flow: the export and the pack are finished *before* the level exists (steps EXPORT, PACK,
    RELOAD precede CREATE, `CreateKantoWorldFlow.java:76-86, 316-407`); the bundle folder travels in a
    static one-shot `BuildRequest` (`BuildRequest.java:24, 48-56`) that the integrated server takes on
    `SERVER_STARTED` (`HeadlessBuild.java:54-67`);
  - headless: `-Dkantobridge.build=<bundle dir>` read on `SERVER_STARTED` (`HeadlessBuild.java:68-91`),
    the bundle having been written by `ExportCli` first (`tools/e2e/build_world.py:131-150`).
  - In both, the bundle is opened and planned **after** the world exists and its spawn chunks are
    generated: `SERVER_STARTED` fires after `initServer()` → `loadLevel()` → `createLevels` (with
    `setInitialSpawn`) → `prepareLevels` (mc → MinecraftServer.java:329-339, 356-417, 419-463, 479-…;
    fapi → `MinecraftServerMixin#afterSetupServer` (MinecraftServerMixin.java:50-53)).
- The generator instance of a new world comes from the preset registry entry decoded at datapack
  load; nothing per-world reaches it (`CreateKantoWorldFlow.java:436-438`; mc →
  `WorldPreset#createWorldDimensions` (WorldPreset.java:44-46)).
- The flat-world assumptions are the table in [section 2.3](#23-part-a-facts-the-builder-and-world-creation) (F1–F15).

**Options / Recommendation / Sketch:** see A2.1 (generator), A2.7 (flow), A6.1 (build order). The one
structural change this audit forces: the footprint has to be known at `SERVER_STARTING`, before
`createLevels`, because `ServerLevel`'s constructor already computes stronghold rings through the biome
source (mc → ServerLevel.java:266; ChunkGeneratorStructureState.java:83-101, 128-131) and
`setInitialSpawn` already generates chunks (mc → MinecraftServer.java:377, 437-441).

**Open:** none.

### A1.2 The vertical budget

**Answer (facts).**
- **Why the override:** PLAN P4 (`docs/PLAN.md:143`): Silph Co.'s floor chain needs about 260 blocks.
  `Scale.maxLayer(minBuildHeight)` = `floorDiv(64 − 1 − minBuildHeight, 22)` (`Scale.java:39-42`), fed
  from `level.getMinBuildHeight()` (`BuildTask.java:97, 156-157`; `WorldPlanner.java:124`): **14** layers
  for −256, only **5** for vanilla's −64. The real build uses all 14 (`build_report.json` `layers 14`)
  and still needed one deep spill and five sideways moves (`notes.interior_deep_spill 1`,
  `interior_moved_sideways 5`).
- **Y use of a full build** (block Y, inclusive):

  | What | Y | Source |
  |---|---|---|
  | Surface feet level | 64 | `Scale.java:21` |
  | Ground / floor block, water half block (top 63.5) | 63 | `Scale.java:23`; `WorldPlan.java:439-440` |
  | Ledge collider, story-cell block | 64 | `WorldPlan.java:441-444, 452-457` |
  | Counter column | 63…64 | `HeightRules.java:23`; `WorldPlan.java:445-450` |
  | Wall / tree column | 63…65 | `HeightRules.java:17` |
  | Roof / cliff column | 63…67 | `HeightRules.java:19` |
  | Void-fill column (border art) | 63…66 | `HeightRules.java:21`; `WorldPlan.java:404-408` |
  | Surface volume (cleared or planned) | 63…67 (`SURFACE_MAX_Y` 68 = `GROUND_Y + ROOF + 1`, exclusive) | `WorldPlan.java:26-27` |
  | Surface habitat blocks | 61 (`feet − 3`) | `HabitatPlanner.java:33, 102` |
  | Surface biome box | 56…87 | `WorldPlan.java:302-306` |
  | Interior layer *k* feet | 64 − 22·k → 42, 20, −2, −24, −46, −68, −90, −112, −134, −156, −178, −200, −222, −244 | `Scale.java:25, 31-33` |
  | Interior floor / walls / lights / ceiling | `feet−1` / `feet−1…feet+1` / `feet+2` (4-block grid, not on dark maps) / `feet+3` | `WorldPlan.java:29-33, 416-427, 458-463`; `HeightRules.java:25` |
  | Interior volume | `feet−1 … feet+3`, footprint + 1-block ring | `WorldPlan.java:248-251` |
  | Interior biome box | `feet−4 … feet+7`, volume + 4 blocks sideways | `WorldPlan.java:311-313` |
  | Deepest (layer 14) | floor −245, ceiling −241, a habitat block would be −247 | derived |
  | Solid-free gap between a floor and the ceiling below it | 17 blocks (floor 63−22k, ceiling below 45−22k) | derived |

- **What the override does to an ordinary vanilla world** (any world opened with the mod; level.dat
  refers to the type by id):
  1. The build range becomes −256…319 (36 sections instead of 24). Validation passes: `min_y` and
     `height` multiples of 16, `min_y + height ≤ 2032`, `logical_height ≤ height` (mc →
     `DimensionType#<init>` (DimensionType.java:78-98)).
  2. **Terrain is unchanged and nothing generates in −256…−65: it is air.** The noise generator
     clamps its own settings to the level (`NoiseSettings#clampToHeightAccessor`: `max(noise min,
     level min)` … `min(noise top, level top)`, mc → NoiseSettings.java:54-58; used by `fillFromNoise`
     and `iterateNoiseColumn`, NoiseBasedChunkGenerator.java:151, 290), and world-gen anchors resolve
     against `max(level min, generator min) = −64` and `min(576, 384) = 384` (mc →
     WorldGenerationContext.java:10-13; `VerticalAnchor.AboveBottom#resolveY` (VerticalAnchor.java:63-66)),
     so the bedrock floor stays at −64…−60 (mcdata → noise_settings/overworld.json:393-405).
  3. Side effects that are real, if small:
     - void death at Y < −320 instead of −128 (mc → `Entity#checkBelowWorld` (Entity.java:514-515));
     - **natural mob spawning is diluted:** the spawn Y is drawn uniformly from `minBuildHeight …
       surface+1` (mc → `NaturalSpawner#getRandomPosWithin` (NaturalSpawner.java:284-291)); with 192
       empty blocks added, a column whose surface is Y 64 keeps (64+65)/(64+257) ≈ 40 % of its useful
       attempts (**inference** from the code; the size of the gameplay effect is a [measure], A2.10);
     - the client allocates render sections for 36 levels (mc → ViewArea.java:61);
     - chorus-fruit and portal Y ranges start at the new bottom (mc → ChorusFruitItem.java:29;
       PortalForcer.java:59); their top is unchanged (−256 + 576 = 320).
     - Existing chunks load fine (missing sections become empty); **removing the mod later drops
       everything below −64 on the next save** of those chunks (`refs/_research/P5_mc_content_testing.md:549-553`).

**Options** (for the override itself; it matters more once vanilla play outside Kanto is real play):
1. Keep it global (today). Cost: the side effects above in every world.
2. Ship it as a Fabric built-in data pack enabled only for Kanto worlds
   (`refs/_research/P5_mc_content_testing.md:586-594`). Risk: an existing Kanto world opened without the
   pack would lose layers 6–14; it needs a forced-enable rule for worlds that have a
   `kantobridge/world_index.json`.
3. Remove the need: 14 layers fit in vanilla's −64…63 only with a layer step of ≤ 9 blocks, which
   contradicts DESIGN §3's "at least 16 blocks apart" (user-written; not ours to change).

**Recommendation:** keep option 1 for the first milestone (no behaviour change for existing worlds),
and fix the spawn dilution where it matters (see A2.4). Revisit option 2 only if the user wants plain
vanilla worlds in the same instance.

**Open:** the user's call on option 3 (it would also shrink chunk height back to vanilla).

### A1.3 Placement

**Answer (facts).**
- **Origin:** the layout root is the export's `layout.root` (`PALLET_TOWN`; fallback `DEFAULT_ROOT`)
  (`WorldPlanner.java:40, 97-98`). Its cell (0,0) is block (`originX`, `originZ`), default (0, 0)
  (`BuildOptions.java:35`; both triggers use `BuildOptions.defaults()`: `HeadlessBuild.java:79`,
  `CreateKantoWorldFlow.java:424`). GameTests use a far origin (golden index: 3,200,000).
- **Map origins:** breadth-first over `connections` with the engine's neighbour arithmetic, in Gen 1
  blocks (`Layout.java:48-69, 152-159`), ×4 for blocks (`WorldPlanner.java:114-116`). Positions never
  depend on the map filter (`Layout.java:20-22`).
- **Surface box:** bounding box of the *built* surface maps, inflated by 12 blocks, Y 63…67
  (`WorldPlan.java:254-265`). Unlike the layout it **does** depend on the filter.
- **Void fill:** every cell of the box that no map covers takes the border art of the nearest map
  (multi-source 4-neighbour BFS; `WorldPlanner.java:208-258`) as a 4-block column Y 63…66
  (`WorldPlan.java:396-409`). The ring and the gaps between maps are the same thing.
- **Interiors:** in rounds, each under its parent's door: origin = door block − arrival-cell offset
  (`WorldPlanner.java:301-322`); the layer is the first one ≥ parent's layer + 1 where the footprint
  (+1 ring) keeps 16 blocks from its neighbours (`InteriorPacker.java:24-26, 137-155`); else sideways
  in rings of 16 blocks, up to 64 rings (`:28-30, 97-104`); if the parent is already on the deepest
  layer, the deepest layer at the nearest spot outside every surface **map** (`:112-135`).
- **Do interiors leave the surface footprint?** Nothing in the packer refers to the surface box, so
  they can (F7). **In the real build they do (inference from counts):** the box is 704 × 744 blocks
  (below), which covers 44 or 45 chunks by 47 or 48 chunks = 2,068, 2,112, 2,115 or 2,160 chunks
  depending on alignment; the build touched 2,126 (`build_report.json` `chunks`), so the box covers at
  most 2,115 and **at least 11 chunks hold interior volume outside the surface box**.

**[measure] Numbers.**

*Synthetic bundle* (`fabric-bridge/src/gametest/resources/kantobridge/synthetic_bundle`, generated
by `tools/fake_gen1/synth.py:59-119`; placements cross-checked with the golden
`…/golden/synthetic_world_index.json`), origin-relative blocks:

| Item | Value |
|---|---|
| Surface maps | 1 (`TEST_TOWN`, 10×9 Gen 1 blocks = 20×18 cells = 40×36 blocks at (0, 64, 0)) |
| Surface box | X −12…51, Z −12…47 → **64 × 60** blocks (32 × 30 cells), Y 63…67 → 19,200 positions |
| Void-fill cells | 960 − 360 = 600 |
| `TEST_HOUSE` | layer 1, feet 42, origin (2, −6), 20×20 blocks; volume X 1…22, Z −7…14, Y 41…45 = 22×22×5 = 2,420 |
| `TEST_HOUSE_2F` | layer 2, feet 20, origin (6, −6), 16×16 blocks; volume X 5…22, Z −7…10, Y 19…23 = 18×18×5 = 1,620 |
| Interior extents | X 1…22, Z −7…14: inside the surface box, but 7 blocks north of the surface map |
| Builder volumes in total | 23,240 block positions in 20 chunks (5 × 4) |
| Whole columns over the surface box | 64 × 60 × 576 = 2,211,840 positions |
| Lowest block | Y 19 (house 2F floor); habitat block at (32, 61, 12) |

*Real Kanto* (counts only):

| Quantity | Value | From |
|---|---|---|
| Layout box | 170 × 180 Gen 1 blocks = 340 × 360 cells = **680 × 720 blocks** | `export_report.json` `layout_box_blocks` |
| Surface box (with the 12-block ring) | **704 × 744 blocks** = 352 × 372 cells = 130,944 cells = 523,776 columns | derived (`WorldPlan.java:24, 264`) |
| Cells covered by the 36 surface maps | 130,944 − 87,564 = **43,380 cells (33 %)** | `build_report.json` `notes.void_fill_cells` |
| So void fill is | 87,564 cells = **67 % of the box** | same |
| Surface volume | 523,776 × 5 = 2,618,880 positions | derived |
| Whole columns over the box | 523,776 × 576 ≈ 301.7 million positions | derived |
| Chunks touched | 2,126 (≥ 11 of them interior-only, see above) | `build_report.json` `chunks` |
| Y range used | −245 … 67 (habitats possibly −247) | `layers 14` + A1.2 |
| All cells, all maps | 94,876 (so interiors ≈ 51,496 cells incl. the two unreachable maps) | `export_report.json` `cells` |
| Interiors moved | 5 sideways, 1 deep spill | `build_report.json` `notes`; `build_diagnostics.json` |

*Report fields a future build should add* (all counts, coordinates of boxes, or hashes):

```
"surface":   { "box": [minX, minZ, maxX, maxZ], "map_cells": n, "void_fill_cells": n, "chunks": n },
"interior":  { "bbox": [minX, minZ, maxX, maxZ], "y": [minY, maxY], "volume_blocks": n,
               "by_layer": {"1": n, …}, "outside_surface_box": n, "outside_surface_maps": n,
               "max_overhang_blocks": n, "chunks_interior_only": n },
"footprint": { "version": 1, "hash": "…", "cells": n, "perimeter_cells": n,
               "pocket_cells_filled": n, "bbox": [minX, minZ, maxX, maxZ],
               "chunks_inside": n, "chunks_edge": n }
```

**Recommendation:** add these fields in the first milestone; A3.1 and A2.4 depend on
`interior.outside_surface_box` and `max_overhang_blocks`.

**Open:** the real interior extents (blocked on the new fields; a rebuild by the user or the e2e
build produces them).

> **Integrator note.** The report fields listed here are added in M11a, under the unified names of [4.11](#411-build-report-additions).

### A1.4 The placer's assumptions

**Answer (facts).**
- **Existing blocks:** every position inside the build's volumes (and inside the previous build's
  volumes, read from the old `world_index.json`: `BuildTask.java:160-162, 173-190`) is "owned": planned
  or air (`WorldPlan.java:324-341`; `fabric-bridge/src/main/java/dev/kantobridge/builder/plan/ChunkCodes.java:60-67`).
  A position is written only when its state differs (`BlockPlacer.java:245-255`), so an identical
  rebuild writes nothing. `blocks_cleared` counts positions set to air (`:250-251`) plus removed
  habitat blocks (`:291-294`); it is 0 on a fresh void world. **Nothing outside the volumes is ever
  read or cleared.**
- **Chunk tickets and status:** per chunk, `addRegionTicket(TICKET, pos, 0, pos)` then
  `level.getChunk(x, z)` on the server thread (`BlockPlacer.java:207-210`). Distance 0 is ticket level
  33 = FULL, not ticking (mc → `DistanceManager#addRegionTicket` (DistanceManager.java:180-185);
  ChunkLevel.java:10-12). The ticket is dropped when the chunk's pending light work is done
  (`BlockPlacer.java:274-280, 313-318`); at most 4 chunks wait (`:51, 179-181`).
- **`setBlock` flags:** 18 = `UPDATE_CLIENTS | UPDATE_KNOWN_SHAPE` when a player tracks the chunk,
  else 16 (`BlockPlacer.java:52-53, 214-215`): no neighbour updates (flag 1 absent) and no shape
  updates (flag 16 set) (mc → `Level#setBlock` (Level.java:243-259)). So a fluid next to a placed or
  cleared block is **not** woken up by the build.
- **Heightmaps and light:** `LevelChunk#setBlockState` always updates the four final heightmaps and
  queues a light check when light properties differ (mc → LevelChunk.java:255-258, 264-269); the
  placer waits on `ThreadedLevelLightEngine#waitForPendingTasks` (ThreadedLevelLightEngine.java:213-215).
  Levels are written top-down so interiors are built into darkness (`ChunkCodes.java:8-14`).
- **Written outside the volumes:**
  - habitat blocks at `feet − 3` (surface: Y 61), placed with `Level.setBlock` and removed to **air**
    (`HabitatPlanner.java:33, 102`; `HabitatService.java:173-186, 215-221`; `BlockPlacer.java:283-311`);
  - biomes: all sections of every touched chunk are rewritten; cells outside the region boxes keep
    their stored biome (`BiomeWriter.java:36-60`; boxes `WorldPlan.java:300-316`);
  - the world spawn (`BuildTask.java:227-231`);
  - files: `<world>/kantobridge/world_index.json`, `slot_map.json`, `build_report.json`,
    `build_diagnostics.json`, and `<world>/datapacks/kantobridge_generated/`
    (`BuildTask.java:255-290, 378-401`; `fabric-bridge/src/main/java/dev/kantobridge/builder/pack/DatapackWriter.java:17-20, 46-48`).
  - Light blocks and story-cell blocks are inside the volumes (`WorldPlan.java:452-463`).
- **Block entities:** story cells get their key and type after placement
  (`BlockPlacer.java:256-266`); `onPlace`/`onRemove` run for every write whatever the flags (mc →
  LevelChunk.java:274-285).

**Consequences for a vanilla world (inference):**
1. Terrain, trees and fluids above Y 67 or below Y 63 inside the footprint would survive a build (F4).
2. Clearing a volume next to vanilla water or lava would leave a standing wall of fluid that starts
   to flow at the next neighbour update.
3. `level.getChunk` on the server thread generates each chunk synchronously (F11).
4. Removing an old habitat block would leave an air pocket in whatever fills the under-ground (F9).

**Recommendation:** keep the placer's contract ("owned volumes") and make the *generator* guarantee
that the volumes are already empty and sealed (A2.1, A6.1); add a counted safety pass rather than a
general "clear whatever is there" mode. `HabitatService.remove` should restore the footprint's fill
block instead of air.

**Open:** none.

### A1.5 Every behaviour that is world-scoped or server-scoped today

**Answer (facts).** One row per behaviour. Paths are under `fabric-bridge/src/main/java/dev/kantobridge/` (server and
common) or `fabric-bridge/src/client/java/dev/kantobridge/client/` (client) unless they start with another folder;
client mixins are in `fabric-bridge/src/client/java/dev/kantobridge/mixin/client/`.

*Scope today:* **World** = stored in the world's level data, valid for every dimension and player · **Server** = code or
configuration applied on every server that runs the mod · **Player** = per player on the server, wherever they stand ·
**Client** = client state keyed on "a WorldIndex has arrived" (or on nothing at all), wherever the player stands ·
**Position** = already decided per block position or biome.

*"In vanilla terrain"* is an inference from the code: what the behaviour would do, unchanged, to a survival player
standing on generated land outside Kanto's footprint. *Replacement* names the mechanism and the section that holds its
sketch; every signature in that column was read in the 1.21.1 sources or the Fabric API 0.116.17 sources jars (lines in
the named section). Zones: `OUT` (outside the footprint), `APRON` (in the footprint, on no map), `CORE` (map cells).

| # | Behaviour | Where it lives | Mechanism | Scope today | In vanilla terrain it would… | Region-scoped replacement | At the border | Test |
|---|---|---|---|---|---|---|---|---|
| 1 | Time and weather | `server/KantoGameRules.java:23-24`; `server/Rules.java:63`, `:71-72`, `:77-79`; new worlds: `world/CreateKantoWorldFlow.java:420` (client) | `doDaylightCycle`, `doWeatherCycle` false in the level's game rules, **re-applied at every `SERVER_STARTED`**; `setWeatherParameters` clear and `setDayTime(6000)` at every start | World | freeze the whole world at clear noon: no night, no rain, beds pointless | Stop forcing them in region worlds. Players inside get per-connection `ClientboundSetTimePacket(long gameTime, long dayTime, boolean daylightCycleEnabled)` and `ClientboundGameEventPacket(Type, float)` (`RAIN_LEVEL_CHANGE`, `THUNDER_LEVEL_CHANGE`) from `ServerTickEvents.END_WORLD_TICK` — `void onEndTick(ServerLevel world)` — on the ticks vanilla sends its own (A5.4) | Blended over the apron by signed distance | `RegionSkyGameTests` (packet tap) |
| 2 | Vanilla mob rules | `KantoGameRules.java:25-30`; Peaceful, locked: `Rules.java:74-75` | Game rules `doMobSpawning`, `doInsomnia`, `doPatrolSpawning`, `doTraderSpawning`, `doWardenSpawning` false, `disableRaids` true; `server.setDifficulty(PEACEFUL, true)` | World | remove every monster, stop all natural spawning, phantoms, patrols, traders, wardens and raids; Peaceful also refills food | Vanilla defaults in region worlds. Inside: empty biome spawners (have), three `without_*` biome tags, `ServerEntityEvents.ENTITY_LOAD` — `void onLoad(Entity entity, ServerLevel world)` — discarding non-allow-listed `Mob`s in the footprint, plus a 20-tick sweep (A5.5) | Gate blocks are solid for mobs; the sweep catches climbers and flyers | `RegionMobGameTests` |
| 3 | Player damage | Game rules `KantoGameRules.java:32-35`; veto `Rules.java:66`, `:117-119` | `fallDamage`, `drowningDamage`, `fireDamage`, `freezeDamage` false; `ServerLivingEntityEvents.ALLOW_DAMAGE` returns false for **every** `Player` unless the source is tagged `bypasses_invulnerability` | World + Server | make every player immortal (only the void and `/kill` hurt) | The same event — `boolean allowDamage(LivingEntity entity, DamageSource source, float amount)` — returning false only when the victim's zone is not `OUT`; hunger and effect sweeps inside (A5.3) | The victim's position decides | `RegionRulesGameTests.damageFollowsTheZone` |
| 4 | Death and respawn | `KantoGameRules.java:36-37`, `:53`; world spawn: `builder/BuildTask.java:230` | `keepInventory`, `doImmediateRespawn` true; `spawnRadius` 0; the builder sets the world spawn in Pallet Town | World | keep items on death, skip the death screen, respawn every bed-less player **inside Kanto**, unfollowed | Keep both rules world-wide at first (A5.9). `ServerPlayerEvents.AFTER_RESPAWN` — `void afterRespawn(ServerPlayer oldPlayer, ServerPlayer newPlayer, boolean alive)` — moves a bed-less `OUTSIDE` player to the home gate's apron (A4.6); world spawn outside Kanto (track 1) | Respawn never lands in `CORE` without following | `RegionJoinGameTests` |
| 5 | World protection | `KantoGameRules.java:39-45`, `:54` | `mobGriefing`, `doFireTick`, `doVinesSpread`, `doTileDrops`, `doEntityDrops`, `doMobLoot`, `projectilesCanBreakBlocks` false; `randomTickSpeed` 0 | World | cripple survival: **mined blocks and killed mobs drop nothing**, crops, saplings and grass never grow, leaves never decay, fire never spreads | Vanilla defaults in region worlds. One server mixin at the head of `Level#setBlock(BlockPos, BlockState, int, int)`, early vetoes in `Level#destroyBlock`, `Explosion#explode`, `FlowingFluid#canSpreadTo`, `PistonStructureResolver#resolve`, two immunity tags (A5.7) | Keyed on the **target** position, so actors outside cannot change the inside | `RegionGuardGameTests` |
| 6 | Chat rules | `KantoGameRules.java:47-49` | `announceAdvancements`, `showDeathMessages`, `commandBlockOutput` false | World | silence advancement and death messages everywhere | Not positional. Leave to the world's settings in region worlds (user decision) | — | `KantoGameRulesTest` (existing) |
| 7 | Game mode | `Rules.java:64-67`, `:76`, `:85-90`; `CreateKantoWorldFlow.java:420` | `setGameMode(ADVENTURE)` on `JOIN`, `AFTER_RESPAWN` and `BuildHold` release; `setDefaultGameType(ADVENTURE)`; Adventure in new level settings | Player (forced server-wide) | forbid breaking and placing anything, anywhere | Never switch modes. Deny by position of actor **and** target: `AttackBlockCallback` — `InteractionResult interact(Player, Level, InteractionHand, BlockPos, Direction)`; `UseBlockCallback` — `InteractionResult interact(Player, Level, InteractionHand, BlockHitResult)`; `PlayerBlockBreakEvents.BEFORE` — `boolean beforeBlockBreak(Level, Player, BlockPos, BlockState, @Nullable BlockEntity)` (A5.1); row 5's guard as backstop | A survival player outside cannot reach in; a followed player cannot reach out | `RegionBuildGameTests` |
| 8 | Speed, jump, sprint | `Rules.java:45-47`, `:96-110`; bike: `server/StoryStateService.java:45-47`; client `move/SpeedSync.java:27-30`, `mixin/client/LocalPlayerMixin.java:15-20` | `Abilities#setWalkingSpeed`, `MOVEMENT_SPEED` **base** 0.1737 or 0.3474, `JUMP_STRENGTH` **base** 0, `setSprinting(false)`; client refuses sprint when the jump base is 0 or the player is followed | Player (base values persist in player data) | walk 1.7 times faster than vanilla with **no jump**: one-block steps of natural terrain are impassable | The same calls per zone: `AttributeInstance#setBaseValue(double)` and `walkingSpeed`, vanilla values (0.1, 0.42) in `OUT`, through `RegionRules.apply` on `JOIN`, `AFTER_RESPAWN`, bike change and zone change (A4.5, A5.2) | Switches at the footprint's edge; the apron absorbs the one-or-two-tick attribute lag | `RegionRulesGameTests`; `RulesSpeedTest` (existing) |
| 9 | Kanto biomes | `fabric-bridge/src/main/resources/data/kantobridge/worldgen/biome/kanto_outdoor.json:2`, `:10-15`, `:17-18`, `:19-28` (and `kanto_interior`, `kanto_cave`); written per position by `builder/plan/WorldPlan.java:300-316` | No precipitation; music `minecraft:intentionally_empty` with `replace_current_music`; no carvers or features; empty spawner lists | Position | do nothing (the biome there is vanilla's). Two consequences: Minecraft's music is silenced **only** by these biomes, so vanilla music plays outside on top of the hidden game's sound; and footprint columns above Y 88 or below the interior bands keep the generator's biome | Keep. Add the three biomes to `without_patrol_spawns`, `without_wandering_trader_spawns`, `without_zombie_sieges` (A5.5); never add them to a `#minecraft:` or `#c:` biome tag (A5.6); the away hold fades the hidden game's music (A7.4) | Biome edge = footprint edge (colour blending is track 1's and track 3's) | data test in `StaticDataTests` |
| 10 | Dimension type | `fabric-bridge/src/main/resources/data/minecraft/dimension_type/overworld.json:9`, `:12` | Overrides `minecraft:overworld` itself: `min_y` −256, `height` 576 | Server (every overworld while the mod is loaded) | change the build limits of ordinary land | Track 1 (A1.2, A2.6) | — | — |
| 11 | Cobblemon spawn rules | `…/data/kantobridge/spawn_rules/no_interior_spawns.json`, `walking_level_only.json:7-8` | `filter` rules attached to every player's spawner, conditioned on Kanto biomes; the second allows only `math.mod(y - 63, 22) == 0` | Position (by biome) | do nothing (their conditions name Kanto biomes) | Keep; add one `SpawningInfluence` per player through `PlayerSpawnerFactory.influenceBuilders: MutableList<(ServerPlayer) -> SpawningInfluence?>` (A5.6) | See row 12 | `RegionSpawnGameTests` |
| 12 | Habitat blocks | `server/HabitatService.java:61` (range 14: `builder/plan/HabitatPlanner.java:31`), `:106`, `:188-200`, `:225-251` | Cobblemon habitat blocks under the ground inject each route's pool within a 14-block sphere; verified as chunks load; overworld only | Position | make Kanto's route Pokémon spawn on vanilla ground within 14 blocks of a habitat near the edge (habitat spawn details carry no biome condition) | The influence refuses `habitat_` details outside the footprint, and everything but them inside (A5.6) | No leak either way | `RegionSpawnGameTests` |
| 13 | Cobblemon settings | `server/CobblemonSettings.java:35-41`, `:48-73`, `:76-83`; applied `Rules.java:59-62`, `:73` | Cobblemon's process-wide config at `SERVER_STARTING`; its game rules; the starter prompt locked per player | Server + World + Player | apply Kanto's Cobblemon everywhere: no passive healing, no shinies, no drops, normal experience, wild Pokémon not saved, flee distance 12, spawn zone 8–32 blocks, no starter choice | Most stay global on purpose (table in A5.6). Restore `shinyRate` and `defaultFleeDistance`; force non-shiny inside through `SpawningInfluence#affectAction`; loot by the cancelable `LOOT_DROPPED` | — | `CobblemonSetupGameTests` (existing) + `RegionSpawnGameTests` |
| 14 | Recipes off | `…/data/cobblemon/recipe/healing_machine.json:2-9`, `pc.json`, `pasture.json` | Overridden recipes with an always-false Fabric load condition | Server (data pack) | stop an outside player from crafting a healing machine, a PC or a pasture | Not positional (a data pack has no position). Recommended: re-enable them, since nothing can be placed inside the footprint anyway (A5.1) and Kanto keeps its own Centers and Bill's PC; a user decision (A7.3) | A machine can stand just outside a gate | `StaticDataTests` (the recipe assertion flips) |
| 15 | Following | `move/MoveController.java:86-106`; `move/MovementSync.java:193-224`, `:401-428`, `:461-481`, `:483-499`; `move/MoveRuntime.java:109-113`, `:136-150` | `following` = index present and not creative or spectator; steps, facing, drift correction, Cycling Road brake | Client | treat every vanilla position as "off the map": no steps, and after 500 ms a teleport back onto the hidden cell, repeated every 3 s (A1.6) | `following` = … and border state `FOLLOWING` (A4.2, A5.8). No Minecraft API involved | Following ends and resumes at a gate, without a teleport (`linkFrom`) | JUnit `BorderMachineTest`, `MoveControllerTest`, `MovementSyncTest` |
| 16 | `InputLock` | `move/InputLock.java:107-134`, `:137-153`; `mixin/client/KeyboardInputMixin` | Zeroes movement input while the hidden game is busy, at its title, pairing, on an unbuilt map, or the link is down | Client | freeze a player who is mining outside whenever the hidden game is not walking freely | `InputLock.reason(following, …)` instead of `reason(kanto, …)` (A5.8) | Outside: never locked | JUnit `InputLockTest` |
| 17 | Teleport on join, re-link, snapshot | `MovementSync.java:153-185`, `:229-241`, `:505-526`, `:602-618`; `MoveController.java:201-207`, `:309-314` | `rejoin`/`settle` send `warp.request` so the player stands on the hidden cell after a join, a re-link, a new index, a snapshot with `ingame` | Client | yank a player who logged out in vanilla land into Kanto at the next join | A persistent presence attachment decides: `AttachmentRegistry.create(ResourceLocation, Consumer<Builder<A>>)` with `persistent(Codec<A>)`, `copyOnDeath()`, `syncWith(StreamCodec<? super RegistryFriendlyByteBuf, A>, AttachmentSyncPredicate.targetOnly())` (A4.2, A4.6) | `OUTSIDE` at join: no snap | `RegionJoinGameTests`; JUnit `BorderMachineTest` |
| 18 | Warps | `MoveController.java:161-184`; `server/WarpService.java:82-119` | The hidden game's `warp` → fade → `warp.request` → server teleport within the level (refusals `HELD`, `NO_INDEX`, `UNKNOWN_MAP`, `BAD_CELL`) | Server (any player of a world with an index) | teleport an outside player into Kanto whenever the hidden game warps | The away hold stops the hidden game, so no warp arises; `WarpService` also refuses a request from a player whose presence is `OUTSIDE` unless it is the entry warp (A4.2) | A gate is never a warp | `WarpGameTests` addition |
| 19 | Blackout | `docs/PROTOCOL.md:435-437`; `refs/gen1recomp/src/world/OverworldController.lua:5387-5404` | The hidden game's own warp to the last heal point after a lost battle | Player (hidden game) | not happen: only battles the hidden game asked for can end in one | Unchanged; such battles cannot start under the away hold (A4.6) | — | `BlackoutGameTests` (existing) |
| 20 | Saving | `server/SaveService.java:58`, `:77-121`, `:124-160` | `save.begin` (reasons `menu`, `center`) → `server.saveEverything(true, false, false)`; refused during a build | Server (one player's save flushes the whole server) | never fire by itself (the hidden game is not played), while Minecraft's own autosave keeps saving the player | Unchanged, plus the `region` field (A8.3). See row 21 for the consequence | — | `SaveGameTests` (existing) |
| 21 | Save pair and M10 warnings | `server/SavePairService.java:75-129`; `server/SavePair.java:28-39`; operations counted at `pokemon/PartyService.java:246`, `:337`, `pokemon/DaycareService.java:135`, `:199`; client `net/Versions.java:122-154`, `ui/Notices.java:36-64` | A per-player attachment `{pair, at, ops}`; a warning screen on `save.mismatch` or on a game-version mismatch, queued without any position check | Player + Client | open a full-screen warning over vanilla play; outside progress never reaches a hidden save, so the pair drifts (A7.2) | Show notices only when the border state is not `OUTSIDE`; an entry check at the gate (A7.2) | The warning appears when the player is `ENTERING` | `SavePairGameTests` addition; JUnit `VersionCheckTest` |
| 22 | Pairing (M9) | `server/SessionService.java:131-196`; client `net/SessionRuntime.java:306-317`, `net/SessionBinder.java:111-131`, `net/SessionGate.java:371-381`, `move/MoveRuntime.java:103-107` | `session.bind` on joining any world with an index; the player is held until the hidden game runs this world's slot | World + Client | hold a player at join although they stand outside and may never enter | Bind at join stays (per region, A8.3); the hold applies only when the border state is not `OUTSIDE`; entry waits for the gate to be `OPEN` (A4.6) | — | `SessionGameTests` addition; JUnit `SessionBinderTest` |
| 23 | `BuildHold` | `server/BuildHold.java:52-58`, `:66-69`, `:72-96`; `builder/HeadlessBuild.java:109`, `:124` | A build puts **every** player into spectator, then teleports them all to the world spawn | Server | take an outside player out of their game for a build of Kanto | Hold only players whose zone is not `OUT` (and those who enter the region's chunks meanwhile); release them where they were or at a gate apron (A4.7) | — | `BuildHoldGameTests`, `MultiplayerGameTests` additions |
| 24 | WorldIndex and slot map sync | `server/WorldIndexSync.java:45-59`, `:184-224`; client `world/ClientWorldIndex.java:26-27` | `world.index` and `world.slots` in parts to every joining client; one of each per server and per client | Server → every client | send Kanto's layout to a player who never enters (allowed by U8; a user decision in A4.7) | `world.regions` + `world.footprint` + a `region` field (A8.1) | — | `RelayGameTests` addition; JUnit `SlotMapTransferTest` |
| 25 | Story state and story cells | `story/StoryStates.java:19-21`, `:45-67`; `block/StoryCellBlock.java:67-80`; `server/StoryStateService.java:35-51` | A per-player attachment; collision of story blocks decided per moving entity | Player + Position | do nothing (the blocks exist only in Kanto). A minecart has no governing player, so story cells are open for it (A4.8) | Unchanged. The gate block copies this pattern with the presence attachment (A4.2) | The gate block | `CollisionGameTests` addition |
| 26 | Puppets | `puppet/PuppetRuntime.java:197-218`, `:238-246`; `puppet/PuppetCulling.java:20-36` | Client-only entities for the hidden player's map, within 48 blocks | Client (hidden map) | show frozen NPCs across the wall while the player stands just outside | Draw only while the local zone is not `OUT`, or keep them as scenery (A5.8) | — | JUnit `PuppetCullingTest` |
| 27 | Status line, extra HUD lines, F7 | `bridge/BridgeRuntime.java:92`, `:167-169`, `:195-198`; `bridge/ConnectionHud.java:73-74`; `move/MoveRuntime.java:92`; `net/SessionRuntime.java:233`; `packs/TextureSyncRuntime.java:101` | Drawn whenever the HUD is; "not connected, retrying…" stays while disconnected | Client (every world) | nag a player who is only playing Minecraft | Show when `RegionRuntime.concerned()`: border state not `OUTSIDE`, or within 32 blocks of the footprint (A5.8). F7 stays global and gains a region line | Fades in near the footprint | play-test |
| 28 | Pause overlay | `bridge/BridgeRuntime.java:177-193`; `bridge/LinkPause.java:103-119`; `bridge/LinkPauseScreen.java:43-45` | After 1.5 s without a link a pausing screen opens, when `following` | Client (via `following`) | pause a singleplayer world and cover the screen when Gen1Recomp closes, wherever the player is | Follows row 15's flag: none outside | — | JUnit `LinkPauseTest` |
| 29 | Speech bubbles and activity | `server/ActivityService.java:41`; `fx/SpeechBubbles.java:93-135`; `net/GameRelayRuntime.java:125-143`; `net/ActivityTracker.java:33-47` | A synced attachment drawn over other players | Player (synced to all) | show a battle bubble over an outside player in an ordinary Cobblemon battle | Optional: report `battle` only inside (A4.7) | — | JUnit `ActivityTrackerTest` |
| 30 | Blockade fading | `fx/BlockadeFade.java:51-61`, `:68-85`; `mixin/client/LivingEntityRendererMixin` | Other players inside the local player's closed cells are drawn faded | Position | do nothing | None | — | — |
| 31 | Surf sprite | `fx/SurfSprite.java:104-133`, `:188-202`; flag `StoryStateService.java:48` | Drawn under a surfer standing on a map's water | Position + Player | draw nothing; the flag stays set for a player who left by a sea gate | Clear the `Surfing` attachment when presence becomes `OUTSIDE` (A4.7) | — | — |
| 32 | Texture flow | `packs/TextureSyncRuntime.java:121-168`; `server/TextureStatusService.java:45`, `:66`; `mixin/client/BuiltInPackSourceMixin` | On joining a world with an index: compare the pack with the slot map, re-export and reload on a mismatch | Client + Player | run the export and a resource reload for a player who stays outside | Keep at join (Kanto may be visible at once); per region later (A8.3; track 3) | — | JUnit `TextureSyncTest` |
| 33 | Hidden-game screens and clicks | `ui/UiRuntime.java:188-190`; `act/ActRuntime.java:157-195`, `:278-302`, `:317-322`; `mixin/client/GameRendererMixin` | Text boxes, menus and the mirror open while `following`; **every plain right-click on a block returns `FAIL`** while `following` | Client (via `following`) | open Gen 1 text over vanilla play and make chests, doors and crafting tables unusable | Follows row 15's flag; screens also in `ENTERING` (A5.8) | — | JUnit `ClickPolicyTest` addition |
| 34 | Wild-battle rules and battle music | `battle/EncounterService.java:236-246`, `:291-305`; `battle/BattleMusic.java:37-41`, `:57-74`; `battle/BattleService.java:279` | "The server has an index" → Run always ends a wild battle as fled; Cobblemon's battle themes are muted | Server | change how every wild battle outside ends and mute its music | Key both on the player's zone (A5.6) | The player's position when the battle starts | `EncounterGameTests`, `BattleMusicGameTests` additions |
| 35 | Party, Pokédex and bag sync | `pokemon/PartyService.java:134-174`, `:191-228`, `:505-515`; `pokemon/DexService.java:105-122`; `bag/BagService.java:118-217` | Cobblemon's party and Pokédex are mirrored to the hidden game on every change; nicknames are refused; evolutions past dex 151 are vetoed; mirrored bag items are owned by the hidden bag | Player | send outside catches to the hidden game (species above 151 are dropped by `gen1-bridge/act/kb_party.lua:345`), refuse nicknames, block cross-generation evolutions, delete mirrored items a player found outside | A7.1 (species policy), A7.3 (marked stacks) | — | `PartyGameTests`, `BagGameTests` additions |
| 36 | Flash night vision | `server/FlashService.java:41-49`, `:69-79` | Night Vision while on a dark map the hidden game has lit | Position | do nothing | None | — | `FlashGameTests` (existing) |
| 37 | Mod required to join | `net/RelayHandshake.java:126-152` | Clients without the mod are disconnected with a reason | Server | — (a vanilla client cannot join at all) | Keep: the blocks and the puppet entity type need the mod (A4.7) | — | `RelayHelloGameTests` (existing) |

**The rows that would break vanilla play first** (inference): 5 (no drops, no growth), 8 (no jump), 7 (no building),
15 (teleported back every three seconds), 33 (no right-click), 16 (frozen by the hidden game's text boxes), 1 and 2
(no nights, no mobs).

**Options:** n/a (audit). **Recommendation.** Two switches carry almost everything: on the server "the zone of a
position" (`Regions.classAt`, A8.2) replaces "this server"; on the client the single flag `MoveRuntime.following()`
gains the border state (A5.8). Worlds without region records keep today's behaviour untouched (A5, first paragraph).

**Open.** Row 6 and the Cobblemon rows that stay global are user decisions about how the world outside Kanto should
feel (listed in [9.4](#94-runtime-scoping-and-the-hidden-game-blocked-and-open)).

> **Integrator note.** Row 14 keeps the three Cobblemon recipes disabled, while [A7.3](#a73-outside-economy) recommends bringing them back. The plan keeps them disabled until decision D9 (a one-file change either way). In M11 only the rows that hang on `following` (15–18, 26–28, 33) and row 8 (speed, jump, sprint) change; every other row moves in M12 ([4.4](#44-region-scoped-rules)).

### A1.6 A player outside every built map; the hidden player on an unbuilt map

**Answer (facts).**

*Minecraft player on no map cell (a gap, the void ring, between layers), not creative/spectator:*

1. **Position lookup is empty.** `WorldIndex.toCell` returns `Optional.empty()` "outside every map (gaps, borders, between
   layers)" (`fabric-bridge/src/main/java/dev/kantobridge/world/WorldIndex.java:137-149`); `Spot.at` then returns `null`
   (`fabric-bridge/src/client/java/dev/kantobridge/client/move/Spot.java:26-30`).
2. **No steps are sent.** `MovementSync.crossings` returns at once when `here == null`
   (`.../client/move/MovementSync.java:401-405`); `push` resets (`:434-438`). `move.face` still goes out (`face()` never
   looks at `here`, `:461-481`).
3. **The drift rule pulls the player back.** `drift()` sees `target (hidden spot) != here (null)`; after `DRIFT_MS` = 500 ms
   it calls `correct(null, target, …)`, and with `from == null` that is a **teleport**
   (`MovementSync.java:483-499`, `:588-595`, `:602-618`) → `WarpFade.sync` → `warp.request` without `kind`
   (`.../client/move/MoveController.java:309-314`, `:329-344`) → `WarpService.teleport`
   (`fabric-bridge/src/main/java/dev/kantobridge/server/WarpService.java:98-119`). Teleports are at least
   `TELEPORT_COOLDOWN_MS` = 3 s apart (`MovementSync.java:83`), so a player kept off-map is yanked back every ~3 s behind a
   250 ms fade.
4. **The input lock does not engage** for "off every map": `InputLock.reason` has no such input
   (`.../client/move/InputLock.java:107-134`).
5. **Physically they cannot get there on foot:** the surface is enclosed by a 12-block ring of 3-high border art
   (`.../builder/plan/WorldPlan.java:23-24`, `:396-409`; `.../builder/plan/HeightRules.java:21`), gaps inside the bounding
   box are the same 3-high fill, jump strength is 0 (`.../server/Rules.java:104-107`). Beyond the ring the flat preset has
   no blocks at all (`fabric-bridge/src/main/resources/data/kantobridge/worldgen/world_preset/kanto.json:5-13`), so a
   player who does get out falls into the void; void damage bypasses the damage veto (`Rules.java:117-119`), and they
   respawn at once (`keepInventory`, `doImmediateRespawn`, `.../server/KantoGameRules.java:36-37`) at the world spawn the
   builder set in Pallet Town (`.../builder/BuildTask.java:230`; `spawnRadius` 0, `KantoGameRules.java:53`).
6. **Creative and spectator are never followed or held** (`MoveController.java:89-90`, `MoveRuntime.java:123`); switching
   back re-links, and re-linking teleports onto the hidden cell (`MovementSync.setLinked` → `rejoinWanted` → `rejoin`,
   `MovementSync.java:165-176`, `:505-514`).
7. **Other per-position services just see "no map":** no Flash night vision (`.../server/FlashService.java:69-79`), no
   blockade fade (`.../client/fx/BlockadeFade.java:51-61`), no surf sprite (`.../client/fx/SurfSprite.java:188-193`).
   But `ActRuntime.useBlock` still returns `FAIL` for every plain right-click, because it asks `MoveRuntime.following()`,
   which is world-wide (`.../client/act/ActRuntime.java:176-195`).

*Hidden player on a map this world doesn't have (a partial build; COLOSSEUM/TRADE_CENTER are never built —
`build_report.json` `notes.map_unreachable: 2`):*

1. `MovementSync.hiddenUnbuilt()` is true (`MovementSync.java:385-388`) → `InputLock.Reason.UNBUILT_MAP`
   (`InputLock.java:121-123`): the player is **held where they stand**, with the HUD line `kantobridge.hud.move.unbuilt`
   naming the map id (`MoveController.java:245-254`).
2. No teleport is attempted: `hiddenSpot()` is `null`, so `rejoin`, `settle` and `drift` do nothing
   (`MovementSync.java:381-383`, `:484-487`, `:506-510`).
3. A hidden-game warp into such a map still runs the fade; the server refuses the request with `unknown_map`
   (`WarpService.java:105-108`), the fade gives up with the counted warning `refused` and opens again
   (`.../client/move/WarpFade.java:164-167`, `:191-195`); then rule 1 holds the player.
4. Puppets of that map are not spawned (`.../client/puppet/PuppetRuntime.java:203-206`, `:238-242`).

**Options:** n/a (audit).

**Recommendation.** The drift teleport (fact 3) is today's only "border": in a vanilla world it must not fire for a player
who left Kanto on purpose. The border state machine (A4.2) replaces "off every map → teleport after 500 ms" by
"`FOLLOWING` only while the feet are on a map cell; leaving a map through a gate ends following; leaving it any other
way is impossible (collision) or, as a fallback, corrected by the existing drift rule".

**Open.** Whether a respawn (void death) should also go through `rejoin`: today there is no respawn hook in
`MoveRuntime` (only `JOIN`/`DISCONNECT`, `MoveRuntime.java:79-84`); the respawned player is brought back by the drift rule.

### A1.7 What in session and identity assumes one region per world

**Answer (facts).** Every item below is a singleton per server/world or per player, with no region key.

| # | Assumption | Where | Detail |
|---|---|---|---|
| 1 | One WorldIndex per server | `.../server/WorldIndexSync.java:49` (`CURRENT: Map<MinecraftServer, Published>`), `:62-78` reads `<world>/kantobridge/world_index.json` | `current(server)` is what warps, Flash, habitats, battles and "is this a Kanto world?" use (`WarpService.java:85`, `FlashService.java:45`, `HabitatService.java:226`, `BattleService.java:279`, `EncounterService.java:237`,`:302`, `BattleMusic.java:73`, `TextureStatusService.java:66`) |
| 2 | Fixed file names | `.../builder/BuildTask.java:47-49` (`kantobridge/world_index.json`, `slot_map.json`) | One index and one slot map per world folder |
| 3 | `world.index` / `world.slots` carry no region id | `protocol/messages/world.index.schema.json` (fields `index,count,hash,part` only), `world.slots.schema.json` | Client keeps one of each: `.../client/world/ClientWorldIndex.java:26-27` (static `current`, `slots`) |
| 4 | "Kanto world" = "an index arrived" on the client | `MoveController.java:89` (`kanto = frame.index() != null`), `SessionRuntime.java:310`, `Versions.java:144`, `InputLock.java:109` | Following, the lock, the pause overlay, pairing and the game check all hang on it |
| 5 | WorldIndex is Gen-1-only and origin-less | `WorldIndex.java:37` (`GAMES = {"red","blue"}`), schema `protocol/world/world_index.schema.json:19-20` (`version` const 1, `game` enum red/blue), `Scale.java:21-25` (constants `SURFACE_Y 64`, `LAYER_STEP 22`) | Map origins are absolute block coordinates; there is no region origin field (the planner has `originX/originZ` internally, `WorldPlan.java:67-68`) |
| 6 | World id | `.../server/SessionService.java:78-80`, `:86-108`; `.../server/SessionRecord.java:34`,`:62-64` | One UUID per world folder in `<world>/kantobridge/session.json`, created on **every** world's first start (Kanto or not) |
| 7 | `session.bind{world, multiplayer}` | `protocol/messages/session.bind.schema.json:10-15`; server offer `SessionService.java:131-151`; client `.../client/net/SessionBinder.java:111-131`, `SessionRuntime.java:306-326` | The bind names a world only; the client binds when `ClientWorldIndex.current() != null` |
| 8 | Pairing record per player = one slot | `SessionRecord.java:55` (`Map<UUID,Integer> slots`), `:96-97` (`allowOnly("slot")`) | No region dimension |
| 9 | gen1-bridge pairing | `gen1-bridge/act/kb_pairing.lua:21-31` (`games.<version>.worlds.<world id>.slot`, plus `home`) | Keyed by game version, then world id |
| 10 | Save pair | `.../server/SavePair.java:38-39` (attachment `kantobridge:save_pair`, one `{pair, at, ops}` per player) | One hidden save per player per world |
| 11 | Story state | `.../story/StoryStates.java:19-21` (attachment `kantobridge:story_state`, one per player, not persisted); cell keys `"MAP:x:y"` (`protocol/defs.schema.json:155-160`) | Map ids are unique only inside one game |
| 12 | Texture flow | `.../server/TextureStatusService.java:45` (one `Status` per player), `texture.status` has one `slotMapHash`; client pack `<gameDir>/kantobridge/resourcepack/` with one `kantobridge.json` (`docs/PROTOCOL.md:727-739`); `TextureSyncRuntime.java:132-141` | One pack, one slot map hash |
| 13 | One hidden game per client | `.../client/bridge/BridgeRuntime.java:70-71` (one `BridgeClient`, one port); `hello.ok.game` vs `WorldIndex.game` warning `Versions.java:140-154` | One socket, one game |
| 14 | Daycare store per world | `docs/PROGRESS.md` deviation 83 (`<world>/kantobridge/daycare/<uuid>.json`) | No region key |
| 15 | Habitat check | `HabitatService.java:106` (overworld only), `:259` (`server.overworld()`) | Fine for one dimension, one index |
| 16 | Builder spawn | `BuildTask.java:230` (`setDefaultSpawnPos` in Pallet Town) | The world spawn is inside Kanto |

**Options:** see A8.1–A8.3 (the region record removes 1–5, 7–12).

**Recommendation.** Introduce the region id now, even with a single region, so that every later step only adds a second
record: keep the v1 WorldIndex as the *per-region* index and wrap it (A8.1); add an optional `region` field to the relay
messages that are per-region (`world.index`, `world.slots`, `texture.status`, `session.bind`, `save.begin`,
`save.loaded`, `save.mismatch`, `story.state`); default it to the world's only region so M9/M10 behaviour is unchanged.

**Open.** Whether two regions may ever run the *same* game in one world (it decides whether gen1-bridge's pairing key
needs the region, A8.3).

---

**A2. World generation: vanilla terrain plus a reserved footprint**

Terms used below (defined in A3.1): the **core** is the set of columns Kanto owns (maps, the ring,
filled pockets, interior projections); **d** is a column's distance in blocks to the core (≤ 0
inside); the **apron** is 0 < d ≤ A; the **ramp** is A < d ≤ R; the **foundation** is the fill block
under Kanto.

### A2.1 Architecture

#### Facts the comparison rests on

**F-1. The generation pipeline of 1.21.1** (mc → `ChunkPyramid.GENERATION_PYRAMID` (ChunkPyramid.java:9-44);
tasks in ChunkStatusTasks.java; statuses in ChunkStatus.java:20-31). "Needs" = neighbour radius the
step requires; "writes" = `blockStateWriteRadius` ("—" is the default −1: every region write is
refused; 0 = own chunk only).

| Status | Task → generator call | Needs (radius) | Writes | Runs |
|---|---|---|---|---|
| `structure_starts` | `generateStructureStarts` → `createStructures(RegistryAccess, ChunkGeneratorStructureState, StructureManager, ChunkAccess, StructureTemplateManager)`, only if `worldGenOptions().generateStructures()` (ChunkStatusTasks.java:35-48) | — | — | serial |
| `structure_references` | `generateStructureReferences` → `createReferences(WorldGenLevel, StructureManager, ChunkAccess)`: scans ±8 chunks for starts whose box meets this chunk (ChunkStatusTasks.java:57-64; ChunkGenerator.java:553-584) | starts 8 | — | serial |
| `biomes` | `generateBiomes` → `createBiomes(RandomState, Blender, StructureManager, ChunkAccess)` (ChunkStatusTasks.java:66-75) | starts 8 | — | async (`Util.backgroundExecutor`) |
| `noise` | `generateNoise` → `fillFromNoise(Blender, RandomState, StructureManager, ChunkAccess)` (ChunkStatusTasks.java:77-99) | starts 8, biomes 1 | 0 | async |
| `surface` | `generateSurface` → `buildSurface(WorldGenRegion, StructureManager, RandomState, ChunkAccess)` (ChunkStatusTasks.java:101-109) | starts 8, biomes 1 | 0 | serial |
| `carvers` | `generateCarvers` → `applyCarvers(WorldGenRegion, long seed, RandomState, BiomeManager, StructureManager, ChunkAccess, GenerationStep.Carving.AIR)` (ChunkStatusTasks.java:111-131) | starts 8 | 0 | serial |
| `features` | `generateFeatures` → primes final heightmaps, then `applyBiomeDecoration(WorldGenLevel, ChunkAccess, StructureManager)` (ChunkStatusTasks.java:133-144) | starts 8, carvers 1 | **1** | serial |
| `initialize_light` | `initializeLight` (no generator call) | — | — | light thread |
| `light` | `light` (no generator call) | initialize_light 1 | — | light thread |
| `spawn` | `generateSpawn` → `spawnOriginalMobs(WorldGenRegion)` unless upgrading (ChunkStatusTasks.java:161-169) | biomes 1 | — | serial |
| `full` | `full`: ProtoChunk → `LevelChunk`, wrapped in `new ImposterProtoChunk(levelChunk, false)` (ChunkStatusTasks.java:171-196) | — | — | main thread |

- A region write farther than the step's write radius is refused: `WorldGenRegion#ensureCanWrite`
  (WorldGenRegion.java:244-273), checked first in `setBlock` (`:276-279`).
- **Writes into a chunk that is already FULL are dropped silently:** `ImposterProtoChunk#setBlockState`
  returns null unless `allowWrites` (ImposterProtoChunk.java:80-83), and generation creates it with
  `false`. So nothing generated later can change a footprint chunk once it is FULL.
- Structure pieces are written during `features`, **clipped to the chunk being decorated**:
  `structureStart.placeInChunk(level, structureManager, this, random, getWritableArea(chunk), chunkPos)`
  with `getWritableArea` = that chunk's 16×16 columns (ChunkGenerator.java:334-351, 397-405;
  StructureStart.java:81-98).
- Features run only for biomes **stored** in the 3×3 chunks around the chunk and known to the biome
  source, each with a biome check at its position (ChunkGenerator.java:318-325, 378).
- Carvers: start chunks in ±8 chunks, carver set chosen by the biome source at each start chunk's
  corner, carving only the current chunk and only where `CarvingMask#get` is false; the mask has an
  extra predicate, `setAdditionalMask` (NoiseBasedChunkGenerator.java:252-285; CarvingMask.java:18-37;
  WorldCarver.java:110-111, 225-227).

**F-2. Terrain evaluation.** Per chunk a `NoiseChunk` wraps the router held by the world's
`RandomState`; the block state is `aquifer.computeSubstance(ctx, density)` where density =
`cacheAllInCell(add(router.finalDensity(), beardifier))`, then the ore-vein filler; a null result
becomes the settings' default block (NoiseChunk.java:140-161; NoiseBasedChunkGenerator.java:363-366).
Vanilla's final density is `min(squeeze(0.64 · interpolated(blend_density(…))), noodle)`
(mcdata → noise_settings/overworld.json:30-41, 167): everything inside
`interpolated` is sampled on 4×8×4 cell corners; anything wrapped *around* the whole expression is
evaluated per block. Vanilla's own terrain-blending hooks are three markers: `blend_alpha` and
`blend_offset` inside `overworld/offset` (= `blend_offset·(1−α) + vanillaOffset·α`), `overworld/factor`
(= `10 + α·(spline − 10)`) and `overworld/jaggedness` (= `α·spline`), and `blend_density`
(mcdata → density_function/overworld/offset.json, factor.json, jaggedness.json;
noise_settings/overworld.json:40). The markers are singletons reachable through
`DensityFunctions.blendAlpha()`/`blendOffset()` (DensityFunctions.java:233-239).

**F-3. `RandomState` and the type check.** `ChunkMap` does
`generator instanceof NoiseBasedChunkGenerator n ? RandomState.create(n.generatorSettings().value(), noises, seed) : RandomState.create(NoiseGeneratorSettings.dummy(), noises, seed)`
(ChunkMap.java:182-186) and then `generator.createState(structureSets, randomState, seed)` (`:188`).
`generatorSettings()` is public and not final (javap).

**F-4. What a generator can read when it is constructed.** Only its codec input: literal fields and
registry holders. No server, world path or seed (the seed arrives in `createState` and in
`RandomState`). During datapack loading holders are **unbound** until the registry freezes (mc →
`MappedRegistry#freeze` binds values (MappedRegistry.java:327-333); world presets load after noise
settings, and every registry is frozen only after all have loaded (RegistryDataLoader.java:73-99,
138-146)), so a constructor must not call
`holder.value()`. On the client, `generator.validate()` (and through it
`biomeSource.possibleBiomes()`) runs when a world is opened, before the server exists
(WorldOpenFlows.java:337; ChunkGenerator.java:93-100).

**F-5. Where per-world data can legally live.**
1. Generator codec fields in level.dat (`WorldGenSettings.dimensions["minecraft:overworld"].generator`);
   for a new world they can only be set by code that builds the `WorldDimensions` itself
   (`createFreshLevel`'s dimension function, or `WorldDimensions#replaceOverworldGenerator`,
   WorldDimensions.java:60-64); a dedicated server takes the preset as is (facts table, section 2.3).
2. Files in the world folder, read by our own code on `ServerLifecycleEvents.SERVER_STARTING`, which
   fires before `initServer()` and so before `createLevels` (fapi → MinecraftServerMixin.java:45-48;
   `server.getWorldPath(LevelResource.ROOT)` is valid from construction, MinecraftServer.java:305, 1904-1906).
3. `SavedData` in the level's data storage: exists only after the `ServerLevel` is constructed
   (MinecraftServer.java:366-371), which is too late for the stronghold rings (ServerLevel.java:266).

**F-6. The Blender.** `Blender.of(region)` is `EMPTY` unless `IOWorker#isOldChunkAround(center, 7)` finds,
in the **region files**, a chunk with a `blending_data` tag or a pre-3441 `DataVersion`
(Blender.java:55-56, 67-75; IOWorker.java:45-72, 114-116); the answer is cached per region file and
never invalidated by later saves (IOWorker.java:37, 74-88). An old chunk's data is then read from the
loaded chunk if it has `BlendingData` and is at least at `biomes` status (BlendingData.java:101-110).
`BlendingData` has a private constructor and a codec (`min_section`, `max_section`, optional 16
`heights`), samples heights only from a fixed list of 11 vanilla surface blocks, and also blends
**biomes** of the old chunk outward (BlendingData.java:51-63, 70-98, 177-196; Blender.java:221-253).

#### Options

**(a) A generator that extends or wraps `NoiseBasedChunkGenerator`.**
- *How:* subclass (needs an access widener, lead L10) and override the stage methods; or a separate
  `ChunkGenerator` that delegates (needs a `ChunkMap` mixin to repair `RandomState`, F-3).
- *Inside / edge:* every stage can be skipped or post-processed per chunk. `fillFromNoise` alone cannot
  give block-exact partial chunks or a consistent `getBaseHeight`/`getBaseColumn` (those re-evaluate
  the router, NoiseBasedChunkGenerator.java:109-118, 148-208); it needs the density gate of (b) for that.
- *Registration:* codec in `BuiltInRegistries.CHUNK_GENERATOR`; preset JSON names the type.
- *Upkeep:* one class tracking `NoiseBasedChunkGenerator`'s public methods; signature changes show up
  as compile errors.

**(b) A footprint density function in a copy of the overworld router, plus a wrapping biome source.**
- *How:* `data/kantobridge/worldgen/noise_settings/overworld.json` = vanilla's file with `final_density`,
  `initial_density_without_jaggedness` and `vein_toggle` wrapped in a `kantobridge:…` function; preset
  uses `minecraft:noise` with a `kantobridge:…` biome source. The generator stays a real
  `NoiseBasedChunkGenerator`, so F-3 is satisfied for free.
- *Inside / edge:* terrain, noise caves, aquifers, ore veins, heightmaps, `getBaseHeight`: exact and
  consistent. Not covered by data: carvers (they would carve the stone under Kanto), structure pieces
  reaching in, feature spill, `spawnOriginalMobs` in edge chunks, and the fill is always the default
  block (stone) unless a biome-conditioned surface rule repaints it (biome lookups there are fuzzy,
  BiomeManager.java:31-65). Each gap needs a mixin.
- *Registration:* two Java types (density function, biome source) + three data files.
- *Upkeep:* vanilla's noise settings file (2,541 lines: router `:20-335`, surface rule `:386-…`) must
  be re-copied on every Minecraft update, and a world-gen data pack that edits `minecraft:overworld`
  noise settings would be ignored.

**(c) Vanilla generation, then the builder clears.**
- *Inside / edge:* everything generates and is then removed: millions of block writes with light
  recalculation, fluids left standing (flags 16/18 do not wake them), animals left behind, and
  structure starts and references that stay in chunk data and keep driving spawn overrides
  (ChunkGenerator.java:421-444). Interiors would be boxes cut into live cave systems.
- *Use:* only as a counted safety net.

**(d) Vanilla's `Blender`, Kanto's chunks as "old".**
- Blocked by F-6 in a fresh world: the trigger reads region files and is cached; `BlendingData` cannot
  be constructed directly, does not recognise Kanto's blocks as surface, works per whole chunk, and
  would smear Kanto's biomes ~50 blocks outward. Inside the footprint it does nothing. **Rejected**,
  but its *mechanism* (the three markers of F-2) is exactly the right tool for the ramp, see (e).

**(e) Combination (recommended):** (a) as a subclass, with (b)'s density gate built in Java instead of
copied JSON, the Blender's markers re-purposed for the ramp, and (c) reduced to a counted check.

Per stage, inside the core and at its edge:

| Aspect | (e) inside the core | (e) at the edge (apron, ramp) | (b) alone | (c) alone |
|---|---|---|---|---|
| Terrain | fast path: foundation up to Y 62, air above, interiors as air boxes; no noise sampled | apron: an exact plane at the edge's target height; ramp: vanilla noise with α/offset pulled to the target | exact, but stone | vanilla, cleared later |
| Surface rules | skipped (foundation is not the default block: SurfaceSystem.java:149-153) | vanilla rules on the plane (grass, sand, gravel by biome) | grass/dirt under Kanto | vanilla |
| Aquifers | none (all solid) | consistent: the same gate wraps `initialDensityWithoutJaggedness`, which feeds `preliminarySurfaceLevel` (NoiseChunk.java:196-214; Aquifer.java:389-425); apron fluids are then enforced (water below Y 63 at sea edges, none on land) | consistent, not enforced | vanilla lakes and lava left around |
| Ore veins | none (`vein_toggle` gated to 0: OreVeinifier.java:27-37) | vanilla beyond the apron | same | vanilla |
| Carvers | skipped | masked in core and apron with `setAdditionalMask`; vanilla beyond | not maskable | vanilla |
| Features | none originate (Kanto biomes); spill repaired after each decoration | vanilla; the belt biome decides the look | spill not handled | vanilla |
| Structures | no starts (biome validity) and no pieces (starts whose box meets the core + margin are dropped) | none within the margin | starts only | all, then cut |
| `spawnOriginalMobs` | skipped | skipped for chunks touching core or apron | runs in edge chunks | runs |
| Heightmaps | primed after the fill (62) | vanilla | vanilla | vanilla |
| `getBaseHeight` / `getBaseColumn` | 63 / foundation column, from the gated router | plane / blended | same | vanilla (wrong) |

#### Recommendation

**Option (e).** It is the only one in which every consumer of terrain (chunks, heightmaps, structure
placement, aquifers, the spawn search, `/locate`) sees the same world, it needs no mixin and no copied
vanilla JSON, and it keeps the builder's contract (`blocks_cleared == 0` on a fresh build becomes the
regression gate). What would change the choice: a decision never to use access wideners (then the
wrapper + `ChunkMap` mixin variant of (a), or (b) + mixins), or a world-gen mod that replaces the
generator class.

Cost across Minecraft updates: one access-widener line; one subclass against ~10 public methods; one
record constructor (`NoiseGeneratorSettings`, 11 components, and `NoiseRouter`, 15); the two marker
factories. All break at compile time, none silently.

#### Sketch (implementable; every signature below was checked with `javap` or the sources)

**Registration (Java, in `KantoBridge#onInitialize`, next to `KbBlocks.register()` at
`fabric-bridge/src/main/java/dev/kantobridge/KantoBridge.java:48`):**

```java
Registry.register(BuiltInRegistries.CHUNK_GENERATOR,       id("overworld"),      KantoChunkGenerator.CODEC);      // MapCodec<? extends ChunkGenerator>
Registry.register(BuiltInRegistries.BIOME_SOURCE,          id("region_overlay"), RegionBiomeSource.CODEC);        // MapCodec<? extends BiomeSource>
Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, id("region_gate"),    RegionGate.CODEC.codec());       // MapCodec<? extends DensityFunction>
Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, id("region_alpha"),   RegionAlpha.CODEC.codec());
Registry.register(BuiltInRegistries.DENSITY_FUNCTION_TYPE, id("region_offset"),  RegionOffset.CODEC.codec());
```
(mc → BuiltInRegistries.java:225-226, 233-235; vanilla's own registrations: ChunkGenerators.java:10-14,
BiomeSources.java:7-12, DensityFunctions.java:42-80. The mod already registers into built-in
registries from the same place: `fabric-bridge/src/main/java/dev/kantobridge/block/KbBlocks.java:60-62, 114`.)

**What can be pure data pack, and what needs Java:**

| Pure data (jar `data/`) | Needs Java |
|---|---|
| world presets and their tags; dimension types; biomes and biome tags | a new **generator type** (codec in `BuiltInRegistries.CHUNK_GENERATOR`) |
| noise settings, density functions and surface rules built from registered types; structure sets and structure biome tags; placed features | a new **density-function type** (`DENSITY_FUNCTION_TYPE`) or **biome-source type** (`BIOME_SOURCE`) |
| a preset that *names* our types (`"type": "kantobridge:overworld"`) | anything that depends on a per-world file (the footprint), on other chunks' state (structure filtering, repair) or on stage skipping |

World-folder data packs can hold none of the left column without making the world "experimental"
(`refs/_research/P5_mc_content_testing.md:571-572`); everything here ships in the jar.

**Access widener** (new file `fabric-bridge/src/main/resources/kantobridge.accesswidener`, declared as
`"accessWidener": "kantobridge.accesswidener"` in `fabric.mod.json` and
`loom { accessWidenerPath = file("src/main/resources/kantobridge.accesswidener") }` in `build.gradle`):

```
accessWidener	v2	named
extendable	class	net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator
```
"Classes are made public and final is removed" (https://wiki.fabricmc.net/tutorial:accesswidening,
fetched 2026-10-03). The header `accessWidener v2 named` is the one the pinned Fabric API jar itself
uses (`fabric-transitive-access-wideners-v1.accesswidener:1`); the wiki now shows `classTweaker v1`,
⚠ not confirmed for Loom 1.17.21 / Loader 0.19.5.

**World preset** (new, pure data: `data/kantobridge/worldgen/world_preset/kanto_overworld.json`):

```json
{ "dimensions": {
    "minecraft:overworld": { "type": "minecraft:overworld",
      "generator": { "type": "kantobridge:overworld",
        "biome_source": { "type": "minecraft:multi_noise", "preset": "minecraft:overworld" },
        "settings": "minecraft:overworld" } },
    "minecraft:the_nether": { "type": "minecraft:the_nether", "generator": { "type": "minecraft:noise",
        "biome_source": { "type": "minecraft:multi_noise", "preset": "minecraft:nether" }, "settings": "minecraft:nether" } },
    "minecraft:the_end": { "type": "minecraft:the_end", "generator": { "type": "minecraft:noise",
        "biome_source": { "type": "minecraft:the_end" }, "settings": "minecraft:end" } } } }
```
The stem stays "stable" (no experimental-settings prompt): the type key is `minecraft:overworld` and
the biome source is not a `MultiNoiseBiomeSource` with a foreign preset (WorldDimensions.java:129-137);
jar data has a `KnownPack` (`refs/_research/P5_mc_content_testing.md:562-580`).

**The generator:**

```java
public final class KantoChunkGenerator extends NoiseBasedChunkGenerator {
    public static final MapCodec<KantoChunkGenerator> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.baseBiomes),
            NoiseGeneratorSettings.CODEC.fieldOf("settings").forGetter(g -> g.baseSettings),
            RegistryOps.retrieveGetter(Registries.BIOME)                      // HolderGetter<Biome>, RegistryOps.java:67
        ).apply(i, i.stable(KantoChunkGenerator::new)));

    private final BiomeSource baseBiomes;                       // serialised as given (multi_noise)
    private final Holder<NoiseGeneratorSettings> baseSettings;  // serialised by reference
    private final RegionField field;                            // per generator instance, bound once per server
    private final Supplier<Holder<NoiseGeneratorSettings>> gated;

    public KantoChunkGenerator(BiomeSource biomes, Holder<NoiseGeneratorSettings> settings, HolderGetter<Biome> biomeGetter) {
        this(biomes, settings, new RegionField(biomeGetter));
    }
    private KantoChunkGenerator(BiomeSource biomes, Holder<NoiseGeneratorSettings> settings, RegionField field) {
        super(new RegionBiomeSource(biomes, field), settings);      // NO settings.value() here (F-4)
        this.baseBiomes = biomes; this.baseSettings = settings; this.field = field;
        this.gated = Suppliers.memoize(() -> Holder.direct(RegionNoise.gate(settings.value(), field)));
    }

    @Override protected MapCodec<? extends ChunkGenerator> codec() { return CODEC; }
    public RegionField field() { return field; }

    /** ChunkMap builds the world's RandomState from this (F-3): the gated router, same everything else. */
    @Override public Holder<NoiseGeneratorSettings> generatorSettings() { return gated.get(); }

    // ---- stages ------------------------------------------------------------------------------
    @Override public void createStructures(RegistryAccess ra, ChunkGeneratorStructureState state, StructureManager sm,
                                           ChunkAccess chunk, StructureTemplateManager tm) {
        super.createStructures(ra, state, sm, chunk, tm);
        Footprint fp = field.current();
        if (fp == null || !fp.near(chunk.getPos(), 8 * 16 + STRUCTURE_MARGIN)) return;   // 8 chunks = max structure reach
        for (var e : List.copyOf(chunk.getAllStarts().entrySet())) {                      // ChunkAccess.java:216
            StructureStart s = e.getValue();
            if (s.isValid() && fp.touchesCore(s.getBoundingBox(), STRUCTURE_MARGIN))       // StructureStart.java:71-79
                chunk.setStartForStructure(e.getKey(), StructureStart.INVALID_START);      // ChunkAccess.java:211; StructureStart.java:24
        }   // runs before serverLevel.onStructureStartsAvailable(chunk), which skips invalid starts
    }       // (ChunkStatusTasks.java:40-46; StructureCheck.java:207-216)

    @Override public CompletableFuture<ChunkAccess> createBiomes(RandomState rs, Blender b, StructureManager sm, ChunkAccess chunk) {
        if (field.kind(chunk.getPos()) == Kind.INSIDE)                                     // no NoiseChunk needed
            return CompletableFuture.supplyAsync(() -> { chunk.fillBiomesFromNoise(this.biomeSource, rs.sampler()); return chunk; },
                                                 Util.backgroundExecutor());              // = ChunkGenerator#createBiomes
        return super.createBiomes(rs, b, sm, chunk);
    }

    @Override public CompletableFuture<ChunkAccess> fillFromNoise(Blender b, RandomState rs, StructureManager sm, ChunkAccess chunk) {
        if (field.kind(chunk.getPos()) == Kind.INSIDE)
            return CompletableFuture.supplyAsync(() -> RegionFill.fill(chunk, field.current()), Util.backgroundExecutor());
        return super.fillFromNoise(b, rs, sm, chunk)                                       // gated router does the columns
                    .thenApply(c -> RegionFill.repaint(c, field.current()));               // default block -> foundation in core columns
    }

    @Override public void buildSurface(WorldGenRegion level, StructureManager sm, RandomState rs, ChunkAccess chunk) {
        if (field.kind(chunk.getPos()) != Kind.INSIDE) super.buildSurface(level, sm, rs, chunk);
    }

    @Override public void applyCarvers(WorldGenRegion level, long seed, RandomState rs, BiomeManager bm, StructureManager sm,
                                       ChunkAccess chunk, GenerationStep.Carving step) {
        Kind k = field.kind(chunk.getPos());
        if (k == Kind.INSIDE) return;
        if (k == Kind.EDGE) {
            Footprint fp = field.current();
            int x0 = chunk.getPos().getMinBlockX(), z0 = chunk.getPos().getMinBlockZ();
            ((ProtoChunk) chunk).getOrCreateCarvingMask(step)                              // ProtoChunk.java:288
                .setAdditionalMask((lx, y, lz) -> fp.distance(x0 + lx, z0 + lz) <= APRON); // true = never carve (CarvingMask.java:35-37);
        }                                                                                  // x and z arrive chunk-local (WorldCarver.java:98-111)
        super.applyCarvers(level, seed, rs, bm, sm, chunk, step);
    }

    @Override public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager sm) {
        Kind k = field.kind(chunk.getPos());
        if (k != Kind.INSIDE) super.applyBiomeDecoration(level, chunk, sm);
        if (k != Kind.FAR && field.coreWithinChunks(chunk.getPos(), 1))
            RegionFill.repair(level, chunk.getPos(), field.current());   // 3x3 chunks: clear above the fill, restore foundation
    }

    @Override public void spawnOriginalMobs(WorldGenRegion level) {
        Footprint fp = field.current();
        if (fp == null || !fp.touchesChunk(level.getCenter(), APRON)) super.spawnOriginalMobs(level);   // WorldGenRegion.java:97-99
    }
    // getBaseHeight, getBaseColumn, getSeaLevel, getMinY, getGenDepth, createState, createReferences: inherited.
    // They read the RandomState's (gated) router, so they agree with the generated blocks.
}
```

Chunk kinds (`RegionField#kind`, precomputed per chunk when the footprint is bound): `INSIDE` = all 256
columns in the core; `EDGE` = some column with d ≤ A (core or apron) but not INSIDE; `NEAR` = some
column with d ≤ R; `FAR` = the rest (and every chunk while nothing is bound).

**The gate (built lazily, when `ChunkMap` first asks):**

```java
final class RegionNoise {
    static NoiseGeneratorSettings gate(NoiseGeneratorSettings base, RegionField field) {
        DensityFunction alpha = DensityFunctions.blendAlpha(), offset = DensityFunctions.blendOffset();  // marker singletons
        NoiseRouter r = base.noiseRouter().mapAll(df ->                                    // DensityFunction.Visitor, NoiseRouter.java:49-67
                df == alpha ? new RegionAlpha(field) : df == offset ? new RegionOffset(field) : df);
        NoiseRouter g = new NoiseRouter(r.barrierNoise(), r.fluidLevelFloodednessNoise(), r.fluidLevelSpreadNoise(), r.lavaNoise(),
                r.temperature(), r.vegetation(), r.continents(), r.erosion(), r.depth(), r.ridges(),
                new RegionGate(r.initialDensityWithoutJaggedness(), field, RegionGate.Mode.INITIAL),
                new RegionGate(r.finalDensity(), field, RegionGate.Mode.DENSITY),
                new RegionGate(r.veinToggle(), field, RegionGate.Mode.ZERO_NEAR_CORE), r.veinRidged(), r.veinGap());
        return new NoiseGeneratorSettings(base.noiseSettings(), base.defaultBlock(), base.defaultFluid(), g, base.surfaceRule(),
                field.current() != null ? List.of() : base.spawnTarget(),   // bound: start the spawn search at 0,0 (A2.7)
                base.seaLevel(), base.disableMobGeneration(),
                base.aquifersEnabled(), base.oreVeinsEnabled(), base.useLegacyRandomSource());
    }
}

/** NOT a SimpleFunction: it must map its child so RandomState can wire the noises (lead L11). */
record RegionGate(DensityFunction inner, RegionField field, Mode mode) implements DensityFunction {
    public double compute(FunctionContext c) {
        Footprint fp = field.current();
        if (fp == null) return inner.compute(c);
        int x = c.blockX(), z = c.blockZ();
        float d = fp.distance(x, z);                      // blocks to the core; <= 0 inside; +INF when far (O(1) table)
        if (d > APRON + JOIN) return inner.compute(c);    // ramp and beyond: vanilla, already shaped by RegionAlpha/RegionOffset
        if (mode == Mode.ZERO_NEAR_CORE) return 0.0;      // no ore veins in core, apron and join
        int y = c.blockY();
        if (d <= 0 && mode == Mode.DENSITY) return fp.solid(x, y, z) ? 1.0 : -1.0;  // foundation / air above / interior boxes
        double plane = Mth.clamp((fp.targetTop(x, z) + 1 - y) * 0.25, -1.0, 1.0);   // solid up to the nearest edge's target height
        if (d <= APRON) return plane;   // also inside the core for Mode.INITIAL: the initial density only feeds
                                        // preliminarySurfaceLevel (aquifers, surface rules), which must read "sea shelf"
                                        // on both sides of a sea edge or aquifer centres inside the core would say "dry"
        double t = (d - APRON) / JOIN;                                           // 0..1 across the join
        return Mth.lerp(t * t * (3 - 2 * t), plane, inner.compute(c));
    }
    public void fillArray(double[] out, ContextProvider p) { p.fillAllDirectly(out, this); }
    public DensityFunction mapAll(Visitor v) { return v.apply(new RegionGate(inner.mapAll(v), field, mode)); }
    public double minValue() { return Math.min(-1.0, inner.minValue()); }
    public double maxValue() { return Math.max(1.0, inner.maxValue()); }
    public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }   // {"argument": <df>, "mode": "..."}; field unbound when decoded from data
}

/** Replaces blend_alpha: 0 = "use my offset, factor 10, no jaggedness", 1 = vanilla. */
record RegionAlpha(RegionField field) implements DensityFunction.SimpleFunction {
    public double compute(FunctionContext c) {
        Footprint fp = field.current(); if (fp == null) return 1.0;
        float d = fp.distance(c.blockX(), c.blockZ());
        if (d >= RAMP) return 1.0; if (d <= APRON) return 0.0;
        double t = (d - APRON) / (RAMP - APRON); return t * t * (3 - 2 * t);
    }
    public double minValue() { return 0.0; } public double maxValue() { return 1.0; }
    public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}

/** Replaces blend_offset: the offset whose surface is the nearest edge's target height (same formula as Blender#heightToOffset). */
record RegionOffset(RegionField field) implements DensityFunction.SimpleFunction {
    public double compute(FunctionContext c) {
        Footprint fp = field.current(); if (fp == null) return 0.0;
        return heightToOffset(fp.targetTop(c.blockX(), c.blockZ()));
    }
    static double heightToOffset(double h) { double e = h + 0.5, f = Mth.positiveModulo(e, 8.0);
        return (32.0 * (e - 128.0) - 3.0 * (e - 120.0) * f + 3.0 * f * f) / (128.0 * (32.0 - 3.0 * f)); }   // Blender.java:146-151
    public double minValue() { return -2.0; } public double maxValue() { return 2.0; }
    public KeyDispatchDataCodec<? extends DensityFunction> codec() { return CODEC; }
}
```

**The biome source** (reports what is stored, A2.5):

```java
public final class RegionBiomeSource extends BiomeSource {
    public static final MapCodec<RegionBiomeSource> CODEC = …;       // {"base": BiomeSource.CODEC}; decoded copies are unbound
    private final BiomeSource base; private final RegionField field;
    @Override protected MapCodec<? extends BiomeSource> codec() { return CODEC; }
    @Override protected Stream<Holder<Biome>> collectPossibleBiomes() {       // called before any footprint exists (F-4)
        return Stream.concat(base.possibleBiomes().stream(), field.regionBiomes());   // the three Kanto biomes + belt biomes, by key
    }
    @Override public Holder<Biome> getNoiseBiome(int qx, int qy, int qz, Climate.Sampler sampler) {
        Footprint fp = field.current();
        if (fp != null) { Holder<Biome> b = fp.biomeAtQuart(qx, qy, qz, field); if (b != null) return b; }
        return base.getNoiseBiome(qx, qy, qz, sampler);
    }
    @Override public void addDebugInfo(List<String> info, BlockPos pos, Climate.Sampler s) { base.addDebugInfo(info, pos, s); }
}
```

**The fill** (`RegionFill`): `fill` writes foundation into every section below Y 63 with
`LevelChunkSection#setBlockState(x, y, z, state, false)` (LevelChunkSection.java:58), leaves the
interior boxes of the footprint as air, then `Heightmap.primeHeightmaps(chunk, EnumSet.of(OCEAN_FLOOR_WG,
WORLD_SURFACE_WG))` (Heightmap.java:38). For the deep part (below the noise settings' −64) the same
loop simply runs down to `chunk.getMinBuildHeight()`. `repaint` (edge chunks) turns default-block
positions of core columns into foundation and extends the fill below −64; it also **enforces the
apron's fluids**, because an aquifer's verdict for a block comes from the nearest of its grid centres
(16 × 12 × 16 spacing, Aquifer.java:91-101, 169-216), which may sit a few blocks away over different
terrain: in sea aprons every air block below Y 63 becomes water, in land aprons no fluid stays above
the target top. `repair` (after decoration)
visits core columns in the 3×3 chunks, clears anything above the fill top and restores foundation
below; the features step may write there (write radius 1), and the synchronous steps (everything
except the async bodies of `biomes`/`noise`) run inside one single-consumer world-gen mailbox
(`ChunkMap#runGenerationTask`, ChunkMap.java:190, 196, 673-680), so there is no concurrent writer
(**inference** from that scheduling; the A6.4 GameTest should assert it by counting).

**The foundation block:** a new `kantobridge:foundation` (registered in `KbBlocks`): unbreakable like
bedrock, not in `#minecraft:overworld_carver_replaceables` (so carvers skip it, WorldCarver.java:225-227),
added to `#minecraft:features_cannot_replace` (lakes and geodes honour it: LakeFeature.java:150;
CaveFeatures.java:443), `#dragon_immune`, `#wither_immune`, with a stone-like model so exposed faces
(the underwater wall at sea edges) look natural. It is the "bedrock-like shell" of A2.4 and makes the
under-ground of Kanto mining-proof by itself.

**Binding** (new `server/RegionBootstrap`, registered on `ServerLifecycleEvents.SERVER_STARTING`):

```java
static void onStarting(MinecraftServer server) {
    LevelStem stem = server.registries().compositeAccess().registryOrThrow(Registries.LEVEL_STEM).get(LevelStem.OVERWORLD);
    if (!(stem.generator() instanceof KantoChunkGenerator gen)) return;            // flat legacy worlds: nothing to do
    Path dir = server.getWorldPath(LevelResource.ROOT).resolve("kantobridge/regions/kanto");
    Footprint fp = Files.isRegularFile(dir.resolve("footprint.json"))
            ? Footprint.read(dir.resolve("footprint.json"))                        // every later start
            : planFromPendingBuild(server, dir);                                   // first start: BuildRequest.pending() bundle (client flow)
                                                                                   // or -Dkantobridge.build (headless); writes the file; null if neither
    if (fp != null) gen.field().bind(fp, server.registryAccess());                 // immutable snapshot; biome holders resolved here
}
```
(The stem lookup is the one `createLevels` itself uses, MinecraftServer.java:359, 366.)
`planFromPendingBuild` runs the layout and interior packing only (no art, no cell models): the same
`Layout`/`InteriorPacker` code the builder uses, over the **whole** export (not the map filter), so
the builder later reproduces exactly the placements the generator reserved (A6.5).

**Open:**
- ⚠ Not confirmed by a run: that registering into `BuiltInRegistries.CHUNK_GENERATOR` /
  `BIOME_SOURCE` / `DENSITY_FUNCTION_TYPE` from `onInitialize` is early enough. `KbBlocks.register()`
  does the same for `BLOCK` in the same place (`KantoBridge.java:48`), and vanilla decodes presets only
  at world load, so it should be; the first GameTest of A6.4 proves it.
- Constants to tune by experiment: `APRON` 8, `JOIN` 8, `RAMP` 96, `STRUCTURE_MARGIN` 32 (blocks).
- The fallback if access wideners are refused: the same class as a plain `ChunkGenerator` delegating
  to an inner `NoiseBasedChunkGenerator`, plus one mixin that makes `ChunkMap` use the wrapper's own
  `RandomState` (MixinExtras `@WrapOperation` on the `NoiseGeneratorSettings.dummy()` call,
  ChunkMap.java:185). More fragile; not recommended.

> **Integrator note.** Re-checked by the integrator ([10.3](#103-verification-log), rows 1–7, 10–12): the class is `final`; `ChunkMap` is the only vanilla caller of `generatorSettings()`, so the override changes nothing but the world's `RandomState`; the write radii, the full-chunk rule and the lifecycle order are as cited. One addition from the border design: the mouth of a gate is a fourth edge kind, `open` (flat at Y 63), set by the gate resolver ([4.2](#42-the-footprint-and-edge-design)).

### A2.2 Structures

**Answer (facts).**
- **Starts.** Per chunk and structure set: skip if the chunk already holds a valid start of the set;
  test `placement.isStructureChunk(state, x, z)`; then `structure.generate(...)`, which succeeds only if
  `findValidGenerationPoint` passes the **biome test at the generation stub's single position**:
  `validBiome.test(chunkGenerator.getBiomeSource().getNoiseBiome(quart(x), quart(y), quart(z), randomState.sampler()))`
  (mc → `ChunkGenerator#createStructures`/`tryGenerateStructure` (ChunkGenerator.java:446-546);
  `StructurePlacement#isStructureChunk` (StructurePlacement.java:82-86); `Structure#isValidBiome`,
  `findValidGenerationPoint` (Structure.java:116-126, 189-191)). The test uses the **biome source**,
  not stored biomes.
- **Which sets exist at all** is decided once from `biomeSource.possibleBiomes()` (mc →
  `ChunkGeneratorStructureState#createForNormal`/`hasBiomesForStructureSet` (ChunkGeneratorStructureState.java:52-67)).
- **Pieces** are written chunk by chunk during `features`, clipped to that chunk, for every start the
  chunk *references*; references are collected from starts within ±8 chunks whose bounding box meets
  the chunk (F-1 in A2.1; `ChunkStatus.MAX_STRUCTURE_DISTANCE = 8`, ChunkStatus.java:15). So no
  structure reaches more than 8 chunks from its start chunk.
- **Terrain adaptation** (`beard_thin`, `beard_box`, `bury`, `encapsulate`) changes density up to 12
  blocks around pieces (mc → `Beardifier#forStructuresInChunk`, `isCloseToChunk(chunkPos, 12)`, 24³
  kernel (Beardifier.java:24-32, 36-66)), and is added *outside* the router's final density
  (NoiseChunk.java:151-154), i.e. after any gate.
- **Vanilla reach** (mcdata → `worldgen/structure/*.json`, `worldgen/structure_set/*.json`; piece code):

  | Structure | Placement | Reach from its start | Terrain adaptation |
  |---|---|---|---|
  | Villages (5) | random spread 34/8 | jigsaw, `max_distance_from_center` 80 | `beard_thin` |
  | Pillager outpost | 32/8, frequency 0.2, excluded within 10 chunks of villages | jigsaw 80 | `beard_thin` |
  | Mineshaft | spacing 1, frequency 0.004 | pieces within ±80 blocks of the first (MineshaftPieces.java:82) | none |
  | Stronghold | concentric rings: 128, distance 32, spread 3 | pieces within ±112 blocks (StrongholdPieces.java:188) | `bury` |
  | Trial chambers | 34/12 | jigsaw 116, start Y −40…−20 | `encapsulate` |
  | Ancient city | 24/8 | jigsaw 116, start Y −27 | `beard_box` |
  | Ocean monument | 32/5 | 58 × 58 (OceanMonumentPieces.java:158-160); also requires every biome within 29 blocks to be allowed (OceanMonumentStructure.java:34) | none |
  | Ocean ruins | 20/8 | one ruin plus, for large ones, 4–8 more within about a chunk (OceanRuinPieces.java:160-197) | none |
  | Shipwreck | 24/4 | one piece (ShipwreckPieces.java:77-78) | none |
  | Buried treasure | spacing 1, frequency 0.01 | one piece (BuriedTreasureStructure.java:25) | none |
  | Trail ruins | 34/8 | jigsaw 80, start Y −15 | `bury` |

- **Strongholds:** ring positions are computed when the level is constructed (ServerLevel.java:266):
  ring *m* lies at `4·32 + 32·m·6 ± 40` chunks from the origin, so the first ring is **1,408–2,688
  blocks out**; each position is then moved to a `#stronghold_biased_to` biome within 112 blocks if the
  biome source offers one (ChunkGeneratorStructureState.java:103-157, formula `:121`, search `:128-131`).
  A Kanto near the origin cannot meet one; a region placed farther out can, and its biome answers
  would steer the ring positions (hence the bind at `SERVER_STARTING`, A2.1).

**Is biome validity enough?** No. It stops starts whose *stub* lies in a Kanto biome. A village 60
blocks outside still lays houses up to 80 blocks from its centre, a mineshaft tunnels 80 blocks, an
ancient city 116; all of those pieces would be written into footprint chunks during their `features`
step, and beardifier terrain would bulge up to 12 blocks further.

**Options.**
1. *Biome validity only* (no code). Leaves pieces from outside starts and beard terrain.
2. *Skip structure placement in footprint chunks* (don't run the structure half of
   `applyBiomeDecoration`). No mixin, but structures are cut in half at the border and beard terrain
   remains.
3. **Drop whole starts whose bounding box meets the core + margin** — in the generator's
   `createStructures` override (sketch in A2.1): after `super.createStructures(...)`, replace such a
   start by `StructureStart.INVALID_START` through `ChunkAccess#setStartForStructure` (ChunkAccess.java:211;
   StructureStart.java:24). `createStructures` is public and non-final (javap). An invalid start is
   never referenced or placed (`structureStart.isValid()` guards in ChunkGenerator.java:464-466, 568),
   `/locate` re-checks validity on the real chunk (ChunkGenerator.java:287-291), and it is saved as
   `"INVALID"` (StructureStart.java:100-113).
4. A mixin on `Structure#generate` or `StructureStart#placeInChunk`. Works for any generator class,
   but is a mixin.

**Recommendation:** option 3 with `STRUCTURE_MARGIN` = 32 blocks (≥ the 12-block beard radius plus the
8-block apron), **plus** biome validity for free. Only chunks within 8 chunks + margin of the core
need the check. This is start-level and all-or-nothing, so nothing is ever cut in half.

**Sketch:** `fp.touchesCore(BoundingBox box, int margin)` = any column of
`[box.minX−margin, box.maxX+margin] × [box.minZ−margin, box.maxZ+margin]` has d ≤ 0; with the
distance table of A3.1 this is "min distance over the rectangle ≤ margin", answered from a
coarse 16-block min-distance grid in O(rectangle area / 256).

**Open:** whether the user wants a *wider* structure-free belt for looks (no village roofs peeking
over Route 1's trees). That is a constant.

### A2.3 Spillover

**Answer (facts → margins).**

| Source | How far it can reach into the core | Evidence | Masked by |
|---|---|---|---|
| Carvers (caves, canyons) | 0 from other chunks: a carver only writes the chunk being generated. A tunnel that *starts* up to 112 blocks away can pass through it | NoiseBasedChunkGenerator.java:263-279; WorldCarver.java:57-59; ChunkPyramid.java:31 | skip `applyCarvers` for INSIDE chunks; `CarvingMask#setAdditionalMask` for core + apron columns of EDGE chunks; the foundation is not carver-replaceable anyway |
| Noise caves, aquifers, ore veins | 0: they are functions of the gated router | NoiseChunk.java:140-161 | the gate (and `vein_toggle` → 0) |
| Aquifer fluid decisions near Kanto | look at `preliminarySurfaceLevel` of 13 columns: 1–3 chunks toward −X, 1 toward +X, ±1 in Z | Aquifer.java:120-122, 389-425 | the same gate on `initialDensityWithoutJaggedness`; a sea-edge target low enough that the preliminary surface (sampled every 8 blocks, NoiseChunk.java:202-214) is ≤ 48, so `prelim + 8 < 63` and the global sea applies (`:398-415`): a top of 52 does it, anything above 56 does not |
| Features (trees, lakes, geodes, disks, springs, ores, icebergs, snow) | **at most the 3×3 chunks around the chunk being decorated**, i.e. ≤ 16 blocks past that chunk's edge; and only while the target chunk is not yet FULL | ChunkPyramid.java:33-39; WorldGenRegion.java:244-273; ImposterProtoChunk.java:80-83 | no feature originates in Kanto biomes; the post-decoration repair of core columns (A2.1); foundation in `#features_cannot_replace` |
| Structure pieces | only inside their own chunk; starts up to 8 chunks away | ChunkGenerator.java:334-351, 397-405, 553-584 | A2.2 option 3 |
| Beardifier terrain | 12 blocks around pieces | Beardifier.java:45 | A2.2 margin; gate values of ±1 dominate inside |
| `spawnOriginalMobs` (animals at generation) | the chunk itself (plus a few blocks of pack wander) | NoiseBasedChunkGenerator.java:411-419 | skip for chunks touching core or apron |
| Fluids at run time | water spreads 7 blocks on a level surface, further downhill; lava less | vanilla fluid rules (track 2, A5.7) | generation side: the apron is flat and never higher than Kanto's ground; sea edges put solid foundation, not air, beside vanilla water (A6.2) |
| Biome lookups (`Level#getBiome`) | jitter of about half a cell (2 blocks) around a 4×4×4 biome cell | BiomeManager.java:31-65 | Kanto biomes extend over the whole core, whose outer 12 blocks are ring, not maps |
| Biome colour blend | (2r+1)² blocks, r ≤ 7, default 2 | ClientLevel.java:758-780; Options.java:363-368 | cosmetic (A2.5) |
| Later generation | nothing: writes into FULL chunks are dropped | ImposterProtoChunk.java:80-83 | — |

**Margins that follow (recommendation):**

| Band | Width | Rule |
|---|---|---|
| Core | — | Kanto's; nothing vanilla |
| Apron | A = 8 blocks | exact plane at the edge's target height; no carvers, no structures, no generation-time mobs; vanilla surface blocks and features allowed |
| Structure-free belt | 32 blocks | no structure bounding box |
| Ramp | to R = 96 blocks | vanilla noise with α/offset pulled toward the target |
| Belt biomes (optional, A2.8) | 64–128 blocks | biome chosen by the edge kind |

**Open:** A, R and the belt width are to be tuned in the first play-test; they are constants in one
class.

### A2.4 Underground

**Answer (facts).**
- Today Kanto's interiors are sealed boxes floating in void (F5 in section 2.3); layers 1–5 lie at
  Y 41…−47, inside vanilla's terrain range, layers 6–14 at −69…−245, below vanilla's −64 floor (A1.2).
- A dimension type taller than the noise settings is valid; outside the noise range nothing
  generates (A1.2, A6.3).
- In a vanilla world caves, aquifers, mineshafts and ore veins fill Y −64…surface, and survival
  players can dig.

**Options.**
1. *Reserve only the surface slab and each interior box* (plus a shell), keep vanilla terrain around
   them. Players walking a natural cave would pass under Kanto: by any 2D region test they are
   "inside Kanto" (track 2's rules would have to become 3D), mobs spawn under the routes, and every
   interior needs its own shell against caves, lava and digging.
2. *Put all interiors below −64* (isolated in the extra depth). Needs 14 layers × 22 = 308 blocks
   below −64, i.e. `min_y` ≤ −384, a deeper override, and every warp becomes a 130+ block jump; layers
   1–5 would move. No gain over option 3.
3. **Reserve the whole column of every core cell, top to bottom:** foundation from `min_y` (−256) up
   to Y 62, interiors as air boxes inside it, air above. Under the core the foundation continues
   below vanilla's bedrock floor; outside the core the band −256…−65 stays empty and unreachable
   (bedrock at −64…−60 is still generated there, A1.2).

**Recommendation:** option 3.
- It keeps "inside Kanto" a 2D question for every other track.
- It realises DESIGN §3's "thick solid layers between spaces" for the first time (today it is void).
- **Sealing:** the foundation is unbreakable and not replaceable by carvers or features (A2.1), so
  caves, lava, water and tunnels end at a wall; nothing has to be detected or repaired. Interiors keep
  their own floor, 1-block ring and ceiling (A1.2), now embedded in foundation instead of void.
  Region protection (track 2, A5.7) is then a second line, not the only one.
- **Interiors that overhang the surface maps** (they exist: A1.3) are covered by making the core the
  union of the surface mask and the interior projections (A3.1), and by teaching `InteriorPacker` to
  stay inside the core when it moves an interior sideways.
- **Vanilla mob spawning outside** keeps the dilution of A1.2 as long as the dimension is 576 tall.
  If the measurement of A2.10 shows it matters, the smallest fix is a one-line mixin that makes
  `NaturalSpawner#getRandomPosWithin` start at `max(level min, generator.getMinY())` (track 2, A5.5).

**Sketch:** `Footprint#solid(x, y, z)` for a core column = `y <= 62 && !insideAnyInteriorBox(x, y, z)`;
the interior boxes are the builder's volumes (`WorldPlan.interiorVolume`, `WorldPlan.java:248-251`)
looked up through a per-chunk list.

**Open:** none for generation. (Whether habitat blocks, which sit 2 below the ground inside the
foundation, still work for Cobblemon is track 2's A5.6; the block is placed the same way as today.)

### A2.5 Biomes

**Answer (facts).**
- **Storage:** one biome per 4×4×4 cell per section; `ChunkAccess#fillBiomesFromNoise(resolver, sampler)`
  rebuilds every section's container from a resolver called with quart coordinates (ChunkAccess.java:440-451).
- **What `BiomeWriter` writes:** for every chunk the builder touches it rewrites all cells; a cell
  whose minimum corner lies in a region box gets that region's biome, every other cell keeps the
  stored value (`BiomeWriter.java:46-56`). Boxes: the surface box at Y 56…87 → `kanto_outdoor`; each
  interior volume + 4 blocks at `feet−4…feet+7` → `kanto_interior`, or `kanto_cave` for the CAVERN
  tileset and for interiors with a habitat pool (`WorldPlan.java:300-316`; `WorldPlanner.java:334`).
- **What the biome source reports today:** `kanto_outdoor` everywhere (flat generator, `kanto.json:8`).
  The source is asked whenever a chunk isn't loaded to `biomes` status (`LevelReader#getNoiseBiome` →
  `getUncachedNoiseBiome`, LevelReader.java:58-61; ServerLevel.java:319), for structure validity
  (A2.2), `/locate biome` (ServerLevel.java:1280), carver choice (NoiseBasedChunkGenerator.java:267-271)
  and the spawn search. So today stored and reported biomes differ inside interiors, and nobody
  notices because nothing vanilla generates.
- **Lookups are fuzzy:** `BiomeManager#getBiome(BlockPos)` picks among the 8 surrounding cells with a
  seeded jitter (BiomeManager.java:31-65); vanilla mob spawning, rain, music and fog go through it
  (⚠ not confirmed here that Cobblemon's `spawnable_position.biome` does too: track 2).
- **Colours:** grass, foliage and water tints are averaged over (2r+1)² blocks, r = `biomeBlendRadius`
  0–7, default 2 (ClientLevel.java:758-780; Options.java:363-368); a biome without overrides takes
  its grass colour from temperature and downfall (Biome.java:193-199), which for Kanto's 0.8 / 0.0
  should be a dry, yellowish green (⚠ inferred from the formula, not read off the colour map). The
  sky colour is Gaussian-sampled around the camera (ClientLevel.java:639-645).
  Kanto's own tile blocks are untinted (`ResourcePackWriter.java:157`: `cube_all`, no tint index), so
  only *vanilla* grass, leaves and water within r blocks of the border pick up a share of Kanto's
  colours.

**Options.**
1. Keep `BiomeWriter` only (stored biomes right, source wrong). Vanilla structure checks and
   unloaded lookups would treat Kanto as whatever the multi-noise says.
2. **`RegionBiomeSource` reports exactly what is stored** (A2.1): whole core columns →
   `kanto_outdoor`; interior boxes (+4 blocks, the same boxes as `WorldPlan#biomeRegions`) → their
   biome; optional belt biomes outside (A2.8). Chunks then come out of generation with the right
   biomes and `BiomeWriter` finds nothing to change (it can stay as an assertion, counting changed
   chunks; expected 0).
3. Per-region generated biomes (one biome per map for fog, sky, particles: NEXT_STAGES §3). Possible
   later through the same source; world-folder data packs with worldgen entries are experimental
   (`refs/_research/P5_mc_content_testing.md:571-572`), so they would have to be jar biomes.

**Recommendation:** option 2. Give the three Kanto biomes explicit `grass_color`, `foliage_color`
(and keep `water_color` 4159204, vanilla's default) chosen to match a temperate vanilla forest, so
the blend at the border is invisible; this is a data-only change.

**Cobblemon hooks (for track 2):** the two spawn rules select by `v.spawnable_position.biome.is_of(...)`
(`no_interior_spawns.json:7`, `walking_level_only.json:7-8`); habitat pools are attached to habitat
blocks, not biomes. Outside Kanto the biomes are vanilla's, so Cobblemon's natural spawning sees
ordinary biomes; belt biomes are vanilla biomes too.

**Sketch:** `Footprint#biomeAtQuart(qx, qy, qz)`: `x = qx<<2, y = qy<<2, z = qz<<2`; if the column is
not core → belt biome or null; else the first interior biome box containing (x, y, z), else outdoor.
Same corner rule as `BiomeWriter.java:47-52`, so the two can be compared cell for cell in a test.

**Open:** the colour values (pick in play-test).

### A2.6 Sea level and heights

**Answer (facts).**

| | Block Y | Top surface |
|---|---|---|
| Vanilla sea (sea level 63) | top water block 62 | **62.889** (lead L4) |
| Vanilla shore / beach | top block typically 62–64 | 63.0–65.0 |
| Kanto ground | 63 | 64.0 |
| Kanto water (half tile block) | 63 | 63.5 |
| Kanto void-fill ring (all edge kinds alike, also at sea) | 63…66 | 67.0 (`WorldPlan.java:396-409`) |

- So along a land edge a vanilla top block at Y 63 is flush with Kanto's ground; along a sea edge
  Kanto's water stands 0.611 above the vanilla sea, and today's ring would be a 4-block wall of
  water-art blocks.
- In 1.21.1 `Level#getSeaLevel()` is the constant 63 (Level.java:831-833), while terrain, aquifers
  and structures use the generator's `getSeaLevel()` = the noise settings' value
  (NoiseBasedChunkGenerator.java:401-403; e.g. MineshaftStructure.java:55, OceanMonumentStructure.java:34).
  A custom noise `sea_level` therefore gives two different sea levels in one world (mob logic such as
  Drowned, water animals and phantoms reads the constant: Drowned.java:121, WaterAnimal.java:68,
  Phantom.java:300).

**Options.**

| | What | Result at sea edges | Cost / risk |
|---|---|---|---|
| A | Change nothing in Kanto; make the ring kind-aware (water border → water half blocks, not a wall) | a 0.61 step up from the sea onto Kanto's water, with tile-art sides | none inside Kanto; boats cannot ride onto it (fine: gates are track 2) |
| B | Shift Kanto down 1 (`SURFACE_Y` 63) | Kanto water 0.39 **below** the vanilla sea; standing water faces at the border | constants, data and tests (list below); legacy worlds stay at 64 |
| C | B + water tile 14/16 high instead of 8/16 | flush: 62.875 vs 62.889 | B + a visible change inside Kanto (water almost level with the ground) |
| D | Noise `sea_level` 64 + water tile 14/16 | flush: 63.875 vs 63.889, no shift | every vanilla shoreline is one block wetter; the 63-constant mismatch above |
| E | Transition blocks only (a vanilla water rim at Y 63) | flowing water skirt around Kanto | ugly; rejected |

**What a shift by ±1 touches** (B or C):
- `Scale.SURFACE_Y`/`GROUND_Y` (`Scale.java:21-23`) and everything derived: `feetY`, `maxLayer`
  (layer 14's floor becomes −246, still ≥ −256), `WorldPlan.SURFACE_MIN_Y/MAX_Y` (`:26-27`), the
  surface biome box (`:304`), void-fill heights (`:406`), placements (`WorldPlanner.java:114, 335`).
- `WorldIndex`: new builds write `scale.surfaceY` from the constant (`WorldIndex.java:59`), but each
  map's `y` is stored and all run-time lookups read it (`:141-171`), so **existing worlds keep
  working unchanged**.
- `WorldPlan.volumesOf(previous)` computes the old surface volume from the *current* constants
  (`WorldPlan.java:254-280`): after a shift a rebuild of an old world would leave its old top layer.
  It must use `previous.surfaceY()`.
- Data: `walking_level_only.json:8` hard-codes 63 and 22.
- Fixtures: the golden `synthetic_world_index.json` and many JUnit/GameTest placements at 64/42/20.
- For C also the half tile block's shape and model, and the client's surf height (PROGRESS deviation
  75; tests `DirIntentSpotTest.java:64`, `BlockadeFadeTest.java:67` use 63.5) — rendering/runtime tracks.
- PLAN P1 and P6 are plan defaults (`docs/PLAN.md:140, 145`): a change is the user's decision.

**Recommendation:** **A for the first milestone** (zero change inside Kanto, the gate works at any
height because the target heights are data), and put **C** to the user as the "seamless sea" decision
once a sea edge can be looked at in play. D is not recommended on 1.21.1.

**Consequences for existing worlds:** none for A. For B/C: old worlds are untouched until rebuilt; a
rebuild in place moves everything one block (players are re-seated by the join teleport, PROGRESS
deviation 35), provided `volumesOf` is fixed as above.

**Open:** user decision between A and C; under A, whether sea edges should also be *gates* is track 2.

### A2.7 World creation UX

**Answer (facts).**
- Today's flow creates the level itself with `createFreshLevel` and a preset key
  (`CreateKantoWorldFlow.java:93-94, 410-438`); it never shows vanilla's Create World screen, so the
  game type, difficulty, rules, seed and structure flag are whatever the flow passes (`:420-422`).
- The flat preset is also listed for vanilla's screen through `#minecraft:normal`
  (`…/tags/worldgen/world_preset/normal.json:2-4`); a world made there has no export and stays void.
- A dedicated server picks the preset from `level-type` and the seed and structure flag from
  `level-seed` / `generate-structures` (mc → DedicatedServerProperties.java:107-108, 208-233;
  `tools/e2e/build_world.py:64-73`).
- At creation vanilla looks for a spawn: it starts at `randomState.sampler().findSpawnPosition()`
  (a climate search up to 2,048 blocks out; block 0,0 when the settings' `spawn_target` is empty),
  then tries an 11×11 spiral of chunks, loading each to FULL, for a column with a solid top
  (mc → MinecraftServer.java:419-463; Climate.java:479-491; PlayerRespawnLogic.java:18-61); then it
  loads `spawnChunkRadius` (default 2 → 5×5) chunks around it (MinecraftServer.java:479-487;
  GameRules.java:197-198). The builder moves the spawn later (`BuildTask.java:227-231`).

**Options.**
- *Presets:* (1) replace the flat preset; (2) **add `kantobridge:kanto_overworld` and keep
  `kantobridge:kanto`** (legacy worlds, GameTests' reference, fast e2e builds).
- *Seed:* (1) **random seed, Kanto at the fixed origin** (Pallet Town's cell (0,0) at block 0,0, as
  today); (2) a seed field on the flow's screen; (3) a seed-chosen origin (search the climate sampler
  for a coast). The generator shapes the surroundings itself (A2.8), so (3) buys little.
- *Spawn:* (1) **Pallet Town** (today: `WorldPlanner#chooseSpawn`, `WorldPlanner.java:343-394`);
  (2) a spot just outside Kanto; (3) vanilla's own search.
- *Vanilla's Create World screen:* (1) list the new preset there too; (2) **list neither**: only the
  flow can supply the export.

**Recommendation.**
1. Add the preset `kantobridge:kanto_overworld` (JSON in A2.1) with lang key
   `generator.kantobridge.kanto_overworld`; keep `kantobridge:kanto`. Do not add the new one to
   `#minecraft:normal` (a world created without the flow would be plain vanilla with our generator
   type: harmless, but confusing).
2. Fixed origin, random seed, and an optional seed field later.
3. World spawn stays in Pallet Town; with `spawn_target` emptied in the gated settings (A2.1) the
   initial vanilla search starts at chunk (0,0), which is an INSIDE chunk (fast path) and has a solid
   foundation top, so creation costs a few trivial chunks instead of a far-away search.
4. Flow changes (all in `CreateKantoWorldFlow.java`): the preset key (`:93-94`); `WorldOptions(seed,
   generateStructures = true, bonusChest = false)` (`:422`); the level settings (`:420-421`) become
   track 2's decision (survival outside, Kanto's rules inside); everything else (export, pack,
   `BuildRequest`, progress, cleanup) stays.
5. The footprint is planned on `SERVER_STARTING` from the pending `BuildRequest`'s bundle
   (`BuildRequest.pending()`, `BuildRequest.java:86-89`; matched by the world folder name taken from
   `server.getWorldPath(LevelResource.ROOT)`, because `HeadlessBuild.levelId` needs the overworld,
   `HeadlessBuild.java:95-98`) or from `-Dkantobridge.build` (headless).
6. Headless (`tools/e2e/build_world.py:64-73`): a `--preset flat|overworld` switch; for `overworld`:
   `level-type=kantobridge\:kanto_overworld`, `generate-structures=true`, a fixed `level-seed` so
   report counts are reproducible; the automated gate keeps `flat` (seconds), plus one `overworld`
   build of the Pallet set and one full build in `--e2e`.

**Sketch (what the user sees):** title button → "Create Kanto world" screen gains one choice,
"World: Classic (empty) / In a Minecraft world" → export → pack → "Creating world" → "Building Kanto
(n / m chunks)" → spawn in Pallet Town.

**Open:** user decisions: which preset is the default; whether to expose the seed.

> **Integrator note.** World spawn: this answer keeps it in Pallet Town; [A4.6](#a46-joining-dying-and-respawning-outside) would rather have it outside. M11 keeps Pallet Town (no change to `BuildTask`), and a bed-less player who dies outside is moved to the gate's apron on respawn. Where the spawn finally sits is decision D4.

### A2.8 Placement and surroundings

**Answer (facts).**
- The builder is already origin-relative (`BuildOptions.originX/originZ`, `BuildOptions.java:24-41`;
  `WorldPlanner.Options`, `WorldPlanner.java:50`; GameTests build at 3,200,000).
- Kanto's surface box is 704 × 744 blocks; where it sits relative to Pallet Town is not in any report
  (field `surface.box`, A1.3).
- The first stronghold ring is 1,408–2,688 blocks from the world origin (A2.2). The world border
  allows ±29,999,984 (mc → WorldBorder.java:19-20).
- 67 % of today's surface box is void fill (A1.3): Kanto's maps are a thin network, not a rectangle.
- With the gate of A2.1 the generator can set, per column near Kanto, the terrain's target height
  (`RegionOffset`, the apron plane) and the biome (`RegionBiomeSource`), from data in the footprint.

**Options (where).**
1. **At the origin** (today). Spawn is in Pallet Town; no search; no stronghold nearby.
2. Seed-chosen coast. More natural composition, more code, harder to test.
3. Its own island: force ocean all around. Simple and always "works", but removes the "vanilla land
   leads directly into Kanto" goal on land edges.

**Options (shaping the land around it).**
1. Height ramp only: vanilla terrain eases to Kanto's level whatever the seed put there.
2. **Edge-kind belts:** the footprint stores, for each run of boundary cells, the kind of edge
   (A3.2). Within the belt the generator picks target height *and biome* by the nearest edge:

   | Edge kind | Target top (blocks) | Belt biome | Look |
   |---|---|---|---|
   | tree wall | 63 (flush with Kanto's ground) | `minecraft:forest` | Kanto's tree wall continues as real forest |
   | sea | 52 for ≥ 24 blocks (low enough for the global sea, A2.3), then ramp | `minecraft:ocean` (beach where it meets land) | Kanto's sea continues as ocean |
   | cliff / rock / building back | 63 at the edge rising to ~75 over 16 blocks | `minecraft:windswept_hills` or the vanilla biome | hills behind the wall |
   | open ground | 63 | the vanilla biome | level meadow |

   Beyond the belt (64–128 blocks) the seed's own terrain and biomes take over through the ramp.
3. A hand-drawn surroundings map. Not generative; rejected.

**Recommendation:** origin placement and **edge-kind belts**. It gives the three asks of the spec
(sea-level water edges, hills behind tree walls, a coast along the southern sea) from data the
export already has, works for any seed, and is the same mechanism a second region would use.

**Region layout to reserve (for Johto later; nothing to build now):**
- A region = an origin (multiple of 16 blocks) + a footprint file; regions never overlap, including
  their ramps (R = 96) and belts.
- Reserve a grid of 4,096-block slots: Kanto in the slot around the origin, the next region one or
  two slots away (≈ 4–8 km: several minutes by boat, never in view together without an LOD mod, far
  inside the world border). Johto lies west of Kanto in the games, so the west slot is the natural one.
- The generator's `RegionField` holds a list of footprints; every lookup is "first footprint whose
  padded bounding box contains the column", so a second region costs one more box test.
- Strongholds: a region 1.4–2.7 km out will sit on the first ring; affected strongholds simply fail
  the biome test or the bounding-box filter (A2.2) and the eye of ender leads to the next one.

**Sketch:** `Footprint#targetTop(x, z)` = the target of the nearest boundary run (a second small
table beside the distance table, filled by the same distance transform); `biomeAtQuart` returns the
belt biome while `0 < d ≤ BELT`.

**Open:** user decisions: the belt biomes per edge kind (they decide which Cobblemon spawn next to
Kanto), the belt width, and the slot size.

### A2.9 Determinism and the client

**Answer (facts).**
- Terrain is generated only on the server (integrated or dedicated); clients receive chunks. A
  client never needs the generator's inputs to *see* the world.
- The client gets the WorldIndex on join through `world.index` (S→C, the JSON in 64-Ki-character
  parts with a SHA-256), then `world.slots` when its hash matches the index (`docs/PROTOCOL.md:701-702,
  704-718, 743-748`; `fabric-bridge/src/main/java/dev/kantobridge/server/WorldIndexSync.java:55-58, 96-110, 184-195`).
- The WorldIndex has each map's rectangle, layer and biome (`WorldIndex.java:175-220`), i.e. enough to
  tell "on a map", but not the ring, the filled pockets, the apron or the edge kinds.
- Generation is deterministic given (seed, vanilla data, footprint): the gate is a pure function of
  position and the bound footprint, which is immutable once bound (A2.1).

**What the client needs the footprint for** (no terrain):
1. region lookups: "inside the core", and the signed distance to the border, for the border state
   machine and rule scoping (track 2);
2. rendering: distance-based fades (time, fog, LOD hand-over) (rendering tracks);
3. prediction of protected columns, so the client doesn't predict a block break the server will veto.

**Options.**
1. Recompute on the client from the WorldIndex (same algorithm, versioned). No new message; but any
   difference in algorithm version silently disagrees with the server.
2. **Send the footprint file itself**, the same way as the slot map: a relay message
   `world.footprint{region, index, count, hash, part}` after `world.index`; the WorldIndex gains
   `footprintHash`, and the client accepts the text only if both hashes match (the `world.slots`
   rule). Size: a few tens of KB (A3.1).
3. Put the mask inside the WorldIndex. Simpler, but couples a per-region blob to the per-world index
   that track 2 is splitting per region.

**Recommendation:** option 2. The footprint is layout-derived, so it is marked `x-rom-derived` like
the index; it passes through the host's server under rule U8 (text and layout may, art may not). The
shared class `world/Footprint` (in `src/main`, pure Java) parses it on both sides, so server and
client compute identical distances.

**Sketch:** see A3.1 for the file; protocol addition:
`world.footprint | S→C | region, index, count, hash, part | ROM: yes`, schema
`protocol/world/footprint.schema.json`, catalog regenerated.

**Open:** track 2 owns the region record that will reference `footprintHash`.

> **Integrator note.** The relayed footprint is the JSON file of [A3.1](#a31-the-footprint) (a few KB, not a binary); its hash is quoted by the region record, not by the WorldIndex, so the v1 index stays unchanged ([4.5](#45-the-region-record)).

### A2.10 Performance [measure]

Nothing below was run. Each experiment prints counts and times only.

**E1. Chunk generation: mask against plain vanilla.**
- *Setup:* headless dev server, fixed `level-seed`, three worlds: (V) `minecraft:normal`; (K0)
  `kanto_overworld` with no footprint bound; (K1) `kanto_overworld` with the synthetic footprint;
  (K2) with the real one.
- *Method:* a driver forces square areas to FULL with region tickets (the placer's mechanism) and
  records JFR `minecraft.ChunkGeneration` events, which vanilla emits per chunk and status
  (mc → ChunkStep.java:22-28; ChunkGenerationEvent.java:15-22):

  ```
  for area in [FAR (2,000 blocks from Kanto), RAMP ring, EDGE ring, INSIDE]:
      start JFR; ticket 32×32 chunks; wait until all FULL; stop JFR
      report per status: count, mean, p95, total ms; wall time; chunks/s
  ```
- *Metrics:* ms per chunk for `biomes`, `noise`, `surface`, `carvers`, `features`; chunks/s.
- *Pass:* K0 FAR within 3 % of V (the gate's cost when idle); K2 FAR within 5 %; RAMP `noise` ≤ 1.25 × V;
  INSIDE `noise` + `surface` + `carvers` + `features` ≤ 0.15 × V (fast path).

**E2. Build time with vanilla chunks around.**
- *Facts for the model:* to be FULL a chunk needs its neighbours at `initialize_light` (r 1, so they
  have run `features`), `carvers` (r 2), `biomes` (r 3) and `structure_starts` (r 4–11) (derived by
  hand from ChunkPyramid.java:9-44 and `ChunkStep.Builder#buildAccumulatedDependencies`,
  ChunkStep.java:110-131; `ChunkLevel.RADIUS_AROUND_FULL_CHUNK`, ChunkLevel.java:13-14). For a ~45 × 47
  chunk box that is about 190 chunks decorated, 390 with full terrain, and 1,900 with structure starts
  only, besides the 2,126 fast-path chunks.
- *Method:* `build_world.py --preset overworld` (Pallet set, then all); new report fields
  `gen_ms` (first ticket → all footprint chunks FULL), `place_ms`, `chunks_generated`
  `{inside, edge, outside}`, `max_tick_ms`.
- *Pass:* full build ≤ 2.5 × today's 57 s; `max_tick_ms` ≤ 250 (today the placer may block a tick on
  `level.getChunk`; A6.1 proposes ticketing a window ahead); `blocks_cleared == 0`.

**E3. Memory.**
- *Method:* heap after a forced GC before and after binding the real footprint; and with the world
  loaded at view distance 16 in V against K2.
- *Metrics:* footprint tables (mask, distance, target, chunk kinds) in bytes; heap per loaded chunk.
- *Pass:* footprint ≤ 4 MB per region (estimate: (704+2·96) × (744+2·96) columns at 1 byte for
  distance + 1 for target ≈ 1.7 MB at block resolution, a quarter of that at cell resolution);
  loaded-chunk heap within 10 % of V.

**E4. Mob-spawn dilution (A1.2).**
- *Method:* in V with and without the mod's dimension override, same seed, a player parked at night
  for 5 minutes; count `NaturalSpawner` successes per category with a counter.
- *Pass / decision:* if the override world spawns < 70 % of vanilla's monsters, adopt the one-line
  fix of A2.4.

**Open:** thresholds are proposals; the user may prefer absolute times.

### A2.11 Existing worlds

**Answer (facts).**
- The user's `saves/Kanto` is a flat world rebuilt in place several times with player data kept
  (`docs/PROGRESS.md:99-100, 109-110, 626-627`). Its level.dat names the flat generator.
- Everything that isn't terrain lives in the world folder and is not tied to the generator: player
  data (with Fabric attachments), Cobblemon's stores, `kantobridge/session.json` (the world id used for
  save pairing, PROGRESS deviation 64), the save-pair record, `kantobridge/daycare/` (deviation 83).
- A flat world's generated chunks are void. Every chunk a player ever had in view is saved as a void
  chunk, far beyond Kanto's own 2,126 chunks.
- Builds are deterministic from the export: the same export gives the same placements, slot map and
  pack hashes (`docs/PROGRESS.md:146-147`).

**Options.**
1. **Keep it as a legacy world.** The flat preset and all of today's code paths stay; nothing to do.
2. Convert in place (swap the generator in level.dat). New chunks would be vanilla, but Kanto would
   sit in a moat of already-saved void chunks with no foundation under it; fixing that means deleting
   region data. Rejected.
3. **A new vanilla-world Kanto that imports the player side:** the flow creates the new world, and
   before its first start copies from the chosen old world: `playerdata/`, Cobblemon's folders,
   `kantobridge/session.json`, the save-pair record and `kantobridge/daycare/`. Positions stay valid
   because the origin and scale are the same (and the join teleport re-seats the player anyway,
   PROGRESS deviation 35). The world id travels with `session.json`, so the hidden game keeps its
   paired save.

**Recommendation:** 1 now, 3 as an explicit "Import from an existing Kanto world" step later (it is
the user's data; the agent never opens it). Never 2.

**What carries over to a new vanilla-world build:** Cobblemon data, attachments, pairing: yes, by
copying the folders above. The WorldIndex, slot map, pack and footprint: regenerated, identical
hashes for the same export. Terrain: nothing.

**Open:** the exact Cobblemon folder names to copy and what the save-pair record expects after a copy
are track 2's (A7.2, A8.3). ⚠ Not confirmed here.

---

**A3. The footprint's shape and its seamless edges**

### A3.1 The footprint

**Answer (facts).**
- What the builder owns today is a rectangle (the surface box) plus one box per interior
  (`WorldPlan.java:233-265`). In the real build two thirds of the rectangle are void fill and at least
  11 chunks of interior volume lie outside it (A1.3).
- Surface maps are rectangles on one global cell grid (`Layout.java:26-37`); a cell is 2×2 blocks.
- No file describes the footprint today; the WorldIndex lists map rectangles only (`WorldIndex.java:175-220`).

**Options (shape).**
1. *The bounding rectangle* (today). One box test; but 523,776 reserved columns of which 67 % are
   filler, and it still misses overhanging interiors.
2. *Map rectangles + ring, nothing else.* Tight, but every gap between routes becomes vanilla
   terrain *inside* Kanto, and the boundary becomes several times longer.
3. *A hull:* the maps grown by the ring, concavities closed, enclosed pockets filled. One closed
   outline that hugs the routes; the box's empty corners go back to vanilla.
4. Any of these stored as *a per-cell mask*.

**Recommendation:** store a **per-cell mask** (representation 4) and compute it as a hull (shape 3),
with the parameters written into the file so the choice can change without a format change:

```
maps      = union of the surface maps' cell rectangles (whole export, not the build filter)
grown     = dilate(maps, ring)                       // ring = 6 cells = today's 12-block ring
closed    = erode(dilate(grown, close), close)       // close = 16 cells: gaps under 64 blocks wide vanish
filled    = closed ∪ {cells not reachable from outside the padded box without crossing closed}
core      = filled ∪ dilate(projection of every interior box, shell)     // shell = 2 cells
```
`pockets: "fill" | "wild"` selects whether the third line runs. `fill` reproduces today's look inside
(tree-wall filler between routes); `wild` would leave real terrain between routes, which multiplies
the border and is a later decision for the user.

**Extent.** Vertical: the whole column, `minY … build limit` (A2.4). Horizontal: the mask; interiors
are inside it by construction, and the file also lists their boxes (the generator leaves them as air
and reports their biomes).

**Storage and versioning.** One self-contained canonical-JSON file per region,
`<world>/kantobridge/regions/<region>/footprint.json`, written before the first chunk generates
(A2.1 "Binding"); its SHA-256 is the `footprintHash` that the region record, the WorldIndex and
the build report quote. `version` is the format; `algorithm` names the shape rule. The world never
rewrites it unless the new footprint is a subset (A6.5).

**Sketch (format).**

```json
{
  "version": 1,
  "region": "kanto",
  "game": "blue",
  "exportHash": "<sha256 of the export index>",
  "algorithm": { "id": "ring-close-1", "ring": 6, "close": 16, "pockets": "fill", "shell": 2 },
  "cellBlocks": 2,
  "origin": [0, 0],
  "surfaceY": 64, "layerStep": 22, "minY": -256,
  "grid": { "x": -44, "z": -120, "w": 352, "h": 372 },
  "rows": ["0:0", "6:40,10:96", "…"],
  "edges": [ { "x": 0, "z": 0, "dir": "n", "len": 20, "kind": "wall" }, "…" ],
  "interiors": [ { "x": 2, "z": -6, "w": 10, "h": 10, "layer": 1, "biome": "kantobridge:kanto_interior" }, "…" ]
}
```
- `origin`: block X, Z of the root map's cell (0,0). `grid`: the mask's rectangle in cells relative to
  the origin; cell (cx, cz) covers blocks `origin + 2·c … +1`.
- `rows[i]`: run-length pairs `skip:len` along +X for mask row `grid.z + i`; cells not covered are
  outside. (352 × 372 cells is 16 KB as raw bits; as runs a few KB.)
- `edges`: every boundary side of the core, as runs: `len` cells from (x, z) going east (`dir` n/s) or
  south (`dir` w/e), with the edge kind that picks the target height and belt biome (A2.8, A3.3).
- `interiors`: block origin relative to `origin`, size in cells, layer and biome: enough for the air
  boxes (`WorldPlan.interiorVolume`) and biome boxes (`WorldPlan.biomeRegions`).
- Everything is counts, coordinates and ids; it is layout-derived, so it is ROM-derived data that
  stays in the world folder and travels only as A2.9 describes.

**Derived at load (not stored):** an exact Euclidean distance transform over the grid padded by the
ramp radius gives `distance(x, z)` and "nearest edge run" per cell (so `targetTop` and the belt biome
are table lookups); a byte per chunk gives its kind (A2.1). About 448 × 468 cells × 3 bytes ≈ 0.6 MB
for Kanto.

**Open:** `close` and `pockets` are look-and-feel parameters for the user; the report fields of A1.3
(`footprint.cells`, `perimeter_cells`, `pocket_cells_filled`) give the numbers to decide with.

> **Integrator note.** The integrated format ([4.2](#42-the-footprint-and-edge-design)) adds two things: `algorithm.apron` and `algorithm.ramp`, so server and client derive the same zones, and `gates` with their resolved geometry (inside cells, corridor). A corridor counts as core for generation (foundation below, builder-owned) and as apron for the rules.

### A3.2 The edge inventory

**Answer.**
- **Does the export carry Gen 1's per-map border block? Yes.** gen1-bridge exports `border` from the
  map definition's `borderBlock` (`gen1-bridge/export/kb_export.lua:695-696, 704`), the schema requires
  it (`protocol/export/map.schema.json:15, 41`), Java reads it (`ExportMap.java:23, 167`) and uses it for
  the void fill and interior rings (`MapModel.java:322-326`; `WorldPlan.java:405, 423, 459`).
- **Connections: yes.** `connections{north|south|west|east: {map, offset}}`
  (`kb_export.lua:632-637`; `map.schema.json:18, 50-87`; `ExportMap.java:24, 49-50, 115-122`); the real
  export has 78 directed edges, all consistent (`export_report.json` `layout_edges_agree 78`,
  `layout_edges_disagree 0`). The layout resource repeats the resulting rectangles
  (`ExportLayout.java:14-17`).
- **Which data can classify an edge cell today:** the cell's flags and the builder's model: `WATER`,
  `GROUND`/`LEDGE`/`COUNTER`, or `SOLID` with a measured height class of 2 (trees, fences) or 4
  (cliffs, roofs, facades) (`MapModel.java:34-45, 235-249, 266-289`; `HeightRules.java:106-109`).
  "Cliff" against "building" cannot be told apart without looking at the art (Part B).

**Count on the synthetic bundle** (an outer-edge cell = a surface-map cell whose neighbour in that
direction is not a surface-map cell; `tools/fake_gen1/synth.py:58-69, 88`):

| Side | Cells | tree wall (solid, class 2) | sea | cliff / building (class 4) | open |
|---|---|---|---|---|---|
| North | 20 | 20 | 0 | 0 | 0 |
| South | 20 | 20 | 0 | 0 | 0 |
| West | 18 | 18 | 0 | 0 | 0 |
| East | 18 | 18 | 0 | 0 | 0 |

Surface maps owning outer edges: 1 of 1 (`TEST_TOWN`). Border blocks: 1 solid (trees), 0 water.
Connections: 0 (`synth.py:261`).

**For the real Kanto: not countable without the export.** Fields to add to `build_report.json`
(counts) and `build_diagnostics.json` (map ids, which are symbols):

```
"edges": {
  "by_side": { "north": {"wall2": n, "wall4": n, "sea": n, "open": n}, "south": {…}, "west": {…}, "east": {…} },
  "maps_with_outer_edges": n,                       // ids in the diagnostics
  "border_blocks": { "solid": n, "water": n, "walkable": n },   // per surface map, from its border block's collision tile
  "connections": 78,
  "core_perimeter_runs": { "wall": n, "sea": n, "open": n }      // the footprint's "edges" by kind and total length
}
```

**Recommendation:** classify the *core's* boundary (the ring's outer side) by the border block of the
map that owns each ring cell (the owner is already computed for the void fill,
`WorldPlanner.java:208-258`): water border → `sea`, solid → `wall`; add `tall` when the owner map's
nearest edge cells are class 4. That is deterministic, needs no art analysis, and is exactly what
the generator's belts need.

**Open:** the real counts (first build with the new fields).

### A3.3 Transitions per edge kind

**Answer (facts).** Today every gap cell, whatever its map's border block, is a 4-block column of
border art, Y 63…66 (`WorldPlan.java:396-409`); at a sea route that is a wall of water-art blocks.
The generator can set a target height per column near Kanto (A2.1) and the belt biome (A2.8).
Vanilla's sea surface is 62.889 (lead L4); a land apron whose top block is Y 63 is flush with Kanto's
ground.

**Options and recommendation, per kind.**

| Edge kind | Ring (Kanto's side) | Apron and ramp (vanilla's side) |
|---|---|---|
| Tree wall | keep the 3-high tree-art ring: it is Kanto's inner wall and the hidden game's own barrier | top block 63 for 8 blocks, forest belt, then ramp to the seed's terrain |
| Sea | **make the ring water**: half tile blocks like the map's own sea, not a wall | sea floor 52 for ≥ 24 blocks (vanilla water 53…62), ocean belt, then ramp |
| Cliff / rock | keep the ring (class-4 art would look better: Part B) | rise from 63 to about 75 within 16 blocks, then ramp: hills behind the wall |
| Building / other | as tree wall | as tree wall |
| Height ramp | — | α = smoothstep over A…R; vanilla's own `offset`/`factor`/`jaggedness` blend does the easing |
| Void-fill ring | **configurable per kind** in `algorithm` (`wall` / `water` / `flat`), default as above | — |

The ring stays because it is the cheapest story barrier: its cells are solid (or water that story
cells already block) and belong to no map, so the hidden game never walks there.

**Sketch (the ramp).**

```
A = 8, R = 96                                  // blocks
targetTop(kind, d):                            // top solid block Y at distance d from the core
    wall, open : 63
    sea        : 52                            // low enough for the global sea (A2.3)
    tall       : 63 + clamp(d - 4, 0, 12)
alpha(d)  = 0                       if d <= A
          = s*s*(3 - 2*s), s = (d - A)/(R - A)      if A < d < R
          = 1                       otherwise
offset(x,z)   = (1 - alpha) * heightToOffset(targetTop) + alpha * vanillaOffset(x,z)     // vanilla's own formula, F-2
factor(x,z)   = 10 + alpha * (vanillaFactor - 10)       // flat and firm near Kanto
jaggedness    = alpha * vanillaJaggedness
density(x,y,z)= plane(targetTop)                         if 0 < d <= A          // exact, per block
              = lerp(smooth((d - A)/8), plane, vanilla)  if A < d <= A + 8
              = vanilla (with the offset above)          otherwise
plane(T)(y)   = clamp((T + 1 - y) * 0.25, -1, 1)
```
The resulting surface height is approximately `lerp(alpha(d), targetTop, vanillaHeight)`; it is
exact only in the apron, which is what matters for flush edges.

**Open:** A, R, the sea shelf width and the cliff rise are tuning constants for the first play-test.

### A3.4 Visual continuity

**Answer (facts).** Kanto's blocks are untinted `cube_all` tile blocks
(`fabric-bridge/src/main/java/dev/kantobridge/builder/pack/ResourcePackWriter.java:157`; no `tintindex`
and no colour provider anywhere in `fabric-bridge/src`) with 8×8 art upscaled to 16×16
(`docs/PLAN.md:864-866`); vanilla terrain is tinted by biome with a (2r+1)² blend
(ClientLevel.java:758-780). The two meet at the ring's outer face.

**Options.**
1. *Nothing:* a clean line where the tree-art ring meets real trees.
2. *Belt biomes and matched tints:* forest behind tree walls, ocean behind sea, and Kanto's biomes
   given `grass_color`/`foliage_color` close to the belt's (A2.5), so no colour seam shows in vanilla
   blocks near the border.
3. *A fringe of Kanto-art blocks fading out:* a few scattered tree-art columns in the apron. It needs
   generator-placed Kanto blocks outside the core (so the apron is no longer purely vanilla) and art
   that reads as "a tree" from all sides, which today's `cube_all` blocks do not.

**Recommendation:** option 2 now. From afar what reads is colour and silhouette: a forest belt
behind a 3-high green wall and an ocean behind the sea do most of the work, and both are free with
A2.8. Up close the difference in texture style is unavoidable until Part B gives tree walls real
shapes; revisit option 3 then. (The rendering track owns the final call.)

**Open:** none for generation.

### A3.5 Sightlines

**Answer (facts).**
- Kanto's surface box is 704 × 744 blocks = 44 × 46.5 chunks, diagonal ≈ 1,024 blocks (A1.3).
- Client render distance: 2–32 chunks (32 only with ≥ 1 GB of heap), default 12
  (Options.java:1069-1077). Terrain fog starts at `far − clamp(far/10, 4, 64)` and ends at `far`,
  `far` = render distance × 16 (FogRenderer.java:255-257; GameRenderer.java:1228): 230–256 blocks at
  16 chunks, 461–512 at 32.
- Server view distance is clamped to 2–32 (ChunkMap.java:817-818); the integrated server follows the
  client's setting (IntegratedServer.java:104-107).
- So: from Kanto's centre at 32 chunks the whole box is just inside the fog; from any point *outside*
  Kanto the far side is 700+ blocks away and cannot be seen in vanilla. "See the whole world from a
  distance" is not reachable with vanilla rendering alone.

**Vantage points (generation side).** The belts can raise a hill: an optional
`viewpoints: [{x, z, top, radius}]` list in the footprint's algorithm block makes `targetTop` rise to
`top` around a point in the ramp (for instance 60 blocks above the sea, 150 blocks outside the
south-west corner). At 32 chunks such a point sees about 500 blocks into Kanto: a wide view, not the
whole region.

**What the sightline design needs from an LOD mod** (pins, licences and how it draws modded blocks
are the rendering researcher's):
1. LODs of Kanto must come from the **saved, built chunks**, never from re-running the generator: the
   generator only knows the foundation, so a regenerated LOD would show a flat grey plateau.
2. It must take block colours from the client's own resource pack (the generated tile art), since
   art is never sent.
3. If it generates distant terrain itself, it calls our generator off-thread: the gate is a pure
   function over an immutable footprint, so that is safe; it must run `features` through the normal
   pipeline or accept that the post-decoration repair (A2.1) did not run.
4. In multiplayer, clients need LODs for chunks they never loaded, which needs the mod's server side.

**[measure] fps over Kanto at 16 and 32 chunks (to be run by the user; agents never start the client).**
- *Worlds:* today's flat `Kanto`, and a vanilla-world Kanto of the same export.
- *Camera stops:* P1 Pallet Town at ground level; P2 above Kanto's centre at Y 160, four headings;
  P3 the viewpoint hill, looking at Kanto.
- *Method:* at each stop hold still 30 s with F3's frame-time chart (or the F7 overlay extended with
  an fps logger that writes counts only): average fps, 1 % low, rendered sections (the "C:" line),
  heap in use.
- *Pass:* at 16 chunks ≥ 60 fps average and ≥ 30 fps 1 % low at every stop; at 32 chunks ≥ 30 fps
  average; the vanilla-world Kanto within 20 % of the flat one at P1 (same blocks in view, plus
  terrain behind).

**Open:** the LOD mod decision (user; rendering track presents it).

### A3.6 Sky and time in one overworld

**Answer (facts).**
- Today: `doDaylightCycle` and `doWeatherCycle` false, time set to noon and weather cleared on every
  server start (`KantoGameRules.java:23-24`; `Rules.java:63, 72-79`). All of it is world-wide.
- Time is a property of the level, sent to every player in the dimension once a second and on join
  (mc → MinecraftServer.java:1060; PlayerList.java:694); a dimension type can fix it, a biome cannot.
- What biomes *can* vary: sky, fog and water colours (sampled smoothly around the camera,
  ClientLevel.java:639-651) and precipitation (rain is drawn only in columns whose biome has it,
  LevelRenderer.java:299, 319). Kanto's biomes already have `has_precipitation: false`.
- Gen 1 has no clock; nothing in the hidden game depends on Minecraft's time.

**Options.**
1. *Vanilla day and night everywhere* (turn the two rules on). Kanto gets nights; with skylight only,
   the tile art goes dark at night. Purely cosmetic for the story.
2. *A client-side time override inside Kanto, faded at the border:* the server keeps real time (mobs,
   beds, crops outside); each client renders `lerp(serverTime, noon, w)` with `w` from the signed
   distance to the core (the footprint gives it, A2.9), over the apron and ramp. Inside: today's
   permanent noon. Outside: real time. Each view is self-consistent because the lightmap is global
   per frame.
3. *Biome sky colours only.* Can tint Kanto's sky but cannot make night into day.
4. *Per-player time packets from the server.* No client code, but the once-a-second broadcast has to
   be suppressed per player, and the switch at the border is a jump.

**Recommendation:** 1 on the server plus 2 as a client option that is on by default ("Kanto
daylight"), and rain simply stops at the border because of the biomes. That keeps Kanto looking as
it does today, gives the outside its full day, and needs nothing from generation except the distance
field. Track 2 owns the rule change (A5.4), the rendering track the override.

**How each fits Gen 1:** 1 and 2 both leave the story untouched; 2 matches the Game Boy's permanent
daylight; night in Kanto (1 alone) is the option that changes how Kanto looks.

**Open:** user decision (it is listed in the spec's §8 decisions).

> **Integrator note.** The mechanism chosen for the plan is option 3 of [A5.4](#a54-time-and-weather) (per-player time and weather packets from the server, no client mixin); the client-side override described here is the fallback if once-a-second packets look wrong.

---

**A4. Entering and leaving without a magic warp**

### A4.1 The hidden game's edges

**Answer (facts).**

*An outer edge without a connection refuses the step.* The chain, in the engine and in gen1-bridge:

1. A held direction reaches `OverworldState:handleInput`. Before trying the move it calls `checkEdgeExit(dir)`
   (`OC:1723-1736`).
2. `checkEdgeExit` (`OC:2047-2068`): if the target cell is in bounds it returns false. Otherwise, in order:
   an **edge warp** when the player stands on a warp cell (`Warp.onEdge`, `refs/gen1recomp/src/world/Warp.lua:77-86`;
   only while `canCollisionWarp()`), then a **connection** (`self.map:connection(COMPASS[dir])` →
   `crossConnection`, `OC:2063-2066`; `refs/gen1recomp/src/world/Map.lua:288-290`). With neither, it returns false.
3. `crossConnection` itself refuses (returns false) when the landing cell on the neighbour is not passable
   (`Map.defPassable`, `OC:2109-2116`; `Map.lua:100-110`), so even an edge *with* a connection bumps where the
   neighbour's strip is solid.
4. `handleInput` then calls `Player:tryMove` (`OC:1737`), which asks `Collision.canMove`
   (`refs/gen1recomp/src/world/Player.lua:166-177`). `Collision.verdict` answers out-of-bounds first:
   `if not map:inBounds(tx, ty) then return false, "bounds" end` (`refs/gen1recomp/src/world/Collision.lua:56-59`;
   `Map:inBounds`, `Map.lua:213-215`). The comment above `canMove` says it: "Out-of-bounds is blocked here; the
   OverworldController handles map connections and edge warps before asking" (`Collision.lua:80-83`).
   `tryMove` returns `"blocked", "bounds"`; the player only turns and walks in place (`Player.lua:167-176`).
5. gen1-bridge records every player verdict from the `movement.collision` hook without changing it
   (`gen1-bridge/main.lua:854-864`; `gen1-bridge/lib/kb_engine.lua:1213-1229`). The step driver maps a refused verdict to
   `"blocked", reason` with the engine's reasons `tile`, `entity`, `bounds` (`gen1-bridge/act/kb_move.lua:57`,
   `:190-196`, `:214-217`) and answers `player.blocked{dir, reason, map, x, y, n}` at the hidden player's unchanged cell
   (`kb_move.lua:160-171`, `:265-267`). Protocol: `docs/PROTOCOL.md:172-173`.
6. The client then snaps the Minecraft player back to the hidden cell (`MovementSync.onBlocked`,
   `fabric-bridge/src/client/java/dev/kantobridge/client/move/MovementSync.java:299-319`).

Note for the border design: a Minecraft player who walks off a map **sends no step at all** today (`crossings` needs a
cell on both sides, `MovementSync.java:401-405`), so `player.blocked{bounds}` is only seen for a *push* against a solid
edge (`MovementSync.java:434-453`), which is what happens at the 3-high void ring.

*Is there any other "outside" in the hidden game?* No walkable one. Every legal position is a cell of a loaded map.
The near-misses:

| Case | What it is | Evidence |
|---|---|---|
| Title screen, intro | No overworld on the stack; `snapshot.ingame:false`, the position is only the save's | `gen1-bridge/lib/kb_engine.lua:440-448`; `docs/PROTOCOL.md:148` |
| Seam step | During a connection crossing the player is parked one cell *before* the entry cell of the new map (coordinate −1 or size), then walks the seam step | `OC:2135-2148`; `docs/PROTOCOL.md:266-268` |
| Border art and neighbour strips | Out-of-bounds blocks read the map's `borderBlock`; neighbour maps are drawn as strips with "ghost" NPCs that never collide or talk | `Map.lua:193-198`; `OC:785-802` |
| Exit mats | Walking off the edge from a warp cell is a warp to `LAST_MAP`, not an outside | `Warp.lua:77-86`; `OC:2052-2061` |
| Maps the world doesn't build | COLOSSEUM and TRADE_CENTER (link rooms, no parent warp) | `local/reports/export_report.json` `interiors_without_parent`; `build_report.json` `notes.map_unreachable: 2` |
| A map reloaded out from under the player | The engine sends the player to the heal point | `OC:5578-5583` |

*What gen1-bridge can do to put the hidden player somewhere else.* It uses none of these for positioning today (only the
e2e drivers call `world:warpTo`, e.g. `tools/e2e/drivers/kb_content.lua:333`). Available:

| Call | What it does | What it resets or triggers | What it skips |
|---|---|---|---|
| `mod.world:warpTo(mapId, x, y, facing, opts)` — supported facade (`refs/gen1recomp/src/world/WorldAPI.lua:358-370`). `opts.arrive` = `"fly"`/`"teleport"` picks the arrival FX; `opts.onDone`; `opts.keepMusic` | `ow:startWarpTo(…, {via="warp"})`: remembers the outdoor side for `LAST_MAP` when leaving an outside map (`OC:5419-5422`), sets `transitioning`, pushes a fade (`OC:5464-5553`), then `setMap` at the midpoint; marks `warpEntryCell` (`OC:5490`) | `setMap` (`OC:424-723`): ends parallel map scripts and drops queued scripts of other maps (`:432-452`); `poisonSteps = 0` (`:469`); `strengthActive = false` (`:479`); **cut trees regrow** and card-key doors are stamped shut (`:481-488`); bike forced off where riding isn't allowed (`:492-494`); a Safari game ends when leaving Safari maps (`:504-506`); Flash light reset outside dark maps (`:510-515`); **a fly town is marked visited** (`:520-524`); **the NPC pool is rebuilt, every NPC back at its spawn** (`:538-558`); map music restarts unless `keepMusic` (`:637-658`); forced bike/surf tiles and Seafoam currents are checked (`:665-675`); `map.exited`/`map.entered{via="warp"}` fire (`:427`, `:685-692`); **the map's `onEnter` script runs** (`:703-706`); neighbours rebuilt (`:709`) | No `player.warped` (only `takeWarp` emits it, `OC:5308-5309`); **no `onStep` trigger, badge-gate check or encounter roll at the landing cell** (those run only from `onStepComplete`, `OC:4691-4703`, and at a fresh boot, `OC:716-721`); no door walk-out. **No walkability check of the target cell** (only `via == "boot"` repairs a bad cell, `OC:559-567`) and the surf state is not changed (`OC:592-619`), so a warp onto water leaves a walker on water — the soft-lock the engine's own comment describes (`OC:587-590`) |
| `ow:setMap(mapId, x, y, facing, {seamless=true, via="reload", keepMusic=true})` — internal; what `WorldAPI:toggleObject` does for a same-map reload (`WorldAPI.lua:383-386`) | Places the player with no fade | As `setMap` above, except: keeps `poisonSteps` and the NPC pool (`OC:469`, `:538-550`), keeps the music. `map.entered{via="reload"}`, which gen1-bridge does **not** treat as a warp (`gen1-bridge/observe/kb_warp.lua:42`): the client would only see a `player.pos` kind `sync` | Same skips as above |
| Writing `p.cellX/cellY/px/py` — internal | Moves the sprite only | Nothing | Everything; "only suitable for emergency resync" (`refs/_research/P1_gen1_movement.md` §2) |
| `ow:warpToHealPoint(onDone, opts)` — internal (`OC:5387-5404`) | To the fly spot of the last heal point's town | Clears surfing and `forcedBike`, then as `warpTo` | — |
| `ow:flyTo(mapId)` / `mod.world:flyTo` (`OC:2441-2458`; `WorldAPI.lua:314-324`) | Fly, with its animation; the facade needs FLY in the party and a visited town | As a warp with `via="fly"`; clears bike and surf | — |
| `act.field` (`fly`, `dig`, `teleport`, …) — already in the protocol (`docs/PROTOCOL.md:199-213`; `gen1-bridge/lib/kb_engine.lua:4247-4381`) | The game's own travel, after its own checks | As above | — |

A mod warp is visible to the client: `startWarpTo` sets `transitioning`, so `kb_warp` sends `warp{start, kind:"script"}`
and, at `map.entered{via="warp"}`, `warp{arrive, to}` (`gen1-bridge/observe/kb_warp.lua:103-126`). Today's client would
fade and send `warp.request` for it (`MoveController.java:161-184`).

**Options:** n/a (audit). Used by A4.3.

**Recommendation.** For anything the bridge itself asks for, use only `mod.world:warpTo` (supported, runs `onEnter`, and
its effects are the same as any map entry), only to a cell the hidden player has stood on before, and only on land (or
with the surf state set first). Never `setMap`/cell writes for gameplay.

**Open.** None.

### A4.2 Leaving — the gate model and the border state machine

**Answer (facts the design rests on).**

1. Walking off a map sends no hidden step (`MovementSync.crossings` returns when the new position is on no map,
   `MovementSync.java:401-405`); the only reaction today is the drift teleport after 500 ms (A1.6).
2. The hidden game has no "outside" (A4.1), so while the Minecraft player is out, the hidden player must stand on a real
   cell. It can be frozen there with a hold state, a pattern gen1-bridge already uses for Bill's PC
   (`gen1-bridge/lib/kb_engine.lua:3362-3377`; only the top state updates, `refs/gen1recomp/src/core/StateStack.lua:60-63`).
   Left running, it would keep checking trainer sight every update (`OC:1553-1554`) and could start a `battle.start` for
   a player who is far away.
3. Per-player collision without a mixin already exists: `StoryCellBlock.getCollisionShape` decides by the moving
   entity's own state on both sides (`fabric-bridge/src/main/java/dev/kantobridge/block/StoryCellBlock.java:61-80`;
   `.../story/StoryStates.java:45-67`), and the client applies a state change locally before relaying it so prediction
   and server agree (`.../client/story/ClientStoryView.java:148-163`; packet order argument in
   `refs/_research/P4_mc_runtime.md` §3c).
4. gen1-bridge can save the hidden game by itself behind the engine's quick-save gate (`Game:writeSave`, used for the
   Pokémon Center save, `kb_engine.lua:3260-3285`), and each save reaches Minecraft as `save.begin`.
5. `act.result` already carries free-form `kind` and `reason` strings (`protocol/messages/act.result.schema.json`:
   `kind` is a `symbol`, `reason` a lowercase code), so new answers need no change to that schema.

**Options — where a player may leave.**

| Option | How it works | Cost / risk |
|---|---|---|
| (1) Every outer edge | Any edge cell without a connection becomes an exit | Almost every outer edge of Kanto is solid art (trees, rock, buildings), so exits would exist only where a walkable or water cell touches the edge. Uncontrolled: each such cell is a possible entry too (A4.4) |
| (2) Sea edges only | All surfable outer water | Needs Surf to leave at all (late game). Good for "sail to Johto", useless for a first milestone |
| (3) **Designated gates** | The region record lists gates. A gate is an edge cell (or a run of cells) plus an outward direction; kinds `land` (an opening the builder cuts through the edge art) and `sea` (a run of outer water cells) | One list drives the builder, the collision and the entry policy. Chosen |

**Recommendation.** (3). Leaving is possible exactly at gates; a gate is passable only for a player whose hidden game
agrees. The state machine below replaces the seed sketch in the spec's §9.

**Sketch — the model.**

*Zones* (from track 1's mask; I need three classes per cell, see A8.2): `OUT` (vanilla), `APRON` (inside the footprint,
walkable by anyone under Kanto's protection rules: the transition strip and the gate thresholds), `CORE` (every map
cell, the solid ring and the gaps: only followed players, creative and spectators may be there).

*Gate* (region record, A8.1): `{id, kind, map, x0, y0, x1, y1, dir}` — the inside cells `G` on the map edge and the
outward direction; the **threshold** `T` is the row of apron cells `G + dir`. The builder places an invisible
**gate block** (`kantobridge:gate_cell`, a copy of `StoryCellBlock` whose block entity holds the gate id) on every
threshold cell, 3 blocks high.

*Presence* (new attachment `kantobridge:region_presence` on the player, both sides like `StoryState`; the server's copy
is persistent and `copyOnDeath`):
`{region, state: NEW|INSIDE|OUTSIDE, home: gateId?, admitted: gateId?}`.

*Gate block collision* (common code, both sides):

```java
// GateCellBlock.getCollisionShape(state, level, pos, ctx) — same shape of code as StoryCellBlock.closedFor
Entity e = ctx instanceof EntityCollisionContext c ? c.getEntity() : null;
if (e == null) return Shapes.block();                     // pathfinding, caches: a wall
Player p = StoryStates.governingPlayer(e);                // the rider counts for boats and Cobblemon rides
if (p == null) return Shapes.block();                     // mobs and wild Pokémon never pass a gate
if (p.isCreative() || p.isSpectator()) return Shapes.empty();
RegionPresence pr = RegionPresence.of(p);
String gate = cellGate(level, pos);                       // from the block entity
return pr.state() == INSIDE || gate.equals(pr.admitted()) ? Shapes.empty() : Shapes.block();
```

*Client states* (new `client/region/BorderMachine`, plain Java, one per region the player is near):

```
OUTSIDE    not following. MovementSync unlinked. No input lock, no pause overlay, no hidden-game screens.
ENTERING   a region.enter is in flight (or its warp is). Hidden-game screens allowed (the title, a warning).
FOLLOWING  today's behaviour, unchanged.
LEAVING    a region.leave is in flight; the player already walks in the apron.
```

```
FOLLOWING → LEAVING
   when  last tick's cell ∈ gate.G  and  this tick's feet are in gate.T  (so the feet left the map through the gate)
   do    sync.setLinked(false)                         // no steps, no drift teleport
         bridge.send region.leave{region, gate}        // answered by act.result (re)
LEAVING → OUTSIDE
   when  act.result{ok:true, kind:"leave"}
   do    presence := {OUTSIDE, home: gate, admitted: gate}     // local first, then
         relay region.state{region, inside:false, home: gate, admitted: gate}
LEAVING → FOLLOWING   (the hidden game refused: a script started, a trainer saw the player, not on the gate cell)
   when  act.result{ok:false}  or  3 s without an answer  or  the link drops
   do    sync.setLinked(true); mover.walkTo(nearest cell of gate.G, SNAP_FRAMES)   // then today's rejoin rules
OUTSIDE → ENTERING
   when  feet cross from gate.T into gate.G                      (home gate: the gate block is open, entry is optimistic)
      or feet stand in gate.T of another gate for 10 ticks       (the gate block is closed: ask first)
   guard bridge CONNECTED, snapshot.ingame, SessionGate OPEN, no Cobblemon battle; else stay OUTSIDE with a HUD reason
   do    bridge.send region.enter{region, gate, to:{map,x,y,facing: opposite(gate.dir)}}
ENTERING → FOLLOWING
   when  act.result{ok:true}  and  the feet are on a map cell  (for "enter_warp": after its arrival, see the note)
   do    presence := {INSIDE, home: null, admitted: null}; relay region.state{inside:true}
         sync.linkFrom(hiddenSpot)   // new: linked := true, last := the hidden cell, hasLast := true, no rejoin.
                                     // Feet on the hidden cell: nothing is sent. Feet one cell further in (the player
                                     // kept walking during the round trip): the ordinary crossing rule sends that one
                                     // step, exactly as if following had never stopped. Farther: today's drift rule.
   note  kind "enter_warp": the hidden player was elsewhere and is being warped to the gate. The warp{start/arrive} that
         follows is consumed here (sync.onWarpArrive(to)) with no fade and no warp.request; then presence.admitted := gate
         (local first, relayed), which opens the gate block, and the player walks in.
ENTERING → OUTSIDE
   when  act.result{ok:false}  or  5 s without an answer  or  the link drops
   do    if feet are on the map: mover.walkTo(nearest cell of gate.T)      // the optimistic entry is undone
         presence.admitted := null (relayed); HUD line with the reason
any → OUTSIDE   on world join when the server's presence says OUTSIDE (A4.6); on a server bounce (below)
any → FOLLOWING on world join when presence is NEW or INSIDE (today's join teleport, MovementSync.rejoin)
```

*What the hidden game does* (new `gen1-bridge/act/kb_region.lua` + two `kb_engine` functions):

```
region.leave{gate}:   wait (≤ 60 logic steps) until the player stands, mode == idle; else ok:false not_idle
                      hidden cell ∉ gate cells              → ok:false not_at_gate
                      mod.save:set("region", {away = {gate = gate}, unlocked = {…, [gate] = true}})   -- before the save, so the save has it
                      Game:writeSave() behind quickSaveAllowed (optional, recommended) → the usual save.begin{reason:"menu"}
                      mover.releaseAll(); engine.away(gate)  -- push the hold, Music.fadeOut
                      → act.result{ok:true, kind:"leave"}
region.enter{gate,to}: not in game                           → ok:false not_ingame
                      away hold for this gate, or hidden player standing idle on a gate cell
                                                             → engine.back(): pop the hold, Music.restoreMap
                                                             → act.result{ok:true, kind:"enter"}
                      otherwise (hidden player elsewhere): A4.3's policy; when allowed
                                                             → pop any hold, mod.world:warpTo(to.map, to.x, to.y, to.facing)
                                                             → act.result{ok:true, kind:"enter_warp"}  (warp{start/arrive} follow)
                                                             else ok:false locked | not_idle | no_party
link drops while away: pop the hold (so Gen1Recomp can be played alone); the `away` marker in mod.save stays
snapshot:             adds away:{gate}? when the hold is up (optional field), so a reconnecting client knows
```

*What the server does* (new `server/RegionService`):

```
END_SERVER_TICK, per player:  zone := regions.classAt(blockX, blockZ)           // O(1), A8.2
   zone changed                → RegionRules.apply(player, zone)                 // A5: speed, jump, protection profile
   zone == CORE and presence.state != INSIDE and admitted == null
        and not creative/spectator and not BuildHold-held, for more than 20 ticks
                               → player.teleportTo(lastGood) ; relay error{code:"region.not_admitted"}
   zone != CORE                → lastGood := position
region.state{region, inside, home?, admitted?} (C→S): accepted only for the sender; stored in the attachment
   inside:true is accepted only when zone ∈ {APRON, CORE}; inside:false always
```

*While the player is out:* the hidden player stands on the gate cell under the hold: NPCs, scripts, trainer sight,
step counters and encounters are all frozen (nothing below the top state updates); the music is faded out; `party.sync`
and `dex.sync` still apply (`engine.partySafe` only needs an overworld and no battle state, `kb_engine.lua:2460-2474`);
the pause overlay and the input lock never engage because they follow `FOLLOWING` (A5.8); a dropped link shows only the
status line.

**Messages.** New: `region.leave` (M→G), `region.enter` (M→G), both answered by the existing `act.result`;
`region.state` (C→S). Reused: `act.result`, `warp`, `save.begin`, `error` (S→C, code `region.not_admitted`).
Optional additions: `snapshot.away`. The spec's sketch message "region.leave to the server" becomes `region.state`,
because the server needs the presence, not the event.

```json
{"type":"region.leave","seq":41,"data":{"region":"kanto","gate":"g1"}}
{"type":"region.enter","seq":57,"data":{"region":"kanto","gate":"g1","to":{"map":"ROUTE_22","x":0,"y":5,"facing":"right"}}}
{"type":"act.result","seq":90,"re":57,"data":{"ok":true,"kind":"enter"}}
{"type":"region.state","seq":12,"data":{"region":"kanto","inside":false,"home":"g1","admitted":"g1"}}
```

**Changes in our code (files).**

| File | Change |
|---|---|
| `client/move/MovementSync.java` | `Port.leaving(Gate)`; in `tick` (`:193-224`), before `crossings`/`drift`: if `last` is a gate cell and the feet are in its threshold, call it and skip drift. `onWorldJoin` (`:179-185`) and `setIndex` (`:153-162`) set `rejoinWanted` only when the border machine says `FOLLOWING` is expected |
| `client/move/MoveController.java` | `following` (`:90`) = Kanto world ∧ not free camera ∧ **border state FOLLOWING**; `InputLock.reason` gets that instead of "index present" (`:103-104`); `onWarp` (`:161-184`) skips the fade for an expected `enter_warp` arrival |
| `client/move/MoveRuntime.java` | builds the `Frame` with the border state (`:123-125`); registers `act.result` for leave/enter |
| `client/move/InputLock.java` | first parameter of `reason` (`:107-110`) means "following", not "Kanto world" |
| new `client/region/RegionRuntime`, `BorderMachine`, `ClientRegions` | the machine above; region records and footprints from the relay (A8) |
| new `block/GateCellBlock` (+ block entity), `KbBlocks.java:56-63` | registration next to `story_cell` |
| new `server/RegionService`, `server/RegionRules`, `story/RegionPresence` | presence attachment, zone tick, bounce, `region.state` handler (`RelayService.register`) |
| `gen1-bridge/act/kb_region.lua`, `lib/kb_engine.lua`, `main.lua:434-459` | the two requests, the away hold, `mod.save` keys |
| `protocol/messages/region.*.schema.json`, `docs/PROTOCOL.md`, catalogs | three message types |

**Tests.** JUnit: `BorderMachineTest` (every transition above, with a fake port; timeouts; link drop in each state);
`MovementSyncTest` additions (leaving through a gate sends no step and no teleport; leaving elsewhere still drifts back).
lupa: `test_lua_region.py` (leave refused when not idle / not at the gate; hold pushed and popped; enter by warp only
when unlocked; hold released on link drop; `mod.save` keys). GameTest: `GateGameTests` (a mock player with presence
OUTSIDE collides with a gate block, INSIDE or admitted does not; a cow never passes; the bounce moves a non-admitted
player out of CORE and leaves a creative player alone). `fake_gen1`: answer `region.leave/enter` for the stand-in
integration test.

**The minimal version (first milestone, one working gate).** One gate; **no** entry at other gates (so no unlocked set
and no `enter_warp`); `region.enter` succeeds only when the hidden player is parked at, or standing on, the gate cell,
otherwise the HUD offers today's teleport ("return to where your game is": a kind-less `warp.request` to the hidden
cell, the existing `MovementSync.rejoin`). No save on leaving yet (add it with the multi-gate version). Needed: the
three messages, `GateCellBlock`, the presence attachment, the border machine with four states, the `FOLLOWING` scoping
of `MoveController`/`InputLock`, the away hold, and the server bounce.

**Open.** (1) Save on leaving: recommended, a user decision (it is an automatic save the original game never makes).
(2) Whether the hold should also be released when the *Minecraft* client disconnects cleanly (recommended: yes, the
marker in `mod.save` is enough). (3) Gate width and placement belong to track 1 (A3.3).

> **Integrator note.** Fitted to the world-generation design: the threshold is the first cell of a **corridor** that runs through the 6-cell ring (and through up to three solid edge cells of the map itself) to the apron, so the inside cells need not lie on the map's last row or column ([4.2](#42-the-footprint-and-edge-design)). For `MovementSync` every position whose zone is not `CORE` counts as on no map, so a corridor cell that lies inside a map rectangle never produces a step. With gate blocks solid for a non-admitted player, "standing in the threshold for 10 ticks" becomes "pushing against the gate for 10 ticks". `region.state` also travels S→C once on join. Gate ids are plain words (`west`).

### A4.3 Entering

**Answer (facts).**

- If the Minecraft player stands on the hidden player's cell when following resumes, nothing is corrected:
  `setLinked(true)` sets `rejoinWanted`, and `rejoin` does nothing when the spots are equal
  (`MovementSync.java:165-176`, `:505-514`). If the spots differ, `rejoin` **teleports** (`:511-513`), which is the
  "magic warp" to avoid; the crossing rule would instead send one hidden step when the two are a cell apart
  (`:401-428`). `MovementSync` keeps `last`/`hasLast` up to date even while unlinked (`:220-223`), so resuming needs a
  small new entry point (`linkFrom`, A4.2) rather than `setLinked`.
- A hidden-only warp is possible (`mod.world:warpTo`), with the resets listed in A4.1: NPCs back to spawn, cut trees
  regrown, Strength off, poison steps zeroed, the map's `onEnter` run, a fly town marked visited, **no step trigger at
  the landing cell**.
- Step triggers are how Gen 1 gates progress in many places, and they exist only as scripts in the hidden game:
  map `onStep` hooks ("Pallet intro, Saffron gate guards, ...", `OC:4691-4697`), the badge guards (`checkBadgeGate`,
  evaluated on a completed step and at a fresh boot only, `OC:4702-4703`, `:716-721`, `:4909-4967`), spinners and forced
  tiles (`OC:4699-4707`). None of them is in the WorldIndex: its story-cell table holds only STAY objects, swap sites,
  water and boulder areas (`protocol/world/world_index.schema.json:81-155`), and it has no walkability, warps or
  connections at all (`:45-79`).

**Can reachability be computed, and by whom?**

| Who | What it would need | Verdict |
|---|---|---|
| Server (`StoryState` + WorldIndex graph) | A walk graph it doesn't have today: per-cell passability, ledge directions, warps and connections would have to be added to the region data (the export has them: `cells` flags, `warps`, `connections`, `protocol/export/map.schema.json:50-106`; about 94,876 cells, `export_report.json` `cells`). A BFS over that is trivial (≪ 10 ms). | **Not sound.** The script gates above are invisible to it, so it would call cells behind a badge guard or a thirsty guard reachable. Fixing that means hand-curating every script gate, the same kind of audit as the swap sites (95 sites, `export_report.json` `swap_sites`) but for behaviour, with no engine hook to detect a miss |
| gen1-bridge | Map data and flags are all there, but the gates are Lua functions (`mapScripts.get(id).onStep`), not data. It cannot evaluate them without running them | **Not sound** either, for the same reason |
| Nobody: use history instead | "This save has stood on that gate cell before" | **Sound by construction**, cheap, and it is the rule Gen 1 itself uses for Fly (`save.visited`, `OC:520-524`; `WorldAPI.lua:80-91`) |

**Options — the entry policy when the hidden player is not at the gate.**

| Option | How | Story safety | UX |
|---|---|---|---|
| Refuse | Only the gate where the hidden player waits opens | Trivially safe: the hidden game is untouched | You must return by the gate you left by. With one gate that is no restriction at all |
| Hidden-only warp, any gate | `warpTo` the gate cell on request | **Unsafe**: any gate becomes an entry, whatever the story state (A4.4) | Free |
| Hidden-only warp, **unlocked gates** | `warpTo` only to a gate this save has left through before (`mod.save.region.unlocked`) | Safe: the player reached that cell from inside in this save; equivalent to Fly to a visited place; the warp is the engine's own map entry | Leave by A, come back by B once both were used |
| Scripted hidden walk | Path-find and walk the hidden player to the gate | Needs the same unsound reachability, takes minutes across Kanto, fires trainers and triggers on the way | No |
| Reachability | BFS from the hidden cell under the current story state | Unsound (above) | — |

**Recommendation.** "Home gate, plus unlocked gates":

1. The gate where the hidden player is parked always works (nothing moves in the hidden game).
2. Another gate works only if it is in the save's unlocked set (it was left through before, in this save), the hidden
   game is idle and standing, and the gate's own requirement holds (a sea gate: the engine's Surf eligibility, see A4.4).
   gen1-bridge performs it with `mod.world:warpTo`; nothing in Minecraft places the hidden player.
3. Everything else is refused with a reason, and the client offers the existing teleport back to the hidden cell.

*Why it is story-safe:* (a) every decision that moves the hidden player is taken by gen1-bridge from the hidden save,
never by the server; (b) the hidden player only ever lands on a cell it has already stood on in this save, so no step
trigger between two places is skipped that Fly would not also skip; (c) a New Game has an empty unlocked set because
the set lives in the save's own mod data (`mod.save`, restored by CONTINUE with the save, as the save pair is:
`docs/PROTOCOL.md:556-560`); (d) the non-entry paths are closed physically and by the server bounce (A4.2), so no entry
bypasses the question.

What would change the choice: if the user wants free entry anywhere, it has to be an explicit "sequence breaks allowed"
world setting; the mechanism is the same with the unlocked check turned off.

**Sketch.** See `region.enter` in A4.2. The guard for sea gates and compatibility (A7.1):

```lua
local function mayEnter(gate)
  if not engine.inGame() then return false, "not_ingame" end
  local st = mod.save:get("region") or {}          -- {away = {gate = id}?, unlocked = {[id] = true}}; `mod.save:get/set` as in main.lua:174-175
  if st.away and st.away.gate == gate.id then return true, "enter" end
  local f = engine.observe()
  if onGate(f, gate) and Player.classify(f) == "idle" then return true, "enter" end
  if not (st.unlocked or {})[gate.id] then return false, "locked" end
  if Player.classify(f) ~= "idle" or f.moving then return false, "not_idle" end
  if gate.kind == "sea" and not engine.surfEligible() then return false, "no_move" end   -- partyKnows SURF + badge
  return true, "enter_warp"
end
```

**Open.** For a sea gate reached by warp the hidden player must be surfing on arrival: the engine has no public call for
"start surfing here"; the boot path sets `player.surfing` directly (`OC:592-606`). ⚠ Not confirmed that writing
`ow.player.surfing = true` followed by `Music.setSurfing` is enough for a mid-game warp (a spike for the sea-gate
milestone, not the first one).

### A4.4 Story gates on the outer boundary

**Answer.**

*Facts from our data.* The real export has 36 surface maps joined by 78 directed connection edges
(`export_report.json` `layout_maps`, `layout_edges_agree`). 36 maps have 144 sides, so **66 map sides have no
connection**; how many of those lie on the outer boundary (rather than against a gap inside the bounding box), and what
their edge cells are, is not in any report. The fields a future build should add:
`outer_edge_cells{north,south,west,east}{walkable,water,solid}`, `outer_edge_maps` (count),
`gates{land,sea}` and `gate_cells`. Water is everywhere per-player story collision: 9,301 water story cells
(`build_report.json` `story_cells_by_type.water`), closed for anyone who is not surfing, including a player with no
story state and any entity a player steers (`StoryState.java:33-44`; `StoryStates.java:45-67`).

*Mechanics (synthetic bundle and code).* A story cell is one block high at feet level
(`WorldPlan.java:452-457`); walls are 2 high, roofs 4, the ring 3 (`HeightRules.java:17-21`). That is enough only
because jumping is off. A player with vanilla jump (about 1.25 blocks: my arithmetic from jump strength 0.42, gravity
0.08 and the 0.98 vertical drag — Attributes.java:72-80, LivingEntity.java:2300 — not measured) can hop onto a closed story cell and walk across "closed"
water on top of the collision cubes; a player with blocks, an elytra or an ender pearl clears every wall.

*Which outer edges are story-sensitive (public Gen 1 knowledge, not read from the export).* Gen 1's progression is a
path, so nearly every outer edge lets a player in ahead of something:

| Outer area (public knowledge) | What entering there from outside would skip |
|---|---|
| The southern sea: Routes 19, 20, 21, Seafoam Islands, Cinnabar Island, Pallet Town's shore | Surf (HM03 from the Safari Zone plus the Soul Badge): Cinnabar's gym, mansion and lab, and Articuno, before Fuchsia |
| Route 22 → Route 23 → Victory Road → Indigo Plateau (the west column) | The eight badge guards (step triggers) and Victory Road |
| Cerulean Cape (Routes 24, 25) and Cerulean's north-east | Nugget Bridge and its Rocket, the Cut tree east of Cerulean; the water there leads to Cerulean Cave (Mewtwo) |
| Routes 9, 10, Rock Tunnel, the Power Plant's water | Cut (Cascade Badge), Flash, and Surf for Zapdos |
| Lavender Town and Routes 12–15 (the east coast) | Rock Tunnel and both Snorlax |
| Fuchsia City, Routes 16–18 | Snorlax or the Cycling Road gate (Bicycle) |
| Pewter's east side, Route 3, Mt. Moon, Route 4 | The Boulder Badge escort out of Pewter |
| Vermilion's harbour | The S.S. Anne ticket |

Only the start area (Pallet Town, Route 1, Viridian City's south, Route 22's east end) has nothing behind it.

*How footprint and gate data prevent sequence breaks.*

1. **No free edge.** The outer boundary is CORE up to its last cell. On foot it is Kanto's own solid edge; for
   everything else (jumping onto story cells, pillars, elytra, pearls, boats, rides) the server bounce removes any
   non-admitted player from CORE within a second (A4.2).
2. **Gates are data, validated by the builder.** A gate's inside cells must be walkable (land gate) or water (sea gate)
   in the export's cell flags (bit 0 / bit 1, `docs/PROTOCOL.md:647-651`), must not carry a warp, sign, object or story
   cell other than water, and must lie on a side without a connection. The builder reports `gates` and refuses a bad one.
3. **Entry is the hidden game's decision** (A4.3): home gate or unlocked gate only.
4. **Sea edges before Surf.** A sea gate's cells are water, so the hidden player can only *be* there while surfing: a
   player can leave by sea only with Surf, hence unlock it only with Surf. Before that, the water story cells stay closed
   for them, the bounce handles anyone who gets over, and a boat or a Cobblemon ride is governed by its rider's state
   (`StoryStates.governingPlayer`).
5. **Jump is off before the first story cell.** The apron is at least 2 cells deep and already has Kanto's movement
   profile (A4.5), so nobody arrives at a story cell able to hop it.

**Options.** (a) Carry story collision on the outer border (a full-height per-player collider through a movement mixin):
smooth, but a mixin on `Entity#collide`, and still needs the bounce for teleports. (b) Entry policy + bounce (above):
no mixin. (c) Block vanilla boats and riding in the apron: simple, but stops sailing for players who do have Surf.

**Recommendation.** (b), with gates as the only openings; add (a) later only if play-testing shows the bounce is seen
too often.

**Open.** The edge inventory itself is track 1's (A3.2); the report fields above are what both tracks need.

### A4.5 Crossing physically

**Answer (facts).** († = read by a sub-agent of mine and not re-read by me; everything else I read myself.)

*What our code does today.*

- `server/Rules.applyPlayer` runs on `ServerPlayerEvents.JOIN`, `AFTER_RESPAWN` and on `BuildHold` release, for every
  player on every server: `player.setGameMode(GameType.ADVENTURE)` (unless held for a build), then `applyMovement`
  (`fabric-bridge/src/main/java/dev/kantobridge/server/Rules.java:64-67`, `:85-90`).
- `Rules.applyMovement` (`Rules.java:96-110`): `abilities.setWalkingSpeed(speed)`; `MOVEMENT_SPEED` **base** = speed
  (0.1737 walking, 0.3474 on the bike, `:45-47`); `JUMP_STRENGTH` **base** = 0; `setSprinting(false)`;
  `onUpdateAbilities()`. It is called again when the bike flag changes (`.../server/StoryStateService.java:45-47`).
- `client/move/SpeedSync.sprintBlocked` = jump base ≤ 1e-5 **or** `MoveRuntime.following()`
  (`fabric-bridge/src/client/java/dev/kantobridge/client/move/SpeedSync.java:27-30`); `LocalPlayerMixin` ANDs its negation
  into `LocalPlayer#canStartSprinting` (`.../mixin/client/LocalPlayerMixin.java:15-20`). The jump key is not blocked on
  the client at all unless the input lock is on (`InputLock.java:137-153`); jumping is impossible only because the
  attribute is 0.

*The 1.21.1 APIs.*

| Topic | API | Evidence |
|---|---|---|
| Modifier | `record AttributeModifier(ResourceLocation id, double amount, AttributeModifier.Operation operation)`; operations `ADD_VALUE`, `ADD_MULTIPLIED_BASE`, `ADD_MULTIPLIED_TOTAL` | mc → net.minecraft.world.entity.ai.attributes.AttributeModifier (AttributeModifier.java:21, :63-65) |
| Value | `d = base + Σadd; e = d + Σ(d·multBase); e *= Π(1 + multTotal)`, then `sanitizeValue` (the attribute's range) | mc → …AttributeInstance#calculateValue (AttributeInstance.java:145-163) |
| Instance | `setBaseValue(double)` :51; `addTransientModifier(AttributeModifier)` :94 and `addPermanentModifier(AttributeModifier)` :104 (both throw on a duplicate id†); `addOrUpdateTransientModifier` :86; `addOrReplacePermanentModifier` :98; `removeModifier(ResourceLocation): boolean` :118; `hasModifier(ResourceLocation)` :72; `getModifier(ResourceLocation)` :68 | mc → …AttributeInstance (AttributeInstance.java) |
| Transient vs permanent | Only the base and the permanent modifiers are saved (`AttributeInstance#save`, :181-197). A respawn copies **base values only**: `this.getAttributes().assignBaseValues(that.getAttributes())` | mc → net.minecraft.server.level.ServerPlayer#restoreFrom (ServerPlayer.java:1347); AttributeMap#assignBaseValues (AttributeMap.java:111) |
| Speed base on load | `this.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(this.abilities.getWalkingSpeed())` after the attributes were read | mc → net.minecraft.world.entity.player.Player#readAdditionalSaveData (Player.java:804) |
| Attributes | `MOVEMENT_SPEED` (player default 0.1), `JUMP_STRENGTH` default 0.42 (range 0–32), `STEP_HEIGHT` default 0.6, `SCALE` default 1.0; all client-syncable | mc → …Attributes (Attributes.java:111-113, :78-80, :132-134, :120-122); Player#createAttributes (Player.java:228); Abilities `walkingSpeed = 0.1F` (Abilities.java:12) |
| Sync | Dirty syncable attributes are sent with `broadcastAndSend` — to trackers **and to the player itself** — each tick; the client replaces base and modifiers from the packet | mc → net.minecraft.server.level.ServerEntity#sendDirtyEntityData (ServerEntity.java:311-327); ClientPacketListener#handleUpdateAttributes (ClientPacketListener.java:2032-2056)† |
| Jump | `getJumpPower = JUMP_STRENGTH × multiplier × blockJumpFactor + getJumpBoostPower()`; no jump when ≤ 1e-5. **A Jump Boost effect still jumps with the attribute at 0** (0.1 per level) | mc → net.minecraft.world.entity.LivingEntity#getJumpPower, #getJumpBoostPower, #jumpFromGround (LivingEntity.java:2130-2150) |
| Step | `STEP_HEIGHT`, but **at least 1.0 for any living mount a player controls** | mc → …LivingEntity#maxUpStep (LivingEntity.java:3645-3648) |
| FOV | `f *= (MOVEMENT_SPEED / abilities.walkingSpeed + 1) / 2` — raising the attribute without the ability widens the view | mc → net.minecraft.client.player.AbstractClientPlayer#getFieldOfViewModifier (AbstractClientPlayer.java:68-76) |
| Sprint | A transient `ADD_MULTIPLIED_TOTAL 0.3` modifier with id `minecraft:sprinting`, added and removed by `LivingEntity#setSprinting(boolean)`; the server applies it on the client's START_SPRINTING packet† | mc → …LivingEntity (LivingEntity.java:142-145, :2073-2081); ServerGamePacketListenerImpl (:1398-1403)† |
| Game mode | `ServerPlayer#setGameMode(GameType): boolean` → `ServerPlayerGameMode#changeGameModeForPlayer(GameType)`: a `CHANGE_GAME_MODE` game event to the player, `onUpdateAbilities()`, and a tab-list `UPDATE_GAME_MODE` broadcast to **everyone** | ServerPlayer.java:1473-1480; ServerPlayerGameMode.java:52-69 |
| Abilities by mode | `mayBuild = !isBlockPlacingRestricted()`; restricted = ADVENTURE or SPECTATOR; every mode change rewrites `mayfly`, `instabuild`, `invulnerable`, `flying` | mc → net.minecraft.world.level.GameType#updatePlayerAbilities, #isBlockPlacingRestricted (GameType.java:63-88) |
| Break permission | `blockActionRestricted` returns **false at once unless the game mode is restricted**; there is no per-position input | mc → …Player#blockActionRestricted(Level, BlockPos, GameType) (Player.java:208-223) |
| Use permission | `mayUseItemAt(BlockPos, Direction, ItemStack)` is `abilities.mayBuild` or an adventure `can_place_on` test; `ItemStack#useOn` passes when `!mayBuild` and no such test | Player.java:1735-1743; ItemStack.java:357-361 |
| Server position rule | The only vanilla per-position permission is spawn protection plus the world border: `ServerLevel#mayInteract(Player, BlockPos)`, asked for the block being broken and for the **clicked** block of a use (not the placement position) | ServerLevel.java:791-793; ServerPlayerGameMode.java:145; ServerGamePacketListenerImpl.java:1077 |

**Options.**

| What | Option | Notes |
|---|---|---|
| Game mode | (a) Switch at the border: Adventure inside, Survival outside | A packet burst and a tab-list broadcast per crossing; abilities are rewritten (`mayfly`, `invulnerable`); fights `/gamemode`, `BuildHold`'s saved modes and creative builders; persists in player data across a crash. And it is **not sufficient**: the mode belongs to the player, so a Survival player standing outside still reaches 4.5 blocks into the footprint |
| | (b) **Leave the game mode alone; decide by position** (of the actor *and* of the target) | A5.1. The player stays in whatever mode the world gives; inside they simply cannot change blocks |
| Speed and jump | (a) Transient modifiers with our own ids | Not saved, so no stale state; but an exact absolute speed is impossible while other modifiers exist, the FOV formula needs `walkingSpeed` set anyway, a respawn drops them, and the client's sprint check reads the jump *base* |
| | (b) **Base values + `walkingSpeed`, set per zone and normalised** on join, respawn and zone change | Today's mechanism with an `else` branch. Base values persist, so the normalisation on join is what removes a stale value |
| Inventory | (a) Swap inventories at the border | Heavy, risky (item loss on a crash between the two writes) |
| | (b) **No swap**: one inventory; the hidden bag's mirror only touches its own marked stacks (A7.3) | Nothing to do at the border |

**Recommendation.** (b) in all three rows.

- **Where the profile changes:** at the footprint's edge (OUT ↔ APRON), not at the gate. The apron is at least two cells
  deep, so by the time a player reaches a story cell or the gate they already walk at Gen 1's pace and cannot jump.
- **Server:** `RegionRules.apply(player, zone)`, called from the zone tick (A4.2), `JOIN`, `AFTER_RESPAWN`, a bike change
  and a `BuildHold` release.
- **Client:** nothing predicts the attribute change; it arrives one or two ticks after the crossing. That is harmless:
  the server does not validate speed against the attribute, and the only visible effect is a slight change of pace in
  the apron. `SpeedSync.sprintBlocked` gets `|| RegionRuntime.zone() != OUT` so sprinting stops on the first tick.
- **Jump Boost and other movement effects** (jump, levitation, speed, slow falling) must not survive the border, or a
  potion defeats one-block story collision: remove them while the zone is not `OUT` (A5.3).
- **Step height** stays 0.6. Mounts are the exception (1.0 and more), which is why riding ends at the border (A4.8).

**What keeps the crossing from stuttering.**

1. No teleport and no game-mode packet at any point of a normal crossing.
2. The rules change in the apron, the following state at the gate: two different lines, a few blocks apart.
3. Leaving and the home-gate entry are optimistic: the player keeps walking while `region.leave`/`region.enter` makes its
   round trip (one hidden logic step, about 16 ms, plus one or two client ticks); only a refusal produces a short forced
   walk back (`ForcedMover`, the same mechanism as a refused step today).
4. Following resumes with `linkFrom(hidden cell)`, which sends at most one ordinary step and never teleports (A4.2).
5. The gate block is decided from the same attachment on both sides, changed locally before it is relayed, so the
   client's prediction and the server's re-simulation agree (the story-cell argument, `refs/_research/P4_mc_runtime.md` §3c).

**Sketch.**

```java
// server/RegionRules (replaces the unconditional body of Rules.applyPlayer / applyMovement)
static void apply(ServerPlayer p, byte zone) {
    boolean kanto = zone != Footprint.OUT && !p.isCreative() && !p.isSpectator();
    StoryState s = StoryStates.peek(p);
    float speed = kanto ? Rules.speedFor(s != null && s.bike()) : 0.1F;          // Abilities default (Abilities.java:12)
    p.getAbilities().setWalkingSpeed(speed);
    p.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(speed);
    p.getAttribute(Attributes.JUMP_STRENGTH).setBaseValue(kanto ? 0.0 : 0.42);    // attribute default (Attributes.java:78-80)
    if (kanto) { p.setSprinting(false); RegionEffects.strip(p); if (p.isPassenger()) p.stopRiding(); }
    p.onUpdateAbilities();
}
```

**Tests.** GameTest `RegionRulesGameTests`: a mock player moved OUT → APRON → CORE → OUT has, two ticks after each move,
the expected `MOVEMENT_SPEED` base, `JUMP_STRENGTH` base and `walkingSpeed` (extends `RulesGameTests.assertMovement`,
`fabric-bridge/src/gametest/java/dev/kantobridge/gametest/RulesGameTests.java:30-35`); a respawn outside ends with vanilla
values; a join with stale Kanto values outside is normalised; the game mode is never touched. JUnit: `RulesSpeedTest`
unchanged.

**Open.** Whether the legacy flat preset keeps "Adventure on join" (recommended: yes, untouched, selected by "the world
has no region records").

### A4.6 Joining, dying and respawning outside

**Answer (facts).**

- **Joining today always snaps.** `ClientPlayConnectionEvents.JOIN` → `MoveController.onJoin` → `MovementSync.onWorldJoin`
  sets `rejoinWanted` (`MoveRuntime.java:79`; `MoveController.java:201-207`; `MovementSync.java:179-185`); once linked,
  `rejoin` teleports the player onto the hidden cell (`MovementSync.java:203-206`, `:505-514`). A snapshot with
  `ingame:true` and every re-link do the same (`:165-176`, `:229-241`).
- **The server puts everyone in Adventure with Gen 1 movement on join and on respawn**, wherever they are
  (`Rules.java:64-65`, `:85-110`).
- **Death** is only possible through sources that bypass invulnerability (`Rules.java:117-119`); `keepInventory` and
  `doImmediateRespawn` are on (`KantoGameRules.java:36-37`); the respawn point is the world spawn the builder put in
  Pallet Town (`BuildTask.java:230`), radius 0 (`KantoGameRules.java:53`). There is no respawn hook in the movement
  glue; the respawned player is brought back to the hidden cell by the drift rule (A1.6).
- **A blackout** is the hidden game's: `battle.result{lose}` → the engine's blackout → `warp{kind:"blackout"}` → the
  usual warp path (`docs/PROTOCOL.md:435-437`). It only happens in a battle the hidden game asked for.
- **The hidden game at its title, or pairing in progress:** the lock reasons `NOT_IN_GAME` and the pairing hold apply to
  every non-creative player of a Kanto world (`InputLock.java:118-120`; `MoveRuntime.java:103-107`, `:124`). **A dropped
  link** pauses the game behind the overlay (`BridgeRuntime.java:177-193`; `LinkPause.java:103-119`).

**Options — join.** (a) Always snap (today). (b) Trust the saved Minecraft position: no snap when the player logged out
outside. (c) Ask the hidden game.

**Recommendation.** The server's persistent presence decides (A4.2): 

| Presence at join | Zone of the saved position | Result |
|---|---|---|
| `NEW` (never played here) | any | today's behaviour: `FOLLOWING`, teleport onto the hidden cell once the hidden game is in game. This is the one accepted teleport: the story starts in a bedroom |
| `INSIDE` | CORE | `FOLLOWING`, `rejoin` as today (no visible jump when the two saves agree) |
| `INSIDE` | OUT/APRON (the files disagree) | `OUTSIDE`; the gate rules apply |
| `OUTSIDE` | OUT/APRON | `OUTSIDE`: **no snap**, no lock, no overlay. `home` and `admitted` come from the attachment |
| `OUTSIDE` | CORE (the files disagree) | the server bounce moves the player to the home gate's apron |

The server sends its presence to the client with the region data on join (one more field in the `world.regions`
bundle's per-player tail, or a small S→C `region.state`; the type already exists in the other direction).

**Dying and respawning.** With the damage veto scoped to the footprint (A5.3), a player can die outside. Then:
`ServerPlayerEvents.AFTER_RESPAWN` (already registered, `Rules.java:65`) runs `RegionRules.apply` for the zone of the
respawn position; presence is `copyOnDeath`. Respawn point: the player's bed or anchor as in vanilla (they can only be
placed outside); **without one, the home gate's apron** rather than the world spawn, so nobody respawns in CORE without
being followed. Kanto blackouts stay inside: they come only from battles the hidden game started, which cannot start
under the away hold.

**The hidden game while the player is outside.**

| Hidden game is… | Today (anywhere in a Kanto world) | With the border machine, player `OUTSIDE` |
|---|---|---|
| at its title, or in the intro | player locked, HUD hint (`InputLock.Reason.NOT_IN_GAME`) | nothing; at a gate, `region.enter` is refused `not_ingame` and the HUD says so. In `ENTERING` the title and intro UIs may show (A5.8) |
| switching save slots (M9), bind unanswered | player held, story traffic held (`SessionGate`) | traffic still held (correct: the game isn't this world's yet); the player is not held; at a gate, entry waits for `SessionGate` `OPEN` |
| disconnected or crashed (M10) | pause overlay after 1.5 s, singleplayer paused | status line only; the gate stays closed. gen1-bridge releases the away hold when its client drops, and keeps the marker in the save |
| restarted and CONTINUEd from an older save | `save.mismatch` screen | the same message, shown when the player is next `ENTERING` (A7.2). If the hidden player is not at the home gate: unlocked-gate warp or the teleport-back offer (A4.3) |

**Sketch.**

```java
// server, RegionService
ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
    RegionPresence p = RegionPresence.of(newPlayer);
    Region r = regions.byId(p.region());
    if (!alive && r != null && p.state() == OUTSIDE && newPlayer.getRespawnPosition() == null) {
        Vec3 at = r.gate(p.home()).apronCentre();                    // outside CORE
        newPlayer.teleportTo(newPlayer.serverLevel(), at.x, at.y, at.z, yawInto(r, p.home()), 0);
    }
    RegionRules.apply(newPlayer, regions.classAt(newPlayer.blockPosition()));
});
```

**Tests.** GameTest `RegionJoinGameTests`: a mock player with presence `OUTSIDE` placed in OUT keeps survival-side
attributes after `Rules.applyPlayer`; one with `OUTSIDE` placed in CORE is moved out within 20 ticks; respawn without a
bed lands in the apron. JUnit `BorderMachineTest`: join in each row of the table.

**Open.** Where the world spawn is (track 1, A2.7). With the table above it may be anywhere: `NEW` players are taken to
their bedroom, everyone else respawns at a bed or their gate.

> **Integrator note.** See the note under [A2.7](#a27-world-creation-ux) for the world spawn (decision D4).

### A4.7 Multiplayer: some players inside, some outside

**Answer (facts → behaviour with the border machine).**

| Thing | Today | With players outside |
|---|---|---|
| Puppets | Client-only entities of this client's own hidden game (`PuppetRuntime.java:197-218`); drawn within 48 blocks and inside the hidden player's map area (`PuppetCulling.java:20-36`, deviation 101) | Unchanged for others (they never saw them). An outside player's own puppets stand frozen near their gate; draw them only when the local zone is not `OUT` |
| Speech bubbles | From the synced `kantobridge:activity` attachment, to all but the player (`ActivityService.java:41`; `SpeechBubbles.java:93-135`) | An outside player's activity is `none` (mode `busy` maps to none, `ActivityTracker.java:33-47`; no pause overlay outside). A Cobblemon battle outside still reports `battle` (`GameRelayRuntime.java:132-134`): keep, or report it only inside |
| Story cells | Collision by each mover's own `StoryState` (`StoryCellBlock.java:67-80`); a player without a state is blocked by water only (`StoryState.java:42-44`) | Outside players never reach story cells (bounce). Their `StoryState` stays as last relayed; it is not persisted (`StoryStates.java:14-21`) and is resent in full on join |
| Blockade fading | Position-based, local (`BlockadeFade.java:51-61`) | Unchanged |
| Surf sprite | Others' `Surfing` attachment within 64 blocks; drawn only on a map (`SurfSprite.java:104-106`, `:188-193`) | A player who left by a sea gate keeps `surf` in their state (the hidden player still surfs under the hold), but nothing is drawn off a map. Clear the `Surfing` attachment when presence becomes `OUTSIDE` |
| Relay authority | Every C→S message affects only its sender (GameTest-proven, `docs/PROTOCOL.md:756-757`) | `region.state` follows the same rule; the server additionally checks it against the sender's own position |
| `BuildHold` | Holds **every** player on the server as a spectator and teleports them all to the world spawn on release (`BuildHold.java:52-58`, `:72-96`); `SaveService.save` refuses while holding (`SaveService.java:126-130`) | Must become region-scoped: hold only players whose zone is not `OUT` (and those who enter the region's chunks during the build); release them to where they were, or to the gate apron |
| Dedicated server | Rules, relay handshake and index sync run on any server (`KantoBridge.java:47-95`); a client without the mod is kicked (`RelayHandshake.java:126-152`) | Unchanged. A decision hides here: an open world may want vanilla+Cobblemon players without Gen1Recomp. They still need the mod (blocks, entity type), so the kick is right; they simply never get past a gate |
| Battles | A proxy NPC nobody sees; other players may see the opponent's Pokémon (PLAN U9) | Unchanged inside. Outside, ordinary Cobblemon battles |
| Wild spawns | Shared (habitat pools inside) | Shared; natural Cobblemon spawns outside (A5.6) |

**Is anything broadcast that shouldn't be?**

1. `world.index`, `world.slots` (and tomorrow `world.regions`, `world.footprint`) go to **every** joining client with the
   mod (`WorldIndexSync.java:58`, `:184-189`). They are ROM-derived layout data (not art). U8 allows ROM-derived data
   through the host's server, and DESIGN §8 assumes every player has their own ROM. In an open world a player may never
   intend to enter Kanto. Option: send the index of a region only once the client reports a bridge link
   (`texture.status` or a first `session.bound`), and send only the footprint and gates to everyone. A decision for the
   user; not needed for the first milestone.
2. The `Surfing` and `Activity` attachments are synced to all trackers. They carry one boolean and one enum; fine.
3. `region_presence` should be synced to its **own** player only (the gate block's client prediction needs it); other
   players do not need it.

**Options.** For the one thing that needs a choice, `BuildHold`: (a) hold everybody as today (simple; an outside player
is thrown into spectator for the minute a rebuild takes); (b) **hold only players whose zone is not `OUT`**, and anyone
who enters the region's chunks while the build runs (the zone tick already knows); (c) refuse a rebuild while anyone is
inside (no hold at all, but a host cannot rebuild a busy server).

**Recommendation.** (b). Scope `BuildHold` and `SaveService`'s refusal to the region; clear `Surfing` on leaving; sync
presence to the owner only; leave the rest.

**Tests.** GameTest `MultiplayerGameTests` additions: two mock players, one `INSIDE` and one `OUTSIDE`; the gate block
is open for the first and closed for the second; `region.state` from one never changes the other; a build hold takes
only the inside player.

**Open.** The index-on-demand decision above.

### A4.8 Edge cases: ways in or out other than walking

**Answer (facts).** († = read by a sub-agent, not re-read by me.)

- Our per-player collision follows "the player who governs the entity": the entity itself, or its
  `getControllingPassenger()` (`fabric-bridge/src/main/java/dev/kantobridge/story/StoryStates.java:45-50`). A boat's
  controlling passenger is its first living passenger (mc → net.minecraft.world.entity.vehicle.Boat#getControllingPassenger,
  Boat.java:869-873†); a minecart has none (mc → net.minecraft.world.entity.Entity#getControllingPassenger returns null,
  Entity.java:3198-3200†), so **story cells are open for a minecart with a player in it** (`StoryCellBlock.java:72-74`).
- Vehicle moves are re-simulated on the server with the vehicle's own `move`, so block collision shapes apply there too
  (mc → net.minecraft.server.network.ServerGamePacketListenerImpl#handleMoveVehicle, :395-465†).
- A living mount that a player controls steps up at least one full block
  (mc → net.minecraft.world.entity.LivingEntity#maxUpStep, LivingEntity.java:3645-3648). Cobblemon overrides it with the
  riding behaviour's value (`refs/cobblemon/common/src/main/kotlin/com/cobblemon/mod/common/entity/pokemon/PokemonEntity.kt:2499-2504`):
  **1.2 blocks for land rides** (`.../api/riding/behaviour/types/land/HorseBehaviour.kt:534-540`), 0 for the boat-style
  liquid ride (`.../types/liquid/BoatBehaviour.kt:381`). Story cells are one block high (`WorldPlan.java:452-457`).
- Nothing in our code reacts to riding (no use of `RIDE_EVENT_*`, `startRiding` or `stopRiding` anywhere under
  `fabric-bridge/src`). **Inference:** in today's flat Kanto a player riding a land-rideable Pokémon can step over closed
  story cells and counters and outrun the hidden game. ⚠ Not confirmed in play.
- Cobblemon posts a cancelable event before a player mounts: `CobblemonEvents.RIDE_EVENT_PRE`, a
  `CancelableObservable<RidePokemonEvent.Pre>` (`refs/cobblemon/.../api/events/CobblemonEvents.kt:174`), posted in
  `PokemonEntity.tryRidingPokemon(player: ServerPlayer)` before `player.startRiding(this)`
  (`PokemonEntity.kt:1595-1607`). There is no dismount event†. Lapras has riding data (land and liquid/boat,
  `refs/cobblemon/common/src/main/resources/data/cobblemon/species/generation1/lapras.json:53-103`†).
- Fabric has an elytra veto, checked when gliding starts and on every gliding tick:
  `EntityElytraEvents.ALLOW`, `boolean allowElytraFlight(LivingEntity entity)`
  (fapi → EntityElytraEvents (fabric-entity-events-v1, EntityElytraEvents.java:33, :76)).
- Fabric has **no** teleport event and no spawn-cancel event†. Player teleports all end in
  mc → net.minecraft.server.network.ServerGamePacketListenerImpl#teleport(double, double, double, float, float, Set<RelativeMovement>)
  (ServerGamePacketListenerImpl.java:991).
- An ender pearl moves its owner with `changeDimension(new DimensionTransition(...))`
  (mc → net.minecraft.world.entity.projectile.ThrownEnderpearl#onHit, ThrownEnderpearl.java:44-92†); chorus fruit uses
  `LivingEntity#randomTeleport(double, double, double, boolean)` (LivingEntity.java:3300-3344†); `/tp` calls
  `entity.teleportTo(level, x, y, z, relativeList, yaw, pitch)` (TeleportCommand.java:265†).
- A Nether portal's overworld exit is searched within **128 blocks** of the scaled position, and a new one is built
  within 16 blocks when none is found (mc → net.minecraft.world.level.portal.PortalForcer#findClosestPortalPosition,
  PortalForcer.java:41-50; #createPortal(BlockPos, Direction.Axis), :52). `createPortal` writes into replaceable blocks,
  which include `minecraft:light` (PortalForcer.java:150-152†).
- Fabric's world-change event exists: `ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD`,
  `void afterChangeWorld(ServerPlayer player, ServerLevel origin, ServerLevel destination)`
  (fapi → ServerEntityWorldChangeEvents (fabric-entity-events-v1, :58, :89)†).

**The rule that covers everything:** the server bounce of A4.2 — a non-admitted, non-creative player found in `CORE` for
more than 20 ticks is moved back to their last good position — plus the block-change guard of A5.7. The table says what
each case needs on top of that.

| Way | Policy | Hook, and what happens |
|---|---|---|
| Boat | **Handle.** Kanto's water is solid half blocks, so a boat cannot float in. | Gate blocks and water story cells collide for the boat by its rider's state (above). In the zone tick: `if (zone != OUT && player.isPassenger()) player.stopRiding()`. `region.enter` is not sent while the player is a passenger (HUD: "leave your boat") |
| Cobblemon riding | **Block inside the footprint.** | `CobblemonEvents.RIDE_EVENT_PRE.subscribe(Priority.HIGHEST, e -> { if (zoneOf(e.getPlayer()) != OUT) e.cancel(); })` and the same `stopRiding()` in the zone tick for players who ride in. This also closes today's hole. "Surf on your own Lapras" (NEXT_STAGES §4.5) is a later feature: the liquid ride has step height 0, and it would be allowed only on Kanto water while the hidden player surfs |
| Minecart, horse, pig, strider, camel | **Block inside.** | The same `stopRiding()`. Rails cannot be laid inside (A5.7). Gate blocks are solid for every entity that no admitted player governs, so an empty or rolling cart stops at the gate |
| Elytra | **Block over the footprint.** | `EntityElytraEvents.ALLOW.register(e -> e instanceof ServerPlayer p ? zoneOf(p) == OUT : true)`. Gliding stops at the footprint's edge; the player lands in the apron (fall damage is vetoed inside, A5.3) or in `CORE`, where the bounce takes them out |
| Ender pearl | **Handle** (bounce). Optional polish: a mixin at the head of `ThrownEnderpearl#onHit` that discards a pearl landing in `CORE` for a non-admitted owner. | No Fabric event. From inside to outside: the feet leave the map without a gate, so the existing drift rule returns the player to the hidden cell (A1.6) |
| Chorus fruit | **Handle** (bounce / drift, as pearls). Optional: `UseItemCallback` returning `FAIL` for it while the zone is not `OUT`. | `UseItemCallback#interact(Player, Level, InteractionHand)` (fapi events-interaction-v0†) |
| Riptide, wind charge, TNT launch, pistons pushing a player, swimming or stepping up from the sea | **Handle** (bounce). | — |
| Nether portal lit inside | **Block.** | Fire and portal blocks are block changes in the footprint (A5.7) |
| Nether portal arriving inside | **Handle.** Kanto's footprint maps onto a Nether area one eighth its size; a portal lit there searches 128 blocks around the scaled point. | First milestone: `AFTER_PLAYER_CHANGE_WORLD` → if the arrival is in `CORE` or `APRON`, move the player to the nearest `OUT` position (follow the signed distance outwards). The portal frame it tried to build is refused by A5.7. Later: a mixin on `PortalForcer#createPortal` and `#findClosestPortalPosition` that moves the search origin out of the footprint, so a usable portal appears just outside |
| Creative flight | **Allow.** Creative players are never followed, bounced or blocked by gates (today: `MoveController.java:89-90`). | They may build nothing inside unless the protection game rule is off (A5.7) |
| Spectator | **Allow.** | `noPhysics`; never followed |
| `/tp` of a survival player into `CORE` | **Handle** (bounce). An operator who wants to inspect uses creative or spectator, as today (deviation 34). | — |
| `/fill`, `/setblock`, `/clone`, `/place` inside | **Block by default, with an operator switch.** | They all end in `Level#setBlock` (A5.7). A boolean game rule `kantobridgeProtectRegions` (default true), registered with fapi `GameRuleRegistry.register(String, GameRules.Category, GameRules.Type<T>)` and `GameRuleFactory.createBooleanRule(boolean)` (fabric-game-rule-api-v1, GameRuleRegistry.java:46†, GameRuleFactory.java:58†) |
| Mobs and wild Pokémon walking in | **Block.** | Gate blocks are solid for them; A5.5 removes vanilla mobs found inside |

**Options.** (a) A full-height per-player collider around the footprint (a mixin on `Entity#collide`, where vanilla
already adds the world border's shape: mc → net.minecraft.world.entity.Entity#collectColliders, Entity.java:977-986):
smooth for flyers and boats, no visible bounce, but a movement mixin shared with Cobblemon's own hook on `collide`.
(b) **Bounce + specific vetoes** (table above): no movement mixin; an occasional visible pull-back for exotic entries.

**Recommendation.** (b) now; (a) only if play-testing shows the bounce is seen in ordinary play.

**Sketch.**

```java
// server/RegionService, END_SERVER_TICK
for (ServerPlayer p : server.getPlayerList().getPlayers()) {
    byte zone = regions.classAt(p.getBlockX(), p.getBlockZ());
    Presence pr = RegionPresence.of(p);
    if (zone != pr.lastZone()) RegionRules.apply(p, zone);
    boolean free = p.isCreative() || p.isSpectator() || BuildHold.isHeld(p);
    if (zone == Footprint.CORE && !free && pr.state() != INSIDE && pr.admitted() == null) {
        if (pr.strayTicks() > 20) {                                    // grace for the optimistic entry
            Vec3 to = pr.lastGood();
            p.teleportTo(p.serverLevel(), to.x, to.y, to.z, p.getYRot(), p.getXRot());   // also dismounts
            RelayService.get().sendError(p, "region.not_admitted", null);
        }
    } else if (zone != Footprint.CORE) {
        pr.setLastGood(p.position());
    }
}
```

**Tests.** GameTest `RegionEdgeGameTests`: a mock player in a boat is dismounted on entering the apron;
`RIDE_EVENT_PRE` is cancelled inside and not outside; `EntityElytraEvents.ALLOW.invoker()` is false inside; a mock
player teleported into `CORE` without presence is back outside within 25 ticks, a creative one is not; a zombie pushed
against a gate block does not pass.

**Open.** Whether operators should be exempt from the bounce by permission level instead of by game mode.

> **Integrator note.** For the plan, the bounce applies to a player whose presence is `OUTSIDE` (and who is not admitted at a gate). A `NEW` player standing at the world spawn in Pallet Town is today's first-join case and is never bounced; that is why the sketch's test `pr.state() != INSIDE` becomes `pr.state() == OUTSIDE`.

---

**A5. Scoping every Kanto rule to the footprint**

The inventory of what is world-scoped today is the [A1.5](#a15-every-behaviour-that-is-world-scoped-or-server-scoped-today) table. A5.1–A5.9 give, per topic, the
facts, the options, the recommended mechanism, the border behaviour and the tests.
(† = read by a sub-agent of mine and not re-read by me.)

**One switch for old worlds.** Every replacement below applies to worlds that have region records (A8.1). A world
without them (today's flat `kantobridge:kanto` worlds and the GameTest server) keeps today's code path unchanged:
`Rules.applyServer` and `Rules.applyPlayer` run as they do now when `Regions.of(server).legacy()`.

### A5.1 Building

**Answer (facts).**

- Today: Adventure for everyone on join and respawn, Adventure as the server default, Adventure in the created level
  (`Rules.java:76`, `:85-90`; `CreateKantoWorldFlow.java:420`). Our own blocks are unbreakable and unpushable
  (`strength(-1.0F, 3_600_000.0F)`, `pushReaction(PushReaction.BLOCK)`, `KbBlocks.java:86-93`, `:96-107`).
- Vanilla's checks are per player, not per position: `Player#blockActionRestricted` returns false unless the mode is
  Adventure or Spectator (Player.java:208-223); placing is gated by `abilities.mayBuild` in `ItemStack#useOn`
  (ItemStack.java:357-361) and `Player#mayUseItemAt` (Player.java:1735-1743). In Survival both are open.
- What a Survival player can and cannot do to the footprint's contents: blocks with destroy time −1 cannot be mined
  (progress 0, BlockBehaviour.java:380-384), which covers our blocks and `minecraft:light`
  (`.replaceable().strength(-1.0F, 3600000.8F)`, Blocks.java:3187-3197). But **air and `light` can be built over**: a
  block item replaces a replaceable block (BlockBehaviour.java:281-283), a lava bucket destroys a light block and a
  water bucket waterlogs it (BucketItem.java:129-137). A real build has 12,114 light blocks
  (`build_report.json` `light_blocks`).
- A placement lands on `BlockPlaceContext#getClickedPos`: the clicked block if it is replaceable, otherwise the block
  next to the clicked face (BlockPlaceContext.java:26-28, :47-49). The server's only position check, `mayInteract`, looks
  at the **clicked** block (ServerGamePacketListenerImpl.java:1077), so a player outside can place *into* the footprint
  by clicking a block outside it.
- Fabric events, all present in events-interaction-v0 0.7.14:
  `PlayerBlockBreakEvents.BEFORE` — `boolean beforeBlockBreak(Level world, Player player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity)`
  (PlayerBlockBreakEvents.java:42, :97), server side only, fired inside `ServerPlayerGameMode#destroyBlock` just before
  the block's `playerWillDestroy`; false cancels the break
  (fapi events-interaction-v0, ServerPlayerInteractionManagerMixin.java:105-112).
  `AttackBlockCallback` — `InteractionResult interact(Player player, Level world, InteractionHand hand, BlockPos pos, Direction direction)`
  (AttackBlockCallback.java:61), both sides: on the client a result other than `PASS` stops the vanilla handling, and
  only `SUCCESS` still sends the packet (client ClientPlayerInteractionManagerMixin.java:67-90); on the server it fires
  at the head of the block-break handler, resends the block and cancels (ServerPlayerInteractionManagerMixin.java:58-79).
  `UseBlockCallback` — `InteractionResult interact(Player player, Level world, InteractionHand hand, BlockHitResult hitResult)`
  (UseBlockCallback.java:51), both sides in the same way (client mixin :99-114; server mixin :83-89, at the head of
  `ServerPlayerGameMode#useItemOn`). So `FAIL` on the client sends nothing, and a modified client is refused again on
  the server.
  After every use-on-block the server resends the clicked block and its neighbour on that face
  (ServerGamePacketListenerImpl.java:1095-1096), so a refused placement corrects itself on the client.

**Options.**

| Option | How | Verdict |
|---|---|---|
| (a) Game-mode switching | Adventure inside, Survival outside | Not sufficient at the border (reach), and costly (A4.5) |
| (b) **Per-position permission through Fabric events** | Deny when the actor **or** the target (and the placement position) is in the footprint | Chosen; no mixin; works on client and server |
| (c) Mixins on `Player#blockActionRestricted` / `mayUseItemAt` | Emulate Adventure per position inside vanilla's own checks | Closest to vanilla, but two more mixin targets for what (b) already does |
| (d) The block-change guard alone (A5.7) | Server refuses the write | The authoritative backstop; alone it leaves break animations and a one-tick ghost block on the client |

**Recommendation.** (b) for a smooth client plus (d) as the guarantee. The rule, identical on both sides:

```java
static boolean denied(Player p, BlockPos target) {                       // common code
    if (p.isCreative() || p.isSpectator()) return false;                 // builders and inspectors
    Regions r = Regions.of(p.level());
    return r.classAt(target.getX(), target.getZ()) != OUT || r.classAt(p.getBlockX(), p.getBlockZ()) != OUT;
}
AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) ->
        denied(player, pos) ? InteractionResult.FAIL : InteractionResult.PASS);
PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> !denied(player, pos));
UseBlockCallback.EVENT.register((player, world, hand, hit) ->
        changesBlocks(player.getItemInHand(hand))
                && (denied(player, hit.getBlockPos()) || denied(player, hit.getBlockPos().relative(hit.getDirection())))
            ? InteractionResult.FAIL : InteractionResult.PASS);
```

`changesBlocks` is true for block items, buckets, flint and steel, fire charges, bone meal, hoes, shovels, axes and
spawn eggs; anything missed is still refused by A5.7. On the client the existing `ActRuntime.useBlock` runs first for
followed players and already fails plain clicks (`ActRuntime.java:176-195`); item use (Poké Balls, medicine) keeps
passing through.

**At the border.** The rule is about where the block is, so a Survival player outside cannot dig, place or pour into
the footprint, and a followed player inside cannot reach out and change the vanilla land either.

**Tests.** GameTest `RegionBuildGameTests`: with a test footprint, a Survival mock player outside and a target inside,
`AttackBlockCallback.EVENT.invoker()` returns `FAIL`, `PlayerBlockBreakEvents.BEFORE.invoker()` returns false, and
`UseBlockCallback` with a stone block in hand returns `FAIL` both for a clicked block inside and for a clicked block
outside whose face points in; the same calls pass for a target and actor both outside, and for a creative player.

**Open.** A user decision: the game mode outside (Survival is assumed above; nothing here depends on it).

### A5.2 Movement

**Answer (facts).** See A4.5 for the APIs. Today the speed and jump base values are set for every player on join and
respawn (`Rules.java:96-110`); sprinting is refused on the client when the jump base is 0 or the player is followed
(`SpeedSync.java:27-30`); step height is untouched (0.6).

**Options.** Base values per zone, or transient modifiers per zone (A4.5).

**Recommendation.** Base values and `walkingSpeed` per zone, through one function (`RegionRules.apply`, A4.5), called
on join, respawn, bike change and zone change. Sprint: unchanged mixin, with the predicate widened by the local zone.
Step height: unchanged. Mounts: dismounted (A4.8).

**At the border.** The profile switches at the footprint's edge; the apron gives a few blocks for the attribute packet
to arrive. Walking speed inside (0.1737) is higher than vanilla's 0.1, so entering feels like a slight speed-up and
leaving like a slow-down; neither rubber-bands.

**Tests.** `RegionRulesGameTests` (A4.5). JUnit: the `SpeedSync` predicate with a fake zone.

**Open.** None.

### A5.3 Damage, hunger and status effects

**Answer (facts).**

- Today every player is immune to every damage source except those tagged `bypasses_invulnerability`
  (`Rules.java:66`, `:117-119`; the tag holds `out_of_world` and `generic_kill`†). The event:
  `ServerLivingEntityEvents.ALLOW_DAMAGE`, `boolean allowDamage(LivingEntity entity, DamageSource source, float amount)`
  (fapi ServerLivingEntityEvents.java:34, :119†; used by our own test, `RulesGameTests.java:89-93`). It is injected in
  `LivingEntity#hurt` before shields and armour (fapi LivingEntityMixin.java:78-83 ↔ LivingEntity.java:1136†).
- The four damage game rules are also off world-wide (`KantoGameRules.java:32-35`); vanilla reads them in
  `Player#isInvulnerableTo` (Player.java:867-879†). Peaceful is forced and locked (`Rules.java:74-75`).
- Hunger: `FoodData#tick(Player)` drains food from exhaustion except on Peaceful (FoodData.java:39-79†); Peaceful also
  refills it (Player.java:524-536†). `Player#causeFoodExhaustion` does nothing when `abilities.invulnerable`
  (Player.java:1691-1697†), and `Player#hurt` returns false at once for an invulnerable player unless the source bypasses
  it (Player.java:882-888).
- Status effects: no Fabric event can veto one†; `LivingEntity#addEffect` asks `canBeAffected(MobEffectInstance)`
  (LivingEntity.java:961-992†). `FlashService` already manages one effect by position (Night Vision on dark maps,
  `FlashService.java:52-66`).

**Options.**

| Option | How | Notes |
|---|---|---|
| (a) **Scope the existing veto by position** | `allowDamage` returns false only when the player's zone is not `OUT` | One line. Covers fall, drowning, fire, freezing, starvation, mobs, explosions, poison and wither, because all of them end in `hurt` |
| (b) `abilities.invulnerable = true` inside | Vanilla's own flag: no damage and no food exhaustion | One flag does both, but it is rewritten by every game-mode change and saved with the player; needs the same normalisation as the speed |
| (c) Per-position emulation of the four game rules | A mixin on `Player#isInvulnerableTo` | Redundant with (a) |

**Recommendation.** (a), plus two small position-based sweeps in the zone tick for players inside:

- **Hunger:** keep food and saturation full (`getFoodData().setFoodLevel(20)`, `setSaturation(5)`) once a second. With a
  non-Peaceful world a Kanto session would otherwise end with an empty food bar.
- **Effects and fire:** on entering and once a second, remove effects that defeat Kanto's collision or pace — Jump
  Boost, Levitation, Slow Falling, Speed, Slowness — and harmful ones, and clear fire ticks; never touch our own Night
  Vision (`FlashService.ours`, `FlashService.java:86-88`).

Is `ALLOW_DAMAGE` enough? For health, yes. It does not stop knock-back-free effects, hunger or burning visuals, hence
the sweeps. The vanilla damage game rules and Peaceful then go back to their defaults in region worlds.

**At the border.** The victim's position decides: a creeper outside cannot hurt a player in the apron; a player one
step outside takes normal damage. A fall that ends inside does no damage.

**Tests.** GameTest `RegionRulesGameTests.damageFollowsTheZone`: `ALLOW_DAMAGE.invoker().allowDamage(player, generic, 4)`
is false inside and true outside; `fellOutOfWorld` is true in both; after 30 ticks inside with Jump Boost applied the
effect is gone and food is 20.

**Open.** Whether beneficial effects (Regeneration, Night Vision from a potion) should also be stripped inside
(recommended: leave them).

### A5.4 Time and weather

**Answer (facts).**

- Today: `doDaylightCycle` and `doWeatherCycle` are off world-wide (`KantoGameRules.java:23-24`), and every server
  start sets clear weather and noon (`Rules.java:77-79`). Kanto's three biomes have `has_precipitation: false`
  (`.../worldgen/biome/kanto_outdoor.json:2`).
- Server: time advances in `ServerLevel#tickTime` only under `RULE_DAYLIGHT` (ServerLevel.java:435-444†); weather in
  `advanceWeatherCycle` only under `RULE_WEATHER_CYCLE` (:646-728†). Both are per dimension.
- To clients: the day time goes out to each dimension's players when `tickCount % 20 == 0`, just before that level
  ticks (mc → net.minecraft.server.MinecraftServer#tickChildren, MinecraftServer.java:1013-1016; `synchronizeTime`,
  :1057-1062) as `ClientboundSetTimePacket(long gameTime, long dayTime, boolean daylightCycleEnabled)`;
  a **negative** day time tells the client the cycle is frozen (ClientboundSetTimePacket.java:15-26;
  `ClientLevel#setDayTime`, ClientLevel.java:258-267). Rain and thunder levels are `ClientboundGameEventPacket`s
  (`START_RAINING`, `STOP_RAINING`, `RAIN_LEVEL_CHANGE`, `THUNDER_LEVEL_CHANGE`, ClientboundGameEventPacket.java:15-22;
  constructor `ClientboundGameEventPacket(Type, float)`, :36). They are plain per-connection sends.
- Packets written during `tickChildren` are held and flushed together at its end (`suspendFlushing` :1006,
  `resumeFlushing` :1051). Fabric's `ServerTickEvents.END_WORLD_TICK` — `void onEndTick(ServerLevel world)`
  (fapi fabric-lifecycle-events-v1, ServerTickEvents.java:60, :83) — fires at the tail of `ServerLevel#tick`, inside
  that window; `END_SERVER_TICK` fires after it.
- Client: everything the sky shows derives from `LevelTimeAccess#getTimeOfDay(float)`, a default method
  `dimensionType().timeOfDay(dayTime())` that `ClientLevel` does not override (LevelTimeAccess.java:12-14), and from
  `Level#getRainLevel(float)` (Level.java:896-898). The client advances its own day time each tick from the stored
  value (`ClientLevel#tickTime`, ClientLevel.java:244-249), so faking the stored value would feed back into it.
  Rain columns are drawn only where the biome has precipitation (LevelRenderer.java:298-320†).

**Options.**

| Option | How | Inside Kanto | Cost |
|---|---|---|---|
| (1) Vanilla everywhere | Stop forcing the two game rules | Day and night; no rain falls (biome), but the sky dims and thunder is heard; nights are dark on the surface (interiors are lit, dark caves are dark by design) | None |
| (2) Client presentation override | A client mixin adding a `getTimeOfDay(float)` override to `ClientLevel` and wrapping `getRainLevel`/`getThunderLevel`, blended by the camera's signed distance to the footprint | Always noon and clear, fading in over the apron | One client mixin class (two or three methods). ⚠ Not compiled. Shader packs read the same values, so they follow |
| (3) **Server-sent per-player time and weather** | In `END_WORLD_TICK` of the overworld, on the ticks where vanilla broadcasts the time (`tickCount % 20 == 0`), for each player whose zone is not `OUT`: send `new ClientboundSetTimePacket(gameTime, blend(realDayTime, 6000, w), false)` and, when it rains, `RAIN_LEVEL_CHANGE 0` / `THUNDER_LEVEL_CHANGE 0`; on leaving, send the real values once | Noon and clear for players inside, real time outside; `w` rises from 0 to 1 across the apron, so the sun moves as one walks in | No mixin, about 30 lines, server only. The packet with `daylightCycleEnabled=false` stops the client's own ticking. Ours is written after vanilla's in the same held flush, so the client applies both before it draws a frame (inference; play-test B2) |
| (4) Frozen noon world-wide | Today | Noon | Outside has no nights, no rain: not "real gameplay outside" |

Gen 1 has no clock, so the hidden game is indifferent to all four.

**Recommendation.** First milestone: (1), which costs nothing and shows the world honestly; then (3) as the default
"Kanto is always noon" presentation, as a world setting (`rules.time`, `rules.weather` in the region record) so the user
can keep vanilla day and night in Kanto if they like the look (`docs/NEXT_STAGES.md:211-217` lists day, night and
weather as a cosmetic option). (2) only if (3)'s once-a-second granularity looks wrong.

**At the border.** With (3), time is a function of each player's own position; two players on either side of the edge
see different skies, which is fine (it is presentation). Viewed from outside at night, Kanto is dark like everything
else.

**Sketch.**

```java
// server/RegionSky: ServerTickEvents.END_WORLD_TICK.register(level -> …) for the overworld,
// when level.getServer().getTickCount() % 20 == 0 (the tick vanilla sends its own), and at once on a zone change
float w = Mth.clamp(-footprint.distance(p.getBlockX(), p.getBlockZ()) / (float) BLEND_BLOCKS, 0f, 1f);   // 0 outside
if (w > 0) {
    long real = level.getDayTime() % 24000L, shown = lerpDay(real, 6000L, w);
    p.connection.send(new ClientboundSetTimePacket(level.getGameTime(), shown, false));
    // Rain and thunder: vanilla re-broadcasts the level on every tick it changes and at start/stop
    // (ServerLevel.java:708-727), so ours goes out every tick the level's value changed, or w did:
    float rain = (1 - w) * level.getRainLevel(1f), thunder = (1 - w) * level.getThunderLevel(1f);
    if (levelRainChanged || rain != lastRain(p)) p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.RAIN_LEVEL_CHANGE, rain));
    if (levelThunderChanged || thunder != lastThunder(p)) p.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.THUNDER_LEVEL_CHANGE, thunder));
} else if (wasInside(p)) {
    // …and the real rain and thunder levels once
    p.connection.send(new ClientboundSetTimePacket(level.getGameTime(), level.getDayTime(), level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT)));
}
```

**Tests.** GameTest `RegionSkyGameTests` with a packet tap on a mock player (the pattern of `RelayTap`): inside, a
`ClientboundSetTimePacket` with a negative day time near −6000 is sent within 21 ticks; outside, none of ours.
Play-test: walk in at midnight, watch the sun rise across the apron; rain stops inside.

**Open.** A user decision (the spec lists "time and weather"). ⚠ Not confirmed: that no client code path re-enables the
cycle between our packets (the packet handler is `ClientPacketListener#handleSetTime`, ClientPacketListener.java:962-965†;
a short play-test settles it).

> **Integrator note.** This is the mechanism the plan uses: M12 turns vanilla time and weather on everywhere; M13 adds the packets as the default presentation inside, as a region setting (decision D7).

### A5.5 Vanilla mobs

**Answer (facts).**

- Today: `doMobSpawning`, `doInsomnia`, `doPatrolSpawning`, `doTraderSpawning`, `doWardenSpawning` off and
  `disableRaids` on, world-wide (`KantoGameRules.java:25-30`); Peaceful forced and locked (`Rules.java:74-75`), which
  also removes monsters every tick (mc → net.minecraft.world.entity.Mob#checkDespawn, Mob.java:719-743†).
- Natural spawning takes its candidates from the biome at the position (structure overrides first):
  mc → net.minecraft.world.level.NaturalSpawner#mobsAt → ChunkGenerator#getMobsAt (NaturalSpawner.java:267-273†;
  ChunkGenerator.java:421-443†). Kanto's three biomes have empty spawner lists for every category
  (`.../worldgen/biome/kanto_outdoor.json:19-28`), so **natural spawning is already biome-scoped** wherever the builder
  wrote those biomes: Y 56–88 over the surface box and feet−4…feet+8 around each interior
  (`WorldPlan.biomeRegions`, `WorldPlan.java:300-316`). Columns of the footprint above or below those bands keep the
  generator's biome.
- Not governed by the biome's spawner list:

  | Source | Where | Existing position gate |
  |---|---|---|
  | Patrols | mc → …levelgen.PatrolSpawner#tick (PatrolSpawner.java:22-63†) | biome tag `minecraft:without_patrol_spawns` (PatrolSpawner.java:70) |
  | Wandering trader | mc → …npc.WanderingTraderSpawner#spawn (WanderingTraderSpawner.java:85-121†) | biome tag `minecraft:without_wandering_trader_spawns` (:102) |
  | Zombie sieges | mc → …ai.village.VillageSiege#tick (VillageSiege.java:30†) | villages only; biome tag `minecraft:without_zombie_sieges` (:74) |
  | Phantoms | mc → …levelgen.PhantomSpawner#tick: above each non-spectator player at or above sea level under open sky | only the `doInsomnia` game rule (PhantomSpawner.java:30, :47-51) |
  | Raids | mc → …raid.Raids#createOrExtendRaid (Raids.java:79-122†) | villages; `disableRaids` (:84†) |
  | Cats | mc → …npc.CatSpawner#tick (CatSpawner.java:24-60†) | villages and swamp huts |
  | Spawner blocks, trial spawners | BaseSpawner#serverTick (:78†), TrialSpawner#spawnMob (:181†) | the block's own position |
  | Lightning's skeleton horses, endermites from pearls | ServerLevel.java:478-487†; ThrownEnderpearl.java:63-67† | none |
  | World generation's animals | NaturalSpawner#spawnMobsForChunkGeneration (:305-371†), added through the chunk, not `addFreshEntity` | the biome's `creature` list (empty in Kanto) |

- Every runtime spawn above ends in mc → net.minecraft.server.level.ServerLevel#addFreshEntity(Entity)
  (ServerLevel.java:881-883) → private `addEntity(Entity): boolean` (:922-929). Chunk loads and world-generation
  entities do not.
- Fabric has no event that cancels a spawn†. `ServerEntityEvents.ENTITY_LOAD` — `void onLoad(Entity entity, ServerLevel world)`
  (fapi fabric-lifecycle-events-v1, ServerEntityEvents.java:36, :67) — fires for **every** entity that starts being
  tracked, spawned or loaded with its chunk, after it has been added: Fabric injects at the tail of
  mc → net.minecraft.server.level.ServerLevel$EntityCallbacks#onTrackingStart(Entity) (ServerLevel.java:1694;
  fapi ServerWorldServerEntityHandlerMixin.java:37-39).

**Options.**

| Option | How | Notes |
|---|---|---|
| (a) Data only | Empty biome spawners (have); add the three Kanto biomes to the three `without_*` biome tags | No code. Leaves phantoms, lightning traps, mobs walking or flying in, and columns outside the biome bands |
| (b) One mixin | `ServerLevel#addEntity(Entity)` at HEAD: return false for a non-allow-listed `Mob` whose position is in the footprint | Nothing ever exists inside, even for a tick. One more mixin target |
| (c) **Event sweep** | `ServerEntityEvents.ENTITY_LOAD`: queue a non-allow-listed `Mob` inside the footprint for removal at the end of the tick; plus, every 20 ticks, discard such mobs within 64 blocks of each player whose zone is not `OUT` | No mixin. A spawned mob exists for at most one tick. The periodic part catches walkers, climbers, flyers and teleporters |

Allow-list: entity types in the `cobblemon` and `kantobridge` namespaces (wild and owned Pokémon, NPC battle proxies),
players, and non-`Mob` entities (items, projectiles, boats).

**Recommendation.** (a) + (c). Then stop forcing the spawning game rules and Peaceful in region worlds.

**At the border.** Gate blocks are solid for every entity no admitted player governs (A4.2), and the ring is three
blocks high, so ordinary mobs do not walk in; spiders, phantoms and endermen are caught by the sweep. Structures and
their spawn overrides are kept out of the footprint by track 1 (A2.2). Dark interiors such as Rock Tunnel stay empty:
their biome (`kanto_cave`) has no spawners and the sweep covers the rest.

**Sketch.**

```java
ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
    if (entity instanceof Mob mob && !allowed(mob) && Regions.of(level).inside(mob.getBlockX(), mob.getBlockZ())) DeferredRemovals.discardLater(mob);   // removed at END_SERVER_TICK, not inside the entity manager
});
// data/minecraft/tags/worldgen/biome/without_patrol_spawns.json (and the two others):
// {"replace": false, "values": ["kantobridge:kanto_outdoor", "kantobridge:kanto_interior", "kantobridge:kanto_cave"]}
```

**Tests.** GameTest `RegionMobGameTests`: a zombie added inside the test footprint is gone on the next tick, one added
outside lives; a Cobblemon `PokemonEntity` inside lives; a zombie moved inside next to a mock player is gone within 21
ticks; the three tags contain the three biomes (a data test like `StaticDataTests`).

**Open.** The sketch defers the removal to `END_SERVER_TICK` through the existing `DeferredRemovals.discardLater(Entity)`
(`fabric-bridge/src/main/java/dev/kantobridge/entity/DeferredRemovals.java:26-33`), because the event fires inside the
entity manager's own add path, and a removal from inside a tracking loop has crashed this server before
(`docs/PROGRESS.md` deviation 70). ⚠ Not confirmed: whether an inline `discard()` would also be safe (B3); the deferred
form does not depend on the answer.

### A5.6 Cobblemon spawning

**Answer (facts).**

*How Cobblemon decides a spawn.*

1. One `PlayerSpawner` per `ServerPlayer`, ticked at the tail of `ServerPlayer.tick` when `config.enableSpawning`, the
   `doPokemonSpawning` game rule and the dimension blocklist allow it
   (`refs/cobblemon/common/src/main/java/com/cobblemon/mod/common/mixin/ServerPlayerMixin.java:63-68`†, `:137-144`).
2. Each attempt picks a zone at a random distance between `minimumSpawningZoneDistanceFromPlayer` and
   `maximumSpawningZoneDistanceFromPlayer` from the player (`.../api/spawning/spawner/PlayerSpawner.kt:44-76`†),
   resolves spawnable positions in it, rolls a bucket, and gathers candidates: the **world pool's** spawns that are
   satisfied at the position, plus whatever the position's influences inject (`.../spawner/Spawner.kt:69-79`†).
3. World-pool spawns live in `data/<ns>/spawn_pool_world/` (1,544 files in Cobblemon's jar†) and are selected by biome
   tags. A sub-agent's scan found no bundled spawn that is valid without a biome condition†; the earlier research note
   says the same (`refs/_research/P6_cobblemon_integration.md` §6.2). `#cobblemon:is_overworld` is
   `#minecraft:is_overworld` plus the optional `#c:is_overworld`
   (`refs/cobblemon/common/src/main/resources/data/cobblemon/tags/worldgen/biome/is_overworld.json:4-6`).
   **So in a biome that is in none of those tags, no world-pool Pokémon spawns: natural spawns are already biome-scoped,
   as long as no `kantobridge:` biome is ever added to a `#minecraft:` or `#c:` biome tag.**
4. A natural habitat block is such an influence: it injects its pool's spawns for positions within its
   `rangeOfInfluence` (a 3-D sphere), found through a point-of-interest search of at least 128 blocks around the zone
   (`.../api/spawning/influence/detector/HabitatBlockDetector.kt:28-53`†; `.../block/habitat/HabitatBlockEntity.kt:236-285`†).
   Habitat spawn details carry **no biome or position condition** and the id `habitat_<species id>`
   (`.../api/habitats/HabitatSpawn.kt:138`). With `replaceSpawns` off (ours, `HabitatService.java:189-200`) world and
   habitat spawns mix inside the sphere. Our range is 14 (`HabitatService.java:61` → `builder/plan/HabitatPlanner.java:31`).
5. Spawn rules (`data/<ns>/spawn_rules/`) are attached to each `PlayerSpawner` when it is created
   (`.../api/spawning/spawner/PlayerSpawnerFactory.kt:48-56`). A `filter` component can test the position
   (`v.spawnable_position.x/y/z`, `.biome.is_of/is_in`) and the detail (`v.spawn_detail.id`, `.bucket`, …)†; a `location`
   component vetoes a position for all spawns from `v.x`, `v.y`, `v.z`
   (`.../api/spawning/rules/component/LocationRuleCalculator.kt:51-81`).
6. Code can add an influence to every new player spawner:
   `PlayerSpawnerFactory.influenceBuilders: MutableList<(ServerPlayer) -> SpawningInfluence?>`
   (`PlayerSpawnerFactory.kt:41-46`). `SpawningInfluence` offers, among others,
   `affectSpawnable(detail, spawnablePosition): Boolean`, `affectAction(action)` and
   `isAllowedPosition(world, pos, calculator): Boolean` (`.../api/spawning/influence/SpawningInfluence.kt:27-58`).
7. Our two rules are biome-scoped already: `no_interior_spawns` (biome `kanto_interior` → deny) and
   `walking_level_only` (biomes `kanto_outdoor`/`kanto_cave` → allow only `math.mod(y - 63, 22) == 0`;
   `fabric-bridge/src/main/resources/data/kantobridge/spawn_rules/walking_level_only.json:7-8`). The second hard-codes
   surface Y 63 and layer step 22.

*Natural spawns outside, habitat pools inside.* Outside, vanilla biomes are in Cobblemon's tags, so the full world pool
applies: every species Cobblemon ships a spawn file for, not just the 151. Inside, only our 46 pools on 296 habitat
blocks (`build_report.json` `pools`, `habitat_blocks`). One leak in each direction is possible:
a habitat sphere that reaches past the footprint injects Kanto route Pokémon into vanilla land (its details have no
biome condition, and `walking_level_only` does not apply outside Kanto biomes); and world spawns would appear inside if
a Kanto biome were ever put into a vanilla or convention biome tag.

*Which `CobblemonSettings` are global today* — all of them (`CobblemonSettings.java:48-73`, applied at
`SERVER_STARTING`/`SERVER_STARTED`, `Rules.java:59`, `:71-73`):

| Setting (ours → Cobblemon default†) | Where Cobblemon reads it† | Can it be scoped? Recommended |
|---|---|---|
| `healPercent` 0 → 0.05; `faintAwakenHealthPercent` 0 → 0.2 | `PlayerPartyStore.onSecondPassed(player)` (`.../api/storage/party/PlayerPartyStore.kt:104-132`), once a second per player | Only with a mixin. **Keep global**: with passive healing outside, a step through the gate would replace Pokémon Centers |
| `passiveStatuses` 1e9 s | rolled in `Pokemon.applyStatus` (`Pokemon.kt:1032-1033`) | Mixin only. Keep global (Gen 1 statuses persist) |
| `experienceMultiplier` 1.0 → 2.0 | `StandardExperienceCalculator` (`.../api/pokemon/experience/ExperienceCalculator.kt:43`) | `EXPERIENCE_GAINED_EVENT_PRE` could scale it per position. Keep global (Kanto's level curve) |
| `awardExperienceOnBattleLoss` true; `allowExperienceFromPvP` false; `preventCompletePartyDeposit` true; `playerDamagePokemon` false | battle and storage code | Mixin only. Keep global |
| `shinyRate` 1e9 → 8192 | `PokemonProperties.roll` (`:652-658`) | **Scope it**: restore the default and clear shininess for spawns inside through the influence's `affectAction` (below). Gifts and trainer teams are built by our code and are never shiny |
| spawning-zone distance 8/32 → 16/64 | `PlayerSpawner.kt:53`, re-read on every attempt; the class is final | Global only. Keep 8/32 for now; a measurement can decide later (`player.spawner.ticksBetweenSpawns` is a per-player knob, `PlayerSpawner.kt:78-81`) |
| `savePokemonToWorld` false → true | `PokemonEntity.shouldBeSaved` (`:1356-1363`) | The cancelable `POKEMON_ENTITY_SAVE_TO_WORLD` event scopes it per entity. Keep global |
| `defaultFleeDistance` 12 → 32 | default argument of `BattleBuilder.pve` | Our own battles pass it explicitly. **Restore the default**: outside, a player is not held and can walk away |
| game rules `doPokemonLoot` false, `battleInvulnerability` true, `doShinyStarters` false, `doPokemonSpawning` true | `CobblemonGameRules.kt:17-28` and their read sites | World-wide by nature. Loot can be scoped with the cancelable `LOOT_DROPPED` event. Keep for now |
| starter prompt locked (`CobblemonSettings.java:76-83`) | `GeneralPlayerData.starterLocked` | Per player, world-wide. Keep: the starter is Oak's |
| nickname changes cancelled (`PartyService.java:144-146`) | `POKEMON_NICKNAMED` (cancelable, has the player) | Could be scoped to "player not `OUTSIDE`". Optional |
| evolutions above dex 151 vetoed (`PartyService.java:505-515`) | `EVOLUTION_TESTED` (pokemon only) | Tie it to the world's "species outside" setting (A7.1), not to a position |
| Run ends any wild battle as fled (`EncounterService.java:291-305`), battle themes silenced (`BattleMusic.java:57-74`) | our own code, keyed on "the server has a WorldIndex" | **Scope by the player's position** (`zone != OUT`) |

**Options.**

| Option | How | Notes |
|---|---|---|
| (a) Biomes only | Rely on tags; nothing else | Leaves the habitat leak at the border and no "151 only" switch |
| (b) JSON spawn rules | A `location` rule per region with literal `minX…maxZ`, plus a `filter` on `q.is_included(v.spawn_detail.id,'habitat_')` | Data only, but the footprint is not a rectangle, the rules are attached only when a spawner is created, and Cobblemon's own wild habitat structures also use `habitat_` ids |
| (c) **One influence in code** | `PlayerSpawnerFactory.influenceBuilders.add(player -> new RegionSpawnInfluence(...))` | Position-exact through the footprint mask; carries the whole policy in one class |

**Recommendation.** (c), keeping the two JSON rules as they are:

```java
final class RegionSpawnInfluence implements SpawningInfluence {
    public boolean affectSpawnable(SpawnDetail detail, SpawnablePosition pos) {
        BlockPos at = pos.getPosition();
        boolean habitat = detail.getId().startsWith("habitat_");
        int d = regions.distance(at.getX(), at.getZ());            // < 0 inside the footprint
        if (d < 0) return habitat;                                  // inside: habitat pools only
        if (habitat && d <= HabitatService.RANGE + 2) return false; // just outside: no Kanto leak
        return !gen1OnlyOutside || dexOf(detail) <= 151;            // the A7.1 setting; non-Pokémon details pass
    }
    public void affectAction(SpawnAction<?> action) {               // Gen 1 has no shinies
        if (action instanceof PokemonSpawnAction a && insideFootprint(a.getSpawnablePosition().getPosition())) {
            PokemonProperties p = a.getProps().copy(); p.setShiny(false); a.setProps(p);   // replace, don't mutate (its own doc comment)
        }
    }
}
```

(`PokemonSpawnAction` has `var props: PokemonProperties` with the note "Don't modify the contents of this",
`refs/cobblemon/common/src/main/kotlin/com/cobblemon/mod/common/api/spawning/detail/PokemonSpawnAction.kt:26-34`; the
position is `SpawnAction.spawnablePosition`, `.../detail/SpawnAction.kt:21-24`; the shiny roll only runs when
`props.shiny == null`, `.../api/pokemon/PokemonProperties.kt:350`, `:654-658`.)

`affectSpawnable` is consulted for injected habitat details too: `HabitatBlockEntity.injectSpawns` keeps only details
for which `isSatisfiedBy(position)` holds (`refs/cobblemon/common/src/main/kotlin/com/cobblemon/mod/common/block/habitat/HabitatBlockEntity.kt:278-285`);
`isSatisfiedBy` starts with `spawnablePosition.preFilter(this)` (`.../api/spawning/detail/SpawnDetail.kt:112-115`);
`preFilter` asks every influence on the position (`.../api/spawning/position/SpawnablePosition.kt:162-165`), and those
include the spawner's own (`influences = input.spawner.copyInfluences()`,
`.../api/spawning/position/calculators/FlooredSpawnablePositionCalculators.kt:60`). So the border rule holds for them.
Register the builder at mod init: the list is read only when a player's spawner is created
(`PlayerSpawnerFactory.kt:48-56`). Add a startup check that no world spawn's valid biomes include a `kantobridge:` biome (a
GameTest and a log line), since that single mistake would undo the biome scoping.

**"Should outside spawns be limited to the 151?"** As a world setting, default on for the first milestones (A7.1). It
is the last line of the sketch; turning it off needs no other change.

**Tests.** GameTest `RegionSpawnGameTests` (next to the existing synthetic-pool spawner test): the influence allows a
`habitat_` detail and refuses a world detail at a position inside; refuses a `habitat_` detail 5 blocks outside;
allows a world detail outside; with the setting on, refuses a world detail whose species has dex 152. A data test that
no `kantobridge:` biome appears in any `SpawnDetail.getValidBiomes()`.

**Open.** (1) `walking_level_only`'s literals (63, 22) must follow any vertical shift of Kanto (track 1, A2.6).
(2) Spawn density inside versus outside with one global zone distance is a play-test question; a measurement design:
count `ENTITY_SPAWN` events per minute per player, inside on a route and outside in plains, at 8/32 and at 16/64;
accept if a route shows 1–3 wild Pokémon within 32 blocks most of the time (the user judges).

### A5.7 Block-changing mechanics

**Answer (facts).**

- Today the world is protected by game rules, world-wide: `mobGriefing`, `doFireTick`, `doVinesSpread`,
  `projectilesCanBreakBlocks` off and `randomTickSpeed` 0 (`KantoGameRules.java:39-45`, `:54`), plus Adventure mode and
  unbreakable blocks. `randomTickSpeed` 0 stops every random tick and all precipitation placement in the dimension
  (mc → net.minecraft.server.level.ServerLevel#tickChunk(LevelChunk, int), ServerLevel.java:467, :502-504, :520-523).
- **The choke point.** Every runtime block change in a `ServerLevel` goes through
  mc → net.minecraft.world.level.Level#setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft): boolean
  (Level.java:221), which writes with `levelChunk.setBlockState(pos, state, (flags & 64) != 0)` (:232). `ServerLevel` does
  not override `setBlock`, `removeBlock` or `destroyBlock`; `setBlockAndUpdate`, `removeBlock` and `destroyBlock` all end
  there (Level.java:273-310†). Only world generation bypasses it: `WorldGenRegion#setBlock` writes the chunk directly
  (WorldGenRegion.java:276-282), and it cannot write into a chunk that is already full
  (`ImposterProtoChunk`, ChunkStatusTasks.java:183†).
- Our own blocks: immune to mining, explosions (resistance 3.6 M) and pistons (destroy speed −1 →
  `PistonBaseBlock#isPushable` false, PistonBaseBlock.java:275-276†). **Not immune to the wither**: its block breaking
  ignores hardness and only skips air and `#minecraft:wither_immune`
  (mc → net.minecraft.world.entity.boss.wither.WitherBoss#canDestroy, WitherBoss.java:340-342).
- What can actually change inside the footprint is therefore: **air** (anything can be put there), **`minecraft:light`**
  (replaceable: block items, lava buckets, falling blocks, cobwebs, portal frames can replace it; flowing fluids
  cannot enter it because it is a `SimpleWaterloggedBlock` that only accepts a water *source*†), Cobblemon's habitat
  blocks, and whatever vanilla blocks track 1 puts in the apron.

*Per mechanic: where it can be stopped for a position inside the footprint.* The "decisive call" is where the mechanic
writes; "best veto" is where to stop it without side effects. All of them are *also* stopped by the choke point.

| Mechanic | Class#method (File.java:line)† | Vanilla gate | Changes | Best veto besides the choke point | Fabric event |
|---|---|---|---|---|---|
| Player breaking | ServerPlayerGameMode#handleBlockBreakAction (:137), #destroyBlock(BlockPos) (:244 → `removeBlock` :262) | `mayInteract` :145, adventure :156/:257 | own | `AttackBlockCallback`, `PlayerBlockBreakEvents.BEFORE` (A5.1) | yes |
| Player placing | BlockItem#place(BlockPlaceContext) (BlockItem.java:54 → `setBlock` :152) | `mayBuild` (ItemStack.java:357-361) | neighbour (`getClickedPos`) | `UseBlockCallback` on the clicked block **and** the placement position (A5.1) | yes |
| Buckets | BucketItem#use (:40), #emptyContents (:103; `destroyBlock` :134, `setBlock` :137) | `mayInteract` + `mayUseItemAt` :54 | neighbour | `UseBlockCallback`/`UseItemCallback` | yes |
| Flint and steel, fire charge | FlintAndSteelItem#useOn (:27 → :37), FireChargeItem#useOn (:31 → :40) | air only | neighbour | `UseBlockCallback` | yes |
| Bone meal, hoe, shovel, axe | BoneMealItem#useOn (:36), HoeItem (:46), ShovelItem (:42), AxeItem (:56) | — | own + neighbours | `UseBlockCallback` | yes |
| Fire spread | FireBlock#tick (FireBlock.java:148; spread `setBlock` :212; `checkBurnOut` :239) | `doFireTick` :150 | neighbour, air only | choke point (fire needs a flammable neighbour; our blocks are not flammable) | no |
| Lava igniting | LavaFluid#randomTick (LavaFluid.java:73 → :88/:103) | `doFireTick` :74 | neighbour | choke point | no |
| Lightning fire | LightningBolt#spawnFire (LightningBolt.java:152 → :157/:165) | `doFireTick` :153 | own + neighbour | choke point | no |
| Portal from fire | BaseFireBlock#onPlace (BaseFireBlock.java:140) → PortalShape#createPortalBlocks (PortalShape.java:165) | — | neighbours | choke point | no |
| Fluids | FlowingFluid#tick (:424), #spread (:117), #spreadTo (:238; `setBlock` :246) | none | neighbour | **`FlowingFluid#canSpreadTo(BlockGetter, BlockPos fromPos, BlockState, Direction, BlockPos toPos, BlockState, FluidState, Fluid)`** (FlowingFluid.java:402): return false when `toPos` is inside | no |
| Lava + water | LiquidBlock#shouldSpreadLiquid (LiquidBlock.java:154) | — | own (the lava) | not needed (fluids never get inside) | no |
| Explosions | Level#explode (13-argument form, Level.java:619) → Explosion#explode() (Explosion.java:210; `toBlow.addAll` :259) → #finalizeExplosion(boolean) (:313; block `onExplosionHit` :346, fire :356-361) | `mobGriefing` for mob sources (Level.java:637); none for TNT or blocks | neighbours | **after `Explosion#explode()`: remove inside positions from `getToBlow()`** — this also fixes the client, which replays the list (ServerLevel.java:1116) | no |
| TNT, creeper, wither skull, end crystal, respawn anchor, wind charge | PrimedTnt#explode (:111), Creeper#explodeCreeper (:245), WitherSkull#onHit (:91), EndCrystal#hurt (:87), RespawnAnchorBlock#explode (:143; **explodes in the overworld**), WindCharge#explode (:52) | as explosions | neighbours | the `toBlow` filter | no |
| Pistons | PistonBaseBlock#triggerEvent (:176) → #moveBlocks (:294; writes :325, :337-340, :353, :360); PistonStructureResolver#resolve() (PistonStructureResolver.java:43) | none; a pushed line may end only in air | neighbours | **`PistonStructureResolver#resolve()`: return false if the head position, any destination or any destroyed block is inside** (a late veto of single writes would delete the pushed blocks) | no |
| Endermen | EnderMan.EndermanTakeBlockGoal#tick (:623 → :636), EndermanLeaveBlockGoal#tick (:477 → :491, air only) | `mobGriefing` | neighbour | choke point; A5.5 removes them anyway | no |
| Ravager, silverfish, rabbits, sheep, foxes, snow golems, villagers | Ravager#aiStep (:126 → :147), Silverfish goals (:164, :200), Rabbit.RaidGardenGoal (:548), EatBlockGoal#tick (:71), Fox.FoxEatBerriesGoal (:912), SnowGolem#aiStep (:88 → :107), HarvestFarmland#tick (:104) | `mobGriefing` | neighbour | choke point; they act only on vanilla blocks; A5.5 | no |
| Wither | WitherBoss#customServerAiStep (:253 → `destroyBlock` :322) | `mobGriefing` :312 | neighbour | **add our blocks to `#minecraft:wither_immune` and `#minecraft:dragon_immune`** (data), plus the choke point | no |
| Dispensers | DispenserBlock#dispenseFrom (DispenserBlock.java:93); behaviours in DispenseItemBehavior#bootStrap (:72: buckets :229, flint :275, bone meal :300, shulker boxes) | none | the block in front | choke point (the dispenser sits outside, its target inside) | no |
| Sculk | SculkSpreader#updateCursors (:143), SculkBlock#attemptUseCharge (:28 → :40), SculkVeinBlock#attemptPlaceSculk (:107 → :117) | none | neighbours | choke point | no |
| Trees and saplings | SaplingBlock#advanceTree (:53) → TreeGrower#growTree(ServerLevel, ChunkGenerator, BlockPos, BlockState, RandomSource) (TreeGrower.java:124) → TreeFeature#place (:115; `setBlock` through the `ServerLevel`) | light ≥ 9; positions must be air or `replaceable_by_trees` | neighbours | choke point: a tree at the border grows without the part that would reach in | no |
| Other growth | MushroomBlock (:49), BambooStalkBlock (:127), SugarCaneBlock (:51), CactusBlock (:50), GrowingPlantHeadBlock (:46), CropBlock (:86), StemBlock (:80), ChorusFlowerBlock (:63), BuddingAmethystBlock (:27) | random ticks | neighbours (air) | choke point | no |
| Bone meal effects | BoneMealItem#growCrop (:63), #growWaterPlant (:80); MossBlock/GrassBlock#performBonemeal | — | own + neighbours | `UseBlockCallback`; choke point for dispensers | partly |
| Snow and ice | ServerLevel#tickPrecipitation(BlockPos) (ServerLevel.java:541; ice :546, snow :558/:561) | raining; `Biome#shouldFreeze`/`shouldSnow` | top of a column | already biome-scoped: Kanto's biomes have no precipitation and temperature 0.8; choke point as backstop | no |
| Melting | IceBlock#randomTick (:51), SnowLayerBlock#randomTick (:114) | block light > 11 | own | only vanilla blocks; n/a inside | no |
| Leaf decay | LeavesBlock#randomTick (LeavesBlock.java:56; drops :58, `removeBlock` :59) | random ticks | own | only vanilla leaves (apron art); **veto in `Level#destroyBlock`/before the drop**, see below | no |
| Vines | VineBlock#randomTick (VineBlock.java:179; writes :196-248) | `doVinesSpread` :180 | neighbours (air) | choke point | no |
| Dripstone | PointedDripstoneBlock#randomTick (:174) → grow (:365) → `setBlock` :381 | air or water target | neighbours | choke point | no |
| Grass spread | SpreadingSnowyDirtBlock#randomTick (:43) | random ticks | neighbours (dirt only) | n/a inside (no dirt) | no |
| Falling blocks | FallingBlockEntity#tick (:129 → `setBlock` :165 if the target is replaceable, **which includes `light`**) | — | landing position | choke point (the entity then drops as an item when entity drops are on) | no |
| Frost Walker, cobwebs from the Weaving effect | ReplaceDisk#apply (:41 → :50); WeavingMobEffect#onMobRemoved (:26 → :47, replaceable targets) | — | neighbours | choke point | no |
| Farmland, turtle eggs | FarmBlock#fallOn (:101), TurtleEggBlock (:54/:63) | player or `mobGriefing` | own | only vanilla blocks | no |
| Projectiles | Projectile#mayBreak (Projectile.java:289) | `projectilesCanBreakBlocks` + `mayInteract` | own | only vanilla blocks (pots, chorus, dripstone) | no |
| Portal on arrival | PortalForcer#createPortal(BlockPos, Direction.Axis) (PortalForcer.java:52; frame :133, portal :143, fallback platform :118-124) | world border | neighbours; `light` counts as replaceable | choke point now; moving the search origin later (A4.8) | no |
| Commands and structure blocks | SetBlockCommand (:83), FillCommand (:134), CloneCommands (:213), PlaceCommand (:239-331), StructureTemplate#placeInWorld (:205) | permission level | own | choke point, lifted by the protection game rule | no |

**Options.**

| Option | How | Notes |
|---|---|---|
| (a) Keep the game rules world-wide | Today | Kills fire, growth, mob griefing and random ticks in the whole vanilla world. Not "real gameplay outside" |
| (b) Hook each mechanic | A veto per row | Dozens of targets; always one behind the next Minecraft update or mod |
| (c) **One choke point + a few clean vetoes + data** | A server-side mixin at the head of `Level#setBlock`; three small mixins where a late veto has side effects; two tag files; the Fabric events for players | Complete by construction for everything that writes through the level, including other mods |

**Recommendation.** (c).

1. **The guard** (the guarantee):

```java
@Mixin(Level.class)
abstract class LevelRegionGuardMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), cancellable = true)
    private void kantobridge$guard(BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        Level self = (Level) (Object) this;
        if (!self.isClientSide && RegionGuard.denies((ServerLevel) self, pos, state)) cir.setReturnValue(false);
    }
}
// RegionGuard.denies: regions.touchesChunk(pos) && regions.classAt(pos.x, pos.z) != OUT
//                     && !RegionGuard.writing()                        // the builder's own scope, server thread only
//                     && !sameBlockAllowListed(level.getBlockState(pos), state)   // cobblemon:habitat_block state flags
//                     && level.getGameRules().getBoolean(PROTECT_REGIONS)
```

   The builder (`BlockPlacer`), `HabitatService.place/remove` and `BiomeWriter` run inside
   `RegionGuard.write(() -> …)`. Cobblemon's habitat block rewrites its own state flags at run time
   (`HabitatBlockEntity.refreshFromSettings`, `HabitatBlockEntity.kt:112-117`†), hence the same-block allow-list.
2. **Clean vetoes** (so nothing drops, half-moves or desyncs): `FlowingFluid#canSpreadTo` (return false for a `toPos`
   inside), `PistonStructureResolver#resolve` (return false when any affected position is inside),
   `Explosion#explode` (filter `getToBlow()`), and a head check in
   mc → net.minecraft.world.level.Level#destroyBlock(BlockPos, boolean, @Nullable Entity, int) (Level.java:279), because it
   drops the block's loot (:290-292) **before** the write (:295).
3. **Data:** add every `kantobridge` block to `#minecraft:wither_immune` and `#minecraft:dragon_immune`.
4. **Players:** the three Fabric callbacks of A5.1.
5. Then the block-protecting game rules and `randomTickSpeed` go back to vanilla defaults in region worlds.

**At the border.** Every rule is keyed on the **target** position, so it does not matter that the actor (a Survival
player, a piston, a dispenser, a burning forest, a river, a creeper) is outside. Fluids stop at the footprint's first
column; an explosion outside damages vanilla land up to the edge and nothing beyond; a sapling planted next to the
edge grows a tree with the inner part missing. What track 1 must add: the terrain itself should not send water downhill
into the footprint (A2.6, A3.3), or the edge will show standing walls of water held back by the guard.

**Tests.** GameTest `RegionGuardGameTests` (server, with a small test footprint):
`level.setBlock(inside, STONE, 3)` is false and the block is unchanged; the same inside `RegionGuard.write` is true;
`level.destroyBlock(inside, true)` is false and no item entity appears; a water source placed outside next to inside
air leaves the inside column dry after 40 ticks; `level.explode(...)` at the edge leaves an inside vanilla block and
drops nothing; a piston facing in with a block in front does not extend; `SaplingBlock#advanceTree` at the edge changes
nothing inside; the two immunity tags contain all tile families. JUnit: `RegionGuard.denies` truth table.
The mixin targets go into the existing `verifyMixinRemap` / `verifyMixinTargets` checks (`docs/PROGRESS.md` S3,
deviation 90).

**Version risk.** Five mixin targets, all on long-stable methods: `Level#setBlock` (4 arguments), `Level#destroyBlock`
(4 arguments), `FlowingFluid#canSpreadTo`, `PistonStructureResolver#resolve`, `Explosion#explode`. A Minecraft update
that changes a signature fails the build through `verifyMixinTargets` rather than silently unprotecting the region.

**Open.** (1) ⚠ Not confirmed: the cost of the head inject on `Level#setBlock` under heavy redstone outside (one hash
probe per call; expected negligible — a measurement design: count calls and total nanoseconds over 60 s next to a
0-tick piston clock with and without the mixin; accept below 1% of tick time). (2) Whether the apron uses vanilla
blocks at all (track 1): if it does, leaf decay and grass spread inside the apron are the reason for the
`destroyBlock` veto.

### A5.8 Client checks: from "Kanto world" to "inside the footprint"

**Answer (facts).** On the client "Kanto world" means "a WorldIndex has arrived" (`ClientWorldIndex.current() != null`),
and almost everything hangs on one derived flag, `MoveController.following` = index present ∧ not creative/spectator
(`fabric-bridge/src/client/java/dev/kantobridge/client/move/MoveController.java:89-90`), exposed as
`MoveRuntime.following()` (`MoveRuntime.java:109-113`).

| Check | Where it is decided today | What must change |
|---|---|---|
| Following (steps, face, drift, join teleport) | `MoveController.java:89-93`; `MovementSync.java:193-224` | `following` = index ∧ not free camera ∧ **border state `FOLLOWING`** (A4.2). One line; everything below that reads `MoveRuntime.following()` follows |
| `InputLock` | `MoveController.java:103-104` → `InputLock.reason(kanto, …)` (`InputLock.java:107-134`) | Pass `following` instead of `kanto`. Outside: `Reason.NONE` always, so no lock for battles, title, pairing, unbuilt maps |
| Sprint block | `SpeedSync.sprintBlocked` = jump base ≤ 1e-5 ∨ following (`SpeedSync.java:27-30`), asked by `LocalPlayerMixin` | Becomes region-scoped by itself once the server restores the jump attribute outside (A5.2). Add `∨ local zone != OUT` so the apron is covered before the attribute packet arrives |
| Pause overlay | `BridgeRuntime.tickPause`: `kanto = level ∧ player ∧ following` (`BridgeRuntime.java:177-193`); `LinkPause.tick` (`LinkPause.java:103-119`) | Nothing more: it follows `following` |
| Status line (HUD) | Drawn in game whenever the HUD is (`BridgeRuntime.java:92`, `:195-198`; `ConnectionHud.java:73-74`); "not connected, retrying…" stays up while disconnected (deviation 13) | Outside, show it only within some distance of the footprint (signed distance ≤ 32 blocks) or in `ENTERING`; otherwise it nags a player who is just playing Minecraft |
| Extra HUD lines | move hint (`MoveRuntime.java:92`), pairing (`SessionRuntime.java:233`), textures (`TextureSyncRuntime.java:101`) | Same rule as the status line |
| F7 overlay | Toggle, global (`BridgeRuntime.java:167-169`) | Keep global; add one line: region id, zone, border state, home gate |
| Hidden-game screens (text, menus, mirror) | `UiRuntime.reconcile`: `allowed = … ∧ MoveRuntime.following() ∧ ¬battle ∧ ¬suspended` (`UiRuntime.java:188-190`) | `following ∨ ENTERING` (the title, the intro and a refusal text must be showable at a gate) |
| A / START keys | `ActRuntime.idle()` → `ActionGate.idle(MoveRuntime.following(), …)` (`ActRuntime.java:317-322`) | Follows `following` |
| Right-click on blocks | `ActRuntime.useBlock` returns `PASS` unless `MoveRuntime.following()`, else `FAIL` for every plain click (`ActRuntime.java:176-195`) | Follows `following`. **Today this makes every plain right-click in the whole world fail** (chests, doors, crafting tables); outside it must pass |
| Crosshair on closed art | `ActRuntime.pickClosedArt` needs `following` (`ActRuntime.java:278-285`) | Follows `following` |
| Puppets | Spawned whenever the index has their map (`PuppetRuntime.java:197-218`); culled beyond 48 blocks or outside the hidden player's map area (`PuppetCulling.java:20-36`) | Draw only while the local zone is not `OUT`. Clicks on them already need `idle()` |
| Speech bubbles | Any world, from the synced activity (`SpeechBubbles.java:93-135`) | No change |
| Blockade fading, surf sprite, closed-art renderer | Position-based (`BlockadeFade.java:51-61`; `SurfSprite.java:188-193`; `StoryCellRenderer.java:43`) | No change |
| Cycling Road brake | `controller.following() ∧ slope` (`MoveRuntime.java:136-141`) | Follows `following` |
| Warp fade | Only `if (this.following)` (`MoveController.java:169-183`) | Follows `following`; plus the `enter_warp` exception (A4.2) |
| Story view | Computes and relays `story.state` whenever the relay is open (`ClientStoryView.java:114-164`) | No change (the state must be right at re-entry) |
| Battle relay | `battle.start` is relayed whenever it arrives (`GameRelayRuntime.java:67`, `:99-105`) | Guard: a `battle.start` while not `FOLLOWING` is answered `aborted` (the same answer as "no server link", `docs/PROTOCOL.md:364`). Under the away hold none should arrive |
| Texture flow | Starts on joining any world with an index; a mismatch pauses the link and re-exports (`TextureSyncRuntime.java:121-142`, `:146-168`) | Keep at join (the pack must be ready before the player sees Kanto, which may be at once from a hill), per region later (track 3's B6.4). Its HUD line as above |
| Session bind | `kanto = level ∧ player ∧ index != null` → bind (`SessionRuntime.java:310-316`) | Keep at join, per region (A8.3) |
| M10 warning screens | Game-version check each tick in any Kanto world (`Versions.java:140-154`); `save.mismatch` on arrival (`:122-137`); queued in `Notices` with no position check (`Notices.java:53-64`) | Show when the border state is `ENTERING`, `FOLLOWING` or `LEAVING` (A7.2). The relay-protocol check stays global (`Versions.java:97-119`) |
| Activity (bubbles others see) | `ActivityTracker.of(battle, pauseShown, uiOpen, mode)` (`GameRelayRuntime.java:125-143`) | Report `battle` only when the battle is the hidden game's or the player is inside; else `none` (optional) |

**Options.** (a) Sprinkle `inside(pos)` checks over each class. (b) **Keep the single flag** and redefine it. 

**Recommendation.** (b). `MoveRuntime.following()` already is the single switch for 10 of the 19 rows; redefine it and
add one second query, `RegionRuntime.concerned()` (border state ≠ `OUTSIDE`, or within 32 blocks of the footprint), for
the HUD lines, the puppets and the notices.

**Sketch.**

```java
// MoveController.beforeMove (replaces :89-90)
boolean kanto = frame.index() != null;
this.following = kanto && !frame.freeCamera() && frame.border() == Border.FOLLOWING;
// MoveRuntime (new)
public static boolean concerned() { RegionRuntime r = RegionRuntime.get();
    return r != null && (r.border() != Border.OUTSIDE || r.distanceToFootprint() <= 32); }
```

**Tests.** JUnit: `InputLockTest` gains "outside: never locked, whatever the link says"; `MoveControllerTest` gains the
four border states; `BorderMachineTest` (A4.2). The rest is play-test only (no client GameTests on 1.21.1): HUD silence
outside, clicks on a chest outside, the pause overlay not appearing outside when Gen1Recomp is closed.

**Open.** None.

### A5.9 Inventory and respawn

**Answer (facts).** `keepInventory` and `doImmediateRespawn` are set world-wide at every server start
(`fabric-bridge/src/main/java/dev/kantobridge/server/KantoGameRules.java:36-37`; `Rules.java:63`, `:71-72`), and the
world spawn radius is 0 (`KantoGameRules.java:53`). Inside Kanto a player cannot die except by sources that bypass
invulnerability (`Rules.java:117-119`), which in a vanilla world with bedrock below leaves `/kill`. The battle-item
mirror keeps its copies in the player's inventory (`.../bag/BagService.java:118-151`); losing them on death would be
reported to the hidden game as consumption (`:155-200`).

**Options.**

| Option | Inside | Outside | Notes |
|---|---|---|---|
| (a) Keep both rules world-wide | n/a (no deaths) | no item loss, no death screen | Zero code. Outside is gentler than vanilla |
| (b) Vanilla rules world-wide | n/a | items drop, death screen | The mirrored bag items must not drop: exclude marked stacks (A7.3) from `Inventory.dropAll`, or they vanish from the hidden bag |
| (c) Per-region emulation | keep | vanilla | A mixin on the death-drop path keyed by position; more code for the same result as (b), since nobody dies inside |

**Recommendation.** (a) for the first milestones — it is the only option with no new failure mode for the bag mirror —
and offer (b) as a world option once marked stacks exist. Respawn position: bed/anchor, else the home gate's apron
(A4.6). `spawnRadius` back to the vanilla default once the world spawn is outside Kanto.

**Tests.** GameTest: with option (a), a mock player killed outside keeps the inventory and respawns in the apron when no
bed is set (`RegionJoinGameTests`, A4.6).

**Open.** A user decision ("game mode outside" in the spec's §8 list covers it): how harsh is the world outside Kanto?

---

**A6. Building Kanto into a vanilla world**

### A6.1 When the build runs

**Answer (facts).** Both triggers start the build on `SERVER_STARTED`, after the world and its spawn
chunks exist (sequence diagrams, section 2.3). The placer loads each chunk with a blocking `level.getChunk` inside its 25 ms
slice (`BlockPlacer.java:207-216`; `BuildOptions.java:26`). A chunk is FULL only after its 8 neighbours
have run `features` (A2.10 E2), and a FULL chunk can no longer be written by generation (A2.1 F-1).

**Options.**
1. *Build inside generation* (the generator places Kanto's blocks). Rejected: the generator would need
   the whole export, the art catalogue and block entities, on world-gen threads.
2. **Generate first, then build on demand** (today's flow and the headless build, unchanged in
   order): the generator reserves; the placer fills.
3. Build lazily, map by map, as players approach (PLAN §8's fallback for slow builds). Not needed at
   57 s.

**Recommendation:** option 2, with two changes to the placer.
- **Ticket a window ahead.** Add region tickets for the next ~32 chunks and take a chunk only when
  `getChunkSource().getChunkNow(x, z)` returns it (mc → ServerChunkCache.java:168), instead of
  blocking on `getChunk` (`BlockPlacer.java:209-210`). Generation then runs on the worker threads
  while the server thread places.
- **A counted check instead of clearing.** With the generator of A2.1 the volumes are already empty:
  air above the fill, air inside interior boxes. So the placer's own rule ("write only what differs")
  places exactly today's blocks and `blocks_cleared` stays 0. Add one pass per chunk over core columns:
  anything that is neither planned, air, foundation nor a habitat block is counted as
  `foreign_blocks` and removed. **Gate: `blocks_cleared == 0` and `foreign_blocks == 0` on a fresh
  world.** A non-zero count means the generator let something through.

**Can the generator guarantee an empty footprint?** Yes for terrain, caves, fluids, ore veins,
carvers, structures and generation-time mobs (skipped or gated); features are the one stage that can
write from a neighbouring chunk, and the post-decoration repair removes that before the chunk is
FULL. What the placer must still do: nothing by design; the counted pass is the proof.

**Open:** none.

### A6.2 Lighting and chunk state

**Answer (facts).**
- Placement needs the chunk at FULL (a `LevelChunk`); ticket level 33 gives that without ticking
  (A1.4), so no random ticks or fluid ticks run in chunks while they are built.
- Each write updates the final heightmaps and queues light work when needed; the placer already
  throttles on the light engine (A1.4).
- The neighbours of a FULL chunk are light-initialised but may not be fully lit yet (FULL needs
  `initialize_light` at radius 1, A2.10); the light engine finishes them when they reach `light`, as it
  does for any player-built structure at the edge of the loaded area. Nothing special is needed.
- Flags 16/18 send no neighbour or shape updates (A1.4).

**Border geometry that makes updates unnecessary (design, from A2.1/A3.3):**
- *Sea edge:* the outermost Kanto column is a water half block at Y 63 over foundation at Y ≤ 62;
  the vanilla column beside it has water at Y ≤ 62 and air above. Vanilla water touches foundation,
  never air, at its own level and below, so it has nowhere to flow.
- *Land edge:* the apron's top block is Y 63, Kanto's ground is Y 63, the ring rises above both.
- *Underground:* vanilla caves, aquifers and lava touch the foundation wall only.
- The placer never removes a block that holds back a fluid, because it clears nothing.

**Skylight:** the fill makes Kanto's columns opaque from Y 62 down, like any terrain; interiors are
enclosed air lit by their light blocks, as today. Tile blocks are full opaque cubes; the half water
block's light behaviour is the rendering track's.

**Recommendation:** keep flags and throttling as they are; add a GameTest that ticks a built sea edge
for 200 ticks and counts fluid blocks inside core columns (expected 0).

**Open:** none.

### A6.3 The noise settings and the dimension type

**Answer (facts).**
- **They combine validly.** Neither codec nor constructor compares them (DimensionType.java:62-64,
  78-98; NoiseSettings.java:12-36); the generator clamps its settings to the level at use
  (NoiseSettings.java:54-58), anchors resolve against the intersection (WorldGenerationContext.java:10-13).
- **Below −64 nothing generates:** air from −256 to −65, bedrock floor unchanged at −64…−60 (A1.2).
- With the recommended generator the foundation and interior boxes under the core are written by our
  own fill down to `chunk.getMinBuildHeight()`, independent of the noise settings.

**Is a custom noise-settings copy needed? No.** The subclass refers to `minecraft:overworld` by
reference and gates its router in memory (A2.1). A copy would only be needed for the pure-data variant
(option b), or to extend *vanilla terrain itself* below −64, which nobody wants: the extra depth would
fill with lava below −54 wherever a cave opens (`createFluidPicker`, NoiseBasedChunkGenerator.java:67-73).

**Recommendation:** vanilla noise settings by reference; dimension type as today.

**Open:** none.

### A6.4 Tests

**Answer (facts).**
- The GameTest server always runs vanilla's **flat** preset with an empty stem registry
  (mc → GameTestServer.java:99-105), so neither a noise overworld nor a data-pack dimension can be the
  test world. Its registries do contain our jar data and vanilla's worldgen data.
- JUnit runs under `fabric-loader-junit` (`fabric-bridge/build.gradle:406`); pure classes need no
  Minecraft bootstrap.
- Generator stages are callable on a free-standing `ProtoChunk` (constructor: ProtoChunk.java:52-56;
  `LevelHeightAccessor.create(min, height)`, LevelHeightAccessor.java:47): `createBiomes` and
  `fillFromNoise` take no level; the 7-argument `buildSurface(ChunkAccess, WorldGenerationContext,
  RandomState, StructureManager, BiomeManager, Registry<Biome>, Blender)` is public
  (NoiseBasedChunkGenerator.java:226-240).

**Design (counts and hashes only).**

| Layer | Test | Asserts |
|---|---|---|
| JUnit (pure) | `FootprintTest` | mask from the synthetic layout: cells, perimeter, pockets; RLE round trip; hash stable; `distance` exact on hand cases; chunk kinds |
| JUnit (pure) | `RampTest` | `alpha` monotone 0→1; `targetTop` per kind; `plane` crosses zero at T+1; `heightToOffset` equals a table of vanilla values |
| JUnit (pure) | `FootprintPlannerTest` | footprint of a filtered build == footprint of the full build (whole-export rule); interiors inside the core; `InteriorPacker` never leaves the mask |
| GameTest | `generatorRegistered` | the preset decodes; codec round-trips to the same JSON; `generatorSettings()` works after freeze |
| GameTest | `insideChunk` | on a `ProtoChunk` inside the synthetic core: 256 × 318 foundation blocks, 0 others at Y ≤ 62 except interior air boxes, 0 blocks above; heightmap 62; biomes all Kanto |
| GameTest | `farChunkIsVanilla` | a chunk 4,096 blocks away is block-for-block equal (hash of its sections after `fillFromNoise` + `buildSurface`) to the same chunk from a plain `NoiseBasedChunkGenerator` with the same seed and settings |
| GameTest | `apronChunk` | top block Y == target for every apron column; no fluid in core columns; carving mask blocks apron columns |
| GameTest | `baseHeightAgrees` | `getBaseHeight` == 63 inside, == target+1 in the apron, == the vanilla generator's value far away |
| GameTest | `structureFilter` | a hand-made `StructureStart` whose box meets the core + margin becomes invalid; one outside stays |
| GameTest | `builderOnFoundation` | the existing synthetic build run over a hand-filled foundation volume: same golden WorldIndex, `blocks_cleared == 0`; removing a habitat block restores foundation |
| e2e headless | `build_world.py --preset overworld` (Pallet set; full in `--e2e`) | report: `status ok`, `blocks_cleared 0`, `foreign_blocks 0`, `fluid_blocks_in_core 0`, `entities_in_core 0`, `structure_starts_rejected ≥ 0`, `footprint.hash` equal across two runs with the same seed, `maps_built`, plus today's gates |
| Play-test | user | edges by eye; walk the apron; a sea edge; night and rain at the border |

**Recommendation:** the generator is tested by direct calls in GameTests and by the headless build;
the flat GameTest world stays as it is.

**Open:** ⚠ Not confirmed: that `createBiomes`/`fillFromNoise` on a detached `ProtoChunk` need nothing
from the level beyond the `StructureManager` (the beardifier asks it for starts,
Beardifier.java:36-66). If they do, the tests use the structure manager of the GameTest level, whose
chunks have no starts.

### A6.5 Rebuilds

**Answer (facts).** A rebuild clears the previous build's volumes (from the old index) and the new
ones, and writes only differences (A1.4). Layout positions do not depend on the map filter, but the
surface box does (A1.3).

**Rules (recommendation).**
1. **The footprint comes from the whole export, never from the filter** (as the slot map does,
   `WorldPlanner.java:260-262`). A Pallet-only build reserves all of Kanto; later builds add maps inside
   the same reservation. Gaps of unbuilt maps are covered by the ring/void fill of the built ones only
   where the builder owns them; the rest of the core stays bare foundation until built.
2. **The footprint is fixed for the life of the world.** On a rebuild the planner recomputes it; if
   the new core is a subset of the stored one, build; otherwise fail with `footprint_changed` before
   touching a block, naming nothing but the two hashes and the count of cells outside. Vanilla chunks
   next to Kanto are already generated and cannot be un-generated.
3. Same export, same world: the placer finds everything in place; foundation and apron are untouched.
4. Habitat blocks an older build left are replaced by foundation, not air (`HabitatService.java:215-221`).
5. The footprint hash goes into `build_report.json` and the region record; a world whose stored
   footprint is missing but whose `world_index.json` exists gets it recomputed from the index's map
   rectangles with the recorded `algorithm` (same result, by determinism).

**What if a new export changes the footprint's size?** Red and Blue share their maps (PROGRESS
deviation 95), so the realistic cause is a Gen1Recomp pin change. Then: a new world (rule 2), with
the player side imported (A2.11).

**Open:** none.

### A6.6 Migration

**Answer.** As A2.11: the user's flat `saves/Kanto` stays a supported legacy world (the flat preset
and the placer's behaviour on void are unchanged, and every new code path is behind
`generator instanceof KantoChunkGenerator`). It is never converted in place. Moving to a vanilla-world
Kanto means creating a new world from the same export and, if the user wants their progress, copying
the player-side folders before the first start; terrain is rebuilt, not migrated.

**Recommendation:** ship the new preset beside the old one; add the import step only after the first
vanilla-world milestone has been play-tested.

**Open:** the import's exact file list (track 2, A7.2/A8.3).

### A6 addendum: the change list for our files

Paths are under `fabric-bridge/src/main/` unless stated. "New" files do not exist today.

| File | Change | Why |
|---|---|---|
| new `resources/kantobridge.accesswidener`; `resources/fabric.mod.json` (add `"accessWidener"`); `fabric-bridge/build.gradle:29-51` (`loom { accessWidenerPath = … }`) | `extendable class …NoiseBasedChunkGenerator` | A2.1 |
| new `resources/data/kantobridge/worldgen/world_preset/kanto_overworld.json`; `resources/assets/kantobridge/lang/en_us.json:2` (add `generator.kantobridge.kanto_overworld`) | the preset | A2.1, A2.7 |
| new package `java/dev/kantobridge/worldgen/`: `KantoChunkGenerator`, `RegionBiomeSource`, `RegionNoise` (`RegionGate`, `RegionAlpha`, `RegionOffset`), `RegionField`, `RegionFill` | the generator | A2.1 |
| new `java/dev/kantobridge/world/Footprint.java` (pure Java, shared with the client), new `java/dev/kantobridge/builder/plan/FootprintPlanner.java` | mask, distance table, file codec; planning from the whole export | A3.1 |
| new `java/dev/kantobridge/server/RegionBootstrap.java`; `java/dev/kantobridge/KantoBridge.java:48-61` | codec registrations; `SERVER_STARTING` binding | A2.1 |
| `java/dev/kantobridge/block/KbBlocks.java:114` (+ blockstate, model, lang); new `resources/data/minecraft/tags/block/features_cannot_replace.json`, `dragon_immune.json`, `wither_immune.json` | `kantobridge:foundation` | A2.1, A2.4 |
| `resources/data/kantobridge/worldgen/biome/kanto_*.json` | add `grass_color`, `foliage_color` | A2.5 |
| `java/dev/kantobridge/builder/plan/WorldPlanner.java:120-124, 208-258` | give the packer the core mask; void fill over the mask, ring kind from the owner's border block | A1.3, A3.2, A3.3 |
| `java/dev/kantobridge/builder/plan/InteriorPacker.java:86-155` | sideways and deep spills stay inside the core | A2.4 |
| `java/dev/kantobridge/builder/plan/WorldPlan.java:254-280, 396-409` | surface volume per mask; `volumesOf` reads the old index's `surfaceY`; kind-aware ring | A2.6, A3.3 |
| `java/dev/kantobridge/builder/place/BlockPlacer.java:207-216` | ticket window + `getChunkNow`; counted `foreign_blocks` pass | A6.1 |
| `java/dev/kantobridge/server/HabitatService.java:215-221` | removal restores foundation in a generator world | A1.4 |
| `java/dev/kantobridge/builder/BuildTask.java:153-170, 255-290, 322-376`; `java/dev/kantobridge/builder/BuildReport.java:27-67` | verify the footprint (subset rule), record its hash, new report fields | A1.3, A3.2, A6.5 |
| `fabric-bridge/src/client/java/dev/kantobridge/client/world/CreateKantoWorldFlow.java:93-94, 420-422` | preset choice; structures on; level settings (track 2) | A2.7 |
| `java/dev/kantobridge/server/WorldIndexSync.java`; new `protocol/world/footprint.schema.json`; `docs/PROTOCOL.md` relay table (`:691-702`); `java/dev/kantobridge/world/WorldIndex.java:31-36` | `world.footprint` relay and `footprintHash` (with track 2's region record) | A2.9 |
| `tools/e2e/build_world.py:64-73, 171-187` | `--preset`, seed, new report gates | A2.7, A6.4 |
| new JUnit and GameTest classes | A6.4's table | A6.4 |

Unchanged on purpose: `kanto.json` (the flat preset), the dimension-type override, `Scale`, the
placer's flags and light throttling, the pack and slot map.

> **Integrator note.** The task list to work from is milestone M11 in [section 6](#6-milestone-plan). It differs from this list in two rows: the footprint's hash is quoted by the region record, so `WorldIndex.java` is not changed; and gates add the corridor, the gate block and the gate resolver to the builder.

---

**A7. The hidden game and Cobblemon while playing outside**

### A7.1 Party and Pokédex sync beyond Gen 1

**Answer (facts) — the trace, end to end.**

| Hop | What happens to a species above 151, a non-Gen-1 move, a form, a held item | Evidence |
|---|---|---|
| Server builds `party.sync` | `PartySync.entry` sends `dex = pokemon.getSpecies().getNationalPokedexNumber()` and drops the member only when the number is outside 1..2000 (`MAX_DEX = 2000`). Moves are `move.getTemplate().getNum()`, kept when 1..2000, at most 4. **No form, held item, ability, nature, gender or shiny field is written.** Level is clamped 1..100, HP to 0..maxhp ≤ 9999, status mapped to `psn/brn/frz/par/slp` (`tox` → `psn`). | `fabric-bridge/src/main/java/dev/kantobridge/pokemon/PartySync.java:21`, `:49-84`, `:87-96` |
| When it is sent | On Cobblemon's `DATA_SYNCHRONIZED`, then on any party/PC change, debounced to 10 ticks, never during a battle, once after each battle. Server-wide: no check of where the player is. | `.../pokemon/PartyService.java:134-136`, `:162-174`, `:191-228` |
| Schema | `party[].dex` is `nationalDex` (1..2000), `moves[]` are `moveNum` (1..2000); `additionalProperties:false`, so a form or held item **cannot** travel without a schema change. | `protocol/messages/party.sync.schema.json:17-36`; `protocol/defs.schema.json:102-107`, `:115-120` |
| Client relay | Forwards `party.sync` / `dex.sync` to gen1-bridge unchanged; keeps the newest and re-sends it after every snapshot and when the pairing gate opens. | `fabric-bridge/src/client/java/dev/kantobridge/client/net/GameRelay.java:71`, `:238-240`, `:295-297` |
| gen1-bridge validates | `Party.validSync` accepts `dex` 1..2000 and moves 1..2000 (shape only). | `gen1-bridge/act/kb_party.lua:161-187` |
| gen1-bridge applies | A member whose `dex > 151` (or whose dex has no species in the running game's registry) is **left out of the shadow party** and counted (`nonGen1Species`, warning `party.non_gen1_species`). A move `> 165` (or unknown) is dropped from that member's move list and counted (`nonGen1Moves`, warning `party.non_gen1_moves`). Both cut-offs are literal constants. | `gen1-bridge/act/kb_party.lua:343-356`, `:386-387`; lookups `gen1-bridge/lib/kb_engine.lua:1415-1427`, `:1436-1448` |
| Shadow party rebuild | `engine.applyParty(entries)` refills `save.party` in place from the surviving entries (plus unconfirmed gifts and pending daycare withdrawals). A member with **zero** surviving moves keeps its old moves, or the level-up moves `Pokemon.new` gave it (`updateMon` only replaces moves `if #e.moves > 0`). | `gen1-bridge/lib/kb_engine.lua:2481-2522`, `:2533-2594` |
| `dex.sync` (server) | Only species whose Cobblemon id is one of the 151 ids in `GenOneIds.SPECIES` (namespace `cobblemon`) are sent; `DexSync` filters again with `GenOneIds.isDex`. Knowledge is read per species record, so a regional form of a Gen 1 species counts as that species. | `.../pokemon/DexService.java:105-122`; `.../pokemon/DexSync.java:23-39`; `.../pokemon/GenOneIds.java:19`, `:105-109` |
| `dex.sync` (schema, Lua) | `seen`/`owned` items are `dex` (1..151). gen1-bridge rejects the whole message as `bad_message` if any entry is outside 1..151 (`Party.validDex`), then marks species through `speciesOfDex` (never clears). | `protocol/messages/dex.sync.schema.json:13-22`; `protocol/defs.schema.json:96-101`; `gen1-bridge/act/kb_party.lua:191-203`; `gen1-bridge/lib/kb_engine.lua:2913-2941` |
| Evolutions | Any evolution whose result has national dex > 151 is vetoed for **every player on the server** (`EVOLUTION_TESTED`, lowest priority). | `.../pokemon/PartyService.java:147`, `:505-515` |
| Nicknames | Every Cobblemon rename is cancelled server-wide (`POKEMON_NICKNAMED`). | `.../pokemon/PartyService.java:144-146` |

**Consequences today (inference from the code above).**

- A party of only non-Gen-1 Pokémon gives the hidden game an **empty party** (unless a gift is pending). Battles are
  fought in Cobblemon, so trainer and scripted battles still work, but everything that reads the shadow party does not
  see those Pokémon: field-move eligibility (`partyKnows`, PLAN P13), REPEL's lead-level check for surf encounters
  (`lead = Game.save.party[1]`, `OC:4794-4797`), and the hidden party menu on the mirror.
- The order of the shadow party is Cobblemon's order minus the members left out, so the hidden "lead" can differ from
  Cobblemon's lead.
- A Gen 1 species carrying only Gen 2+ moves shows in the hidden game with moves it does not have in Cobblemon.
- Regional forms of Gen 1 species (same national number) pass as the base species: Gen 1 stats and typing in every
  native screen. Harmless for the story; cosmetic mismatch on mirror screens.
- Held items never reach the hidden game (Gen 1 has none), so nothing breaks.

**Options.**

| Option | How | Changes | Cost / risk |
|---|---|---|---|
| (a) Keep "leave out and count" | Today's behaviour | None | Silent: the player isn't told why Surf or Cut "isn't known". Empty shadow party possible |
| (b) Refuse entry | The gate stays closed while the party holds an incompatible member | Entry check in the border machine (A4.3) against a region compatibility profile | Strict; players must use a PC before every visit, and outside there is no craftable PC (A7.3) |
| (c) Placeholders | Send incompatible members as a stand-in Gen 1 species | `PartySync` or `kb_party.apply` | The hidden party menu (mirror) would show Pokémon the player doesn't own; fake Pokédex risk; not recommended |
| (d) Keep them in the PC | On entering, move incompatible members to the PC | Server: `PlayerPartyStore`/`PCStore` moves at the gate | Changes the player's party behind their back; needs "at least one stays" logic |
| (e) Gen 1 only outside (a world setting) | Outside spawns limited to dex ≤ 151, evolution filter kept | A Cobblemon spawn rule or influence (A5.6) | Smallest surprise for a first milestone; trades and other mods can still bring other species, so (a) stays as the safety net |

**Recommendation.**

1. Keep (a) as the mechanism — it is implemented and covered by tests — and make it visible: when a `party.sync` leaves
   members out, gen1-bridge already counts it; add the count to the answer path (a `log` code the client turns into one
   HUD line: "2 Pokémon stay outside Kanto's story").
2. Add one guard at the gate (A4.3): entry is refused only when the story already has a party
   (`snapshot.party` non-empty or the starter flag set) **and** zero members are compatible. That avoids the empty shadow
   party without touching anybody's storage.
3. Default the world setting to (e) for the first milestones ("outside Kanto: the 151 only"), as a setting the user can
   turn off later.
4. Move the two literals (151, 165) and `GenOneIds.MAX_DEX/MAX_MOVE` into a **compatibility profile** of the region record
   (`rules.compat`, A8.1). That is the hook for the later "bag requirements per region": the same profile can later carry
   requirements evaluated at the gate or at battle start (e.g. level caps by badge count, read from `snapshot.badges`).

What would change the choice: if the user wants the full national dex outside from day one, drop (e) and rely on (a)+(2).

**Sketch.**

```json
"compat": {"maxDex": 151, "maxMove": 165, "forms": "base", "heldItems": "ignore",
           "onIncompatible": "leave_out", "minCompatibleToEnter": 1}
```

```
// gen1-bridge, kb_party.apply (today kb_party.lua:345, :354): use the running game's registry, not literals
species = engine.speciesOfDex(e.dex)          -- nil for a dex the game doesn't have (Blue: >151; Gold: >251)
id      = engine.moveIdOfNum(n)               -- nil for a move the game doesn't have
```

**Tests.** lupa `tools/lua/test_lua_party.py` additions: a sync with dex 152 and move 166 leaves the member out and counts
both warnings (today's behaviour, pinned); with the limits taken from the running game's registry the same test passes
unchanged. GameTest `PartyGameTests` addition: a party of one compatible and one incompatible member still sends both in
`party.sync`. lupa `tools/lua/test_lua_region.py` (new): `region.enter` answers `no_party` only when the story already
has a party and the last `party.sync` left every member out; JUnit `BorderMachineTest`: that refusal keeps the player
`OUTSIDE` with a HUD reason.

**Open.** (1) Whether the user wants incompatible Pokémon usable in Kanto battles at all (today they are: Cobblemon fights
with the real party). (2) `PokedexManager` knowledge aggregation over forms was not read in Cobblemon's source
(⚠ Not confirmed; `DexService` only uses `getSpeciesRecords()` / `getKnowledge()`).

### A7.2 Save pairs while playing outside

**Answer (facts).**

- The operation count changes only in `SavePairService.operation`, which has exactly four callers, all handlers of
  hidden-game messages: `party.give` (`.../pokemon/PartyService.java:246`), `party.take` (`:337`), `daycare.deposit`
  (`.../pokemon/DaycareService.java:135`), `daycare.withdraw` (`:199`). The pair itself changes only in
  `SavePairService.saveBegun`, called from `save.begin` (`.../server/SaveService.java:104`;
  `.../server/SavePairService.java:75-82`, `:91-105`).
- gen1-bridge counts the same four kinds, once each, when first sent (`gen1-bridge/act/kb_party.lua:543`, `:609` →
  `saves.opSent`, `gen1-bridge/act/kb_save.lua:256-259`).
- Catching, levelling, evolving, trading or boxing Pokémon outside produces only `party.sync` / `dex.sync`
  (S→C→G), which neither side counts.

So **outside play creates no save-pair operations**, and `SavePairRules.compare` keeps returning `null` for the same
pair with equal counts (`.../server/SavePairRules.java:85-88`). No M10 warning misfires because of outside play.

Things that can still surprise (inference):

1. **Timing, not logic.** A real mismatch (`new_game`, `hidden_older`, …) is detected when `save.loaded` arrives after the
   bind, which happens on joining the world wherever the player stands. `Versions.mismatch` queues an `AlertScreen`
   through `Notices` with no position check (`.../client/net/Versions.java:122-137`; `.../client/ui/Notices.java:36-38`,
   `:53-64`); `AlertScreen` pauses singleplayer. A player exploring far outside gets a Kanto warning screen.
2. **The daycare reconcile** runs on every `save.loaded` (`SavePairService.java:127-128`) and may put a boarded Pokémon
   back into the party while the player is outside. Correct, but unexpected there.
3. **Minecraft autosaves outside are not paired.** Hours outside change the Cobblemon party with no `save.begin`. After a
   Minecraft crash those Pokémon are lost as in vanilla; after a hidden-game CONTINUE nothing is lost, because Cobblemon
   is authoritative for Pokémon. The pair stays valid either way.
4. **Daycare steps** only count hidden-game steps (`OC:4713-4716`), so walking outside gives a boarded Pokémon nothing.

**Options.** (a) Leave as is. (b) Hold mismatch screens until the player is in `ENTERING`/`FOLLOWING` (A5.8).
(c) Save both games when leaving through a gate (A4.2), so the pair is fresh at every exit.

**Recommendation.** (b) and (c). (c) removes the main source of "hidden player is not at the gate I left by" after a
crash; (b) keeps Kanto's warnings where they mean something.

**Sketch.** `Notices.tick`: add `&& RegionRuntime.concerned()` (true in `ENTERING`, `FOLLOWING`, `LEAVING`) for notices
tagged `REGION`; relay-version notices stay global.

**Tests.** GameTest `SavePairGameTests` addition: a party change with no hidden-game message (a catch outside) leaves
the attachment's `ops` unchanged, and a following `save.loaded` with the same pair and counts produces no
`save.mismatch`. JUnit (next to `VersionCheckTest`): a `save.mismatch` that arrives while the border state is `OUTSIDE`
is queued and shown on `ENTERING`.

**Open.** Whether exit saves should be announced to the user (a toast "Saved") or silent like the Pokémon Center save.

### A7.3 Outside economy

**Answer (facts).**

- **Recipes.** The mod's jar overrides three Cobblemon recipes with an always-false load condition, which removes them:
  `fabric-bridge/src/main/resources/data/cobblemon/recipe/healing_machine.json:2-9`, `pc.json:2-9`, `pasture.json:2-9`
  (`fabric:not(fabric:true)`). They win because Fabric orders mod data packs by id (`docs/PROGRESS.md` deviation 4), and a
  GameTest checks the three are gone. Why: PLAN §4.2 lists "the PC, healer and pasture recipes disabled through load
  conditions" (`docs/PLAN.md:723`); DESIGN §6 says "One PC: Bill's PC in the hidden game opens Cobblemon's PC" and
  "Healing: Pokémon Centers heal in Cobblemon" (`docs/DESIGN.md:108`, `:110`); the daycare is Gen 1's (P15,
  `docs/PLAN.md:159`). Scope: the whole server (a data pack has no position).
- **Passive healing is off server-wide:** `healPercent 0`, `faintAwakenHealthPercent 0`
  (`.../server/CobblemonSettings.java:50-51`). Statuses never expire (`:62-65`).
- **Cobblemon adds Pokémon Centers to vanilla villages** (weight 35 in the five `minecraft:village/<type>/houses` pools:
  `refs/cobblemon/common/src/main/kotlin/com/cobblemon/mod/common/world/CobblemonStructures.kt:27`, `:31-35`, `:153-203`).
  ⚠ Not confirmed: that those templates contain a healing machine and a PC (the `.nbt` files are binary and were not
  opened); the name says so.
- **The bag mirror removes outside items of mirrored types.** `bag.sync` makes the inventory match the hidden bag for the
  29 mirrored battle items: `BagService.apply` → `ledger.sync(bag, counts(player))` → `change(...)` adds **or takes**
  until it matches (`fabric-bridge/src/main/java/dev/kantobridge/bag/BagService.java:118-151`), and `counts` counts every
  stack of a mirrored item in the inventory (`:205-217`). So a Poké Ball crafted or looted outside is deleted at the next
  `bag.sync` (sent after every snapshot and every hidden-bag change, `docs/PROTOCOL.md:394-396`), and a ball thrown
  outside is reported as `bag.use` and taken from the hidden bag (`BagService.java:155-200`).
- Money, key items, badges and the item PC exist only in the hidden game (CLAUDE.md "Ownership").

**Options.**

| Topic | Option | Notes |
|---|---|---|
| Healing/PC outside | (1) Keep the three recipes disabled; outside healing comes from village Pokémon Centers and medicine | No change; long walks back when a village has none |
| | (2) Re-enable the recipes (delete the three overrides) and rely on "no building inside the footprint" (A5.1) | Normal Cobblemon outside; inside nothing changes because nothing can be placed there. A healing machine at the gate costs nothing in Gen 1 terms (Centers are free) |
| Battle items | (A) Tag mirrored stacks with a custom-data marker; `BagService` counts, adds and removes **only tagged stacks** | Outside items stay the player's own; a tagged ball used anywhere still reports `bag.use` (it is the hidden bag's) |
| | (B) Feed outside gains into the hidden bag (new M→G `bag.gain`) | One economy; runs into Gen 1's 20-slot / 99-per-stack bag and lets vanilla crafting inflate the story's economy |
| | (C) Suspend the mirror outside (stash on leave, restore on enter) | Simple, but balls bought in Kanto can't be used outside |

**Recommendation.** (2) + (A). Inside Kanto the hidden bag stays the only source of story items; outside the player
keeps what Minecraft gives. (A) needs no protocol change.

**Sketch.**

```java
// BagService: a marker on every stack the mirror creates
static final String MARK = "kantobridge_bag";            // inside DataComponents.CUSTOM_DATA
static boolean mirrored(ItemStack s) { CustomData d = s.get(DataComponents.CUSTOM_DATA);
                                       return d != null && d.contains(MARK); }
// counts(): only stacks with mirrored(stack); add(): stamp new stacks; remove(): only mirrored stacks
```

Verified: `mc → net.minecraft.core.component.DataComponents#CUSTOM_DATA (DataComponents.java:57)` is a persistent
`DataComponentType<CustomData>`; `mc → net.minecraft.world.item.component.CustomData#update(DataComponentType<CustomData>,
ItemStack, Consumer<CompoundTag>) (CustomData.java:58)`, `#contains(String) (CustomData.java:137)`. Stacks merge only when
their components are equal (`mc → net.minecraft.world.item.ItemStack#isSameItemSameComponents (ItemStack.java:618)`), so
a marked and an unmarked stack of the same ball never merge.
⚠ Not confirmed: that Cobblemon's ball-throw and battle-item code only shrinks the used stack (so the marker never
leaks onto another stack); check `refs/cobblemon` when coding.

**Tests.** GameTest `BagGameTests` additions: an unmarked Poké Ball stack survives a `bag.sync` whose count for that item
is 0, while a marked stack is removed; using an unmarked ball sends no `bag.use`; a marked and an unmarked stack do not
merge. `StaticDataTests`: the recipe assertion flips if option (2) is taken.

**Open.** A decision for the user: one economy or two (B vs A). Whether Kanto-bought items should be usable outside.
Whether to re-enable the three recipes at all (option 2) or keep healing machines, PCs and pastures uncraftable (option 1).

> **Integrator note.** See the note under [A1.5](#a15-every-behaviour-that-is-world-scoped-or-server-scoped-today): the recipes stay disabled until decision D9; marked stacks are part of M12.

### A7.4 What the hidden window shows while the player is outside

**Answer (facts).**

- Today nothing tells the hidden game that the Minecraft player is elsewhere: it keeps running the overworld, NPCs
  wander, `checkTrainerSight` runs every update while nothing is scripted (`OC:1553-1554`, `OC:4372`), map music plays
  (sound passes through Gen1Recomp, PLAN U4), and gen1-bridge keeps a minimised/hidden window at full speed while a
  client is connected (`docs/PROTOCOL.md:536-545`).
- gen1-bridge already has a pattern to freeze the game without touching scripts: a **hold state** pushed on the stack
  (the Bill's PC hold, `gen1-bridge/lib/kb_engine.lua:3362-3377`). Only the top state updates
  (`refs/gen1recomp/src/core/StateStack.lua:60-63`) and every state still draws bottom-up (`:84-89`), so the overworld
  stays on screen, frozen. The classifier reports such a state as `busy` (`gen1-bridge/observe/kb_player.lua:74-78`).
- Music can be stopped and brought back through the engine's own API: `Music.fadeOut`, `Music.stop`
  (`refs/gen1recomp/src/core/Music.lua:334-348`, `:357-373`) and `Music.restoreMap` (`:491-497`), which gen1-bridge
  already uses after battles (`docs/PROGRESS.md` deviation 68).
- Minecraft's own music is silenced only by the three Kanto biomes (`"music": minecraft:intentionally_empty`,
  `.../worldgen/biome/kanto_outdoor.json:10-15`), so in vanilla biomes outside Minecraft's music plays — on top of the
  hidden game's route theme unless the latter is stopped.

**Options.** (a) Leave the hidden game running (trainers can spot the parked player and start a `battle.start` for
someone who is far away; two soundtracks at once). (b) Away hold: on `region.leave`, save, fade the music out, push a hold
state that draws a small "exploring outside" banner; on `region.enter`, pop it and `Music.restoreMap`. (c) Return to the
title screen while outside (loses the position unless saved; slow re-entry).

**Recommendation.** (b). The window then shows a frozen, labelled frame, which also serves PLAN U6 ("the user glances at
it to check it's working"). The mirror is not subscribed while outside, so nothing is captured.

**Sketch.**

```lua
-- kb_engine (new, next to holdPc): engine.away(gate) / engine.back()
local function holdAway(ow, save, gate)
  local rec = { state = "away", ow = ow, save = save, gate = gate }
  local hold = { kantoBridgeAwayHold = true }
  function hold.update() if rec.state ~= "away" and stack:top() == hold then stack:pop() end end
  function hold.draw() --[[ a one-line banner; no game text ]] end
  rec.hold = hold; st.away = rec; stack:push(hold)
  Music.fadeOut(10)                     -- Music.lua:357
end
-- back: rec.state = "back"; Music.restoreMap(game.data, "map")  -- Music.lua:491
```

The fade ends in `Music.stop()` when its level reaches 0 (`refs/gen1recomp/src/core/Music.lua:589-605`), and
`Music.update` is driven by the game loop itself, not by the overworld state (`refs/gen1recomp/src/core/Game.lua:443`),
so it keeps running under a hold state.

**Tests.** lupa `tools/lua/test_lua_region.py` (new): under the away hold the classifier reports `busy`; no
`battle.start` is sent although a trainer faces the parked player in the fake world; `Music.fadeOut` and
`Music.restoreMap` are each called once per leave/enter pair; a link drop pops the hold. e2e (ids and counts only): a
leave/enter pair leaves the hidden player's map and cell unchanged.

**Open.** Whether the banner should be drawn at all (it puts bridge text into the hidden window; it must never be game
text). ⚠ Not confirmed: that `Music.restoreMap` replays the right theme after a `Music.stop()` that cleared
`state.mapSong`-independent fields — `stop()` does not clear `state.mapSong` (`Music.lua:334-348`), so it should.

---

**A8. Region identity**

### A8.1 The region record

**Answer (facts).**

- Today a world holds exactly one index and one slot map under fixed names
  (`fabric-bridge/src/main/java/dev/kantobridge/builder/BuildTask.java:47-49`), loaded once per server
  (`.../server/WorldIndexSync.java:62-78`) and sent to every joining client as `world.index` parts followed by
  `world.slots` parts (`WorldIndexSync.java:195-224`; 64 Ki characters per part, `docs/PROTOCOL.md:717-718`).
- The WorldIndex schema is closed (`additionalProperties:false`, `version` const 1, `game` enum red/blue,
  `protocol/world/world_index.schema.json:17-20`), and a GameTest reproduces the synthetic index byte for byte
  (`docs/PROGRESS.md` deviation 24). Adding fields to it means a version bump and a new golden file.
- The protocol's `id` pattern is `^[A-Za-z0-9_.:-]+$`, at most 64 characters (`protocol/defs.schema.json:56-62`).
  **The seed sketch's id `kanto@blue` does not fit it** (`@`), nor a resource-location path (`[a-z0-9_.-/]`).
- The world id is a 36-character UUID (`.../server/SessionRecord.java:62-64`).

**Options.**

| Option | How | Cost |
|---|---|---|
| (a) Grow the WorldIndex (v2: add `region`, `origin`, `footprint`, `gates`) | One file as today | Breaks the golden file and every v1 reader; mixes "where the region is" with "what is in it" |
| (b) **Wrap it**: a small region record beside an unchanged per-region WorldIndex | New `regions/<id>/region.json`; the index, slot map and footprint sit next to it | One new file kind and one new relay message; v1 index, schema, golden test and `WorldIndex.java` untouched |
| (c) A registry file listing regions with inline indexes | One big file | Re-sends everything when one region changes |

**Recommendation.** (b). The record is the unit both parts of the report share (Part B's shape tables and pack
manifests hang off the same id).

**Sketch — the record** (`<world>/kantobridge/regions/<id>/region.json`, canonical JSON like the index):

```json
{
  "version": 1,
  "id": "kanto",
  "game": "blue",
  "family": "gen1",
  "exportHash": "<64 hex>",
  "slotMapHash": "<64 hex>",
  "indexHash": "<64 hex>",
  "origin": {"x": 0, "y": 64, "z": 0},
  "bounds": {"minX": -200, "minZ": -260, "maxX": 520, "maxZ": 480, "minY": -256, "maxY": 80},
  "footprint": {"file": "footprint.bin", "hash": "<64 hex>", "x0": -200, "z0": -260,
                "w": 360, "h": 370, "cell": 2, "classes": ["out", "apron", "core"], "sdf": true},
  "gates": [
    {"id": "g1", "kind": "land", "map": "ROUTE_22", "x0": 0, "y0": 5, "x1": 0, "y1": 6, "dir": "left"}
  ],
  "rules": {
    "profile": "kanto",
    "movement": {"walk": 0.1737, "bike": 0.3474, "jump": 0.0, "sprint": false},
    "build": false, "damage": false, "hunger": false, "vanillaMobs": false,
    "time": "noon", "weather": "clear", "keepInventory": true,
    "compat": {"maxDex": 151, "maxMove": 165, "onIncompatible": "leave_out", "minCompatibleToEnter": 1}
  },
  "namespaces": {"assets": "kanto", "pools": "kantobridge:kanto"},
  "files": {"index": "world_index.json", "slots": "slot_map.json"}
}
```

(The numbers in `bounds` and `footprint` above are made-up placeholders, not Kanto's.)

- **`id`**: `^[a-z][a-z0-9_]{0,23}$`. It is a protocol id, a folder name, a resource-path segment and the key of every
  per-region store. The game is a separate field, so `kanto` in Blue and a later `kanto_gsc` stay distinct.
- **`origin`**: the block position of the root map's cell (0,0) at feet level. Map placements inside the v1 index stay
  absolute; the origin is what the builder is given and what a rebuild or a move compares against.
- **`footprint`**: track 1's mask file. What this track needs from it: a class per mask cell (`out`, `apron`, `core`),
  optional signed distance, and a hash.
- **`gates`**: A4.2. **`rules`**: the profile A5 applies; `compat` is A7.1's profile.
- **Storage:** `<world>/kantobridge/regions/<id>/{region.json, world_index.json, slot_map.json, footprint.bin}`. A world
  with `<world>/kantobridge/world_index.json` and no `regions/` folder is a **legacy** world: one implicit region `kanto`
  with no footprint, where every position counts as inside (today's behaviour, unchanged).
- **To clients:** a new relay message `world.regions` (S→C, parts like `world.index`: `{index, count, hash, part}`),
  whose text is `{"version":1,"world":"<id>","regions":[<record>, …]}`; then, per region, the existing `world.index` and
  `world.slots` parts with one new optional field `region`, and `world.footprint{region, index, count, hash, part}` with
  the mask bytes in base64. Absent `region` means the legacy region, so an old world needs nothing new. The server sends
  them in that order on join and after a build (`WorldIndexSync.sendTo`, `WorldIndexSync.java:195-207`).
- **How it extends the WorldIndex:** it doesn't change it. `WorldIndexSync.Published` (`WorldIndexSync.java:45-47`)
  becomes one entry per region; `WorldIndexSync.current(server)` (`:154-157`) becomes
  `Regions.of(server).at(pos)` / `.byId(id)`; `ClientWorldIndex.current()` (`ClientWorldIndex.java:40-42`) becomes
  `ClientRegions.at(x, z)` plus `ClientRegions.followed()`.

All of `region.json`, the index and the footprint are ROM-derived in the sense of the WorldIndex (layout and sizes);
they stay in the world folder and travel only through the relay, as today (CLAUDE.md "ROM-derived data may pass through
the Minecraft host's server. Art never does").

**Open.** Whether `rules` lives in the record (per world, editable by the host) or in the mod as named profiles with the
record holding only `"profile": "kanto"`. Recommended: both — a named profile plus overrides in the record.

> **Integrator note.** The integrated record is in [4.5](#45-the-region-record) and in `region_record.draft.json`. Differences from this sketch: gate *specifications* stay in the record while resolved gate geometry lives in the footprint (the hashed input of generation); the slot map stays one per world ([B6.3](#b63-several-regions-in-view)), so `slotMapHash` is not a per-region field; `hashes` and `generator` were added for Part B; the footprint is the JSON of [A3.1](#a31-the-footprint), from which the class grid and distances of A8.2 are built at load; for the first region `world_index.json` stays at `<world>/kantobridge/` and the record names it in `files`.

### A8.2 Position lookups

**Answer (facts).** Today's lookup is a linear scan over all maps: `WorldIndex.toCell` loops over `maps.values()`
(220 maps in the real build) and tests the Y band and the column (`WorldIndex.java:141-149`). It runs per player per
tick on the client (`Spot.at`, `MovementSync.java:194`) and every 5 ticks per player on the server
(`FlashService.java:41-49`, `:69-79`). There is no "inside the region" test at all.

**Options.** (a) Bounding boxes of the maps only (no mask): cheap but wrong in gaps and on the ring. (b) A quadtree or
R-tree: flexible, more code than the problem needs. (c) **A flat per-cell class grid per region** behind a bounds
check, plus a chunk-key set for hot hooks.

**Recommendation.** (c).

**Sketch** (common code, `dev.kantobridge.world`, immutable, safe from any thread):

```java
public final class Footprint {
    public static final byte OUT = 0, APRON = 1, CORE = 2;
    final int x0, z0, w, h, cell;           // blocks of the grid origin, grid size in mask cells, blocks per cell
    final byte[] cls;                        // w*h classes
    final byte[] sdf;                        // w*h, cells to the nearest OUT cell, negative inside, clamped to ±127
    public byte classAt(int bx, int bz) {
        int cx = Math.floorDiv(bx - x0, cell), cz = Math.floorDiv(bz - z0, cell);
        return cx < 0 || cz < 0 || cx >= w || cz >= h ? OUT : cls[cz * w + cx];
    }
    public boolean inside(int bx, int bz) { return classAt(bx, bz) != OUT; }
    /** Blocks to the footprint's edge: negative inside, positive outside; beyond the grid, the distance to the grid box. */
    public int distance(int bx, int bz) {
        int cx = Math.floorDiv(bx - x0, cell), cz = Math.floorDiv(bz - z0, cell);
        if (cx < 0 || cz < 0 || cx >= w || cz >= h) return boxDistance(bx, bz) + EDGE_MARGIN;
        return sdf[cz * w + cx] * cell;      // used by A4.8 (bounce target), A5.4 (sky blend), A5.6 (habitat leak), A5.8 (HUD)
    }
}
public final class Regions {                 // one per server / per client world
    final Region[] list;                     // 1–3 entries
    final LongOpenHashSet chunks;            // every chunk that holds an inside cell, for block-change hooks
    public @Nullable Region at(int bx, int bz) {
        for (Region r : list) if (r.bounds.containsXZ(bx, bz) && r.footprint.inside(bx, bz)) return r;
        return null;
    }
    public boolean touchesChunk(int cx, int cz) { return chunks.contains(ChunkPos.asLong(cx, cz)); }
}
```

*Memory (estimate, to be confirmed by a report field).* The layout box is 170 × 180 Gen 1 blocks
(`export_report.json` `layout_box_blocks`), i.e. 340 × 360 cells of 2 × 2 blocks; with the 12-block ring
(`WorldPlan.java:24`) and, say, a 32-block apron on every side, the grid is about 384 × 404 = 155,136 mask cells:
155 KB for classes and 155 KB for distances as byte arrays (39 KB if the classes are packed to 2 bits). The chunk set
holds about 2,126 keys for the built volume (`build_report.json` `chunks`) plus the apron. A future report should
state it: `footprint{cells_w, cells_h, core_cells, apron_cells, chunks, bytes}`.

*Speed.* One bounds test and one array read: a few nanoseconds; with one region and 20 players, 20 lookups a tick. The
block-change hook (A5.7) does one hash probe per call for positions outside every region.

*On the wire.* Classes plus distances deflate well (large uniform areas); expect one to three 64 Ki-character relay
parts. ⚠ Not measured.

*Y.* The lookup is by column. A `CORE` column is reserved from the dimension's bottom to its top for the rules of this
track (bounce, block protection); `WorldIndex.toCell` still decides which *map* a position is on. If track 1 keeps
interiors outside the surface footprint's columns (5 interiors moved sideways and one deep spill in the real build,
`build_report.json` `notes`), those columns must be `CORE` in the mask too.

**Tests.** JUnit `FootprintTest`: classes at corners, negative coordinates, outside the grid; `RegionsTest`: two
regions, a point in neither; a micro-benchmark is not needed.

**Open.** Mask resolution (per 2-block cell or per block) is track 1's choice; the API above hides it.

> **Integrator note.** The stored and relayed form is the JSON of [A3.1](#a31-the-footprint); `cls` and `sdf` here are the in-memory tables built from it. `APRON` also covers gate corridors.

### A8.3 Session and pairing per (world, region)

**Answer (facts).**

- The bind: the server tells each joining client the world id (`SessionService.offer`, `SessionService.java:143-151`,
  body `{world, multiplayer}` `:131-140`); the client passes it to gen1-bridge when it is in a Kanto world and after
  every reconnect (`SessionBinder.tick`, `SessionBinder.java:111-131`; `SessionRuntime.tick`, `SessionRuntime.java:306-317`).
- gen1-bridge keeps `games.<version>.worlds.<world id>.slot` plus `home` in `session/pairings.json`
  (`gen1-bridge/act/kb_pairing.lua:21-31`): the pairing is **already keyed by game version**. A multiplayer bind gives
  the world its own slot; a singleplayer bind keeps the current slot (`kb_pairing.lua:33-39`).
- The server records one slot per player (`SessionRecord.java:55`, `:96-97`, `:112-118`) and one save pair per player
  (`SavePair.java:28-39`).
- `SessionGate` holds all story traffic until the bind for *this link and this world* is answered
  (`SessionGate.java:376-381`, `:439-444`).

**Options.**

| Option | How | Breaks M9? |
|---|---|---|
| (a) Composite world id: the client binds `"<world>:<region>"` | No schema change (`id` allows `:`; 36 + 1 + ≤ 24 characters fits 64) | Existing pairings are keyed by the bare world id: every multiplayer world would get a **new** slot unless the default region keeps the bare id |
| (b) **Optional `region` field** on `session.bind` (M→G leg), `session.bound`, `save.begin`, `save.loaded`, `save.mismatch` | Absent = the world's legacy region; gen1-bridge keys `worlds[world]` when absent and `worlds[world .. ":" .. region]` otherwise | No: old worlds, old pairings and old clients behave exactly as today |
| (c) One bridge link per region with its own world id | Each region's game on its own port | Needed anyway for a second game (Gold), but does not replace the key |

**Recommendation.** (b), designed so that (c) can be added: the client owns a map `region → bridge link`
(today one link, `BridgeRuntime.java:70-71`), binds each link with `{world, multiplayer, region}`, and keeps one
`SessionGate` per link. Because pairings are per game version already, Kanto-in-Blue and Johto-in-Gold in one world
never share a slot even without the region key; the key only matters for two regions of the same game.

Code paths that change:

| Path | Today | Becomes |
|---|---|---|
| `SessionBinder.tick` (`SessionBinder.java:119-122`) | `{world, multiplayer}` | adds `region` when the region is not the legacy one |
| `kb_pairing` bind key (`kb_pairing.lua:26-27`, `validWorld` `:84-86`) | `worlds[world]` | `worlds[region and (world .. ":" .. region) or world]` |
| `SessionService.onBound` (`SessionService.java:157-172`), `SessionRecord` (`:96-97`, `:124-136`) | `players{uuid:{slot}}` | format 2: `players{uuid:{slot, regions:{<id>:{slot}}}}`; format 1 files still read |
| `SaveService.onBegin` (`SaveService.java:84-91`), `SavePairService.saveBegun/operation/onLoaded` (`SavePairService.java:75-129`), `SavePair` (`SavePair.java:38-39`) | one pair per player | attachment `kantobridge:save_pairs`: region id → `{pair, at, ops}`; the old `kantobridge:save_pair` value is read once as the legacy region's |
| Story messages without a region (`party.*`, `daycare.*`, `battle.*`, `bag.sync`, `story.state`) | attributed to the player | attributed to the region the player's link is bound to (`RegionPresence.bound`), so their schemas don't change |
| `StoryState` (`StoryStates.java:19-21`) | one per player | one per player **for the followed region**; cleared on changing region (a player follows one region at a time). Cell keys stay `MAP:x:y` |
| `DaycareStore` path (`docs/PROGRESS.md` deviation 83) | `<world>/kantobridge/daycare/<uuid>.json` | `<world>/kantobridge/regions/<id>/daycare/<uuid>.json`; the old path is the legacy region's |

**Tests.** JUnit: `SessionRecordTest` (format 1 still parses; format 2 round-trips), `SavePairRulesTest` unchanged plus
a per-region map codec test, `SessionBinderTest` (bind carries `region`; a region change rebinds). lupa:
`test_lua_pairing.py` (a bind without `region` hits the old key; with it, a separate slot; `home` untouched). GameTest:
`SessionGameTests` (two regions → two recorded slots for one player).

**Open.** The singleplayer rule ("a singleplayer bind keeps the current slot") means all singleplayer worlds share the
user's own save today. With vanilla-world Kanto that stays true; if the user wants a separate story per singleplayer
world, that is a new decision, not a consequence of regions.

---

**B1. As-is audit**

### B1.1 Tile blocks

**Answer.**

*Registration (F).* `KbBlocks.register()` registers, for `family` 0..15, `tile_full_NN` (`new TileBlock(tileProperties(), FULL)`)
and `tile_half_NN` (`… HALF`), then `story_cell`, `story_tile`, `ledge_collider` and the two block-entity types
(`KbBlocks.java:47-64`). `FAMILIES_PER_SHAPE = 16` (`KbBlocks.java:27`) must equal `SlotMap.FAMILIES_PER_SHAPE`
(`SlotMap.java:33-34`, whose comment says a unit test checks it). There are no block items.

*The `hi`/`lo` properties (F).* `TileBlock.HI`/`LO` are `IntegerProperty.create("hi"|"lo", 0, 15)` (`TileBlock.java:21-22`);
`TileBlock.slot(state) = hi*16+lo`, `withSlot(slot)` sets `hi = slot>>4`, `lo = slot&15` (`TileBlock.java:44-54`). The
split exists because every state builds a neighbour table over all values of all properties (`TileBlock.java:16-18`,
`refs/_research/P5_mc_content_testing.md:87-104`); measured cost of all 8,192 states ≈10.1 MB (`docs/PROGRESS.md:42`),
i.e. **≈1.23 KB per state** (I: 10.1 MB / 8,192).

*Shapes (F).* `TileBlock#getShape` returns the family's shape for every state and context (`TileBlock.java:38-41`).
Everything else is vanilla default behaviour in 1.21.1:

| Query | Result for `tile_full_*` | Result for `tile_half_*` | Vanilla rule |
|---|---|---|---|
| outline / crosshair (`getShape`) | full cube | lower half | `mc → ClipContext.Block.OUTLINE → BlockStateBase::getShape (ClipContext.java:51-57)`; `Entity#pick (Entity.java:1673)` |
| collision | full cube | lower half | `BlockBehaviour#getCollisionShape (:352-354)` |
| occlusion shape | full cube | lower half | `#getOcclusionShape (:308-310)` |
| `isSolidRender` (opaque for light, sections and culling) | **true** | false | `BlockStateBase#isSolidRender (:677-684)`; cached per state at `(EmptyBlockGetter, ZERO)` (`Cache`, `:952-988`) |
| light block | 15 | 0 | `#getLightBlock (:320-326)`, `#propagatesSkylightDown (:436-438)` |
| AO shade (`getShadeBrightness`) | 0.2 | 1.0 | `:337-339` |
| camera clip / suffocation / view blocking | cube | half; never suffocating | `#getVisualShape (:364-366)`; `Properties.isSuffocating` default (`:1044-1049`) |
| `isSolid()` (Cobblemon ground spawns need it) | true | true (bounds average (1 + 0.5 + 1)/3 = 0.83 ≥ 0.729) | `BlockStateBase#calculateSolid (:513-533)`; `mc → AABB#getSize (AABB.java:347-352)` |

*Render layer, sound, light (F).* No render layer is registered, so `ItemBlockRenderTypes.getChunkRenderType` returns
`RenderType.solid()` (`ItemBlockRenderTypes.java:344-352`). `SoundType.STONE` for every tile; `MapColor.STONE`; no light
emission (`KbBlocks.java:86-93`). Interior light comes from separate `minecraft:light` blocks (`CodeStates.java:23`,
`WorldPlan.java:460-462`).

*How a placed block encodes atlas, palette, tile and shape (F).* It does not store them: the block **state is only a
slot number**. The chain is:

```
planner: (map.atlas, map.palette, tile, half?) --slot map--> index 0..4095      WorldPlan.java:466-474
plan:    BlockCode.full(index) | BlockCode.half(index)                           BlockCode.java:28-35
placer:  family = index / 256, slot = index % 256                                CodeStates.java:30-39
state:   kantobridge:tile_<full|half>_<family 2 digits>[hi=slot>>4, lo=slot&15]  TileBlock.java:49-54
client:  generated blockstate JSON "hi=H,lo=L" -> model -> texture               ResourcePackWriter.java:149-181
         kantobridge:block/tile/<atlas>/<palette>/<tile 4 digits>                ResourcePackWriter.java:238-241
```

The only "shape" a state knows is FULL or HALF (its family). Height is expressed by stacking the **same** state
(`WorldPlan.java:445-450`), which is why sides repeat the top tile.

*How the slot map is built (F).* `ArtCatalog.of(bundle, warnings)` walks **every map of the export** and collects keys
`(atlas, palette, tile, FULL|HALF)` (`ArtCatalog.java:69-125`): the tile of each of the map's `2w × 2h` tile columns
(HALF where the cell kind is `WATER`, lines 100-108), the 16 border tiles (109-111), and for each swap site the 16 tiles
of `before`, `after` and the data block (112-124). `SlotMap.enumerate` sorts the keys (`atlas`, `palette`, `tile`,
`shape`) and numbers them per shape, 256 per family (`SlotMap.java:42-45,157-170`). The JSON is canonical and its SHA-256
is `slotMapHash` (`SlotMap.java:150-154,206-234`).

*Capacity and exact numbers (F unless marked).*

| Quantity | Value | Source |
|---|---|---|
| Registered tile states | 8,192 (32 blocks × 256) | `KbBlocks.java:26`; `TileBlockTest` |
| Capacity per shape | 4,096 | `SlotMap.java:34-37` |
| Full slots used | 3,926 (95.8 %), 170 free | `report → slots_full` |
| Half slots used | 92 (2.2 %) | `report → slots_half` |
| Families holding slots | full 16 of 16 (family 15 holds 86), half 1 of 16 | I: 3,926 = 15×256 + 86 |
| Tile textures | 4,005 (4,018 slots share 13 textures across shapes) | `report → tile_textures`; `ResourcePackWriter.java:107-117` |
| Animated textures | 23 | `report → animated_textures` |
| Palettes in use / SGB palettes | 14 / 37 | `export_report → palettes_used, sgb_palettes` |
| Tilesets / atlas images | 24 / 34 images | `export_report → tilesets, images` |
| Cells in the export | 94,876 (water 9,331; walkable 48,346) | `export_report → cells, cell_flags` |

**Options.** None (audit).

**Recommendation.** Treat three properties of today's blocks as *load-bearing* for Part B: (1) the family decides
collision, outline, occlusion and light for all 256 states; (2) the state is a slot number, and its meaning lives in the
world's slot map; (3) full tiles are opaque full cubes. Any shaped art must respect (3): geometry that does not fill the
cube cannot live in a `tile_full_*` block (B2.4).

**Sketch.** See the chain above.

**Open.**
- A future report field **`blocks_by_kind`** (`tile_full`, `tile_half`, `story_cell`, `story_tile`, `ledge`, `light`,
  `habitat`) would replace the inferred 2,419,048; **`cells_by_kind`** (`ground`, `water`, `ledge`, `counter`, `solid`) and
  **`solid_cells_by_height`** would size the shaped subset (B2.1, B2.3).
- All tiles sound like stone (`SoundType.STONE`); a per-class sound needs either families per class or a client-side
  sound hook (NEXT_STAGES §4.5 "tile class to Minecraft sound type"). Not designed here.

### B1.2 The generated pack

**Answer.**

*Layout written by `ResourcePackWriter.generate` (F).*

```
<gameDir>/kantobridge/resourcepack/
  pack.mcmeta                      {"pack":{"pack_format":34,"description":"Kanto Bridge (generated)"}}   :93-98
  kantobridge.json                 {"game","exportHash","slotMapHash","textures"}                          :228-233
  assets/kantobridge/
    blockstates/tile_<shape>_<ff>.json          one per family that holds a slot; all 256 "hi=H,lo=L"
                                                variants; unused slots -> the jar's placeholder model      :170-182
    models/block/tile/tile_<shape>_<ff>/<sss>.json   one per used slot:
        full: {"parent":"minecraft:block/cube_all","textures":{"all":T}}
        half: {"parent":"kantobridge:block/tile_half_placeholder","textures":{"particle":T,"tile":T}}      :149-169
    textures/block/tile/<atlas>/<palette>/<tile 4 digits>.png [+ .png.mcmeta]                              :107-143,238-241
    textures/npc/<palette>/<sprite>.png          64x32 sheet                                               :184-207,243-246
    textures/player/<palette>/surf.png           64x32 sheet, per palette of a map with water              :209-226,248-251
```

`<atlas>`, `<palette>`, `<sprite>` are the export ids lower-cased with everything outside `[a-z0-9_.-]` replaced by `_`
(`pathPart`, `ResourcePackWriter.java:253-262`); two ids that collapse to one file name stop the build (`:110-116`). The
jar ships the placeholders the pack overrides: 32 single-variant blockstates, `tile_full_placeholder` (`cube_all`),
`tile_half_placeholder`, `invisible`, `tile_placeholder.png` (16×16) and `entity/puppet_placeholder.png` (64×32)
(`fabric-bridge/src/main/resources/assets/kantobridge/…`).

*Colouring (F).* The export's atlases are 4-shade greyscale PNGs. `Textures.shade(argb)` maps a pixel to shade 0..3 by
grey level (thresholds 213/128/43), transparent → −1 (`Textures.java:33-46`). Tiles: `colour(palette, max(shade,0))` is
always opaque; shade 0 is the palette's **lightest** colour, and transparent pixels of tile art also take it
(`Textures.java:48-51,63`). The palette is the map's SGB palette from the export's `palettes/sgb` (name → 4 RGB, lightest
first, `builder/export/ExportPalettes.java:13`); a missing palette falls back to greys `FFFFFF/AAAAAA/555555/000000`
(`ResourcePackWriter.java:264-267`, `Textures.java:12`). Since the key includes the palette, the same tile in two
palettes is two textures and two slots.

*Upscaling (F).* 8×8 → 16×16 nearest-neighbour (`TILE = 8`, `SCALE = 2`, `Textures.java:26-27,74-79,112-124`), chosen so
the block atlas keeps mip level 4 (`refs/_research/P5_mc_content_testing.md:233-246`; the rule is
`mc → SpriteLoader#stitch (SpriteLoader.java:56-82)`). Output is 8-bit RGBA PNG (`builder/pack/Png.java:276-277`).

*Animations (F).* Water tiles (the tileset's water tiles that are not shore, on tilesets that animate water) become an
8-frame vertical strip 16×128, the tile rotated right by `{1,2,3,2,1,0,7,0}` pixels, `.mcmeta` `{"animation":{"frametime":7}}`
(`Textures.java:14,19,81-88,99-101`; selection `ArtCatalog.java:83-90`, `ResourcePackWriter.java:128-130`). The flower
tile (tile 3 on atlases whose tileset animates flowers) becomes a 3-frame strip from the images `*.flower1..3` with frame
order `{0,1,2,0,0,1,2,0}`, frametime 7 (`Textures.java:21,90-109`; `ResourcePackWriter.java:47-53,131-133,281-309`).

*NPC sheets and the surf sprite (F).* `Textures.spriteSheet` lays every sprite out as 64×32: row 0 standing, row 1
walking; columns down, up, left, right (right = left mirrored); 3-frame sheets reuse standing frames, 1-frame sheets
their only frame; shade ≤ 0 is transparent (`Textures.java:22-24,126-161`). One sheet per `(palette, sprite)` pair that
appears as a map object (`ArtCatalog.java:94-96`). The surf sheet is the sprite named by the export's
`field.playerSprites.surf`, once per palette of a map that has water (`ArtCatalog.java:126-131,138-139`).

*`kantobridge.json` (F).* Root file with `game`, `exportHash`, `slotMapHash`, `textures` (tile + NPC count)
(`ResourcePackWriter.java:45-46,228-233`); read by `PackInfo.read/parse`, which requires `game` and both hashes
(`client/packs/PackInfo.java:23-59`).

*How it is written (F).* `PackFiles.writeTo(dir, "pack.mcmeta")` writes into `<dir>.tmp`, refuses to replace a folder
that does not look like a generated pack, deletes the old one and moves the new one in (`builder/pack/PackFiles.java:62-86`).
Writers: `PackGenerator.generate(bundleDir, packDir[, hostSlots])` on the client (`client/packs/PackGenerator.java:38-60`,
used by `CreateKantoWorldFlow.java:352` and `TextureSyncRuntime.java:179`) and `BuildTask` when `options.writePack()`
(`builder/BuildTask.java:158`, default dir `KantoWorldBuilder.defaultPackDir()`, `KantoWorldBuilder.java:44-46`).

*How it is installed (F).* `BuiltInPackSourceMixin` injects at `RETURN` of `BuiltInPackSource#loadPacks(Consumer<Pack>)`
and, for the `ClientPackSource` instance only, calls `GeneratedPack.offer(gameDir, consumer)`
(`BuiltInPackSourceMixin.java:20-27`; target `mc → BuiltInPackSource#loadPacks (BuiltInPackSource.java:39-47)`). `offer`
does nothing while `suppressed` is set or the folder has no `pack.mcmeta`; otherwise it creates the pack with
`Pack.readMetaAndCreate(location(), new PathPackResources.PathResourcesSupplier(dir), CLIENT_RESOURCES, selection())`
(`GeneratedPack.java:56-87`). Sources are asked again on every `PackRepository#reload()`
(`mc → PackRepository#reload/discoverAvailable (PackRepository.java:33-47)`).

*How it is reloaded (F).* `PackReload.reload(minecraft)` = `getResourcePackRepository().reload()` then
`minecraft.reloadResourcePacks()` (`client/packs/PackReload.java:17-22`); the future completes after
`levelRenderer.allChanged()` (`mc → Minecraft#reloadResourcePacks (Minecraft.java:898-941)`). `TextureSync` decides when:
`decide(serverHash, localHash, packLoaded, slotsHash, bridgeConnected)` → `MATCH`, `RELOAD`, `WAIT_SLOTS`,
`SLOTS_MISMATCH`, `NO_BRIDGE`, `REGENERATE` (`client/packs/TextureSync.java:141-156`); `REGENERATE` re-exports the
player's own art through `ExportClient` and rebuilds the pack **against the server's slot map**
(`TextureSyncRuntime.java:146-203`), then reloads; a pack made for other slots is *held back* by
`GeneratedPack.setSuppressed(true)` + reload so the jar's placeholders show (`TextureSync.java:296-302`,
`TextureSyncRuntime.java:214-223`).

*Where it sits in the pack stack (F).*

1. Selection is rebuilt as: the saved ids in their saved order, then **every available required pack that is missing is
   inserted at its default position**; for `TOP` that is the end of the list, i.e. the highest priority
   (`mc → PackRepository#rebuildSelected (PackRepository.java:77-87)`, `Pack.Position#insert (Pack.java:154-179)`).
   Available packs are visited in id order (`discoverAvailable` uses a `TreeMap`, `PackRepository.java:39-47`), so on a
   fresh profile the order is `vanilla` (required, BOTTOM) < `fabric` (required, TOP:
   `fapi:fabric-resource-loader-v0 → ModResourcePackCreator (ModResourcePackCreator.java:44,84,107-118)`) < `kantobridge_generated`.
   `fabric` is a placeholder; the mod jars' own resources (ours included, with the placeholder blockstates) are hidden
   packs that Fabric re-inserts directly after it on every rebuild
   (`ModResourcePackUtil#refreshAutoEnabledPacks (ModResourcePackUtil.java:90-112)`, called from
   `ResourcePackManagerMixin (ResourcePackManagerMixin.java:84-87)`), so they always stay below the generated pack.
2. Later packs win: `mc → FallbackResourceManager#getResource` walks the list from the end
   (`FallbackResourceManager.java:64-83`), `#listResources` lets later packs overwrite (`:153-176`); blockstate files of
   all packs are merged per state in pack order (`mc → BlockStateModelLoader#loadBlockStateDefinitions (BlockStateModelLoader.java:101-157)`),
   which is how the generated blockstates override the jar's placeholders.
3. So **by default the generated pack is on top of everything, including the player's own packs**, and it returns to the
   top on every start, because its id is not in `options.txt` until the player has pressed *Done* on the pack screen once
   (`mc → Options#loadSelectedResourcePacks (Options.java:1484-1510)` only restores ids that are saved;
   `Options#updateResourcePacks (Options.java:761-780)` is what saves them).

*Can a player's own pack stack above it today? (F + I)* **Yes, but only by hand, and not durably.**

- The pack screen lists it among the selected packs. It cannot be unselected (`canUnselect() = isSelected() && !isRequired()`),
  but it can be moved, because moving only checks the *neighbour's* `isFixedPosition()` and ours is `false`
  (`mc → PackSelectionModel.Entry#canUnselect (PackSelectionModel.java:102-104)`, `EntryBase#canMoveUp/canMoveDown (:186-203)`;
  `GeneratedPack.java:51-53`).
- After *Done*, `Options#updateResourcePacks` saves every selected non-fixed pack id in order, ours included, and the next
  start restores that order — **if** the generated pack exists at that moment; an id whose pack is missing is removed from
  the options (`Options.java:1484-1497`), and the pack then re-enters at the top.
- Within a session, every hold-back/offer cycle of `TextureSync` (`setSuppressed`) and the first appearance of the pack
  after "Create Kanto world" re-insert it at the top (I, from `rebuildSelected`: a pack that left `selected` comes back
  through the "missing required pack" branch).
- A player pack above ours can override single files at the same path, e.g.
  `assets/kantobridge/textures/block/tile/<atlas>/<palette>/<tile>.png` — the paths are already key-based (atlas, palette,
  tile), not slot-based. It can also override the slot-numbered blockstate/model files, which would break when the slot
  map changes.

**Options.** None (audit). The design consequences are in B2.5 and B6.2/B6.4; researcher 3b owns the pack format (B4).

**Recommendation.** For B4/B6: (1) today's default silently defeats "player packs on top" — either keep `TOP` and
document the manual reorder, or (after B2.8 step 1, when the pack holds only textures) place it below player packs;
(2) remove slot numbers from pack files (B2.8 step 1) so the only overridable files are key-addressed textures.

**Sketch.** How to get "below player packs" without a new mixin (I, to verify in a client run):
`new PackSelectionConfig(true, Pack.Position.BOTTOM, false)` inserts at the front of the list (before the first pack
that is not a fixed BOTTOM pack, `Pack.java:156-166`), i.e. below `fabric` and below every player pack. Today that would
let the jar's placeholder blockstates win. With the runtime model of B2.8 there are no blockstate files left to beat, and
textures at distinct paths do not conflict, so BOTTOM becomes safe. Until then keep TOP.

**Open.**
- ⚠ Not confirmed in a running client: the exact pack-screen behaviour (the facts above are from the 1.21.1 sources).
  Add a PLAYTEST item: put a pack with one overriding tile PNG above "Kanto Bridge (generated)", press Done, restart,
  check the order survived and the tile changed.
- The pack's `textures` count includes NPC sheets; there is no per-kind count in `kantobridge.json`.

> **Integrator note.** Decision recorded in [4.8](#48-the-texture-pack-format): the generated pack keeps `TOP` until migration step 1 of the block approach, then moves below player packs.

### B1.3 Per-player story art

**Answer.**

*What is built (F).* Swap sites are built **open** (the walkable state); each cell a site changes gets a `tile` story
cell, i.e. four `kantobridge:story_tile` blocks at feet level over the cell's 2×2 floor blocks
(`builder/plan/StoryCellPlanner.java:63-76`, `WorldPlan.java:452-457`, `BlockCode.java:18-19`). `story_tile` is
`StoryCellBlock` with its own block-entity type so that only these get a renderer (`block/StoryTileBlock.java:16-31`,
`KbBlocks.java:60-63`); the real build has 162 tile cells = 648 such blocks (`report → story_cells_by_type.tile`).

*The data (F).* The WorldIndex entry of a tile cell carries `swap{bx,by,kind,before,after}`, `start` (the map data's
block), `water`, and `closed{block, pass, height, art[4]}` (`world/StoryCellTable.java:46-62,97-131,141-142`). `closed.art`
is four **full-shape slot indices** (north-west, north-east, south-west, south-east; `art(dx,dz) = art[(dz&1)*2+(dx&1)]`,
`:116-119`), computed by the planner from the map's *closed variant* (`StoryCellPlanner.closedState`, `:107-133`):
`height` = the closed cell's column height when it is `SOLID`/`COUNTER`, else 0 (capped at 8, `StoryCellTable.java:35`);
`pass` = `GROUND`/`WATER`/`SOLID`. The slot map lists both blocks of every site, so the art has slots whichever state is
built (`ArtCatalog.java:112-124`). Block entities carry only the cell key and type (`block/StoryCellBlockEntity.java:24-28,67-83`)
and travel in chunk data and update packets (`:85-93`).

*Who decides closed or open (F).* The client's `StoryRules` (`story/StoryRules.java:304-340`): a tile cell is *shut* when
the live block at its site (from `story.blocks`/the snapshot for the hidden player's current map, else the entry's
`start`) equals `closed.block`. Shut cells go into `drawn`; they are also `active` (colliding) when `pass` is `SOLID`, or
`WATER` and the player is not surfing. `ClientStoryView.tick` (END_CLIENT_TICK) copies `rules.cells().drawn()` into a
field read by the renderer and applies/relays `active` as `story.state` (`client/story/ClientStoryView.java:114-164,96-106`).

*How a closed cell is drawn for the local player (F).* `StoryCellRenderer` (a `BlockEntityRenderer<StoryCellBlockEntity>`
registered for `STORY_TILE_ENTITY`, `KantoBridgeClient.java:39`):

1. skips unless `ClientStoryView.drawn(cellKey)` (`StoryCellRenderer.java:44,79-81`);
2. `StoryCellArt.piece(index, entry, x, z)` picks the art slot of this block's quarter of the cell and the height
   (`client/story/StoryCellArt.java:39-51`);
3. builds `KbBlocks.tileFull().get(slot/256).withSlot(slot%256)` and draws it with
   `BlockRenderDispatcher#renderSingleBlock(state, pose, buffers, packedLight, packedOverlay)` — stacked `height` times
   from feet level, or once as a layer scaled to 0.01 high lifted 0.002 when `height == 0`
   (`StoryCellRenderer.java:56-70`);
4. view distance 256 (`:28,73-76`).

`renderSingleBlock` calls `ModelBlockRenderer#renderModel` with the state's baked model
(`mc → BlockRenderDispatcher#renderSingleBlock (BlockRenderDispatcher.java:89-108)`): all faces, no neighbour culling, no
ambient occlusion, one packed light value (the block-entity renderer's light at the `story_tile` block), in the buffer of
`ItemBlockRenderTypes.getRenderType(state, false)`. It is drawn **every frame**, not meshed into the chunk.

*How the same cell looks for a player who has it open (F).* Nothing is drawn: the block is `RenderShape.INVISIBLE`
(`StoryCellBlock.java:46-49`) and that player's `drawn` set does not hold the key. The world's own blocks under and around
it are the open state. Each client decides from **its own** hidden game, so two players standing side by side see
different art; nothing about it is in the world or on the server except the collision set.

*Collision and crosshair (F).* `StoryCellBlock#getCollisionShape` is a full cube when the moving entity's governing
player has the cell active, else empty; with no entity it is a cube (`StoryCellBlock.java:61-80`). The block has no
outline, so `GameRendererMixin` (TAIL of `GameRenderer#pick(F)V`) calls `ActRuntime.pickClosedArt`, which ray-tests the
drawn columns (`client/act/ClosedArtPick.java:43-78`) so the crosshair lands on closed art (`mixin/client/GameRendererMixin.java:15-20`).
Other players standing in a cell closed for you are drawn at ≈55 % alpha (`client/fx/BlockadeFade.java:34,51-61`,
`mixin/client/LivingEntityRendererMixin.java:28-48`).

*How it updates (F).* `story.blocks`, snapshots, `player.pos`/`warp` (map change) and `player.state` (surf) mark
`StoryRules` dirty (`StoryRules.java:118-239`); the next client tick recomputes `drawn`; the block-entity renderer simply
starts or stops drawing on the next frame. **No chunk is re-meshed.** Leaving a map drops its live-block overrides, so cut
trees "grow back" (`StoryRules.java:194-206`).

**Options.** None (audit); B2.6 compares ways to do this with shapes.

**Recommendation.** Keep the ownership exactly (client decides from its own hidden game; server gets only `active`). What
has to change for shapes is only step 3 above: `renderSingleBlock` reads `BakedModel#getQuads`, so it cannot draw a model
that only implements `emitBlockQuads` (B2.6).

**Sketch.** —

**Open.** PROGRESS lists "the closed art's lighting" as play-test-only (`docs/PROGRESS.md:545`); the single light sample
per block and missing AO are the likely cause (I).

### B1.4 Puppets: rendering, textures, frame selection, sizes, culling, the surf sprite, other players

**Answer (facts).** See the puppet facts table in [section 2.4](#24-part-b-facts-blocks-packs-puppets-and-the-export); every row carries its citation. In one paragraph: a puppet is a
client-only `Entity` whose renderer emits **one textured quad** per frame through the entity cutout render
type, turned about the vertical axis to face the camera, 2×2 blocks, lit by the entity's packed light with a
constant up normal. The texture is a per-(map palette, sprite) 64×32 sheet from the generated pack; the frame
column is chosen from the NPC's facing relative to the camera yaw in four 90° sectors, the row from the Gen 1
step phase. M10 culling drops puppets beyond 48 blocks or off the hidden player's map/layer before any frame
work. The surf sprite reuses the same sheet format and vertex path from a world-render event. Other players
are vanilla player models; only their translucency inside the local player's blockades, a speech-bubble sign
and the surf sprite are ours. The local player is the vanilla model.

Three properties matter for B5:

1. **The whole puppet pipeline is client-side and already keyed by engine symbols** (sprite id, map palette).
   Nothing about a puppet's look reaches the server (`PuppetEntity.java:12-20`).
2. **Geometry is immediate-mode**: vertices are written each frame into the shared `MultiBufferSource`
   (`PuppetRenderer.java:64-68`). There is no baked model, no `ModelPart`, no model layer registration.
3. **Per-frame allocation was removed in M10** (`FacingFrames.java:44-58`: UVs precomputed;
   `PuppetTextures.java:24-41`: no key strings). Any new style has to keep that: build per-sprite geometry
   once, cache it, and only push vertices per frame.

**Options.** None (audit).

**Recommendation.** Keep `PuppetEntity`, `PuppetManager`, `Puppet`, `PuppetCulling` and `PuppetTextures`
unchanged; add character styles as a **strategy inside `PuppetRenderer.render`** (B5.2–B5.4). The surf sprite
should go through the same style code path (it is "a puppet under the player"), which means factoring the
quad emission of `SurfSprite.draw` (`SurfSprite.java:159-183`) and `PuppetRenderer.render` into one
`client/puppet/style/CharacterStyle` interface.

**Sketch.**

```java
// client/puppet/style/CharacterStyle.java (new; pure geometry + a thin Minecraft adapter)
interface CharacterStyle {
    /** True when the figure is a real 3D object turned by its own facing; false when it billboards. */
    boolean worldOriented();
    /** Emits the figure for one sheet/pose. Local space: x right, y up, z toward the viewer; feet at y = 0. */
    void emit(VertexConsumer out, PoseStack.Pose pose, int light, FigurePose figurePose);
}
record FigurePose(int column /*FacingFrames.COLUMN_*/, boolean walking, boolean mirrored, float stepPhase) {}
```

**Open.** None.

### B1.5 What a generator can use, what is missing, and where the missing pieces should come from

**Answer (facts).** The export facts table in [section 2.4](#24-part-b-facts-blocks-packs-puppets-and-the-export) lists every field with its accessor. The points that decide the design:

1. **Tiles under every block, both swap states, are already modelled in Java.** `MapModel` holds the tile id
   under each Minecraft block (`tiles[ty*tw+tx]`), the per-cell kind (`GROUND, WATER, LEDGE, COUNTER, SOLID`),
   the per-cell column height, and the same for the closed variant (`MapModel.java:34-45, 55-58, 191-233,
   291-388`). A shape planner can be written against `MapModel` without touching the export parsers.
2. **Heights are measured today, from tile ids, then thrown away.** `HeightRules.measure` computes a drawn
   unit of 1..6 tile rows per solid tile along each tile column with a period-1/period-2 repeat rule
   (`builder/plan/HeightRules.java:35-104`), and `HeightRules.classify` rounds it to 2 or 4 blocks
   (`HeightRules.java:106-109`; used at `MapModel.java:266-289`). Interiors skip measuring and use 2
   (`MapModel.java:223-232`).
3. **Pixels are available to Java** on both sides (section 2.4, "Atlas pixels"): the server-side builder holds
   the bundle (`builder/HeadlessBuild.java:147` opens it, `builder/BuildTask.java:158` uses it), and the client pack generator opens
   it too (`client/packs/PackGenerator.java:46-52`).
4. **One palette per map, four shades.** A tile's colour is `palette[shade]`; shade comes from the greyscale
   PNG (`Textures.java:32-51`). Pixel analysis (background, outline, void) should be done on **shades**, never
   on colours, so results are palette-independent.

**What is missing, and the fix for each**

| # | Needed for | Missing today | Where it should come from | ROM-derived? |
|---|---|---|---|---|
| M1 | Measured heights 1..6 blocks (B3.1 volumes) | Nothing: `HeightRules.measure` already returns the unit; only the rounding hides it | **Builder-side.** Keep `units` in `MapModel` (`heightUnits[]` per tile, new accessor `unit(tx, ty)`); stop calling `classify` for shaped columns | Derived from map data (stays in the world / WorldIndex as today) |
| M2 | Region consensus (modal height, door columns adopt the region, "rim over uniform body") | Not computed: `HeightRules` works per tile column only (`HeightRules.java:41-64`) | **Builder-side**, new pure class `builder/shape/Structures` (flood fill over `MapModel` solid tiles) | as M1 |
| M3 | Background-pixel detection, sprite-like tiles, object silhouettes | Not computed | **Client-side (and optionally builder-side) computation** from atlas shades + the map's tile grid; no export change | Art-derived: client only, never sent |
| M4 | "Void" tiles (all-dark art that must not extrude) | Not computed | **Computation** over `Textures.tileShades` (all 64 pixels shade 3). Pure function of the atlas; cheap enough to run on both sides | A per-tileset boolean set; art-derived but a classification, comparable to tile classes. Keep it out of logs; it may live in the shape index like other tile classes |
| M5 | Band classification of buildings (roof-from-above vs facade) | Not available and **not derivable with certainty**: Dramaless hand-authors exactly this (`refs/DRAMALESS_SHAPE-main/data/voxel_heights.lua:2913-2964`) | Either an automatic heuristic (B3.1 "Buildings") or a user decision to use hand-made tables (licence) or pack-supplied rules (B4.3) | Heuristic output is art-derived (client only). A hand-made table is authored data, not ROM data |
| M6 | The outdoor border ring as the game draws it | The export has only the map's own `border` block (`kb_export.lua:695-704`). The engine's renderer draws `OVERWORLD`-tileset maps with a tree-wall block by default (or a water block, or nothing for "black": the player's `voidFill` option) and every other tileset with the map's own border block: `refs/gen1recomp/src/render/TileRenderer.lua:11-32` (`TileRenderer.borderBlockFor(map)`, which reads only `map.def.tileset` and `map.def.borderBlock` and returns a block id or `false`). Dramaless uses the same call (`refs/DRAMALESS_SHAPE-main/lib/Structures.lua:165-167`). gen1-bridge does not pin `voidFill` (no occurrence in `gen1-bridge/`) | **Export addition** (small): per map `borderDrawn` = `TileRenderer.borderBlockFor({def = def})` (null for `false`), through a new `kb_engine` query, so no engine constant is copied into Java. Today the builder fills the surface void with each owner map's `border` block art (`builder/plan/WorldPlan.java:396-409`) | Map data, ROM-derived like `border` |
| M7 | Animated tiles as data | Only the `animation` symbol; tile ids and frame order are Java constants (`ResourcePackWriter.java:48-53`; `Textures.java:13-21`) | **Export addition**: tileset `animated` = the record's `animatedTiles`, else `TileRenderer.defaultAnimatedTiles(tileset)` (`refs/gen1recomp/src/render/TileRenderer.lua:309-332`: entries with `tile` or `tiles`, a `kind` of `hshift`, `frames` or `toggle`, a `period`, and `offsets`, or `images` + `sequence`, or `image` + `stripOffsets`, and an optional `gate`), image paths turned into export image ids. The engine call is already used for the image list at `kb_engine.lua:5081-5098` | ROM-derived |
| M8 | `spinner`, `pads` tile classes in Java | Exported, not parsed (`ExportTileset.java:19-21`) | **Parser addition** in `ExportTileset.parse` | — |
| M9 | Door / warp semantics per cell for "fold the door into the facade" | Present: `CellFlags.DOOR`, `WARP`, `WARP_TILE` | — | — |
| M10 | Per-tile palette groups (Gen 1 "ADVANCED" mode; needed in any case for Gen 2/3) | Left out in M2 on purpose (`docs/PROGRESS.md:241-243`) | Not needed for Gen 1 shapes. The scene description reserves the field (B3.3) | ROM-derived |
| M11 | Report counts for sizing (shape counts, structures, sprite frame counts) | Not in `build_report.json` | **Report additions** (B3.5, B8.2) | counts only |

**Options** for where the structural analysis (M1, M2) runs:

- **(a) Lua, in gen1-bridge's export.** Would need a port of the flood fill and run logic to Lua, a longer
  export (it already takes ~8 s of game time, ER:239-246), and a protocol change. No gain: the inputs are
  already in the bundle.
- **(b) Java, in the builder (`src/main`, shared by server and client).** Pure functions over `MapModel`;
  JUnit-testable on the synthetic bundle; usable by the server-side planner and by the client's shape/pack
  generator. **Recommended.**

**Recommendation.** Two tiny export additions (M6 `borderDrawn`, M7 `animated`), one parser addition (M8),
everything else as builder-side or client-side computation. Files that change:

- `gen1-bridge/export/kb_export.lua` (map record: `borderDrawn`; tileset record: `animated`),
  `gen1-bridge/lib/kb_engine.lua` (new `engine.borderBlockFor(def, tileset)` and
  `engine.animatedTiles(tileset)`; both are engine internals to re-check on a pin change, CLAUDE.md),
  `protocol/export/map.schema.json`, `protocol/export/tileset.schema.json`, `docs/PROTOCOL.md`
  ("Export resources"), `tools/fake_gen1/synth.py` (`resources()`), the synthetic bundle under
  `fabric-bridge/src/gametest/resources/kantobridge/synthetic_bundle/`, the golden
  `…/golden/synthetic_world_index.json` only if the WorldIndex changes.
- `builder/export/ExportMap.java` (`borderDrawn`, optional with fallback to `border`),
  `builder/export/ExportTileset.java` (`spinner`, `pads`, `animated`).
- New package `builder/shape/` (B3.3–B3.5).

Adding optional fields changes resource hashes, hence `exportHash`, hence nothing else: the slot map depends
only on keys (`ArtCatalog.java:60-125`).

**Sketch** (export additions, synthetic values):

```json
// map resource
{"id": "TEST_TOWN", "border": 2, "borderDrawn": 2, "…": "…"}
// tileset resource
{"id": "TEST", "animated": [
  {"tiles": [6], "kind": "hshift", "period": 20, "offsets": [1, 2, 3, 2, 1, 0, 7, 0]},
  {"tiles": [7], "kind": "frames", "period": 20, "sequence": [1, 2, 3, 1, 1, 2, 3, 1],
   "images": ["tilesets.flower1", "tilesets.flower2", "tilesets.flower3"]}
]}
```

(The synthetic values above are the ones our own Java already uses: `Textures.java:13-21`.)

**Open.**

- The drawn border depends on the player's `voidFill` option, which gen1-bridge does not pin. Either pin it
  to the default in `engine.pinDisplay` (a behaviour change in the hidden window: ask the user) or export the
  value under the current option together with the option's name and let the builder warn when it is not the
  default. Whether the ring should follow that option at all is a design choice for 3a/track 1 (the void fill
  is geometry in our world, `WorldPlan.java:23-24, 396-409`).
- Both new `kb_engine` queries are engine internals: re-check on every Gen1Recomp pin change (CLAUDE.md
  "Architecture decisions").

### B1.6 Single-region assumptions in the art pipeline

**Answer (all F).**

| Assumption | Where | What breaks with a second region |
|---|---|---|
| One generated pack folder per game directory, one pack id | `GeneratedPack.java:22,37-39` | A Johto pack would overwrite the Kanto pack (`PackFiles.writeTo` replaces the folder, `PackFiles.java:62-86`) |
| The pack records one `game`, one `exportHash`, one `slotMapHash` | `ResourcePackWriter.java:228-233`; `PackInfo.java:23` | Cannot say "fits Kanto's slots and Johto's" |
| One namespace and un-prefixed paths: `kantobridge:block/tile/<atlas>/<palette>/<tile>` | `ResourcePackWriter.java:44,238-251` | Two games with the same atlas id (`tilesets.overworld`) and palette name collide |
| Slot numbers are baked into pack files (blockstates + models per slot) | `ResourcePackWriter.java:149-182` | A pack fits exactly one slot map; M9 has to re-export and rebuild it per host (`TextureSyncRuntime.java:146-203`) |
| One slot map per world: `<world>/kantobridge/slot_map.json`; one `slotMapHash` in the WorldIndex | `BuildTask.java:47-49,260-263`; `world/WorldIndex.java:51,206` | Two regions would have to share 4,096 full slots; Kanto alone uses 3,926 |
| The tile block families are global | `KbBlocks.java:27,52-55` | Same capacity problem, in the registry |
| One WorldIndex per world; `game` ∈ {red, blue} | `WorldIndex.java:37,64-66`; `WorldIndexSync.java:45-49` (one `Published` per server) | No region id anywhere; `toCell` scans all maps of "the" index (`WorldIndex.java:141-149`) |
| `world.index` then `world.slots` once per join, each a single document in parts | `WorldIndexSync.java:183-207`; `ClientWorldIndex.java:24-27` (one assembler each, one `current`, one `slots`) | No way to address a region |
| `texture.status{ok, slotMapHash?, textures?, reason?}` is per player, compared with the one index | `server/TextureStatusService.java:32,58-73` | One status for the whole world |
| `TextureSync` judges one `serverHash` and suppresses the one pack | `TextureSync.java:141-156,296-302` | Holding the pack back blanks every region |
| Story art slots are indices into the one slot map | `StoryCellTable.java:104-108` | Fine once the table is per region |
| Client lookups are static singletons: `ClientWorldIndex.current()`, `.slotMap()` | `ClientWorldIndex.java:26-27` | Every consumer (`StoryCellRenderer`, `SurfSprite`, `BlockadeFade`, `PuppetTextures`, move/act code) assumes one |
| Texture paths for NPC and surf sheets use only palette + sprite | `ResourcePackWriter.java:243-251` | Same sprite id in two games collides |

**Options.** None (audit).

**Recommendation.** See B6.5 for what to namespace now; the two assumptions that cost a migration if left are the
un-prefixed texture paths and the slot numbers baked into pack files.

**Sketch.** —

**Open.** None.

### B1.7 Existing custom rendering

**Answer (all F; search = Grep over `fabric-bridge/src` for the Fabric and vanilla registration points).**

| Kind | Where | What it does |
|---|---|---|
| `WorldRenderEvents.AFTER_ENTITIES` | `client/fx/SurfSprite.java:81,108-185` | Surf sprite under the local surfer and other surfers: a quad in `RenderType.entityCutoutNoCull(texture)`, upright billboard or flat |
| `WorldRenderEvents.AFTER_ENTITIES` | `client/fx/SpeechBubbles.java:72,93-135` | Activity signs over other players with `Font#drawInBatch` (name-tag recipe) |
| `HudRenderCallback` | `client/bridge/BridgeRuntime.java:92` | Status line, HUD lines, F7 overlay |
| `HudRenderCallback` | `client/puppet/PuppetRuntime.java:92` | The player's own emote bubble |
| `HudRenderCallback` (own phase before default) | `client/move/MoveRuntime.java:89-90,169` | Warp fade (`graphics.fill(RenderType.guiOverlay(), …)`) |
| Block-entity renderer | `KantoBridgeClient.java:39` → `client/story/StoryCellRenderer.java` | Closed story art (B1.3) |
| Entity renderer | `client/puppet/PuppetRuntime.java:74` → `client/puppet/PuppetRenderer.java:64` | Puppets, `entityCutoutNoCull` (3b's B1.4) |
| Resource reload listener | `client/fx/SurfSprite.java:82-92` | Clears the surf texture cache |
| `DynamicTexture` | `client/ui/MirrorScreen.java:87,141-143` | The mirror's 160×144 frame |
| `LevelRenderer#isSectionCompiled` (read only) | `client/move/MoveRuntime.java:212` | Warp fade waits for the destination's sections |
| Mixin `BuiltInPackSource#loadPacks` RETURN | `mixin/client/BuiltInPackSourceMixin.java:22` | Offers the generated pack |
| Mixin `GameRenderer#pick(F)V` TAIL | `mixin/client/GameRendererMixin.java:17` | Crosshair on closed art |
| Mixin `LivingEntityRenderer`: `isBodyVisible` HEAD, `render` `@ModifyConstant(654311423)`, `@WrapWithCondition` on `RenderLayer#render` | `mixin/client/LivingEntityRendererMixin.java:28-48` | Blockade fade |
| Mixin `KeyboardInput#tick` TAIL; `LocalPlayer#canStartSprinting` `@ModifyReturnValue` | `mixin/client/KeyboardInputMixin.java:16-18`, `LocalPlayerMixin.java:14-16` | Input lock, no sprint (not rendering) |

Client mixin list: `fabric-bridge/src/client/resources/kantobridge.client.mixins.json` (5 entries).

**Not used anywhere today:** `ModelLoadingPlugin`, `FabricBakedModel`/`RendererAccess`, `BlockRenderLayerMap`,
`ColorProviderRegistry`, `WorldRenderEvents.BLOCK_OUTLINE`, `InvalidateRenderStateCallback`, `AtlasSourceTypeRegistry`,
custom shaders, `LevelRenderer#setSectionDirty` or any other re-mesh call. The block look is 100 % data (generated JSON).

**Version risk of what exists (I).** The two renderer-touching mixins are the fragile ones: `LivingEntityRenderer#render`
by constant value, and `GameRenderer#pick(F)V`. Sodium does not replace either class's logic we touch
(⚠ Not confirmed against Sodium's mixin list); Iris wraps entity rendering but `isBodyVisible` is vanilla logic.

**Options.** None (audit).

**Recommendation.** The block approach of B2.8 adds one model-loading plugin and re-mesh calls, and no production mixin;
`StoryCellRenderer` and the `GameRenderer#pick` mixin are the two existing pieces it eventually replaces or simplifies
(B2.6, B2.4).

**Sketch.** —

**Open.** None.

---

**B2. Static blocks against a flexible block**

Vocabulary used below.

- **Static**: one block state per drawable thing; its model is data (generated JSON) — today's approach.
- **Flexible model**: a `BakedModel` of ours that emits quads per position through the Fabric Rendering API (FRAPI).
- **Carrier**: a block placed in the world whose only visual job is to give the mesher a position at which to call our
  model. Its family fixes collision, outline, occlusion and light.
- **Slot**: today's `(family, hi, lo)` number naming a base tile texture through the world's slot map.
- **Shape id**: a world-scoped integer naming a shape key; **shape map**: `position → shape id` (ids only);
  **shape table**: `shape id → geometry + texture keys`, generated on each client from its own export (3b's B3).

### B2.1 The flexible block in Fabric 1.21.1

**Answer.**

**(1) Exact signatures (F; confirmed with `javap -p` on the mapped/remapped jars; `ModelModifier`'s nested interfaces and
all line numbers are from the sources jars).**

```java
// fapi:fabric-renderer-api-v1 3.4.1 — net.fabricmc.fabric.api.renderer.v1.model.FabricBakedModel
//   "automatically implemented on all baked models via Mixin and interface injection" (FabricBakedModel.java:39);
//   in the mapped jar: `public interface BakedModel extends FabricBakedModel` (mc → BakedModel.java:20)
default boolean isVanillaAdapter()                                    // :51  returns true; enhanced models must return false
default void emitBlockQuads(BlockAndTintGetter blockView, BlockState state, BlockPos pos,
                            Supplier<RandomSource> randomSupplier, RenderContext context)   // :94
default void emitItemQuads(ItemStack stack, Supplier<RandomSource> randomSupplier, RenderContext context)  // :125

// …v1.render.RenderContext (RenderContext.java)
QuadEmitter getEmitter();                                             // :56  thread-local, re-used: never retain
default boolean hasTransform();                                       // :63
void pushTransform(QuadTransform transform); void popTransform();     // :83, :89
default boolean isFaceCulled(@Nullable Direction face);               // :105 block contexts only
interface QuadTransform { boolean transform(MutableQuadView quad); }  // :122-127

// …v1.mesh.QuadEmitter extends MutableQuadView (QuadEmitter.java) — every setter returns QuadEmitter
QuadEmitter pos(int vertexIndex, float x, float y, float z);          // :45  block-relative, 0..1 "recommended"
QuadEmitter color(int vertexIndex, int color);                        // :60  (also color(c0,c1,c2,c3))
QuadEmitter uv(int vertexIndex, float u, float v);                    // :69
QuadEmitter spriteBake(TextureAtlasSprite sprite, int bakeFlags);     // :84  flags: MutableQuadView.BAKE_* (:46-99)
QuadEmitter lightmap(int vertexIndex, int lightmap);                  // :95
QuadEmitter normal(int vertexIndex, float x, float y, float z);       // MutableQuadView.java:204
QuadEmitter cullFace(@Nullable Direction face);                       // :119 also sets nominalFace
QuadEmitter nominalFace(@Nullable Direction face);                    // :122
QuadEmitter material(RenderMaterial material);                        // :125
QuadEmitter colorIndex(int colorIndex);                               // :128 tint index, default -1
QuadEmitter tag(int tag);                                             // :131
QuadEmitter copyFrom(QuadView quad);                                  // :133
QuadEmitter fromVanilla(BakedQuad quad, RenderMaterial material, @Nullable Direction cullFace);   // :139
default QuadEmitter square(Direction nominalFace, float left, float bottom, float right, float top, float depth); // :157
QuadEmitter emit();                                                   // :212 nothing is output without it
//   BAKE_ROTATE_NONE=0, _90=1, _180=2, _270=3, BAKE_LOCK_UV=4, BAKE_FLIP_U=8, BAKE_FLIP_V=16, BAKE_NORMALIZED=32

// …v1.mesh.MeshBuilder / Mesh
QuadEmitter getEmitter();  Mesh build();                              // MeshBuilder.java:35, :41
void forEach(Consumer<QuadView> consumer);                            // Mesh.java:39
default void outputTo(QuadEmitter emitter);                           // Mesh.java:46
// QuadView: toVanilla(int[] target, int targetIndex) (:181), default BakedQuad toBakedQuad(TextureAtlasSprite) (:194)
// ModelHelper: static List<BakedQuad>[] toQuadLists(Mesh) (ModelHelper.java:75), toFaceIndex (:48), NULL_FACE_ID = 6 (:41)

// …v1.material
interface RenderMaterial extends MaterialView { ResourceLocation MATERIAL_STANDARD = fabric:standard; }  // RenderMaterial.java:64,75
interface MaterialFinder extends MaterialView {                       // MaterialFinder.java
    MaterialFinder blendMode(BlendMode blendMode);                    // :43
    MaterialFinder disableColorIndex(boolean disable);                // :51
    MaterialFinder emissive(boolean isEmissive);                      // :66
    MaterialFinder disableDiffuse(boolean disable);                   // :77
    MaterialFinder ambientOcclusion(TriState mode);                   // :92
    MaterialFinder glint(TriState mode);                              // :106
    default MaterialFinder shadeMode(ShadeMode mode);                 // :120
    MaterialFinder copyFrom(MaterialView material); MaterialFinder clear(); RenderMaterial find();   // :127,:133,:144
}
enum BlendMode { DEFAULT(null), SOLID, CUTOUT_MIPPED, CUTOUT, TRANSLUCENT; public final RenderType blockRenderLayer; }  // BlendMode.java:25-53
enum ShadeMode { ENHANCED, VANILLA }                                  // ShadeMode.java:23-35

// …v1.Renderer / RendererAccess
MeshBuilder meshBuilder(); MaterialFinder materialFinder();           // Renderer.java:38, :47
@Nullable RenderMaterial materialById(ResourceLocation id); boolean registerMaterial(ResourceLocation id, RenderMaterial m); // :54, :64
RendererAccess.INSTANCE.getRenderer() / hasRenderer()                 // RendererAccess.java:29,45,50 (null when no renderer)

// fapi:fabric-model-loading-api-v1 2.1.0 — net.fabricmc.fabric.api.client.model.loading.v1
interface ModelLoadingPlugin { static void register(ModelLoadingPlugin plugin);            // ModelLoadingPlugin.java:40
                               void onInitializeModelLoader(Context pluginContext); }      // :48  called on every (re)load
interface ModelLoadingPlugin.Context {                                                    // :51-95
    void addModels(ResourceLocation... ids); void addModels(Collection<? extends ResourceLocation> ids);
    void registerBlockStateResolver(Block block, BlockStateResolver resolver);            // :70
    Event<ModelResolver> resolveModel();                                                  // :75
    Event<ModelModifier.OnLoad> modifyModelOnLoad();                                      // :80
    Event<ModelModifier.BeforeBake> modifyModelBeforeBake();                              // :89
    Event<ModelModifier.AfterBake> modifyModelAfterBake();                                // :94
}
interface BlockStateResolver { void resolveBlockStates(Context context); }                // BlockStateResolver.java:52
interface BlockStateResolver.Context { Block block(); void setModel(BlockState state, UnbakedModel model);  // :62, :70
                                       UnbakedModel getOrLoadModel(ResourceLocation id); ModelBakery loader(); }
interface ModelResolver { @Nullable UnbakedModel resolveModel(Context context); }         // ModelResolver.java:52 (Context: id(), getOrLoadModel, loader)
// ModelModifier.OnLoad#modifyModelOnLoad(UnbakedModel, Context{resourceId(), topLevelId(), …})
// ModelModifier.BeforeBake#modifyModelBeforeBake(UnbakedModel, Context{…, textureGetter(), settings(), baker()})
// ModelModifier.AfterBake#modifyModelAfterBake(@Nullable BakedModel, Context{…, sourceModel(), …}); phases OVERRIDE/DEFAULT/WRAP/WRAP_LAST
// final class DelegatingUnbakedModel(ResourceLocation delegate) — bakes once, shared by many states
// PreparableModelLoadingPlugin<T>.register(DataLoader<T>, plugin) — loads data off-thread before model loading

// mc 1.21.1
interface UnbakedModel { Collection<ResourceLocation> getDependencies();
                         void resolveParents(Function<ResourceLocation, UnbakedModel> resolver);
                         @Nullable BakedModel bake(ModelBaker baker, Function<Material, TextureAtlasSprite> spriteGetter, ModelState state); } // UnbakedModel.java:12-18
interface BakedModel extends FabricBakedModel { List<BakedQuad> getQuads(@Nullable BlockState, @Nullable Direction, RandomSource);
    boolean useAmbientOcclusion(); boolean isGui3d(); boolean usesBlockLight(); boolean isCustomRenderer();
    TextureAtlasSprite getParticleIcon(); ItemTransforms getTransforms(); ItemOverrides getOverrides(); }                          // BakedModel.java:20-35

// fapi:fabric-block-view-api-v2 1.0.11
interface FabricBlockView { @Nullable default Object getBlockEntityRenderData(BlockPos pos);   // FabricBlockView.java:67
                            default boolean hasBiomes(); default Holder<Biome> getBiomeFabric(BlockPos pos); }
interface RenderDataBlockEntity { @Nullable default Object getRenderData(); }                 // RenderDataBlockEntity.java:59
```

**(2) Which hook supplies our model for all states of our blocks (F).** `ModelLoadingPlugin.Context#registerBlockStateResolver(block, resolver)`.
For a block with a resolver, Fabric cancels vanilla's `BlockStateModelLoader#loadBlockStateDefinitions` at HEAD
(`fapi:fabric-model-loading-api-v1 → BlockStatesLoaderMixin#onHeadLoadBlockStates (BlockStatesLoaderMixin.java:38-43; "@Inject … HEAD, cancellable", Yarn target string "loadBlockStates")`,
installed by `ModelLoaderMixin (ModelLoaderMixin.java:90-102)`,
`ModelLoadingEventDispatcher#loadBlockStateModels (ModelLoadingEventDispatcher.java:82-91)`), so **no `blockstates/*.json` of any
pack is read for that block**, and the resolver must call `setModel` exactly once for each of the block's states
(`BlockStateResolver.java:44-52`; missing states get the missing model with an error, `ModelLoadingEventDispatcher.java:124-139`).
The other hooks are worse fits: `resolveModel` needs a JSON blockstate that points at a model id; `modifyModelAfterBake`
would wrap 4,018 JSON-baked models. One block may have only one resolver (`ModelLoadingPlugin.java:64-70`).

Two consequences worth knowing:

- Vanilla groups states that share an unbaked model so that swapping between them skips a re-mesh
  (`mc → ModelManager#requiresRender (ModelManager.java:243-259)`, groups built in
  `BlockStateModelLoader#loadBlockStateDefinitions (:164-196)`). Resolver-driven states never get a group (the method was
  cancelled), `modelGroups.getInt` returns −1, and `requiresRender` returns `true` — every state change of our blocks
  re-meshes. Good: no stale geometry.
- The jar's 32 placeholder blockstates, the half/full placeholder models and the pack's generated blockstates/models
  become dead files for blocks that have a resolver.

**(3) Threading of chunk meshing in 1.21.1 (F).**

| Step | Thread | Evidence |
|---|---|---|
| `LevelRenderer#compileSections(Camera)` walks visible dirty sections once per frame | render (client) thread | `mc → LevelRenderer (LevelRenderer.java:1861-1900)` |
| A section is rebuilt **synchronously** only when `prioritizeChunkUpdates` is `NEARBY` (within √768 ≈ 27.7 blocks, or player-caused) or `PLAYER_AFFECTED` (player-caused); the option's default is `NONE`, so by default every rebuild is asynchronous | render thread / workers | `LevelRenderer.java:1870-1887`; `mc → Options (Options.java:195-207)` |
| `RenderSection#createCompileTask` snapshots the 3×3 chunk columns: `RenderRegionCache#createRegion` → `new RenderChunk(levelChunk)` copies **the whole column's** block-entity map (`ImmutableMap.copyOf`) and every non-empty section's `PalettedContainer` | render thread | `mc → SectionRenderDispatcher.RenderSection#createCompileTask (SectionRenderDispatcher.java:433-445)`; `RenderRegionCache#createRegion (RenderRegionCache.java:18-39)`; `RenderChunk.<init> (RenderChunk.java:31-45)` |
| Fabric captures block-entity render data at the same moment: iterates **all** block entities of each of the 9 chunks and keeps those within one section's distance of the rebuilt section | render thread | `fapi:fabric-block-view-api-v2 → ChunkRendererRegionBuilderMixin (ChunkRendererRegionBuilderMixin.java:48-117)` |
| `RebuildTask#doTask` → `SectionCompiler#compile(sectionPos, region, sorting, buffers)` | `Util.backgroundExecutor()` (a `ForkJoinPool` of `clamp(cores−1, 1, max)` workers), or the render thread for synchronous rebuilds (`compileSync`) | `SectionRenderDispatcher.java:94-121,466-469,527-574`; `mc → Util#makeExecutor (Util.java:145-152)`; `LevelRenderer#allChanged (:694-697)` |
| `compile` visits all 4,096 positions; for `RenderShape.MODEL` it takes the buffer of `ItemBlockRenderTypes.getChunkRenderType(state)` and calls `BlockRenderDispatcher#renderBatched` → `ModelBlockRenderer#tesselateBlock` | worker | `mc → SectionCompiler#compile (SectionCompiler.java:43-102)` *javap*; `BlockRenderDispatcher#renderBatched (BlockRenderDispatcher.java:56-68)`; `ModelBlockRenderer#tesselateBlock (ModelBlockRenderer.java:47-76)` |
| Uploads | render thread (`toUpload` queue) | `SectionRenderDispatcher.java:168-173,200-210` |

*How Indigo intercepts (F).* `SectionBuilderMixin` (targets `SectionCompiler`): an `@Inject` before the block loop takes a
thread-local `TerrainRenderContext` and prepares it with the region and a function that opens layer buffers
(`fapi:fabric-renderer-indigo → SectionBuilderMixin#hookBuild (SectionBuilderMixin.java:70-81)`); a `@Redirect` of the
`renderBatched` call sends every `RenderShape.MODEL` block whose model is not a vanilla adapter — or every block, since
`Indigo.ALWAYS_TESSELATE_INDIGO` defaults to `true` (`Indigo.java:120`) — to
`TerrainRenderContext#tessellateBlock`, which calls
`model.emitBlockQuads(blockInfo.blockView, blockInfo.blockState, blockInfo.blockPos, blockInfo.randomSupplier, this)`
(`SectionBuilderMixin.java:99-112`; `TerrainRenderContext.java:83-96`). Each emitted quad then goes through
`AbstractBlockRenderContext#renderQuad`: transform → `isFaceCulled(quad.cullFace())` → material → colour → shade →
buffer of `blockInfo.effectiveRenderLayer(mat.blendMode())` (`AbstractBlockRenderContext.java:94-114`;
`BlockRenderInfo#effectiveRenderLayer (BlockRenderInfo.java:123-125)`). Outside terrain (falling blocks, breaking overlay)
`BlockModelRendererMixin` routes `ModelBlockRenderer#tesselateBlock` for non-vanilla-adapter models
(`BlockModelRendererMixin.java:40-47`). **`BlockRenderDispatcher#renderSingleBlock` is not hooked**: it calls
`ModelBlockRenderer#renderModel`, which reads `getQuads` only (`mc → BlockRenderDispatcher.java:89-108`).

So `emitBlockQuads` runs **concurrently on several worker threads and sometimes on the render thread**, exactly once per
block position per rebuild (`FabricBakedModel.java:60-62`).

**(4) What is safely readable from a mesh thread (F unless marked).**

| Readable | Why | Evidence |
|---|---|---|
| `blockView.getBlockState(pos)` for the section and its neighbours | the view is a snapshot (Indigo: `RenderChunkRegion` over copied palettes, 3×3 columns; Sodium: `LevelSlice`, section ± **2 blocks**, air outside) | `mc → RenderChunkRegion (RenderChunkRegion.java:33-56)`, `RenderChunk#getBlockState (:52-89)`; Sodium `LevelSlice` (URL S7 in B2.2) |
| `((FabricBlockView) blockView).getBlockEntityRenderData(pos)` | captured on the render thread when the region was built; the object must be immutable | `fapi → ChunkRendererRegionMixin#getBlockEntityRenderData (ChunkRendererRegionMixin.java:43-46)`; `FabricBlockView.java:37-70` |
| Biome tint (`blockView.getBlockTint`) | vanilla does it from mesh threads | `RenderChunkRegion.java:58-60` |
| Our own data, **if** published as immutable snapshots through a `volatile`/atomic reference | Java memory model | — |
| **Not** safe: `blockView.getBlockEntity(pos)` fields (live object); `blockView.getLightEngine()` (throws under Sodium); `Minecraft.getInstance().level/player`; today's `ClientWorldIndex.current` and `ClientStoryView.drawn` (plain non-volatile fields written on the client thread, "Client thread only") | | `FabricBlockView.java:40-46`; `client/world/ClientWorldIndex.java:20,26-27`; `client/story/ClientStoryView.java:55,118` |

`WorldIndex`, `MapPlacement`, `StoryCellTable`, `SlotMap` are deeply immutable (final fields, unmodifiable collections:
`world/WorldIndex.java:46-55,80-83`, `world/StoryCellTable.java:195-204`, `world/SlotMap.java:146-154`), so they can be
shared once the *reference* is published safely. Two more rules from the sources: `pos` is the mesher's mutable cursor
(`BlockPos.betweenClosed`, `SectionCompiler.java:55`; Indigo passes it straight through, `TerrainRenderContext.java:94-95`)
— copy coordinates, never keep the object; and the `RandomSource` from `randomSupplier` is not thread-safe
(`FabricBakedModel.java:90-91`).

**(5) Where per-position data can come from.**

| Source | How it works | Cost and limits |
|---|---|---|
| **(a) Block state** (today) | `state` parameter; our slot = family×256 + `hi*16+lo` | Free at mesh time. One state per distinct thing: fixed registry capacity (B2.5); ≈1.23 KB heap per state on client **and** server (`docs/PROGRESS.md:42`) |
| **(b) `RenderDataBlockEntity`** | block entity overrides `getRenderData()`; model calls `getBlockEntityRenderData(pos)` | See the cost table below: per-position objects, NBT, packets, and a main-thread copy of every block entity of 9 chunk columns for each rebuild. Only acceptable for sparse things (today's 648 `story_tile` blocks) |
| **(c) Client-side lookup by position** | model reads an immutable table `position → shape id` (per region, indexed per map/column or per section) | O(1) reads; needs a sync path for the table (like `world.slots`) and explicit invalidation (below). No per-position objects in the world |
| **(d) Neighbour queries** | `blockView.getBlockState(pos.relative(dir))` | Fine for ±1 block on both renderers; Sodium's slice ends 2 blocks outside the section. State-only: it cannot tell *which* shape a neighbour has unless (c) is also used |

*What block entities would cost, scaled with the real build (F for the mechanisms, I for the arithmetic).*

| Item | Mechanism | Scale |
|---|---|---|
| Count | every shaped block becomes a block entity | up to ≈2.42 M tile blocks in 2,126 chunks = **≈1,138 per chunk column** on average (`report → blocks_placed, chunks`; today's total of block entities is 50,664 story blocks = 24 per chunk) |
| Server/client heap | `BlockEntity` object + `HashMap` node + immutable `BlockPos` key in `LevelChunk.blockEntities` (≈150–200 B each, ⚠ estimate, not measured) | ≈0.2 MB per loaded chunk column; ≈110–140 MB for 625 loaded columns (render distance 12), on each side that has them loaded |
| Chunk NBT | one compound per block entity in `block_entities` (id, x, y, z + data) | ≈2.42 M compounds in the save (⚠ ≈60–100 B each uncompressed) |
| Chunk packets | block-entity data rides in the chunk packet through `getUpdateTag` (as `StoryCellBlockEntity` does, `block/StoryCellBlockEntity.java:85-93`) | ≈1,138 tags per column sent on every chunk load |
| Section rebuild, render thread | `RenderChunk.<init>` copies the column's whole block-entity map; Fabric's `mapChunk` iterates it again; both for each of the 9 columns of a region build (cached per `RenderRegionCache`, i.e. per frame) | ≈10,000 map entries copied + ≈10,000 visited per rebuilt section when the neighbours are equally dense (`RenderChunk.java:34`; `ChunkRendererRegionBuilderMixin.java:85-117`) |
| Ticking | none unless the block declares a ticker | 0 (`StoryCellBlock` declares none) |
| Updates | a change needs `sendBlockUpdated` (as the placer does, `builder/place/BlockPlacer.java:261-265`) | per block |

**(6) Invalidating a client-side lookup (F).** All on the client thread:

| Call | Effect | Evidence |
|---|---|---|
| `levelRenderer.setSectionDirty(sx, sy, sz)` | marks one 16³ section dirty (async rebuild, not player-flagged) | `mc → LevelRenderer#setSectionDirty (LevelRenderer.java:2327-2333)` *javap*; `ViewArea#setDirty (ViewArea.java:100-106)` |
| `levelRenderer.setBlocksDirty(minX, minY, minZ, maxX, maxY, maxZ)` | marks every section touched by the box grown by 1 block | `LevelRenderer.java:2301-2309` |
| `levelRenderer.setSectionDirtyWithNeighbors(sx, sy, sz)` | 27 sections | `:2317-2325` |
| `levelRenderer.blockChanged(level, pos, old, new, flags)` | 27 neighbours' sections; `flags & 8` (`Block.UPDATE_IMMEDIATE`) marks them player-caused | `:2284-2296`; `mc → Block.UPDATE_IMMEDIATE (Block.java:84)` |
| `ClientLevel#setBlocksDirty(pos, old, new)` → `LevelRenderer#setBlockDirty(pos, old, new)` | only if `ModelManager#requiresRender(old, new)` | `mc → ClientLevel.java:573-575`; `LevelRenderer.java:2311-2315` |
| `levelRenderer.allChanged()` | drops and rebuilds everything; also what a resource reload ends with | `:690-722`; `mc → Minecraft#reloadResourcePacks (Minecraft.java:931)` |

Guards: `LevelRenderer.viewArea` is null without a level (`setSectionDirty` would throw), and `ViewArea#setDirty` wraps
coordinates with `floorMod`, so a section outside the view marks an unrelated one dirty — harmless, as in vanilla. A
rebuilt section that was already compiled is scheduled as high priority (`SectionRenderDispatcher.java:441-443`). Under
Sodium the same vanilla entry points are `@Overwrite`n to Sodium's scheduler, so the calls keep working (B2.2).
`InvalidateRenderStateCallback.EVENT` fires inside `allChanged()`
(`fapi:fabric-rendering-v1 → InvalidateRenderStateCallback.java:31-41`, `WorldRendererMixin.java:171`) — the place to drop
caches that hold atlas sprites.

**(7) Per-quad render layers, ambient occlusion, emissive light (F).**

- **Layer per quad:** `material(finder.blendMode(BlendMode.CUTOUT).find())`. Indigo buffers each quad into
  `blendMode.blockRenderLayer`, or the block's own layer for `DEFAULT` (`BlockRenderInfo.java:123-125`); the layer buffer
  is opened on demand (`SectionBuilderMixin.java:79`). So one block can mix solid, cutout and translucent quads without a
  `BlockRenderLayerMap` entry. Sodium honours it too (B2.2).
- **AO:** per material `ambientOcclusion(TriState)`; `DEFAULT` = on when the model's `useAmbientOcclusion()` is true, the
  state's light emission is 0 and smooth lighting is enabled (`BlockRenderInfo.java:83-84`;
  `AbstractBlockRenderContext.java:105-106`). Indigo's default AO mode is `hybrid` (`Indigo.java:121`), which handles
  quads that are not on block faces. The darkness of creases comes from neighbours' `getShadeBrightness` (0.2 next to a
  full-collision block, `mc → BlockBehaviour.java:337-339`).
- **Flat light (smooth lighting off):** a quad with a `cullFace` samples light from the neighbour in that direction; a
  quad without one samples the neighbour in its light-face direction when it lies on the block's surface *or* is
  axis-aligned in a full-collision block, and otherwise the block's own position
  (`AbstractBlockRenderContext#flatBrightness (:255-273)`). So a sloped quad inside an opaque full cube takes the cube's
  own light, which is 0.
- **Inset quads with smooth lighting:** materials default to `ShadeMode.ENHANCED`; axis-aligned quads that are not on
  the block's surface blend an "outer" face with an "inner" face (sampled around the block's own position) by their
  depth (`fapi:fabric-renderer-indigo → AoCalculator (AoCalculator.java:178-194,216-220,239-248,345-357,473-488)`). The
  inner face's centre uses the block's **own** `getShadeBrightness` — 0.2 for a full-collision block — so inset faces of a
  colliding carrier darken with depth unless the carrier reports 1.0 (as the story blocks do,
  `block/StoryCellBlock.java:82-86`). A look decision for the prototype (B2.4).
- **Emissive:** `emissive(true)` sets the lightmap to `LightTexture.FULL_BRIGHT` after AO/diffuse
  (`AbstractBlockRenderContext.java:132-136,146-149`). Diffuse shading can be switched off per material
  (`disableDiffuse`).
- **Tint:** `colorIndex(i)` + a registered block colour provider; unused today.
- **UVs:** `uv()` takes 0–16 unless `BAKE_NORMALIZED`; `spriteBake(sprite, flags)` maps them into the sprite's atlas
  rectangle (`fapi:fabric-renderer-indigo → TextureHelper#bakeSprite (TextureHelper.java:37-80)`). Vanilla shrinks UVs
  towards the face centre by `sprite.uvShrinkRatio()` = 4/atlas size to avoid bleeding
  (`mc → FaceBakery (FaceBakery.java:46-52)`, `TextureAtlasSprite#uvShrinkRatio (:127-129)`); FRAPI does not, so our
  emitter should.
- **Mesh memory (Indigo):** 36 ints = **144 bytes per quad** (`EncodingFormat.java:43-71`); a `Mesh` is an immutable
  `int[]` and `outputTo` is an array copy per quad (`MeshImpl.java:62-77`). Meshes must come from
  `RendererAccess.INSTANCE.getRenderer().meshBuilder()` of the active renderer, and they embed atlas UVs.

**Options** (where the shape id lives and how it reaches the mesher).

| Option | World holds | Reaches the mesher by | Verdict |
|---|---|---|---|
| **O1 static** | a state per shape | `state` → JSON model | Rejected as the general mechanism (B2.5): fixed, shared capacity; JSON volume. Kept for what it is good at: the base slot |
| **O2 flexible, one stateless block + block entity per position** | block entity with shape id | `getBlockEntityRenderData` | Rejected: cost table above |
| **O3 flexible, one stateless block + synced side table** | nothing per state | client lookup | Works, minimal registry. But every external consumer that keys on block state (Distant Horizons colours, Iris ids, the fallback look, F3) sees one anonymous block (B2.2) |
| **O4 hybrid (recommended)** | carrier family (collision class) + base slot in the state, as today | `state` for the base tile; client lookup for the shape id | Keeps today's world valid and today's look as the fallback; shape and extra art are unlimited; costs one more carrier family set |
| O5 chunk attachments | Fabric data attachment on chunks (`fabric-data-attachment-api-v1` supports `ChunkAccess` targets and has a sync layer: `AttachmentTarget.java:34`, `impl/attachment/sync/*`) | copy into a client snapshot on arrival | Possible later as the transport of O3/O4's table (per-chunk lifecycle for free). It puts derived, regenerable data into every chunk's save and packet; not needed at Kanto's size |

**Recommendation.** **O4.** Register one `ModelLoadingPlugin` whose `BlockStateResolver` gives every state of every
carrier block a tiny per-state `UnbakedModel`/`BakedModel` of ours. `emitBlockQuads` looks the position up in an immutable
client snapshot; without an entry (or without a shape table) it emits the plain cube/half-cube of the state's slot, which
is exactly today's look. `getQuads` always returns that cube, so state-keyed consumers keep working. What would change
the choice: if the user rules out LOD mods and shader packs for good, O3 is simpler (no slot in the state).

**Sketch** (checked against the signatures above; not compiled — no code was written or run).

```java
// client/model/KbModelPlugin.java — registered once from KantoBridgeClient#onInitializeClient
public final class KbModelPlugin implements ModelLoadingPlugin {
    public static void init() { ModelLoadingPlugin.register(new KbModelPlugin()); }

    @Override public void onInitializeModelLoader(Context ctx) {          // runs at the start of every resource reload
        for (CarrierBlock block : KbBlocks.carriers()) {                  // tile_full_*, tile_half_*, shape_full_*, decor, decor_leaves, story_tile
            ctx.registerBlockStateResolver(block, r -> {
                for (BlockState s : r.block().getStateDefinition().getPossibleStates()) {
                    r.setModel(s, new TileUnbakedModel(block.kind(), block.slotIndex(s)));   // family*256 + hi*16 + lo; 0 for slot-less carriers
                }                                                         // exactly once per state
            });
        }
    }
}

final class TileUnbakedModel implements UnbakedModel {
    public Collection<ResourceLocation> getDependencies() { return List.of(); }
    public void resolveParents(Function<ResourceLocation, UnbakedModel> resolver) { }
    public BakedModel bake(ModelBaker baker, Function<Material, TextureAtlasSprite> sprites, ModelState state) {
        TextureAtlasSprite fallback = sprites.apply(new Material(TextureAtlas.LOCATION_BLOCKS, KbSprites.PLACEHOLDER));
        return new TileBakedModel(kind, slotIndex, fallback);             // 8,192+ tiny objects; no quads baked here
    }
}

final class TileBakedModel implements BakedModel {
    @Override public boolean isVanillaAdapter() { return false; }

    @Override public void emitBlockQuads(BlockAndTintGetter view, BlockState state, BlockPos pos,
                                         Supplier<RandomSource> random, RenderContext ctx) {
        RenderSnapshot rs = RenderSnapshot.current();                     // ONE volatile read; everything behind it is immutable
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();               // pos is the mesher's cursor: copy, never keep
        RegionView region = rs.regionAt(x, y, z);                         // per-thread "last region" cache inside
        QuadEmitter out = ctx.getEmitter();

        if (region != null && kind.carriesStoryArt()) {                   // story_tile and the carriers above it (B2.6)
            int closed = region.closedShapeAt(rs.story(), x, y, z);       // 0 when open for this player, or not a story position
            ShapeGeometry cg = closed == 0 ? null : region.shape(closed);
            if (cg != null) emitShape(rs, region, cg, view, x, y, z, out);
        }
        int shapeId = region == null ? 0 : region.shapeAt(x, y, z);       // 0 = no entry (also while world.shapes is missing)
        ShapeGeometry g = shapeId == 0 ? null : region.shape(shapeId);    // null while the table loads or for unknown ids
        if (g == null) {                                                   // fallback = today's look
            if (kind.drawsCube()) rs.cubeMesh(kind, slotIndex).outputTo(out);   // tile_full, tile_half, shape_full; nothing for decor/story
            return;
        }
        emitShape(rs, region, g, view, x, y, z, out);
    }

    private static void emitShape(RenderSnapshot rs, RegionView region, ShapeGeometry g, BlockAndTintGetter view,
                                  int x, int y, int z, QuadEmitter out) {
        for (int i = 0; i < g.quads(); i++) {
            int face = g.boundaryFace(i);                                  // 0..5 when the quad lies on a block face, else -1
            if (face >= 0 && rs.covered(region, view, x, y, z, face, g.mask(i))) continue;  // shaped neighbour hides it (B2.3)
            TextureAtlasSprite sprite = rs.sprite(region, g.texture(i));   // key -> sprite, resolved once per reload
            for (int v = 0; v < 4; v++) {
                out.pos(v, g.x(i, v), g.y(i, v), g.z(i, v));
                out.uv(v, lerpInset(sprite, g.u(i, v)), lerpInsetV(sprite, g.v(i, v)));   // absolute atlas UVs, inset by uvShrinkRatio
                out.color(v, -1);
            }
            out.cullFace(face >= 0 ? DIRECTIONS[face] : null);             // vanilla culling against opaque cubes
            out.nominalFace(DIRECTIONS[g.normalFace(i)]);
            out.material(rs.material(g.layer(i), g.emissive(i)));          // SOLID / CUTOUT / CUTOUT_MIPPED / TRANSLUCENT
            out.emit();
        }
    }

    // State-keyed consumers: Distant Horizons, renderSingleBlock (today's story renderer), breaking overlay.
    @Override public List<BakedQuad> getQuads(@Nullable BlockState s, @Nullable Direction side, RandomSource r) {
        return RenderSnapshot.current().cubeQuads(kind, slotIndex)[ModelHelper.toFaceIndex(side)];
    }
    @Override public boolean useAmbientOcclusion() { return true; }
    @Override public boolean isGui3d() { return false; }
    @Override public boolean usesBlockLight() { return true; }
    @Override public boolean isCustomRenderer() { return false; }
    @Override public TextureAtlasSprite getParticleIcon() { return RenderSnapshot.current().slotSprite(kind, slotIndex, fallback); }
    @Override public ItemTransforms getTransforms() { return ItemTransforms.NO_TRANSFORMS; }
    @Override public ItemOverrides getOverrides() { return ItemOverrides.EMPTY; }
}
```

`RenderSnapshot` (new, client): an immutable object holding the regions, the world's slot → sprite array, the cube meshes
and quad lists per slot, the materials, and the local player's story snapshot. It is rebuilt on the client thread and
swapped through one `volatile` field when (i) `world.index`/`world.slots`/`world.shapes` arrive or change, (ii) a
resource reload ends (`InvalidateRenderStateCallback`), (iii) the story snapshot changes (then only the story part is
replaced and the affected sections are marked dirty). Sprites are resolved on the client thread with
`minecraft.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).getSprite(id)`
(`mc → ModelManager#getAtlas (ModelManager.java:261)`, `TextureAtlas#getSprite (TextureAtlas.java:139-140)`, which returns
the missing sprite for unknown ids).

**Open.**
- ⚠ Not confirmed by a run: that a `BlockStateResolver` on 8,192+ states has no noticeable reload cost (one `add` per
  state; cheap by reading, `ModelLoadingEventDispatcher.java:117-123`).
- The per-state `BakedModel` objects need their family index; `KbBlocks` has no `family()` accessor today
  (`KbBlocks.java:70-83` exposes lists only).

### B2.2 Compatibility

**Answer.** Fetched 2026-10-03/04. "Pin" below is what a decision would pin; **nothing is recommended for pinning without
the user's decision** (spec §2.6).

| Renderer | Version line for Fabric 1.21.1 | Licence | FRAPI status | Sources |
|---|---|---|---|---|
| **Indigo** (in Fabric API) | `fabric-renderer-indigo` 1.7.1 in Fabric API 0.116.17 — already pinned | Apache-2.0 | The reference implementation. Switches itself off when any mod declares `fabric-renderer-api-v1:contains_renderer` | `fabric-renderer-indigo-…/fabric.mod.json`; `IndigoMixinConfigPlugin.java:32,43-62`; `Indigo.java:136-149` |
| **Sodium** | Two lines exist for 1.21.1: **0.6.x** (last `mc1.21.1-0.6.13-fabric`, 2025-04-04) and the backport **0.8.x** (`mc1.21.1-0.8.13-fabric`, release, 2026-08-28, file `sodium-fabric-0.8.13+mc1.21.1.jar`, sha1 `003c114c…d6a1`; branch `1.21.1/stable`) | `Polyform-Shield-1.0.0` (source-available, non-compete; not an OSI licence) | **Implements FRAPI itself**: "The Fabric Rendering API (FRAPI) is now supported out of the box!" (0.6.0 changelog). Its `fabric.mod.json` has `"fabric-renderer-api-v1:contains_renderer": true`, depends on `fabric-renderer-api-v1`, `fabric-block-view-api-v2`, `fabric-rendering-data-attachment-v1`, `fabric-rendering-fluids-v1 >=2.0.0`, `fabric-resource-loader-v0`, **`provides: ["indium"]`**, `breaks: iris <1.8.13` (0.8.13) | S1–S7 below |
| **Indium** | not applicable | Apache-2.0 | "Since Sodium 0.6.0, Indium is no longer required! … Indium also isn't compatible with Sodium 0.6.0+" | S8 |
| **Iris** | `1.8.8+1.21.1-fabric` (release, 2025-02-20) for the Sodium 0.6 line (I: from dates and Sodium 0.8's `breaks iris <1.8.13`); `1.8.14-beta.1+1.21.1-fabric` (**beta**, 2026-06-13, "Updates to Sodium 0.8", requires Sodium version id `s7adptIg`; branch `1.21.1` declares `depends sodium 0.8.x`, `breaks distanthorizons <=2.1.99`) | LGPL-3.0-only | Needs Sodium. Identifies blocks by **block id + state properties** from the shader pack's `block.properties` | I1–I5 |
| **Distant Horizons** | `3.3.3-1.21.1` (release, 2026-09-29, `DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar`); one jar for Fabric and NeoForge; client optional, server optional | LGPL-3.0-only | Not a FRAPI renderer: it draws its own LOD geometry and reads block data per **block state** | D1–D10 |

*Sodium details that matter to our model (F, from the `1.21.1/stable` sources).*

- `BlockRenderer#renderModel` calls `((FabricBakedModel) model).emitBlockQuads(this.level, state, pos, this.randomSupplier, this)`
  for every model; `processQuad` reads the quad's `RenderMaterial`: `blendMode` picks the terrain material/pass
  (`DEFAULT` → the block's `ItemBlockRenderTypes.getChunkRenderType`), `ambientOcclusion` TriState, `emissive`,
  `shadeMode` (S5). Culling per face goes through Sodium's occlusion cache keyed like vanilla's (S6).
- The block view is Sodium's `LevelSlice implements BlockAndTintGetter, RenderAttachedBlockView, FabricBlockView`:
  positions further than 2 blocks outside the section read as air; `getBlockEntityRenderData` works;
  **`getLightEngine()` throws `UnsupportedOperationException`** (S7).
- Sodium `@Overwrite`s `LevelRenderer#setBlocksDirty`, `#setSectionDirtyWithNeighbors`, the private `setBlockDirty` and
  `setSectionDirty(int,int,int,boolean)`, and `isSectionCompiled`, forwarding to its own scheduler (S4). The public
  `setSectionDirty(int,int,int)` we would call delegates to the overwritten private one, so invalidation is unchanged;
  `MoveRuntime`'s existing `isSectionCompiled` use (`client/move/MoveRuntime.java:212`) is covered too.
- Sodium has its own "material downgrading" optimisation (0.6.0 changelog, S3) that may move quads whose sprite has no
  transparent texels to a cheaper pass; ⚠ its exact rules were not read. Nothing in our design depends on it.

*Iris: how shader packs identify our blocks (F from the shader documentation site; I where marked).*

- `block.properties` in the shader pack: `block.<ID> = [<namespace>:]<block_id>[:<property>=<value>,…]`; "the optional
  namespace allows support for modded blocks"; a block (state) can have only one id; ids are 16-bit signed (I2).
  The id arrives in `mc_Entity.x` in `gbuffers_terrain`, `gbuffers_water` and `shadow`; **unmapped blocks get −1** (I3).
  Render layers can be overridden per block with `layer.solid|cutout|cutout_mipped|translucent=<blocks>`, and data-pack
  tags can be used with `%[<namespace>:]<tag>` under `IRIS_TAG_SUPPORT` (I2).
- So a shader pack knows nothing about `kantobridge:*` unless the pack (or the player) lists our ids. Waving foliage and
  water effects are keyed on those ids. **Custom-model blocks can be mapped** exactly like any block, because the id is
  per block state, not per model (I: follows from the id being assigned from `block.properties` by block id; ⚠ Not
  confirmed in Iris's source that FRAPI-emitted quads get the state's id under Sodium).
- Consequence for our block design: generic families (`tile_full_07[hi=3,lo=9]`) carry no meaning a pack could map
  ("leaves", "water"). If waving canopies or shader water are wanted, the **class must be visible in the block id or a
  state property** — e.g. a separate carrier block for foliage (`decor_leaves`) and water (`tile_half_*` is already
  water-only: `ArtCatalog.java:102-103`). Then a three-line `block.properties` snippet per shader pack does it.
  Our "water" is an opaque animated tile in the solid layer, not a fluid: packs treat it as terrain unless mapped.

*Distant Horizons: how it derives LODs for our blocks (F, from DH's `main` sources; applies to the 1.21.1 build through
its preprocessor branches).*

- **Colour is per block state**: `ClientBlockStateColorCache` asks, for directions UP, unculled, N, E, W, S, DOWN in that
  order, `MC.getModelManager().getBlockModelShaper().getBlockModel(state).getQuads(state, direction, RANDOM)` (the
  `MC_VER < 1.21.5` branch), takes the **first quad's sprite** and averages its texels (alpha-weighted, in linear light);
  no quads → the particle icon's average; failure → hot pink (D5, D6). So DH never calls `emitBlockQuads`, and has no
  position when it asks. DH 3.x also exposes per-state face textures (`BlockStateTextureProvider`, D7).
- **What counts as a block to draw:** config `blocksToIgnore` defaults to `NON_COLLIDING` ("only render solid blocks");
  solid = the state's collision shape at `(EmptyBlockGetter, ZERO)` is non-empty (D8, D9). `ignoredRenderBlockCsv`
  defaults to `minecraft:barrier, structure_void, light, tripwire, brown_mushroom` (D9). `tintWithAvoidedBlocks`
  defaults to `true`: an avoided block tints the surface under it (D9). Opacity: fully transparent when
  `propagatesSkylightDown && !canOcclude`, else opaque (D8).
- **For today's blocks (I):** `tile_full_*`/`tile_half_*` states each have a `cube_all`/half model → correct average
  colour per tile → Kanto would LOD correctly today. **`story_cell`, `story_tile` and `ledge_collider` are a problem**:
  their empty-context collision is a full cube/wall (`StoryCellBlock.java:61-70`, `LedgeColliderBlock.java:66-70`), so DH
  counts them as solid, and their model has no quads, so their colour is the placeholder particle texture — 50,664 +
  3,032 phantom blocks, mostly over water. They must go on DH's ignore list (config, or DH's API), or get a fully
  transparent particle texture. ⚠ Not confirmed in a run.
- **With a flexible model:** correct as long as `getQuads(state, UP, …)` returns a quad with the right sprite — which is
  why the recommended carriers keep a slot in the state. A stateless flexible block would give every position the same
  colour. DH has an API event for per-position colour overrides (`DhApiBlockColorOverrideEvent`, used in D5) as the
  alternative; it needs DH's API jar on the compile classpath.
- **Shader material ids:** DH gives shader packs `dhMaterialId` (`DH_BLOCK_LEAVES`, `…_WATER`, `…_STONE`, … `…_UNKNOWN`)
  (I4), computed from tags, sound types and **substrings of the block id** (D8): `blockState.is(BlockTags.LEAVES)` or id
  contains "bamboo"/"cactus"/"mushroom" → leaves; a fluid state → water; `SoundType.WOOD` → wood; id contains "sand",
  "snow", "stone", "dirt"… → those. Our ids contain none, and the sound type is STONE (not checked for stone), so
  everything is `UNKNOWN` unless the families are named or tagged for it.
- **Tall dimension:** ⚠ Not confirmed. One issue about LOD artefacts in a world of Y −256…1024 (DH 2.1.2-a, MC 1.20.4)
  was closed as "Unable To Reproduce / Need More Info" (D4). Our dimension type is −256…319 (`min_y -256`, `height 576`:
  `fabric-bridge/src/main/resources/data/minecraft/dimension_type/overworld.json:9-12`). Must be in the experiment.
- **With Sodium/Iris:** DH's wiki lists Sodium and "Iris 1.7.5 and newer" as supported and says shaders need DH support
  built in (D2, D1). Its troubleshooting page says "Fabric + Sodium crashing: On MC 1.21.1 and older install the Indium
  mod" (D3) — **this contradicts Indium's own page and Sodium's metadata for Sodium ≥ 0.6** (S8, S2). The code settles
  it: Sodium ≥ 0.6 `provides` `indium` and implements FRAPI; the DH note is stale for the 0.6+/0.8 lines. Flagged under
  Open, not Blocked.
- **Multiplayer:** client-only DH builds LODs from chunks the client has seen; with DH on the server LODs are sent to
  clients (D10). LODs are block-state data, so they are not per-player: closed story art never appears in LODs (correct).

**Options** (dependency choices for the user; each is independent).

| Option | Pin | Licence | What we would have to do | Risk |
|---|---|---|---|---|
| A. Indigo only (today) | none | — | nothing | Lowest fps headroom for dense shapes; no LOD, no shaders |
| B. Sodium | `mc1.21.1-0.8.13-fabric` *or* `mc1.21.1-0.6.13-fabric` | Polyform Shield 1.0.0 | test our model on its FRAPI path; never use Indigo internals; neighbour reads ≤ 2 blocks; no `getLightEngine()` | The 0.8 line on 1.21.1 is a backport that was alpha in May 2026 ("Support level for this branch depends on testing", S3); licence is not open source (redistribution in an installer needs a look) |
| C. Iris (needs B) | `1.8.8+1.21.1-fabric` with Sodium 0.6.13, or `1.8.14-beta.1+1.21.1-fabric` with Sodium 0.8.x | LGPL-3.0-only | optional: document a `block.properties` snippet; name/tag carrier families by class | With Sodium 0.8 only a beta exists for 1.21.1; shader packs may treat our terrain as generic blocks |
| D. Distant Horizons | `3.3.3-1.21.1` | LGPL-3.0-only | ignore list for the three invisible blocks; keep slots in carrier states; test the −256 world; optional API use for colours | Tall-world behaviour unconfirmed; with Iris only DH-aware shader packs work; LODs of chunks never loaded need DH's generator or a server-side DH |

**Recommendation.** Design and test against **A and B** (the model must not depend on Indigo); treat **C and D as
opt-in extras** whose only design cost now is (1) slots stay in carrier states, (2) carrier families are split by class
where a pack could care (water already is; add a foliage class for decor), (3) the three invisible blocks get a
transparent particle texture. Decide pins after the B2.7 prototype has numbers.

**Sketch.** A `block.properties` fragment a player could add to a shader pack (ids depend on the pack):

```
# Kanto Bridge (example; <leavesId>/<waterId> are the pack's own ids)
block.<leavesId> = kantobridge:decor_leaves
block.<waterId>  = kantobridge:tile_half_00
```

**Sources fetched** (S = Sodium, I = Iris, D = Distant Horizons):

- S1 `https://api.modrinth.com/v2/project/sodium/version?game_versions=["1.21.1"]&loaders=["fabric"]` · project `https://api.modrinth.com/v2/project/sodium`
- S2 `https://raw.githubusercontent.com/CaffeineMC/sodium/mc1.21.1-0.8.13/fabric/src/main/resources/fabric.mod.json` (same content on branch `1.21.1/stable`)
- S3 `https://api.modrinth.com/v2/project/sodium/version/mc1.21.1-0.8.13-fabric`, `…/mc1.21.1-0.8.12-alpha.1-fabric`, `…/mc1.21.1-0.6.0-fabric`; branches `https://github.com/CaffeineMC/sodium/branches/all`
- S4 `…/sodium/1.21.1/stable/common/src/main/java/net/caffeinemc/mods/sodium/mixin/core/render/world/LevelRendererMixin.java`
- S5 `…/client/render/chunk/compile/pipeline/BlockRenderer.java` · `…/client/render/frapi/SodiumRenderer.java`
- S6 `…/client/render/frapi/render/AbstractBlockRenderContext.java`
- S7 `…/client/world/LevelSlice.java`
- S8 `https://api.modrinth.com/v2/project/indium`
- I1 `https://api.modrinth.com/v2/project/iris/version?game_versions=["1.21.1"]&loaders=["fabric"]`, `…/version/1.8.14-beta.1+1.21.1-fabric`, `…/version/1.8.8+1.21.1-fabric`, project `…/project/iris`
- I2 `https://shaders.properties/current/reference/miscellaneous/block_properties/`
- I3 `https://shaders.properties/current/reference/attributes/mc_entity/`
- I4 `https://shaders.properties/current/reference/mod-support/distant_horizons/`
- I5 `https://raw.githubusercontent.com/IrisShaders/Iris/1.21.1/fabric/src/main/resources/fabric.mod.json`
- D1 `https://api.modrinth.com/v2/project/distanthorizons` and `…/version?game_versions=["1.21.1"]&loaders=["fabric"]`
- D2 wiki "Mod Support" · D3 "Problems and Solutions" · D10 "General" (`https://gitlab.com/api/v4/projects/distant-horizons-team%2Fdistant-horizons/wikis/<slug>`)
- D4 `https://gitlab.com/api/v4/projects/distant-horizons-team%2Fdistant-horizons/issues/785`
- D5 `https://gitlab.com/distant-horizons-team/distant-horizons/-/raw/main/common/src/main/java/com/seibel/distanthorizons/common/wrappers/block/ClientBlockStateColorCache.java`
- D6 `…/block/QuadWrapper.java` · D7 `…/block/BlockStateTextureProvider.java` · D8 `…/block/BlockStateWrapper.java`
- D9 `https://gitlab.com/distant-horizons-team/distant-horizons-core/-/raw/main/core/src/main/java/com/seibel/distanthorizons/core/config/Config.java`

**Open.**
- ⚠ Everything in this section about third-party behaviour is from reading metadata, documentation and source files, not
  from running them with our mod.
- ⚠ DH `main` is a multi-version source tree; the quoted branches are the ones its preprocessor selects for 1.21.1
  (`MC_VER < MC_1_21_5`), but the 3.3.3 release was not decompiled.
- Whether Sodium's and Iris's licences allow bundling in the planned installer (`docs/INSTALLER.md`) is a question for
  the user; downloading at install time is the usual route.
- ⚠ Not confirmed: that animated tiles (water, flowers) keep animating under Sodium when their quads come from our
  model with sub-rectangle UVs (Sodium only animates sprites it has seen in meshes and finds them from quad UVs). Check
  in the prototype.

> **Integrator note.** Re-checked by the integrator: Sodium's `fabric.mod.json` for `mc1.21.1-0.8.13` (licence, `provides: indium`, `contains_renderer`, `breaks iris <1.8.13`) and the Distant Horizons release `3.3.3-1.21.1` of 2026-09-29 ([10.3](#103-verification-log), rows 27–28).

### B2.3 Performance [measure]

**Answer (facts that set the scale).**

- A section mesh is `DefaultVertexFormat.BLOCK` = 32 bytes per vertex, **128 bytes per quad** in the build buffer
  (`mc → DefaultVertexFormat (DefaultVertexFormat.java:9-16)`). The per-thread buffers start at 4,194,304 bytes for solid
  and cutout-mipped (**32,768 quads** before a buffer has to grow) and 786,432 for cutout and translucent (6,144 quads)
  (`mc → RenderType (RenderType.java:26-67)`); the worker count is limited by the buffer pool, sized from 30 % of the heap
  (`mc → SectionBufferBuilderPool#allocate (SectionBufferBuilderPool.java:24-26)`).
- Reference loads in vanilla terms (I): a flat ground layer is 256 quads per section; a section full of leaves in fancy
  graphics is up to 4,096 × 6 = 24,576 quads (leaves do not cull each other). Today's Kanto surface sections are far
  below that: one quad per exposed cube face.
- Scale of our art: a block is 8 Game Boy pixels across (`world/Scale.java:7`), so the finest sensible voxel is 1/8 block
  (2/16) and a block holds at most 8×8×8 voxels. There is **no merging across blocks** in any FRAPI renderer: each block
  emits its own quads, so "greedy meshing" means within one block.
- Quad count depends on geometry only, not on colours: a face's texture is one sprite sampled through UVs, so any
  rectangle of coplanar voxel faces that maps to one tile is one quad.
- A cached Indigo `Mesh` costs 144 bytes per quad (B2.1); direct emission from a compact table costs no mesh memory.
- Occlusion between sections (`VisGraph`) only counts `isSolidRender` blocks (`SectionCompiler.java:57-59`): carriers
  that do not occlude make sections "see-through" for the visibility graph.
- Atlas: 4,005 tile sprites × 16×16 = 1.03 M texels (≈ 5.5 MB with mips, I) — negligible.

**Options** for the cost drivers.

| Driver | Option | Effect |
|---|---|---|
| Quads per block | (a) cap in the generator (e.g. heights quantised to 2 px for canopies), (b) uncapped | (a) bounded mesh time and buffer use |
| Hidden faces between two shaped blocks | (a) emit all (engine cannot cull against non-occluding neighbours), (b) per-shape **face masks**: one 64-bit mask (8×8 cells) per block face per shape; a boundary quad is skipped when its footprint mask ⊆ the neighbour's opposite-face mask | (b) removes most interior faces of dense canopies/roofs for 6 `long`s per shape and one AND per boundary quad |
| Geometry storage | (a) `Mesh` per (shape, texture binding) cached, (b) compact shape table + direct emission, (c) (b) plus a bounded LRU of meshes | (a) fastest emit, 144 B/quad resident; (b) smallest; (c) tunable |
| Texture bindings | geometry stored once per shape, textures bound by key at emit time | one geometry for all palettes of a tile |
| Enclosures | keep floors, ceilings, void ring and plain walls as opaque `tile_full_*` | section occlusion keeps working for the 14 interior layers |

Which test culls what (F, from `Block#shouldRenderFace`, `Block.java:211-241`): the engine drops a `cullFace`-tagged quad
only when the neighbour `canOcclude()` **and** the emitting state's own face-occlusion shape is covered — so it works for
`tile_full` and `shape_full` (whose `getShape` is a cube) against an opaque cube, never between two non-occluding
carriers, and never for `decor` (empty shape → "draw"). The face-mask test in the model therefore handles shaped ↔ shaped
pairs and treats an opaque `tile_full` neighbour as a full mask.

**Recommendation.** (b)/(c) storage, face masks, generator cap, opaque enclosures; and the budgets below as the pass
thresholds for B2.7. The numbers are **proposed budgets**, to be confirmed or revised by the measurement; the
baseline-relative ones decide.

| Budget | Threshold |
|---|---|
| Quads per shaped block | max ≤ 96 (generator cap), mean ≤ 24 |
| Quads per section (after our face-mask culling, before engine culling) | P95 ≤ 12,000; max ≤ 30,000 (≈ a full-leaves section; under the 32,768-quad initial buffer) |
| Our share of mesh time (`emitBlockQuads`, summed per section) | median ≤ 1.0 ms; P95 ≤ 4 ms |
| Whole section rebuild (Indigo, `SectionCompiler#compile`) | P95 ≤ 2× the cube baseline's P95 measured in the same session, and ≤ 15 ms |
| Frame rate | at render distance 16: ≥ 90 % of the cube baseline; at 32: ≥ 85 % (same scene, same machine) |
| Heap | shape tables ≤ 32 MB; mesh cache ≤ 64 MB (hard cap); client lookup ≤ 16 MB at render distance 32; registry growth ≤ 12 MB per side |
| Atlas | blocks atlas ≤ 4096×4096 with the base (16 px) art; mip level stays 4 |
| Reload / join | resource reload ≤ baseline + 2 s; world join to first full view ≤ baseline + 1 s |
| Build | placement ≥ 40,000 blocks/s (today 49,237: `report → throughput.blocks_per_s`); full build ≤ 90 s (today 56.7 s) |

**Sketch** (the measurement; nothing here was run).

```
Counts (pure Java, on the builder machine, counts only -> build_report.json / shape_report.json):
  for each shape: quads, boundary quads, masks        -> shapes_distinct, shape_quads_max, shape_quads_mean
  for each section touched by the plan:
      q = sum over shaped positions of (quads - boundary quads covered by a shaped neighbour)
      + cube faces of plain tile blocks not facing an opaque cube
  -> quads_total, quads_per_section_{p50,p95,max}, sections, shape_blocks, decor_blocks

Worst case fixture (JUnit): a 16x16x16 section filled with the heaviest canopy shape, and one filled with the
  heaviest prop; assert against the per-section budget; also run on the real plan for the densest forest map
  (report field to add: densest_section{map, quads}).

Client timing (dev-only switch -Dkantobridge.meshstats=true; user runs it, B2.7):
  in emitBlockQuads: t0 = nanoTime(); ...; perThread.add(sectionKey(pos), nanoTime() - t0, quadsEmitted)
  a dev-only mixin at HEAD/RETURN of SectionCompiler#compile records whole-section times (Indigo only)
  every 5 s on the client thread: merge thread-local histograms -> F7 line + local/reports/mesh_report.json
```

**Open.**
- The real numbers need the shape generator (3b). Report fields a future build should add: `blocks_by_kind`,
  `shapes_distinct`, `shape_quads_max`, `shape_quads_mean`, `quads_total`, `quads_per_section_p50/p95/max`,
  `densest_section`, `shape_map_bytes`.
- ⚠ T-junctions: greedy rectangles of different sizes meeting along an edge can show single-pixel sparkles at a
  distance. Vanilla has the same artefact with complex models; judge it in the prototype.

> **Integrator note.** The report field names are unified in [4.11](#411-build-report-additions).

### B2.4 Collision and interaction

**Answer (F: how 1.21.1 decides each thing for non-full shapes).**

| Concern | Decided by | Cached per state? | Evidence |
|---|---|---|---|
| Entity collision | `getCollisionShape(state, level, pos, context)`; default = `getShape` when the block has collision | The empty-context shape is cached per state; entity contexts are evaluated live | `BlockBehaviour.java:352-354`; `BlockStateBase#getCollisionShape (:702-708)`; `Cache (:968)` |
| Crosshair and block outline | `getShape` (`ClipContext.Block.OUTLINE`); the outline box is drawn from `getShape(level, pos, CollisionContext.of(camera entity))` | live | `ClipContext.java:51-57`; `Entity.java:1673`; `LevelRenderer#renderHitOutline (LevelRenderer.java:2012-2027)` |
| Third-person camera clip | `getVisualShape` (default = collision shape) | live | `Camera.java:92`; `BlockBehaviour.java:364-366` |
| Face culling of a quad with `cullFace = d` | `Block.shouldRenderFace(state, level, pos, d, neighbourPos)`: `state.skipRendering(neighbour, d)` → false; else if the neighbour `canOcclude()`: compare `state.getFaceOcclusionShape(d)` with the neighbour's opposite face shape; else draw | **Yes: the result is cached per `(state, neighbourState, direction)` in a thread-local 2,048-entry map** — position is not part of the key | `Block.java:101-106,211-241`; Sodium uses an equivalent cache (S6) |
| "Opaque cube" (light, section visibility, AO darkness) | `isSolidRender` = `canOcclude && occlusionShape is a full cube` | per state | `BlockStateBase#isSolidRender (:677-684)`; `Cache (:954)` |
| Light passing | `getLightBlock`: 15 if solid-render, else 0 if `propagatesSkylightDown`, else 1; `propagatesSkylightDown` default = shape is not a full cube and no fluid | per state | `BlockBehaviour.java:320-326,436-438`; `Cache (:955-956)` |
| Directional light blocking by face shapes | only when `canOcclude && useShapeForLightOcclusion()` (default false) | per state | `mc → LightEngine (LightEngine.java:52-72)`; `BlockBehaviour.java:235-237` |
| `canOcclude` | a **block-level** property (`Properties.noOcclusion()`), the same for all states | — | `BlockBehaviour.java:501,1027,1129-1131` |
| Position-dependent shapes at all | only blocks with `dynamicShape()` skip the per-state cache; such blocks are never "solid" for Cobblemon ground spawns unless `forceSolidOn()` | — | `BlockBehaviour.java:513-543`; `refs/_research/P5_mc_content_testing.md:156-161` |

Three conclusions (I):

1. **Collision, outline and occlusion belong to the carrier family, never to the visual shape.** That is already how the
   world works (`TileBlock.java:38-41`), and it is what keeps per-cell collision independent of art: the model cannot
   change any of them.
2. **Occlusion cannot vary per position**, because culling is cached per state pair. A carrier is either "always an
   opaque cube" or "never occludes".
3. **Geometry that does not fill the cube must not sit in an opaque carrier.** Around a `tile_full_*` block the
   neighbours' faces are culled (holes appear around a thin sign), the cube blocks sky light for whatever is beneath it,
   and its own light is 0, which sloped quads sample when smooth lighting is off (B2.1 (7)).

**Options.**

| Option | How | Trade-off |
|---|---|---|
| A. Everything in today's families | visuals limited to cube faces (per-face textures) | No new blocks; no real shapes |
| B. One more **solid, non-occluding** carrier set + a **non-colliding** carrier (recommended) | `shape_full_NN[hi,lo]`: `getShape` = full cube (same collision and outline as `tile_full`), `noOcclusion()`, `isSuffocating/isViewBlocking` false, `propagatesSkylightDown` true or default; `decor`, `decor_leaves`: `getShape` empty, `noCollission()` | +16 families (4,096 states, ≈ +5 MB per side) for `shape_full`; decor is two single-state blocks |
| C. `dynamicShape()` carriers with lookup-driven shapes | position-dependent collision/outline | Server would need the shape table (art-derived) for collision; breaks "collision per cell"; Cobblemon solidity issue. Rejected |

**Recommendation.** **B**, with these rules for the builder and the model:

- **Collision classes** (the only blocks that collide): `tile_full` (opaque cube), `shape_full` (cube, see-through for
  the engine), `tile_half` (water), plus the unchanged entity-dependent `story_cell`/`story_tile`/`ledge_collider`.
  `decor` blocks never collide and have no outline, so the crosshair passes through canopies and overhangs to the cell behind.
- **Invariant to test:** for every cell, the set of colliding blocks at feet level and feet+1 is the same in a shaped
  build as in today's build (walkable cells empty; non-walkable cells blocked). A 1-block column is enough to block:
  step height is 0.6 and jumping is zeroed (`refs/_research/P5_mc_content_testing.md:150-155`; spec §4 "Rules"; counters
  are 1 high today, `HeightRules.java:22-23`).
- **Which carrier:** a block whose shape fills the whole cube and all of whose visible quads lie on cube faces stays
  `tile_full` (fast culling, sky-light blocking). Anything else that must collide is `shape_full`. Floors, ceilings, the
  void ring and plain walls stay `tile_full` so interiors stay sealed for light and section occlusion.
- **Outline for the crosshair:** keep the cell-sized cube (`getShape` unchanged), so signs and counters stay easy to hit
  and `ActRuntime`'s cell targeting is untouched. If the wireframe around thin art looks wrong, hide or redraw it on the
  client with `WorldRenderEvents.BLOCK_OUTLINE` (`fapi:fabric-rendering-v1 → WorldRenderEvents.java:174,290-291`; returning
  `false` cancels the vanilla outline) — a look decision for the user, no gameplay effect.
- **Ledges:** `LedgeColliderBlock` stays invisible at feet level; the lip is drawn by the *ground* block's shape
  reaching up to 12/16 above its top (quads may leave the 0..1 box by under a block, as vanilla JSON models may:
  −16..32 px, `refs/_research/P5_mc_content_testing.md:125`). Surface ground is at Y 63, the top of a section, so lips
  are meshed by the section below the one they appear in — fine for rendering; note it for frustum-edge cases.
- **Story cells:** closed art needs positions at feet level and above; feet level is the `story_tile` block itself (make
  it a carrier, B2.6), the blocks above get `decor`-class story carriers.
- **AO:** `shape_full` keeps the default shade 0.2 (contact shadows at tree bases); set it to 1.0 like the story blocks
  (`StoryCellBlock.java:82-86`) if the prototype shows dirty edges.

**Sketch.**

```java
// block/ShapeBlock.java (new) — collides and is picked like a full tile, never occludes
public class ShapeBlock extends TileBlock {                      // same HI/LO slot properties
    public ShapeBlock(Properties p) { super(p.noOcclusion().isSuffocating((s, l, pos) -> false)
                                              .isViewBlocking((s, l, pos) -> false), Shapes.block()); }
    @Override protected boolean propagatesSkylightDown(BlockState s, BlockGetter l, BlockPos p) { return true; }
}
// block/DecorBlock.java (new) — visual only; one state (no slot) unless the Distant Horizons test asks for slots
public class DecorBlock extends Block {
    public DecorBlock(Properties p) { super(p.noCollission().noOcclusion()); }                   // no collision
    @Override protected VoxelShape getShape(BlockState s, BlockGetter l, BlockPos pos, CollisionContext c) { return Shapes.empty(); }  // no outline
}
```

**Open.**
- Whether `decor` needs slots (256 states × families) or a handful of states is decided by Distant Horizons'
  `tintWithAvoidedBlocks` behaviour (B2.2) — measure before registering 16 more families.
- ⚠ `noCollission()` also sets `canOcclude = false` (`BlockBehaviour.java:1123-1126`); the explicit `noOcclusion()` is
  redundant but harmless.

### B2.5 Capacity and namespaces

**Answer.**

*The static approach: a state per shape (F + arithmetic).*

- Families needed = ⌈N / 256⌉ per collision class, where N = distinct drawable things (geometry × texture binding).
  Every family is a registered block on **both** sides; the neighbour-table cost measured for today's layout is
  ≈10.1 MB per 8,192 states ≈ **1.23 KB per state** (`docs/PROGRESS.md:42`; the 0..255 single-property layout would be
  ≈12 KB per state, `refs/_research/P5_mc_content_testing.md:95-103`).
- Every state needs its variant in a blockstate file (an unmatched state logs a warning and gets the missing model,
  `mc → BlockStateModelLoader.java:164-170`) and a baked model; today that is 17 blockstate files × 256 variants and
  4,018 model files (B1.1).

| Distinct things N | Families | Registry heap (per side, I) | Generated model files |
|---|---|---|---|
| 4,018 (today: cubes) | 17 used of 32 | ≈10 MB (all 32 registered) | 4,018 |
| 16,384 | 64 | ≈20 MB | 16,384 |
| 65,536 | 256 | ≈81 MB | 65,536 |

  N for a voxel Kanto is not known yet (it needs 3b's generator); the report field to add is `shapes_distinct` and, for
  the static variant, `shape_texture_bindings_distinct`. What is certain: the count is fixed in the registry at
  mod-build time, a second region shares it, and exceeding it is a hard build failure (`slot_map.full`,
  `builder/plan/ArtCatalog.java:132-137`).

*Flexible ids (I).* A shape id is an integer scoped to a region's shape index; it costs nothing in the registry. Several
regions in view just means several tables; the lookup picks the table by position (B6.1).

*Texture atlas facts for 1.21.1 (F).*

| Fact | Evidence |
|---|---|
| The block atlas is defined by `assets/minecraft/atlases/blocks.json`: `directory` sources `block` → prefix `block/`, `item`, `entity/conduit`; three `single` files; one `paletted_permutations` (armour trims) | client jar, `assets/minecraft/atlases/blocks.json` (read with `unzip -p`) |
| Source types: `single`, `directory`, `filter`, `unstitch`, `paletted_permutations` | `mc → SpriteSources (SpriteSources.java:21-25)` |
| `directory` lists `textures/<source>/**.png` **in every namespace**, recursively, the top-most pack's file winning; sprite id = `<namespace>:<prefix><relative path>` | `DirectoryLister#run (DirectoryLister.java:33-39)`; `FileToIdConverter#listMatchingResources (FileToIdConverter.java:30-32)`; `FallbackResourceManager#listResources (:153-176)` |
| Atlas JSON files of all packs are **concatenated** (`getResourceStack`), sources run in order, a later source replaces a sprite id | `SpriteSourceList#load/list (SpriteSourceList.java:39-84)` |
| `paletted_permutations` recolours listed textures by a palette key image; sprites are named `<texture>_<permutation>`; no animation metadata | `PalettedPermutations (PalettedPermutations.java:57-82,136-149)` |
| `unstitch` cuts named regions out of one image | `Unstitcher (Unstitcher.java:52-64,72-83)` |
| Mods can add source types | `fapi:fabric-rendering-v1 → AtlasSourceTypeRegistry#register (AtlasSourceTypeRegistry.java:36)` |
| Maximum size: `RenderSystem.maxSupportedTextureSize()` probes from `max(32768, GL_MAX_TEXTURE_SIZE)` downwards with a proxy texture, never below 1024 | `mc → RenderSystem (RenderSystem.java:515-534)` |
| The stitcher grows in powers of two, alternating width and height, and throws `StitcherException` (a crash report) when a sprite no longer fits `max × max` | `Stitcher#stitch/expand (Stitcher.java:45-54,86-122)`; `SpriteLoader#stitch (SpriteLoader.java:84-98)` |
| Mip level: requested level (option, default and max 4) is lowered to `log2` of the smallest power-of-two factor of any sprite's width/height — for the whole atlas. A 16×16, 32×32 or 64×64 sprite keeps level 4; an 8×8 or 24×24 one lowers everything to 3 | `SpriteLoader.java:50-82`; `mc → Options (Options.java:343-350)` |
| Each sprite's slot is rounded up to a multiple of 2^mip (16 at level 4) | `Stitcher#smallestFittingMinTexel (Stitcher.java:62-64,134-138)` |
| Animated sprites occupy one frame's area; frames are re-uploaded when they change | `SpriteResourceLoader (SpriteResourceLoader.java:44-47)`; `TextureAtlas#cycleAnimationFrames (TextureAtlas.java:122-135)` |
| The result is logged as `Created: WxHxMIP minecraft:textures/atlas/blocks.png-atlas` | `TextureAtlas.java:54` |
| Sprite objects are replaced on every reload; `getSprite` returns the missing sprite for unknown ids | `TextureAtlas.java:60,139-140` |
| A folder pack is listed by walking its tree (`Files.find`) at every reload | `mc → PathPackResources#listPath (PathPackResources.java:79-91)` |
| Resource paths allow only `[a-z0-9/._-]`, namespaces `[a-z0-9_.-]` | `mc → ResourceLocation#validPathChar (ResourceLocation.java:284-286)` |

*Sprite-count practicalities (I).* Texels are not the limit: 4,005 sprites at 16 px are 1.0 M texels; even 100,000 would
be 25.6 M, an 8192×4096 atlas. A player pack at 64 px per tile multiplies area by 16 (4,005 → 16.4 M texels); at 128 px
by 64 (65.6 M, more than 8192² = 67 M once vanilla and Cobblemon are added → needs a 16,384 atlas and ≈350 MB of VRAM
with mips). The practical limits are (1) **file count** — every tile is a PNG that is walked, opened and decoded per
reload, and written by the generator; (2) a stitch failure is a **crash**, so anything that can enlarge sprites (player
packs) must be validated before a reload is triggered.

*Do per-region namespaces or paths work with pack stacking? (F)* Yes. The `directory` source spans namespaces and
sub-folders, and file priority is per path, so `kantobridge:block/<set>/tile/<atlas>/<palette>/<tile>` (path prefix) and
`<other_namespace>:block/…` (namespace) both stitch automatically, and a player pack placed above overrides any single
file by repeating its path. `@` is not allowed in paths, so a region id such as `kanto@blue` needs a sanitised form.

**Options** (namespace scheme).

| Scheme | Example | Notes |
|---|---|---|
| N1 today | `kantobridge:block/tile/tilesets.overworld/pallet/0044` | collides across games |
| N2 path prefix per **art set** (recommended) | `kantobridge:block/blue/tile/tilesets.overworld/pallet/0044`, `kantobridge:textures/npc/blue/pallet/sprite_oak.png` | one namespace; the set is the *game export the art came from*, not the region: two regions of one game share it, and a Red guest in a Blue world has `red/` |
| N3 namespace per art set | `kb_blue:block/tile/…` | equal power; more `assets/<ns>/` roots, nothing gained |
| N4 path prefix per region | `kantobridge:block/kanto_blue/…` | duplicates art when one game supplies two regions |

**Options** (how base textures enter the atlas).

| | How | For | Against |
|---|---|---|---|
| T1 files per tile (today) | PNG per `(set, atlas, palette, tile)` in the generated pack | simplest; player packs override one file; doubles as the template export for pack makers (3b's B4.6) | thousands of files per game |
| T2 `paletted_permutations` | one greyscale PNG per `(atlas, tile)` + a palette image per SGB palette, declared in a generated `atlases/blocks.json` | fewer files (one per (atlas, tile) instead of one per (atlas, palette, tile); the ratio is not in the reports); a pack can restyle a palette | sprite ids become `<tile>_<palette>`; no animated sprites (water, flowers stay files); permutations are all-pairs per source, so sources must be grouped per used (atlas, palette) set |
| T3 custom source type | `AtlasSourceTypeRegistry.register("kantobridge:export_art", …)`; sprites are produced in memory from the export bundle | no generated texture files at all | player packs can only override if the source checks for a file first; no template on disk; more code in a reload path |

**Recommendation.** N2 + T1 now (it is today's pipeline plus one path segment); keep T2/T3 as later optimisations if
file count hurts reload time in the B2.7 numbers. Capacity: stop spending slots on anything but base tiles — shapes and
extra textures (darkened ceilings, folded facades) are referenced by key from the shape table, never through a slot.
Keep `FAMILIES_PER_SHAPE` a single constant that can be raised when a second region arrives (new block ids never
invalidate old worlds); the slot-map schema's `family ≤ 15` (`protocol/world/slot_map.schema.json:22`) and
`SlotMap.FAMILIES_PER_SHAPE` (`world/SlotMap.java:34`) move with it.

**Sketch.** A proposed pack check before any reload that includes player packs (pure Java, client):

```
area = sum over sprites of ceil16(w) * ceil16(h)            // slot rounding at mip 4
if any sprite has min(lowestOneBit(w), lowestOneBit(h)) < 16 -> warn "lowers mip level"
if area > 0.5 * max * max                                    -> refuse the pack (hint: use <= 64 px tiles)
```

**Open.**
- `shapes_distinct` (report field) decides how bad the static variant would be; unknown until 3b's generator exists.
- ⚠ Not measured: reload time as a function of file count; stitch time for 10⁴–10⁵ sprites.

### B2.6 Per-player story art with shapes

**Answer.** Today (B1.3): a block-entity renderer redraws the closed art every frame through
`renderSingleBlock` → `getQuads`, lit by one light sample, without AO, without neighbour culling. Nothing is re-meshed;
the world is identical for everyone; each client decides from its own hidden game.

Facts that constrain the choice:

- `renderSingleBlock` never reaches `emitBlockQuads` (B2.1 (3)); a shape must be turned into vertices by us in a
  block-entity renderer.
- A chunk mesh can only contain quads emitted at a position that holds a `RenderShape.MODEL` block; `story_tile` is
  `INVISIBLE` (`block/StoryCellBlock.java:46-49`) and the blocks above it are air.
- Emitting quads far outside the emitting block's cube is unsafe (I): a section's mesh is drawn or skipped by the
  section's own bounds, and vanilla models stay within one block of their cube. Closed art is up to 8 blocks tall
  (`world/StoryCellTable.java:35`).
- A re-mesh is one `setSectionDirty` per touched section and is asynchronous by default (B2.1 (6)).

**Options.**

| | S-A keep the block-entity renderer | S-B mesh it (recommended) |
|---|---|---|
| How closed art is drawn | `StoryCellRenderer` walks the closed shape's quads into the entity buffers each frame | the carrier at each position of the closed column emits the closed shape's quads in `emitBlockQuads` when the local player's snapshot has the cell closed; nothing otherwise |
| World change | none | `story_tile` becomes `RenderShape.MODEL` (same block, same collision); the builder places `decor`-class carriers in the air positions of every tile cell's closed column (≤ 162 cells × 4 columns × ≤ 7 blocks) |
| Open for another player | not drawn | not emitted in *their* mesh |
| Update | next frame | client thread: diff of `drawn` → `setSectionDirty` for the sections of the changed cells; a few frames of latency by default, same-frame with the "player affected" option |
| Lighting / AO / shaders | one light value per block entity, no AO; shader packs treat it as a block entity | identical to terrain |
| Per-frame cost | every visible closed cell, every frame | none |
| Distant Horizons | absent from LODs | absent from LODs (correct: per player) |
| Multiplayer | each client draws its own | each client meshes its own; the server and other clients are unaffected |
| Risk | lighting mismatch against meshed neighbours (already a play-test note, `docs/PROGRESS.md:545`) | visual lag of a few frames behind collision after a change; more carriers |

**Recommendation.** **S-B**, introduced after the flexible model exists, with S-A kept until then (it keeps working
unchanged through `getQuads`). Rules that keep it right in multiplayer:

1. The model reads only an immutable **story snapshot of the local player** (`RenderSnapshot.story()`: the `drawn` key
   set), published by `ClientStoryView.tick` whenever `StoryRules.cells().drawn()` changes
   (`client/story/ClientStoryView.java:117-118`). No other player's state exists on the client.
2. Collision stays where it is: `StoryStates.blocks(entity, key, type)` from the synced `active` set
   (`block/StoryCellBlock.java:67-80`); drawing never affects it.
3. The cell of a position comes from the region's index (`WorldIndex.toCell`, `world/WorldIndex.java:141-149`) or, for the
   `story_tile` block itself, from block-entity render data (`StoryCellBlockEntity` would implement `getRenderData()`
   returning its immutable key string) — the lookup is preferred because it also serves the carriers above.
4. `ClosedArtPick` keeps working from the WorldIndex (`client/act/ClosedArtPick.java:43-63`); `closed.height` stays in the
   story table or is derived from the closed shapes' bounding boxes.

What the region data must carry per tile cell (ask to Track 2/3b): today `closed{block, pass, height, art[4 slots]}`;
with shapes `closed.shapes`: for each of the 4 block columns, the shape ids bottom-up from feet level (0 = nothing). The
flat case (`height == 0`: closed art is walkable, only the picture differs) becomes a 1-voxel-thin shape at feet level
(today's 0.002 lift, `client/story/StoryCellRenderer.java:28-31`).

**Sketch.**

```java
// ClientStoryView.tick (client thread), after cells = rules.cells():
Set<String> now = cells.drawn();
if (!now.equals(published)) {
    Set<String> changed = symmetricDifference(published, now);
    RenderSnapshot.publishStory(now);                         // volatile swap of an immutable set
    for (String key : changed) {
        StoryCellTable.Entry e = index.storyCells().get(key);
        BlockPos o = index.cellOrigin(e.cell().map(), e.cell().x(), e.cell().y());     // WorldIndex.java:165-171
        int h = Math.max(1, closedHeight(e));
        // 2x2 blocks, feet .. feet+h-1; setBlocksDirty grows the box by one block and marks every section it touches
        minecraft.levelRenderer.setBlocksDirty(o.getX(), o.getY(), o.getZ(), o.getX() + 1, o.getY() + h - 1, o.getZ() + 1);
    }
    published = now;
}
```

**Open.**
- Whether a few frames between "collision gone" and "tree gone" is visible in play; if it is, call
  `levelRenderer.blockChanged(level, pos, state, state, Block.UPDATE_IMMEDIATE)` instead (player-flagged sections).
- Under Sodium, rebuilds flagged "important" are also prioritised (`LevelRendererMixin`, S4); ⚠ latency not measured.

> **Integrator note.** Order agreed with [B3.7](#b37-story-cells-and-swap-sites-open-and-closed-shapes): the block-entity renderer stays (drawing shape quads once shapes exist) until migration step 4, then the closed shapes are meshed.

### B2.7 Prototype design [measure]

**Answer.** Nothing was built or run. The design below separates what an agent can produce headlessly (counts, build
times) from what only a client run can show (frame rate, mesh time), which is the user's to run (CLAUDE.md "Testing").

**What to build** (on a throw-away branch, with the user's approval).

| Variant | World | Client |
|---|---|---|
| **A** baseline | today's build | today's generated JSON |
| **A′** control | same world as A | runtime model emitting the same cubes (isolates the cost of the FRAPI path itself) |
| **B** static shapes | carriers = one state per shape, from prototype-only families `proto_shape_NN[hi,lo]` | generated JSON element models per state |
| **C** flexible shapes | `shape_full` / `decor` carriers + shape map | runtime model + lookup + face masks |

Shapes for B and C come from one small, hard-coded prototype generator (trees as trunk + canopy, measured roof heights
with one slope style, signs, fences, ledge lips) so both draw the same geometry. Scenes, built with the existing
headless path (`tools/e2e/build_world.py --maps …`, into their own `saves/KantoProto*` folders):

1. **Pallet** — the M2 set (5 maps, 20 chunks: `docs/PROGRESS.md:65-68`).
2. **Forest** — the map whose plan has the densest section (new report field `densest_section`), with its neighbours.
3. **All Kanto** — 220 maps, for the distance views.

**Metrics and how each is taken.**

| Metric | How | Who |
|---|---|---|
| Quads total / per section / per block; shapes; bytes of shape map and tables | pure Java counters → `local/reports/shape_report.json` (counts only) | agent, headless |
| Build time (`plan_ms`, `place_ms`, new `shape_ms`), blocks placed, throughput | `build_report.json` | agent, headless |
| Pack: files, bytes, generation ms | `PackGenerator` log line (`client/packs/PackGenerator.java:54-57`) → report | agent (JUnit on the synthetic bundle; real bundle through the e2e build) |
| Registry heap | the existing measuring test pattern (`src/test/java/dev/kantobridge/block/TileBlockTest.java`, `stateMemoryStaysSmall`) | agent, JUnit |
| Resource reload time; atlas size and mip | timestamps around `PackReload.reload`; log line `Created: WxHxMIP …blocks.png-atlas` | user, client |
| Our share of mesh time | `-Dkantobridge.meshstats=true`: nanoTime inside `emitBlockQuads`, per thread, keyed by section; quads emitted | user, client (works on Indigo and Sodium) |
| Whole-section rebuild time | dev-only mixin at HEAD/RETURN of `SectionCompiler#compile` (Indigo) | user, client |
| Full re-mesh time | call `levelRenderer.allChanged()`, time until `levelRenderer.hasRenderedAllSections()` and the dispatcher's queue is empty (`mc → LevelRenderer.java:3032`, `SectionRenderDispatcher#getToBatchCount (:148-150)`); 3 repeats | user, client |
| Frame rate | fixed spectator viewpoints (`/tp` coordinates in the checklist), vsync off, unlimited fps, 30 s each after the queue is empty: mean and 1 % low from per-frame deltas | user, client; render distance 16 and 32 |
| Heap | `totalMemory − freeMemory` after two `System.gc()` at the idle point | user, client |

All client numbers go to `local/reports/mesh_report.json` (numbers only) and a line on F7.

**Run matrix.** {A, A′, B, C} × {Pallet, Forest} × {Indigo; Sodium if the user allows a dev-only pin} × {render distance
16, 32}; All-Kanto only for A′ and C. Three runs each, medians reported.

**Thresholds that decide.**

| Question | Pass |
|---|---|
| Is the FRAPI path itself affordable? | A′ vs A: full re-mesh time ≤ 1.25×, frame rate ≥ 97 % |
| Is flexible competitive with static for the same shapes? | C vs B: full re-mesh time ≤ 1.5×; frame rate ≥ 95 %; heap ≤ B + 32 MB; reload time ≤ B |
| Are shapes affordable at all? | C vs A: the B2.3 budgets (frame rate ≥ 90 % at 16, ≥ 85 % at 32; section P95 ≤ 2× and ≤ 15 ms; quads within budget) |
| Capacity | B needs `shapes_distinct` ≤ registered states for **all** of Kanto; C has no limit |

**Decision rule.** Choose **C** if it passes rows 1–3. Choose **B** only if C fails row 2 by more than 25 % *and* the
capacity row holds with headroom for a second region. If both fail row 3, reduce the generator's detail (cap per block)
and re-measure before changing the architecture.

**Options.** A cheaper first step is to build only A′ (no shapes, no world change): it validates the model-loading
plugin, the slot → sprite path, thread safety and both renderers in a day's work, and it is also migration step 1 of
B2.8.

**Recommendation.** Do A′ first, then B and C on Pallet and Forest.

**Sketch.** PLAYTEST items for the run (for the user):

```
1. Start the dev client with -Dkantobridge.meshstats=true, open saves/KantoProtoC, F7 on.
2. /gamemode spectator; /tp to each listed viewpoint; wait for "mesh queue 0" on F7; stand 30 s.
3. Press the stats key: writes local/reports/mesh_report.json; note the fps lines.
4. Repeat at render distance 16 and 32; repeat for variants A, A', B.
```

**Open.** The user must approve: the throw-away branch, a dev-only Sodium pin for the measurement, and the play-test
time. Viewpoint coordinates can only be chosen after the build (they come from the WorldIndex placements).

### B2.8 Recommendation and migration

**Answer / Recommendation.** The hybrid of B2.1 (O4) and B2.4 (B):

| Block | States | Collision / outline | Occludes | Role | Change |
|---|---|---|---|---|---|
| `tile_full_00..15[hi,lo]` | 4,096 | cube | yes, opaque | ground, floors, ceilings, void ring, plain walls; cubes with per-face art | none on the server |
| `tile_half_00..15[hi,lo]` | 4,096 | lower half | partly (not solid-render) | water | none |
| `shape_full_00..15[hi,lo]` | 4,096 | cube | no | colliding shaped art; the slot is the block's top tile (fallback cube, LOD colour); **same slot numbers as `tile_full`** | new |
| `decor`, `decor_leaves` | 1 each | none / none | no | visual-only geometry in otherwise-air positions; the leaves variant exists so shader packs and LOD mods can treat foliage as foliage | new |
| `story_cell`, `ledge_collider` | 1, 4 | entity-dependent / none | no | unchanged | none |
| `story_tile` | 1 | entity-dependent / none | no | also the feet-level carrier of closed story art | `RenderShape.MODEL` instead of `INVISIBLE` |

One runtime model class serves every carrier. The state gives the carrier kind and the base slot; the client's region
lookup gives the shape id; the client's shape table gives geometry and texture keys. Without a lookup entry the model
draws today's cube, so every stage of the migration is shippable.

**What stays on the server (world-scoped, no art):** which carrier stands where (collision class + base slot), story
blocks and their block entities, ledge colliders, light and habitat blocks, the WorldIndex, the slot map, the shape index
and shape map (ids only), all relay logic and collision rules. **What becomes client-only:** geometry, texture binding,
meshes, face-mask culling, the story overlay's drawing, outline styling, texture-pack overrides. The server never loads
a model, a sprite or a shape table.

**Migration path.** Each step ends green on `tools/check` and leaves existing worlds playable.

| Step | What changes | World rebuild? | Files |
|---|---|---|---|
| **1. Runtime model, same cubes** (variant A′) | New `client/model/` package: `KbModelPlugin` (registered from `client/KantoBridgeClient.java:27-64`), `TileUnbakedModel`, `TileBakedModel`, `RenderSnapshot`, `CubeMeshes`. `RenderSnapshot` is rebuilt when `ClientWorldIndex` receives `world.slots` (`client/world/ClientWorldIndex.java:79-99`) and on `InvalidateRenderStateCallback`; then `levelRenderer.allChanged()`. `ResourcePackWriter` stops writing blockstates and models (`builder/pack/ResourcePackWriter.java:146-182`) and marks the pack layout in `kantobridge.json` (`:228-233`); `PackInfo` reads it (`client/packs/PackInfo.java:38-59`). `TextureSync.decide` no longer compares slot-map hashes: a pack fits when it holds the art set for the world's game and no key of the world's slot map is missing (`client/packs/TextureSync.java:141-156`, `TextureSyncRuntime.java:132-141`); `GeneratedPack.suppressed` and the hold-back path go (`GeneratedPack.java:24-28,69-87`; `TextureSync.java:296-302`). `KbBlocks` gains accessors for kind and family. The jar's placeholder blockstates/models can stay until step 5 | no | client + `ResourcePackWriter`; tests: `PackWriterTest`, `PackGeneratorTest`, `HostSlotPackTest`, `TextureSyncTest`, `GeneratedPackTest` |
| **2. Shape plumbing, cubes with per-face art** | Builder writes a shape index and shape map (ids only) beside `slot_map.json` (`builder/BuildTask.java:260-263`); `WorldIndexSync` sends them as `world.shapes` parts after `world.slots` (`server/WorldIndexSync.java:183-207`); new schema `protocol/messages/world.shapes.schema.json`, catalog regenerated; client `ShapeMap` + lookup; shapes of kind "cube with six texture keys" only, so every block is still an opaque cube in `tile_full` | no block changes (the map is derived from the plan; re-running the build on an existing world only adds the data files, because identical blocks are left alone, `builder/place/BlockPlacer.java:39-41,245-255`; a world without a shape map keeps the cube look) | `builder/plan/*` (3b's generator), `server/WorldIndexSync.java`, `protocol/`, `client/region/*` |
| **3. Real shapes** | Register `shape_full_*`, `decor`, `decor_leaves` (`block/KbBlocks.java:47-64`); `BlockCode.KIND_SHAPE`, `KIND_DECOR` (`builder/plan/BlockCode.java:13-22`), `CodeStates` (`builder/place/CodeStates.java:17-28`); `WorldPlan.renderMapColumn` chooses carriers from the shape plan (`builder/plan/WorldPlan.java:429-464`); face masks; budgets enforced by the generator; report fields | **yes** (the builder is re-runnable and clears its volumes: `builder/place/BlockPlacer.java:39-41`; the user's world was rebuilt in place at M6–M8, `docs/PROGRESS.md:99-100,109-110`) | common + builder + client |
| **4. Story art in the mesh** | `StoryTileBlock#getRenderShape` → `MODEL`; story carriers above tile cells; `closed.shapes` in the story table; `ClientStoryView` publishes the story snapshot and marks sections dirty; `StoryCellRenderer` and its registration removed (`client/KantoBridgeClient.java:39`) | yes (carriers) | `block/StoryTileBlock.java`, `world/StoryCellTable.java`, `builder/plan/StoryCellPlanner.java`, `client/story/*` |
| **5. Clean-up and regions** | Remove the jar's placeholder blockstates/models; art-set path prefix; region-scoped tables (B6) | no | resources, `ResourcePackWriter`, client |

**Why this order.** Step 1 has no visual change and no server change, removes ≈4,000 generated files, makes packs
world-independent (so M9's "re-export and rebuild against the host's slots" flow shrinks to a re-mesh) and proves the
pipeline on both renderers. Step 2 delivers the first visible gain (facades and roof tops differ) without touching
collision or rebuilding worlds. Only step 3 needs new blocks and a rebuild.

**Invariants to keep (each has a test in the tests note after B8.2).**

1. For every cell, the colliding blocks at feet level and feet+1 are the same before and after (B2.4).
2. Floors, ceilings, the void ring and the interior rings stay `tile_full` (light, section occlusion, DH solidity).
3. A world without a shape map, or a client without the shape table, shows today's cubes; never missing geometry.
4. Art never travels: `world.shapes` carries ids; the shape table and textures are generated from the player's own
   export.
5. The model reads only immutable snapshots; no client-thread object is touched from `emitBlockQuads`.
6. No Indigo class is referenced; only `net.fabricmc.fabric.api.renderer.v1.*`.

**Options (rejected alternatives, and what would revive them).**

| Alternative | Rejected because | Would be chosen if |
|---|---|---|
| Pure static (more families, JSON element models) | shared fixed capacity; ≈1.23 KB/state on both sides; tens of thousands of generated files; a pack is tied to one slot map | the prototype shows the flexible path failing the mesh/frame thresholds by > 25 % and `shapes_distinct` is small |
| One stateless flexible block + side table | LOD colours, shader ids and the fallback all need the state | the user rules out LOD mods and shader packs |
| Block entities with render data | cost table in B2.1 | only for a handful of special objects |
| A custom world renderer (own vertex buffers from `WorldRenderEvents`) | bypasses lighting, culling, Sodium/Iris/DH integration | never for terrain |

**Version risk** (what a Minecraft or Fabric update can break).

| Touch point | Kind | Risk |
|---|---|---|
| `ModelLoadingPlugin`, `BlockStateResolver` (model-loading API v1) | Fabric API | The model pipeline was rewritten in Minecraft 1.21.4/1.21.5 and FRAPI with it (⚠ from memory, not checked here); on a version bump the plugin and model classes are a rewrite, the data design is not |
| `FabricBakedModel#emitBlockQuads`, `QuadEmitter`, `RenderMaterial` | Fabric API | same |
| `LevelRenderer#setSectionDirty/setBlocksDirty/allChanged`, `InvalidateRenderStateCallback` | vanilla public methods + Fabric event | low; Sodium overwrites them compatibly today |
| `TextureAtlas#getSprite`, `ModelManager#getAtlas` | vanilla | low |
| Optional dev-only mixin on `SectionCompiler#compile` for timing | mixin | dev builds only |
| No new production mixin is needed for the block approach | | |

**Sketch.** The builder's carrier choice for one planned block (server side, ids only):

```
carrierFor(block b):                      // b = position in a map column with its planned shape s (or none)
  if b is water                      -> tile_half[slot(b)]
  if s == none or s.isOpaqueCube     -> tile_full[slot(b)]         // per-face art is fine: all quads on cube faces
  if b.cell collides at this height  -> shape_full[slot(b.topTile)]
  else                               -> s.isFoliage ? decor_leaves : decor
shape map entry for b               -> s.id (0 when none)
```

**Open.**
- The decision itself is the user's ("the block approach, after the prototype", `docs/NEXT_STAGES.md:372-379`).
- Whether `decor` needs slot-bearing states depends on the Distant Horizons experiment (B2.2, B2.4).
- **Positions that are already taken.** A block position holds one state, and some positions a shape may want are
  occupied today: feet level over every story cell (water 9,301, boulder areas 2,438, objects 765, tiles 162 cells:
  `report → story_cells_by_type`) and over every ledge cell (3,032 colliders); feet+2 on a 4-block grid in lit interiors
  (`minecraft:light`, `builder/plan/WorldPlan.java:458-462`). Rules for the builder: art over those cells is drawn by the
  block below reaching up (under one block), or the occupying block becomes a carrier itself (`story_tile` does in step
  4; `story_cell` and `ledge_collider` could the same way); a carrier that needs a light block's position wins and the
  light moves to a free neighbour. 3b's generator needs this list as a constraint.

---

**B3. The shape generator**

### B3.1 Dramaless Shape's geometry: inventory of archetypes and rules (ideas only)

**Answer (facts).** Read: `refs/DRAMALESS_SHAPE-main/lib/TileShape.lua`, `Structures.lua`, `Buildings.lua`,
`ChunkMesher.lua`, `Water.lua`, `SpriteBillboards.lua`, `VoxelCompanionHost.lua` (parts), the header
comments of `data/voxel_heights.lua`, `assets/docs/buidling_to_voxel/sprite_to_voxel_methodology.md`,
`README.md`. Everything below is described in my own words; no code or table value is reproduced. Its world
units are Game Boy pixels (one art texel = one voxel); a cell is 16 px. At our scale one Game Boy pixel is
2/16 of a block (`docs/PLAN.md:199-206`, `world/Scale.java:13-19`), so every rule below lands exactly on
Minecraft's 1/16 grid.

The file `data/voxel_heights.lua` (described structurally only) is a "profile" with: a format version; a
class → height table; per tileset id, lists of tile ids per class ("pins"), optional per-tileset height
overrides, conditional pins that depend on the tile drawn directly above or below, a prop → ground-tile map,
a per-tile list of shades to treat as background, hand-drawn pixel masks for figures painted into furniture
and for objects painted into wall bands, a few tuning scalars, and semantic tags for the companion API; and,
per tileset id, a list of building templates (an exact tile-id grid plus a short "band table" saying how many
top rows are roof and how the roof rows are laid in depth). Its header states it is hand-authored and not
extracted from a ROM (`refs/DRAMALESS_SHAPE-main/data/voxel_heights.lua:1-9, 99-135, 2913-2964`); our earlier
research notes that it nevertheless consists of tile-index grids of the original tilesets
(`refs/_research/D_voxel_pokered.md:19`).

| # | Archetype | Input it needs | How shape and height are decided | Hand-made table, or derivable from art/data? | Cite |
|---|---|---|---|---|---|
| 1 | **Tile classes** (the classifier) | Tileset record (walkable list, water tiles, grass tile, animated tiles), per-cell collision, the profile | First match per tile: (1) a profile pin; (2) the tileset's grass tile → grass; (3) a frame-animated tile → flower; then **per position**: (4) conditional pin on the tile above/below; (5) a water cell → water, a walkable cell → ground (cell granularity, because collision is judged by the cell's bottom-left tile; the other three tiles carry no collision meaning); (6) tile fallback: water set → water, walkable list → ground, otherwise wall. Every class has a height and an "art mode" (flat; art on top; art folded upright on the south face; round hull; standing cut-out; fence post; grass tufts; flower; relief; bookcase; stair) | Steps 2, 3, 5, 6 are **derivable** from data we already export (tile classes and cell flags, table 2.2). Steps 1 and 4 and all class heights are the **hand-made table** | `lib/TileShape.lua:1-39, 122-209, 277-330, 385-507` |
| 2 | **Void tiles** | Atlas pixels | A tile whose art is entirely black or transparent never extrudes, whatever its class | **Derivable** (pixel scan; our M4) | `lib/Structures.lua:102-127, 205-208` |
| 3 | **Structures: connected regions** | Class grid of the map body plus a ring of border blocks (the ring uses the block the 2D renderer draws there, not the map's own border block) | 4-connected flood fill over tiles that are "upright and not pinned". Each region is then modelled as objects and/or a volume. Tree-wall ring cells beyond a short distance are not modelled at all | **Derivable** (needs our M6 for the ring) | `lib/Structures.lua:1-42, 137-212, 274-279, 323-369` |
| 4 | **Background-pixel detection** | Region art + which neighbouring tiles are flat ground | An image of the region's bounding box plus a 1-px apron. Lightest-shade (or transparent) pixels are background *candidates*; flat-ground neighbours and, outdoors, only the **south** apron row are "air" seeds (indoors all four sides); a 4-connected flood from the seeds through candidates marks the real background. White sealed behind dark trim (window frames, wall stripes) is not reached and stays part of the object | **Derivable** from art + map. One escape hatch is hand-made: a per-tile "which shades are background" override for props whose body touches their own bounding box | `lib/Structures.lua:2334-2365, 2366-2416, 2430-2523`; `lib/TileShape.lua:672-722` |
| 5 | **Objects (props)**: signs, plants, mailboxes, lone posts | Result of 4 | A tile with at least a threshold share of background is "sprite-like"; 4-connected sprite-like tiles form a cluster. A cluster becomes an object only if it is short enough, meets flat ground to its south (any side indoors), does not repeat vertically (a tree-wall edge is scenery), and really has some background. Each solid pixel becomes a voxel column of a small fixed depth; 8-connected pixel components each stand on their own feet in the depth band of the tile row their lowest pixel is drawn in. The ground under the object is the commonest flat neighbour tile | **Derivable.** Thresholds and the depth are design constants (ours to choose). Hand-made parts: forced "billboard / prop / cutout / post / signpost / bike / console" pins with a thickness per pool, and the prop → ground map | `lib/Structures.lua:66-83, 2525-2583, 2585-2665, 2667-2745, 489-523` |
| 6 | **Volumes: measured heights** (walls, facades, fences, tree lines, cliff mounds) | Tile ids along each tile column of the region | Per contiguous vertical run in a column: the drawn unit is the run length (capped), unless the front tile recurs further north (then the repeat period, at least two rows), or the two rows above the front are identical (a repeat wearing a one-row trim). Height = unit × one tile. The region then votes a modal height: a repeat-read column adopts a taller region height; a doorway column takes the region height outright. No pixels are used | **Derivable** (tile ids only). We already re-implemented the run rule: `builder/plan/HeightRules.java:35-104`; the region vote, the trim rule and door adoption are not implemented | `lib/Structures.lua:2140-2227, 2264-2285`; our `docs/REFERENCES.md:422-427` |
| 7 | **Volumes: roofs** | Runs from 6, outdoor flag | Outdoors, the top (at most two) rows of a non-repeat column become a pitched roof, unless the top two rows are the same tile (a flat rooftop). The mesher builds a gable: rising from the south eave to a ridge over the middle of the footprint and falling to the north; exposed east/west flanks are hipped. A "rim over a uniform body" (cliff mounds) lays its rim row once instead of cycling it | **Derivable** (tile ids) | `lib/Structures.lua:2229-2263, 2286-2316`; `lib/ChunkMesher.lua:68-86, 545-559` |
| 8 | **Volume faces (the fold rule)** | Runs, atlas | The south face shows the artwork "folded upright", one tile row per 8-px band, band *k* sampling the map row *k* tiles north of the front; the top wears the structure's top rows; side faces are tiled per band and cropped at partial bands, never stretched | **Derivable** | `lib/ChunkMesher.lua:1-25`; methodology "Core principle" (`assets/docs/buidling_to_voxel/sprite_to_voxel_methodology.md:9-28`) |
| 9 | **Door fold** | Door tiles of the tileset, class grid | A door cell is walkable, which would punch a hole in the facade and let the background flood into the building. If the tile north of a door cell is upright, the door cell's tiles are treated as wall so the door art stands in the facade. Only the tileset's door tiles; interior stair/mat warps stay flat; a pin wins | **Derivable** (we have `CellFlags.DOOR`) | `lib/Structures.lua:233-272` |
| 10 | **Buildings: band classification** | A template (tile grid + band table), atlas pixels | Match the exact tile grid on the map (first template to claim cells wins). Composite the drawing; flood the silhouette in from the border through *light* pixels only (black outline and dark shading together are the boundary). Measure: the topmost drawn row of each column is the roof's elevation profile (a drawn taper is the slope); "panes" are small non-black regions sealed behind black frames. Build: facade rows extrude straight back over the footprint depth; an awning band juts out; panes sink one voxel; the roof lays its top-facing rows flat, cycling a middle run of rows to fill the depth, stepping down the taper at the ends, overwriting the walls it meets. Emit: cull to the shell, merge texel-adjacent faces. One model per template, stamped at each placement; a building straddling a map boundary is composited from both maps by template fields | **Split.** The grid match and the band table (which rows are roof, how they map to depth, eave and slab sizes) are the **hand-made table**. Silhouette, taper, eave height and panes are **measured from art**. The five written cues for bands (plain coursed top band; constant-rate taper; frames in a band; underlined full-width band over an inset band; dark runs beside a facade) are documented method, not table | `lib/Buildings.lua:1-47, 1198-1267, 1269-1347`; `data/voxel_heights.lua:2913-2964`; methodology stages 1–5 (`sprite_to_voxel_methodology.md:32-153`) and "What shipping it settled" (`:182-216`) |
| 11 | **Ledges** | Ledge tiles | A low box with the art on its top face; the short side faces show the bottom of the lip drawing (cropped art) | In Dramaless the ledge tiles are **pins** (hand-made). For us **derivable**: ledge cells and their direction are exported (`CellFlags.LEDGE`, `gen1-bridge/export/kb_cells.lua:26-34`) | `lib/TileShape.lua:139-140`; `lib/ChunkMesher.lua:7-9`; `data/voxel_heights.lua:37-40` |
| 12 | **Water and shore** | Water cells | Water is a flat class recessed slightly below ground, so every shoreline shows a lip; the ground next to water emits the short below-ground side bands from its own art. The water surface is taken out of the terrain mesh and drawn in its own pass with sky / screen-space reflections and a shader height field. "Shore" is not a geometry class: a land cell beside water is merely tagged | Geometry **derivable** (water flag). The reflection pass is a renderer effect with no Minecraft equivalent in our scope | `data/voxel_heights.lua:36`; `lib/ChunkMesher.lua:268-284, 507-538`; `lib/Water.lua:1-74`; `lib/VoxelCompanionHost.lua:799-805` |
| 13 | **Trees: round hull** | A pinned 16×16 cell (four tiles), atlas pixels, the map's ground tiles | Mask = the darkest-shade outline plus everything it encloses (flood from the cell border through non-black pixels; if almost nothing is enclosed, as with dithered tree walls, flood only through the light shades). Each mask row is a disc: its span gives a centre and half-width and every pixel column runs that circle's chord in depth, in whole voxels; rows under the mask repeat the lowest discs down to the ground. Front and back wear the drawing per pixel (back mirrored), sides take their column's own texel, the cap keeps the outline only on its rim. One hull per distinct art signature, stamped per cell | Which cells are round is the **hand-made table**. The hull itself is **derived from art** | `lib/Structures.lua:529-570, 604-690, 1268-1290` |
| 14 | **Canopies and variants** | As 13, larger canvases | A 2×2-cell drawing carved as one double-size hull (forest trees); a hull with a cut top (stumps); a hollow tapered hull (bins); a drawing two cells tall on one cell of plot whose leafy top stands as a thin slab while only the pot revolves (potted plants) | Pins and a few tuning numbers are **hand-made**; geometry **derived** | `lib/TileShape.lua:65-88`; `lib/Structures.lua:574-603` |
| 15 | **Cliffs / mountains, and map seams** | Class grid; connections | (a) Cliff mounds are ordinary volumes with the rim-over-body and trim-foot rules (rows 6–7); two-course masonry is a pinned class at double wall height; a cave mouth is a door folded into its mound. (b) Seams: the ring around the current map is suppressed wherever a connected neighbour's body lies, and a building's overhang past its own map edge is kept. (c) The companion API tags "mountain" cells: seeded by pinned rock tiles, grown at most a couple of cells through solid wall/cliff cells, never inside the two-cell band along a connected edge, never near roofs or (for non-seeds) doors | (a) **derivable** except the pinned class; (b) irrelevant for us in its form (our surface maps share one world grid); (c) seeds are **hand-made** | `lib/Structures.lua:2185-2199, 2229-2246, 2270-2281`; `lib/ChunkMesher.lua:265-279, 459-483`; `lib/Buildings.lua:1338-1345`; `lib/VoxelCompanionHost.lua:597-684`; `docs/kfp-companion-integration-report.md:115-136` |
| 16 | **Interior furniture** | Pins | Beds and couch backs: low boxes with art on top. Tables, desks, counters: upright boxes at their own heights (a counter is half a cell: one band stands, the rest rides the top). Stools, TVs, plants, consoles, bicycles: standing cut-outs in pools with different thickness. A top-down drawing (a console on the floor) extrudes a few voxels with art on top ("relief"). Bookcases: a tall drawing collapsed onto a one-cell-deep box, panes sunk. Stairs: real steps up, or a sunken stairwell. Figures drawn into furniture and objects drawn into walls: lifted by hand-drawn masks. A pinned prop above a pinned box in a blocked cell stands on the box | Almost entirely the **hand-made table**. Derivable for us without it: counters (`CellFlags.COUNTER`), bookshelves (`field.bookshelves` is exported: `gen1-bridge/export/kb_export.lua:41-47`), stairs as warp tiles (no direction), and generic objects via rows 4–5 (which work indoors too) | `lib/TileShape.lua:94-120, 158-209, 509-563, 632-659, 724-759`; `lib/Structures.lua:1481-1491, 1594-1620, 1955-1967, 2676-2720` |
| 17 | **Caves** | — | No cave-specific geometry: a cave is an interior (flat floor, wall volumes, black void tiles). Lighting treats interiors and caves as sky-less | — | `lib/VoxelScene.lua:144` (comment); rows 2, 6, 15 |
| 18 | **Tall grass** | Grass tile, atlas | Flat ground plus, per 8×8 grass tile, one thin standing cut-out of the tuft strokes at that tile's own depth, so the player walks between two rows and the southern row hides the feet; one template per grass tile | **Derivable** (we export the grass tile and `CellFlags.GRASS`) | `lib/Structures.lua:3327-3345`; `lib/TileShape.lua:129-134, 454-458` |
| 19 | **Flowers** | Animated-tile list, frames | A one-voxel-thick standing cut-out of the darkest tones plus what they enclose, taken as the union over the animation frames, with the texture animation masking each frame; ground synthesised from the commonest flat neighbour | **Derivable** (needs our M7) | `lib/Structures.lua:3443-3461`; `lib/TileShape.lua:414-434, 459-460` |
| 20 | **Characters** | Sprite sheet dimensions only | One flat quad per current frame, alpha-cut, always facing south, leaning back by the camera pitch. Deliberately not voxelised | Not a table matter (B5.5) | `lib/SpriteBillboards.lua:1-29` |
| 21 | **Standing height** | Class heights | A character on a cell rides at that cell's class height (an item on a table sits on the table) | **Derivable** from our own shape heights. We need it: 24 story objects stand on solid cells in the real build (BR:31, `story_cell_on_solid`) and puppets are placed at the map's floor level today (`client/puppet/PuppetRuntime.java:243-245`) | `refs/_research/D_voxel_pokered.md:99` |
| 22 | **Meshing rules** | — | Faces sample the tileset atlas directly; merged runs stop at tile boundaries; a tiny texel inset avoids bleeding; ambient occlusion baked per vertex; fixed brightness per face direction | Renderer matters: 3a | `lib/ChunkMesher.lua:27-33, 88-113, 309-330`; `lib/Buildings.lua:71-103` |

**Which rules need the hand-made tables (the licence decision), in one list**

- needed: *which* tiles are trees/round, furniture classes and their heights, ledge tiles, cliff pins, the
  conditional pins, prop background/ground overrides, figure/mounted masks, building grids and band tables;
- not needed (derived from art or from data we export): void tiles; region flood; background detection;
  object extraction; measured heights, region vote, roofs; the fold rule; door fold; ledges, water, grass,
  flowers, counters, bookshelves (from our own export flags); hulls once a cell is known to be round; all
  measured parts of buildings (silhouette, taper, panes).

**Options.**

- **O1. No tables: the automatic pipeline only.** Rows 2–9, 11, 12, 18, 19 plus a *round-cell heuristic* and
  an *automatic band heuristic* (below). Changes: new `builder/shape/` package (B3.3–B3.5). Cost: the largest
  amount of our own design work; interiors stay plain (walls, counters, detected props). Risk: trees and
  houses are only as good as the heuristics; the self-check (B3.6) is the safety net.
- **O2. O1 plus user-supplied rules through texture packs** (B4.3): the same pin vocabulary (class per tile,
  band table per structure) as *pack content the user authors or installs locally*. We ship no table.
  Cost: the rule format and its validation. Risk: none for licences; quality depends on the community.
- **O3. Use Dramaless Shape's `voxel_heights.lua`** (copied, or loaded from the user's own copy of the mod at
  export time). Needs the user's licence decision (`docs/REFERENCES.md:58-62`): MIT is credible for the 2.0
  work, the base is unverifiable. It would also tie tile ids to pokered-era tilesets and to Gen 1 only.
- **O4. Write our own table.** Not possible for an agent: authoring it means looking at ROM art, which the
  hard rules forbid; the user could author it (that is O2).

Heuristics that replace the two table-driven decisions under O1/O2 (inference, to be validated by B3.6):

- *Round cell:* a solid 2×2-tile cell whose outline-plus-enclosure mask (row 13) exists, is roughly as wide as
  tall, is left/right symmetric within a tolerance, and whose four tiles recur as the same unit on a lattice
  (tree walls) or stand alone on flat ground (lone trees, boulders-as-tiles). Cut trees are known exactly
  (`CellFlags.CUT_TREE`).
- *Bands of a building:* take a bounded, non-repeating region with a door (row 9). Facade rows are the rows
  from the door row up to the highest tile row that contains a pane (row 10's "sealed non-black region" test,
  run on the composite); everything above is roof. A constant-rate taper of the silhouette in the roof rows
  means sloped ends; no taper means a flat roof (methodology table, `sprite_to_voxel_methodology.md:57-63`).
  When the test finds no pane row, keep the generic volume (rows 6–8).

**Recommendation.** **O2 with O1 as the base**, built cheapest-first in three tiers so each tier is useful
alone (this is also `docs/NEXT_STAGES.md:145-162`):

1. **Tier A, tile ids only (no pixels):** per-face textured columns with the fold rule, measured heights
   1–6 blocks, region vote, door fold and door adoption, gable/flat roofs, ledge lips, recessed water. Fully
   deterministic from map data; no new textures (faces reuse the existing per-tile textures).
2. **Tier B, cell-sized pixel work:** void tiles, grass tufts, flowers, objects (rows 4–5), cut trees and
   round cells as hulls.
3. **Tier C, structure templates:** bounded structures modelled as a whole (objects split off, bands,
   panes, awnings), gated per structure by the self-check score, overridable by pack rules and `.vox` models.

What would change the choice: the user approving O3 (then Tier B/C class decisions come from the table for
Gen 1 and the heuristics remain for other games), or the prototype (3a's B2.7) showing Tier B/C quad counts
are unaffordable (then stop at Tier A plus hulls only for trees near walkable cells).

**Sketch** (Tier A, in our units; `MapModel` already supplies `tile(tx,ty)` and `kind(cx,cy)`):

```
// builder/shape/Structures.java (new, pure). Coordinates are tiles (= blocks) in a stitched grid.
regions = floodFill4(tiles where solid && sameAtlas)            // scan order row-major => deterministic
for region:
  for each tile column tx, each contiguous run [north..front] (front = southmost):
     unit, fromRepeat = measureRun(...)        // today: HeightRules.measureRun (HeightRules.java:66-90)
     trim rule: extent > 2 && tile(front-1) == tile(front-2) => unit = 2, fromRepeat = true
  modeH = most frequent unit (ties: taller)
  for run: h = door ? modeH : (fromRepeat && modeH > unit ? modeH : unit)
           roofRows = outdoor && (!fromRepeat || adopted) && h >= 2 && tile(north) != tile(north+1)
                      ? min(2, h-1) : 0
// faces of the block at height band k (0 = lowest) of the column standing at tile (tx, ty):
south = tile(tx, front - k)                    // the fold rule
top   = roofRows == 0 ? tile(tx, flatTopRow(run, ty)) : roof wedge wearing tile(tx, north + d)
east/west = same band as south (exposed ends only), north = the column's body tile (row north + 1)
```

**Open.**

- The user's decision on O3 (licence) and on whether pack-supplied rule tables (O2) are wanted at all.
- The object/round thresholds and depths are ours to choose and need the self-check plus a play-test; none of
  Dramaless's values should be taken over.
- ⚠ Not confirmed: how well the round-cell and band heuristics work on real art. Agents cannot look at it;
  only scores (B3.6) can say.

### B3.2 Kanto in First Person's dressing: builder geometry or client effect?

**Answer (facts).** Kanto in First Person (KFP) is a patch to Dramatic Shape that adds presentation only
(`refs/kanto-first-person/README.md:13-15`). No licence: ideas only (`docs/REFERENCES.md:63-64, 473-476`).
Its sounds, panoramas and pictures are its own assets and are never to be used. Sorted for our setup:

| Idea (KFP) | What it does there | For us | Where it lives | Cite |
|---|---|---|---|---|
| Interior walls on the three undrawn sides | Synthesised floor to ceiling wherever open floor meets the void | **Builder geometry.** Today: a 1-block ring of border art up to the ceiling (`builder/plan/WorldPlan.java:416-427`). Change: use the room's dominant wall tile instead of border art | dressing pass | `README.md:23-25` |
| Risers | Every wall-height cell grows side faces up to the ceiling, textured with the cell's own tile repeated per course | **Builder geometry** (Tier A: repeat the column's top band up to the ceiling) | dressing pass | `payload_ceiling.lua:11-16` |
| Ceiling with headroom, darkened dominant wall tile | A lid at a configurable height | **Builder geometry.** Today: a ceiling of border art 4 blocks above the floor (`WorldPlan.java:28-29, 458-459`). Darkening should be a vertex colour/tint, not a new texture (avoids the slot-capacity cost noted in `docs/NEXT_STAGES.md:190-192`); possible only if the block approach allows per-quad colour (3a) | dressing pass | `payload_ceiling.lua:18-21` |
| No ceiling over open-air "interiors" | Forest, Safari, plateau and port tilesets are sky, not rooms | **Builder rule.** Closes our known gap "Forest and Safari maps get ceilings like any interior" (`docs/PROGRESS.md:858`). Needs a neutral `openAir` map attribute (B3.3). For Gen 1 the front end can derive it from engine data we already export, without copying KFP's list: `outside` maps, plus maps whose tileset is `field.indoorEncounters.excludedTileset` (the forest tileset; `builder/export/ExportField.java:21-41`); anything else is a pack/user rule | scene map attribute | `payload_ceiling.lua:118-125` |
| Doors as doors (frame, panels, step, lintel) | Drawn from KFP's own door art | **Builder geometry**, but generated from the door tile's art (Tier C recess rule) or left as the folded door art; never their art | shapes | `README.md:28-30, 247-248` |
| Building backs | A building's north face shows its plain side tile, not a second front | **Builder geometry** (Tier A: choice of the north-face tile) | shapes | `payload_flora.lua:613-621` |
| Tall trees | Round canopies lifted onto trunks drawn as crossed quads of generated bark; stone stacks on rocky maps | **Builder geometry** (a "trunk" parameter of the hull shape; bark must be our own procedural texture or a vanilla block) | shapes / pack rule `class:tree` | `payload_flora.lua:624-631`; `README.md:156` |
| Mountain peaks | Clustered rock cells rise into massifs, higher toward the cluster's interior, faces textured with the cell's own subtiles; candidates exclude bands along connected edges, roof and door neighbours | **Builder geometry** (optional dressing rule; needs a "rock" class, which without tables is a heuristic or a pack rule) | dressing pass | `payload_flora.lua:903-915`; `README.md:157` |
| World apron | Each boundary cell's *ground* tile (the first walkable or water cell inward) continues outward in rings that step down and haze | **Builder geometry**, and it is the same question as Part A's edge transition (track 1, A3): our 12-block void ring (`WorldPlan.java:23-24`) is what it would replace | edge design (track 1) | `payload_flora.lua:1232-1283`; `README.md:82-85` |
| Cave roof, stalactites, stalagmites, pools, torches | Uneven rock overhead, pools on hashed walkable cells, torches along walls | **Builder geometry**, deterministic by hashed cell position; vanilla dripstone/water/lantern blocks or generated shapes. Pools must sit on non-walkable cells for us (collision is per cell) | dressing pass | `README.md:45-48`; `payload_flora.lua:1545-1558` |
| Forest canopy with light wells, an upper layer, a rim of further trees; hanging vines | Leaf panels per cell at hashed heights, a fraction of cells left open | **Builder geometry** (leaf-art shapes at hashed heights; vanilla vines; vine spreading is already off, `docs/NEXT_STAGES.md:202-203`) | dressing pass | `payload_flora.lua:1829-1860` |
| Contact shadow, picture rail, ceiling lamps, pictures | Decoration strips and KFP's own picture art | Geometry is possible; pictures would need our own or vanilla art. Low value | dressing (skip first) | `README.md:31-37` |
| Extra grass blades of varied height | Hashed crossed quads on encounter cells | **Builder geometry** (a variant of the grass tuft shape picked by position hash) | shapes | `payload_flora.lua:4-12` |
| Ledge-hop crouch/arc/settle; doorway step; head bob | Camera only | **Client effect** (on top of `client/move/ForcedMover` and `WarpFade`) | client | `README.md:89-92` |
| Third-person cutaway (near walls melt, ceiling opens) | Renderer | **Client effect**, hard in chunk meshes (needs geometry that changes with the camera); last | client | `README.md:96-99`; `payload_ceiling.lua:23-28` |
| Horizon panorama, clouds, night sky, sun shafts, rainbows | Sky rendering with KFP's panoramas | **Vanilla systems / client effect.** Minecraft already has sky, clouds, sun, moon and stars | rules or client | `README.md:52-56, 65-66` |
| Rain, storms, lightning, puddles, NPC umbrellas | Own weather clock | **Vanilla weather** (a rules decision, track 2); umbrellas and puddles: skip | rules | `README.md:60-64` |
| Particles (seeds, drips, fireflies, leaves, dust, spray, smoke), insects, birds, bats, aircraft | Pooled motes and sprites | **Client effects** with vanilla particle types at map-specific spots derived from the scene (chimney = a roofed structure, drips = cave ceilings); fauna: skip or Cobblemon | client | `payload_flora.lua:14-28`; `README.md:57-59` |
| Cave darkness until Flash; Lavender fog | Nested translucent shells | **Client/biome effect**: we already have dark maps plus Night Vision for Flash (`fabric-bridge/src/main/java/dev/kantobridge/server/FlashService.java`); fog through biome fog colour or a client fog hook | client / datapack | `payload_flora.lua:30-38, 50-51` |
| Wind in grass, swinging vines | Vertex animation | **Client effect** that needs shader support inside a chunk mesh; out of reach without a shader pack (3a) | — | `payload_flora.lua:118, 1849-1860` |
| Ambient loops and footsteps | KFP's own audio files | **Not for us**: sound passes through Gen1Recomp by design (CLAUDE.md "Architecture decisions") | — | file listing only (not opened) |

**Options.** (1) Ignore dressing until shapes exist. (2) Add a **dressing pass** to the generator that runs
after shapes, driven by neutral map attributes (`indoor`, `openAir`, `cave`, `forest`, `dark`), each rule
switchable. (3) Do dressing as hand-placed vanilla blocks in the builder.

**Recommendation.** (2). Dressing rules are pure functions `(scene map, cell, hash(cell)) → extra shapes or
vanilla blocks`, deterministic by position like KFP's ("the same meadow grows the same way every time",
`payload_flora.lua:9-12`), and they never touch walkable cells or collision. Order: interior walls/risers and
ceilings from the room's own art first (they fix what first-person players see today), then open-air
interiors, building backs, trunks, cave roof, canopy; the apron belongs to Part A's edge design.

**Sketch.**

```java
interface DressingRule {                       // builder/shape/dressing/*
    boolean applies(SceneMap map);            // e.g. map.indoor() && !map.openAir()
    void dress(SceneMap map, StitchedGrid grid, ShapeSink out, PositionHash hash);
}
// Example: ceiling. dominantWall = most frequent solid art tile of the map (ties: lowest tile id => deterministic).
// out.put(x, yCeiling, z, ShapeKey.cube6(dominantWall on all faces, tint = DARK))
```

**Open.** Which dressing to keep is a user decision (`docs/NEXT_STAGES.md:372-379`). Headroom and tint values
need a play-test.

### B3.3 A neutral scene description (draft), checked against Gen 2 and Gen 3

**Answer (facts about the three families, from Gen1Recomp's sources).**

| Aspect | Gen 1 (today's export) | Gen 2 (Gold/Silver/Crystal) | Gen 3 (FireRed/LeafGreen/Emerald) |
|---|---|---|---|
| Walk grid | 16-px cell; behaviour from the cell's bottom-left 8×8 tile and per-tileset tile lists (`docs/PROTOCOL.md:644-645`) | 16-px cell; behaviour is one `COLL_*` byte per cell, four per 32-px block, stored on the tileset (`refs/gen1recomp/src/world/gen2/Map.lua:53-63`) | 16-px cell = one metatile; each cell stores `{mid, coll, elev}` (`refs/gen1recomp/src/core/game3/layout_native.lua:26-27, 53-67`) |
| Map size unit | 32-px blocks (`protocol/export/map.schema.json:33`) | 32-px blocks; block id 0 is a stand-in for the border block (`refs/gen1recomp/src/world/gen2/BorderFill.lua:13-15, 36-41`) | cells (`layout.width/height`) |
| Art unit | 8×8 tile, 4×4 per block, one atlas per tileset, 4 grey shades | 8×8 tile, 4×4 per block (`gen2/Map.lua:226-237`); Crystal has two VRAM banks and per-tile flips (`refs/gen1recomp/src/world/gen2/TileAttrs.lua:1-40`) | 16×16 metatile, pre-composited by the importer into **three** indexed atlases per tileset pair: under, middle, over ("matching pret DrawMetatile layer split"); the over layer draws above sprites (`refs/gen1recomp/src/import/gba/native_pack.lua:74-83`; `refs/gen1recomp/src/core/game3/tileset_native.lua:1-2, 78-88, 134-160`) |
| Palettes | One 4-colour SGB palette **per map** (`gen1-bridge/export/kb_export.lua:693-694, 748-760`) | Eight 4-colour BG palettes per map chosen by environment × **time of day** (morn/day/nite/dark), a per-map-group roof override, and a **per-tile** palette slot (`refs/gen1recomp/src/world/gen2/Palettes.lua:1-21, 28-29, 172-212`; `TileAttrs.lua:27-39`) | Sixteen 16-colour palettes per tileset pair (BGR555), patchable at run time (`native_pack.lua:159-196`; `tileset_native.lua:235-255`) |
| Sprite draw order vs. background | Sprites always above the map | Crystal tiles can carry a BG-priority bit (`TileAttrs.lua:7-13`) | An elevation → priority table decides whether an actor draws under or over the "over" layer (`refs/gen1recomp/src/core/game3/field_view.lua:466-472, 517-544`) |
| Elevation | None (every walkable cell is one level) | None | Per cell 0..15: 0 = matches any, 15 = keeps the mover's current level, otherwise a mismatch blocks the step; water cells are level 1 and a surfer lands only on level 3 (`refs/gen1recomp/src/core/game3/collision.lua:926-952, 970, 981-983, 1043-1054`; default player level 3: `refs/gen1recomp/src/core/game3/player.lua:84`) |
| Directional blocking | `field.tilePairs` (a step between two collision tiles is blocked) and ledges from a table (`refs/_research/P3_gen1_data_export.md:240, 246-250`) | Side-wall kinds block leaving in their direction and a neighbour arm blocks "down"; ledges are collision kinds with a hop direction (`gen2/Map.lua:264-277`; `refs/gen1recomp/src/world/gen2/Permissions.lua:170-180`) | Per-direction leave/enter tests on metatile behaviours (`collision.lua:812-816`) |
| Special cells | Flags we compute: grass, door, warp tile, counter, cut tree, pad/hole, spinner (`gen1-bridge/export/kb_cells.lua:8-27`) | Collision kinds: tall/long grass, cut tree, headbutt tree, ice, whirlpool, waterfall, currents with direction, counters, door/staircase/cave warps (`Permissions.lua:31-57, 92-116, 118-149`) | Metatile behaviours (`MB_*`, read through `Collision.behaviorOn`; predicates at `collision.lua:271-647`) |
| Border | One block id per map; outdoors the renderer substitutes a tree-wall block (`refs/gen1recomp/src/render/TileRenderer.lua:23-32`) | One border block per map (`gen2/Map.lua:23, 46-51`) | A `borderWidth × borderHeight` block of metatiles tiled from (0,0) (`layout_native.lua:6-13, 23-25, 63-66`) |
| Indoor/outdoor | `outside`/`surface` booleans derived from tilesets (`kb_export.lua:486-496`) | The map header's `environment` (TOWN, ROUTE, …) and a `PALETTE_*` field that can force a daytime or darkness (`gen2/Map.lua:128-140`; `Palettes.lua:50-58, 84-94`) | ⚠ Not confirmed (map-type fields not read) |
| Runtime terrain changes | Block swaps (`Map:setBlock`), audited as swap sites (`gen1-bridge/export/kb_swapsites.lua:1-53`) | `Map:setBlock` plus `World:changeBlock` with an undo list (`gen2/Map.lua:217-224`) | Per-cell overrides `{mid, coll, elev}` (`layout_native.lua:124-140, 188-205`) |
| Overworld sprites | 16×16 frames, vertical strip: 3 standing + 3 walking, right = left mirrored (`protocol/export/sprite.schema.json:14-19`) | 16×16 from four 8×8 OAM tiles; standing and walking sets, right = left mirrored; per-sprite or per-object OBJ palette, eight palettes per daytime (`refs/gen1recomp/src/world/gen2/OamFacings.lua:23-64`; `Palettes.lua:214-259`) | Per graphics id a sheet with its own `w × h` and `frameCount`; standing S/N/W = frames 0–2, walking 3–8 (two per direction), running 9–17, east = west flipped (`refs/gen1recomp/src/core/game3/ow_sprites.lua:17, 21-28, 84-97`) |
| Connections | Offsets in blocks (`map.schema.json:50-91`) | Offsets in blocks, same strip maths as Gen 1 (`gen2/Map.lua:294-313`) | Offsets in cells (`refs/gen1recomp/src/core/game3/map.lua:65, 93-123`) |
| Mod data access | `mod.datasets:open(v)` registries `maps, tilesets, sprites, palettes, field, …` (`refs/gen1recomp/src/mods/DatasetViews.lua:29-38`) | Same call, Gen 2 roots `gen2Maps, gen2Tilesets, gen2Sprites, gen2Palettes, …`; **no `field` registry** on Gen 2 (`DatasetViews.lua:40-49`; `refs/gen1recomp/src/mods/Schemas.lua:530-552, 589-594`) | Same call exposes `maps` and Pokémon/item/encounter/text roots only; tileset pairs, layouts and sprites are native packs outside the registries (`DatasetViews.lua:51-65`) and need `engine_internals` |

**Draft schema** (JSON; one document per region build, produced by the game's front end from its export
bundle; ROM-derived as a whole, so it lives beside the bundle under `<gameDir>/kantobridge/` and is never
committed or sent). Synthetic values only.

```json
{
  "scene": 1,
  "game": "red",
  "family": "gen1",
  "frontEnd": "kb-frontend-gen1/1",
  "exportHash": "<sha256 of the export index>",
  "units": {"cellPx": 16, "artPx": 8},
  "paletteModel": "map4",
  "paletteVariants": ["default"],
  "atlases": {
    "tilesets.test": {
      "image": "tilesets.test", "tilesPerRow": 16, "tiles": 96, "artPx": 8,
      "planes": ["under"],
      "animated": [{"tiles": [6], "kind": "hshift", "period": 20, "offsets": [1,2,3,2,1,0,7,0]}],
      "tilePalettes": null,
      "hints": {"7": {"class": "flower"}}
    }
  },
  "maps": {
    "TEST_TOWN": {
      "w": 20, "h": 18,
      "env": {"outdoor": true, "openAir": true, "cave": false, "dark": "never"},
      "atlas": "tilesets.test",
      "palette": {"default": "TEST_PALETTE"},
      "art": {
        "cols": 40, "rows": 36,
        "planes": [{"plane": "under", "tiles": "<base64: cols*rows uint16 tile ids, row-major>"}]
      },
      "cells": {
        "kind":  "<base64: w*h uint8; 0 walk, 1 solid, 2 water, 3 ledge, 4 counter>",
        "tags":  "<base64: w*h uint16 bit set; grass, door, warp, warpTile, shore, cut, pad, hole, spinner, sign, ice, current, headbutt, …>",
        "dir":   "<base64: w*h uint8; ledge/current/spinner direction 0..3, 255 none>",
        "edges": null,
        "elev":  null,
        "raw":   "<base64: w*h uint16; the game's own behaviour id, for family-specific rules>"
      },
      "border": {"cols": 4, "rows": 4, "tiles": [2,2,2,2, 2,2,2,2, 2,2,2,2, 2,2,2,2]},
      "connections": {"north": {"map": "TEST_ROUTE", "offsetCells": 0}},
      "warps": [{"x": 5, "y": 5, "to": "TEST_HOUSE", "warp": 1}],
      "objects": [{"id": "TESTTOWN_BALL", "sprite": "SPRITE_TEST_BALL", "x": 15, "y": 4,
                   "stays": true, "kind": "item", "elev": 0}],
      "swapSites": [{"id": "TEST_TOWN:cut:10:14", "kind": "cut",
                     "cells": [{"x": 10, "y": 14,
                                "closed": {"kind": 1, "tags": ["cut"], "art": [14, 14, 14, 14]},
                                "open":   {"kind": 0, "tags": [],      "art": [0, 0, 0, 0]}}]}],
      "structures": [],
      "props": []
    }
  },
  "sprites": {
    "SPRITE_TEST_GIRL": {
      "image": "SPRITE_TEST_GIRL", "frameW": 16, "frameH": 16,
      "frames": {"stand": {"down": [0], "up": [1], "left": [2]},
                 "walk":  {"down": [3], "up": [4], "left": [5]}},
      "mirrorRight": true, "alternateMirror": ["down", "up"],
      "palette": "map", "transparentShade": 0, "anchor": "feet"
    }
  },
  "layout": {"root": "TEST_TOWN", "unit": "cells", "maps": {"TEST_TOWN": {"x": 0, "y": 0}}}
}
```

Field notes (what each part is for, and how the other families fill it):

- **`units`**: `cellPx` is 16 for all three families. `artPx` is the art-grid pitch: 8 (Gen 1/2: four art
  tiles per cell) or 16 (Gen 3: one metatile per cell). The geometry core works in art pixels; our world
  scale stays 1 cell = 2×2 blocks (`world/Scale.java:15`), i.e. 1 px = 1/8 block.
- **`art.planes`**: `under` always; Gen 3 adds `middle` and `over` (pixel index 0 is transparent in the over
  plane: `native_pack.lua:213-217`); Crystal's BG-priority tiles become `over` entries for those tiles. The
  geometry core treats `over` art as "above head height": tree tops, bridge decks, roof overhangs.
- **`paletteModel`**: `map4` (Gen 1: one palette per map), `tile8x4` (Gen 2: `atlases[*].tilePalettes` gives a
  slot per tile, `maps[*].palette` maps each variant to a palette *set* of eight), `tile16x16` (Gen 3: the
  atlas is already paletted per pixel; `palette` names the pair's palette block).
- **`paletteVariants`**: `["default"]` for Gen 1 and Gen 3; `["MORN","DAY","NITE","DARK"]` for Gen 2
  (`Palettes.lua:28`). The texture key carries the variant (B4.1); which variant shows is a client-side
  choice at run time (clock, Flash), never geometry.
- **`cells.kind` / `tags` / `dir`**: the neutral behaviour vocabulary; `raw` keeps the game's own id so a
  front end or a pack rule can still tell `MB_*` or `COLL_*` values apart without widening the core.
- **`cells.edges`**: optional `w*h` uint8, bits 0–3 = leaving N/S/W/E blocked, bits 4–7 = entering blocked.
  Gen 1: from `field.tilePairs`; Gen 2: side walls (`Map:stepPermitted`); Gen 3: the leave/enter tests.
  Visual use only (a low rim or rail on that edge); collision stays with the hidden game.
- **`cells.elev`**: optional `w*h` uint8 (Gen 3). See the paper check.
- **`border`**: a small tiled block of art tiles. Gen 1/2: one 32-px block (4×4 tiles), with Gen 1's renderer
  substitute applied by the front end (M6); Gen 3: `borderWidth × borderHeight` metatiles.
- **`objects`**: stationary scenery objects matter to shapes (items on tables, boulders, cut trees that are
  objects in Gen 3). `kind` and `stays` come from the front end.
- **`swapSites`**: per **cell** open/closed states with their own art and behaviour. This replaces the
  Gen-1-specific "32-px block id before/after" of today's `swaps` export (`protocol/export/swaps.schema.json`),
  which the Gen 1 front end expands through the tileset's block table exactly as `MapModel` does today
  (`builder/plan/MapModel.java:136-153, 171-189`).
- **`structures` / `props`**: optional declarations by a front end or a pack ("this rectangle of art tiles is
  one building with these bands"; "this cell is a tree"). Empty by default: the geometry core detects them.
- **`sprites`**: frame size and a frame table by pose and facing, instead of today's fixed "6 frames of
  16×16" (`builder/pack/Textures.java:134-161`). `palette`: `map` (Gen 1), `own` (Gen 2 sprite palette, with
  an optional per-object override in `objects[*].palette`), `baked` (Gen 3).

**Paper check against Gen 2.**

- 32×32 blocks with 16×16 collision: fits, because the description is per cell and per art tile, never per
  block. Block id 0 as the border stand-in is resolved by the front end (`BorderFill.lua:36-41`).
- Per-tile palettes: `tilePalettes` on the atlas plus a palette **set** per map. Consequence for textures:
  a tile's texture is determined by (atlas, tile, the colours of its slot in the map's set, variant). Many
  maps share a set, so the key should name the *palette-set id*, not the map (B4.1).
- Time-of-day palettes: four variants → up to four colourings of every used tile. Three ways to show them
  (options for whoever builds Gen 2): (a) four texture sets and a client-side swap of the atlas contents at
  dawn/dusk/night; (b) one "day" set plus a tint curve; (c) indexed textures and a palette lookup in a
  shader. Only (a) and (b) work without shaders. The scene format supports all three.
- Roof palettes per map group: part of the palette set; the front end resolves them (`Palettes.lua:198-210`).
- `PALETTE_DARK` maps: `env.dark = "flash"`; the cave-entrance flicker (`Palettes.lua:105-127`) is a client
  effect.
- Ledges, cut trees, headbutt trees, ice, currents, waterfalls, whirlpools: all expressible as `kind` +
  `tags` + `dir`. Directional side walls: `edges`.
- Sprites: 16×16, same poses; palette `own`.
- No `field` grab bag on Gen 2 (`Schemas.lua:589-594`): confirms that ledges, cut trees and water must come
  through the neutral cell vocabulary, not through Gen 1 field tables.

**Paper check against Gen 3.**

- Metatile bottom/top layers: `planes` under/middle/over. Because Gen1Recomp pre-composites each metatile to
  16×16 images per layer, the front end exports three atlases per tileset pair and the core never needs the
  raw 8×8 tiles.
- Behaviours: `raw` holds the metatile behaviour; the front end maps the ones that matter to neutral tags.
- Per-cell elevation: `elev`. **Finding:** in the engine, elevation is a *collision level*: a mismatch only
  blocks a step (`collision.lua:938-944`) and levels order sprites against the over layer
  (`field_view.lua:466-472`). **Inference:** the height differences a player sees are drawn in the art, so
  the default visual rule stays "all walkable cells of a map on one plane", and `elev` is used for two things
  only: (1) **bridges and multi-level cells**: a run of level-15 cells joining two different levels is a deck
  over a passage; the deck is the `over` plane raised above head height and the passage keeps the `under`
  plane; (2) optionally, raising whole plateaus (higher-level cells enclosed by cliff art), which needs ramps
  where the art draws stairs: a later design step.
- Bridges/multi-level in Minecraft: one hidden-game cell that can be crossed at two heights needs two walk
  surfaces in one column. Today's index has exactly one feet level per map (`maps[*].y`,
  `protocol/world/world_index.schema.json:57-60`). A Gen 3 region therefore needs a per-cell feet-level table
  in its index. That is a **region-record / WorldIndex change (track 2, A8)**, flagged here so nothing blocks
  it: the scene format carries `elev` and the shape generator can already place a deck.
- Border block of arbitrary size, connections in cells, sprites of other sizes with more frames, 16-colour
  palettes: all covered by the fields above.
- ⚠ Not confirmed: Gen 3 map-type/indoor flags, weather, reflections and the animated-tile format
  (`refs/gen1recomp/src/core/game3/tileset_anim.lua` was not read).

**What in today's export and builder is Gen-1-specific** (the full list with hooks is B7.2): the 16-bit
`CellFlags`; block-id swap sites; one SGB palette per map and 4-shade greyscale art; 8×8 tiles in 4×4 blocks;
fixed 6-frame 16×16 sprite strips; `PALLET_TOWN` as layout root; games limited to red/blue; Gen 1 field
tables (ledges, tile pairs, cut-tree swaps, card-key doors, spinners).

**Options.**

- **O1. Keep today's export as the contract and bolt Gen 2/3 on later.** No work now. Every consumer
  (`MapModel`, `ArtCatalog`, `Textures`, puppets) keeps its Gen 1 assumptions; a second family means a second
  builder.
- **O2. Change the wire export to the neutral scene now.** One format everywhere, but it rewrites
  gen1-bridge's export, ten schemas, the fake, the bundle and every export test for no visible gain today.
- **O3. Keep the wire export per family; add the neutral scene as an in-memory Java model produced by a
  front-end adapter (`Gen1FrontEnd`), and write the shape generator, the texture keys and the character code
  against that model only.** The JSON form above is the model's serialisation, used for golden tests and
  debugging. **Recommended.**

**Recommendation.** O3. New package `fabric-bridge/src/main/java/dev/kantobridge/scene/` (pure Java, both
sides): `Scene`, `SceneMap`, `SceneAtlas`, `SceneSprite`, `CellKind`, `CellTag`; `scene/gen1/Gen1FrontEnd`
builds it from `ExportBundle` by reusing `MapModel`'s logic (tiles under every block, swap states). Existing
classes keep working unchanged at first; the shape generator (B3.4/B3.5) is the first consumer. What would
change the choice: a decision to start a Gen 2 bridge soon (then O2's wire format becomes worth its cost,
because a new bridge should speak the neutral format from day one).

**Open.** The companion file `scene_description.draft.json` (spec §8) can be the JSON above verbatim; it holds
only synthetic values. Whether plateaus get real height in a Gen 3 region is a later user decision.

### B3.4 Determinism: shape ids identical for every client

**Answer (facts: how the slot map does it today).**

- Keys have a total order: `KEY_ORDER` = atlas, then palette, then tile, then shape
  (`world/SlotMap.java:42-45`); `enumerate` puts the keys in a `TreeSet` and hands out indices in that order,
  per shape (`SlotMap.java:157-170`).
- The key set is collected from the **whole export**, never from a build's map filter, so every build and
  every player's pack agree (`builder/plan/ArtCatalog.java:20-29, 60-125`; `docs/PROTOCOL.md:723-725`).
- The JSON is canonical: `SlotMap` writes it with `ProtoJson.write` (compact, sorted keys:
  `proto/ProtoJson.java:23-26, 58-63`) and its hash is the SHA-256 of that text (`SlotMap.java:150-154, 206-214`;
  `world/Hashes.java:23-34`). A format `version` constant is part of the JSON, hence of the hash
  (`SlotMap.java:32, 216-218`; `protocol/world/slot_map.schema.json:10`).
- A stored map is re-sorted on read, so the hash never depends on file order (`SlotMap.java:273-276`).
- The hash travels in the WorldIndex (`slotMapHash`, `protocol/world/world_index.schema.json:80`) and in the
  pack's `kantobridge.json` (`builder/pack/ResourcePackWriter.java:228-233`); the server relays the slot map
  only when its hash equals the index's, and a client whose pack differs rebuilds its own art against the
  host's slots (`docs/PROTOCOL.md:743-748`).
- Planner code avoids hash-map iteration order where output order matters
  (`builder/plan/StoryCellPlanner.java:63-64`; `world/StoryCellTable.java:20-21, 41-43`).

**Options** for what a shape id *is*:

- **I1. Content-addressed geometry** (id = position of the shape's quads in a sorted list). Rejected: geometry
  is derived from pixels, so two clients with different art (or a different pack) would disagree, and the host
  could not name a shape without having sent art-derived data.
- **I2. Structural keys** (id = position of a *pixel-free* key in a sorted list). The key says which rule and
  which tile ids/offsets; each client turns the key into geometry with its own art. Mirrors the slot map
  exactly (a slot key is also pixel-free). **Recommended.**
- **I3. No ids at all**: every client re-plans positions → geometry locally. Works only together with a guard
  hash; it is I2 without storing the list (see B3.5 option S3).

**Recommendation.** I2, with two separately versioned stages so that only what must be shared is shared:

1. **Planner** (position → key). Shared pure Java in `src/main`. Its version string and its parameters are
   **inside the hashed JSON**. A planner change is a new version; a world keeps the version it was built with.
2. **Geometry** (key → quads). Client-only. Its version is *not* in the world hash: it may improve freely,
   and packs may override it, without touching any world. It keys the client's own cache.

Rules that make the planner deterministic (each is a test in B8.1):

- integers only (heights in 1/16 block, ratios as cross-multiplied integer comparisons); no floating point;
- region ids and flood fills in row-major scan order; ties broken by lowest tile id, then lowest coordinate;
- every collection that reaches the output is sorted (`TreeMap`/`TreeSet`), never `HashMap` iteration;
- keys computed from the whole export (all maps, both swap states, the border art), never from the filter;
- no pixel may influence a key (pixels influence geometry only).

**Sketch: keys, index, hash.**

```
// builder/shape/ShapeKey.java — canonical text form "<kind>:<a>|<numbers…>"; <a> = index into the sorted atlas list.
// Order (a Comparator, as SlotMap.KEY_ORDER): kind name, then atlas index, then each number numerically
// (sig as text), then modifiers. Never by string comparison of the numbers.
flat:<a>|<tile>                                   // full cube, art on top  (today's FULL slot)
slab:<a>|<tile>|<h>                               // water recess, ledge lip, low box; h = 1..15 in 1/16
cube:<a>|<u>,<d>,<n>,<s>,<w>,<e>                  // one tile id per face; -1 = face never visible
roof:<a>|<u>,<s>,<n>|<h0>,<h1>|<hipW>,<hipE>      // a wedge inside one block; edge heights in 1/16; hips 0/1
tuft:<a>|<tile>                                   // flat ground + standing tufts (grass, flower)
cell:<a>|<t0>,<t1>,<t2>,<t3>|<g>|<bx>,<by>,<bz>   // a block of a cell-sized unit (tree, bush, sign); g = ground tile
tmpl:<a>|<sig>|<bx>,<by>,<bz>                     // a block of a bounded structure; sig = first 16 hex digits of
                                                  //   sha256(canonical {cols, rows, tiles[], doors[], ground, outdoor})
// modifiers appended in fixed order when present:  |tint=<0..3>  |v=<variant>
```

```json
// <world>/kantobridge/shape_index.json  (ROM-derived like slot_map.json: tile ids of the game's maps)
{"version": 1,
 "planner": "kb-shapes/1",
 "params": {"maxUnit": 6, "minRepeatRows": 4, "tierB": true, "tierC": true, "hullRing": 2},
 "atlases": ["tilesets.test"],
 "palettes": ["TEST_PALETTE"],
 "geoms": ["cube:0|3,3,3,5,3,3", "flat:0|0", "slab:0|6|8"],
 "templates": [{"sig": "0123456789abcdef", "atlas": 0, "cols": 8, "rows": 6,
                "tiles": [4,4,4,4,4,4,4,4, "…"], "doors": [[5,5]], "ground": 0, "outdoor": true}],
 "instances": [[0, 0], [1, 0], [2, 0]]}
```

- `geoms`: sorted canonical keys; **geom id = index**. `instances`: sorted `[geomId, paletteIndex]` pairs;
  **shape id = index** (what a position refers to). Geometry is palette-free; the palette only picks textures.
- `templates` carries the tile grid behind each `sig`, so a guest can rebuild a structure from the host's
  list even where its own map data would differ.
- `shapeIndexHash = Hashes.sha256(ProtoJson.write(json))`, exactly as `SlotMap` (`SlotMap.java:150-154`).
  Because `planner` and `params` are inside the JSON, the generator version is inside the hash.
- New fields: WorldIndex (or the region record of track 2) `shapeIndexHash`; pack info `kantobridge.json`
  gains `shapeIndexHash` and `geometry` (the client geometry version) so a client can tell whether its cached
  shape table fits the world, the same way `PackInfo` compares `slotMapHash`
  (`client/packs/PackInfo.java:16-23`).

Files: new `world/ShapeIndex.java` (beside `SlotMap.java`, same shape: `enumerate`, `toJson`, `hash`,
`fromJson`), `builder/shape/ShapeKey.java`, `builder/shape/ShapePlanner.java`; `world/WorldIndex.java:31`
(`ROOT_FIELDS`) and `:57-81, 173-210, 230-269` (constructor, writer, reader) for the optional hash;
`protocol/world/world_index.schema.json`; a new `protocol/world/shape_index.schema.json`;
`builder/BuildTask.java:48-49` (a third file constant) and its write step; `builder/BuildReport.java`
(`shapeIndexHash` + counts, B8.2).

**Open.**

- An older client reading a WorldIndex with an unknown root field: `WorldIndex` validates root fields against
  `ROOT_FIELDS` (`world/WorldIndex.java:31`), so adding a field needs either the relay protocol number to
  move (`docs/PROTOCOL.md:807-814`) or the hash to live in the new region record instead. Track 2 decides.
- Whether `templates` (tile grids) should be relayed at all, or guests with a different export simply fall
  back to plain cubes for templates they cannot rebuild. Tile grids are map data, not art, and map data
  already passes through the server (`docs/PROTOCOL.md:806`), so relaying is allowed.

> **Integrator note.** `shapeIndexHash` lives in the region record's `hashes` ([4.5](#45-the-region-record)), which answers the open point here without touching the closed v1 WorldIndex.

### B3.5 Where generation runs; data volumes; build-time impact

**Answer (facts).**

- The planner inputs exist on the server at build time and on every client that has an export: the builder
  opens the bundle (`builder/HeadlessBuild.java:147`; the "Create Kanto world" flow hands the bundle path to
  the integrated server, `docs/PROGRESS.md:280-283`), and the client's pack generator opens it
  (`client/packs/PackGenerator.java:46-52`). Both already run the same pure planner code
  (`ArtCatalog.of`, `MapModel.build`) from `src/main`.
- Art never travels (`docs/PROTOCOL.md:746-748, 806`; CLAUDE.md "ROM-derived data"). Map-derived data does:
  `world.index` and `world.slots` are relayed in 64 Ki-character parts
  (`docs/PROTOCOL.md:701-702, 717-718`; `server/WorldIndexSync.java:200-204`).
- Real-Kanto sizes from the reports: 94,876 cells in 222 maps (ER:39, 100), of which walkable 48,346 and
  water 9,331 (ER:34, 37); grass 2,537, ledge 758, counter 757, door 205, cut tree 35, sign 201 cells
  (ER:24-37); 87,564 void-fill cells (BR:34); 2,485,154 blocks in 2,126 chunks (BR:45, 47); planning 6,185 ms,
  placing 50,473 ms (BR:50-51); 4,005 tile textures, 3,926 full slots, 14 palettes in use (BR:37, 40;
  ER:104); export build about 8 s, 0.77 MB (ER:240-247).

**Inferences from those counts** (each marked; none is a measurement):

- Solid cells ≈ 94,876 − 48,346 − 9,331 = **37,199** (assumes walkable and water never overlap; includes the
  757 counter cells and, if ledge cells are not walkable, the 758 ledge cells).
- The void fill is by far the largest group of solid art: 87,564 cells × 4 columns × 4 blocks
  (`builder/plan/WorldPlan.java:404-408`: Y 63..66) = **1,401,024 blocks, about 56% of all placed blocks**.
  Consequence for the generator: the void ring must never get per-pixel hulls. Dramaless reaches the same
  conclusion and only models tree hulls within a few tiles of the map body
  (`refs/DRAMALESS_SHAPE-main/lib/Structures.lua:58-64, 168-178`).
- Upper bound on shaped block positions inside maps: 37,199 cells × 4 columns × 6 blocks = 892,776.
- Tall-grass tufts: at most 2,537 cells × 4 tiles = 10,148 standing cut-outs in the whole region.
- The number of distinct keys, templates and quads **cannot** be derived from the reports. Fields to add are
  listed in B8.2.

**Options** for getting position → shape id to the client (the choice interacts with 3a's block approach; the
generator supports all three behind one function `ShapePlan.shapeAt(x, y, z)`):

| | How | Storage / network | Works with | Risks |
|---|---|---|---|---|
| **S1. In the block state** | The builder places a block whose state *is* the shape id | none extra | static block families (one state per shape) | Capacity: today 16 × 256 states per shape class and 3,926 of 4,096 full slots used (BR:40; `docs/PROGRESS.md:847-848`). Every new shape needs a state |
| **S2. A stored shape map** | The builder writes, per chunk, a palette-compressed array of shape ids; the server sends it with the chunk or on join | ≤ 2,485,154 entries; 2 bytes each raw ≈ 5 MB, run-length/palette coding should bring it well under 1 MB (⚠ estimate) | flexible block with a client lookup | New world file format and a new relay message; the server must hold it |
| **S3. Re-planned on each client** | The client runs the same `ShapePlanner` over its own export and the synced WorldIndex placements, and compares its `shapeIndexHash` with the world's. Equal hash ⇒ identical plan by construction | nothing stored, nothing sent except one hash | flexible block with a client lookup | A guest whose export differs gets a different hash and needs S2 or a fallback (plain cubes, today's look). Planning time on join (see below) |

**Recommendation.**

- **Server/builder:** run `ShapePlanner` during planning (`builder/plan/WorldPlanner.java:171-206` is where
  the per-map `MapModel`s are built; the shape pass follows the void-fill step that starts at `:208`), place
  the host blocks, write `shape_index.json`, put `shapeIndexHash` in the index. The server never builds
  geometry and never reads pixels for it.
- **Each client:** build the **shape table** (shape id → quads + texture keys) from its own export, off the
  client thread, right after the pack is generated (`client/packs/PackGenerator.java:46-60` is the hook; the
  "Create Kanto world" flow and `TextureSync` already call it). Cache it under
  `<gameDir>/kantobridge/shapes/<shapeIndexHash>-<geometryVersion>-<packOverridesHash>.bin`.
- **Position → id:** S3 with S2 as the fallback for mismatching exports, *if* 3a chooses the flexible block;
  S1 if 3a chooses static families. Both use the same `ShapeIndex`.
- **Multiplayer:** a guest whose own `shapeIndexHash` differs asks for the host's index (`world.shapes`, same
  parts format as `world.slots`, sent after it in `server/WorldIndexSync.java:200-204`) and builds geometry
  for the host's keys from its **own** art; a key it cannot build (unknown atlas or tile) becomes a plain
  cube. This is the M9 texture flow applied to shapes (`docs/PROTOCOL.md:743-748`).

**Build-time and memory impact (scaled; to be measured).**

| Step | Today | With shapes | Basis |
|---|---|---|---|
| Export (gen1-bridge) | ~8 s game time, 0.77 MB (ER:240-247) | + two small fields (M6, M7): negligible | inference |
| Planning (server) | 6,185 ms for 220 maps (BR:50) | + one linear pass over ≈ 380k tiles plus the ring. Same order as `MapModel.fill` + `HeightRules.measure`, which are part of today's 6.2 s: expect +1–3 s | ⚠ estimate; report `shape_plan_ms` |
| Placing (server) | 50,473 ms for 2.49 M blocks (BR:45, 51) | Unchanged if host blocks are the same count; measured heights up to 6 instead of 2/4 change the count by the height difference only | inference |
| Shape index | — | Keys: unknown count (report it). At ~50 bytes of JSON per key, 10k keys ≈ 0.5 MB ≈ 8 relay parts | ⚠ estimate |
| Pack build (client) | Not in the report (only logged: `PackGenerator.java:54-57`) | No new textures for Tier A/B/C: faces reuse the 4,005 tile textures through sub-tile UVs; darkening by vertex colour | design; report `pack_ms` |
| Shape table build (client) | — | Tier A: a few quads per key, microseconds each. Tier B/C: one pixel analysis per *distinct* signature; Dramaless reports a whole house shell as roughly two thousand merged quads (`refs/DRAMALESS_SHAPE-main/lib/Buildings.lua:37-38`) and a carved tree cell as several hundred (`lib/Structures.lua:1268-1275`) at the same texel resolution we use | their figures, not ours; report `shape_quads`, `shape_table_ms` |
| Shape table memory (client) | — | At 32 ints per quad (vanilla's baked-quad size, 3a to confirm) 100k quads ≈ 13 MB | ⚠ estimate |

**Sketch.**

```java
// builder/shape/ShapePlanner.java (src/main; pure)
public static ShapePlan plan(Scene scene, Collection<MapPlacement> placements, ShapeParams params);
public final class ShapePlan {
    ShapeIndex index();                               // sorted keys + hash
    int shapeAt(int x, int y, int z);                 // -1: no shape here (air, or a plain host block)
    Iterable<Host> hosts();                           // positions the builder must make solid/host blocks
}
// builder/shape/geom/ShapeTable.java (src/main, pure: no Minecraft client classes, so JUnit and the headless
// build can run it; built off-thread, immutable afterwards => safe for meshing threads)
public final class ShapeTable {
    static ShapeTable build(ShapeIndex index, Scene scene, ArtSource art, Overrides packs, Geometry g);
    ShapeMesh mesh(int shapeId);                      // quads: 4×(x,y,z in 1/16), texture key, uv rect, face, flags
    ShapeMesh closedMesh(int storyCellIndex, int block); // B3.7
}
// src/client only holds the adapter: cache file, reload listener, texture-key -> sprite lookup, the emitter (3a).
```

**Open.** S1/S2/S3 is decided together with 3a (B2.1, B2.5). The estimates above need the report fields of
B8.2 before anyone relies on them.

> **Integrator note.** Integration decision: the shape map is **synced** (option S2), as [B6.1](#b61-the-region-registry) recommends; re-planning on the client behind the hash (S3) stays a possible optimisation. Report field names are unified in [4.11](#411-build-report-additions).

### B3.6 A self-check: re-project the 3D result into the game's view and score it

**Answer (facts).**

- The game's own picture of terrain is fully determined by data the bundle already has: block ids → tile ids
  → atlas pixels (`builder/plan/MapModel.java:191-208`; `builder/pack/Textures.java:53-67`). No live frame is
  needed to know what the map looks like from the game's angle.
- The Game Boy view is an oblique projection in which both the ground plane and south-facing walls appear
  undistorted: this is exactly what Dramaless's "fold" rule assumes (band *k* of a south face shows the map
  row *k* tiles north: `refs/DRAMALESS_SHAPE-main/lib/ChunkMesher.lua:10-16`) and what the methodology calls
  "roof tops drawn as if seen from above, walls as if seen from the front"
  (`sprite_to_voxel_methodology.md:11-16`).
- Dramaless verifies its building models with numeric checks, a second implementation and a cheap
  painter's-algorithm preview, not by eye (`sprite_to_voxel_methodology.md:135-153`).
- Live frames exist on the client today: the mirror screen receives the hidden game's 160×144 frames
  (`client/ui/MirrorLink.java`, `client/ui/MirrorScreen.java`; cost and rates in `docs/PROGRESS.md:375-377,
  744-752`). They are ROM-derived art.

**Feasibility.** Yes, as a pure function in `src/main` with no Minecraft classes:

```
project(x, y, z)  ->  screen (sx, sy) = (x, z - y),  nearness = z + y        // units: Game Boy pixels
```

A point at height *y* appears *y* pixels further up the screen. Only up-facing and south-facing quads can be
seen from that direction (east/west faces are edge-on, north and down faces point away). For a Tier A column
with front row *f* and height *h* rows, the south face lands on screen rows *f−h+1 … f*, wearing tiles
*f−h+1 … f*: an exact match with the 2D map **by construction**. Anything else the score will show.

One thing the check must not punish: a 3D structure hides the ground north of it (its top face projects *h*
rows further north than its footprint). The 2D drawing has no depth, so that occlusion is inherent. The score
is therefore taken over the structure's own pixels, and hidden ground is only counted.

**Metrics** (all computed on *texel identity*, never on colour, so they are palette-independent and exact):

| Metric | Definition | Pass threshold (initial; tune after the first real run) |
|---|---|---|
| `holes` | pixels of the map body where the projection hits nothing | 0 |
| `ground_match` | of the walkable/water pixels **not** hidden by a structure: share whose nearest projected sample comes from the same atlas texel as the 2D map | 1.0 for Tier A; ≥ 0.98 with tufts/flowers (their ground is synthesised) |
| `structure_match` | of the pixels of solid tiles: share whose nearest projected sample is the same atlas texel | Tier A walls: ≥ 0.99. Roofed columns: ≥ 0.90 (a sloped roof is stretched by this projection). Objects: ≥ 0.95. Templates: ≥ 0.90, else that template falls back to Tier A |
| `occluded_ground` | share of walkable pixels hidden behind 3D geometry | reported only (it says how much of the classic view the shapes cover) |
| `back_faces` | quads facing north/down that are not hidden behind same-structure geometry from a north view | reported only (the "false back door" problem KFP patches) |

**When it runs.**

1. **JUnit** on the synthetic bundle: exact expected scores (golden numbers), per rule (B8.1).
2. **On each client, after the shape table is built**, per distinct template (cheap: only templates need it;
   Tier A is exact). Result: gate Tier C templates automatically; write
   `<gameDir>/kantobridge/reports/shape_selfcheck.json` with, per map id and per template signature, the five
   numbers above and nothing else.
3. **e2e headless** (test install): the same file copied to `local/reports/` like the build report
   (`builder/BuildOptions.java:17`), and gated on thresholds (B8.2). The generator needs the bundle only, so
   it can run inside the headless server process or `ExportCli`'s JVM; no client is needed.

**Options** for the reference image:

- **R1. The 2D map composed from the export** (recommended). Deterministic, no hidden game, testable.
- **R2. The hidden game's live frame** through the mirror. It would additionally check the *export* against
  the engine (tiles, border, animation phase), but it contains sprites, text boxes and animation, covers
  only 10×9 cells around the player, and can only run in a real client. Keep it as an optional debug overlay
  later, never in automated runs.

**Data-safety constraints.**

- The reference image, the projection buffer and any difference image are art-derived: memory only, on the
  player's own machine; never written to disk by default, never sent, never logged.
- Only the player's own client may read live frames (R2); the server never sees them (frames reach the
  client over its own socket to gen1-bridge, not through the relay).
- Outputs are scores and counts keyed by engine symbols (map ids) and hashes (template signatures): the same
  class of data as `build_report.json` (`builder/BuildReport.java:14-17`). Tests log scores only.
- A debug image dump, if ever added, must be an explicit user action writing under `<gameDir>/kantobridge/`;
  agents never open it (CLAUDE.md hard rules; `docs/PLAN.md` §0.3).

**Recommendation.** Build R1 with the five metrics as `builder/shape/SelfCheck.java`; use it as the automatic
gate for Tier C and as the regression test for every rule. Files: new `builder/shape/SelfCheck.java`,
`builder/shape/Reference.java`; hook in the client shape-table build and in `builder/BuildTask.java` (report
copy).

**Sketch.**

```
for each quad q of the placed shapes with normal +Y or +Z:
    for each texel (i, j) of q:                       // 1 texel = 1 Game Boy pixel = 2/16 block
        (x, y, z) = centre of that texel in pixel units
        sx, sy = x, z - y ;  near = z + y
        if near > nearest[sx, sy]:  nearest[sx, sy] = near ;  source[sx, sy] = (atlas, tile, tx, ty) of q's uv
for each pixel p of the map body:
    want = (atlas, tile, tx, ty) of the 2D map at p
    classify p as hole / match / mismatch, into ground or structure, per map and per template signature
```

**Open.** Thresholds are guesses until the first real run; record the distribution first, then fix them.

### B3.7 Story cells and swap sites: open and closed shapes

**Answer (facts).**

- The world is built with every swap site **open**; each changing cell gets a `story_tile` block at feet level
  (`builder/plan/WorldPlan.java:452-457`; `builder/plan/MapModel.java:21-30`; `docs/PROGRESS.md:247-248,
  521-525`). There are 95 sites in the real export: 35 cut trees, 20 card-key doors, 36 scripted, 4 boulder
  switches (ER:231-237), and 162 tile story cells in the full build (BR:17).
- The closed state of a cell is already planned from the same code path as the open one:
  `MapModel.closedModel()` is the map with every changing cell in its other state
  (`MapModel.java:373-388`), and `StoryCellPlanner.closedState` reads the closed kind, height and the four
  art slots from it (`builder/plan/StoryCellPlanner.java:102-133`). The WorldIndex stores
  `closed{block, pass, height, art[4]}` (`protocol/world/world_index.schema.json:122-151`).
- Drawing is per player and client-side: `ClientStoryView.drawn(key)` says whether the local player sees a
  tile cell closed (`client/story/ClientStoryView.java:96-106, 114-118`); `StoryCellRenderer` then draws the
  tile block's baked model `height` times (`client/story/StoryCellRenderer.java:44-71`). A `GameRenderer.pick`
  mixin lets the crosshair land on the drawn art (`docs/PROGRESS.md:574-578`;
  `client/act/ClosedArtPick.java`).
- Collision is separate and unchanged by art: `closed.pass` is `solid`, `water` or `ground`
  (`world/StoryCellTable.java:64-85`).
- Strength boulders are not tiles: they are NPC puppets of kind `boulder` (`docs/PROTOCOL.md:285`;
  `client/puppet/PuppetManager.java:34`); `boulder_area` story cells only carry collision
  (`docs/PROTOCOL.md:323`). Item balls, Snorlax and guards are puppets too (`object` cells).

**What each case needs in the shape model**

| Case | Open shape | Closed shape | Notes |
|---|---|---|---|
| Cut tree (35) | ground of the `after` block | a cell-sized object (`cell` key of the closed tiles: a bush/tree hull on the ground the Cut leaves behind) | The ground under the closed object is the open state's tile: exactly the "prop → ground" pairing Dramaless needs a table for (B3.1 row 5); we get it from the swap site itself |
| Card-key door (20), other gates | floor | a wall/door column (`cube` keys of the closed tiles, height from the closed model) | Neighbouring wall columns were planned with the door open; their faces toward the door are simply hidden when it is closed |
| Scripted swaps (36): Elite Four seals, Mansion gates, gym gates | as built | as the closed model | One site is overwritten on entry (noted, `MapModel.java:126-129`) |
| S.S. Anne (hull → water) | water | hull art, flat (`pass` ground/water) | `height` 0: flat art as today (`StoryCellRenderer.java:57-63`) |
| Victory Road boulder switches (4) | floor | barrier column | swap kind `boulder` |
| Strength boulder, item ball, Snorlax, guards | — | — | puppets: character styles (B5), no tile shape |

**Options** for drawing the closed shapes:

- **C1. Keep the block-entity renderer**, but let it draw *shape meshes* instead of whole tile cubes. Per-frame
  cost stays tied to the 162 tile cells; no chunk re-mesh on story changes; works with either block approach.
- **C2. Draw closed shapes in the chunk mesh** (the flexible block asks "is this cell closed for the local
  player?" while meshing) and mark the section dirty when a cell opens or closes. No per-frame cost and
  correct lighting/occlusion, but a re-mesh per change and thread-safe access to the story view. 3a owns the
  mechanism (B2.6).

**Recommendation.** Data model first, mechanism second:

- The shape planner runs on **both** `MapModel` states. For every `tile` story cell it emits, besides the
  open shape ids of its blocks, a list of **closed shape ids**: for each of the cell's 2×2 columns, bottom to
  top. Both sets are in the shape index (the closed art is already in the slot map for the same reason:
  `ArtCatalog.java:112-124`).
- WorldIndex `storyCells[*].closed` gains `shapes: [[ids of column NW], [NE], [SW], [SE]]`; `art` and `height`
  stay for clients without a shape table (fallback = today's look). Classes:
  `world/StoryCellTable.java:97-131` (`Closed`), `builder/plan/StoryCellPlanner.java:107-133`,
  `protocol/world/world_index.schema.json:122-151`, `client/story/StoryCellArt.java:22-51` (`Piece` gains the
  shape ids), `client/story/StoryCellRenderer.java:56-70` (draw meshes).
- Start with **C1** (smallest change, no dependency on the block decision); move to C2 only if 3a's flexible
  block is chosen and a play-test shows the lighting difference matters.
- `ClosedArtPick.boxes` builds the click box from the cell's 2×2 blocks and `closed.height()`
  (`client/act/ClosedArtPick.java:43-63`). With shapes, take the height from the closed shape's bounds and
  keep the full 2×2 footprint (a generous click target is better than an exact hull).
- Objects standing on solid cells (items on tables: 24 in the real build, BR:31) need the shape plan's
  `topHeight(cell)` so their puppet stands on the table: `client/puppet/PuppetRuntime.java:238-246` adds it
  to `map.y()`.

**Sketch.**

```json
{"key": "TEST_TOWN:10:14", "type": "tile",
 "swap": {"bx": 5, "by": 7, "kind": "cut", "before": 14, "after": 15},
 "start": 14,
 "closed": {"block": 14, "pass": "solid", "height": 2, "art": [12, 12, 12, 12],
            "shapes": [[41, 42], [43, 44], [45, 46], [47, 48]]}}
```

**Open.** Whether a closed *template* (a building-sized swap, none exists in Gen 1) should be supported: the
data model allows it (ids are per block), nothing more is needed now.

---

**B4. Player-made texture packs**

### B4.1 Stable, readable keys

**Answer (facts: what identifies art today).**

- A tile texture is identified by **(atlas image id, palette name, tile index)**; its file is
  `assets/kantobridge/textures/block/tile/<atlas>/<palette>/<tile, 4 digits>.png`, with ids lower-cased and
  any character outside `[a-z0-9_.-]` replaced by `_` (`builder/pack/ResourcePackWriter.java:118, 239-241,
  253-262`). The texture path is therefore **already key-based**; only the *model* and *blockstate* files are
  slot-numbered (`models/block/tile/tile_<shape>_<ff>/<sss>.json`, `ResourcePackWriter.java:153-181`).
- The atlas is an export image id such as `tilesets.<name>` (`gen1-bridge/export/kb_images.lua:53-63`), not a
  tileset id: several tilesets can share one atlas image (`builder/plan/ArtCatalog.java:80-82` keys by
  `tileset.image()`).
- The palette is one SGB palette name per map (`builder/export/ExportMap.java:25`;
  `gen1-bridge/export/kb_export.lua:693-694`); 14 palettes are in use in the real export (ER:104).
- The slot map is the existing index from opaque slots to keys: each entry is
  `{atlas, palette, tile, shape, family, slot}` (`protocol/world/slot_map.schema.json:11-25`); block
  `kantobridge:tile_<shape>_<ff>`, state `hi = slot >> 4`, `lo = slot & 15`
  (`slot_map.schema.json:5`; `world/SlotMap.java:99-123`). It lives in `<world>/kantobridge/slot_map.json`
  and reaches clients as `world.slots` (`docs/PROTOCOL.md:702, 720-725`).
- NPC sheets: `assets/kantobridge/textures/npc/<palette>/<sprite>.png`, sprite ids are engine symbols
  (`ResourcePackWriter.java:205, 244-246`; `docs/PROTOCOL.md:291`). Surf sheet:
  `textures/player/<palette>/surf.png` (`ResourcePackWriter.java:222, 249-251`).
- There are no structure, cell-unit or class keys today.

**Options.**

- **K1. Paths as keys.** A pack overrides files at the generated paths. Works today for textures (no code),
  nothing else. Palette-bound: a maker must supply one file per palette.
- **K2. Symbolic keys resolved through a generated key index** (recommended). Keys are short strings; the
  client writes an index of every valid key with what it stands for.
- **K3. Keep slot numbers.** Rejected: slots are host-specific in multiplayer and change with any export
  change (`docs/PROTOCOL.md:743-748`).

**Recommendation.** K2, with K1 kept as the zero-effort path for plain textures.

| Key | Meaning | Resolves to today |
|---|---|---|
| `tile:<atlas>/<tile>` | A tile of an atlas, **all** palettes and variants (a full-colour replacement) | every `block/tile/<atlas>/*/<tile>` texture |
| `tile:<atlas>/<tile>@<PALETTE>` | The same tile in one palette (Gen 2: one palette set); optional `#<variant>` | one texture; slot(s) via the slot map |
| `sprite:<SPRITE_ID>` (optional `@<PALETTE>`) | A character sheet or character model | `textures/npc/<palette>/<sprite>.png` |
| `cell:<atlas>/<t0>-<t1>-<t2>-<t3>` | A cell-sized unit: the four tiles of one 16-px cell (a tree, a bush, a sign) | `cell` shape keys (B3.4) |
| `struct:<atlas>/<sig>` | A bounded structure by the 16-hex signature of its tile grid | `tmpl` shape keys (B3.4) |
| `place:<MAP_ID>/<n>` | The *n*-th structure of a map (row-major by its north-west tile): a readable alias | resolved through the key index to `struct:` plus a position; lets a pack restyle one placement only |
| `class:<name>` | A tile class for shape rules (`ground, water, ledge, wall, roof, tree, grass, flower, counter, prop, door, void`) | rule lookup in the geometry stage |
| `map:<MAP_ID>` | Per-map dressing and mood | dressing rules (B3.2) |

- The **game** is not part of the key. A manifest names the games its keys target (`targets.games`), because
  Red and Blue share tiles, palettes and swap sites (`docs/PROGRESS.md:662-663`) and one pack should serve
  both. Atlas ids, sprite ids and map ids are the engine's own symbols, so they are stable for a given game.
- Precedence between key kinds for one face or shape: `place:` > `struct:` / `cell:` > `tile:@palette` >
  `tile:` > `class:` > generated base.
- **Where the index lives:** `<gameDir>/kantobridge/keys/<game>.json`, written by the client whenever it
  builds its pack/shape table. For every key: what it resolves to (texture ids, slots, shape ids), its
  palette list, its class, how many maps use it, and for `struct:`/`place:` the size in tiles and blocks. It
  is ROM-derived (tile ids and map ids of the game) and stays on the player's machine.

Files: new `builder/pack/ArtKeys.java` (parse/format, pure), new `client/packs/KeyIndexWriter.java`;
`ResourcePackWriter.texture(SlotMap.Key)` (`ResourcePackWriter.java:239-241`) becomes
`ArtKeys.texturePath(key)` so one function owns the path rule.

**Sketch** (`keys/<game>.json`, synthetic):

```json
{"keys": 1, "game": "red", "exportHash": "<hash>", "slotMapHash": "<hash>", "shapeIndexHash": "<hash>",
 "tiles": {"tilesets.test/3": {"class": "wall", "maps": 3,
           "palettes": {"TEST_PALETTE": {"texture": "kantobridge:block/tile/tilesets.test/test_palette/0003",
                                         "slots": {"full": 12}}}}},
 "sprites": {"SPRITE_TEST_GIRL": {"frames": 6, "palettes": ["TEST_PALETTE"]}},
 "cells": {"tilesets.test/2-2-2-2": {"kind": "round", "count": 150}},
 "structs": {"tilesets.test/0123456789abcdef": {"tiles": [8, 6], "blocks": [8, 6, 6],
             "places": ["TEST_TOWN/1"]}},
 "classes": ["ground", "water", "ledge", "wall", "roof", "tree", "grass", "flower", "counter", "prop", "door", "void"]}
```

**Open.** The palette qualifier for Gen 2 is a palette *set* id, which only exists once a Gen 2 front end does.

> **Integrator note.** From M14 on, generated texture paths carry an art-set segment (`kantobridge:block/<set>/tile/<atlas>/<palette>/<tile>`, [B6.5](#b65-namespacing-now)); the keys themselves do not change.

### B4.2 Layering: pack stacking, a manifest, precedence, validation, fallbacks, reload

**Answer (facts).**

- The generated pack is offered as a **required** built-in pack with default position **TOP**, not
  fixed-position (`client/packs/GeneratedPack.java:46-53`). A required pack that is not yet in the selected
  list is inserted at its default position on every repository reload
  (`mc → net.minecraft.server.packs.repository.PackRepository#rebuildSelected (PackRepository.java:77-87)`,
  `mc → Pack.Position#insert (Pack.java:150-178)`), and the client saves the order of every selected pack that
  is not fixed-position (`mc → net.minecraft.client.Options#updateResourcePacks (Options.java:761-778)`).
  3a owns the exact stacking behaviour (B1.2); what matters here is that position is user-controllable and
  that a file at the same path in a higher pack replaces ours.
- Resources can be read as a **stack**: `ResourceManager#getResourceStack(ResourceLocation)` returns every
  pack's copy, lowest priority first, highest last
  (`mc → net.minecraft.server.packs.resources.ResourceManager#getResourceStack (ResourceManager.java:15)`;
  order: `mc → FallbackResourceManager#getResourceStack (FallbackResourceManager.java:96-136)`, which ends in
  `Lists.reverse`); `listResources(path, filter)` lists files of a folder across namespaces
  (`ResourceManager.java:17`); `Resource#sourcePackId()` names the pack (`Resource.java:37`).
- Every PNG under `textures/block/` of any namespace is stitched into the block atlas: the atlas config's
  first source is a directory lister for `block`
  (`assets/minecraft/atlases/blocks.json` in the client jar;
  `mc → net.minecraft.client.renderer.texture.atlas.sources.DirectoryLister#run (DirectoryLister.java:33-38)`).
  The stitcher lowers the atlas mip level to fit the smallest / least power-of-two-aligned sprite
  (`mc → net.minecraft.client.renderer.texture.SpriteLoader#stitch (SpriteLoader.java:50-81)`), which is why
  our tiles are upscaled to 16×16 (`builder/pack/Textures.java:6-8`).
- Reload: F3+T and our own `PackReload.reload` end in `Minecraft#reloadResourcePacks`
  (`client/packs/PackReload.java:17-22`); after a successful reload Minecraft calls
  `levelRenderer.allChanged()` (`mc → net.minecraft.client.Minecraft (Minecraft.java:931)`), so every chunk
  section is meshed again. We already hook reloads with Fabric's listener API
  (`client/puppet/PuppetRuntime.java:93-103`; `client/fx/SurfSprite.java:82-92`). Fabric API has the
  two-stage form too: `SimpleResourceReloadListener<T>` with
  `CompletableFuture<T> load(ResourceManager, ProfilerFiller, Executor)` and
  `CompletableFuture<Void> apply(T, ResourceManager, ProfilerFiller, Executor)`
  (fabric-resource-loader-v0 1.3.1+5b5275af19 sources → `net.fabricmc.fabric.api.resource.SimpleResourceReloadListener`
  (`SimpleResourceReloadListener.java:45-72`)).

**Options** for where pack content beyond plain textures lives:

- **P1. A separate folder** (`<gameDir>/kantobridge/packs/<name>/…`) with our own loader. Full control, but a
  second pack UI, second ordering, second reload.
- **P2. Inside ordinary Minecraft resource packs**, under our namespace, read through `ResourceManager`
  (recommended). Enable/disable, order, zip support and F3+T come from Minecraft.

**Recommendation.** P2. A pack is a normal resource pack that contains
`assets/kantobridge/kb_pack/<anything>.json` (one or more manifests). On each resource reload a
`SimpleResourceReloadListener` (id `kantobridge:kb_packs`) does:

1. `load` (off-thread): `listResources("kb_pack", path ends with ".json")` and, per location,
   `getResourceStack` → manifests in priority order (lowest first). Parse, validate, merge key by key (a
   higher pack replaces the entry of a lower one for the same key). Read referenced `.vox` files and rule
   files through the same manager. Build `PackOverrides` (immutable).
2. `apply` (client thread): swap the active `PackOverrides`, rebuild or re-validate the shape table cache
   (cache key includes a hash of the merged overrides), clear puppet model caches (as
   `PuppetTextures.clear()` does today, `client/puppet/PuppetTextures.java:43-46`). The re-mesh follows from
   Minecraft's own `allChanged()`.

**Precedence** (lowest to highest): generated base → manifests in resource-stack order → for one key, the
key-kind order of B4.1. Plain file overrides (K1) follow Minecraft's own order; because manifest textures
use the pack's **own paths**, a manifest override works wherever the generated pack sits in the list.

**Validation** (each failure is a counted warning with a code; the entry is skipped and the base is used):

| Check | Code |
|---|---|
| `kantobridge_pack` is a supported format number | `pack.format` |
| `targets.games` contains the current region's game (otherwise the whole manifest is skipped silently) | — |
| key parses and exists in the key index | `pack.unknown_key` |
| texture exists; for block faces it lies under `textures/block/` and is a square power of two ≥ 16 | `pack.texture_missing`, `pack.texture_size` |
| `.vox`: magic and version, one model, size within the target's bounds, voxel count under a cap | `pack.vox_format`, `pack.vox_size` |
| rule: known class, numbers in range | `pack.rule` |
| total quads a model contributes per block under the budget 3a sets | `pack.model_budget` |

Logs carry codes, counts, pack ids and keys only (keys are engine symbols).

**Fallbacks.** Missing or invalid entry → next lower pack → generated base → plain cube (today's look).
A pack can never leave a hole: every shape id always has the base mesh.

**Sketch** (manifest; synthetic ids):

```json
{
  "kantobridge_pack": 1,
  "id": "hd_test", "name": "HD Test", "version": "1.0.0",
  "targets": {"games": ["red", "blue"], "geometry": 1},
  "textures": {
    "tile:tilesets.test/4": "kantobridge:block/pack/hd_test/roof",
    "tile:tilesets.test/7@TEST_PALETTE": "kantobridge:block/pack/hd_test/flower",
    "sprite:SPRITE_TEST_GIRL": "kantobridge:textures/npc_pack/hd_test/girl.png"
  },
  "models": {
    "sprite:SPRITE_TEST_BALL": {"vox": "kantobridge:kb_models/hd_test/ball.vox", "voxelsPerBlock": 8},
    "struct:tilesets.test/0123456789abcdef": {"vox": "kantobridge:kb_models/hd_test/house.vox",
                                              "voxelsPerBlock": 16, "origin": [0, 0, 0], "turn": 0},
    "cell:tilesets.test/2-2-2-2": {"vox": "kantobridge:kb_models/hd_test/tree.vox", "voxelsPerBlock": 8}
  },
  "rules": {
    "class:tree": {"style": "hull", "trunk": 1},
    "tile:tilesets.test/4": {"class": "roof"},
    "struct:tilesets.test/0123456789abcdef": {"bands": {"roofRows": 2}}
  },
  "characters": {"style": "carved", "sprites": {"SPRITE_TEST_BALL": "model"}},
  "dressing": {"map:TEST_HOUSE": {"ceiling": "dominant_wall", "headroom": 4}}
}
```

**Sketch** (the listener; every call below was checked against the sources named in the facts above, plus
`mc → Resource#openAsReader (Resource.java:49-51)`, `ResourceManager#listResourceStacks (ResourceManager.java:19)`
and Fabric's `ResourceManagerHelper.get(PackType)` / `registerReloadListener(IdentifiableResourceReloadListener)`
(`ResourceManagerHelper.java:51, 69` in the fabric-resource-loader-v0 sources); not compiled):

```java
// client/packs/KbPackReload.java
public final class KbPackReload implements SimpleResourceReloadListener<PackOverrides> {
    @Override public ResourceLocation getFabricId() {
        return ResourceLocation.fromNamespaceAndPath(KantoBridge.MOD_ID, "kb_packs");
    }
    @Override public CompletableFuture<PackOverrides> load(ResourceManager manager, ProfilerFiller profiler, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            PackOverrides.Builder out = new PackOverrides.Builder(ClientRegions.currentGame());   // no region: empty
            Map<ResourceLocation, List<Resource>> stacks =
                    manager.listResourceStacks("kb_pack", id -> id.getPath().endsWith(".json"));
            for (Map.Entry<ResourceLocation, List<Resource>> file : new TreeMap<>(stacks).entrySet()) {  // sorted
                for (Resource resource : file.getValue()) {                                   // lowest priority first
                    try (Reader reader = resource.openAsReader()) {
                        out.merge(KbPackManifest.parse(reader), resource.sourcePackId(), manager);  // validates, counts
                    } catch (IOException | RuntimeException e) {
                        out.warn("pack.io");
                    }
                }
            }
            return out.build();
        }, executor);
    }
    @Override public CompletableFuture<Void> apply(PackOverrides data, ResourceManager manager, ProfilerFiller profiler, Executor executor) {
        return CompletableFuture.runAsync(() -> ClientShapes.install(data), executor);  // swap one volatile reference
    }
}
// client init: ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(new KbPackReload());
```

Files: new `client/packs/KbPackManifest.java` (pure parser/validator), `client/packs/PackOverrides.java`,
`client/packs/KbPackReload.java` (the listener); `client/KantoBridgeClient.java:25-64` registers it; a schema
`protocol/packs/kb_pack.schema.json` for tests and pack makers.

**Open.** Whether the generated pack should keep default position TOP (`GeneratedPack.java:50-53`): with TOP,
a newly added user pack that overrides *files* must be moved above it by hand. 3a's B1.2 answer decides.

> **Integrator note.** The open point is settled in [4.8](#48-the-texture-pack-format): `TOP` until migration step 1 of the block approach, below player packs after it.

### B4.3 What a pack can contain

**Answer (facts about `.vox`,** from the format description I fetched:
<https://raw.githubusercontent.com/ephtracy/voxel-model/master/MagicaVoxel-file-format-vox.txt> and its
extension <https://raw.githubusercontent.com/ephtracy/voxel-model/master/MagicaVoxel-file-format-vox-extension.txt>**).**

- File: the 4 bytes `VOX ` then an int version; the description states version 150.
- Chunks: 4-byte id, int size of the chunk's content, int size of its children, content, children.
- `MAIN` holds: an optional `PACK` chunk ("if it is absent, only one model in the file; only used for the
  animation in 0.98.2"), then one `SIZE` + `XYZI` pair per model, then an optional `RGBA`.
- `SIZE`: three ints: size x, size y, size z, and the description marks **z as the gravity direction**
  (z is up).
- `XYZI`: an int voxel count, then per voxel four bytes `(x, y, z, colorIndex)`. Each coordinate is one byte,
  so a model spans **at most 256 voxels per axis** (my inference from the one-byte fields; the text does not
  state the limit in words).
- `RGBA`: 256 entries of four bytes (R, G, B, A). Notice in the description: colours 0–254 map to palette
  indices 1–255 (`palette[i + 1] = ReadRGBA()`), so **index 0 is not a colour** (empty). If `RGBA` is absent
  a default palette applies.
- Extension: several `SIZE`/`XYZI` pairs mean several models (model id = stored order); a scene graph of
  `nTRN` (transform, with a rotation packed in one byte and an integer translation), `nGRP` and `nSHP` nodes
  places them; `MATL` materials, `LAYR` layers, `IMAP` (a 256-entry index map) and `NOTE` also exist.
- ⚠ Not confirmed: which way MagicaVoxel's x and y axes map to "east" and "south" on screen (the description
  only fixes z as up). We must define our own mapping and give pack makers a calibration model.

**What a pack may contain (recommendation).**

| Content | Form | Limits and fallbacks |
|---|---|---|
| **Textures at any resolution** | (a) file override at the generated path (K1); (b) manifest `textures` entry to the pack's own file | Block faces: under `textures/block/`, square, power of two, ≥ 16 (mip levels: `SpriteLoader.java:50-81`). Animated: a vertical strip + `.mcmeta` as today (`Textures.java:81-109`). A palette-free `tile:` override must be full colour; a `@palette` override colours one palette only. Character sheets: any integer multiple of the generated layout (64×32 for Gen 1, `Textures.java:22-24`) |
| **Shape-rule overrides** | manifest `rules`: per `class:` (style, trunk, depth, lip height, roof style), per `tile:` (assign a class: the "pin"), per `struct:` (a band table: which rows are roof, eave, awning) | Closed vocabulary; numbers clamped; a rule that fails validation is ignored. This is the user-authored replacement for a shipped table (B3.1 option O2) |
| **Hand-made voxel models** | manifest `models`: `.vox` per `sprite:`, `cell:`, `struct:` or `place:` | First model of the file only (others ignored with a warning) in the first version; `voxelsPerBlock` 8, 16 or 32; size ≤ the target's bounds plus an overhang allowance; the model is sliced per block and greedy-meshed like a generated template, so it obeys the same per-block quad budget |
| **Dressing** | manifest `dressing` per `map:` or per class of map (ceiling tile, headroom, fog colour, particles, canopy on/off) | Values from a closed list; geometry dressing is client-visual only |
| **Character choices** | manifest `characters`: default style and per-sprite style or model | The player's own setting wins over a pack's default unless the setting is "pack decides" (B5.5) |

**Mapping `.vox` to our grid.**

- Scale: our block is 16 sub-voxels; one Game Boy pixel is 2 sub-voxels (B3.1). `voxelsPerBlock = 16` maps
  one voxel to 1/16 block (native Minecraft model resolution); `8` maps one voxel to one Game Boy pixel
  (the resolution of generated shapes); `32` is allowed for detail and still exact on the quad level because
  quads carry float positions.
- Extent: 256 voxels per axis = 16 blocks at 16/block, 32 blocks at 8/block. Our tallest generated column is
  6 blocks plus a roof rise (`builder/plan/HeightRules.java:27-28`), and structures are a few cells wide, so
  one model covers any single structure; larger ones are rejected with `pack.vox_size`.
- Axes (our definition, to be printed in the pack documentation and testable with a calibration model):
  vox **z → Minecraft +Y (up)**; vox **x → +X (east)**; vox **y → −Z (north)**, so that a model viewed from
  MagicaVoxel's default front shows the face that will look **south**, the side the game draws. `turn`
  (0/90/180/270) and `flip` in the manifest correct a model built the other way round.
- Origin: the model's minimum corner sits at the structure's north-west-bottom corner (`origin` offsets it,
  in voxels); for sprites the footprint centre sits on the cell centre at feet level.
- Colour: no texture is needed. Each merged quad carries its palette colour as a vertex colour over a plain
  white sprite (block side: 3a confirms per-vertex colour in the emitter; entity side:
  `VertexConsumer#setColor(int)`, `VertexConsumer.java:41-43`). Palette index 0 = empty; alpha < 255 is
  treated as opaque in the first version.
- Meshing: remove hidden faces, then greedy-merge coplanar faces of equal colour; slice at block boundaries.

Files: new `client/shape/vox/VoxFile.java` (pure parser: `MAIN`, `SIZE`, `XYZI`, `RGBA`; skips unknown
chunks by their declared sizes), `client/shape/vox/VoxMesher.java`; both pure and JUnit-tested.

**Open.** Multi-model `.vox` scenes (the `nTRN` graph) and materials (emissive for lamps) are left for a
later version. The axis mapping needs one real MagicaVoxel export by the user to confirm.

### B4.4 Multiplayer

**Answer (facts).** Art is client-local by design: each client builds its own pack; the server only tracks
`texture.status` (`docs/PROTOCOL.md:746-753`; `server/TextureStatusService.java`). Collision and story logic
use server-side blocks and each player's `StoryState`, never models (`docs/PLAN.md:698-709`).

- **What other players see:** their own client's art: their own generated base plus their own packs. Two
  players standing side by side can see different textures, shapes and character styles; positions,
  collision and story cells are identical because those are blocks and state, not art.
- **The server is unaffected:** it never loads `kb_pack` manifests (client resources), never needs shape
  geometry (B3.5), and no pack content is sent to it. `texture.status` keeps reporting whether the *base*
  pack matches the world (`slotMapHash`, and with shapes `shapeIndexHash`); user packs are not reported.
- **What a pack cannot do:** change collision, outlines, story cells, warps or what the crosshair hits
  (those follow server-known blocks). A pack can make a wall *look* open; the hidden game still refuses the
  step. That is acceptable and is the same as any Minecraft resource pack.
- **Server resource packs:** a server operator could push a Minecraft server resource pack containing
  `kb_pack` manifests; it would load like any other pack. It must then contain only the operator's own
  files (B4.5). No code needed; mention it in the pack documentation.

**Recommendation.** No protocol change. One optional addition for support: an F7 line and a
`texture.status` field `packs` (a count) so a host can see that a guest runs overrides when debugging
"my tree looks different" reports.

**Open.** None.

### B4.5 Legal and data rules

**Answer / recommendation.**

1. **We ship no pack and no rule table.** The repository contains only code, schemas and synthetic test
   data (CLAUDE.md "Hard rules"; the stand-ins must not contain ROM data).
2. **The generated base is ROM-derived and stays local**: `<gameDir>/kantobridge/resourcepack/`,
   `…/shapes/`, `…/keys/`, `…/export/` (`docs/PROTOCOL.md:665-667, 727-728`). In development those live under
   `local/minecraft/` and are git-ignored (CLAUDE.md "Build and run").
3. **Packs are user content.** A pack that contains only the maker's own drawings, models and rule files is
   theirs to share. A pack must not contain generated files: the template export (B4.6) marks every
   generated file, and the documented "prepare for sharing" step leaves them out.
4. **Rule files that list tile ids** (a class per tile, a band table per structure) describe the game's
   tilesets. That is the same kind of data as Dramaless Shape's profile (B3.1); it is not art, but it is
   the user's or a community's work, not ours, and we do not bundle it.
5. **Third-party references stay ideas only**: no code, table or asset of Dramaless Shape or Kanto in First
   Person in the mod or in any example pack (`docs/REFERENCES.md:58-64`). Using Dramaless's table remains a
   separate user decision.
6. **Logs and reports:** counts, codes, ids and hashes only (`docs/PLAN.md` §0.3); never file contents,
   never pixel data.

**Open.** A short `docs/PACKS.md` should carry rules 3–5 for pack makers when the feature is built.

### B4.6 Tooling for pack makers

**Answer (facts).** Nothing exists today beyond the generated pack folder and its `kantobridge.json`
(`ResourcePackWriter.java:228-233`).

**Options.** (a) Documentation only. (b) A **template export**: a button that writes a ready-to-edit pack
folder with the key index and reference material. (c) An external editor. (c) is out of scope.

**Recommendation.** (b), as a client action next to "Create Kanto world" (`client/world/KantoWorldScreen.java`)
and as a client command, writing to `<gameDir>/kantobridge/pack-template/<game>/`:

```
pack-template/<game>/
  pack.mcmeta                              pack_format 34 (as ResourcePackWriter.PACK_FORMAT)
  README.txt                               what may be shared; axis and scale conventions
  assets/kantobridge/kb_pack/template.json a manifest skeleton with every section empty
  keys.json                                the key index (B4.1): every valid key, its class, size, usage counts
  reference/                               GENERATED FROM YOUR ROM - DO NOT SHARE
    textures/…                             the base textures by key, for tracing over
    models/<key>.vox                       each generated structure / cell unit / sprite as .vox, to edit
    calibration.vox                        an asymmetric shape to check axes and scale
  generated.json                           SHA-256 of every file under reference/ (to exclude them when sharing)
```

- Writing `.vox` is the inverse of B4.3's reader (same pure class), so a maker can open a generated house,
  improve it and save it under `assets/kantobridge/kb_models/…`.
- A **pack check** (F7 line plus a client command): overrides applied / skipped by code, unknown keys,
  budget violations. Counts only.
- A **"prepare for sharing"** command copies a pack folder while dropping every file whose hash is listed in
  `generated.json`.

Files: new `client/packs/PackTemplateWriter.java`, `client/shape/vox/VoxFile.java` (write side),
documentation `docs/PACKS.md`.

**Open.** Whether the reference textures and models should be exported at all (they are copies of ROM-derived
art on the player's own disk, like the generated pack itself); the README and `generated.json` are the
safeguards. A user decision.

---

**B5. Characters: three styles**

### B5 (common ground for B5.1–B5.6): verified signatures for runtime character geometry

All from the Minecraft 1.21.1 sources (Mojang names); the inner-class signatures were also confirmed with
`javap -p` on the Loom-mapped client jar.

| What | Signature | Source | Access |
|---|---|---|---|
| Immediate vertices | `VertexConsumer addVertex(float x, float y, float z)`; `setColor(int r, int g, int b, int a)`; `setUv(float u, float v)`; `setUv1(int, int)`; `setUv2(int, int)`; `setNormal(float, float, float)`; defaults `setColor(int argb)`, `setLight(int packedLight)`, `setOverlay(int packedOverlay)`, `addVertex(PoseStack.Pose, float, float, float)`, `setNormal(PoseStack.Pose, float, float, float)`; bulk `addVertex(float x, float y, float z, int color, float u, float v, int packedOverlay, int packedLight, float nx, float ny, float nz)` | `mc → com.mojang.blaze3d.vertex.VertexConsumer (VertexConsumer.java:16-55, 136-148)` | public (vanilla) |
| Render types | `RenderType.entityCutoutNoCull(ResourceLocation)`, `entityCutout(ResourceLocation)`, `entitySolid(ResourceLocation)`, `entityTranslucent(ResourceLocation)` | `mc → net.minecraft.client.renderer.RenderType (RenderType.java:812-848)` | public |
| Renderer | `EntityRenderer#shouldRender(T, Frustum, double, double, double)`; `render(T, float entityYaw, float partialTick, PoseStack, MultiBufferSource, int packedLight)`; `getTextureLocation(T)` | `mc → net.minecraft.client.renderer.entity.EntityRenderer (EntityRenderer.java:52, 91, 181)` | public |
| Mesh builder | `new MeshDefinition()`; `MeshDefinition#getRoot()`; `PartDefinition#addOrReplaceChild(String name, CubeListBuilder cubes, PartPose pose)`; `CubeListBuilder.create()`, `#texOffs(int, int)`, `#mirror()`, `#addBox(float originX, float originY, float originZ, float dimX, float dimY, float dimZ)` (+ overloads with `CubeDeformation`, visible faces); `PartPose.offset(float, float, float)`, `PartPose.offsetAndRotation(…)`; `LayerDefinition.create(MeshDefinition, int texWidth, int texHeight)`; `LayerDefinition#bakeRoot()` → `ModelPart` | `MeshDefinition.java:9-15`; `PartDefinition.java:26-34, 36-50`; `CubeListBuilder.java:20-33, 96-117, 119-140, 249-251`; `PartPose.java:25-35`; `LayerDefinition.java:17-23` (all under `mc → net.minecraft.client.model.geom[.builders]`) | public. Baking at run time is what vanilla itself does: `EntityModelSet#bakeLayer` returns `layerDefinition.bakeRoot()` (`EntityModelSet.java:15-20`) |
| Model part | `new ModelPart(List<ModelPart.Cube> cubes, Map<String, ModelPart> children)`; fields `x, y, z, xRot, yRot, zRot, xScale, yScale, zScale, visible`; `setPos`, `setRotation`, `getChild(String)`; `render(PoseStack, VertexConsumer, int packedLight, int packedOverlay)` and `render(…, int color)` | `mc → net.minecraft.client.model.geom.ModelPart (ModelPart.java:25-43, 89-130)` | public. The `cubes` and `children` **fields** are public only through Cobblemon's transitive access widener (`cobblemon-common.accesswidener` lines 11-12 in the Cobblemon 1.8.1 jar) |
| Cube | `new ModelPart.Cube(int texU, int texV, float originX, float originY, float originZ, float dimX, float dimY, float dimZ, float growX, float growY, float growZ, boolean mirror, float texScaleU, float texScaleV, Set<Direction> visibleFaces)`; UVs are a fixed **box unwrap** from `(texU, texV)` | `ModelPart.java:209-301`; javap | Constructor public. `Cube.polygons` (field) and the classes `ModelPart.Polygon`, `ModelPart.Vertex` are accessible only through wideners: Fabric API's `fabric-transitive-access-wideners-v1` 6.2.0 (lines 31-32: both classes) and Cobblemon's (line 89: `polygons`; line 90: `Polygon`) |
| Units | A cube coordinate and a part offset are in 1/16 block (`pos / 16` when compiled; `translate(x / 16, …)`) | `ModelPart.java:152-161, 313-318` | — |
| Humanoid walk | legs `xRot = cos(limbSwing × 0.6662 [+ π]) × 1.4 × limbSwingAmount / f`; arms `cos(limbSwing × 0.6662 [+ π]) × 2 × limbSwingAmount × 0.5 / f` | `mc → net.minecraft.client.model.HumanoidModel#setupAnim (HumanoidModel.java:164-169)` | — |
| Run-time textures | `NativeImage.read(InputStream)`, `new NativeImage(int w, int h, boolean calloc)`, `getPixelRGBA(int, int)`, `setPixelRGBA(int x, int y, int abgrColor)`; `new DynamicTexture(NativeImage)`, `DynamicTexture#upload()`; `TextureManager#register(ResourceLocation, AbstractTexture)`, `#release(ResourceLocation)` | `mc → com.mojang.blaze3d.platform.NativeImage (NativeImage.java:54, 101, 199, 213)`; `mc → net.minecraft.client.renderer.texture.DynamicTexture (DynamicTexture.java:22, 44)`; `mc → net.minecraft.client.renderer.texture.TextureManager (TextureManager.java:63, 159)` | public. We already use a `DynamicTexture` for the mirror screen (`docs/PLAN.md:770-771`) |
| Fabric layer registry | `EntityModelLayerRegistry.registerModelLayer(ModelLayerLocation, TexturedModelDataProvider)`; the provider's `createModelData()` returns a `LayerDefinition` | fabric-rendering-v1 5.2.1+f44c2eea19 sources → `net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry (EntityModelLayerRegistry.java:35, 50-56)` | Static registration by layer id: **not suited** to per-sprite models made at run time; baking directly (row "Mesh builder") is |

**Two consequences that shape the options below.**

1. A `ModelPart.Cube` can only use the box-unwrap UV layout. It cannot show "texel (x, y) of the sprite's
   front frame" on an arbitrary face unless a texture in unwrap layout is generated for it. Carved figures
   have hundreds of small faces that each need their own sprite texel, so they are far simpler as **direct
   `VertexConsumer` quads with free UVs** into the existing sheet. Rigged figures have six boxes, so a small
   generated unwrap texture per sprite is practical and the vanilla `ModelPart` path fits.
2. Either way the per-frame work is CPU vertex submission: `ModelPart.Cube#compile` transforms and pushes
   every vertex each frame (`ModelPart.java:303-321`), exactly as our own quad code does
   (`client/puppet/PuppetRenderer.java:107-114`). There is no speed difference between the two paths; the
   quad count is what costs.

### B5.1 Inputs

**Answer (facts).**

- Export sprites: a 16-px-wide vertical strip of 16×16 frames per sprite id; frame order 0 stand down,
  1 stand up, 2 stand left, 3 walk down, 4 walk up, 5 walk left; right is left mirrored
  (`protocol/export/sprite.schema.json:12-19`; `gen1-bridge/export/kb_images.lua:77-94`). Shade 0 is
  transparent (`builder/pack/Textures.java:150-156`). The real export has 73 sprites (ER:113, 126).
- Sheets can be shorter: 3 frames (standing only) or 1 frame (`Textures.java:136-149`). The split by frame
  count is **not** in our reports (the 40/20/12 split in `refs/_research/P3_gen1_data_export.md:298-302`
  comes from the engine's manifest, not from the user's export): report field `sprites_by_frames`.
- Generated textures: one 64×32 sheet per (palette, sprite) the maps need; 296 in the real build (BR:38);
  the player's surf sheet per water palette (`ResourcePackWriter.java:209-226`).
- Motion data per puppet: cell, facing, step frames/progress, "moving" (march), kind
  (`person, item, boulder, static, trainer`) (`client/puppet/Puppet.java:23-40, 127-160`;
  `client/puppet/PuppetManager.java:34`).
- Other families: Gen 2 is 16×16 with separate standing/walking tile sets and its own sprite palettes
  (`refs/gen1recomp/src/world/gen2/OamFacings.lua:23-64`; `Palettes.lua:214-259`); Gen 3 sheets have their
  own frame size (people are 16×32) and two walk frames per direction plus running
  (`refs/gen1recomp/src/core/game3/ow_sprites.lua:17, 21-28, 84-97`).

**Options** for where silhouettes come from: (a) the export bundle (`bundle.sprite(id)` → `Png.decode`,
`builder/export/ExportBundle.java:224-226`): palette-free shades, always 16×16; (b) the **resolved** texture
through the resource manager (after pack stacking): follows a pack's higher-resolution sheet.

**Recommendation.** One neutral input type, filled by (b) with (a) as the fallback when no pack is loaded:

```java
// client/puppet/style/SpriteFrames.java (pure)
record SpriteFrames(int frameW, int frameH,
                    Map<Pose, Map<Dir, Frame>> frames,     // Pose: STAND, WALK (later WALK2, RUN)
                    boolean mirrorRight, Set<Dir> alternateMirror) {}
record Frame(BitSet mask, int u0, int v0) {}               // mask: opaque texels; u0,v0: position in the sheet
```

Geometry is built from masks only (palette-free), cached per sprite id (not per palette): 73 sprites, not
296 sheets. Frames larger than 32×32 are down-sampled (nearest) for geometry; the texture stays as it is.

**Open.** None.

### B5.2 Thick cards

**Answer.** Today's card is one quad (`PuppetRenderer.java:64-68`). A thick card is the same frame with
depth: a front face, a back face and an edge strip around the silhouette.

**Algorithm** (pure; one mesh per sprite, pose and column, cached):

```
depth d = 1 or 2 sprite pixels (1 px = 1/8 block at puppet scale: PuppetRenderer.java:29-31)
front: one quad over the whole frame at z = +d/2, uv = the frame rect           (alpha test cuts the shape)
back:  one quad at z = -d/2, uv mirrored in u
edges: for every row y, every maximal run [x0, x1) of opaque texels whose upper neighbour is transparent:
           a quad (x0..x1) × (z = -d/2 .. +d/2) at the top of row y, normal +Y,
           uv: u from x0 to x1, v pinned to the centre of row y   -> each texel's colour is drawn across the depth
       likewise for lower neighbours (normal -Y), and per column for left/right neighbours (normal -X/+X)
```

Quad count: 2 + boundary runs. For a compact 16×16 figure that is a few dozen quads (⚠ estimate; the exact
number per sprite is a counter the build should report); the worst possible mask (a checkerboard) gives 514.

**Options** for orientation (frame choice stays `FacingFrames.column`, `FacingFrames.java:32-42`):

- **T-a. Yaw billboard, as today.** The slab always faces the camera; thickness shows as top edges when
  looking down. No popping. Cheapest to adopt.
- **T-b. Quadrant snap.** The slab faces the nearest of the four world directions toward the camera, so it
  reads as an object standing on the grid; it turns 90° at the diagonals (visible pop).
- **T-c. World-oriented.** Front = the NPC's "down" frame, back = "up" frame, fixed to the NPC's facing; seen
  from the side it is 1–2 px thin.

**Renderer.** Direct quads as today: `buffers.getBuffer(RenderType.entityCutoutNoCull(texture))`, then for
each cached quad four `addVertex(pose, x, y, z).setColor(-1).setUv(u, v).setOverlay(NO_OVERLAY)
.setLight(packedLight).setNormal(pose, nx, ny, nz)` calls (same chain as `PuppetRenderer.java:107-114`).
Keep the "up" normal on the front and back faces (today's choice, `PuppetRenderer.java:113`). Inference:
entity shading uses two fixed diffuse light directions
(`mc → com.mojang.blaze3d.platform.Lighting#setupLevel (Lighting.java:22-23)`), so a true normal would make
the card's brightness change as it turns with the camera. Edges take their real normals.

```java
// client/puppet/PuppetRenderer.java, replacing lines 56-69 (sketch; not compiled)
CharacterStyle style = runtime.styles().styleFor(puppet);                 // cached per sprite; applies the fallback chain
float[] mesh = style.mesh(puppet, column, walking, mirrored);             // 20 floats per quad: 4 × (x, y, z, u, v)
float[] normals = style.normals(puppet, column, walking, mirrored);       // 3 floats per quad
poseStack.pushPose();
poseStack.mulPose(Axis.YP.rotation(style.worldOriented()
        ? -puppet.facing().yaw() * Mth.DEG_TO_RAD      // a real object: local +Z (its front) along the NPC's facing;
                                                       //   derived from yaw 0 = south (FacingFrames.java:29-30);
                                                       //   ⚠ sign to be confirmed in the play-test
        : Mth.PI - cameraYaw * Mth.DEG_TO_RAD));       // a card: local +Z toward the camera (today, PuppetRenderer.java:62)
PoseStack.Pose pose = poseStack.last();
VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(this.getTextureLocation(entity)));
for (int q = 0, n = mesh.length / 20; q < n; q++) {
    for (int v = 0; v < 4; v++) {
        int i = q * 20 + v * 5;
        consumer.addVertex(pose, mesh[i], mesh[i + 1], mesh[i + 2]).setColor(-1).setUv(mesh[i + 3], mesh[i + 4])
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(packedLight)
                .setNormal(pose, normals[q * 3], normals[q * 3 + 1], normals[q * 3 + 2]);
    }
}
poseStack.popPose();
```

**Cost.** Per puppet per frame: 4 × (2 + runs) vertices; at ~50 runs about 200 vertices, the size of a
vanilla zombie model (6 boxes × 24). Worst case on screen: one `npc.set` holds at most 64 NPCs
(`PuppetManager.java:78`) and culling removes everything beyond 48 blocks (`PuppetCulling.java:17-18`).
Memory: one float array per (sprite, pose, column), 20 floats per quad.

**Quality checks / fallback.** More than 256 quads, or an empty mask → today's flat card.

**Recommendation.** T-a with d = 2 px as the first style to ship (it cannot look wrong: from the front it is
pixel-identical to today's card). Changes: `client/puppet/PuppetRenderer.java:49-73` delegates to a
`CharacterStyle` (B1.4 sketch); new pure classes `client/puppet/style/SpriteFrames.java`,
`ThickCardMesh.java`; `client/fx/SurfSprite.java:159-183` uses the same style object.

**Open [measure].** Experiment "64 puppets": a dev world with 64 synthetic puppets inside 48 blocks; record
frame time (F3 frame graph average and 1% low) for flat / thick; pass if thick costs < 0.3 ms per frame over
flat at 64 puppets and allocates nothing per frame (allocation counter in a JFR recording). The user runs it;
agents never start the client.

### B5.3 Carved voxel figures

**Answer.** A figure is the **visual hull** of the three drawn views of one pose (front = "down" frame,
back = "up" frame, side = "left" frame), in a grid of frameW × frameH × frameW voxels, painted from the views.

**Algorithm** (pure; replaces the §9 sketch):

```
inputs for pose p in {STAND, WALK}:  F = mask(down), B = mask(up), L = mask(left)      (W x H each)
axes: x = left->right as seen from the front, y = row from the top, z = 0 at the front .. W-1 at the back
      in the side view the figure faces screen-left, so side column s = z

hull(x, y, z) = F[x][y] and L[z][y] and (useBack(y) ? B[W-1-x][y] : true)
useBack(y)    = the back row's extent matches the front row's (mirrored) within 1 px; otherwise that row
                ignores B (a back view that is drawn slightly differently must not erode the body)

paint, as UVs into the sprite's sheet (so palettes, resolutions and packs apply for free):
  face toward the front  (-z): texel (x, y)       of the DOWN column
  face toward the back   (+z): texel (W-1-x, y)   of the UP column
  side faces (-x and +x):      texel (z, y)       of the LEFT column (the RIGHT column is its mirror)
  top / bottom faces:          texel (x, y)       of the DOWN column, stretched over the run in z

mesh: drop faces between two hull voxels; greedy-merge coplanar faces whose texels are neighbours in the same
      frame (a merged rectangle maps to a rectangle of the frame, so merging stays exact)
walk: carve WALK from frames 3, 4, 5; alternate steps mirror the mesh in x for up/down walkers,
      as Puppet.mirroredWalk does for the flat frame (Puppet.java:155-160)
orientation: a real object: rotate by the NPC's own facing (no billboarding, no FacingFrames)
```

**Quality checks and the per-sprite fallback chain** (all integer tests; results cached per sprite):

| # | Check | Fails when | Then |
|---|---|---|---|
| 1 | Views available | the sheet has fewer than 3 frames | single-frame sprites of kind `item` or `boulder` (`PuppetManager.java:34`): **revolve** instead: `hull = F[x][y] and F[z][y]` (the front outline used as the side outline; right for balls and boulders). Anything else: thick card |
| 2 | Row agreement | more than 1 row has pixels in F but none in L (or the reverse) | thick card |
| 3 | Projection fidelity | the hull's front projection differs from F, or its side projection from L, in more than 3% of the mask pixels | retry once without the back constraint; then thick card |
| 4 | Thin parts | more than 3 rows where the hull is 1 voxel thick in x or z although F and L are both ≥ 2 px wide there | thick card |
| 5 | Budget | more than 600 merged quads | thick card |

**Runtime path.** Direct `VertexConsumer` quads from a cached array (B5 consequence 1), render type
`entityCutoutNoCull` on the existing sheet. No generated texture, no `ModelPart`.
Rejected alternative: building `ModelPart.Cube`s and replacing their `polygons` with hand-made
`ModelPart.Polygon`s: it needs fields and classes that are only reachable through Cobblemon's and Fabric's
access wideners (table above), allocates a `Vector3f` per cube per frame (`ModelPart.java:305`), and is not
faster.

**Cost (⚠ estimates, to be measured).** A 16³ grid has at most 4,096 voxels; a figure's shell is a few
hundred texel faces per side. After merging, expect roughly 250–400 quads (1,000–1,600 vertices) per figure
and pose. Twenty figures in view ≈ 30,000 vertices per frame; the theoretical worst case of 64 is about
100,000. Carving itself is microseconds per sprite and happens once.

**Recommendation.** Offer carved figures as an opt-in style with the fallback chain above; do not make it
the default before the user has looked at it (the aesthetic risk is the one Dramaless names, B5.5).
Changes: new pure classes `client/puppet/style/HullCarver.java`, `QuadMerger.java` (shared with the terrain
geometry's greedy mesher), `CarvedStyle.java`; `PuppetRenderer.render` (`PuppetRenderer.java:49-73`) rotates
by `puppet.facing()` instead of the camera for world-oriented styles.

**Open [measure].** Same experiment as B5.2 with carved figures; pass if 20 figures cost < 0.5 ms per frame
and 64 cost < 1.5 ms on the dev machine; otherwise lower the quad budget in check 5.

### B5.4 Rigged figures

**Answer.** Split the front frame into head, torso, two arms and two legs by its rows and columns, give each
part a box whose depth comes from the side frame, texture the boxes from the frames, and swing the limbs
like a Minecraft mob.

**Splitting heuristics** (pure, on the STAND masks; `top`/`bottom` = first/last non-empty row of F):

```
legs:   scan up from `bottom` while the row of F has two runs with a gap between them -> legTop
        (no split found: one "skirt" block of the lowest 3 rows, which does not swing)
head:   width(y) = run length of F at row y; headWide = row of the maximum width in the top half;
        neck = first row below headWide where width(y) is a local minimum and y <= top + frameH * 5/8
        head = rows top .. neck-1
torso:  rows neck .. legTop-1;  core = the run of F at row legTop-1 (the hips), extended upward
arms:   in the torso rows, the columns left and right of the core (if none: no arm parts)
depth:  for each part, the extent of L over the part's rows
humanoid = sheet has walk frames and a neck was found and head height is between 1/4 and 2/3 of the figure
```

**Building the model at run time** (verified signatures in the table above):

```java
MeshDefinition mesh = new MeshDefinition();
PartDefinition root = mesh.getRoot();
// units: 1/16 block; one sprite pixel = 2 units. Pivots at the joints.
root.addOrReplaceChild("head",  CubeListBuilder.create().texOffs(u0, v0).addBox(-w/2f, -h, -d/2f, w, h, d),
                       PartPose.offset(0f, neckY, 0f));
root.addOrReplaceChild("left_leg", CubeListBuilder.create().texOffs(u5, v5).addBox(…), PartPose.offset(…));
// … torso, right_leg, left_arm, right_arm
ModelPart model = LayerDefinition.create(mesh, texW, texH).bakeRoot();     // cached per sprite id
```

- **Texture:** a small unwrap sheet per (sprite, palette), made once from the resolved NPC sheet: for each
  box, copy the front crop to the box's front face region, the back crop (mirrored) to its back region, the
  side crop to both side regions, the crop's top row to the top region and its bottom row to the bottom
  region (layout: `ModelPart.java:256-264`). Built with `NativeImage`, wrapped in a `DynamicTexture`,
  registered under `kantobridge:dynamic/rig/<palette>/<sprite>` with `TextureManager#register`, released on
  resource reload (`TextureManager#release`). About 64×64 texels per sheet; 296 sheets ≈ 5 MB on the GPU.
- **Animation:** phase from the puppet's step: `phase = 2π × stepElapsed / stepFrames` while stepping,
  `marchClock`-based while marching (`Puppet.java:142-153`); `leftLeg.xRot = cos(phase) × A`,
  `rightLeg.xRot = cos(phase + π) × A`, arms opposite with a smaller amplitude: the vanilla pattern
  (`HumanoidModel.java:164-169`), with A chosen by eye.
- **Render:** `model.render(poseStack, buffers.getBuffer(RenderType.entityCutoutNoCull(texture)), packedLight,
  OverlayTexture.NO_OVERLAY)` (`ModelPart.java:110-112`), after rotating to the NPC's facing and scaling
  nothing (the box units already are pixels × 2).

**Cost.** 6 boxes × 6 faces × 4 = 144 vertices per figure per frame: the cheapest of the three 3D styles.

**Quality checks / fallback.** Not humanoid by the test above, or the rig's front projection (boxes)
overlaps F by less than 85% → carved (if enabled) → thick card. Non-walking sprites (3-frame and 1-frame
sheets) are never rigged.

**Recommendation.** Third in order of work. It is the cheapest per frame but the most heuristic: 16×16
drawings have 1–2-px arms and 2–3-px legs, so the result is a small blocky doll wearing a smeared sprite.
Worth offering because it matches Minecraft's own look; never the default without a play-test. Changes: new
`client/puppet/style/RigSplitter.java` (pure), `RigTextures.java`, `RiggedStyle.java`.

**Open.** The amplitude and whether arms swing at all are play-test matters. Gen 3's 16×32 people give the
splitter twice the rows and should rig better (inference).

### B5.5 Settings, consistency, packs, and Dramaless Shape's argument for flat cards

**Answer (facts).**

- The client settings file holds only the port today (`client/bridge/ClientConfig.java:24-28`).
- Dramaless Shape's reasons for flat cards (`refs/DRAMALESS_SHAPE-main/lib/SpriteBillboards.lua:1-29`), in my
  words: (1) a Gen 1 figure is a drawing with one front-on reading, and a solid made from it invents a body
  nobody drew; (2) an earlier voxelised version made that mod ship a description of ROM art; (3) one quad is
  cheaper and needs only the sheet's dimensions; (4) its card always faces south and only leans back with the
  camera's pitch, so it reads face-on at every tilt.

**How to weigh that argument for us.**

| Their point | Applies to us? |
|---|---|
| (1) Fidelity to the drawing | **Yes, as an aesthetic risk.** It is the reason carved and rigged styles need quality checks and a fallback, and the reason they should not be the default unseen |
| (2) Shipping art-derived data | **No.** Our figures are computed at run time on each player's machine from that player's own sheets and are never stored in the mod, sent or shared (B4.5) |
| (3) Cost | **Mostly no.** Flat = 4 vertices; thick ≈ 200; rigged = 144; carved ≈ 1,000–1,600 (B5.2–B5.4). All are within what Minecraft spends on ordinary mobs; only carved needs a measured budget |
| (4) South-facing card | **No.** Their camera's yaw is fixed, ours is free. A south-facing card would be edge-on from east or west, which is why we already turn the card and pick the frame by camera angle (`PuppetRenderer.java:56-62`; `FacingFrames.java:28-42`). So even our "flat" style already departs from theirs, using only frames the game drew |

**Options** for the default: (a) flat (today); (b) thick card; (c) carved; (d) rigged; (e) "pack decides".

**Recommendation.**

- Setting `characters.style` in `kantobridge-client.json`: `flat | thick | carved | rigged`, plus
  `characters.packs: true|false` (let packs replace individual sprites with models). Default **thick**
  (T-a): identical to today from the front, a slab from above, no heuristics.
- Fallback chain per sprite, decided once and cached: `rigged → carved → thick → flat`.
- **Consistency with the terrain:** tie the *default* to the terrain tier, not the user's explicit choice:
  cubes only (today, or Tier A) → flat or thick; per-pixel shapes (Tier B/C) → thick; carved/rigged remain
  explicit choices.
- **Packs:** `models: {"sprite:<ID>": {"vox": …}}` gives a hand-made figure per sprite; optional
  `poses: {"walk": "…vox"}`; without a walk model the figure bobs by one pixel on the walking frame's beat.
  A pack may also set `characters.style` as its suggestion; the player's setting wins unless it is unset.
- **Speech bubbles and emotes** stay as they are (`PuppetRenderer.java:85-100`).

Files: `client/bridge/ClientConfig.java` (new optional fields with defaults; unknown values fall back with a
warning, as the port does at `:56-73`), `client/puppet/PuppetRenderer.java`, the new `style` package.

**Open (user decisions).** The default style; whether a settings screen is wanted or the JSON file is
enough.

### B5.6 How players look

**Answer (facts).** The local player and every other player are drawn by vanilla's player renderer with
their Minecraft skins; we add the blockade fade, the speech-bubble sign and the surf sprite (section 2.4). The
hidden game's own player sprites are known to the export: `field.playerSprites` names the walking, biking
and surfing sprites (`gen1-bridge/export/kb_export.lua:41-47` exports the table;
`builder/export/ExportField.java:58-68` reads only `surf`). A player is 1.8 blocks tall in a world whose
characters are 2 blocks tall and whose cells are 2 blocks wide (`docs/NEXT_STAGES.md:274-275`).

**Options.**

- **L1. Minecraft skins (today).** No work; players are recognisable in multiplayer; the style differs from
  the NPCs'.
- **L2. A generated trainer for the local player only in third person.** Replace the local player's model
  with the hidden game's player sprite in the chosen character style (walk / bike / surf sheet by state).
- **L3. Generated trainers for everybody.** Each client draws *other* players from its **own** ROM's player
  sheet (art never travels); the server syncs only small state flags. Today only surfing is synced to others
  (`story/Surfing.java`; `docs/PROTOCOL.md:786-788`); biking would need the same kind of attachment. Everyone
  would look alike, so it needs a per-player tint or palette choice plus the name tag.
- **L4. Skin on a generated body.** Out of scope.

Hook for L2/L3: cancel `PlayerRenderer#render(AbstractClientPlayer, float, float, PoseStack, MultiBufferSource,
int)` (`mc → net.minecraft.client.renderer.entity.player.PlayerRenderer (PlayerRenderer.java:69)`) at HEAD for
players inside the region and draw the figure with the same `CharacterStyle` code. This is a new client
mixin next to `LivingEntityRendererMixin` (`mixin/client/LivingEntityRendererMixin.java:26-48`), which
already targets the same render path for the blockade fade; the two must be tested together. First-person
arms go through a separate path (`PlayerRenderer#renderRightHand` / `#renderLeftHand`,
`PlayerRenderer.java:190-198`, called from `mc → net.minecraft.client.renderer.ItemInHandRenderer
(ItemInHandRenderer.java:154-156, 250-252)`) and can stay vanilla.

**Recommendation.** L1 by default; L2/L3 as one later, optional setting `characters.players:
skin | trainer`, client-local (it changes how *you* see players, not how others see you). Reasons: identity
in multiplayer, no new mixin on a hot vanilla path until the NPC styles are settled, and the scale question
(1.8 against 2 blocks, `Attributes.SCALE` exists: `Attributes.java:120`) belongs to Part A's rules.

**Open (user decision).** Whether trainers-for-players is wanted at all; if so, how players are told apart.

---

**B6. Region awareness at runtime**

Track 2 owns the region record (A8). This section designs the client side and states, per question, **what the record
must contain** for it. Terms: an **art set** is the art of one game export on this player's machine (e.g. `blue`); a
region names the game it needs, the client picks a local art set that can draw it.

### B6.1 The region registry

**Answer (today, F).** There is no region. One WorldIndex per server is published from `<world>/kantobridge/world_index.json`
(`server/WorldIndexSync.java:45-49,62-78`), sent to each joining player as `world.index` parts of 64 Ki characters with a
SHA-256 over the whole text, then `world.slots` (`WorldIndexSync.java:58,183-207`; `world/WorldIndexParts.java:18`). The
client keeps one of each in static, non-volatile fields (`client/world/ClientWorldIndex.java:24-27`), cleared on
disconnect (`:36,49-54`). "Is this a Kanto world?" is `ClientWorldIndex.current() != null`; 18 call sites read `current()` or `slotMap()`
(`ActRuntime` ×4, `PuppetRuntime` ×3, `TextureSyncRuntime` ×3, `ClientStoryView`, `StoryCellRenderer`, `SurfSprite`,
`BlockadeFade`, `PuppetRenderer`, `Versions`, `SessionRuntime`, `MoveRuntime`). Position → cell is a linear scan over
all maps (`world/WorldIndex.java:141-149`).

**Options.**

| | R1 one index, add a `region` field per map | R2 a list of regions, each with its own index, shape map and hashes (recommended) |
|---|---|---|
| Sync | one growing document | per-region documents; a region that did not change is not re-sent |
| Client lookup | maps of all regions in one table | region by bounds, then that region's tables |
| Different games per region | awkward (`game` is a root field, `WorldIndex.java:31,37`) | natural |

**Recommendation.** R2, with this client API (all types immutable; the only mutable thing is one `volatile` reference):

```java
package dev.kantobridge.client.region;

public final class ClientRegions {
    private static volatile RegionSet current = RegionSet.EMPTY;
    public static RegionSet current() { return current; }           // any thread
    static void publish(RegionSet next) { ... }                      // client thread: swap, rebuild RenderSnapshot, mark sections dirty
}

public record RegionSet(List<RegionView> regions, long epoch) {
    public @Nullable RegionView at(int x, int y, int z);            // bounds test per region (a handful), last hit cached per thread
    public @Nullable RegionView byId(String id);
    public boolean isEmpty();                                        // replaces "ClientWorldIndex.current() == null"
}

public final class RegionView {
    public String id();                 // "kanto@blue" (record)
    public String game();               // "blue" (record)
    public @Nullable String artSet();   // local set that draws it: "blue", or "red" for a Red guest; null = none yet
    public Box3 bounds();               // record: block box including interior layers
    public WorldIndex index();          // today's WorldIndex, per region
    public @Nullable ShapeMap shapes(); // world-scoped ids; null until world.shapes arrived and its hash matched
    public @Nullable ShapeTable geometry();  // generated locally for (artSet, generator); null until ready
    public TextureState textures();     // READY | PLACEHOLDERS(reason)

    // used by the model on mesh threads: no allocation, no locks
    public int shapeAt(int x, int y, int z);                                  // 0 = none (or no shape map yet)
    public int closedShapeAt(StorySnapshot story, int x, int y, int z);       // 0 = open for this player / not a story position
    public @Nullable ShapeGeometry shape(int shapeId);                        // null while the table loads, or unknown id
    public @Nullable MapPlacement mapAt(int x, int y, int z);
}
```

- **Lookup structure.** Per region, a chunk grid built once at publish: `chunk (x,z) → MapPlacement[]` (the placements
  whose footprint plus ring touches the chunk, sorted by Y). A position resolves in: region bounds test → grid cell →
  ≤ ~15 candidates by Y (one per layer) → column index. A per-thread "last region / last map" cache makes consecutive
  positions of one section O(1). Memory (I): placements are 220 objects; the grid is 2,126 small arrays (`report → chunks`).
- **Caching and invalidation.** `publish` happens when a region's documents are complete and verified, when the local
  art set or shape table becomes ready, and on disconnect (`RegionSet.EMPTY`). After a publish that changes drawing:
  `levelRenderer.allChanged()` (simple, one hitch) or `setSectionDirty` over the loaded sections inside the region's
  bounds.
- **Compatibility shim.** `ClientWorldIndex.current()` stays as "the index of the region the local player stands in"
  (or the only region), so the 18 call sites move one at a time.

**What the client needs from the region record (to Track 2).**

| Field | Why |
|---|---|
| `id`, `game` | identity; choosing the art set |
| `bounds` (block box, all layers) | cheap region test on mesh threads; which loaded sections to re-mesh |
| `indexHash`, `shapeIndexHash`, `shapeMapHash` | verify parts; skip downloads that are already cached |
| `generator` (shape generator version) | the local shape table must be generated by the same rules, or the region falls back to cubes |
| `exportHash` and an **art key hash** (hash over the export resources art depends on: maps, tilesets, images, sprites, palettes, swaps, field tables) | lets a Red guest see that its own export yields the same keys as the Blue host's without comparing whole exports |
| world-level `slotMapHash` (not per region, B6.3) | state → sprite table |
| an asset-safe id (`[a-z0-9_.-]`) if any path ever needs the region | `@` is not valid in resource paths (B2.5) |

Sync need: a region list on join and whenever a build publishes; `world.index`, `world.shapes` addressed by an optional
`region` field; parts and hashes as today.

**Sketch.** Shape map document (ids only; world-scoped; one per region):

```
shape_map v1  (canonical bytes -> SHA-256 = shapeMapHash; sent base64 in world.shapes parts)
  header: version, generator, regionId, shapeIndexHash, mapCount
  per map (sorted by id):
    id, originDy,                      // lowest block relative to feet (-1 = the floor block)
    cols = (2*w + 2*ring) x (2*h + 2*ring)   // one entry per block column of the map and its ring
    columnOf[cols]      : u16          // index into this map's column palette
    palette[n]          : { count u8, shapeId u24[count] }   // bottom-up; 0 = "no shape here: plain carrier"
  story overlays: per tile-cell key: 4 columns x { count u8, shapeId u24[count] }
shapeAt(x,y,z): m = mapAt(x,y,z); c = m.columnOf[(z - m.z0) * m.colsW + (x - m.x0)];
                dy = y - (m.feetY + m.originDy); return 0 <= dy < palette[c].count ? palette[c].shapeId[dy] : 0
```

Size (I): the export has 94,876 cells (`export_report → cells`) = 379,504 block columns, plus the void ring
(87,564 cells = 350,256 columns, `report → notes.void_fill_cells`) and interior rings; at 2 bytes per column ≈ 1.5 MB
before compression, the same order as today's index. A report field `shape_map_bytes` should confirm it.

**Open.** Whether the shape map is synced (authoritative, like `world.slots`) or recomputed by each client from its own
export and only its hash compared. Recommendation: sync it — one mechanism, already proven for the slot map — and let the
client skip the download when a cached copy has the announced hash.

> **Integrator note.** The region id is `kanto` ([A8.1](#a81-the-region-record)), which is already a valid resource-path segment, so no separate asset-safe id is needed; the hashes and the generator version asked for here are the record's `hashes` and `generator` ([4.5](#45-the-region-record)).

### B6.2 Client assets per region

**Answer (today, F).** One pack folder and id for everything (`client/packs/GeneratedPack.java:22,37-39`), replaced as a
whole on every write (`builder/pack/PackFiles.java:62-86`); one `kantobridge.json` with one game and two hashes
(`builder/pack/ResourcePackWriter.java:228-233`); no shape tables; NPC and surf sheets looked up by `(palette, sprite)`
through `getResource` at first use (`client/puppet/PuppetTextures.java:53`, `client/fx/SurfSprite.java:188-201`).

**Options.**

| | P1 one pack, one sub-tree per art set | P2 one pack **per art set** (recommended) |
|---|---|---|
| Layout | `resourcepack/assets/kantobridge/textures/block/<set>/…` | `resourcepacks/<set>/assets/kantobridge/textures/block/<set>/…` (the set stays in the path, so files never collide either way) |
| Writing | `PackFiles.writeTo` must learn to replace a sub-tree | unchanged: replace one folder |
| Mixin | unchanged | `GeneratedPack.offer` offers each folder that has a `pack.mcmeta`; ids `kantobridge_generated_<set>` |
| Pack screen | one entry | one entry per game the player owns |
| `kantobridge.json` | lists all sets | one per pack: `{layout: 2, artSet, game, exportHash, artKeyHash, textures, generator}` |

*Shape tables and character models.* Generated on the client from the art set's export bundle
(`<gameDir>/kantobridge/export/<game>/`, `builder/KantoWorldBuilder.java:48-51`) by 3b's generator, on a worker thread,
keyed by `(artSet, exportHash, generator)`. A local cache file beside the pack
(`<gameDir>/kantobridge/cache/<set>/shapes-g<generator>-<exportHash prefix>.bin`) avoids regenerating at every join; it is
ROM-derived and stays in the game folder like the bundle and the pack (in dev that is under `local/`, git-ignored).

*When they load.* Textures cannot be lazy: the block atlas is stitched once per resource reload from every selected pack
(B2.5), so all installed art sets are always in the atlas. Shape maps (server) and shape tables (local) are small enough
to load **at join for every region in the list**; while one is missing that region draws cubes. If a future world held
many regions, `RegionView.geometry()` can become lazy per map without changing the model (the lookup already returns
"none yet").

*Memory (I, budgets from B2.3).* Per region: shape map ≈ 1–2 MB, lookup grid < 1 MB, shape table ≤ 32 MB, mesh cache
shared and capped at 64 MB; atlas +≈5.5 MB per art set at 16 px.

**Recommendation.** P2; generate and cache shape tables per art set and generator version; load everything at join.

**Sketch.** `GeneratedPack.offer` (client), replacing `client/packs/GeneratedPack.java:78-87`:

```java
static void offer(Path gameDir, Consumer<Pack> out) {
    for (Path dir : artSetFolders(gameDir)) {                 // kantobridge/resourcepacks/*/pack.mcmeta, plus the legacy folder
        Pack pack = Pack.readMetaAndCreate(location("kantobridge_generated_" + dir.getFileName()), 
                new PathPackResources.PathResourcesSupplier(dir), PackType.CLIENT_RESOURCES, selection());
        if (pack != null) out.accept(pack);
    }
}
```

**Open.** Where the generated packs should sit relative to player packs (B1.2): with no slot files left in them, the
safe default becomes "below player packs"; 3b's B4.2 decides.

### B6.3 Several regions in view

**Answer.** Chunk meshing is per section and our model resolves each position on its own, so two regions (and vanilla
land between them) need no special rendering path. What they share is global and must not conflict:

| Shared thing | Risk | Design answer |
|---|---|---|
| Block states (slots) | The same state would mean different tiles in two regions if each region had its own slot map; everything keyed on state (`getQuads`, Distant Horizons' colour cache, the fallback cube) has no position | **One slot map per world**, keys qualified by art set: `(artSet, atlas, palette, tile, shape)`. A region's build *appends* its new keys (existing slots never move); the world's `slotMapHash` changes; clients re-read `world.slots` and rebuild the slot → sprite table — no pack rebuild, no resource reload |
| Slot capacity | Kanto alone uses 3,926 of 4,096 full slots (`report → slots_full`) | Raise the family count when a second region is built: +16 families ≈ +5 MB per side (B2.5). Additive: existing worlds keep their ids |
| Block atlas | sprite ids of two games collide | art-set path prefix (B2.5 N2) |
| Animated sprites | all sets tick all the time | 23 animated tiles today (`report → animated_textures`); negligible |
| Texture state | one region's art missing must not blank the other | per-region `TextureState`; missing sprites resolve to the placeholder per key; no global pack suppression |
| Shape generator version | regions built by different mod versions | the record's `generator`; a client that cannot produce that version draws that region as cubes and reports `generator_mismatch` |
| Mesh-thread lookups alternating between regions | cache thrash | per-thread last-region cache; bounds tests on a handful of regions |

Sizes for sightlines (A3.5 owns the topic; numbers for reference, I): the surface layout box is 170 × 180 Gen 1 blocks
(`export_report → layout_box_blocks`) = 680 × 720 blocks, 704 × 744 with the 12-block void ring
(`builder/plan/WorldPlan.java:23-24`) ≈ 44 × 46.5 chunks. Vanilla's maximum render distance of 32 chunks (512 blocks)
reaches the edges from the centre but only just the corners (half-diagonal ≈ 512 blocks), and never the whole region
from outside it; seeing a region from afar is a job for an LOD mod (B2.2).

**Options.**

| | Q1 a slot map per region (every region reuses all families) | Q2 one slot map per world (recommended) |
|---|---|---|
| Capacity | each region has the full 4,096 | regions share; families are added as regions are added |
| State → tile | depends on the position's region | global within a world |
| `getQuads`, Distant Horizons colours, fallback cube | wrong for every region but one (no position available) | correct |
| Builder | independent per region | allocates new keys after the existing ones; never moves a slot |

**Recommendation.** Q2: a world-level slot map with art-set-qualified keys; per-region index, shape map and texture
state; nothing region-specific in block ids.

**Sketch.** Slot map v2 entry (ids from the synthetic test bundle): `{"set":"red","atlas":"tilesets.test","palette":"TEST_PALETTE","tile":6,"shape":"half","family":0,"slot":0}`;
a v1 file is read as `set = <the world's game>`.

**Open.** Append-only allocation changes today's rule "slots are the sorted enumeration of the whole export"
(`world/SlotMap.java:27,157-170`): within one region's first build it is still sorted; across builds it is
first-come. The slot map stays authoritative in the world file, so determinism between clients is unaffected.

### B6.4 The texture flow per region

**Answer (today's M9 flow, F).** Join → `world.index` → `world.slots` (sent only when its hash equals the index's
`slotMapHash`, `server/WorldIndexSync.java:124-131`) → `TextureSync.decide(serverHash, localHash, packLoaded, slotsHash,
bridgeConnected)` (`client/packs/TextureSync.java:141-156`): equal hashes → `MATCH`/`RELOAD`; else wait for the slots
(15 s, `:33,223-228`), then `REGENERATE` (export own art through `ExportClient`, build the pack **against the host's slot
map**, reload: `client/packs/TextureSyncRuntime.java:146-203`) or `NO_BRIDGE`; a wrong pack is held back
(`TextureSync.java:296-302`). The client reports `texture.status{ok, slotMapHash?, textures?, reason?}`
(`protocol/messages/texture.status.schema.json`), which the server stores per player and compares with the one index
(`server/TextureStatusService.java:58-73`).

With the runtime model (B2.8 step 1) the pack no longer contains slot numbers, so **the slot map can never make a pack
wrong**. What can be wrong is: no art set for the region's game, an art set that lacks some keys, a shape map that does
not verify, or a shape table of another generator version.

**Options.**

| | F1 keep one status for the world | F2 one status per region (recommended) |
|---|---|---|
| Message | unchanged | `texture.status` gains optional `region`, `missing`, `shapes`, `generator`; absent `region` = the legacy single region |
| Server | unchanged | `TextureStatusService` keys by `(player, region)` |

**Recommendation.** F2 with this decision table, evaluated per region each client tick as today:

```
set = localArtSetFor(region.game)              // same game, else a compatible one (red <-> blue), else none
none:
    bridge connected to a compatible game ?  EXPORT + GENERATE(set) + one RELOAD   // once per game, not per world
                                          :  PLACEHOLDERS reason "no_art" (was "no_bridge"); retry when the bridge connects
present, its pack not loaded:                RELOAD
present and loaded:
    missing = keys of the world's slot map (for this region's set) and of the region's shape textures whose sprite is absent
    missing == 0                           ->  textures READY
    missing > 0 and bridge connected       ->  EXPORT + GENERATE + RELOAD (the set is stale)
    missing > 0 otherwise                  ->  READY with placeholders for those keys, reason "art_incomplete"
shapes:
    world.shapes verified and table generated for (set, region.generator) -> shapes READY
    else cubes; reason "no_shapes" | "shape_map_mismatch" | "generator_mismatch"
report texture.status{region, ok, slotMapHash, textures, missing?, shapes?, reason?}
after any state change: publish RegionSet -> re-mesh (no resource reload unless a pack was written)
```

Protocol and code touched: `protocol/messages/texture.status.schema.json` (optional fields; `additionalProperties` is
`false` today), `world.index`/`world.slots` schemas (optional `region`), new `world.shapes`; regenerate
`protocol/catalog.json` (CLAUDE.md "Testing"); `client/packs/TextureSync.java` (inputs become per region; `Decision`
loses `SLOTS_MISMATCH`'s pack consequences), `TextureSyncRuntime.java`, `client/packs/PackInfo.java`,
`server/TextureStatusService.java:32-45,58-73`. All additions are optional fields, as earlier additive changes were
(`docs/PROTOCOL.md:822-842`).

**Sketch.** `{"type":"texture.status","data":{"region":"kanto@blue","ok":true,"slotMapHash":"…","textures":4301,"missing":0,"shapes":18234,"generator":1}}`
(numbers illustrative; `textures` as today is the pack's own count).

**Open.** With two regions of two games, one hidden game is linked at a time (Track 2's A7/A8): the export for the other
game's art can only run while *that* game is connected. Until then that region shows placeholders — acceptable, but it
should be said in the HUD line.

### B6.5 Namespacing now

**Answer / Recommendation.** What to namespace **now**, in the order it pays off, so a second region needs no redesign.
Each item is small and independent.

| # | Change | Where today | Why now |
|---|---|---|---|
| 1 | Art-set segment in every generated texture path: `block/<set>/tile/<atlas>/<palette>/<tile>`, `textures/npc/<set>/<palette>/<sprite>.png`, `textures/player/<set>/<palette>/surf.png` | `builder/pack/ResourcePackWriter.java:205,222,238-251`; readers `client/puppet/PuppetTextures.java:53`, `client/fx/SurfSprite.java:196` | player packs are written against these paths (3b's B4); changing them later breaks every pack |
| 2 | Slot-map keys carry the art set; schema version 2; `family` bound follows the registered count | `world/SlotMap.java:32,34,71-89`; `protocol/world/slot_map.schema.json:10,22` | the world file is authoritative and long-lived |
| 3 | Pack info v2 (`layout`, `artSet`, `artKeyHash`, `generator`); one pack per art set | `ResourcePackWriter.java:228-233`; `client/packs/PackInfo.java:23`; `GeneratedPack.java:22,37-39` | lets old and new packs be told apart |
| 4 | World data in a per-region folder: `<world>/kantobridge/regions/<id>/{world_index.json, shape_map.bin, shape_index.json}`, world-level `slot_map.json` and `regions.json`; the legacy root files read as one region | `builder/BuildTask.java:47-49,260-263`; `server/WorldIndexSync.java:62-78` | Track 2's record lands here |
| 5 | Optional `region` on `world.index`, `world.slots`, `world.shapes`, `texture.status`, `story.state` | `protocol/messages/*.schema.json` | additive now, breaking later |
| 6 | Client lookups go through `ClientRegions`; `ClientWorldIndex.current()` becomes a shim | 18 call sites (B6.1) | every new renderer feature would otherwise add a 19th |
| 7 | Block ids stay generic (`tile_full_NN`, `shape_full_NN`, `decor`, `decor_leaves`); never a region or game name in a block id | `block/KbBlocks.java:66-68` | registry entries are forever |
| 8 | `WorldIndex.GAMES` (`red`, `blue`) becomes a lookup of known games | `world/WorldIndex.java:37,64-66,234-237` | a closed set in a parser blocks any second game |
| 9 | Shape ids, shape index and generator version are per region and hashed | new | B3.4 (3b) |
| 10 | Story-cell keys (`"MAP:x:y"`) are unique per game only: client sets (`drawn`, `active`) and `story.state` are scoped by region | `world/StoryCellTable.java:40`; `story/StoryRules.java:57-62`; `client/story/ClientStoryView.java:50-55` | two games can both have a `ROUTE_1` |

**Options.** Doing only items 1, 2 and 7 now and the rest with the first second region is possible; 1 and 2 are the two
that cost a migration later.

**Sketch.** Reading old data: a pack without `layout` is layout 1 (slot files; keep today's flow for it or regenerate);
a slot map with `version: 1` gets `set = index.game`; a world without `regions.json` is one region `kanto@<game>` whose
bounds are the union of its maps' volumes (`builder/plan/WorldPlan.java:234-245`).

**Open.** The exact art-set id for compatible games (`red`/`blue` share tiles, palettes and swap sites,
`docs/PROGRESS.md:662-663`): keep them separate (`red`, `blue`) and let the client map a region's game to a compatible
local set — or merge into one family id. Separate is safer.

> **Integrator note.** Where this answer writes `kanto@<game>`, read the region id `kanto` with its `game` field ([4.5](#45-the-region-record)); the per-region folder of item 4 is the layout of 4.5. Items 1–3, 5, 6, 8 and 10 are scheduled in M14, item 9 in M16; item 7 is a rule from now on.

---

**B7. One architecture for many games**

### B7.1 A front-end interface per game family (survey; validated against Gen1Recomp)

**Answer (facts: what Gen1Recomp exposes to a mod for each family).**

| Need of the neutral scene (B3.3) | Gen 1 (red, blue, yellow) | Gen 2 (gold, silver, crystal) | Gen 3 (firered, leafgreen, emerald; `beta`) |
|---|---|---|---|
| Which family is running | `GameVersion.generation()` 1/2/3 and `GameVersion.engine()` `gen1`/`gs`/`crystal`/`game3` (`refs/gen1recomp/src/core/GameVersion.lua:194-207`) | same | same |
| Pristine map and tileset records | `mod.datasets:open(v)`: registries `maps, tilesets, sprites, palettes, field, …` (`refs/gen1recomp/src/mods/DatasetViews.lua:29-38`); used today (`gen1-bridge/export/kb_export.lua:243-248`) | same call; the registry names stay `maps`, `tilesets`, `sprites`, `palettes` but route to `gen2Maps`, `gen2Tilesets`, `gen2Sprites`, `gen2Palettes` with Gen 2 record shapes (`refs/gen1recomp/src/mods/Schemas.lua:530-552, 748-837`; `DatasetViews.lua:40-49`) | `maps` only, plus Pokémon/moves/items/encounters/text/scripts (`DatasetViews.lua:51-65`). Layouts, tileset pairs and sprites are native packs read by engine modules |
| Cell behaviour | `Map.new(def, tileset)` and its cell predicates (`gen1-bridge/lib/kb_engine.lua:4984-5008`) | `require("src.world.gen2.Map").new(def, tileset)`: `cellCollision`, `isWalkableCell`, `isWaterCell`, `isGrassCell`, `isCounterCell`, `isDoorTileCell`, `isWarpTileCell`, `stepPermitted`; def-level helpers work without a loaded map (`refs/gen1recomp/src/world/gen2/Map.lua:13-39, 53-63, 97-126, 167-208, 241-251, 274-277`); predicates for ledges, cut/headbutt trees, ice, currents, waterfalls (`refs/gen1recomp/src/world/gen2/Permissions.lua:31-57, 92-116, 133-140, 170-180`) | `def.midLayout` (`LayoutNative`): `midAt`, `collAt`, `elevAt`, `width`, `height`, `borderMids` (`refs/gen1recomp/src/core/game3/layout_native.lua:15-34, 88-98`), prepared by `Map.ensureMidLayout` (`refs/gen1recomp/src/core/game3/map.lua:49, 146`); behaviours through `Collision.behaviorOn` (`refs/gen1recomp/src/core/game3/collision.lua:812-814`) |
| Art tiles | block → 16 tile ids; greyscale atlas via `Assets.imageData` (`kb_engine.lua:5142-5165`) | `tileset.blocks` (4×4 tile ids per block, `gen2/Map.lua:226-237`); per-tile palette slot and Crystal attributes (`refs/gen1recomp/src/world/gen2/TileAttrs.lua:15-44`); block 0 → border (`refs/gen1recomp/src/world/gen2/BorderFill.lua:36-41`) | per tileset pair: metatile atlases under / over (and middle), 16×16 indexed pixels per metatile, and 16 palettes of 16 colours (`refs/gen1recomp/src/import/gba/native_pack.lua:74-83, 107-157, 159-196`; `refs/gen1recomp/src/core/game3/tileset_native.lua:50-60, 78-88`) |
| Colours | SGB palette per map (`kb_engine.lua:5120-5140`) | `Palettes.bgSet(data, mapDef, daytime)` (eight palettes for a map and time of day), `Palettes.daytimeFor`, `Palettes.spritePalette`: "pure table math … no love calls" (`refs/gen1recomp/src/world/gen2/Palettes.lua:1-4, 84-94, 172-212, 245-259`) | `NativePack.palsToRgb8` (`native_pack.lua:198-211`) |
| Layout of connected maps | `OverworldController.computeNeighbors` (`kb_engine.lua:5100-5118`) | the same strip arithmetic (`gen2/Map.lua:294-313`); a `computeNeighbors` exists on the Gen 2 world (referred to at `gen2/Map.lua:27-29`; ⚠ not read) | `Map.computeWorld(maps, rootId, hops, reachW, reachH, ensure)`: a BFS over connections with offsets in cells (`refs/gen1recomp/src/core/game3/map.lua:73-128`) |
| Sprites | sprite registry + `Assets.imageData` (`kb_export.lua:730-746`) | `gen2Sprites` registry; 16×16 from OAM tiles (`refs/gen1recomp/src/world/gen2/OamFacings.lua:23-64`) | `OwSprites.get(graphicsId)` → sheet with `w`, `h`, `frameCount` (`refs/gen1recomp/src/core/game3/ow_sprites.lua:84-120`) |
| Runtime terrain changes | swap-site audit (`gen1-bridge/export/kb_swapsites.lua`) | `Map:setBlock` / `World:changeBlock` (`gen2/Map.lua:217-224`): needs its own audit | `LayoutNative:setMetatiles` / `applyOverride` (`layout_native.lua:124-140, 188-205`): needs its own audit |

So everything the neutral scene needs is reachable for Gen 2 through registries and pure modules, and for
Gen 3 through engine internals of an engine that is still marked beta
(`refs/gen1recomp/src/core/GameVersion.lua:119, 138, 153`). A bridge for either family is a separate mod
(`docs/NEXT_STAGES.md:433-439`); gen1-bridge itself declares `"games": ["red", "blue"]`
(`gen1-bridge/manifest.json:9`).

**Options.**

- **F1. One front end per family in Java, behind one interface; the wire export stays per family**
  (recommended; follows B3.3 option O3).
- **F2. One neutral wire export produced in Lua by every bridge.** The right end state once a second bridge
  is written, because the new bridge then needs no Java front end at all; too costly to do first for Gen 1.

**Recommendation.** F1 now; write the Gen 2/3 *bridges* (when their time comes) to emit the neutral scene
directly (F2 for new families), so `Gen1FrontEnd` remains the only adapter.

**Sketch.**

```java
// fabric-bridge/src/main/java/dev/kantobridge/scene/GameFrontEnd.java (pure Java, both sides)
public interface GameFrontEnd {
    String family();                                   // "gen1" | "gen2" | "gen3"
    boolean accepts(String game);                      // the export index's "game"
    Scene describe(ExportBundle bundle) throws BundleException;   // wire export -> neutral scene (B3.3)
    ArtSource art(ExportBundle bundle);                // pixels on demand; never serialised
    PaletteModel palettes(ExportBundle bundle);
    ClassHints hints();                                // family defaults for the classifier
}
public interface ArtSource {
    ArtTile tile(String atlas, int tile, Plane plane); // artPx × artPx; shade or palette index per pixel; alpha
    SpriteFrames sprite(String id);                    // B5.1
}
public interface PaletteModel {
    List<String> variants();                           // ["default"] or ["MORN","DAY","NITE","DARK"]
    String textureKey(String atlas, int tile, String mapId, String variant);   // B4.1 key incl. palette (set) id
    int[] colours(String atlas, int tile, String mapId, String variant);      // ARGB per shade / index
}
public final class FrontEnds {                         // registry: FrontEnds.forGame(bundle.game())
    public static GameFrontEnd forGame(String game) { … }
}

// Gen 1 (exists as data today; adapter only)
final class Gen1FrontEnd implements GameFrontEnd {
    // cells:   CellFlags bits -> kind/tags/dir (walk, water, shore, grass, door, warp, counter, cut, ledge+dir, …)
    // art:     block -> 4×4 tile ids (ExportTileset.tile), both swap states via MapModel; greyscale shades
    // border:  borderDrawn (M6) or border
    // palette: map4; one SGB palette name per map
    // sprites: 16×16 strips, 6/3/1 frames
    // hints:   CUT_TREE cells are round objects on the swap's "after" ground; bookshelves from field.bookshelves
}
// Gen 2 (sketch; needs a gen2 bridge export)
final class Gen2FrontEnd implements GameFrontEnd {
    // cells:   COLL byte per cell -> kind/tags/dir; edges from side-wall kinds
    // art:     block -> 4×4 tile ids; tilePalettes slot per tile; priority tiles -> plane OVER; flips resolved
    // border:  map borderBlock (block id 0 inside the map means border)
    // palette: tile8x4; palette-set id per (environment, map group roof), variants MORN/DAY/NITE/DARK
    // sprites: 16×16, palette "own" with per-object override
}
// Gen 3 (sketch; needs a game3 bridge export)
final class Gen3FrontEnd implements GameFrontEnd {
    // cells:   coll + behaviour -> kind/tags/dir; elev per cell
    // art:     artPx 16; planes UNDER/MIDDLE/OVER from the pair's metatile atlases; palettes baked per pixel
    // border:  borderWidth × borderHeight metatiles
    // palette: tile16x16; variants ["default"]
    // sprites: frame size from the sheet; walk has two frames per direction
}
```

**Open.** ⚠ Not confirmed: whether a mod on a Gen 2 or Gen 3 boot can read atlas pixels the way gen1-bridge
does (`Assets.imageData`), and the Gen 2 `computeNeighbors` signature; both are for whoever writes those
bridges. Yellow is Gen 1 engine-wise (`GameVersion.lua:46-56`) but is not accepted by gen1-bridge or the
builder today.

### B7.2 Generality hooks: the Gen-1-specific code paths

**Answer (facts).** Every place below assumes Gen 1 (or Red/Blue, or Kanto). "Hook" says what replaces the
assumption; none of them needs to change behaviour for Gen 1.

**Export (gen1-bridge, Lua)**

| Where | Assumption | Hook |
|---|---|---|
| `gen1-bridge/manifest.json:9` | `games: ["red","blue"]` | per-family bridge mods; the protocol's `game` field already names the game |
| `gen1-bridge/export/kb_export.lua:31` | layout root `PALLET_TOWN` | `region.root` from the front end / region record |
| `kb_export.lua:859` | layout resource id `kanto` | resource id = region id |
| `kb_export.lua:760` | palettes resource `sgb`, `mode: "sgb"` | `paletteModel` + variants (B3.3) |
| `kb_export.lua:882-883` | game must be `red` or `blue`, else coerced to `red` | accept the family's games; never coerce |
| `kb_export.lua:476, 832, 840` | dex 1..151, moves 1..165 | ranges from the family (track 2 owns party/dex limits) |
| `kb_export.lua:41-47` | Gen 1 field tables | family-specific; the neutral scene carries cells/edges/swap sites instead |
| `gen1-bridge/export/kb_cells.lua:8-34, 59-71` | 16-bit flags from tile lists; ledges from `field.ledges`, default tileset `OVERWORLD` | neutral `kind/tags/dir/edges` |
| `gen1-bridge/export/kb_swapsites.lua:60-113` | curated Red/Blue script sites as 32-px block swaps | per-cell `swapSites` |
| `gen1-bridge/export/kb_images.lua:84` | sprite strip must be 16 px wide | `frameW/frameH` + frame table |
| `gen1-bridge/lib/kb_engine.lua:4993-5008, 5027-5047, 5058-5062, 5126-5140` | Gen 1 `Map`, a 0..255 tile probe, cut-tree tile constants, the SGB cascade | family engine adapters |

**Protocol and schemas**

| Where | Assumption | Hook |
|---|---|---|
| `protocol/world/world_index.schema.json:20` | `game` ∈ {red, blue} | open string + `family`; region record (track 2) |
| `world_index.schema.json:30-33` | `cellBlocks` const 2 | stays 2 for all three families (16-px cells); keep, but document as "blocks per 16-px cell" |
| `world_index.schema.json:57-60` | one feet level per map | per-cell level table for Gen 3 (B3.3) |
| `protocol/export/map.schema.json:33-41, 159-168` | sizes in 32-px blocks, block ids 0..255, Gen 1 flag bits | neutral scene |
| `protocol/export/tileset.schema.json:40-50` | blocks of 16 tile ids 0..255, Gen 1 class lists | atlas + planes + tile palettes |
| `protocol/export/palettes.schema.json:11, 22-23` | `mode` const `sgb`, 4 colours | palette model |
| `protocol/export/sprite.schema.json:12, 14-19` | `w` const 16, six fixed frames | frame table |
| `protocol/export/swaps.schema.json:19-23` | block ids, four Gen 1 kinds | per-cell states, open `kind` |
| `protocol/world/slot_map.schema.json:19-23` | key (atlas, SGB palette, tile ≤ 1023, shape full/half), ≤ 16 families | texture key with palette-set + variant; shape index |

**Builder and shared code (Java, `fabric-bridge/src/main`)**

| Where | Assumption | Hook |
|---|---|---|
| `builder/export/ExportBundle.java:35-36, 40, 228-234` | resource kinds; games {red, blue}; ids `sgb`, `kanto` | `FrontEnds.forGame`; ids from the index |
| `world/WorldIndex.java:37` | games {red, blue} | as the schema |
| `builder/export/ExportPalettes.java:27-28, 38-39` | SGB mode, 4 colours | `PaletteModel` |
| `builder/export/ExportTileset.java:37-43, 62-65` | 4×4 tiles per block, ids 0..255 | `SceneAtlas` |
| `builder/export/ExportMap.java:79-87, 111-114, 143-158` | cells = blocks × 2; block ids | `SceneMap` |
| `builder/export/ExportImage.java:22-25` | sprite width 16 | `SceneSprite` |
| `builder/export/CellFlags.java:8-35` | the Gen 1 flag bits | `CellKind` / `CellTag` |
| `builder/export/ExportSwaps.java:21-29`; `world/StoryCellTable.java:31, 46-62` | block before/after; four kinds | per-cell swap states |
| `builder/export/ExportField.java:21-69` | Gen 1 field tables (`indoorEncounters`, `safari`, `playerSprites`) | front-end hints / rules profile |
| `builder/plan/MapModel.java:74-81, 171-189, 191-208, 235-264` | tw = cells × 2; collision tile = bottom-left; walkable lists; `kindOf` from flags | built from `SceneMap` (planes, kinds) |
| `builder/plan/HeightRules.java:15-30, 41-64` | one tile row = one block; runs along 8-px columns | runs along `artPx` columns; unit = `artPx / 8` blocks |
| `builder/plan/ArtCatalog.java:80-82, 99-110, 112-124` | one palette per map; key (atlas, palette, tile); half shape = water | key from `PaletteModel.textureKey`; shapes from the shape index |
| `builder/plan/WorldPlanner.java:40, 42, 97, 114-116, 188` | root `PALLET_TOWN`; cave tileset `CAVERN`; layout `kanto`; origins × `GEN1_BLOCK_BLOCKS`; palettes `sgb` | region root; `env.cave`; layout unit from the scene |
| `builder/plan/WorldPlan.java:23-37, 396-427, 429-464` | void ring of border art; ceilings; three Kanto biomes; one feet level | dressing rules; biome/mood per map from the scene `env`; per-cell level |
| `builder/plan/StoryCellPlanner.java:27-28` | boulders are `SPRITE_BOULDER` objects | `objects[*].kind == "boulder"` from the front end (Gen 2 uses a movement byte: `refs/gen1recomp/src/world/gen2/Map.lua:150-157`) |
| `builder/pack/Textures.java:11-27, 32-51, 74-109, 126-161` | 4 grey shades → palette colour; 8×8 → 16×16; water/flower animation constants; 6/3/1-frame 16×16 sheets | `ArtSource` + `PaletteModel`; `animated` from the export (M7); `SceneSprite` frame table |
| `builder/pack/ResourcePackWriter.java:47-53, 128-136, 184-226` | flower tile 3 and `flower1..3` images; one NPC sheet per (map palette, sprite); surf sheet | animated list; sprite palette mode (`map` / `own` / `baked`) |
| `world/Scale.java:15-19` | `GEN1_BLOCK_BLOCKS`, `GEN1_BLOCK_TILES` | derive from `units` |
| `world/SlotMap.java:47-50, 71-83` | shapes FULL/HALF; SGB palette symbol; tile ≤ 1023 | superseded by the shape index for shaped art; texture key generalised |

**Client art (`fabric-bridge/src/client`)**

| Where | Assumption | Hook |
|---|---|---|
| `client/puppet/FacingFrames.java:15-19, 44-58` | 4 columns × 2 rows of 16-px frames | frame table from `SpriteFrames` (B5.1) |
| `client/puppet/Puppet.java:17-21, 142-160` | 60 Hz frames, a 16-frame step, walking frame on frames 4–11, mirror on alternate up/down steps | animation profile per family (Gen 3 has two walk frames per direction) |
| `client/puppet/PuppetRenderer.java:29-31`; `entity/KbEntities.java:16-17` | every figure is 2×2 blocks; box 1.75 × 1.9 | size from `frameW/frameH` (16×32 people are 2×4 blocks) |
| `client/puppet/PuppetTextures.java:29-41` | texture chosen by the map's palette | by sprite palette mode |
| `client/fx/SurfSprite.java:187-202` | one surf sprite per map palette | player sprite table from the front end |
| `client/story/StoryCellArt.java:39-51`; `world/StoryCellTable.java:32-35, 97-109` | closed art = four full-shape slots and a height | closed shape ids (B3.7) |

**Recommendation.** Do not generalise these now. Introduce the `Scene` model and `GameFrontEnd` with the
Gen 1 adapter, write **new** code (shape planner, geometry, keys, character styles) against them only, and
convert an existing class from the tables above only when it is touched anyway. The one change worth making
early is cosmetic but removes a trap: no new code may take `"red"`/`"blue"`, `PALLET_TOWN`, `sgb`, `kanto`,
16 or 4×4 as literals; they come from the scene.

**Open.** None beyond B7.1's.

---

**B8. Testing**

### B8.1 Test design for Part B

**Answer (facts: what exists).** JUnit tests in `fabric-bridge/src/test` already cover pure builder and client
classes (`HeightRulesTest`, `SlotMapTest`, `PackWriterTest`, `TexturesTest`, `SyntheticPlanTest`,
`StoryCellPlannerTest`, `StoryCellArtTest`, `FacingFramesTest`, `PuppetCullingTest`, `PuppetTest`,
`GeneratedPackTest`, `PackGeneratorTest`, …); GameTests in `src/gametest` run server parts on the synthetic
bundle (`SyntheticBuildGameTests`, `TileBlockTests`, `StoryRulesGameTests`, …) and reproduce a golden
WorldIndex byte for byte (`fabric-bridge/src/gametest/resources/kantobridge/golden/synthetic_world_index.json`;
`docs/PROGRESS.md:264-266`). The synthetic export is `tools/fake_gen1/synth.py`: one tileset with 16 drawn
tile kinds on a 96-tile atlas, three maps, four sprites with 6, 6, 3 and 1 frames, a ledge row, water,
flowers, a two-row house front, one cut-tree swap (`tools/fake_gen1/synth.py:16-18, 38-83, 121, 130, 196-220,
301-302`). There are no client GameTests on 1.21.1 (`docs/PROGRESS.md:863-864`).

**A limit of today's synthetic art for shape tests (fact).** Its tree, wall and post tiles have no
lightest-shade background (`synth.py:200-207`), so background detection, object extraction and hulls would
find nothing; the house has no window panes and no tapered roof. Tier B/C rules cannot be exercised with it
as it is.

**Design.**

1. **Pure parts live in `src/main`** (`scene/`, `builder/shape/`, `builder/shape/geom/`) or as pure classes in
   `src/client` (`client/puppet/style/`, `client/packs/KbPackManifest`, `client/shape/vox/`), with no
   Minecraft class in their signatures, so JUnit can run them (the pattern of `FacingFrames`,
   `PuppetCulling`, `StoryCellArt`).
2. **Two kinds of fixtures.**
   - *In-code scenes* (`ShapeFixtures` in `src/test`): tiny `Scene` objects and tiny art built from ASCII
     strings (`"..##.."`), one per rule. They need no bundle change and make failures readable.
   - *The synthetic bundle*, extended once: add to `synth.py` a sign with a lightest-shade background, a
     2×2-tile round tree with a dark outline, a house with a tapered two-row roof and windowed facade, a
     fence line and a cliff mound with a rim; regenerate the bundle (`python -m fake_gen1 --write-bundle`,
     `docs/PROTOCOL.md:680-682`) and the golden index. All drawn by code, no ROM data
     (CLAUDE.md "Testing").
3. **JUnit suites** (names are proposals):

| Suite | What it proves |
|---|---|
| `StructuresTest` | flood fill regions; run units incl. repeat and trim rules; region vote; door fold and door adoption; roof rows; results equal for shuffled input order |
| `ShapeKeyTest`, `ShapeIndexTest` | canonical text; ordering; ids stable when the build filter changes (whole-export rule, as `SlotMapTest`); hash changes when `planner` or `params` change; JSON round trip; `fromJson` re-sorts |
| `ShapePlannerSyntheticTest` | golden `shape_index.json` hash for the synthetic bundle; every block of the build has either a shape or is air; both swap states planned; determinism across two runs and across a reversed map order |
| `GeometryTest` | per key kind: quad count, bounds inside the block (or the declared overflow), UVs inside the tile; fold rule faces; wedge heights; greedy merge keeps texel mapping exact (compare a rasterised mesh with the unmerged one) |
| `BackgroundTest`, `ObjectTest`, `HullTest`, `BandsTest` | each Tier B/C rule on ASCII art, incl. the negative cases (white sealed behind dark trim stays; a vertically repeating cluster is not a prop; a body flush with its box) |
| `SelfCheckTest` | Tier A scores are exactly 1.0 / 0 holes on the fixtures; a deliberately wrong face mapping drops the score |
| `ArtKeysTest`, `KbPackManifestTest`, `PackOverridesTest` | key parse/format; manifest validation codes; precedence across a stack; unknown keys counted not fatal |
| `VoxFileTest`, `VoxMesherTest` | chunk parsing with unknown chunks skipped, size and index-0 rules, the default-palette path, the axis mapping on an asymmetric calibration model built in the test; write → read round trip |
| `ThickCardMeshTest`, `HullCarverTest`, `RigSplitterTest`, `StyleFallbackTest` | quad counts and bounds; hull projections equal the masks; each fallback rule triggers on its ASCII counter-example; single-frame revolve |
| `Gen1FrontEndTest` | the scene built from the synthetic bundle matches a golden `scene.json`; flags → kinds/tags; both swap states |

4. **GameTests (server):** `ShapeBuildGameTests`: a synthetic build places a host block at every shaped
   position; collision of a measured-height column equals today's (a mock player cannot enter a solid cell);
   `story_tile` cells carry closed shape ids; the WorldIndex golden gains only the new fields; `world.shapes`
   reaches a mock player after `world.slots` and only with a matching hash (the relay tests' pattern,
   `docs/PROGRESS.md:633-637`).
5. **Client logic without client GameTests:** everything testable is pure (items 1–3). The Minecraft
   adapters (emitter, renderer, reload listener) stay thin and are covered by the mixin/target checks that
   already run in `check` (`docs/PROGRESS.md:643`) plus the play-test.
6. **Play-test checklist items** (for `docs/PLAYTEST.md`, the user's):
   - Pallet Town from the south: houses read like the 2D game; from the north: no second front door.
   - Walk around a house, a sign, a lone tree, a fence, a ledge, the shore: no holes, no floating parts,
     no z-fighting; ledge lips and the water edge look right.
   - A cut tree before and after Cut; a card-key door; the crosshair lands on the closed shape.
   - An item on a table sits on the table.
   - F3+T with a test pack: a texture override, a rule override, a `.vox` house, a `.vox` sprite; remove the
     pack: everything falls back.
   - Each character style on: a walking NPC, a standing NPC, an item ball, a boulder, Snorlax; push a boulder.
   - Two players: different packs, same collision; the guest's shapes match the host's.
   - F7: shape counts, fallbacks, pack warnings; frame rate in a town and in a forest.

**Recommendation.** As designed; the in-code fixtures come first because they do not disturb existing
goldens.

**Open.** None.

### B8.2 What the e2e headless build can check from counts alone

**Answer (facts).** The headless build runs the planner and the pack writer inside the server process
(`builder/BuildTask.java:158`), writes `build_report.json` and copies it to `local/reports/`
(`builder/BuildReport.java:14-25`; `builder/BuildOptions.java:17`); `tools/e2e/build_world.py` gates on
counts in that report (`tools/e2e/build_world.py:171-187`). Because the geometry core is pure and in
`src/main` (B3.5), the same process can build the shape table and run the self-check without a client.

**New report fields** (counts, hashes and timings only) and the gate each allows:

| Field | Gate |
|---|---|
| `shape_index_hash`, `shape_planner` | equal across two builds of the same export (determinism); equal for a filtered and a full build |
| `shape_keys`, `shape_keys_by_kind{flat,slab,cube,roof,tuft,cell,tmpl}`, `shape_instances`, `shape_templates` | > 0; within the capacity 3a's block approach allows |
| `shaped_blocks`, `shape_hosts_added` | `shaped_blocks` ≤ `blocks_placed`; column heights within 1..6 (+ roof) |
| `structures`, `structures_by_kind{volume,object,hull,building,fallback}` | `fallback` reported; a sudden jump flags a heuristic regression |
| `closed_shape_cells` | equals `story_cells_by_type.tile` (BR:17) |
| `void_hull_cells` | 0 beyond the configured ring (B3.5) |
| `shape_quads_total`, `shape_quads_max_block`, `shape_quads_p99_block`, `shape_quads_max_section` | under the budgets 3a sets (B2.3) |
| `shape_plan_ms`, `shape_table_ms`, `pack_ms` | under time budgets; trend over runs |
| `selfcheck{maps, holes, ground_match_min, structure_match_min, structure_match_p05, occluded_ground_max, templates_failed}` | `holes == 0`; `ground_match_min` and `structure_match_min` at or above the thresholds of B3.6 |
| `sprites_by_frames{1,3,6}`, `character_fallbacks{carved_to_thick, rigged_to_carved, …}` (when the styles' pure parts run in the build) | reported; a jump flags a heuristic regression |
| `pack_overrides{applied, skipped_by_code}` (client F7 / template tool, not the headless build) | — |

`build_diagnostics.json` (symbols only, `docs/PROGRESS.md:530-533`) can list, per failed template, its
signature hash and map id, never its tile grid.

**Recommendation.** Add the fields with the first shape milestone, before any tier beyond A, so the
estimates of B3.5 become measurements. Changes: `builder/BuildReport.java:27-67, 73-130`,
`tools/e2e/build_world.py:171-187`, `tools/e2e/test_build_world.py`.

**Open.** The numeric budgets come from 3a (B2.3) and from the first measured run.

> **Integrator note.** The report field names are unified in [4.11](#411-build-report-additions).

### B8 addendum: tests for the block approach

**Claim checked: "no client GameTests on 1.21.1" — confirmed (F).** Fabric API 0.116.17+1.21.1 has only
`fabric-gametest-api-v1` 2.0.5, whose classes are the server test hooks (`FabricGameTest`, server `MainMixin`,
`TestServerMixin`, …; jar listing) — there is no client-gametest module among the remapped modules; and the build sets
`enableClientGameTests = false` with the comment "There is no client GameTest module on 1.21.1"
(`fabric-bridge/build.gradle:66-74`; also `refs/_research/P5_mc_content_testing.md:57`). `fabric-loader-junit` starts
Knot but never the game (`src/test/java/dev/kantobridge/McBootstrap.java`), so no renderer is registered in JUnit:
`RendererAccess.INSTANCE.getRenderer()` is null there.

Consequence for the design: keep everything testable **behind a tiny interface** and keep the FRAPI adapter thin.

```java
interface QuadSink { void quad(float[] xyz12, float[] uv8, int textureRef, int cullFace, int layer, boolean emissive); }
// production: an adapter writing to QuadEmitter; tests: a recording sink
```

| Level | Test | Asserts |
|---|---|---|
| JUnit, plain Java | shape-map codec | encode → decode → same lookups; canonical bytes; hash stable; bad input rejected |
| | `shapeAt` lookup | every block of the synthetic plan resolves to the planned id; positions outside maps → 0; ring columns |
| | region lookup | bounds, layers 22 apart (`world/Scale.java:25`), two regions, per-thread cache correctness |
| | face masks | a boundary quad is dropped iff its mask ⊆ the neighbour's opposite mask; symmetric pairs; no hole cases |
| | in-block greedy mesher (3b) through `QuadSink` | covered area = exposed voxel faces; no overlaps; quad count ≤ cap; deterministic order |
| | UV math | atlas UV = sprite rect ∘ normalised UV; inset by the shrink ratio; all UVs inside the sprite |
| | dirty-section set from a story diff | the sections of the changed cells' boxes, nothing else |
| | snapshot publication | readers on other threads never see a half-built snapshot (hammer test with a swap loop) |
| | slot → sprite id | key → path for art set v2; lower-casing and `_` replacement as `pathPart` |
| | `TextureSync` v2 decision table | every row of B6.4 |
| JUnit with `McBootstrap` | block registration | counts; `shape_full` collision == `tile_full` collision; `decor` has empty collision and outline; `canOcclude`, `isSolidRender`, `getLightBlock` per family; state heap per family (pattern of `TileBlockTest#stateMemoryStaysSmall`) |
| | culling contract | `Block.shouldRenderFace` for the pairs (tile_full↔tile_full: culled; shape_full→tile_full: culled; tile_full→shape_full: drawn; decor↔anything: drawn) with a small fake `BlockGetter` |
| | carrier choice | `carrierFor` table of B2.8 on the synthetic plan |
| GameTest (server) | build with shapes on the synthetic bundle | carriers placed as planned; golden shape map reproduced byte for byte (like the golden WorldIndex, `src/gametest/resources/kantobridge/golden/`) |
| | collision invariant | a mock player cannot enter any non-walkable cell and can walk every walkable one, in the shaped build (extends `CollisionGameTests`) |
| | `world.shapes` | sent after `world.slots`, only with matching hashes (extends `MultiplayerGameTests`) |
| | story cells | unchanged behaviour with `story_tile` as a MODEL block |
| e2e headless (counts only) | full real build | `shapes_distinct`, `shape_blocks`, `decor_blocks`, `quads_total`, `quads_per_section_p95/max`, `shape_map_bytes`, hashes; gates: budgets of B2.3, `warnings == 0` |
| Play-test (user) | visual | cubes identical before/after step 1; no holes around shaped blocks; no black faces; closed story art appears/disappears with the hidden game for each of two players; crosshair on signs/counters; Sodium on/off if pinned; F3+T reload; joining a host with another slot map without a reload |
| Runtime self-check (dev flag) | in the model | quads emitted per section equal the pure-Java prediction for that section; logged as counts |

---

## 4. Designs

Each design below is the integrated form of the answers in section 3: where two researchers differed, the
choice made here is the one to implement, and the answer concerned carries an **Integrator note**. Long class
sketches stay in the answers and are linked; the data formats and the pseudo code a coder needs first are
repeated here.

### 4.1 The world generation design

**The choice** ([A2.1](#a21-architecture), option e): a generator that *is* vanilla's noise generator, with
three additions: it hands the world a gated copy of the noise router, it skips or post-processes each
generation stage near the footprint, and it reports the region's biomes. It needs one access-widener line and
no mixin.

**Pieces** (all new; package `dev.kantobridge.worldgen` unless noted):

| Piece | Job | Detail |
|---|---|---|
| `resources/kantobridge.accesswidener` | `extendable class net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator` | The class is `final` (NoiseBasedChunkGenerator.java:49). Declared in `fabric.mod.json` and `build.gradle` |
| `KantoChunkGenerator extends NoiseBasedChunkGenerator` | Type `kantobridge:overworld` in `BuiltInRegistries.CHUNK_GENERATOR`. Overrides `generatorSettings()` (gated settings, read only by `ChunkMap`: ChunkMap.java:183), `createStructures`, `createBiomes`, `fillFromNoise`, `buildSurface`, `applyCarvers`, `applyBiomeDecoration`, `spawnOriginalMobs` | Full class in [A2.1](#a21-architecture) |
| `RegionNoise` with `RegionGate`, `RegionAlpha`, `RegionOffset` | The gate on `finalDensity` and `initialDensityWithoutJaggedness`; `vein_toggle` zeroed near the core; vanilla's `blend_alpha`/`blend_offset` markers replaced so the ramp uses vanilla's own blending maths | `RegionGate` must map its child (correction 3 in 2.1) |
| `RegionBiomeSource` | Wraps the preset's biome source; returns Kanto's biomes in the core and, later, belt biomes | Type in `BuiltInRegistries.BIOME_SOURCE` |
| `RegionField` | The late-bound holder of the footprint(s) for this generator instance; per-chunk kind `INSIDE`/`EDGE`/`NEAR`/`FAR` | Unbound = plain vanilla |
| `RegionFill` | `fill` (inside chunks: foundation, interior air boxes, heightmaps), `repaint` (edge chunks), `repair` (after decoration, 3×3 chunks) | |
| `world/Footprint`, `builder/plan/FootprintPlanner` | The mask, distances, zones and gates; planning from the whole export | [4.2](#42-the-footprint-and-edge-design) |
| `server/RegionBootstrap` | On `SERVER_STARTING`: read or plan the footprint, write the region files, bind the field | Must run before `createLevels` |
| Block `kantobridge:foundation` | Unbreakable fill under Kanto; not carver-replaceable; in `#minecraft:features_cannot_replace`, `#dragon_immune`, `#wither_immune` | Stone-like model |
| `data/kantobridge/worldgen/world_preset/kanto_overworld.json` | The new preset (generator type `kantobridge:overworld`, `multi_noise` overworld biomes, settings `minecraft:overworld` by reference) | The flat preset `kantobridge:kanto` stays |

**Order of events when a world is created** (what changes against today's sequence in
[2.3](#23-part-a-facts-the-builder-and-world-creation)):

```
Client flow (unchanged order): CHECK → EXPORT → PACK → RELOAD → CREATE
  CREATE: preset kantobridge:kanto_overworld; WorldOptions(seed, generateStructures = true, bonusChest = false);
          BuildRequest.submit(folder, bundle, …)                      (as today)
Server thread:
  SERVER_STARTING                                                     (fires before initServer: Fabric MinecraftServerMixin.java:45-48)
    RegionBootstrap: generator is a KantoChunkGenerator?  no → legacy world, nothing to do
      regions/kanto/footprint.json exists → read it
      else plan it from BuildRequest.pending() (client flow) or -Dkantobridge.build (headless):
           layout + interior packing + gate resolution over the WHOLE export → write region.json and footprint.json
      field.bind(footprint)                                           (immutable from here on)
  createLevels → ChunkMap asks generatorSettings() → the gated router → RandomState
               → stronghold rings and the spawn search already see the footprint
  SERVER_STARTED → BuildRequest.take / -Dkantobridge.build → BuildTask (as today):
      plan → must reproduce the stored footprint (subset rule, else fail "footprint_changed")
      place: tickets a window ahead, takes chunks with getChunkNow, writes only what differs
      report: blocks_cleared == 0 and foreign_blocks == 0 on a fresh world
      publish: world.regions, world.footprint, world.index, world.slots
```

**What each stage does, by chunk kind:**

| Stage | `INSIDE` (all columns in the core) | `EDGE` (touches core or apron) | Elsewhere |
|---|---|---|---|
| Structure starts | none start (Kanto biomes); starts whose box meets the core + 32 blocks are replaced by `INVALID_START` | same filter, within 8 chunks + margin | vanilla |
| Biomes | Kanto biomes straight from the footprint (no noise sampled) | by column | vanilla |
| Noise | fast path: foundation to Y 62, interior boxes as air, air above | gated router: exact plane in the apron, blended over the next 8 blocks; then core columns repainted to foundation and apron fluids enforced | vanilla, with the ramp's offset and factor pulled toward the target height out to 96 blocks |
| Surface rules | skipped | vanilla on the plane (grass, sand, gravel by biome) | vanilla |
| Carvers | skipped | masked for core and apron columns (`CarvingMask#setAdditionalMask`) | vanilla |
| Features | skipped, then `repair` | vanilla, then `repair` of core columns in the 3×3 chunks | vanilla |
| Generation-time mobs | skipped | skipped | vanilla |
| `getBaseHeight`, aquifers, ore veins | agree with the blocks, because they read the same gated router | same | vanilla |

**Why nothing can change Kanto after it is built:** features may write one chunk around the chunk being
decorated (ChunkPyramid.java:33-39), but a chunk that is already full drops such writes
(ImposterProtoChunk.java:80-83), and every neighbour of a footprint chunk is decorated before that chunk
becomes full. So all spill happens before the builder runs, and `repair` removes it.

**Constants (one class, to tune in the first play-test):** apron 8 blocks, join 8, ramp 96, structure margin
32, sea shelf top Y 52 for at least 24 blocks, belt 64–128.

**Tests:** direct generator calls on free-standing `ProtoChunk`s in GameTests, pure JUnit for the footprint
and the ramp maths, and the headless build with the new preset ([A6.4](#a64-tests)). The GameTest server
itself always runs vanilla's flat preset (GameTestServer.java:99-105).

**What a Minecraft update can break:** the access-widener target, the overridden public methods, the
`NoiseGeneratorSettings` (11 components) and `NoiseRouter` (15) constructors, and the two marker factories.
All fail at compile time.

### 4.2 The footprint and edge design

**Shape** ([A3.1](#a31-the-footprint)): a per-cell mask (a cell is 2×2 blocks) computed as a hull, always from
the whole export:

```
maps    = union of the surface maps' cell rectangles
grown   = dilate(maps, ring)                         // ring = 6 cells = today's 12-block ring
closed  = erode(dilate(grown, close), close)         // close = 16 cells: gaps narrower than 64 blocks vanish
filled  = closed ∪ enclosed pockets                  // "pockets": "fill" (today's look) or "wild" (later decision)
core    = filled ∪ dilate(projection of every interior box, shell)    // shell = 2 cells
```

Whole columns are reserved, bottom to top. The footprint is fixed for the life of the world: a rebuild may
only produce a subset.

**Cross-section at a land edge** (not to scale):

```
          OUT (vanilla rules)            |  APRON  |            CORE
   ramp: seed terrain eased to Y 63      | flat 63 | ring (3 high) | map cells …
 ~~~~~~~~~\________________________________________|▓▓▓|_________________
  vanilla stone, caves, ores …           | no caves|  foundation to Y 62; interiors are air boxes in it
 d = 96 ………………………………………………… d = 8 ……… d = 0 (core boundary; d is the distance to the core)
```

**The file** `<world>/kantobridge/regions/<id>/footprint.json` (canonical JSON; its SHA-256 is
`footprintHash`). This is the stored and relayed form; the per-cell zone and distance tables are derived from
it at load on both sides by the shared class `world/Footprint`:

```json
{
  "version": 1,
  "region": "kanto",
  "game": "red",
  "exportHash": "<sha256 of the export index>",
  "algorithm": {"id": "ring-close-1", "ring": 6, "close": 16, "pockets": "fill", "shell": 2,
                "apron": 8, "ramp": 96},
  "cellBlocks": 2,
  "origin": [0, 0],
  "surfaceY": 64, "layerStep": 22, "minY": -256,
  "grid": {"x": -6, "z": -6, "w": 32, "h": 30},
  "rows": ["0:32", "0:32", "…"],
  "edges": [{"x": -6, "z": -6, "dir": "n", "len": 32, "kind": "wall"},
            {"x": -6, "z": 9, "dir": "w", "len": 1, "kind": "open"}, "…"],
  "interiors": [{"x": 2, "z": -6, "w": 10, "h": 10, "layer": 1, "biome": "kantobridge:kanto_interior"}],
  "gates": [{"id": "west", "kind": "land", "map": "TEST_TOWN",
             "inside": {"x0": 1, "y0": 9, "x1": 1, "y1": 9}, "dir": "left",
             "corridor": {"cx": -6, "cz": 9, "w": 7, "h": 1}, "cut": 1}]
}
```

(Synthetic values. `rows` are run-length pairs `skip:len` per mask row; `edges` are the core's boundary runs
with the kind that picks the target height and belt biome; `interiors` are the air and biome boxes;
`corridor` is a rectangle of cells in origin-relative cell coordinates.)

**Zones, derived at load:**

```
corridor(c) = c lies in some gate's corridor
CORE  = mask(c) and not corridor(c)
APRON = corridor(c), or not mask(c) and distance(c) <= algorithm.apron
OUT   = everything else
classAt(blockX, blockZ): bounds test, then one array read.  distance(blockX, blockZ): signed, blocks.
```

**Edge kinds and transitions** ([A3.2](#a32-the-edge-inventory), [A3.3](#a33-transitions-per-edge-kind)): the
core's boundary is classified by the border block of the map that owns each ring cell (already computed for
the void fill): water → `sea`, solid → `wall`, `tall` when the owner's nearest edge cells are the 4-high
class; a gate's mouth is `open`.

| Kind | Ring (Kanto's side) | Apron and ramp (vanilla's side) | Belt biome (optional) | Milestone |
|---|---|---|---|---|
| `wall` | today's 3-high ring of border art | top block Y 63 for 8 blocks, then the ramp | forest | M11 |
| `open` (gate mouth) | the corridor's ground | top block Y 63, flat | the neighbour's | M11 |
| `sea` | ring as water half blocks, not a wall | sea floor Y 52 for at least 24 blocks, then the ramp | ocean | M13 |
| `tall` | today's ring | rising from 63 to about 75 over 16 blocks | windswept hills | M13 |

In M11 every non-gate edge is treated as `wall`. The ramp's pseudo code is in
[A3.3](#a33-transitions-per-edge-kind).

**Gates.** A gate is specified in the region record (`{id, kind, map, side}`) and resolved to geometry by the
planner. An outer edge of a Gen 1 map is usually solid art, so the resolver may cut a short corridor through
the map's own edge cells as well as through the ring:

```
resolveGate(spec, model of the map, mask):
  for each line of cells perpendicular to spec.side, scanning inward from the map's edge:
      cut = solid cells before the first walkable ground cell g
      out = cells straight out from the edge to the core's boundary       // ring, and filled gap if any
      reject when: cut > 3; the line reaches water, a ledge or a counter first; g or a cut cell carries a warp,
                   door, sign, object or story cell; a cell of `out` belongs to another map; cut + out > 16
      score = (cut + out, cut, distance of the line from the map's middle, line index)      // shortest first
  none left → fail "gate_unresolved" with a count per reject reason (map id and side only)
  inside G  = {g}                                  // where the hidden player stands and waits
  corridor  = the cut cells, then `out`
  threshold = the corridor cell next to G          // gate blocks stand here, 3 high
  the boundary run at the corridor's outer end becomes edge kind "open"
```

The record may list several specifications in order of preference; the first that resolves is built, and the
report says which (`gates`, with the cut and corridor lengths as counts). That matters because no agent can
look at the real maps: the default list for Red and Blue (decision D3) is a guess from public knowledge of
the game, and the build either confirms it or names the reason it failed.

The builder gives the corridor walkable ground (the ground tile of `g`) and keeps the ring on both sides. The
corridor is `APRON`: walkable in Minecraft, never walked by the hidden game. Plan view (one cell per
character; `G` inside cell, `T` threshold with gate blocks, `=` corridor, `#` ring, `.` map ground, `x` solid
map art, `a` apron):

```
 a a # # # # # # x . . .
 a a = = = = = = T G . .      ← the hidden player waits on G; Minecraft walks G → T → = → a
 a a # # # # # # x . . .
```

A sea gate (M13) is a run of outer water cells with a water corridor; it needs Surf to be left by, and so to
be unlocked.

**Sea level** ([A2.6](#a26-sea-level-and-heights)): M11 changes nothing inside Kanto. A flush sea (Kanto one
block lower, water tiles 14/16 high) is decision D5.

**Sightlines** ([A3.5](#a35-sightlines)): Kanto's surface box is 704 × 744 blocks; at the maximum render
distance (32 chunks, fog from 461 to 512 blocks) the far side cannot be seen from outside. Seeing all of it
needs a distant-terrain mod (decision D10); LODs must then come from saved chunks, not from the generator.

### 4.3 The border state machine

**Who does what** ([A4.2](#a42-leaving--the-gate-model-and-the-border-state-machine),
[A4.3](#a43-entering), [A4.6](#a46-joining-dying-and-respawning-outside)):

| Side | New piece | Job |
|---|---|---|
| Client | `client/region/BorderMachine` (pure Java), `RegionRuntime`, `ClientRegions` | States `OUTSIDE`, `ENTERING`, `FOLLOWING`, `LEAVING`; redefines "following" |
| Hidden game | `gen1-bridge/act/kb_region.lua`, `engine.away(gate)` / `engine.back()` in `kb_engine.lua` | Answers `region.leave` / `region.enter`; pushes the away hold (the pattern of the Bill's PC hold, `kb_engine.lua:3362-3377`); fades and restores the music; keeps `{away, unlocked}` in the save's own mod data |
| Server | `server/RegionService`, `story/RegionPresence`, `block/GateCellBlock` | Presence per player (persistent, copied on death, synced to its owner); the zone tick; the bounce; gate-block collision by presence |

**Transitions:**

| From → to | When | What happens |
|---|---|---|
| `FOLLOWING` → `LEAVING` | Last tick's cell was a gate's inside cell and the feet are now in its corridor. Checked **before** the crossing and drift rules, and any position whose zone is not `CORE` counts as "on no map" for `MovementSync` | Unlink the movement sync (no step, no drift teleport); send `region.leave{region, gate}` |
| `LEAVING` → `OUTSIDE` | `act.result{ok:true, kind:"leave"}` | Presence `{OUTSIDE, home: gate, admitted: gate}`, applied locally first, then relayed as `region.state` |
| `LEAVING` → `FOLLOWING` | Refusal (`not_idle`, `not_at_gate`), 3 s without an answer, or the link drops | Relink; a short forced walk back to the gate cell |
| `OUTSIDE` → `ENTERING` | The feet cross from the threshold onto the inside cell (home gate: the gate block is open), or the player pushes against a closed gate for 10 ticks. Guards: link connected, hidden game in game, pairing open, no Cobblemon battle, not riding | Send `region.enter{region, gate, to}` |
| `ENTERING` → `FOLLOWING` | `act.result{ok:true}` and the feet on a map cell | Presence `INSIDE`; `sync.linkFrom(hidden cell)`: at most one ordinary step, never a teleport |
| `ENTERING` → `OUTSIDE` | Refusal, 5 s, or the link drops | Forced walk back to the corridor; HUD line with the reason |
| join | Presence `NEW` or `INSIDE` in the core → `FOLLOWING` as today (the first start in the bedroom is the one accepted teleport); presence `OUTSIDE` → `OUTSIDE`, no snap, no lock | Table in [A4.6](#a46-joining-dying-and-respawning-outside) |

**Messages** (protocol 1 stays; all additions are new types or optional fields):

```json
{"type":"region.leave","seq":41,"data":{"region":"kanto","gate":"west"}}
{"type":"region.enter","seq":57,"data":{"region":"kanto","gate":"west","to":{"map":"TEST_TOWN","x":1,"y":9,"facing":"right"}}}
{"type":"act.result","seq":90,"re":57,"data":{"ok":true,"kind":"enter"}}
{"type":"region.state","seq":12,"data":{"region":"kanto","inside":false,"home":"west","admitted":"west"}}
```

| Type | Direction | Notes |
|---|---|---|
| `region.leave`, `region.enter` | M→G | Answered by the existing `act.result` (`kind`: `leave`, `enter`, later `enter_warp`; reasons `not_ingame`, `not_idle`, `not_at_gate`, `locked`, `no_party`, `no_move`) |
| `region.state` | C→S, and S→C once on join | The server accepts `inside:true` only when the sender stands in the apron or core |
| `error{code:"region.not_admitted"}` | S→C | After a bounce |
| `snapshot.away` | G→M, optional field | So a reconnecting client knows the hold is up |

**While the player is out:** the hidden player stands on the gate cell under the hold, so NPCs, scripts,
trainer sight, step counters and encounters are frozen and no `battle.start` can arise; the music is faded
out; `party.sync` and `dex.sync` still apply; the pause overlay and the input lock never engage. If the link
drops, gen1-bridge pops the hold so Gen1Recomp can be played alone, and keeps the marker in the save.

**Entry policy:** the home gate always works. A different gate works only if this save has left through it
before (the engine's own `mod.world:warpTo`, like Fly to a visited town); that is M13. Anything else is
refused with a reason, and the client offers today's teleport back to the hidden cell. Why reachability is
not used: badge guards and step triggers are Lua scripts run on step completion
(`refs/gen1recomp/src/world/OverworldController.lua:4691-4707, 4909-4967`), not data.

**Every other way in or out** ([A4.8](#a48-edge-cases-ways-in-or-out-other-than-walking)) is covered by one
rule plus vetoes: a non-creative player whose presence is `OUTSIDE` and who is not admitted at a gate, found
in the core for more than 20 ticks, is moved back to their last good position (a `NEW` player standing at
the world spawn is today's first-join case and is never bounced); riding is refused inside (`CobblemonEvents.RIDE_EVENT_PRE`) and ended at the
edge; gliding is refused over the footprint (`EntityElytraEvents.ALLOW`); portals and block changes are
stopped by the guard of 4.4. Creative players and spectators are never followed, bounced or blocked.

**The minimal version (M11):** one land gate; the home gate only (no unlocked set, no entry warp); no save
on leaving; the three messages, the gate block, the presence attachment, the four states, the away hold and
the bounce.

### 4.4 Region-scoped rules

**Two switches and one fallback.** On the server, `Regions.of(server).classAt(x, z)` replaces "this server
runs the mod". On the client, `MoveRuntime.following()` gains "and the border state is `FOLLOWING`", and a
second query `concerned()` (border state not `OUTSIDE`, or within 32 blocks of the footprint) decides HUD
lines, puppets and notices. A world without region records is a **legacy** world and keeps today's code path
unchanged.

**Per rule** (the full table with file and line for each of the 37 behaviours is
[A1.5](#a15-every-behaviour-that-is-world-scoped-or-server-scoped-today); mechanisms in A5.1–A5.9):

| Rule | Mechanism | At the border | Milestone |
|---|---|---|---|
| Following, input lock, hidden-game screens, right-clicks, pause overlay, Cycling Road brake, warp fade | The one flag `following` ([A5.8](#a58-client-checks-from-kanto-world-to-inside-the-footprint)) | Ends and resumes at a gate | M11 |
| Speed, jump, sprint | `RegionRules.apply(player, zone)`: base values and `walkingSpeed` per zone, on join, respawn, bike change and zone change ([A4.5](#a45-crossing-physically)) | Switches at the footprint's edge; the apron absorbs the one-or-two-tick lag | M11 |
| Building | No game-mode switching. `AttackBlockCallback`, `UseBlockCallback`, `PlayerBlockBreakEvents.BEFORE` deny when the actor **or** the target is inside ([A5.1](#a51-building)) | A player outside cannot reach in; a player inside cannot reach out | M12 |
| Every other block change (fluids, fire, explosions, pistons, growth, commands, other mods) | One server mixin at the head of `Level#setBlock(BlockPos, BlockState, int, int)`, keyed on the **target** position; four early vetoes so nothing drops or half-moves (`Level#destroyBlock`, `FlowingFluid#canSpreadTo`, `PistonStructureResolver#resolve`, `Explosion#explode`); two immunity tags; the builder writes inside `RegionGuard.write(…)` ([A5.7](#a57-block-changing-mechanics)) | Complete for everything that writes through the level | M12 |
| Damage, hunger, effects | `ServerLivingEntityEvents.ALLOW_DAMAGE` by the victim's zone; once a second inside: food full, movement and harmful effects removed ([A5.3](#a53-damage-hunger-and-status-effects)) | The victim's position decides | M12 |
| Vanilla mobs | Empty biome spawners (have), three `without_*` biome tags, `ServerEntityEvents.ENTITY_LOAD` plus a 20-tick sweep, removal deferred through `DeferredRemovals` ([A5.5](#a55-vanilla-mobs)) | Gate blocks are solid for mobs | M12 |
| Cobblemon spawning | One `SpawningInfluence` per player through `PlayerSpawnerFactory.influenceBuilders`: habitat pools only inside, no habitat leak just outside, optional "the 151 only" outside ([A5.6](#a56-cobblemon-spawning)) | No leak either way | M12 |
| Time and weather | Stop forcing the game rules; then per-player `ClientboundSetTimePacket` and rain packets for players inside, blended over the apron ([A5.4](#a54-time-and-weather)) | Each player's own position | M12 (vanilla everywhere), M13 (noon inside) |
| Game rules, Peaceful, Adventure default | Back to vanilla defaults in region worlds once the rows above exist | — | M12 |
| Riding, elytra, portals | Vetoes and the bounce ([4.3](#43-the-border-state-machine)) | — | M12 |
| Bag mirror | Mirrored stacks carry a custom-data marker; only marked stacks are counted, added or removed ([A7.3](#a73-outside-economy)) | Nothing to do at the border | M12 |
| Notices (save mismatch, game version) | Shown only when the border state is not `OUTSIDE` | — | M12 |
| `BuildHold`, saving refusal | Scoped to players whose zone is not `OUT` | — | M12 |

In **M11** only the first two rows change; every other rule stays world-wide as today (Adventure, no mobs,
fixed noon). So M11 shows the world and the gate, and M12 makes the outside real play.

```java
// server/RegionRules (replaces the unconditional body of Rules.applyPlayer / applyMovement in region worlds)
static void apply(ServerPlayer p, byte zone) {
    boolean kanto = zone != Footprint.OUT && !p.isCreative() && !p.isSpectator();
    StoryState s = StoryStates.peek(p);
    float speed = kanto ? Rules.speedFor(s != null && s.bike()) : 0.1F;          // vanilla default
    p.getAbilities().setWalkingSpeed(speed);
    p.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(speed);
    p.getAttribute(Attributes.JUMP_STRENGTH).setBaseValue(kanto ? 0.0 : 0.42);    // vanilla default
    if (kanto) { p.setSprinting(false); /* M12: strip effects, stop riding */ }
    p.onUpdateAbilities();
}
```

**The rules profile** lives in the region record (`rules`, 4.5): a named profile in the mod plus overrides in
the record, including the compatibility profile for the party (`maxDex`, `maxMove`, what to do with members
the game cannot hold), which is the hook for the later "bag requirements per region" idea
([A7.1](#a71-party-and-pokédex-sync-beyond-gen-1)).

### 4.5 The region record

**The choice** ([A8.1](#a81-the-region-record)): wrap, don't grow. The v1 WorldIndex, its schema, its golden
test and `WorldIndex.java` stay as they are and become the per-region index; a small record beside it says
where the region is and which rules it has.

**Files in the world folder:**

```
<world>/kantobridge/
  session.json                        the world id (unchanged)
  slot_map.json                       one per WORLD: block states are global, so their meaning must be too
  world_index.json                    the region's index; stays here for the first region
  regions/<id>/region.json            the record
  regions/<id>/footprint.json         4.2
  regions/<id>/shape_index.json, shape_map.bin      Part B, later
  build_report.json, build_diagnostics.json
```

A world with `world_index.json` and no `regions/` folder is a legacy world: one implicit region `kanto` with
no footprint, where every position counts as inside.

**The record** (the draft companion [`region_record.draft.json`](region_record.draft.json) is this, with
synthetic values):

```json
{
  "version": 1,
  "id": "kanto",
  "game": "red",
  "family": "gen1",
  "exportHash": "<64 hex>",
  "origin": {"x": 0, "y": 64, "z": 0},
  "bounds": {"minX": -20, "minY": -256, "minZ": -20, "maxX": 59, "maxY": 319, "maxZ": 55},
  "files": {"index": "world_index.json", "footprint": "regions/kanto/footprint.json"},
  "hashes": {"index": "<64 hex>", "footprint": "<64 hex>",
             "shapeIndex": null, "shapeMap": null, "artKeys": null},
  "generator": {"footprint": "ring-close-1", "shapes": null},
  "gates": [{"id": "west", "kind": "land", "map": "TEST_TOWN", "side": "west"}],
  "rules": {
    "profile": "kanto",
    "movement": {"walk": 0.1737, "bike": 0.3474, "jump": 0.0, "sprint": false},
    "build": false, "damage": false, "hunger": false, "vanillaMobs": false,
    "time": "world", "weather": "world",
    "compat": {"maxDex": 151, "maxMove": 165, "forms": "base", "heldItems": "ignore",
               "onIncompatible": "leave_out", "minCompatibleToEnter": 1}
  },
  "namespaces": {"artSet": "red", "pools": "kantobridge"}
}
```

- **`id`**: `^[a-z][a-z0-9_]{0,23}$`. It is a protocol id, a folder name and a resource-path segment. The game
  is its own field.
- **`gates`** here are the *specifications*; the resolved geometry is in the footprint, which is the hashed
  input of generation.
- **`hashes`** and **`generator`** are what Part B's client needs to pick its art set, verify the shape map
  and build a matching shape table ([B6.1](#b61-the-region-registry)).
- The slot map's hash stays in the v1 index for now and moves to the root of `world.regions` when a second
  region shares the world's slot map ([B6.3](#b63-several-regions-in-view)).

**To clients** (the relay, in this order on join and after a build):

| Message | Direction | Content |
|---|---|---|
| `world.regions` | S→C | `{index, count, hash, part}`: parts of `{"version":1,"world":"<id>","regions":[<record>, …]}` |
| `world.footprint` | S→C | `{region, index, count, hash, part}`: the footprint file's text; accepted only when the hash equals the record's |
| `world.index`, `world.slots` | S→C | As today, with an optional `region` field (absent = the legacy region) |
| `region.state` | S→C | The player's presence |
| later: `world.shapes` | S→C | The shape index and shape map, ids only (4.6) |

All of it is layout-derived, so it is marked ROM-derived like the index and may pass through the host's
server (rule U8); art never does.

**Lookups** ([A8.2](#a82-position-lookups)): `Regions.of(server)` and `ClientRegions.current()` hold one to
three regions; `at(x, z)` is a bounds test and one array read; a chunk-key set answers "does this chunk touch
a region" for the block-change guard with one hash probe. Kanto's tables are well under 1 MB.

**Sessions and saves per region** ([A8.3](#a83-session-and-pairing-per-world-region), M14): an optional
`region` field on `session.bind`, `session.bound`, `save.begin`, `save.loaded` and `save.mismatch`; absent
means the legacy region, so M9 and M10 behave exactly as today. gen1-bridge's pairing is already keyed by game
version, so Kanto in Blue and Johto in Gold never share a slot even without the field.

### 4.6 The block approach

**The choice** ([B2.8](#b28-recommendation-and-migration)): a hybrid. Nothing changes on the server for
today's blocks; the *look* moves from generated JSON to one runtime model; shaped art gets new carriers.
The final decision is yours after the prototype (decision D11); step 1 below is safe to build either way.

**Blocks:**

| Block | States | Collision / outline | Occludes | Role | Change |
|---|---|---|---|---|---|
| `tile_full_00..15[hi,lo]` | 4,096 | cube | yes | ground, floors, ceilings, the ring, plain walls; cubes with per-face art | none |
| `tile_half_00..15[hi,lo]` | 4,096 | lower half | partly | water | none |
| `shape_full_00..15[hi,lo]` | 4,096 | cube | **no** | shaped art that must collide; the slot is its top tile (fallback cube, distant-terrain colour) | new (step 3) |
| `decor`, `decor_leaves` | 1 each | none | no | visual-only geometry in otherwise-air positions; the leaves variant lets shader packs treat foliage as foliage | new (step 3) |
| `story_tile` | 1 | per player | no | also the feet-level carrier of closed story art | `RenderShape.MODEL` (step 4) |
| `story_cell`, `ledge_collider`, `gate_cell`, `foundation` | — | as designed | — | unchanged by Part B | — |

Three rules follow from how Minecraft 1.21.1 works ([B2.4](#b24-collision-and-interaction)): collision,
outline and occlusion belong to the carrier family, never to the visual shape; occlusion cannot vary by
position (face culling is cached per pair of states); geometry that does not fill the cube must not sit in an
opaque carrier.

**The data path:**

```
builder (server)                         relay                      each client
----------------                         -----                      -----------
plan → shape keys → shape_index.json     world.shapes  ───────────► ShapeMap (position → shape id)
     → shape map (ids only)              (ids only, hashes)                │
     → carriers placed in the world      chunks  ─────────────────► carrier states (kind + base slot)
                                                                           │
own export bundle ─► scene ─► ShapeTable (shape id → quads + texture keys) │   built locally; art never travels
generated pack    ─► block atlas sprites                                   ▼
                                               RenderSnapshot (immutable; one volatile reference)
                                                                           ▼
                             TileBakedModel#emitBlockQuads (mesh worker threads): look up, emit quads
                             getQuads(state, …): the plain cube of the state's slot (fallback, LOD colour)
```

- **The model** is registered once with `ModelLoadingPlugin` and a `BlockStateResolver` per carrier block;
  `isVanillaAdapter()` returns `false`; `emitBlockQuads` reads only the immutable snapshot and never keeps
  `pos`. The verified signatures and the full sketch are in [B2.1](#b21-the-flexible-block-in-fabric-1211).
- **The shape map is synced** (authoritative, like the slot map), and a client may skip the download when its
  cached copy has the announced hash. Re-planning on the client was considered and kept only as a later
  optimisation ([B3.5](#b35-where-generation-runs-data-volumes-build-time-impact),
  [B6.1](#b61-the-region-registry)).
- **Without an entry** (no shape map yet, no shape table, an unknown id) the model draws today's cube. Every
  migration step is therefore shippable.

**Which carrier the builder places** (server side, ids only):

```
carrierFor(block b):
  b is water                              → tile_half[slot(b)]
  no shape, or the shape is an opaque cube → tile_full[slot(b)]          // per-face art is fine
  the cell collides at this height        → shape_full[slot(b.topTile)]
  otherwise                               → the shape is foliage ? decor_leaves : decor
shape map entry for b                     → the shape id (0 when none)
```

**Migration** (each step ends green on `tools/check` and leaves existing worlds playable):

| Step | What changes | World rebuild? |
|---|---|---|
| 1. Runtime model, same cubes | New `client/model/` package; the pack stops writing blockstates and models, so a pack no longer depends on the host's slot map (joining a host with other slots becomes a re-mesh, not a re-export and reload) | no |
| 2. Shape plumbing, cubes with per-face art | Shape index and shape map written beside the slot map; `world.shapes`; shapes of kind "cube with six texture keys" only | no |
| 3. Real shapes | `shape_full_*`, `decor`, `decor_leaves`; carriers chosen from the shape plan; face masks; budgets enforced | yes |
| 4. Story art in the mesh | `story_tile` becomes a model block; closed shapes per story cell; sections marked dirty on story changes; the block-entity renderer goes | yes (carriers) |
| 5. Clean-up and regions | Placeholder files removed; art-set path prefix; region-scoped tables | no |

Until step 4 the closed story art keeps today's block-entity renderer, which keeps working through `getQuads`
([B2.6](#b26-per-player-story-art-with-shapes), [B3.7](#b37-story-cells-and-swap-sites-open-and-closed-shapes)).

**Invariants, each with a test** ([B2.8](#b28-recommendation-and-migration)): the colliding blocks at feet
level and feet + 1 are the same before and after; floors, ceilings and rings stay `tile_full`; a world
without a shape map shows today's cubes; `world.shapes` carries ids only; the model reads only immutable
snapshots; no Indigo class is referenced.

**Budgets and the prototype:** [B2.3](#b23-performance-measure) and [B2.7](#b27-prototype-design-measure);
summarised in [8](#8-experiments-designed-but-not-run).

**Other renderers** (options only; nothing is pinned without your decision, D10):

| Mod | Version for Fabric 1.21.1 | Licence | What it means for us |
|---|---|---|---|
| Indigo | in the pinned Fabric API | Apache-2.0 | The default; nothing to do |
| Sodium | `mc1.21.1-0.8.13-fabric` or `mc1.21.1-0.6.13-fabric` | Polyform Shield 1.0.0 (not open source) | Implements the Fabric rendering API itself (`provides: indium`); our model must use only the public API, read neighbours at most 2 blocks away, and never call `getLightEngine()` |
| Iris | `1.8.8+1.21.1` (Sodium 0.6) or `1.8.14-beta.1+1.21.1` (Sodium 0.8) | LGPL-3.0 | Shader packs identify blocks by block id and state, so a class a pack could care about must be visible in the block id (hence `decor_leaves`; water is already its own family) |
| Distant Horizons | `3.3.3-1.21.1` | LGPL-3.0 | Colours each block **state** from `getQuads`, which is why carriers keep their slot; our invisible story, ledge and gate blocks must go on its ignore list; the −256…319 world is unconfirmed |

### 4.7 The shape generator pipeline

**The choice** ([B3.1](#b31-dramaless-shapes-geometry-inventory-of-archetypes-and-rules-ideas-only),
[B3.3](#b33-a-neutral-scene-description-draft-checked-against-gen-2-and-gen-3),
[B3.4](#b34-determinism-shape-ids-identical-for-every-client)): no third-party tables. A front end per game
family turns the wire export into a neutral in-memory **scene**; a shared planner turns the scene into
**shape keys** that contain tile ids and offsets but never pixels; each client turns keys into geometry with
its own art.

| Layer | Runs | Input → output | Version lives in |
|---|---|---|---|
| Front end (`scene/gen1/Gen1FrontEnd`) | builder and client | export bundle → `Scene` | the scene's `frontEnd` field |
| Planner (`builder/shape/ShapePlanner`) | builder (and any client that wants to check) | scene + placements → shape keys per position, carriers, closed-state keys | **inside the hashed** `shape_index.json` (`planner`, `params`) |
| Geometry (`builder/shape/geom/ShapeTable`) | each client; the headless build for counts and scores only | keys + own art + pack overrides → quads | the client's cache key; never in a world hash |

**Tiers, cheapest first** (each useful alone):

| Tier | Needs | Gives |
|---|---|---|
| A | tile ids only | art folded upright on south faces, measured heights 1–6 blocks, a region vote, doors folded into facades, gable or flat roofs, ledge lips, recessed water. No new textures |
| B | cell-sized pixel work | void tiles, grass tufts, flowers, small objects (signs, posts), cut trees and round trees as hulls |
| C | whole structures | objects split off, facade and roof bands, panes, awnings; **gated per structure by the self-check**, overridable by pack rules and `.vox` models |

**Rules that keep the planner deterministic** (each is a test): integers only; scan order row-major, ties by
lowest tile id then lowest coordinate; every collection that reaches the output sorted; keys from the whole
export, both swap states and the border art, never from the build filter; no pixel may influence a key.

**The shape index** (`<world>/kantobridge/regions/<id>/shape_index.json`, hashed exactly like the slot map;
key forms in [B3.4](#b34-determinism-shape-ids-identical-for-every-client)):

```json
{"version": 1, "planner": "kb-shapes/1",
 "params": {"maxUnit": 6, "minRepeatRows": 4, "tierB": true, "tierC": true, "hullRing": 2},
 "atlases": ["tilesets.test"], "palettes": ["TEST_PALETTE"],
 "geoms": ["cube:0|3,3,3,5,3,3", "flat:0|0", "slab:0|6|8"],
 "templates": [], "instances": [[0, 0], [1, 0], [2, 0]]}
```

**The self-check** ([B3.6](#b36-a-self-check-re-project-the-3d-result-into-the-games-view-and-score-it)):
project every up-facing and south-facing texel with `(x, y, z) → (x, z − y)` and compare, by texel identity,
with the 2D map composed from the export. Five numbers per map and per template (`holes`, `ground_match`,
`structure_match`, `occluded_ground`, `back_faces`); tier A must score exactly; a template under its
threshold falls back to plain columns. Reference and difference images stay in memory on the player's own
machine; only scores are written.

**Export additions** ([B1.5](#b15-what-a-generator-can-use-what-is-missing-and-where-the-missing-pieces-should-come-from)):
`borderDrawn` per map (the block the engine really draws outdoors) and an `animated` list per tileset; the
Java side also starts parsing the exported `spinner` and `pads` classes. Everything else the generator needs
is already in the bundle.

The implementer's view of the whole pipeline, with stage order, the geometry of each key kind, the
pixels-to-quads procedure and the data types, follows.

#### The pipeline in one place (the shapes researcher's formulation, for the implementer)

Everything here is a recommendation written in our own terms; it restates B3.1–B3.7 as an ordered pipeline
with data formats. Stages 0–6 are pixel-free and shared (`src/main`); stages 7–9 use pixels and run only
where the result is drawn (each client; also the headless build, for counts and scores only).

##### Stages

```
Stage 0  scene   = FrontEnds.forGame(bundle.game()).describe(bundle)                       (B3.3, B7.1)
Stage 1  grids   = stitch(scene, placements)
         one grid per connected surface component (all its maps + the ring) and one per interior (+ its ring)
         one grid entry per art tile (8 px for Gen 1) = one block column:
             atlas, tile(open), tile(closed) or -1, cell kind/tags of its 16-px cell, map, palette, isRing
         a region never crosses an atlas change (tile ids of different atlases are not comparable)
Stage 2  class per tile position (palette-free), first match:
             pack rule or front-end hint for (atlas, tile)
             kind WATER -> water | LEDGE -> ledge | COUNTER -> counter
             kind WALK:  the atlas's grass tile on a GRASS cell -> grass ; a "frames" animated tile -> flower ; else ground
             kind SOLID: wall
Stage 3  structures (tile ids only): door fold -> regions -> runs -> region vote -> roofs       (B3.1 sketch)
Stage 4  units and templates (tile ids only):
             cell unit: a SOLID 16-px cell (tiles t0..t3) that is a cut tree, or stands alone (no solid 4-neighbour
                        cell), or repeats as the same 2×2 unit across its region (a tree wall)
                        ground g = most frequent ground tile among its 4-neighbour cells (ties: lowest id;
                        none: the map's most frequent ground tile)
             template:  a bounded region (bbox within a size limit), not a pure repeat; signature = hash of its tile grid
             the rest:  columns
Stage 5  keys:   for every block of every column / unit / template volume -> one ShapeKey (B3.4);
                 hosts: which carrier class each position needs; closed-state keys for swap-site cells (B3.7)
Stage 6  index:  sort keys -> ids -> shape_index.json -> shapeIndexHash                           (B3.4)
------------------------------------------------------------------------------------------- pixels from here on
Stage 7  geometry per key:
             flat / slab / cube / roof : direct quads (below)
             tuft                      : standing cut-outs over flat ground
             cell                      : round test -> hull ; else background test -> object(s) on ground g ; else columns
             tmpl                      : objects split off ; the rest as bands (rule or heuristic) or as columns
             voxels -> shell -> merged quads -> sliced per block (bx, by, bz)
Stage 8  pack overrides: rules (classes, bands), .vox models, texture bindings                    (B4)
Stage 9  self-check per cell unit and template; below threshold -> the column version of the same blocks (B3.6)
```

Volumes are fixed in stage 5 from tile data alone, so the builder can place carriers without pixels: a column
is `h` blocks high (plus one block where a roof rises); a cell unit is its 2×2 blocks × 2 blocks (the drawing
is two tile rows) plus any trunk a rule adds; a template is its footprint × its drawn rows (at most 6) plus
the roof rise. A block of that volume whose mesh turns out empty simply draws nothing.

##### Geometry of the direct kinds (block-local coordinates in 1/16; one Game Boy pixel = 2 units)

```
flat  a|t            cube 0..16; up = tile t; sides and bottom = tile t (today's look) but only emitted where exposed
slab  a|t|h          box 0..16 × 0..h × 0..16; up = t; sides = the lowest h/2 pixel rows of t
                     water: h below the block top by the recess; ledge: a lip of the chosen height on top of the ground
cube  a|u,d,n,s,w,e  cube; each face its own tile; a face with -1 is never emitted
                     south face of band k of a column: tile(tx, front - k)        (the fold rule)
                     up: flat top row rule; north: the column's body tile; west/east: the same band as south
roof  a|u,s,n|h0,h1|hipW,hipE
                     a wedge: top surface from height h0 at the south edge to h1 at the north edge (two triangles
                     as one sloped quad), wearing tile u; vertical south/north faces up to h0/h1 wearing s/n;
                     hip flags lower the outer top edge to the eave on an exposed flank
tuft  a|t            the flat ground quad, plus for each maximal run of "stroke" texels of t (non-lightest shade):
                     an upright quad 2 units thick standing at the tile's own depth; grass: both tiles of the cell
                     row stand; flower: the mask is the union over the animation frames
```

Face conventions (ours; ⚠ the default UV orientation of Minecraft's faces is 3a's to confirm in the emitter):
top faces show the tile as on the map (texture top = north); south faces show it upright (texture top = up);
north faces are mirrored in x so the art is not reversed when walked around; west/east faces use the tile
upright with texture left = north.

##### From pixels to quads (shared by objects, hulls, templates, `.vox` models and carved figures)

```
voxelise:  a boolean grid at pixel resolution plus, per exposed face, a SOURCE = (texture key, u, v) texel
           (or a flat colour for .vox)
shell:     keep faces whose neighbour voxel in that direction is empty
merge:     per direction and layer, grow rectangles greedily: extend along the first axis while the next face
           exists, is unused and its source is the next texel of the same tile along that axis (or the identical
           texel, for stretched faces); then along the second axis while the whole row qualifies.
           never cross a block boundary (every 8 pixels) or an art-tile boundary
emit:      one quad per rectangle: 4 corners in 1/16 units, texture key, uv rectangle, face, cull face when the
           rectangle lies on the block surface, flags (cutout, tint, emissive)
slice:     file each quad under the block (bx, by, bz) that contains it
```

Objects, hulls and bands in our own words (the rules they implement are B3.1 rows 4, 5, 10, 13):

```
background(region):           // which pixels of a solid region are not part of the thing drawn
    image = the region's art + a 1-px apron
    candidate = lightest shade (or transparent)
    seeds     = apron pixels over flat ground: outdoors the south side only, indoors all sides
    flood 4-connected from the seeds through candidates  -> background

objects(region):
    tile is sprite-like when its background share >= T1
    cluster = 4-connected sprite-like tiles; keep a cluster only if it is at most R rows tall, touches flat ground
              on its south side (any side indoors), does not repeat vertically, and has background share >= T2
    for each 8-connected component of its solid pixels: a slab D pixels deep standing in the depth band of the
              tile row holding its lowest pixel; south face = the drawing, north face = mirrored, edges = own texel
    ground under the cluster = the unit's ground tile g
    (T1, T2, R, D are our own tuning constants, chosen by the self-check and a play-test)

round(cell):                   // a tree or boulder drawn in one 16×16 cell
    outline = darkest shade; mask = outline + everything a flood from the cell border through the other shades
              cannot reach; if that encloses almost nothing, flood through the light shades only
    round when the mask is roughly as wide as tall and left/right symmetric within a tolerance
    hull: for each mask row, centre and half-width of its span; a mask pixel at offset dx from the centre becomes
          a column of depth 2*floor(sqrt(r*r - dx*dx)) around the cell's middle; rows below the mask repeat the
          lowest row down to the ground

bands(template):               // only with a pack rule, or when the heuristic of B3.1 finds panes
    silhouette = flood from the image border through the two light shades
    profile(x) = topmost silhouette row of column x              (a drawn taper becomes the roof's slope)
    facade rows: extrude straight back over the footprint depth; panes (small non-dark regions sealed by dark
                 frames) sink one pixel
    roof rows:   laid flat at the facade's top, following profile(x) at the ends, the middle rows cycled to fill
                 the depth; where roof and wall overlap the roof wins
```

##### Data formats

```java
// scene/ (pure; B3.3)
record Scene(String game, String family, String exportHash, Units units, PaletteModel palettes,
             Map<String, SceneAtlas> atlases, Map<String, SceneMap> maps, Map<String, SceneSprite> sprites,
             SceneLayout layout) {}
record Units(int cellPx, int artPx) {}
record SceneAtlas(String image, int tilesPerRow, int tiles, int artPx, List<Plane> planes,
                  List<Animated> animated, int @Nullable [] tilePalettes, Map<Integer, String> classHints) {}
record SceneMap(String id, int w, int h, Env env, String atlas, Map<String, String> palette,
                int artCols, int artRows, Map<Plane, int[]> art,          // tile id per art tile, row-major
                byte[] kind, short[] tags, byte[] dir, byte @Nullable [] edges, byte @Nullable [] elev, short[] raw,
                Border border, Map<Dir4, Connection> connections, List<Warp> warps, List<SceneObject> objects,
                List<SwapSite> swapSites, List<StructureHint> structures, List<PropHint> props) {}
record Env(boolean outdoor, boolean openAir, boolean cave, String dark /* never | flash | always */) {}
record SwapSite(String id, String kind, List<SwapCell> cells) {}
record SwapCell(int x, int y, CellState closed, CellState open) {}       // CellState: kind, tags, 2×2 art tiles
record SceneSprite(String image, int frameW, int frameH, Map<Pose, Map<Dir4, int[]>> frames,
                   boolean mirrorRight, Set<Dir4> alternateMirror, String palette, int transparentShade) {}

// builder/shape/geom/ (pure; B3.5)
record ShapeMesh(ShapeQuad[] quads) {}
record ShapeQuad(byte face,          // 0..5 = down, up, north, south, west, east; 6 = sloped (normal from the corners)
                 short[] xyz,        // 12 values: 4 corners × (x, y, z) in 1/16 block; block-local, may leave 0..16 by
                                     //   at most 8 (an "overflow" quad drawn by a neighbouring host)
                 int texture,        // index into ShapeTable.textureKeys()  (a B4.1 key; resolved to a sprite on the client)
                 byte[] uv,          // u0, v0, u1, v1 in 1/16 of the texture (0..16); 8 values for a sloped quad
                 byte cull,          // face index, or -1: emitted regardless of the neighbour
                 byte flags) {}      // 1 cutout, 2 darken (ceiling), 4 emissive, 8 no ambient occlusion
```

Cache file (client, ROM-derived, stays under `<gameDir>/kantobridge/shapes/`): a header (magic, format,
`shapeIndexHash`, geometry version, overrides hash), the texture-key list, then per shape id a quad count and
the quads in the field order above. Rebuilt whenever any of the three header values differs.

##### Positions that cannot take a carrier

Feet-level blocks of walkable cells can already hold `story_cell`, `story_tile` or `ledge_collider` blocks
(`builder/plan/WorldPlan.java:441-457`). Rules for the planner: collision-relevant shapes never depend on such
a position; decoration that would need one (a tuft on a story cell, an eave over a doorway cell) is either
emitted as an overflow quad by the adjacent host or skipped, and the count of skipped pieces goes into the
report (`shape_decor_skipped`).

#### How the generator's outputs meet the block approach

I read researcher 3a's summary and vocabulary after finishing my answers. The two tracks use the same three terms with the same meaning:
**shape id** (a world-scoped integer naming a shape key), **shape map** (position → shape id, ids only),
**shape table** (shape id → geometry + texture keys, built on each client). What this track supplies to 3a's
recommended hybrid (tile blocks keep the base slot; a runtime model looks the shape up per position):

| 3a needs | This track provides | Where |
|---|---|---|
| World-scoped shape ids and a hash to compare | `ShapeIndex` (`shape_index.json`, `shapeIndexHash`, planner version inside the hash) | B3.4 |
| The shape map | `ShapePlan.shapeAt(x, y, z)`: stored by the builder (S2) or re-planned on the client from its own export when the hashes agree (S3) | B3.5 |
| Which carrier block to place at each shaped position | `ShapePlan.hosts()`: per position a carrier class. Mapping to 3a's families: a full, occluding cube whose faces are all on the block surface (flat ground, Tier A wall columns) → today's `tile_full_*`; anything with inset or overflowing quads (roof wedges, objects, hulls, lips) but solid for the player → 3a's `shape_*` (collides, does not occlude); visual-only positions in air (overhangs, canopies, tufts above ground) → 3a's `decor_*` | B3.5 |
| Geometry per id, thread-safe | `ShapeTable.mesh(id)`: immutable quad lists in 1/16-block units with a texture key, a UV rectangle, a face/cull direction, flags (cutout, tint, emissive) | B3.5 |
| Per-player closed art | closed shape ids per story-cell block in the WorldIndex; `ShapeTable.closedMesh` | B3.7 |
| Texture binding | texture keys resolve to block-atlas sprite ids (`kantobridge:block/tile/<atlas>/<palette>/<tile>` today); the palette comes from the map at that position | B4.1 |
| Budgets | quad counts per shape/block/section as report fields | B8.2 |

Points where 3a's findings constrain the generator (taken from 3a's file, not re-verified by me): a quad
inset inside an opaque full-cube carrier is lit as black, so inset geometry must sit in a non-occluding
carrier; the Fabric emitter does not shrink UVs the way vanilla's face bakery does, so sub-tile UVs need a
small inset in the emitter; per-quad colour and emissive flags are available through the emitter's material
and colour calls. Report-field names proposed by the two tracks overlap (`shapes_distinct` ↔ `shape_keys`,
`shape_quads_max` ↔ `shape_quads_max_block`, `quads_per_section_*` ↔ `shape_quads_max_section`,
`shape_map_bytes`): the integrator should pick one set.

### 4.8 The texture-pack format

**The choice** ([B4.2](#b42-layering-pack-stacking-a-manifest-precedence-validation-fallbacks-reload)): a
pack is an ordinary Minecraft resource pack. Plain texture overrides work by file path today; anything more
goes through a manifest at `assets/kantobridge/kb_pack/<name>.json`, read as a resource stack on every
reload (F3+T included).

**Keys** ([B4.1](#b41-stable-readable-keys)):

| Key | Meaning |
|---|---|
| `tile:<atlas>/<tile>` | A tile of an atlas image, in every palette (a full-colour replacement) |
| `tile:<atlas>/<tile>@<PALETTE>` | The same tile in one palette |
| `sprite:<SPRITE_ID>` | A character sheet or model |
| `cell:<atlas>/<t0>-<t1>-<t2>-<t3>` | A cell-sized unit (a tree, a sign) |
| `struct:<atlas>/<sig>` | A bounded structure by the signature of its tile grid |
| `place:<MAP_ID>/<n>` | One placement of a structure |
| `class:<name>` | A tile class, for shape rules |
| `map:<MAP_ID>` | Per-map dressing |

The game is not part of a key: a manifest names the games it targets, because Red and Blue share their tiles.
Precedence for one face or shape: `place:` > `struct:` / `cell:` > `tile:@palette` > `tile:` > `class:` >
the generated base. A pack can never leave a hole: an invalid entry is skipped with a counted warning and the
base is used. The client writes the list of valid keys to `<gameDir>/kantobridge/keys/<game>.json`.

**The manifest** is the draft companion
[`texture_pack_manifest.draft.json`](texture_pack_manifest.draft.json): `kantobridge_pack`, `id`, `targets`,
`textures`, `models` (`.vox`), `rules`, `characters`, `dressing`.

**`.vox` models** ([B4.3](#b43-what-a-pack-can-contain)): the first model of the file; `voxelsPerBlock` 8, 16
or 32; at most 256 voxels per axis; palette index 0 is empty. Our axis mapping: vox z → up, vox x → east,
vox y → north, so the face seen from MagicaVoxel's front looks south, the side the game draws. The x/y
handedness is not fixed by the format text, so the template export ships a calibration model.

**Where the generated pack sits:** today it is inserted above the player's packs at every start
([B1.2](#b12-the-generated-pack)). After migration step 1 of 4.6 it holds only key-addressed textures and can
sit below them; manifest overrides work at either position because they use the pack's own paths.

**Rules** ([B4.4](#b44-multiplayer), [B4.5](#b45-legal-and-data-rules)): packs are client-local and change
nothing the server decides; we ship no pack and no rule table; the generated base and the template's
reference files are ROM-derived and stay on the player's machine; a "prepare for sharing" step leaves
generated files out.

### 4.9 Character styles

One interface inside the existing `PuppetRenderer`; the entity, the manager, culling and textures stay
([B1.4](#b14-puppets-rendering-textures-frame-selection-sizes-culling-the-surf-sprite-other-players)):

```java
interface CharacterStyle {                    // client/puppet/style
    boolean worldOriented();                  // a real 3D object turned by its own facing, or a billboard
    void emit(VertexConsumer out, PoseStack.Pose pose, int light, FigurePose figurePose);
}
```

| Style | How | Vertices per figure | Falls back when |
|---|---|---|---|
| Flat card (today) | one quad, yaw billboard | 4 | — |
| **Thick card** (recommended default) | front and back quads plus an edge strip around the silhouette, 2 sprite pixels deep, still a yaw billboard: identical to today from the front | about 200 | more than 256 quads, or an empty mask → flat |
| Carved figure | the visual hull of the front, back and side frames of each pose, painted through UVs into the same sheet; walk frames carved too; turned by the NPC's own facing | about 1,000–1,600 | fewer than 3 frames (items and boulders are revolved instead); rows disagree; projection differs by more than 3%; thin parts; more than 600 quads → thick |
| Rigged figure | head, torso, arms and legs split from the sprite's rows; six boxes built with `LayerDefinition.create(…).bakeRoot()`; a generated unwrap texture; limbs swung like a mob | 144 | not humanoid by the splitter's test, or the boxes cover less than 85% of the sprite → carved (if enabled) → thick |

Algorithms and verified signatures: [B5.2](#b52-thick-cards), [B5.3](#b53-carved-voxel-figures),
[B5.4](#b54-rigged-figures). A setting `characters.style` (`flat | thick | carved | rigged`) in the client
config; packs may replace single sprites with `.vox` models. Players keep their Minecraft skins; a generated
trainer is a later, client-local option ([B5.6](#b56-how-players-look)). All cost figures are estimates until
the "64 puppets" experiment is run.

### 4.10 How Parts A and B meet

One region record ([4.5](#45-the-region-record)), read by both:

| Consumer | Reads | For |
|---|---|---|
| Generator (server) | the footprint: mask, edges, interiors, gates | What not to generate, target heights, belt biomes |
| Builder (server) | the footprint; the index it writes; the gate corridors | The subset rule; the ring; corridors and gate blocks |
| `Regions` (server), `ClientRegions` (client) | bounds, the footprint | Zone and distance lookups; the border machine; rule scoping |
| Border machine, gen1-bridge | gates | Where following ends and resumes |
| Rules (server and client) | `rules` | The profile per zone; the compatibility profile for the party |
| Texture flow (client) | `game`, `hashes.artKeys`, `namespaces.artSet` | Which local art set draws the region |
| Model (client) | `hashes.shapeIndex`, `hashes.shapeMap`, `generator.shapes` | Verify the synced shape map; build a matching shape table, or fall back to cubes |
| Packs (client) | `game` | Which manifests apply |

What must be namespaced now so a second region needs no redesign ([B6.5](#b65-namespacing-now)): an art-set
segment in every generated texture path; the art set in slot-map keys; block ids that never name a region or
a game; an optional `region` field on the per-region relay messages; client lookups through `ClientRegions`;
the closed set of games in the WorldIndex parser turned into a lookup.

### 4.11 Build report additions

One set of names for the fields the researchers asked for (counts, hashes, boxes and timings only; map ids
only in `build_diagnostics.json`):

| Group | Fields | Milestone |
|---|---|---|
| Footprint | `footprint{version, hash, cells_w, cells_h, core_cells, apron_cells, perimeter_cells, pocket_cells_filled, bbox, chunks_inside, chunks_edge, bytes, bytes_deflated}` | M11 |
| Surface and interiors | `surface{box, map_cells, void_fill_cells, chunks}`, `interior{bbox, y, volume_blocks, by_layer, outside_surface_box, outside_surface_maps, max_overhang_blocks, chunks_interior_only}` | M11 |
| Edges and gates | `edges{by_side{north,south,west,east}{wall2, wall4, sea, open}, maps_with_outer_edges, border_blocks{solid, water, walkable}, connections, core_perimeter_runs{wall, sea, open, tall}}`, `gates{land, sea, cells, cut_cells}`, `habitat_blocks_near_edge` | M11 |
| Generation | `gen_ms`, `chunks_generated{inside, edge, outside}`, `max_tick_ms`, `foreign_blocks`, `fluid_blocks_in_core`, `entities_in_core`, `structure_starts_rejected` | M11 |
| Blocks | `blocks_by_kind{tile_full, tile_half, shape_full, decor, story_cell, story_tile, ledge, gate_cell, light, habitat}`, `cells_by_kind{ground, water, ledge, counter, solid}`, `solid_cells_by_height` | M15 |
| Shapes | `shape_index_hash`, `shape_planner`, `shape_map_hash`, `shape_map_bytes`, `world_index_bytes`, `shape_keys`, `shape_keys_by_kind{flat, slab, cube, roof, tuft, cell, tmpl}`, `shape_instances`, `shape_templates`, `shaped_blocks`, `shape_hosts_added`, `structures`, `structures_by_kind{volume, object, hull, building, fallback}`, `closed_shape_cells`, `void_hull_cells`, `shape_decor_skipped` | M16–M17 |
| Quads and time | `shape_quads_total`, `shape_quads_mean_block`, `shape_quads_max_block`, `shape_quads_p99_block`, `shape_quads_section_p50`, `shape_quads_section_p95`, `shape_quads_section_max`, `densest_section{quads}`, `shape_plan_ms`, `shape_table_ms`, `pack_ms` | M16–M17 |
| Self-check | `selfcheck{maps, holes, ground_match_min, structure_match_min, structure_match_p05, occluded_ground_max, templates_failed}` | M17 |
| Characters | `sprites_by_frames{1, 3, 6}`, `character_fallbacks{…}` | M18 |

(Where an answer in section 3 uses another name, this table wins: `shapes_distinct` is `shape_keys`,
`shape_quads_max` is `shape_quads_max_block`, `quads_per_section_*` are `shape_quads_section_*`,
`shape_blocks` is `shaped_blocks`.)

### 4.12 Cross-checks

Every recommendation checked against the spec's §7:

| Check | Verdict | How it holds |
|---|---|---|
| **Hard rules** | Pass | No ROM-derived file was opened; real numbers come from `local/reports/*.json`. Art is generated on each client from its own export and never travels: the relay carries layout, ids and hashes only (`world.regions`, `world.footprint`, `world.shapes`), marked ROM-derived like the index. Nothing is copied from Dramaless Shape or Kanto in First Person: their techniques are described in our own words and no table value appears; using Dramaless's tables stays a licence decision (D12). No pin changes: Sodium, Iris and Distant Horizons are options (D10). `docs/DESIGN.md` is untouched |
| **Story safety** | Pass, with two open checks | Gates are the only openings; the hidden game decides every entry and only ever lands on a cell this save has stood on; the hidden game is frozen while the player is out; the server bounce and the vetoes close other routes; a refused step or a refused entry is an existing, recorded correction (forced walk, HUD reason, counter). Open: the riding hole in today's Kanto (experiment B1) and starting to surf after a sea-gate entry (B7, M13) |
| **Multiplayer** | Pass | Story state and story art stay per player (the gate block copies the story cell's per-entity collision; closed art is meshed per client); every client → server message affects only its sender, `region.state` included, and the server checks it against the sender's position; presence is synced to its owner only; players inside and outside coexist; `BuildHold` is scoped to the region in M12. One decision: whether a region's index goes to clients that never link a hidden game (D18) |
| **Performance** | Budgets and plans exist; nothing measured | Chunk generation ([A2.10](#a210-performance-measure) E1–E3), build time (E2), mob-spawn dilution (E4), the guard's cost (B4), mesh time, frame rate and memory ([B2.3](#b23-performance-measure), [B2.7](#b27-prototype-design-measure)), puppets ([B5.2](#b52-thick-cards)), frame rate over Kanto ([A3.5](#a35-sightlines)). All in [8](#8-experiments-designed-but-not-run) |
| **Version risk** | Named below | Every touch point fails at compile time or in the existing `verifyMixinRemap` / `verifyMixinTargets` checks |
| **Consistency** | Pass after the integrator notes | Differences settled: the footprint file format and where gates live; the region id; one slot map per world; the shape map is synced; how time is overridden inside; the three craft recipes; the pack's position; report field names. Parts A and B share the record of 4.5 |
| **Later goals** | Nothing blocks them | Johto: `RegionField` holds a list of footprints; regions sit in 4,096-block slots (the west slot for Johto); the record has `game`, `family` and a compatibility profile; pairing is already keyed by game version; texture paths carry the art set; the scene description was paper-checked against Gen 2 and Gen 3. Bag requirements: `rules.compat` is evaluated at the gate. Carrying progress between saves: untouched by this design (a later decision about ownership) |

**Mixins and internals touched, and what an update would break:**

| Touch point | Kind | Milestone | What breaks on a change |
|---|---|---|---|
| `NoiseBasedChunkGenerator` made extendable | access widener (new for this mod) | M11 | Build fails if the class is renamed; if it stops being the overworld's generator the subclass must follow |
| `NoiseBasedChunkGenerator#generatorSettings`, `createStructures`, `createBiomes`, `fillFromNoise`, `buildSurface`, `applyCarvers`, `applyBiomeDecoration`, `spawnOriginalMobs` | overrides of public methods | M11 | Compile errors on signature changes |
| `NoiseGeneratorSettings`, `NoiseRouter` constructors; `DensityFunctions.blendAlpha()/blendOffset()`; `CarvingMask#setAdditionalMask`; `StructureStart.INVALID_START` | vanilla API | M11 | Compile errors |
| `Level#setBlock(BlockPos, BlockState, int, int)`, `Level#destroyBlock`, `FlowingFluid#canSpreadTo`, `PistonStructureResolver#resolve`, `Explosion#explode` | five server mixins (new) | M12 | `verifyMixinTargets` fails the build |
| `ThrownEnderpearl#onHit`, `PortalForcer` | optional mixins | later | same |
| Existing client mixins (`BuiltInPackSource`, `GameRenderer`, `KeyboardInput`, `LivingEntityRenderer`, `LocalPlayer`) | unchanged | — | as today |
| `ModelLoadingPlugin`, `BlockStateResolver`, `FabricBakedModel`, `QuadEmitter`, `RenderMaterial` | Fabric API | M15 | The model classes are a rewrite on a Minecraft version whose model pipeline changed; the data design is not |
| `LevelRenderer#setSectionDirty/setBlocksDirty/allChanged`, `InvalidateRenderStateCallback` | vanilla + Fabric | M15 | low |
| Cobblemon: `PlayerSpawnerFactory.influenceBuilders`, `SpawningInfluence`, `CobblemonEvents.RIDE_EVENT_PRE`, the `habitat_` id prefix | Cobblemon 1.8.1 API | M12 | Re-check on a Cobblemon pin change |
| Gen1Recomp: a hold state on `game.stack`, `Music.fadeOut`/`restoreMap`, `mod.world:warpTo`, `mod.save`, `TileRenderer.borderBlockFor`, the animated-tile query | engine internals, all inside `kb_engine.lua` | M11, M13, M16 | Re-check on every Gen1Recomp pin change (CLAUDE.md) |

---

## 5. Decisions for the user

Each row has the options and a recommendation. "Needed by" is the milestone that cannot start, or cannot be
finished, without the decision; a default is named wherever work can proceed without it.

| # | Decision | Options | Recommendation | Needed by |
|---|---|---|---|---|
| D1 | **The generator architecture** ([A2.1](#a21-architecture)) | (a) a subclass of vanilla's noise generator, through one access-widener line (the project's first); (b) a delegating wrapper plus a mixin on `ChunkMap`; (c) pure data (a copied 2,541-line noise settings file) plus mixins for carvers, structures and spill | **(a).** No mixin, no copied vanilla JSON, every break is a compile error | M11 |
| D2 | **Kanto's placement and surroundings** ([A2.8](#a28-placement-and-surroundings), [A3.1](#a31-the-footprint)) | Where: at the origin as today; a seed-chosen coast; its own island. Shape: a hull with the gaps between routes filled as today; gaps left as wild terrain; the plain rectangle. Around it: a height ramp only, or belts whose biome and height follow the kind of edge. Later regions: 4,096-block slots, Johto to the west | **Origin, hull with filled gaps, edge-kind belts** (belts arrive in M13; M11 uses a flat apron and the ramp) | M11 (shape), M13 (belts) |
| D3 | **Gates, and the entry and exit policy** ([A4.2](#a42-leaving--the-gate-model-and-the-border-state-machine), [A4.3](#a43-entering)) | Which gate first: the west end of Route 22 (the road that leads toward Johto in the later games; chosen from public knowledge of the map, not from the export), or another early map side; the resolver takes an ordered list and the build report says which one it could open. Entry: the gate you left by only; that plus gates this save has used (a hidden-only warp, like Fly); or free entry at any gate as a "sequence breaks allowed" world option. Whether the hidden game saves itself when you leave | **West of Route 22 first; home gate plus used gates; save on leaving** (silent, like the Pokémon Center save) | M11 (first gate), M13 (the rest) |
| D4 | **The world spawn and the first start** ([A2.7](#a27-world-creation-ux), [A4.6](#a46-joining-dying-and-respawning-outside)) | In Pallet Town as today; just outside the main gate; a viewpoint | **Pallet Town for M11** (no change). From M12: **outside the main gate**, with a new story still starting in the bedroom (the one accepted teleport), so nobody without a linked game spawns inside Kanto | M12 |
| D5 | **Sea level** ([A2.6](#a26-sea-level-and-heights)) | Keep Kanto's heights (a 0.61-block step up from the vanilla sea onto Kanto's water); a flush sea (Kanto one block lower and water tiles 14/16 high: changes P1 and P6 and the look of water inside); a custom vanilla sea level (not recommended: 1.21.1 has two sea levels then) | **Keep for M11–M12; decide the flush sea when a sea edge can be looked at** | M13 |
| D6 | **Game mode and harshness outside** ([A5.1](#a51-building), [A5.9](#a59-inventory-and-respawn)) | Survival outside with position-based protection inside (no mode switching), or Adventure everywhere. `keepInventory` and immediate respawn world-wide, or vanilla death outside. Whether advancement and death messages come back | **Survival; keep `keepInventory` and immediate respawn at first** (the bag mirror has no new failure mode); chat rules back to the world's settings | M12 |
| D7 | **Time and weather** ([A3.6](#a36-sky-and-time-in-one-overworld), [A5.4](#a54-time-and-weather)) | Vanilla day, night and rain everywhere, Kanto included; vanilla outside and "always noon and clear" inside, blended over the apron; frozen noon everywhere (today) | **Vanilla outside; noon inside as a region setting that is on by default** | M12 (vanilla on), M13 (noon inside) |
| D8 | **Species outside Kanto** ([A5.6](#a56-cobblemon-spawning), [A7.1](#a71-party-and-pokédex-sync-beyond-gen-1)) | Outside spawns limited to the 151 (a world setting); the full Cobblemon dex outside, with species the hidden game cannot hold left out of its party (today's behaviour) and a HUD line saying so; refuse entry while the party holds one. Whether such Pokémon may fight in Kanto at all | **"The 151 only" on at first, as a setting; always keep "leave out and tell the player"; refuse entry only when the story has a party and none of it is compatible** | M12 |
| D9 | **The economy outside** ([A7.3](#a73-outside-economy), [A5.6](#a56-cobblemon-spawning)) | Healing machine, PC and pasture recipes stay disabled, or come back. Battle items: two economies (the hidden bag's copies are marked; items found outside stay yours) or one. Which of Kanto's Cobblemon settings stay world-wide (no passive healing, experience ×1, no loot) | **Recipes stay disabled until you decide; marked stacks; the listed settings stay world-wide** | M12 |
| D10 | **LOD and extra renderers (pins)** ([B2.2](#b22-compatibility), [A3.5](#a35-sightlines)) | None; Sodium (`mc1.21.1-0.8.13` or `0.6.13`, Polyform Shield); Iris (`1.8.8`, or `1.8.14-beta.1` with Sodium 0.8; LGPL-3.0); Distant Horizons (`3.3.3-1.21.1`, LGPL-3.0), the only way to see all of Kanto from afar | **Pin nothing now.** Allow a dev-only Sodium pin for the M15 measurement; try Distant Horizons after M12 in one session of your own | M15 (prototype) |
| D11 | **The block approach** ([B2.8](#b28-recommendation-and-migration), [B2.7](#b27-prototype-design-measure)) | Hybrid: runtime model, slots stay in block states, shape ids looked up per position; pure static (a state and generated JSON per shape); one stateless flexible block | **Hybrid, confirmed by the prototype's thresholds.** Approve the throw-away prototype branch | M15 → M16 |
| D12 | **Dramaless Shape's tables (licence)** ([B3.1](#b31-dramaless-shapes-geometry-inventory-of-archetypes-and-rules-ideas-only)) | Never use them (our own rules from art and data, plus rules that pack makers write); use `voxel_heights.lua` (MIT is credible for the 2.0 work only; ties shapes to Gen 1 tile ids) | **Never; user-written rules arrive through packs** | M16 |
| D13 | **Characters** ([B5.5](#b55-settings-consistency-packs-and-dramaless-shapes-argument-for-flat-cards), [B5.6](#b56-how-players-look)) | Default style: flat, thick card, carved, rigged. Players: Minecraft skins, or a generated trainer | **Thick cards by default; carved and rigged as options; skins** | M18 |
| D14 | **How far texture packs go** ([B4.3](#b43-what-a-pack-can-contain), [B4.6](#b46-tooling-for-pack-makers)) | Textures only; plus shape rules; plus `.vox` models; plus a template export that writes reference textures and models generated from your ROM to your own disk | **All four, in that order; the template marks every generated file and a "prepare for sharing" step leaves them out** | M19 |
| D15 | **Which dressing to keep** ([B3.2](#b32-kanto-in-first-persons-dressing-builder-geometry-or-client-effect)) | Interior walls and ceilings from the room's own art; no ceiling over open-air "interiors" (forest, Safari); building backs; trunks; cave roofs; canopy; apron art | **Walls, ceilings and open-air interiors first; the rest after shapes exist** | M20 |
| D16 | **The dimension-type override** ([A1.2](#a12-the-vertical-budget)) | Keep it for every world; ship it as a built-in pack enabled only for Kanto worlds; (a thinner layer step is ruled out by DESIGN §3) | **Keep; measure the mob-spawn effect outside (experiment E4) and add the one-line fix only if it shows** | M12 |
| D17 | **Your existing flat world** ([A2.11](#a211-existing-worlds), [A6.6](#a66-migration)) | Keep it as a legacy world; convert in place (rejected); a new vanilla-world Kanto that imports the player side (Cobblemon data, pairing, daycare) | **Keep as legacy; add the import step in M14** | M14 |
| D18 | **Smaller ones** | Who receives a region's index in multiplayer: every client, or only clients with a linked hidden game ([A4.7](#a47-multiplayer-some-players-inside-some-outside)). Pin the hidden game's "void fill" display option ([B1.5](#b15-what-a-generator-can-use-what-is-missing-and-where-the-missing-pieces-should-come-from)). Hide the block outline around thin art ([B2.4](#b24-collision-and-interaction)). Operators exempt from the bounce by game mode or by permission level ([A4.8](#a48-edge-cases-ways-in-or-out-other-than-walking)). A separate story per singleplayer world ([A8.3](#a83-session-and-pairing-per-world-region)) | Every client (allowed by U8); pin it; keep the outline until seen; by game mode; no (unchanged) | as they come up |

---

## 6. Milestone plan

In the style of [PLAN.md](../PLAN.md) §6, continuing its numbering. Every milestone ends with `tools/check`
green (plus `--e2e` where listed), PROGRESS.md updated, new items in PLAYTEST.md, and a commit. Track tags as
in PLAN: **[L]** gen1-bridge Lua, **[C]** client, **[S]** server/common, **[B]** builder, **[T]** tools and
tests, **[D]** docs and protocol. Protocol and schema changes come first inside each milestone (PLAN §7).

Sizes are relative to M0–M10: **S** about the size of M1, **M** about M6 or M7, **L** about M8.

| # | Milestone | Size | Depends on | Your decisions first |
|---|---|---|---|---|
| M11 | Kanto in a vanilla world, with one gate | L | — | D1, D2 (shape), D3 (first gate) |
| M12 | Real play outside: every rule by zone | L | M11 | D4, D6, D7, D8, D9, D16 |
| M13 | Edges, more gates, sky | M | M12 | D2 (belts), D3 (policy), D5 |
| M14 | Regions as data | M | M12 | D17 |
| M15 | Runtime model with the same cubes, and the prototype | M | M11 (or none) | D10 (dev pin), D11 (branch) |
| M16 | Scene, shape planner, per-face art | L | M15 | D11, D12 |
| M17 | Real shapes | L | M16 | — |
| M18 | Characters | M | — (any time after M11) | D13 |
| M19 | Texture packs | M | M16 | D14 |
| M20 | Story art in the mesh, dressing, structure templates | M | M17 | D15 |

Part A is M11–M14; Part B is M15–M20. M15 and M18 can run beside M12–M14 (different files).

### M11: Kanto in a vanilla world, with one gate

The smallest step that shows Kanto inside vanilla terrain with one working gate. It has three work packages;
each ends green on its own, in this order. Outside Kanto, M11 gives walkable vanilla terrain under today's
world-wide rules (Adventure, no mobs, fixed noon); M12 makes the outside real play.

**M11a: the world** (generator, footprint, builder)

- [D] `protocol/world/footprint.schema.json`, `protocol/world/region.schema.json` with synthetic examples
  ([4.2](#42-the-footprint-and-edge-design), [4.5](#45-the-region-record)); PROTOCOL.md "The footprint" and
  "The region record".
- [S] Access widener: new `fabric-bridge/src/main/resources/kantobridge.accesswidener`
  (`accessWidener v2 named` / `extendable class net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator`),
  `"accessWidener"` in `fabric.mod.json`, `loom { accessWidenerPath … }` in `build.gradle`.
- [S] New package `dev.kantobridge.worldgen`: `KantoChunkGenerator`, `RegionBiomeSource`, `RegionNoise`
  (`RegionGate`, `RegionAlpha`, `RegionOffset`), `RegionField`, `RegionFill`, exactly as sketched in
  [A2.1](#a21-architecture); the three codec registrations in `KantoBridge#onInitialize` next to
  `KbBlocks.register()` (`KantoBridge.java:48`).
- [S] New preset `data/kantobridge/worldgen/world_preset/kanto_overworld.json` and lang key
  `generator.kantobridge.kanto_overworld`; the flat preset and its tag stay untouched.
- [S] `KbBlocks`: `kantobridge:foundation` (unbreakable, stone-like model), with tag files
  `data/minecraft/tags/block/features_cannot_replace.json`, `dragon_immune.json`, `wither_immune.json`.
- [S] New `world/Footprint` (pure Java, both sides: mask, exact distance transform, zones, gates, canonical
  JSON codec and hash) and `world/RegionRecord`; new `server/RegionBootstrap` on
  `ServerLifecycleEvents.SERVER_STARTING` (read, or plan from `BuildRequest.pending()` /
  `-Dkantobridge.build`, write, bind).
- [B] New `builder/plan/FootprintPlanner` (the hull, the edge runs, the interior boxes, the gate resolver),
  planned from the whole export. `WorldPlanner` (`:120-124, 208-258`) and `InteriorPacker` (`:86-155`): void
  fill over the mask, interiors kept inside the core. `WorldPlan` (`:254-280, 396-409`): the surface volume
  per mask; the corridor's ground. `BuildTask`: the subset rule (`footprint_changed`), hashes into the record
  and the report. `BlockPlacer` (`:207-216`): tickets a window ahead and `getChunkNow`; the counted
  `foreign_blocks` pass. `HabitatService.remove` (`:215-221`) restores foundation.
- [C] `CreateKantoWorldFlow` (`:93-94, 420-422`): a choice "Classic (empty) / In a Minecraft world";
  `generateStructures = true` for the new preset.
- [T] `tools/e2e/build_world.py`: `--preset flat|overworld`, a fixed `level-seed`, the new report gates; the
  report fields of the first four rows of [4.11](#411-build-report-additions).
- [T] JUnit: `FootprintTest`, `RampTest`, `FootprintPlannerTest`. GameTests: `generatorRegistered`,
  `insideChunk`, `farChunkIsVanilla`, `apronChunk`, `baseHeightAgrees`, `structureFilter`,
  `builderOnFoundation`, and a built edge ticked 200 times with 0 fluid blocks in core columns
  ([A6.4](#a64-tests)).

Checks for M11a: `tools/check` green; `build_world.py --preset overworld` (Pallet set) reports
`status ok`, `blocks_cleared 0`, `foreign_blocks 0`, `fluid_blocks_in_core 0`, `entities_in_core 0`, the same
`footprint.hash` on two runs with one seed, and today's gates; the flat build still passes unchanged.

**M11b: zones** (region record to the client, movement by zone, the one flag)

- [D] PROTOCOL.md and schemas: `world.regions`, `world.footprint` (S→C); catalogs regenerated.
- [S] New `world/Regions` (`of(server)`, `legacy()`, `classAt`, `distance`, `touchesChunk`);
  `WorldIndexSync` sends the two new messages before `world.index`; `server/RegionRules.apply`
  ([4.4](#44-region-scoped-rules)) called from `JOIN`, `AFTER_RESPAWN`, the bike change
  (`StoryStateService.java:45-47`), `BuildHold` release and a zone change; `Rules` keeps today's path when
  `Regions.of(server).legacy()`.
- [C] New `client/region/ClientRegions` and `RegionRuntime` (records and footprint from the relay; local
  zone and distance); `SpeedSync.sprintBlocked` gains "or the local zone is not `OUT`".
- [T] GameTest `RegionRulesGameTests`: a mock player moved OUT → APRON → CORE → OUT has the expected speed,
  jump and `walkingSpeed` two ticks after each move; the game mode is never touched; a legacy world behaves
  as today. JUnit: `RegionsTest`, relay round trip of the two messages.

**M11c: the gate** (border machine, away hold, presence)

- [D] PROTOCOL.md and schemas: `region.leave`, `region.enter` (M→G), `region.state` (C→S, S→C), the new
  `act.result` kinds and reasons, the optional `snapshot.away`, the error code `region.not_admitted`;
  `kb_catalog.lua` regenerated.
- [L] New `gen1-bridge/act/kb_region.lua`; `engine.away(gate)` / `engine.back()` in `lib/kb_engine.lua` (a
  hold state like `holdPc`, `Music.fadeOut` / `Music.restoreMap`); the `{away}` marker in `mod.save`;
  wiring in `main.lua`. The home gate only: `region.enter` succeeds when the hold is up for that gate or the
  hidden player stands idle on its inside cell.
- [S] `block/GateCellBlock` and its block entity (the gate id), registered beside `story_cell`
  (`KbBlocks.java:56-63`); `story/RegionPresence` (persistent, copied on death, synced to its owner);
  `server/RegionService`: the zone tick, the `region.state` handler, the bounce; `WarpService` refuses a
  request from a player whose presence is `OUTSIDE`; `AFTER_RESPAWN` moves a bed-less `OUTSIDE` player to the
  gate's apron.
- [B] The corridor and the gate blocks (three high on the threshold) in `WorldPlan.renderChunk`; new
  `BlockCode` kind and `CodeStates` entry.
- [C] `client/region/BorderMachine` (pure Java, four states); `MoveController` (`:89-90, 103-104`):
  `following` = index present, not a free camera, **and border state `FOLLOWING`**; `Frame` gains the border
  state; `InputLock.reason` takes `following`; `MovementSync`: the gate check before `crossings` and `drift`
  (`:193-224`), `linkFrom(hiddenSpot)`, positions outside `CORE` count as on no map; `MoveRuntime` registers
  the `act.result` answers; a HUD line for refusals with today's "return to where your game is" teleport;
  one more F7 line (region id, zone, border state, home gate).
- [T] `fake_gen1` answers `region.leave` / `region.enter`; JUnit `BorderMachineTest` (every transition,
  timeouts, a link drop in each state), `MovementSyncTest` and `MoveControllerTest` additions,
  `InputLockTest` ("outside: never locked"); lupa `tools/lua/test_lua_region.py`; GameTest `GateGameTests`
  (presence decides the gate block; a cow never passes; the bounce moves a non-admitted player out of the
  core and leaves a creative player alone); e2e `tools/e2e/test_region.py` on the test install: the driver
  warps the hidden player to the gate's inside cell (read by the test from the built world's footprint file,
  never printed), then 20 leave/enter pairs; the output holds ids and counts only.

**Checks:** `tools/check --e2e` green, including the full real build with `--preset overworld` and the
crossing test: 0 teleports, 0 bounces, the hidden player's map and cell unchanged by each pair, p95 of the
round trip at most 8 client ticks.
**Play-test:** create a world "In a Minecraft world". Start in your room; walk to the gate (F7 shows the region,
your zone and the gate). Step out: Gen1Recomp's window freezes with its music faded, and you can jump. Walk around on vanilla
land. Come back in: following resumes with no jump of the camera. Try to get in anywhere else: a wall. Log
out outside and rejoin: no snap. Enter a house and a cave: as before. Look at the apron, the ramp and what
lies under a cave mouth next to Kanto. Open your flat `Kanto` world: unchanged.
**Commit "M11: Kanto in a vanilla world".**

### M12: Real play outside

**Tasks:**
- [S] The guard: `mixin/LevelRegionGuardMixin` at the head of `Level#setBlock(BlockPos, BlockState, int, int)`
  and `server/RegionGuard` (`denies`, `write(…)`, the same-block allow-list for habitat blocks); the four
  early vetoes; all `kantobridge` blocks in `#wither_immune` and `#dragon_immune`; the game rule
  `kantobridgeProtectRegions`; the builder, `HabitatService` and `BiomeWriter` write inside
  `RegionGuard.write` ([A5.7](#a57-block-changing-mechanics)).
- [S] The three player callbacks on both sides ([A5.1](#a51-building)); the damage veto by zone, the hunger
  and effect sweep ([A5.3](#a53-damage-hunger-and-status-effects)); the mob sweep and the three biome tags
  ([A5.5](#a55-vanilla-mobs)); `RegionSpawnInfluence`, the "151 only" setting, `shinyRate` and
  `defaultFleeDistance` restored, `EncounterService` and `BattleMusic` keyed on the player's zone
  ([A5.6](#a56-cobblemon-spawning)); the ride and elytra vetoes and the portal arrival rule
  ([A4.8](#a48-edge-cases-ways-in-or-out-other-than-walking)); `BuildHold` and the save refusal scoped to
  the region ([A4.7](#a47-multiplayer-some-players-inside-some-outside)); marked bag stacks
  ([A7.3](#a73-outside-economy)).
- [S] In region worlds: stop forcing the game rules, Peaceful, the default game type, the weather and the
  time; the flow creates the world in Survival; the world spawn per D4.
- [C] `RegionRuntime.concerned()` for the status line, the HUD lines, puppets and notices; a `battle.start`
  while not following is answered `aborted`; `Surfing` cleared on leaving
  ([A5.8](#a58-client-checks-from-kanto-world-to-inside-the-footprint)).
- [L] `kb_party`: limits from the running game's registry instead of the literals 151 and 165; a `log` code
  for members left out (one HUD line); `region.enter` answers `no_party` when the story has a party and none
  of it is compatible ([A7.1](#a71-party-and-pokédex-sync-beyond-gen-1)).
- [T] GameTests `RegionGuardGameTests`, `RegionBuildGameTests`, `RegionMobGameTests`, `RegionSpawnGameTests`,
  `RegionEdgeGameTests`, `RegionJoinGameTests`, additions to `MultiplayerGameTests`, `BagGameTests`,
  `SavePairGameTests`; the experiments E4, B1, B3, B4 and B8 of section 8.

**Checks:** green; `verifyMixinTargets` covers the five new targets.
**Play-test:** spend a night outside; build beside Kanto; try to grief it from outside (water, TNT, a
piston, a sapling at the edge); catch Pokémon outside and walk back in; die outside; ride up to the gate.
**Commit.**

### M13: Edges, more gates, sky

**Tasks:**
- [B]/[S] Edge kinds from the owner map's border block; the kind-aware ring (water ring at sea edges); sea
  shelf, cliff rise and belt biomes in `RegionOffset`/`RegionBiomeSource`; matched `grass_color` and
  `foliage_color` in the three Kanto biomes ([A2.8](#a28-placement-and-surroundings),
  [A3.3](#a33-transitions-per-edge-kind), [A3.4](#a34-visual-continuity)).
- [L]/[C]/[S] Unlocked gates and the entry warp (`enter_warp`), the save on leaving, sea gates (after the
  surf-start spike B7) ([A4.3](#a43-entering)); `snapshot.away`.
- [S] `server/RegionSky`: noon and clear weather for players inside, blended over the apron
  ([A5.4](#a54-time-and-weather)).
- [B] If D5 chooses the flush sea: `Scale.SURFACE_Y` 63, water tiles 14/16 high, `walking_level_only.json`,
  `WorldPlan.volumesOf` reading the old index's level, fixtures ([A2.6](#a26-sea-level-and-heights)).
- [T] GameTests for each edge kind; the frame-rate experiment over Kanto ([A3.5](#a35-sightlines)); the
  generation-cost experiments E1–E3.

**Checks:** green. **Play-test:** walk the whole outer edge; sail up to a sea edge; leave by one gate and
return by another; watch the sun at the border at midnight. **Commit.**

### M14: Regions as data

**Tasks:**
- [D]/[S]/[C]/[L] An optional `region` field on `world.index`, `texture.status`, `session.bind`,
  `session.bound`, `save.begin`, `save.loaded`, `save.mismatch`, `story.state`; `SessionRecord` format 2;
  the `kantobridge:save_pairs` map; `kb_pairing` keyed by world and region; the daycare store per region
  ([A8.3](#a83-session-and-pairing-per-world-region)).
- [S]/[C] Per-region files under `regions/<id>/` with the legacy fallback; `ClientRegions` replaces the 18
  uses of `ClientWorldIndex.current()` behind a shim; the art set in texture paths and slot-map keys (schema
  version 2); one generated pack per art set; `WorldIndex.GAMES` as a lookup
  ([B6.1](#b61-the-region-registry)–[B6.5](#b65-namespacing-now)).
- [C] "Import from an existing Kanto world" in the create flow (player data, Cobblemon's folders,
  `session.json`, the save pair, the daycare) ([A2.11](#a211-existing-worlds)).
- [T] Two synthetic regions in one GameTest world; lupa pairing tests; reading every legacy file.

**Checks:** green; M9's and M10's e2e scenarios unchanged. **Commit.**

### M15: Runtime model with the same cubes, and the prototype

**Tasks:**
- [C] New `client/model/`: `KbModelPlugin`, `TileUnbakedModel`, `TileBakedModel`, `RenderSnapshot`,
  `CubeMeshes` ([B2.1](#b21-the-flexible-block-in-fabric-1211)); `ResourcePackWriter` stops writing
  blockstates and models and marks the pack layout; `PackInfo`, `TextureSync` (a pack fits when it has the
  art for the world's keys), `GeneratedPack` (no hold-back) ([B2.8](#b28-recommendation-and-migration), step 1).
- [T] The pure tests of [B8.1](#b81-test-design-for-part-b) for snapshots, UV maths and the decision table.
- [T] On a throw-away branch, with your approval: variants B (static shapes) and C (flexible shapes) of the
  prototype on the Pallet and forest scenes; `-Dkantobridge.meshstats`; you run the client measurements
  ([B2.7](#b27-prototype-design-measure)).

**Checks:** green; the world looks identical before and after (play-test); joining a host with another slot
map needs no resource reload. **Decision D11 from the numbers. Commit.**

### M16: Scene, shape planner, per-face art

**Tasks:**
- [L]/[D] Export additions `borderDrawn` and `animated`; schemas, `fake_gen1`, the synthetic bundle.
- [S]/[B] New `scene/` (`Scene`, `Gen1FrontEnd`); `builder/shape/` (`Structures`, `ShapeKey`,
  `ShapePlanner`, `SelfCheck`); `world/ShapeIndex`; the shape map codec; `world.shapes`; hashes in the
  region record ([4.7](#47-the-shape-generator-pipeline)).
- [C] `ShapeMap`, geometry for `flat` and `cube` keys, per-face art in `tile_full` carriers (no block
  changes).
- [T] The JUnit suites of [B8.1](#b81-test-design-for-part-b) for structures, keys, the index, the planner
  on the synthetic bundle (extended once with a sign, a round tree, a windowed house, a fence and a cliff),
  geometry and the self-check; the report fields of [4.11](#411-build-report-additions).

**Checks:** green; the real build reports shape counts, an identical `shape_index_hash` on two runs and on a
filtered build, and tier A self-check scores of exactly 1.0 with 0 holes.
**Play-test:** Pallet Town from the south reads like the 2D game; from the north there is no second front
door. **Commit.**

### M17: Real shapes

**Tasks:** [S] `shape_full_*`, `decor`, `decor_leaves`; [B] carriers from the shape plan, the collision
invariant; [C] face masks, roofs, ledge lips, recessed water (tier A), then tufts, flowers, objects and
hulls (tier B), the generator's quad cap; [T] the collision GameTest on the shaped build, budgets as report
gates, the rebuild of your world.
**Checks:** green; every budget of [B2.3](#b23-performance-measure) met on the real build's counts.
**Play-test:** the checklist of [B8.1](#b81-test-design-for-part-b). **Commit.**

### M18: Characters

**Tasks:** [C] `client/puppet/style/` (`CharacterStyle`, `SpriteFrames`, `ThickCardMesh`, `HullCarver`,
`QuadMerger`, `RigSplitter`, `RigTextures`), the setting in `ClientConfig`, the surf sprite through the same
style; [T] the pure tests and the "64 puppets" experiment ([B5.2](#b52-thick-cards)–[B5.5](#b55-settings-consistency-packs-and-dramaless-shapes-argument-for-flat-cards)).
**Checks:** green. **Play-test:** each style on a walking NPC, an item ball, a boulder and Snorlax. **Commit.**

### M19: Texture packs

**Tasks:** [C] `ArtKeys`, the key index, `KbPackReload`, `KbPackManifest`, `PackOverrides`, `VoxFile`,
`VoxMesher`, `PackTemplateWriter`; the generated pack below player packs; [D] `docs/PACKS.md` and
`protocol/packs/kb_pack.schema.json` ([B4.1](#b41-stable-readable-keys)–[B4.6](#b46-tooling-for-pack-makers)).
**Checks:** green. **Play-test:** F3+T with a test pack (a texture, a rule, a `.vox` house, a `.vox`
sprite); remove it and everything falls back. **Commit.**

### M20: Story art in the mesh, dressing, structure templates

**Tasks:** [S]/[B]/[C] `story_tile` as a model block, closed shapes per story cell, dirty sections on story
changes, the block-entity renderer removed ([B2.6](#b26-per-player-story-art-with-shapes)); the dressing pass
(D15); tier C templates gated by the self-check; clean-up (migration step 5).
**Checks:** green. **Play-test:** a cut tree before and after Cut for each of two players; interiors in
first person. **Commit.**

---

## 7. Risk register

Likelihood and impact are the integrator's judgement (L low, M medium, H high).

| # | Risk | Likelihood | Impact | How it is detected | Fallback |
|---|---|---|---|---|---|
| R1 | The gated generator lets something into the core: aquifer water at a sea edge, decoration spill, a structure piece, a cave mouth | M | H (flooded or damaged Kanto) | Report gates `foreign_blocks`, `fluid_blocks_in_core`, `entities_in_core` = 0 on every e2e build; the GameTests `apronChunk` and the 200-tick edge test; play-test of the edge | The foundation is unbreakable and not replaceable by carvers or features, so the worst case is debris above ground, which the counted pass removes; widen the apron or the structure margin (constants) |
| R2 | The access widener, or registering the three codecs from `onInitialize`, does not work with the pinned Loom and Loader | L | M (M11 blocked) | The first GameTest of M11a (`generatorRegistered`) | The wrapper generator plus one `ChunkMap` mixin ([A2.1](#a21-architecture), "fallback") |
| R3 | A Minecraft update changes the generator's methods or the noise records | M (on any version bump) | M | Compile errors | The data design (footprint, record, bands) does not change; only the subclass follows. No bump is planned (pins) |
| R4 | A way into Kanto that skips a story gate: land rides (step height 1.2 over one-block story cells), Jump Boost, pearls, elytra, other mods' teleports | M | H (sequence breaks; the two games out of step) | GameTests `RegionEdgeGameTests`; the e2e crossing test's bounce and teleport counters; experiment B1 | The server bounce is the catch-all; if it is seen too often in play, a full-height per-player collider through a mixin on `Entity#collide` ([A4.8](#a48-edge-cases-ways-in-or-out-other-than-walking), option a) |
| R5 | The hidden game and Minecraft diverge at a gate: a refused leave, a crash while outside, a hidden save older than the Minecraft save | M | M | `act.result` reasons and counters on F7; the save-pair mismatch (shown on entering); the `away` marker in the hidden save | Today's teleport back to the hidden cell is always offered; the save on leaving (M13) makes the pair fresh at every exit |
| R6 | Stutter or rubber-banding at the border | M | M (the "seamless" goal) | The crossing test: p95 round trip, forced walk-backs; play-test | The rules change in the apron and the following state at the gate, so neither waits for the other; widen the apron; the hidden-step speed-up (specs/HIDDEN_STEP_SPEEDUP.md) reduces refusals in general |
| R7 | Scoping the 37 behaviours leaves one world-wide, or breaks the legacy world | M | M | A GameTest per rule; the legacy switch tested in every `Region*GameTests`; your flat world in the play-test | The legacy path is today's code, untouched; region worlds can fall back to a world-wide rule one at a time |
| R8 | The guard on `Level#setBlock` costs too much or fights another mod | L | M | Experiment B4 (below 1% of tick time, below 50 ns a call) | The chunk-key probe makes calls outside regions one hash lookup; if still too costly, per-mechanic vetoes for the hot cases |
| R9 | The dimension-type override thins vanilla mob spawning outside, and applies to every world | M (inferred from the code) | L–M | Experiment E4 | The one-line start-height fix; or the override as a built-in pack for Kanto worlds only (D16) |
| R10 | The flexible model is too slow, or wrong under Sodium, Iris or Distant Horizons | M | H for Part B | The four-variant prototype with thresholds (M15) before any world change | The static approach if the capacity allows; fewer quads per block (the generator's cap); step 1 alone is still a gain (packs independent of the slot map) |
| R11 | Shapes look wrong on real art (trees, houses), which no agent may look at | M–H | M | Self-check scores per map and per template; `structures_by_kind.fallback`; your play-test | Automatic fallback to plain columns per structure; tier A alone; rules and models from packs |
| R12 | Full slots are 96% used (3,926 of 4,096) | H if art kinds grow | M | `slot_map.full` fails the build | The block approach stops spending slots (shapes and extra textures are keys, not slots); 16 more families when a second region arrives |
| R13 | A footprint that must change after the world exists (an export from another Gen1Recomp pin) | L | M (a new world) | `footprint_changed` before any block is touched | A new world with the player side imported (M14) |
| R14 | A third-party licence or pin: Sodium is not open source; Iris for Sodium 0.8 is a beta; the Distant Horizons wiki is stale about Indium | — (your decision) | L | Re-check at pin time | Pin nothing (D10) |
| R15 | Layout data of a region reaches a player who never links a hidden game | L | L (allowed by U8; no art) | — | Send the index on demand (D18) |
| R16 | Cobblemon specifics: habitat blocks inside the foundation; village Pokémon Centers with healers and PCs outside; marked stacks surviving item use | M | L–M | Experiments B8, B9; the habitat GameTest on foundation (`builderOnFoundation`) | Habitat blocks are placed as today; recipes and structures are settings (D9) |

---

## 8. Experiments designed but not run

Nothing below was run. Each prints counts and times only; client-side ones are yours to run. The full method
of each is in the answer named.

| # | Question | Method (short) | Metrics | Pass threshold | Designed in |
|---|---|---|---|---|---|
| E1 | What does the gate cost per chunk? | Headless server, fixed seed, four worlds (vanilla; our preset unbound; bound to the synthetic footprint; bound to the real one); force 32×32-chunk areas to full with tickets; read JFR `minecraft.ChunkGeneration` | ms per chunk per status; chunks/s | Unbound, far away: within 3% of vanilla; bound, far: within 5%; ramp `noise` ≤ 1.25×; inside ≤ 0.15× | [A2.10](#a210-performance-measure) |
| E2 | Build time with real chunks around | `build_world.py --preset overworld`, Pallet then all | `gen_ms`, `place_ms`, `chunks_generated`, `max_tick_ms` | Full build ≤ 2.5 × today's 57 s; `max_tick_ms` ≤ 250; `blocks_cleared == 0` | [A2.10](#a210-performance-measure) |
| E3 | Memory of the footprint and of loaded chunks | Heap after a forced GC before and after binding; view distance 16 against vanilla | bytes of the tables; heap per chunk | Footprint ≤ 4 MB per region; heap within 10% of vanilla | [A2.10](#a210-performance-measure) |
| E4 | Does the 576-block-tall dimension thin mob spawning? | Vanilla preset with and without the override, same seed, 5 minutes at night; count `NaturalSpawner` successes | spawns per category | Below 70% of vanilla → adopt the start-height fix | [A2.10](#a210-performance-measure) |
| E5 | Frame rate over Kanto | Three camera stops (Pallet at ground level; above the centre at Y 160; the viewpoint hill) in the flat world and the vanilla-world Kanto, render distance 16 and 32, 30 s each | mean fps, 1% low, rendered sections, heap | 16 chunks: ≥ 60 fps mean and ≥ 30 fps 1% low; 32 chunks: ≥ 30 fps mean; vanilla-world within 20% of flat at Pallet | [A3.5](#a35-sightlines) |
| E6 | The gate round trip | 100 crossings each way against `fake_gen1`, then on the test install (ids and counts only) | ticks from request to `act.result`; forced walk-backs; bounces; teleports | p95 ≤ 4 client ticks (stand-in), ≤ 8 (real game); 0 bounces and 0 teleports at the home gate | track 2's B6 in [9.4](#94-runtime-scoping-and-the-hidden-game-blocked-and-open) |
| E7 | The riding hole in today's Kanto | Ride a land mount at a closed Cut tree and at a counter; then with `RIDE_EVENT_PRE` cancelled | crossed or not | After the fix: mounting refused inside, allowed outside | B1, same place |
| E8 | Server-sent time and weather show no flicker | Night and rain; walk OUT → APRON → CORE → OUT; log the client's day time and rain level each second | day time, rain level | Inside: within ±40 of 6000 for 60 s, rain 0; outside again within 1 s | B2 |
| E9 | The guard's cost | Counters in the guard; 60 s beside a 0-tick piston clock and a river outside | calls, sampled nanoseconds | Below 1% of mean tick time and below 50 ns a call | B4 |
| E10 | The footprint's size on the wire | Report `footprint{…, bytes, bytes_deflated}` from a full build | bytes | At most three 64 Ki-character parts | B5 |
| E11 | A sea gate reached by an entry warp starts the hidden player surfing | lupa with the fake engine, then one e2e case | step accepted; `player.state.surf` | Both true (needed for M13 only) | B7 |
| E12 | Marked bag stacks survive item use; village centre contents; spawn density at 8/32 and 16/64 | GameTest; decode the templates' block palettes (ids only); count `ENTITY_SPAWN` per minute | marker kept; block ids; spawns per minute | Marker kept; decides D9; a route shows 1–3 wild Pokémon within 32 blocks | B8, B9, B10 |
| E13 | Is the runtime model affordable, and is flexible competitive with static? | Four variants (today; runtime model with the same cubes; static shapes; flexible shapes) × Pallet and forest × Indigo (and Sodium if allowed) × render distance 16 and 32; three runs | full re-mesh time, our share of mesh time, section rebuild P95, fps, heap, reload time, quads | Runtime model vs today: re-mesh ≤ 1.25×, fps ≥ 97%; flexible vs static: re-mesh ≤ 1.5×, fps ≥ 95%, heap ≤ +32 MB; shapes vs today: fps ≥ 90% at 16 and ≥ 85% at 32, section P95 ≤ 2× and ≤ 15 ms | [B2.7](#b27-prototype-design-measure), [B2.3](#b23-performance-measure) |
| E14 | Quads per block and per section on the real plan | Pure Java counters in the builder | `shape_quads_*`, `densest_section` | Per shaped block max ≤ 96, mean ≤ 24; per section P95 ≤ 12,000, max ≤ 30,000 | [B2.3](#b23-performance-measure) |
| E15 | Third-party renderers with our blocks | One client session each: Distant Horizons on a built world (−256…319 dimension, invisible story and gate blocks, `decor`); one Iris shader pack with a `block.properties` snippet; animated tiles under Sodium | by eye, plus the atlas log line | No phantom blocks in LODs; ids reach the shader; water and flowers animate | [B2.2](#b22-compatibility) |
| E16 | The pack screen keeps a player pack above the generated one | Put a pack with one overriding tile above "Kanto Bridge (generated)", press Done, restart | order kept; tile changed | Both | [B1.2](#b12-the-generated-pack) |
| E17 | Self-check score distributions before fixing thresholds | The headless build writes scores per map and per template | the five scores | Record first; then tier A exactly 1.0 and 0 holes; templates ≥ 0.90 or fall back | [B3.6](#b36-a-self-check-re-project-the-3d-result-into-the-games-view-and-score-it) |
| E18 | "64 puppets": the cost of each character style | A dev world with 64 synthetic puppets within 48 blocks | frame time mean and 1% low; allocations per frame | Thick: < 0.3 ms over flat at 64, no allocation; carved: < 0.5 ms at 20 and < 1.5 ms at 64 | [B5.2](#b52-thick-cards), [B5.3](#b53-carved-voxel-figures) |
| E19 | MagicaVoxel's horizontal axes | Export the asymmetric calibration model once and load it | orientation by eye | Front faces south, as documented | [B4.3](#b43-what-a-pack-can-contain) |
| E20 | Optional: Dramaless Shape as a test oracle in the test install only | Its public companion API per cell against our classes and heights | agreement counts | Reported only | [NEXT_STAGES.md](../NEXT_STAGES.md) §2 |

---

## 9. Blocked and open questions

### 9.1 Blocked by the research rules

Nothing was blocked by a contradiction between sources; the one contradiction met (Distant Horizons' wiki
says to install Indium on 1.21.1; Indium's page and Sodium's own metadata say the opposite) was settled by
Sodium's `fabric.mod.json`. The items below could not be answered without opening ROM-derived data, running
the game, or using a tool outside the read-only set. Each names what unblocks it.

| # | Question | Why blocked | What unblocks it |
|---|---|---|---|
| 1 | Kanto's real footprint numbers: interior extents and how far interiors overhang the surface maps; edge cells per side and kind; where the surface box sits relative to Pallet Town; whether any outer edge has walkable land (so whether the first gate needs a cut) | The export and the world index are off-limits; only an inference from chunk counts is possible (at least 11 interior-only chunks) | The report fields of [4.11](#411-build-report-additions) (first four rows), then one build in M11a |
| 2 | Frame rate, mesh time, memory, reload time; the pack screen; Sodium, Iris and Distant Horizons with our blocks | Agents never start the client | Experiments E5, E13, E15, E16, E18, run by you |
| 3 | How good the shape, carving and rig heuristics are on real art | Agents may not look at ROM-derived art | Scores only: E14, E17, the report's fallback counts, your play-test |
| 4 | Whether Cobblemon's village Pokémon Centers contain a healing machine and a PC | The structure templates are binary NBT; decoding needs a tool outside the allowed set | E12 (list block ids of the templates) |
| 5 | MagicaVoxel's horizontal axis convention | The official text fixes only "z is up" | E19 (one calibration export) |
| 6 | Starting to surf after an entry warp at a sea gate; `Music.restoreMap` after the away hold's fade | Needs the engine running | E11 and the lupa tests of M11c and M13 |

### 9.2 Open: not confirmed, to settle while building

| Area | Not confirmed | Settled by |
|---|---|---|
| Generator | Registering the generator, biome-source and density-function codecs from `onInitialize` is early enough; the access-widener header for Loom 1.17.21 (`accessWidener v2 named` is what the pinned Fabric API itself uses); the world-gen mailbox serialises `features` steps, so `repair` has no concurrent writer; detached-`ProtoChunk` tests need only a `StructureManager`; which features honour `#features_cannot_replace` beyond lakes and geodes | The first GameTests of M11a; `foreign_blocks == 0` |
| Generator | The radius of chunks generated around a full chunk was derived by hand from the pyramid; Kanto's default grass tint was inferred from the formula | E2; a look at the border |
| Border | The riding hole in today's Kanto; the gate round trip; no client path re-enables the day cycle between our packets; the guard's cost | E7, E6, E8, E9 |
| Cobblemon | How the Pokédex manager aggregates knowledge over forms; that ball throws only shrink the used stack; habitat blocks working inside foundation | Reading `refs/cobblemon` when coding; E12; `builderOnFoundation` |
| Rendering | A `BlockStateResolver` over 8,192 states adds no noticeable reload time; Iris gives `block.properties` ids to quads emitted through the Fabric API under Sodium; Distant Horizons on a −256…319 world; animated tiles under Sodium with sub-rectangle UVs; the default UV orientation per block face; "the model APIs change after 1.21.4" (from memory) | M15's first step; E15; re-research at the next Minecraft pin change |
| Sizes | Shape map and shape index sizes, quad counts, shape-table memory, the planner's added time: all estimates | The report fields of 4.11 |
| Characters | The sign of the yaw rotation for world-oriented figures; quad counts per real sprite | M18's play-test; E18 |
| Other families | Gen 3 map-type flags, weather and animated tiles; Gen 2's `computeNeighbors`; pixel access from a mod on a Gen 2 or Gen 3 boot | Whoever writes those bridges |

The decisions that are yours are in [section 5](#5-decisions-for-the-user). The lists below are each
researcher's own "Blocked" and "Open" sections, kept in full.

### 9.3 World generation and the builder: blocked and open

**Blocked**

| # | Question | Why | What unblocks it |
|---|---|---|---|
| B1 | A1.3 real interior extents; how many interiors leave the surface box and by how much | only an inference from chunk counts is possible (≥ 11 interior-only chunks); the export and the world index are off-limits | report fields `interior.bbox`, `outside_surface_box`, `max_overhang_blocks` (A1.3), then one build |
| B2 | A3.2 real edge counts per side and kind; which surface maps own outer edges | needs the real export | report fields `edges.*` (A3.2), then one build |
| B3 | A1.3 where the surface box sits relative to Pallet Town (and so its exact chunk count) | not in any report | report field `surface.box` |

Nothing was blocked by a contradiction between sources.

**Open**

**For the user (decisions).**
1. Generator architecture: option (e) with one access-widener line (A2.1), or the wrapper + mixin
   fallback.
2. Sea height: keep Kanto as it is (a 0.61 step at sea edges) or shift Kanto down one block with
   14/16-high water tiles for a flush sea (A2.6 options A / C).
3. Footprint shape parameters: `close`, and whether enclosed pockets are filled or left wild (A3.1).
4. Belt biomes per edge kind and the belt width (A2.8); placement at the origin; region slot size.
5. Presets: keep both, which is the flow's default, and whether to list either on vanilla's Create
   World screen (A2.7).
6. Time: vanilla day/night with a client-side "Kanto daylight" override (A3.6).
7. The dimension-type override: keep global (A1.2); and whether the mob-spawn dilution outside Kanto
   deserves the one-line fix (A2.4, A2.10 E4).
8. Legacy world: keep, and add an import step later (A2.11, A6.6).

**⚠ Not confirmed (to settle in the first milestone).**
- Registering the generator, biome-source and density-function codecs from `onInitialize` is early
  enough (A2.1).
- The access-widener header accepted by Loom 1.17.21 / Loader 0.19.5 (`accessWidener v2 named` is what
  the pinned Fabric API uses; the wiki shows a newer header) (A2.1).
- The world-gen mailbox serialises `features` steps, so the post-decoration repair has no concurrent
  writer (A2.1; inferred from `ChunkMap#runGenerationTask`).
- Detached-`ProtoChunk` tests need only a `StructureManager` (A6.4).
- `RADIUS_AROUND_FULL_CHUNK` = 11 and the per-radius statuses were derived by hand from the pyramid
  (A2.10 E2).
- Which features honour `#features_cannot_replace` beyond lakes and geodes (A2.1); the repair pass is
  the guarantee either way.
- The Cobblemon folder names for an import, and habitat blocks working inside foundation (track 2).

**Experiments designed, not run:** A2.10 E1–E4, A3.5 fps, A6.4 test list.

**Tuning constants (first play-test):** apron 8, join 8, ramp 96, structure margin 32, sea shelf 52
for 24 blocks, belt 64–128, ring 6 cells, close 16 cells, shell 2 cells.

### 9.4 Runtime scoping and the hidden game: blocked and open

**Blocked**

Nothing in this track's questions is unanswerable, and no two sources contradicted each other in a way the code could
not settle. The items below are blocked **only by the research rules** (no builds, no game, no tools outside the
read-only set); each has the experiment that settles it. None of them changes a recommendation; each could change a
detail.

| # | What is not settled | Why it is blocked | The experiment (designed, not run) | Pass threshold |
|---|---|---|---|---|
| B1 | Today's riding hole (A4.8): a player on a land-rideable Pokémon steps over closed story cells and counters | Needs a play-test. The code says step height 1.2 for land rides (`refs/cobblemon/common/src/main/kotlin/com/cobblemon/mod/common/api/riding/behaviour/types/land/HorseBehaviour.kt:534-540`) against one-block story cells (`WorldPlan.java:452-457`) | In today's flat Kanto: ride a land mount at a closed Cut-tree cell and at a counter; note whether it crosses. Then with `RIDE_EVENT_PRE` cancelled: try to mount inside | Before the fix: crossing observed or not (records the fact). After: mounting refused inside, allowed outside |
| B2 | Server-sent time and weather (A5.4, option 3) show no flicker | Needs a client | Night, rain. Walk OUT → APRON → CORE → OUT. Log, per second, the client's `getDayTime() % 24000` and `getRainLevel(1)` (F7 line) | Inside: day time within ±40 of 6000 for 60 s, rain level 0; outside again: the real values within 1 s; no visible sun jump |
| B3 | Whether an inline `discard()` inside `ServerEntityEvents.ENTITY_LOAD` would be safe during a chunk load (A5.5). The sketch defers the removal to the end of the tick, so this only decides whether the deferral can be dropped | Needs a GameTest run | GameTest: save a zombie into a chunk inside the footprint, unload and reload the chunk with the listener on; 100 repetitions, once deferred and once inline | Deferred: no exception, no entity left after one tick. Inline: the same, or keep the deferral |
| B4 | Cost of the `Level#setBlock` head inject (A5.7) | Needs a build | Counters `calls`, sampled `nanos` (1 call in 64) in the guard; 60 s beside a 0-tick piston clock and a flowing river outside the footprint, with and without the mixin | Guard time below 1% of mean tick time and below 50 ns per call |
| B5 | Size of `world.footprint` on the wire (A8.2) | Needs a real build; the mask is track 1's | Report `footprint{cells_w, cells_h, core_cells, apron_cells, chunks, bytes, bytes_deflated}` from a full build | At most three 64 Ki-character parts; otherwise send classes only and compute distances on the client |
| B6 | Gate round trip and bounce count (A4.2) | Needs a build and the e2e instance | 100 crossings each way, first against `tools/fake_gen1`, then on `local/gen1recomp-e2e/` (ids and counts only in the log). Per crossing: ticks from `region.leave`/`region.enter` to `act.result`; forced walk-backs; server bounces; `MovementSync` teleport counter | p95 at most 4 client ticks with the stand-in and 8 with the real game; 0 bounces and 0 teleports at the home gate |
| B7 | A sea gate reached by an entry warp must start the hidden player surfing (A4.3) | Needs the engine running; there is no public call for it | lupa test with the fake engine, then one e2e case: warp to a water gate cell, set the surfing flag as the boot path does (`OC:592-606`), take one step | The step is accepted and `player.state` reports the surf mode. Not needed for the first (land-gate) milestone |
| B8 | Marked bag stacks survive Cobblemon's item use (A7.3) | Needs a GameTest run | GameTest: a stack of 5 marked Poké Balls; throw one; inspect the remaining stack's `CUSTOM_DATA` | The remaining 4 still carry the marker |
| B9 | Cobblemon's village Pokémon Center contents (A7.3): do generated centres hold a healing machine and a PC? | The structure templates are binary NBT; decoding them needs a tool outside the allowed read-only set | Decode the `.nbt` templates of Cobblemon's village centres and list their block palettes (block ids only) | Tells whether outside healing and PC access already exist through generated villages whatever the recipes do (it does not change the recommendation in A7.3) |
| B10 | Spawn feel with one global spawning-zone distance (A5.6) | Needs play | Count `ENTITY_SPAWN` per player per minute on a route inside and in plains outside, at 8/32 and at 16/64 | The user judges; a route should show 1–3 wild Pokémon within 32 blocks most of the time |

**Open**

**Decisions for the user** (each has a recommendation in the named section):

1. The game mode outside Kanto (Survival assumed; nothing in the design depends on it) — A5.1.
2. How harsh the outside is: `keepInventory` and immediate respawn world-wide (recommended at first) or vanilla — A5.9.
3. Time and weather: vanilla everywhere at first, then "always noon inside" by per-player packets as a world setting — A5.4.
4. Outside spawns limited to the 151: a world setting, on at first — A5.6, A7.1.
5. One economy or two; whether Kanto-bought items work outside — A7.3.
6. A save of the hidden game on leaving through a gate (recommended) and whether it is announced — A4.2, A7.2.
7. Whether `world.index` goes to every client or only to clients with a bridge link — A4.7.
8. Whether "sequence breaks allowed" (entry at any gate) should exist as a world option — A4.3.
9. The chat game rules (`announceAdvancements`, death messages) outside — A1.5 row 6.
10. Which of Cobblemon's Kanto settings stay global (no passive healing, experience ×1, no loot): recommended global — A5.6.
11. Operators: exempt from the bounce by game mode (recommended, as today) or by permission level — A4.8.
12. A separate story per singleplayer world — A8.3 (not a consequence of regions).

**New dependencies or pin changes proposed by this track:** none. Everything above uses Minecraft 1.21.1, Fabric API
0.116.17 modules that are already present, and Cobblemon 1.8.1's public API.

**New mixins proposed** (all server or common; the client needs none): `Level#setBlock(BlockPos, BlockState, int, int)`,
`Level#destroyBlock(BlockPos, boolean, Entity, int)`, `Explosion#explode()`, `FlowingFluid#canSpreadTo(…)`,
`PistonStructureResolver#resolve()` (A5.7). Optional later: `ThrownEnderpearl#onHit`, `PortalForcer` (A4.8).

**Needed from track 1** (the interface this track assumes):

- a class per mask cell — `OUT`, `APRON`, `CORE` — and a signed distance, behind `classAt(x, z)` and `distance(x, z)` (A8.2);
- columns of sideways-moved or spilled interiors marked `CORE` (5 and 1 in the real build, `build_report.json` `notes`);
- an apron at least two cells deep everywhere a player can walk up to Kanto, and at each gate;
- gate records `{id, kind, map, x0, y0, x1, y1, dir}` validated against the export's cell flags, and gate blocks placed on the threshold cells, three high (A4.2, A4.4);
- no structures or water running into the footprint (A5.5, A5.7), and a world spawn outside it (A4.6);
- if Kanto moves vertically: `walking_level_only.json`'s literals 63 and 22 follow (A5.6).

**Report fields a future build should add** (counts only): `outer_edge_cells{north,south,west,east}{walkable,water,solid}`,
`outer_edge_maps`, `gates{land,sea}`, `gate_cells`, `footprint{cells_w, cells_h, core_cells, apron_cells, chunks, bytes, bytes_deflated}`,
`habitat_blocks_near_edge` (habitat blocks within 16 blocks of the footprint's edge).

**Every `⚠ Not confirmed` in the runtime answers, in one place:**

| Where | What |
|---|---|
| Leads, row 4 | That the vanilla sea's top water block is at Y 62 (track 1 owns the generator) |
| A4.3 | Starting to surf by writing the engine's flag after an entry warp (B7) |
| A4.8 | The riding hole, in play (B1) |
| A5.4 | A client override of `getTimeOfDay`/`getRainLevel` (option 2) was not compiled; no client path re-enables the day cycle between our packets (B2) |
| A5.5 | An inline `discard()` inside `ENTITY_LOAD` (B3; the sketch defers it) |
| A5.7 | The guard's cost (B4) |
| A7.1 | How Cobblemon's Pokédex manager aggregates knowledge over forms (not read) |
| A7.3 | Village Pokémon Center contents (B9); component survival on item use (B8) |
| A7.4 | That `Music.restoreMap` replays the map's theme after the away hold's fade (it should: `Music.stop()` leaves `state.mapSong`, `refs/gen1recomp/src/core/Music.lua:334-348`) |
| A8.2 | The mask's size on the wire (B5) |

**Claims that rest on a sub-agent's reading (†) and were not re-read by me** are marked where they appear. The ones a
coding agent will lean on most: the per-mechanic line numbers in A5.7's table, the Cobblemon configuration read sites in
A5.6's table, and the vanilla spawner classes in A5.5's table. Every mixin target, Fabric event signature, attribute
call, packet constructor and Cobblemon hook that a recommendation uses was re-read by me.

**A suggested order of work** (each step leaves old worlds untouched):

1. Region record, `Regions.classAt`, the legacy switch (A8.1, A8.2).
2. Server scoping by zone: `RegionRules` (speed, jump), the damage veto, the build callbacks and the `setBlock` guard,
   the mob sweep and biome tags, the spawn influence (A4.5, A5.1–A5.7).
3. The client's single flag: `following` with the border state, `concerned()` (A5.8).
4. One gate: three messages, `GateCellBlock`, presence, the away hold, the bounce (A4.2, minimal version).
5. Later: unlocked gates and the entry warp, the save on leaving, sea gates, the sky packets, marked bag stacks,
   per-region pairing (A4.3, A7, A8.3).

### 9.5 The block approach: blocked and open

**Blocked**

Nothing was blocked by a hard rule, and no contradiction was left that code could not settle. Two things could not be
*answered* without running code and are therefore designed as experiments, not answered with numbers:

- **B2.3 / B2.7** frame rate, mesh time, memory and reload time (client runs are the user's).
- **Real counts of shaped blocks and distinct shapes** — not in `local/reports/*.json`; the report fields to add are
  named in B1.1 and B2.3.

One contradiction between sources was settled by code: Distant Horizons' wiki says "On MC 1.21.1 and older install the
Indium mod" for Fabric + Sodium; Indium's page says it is not compatible with Sodium 0.6.0+, and Sodium's own
`fabric.mod.json` for 1.21.1 `provides: ["indium"]` and declares itself the FRAPI renderer. Sodium's metadata wins.

**Open and not confirmed**

| # | Item | How to close it |
|---|---|---|
| 1 | Pack-screen behaviour of the generated pack (movable, order kept across restarts) is from reading 1.21.1 sources, not from a run | PLAYTEST item in B1.2 |
| 2 | That a `BlockStateResolver` over 8,192+ states adds no noticeable reload time | prototype step A′ |
| 3 | Iris assigning `block.properties` ids to FRAPI-emitted quads under Sodium | read Iris's Sodium integration or test with one shader pack |
| 4 | Distant Horizons with a −256…319 dimension; DH's treatment of our invisible story/ledge blocks and of `decor`; DH 3.3.3 release vs the `main` sources read here | one client session with DH on a built world (user decision on the pin first) |
| 5 | Per-block-entity heap/NBT sizes in the B2.1 cost table are estimates | not needed unless block entities are reconsidered |
| 6 | Shape map size (≈1.5 MB estimate) and world index size | report fields `shape_map_bytes`, `world_index_bytes` |
| 7 | Sodium 0.8.x for 1.21.1 is a backport that was alpha in May 2026 and release in August; Iris for it is still a beta | re-check at pin time |
| 8 | Whether Sodium's Polyform Shield licence permits bundling in the installer | user / licence reading |
| 9 | T-junction sparkles and UV bleeding with in-block greedy quads | prototype visual check; UV inset is in the sketch |
| 10 | The model-loading and renderer APIs differ after 1.21.4 (from memory, not checked) | re-research at the next Minecraft pin change |
| 11 | Animated tiles (water, flowers) under Sodium when their quads come from our model with sub-rectangle UVs; Sodium's "material downgrading" rules | prototype check on Sodium |
| 12 | Whether `decor` needs slot-bearing states (Distant Horizons' `tintWithAvoidedBlocks`) | the DH session of item 4 |
| 13 | Decisions for the user: the block approach (after the prototype); pins for Sodium / Iris / Distant Horizons; whether to hide the block outline in Kanto; where generated packs sit relative to player packs; registering 16 more families when a second region arrives | — |

### 9.6 Shapes, packs and characters: blocked and open

**Blocked**

Nothing was blocked by a hard rule. Three things could not be settled from sources and are recorded as open
instead:

1. **Quality of the art-driven heuristics on the real game** (round cells, building bands, object
   thresholds, carving, rig splitting). Agents may not look at ROM-derived art, so only scores and counts can
   judge them; the self-check (B3.6) and the report fields (B8.2) exist for that.
2. **MagicaVoxel's horizontal axis convention.** The official text fixes only z as the gravity direction.
   Our mapping is defined in B4.3 and needs one calibration export by the user.
3. **Anything about Gen 2 / Gen 3 pixel access from a mod** (B7.1): survey only, by the brief.

**Open**

**Decisions for the user**

- D1. Dramaless Shape's hand-made tables: never (O1/O2, recommended) or with a licence decision (O3) (B3.1).
- D2. Whether user/community rule tables through packs are wanted (B3.1 O2, B4.3).
- D3. Which dressing to keep (B3.2).
- D4. The default character style (recommended: thick cards) and whether carved/rigged ship as options (B5.5).
- D5. Players as Minecraft skins (recommended) or generated trainers (B5.6).
- D6. How far packs go: textures only; + rules; + `.vox` models; + template export of reference art (B4.3, B4.6).
- D7. Pin or export the hidden game's `voidFill` option (B1.5 M6).

**For the integrator / other tracks**

- I1. Position → shape id: S1 (block state), S2 (stored map) or S3 (re-planned on the client) depends on
  3a's block approach (B3.5). The generator offers `ShapePlan.shapeAt` for all three.
- I2. `shapeIndexHash` belongs in the region record (track 2, A8) or as a new WorldIndex root field; the
  WorldIndex reader rejects unknown root fields today (`world/WorldIndex.java:31, 230`).
- I3. Gen 3 multi-level cells need a per-cell feet level in the region's index (B3.3).
- I4. Per-quad vertex colour (ceiling darkening, `.vox` colours) and the per-block quad budget are 3a's to
  confirm (B3.2, B4.3, B8.2).
- I5. Whether the generated pack keeps default position TOP (B4.2) is 3a's B1.2.
- I6. Puppets on solid cells need `topHeight(cell)` from the shape plan (B3.1 row 21, B3.7).

**Measurements designed, not run**

- M-a. Shape counts, quads and times on the real export: report fields of B8.2.
- M-b. Self-check score distributions before fixing thresholds (B3.6).
- M-c. "64 puppets" frame-time experiment for thick and carved styles (B5.2, B5.3).

**⚠ Not confirmed (collected)**

- The share of boundary runs per real sprite (thick-card quad counts) and carved quad counts: estimates.
- Planning time added by the shape pass (+1–3 s), shape-map size, shape-index size, shape-table memory:
  estimates.
- Gen 3 map-type flags, weather, animated tiles (`tileset_anim.lua` not read); Gen 2 `computeNeighbors`.
- MagicaVoxel x/y handedness.
- That walkable and water cell flags never overlap (used for the "37,199 solid cells" inference).
- The default UV orientation of each block face in the emitter (the pipeline appendix in section 4.7, "Face conventions"): 3a's to confirm.
- The sign of the yaw rotation for world-oriented figures (B5.2 sketch): derived, to be confirmed in play.
- How good the round-cell and band heuristics are on real art (B3.1): only scores can tell.
- Statements taken from track 3a's file in the interface table of section 4.7 (inset-quad lighting, UV shrink): not re-verified here.

---

## 10. Citations appendix

### 10.1 Conventions

| Form | Means |
|---|---|
| `path:line` | A file of this repository, relative to its root (or to the folder a table's caption names). Line numbers are those of the working tree on 2026-10-03/04 (commit `761bbc3` plus the uncommitted documentation changes) |
| `mc → Class#member (File.java:line)` | Minecraft 1.21.1, Mojang names with Parchment parameter names. Line numbers are those of the decompiled sources jar that Loom produced (10.2). Where a signature mattered it was also checked with `javap` on the mapped jar |
| `mcdata → path:line` | Vanilla data inside the mapped common jar (`data/minecraft/…`) |
| `fapi → …` or `fapi:<module> → …` | Fabric API 0.116.17+1.21.1, from that module's `-sources.jar` in the Loom cache. The sources are remapped to Mojang names; mixin target strings inside them are still Yarn |
| `refs/<project>/path:line` | A reference clone. `OC:n` abbreviates `refs/gen1recomp/src/world/OverworldController.lua:n` |
| `BR:line`, `ER:line`, `report → field` | `local/reports/build_report.json` and `export_report.json`: counts, hashes and timings only |
| A URL | A page fetched on 2026-10-03 or 2026-10-04 |
| **F**, **I**, **R**; Fact, Inference, Recommendation | Kept apart in every answer |
| `⚠ Not confirmed` | Not checked against a source, or not tried in play |
| † | In the runtime answers: read by a helper of that researcher and not re-read by the researcher |

Dramaless Shape and Kanto in First Person are cited for ideas only; their techniques are described in the
report's own words and no code or table value is reproduced ([REFERENCES.md](../REFERENCES.md)).

### 10.2 Where the cited sources are

Everything is already on this machine; nothing was downloaded for the citations.

```
Minecraft 1.21.1 sources (4,064 common + 1,299 client-only files):
  fabric-bridge/.gradle/loom-cache/minecraftMaven/net/minecraft/
    minecraft-common-6a04650013/1.21.1-loom.mappings.1_21_1.layered+hash.730628366-v2/…-sources.jar
    minecraft-clientOnly-6a04650013/1.21.1-loom.mappings.1_21_1.layered+hash.730628366-v2/…-sources.jar
  one file, with line numbers:
    unzip -p <…-sources.jar> net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator.java | cat -n
  vanilla data (same folder, the jar without "-sources"):
    unzip -p <…common….jar> data/minecraft/worldgen/noise_settings/overworld.json
  a signature:
    "/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot/bin/javap" -cp <…common….jar> -p \
        net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator
Fabric API 0.116.17 and Cobblemon 1.8.1 (remapped jars, each with a -sources.jar beside it):
  fabric-bridge/.gradle/loom-cache/remapped_mods/remapped/net/fabricmc/fabric-api/<module>/<version>/
  fabric-bridge/.gradle/loom-cache/remapped_mods/remapped/com/cobblemon/fabric-8f637a66/1.8.1+1.21.1/
Full sources of Cobblemon (tag 1.8.1) and Gen1Recomp (dev @ cb02aeb, contained in v0.3.49):
  refs/cobblemon, refs/gen1recomp        (read only; nothing in refs/ was run)
```

This build of `unzip` needs `**` in a pattern to cross folders. JDK 21's `javap` is called by its full path;
the machine's default Java stays 17.

### 10.3 Verification log

Claims the designs rest on that the integrator re-read in the sources after the researchers reported. Every
one matched.

| # | Claim | Source re-read |
|---|---|---|
| 1 | `NoiseBasedChunkGenerator` is `final`; `generatorSettings()` is public; its only vanilla caller is `ChunkMap` | NoiseBasedChunkGenerator.java:49, :100; ChunkMap.java:183 (search of both source trees) |
| 2 | `ChunkMap` builds `RandomState` from real settings only for `instanceof NoiseBasedChunkGenerator` | ChunkMap.java:182-186 |
| 3 | Writes into a full chunk during generation are dropped | ImposterProtoChunk.java:80-83 |
| 4 | Write radius: noise, surface, carvers 0; features 1; requirement radii as listed | ChunkPyramid.java:9-44 |
| 5 | `StructureStart.INVALID_START`; `ChunkAccess#setStartForStructure`, `getAllStarts`; `ChunkGenerator#createStructures` is public | StructureStart.java:24; ChunkAccess.java:211, 216; ChunkGenerator.java:446 |
| 6 | A custom generator on the `minecraft:overworld` dimension type is still "stable" | WorldDimensions.java:129-137 |
| 7 | The Blender's "old chunk" test reads region files and caches per region | IOWorker.java:37-116 |
| 8 | Sea level 63 puts the top water block at Y 62 | Aquifer.java:86-88 |
| 9 | A dedicated server honours `generator-settings` only for the flat preset | DedicatedServerProperties.java:208-233 |
| 10 | `DensityFunctions.blendAlpha()/blendOffset()`, `CarvingMask#setAdditionalMask`, `NoiseRouter#mapAll`; `NoiseGeneratorSettings` has 11 components in the order used | DensityFunctions.java:233-239; CarvingMask.java:18; NoiseRouter.java:49; NoiseGeneratorSettings.java |
| 11 | The GameTest server always uses the flat preset | GameTestServer.java:99-102 |
| 12 | `SERVER_STARTING` fires before `initServer()` | Fabric lifecycle `MinecraftServerMixin.java:45-48`; MinecraftServer.java:664-666 |
| 13 | `Level#setBlock(BlockPos, BlockState, int, int)`, `Level#destroyBlock`, `FlowingFluid#canSpreadTo`, `PistonStructureResolver#resolve`, `Explosion#explode` and `getToBlow` exist as named | Level.java:221, 279; FlowingFluid.java:402; PistonStructureResolver.java:43; Explosion.java:210, 420 |
| 14 | `ClientboundSetTimePacket(long, long, boolean)` | ClientboundSetTimePacket.java:15 |
| 15 | Fabric events: `PlayerBlockBreakEvents.BEFORE`, `AttackBlockCallback`, `UseBlockCallback`, `ServerLivingEntityEvents.ALLOW_DAMAGE`, `EntityElytraEvents.ALLOW`, `ServerPlayerEvents.AFTER_RESPAWN` and `JOIN`, with the signatures quoted | the modules' sources jars (lines as cited in A5.1, A5.3, A4.8) |
| 16 | Today a player on no map is teleported back after 500 ms, at most every 3 s; no step is sent from a position on no map | `MovementSync.java:71, 83, 401-405, 483-499` |
| 17 | Story-cell collision is per moving entity; an entity with no governing player passes | `StoryCellBlock.java:61-80`; `StoryStates.java:45-50` |
| 18 | `BuildRequest.pending()` exists; the build starts on `SERVER_STARTED` | `BuildRequest.java:86-89`; `HeadlessBuild.java:50-70` |
| 19 | `party.sync` sends any dex up to 2000; gen1-bridge leaves out dex above 151 and moves above 165 by literal constants | `PartySync.java:21`; `gen1-bridge/act/kb_party.lua:345, 354` |
| 20 | The bag mirror adds and takes until the inventory matches the hidden bag | `BagService.java:118-135` |
| 21 | An out-of-bounds step is refused with `bounds`; an edge without a warp or connection does nothing | `refs/gen1recomp/src/world/Collision.lua:56-59`; `OverworldController.lua:2047-2068` |
| 22 | `mod.world:warpTo` exists as described; only the top state updates; the Bill's PC hold is a pushed state | `refs/gen1recomp/src/world/WorldAPI.lua:358-370`; `src/core/StateStack.lua:60-63`; `gen1-bridge/lib/kb_engine.lua:3362-3377` |
| 23 | Cobblemon: `PlayerSpawnerFactory.influenceBuilders`; `RIDE_EVENT_PRE` is cancelable; land rides step 1.2 | `PlayerSpawnerFactory.kt:41-46`; `CobblemonEvents.kt:174`; `HorseBehaviour.kt:534-540` |
| 24 | `FabricBakedModel#isVanillaAdapter`, `emitBlockQuads`; `ModelLoadingPlugin`, `registerBlockStateResolver`; `BlockStateResolver.Context#setModel` | the modules' sources jars (FabricBakedModel.java:51, 94; ModelLoadingPlugin.java:40, 48, 70; BlockStateResolver.java:52, 70) |
| 25 | Indigo calls `emitBlockQuads` from the chunk mesher; `renderSingleBlock` reads `getQuads` only | `TerrainRenderContext.java:83-96`; BlockRenderDispatcher.java:89-108 |
| 26 | Face culling is cached per pair of states and direction (2,048 entries) | Block.java:211-241 |
| 27 | Sodium for 1.21.1: licence Polyform Shield 1.0.0, `provides: ["indium"]`, `fabric-renderer-api-v1:contains_renderer`, `breaks iris <1.8.13` | <https://raw.githubusercontent.com/CaffeineMC/sodium/mc1.21.1-0.8.13/fabric/src/main/resources/fabric.mod.json> |
| 28 | Distant Horizons `3.3.3-1.21.1`, a release of 2026-09-29, one jar for Fabric and NeoForge | Modrinth API, project `distanthorizons`, versions for 1.21.1 / fabric |
| 29 | The WorldIndex reader rejects unknown root fields and games other than red and blue | `WorldIndex.java:31-37, 230-236` |
| 30 | The generated pack is required, `TOP`, not fixed-position | `GeneratedPack.java:51-53` |
| 31 | Heights: wall 2, roof 4, void fill 3, counter 1; measured unit up to 6 rows, rounded to 2 or 4 | `HeightRules.java:16-30, 106-109` |
| 32 | Full slots use `cube_all`, half slots the half placeholder | `ResourcePackWriter.java:156-163` |
| 33 | Puppets are yaw-only billboards drawn as one quad in `entityCutoutNoCull` | `PuppetRenderer.java:56-68` |
| 34 | Outdoors the engine draws a substitute for the border block; FireRed, LeafGreen and Emerald are `beta`; Gen 3 elevation is a collision level | `refs/gen1recomp/src/render/TileRenderer.lua:11-32`; `src/core/GameVersion.lua:119, 138, 153`; `src/core/game3/collision.lua:926-952` |
| 35 | The planner already takes an origin; the surface box is the maps' bounding box grown by 12 blocks; the void fill is a 4-block column of border art; interiors are packed without reference to the surface box | `WorldPlanner.java:50, 114-116, 208-258, 301-337`; `WorldPlan.java:24-27, 254-265, 396-409` |
| 36 | "Following" is one flag: index present and not a free camera | `MoveController.java:86-106`; `MoveRuntime.java:109-125` |
| 37 | The rules apply on every server running the mod; the damage veto covers every player | `Rules.java:38, 56-68, 117-119` |
| 38 | The files and line ranges milestone M11 names: the create flow's preset key, level settings, `BuildRequest.submit` and `createFreshLevel`; the builder's file names and spawn call; the placer's ticket and write-only-what-differs rule; `InputLock.reason`; `SpeedSync`; the block registrations; the bike hook; `HabitatService.remove`; the headless build's server properties and report gates; gen1-bridge's handler wiring; the engine's music calls | `CreateKantoWorldFlow.java:93-94, 417-438`; `BuildTask.java:47-49, 226-231`; `BlockPlacer.java:207-216, 245-255`; `InputLock.java:107-110`; `SpeedSync.java:27-30`; `KbBlocks.java:54-64`; `StoryStateService.java:45-47`; `HabitatService.java:215-221`; `tools/e2e/build_world.py:64-73, 160-164, 173-187`; `gen1-bridge/main.lua:434-459`; `refs/gen1recomp/src/core/Music.lua:334, 357, 491` |

Read in full by the integrator for context: CLAUDE.md, DESIGN.md, NEXT_STAGES.md, PROGRESS.md, PROTOCOL.md,
PLAN.md §§1–2, 4.2–4.6, 6–9 and the appendices, VERSIONS.md, the Dramaless Shape and Kanto in First Person
sections of REFERENCES.md, `local/reports/build_report.json`, and the four researchers' findings.

### 10.4 External pages fetched

| Topic | URLs |
|---|---|
| Sodium | `https://api.modrinth.com/v2/project/sodium` and `…/version?game_versions=["1.21.1"]&loaders=["fabric"]`; `https://raw.githubusercontent.com/CaffeineMC/sodium/mc1.21.1-0.8.13/fabric/src/main/resources/fabric.mod.json`; source files on branch `1.21.1/stable` (`LevelRendererMixin`, `BlockRenderer`, `SodiumRenderer`, `AbstractBlockRenderContext`, `LevelSlice`) |
| Indium | `https://api.modrinth.com/v2/project/indium` |
| Iris | `https://api.modrinth.com/v2/project/iris` and its 1.21.1 versions; `https://raw.githubusercontent.com/IrisShaders/Iris/1.21.1/fabric/src/main/resources/fabric.mod.json`; `https://shaders.properties/current/reference/miscellaneous/block_properties/`, `…/attributes/mc_entity/`, `…/mod-support/distant_horizons/` |
| Distant Horizons | `https://api.modrinth.com/v2/project/distanthorizons` and its 1.21.1 versions; wiki pages "Mod Support", "Problems and Solutions", "General" and issue 785 through `https://gitlab.com/api/v4/projects/distant-horizons-team%2Fdistant-horizons/…`; source files `ClientBlockStateColorCache`, `QuadWrapper`, `BlockStateTextureProvider`, `BlockStateWrapper` and the core `Config.java` on `main` |
| Access wideners | `https://wiki.fabricmc.net/tutorial:accesswidening` |
| MagicaVoxel `.vox` | `https://raw.githubusercontent.com/ephtracy/voxel-model/master/MagicaVoxel-file-format-vox.txt` and `…-vox-extension.txt` |

The complete list with what was read on each page is in [B2.2](#b22-compatibility) and
[B4.3](#b43-what-a-pack-can-contain).
