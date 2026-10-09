# Plan: Kanto in a Minecraft world, with its interiors in their own dimension

- **Status:** proposed on 2026-10-09 from your brief (§2.1); not decided. You were away, so every choice
  the brief left open has a default in §2.2. Change any of them and the plan follows; nothing here has been
  built.
- **What it delivers:**
  - New worlds keep every interior in **one custom dimension**, `kantobridge:interiors`: houses, gyms, the
    Pokémon Centers, caves, dungeons and gates. Only two dimensions are ever in use: the overworld for Kanto's
    outdoor maps, and the interiors.
  - The overworld no longer has to be flat. A new world type, **"In a Minecraft world"**, places Kanto's
    outdoor maps as a **protected, contained area** inside ordinary vanilla terrain.
  - The land under and around Kanto is **pre-generated** before the build, and a **buffer zone** blends it
    down to Kanto's level. A fully generated chunk can't be changed by later generation, so vanilla terrain,
    trees, lakes and structures can never overlap Kanto afterwards.
  - Today's flat worlds keep working untouched.
- **Why:** your brief of 2026-10-09. The flat world exists because interiors are stacked under Kanto in up to
  14 layers, down to Y −245. That needs an empty overworld and an override of its height range
  (`data/minecraft/dimension_type/overworld.json`: `min_y` −256, height 576).
- **Sources:**
  - [VOXEL_WORLD_REPORT.md](../research/VOXEL_WORLD_REPORT.md) Part A (2026-10-04). Its facts F1–F15,
    A1.2–A1.5, A2.2–A2.3, A2.7, A6.1–A6.6 and its report fields are reused here; its gated generator, gates
    and region-scoped rules are not (§9).
  - A read of today's code for this plan (file:line references below).
  - Minecraft 1.21.1's own server and client jars, decompiled with Mojang's mappings in a scratch folder
    (never committed), plus Fabric API's 1.21.1 branch, Chunky's source and Cobblemon 1.8.1's source (all
    read, nothing copied).
  - Every claim that depends on running the game is a spike (§6).
- **Size:** spikes S1–S9 on the RunPod pod (about three and a half pod hours, under $2), then five milestones W1–W5.

---

## 0. How to execute this plan

- **CLAUDE.md's rules apply unchanged.** In particular:
  - nothing ROM-derived is opened, logged or committed;
  - `local/` and `refs/` stay out of git;
  - pins don't change without your OK;
  - ambiguity or a failed step stops the run with a question.
