# Bridges: the problem (for a plan; written 2026-10-25 at the end of FA9)

A summary of what is wrong with bridges in the built world, what the game does there, what the code does today, and what a
fix has to touch. It is input for a plan, not a plan. Sources: the field audit's parity after FA9
(FIELD_MECHANICS_AUDIT_PLAN.md §14, report `g3_field_parity_report_v15.json`), HEIGHTS_ALL_GAMES_PLAN.md (D4, Q3, W6),
the levels dumps and Gen1Recomp's Gen 3 engine (`refs/gen1recomp/src/core/game3/collision.lua`).

## 1. What the player meets

In Emerald a bridge is a cell the game lets a player stand on at two heights: on the deck, and under it (on the ground,
or surfing on the water). Minecraft builds such a cell as one solid column up to the deck, so nobody passes under it.

| Map | Breaks (cell-directions, parity) | What happens |
|---|---|---|
| Route 110 (the Cycling Road) | about 220: walking into the deck's side 79, falling off a deck 71, surfing into its side 70 | The road is a deck 9 blocks up over the water and the paths; the game lets walkers cross under it (row 32 to the south paths) and surfers swim under it (rows 29 to 30, columns 25 to 29). Minecraft has a solid wall where the road crosses. "Falling" counts the deck's sides: the game keeps a player on the deck (the deck's elevation), Minecraft lets the player walk off and drop 9 blocks. |
| Route 120 | about 47, mostly surfing | The log bridges over the river: surfers pass under them in the game. |
| Route 119 | about 12 | The long log bridge north of the Weather Institute, and the two nooks under it (the levels review noted they stand at the deck's 9 and should be 3). |
| Fortree City, Route 113 and the rest of the 10 maps | few or none today | Bridges whose floor is level with the ground beside them break nothing yet, but are the same kind of cell. |
| Emerald's caves | not measured | Five caves stay flat because of their bridges (Meteor Falls 1F 1R, Victory Road 1F, B1F and B2F, Shoal Cave's low-tide inner room: PROGRESS, the 3D caves). |

Crystal and Blue have bridges too, but their data doesn't mark them (no elevations): HEIGHTS_ALL_GAMES_PLAN.md D4 calls
for an agent review of the maps with bridges there.

## 2. What the game does (Emerald)

- **Elevation 15 is a bridge** (`G3Map.elev[]`, 4 bits per cell, exported): a walker on it keeps the elevation it came in
  with. Coming from the deck's ends (elevation 4 or so) it is on the deck; coming from the ground or water under (3 or 1)
  it is under. The engine's collision checks compare the cell's elevation with the player's, and 15 matches any
  (`PairEdges.elevationWall`, `collision.lua`); so a player on the deck can't step sideways off it onto lower ground, and
  a player under it can't step up onto the deck.
- **Surfers** are under a bridge whenever they swim through it; `Collision.tryConnection` and the surf checks treat 15 as
  "keeps the player's" too (`collision.lua:831`).
- **381 cells on 10 maps** are elevation 15 (HEIGHTS_ALL_GAMES_PLAN.md, the survey); Route 110's road is 185 of them.

## 3. What the code does today

- **The levels:** `RaisedRegions` takes a bridge's cells as its deck, at the level of the floors its ends meet
  (`RaisedRegions.Input.bridge`, "until the two-floor cells of W6"); the planner fills the column under every raised cell
  from the map's floor up (`WorldPlan.renderMapColumn`, "under a raised cell its own art"). So the deck is a cliff-sided
  block 9 tall where the road crosses water and paths.
- **The world index** maps a position to a cell only within a few blocks of that cell's floor
  (`WorldIndex.toCell`: `PIECE_REACH_DOWN` 3, `PIECE_REACH_UP` 5 for a map with a layout). A player 9 blocks under a deck
  is in no cell, so the client couldn't tell the hidden game where it is even if the column were open.
- **The layout format** (`MapLayout`: levels, ramps, pieces, ceilings; serialized in the index, schema in `protocol/world/`)
  holds one floor per cell.
- **The client's position sync** settles a player on a cell's floor (`WorldIndex.toBlock`, the warp and fade code,
  `MovementSync`); a teleport onto a bridge cell always lands on the one floor there is.
- **Parity** (`FieldParity`) asks the engine for one verdict per cell, direction and state (walking, surfing), with the
  player's elevation as the dump set it; it has no notion of a cell's two floors.

## 4. What a fix has to touch (the open questions for the plan)

1. **Which cells, which floors.** Emerald: elevation 15 gives the cells; the floor under each must be worked out (water if
   the bridge crosses water, the path's level if it crosses a path; the deck's level from its ends, as today). Crystal and
   Blue: a review (D4) or the drawing's bridge tiles.
2. **The layout and the index format:** a second floor per cell (or a list of bridge cells with their under level), its
   schema in `protocol/world/`, the reader in the client and the server, `toCell` answering for both heights, `toBlock`
   choosing one. An old index without it must still load (as `onIncompatible` handles regions today).
3. **The builder:** under the deck, the floor (water or ground) and at least 2 blocks clear (D4: "the deck keeps 2 blocks
   clear under it"; Route 110 has 9); the deck as a slab of blocks carrying its art; walls on the deck's sides at deck
   height only, so nobody walks off it, while walkers and surfers pass beneath; the void fill, faces and models round it
   (landscape models, fitted buildings, the shape planner) must not fill the space under.
4. **Which floor the player is on:** the client knows from its own position; the hidden game from the player's elevation.
   A teleport (a warp's arrival, a fade, the client snapping to the hidden player's cell) must pick the floor the game's
   elevation says (the bridge's `player.state` would need the elevation, or the bridge infers it from where the player
   came from). Story cells, NPC puppets and wild encounters on bridge cells (trainers on the Cycling Road) stand on the
   deck.
5. **Bikes:** the Cycling Road is a bike road (forced riding, the Mach Bike); FA8 left "bikes on Fortree's bridges" and
   the bike feature is planned in its own session (a real Minecraft bike, Acro tricks, Mach speed; §13.9 Q4).
   The two plans meet on Route 110 and Fortree.
6. **The art:** the deck's underside, its supports (pillars or none), the water or path under it; the client's meshing
   from the shape map draws only what the blocks carry.
7. **Checks:** parity with two floors per bridge cell (a verdict per elevation), the sweep and the real-client spots on
   Route 110 and Route 120 (walk under, surf under, ride over, step off the deck: refused), GameTests for a deck's walls.

## 5. Sizes, for scale

- Emerald: 381 bridge cells, 10 outdoor maps and 5 caves; after FA9 the bridges are most of Emerald's remaining parity B
  (about 280 of 444).
- Index growth: one extra small number per bridge cell.
- Rebuild: every region with bridges (Hoenn always; Johto and Kanto if Crystal's and Blue's are done), about 50 minutes
  on an 8-vCPU pod for all three.
