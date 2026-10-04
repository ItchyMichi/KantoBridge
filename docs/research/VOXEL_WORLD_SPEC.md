# Research spec: Kanto inside a vanilla overworld, and region-aware voxel art

A framework for an AI researcher. Follow it to produce an in-depth, evidence-backed report that a
coding agent can implement from without asking basic questions. **This is research only: no code
changes.** Pseudo code and code examples belong in the report, not in the repository.

- **Commissioned by** the user on 2026-10-03, after M0–M10 were finished (see
  [PROGRESS.md](../PROGRESS.md)).
- **Background:** [NEXT_STAGES.md](../NEXT_STAGES.md) sections 2–5.

---

## 1. Purpose

The user wants two things researched deeply enough to code them:

**Part A — Kanto safely inside vanilla world generation.**
- **One overworld:** vanilla terrain, biomes, caves and structures everywhere, except a reserved
  footprint where Kanto is built.
- **Real gameplay outside:** ordinary Minecraft and Cobblemon play outside Kanto.
- **Seamless edges:** the vanilla land leads directly into Kanto. The player walks or sails in and
  out with **no teleport ("magic warp") if at all possible**, and can see the whole world, Kanto
  included, from a distance.
- **No change inside:** Kanto keeps today's behaviour within its footprint (story, movement sync,
  collision, warps, saving, battles, multiplayer).

**Part B — flexible voxel art for the region the player is in.**
- **The block approach:** today's custom tile blocks compared with a flexible, data-driven block.
- **Shapes:** Dramaless-Shape-style 3D shapes generated from each player's own ROM art.
- **Player texture packs** for the voxel world.
- **Characters:** three styles (thick cards, carved voxel figures, rigged Minecraft-style figures).
- **Game-agnostic:** one architecture that can serve other games later.

**Later, not to be designed now, but nothing may block them:**
- other regions in the same world, such as Johto across the sea;
- a "Kanto team in Johto" with bag requirements that make a high-level team practically unusable at
  first in another region;
- carrying progress between region saves.

---

## 2. Rules for the researcher

1. **No repository changes** except writing the report files named in §8. No commits, no git state
   changes, no Gradle builds or `runClient`. Read-only commands are fine: `javap` on the
   Loom-mapped Minecraft jar, `unzip -l`/`-p` on jars, grep, reading sources.
2. **Data safety** (CLAUDE.md "Hard rules", PLAN.md §0.3):
   - Never open anything under `local/` except listing names and reading `local/reports/*.json`.
     The real export, `world_index.json`, `slot_map.json`, the packs and Gen1Recomp's data are
     ROM-derived.
   - Never open `refs/personal rom dumps`.
   - Never run tools that read ROM-derived art. That includes Dramaless Shape's `tools/` and
     `assets/docs/*.py`.
   - When the real Kanto's numbers are needed, use the counts in `local/reports/build_report.json`,
     or say exactly which count a future report field should add.
3. **Licences** ([REFERENCES.md](../REFERENCES.md)):
   - Dramaless Shape (MIT for its 2.0 work, base unverifiable) and Kanto in First Person (no licence)
     are **ideas and documented rules only**. Never copy their code, tables or assets into answers.
   - Cobblemon is MPL-2.0: calling its API is fine.
   - Minecraft code may be described and quoted briefly for analysis.
4. **Evidence:**
   - Every factual claim cites its source: `path:line` for our repository; `jar → class#member` (via
     `javap`) or `refs/<project>/path:line` for others; a URL for external documentation.
   - Separate **facts**, **inferences** and **recommendations**.
   - Mark anything not checked against a source **⚠ Not confirmed**.
   - Prefer reading the real source over memory, especially for Minecraft 1.21.1 and Fabric API
     0.116.17 signatures.
5. **Experiments:** questions marked **[measure]** need numbers.
   - Where a number would need throwaway code, **design the experiment** instead: pseudo code,
     metrics, pass thresholds. Don't run it unless the user approves.
   - Never launch the user's Gen1Recomp (`local/gen1recomp`) or Minecraft.
6. **Pins:** adding or upgrading a dependency (Sodium, Iris, Distant Horizons, anything else) is a
   **decision for the user**. Present it as an option with its pin, licence and risk.
7. **Stop and record** under "Blocked" in the report, rather than improvising, if:
   - a question can't be answered without touching a hard rule; or
   - two sources contradict each other and the code can't settle which is right.

---

## 3. Method

| Phase | Work | Output |
|---|---|---|
| 0. Context | Read CLAUDE.md, DESIGN.md (user-written, never modify), PLAN.md §§2–4, PROGRESS.md (deviations 1–110, open issues), PROTOCOL.md (WorldIndex, slot map, generated pack, relay, multiplayer and crash rules), NEXT_STAGES.md, REFERENCES.md (Dramaless Shape, Kanto in First Person, Cobblemon) | — |
| 1. As-is audit | Answer **A1** and **B1** first: how the code really works today | A facts table with citations; corrections to §4 |
| 2. Questions | Answer A2–A8 and B2–B8 | Per question: facts, options, recommendation, pseudo code |
| 3. Synthesis | One coherent design for Part A and one for Part B, plus how they meet | Architecture sections, data formats, a milestone plan |
| 4. Cross-checks | §7: hard rules, multiplayer, performance, version risk, contradictions | A cross-check table |
| 5. Report | Write it in the format of §8 | `docs/research/VOXEL_WORLD_REPORT.md` |

**Parallel work (optional).** Three researchers can work at once, with an integrator owning phases
3–5:
1. **World generation and the builder:** A1–A3 and A6.
2. **Runtime scoping and the hidden game:** A4, A5, A7 and A8.
3. **Rendering:** B1–B8.

**Answer format,** for every question ID:

```
### A2.1 <title>
**Answer:** facts with citations (and ⚠ for unconfirmed).
**Options:** each with how it works, what it changes in our code (files/classes), costs, risks.
**Recommendation:** the choice and why; what would change the choice.
**Sketch:** pseudo code or a data format, when it helps.
**Open:** anything left for the user or a later experiment.
```

