# Plan: several games in one world (regions, links and crossings)

The system that lets more games be imported (Hoenn in Emerald, Johto in Gold, Kanto again in FireRed), built as
**regions** of the one flat overworld, and joined to Kanto by Wild Routes, with the hidden game switching as you walk
across. This plan is the **fundamental system**: everything game-specific (a Gen 3 engine adapter, Hoenn's maps,
each ROM's tweaks) hooks into it later, one game at a time.

Written 2026-10-13 from a read of the code at `fef0e41` (H7: every cave in 3D), the docs, and Gen1Recomp's official
repository (the pin v0.3.49 and the latest release, v0.3.85). `file:line` references are as they stood at `fef0e41`.
The user's design statement of 2026-10-13 governs where the docs disagree: **one dimension, the flat preset, interiors
underground, forests open-air, caves in their own area with 3D height (heights may spread to everything later).** The
research report's vanilla-overworld track (`docs/research/VOXEL_WORLD_REPORT.md` M11–M13: a chunk generator,
footprints, aprons, gate blocks on Kanto's edge) is **not** the direction; its *regions-as-data* ideas (A8.x, B6.x,
M14) are reused here and adapted to the flat world.

The user's note "Pokémon and items from outside Gen 1" (2026-10-13) is the companion for the Pokémon side. Its spec,
`docs/specs/FOREIGN_POKEMON_PLAN.md`, is **not in this checkout** (the note pins `2db4b8b`, which is here, but the
file was never committed here). §4.9 names where it plugs in.

## 0. How to execute this plan

