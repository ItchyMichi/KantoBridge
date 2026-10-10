# Plan: Wild Routes, generated routes and dungeons off Kanto's maps

- **Status:** proposed on 2026-10-10 from your choices R1–R9. Not decided, and nothing is built. Each open question
  in §9 has a default.
- **What it delivers:**
  - **New exits** added to some of Kanto's maps, for example a path through Viridian City's trees. Each one is a
    warp to a **generated route** in the empty overworld.
  - **Each route is one long path.** Its DWM-style areas are joined edge to edge, each turned so that its entrance
    meets the last area's exit, with no fade in between. Inside are branches, dead ends, trainers, item balls and
    tall grass.
  - **Connecting routes** come out in another ROM map. **Free-roam zones** are dead ends: you leave the way you came.
  - **Dungeons** sit in dead ends, 0–1 per route. They are PMD-style floors made new on every visit, one copy per
    party.
  - Routes are made **once per world**, when the world is built.
- **Why:** your brief and your choices of 2026-10-10.
- **Sources:**
  - [ROUTES_RESEARCH.md](../research/ROUTES_RESEARCH.md) and its prototypes.
  - Gen1Recomp v0.3.49's own modding docs and source on GitHub: the registry reference, `docs/modding.md`,
    `WorldAPI.lua`, `Commands.lua` and `OverworldController.lua` (read, nothing copied).
  - A read of today's code (Appendix A).
  - A new prototype of the joined-up routes (Appendix B).
- **Size:** eight phases, WR1–WR8. About 7–8 pod hours in all (under $4 at $0.48 an hour), plus checks on your PC.

---

## 0. How to execute this plan

- **CLAUDE.md's rules apply unchanged.** Nothing ROM-derived is opened, logged or committed. Pins don't change
  without your OK. Anything ambiguous, or a step that fails, stops the run with a question.
- **PLAN.md §0.2–0.5 apply as they do to the milestones:**
  - one commit per phase, titled `WR<n>: <title>`;
  - a "Wild Routes" section in PROGRESS.md;
  - new items in PLAYTEST.md.
- **Where things run:**
  - Code is edited on the server.
  - Java builds and tests run on the pod `kb-dev` through `kb-pod`. Each session needs your OK, and the pod is
    stopped after it.
  - Lua tests (`tools/lua`) and the Python tools run on the server, for free.
