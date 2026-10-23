# Landscape sections: the pilot (the user's suggestion, 2026-10-21)

Part of [HEIGHTS_ALL_GAMES_PLAN.md](HEIGHTS_ALL_GAMES_PLAN.md) W6 (D9, and later the outdoor levels of D4).

## 0. Why

D9 decides a face's height per column of tiles from the tiles alone. The pictures of 2026-10-21 (seven spots, the
game beside two builds; PROGRESS W6 note 9) show where that stops: the same tiles can be a cliff, a line of boulders or
a column of trees, and the planner can't tell which. The first build, which stacked every face of fitted cell models,
looked best to the user, but it also stacked boulder lines into towers and rock lines into pillars; the narrowed build
put those back but left the cliffs low, the cave mouths as one-cell towers and the tree columns as tall walls.

The user's suggestion: do it as the buildings were done (HAND_MODELS.md section 7, BUILDING_PARTS.md). Agents mark the
landscape's sections on each map (this is a cliff, this a mountain, these are boulders, this is a tree mass), and the
sections are then modelled as wholes, the large ones individually where it helps. This plan is the pilot: a few maps,
two ways of modelling the marked sections, the same pictures, and the user picks.

## 1. Scope

- **Maps:** the seven picture spots' maps, so the pictures compare directly: Kanto `ROUTE_23` (cliffs, boulders, the
  gate's posts) and `ROUTE_10` (Rock Tunnel's mountain and mouth); Johto `G2_BLACKTHORN_CITY` (rock lines, plateau
  edges) and `G2_ROUTE_26` (tree columns, a cliff strip); Hoenn `EM_ROUTE119` (tree walls, rock faces),
  `EM_ROUTE127` (rock islands) and `EM_BATTLE_FRONTIER_OUTSIDE_EAST` (the user's mouth; an outdoor map under the open
  sky).
- **Out of the pilot:** raised ground (the floors stay flat; levels are W6's next step, and section 6 says how the same
  marking gives them), other maps, committing a default.
- **Result:** the big sections at each spot as models, previewed in kb-hub's viewer for the user before anything is
  built; then a builder for them and the seven pictures beside the stacked build's.

## 2. Stage 1: the sections (done, 2026-10-21)

**2.1 A calculated first pass** (`LandscapeRegionsTest`, pod only, `-Pkb.landscape`): per map the 4-connected regions
of solid cells and per region its cells, bounding box, the floor along each side, its south face's drawn rows per
column, its shares of repeats, foliage and fitted models, and the doors under it (map ids, positions and counts). The
renders (the map's art with each region outlined and numbered, in 32 x 32-cell chunks) are private, in kb-hub's work
folder.

**2.2 Marking by agents** ([LANDSCAPE_MARKING_RULES.md](LANDSCAPE_MARKING_RULES.md)): one agent per map wrote its
sections (kind, region, cell boxes, top cells, the face's rows where the drawing misleads, a landmark flag, a note):

| Map | Sections | Face | Mass | Objects | Trees | Mouth | Building | Left to the default |
|---|---|---|---|---|---|---|---|---|
| ROUTE_23 | 56 | 23 | 0 | 27 | 3 | 2 | 1 | 0 cells |
| ROUTE_10 | 22 | 2 | 4 | 6 | 5 | 2 | 3 | 0 |
| G2_BLACKTHORN_CITY | 50 | 20 | 0 | 20 | 0 | 4 | 6 | 0 |
| G2_ROUTE_26 | 42 | 23 | 0 | 9 | 7 | 0 | 3 | 0 |
| EM_ROUTE119 | 73 | 26 | 4 | 13 | 28 | 0 | 2 | 365 (water, banks) |
| EM_ROUTE127 | 52 | 0 | 20 | 29 | 3 | 0 | 0 | 149 (solid water) |
| EM_BATTLE_FRONTIER_OUTSIDE_EAST | 93 | 16 | 1 | 34 | 27 | 1 | 14 | 243 (fences) |

The agents found where the drawing misleads: a long strip of high ground running north and south counts every cell of
its columns as face (Blackthorn's side walls, Route 10's edges), so they marked its real height (`rows`); Gen 1 and 2
draw plateaus and mountains as tops behind a one-cell rim. Open: Route 23's green dither (a tall cliff face, or a
terrace's top behind a 4-row face). The table (`tools/landscape_sections/table.py`) checks the marks and gives each
section's cells; review sheets (kinds coloured over the art) are in kb-hub's work folder.

## 3. Stage 2: a model per big section (the user's choice, 2026-10-21)

The plan first modelled the marked sections with the planner's rules (B: stacking by section, mountains tall; C: each
section one height). The user chose instead the buildings' way ([BUILDING_PARTS.md](BUILDING_PARTS.md)): a model per
big section, previewed before it is built ([LANDSCAPE_MODELS.md](LANDSCAPE_MODELS.md)). B and C were built and dropped
before their pictures (the branch `pilot/landscape-bc` keeps them).

- **The packages** (`tools/landscape_sections/package.py`): per picture spot the window its camera looks over (12
  cells either side, 24 north), its drawing at 16 px a cell, a reference with the grid and the sections, and the
  sections to model: masses, mouths, faces of 3 cells or more, and at the two tree spots the trees.
- **The models:** an agent per spot describes each big section as parts (boxes, sloped profiles, domes, cones,
  cylinders, carves, and a trees helper), painted from the drawing; heights at the buildings' scale (1.5 voxels a drawn
  row, 8 a block). `tools/landscape_sections/terrain_model.py` builds them, keeps each to its own cells (a door keeps
  3 blocks of headroom), and draws it in 3D, from the south and in the game's own view over the drawing.