1. **Read first, in order:** `CLAUDE.md`; `docs/DESIGN.md` (the user's; never modify); this plan; `WILD_ROUTES_PLAN.md`
   §4.1–4.2 and §4.10 (the route list, the exit, the room left for other games); `CAVES_3D_PLAN.md` §3 (areas and
   per-cell layouts); `PROTOCOL.md` ("Session", "Saving", "Crash recovery and save pairs", "Wild Routes", "Export",
   "Relay", "Multiplayer rules"); `PROGRESS.md` sections "Wild Routes" and "Caves in 3D".
2. **Ground rules** (CLAUDE.md's hard rules apply unchanged):
   - Nothing ROM-derived is opened, copied or committed. Imports are the user's. Tests use the stand-ins
     (`tools/fake_gen1`, `tools/fake_minecraft`); the real engine only on the test install, counts and ids in logs.
   - **The Gen1Recomp pin stays v0.3.49.** A bump is a separate decision (D11): 36 releases and 252 commits landed
     between v0.3.49 and v0.3.85, `OverworldController.lua` changed in 423 lines, and the Union Room changes every
     Pokémon Center's map (a world rebuild).
   - Protocol and schema changes come first inside a milestone (PLAN.md §7); regenerate `catalog.json`; both stand-ins
     follow. **Protocol stays 1**: every change here is a new message type or an optional field (D10).
   - Feature tests while building; the full suite (`tools/check`, GameTests) at the checkpoints marked ✔ below. Pods
     and the Hetzner server per the standing rules (stop pods after each job; GameTests on the server only while
     MonoLingual is stopped).
   - One commit per milestone, `MG<n>: <title>`; PROGRESS.md gets a "Several games" section; PLAYTEST.md gets the
     checks only the user can do.
   - **Stop and ask** when a step is ambiguous or an engine fact below turns out wrong on the pin.
3. **What is "the system" and what is "per game":** MG1–MG5 are game-neutral and are proven with the stand-ins and
   with **Red ↔ Blue on the real engine** (both are Gen 1 games gen1-bridge already runs, so a Kanto-in-Blue ↔
   Kanto-in-Red crossing exercises every new mechanism with no new engine work). MG6 onward is per game family and
   per ROM, each with its own short spec written when its spikes are done.
4. **The user's part** (pre-flight, §9 lists the decisions): answer D1–D14 or accept the defaults; on the PC, one
   Gen1Recomp install per game (§4.11) with that game's ROM imported; a second e2e import for the Red ↔ Blue test
   (§5, MG5); the spikes that need the PC (S1, S2–S4 on Emerald); the play-tests.

## 1. Your questions, answered

**"Do I need a bridge for each game?"** One bridge **mod**, with one **engine adapter per engine family**, not one per
ROM. Gen1Recomp is one program with three parallel engines, chosen per boot by the game's `engine` field: `gen1` (Red,
Blue, Yellow), `gs`/`crystal` (Gold, Silver, Crystal) and `game3` (FireRed, LeafGreen, Emerald; Ruby and Sapphire from
v0.3.85). A Gold boot never loads `src/core/Game.lua`, `src/world/OverworldController.lua` or `src/battle/BattleState.lua`
(Appendix A.3). gen1-bridge's `lib/kb_engine.lua` is written against the `gen1` engine's internals, so it is the **Gen 1
adapter** already; the other 11,000 lines of the mod (network, protocol, sessions, pairing, save pairs, routes, the
snapshot and story diffs) never touch the engine and are the **core** (Appendix B). A second family means a second
adapter (`lib/engine/game3.lua`) implementing the same interface, loaded when `GameVersion.engine()` says so. Per-ROM
differences inside a family are data (a game profile, the route list's `games`, swap-site tables) and small branches,
as `isYellow()` is today (`gen1-bridge/lib/kb_engine.lua:1673-1678`).

**"Do I need a master bridge for all the games?"** Not on the Gen1Recomp side. The hidden games never talk to each
other and each process knows nothing of the others. The "master" is on the Minecraft side, where it already half
exists:
- **the client's link manager** (§4.5): one TCP link per hidden game — one Gen1Recomp process per game, each on its own
  port, exactly how `tools/launch.ps1 -Players 2` runs two games on one PC today — with one link **followed** (movement,
  screens, story) and the others **parked**;
- **the server's region registry** (§4.6): a world holds several regions, each bound to one game, with its own index,
  shape documents, save pairing, story state and progress;
- **shared state is already Cobblemon's**: the party, the PC and the Pokédex follow every hidden game through
  `party.sync` / `dex.sync` whichever game is followed. The only new shared thing is a small per-player **progress
  record** per region (§4.7).

Why not one Gen1Recomp process that changes game at the crossing: Gen1Recomp can boot another imported game in the same
process only through its own launcher (`returnToLauncher` → `bootGame`), and **no mod API reaches it** — the `mod`
object has no launcher, session or restart facade, and `love.event.quit` is a no-op for mods (Appendix A.2). Even with
an engine-internal poke, the switch tears the mod down (a hot-reload-like reload), loses the export cache, takes
seconds, and the parked game's live state (its hidden player standing on a route map, pending gifts) would have to be
reloaded from a save. One process per game needs none of that. It costs RAM and a window per game (minimised windows
keep full speed: M10). The link manager is written so that a same-process switch could be added later if upstream adds
a facade (§8).

**"An almost seamless transition through the wild routes."** A **connecting route** (WILD_ROUTES_PLAN §4.10, the
generator's unused `CONNECTING` kind and far gate, `routes/gen/RouteSpec.java:26,56`, `RouteLayout.java:27-32`) is
built as **two zone halves**, one in each region's zone area, each ending in a **pass** (a tunnel mouth, the dungeon
entrance's prop). Walking into the pass fades the screen for about half a second, teleports you to the other half's
pass, and swaps the followed link; the hidden player of the game you left stays parked on its route map, and the hidden
player of the game you enter stands on *its* route map. From there you walk out into that game's town through its
clearing, as today's zone exit works (§4.8). Regions sit about 8 km apart (§4.6), so a continuous 8-km corridor is
neither buildable nor walkable; the fade is the same one dungeons and cave mouths already use.

**"Detect storyline progression so players can start the story of a hidden game if they haven't."** Each hidden game
already exposes the signals: `engine.playthrough()` says `title`, `fresh` (New Game never saved) or `saved`
(`kb_engine.lua:4568-4580`); `snapshot.ingame`; `save.loaded.fresh`; flags such as `EVENT_GOT_STARTER`; badges as bag
items. A new message, `game.status` (§4.7), carries them per link, and the server keeps them per player and region.
At a pass, the far game's record decides: a started game is **resumed** (its hidden player is warped onto the route
map); a game still at its title is **started** (the bridge drives NEW GAME, the intro plays as the screens the client
already mirrors, and when the game reaches its overworld Minecraft takes you to that region's first room — the "one
accepted teleport" of every first start). The per-family *markers* (which flag means "has the starter", "is champion")
are data in the game profile, resolved by each adapter in its own flag namespace (Gen 1 string events, Gen 3 flag ids).

## 2. How it plays

- **Setup.** You install Gen1Recomp once per game you want (`local/gen1recomp-emerald/`, portable, with the bridge mod
  linked) and import that game's ROM into it. `launch.ps1 -Games blue,emerald` starts both hidden games (ports 27151,
  27152) and Minecraft; the client's config lists the two links.
- **Building.** Your Kanto world is region `kanto` at the origin, as today. "Add a region" on the title screen (or the
  headless build) exports Emerald through its link and builds **Hoenn** at the next region slot, 8,192 blocks east:
  its towns at feet level, its interiors underground, its forests open-air, its caves in their own 3D area, its wild
  zones south and dungeons north — the same areas Kanto has, relative to Hoenn's origin.
- **The pass.** A connecting route in `routes.json` names an exit in a Kanto map and an exit in a Hoenn map. The build
  makes two forest halves, one in Kanto's zone area with a trailhead clearing in the Kanto town, one in Hoenn's zone
  area with a clearing in the Hoenn town, each ending in a tunnel mouth. The route's trainers and items belong to the
  half they stand in: their prize money and items go to that game's bag.
- **First crossing.** You walk from the Kanto town into its half (the hidden Blue player parks on Blue's route map, as in
  today's zones), through its areas, into the tunnel. Fade. Hoenn has never been started for this world, so the HUD
  says "Starting Emerald…"; the title screen's NEW GAME runs; Birch's intro, the naming screen (your Kanto name offered
  as the default) and the truck play as Minecraft screens; when Emerald reaches its overworld, Minecraft puts you in
  Brendan's room in Littleroot. From here Emerald is the followed game: you walk its story. Blue waits on its route map.
- **Later crossings.** From Littleroot you walk back into the Hoenn half and into the tunnel. Fade. Emerald parks;
  Blue's hidden player is already on Blue's route map; you come out of the Kanto half's tunnel and walk on to Viridian.
  Next time you cross into Hoenn, Emerald's hidden player is warped onto Hoenn's route map and you walk out of the
  Hoenn half's clearing into Littleroot: no teleport, no intro.
- **What you keep.** Your Pokémon, PC and Pokédex are Cobblemon's and go everywhere; in each region, the Pokémon that
  region's game cannot hold are left out of its shadow party and the HUD says how many (the visitor plan removes that
  limit later). Money, bag, badges and story stay per game, as DESIGN.md's ownership says.
- **The HUD** (F7) shows each link: game, port, state (followed / parked / down), that region's progress (not started,
  badges, champion), and where its hidden player waits.
- **Multiplayer.** Each player runs their own processes and crosses on their own; the world holds both regions for
  everyone. A player with no Emerald link sees Hoenn built but cannot cross (the pass refuses: "Emerald isn't running")
  and draws Hoenn's blocks as placeholders unless their own Emerald export exists, as a guest without a compatible game
  does today.

## 3. What changes

| Component | Today | With this plan |
|---|---|---|
| Game identity | `red`/`blue` literals in three Java sets and three schemas; gen1-bridge coerces anything else to `"red"` (`kb_session.lua:38,213`; `kb_export.lua:995-996`) | A **game catalogue** (`protocol/games.json`): id, family, engine, compat limits, default region; open `game` fields; `family` sent in `hello.ok` |
| The Gen1Recomp mod | One flat engine table (`kb_engine.lua`, 5,301 lines) used directly by observers and actors | **Core + adapters**: `lib/engine/gen1.lua` (today's table) behind an interface, loaded by engine id; a boot check names missing internals; per-game pairing files |
| Hidden game processes | One, on one port | **One per game**, each on its own port and install; the launcher starts them all |
| The client | One `BridgeClient` singleton; one `ClientWorldIndex`; one art set | **Links**: one per game; one followed, the rest parked; `ClientRegions`; art set per region |
| The world | One WorldIndex, slot map, shape index/map, datapack, generated pack per world; areas anchored on Kanto's box | **Regions**: `regions.json` (the region table), `regions/<id>/{region.json, world_index.json, shape_index.json, shape_map.bin}`; one world-level slot map with a family range per region; areas anchored on each region's own box |
| The server | One published index; services read it; no idea which game a client runs; one save pair and one slot per player | `Regions.of(server)`; services take the region from the player's position or the message; per-region pairing, save pairs, daycare, story state; a **progress** record per region |
| Routes | `free_roam` only; one end; progress in the active save | `connecting` routes with two **ends**, each registered by its own game; two halves with **passes**; a pass service |
| Protocol | 77 types | + `game.status`, `region.park`, `region.resume`, `region.start`, `region.state`, `world.regions`, `pass.cross`, `pass.abort`; optional `region` on the relayed types; open `game` + `family`; `hello.reject{reason:"game"}` |
| Builder input | `ExportBundle` → Gen 1 record types read everywhere; `scene/` used by the shape planner only | New code reads the **Scene**; a `GameFrontEnd` per family (`scene/GameFrontEnd.java` exists with `Gen1FrontEnd`); Gen 3 arrives as `Gen3FrontEnd` + a scene-shaped export |

## 4. Design

### 4.1 Vocabulary

| Term | Meaning |
|---|---|
| **Game** | A Gen1Recomp game id: `red`, `blue`, `yellow`, `gold`, `silver`, `crystal`, `firered`, `leafgreen`, `emerald` (`ruby`, `sapphire` at v0.3.85). Pattern `^[a-z][a-z0-9_]{0,15}$` |
| **Family** | The engine family a game runs on: `gen1` (engine `gen1`), `gen2` (engines `gs`, `crystal`), `gen3` (engine `game3`; layouts `frlg`, `rse`). One adapter per family |
| **Region** | An area of the one overworld built from one game's export: a record + its own index, shape documents, pairing, story state and progress. Id `^[a-z][a-z0-9_]{0,23}$` (`kanto`, `hoenn`); the game is a field of the record, so `kanto` (Blue) and a later `kanto_frlg` stay distinct. Kanto is the **legacy** region of every existing world |
| **Region slot** | A place in the **region table**: slot `n` has its origin at `x = 8192·n, z = 0` (D8); a region owns the surface box, interior spill, zone area, dungeon area and cave area the builder plans around that origin |
| **Link** | A TCP connection from the client to one hidden game process. Exactly one link is **followed** (movement, screens, NPCs, story relays go through it); the others are **parked**: connected, heartbeat on, `party.sync`/`dex.sync` still applied, their hidden player held still |
| **Pass** | A connecting route's far gate in a zone half: the cell where a crossing starts, with a tunnel-mouth prop |
| **Crossing** | Fade → park the followed link → resume or start the target link → teleport to the other half's pass → follow the target |
| **Progress record** | Per (player, region): `{state: none\|title\|fresh\|saved, ingame, started, badges, markers{…}, at}` |

### 4.2 The game catalogue

One file, `protocol/games.json`, with a schema (`protocol/games.schema.json`), copied into the jar as a resource the way
`catalog.json` becomes `MsgTypes` (`fabric-bridge/build.gradle:471-527`) and generated into `gen1-bridge/lib/kb_games.lua`
by `kb_protocol.catalog --write`. It replaces the closed sets `WorldIndex.GAMES` (`world/WorldIndex.java:61`),
`ExportBundle.GAMES` (`builder/export/ExportBundle.java:40`), `Games.FAMILY` (`world/Games.java:13`), `kb_session`'s
`GAMES` (`gen1-bridge/lib/kb_session.lua:38`) and the three schema enums (`hello.ok`, `export.index`,
`world_index`).

```json
{
  "version": 1,
  "games": {
    "red":      {"family": "gen1", "engine": "gen1",  "generation": 1, "label": "Red",      "region": "kanto",
                 "compat": {"maxDex": 151, "maxMove": 165, "nick": 10}, "artSet": "red",  "adapter": true},
    "blue":     {"family": "gen1", "engine": "gen1",  "generation": 1, "label": "Blue",     "region": "kanto",
                 "compat": {"maxDex": 151, "maxMove": 165, "nick": 10}, "artSet": "blue", "adapter": true},
    "yellow":   {"family": "gen1", "engine": "gen1",  "generation": 1, "label": "Yellow",   "region": "kanto",
                 "compat": {"maxDex": 151, "maxMove": 165, "nick": 10}, "artSet": "yellow", "adapter": false},
    "gold":     {"family": "gen2", "engine": "gs",    "generation": 2, "label": "Gold",     "region": "johto",
                 "compat": {"maxDex": 251, "maxMove": 251, "nick": 10}, "artSet": "gold", "adapter": false},
    "emerald":  {"family": "gen3", "engine": "game3", "generation": 3, "label": "Emerald",  "region": "hoenn",
                 "layout": "rse", "compat": {"maxDex": 386, "maxMove": 354, "nick": 7}, "artSet": "emerald",
                 "adapter": false}
  }
}
```

- `adapter` says whether the bridge mod has an engine adapter for the game today; the Java side uses it only for
  messages ("Emerald isn't supported by this bridge yet"). The mod's manifest `games` lists the games with
  `adapter: true` (Gen1Recomp's `games` gate refuses to load a mod for a game it doesn't list: Appendix A.4).
- `compat` is the region's default compatibility profile (§4.9). The numbers for Gen 2/3 are national-dex and move
  counts of those games and are checked against the engine's registries when the adapter is written (an adapter
  reports its real limits in `hello.ok.compat`, which wins).
- `region` is the default region id offered by "Add a region".
- Rule from now on (the report's B7.2): **no new code takes `"red"`/`"blue"`, `PALLET_TOWN`, `kanto`, `sgb`, 151, 165,
  16 or 4×4 as literals**; they come from the catalogue, the scene or the record.

### 4.3 The bridge mod: core and engine adapters

**Facts.** `kb_engine.lua` is "the only gen1-bridge file that touches Gen1Recomp engine internals" (`kb_engine.lua:1-10`);
it requires 32 engine modules lazily through `try()` (`:181-185`), reads the Game object directly, and exposes about
170 functions in milestone sections (Appendix B). Observers and actors receive it as `opts.engine` and call it
directly; nine modules never call it at all (`kb_player`, `kb_snapshot`, `kb_npcs`, `kb_warp`, `kb_mirror`,
`kb_cells`, `kb_images`, `kb_swapsites`, `kb_climb`). Own files are loaded with `mod:read` + `load`, never `require`,
so one mod can carry several adapters and load one (`main.lua:17-26`).

**Design.**

- `lib/kb_engine.lua` becomes the **loader**: `Engine.new(opts)` reads `GameVersion.engine()` and loads
  `lib/engine/<engine>.lua` (`gen1.lua` is today's file, moved; `game3.lua` and `gs.lua` later). It returns the adapter
  table, or a stub whose `hello` answers `hello.reject{reason:"game"}` (new reason) and logs `engine.no_adapter`
  once, so a client connecting to a process running an unsupported game is told why.
- **The interface** is Appendix B's groups. Each group is tagged with the capability it serves: `link` (session,
  observe, move, warp, UI, NPCs, story, mirror, save), `export`, `party`, `battle`, `items`, `routes`, `region` (new).
  The core treats a missing optional group as "not supported" (for example an adapter without `export` makes the
  builder role answer `error{code:"export_failed"}`), so an adapter can grow group by group.
- **`Adapter.REQUIRES`**: one table per adapter listing the engine modules, functions and Game fields it depends on.
  Checked once on `game.ready`: one `log` warning, `engine.missing`, naming what is absent, and an assertion in an e2e
  smoke test. This is ROADMAP Stage 4's item 1 ("make engine upgrades loud"), done as part of the split.
- **CLAUDE.md** changes one sentence: "only `lib/engine/*.lua` touch engine internals" (needs D1).
- **Per-game pairing files.** `session/pairings.json` is one file per install (`kb_pairing.lua:21-31`) keyed by game
  version. With one process per game, each process writes `session/pairings.<game>.json` and reads the legacy file
  once for its own game's entry. (Two processes of one install still share `options.lua`, which the engine rewrites
  whole: §4.11 keeps one install per game.)
- **New act module `act/kb_region.lua`** (the `region` group):
  - `region.park{region}`: the hidden player must be idle; pushes a **hold** (the pattern of the Bill's PC hold,
    `kb_engine.lua:3362-3377`: input and steps refused, scripts and NPC movement frozen) and writes
    `mod.save "region" = {away: {map, x, y}}`. Music fades (`Music.fadeOut`). Answer `act.result{ok, kind:"park"}`.
    Reasons: `not_ingame`, `not_idle`, `busy`.
  - `region.resume{region, to{map,x,y,facing}}`: pops the hold; if the hidden player is not on `to`, queues the engine's
    own `warp` (`Routes.warpTo`'s pattern, `kb_routes.lua:487-505`) — **only** onto one of this mod's route maps
    (`KB_ROUTE_*`) or the saved `away` cell (story safety, §4.13). Music restores. Answer `kind:"resume"`. Reasons:
    `not_ingame`, `not_idle`, `busy`, `bad_target`.
  - `region.start{region}`: from the title screen: CONTINUE when the active slot has a save (`slotHasSave`,
    `kb_engine.lua:4516`), else NEW GAME, driven through the title menu the way the switch already rebuilds it
    (`titleMenuOpen`, `toTitle`, `kb_engine.lua:4568-4634`); the adapter supplies `startFromTitle(mode)`. Answer
    `kind:"start", detail:"new"|"continue"`. Reasons: `not_title`, `busy`, `failed`. The intro then runs as ordinary UI
    (`ui.open` text boxes, the naming screen, the mirror for graphics), nothing new.
  - On a link drop the hold is popped so the game can be played alone; the `away` marker stays in the save, and
    `snapshot.away` tells a reconnecting client.
- **New observer `observe/kb_progress.lua`** → `game.status` (§4.7).
- **Routes v2** in `lib/kb_routes.lua` (§4.8): `kind: connecting`, two **ends**, one registered per running game.

### 4.4 The protocol additions

All additive. Protocol number stays 1 (D10). Schemas first, then `catalog.json`, `kb_catalog.lua`, both stand-ins.

| Type | Dir | Data | Notes |
|---|---|---|---|
| `hello.ok` | G→M | + `family`, + `compat?{maxDex, maxMove, nick}`; `game` becomes an open game id | `family` from `GameVersion.engine()` through the catalogue |
| `hello.reject` | G→M | + reason `game` | the running game has no adapter |
| `export.index` | G→M | `game` open | |
| `game.status` | G→M, C→S | `game, region?, playthrough:"title"\|"fresh"\|"saved", ingame, started, badges, markers{<name>:bool}, slot?` | after `snapshot`, after `session.bound`, on `save.created`/`save.loaded`, when a marker or the badge count changes (checked with the story flags, every 10 steps) |
| `region.park` | M→G | `region` | → `act.result{kind:"park"}` |
| `region.resume` | M→G | `region, to{map,x,y,facing}` | → `act.result{kind:"resume"}` |
| `region.start` | M→G | `region` | → `act.result{kind:"start", detail}` |
| `snapshot` | G→M | + `away?{map,x,y}` | the hold is up |
| `region.state` | C→S, S→C | `followed?, parked[]` | the client's links as it sees them; once S→C on join with the stored presence |
| `world.regions` | S→C | `index, count, hash, part` | parts of `{"version":1,"world":"<id>","table":{…},"regions":[<record>…]}` (§4.6) |
| `pass.cross` | C→S | `route, dir:"to"\|"from"` | the player stands on that pass |
| `pass.abort` | C→S | `route, reason` | the client could not park/resume/start |
| `error` | S→C | codes `pass.no_region`, `pass.no_link`, `pass.not_built`, `pass.busy`, `pass.timeout` | |
| optional `region` | relayed types | `world.index`, `world.shapes`, `texture.status`, `story.state`, `session.bind/bound/waiting`, `save.begin/loaded/mismatch`, `warp.request`, `route.*`, `party.*`, `daycare.*`, `bag.*`, `battle.*`, `pc.*`, `game.status` | absent = the legacy region. The client tags every message it relays with its link's region; S→C messages with `region` are routed to that region's link |

`defs.schema.json` gains `gameId`, `regionId`, `family`. The Pokémon limits stay as they are in this plan (`dex` ≤ 151,
`gen1Move` ≤ 165 in the Gen 1-only messages); the Gen 3 adapter's spec widens them where it needs to (`party.sync`
already carries 1..2000).

### 4.5 Links (the client)

**Facts.** `BridgeRuntime` is a static singleton with exactly one `BridgeClient` whose config is final
(`client/bridge/BridgeRuntime.java:47,72,89`; `BridgeClient.java:58`); the port comes from `kantobridge-client.json`
or `-Dkantobridge.port` (`ClientConfig.java:38-40,52-89`). Every runtime calls `bridge.client()`. "Kanto world" means
`ClientWorldIndex.current() != null` (18 call sites: `ActRuntime` ×4, `PuppetRuntime` ×3, `TextureSyncRuntime` ×3,
`ClientStoryView`, `StoryCellRenderer`, `SurfSprite`, `BlockadeFade`, `PuppetRenderer`, `Versions`, `SessionRuntime`,
`MoveRuntime`). The pause overlay engages whenever the one link is down for 1.5 s.

**Design.**

- **Config v2**: `{"links": [{"game": "blue", "port": 27151}, {"game": "emerald", "port": 27152}]}`;
  `-Dkantobridge.links=blue:27151,emerald:27152`. The legacy `port` key is one link whose game is learned from
  `hello.ok`.
- **`Links`** (replaces `BridgeRuntime`'s single client): `Map<game, BridgeLink>`; `followed()`, `parked()`,
  `forRegion(id)` (region → game through `world.regions`), `follow(game)`. A `BridgeLink` owns what is per hidden game:
  `BridgeClient`, `Dispatcher`, `ClientBridgeState`, `SessionGate`, `GameRelay` (party/dex/daycare/battle relays),
  `SessionBinder` (binds `{world, multiplayer, region}`), `ClientStoryView` (story for its region), `TextureSync` (its
  region's art), `BundleWriter`/export.
- **Per-body runtimes** — the player has one body: `MoveRuntime`/`MovementSync`, `ActRuntime`, the UI screens, the
  mirror, `PuppetRuntime`, `FxRuntime`, `ZoneRuntime` — subscribe to `followed()`'s dispatcher. `Links.follow(game)`
  closes open screens, clears puppets and the input lock, and relinks `MovementSync` from the new hidden spot
  (`linkFrom`, at most one ordinary step; a teleport only for `start`).
- **`hello.ok.game` must equal the link's configured game**; otherwise the link is marked `wrong_game` (HUD: "port 27152
  runs Blue, expected Emerald") and never followed.
- **The pause overlay** engages only for the followed link. A parked link down is an F7 line and a refusal at the pass.
- **Relay tagging**: every message a link relays to the server carries `region` = the link's bound region; the server's
  `region`-tagged messages go to `forRegion`.
- **Exports and builds per link**: "Create Kanto world" keeps its name and uses the followed link; "Add a region" (title
  screen) lists the connected links, offers the catalogue's default region id and the next free slot, exports that
  link's bundle to `<gameDir>/kantobridge/export/<game>/` (already per game) and builds. The headless build takes
  `-Dkantobridge.region=<id> -Dkantobridge.slot=<n>` beside `-Dkantobridge.build=<bundle>`.

### 4.6 Regions (the world, the server, the builder)

**Facts.** Everything sits in `<world>/kantobridge/` with fixed names (`builder/BuildTask.java:64-71`); the WorldIndex
root fields are `version, game, exportHash, scale, maps, slotMapHash, storyCells, habitats, zones, dungeons, shafts`
(`world/WorldIndex.java:32-33`), `game` ∈ {red, blue}; map ids and story-cell keys (`MAP:x:y`) are bare. The server
publishes one index (`server/WorldIndexSync.java:46-51`) and every service reads it. Off-Kanto areas are anchored on
the plan's surface box: wild zones 3,000 blocks south in 640-block slots, 8 to a row (`builder/zone/ZoneRegions.java:13-45`),
dungeon slots 3,000 north (`dungeon/DungeonSlots.java:15-46`), the cave area a further 3,000 north
(`builder/plan/CavePlanner.java:37-49,176-178`), interiors under their doors with spill (`builder/plan/InteriorPacker.java:21-30`);
"west and east are kept for other games' worlds" is a comment (`ZoneRegions.java:7-10`). Kanto's extents: about 704×744
blocks of surface, zone rows 5,120 east of its west edge, the cave area reaching 6,160+ blocks north.

**The region table** `<world>/kantobridge/regions.json`:

```json
{"version": 1, "pitch": 8192,
 "regions": [{"id": "kanto", "game": "blue", "slot": 0}, {"id": "hoenn", "game": "emerald", "slot": 1}]}
```

Slot `n` builds at origin `(8192·n, 64, 0)` (D8). 8,192 blocks leaves about 3,000 clear between Kanto's eastmost zone row
and the next region's west edge; the north–south extent (caves to zones, about 10,000 blocks) is the same for every
slot, so one row of slots along X holds as many regions as wanted inside the world border. The builder refuses a build
whose `bounds` overlap another record's (`build.region_overlap`).

**The region record** `<world>/kantobridge/regions/<id>/region.json` (canonical JSON; the report's A8.1/4.5 record
without footprint, apron or gates):

```json
{
  "version": 1, "id": "hoenn", "game": "emerald", "family": "gen3", "exportHash": "<64 hex>",
  "slot": 1, "origin": {"x": 8192, "y": 64, "z": 0},
  "bounds": {"minX": 7980, "minZ": -9400, "maxX": 13400, "maxZ": 4200, "minY": -256, "maxY": 319},
  "areas": {"surface": {"x":0,"z":0,"w":0,"d":0}, "zones": {}, "dungeons": {}, "caves": {}},
  "families": [16, 31],
  "files": {"index": "regions/hoenn/world_index.json", "shapeIndex": "regions/hoenn/shape_index.json",
            "shapeMap": "regions/hoenn/shape_map.bin"},
  "hashes": {"index": "<64 hex>", "shapeIndex": "<64 hex>", "shapeMap": "<64 hex>"},
  "generator": {"shapes": "kb-shapes/11"},
  "rules": {"profile": "gen3", "movement": {"walk": 0.1737, "bike": 0.3474},
            "compat": {"maxDex": 386, "maxMove": 354, "onIncompatible": "leave_out"}},
  "namespaces": {"artSet": "emerald", "pools": "kantobridge:hoenn"}
}
```

(The numbers are placeholders.) `bounds` is the union of the areas and the interior spill; `areas` are the boxes the
client and server use for "which region am I in" and for zone/dungeon logic; `families` is the slot-map family range
the region's tiles use (below). A world with `world_index.json` at the root and no `regions/` folder is a **legacy
world**: one implicit region `kanto` at slot 0 with families `[0, 15]`; nothing is rewritten until it is rebuilt.

**The slot map and capacity.** Block states are global, so the slot map stays **one per world** (`slot_map.json`,
schema v2): each region gets a family range, and slots are assigned within the range from that region's whole export,
so a rebuild of one region never renumbers another's blocks. Kanto uses 3,926 of 4,096 full slots, so a second region
needs more families: `FAMILIES_PER_SHAPE` 16 → 32 in `block/KbBlocks.java:23` and `world/SlotMap.java:34` (every tile,
half and shape family doubles: about 12,000 more block states, roughly 10–15 MB more per side at the measured 1.23 KB a
state; D9 asks to measure it on the pod). Keys carry the art set (the report's "namespace now" item 2). Legacy slot maps
(v1) read as region `kanto`, families 0..15.

**Shape documents per region.** `shape_index.json` and `shape_map.bin` are per region (the map's header already binds
it to one index hash, `world/ShapeMap.java:23-38`); `world.shapes` carries `region`; the 524,288-character wire budget is
per document, so Kanto's 97 % no longer caps Hoenn.

**Pools and biomes.** Habitat pool ids become `kantobridge:<region>_<map>` for non-legacy regions (today
`kantobridge:<map>`, `builder/plan/HabitatPlanner.java:47,57-64`: two games both have a `ROUTE_1`). The biome set
(`kanto_outdoor`, `kanto_interior`, `kanto_cave`, `cave_3d`, `wild_forest`) is shared by every region at first; per-region
moods are a later tweak.

**The server.** `Regions.of(server)`: records, indices and shape documents; `at(x, z)` (bounds then areas), `byId`,
`legacy()`. `WorldIndexSync.Published` becomes one per region; on join: `world.regions`, then each region's
`world.index{region}`, the world's `world.slots`, each region's `world.shapes{region}`. Services that read the single
index (`WarpService`, `ZoneService`, `DungeonService`, `HabitatService`, `FlashService`, `EncounterService`, `BattleService`,
`BattleMusic`, `TextureStatusService`) take the region from the player's position or the message's `region`; `WarpService`
resolves `warp.request.to.map` against the followed region's index. Per player: `SessionRecord` format 2
(`players{uuid:{slot, regions:{<id>:{slot}}}}`, format 1 read); `kantobridge:save_pairs` (region → `{pair, at, ops}`,
the old `kantobridge:save_pair` read once as the legacy region's); daycare at `regions/<id>/daycare/<uuid>.json`;
`StoryState` for the followed region only, cleared on a swap; `RegionPresence{followed, parked[]}` (persistent, synced
to its owner); `kantobridge:progress` (§4.7). Server rules stay world-wide: the flat world has no "outside", so the
report's `RegionRules` are not needed; per-game movement speeds come from `rules.movement` when a game needs them.

**The client.** `ClientRegions` (`RegionSet{regions, epoch}`, `at(x,y,z)`, `byId`, `followed()`), published when a
region's documents are complete; `ClientWorldIndex.current()` becomes a shim returning the followed region's index so
the 18 call sites move one at a time; `ClientShapes` builds geometry per region from the export of that region's game
(`<gameDir>/kantobridge/export/<game>`); `TextureSync`/`texture.status` per region: a region whose game the client has
no export for draws placeholders and reports `no_art`, exactly as a guest without a compatible game does today.

**The builder.** A build is `(bundle, regionId, slot)`. `WorldPlanner` plans from the slot's origin; zones, dungeons and
the cave area already anchor on the plan's surface box, so they move with it (a test proves it). `BuildTask` writes under
`regions/<id>/`, updates `regions.json`, clears only its own region's previous volumes on a rebuild, and reports per
region. The build report gains `region`, `slot`, `bounds`, `families`, `shape_map_chars` per region.

### 4.7 Progress and the story start

**Facts.** `engine.playthrough()` → `title` | `fresh` | `saved` (`kb_engine.lua:4568-4580`); `snapshot.ingame`
(`kb_snapshot.lua:85-99`); `save.loaded.fresh` (`kb_save.lua:63-68`); badges are bag items read by `engine.badgeItems()`
(`kb_engine.lua:335-347`) and only in the snapshot; the starter gift is recognised through `EVENT_GOT_STARTER`
(`kb_engine.lua:2160-2162`); `save.created` fires on New Game (and at boot for the title skeleton, `kb_save.lua:231-233`).
The server never learns which game a client runs (`relay.hello` carries `{protocol, mod}` only).

**Design.**

- **`game.status`** is built by `observe/kb_progress.lua` from `playthrough`, `inGame`, the badge count and the
  adapter's `progressMarkers()`: a table `{starter = bool, champion = bool, …}` whose names are fixed by the game
  profile (`markers` in `games.json`: `{"starter": "EVENT_GOT_STARTER", "champion": "<the Hall of Fame flag>"}` for Gen 1;
  Gen 3 flag names for Emerald) and whose resolution is the adapter's: Gen 1 reads `save.flags[name]`, Gen 3 asks the
  `game3` flag store (Appendix A.4: `mod.world:getFlag` exists on all three engines but with different namespaces).
  `started` = `playthrough ~= "title"`.
- The client forwards `game.status` with `region`; the server stores it in `kantobridge:progress` (region → record,
  persistent) and shows it on F7. The pass uses the stored record for a region whose link is connected now; a region
  whose link is absent shows `none`.
- **At a pass** (§4.8) the target region's record decides: `fresh`/`saved` → `region.resume` onto the route map;
  `title` → `region.start` (CONTINUE if the slot has a save, else NEW GAME); no link → refused.
- **The first start** (D5): NEW GAME runs as the game wrote it. The intro's text, choices and naming screens are the
  client's screens (M4/M5); graphics (Birch's slide show, the truck) are the mirror. The naming screen's default name is
  the player's name from the followed Kanto save (`snapshot` carries the player; D6). When the game first reports
  `ingame:true`, the client relinks `MovementSync` from the hidden spot with a teleport allowed (the "one accepted
  teleport" of a first join), which puts the player in that game's first room. Nothing skips the intro: skipping would
  break the opening scripts (the rival, the starter), and the hidden player would land where no script expects it.
- **Compatibility at a start:** a new game's story gives a starter; the party already holds the player's Pokémon.
  Cobblemon's party is the truth; the region's compat profile decides what the shadow party shows (§4.9). Level caps or
  a region-only party are later decisions (§8).

### 4.8 Connecting routes and the pass

**Facts.** The route list (`gen1-bridge/data/routes.json`) accepts `kind: free_roam` only (`kb_routes.lua:84` refuses
`connecting` with `kind_later`); `from.map` is a bare symbol with `from.games` a list; no `to` is parsed anywhere. At
boot the mod registers, per zone of the running game, the route map `KB_ROUTE_<ID>` (2×2 cells, A at (0,0), arrival x1
at (1,0)), two step scripts and the town's block patch, all through `mod.content` before the registries freeze
(`kb_routes.lua:357-481`). The warps are queued scripts of the engine's `warp` command (`kb_routes.lua:487-505`). The
builder's generator supports a connecting layout `[A][x1]…[x2][B]` with a far gate (`routes/gen/RouteLayout.java:16,27-32`)
but `ZonePlanner` always builds `FREE_ROAM` (`builder/zone/ZonePlanner.java:57-60`). Dungeon entrances already fade and
teleport players server-side (`route.fade`, `dungeon/DungeonService.java:173-231`). While a player roams a zone the
hidden player waits on the route map and `MovementSync` is "away" (`client/move/MovementSync.java:767-779`).

**Routes v2** (`routes.json` `version: 2`; `protocol/world/route_list.schema.json`, `protocol/export/routes.schema.json`):

```json
{
  "id": "eastern_pass", "name": "EASTERN PASS", "kind": "connecting", "theme": "forest",
  "from": {"region": "kanto", "games": ["red", "blue"], "map": "ROUTE_?", "x": 0, "y": 0, "facing": "right", "depth": 2,
           "classes": ["OPP_HIKER", "OPP_YOUNGSTER"], "music": "Music_Routes1"},
  "to":   {"region": "hoenn", "games": ["emerald"], "map": "<engine map id>", "x": 0, "y": 0, "facing": "left", "depth": 2,
           "classes": ["<gen 3 classes>"], "music": "<gen 3 song>"},
  "areas": {"main": [2, 3], "side": 0.5}, "levels": {"from": "neighbours", "perArea": 1, "maxRise": 3},
  "trainers": {"perArea": [1, 2], "rematch": "each_visit"}, "dungeon": {"chance": 0}
}
```

- Both ends have the same shape (an *end*: region, games, exit cell, depth, trainer classes, music). Each hidden game
  registers **only the end whose `games` include it**: the clearing patch in its town and a route map
  `KB_ROUTE_<ID>` of the same `[A][x1]` form as today (A warps back to the clearing; x1 is the arrival). So each game
  holds one half of the plan's `[A][x1][x2][B]`; the far cells live in the other process. The export's `routes`
  resource carries both ends so the builder knows the route is connecting and which region each half belongs to.
- **The halves.** The builder makes a zone per end with `RouteSpec.connecting(theme)`: the trailhead gate at the town's
  clearing, the far gate = the **pass**, a tunnel-mouth prop (a `kantobridge:prop` variant beside `prop_dungeon_entrance_*`)
  on the far gate's cells. Each half sits in its region's zone area; levels interpolate across the two halves by the
  layout's `progress` (`RouteLayout.java:60`). The WorldIndex zone entry gains `pass{x, y, route, toRegion}`.
- **The pass service** (server, `server/PassService`, modelled on the dungeon gathering):
  1. The client sees the pass cell under its feet and sends `pass.cross{route, dir}`.
  2. The server checks: the player stands on that pass; the target region exists and is built (`pass.no_region`,
     `pass.not_built`); the player's `region.state` lists a link for the target's game (`pass.no_link`); no crossing in
     progress (`pass.busy`). It answers `route.fade{ticks:10}` and starts a 15 s timer.
  3. The client (`client/zone/PassRuntime`): on the fade, `region.park` to the followed link (the hidden player is on
     the route map already; `not_in_zone` or any refusal → `pass.abort`), then on the target link: `fresh`/`saved` →
     `region.resume{to: that game's route map x1}`; `title` → `region.start`, then wait for `game.status{ingame:true}`
     (up to 10 s; the intro's screens are shown meanwhile and the fade lifts for them). Then `Links.follow(target)` and
     `region.state{followed: target, parked: […]}`.
  4. On `region.state` the server teleports the player to the other half's pass, `out` side (as the dungeon exit does)
     — or, after a `start`, leaves the teleport to the client's relink (the first room). The client fades in when the
     chunk is drawn or 15 s after it was due. On `pass.abort` or the timer: `error{code:"pass.timeout"}`, the fade lifts
     where the player stands, the followed link is unchanged.
- **Items and trainers** of a half belong to its region: the server already keys them by zone; the client forwards
  `route.give` / `route.battle` to the link of the zone's region, and prize money and items land in that game's bag
  (WILD_ROUTES_PLAN §4.10: "items always go into the active game's bag"). `RouteProgress` is keyed by region and zone.
- **Leaving a half towards its town** is today's exit: the player walks onto the route map's placement at the trailhead,
  A's step script warps the hidden player back to the clearing and the client follows (warp kind `script`).

### 4.9 Pokémon, Pokédex and bag across regions

- **The party and PC are Cobblemon's**; every link receives `party.sync` and `dex.sync` (parked links too), so each
  hidden game's shadow party is current when it is resumed.
- **Per-region compatibility** (`rules.compat`, from the catalogue, overridable in the record): `kb_party.apply` takes
  the limits from the running game's registry (`speciesOfDex` is nil for a dex the game lacks) instead of the literals
  151/165 (`gen1-bridge/act/kb_party.lua:343-356`); the count of members left out goes to the client as a `log` code and
  one HUD line. The Java caps `GenOneIds.MAX_DEX/MAX_MOVE` (`pokemon/GenOneIds.java:19-20`) become per-link values from
  `hello.ok.compat`; the global evolution veto (`pokemon/PartyService.java:147,505-513`) applies the compat profile of
  the player's **followed** region.
- **The visitor plan** (the user's note) is the companion that makes a foreign team *playable* inside a region (a
  stand-in species and label moves registered at boot by that region's adapter). `onIncompatible: "leave_out"` is
  today's behaviour; `"visitor"` is the value that plan adds. Its item fixes (2.4.1–2.4.4 of the note) are independent
  of this plan.
- **`dex.sync`** per link sends the species that game knows (`DexService` today sends `GenOneIds.SPECIES` only,
  `pokemon/DexService.java:104-111`): the per-link species list comes from the link's compat profile.
- **Bag, money, badges** stay per game (DESIGN.md §6 ownership). A trainer-level wallet across games is D7 (no).

### 4.10 The builder per game

- **`GameFrontEnd`** (`scene/GameFrontEnd.java:11-20`, `FrontEnds.forGame` → `Gen1FrontEnd`) is the per-family
  adapter on the Java side; the `Scene` (`scene/Scene.java`, `SceneMap` with `CellKind`/`CellTag`/`Env`) is the neutral
  model. Today only the shape planner reads the scene; `WorldPlanner`, `Layout`, `MapModel`, `CavePlanner`,
  `HabitatPlanner`, `StoryCellPlanner`, `ArtCatalog`, the pack writers and `ZoneContent` read Gen 1 export types. Rule:
  new code reads the scene; existing classes are converted only when touched (the report's B7.2 table lists each).
- **Gen 3 (Emerald) specifics** for MG6's spec, from the engine facts (Appendix A.5): cells are 16 px (so `cellBlocks`
  2 holds); art is a 16×16 metatile per cell in up to three planes (under, middle, over) with 16-colour palettes; the
  front end **splits each metatile into 8-px quarters** so the existing 8-px pipeline, slot keys and shape planner work
  unchanged (D13); the `over` plane becomes a cutout layer above sprites; per-cell `elev` 0..15 maps onto H1's `levels`
  — the first Hoenn build is **flat with invisible edge walls where an elevation mismatch blocks a step** (H2's pair
  edges), heights later, matching the user's "caves first, everything later"; connections are in cells, not blocks;
  map ids are engine ids (`FR_*`-style for FRLG; Emerald's are the catalog's), kept as the export's map ids.
- **Capacity per region**: slot families (§4.6), shape documents per region, the block-quads and section budgets are
  per section and unaffected.

### 4.11 Launcher and installs

- **One portable install per game**: `local/gen1recomp/` stays Blue (compatibility); `local/gen1recomp-<game>/` for
  the others, each with `portable.txt`, its own `options.lua`, saves, ROM import and a `mods/gen1-bridge` junction.
  Reason: `options.lua` holds every game's slot registry and mod state and is rewritten whole with no writer lock, so
  two processes of one install can clobber each other (Appendix A.2; the README already forbids two players on one
  install). `POKEPORT_IDENTITY=<name>` gives a process a whole separate data home and is an alternative to spike
  (D3), but each identity still needs its own import by the user.
- **`tools/launch.ps1 -Games blue,emerald`**: `launch.json` gains `games: {blue: {dir, port}, emerald: {dir, port}}`;
  ports default to 27151 + i (player 2's processes at +10); it starts each `gen1recomp.exe --game=<id>`, waits for each
  port (the existing `Wait-Listener`), and passes `-Dkantobridge.links=…` to the dev client. `-Watch` restarts any that
  closes.
- The Linux e2e runner (`tools/e2e/run_gen1recomp.py`) already isolates a data home per process through
  `XDG_DATA_HOME`; a second game needs a second data home with its own import (the user's).

### 4.12 Multiplayer

- Regions and the table are the host's world data and reach every client through the relay (layout data, like the
  index). Each player's links are their own; `region.state` is per player; the pass refuses players without the target
  game's link.
- Pairing is per link and per region: gen1-bridge already keys pairings by game version, so Kanto-in-Blue and
  Hoenn-in-Emerald never share a slot; the `region` key only matters for two regions of one game (Red-Kanto and Blue-Kanto
  in one world).
- A player standing in a region whose game they don't run: allowed (it is just terrain and other players' Pokémon);
  their links are all parked there; no story objects of that region are drawn for them.

### 4.13 Story safety rules

1. Every decision that moves a hidden player is taken by its own gen1-bridge from that game's save; the server and
   the other games never place a hidden player.
2. A hidden player is warped by the bridge **only onto one of our route maps or the saved `away` cell**. Towns are
   entered through the route map's own step script, the way the engine warps players today.
3. A new story is never skipped into: a region at its title is started with NEW GAME (or CONTINUE) and followed from
   its first room.
4. Parked games are frozen: input, steps, scripts and NPC movement are held; `party.sync`/`dex.sync` still apply.
5. A link drop pops the hold (the game can be played alone) and leaves the marker in the save.

### 4.14 Legacy worlds

A world without `regions.json` is region `kanto`, slot 0, families 0..15, its index at the root, all messages without
`region`, one link. Every golden file, the e2e scenarios of M9/M10 and the real Kanto build stay byte-identical until a
rebuild writes the new layout. Old clients (one link, no `region` fields) keep working against a one-region world.

## 5. Phases

Sizes as in PLAN.md §6: **S** about M1, **M** about M6/M7, **L** about M8, **XL** larger. Tags: [L] gen1-bridge Lua,
[C] client, [S] server/common, [B] builder, [T] tools and tests, [D] docs and protocol. ✔ marks a full-suite checkpoint.

### MG0: Decisions and spikes (no code in `main`)

- The user answers §9 (defaults proceed where marked).
- **S1 (user's PC):** two Gen1Recomp processes of different games from two installs (`--game=blue`, `--game=red` with
  the e2e install as the second): RAM per process, both windows minimised, both bridge ports listening. Pass: both run
  at full speed with Minecraft; RAM noted in PROGRESS.
- **S2 (user's PC, Emerald import in a test install):** a 50-line probe mod (`games: ["emerald"]`, `engine_internals`)
  that logs **counts and names only**: `GameVersion.engine()`, which of `mod.datasets:open("emerald")`'s registries
  exist, whether `mod.world:warpTo`, `queueScript`, `current()`, `getFlag` work on the `game3` WorldAPI, whether
  `maps:patch` on an Emerald map and a `map_scripts` registration are accepted or dropped, which hooks fire
  (`world.stepped`, `map.entered`, `player.warped`, `warp.destination`, `input.step`). Pass: a written table of what the
  adapter can and cannot use. It settles the exit mechanism for Gen 3 (`map_scripts` or the `warp.destination` hook).
- **S3 (same install):** read a flag and the badge count through the `game3` flag store; find Emerald's "has starter" and
  "champion" flags by name. Pass: the two names, as text, nothing else.
- **S4 (test install, Gen 1):** start NEW GAME and CONTINUE from the title under a connected test session, driven from
  the mod (title-menu taps or `Game:startNewGame`), 10 times each. Pass: the game reaches the overworld and `save.loaded`
  arrives each time; no stray input reaches the intro.
- Output: PROGRESS "Several games: spikes"; `docs/specs/GEN3_BRIDGE_PLAN.md` is written only at MG6 from S2–S3.

### MG1: The game catalogue and an open protocol (S–M)

- [D] `protocol/games.json` + schema; `defs` `gameId`, `regionId`, `family`; `hello.ok` (`game` open, `family`,
  `compat?`), `hello.reject{reason:"game"}`, `export.index.game` open, `world_index.game` open + `family?`; PROTOCOL.md.
  `kb_protocol.catalog --write` also writes `gen1-bridge/lib/kb_games.lua`; Gradle copies the catalogue into the jar.
- [L] `kb_session`: send the real game id and family; no coercion; `hello.reject{reason:"game"}` for a game outside the
  manifest's list (defensive). `kb_export`: no coercion; `layout` id from the catalogue's default region.
- [L] pairing file per game (`session/pairings.<game>.json`, legacy read once).
- [S] `Games` reads the catalogue (`known()`, `family()`, `compat()`, `defaultRegion()`); `WorldIndex` and `ExportBundle`
  use it; fixed resource ids come from the family (`sgb`, `kanto` stay for `gen1`).
- [C] `Versions.gameNotice` and `TextureSync` use `Games.compatible` unchanged in behaviour.
- [T] `fake_gen1 --game <id>` for any catalogued id (the synthetic bundle is still Gen 1-shaped; `family` from the
  catalogue); schema tests for the open fields; lupa: `kb_session` passes `yellow`/`emerald` through; JUnit `GamesTest`.
- **Check:** `tools/check` green; the golden synthetic index is byte-identical (`game` stays `red`). **Commit `MG1`.**

### MG2: Core and adapters in the bridge mod; the region acts on Gen 1 (M) ✔

- [L] `lib/engine/gen1.lua` (today's `kb_engine.lua` moved whole); `lib/kb_engine.lua` the loader; `Adapter.REQUIRES`
  and the `engine.missing` boot check; the interface documented in `gen1-bridge/README.md` (Appendix B). CLAUDE.md's
  one sentence (D1).
- [L] `act/kb_region.lua`: `region.park`, `region.resume`, `region.start`; the hold; `mod.save "region"`;
  `snapshot.away`; pop on link drop. The adapter gains `holdAway/releaseAway`, `startFromTitle(mode)`,
  `progressMarkers()`.
- [L] `observe/kb_progress.lua` → `game.status`.
- [L] `kb_routes` v2: `kind: connecting`, `from`/`to` ends, register the end(s) of the running game; the `routes` export
  resource v2.
- [D] schemas: `game.status`, `region.park/resume/start`, `act.result` kinds `park|resume|start` and the reasons,
  `snapshot.away`, route list v2, export `routes` v2; PROTOCOL.md "Regions (MG2)".
- [T] lupa: the loader picks `gen1`; `REQUIRES` reports a removed module; park/resume/start on the fake engine incl.
  every refusal; `game.status` on New Game, CONTINUE, a badge, the starter; routes v2 registers one end per game and
  refuses an end for another game silently. `fake_gen1`: answers the three acts, emits `game.status`, takes
  `--state title|fresh|saved`.
- **Check:** full suite; e2e on the test install: park/resume round trip (the hidden player's cell unchanged), `start`
  from the title (S4's scenario), `game.status` after CONTINUE (`saved`) and after NEW GAME (`fresh`). **Commit `MG2`.**

### MG3: Regions as data (L) ✔

- [D] `protocol/world/region.schema.json`, `regions.schema.json`, `slot_map.schema.json` v2, `world.regions`,
  optional `region` on the relayed types (§4.4); PROTOCOL.md "Regions" (files, messages, legacy rule).
- [B] `RegionTable` (`regions.json`), `BuildOptions.region/slot`, origin from the slot, the overlap check; `BuildTask`
  writes `regions/<id>/`, clears only its region, reports per region; `SlotMap` v2 with family ranges and art-set keys;
  `KbBlocks`/`SlotMap` `FAMILIES_PER_SHAPE` 32; shape documents per region; pool ids per region; the headless build's
  `-Dkantobridge.region/-Dkantobridge.slot`; `tools/e2e/build_world.py --region --slot`.
- [S] `Regions`, per-region `WorldIndexSync`, services keyed by region, `SessionRecord` v2, `kantobridge:save_pairs`,
  daycare path, `StoryState` per followed region, `RegionPresence`, `kantobridge:progress` (fed by `game.status`).
- [C] `ClientRegions`; the `ClientWorldIndex.current()` shim; `ClientShapes` and `TextureSync` per region; `region` on
  everything the client relays (one link still).
- [T] JUnit: `RegionTableTest` (slots, overlap), `RegionsTest` (`at`, legacy), `SlotMapV2Test` (ranges; v1 read),
  `SessionRecordTest` (v1 read, v2 round trip), `SavePairsTest`, `ZoneRegions/DungeonSlots/CavePlanner` follow a moved
  origin. GameTests: the synthetic bundle built twice as `kanto` (slot 0) and `test2` (slot 1) in one world: both
  indices and shape documents relayed with `region`, pools distinct, `warp.request` resolves per region, a legacy world
  byte-identical to its golden. Pod: the real Kanto build under `regions/kanto/`, same hashes as `fef0e41`'s world
  except the file paths; block-state memory measured with 32 families (D9).
- **Check:** full suite; M9/M10 e2e scenarios unchanged. **Commit `MG3`.**

### MG4: Links (L)

- [C] `ClientConfig` v2 (`links`, `-Dkantobridge.links`, legacy `port`); `Links` and `BridgeLink`; per-link
  `SessionGate`, `GameRelay`, `SessionBinder`, `ClientStoryView`, `TextureSync`; per-body runtimes on `followed()`;
  `Links.follow`; `wrong_game`; the pause overlay for the followed link only; F7 lines per link; `region.state`.
- [C] "Add a region" in the title-screen flow (pick a link, region id, slot → export → build on the integrated server).
- [S] `region.state` handling; `RegionPresence` from it; the join-time S→C `region.state`.
- [T] `tools/launch.ps1 -Games` (installs per game, ports, `-Dkantobridge.links`); `fake_minecraft --links`; JUnit
  `LinksTest` (two fake dispatchers: follow/park swap closes screens and relinks; a parked drop never pauses; a wrong
  game is never followed); e2e `test_links.py` with two `fake_gen1` processes.
- **Check:** with one link configured, the full e2e on the test install behaves as before (regression). **Commit `MG4`.**

### MG5: Crossings (L) ✔

- [B] `ZonePlanner` builds a connecting route as two halves (`RouteSpec.connecting`, far gate = pass, prop variant
  `pass`), one per region, levels by `progress`; WorldIndex `zones[].pass`; a route whose other region isn't built yet
  gets its half with the pass closed (prop `pass_closed`, refused with `pass.not_built`).
- [S] `PassService` (§4.8 steps 1–4), `pass.cross`/`pass.abort`, the error codes, the timer; `RouteProgress` per region
  and zone; `route.give`/`route.battle` of a half to its region.
- [C] `PassRuntime`: the choreography (park → resume | start → follow → `region.state`), the fade, the HUD lines
  ("Starting Emerald…", refusals), the naming default (D6).
- [L] nothing new beyond MG2 (x1 arrival, A's warp back, the hold) — verified on the real engine.
- [D] `pass.cross`, `pass.abort`, the WorldIndex `pass` field, PROTOCOL.md "Crossings"; PLAYTEST.md "Several games".
- [T] JUnit `PassRuntimeTest` (every path: resume, start, refusals, timeout, link drop mid-crossing); GameTests: two
  synthetic regions joined by a synthetic connecting route (the synthetic bundle's `routes` v2 gains one), the server
  half of a crossing (`pass.cross` → `route.fade` → `region.state` → teleport to the far pass; abort and timeout
  paths; a player without the link refused). e2e: two `fake_gen1` (one `--state title`) through the whole choreography;
  then **Red ↔ Blue on the real engine**: the test install (Red or Blue) plus the user's second e2e install, a world with
  regions `kanto` (slot 0) and `kanto_red` (slot 1) and one connecting route, 20 crossings each way, one `start`.
- **Check:** full suite; the e2e logs hold only ids, counts and hashes. **Play-test (user):** cross from Viridian's
  half into the other Kanto; the first time the other game's NEW GAME intro appears and you end in its bedroom; cross
  back; cross again (a plain resume). **Commit `MG5`.**

### MG6: The second family (Emerald): adapter and export (XL, its own spec)

- Write `docs/specs/GEN3_BRIDGE_PLAN.md` from S2–S3: the `game3` adapter group by group in this order — boot and
  identity; observe (position, facing, state) and move (taps); warps (`warp.destination`, `player.warped`); UI (Gen 3
  text boxes, yes/no, naming, the mirror for the rest); NPCs (objects); story (flags through the `game3` store; blocks
  through `layout_native` overrides); saves and pairing (`SaveData` is shared); `region` (hold, resume, start,
  markers); export; battles and party; items and bag; routes (the exit: `maps:patch` plus `map_scripts` if S2 says they
  reach `gen3Scripts`, else the `warp.destination` hook). Each group lands with lupa tests on a `game3` fake engine.
- [L] the Gen 3 export: scene-shaped resources per the report's B3.3 (cells `kind/tags/dir/elev`, metatile quarters per
  plane as atlases, 16×16 palettes, sprites with frame tables, connections in cells, per-cell swap sites from the
  override audit); the manifest's `games` gains `emerald`.
- [B][S] `Gen3FrontEnd` → Scene (artPx 8 by quarters, planes, `elev`); `WorldPlanner` reads the Scene for Gen 3
  (converting the B7.2 classes it needs); flat Hoenn with edge walls on elevation mismatches; habitat pools from Gen 3
  encounters (national dex → Cobblemon ids, compat 386); `ProxyClass`/`BagItems`/`wild_routes` data per family.
- [T] a synthetic `gen3` bundle in `fake_gen1 --family gen3`; goldens; GameTests for a synthetic Gen 3 region.
- **Check:** full suite; e2e on an Emerald test install (the user's import): walk Littleroot, a door, a text box, a
  save. **Commit `MG6` (several commits by group).**

### MG7: Hoenn and the pass to it (L) ✔

- `routes.json`: the Kanto ↔ Hoenn pass (the user picks both exits with F7, as for the Viridian zone). The pod build of
  Hoenn at slot 1 (Littleroot and Route 101 first, then all maps); the budgets per region; videos; PLAYTEST "Hoenn".
- Per-map tweaks as they come up, each a small item in PROGRESS (the user's "each ROM requires its own tweaks").
- **Commit `MG7`.**

### MG8: Later (not in this plan; §8)

## 6. Checks

1. **Nothing regresses:** every golden file and the real Kanto build's hashes unchanged through MG1–MG4; the e2e
   scenarios of M9/M10 pass with one link.
2. **Two regions in one world** (GameTest): both indices relayed, distinct pools, warps per region, a legacy world
   untouched.
3. **Two links** (e2e with stand-ins): follow/park swap; a parked drop never pauses; wrong game never followed.
4. **A crossing** (e2e with stand-ins, then Red ↔ Blue on the real engine): 20 crossings each way, 0 teleports other
   than the pass teleport, the hidden players' cells unchanged by a park/resume pair, one `start` reaching the overworld.
5. **Budgets:** shape-map characters per region under 524,288; block-state memory with 32 families measured and noted;
   RAM of two hidden processes noted (S1).
6. **Logs:** ids, counts and hashes only, as today.

## 7. Risks

| Risk | Why it matters | Mitigation |
|---|---|---|
| Gen 3 is `beta` upstream and its internals move fast (36 releases in 10 days) | The `game3` adapter patches internals of an engine still changing | Pin stays; `Adapter.REQUIRES` makes drift loud; the adapter is written against the pin and re-checked on any bump |
| No documented Gen 3 map scripts; `movement.collision` and `fieldmove.eligibility` aren't raised by `game3` | The route exit and the closed lower row rely on them in Gen 1 | S2 decides: `map_scripts` → `gen3Scripts`, or the `warp.destination` hook (raised on all three engines) for the exit; the lower row closed by the zone's hedges alone |
| Gen 2 runs cart bytecode (no `map_scripts`, numeric flags) | A Johto adapter can't reuse the Gen 1 exit trick | Out of this plan; §8 names the research |
| RAM and windows per hidden game on the player's PC | Two LÖVE processes beside Minecraft | S1 measures; minimised windows keep full speed (M10) |
| Shared `options.lua` across processes of one install | Slot registry clobbered | One install per game (D3) |
| Slot capacity and block-state memory | 32 families ≈ +10–15 MB per side | Measured at MG3 (D9); the family count stays a constant that can be raised again |
| The client refactor (one singleton → links; 18 index call sites) | Large, touches every runtime | MG3's shim first, MG4 behind one `Links` facade with one link configured as the regression gate |
| Story safety of warps | A warp to the wrong cell skips triggers | §4.13: only our route maps or the saved cell; a start never skips the intro |
| `additionalProperties:false` everywhere | Every relayed type needs the optional `region` | One sweep in MG3 with the catalogue regenerated and both stand-ins updated |
| The first start's intro under a fade | Screens must be visible | The fade lifts for UI screens and returns for the final teleport |

## 8. Later, not in this plan

- **Same-process game switching**, if Gen1Recomp adds a mod facade for `returnToLauncher`/`bootGame`: the link manager
  then owns one process with N "virtual" links; the choreography is unchanged.
- **A Gen 2 adapter** (Gold/Silver/Crystal): bytecode scripts, numeric flags, `coordEvents`; exits through
  `warp.destination`; its own research first.
- **FireRed/LeafGreen** as a second Kanto (`kanto_frlg`): the `game3` adapter plus the Gen3Compat shim upstream provides
  for FRLG only.
- **The visitor plan** (foreign Pokémon playable in every region) and per-region rules (Gen 3 running shoes, bike speeds).
- **Heights for Hoenn outdoors** (elevation → levels), Gen 3 bridges (two floors in one cell), per-region moods.
- **A trainer wallet** across games (money, items) — a decision of the user's; DESIGN.md keeps them per game today.
- **Yellow** (accepted by the Gen 1 adapter; `isYellow` branches exist; the manifest and catalogue list it).

## 9. Decisions and defaults

| # | Decision | Options | Default taken | Needed by |
|---|---|---|---|---|
| D1 | The bridge mod's shape | one mod with `lib/engine/<engine>.lua` adapters (CLAUDE.md's rule becomes "only `lib/engine/*.lua`"); or one mod per family sharing a copied core | **one mod, adapters** | MG2 |
| D2 | Hidden game processes | one per game on its own port; or one process switching game | **one per game** | MG4 |
| D3 | Installs | one portable install per game; or one install with `POKEPORT_IDENTITY` per process (spike) | **one per game** | MG4 |
| D4 | The first second game | Emerald (Hoenn, the user's wish; `game3`, beta, no Gen3Compat); FireRed/LeafGreen (Gen3Compat helps, but Kanto again); Gold ("under construction", bytecode) | **Emerald**, after the system is proven with Red ↔ Blue | MG6 |
| D5 | A never-started game at a pass | start it (NEW GAME, the intro, the first room); or skip into the route map | **start it** | MG5 |
| D6 | The new game's player name | the followed save's name as the naming screen's default; or nothing | **default offered** | MG5 |
| D7 | What crosses besides Pokémon | nothing (money, bag, badges per game); or a trainer wallet | **nothing** | — |
| D8 | The region table | slots 8,192 blocks apart along +X, Kanto at 0; or ±X both ways; or a 2-D grid | **+X, 8,192** | MG3 |
| D9 | Slot capacity | `FAMILIES_PER_SHAPE` 32 with a range per region; or per-region slot maps (impossible: block states are global) | **32, measured** | MG3 |
| D10 | Protocol number | stay 1 with new types and optional fields; or bump to 2 | **stay 1** | MG1 |
| D11 | Gen1Recomp pin | v0.3.49 for all; or bump to v0.3.85 (Ruby/Sapphire, Gen 3 fixes; Kanto rebuild) | **stay**; revisit at MG6 | MG6 |
| D12 | The pass's look | a tunnel mouth prop (the dungeon entrance's); or a gate house | **tunnel mouth** | MG5 |
| D13 | Gen 3 art unit | split metatiles into 8-px quarters (pipeline unchanged); or teach the geometry 16-px art | **quarters** | MG6 |
| D14 | The Red ↔ Blue harness | a second e2e install imported by the user; or two processes on one data home (shared `options.lua`, unconfirmed safe) | **second install** | MG5 |

## Appendix A: Gen1Recomp facts this plan rests on

Read on 2026-10-13 from <https://github.com/bryanthaboi/gen1recomp> at tags `v0.3.49` (the pin) and `v0.3.85` (the
latest). Paths are the engine's. Anything marked ⚠ was not confirmed.

**A.1 Games.** v0.3.49 imports Red, Blue, Yellow, Gold, Silver, Crystal, FireRed, LeafGreen and Emerald side by side
(`README.md`); "Gen 2 support is still under construction", "FireRed, LeafGreen, and Emerald support is in beta".
`src/core/GameVersion.lua` gives each game `generation`, `engine` (`gen1` | `gs` | `crystal` | `game3`), `layout`
(`frlg` | `rse`), and `beta` on the Gen 3 rows only. v0.3.85 adds Ruby and Sapphire (`game3`, `rse`, beta). No
document claims any game is playable to completion ⚠.

**A.2 One game per process; no mod API to switch.** `main.lua` keeps one `Game`; `bootGame(version)` mounts that
version's cache and picks `Game`, `Game2` or `Game3`; `returnToLauncher` ends the session (unmount, unload generated
data, mod `Runtime.reset`, `Loader.endSession`) and rebuilds the launcher, from which another imported game can boot in
the same Lua state. The mod facades (`src/mods/Loader.lua`) have no launcher, session or restart entry; `love.event.quit`
is a no-op for mods; `core.quit_to_launcher` is a veto hook only. `--game=<id>` (aliases incl. `emerald`/`em`),
`--slot`, `--launcher`, `POKEPORT_GAME`, `POKEPORT_VERSION` select at start (`src/core/LaunchOptions.lua`,
`docs/guides/launch-options.md`). Saves: `saves/<version>/slotN.lua`; `options.lua` is **shared by all games** of an
install (`options.saveSlots[version]`, `modsByVersion`), rewritten whole with `.tmp`/`.bak` staging and no lock; no
single-instance guard anywhere; `POKEPORT_IDENTITY` names a separate data home. Portable mode: `portable.txt` beside the
app moves saves, options, caches, `roms/`, `imports/` and `mods/` next to it (`docs/guides/portable-mode.md`).

**A.3 Three engines.** `docs/preparing-your-mod-for-gen2.md`: "Gold is not a skin over the Gen 1 engine. It is a second
engine living beside the first one… A Gold boot never loads src/core/Game.lua, src/world/OverworldController.lua or
src/battle/BattleState.lua". Gen 1: `src/core/Game.lua`, `src/world/*`, `src/battle/*`, `src/script/*` with hand-ported
map scripts in `data/scripts/<map>.lua`. Gen 2: `src/core/Game2.lua`, `src/world/gen2/` (World.lua ≈ 12k lines),
`src/script/gen2/Vm.lua` running the cart's bytecode. Gen 3: `src/core/Game3.lua`, `src/core/game3/` (152 modules:
`field`, `map`, `warp`, `collision`, `connections`, `layout_native`, `field_view`, `scripting/opcodes_{frlg,emerald}`,
`profiles/<game>`, `constants/<game>`), `src/world/game3/WorldAPI.lua`. Shared: the launcher, `SaveData`, `Version`,
input, state stack, music, `Renderer`/`Assets`, the whole mod platform (`Loader`, `Registry`, `Schemas` with
GEN1/GEN2/GEN3 routing, `Hooks`, `Events`, `Gen2Compat`, `Gen3Compat`).

**A.4 Mod API.** Manifest `games` takes version ids, `gen1`/`gen2`/`gen3`, `frlg`/`rse` or `all`; omitted = Gen 1 only;
a non-matching mod is **not loaded** for that game (`ModTargets.supports`). Registries freeze at the merge
(`Registry:freeze`); hot reload is Gen 1 only (Gold lacks the console; Gen 3 ⚠). Hooks by engine: `movement.speed`,
`fieldmove.eligibility`, `movement.collision` on Gen 1 and Gen 2 only (`game3` raises `movement.collision` only inside
the Gen3Compat facade); `warp.destination`, `encounter.*`, `world.stepped`, `map.entered/exited`, `player.warped`,
`world.interacted`, `world.blacked_out`, `flag.changed`, `save.*`, `battle.*` core, `input.step`, `game.ready` on all
three; `world.talk` and `item.use` on Gen 1 and Gen 3; `trainer.before_battle` Gen 1 only; the UI hooks (`ui.*`,
`render.output`) Gen 1 and Gen 2 only. Registries on a Gen 3 boot: `pokemon, moves, items, encounters, trainers, text,
map_scripts (→ gen3Scripts, undocumented ⚠), rom_text` have Gen 3 homes; `maps` merges into the shared `Data.maps` with a
Gen 3 field schema (`maps:patch("FR_PALLET_TOWN", …)` works); `tilesets, sprites, palettes, field` and the rest are
dropped with a report. `mod.world:warpTo`, `queueScript`, `current`, `getFlag/setFlag` exist on all three WorldAPIs;
flag namespaces differ (Gen 1 `EVENT_*` strings; Gen 2 numeric; Gen 3 FireRed names/ids via `Gen3Compat.flagId`, the
`game3` flag store on Emerald). Gen3Compat serves the 15 Gen 1 module names only for `frlg` layouts; on Emerald such
requires are reported with no adapter. Badges: no facade (Gen 1 inventory items; Gen 3 flags).

**A.5 Data model.** Gen 1: 32-px blocks of 4×4 8-px tiles, behaviour from the cell's bottom-left tile against tileset
lists, one SGB palette per map, connections in blocks. Gen 2: COLL byte per cell, per-tile GBC attributes, eight
palettes by time of day, connections in blocks. Gen 3: one u16 per cell = metatile | collision << 10 | elevation << 12
(`src/import/gba/maps.lua`); dual-layer metatiles with NORMAL/COVERED/SPLIT layer types (`metatile.lua`), three indexed
atlases per tileset pair (under, middle, over) and sixteen 16-colour palettes (`native_pack.lua`, `tileset_native.lua`);
elevation 0/15 wildcards, 3 = ground and surf dismount, a mismatch blocks a step (`collision.lua`); connections in cells
(`connections.lua`); map ids like `FR_*` from `map_catalog.lua`; `field_view.lua` renders 240×160 with the over layer
above sprites; `layout_native.lua` holds per-cell overrides for runtime terrain changes.

**A.6 Drift v0.3.49 → v0.3.85.** 252 commits; `OverworldController.lua` +231 lines (423 changed), `SaveData.lua` +203,
`Game.lua` +72, `Loader.lua` +74; `Schemas.lua` maps gain `outdoor`; `warp` gains a `door` argument; the Union Room adds
a 2F to every Gen 1 and Gen 2 Pokémon Center at load (`docs/union-room.md`); ROM cache contracts bumped for every game
(re-imports). `Hooks.lua`, `Events.lua`, `Runtime.lua`, `Manifest.lua`, `ModTargets.lua`, `WorldAPI.lua`, `Map.lua`,
`Warp.lua`, `Collision.lua` unchanged; no hook names removed.

## Appendix B: the engine adapter interface

The groups are `kb_engine.lua`'s own milestone sections; the function names are its public table today
(`gen1-bridge/lib/kb_engine.lua`). An adapter implements a group whole or reports it absent; the core checks presence at
boot and treats an absent optional group as "not supported". **Required for `link`** (a game can be followed): boot,
observe, UI, movement, story, save/sessions, reliability. **Optional**: export (the builder), battles/gifts/party,
encounters/bag/items, PC/daycare/renames, routes, region.

| Group (section) | Functions | Serves |
|---|---|---|
| boot and identity (`:172-608`) | `setGame game takeOver version checkPin gameVersion logicStep save top overworld classifyState darkMaps badgeItems observe npcs emote`; new: `engineId family compat REQUIRES` | link |
| interaction (M4) | `interactedFacts cellInMap counterCell` | link |
| UI (M4) | `uiClass uiStack uiOpenFacts uiCursor uiItems uiMoneyFooter uiSetIndex uiSetQty uiSetGlyphs` | link |
| movement (M3) | `setFacing buttonDown playerVerdict warpDeparture displayPinned pinDisplay` | link |
| battles, gifts, shadow party (M5) | `bridgeToken nextSerial dexOf speciesOfDex moveNumOf moveIdOfNum trainerTeam battleHolds battlePending battleSent battleStale trainerBeforeBattle battleFinish ops opSent opSynced opDone excludedUids scriptCommand gifts giftSent giftSynced giftDone queueHeal releaseHeals takeHeals uidOf partyUids adoptUids partySafe applyParty bridgeCounts` | party, battle |
| encounters, Pokédex, bag, items (M6) | `attachBridge encounterRoll encounterFishing fishPushed nativeBattle encounterCounts inGame applyDex dexCounts bagCounts bagRemove tmWatchStep itemUse takeItemUses itemCounts` | items |
| Pokémon Center, saving, PC, sessions (M7) | `nurseHealed armCenterSave centerSaveArmed centerSaveStep savingReason queueSave takeSave queuedSaveSerial saveSlot pcItems pcPending pcSent pcClose pcHeld pcCounts` | link (save), PC |
| direct battles, catches, daycare, renames (M8) | `worldTalk directStepped directPushed nativeCaught releaseCatches daycareStep daycareUid partyNicks queueRename takeRenames contentCounts` | battle, PC |
| story, departures, field moves (M8) | `capturePristine pristineBlocks pristineCaptured pristineBlockAt storyMap liveBlocks liveBlockChanges cutRecords cutSwaps swapField storyToggles markDeparture fieldAction` | link (story) |
| save slots, online pairing (M9) | `slotState resolveActiveSlot slotRegistered slotHasSave labelSlot createSlot deleteSlot activateSlot playthrough titleMenuOpen switchGateOpen switchSave toTitle dropPlaythrough` | link (sessions) |
| reliability (M10) | `metaOf persistent runHidden hiddenStats` | link |
| export (M2) | `openDataset datasetSource liveSource worldShowing liveSourceFromGame mapQuery isOutside tileProbe shoreTiles cutTreeTiles spinnerTiles animatedTiles borderBlockFor voidFill modDataFile animationImages computeNeighbors paletteLookup imagePng` | export |
| Wild Routes (WR2) | `inventoryCount writeTrainerParty scriptBattleResult blackout` | routes |
| **region (new, MG2)** | `holdAway releaseAway awayMarker startFromTitle(mode) progressMarkers routeMapIds` | region |

Engine modules the Gen 1 adapter requires today (for its `REQUIRES` table): `src.core.Version`, `src.core.GameVersion`,
`src.core.Game`, `src.inventory.Badges`, `src.world.Map`, `src.world.FieldDefaults`, `src.core.Strings`, `src.ui.PartyMenu`,
`src.ui.PrizeCounter`, `src.ui.PokedexMenu`, `src.render.Tilt`, `src.render.Pipelines`, `src.render.Zoom`,
`src.mods.Runtime`, `src.pokemon.Pokemon`, `data.scripts.victories`, `src.core.Music`, `src.script.Commands`,
`src.core.RomText`, `src.render.TextBox`, `src.battle.BattleState`, `src.script.Flags`, `src.pokemon.Stats`,
`src.pokemon.Growth`, `src.inventory.Bag`, `src.inventory.ItemEffects`, `src.render.Transition`, `src.core.SaveData`,
`src.world.OverworldController`, `src.ui.Screens`, `src.render.TileRenderer`, `src.render.Assets`; plus the Game-object
slots `kantoBridgeCloseInstance`, `kantoBridgeState`, `kantoBridgeWindow`.

## Appendix C: message sketches

```json
{"type":"hello.ok","seq":1,"re":1,"data":{"protocol":1,"bridge":"0.1.0","engine":"0.3.49","tested":"0.3.49",
  "game":"emerald","family":"gen3","compat":{"maxDex":386,"maxMove":354,"nick":7},"session":"s-1a2b3c4d"}}
{"type":"game.status","seq":9,"data":{"game":"emerald","region":"hoenn","playthrough":"title","ingame":false,
  "started":false,"badges":0,"markers":{"starter":false,"champion":false},"slot":2}}
{"type":"region.park","seq":40,"data":{"region":"kanto"}}
{"type":"act.result","seq":77,"re":40,"data":{"ok":true,"kind":"park"}}
{"type":"region.resume","seq":41,"data":{"region":"hoenn","to":{"map":"KB_ROUTE_EASTERN_PASS","x":1,"y":0,"facing":"right"}}}
{"type":"region.start","seq":42,"data":{"region":"hoenn"}}
{"type":"act.result","seq":80,"re":42,"data":{"ok":true,"kind":"start","detail":"new"}}
{"type":"pass.cross","seq":12,"data":{"route":"eastern_pass","dir":"to"}}
{"type":"route.fade","seq":30,"data":{"ticks":10}}
{"type":"region.state","seq":13,"data":{"followed":"hoenn","parked":["kanto"]}}
{"type":"pass.abort","seq":14,"data":{"route":"eastern_pass","reason":"not_idle"}}
{"type":"world.regions","seq":3,"data":{"index":0,"count":1,"hash":"<64 hex>","part":"{\"version\":1,\"world\":\"…\",\"table\":{…},\"regions\":[…]}"}}
```