- **Data rules for this feature:**
  - The route list holds only map ids (the engine's symbols, as everywhere in the repo), cell coordinates, counts
    and our own text. It never holds block ids, tiles, map contents or ROM text.
  - Every block the hidden game's maps gain is copied at boot from the player's own data, from spots the route list
    names. Logs print only ids, counts and hashes.
  - You choose where each exit goes, in your own game. The preview shows it to you, not to the agent (§4.2).
  - Tests use the synthetic bundle (`tools/fake_gen1`) and synthetic route lists.
- **DESIGN.md is yours.** This plan adds exits to the ROM's maps because you asked for that (R1). DESIGN.md §3's line
  "joined edge to edge exactly as the original game connects them" still holds for Kanto's own maps. Whether to add
  a line about Wild Routes is up to you; I won't edit it.

## 1. Your choices

| # | You chose | What the plan does |
|---|---|---|
| R1 | Extra routes, reached through new exits added to the game | A new clearing and warp in a ROM map lead to a small new map in the hidden game that stands for the route (§4.2). Connecting routes come out in another ROM map (my reading of "a new ROM world space"); free-roam zones lead back |
| R2 | They work as normal routes | Always open; nothing in Kanto is replaced or blocked. Each route has whatever story check its far end needs |
| R3 | Routes once, dungeons every visit | Routes are built with the world, from its seed. Dungeon floors are made on each visit |
| R4 | Vanilla terrain with voxel props | Terrain from Minecraft's own noise. Maze walls, rocks, item balls and signs are props drawn from their block state |
| R5 | One long area per route, the areas turned and joined the way routes join towns | Areas chained edge to edge with their openings lined up and no fade. Branches and dead ends come from DWM's layout, plus an optional side area. No gatekeeper (§4.3) |
| R6 | Difficulty from the neighbouring maps' wild levels | Each route's species and levels come from the ROM maps beside its two ends. Levels change along the route from one end's range to the other's |
| R7 | Rewards into the hidden bag and money | The hidden game gives the items and pays the prizes itself (§4.6) |
| R8 | 0–1 dungeon, in a dead end | A dungeon entrance in a dead-end block, or at the end of the side area |
| R9 | One copy per party | Players who enter at the same entrance within 5 seconds share a copy |

## 2. How it plays

1. In Viridian City, a new path runs into the trees and ends at an opening. You step on it, the usual fade plays, and
   you arrive at the **start gate** of VIRIDIAN WILDS (a name we'd give it) in the route zone, far from Kanto.
2. From there the route runs on with no more fades: clearings, lanes, ponds, tall grass, and dead ends with item
   balls. Trainers spot you along their line of sight, walk up, speak and battle. Wild Pokémon appear in the grass
   as they do in Kanto.
3. A dead end holds a cave entrance to a **dungeon**, which is new every time you go in. Its floors are linked by
   stairs. Wild Pokémon wait in the rooms, items lie about, and sometimes there's a Monster House. The last floor's
   exit brings you back out of the same entrance.
4. A connecting route ends at the **far gate**. You fade out and step out of a new opening in another map, say
   Cerulean City. A free-roam zone ends in a dead end instead, and you walk back to the start gate.
5. If you come back later, the route is the same. Trainers you've beaten stay beaten and balls you've taken stay
   gone, for you only; other players have their own.

The figures come from the new prototype (Appendix B):

![A connecting route: four areas joined edge to edge, plus a side area (top) holding the dungeon (orange ring). Cyan: the start gate. Gold: the far gate. Red: item balls. Purple: trainers, with their lines of sight in pink. White squares: the areas, 96×96 blocks each.](wild_routes/connecting_route.png)

![A free-roam zone: three areas, with a dungeon in the last dead end. You leave through the start gate.](wild_routes/free_roam_zone.png)

In both games at once:

```text
Kanto (both games)            route zone (Minecraft only)                        Kanto (both games)
VIRIDIAN_CITY  ── warp ──▶  start gate ~~ areas, no fades ~~ far gate  ── warp ──▶  CERULEAN_CITY
                              [A][x1]                        [x2][B]
hidden game:                KB_ROUTE_<ID>, one row of four cells: [A][x1][x2][B]
```

## 3. What changed since the research

- **No away hold.** Each route gets a small map of its own in the hidden game, a *route map*. While you walk the
  route, the hidden player stands in it. That's a real place in the game, so saving, loading, Fly and blacking out
  all work the game's own way. The research's border machine (VR A4.2) isn't needed.
- **The hidden game does more of the work:**
  - Item balls use the engine's `give_item`: the bag, the jingle and the "bag full" refusal.
  - Trainers use its scripted trainer battle. That's the path a story script's `start_battle` already takes through
    the bridge, so the prize money, the battle music, the trainer's loss line and the blackout all come for free.
  - Trainers' lines are its text boxes, mirrored as they are today.
  - That drops four pieces from the research's list: the `bag.add` act, the `money.add` act, a battle started by
    the server, and text boxes sent by the server.
- **Still new:**
  - route trainers and balls drawn by the server;
  - lines of sight checked on the server;
  - props drawn from their block state;
  - route habitats;
  - a blackout act for wild battles. It also closes a gap in today's game, where losing a habitat battle in Kanto
    doesn't black you out.
- **Built with the world**, not on first approach. That's simpler, and the route maps' positions are known when the
  WorldIndex is written.
- **One long route** with no gatekeeper (R5). Levels come from the neighbouring maps (R6).

## 4. Design

### 4.1 The route list

The list is one file, `gen1-bridge/data/routes.json`. gen1-bridge reads it at boot and passes it to the builder in
the world export, so both halves use the same list.

| Field | What it says | Example |
|---|---|---|
| `id` | Our id for the route | `viridian_wilds` |
| `name` | The name on the gate's sign, in our own words | `VIRIDIAN WILDS` |
| `from` | The map and spot of the exit (§4.2) | `VIRIDIAN_CITY`, a cell and a direction |
| `to` | The far end's map and spot, or `null` for a free-roam zone | `CERULEAN_CITY`, … |
| `areas` | How many areas on the main line | 3 |
| `sideArea` | The chance of a side area | 0.5 |
| `dungeon` | The chance of a dungeon, and its floors | 0.6, 3 |
| `theme` | The biome and the set of props | `forest` |
| `requires` | The badges and event flags needed to go in, each way | `BOULDERBADGE` |
| `pools` | Where species and levels come from (R6) | `neighbours` (the default) or a list of maps |
| `music` | The route map's song | a Gen 1 route theme |

Ids are the engine's own symbols, as in the rest of the repo. The file holds nothing from a map's contents.

### 4.2 The hidden game: new exits and route maps

Gen1Recomp's content registries are built for this. Its `docs/modding.md` says: "An edited vanilla map becomes a
`mod.content.maps:patch` carrying only the fields that moved; a new map becomes a `:register`." gen1-bridge does both
in its entry chunk, before the engine merges everything:

- **The exit** is a patch of the town map:
  - A short run of blocks through the tree line becomes path, ending in an opening. The opening is a warp: a new
    entry added to the end of the map's `warps`.
  - The path copies a road block of the same map, next to the exit. The opening copies a block with a warp tile from
    a spot you pick: a cave entrance or a gatehouse door.
  - Before patching, a **signature check** uses the engine's own map queries. Every cell to be opened must be
    unwalkable now, and the exit's mouth must touch the town's walkable ground. After patching, the path must be
    walkable and the opening must be a warp.
  - If any check fails, that route is switched off, and the log names the check and the route's id.
- **The route map**, `KB_ROUTE_<ID>`, is one row of four cells: `[A][x1][x2][B]`.
  - A and B are warp cells. A leads to the town's new opening; B leads to the far end's. A free-roam zone's route
    map is just `[A][x1]`.
  - It uses the overworld's tileset, so the engine treats it as outdoors and Fly works from it, as on any route.
  - Its blocks are picked at boot by asking the engine which of the tileset's blocks are plain floor and which hold
    a warp, the way `engine.tileProbe` already probes tiles.
  - Nobody ever sees it: Minecraft doesn't draw it, and the hidden window is hidden.
- **The story check** is a small map script on the town map (map scripts compose with the engine's own). If you
  step into the exit's mouth without the badge or flag the route needs, you see a line of our text and are walked
  back one step. The engine's badge gates (`field.badgeGates`, the Route 22 and Route 23 guards) work the same way.
- **Trainer classes:** at boot, `KB_<CLASS>` records are registered as copies of the Gen 1 classes routes use (the
  name, picture, prize money and AI). Each has one party, rewritten before every route battle.
- **New acts.** PROTOCOL.md and the schemas change first.
  - `route.give {route, id, item}`: a queued script (`mod.world:queueScript`) runs the engine's `give_item`. The
    answer says whether the item went into the bag.
  - `route.battle {route, id, class, party, lines}`: writes the party, then a queued script shows the trainer's line
    and runs `start_battle trainer`. The bridge's existing script-battle path does the rest, including the prize.
  - `route.blackout`: the engine's own loss handling (`afterBattle("lose")`: heal, lose half the money, go back to
    the last Pokémon Center). It's for wild battles lost in Cobblemon, in routes and in Kanto.
  - `queueScript` refuses while another script is running, so these acts retry until the world is free.
- **Progress** (trainers beaten and balls taken, per route) lives in the hidden save's mod data, under
  `mod.save "routes"`, next to the bridge's other mod data.
  - It rolls back with the bag and money when an older save is loaded, as Kanto's own balls and trainers do.
  - The snapshot carries it to the server.
- **The export** adds three things:
  - the patched maps, built like any other map, so the exit appears in Kanto;
  - the route maps, flagged as route maps so the builder doesn't make them interiors;
  - the route list.
- **Two dev commands, for you:**
  - `/kb route mark <id> from|to` records the spot where you stand and the way you face as an exit.
  - `/kb route preview <id>` applies that exit live in the hidden game's window (`mod.world:replaceBlock`, which
    works on the current map only), so you can look at it. It prints only counts.
  - An exit becomes part of a world when the world is built.

### 4.3 Generating a route

The prototype does all of this (Appendix B). WR1 writes it in Java.

- **Areas** are the research's DWM areas: 4×4 blocks of 12×12 cells each (96×96 Minecraft blocks), drawn with our
  own block designs.
  - Each area is made with its entrance on its own bottom edge.
  - Its exit goes on the top, left or right edge (odds 2:1:1), in the farthest block on that side.
- **Joining areas:**
  - The next area is turned so its entrance faces the exit, then slid along the shared edge until the two 2-cell
    openings meet. There's no fade and no gap: the edge works like a seam between two Kanto maps.
  - If the new area would overlap one already placed, it's made again. If that keeps failing, the area before it
    gets a different exit side.
- **Branches and dead ends:** DWM's layout gives T-junctions, crossings and dead ends inside each area. A **side
  area** (a dead-end branch off the main line) is optional for each route.
- **The ends:** a start gate stands behind the first entrance. On a connecting route, a far gate stands past the last
  exit. On a free-roam zone, the last area has no exit.
- **The dungeon (R8):** in a dead-end block in the far half of the route, or at the end of the side area.
- **Items** follow DWM's counts by tier, scaled down to Gen 1 numbers. They go in dead ends first and never anywhere
  you can't reach. Gen 1 items only.
- **Trainers** stand on lanes with 3–4 cells of sight, apart from each other. They're never on the only way through,
  or between you and an item. 1–3 per area.
- **Levels and species (R6):**
  - They come from the habitat pools of the maps beside each end. By default that's the end's own map and the
    outdoor maps connected to it, where they have grass tables.
  - Area *k* of *n* takes its levels part of the way from the start's range to the far end's.
  - A free-roam zone uses its town's neighbours and adds one level per area, up to three.
  - Trainers get Gen 1 species from the same pools, chosen by class, at 0–3 levels above the area's highest wild
    level.
- **Seeds:** the world seed plus the route's id. The same world gives the same route every time (R3).
- **The prototype's numbers**, over 300 routes (3–4 areas on the main line, a side area in a third, a quarter of
  them free-roam):
  - 146–415 cells from the start gate to the far gate. The median is 266, about 70 s of walking without stops.
  - 2–18 dead ends (median 9) and 11–43 junction blocks.
  - 1–10 item balls (median 6) and 3–13 trainers (median 8).
  - Every route fits inside 576×576 blocks.
  - At most one retry, and 40 ms per route in Python.

