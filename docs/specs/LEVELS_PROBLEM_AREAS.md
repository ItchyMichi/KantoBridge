# Levels: the problem areas left to check by hand

The outdoor levels after four rounds of reviews (LEVELS_PIPELINE.md, 2026-10-22; the rules of 436ba8e, the models table
v13): where they are still wrong, or where a reviewer was unsure, for a person to look at. Each map's levels, drawn over
its art with the red boxes, are on kb-hub (the tailnet viewer) at `/images/levels_problems/index.html`, one section a map
in the order below. Numbers are blocks over the map's lowest ground (a cell of cliff is 3, a ledge drops 1); cells are
`(x, y)` in map cells.

## 1. Walls where the game walks (the computer's red boxes)

A red box is an edge the game walks across that the computer could not make walkable: two cells more than a step apart, a
ledge dropping more than a block, a stair whose end meets nothing. 232 cells on 32 maps, counted before the pins (Blue's
Route 1 and Johto's Route 4 (19,8) are mended by their pins).

**Water standing with its higher bank** (not learned: water stands with its lowest bank):

| Map | Red boxes | What is wrong |
|---|---|---|
| Route 130 (Hoenn) | 77, the beach's edge, e.g. (25-28,16) | The sea stands with the upland (3), the whole beach at 0 under it. Unsure. |
| Route 22 (Kanto) | 24, the row-13 lips (2-9,13) and on | The pond joins the corridor, so the west lowland, the pond and the gate front stand a block too high and the lips drop 2. Unsure. |
| Route 24 (Johto) | 6, (3,8-13) | The river can't be parted from the tall-grass terrace; it belongs at the path's level. |
| Viridian City (Johto) | 6, (8-13,27) | The pond (8-13,24-27) has no south bank: it belongs at the strip's 0, but it is one area with the town (1). |
| Viridian City (Kanto) | 6, (8-13,27) | The same pond, the same row: the town and pond at 1, the strip at 0. |
| Slateport City | 5, (7,58-59), (35,57-59) | 93 sea cells share the city's area and stand at 3 over the beach; the reviewer pinned the rest of the sea to 0. |
| Silver Cave outside | 2, (23,28-29) | The pond waits on the same rule. |

**Water parted at a waterfall** (not learned):

| Map | Red boxes | What is wrong |
|---|---|---|
| Safari Zone south-east | 21, from (20,27) | The lake and its pocket (25-32,18-22) stand at 6 with the north half through the waterfall's water; they belong at 0. |

**Ledges:**

| Map | Red boxes | What is wrong |
|---|---|---|
| Jagged Pass | 12, (10,10), (17,10), (11-16,11) | One 3-block band but two ledge lips, so one lip drops 2. |
| Lilycove City | 8, from (46,17) | A one-cell face drawn as two half-cell ledge rims round pocket B and the box: 2-block drops. |
| Route 120 | 6, (34-38,61), (22,76) | A ledge in a one-way loop lifts the strip, the south field and the east area a block over the field. |
| Route 123 | 6, (36-41,11) | Ledge cells left at 0 instead of 1: the dead end (35,11) counts as a way round. |
| Safari Zone south | 5, (28-32,8) | Two half-cell ledges climb a full cell, so one drops 2. Also unsure: 12 cells of bay floor the Acro Bike rails wall in ((18-21,3-4), (18-19,5-6)) stand at 3, the drawing puts them at 0. |
| Petalburg Woods | 4, (32-35,32) | The east pocket, entered and left only by ledges; the reviewer flattened the woods with a pin. |
| Safari Zone north-east | 4, (7-10,18) | The pit's exit ledges: a 2-block one-way drop. |
| Safari Zone north | 2, (20-21,23) | The rock ring's south ledges drop 3 to the basin; the ring's floor could be 2 or 3. |
| Route 10 (Kanto) | 2, (2-3,59) | The west ledge row drops 2: the only way round climbs both east steps. |

**Stairs the computer skips:**

| Map | Red boxes | What is wrong |
|---|---|---|
| Route 6 (Johto) | 2, (3,2), (13,2) | Two stairs whose foot is a hop cell (now a 1-block step each). |
| Route 4 (Johto) | 1, (33,6) | The stair (33,5) whose foot is a hop cell. |
| Route 9 (Johto) | 1, (28,12) | The stair (28,11), the only way up to three areas, now a 1-block step. |
| Sootopolis City | 2, (45,6), (53,28) | House doors at the top of one-row stairs: a door joins any level, so the stair finds no top. |
| Battle Frontier outside east | 1, (39,32) | The stair (39,33): its top is an elevation-0 strip. The plaza's cascade is pinned round. |

**Open-sky maps no review looked at** (their levels are the solver's alone): Meteor Falls 1F 1R (9, row 37), Fortree City
Gym (5, (13-17,17)), the Abandoned Ship's deck (2, (13-14,14)), Silver Cave room 2 (2, (11,11), (11,25)), Artisan Cave
B1F (1, (16,38)), Victory Road B2F (Hoenn; 1, (40,20)), Silver Cave room 1 (1, (5,6)), Whirl Islands B1F (1, (8,14)) and
north-east (1, (15,12)).

## 2. Seams between maps that don't meet

Where two maps touch, cells that still stand apart after the chain: a step of 1 can be jumped, 3 or more is a wall; water
at two levels is a waterfall nobody drew. Cells on the first map's side.

| Maps | Cells apart (of the seam's) | Step | Where |
|---|---|---|---|
| Dewford Town / Route 107 | 1 of 4 | 3 | Dewford (19,4) |
| Mossdeep City / Route 127 | 3 of 24 | 3 | Mossdeep (17,39), (19,39), (21,39) |
| Route 110 / Slateport City | 4 of 9, water | 3 | Route 110 (36-39,99) |
| Route 124 / Route 126 | 2 of 18 | 3 | Route 124 (19-20,79) |
| Route 127 / Route 128 | 1 of 33 | 3 | Route 127 (14,79) |
| Route 134 / Slateport City | 8 of 36, water | 3 | Route 134 (0,7-14) |
| Cerulean City / Route 24 (Johto) | 6 of 16 | 1 | Cerulean (12-16,0) |
| Cerulean City / Route 4 (Johto) | 5 of 14 | 3, 6 | Cerulean (0,10-12), (0,26) |
| Cerulean City / Route 5 (Johto) | 8 of 16 | 4 | Cerulean (16-19,35) |
| Lavender Town / Route 10 south (Johto) | 7 of 13 | 3, 6 | Lavender (0-3,0) |
| Lavender Town / Route 12 (Johto) | 2 of 18 | 3 | Lavender (8-9,17) |
| Lavender Town / Route 8 (Johto) | 2 of 4 | 6 | Lavender (0,0), (0,8) |
| Mahogany Town / Route 42 | 7 of 17 | 3 | Mahogany (0,0-4) |
| Mahogany Town / Route 44 | 3 of 11 | 3 | Mahogany (19,0), (19,8-9) |
| Pallet Town / Route 1 (Johto) | 3 of 14 | 1 | Pallet (0,0), (17,0), (19,0) |
| Pewter City / Route 3 (Johto) | 6 of 15 | 1, 3 | Pewter (39,10-13) |
| Route 10 north / Route 9 (Johto) | 3 of 9 | 1, 2 | Route 10 north (16-19,0) |
| Route 22 / Viridian City (Johto) | 9 of 17 | 1, 2 | Route 22 (39,0-3) |
| Cerulean City / Route 4 (Kanto) | 6 of 11 | 1, 2 | Cerulean (0,12-16) |
| Pallet Town / Route 1 (Kanto) | 4 of 16 | 1 | Pallet (0-2,0), (19,0) |
| Route 14 / Route 15 (Kanto) | 4 of 13 | 1 | Route 14 (0,40-43) |

## 3. Doubts the reviewers left (no red box)

**Hoenn:**
- Route 116: the east block (the grass (64-79,11-14) with the cave (65,10), the terrace (69-72,9-10), the top
  (69-81,5-9)) stands 3 blocks too high; unpinned while pins couldn't carry stairs. Its review's pins in that order,
  (72,11) 0, (72,8) 3, (74,7) 6, may work now. Unsure.
- Route 119: the two nooks north of the long log bridge stand at the deck's 9; they are ground under it and should be 3.
- Route 114: the plateau over the waterfall pond stands at 0 and 9; by its 5-cell south face it should be 15.
- Route 112: the ledge at x 13 doesn't drop.
- Route 110: the east yard (29-33,15-17) stands at 9 with the deck; notches in the deck where paths cross; rail and
  pillar cells at the water's level; (4,61) and (5,61) should be 3.
- Ever Grande City: the League plateau's height and some unreachable mountain pockets.
- Mt. Pyre summit: the unreachable upper pockets at 6, the east flank's at 0.
- Battle Frontier outside west: the lakes could be 6, the dock 9; its east neighbour lifts its island by 9 to meet it.
- Rustboro City: 16 cliff-top cells (north of Devon (6-12,5-7), the corner (0-1,34-35)) touch the sea and stay at 0.
- Dewford Town: nine plateau tree cells at 0. Safari Zone south-west: trees by the plateau's rim at its level.
- Sky Pillar top: the sky rows 0-2 read as walkable ground. Southern Island: the dock may be a pier a cell over the sea.

**Johto (Crystal):**
- Crystal's ledges never drop (its hop cell stands before the lip): the reviews pinned round them.
- Route 2: the Route 2 Gate stands on a 4-block bank; the ledges round the east path close their loop there.
- Route 22: the pond ties area C to A, so C stands at 2, not 1.
- Route 32: the west hill top can't be pinned at 6: its cave mouth (2,15) has no warp and joins the stub in front.
- Goldenrod City: the boulder column and the house cells (37,22-23) stand at 3.
- Violet City: Sprout Tower's plinth is half a cell, its door about 1.5 blocks too high.
- Azalea Town: whether the well's stair climbs up or down. Mt. Moon's square: the mountain 4 by its rims, not 3.
- Cinnabar Island: the plateau's south face reads 3 over the sea but stands at 4. Cerulean City: the river's level.
- Route 20: the free deck may be a raised pier. Vermilion port: the half-cell wooden edge at (6,12).

**Kanto (Blue):**
- Route 2: the unreachable strip south of the north gate (x 0-7, y 20-21) should be 4 (it is 5).
- Route 4: the ledge row 9 at x 76-79 stays flat (a ledge loop joins the strip x 76-89, y 10-12 to row 8).
- Route 6: the gate's north path (10,0-1) at 2, its door at 1: pin (10,1) 1 if the two doors should stand level.
- Route 3: a 1-block step under the hedge column x 23, y 12-13, where no face is drawn.
- Route 10: the Power Plant strip, yard and water at 3, the lawn beside them at 2 (two trees between).
- Cerulean City: the river at 1. Lavender Town: the rock tops round the town could stand a cell over it.
- Routes 12 and 13: the boardwalk's pilings suggest a deck a little over the sea.
- Safari Zone east: its stairs' risers look dark in the build (the stair tile's own dark lines).

## 4. What would fix most of it (rules not learned yet)

- **Water stands with its lowest bank**: about 126 of the red boxes (section 1's first table) and Route 22's C.
- **A waterfall parts water**: the Safari Zone's south-east lake; Ever Grande's pool and the Battle Frontier's plaza are
  pinned round it.
- **Crystal's ledges** drop across their lip: most of Johto's pins round ledges.
- **Ledges**: a ledge in a one-way loop stays level (Route 120); a dead end is no way round (Route 123); a pocket entered
  and left only by ledges (Petalburg Woods); a face drawn as two half-cell ledges (Lilycove, Jagged Pass, Safari Zone
  south).
- **Stairs**: a stair whose foot is a hop cell (Johto's Routes 4, 6, 9); a door at a stair's top (Sootopolis); a stair
  whose top is an elevation-0 strip (the Battle Frontier).
- **Raised piers and decks** (Southern Island, Route 20, Kanto's Routes 12 and 13).
