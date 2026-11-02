# Crystal model pass — progress

The Crystal equivalent of the Blue and Emerald passes ([MODEL_AUTHORING_WORKFLOW.md](MODEL_AUTHORING_WORKFLOW.md)):
every fitted cell and building of `fitted_shapes/crystal.json` gets a semantic decision and, where needed, explicit
geometry, reviewed in real Minecraft before it is merged. Tooling and contract: [tools/shape_fit/crystal_pass/README.md](../tools/shape_fit/crystal_pass/README.md).

## Checkpoint 0 (2 November): the process recalibrated on Codex's Crystal trial

The user asked Claude to recreate Codex's two accepted Crystal building trials with the same process and compare,
before the full pass. `tools/shape_fit/crystal_pass/claude_qc_buildings.py` authors the Ecruteak house (b010) and Sprout
Tower (b088) from their drawings (parts specs, source-rectangle paint, `crystal/parts_model.py` previews), into a
scratch table only; Codex's versions stay live. Captured with Codex's own gallery command (`-Subset buildings -Angled`,
baseline `buildings-baseline-crystal.json`, 20 settle ticks): `local/model-gallery/crystal-claude-qc/gallery-v2/`
(12/12 views, 2/2 sheets, all four templates hulls; v1 had half-dark skirt roofs, fixed). Three-way sheets
(original / Codex / Claude) and `volume-comparison.json` in `local/model-gallery/crystal-claude-qc/`.

Result: the same reading and construction. House: plaster and timber walls, lattice wainscot, swept eaves, raised
ridge, ~6 blocks (volume IoU 0.83, footprint 0.92); Claude adds a hip-and-gable roof, veranda posts and deck, ridge-end
tiles. Tower: three inset storeys under separate curved roofs, ~13-14 blocks (IoU 0.56: Codex narrows the roofs
upward, Claude keeps them as wide as the drawn eave bands). Caveat: Claude read Codex's `building_trials.py` while
locating the process, before authoring.

## Checkpoint 1 (2 November): pass tooling and the reference family

- Baseline: `local/model-gallery/crystal-full-pass-baseline.json` = the live table (SHA-256 `390a39cf…`, CRLF working
  copy), Codex's trials included and protected. Inventory: 1,238 voxel cells (all one layer; 90 `on`, 47 `walk`,
  19 `seat`), 107 buildings (94 box, 13 parts); `local/model-gallery/crystal-full-inventory/{scenery,buildings}/`
  (1,328 scenery exhibits incl. 90 automatic stacks).
- Tooling in `tools/shape_fit/crystal_pass/`: `study.py` (families by tileset, group sheets, pixel maps), `common.py`
  (`Pass`: patch, ledger, objects, amendment proposals, review sheets), `furniture.py` (face-aware source paint),
  `building_parts.py`, `review.py` (guarded merger: protected pilots, disjoint ownership, preserved metadata, amendments
  only from `source_amendments.json`), `batch.py` (candidate table, `tools/shape_fit/entities/crystal.json`, gallery).
- Reference module `players_house.py` (TILESET_PLAYERS_HOUSE_1): 25 changed, 4 retained, 7 complete objects; Red's
  bed's false vertical stack removed (amendment). Minecraft review `local/model-gallery/crystal-review/players-house-v1/`
  (32 comparisons, exact coverage, `visual-qa.json`); the game console was rebuilt after it (one controller, not two:
  the second was floor stripes; the new `furniture.silhouette` keeps floor objects to their drawn outline) and
  recaptured in `players-house-v3-console/`.
- **Merged** into the live table (guarded): 25 changed, 4 retained, 1,316 pending. Live table SHA-256
  `c651a39e…`, byte-identical to the captured candidate. Pre-merge snapshot: `local/model-history/crystal-premerge-players-house/`.

## How the pass runs (the user's decision, 2 November)

After Checkpoint 1 Claude briefly fanned the remaining families out to seven parallel agents. The user asked for the
earlier way instead: **sequential, thorough, inspected as it goes, even if slower**. The agents were stopped within
about 20 minutes; their partial, unreviewed drafts are kept only as reference in
`local/model-gallery/crystal-pass-study/_agent-drafts-20261102/` (never merged as they stand).

Each checkpoint is one family or a few related ones: study (sprites, maps, collision, pixel maps) → module → local
review sheets → candidate table → Minecraft gallery (before = baseline; front, side, angled) → inspect every contact
page and the full sheets of objects and suspects → fix and recapture → `visual-qa.json` → guarded merge into the live
table. Planned order: homes (players' rooms and houses, traditional houses, the mansion, labs), Centers and Marts,
public interiors (Pokécom Center, Radio Tower, Game Corner, train station, gates, underground, Battle Tower inside),
special interiors (Facility, Elite Four and Champion, Lighthouse, towers, Ruins, ports), Johto outdoors, caves,
Kanto outdoors, the National Park and Battle Tower outside, then the buildings in groups (Centers and Marts, houses,
landmarks). Codex's two buildings stay protected.

## The census (2 November): where each entry is actually drawn

`./gradlew shapeFit -Pkb.fitArgs="survey <crystal bundle> <out.json>"` (FitTool.survey) plans all 388 Crystal maps with the
jar's table and records why each cell is or isn't a unit; `crystal_pass/census.py` tallies that per fitted key
(`local/model-gallery/crystal-pass-study/census/census.json`, shown on every study sheet). Drawn: `unit:fit`, `seat`,
`walk_voxels`, `stack_top`. Not drawn: `walk` (walkable, no walk/seat flag), `building` (a template claims it), `water`,
`ledge`, `not_low` (Tier A raised and folded it), `counter` (Crystal keeps the native counter fold: only Emerald has
fitted counters), `no_rule`. At the start: 860 keys drawn somewhere, 376 never drawn, 2 unplaced. Never-drawn entries
are retained with that evidence. Re-run the survey after merges that change stacks or walk/seat flags.

## Checkpoint 2 (2 November): houses and the bedroom

`house.py` (TILESET_HOUSE_1, TILESET_PLAYERS_ROOM_1) on the new shared `interiors.py` (one table system for every family:
1-block top from each cell's drawing, legs at drawn outer corners only, solid apron rows ending at the drawn front;
device-on-cabinet stacks; bookcases; stools; `paint_global` for objects drawn across cells). 39 changed, 6 retained
(never drawn), 8 objects: the 4x4 Fan Club / Trainer House table with its potted centrepiece, TV / stereo / PC stacks,
bookcases, the tall potted plant (109 placements) and the flower shop's tall planter, cushion stools (222 placements,
seat height kept), gravestones, planters, the Trainer House stairwell (walk), the bedroom radio, TV and bookcase.
Amendment: the wall-row TV top `1905-1905-1907-1908` no longer stacks onto the bedroom TV (it stands in place over a TV in
five other maps); its model is unchanged. Merger fix: a metadata-only change on an entry with default paint is valid.
Minecraft review `crystal-review/house-v1/` (48 comparisons) and `house-v2-fixes/` (stairwell side wall, raised
centrepiece); `visual-qa.json` in both. **Merged**: 65 changed, 9 retained, 1,271 pending; live SHA-256 `6e1f1486…` =
the captured candidate. Snapshot before merge: `local/model-history/crystal-premerge-house/`.