### 4.4 Building it

- **The route zone** is at least 3,000 blocks from Kanto's box. It's cut into slots of 640×640 blocks (40×40
  chunks), one route per slot.
- **Terrain (R4)** comes from Minecraft's own overworld noise for a seed, sampled without generating any chunks: the
  height and biome of each column, from a `RandomState` (research §6.1).
  - In v1, walkable cells are flat at Kanto's walking level (feet at Y 64), so today's spawn rule and lines of sight
    work unchanged.
  - Walls and the land around the maze keep the noise's hills.
- **Props:** maze walls, rocks, balls, signs, gate arches and dungeon entrances are prop blocks that carry their kind
  in their block state, and the client draws each one by kind (research §6.2).
  - They use built-in models we make.
  - Texture packs can replace them through `prop:` keys.
  - A "Kanto look" option comes later.
- **Scenery:** vanilla features (trees, flowers, rocks) are placed from code on the land around the maze and inside
  clearings, never on lanes.
- **Gates** are small plazas, never turned. Their two mapped cells match the route map's: `[A][x1]` at the start gate,
  `[x2][B]` at the far gate. The route's opening meets the plaza from whichever side the route arrives on.
- **Habitat blocks** sit under the tall grass and point at the route's pools.
- **A build stage** runs after Kanto's. For each route it generates the layout, places it and writes it.
  - The WorldIndex gains the route boxes and each route map's two pieces.
  - The build report gains counts and times per route.
  - My estimate is 5–10 s per route at today's placing speed. Check C2 measures it.

