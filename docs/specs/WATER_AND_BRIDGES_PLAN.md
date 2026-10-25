# Water and bridges: the plan (WB0–WB8)

Written 2026-10-25, after FA9 (FIELD_MECHANICS_AUDIT_PLAN.md §14). It solves the two things FA9 left: the four level
places that were "checked and left" (Ever Grande's waterfall and its kin, Lilycove's shore, Slateport's two-height sea,
Route 109's beach chair) and the bridges (BRIDGES_PROBLEM.md). Both turn out to be about water: where it stands, what
crosses it, and what stands over it. Every number below was read from the repo, the engine's reference source
(`refs/gen1recomp/src/core/game3/collision.lua`), the heights survey (`heights_survey_emerald.json`) and the levels dumps
(kb-hub `work/landscape/levels_all11`, `levels_r7/levels_r7pin`, models table v15); what a pod must confirm is marked
*(WB0)*. Decisions are defaults the user may change (§3); the phases are §4.

## 0. In one paragraph

The four level places are three rule mistakes and one collider, not four pins. (1) Lilycove's "sea" under the plateau is
a lagoon on the map's north edge with no map beyond it: the solver counts every body of water that reaches an edge as the
sea and sinks it to the floor; a body is the sea only where the game connects across that edge onto water, else it is a
pond standing with its bank. (2) Slateport's sea is one level in its own frame; the chain splits it, because at the
Route 110 seam five walking pairs outvote four water pairs and the Route 134 seam carries a loop's leftover. The sea has
to be the chain's datum: water pairs decide a seam's step, walkers take the ramps SC2 already builds; the 93 harbour cells
at the quay's level are two enclosed basins, two wet pins. (3) A waterfall's cells are skipped by every water rule and sit
at the lower water's level between the pool above and the sea, so the pool ends in a drop of 9 or more; a waterfall
becomes a stepped cascade (3 blocks a cell, the water's own art on the steps) that the game's forced moves climb and
descend, with a wall at its foot that opens only during a forced move. (4) On Route 109 a surfer stands half a block below
the sand and a seat's top half a block above it: a 1-block step; a seat a surfer can land on keeps its art and loses its
collider. The bridges become **two-floor cells** as the user chose (HEIGHTS_ALL_GAMES_PLAN D4, Q3): the layout gains
`decks` (per bridge cell the floor under the deck and the deck's elevation), the index answers for both floors by height,
the client puts the player on the floor the hidden game's elevation names (a new optional `elev` in `player.pos`,
`snapshot` and `warp`, Gen 3 only; `npc.add` too for the puppets), the builder leaves the column under the deck open
(water or path, 2 blocks of air, a 1-block deck, walls per floor), and the parity judges a bridge cell once per floor.
Emerald only: Crystal and Blue have no layers, so nobody passes under a bridge in those games; their bridges are right as
built.

## 1. What the investigation found

### 1.1 Lilycove City: a lagoon, not the sea

- The two broken surf landings are on the north edge: a strip of water at (39–50, 0–1) under ground at row 2. In the v11
  dump (`levels_r7pin`) the water stands at 0 and the row-2 ground at 9 (the review's pins (40,2) 9, (34,4) 9, (70,1) 9:
  the city's north rim is a plateau); no cliff is drawn between them (the review picture `review/EM_LILYCOVE_CITY/r1`,
  chunk r0_c2).
- That water is its own body: 48 cells (x 39–64, y 0–3), closed off by rock from the east sea (246 cells, x 62–79,
  y 5–35) and the south sea (337 cells). It touches only the map's north edge, and Lilycove has no north connection
  (Route 121 west, Route 124 east). The game lets a player surf on it from the two elevation-3 cells and land back on
  them (`collision.lua:989`, `isSurfDismount`: land only on elevation 3).
- The cause is one line of `RaisedRegions.solve`: "the sea (every body reaching the map's edge that no waterfall feeds)
  is one body, one level" (RaisedRegions.java ~612–633, `edge |= c % w == 0 || ...`). A body that reaches an edge the game
  never crosses onto water is a pond on its terrace, and here the terrace is the plateau. At 9 the lagoon meets the
  plateau level, the two landings work, and the drawing is honoured (no face drawn, so the water and the grass are one
  level). Nothing else moves.

### 1.2 Slateport City: the chain splits the sea

- In Slateport's own frame the water that reaches an edge stands at 0 in every dump; the city at 3 (the reviewer's quay),
  the beach at 0. The 93 cells at 3 (LEVELS_PROBLEM_AREAS.md §1) are two enclosed basins, the north-east harbour (41 cells,
  x 32–39, y 7–14, behind a solid row 6) and the east basin (52 cells, x 23–34, y 17–25): ponds "on their terrace", the
  quay. No surfer reaches them; they are cosmetic.
- The seam steps come from `MapChain.seams()` (MapChain.java ~636–680): a seam's step is the commonest difference over
  all its touching pairs, walking and water alike. Route 110 | Slateport has 9 pairs: 5 walkers (Route 110's path at its
  water's level, Slateport's at 3 over its water) and 4 water (0 against 0). The walkers win, the paths meet, and the
  water is left 3 apart ("4 of 9, water, step 3"). Route 134 | Slateport (36 pairs, all water, step 0 on its own) carries
  a loop's leftover instead: the chain's leftovers go to the seam Kruskal takes last, and `MapChain.joins` can build a
  stair only on walkable pairs, so a water seam keeps it ("water a block apart across three seams", FA9: Route 134 |
  Slateport, Route 107, Route 4 | Cerulean).
- So the sea must be the datum. A seam with water pairs takes its step from them (the two maps' sea meets exactly); the
  walking pairs left apart are ragged cells, which `MapChain.mend` (SC2) already ramps, the lower map's cell climbing to
  the higher's. Seams between maps whose water is one sea get the chain's first turn, so a loop's leftover lands on a land
  seam, where a join stair or a mend ramp closes it.

### 1.3 The waterfalls: Ever Grande, Routes 114 and 119, the Safari Zone's south-east (and Meteor Falls)

- In the v11 dump Ever Grande's fall column (x 0–2, rows 60–63) and the pool above it stand at 0 with the sea, while the
  landing beside them is at 3 and the path climbs 9, 12, 15 beyond. The solver skips a waterfall's cells in the water
  flood (`in.falls()[s]`: "water no body flows across"), so the body above is parted from the sea (right) but the fall's
  own cells take no level at all, and wherever the pool above has been lifted to its bank the pool ends in a vertical drop
  onto the fall: FA9's "a drop of more than 8 blocks" and "a wall or a height step with no ramp" at Ever Grande.
- What the game does: a surfer at the foot uses Waterfall (`act.field waterfall`; `fieldmoves.lua:81`
  `isFacingWaterfall`) and the engine walks the player up every fall cell as a forced move; a surfer entering the fall from
  above is pushed down the same way (FA7: "waterfalls 78 (the push)"). Without the move the step up is refused. The
  client follows forced moves with the `ForcedMover` (horizontal legs; the player's own physics does the step-up: a 3-block
  outdoor ramp is three half steps a block, exactly what the Mach Bike slopes use), and teleports when the mover sticks.
- So a waterfall is a ramp of water: each fall cell climbs 3 blocks toward the pool (`MapLayout.Ramp` on a water cell,
  `dir` the way up), its steps built as step colliders carrying the cell's own art (the fall tile), so the cascade looks
  like falling water. Going down, the push walks the player down the steps; going up, the Waterfall move's forced walk
  climbs them. A wall at the fall's foot keeps a surfer out unless a forced move is running, as the `BikeWallBlock` is a
  wall only for a biker (its collision asks the player's story state; `mode` is `forced` during one). The parity counts
  steps onto and off a fall as `waterfall`.
- Meteor Falls 1F 1R has a fall inside a 3D cave: it is one of the five rooms left flat by their bridges (§1.5) and comes
  with them (WB7).

### 1.4 Route 109: the chair, not the sand

- The chairs' levels were fixed by the reviews (five chairs at 3 pinned to 0, their 23 data ramps dropped). What is left
  is one landing: the game lets a surfer step from the water onto a chair's cell (passable, elevation 3), Minecraft puts a
  `SeatColliderBlock` there (half a block, `WorldPlan.renderMapColumn`, `BlockCode.seat()`). A water cell is a half block
  at `feet - 1`, so a surfer's feet are at feet − 0.5; the seat's top is at feet + 0.5; the step is 1.0, over the 0.6
  Minecraft climbs. Sand to seat (0.5) and water to sand (0.5) are fine; only water to seat fails.
- The fix: a seat on a cell with an open edge to water keeps its art (the seat unit) and gets no collider (the cell's
  floor block carries it, as a tuft's decor carrier does). One condition in `Units.seatUnit` / the renderer's `SEAT` bit,
  one test.

### 1.5 The bridges: the inventory

From the heights survey (per-cell `elev`), the v11 levels and models table v15:

| Map | Bridge cells (elevation 15) | Round them (neighbours' elevations) | Deck over the lowest walkable or water neighbour (v11) |
|---|---|---|---|
| Route 110 (the Cycling Road) | 185 | water 1: 121, 4: 41, 3: 14, 0: 6 | 9 blocks for 120 cells; 0 for 29 (the road's landings and junctions); 36 inner cells of the 2-wide road have no non-bridge neighbour |
| Route 120 | 48 | water 1: 48, 4: 8 | 6 for 32, 3 for 16 (the log bridges over the river) |
| Route 119 | 22 | 4: 16, water 1: 10, 3: 6, 0: 6 | 6 for 13, 0 for 7 |
| Fortree City | 4 | 3: 8, 4: 2 | 0: the solver gave the tree platforms no height |
| Littleroot Town | 2 | 3: 8 | 0: level with everything (one floor in the game too) |
| Shoal Cave, high-tide inner room | 76 | water 1: 76, 4: 8 | caves: flat today (`flat_bridge`) |
| Shoal Cave, low-tide inner room | 64 | 0: 24, 5: 24, 3: 16, 4: 8 | flat today |
| Victory Road 1F / B1F / B2F | 30 / 8 / 12 | 3 and 4 | flat today |
| Meteor Falls 1F 1R | 6 | water 1: 6, 4: 4 | flat today |
| Battle Arena corridor (interior) | 66 | 0: 32, 3: 2 | one floor (a corridor the engine keys oddly) |

523 cells on 12 maps; 261 outdoors, 196 in six cave rooms. No landscape-model section of table v15 claims a bridge cell
(checked by claim against the survey), so the models stay out of the way.

What the engine does (`collision.lua`): `nextElevation` keeps the player's elevation on entering or leaving a 15 cell
(`collision.lua:955`); `elevationMismatchOn` never refuses a 15 cell and always refuses two different non-zero elevations
(`:947`); a surfer dismounts only onto elevation 3 (`:989`, `:1059`); across a seam a surfer onto water needs the water's
elevation or 0/15 (`:826–833`). So on a deck the walker keeps the deck ends' elevation (4 on the Cycling Road) and can't
step sideways onto 3 or 1; under it a walker keeps 3 and a surfer 1. Two floors exist in the game exactly where the deck
ends' elevation differs from the ground's or water's under it.

What the code lacks (BRIDGES_PROBLEM.md §3, confirmed): `RaisedRegions.decked` gives a bridge cell its ends' level and
clears the bridge flag, so outdoors the deck is a solid column (`WorldPlan.renderMapColumn` fills "under a raised cell its
own art" from the map's floor up, ~line 1428); indoors `solve` returns `flat_bridge`. `MapLayout` holds one level a cell;
`WorldIndex.toCell` answers within `PIECE_REACH_DOWN` 3 / `PIECE_REACH_UP` 5 blocks of that one floor; `toBlock` and
`levelOrigin` (the client's `Spot`) know one floor; `player.pos`, `snapshot.player`, `warp.to` and `npc.add` carry no
elevation; the field driver marks a 15 cell's verdict `?` where its neighbours' elevations disagree and `FieldParity`
skips it (`walk_varies`); `PairEdges.elevationsRefuse` never walls a 15 cell, so a deck has no side walls (FA9's 71
"falling" breaks on Route 110).

## 2. The design

### 2.1 Water: the sea is the datum

1. **The lagoon rule** (`RaisedRegions.solve`, the water section): a body of water is the sea only if some cell of it
   lies on a map edge the game connects across onto water: `Input` gains `open[]` (per edge cell, true when a connected
   neighbour map has a water cell across it; `Gen3Planner` works it out from `G3Map.connections` and the neighbour's
   model, as `SeamEdges` reads neighbours after the chain). Any other body is a pond: it stands with its banks as today
   (the bank it touches most, soft). Emerald only (the sea rule runs with the shore key, `in.shore() >= 0`).
2. **The datum in the chain** (`MapChain.seams`): a seam with water pairs takes its step from the water pairs' commonest
   difference; seams whose water pairs are the region's sea are ordered first (before the land seams, still by pair
   count), so the forest is built on the sea and a loop's leftover falls on a land seam. The walking pairs the datum
   leaves apart are mended by `MapChain.mend` (SC2) as any ragged seam; `seams.py` gets the same rule.
3. **The basins** (Slateport): two wet pins in models table v16, `[30, 20, 0, 1]` and `[35, 10, 0, 1]` (the dry flag
   keeps a flood on its own side of the water's edge, so on a water cell it is a wet pin: `OutdoorLevels.pinned`,
   `pin.dry()`), after WB0 confirms the cells. Pins translate across the rule change with `rules_check.py translate`.
4. **Waterfalls as cascades:** after the bodies are levelled, each run of fall cells (`in.falls()`, `gen3Falls`) between a
   lower body at L and an upper body at U gets ramps up from L, `OUTDOOR_RISE` (3) a cell toward U; a run too short for
   U − L continues onto the upper body's cells beyond its head (the river keeps climbing, one cell per 3 blocks); if even
   that is short, the upper body is lowered to what fits (soft, noted `fall_short`). `Result` carries the ramps; the dump
   lists them; `levels_overlay.py` draws them as it draws stairs. In the builder a water ramp is the outdoor ramp's step
   colliders (`BlockCode.step`) over the water's half block, the fall cell's own art on the carriers. A new carrier kind
   `fall_wall` (as `BikeWallBlock`: open to a player whose story state says `mode: forced`, closed otherwise) stands on
   the foot cell's downstream side at the surfer's height. `MovementSync` needs nothing new if the mover climbs the steps
   (the Mach Bike slopes prove the physics; *WB0* checks a 9-block fall on the pod); the stuck fallback stays.
5. **Seats on landings** (§1.4): no collider on a seat whose cell has an open edge to water.

### 2.2 Bridges: two-floor cells

1. **The layout** (`MapLayout`, `protocol/world/world_index.schema.json`): a new optional key `decks`, a list of
   `{x, y, under, elev}`: the floor under the deck in blocks above the piece's feet (the cell's `level` stays the deck),
   and the deck's elevation (the ends' elevation, 4 on the Cycling Road). `under(cx, cy)` (−1 for none), `deckElev`,
   `trivial`, `headroom` (the under floor's room counts), `writeTo`/`read`; `WorldIndex.VERSION` stays 1: a world without
   `decks` is a world without two-floor cells, as every world built so far.
2. **The index** (`WorldIndex`): `toCell` tries both floors of a deck cell (deck first, then under) with the same reach,
   the nearer feet on an overlap (a 3-clearance deck's floors overlap by 2 blocks); new `Floor {DECK, UNDER}`,
   `floorAt(cell, y)`, `toBlock(map, x, y, floor)` and `levelOrigin(map, x, y, floor)`; the old `toBlock` keeps the deck.
3. **Which floor the player is on:** the hidden game's elevation. `player.pos`, `snapshot.player` and `warp.to` gain an
   optional `elev` (0–15, the engine's `Player.currentElevation`), sent by the Gen 3 adapter only (`defs.schema.json`
   `place` for the two that use it, `player.pos` beside its own fields; catalog regenerated). `Spot.of(index, place)`
   picks the deck when `elev == deckElev`, the under floor when it is the under floor's kind (3 ground, 1 water), the
   floor nearest the player's current feet when `elev` is 0 or absent; `Spot.at` already picks by height. Every snap,
   settle and teleport in `MovementSync` goes through these, so a player who swims under the Cycling Road and is resynced
   lands under it, not on it.
4. **NPCs:** `npc.add` and `npc.move` gain an optional `elev` (the object event's elevation; trainers on the Cycling Road
   stand at the deck's, swimmers under it at 1); the puppet takes the floor as the player does; absent, the deck.
5. **The planner** (`RaisedRegions`): `decked` keeps giving the deck its ends' level along the bridge, but a bridge cell
   no longer votes with the cells across it (Route 119's two nooks fall back to the ground's 3). The under floor: the
   water body the `under` flood already carries through the bridge (its level), else the ground region across the deck
   (its level); an inner cell of a 2-wide deck takes its row-mate's. The deck's elevation: the ends'. Room: a two-floor
   cell needs `deck − under >= 3` (a 1-block slab and 2 blocks of air: D4's "2 blocks clear"); where the game has two
   floors (ends' elevation ≠ the under's) but the levels give no room, the ends' region is lifted by what is missing
   (soft; Fortree's platforms, 4 cells); where the deck is level with the ground beside it and the elevations agree
   (Littleroot's 2, the Cycling Road's 29 landings and junctions) the cell is one floor. `solve` drops the `flat_bridge`
   return (WB7 for the caves). The dump writes `decks`; the overlay draws the under level in the cell's corner.
6. **Walls** (`PairEdges`): a deck cell gets two side bytes. At the deck: closed toward every neighbour that is not a cell
   of the same deck at the same level nor an end cell (elevation = the deck's, level = the deck's). Under: the existing
   elevation rule with the under floor's elevation in place of 15, plus water's rules for a surfer. The builder places
   `EdgeWallBlock`s at each floor's feet.
7. **The builder** (`WorldPlan.renderMapColumn`): for a deck cell, the under floor as its kind at `base + under − 1`
   (water: the half block; ground: a full block wearing the floor tile of the ground across the deck); nothing between it
   and the deck but air and each floor's walls; the deck a full block at `base + deck − 1` with the cell's own (bridge)
   art, its underside the same art; the "under a raised cell its own art" fill only below the under floor; no
   water-under-cliff barriers (FA8 item 2) on a deck cell; units, decor, story blocks and tufts stand on the deck. No
   pillars (D9).
8. **The parity** (`kb_g3field.lua`, `FieldParity`): the driver writes, for every 15 cell, its verdicts per neighbour
   elevation in a side table (`bridges: {"x,y": {"4": "udlr", "3": "udlr", "1": "udlr"}}`) beside the `?`; `FieldParity`
   walks each from the matching floor and counts `walk_deck`, `walk_under`, `surf_under`; steps from a neighbour into a
   deck cell land on whichever floor `toCell` gives for the walker's height. Expected: Emerald's bridge breaks (about 280
   of its 444 B after FA9) to 0.
9. **Caves** (WB7): Shoal Cave's two inner rooms (76 + 64; the tides are two layouts of one place), Victory Road 1F, B1F,
   B2F (50), Meteor Falls 1F 1R (6, with its waterfall): the same decks under their ceilings (`Headroom` keeps the deck
   plus 2 blocks under it inside the room's depth; the cave rise is `STAIR_RISE`).
10. **Bikes:** the Cycling Road's deck is the bike feature's road (its forced ride, the Mach speed, `BikeWallBlock`,
    `CellFlags.NO_BIKE`: the bike plan). This plan gives it a floor with side walls and a free passage under; nothing
    about riding changes here. Fortree's bridges get their floor the same way; "bikes on Fortree's bridges" (FA8) rides
    on it.

## 3. Decisions (defaults proceed unless the user says otherwise)

| # | Decision | Default |
|---|---|---|
| D1 | The sea is the chain's datum: water pairs decide a seam's step, sea seams go first, the walkers left apart take SC2 ramps | yes |
| D2 | A body of water is the sea only through a water connection across a map edge; otherwise a pond on its terrace (Lilycove's lagoon rises to the plateau) | yes |
| D3 | Slateport's two enclosed basins: wet pins to 0 in table v16 (cosmetic; no surfer reaches them) | yes |
| D4 | Waterfalls as stepped cascades, 3 blocks a cell, climbed and descended by the game's own forced moves; a wall at the foot that opens only during a forced move; a run too short continues onto the river above, else the pool is lowered to fit | yes |
| D5 | A seat a surfer can land on keeps its art and loses its collider | yes |
| D6 | Two-floor cells only where the game has them: Emerald's elevation 15 with ends' elevation ≠ the under floor's. Crystal and Blue have no layers (nobody passes under a bridge there): their bridges stay as built; an art-only review of drawing water under them is not in this plan | yes |
| D7 | The format: `decks` in the layout, index version unchanged; the floor from the game's elevation (`elev` in `player.pos`, `snapshot`, `warp`, `npc.add`, `npc.move`, Gen 3 only) | yes |
| D8 | Room: a 1-block deck over at least 2 blocks of air; a deck without room where the game has two floors lifts its ends' region (Fortree +3); a deck level with its ground is one floor (Littleroot, the Cycling Road's landings) | yes |
| D9 | No pillars or supports under a deck; the slab carries the bridge art above and below; water or path runs under it | yes |
| D10 | Order: the water fixes first (WB1–WB3, one pod session), then the bridges (WB4–WB8) | yes |
| D11 | The six cave rooms after the outdoor decks (WB7), in the same push | yes |

## 4. Phases

Each phase: JUnit for what it touches, then the pod step named, then PROGRESS.md ("Water and bridges"). Pods per the
standing rules (a small 22-only pod; stop it after each job; the full suite at WB3 and WB8). Push only on the user's go.

- **WB0 Facts on a pod** (~30 min, a 4-vCPU pod): the chained dump of every outdoor map with today's rules
  (`levels_dump.sh OUTDOOR=all CHAIN=1 TABLE=v15`), `seams.py` on it, and FA9's parity report
  (`g3_field_parity_report_v15.json`) copied off the volume. Read from them: the pool levels above each fall (Ever Grande,
  Routes 114 and 119, the Safari Zone south-east: how many blocks each cascade must climb and whether its cells suffice);
  Slateport's basin cells for the pins; Fortree's platform levels; the 29 Cycling Road cells at clearance 0 are its
  landings and junctions; the exact two Lilycove landing cells. Also the forced-move check: on LandSky5 drive the client up
  and down Ever Grande's fall with `field_spots.py` to see what the mover does today (teleport expected).
- **WB1 The datum and the lagoon** (`MapChain.seams` water-first and sea-first order, `RaisedRegions` `open[]` and the
  lagoon rule, `Gen3Planner` fills `open[]`, `seams.py` the same rule, table v16 with the basin pins). Checks: `MapChainTest`,
  `RaisedRegionsTest` (a lagoon on an unconnected edge stands with its bank; a body across a connected edge is the sea),
  then on the pod the dump-all twice (no pins, pins translated) and `rules_check.py`: Route 119 524/524, every reviewed
  map still its pins, `seams.py` with no water pair apart in Hoenn, Johto or Kanto, the Hoenn loops' leftovers on land
  seams only; then the parity (Lilycove's 2 landings gone, Route 110 | Slateport and Route 134 | Slateport water at 0,
  Route 107 and Route 4 | Cerulean too).
- **WB2 Cascades** (planner ramps on falls, the water ramp in `renderMapColumn`, `fall_wall` block and kind, the parity's
  `waterfall` class, the dump and overlay). Checks: `RaisedRegionsTest` (a fall of n cells between L and U), a GameTest
  (a surfer is stopped at the foot, a forced walker climbs and descends the steps), the parity on the pod (Ever Grande,
  Routes 114 and 119, the Safari Zone's south-east: no B at the falls), then Hoenn rebuilt and the real client driven up
  Ever Grande's fall with Waterfall and down it (`field_spots.py`, a video).
- **WB3 Seats on landings** and the checkpoint: `Units.seatUnit` / the renderer; the parity (Route 109 clean); the full
  suite (`kb-junit`, GameTests, pytest). Pictures for the user: Lilycove's north shore, Slateport's harbour and both seams,
  Ever Grande's cascade, Route 109's chairs (`context_shots.py`, a sheet). **Push on the user's go.**
- **WB4 The format and the client**: `MapLayout.decks`, the schema, `WorldIndex` floors, `Spot`/`MovementSync` floor
  choice, `elev` in `player.pos`/`snapshot`/`warp`/`npc.add`/`npc.move` (schemas, examples, catalog, the Gen 3 adapter's
  observe side, the puppet's floor). Checks: `MapLayoutTest`, `WorldIndexTest` (both floors, the overlap), `MovementSyncTest`
  (a resync under a deck stays under; a teleport onto a deck cell with elev 4 lands on top), the protocol tests, Lua
  tests with `fake_gen1` sending `elev`.
- **WB5 The planner**: `RaisedRegions` decks (under floor, deck elevation, the room rule, Fortree's lift, no votes across
  the deck), `PairEdges` two side bytes, the dump's `decks` and overlay. Checks: `RaisedRegionsTest.outdoor()` with `B`
  cells over water and over a path, over a 2-wide deck, a landing; the dump-all on the pod: Route 110's 120 cells at 9
  over water/path, Route 120's 48 at 6/3, Route 119's 13 at 6 and its nooks at 3, Fortree's platforms lifted; no other
  map changes (`rules_check.py` same as before).
- **WB6 The builder**: `renderMapColumn` deck columns, walls per floor, no fill or barriers under a deck; the shape
  planner's units stay on the deck. Checks: `WorldPlanTest` columns, GameTests (walk under Route 110's deck at row 32,
  surf under rows 29–30, step off the deck refused, walk the deck end to end), then Hoenn rebuilt and the parity on the
  real index (bridge B about 280 to 0; `walk_varies` 0).
- **WB7 Caves**: the six rooms (drop `flat_bridge`, `Headroom` with the under floor, Meteor Falls' cascade in the cave),
  the dry runs of W4's Emerald caves, GameTests for one room, Hoenn's cave area rebuilt.
- **WB8 The world and the record**: every region rebuilt (LandSky6; Hoenn is the one that changes, Johto and Kanto only by
  the datum rule), `field_spots.py` on all three (Blue 11/11, Crystal 12/13, Emerald 29/29 as before plus the new spots:
  Route 110 under, over and off; Route 120 surf under; Route 119's nooks; Fortree's bridge; Lilycove's lagoon landing;
  Ever Grande's fall; Route 109's chair), videos and a sheet to the user, PROGRESS.md, PLAYTEST.md (the user's own
  checks: ride the Cycling Road, swim under it), the full suite. **Push on the user's go.**

## 5. Risks

- **The lagoon rule moves other ponds.** A body on an unconnected edge that was counted as the sea rises to its bank
  wherever it occurs (Johto's and Kanto's water has no shore key, so only Emerald). `rules_check.py` over all maps shows
  every move; a move a review disagrees with is a pin.
- **The datum shifts loop leftovers onto land seams** that today meet. SC2 ramps and the join stairs take them; a leftover
  over what a corridor can take stays noted, as today (`chain_loop_open`).
- **A cascade may be too steep for its cells** (a tall fall of two cells). The rule continues the ramp onto the river
  above and, failing that, lowers the pool; WB0 lists each fall's blocks first.
- **The under floor's reach overlaps the deck's** at 3-clearance decks (Route 120's 16 cells): the nearer floor wins; a
  player exactly between (jumping) is on the deck. GameTests cover the overlap.
- **The elevation the adapter sends** must be the engine's current one, not the cell's: `Player.currentElevation`
  (`kb_g3field.lua:417` saves both); on a 15 cell they differ, which is the point.
- **Old worlds**: a world built before `decks` keeps today's solid bridges and today's one floor; nothing breaks, nothing
  improves until it is rebuilt.
- **Memory on the pod**: the dump-all of Hoenn with `decks` is the same size class as today (one extra small number per
  bridge cell); region builds unchanged (8-vCPU pod, 20 GB heap as LandSky5).

## 6. Not in this plan

Riding (speed, tricks, rails: the bike feature); Crystal's and Blue's bridge art (water drawn under a deck nobody passes
under); Route 130's Mirage Island story water (55 cells: a story cell); the two underwater routes 127 and 128 placed
apart (16); the 16 Emerald walkers refused at a seam for a reason the model doesn't see (FA9); pillars or supports under
decks; the Battle Arena corridor's odd keys.

## 7. Sizes

- Code: `MapLayout`, `WorldIndex`, `Spot`/`MovementSync`, `RaisedRegions`, `MapChain`, `PairEdges`, `WorldPlan`, a block
  kind, `FieldParity` and the driver, `Gen3Planner`, the Gen 3 adapter's observe and NPC sides, five schemas; the dump
  and overlay tools, `seams.py`.
- Index growth: one small object per bridge cell (261 outdoors, 196 in caves) and one ramp per fall cell.
- Pod time: WB0 ~0.5 h, WB1–WB3 ~1.5 h (dumps, parity, a Hoenn build, spots), WB4–WB7 ~1.5 h, WB8 ~1.5 h (three
  regions ~50 min on 8 vCPU, spots, videos): about 5 pod hours, under $3 at the small pods' rates.
- Rebuild: Hoenn after WB3, WB6 and WB7; all three at WB8 (Johto and Kanto only for the datum rule).

## 8. Running it without the user (the recipe; written for an agent working alone, 2026-10-25)

The user has accepted D1–D11 and will not answer questions while this runs. Decide by the defaults; where the plan is
silent, keep the game's behaviour and write the choice in PROGRESS.md. A blocked phase is recorded with its reason and
the work moves to the next phase that doesn't depend on it; the final report lists what is done and what is not.

**Where things are.** The repo is `/home/claude/kantobridge` (commit identity set; push URL `github-kantobridge`, the
repo is public). Dumps, tables, reviews and pictures from the levels work are in `/home/claude/kb-hub/work/landscape/`
(`rollout/models_table_v15.json` is the current table; `review/<MAP>/r1` the review pictures; `pick/` the sheet tools).
On a pod the volume `kantobridge-dev` (jcp4eqtl70, EU-RO-1 only) is `/workspace`: `/workspace/local` is ROM-derived (the
builds read it; never open or copy it), `/workspace/local/minecraft/kantobridge/export/<game>` the export bundles,
`/workspace/videos/landscape/` the models tables, dump outputs and build logs. Numbers, map ids and cells may leave the
pod; nothing else.

**Pods.** Create pods as `kb-dev51` (w0hifkqk7kapln) is made (`get-pod` it and copy: image `eclipse-temurin:21-jdk-noble`,
the sshd start command with `PUBLIC_KEY`, the volume at `/workspace`, port 22/tcp, 20–30 GB container disk, EU-RO-1):
4 vCPU / 16 GB (cpu3g x4 $0.16/h, cpu5g x4 $0.184/h, cpu5c x4 $0.14/h) for JUnit, dumps and the parity; 8 vCPU / 32 GB
(cpu3g x8 $0.32/h) for region builds (`HEAP=20g`) and real-client pictures (they hang on 2 vCPU). When a stopped pod's
host is full, make a new one rather than retrying for long. After every start: `kb-pod host <ip> <port>` (get-pod's
`ssh.direct`), `kb-pod prep`, `ssh kb-dev 'ln -sfn /workspace/local /root/kantobridge/local'`; for anything that runs the
engine or the client also `/home/claude/kb-runpod/prep_base.sh` and `/home/claude/kb-runpod/pod/prep2.sh`, then
`Xvfb :99` (`setsid nohup`; check with `pgrep -f "[X]vfb :99"`; the field sweeps stop it, restart it before pictures).
The container disk is wiped on stop: the pod scripts live in `/home/claude/kb-runpod/pod/` (scp them each time);
`/workspace` persists. Stop a pod (`pod-action stop`) the moment its job ends; never leave one running while waiting.

**Scripts.** `tools/landscape_sections/pod/levels_dump.sh` (`OUT`, `OUTDOOR=all`, `CHAIN=1`, `TABLE`, `GAMES`; a minute a
game; copy the JSON off with scp), `levels_build.sh` (`WORLD`, `REGIONS`, `TABLE`, `OUTDOOR`, `CHAIN`, `HEAP`, `KEEP=1`
to rebuild Kanto without wiping the others), `prep_pictures.sh` + `levels_shoot.sh`; `/home/claude/kb-runpod/pod/sky5.sh`
(the full recipe: all three regions into a world, then `field_spots.py` per game; copy and rename for LandSky6),
`fa2_run.sh 3 emerald <tag>` (Emerald's parity into `local/reports/g3_field_parity_report<tag>.json`; `FieldParity` reads
the field dump the sweep wrote), `edge_probe.py` (one step of the real engine from chosen cells); `tools/e2e/g3_field_check.py`,
`field_spots.py` and `context_shots.py` (`DISPLAY=:99 KB_DATASET_MEMO=30`); `tools/landscape_sections/rules_check.py`,
`seams.py`, `pins_table.py`, `apply_reviews.py`, `levels_overlay.py`; sheets with `kb-hub/work/landscape/pick/levels_sheets.py`.

**Tests.** JUnit on the pod with `/home/claude/kb-runpod/kb-junit [package ...]` (one package at a time: the whole suite
in one Gradle run hangs at its end on these pods); GameTests `./gradlew runGameTest` (report in `build/reports/gametest`;
tests that reset static state need their own batch); pytest from `tools/` with `tools/.venv/bin/python -m pytest -c
pytest.ini --rootdir .`; after a schema change `python -m kb_protocol.catalog --write`. Feature tests per phase; the full
set at WB3 and WB8 only. The Lua side under lupa is in the pytest run.

**Gotchas.** Never `pkill -f` a pattern that matches your own ssh command line (kill by PID). Long jobs:
`(setsid nohup cmd > log 2>&1 < /dev/null &)` and poll the log; a failed headless build can hang instead of exiting
(watch for `failed`/`ERROR`, not only `ALL DONE`). Waiting: `for i in $(seq 1 N); do sleep 4; done`. The volume is 40 GB:
delete the worlds and logs you made when done. Dumps want `-Pkb.testHeap=10g` on a 16 GB pod. Never load a whole
transcript or log into context (`grep` it). The Minecraft EULA is accepted on the volume. Gen 3's engine exposes the
player's elevation as `P.currentElevation` (the field driver saves it, `kb_g3field.lua:417`).

**Record and report.** After each phase: commit (a clear message; the attribution lines the session carries), a
PROGRESS.md entry under "Water and bridges" (numbers only: counts, cells, parity before/after), and the plan's results in
a new §9 of this file. Pictures and videos go to the user with SendUserFile (30 MiB limit; shrink video with ffmpeg
libx264 crf 26). Pushes at WB3 and WB8 only, after the scan every push had: author and committer the noreply identity,
no `local/`, `refs/`, binaries, secrets or personal addresses; a bundle backup first in `/home/claude/backups/`
(`kantobridge-<date>-<hash>.bundle` + `.sha256`). When everything is done, or nothing more can be done alone: stop every
pod, confirm it, and finish with a summary (done, the parity before and after, left and why).

## 9. Results (2026-10-25/26, run unattended)

Numbers only; the reasons, the choices where the plan was silent and the deviations are in PROGRESS.md "Water and
bridges". Emerald's parity is FieldParity on the planned world (walking A/B, surfing A/B; the waterfall class and the
two-floor comparisons apart).

| Phase | Emerald walking A / B | Emerald surfing A / B | Waterfall | Varies (walk / surf) |
|---|---|---|---|---|
| before (FA9's v15) | 231 / 183 | 27 / 261 | 0 | 166 / 16 |
| WB1 (datum, lagoon rule, solver cascades, seats) | 219 / 184 | 31 / 224 | 61 | 166 / 16 |
| WB5/WB6 decks built | 219 / 149 | 21 / 90 | 25 | 166 / 16 |
| WB6/WB7 bridges judged per floor, final planner | 223 / 45 | 21 / 90 | 25 | 60 / 16 |

- **WB0:** falls Ever Grande 8 cells for 24 blocks, Route 114 3 for 9, Route 119 4 for 15 (one more on the river above),
  Route 119 south and the Frontier's east fall between water at one level; basins (30, 20) and (35, 10) at 3; Lilycove's
  landings (39, 1), (40, 1); Fortree's bridge (30-33, 14).
- **WB1:** water pairs left apart after the chain: Hoenn 9 (the Frontier's own pond), Johto 3, Kanto 0. Route 134 |
  Slateport 8 -> 0. Blue surfing B 3 -> 0, Crystal 5 -> 3. D3 only half held (the basins have quay landings): table v16
  keeps the harbour's pin `[35, 10, 0, 1]`, drops the east basin's; Lilycove's lagoon needed a wet pin `[45, 0, 9, 1]`.
- **WB2:** cascades on Ever Grande (8), Route 114 (3), Route 119 (4 + 1); rebuilt after the pins.
- **WB3:** Route 109 21 -> 3.
- **WB4:** `decks`, floors, `elev` (format and client); tests MapLayoutTest, MovementSyncTest, StoryStateTest, Lua.
- **WB5:** decks: Route 110 185 of 185, Route 120 48, Route 119 16, Fortree 4; Littleroot 2 one floor.
- **WB6:** Route 110 222 -> 4, Route 120 52 -> 2; bridge cells compared per floor: 1,108 on decks, 592 walked under, 500
  surfed under.
- **WB7:** Shoal Cave's two inner rooms, Victory Road 1F/B1F/B2F and Meteor Falls 1F 1R raised with their decks
  (breaks: Shoal Cave low tide 11, Meteor Falls 6, the rest 0).