---

## 4. Starting leads (verify; correct them in A1/B1)

These come from earlier exploration and are deliberately unverified. Treat each as a lead to check.

**Our world today**
- **World preset:** `fabric-bridge/src/main/resources/data/kantobridge/worldgen/world_preset/kanto.json`
  is an **empty flat** overworld: no layers, no features, no structures, biome
  `kantobridge:kanto_outdoor`. `data/minecraft/tags/worldgen/world_preset/normal.json` lists it.
- **The dimension-type override:** `data/minecraft/dimension_type/overworld.json` overrides
  **minecraft:overworld itself**, with `min_y -256` and `height 576`. That is presumably for the
  underground interior layers, and it applies to every overworld while the mod is loaded.
- **Vanilla 1.21.1's overworld noise settings** (inside the mapped jar,
  `data/minecraft/worldgen/noise_settings/overworld.json`): `sea_level 63`, `min_y -64`,
  `height 384`, aquifers and ore veins on.
- **Kanto's height against vanilla's sea:** Kanto's ground blocks sit at **Y 63** (feet 64), and its
  water cells are half blocks topping out at **Y 63.5** (PLAN.md §4.5). They won't meet vanilla's
  ocean surface (about 63.0) flush.
- **Biomes:** `data/kantobridge/worldgen/biome/kanto_outdoor|kanto_interior|kanto_cave.json` have no
  precipitation, empty spawners, no carvers or features, and silent music.
- **Spawn rules:** `data/kantobridge/spawn_rules/no_interior_spawns.json` and
  `walking_level_only.json`.
- **The builder** (`fabric-bridge/src/main/java/dev/kantobridge/builder/`):
  - `KantoWorldBuilder`, `BuildTask`, `HeadlessBuild`;
  - `plan/` (`WorldPlanner`, `WorldPlan` with `SURFACE_MAX_Y`, `Layout`, `InteriorPacker`,
    `HeightRules`, `MapModel`, `PlacedMap`, `StoryCellPlanner`, `HabitatPlanner`, `ArtCatalog`,
    `BlockCode`, `ChunkCodes`);
  - `place/` (`BlockPlacer`, `BiomeWriter`, `CodeStates`);
  - `pack/` (`ResourcePackWriter`, `DatapackWriter`, `HabitatPoolWriter`, `Textures`, `Png`);
  - `export/` (`ExportMap`, `ExportTileset`, `ExportImage`, `ExportPalettes`, `ExportField`,
    `ExportLayout`, `ExportSwaps`, `CellFlags`).
- **The real full build** (`local/reports/build_report.json`):
  - maps: 220, of which 36 surface maps and 184 interiors in **14 layers**;
  - space: **2,126 chunks**, 87,564 void-fill cells;
  - story: 12,666 story cells and 3,032 ledge colliders;
  - blocks: 12,114 light blocks and 2,485,154 blocks placed in about 57 s (`throughput` 49k
    blocks/s);
  - art: slots 3,926 of 4,096 full and 92 half; 4,301 pack textures.
- **Rules:**
  - `server/Rules`: adventure mode on join, `WALK_SPEED` 0.1737 and `BIKE_SPEED` 0.3474, jump zeroed;
  - `server/KantoGameRules`: daylight, weather and mob spawning off; fall, drowning, fire and freeze
    damage off; keepInventory and immediate respawn; mob griefing, fire tick and vine spread off;
  - also `server/CobblemonSettings`, `server/BuildHold`, `server/WarpService`,
    `server/SaveService`, `server/SessionService`, `server/SavePairService`.
- **Client movement** (`client/move/` `MoveRuntime`, `MovementSync`, `InputLock`, `SpeedSync`,
  `ForcedMover`, `WarpFade`):
  - it follows the hidden game only in "Kanto worlds" (WorldIndex present), never in creative or
    spectator;
  - joining, re-linking and snapshots **teleport** the player onto the hidden cell (`warp.request`);
  - the client holds the player when the hidden map isn't built.
- **Story collision:** `story/StoryState`, `story/StoryRules`, and the blocks
  `block/StoryCellBlock`, `StoryTileBlock`, `LedgeColliderBlock`.

**The art pipeline today**
- **Tile blocks:** 32 families × 256 states (16 full, 16 half). Each slot has a `cube_all` model, the
  same tile art on all six faces (`ResourcePackWriter`).
- **The slot map:** `<world>/kantobridge/slot_map.json`, computed from the whole export.
- **The generated pack:** `<gameDir>/kantobridge/resourcepack/`, injected by
  `mixin/client/BuiltInPackSourceMixin` and managed by `client/packs/`.
- **The world index on the client:** `client/world/ClientWorldIndex`.
- **Puppets** (`client/puppet/`): upright 2×2-block billboards that turn to face the camera, with the
  facing frame picked relative to the camera (`FacingFrames`).
- **Effects:** `client/fx/` (`SpeechBubbles`, `BlockadeFade`) and `SurfSprite`.

**Platforms**
- **Fabric API 0.116.17+1.21.1 modules present:**
  - rendering: `fabric-renderer-api-v1` (`FabricBakedModel`, `QuadEmitter`, `RenderContext`),
    `fabric-renderer-indigo`, `fabric-block-view-api-v2` (`FabricBlockView`,
    `RenderDataBlockEntity`), `fabric-model-loading-api-v1`;
  - world and data: `fabric-biome-api-v1`, `fabric-dimensions-v1`, `fabric-data-attachment-api-v1`,
    `fabric-game-rule-api-v1`;
  - events: `fabric-events-interaction-v0` (`PlayerBlockBreakEvents`, `UseBlockCallback`,
    `AttackBlockCallback`, `UseItemCallback`), `fabric-entity-events-v1`.
