# gen1-bridge

The Gen1Recomp half of Kanto Bridge: a Gen1Recomp mod (Lua, mod API 2) that runs inside the
player's hidden Gen1Recomp. It listens on `127.0.0.1:27151` for `fabric-bridge`
([`docs/PROTOCOL.md`](../docs/PROTOCOL.md#transport)).

**Status: M5, opening, Oak's cutscene, starter and rival battle.** The listener, the handshake, the
heartbeat, `snapshot`, the live `player.pos`/`player.state` stream, the world export for the builder,
the step driver (`move.step`, `move.face`, `move.hold`), the `warp` messages, the UI observer and its
answers (`ui.*`), the NPC observer (`npc.*`, `emote`), `act.interact`, `act.button`, the mirror frames
(`mirror.*`), and now battles fought in Cobblemon (`battle.start`/`battle.result`), gifts
(`party.give`), heals (`party.heal`) and the shadow party (`party.sync`) work against the real engine
(end-to-end tests in [`tools/e2e/`](../tools/e2e): the title, Oak's speech with both names typed from
the client, Oak's escort, the starter and the lab's rival battle won and lost). Story diffs, wild
encounters, the bag and the Pokédex arrive in later milestones.

## What it does each logic step

Everything runs from the `input.step` hook (about 60 times a second; `core.update` doesn't fire under
test drivers), guarded so the hook always calls `next`:

1. **Network:** the listener binds lazily on the first step (no `SO_REUSEADDR`; a busy port is
   retried every second and logged once), takes one client at a time (a newer one replaces it), and
   moves bytes without ever blocking.
2. **Session:** the first message must be `hello` (protocol 1; roles `client`, `builder`, `test`),
   answered by `hello.ok` and a `snapshot`, or `hello.reject` and a close. `ping` gets `pong`;
   `resync` gets a `snapshot`; 10 s of silence drops the client. `debug.status` and `debug.quit` are
   for the `test` role only. Unknown types get `error unknown_type`, types that never travel this way
   `error wrong_leg`; protocol types handled in later milestones are accepted quietly.
3. **Player:** the classifier turns the engine's state into a mode (`screen`, `busy`, `forced`,
   `cutscene`, `idle`), and changes go out as `player.state`. Each step start goes out as
   `player.pos` with the **target** cell and its length in frames (kind `walk`, `bike`, `surf`,
   `script` for the engine's own moves such as a door walk-out, `spin`, `slope` for the Cycling Road
   roll); a ledge hop is one `player.pos` kind `ledge` at its landing cell with the whole hop's
   frames; turns, warps and teleports go out as `player.pos` kind `sync`.
4. **Steps** (`act/kb_move.lua`, PLAN §4.1 "Step driver"): `move.step{dir, n, push?}` requests
   queue (at most 2 waiting behind the one in flight; the oldest beyond that is dropped and logged).
   While the classifier says `idle` and the player stands, the driver sets the facing directly (no
   4-frame turn) and holds the direction through `mod.input`; the engine starts the step in that
   same logic step. The hold is released on the landing poll, unless the next queued step is ready:
   it then starts in that same logic step, with no idle frame. Every `move.step` is answered exactly
   once with its `n`: the step's `player.pos` (its start; a hop's landing; kind `push` for a Strength
   boulder push; kind `sync` when it set off a warp), or `player.blocked` (`tile`, `entity`,
   `bounds` from the engine's `movement.collision` verdict; `script` when the hidden game isn't
   taking steps or the step was dropped). Holds are a "desired" set, pressed again after a focus
   change's `Input:reset`, and all released the moment the mode leaves `idle`. `move.face` turns in
   place while idle (answered by a `sync`); `move.hold{b}` holds B, the Cycling Road brake.
5. **Warps** (`observe/kb_warp.lua`): `warp{phase:"start", kind}` when a warp begins (the engine's
   `player.warped` event, a blackout, or a fade with no warp entry), then `warp{phase:"arrive", kind,
   to}` once `map.entered` reports the destination, followed by the `player.pos` sync of the new
   cell. Kinds: `door` (a door tile, or a gate doorway), `stairs` (another warp tile), `edge` (an exit
   mat on the map edge), `pad`, `hole`, `elevator` (out of a `*_ELEVATOR` car), `fly`, `teleport`
   (Dig, Teleport and Escape Rope look alike here), `blackout`, `script`. A connection crossing is not
   a warp: its step's `player.pos` carries the new map.
