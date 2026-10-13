# Next stages: proposals

Ideas for work after M10, written down from play-testing questions: the hidden-player speed-up (1),
voxel shapes (2), first-person dressing (3), the deep dive that designs them as one generative
system (4), and a later vision of several regions and the open world in one Minecraft world (5).
When written, nothing here was decided or built. Since then §2 became the voxel plan and the profile
plan, and §4's research was done; [ROADMAP.md](ROADMAP.md) says where each section stands now. Each
section says what it would change, why, and how we'd know it worked.

## 1. Hidden-player speed-up (less rubber-banding)

> **Firm implementation spec:** [specs/HIDDEN_STEP_SPEEDUP.md](specs/HIDDEN_STEP_SPEEDUP.md)
> (ready to code; gen1-bridge only). The analysis below is kept for background. The spec corrects
> one detail: bridge-driven steps are shortened toward Minecraft's pace with
> `min(in, base) × 100 / STEP SPEED`, which also fixes the Cycling Road uphill mismatch.

### The problem

While walking, Minecraft sometimes pulls the player back ("rubber-banding") when they fall out of
step with the hidden Gen1Recomp.

### Why it happens (M3's movement design)

- **No headroom.** Minecraft walks at exactly Gen 1's pace: `Rules.WALK_SPEED` 0.1737 and
  `BIKE_SPEED` 0.3474 move one 2-block cell per 16 and 8 Gen 1 frames (PROGRESS deviation 33).
- **One step at a time.** Each cell crossing sends `move.step`. gen1-bridge runs one step at a time,
  with at most 2 waiting behind it. A further step drops the oldest waiting one, which is answered
  `player.blocked{script}` (PROTOCOL.md "Movement rules (M3)").
- **Diagonals outrun the hidden game.** A corner cut becomes two orthogonal hidden steps (Gen 1 is
  strictly 4-directional). At 45°, Minecraft covers grid distance √2 ≈ 1.41× faster than walking
  straight, because its input vector is normalised.
  - So the hidden game falls about 0.4 cell behind for every cell walked diagonally.
  - After a few cells the queue overflows and a step is dropped. The hidden player ends a cell
    behind, and `MovementSync` snaps the Minecraft player back to it.
- **Slow frames** in either game do the same, without diagonals.
- **Other corrections are legitimate:** refusals (an NPC moved into the cell, a tile the hidden game
  treats as blocked, a script starting), and drift steering after 500 ms.

### Proposal

Make the hidden player step faster than Minecraft, only for the steps the bridge drives.

- **No new mod needed.** gen1-bridge is a Gen1Recomp mod, and Gen1Recomp has a public hook for
  exactly this: `movement.speed`. `Player:stepLength(dir)` (`src/world/Player.lua:120-145`) passes
  the step's frames through it with `{onBike, slope, dir, surfing, player, input, save}`. The comment
  there reads "movement.speed lets a mod multiply or replace that (running shoes, dash, etc.)".
- **The rule:** while a client is connected and gen1-bridge is running one of its own `move.step`s,
  return half the frames (factor 0.5):
  - walking 16 → 8;
  - bike 8 → 4;
  - surfing 16 → 8.
- **Untouched:** Cycling Road slopes, ledge hops, spinners, currents and every scripted move
  (cutscenes, door walk-outs, forced movement). Their timing stays Gen 1's, so the client's
  `ForcedMover` animations and cutscenes still match.
  - The hook doesn't say who started a step, so gen1-bridge marks its own: it knows when it starts
    one.
- **Minecraft keeps its speed.** Its speed is a fixed server rule, not taken from the hidden game,
  so Minecraft stays the pace-setter.
  - The hidden game gets 2× headroom, more than the 1.41× diagonals need.
  - It finishes each step before the player reaches the next cell, so the queue no longer fills.
- **A setting:** a gen1-bridge mod option, "STEP SPEED" (1×, 1.5×, 2×, default 2×), to compare by
  feel.

### What changes and what doesn't

- **Game logic doesn't change.** Gen 1 counts steps, not time: encounters (surf and fishing), trainer
  sight lines, coordinate triggers, and the daycare, poison and Safari step counters all see every
  cell exactly as before.