- **The review:** every spot's models and their scene (all models on the ground) in kb-hub's viewer, group
  "landscape models / <spot>"; the user's notes go back to the agents.
- **The builder** (after the user's review): each model's voxels into the world, block by block (8 voxels a block,
  like the fitted cell models' voxels), over its section's cells, in place of what the planner stands there today;
  walkable cells untouched. Then the seven pictures beside the stacked build's.

## 4. Steps

1. The first pass and the renders for the seven maps. Done.
2. The marking guide, one agent per map, the marks checked against the renders. Done.
3. The packages and the modelling guide; one agent per spot models its big sections; the previews in kb-hub.
4. The user reviews the models; fixes.
5. The builder, with tests on made-up maps; the seven maps built; the pictures beside the stacked build's. The user
   picks; rolling out follows from that. (The user approved the models, 2026-10-21: "very happy with the results and
   want to see them in game". The builder reuses the buildings' parts templates: LANDSCAPE_MODELS.md, "In the game".)
   Rolled out 2026-10-22/23, rock only, all regions: 920 units, 1,098 models, all three regions built (world
   LandRollout); LANDSCAPE_MODELS.md, "The rollout".
6. A frame-rate test on a pod with a mid-range GPU (about an average PC; the CPU pods render in software), the world
   with the models against the world without (the user's idea, 2026-10-21). Done: no measurable cost; Route 119's trees
   make the heaviest section, over the quad budget (LANDSCAPE_MODELS.md, "The frame-rate test").

## 5. Checks

- The marks: every solid cell of the seven maps in exactly one section or left to its default (counts per kind).
- The models: each within its own cells (the tool's cleared count), its game view reading like the drawing, the
  user's review.
- In the game: walkable and solid cells unchanged, the visible-layers check (D9), the quad budgets (Kanto's densest
  section stands at 29,648 of 30,000 quads today).

## 6. What it gives W6 later

The same marks answer what the data can't for Crystal and Blue: a `mass` or a `face` with floor on top of it says
that floor is a level up, by the face's drawn rows; Emerald's elevations cross-check it. Rolling out means marking
about 185 outdoor maps (the surface and the open-sky maps), the user's review, and the variant the pilot picks.
The user's choice for it (2026-10-22): rock only, all regions; the steps: [LANDSCAPE_ROLLOUT_PLAN.md](LANDSCAPE_ROLLOUT_PLAN.md).