6. **NPCs** (`observe/kb_npcs.lua`): the current map's NPCs (`ow.npcs`) are diffed by id every
   step. `npc.set` (the whole list) on map entry (every new NPC table: warps, seam crossings) and after
   every snapshot; `npc.add`/`npc.remove` when one appears or goes (show/hide, an item taken);
   `npc.move` at each step start with the target cell and its frames (kind `walk` for its own
   wandering, `script` for a cutscene move, `push` for a Strength boulder, `march` for a
   walk-in-place beat; a jump with no step is `script` with 0 frames); `npc.face` for a turn. Kinds:
   `item`, `boulder`, `static`, `trainer`, else `person`; the sprite is the NPC's appearance or its
   own. The global emotion bubble goes out as `emote` (`!`, `?`, `happy`, or null when silent) on the
   player or an NPC. The snapshot's NPC list is shaped by the same code.
7. **UIs** (`observe/kb_ui.lua`): the screen stack is polled every step (`screen.pushed`/`popped`
   only wake a re-read of open lists' rows). A state opens once, when it first becomes the top
   (`ui.open`, ids unique per connection; after every snapshot the open UIs are sent again, bottom
   first, with their ids), sends its moving parts as `ui.update`, and closes once it has left the
   stack (`ui.close`). Kinds: text (pages and `cont` once, then 0-based page/line, waiting (the
   arrow is up) and done; `instant`; `choice` when a YES/NO opens over it at its end; `auto` and
   `stay` only for boxes whose end takes no press; pages of more than 8 lines are split into protocol
   pages), choice (labels, index 0 = the first, the question from the text box under it), menu and
   list (rows with their right column, cursor and scroll, `wrap` when the cursor goes round; the
   Mart's footer and money), quantity, naming (title, length, presets, the
   default, the species' dex number and the engine's letter `grid` without its ED and case cells; its
   preset menu is part of it), small list adapters for the Pokédex list, the prize counter and the
   Fly list, and `mirror` (with the screen id) for everything else that takes input: the party menu,
   the summary, the trainer card, battles, the title. Fades, flashes and panels open nothing; the mod
   manager and anything above it are ignored. All text goes out in the protocol markup
   (docs/PROTOCOL.md "Text": `#` becomes `POKé`, `<PK>`/`<MN>` stay, font-only sequences and leftover
   engine tokens are written out or dropped).
8. **UI answers and buttons** (`act/kb_uiact.lua`), one tap per step: `ui.advance` taps A only when
   the text box is ready (waiting with its 3-frame preWait over, or finished and closed by A/B; never
   during a scroll or page clear, never for auto, stay or choice boxes); `ui.pick` puts the cursor on
   the row and taps A (a YES/NO's NO is B, which always answers NO); `ui.back` taps B; `ui.qty` sets
   the number and taps A; `ui.name` checks the glyphs against the grid and the length, picks NEW
   NAME (or the preset itself) when the presets are up, then sets the glyphs and taps START, the
   screen's own confirm. Answers wait for their UI to be on top; a closed UI takes its answers with
   it (`error ui.stale`), and one that never comes back on top times out (`error ui.timeout`).
   `act.button` taps START while idle (the start menu then opens as a normal menu) or on the start
   menu (closing it), and forwards any button, as a tap or a hold, to a mirror screen; holds end with
   their screen and the A+B+START+SELECT soft-reset chord is refused. Every `act.button` gets an
   `act.result`.
9. **Interaction** (`act/kb_interact.lua`): `act.interact{map,x,y}` sets the facing toward the cell
   and taps A in the same logic step, only while idle, on the player's map, with the cell adjacent or
   across a counter (it waits for a step in flight to land). The engine's `world.interacted` gives
   the `act.result` (`ok`, `kind`); an NPC caught mid-step is retried once where it stands. Refusals:
   `not_idle`, `wrong_map`, `off_map` (a cell across a map seam, x or y -1 or one past the edge: the
   A press never reaches another map), `not_adjacent`, `busy`, `nothing`, `no_effect`,
   `npc_moving`, `npc_moved`.
10. **Mirror** (`observe/kb_mirror.lua`): while a client is subscribed (`mirror.sub{fps}`, at most
    30), the `render.output_enabled` hook asks the engine for its full-window composite and a
    read-only `render.output` observer captures it: the game rectangle downscaled into a 160×144
    canvas (inside `push("all")`/`pop()`), `newImageData`, a hash to skip identical frames,
    `encode("png")` with no filename, base64. The observer always returns `next(ctx)`, so the engine
    presents the frame as usual; it never hides a state, never takes the output over and never writes
    a file. Right after a `mirror.sub` the current picture goes out even if unchanged; then at most
    `fps` frames a second, changed ones only, the newest one per logic step, dropped while the client
    lags; a capture that keeps failing switches itself off. Measured on the test install: about
    1.5 ms per capture (0.9 ms of it PNG encoding), 0.1-1.7 KB PNGs, no change to the 16.7 ms frames.
11. **Battles** (`act/kb_battle.lua`, kb_engine's battle seams; PLAN §4.1 "Battles"): while a client
    or test session is connected, the battles the hidden game would fight are fought in Cobblemon.
    A scripted battle row (`start_battle`, `rival_battle`, `static_battle`, seen through the
    `script.command` hook) waits inside its script on the runner's own `waitingCheck`; a map trainer
    (sight or talk) is deferred through `trainer.before_battle`, with the world held as a sighting
    holds it. Meanwhile the classifier says `busy`, and `battle.start` goes out with the team the
    engine would build (species as dex, level, 1–4 Gen 1 move numbers from `Pokemon.movesAtLevel`,
    the boss-move table, the rival's starter offset, the trainer DVs) and the trainer's class,
    1-based party index, name, NPC and cell; it goes out again with the same id after every snapshot
    until its `battle.result` arrives. A result first shows what the battle screen would have (the
    trainer's loss line and the prize, added to the money; on a loss `Rival1WinText` and the
    blackout lines, except for the Oak's-lab rival), then applies the engine's own effects: a script
    resumes through `start_battle`'s resume path (`lastBattleResult`, `lastCheck`, `afterBattle`: a
    loss blacks out, except in Oak's lab); a map trainer gets engageTrainer's win effects (defeated,
    event flag, rewards, `afterBattle`) and its encounter ends with `continue({cancel = true})`.
    `aborted` (or a trainer battle "fled"/"caught") applies nothing: the script stops, the encounter
    is cancelled, so the battle can be fought again. Without a client the hidden game fights its own
    battles (shown on the mirror).
12. **Gifts, heals, the shadow party** (`act/kb_party.lua`): `give_pokemon` runs natively, its
    nickname prompt included (it reaches the client as a text box, a YES/NO and the naming screen),
    and the gift goes out as `party.give` (`starter`, `prize` or `gift`; moves, DVs, nickname, OT)
    once it returns, again after every snapshot until a `party.sync` shows it (same give id). Until
    then the engine's own copy stays in the hidden party; a gift sent to a Gen 1 box is taken out of
    it (Cobblemon's PC keeps it). `party.sync` rebuilds the hidden party from Cobblemon's (uids kept
    in `mod.save`, members kept by uid in place, the pending gift matched by species and level,
    moves by Gen 1 number, HP scaled, status, nickname; non-Gen-1 species and moves left out and
    counted), as soon as that is safe (not at the title, not during a give, not under a native
    battle); the last one applies again after New Game or CONTINUE. `heal_party` and a blackout
    send `party.heal`. The snapshot's party carries the uids it knows.
13. **The opening** needs nothing of its own: the title screen and Oak's speech arrive as mirror and
    text UIs, the title menu as a menu, and both naming screens as `ui.open{kind:"naming"}` (presets,
    grid) answered by `ui.name` through the M4 observer path, which no hot reload can break.
14. **Options pin (PLAN P21):** the hidden game's display options stay classic (no tilt, zoom, render
   pipelines, wide or fill battles, or dynamic UI) and text speed stays FAST. They are pinned in
   memory at boot, on New Game and on CONTINUE, and re-checked every second. Nothing is written to
   disk; Gen1Recomp's own OPTIONS screen persists them if the player saves options there.
15. **World export** ([`docs/PROTOCOL.md`](../docs/PROTOCOL.md) "Export resources"): for `builder`
   and `test` connections, `export.index` and `export.get` serve the ten resource kinds (maps with
   per-cell flags, tilesets, images, sprites, palettes, encounters, field tables, names, the surface
   layout, swap sites). The first request starts one build of everything in a coroutine that
   yields after about 6 ms of work per step; requests wait until it's done. Resources are cached
   for the instance's lifetime as canonical JSON (the hash is its SHA-256), and replies go out at
   most 256 KiB per step, pausing while the client lags more than 1 MiB behind.

### Where the export's data comes from

- **First choice:** `mod.datasets:open(<game>)`, the engine's read-only view of the imported cache.
  It hands out detached copies, so the live tables the game changes during play (Cut, card-key
  doors, elevators) never leak into the world. It is the pristine import, without other mods'
  changes.
- **Fallback (PLAN §8):** when the view doesn't open at `mods.loaded`, the live `Data` is
  deep-copied right then, before any map has loaded. If the view opens but reads nothing at export
  time, the live `Data` is copied then, but only while no map has been loaded in this instance;
  otherwise the export fails with `error export_failed`.
- On v0.3.49's portable build the view works (the e2e test reports which path ran). Each record
  read through it costs about 5 ms, because the view re-checks every decoded cache module on each
  read (`src/mods/DatasetViews.lua:209-243`), so a full build takes about 5 s of CPU, spread over
  roughly 500 steps, with a couple of one-off hitches of up to about 0.2 s while the engine decodes
  a cache module.
- Every engine question (per-cell flags through `Map.new`, the neighbour layout through
  `computeNeighbors`, image bytes through `Assets.imageData`, tile animations, spinner tiles) goes
  through `lib/kb_engine.lua`. Anything that can't be exported correctly is left out and counted
  under a warning code, logged with its count when the build ends.

Warnings and errors from `kb_log` are also sent to the client as `log` messages (at most 5 a
second). Logs only ever show codes, engine symbols, counts, lengths and short hashes.

**The port** is the mod option `port` (default 27151, in Gen1Recomp's F10 mod manager). Test drivers
move it with `game.mods.exports["gen1-bridge"].configure({ port = N })`.

**Hot reload (F5) and returning to the launcher:** each instance leaves a "close my sockets" function
on the Game object; the next instance calls it on `game.ready`, before it binds, so the port and the
client are released at once.

## Files

| File | Purpose |
|---|---|
| `manifest.json` | Read by Gen1Recomp's loader. `network` unlocks `require("socket")` (LÖVE's bundled LuaSocket) inside the mod sandbox; `engine_internals` discloses that the mod requires engine modules (`src.*`). Gen1Recomp only displays permissions; it never prompts. |
| `main.lua` | Entry point. Loads the modules, defines the `port` option, wires the `input.step`, `movement.collision`, `render.output_enabled`, `render.output`, `script.command` and `trainer.before_battle` hooks and the `game.ready`, `save.created`, `save.loaded`, `player.warped`, `map.entered`, `world.blacked_out`, `screen.pushed`/`popped` and `world.interacted` events, routes `move.*`, `act.*`, `ui.*`, `mirror.*`, `battle.result` and `party.sync` to their modules, keeps one tap per logic step, orders each step's messages (`player.state`, `warp`, `player.pos`/`player.blocked`, `act.result`, `npc.*`/`emote`, `ui.close`/`open`/`update`, `party.give`/`party.heal`, `battle.start`, `mirror.frame`), and publishes a status table in `mod.exports` (versions, counts, flags) for test drivers. `debug.status` adds the battle and party counters and the typed names' lengths and short hashes. |
| `lib/kb_json.lua` | Our own strict, deterministic JSON codec: sorted keys, `{}`/`[]`/null kept apart, strict parsing, errors that never quote the input. |
| `lib/kb_sha256.lua` | SHA-256, for the short hashes logs may show and for export hashes. |
| `lib/kb_log.lua` | Redacted logging: a code plus fields; any string that isn't id-shaped prints as `<str:LEN:SHA8>`. |
| `lib/kb_net.lua` | The non-blocking TCP listener: one client at a time, newline framing up to 4 MiB, a send queue that keeps unsent tails, `finish` (send what is queued, then close), port changes. |
| `lib/kb_proto.lua` | The message envelope (`type`, `seq`, `re`, `data`), dispatch, replies and `error` answers, with a gate and catalog-aware handling of unhandled types. |
| `lib/kb_session.lua` | One connection's session: hello first, handshake, heartbeat timeout, resync, debug messages, live streams, log forwarding. |
| `lib/kb_catalog.lua` | **Generated** from `protocol/catalog.json` by `tools/lua/gen_catalog.py`: the message types and their legs. A test fails when it is stale. |
| `lib/kb_engine.lua` | **The only file that touches engine internals.** Each function cites the engine `file:line` it relies on (v0.3.49): the overworld and its player, the top state's class, NPCs, dark maps, badges, the options pin, the instance takeover slot, the movement internals (setting the facing, held buttons, the player's collision verdicts, warp departures, hop/boulder/departure facts), the M4 internals (the screen stack's classes and stable keys, each widget's fields and its ready test, the few writes before a confirming tap: a cursor, a quantity, a name's glyphs; NPC steps and scripted moves, the emotion bubble, `world.interacted`'s target, counter tiles), and the export's data sources and queries (dataset views, the live-Data copy, `Map.new` and its cell queries, `Map.isOutside`, `computeNeighbors`, tile animations, spinner tiles, `Assets.imageData` + PNG encoding, the SGB palette cascade, and the few engine constants with nothing to call: cut-tree and shore tiles), and the M5 seams: the `script.command` and `trainer.before_battle` bodies (the held battle on the runner's `waitingCheck`, `start_battle`'s resume path, engageTrainer's win effects, `continue({cancel})`, the result texts and prize), trainer teams (`Pokemon.movesAtLevel`, the copied boss-move table and trainer DVs, the rival's offset), `give_pokemon`'s gift capture, `heal_party`, the shadow party's rebuild (`Pokemon.new`, `Stats.calc`, `Growth.expForLevel`, `stampOT`) with its uid map, and the process-lifetime `kantoBridgeState` slot on the Game object. Re-check them all when the Gen1Recomp pin changes. |
| `observe/kb_player.lua` | The mode classifier (`busy` while a Cobblemon battle holds the world) and the `player.state`/`player.pos` observer, working on plain facts from `kb_engine`: step starts, ledge hops (one message at the landing), syncs, and tags the step driver reads. |
| `observe/kb_warp.lua` | The `warp` start/arrive messages and their kinds, from the warp events and the transition facts. |
| `act/kb_move.lua` | The step driver: `move.step` queue, facing first, holds through `mod.input` (released on the landing poll, re-pressed after an input reset, all released outside `idle`), outcomes (step, hop, push, warp, blocked) answered once per `n`, `move.face`, `move.hold`. |
| `observe/kb_snapshot.lua` | Builds `snapshot` from the facts and the save: position, state, flags, toggles, bag, money, coins, badges, the hidden game's party (with the Cobblemon uids it knows), the Pokédex, the current map's NPCs (shaped by `kb_npcs`). |
| `act/kb_battle.lua` | Battles fought in Cobblemon: the `battle.start` payload of a battle kb_engine holds (kind, trainer, team, wild, rules), sending it and re-sending it after snapshots, `battle.result` (outcomes to the engine's results; errors `battle.unknown`, `bad_message`). |
| `act/kb_party.lua` | Gifts (`party.give`, re-sent until a sync shows them, given up on after five syncs and two minutes), heals (`party.heal`), `party.sync` (checked, mapped to Gen 1 species and moves, matched to pending gifts, applied when safe), the uids in `mod.save`. |
| `observe/kb_npcs.lua` | The NPC observer: `npc.set`/`add`/`remove`/`move`/`face` and `emote` from a per-step diff by id; `Npcs.shape`, the one place an NPC becomes a protocol entry. |
| `observe/kb_ui.lua` | The UI observer: the stack's states as `ui.open`/`update`/`close` by kind, the protocol text markup (`Ui.markup`), re-announcing after a snapshot. |
| `observe/kb_mirror.lua` | The mirror: subscriptions, the fps cap, the capture inside the `render.output` observer, dedupe, the cost counters. |
| `act/kb_uiact.lua` | UI answers (`ui.advance`, `ui.pick`, `ui.back`, `ui.qty`, `ui.name`) and `act.button`, applied as taps to the top state. |
| `act/kb_interact.lua` | `act.interact`: face and press A in one step, confirmed by `world.interacted`, one retry for a moving NPC. |
| `export/kb_export.lua` | The export service: roles, the request queue, the budgeted build coroutine, the cache, budgeted replies, and every resource's shaping (map records, surface, parents by BFS, the `LAST_MAP` memory, palettes, tile classes, images, sprites, encounters, field tables, names, layout). |
| `export/kb_cells.lua` | The per-cell flag integers of one map, from `kb_engine`'s map queries plus `field.ledges`, `field.spinners`, the warps and signs. |
| `export/kb_images.lua` | Base64, image ids from asset paths (`<folder>.<name>`), and the image and sprite resources. |
| `export/kb_swapsites.lua` | Runtime block-change sites: cut trees (from the cells), card-key doors and lift gates (`field.cardKeyDoors`), the Game Corner poster and the Vermilion Gym door; curated script swaps come with M8's audit. |
| `mod.card` | Sharing metadata for the mod manager and gallery. The loader never reads it. |

Rules for this folder:

- Modules are loaded with `load(mod:read(rel), "@" .. rel)()`, which compiles them into the mod's
  sandbox. Never `require` our own files.
- File names start with `kb_`, and no file may mention the engine's generated-data folders by path
  (modkit's MK301 and MK305 checks).
- Only `lib/kb_engine.lua` reaches into the engine; everything else gets plain values from it.
- Nothing may log game text. Log codes and fields through `kb_log`.
- Tests live outside this folder, in [`tools/lua/`](../tools/lua) (lupa, LuaJIT 2.1, with a copy of
  the engine's mod sandbox, a fake engine and a fake LuaSocket).

## Trying it

The pinned Gen1Recomp release lives in `local/gen1recomp/` (see [`CLAUDE.md`](../CLAUDE.md)).
`portable.txt` sits beside the exe, so all its data stays in that folder. This mod folder is linked
into it as `local\gen1recomp\mods\gen1-bridge` (a junction), so edits here take effect on the next
boot without copying.

1. Start `local\gen1recomp\gen1recomp.exe`. On the very first run, import your ROM from the launcher.
2. Boot a game. Later runs can boot directly with `--game=blue` (or `--game=red`). Mod entry
   points run only once a game boots, not in the launcher.
3. To see the log, add `--console`. In PowerShell, from inside the install folder:
   `.\gen1recomp.exe --console --game=blue`. Look for `[gen1-bridge] gen1-bridge loaded`, then
   `[gen1-bridge] game.ready ...`, and `net.listening ... port=27151` once the title screen runs.
   `POKEPORT_DEV=1` enables the in-game developer console (backtick).
4. Connect a stand-in client: from `tools/`, `.venv\Scripts\python.exe -m fake_minecraft --seconds 10`
   prints what gen1-bridge sends (types and sizes; `--verbose` shows them with game text redacted).
   The export is exercised end to end by `tools/e2e/test_export.py`.

Automated runs use the separate test install `local/gen1recomp-e2e/` through
`tools/e2e/run_gen1recomp.py`, never the user's install.

Keep Gen1Recomp's window open behind Minecraft and never minimise it. A minimised window drops to
about a third of normal speed (from reading the code, not measured).

To remove the link, use `rmdir local\gen1recomp\mods\gen1-bridge` in `cmd`. Never use `rm -rf`
through it, which can delete this folder's contents.