- **Wandering NPCs** move by time, so they move half as far per player step. It's barely visible:
  their Minecraft puppets follow the hidden NPCs at the NPCs' own pace.
- **The hidden window** shows the player walking at double speed.
- **The hidden player is now slightly ahead.**
  - A door or trainer script starts while the Minecraft player is still finishing the cell. The
    client already holds the player for scripts and steers them onto the hidden cell, which is the
    same or an adjacent one.
  - Refused steps come back sooner, so snap-backs get shorter.
- **No protocol change.** `player.pos.frames` reports the shorter steps; the client ignores frames
  for steps it asked for itself.

### Alternatives, and complements if needed

All on the Minecraft side:

- **Scale diagonal speed** so that grid progress never exceeds one cell per 16 frames (about 0.71×
  at 45°). It works without touching Gen1Recomp, but diagonals feel slow.
- **An optional 4-direction movement mode**, closer to Gen 1: with two keys held, the newest one
  wins.
- **A deeper step queue** (3–4 waiting) only delays the overflow. It doesn't remove it.

### Tests and how we'd know it worked

- **lupa:**
  - the hook halves only bridge-driven walk, bike and surf steps;
  - slopes, ledges, spinners, currents and scripts are untouched;
  - it is off with no client;
  - it survives a hot reload.
- **e2e on the test install:**
  - a diagonal staircase walk with pipelined steps drops none (today some are dropped);
  - the measured step length is about 8 frames;
  - encounter and step counters advance once per cell.
- **JUnit:** `MovementSync` against a fake that answers in 8 frames makes no snap-backs on diagonals.
- **Play-test:** walk diagonally across Pallet Town and Route 1 with F7 open. "Move … lost" and
  "Fixes … snaps / drift" should stay at or near 0, where today they climb.

**Effort:** small. A hook and a mod option in gen1-bridge, plus tests. No Minecraft or protocol
changes.

## 2. Voxel shapes and Dramaless Shape (findings)

### Today

The builder re-implements only Dramaless Shape's height rules (`builder/plan/HeightRules.java`):
- walls, fences and trees are 2-block columns;
- roofs, facades and cliffs are 4-block columns;
- water is a half block, and ledges get their art and a one-way collider.

Every tile block's model is `cube_all`: the same tile art on all six faces.

### Installing Dramaless Shape in the hidden Gen1Recomp would not give us its shapes

Only gen1-bridge is installed in either Gen1Recomp install. Dramaless Shape is only the reference
copy in `refs/`. Installing it wouldn't help, for three reasons:

1. **Its renderer would be off.** gen1-bridge pins every render pipeline to level 0, Dramaless's
   "voxel" pipeline included (`kb_engine.lua` `pinDisplay`, PLAN P21). The mirror screen needs the
   classic frame, and the hidden game shouldn't spend time on 3D.
   - Dramaless only feeds its companion host while that pipeline renders. Its `update` returns
     early with `if not Voxel.active() then return end` (`main.lua:129-150`).
2. **Its public API carries little we lack.**
   - Its Voxel Companion API v1 is reachable from gen1-bridge as
     `mod.find("DRAMALESS_SHAPE").exports.voxel_companion`.
   - It hands extensions a snapshot of the **active map only**: per cell, a `kind` (TileShape
     class), a `height` (that class's height), `solid`, `walkable`, tags (tree, door, water, shore,
     mountain) and the ground level.
   - That is what our builder already derives. The interesting parts are private: `Structures.lua`
     (connected regions, measured heights, model choice), `Buildings.lua` (buildings voxelized band
     by band) and the meshes.
3. **Its private modules are the wrong thing to depend on.** gen1-bridge could in principle load
   them during the export: inside Gen1Recomp they have the engine modules, LÖVE and the decoded art.
   But that would:
   - tie us to another mod's private code, which breaks when it updates;
   - need your licence decision, because MIT is credible for its 2.0 work but its Dramatic Shape
     base can't be verified (REFERENCES.md);
   - still leave a conversion from its pixel-space model to Minecraft blocks.

### Recommended path

Re-implement its *documented* techniques in our builder. That generates Minecraft block models from
each player's own export at pack-build time, so no art is shipped. Cheapest first:

1. **Upright faces:** per-face textures. Roof art goes on top, and facade art on the south face. In
   Dramaless's words, the south face "folds the full artwork upright, 8px band by band".
2. **Measured heights:** 1–6 blocks instead of rounding to 2 or 4. Sloped or stepped roofs.
3. **Voxelized buildings and objects:** follow its written methodology,
   `assets/docs/buidling_to_voxel/sprite_to_voxel_methodology.md`. Its stages are extraction, band
   classification, geometry, colour from the sprite, and verification.
   - At our scale one Game Boy pixel is 2/16 of a block, so block-model elements on Minecraft's
     1/16 grid can represent its voxels exactly.
   - Collision stays per cell, so story logic is untouched.
   - **Costs:**
     - more tile-block families, because 3,926 of 4,096 slots are already used;
     - chunk meshing cost, so only object tiles should get it, greedy-meshed;
     - a longer pack build.

Never run its `tools/` or `assets/docs` scripts (`sprite_extract.py`, `voxel_build_verify.py` and
others): they read ROM-derived art.

### Optional: Dramaless Shape as a test oracle only

1. Install it in the **test** instance (`local/gen1recomp-e2e`) only.
2. Turn its pipeline on in an e2e session and walk a few maps.
3. Compare its per-cell classes and heights with our builder's.

This uses only its public API, adds no dependency for players, and checks our re-implementation.

## 3. First-person dressing (ideas from Kanto in First Person)

[Kanto in First Person](REFERENCES.md#kanto-in-first-person-ds_fp_ceiling) (`ds_fp_ceiling`) is a
companion patch to the Dramatic Shape voxel mod that "finishes Kanto's first-person view". Every
feature is presentational, and its licence grants nothing. So we take **ideas only**: none of its
code, art or sounds.

Minecraft already gives us the first-person camera, mouse look, view bobbing and third person. What
it adds maps onto our setup like this.

### In the builder: geometry from each map's own art, generated locally

- **Interior risers:** wall cells rise to the ceiling with their own tile art, repeated per block.
  - **Today:** 2-block walls, a 4-block ceiling, and a ring of the map's border art.
  - The three sides Gen 1 never draws should use the room's dominant wall tile, not border art.
- **Ceilings** textured with the room's dominant wall tile, darkened (rock in caves, plaster in
  houses).
  - Darkened copies are new textures, so they count against the slot capacity.
- **Doors** shaped as doors (frame, panels, a step), generated from the door tile's art. The warp
  cell stays walkable.
- **Caves:**
  - an uneven rock roof, 4–6 blocks high, in the cave's own rock art;
  - vanilla pointed dripstone for stalactites and stalagmites, placed only overhead or on solid
    cells;
  - still pools on non-walkable cells;
  - lanterns on cave walls.
  - Rock Tunnel stays dark until Flash.
- **Viridian Forest:** a leaf canopy with gaps (light wells) and hanging vines, instead of today's
  opaque interior ceiling. Vine spreading is already off.
- **Built (2026-10-11, [specs/CAVES_AND_WOODS_PLAN.md](specs/CAVES_AND_WOODS_PLAN.md)):** the rock roof, stalactites
  and stalagmites in the cave's own art within today's three-block caves (not vanilla dripstone: your CD1), and
  Viridian Forest's canopy, light wells and vines, both by Kanto in First Person's own placement rules re-implemented;
  vines in the wild zone; and (2026-10-12) the forest's apron past its edge, in Minecraft's own logs and leaves. Pools
  and lanterns stay ideas.
- **Built (2026-10-13, [specs/CAVES_3D_PLAN.md](specs/CAVES_3D_PLAN.md)):** caves in an area of their own north of
  Kanto, raised ground with stairs and cliff edges, real ladders between floors that line up (15 of 37), and invisible
  walls on every edge the game blocks. That plan's later ideas (its §8):
  - raised ground in Viridian Forest and the Safari Zone (open sky: the room is there already), and outdoors
    ([research/HEIGHTS_RESEARCH.md](research/HEIGHTS_RESEARCH.md) §3.6);
  - tunnels for the cave links that can't line up (22 ladders keep their fade), and a guided climb as an option;
  - Gen 3's bridges (two floors in one cell);
  - real stairs between a building's floors.