## Checkpoint 3 (2 November): traditional houses

`traditional_house.py` (TILESET_TRADITIONAL_HOUSE_1: Ecruteak/Violet houses, Kurt's, the Dance Theater, the Wise Trio's room,
Mahogany's shop, Mt Moon's gift shop, the Route 39 barn): 30 changed, 8 retained (never drawn), 7 objects. Low tables on the
shared table system at Japanese height (top y 4-6); Kurt's L-shaped workbench with his apricorns, vice and tools; tansu
stacks (altar cabinet, chest of drawers); zabuton (seat height kept); Mahogany's double-sided gondola with recessed shelves;
the barn / Wise Trio railing; the Wise Trio stairwell; the theater stand, radio and bookshelf. Amendment: the wall-row chest
top `1984-1984-2008-2010` stood in place over a bookshelf in 7 of 12 placements; its link is removed and it is the lattice
wall it shows (native scale). Shared `interiors.table_piece` gained `slab` and `cut` (house output unchanged, checked).
Review `crystal-review/traditional-house-v1/` (37) and `traditional-house-v2-gondola/` (recessed shelves). **Merged**:
95 changed, 17 retained, 1,233 pending; live = the gondola candidate. Snapshot: `local/model-history/crystal-premerge-traditional-house/`.

## Checkpoint 4 (2 November): Celadon Mansion and the Mobile rooms

`mansion.py` (TILESET_MANSION_1): 38 changed, 8 objects. Display and ornament cabinets (stacked tops), a potted bush, the 1F
table with its drawer apron, the 2F office (computer desk, drawing desk, chair seen from behind at the seat height), the 2F
bed (false stack removed: amendment), the corridor nameplate, the roof's see-through lattice railing (33 placements, also
the Mobile rooms' partitions), stairwell and parapet, the roof hut's blue front walls, and the Mobile rooms' terminal (side
towers, screen hood, counter, front pillar, wall emblems in the terminal's own warm greys). Shared-key compromise: the roof
hut's roof is drawn with the house tables' tiles, so on the roof it reads as a low flat top behind the hut's front wall.
`interiors.LEG` gained plain wood as a last fallback (single-colour table tiles; earlier modules unchanged, checked).
Review `crystal-review/mansion-v1/` (46). **Merged**: 133 changed, 17 retained, 1,195 pending; live = the captured candidate.

## Checkpoint 5 (2 November): labs, the Academy, the Dragon Shrine; in-place wall-row tops