- **Minecraft 1.21.1's world generation API** (mapped jar):
  - `ChunkGenerator` has `fillFromNoise(Blender, RandomState, StructureManager, ChunkAccess)`,
    `buildSurface`, `applyCarvers`, `applyBiomeDecoration`, `createStructures`, `getBaseHeight`,
    `getBaseColumn`, `getSeaLevel`, `getMinY` and `getGenDepth`;
  - also `NoiseBasedChunkGenerator`, `NoiseRouter`, `DensityFunctions`, `MultiNoiseBiomeSource`,
    `StructureSet`, and `levelgen.blending.BlendingData`/`Blender`;
  - **`Attributes.SCALE`** and `STEP_HEIGHT` exist.
- **Cobblemon 1.8.1** has a riding API (`refs/cobblemon/.../api/riding/`, land, liquid and air
  behaviours), and Lapras has riding data.
- **Gen1Recomp v0.3.49** runs Red, Blue and Yellow; Gold, Silver and Crystal; FireRed, LeafGreen and
  Emerald. Its Gen 3 engine has a per-cell elevation (`refs/gen1recomp/src/core/game3/collision.lua`).
- **Prior art:**
  - Dramaless Shape draws characters as flat south-facing cards on purpose
    (`refs/DRAMALESS_SHAPE-main/lib/SpriteBillboards.lua:1-30`).
  - Kanto in First Person textures risers and ceilings from each room's own art
    (`refs/kanto-first-person/payload_ceiling.lua:1-40`).

---

## 5. Part A questions — Kanto inside a vanilla overworld

### A1. As-is audit

- **A1.1 World creation, end to end.**
  - **Question:** how is a Kanto world created and built today, and what in Java or data assumes the
    empty flat generator?
  - **Look at:** the preset and tag JSON, `client/.../CreateKantoWorldFlow`, `HeadlessBuild`,
    `BuildTask`, and the `server.properties` that `tools/e2e/build_world.py` writes.
  - **Answer with:** a sequence diagram and a list of every flat-world assumption.
- **A1.2 The vertical budget.**
  - Why does the mod override the `minecraft:overworld` dimension type?
  - Exactly which Y range does a full build use: the surface Y, the interior layer step, the deepest
    interior floor, ceilings, void-fill heights, and how `SURFACE_MAX_Y` is derived?
  - What does the override do to an ordinary vanilla world created while the mod is installed?
    Vanilla terrain still spans −64…320, so what fills −256…−64?
- **A1.3 Placement.**
  - Which map sits at the origin, and how are map origins, the surface bounding box and the
    void-fill ring computed?
  - How do interiors find their layers, and do sideways spills exceed the surface footprint?
  - **[measure]** Footprint width × depth in blocks, the interior extents, and the total reserved
    volume. Use the synthetic bundle plus the counts allowed from the real report, and name the
    report fields that would give the real numbers.
- **A1.4 The placer's assumptions.**
  - What does `BlockPlacer` assume about existing blocks (re-runnable volumes, `blocks_cleared`)?
  - Cover chunk tickets, lighting, heightmaps, `setBlock` flags, and anything it writes outside its
    volumes (habitat blocks under the ground, light blocks, biomes).
- **A1.5 World-scoped behaviours.** Inventory every behaviour that is world-scoped or server-scoped
  today: gamerules, rules on join, attributes, biome settings, spawn rules, Cobblemon settings,
  following and `InputLock`, teleports on join, respawn and blackout, warps, saving, `BuildHold`,
  puppets, the HUD and F7, the pause overlay, bubbles, fading, the surf sprite and the texture flow.
  For each:
  - where it lives (`path:line`);
  - its scope today;
  - what it would do to a player standing in vanilla terrain.
- **A1.6 A player outside every map.** What happens today to a player standing outside every built
  map in a Kanto world? And when the hidden player is on a map that isn't built?
- **A1.7 "One world = one Kanto".** What in session and identity assumes it: `session.bind{world}`,
  `SessionService`, `StoryState` persistence, save pairs, WorldIndex sync, `world.slots`,
  `texture.status`?

### A2. World generation: vanilla terrain plus a reserved footprint

- **A2.1 Architecture.** Compare, with evidence from the 1.21.1 classes:
  - **(a)** a `ChunkGenerator` that extends or wraps `NoiseBasedChunkGenerator` and short-circuits
    its stages inside the footprint;
  - **(b)** a registered `DensityFunction` "footprint mask" in a copy of vanilla's overworld noise
    router, plus a `BiomeSource` that wraps `multi_noise` to return Kanto biomes inside;
  - **(c)** vanilla generation followed by the builder clearing what vanilla made;
  - **(d)** vanilla's `Blender`/`BlendingData`, treating Kanto's chunks as "old" chunks that new
    terrain blends into;
  - **(e)** combinations.

  For each option, say:
  - how terrain, surface rules, aquifers, ore veins, carvers, features, structures,
    `spawnOriginalMobs`, heightmaps and `getBaseHeight` behave inside and at the edge;
  - how it is registered (codecs, Fabric registries, world preset JSON, datapack against Java);
  - what it costs to keep working across Minecraft updates.

  Recommend one, with pseudo code.
- **A2.2 Structures.** How are structure **starts** and **pieces** kept out of the footprint plus a
  margin?
  - Cover villages, pillager outposts, mineshafts, strongholds, trial chambers, ancient cities
    (deep), ocean monuments and ruins, shipwrecks and buried treasure.
  - Is biome validity enough, given that Kanto's biomes have no structure tags? Or do pieces
    reaching in from outside starts need a bounding-box filter, and where would it hook in?
- **A2.3 Spillover.** How far can carvers (up to 8 chunks), features such as trees, lakes, geodes and
  disks, and lava or aquifers reach into the footprint from outside? What margin is needed, and how
  is each one masked?