### 4.5 Moving through it

- **Into the route:** the hidden warp into the route map is followed like any other warp. `WarpService` finds the
  cell in its piece, at the start gate or the far gate.
- **On the route:** inside a route box, `MovementSync` sends no steps and the drift rule is off. The hidden player
  stands on x1 (or x2).
- **At the gates:**
  - Walking back onto x1 needs nothing.
  - Reaching x2 at the far gate sends one ordinary step, x1 → x2: they're neighbours in the route map.
  - Stepping onto B is the warp into the far map.
  - The other way round works the same.
- **Leaving any other way** (Fly, Teleport, a blackout) is a hidden warp, and Minecraft follows it as it does today.
- **Rules** are Kanto's: Adventure mode, no jumping, noon, Peaceful.

### 4.6 Trainers and items

- **Drawn by the server:** the server sends each player the route's trainers and balls: their positions from the
  layout, their state from that player's progress. Your client draws them with the puppet renderer and your pack's
  sprites.
- **Sight** follows the Gen 1 rules, checked on the server: the same row or column, within the trainer's range,
  nothing in between, facing you. You're held still, the trainer walks up and shows "!", and then `route.battle`
  runs.
- **Collision:** each trainer stands on a shared blocker. Balls use the per-player story cell, so a taken ball is gone
  for you only.
