# Plan: Dramaless Shape's profile in the Kanto world

- **Status:** decided by the user on 2026-10-04 (§2.1); P1 starts at once.
- **What it delivers:** the world's shapes follow Dramaless Shape's hand-authored profile
  (`data/voxel_heights.lua`) wherever it says something: every tree it calls round becomes a hull, its props, signs
  and fence posts become standees of their own depth, buildings become band-table models with real roofs and plain
  side walls, interiors get furniture at their drawn heights, stairs, bookcases and reliefs. Where the profile is
  silent, or the mod isn't installed, our own rules stay the fallback.
- **Why:** the user's play-test after the voxel plan (2026-10-04): trees at Pallet Town and on Route 1 stood as
  folded cubes, and buildings were painted boxes. The counts behind it are in PROGRESS.md ("Profile plan").
- **Source:** two research passes over `refs/DRAMALESS_SHAPE-main/` (v2.0.4, MIT for the 2.0 line), summarised in
  §5 in our own words: `lib/TileShape.lua` (classes), `lib/Structures.lua` (geometry per class),
  `lib/Buildings.lua` (band tables). No code is copied; the table is read at runtime from the user's installed
  copy of the mod.
- **Size:** four milestones, P1–P4.

---

## 0. How to execute this plan

- The voxel plan's §0 applies unchanged ([VOXEL_BLOCKS_PLAN.md](VOXEL_BLOCKS_PLAN.md) §0.1–§0.4), with the changes
  below. Milestones run in order; one commit each, `P<n>: <title>`, with the harness's attribution trailer.
- **The profile is allowed, its code is not.** The voxel plan's "no table from Dramaless Shape" (its §0.2, VD3) is
  replaced by PD1: the table is read at runtime from the installed mod. Its Lua code is still never copied: every
  rule here is re-implemented in our own Java from §5.
- **The profile is never committed and never run.** The repo holds no copy of it (tests use made-up profiles).
  gen1-bridge reads it as text; Java reads it as data (`scene/profile/LuaData`), which refuses anything but data.
- **Masks never travel.** The profile's `figures` and `mounted` masks are traced from the game's art, so they are
  art: they never enter the shape index or any message (§2.2, PD5).
- **Agents never look at the profile's values.** Tests, logs and reports name classes, counts and hashes; never
  tile ids, grids or masks. Reading the format (field names, the header comments) is allowed.
- **Rebuilds:** every milestone changes the planner, so the user's world shows it only after a new world or a
  rebuild, which the user starts.

## 1. Scope

| Area | What is built | Milestone |
|---|---|---|
| Plumbing | gen1-bridge reads the installed mod's table and exports it (`profile/dramaless`); `LuaData`, `Profile`; the scene carries per-tileset classes | P1 |
| Outdoor units | Pinned hulls (`cylinder`), standee pools (`prop`, `billboard`, `signpost`, `post`, `cutout`, `bike`, `stool`, `console`), `prop_ground`, `prop_bg`; the tree fallback the user chose (option 1) | P1 |
| Buildings | Band-table buildings (`buildings`, roof path) matched by tile grid, first claim wins; the model of §5.5; doors carved | P2 |
| Boxes and interiors | Pinned uprights and tops at class heights (`wall`, `cliff`, `counter`, `table`, `desk`, `ledge`, `roof`, `bed`, `backrest`), `relief`, `bookcase`, stairs, hull variants (`stump`, `can`, `canopy`, `planter`), furniture band tables (roof and parts paths) | P3 |
| Finish | Docs, PLAYTEST, the user's install, clean-up | P4 |

**Out of scope:** `figures` and `mounted` (PD5); Dramaless's renderer (shading, water reflections, day/night);
its `companion` tags (metadata only); conditional reads of tiles a building claim repainted (we never repaint).

## 2. Decisions

### 2.1 The user's (2026-10-04)