- **A2.4 Underground.**
  - What surrounds Kanto's interiors (14 layers below the surface) in a vanilla world?
  - Should interiors live **below vanilla's −64 floor**, isolated in the extra depth the
    dimension-type override gives?
  - Or should the whole footprint column be reserved from top to bottom?
  - How are interiors sealed against caves, mining players and fluids? Consider a bedrock-like
    shell, barriers, or protection volumes.
  - Is a dimension type taller than the noise settings valid? Check the validation rules.
- **A2.5 Biomes.**
  - How does what `BiomeWriter` writes match what the biome source reports? Minecraft stores
    biomes per 4×4×4 cell.
  - How do grass and foliage colours blend across the border?
  - How do biomes decide Cobblemon spawning: vanilla biomes outside, habitat pools inside?
- **A2.6 Sea level and heights.**
  - **The gap:** vanilla's sea surface is about 63.0, while Kanto's ground is at Y 63 (feet 64) and
    its water half blocks top out at 63.5.
  - **The options:**
    - shift Kanto (what changes in `Scale`, the WorldIndex, `Rules` and the client);
    - a custom noise `sea_level`;
    - transition blocks at the shoreline.
  - **The consequences** for existing worlds and saves.
- **A2.7 World creation UX.**
  - A new preset ("Kanto in a vanilla world") alongside the flat one?
  - **Seeds:** vanilla terrain from the seed, with Kanto at a fixed or seed-chosen position.
  - **The spawn point:** outside Kanto, in Pallet Town, or at a viewpoint?
  - How do the "Create Kanto world" flow and the headless build change?
- **A2.8 Placement and surroundings.**
  - Where should Kanto go: near spawn, on its own continent or island?
  - How should the generator shape the land around it?
    - lowering to sea level along water edges;
    - hills behind tree walls;
    - a coastline along Kanto's southern sea.
  - Reserve a **region layout** for later, such as Johto across the sea: coordinates, distances
    and world-border limits.
- **A2.9 Determinism and the client.**
  - The server generates terrain. Which footprint data does the client need, for region lookups and
    rendering, and how does it get there?
  - How does that data extend today's `world.index` relay?
- **A2.10 Performance [measure].** Design measurements for:
  - chunk generation time with the mask against plain vanilla;
  - build time with vanilla chunks around;
  - memory.
- **A2.11 Existing worlds.**
  - The user's flat `saves/Kanto` holds their player data and Cobblemon party: keep it as legacy, or
    migrate it?
  - What carries over to a new vanilla-world build: Cobblemon data, attachments, save pairing?

### A3. The footprint's shape and its seamless edges

- **A3.1 The footprint.**
  - **Its shape:** a per-cell mask (surface cells, the void ring, a margin), map rectangles, or a
    hull?
  - **Its extent:** vertical, plus the interiors' horizontal extents.
  - **Storage:** where it is stored in the world and how it is versioned.
- **A3.2 The edge inventory.** Count only.
  - How many outer-edge cells per side, by kind: tree wall, sea, cliff or rock, building, other?
  - Which surface maps own outer edges?
  - Does our export carry Gen 1's per-map **border block**? Check `ExportMap` and the layout
    schemas.
- **A3.3 Transitions per edge kind.**
  - **Tree walls:** into a vanilla forest belt.
  - **Sea:** into vanilla ocean at a matching height.
  - **Cliffs:** into hills.
  - **A height ramp:** vanilla terrain easing to Kanto's level over *N* blocks.
  - **The void-fill ring:** keep it as Kanto's inner wall, replace it with transition art, or make
    it configurable?

  Include pseudo code for the ramp.
- **A3.4 Visual continuity.** How should Kanto's tile art meet vanilla blocks? Options: a fringe of
  Kanto-art blocks fading out, biome tints, or nothing. Weigh what reads well from up close against
  from afar.
- **A3.5 Sightlines: "see the whole world from a distance."**
  - Kanto's size against vanilla's render distance (a client maximum of 32 chunks, and the server's
    view distance), plus fog.
  - Vantage points facing Kanto.
  - **The LOD option:** Distant Horizons for Fabric 1.21.1. How does it draw custom blocks and
    textures, and data-driven models? What is its pin, licence and risk?
  - **[measure]** Design an fps measurement over Kanto at render distances 16 and 32.
- **A3.6 Sky and time in one overworld.**
  - **Today:** fixed noon through a world-wide gamerule.
  - **The options:**
    - vanilla day and night everywhere;
    - a client-side time override inside Kanto, faded at the border;
    - biome sky colours.
  - How each fits Gen 1, which has no clock.

### A4. Entering and leaving without a magic warp

- **A4.1 The hidden game's edges.** Confirm in Gen1Recomp's source and in `gen1-bridge/act/kb_move.lua`
  that stepping off an outer map edge without a connection is refused (`player.blocked{bounds}`).
  Is there any other "outside" in the hidden game?
- **A4.2 Leaving.** Define the **gate model**.
  - Where may a player leave: every outer edge, sea edges only, or designated gates?
  - How does the client stop following *before* the step that would leave?
  - What do the hidden player, NPCs, encounters and the pause overlay do while the player is out?
  - Include a state machine with pseudo code.
- **A4.3 Entering.** The player steps into the footprint at cell *C*.
  - **If *C* is the hidden player's cell**, following simply resumes.
  - **Otherwise, the options are:**
    - **refuse** (the border is solid except at the gate you left by);
    - a **hidden-only warp** (`mod.world:warpTo` or a teleport in gen1-bridge, invisible in
      Minecraft; what does it reset?);
    - a **scripted hidden walk**;
    - **reachability:** allow *C* only if it is reachable from the hidden player's cell under the
      current story state (cut trees, Surf, Strength, guards, Snorlax).

    Can reachability be computed, and by whom: gen1-bridge, or the server's `StoryRules` plus the
    WorldIndex graph?
  - Recommend a policy and say why it is story-safe.