- **Balls:** clicking one sends `route.give`. If the item went into your bag, the ball disappears, for you.
- **Lines:** a list in the mod (our own text), chosen by trainer class.

### 4.7 Wild Pokémon

- At build time, each area gets a pool made from its route's neighbouring pools (R6), written next to Kanto's 46.
  `HabitatService` learns about route habitats from the route boxes in the WorldIndex.
- Route biomes ship in the mod and belong to none of Cobblemon's spawn tags. Floors sit at Y 63, so today's spawn
  rule applies once it lists the route biomes.
- Losing a wild battle sends `route.blackout`, in Kanto too.

### 4.8 Dungeons

- **Going in:** stepping into the entrance starts a visit. Everyone who steps in within 5 seconds shares one copy
  (R9). You fade into a free slot of the dungeon zone, another area at least 3,000 blocks from Kanto and from the
  route zone.
- **Floors** are PMD's standard floor (research §5), seeded from the visit: 112×64 blocks, cased in stone, lit
  according to the theme. The first floor is built behind the fade. Each next floor is built while you play the one
  before it.
- **Inside:**
  - Wild Pokémon stand at PMD's enemy spots, spawned with `EncounterService`, and more appear over time.
  - Some floors have a Monster House.
  - Items go into your bag through `route.give`.
  - Stairs fade you to the next floor.
- **The last floor** ends in a treasure room. Its exit brings you back out of the entrance. Each route's dungeon has
  the number of floors its entry in the route list gives (3 by default).
- **The hidden game** stays in the route map the whole time. Fly is refused inside: the hidden game would allow it,
  because as far as it knows you're standing on the route.
