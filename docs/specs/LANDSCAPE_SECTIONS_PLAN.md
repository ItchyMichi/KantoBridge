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
- **Result:** the seven pictures in four versions side by side, the game, A (the stacked build, pictures on the volume),
  B and C (below); the user picks, or asks for a mix.

## 2. Stage 1: the sections

**2.1 A calculated first pass** (pixel-free, a pod test as the dry runs are). Per map, the 4-connected regions of solid
cells, and per region: its cells, bounding box, the floor along each side, the drawn rows of its south face per column,
how much of it repeats (the repeat rule), is foliage (the hint), holds fitted models, and the doors and cave mouths in
it; for Emerald, the elevations around it. Written as the regions' ids and cells (counts and positions only) and a
render: the map's art with each region outlined and numbered, private in kb-hub's work folder.

**2.2 Marking by agents.** One agent per map, given the render, a zoomed sheet per large region, the region list and a
short marking guide (`docs/specs/LANDSCAPE_MARKING_RULES.md`, written with the pilot). For each region, or for parts of
one it splits, it writes:

| Kind | What it is | Examples |
|---|---|---|
| `face` | a cliff band or a wall: a vertical face, its top behind it | Route 23's cliffs, a ledge's wall, a town's rock wall |
| `mass` | a mountain, a rock island, a hill: a front face and a top over its other cells | Rock Tunnel's mountain, Route 127's islands |
| `objects` | separate things on the ground, each its own cell | boulders, rocks in a line, posts, signs, statues |
| `trees` | a tree line or a tree mass | Route 26's and Route 119's tree columns |
| `mouth` | a face holding a cave mouth or a wall exit | Rock Tunnel's mouth, the Battle Frontier's Artisan Cave |
| `building` | part of a building (left to the buildings) | a house's wall drawn into a cliff |

with its front (the side the drawing shows as the face, nearly always south) and, for a `mass`, the cells of its top.
A section it thinks deserves a model of its own (a landmark: the Battle Frontier rock, Mt. Chimney, Sootopolis's rim)
it flags; the pilot models at most one (the Battle Frontier rock, as parts, BUILDING_PARTS.md).

**2.3 Review and data.** Sheets with the kinds coloured over the art go to kb-hub for the user. The result is a
pixel-free table per game (map id, section id, kind, front, cells as runs, top cells), read by the planner; in the pilot
it is passed to the build by a property, not put in the jar.

## 3. Stage 2: two ways to model them (for the comparison)

Both keep the game's walkable cells walkable and its solid cells solid, every fitted model in use, and the floors flat.

- **B, stacking by section.** The first build's stacking (each fitted model lifted onto the face's own blocks to its
  own rows), but only in `face`, `mass` and `mouth` sections; `objects` and `trees` stay on the ground as they stand
  today (the narrowed build). The stacked look the user liked, without the towers and pillars.
- **C, whole sections.** A `face`, `mass` or `mouth` section stands as one shape: every column of it as tall as its
  face (the drawn rows of its front, at most the screen's), its front showing the drawn rows, its top flat and showing
  the top tiles, its fitted models standing in the front at their rows (as in B). Unlike B its height is the same
  across its width and its top is flat, so a cliff reads as one cliff and a mountain as one mass; a `mouth` is the
  face with its mouth model at the foot. The flagged landmark gets its parts model.

## 4. Steps

1. The first pass and the renders for the seven maps (pod, pixel-free output; renders private).
2. The marking guide, then one agent per map; I check the marks against the renders; sheets on kb-hub for the user.
3. The planner reads the table behind a build property and models it as B or as C (`stackFaces` by kind; C's section
   template), with tests on made-up maps for each kind; nothing becomes the default.
4. The seven maps built into two pilot worlds (B and C, `build_world.py --maps`), the seven spots photographed in each
   (`context_shots.py`, the same spots and pitch as before).
5. The sheets sent to the user: the game, A, B, C per spot. The user picks (or a mix per kind); the plan for rolling
   it out follows from that.

Cost: the pod about 3-4 hours at $0.184 an hour; seven marking agents.

## 5. Checks

- The marks: every solid cell of the seven maps in exactly one section or left to its default (counts per kind).
- The models: the visible-layers check (D9) per variant; walkable and solid cells unchanged; the quad budgets
  (Kanto's densest section stands at 29,648 of 30,000 quads today).
- The pictures, against the game.

## 6. What it gives W6 later

The same marks answer what the data can't for Crystal and Blue: a `mass` or a `face` with floor on top of it says
that floor is a level up, by the face's drawn rows; Emerald's elevations cross-check it. Rolling out means marking
about 185 outdoor maps (the surface and the open-sky maps), the user's review, and the variant the pilot picks.