- **A4.4 Story gates on the outer boundary.**
  - Which outer edges would let a player reach areas early, for example Cinnabar or Seafoam by sea
    before Surf, Route 23 or Indigo, Cerulean Cape?
  - How are those prevented: the outer border carrying story collision, entry policies, or blocking
    vanilla boats and Cobblemon riding at sea edges?
- **A4.5 Crossing physically.**
  - **What changes at the border:** game mode (adventure inside, survival outside?), the speed
    attribute (Gen 1 pace inside, vanilla outside), jump and sprint, step height, and the inventory
    (Minecraft items outside, the hidden bag inside).
  - **How:** server-side attribute modifiers, client prediction, and the transition states of
    `MovementSync` and `InputLock`.
  - **Smoothness:** what keeps the crossing from stuttering?
- **A4.6 Joining, dying and respawning outside.**
  - **Joining:** no snap to the hidden cell when joining outside.
  - **Dying:** respawn outside (bed, world spawn), with Kanto blackouts staying inside-only.
  - **The hidden game:** what if it is at its title, switching save slots (M9), or disconnected
    (the M10 pause) while the player is outside?
- **A4.7 Multiplayer.** Some players are inside and some outside.
  - How do puppets, speech bubbles, story cells, fading, relay authority, `BuildHold` and the
    dedicated server behave?
  - Is anything broadcast that shouldn't be?
- **A4.8 Edge cases.**
  - Ways in or out other than walking:
    - boats;
    - Cobblemon riding across the border;
    - elytra, ender pearls and chorus fruit;
    - nether portals built near or inside the footprint;
    - minecarts and rails.
  - Players: creative flight, spectators, and operators' commands (`/tp`, `/fill`).
  - For each, say whether to block, allow or handle it, and how.

### A5. Scoping every Kanto rule to the footprint

For every behaviour listed in A1.5, give:
- the region-scoped mechanism (a Fabric event, a mixin target, an attribute modifier, a biome
  property, a client check);
- what happens at the border;
- a GameTest or JUnit test.

At minimum:

- **A5.1 Building.** Adventure mode inside against survival outside: game-mode switching, or
  per-position permission through Fabric events. What vanilla checks apply (`mayBuild`,
  block-action restrictions)?
- **A5.2 Movement.** Speed, jump strength 0, sprint blocking (`LocalPlayerMixin`) and step height,
  inside only.
- **A5.3 Damage.** Fall, drowning, fire, freeze and hunger.
  - Gamerules are world-wide. Are `ServerLivingEntityEvents.ALLOW_DAMAGE` and similar per-position
    alternatives enough?
  - What about status effects?
- **A5.4 Time and weather.** These gamerules are world-wide. Kanto's biomes already have no
  precipitation. Choose a scheme (with A3.6).
- **A5.5 Vanilla mobs.**
  - Mob spawning outside, with nothing inside, including dark interiors such as Rock Tunnel.
  - Patrols, phantoms, wandering traders, raids, sieges, spawners, and structure spawns at the
    border.
- **A5.6 Cobblemon spawning.**
  - Cobblemon's natural spawns outside (which species, which pools?) against habitat pools inside
    (`no_interior_spawns`, `walking_level_only`).
  - Which `CobblemonSettings` changes are global today?
  - Should outside spawns be limited to the 151 as a setting? (See A7.1.)
- **A5.7 Block-changing mechanics.** List every mechanic that can change blocks in the footprint and
  the hook that stops it:
  - griefing, fire spread, fluids, explosions, pistons;
  - endermen, ravagers, withers, TNT, dispensers, sculk;
  - tree and sapling growth, bonemeal, snow and ice, leaf decay;
  - vines and dripstone.

  Pay special attention to the border, where survival players and flowing fluids act from outside.
- **A5.8 Client checks.** Which client checks must change from "Kanto world" to "inside the
  footprint"? Following, `InputLock`, the pause overlay, the HUD and F7, puppets, bubbles, fading,
  the surf sprite, the texture flow, and the M10 warning screens.
- **A5.9 Inventory and respawn.** keepInventory and immediate respawn are world-wide and suit Kanto.
  What should apply outside?

### A6. Building Kanto into a vanilla world

- **A6.1 When the build runs.**
  - At world creation (from the preset), or on demand through the existing flow and the headless
    build?
  - With vanilla chunks already generated, or not yet?
  - Can the generator guarantee an empty footprint so the placer never has to clear, and if not,
    what must the placer clear?
- **A6.2 Lighting and chunk state.**
  - Lighting, heightmaps and skylight where the footprint meets vanilla-lit chunks.
  - The chunk status needed at placement time.
  - Neighbour updates at the border: fluids next to Kanto's water.
- **A6.3 The noise settings and the dimension type.**
  - Do vanilla's noise settings (`min_y -64`) and our dimension type (`min_y -256`) combine validly?
  - What generates below −64? Is a custom noise-settings copy needed?
- **A6.4 Tests.**
  - Can the GameTest server host a vanilla-noise generator, or is the footprint mask tested in JUnit
    plus a headless e2e build?
  - Design the tests, counts only.
- **A6.5 Rebuilds.**
  - What happens when the builder re-runs inside an existing vanilla world?
  - What if a new export changes the footprint's size?
- **A6.6 Migration.** What happens to the user's flat `saves/Kanto` (A2.11)?

### A7. The hidden game and Cobblemon while playing outside

- **A7.1 Party and Pokédex sync beyond Gen 1.**
  - What happens today when the Cobblemon party or Pokédex holds species above 151, Gen 2+ moves,
    held items or forms, and `party.sync`/`dex.sync` go to the Gen 1 hidden game?
  - Check the schemas in `protocol/messages/party.sync.schema.json`, `dex.sync`, and
    `protocol/defs.schema.json` (`dex`, `nationalDex`, `gen1Move`).
  - Check `pokemon/PartyService`, `GenOneIds`, and `gen1-bridge/act/kb_party.lua`.
  - **Options:** refuse, use placeholders, keep such Pokémon in the PC, or restrict outside spawns to
    the 151 for now. Note how this feeds the later "bag requirements per region" idea.
