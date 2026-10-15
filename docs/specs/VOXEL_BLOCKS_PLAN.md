# Plan: voxel blocks and models in the Kanto world

- **Status:** executed (V1–V9, 2026-10-04), its decisions taken by their defaults; recorded in
  [PROGRESS.md](../PROGRESS.md), "Voxel plan". Its user measurements (X1–X7) and play-tests wait.
- **What it delivers:** Kanto's tile cubes become shaped art: folded facades, measured heights, roofs,
  ledge lips, grass tufts, signs, tree hulls, whole-structure models, story art meshed per player,
  player texture packs with hand-made `.vox` models, and three character styles.
- **What it leaves out:** everything about building Kanto inside a vanilla-generated overworld. That is a
  separate plan ([§1.2](#12-out-of-scope)). This plan runs on today's flat `kantobridge:kanto` world.
- **Source:** Part B of [the research report](../research/VOXEL_WORLD_REPORT.md) (sections 4.6–4.9, answers
  B1–B8). The report holds the evidence; this plan holds what to build, in which order, and how it is
  checked. Where the two differ, [§1.3](#13-differences-from-the-research-report) says why.
- **Size:** nine milestones, V1–V9. V1–V5 are the block feature itself; V6–V8 are packs, structures and
  characters; V9 is clean-up.
- **Code references** (`path:line`) were re-read on 2026-10-04 in the working tree, or carried over from
  the report's verification log.

---

## 0. How to execute this plan

### 0.1 Read first, in order

1. [`CLAUDE.md`](../../CLAUDE.md): the hard rules and the architecture decisions.
2. [`docs/PLAN.md`](../PLAN.md) §0.2–§0.5: ground rules, data safety, when to stop, commits. They apply here
   unchanged.
3. This plan, whole.
4. The report sections named in the milestone you are starting. Start with
   [4.6](../research/VOXEL_WORLD_REPORT.md#46-the-block-approach) and
   [4.7](../research/VOXEL_WORLD_REPORT.md#47-the-shape-generator-pipeline).
5. [`docs/PROGRESS.md`](../PROGRESS.md): where execution stands.

### 0.2 Ground rules added by this plan

- **Milestones run in order,** except where [§8](#8-order-and-parallel-work) says two may run side by side.
  A milestone is done only when its checks pass.
- **Art never travels and is never looked at.**
  - Geometry and textures are built on each player's machine from that player's own export. The server and
    the relay carry ids, tile numbers and hashes only.
  - Agents never open a generated pack, a shape-table cache, a key index, a pack template or an export
    bundle. All of them are ROM-derived. Listing names is allowed.
  - Reference, projection and difference images of the self-check exist in memory only.
- **Reports and logs** hold counts, hashes, timings, scores, map ids and reason codes. Never tile grids,
  pixels or text.
- **No table, code or asset from Dramaless Shape or Kanto in First Person.** Their ideas are described in
  the report in our own words; implement from the report and this plan, not from `refs/`.
- **No new dependency and no pin change.** Sodium, Iris and Distant Horizons are options for the user
  ([§2.1](#21-decisions-for-the-user), VD2). The model code must use only
  `net.fabricmc.fabric.api.renderer.v1.*` and `…client.model.loading.v1.*`, never an Indigo class.
- **No production mixin is added by this plan.** One dev-only timing mixin is allowed behind a system
  property ([§10](#10-measurements-the-user-runs)).
- **The protocol has one owner.** Change `docs/PROTOCOL.md` and `protocol/` first, regenerate the catalog
  (`tools/.venv/Scripts/python.exe -m kb_protocol.catalog --write`), then the code.
- **Client behaviour is verified by the user.** There are no client GameTests on 1.21.1
  (`fabric-bridge/build.gradle:72`). Keep everything testable in plain Java behind thin Minecraft adapters,
  and never claim a look works until the user has confirmed it.

### 0.3 When to stop and ask

Stop, write the question under "Blocked" in PROGRESS.md, commit, and end the run when:

- a hard rule would be touched, or a pin would change;
- a budget gate of [§3.8](#38-budgets) fails and the fallbacks of [§9](#9-risks-and-fallbacks) are used up;
- a self-check gate fails on the real build and the automatic fallback does not clear it;
- a decision of §2.1 without a usable default is reached;
- the collision invariant ([§3.7](#37-invariants), I1) cannot be kept.

Implementation choices inside a component's design, and tuning constants listed in
[§2.2](#22-plan-defaults), are pre-approved.

### 0.4 Commits and progress

- One commit per milestone: `V<n>: <title>`, with the harness's attribution trailer. Nothing from `local/`
  or `refs/`.
- PROGRESS.md gets a "Voxel plan" milestone table (status, date, commit), deviations, and open issues.
- PLAYTEST.md gets each milestone's play-test items.
- The user's world is rebuilt only by the user, or with their go-ahead. Old worlds stay playable after
  every milestone ([§3.7](#37-invariants), I3).

---

## 1. Scope

### 1.1 In scope

| Area | What is built | Milestone |
|---|---|---|
| Runtime model | One model class for every tile state, registered through Fabric's model-loading API; the generated pack stops writing blockstates and models | V1 |
| Shape data | A neutral scene, a deterministic planner, shape keys, the shape index and shape map, their sync to clients | V2 |
| Carrier blocks | `shape_full_00..15`, `decor`, `decor_leaves`; `ledge_collider` and `story_tile` become model blocks | V3, V5 |
| Tier A shapes | Folded facades, measured heights, roofs, ledge lips, the self-check | V3 |
| Tier B shapes | Void tiles, grass tufts, flowers, signs and other small objects, tree hulls | V4 |
| Story art | Closed swap-site art meshed per player; the block-entity renderer removed | V5 |
| Texture packs | Readable keys, a manifest, texture overrides, shape rules, `.vox` models, a template export | V6 |
| Tier C shapes and dressing | Whole-structure templates gated by the self-check; interior walls and ceilings from the room's own art | V7 |
| Characters | Thick cards (default), carved figures, rigged figures | V8 |
| Clean-up | Placeholder files removed, optional-renderer checks, docs | V9 |

### 1.2 Out of scope

Owned by the separate world-generation plan (report Part A, milestones M11–M14 there):

- the `kanto_overworld` preset, the chunk generator, the footprint, apron, ramp and belts;
- the `foundation` and `gate_cell` blocks, gates, the border state machine, `region.*` messages;
- rules by zone, protection, mobs, time and weather outside Kanto;
- the region record, `world.regions`, `world.footprint`, per-region folders, `ClientRegions`;
- the apron dressing idea (it needs generated terrain to stand on).

What this plan leaves ready for it, so neither plan has to redo the other's work:

| Later need | How this plan already fits |
|---|---|
| Kanto moved or placed inside generated terrain | The shape map stores world block positions written by the builder. Nothing in it depends on the preset or on what surrounds Kanto |
| A region record wrapping the WorldIndex | The WorldIndex stays format 1. The shape files carry their own hashes and bind to the index by hash ([§4.3](#43-shape_mapbin)); a record can list those hashes without a format change |
| Several regions | The model asks its snapshot by position (`shapeAt(x, y, z)`), never "the world's shape map". One implicit region today; a list later |
| A second game's art | Generated texture paths carry an art-set segment from V1 ([§4.7](#47-the-generated-pack-layout-2)). Block ids name no game and no region |
| Distant terrain mods | Carrier states keep their base tile, so state-keyed LOD colours stay right ([§5.13](#513-other-renderers-options-only)) |

### 1.3 Differences from the research report

| Report | This plan | Why |
|---|---|---|
| Hashes of the shape index and shape map live in the region record | They travel in `world.shapes`; the shape map's header names the WorldIndex hash and the shape index hash | No region record without Part A. The binding is the same strength |
| Closed story shapes go into the WorldIndex (`closed.shapes`) | They are an overlay table inside the shape map | Keeps WorldIndex format 1 and its golden file untouched |
| The art-set path segment arrives with "regions as data" (M14) | It arrives in V1 | V1 changes the pack layout anyway, and packs (V6) are written against these paths |
| A four-variant throw-away prototype decides the block approach before any work | The hybrid is built directly; V1 measures the control case and V4 the real one, against the same thresholds. The static variant is built only if those gates fail | Step V1 is needed by every outcome; the static approach is already short of capacity (3,926 of 4,096 full slots). Still the user's call: VD1 |
| The ledge lip is drawn by the ground block reaching up | `ledge_collider` becomes the carrier and draws the lip in its own position | A quad that leaves an opaque cube is lit from inside the cube (black with smooth lighting off). The collider already sits there and never occludes |
| Until story art is meshed, the block-entity renderer draws shape quads | It keeps drawing today's cubes until V5 replaces it | Avoids a renderer that would be thrown away one milestone later |
| Milestones M15–M20 | V1–V9 | Own numbering, so the two plans can be scheduled independently. Mapping: V1 = M15; V2 = M16; V3–V4 = M17; V5 and V7 = M20; V6 = M19; V8 = M18 |

---

## 2. Decisions

### 2.1 Decisions for the user

Each has a default. The run uses the default unless you say otherwise before the milestone named.

| # | Decision | Options | Default | Needed by |
|---|---|---|---|---|
| VD1 | The block approach | (a) Build the hybrid now, with measurement gates in V1 and V4. (b) First build the report's four-variant prototype on a throw-away branch, then decide | (a) | V1 |
| VD2 | Other renderers | Pin nothing. Allow a Sodium jar in the dev run folder for your own measurement sessions. Try Distant Horizons or Iris later | Pin nothing; the checks of V9 are sessions you run if you want them | V1 (measuring), V9 |
| VD3 | Dramaless Shape's tile tables | Never use them; or decide their licence | Never. Rules that only a person can write arrive through packs (V6) | V2 |
| VD4 | The hidden game's "void fill" display option | Pin it to the default while the bridge runs; or export the border under the current option and warn when it is not the default | Export and warn. No change to the hidden window | V2 |
| VD5 | Character style | Flat, thick card, carved, rigged as the default. Players as Minecraft skins or generated trainers | Thick cards; carved and rigged as options; skins | V8 |
| VD6 | How far packs go | Textures; plus shape rules; plus `.vox` models; plus a template export that writes reference art from your ROM to your own disk | All four, in that order. The template export runs only when you press it | V6 |
| VD7 | Dressing | Interior walls and ceilings from the room's own art; no ceiling over open-air interiors; building backs; trunks; cave roofs; canopy | The first two in V7. The rest stay switchable rules, off | V7 |
| VD8 | The block outline around thin art | Keep the cell-sized box; or hide it in Kanto | Keep until you have seen it | V3 play-test |
| VD9 | Where the generated pack sits | Above player packs (today); below them | Below, from V1. Checked by a play-test item | V1 |
| VD10 | A settings screen for client options | JSON file only; or a screen | JSON file (`kantobridge-client.json`) | V8 |

### 2.2 Plan defaults

Constants the run may tune without asking. A change to any value marked **(hashed)** is a new planner
version, because the value is inside the shape index.

| Constant | Value | Where |
|---|---|---|
| Planner version | `kb-shapes/1` in V2, raised by one in each milestone that changes planning (V3, V4, V5, V7) **(hashed)** | `ShapeParams` |
| Tallest measured unit | 6 tile rows **(hashed)** | today's `HeightRules.MAX_UNIT` (`HeightRules.java:28`) |
| Shortest repeat that splits a run | 4 tile rows **(hashed)** | `HeightRules.MIN_REPEAT_ROWS` (`:30`) |
| Void-fill cells planned like map cells | within 2 cells of a map body **(hashed)** | `hullRing` |
| Interior wall height | 2 blocks; ceiling at feet + 3 (unchanged) **(hashed)** | `HeightRules.INTERIOR_WALL`, `WorldPlan.CEILING_ABOVE_FEET` |
| Roof rise | at most 1 block above the eave **(hashed)** | `ShapePlanner` |
| Ledge lip height | 4/16 block (at most 12/16) | geometry |
| Grass tuft thickness | 2/16 block | geometry |
| Object depth, object and round-cell thresholds | chosen by the self-check and a play-test; never taken from a reference project | geometry |
| Quads per shaped block | at most 96; the generator falls back to the column version above it | geometry |
| Thick-card depth | 2 sprite pixels | characters |
| Carved figure cap | 600 merged quads | characters |
| `.vox` scale | 8, 16 or 32 voxels per block; at most 256 voxels per axis | packs |
| Shape id 0 | "no shape here": draw the plain cube of the state | everywhere |

---

## 3. Architecture

### 3.1 Vocabulary

| Term | Meaning |
|---|---|
| **Slot** | Today's `(family, hi, lo)` number in a tile block's state. It names a base tile texture through the world's slot map |
| **Carrier** | A block whose only visual job is to give the chunk mesher a position at which our model runs. Its family fixes collision, outline, occlusion and light |
| **Shape key** | A pixel-free text naming a shape: which rule, which tile ids, which offsets |
| **Shape index** | The sorted list of a world's shape keys and (key, palette) instances; hashed like the slot map |
| **Shape id** | A position's reference into the shape index: instance index + 1; 0 means none |
| **Shape map** | Position → shape id for a world. Ids only. Synced to clients |
| **Shape table** | Shape id → quads and texture keys. Built on each client from its own export; never sent |
| **Art set** | The art of one game export on one player's machine (`red`, `blue`) |
| **Scene** | The neutral in-memory description of a game's maps, atlases and sprites that the planner reads |
| **Tier A / B / C** | Shapes from tile ids only / from cell-sized pixel work / from whole structures |
| **Self-check** | Re-projecting the 3D result into the game's own view and scoring it against the 2D map |

### 3.2 The data path

```
builder (server)                          relay                        each client
----------------                          -----                        -----------
export bundle → Scene → ShapePlanner      world.shapes  ─────────────► ShapeIndex + ShapeMap (ids only)
   → shape_index.json  (keys, hashed)     (parts, hashed)                     │
   → shape_map.bin     (position → id)                                        │
   → carriers placed in the world         chunks  ───────────────────► carrier states (kind + base slot)
                                                                              │
own export bundle ─► ShapeTable (id → quads + texture keys)   built locally   │   art never travels
generated pack    ─► block-atlas sprites                                      ▼
pack manifests    ─► PackOverrides                      RenderSnapshot (immutable; one volatile reference)
                                                                              ▼
                              TileBakedModel#emitBlockQuads (mesh worker threads): look up, emit quads
                              getQuads(state, …): the plain cube of the state's slot (fallback, LOD colour)
```

Without a shape map, a shape table or an entry for a position, the model draws today's cube. Every
milestone is therefore shippable on its own.

### 3.3 Blocks

| Block | States | Collision and outline | Occludes | Role | Change |
|---|---|---|---|---|---|
| `tile_full_00..15[hi,lo]` | 4,096 | cube | yes | Ground, floors, ceilings, the void fill, interior rings, plain walls; cubes with per-face art | none |
| `tile_half_00..15[hi,lo]` | 4,096 | lower half | partly | Water | none |
| `shape_full_00..15[hi,lo]` | 4,096 | cube | **no** | Shaped art inside a solid cell: roofs, objects, hulls. The slot is the position's own tile (fallback cube, LOD colour); same slot numbers as `tile_full` | new in V3 |
| `decor` | 1 | none | no | Visual-only geometry in positions that are air today | new in V3 |
| `decor_leaves` | 1 | none | no | The same for foliage, so shader packs and LOD mods can treat it as foliage | new in V3 |
| `ledge_collider[facing]` | 4 | as today (per direction) | no | Also the carrier of the ledge lip | `RenderShape.MODEL` in V3 |
| `story_tile` | 1 | as today (per player) | no | Also the feet-level carrier of closed story art | `RenderShape.MODEL` in V5 |
| `story_cell` | 1 | as today (per player) | no | Unchanged | none |

Registration follows today's pattern (`block/KbBlocks.java:47-64`). New block ids never invalidate an
existing world. Registry growth is about 5 MB of heap per side for the 4,096 new states (report B2.5).

### 3.4 Three rules from Minecraft 1.21.1

From [B2.4](../research/VOXEL_WORLD_REPORT.md#b24-collision-and-interaction):

1. **Collision, outline and occlusion belong to the carrier family, never to the visual shape.** The model
   cannot change them, which keeps "collision per cell" independent of art.
2. **Occlusion cannot vary by position.** Face culling is cached per pair of block states. A carrier is
   either always an opaque cube or never occludes.
3. **Geometry that does not fill the cube must not sit in an opaque carrier.** Neighbours' faces would be
   culled around it and the cube's own light is 0.

### 3.5 Who owns what

| Side | Owns |
|---|---|
| **Server and world files** | Which carrier stands where; story blocks and their entities; ledge colliders; light and habitat blocks; the WorldIndex; the slot map; the shape index and shape map (ids only); collision; all relay logic |
| **Each client** | Geometry, texture binding, face-mask culling, the per-player story overlay's drawing, texture-pack overrides, character styles. The server never loads a model, a sprite or a shape table |

### 3.6 Threads

- `emitBlockQuads` runs on several mesh worker threads at once, and sometimes on the render thread, once per
  block position per section rebuild (report B2.1, point 3).
- It may read: the block view's states at the position and its direct neighbours; our own data **only**
  through `RenderSnapshot.current()`, one `volatile` read of a deeply immutable object.
- It must not: keep the `BlockPos` it is given (the mesher reuses it); call `getLightEngine()` (it throws
  under Sodium); read `Minecraft.getInstance().level`, `ClientWorldIndex.current()` or
  `ClientStoryView`'s fields; read neighbours more than 2 blocks away.
- Everything that changes drawing is published on the client thread: build a new snapshot, swap the
  reference, then mark sections dirty.
- The shape table is built off the client thread and is immutable afterwards.

### 3.7 Invariants

Each has a test ([§6](#6-testing-strategy)).

| # | Invariant |
|---|---|
| I1 | **Walkability is unchanged.** For every cell of every map, a shaped build and an unshaped build have the same colliders at feet level: a full-cube collider (`tile_full` or `shape_full`) in all four columns of a solid or counter cell, and the unchanged story and ledge blocks elsewhere. A shaped build never adds a colliding block at feet level or feet + 1 of a walkable, water or ledge cell |
| I2 | Floors, ceilings, the void fill and interior rings stay `tile_full`, so interiors stay sealed for light and section occlusion |
| I3 | A world without a shape map, or a client without a shape table, shows today's cubes. Never a hole, never missing geometry |
| I4 | Art never travels. `world.shapes` carries ids, tile numbers and hashes |
| I5 | The model reads only immutable snapshots |
| I6 | No Indigo class is referenced |
| I7 | The planner is deterministic: the same export gives the same shape index hash and shape map hash on every run, for a full and for a filtered build |
| I8 | No pixel influences a shape key or a carrier. Pixels influence geometry only |
| I9 | A pack can never leave a hole: an invalid entry is skipped with a counted warning and the base is used |
| I10 | Story art stays per player: the mesh of one client shows that client's story state only; collision still comes from the synced `active` set |

### 3.8 Budgets

Proposed in [B2.3](../research/VOXEL_WORLD_REPORT.md#b23-performance-measure); the relative ones decide.
"Baseline" is the same scene with today's cubes, measured in the same session.

| Budget | Threshold | Checked by | From |
|---|---|---|---|
| Quads per shaped block | max ≤ 96, mean ≤ 24 | build report | V3 |
| Quads per section, after face-mask culling | P95 ≤ 12,000; max ≤ 30,000 | build report | V3 |
| Runtime model with the same cubes against today | full re-mesh time ≤ 1.25×; frame rate ≥ 97% | user, [§10](#10-measurements-the-user-runs) | V1 |
| Our share of mesh time per section | median ≤ 1.0 ms; P95 ≤ 4 ms | user | V4 |
| Whole section rebuild | P95 ≤ 2× baseline and ≤ 15 ms | user | V4 |
| Frame rate | ≥ 90% of baseline at render distance 16; ≥ 85% at 32 | user | V4 |
| Heap | shape tables ≤ 32 MB; client lookup ≤ 16 MB; registry growth ≤ 12 MB per side | JUnit and user | V3 |
| Resource reload; join to first full view | ≤ baseline + 2 s; ≤ baseline + 1 s | user | V1, V4 |
| Build | placement ≥ 40,000 blocks/s; full build ≤ 90 s (today 49,237 and 56.7 s) | build report | V3 |
| Shape map on the wire | ≤ 8 relay parts of 64 Ki characters (since 2026-10-15: ≤ 64 parts for the map and ≤ 64 for the deflated index, per region: a first download, see R10) | build report | V2 |

---

## 4. Data formats and protocol

All examples use the synthetic test bundle's ids (`TEST_TOWN`, `tilesets.test`). Schemas go under
`protocol/`, with a synthetic example each and `"x-rom-derived": true` on every field that holds tile
numbers, map ids or text.

### 4.1 Files in the world folder

`<world>/kantobridge/`, written together by `builder/BuildTask.java:260-263`:

| File | Format | Status |
|---|---|---|
| `world_index.json` | WorldIndex format 1 | unchanged |
| `slot_map.json` | slot map format 1 | unchanged |
| `shape_index.json` | [§4.2](#42-shape_indexjson) | new in V2 |
| `shape_map.bin` | [§4.3](#43-shape_mapbin) | new in V2 |
| `build_report.json`, `build_diagnostics.json` | counts; symbols | new fields, [§4.10](#410-build-report-additions) |

A world without the two shape files is valid: it shows cubes.

### 4.2 `shape_index.json`

Canonical JSON written with `ProtoJson.write`, exactly like the slot map; `shapeIndexHash` is the SHA-256 of
that text. Because `planner` and `params` are inside, the planner version is inside the hash. New class
`world/ShapeIndex.java`, shaped like `world/SlotMap.java` (`enumerate`, `toJson`, `hash`, `fromJson` that
re-sorts). Schema `protocol/world/shape_index.schema.json`.

```json
{"version": 1,
 "planner": "kb-shapes/2",
 "params": {"heights": "measured", "maxUnit": 6, "minRepeatRows": 4, "hullRing": 2,
            "tierB": false, "tierC": false},
 "atlases": ["tilesets.test"],
 "palettes": ["TEST_PALETTE"],
 "geoms": ["cube:0|3,3,3,5,3,3", "slab:0|9|4"],
 "templates": [],
 "instances": [[0, 0], [1, 0]]}
```

- `atlases`, `palettes`: sorted id lists; keys refer to them by index.
- `geoms`: sorted canonical keys. **Geom id = index.** Geometry is palette-free.
- `instances`: sorted `[geomId, paletteIndex]` pairs. **Shape id = index + 1.** The palette only picks
  textures.
- `templates` (from V7): for each structure signature, its tile grid, door cells, ground tile and outdoor
  flag, so a guest can rebuild a structure from the host's list.

Key forms (`builder/shape/ShapeKey.java`). `<a>` is the atlas index. Ordering is a comparator like
`SlotMap.KEY_ORDER`: kind name, atlas, then each number numerically; never string comparison of numbers.

| Key | Meaning | First used |
|---|---|---|
| `cube:<a>\|<u>,<d>,<n>,<s>,<w>,<e>` | A full cube with one tile per face (up, down, north, south, west, east) | V2 |
| `slab:<a>\|<tile>\|<h>` | A box `h`/16 high with the tile on top and the tile's lowest rows on its sides | V3 (ledge lips) |
| `roof:<a>\|<u>,<s>,<n>\|<h0>,<h1>\|<hipW>,<hipE>` | A wedge inside one block: top surface from `h0`/16 at the south edge to `h1`/16 at the north edge; hip flags lower an exposed flank | V3 |
| `tuft:<a>\|<tile>` | Standing cut-outs of the tile's strokes (grass, flowers); the ground is the block below | V4 |
| `cell:<a>\|<t0>,<t1>,<t2>,<t3>\|<g>\|<bx>,<by>,<bz>` | One block of a cell-sized unit (tree, bush, sign); `g` is its ground tile | V4 |
| `tmpl:<a>\|<sig>\|<bx>,<by>,<bz>` | One block of a bounded structure; `sig` is the first 16 hex digits of the SHA-256 of its canonical template entry | V7 |

A block that would be `cube` with all six faces equal to the carrier's own slot tile gets shape id 0: the
plain cube already is that shape. Modifiers, appended in fixed order when present: `|tint=<0..3>`,
`|v=<variant>`.

### 4.3 `shape_map.bin`

Position → shape id, ids only. Big-endian, written with `DataOutputStream`. `shapeMapHash` is the SHA-256
of the file's bytes. New class `world/ShapeMap.java` (codec and lookup, pure).

```
magic        4 bytes   "KBSM"
version      u8        1
indexHash    32 bytes  SHA-256 of world_index.json's text
shapeHash    32 bytes  SHA-256 of shape_index.json's text
grids        u16       count; sorted by (baseY, z0, x0)
per grid:
  x0, z0     i32, i32  block position of the north-west column
  w, d       u16, u16  columns east, columns south
  baseY      i16       Y of the lowest described block
  stacks     u32       count; stack 0 is the empty stack
    per stack: u8 n, then n × u24 shape id, bottom-up from baseY
  columns    w × d × u32 stack index, row-major (z outer, x inner)
overlays     u16       count; sorted by story-cell key        (empty until V5)
per overlay:
  key        UTF       the story-cell key, as in the WorldIndex
  x, y, z    i32, i16, i32   north-west column of the cell, feet level
  4 × (u8 n, n × u24 shape id)   columns NW, NE, SW, SE; bottom-up from feet level

shapeAt(x, y, z):  g = the grid whose box holds (x, y, z)
                   s = g.stacks[g.columns[(z - g.z0) * g.w + (x - g.x0)]]
                   dy = y - g.baseY;  return 0 <= dy < s.n ? s.id[dy] : 0
```

- **Grids:** one for the surface box (`WorldPlan.surfaceBox()`), one per interior volume
  (`WorldPlan.interiorVolume`). Interiors lie on layers 22 blocks apart, so the Y range picks the grid.
- **The binding:** the two hashes in the header tie the map to one WorldIndex and one shape index. A client
  that holds another index draws cubes (`shape_map_mismatch`).
- **Lookup on the client:** at publish, a chunk grid `chunk (x, z) → grids touching it, sorted by baseY`.
  A position resolves in a few comparisons; a per-thread "last grid" makes consecutive positions of one
  section constant-time. No allocation, no locks.
- **Overlays** hold the closed shapes of `tile` story cells ([§5.9](#59-story-art-in-the-mesh)).

### 4.4 `world.shapes` (server → client, relay only)

> **Since 2026-10-15 (R10, the follow-up's item 4)** the two documents are no longer pushed: the server sends a
> `world.shapes.offer` per region in their place (each document by the SHA-256 of what it holds, and its wire size),
> and a client asks for the ones its cache lacks with `world.shapes.get`; the parts below then follow, the shape
> index deflated like the map (`enc:"deflate"`). Measured on the real Hoenn: its map is 2,080,948 characters on the
> wire (31.75 parts; the surface grid, 1,624 × 792 columns, is 40% of it, about 460 interior grids the rest; other
> encodings of the same content save 5-15% under deflate) and its index, which travelled as plain text, 8,851,374
> characters (135 parts; deflated 1,264,612, 19.3 parts). A client downloads a region's documents once per build;
> rejoining downloads none. See PROTOCOL.md "Shapes on demand".

Sent after the `world.slots` parts on join and whenever a build publishes
(`server/WorldIndexSync.java:195-207`), only when both shape files exist and the shape map's header names
the published index. Same parts mechanism as `world.slots` (`world/WorldIndexParts.java`).

| Field | Meaning |
|---|---|
| `doc` | `"index"` (the text of `shape_index.json`) or `"map"` (Base64 of the deflated bytes of `shape_map.bin`) |
| `index`, `count` | Part number and number of parts of this document |
| `hash` | SHA-256 of this document's whole transmitted text |
| `part` | A slice of the text; marked ROM-derived (tile numbers of the game's maps) |

```json
{"type": "world.shapes", "data": {"doc": "index", "index": 0, "count": 1, "hash": "<sha256>", "part": "{…}"}}
```

The client accepts the pair when: both documents assemble and match their part hashes; the shape index
parses and its version is known; the map's `indexHash` equals the hash of the `world.index` it holds; the
map's `shapeHash` equals the SHA-256 of the received index text. Otherwise it keeps cubes and reports the
reason. Work: `protocol/messages/world.shapes.schema.json`, an example, the catalog, `MsgTypes`,
PROTOCOL.md "Relay only" and "Multiplayer rules".

### 4.5 `texture.status` additions

Optional fields, so older worlds and tests keep working (`server/TextureStatusService.java:32` lists the
allowed fields; the schema has `additionalProperties: false`):

| Field | Meaning | From |
|---|---|---|
| `missing` | Slot-map keys whose sprite this client lacks (drawn as placeholders) | V1 |
| `shapes` | Shape ids this client has geometry for; 0 while it draws cubes | V2 |
| `shapeIndexHash` | The shape index it drew them from | V2 |
| `packs` | Manifests applied (a count, for "my tree looks different" reports) | V6 |

From V1, `ok` means "this client draws the world with its own art", and `slotMapHash` is the hash of the
world's slot map it resolved sprites against. New reason codes: `no_art`, `art_incomplete`, `no_shapes`,
`shape_map_mismatch`, `geometry_unsupported`. `slot_map_mismatch` can no longer happen.

### 4.6 Export additions (gen1-bridge)

Two small fields and one option record; everything else the planner needs is already exported (report
B1.5).

| Resource | Field | Value | Lua |
|---|---|---|---|
| map | `borderDrawn` | The block id the engine really draws around the map, or null when it draws none | new `engine.borderBlockFor(def)` in `lib/kb_engine.lua`, wrapping the engine's `TileRenderer.borderBlockFor` |
| tileset | `animated` | The tileset's animated tiles as data: `tiles`, `kind` (`hshift`, `frames`, `toggle`), `period`, and `offsets` or `images` + `sequence` | new `engine.animatedTiles(tileset)`, wrapping the call already used at `kb_engine.lua:5081-5098` |
| field | `display.voidFill`, `display.voidFillDefault` | The player's void-fill option and whether it is the engine's default (decided in Lua, so no engine constant is copied into Java) | `export/kb_export.lua` |

```json
{"id": "TEST_TOWN", "border": 2, "borderDrawn": 2}
{"id": "TEST", "animated": [
  {"tiles": [6], "kind": "hshift", "period": 20, "offsets": [1, 2, 3, 2, 1, 0, 7, 0]},
  {"tiles": [7], "kind": "frames", "period": 20, "sequence": [1, 2, 3, 1, 1, 2, 3, 1],
   "images": ["tilesets.flower1", "tilesets.flower2", "tilesets.flower3"]}]}
```

- Both engine queries are engine internals: only `kb_engine.lua` touches them, and they are re-checked on
  every Gen1Recomp pin change (CLAUDE.md).
- Java: `builder/export/ExportMap.java` (`borderDrawn`, falling back to `border`),
  `builder/export/ExportTileset.java` (`animated`, and the already-exported `spinner` and `pads` classes),
  `builder/export/ExportField.java` (`display`).
- The builder warns `void_fill_not_default` when the option is not the default (VD4).
- **V2 only parses `borderDrawn`.** The void fill keeps using `border` until V3, so V2 changes no block.
  From V3 the void fill and the rings wear the drawn border's art. That can add slot-map keys: if the
  build fails with `slot_map.full`, stop and ask.
- The additions change resource hashes and so `exportHash`. The slot map does not change: it depends on
  keys only.
- Also touched: `protocol/export/{map,tileset,field}.schema.json`, PROTOCOL.md "Export resources",
  `tools/fake_gen1/synth.py`, the synthetic bundle under
  `fabric-bridge/src/gametest/resources/kantobridge/synthetic_bundle/`, and the lupa tests.

### 4.7 The generated pack, layout 2

```
<gameDir>/kantobridge/resourcepack/
  pack.mcmeta
  kantobridge.json      {"layout": 2, "artSet": "blue", "game": "blue", "exportHash": "<hash>",
                         "tiles": 0, "sprites": 0, "textures": 0}
  assets/kantobridge/textures/block/<set>/tile/<atlas>/<palette>/<tile, 4 digits>.png  [+ .png.mcmeta]
  assets/kantobridge/textures/npc/<set>/<palette>/<sprite>.png
  assets/kantobridge/textures/player/<set>/<palette>/surf.png
```

- **No blockstates and no models.** The pack no longer contains slot numbers, so it fits every world of a
  compatible game. Joining a host with another slot map becomes a re-mesh, not a re-export and a reload.
- **`<set>`** is the art set: the game the export came from. A Red guest in a Blue world draws with `red/`.
- Sprite id of a tile: `kantobridge:block/<set>/tile/<atlas>/<palette>/<tile>`. One function owns the path
  rule: `builder/pack/ArtKeys.texturePath` (it replaces `ResourcePackWriter.java:238-251`).
- The pack holds the textures of the whole export's art catalog, as today.
- A pack without `layout` is layout 1: it is regenerated when the bridge is connected, and until then the
  world shows placeholders.
- One pack folder holds one art set. A folder per art set is left to the world-generation plan; the paths already
  allow it.
- **Position:** `Pack.Position.BOTTOM` (VD9). With no blockstate files left to beat, sitting below player
  packs is safe, and a player pack overrides any single texture by repeating its path.

### 4.8 Files on the client

All ROM-derived, all under `<gameDir>/kantobridge/` (in development under `local/minecraft/`, git-ignored).
Agents list names only.

| Path | Content | Written by | From |
|---|---|---|---|
| `export/<game>/` | The export bundle (exists today) | export flow | — |
| `resourcepack/` | The generated pack, layout 2 | `PackGenerator` | V1 |
| `shapes/<shapeIndexHash 16>-g<geometry>-<overridesHash 16>.bin` | The shape table cache: a header (magic, format, the three values), the texture-key list, then per shape id its quads | `ClientShapes` | V2 |
| `keys/<game>.json` | The key index for pack makers | `KeyIndexWriter` | V6 |
| `reports/shape_selfcheck.json` | Self-check scores per map id and template signature; nothing else | `SelfCheck` | V3 |
| `pack-template/<game>/` | The template export | `PackTemplateWriter`, only on the user's action | V6 |

### 4.9 The texture-pack manifest

A pack is an ordinary Minecraft resource pack. Anything beyond a plain texture override goes through
`assets/kantobridge/kb_pack/<name>.json`. The draft is
[`texture_pack_manifest.draft.json`](../research/texture_pack_manifest.draft.json); V6 turns it into
`protocol/packs/kb_pack.schema.json`.

```json
{"kantobridge_pack": 1, "id": "hd_test", "name": "HD Test", "version": "1.0.0",
 "targets": {"games": ["red", "blue"], "geometry": 1},
 "textures": {"tile:tilesets.test/4": "kantobridge:block/pack/hd_test/roof",
              "sprite:SPRITE_TEST_GIRL": "kantobridge:textures/npc_pack/hd_test/girl.png"},
 "models":   {"cell:tilesets.test/2-2-2-2": {"vox": "kantobridge:kb_models/hd_test/tree.vox", "voxelsPerBlock": 8},
              "sprite:SPRITE_TEST_BALL":    {"vox": "kantobridge:kb_models/hd_test/ball.vox", "voxelsPerBlock": 8}},
 "rules":    {"class:tree": {"style": "hull", "trunk": 1}, "tile:tilesets.test/4": {"class": "roof"}},
 "characters": {"style": "carved"},
 "dressing": {"map:TEST_HOUSE": {"ceiling": "dominant_wall", "headroom": 4}}}
```

Keys (the game is not part of a key; `targets.games` names it, because Red and Blue share their tiles):

| Key | Meaning |
|---|---|
| `tile:<atlas>/<tile>` | A tile in every palette (a full-colour replacement) |
| `tile:<atlas>/<tile>@<PALETTE>` | The same tile in one palette |
| `sprite:<SPRITE_ID>` | A character sheet or model |
| `cell:<atlas>/<t0>-<t1>-<t2>-<t3>` | A cell-sized unit |
| `struct:<atlas>/<sig>` | A bounded structure by signature |
| `place:<MAP_ID>/<n>` | One placement of a structure on a map |
| `class:<name>` | A tile class, for shape rules: `ground, water, ledge, wall, roof, tree, grass, flower, counter, prop, door, void` |
| `map:<MAP_ID>` | Per-map dressing |

Precedence for one face or shape: `place:` > `struct:` / `cell:` > `tile:@palette` > `tile:` > `class:` >
the generated base. Between packs, Minecraft's own order.

### 4.10 Build report additions

Counts, hashes and timings only; map ids only in `build_diagnostics.json`. Names as unified in report 4.11.

| Group | Fields | From |
|---|---|---|
| Blocks | `blocks_by_kind{tile_full, tile_half, shape_full, decor, decor_leaves, story_cell, story_tile, ledge, light, habitat}`, `cells_by_kind{ground, water, ledge, counter, solid}`, `solid_cells_by_height` | V2 |
| Shapes | `shape_index_hash`, `shape_planner`, `shape_map_hash`, `shape_map_bytes`, `shape_map_wire_chars`, `world_index_bytes`, `shape_keys`, `shape_keys_by_kind{cube, slab, roof, tuft, cell, tmpl}`, `shape_instances`, `shaped_blocks`, `shape_plan_ms` | V2 |
| Carriers | `shape_hosts_added`, `shape_decor_skipped`, `void_hull_cells`, `closed_shape_cells` | V3, V5 |
| Quads and time | `shape_quads_total`, `shape_quads_mean_block`, `shape_quads_max_block`, `shape_quads_p99_block`, `shape_quads_section_p50`, `shape_quads_section_p95`, `shape_quads_section_max`, `densest_section{quads}`, `shape_table_ms`, `pack_ms` | V3 |
| Self-check | `selfcheck{maps, holes, ground_match_min, structure_match_min, structure_match_p05, occluded_ground_max, units_failed}` | V3 |
| Structures | `structures`, `structures_by_kind{volume, object, hull, building, fallback}`, `shape_templates`, `selfcheck.templates_failed` | V4, V7 |
| Characters | `sprites_by_frames{1, 3, 6}`, `character_fallbacks{carved_to_thick, rigged_to_carved, rigged_to_thick}` | V8 |

---

## 5. Component designs

Packages: pure Java that both sides need goes in `fabric-bridge/src/main` (`scene/`, `builder/shape/`,
`world/`); Minecraft client adapters go in `src/client` (`client/model/`, `client/shape/`, `client/packs/`,
`client/puppet/style/`). A pure class has no Minecraft class in its signatures, so JUnit can run it.

### 5.1 The runtime model (`client/model/`)

Evidence and verified signatures:
[B2.1](../research/VOXEL_WORLD_REPORT.md#b21-the-flexible-block-in-fabric-1211).

| Class | Job |
|---|---|
| `KbModelPlugin` | `ModelLoadingPlugin.register(...)` once from `KantoBridgeClient#onInitializeClient` (`client/KantoBridgeClient.java:27`). In `onInitializeModelLoader`, registers a `BlockStateResolver` for every carrier block and calls `setModel` exactly once per state |
| `TileUnbakedModel` | No dependencies; `bake` returns a `TileBakedModel(kind, slotIndex)` holding the placeholder sprite |
| `TileBakedModel` | `isVanillaAdapter()` returns `false`. `emitBlockQuads`: one snapshot read, copy the position's coordinates, look up the shape, emit. `getQuads`: the plain cube of the state's slot, for state-keyed consumers (Distant Horizons, the breaking overlay, `renderSingleBlock`). `getParticleIcon`: the slot's sprite |
| `RenderSnapshot` | The immutable view the model reads: slot → sprite arrays, the cube quad lists per used slot, the shape map and its lookup grid, the shape table with its sprites resolved, the materials, the closed-story bit set. `current()` is one `volatile` read; `EMPTY` when not in a Kanto world |
| `SnapshotPublisher` | Client thread only. Builds and swaps a snapshot when `world.slots` or `world.shapes` arrive, when a shape table becomes ready, when a resource reload ends (`InvalidateRenderStateCallback`), when the story overlay changes, and on disconnect. After a swap that changes drawing: `levelRenderer.allChanged()`, or `setBlocksDirty` for story changes |
| `CubeEmitter` | Emits the plain full cube and the half cube of a slot |
| `ShapeEmitter` | The `QuadSink` adapter that writes `ShapeQuad`s to a `QuadEmitter` |
| `MeshStats` | Dev-only counters behind `-Dkantobridge.meshstats=true` ([§10](#10-measurements-the-user-runs)) |

Rules for the implementation:

- **A carrier resolver disables that block's blockstate JSON.** Fabric cancels vanilla's loader for a block
  with a resolver, so every state must get its model from us, and the jar's placeholder files become dead
  (removed in V9).
- **The same cubes, exactly.** The full cube uses
  `emitter.square(face, 0, 0, 1, 1, 0).spriteBake(sprite, MutableQuadView.BAKE_LOCK_UV)` with
  `cullFace(face)`, which follows the same UV rule as `cube_all`. The half cube reproduces the element and
  face UVs of the jar's `models/block/tile_half_placeholder.json`; a JUnit test compares the corner and UV
  table with that file.
- **Sprites** are resolved on the client thread, when a snapshot is built:
  `minecraft.getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).getSprite(id)`. An unknown id gives the
  missing sprite; count it as `missing`.
- **UV inset.** The Fabric emitter does not shrink UVs as vanilla's face bakery does. For sub-tile UVs
  (shapes), inset each quad's UV rectangle by `sprite.uvShrinkRatio()`.
- **Materials** come from `RendererAccess.INSTANCE.getRenderer().materialFinder()`: one per blend mode used
  (`SOLID`, `CUTOUT`), built when the snapshot is built. Without a renderer the model draws through
  `getQuads` only.
- `KbBlocks` gains `carriers()`, and per block its kind (`TILE_FULL`, `TILE_HALF`, `SHAPE_FULL`, `DECOR`,
  `LEDGE`, `STORY_TILE`) and family index.
- A client setting `shapes: on | off` in `kantobridge-client.json`; `off` draws cubes. It is the debugging
  switch and the user's fallback.

Sketch of the emit path (the full sketch is in the report):

```java
RenderSnapshot rs = RenderSnapshot.current();                 // one volatile read
int x = pos.getX(), y = pos.getY(), z = pos.getZ();           // pos is the mesher's cursor: copy, never keep
QuadEmitter out = ctx.getEmitter();
if (kind.carriesStoryArt()) rs.emitClosed(view, x, y, z, out);          // V5
int id = rs.shapeAt(x, y, z);                                 // 0: no entry, no map yet, or shapes off
ShapeMesh mesh = id == 0 ? null : rs.mesh(id);                // null while the table loads, or an unknown id
if (mesh == null) { if (kind.drawsCube()) rs.emitCube(kind, slotIndex, out); return; }
rs.emitShape(mesh, view, x, y, z, out);                       // face masks, sprites, UV inset, materials
```

### 5.2 The generated pack and the texture flow

| File | Change |
|---|---|
| `builder/pack/ResourcePackWriter.java` | Stops writing blockstates and models (`:146-182`); writes layout 2 paths through `ArtKeys.texturePath`; `kantobridge.json` as in §4.7 (`:228-233`). From V2, animated tiles come from the export's `animated` list, not from Java constants (`:47-53`) |
| `builder/pack/ArtKeys.java` (new, pure) | Key parsing and formatting; the one owner of path rules |
| `client/packs/PackInfo.java` | Reads `layout`, `artSet`; a layout 1 pack counts as "no pack" |
| `client/packs/PackGenerator.java` | No host slot map argument any more |
| `client/packs/GeneratedPack.java` | `Pack.Position.BOTTOM`; (`:52`); `suppressed` and the hold-back path go (`:28`, `:70-87`) |
| `client/packs/TextureSync.java`, `TextureSyncRuntime.java` | The decision table below replaces the slot-hash comparison (`TextureSync.java:141-156`) |
| `client/puppet/PuppetTextures.java`, `client/fx/SurfSprite.java` | Texture paths with the art-set segment |
| `server/TextureStatusService.java` | Accepts the optional fields of §4.5 |

```
set = the pack's artSet, when the pack is layout 2 and its game can draw the world's game
      (the same game, or red <-> blue)
no such pack:
    bridge connected to a compatible game ?  EXPORT + GENERATE + one RELOAD
                                          :  placeholders, reason "no_art"; retry when the bridge connects
pack present, not loaded:                    RELOAD
loaded:
    missing = slot-map keys whose sprite is absent from the block atlas
    missing == 0                          -> ready
    missing > 0 and bridge connected      -> EXPORT + GENERATE + RELOAD      (the set is stale)
    missing > 0 otherwise                 -> ready with placeholders, reason "art_incomplete"
after any change: publish a new RenderSnapshot and re-mesh. No resource reload unless a pack was written.
```

### 5.3 The scene and the front end (`scene/`)

Evidence: [B3.3](../research/VOXEL_WORLD_REPORT.md#b33-a-neutral-scene-description-draft-checked-against-gen-2-and-gen-3).
The wire export stays as it is. A front end per game family turns the bundle into a neutral in-memory
scene, and all new code (planner, geometry, keys, character styles) is written against the scene only.

- `scene/Scene`, `SceneMap`, `SceneAtlas`, `SceneSprite`, `CellKind` (`WALK, SOLID, WATER, LEDGE, COUNTER`),
  `CellTag` (`grass, door, warp, warpTile, shore, cut, pad, hole, spinner, sign`), `Env(outdoor, openAir,
  cave, dark)`, `SwapSite` with per-cell open and closed states. Records as in the report's "Data formats".
- `scene/GameFrontEnd` (`describe(ExportBundle) → Scene`, class hints), `scene/ArtSource` (pixels of a tile
  or sprite; used only where geometry is built), `scene/PaletteModel`, `scene/FrontEnds.forGame(game)`.
- `scene/gen1/Gen1FrontEnd` builds the scene from `ExportBundle` by reusing `MapModel`'s logic: tiles under
  every block, both swap states, cell kinds from `CellFlags`, the border from `borderDrawn`.
  - `openAir`: `outside` maps, plus maps on the tileset named by `field.indoorEncounters.excludedTileset`.
  - Foliage hint: the cell unit of the tileset's cut-tree tiles, and the cell units of the drawn outdoor
    border block.
- The JSON form (the draft
  [`scene_description.draft.json`](../research/scene_description.draft.json)) is the model's serialisation
  for golden tests. A scene of a real game is ROM-derived and is never written to disk by default.
- **Rule:** no new code takes `"red"`, `"blue"`, `PALLET_TOWN`, `sgb`, `kanto`, 16 or 4×4 as a literal;
  they come from the scene. Existing classes are converted only when a milestone touches them anyway.

### 5.4 The shape planner (`builder/shape/`)

Evidence: [4.7](../research/VOXEL_WORLD_REPORT.md#47-the-shape-generator-pipeline) and
[B3.1](../research/VOXEL_WORLD_REPORT.md#b31-dramaless-shapes-geometry-inventory-of-archetypes-and-rules-ideas-only).
Pure, integer-only, shared by server and client. It never reads a pixel.

```java
public static ShapePlan plan(Scene scene, Collection<MapPlacement> placements, ShapeParams params, Occupancy taken);
public final class ShapePlan {
    ShapeIndex index();                       // sorted keys and instances, hashed
    ShapeMap map();                           // position -> shape id, hashed
    Column column(int x, int z);              // what the builder places: carrier, slot tile and id per block
    int topHeight(String map, int cx, int cy);// for puppets standing on solid cells
}
```

**Stages** (stages 7–9 are geometry, §5.5):

```
1 grids      one entry per art tile = one block column: atlas, tile(open), tile(closed) or -1, the kind and
             tags of its cell, map, palette, void flag.  Surface: the whole surface box, void fill included.
             Interior: the map plus its 1-block ring.  A region never crosses an atlas change.
2 classes    per tile position, first match: front-end hint; WATER; LEDGE; COUNTER; on a walkable cell the
             atlas's grass tile -> grass, a "frames" animated tile -> flower, else ground; SOLID -> wall
3 structures door fold -> regions -> runs -> region vote -> roofs                              (tile ids only)
4 units      cell units and templates                                                          (V4, V7)
5 keys       one shape key per block; the carrier each position needs; closed-state keys for swap cells
6 index      sort keys -> ids -> shape_index.json and shape_map.bin
```

**Tier A rules** (`Structures`; planner version 2, used from V3). Coordinates are tiles; one tile is one
block column; rows grow southward.

```
solid     a tile of a SOLID cell
run       a maximal vertical stretch of solid tiles in one tile column: rows n..f (n north, f front), L = f-n+1
origin    the fold origin of a run: f; or, when a DOOR cell lies directly south of the run, that cell's
          southern tile row (the door fold: the door art stands upright at the back of the doorway,
          and the door cell itself stays walkable and unshaped)
unit      today's HeightRules.measureRun, plus the trim rule:
          L > 2 and tile(f-1) == tile(f-2)  ->  unit 2, read as a repeat
region    a 4-connected set of solid tiles of one atlas, found in row-major scan order
modeH     the most frequent unit among the region's runs (ties: the taller)
h         a run with a door fold: modeH;  a repeat with modeH > unit: modeH;  else unit        (1..6)
r         roof rows: outdoors, h >= 2, L >= 2, not a plain repeat, and tile(n) != tile(n+1)
          ? min(2, h-1) : 0
wallH     h - r                                                              (the eave height, >= 1)
```

Blocks of the column at `(tx, ty)`, `ty` in `n..f`, depth `dp = f - ty`:

```
floor (feet-1):        unchanged: the position's own tile, plain
wall band k = 0..wallH-1 at feet+k:   cube with
    south = west = east = tile(tx, origin - k)                              (the fold rule)
    north = tile(tx, min(f, n+1))                                           (a plain back, never a second front)
    up    = row ty-h belongs to the run ? tile(tx, ty-h) : tile(tx, n)      (what the 2D map shows there)
    down  = up
roof (r > 0) at feet+wallH:   roof with
    rise(s) = (32 * min(s, L-s)) / L           (1/16 block; at most one block; integer division)
    h0 = rise(dp), h1 = rise(dp+1)
    top face   = 2*dp < L ? tile(tx, n+r-1) : tile(tx, n)    (near slope: lower roof row; far slope: upper)
    south face = tile(tx, origin - wallH);  north face = tile(tx, n)
    hipW / hipE = 1 when the west / east neighbour column has no roof block at this height
COUNTER cell:          one block at feet, plain
LEDGE cell:            the ledge collider at feet carries slab(tile, lip height); the ground block is plain
WATER, walkable cells: plain
interiors:             every solid run has h = INTERIOR_WALL, r = 0; the ring and the ceiling are unchanged
void fill:             unchanged in V3 (plain columns); from V4, cells within hullRing of a map body may
                       become cell units; beyond it never (report gate void_hull_cells)
```

In V2 the planner runs with `heights: "classified"` (planner version 1): `h` is today's height of the cell
(`HeightRules.classify`, 2 or 4; interiors 2), `r = 0`, and only `cube` keys are produced. That is why V2
changes no block.

**Units and templates** (stage 4; V4 and V7):

- **Cell unit:** a SOLID 16-px cell (four tiles) that is a cut-tree cell, or stands alone (no solid
  4-neighbour cell), or repeats as the same 2×2 unit across its region (a tree wall). Its ground tile `g`
  is the most frequent ground tile among its 4-neighbour cells (ties: lowest id; none: the map's most
  frequent ground tile). Its volume is its 2×2 columns × 2 blocks, plus any trunk a rule adds.
- **Template:** a bounded region (bounding box within a size limit) that is not a pure repeat. Signature =
  hash of its tile grid, door cells, ground tile and outdoor flag. Its volume is its footprint × its drawn
  rows (at most 6) plus the roof rise.
- Volumes are fixed here from tile data alone, so the builder places carriers without pixels. A block
  whose mesh turns out empty draws nothing.

**Carriers** (stage 5):

```
a position in a SOLID or COUNTER cell's column:
    its key is none or a cube                         -> tile_full[slot of the position's tile]
    anything else (roof, cell, tmpl)                  -> shape_full[slot of the position's tile]
the feet-level position of a LEDGE cell               -> ledge_collider (already there)
a position over a walkable or water cell, or outside every column:
                                                      -> decor, or decor_leaves when the unit is foliage
the floor block under a cell unit                     -> tile_full[slot of the unit's ground tile g]
a position already taken (story_cell, story_tile, light):
    the occupant stays; the piece is skipped and counted (shape_decor_skipped).
    Collision-relevant shapes never depend on such a position.
```

**Determinism rules** (each is a test): integers only; row-major scans, ties by lowest tile id then lowest
coordinate; every collection that reaches the output sorted (`TreeMap`, `TreeSet`); keys from the whole
export (all maps, both swap states, the border art), never from a build's map filter; no pixel input.

### 5.5 Geometry (`builder/shape/geom/`)

Pure Java in `src/main`, so JUnit and the headless build can run it; it reads pixels through `ArtSource`
and runs only where the result is drawn (each client) or counted (the headless build).

```java
public final class ShapeTable {                       // immutable once built; safe for mesh threads
    static ShapeTable build(ShapeIndex index, ArtSource art, Overrides packs, int geometryVersion);
    ShapeMesh mesh(int shapeId);                      // never null for a known id: at worst the plain cube
    List<String> textureKeys();                       // resolved to sprites on the client
    long[] faceMasks(int shapeId);                    // six 64-bit masks, one per face
}
record ShapeMesh(ShapeQuad[] quads) {}
record ShapeQuad(byte face,      // 0..5 down, up, north, south, west, east; 6 sloped
                 short[] xyz,    // 4 corners × (x, y, z) in 1/16 block; block-local; may leave 0..16 by at most 8
                 int texture,    // index into textureKeys()
                 byte[] uv,      // u0, v0, u1, v1 in 1/16 of the texture; 8 values for a sloped quad
                 byte cull,      // a face index, or -1
                 byte flags) {}  // 1 cutout, 2 darken, 4 emissive, 8 no ambient occlusion
interface QuadSink { void quad(ShapeQuad quad, int textureRef); }   // production: ShapeEmitter; tests: a recorder
```

**The direct kinds** (block-local, 1/16 block; one Game Boy pixel is 2 units):

| Kind | Geometry |
|---|---|
| `cube` | Six faces on the block surface, each with its own tile and its cull face |
| `slab` | A box `0..16 × 0..h × 0..16`; top = the tile; sides = the tile's lowest `h/2` pixel rows |
| `roof` | The top surface as one sloped quad from `h0` (south) to `h1` (north), wearing `u`; vertical south and north faces up to `h0` and `h1`; triangular flanks, or a lowered outer edge when hipped |
| `tuft` | For each maximal run of stroke texels (not the lightest shade) of the tile: an upright quad pair 2 units thick at the tile's own depth. Grass: both tile rows of the cell stand. Flower: the mask is the union over the animation frames |

Face conventions: top faces show the tile as on the map (texture top = north); south faces show it upright;
north faces are mirrored in x, so art is not reversed when walked around; west and east faces show the tile
upright with texture left = north. `GeometryTest` pins them.

**From pixels to quads** (one routine for objects, hulls, templates, `.vox` models and carved figures):

```
voxelise   a boolean grid at pixel resolution and, per exposed face, a source (texture key, u, v) or a colour
shell      keep faces whose neighbour voxel in that direction is empty
merge      per direction and layer, grow rectangles greedily while the next face exists, is unused and its
           source is the next texel of the same tile; never cross a block boundary or an art-tile boundary
emit       one quad per rectangle; a cull face when it lies on the block surface
slice      file each quad under the block (bx, by, bz) that contains it
```

**Tier B** (V4; rules in our own words in report 4.7, "The pipeline in one place"):

| Rule | Input | Result |
|---|---|---|
| Void tile | all 64 pixels the darkest shade | never extrudes, whatever its class |
| Background | a structure's art plus a 1-pixel margin | lightest-shade pixels reachable from flat ground (outdoors from the south only) are not part of the thing drawn |
| Object | sprite-like tiles in a short cluster that meets flat ground and does not repeat vertically | per 8-connected pixel component, a slab a few pixels deep on the unit's ground tile |
| Round cell | a cell whose outline-plus-enclosure mask is about as wide as tall and left-right symmetric | a hull: each mask row becomes a disc |
| Otherwise | — | the column version of the same blocks (Tier A geometry from the key's tiles) |

Analysis works on **shades**, never on colours, so results are palette-independent. Every threshold is our
own tuning constant, chosen with the self-check and a play-test.

**Tier C** (V7): a template's objects are split off; the rest becomes facade and roof bands, either by a
pack rule (`struct:` band table) or by the pane heuristic; panes sink one pixel. Each template passes the
self-check or falls back to columns.

**Caps and fallbacks:** a block over 96 quads, a unit that fails its self-check, or a key this client cannot
build (unknown atlas or tile) falls back to the column version, then to the plain cube. A pack or a
heuristic can never leave a hole (I9).

**Face masks:** per shape and face, a 64-bit mask of the 8×8 pixel cells the shape covers on that block
face. A boundary quad is skipped when its footprint is inside the neighbour's opposite mask. An opaque
`tile_full` neighbour counts as a full mask; closed story shapes neither cull nor are culled.

**Texture binding:** quads name texture keys; the instance's palette picks the sprite
(`kantobridge:block/<set>/tile/<atlas>/<palette>/<tile>`). No new textures are generated for any tier:
faces reuse the tile textures through sub-tile UVs, and darkening is a vertex colour.

### 5.6 The self-check (`builder/shape/SelfCheck.java`)

Evidence: [B3.6](../research/VOXEL_WORLD_REPORT.md#b36-a-self-check-re-project-the-3d-result-into-the-games-view-and-score-it).
A pure function: project every up-facing and south-facing texel with `(x, y, z) → (x, z − y)`, keep the
nearest sample per screen pixel, and compare by **texel identity** with the 2D map composed from the
export (`Reference.java`).

| Score | Meaning | Gate |
|---|---|---|
| `holes` | map-body pixels the projection misses | 0 |
| `ground_match` | walkable and water pixels not hidden by a structure that show the same texel | 1.0 for Tier A; ≥ 0.98 with tufts |
| `structure_match` | solid-tile pixels that show the same texel | Tier A walls ≥ 0.99; roofed columns ≥ 0.90; objects ≥ 0.95; templates ≥ 0.90, else fall back |
| `occluded_ground` | walkable pixels hidden behind geometry | reported |
| `back_faces` | north or down quads not hidden by their own structure | reported |

- The first real run **records** the distributions; thresholds are fixed after it. Until then only
  `holes == 0` gates.
- It runs in JUnit on fixtures (exact expected numbers), on each client after the shape table is built
  (gating cell units and templates), and in the headless e2e build (scores copied to `local/reports/`).
- Reference, projection and difference images stay in memory. Only the five numbers per map id and per
  unit or template signature are written.

### 5.7 The builder

| File | Change | From |
|---|---|---|
| `builder/BuildOptions.java` | `shapes` (on by default; off builds today's world, used by the invariant tests) | V2 |
| `builder/plan/WorldPlanner.java` | After the maps are placed and the void fill is known (`:171-258`): build the scene, run `ShapePlanner`, keep the `ShapePlan` in the `WorldPlan` | V2 |
| `builder/plan/MapModel.java` | Keeps the measured unit per tile (`unit(tx, ty)`); `height(cx, cy)` stays for unshaped builds | V3 |
| `builder/plan/BlockCode.java` | `KIND_SHAPE = 7` (value: slot index), `KIND_DECOR = 8` (value: 0 `decor`, 1 `decor_leaves`) (`:13-22`) | V3 |
| `builder/place/CodeStates.java` | States for the two new kinds (`:17-28`) | V3 |
| `builder/plan/WorldPlan.java` | `renderMapColumn` (`:429-464`) emits the column from `ShapePlan.column(x, z)` when shapes are on, then applies story blocks, ledges, lights and ceilings as today under the occupied-position rule. `SURFACE_MAX_Y` (`:27`) rises to ground + 8 (six rows, a roof rise, a trunk). `volumesOf` keeps clearing the old world's volumes on a rebuild | V3 |
| `builder/plan/StoryCellPlanner.java` | Closed shape ids per tile cell, from the closed `MapModel`; story carriers | V5 |
| `builder/BuildTask.java` | Writes `shape_index.json` and `shape_map.bin` beside the slot map (`:48-49`, `:260-263`); publishes them; fills the report fields | V2 |
| `builder/BuildReport.java` | The fields of §4.10 | V2 |
| `tools/e2e/build_world.py`, `test_build_world.py` | The gates of §6.4 | V2 |

Rules:

- **A light block's position:** a carrier wins; the light is placed only where the position is still air
  after shapes.
- **Rebuilding:** the builder is re-runnable and leaves identical blocks alone
  (`builder/place/BlockPlacer.java:39-41`), so a V2 build of an existing world only adds the two files.
  From V3 a rebuild changes blocks in place, as at M6–M8.
- **Puppets on solid cells.** An object on a solid cell (an item on a table: 24 in the real build) gets no
  story block, because the terrain already closes the cell (`builder/plan/StoryCellPlanner.java:140-145`).
  Its puppet stands at the map's feet level today (`client/puppet/PuppetRuntime.java:239-248`), inside the
  column. From V4 it stands on top: `ClientShapes.topHeight(map, cx, cy)` counts the colliding carrier
  blocks from feet level up in the cell's north-west column of the client's level, cached per cell.

### 5.8 Sync

**Server** (`server/WorldIndexSync.java`): `Published` (`:45`) gains the shape parts. `load` reads the two
files on `SERVER_STARTED`; `publish` takes them from a finished build. They are split with
`WorldIndexParts.split` (the map as Base64 of its deflated bytes) and sent after the slot parts
(`:195-207`). A shape map whose header does not name the published index is not sent, with a warning
(`world.shapes: … not sent`), exactly as a slot map with another hash is not sent today (`:124-131`).

**Client:**

| Class | Job |
|---|---|
| `client/world/ClientWorldIndex.java` | Two more assemblers (`doc` index and map); keeps the index text's hash; clears on disconnect |
| `client/shape/ClientShapes.java` (new) | Holds the verified `ShapeIndex` and `ShapeMap`. Starts the shape-table build on `Util.backgroundExecutor()` from the local export bundle; reads or writes the cache file of §4.8; hands the result to `SnapshotPublisher`; reports `texture.status` |
| `client/model/SnapshotPublisher.java` | See §5.1 |

Order on join: `world.index` → `world.slots` → `world.shapes` (index, then map). The world is drawn with
cubes until the table is ready, then re-meshed once. A guest builds geometry for the **host's** keys from
its **own** art.

### 5.9 Story art in the mesh

Evidence: [B2.6](../research/VOXEL_WORLD_REPORT.md#b26-per-player-story-art-with-shapes) and
[B3.7](../research/VOXEL_WORLD_REPORT.md#b37-story-cells-and-swap-sites-open-and-closed-shapes). Today a
block-entity renderer redraws each closed swap cell every frame as stacked cubes, with one light sample and
no ambient occlusion (`client/story/StoryCellRenderer.java`). From V5 the closed shapes are part of each
client's own chunk mesh.

| Part | Change |
|---|---|
| Planner | Runs on both `MapModel` states. For every `tile` story cell it plans the closed column of each of the cell's four block columns, bottom-up from feet level, and writes them as an overlay in the shape map (§4.3). A flat closed cell (`height == 0`, for example hull art over water) is a `slab` 1/16 high |
| Builder | `story_tile` stays at feet level. Air positions of a closed column above it get `decor` carriers. Collision is unchanged: it still comes from the `story_tile` block and the player's `active` set (`block/StoryCellBlock.java:61-80`) |
| `block/StoryTileBlock.java` | `getRenderShape` returns `MODEL`. The block entity stays (it carries the cell key for collision); only its renderer goes |
| `client/story/ClientStoryView.java` | When `rules.cells().drawn()` changes: publish the new closed set as a bit set over overlay indices, then `levelRenderer.setBlocksDirty(...)` over each changed cell's closed column |
| `client/model/RenderSnapshot` | `emitClosed`: position → overlay column → bit set → shape ids → mesh. No string work on mesh threads |
| `client/act/ClosedArtPick.java` | The click box keeps the cell's 2×2 footprint; its height comes from the overlay column |
| Removed | `client/story/StoryCellRenderer.java`, its registration (`client/KantoBridgeClient.java:39`), the slot path of `client/story/StoryCellArt.java` |

- **Per player:** the model reads only the local player's closed set. Another player's state never exists
  on this client, and the server sees nothing new.
- **Latency:** a re-mesh is asynchronous by default, so art may trail collision by a few frames. If the
  play-test shows it, mark the sections as player-caused:
  `levelRenderer.blockChanged(level, pos, state, state, Block.UPDATE_IMMEDIATE)`.
- **Until V5** the block-entity renderer keeps working through `getQuads` and draws cubes. Closed art looks
  plainer than its surroundings in V3 and V4; that is expected and listed in PLAYTEST.md.

### 5.10 Texture packs

Evidence: [4.8](../research/VOXEL_WORLD_REPORT.md#48-the-texture-pack-format),
[B4.2](../research/VOXEL_WORLD_REPORT.md#b42-layering-pack-stacking-a-manifest-precedence-validation-fallbacks-reload),
[B4.3](../research/VOXEL_WORLD_REPORT.md#b43-what-a-pack-can-contain).

| Class | Job |
|---|---|
| `client/packs/KbPackReload` | A `SimpleResourceReloadListener<PackOverrides>` (id `kantobridge:kb_packs`). `load`, off-thread: `listResourceStacks("kb_pack", …)`, manifests in priority order, parse, validate, merge key by key, read referenced `.vox` files. `apply`: swap the active overrides, rebuild the shape table if the overrides hash changed, clear character caches |
| `client/packs/KbPackManifest` (pure) | Parser and validator |
| `client/packs/PackOverrides` (immutable) | The merged result, with a hash that is part of the shape-table cache name |
| `client/packs/KeyIndexWriter` | Writes `keys/<game>.json`: every valid key, its class, size and usage counts |
| `client/shape/vox/VoxFile` (pure) | Reads and writes `.vox`: `MAIN`, `SIZE`, `XYZI`, `RGBA`; skips unknown chunks by their declared sizes |
| `client/shape/vox/VoxMesher` (pure) | Voxels → shell → merged quads of one colour → sliced per block; vertex colours over a plain white sprite |
| `client/packs/PackTemplateWriter` | The template export, on the user's action only |

Validation (each failure is a counted warning with a code; the entry is skipped and the base is used):

| Check | Code |
|---|---|
| `kantobridge_pack` is a supported format | `pack.format` |
| `targets.games` holds the world's game | none: the manifest is skipped silently |
| The key parses and exists in the key index | `pack.unknown_key` |
| The texture exists; a block-face texture lies under `textures/block/` and is a square power of two ≥ 16 | `pack.texture_missing`, `pack.texture_size` |
| `.vox`: magic and version, one model used, size within the target's bounds, voxel count under the cap | `pack.vox_format`, `pack.vox_size` |
| A rule names a known class and its numbers are in range | `pack.rule` |
| A model stays within the quad budget per block | `pack.model_budget` |
| Total sprite area stays under half of the largest atlas; no sprite lowers the mip level | `pack.atlas_size` (the pack's textures are refused, because a stitch failure is a crash) |

`.vox` mapping, printed in the pack documentation and checked with a calibration model:

- Scale: `voxelsPerBlock` 8 (one voxel per Game Boy pixel), 16 or 32. At most 256 voxels per axis. Palette
  index 0 is empty.
- Axes: vox **z → up**, vox **x → east**, vox **y → north**, so the face seen from MagicaVoxel's default
  front looks south, the side the game draws. `turn` and `flip` in the manifest correct a model built the
  other way round. The horizontal handedness is not fixed by the format text: the user loads the
  calibration model once ([§10](#10-measurements-the-user-runs), X5).
- Origin: the model's minimum corner at the structure's north-west-bottom corner; for sprites, the
  footprint's centre on the cell's centre at feet level.

Limits that follow from the architecture:

- A pack is client-local. It cannot change collision, outlines, story cells or anything the server decides.
- A pack changes geometry **inside the carriers the world has**, plus the overflow allowance of 8/16 block.
  It cannot add positions. Anything that needs more room is a planner parameter and a rebuild.
- We ship no pack and no rule table. Generated reference files are ROM-derived: the template marks every
  one in `generated.json`, and the "prepare for sharing" command leaves them out.

### 5.11 Characters

Evidence: [4.9](../research/VOXEL_WORLD_REPORT.md#49-character-styles),
[B5.2](../research/VOXEL_WORLD_REPORT.md#b52-thick-cards),
[B5.3](../research/VOXEL_WORLD_REPORT.md#b53-carved-voxel-figures),
[B5.4](../research/VOXEL_WORLD_REPORT.md#b54-rigged-figures). The entity, the manager, culling and textures
stay. One interface inside `PuppetRenderer`; geometry is built once per sprite and cached, and a frame only
pushes vertices.

```java
interface CharacterStyle {                    // client/puppet/style/
    boolean worldOriented();                  // a real 3D object turned by its own facing, or a billboard
    void emit(VertexConsumer out, PoseStack.Pose pose, int light, FigurePose figurePose);
}
```

| Style | How | Vertices | Falls back when |
|---|---|---|---|
| Flat card (today) | one quad, yaw billboard | 4 | — |
| **Thick card** (default) | front and back quads plus an edge strip around the silhouette, 2 sprite pixels deep; still a yaw billboard, so identical to today from the front | about 200 | more than 256 quads, or an empty mask → flat |
| Carved | the visual hull of the front, back and side frames of each pose, painted through UVs into the same sheet; turned by the NPC's own facing | about 1,000–1,600 | fewer than 3 frames (items and boulders are revolved instead); rows disagree; projection differs by more than 3%; thin parts; more than 600 quads → thick |
| Rigged | head, torso, arms and legs split from the sprite's rows; six boxes built with `LayerDefinition.create(…).bakeRoot()`; a generated unwrap texture; limbs swung like a mob | 144 | not humanoid, or the boxes cover less than 85% of the sprite → carved (if enabled) → thick |

- New pure classes in `client/puppet/style/`: `SpriteFrames`, `ThickCardMesh`, `HullCarver`, `RigSplitter`,
  the styles and `StyleFallback`; `RigTextures` (a `DynamicTexture` per sprite and palette, released on
  reload). `QuadMerger` is shared with the terrain geometry.
- `client/puppet/PuppetRenderer.java` delegates to the style; `client/fx/SurfSprite.java` uses the same
  style object.
- Setting `characters.style`: `flat | thick | carved | rigged`, and `characters.packs: true | false`, in
  `client/bridge/ClientConfig.java`. Unknown values fall back with a warning, as the port does.
- Packs may replace single sprites with `.vox` models (needs V6).
- Players keep their Minecraft skins. A generated trainer is a later, separate option.

### 5.12 Dressing

Evidence: [B3.2](../research/VOXEL_WORLD_REPORT.md#b32-kanto-in-first-persons-dressing-builder-geometry-or-client-effect).
Dressing rules run after shapes, in the planner, so they are deterministic and hashed. They never touch a
walkable cell or collision.

```java
interface DressingRule {                      // builder/shape/dressing/
    boolean applies(SceneMap map);
    void dress(SceneMap map, StitchedGrid grid, ShapeSink out, PositionHash hash);
}
```

| Rule | What it does | In V7 |
|---|---|---|
| Interior walls | Wall columns rise to the ceiling, repeating their top band; the ring uses the room's dominant wall tile instead of border art | yes |
| Ceilings | The room's dominant wall tile, darkened by vertex colour (`tint`), not by a new texture | yes |
| Open-air interiors | No ceiling over maps with `env.openAir` (forest, Safari) | yes, if the sky above them is clear (below) |
| Building backs | A plain north face (already Tier A's north rule) | done in V3 |
| Trunks, cave roofs, canopy | Switchable rules | off; a later decision (VD7) |

Open-air interiors need more than a missing ceiling: the interior lies on an underground layer, so
something else may sit above it. The task in V7 is: `InteriorPacker` keeps the columns above an open-air
interior free up to the sky, and the biome gives it sky light. If the packer cannot do that for a map, the
map keeps a ceiling, raised for headroom, and the report counts it (`open_air_kept_ceiling`). Stop and ask
before changing the layer layout in any larger way.

### 5.13 Other renderers (options only)

Nothing is pinned ([§2.1](#21-decisions-for-the-user), VD2). Evidence:
[B2.2](../research/VOXEL_WORLD_REPORT.md#b22-compatibility).

| Mod | Version for Fabric 1.21.1 | Licence | What it means for our code |
|---|---|---|---|
| Indigo | in the pinned Fabric API | Apache-2.0 | The default; nothing to do |
| Sodium | `mc1.21.1-0.8.13-fabric` or `mc1.21.1-0.6.13-fabric` | Polyform Shield 1.0.0 (not open source) | It implements the Fabric rendering API itself. Our model uses only the public API, reads neighbours at most 2 blocks away, and never calls `getLightEngine()` |
| Iris | `1.8.8+1.21.1` (Sodium 0.6) or `1.8.14-beta.1+1.21.1` (Sodium 0.8) | LGPL-3.0 | Shader packs identify blocks by block id and state, so a class a pack could care about is visible in the block id: `decor_leaves`, and water in `tile_half_*` |
| Distant Horizons | `3.3.3-1.21.1` | LGPL-3.0 | It colours each block **state** from `getQuads`, which is why carriers keep their slot. Our invisible story and ledge blocks need a transparent particle texture or a place on its ignore list |

---

## 6. Testing strategy

### 6.1 Pure JUnit (the bulk)

Fixtures first: `ShapeFixtures` in `src/test` builds tiny scenes and tiny art from ASCII strings, one per
rule, so failures are readable and no golden file moves. The synthetic bundle is extended **once**, in V2
(`tools/fake_gen1/synth.py`, all drawn by code): a sign with a lightest-shade background, a 2×2-tile round
tree with a dark outline, a house with a tapered two-row roof and a windowed facade, a fence line and a
cliff mound with a rim. Then the bundle and the golden WorldIndex are regenerated.

| Suite | Proves | Milestone |
|---|---|---|
| `CubeEmitterTest`, `SlotSpriteIdTest` | Corner and UV tables equal the jar's cube and half-cube models; key → sprite path with the art set, lower-casing and `_` replacement | V1 |
| `RenderSnapshotTest` | Readers on other threads never see a half-built snapshot (a swap-loop hammer test) | V1 |
| `TextureSyncTest` (rewritten) | Every row of the decision table of §5.2 | V1 |
| `PackWriterTest`, `PackGeneratorTest`, `GeneratedPackTest`, `HostSlotPackTest` | Layout 2: no blockstates, no models, art-set paths; a pack fits a host with another slot map | V1 |
| `Gen1FrontEndTest` | The scene from the synthetic bundle equals a golden `scene.json`; flags → kinds and tags; both swap states | V2 |
| `StructuresTest` | Regions; run units with the repeat and trim rules; the region vote; the door fold; roof rows; equal results for shuffled input | V2, V3 |
| `ShapeKeyTest`, `ShapeIndexTest` | Canonical text; ordering; ids stable when the build filter changes; the hash changes with `planner` or `params`; JSON round trip | V2 |
| `ShapeMapTest` | Encode → decode → same lookups; canonical bytes; stable hash; bad input rejected; positions outside every grid → 0; layers 22 apart | V2 |
| `ShapePlannerSyntheticTest` | A golden shape index hash and shape map hash for the synthetic bundle; both swap states planned; equal across two runs and a reversed map order (I7) | V2 |
| `NoPixelTest` | The planner's inputs contain no `ArtSource`; two bundles that differ only in pixels give equal hashes (I8) | V2 |
| `CarrierChoiceTest`, `WalkabilityTest` | The carrier table of §5.4; I1 on the synthetic plan, shaped against unshaped | V3 |
| `GeometryTest` | Per kind: quad count, bounds, UVs inside the tile, face conventions, wedge heights; the greedy merge keeps the texel mapping exact (a rasterised merged mesh equals the unmerged one) | V3 |
| `FaceMaskTest` | A boundary quad is dropped only when its mask is inside the neighbour's opposite mask; no hole cases | V3 |
| `SelfCheckTest` | Tier A scores exactly 1.0 with 0 holes on the fixtures; a deliberately wrong face mapping lowers the score | V3 |
| `BackgroundTest`, `ObjectTest`, `HullTest`, `TuftTest` | Each Tier B rule on ASCII art, with the negative cases (white sealed behind dark trim stays; a vertically repeating cluster is not a prop) | V4 |
| `BudgetTest` | A section filled with the heaviest canopy shape, and one with the heaviest prop, stay within the section budget; the cap falls back to columns | V4 |
| `StoryDirtyTest`, `ClosedOverlayTest` | The dirty boxes of a story diff are the changed cells' columns and nothing else; overlay lookup | V5 |
| `ArtKeysTest`, `KbPackManifestTest`, `PackOverridesTest` | Key parsing; every validation code; precedence across a stack; unknown keys counted, not fatal | V6 |
| `VoxFileTest`, `VoxMesherTest` | Chunk parsing with unknown chunks skipped; the size and index-0 rules; the axis mapping on an asymmetric model built in the test; write → read round trip | V6 |
| `BandsTest`, `DressingTest` | Tier C bands on ASCII art; each dressing rule; determinism by position hash | V7 |
| `ThickCardMeshTest`, `HullCarverTest`, `RigSplitterTest`, `StyleFallbackTest` | Quad counts and bounds; hull projections equal the masks; each fallback rule on its counter-example; the single-frame revolve | V8 |

With `McBootstrap`: `ShapeBlockTest` (counts; `shape_full` collision equals `tile_full`; `decor` has empty
collision and outline; `canOcclude`, `isSolidRender` and `getLightBlock` per family; state heap per family,
after `TileBlockTest#stateMemoryStaysSmall`) and `CullingContractTest` (`Block.shouldRenderFace`:
`tile_full` ↔ `tile_full` culled; `shape_full` → `tile_full` culled; `tile_full` → `shape_full` drawn;
`decor` ↔ anything drawn).

### 6.2 GameTests (server)

| Test | Asserts | Milestone |
|---|---|---|
| `ShapeBuildGameTests.dataFilesOnly` | A V2 build of the synthetic bundle places exactly today's blocks and writes both shape files | V2 |
| `MultiplayerGameTests` additions | `world.shapes` reaches a mock player after `world.slots`, and only with matching hashes | V2 |
| `ShapeBuildGameTests.carriers` | Carriers stand where planned; the golden shape map is reproduced byte for byte | V3 |
| `CollisionGameTests` additions | In the shaped build a mock player cannot enter any non-walkable cell and can walk every walkable one (I1) | V3 |
| `EncounterGameTests` addition | A wild Pokémon still spawns on a grass cell that holds a `decor` tuft block | V4 |
| `StoryRulesGameTests` additions | Story cells behave as before with `story_tile` as a model block and `decor` above it | V5 |

### 6.3 What cannot be tested automatically

The Fabric adapters (the emitter, the reload listener, the entity renderer) stay thin. They are covered by
the mixin and target checks that already run in `tools/check`, by a dev-only runtime self-check
(`-Dkantobridge.meshstats`: quads emitted per section equal the pure-Java prediction, logged as counts),
and by the user's play-test.

### 6.4 End-to-end gates on the test install

`tools/check --e2e` builds the real game headlessly in `local/gen1recomp-e2e/` and reads only
`local/reports/*.json`.

| Gate | From |
|---|---|
| `shape_index_hash` and `shape_map_hash` equal across two builds, and for a filtered and a full build | V2 |
| `blocks_placed` equals the last build before V2 (no block changed) | V2 only |
| `shape_map_wire_chars` within 8 parts | V2 |
| `warnings == 0`; every budget of §3.8 that the report can check | V3 |
| `selfcheck.holes == 0`; after the first recorded run, the thresholds of §5.6 | V3 |
| `void_hull_cells == 0` beyond the ring; `shape_decor_skipped` reported | V4 |
| `closed_shape_cells == story_cells_by_type.tile` | V5 |
| `structures_by_kind.fallback` and `selfcheck.templates_failed` reported; a jump flags a regression | V7 |

---

## 7. Milestones

In the style of [PLAN.md](../PLAN.md) §6. Every milestone ends with `tools/check` green (with `--e2e` where
listed), PROGRESS.md updated, new items in PLAYTEST.md, and one commit. Track tags as in PLAN: **[L]**
gen1-bridge Lua, **[C]** client, **[S]** server and common, **[B]** builder, **[T]** tools and tests,
**[D]** docs and protocol. Protocol and schema changes come first inside each milestone.

Sizes are relative to M0–M10: **S** about M1, **M** about M6 or M7, **L** about M8.

| # | Milestone | Size | World rebuild | Depends on | Decisions first |
|---|---|---|---|---|---|
| V1 | Runtime model with the same cubes | M | no | — | VD1, VD2, VD9 |
| V2 | Shape data, sync and per-face art | L | no (two files are added) | V1 | VD3, VD4 |
| V3 | Carriers and Tier A shapes | L | **yes** | V2 | — |
| V4 | Tier B shapes | L | **yes** | V3 | — |
| V5 | Story art in the mesh | M | **yes** | V3 | — |
| V6 | Texture packs and `.vox` models | L | no | V1 (textures), V4 (rules, models) | VD6 |
| V7 | Structure templates and dressing | L | **yes** | V4; V6 for pack band rules | VD7 |
| V8 | Characters | M | no | V1; V6 for `.vox` sprites | VD5, VD10 |
| V9 | Clean-up, optional renderers, docs | S | no | all | VD2 |

### V1: Runtime model with the same cubes

No visible change and no server change. Two work packages, each green on its own.

**V1a: measuring tools** (so the baseline is taken on today's rendering)

- [C] `client/model/MeshStats`: behind `-Dkantobridge.meshstats=true`. Full re-mesh time (from
  `allChanged()` until the queue is empty), frame-time mean and 1% low over 30 s, heap after two GCs; a
  dev-only mixin at the head and return of `SectionCompiler#compile` for whole-section times, applied only
  when the property is set (a mixin config plugin). A key writes
  `local/reports/mesh_report.json` (numbers only) and an F7 line.
- [T] `tools/e2e/build_world.py` writes `local/reports/viewpoints.json`: map ids and block coordinates of
  fixed spectator viewpoints (the spawn map at ground level; above the centre of the surface box).
- **You run** measurement X1's baseline ([§10](#10-measurements-the-user-runs)).

**V1b: the model**

- [D] `texture.status`: optional `missing`; PROTOCOL.md "Multiplayer rules" (a pack no longer depends on
  the host's slot map); schema, example, catalog.
- [S] `block/KbBlocks`: `carriers()`, kind and family per block. `server/TextureStatusService`: the
  optional fields.
- [B] `builder/pack/ArtKeys`; `ResourcePackWriter`: layout 2 ([§4.7](#47-the-generated-pack-layout-2)).
- [C] `client/model/`: `KbModelPlugin`, `TileUnbakedModel`, `TileBakedModel`, `RenderSnapshot`,
  `SnapshotPublisher`, `CubeEmitter` ([§5.1](#51-the-runtime-model-clientmodel)); registered from
  `KantoBridgeClient`.
- [C] `PackInfo`, `PackGenerator`, `GeneratedPack`, `TextureSync`, `TextureSyncRuntime`, `PuppetTextures`,
  `SurfSprite` ([§5.2](#52-the-generated-pack-and-the-texture-flow)).
- [T] The V1 suites of [§6.1](#61-pure-junit-the-bulk); `fake_minecraft` if it reports `texture.status`.

**Checks:** green with `--e2e` (M9's and M10's scenarios unchanged); no blockstate or model file in a
generated pack.
**Play-test:** the world looks the same as before from the fixed viewpoints; join a host whose slot map
differs: no resource reload; F3+T; a pack with one overriding tile above "Kanto Bridge (generated)" works
and the order survives a restart (X4). **You run** X1.
**Gate:** X1 within its thresholds. If not, stop ([§9](#9-risks-and-fallbacks), R1).
**Commit "V1: runtime model".**

### V2: Shape data, sync and per-face art

No block changes: every block stays `tile_full` or `tile_half` with today's slot. Facades start showing
their own tile rows.

- [L]/[D] The export additions of [§4.6](#46-export-additions-gen1-bridge): `kb_engine.lua`, `kb_export.lua`,
  the three export schemas, PROTOCOL.md, lupa tests, `fake_gen1/synth.py`. The synthetic bundle is extended
  once ([§6.1](#61-pure-junit-the-bulk)) and the golden WorldIndex regenerated.
- [D] `world.shapes` ([§4.4](#44-worldshapes-server--client-relay-only)), `shape_index.schema.json`,
  `texture.status` (`shapes`, `shapeIndexHash`), catalog.
- [S] `scene/` and `scene/gen1/Gen1FrontEnd` ([§5.3](#53-the-scene-and-the-front-end-scene));
  `ExportMap`, `ExportTileset`, `ExportField` parsing.
- [S]/[B] `builder/shape/`: `ShapeParams`, `ShapeKey`, `Structures` (regions and runs with classified
  heights), `ShapePlanner`, `ShapePlan`; `world/ShapeIndex`, `world/ShapeMap`; `builder/shape/geom/` for
  `cube`; `SelfCheck` and `Reference` (recording only).
- [B] `BuildOptions.shapes`; the planner hook in `WorldPlanner`; `BuildTask` writes and publishes the two
  files; `BuildReport` groups Blocks and Shapes.
- [S] `server/WorldIndexSync`: load, publish and send the shape parts.
- [C] `ClientWorldIndex` assemblers; `client/shape/ClientShapes` (verify, build the table off-thread,
  cache); `ShapeEmitter`; the snapshot's shape lookup; the `shapes: on | off` setting in `ClientConfig`; an F7 line (shape ids,
  fallbacks, reason).
- [T] The V2 suites and GameTests; the e2e gates of [§6.4](#64-end-to-end-gates-on-the-test-install).

**Checks:** green with `--e2e`; the real build reports shape counts, identical hashes on two runs and on a
filtered build, and an unchanged `blocks_placed`. Self-check scores are recorded, not gated.
**Play-test:** Pallet Town from the south reads like the 2D game; from the north there is no second front
door; a guest sees the same faces as the host; `shapes: off` gives today's look.
**Commit "V2: shape data".**

### V3: Carriers and Tier A shapes

- [S] `block/ShapeBlock` (collides and is picked like a full tile; `noOcclusion`, never suffocating or
  view-blocking, lets sky light through), `block/DecorBlock` (`decor`, `decor_leaves`: no collision, empty
  outline), `LedgeColliderBlock#getRenderShape` → `MODEL`; registration in `KbBlocks`; lang entries. Every
  tag, spawn rule or list that names the tile families gains the new ones (search resources and code for
  `tile_full`).
- [B] `BlockCode`, `CodeStates`, `MapModel.unit`, `WorldPlan` ([§5.7](#57-the-builder)); `Structures` in
  full (measured heights, the door fold, the region vote, roofs); planner version 2; the carrier table and
  the occupied-position rule; ledge lips; the void fill and the rings wear `borderDrawn`'s art.
- [C]/[B] Geometry for `slab` and `roof`; face masks; quad counters shared by the client and the headless
  build; report groups Carriers, Quads and Self-check.
- [T] The V3 suites and GameTests. The first real e2e build **records** self-check distributions; then the
  thresholds of [§5.6](#56-the-self-check-buildershapeselfcheckjava) are fixed in `build_world.py` and
  noted in PROGRESS.md.

**Checks:** green with `--e2e`; I1 holds in JUnit and in the GameTest; every report-checkable budget of
[§3.8](#38-budgets) met; `selfcheck.holes == 0`.
**Play-test** (a rebuilt or new world): walk around a house, a fence, a ledge and the shore: no holes, no
black faces, no flicker; roofs and ledge lips look right; the crosshair still lands on signs and counters
(VD8); interiors are unchanged; an old world still shows cubes.
**Commit "V3: Tier A shapes".**

### V4: Tier B shapes

- [B] The planner, version raised: cell units with their ground tile; units in the void fill within `hullRing`;
  `tuft` carriers (`decor` at feet level of grass and flower cells); the foliage hint → `decor_leaves`;
  skipped pieces counted.
- [C]/[B] The pixel pipeline (`VoxelGrid`, `QuadMerger`); void tiles, background, objects, round hulls,
  tufts (flowers from the union of their frames); the quad cap and the column fallback; the self-check
  gating each unit.
- [C] `ClientShapes.topHeight`; `PuppetRuntime.position` uses it.
- [T] The V4 suites; `EncounterGameTests` addition; report group Structures.

**Checks:** green with `--e2e`; budgets met; `void_hull_cells == 0` beyond the ring.
**Play-test:** tall grass hides the feet from the south; signs, lone trees and tree walls; an item on a
table sits on the table; wild Pokémon still appear in grass. **You run** X2 and X3.
**Gate:** the budgets of [§3.8](#38-budgets). If they fail, the ladder of R1.
**Commit "V4: Tier B shapes".**

### V5: Story art in the mesh

- [B] The planner, version raised: closed columns per tile cell from the closed `MapModel`; overlays in
  the shape map; `decor` story carriers ([§5.9](#59-story-art-in-the-mesh)).
- [S] `StoryTileBlock#getRenderShape` → `MODEL`.
- [C] `ClientStoryView` publishes the closed set and marks sections dirty; `RenderSnapshot.emitClosed`;
  `ClosedArtPick` from the overlay; `StoryCellRenderer` and its registration removed.
- [T] `StoryDirtyTest`, `ClosedOverlayTest`, the story GameTests; the gate `closed_shape_cells`.

**Checks:** green with `--e2e`.
**Play-test:** a cut tree before and after Cut; a card-key door; the crosshair lands on the closed shape;
with two players, each sees their own state. Note any lag between "collision gone" and "tree gone".
**Commit "V5: story art in the mesh".**

### V6: Texture packs and `.vox` models

Ordered by VD6; each step is usable alone.

1. [C] **Textures:** `KbPackReload`, `KbPackManifest`, `PackOverrides` (`textures` only), the atlas check,
   `KeyIndexWriter`; an F7 pack line (applied, skipped by code). Can start after V1.
2. [C] **Rules:** `rules` for `class:`, `tile:` and `struct:` keys feeding the geometry stage; the
   overrides hash in the shape-table cache name.
3. [C] **Models:** `VoxFile`, `VoxMesher`; `models` for `cell:`, `struct:`, `place:` and `sprite:` keys.
4. [C] **Template export:** `PackTemplateWriter` (the key index, a manifest skeleton, reference textures,
   generated units as `.vox`, a calibration model, `generated.json`); a button beside "Create Kanto world"
   and a client command; the "prepare for sharing" command.
5. [D] `docs/PACKS.md` (keys, the manifest, the `.vox` conventions, what may be shared);
   `protocol/packs/kb_pack.schema.json` with a synthetic example; `texture.status.packs`.
6. [T] The V6 suites.

**Checks:** green.
**Play-test:** F3+T with a test pack of your own: a texture, a rule, a `.vox` tree, a `.vox` sprite;
remove it and everything falls back. **You run** X5 (the calibration model).
**Commit "V6: texture packs".**

### V7: Structure templates and dressing

- [B] The planner, version raised: templates (signature, the tile grid in the shape index, volume, carriers);
  `builder/shape/dressing/` with the rules chosen in VD7; `InteriorPacker` for open-air interiors
  ([§5.12](#512-dressing)).
- [C]/[B] Tier C geometry: objects split off, facade and roof bands (pack rule or pane heuristic), panes;
  the self-check gate per template; the `tint` modifier as a vertex colour.
- [T] `BandsTest`, `DressingTest`; the e2e gates for fallbacks.

**Checks:** green with `--e2e`; budgets still met.
**Play-test:** houses in a town from all four sides; interiors in first person (walls to the ceiling, the
ceiling's look); a forest map.
**Commit "V7: structures and dressing".**

### V8: Characters

- [C] `client/puppet/style/` ([§5.11](#511-characters)); `PuppetRenderer` and `SurfSprite` through
  `CharacterStyle`; the settings in `ClientConfig`.
- [B] Report group Characters (the pure parts run in the headless build).
- [T] The V8 suites.

**Checks:** green.
**Play-test:** each style on a walking NPC, a standing NPC, an item ball, a boulder and Snorlax; push a
boulder. **You run** X7 (64 puppets).
**Commit "V8: characters".**

### V9: Clean-up, optional renderers, docs

- [S]/[C] Remove the jar's placeholder blockstates and models that a resolver replaced (the 32 tile
  blockstates, the two tile placeholder models, `ledge_collider.json`, `story_tile.json`); keep
  `story_cell.json` and `invisible.json`. A transparent particle texture for the invisible blocks.
- [C] Remove what the migration left: the layout 1 code paths except "read as no pack", the slot path of
  `StoryCellArt`.
- [D] CLAUDE.md (layout table, architecture decisions: the block approach, packs, characters), README,
  PROTOCOL.md complete, `gen1-bridge/README.md` (the two engine queries), PLAYTEST.md, PROGRESS.md final.
- **You run**, if you want them, the sessions of X6 (Sodium, Iris, Distant Horizons). Any pin that follows
  is your decision and a VERSIONS.md change.

**Checks:** green with `--e2e`. **Commit "V9: clean-up".**

---

## 8. Order and parallel work

```
V1 ──► V2 ──► V3 ──► V4 ──► V7
 │             │      └───► V6 steps 2–4
 │             └──► V5
 ├──► V6 step 1 (textures)
 └──► V8
```

- V8 and V6 step 1 touch only client files that V2–V5 do not (`client/puppet/`, `client/packs/Kb*`); they
  can run beside them.
- V5 can run before or after V4. After V4, closed cut trees are hulls at once.
- **The hidden-step spec** ([HIDDEN_STEP_SPEEDUP.md](HIDDEN_STEP_SPEEDUP.md)) touches `gen1-bridge/act/`;
  V2 touches `gen1-bridge/export/` and two new functions in `kb_engine.lua`. No conflict.
- **The world-generation plan** touches the same builder files (`WorldPlanner`, `WorldPlan`, `BuildTask`,
  `BuildReport`, `KbBlocks`, `WorldIndexSync`, `build_world.py`). There is no design conflict: it changes
  what surrounds and underlies Kanto; this plan changes which blocks stand in Kanto's columns and what the
  client draws. Run the milestones of the two plans one at a time, not at once in the same files.

---

## 9. Risks and fallbacks

| # | Risk | Detected by | Fallback |
|---|---|---|---|
| R1 | The runtime model is too slow, or shapes cost too much | X1 (V1), the report budgets (V3), X2 and X3 (V4) | In order: lower the per-block cap; hulls only near walkable cells; Tier A only (a full, shippable result); then the report's static-variant comparison on a throw-away branch, and stop for your decision. V1 alone is still a gain (packs independent of the slot map) |
| R2 | Shapes look wrong on real art, which no agent may look at | Self-check scores per map and per unit; `structures_by_kind.fallback`; your play-test | Automatic fallback to columns per unit; Tier A alone; rules and models from packs; `shapes: off` |
| R3 | `decor` at feet level upsets Cobblemon spawning or pathing in grass | `EncounterGameTests` addition (V4) | Tufts drawn by the ground block instead: grass ground becomes `shape_full` and its quads reach up |
| R4 | Dark or dirty faces on inset geometry (a colliding carrier shades its own inside) | Play-test (V3) | `shape_full` reports full shade brightness, as the story blocks do (`block/StoryCellBlock.java:82-86`) |
| R5 | Sections full of non-occluding carriers defeat section culling | Frame rate in X2 | Keep enclosures opaque (I2); lower the density of `shape_full` in thick walls by keeping inner blocks `tile_full` |
| R6 | Story art trails collision by a few frames | Play-test (V5) | Player-flagged re-mesh (`UPDATE_IMMEDIATE`) |
| R7 | A Minecraft or Fabric update changes the model pipeline | Compile errors | No bump is planned. The adapters are thin; the data design (keys, index, map, table) does not depend on them. ⚠ Not confirmed: how the APIs differ after 1.21.4 |
| R8 | Full slots are 96% used (3,926 of 4,096) | `slot_map.full` fails the build | This plan stops spending slots: shapes and extra art are keys |
| R9 | A player pack enlarges the atlas until stitching fails (a crash) | The atlas check before a reload | The pack's textures are refused with `pack.atlas_size` |
| R10 | The shape map or index is too large for the relay | `shape_map_wire_chars`, `shape_index_wire_chars`, `shape_keys` | **Done 2026-10-15 (docs/SEVERAL_GAMES_FOLLOWUPS.md item 4)** a different way: the documents are offered by content hash (`world.shapes.offer`), sent only when a client asks (`world.shapes.get`) and kept in its cache, and the index travels deflated. Grids on demand were measured and not needed (below) |
| R11 | A guest's export lacks an atlas or tile the host's keys name | `texture.status.shapes`, F7 | That key draws the plain cube; counted |
| R12 | Open-air interiors cannot get clear sky | `open_air_kept_ceiling` | Keep a raised ceiling |
| R13 | Animated tiles stop animating under Sodium when quads use sub-tile UVs | X6 | Whole-tile quads for animated tiles |
| R14 | UV orientation or mirroring mistakes | `GeometryTest`, the self-check, the V1 "looks the same" item | — |
| R15 | Sparkles where merged rectangles of different sizes meet, or texture bleeding | Play-test (V4) | The UV inset; smaller merge rectangles |
| R16 | Heuristic thresholds are tuned blind | Recorded score distributions; your play-test | Thresholds are constants, not hashed; they change without a rebuild |

---

## 10. Measurements the user runs

Agents never start the client. Each measurement writes numbers only to `local/reports/mesh_report.json`.
Use the dev client with `-Dkantobridge.meshstats=true`, spectator mode, the viewpoints of
`local/reports/viewpoints.json`, vsync off, three runs, medians.

| # | When | What | Pass |
|---|---|---|---|
| X1 | V1a (baseline), then V1b | Today's rendering against the runtime model with the same cubes: full re-mesh time; frame rate at render distance 16 and 32; resource reload time; heap. Scenes: the spawn town, the densest map, all of Kanto from above | Re-mesh ≤ 1.25×; frame rate ≥ 97%; reload ≤ baseline + 2 s |
| X2 | V4 | Shapes against cubes on the same world (`shapes: on` and `off`): frame rate at 16 and 32; heap | ≥ 90% at 16; ≥ 85% at 32; shape tables ≤ 32 MB |
| X3 | V4 | Our share of mesh time and whole-section rebuild time | Share: median ≤ 1.0 ms, P95 ≤ 4 ms. Section: P95 ≤ 2× cubes and ≤ 15 ms |
| X4 | V1 | A pack with one overriding tile, placed above the generated pack; press Done; restart | The order is kept and the tile changed |
| X5 | V6 | Load the asymmetric calibration `.vox` exported by the template | Its front faces south, as documented; otherwise flip the documented axis and the mesher |
| X6 | V9, optional | One session each: Sodium (animated tiles, frame rate); one Iris shader pack with a `block.properties` snippet; Distant Horizons on a built world (the −256…319 dimension, invisible blocks, `decor`) | By eye: water and flowers animate; ids reach the shader; no phantom blocks in LODs |
| X7 | V8 | 64 synthetic puppets within 48 blocks, per style: frame time and allocations per frame | Thick: < 0.3 ms over flat, no allocation. Carved: < 0.5 ms at 20 and < 1.5 ms at 64 |

---

## 11. Definition of done

1. A fresh Kanto world shows shaped art: folded facades, measured heights, roofs, ledge lips, tufts,
   signs, tree hulls and structure models, with every unit that failed its self-check drawn as columns.
2. Walkability is identical to an unshaped build (I1), proven in JUnit, in a GameTest and by the e2e gates.
3. Each player sees closed story art from their own hidden game, meshed with the terrain; the block-entity
   renderer is gone.
4. A player can drop in a resource pack that overrides textures, shape rules and whole models (`.vox`),
   reload with F3+T, and remove it again without a hole anywhere.
5. Characters draw as thick cards by default, with carved and rigged styles as settings.
6. Art never left a player's machine: the relay carried ids, tile numbers and hashes only (I4).
7. A world built before this plan, and a client without art, still show today's cubes (I3).
8. The budgets of §3.8 hold on the real build's counts and in your measurements, or a recorded fallback of
   §9 is in force.
9. `tools/check --e2e` is green; PROTOCOL.md, PLAYTEST.md, PROGRESS.md, CLAUDE.md and `docs/PACKS.md` are
   current; nothing is pinned that you did not decide.

---

## Appendix A: new code at completion

| Path | Contents |
|---|---|
| `fabric-bridge/src/main/java/dev/kantobridge/scene/` | `Scene`, `SceneMap`, `SceneAtlas`, `SceneSprite`, `CellKind`, `CellTag`, `Env`, `SwapSite`, `GameFrontEnd`, `ArtSource`, `PaletteModel`, `FrontEnds`; `gen1/Gen1FrontEnd` |
| `…/builder/shape/` | `ShapeParams`, `ShapeKey`, `Structures`, `ShapePlanner`, `ShapePlan`, `Occupancy`, `SelfCheck`, `Reference`; `geom/` (`ShapeTable`, `ShapeMesh`, `ShapeQuad`, `QuadSink`, `VoxelGrid`, `QuadMerger`, the per-kind builders, `FaceMasks`); `dressing/` (`DressingRule` and the rules) |
| `…/builder/pack/ArtKeys.java` | Keys and texture paths |
| `…/world/ShapeIndex.java`, `ShapeMap.java` | The two shape documents |
| `…/block/ShapeBlock.java`, `DecorBlock.java` | The new carriers |
| `fabric-bridge/src/client/java/dev/kantobridge/client/model/` | `KbModelPlugin`, `TileUnbakedModel`, `TileBakedModel`, `RenderSnapshot`, `SnapshotPublisher`, `CubeEmitter`, `ShapeEmitter`, `MeshStats` |
| `…/client/shape/` | `ClientShapes`; `vox/VoxFile`, `vox/VoxMesher` |
| `…/client/packs/` | `KbPackReload`, `KbPackManifest`, `PackOverrides`, `KeyIndexWriter`, `PackTemplateWriter` |
| `…/client/puppet/style/` | `CharacterStyle`, `SpriteFrames`, `ThickCardMesh`, `HullCarver`, `RigSplitter`, `RigTextures`, `StyleFallback` |
| `protocol/` | `messages/world.shapes.schema.json`, `world/shape_index.schema.json`, `packs/kb_pack.schema.json`, examples; changed: `texture.status`, `export/{map,tileset,field}` |
| `docs/PACKS.md` | For pack makers |

## Appendix B: where the evidence is

| This plan | Report |
|---|---|
| §3.3–§3.4, §5.1 | [4.6](../research/VOXEL_WORLD_REPORT.md#46-the-block-approach), B1.1–B1.3, [B2.1](../research/VOXEL_WORLD_REPORT.md#b21-the-flexible-block-in-fabric-1211), [B2.4](../research/VOXEL_WORLD_REPORT.md#b24-collision-and-interaction), [B2.8](../research/VOXEL_WORLD_REPORT.md#b28-recommendation-and-migration) |
| §3.8, §10 | [B2.3](../research/VOXEL_WORLD_REPORT.md#b23-performance-measure), [B2.7](../research/VOXEL_WORLD_REPORT.md#b27-prototype-design-measure), section 8 (E13–E19) |
| §4.2–§4.4, §5.4, §5.8 | [B3.4](../research/VOXEL_WORLD_REPORT.md#b34-determinism-shape-ids-identical-for-every-client), [B3.5](../research/VOXEL_WORLD_REPORT.md#b35-where-generation-runs-data-volumes-build-time-impact), B6.1 |
| §4.6 | B1.5 |
| §4.7, §5.2 | [B1.2](../research/VOXEL_WORLD_REPORT.md#b12-the-generated-pack), B2.5, B6.4, B6.5 |
| §5.3 | [B3.3](../research/VOXEL_WORLD_REPORT.md#b33-a-neutral-scene-description-draft-checked-against-gen-2-and-gen-3), B7.1, B7.2 |
| §5.5 | [4.7](../research/VOXEL_WORLD_REPORT.md#47-the-shape-generator-pipeline) ("The pipeline in one place"), [B3.1](../research/VOXEL_WORLD_REPORT.md#b31-dramaless-shapes-geometry-inventory-of-archetypes-and-rules-ideas-only) |
| §5.6 | [B3.6](../research/VOXEL_WORLD_REPORT.md#b36-a-self-check-re-project-the-3d-result-into-the-games-view-and-score-it) |
| §5.9 | [B2.6](../research/VOXEL_WORLD_REPORT.md#b26-per-player-story-art-with-shapes), [B3.7](../research/VOXEL_WORLD_REPORT.md#b37-story-cells-and-swap-sites-open-and-closed-shapes) |
| §4.9, §5.10 | [4.8](../research/VOXEL_WORLD_REPORT.md#48-the-texture-pack-format), B4.1–B4.6 |
| §5.11 | [4.9](../research/VOXEL_WORLD_REPORT.md#49-character-styles), B5.1–B5.6 |
| §5.12 | [B3.2](../research/VOXEL_WORLD_REPORT.md#b32-kanto-in-first-persons-dressing-builder-geometry-or-client-effect) |
| §5.13 | [B2.2](../research/VOXEL_WORLD_REPORT.md#b22-compatibility) |
| §6 | [B8.1](../research/VOXEL_WORLD_REPORT.md#b81-test-design-for-part-b), [B8.2](../research/VOXEL_WORLD_REPORT.md#b82-what-the-e2e-headless-build-can-check-from-counts-alone) |
