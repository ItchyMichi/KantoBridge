# fabric-bridge

The Minecraft half of Kanto Bridge: a Fabric mod (Java) for Minecraft 1.21.1 with Cobblemon 1.8.1.
Mod id `kantobridge`, package `dev.kantobridge`.

**Status: M8 wave 2, the rest of Kanto (Java side): field moves from Minecraft, native special battles on the mirror,
Safari catches, trades and the Name Rater, the daycare and Gen 1 battle music (below), on top of wave 1's per-player
story cells, closed art, surfing, Flash and the full build's fixes.** On top of M0's protocol codec, relay
skeleton, tile blocks, static world data and test harnesses, M1's link to gen1-bridge, M2's world export, builder ("Create
Kanto world"), per-player story-cell and ledge collision and WorldIndex sync, M3's walking and warps in step with the
hidden Gen 1 player under Kanto's rules, M4's NPC puppets and our screens for the hidden game's UIs, M5's trainer,
rival and scripted battles against a hidden proxy NPC with the party flowing both ways, and M6's habitat pools, wild
encounters, Pokédex and bag mirror: Bill's PC now opens Cobblemon's PC, a save in the hidden game saves the player's
Pokémon and the world too, each world has an id for pairing the hidden game's save slot, and a lost battle's blackout
fades slowly to the last Pokémon Center (below); gameplay arrives milestone by milestone
([`docs/PLAN.md`](../docs/PLAN.md), [`docs/PROGRESS.md`](../docs/PROGRESS.md)).

Generated with Fabric's official template generator (`@fabricmc/cli init`, CC0), using Mojang mappings
and split client/common sources. It was then changed as follows:

- Parchment is layered on the Mojang mappings.
- Cobblemon and Fabric Language Kotlin were added as dependencies.
- Loom is pinned to 1.17.21, the newest Loom that runs on Java 21.
- The example mixins, the template's CC0 `LICENSE` and its nested CI workflow were removed.

All versions live in `gradle.properties` and are recorded, with their sources, in
[`docs/VERSIONS.md`](../docs/VERSIONS.md).

## Requirements

**JDK 21.** Gradle itself must run on Java 21, so point `JAVA_HOME` at a JDK 21 for the build. This
machine's default Java is 17. In Git Bash:

```sh
cd fabric-bridge
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot" ./gradlew build
```

In IntelliJ, set *Settings → Build Tools → Gradle → Gradle JVM* to the JDK 21.

## Layout

| Path | Contents |
|---|---|
| `src/main/java/dev/kantobridge/` | Common code. `proto/` (strict JSON, `Envelope`, `Catalog`, `Redact`, `Ids`; `MsgTypes` is generated), `net/` (the `kantobridge:json` payload and the server `RelayService`), `block/` (tile blocks, `StoryCellBlock` and `StoryTileBlock` + block entity, `LedgeColliderBlock`), `story/` (`StoryState` attachment, `StoryStateMessages`, `StoryRules`, the `Activity` attachment), `entity/` (the client-only `PuppetEntity` type, registered on both sides), `world/` (the pure world model: `Scale`, `CellPos`, `Dir`, `Place`, `MapPlacement`, `WorldIndex`, `StoryCellTable`, `SlotMap`), `builder/` (the world builder, below, and its triggers: `HeadlessBuild`, the in-process `BuildRequest`/`BuildStatus`), `server/` (`WorldIndexSync`, `BuildHold`, `KantoGameRules`, `Rules`, `CobblemonSettings`, `WarpService`, `StoryStateService`, `FlashService`, `ActivityService`, `HabitatService`, `SaveService`, `SessionService`, `SessionRecord`), `battle/`, `pokemon/` and `bag/` (battles, Pokémon, Bill's PC, the Pokédex, the bag, the daycare and the battle music, below), `mixin/` (also `PokemonStoreManagerAccessor` and `PokemonEntityMixin`, below) |
| `src/main/resources/` | `fabric.mod.json`, `kantobridge.mixins.json`, and static data: the void world preset, biomes, the deeper overworld, battle-proxy NPC classes, the spawn rules (no spawns inside buildings; wild Pokémon only on a map's walking level), recipe overrides, placeholder tile models |
| `src/client/java/dev/kantobridge/client/` | Client-only code: `bridge/` (the link to gen1-bridge and the world export, below), `move/` (walking and warps, below; its mixins in `mixin/client/`), `ui/` (our screens for the hidden game's UIs, native special battles and the Game Boy keys), `puppet/` (NPC puppets), `act/` (A, START, right-clicks and field moves from the world; the crosshair on closed art through `mixin/client/GameRendererMixin`), `story/` (`ClientStoryView`, `StoryCellRenderer`, `StoryCellArt`), `fx/SurfSprite`, `net/` (`ClientRelay`; `GameRelay`, `ActivityTracker` and their glue `GameRelayRuntime`; `SaveRelay`, `PcRelay`, `SessionBinder` and their glue `SessionRuntime`, below), `battle/ClientBattles`, `packs/` (`GeneratedPack` and the pack-source mixin in `mixin/client/`, `PackGenerator`, `PackReload`), `world/` (`ClientWorldIndex`, the "Create Kanto world" flow) |
| `src/test/java/` | JUnit 5 under Fabric Loader (`fabric-loader-junit`): codec, catalog, redaction vectors, protocol examples, tile blocks, generated pack, the bridge client and its pause, the export client and CLI (also against `tools/fake_gen1`), the world model, export bundles, layout, interior packing, heights, story cells, slot map, PNG and textures, pack writers and the client-side pack, the synthetic plan and its golden WorldIndex, the build-request hand-off, world-folder names, game rules; the movement sync, forced mover, warp fade, input lock and their controller (also end to end against `tools/fake_gen1`), story-state messages, places, the Gen 1 speeds; the text markup, text paging and the typewriter, choice/menu/list cursors and scrolling, quantity bounds, naming rules, the UI router, the mirror subscription, the Gen 1 canvas, puppets and their bookkeeping and frames, interaction targets, the puppet entity type, the NPC sheets the renderer needs, and talking to an NPC end to end against `tools/fake_gen1`; Gen 1 ids (151 species, 165 moves, Appendix B's special names), DVs to IVs, `battle.start` and `party.give` reading, the proxy class, outcome and placement, `party.sync`'s statuses and nicknames, the client's battle and party relay, the activity, and the battle lock; wild starts, catches and where an encounter appears, `dex.sync` and `party.item` bodies, the sync debounce, the mirrored item table and the bag ledger (uses in flight, battles, expiry), habitat planning and pools, plain clicks, the bag relay; the save, PC and session relays (the session bind also against `tools/fake_gen1`), the session record, the blackout fade; M8's story rules (every story-cell type and source; live blocks per map visit), the closed-art lookup, swap sites (stamped, mismatched, reversed, art-only), the deep spill of interior packing, the build diagnostics, the surfing sheet, the slope brake, Flash's Night Vision rule; M8 wave 2: field moves from a press (Cut, Surf, stepping ashore, across a seam, a sailed ship), the refusal lines and their lang keys, the crosshair on closed art, a boulder's two pushes, native special battles on the mirror, the daycare's experience and its file, the daycare's relay, a seal its script overwrites and a ship that sails (`SealAndShipTest`) |
| `src/gametest/` | Server GameTests (mod `kantobridge-gametest`): Cobblemon start-up and mock players, tile blocks, static data, relay, battle-proxy hiding, story-cell and ledge collision, the synthetic world build, holding players during a requested build, Kanto's rules (game rules, Adventure, speeds, the bike flag, no damage), warps through the relay; Gen 1 numbers against Cobblemon's registries, Appendix C's settings and game rules, the starter lock, the activity; gifts into the party and the PC, `party.sync`, heal, the Name Rater, trades, the evolution filter; trainer battles against a hidden proxy (party, moves, IVs, class, `pvn`), stop → aborted, a real Showdown forfeit → lose, a win, refusals, leftover proxies; the synthetic town's habitat block (configured, a point of interest, its influence through Cobblemon's own spawner, the walking-level rule); wild encounters (a still, unbattleable Pokémon in a wild battle, refused balls, aborted, fled, win, lose, caught); the bag mirror and `bag.use`; the Pokédex both ways; stones, TMs/HMs and Rare Candy; `give` in `party.sync`; Bill's PC (Cobblemon's PC linked and opened, one `pc.closed` when it closes, refusals, leaving), saving (party, PC and player data written, one answer per id, repeats, refusals), the world id across restarts and the slot pairing, a lost battle's blackout (fainted party untouched until `party.heal`, the `blackout` warp); a player leaving with a Pokémon out, mid-battle and in the tick the battle ends (nothing removed inside the server's entity tracking); two players whose hidden games differ seeing the synthetic town's cut tree, item ball and water closed or open, a surfer on the water's surface, a delta for one player only (M8); Flash's Night Vision on a made-up dark map; M8 wave 2: two players' story cells as their hidden games move between maps (a cut tree growing back, toggles at their defaults, a sailed ship, an overwritten seal), the daycare (deposit, a restart, withdrawal with experience, level 100, the PC when the party is full, repeats, mismatches, stale entries after a crash, `party.sync`), a Safari catch and an in-game trade (OT, nickname, DVs, no doubles, `give` in `party.sync`), and Gen 1 battle music (no Cobblemon theme for the hidden game's battles, nor for wild ones in a Kanto world). Tests that fight end the battle, wait for Cobblemon's recall and a tick before the mock player leaves (`MockPlayers.leaveAfterBattle`). `resources/kantobridge/synthetic_bundle/` is the made-up export (generated by `tools/fake_gen1`, never edit it); `resources/kantobridge/golden/synthetic_world_index.json` is the WorldIndex that build must produce |

The protocol catalog comes from [`../protocol/catalog.json`](../protocol/README.md): the build generates
`MsgTypes.java` from it and bundles it in the jar as `kantobridge/protocol/catalog.json`.

## The link to gen1-bridge (`client/bridge/`)

The client connects to its own player's hidden Gen1Recomp, where gen1-bridge listens on `127.0.0.1:27151`
([`docs/PROTOCOL.md`](../docs/PROTOCOL.md)). Everything except the glue is plain Java, so JUnit drives it without
Minecraft:

| Class | Role |
|---|---|
| `BridgeClient` | Socket thread: connect, reconnect with backoff (0.5 s doubling to 5 s; the longest wait after `hello.reject`), `hello` first, ping every 2 s, drop after 10 s of silence, 4 MiB line limit. A writer thread per connection, so `send()` never blocks. Counts connects, restarts (new gen1-bridge session) and reconnects. `pauseReconnects()` hangs up and stays off (state `PAUSED`) until the pause is closed, so another connection (the export) can have gen1-bridge, which serves one connection at a time; it then reconnects and resyncs as after any drop |
| `BridgeSession` | One connection's protocol logic, no I/O: handshake, heartbeat, and the envelope and catalog checks on every message (answered with `error`) |
| `Dispatcher` | Hands messages and state changes to the client thread; drained every client tick |
| `ClientBridgeState` | The hidden player's map, cell, facing and mode, from `snapshot`, `player.pos`, `player.state` and `warp`, and whether it is live (`ingame`: the snapshot says so, or a position or warp arrival came since) |
| `ConnectionStatus`, `ConnectionHud` | The status line in the top-left corner, in game and on the title screen. It fades once connected and hides with F1 and F3 |
| `DebugOverlay` | **F7** (rebindable: *Options → Controls → Key Binds → Kanto Bridge*): the hidden map id, cell, facing, mode, session, counters and message rates |
| `ClientConfig` | `config/kantobridge-client.json` in the game folder (`local/minecraft/config/` in dev), created with `{"port": 27151}`. A broken file falls back to the default with a warning. `-Dkantobridge.port=<n>` overrides it, e.g. for a second client/Gen1Recomp pair |
| `BridgeRuntime` | The Minecraft glue: starts the client at client init and closes it when the game stops |
| `ExportClient` | The world export (role `builder`, its own short connection): `export.index` (waits up to 120 s, pinging, while gen1-bridge builds the export), then `export.get` for every resource, 8 in flight. Each `export.data` is re-serialised canonically and must match the index's size and SHA-256. Errors are codes: `unreachable`, `rejected`, `closed`, `index_timeout`, `stalled`, `role`, `not_found`, `bad_message`, `export_failed`, `peer_error`, `bad_index`, `bad_reply`, `size`, `hash`, `not_a_bundle`, `io`, `cancelled` |
| `BundleWriter`, `PrettyJson` | The bundle on disk: files go to `<out>/<game>.export-tmp` and the finished bundle is renamed to `<out>/<game>` (a previous bundle moves aside and is deleted after the swap); only a bundle or an empty folder is ever replaced. `<kind>/<id>.json` hold the canonical bytes; `index.json` is the repository's pretty format (`tools/kb_protocol/jsonfmt.pretty`, byte for byte), so a download equals `fake_gen1 --write-bundle` |
| `ExportCli` | The headless export: `./gradlew exportCli -Pkb.exportPort=<port> -Pkb.exportOut=<dir> [-Pkb.exportTimeout=<s>]` (see Tasks) |

Client logs show states, message types, sequence numbers, sizes and error codes only; never game content. The export
logs counts, kinds, ids and hashes, never data.

**Integration tests.** `FakeGen1IntegrationTest` starts `tools/fake_gen1` from the project venv
(`tools/.venv/Scripts/python.exe`, or `.venv/bin/python`; found through the repository root that Gradle passes as
`kb.protocolDir`), connects a real `BridgeClient` to it, follows a walk, and checks the reconnect after fake_gen1
drops the link. `ExportFakeGen1IntegrationTest` pauses a connected `BridgeClient`, exports as `builder`, checks that
the main link comes back, and compares the bundle byte for byte with `python -m fake_gen1 --write-bundle`.
`MovementFakeGen1IntegrationTest` (in `client/move`) drives a simulated player with the `MoveController` against
fake_gen1, playing the server's part of warps: the join teleport, a walk up into the house, and the exit mat's push and
walk-out. Without the venv they are skipped; `tools/setup.ps1` (or `setup.sh`) creates it.

## Walking and warps (`client/move/`)

The Minecraft player and the hidden Gen 1 player move together (PLAN §2.4 flows 1, 6, 7 and 13; PROTOCOL.md "Movement").
The logic is plain Java, tested without Minecraft (also end to end against `tools/fake_gen1`); `MoveRuntime` is the glue.

| Class | Role |
|---|---|
| `MovementSync` | **Steps:** crossing into an adjacent cell sends `move.step{dir, n}` (a corner cut in one tick becomes two steps, in crossing order); pushing one way against something for 8 ticks sends `move.step{dir, n, push:true}` (exit mats, gates), again every 16 ticks. **Facing:** standing still with the camera in another quadrant than the hidden player faces sends `move.face` after 4 ticks. **Answers:** gen1-bridge answers every step once, with its `n`. A step Minecraft is already making needs nothing; every other move (a walk Minecraft didn't ask for, the Cycling Road roll, scripts and door walk-outs, spinners, currents, ledge hops) is walked by the `ForcedMover`; a `sync` to another cell is snapped to; `player.blocked` snaps the player back to the hidden cell once no other step waits. **Drift:** cells that disagree for over 500 ms with no step waiting are steered back (up to 2 cells on one level) or teleported. The same check settles the player on joining, after a reconnect's snapshot and after every warp. All of it only while linked, idle, and with no warp or forced move running |
| `ForcedMover` | Moves the hidden game makes on its own, as legs of Gen 1 frames (3 per tick). Each world tick, before the player's own movement, it gives the player the velocity to where the legs say it should be, so collision and the server's movement checks treat it like walking. One braking tick after the last leg; a player who hasn't arrived 10 ticks later is teleported |
| `WarpFade` | `warp{start}` fades out (black; white for Fly, Teleport, Dig, Escape Rope, pads and holes) and holds the player. Once dark, with `warp{arrive, to}` known, it sends `warp.request{to, kind}`; after `warp.done` it waits until the destination's sections are drawn, then fades in. The client's own teleports (join, reconnect, drift) use it too, without a `kind`. Every wait times out (10 s for the arrival, 5 s for the answer, 3 s for the sections), fades in and logs a counted warning |
| `InputLock` | Whether the player is held, decided once per world tick. Only in a Kanto world (one with a WorldIndex) and never in creative or spectator; held during a Cobblemon battle (the hidden game waits for its result), while gen1-bridge isn't connected, the hidden game isn't in its overworld (title screen), its player stands on a map this world doesn't have, a warp or forced move runs, one of our screens (`InputLock.Holds`) is open or a hidden UI is open behind another screen (`holdWhile`), or the hidden mode isn't `idle`. `KeyboardInputMixin` zeroes movement, jump and sneak at the end of `KeyboardInput.tick` while held. A HUD line says why when the status line doesn't |
| `SpeedSync` | No sprinting where Kanto's rules hold (`LocalPlayerMixin` on `canStartSprinting`); the speeds themselves come from the server |
| `MoveController`, `MoveRuntime` | The controller turns the bridge's messages and `warp.done` into calls on the models and decides the lock; the runtime feeds it Minecraft's world ticks, applies the velocity and the lock, and draws the fade (under the status line). F7 shows the counters |
| `Intent`, `Spot` | The direction the movement keys point (one axis clearly dominating); a cell as a place in the built world (the outdoor maps share one grid, so neighbouring maps' cells compare across seams) |

`client/story/ClientStoryView` applies the hidden game's story (below, M8) to the local player's `StoryState` first (water
opens for a surfer at once) and then relays it as `story.state`: a full state on joining, after a snapshot and on respawn,
deltas after. `SlopeBrake` (Cycling Road, PLAN P19): while `player.state.slope` holds, sneak sends `move.hold{b, on}` on each
change (again after a reconnect) and doesn't crouch; the roll itself is a forced walk like any other.

## Dialogue, menus and NPCs (`client/ui/`, `client/puppet/`, `client/act/`)

The hidden game's NPCs and UIs in Minecraft (PLAN §2.4 flows 2–5, §4.3; PROTOCOL.md "Text", "UI", "NPCs"). As before, the
logic is plain Java tested without Minecraft (also against `tools/fake_gen1`); `UiRuntime`, `PuppetRuntime` and
`ActRuntime` are the glue. Logs carry codes, kinds, ids and counts, never text or art.

**Keys** (`Keybinds`, rebindable under *Options → Controls → Key Binds → Kanto Bridge*, PLAN P20): A = `Z`, B = `X`, START =
`Enter`, SELECT = `Backspace`; the directions are the arrow keys and the movement keys. Inside our screens a key held since
before the screen opened acts only once pressed again, and only directions repeat. Esc opens the pause menu; the screen
comes back when it closes, since the hidden UI is still open.

| Class | Role |
|---|---|
| `Gen1Text` | The markup: `<PK>`/`<MN>` are one glyph each, everything else one glyph per code point; counts, typewriter prefixes, wrapping |
| `UiRouter` | The open UIs as a stack: `ui.open` pushes (or replaces its id), `ui.update` changes one, `ui.close` removes one anywhere; only the top answers, once until it changes (an update, a UI above it closing, 1.5 s). A choice over a text box is one view. A dropped link closes everything, each `snapshot` starts over from its `ui`, and a hidden player idle for 0.5 s closes what is left (counted as stale) |
| `TextBoxModel` | Gen 1's box: types at FAST speed (one glyph per 60 Hz frame), two lines in view, scrolls on by itself except before `cont` lines; stops (▼) before a `cont` line, at a page end and at the end. A press at a stop moves on at once and sends one `ui.advance`; while typing it only finishes the visible text. `ui.update` moves it forward to the hidden box (never back); `auto` and `stay` boxes, and those a choice opens over (`choice`), show no ▼ and never ask at their end |
| `ChoiceModel`, `MenuModel`, `QuantityModel`, `NamingModel` | Cursors and answers: choices toggle; menus wrap and lists stop at their ends unless `ui.open.wrap` says otherwise, lists page with left/right and scroll their `rows`-high window from `top`; quantities step and wrap through 1..max (left/right by 10); naming shows presets first (NEW NAME opens the grid), then the engine's grid from `ui.open.grid` (glyph cells only) or Gen 1's upper/lower layout, types up to `max` (then jumps to ED), B deletes, SELECT flips case, START/ED confirm; an empty name falls back to the default, then the first preset |
| `BridgeScreen`; `TextBoxScreen` (with `ChoiceOverlay`), `MenuScreen`, `ListScreen`, `QuantityScreen`, `NamingScreen`, `MirrorScreen` | One screen at a time shows the router's view on the Gen 1 canvas (`Gen1Canvas`: 160×144 Gen 1 pixels, a whole number of window pixels each, about half the window wide, bottom-centred); UIs under it are drawn beneath, inactive. Never pausing, no background, holding the player. `Gen1Box` is Gen 1's window (white, black frame, on the 8-pixel tile grid); `Gen1Draw` draws the markup with Minecraft's font (`<PK>`/`<MN>` as small capitals), ▶ and ▼ |
| `MirrorLink`, `MirrorScreen` | `mirror.sub{fps:30}` while a mirror screen shows, `mirror.unsub` when it goes; the newest `mirror.frame` goes into one reused 160×144 `DynamicTexture` (nearest filtering, `kantobridge:mirror`), drawn at the largest whole scale that fits; every key is an `act.button` tap |
| `NativeBattle` | **Native special battles** (P9, M8 wave 2): the Safari Zone, the old man's demo and the ghost run in the hidden game. Their battle screen already arrives as a mirror UI (the UI observer's `BattleState`) with its text boxes as our screens; `battle.native{start}` keeps the mirror up between them on a UI of our own (id −1, keys still going to the hidden game) until `{end}`, holds the player throughout, refuses A, START and clicks, and reports the `activity` as a battle. A snapshot or a dropped link forgets it (gen1-bridge announces it again). Safari catches cross over as `party.give{native_catch}` |
| `UiRuntime` | Feeds the router, shows its view in one screen of ours while the player follows the hidden game (a Kanto world, not creative or spectator), keeps a closed UI's screen 150 ms so the next text box replaces it without flicker, and holds the player while any hidden UI is open (`InputLock.holdWhile`) |
| `Puppet`, `PuppetManager` | NPCs from `npc.set` (a map's full list: puppets rebuilt from it), `npc.add/remove/move/face`, `emote` and the snapshot's `npcs`; negative entity ids from −1,000,000 down, kept for a puppet's life. A step slides from cell to cell over its frames; the walking frame shows for the middle half of each 16-frame cycle, mirrored on every other step for up and down |
| `PuppetRuntime`, `PuppetRenderer`, `PuppetTextures`, `FacingFrames` | `PuppetEntity`s in the client level only (re-added to every new level), at the cell centre on the map's walking surface, moved each entity tick. Drawn as an upright, camera-facing 2×2-block billboard (`entityCutoutNoCull`) from the player's own pack, `textures/npc/<map palette>/<sprite>.png`, the frame chosen from the NPC's facing as the camera sees it; a neutral placeholder when the pack has no such sheet. Emote bubbles above them; the player's own over the crosshair. Solid and targetable for the local player only |
| `InteractTarget`, `ActionGate`, `ActRuntime` | A (`Z`) sends `act.interact` for the cell in front, in the camera's quadrant, when the Minecraft player stands on the hidden player's cell; right-click on a puppet, or a plain right-click on a block (`ClickPolicy`: an empty hand or an item that does nothing to blocks), sends it for that cell (straight, 1–2 cells away, named in the hidden player's map; across an outdoor seam too) and returns FAIL, so the server hears nothing; item use on a block (a Poké Ball, medicine) passes through; left-clicks on puppets are cancelled. START (`Enter`) sends `act.button{start}`. Only while the hidden player is idle and nothing of ours is open, once per press. Field moves (below) go out the same way |
| `FieldClick`, `FieldFeedback`, `ClosedArtPick`, `mixin/client/GameRendererMixin` | **Field moves from Minecraft** (M8 wave 2): A or a plain right-click on a Cut tree the player sees standing sends `act.field{cut, x, y}`; on water while not surfing `act.field{surf, x, y}`; a right-click on the ground of land while surfing `act.field{surf, x, y}` (gen1-bridge's dismount; A never dismounts, so a sign on the shore stays readable). Gen 1 itself does nothing for A on a tree or on water. The target must be next to the hidden player: one further away is refused here. A cell across an outdoor seam is looked up in its own map, named in the player's. After Minecraft's pick, the crosshair meets closed art as this player sees it (a standing tree's whole 2-high column, a shut door), so a right-click on a trunk reaches the tree, not what lies behind it; only with a plain hand while following the hidden game. Refusals (`no_move`, `no_badge`, `not_adjacent`, `not_here`, …) show one line over the hotbar (`kantobridge.field.action.*` and `.reason.*` lang keys), matched to the request by `act.result.re`; other answers stay silent. Strength needs nothing new: once it is on, holding the key against a boulder sends the two pushes (bump, push). Fly, Dig, Teleport and the Escape Rope run through the hidden game's own party menu and bag (the Fly list arrives as a list UI) |

## Battles and Pokémon (`battle/`, `pokemon/`, `client/net/`)

The hidden game owns the story; Cobblemon owns every Pokémon and fights every battle (CLAUDE.md "Ownership", PLAN §2.4
flows 8 and 11, U3, P11). gen1-bridge defers the hidden game's battle and sends `battle.start`; the player's client relays it
(and gen1-bridge's `party.*` requests) to the server unchanged, and brings `battle.result` and `party.sync` back. Logs carry
kinds, counts, class ids and codes, never teams or names.

| Class | Role |
|---|---|
| `GenOneIds`, `Gen1Mons` | Gen 1 numbers to Cobblemon (Appendix B): species by national dex number, moves by Gen 1 move number (= Showdown `num`), each with a by-id fallback from the tables of the 151 species and 165 moves; Appendix B's special names (Nidoran♀/♂, Farfetch'd, Mr. Mime, Vise Grip, High Jump Kick, `PSYCHIC_M`). A trainer's Pokémon: exactly its moves (slot by slot: Cobblemon's `moves=` keeps modern defaults), IVs `2·DV + 1` (Special to both special stats, HP from the derived HP DV), Hardy, the species' first regular ability, no item, never shiny. A gift: moves and DVs when given, otherwise Cobblemon's defaults |
| `BattleStart`, `Gen1Mon`, `Dvs`, `PartyGive` | The messages, read strictly (the relay checks envelopes, not schemas); errors are a JSON path and a reason |
| `BattleService` | `battle.start` → a `kantobridge:battle_proxy[_rival\|_gym\|_elite]` NPC (skill 1/3/3/5, by trainer class: `OPP_RIVAL3` is the Champion) on the trainer's cell (the WorldIndex; within 32 blocks) or 4 blocks in front of the player; never sent to any client (`NPCEntityMixin`), no AI, the trainer's name for Cobblemon's battle UI; `BattleBuilder.pvn(player, proxy, null, GEN_9_SINGLES, false, false)`. The end handler answers `battle.result` once: win, lose (a forfeit is a loss), fled (`BATTLE_FLED`), or aborted when nobody won (`stop()`: a disconnect, a crash). The proxy is discarded when Cobblemon's recall is done, 10 s after the end at the latest, before the server saves, and when a crash left one in a chunk. One battle per player: the same `battle` id again is ignored; anything that can't run (another battle, no usable Pokémon, a held player, malformed) is answered `aborted` with a counted warning; wild kinds go to `EncounterService` (below) |
| `PartyService`, `PartySync` | `party.sync` after Cobblemon's data sync and on party or PC changes (at most every 10 ticks, only when it changed, not during a battle; once after every battle), each gift with its `give` id. `party.give` → the party, or the PC when full (`PlayerPartyStore.add`), with its nickname, a trade's NPC trainer, a repeated `give` id ignored; `party.heal`; `party.rename` (the Name Rater; Cobblemon's own renames are cancelled, P16); `party.take` for trades (a sent-out Pokémon is recalled first; reason `daycare` boards it at the daycare); `party.item` (below). A trade's `ot` becomes Cobblemon's NPC original trainer; a native catch (the Safari Zone, `native_catch`) is the player's own. Evolutions into species beyond 151 are blocked (P14) |
| `DaycareService`, `DaycareStore`, `DaycareRules` | **The daycare** (P15, M8 wave 2): `daycare.deposit{uid}` takes the Pokémon out of the party (or the PC) into the player's daycare on the server, Cobblemon's own JSON of it in `<world>/kantobridge/daycare/<player uuid>.json` (written atomically before it leaves the party; an unreadable file is moved to `.broken`); `daycare.withdraw{uid, steps}` brings it back into the party (the PC when full) with one experience point per step, never past level 100 (Cobblemon's level-ups, moves and evolution prompt follow). The party and PC are saved right after each change; should a crash leave it in both, the party's copy wins and no second Pokémon with the same uid appears. Repeats change nothing; mismatches are counted warnings. `party.sync` follows |
| `BattleMusic` | **Gen 1 battle music** (P22, M8 wave 2): the hidden game plays its own battle theme through Gen1Recomp's window, so Cobblemon's battle theme is cleared for each player actor just before a battle starts (`BATTLE_STARTED_PRE`, after Cobblemon's builder chose it): every battle the hidden game asks for (`BattleService`, `EncounterService`), and natural wild battles in a Kanto world. Cobblemon then sends no theme (spectators neither), and Kanto's biomes keep Minecraft's music silent. Elsewhere Cobblemon's music stays |
| `server/CobblemonSettings` | Appendix C, applied by `Rules`: the configuration in memory at server start (`config/cobblemon/main.json` is never rewritten), Cobblemon's game rules with Minecraft's, and the starter prompt locked for each player on Cobblemon's data sync |
| `server/ActivityService`, `story/Activity` | Each client's `activity` (battle, dialogue, cutscene, none) as an attachment on its player, for M9's speech bubbles |
| `client/net/GameRelay`, `GameRelayRuntime` | The client's relay: `battle.start` up with a pending-battle record; its `battle.result` down, kept and sent again after every gen1-bridge snapshot for 60 s (an undelivered one until delivered); a repeated start gets its result again; no server answers `aborted` here, and leaving the server aborts pending battles (a native special battle reports a battle `activity` too). `party.*` and `daycare.*` up, queued while there is no server (and sent before a battle start); `party.sync` and `dex.sync` down, and again after every snapshot; the bag both ways (below). Also reports the `activity` |
| `client/battle/ClientBattles` | Cobblemon's client battle state: while the player fights, the input lock holds them (`InputLock.Reason.BATTLE`), the movement sync sends nothing (relinking afterwards puts them back on the hidden cell), our screens wait, and A, START and clicks do nothing |

## Wild Pokémon, the Pokédex and the bag (`battle/`, `pokemon/`, `bag/`, `server/HabitatService`; M6)

Wild Pokémon of grass and caves come from Cobblemon habitat pools; surfing, fishing and static encounters are rolled by
the hidden game and fought in Cobblemon (PLAN P8); the Pokédex and the bag's battle items cross over (P17). Logs carry
kinds, counts, map ids and codes, never species, levels or tables (the encounter tables are ROM-derived).

| Class | Role |
|---|---|
| `builder/plan/HabitatPlanner` | For every built map with a grass table that rolls (not the Safari Zone, and not the Pokémon Tower until M8: its ghosts stay native until the Silph Scope): habitat blocks 2 blocks under the ground, covering every floor block of its grass cells (or of every walkable cell on indoor encounter maps, the engine's `indoorEncounters` rule, FOREST excepted) within Cobblemon's 3-D range 14: a greedy cover that always covers the first uncovered block, ties going to the candidate nearest the middle of what it covers. Interiors with a pool get the cave biome so the interior spawn guard leaves them alone |
| `builder/pack/HabitatPoolWriter`, `DatapackWriter` | `data/kantobridge/habitat_pools/<map>.json` in the world's datapack for every wild map of the export (like the slot map, whatever the filter): one entry per Gen 1 slot, bucket `common`, weight the slot's percentage (its share of the 256-step roll), the single level, Cobblemon's species id by dex number, no phases |
| `server/HabitatService` | Places and configures the blocks for the builder (natural, `ReplaceSpawns` off, range 14, the map's pool, SIMPLE, `refreshFromSettings()`), hands the pools to Cobblemon at once (the datapack loads with the next world load), and checks every listed block as its chunk loads: re-attaching a pool from the datapack's files if the pack is off, preparing a reloaded block before its chunk ticks. Counts only |
| `data/kantobridge/spawn_rules/walking_level_only.json` | A sphere also reaches tree tops, roofs, ledges and story cells near the grass: in Kanto's outdoor and cave biomes Pokémon spawn only on a floor block at a map's walking level (Y 63 − 22k) |
| `battle/EncounterService`, `EncounterPlacement` | A wild `battle.start` (static, surf, fish, or scripted with `wild`): the Pokémon (Cobblemon's moves for its level) on the given cell of the player's map, or two cells ahead (on the water when surfing or fishing), standing still (`no_ai`), persistent, `UNBATTLEABLE`, outside the spawn cap; `BattleBuilder.pve` with flee distance 12 (none when `rules.flee` is false); `rules.catch` false makes it uncatchable. Balls from outside its battle are refused. One `battle.result`: `caught{uid, dex, level}`, `win`, `lose`, `fled`, `aborted`; the Pokémon is discarded after. The held client can't walk away, so Run ends any wild battle in a Kanto world as fled |
| `pokemon/DexService`, `DexSync`, `SyncDebounce` | Cobblemon's Pokédex changes → `dex.sync{seen, owned}` (dex ≤ 151, owned counted as seen; debounced, only when changed). The hidden game's entries are written with `setKnowledgeProgress` (no events, never downgrading), reached through `dex.sync` C→S once the catalog gives it that leg |
| `bag/BagItems`, `BagLedger`, `BagService` | The 29 mirrored battle items (Appendix B, ids checked against Cobblemon 1.8.1: `x_defence`, `x_special_attack`). `bag.sync` adds and removes them until the inventory matches (main inventory before the hotbar; a matching sync changes nothing); consumption goes back as `bag.use`, during battles only once they are over (Cobblemon gives back an item whose turn never came); uses in flight are subtracted from crossing syncs until a sync shows them, or for a minute |
| `pokemon/PartyService`, `PartyItem` | Also: `party.item` (a stone evolves at once with `forceEvolve`, a TM/HM is taught whatever Cobblemon's learnset says into its slot or a free one, the old move benched, Rare Candy gives the experience to the next level); `give` in `party.sync`; one sync after every battle |
| `client/act/ClickPolicy` | Plain right-clicks on blocks (an empty hand, block items, plain items) are A on the cell; anything usable (Poké Balls, Cobblemon's medicine, food, tools) passes through to Minecraft |
| `client/net/GameRelay` | Also: `bag.sync` up (the newest waits for the server, after waiting party requests and before a battle start), `bag.use` down (queued for the next snapshot without a link), a live snapshot's Pokédex up when `dex.sync` has a `c2s` leg |

## Pokémon Center: Bill's PC, saving, the save pairing and blackouts (M7)

Bill's PC is Cobblemon's PC, both games save at the same moment, and each world has an id the hidden game can pair a
save slot with (PLAN §2.4 flows 8, 12 and 13, §4.4; PROTOCOL.md "Saving", `pc.open`/`pc.closed`). Logs carry codes,
counts, reasons, times and short world ids.

| Class | Role |
|---|---|
| `pokemon/PcService` | `pc.open` → a `BridgePcLink` (Cobblemon's `PCLink`, permitted for that player anywhere, outside battles) and Cobblemon's `OpenPCPacket`. Cobblemon's PC screen unlinks itself on every way out, so a server tick that finds the link gone (or replaced by another PC) ends the session with exactly one `pc.closed`. A PC that can't open (a battle, a player held for a build, no PC store, malformed) gets `pc.closed` at once with a counted warning; a second `pc.open` while one is open is ignored; leaving ends it unanswered (the client answers). `close(player)` closes it from the server (`ClosePCPacket`) |
| `server/SaveService` | `save.begin{save, reason}` → the player's party and PC handed to Cobblemon's store writer now (Cobblemon itself writes stores only on its 30 s timer, at logout and shutdown; its private factory list is reached through `mixin/PokemonStoreManagerAccessor`), their Pokédex, general data and TM moves, then `saveEverything` as `/save-all` does but without a flush (serialised now, written by the usual background writers, so the server doesn't stall) → `save.done{save, ok}` with `re`. `ok:false` (counted) for any exception, levels with saving off, or a world holding players for a build. A repeated id is answered again from the first answer, without saving twice (the last 16 per player) |
| `server/SessionService`, `SessionRecord` | The world id: a lowercase UUID in `<world>/kantobridge/session.json`, created at the world's first start with the mod and never changed (written atomically; an unreadable file is moved to `session.json.broken` and replaced, with a counted warning). The record also keeps each player's paired slot (`record(player, slot)`) for M9/M10. `bindData(player)` is the `session.bind` body: the id and `multiplayer` (everyone but an integrated server's owner). Sent to joining clients once the protocol gives `session.bind` an `s2c` leg; `session.bound` is recorded once it has a `c2s` leg (both guarded by the bundled catalog, like M6's `dex.sync` C→S) |
| `client/net/SaveRelay` | `save.begin` up unchanged, `save.done` down with `re` = gen1-bridge's `save.begin` seq when it came on the current link. No server: `ok:false` here; leaving the server: pending saves `ok:false`; a repeated ask for an answered save is answered from the record (kept 60 s after delivery), a repeat while pending is ignored |
| `client/net/PcRelay` | `pc.open` up, `pc.closed` down. While a session runs the player is held (`InputLock.holdWhile`) and our screens wait (`UiRuntime.suspendWhile`), so Cobblemon's PC screen has the stage. No server or leaving it: `pc.closed` here. A close gen1-bridge couldn't get (link down) answers a `pc.open` that comes within 5 s of its next snapshot instead of opening the PC again, and is forgotten otherwise (`pc.open` has no id; gen1-bridge releases its hold when the link drops and never asks again). Safety nets: a PC screen that never shows within 10 s, or disappears with no answer within 2 s, gets Cobblemon's own unlink sent for it; 5 s later without an answer the client answers itself and drops the late answer |
| `client/net/SessionBinder`, `SessionRuntime` | `session.bind{world, multiplayer}` to gen1-bridge once the player is in a Kanto world whose id is known, and again after every (re)connect or a change of world; `multiplayer` is false only on the integrated server this client runs. The id comes from the server's `session.bind` (with the `s2c` leg) or, until then, in-process from our own integrated server (singleplayer, LAN host). `session.bound{slot}` is kept and shown on F7, and relayed up with the `c2s` leg. The runtime also wires the save and PC relays and adds two F7 lines |
| `client/move/WarpFade` | The `blackout` warp fades to black over 1 s (4× slower) and back in over 0.5 s |
| `mixin/PokemonEntityMixin`, `entity/DeferredRemovals` | Cobblemon discards a player's own sent-out Pokémon in `stopSeenByPlayer`, which Minecraft calls from inside `ChunkMap`'s loops over its tracked entities (a player leaving, respawning, changing dimension or moving out of range); the removal changed the map under the loop and could crash the server when a player left with a Pokémon out (in or right after a battle: its recall plays for 1.5 s). That discard now runs at the end of the server tick. Such Pokémon are never saved with the world, so one a stopping server doesn't get to is harmless |

**Blackouts** need nothing more on the server: a lost trainer battle is answered `lose`, and gen1-bridge's blackout
arrives as an ordinary `warp.request{kind:"blackout"}` and `party.heal`. Cobblemon closes the battle at once (no one is
left in or watching it), its passive healing and fainted-revival are off (Appendix C), so the fainted party stays fainted
until the heal; a GameTest runs the sequence.

## Rules and warps on the server (`server/`)

| Class | Role |
|---|---|
| `Rules` | On server start: Appendix D's game rules (`KantoGameRules`) and Appendix C's Cobblemon settings and game rules (`CobblemonSettings`), Peaceful with the difficulty locked (so no hunger), Adventure as the default game type, clear weather, noon. Cobblemon's starter prompt locked for every player. On join, respawn and release from `BuildHold`: Adventure (held players stay spectators), the Gen 1 speed for the player's bike flag (walk 0.1737, bike 0.3474: 16 and 8 frames per 2-block cell; the movement attribute and `abilities.walkingSpeed` alike) and jump strength 0. An `ALLOW_DAMAGE` veto protects players, except from what bypasses invulnerability (the void). The rules hold on every server running the mod, the GameTest server included |
| `WarpService` | `warp.request{to, kind?}` teleports the sender, and only the sender, to the centre of `to`'s cell at its map's feet level, in the same level, facing `to.facing`, and answers `warp.done{ok, to}` with `re`. Unknown maps, cells outside their map, malformed requests, a world without a WorldIndex and held players get `ok:false` and a counted warning |
| `StoryStateService` | Applies `story.state` (full or delta, checked completely first) to the sender's `StoryState`; a bike change re-applies the speed, the lit maps re-decide Flash at once; malformed messages are answered with `error` |
| `FlashService` | Dark maps have no lights. A player standing on a dark map (WorldIndex `dark`) that their own relayed state has lit gets Night Vision (endless, no particles, no icon), checked every 5 ticks and on each `story.state`; anywhere else ours is taken away. Any other Night Vision (a potion) is left alone |

## Create Kanto world (`client/world/`)

A title-screen button ("Create Kanto world", below the menu, or top right on a small window) runs
`CreateKantoWorldFlow` (PLAN §4.3) behind `KantoWorldScreen`, a progress screen that never pauses the game:

1. **Check** that the main link to gen1-bridge is connected (waiting up to 7 s for a reconnect).
2. **Export** with `ExportClient` into `<gameDir>/kantobridge/export/<game>/`, the main link paused meanwhile.
3. **Pack:** `PackGenerator` writes `<gameDir>/kantobridge/resourcepack/` with the builder's pack writer over the whole
   export's slot map (the same pack any build of that export writes), then `PackReload` re-scans the pack sources and
   reloads resources, so the pack-source mixin's required pack is picked up even if its folder is new.
4. **Create** a level with `createWorldOpenFlows().createFreshLevel(...)`: folder `Kanto`, or `Kanto (1)`, `Kanto (2)`...
   (`KantoWorldNames`; an existing world is never reused), preset `kantobridge:kanto`, Adventure, Peaceful, commands on,
   and the Appendix D game rules (`server/KantoGameRules`).
5. **Build:** just before creating the level the flow files a `BuildRequest` (bundle, every map, pack already written).
   The integrated server takes it on `SERVER_STARTED` if its world folder matches (`HeadlessBuild`, the same code path as
   the headless trigger), locks Peaceful, and builds; `BuildHold` keeps joining players as spectators until the build is
   done, then moves them to the new spawn with their own game mode. The screen follows the request's `BuildStatus` and
   lets the player in when it is done.

Cancel (export or build) and failures (gen1-bridge not running, export, pack, reload, world creation, build) end on a
message; a world folder the flow created is left like "Save and Quit" and then deleted through Minecraft's own world
deletion. Nothing is ever saved with the spectator mode: held players get their mode back before they are saved.

## The world builder (`builder/`)

Builds Kanto into a level from an export bundle (`<gameDir>/kantobridge/export/<game>/`: `index.json` plus
`<kind>/<id>.json`; [`docs/PROTOCOL.md`](../docs/PROTOCOL.md) "Export resources"). The bundle is ROM-derived: the
builder logs and reports only counts, map ids, codes and hashes.

| Step | Where | What |
|---|---|---|
| Read | `export/ExportBundle` | Checks every resource's size and SHA-256 against the index before anything is used |
| Plan | `plan/WorldPlanner` (pure Java, off the server thread) | Surface layout by BFS from the layout root (`Layout`; checks edges, the exported layout and overlaps for the built maps), interiors under their parent door, one layer down, packed with 16-block gaps (`InteriorPacker`; a chain that runs out of depth goes sideways on the deepest layer, under the void fill: PLAN §8), cell models and heights (`MapModel`, `HeightRules`), the slot map (`ArtCatalog`: from the **whole export**, so any filtered or test build writes the same pack and slot-map hash), story cells (`StoryCellPlanner`, with each tile cell's closed state as slots of that slot map), spawn, WorldIndex |
| Pack | `pack/ResourcePackWriter` | `<gameDir>/kantobridge/resourcepack/`: `pack.mcmeta` (34), blockstates with all 256 variants per used family, models, 16×16 tile textures with water/flower animations, NPC sheets (64×32: standing and walking, down/up/left/right), the surfing player's sheet (`textures/player/<palette>/surf.png`, same layout) per palette of a map with water. Replaces the previous generated pack as a whole |
| Place | `place/BlockPlacer` | 25 ms per server tick, chunk by chunk, top-down, `setBlock` flags 18 (16 while unseen), a region ticket per chunk, at most 4 chunks waiting for the light engine, biomes per 4×4×4 cell. Re-runnable: everything inside the build's volumes (and the previous build's, from its WorldIndex) is set or cleared; unchanged blocks are left alone |
| Finish | `BuildTask` | `<world>/kantobridge/world_index.json` (with the habitat blocks), `slot_map.json`, `build_report.json` (also `habitat_blocks`, `pools`, `maps_unplaced`), `build_diagnostics.json` beside every copy of the report (`BuildDiagnostics`: per warning or note code, the rows of engine symbols it is about, such as map ids, swap kinds, event flags, a site's position in the export's list; never coordinates, block ids or text, and never in the log), `<world>/datapacks/kantobridge_generated/` (format 48, no worldgen: the habitat pools) |

What gets built (PLAN §4.5): ground at Y 63 under the Y 64 walking surface; water as half blocks with a story cell
above; ledges as ground art plus a `ledge_collider`; solid cells as tile columns (trees, walls, fences, signs 2 high,
roofs and cliffs 4, measured from the drawing; counters 1); a 12-block ring of 3-high void fill around the surface
maps; interiors at Y 64 − 22k with floor, 2-high walls, an enclosure ring and an opaque ceiling 4 above the floor,
and `minecraft:light` 15 on a 4-block grid unless the map is dark. Swap sites (Cut trees, card-key doors, scripted swaps) are
built open: each cell a site changes in its walkable state (normally the site's `after`; a cell whose `after` is the
solid one keeps `before`), with a `story_tile` story cell that closes it per player. A site is accepted when the map data
holds either of its blocks: `before` (the data shows it closed, like a Cut tree) or `after` (the engine stamps it closed
on map load until its event is set, like Silph Co.'s card-key doors and the Rocket Hideout's lift gates: noted as
`swap_site_stamped`). A `script` site whose data holds neither is accepted too (M8 wave 2: its script writes the block on
every map entry, like Agatha's exit seal; noted as `swap_site_overwritten`): built open like any other, its tile cells'
`start` is the data's own block (open away from the room; inside it the live block decides), and every cell of its
block takes its flags from what it is built with. Any other mismatch is a `swap_block_mismatch` warning, named in the
diagnostics. The slot map also lists each site's data block, so accepting such a site changes no slot.

**Entry points.** `KantoWorldBuilder.start(ServerLevel, ExportBundle, maps, BuildOptions, BuildListener)` on the
server thread returns a `BuildTask` (phase, progress, report). In game, the "Create Kanto world" flow hands its build to
the integrated server through a `BuildRequest` (above). Headless:

```sh
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot" ./gradlew runServer \
  -Pkb.build=../local/minecraft/kantobridge/export/blue \
  -Pkb.buildMaps=PALLET_TOWN,REDS_HOUSE_1F,REDS_HOUSE_2F,BLUES_HOUSE,OAKS_LAB \
  -Pkb.buildReport=../local/reports/build_report.json -Pkb.buildExit=true
```

The properties become `-Dkantobridge.build`, `.build.maps`, `.build.report` and `.build.exit` on the server run only
(relative paths resolve against this folder). The build starts on `SERVER_STARTED` in the overworld of the server's
own world folder (`level-name`), and with `exit=true` the process ends with 0 on success and 1 on failure (the report's
`status` says the same). `runServer` needs the user's `eula.txt` and a `server.properties` in `../local/minecraft`.

**Collision.** `story_cell` and `story_tile` blocks are invisible and have no outline; they collide only for players whose
`StoryState` (a data attachment on both sides) has their cell closed: `object`/`tile` cells while active, `water`
unless surfing, `boulder_area` where a boulder stands. With no entity they are full cubes. `ledge_collider` is a 24-px
wall only for an entity entirely on its downhill side. The server sends the WorldIndex to joining clients as
`world.index` parts (`server/WorldIndexSync`); the client keeps it in `client/world/ClientWorldIndex`.

## Tasks

| Command | Result |
|---|---|
| `./gradlew build` | Builds `build/libs/kantobridge-<version>.jar` and runs `check`: JUnit (`test`), the server GameTests (`runGameTest`) and `verifyMixinRemap` |
| `./gradlew test` | JUnit only (reads `../protocol/` for examples and redaction vectors, and `src/gametest/resources/kantobridge/` for the synthetic bundle). `-Pkb.updateGolden=true` rewrites the golden WorldIndex instead of comparing |
| `./gradlew runGameTest` | Server GameTests, headless, in `build/run/gameTest` (its old world is deleted first; 15-minute timeout). Report: `build/reports/gametest/TEST-gametest.xml` |
| `./gradlew runServer` | A dev server in `../local/minecraft` (git-ignored); needs the user's `eula.txt` there. With `-Pkb.build=...` it builds a world headless (above) |
| `./gradlew exportCli -Pkb.exportPort=<port> -Pkb.exportOut=<dir>` | Downloads gen1-bridge's export (role `builder`) into `<dir>/<game>/` with `ExportCli`, plain Java without Minecraft. `<dir>` defaults to `../local/minecraft/kantobridge/export`; relative paths resolve against this folder; `-Pkb.exportTimeout=<s>` sets the index wait (default 120). Prints one line, `export: ok game=... resources=... bytes=... kinds=... index=<sha256> ...` or `export: failed <code> ...`, and fails the task when the export fails. Warnings go to stderr through its own log4j2 config |
| `./gradlew runClient` | A dev client in `../local/minecraft`. Agents never use it for verification: nobody can see it |
| `./gradlew genSources` | Decompiles Minecraft with Parchment names, for reading in the IDE |

## Build notes

- **Kotlin Gradle plugin, no Kotlin sources.** Loom remaps the Kotlin metadata of Kotlin mods only when the
  `org.jetbrains.kotlin.jvm` plugin is applied. Without it, Cobblemon's metadata keeps intermediary names and its
  start-up crashes in every dev runtime (`ClassNotFoundException: net.minecraft.class_2960`). The plugin version
  equals Cobblemon's Kotlin (2.2.20). After changing it, delete `.gradle/loom-cache/remapped_mods` once.
- **ICU4J on server runs.** Minecraft lists ICU4J as a client library, so Loom drops it from server-side runs, but
  Cobblemon's Showdown engine needs it. The build adds Cobblemon's own version to `runServer` and `runGameTest`
  only; real servers use the copy bundled in Cobblemon's jar.
- **Mixin remapping.** Loom 1.17 remaps mixins with tiny-remapper (no refmap). `verifyMixinRemap` fails the build if
  an injector target in the remapped jar still has its Mojang name, which would only break in a real game.

## Story cells, closed art, surfing and Flash (M8 wave 1)

Each player's hidden game decides which story cells are closed for them (docs/PROTOCOL.md "Story rules (M8)"). The client
computes it, applies it to its own player first and relays it; the server only collides with what it is told.

| Class | Role |
|---|---|
| `story/StoryRules` | Plain Java, common (so GameTests use it too): the story messages (`snapshot`, `story.toggles`, `story.blocks`, `story.lit`, `player.state`, the current map from `player.pos` and warps) and the live NPC list → the **active** cells and the tile cells **drawn** closed. `object` cells: on the NPC list's map, closed where a live NPC stands; elsewhere by effective visibility (the snapshot's `toggles`, then `story.toggles`, else the WorldIndex default `hidden`). `tile` cells: closed while the live block at the site is the cell's closing block (`closed.block`); live blocks are known for the current map only, other maps show their map data (`start`), so cut trees grow back. A closed cell is drawn and collides as `closed.pass` says (solid: always; water: unless surfing; ground: never, only its art differs); an open cell built as water (`water`, e.g. where the S.S. Anne lay) collides unless surfing. `boulder_area` cells: closed under live boulders. Water is never active (the surf flag opens it). Lit maps from the snapshot's `lit`, `story.lit` and `player.state` `dark`/`lit` |
| `client/story/ClientStoryView` | The glue: feeds the rules (the puppets are the live NPC list), applies `story.state` to the local `StoryState` and relays it (full on join, snapshot and respawn; deltas after). F7 shows the counts |
| `client/story/StoryCellRenderer`, `StoryCellArt` | A block-entity renderer for `story_tile` blocks only (their own block-entity type, so water and boulder cells cost nothing per frame): while the local player sees the cell closed, each of its four blocks draws its quarter's closed art, the tile block of the slot the WorldIndex names, up the closed column (`height`: a tree or a wall is 2) or as a thin layer on the floor (art-only). The textures are the player's own pack's: the slot map lists both blocks of every swap site |
| `client/fx/SurfSprite` | While the local player surfs, the hidden game's surfing sprite (`field.playerSprites.surf`, Gen 1's Seel) from the pack's `textures/player/<palette>/surf.png`, in the palette of the map underfoot: in third person an upright, camera-facing billboard sunk into the water in front of the player's legs (the player rides it); in first person its back lies flat on the water under the player. The walking frame cycles while moving. A surfer's feet are on the water's surface (the half block's top, Y 63.5): the water story cells are open for them |
| `server/FlashService` | Above |
| `builder/BuildDiagnostics` | Above |

## Field moves, native battles, the daycare and battle music (M8 wave 2)

The rest of M8's Java side, each above in its table: field moves from Minecraft (`client/act/FieldClick`,
`FieldFeedback`, `ClosedArtPick` and `mixin/client/GameRendererMixin`), native special battles kept on the mirror
(`client/ui/NativeBattle`), Safari catches and in-game trades (`pokemon/PartyService`), the daycare
(`pokemon/DaycareService`, `DaycareStore`, `DaycareRules`) and Gen 1 battle music (`battle/BattleMusic`). The builder
accepts a scripted swap site its script overwrites on every map entry (Agatha's exit seal; `swap_site_overwritten`), and
the story rules wave 1 left to check (live blocks per map visit, toggles at their defaults, a sailed ship's water) have
JUnit and GameTest cases.