- **A7.2 Save pairs.** Confirm that outside play creates no save-pair operations
  (`SavePairService` counts only `party.give`, `party.take` and the daycare messages from the hidden
  game). Would any M10 crash-recovery warning misfire?
- **A7.3 Outside economy.** Cobblemon's healing machine, PC and items outside Kanto, against Kanto's
  hidden bag and money. Check what the mod changes in `data/cobblemon/recipe/*` and why.
- **A7.4 What the hidden window shows** while the player is outside, and what the user sees there.

### A8. Region identity (groundwork; design only what Part A needs)

- **A8.1 The region record.** Define one:
  - `id` (e.g. `kanto@blue`), the game, the export hash and slot-map hash;
  - the origin, footprint and gates;
  - the rules profile and namespaces;
  - where it is stored, how it reaches clients, and how it extends the WorldIndex.
- **A8.2 Position lookups.** A position-to-region lookup on both server and client: data structure,
  memory and speed.
- **A8.3 Session and pairing.** How `session.bind` and save pairing become per region (a world plus a
  region) without breaking M9.

---

## 6. Part B questions — region-aware flexible voxel art

### B1. As-is audit

- **B1.1 Tile blocks.**
  - Registration, the hi/lo properties, collision, occlusion and outline shapes.
  - The render layer, `SoundType` and light.
  - How a placed block encodes atlas, palette, tile and shape (`BlockCode`, `CodeStates`).
  - How the slot map is built (`ArtCatalog`), its capacity, and exact numbers.
- **B1.2 The generated pack.**
  - The exact layout and colouring (SGB palettes), upscaling and animations.
  - NPC sheets and the surf sprite; `kantobridge.json`.
  - How it is installed and reloaded (`PackGenerator`, `GeneratedPack`, `TextureSync`,
    `BuiltInPackSourceMixin`).
  - Its place in the pack stack, and whether players can stack their own packs above it today.
- **B1.3 Per-player story art.** How a cell that is closed for the local player (an uncut tree, a
  boulder, a guard) is drawn differently from one that is open for another player, using
  `storyCells[].closed.art`, `StoryTileBlock` and the client code. How does it update?
- **B1.4 Puppets.** Rendering, textures, `FacingFrames` selection, sizes, culling (M10), the surf
  sprite, and how other players are drawn.
- **B1.5 What a generator can use.**
  - What exactly can a Java generator read from the export today: cells, flags, block or metatile
    ids, tile ids per cell, atlas pixels, palettes, animation frames, sprites, swap sites,
    connections and border blocks?
  - What is **missing** for Dramaless-style analysis: measured heights from drawn rows, background
    pixels, band classification?
- **B1.6 Single-region assumptions in the art pipeline:** one pack, one `slotMapHash`,
  `texture.status`, `world.slots`.
- **B1.7 Existing custom rendering:** `WorldRenderEvents` hooks, mixins, render-layer registrations,
  and any use of the Fabric rendering API.

### B2. Static blocks against a flexible block

