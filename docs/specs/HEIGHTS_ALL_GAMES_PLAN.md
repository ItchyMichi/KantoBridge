# 3D heights for the whole world, all three games (to do, the user 2026-10-20)

The user: every game gets 3D heights across its whole world space, **interiors and exteriors**, in Crystal and
Emerald, plus a check that Blue has them in both too. Queued after Emerald's shapes, together with the warp jobs
(walk-down doorways, stairs/ladder models, cave ladders), since they use the same warp data.

## Where each game is (checked 2026-10-20)

| | Caves | Other interiors | Outdoors, forests, Safari |
|---|---|---|---|
| Blue | 3D: cave area, stacked floors, real ladders, one raised level per map (CAVES_3D_PLAN.md H1-H7) | flat | flat (CAVES_3D_PLAN.md §8 "later") |
| Crystal | flat, warps fade (GEN2_BRIDGE_PLAN.md: "no heights or caves in 3D for Johto yet") | flat | flat |
| Emerald | flat, warps fade | flat | flat |

- The cave builder picks caves by Blue's tileset (`WorldPlanner.CAVE_TILESET = "CAVERN"`); Crystal and Emerald
  already flag caves in their front ends (`map.caveLike()`, `G3Cells.cave`) and export each warp's kind (door,
  ladder, staircase, cave mouth, hole).

## Height sources

- Blue: tile pairs (raised cave ground, exported, read since H1); ledges; for outdoors, cliff edges and one-way ledges
  (HEIGHTS_RESEARCH.md §3.6).
- Emerald: every tile has an **elevation** in the map data (the game's own heights): the best source of the three.
- Crystal: no height data; heights must come from the drawing and collision (ledges, cliff edges, stair and ladder
  tiles, raised platforms), as Blue's outdoors.

## Steps

1. **Blue check (first):** per map, does every raised area in the game have a raised floor in Minecraft, interiors and
   exteriors? A report listing maps still flat that the game draws raised (outdoors, forests, Safari Zone, gyms or
   rooms with platforms), then extend Blue's levels to them (CAVES_3D_PLAN.md §8).
2. **Caves 3D for Crystal and Emerald:** the cave area, stacked floors and ladders, chosen by the front ends' cave
   flag; floor order from map names, with Emerald's own (`1F_1R`, `B1F_2R`).
3. **Raised ground everywhere:** a per-cell floor level from each game's source above, with the builder check that
   Minecraft allows exactly the game's steps (else the map stays flat and is reported); interiors and exteriors.
4. **Pictures and GameTests** per game: a cave with ladders, a raised outdoor area, an interior with a platform.

Limits to revisit: one raised level per cave map and the layer gaps (HEIGHTS_RESEARCH.md); outdoors have open sky, so
room is not a problem there.