- **Limits:** dungeon item tables have no Rare Candy or Master Ball, and at most one valuable item (a Nugget) per
  visit. A slot is cleared when its last player leaves.

### 4.9 Multiplayer

- Routes are shared places. Trainers and balls are per player: puppets per client, progress in each player's hidden
  save.
- Dungeons are one copy per party, as above. A slot is reused once its party has left.
- Battles stay visible only to the player fighting, as today.

## 5. Phases

The tags are PLAN's: **[L]** gen1-bridge, **[C]** client, **[S]** server, **[B]** builder, **[T]** tests,
**[D]** docs and protocol.

### WR1: The generators

- [S] Route areas, joining, side areas, items, trainers, dungeon spots and levels; then PMD floors.
  - Pure Java, with no Minecraft classes.
  - Our own table of block shapes and a modern random number generator. The port that matches DWM exactly stays in
    the prototype, as a reference.
- [T] JUnit:
  - golden layouts for fixed seeds;
  - checks over 10,000 seeds for each generator: openings meet, nothing overlaps, everything is reachable, and
    nothing blocks the way through.
- **Pod:** about 30 minutes. **Needs you:** nothing.

### WR2: The hidden game's exits and route maps

- [D] The route list's schema, the new acts and the export's additions.
- [L]:
  - loading the route list;
  - at boot: the exits, the route maps, the story checks and the trainer classes;
  - the acts `route.give`, `route.battle` and `route.blackout`;
  - progress in `mod.save`;
  - the export's additions;
  - the two dev commands.
- [T] Lua tests with the fake engine: the patch maths, the signature checks, registration, the scripts and the
  acts. Export tests with the synthetic bundle.
- [T] End to end on the real engine, in the test instance:
  - walk into a test exit, arrive in the route map, and step back out;
  - receive an item;
  - win and lose against a generated trainer;
  - black out after a wild battle.
- **Pod:** none; Lua and Python run on the server.
- **Needs you:**
  - Mark the first exits with the dev commands (about 10 minutes each).
  - Run the end-to-end tests on your PC, or give your OK to run them on the pod.

### WR3: Building routes

- Check C2 first: stamp one route into a void world and measure the time, the chunks and the cost per tick.
- [B] The route stage: zone and slots, terrain, props (server side), scenery, gates and habitat blocks. Then the
  WorldIndex additions with their schema, and the report.
- [T] JUnit: slots, pieces and index round trips.
- [T] GameTest: a synthetic route world. Openings are walkable, walls are solid, gates are mapped.
- **Pod:** about 1.5 hours.

### WR4: Moving through routes

- [C] `MovementSync` inside route boxes and at the gates. Props drawn from their block state, with the built-in
  models.
- [S] `WarpService` into pieces; the route rules.
- [T] JUnit for the movement cases; GameTests for the warps.
- Check C5 needs a client: your PC, or the pod under Xvfb with your OK.
- **Pod:** about 1 hour.

### WR5: Trainers and items

- [S] Route trainers and balls sent to clients; sight; the walk-up; `route.battle` and `route.give`; progress from
  the snapshot.
- [C] Puppets from the server's feed; clicks on balls.
- [T] JUnit for the sight rules. GameTests with `fake_gen1`: a generated battle through the script path, an item
  received, and a full bag.
- **Pod:** about 1 hour. **Needs you:** an end-to-end session on your PC.

### WR6: Wild Pokémon

- [B] Route pools, written at build time.
- [S] Route habitats, the route biomes in the spawn rule, and the blackout act on lost wild battles.
- [T] Check C3 as a GameTest: a mix of two pools, with levels in range. The blackout through `fake_gen1`.
- **Pod:** about 30 minutes.

### WR7: Dungeons

- [S][B] The dungeon zone and its slots; parties; floors built behind the fade; stairs; spawns and Monster Houses;
  items; the treasure room; leaving; the limits.
- [T] JUnit for slots and parties. GameTests: in, down two floors and out; two parties at once; a blackout inside.
- **Pod:** about 1.5 hours. **Needs you:** an end-to-end session.