- **Past the map edge:** each edge's terrain continues outward as non-walkable scenery that falls
  away, instead of today's 12-block ring of border art. Mountains or sea would fill the distance.
- **Building backs and tall trees:** these come with section 2. Per-face textures stop a house's back
  showing its front, and trees get trunks under their canopies.

### Vanilla Minecraft systems: mostly settings

- **Day and night, sky and weather.** These are off today: time is fixed at noon and the weather
  cycle is off (`server/KantoGameRules`). Turning them on gives us natively:
  - the sun, moon, stars and clouds;
  - rain and thunderstorms.

  Gen 1 has no clock, so it's purely cosmetic. Points to check: night readability, rain sound
  against Gen1Recomp's music, and lightning (harmless: fire spread and mob griefing are off).
- **Biome effects** in the generated datapack:
  - per-map fog and sky colours (fog over Lavender Town and its tower);
  - water colour;
  - ambient particles, for example falling leaves in the forest and dust indoors.

  Ambient sounds stay optional: sound comes from Gen1Recomp by design.
- **Chimney smoke, cave drips and leaf fall:** vanilla particle types, spawned by the client at
  map-specific spots.
- **Bats:** vanilla mob spawning is off, and Cobblemon has Zubat. Skip them, or spawn a few ambient
  bats ourselves.

### In the client (fabric-bridge)

- **Ledge-hop feel:** a camera crouch, arc and landing settle on top of the `ForcedMover`'s 32-frame
  hop. Only the camera moves.
- **A doorway step:** the camera leans through the door during the warp fade instead of cutting.
- **A horizon panorama** behind the world, from our own or generated art, never theirs. It is
  optional if the scenery past the map edge does the job.
- **A third-person cutaway** in interiors: near walls and the ceiling fade around the player. It's
  hard in Minecraft's renderer; last.

### Probably skip

NPC umbrellas, aircraft, rainbows, puddles and wind-blown grass. They need custom rendering or
shaders for little gain.

### Rules that carry over

- **Presentational only:** collision stays per cell, and story cells, warps and scripts are
  untouched.
- **Multiplayer-safe:** world geometry is the same for everyone, and client effects are per player.
- **No ROM-derived art ships:** it is generated from each player's own export.

## 4. The deep dive: a generative 3D presentation system

> **The research brief** for this deep dive, together with section 5's first step (Kanto inside a
> vanilla overworld with seamless edges), is [research/VOXEL_WORLD_SPEC.md](research/VOXEL_WORLD_SPEC.md).

Scope agreed with you on 2026-10-03. This is research and design only. The output is a design spec, a
milestone plan and a short list of decisions for you; no code. Third-party mods are studied for
ideas and documented rules only (licences in [REFERENCES.md](REFERENCES.md)).

### 4.1 Terrain geometry

- **A complete inventory of Dramaless Shape's geometry**, from its source and its written
  methodology, without copying code or its tables. It covers more than section 2's three points:
  - its per-tileset tile pins;
  - trees as trunk and canopy (tree hull 16 px, forest canopy 32 px);
  - 6-px ledge lips, water depth and shore;
  - small props voxelized from their art (signs, mailboxes, fences, lamp posts);
  - flowers and grass;
  - cliffs and mountains that span map seams;
  - interior furniture heights (3–32 px);
  - caves;
  - how it decides which pixels are background (see-through).
- **Kanto in First Person's dressing** (section 3).
- **Minecraft's constraints:**
  - scale: a 1.8-block player among 2-block cells;
  - block-model elements on the 1/16 grid;
  - cutout textures for see-through pixels;
  - ambient occlusion and light;
  - each player's closed-cell art (trees to cut, boulders) in the new shapes;
  - multiplayer;
  - pack build time.
- **Left out because Minecraft already does it:** camera modes, sky, shadows, anti-aliasing, 3D
  battles (Cobblemon).

### 4.2 Blocks: today's custom blocks versus a flexible block

- **Today:** 32 block families × 256 states. Each slot is one `cube_all` model chosen through the slot
  map, and the full-block slots are 95% used (3,926 of 4,096).