- **Order:**
  1. The spikes first (§6). They settle the assumptions the milestones rest on, and two of them can
     change a default or add a step (S7 can change WD1; S3 can add §5.6's lever).
  2. Then W1–W5 in order. One commit each, `W<n>: <title>`, with the harness's attribution trailer.
  3. Each milestone ends with `tools/check` green, the new scenario suite green on the pod (§7.0),
     PROGRESS.md's new "World plan" section updated, and new PLAYTEST.md items.
- **Where things run:**
  - Code is edited on the server (`/home/claude/kantobridge`).
  - Builds and tests run on the RunPod pod `kb-dev` through `/home/claude/kb-runpod/kb-pod`
    (`host`, `prep`, `sync`, `check`, `run`).
  - The pod is stopped after every session. Each spike states its pod time.
- **Data rules for the new kinds of output:**
  - Agents only use the synthetic bundle (`tools/fake_gen1`) and a new Kanto-sized synthetic layout
    (§6, S6).
  - Real Kanto is built only by you, or on the test install under PLAN §0.3.
  - Site coordinates, the reserved area and the build report are layout-derived. They stay in the world
    folder, like `world_index.json` does today. Logs and test output carry counts, timings, hashes and
    vanilla ids (structure and block names) only.
- **The Minecraft EULA:** the spikes and scenario runs start a dedicated dev server on the pod, which needs
  `eula.txt` in its run folder. Per PLAN §0.6 an agent never accepts the EULA. You accept it for the pod once,
  with the one command S1 gives you.
- **Existing worlds:** never converted, never touched. Every new code path sits behind one of two tests:
  - "this world has the interiors level" (format 2, §3.1);
  - "this world has a region record" (the vanilla placement, §4.3).
- **DESIGN.md is yours and stays as written.** Its section "Interiors underground" chose interiors under
  their own building so that "the interior is already loaded when you reach the door". This plan follows your
  2026-10-09 brief instead for new worlds. §8 R1 is the cost of that, and S7 measures it. Updating DESIGN.md
  is your call.

## 1. Scope

| Area | What is built | Milestone |
|---|---|---|
| Interiors dimension | `kantobridge:interiors` (void, its own dimension type); every interior map built there at today's coordinates; WorldIndex v2 names each map's dimension | W1 |
| Warps | Door warps between the overworld and the interiors become cross-dimension teleports; the client re-seats puppets, lookups and the fade after a level change | W1 |
| Two new presets | `kantobridge:kanto_flat` (Classic: a flat void overworld plus the interiors) and `kantobridge:kanto_vanilla` (vanilla overworld, the interiors, the Nether and the End). The old `kantobridge:kanto` stays for legacy worlds | W1, W2 |
| Region record | `<world>/kantobridge/region.json`: site, reserved area, buffer, veto, hashes; bound at every server start | W2 |
| Site search | A deterministic search of the seed's terrain for a site close to Kanto's level, before any chunk generates | W2 |
| Structure veto | One mixin: structure starts whose box would reach the reserved area are dropped whole, for the life of the world | W2 |
| Pre-generation | Every chunk of the reserved area (+1 chunk) generated to FULL with a ticket window and a progress line | W2 |
| Site preparation | Core cleared above Kanto's ground, a foundation slab under it, the buffer ramped to Kanto's level, fluids removed, biomes painted | W3 |
| Building | Surface maps into the prepared overworld, interiors into their dimension, world spawn in Pallet Town | W3 |
| Rules | Today's world-wide rules kept (Kanto is contained); Cobblemon spawns limited to Kanto biomes | W3 |
| Creation | "Create Kanto world" offers Classic / In a Minecraft world; headless `--preset` | W4 |
| Hardening | Rebuild rules, restart persistence, the placer's ticket window, the legacy height override isolated | W5 |

**Out of scope** (each a later plan, §9):
- leaving Kanto: gates, rules by zone, survival play and Cobblemon outside;
- a hull-shaped footprint;
- a generator that reserves the area itself;
- importing the player side of an old world;
- more regions.

## 2. Decisions

### 2.1 Yours (2026-10-09)

| # | Decision |
|---|---|
| U1 | Move all interior spaces into a single custom dimension, so only two dimensions are in use at once and the world no longer has to be flat |
| U2 | Integrate the ROM-derived world space as a protected area in a Minecraft world with vanilla world generation |
| U3 | Protect it from overlapping vanilla generation with a buffer zone on pre-generated land |
| U4 | Kanto stays contained for now; opening it is a later change |
| U5 | Research from the existing code, with short tests on the RunPod system; the agent makes the best choices, to be changed later |

### 2.2 Plan defaults (the agent's choices; change any)

| # | Default | Alternatives | Why this one |
|---|---|---|---|
| WD1 | **Interiors keep today's coordinates** in the interiors dimension: same X/Z under their door, same layers (Y 42, 20, −2 … −244) | Re-pack them on one layer (no deep dimension type needed); **or keep them in the overworld below the bedrock** (no dimension change at doors, §8 R1) | The planner, the packer, the shape map and the goldens stay as they are; only the dimension changes. Positions stay unique across both dimensions, so position-keyed data needs no new key |
| WD2 | **Format 2 for new worlds only.** The `kanto` preset (format 1) stays for legacy worlds and as a reference; the two new presets are format 2 | Change `kanto` itself | Legacy worlds read their dimensions from level.dat, so nothing old can change by accident |
| WD3 | **Both new presets keep the Nether and the End,** as `kanto.json` does today. They sit idle: no player, no chunks | Leave them out | Opening Kanto later needs a normal world. An idle level costs close to nothing (S9 confirms) |
| WD4 | **Both new presets' overworlds use `kantobridge:overworld`,** a dimension type with vanilla's own values (−64…319) | Keep `minecraft:overworld` with the global −256 override | New worlds don't inherit the legacy override, whose side effects are listed in research A1.2. The override stays only because legacy worlds need it (W5). Like the fourth stem, it needs W1's stability mixin (§3.2) |
| WD5 | **The reserved core is today's surface box,** a rectangle including the 12-block ring (704 × 744 blocks for real Kanto) | A hull hugging the routes (research A3.1) | The build inside stays identical, goldens included. A hull is a looks upgrade for when Kanto opens |
| WD6 | **Buffer 64 blocks** around the core. Pre-generation covers the core, the buffer and one more chunk | 32 (faster, steeper), 96 (smoother) | 64 gives a gentle ramp over most terrain. S3 sizes the cost |
| WD7 | **Site: automatic search** within 3,072 blocks of the origin. A **fixed origin** (Pallet's cell at 0,0 as today) for tests and as an option | Always fixed | A site near Kanto's level makes the buffer small and natural |
| WD8 | **Structure veto:** starts whose box, grown by 12 blocks for terrain adaptation, meets the reserved area. Strongholds are exempt, and so is anything wholly below Y 43 | Veto everything nearby | Nothing is cut in half, and the world keeps its strongholds |
| WD9 | **Foundation:** a new unbreakable block, `kantobridge:foundation`, fills the core from Y 55 to 62 wherever generation left air or fluid | Vanilla stone | Seals cave mouths under Kanto, and stays right when Kanto opens (research A2.4) |
| WD10 | **Buffer look:** each buffer column eases from Kanto's ground (Y 63) at the core to its natural height at the outer edge (a smoothstep). Its own top layers are copied onto the new surface; fluids above Y 62 are removed; the sea stays | A flat apron and a cliff; belts by edge kind (research A2.8) | Looks natural on most land and never sends water into Kanto |
| WD11 | **Rules: today's world-wide rules unchanged** in vanilla worlds (Adventure, Peaceful, no vanilla mobs, frozen noon, no weather) | Survival outside | Kanto is contained, so the vanilla land is scenery. Rules by zone come with opening it |
| WD12 | **Cobblemon spawns only in Kanto biomes** (one new spawn rule) | Leave it | Players stand near the ring, and vanilla biomes beyond it would show wild Pokémon over the wall |
| WD13 | **Kanto biomes over whole core columns** (not only Y 56…88 as today) | Today's boxes | Rain, music and spawning are decided by Kanto's biome at every height above the core |
| WD14 | **Accept vanilla's loading screen at a dimension change, behind our fade.** Polish only if S7 misses its thresholds | Hide it from the start | Measure before building workarounds |
| WD15 | **The creation flow defaults to Classic** until your W3 play-test; then "In a Minecraft world" | Vanilla from W4 | You see the new world before it becomes the default |

## 3. Architecture

### 3.1 World formats

| Format | Overworld | Interiors | How the code tells |
|---|---|---|---|
| 1 (legacy) | flat void, height override (−256…319) | in the overworld, under Kanto, layers 1–14 | no `kantobridge:interiors` level on the server |
| 2 Classic | flat void (`kanto_flat`) | `kantobridge:interiors`, today's coordinates | the level exists; no region record |
| 2 In a Minecraft world | vanilla noise (`kanto_vanilla`), Kanto in a reserved area | `kantobridge:interiors`, today's coordinates | the level exists, and `kantobridge/region.json` too |

- **Telling formats apart:** the builder and every runtime service ask one class: `KbLevels.of(server)`.
  It answers the format, `levelOf(map)` (the level a map is built in) and `interiors()` (null in format 1).
- **GameTests:** the GameTest server has no stem for our dimension (research A6.4), so it stays format 1.
  Today's GameTests and goldens therefore stay valid. Format 2 is tested by JUnit and the scenario runner (§7.0).

### 3.2 Two dimensions in use

```
overworld (format 2)                         kantobridge:interiors (void, fixed noon, Kanto biomes)
Y 64…94  Kanto's blocks: ring, roofs, shapes   Y 45 ┌─┐ ceiling    layer 1, feet 42
Y 63     Kanto's ground                        Y 41 └─┘ floor      (Red's house, under its door's X/Z)
Y 55…62  foundation (vanilla worlds)           Y 23 ┌─┐            layer 2, feet 20
         vanilla underground (vanilla worlds)       └─┘ …          down to layer 14, feet −244
Y −64    bedrock (vanilla worlds)
```

- **What "in use" means:**
  - The overworld holds the spawn chunks and every outdoor player.
  - The interiors level loads chunks only while someone is inside.
  - The Nether and the End exist but stay idle (WD3).
- **The interiors dimension type** (`data/kantobridge/dimension_type/interiors.json`) keeps today's vertical
  budget (WD1):
  - `min_y` −256, `height` 576, `logical_height` 576;
  - `has_skylight` true and `fixed_time` 6000, so the open-air interiors (Viridian Forest, the Safari Zone)
    get daylight. Enclosed rooms are dark under their ceilings and lit by their light blocks, as today;
  - `effects` `minecraft:overworld` (a normal sky over the open-air maps);
  - `natural` false with `bed_works` true, so a vanilla bed placed there is inert:
    - sleeping needs `natural`;
    - `bed_works` false would make any bed explode on use.
  - `respawn_anchor_works`, `has_raids`, `piglin_safe`, `ultrawarm` false;
  - `ambient_light` 0, `monster_spawn_light_level` 0, `monster_spawn_block_light_limit` 0,
    `infiniburn` `#minecraft:infiniburn_overworld`, `coordinate_scale` 1.
  - **Shared with the overworld:** every non-overworld level reads the overworld's time, weather and game
    rules (`DerivedLevelData`). `fixed_time` pins the interiors' clock, and Kanto's biomes have no rain.
- **Its generator** is the flat void used by `kanto.json` today, with biome `kantobridge:kanto_interior` and
  an explicit `"structure_overrides": []`. Without that field, `FlatLevelSource` would use every structure
  set.
  - Interior chunks generate instantly, as void chunks do now, so the placer's "owned volumes" contract
    (research A1.4) holds there unchanged.
- **Only the presets list it. There is no `data/kantobridge/dimension/` file in the jar.**
  `WorldDimensions#bake` merges every data-pack dimension into every world it loads, legacy ones included.
  Stems listed by a preset are saved into the new world's level.dat and nowhere else.
- **Stability:** `bake` marks a world `Lifecycle.experimental()` unless it has exactly vanilla's three
  dimensions of vanilla kinds.
  - Single-player would then show the "experimental settings, make a backup?" screen at every load
    (`WorldOpenFlows`).
  - Today's flat Kanto passes: three stems, vanilla types.
  - A fourth stem fails, and so does WD4's own overworld type.
  - W1 adds one small mixin into that stability check, so that our two stems, with exactly our types and
    generators, count as stable. S1 checks the world's lifecycle; S7 checks the screen.
- **Open-air interiors:** in format 2, everything above layer 1 is open sky, so `placeOpenSky` can only find
  more spots. Its result is kept as it is, so placements stay identical.

### 3.3 Coordinates

- Scale and placement formulas are unchanged: cell = 2×2 blocks, ground Y 63, feet Y 64, layer *k* feet
  64 − 22k (`Scale.java:15-42`). Map origins stay absolute block coordinates.
- A map's dimension follows from its kind: `SURFACE` → overworld, `INTERIOR` → interiors. WorldIndex v2
  writes it per map (§4.2), so readers never infer it.
- **The origin** (Pallet Town's cell 0,0):
  - Classic and legacy: (0, 0), as today (`BuildOptions.java:35`).
  - Vanilla: the site from the region record. The planner already takes `originX/originZ`.

### 3.4 The reserved area (vanilla worlds)

```
                 ← B = 64 →                                         ← B = 64 →
vanilla terrain │ buffer (ramp) │  core: Kanto's surface box (704 × 744 for real Kanto)  │ buffer │ vanilla
                │  Y natural→63 │  ring (12 blocks of 3-high border art) + maps + filler   │        │
                └───────────────┴──────────────────────────────────────────────────────────┴────────┘
pre-generated:  [ +1 chunk ][ every chunk of buffer and core, FULL before the build ][ +1 chunk ]
structure veto: no start whose box (+12) meets buffer ∪ core, ever (strongholds and deep starts exempt)
```

- **Core:** the surface box exactly as `WorldPlan` computes it today (bounding box of the surface maps,
  inflated by `SURFACE_RING` 12; Kanto's blocks fill Y 63…94: `WorldPlan.java:27, 34-35, 295`), **from the
  whole export**, never from the build's map filter. That way a Pallet-only build reserves all of Kanto
  (research A6.5 rule 1).
- **Buffer:** a ring of width B around the core. Vanilla's own land, reshaped once by site preparation.
- **Pre-generated area:** the core and buffer chunks, plus one chunk all round. With B = 64, real Kanto
  reserves 832 × 872 blocks: about 2,970 chunks, or about 3,100 with the margin.
- **Why this protects Kanto** (research A2.3, confirmed in 1.21.1's code):
  - In `ChunkPyramid`, the only step allowed to write outside its own chunk is `features`, and only one
    chunk away. `noise`, `surface` and `carvers` write their own chunk; every other step writes none.
    `WorldGenRegion#ensureCanWrite` drops anything farther.
  - A chunk reaches `light` only after its 8 neighbours have finished `features`.
  - Its structure references are recorded once, after every start within 8 chunks exists. Pieces are placed
    only during its own `features` step.
  - So once a chunk is FULL, no later generation can change its blocks. One chunk of margin would not even
    be needed; we keep it so that the ring around the buffer is final too.
  - The veto is still needed for structures that start outside. Without it, their pieces would stop at the
    buffer's edge and leave half a village there.
  - What can still reach a FULL chunk is run-time behaviour, not generation: light, mobs, fluids, fire,
    scheduled ticks, and generation's own pending post-processing (§5.8).
- **Containment:** today's mechanisms are enough for a contained Kanto. Nothing new is needed:
  - the ring is 3-high border art all round (`HeightRules.java:21`);
  - jump strength is 0 (`Rules.java:104-107`);
  - a player off every map is teleported back to the hidden cell after 500 ms (research A1.6).

  Outside the ring there is now ground (the buffer) instead of void.
- **Protection:**
  - Players are in Adventure.
  - Every tile, shape and story block is unbreakable and blast-proof:
    `.strength(-1.0F, 3_600_000.0F)` (`KbBlocks.java:141-148, 164-175`).
  - The rules switch off mob griefing, fire spread and mob spawning world-wide.
  - Site preparation (§5.8) leaves no fluid that could reach the core.
  - Adventure's known gaps don't apply here:
    - the gaps are paintings, item frames and leash knots that can still be broken, farmland trampling,
      blocks with their own use action (note blocks, beds), and projectiles;
    - Kanto has none of those blocks or entities, and every plain block click already fails on the client
      while following (`ActRuntime.java:178-196`).

  So for a contained Kanto, nothing in the vanilla land around can change it. A server-side guard on
  `Level#setBlock` (research A5.7) is for when Kanto opens.

### 3.5 Lifecycle

```
SERVER_STARTING  RegionBootstrap
                   region.json exists      → read it, bind the veto to the overworld generator
                   else a build is pending  → lay out the surface maps from the bundle (the whole export;
                   (vanilla preset)            Layout and the surface box only, not the full plan), search the
                                               site, write region.json, bind the veto. The pending build is
                                               matched by world folder name (research A2.7 point 5)
createLevels     vanilla spawn search (11×11 chunks around its pick; the veto is already bound)
SERVER_STARTED   HeadlessBuild → BuildTask
                   PLANNING    today's planner (+ dimension per map, + origin from the record)
                   GENERATING  pre-generation of the reserved area, ticket window, progress (vanilla only)
                   PREPARING   site preparation chunk by chunk (vanilla only)
                   PLACING     today's placer, once per level: overworld volumes, then interiors volumes
                   FINISHING   WorldIndex v2, slot map, pack, report; region.json gets its build hashes
later starts     RegionBootstrap binds the veto again from region.json: chunks generated near Kanto
                 for the rest of the world's life never start a structure that reaches it
```

## 4. Formats

### 4.1 Presets and dimension data

- `data/kantobridge/worldgen/world_preset/kanto_flat.json`: `kanto.json`'s three stems, plus:

  ```json
  "kantobridge:interiors": {
    "type": "kantobridge:interiors",
    "generator": { "type": "minecraft:flat", "settings": {
      "biome": "kantobridge:kanto_interior", "layers": [], "lakes": false,
      "features": false, "structure_overrides": [] } }
  }
  ```

  Its overworld uses `kantobridge:overworld` (WD4); a flat void doesn't need the depth.
- `data/kantobridge/worldgen/world_preset/kanto_vanilla.json`: the same, but the overworld is vanilla's
  default stem with our type:

  ```json
  "minecraft:overworld": { "type": "kantobridge:overworld",
    "generator": { "type": "minecraft:noise", "settings": "minecraft:overworld",
      "biome_source": { "type": "minecraft:multi_noise", "preset": "minecraft:overworld" } } }
  ```
- `data/kantobridge/dimension_type/interiors.json` (§3.2) and `overworld.json` (vanilla's own values:
  `min_y` −64, `height` 384, `logical_height` 384, and the rest as vanilla).
- **Lang keys and tags:**
  - lang keys: `generator.kantobridge.kanto_flat`, `generator.kantobridge.kanto_vanilla`.
  - Neither preset joins `#minecraft:normal`, because only the flow can supply the export (research A2.7).
    `kanto` keeps its tag.

### 4.2 WorldIndex v2

- `protocol/world/world_index.schema.json`:
  - `version` becomes `1 | 2`;
  - each map gains an optional `dimension`: `"minecraft:overworld"` or `"kantobridge:interiors"`. An absent
    field means the overworld, so every v1 index reads as before.
- A format-2 build writes `version 2` and the field on every map; a format-1 build writes v1 byte-for-byte as
  today (the GameTest golden stays).
- The Java parser rejects unknown fields (`WorldIndex.java:31-35, 251`), so it learns `dimension` in the same
  change.
- A client older than W1 can't read a v2 index. That is fine, because the relay's version handshake already
  makes every player of a world run the same release.
- `world.index` carries the index as today. No message schema changes, and gen1-bridge never sees dimensions:
  **W1 changes no Lua and no protocol message**.
- PROTOCOL.md's world-index section and the catalogs are updated with it.

### 4.3 The region record

`<world>/kantobridge/region.json`, canonical JSON, written once before the first chunk generates:

```json
{
  "version": 1,
  "region": "kanto",
  "seed": -4172144997902289642,
  "site":       { "mode": "auto", "origin": [1536, -768], "cost": 3.4, "candidates": 1849 },
  "core":       { "min": [1492, -888], "max": [2195, -145] },
  "buffer":     64,
  "pregen":     { "minChunk": [88, -61], "maxChunk": [142, -5] },
  "foundation": { "block": "kantobridge:foundation", "fromY": 55, "toY": 62 },
  "veto":       { "grow": 12, "minTopY": 43, "exempt": ["minecraft:stronghold"] },
  "exportHash": "…",
  "footprintHash": "…",
  "build":      { "prepared": true, "prepHash": "…" }
}
```

- Coordinates are blocks (inclusive), or chunks where named. The values above are made up.
- **The core and the buffer never change for the life of the world** (§5.11).
- `seed` is the world's seed, kept so that a copied `region.json` can't bind to another world.
- Layout-derived, like `world_index.json`: it stays in the world folder and is never committed or sent.
  The client doesn't need it in this plan.

### 4.4 Build report additions

Counts, times, hashes and vanilla ids only, under the names of research §4.11 where they overlap:

```json
"format": 2,
"dims":   { "overworld": { "chunks": 0, "blocks": 0 }, "interiors": { "chunks": 0, "blocks": 0 } },
"site":   { "mode": "auto", "candidates": 0, "cost": 0.0, "search_ms": 0 },
"pregen": { "chunks": 0, "ms": 0, "chunks_per_s": 0.0, "max_tick_ms": 0, "window": 64 },
"prep":   { "chunks": 0, "core_cleared": 0, "foundation_filled": 0, "buffer_cut": 0,
            "buffer_filled": 0, "fluids_removed": 0, "ms": 0 },
"veto":   { "starts": 0, "by_structure": { "minecraft:village_plains": 0 } },
"checks": { "fluid_in_core": 0, "foreign_in_core": 0, "pieces_in_reserved": 0 }
```

The headless gates (W4) fail the build when any of the three `checks` is not 0.

## 5. Component designs

### 5.1 KbLevels: one place that knows the format

- **Where:** a new `server/KbLevels` (common).
  - `of(MinecraftServer)` answers the format, `overworld()`, `interiors()` (nullable) and
    `levelOf(MapPlacement)` / `levelOf(dimension id)`.
  - The format is decided once, at `SERVER_STARTING`, from the level registry, and cached per server.
- **Who calls it.** Today nothing names a dimension:
  - `server.overworld()` appears in `HeadlessBuild:96, 160`, `BuildHold:77`, `HabitatService:259, 309` and
    `Rules:77`;
  - `Level.OVERWORLD` appears only in `HabitatService:106`;
  - there is no `ResourceKey<Level>` and no `getLevel(key)` anywhere.

  W1 routes the builder, `WarpService` and `HabitatService` through `KbLevels`. `BuildHold`'s release
  already teleports to the overworld spawn from any level (`BuildHold.java:72-96`). `Rules`' noon and clear
  weather act on the overworld (`Rules.java:77-79`), which is right: the interiors have `fixed_time` and
  biomes without rain. `SaveService:131` already saves every level. The full list is in §10.
- **The client's mirror image:** `ClientWorldIndex` lookups take the current level's dimension:
  `toCell(dimension, pos)`.

### 5.2 Building into two levels

- **`WorldPlan`:** `volumes()`, `chunks()`, `renderChunk()`, `biomeRegions()`, `habitatsIn()` and
  `volumesOf()` (`WorldPlan.java:257-347, 355-416`) work per dimension.
  - Interior volumes go to the interiors level in format 2, and to the overworld in format 1.
  - With WD1 no chunk ever holds codes from both dimensions, so `ChunkCodes` stays one per (dimension,
    chunk).
- **`BuildTask`:**
  - holds one `ServerLevel` today (`BuildTask.java:86`) and runs one `BlockPlacer` (`:248`). It will run the
    placer once per level: overworld first, then interiors. Each pass gets its own level, chunk list,
    previous volumes and counters, and progress is the sum of both;
  - the world spawn stays in the overworld (`:250-253`);
  - "previous volumes" (`:177-184, 194-212`) are read per dimension from the old index (a v1 index means
    everything is in the overworld);
  - `KantoWorldBuilder.start` (`KantoWorldBuilder.java:32`) and `HeadlessBuild.java:160` pass the server
    instead of `server.overworld()`.
- **The layer budget comes from the interiors level.** `BuildTask.java:112` feeds `Scale.maxLayer` the
  build level's `getMinBuildHeight()`. In a vanilla overworld (−64) that would allow 5 layers instead of 14,
  so format 2 reads the interiors level's minimum (−256 → 14 layers, as today).
- **`BiomeWriter`** (`BiomeWriter.java:36-60`: `fillBiomesFromNoise`, then `resendBiomesForChunks`) runs per
  level. WD13 widens the core's biome box to the whole column; today's surface box is Y 56–88
  (`WorldPlan.java:331-347`).
- **Habitat blocks:**
  - `HabitatPlanner` (`HabitatPlanner.java:87-107`) tags each block with its map's dimension. Cave pools now
    live in the interiors level.
  - `HabitatService` re-checks overworld chunks only and keys them by chunk alone (`HabitatService.java:105-109,
    254-318, 354-363`). It moves to (dimension, chunk) keys over every level that has pools.
  - The spawn rule `walking_level_only.json:8` (`math.mod(y − 63, 22) == 0`) still holds, since the layers keep
    their Y.

### 5.3 Warps across dimensions (server)

- **`WarpService`:** `teleport` (`WarpService.java:98-119`) ends today in
  `player.teleportTo(player.serverLevel(), x, y, z, Set.of(), yaw, xRot)`, commented "Same level: interiors
  are layers of the overworld, so this is a plain position packet, no loading screen" (`:113-114`).
  - It passes the target map's level from `KbLevels` instead. In 1.21.1 that overload:
    - adds a `POST_TELEPORT` ticket around the target;
    - in the same level, sends a position packet;
    - otherwise calls `changeDimension(new DimensionTransition(…))`. That sends the respawn packet, removes
      the player from the old level, adds them to the new one, and resends abilities, level info, inventory
      and effects.
  - The comment changes with it.
  - (1.21.2 renamed these to `TeleportTransition`. No bump is planned.)
  - Refusals (`HELD`, `NO_INDEX`, `UNKNOWN_MAP`, `BAD_CELL`) are unchanged, plus `NO_LEVEL` when a
    format-2 index meets a server without the interiors level.
- **Cobblemon recalls a player's sent-out Pokémon on a change of dimension** (noted at
  `mixin/PokemonEntityMixin.java:13-14`). Warps are already refused while a battle holds the player, so only
  a Pokémon sent out by hand is affected, and it returns to the ball.
- **Leaving an interior:** keep the overworld chunks around the door loaded for 30 seconds with a short
  ticket. Re-entering the overworld then doesn't wait on disk. The client still has to receive and mesh
  them again (§8 R1).
- **Joining, respawning and builds:**
  - A player who logged out inside an interior rejoins in the interiors level. The join's
    `rejoin`/`settle` then warps them to the hidden cell, wherever that is.
  - After a death, the player respawns at the world spawn (Pallet, overworld, `doImmediateRespawn`) and is
    brought back to the hidden cell by today's drift rule (research A1.6).
  - `BuildHold`'s release teleports to the world spawn in the overworld, from whichever level the player
    was held in.

### 5.4 The client across a dimension change

- **What the change does** (1.21.1, `ClientPacketListener#handleRespawn`):
  - vanilla builds a new `ClientLevel` and a new `LocalPlayer` with the same id;
  - it closes open containers and stops Minecraft's music (ours is silent anyway);
  - it shows `ReceivingLevelScreen` ("Loading terrain") until `LEVEL_CHUNKS_LOAD_START` has arrived and the
    player's chunk section is compiled, or 30 s at most;
  - every chunk then streams in again.

  Fabric API 0.116.17 has `ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE` for re-seating. (`FabricDimensions`
  was removed for 1.21; vanilla's teleport is the API now.) Everything we keep in or about the level has to be
  re-seated:
  - **Puppets:**
    - A new `ClientLevel` already makes `PuppetRuntime` add all puppets again (`PuppetRuntime.java:164-167`).
    - What's missing: it spawns puppets with no map or level check (`:218-238`). It will only spawn those of
      maps in the current level.
  - **The fade's readiness check:**
    - `MoveRuntime#destinationReady` (`MoveRuntime.java:199-213`) waits until the player is within 1 block
      (X/Z) and 3 (Y) of the target and the feet sections are compiled. It doesn't know about levels.
    - It gains "the player's level is the target map's dimension".
    - `WarpFade`'s timeouts of 5 s and 3 s (`WarpFade.java:52-53`) get a longer variant for cross-level
      warps, sized by S7.
    - If vanilla's "Loading terrain" screen still shows through the fade, WD14 decides.
  - **`ClientShapes.topHeight`** reads the current level and caches the answer per map forever (`TOPS`,
    `ClientShapes.java:298-327`). It checks that the map belongs to the current level before reading or
    caching.
  - **Position lookups:**
    - `WorldIndex.toCell` (`WorldIndex.java:141-149`) matches a map by its column and its Y band. The other
      lookups are `Spot`, `InteractTarget`, `ActRuntime`, `ClosedArtPick`, `BlockadeFade`, `SurfSprite`,
      `StoryCellArt`, `PuppetCulling`, the shape map's `Lookup` and `RenderSnapshot`.
    - With WD1 a position belongs to one map in one dimension only, so none of them needs a level key.
    - `toCell` still gains an optional dimension filter, which costs one comparison and guards against a
      future re-pack.
    - A JUnit test asserts that the planner never gives two maps overlapping blocks in different
      dimensions.
    - `ClosedArt.at(x, z)` keys by column and ignores Y already today (`ClosedArt.java:101-104`). That is
      unchanged.
  - **F7** gains the dimension on the map line.
- **What doesn't change:** the mirror screen, dialogue, the texture flow and the bridge link don't depend on
  the level.

### 5.5 The site search (vanilla, W2)

- **Inputs:**
  - the overworld's generator and noise settings from the level stem registry;
  - the world seed;
  - the core size from planning the bundle (whole export).
- `RegionBootstrap` builds its own `RandomState` from the noise settings and the seed, because the levels
  don't exist yet at `SERVER_STARTING`. S5 confirms the 1.21.1 call.
- **Height field:**
  - One lattice every 64 blocks over the search square (±3,072 blocks plus the reserved size).
  - At each point: the surface (`getBaseHeight` with `WORLD_SURFACE_WG`), the floor (`OCEAN_FLOOR_WG`)
    and the biome (`getNoiseBiome`).
  - This is about 10,000 samples. Nothing generates and no chunk is created.
- **Cost of a candidate origin** (a 128-block grid), from summed-area tables over the lattice:

  | Term | Weight |
  |---|---|
  | mean \|surface − 64\| over the core | 1.0 |
  | mean \|surface − 64\| over the buffer | 0.5 |
  | share of core points that are water (floor < 60) | 40 |
  | share of core points in river biomes | 20 |
  | standard deviation of the core's surface | 0.3 |
  | distance from the origin, per 1,000 blocks | 0.5 |

- **Refining:** the best 10 candidates get a 16-block lattice, and the lowest cost wins. Ties go to the
  smaller distance, then the smaller X, then the smaller Z, so the result is deterministic.
- **Speed:** S5's thresholds are at most 10 s per search and a mean |surface − 64| of at most 6 for 9 seeds
  in 10.
- **Fixed mode** skips all of this. The origin is (0, 0), or a given one.

### 5.6 Pre-generation (W2)

- **`builder/PreGenerator`**, driven from the build's tick like `BlockPlacer`:
  - It walks the pre-generated area in a spiral from Kanto's centre.
  - It keeps a window of W chunks under its own region ticket (`TicketType` `kantobridge:pregen`, level
    FULL, not ticking), polls `getChunkNow`, and releases each chunk once FULL.
  - W starts at 64. It halves when a tick goes over 45 ms and grows back by 8 when ticks are under
    25 ms.
- **The same method as Chunky** (`pop4959/Chunky`, GPL-3, read for this plan, nothing copied): a radius-0
  region ticket per chunk (level 33: FULL, not ticking), the ticket dropped when the chunk is FULL, and at
  most about 50 in flight (`chunky.maxWorkingCount`).
  - `getChunkFuture(…, FULL, true)` blocks on the server thread, so we poll `getChunkNow` instead.
  - Chunky's own API (`ChunkyAPI`) would be a new runtime dependency, and a pin you'd have to approve. Not
    needed.
- **Progress:** `GENERATING n/m` on the existing build screen and in the headless log.
- **Expected cost:**
  - FULL needs ring 1 at `initialize_light`, ring 2 at `carvers`, ring 3 at `biomes` and rings 4–11 at
    `structure_starts` (a 23 × 23 footprint, `ChunkLevel.RADIUS_AROUND_FULL_CHUNK` 11; read in 1.21.1's
    `ChunkPyramid`). Over a large area the extra work is a thin border.
  - **Vanilla generation is slow, and the core dominates.** A public benchmark (ishland, 1.21.3, vanilla
    overworld, Chunky) measured 16.5 chunks/s at 2 threads, 22.9 at 4, 29.6 at 8 and about 33 at 16, where
    it levels off. C2ME, a mod you could add yourself, reached 109 at 8 threads.
  - So real Kanto's ~3,100 chunks take **about 2–3 minutes once**, at world creation.
  - The core alone is ~2,100 chunks, so a smaller buffer saves little: B = 32 saves about 450 chunks.
  - S3 measures it at 4, 8 and 16 threads.
- **The lever if S3 is too slow:** chunks wholly inside the core are cleared anyway, so they don't need
  decoration or carvers. Two more mixins, bound like the veto (§5.7), would skip `applyBiomeDecoration` and
  `applyCarvers` for those chunks only. Terrain noise still runs, which keeps the FULL-chunk guarantee
  intact.

### 5.7 The structure veto (W2)

- **Where:** a mixin at the return of `Structure#generate(…)`, which makes one start.
  - Its `GenerationContext` names the chunk generator, so the binding below applies.
  - It is the narrowest hook the 1.21.1 read found, with the same signature in 1.21.2.
  - The alternative is the tail of `ChunkGenerator#createStructures(RegistryAccess,
    ChunkGeneratorStructureState, StructureManager, ChunkAccess, StructureTemplateManager)`, rewriting that
    chunk's starts.
- **What it does,** for a generator bound to a region, unless the start is exempt or its box's top is below
  `veto.minTopY`:
  - it returns `StructureStart.INVALID_START` when the start's bounding box, grown by `veto.grow`, meets the
    reserved area.
- **Reach, for sizing:**
  - jigsaw pieces stay within `max_distance_from_center` of the start: 80 blocks (villages, outposts, trail
    ruins) or 116 (ancient city, trial chambers), plus 12 for terrain adaptation, never past 128;
  - strongholds reach 112 blocks, mineshafts 80;
  - nothing can reach a chunk more than 8 chunks from its start (the reference radius).
- **Data-driven options don't work.** `exclusion_zone` only names another structure set, never coordinates.
  Biome tags are checked only at the start point.
- **Why that is safe:** an invalid start is never referenced or placed, is saved as `"INVALID"`, and
  `/locate` re-checks validity (research A2.2 option 3; re-read in 1.21.1 by S4).
- **Binding:** `RegionBootstrap` keeps a map from generator instance to region. The overworld's generator
  instance in the stem registry is the one `createLevels` uses (research F-table). Unbound generators,
  including the Nether's and the End's, are untouched.
- **Report:** `veto.starts` and `veto.by_structure`.
- **Not used:** biome validity alone. Starts outside would still lay pieces inside (research A2.2).

### 5.8 Site preparation (W3)

**`builder/SitePreparer`** runs chunk by chunk over the pre-generated area after GENERATING, in the order below. It
writes with the placer's flags (16/18: no neighbour or shape updates, research A1.4), so nothing starts
flowing while it works. Chunks are at ticket level FULL, not ticking, so no fluid tick runs either.

| Where | Step | Rule |
|---|---|---|
| Buffer column at distance d (1…B) | ground N | The highest block that is not air, fluid, `#minecraft:logs`, `#minecraft:leaves` or a replaceable plant (so a tree is never mistaken for a hill) |
| | target (land) | `T = 63 + round((N − 63) · s(d / B))` with `s(x) = 3x² − 2x³`: Kanto's ground at the core, the natural ground at the outer edge |
| | cut | N > T: everything above T becomes air (trees included); the column's original top layers (up to 4 blocks: grass, sand, snow…) are copied onto the new surface. T ≥ 63 whenever N > 63, so a cut never opens the underground |
| | fill | N < T on land: fill up to T with the column's own filler (the block under its top layers), top layer copied |
| | sea | A column whose ground is under water at sea level: a sand beach up to Y 62 for d ≤ 8; beyond it the floor eases from 62 down to its natural depth with the same `s`; the water above the floor stays |
| | fluids | every fluid above Y 62 in buffer columns is removed (lakes, springs, rivers above sea level); sea water at Y ≤ 62 stays |
| Core column | clear | everything above Y 63 becomes air before Kanto is placed |
| | foundation | Y 55…62: air, fluid or anything replaceable becomes `kantobridge:foundation` |
| | biomes | `kanto_outdoor` over the whole column (WD13) |
| Pre-gen margin | — | untouched |

- **Generation's pending post-processing is cleared.**
  - Generation marks positions for post-processing: fluids to tick, shapes to update.
  - Vanilla runs them the first time a chunk *ticks* (`ChunkMap#prepareTickingChunk` →
    `LevelChunk#postProcessGeneration`), not when it reaches FULL. A pre-generated chunk, never ticked, still
    carries them, and they would fire when a player first comes near.
  - They describe blocks site preparation has just rewritten, so `SitePreparer` empties
    `ChunkAccess#getPostProcessing()` in every chunk it prepares.
  - The soak in S6 and W3 runs with ticking chunks to prove nothing moves afterwards.
- **Then the placer:** it writes today's blocks over a core that is empty above Y 63 and sealed below, so the
  rule "write only what differs" places exactly today's set.
- **The counted pass** (research A6.1's `foreign_blocks`, kept as a check, never as a cleaner): after
  placing, every core column is read once for:
  - `foreign_in_core`: anything at Y 63 or above that is neither planned nor air;
  - `fluid_in_core`: any fluid from Y 55 up.

  Both must be 0.
- **Rebuilds:** the core and buffer are prepared once (`build.prepared`). A later rebuild runs PLACING only,
  as today.

### 5.9 Rules and spawning in vanilla worlds (W3)

- **Unchanged and world-wide** (WD11; research A1.5 rows 1–8): Adventure, Peaceful locked, no natural mobs,
  noon, no weather, the damage veto, `keepInventory`, immediate respawn.
  - They were chosen for the hidden game's rules, and with a contained Kanto they still fit.
  - The vanilla land around is scenery at frozen noon. Its own mobs never spawn.
- **New spawn rule, per world:** a filter rule in the shape of today's two:

  ```json
  { "displayName": "Kanto Bridge: wild Pokémon only in Kanto", "enabled": true,
    "components": [ { "type": "filter",
      "spawnablePositionSelector": "!(v.spawnable_position.biome.is_of('kantobridge:kanto_outdoor') || v.spawnable_position.biome.is_of('kantobridge:kanto_cave') || v.spawnable_position.biome.is_of('kantobridge:kanto_interior'))",
      "allow": "false" } ] }
  ```

  - **Where it goes:** the builder writes it into the world's own generated datapack
    (`<world>/datapacks/kantobridge_generated/`, `DatapackWriter`), and only for a vanilla world.
  - **Why not in the jar:** today's jar rules are conditioned on Kanto biomes, so they do nothing in other
    worlds. This one would stop every Cobblemon spawn in any ordinary world opened with the mod.
  - **Unconfirmed:** that Cobblemon's MoLang accepts the `!`. S8 checks it.
  - **Why it's needed** (Cobblemon 1.8.1, read for this plan):
    - Its default spawn pools (`spawn_pool_world/*.json`) select by Cobblemon's own biome tags. So Kanto's
      biomes get no default spawns, and today only the habitat pools spawn there.
    - Vanilla biomes outside would spawn everything.
    - Spawn rules can also see `v.world`, `v.x` and `v.z`, if a box rule is ever wanted instead.
  - **Cobblemon elsewhere in the interiors dimension:**
    - Its per-player spawner, battles and PC work in any dimension.
    - The PC and the party live in `world/pokemon`, not per dimension.
    - Player-against-player challenges need both players in one dimension (`ChallengeManager`). That is
      natural: you challenge whoever stands beside you.
- **Music:** Kanto's biomes already silence Minecraft's music (research A1.5 row 9), and with WD13 the player
  always stands in one.

### 5.10 Creation and headless (W4)

- **`CreateKantoWorldFlow`:**
  - one choice: "World: Classic / In a Minecraft world";
  - for vanilla, `WorldOptions(seed, generateStructures = true, bonusChest = false)`, with an optional seed
    field;
  - the progress screen gains "Generating land (n/m chunks)" and "Preparing Kanto's site";
  - the level settings stay today's (WD11).
- **`tools/e2e/build_world.py`:**
  - `--preset legacy|classic|vanilla` (`kanto`, `kanto_flat`, `kanto_vanilla`) and `--seed N`;
  - the new report gates (§4.4);
  - the automated gate keeps the fast flat build. One vanilla build of the Pallet set runs in `--e2e`.

### 5.11 Rebuilds and the life of a world (W5)

- **The reserved area is fixed** for the life of a vanilla world. On a rebuild the planner recomputes the core
  from the export:
  - equal or a subset: build;
  - otherwise: fail with `footprint_changed` before any block is written. The report carries only the two
    hashes and a count.

  This is research A6.5's rule, with the rectangle in place of the hull.
- **The site is fixed once `region.json` exists.** A new site means a new world.
- **The veto is bound at every start** (§3.5). Deleting `region.json` from a built world would unbind it, so
  the server refuses to start a world that has a v2 index and an overworld reserved area in its report but no
  record. It warns and stops, and repairs nothing.
- **Legacy worlds:** format 1, untouched. The global height override stays for them. W5 moves it into a
  built-in data pack that is on only for worlds whose index is v1 (research A1.2 option 2), so new worlds
  never load it.

## 6. Spikes on the RunPod pod (before W1)

All headless on `kb-dev` (16 vCPU, 32 GB, $0.48/hr), with throwaway code on a spike branch. Each writes a JSON
of counts and times and exits.

**The harness:**
- A dev-only `SpikeRunner` in a new `src/spike` source set (never in the jar).
- A Loom server run config `runSpike -Pkb.spike=<id> -Pkb.seed=<n>`.
- Fabric API's `FakePlayer` where a player is needed.

**Prerequisites:**
- **The EULA (§0):**
  - Every spike and scenario server runs in a folder under the pod's persistent volume, `/workspace/kb-run/`.
    The harness copies your accepted `eula.txt` from there into each run folder.
  - Once you have read <https://aka.ms/MinecraftEULA>, you accept it once by typing this yourself:
    `! ssh kb-dev 'mkdir -p /workspace/kb-run && printf "eula=true\n" > /workspace/kb-run/eula.txt'`
  - An agent never writes that file.
- **The pod:** prepared with `kb-pod prep`, and Gradle warm.

| # | Question | Method | Pass | Pod time |
|---|---|---|---|---|
| S1 | Does a world with `kantobridge:interiors` boot, save and reload, and can a player cross both ways? | Spike preset (flat overworld + interiors); `FakePlayer` teleported 100 times each way with `teleportTo(ServerLevel, …)`; restart and reload; read the world's lifecycle with and without the stability mixin | Levels listed; type values read back; 0 failures; mean server cost per crossing reported; lifecycle stable with the mixin, experimental without | 15 min |
| S2 | Is a FULL chunk final? | Vanilla preset, 3 seeds (one with a village and one with a river at the area's edge): generate a 40×40-chunk area, hash every section, then generate a 12-chunk ring around it and hash again | 0 sections changed | 20 min |
| S3 | What does pre-generation cost? | `PreGenerator` over a 54×57-chunk area (Kanto-sized + buffer) at `-XX:ActiveProcessorCount` 4, 8 and 16; once more with decoration and carvers skipped inside the core (§5.6's lever) | Report chunks/s, wall time, heap peak, max tick. Thresholds: at 8 threads ≤ 180 s (the benchmark predicts about 105 s), heap ≤ 3 GB, max tick ≤ 250 ms; else adopt the lever | 40 min |
| S4 | Does the veto keep structures out without cutting any? | 5 seeds where `/locate` finds a village within 200 blocks of the site; with and without the mixin; count starts and pieces meeting the reserved area | With: 0 pieces inside, 0 cut structures at the edge, strongholds unchanged. Without: > 0 (the control) | 20 min |
| S5 | Is the site search fast and good? | 20 seeds; `RandomState` built at `SERVER_STARTING`; search as in §5.5 | ≤ 10 s each; mean \|surface − 64\| ≤ 6 for ≥ 18 seeds; identical result on a second run | 15 min |
| S6 | Does site preparation leave Kanto sealed? | A **Kanto-sized synthetic layout** (new `synth.py --scale kanto`: made-up maps covering a third of a 704 × 744 box, made-up interiors on 14 layers; no ROM data), a fixed seed, prepare and build; then 2,000 ticks with a `FakePlayer` at 8 points along the ring | `checks` all 0 before and after the soak; counters reported; total time ≤ 5 min | 30 min |
| S7 | How long does a door take across dimensions? | Dev client under Xvfb (it ran there on 2026-10-08) quick-playing a Classic format-2 synthetic world; 30 enters and exits through a door; the client logs the time to the respawn, the end of "Loading terrain", and all chunks within radius 4 and 10. Control: the same 30 doors as same-level teleports to Y −200 (today's kind of warp, and R1's fallback) | Enter: p95 ≤ 750 ms to the first frame. Exit: p95 ≤ 1,500 ms to radius 10. No experimental-settings screen when the world opens. Else §8 R1's fallback. **Needs your OK** to run the client (PLAN says agents don't verify with `runClient`; this measures timings, logs only, synthetic art) | 30 min |
| S8 | Does Cobblemon behave in the interiors dimension, and does the Kanto-only rule hold? | `FakePlayer` in a synthetic cave with a habitat block, 5 minutes; then a wild battle through the existing encounter path; then the same player at the ring of a vanilla world | Spawns > 0 in the cave from its pool; the battle starts and ends; 0 spawns in vanilla biomes | 20 min |
| S9 | What do idle levels cost? | Tick time with 4 levels (vanilla preset) against 2, no player and one `FakePlayer` | Difference ≤ 0.2 ms per tick | 10 min |

About 3 h 20 min of pod time in all, about $1.60 at $0.48/hr. S2, S4 and S6 also become permanent scenarios in W2–W3.

## 7. Milestones

Track tags as in PLAN: **[L]** gen1-bridge, **[C]** client, **[S]** server/common, **[B]** builder,
**[T]** tools and tests, **[D]** docs and protocol. Protocol and schema changes come first in each milestone.

### 7.0 The scenario runner (built in W1, grows with each milestone)

- **Why:** GameTests can't reach a second dimension or a noise overworld (research A6.4), so format 2 needs
  its own layer.
- **What it is:**
  - `runScenario -Pkb.scenario=<id>` starts the dev server on the pod with a given preset and seed, runs one
    scripted scenario against the synthetic bundle (with `FakePlayer` where needed), and writes
    `build/reports/scenario/<id>.json`.
  - `tools/e2e/scenarios.py` runs the list and checks each JSON.
  - `tools/check --scenarios` adds them; they need the pod's EULA.

### W1: Interiors in their own dimension (Classic first)

- [D] WorldIndex v2 (§4.2): schema, synthetic example, PROTOCOL.md, catalogs regenerated. PLAN.md's
  same-dimension statements are marked "format 1" (§10).
- [S]:
  - `data/kantobridge/dimension_type/interiors.json`, `overworld.json` (WD4), preset `kanto_flat.json`
    and its lang key;
  - the stability mixin into `WorldDimensions`' check (§3.2), so format-2 worlds aren't "experimental";
  - `server/KbLevels` (§5.1); `WarpService` across levels (§5.3) with the door ticket;
  - `HabitatService` over every level with pools, keyed by (dimension, chunk).
  - `FlashService`, `EncounterService`, `BattleService`, `BuildHold` and `WorldIndexSync` need no change with
    WD1 (§10).
- [B]:
  - `WorldPlan`/`BuildTask`/`BlockPlacer`/`BiomeWriter`/`HabitatPlanner` per level (§5.2);
  - the layer budget from the interiors level;
  - WorldIndex v2 writer;
  - report `format` and `dims`.
- [C]:
  - `MoveRuntime#destinationReady` knows the level, and `WarpFade` gets cross-level timeouts;
  - `PuppetRuntime` spawns only the current level's maps;
  - `ClientShapes.topHeight` checks the level before caching;
  - `WorldIndex.toCell`'s optional dimension filter;
  - the F7 line.
- [T]:
  - JUnit: the planner's dimension per map; v1/v2 index round trips, with v1 golden bytes unchanged; no
    position shared across dimensions; `ClientWorldIndex` lookups.
  - GameTests: unchanged (format 1), plus `KbLevels` in a format-1 world.
  - Scenarios: `classic2_build` (counts per level), `warps` (50 door round trips, levels and cells right),
    `rejoin_inside`, `habitat_cave`.
- **Checks:** `tools/check` green; scenarios green; a `kanto` (legacy) build byte-identical to today.
- **Play-test:** create a Classic world. Go in and out of houses, a multi-floor building, a cave, Viridian
  Forest and the Safari Zone; fly with a bike out of a cave; black out in a cave. Watch each door: the fade,
  any loading screen, the outside appearing again.
- **Commit** "W1: interiors in their own dimension".

### W2: The vanilla overworld, reserved

- [D] `region.json` (§4.3) with its schema under `protocol/world/region.schema.json` (tests only; never on
  the wire).
- [S]:
  - preset `kanto_vanilla.json` and its lang key;
  - `world/RegionRecord`, `server/RegionBootstrap` (§3.5), `worldgen/SiteSearch` (§5.5),
    `worldgen/StructureVeto` and `mixin/StructureGenerateMixin` (§5.7);
  - `builder/PreGenerator` (§5.6) and the phase `GENERATING`;
  - only if S3 asks for it, the two bound mixins that skip decoration and carvers inside the core (§5.6);
  - report `site`, `pregen`, `veto`.
- [T]:
  - JUnit: `SiteSearchTest` (synthetic height fields: cost, ties, determinism); `StructureVetoTest` (boxes,
    grow, exemptions); `RegionRecordTest` (round trip, canonical bytes).
  - Scenarios: `vanilla_pregen`, `structure_veto`, `full_chunks_final`.
  - `verifyMixinTargets` covers the new target.
- **Checks:** green; the same seed gives the same `region.json` twice.
- **Commit** "W2: the vanilla overworld, reserved".

### W3: Kanto built into the vanilla overworld

- [S] `KbBlocks.FOUNDATION` (unbreakable, stone look, `#wither_immune`, `#dragon_immune`); the spawn rule
  (§5.9).
- [B] `builder/SitePreparer` (§5.8), phase `PREPARING`; the placer runs after it; the counted pass; report
  `prep` and `checks`; `build.prepared` in the record.
- [T]:
  - JUnit: `SitePreparerTest` on hand-made columns (ramp values, cut and fill, sea shelf, fluid rules).
  - Scenarios: `vanilla_build_synthetic` (checks 0; 2,000-tick soak), `vanilla_build_kanto_sized` (times
    against S3/S6), `spawn_kanto_only`.
- **Checks:** green; `checks` all 0.
- **Play-test:** a world "In a Minecraft world" (from the headless build until W4):
  - walk the ring all the way round and look out over the buffer: ramps, cut trees, the sea at the south;
  - go in and out of buildings;
  - confirm nothing ever spawns outside.
- **Commit** "W3: Kanto in a Minecraft world".

### W4: Creating it

- [C] The flow's choice, seed field and phases (§5.10).
- [T]:
  - `build_world.py` `--preset` and `--seed`, and the report gates;
  - `--e2e` builds the Pallet set in a vanilla world on the test install (yours, PLAN §0.3), ids and counts
    only.
- [D] INSTALL.md, README, PLAYTEST.md, ROADMAP.md, PROGRESS.md "World plan".
- **Play-test:** create both kinds from the title screen, with your own game.
- **Commit** "W4: creating Kanto in a Minecraft world".

### W5: Hardening

- [S]/[B]:
  - the rebuild rules (§5.11);
  - the start-up refusal without a record;
  - the placer's ticket window (`BlockPlacer.java:202-213`, research A6.1);
  - the door ticket tuned from S7;
  - the legacy height override as a built-in pack for v1 worlds only;
  - WD14's loading-screen polish if S7 asked for it.
- [T]:
  - scenarios `rebuild_same`, `rebuild_footprint_changed`, `restart_veto_bound`;
  - a legacy world opened with and without the pack.
- **Commit** "W5: hardening".

## 8. Risks

| # | Risk | Likelihood | Impact | Detected by | Fallback |
|---|---|---|---|---|---|
| R1 | **Doors get slower.** DESIGN chose interiors under their building so nothing loads at a door; now every door is a dimension change, and leaving a building re-sends the outdoor chunks around you | H (it happens by design) | M | S7; W1 play-test | Two layers, then a third: (1) the door ticket (server side); (2) a fade that holds until radius 4 is meshed; (3) WD1's third option, described below |

**WD1's third option, if S7 fails:** interiors stay in the overworld **below the bedrock**, from Y −65 down, as
layers 6–14 already are (research A2.4 option 2).
- **What it keeps:** one dimension and instant doors. Chunk packets carry whole columns, so the interior is on
  the client before you reach the door, as DESIGN intended.
- **What it costs:**
  - a deeper global overworld type (`min_y` ≤ −384) for every world;
  - its mob-spawn dilution (harmless while Kanto is contained and peaceful, research A1.2);
  - layers 1–5 move;
  - the open-air interiors (Viridian Forest, the Safari Zone) lose their sky under vanilla terrain.
- **Why the dimension stays the default:** it is your brief (U1), and it gives those maps a real sky.
  S7 compares the two door times directly, with a same-level deep teleport as its control.
| R2 | Client state lost at a level change (puppets, mirror, HUD, shapes) | M | M | W1 scenarios and JUnit; play-test | Re-seat on level identity (§5.4); everything keyed by position stays valid by WD1 |
| R3 | Pre-generation too slow on modest hardware (DESIGN §"modest hardware") | M | M | S3 at 4 threads (vanilla's benchmark: 16.5–33 chunks/s, about 2–3 minutes for Kanto) | §5.6's lever (no decoration or carvers inside the core); a smaller buffer saves little, since the core is ~2,100 of the ~3,100 chunks; C2ME is yours to add |
| R4 | The buffer looks artificial (ramps, cut trees, dry riverbeds) | M | L | Synthetic views on the pod; your W3 play-test | Tune B and `s(x)`; belts by edge kind (research A2.8); the generator approach (§9) |
| R5 | A FULL chunk changes after all (lighting, post-processing, scheduled fluid ticks at the edge) | L | H | S2; the soak in S6 and W3 | The counted pass names it; fluids are already removed above Y 62 in the buffer |
| R6 | The veto mixin's target moves on a Minecraft update | M (at a bump) | M | `verifyMixinTargets`; compile | The same logic inside a generator subclass (research A2.1) |
| R7 | Cobblemon misbehaves in a custom dimension (spawns, battles, sent-out Pokémon at a door) | L–M | M | S8 | Warps are already refused while held (battles); pools and rules are data |
| R8 | Coverage gap: GameTests can't run format 2 | H (known) | M | — | The scenario runner (§7.0), which needs the pod and your EULA acceptance |
| R9 | The vanilla spawn search or other start-up generation happens before the region is bound | L | M | S4 (fixed-origin seeds); the `checks` | Binding at `SERVER_STARTING` (§3.5) |
| R10 | Memory on your PC with two levels plus pre-generation | L | M | S3 heap; S9 | The window shrinks with tick time; chunks are released as they finish |
| R11 | Single-player shows the "experimental settings" screen at every load of a format-2 world (four stems, and WD4's own overworld type) | H without the fix | M (a nag screen) | S1 (lifecycle), S7 (screen) | The stability mixin of §3.2. If it can't be done cleanly, keep `minecraft:overworld` (WD4's alternative), and suppress the screen for worlds holding `kantobridge/world_index.json` |
| R12 | Generation's pending post-processing fires at the first tick and moves fluids or shapes after the build | M | M | S6 and W3 soaks with ticking chunks | `SitePreparer` empties it (§5.8); the counted pass names anything left |

## 9. Later, not in this plan

- **Opening Kanto** (U4): gates, rules by zone, survival and Cobblemon outside. That is research M11b, M11c
  and M12–M13, mostly unchanged.
  - The reserved area, the record and the rules switch are where they plug in.
  - The hull footprint (A3.1) and belts by edge kind (A2.8) come with it.
- **A reserving generator** (research A2.1, D1): it generates the reserved area lazily with seamless edges,
  so no pre-generation is needed.
  - It is worth it when Kanto opens and players see the edge up close, or if S3 or R4 disappoint.
  - The record and the veto carry over.
- **Re-packing interiors** on one layer, so the interiors type can drop to vanilla's height.
- **Importing an old world's player side** (research A2.11 option 3).
- **More regions** (research M14).

## 10. Code inventory for W1

From a read of the code on 2026-10-09.

**Path prefixes:**
- `M/` = `fabric-bridge/src/main/java/dev/kantobridge/`
- `C/` = `fabric-bridge/src/client/java/dev/kantobridge/client/`
- `G/` = `fabric-bridge/src/gametest/java/dev/kantobridge/gametest/`
- `D/` = `fabric-bridge/src/main/resources/data/`

**The headline:** nothing in the code carries a dimension today. Every position-to-map lookup works on plain
(x, y, z). Maps that share X/Z are told apart by their Y band alone, because interiors are stacked 22 blocks
apart under their doors. That is why WD1 (keep the coordinates) keeps W1 to about M–L. Re-packing the
interiors so that coordinates overlap would make it L, since every lookup below would need a level key.

| Area | File | Change (with WD1) | Size |
|---|---|---|---|
| Data | new `D/kantobridge/dimension_type/interiors.json`, `D/kantobridge/dimension_type/overworld.json`; new presets; lang | §3.2, §4.1. The generated world datapack must never hold worldgen entries (`M/builder/pack/DatapackWriter.java:11-14`), so all of these live in the jar | S |
| Data | `D/minecraft/dimension_type/overworld.json` (−256, 576), asserted by `G/StaticDataTests.java:37-45` | Unchanged in W1; W5 moves it into a pack for v1 worlds | S |
| World | `M/world/MapPlacement.java`, `M/world/WorldIndex.java` (`VERSION`, parse :31-35, 251, `toCell` :141-149) | Dimension per map, v2 read/write, optional filter in `toCell` | S |
| World | new `M/server/KbLevels` | §5.1 | S |
| Builder | `M/builder/plan/WorldPlan.java:257-347, 355-416` | Volumes, chunks, codes, biomes and habitats per dimension | M |
| Builder | `M/builder/BuildTask.java:86-113, 168-260, 355-393` | Two levels, two placer passes, the layer budget from the interiors level, report `dims` | M |
| Builder | `M/builder/KantoWorldBuilder.java:32`, `M/builder/HeadlessBuild.java:160` | Pass the server | S |
| Builder | `M/builder/place/BlockPlacer.java`, `M/builder/place/BiomeWriter.java` | One instance per level | S |
| Builder | `M/builder/plan/HabitatPlanner.java:87-107` | Habitat → dimension | S |
| Builder | `M/builder/plan/WorldPlanner.java:134-184, 383-432`, `M/builder/plan/InteriorPacker.java` | Unchanged with WD1 (open-air and deep-spill rules kept) | — |
| Server | `M/server/WarpService.java:98-119` | Target level; `NO_LEVEL` | S |
| Server | `M/server/HabitatService.java:105-109, 254-318, 354-363` | Every level with pools; (dimension, chunk) keys | M |
| Server | `M/server/Rules.java:77-79`, `M/server/BuildHold.java:72-96`, `M/server/SaveService.java:131` | No change (see §5.1) | — |
| Server | `M/server/FlashService.java:69-79`, `M/battle/EncounterService.java:236-246`, `M/battle/BattleService.java:278-290` | No change with WD1 (positions unique) | — |
| Client | `C/move/MoveRuntime.java:199-213`, `C/move/WarpFade.java:51-53`, `C/move/MoveController.java:330-345` | Readiness includes the level; longer timeouts across levels | S |
| Client | `C/puppet/PuppetRuntime.java:218-272` | Spawn only the current level's maps (re-adding on a new level already exists, :164-167) | S |
| Client | `C/shape/ClientShapes.java:298-327` | Level check before reading and caching `TOPS` | S |
| Client | `C/move/Spot.java`, `C/move/MovementSync.java`, `C/act/*`, `C/fx/*`, `C/story/StoryCellArt.java`, `C/model/*`, `M/world/ShapeMap.java` | No change with WD1 | — |
| Protocol | `protocol/world/world_index.schema.json` (`version` const 1 at :19; maps `additionalProperties: false` at :48-77); `docs/PROTOCOL.md:707-721` | v2 and `dimension` (§4.2). The shape map binary keeps absolute positions (`docs/PROTOCOL.md:742-748`), valid by WD1 | S |
| Docs | `docs/PLAN.md:143, 209-212, 267, 716-720, 833-839` (interiors as layers of the same dimension) | Marked "format 1", with a pointer here. DESIGN.md:33-41 stays yours | S |
| Tests | JUnit: `WorldIndexTest`, `SyntheticPlanTest`, `MovementSyncTest`, `MoveControllerTest`, `PuppetCullingTest`, plus new ones (§7 W1) | Dimension per placement, v1/v2 round trips, the readiness rule | M |
| Tests | GameTests (`G/SyntheticBuildGameTests`, `G/ShapeBuildGameTests`, `G/WarpGameTests`, `G/FlashGameTests`, `G/BuildHoldGameTests`, …) and the goldens `synthetic_world_index.json`, `synthetic_shapes.json`, `tools/tests/test_builder_golden.py:33-35` | Unchanged: the GameTest server is format 1 | — |

**Two facts the inventory surfaced** (both in §5):
- `BuildTask.java:112` sizes the layers from the build level's minimum height. That must come from the
  interiors level in format 2.
- `MoveRuntime#destinationReady` and `WarpFade`'s 3–5 s timeouts were written for same-level warps.