### WR8: Content and play-test

- With you: the full route list, themes, names, trainers' lines and item tables. The research's special areas (rest
  stop, item clearing, hedge maze, Mart stall, trainer gauntlet) if you want them.
- [T] PLAYTEST.md items, then a play-test on your PC.
- **Pod:** about 1 hour.

**In all:** about 7–8 pod hours (under $4), each session with your OK. WR1 and WR2 can run side by side; the rest go in
order.

## 6. Checks

| # | What | Where | When | Cost |
|---|---|---|---|---|
| C2 | One route stamped into a void world: noise sampling, features, one structure. Time, chunks, cost per tick | The pod, server only | Start of WR3 | About $0.50 |
| C3 | A generated battle through the script path; a mix of two habitat pools | The pod, GameTests | WR5, WR6 | About $0.30 |
| C5 | Props drawn from their block state; fades at the gates | A client: your PC, or the pod under Xvfb with your OK | WR4 | About $0.50 |
| C6 | The route map and exit on the real engine: registration, the story check, `give_item` and `start_battle` from a queued script, a save and load inside the route map | Your PC, in the test instance | WR2 | Free |

The research's C1 becomes WR1. Its C4 becomes part of WR2's Lua tests, minus the away hold, which is no longer needed.

## 7. Risks

- **Nothing has been played in-game yet** (ROADMAP.md), and Wild Routes stacks on that unchecked base. A short
  play-test of plain Kanto before WR4 would help.
- **The engine:** the map changes, scripts and gifts use Gen1Recomp's public mod API. Rewriting a trainer's party and
  the blackout reach into the engine's internals, as much of `kb_engine.lua` already does. Both are checked again on
  every pin change.
- **Saves inside a route map** need gen1-bridge loaded, as today's saves already need its mod data. A route that a
  world uses is never removed from the list.
- **Changing the route list** after a world is built doesn't change that world in v1. New exits need a new world.
- **Story:** a connecting route can skip part of the story. Each route's check has to match what its far end needs
  (§9 Q3).
