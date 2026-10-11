# Plan: caves and woods (first-person dressing from Kanto in First Person)

- **Status:** decided by the user on 2026-10-11 (§2.1); CW1–CW5 to execute.
- **What it delivers:** the caves get a sagging rock roof with stalactites and stalagmites; Viridian Forest gets a leaf
  canopy with light wells and hanging vines; the Wild Routes forest zone gets Minecraft vines. The placements and sizes
  are those of the Gen1Recomp mod Kanto in First Person (`ds_fp_ceiling` v1.57.2): the same cells, by the same
  position formula, at the same sizes, adapted only where Minecraft's camera or our caves' height need it (§2.1 CD5,
  CD6). Everything is drawn from each map's own art, on blocks without collision.
- **Why:** the user wants the woods and caves of that mod's first-person view in Minecraft (NEXT_STAGES.md §3, ROADMAP
  Stage 6: "caves (rock roof, dripstone, pools, lanterns)" and the canopy were never built).
- **Source:** two research passes over `refs/kanto-first-person/` (`payload_ceiling.lua`, `payload_flora.lua`,
  `main.lua`), notes in `refs/_research/KFP_CAVES_INTERIORS.md` and `KFP_WOODS.md` with `file:line` references. The
  canopy's map list is Dramaless Shape's `lib/DayNight.lua` (`DayNight.CANOPY = { VIRIDIAN_FOREST = true }`, MIT).
- **Size:** five milestones, CW1–CW5.

---

## 0. How to execute this plan

- The voxel plan's §0 applies ([VOXEL_BLOCKS_PLAN.md](VOXEL_BLOCKS_PLAN.md) §0.1–§0.4), with the changes below.
  Milestones run in order; one commit each, `CW<n>: <title>`, with the harness's attribution trailer.
- **Rules, not code.** Kanto in First Person has no licence: nothing is copied from it (no code, art, sound or
  generated texture). Every rule here is re-implemented in our own Java from the research notes; its numbers (densities,
  sizes, hash salts) are parameters we use, credited in REFERENCES.md.
- **Nothing is read from the mod at runtime, and it is never installed.** It stores no positions: it computes each one
  from the cell's coordinates with a fixed formula, and its sizes are constants in its code. Re-implementing the formula
  gives every player the full effect with or without the mod. Installing it in Gen1Recomp is unsafe besides: if it is
  ever enabled it rewrites every Dramatic Shape family folder in `mods/` (KFP notes §7).