- **The approaches to compare:**
  1. **Static:** more families, with generated JSON block models per state (shaped boxes and
     per-face art).
  2. **Flexible:** one data-driven block whose model emits its quads per position while the chunk
     mesh is built (Fabric rendering API: `FabricBakedModel`, `QuadEmitter`, `RenderDataBlockEntity`).
     - The world holds only shape ids.
     - Each client builds the geometry for every id from its own export.
  3. **Block entities or entities,** for a few special objects only.
- **Criteria:**
  - capacity;
  - chunk-meshing speed, frame rate and memory;
  - compatibility with the vanilla renderer, Sodium and Iris shader packs;
  - multiplayer (ids travel, art never does);
  - per-player story-cell art;
  - collision (stays per cell);
  - build time;
  - upkeep across Minecraft versions;
  - several regions in one world (section 5: namespaces with no shared fixed capacity).
- **A measured prototype:** Pallet Town built each way, with chunk-mesh time, frame rate and memory
  recorded, before choosing.

### 4.3 Player-made texture packs for the voxel world

- **Stable, readable keys** instead of today's opaque slot numbers:
  - `game/tileset/tile` for tile art;
  - sprite ids for characters;
  - map plus structure for buildings and props;
  - tile classes for shape rules.
- **Layers:**
  - **The base:** generated from the player's own ROM.
  - **Player packs on top:** Minecraft's resource-pack stacking for plain textures, plus a small
    `kantobridge` pack manifest for anything else.
  - Anything a pack doesn't cover falls back to the base.
- **What a pack can override:**
  - textures at any resolution;
  - shape rules per class (tree style, roof style);
  - whole hand-made models, for example imported from MagicaVoxel `.vox` files for characters and
    buildings;
  - dressing (pictures, panoramas, ambience);
  - the character style.
- **Rules:**
  - packs stay on the player's machine, and we ship none;
  - the same pack format serves every game, with a pack naming the game whose keys it targets;
  - packs are versioned and validated.

### 4.4 Characters

- **The three styles, as a setting:**
  1. **Thick cards:** the sprite extruded 1–2 voxels deep.
  2. **Carved voxel figures:** the front and side silhouettes intersected, painted from their views,
     with the sprite's own walk frames.
  3. **Rigged Minecraft-style figures:** limbs animated like a mob.
- **Details to settle:**
  - a per-sprite fallback to thick cards where carving goes wrong;
  - everything generated from each player's own sprites;
  - the walk animation per style;
  - keeping the character style consistent with the terrain style;
  - the player's own look (Minecraft skin or a generated trainer) and how other players see them.

### 4.5 One system for many games

- **Layers:** a front end per game family, then a neutral scene description, then the shared
  geometry core, then the shared Minecraft back end.
- **Paper checks of the scene description:**
  - against Gold/Silver/Crystal: per-tile palettes, time-of-day palettes;
  - against FireRed/LeafGreen/Emerald: metatile bottom and top layers, per-cell elevation, and
    multi-level cells such as bridges.
  - DS games aren't run by Gen1Recomp.
- **The automatic self-check:** re-project the 3D world into the game's own view and compare it
  locally, logging scores only.
- **Personalisation as data-driven mappings:**
  - tile class to Minecraft sound type;
  - map to mood biome (fog, sky, particles);
  - the player's scale from the game's proportions;
  - time and weather options;
  - lights;
  - Cobblemon riding (Surf on your own Lapras) and followers.

### 4.6 Groundwork for several regions in one world

Design the world format, block namespaces, texture-pack keys and rules so that a second region
(section 5) can be added later without a redesign.

### 4.7 Decisions for you, at the end of the deep dive

- How faithful versus how fast.
- Whether to use Dramaless Shape's hand-made tables (licence).
- Which dressing to keep.
- The default character style.
- The block approach, after the prototype.
- How far texture packs go.

## 5. Later: one Minecraft world, several regions, and the open world

### The vision (from you, 2026-10-03)

- **The open world:** step out of Kanto into ordinary Minecraft land. Catch and level Pokémon there,
  and find those changes in your Kanto save when you come back.
- **Johto:** sail across the sea to another continent where Johto lives. Start a Johto game there
  carrying progress from Kanto, and dip in and out of both saves.