- **Effects that count steps** (poison damage outside battle, Repel, the daycare's EXP) don't tick on routes, because
  no steps reach the hidden game there.
- **Balance:** route rewards come once per player, and dungeon rewards are capped (§4.8).
- **Build time and disk:** 5–10 s and about 2–4 MB per route. Both are estimates; C2 measures them.
- **Licences:** the generators are re-implemented from your notes, with our own tables. No DWM or PMD code, assets or
  names. Pools, sprites and trainer classes come from each player's own export and stay local.

## 8. Later, not in this plan

- Sea routes (with Surf), and caves or stepped terrain inside routes.
- Escape Rope and Dig inside dungeons (a cave route map for each dungeon).
- Fresh layouts each time you go back in, or routes made new on every visit.
- Adding routes to a world that already exists.
- Rematches with route trainers.

## 9. Open questions

| # | Question | Default |
|---|---|---|
| Q1 | Which routes first? | A free-roam zone off Viridian City (3 areas and a dungeon), and a connecting route Pewter City ↔ Cerulean City (4 areas), the long way round Mt. Moon |
| Q2 | What does a new exit look like in Kanto? | A path through the tree line, ending at a cave entrance |
| Q3 | Story checks | Whatever the far end needs if you go the normal way. Pewter ↔ Cerulean needs the Boulder Badge. Viridian's zone needs the Pokédex, since you can buy Poké Balls from then on |
| Q4 | Route length | 3–4 areas on the main line, and a side area half the time: about 10–15 minutes to cross, with battles |
| Q5 | Levels in free-roam zones | The town's neighbours, plus one level per area, up to three |
| Q6 | Dungeon size and how it ends | 3 floors, ending in a treasure room. No boss in v1 |
| Q7 | Route trainers | Beaten once, for good, as in Gen 1 |
| Q8 | When routes are built | With the world (default), or the first time someone comes near |

## Appendix A: references

Prefixes: `M/` = `fabric-bridge/src/main/java/dev/kantobridge/`,
`C/` = `fabric-bridge/src/client/java/dev/kantobridge/client/`, `G/` = `gen1-bridge/`.
Gen1Recomp paths are in its repository at tag v0.3.49.

| Fact | Where |
|---|---|
| Maps edited by mods: a patch for an edited map, a register for a new one | Gen1Recomp `docs/modding.md` ("Editing maps in Tiled") |
| The `maps` record (blocks, warps `{x, y, destMap, destWarp}`, …), `map_scripts` (compose), `trainers` (name, parties, base money), `field.badgeGates`, `map_songs` | Gen1Recomp `docs/modding/reference/registries.md` |
| Patches merge tables and replace lists; `__append` adds to a list; registrations happen in the entry chunk, before the merge | Gen1Recomp wiki, "Concepts: Registries" |
| `queueScript`, `replaceBlock` (current map only), `warpTo`, `startWildBattle` | Gen1Recomp `src/world/WorldAPI.lua:626-632, 461-466, 358-370, 638-675` |
| `give_item`, `start_battle` | Gen1Recomp `src/script/Commands.lua:250-292, 339-393` |
| Blackout: heal, half the money, warp to the last heal point | Gen1Recomp `src/world/OverworldController.lua:5184-5225` |
| Badge-check guards | Gen1Recomp `src/world/OverworldController.lua:4873-4972` |
| The bridge's script battles (`start_battle trainer` needs `data.trainers[class]`) | `G/lib/kb_engine.lua:1681-1772` |
| Prize = base money × the last Pokémon's level, paid when the result comes back | `G/lib/kb_engine.lua:1573-1584, 2085-2125` |
| Probing a tileset's tiles with the engine's own map | `G/lib/kb_engine.lua:5027-5047` |
| The bridge's mod data in the hidden save | `G/act/kb_party.lua:40, 71`; `G/act/kb_save.lua:41, 76` |
| The export reads pristine data, so it applies the route patches itself | `G/export/kb_export.lua:259-264` |
| Interiors found by a walk over warps (route maps must be left out) | `G/export/kb_export.lua:299-328` |
| Map kinds today: surface, interior | `M/world/MapPlacement.java:23-25` |
| Warps: a teleport to a map's cell through the WorldIndex | `M/server/WarpService.java:98-119` |
| Steps come from crossings in Minecraft; the drift rule | `C/move/MovementSync.java:401-428, 483-499` |
| Habitat pools: overworld only, keyed by the WorldIndex | `M/server/HabitatService.java:105-109, 354-363` |
| Spawning a wild Pokémon from code | `M/battle/EncounterService.java:157-233` |
| Puppets: client only, fed by gen1-bridge | `M/entity/PuppetEntity.java:11-20`; `C/puppet/PuppetManager.java:93-97` |
| Builder stages | `M/builder/BuildPhase.java`, `M/builder/BuildTask.java`, `M/builder/plan/WorldPlanner.java` |
| Everything else the research cited | [ROUTES_RESEARCH.md](../research/ROUTES_RESEARCH.md), Appendix A |

## Appendix B: the prototype

In `/home/claude/kb-routes-proto/` on the server, not in this repo. Python 3.12, standard library only.

- `chain.py`: whole routes, as in §4.3. `python3 -I chain.py` makes 300 routes and prints the numbers above.
- `render_chain.py <dir> [dest:<seed>|roam:<seed> …]`: the figures. They are `dest:7` and `roam:9`.
- One fix on the way: an item could land on a cell cut off by the ragged edge of a clearing. Items now go only on
  cells you can reach. The research's `route.py` still has the old behaviour; its figures are unchanged.
- `dwm.py`, `route.py`, `pmd.py` and `render.py` are the research's prototypes, unchanged.