- **Feature tests per milestone; the full suite at CW2 and CW5** (the user's rule of 2026-10-11).
- **Rebuilds:** every planner milestone is a new `kb-shapes/<n>`; the user's world shows it after a rebuild, which the
  user starts.

## 1. Scope

| Area | What is built | Milestone |
|---|---|---|
| Plumbing | The position hash; a dressing layer in the plan (keys and decor carriers outside the self-check); decor above any cell kind; lit decor where a light was | CW1 |
| Caves | Every cave map (the CAVERN tileset): a sagging roof under the ceiling, stalactites, stalagmites, in the cave's rock art | CW1 |
| Forest canopy | Viridian Forest: a leaf canopy over the map and its ring at the mod's heights, light wells showing a brighter layer above, the ring raised as a curtain | CW2 |
| Vines | Viridian Forest: both of the mod's vine kinds (strands in the leaf art; stem-and-stub strands in three greens), static | CW2 |
| The forest's apron | The mod's 4-cell ring of further trees past the map edge, with its curtain at the outer edge | CW3 (optional, the user decides after CW2) |
| Wild zone | Minecraft vines on the Wild Routes forest's trees and hedges, above head height | CW4 |
| Finish | Docs, the full suite, a real-world build on the pod (budgets), a video | CW5 |

**Out of scope:** swaying and swinging (needs a custom renderer: later), cave pools, torches, bats, route tunnels (the
UNDERGROUND tileset), interiors' doors, pictures, lamps and windows, the sky, rain, tufts, sun shafts and particles.

## 2. Decisions

### 2.1 The user's (2026-10-11)

| Id | Decision |
|---|---|
| CD1 | Each map's own art (not vanilla dripstone and vines) for the caves and Viridian Forest |
| CD2 | Viridian Forest gets the canopy and vines |
| CD3 | Extra: Minecraft vines in the Wild Routes forest zone |
| CD4 | The mod's positions and sizes, with a fallback where something is missing. Since it stores no positions, its formula and numbers are re-implemented (no install, nothing read at runtime). The one input with a fallback is Dramaless Shape's tile classes (the canopy's leaf tiles), read as today from the profile; without it, our own classification |
| CD5 | Caves stay 3 blocks high (the mod's MID headroom); the roof and spikes are scaled to fit |
| CD6 | Paths stay clear: over a cell you walk or surf on, nothing hangs below head height and no stalagmite stands |

### 2.2 Plan defaults (pre-approved; the user may change any)

| Id | Default |
|---|---|
| CD7 | Static: no sway, no swing (a custom renderer, later) |
| CD8 | Cave heights scale by 24/32 (our 3 blocks over the mod's default 4-block AIRY room); widths and positions stay; heights round to whole pixels (the face masks' grid) |
| CD9 | "Head height" is 16 px (2 blocks) above the floor: a player is 1.8 blocks tall, the eye at 1.62 |
| CD10 | A stalagmite rises from its cell's own top (a rock cell's two blocks), not from the floor: the mod's floor stalagmites stand on paths or inside rock |
| CD11 | Rock wears the room's wall tile (V7's `Ceilings` choice: the commonest wall tile, the mod's own fallback); the mod's preferred "plainest" tile needs pixels, and the planner reads none (I8) |
| CD12 | Light wells show the upper leaf layer, as in the mod (correcting the "gaps of sky" in the question of 2026-10-11) |
| CD13 | The canopy is a filled mass hanging from the upper layer: the mod's panels are its underside and its skirts the steps between them; a light well is a shaft up to the upper layer (one geometry for caves and canopy, and the face masks cull it) |
| CD14 | The mod's two quirks are not kept: a stem-and-stub strand never floats below its panel (it hangs from the canopy's underside, and none hangs in a well), and leaf tiles are ranked by frequency (the mod's "greenness" needs pixels) |
| CD15 | Lights stay where they are: a decor carrier that takes a light's position emits the light itself (`decor` gains `lit`) |

## 3. Architecture

```
planner (shared, pixel-free)                     builder (server)                each client
----------------------------                     ----------------                -----------
Dressing.RULES += CaveRock (CW1), Canopy (CW2)   WorldPlan: decor carriers at     ShapeTable: the new kinds
  → Dress.hang: per art tile, keys by level        every dressing position;          (hang, vine, strand) from
    from feet, and their carrier mask              lit where a light was;            its own art; FaceMasks
  → MapShapes.hung / hungMask (not in `open`:      ring columns raised (CW2);        cull the inner faces
    the self-check never sees them)                biome and volume to feet+11
ShapePlanner: ids into the shape map,              for open-sky interiors
  under the ceiling or the sky
```

- **Where things are decided.** The planner decides every piece from tile ids, cell kinds, the profile's classes and
  the position hash only (I8). Keys carry every number their geometry needs; a client without the profile draws the
  same pieces.
- **The position hash** (`dressing/CellHash`): `hash01(a, b, c)` = the fraction of `sin(a·127.1 + b·311.7 + c·74.7) ×
  43758.5453123`, evaluated left to right in doubles with `StrictMath.sin`, so the server and every client get the same
  bits on every JVM. Inputs: the cell's coordinates in its map (cells, negative in a ring) and a salt. "One in N" is
  `floor(hash · N) == 0`. LuaJIT evaluates the same expression in the same order, so the cells match the mod's (a cell
  could differ only if two runtimes' `sin` disagreed within 10⁻¹¹ of a threshold).
- **A dressing layer.** Dressing pieces are decoration, never structure: they live in `MapShapes.hung` (per art tile,
  keys by level from feet, null where none) and `hungMask` (bit k: a decor carrier at feet + k), not in `open`. So the
  self-check, unit tallies and puppets' stand heights never see them, and collision never changes.
- **Decor above any cell.** WorldPlan places a decor carrier at every `hungMask` level a column leaves free (a solid
  column's blocks, a ledge collider, a story block and a tuft all win). The shape map takes the ids under the ceiling
  (interiors) or up to the dressing's top (open sky).

## 4. Formats

### 4.1 Keys (`kb-shapes/9` from CW1, `kb-shapes/10` from CW2)

All lengths in 1/16 block (a Game Boy pixel is 2); block-local, x east, y up, z south.

| Kind | Text | Geometry |
|---|---|---|
| `hang` | `hang:<a>\|<t>\|<y0>\|<d>,<b>,<p>,<w>,<x>,<z>` | A box from y0 up to the block's top, without a top face: its bottom and sides wear tile t (sides show the tile's lowest rows, like a box's). y0 = 16: no box. Plus a spike when d > 0: a square pyramid, half-width w, its base centred at (x, z) at height b, its apex at height p (d = 1 hangs, p < b; d = 2 stands, p > b); four sloped faces wearing t, squeezed to the apex. `tint` darkens it |
| `vine` | `vine:<a>\|<t>\|<x>,<z>,<w>,<r>\|<top>,<len>,<k>\|<n>,<s1>,<s2>,<e1>,<e2>` | One block (k blocks below the strand's top block) of a leaf strand: two crossed vertical quads, half-width w, turned r × π/16 and r × π/16 + π/2 about (x, z), from height top (in its top block) down `len`, the whole tile stretched along it; n stubs (1–2) at heights s1, s2 above the tail, on side e (0 west, 1 east), 6 × 3 flat quads; both faces of every quad. `tint` darkens it |
| `strand` | `strand:<a>\|<x>,<z>,<r>\|<top>,<len>,<k>` | One block of a stem-and-stub strand: the mod's 8 × 16 pattern built as coloured quads (`color:` textures) on two crossed planes, 10 wide, from top down `len`: a two-column stem and stubs every five rows on alternate sides, in three greens; both faces |

Modifiers as before (`|tint=`, `|v=`). `shape_index.schema.json`'s geoms pattern gains the three kinds;
`ShapeTable.GEOMETRY` goes to 5. A key over air draws nothing; a piece that fails to build draws nothing (decor
carriers never fall back to a cube), so there is never a hole (I3).

### 4.2 Plan, blocks, report

- `MapShapes` gains `hung` (`ShapeKey[][]`) and `hungMask` (`int[]`); `Dress` gains the same, written by the rules.
- `decor` and `decor_leaves` gain a boolean `lit` (light 15); `BlockCode.decor(leaves, lit)`.
- `ShapeParams` gains `caveRock` (CW1) and `canopy` (CW2), written into the shape index's parameters.
- `Env` gains `canopy`, set by the Gen 1 front end for `VIRIDIAN_FOREST` (Dramaless Shape's own canopy list).
- The build report's `dressing`: per rule, maps dressed, pieces by kind (roof, stalactite, stalagmite, canopy, well,
  vine, strand), clamped and dropped spikes and vines, lit decor.

## 5. Designs

### 5.1 Caves: `CaveRock` (CW1)

Applies to an interior under a ceiling (not open-air) whose `env.cave` is set (the CAVERN tileset). A cell is
**roofed** when it lies in the map, its four tiles are not all the map's border-block tiles (the mod's "void"), and
its column is lower than the headroom (3 blocks; a wall that rises to the ceiling is not roofed). Per roofed cell
(cx, cy), with h = `hash01` and px the Game Boy pixel (8 to a block; the lid at 24):

| Piece | Rule (the mod's at its AIRY room, then CD8's scale and CD6's clamps) |
|---|---|
| Roof | rock = 24 − round(0.75 × (2 + 9·h(cx,cy,241))) px, so 16–22; on all four blocks of the cell, a `hang` box from the lid down to rock (y0 = 2·(rock − 16) in the block under the ceiling); tint 0 when h(cx,cy,263) ≥ 0.5, else 1 (the mod's 0.52–0.74 panel shade) |
| Spike | when `floor(h(cx,cy,269) · 5) == 0`; length L = round(0.75 × (5 + 10·h(cx,cy,271))) px; centre x = 4 + 8·h(cx,cy,251), z = 4 + 8·h(cy,cx,257) px into the cell (its block: the one holding the centre) |
| Stalagmite | when h(cx,cy,277) < 0.45 and the cell is SOLID or COUNTER (CD6, CD10): from the cell's top up to min(top + L, rock − 2) px; half-width 2.6 px (5) |
| Stalactite | otherwise: from rock down to max(rock − L, clear), clear = 16 px over a path (WALK, LEDGE, WATER) or the cell's top + 1 px; half-width 2.2 px (4) |
| Dropped | a spike shorter than 2 px after its clamps (counted) |

- The pieces sit in the block under the ceiling (feet + 2): `hang` keys, decor carriers. In a dark map (Rock Tunnel)
  nothing changes about darkness: no lights there, none added. In a lit map, a decor carrier on a light's position is
  `lit` (CD15).
- The ceiling stays `tile_full` at feet + 3 (I2), and so does the ring; the rock hangs as a second surface under it.
- **Culling:** a roof box's side is covered by its neighbour's when the neighbour hangs at least as low, so only the
  steps show (the mod's skirts); the boxes of one cell share a height and hide each other's sides.

### 5.2 Forest canopy: `Canopy` (CW2)

Applies to an open-air interior whose `env.canopy` is set (Viridian Forest), under the open sky. Cells: the map's,
plus its enclosure ring (CW3 widens the ring to the mod's four cells).

- **Leaf tiles:** every map cell's top-left tile whose class (the profile's, CD4) is `tree`, `wall` or `cliff`, or
  whose column is two blocks or more; ranked by frequency (ties: the lower id), the first five. A cell's leaf is
  `palette[floor(h(cx,cy,23) · n) mod n]`. No candidate: no canopy (counted).
- **Lower surface:** a cell is a **well** when `floor(h(cx,cy,5) · 9) == 0`; otherwise its underside is at
  40 + 22·h(cx,cy,2) px (5–7.75 blocks), rounded to whole pixels.
- **Upper layer:** at 74 + 6·h(cx,cy,61) px; the canopy's top is 80 px (feet + 10).
- **The mass (CD13):** a cell's columns hold `hang` boxes and full leaf cubes from its underside (a well's: the upper
  layer) up to 80 px, in its leaf tile. Shade: the mod's 0.30 + 0.26·(h−40)/22 + 0.22·h(cx,cy,31) as tint 0–2 for the
  lower surface; tint 0 for a well's upper layer (lighter: the mod's 0.62–0.82).
- **The curtain:** the enclosure ring stays `tile_full` up to feet + 3 (collision, I2) and carries leaf cubes above it
  up to the canopy's top, in a palette leaf, tint 2 (the mod's 0.30–0.44 rim). Nothing can be seen past the edge.
- **Volume:** an open-sky interior's volume, its biome box and its shape-map grid reach feet + 11. The packer already
  keeps the column above an open-sky interior free.

### 5.3 Vines (CW2)

| | Leaf strands (the mod's "vine blocks") | Stem-and-stub strands (its "billboards") |
|---|---|---|
| Cells | non-well cells, `floor(h(cx,cy,163) · 9) == 0` | all cells except wells, `floor(h(cx,cy,191) · 5) == 0` |
| Top | the cell's underside | the cell's underside (CD14) |
| Length | long when h(cx,cy,229) < 0.28: max(7, top − 3 − 4·h(cx,cy,233)); else 7 + 15·h(cx,cy,167) | 10 + 16·h(cx,cy,211) |
| Position | x = 3 + 10·h(cx,cy,173), z = 3 + 10·h(cy,cx,179) | x = 3 + 10·h(cx,cy,197), z = 3 + 10·h(cy,cx,199) |
| Look | half-width 1.2 + 0.9·h(cx,cy,181); turned h(cx,cy,193)·π; 1 + floor(2·h(cx,cy,197)) stubs at 0.25 + 0.4·h(cx+i,cy,199) of the length, side by h(cx,cy+i,211); the cell's leaf; shade 0.40 + 0.26·h(cx,cy,191) as tint | 5 px wide, turned h(cx,cy,223)·π; the mod's stem and stub pattern in its three greens |
| Clamp (CD6) | the tail stays at or above max(16 px, the cell's top) | the same |

Pixels as in §5.1 (no scale: the forest is open). A strand spans the blocks from its top to its tail, one key per
block. A strand clamped to under 2 px is dropped (counted).

### 5.4 The forest's apron (CW3, optional)

The mod's ring of further wood: four cells past the map edge, each mirroring the nearest map cell (a solid one as a
two-block box in its own tile, 16% of them with a taller inset box 3–5.5 blocks; a walkable one, 30% of them with an
inset trunk), a floor in the forest's grass tile, both canopy layers continuing over it, and the curtain moving to its
outer edge. It needs the open-sky footprint widened by 8 blocks (inside the packer's 16-block gap) and the enclosure
ring turned into an invisible wall where the apron shows through. Decided by the user after seeing CW2.

### 5.5 The wild zone's vines (CW4)

`ZoneBlock` gains vines (one per facing). On the forest zone's scenery trees and hedges, a vine hangs from a leaf
block's side wherever the block beside it is air, 2 to 4 blocks long, never lower than feet + 2 (they are climbable:
a player who can't jump never reaches one), at the canopy's density (one leaf face in nine, by the position hash).
Vine spreading is already off (`KantoGameRules`).

## 6. Milestones

### CW1: Cave rock

- `CellHash`; `Dress.hang`, `MapShapes.hung`/`hungMask`; WorldPlan's decor above any cell and `lit` decor; the `hang`
  kind (key, schema, geometry, face masks); `CaveRock`; `ShapeParams` p4 = `kb-shapes/9`; report fields.
- **Tests:** JUnit for the hash (fixed values, both argument orders), the rule on made-up caves (which cells, clamps on
  paths, stalagmites only on rock, light positions lit, nothing on a non-cave or open-air map), the geometry (quads per
  piece, culled steps, never below the clear line), the plan's carriers (no collision anywhere it adds one); one
  GameTest: a synthetic cave's rock blocks are decor with no collision, and walking over every path cell still works.
- **Commit "CW1: cave rock".**

### CW2: The forest canopy and vines

- `Env.canopy`; `Canopy`; the `vine` and `strand` kinds; the open-sky volume; `kb-shapes/10`.
- **Tests:** JUnit (leaf palette with and without profile classes, wells, heights, the mass and its culling, vines'
  clamps and spans, the curtain); a GameTest over a synthetic canopy map. **Full suite** (checkpoint).
- **Commit "CW2: the forest canopy and vines".**

### CW3: The forest's apron (if the user wants it)

As §5.4. **Commit "CW3: the forest's apron".**

### CW4: Vines in the wild zone

As §5.5. **Tests:** JUnit over `ZonePlan` (vines only on leaves, never below feet + 2, never on the maze's closing
hedges' walkable side); the zone GameTest. **Commit "CW4: vines in the wild zone".**

### CW5: Finish

Docs (CLAUDE.md's sources line, REFERENCES.md, NEXT_STAGES.md §3, ROADMAP Stage 6, PLAYTEST.md, PROGRESS.md); the
full suite; a real-world build on the pod's test install (budgets: quads per block and section, the shape map on the
wire); a short video of Mt. Moon and Viridian Forest. **Play-test (the user):** Mt. Moon, Rock Tunnel with and without
Flash, Seafoam's water, Viridian Forest end to end, the wild zone. **Commit "CW5: finish".**

## 7. Risks

| Risk | Answer |
|---|---|
| The shape map on the wire (78% of its budget before this plan) | Pieces repeat per cell (a cell's four roof blocks share a key); measured on the real world in CW5; if over, the roof's heights take fewer steps, then strands move to client-side variants |
| Quads per section | Culled steps; the mass's inner faces culled; checked by the budget gate |
| A light lost under a piece | `lit` decor (CD15); tested |
| A spike or vine in a player's face | The clear line (CD6, CD9), tested over every path cell of the synthetic maps |
| Rotated quads on the 1/16 grid | Corners rounded; a strand stays within its block's ±8 overflow |