| Id | Decision |
|---|---|
| PD1 | Use Dramaless Shape's hand-made table, read from an installed copy of the mod (not copied into this project). Our rules are the fallback when it isn't installed or says nothing |
| PD2 | If reading it at runtime ever proves impossible, copying it is acceptable and the project stays personal (not needed by this design) |
| PD3 | The tree fallback is option 1: a repeating cell two blocks high becomes a unit; round ones are hulls, sprite-like ones cutouts, the rest unchanged |
| PD4 | Buildings follow the table's band tables (option 3) |

### 2.2 Plan defaults (pre-approved)

| Id | Default |
|---|---|
| PD5 | `figures` and `mounted` are left out: their masks are art, and the shape index travels |
| PD6 | The mod stays **disabled** in Gen1Recomp (it would otherwise run its renderer, hotkeys and day/night hooks in the hidden game); gen1-bridge reads its folder either way |
| PD7 | Collision never changes (voxel plan I1): a solid cell keeps at least two blocks; a shape lower than its column leaves the rest of the column empty to the eye |
| PD8 | Our placements stay where the self-check needs them (an object's slab ends at the south edge of its lowest pixel row); Dramaless's depths, pools and segmentation apply |
| PD9 | Our hulls keep their half-block depth steps (voxel plan deviation 32, the quad budget) |

## 3. Architecture

```
gen1-bridge (in Gen1Recomp)                builder (server)                       each client
---------------------------                ----------------                       -----------
mods.loaded: loader.mods.DRAMALESS_SHAPE   export bundle → Scene + Profile        ShapeTable from its own
  → text of data/voxel_heights.lua           → classes per tile (pixel-free)        export: hulls, standees,
export: profile/dramaless {mod, version,     → ShapePlanner kb-shapes/6..           buildings from the key's
  file, enabled, text}                         keys carry class, pool, params        class and parameters
                                             → shape_index.json, shape_map.bin
```

- **Who decides what.** The planner decides each position's class and volume from tile ids and the profile only
  (pixel-free, as the voxel plan's I8 asks), with one exception: the tree fallback (PD3) asks a probe whether a
  repeating cell's drawing is round or sprite-like (§5.4), because a pixel-free rule would turn 6,000 plain wall
  cells into units. The probe runs on the builder's own export; its answer is written into the keys.
- **What travels.** Keys carry the class and every parameter the geometry needs (depths, pools, background shades,
  band tables). A client without the mod draws the same shapes from its own art.
- **What never travels:** the profile file, masks, pixels.
- **The fallback.** With no profile resource, the planner runs exactly as kb-shapes/5 plus PD3.

## 4. Formats

### 4.1 The export resource

`profile/dramaless` (protocol/export/profile.schema.json): `mod` (`DRAMALESS_SHAPE`), `version`, `file`
(`data/voxel_heights.lua`), `enabled`, `text` (at most 1 MiB less the page's slack). Absent when the mod isn't
installed. gen1-bridge logs `export.profile_found {mod, bytes, enabled}` or `export.profile_absent {mod, reason}`.

### 4.2 The profile, as read

`scene/profile/Profile` (parsed by `LuaData`, validated): `version`; class heights (code fallbacks, then the file's
`heights`, then a tileset's own `heights`); per tileset id: tile → class pins, conditional pins (`when_above`,
`when_below`: tile → [{above|below: [tiles], class}]), `prop_ground` (tile → tile), `prop_bg` (tile → shades),
hull parameters (`stump_cap`, `can_cap`, `can_base`, `can_height`, `can_well`, `can_taper`), bookcase flags
(`bookcase_relief`, `bookcase_backfill`); `buildings` per tileset (an ordered list of entries). Unknown classes and
fields are counted (`profile_unknown`), never fatal. `figures`, `mounted` and `companion` are skipped unread
(PD5).

### 4.3 Keys

- **Unit variants** (kb-shapes/6): `g<sides>[i][r][s][t]` as before, then `_<class>` for a pinned or probed unit,
  then `_bg<hex>` (the background shades of `prop_bg`, bit k = shade k) when the profile names them. At most 32
  characters (ShapeKey's rule). `CellUnits.Context` reads the flags before the first `_` only.
- **P2** places band-table buildings as V7's `tmpl` keys whose template entry carries `band` (§5.5).
- **P3** (kb-shapes/8):
  - `box:<a>|<u>,<n>,<s>,<w>,<e>|<h>`: a box `h`/16 high without a bottom, its top wearing `u`, its sides `n`, `s`,
    `w`, `e` (each the tile's lowest h/2 rows); -1 is no face, all -1 draws nothing (an empty carrier). The class
    boxes and bookcases. `|v=p` sinks the south face's panes (needs the south tile's art).
  - Unit variants gain `_k<params>`, two decimal digits per value, after the class: a stump's cap rows; a can's cap,
    base, height, well and taper; a stair's or relief's height. A class with an underscore (`stair_e`) is read back
    from its parts.
  - A template entry may carry `hull` (a canopy or a planter: `kind`, `height`, per position `solid`), or `band`
    with `parts` (a furniture entry: `plane`, `tray`, `desk`, `wall`, `list`).

## 5. Component designs

### 5.1 gen1-bridge

- `lib/kb_engine.lua` `engine.modDataFile(loader, modId, rel, maxBytes)`: the loader that mods.loaded's payload
  carries (`src/mods/Loader.lua:2163`) lists every mod found under `mods/`, enabled or not
  (`loader.mods[id] = {manifest, path}`, `:545-566`), and reads through `loader.fs` (love.filesystem). The sandbox
  keeps `mod:read` inside a mod's own folder (`:1712-1716`), so this is the way in. Returns text, version and
  enabled, or a reason code. Engine internals: re-checked on every pin change.
- `export/kb_export.lua`: reads at mods.loaded (boot and hot reload), keeps the text, exports it with the bundle.

### 5.2 Reading the profile (Java)

`LuaData` accepts an optional `return`, one table constructor, and only nil, booleans, numbers, strings and
tables, with comments (`--`, long brackets). A name that isn't a field key, an operator other than a number's
minus, or a call is an error naming a line and column. `Profile.parse` validates types and ranges and keeps what
§4.2 lists.

### 5.3 Classes per position

Per map, per art tile (tx, ty), first match:

1. a conditional pin of the tile: `above` reads (tx, ty−1), `below` (tx, ty+1), from the map's own tiles, the
   border block's pattern outside the map; all `above` rules before all `below` rules, each in file order;
2. a pin of the tile;
3. none (our own classification decides).

Classes are art modes (research, TileShape): flat (`ground`, `water`, `void`), top (`ledge`, `roof`, `bed`,
`backrest`), upright (`wall`, `cliff`, `tree`, `fence`, `sign`, `counter`, `table`, `desk`), hull (`cylinder`,
`stump`, `can`; `canopy`; `planter`), standee (`billboard`, `signpost`, `stool`, `prop`, `cutout`, `bike`,
`console`), `post`, `grass`, `flower`, `relief`, `bookcase`, stairs (`stair_e`, `stair_w`, `stair_down_e`,
`stair_down_w`).

### 5.4 Units (P1)

1. **Pinned:** a SOLID cell one of whose tiles has a unit class (hull or standee, or `post`) is never structural:
   it leaves Tier A's solid tiles before runs and regions are measured (a sign beside a house no longer joins the
   house's region) and stands as its own unit, two blocks high. The class is the top-left tile's when it has one,
   else the first unit class in row-major order. Its ground is `prop_ground` of its tiles when the profile names
   one, else today's vote. A pin on a walkable cell is counted (`pin_not_solid`), never forced.
2. **Today's rule**, for a SOLID cell whose two-block columns have no roof ("low") and that the profile pins to
   nothing: a cut tree, a cell whose tiles all wear the foliage hint, or a lone cell.
3. **PD3**, likewise: a cell whose four tiles repeat elsewhere in its 4-connected region of solid cells, when the
   probe says its drawing is round (a hull) or sprite-like (an object). The probe is `CellUnits.build` on the
   builder's own export.

A cell the profile pins only to other classes (walls, counters, tables, …) stays what Tier A makes it until P3. The
geometry (each client, `CellUnits.build`):

| Class | Geometry |
|---|---|
| `cylinder` | the hull (tried even where the round test says no); gates as today |
| standee pools | the object rule without its applicability test, the pool's depth (`billboard`, `stool`, `console` 10; `prop` 5; `signpost`, `bike` 2; `cutout` 1 voxel), the pool's background (below); 8-connected components |
| `post` | the object rule, depth 6, white the only background, seeded from all four sides |

**Standee background** (forced mode): black is always solid; the background shades are `prop_bg`'s when the
profile names them for a tile of the cell, else the non-black shades found on the drawing's border, else white; a
4-connected flood from the border through background shades marks the background, so enclosed pixels stay.
`cutout` is strict: dark and light are always background, white only where a white run reaches the border, and
only the largest component stays.

Gates: a pinned standee must cover its drawing (0.95 of its pixels; cover, not match, since its thick slabs may hide
one another in the top-down view), an unpinned object must show it (0.95), a hull must cover 0.4 of it, a block
holds at most 96 quads; anything else draws its column version. Never a hole.

**Also in P1** (so the maps' structure scores keep meaning something once units leave them): a counter folds as a
run one block high (its panel on the front and sides, the row above on top: the research's `counter`), and the
self-check lets a ledge lip show its art lifted by its own height.

### 5.5 Buildings (P2)

From the research (Buildings.lua), restated in our units (one voxel = one Game Boy pixel = 2/16 block; x east, y up,
z south; z = 0 the drawing's north row):

**Planner (pixel-free).**

- **Match:** for each map whose tileset has `buildings`, the entries in list order; each tries every tile offset of
  the map body (exact ids; a row compares as many ids as it has; the grid is `#tiles[1]` wide); a placement whose tiles
  another claim holds, or that touches a swap site, is refused; first claim wins (entry, then row, then column). The
  claim is every tile of the grid except `keep` ids. An entry without `tiles` is skipped and counted.
- **Claimed tiles never reach Tier A** (as pinned cells don't, §5.4), units or V7 templates.
- **Volume:** per claimed tile column, from feet level: the higher of the Tier A height it had and the model's bound,
  `ceil((8·(rows of topRows + tiles) − roofRows + slab) / 8)` blocks; solid cells on `shape_full`, walkable cells
  (doorways) on `decor` (I1: nothing collides in a walkable cell). One row north and south of the footprint holds the
  overhang (`ledge` two voxels proud, `frontEave`) on `decor` where those cells are walkable; elsewhere the overhang is
  cut (counted).
- **`claimOnly`:** the claim keeps its Tier A height on `shape_full`, with no geometry (the tower's other half is drawn
  by its `topRows` twin); the floor wears the ground vote.
- **Keys:** `tmpl` keys (V7's kind) whose entry carries `band`: the band table (`roofRows`, `roofBack`, `roofFront`,
  `roofCycle`, `slab`, `frontEave`, `ledge`, `seal`, `depth`/`depthPx`, `panes`, `scrub`), the composite grid
  (`topRows` + `tiles`), the doorways, the overhang rows and the volume; its signature covers all of it.

**Geometry (each client, its own art; no pixel leaves the client).**

1. **Compose** the drawing (`topRows` then `tiles`); shades: 3 black, 2 dark, 1 grey, 0 white (our `ArtSource`).
2. **Flood** from the border (minus `seal` sides) through grey and white; what it can't reach is inside. `scrub`
   rects then become grey and inside, wearing the first inside grey pixel outside every rect.
3. **Measure:** per column the first inside row within `roofRows` (its roof elevation; none: no roof there); the
   ground line (one past the last row from the bottom up to `roofRows` with an inside pixel); `wallH = ground −
   roofRows`; `ytop = wallH − 1 + slab`; panes: 4-connected inside non-black regions in facade rows under 24 px each
   way (none when `panes = false`); per shade the first inside pixel (the rim's and the slab's texels); a facade's
   inside black pixel, de-outlined: the first inside non-black pixel within 3 toward the middle; `D` the footprint
   depth (`depthPx`, else 8 × (`depth` or the grid's rows)).
4. **Voxels**, in this precedence: the roof slab (in roofed columns, `slab` voxels under `T[x] = ytop − top[x]`,
   z from 0 to `D − 1 + frontEave`: its top wears the drawing row chosen by depth, `roofBack` rows along the north rim,
   `roofFront` rows along the south rim, `roofCycle` cycled between; the rim (outermost roof columns, first and last
   z) black on top and bottom, dark between; the rest dark); walls stop under the roof; the awning (`ledge` rows, two
   voxels proud north and south of the footprint); the facade (rows under the roof band, extruded over `D`: the
   front and back layers the drawing, the layers between the de-outlined pixel; a pane's front voxel removed, so it
   sinks one).
5. **Ours:** each doorway (a walkable cell of the footprint) is carved two blocks high, and its facade pixels are
   moved to the doorway's back, so the door art stands where the player walks in; the grid is sliced into the
   template's blocks (the overhang rows included) and merged as every voxel model is.
6. **Gate:** the 96-quad cap per block; over it, plain cubes of the claimed solid tiles (I3). The top-down self-check
   does not score band buildings (they no longer reproduce the drawing); their drawn pixels are left out of the map's
   structure score, holes still count.

Without a profile entry, V7's templates stay.

### 5.6 Boxes and interiors (P3)

From two more research passes (refs/_research Q_dramaless_classes.md, R_dramaless_parts.md: `lib/TileShape.lua`,
`lib/ChunkMesher.lua`, `lib/Structures.lua`, `lib/Buildings.lua`), restated in our units; nothing copied. P3 is
`kb-shapes/8`.

**Headroom** (`ShapeHeights`): the surface volume outdoors (31 blocks from feet level); an interior's ceiling (three
blocks); an open-air interior (a forest, V7) four, up to the ceiling's own level, which nothing fills there. Dramaless
has no ceiling; the clamp is ours. A column never passes its map's headroom.

**Class boxes** (`ClassBoxes`, pixel-free): a SOLID or COUNTER tile pinned to `cliff`, `tree`, `fence`, `sign`,
`counter`, `table`, `desk` (upright), `ledge`, `bed`, `backrest`, `roof` (top) or `bookcase`, not claimed, not in a
unit's cell, leaves Tier A and stands as `box` keys, planned after every other column so that culling sees them:

- Height: the class height (the profile's, then its tileset's), under the headroom; a COUNTER cell's one block.
  Collision: a solid cell's blocks over the box, up to two, are empty carriers (PD7).
- Upright fold (ChunkMesher): `front` = the run's south tile, at most six same-class steps; band k's source is the
  tile k rows north of the front when it is the same class, else the column's own. The top wears its own row
  unless it lies in the face block (the southmost floor(h/8) rows): then the row north of the block; a whole run
  folded takes the upright pin north of the run, else the run's north row.
- Top classes: every face its own tile (a partial band its lowest rows).
- Bookcase (Structures): each run cut from the south into ranks of at most four rows; a rank's box stands on its
  two southmost rows, band b its row b north of the front; the northmost rank's cap (the tile directly north, a
  table pin or an unpinned solid tile, not claimed) one band more and its top; the rows behind the box are empty
  carriers on the ground's floor, or (`bookcase_backfill = "above"`) the column of the tile north of the rank. Its
  front face sinks its panes one pixel (`v=p`) unless `bookcase_relief = false`: a pane is a 4-connected region of
  non-black pixels the face's edges don't touch, its reveals wearing the pixels beside them.
- Culling: a side whose neighbour (a box, or a column's full cubes) stands at least as high is left out.
- `wall` stays Tier A's (the profile's fallback class for any solid tile). A pin on a walkable or ledge tile changes
  nothing: its collision stays, so its floor stays flat (counted).

**Units** (from P1's pinned path, behind the P3 flag): `stump` (its tileset's cap rows stripped from the hull and
laid on its top by depth), `can` (cap, base rebuilt as the foot, the body repeated up to its height, tapered, the
well hollowed two voxels thick, the mouth laid on the rim and the well's floor), `relief` (the drawing lying flat:
what a flood from the cell's border through the border's non-black shades can't reach stands as columns of the class
height), and on walkable cells (decor carriers, the ground's floor) the rising stairs `stair_e` and `stair_w` (four
steps a quarter of the class height higher each, every voxel the pixel the drawing shows at its column and height).
A sunken stair would need its floor opened, which collision forbids: counted, left flat. A stair whose bottom block a
story block takes stays out.

**Two-cell hulls** (`BigHulls`, templates with `hull`): a canopy (a solid cell whose top-left tile is a canopy, its
cells east, south and south-east solid with hull or canopy classes) is our hull over the 32 × 32 drawing, four blocks;
a planter (a planter cell over a planter cell) is carved over its 16 × 32 drawing, each half masked on its own, the
crown rows no deeper than five voxels and the pot round, standing on the south cell; the north cell keeps two blocks
of collision.

**Furniture** (§5.5's claims, the parts path): the drawing, flood and measures of §5.5; a base piece (a tray: an open
box, its rim the row its depth shows; a desk: its wall band, base rows, fascia and lid; or none) and the parts riding
its plane in list order (flat, box, iso, upright), the last write winning; nothing leaves the plot or goes under the
floor; no doorway carved. The volume is the pieces' highest layer, under the headroom.

**The self-check** leaves class boxes and two-cell hulls out of a map's structure score (their heights are the
profile's, not the drawn rows), as band buildings are. Holes still count.

### 5.7 Reports and checks

The build report's `profile`: present, mod version, enabled, a short hash of the text, tilesets and pins counted;
`units_by_source` (pin, hint, cut, lone, probe; from P3 `stair_down`); `buildings` (claims, P2); `classes` (P3: boxes by
class, bookcase ranks and empty rows, canopies and planters, pins left flat). The self-check and budgets of the voxel
plan stand.

### 5.8 The test install

`local/gen1recomp-e2e/mods/DRAMALESS_SHAPE` holds a copy of the user's mod (git-ignored), disabled by a one-time
driver (`game.mods:setEnabled`, persisted by Gen1Recomp for the next boot). `tools/e2e` reports whether the export
carried the profile and the build used it.

## 6. Milestones

### P1: Profile and outdoor units

- [G] `engine.modDataFile`; the export's `profile` resource; lupa tests with a stand-in loader.
- [P] `protocol/export/profile.schema.json`, PROTOCOL.md, the catalog.
- [B] `ExportProfile`, `LuaData`, `Profile`, classes per position; planner `kb-shapes/6` (§5.4); the probe;
  report fields.
- [C]/[B] `CellUnits`: classes in variants, forced standees, posts.
- [T] JUnit (reader, profile, classes, units, standees), the synthetic world's made-up profile, e2e with the
  mod installed in the test install.

**Checks:** green with `--e2e`; budgets met. **Play-test:** trees round in Pallet Town and on Route 1, the town
sign thin, fence posts as posts, the cuttable bush a cutout. **Commit "P1: profile and outdoor units".**

### P2: Buildings

As §5.5. **Checks:** green with `--e2e`; no holes; budgets met. **Play-test:** houses, the lab, a Center and a
Mart from all four sides, walking in and out of doors. **Commit "P2: buildings".**

### P3: Boxes and interiors

As §5.6. **Checks:** green with `--e2e`. **Play-test:** a Center, a Mart, a gym, Viridian Forest's canopies, stairs.
**Commit "P3: boxes and interiors".**

### P4: Finish

Docs (CLAUDE.md, REFERENCES.md, PACKS.md where packs and the profile meet, PLAYTEST.md, PROGRESS.md), the user's
own install (with their go-ahead). **Commit "P4: finish".**

## 7. Risks

| Risk | Answer |
|---|---|
| A future Dramaless version changes the table's format | Parse leniently, count what's unknown, fall back per field; the report names the version |
| More units exceed the quad budget | The voxel plan's ladder (R1): coarser hull steps, then fewer probed units |
| The mod is enabled in the hidden game | It renders there; gen1-bridge still pins the display options. The report and F7 show `enabled`; PLAYTEST says how to disable it |
| A pinned class lands in a cell our world treats differently | Counted and skipped, never a hole and never a collision change |