This is ambitious and comes after the voxel work, but that work should lay the ground for it.

### What today's design already gives

- **Pokémon** belong to Cobblemon, in Minecraft. The hidden game's party and Pokédex follow Cobblemon
  (`party.sync`, `dex.sync`), so Pokémon caught or levelled outside Kanto reach the Kanto save on
  return with no new mechanism.
- **Saves** are already paired per world and game version (M9), so a Kanto save and a Johto save side
  by side are a small step.
- **Gen1Recomp also runs Gold, Silver and Crystal,** so Johto's story can run in a hidden Gold game the
  same way Kanto's runs in Blue.

### What the system needs

1. **Regions as a first-class idea.** A world holds several regions.
   - **What each region has:**
     - an id (`kanto@blue`, `johto@gold`);
     - the game that runs its story;
     - the export it was built from;
     - an origin and footprint in the world;
     - its own index (today's WorldIndex becomes per region);
     - its own block and texture namespaces;
     - a rules profile.
   - The client always knows which region, if any, the player is in.
2. **Rules per region.**
   - **Inside a region, its own rules apply:** adventure mode, the game's speed, no jumping,
     following the hidden game, habitat-only spawns, a fixed or game-driven time.
   - **Outside, Minecraft's:** free movement, Cobblemon's natural spawns, day and night.
   - Much of this can hang on biomes (no rain, no vanilla mobs) or dimensions (a dimension can fix
     its time). Today's server rules apply to the whole world.
3. **Where regions live.** Keeping the builder origin-relative and region-scoped keeps both options
   open:
   - **One overworld:** a world generator makes vanilla terrain and ocean everywhere except reserved
     footprints where the regions are built.
     - Sailing from Kanto to Johto is seamless.
     - Region edges blend into vanilla land or sea: the "past the map edge" idea in section 3.
   - **A dimension per region:** entered from a dock or portal.
     - Isolation, and time and rules per dimension, come built in.
     - The crossing becomes a transition, for example a boat ride that hides it.
4. **Gates.**
   - You leave and re-enter a region through defined gates: a dock, a route edge.
   - The hidden player waits at the gate you left by.
   - Coming back through another gate warps the hidden player there (gen1-bridge can warp).
5. **Several hidden games.** Two ways to run them:
   - one Gen1Recomp per region (Blue for Kanto, Gold for Johto, on different ports), with the client
     switching connection by region;
   - or one instance that changes game during the crossing, with the voyage hiding the reload.

   Either way it needs a Gen 2 bridge, since Gen 2's engine internals differ. It would speak the
   same protocol, made generation-neutral: species up to 1,025, Gen 2 items, the clock.
6. **What carries between saves.**
   - **Pokémon and the Pokédex are already shared** (Cobblemon). Each region gets them through a
     compatibility profile saying what its game can hold: Kanto the 151 and Gen 1 moves, Johto 251
     and Gen 2 moves. Anything else stays in the PC there, or the region turns it away.
   - **Story, badges, bag and money belong to each region's hidden game today.** Carrying money,
     items, badges or the player's name across means moving some ownership to Minecraft: a
     player-level wallet and bag projected into each game on entry. A decision for you.
   - **Starting Johto with Kanto progress:** Gold's New Game runs as normal, with your name carried
     in. Your team is already yours through Cobblemon, which raises balance questions: level caps, a
     Johto-only party.
7. **Gold/Silver/Crystal's own Kanto** (its post-game) is a different, later Kanto. It could be its
   own region ("Kanto, three years later") or be mapped onto ours. A decision.
8. **Multiplayer.** Each player needs their own ROM for each region they play. Without it they could
   visit as a spectator with placeholder art.

### What the voxel work should build in now

- **The world format:** region-scoped, with origins, and no "one world = Kanto" assumptions.
- **Blocks and textures:** namespaces per region or export, which favours the flexible block (no
  fixed capacity to share).
- **The game-agnostic scene description** (4.5).
- **Texture-pack keys** that include the game.
- **The map edge** as a configurable transition: tree walls today, blending into vanilla land or
  ocean later.
- **Rules** attached to regions, not to the whole world.