Finding: a wall-row stack top drawn **in place** (the cell south of it is not its base) replaces part of the back wall,
so a short case there leaves a hole. The runtime's `alsoOn` (a top may also stand on listed base variants) fixes the
common case, keeping the objects tall: Elm's bookcase top (12 in-place placements over Elm's base variant), Mr Fuji's
bookcase top (2, house module) and the gym statue figure (Fuchsia's pedestal variant) now stack everywhere
(`Pass.also_on`, amendments with witnesses). Wall-row cells that are only wall art stay full wall blocks
(`interiors.wall_relief`: the art standing proud, e.g. the Dragon Shrine's carved dragons, Elm's wall monitor).

`labs.py` (TILESET_LAB_1, TILESET_LAB_2): 24 changed, 6 retained (never drawn), 7 objects: bookcases, the gym statue
(pedestal and figure, shared `interiors.statue_*` for the Game Corner family's pedestal), the lab table (one row deep,
legs at both ends) and its Poke Ball display, Elm's 2x2 research machine, PC, printer, window, wall monitor and corner
equipment, the Academy's school desks, chairs (seat) and teacher's desk. Review `crystal-review/labs-v1/` (33).
**Merged**: 157 changed, 23 retained, 1,165 pending; live = the captured candidate.

## Checkpoint 6 (2 November): Pokemon Centers

`pokecenter.py` (TILESET_POKECENTER_1, every center 1F, the 2F Cable Club rooms, Indigo Plateau): 30 changed, 3 retained
(never drawn), 7 objects: the 2x2 healing machine (its console in the wall row as full wall blocks, the lowered body,
cream columns and red-cross tray in front: the first capture hid the console, fixed in `pokecenter-v2-healer/`), the
blue terminal and the PC (stacks), the wall post, the counter-end monitor (alsoOn adds the 1F counter variant, 22
centers), Nurse Joy's counter with two Poke Balls, the waiting-room seats (150 placements), the Cable Club booth
(pilasters, pillars, walls, recessed glass, corner beams, counter, machine, walk-through door) and the Indigo rack.
Review `crystal-review/pokecenter-v1/` (37) and `pokecenter-v2-healer/` (3). **Merged**: 187 changed, 26 retained,
1,132 pending; live = the captured candidate.

## Checkpoint 7 (2 November): Marts and department stores

`mart.py` (TILESET_MART_1): 38 changed, 9 retained (never drawn: counters, walkable roof cells), 11 objects: the six
two-cell shelf units (yellow and red goods, plain shelf, refrigerated display, blue glass cabinet, vending machine; base
with recessed shelf or glass, stacked top with the sign), the glass pane (112 placements: window in the wall row and the
Celadon 5F display cases), the cases' sign band, the directory board, the counter end, the tall plant, the lobby bench
(false stack removed: amendment), and the Goldenrod roof (south and east railings, coin binoculars, hedge and bush
planters, ticket machine, the raised flower bed, the stairwell). Review `crystal-review/mart-v1/` (49).
**Merged**: 225 changed, 35 retained, 1,085 pending; live = the captured candidate.

## Checkpoint 8 (2 November): the Pokecom Center admin office

`pokecom.py` (TILESET_POKECOM_CENTER_1, one map: the Goldenrod Pokecom Center's admin office): 59 changed, 6 objects.
The atrium's machine is one octagonal structure (whole-machine object `crystal_pokecom_atrium_machine`): the display at
the back as full blocks (its top rows stand in the wall row), the framed panel two voxels above a six-high ring with the
machinery window recessed, glass bands sloping from the floor to the ring on both sides and turning diagonally at the
front corners (the drawn hatch on the sloped tops), four monitors behind their console desks, and the frame's lower
corners as part-cell columns (a ticket machine beside the west one). Also the trade machines (stacks), the Center-style
terminal and PC tops, counters with Poke Balls, the signboard, 12 waiting seats, potted plants (the tall plant's crown
also stands on the gold urn: alsoOn amendment), the office's window ledge and computer desks. The first capture
(`crystal-review/pokecom-v1/`) showed thin upright guards and a floor pattern on a full frame block, so the machine was
rebuilt; review `crystal-review/pokecom-v2/` (65). `Pass.write(families=...)` now refuses a module whose ledger is
not exactly its families' keys (every earlier module checked: exact). **Merged**: 284 changed, 35 retained, 1,026
pending; live = the captured candidate (`74d9e76a…`). Snapshot: `local/model-history/crystal-premerge-pokecom/`.

## Checkpoint 9 (2 November): the Radio Towers

`radio_tower.py` (TILESET_RADIO_TOWER_1: Goldenrod 1F-5F, Lavender 1F): 33 changed, 15 retained (never drawn: 13 counter
cells, 2 walkable cells), 8 objects. The studio window is one glass band one cell tall straddling the two rows it is
drawn over (the rail row's south half, the glass row's north half), with the equipment rack and two speaker cabinets in
its line and the DJ desk's north end (counter height, the microphone on it) in front; the DJ desk, its script and the
reception counter's corner at counter height, beside Tier A's counters (one block, plain). Stacks: the reception PC, the
record racks, the three-puff hedges in grey planters. The round white tables in the wall row stand against the wall
(the wall closed behind them, the stand's foot in the walkable cell below); the 2F album display has two tiers of album
covers; the 5F console, the low partition, red and brown stools, posters and the 3F wall device (wall reliefs). The
links of the equipment rack and the 3F wall device onto walkable cells never stack at runtime (a base must be solid,
`Units.stack`) and are left unchanged. Review `crystal-review/radio-tower-v1/` (41). **Merged**: 317 changed, 50
retained, 978 pending; live = the captured candidate (`fb9917b1…`). Snapshot: `local/model-history/crystal-premerge-radio-tower/`.

## Checkpoint 10 (2 November): Game Corners, cafes, Vermilion Gym

`game_corner.py` (TILESET_GAME_CORNER_1: both Game Corners, the prize room, the Celadon and Olivine cafes, the Safari
Zone office, Vermilion Gym): 29 changed, 10 retained (never drawn: 8 counter cells, the 2 walkable cells under the prize
machines), 9 objects. The slot machine columns are Tier A counters except each column's last machine and its end
cabinet, so those are one block too (the end cabinets with their coin-slot fronts). The cafe table is a pink octagon on
a red rug (gold centrepiece on Celadon's 2x3 table; Olivine's 2x2 table shares the bottom row, so each cell's octagon is
cut in its own frame). Stacks: the G prize machines, the telephone on its stand, the leafy crown on its gold urn, and
Vermilion's statue figure on this family's pedestal (`interiors.statue_pedestal`, the labs module's stone). Wall-row
cells: the prize exchange machines fill theirs; where the prize counter's or the blue table's north part is drawn under
the wall, the wall closes the cell's north half and the counter (one block) or the table stands in front. The grey
chairs (96), Vermilion's trash cans. Review `crystal-review/game-corner-v1/` (38). **Merged**: 346 changed, 60
retained, 939 pending; live = the captured candidate (`562c1aea…`). Snapshot: `local/model-history/crystal-premerge-game-corner/`.

## Checkpoint 11 (2 November): Magnet Train stations, gym hedges and statues

`train_station.py` (TILESET_TRAIN_STATION_1: both stations; shared pieces of the Celadon, Viridian, Cerulean, Pewter,
Cianwood and Ecruteak gyms, the Fighting Dojo, the Tin Tower entrance): 35 changed, 2 retained (walkable: the train's
door warps, a station planter edge), 6 objects. The maglev is one body designed over its 10x2 cells and painted from
its composite drawing (`interiors.paint_global`): two blocks tall, cream roofs, the drawn side with the middle car's
orange window band, tapered noses, open at its two walkable door cells; the track continues as a low bed with two
rails. The hall: the platform railing (posts and rails, 12 voxels), ticket gate booths (stacks), yellow seats, the blue
info desk at counter height. Amendments (`source_amendments.json`): the tall flower planter's crown also stands on its
planter base (16 placements that stood in place: its link named the station's walkable variant); the gym statue figure
also stands on four more pedestal variants (20 placements: dojo, Tin Tower entrance, Cianwood, Ecruteak, Cerulean,
Pewter); Viridian Gym's four hedge links are removed (they stacked 12 maze segments two cells tall among one-cell
walls), and both gyms' hedges share one height. Review `crystal-review/train-station-v1/` (41). **Merged**: 381 changed,
62 retained, 902 pending; live = the captured candidate (`5bef511c…`). Snapshot:
`local/model-history/crystal-premerge-train-station/`.

Java checks after checkpoint 10: a broad `--tests "*Stack*"` filter also selected Codex's Emerald proof fixtures,
which rewrote 12 proof files under `local/model-gallery/emerald-pass-study/` (the Emerald table is unchanged since
eb34221; all 292 registry evidence bindings still match) and showed
`EmeraldInteriorSourceProofTest.genericWindowsAndFurnitureUseSourceBoundStacksAndNoncollidingDecor` failing (19
expected, 0 found: the local `generic-soft-furnishings/entities.json` has no `baseline_native_required` entries; a stale
local fixture, independent of Crystal). The Crystal-relevant classes (`FittedShapesTest`, `FittedStackAliasesTest`,
`UnitStackChainsTest`, `UnitsTest`, `FittedCarriersTest`, `FittedShapesComparisonTest`) pass: 41 tests.

## Checkpoint 12 (2 November): gate houses, the Colosseum, the Goldenrod Underground

`gates.py` (TILESET_GATE_1: the route gate houses, the underground path entrances, the Colosseum, Trade Center and Time
Capsule; TILESET_GATE_2: the Goldenrod Underground): 19 changed, 15 retained (never drawn: counter cells), 3 objects.
Potted shrubs in wooden planters (40), the National Park gates' PC (monitor stacked on the blue keyboard desk), grey
square chairs, the stairway warps as floor-level plates showing the drawn stairwell (as the Goldenrod roof's). The
Colosseum's link machine is designed over its 2x2 cells (two grid consoles and the green A-frame, its tip drawn in the
row above) and painted from its composite drawing; ring-cushion stools beside it. The Underground's wall-row panels are
wall reliefs (light bar and trim proud), its corners close the wall over the green counters' ends, the shutter door is
recessed in its wall block, and the bush planters stack a two-puff crown on the planter. Review `crystal-review/gates-v1/`
(22). **Merged**: 400 changed, 77 retained, 868 pending; live = the captured candidate (`e56d32ff…`). Snapshot:
`local/model-history/crystal-premerge-gates/`.

## Checkpoint 13 (2 November): basements, the Rocket base, the port passages, the Underground Path

`underground.py` (TILESET_UNDERGROUND_1/2/3): 24 changed, 6 objects. The warehouse's cardboard crates (84, lidded, tape
on the faces); the Rocket base's Persian statues and Saffron Gym's statue (the shared statue builders, each in its own
colours), its grey desk drawn across the wall row and the row below (the wall closes the north half, the top straddles
the seam, the monitor on it) with its chair, the bookshelves (the shared case builders, stacks); the port passages'
potted palms (the pot carries the trunk to the stacked crown); the Underground Path's wall blocks and the basement's
event-placed wall piece (2968-2969-2968-2969: the sliding walls, never in the survey's default layout). Walkable warps:
the red shutter doors are thin panels against the back (the wall line stays closed), the stairways floor-level plates.
Review `crystal-review/underground-v1/` (30). **Merged**: 424 changed, 77 retained, 844 pending; live = the captured
candidate (`ea208458…`). Snapshot: `local/model-history/crystal-premerge-underground/`.

## Checkpoint 14 (2 November): the Battle Tower interior

`battle_tower.py` (TILESET_BATTLE_TOWER_INSIDE_1: 1F, hallway, battle room): 17 changed, 1 retained (a walkable cabinet
foot), 4 objects. The wall row's machine cabinets are full blocks, the glass doors thin back panels (walkable warps);
the reception counters cream-topped and blue-fronted at counter height with a monitor and a keyboard on its ends; the
blue terminal and the battle room's corner machines are stacks; gold plinths. New amendment kind: `Pass.set_metadata`
(a source-evidenced change to an entry's non-geometry fields, accepted by the merger's existing "metadata" allowlist).
The hallway's wall cell over the elevator door (537-537-488-489, walkable) had a stack link onto the walkable door below,
which never stacks, so the lone top was dropped and the back wall had a gap; marked `walk`, it now draws in place.
Review `crystal-review/battle-tower-v1/` (21). **Merged**: 441 changed, 78 retained, 826 pending; live = the captured
candidate (`a1323434…`). Snapshot: `local/model-history/crystal-premerge-battle-tower/`.

Runtime check after checkpoint 14 (`census/survey-cp14.json`, FitTool.survey of all maps with the live table; the
task splits `kb.fitArgs` on whitespace, so the bundle path is passed relative and unquoted): every stack amendment
behaves as recorded. Each `alsoOn` placement stacks (the flower crown 16 of 16, its one walkable-base placement in
place; the statue figure 24 of 24; the earlier ones all), each removed link draws in place one cell tall, and the
Battle Tower hallway cell is `walk_voxels`.

## Checkpoint 15 (2 November): the Power Plant, Mr. Pokemon's house and the facility interiors

`facility.py` (TILESET_FACILITY_1: the Power Plant, Mr. Pokemon's house, Silph Co. 1F, the Ruins research center, the
Trainer House basement, the flower shop's table corner): 37 changed, 7 objects. Tables use the shared
`interiors.table_piece`; this tileset's front rows add the solid front panel drawn under the band, and the one-row desks
(and the north-west corner the flower shop shares, as noted at checkpoint 2) carry legs at both ends, with their
console, gold set or document on top. The Power Plant: the counter (counter height, slatted front), the north-south
counter strip with its wall end and its control-box end, the generators (stacks), the transformer bank (full machine
blocks: vents drawn on the top rows, dials on the face rows); blue sofas (seat, backrest, armrest), chairs, bookcases
(stacks; amendment: the top also stands on the drawer base, 3 placements that stood in place), the research terminal
(stack), potted plants in gold urns (stack), a stairway plate. Review `crystal-review/facility-v1/` (44). **Merged**:
478 changed, 78 retained, 789 pending; live = the captured candidate (`7e84e82a…`). Snapshot:
`local/model-history/crystal-premerge-facility/`.

## Checkpoint 16 (2 November): the Team Rocket base B2F and B3F

`rocket_base.py` (TILESET_FACILITY_2): 28 changed, 6 objects. The Rocket base's furniture is the facility tileset's
redrawn in its own palette, so it takes checkpoint 15's designs with that palette (`facility.py` hard-codes the Power
Plant's colours and is left unchanged): the shared tables with the solid front panel, one-row desks with their devices,
generators (stacks), the transformer bank, bookcases (stacks; amendment: the top also stands on the drawer base, 4
placements that stood in place), plants in urns (stacks), chairs, stairway plates. Review `crystal-review/rocket-base-v1/`
(34). **Merged**: 506 changed, 78 retained, 761 pending; live = the captured candidate (`d06c6f49…`). Snapshot:
`local/model-history/crystal-premerge-rocket-base/`.

## Checkpoint 17 (2 November): the gyms' shared pieces, the Elite Four and Lance's rooms, the Bike Shop

`gyms.py` (TILESET_CHAMPIONS_ROOM_1, TILESET_ELITE_FOUR_ROOM_1/2): 38 changed, 13 retained (never drawn: 4 counter cells,
9 walkable cells), 5 objects. The gym statue is a red-and-white Poke Ball sphere stacked on its pedestal (the shared
pedestal); brick planters with a bush or red flowers (180 placements), the two-cell bush, Olivine's gold boulders (62),
Violet's stone posts, Blackthorn's low blue ledges over the red floor and its dragon heads, the pits and walkway drops
as floor-level plates; Lance's gold dragon pillars (the head a full block in the wall row), the hall's dragon statues
(stacks), the red-ball braziers and the platform's steps; the Bike Shop's bicycles as upright cutouts (`gyms.cutout`:
the drawing's figure as a thin card, every face painted with its pixel) and its parts shelf; the switch room's planters,
switch boxes and wall machine; stairway plates. Review `crystal-review/gyms-v1/` (43). **Merged**: 544 changed, 91
retained, 710 pending; live = the captured candidate (`fc759ea1…`). Snapshot: `local/model-history/crystal-premerge-gyms/`.

## Checkpoint 18 (2 November): the S.S. Aqua and the Olivine Lighthouse

`ship_lighthouse.py` (TILESET_LIGHTHOUSE_1: the S.S. Aqua; TILESET_LIGHTHOUSE_2: the Olivine Lighthouse): 40 changed, 1
retained (a walkable cabin door), 4 objects. Tables use the shared `interiors.table_piece` with the front band and the
woven panel under it, the dishes (a plate across the seam), cups and the captain's book on top; the cabins' small tables
drawn half a row lower carry their top over the north cell's south half (the porthole wall closing the north half under
the wall). Beds lie north-south across head and foot (the players' house design); amendments remove their two stack
links (the foot stacked on the head two cells tall, as at checkpoints 1 and 4). The porthole walls (wall reliefs), the
cabin machine (its upper part under the porthole wall), red stools, the captain's chair, the cabin cabinet (stack), the
red ball, the lighthouse's window walls, stairway plates. Review `crystal-review/ship-lighthouse-v1/` (44). **Merged**:
584 changed, 92 retained, 669 pending; live = the captured candidate (`6f215409…`). Snapshot:
`local/model-history/crystal-premerge-ship-lighthouse/`.

## Checkpoint 19 (2 November): the towers, and the warp cells restored

`towers.py` (TILESET_TOWER_1-4: the Burned Tower, the statue pedestals the gym figure also stands on, Cianwood's
boulders, the Tin Tower entrance, the Sprout and Tin Tower floors, the Tin Tower roof): 25 changed, 4 warp cells kept, 5
objects. The wooden lattice walls (east-west face-on as full blocks, north-south one block thick over the drawn middle),
the Tin Tower's posts and floor ledge, the Sprout Tower's central pillar (2x3 full blocks: dark top, face below), the
Tin Tower figures and Bellsprout heads on their pedestals (stacks; the shared statue builders), the two gym pedestal
variants with the shared pedestal, the Burned Tower's carved head over its barrel pillar and its rubble mounds,
Cianwood's boulders, the roof tiles as a low raised surface beside the walkway.

**Correction: warp cells.** `docs/specs/WARP_MODELS.md` (the user's design, 2026-10-20) gives walkable warp cells their
models: stairs up rise in four steps toward the wall; stairs down and doors keep the drawn floor behind a two-block
doorway frame with a dark opening. The baseline's `walk` entries on warp cells are those models. From checkpoint 2 on,
this pass replaced 32 of them (following an earlier README rule to "keep it low") with floor-level plates, or with thin
closed panels for the doors and shutters (and at checkpoint 6 closed the Cable Club booth door's opening with its
drawn door). All 32 are restored to their baseline entries (`Pass.keep_warp`; the README rule is corrected): Trainer
House, Wise Trio, Celadon Mansion roof and Goldenrod roof stairwells, the Cable Club booth door, the gate and
underground stairways, the Underground's and port passages' shutters and stairs, the Battle Tower doors, and the
facility, Rocket base, gym, S.S. Aqua, lighthouse and tower stairways. Rerunning the 13 affected modules removed exactly
those 32 keys from their updates and changed nothing else (checked against copies in `crystal-pass-study/_pre-warp-revert/`).
Review `crystal-review/towers-warps-v1/` (62: the towers' models and every restored warp cell). **Merged**: 581 changed,
124 retained, 640 pending; live = the captured candidate (`52528bf3…`). Snapshot:
`local/model-history/crystal-premerge-towers-warps/`.

## Checkpoint 20 (2 November): the ports, the Ruins of Alph, the unplaced keys (interiors done)

`ports_ruins.py` (TILESET_PORT_1/2, TILESET_RUINS_OF_ALPH_1, TILESET_AERODACTYL_WORD_ROOM_1, the five unplaced keys): 14
changed, 23 retained (never drawn: water, the cells the docked ship's building claims, the ruins' walkable floor, the
unplaced keys), 5 warp cells kept, 3 objects. The ports' sea rocks lining the piers (268: rounded blue rocks), the docked
ship's waterline row outside its building (the drawn hull rising from a water plate), Cerulean Gym's gold pedestal (the
shared pedestal: the gym figure stands on it), the Ruins' stone statues on their pedestals (stacks), the puzzle wall's
panels and the item rooms' carved stones. Every interior family is now reviewed. Review `crystal-review/ports-ruins-v1/`
(17). **Merged**: 595 changed, 152 retained, 598 pending; live = the captured candidate (`30574614…`). Snapshot:
`local/model-history/crystal-premerge-ports-ruins/`.

## Checkpoint 21 (2 November): the Dragon's Den

`dragons_den.py` (TILESET_JOHTO_4: the Dragon's Den B1F): 20 changed, 2 objects. The island shrine is designed over its
4x3 cells (walls, a gabled roof with its ridge east-west and the eave over the front wall, the roof's drawn slats over
its depth; the door cell is not a fitted key), the 2x2 rock formations as one rounded mound each, the white fence's
east-west and north-south runs, the rough rock edge in the water (a low slab) and the sign. `Java check after checkpoint
20: the Crystal-relevant classes pass (8 classes, 46 tests).` Review `crystal-review/dragons-den-v1/` (22). **Merged**:
615 changed, 152 retained, 578 pending; live = the captured candidate (`6dce816b…`). Snapshot:
`local/model-history/crystal-premerge-dragons-den/`.

## Checkpoint 22 (2 November): the Johto outdoors

`johto_outdoor.py` (TILESET_JOHTO_1/2/3/5/6/8/9/10/11, TILESET_JOHTO_MODERN_1/2): 24 changed, 120 retained, 4 objects. The
standard Johto tree (2,278 / 2,280 placements) and its bushier variant (226) stack two cells tall: one rounded crown over
both on a short trunk (about 30 voxels, the README's outdoor rule), each cell painted from its own drawing by height; the
one-cell (headbutt) tree (654) a compact crown on a stub trunk. The 2x2 rock formations (one mound), fences (east-west
runs, north-south runs, ends), the sign boards (101), the rocky shallows (747: four small stones on a water plate),
ledges (the ground's top over the dirt face), Goldenrod's flower bed and rails. Retained: keys never drawn (building
templates, Tier A folds, water, walk) and the building wall and cliff blocks drawn as units in a few places outside the
templates (their full-block models already read right). Review `crystal-review/johto-outdoor-v1/` (28). **Merged**: 639
changed, 272 retained, 434 pending; live = the captured candidate (`d8b5ffcd…`). Snapshot:
`local/model-history/crystal-premerge-johto-outdoor/`.

## Checkpoint 23 (2 November): the Kanto outdoors

`kanto_outdoor.py` (TILESET_KANTO_1 to 13): 30 changed, 90 retained. The Kanto tree (3,077 placements: one cell, a
rounded crown on a short trunk; Kanto's trees are one square in the source, Johto's two); the round bollards along paths
and bridges (1,988); the town signs (75) and Route 13's wooden post pairs (461); the ledges (a low step, the path or grass
on top, the rock face south and on its ends); the cliff faces and mountain rock drawn as units where no template claims
them (Routes 22-24, Silver Cave's outside: full blocks showing their rock). The gatehouses cut by a map seam (Routes 15,
17, 18, Fuchsia's edge, Routes 6 and 16) are drawn as units on this side; their slices, 11-14 high before with stray rods,
are full blocks now (the roof seen from above, the facade on the face and sides, the dark roof edge on top). Retained: the
keys never drawn, and the sea drawn as a unit at map edges (its plate). Solid units collide as full blocks whatever their
model (`Units`: `shape_full`), so the ledges' low steps keep the source's look over a full block, as before.

Also re-authored from checkpoint 22: the Johto ledges' and flower bed's end faces (the rock and hedge rows, not a flat
dominant colour). Review `crystal-review/kanto-outdoor-v1/` (36). **Merged**: 669 changed, 362 retained, 314 pending
(107 buildings, 207 cells: forest, caves, the Ice Path, the National Park, the Battle Tower's outside); live = the
captured candidate (`9d801c3e…`). Snapshot: `local/model-history/crystal-premerge-kanto-outdoor/`.

## Checkpoint 24 (2 November): Ilex Forest

`forest.py` (TILESET_FOREST_1): 31 changed, 2 retained, 1 object. The big trees that make the forest (247 each, drawn over
2x2 cells): one rounded crown over the four cells on a stout trunk, 32 voxels (two layers: a genuinely tall object, as
Johto's trees), painted from the composite with the crown kept inside the drawn crown's columns; no shadow. The headbutt
tree (the same drawing as Johto's one-cell tree), the shrine (modelled whole in its upper cell; its base cell is walkable,
never drawn), the roofed notice board, the ledges. The two gatehouses at the forest's ends (6x6 cells, no template claims
them, every cell a unit): a cell's paint comes only from its own drawing, so the windows (row 3) cannot reach the front
face (row 5), and the door's warp frame is two blocks; every gate cell is a full block showing its own drawing (the Kanto
gatehouse slices' rule), uniform where the old ones stood 15 or 16. Review `crystal-review/forest-v1/` (32). **Merged**:
700 changed, 364 retained, 281 pending; live = the captured candidate (`9450767a…`). Snapshot:
`local/model-history/crystal-premerge-forest/`.

## Checkpoint 25 (2 November): the National Park

`park.py` (TILESET_PARK_1): 34 changed, 33 retained, 4 objects. The big trees ringing the park (386 each, 2x2 cells): Ilex
Forest's big tree in this art, a rounded crown on a broad trunk, 32 voxels. The ornamental fence: east-west runs are two
wooden blocks as a low wall with the hoop arch on top; north-south runs are the wooden block column as the wall, and the
narrow oval drawn beside it is the same hoop seen edge-on (tiles 3863/3866/3867 over three quarter-tiles, a cap block 3860
between hoops), so there the hoops stand on the wall in line with the run, each cell built from its own quarter tiles so
every phase of the run fits; the run ends are a free-standing hoop. The benches (two cells: slatted seat and backrest on
legs), the bins, the boulder. The fountain's rim is built per quarter tile (stones a block six high, water a plate, ground
left to the floor): the old models raised some ground and water quarters to the stones' height. The north-south fence
cells' `on` links never match where they stand (the census draws all of them in place) and are left as they are.
Review `crystal-review/park-v1/` (38). **Merged**: 734 changed, 397 retained, 214 pending; live = the captured candidate
(`0e4f6802…`). Snapshot: `local/model-history/crystal-premerge-park/`.

## Checkpoint 26 (2 November): the caves, the Ice Path and the Hall of Fame

`caves.py` (TILESET_CAVE_1/2/3, TILESET_DARK_CAVE_1, TILESET_ICE_PATH_2, and TILESET_ICE_PATH_1, whose keys are the Hall of
Fame's): 43 changed, 26 retained, 5 objects, one stack amendment. The boulders (the cave's 767 and the dark cave's 940,
one drawing in two palettes; the Ice Path's ice rocks) are rounded rocks; the 2x2 rock formations (Burned Tower B1F, the
Dark Cave, the Ice Path) one mound over their four cells; the Ice Path's two-wide rocks one elongated rock per pair. Ledges
and their north-south edges are low steps; two dark-cave edge keys (2069-2069-2086-2086, 77 placements; 2073-2069-2073-2069,
23) had **empty models**, invisible solid cells until now. The daylight openings in the rock (Mount Moon, Diglett's Cave,
the Dark Cave, the Seafoam Gym, Silver Cave) are one design for the four keys (a framed opening glowing at the back of the
cell, a little light on the floor): only one of them drew it before (the others were a scrap of floor, a sand slab and a
floating canopy). Ladders kept (warp models). The Hall of Fame's recording machine is designed as one object over its 4x3
cells (the back wall full height, so the wall row stays closed; fluted pillars with steps; lamp posts; the console with
its lit panel on top and its framed screen on the front); the PC is a cabinet with its keypad recessed. **Amendment**: the
dais step corner 3346-3347-3309-3310 was linked `on` the PC, but its tiles are the step and the floor; the stack put a blue
step box on top of the PC, so the link is dropped (a walkable cell, never drawn), recorded in `source_amendments.json`.
Review `crystal-review/caves-v1/` (48). **Merged**: 777 changed, 423 retained, 145 pending (TILESET_BATTLE_TOWER_OUTSIDE_1
and the 107 buildings); live = the captured candidate (`9c114325…`). Snapshot: `local/model-history/crystal-premerge-caves/`.

## Checkpoint 27 (2 November): the Battle Tower's grounds; every cell decided

`battle_tower_outside.py` (TILESET_BATTLE_TOWER_OUTSIDE_1): 6 changed, 32 retained, 1 object. Every key but six is claimed
by the Battle Tower's or the house's template (never drawn: retained). The six are the house's roof rows: the survey's
templates (`templates` per map in `census/survey-cp26.json`) show the runtime claiming only the house's walls (8x4 art
tiles, the default hull: a box 30 high, flat), so the roof rows north of them are drawn as units; as 16-high blocks they
stood as a lower purple box behind the walls. One roof over the 4x2 cells now slopes from the walls' top (30) down to a
back eave (12), the slats on top, the gables and the eave (two layers). Review `crystal-review/battle-tower-outside-v2/`
(v1 drew the gables black from the drawing's edge column). **Merged**: 783 changed, 455 retained, 107 pending: every
fitted cell has a decision; the 107 buildings remain.

**For the buildings:** the fitted building `crystal_b097_G2_BATTLE_TOWER_OUTSIDE` (tiles 8x8: the roof rows and the walls)
never matches the runtime's claim (the walls only), so it is never used. Each building's match has to be checked against
the survey's templates before authoring it: a building the runtime never applies has no runtime effect.

Runtime survey after checkpoint 26 (`census/survey-cp26.json`): the reason tallies equal checkpoint 14's except one cell
(`walk` +1: the Hall of Fame step whose false stack was dropped); the two-layer trees, the empty-model ledges and every
other changed cell draw where they did (`unit:fit`).

## Checkpoint 28 (2 November): the modern Johto buildings, first half

Which buildings the runtime applies: the survey's templates (`census/survey-cp26.json`) matched against each fitted
building's tiles (`census/buildings-applied-cp26.json`): 104 of the 107 are applied; `crystal_b026_G2_GOLDENROD_CITY`,
`crystal_b097_G2_BATTLE_TOWER_OUTSIDE` and `crystal_b104_G2_RUINS_OF_ALPH_OUTSIDE` never match a claim (no runtime effect).

`building_kit.py`: `read()` finds a drawing's facade line (under the roof's last full dark eave row), its door (the topmost
orange lintel whose frame run spans 12 px or more), its windows (yellow panes and their frames) and its signs (pink bars
12 px or wider: a standing sign beside the door, or a wall sign on the facade's first row); `overlay()` draws the reading
for checking. `johto()` builds the Johto archetype: a plinth; walls behind ochre corner posts (the facade's art on the front,
a free swatch of the same wall on the sides and back) scaled as Codex's accepted b010 (16-row facades 1.375x, 28 or more
1:1); a gable over the footprint as two slopes meeting at the middle, the front slope carrying the drawn roof from the
ridge (its first rows) to the eave, eaves overhanging the front by three; the door a real recess four deep with the door at
its back; windows and wall signs a voxel proud; a standing sign a board on two legs. `buildings_johto_a.py`: 23 buildings
(New Bark, Azalea, Cherrygrove, Violet, Ecruteak's Pokémon Center, Mart, Gym and small house, Olivine's). Reviewed: every
reading overlay (`buildings-johto-a/readings.png`), the parts previews, `crystal-review/buildings-johto-a-v1-buildings/` (23).
**Merged**: 806 changed, 455 retained, 84 pending; live = the captured candidate (`dd666eb3…`). Snapshot:
`local/model-history/crystal-premerge-buildings-johto-a/`.

## Checkpoint 29 (2 November): the modern Johto buildings, second half

`buildings_johto_b.py`: 22 changed, 3 retained. Goldenrod's regular buildings (its doorless small blocks b033/b034, 14 and 6
claims, and the 4x4 buildings whose drawn side has no door, b035/b036, are the archetype without the door), Blackthorn,
Cianwood, Mahogany and the Ruins of Alph's small house (`building_kit.johto`, as checkpoint 28). `crystal_b024_G2_GOLDENROD_CITY`
is a facade with no roof drawn: the runtime claims only these walls (at the Battle Tower's grounds and three Goldenrod
houses; the roof rows behind are Tier A's raised columns, or the units of checkpoint 27), so it is walls only, flat-topped at
30 (the height the runtime gave these claims, which the Battle Tower house's roof units start from), the door recessed, the
window proud (`johto(roof=False, top_y=30)`). Retained, never applied: b026, b097, b104. Review
`crystal-review/buildings-johto-b-v1-buildings/` (22). **Merged**: 828 changed, 458 retained, 59 pending (the Kanto buildings,
Ecruteak's traditional ones and the landmarks); live = the captured candidate (`2668fad9…`). Snapshot:
`local/model-history/crystal-premerge-buildings-johto-b/`.

## Checkpoint 30 (2 November): the Kanto hip-roof buildings

`buildings_kanto_hip.py`: 27 houses and Gyms (Viridian, Pewter, Cerulean, Vermilion, Lavender, Celadon, Fuchsia, Saffron,
Pallet, Mount Moon Square, Route 28). `building_kit.kanto_read` finds the eave (the first row under the roof field whose
middle half is all dark, stepping to the triple line's second dark row), the window band under it (eight rows), an awning
band (rows whose middle is mostly the field's colours: Pallet's and Fuchsia's two-storey houses) and the door. `kanto()`: a
plinth; the lower walls between grey stone pillars; a hip roof at 45 degrees from the band's bottom, its slopes in the
drawn front field (the drawn 12-px hatch stretched over the footprint broke into large patches; it stays on the eave edges,
repeated at its own scale); the window band as a wide dormer standing out of the front slope, roofed by the drawn front
field sloping back into the hip; the awning a lean-to; the door recessed. Kanto windows are light panes on a light wall,
so they stay painted on the facade. Known minor: the field's course lines run up the east and west slopes. Review
`crystal-review/buildings-kanto-hip-v1-buildings/` (27). **Merged**: 855 changed, 458 retained, 32 pending; live = the
captured candidate (`e578e2f6…`). Snapshot: `local/model-history/crystal-premerge-buildings-kanto-hip/`.

## Checkpoint 31 (2 November): the Kanto flat-roofed buildings

`buildings_kanto_flat.py`: 16 buildings. `building_kit.kanto_flat`: walls between grey stone pillars (1:1 for tall
facades: Silph Co. stands 22 blocks), a flat roof slab with the drawn lattice field on top and the cornice on its edges, a
parapet three high, the door recessed (b041-b047, b051, b064, b071, b080, b083). b069 (the Kanto Radio Tower's facade at
Lavender's top edge, no roof drawn) is walls only with a plaster cap; b084 is all roof, claimed over a body Tier A raises
with the runtime's 30-high default, so its slab and parapet stand at 30 on a base in the roof field; the Pewter Museum
(b077) is a two-tier hip roof (an eave soffit, a low lower hip, a raised upper storey under a second hip) where the old model
stood the roof's art up as a tall box; the Indigo Plateau (b102) keeps its earlier parts spec with the double door recessed.
Review `crystal-review/buildings-kanto-flat-v1-buildings/` (16). **Merged**: 871 changed, 458 retained, 16 pending (Ecruteak's
traditional buildings, the landmarks, the protected b010/b088); live = the captured candidate (`9ea56a0b…`). Snapshot:
`local/model-history/crystal-premerge-buildings-kanto-flat/`.

## Checkpoint 32 (2 November): Ecruteak's traditional buildings

`buildings_ecruteak.py`: 5 changed, 3 retained. b008 and b009 share Codex's b010 drawing: `machiya()` is b010's design
(claude_qc_buildings.house_spec) with its width, door and windows as parameters (b008 three barred windows, no door; b009
six cells wide, the lattice door recessed in the middle). b015 is a Johto facade under a speckled roof (`building_kit.johto`).
The Burned Tower (b007): a wooden hall under a low ruined roof painted from the charred roof with its hole opened, rubble
mounds at both sides, the lattice door recessed. The Bell Tower as Ecruteak draws it (b016: seven identical storey bands
over a ground floor): eight square storeys on the footprint's south with balcony eaves and a low hip cap (Codex's b088 stands
its storeys the same way; the runtime collides the whole footprint at the building's height whatever the model,
`FittedBuildings.place`). Retained: b010 and b088 (Codex, protected) and b105 (its earlier spec reads well, no door drawn).
Review `crystal-review/buildings-ecruteak-v1-buildings/` (5). **Merged**: 876 changed, 461 retained, 8 pending; live = the
captured candidate (`225952e3…`). Snapshot: `local/model-history/crystal-premerge-buildings-ecruteak/`.

## Checkpoint 33 (2 November): the landmarks; the pass is complete

`buildings_landmarks.py`: 4 changed, 4 retained. The Department Store (b032: the Johto archetype on a tall facade); Olivine's
house with a roof terrace (b022: the house from the drawing's lower part, the drawn terrace a tiled deck on a back extension
with its railings); the Lighthouse (b023: a square brick tower on the footprint's south, 1:1 at 188 voxels, a gallery ring, a
hip cap, the door recessed); the Radio Tower (b029: the lower block on the south, the tall and short towers rising from it at
their drawn heights, the hut at the north; it was a black slab). Kept: the Battle Tower, the National Park's gate and planter,
the S.S. Aqua (their earlier specs read well). Review `crystal-review/buildings-landmarks-v1-buildings/` (4). **Merged**: 880
changed, 465 retained, 0 pending (`review.py --require-complete` passes); live = the captured candidate (`26089dca…`).
Snapshot: `local/model-history/crystal-premerge-buildings-landmarks/`.

## Completion (2 November)

- **Table:** 1,238 cells and 107 buildings, every one decided: 880 changed, 465 retained, 0 pending
  (`review.py --require-complete` passes). Live `fabric-bridge/src/main/resources/kantobridge/fitted_shapes/crystal.json`
  SHA-256 `26089dca26126a546ed248bc79ea648132f685a873e02d715f359f5db400fadd`. Stack amendments: the ones recorded at
  checkpoints 11, 14-18 and 26 (`source_amendments.json`).
- **Runtime survey** (`census/survey-final.json`, 388 maps): the reason tallies are identical to checkpoint 26's (every
  cell draws where it did); all 104 applied buildings are still claimed with their new specs (645 templates, as before).
- **Java:** `FittedShapesTest`, `FittedStackAliasesTest`, `UnitStackChainsTest`, `UnitsTest`, `FittedCarriersTest`,
  `FittedShapesComparisonTest`, `FittedTerrainPreservationTest`: 43 tests pass. `gradlew assemble` builds
  `kantobridge-0.1.0.jar`, which carries the live table byte for byte.
- **Archive:** `local/model-history/crystal-claude-final/` (MODEL_HISTORY.md, "Crystal full pass"): 43 revisions, 1,493
  restorable entries, 1,028 changed in the pass; verified, and a selective restore rehearsed on a scratch table.
- **Known minors:** the Kanto hip roofs' course lines run up their east and west slopes; towers (the Bell Tower, the
  Lighthouse, the Radio Tower) stand on their footprint's south as Codex's b088 does, so the rest of the footprint collides
  without a model (`FittedBuildings.place` collides every solid cell at the building's height).
- **Approved:** the user reviewed the final comparison world (`crystal-review/final-review/` and
  `final-review-buildings/`) and approved the models on 2 November ("I'm happy with the models"); the live table
  `26089dca…` is the archive's `claude-final`.
