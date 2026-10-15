# gen1-bridge

The Gen1Recomp half of Kanto Bridge: a Gen1Recomp mod (Lua, mod API 2) that runs inside the
player's hidden Gen1Recomp. It listens on `127.0.0.1:27151` for `fabric-bridge`
([`docs/PROTOCOL.md`](../docs/PROTOCOL.md#transport)).

**Status: M8 (wave 3 of 3), the rest of Kanto: static encounters, special battles, gifts, trades,
the Name Rater, gyms and the league, the daycare, battle music, and the loss lines trainers' scripts
hand their battles** (M7 brought Viridian City: the
Pokémon Center, the Mart, the PC, money, trainers, saving). The
listener, the handshake, the heartbeat, `snapshot`, the live `player.pos`/`player.state` stream, the
world export for the builder, the step driver (`move.step`, `move.face`, `move.hold`), the `warp`
messages, the UI observer and its answers (`ui.*`), the NPC observer (`npc.*`, `emote`),
`act.interact`, `act.button`, the mirror frames (`mirror.*`), battles fought in Cobblemon
(`battle.start`/`battle.result`), gifts (`party.give`), heals (`party.heal`), the shadow party
(`party.sync`), wild encounters (grass and cave rolls left to Cobblemon's habitat pools, surfing and
fishing fought in Cobblemon, the Safari Zone and ghost battles native with `battle.native`), the
Pokédex (`dex.sync`), the mirrored bag (`bag.sync`/`bag.use`), items used on a party member
(`party.item`), and now saving (`save.begin`/`save.done`, the nurse's heal saving both games),
`session.bind`/`session.bound`, Bill's PC as Cobblemon's PC (`pc.open`/`pc.closed`), the Mart through
the generic menus, and map trainers that sight the player work against the real engine (end-to-end
tests in [`tools/e2e/`](../tools/e2e): the title, Oak's speech with both names typed from the client,
Oak's escort, the starter, the lab's rival battle won and lost, 200 grass steps on Route 1 with no
native battle, and Viridian City: the nurse, both saves, Bill's PC, a Mart purchase and sale, a Viridian
Forest trainer beaten and another lost to, the blackout). **M8 (wave 1)** adds the story diffs
(`story.flags`, `story.toggles`, `story.blocks`, `story.lit` and the snapshot's effective toggles,
live blocks and lit maps), field moves from Minecraft (`act.field`), the forced-movement kinds
(spinners, Seafoam currents, the Cycling Road, holes, pads, boulders) and the audited table of every
runtime block change the engine makes (end to end: a cut tree reported and grown back, Surf, a
Viridian Gym spinner, an elevator, a Strength boulder). **M8 (wave 2)** adds the static encounters
and the battles the engine pushes directly (the ghost Marowak with the SILPH_SCOPE, Giovanni in the
Rocket Hideout), native catches crossing over (the Safari Zone), in-game trades (`party.take` +
`party.give{trade}`), the Name Rater (`party.rename`), the daycare (`daycare.deposit`/`withdraw`),
the Hall of Fame's heal, and the Gen 1 battle themes while Cobblemon fights (P22) (end to end: a
Power Plant Voltorb caught and gone for good, the ghost Marowak, Giovanni, Eevee, a trade, the Name
Rater, Brock with his badge, the daycare both ways, a Safari Zone catch). **M8 (wave 3)** shows the
loss line an `engageTrainer` caller hands its battle itself (the Game Corner rocket, Silph Co
Giovanni, the Karate Master, the Nugget Bridge recruiter, every gym leader's badge lines with their
jingle) after a Cobblemon win (end to end: the Game Corner rocket and Brock; plus the league: Lorelei
beaten and her door open with no heal before Bruno's room, the Champion through the Hall of Fame and
the credits back to the title, and Snorlax woken by the Poké Flute, aborted, woken again and caught).
**M9 (multiplayer)** gives every multiplayer world its own save slot, switched to at runtime
(`session.bind` → `session.bound`, item 27), and lets a second instance on the same PC listen on the
next free port when the configured one is taken (item 1; end to end: two worlds, a restart, a switch
from a saved game, a switch that waits, back to the offline slot with its save untouched, and the
port fallback). **M10 (reliability, performance)** hardens the handshake (item 2), gives every hidden
save a pair that comes back with it after a crash or restart (`save.begin.pair`, `save.loaded`, item
20), says how a bind reached its slot and why one waits (`session.bound.switched`,
`session.waiting`, item 27), ids Bill's PC holds (`pc.open{pc}`, item 21), measures its own cost per
logic step (item 28), caps the mirror at 30 frames in any second (item 10), and keeps a hidden window
at full speed while a client is connected (item 29) (end to end: the handshake's rejections and
timeout, a New Game, a reconnect in a text box, a save, a gift, a kill, a relaunch and CONTINUE with
the same pair; 150-200 µs per logic step on average over a busy scenario; a hidden window at 60
logic steps a second instead of 20).

**Wild Routes (WR2)** adds the zones' exits and route maps at boot (`lib/kb_routes.lua`), the acts
`route.give`, `route.battle` and `route.blackout` answered by `route.result`, the zones' progress
(`route.progress`, the snapshot's `routes`), and the export's `routes` resource with the towns'
clearings in their `blocks` ([docs/PROTOCOL.md](../docs/PROTOCOL.md#wild-routes-wr2)). Lua tests:
`tools/lua/test_lua_routes.py` on `tools/lua/routesim.py`.

## What it does each logic step

Everything runs from the `input.step` hook (about 60 times a second; `core.update` doesn't fire under
test drivers), guarded so the hook always calls `next`:

1. **Network:** the listener binds lazily on the first step (a busy port is retried every second and
   logged once; `SO_REUSEADDR` only off Windows, where it merely clears a crashed predecessor's
   TIME_WAIT remnants and never shares a live listener's port, logged as `reuseaddr=true` on
   `net.listening`), takes one client at a time (a newer one replaces it), and
   moves bytes without ever blocking. **M9:** when the configured port stays busy (or refused) for
   3 seconds, the listener takes the next free port of the 8 after it (27152 … 27159 for the default
   27151), logs `net.listening port=<n> configured=<p> fallback=true`, and reports it in the exports
   (`port`, `portConfigured`, `portFallback`) and `debug.status`; with every port of the span busy it
   starts over from the configured port every second (logged once). It stays on the port it took
   until the port option changes or the listener breaks.
2. **Session:** the first message must be `hello` (protocol 1; roles `client`, `builder`, `test`),
   answered by `hello.ok` and a `snapshot`, or `hello.reject` and a close. `ping` gets `pong`;
   `resync` gets a `snapshot`; 10 s of silence drops the client. `debug.status` and `debug.quit` are
   for the `test` role only. Unknown types get `error unknown_type`, types that never travel this way
   `error wrong_leg`; protocol types handled in later milestones are accepted quietly. **M10:**
   `hello` is checked field by field against its schema: another protocol number is
   `hello.reject{reason:"protocol"}` whatever else it carries, then any malformed field (an unknown
   key, `mod`, `minecraft`, `player`, `world`, a role that isn't a string) is `malformed`, and a role
   string that isn't one of the three is `role`; every reject is answered and the connection closed
   once it has gone out. A connection with no `hello` 10 s after connecting gets
   `error{code:"no_hello", msg:"hello_timeout"}` and is closed; so is one whose first line isn't even
   an envelope (after its `bad_envelope`). `hello.ok.tested` is the engine version this gen1-bridge
   is tested with (`Engine.TESTED`, 0.3.85, beside `kb_engine.lua`'s citations); when the running
   engine differs, each connection gets one `log` warning `engine.untested` after its snapshot.
   `debug.status` counts them (`sessionRejected`, `sessionRejectedProtocol`/`Role`/`Malformed`,
   `sessionHelloTimeouts`, `sessionBadFirstLines`, `sessionUntested`). **MG1** (several games,
   `docs/specs/MULTI_GAME_PLAN.md` §4.2): `hello.ok.game` is the running game's id as the catalogue
   (`lib/kb_games.lua`, generated from `protocol/games.json`) names it, never coerced to `red`, with
   its engine `family` and the adapter's `compat` limits (`maxDex`, `maxMove`, `nick`); a process
   running a game outside the catalogue or outside the games this mod is for (the manifest's `games`
   list, `main.lua`'s `supported`) answers every `hello` with `hello.reject{reason:"game"}` and closes
   (`sessionRejectedGame`; the log line names the game id).
3. **Player:** the classifier turns the engine's state into a mode (`screen`, `busy`, `forced`,
   `cutscene`, `idle`), and changes go out as `player.state`. Each step start goes out as
   `player.pos` with the **target** cell and its length in frames (kind `walk`, `bike`, `surf`,
   `script` for the engine's own moves such as a door walk-out, `spin` for a spinner's slide (48
   frames a cell), `current` for a Seafoam current's drag or push-back (M8: a chain of the player's
   scripted steps that began on one of the map's current cells while surfing), `slope` for the
   Cycling Road roll); a ledge hop is one `player.pos` kind `ledge` at its landing cell with the
   whole hop's frames; turns, warps and teleports go out as `player.pos` kind `sync`.
   `player.state` carries `bike`, `surf`, `slope` (on the bike on a slope map), `dark` (a
   `field.darkMaps` map) and `lit` (dark and lit by Flash).
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
   mat on the map edge), `pad`, `hole` (also a hole a map script opens: no `player.warped` then, the
   hole's sink names it), `elevator` (out of a `*_ELEVATOR` car), `fly`, `dig`, `rope`, `teleport`
   (M8: the escape spin is named by whoever started it -- `act.field`, or the bag's ESCAPE ROPE seen
   through the `item.use` hook -- else by the place: TELEPORT works only outside, DIG only in a
   dungeon), `blackout`, `script`. A connection crossing is not a warp: its step's `player.pos`
   carries the new map.
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
   Mart's footer and money: its BUY/SELL/QUIT menu shows the money box too; a menu or list whose
   footer or money changes while open, such as the clerk's next line or the money after a purchase,
   is sent again as `ui.open` with its own id, which the client replaces in place), quantity
   (with the Mart's unit price), naming (title, length, presets, the
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
   screen's own confirm; no glyphs is an empty name, which the screen confirms as its own (no
   nickname, or its default), refused where it has presets. Answers wait for their UI to be on top; a closed UI takes its answers with
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
    **M10:** on top of the paced captures (a carried deadline, so a late one and the on-time ones
    after it could put 31 in a second), no frame goes out while 30 have gone out in the last second
    (a sliding window, less 2 ms of slack for clock noise): the frame waits a step, or a newer
    capture replaces it. `debug.status`: `mirrorPeakFps` (the most frames in any second so far),
    `mirrorCapped` (steps a frame waited for the window).
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
    trainer's loss line, its tag on the first page, and the prize, added to the money; M8 wave 3: a
    map trainer's win first runs the encounter's own start -- `continueBattle()` with
    `BattleState.newTrainer` stood in for and `pushBattle` diverted for that one call, so no battle is
    built or pushed -- which yields the loss line its caller handed over (`endBattleText`, else the
    header's), its sound and sound page (a gym leader's badge jingle, played once that page has typed)
    and `rewardDialogueShown`; on a loss `Rival1WinText` and the blackout lines, except for the
    Oak's-lab rival), brings the map's song back as a battle screen's exit does (M7), then applies the
    engine's own effects: a script resumes through `start_battle`'s resume path (`lastBattleResult`,
    `lastCheck`, `afterBattle`: a loss blacks out, except in Oak's lab); a map trainer's win is the
    encounter's own `onFinish` (defeated, event flag, rewards with `rewardDialogueShown`,
    `afterBattle`, the caller's completion; M5's copy of those effects with
    `continue({cancel = true})` remains the fallback when the start can't run), and a loss ends its
    encounter with `continue({cancel = true})` after `afterBattle`. A map trainer that sights
    the player plays natively up to its battle text: the "!" bubble over it (`emote`), its walk-up
    (`npc.move` kind `script`), the challenge. `aborted` (or a trainer battle "fled"/"caught")
    applies nothing: the script stops, the encounter is cancelled, so the battle can be fought again
    (a cancelled sighting waits until the player moves). Without a client the hidden game fights its
    own battles (shown on the mirror).
12. **Gifts, heals, the shadow party** (`act/kb_party.lua`): `give_pokemon` runs natively, its
    nickname prompt included (it reaches the client as a text box, a YES/NO and the naming screen),
    and the gift goes out as `party.give` (`starter`, `prize` or `gift`; moves, DVs, nickname, OT)
    once it returns, again after every snapshot until a `party.sync` shows it (same give id). Until
    then the engine's own copy stays in the hidden party; a gift sent to a Gen 1 box is taken out of
    it (Cobblemon's PC keeps it). `party.sync` rebuilds the hidden party from Cobblemon's (uids kept
    in `mod.save`, members kept by uid in place, the pending gift matched by species and level,
    moves by Gen 1 number, HP scaled, status, nickname; non-Gen-1 species and moves left out and
    counted), as soon as that is safe (not at the title, not during a give, not under a native
    battle); the last one applies again after New Game or CONTINUE. `heal_party`, the nurse's heal
    and a blackout send `party.heal`; a blackout's goes out right after its warp's `arrive` (the
    player lands at the last heal point's town, then Cobblemon's party heals), or after 10 s at
    most. The snapshot's party carries the uids it knows.
13. **The opening** needs nothing of its own: the title screen and Oak's speech arrive as mirror and
    text UIs, the title menu as a menu, and both naming screens as `ui.open{kind:"naming"}` (presets,
    grid) answered by `ui.name` through the M4 observer path, which no hot reload can break.
14. **Wild encounters** (M6, PLAN P8/P9; kb_engine's `encounter.roll` and `encounter.fishing`
    wrappers): while a client or test session is connected, grass and indoor (cave) rolls return nil
    without rolling (Cobblemon's habitat pools spawn those Pokémon in Minecraft). A roll the engine
    would make a Safari Zone battle (a Safari game on a SAFARI-region map) or an unidentifiable ghost
    battle (a Pokémon Tower floor without the SILPH_SCOPE) runs natively on the mirror. A surfing roll
    is the engine's own (and REPEL's filter), then held for Cobblemon instead of the native battle:
    `battle.start{kind:"surf", wild{dex, level, x, y}, rules{catch, flee}}` with the water in front
    of the player, the world held like a trainer's encounter. Fishing rolls through the engine (Old,
    Good and Super Rod; "Not even a nibble!" stays native); a bite plays its cast and "Oh! It's a
    bite!" natively, then goes out as `kind:"fish"` instead of the native battle. Results: `win` and
    `fled` continue (the engine's wild-battle ending: grace steps), `caught` too (the Pokémon is
    Cobblemon's: it arrives by `party.sync`, its Pokédex entry by `dex.sync`), `lose` blacks out,
    `aborted` applies nothing. `battle.native{kind:"safari"|"ghost"|"old_man", phase}` announces a
    native special battle when it appears on the stack and when it leaves (again after a snapshot).
    Without a client the hidden game rolls and fights everything itself.
15. **The Pokédex** (`act/kb_party.lua`): `dex.sync{seen, owned}` from Cobblemon is added to
    `save.pokedex` (species from dex numbers; owned implies seen; nothing is ever cleared), once an
    overworld is up, and again after New Game or CONTINUE, so Oak, his aides and the Pokédex count
    see it.
16. **The bag** (`observe/kb_bag.lua`, PLAN P17; MG6 `docs/specs/MULTI_GAME_PLAN.md` §4.9, the one trainer
    economy): `bag.sync{items, money, stamp?}` carries every non-key item of the canonical item catalogue
    (`lib/kb_items.lua`, generated from `protocol/items.json`: balls, medicine, X items, field items,
    stones, valuables, TMs; never key items, HMs or badges, `isKeyItem`) the bag holds, the save's money
    and, once the server has projected its ledger into this save, the projection's stamp (`mod.save
    "ledger"`), after every snapshot and whenever one of them changes, only in game. `bag.use{item,
    qty}` takes the **mirrored** items -- the catalogue's items with a Cobblemon item and a battle-usable
    class, PLAN Appendix B's 29 -- out of the hidden game's bag through the engine's `Bag.remove`, never
    below zero (a counted mismatch warning); anything else is refused (`error bag.not_mirrored`).
    **`bag.set{items, money, stamp}`** (`act/kb_ledger.lua`, MG6) is the server's projection of the
    player's ledger into this save on a `region.resume` or after a `region.start`: when the world is
    free (in game, standing, no script, warp, battle or screen, no save-slot switch waiting; at most 120
    steps, else `busy`; `not_ingame` at the title), the adapter's `bagSet` takes every catalogue item
    that isn't a key item out of the bag (`Bag.remove`; items the catalogue doesn't know stay) and adds
    the projection in class order with one `Bag.add` per 99 (20 slots, 99 a stack); what did not fit is
    answered as `act.result{kind:"bag.set", ok:false, reason:"too_many", left}` (the rest is written
    anyway), else `ok:true`; `save.money` is written; the stamp lands in `mod.save "ledger"` and the
    `bag.sync` of the same step echoes it. Symbols the game's table doesn't know are dropped and counted
    (`bag.set_unknown`).
17. **Items on a party member** (kb_engine's `item.use` wrapper around BagMenu's use-flow): while a
    client is connected, an evolution stone the member's species evolves with and RARE_CANDY (below
    level 100) are Cobblemon's to apply: the Gen 1 effect is skipped, the item is used up from the
    Gen 1 bag, the party menu closes, and `party.item{item, uid}` goes out once. A TM or HM is taught
    natively (its forget prompt is the slot picker); the move it taught is watched for and goes out
    as `party.item{item, uid, move, slot?}` (`slot` when it took an old move's place). The next
    `party.sync` brings Cobblemon's result. Anything the engine refuses, uses in battle, and members
    with no Cobblemon uid stay native. `party.sync` entries naming their gift (`give`) confirm that
    exact gift; without it the M5 dex-and-level match applies.
18. **Options pin (PLAN P21):** the hidden game's display options stay classic (no tilt, zoom, render
   pipelines, wide or fill battles, or dynamic UI) and text speed stays FAST. They are pinned in
   memory at boot, on New Game and on CONTINUE, and re-checked every second. gen1-bridge never
   writes the options file itself, but every progress save writes the live options with it
   (SaveData.save calls saveLiveOptions, `src/core/SaveData.lua:2585-2597`): the player's own saves
   and, from M7, the save after the nurse's heal leave the pinned values in `options.lua`, as
   Gen1Recomp's own OPTIONS screen would.
19. **World export** ([`docs/PROTOCOL.md`](../docs/PROTOCOL.md) "Export resources"): for `builder`
   and `test` connections, `export.index` and `export.get` serve the ten resource kinds (maps with
   per-cell flags, tilesets, images, sprites, palettes, encounters, field tables, names, the surface
   layout, swap sites). The first request starts one build of everything in a coroutine that
   yields after about 6 ms of work per step; requests wait until it's done. Resources are cached
   for the instance's lifetime as canonical JSON (the hash is its SHA-256), and replies go out at
   most 256 KiB per step, pausing while the client lags more than 1 MiB behind.
20. **Saving** (`act/kb_save.lua`, M7, PLAN flow 12): every hidden-game save announces itself
   through the engine's `save.writing` event and goes out as `save.begin{save, reason}` (ids
   `v-<token>-<n>`, unique per gen1-bridge process): reason `center` for the save after the nurse's
   heal, `menu` for every other one (the start menu's SAVE, F1, the Hall of Fame). A save made while
   no client or test session is connected waits, the newest only, until one is. **The Pokémon
   Center:** gen1-bridge notices the nurse's heal machine (a new `healAnim`); Cobblemon's party heals
   at once (`party.heal`), and while a client or test session is connected the hidden game saves
   itself (`Game:writeSave`, behind the engine's own F1 quick-save gate) as soon as her lines are
   done and the player stands; its `save.begin{reason:"center"}` follows. The engine's `lastHeal` (the
   blackout return point) is the nurse's own, never written by gen1-bridge. A vetoed or failed write
   is a counted warning. `save.done{save, ok}` is log only: the hidden save already happened and is
   never blocked or rolled back; `ok: false` is a counted warning (`save.minecraft_failed`, also a
   `log` message); an unknown or repeated id is counted and logged, never answered with an error.
   **Sessions:** `session.bind{world, multiplayer}` is answered with `session.bound{slot}` (`re` set)
   once the hidden game is on the slot paired with the world (M9: item 27); a singleplayer world
   keeps the active save slot (`slotN` → N, 0 for the flat legacy save file). The world and the flag
   go into the hidden save's mod data (`mod.save` "session"), again after New Game or CONTINUE; the
   world id is never logged, only its length. The save made before an M9 slot switch is never
   announced as `save.begin` (it belongs to the pairing being left), and while a switch waits, saves
   wait unannounced.
   **M10, save pairs (crash recovery).** Every hidden save gets a pair, 16 hex digits new for each
   save, written into that save's own mod data during its `save.writing` (`mod.save` "savePair" =
   `{pair, at, ops}`): `mod.save` is the save table's `modData`, which the engine serializes with the
   save in the same file (lib/kb_engine.lua "reliability (M10)"), so a CONTINUE of that save -- in
   this process, after a crash, after a restart -- finds the same record. `save.begin` carries it as
   `pair`, with `at` = the save's own `meta.savedAt` (Unix seconds, stamped by the engine before
   `save.writing`). Not used, because they live apart from the save: `mod.storage` (its own files,
   written at once) and the playthrough id (one per playthrough, and filled in from `options.lua` on
   load when the file lacks it). A save from before M10 gets a pair derived from its own meta
   (`savedAt`, `sessionStart`, the engine), the same on every load.
   `save.loaded{fresh, ops, pair?, at?, daycare?, boarded?}` says which save the hidden game runs from: after
   every `session.bound` while in game (behind that step's `party.*` and `save.begin`, so the server
   pairs a waiting save before it compares), and when the game is entered from the title (CONTINUE or
   F2: `fresh:false` with the loaded save's pair; NEW GAME: `fresh:true`, no pair), while a client or
   test session is connected; never at the title. `ops` counts the Pokémon operations sent
   (`party.give`, `party.take`, `daycare.deposit`, `daycare.withdraw`; each once, when it first goes
   out: a repeat after a snapshot is the same operation) since the save was loaded or its
   `save.begin` went out (stream order). An operation the hidden game made after a save that is still
   waiting to be announced (no client yet) waits until that `save.begin` is out, so Minecraft saves
   before it and counts it after, as the hidden save did. The save before a slot switch keeps the
   identity the world being left knows: its record is that world's last pair, with `ops` = the
   operations sent since plus those the switch drops unsent. `daycare` is the Cobblemon uid of the
   Pokémon in the hidden daycare, read from the save itself (its daycare and the boarded uid kept in
   its mod data), so it rolls back with the save; `boarded` names them all when the daycare holds
   more than one (Gen 3's holds two). The running save's identity lives in the
   process-lifetime slot, so a hot reload keeps it. `debug.status`: `savePairs`, `saveLoadedSent`,
   `saveOps`, `saveFresh`, `saveLegacy`, `saveLoads`, `saveNewGames`, `saveOpsSent`.
21. **Bill's PC** (`act/kb_pc.lua`, M7): the Pokémon Center PC's rows pass the engine's
   `ui.pc.items` hook; while a client or test session is connected, the box row ("BILL'S PC", or
   "SOMEONE'S PC" before meeting Bill) runs its own access text and then, instead of the Gen 1 box
   screens, holds the hidden game (a plain state over the still-open PC menu: mode `busy`, no UI)
   and sends `pc.open` once. `pc.closed` ends the hold and the PC menu takes input again (another row,
   or LOG OFF), all native. The player's item PC and the other rows are untouched; without a client
   the box row is native. A dropped link releases the hold at once; a hold whose world went away
   (the title, New Game, CONTINUE) is dropped; `pc.closed` with no hold is counted and ignored. A hot
   reload keeps it working: the hold lives in kb_engine's process-lifetime slot and a menu built
   before the reload asks the current instance. **M10:** every `pc.open` carries a fresh id
   (`pc-<token>-<n>`, unique per process), kept with the hold; only the `pc.closed` naming it ends the
   hold; one with another id (a stale one) or with no hold at all is counted (`pcStale`,
   `pcUnexpected`) and ignored; one without a valid id is `bad_message`.
22. **The story** (`observe/kb_story.lua`, M8, docs/PROTOCOL.md "Story rules (M8)"), while a client
   or test session is connected, after the NPC messages of each step:
   - `story.flags{set, clear}`: the diff of `save.flags` (flags now true / no longer true) every 10
     logic steps, on the step a script ends (the main runner stops) and on a map load (many paths
     write `save.flags` directly, so there is no event to follow);
   - `story.toggles{map, obj, visible}`: every change in an object's **effective** visibility --
     the engine's own `objectVisible` over its record's `hidden` default, `save.objectToggles`,
     items taken and beaten statics -- on the same beat; `visible` back at the default means the
     object is at its default again. `obj` is the map object's name (the export's, and the story
     cell's `obj`);
   - `story.blocks{map, changes[{bx, by, block}]}`: the current map's live blocks. On every map load
     (warps, seam crossings, same-map warps and reloads: a new `ow.npcs` table) every block that
     differs from the map data the world was built from -- or, for a same-map reload, every block
     that changed since, so regrown trees go out too; then every change while the player stays,
     checked every step. A client drops a map's live blocks when the player leaves it (the map data
     holds again there, as Cut trees grow back on the next load). The map data is copied at
     `mods.loaded`, before any map loads (a hot reload's Data is reloaded from disk first); a map
     the copy lacks is read from the dataset view;
   - `story.lit{map, lit}`: the dark maps Flash lit -- all of them while `save.flashLit` holds,
     since the engine keeps the light across dark floors and drops it on any other map.
   The snapshot holds the same state in full: `toggles` (every non-default effective visibility,
   all maps), `blocks` (the current map's differing blocks) and `lit`; the diffs start from it.
   Every block change must be a known one: a site of `export/kb_swapsites.lua`'s audited table (its
   closed or open block; a card-key door also the CARD KEY's open block), a Cut the engine recorded
   (`ow.cutBlocks`, cut tall grass included), or the map data again. Anything else is still
   reported, and counted (`storyUnknownBlocks`) and logged as the error code `story.unknown_block`
   with the map's symbol. MG9: an adapter with sites of its own (Gen 2's `engine.swapSites`: Cut
   trees, Whirlpools, the blocks its map scripts change, the bedroom's decorations) is checked
   against those, and its Cut record is the World's own record of the visit's edits.
23. **Field moves** (`act/kb_field.lua`, M8): `act.field{action, x?, y?, dest?, item?}` waits for the
   player to stand (a step in flight lands first), turns to `x`/`y` when given (cut, surf, fish: an
   adjacent cell), and has the hidden game perform the move as if it was chosen from the party menu
   or the bag (kb_engine's `fieldAction`: the engine's own entry points behind the checks the engine
   makes): `cut` (the tree or grass in front), `surf` (onto the water in front, or off it: kind
   `dismount`), `strength` (on; a boulder is then pushed by walking into it twice with
   `move.step`, the engine's own push), `flash` (only on a dark map), `dig` (in a dungeon;
   **with `item: "ESCAPE_ROPE"` the bag's Escape Rope instead**, kind `rope`, the rope used up),
   `teleport` (outside), `bicycle` (on or off: kinds `bike_on`, `bike_off`), `fish` (the rod
   `item`, else the best rod in the bag), `fly` (to `dest`, a town the Town Map's fly list offers;
   without one the native fly list opens and arrives as a list UI, kind `fly_list`). Eligibility is
   the engine's: a shadow-party member knows the move, its badge is in the bag (`constants.hmBadges`),
   then `partyKnows` itself (which a mod's `fieldmove.eligibility` hook may answer). Every request
   gets one `act.result`: `ok` with the kind, or a reason: `not_ingame`, `not_idle`, `not_adjacent`,
   `no_move`, `no_badge`, `no_target` (nothing to cut, no water, no such fly town), `not_here` (the
   wrong place: indoors, outdoors, not dark, no riding here, the Cycling Road, a current), `no_item`,
   `already` (Strength is on), `busy` (one request at a time), `failed`.
24. **Static encounters and direct battles** (M8 wave 2, kb_engine's "direct battles, native
   catches, the daycare, renames"). On Red/Blue every static Pokemon object (the Power Plant's
   Voltorbs and Electrodes, the birds, Mewtwo) has a hand-ported talk script ending in
   `static_battle`, and Snorlax's wake-up is one too: M5's script path holds them, as
   `battle.start{kind:"static", wild{dex, level, x, y}}` with the object's cell, and the engine's own
   `static_battle` applies the result (caught, beaten or fled: its beat flag and the object hidden
   for good; lost: per Gen 1). An aborted `static_battle` whose script hid its object first (Snorlax)
   shows it again, so the Poke Flute can wake it once more. Three battles never pass the M5 seams --
   their code pushes the battle itself (`OverworldState:pushBattle`) from a text box's continuation:
   a static object without a talk script (talkTo's own branch), the ghost Marowak (a step trigger)
   and Giovanni in the Rocket Hideout (a talk script). A text box pushed during a talk (the
   `world.talk` hook) or in the logic step of a completed step (`world.stepped`) gets a new
   continuation that runs the native one with `pushBattle` shadowed for that one call (put back at
   once, error or not); a battle pushed then that isn't native (the unidentified ghost, Safari, the
   old man's demo stay native, P9), isn't an engageTrainer battle (those went through
   `trainer.before_battle` already) and that the protocol can express is held for Cobblemon: kind
   `static` (catch false when the engine marks it uncatchable: the Marowak) or `scripted` (Giovanni:
   his team, class and party index; his armed loss line shown with its tag after a win). The result
   ends it through the battle's own `onFinish`, as the battle screen's exit would; `aborted` ends it
   as a flight (nothing won, the object or trigger stays).
25. **Battle music (P22):** while a battle Cobblemon fights holds the hidden game, the hidden game
   plays the Gen 1 theme that battle would have had (`Music.playBattle` with the engine's own rule:
   `final` for the Champion, `gym` for a badge fight or Lance, `trainer`, `wild`; a direct battle plays
   its own `playBattleTheme`); a win with texts to show plays the victory theme under them; every
   result brings the map's song back (also after an abort). Cobblemon's own battle themes are the
   Minecraft side's to silence.
26. **Native catches, trades, the Name Rater, the daycare, the Hall of Fame** (`act/kb_party.lua`):
   a Pokemon caught in a battle the hidden game fought itself (`pokemon.caught`: the Safari Zone, or
   any wild battle without a client; never the old man's demo) becomes a gift with source
   `native_catch` once that battle has left the stack (its nickname included; a box catch is taken out
   of the Gen 1 box). An in-game trade (`Commands.trade` through `script.command`, a sync waiting
   meanwhile) sends `party.take{uid, reason:"trade"}` for the member sent and `party.give{source:
   "trade", nick, ot}` for the one received; until a sync confirms the take, that uid never comes back
   into the hidden party. A nickname of a member with a uid that changes outside a sync (only the Name
   Rater does that in Gen 1) goes out as `party.rename{uid, nick}` (empty when cleared), queued while
   no client is connected. The daycare: a member with a uid boarded is `daycare.deposit{uid}` (kept
   out of syncs while boarded, its uid kept in `mod.save` for CONTINUE); back in the party it is
   `daycare.withdraw{uid, steps}` with the steps walked meanwhile (the exp it gained there over the
   engine's exp per step), and it stays in the hidden party until a sync names it. Takes, deposits and
   withdrawals go out again after every snapshot until a sync confirms them (the gifts' limits for
   giving up). The Hall of Fame (`record_hall_of_fame`) heals the hidden party, so Cobblemon's heals
   too (`party.heal`); its save is a `save.begin{menu}` (M7).
27. **Online save pairing** (`act/kb_pairing.lua`, M9, PLAN section 4.1 "Saving and sessions",
   DESIGN section 8 "Saving"): each multiplayer world gets its own save slot in the hidden game,
   separate from the offline one, switched to **at runtime** through the engine's own slot API
   (kb_engine's "save slots, online pairing" seams):
   - `session.bind{world, multiplayer:true}`: the world's slot, created on its first bind (a new
     empty slot, labelled `MULTIPLAYER <8 hex>` in Gen1Recomp's launcher); the active slot, when it
     isn't a world's, is remembered as **home** (the offline slot). `multiplayer:false`: the current
     slot as before M9, unless the current slot is a world's: then home (a new empty slot if there is
     none);
   - the switch, when that isn't the active slot: **at the title** at once (an open title menu is
     rebuilt, its CONTINUE row was the old slot's); a **New Game never saved** goes on into the target
     as it is when the target is empty (its first save lands there), else it waits (reason
     `new_game`) for the title; a **saved game** is first saved into its own slot (the engine's F1
     gate; never announced as `save.begin`), then the slot switches and the game returns to the title
     (the player picks CONTINUE or NEW GAME there, from Minecraft); while the gate is closed it waits
     (reason `busy`; a refused save retries after 5 s, reason `save_failed`);
   - while a switch waits, one `log` warning says why (`session.switch_waiting reason=<r> slot=<n>`),
     Cobblemon's syncs for the new world are held, `bag.use` is refused (`error session.pending`) and
     the old game's saves aren't announced; a newer bind replaces it; a dropped connection cancels it;
   - a bind that switches drops what Minecraft sent before it (the previous world's `party.sync` and
     `dex.sync`, waiting or kept to apply again after New Game/CONTINUE); the switch drops what the old
     game still had for Minecraft (unconfirmed gifts, party operations, heals, renames, item uses,
     native catches, a save to announce); a New Game moved as it is keeps its own and sends its gifts,
     operations and bag again;
   - `session.bound{slot}` goes out once the hidden game is on the slot. Refusals: `error` with
     `session.no_game`, `session.no_slots` (no slot API, or a cart is active; singleplayer is still
     answered), `session.no_slot` (a slot can't be created or made active, or its number is above
     255), `session.store_failed` (the pairing can't be written: the slot just created is deleted
     again), `session.failed`;
   - **M10:** `session.bound.switched` says how the hidden game got there: `none` (it already was),
     `title` (moved at the title), `saved` (the running game saved into its own slot first, then the
     title), `moved` (a New Game never saved went on into the empty slot as it is); a bind that
     starts waiting sends `session.waiting{slot, reason}` (`re` = the bind's seq), again whenever the
     reason changes (`busy`, `new_game`, `save_failed`); the `session.switch_waiting` log line stays
     for diagnostics (`pairingWaitingSent` counts the messages);
   - **the pairing file** lives in `mod.cache` (installation-wide, never in a slot's own save) as
     `<install>/mod_cache/gen1-bridge/session/pairings.<game>.json`, one per game since MG1 (one
     hidden process per game, each writing only its own file: `MULTI_GAME_PLAN.md` §4.3; written
     through a `.tmp` copy, kept with a `.bak`):
     `{"format":1,"games":{"<game>":{"home":"slot1","worlds":{"<world id>":{"slot":"slot2"}}}}}`.
     The legacy install-wide `session/pairings.json` (every game's entries in one file) is read once,
     when the game's own file doesn't exist yet, for this game's entry (`pairingStoreLegacy`); it is
     never rewritten or deleted.
     A launcher can read it and start Gen1Recomp on a world's slot with `--game=<game>
     --slot=<slot id>` (the id form: a bare number is a 1-based list index); the runtime switch makes
     that unnecessary. A slot deleted in Gen1Recomp's launcher comes back (empty, labelled again) on
     the world's next bind; a slot id handed out again drops the stale pairing.
   `debug.status` adds `pairing*` (binds, created, reused, switches by kind, waits by reason,
   `pairingPending`, `pairingWorlds`, `pairingHome`, the file's writes and failures) and
   `saveSwitchWrites`; the exports add `slot` (the active slot's number), `pairingPending`, and for
   test drivers `pairedSlot(world)`, `homeSlot()` and `forgetWorld(world)`.
   **Host version exclusives:** the world is built on the host from the host's export; a guest whose
   hidden game is the other version needs nothing more: its grass and cave rolls are suppressed (the
   host's habitat pools spawn), surfing and fishing are rolled by its own hidden game from tables that
   are the same in Red and Blue, its pack is built from its own export against the host's slot map,
   and its story (Game Corner prizes, title, naming presets) is its own. Pairings are kept per game
   version, so one world played with Red and with Blue gets one slot in each.
28. **Its own cost** (`lib/kb_perf.lua`, M10, PLAN "gen1-bridge per-step budget under 1 ms on
   average"): a cycle runs from one `input.step` to the next -- the step itself and every hook and
   event handler of ours the engine runs before the next one, timed with `love.timer.getTime`; the
   engine's own work inside our wrappers (the `nextFn` they call) is left out, and so is a call that
   spans a cycle (a script coroutine that yields inside a hook). The draw-phase hooks
   (`render.output_enabled`, `render.output`: the mirror's capture) are counted apart, per drawn
   frame. `debug.status`: `perfSteps`, `perfAvgUs`, `perfMaxUs`, `perfOver1ms`, `perfTotalUs`,
   `perfLastUs`, `perfFrames` (frames drawn), `perfDrawAvgUs`, `perfDrawMaxUs`; the exports publish
   the first six every 30 steps, and drivers start them over with `perfReset()`. Measured on the test
   install over a busy scenario (Red's house, Pallet Town with its NPCs, a sign, the start menu, the
   trainer card, the mirror subscribed at 30 fps throughout): **146 and 203 µs per logic step on
   average** in two runs, 2.5 ms at most (1 and 6 of about 2100 steps over 1 ms: map loads and
   snapshots); the mirror's capture about 0.73 ms per drawn frame on average (1.4 ms per capture,
   30 a second).
29. **Running hidden** (M10, DESIGN section 9; kb_engine "reliability (M10)"): the engine's loop caps
   a window that isn't visible at 10 frames a second and catches up at most about 2 logic steps a
   frame -- a third of the speed -- and doesn't draw it (so the mirror would get no frames). Measured
   on Windows: LÖVE's `isVisible` stays true for a **minimised** window, so minimising never slowed
   anything (60 logic steps a second); a **hidden** window (`ShowWindow SW_HIDE`, say by a launcher)
   drops to 20. While a client or test session is connected, gen1-bridge's wrapper on
   `love.window.isVisible` answers true for a window that isn't shown, so the loop keeps its normal
   cap and draws (60 steps and 60 frames a second, measured); each faked frame is paced to at most 60
   a second (a hidden window's present doesn't wait). A **minimised** window's frames are paced to 60
   a second too while a client is connected: the loop runs its normal cap for it, but its present
   returns at once, so it spun at 170-350 drawn frames a second, taking CPU and GPU time from
   Minecraft (measured: 60 frames and 60 logic steps a second with a client; without one, the
   engine's own behaviour, unchanged). The wrapper is installed once into a process-lifetime slot on
   the Game object, with the original function, answers only while every live step refreshes its
   one-second keep-alive, and is taken out when the link drops and on a hot reload (the next
   instance's takeover). `debug.status`: `hiddenActive`, `hiddenVisible` and `hiddenMinimized` (the
   window's own answers), `hiddenFaked`, `hiddenMinimizedFrames`, `hiddenPaced`, `hiddenInstalls`,
   `hiddenRemovals`.
30. **A dropped client** (M10 crash recovery, gen1-bridge's side): every hold is let go at once -- the
   step driver's held directions and the Cycling Road brake, the UI answers and `act.button` holds,
   the interaction and field requests, Bill's PC hold, a waiting slot switch, the mirror subscription,
   the hidden window's keep-alive -- so the hidden game is never stuck waiting for Minecraft; only a
   battle waiting for Cobblemon stays pending for the next client (its `battle.start` goes out again
   after the next snapshot). On a reconnect: `hello.ok`, `snapshot`, the open UIs again (their
   current page, line or cursor follows as `ui.update`), then, once the client binds,
   `session.bound`, whatever was queued (`party.*`, `save.begin`) and `save.loaded`.
31. **Cave ladders and holes** (`act/kb_climb.lua`, docs/specs/CAVES_3D_PLAN.md §3.6, H5): the player climbs or
   falls through a cave's shaft in Minecraft while the hidden player waits on or next to its near end; at the far end
   `warp.take{from, to, kind}` queues the engine's own `warp` command (as the Wild Routes clearing does), answered by
   `warp.taken{ok, reason?}`: only while idle (`mode`), on `from`'s map (`map`), within a cell of `from` (`far`), and
   with no script running (`busy`). The warp observer then reports that warp with kind `stairs` or `hole` and the
   take's `re`.

### Where the export's data comes from

- **First choice:** `mod.datasets:open(<game>)`, the engine's read-only view of the imported cache.
  It hands out detached copies, so the live tables the game changes during play (Cut, card-key
  doors, elevators) never leak into the world. It is the pristine import, without other mods'
  changes.
- **Fallback (PLAN §8):** when the view doesn't open at `mods.loaded`, the live `Data` is
  deep-copied right then, before any map has loaded. If the view opens but reads nothing at export
  time, the live `Data` is copied then, but only while no map has been loaded in this instance;
  otherwise the export fails with `error export_failed`.
- On v0.3.85's portable build the view works (and v0.3.49's did) (the e2e test reports which path ran). Each record
  read through it costs about 5 ms, because the view re-checks every decoded cache module on each
  read (`src/mods/DatasetViews.lua:209-243`), so a full build takes about 5 s of CPU, spread over
  roughly 500 steps, with a couple of one-off hitches of up to about 0.2 s while the engine decodes
  a cache module.
- Every engine question (per-cell flags through `Map.new`, the neighbour layout through
  `computeNeighbors`, image bytes through `Assets.imageData`, tile animations, spinner tiles) goes
  through `lib/kb_engine.lua`. Anything that can't be exported correctly is left out and counted
  under a warning code, logged with its count when the build ends.
- **Two engine queries for the voxel plan** (docs/specs/VOXEL_BLOCKS_PLAN.md §4.6), both engine internals in
  `lib/kb_engine.lua`, re-checked on every Gen1Recomp pin change: `engine.borderBlockFor(def)` wraps
  `TileRenderer.borderBlockFor` and gives each map's `borderDrawn` (the block the engine really draws around it;
  the map's own `border` when the engine lacks the function), and `engine.animatedTiles(tileset)` gives each
  tileset's `animated` tiles as data (the tileset's own `animatedTiles`, else the renderer's defaults). The
  field's `display` (the void-fill option and whether it is the default) is decided in `export/kb_export.lua`, so
  no engine constant is copied into Java.

Warnings and errors from `kb_log` are also sent to the client as `log` messages (at most 5 a
second). Logs only ever show codes, engine symbols, counts, lengths and short hashes.

**The port** is the mod option `port` ("BRIDGE PORT", default 27151): in game, F10 opens the mod
manager, where gen1-bridge's options page has it (left/right step it by one; a change applies at
once, through the engine's `mod.options_changed`); the Gen1Recomp launcher's mod settings edit the
same value before a boot. Test drivers move it with `game.mods.exports["gen1-bridge"].configure({ port
= N })`.

**A second instance on the same PC** (M9's play-test: two players, two hidden games) should be a
**second install** (its own portable folder: its own saves, options and pairing file; two instances
of one install would share the slot registry and the pairing file). Give it its own port: set BRIDGE
PORT to 27152 in that install, and point that player's Minecraft at it
(`<gameDir>/config/kantobridge-client.json` `{"port": 27152}`, or `-Dkantobridge.port=27152`). If you
forget, the fallback still keeps the two apart: the second instance waits 3 s for 27151, then takes
the first free port of 27152 … 27159 and logs it (`net.listening port=27152 configured=27151
fallback=true`); its Minecraft client must then be pointed at that port.

**Hot reload (F5) and returning to the launcher:** each instance leaves a "close my sockets" function
on the Game object; the next instance calls it on `game.ready`, before it binds, so the port and the
client are released at once.

## The engine adapter interface (MG2)

`lib/kb_engine.lua` loads one adapter per Gen1Recomp engine (`Engine.ADAPTERS`: `gen1` →
`lib/engine/gen1.lua`; `game3` has `lib/engine/game3.lua` since MG7, `gs` and `crystal` `lib/engine/gen2.lua` since MG9; any other engine gets the stub) and the core calls
it through the groups below (`Engine.GROUPS`, docs/specs/MULTI_GAME_PLAN.md Appendix B). An adapter
implements a group whole or leaves it out; `Engine.missingGroups(adapter)` names the groups it lacks
entirely, logged once at `game.ready` as `engine.groups` and counted in `debug.status`
(`engineGroupsMissing`; the names and the engine id are in the exports, `engineGroupsMissing`,
`engineId`, `engineAdapter`). The `link` groups are what a game needs to be followed at all; the rest
are optional (an adapter without `export` makes the builder role fail its export, one without
`routes` registers no zones, and so on).

| Group | Serves | Functions (the adapter's table) |
|---|---|---|
| `boot` | link | `setGame game takeOver version checkPin checkRequired gameVersion engineId family hasAdapter logicStep save top overworld classifyState darkMaps badgeItems observe npcs emote unionRoomPatched unionRoomOff` |
| `interaction` | link | `interactedFacts cellInMap counterCell` |
| `ui` | link | `uiClass uiStack uiOpenFacts uiCursor uiItems uiMoneyFooter uiSetIndex uiSetQty uiSetGlyphs` |
| `movement` | link | `setFacing buttonDown playerVerdict warpDeparture displayPinned pinDisplay` |
| `story` | link | `capturePristine pristineBlocks pristineCaptured pristineBlockAt storyMap liveBlocks liveBlockChanges cutRecords cutSwaps swapField storyToggles fieldAction` |
| `saving` | link | `nurseHealed armCenterSave centerSaveArmed centerSaveStep savingReason queueSave takeSave queuedSaveSerial saveSlot pcItems pcPending pcSent pcClose pcHeld pcCounts` |
| `sessions` | link | `slotState resolveActiveSlot slotRegistered slotHasSave labelSlot createSlot deleteSlot activateSlot playthrough titleMenuOpen switchGateOpen switchSave toTitle dropPlaythrough` |
| `reliability` | link | `metaOf persistent runHidden hiddenStats` |
| `party` | party, battle | `bridgeToken nextSerial dexOf speciesOfDex moveNumOf moveIdOfNum trainerTeam battlePending battleSent battleStale trainerBeforeBattle battleFinish ops opSent opSynced opDone excludedUids scriptCommand gifts giftSent giftSynced giftDone queueHeal releaseHeals takeHeals uidOf partyUids adoptUids partySafe applyParty bridgeCounts` |
| `items` | items | `attachBridge encounterRoll encounterFishing fishPushed nativeBattle encounterCounts inGame applyDex bagCounts bagRemove tmWatchStep itemUse takeItemUses itemCounts` |
| `battles` | battle, PC | `worldTalk directStepped directPushed nativeCaught releaseCatches daycareStep daycareUid partyNicks queueRename takeRenames contentCounts` |
| `export` | export | `openDataset datasetSource liveSource worldShowing liveSourceFromGame mapQuery isOutside tileProbe shoreTiles cutTreeTiles spinnerTiles animatedTiles borderBlockFor voidFill modDataFile animationImages computeNeighbors paletteLookup imagePng` |
| `routes` | routes | `inventoryCount writeTrainerParty scriptBattleResult blackout` |
| `region` | region | `holdAway releaseAway awayHeld startFromTitle progressMarkers` |

Each adapter also lists the engine modules, functions and fields it depends on (`Gen1.REQUIRES`),
checked once at `game.ready` (`engine.missing`), and gets the pin as `opts.checked`.

## Files

| File | Purpose |
|---|---|
| `manifest.json` | Read by Gen1Recomp's loader. `network` unlocks `require("socket")` (LÖVE's bundled LuaSocket) inside the mod sandbox; `engine_internals` discloses that the mod requires engine modules (`src.*`). Gen1Recomp only displays permissions; it never prompts. |
| `main.lua` | Entry point. Loads the modules, defines the `port` option, wires the `input.step`, `movement.collision`, `render.output_enabled`, `render.output`, `script.command`, `trainer.before_battle`, `encounter.roll`, `encounter.fishing`, `item.use` and `ui.pc.items` hooks and the `mods.loaded` (M8: the copy of the map data the story compares with), `game.ready`, `save.created`, `save.loaded`, `save.writing`, `player.warped`, `map.entered`, `world.blacked_out`, `screen.pushed`/`popped` and `world.interacted` events, routes `move.*`, `act.*`, `ui.*`, `mirror.*`, `battle.result`, `party.sync`, `dex.sync`, `bag.use`, `save.done`, `pc.closed`, `session.bind` and (M8) `act.field` to their modules, (M8 wave 2) wires the `world.talk` hook and the `world.stepped` and `pokemon.caught` events (the direct-battle seam, native catches), keeps one tap per logic step, orders each step's messages (`player.state`, `warp`, `player.pos`/`player.blocked`, `act.result`, `npc.*`/`emote`, (M8) `story.blocks`/`story.lit`/`story.flags`/`story.toggles`, `ui.close`/`open`/`update`, `party.give`/`party.heal`/`party.item`, `bag.sync`, `save.begin`, `pc.open`, `battle.native`/`battle.start`, `mirror.frame`; a blackout's `party.heal` after its warp's `arrive`), and publishes a status table in `mod.exports` (versions, counts, flags; from M7 also `saves`, `centerSaves`, `sessionBinds`, `pcHeld`) for test drivers. `debug.status` adds the battle, party, bag, encounter, item, save (`save*`) and PC (`pc*`) counters, the Pokédex counts, `money`, `bagTotal`/`bagKinds`, `defeated` (trainers beaten) and the typed names' lengths and short hashes; M8 adds `story*` (messages, map loads, block changes, `storyUnknownBlocks`, `storySites`, `storyPristine`) and `field*` (requests, ok, failed, busy, `fieldKind_<kind>`); M8 wave 2 adds `direct*` (armed, taken, native, declined), `catches*`, `trades`, `tradeNoUid`, `deposits`, `withdraws`, `depositNoUid`, `opsPending`, `daycareHeld`, `music*` (battle, victory, failed), `badges` and `coins`; M8 wave 3 adds `trainerStarts`, `trainerStartsFailed` (wins applied through the encounter's own start), `lossLines` and `lossLineSounds` (loss lines shown after a win, and those with a jingle). M9: `session.bind` goes to `act/kb_pairing.lua`, whose slot switch runs first in each step (before anything looks at the game; its `session.bound` or `error` goes first), `bag.use` is refused while a switch waits, `save.loaded` tells the pairing a game was loaded, `mod.options_changed` (the F10 manager) moves the port at once; `debug.status` adds `portConfigured`, `portFallback`, `pairing*` and `saveSwitchWrites`; the exports add `port` (the port listened on), `portConfigured`, `portFallback`, `slot` (the active save slot), `pairingPending`, and for drivers `pairedSlot(world)`, `homeSlot()`, `forgetWorld(world)`. M10: every hook and event handler timed into `lib/kb_perf.lua` (`perf.hook` leaves the engine's `nextFn` out; the draw-phase hooks counted per frame), `perfReset()` for drivers and `perf*` in the exports every 30 steps; `save.loaded` after `save.begin` in each step (`party.*`, `bag.sync`, `save.begin`, `save.loaded`, `pc.open`, `battle.*`); New Game and CONTINUE handed to kb_save (`created`, `loadedSave`); the window wrapper refreshed every step while a client or test session is connected and released with the takeover; `hello.ok.tested`; `debug.status` adds `perf*`, `hidden*`, the session's hello counts (`session*`), `mirrorPeakFps`, `mirrorCapped`, `pcStale`, `pairingWaitingSent` and the save pair counts (`savePairs`, `saveOps`, `saveFresh`, `saveLoadedSent`, ...). |
| `lib/kb_json.lua` | Our own strict, deterministic JSON codec: sorted keys, `{}`/`[]`/null kept apart, strict parsing, errors that never quote the input. |
| `lib/kb_sha256.lua` | SHA-256, for the short hashes logs may show and for export hashes. |
| `lib/kb_log.lua` | Redacted logging: a code plus fields; any string that isn't id-shaped prints as `<str:LEN:SHA8>`. |
| `lib/kb_net.lua` | The non-blocking TCP listener: one client at a time, newline framing up to 4 MiB, a send queue that keeps unsent tails, `finish` (send what is queued, then close), port changes; M9: the fallback along a span of ports after the configured one when it stays busy (`portSpan`, `fallbackAfter`; `port`, `configuredPort`, `fallback`). |
| `lib/kb_proto.lua` | The message envelope (`type`, `seq`, `re`, `data`), dispatch, replies and `error` answers, with a gate and catalog-aware handling of unhandled types. |
| `lib/kb_session.lua` | One connection's session: hello first, handshake, heartbeat timeout, resync, debug messages, live streams, log forwarding. M10: the hello checked field by field (`protocol`, `role`, `malformed`), the 10-second hello timeout, a first line that isn't an envelope closing the connection, `hello.ok.tested` and one `engine.untested` warning per connection. MG1: `hello.ok.game` is the catalogue id of the running game (no coercion) with `family` and `compat`; `hello.reject{reason:"game"}` for a game the mod isn't for. |
| `lib/kb_perf.lua` | M10: gen1-bridge's own cost per logic step (our step and every hook and event handler of ours until the next step, the engine's work inside our wrappers left out) and per drawn frame (the draw-phase hooks); `perf*` in `debug.status`, `perfReset()` for drivers. |
| `lib/kb_catalog.lua` | **Generated** from `protocol/catalog.json` by `tools/lua/gen_catalog.py`: the message types and their legs. A test fails when it is stale. |
| `lib/kb_items.lua` | **Generated** from `protocol/items.json` by `tools/kb_protocol/catalog.py --write` (MG6): the canonical item catalogue, each item's class, the move a machine teaches and each family's symbol. `kb_bag` takes its synced and mirrored sets from it, the Gen 1 adapter its item table (`itemTable`, `itemClass`) for `bag.set`. A test fails when it is stale. |
| `lib/kb_games.lua` | **Generated** from `protocol/games.json` by `tools/kb_protocol/catalog.py --write` (MG1): the game catalogue, each Gen1Recomp game id's family, engine, generation, label, default region, compat profile, art set, adapter flag and story markers. `kb_session` names the game, its family and limits with it (and rejects a game the mod isn't for), `kb_export` names the index's game and the layout resource (the game's default region) with it. A test fails when it is stale. |
| `lib/kb_engine.lua` | **The engine adapter loader** (MG2, D1): picks `lib/engine/<engine>.lua` for the running game's engine (`GameVersion.engine`: `gen1`, `gs`, `crystal`, `game3`) and hands the core its table, or a **stub** for an engine without an adapter (every call answered as a game that isn't there, every hook wrapper passing through; every `hello` then gets `hello.reject{reason:"game"}`). Holds the pin (`Engine.CHECKED`, `Engine.TESTED`), `Engine.ADAPTERS`, the interface `Engine.GROUPS` (below) and `missingGroups()`. |
| `lib/engine/gen1.lua` | **The Gen 1 engine adapter: the only file that touches engine internals** (with the Gen 3 adapter, `lib/engine/game3.lua` and its parts in `lib/engine/game3/`, MG7). Each function cites the engine `file:line` it relies on (v0.3.85; `tools/pin_check.py` re-checks them at a pin change): the overworld and its player, the top state's class, NPCs, dark maps, badges, the options pin, the instance takeover slot, the movement internals (setting the facing, held buttons, the player's collision verdicts, warp departures, hop/boulder/departure facts), the M4 internals (the screen stack's classes and stable keys, each widget's fields and its ready test, the few writes before a confirming tap: a cursor, a quantity, a name's glyphs; NPC steps and scripted moves, the emotion bubble, `world.interacted`'s target, counter tiles), and the export's data sources and queries (dataset views, the live-Data copy, `Map.new` and its cell queries, `Map.isOutside`, `computeNeighbors`, tile animations, spinner tiles, `Assets.imageData` + PNG encoding, the SGB palette cascade, and the few engine constants with nothing to call: cut-tree and shore tiles), and the M5 seams: the `script.command` and `trainer.before_battle` bodies (the held battle on the runner's `waitingCheck`, `start_battle`'s resume path, engageTrainer's win effects, `continue({cancel})`, the result texts and prize), trainer teams (`Pokemon.movesAtLevel`, the copied boss-move table and trainer DVs, the rival's offset), `give_pokemon`'s gift capture, `heal_party`, the shadow party's rebuild (`Pokemon.new`, `Stats.calc`, `Growth.expForLevel`, `stampOT`) with its uid map, and the process-lifetime `kantoBridgeState` slot on the Game object; and the M6 seams: the `encounter.roll` and `encounter.fishing` bodies (the Safari region and ghost-map rules, REPEL's filter, `encounter.species`, the held surf/fish battle and its `afterBattle` ending, the cast box's continuation taken over on `screen.pushed`), the native special battle on the stack (`battleKind`), the Pokédex (`save.pokedex`), the bag (`save.inventory`, `Bag.remove`, key items), and the `item.use` body (the stone and candy tests, BagMenu's consume, row refresh and party-menu close, the TM/HM watch over MoveLearnMenu's slot), with the slot pointing at the current instance's bridge; and the M7 seams: the nurse's heal machine (`healAnim`, `lastHeal`), the center save (`Game:writeSave` behind `Game:quickSaveAllowed`, its reason during `save.writing`), the save queue, the active save slot (`SaveData.activeSlot`), the blackout's held heal, the map's song after a battle Cobblemon fought (`Music.restoreMap`), the Mart's money box and changing footers, and the `ui.pc.items` body (the box row by its source label, the access text's continuation, the hold state and its record); and the M8 seams: the copy of the map data at `mods.loaded` (blocks and objects; the dataset view for a map it lacks), the current map's live blocks (`ow.map.def.blocks`) and load serial (a new `ow.npcs` table), the engine's cut record (`ow.cutBlocks`) and `field.cutTreeSwaps`, the swap-site field tables through `FieldDefaults.fieldValue`, effective visibility through `OverworldState.objectVisible`, a Seafoam current's chain (where the player's scripted steps began), the escape spin's kind (the `teleportOut` table marked by `act.field` or the bag's ESCAPE ROPE), and `fieldAction`: WorldAPI's `acceptsMenuInput` test, the shadow party's moves and `constants.hmBadges`, `partyKnows`, and the party menu's and bag's own entry points (`useCutFieldMove`/`tryCut`, `useSurfFieldMove`/`trySurf`/`stopSurfing`, `useStrengthFieldMove`, `useFlashFieldMove`, `beginTeleportOut`, `Bag.remove`, `useBicycle`/`bikeAllowed`, `useFishingRod`, `flyTo` and `Screens.push` of the Town Map's fly list); and the M8 wave 2 seams: the `world.talk` body and the `world.stepped`/`screen.pushed` arms of the direct-battle seam (a text box's continuation run with `OverworldState.pushBattle` shadowed for one call; `battleKind`, `checkpointOrigin`, `noCatch`, `endBattleText`, the battle's own `onFinish`), the battle music (`Music.playBattle`/`playVictory`, `data.scripts.victories`' badge fights, `playBattleTheme`), the aborted `static_battle`'s `Commands.show_object`, `pokemon.caught`'s catches (the party or a box, released once the battle has left the stack), `Commands.trade` read from the party, the party operations (take, deposit, withdraw) with their exclusions and kept members, the daycare's `save.daycare` and `FieldDefaults.world("daycareExpPerStep")`, the nicknames for renames, and `record_hall_of_fame`'s heal; and the M8 wave 3 seam: engageTrainer's own start of a deferred map trainer's battle, run on a win (`continueBattle()` with `BattleState.newTrainer` stood in for and `OverworldState.pushBattle` diverted for that one call; the stand-in's `endBattleText`, `endBattleSound`, `endBattleSoundPage`, `rewardDialogueShown` and the encounter's own `onFinish`), and `TextBox.soundOpts` for a loss line's jingle; and the M9 seams: the save-slot API (`SaveData.activeSlot`, `setActiveSlot`, `createSlot`, `renameSlot`, `deleteSlot`, `refreshSlotResolution` and its legacy-save migration, `getCart`, the registry through `loadOptions`, the slot files on the persistence root), the live playthrough (a New Game without `meta.savedAt`), the title (`TitleState` and what is open over it, `Game:returnToTitle`), the save before a switch (`Game:writeSave` behind the F1 gate, reason `switch`) and dropping what the old game still had for Minecraft; and the M10 seams: `Engine.TESTED` (the engine version hello.ok names), a save's meta as plain values (`savedAt`, `sessionStart`: `SaveData.buildMeta` stamped before `save.writing`, `Game:writeSave`), the queued save's pair and serial (operations made after it wait for its `save.begin`) and whether it came from the live save table, gift serials, the PC hold's id, a named table in the process-lifetime slot for state that must outlive a hot reload (`persistent`), and running hidden: the wrapper on `love.window.isVisible` (reached through the sandbox's love facade, `src/mods/Sandbox.lua:112-152`; the loop's cap `main.lua:1739-1756`, `FixedStep.catchupLimit`) in the `kantoBridgeWindow` slot with its original function, keep-alive and frame pacing (a hidden window's faked frames and a minimised window's, `isMinimized`). Re-check them all when the Gen1Recomp pin changes. |
| `lib/engine/game3.lua`, `lib/engine/game3/*.lua` | **The Gen 3 engine adapter** (MG7, `docs/specs/GEN3_BRIDGE_PLAN.md`; Emerald, switched on in `protocol/games.json`): grows from the loader's stub (every function not written yet answers "no game" with the right type), one part per group: `base` (shared engine access), `boot` (identity, REQUIRES, the save view: Gen 1-shaped facts from the session), `observe` (facts, NPCs), `move` (the `Player.tryMove` tap for step verdicts, warp departures, interaction), `saves` (guarded saves, the nurse through `special HealPlayerParty`; the slots and the queue of announced saves are the shared `common/slots`, `common/savequeue`, MG9), `region` (the `kb_park` field lock, starts from the title), `common/reliability` (meta, token, running hidden; shared with gen2 since MG9), `ui` (G3B: the core's UI stack built from Gen 3's message box, choice, stack layers, battle and boot phase, Birch's speech included; `GEN3_BRIDGE_PLAN.md` §4.5 "As built"; `screenSize` 240×160 for the mirror), `story` (G3C: toggles `O<localId>` from every map's hide flags, the current map's changed cells for `story.cells`; §4.6 "As built"), `export` (G3D: `exportResources`, the `g3*` resources: maps in cells, pairs with flattened 8x8 quarters baked into RGBA pages on the CPU, sprites, the engine's own layout, encounters, names; §5 "As built"), `party` (G3F: the shadow party, gifts, heals, the Pokédex, renames; since 2026-10-15 also in-game trades as `party.take` + `party.give{trade}`, the two-member daycare's deposits and withdrawals with their uids kept in mod.save, and eggs, kept in the hidden game until they hatch into `party.give{egg}`), `battle`, `bag`, `routes`. |
| `lib/engine/gen2.lua`, `lib/engine/gen2/*.lua`, `lib/engine/common/*.lua` | **The Gen 2 engine adapter** (MG9, `docs/specs/GEN2_BRIDGE_PLAN.md`; one adapter for Gen1Recomp's `gs` and `crystal` engines, Crystal switched on in `protocol/games.json`): grows from the loader's stub as game3 does, one part per group: `base` (shared engine access, the `G2_` map-id prefix: Gen 2's Kanto uses Gen 1's map names, J4), `boot` (identity, REQUIRES, the Union Room guard, `mapOf` and the world facade the core's acts warp through, the new game's clock answered with the host's time through `intro.oak_speech.build`, the save view: numbered flags as `E<n>`, `KB_HALL_OF_FAME`, 16 badges), `observe` (facts with `chainsHeld`, the shared TextBox/ChoiceBox and `Gen2<Name>` screens, NPCs `O<index>`), `move` (Gen 1's `movement.collision` verdicts for the player's mover, `Player:scriptFace`, warp kinds from the COLL byte, interaction), `saves` (`Game2:writeSave` behind `quickSaveAllowed`, the nurse through `special HealParty` in `script.command`'s fifth argument), `region` (Gen 1's park: a hold state on the stack, which freezes the field step; starts from the title through `Game2:continueGame`/`newGame`), `ui` (G2B: the shared TextBox and ChoiceBox read as gen1 reads them, the gender choice, script menus as lists, the start menu without QUIT and MODS, the name pick and its keyboard as one naming UI, every clock screen answered with the host's time, Elm's picture and every other `Gen2<Name>` screen mirrored), `story` (G2C: the map data copied at `mods.loaded`; the current map's blocks against it, Gen 1's `story.blocks`; who stands where, the live masks on the current map and the flag and hour window elsewhere, `O<index>`; the swap sites: Cut trees, Whirlpools, the blocks the scripts a map reaches change, Crystal's whole-map swap and the bedroom's decorations, which `kb_story` checks every change against through `swapSites`); `common/` holds the parts shared with game3 (`slots`, `savequeue`, `reliability`). |
| `observe/kb_player.lua` | The mode classifier (`busy` while a Cobblemon battle holds the world) and the `player.state`/`player.pos` observer, working on plain facts from `kb_engine`: step starts, ledge hops (one message at the landing), syncs, and tags the step driver reads. M8: Seafoam currents as kind `current`. |
| `observe/kb_warp.lua` | The `warp` start/arrive messages and their kinds, from the warp events and the transition facts. |
| `act/kb_move.lua` | The step driver: `move.step` queue, facing first, holds through `mod.input` (released on the landing poll, re-pressed after an input reset, all released outside `idle`), outcomes (step, hop, push, warp, blocked) answered once per `n`, `move.face`, `move.hold`. |
| `observe/kb_snapshot.lua` | Builds `snapshot` from the facts and the save: position, state, flags, the story's effective toggles, live blocks and lit maps (M8), bag, money, coins, badges, the hidden game's party (with the Cobblemon uids it knows), the Pokédex, the current map's NPCs (shaped by `kb_npcs`). |
| `act/kb_battle.lua` | Battles fought in Cobblemon: the `battle.start` payload of a battle kb_engine holds (kind, trainer, team, wild, rules; wild kinds `static`, `surf`, `fish`; M8: direct battles as `static` or `scripted`, `catch` false for an uncatchable one), sending it and re-sending it after snapshots, `battle.result` (outcomes to the engine's results; errors `battle.unknown`, `bad_message`), and `battle.native` for the special battles that stay native. |
| `act/kb_party.lua` | Gifts (`party.give`, re-sent until a sync shows them, given up on after five syncs and two minutes), heals (`party.heal`), `party.sync` (checked, mapped to Gen 1 species and moves, matched to pending gifts by `give` or by species and level, applied when safe), the uids in `mod.save`, `dex.sync` (added to the Pokédex in game, again after New Game or CONTINUE) and `party.item` (the item uses kb_engine queued, sent once); M8 wave 2: native catches released as gifts, `party.take` for trades, `daycare.deposit`/`withdraw` (the boarded uid in `mod.save`), the confirmations by sync and the uids kept out of syncs, `party.rename` from the nickname watch. |
| `act/kb_save.lua` | Saving and sessions (M7): `save.writing` → `save.begin` (reason `menu` or `center`, the newest waiting while no client is connected; M9: the save before a slot switch never announced, nothing announced while a switch waits), the nurse's heal (Cobblemon's `party.heal`, then the center save), `save.done` (log only, counted), the bound world kept in `mod.save` once the pairing answers. M10: each save's pair written into its own mod data and announced as `save.begin.pair`/`at`; the running save's identity (fresh, pair, ops) in the process-lifetime slot; `save.loaded` after a bind and on entering the game; the operations counted as they first go out; the save before a slot switch keeping its world's pair. |
| `act/kb_pairing.lua` | M9: online save pairing (item 27): `session.bind` → the world's slot (created and labelled on its first bind) or, singleplayer, the current or home slot; the runtime switch (title, an unsaved New Game, a saved game saved first), waiting with its reason (M10: `session.waiting`), `session.bound{slot, switched}` once done; the pairing file in `mod.cache` (MG1: one per game, `session/pairings.<game>.json`, through `.tmp`, with `.bak`; the legacy `session/pairings.json` read once for the game's entry); the drivers' exports. |
| `act/kb_pc.lua` | Bill's PC (M7): `pc.open` once per hold kb_engine's `ui.pc.items` wrapper sets up, `pc.closed` ending it, a dropped link releasing it, counts. M10: a fresh id per `pc.open`, only the `pc.closed` naming it ending the hold (stale ids counted and ignored). |
| `observe/kb_bag.lua` | The bag for the ledger (MG6): every non-key catalogue item, the money and the projection stamp as `bag.sync` (after snapshots and on change, in game only); `bag.use` on the mirrored items (PLAN Appendix B's 29: a Cobblemon item and a battle-usable class) applied through kb_engine's `Bag.remove` (never below zero, mismatches counted, unmirrored items refused). |
| `act/kb_ledger.lua` | MG6: `bag.set`, the server's projection of the player's ledger into this save (the adapter's `bagSet`: the catalogue's non-key items replaced within 20 slots and 99 a stack, class order, the money written, key items and unknown items untouched), answered by `act.result{kind:"bag.set"}` with `too_many` and `left` for what did not fit, `not_ingame`, `busy`; the stamp into `mod.save "ledger"`. |
| `observe/kb_npcs.lua` | The NPC observer: `npc.set`/`add`/`remove`/`move`/`face` and `emote` from a per-step diff by id; `Npcs.shape`, the one place an NPC becomes a protocol entry. |
| `observe/kb_story.lua` | M8: the story diffs (`story.flags`, `story.toggles`, `story.blocks`, `story.lit`), the snapshot's story parts (`rebase`), and the check that every block change is a known one (`story.unknown_block`). |
| `observe/kb_ui.lua` | The UI observer: the stack's states as `ui.open`/`update`/`close` by kind, the protocol text markup (`Ui.markup`), re-announcing after a snapshot. |
| `observe/kb_mirror.lua` | The mirror: subscriptions, the fps cap, the capture inside the `render.output` observer, dedupe, the cost counters; M10: never more than 30 frames in any second (a sliding window), `peakFps`, `capped`. |
| `act/kb_uiact.lua` | UI answers (`ui.advance`, `ui.pick`, `ui.back`, `ui.qty`, `ui.name`) and `act.button`, applied as taps to the top state. |
| `act/kb_interact.lua` | `act.interact`: face and press A in one step, confirmed by `world.interacted`, one retry for a moving NPC. |
| `act/kb_field.lua` | M8: `act.field`: one request at a time, waiting for the player to stand, turning to a target cell, kb_engine's `fieldAction`, one `act.result` each (kinds and refusal reasons above). |
| `lib/kb_routes.lua` | Wild Routes (WR2, [docs/specs/WILD_ROUTES_PLAN.md](../docs/specs/WILD_ROUTES_PLAN.md) §4.1-4.2): the route list (`data/routes.json`, checked entry by entry with reason codes) and, from the entry chunk before the engine's merge, each zone's registrations through the public content API: the route map `KB_ROUTE_<ID>` (one block of the clearing's floor, 2×2 cells), the map step scripts that lead out (the clearing's last cells) and back (A), the `KB_<CLASS>` trainer records, the route map's song, and last the town's patched `blocks`, after a signature check asked of `kb_engine.mapQuery`; every registration guarded, a failed one switching the zone off. Also the export's view (the patched blocks, the `routes` resource). WR4: `closedMove`, the route map's closed lower row (only A and x1 are walkable, as in the zone), applied in main.lua's `movement.collision` wrapper as a `tile` refusal and counted as `routesClosed`. |
| `act/kb_route.lua` | Wild Routes (WR2): `route.give` (the engine's `give_item` in a queued script, the bag's count before and after), `route.battle` (the zone's `KB_<CLASS>` party written, then a queued `start_battle trainer` through the bridge's script-battle path; answered with the outcome), `route.blackout` (the engine's loss handling); one at a time, waiting for an idle world; progress (balls taken, trainers' wins) in `mod.save "routes"`, in the snapshot and as `route.progress`. |
| `act/kb_region.lua` | MG2: the region acts `region.park` (the hold: input and steps refused, scripts and NPCs frozen, the music fading; the parked cell written into `mod.save "region"`, shown by `snapshot.away`), `region.resume` (the hold popped, the marker cleared, a queued `warp` onto one of our route maps or the saved cell only, answered on arrival) and `region.start` (CONTINUE or NEW GAME from the title, answered once the overworld is up), each `act.result` with its kind, `detail` and reasons; a link drop pops the hold and leaves the marker. |
| `observe/kb_progress.lua` | MG2: `game.status` (the playthrough as the pairing judges it, `ingame`, `started`, the badge count, the game profile's markers resolved by the adapter, the save's player name while started, the active slot) after every snapshot, after `session.bound`, on New Game and CONTINUE, and whenever it changes (compared every 10 steps). |
| `data/routes.json` | Wild Routes: the zone list. Ids, map and class symbols, cell coordinates, counts and our own text only; Viridian City's forest zone waits for its spot (`x`, `y`, `facing` null until chosen). |
| `export/kb_export.lua` | The export service: roles, the request queue, the budgeted build coroutine, the cache, budgeted replies, and every resource's shaping (map records, surface, parents by BFS, the `LAST_MAP` memory, palettes, tile classes, images, sprites, encounters, field tables, names, layout). |
| `export/kb_cells.lua` | The per-cell flag integers of one map, from `kb_engine`'s map queries plus `field.ledges`, `field.spinners`, the warps and signs. |
| `export/kb_images.lua` | Base64, image ids from asset paths (`<folder>.<name>`), and the image and sprite resources. |
| `export/kb_swapsites.lua` | The audited table of every runtime block change the engine makes (M8; the file's header lists each engine site with its `file:line`): cut trees (from the cells), card-key doors and lift gates (`field.cardKeyDoors`), the Game Corner poster, the Vermilion Gym door, the Victory Road barriers (kind `boulder`), the Elite Four exits, Lance's doorway, the Pokemon Mansion switches, the Cinnabar Gym gates and the S.S. Anne's departure (curated rows). `before` is the closed state, `after` the open one; the map data holds one of the two (except AGATHAS_ROOM's exit, which the engine overwrites on every entry). `runtimeIndex`/`allows`: the runtime check the story uses; MG9: `indexSites` indexes an adapter's own sites (Gen 2's `swapSites`) the same way, and the kind `whirlpool` is Gen 2's. |
| `mod.card` | Sharing metadata for the mod manager and gallery. The loader never reads it. |

Rules for this folder:

- Modules are loaded with `load(mod:read(rel), "@" .. rel)()`, which compiles them into the mod's
  sandbox. Never `require` our own files.
- File names start with `kb_`, and no file may mention the engine's generated-data folders by path
  (modkit's MK301 and MK305 checks).
- Only the engine adapters, `lib/engine/**` (`gen1.lua`; `game3.lua` and its parts for Emerald, MG7; `gen2.lua` and its parts for Crystal, MG9; the shared `common/` parts), reach into the engine; everything else gets
  plain values from the adapter table `lib/kb_engine.lua` loads (MG2, D1).
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

**Minimising Gen1Recomp's window is fine** (M10, measured on Windows with the test install): LÖVE
still reports a minimised window as visible, so the game keeps its full 60 logic steps a second, with
or without Minecraft connected; while Minecraft is connected gen1-bridge also paces a minimised
window to 60 drawn frames a second (on its own the loop draws hundreds). A window **hidden** altogether (not just minimised, e.g. by a
launcher) drops to a third of its speed when nothing is connected; while Minecraft is connected,
gen1-bridge keeps it at full speed (item 29).

To remove the link, use `rmdir local\gen1recomp\mods\gen1-bridge` in `cmd`. Never use `rm -rf`
through it, which can delete this folder's contents.