- **B2.1 The flexible block in Fabric 1.21.1.**
  - The exact `FabricBakedModel.emitBlockQuads` signature, and its threading: chunk meshing runs off
    the main thread.
  - **Registering the model:** a model-loading plugin with a custom unbaked or baked model.
  - **Where per-position data comes from:**
    - **(a)** the block state (today's codes);
    - **(b)** `RenderDataBlockEntity` (what does a block entity cost per position, and for how
      many?);
    - **(c)** a client-side lookup from position to shape, per region and section (thread safety,
      `setSectionDirty` invalidation);
    - **(d)** neighbour queries through `BlockAndTintGetter`.
  - Per-quad render layers (blend modes), ambient occlusion and emissive light.
  - Include a sketch of `emitBlockQuads`.
- **B2.2 Compatibility,** each with its pin, licence and status:
  - **Indigo:** the default renderer.
  - **Sodium for 1.21.1:** does it support the Fabric rendering API?
  - **Iris shader packs:** how do they identify our blocks, and can waving grass and leaves or water
    reflections recognise them?
  - **Distant Horizons:** how does it draw data-driven and modded blocks?
- **B2.3 Performance [measure].**
  - Quads per section for greedy-meshed voxel shapes, chunk rebuild cost, and memory for shape tables
    and atlases.
  - The worst case: a forest of voxel trees.
  - Occlusion (`getOcclusionShape` for shapes that aren't full cubes, neighbour face culling) and
    light (`propagatesSkylightDown`, `getLightBlock`).
  - Set budgets and pass thresholds.
- **B2.4 Collision and interaction.**
  - Keep per-cell collision (tile blocks plus story cells) independent of visual shapes.
  - Outline and selection shapes for the crosshair (interacting with signs and counters), ledges,
    and counters.
- **B2.5 Capacity and namespaces.**
  - The static approach needs a state per shape, so how many families would it take?
  - Flexible ids per region; several regions in view at once.
  - Texture atlas limits: GPU maximum size, sprite counts, mipmaps.
  - A namespace scheme, such as `kantobridge:block/<region>/...`.
- **B2.6 Per-player story art with shapes.** How does each approach draw closed and open shapes per
  player, re-mesh on story changes, and stay right in multiplayer?
- **B2.7 Prototype design [measure].**
  - Pallet Town built both ways: what to build and which metrics (chunk mesh ms, fps at 16 and 32
    chunks, memory, build time).
  - Thresholds that decide.
- **B2.8 Recommendation and migration.**
  - A recommendation with a migration path from today's blocks, for example: tile blocks for flat
    ground and collision, a flexible block for shaped art.
  - What stays on the server (collision, story blocks) and what becomes client-only (visual
    geometry).

### B3. The shape generator

- **B3.1 Dramaless Shape's geometry.**
  - Inventory its archetypes and rules, as ideas only: `TileShape` classes and pins,
    `Structures` (regions, background-pixel detection, measured heights, model choice), `Buildings`
    (band classification), ledges, water, trees and canopies, cliffs and mountains across map seams,
    interior furniture, caves.
  - Use `refs/DRAMALESS_SHAPE-main/lib/*.lua`, `data/voxel_heights.lua` (to describe only) and
    `assets/docs/buidling_to_voxel/sprite_to_voxel_methodology.md`.
  - Which rules need its hand-made tables (a licence decision), and which can be derived from the
    art?
- **B3.2 Kanto in First Person's dressing.**
  - Risers and ceilings from room art, caves, canopies, the apron beyond the map edge.
  - Which of these are builder geometry, and which are client effects?
- **B3.3 A neutral scene description.**
  - A draft schema (cells, behaviours, art layers, heights or elevation, structures, props,
    sprites) for Gen 1.
  - Checked on paper against Gen 2 (GBC per-tile palettes, time-of-day palettes, 32×32 blocks with
    16×16 collision) and Gen 3 (metatile bottom and top layers, behaviours, per-cell elevation,
    bridges and multi-level cells).
  - What in today's export or builder is Gen-1-specific (`CellFlags`, swaps, palettes)?
- **B3.4 Determinism.** Shape ids must be identical for every client from the same export, as the
  slot map is: ordering, hashing, and the generator's version inside the hash.
- **B3.5 Where generation runs.**
  - **The server or builder:** world-scoped data, positions to shape ids.
  - **Each client:** geometry and art from its own export. Art never travels.
  - Data volumes and build-time impact.
- **B3.6 A self-check.**
  - Project the generated 3D back into the game's own angled top-down view and compare it with the
    game's frame, locally. Log scores only.
  - Is it feasible, which metrics, and when does it run?
- **B3.7 Story cells and swap sites.** Their open and closed shapes: boulders, cuttable trees,
  card-key doors, and Strength pushes.

### B4. Player-made texture packs

- **B4.1 Stable, readable keys.**
  - `game/tileset/tile`, sprite ids, map plus structure, tile classes.
  - How today's slot ids map to them, and where the index lives.
- **B4.2 Layering.**
  - Minecraft's pack stacking above our generated pack, which needs key-based texture paths.
  - A `kantobridge` pack manifest for shapes, rules and models.
  - Precedence, validation, fallbacks and reloading (F3+T).
- **B4.3 What a pack can contain.**
  - Textures at higher resolutions.
  - Shape-rule overrides.
  - Hand-made voxel models: the MagicaVoxel `.vox` format, its palette, size limits and
    orientation, and mapping to our 1/16 grid.
  - Dressing assets and character choices.
- **B4.4 Multiplayer.** Packs are client-local. What do other players see, and is the server
  unaffected?
- **B4.5 Legal and data rules.** Packs are user content, we ship none, and the generated base stays
  local.
- **B4.6 Tooling for pack makers.** A local template export (keys plus the generated base), and its
  documentation.

### B5. Characters: three styles

- **B5.1 Inputs.** Sprite sheets (frames, facings, walk frames), from the export or the pack. Gen 1
  is 16×16; GBA people are 16×32.
- **B5.2 Thick cards.** Extrusion depth, frame choice by camera angle (`FacingFrames` today), the
  renderer and its cost.
- **B5.3 Carved voxel figures.**
  - A visual hull from the front, back and side silhouettes.
  - Colouring rules for each face.
  - The walk animation: each frame carved.
  - Quality checks and a per-sprite fallback (thin parts; objects such as boulders and Snorlax).
  - Pseudo code and cost.
- **B5.4 Rigged figures.**
  - Splitting head, body, arms and legs from the sprite's rows.
  - Building Minecraft entity models at runtime.
  - Joint animation, the heuristics involved, and cost.
- **B5.5 Settings and consistency.**
  - A per-player setting, and keeping the style consistent with the terrain.
  - Packs overriding characters (`.vox` models per sprite id).
  - Dramaless Shape's argument for flat cards: how should we weigh it?
- **B5.6 How players look.** Your own look (Minecraft skin or a generated trainer) and how other
  players appear.

### B6. Region awareness at runtime

- **B6.1 The region registry.** Format, sync from server to client, the lookup API and caching
  (with A8).
- **B6.2 Client assets per region.**
  - One pack with namespaces, or several packs?
  - Shape tables and character models.
  - When they load (on approach, or always) and memory.
- **B6.3 Several regions in view.** Rendering when more than one region is visible from a distance,
  with vanilla land between.
- **B6.4 The texture flow per region.** The M9 flow (`world.slots`, `texture.status`, the pack's
  `slotMapHash`) made per region.
- **B6.5 Namespacing now.** What must be namespaced today so a second region needs no redesign?

### B7. One architecture for many games (check the constraints)

- **B7.1 A front-end interface** per game family, in pseudo code.
  - Validate it against what Gen1Recomp exposes for Gen 2 and Gen 3 maps
    (`refs/gen1recomp/src/world/gen2/`, `src/core/game3/`).
  - Survey only.
- **B7.2 Generality hooks.** List the Gen-1-specific code paths in the builder, export and client art
  that need generality hooks, and what each hook looks like.

### B8. Testing

- **B8.1 Test design for Part B:**
  - JUnit for pure parts: shape generation, keys, manifests, carving, greedy meshing;
  - golden data from the synthetic bundle (`tools/fake_gen1/synth.py`);
  - GameTests for server parts;
  - client logic in pure classes, since there are no client GameTests on 1.21.1;
  - play-test checklist items.
- **B8.2 What the e2e headless build can check** from counts alone.

---

## 7. Cross-checks before finishing

Check every recommendation against each of these, and include the table in the report:

| Check | Pass when |
|---|---|
| Hard rules | No ROM-derived data ships or travels. Art is generated on each client. Nothing is copied from references without a licence. No pin changes without a user decision |
| Story safety | No path into Kanto skips a story gate. The hidden game and Minecraft can't diverge without a recorded correction |
| Multiplayer | Story state and story art stay per player, relay authority holds, and players inside and outside coexist |
| Performance | Chunk generation, chunk meshing, fps and memory each have a budget and a measurement plan |
| Version risk | Every mixin and internal API touched is named, with what a Minecraft update would break |
| Consistency | No two answers contradict each other. Parts A and B share one region model |
| Later goals | Nothing blocks Johto as a second region, per-region rules, or the bag-requirement idea |

---

## 8. Deliverables

Write **`docs/research/VOXEL_WORLD_REPORT.md`** (the only required file). Optional companions, in the
same folder:
- `scene_description.draft.json`;
- `region_record.draft.json`;
- `texture_pack_manifest.draft.json`.

The report contains, in order:

1. **Summary.** One page: the recommended architecture for A and B, and the five biggest risks.
2. **Facts table** from A1 and B1, with citations, and corrections to §4.
3. **Answers** to every question ID, in §3's format.
4. **Designs:**
   - the world generation design;
   - the footprint and edge design;
   - the border state machine;
   - region-scoped rules;
   - the region record;
   - the block approach;
   - the shape generator pipeline;
   - the texture-pack format;
   - character styles.

   Each with data formats and pseudo code.
5. **Decisions for the user,** each with options and a recommendation. At least:
   - the generator architecture;
   - Kanto's placement and its surroundings;
   - the entry and exit policy;
   - game mode outside;
   - time and weather;
   - species outside Kanto;
   - LOD or extra renderers (pins);
   - the block approach;
   - Dramaless tables (licence);
   - the default character style;
   - how far texture packs go.
6. **A milestone plan** in the style of PLAN.md §6.
   - Ordered milestones with tasks per track, acceptance tests (`tools/check` and play-test items)
     and estimated size.
   - The first milestone should be the smallest step that shows Kanto inside vanilla terrain with
     one working gate.
7. **A risk register:** each risk with likelihood, impact, detection and fallback.
8. **Experiments designed but not run:** method, metrics, thresholds.
9. **Blocked and open questions.**
10. **Citations appendix.**

**Quality bar.** A coding agent should be able to start the first milestone from the report alone,
without rediscovering the codebase.

---

## 9. Seed sketches

These are illustrative, to be checked and replaced. They show the depth expected in answers; every
signature needs checking against 1.21.1 and Fabric API 0.116.17.

**A footprint mask as a density function** (A2.1, option b):

```java
// Registered as a DensityFunction type, e.g. "kantobridge:footprint_mask"; wraps vanilla's final density.
record FootprintMask(DensityFunction vanilla, FootprintIndex fp) implements DensityFunction.SimpleFunction {
    public double compute(FunctionContext c) {
        double d = fp.signedDistance(c.blockX(), c.blockZ());   // blocks; < 0 inside the reserved area
        if (d < 0) return AIR;                                  // nothing generates inside
        if (d < RAMP) return lerp(target(c), vanilla.compute(c), d / RAMP);  // ease toward Kanto's level
        return vanilla.compute(c);
    }
}
```

**Stages skipped by a wrapping generator** (A2.1, option a):

```java
class KantoNoiseGenerator extends NoiseBasedChunkGenerator {
    @Override public void applyBiomeDecoration(WorldGenLevel level, ChunkAccess chunk, StructureManager s) {
        if (!footprint.intersects(chunk.getPos(), FEATURE_MARGIN)) super.applyBiomeDecoration(level, chunk, s);
        // else: decorate only the part outside the footprint, or nothing (A2.3 decides)
    }
    // likewise applyCarvers (carver reach), createStructures (start and piece bounding boxes), buildSurface
}
```

**The border state machine** (A4.2, A4.3):

```
states: OUTSIDE, FOLLOWING, LEAVING, ENTERING
FOLLOWING -> LEAVING   when the next cell is outside the footprint and the edge is a gate
                       (stop sending move.step; unlock; region.leave to the server)
OUTSIDE   -> ENTERING  when the feet cross into footprint cell C
ENTERING  -> FOLLOWING if C == hidden cell; else per policy: refuse (collide),
                       or a hidden-only warp to C when reachable(C, storyState)
```

**A flexible block's model** (B2.1):

```java
@Override public void emitBlockQuads(BlockAndTintGetter view, BlockState state, BlockPos pos,
        Supplier<RandomSource> random, RenderContext ctx) {
    ShapeRef ref = ClientRegions.shapeAt(pos);                  // thread-safe; from the region's data
    Mesh mesh = ShapeMeshes.get(ref, ClientStory.closed(pos));  // cached, greedy-meshed, per player
    mesh.outputTo(ctx.getEmitter());
}
```

**A region record** (A8.1):

```json
{"id": "kanto@blue", "game": "blue", "exportHash": "…", "slotMapHash": "…",
 "origin": [0, 0, 0], "footprint": "kantobridge/regions/kanto@blue/footprint.bin",
 "gates": [{"cell": "ROUTE_22:0:5", "dir": "left"}], "rules": "kanto", "namespace": "kanto"}
```

**A texture-pack manifest** (B4.2):

```json
{"kantobridge_pack": 1, "name": "HD Pallet", "targets": {"game": "blue"},
 "textures": {"blue/OVERWORLD/44": "textures/hd/roof.png"},
 "models": {"sprite:OAK": "models/oak.vox"}, "rules": {"class:tree": {"style": "canopy"}}}
```

**Carving a voxel figure** (B5.3):

```
front, back, side = alpha masks of the 16x16 frames (right = side mirrored)
voxel(x, y, z) = front(x, y) and side(z, y)         // the visual hull in a 16x16x16 grid
paint each visible face from the view it points to (±z: front/back pixels, ±x: side pixels,
top/bottom: the nearest edge pixel); greedy-mesh into boxes; fall back to a thick card when the
hull is thinner than 2 voxels across more than N rows
```
