# Worlds and sessions: the plan (WS0–WS8)

Written 2026-10-27 from the user's brief: a more succinct world creation, in which the player picks in Minecraft which
hidden games join the world and which world space is the default start town; the hidden games saved and closed
automatically when the Minecraft world is saved or closed; the relevant hidden games continued automatically when a
Minecraft world is loaded; and the bug the user hit: saving and leaving inside a wild route put the player back in the
game world (the town) on reload, while the hidden player stayed on the route map ("the holding space"), the movement
keys only turning the hidden player and moving nothing in Minecraft. It answers PROGRESS.md's open question 8 ("another
way to continue and load the games", 2026-10-24) and fixes its bugs B1 and B2 on the way; it also closes questions 2 and
3 there (switching worlds while a game runs; the standalone engine's slot after a session) and the "Starting in Hoenn"
gap of SEVERAL_GAMES_FOLLOWUPS.md. Every file and line below was read from the repo on 2026-10-27 (commit e72ad26); what
a pod or the user's PC must confirm is marked *(WS0)* or *(PC)*. Decisions are defaults the user may change (§3); the
phases are §4; §9 is the recipe for running it without the user.

## 0. In one paragraph

Today a world knows nothing about its games: "Create Kanto world" builds the region of whichever link happens to be
followed, "Add a region" adds one more per visit, the spawn is slot 0's town, the first connected link is followed, and
every hidden game boots to its title and waits for a CONTINUE picked through Minecraft (which hangs on Blue, B2). Saving
runs one way only: the hidden game's own save makes Minecraft save; nothing Minecraft does (autosave, `/save-all`, "Save
and Quit", closing the window) saves, parks or closes a hidden game, and the engines keep running at Minecraft's title.
Nothing about standing in a wild route is persisted on either side, so a reload with the hidden player on the 2×2 route
map and the body somewhere Minecraft didn't expect has no rule to reconcile them. The plan gives each world a **record
of its games** (`kantobridge/world.json`: the games, their save slots, the start region), one **creation screen** that
lists the games the player runs, takes a tick per game, a "start here" choice and a "continue this save / new game"
choice, and builds every chosen region in one go with the spawn at the start town; a **join** that continues every saved
game in the world (CONTINUE run by gen1-bridge itself, as the pass's `region.start` already does), follows the one the
player was in (else the start region) and parks the rest where they stand; a **save with the world** (Minecraft's save
events ask each bound game for its own save, which comes back as today's `save.begin` and pairs as today); a **close
with the world** (the pause menu's quit first saves every game, then returns each engine to its title and its offline
slot, then, when Minecraft started the engine, stops the process); **Minecraft-owned engines** (the mod starts the games
a world needs from `launch.json`'s installs and stops them with the world, so `launch.ps1` only starts Minecraft); and
for the wild routes an explicit **route state** in the snapshot plus one rule on both sides: a hidden player on a zone's
route map whose body is not in that zone is walked home through the route's own back warp, never left stranded, and a
body in the zone with its hidden player on the route map stays put. The first phase reproduces the user's bug on the pod
before anything is changed.

## 1. What exists (the survey of 2026-10-27)

### 1.1 Creating a world

- **"Create Kanto world"** (`fabric-bridge/src/client/java/dev/kantobridge/client/world/CreateKantoWorldFlow.java`): a
  title button (:147-174) runs CHECK → EXPORT → PACK → RELOAD → CREATE → LOADING → BUILD (:79-94) for the game of the
  **main link** only (`BridgeRuntime.get().client()`, :299-309), makes a level named `Kanto` or the next free name
  (`KantoWorldNames.free`, :436) with preset `kantobridge:kanto`, Adventure, Peaceful and `KantoGameRules` (:439-446),
  and files one `BuildRequest` (:443): a **legacy** build, no region table. `KantoWorldScreen` shows progress and has
  Cancel.
- **"Add a region"** (`AddRegionFlow.java`, `AddRegionScreen.java`, MG4): a world button cycling the saves that hold
  `kantobridge/regions.json` or `world_index.json` (:193-226), a link button cycling the connected links (:229-241), an
  id box defaulting to `Games.region(game)` (:136, :288), the slot `table.nextFreeSlot()` (:84-88); it refuses a second
  region of one world space (`region_exists`, :337-367), reopens the world and builds there. So a Kanto + Johto + Hoenn
  world is three visits: Create, then Add, then Add, each an export and a world reopen.
- **Headless:** `tools/e2e/build_world.py --maps --world --fresh --game --region --slot` (:441-453) → `runServer
  -Pkb.build…` (`fabric-bridge/build.gradle:171-181`, `builder/HeadlessBuild.java:40-46`).
- **The catalogue** `protocol/games.json` (`world/Games.java`): id, family, engine, label, `region` (red/blue/yellow →
  `kanto`, gold/silver/crystal → `johto`, firered/leafgreen → `kanto_frlg`, ruby/sapphire/emerald → `hoenn`), `adapter`
  (true for red, blue, crystal, emerald).
- **The spawn:** each export's root map (`gen1-bridge/export/kb_export.lua:40` PALLET_TOWN;
  `lib/engine/gen2/export.lua:53` NEW_BARK_TOWN; `lib/engine/game3/export.lua:48` EM_LITTLEROOT_TOWN) →
  `WorldPlanner.chooseSpawn` (:751-806: the walkable cell nearest the root's centre) → `BuildTask.java:677-682` sets the
  world spawn **only for a legacy build or slot 0**. Where the player really starts is the followed link's hidden
  player: `Links.java:37-39` follows the first connected link unless the server's stored presence names a region
  (`RegionPresenceService.java:20-27` sends `region.state` on join); `MovementSync.linkFrom` (:371) and the join's
  `rejoin` (:978-988) put the body on the hidden cell. A fresh player therefore always starts in the legacy region's
  game (SEVERAL_GAMES_FOLLOWUPS.md:65, open).
- **Per world** (`<world>/kantobridge/`): `world_index.json`, `slot_map.json`, `shape_*`, `build_report.json`,
  `regions.json` + `regions/<id>/…` (`RegionTable.java:16-31`, `RegionRecord.java:28-36`), `session.json` (the world
  UUID and each player's slot per region, `server/SessionRecord.java:24-40`), daycare files, datapacks. **Per player**
  (persistent attachments): `kantobridge:progress` (the last `game.status` per region), `presence` (the followed and
  parked regions, `RegionPresence.java:24-32`), `ledger`, `save_pairs`. **Global:** the client's export, pack and shape
  cache under `<gameDir>/kantobridge/`, `config/kantobridge-client.json` `links`, `local/launch.json` (installs, games,
  ports; `tools/kb_games.py:63-80`), and gen1-bridge's world → slot pairings in `mod_cache/gen1-bridge/session/
  pairings.<game>.json` (`act/kb_pairing.lua:25-43`).

### 1.2 Saving, leaving and joining

- **Saving is one-way.** gen1-bridge hooks the engine's `save.writing` (`act/kb_save.lua:10-17`: the start menu's
  SAVE, F1, the Hall of Fame; reason `menu`) and makes its own `center` save after the nurse (:19-25,
  `engine.centerSaveStep`, `lib/engine/gen1.lua:3805-3890`: `Game:writeSave` behind the engine's own `quickSaveAllowed`
  gate; Gen 2 `gen2/saves.lua:58-60`; Gen 3 `saveGame`, `game3/boot.lua:59`). `save.begin` G→M is the only save message
  (`lib/kb_catalog.lua:86-92`); the server's `SaveService.onBegin` (`server/SaveService.java:77-127`) pairs it, saves
  Cobblemon's data and runs `server.saveEverything` (:130-163), then answers `save.done`. No Fabric event asks a hidden
  game for anything: the mod's lifecycle hooks only clear memory (`SaveService.java:74` and the other `SERVER_STOPPED`
  handlers). A hidden save made with no client connected waits and goes out after the next `hello`
  (PROTOCOL.md:534-536).
- **Leaving:** `SessionRuntime.java:333-341` (`ClientPlayConnectionEvents.DISCONNECT`) answers waiting saves
  `ok:false`, forgets the world and binds nothing; nothing is sent to gen1-bridge. The links stay up at Minecraft's
  title; a parked game's hold is popped when its link drops, its `away` marker kept (`act/kb_region.lua:255-262`).
  `tools/launch.ps1` never stops Gen1Recomp (:24-26), restarts one that exits under `-Watch` (:568-587), and ends with
  "Gen1Recomp keeps running: save there if you like, then close it" (:589). A mod cannot quit the engine:
  `love.event.quit` is a no-op for mods (MULTI_GAME_PLAN.md Appendix A.2); `debug.quit` is the test role's and is
  honoured by the e2e drivers only (`lib/kb_session.lua:305`).
- **Joining:** the client sends `session.bind{world, multiplayer}` on entering and after every reconnect
  (`client/net/SessionBinder.java:126-152`). Multiplayer pairs the world with its own slot; **singleplayer keeps the
  current slot** (`kb_pairing.lua` `decide`, PROTOCOL.md:1348-1358), so singleplayer worlds share whatever slot the
  engine is on. Then the snapshot: `ingame:false` (the title) must not move the player (PROTOCOL.md:177) and `InputLock`
  holds the body with a HUD hint; on `ingame` the body is teleported onto the hidden cell (`MovementSync.onSnapshot`
  :342-360, `rejoin` :978-988, through `warp.request` and `WarpService.teleport`). `region.start`, `region.resume` and
  `region.park` are sent only by `client/zone/PassChoreography.java` (:183, :277, :301), at a pass; nothing continues a
  game at join. gen1-bridge can continue a save itself: `engine.startFromTitle()` (`gen1.lua:4134-4162`: `SaveData.load`
  → `game:restoreSave(loaded, recovered, {freshBoot, continued})`, else `startNewGame`; Gen 2 `gen2/region.lua:77`, Gen
  3 `game3/region.lua:93`), and return to it: `engine.toTitle()` (`gen1.lua:5421`, `gen2/saves.lua:75`, Gen 3
  `returnToTitle`). A game can be held where it stands (`engine.holdAway`, `gen1.lua:4081`; the region hold pushed on
  the engine's stack, the marker `{map,x,y}` in the save's mod data, `snapshot.away`) and resumed onto a route map or
  its own marker (`kb_region.lua:179-210`).
- **The known bugs** (PROGRESS.md:4884-4918): **B1** `PassChoreography.java:252-262` reads a far game's `title` as
  "never started" and stashes the whole party into the PC before `region.start` CONTINUEs a saved slot; **B2** CONTINUE
  picked on Blue's title through Minecraft hangs (`uiact.timeout`). Question 8 (PROGRESS.md:4958-4975) offers a. each
  game continues itself, b. the client starts every bound link at join, c. keep the titles and fix both. Question 3: the
  standalone engine keeps a multiplayer world's slot after a session. Fabric API 0.116.17 (`fabric-lifecycle-events-v1`
  2.6.0) has `ServerLifecycleEvents.BEFORE_SAVE` and `AFTER_SAVE` `(server, flush, force)`; which of the autosave,
  `/save-all` and the integrated server's stop fire them is *(WS0)*.

### 1.3 Standing in a wild route

- **Nothing is persisted.** The client's `away` is recomputed every tick (`MovementSync.awayInZone`, :942-954): true
  only with the body on no map cell, inside `index.zoneAt(x, z)`, and `zone.routeMap().equals(hidden.map())` (or in the
  dungeon region with the hidden map of kind ROUTE). While away: no steps, faces, drift fixes or rejoin teleports
  (:303-325). The server's `ZoneService.tick` (:56-72) decides by position; `ZoneEncounters.visits` is a `HashMap`
  cleared on disconnect (`ZoneService.java:49`). gen1-bridge's zone is just the map the hidden player stands on;
  `rt.byMap` is rebuilt at every boot (`lib/kb_routes.lua:462-638`, the route map registered at :542 with the stable id
  `KB_ROUTE_<ID>`); only the balls and wins are saved (`act/kb_route.lua:30-33, 85-115`).
- **The save on the route map** stores `KB_ROUTE_<ID>` as the player's map
  (`refs/gen1recomp/src/core/Game.lua:1362-1385`); CONTINUE restores it at x1 facing down (:1586-1592) with
  `via="boot"`, which `observe/kb_warp.lua:61` treats as no warp, so the client hears no warp. `save.loaded` in
  `gen1-bridge/main.lua:1290-1310` only reloads the route progress. From x1 the only open move is left onto A, whose
  step script warps home (`kb_routes.lua:543-549`); down is closed by `closedMove` (:396-410), up and right leave the
  2×2 map: every other key only turns the hidden player, which is what the user saw.
- **The body:** the route map has a placement at the trailhead, A=(0,0), x1=(1,0)
  (`builder/zone/ZonePlan.java:390-393`, `builder/plan/WorldPlan.java:225`), so a `rejoin` while **not** away teleports
  the body to the clearing in the town. `MovementSyncTest.aZoneDoesntHideAHiddenPlayerSomewhereElse` (:205-213)
  teleports the body out of the zone whenever the hidden player is reported off the route map. A hidden map the index
  lacks holds the body (`hiddenUnbuilt` :659-662 → `InputLock.Reason.UNBUILT_MAP`, `InputLock.java:136-137`) while
  `face()` (:916-936) still turns the hidden player.
- **Hypotheses for the user's reload** (none proven; WS0 decides): H1 the first in-game tick saw `away` false (the
  zone's route map id and the loaded save's map differed, or `hidden` was briefly a town cell such as the boot
  skeleton's or `lastHeal` after `scrubMaps`, `SaveData.lua:3037-3060`), so `rejoin` or the "somewhere else" rule
  teleported the body to the town; H2 the body stood in the town because the user's vanilla position was the clearing (a
  save made right after entering), and from there the route map's closed moves look exactly like the freeze; H3 the
  client index lacked the route map (a region or route-list mismatch) and `UNBUILT_MAP` held the body. Already noted:
  PROGRESS.md:1409-1413 ("Not checked: saving and loading inside the route map"), PLAYTEST.md:761 (unticked
  rejoin-in-zone item), WILD_ROUTES_PLAN.md §7 (saves inside a route map need gen1-bridge loaded).

## 2. The design

### 2.1 The world record

`<world>/kantobridge/world.json` (schema `protocol/world/world.schema.json`), written by the creation flow and read by
the server (`Regions`) and the client (`ClientRegions`):

```json
{"version": 1, "start": "johto",
 "games": [{"game": "blue", "region": "kanto", "save": "continue", "slot": 1},
           {"game": "crystal", "region": "johto", "save": "new"}]}
```

- `games`: the games this world plays, one per world space (D4 of the multi-game plan holds: Red and Blue are one
  `kanto`); `region` is the catalogue's unless the user typed another; `save` is the creation choice (`continue` the
  engine's current slot, recorded as `slot`; `new` a fresh slot the engine creates at the first bind), singleplayer
  only: multiplayer worlds keep their own slot per game as today.
- `start`: the region whose town is the world's spawn and whose game a fresh player follows first.
- A world without the file is a legacy world: its games are its regions' games, `start` is slot 0's region, every game
  `continue`; nothing changes for it until "Add a region" or a rebuild writes the file (as `regions.json` was in MG3).
- `world.regions` (S→C) gains `start` and the games list, so the client's F7 and the join decision read one source.

### 2.2 Creating a world: one screen, one build

"Create Kanto world" becomes **"Create world"** (`CreateWorldScreen`, replacing the progress-only `KantoWorldScreen`'s
front): the world name (default as today); one row per game the client knows, a tick box, the game's label and world
space, its save summary (`game.status.save`: the active slot's player name and badge count, or "no save"), a cycle
button "continue this save / new game" (shown when a save exists), and a "start here" radio; Create and Cancel. Known
games are the connected links' (`hello.ok.game`), as `AddRegionScreen` lists them; after WS7 also every imported game of
`launch.json`'s installs, which the flow starts on Create. Rules: at least one ticked game; one game per world space
(the second of a space is greyed with "same world as X"); the start must be a ticked game; a ticked game whose link is
down is refused with a HUD line ("Crystal isn't running"). On Create the flow exports each ticked game through its own
link (the pack holds every art set since 2026-10-16), makes the level with the region table filled (slots in the ticked
order, the start region's town as the spawn: `BuildTask` sets the spawn for the **start** region instead of slot 0),
writes `world.json`, and files one `BuildRequest` carrying every region; `BuildTask` builds them one after another
behind the one progress screen (per-region progress lines). "Add a region" stays for adding a game to an existing world
and now appends to `world.json`. Headless: `build_world.py --games blue,crystal --start johto` (`--game/--region/--slot`
kept for one region). The autopilot's `wild_zone` scene uses the new flow.

### 2.3 Joining: every game continues, one is followed

Question 8's option **a**, driven by the client's knowledge of the world (so b's decision stays on the Minecraft side):

- On `session.bind` the client adds `slot` (singleplayer, from `world.json`'s `continue` entry; absent = the engine's
  current slot, today's rule, or a new slot when `save` is `new` and none is recorded yet: gen1-bridge creates it and
  answers `session.bound.slot`, which the client writes back into `world.json`) and `continue`: `play` for the game of
  the region to follow, `park` for every other game listed in the world, `title` for a connected game the world doesn't
  list (today's behaviour: it stays at its title, never followed, F7 says "not in this world").
- gen1-bridge, after the slot is settled (the existing switch seams): `play` → `startFromTitle` when at the title
  (CONTINUE when the slot holds a save, else NEW GAME, the intro's screens as at a pass, D5 and D6 of the multi-game
  plan without the stash: a fresh player has nothing to stash, and a player whose far game is new is handled by the pass
  as before); `park` → the same CONTINUE, then `holdAway` where the save loaded (its town, its route map, wherever it
  saved), the marker written; a slot without a save under `park` stays at the title (`started:"none"`). Already in game
  (the engine was left running): `play` leaves it, `park` holds it. `session.bound` gains `started` and `parked` so the
  client and F7 know what happened; `game.status.save` says whether the active slot holds a save (read at the title from
  the slot registry and the save summary the title menu itself shows; per adapter).
- The region to follow: the server's stored presence (`region.state.followed`), else `world.json`'s `start`, else the
  legacy rule. A fresh player's presence is seeded with `start` on first join.
- **B1** falls out: the pass stashes only when the far game's `game.status.save` is absent (a truly new game); a far
  game continued at join is `saved` and resumed. **B2** becomes unreachable in a world (no title pick is needed); the
  mirrored title stays for a game bound with `title`, and its Gen 1 summary-box answer is a small fix in the same phase.
- Singleplayer slot pairing: a world with a `slot` entry is paired to that slot like a multiplayer world is (the
  switch seams of M9 do the work; `decide` gets a third branch), so two singleplayer worlds can run two playthroughs of
  one game.

### 2.4 Saving with the world

- **`save.request{reason}`** M→G (`world` | `quit`): gen1-bridge runs the center save's path (`writeSave` behind
  `quickSaveAllowed`, waiting up to `SAVE_WAIT` steps for the player to stand idle out of battle and dialogue, held
  games included), answered by `act.result{kind:"save", ok, reason?}` (`not_idle`, `vetoed`, `failed`, `not_ingame` for
  a title); the save announces itself as today's `save.begin` with the request's reason (`REASONS` gains `world` and
  `quit`), so pairing, Cobblemon's data and the Minecraft save run exactly as for a menu save.
- **Who asks:** the server, from `ServerLifecycleEvents.BEFORE_SAVE`, sends S→C `save.request{reason:"world"}` to
  every player whose links are bound; the client fans it out to its bound links (parked ones too). Loop guard: a
  Minecraft save that `SaveService.save` itself runs sets a flag the handler skips; rate limit one request per link per
  `SAVE_MIN` (default 60 s), so the vanilla autosave (every 5 minutes) saves the hidden games about as often.
  `/save-all` and the autosave both pass through `saveEverything`; the stop's own save and a dedicated server's stop are
  *(WS0)* (a dedicated server disconnects players before saving, so there the hidden saves ride on the player's own
  quit, below).
- **Nothing is rolled back:** as today, `save.done{ok:false}` is a counted warning; a request refused `not_idle` is a
  HUD line ("Blue didn't save: in a battle") and the world saves anyway.

### 2.5 Closing with the world

- **The quit choreography** (client, `client/net/SessionClose.java`): the pause menu's "Save and Quit to Title" and
  the window's close (`Minecraft.disconnect` through a `PauseScreen`/`Minecraft` mixin, once per world) first show the
  fade with "Saving Blue, Crystal…", send `save.request{reason:"quit"}` to every bound link, wait for each `save.done`
  or refusal (at most `QUIT_WAIT`, default 10 s; the window close 5 s), then let the vanilla disconnect run (the
  integrated server saves as today). A game that didn't save (refused or timed out) is named on the HUD and **left as it
  is**: never returned to the title or stopped with unsaved progress (D7).
- After the disconnect, **`session.close`** M→G to every link: gen1-bridge releases the hold, returns the engine to
  the title (`engine.toTitle`) and, when the slot was a world's, switches back to the home slot (PROGRESS question 3's
  first option; the pairing record already keeps `home`), answering `session.closed{title, home?}`. A link whose game
  didn't save gets no `session.close`.
- Then, for engines **the mod started** (§2.6), the process is stopped once `session.closed` arrived (or 10 s passed
  with the game saved); the PID file is removed. Engines started by the launcher are left running at their title
  (today's "keeps running" line in `launch.ps1` then says they are at the title).
- A crash of Minecraft changes nothing: the engines run on where they were, the next join continues or re-binds them
  as §2.3 says, and the save pairs' mismatch check (M10) still warns.

### 2.6 Minecraft owns the engines

- `client/engine/EngineLauncher.java` reads `launch.json` (path from `kantobridge-client.json` `engines.launch`,
  default `../launch.json` from the game directory: the dev client's `local/minecraft` → `local/launch.json`; Prism's
  player mode gets the path from the installer) and knows, per install, its folder, its imported games (names and the
  `rom-cache.complete` marker only, as `kb_games.py` reads them) and its port. On entering a world it starts, for each
  game in `world.json` with no link listening on its port, `gen1recomp.exe --game=<id>` with the install as the working
  folder (a visible window, PLAN U6; minimised is fine, M10), writes `<install>/kb_engine.pid`, waits for the port
  (`PORT_WAIT` 30 s) and lets the link connect; the HUD says "Starting Crystal…". A port already answering is an engine
  the user or `launch.ps1` started: it is used and never stopped. Two games of one install are never started at once
  (one install per world space, D3; a world holds one game per space, §2.2). A stale PID file whose process is gone is
  removed; one whose process is alive but not listening is reported, not killed.
- `launch.ps1` keeps starting engines by default this release (`-Engines launcher`); `-Engines minecraft` starts
  Minecraft only and hands the engines to the mod, and becomes the default once the user's PC check passes *(PC)*.
  `-Watch` is a launcher-mode feature only. The e2e runner's Linux engine (`run_gen1recomp.py`) is what the pod's check
  of this phase uses: the launcher takes a command template per platform from `launch.json` (`exe` for Windows,
  `command` for the AppImage), so the pod can test start, bind, save, close and stop end to end.

### 2.7 Saves and loads inside a wild route

- **Explicit state:** `snapshot.route{zone, map}` and `player.state.route` (set while the hidden player stands on a
  route map gen1-bridge registered, from `rt.byMap`) replace the client's map-id guess in `awayInZone`; the server
  stores the player's zone in `kantobridge:presence` (`zone`) when `route.*` messages say so, so a rejoin knows the
  visit.
- **One rule, both sides:** when the hidden player is on zone Z's route map and the body is **inside Z's box or its
  dungeon region**, the body stays where it is (away, as today: no rejoin teleport, ever, in that state); when the body
  is **anywhere else** (the clearing, the town, a Pokémon Center after a blackout), the client sends `route.leave{zone}`
  and gen1-bridge warps the hidden player home through the route's own back warp (`Routes.warpTo(zone, "back")`, the
  step script's path), which arrives as an ordinary warp the body follows. The route map is never a place the body is
  put (`hiddenSpot` of a ROUTE placement is the zone's clearing, so a stray rejoin lands on the trailhead, not on the
  2×2 map) and never counts as unbuilt (`hiddenUnbuilt` false for route maps; the hold reason stays for real partial
  builds).
- **On load:** `save.loaded` with the player on a route map sends the route state in the next snapshot (and, when the
  route list no longer has that zone, warps home by `lastHeal`'s rule before anything else); a join in the zone
  continues the visit (trainers rematch as the plan says; the balls are per save); `ZoneEncounters` rebuilds the visit
  from the stored zone instead of the next tick's position.
- **Saving in the zone** keeps working as today (the start menu is the hidden game's); with §2.4 the world's saves
  also save the parked hidden player on the route map, which is fine by this rule.

### 2.8 The protocol additions (protocol number stays 1, D10 of the multi-game plan)

| Message | Change |
|---|---|
| `game.status` | + `save?{slot, name?, badges}`: the active slot's save summary at the title (absent: no save) |
| `session.bind` | + `slot?` (singleplayer: the world's slot for this game), `continue?: "play" \| "park" \| "title"` |
| `session.bound` | + `started?: "continue" \| "new" \| "none"`, `parked?: bool` |
| `save.request` M→G, S→C | `reason: "world" \| "quit"` → `act.result{kind:"save", ok, reason?}`; then `save.begin{reason}` as today (`REASONS` + `world`, `quit`) |
| `session.close` M→G | `{}` → `session.closed{title: bool, home?: slot}` |
| `snapshot`, `player.state` | + `route?{zone, map}` while the hidden player stands on a registered route map |
| `route.leave` M→G | `{zone}` → `act.result{kind:"route.leave"}`; the back warp follows as a warp |
| `world.regions` S→C | + `start`, `games[]` (the world record) |
| `region.state` | unchanged; the server seeds a fresh player's `followed` with `start` |

Schemas in `protocol/`, the catalog regenerated (`kb_protocol.catalog --write`), `fake_gen1` and `fake_minecraft` taught
every new type, PROTOCOL.md's tables and rules updated in the phase that adds each.

## 3. Decisions (defaults proceed unless the user says otherwise)

| # | Decision | Default |
|---|---|---|
| D1 | Which hidden games a world plays | recorded per world in `kantobridge/world.json`; chosen on the creation screen; one game per world space; "Add a region" appends |
| D2 | The start town | the start region's root town is the world spawn and a fresh player's first followed game; chosen on the creation screen among the ticked games, default the first ticked |
| D3 | Saves per game in a singleplayer world | "continue this save" (the engine's current slot, recorded) or "new game" (a new slot gen1-bridge creates at the first bind); legacy worlds keep today's rule (the current slot) |
| D4 | How games continue at join | question 8's option a, decided by the client from the world record: the followed game is continued (or started new when it has no save), every other listed game is continued and held where it saved, an unlisted connected game stays at its title |
| D5 | What a world save does to the hidden games | asks each bound game (held ones too) for its own save through `save.request`, at most once a minute per link; a game not idle is skipped with a HUD line; never rolled back |
| D6 | What leaving a world does | saves every bound game first (up to 10 s), then `session.close`: the hold released, the engine back at its title and its offline slot; then the process stopped when Minecraft started it |
| D7 | A game that fails to save at quit | left running as it is, named on the HUD; never returned to the title or stopped |
| D8 | Who starts the engines | the mod, from `launch.json`, for the games a world lists whose port isn't answering; engines found already running are used and never stopped; `launch.ps1 -Engines minecraft` opts in until the user's PC check makes it the default |
| D9 | The wild route rule | the body in the zone with its hidden player on the route map stays; the body anywhere else makes the hidden player walk home through the back warp; the route map is never a rejoin target and never counts as unbuilt |
| D10 | The zone across a reload | the server keeps the player's zone in `presence`; `ZoneEncounters` rebuilds the visit from it; trainers rematch, balls stay per save (the Wild Routes plan's rules) |
| D11 | B1 and B2 | B1 fixed by `game.status.save` (stash only before a truly new game); B2 fixed minimally (Gen 1's summary box answered after a title pick) since a world no longer needs the pick |
| D12 | Order | the bug first (WS0 reproduces, WS1 fixes), then the protocol, join, save, close, the creation screen, the engines last (the only Windows-dependent phase) |

## 4. Phases

Each phase: tests for what it touches (JUnit, Lua under lupa, the stand-ins), the pod step named, then PROGRESS.md
("Worlds and sessions"). Pods per the standing rules (a small 22-only pod; stopped after each job; the full suite at WS4
and WS8). Push only on the user's go.

- **WS0 Facts on a pod** (~1 h, a 4-vCPU pod). (1) Reproduce the user's bug both ways against the real Blue engine:
  `test_wild_routes.py` gains a scene that enters the zone, saves on the route map through the start menu, then (A)
  drops and reconnects the client with the engine running, and (B) quits the engine (`debug.quit`), relaunches it on the
  same data home and CONTINUEs (`kb_crash.lua`'s way); record the snapshot's map and `ingame` sequence after each, and
  whether `scrubMaps` ever moved the player (`lastHeal`). On the Minecraft side, the autopilot's `wild_zone` scene gains
  "save in the zone, leave the world, rejoin" under Xvfb (`showcase.py`), logging `away`, the lock reason, `hidden`, the
  rejoin teleports and `warp: refused` lines; that decides H1/H2/H3. (2) Confirm which of the autosave, `/save-all` and
  the integrated server's stop reach `BEFORE_SAVE` (a GameTest with a counting handler, the stop from the log). (3)
  Confirm per adapter that `writeSave` succeeds while the region hold is up (a parked game saving) and what
  `SaveData.load`'s summary offers at the title for Gen 2 and Gen 3 (name, badges). (4) Confirm `engine.toTitle` after a
  world save leaves a clean title on all three engines (the e2e drivers already do this for Gen 1 and Gen 2). Findings
  in PROGRESS.md before WS1.
- **WS1 The wild route rule** (both sides, §2.7, D9, D10): `snapshot.route` / `player.state.route` and `route.leave`
  in gen1-bridge (`observe/kb_snapshot.lua`, `act/kb_route.lua`, the `save.loaded` handler; Lua tests with the fake
  world); `MovementSync` reads the route state, never rejoins onto a ROUTE placement, never holds for one, sends
  `route.leave` when the body is outside the zone (`MovementSyncTest`: the four cases of D9, the join in the zone, the
  join at the clearing, the join after a blackout); the server's `presence.zone` and `ZoneEncounters` rebuild (a
  GameTest: a player disconnects in the zone and returns to the same visit); `fake_gen1` sends the route state. Then
  WS0's scenes again on the pod: A and B both end with the body where it was and the hidden player free. PLAYTEST.md:761
  reworded for the user's own check.
- **WS2 The protocol and the world record** (§2.1, §2.8): schemas, catalog, `world.json` reader/writer
  (`WorldRecord.java`, shared by server and client; legacy fallback), `world.regions` with `start` and `games`,
  `game.status.save` in all three adapters (`observe/kb_progress.lua`), `session.bind.slot` in `kb_pairing.lua`'s
  `decide` (the third branch; its switch seams untouched), the stand-ins. Tests: `tools/tests` protocol and Lua,
  `WorldRecordTest`, a pairing test for the recorded slot.
- **WS3 Continue at join** (§2.3, D4, D11): `session.bind.continue` in gen1-bridge (`act/kb_pairing.lua` hands the
  settled slot to a new `act/kb_continue.lua` that reuses `startFromTitle` and `holdAway` through `kb_region`'s queue,
  answering `session.bound{started, parked}`); the client's join decision (`SessionBinder` from `ClientRegions` +
  presence + `start`); the server seeds presence with `start`; `PassChoreography` stashes only without
  `game.status.save`; the Gen 1 title pick answers the summary box. Tests: Lua (continue, park, no save, already in
  game), `SessionBinderTest`, `PassChoreographyTest` (B1's case), a GameTest for the seeded presence; on the pod
  `test_crossing_real.py` with the far game launched but not continued (B1's scene) and `fp_stone.py`'s CONTINUE (B2's
  scene), both green.
- **WS4 Save with the world** (§2.4, D5): `save.request` in gen1-bridge (`act/kb_save.lua`: the center save's path
  with the wait, the new reasons), the server's `BEFORE_SAVE` handler with the loop guard and the rate limit
  (`SaveService`), the client's fan-out (`SaveRelay`), the HUD line. Tests: Lua (idle, in battle, at the title, held), a
  GameTest that runs `saveEverything` and sees one request per player and no loop, `fake_minecraft` driving a request
  against the real engine on the pod (`test_hidden.py`). **Checkpoint:** the full suite.
- **WS5 Close with the world** (§2.5, D6, D7): the quit choreography (`SessionClose`, the pause-screen mixin, the fade
  and its lines), `session.close` in gen1-bridge (release, `toTitle`, the home slot; `session.closed`),
  `SessionRuntime`'s disconnect path. Tests: Lua (close from a saved game, from the title, from a world slot), a client
  test of the choreography's waits and timeouts with a fake port, on the pod `test_multiplayer.py` extended with a quit
  (the slot returns home: PROGRESS question 3 closed) and the autopilot scene "play, quit, rejoin" (continued where
  left).
- **WS6 The creation screen** (§2.2, D1–D3): `CreateWorldScreen` and the reworked `CreateKantoWorldFlow` (exports per
  link, the region table, the spawn at the start region, `world.json`, one `BuildRequest` with several regions,
  `BuildTask` building them in turn), "Add a region" appending to the record, `build_world.py --games/--start`, the
  autopilot on the new flow. Tests: `WorldPlan`/`BuildTask` JUnit (the spawn at a slot ≠ 0 region), a GameTest building
  two synthetic regions from one request, a client test of the screen's rules (one per space, the start among the
  ticked, a down link refused); on the pod a Kanto + Johto world built from one request with the start in Johto, joined:
  the player stands in New Bark Town, Blue continued and held in Pallet Town, F7 showing both. PLAYTEST.md's "Create
  Kanto world" items renamed.
- **WS7 Minecraft owns the engines** (§2.6, D8): `EngineLauncher`, `launch.json`'s command template, the PID file, the
  stop after `session.closed`, `launch.ps1 -Engines`, INSTALLER.md step 8 reworded. Tests: a launcher test with a stub
  process (start, port wait, already-listening, stale PID, stop order), on the pod the e2e Linux engine started and
  stopped by the dev client under Xvfb (`showcase.py` scene "cold start": no engine running → the world opens → Blue
  starts, binds, continues → quit → the process is gone). The Windows run is the user's *(PC)*: `launch.ps1 -Engines
  minecraft`, a world with Blue and Crystal, both windows appear and go.
- **WS8 The record:** PROGRESS.md ("Worlds and sessions": the findings, each phase, the numbers), PROTOCOL.md,
  PLAYTEST.md (the user's checks: create a world with two games starting in Johto; quit and rejoin; save in the zone and
  rejoin; `-Engines minecraft`), SEVERAL_GAMES_FOLLOWUPS.md ("Starting in Hoenn" ticked), PROGRESS questions 3 and 8 and
  bugs B1 and B2 closed, CLAUDE.md's pointer to this plan. **The full suite. Push on the user's go.**

## 5. Checks

- **C1 (WS1):** WS0's scenes A and B pass on the pod: after a save on the route map and a rejoin, the body stays in
  the zone and walks; after an engine restart and CONTINUE, the same; a rejoin at the clearing walks the hidden player
  home.
- **C2 (WS3):** a world with Blue (saved) and Crystal (saved), joined: both `session.bound{started:"continue"}`,
  Crystal parked, the player in Blue where its save stands; `test_crossing_real.py` with the far game not continued
  keeps the party (B1); `fp_stone.py`'s CONTINUE completes (B2).
- **C3 (WS4):** `/save-all` makes every bound game save once (two `save.begin{reason:"world"}`, two pairs recorded),
  no second request within a minute, no loop; a game in a battle is skipped and the world still saves.
- **C4 (WS5):** "Save and Quit" from a saved game: both games save, both answer `session.closed{title:true}`, the
  engine that had a world slot is back on its home slot; a rejoin continues both.
- **C5 (WS6):** one request builds Kanto and Johto; the spawn is New Bark Town's cell; `world.json` lists both with
  `start:"johto"`; a legacy world still opens and plays unchanged.
- **C6 (WS7):** the cold start on the pod: no engine → world open → engine started, bound, continued → quit → saved,
  closed, process gone, PID file gone; an engine already listening is used and survives the quit.

## 6. Risks

- **Reproduction.** WS0 may show a cause outside the three hypotheses (a region mismatch in the user's multi-region
  world, say). The rule of D9 still removes every stuck state the survey found, but the record must say what was seen.
- **`BEFORE_SAVE` coverage.** If the integrated server's stop bypasses `saveEverything`, the quit choreography carries
  the stop case alone (it does anyway); a dedicated server's stop never reaches the clients' engines: documented, not
  fixed.
- **Memory on small PCs** (question 8's cost): every listed game is continued at join. A world lists only what the
  player ticked, and a parked engine holds its scripts; the user's spike S1 (both games beside Minecraft) is still
  theirs.
- **Killing an engine.** `Process.destroy` ends it without its own shutdown. The order (saved → `session.closed` with
  the title reached → destroy) keeps `options.lua` and the saves whole; the PID file catches orphans. If a Windows quirk
  shows up *(PC)*, D8 falls back to "returned to the title, left running" with `launch.ps1`'s watch stopping them.
- **Pairing seams.** The recorded slot adds a branch to `decide`; the M9 switch seams (saved game → save → title →
  switch) are reused unchanged, and `test_multiplayer.py` covers them on the pod.
- **The creation screen's list** depends on links being up before the world exists; WS7 removes that, WS6 refuses a
  down link clearly meanwhile.

## 7. Not in this plan

- The installer (PROGRESS question 6) and Prism's player mode beyond the `launch.json` path.
- A way to reach a hidden game's title menu from Minecraft (another slot, the options): the engine's own window.
- Crash recovery that repairs (ROADMAP.md:75); hidden saves on a dedicated server's stop.
- Red and Blue in one world (D4 of the multi-game plan); a world space with two games.
- The Wild Routes plan's open content items (WR8) and the step-counting effects in zones.

## 8. Sizes

WS0 S (a pod hour); WS1 M; WS2 S–M; WS3 L; WS4 M; WS5 M; WS6 L; WS7 M, plus the user's Windows check; WS8 S. About two
unattended days of work on the server with pod sessions at WS0, WS1, WS3, WS4, WS5, WS6 and WS7.

## 9. Running it without the user (the recipe; written for an agent working alone)

The user has accepted D1–D12 unless they say otherwise and may not answer while this runs. Decide by the defaults; where
the plan is silent, keep today's behaviour and write the choice in PROGRESS.md. A blocked phase is recorded with its
reason and the work moves to the next phase that doesn't depend on it; the final report lists what is done and what is
not. **Where things are**, **pods** and **data safety** are as WATER_AND_BRIDGES_PLAN.md §8 says (the repo
`/home/claude/kantobridge`; small 22-only pods from `kb-dev51`'s shape, stopped after each job; `/workspace/local` never
opened or copied; map ids, cells and counts may leave the pod, nothing else). WS0 and WS1 come first and are not
skipped: the user's bug is the reason for this plan. The full suite runs at WS4 and WS8 (JUnit and GameTests in a capped
container on the server, pytest on the server, the e2e on a pod). Commits are one per phase step with the phase in the
message; nothing is pushed without the user's go.
