# Kanto Bridge protocol

**Protocol 1.** This file and [DESIGN.md](DESIGN.md) describe the protocol; the JSON schemas in
[`../protocol/`](../protocol/README.md) are its machine-readable form, and the two must agree
(`tools/tests/test_schemas.py` checks that every message type below has a schema and that the ROM
column matches). To change the protocol, change this file and the schemas first, then both sides of
the bridge.

The protocol connects:

- **gen1-bridge**: the Gen1Recomp mod (Lua), running in the player's hidden Gen1Recomp.
- **fabric-bridge**: the Fabric mod (Java), running in Minecraft with Cobblemon. Its **client**
  talks to gen1-bridge; its **server** hears only from clients.

## Transport

A local TCP socket between gen1-bridge and the fabric-bridge client on the player's own machine. The
Gen1Recomp side needs the sandbox's `network` permission (see [REFERENCES.md](REFERENCES.md)).

- **Listening.** gen1-bridge listens on **`127.0.0.1:27151`**: loopback only, so no firewall
  prompt. Both sides read the port from config, so two pairs can run on one PC.
  - gen1-bridge binds lazily on its first logic step and retries every second while the port is
    busy. A port is never taken over: **`SO_REUSEADDR` stays off on Windows**, where it would let a
    second listener bind a port in use; on every other platform it is set, where it only lets the
    listener bind over its own predecessor's TIME_WAIT remnants (a crashed instance's server-closed
    connections keep the port "in use" for a minute without it) and a port another listener holds
    stays busy.
  - It serves one connection at a time; a new connection replaces the old one.
  - Gen1Recomp mods only start once a game boots, so the listener exists from then on.
  - **Why this way round:** the Lua side is single-threaded and sandboxed, and polling `accept()`
    each logic step is its simplest non-blocking pattern, while Java can connect and retry on a
    background thread. Gen1Recomp is also the long-lived side, while Minecraft clients restart and
    rejoin.
- **Reconnect.** The client connects when Minecraft starts and reconnects with backoff: 0.5 s,
  doubling to at most 5 s.
- **Framing.** Newline-delimited **strict** JSON: one UTF-8 JSON object per line.
  - Parsers reject NaN/Infinity, duplicate keys, lone surrogates, non-object lines and trailing
    data. Empty objects stay `{}` and empty arrays stay `[]`.
  - Lines may end in `\r\n`; empty lines are ignored.
  - A line is at most **4 MiB**. Export pages are kept at or below 1 MiB. An oversize line is an
    error that drops the connection.
  - The Lua side keeps a reassembly buffer for partial reads and an unsent-tail buffer for partial
    writes.
- **Heartbeat.** The client sends `ping` every 2 s; the other side answers `pong`. After 10 s
  without receiving anything, either side drops the connection.

## Envelope

```json
{"type": "player.pos", "seq": 42, "data": {"map": "PALLET_TOWN", "x": 5, "y": 6, "facing": "up", "kind": "walk", "frames": 16}}
```

| Field | Meaning |
|---|---|
| `type` | Lowercase dotted words, e.g. `ui.open` |
| `seq` | Integer ≥ 1, counted per sender and per connection (or per relay channel) |
| `re` | Replies only: the `seq` being answered. Each type says whether `re` is required, optional or never set |
| `data` | An object, `{}` when empty |

- **Correlation across hops** uses ids inside `data` (`battle`, `give`, `save`), because `seq`/`re`
  only make sense on one connection. The client maps them when it relays.
- **Unknown types** are answered with `error{code:"unknown_type"}` and otherwise ignored. A
  malformed envelope gets `error{code:"bad_envelope"}`.

## Handshake

The first exchange on every connection:

1. The client sends `hello` with `protocol: 1` and its role:
   - `client`: a player's Minecraft;
   - `builder`: the world builder's short export connection;
   - `test`: `fake_minecraft` and end-to-end tests. Only this role may use `debug.*`.
2. gen1-bridge answers `hello.ok` (its version, the engine version, the game and a random session
   id), or `hello.reject` and closes. The protocol number must match exactly.
3. After `hello.ok`, gen1-bridge sends a `snapshot`, and a client role starts its pings.

Anything else before the handshake is answered with `error{code:"no_hello"}` and the connection is
closed.

**Hardening (M10):**

- **Timeouts:**
  - gen1-bridge answers a connection that sends no `hello` within 10 s with
    `error{code:"no_hello", msg:"hello_timeout"}` and closes it.
  - A first line that isn't a valid envelope gets `error{code:"bad_envelope"}` and is closed.
  - The client drops a connection that brings no `hello.ok` within 10 s. That is the "not
    answering" case, for example Gen1Recomp stuck on its error screen.
- **A stall of the hidden game is not the client's silence:** gen1-bridge drops a client after 10 s without
  a received message, but time the engine itself didn't run (a gap of over a second between two logic steps:
  a long data read during the export, a frozen window) moves that clock along and is logged as
  `session.stalled`; the heartbeat keeps its meaning across stalls.
- **`hello.reject` precedence:** `protocol`, then `malformed` (wrong types or missing fields), then
  `role`. A well-formed but unknown role string gets `role`.
- `hello.ok.tested` is the engine version gen1-bridge was tested with. When `engine` differs,
  gen1-bridge logs `engine.untested` once per connection, and the client shows a one-time notice
  (a toast per pair of versions, plus an F7 line). It is a warning only.
- `hello.ok.union_room` (optional, `"on"` or `"off"`) says whether the engine's Union Room setting
  patched this boot's Pokémon Centers (ENGINE_UPGRADE_PLAN U3: it must be off, because the world is
  built from the import, which never sees the patch). While `"on"`, gen1-bridge logs
  `engine.union_room_on` once, writes the setting off for the next boot, refuses `export.index` with
  `error{code:"union_room_on"}`, and the client shows a one-time notice naming the switch
  (Launcher Options → Union Room → OFF, then restart) plus an F7 suffix.
- `hello.reject{reason:"protocol"}` makes the client's pause overlay show both protocol numbers and
  ask for matching gen1-bridge and kantobridge versions.
- **The game catalogue (MG1, several games):** [`protocol/games.json`](../protocol/games.json)
  (schema [`games.schema.json`](../protocol/games.schema.json)) is the one place that names the games:
  for each Gen1Recomp game id (`red`, `blue`, `yellow`, `gold`, `silver`, `crystal`, `firered`,
  `leafgreen`, `ruby`, `sapphire`, `emerald`) its engine `family` (`gen1`, `gen2`, `gen3`; one bridge
  adapter per family), Gen1Recomp's `engine`, its `generation`, `label`, default `region` (its world
  space: `kanto`, `johto`, `kanto_frlg`, `hoenn`), its `compat` profile (`maxDex`, `maxMove`, `nick`),
  its `artSet`, whether gen1-bridge has an `adapter` for it, and the story `markers` `game.status`
  reports. fabric-bridge bundles it in its jar (`Games`); gen1-bridge carries the generated copy
  `lib/kb_games.lua` (`kb_protocol.catalog --write`); the stand-ins read it with `kb_protocol.games`.
  Every `game` field of the protocol (`hello.ok`, `export.index`, the WorldIndex) is an open
  catalogue id (`defs` `gameId`); nothing coerces an unknown id to `red` any more. `hello.ok` adds
  `family` and `compat` (both optional on the wire: an older bridge omits them and the client takes
  the catalogue's); a process running a game without an adapter answers
  `hello.reject{reason:"game"}`. Rule: no new code names `"red"`, `"blue"`, `kanto`, `sgb`, 151 or
  165 as literals; they come from the catalogue, the export or the record.
- **Singleplayer only:** a world built from the other game (`WorldIndex.game` ≠ `hello.ok.game`)
  gets a one-time warning screen per world join. The story works, and wild Pokémon follow the
  world's version. Multiplayer guests may legitimately differ (M9).

## Redaction and logging

Every ROM-derived field (text, names, trainer teams, map and tile data, images, encounter data)
carries `"x-rom-derived": true` in its schema. The derived [`catalog.json`](../protocol/catalog.json)
lists those locations per type, and the tools (`tools/kb_protocol`) and Java (`proto.Redact`) replace
them with `<rom:KIND:LEN:SHA8>` placeholders before anything is logged. gen1-bridge itself logs only
message types, engine symbols, counts, lengths and short hashes. The details are in
[`protocol/README.md`](../protocol/README.md).

## Text

Every text field (text-box lines, choice labels, menu items, titles, naming presets and values) is a
UTF-8 string in this small markup (fixed in M4):

- gen1-bridge sends text **after** the engine's own token substitution (`{PLAYER}`, `{RIVAL}`, …)
  and with PAUSE marks removed, so the client never sees `{…}` tokens.
- **`#` is expanded to `POKé`** by gen1-bridge (in Gen 1 it is a macro, never a literal `#`).
- **`<PK>` and `<MN>`** stay as tokens: each is one Gen 1 glyph (the small "PK" and "MN" that make
  "PKMN"). The client draws them as glyphs, or as `PK`/`MN` in a smaller style. No other `<…>`
  sequence is a token; anything else is literal text.
- **Ligatures** (`'d`, `'l`, `'s`, `'t`, `'v`, `'r`, `'m`) stay as their two ASCII characters; they
  look the same drawn separately.
- **Everything else** is its Unicode character: `é ♂ ♀ × ¥ … ▶`, digits, letters, punctuation.
- Lines carry no line breaks; `pages`/`cont` give the layout (`cont[p][i]` = the line scrolls in after
  the previous one, `\v` in the engine). A box shows two lines at a time and up to 18 glyphs per line.
- **Naming glyphs** (`ui.name.glyphs`, `ui.open{naming}.grid`) are one token per entry, using the
  same markup: `"A"`, `"é"`, `"<PK>"`, `" "`. Multi-character cells count as one glyph toward the
  length limit.

## Directions

| Notation | Hop |
|---|---|
| G→M (`x-legs` value g2m) | gen1-bridge → the Minecraft client, over the socket |
| M→G (m2g) | the Minecraft client → gen1-bridge, over the socket |
| C→S (c2s) | the Minecraft client → the Minecraft server, as a `kantobridge:json` payload |
| S→C (s2c) | the Minecraft server → the client, as a `kantobridge:json` payload |

## Messages

The **ROM** column marks messages with ROM-derived fields. "Data" lists the fields; `?` marks
optional ones. The schemas give the exact types and limits.

### Session

| Type | Dir | Data | ROM |
|---|---|---|---|
| `hello` | M→G | `protocol:1, role:"client"\|"builder"\|"test", mod, minecraft?, player?{uuid,name}, world?{id,multiplayer}` | |
| `hello.ok` | G→M | `protocol, bridge, engine, tested?, union_room?, game, family?, compat?{maxDex,maxMove,nick,visitor?}, session`; `re` required. `game` is the running game's id (any id of the game catalogue, MG1), `family` its engine family and `compat` the adapter's real limits (both from the catalogue when absent). `tested`: the engine version gen1-bridge was tested with (M10); `union_room`: `"on"` while the engine's Union Room patched this boot's Centers (U3); `compat.visitor`: whether the adapter registered its stand-in species (foreign Pokémon, below) | |
| `hello.reject` | G→M | `reason:"protocol"\|"role"\|"malformed"\|"busy"\|"game", protocol`; `re` required. `game` (MG1): the process runs a game this bridge has no engine adapter for | |
| `ping` | G→M, M→G | `t` (sender's ms clock) | |
| `pong` | G→M, M→G | `t` (echoed); `re` required | |
| `resync` | M→G | `{}`; asks for a `snapshot` | |
| `snapshot` | G→M | `ingame` (false at the title screen and intro: `player` is then only the save's last position and must not move the Minecraft player), `player{map,x,y,facing}, state{…player.state}, flags[], toggles{map:{obj:bool}}, blocks[{map,bx,by,block}], lit[], bag{item:qty}, money, coins, badges[], party[{uid?,dex,level,nick?,moves[],hp?,maxhp?,status?}], dex{seen[],owned[]}, npcs{map,list[]}`, and (Wild Routes, WR2) `routes{<zone>:{taken[],wins{}}}`, and (MG2) `away?{map,x,y}` while a `region.park` hold is up; `re` optional (set when answering `resync`). `ui` is reserved and never sent: open UIs follow as `ui.open` messages (UI rules) | yes |
| `log` | G→M | `level, code, msg` (bridge diagnostics, never game text) | |
| `error` | G→M, M→G, C→S, S→C | `code, msg`; `re` optional (the offending message) | |

### Movement

| Type | Dir | Data | ROM |
|---|---|---|---|
| `move.step` | M→G | `dir:"up"\|"down"\|"left"\|"right", n` (counter), `push?`. Sent on a cell crossing, or (`push:true`) after pushing against an edge for 8 or more ticks (exit mats, gates) | |
| `move.face` | M→G | `dir` (turn in place while idle) | |
| `move.hold` | M→G | `button:"b", on` (Cycling Road brake) | |
| `move.ride` | M→G | `dir` (a direction or null), `b`: the rider's held intent while the hidden player is on a bike (BIKE_PLAN D1); sent on change, held like the brake | |
| `player.pos` | G→M | `map, x, y, facing, kind:"walk"\|"bike"\|"surf"\|"ledge"\|"script"\|"spin"\|"current"\|"slope"\|"push"\|"sync"\|"ice", frames, n?`. Sent at step start (target cell) and on non-step changes. `ice` (FA0): optional, sent by no adapter before FA8. BIKE_PLAN `jump?:"low"\|"normal"\|"high"` (Gen 3): the step is a bike's hop (low a bunny hop onto the next cell, normal a side jump, high a wheelie's ledge hop: kind `ledge`, 32 frames) | |
| `player.blocked` | G→M | `dir, reason:"tile"\|"entity"\|"bounds"\|"script", map, x, y, n?` | |
| `player.state` | G→M | `mode:"idle"\|"screen"\|"forced"\|"cutscene"\|"busy", bike, surf, slope, hidden, dark, lit` (all required); SE6 (Gen 3) `weather`: the weather the hidden game draws now (`none`, `clouds`, `sunny`, `rain`, `snow`, `thunder`, `fog`, `ash`, `sand`, `fog_diagonal`, `underwater`, `shade`, `drought`, `downpour`, `bubbles`; Emerald's abnormal weather is drought and downpour in turn, as the game draws it), absent where the game has no weather; FA0 `run?`: the hidden player is running (Emerald's running shoes; optional, sent by no adapter before FA8); BIKE_PLAN `bikeKind?:"bike"\|"mach"\|"acro"` (present whenever `bike` is true: Blue and Crystal `bike`), `trick?:"none"\|"wheelie"\|"hop"` and `gear?:0..2` (Gen 3 only: the Acro's pose, the Mach's gear) | |
| `warp` | G→M | `phase:"start"\|"arrive", kind:"door"\|"stairs"\|"edge"\|"hole"\|"pad"\|"fly"\|"teleport"\|"dig"\|"rope"\|"blackout"\|"script"\|"elevator"\|"ladder"\|"escalator"\|"dive", to?{map,x,y,facing}` (required on arrive; `ladder`, `escalator`, `dive` are FA0's optional values, sent by no adapter before FA8); `re` optional: the `warp.take` it answers | |
| `warp.take` | M→G | `from{map,x,y}, to{map,x,y,facing}, kind:"ladder"\|"hole"`: the player climbed or fell through a cave's shaft to its far end | |
| `warp.taken` | G→M | `ok, reason?:"busy"\|"mode"\|"map"\|"far"`; `re` required | |
| `emote` | G→M | `target:"player"\|<npc id>, bubble:"!"\|"?"\|"happy"\|null, frames` | |

#### Movement rules (M3)

- **Every `move.step{n}` gets exactly one answer carrying its `n`:**
  - a normal step: the `player.pos` sent at its start (kind `walk`, `bike` or `slope`; the target cell);
  - a step that set off a warp without a visible step (exit mats) or moved the player some other way:
    `player.pos` kind `sync`, sent after the warp's `arrive`;
  - a Strength boulder push: `player.pos` kind `push`, `frames` 0, at the player's unchanged cell;
  - an engine refusal: `player.blocked` with `tile`, `entity` or `bounds` at the player's cell (the
    hidden player only turns);
  - `player.blocked` reason `script` when the game isn't idle, the step was dropped from the queue,
    it produced no result within 12 logic steps, or the game is at the title screen.
- **Queue:** at most 2 steps wait behind the one in flight; a third drops the oldest waiting one.
  Requests die with their connection.
- **`move.face`** is answered by a `player.pos` kind `sync` (`frames` 0). **`move.hold`** applies only
  while idle.
- **Kinds:** `ledge` is one message at the landing cell with the whole hop's 32 frames (a hop onto a
  connected map sends the seam step as kind `ledge` on the new map, without `n`); `slope` is the
  Cycling Road roll (8 frames, no `n`); `spin` a spinner tile (48 frames a cell); `current` a Seafoam
  current or push-back (16 frames); `script` covers moves the game makes on its own (cutscenes, door
  walk-outs).
- **Warp kinds** by mechanism: `door` (a door tile or a gate doorway), `stairs` (any other warp tile),
  `edge` (an exit mat or map edge), `pad`, `hole`, `elevator` (a warp out of an `*_ELEVATOR` map),
  `fly`, `teleport`, `dig`, `rope` (Escape Rope), `blackout`, `script`. `arrive.to` is the
  entry cell; a door walk-out follows as its own `player.pos` kind `script`.
- **Field mechanics audit additions (FA0, [specs/FIELD_MECHANICS_AUDIT_PLAN.md](specs/FIELD_MECHANICS_AUDIT_PLAN.md)
  D2, D4):** optional values that no adapter sends before the plan's fixes (FA8): warp kinds `ladder` (a ladder warp),
  `escalator` and `dive` (Dive down or emerge); `player.pos` kind `ice` (one ice-slide cell, a forced move like `spin`;
  the Crystal and Emerald adapters are to send `spin`, `current`, `slope`, `push` and `ice` where Blue already does);
  `player.state.run` (the running shoes). A client that doesn't know a warp kind treats it as `script`, and an unknown
  `player.pos` kind as a forced move.
- **The ride (BIKE_PLAN D1, [specs/BIKE_PLAN.md](specs/BIKE_PLAN.md)):** while the hidden player is on a bike the client
  sends no `move.step` or `move.face`; it sends `move.ride{dir, b}` on each change of its held intent (the movement keys
  as one direction by the camera, sneak or jump as B) and `move.ride{dir:null, b:false}` once on leaving the bike, a warp,
  a link change or a mode other than `idle`. gen1-bridge holds the ride like the brake: pressed on each idle logic step and
  kept across landings, let go of off `idle` and pressed again on the way back; never answered. A `move.ride` with a
  direction drops the waiting steps (`player.blocked{script}` each), and a `move.step` that comes while a ride direction is
  held is refused the same way. The engine's own bike code moves the player: every `player.pos` is a move the client didn't
  ask for (kind `bike`, `slope`, `ledge`; `jump` on a hop). Gen 3 reports a bump of the ridden bike as a `player.blocked`
  without `n` (reason `tile`), at most once per 16 logic steps. `move.hold{b}` and the ride's B share the button: either
  holds it. A client that doesn't know `bikeKind`, `trick`, `gear` or `jump` ignores them.
- **Connection crossings** (walking off a map edge into the connected map) are not warps: the step's
  `player.pos` simply names the new map and cell.
- **A fade is not always a warp** (Gen 3, 2026-10-14): a `script` warp that only a screen transition started, and whose
  transition ends without the map being entered, arrives about a second later at the player's own cell (a cutscene's
  fade, a battle's end); and the fade-in after an arrival starts no new warp. Every other warp arrives when its map is
  entered, or from the player's cell after 1,200 logic steps.
- **Order within one logic step:** `player.state`, then `warp`, then `player.pos`/`player.blocked`.

#### Ladders and holes (H5)

A cave built in 3D (every cave since H7, unless the builder's option `kantobridge.caves3d` names fewer) has real
shafts between its floors where they line up ([specs/CAVES_3D_PLAN.md](specs/CAVES_3D_PLAN.md) §3.6). The player climbs
or falls in Minecraft while the hidden player waits where it was; the hidden game warps when the player reaches the far
end. Every other cave link stays an ordinary warp with a fade.

- **The client** never sends a step onto a shaft's end cell (the hidden game would warp at once). When the player
  arrives at the far end (feet on the far floor going down, at the far end's feet level going up) it sends
  `warp.take`: `from` the near end cell, `to` the far end and the facing to arrive with, `kind` `ladder` or `hole`.
- **gen1-bridge** accepts it only in mode `idle` (else `mode`), on the take's `from` map (else `map`), with the hidden
  player on the `from` cell or next to it (else `far`). It queues the engine's own `warp` command to `to`, as a
  script; a world busy with another script answers `busy`, and the client retries for 1 s.
- **Accepted:** `warp.taken{ok:true}`, then the warp's `start` and `arrive`, both with the take's `re`, kind `stairs`
  (a ladder) or `hole`. The client shows no fade and doesn't teleport: the player already stands at `to`. The warp is
  the engine's own: NPCs go back to their spawns, the map's enter script runs, and the arrival cell can't warp again
  until the player steps off it.
- **Refused, or no arrival within 3 s:** the client fades and puts the player back on the hidden player's cell.

### Interaction

| Type | Dir | Data | ROM |
|---|---|---|---|
| `act.interact` | M→G | `map, x, y` (face the cell and press A) | |
| `act.button` | M→G | `button, how:"tap"\|"down"\|"up"` (mirror screen, start menu) | |
| `act.field` | M→G | `action:"cut"\|"surf"\|"strength"\|"flash"\|"dig"\|"teleport"\|"bicycle"\|"fish"\|"fly"\|"headbutt"\|"rock_smash"\|"whirlpool"\|"waterfall"\|"sweet_scent", x?, y?, dest?, item?` (MG9: the last five are Gen 2's; Gen 2's Fly opens its own map, so `dest` is refused there) | |
| `act.result` | G→M, M→G, S→C, C→S | `ok, kind?, reason?, detail?, moved?, left?, id?`; `re` required. MG2: also answers `region.park`/`region.resume`/`region.start` (kinds `park`, `resume`, `start`; `detail` `new`\|`continue` for a start); MG5: the server's answer to `party.stash` (kind `stash`, `moved`) travels S→C; MG6: the answer to `bag.set` (kind `bag.set`, `left`) is forwarded C→S; FP: the server's refusal of a `party.item` (kind `item`, `ok:false`, `reason`, `id`) travels S→C and the client relays it M→G to the link that sent the item | |

**`act.field` (M8):** each action runs through the hidden game's own entry points after its own
checks (the move in the party, the badge, the place). `x`/`y` name an adjacent target cell (a cut
tree, water). Escape Rope is `action:"dig", item:"ESCAPE_ROPE"`; `fish` without `item` uses the best
rod in the bag; `fly` without `dest` opens the game's own Fly list (a list UI). `act.result.kind`:
`cut`, `surf`, `dismount`, `strength`, `flash`, `dig`, `rope`, `teleport`, `bike_on`, `bike_off`,
`fish`, `fly`, `fly_list`. Reasons: `not_ingame`, `not_idle`, `not_adjacent`, `no_move`, `no_badge`,
`no_target`, `not_here`, `no_item`, `already`, `busy`, `failed`. Strength is activated once, then the
player walks into the boulder twice (the first step bumps, the second pushes). **The client** sends
`act.field` from A or a plain right-click: on a cut tree it still sees standing → `cut`; on water
while not surfing → `surf`; while surfing, on the ground of a land cell → `surf` (answered as
`dismount`). Targets must be adjacent (farther ones are refused locally as `not_adjacent`); one field
request is in flight at a time; refusals show as one translated line over the hotbar.

### UI

| Type | Dir | Data | ROM |
|---|---|---|---|
| `ui.open` | G→M | `ui` (id), `kind:"text"\|"choice"\|"menu"\|"list"\|"quantity"\|"naming"\|"mirror"`, plus per-kind fields (below) | yes |
| `ui.update` | G→M | `ui` plus the changed fields: `index?, top?, page?, line?, waiting?, done?, items?, qty?` | yes |
| `ui.close` | G→M | `ui` | |
| `ui.advance` | M→G | `ui` (A on a text box; queued until ready) | |
| `ui.pick` | M→G | `ui, index` | |
| `ui.back` | M→G | `ui` | |
| `ui.qty` | M→G | `ui, qty` | |
| `ui.name` | M→G | `ui, glyphs[]` (glyph tokens, 0–10; none: nothing typed, the hidden screen's own empty confirm, i.e. its default or no nickname; refused where the screen has presets) | yes |
| `mirror.sub` | M→G | `fps` (1–30) | |
| `mirror.unsub` | M→G | `{}` | |
| `mirror.frame` | G→M | `w, h, png` (base64), `n`: 160×144 on Gen 1, 240×160 on Gen 3 (the engine's own screen, MG7 G3B). About 3–10 KB per frame | yes |

#### UI rules (M4)

- **Indexes are 0-based:** `page`, `line`, `index` (index 0 is the first label or item).
- **Text boxes:** the client may run ahead locally: a press at a stop moves on at once and sends
  exactly one `ui.advance`; gen1-bridge queues it, taps A only when the box is ready, ties it to that
  `ui` id and drops it if the UI closes first. A final press without `ui.close` within 1.5 s may be
  sent again. `auto`/`stay` boxes and boxes with `choice: true` never get a `ui.advance` at their
  end. `ui.update` only moves a box forward.
- **Several UIs can be open** (a choice over its text box); answers go to the top one, at most once
  until it changes. A dropped link closes every UI.
- **After every `snapshot`** (same logic step) gen1-bridge sends `ui.open` for each open UI, bottom
  first, with their ids, then their `ui.update`s, and an `npc.set` (client and test roles). It never
  fills `snapshot.ui`; a client clears its UIs on a snapshot and rebuilds them from these messages.
- **Field meanings:** `waiting` means the ▼ is up, including at the end of a box that A closes. A
  menu's `rows` is min(visible rows, item count); a list's `rows` is its window size. The naming
  `grid` leaves out ED and the case-switch row; a preset name is accepted even when it can't be
  typed on the grid. `emote.bubble` null is a silent hold. `npc.move` kind `script` with 0 frames
  places an NPC without a step.
- **Mirror frames** are dropped, not queued, while the client lags more than 256 KiB behind; `n` counts
  per gen1-bridge instance.
- **`act.result`** answers every `act.interact` and `act.button`. Interact kinds: `npc`, `sign`,
  `door`, `hidden`, `script`, `bookshelf`. Interact reasons: `not_ingame`, `wrong_map`, `off_map`
  (a target across a seam: the engine's A press never reaches another map), `not_idle`,
  `not_adjacent`, `busy`, `nothing`, `no_effect`, `npc_moving`, `npc_moved`. Button reasons:
  `not_idle`, `not_mirror`, `chord`, `busy`, `press_failed`.
- **UI error codes:** `ui.stale`, `ui.wrong_kind`, `ui.timeout`, `bad_message`.
- **The mirror:** after `mirror.sub` gen1-bridge sends the current frame at once, then changed frames
  only, at most `fps` a second.
  - The cap is a sliding window (M10): never more than `fps` frames within a second less 2 ms.
    `debug.status` counts held frames as `mirrorCapped`.
  - The client also uploads at most 30 frames a second.
  - Capture time is counted apart from the per-step cost, as `perfDraw*`.
- **A same-id `ui.open` replaces an open UI in place:** gen1-bridge re-sends a menu or list whose
  footer or money changed (the Mart's money box and clerk line) this way. The Mart's menus carry
  `money`.
- **`act.interact` targets** a cell 1–2 cells away in a straight line from the hidden player's cell
  (2 only across a counter), named in the hidden player's current map: near an outdoor seam `x`/`y`
  can be −1 or one past the map edge.

Per-kind fields of `ui.open`:

| Kind | Fields |
|---|---|
| text | `pages[[lines]], cont[[bool]], auto?, stay?, instant?, choice?` (a choice opens over it when it ends) |
| choice | `labels[], index, question?` |
| menu, list | `title?, items[{label,right?}], index, top, rows, wrap?, screen?, footer?, money?` |
| quantity | `qty, max, unit?` |
| naming | `title, max, presets[], value, mon?, grid?[pages][rows][glyphs]` |
| mirror | `screen?` |

### NPCs

| Type | Dir | Data | ROM |
|---|---|---|---|
| `npc.set` | G→M | `map, list[{id, sprite, kind:"person"\|"item"\|"boulder"\|"static"\|"trainer", x, y, facing, moving}]` | |
| `npc.add` | G→M | `map` plus the NPC's fields | |
| `npc.remove` | G→M | `id` | |
| `npc.move` | G→M | `id, x, y, facing, frames, kind:"walk"\|"script"\|"push"\|"march"` | |
| `npc.face` | G→M | `id, facing` | |

NPC ids are the engine's `<MAP>_obj_<index>`. Sprite ids are engine symbols (`SPRITE_*`); the art
comes from each player's own generated pack.

### Story

| Type | Dir | Data | ROM |
|---|---|---|---|
| `story.flags` | G→M | `set[], clear[]` (diff of `save.flags`) | |
| `story.toggles` | G→M | `map, obj, visible` | |
| `story.blocks` | G→M | `map, changes[{bx,by,block}]` (live map block diff: Cut, doors, scripted swaps) | yes |
| `story.lit` | G→M | `map, lit` | |
| `story.cells` | G→M | `map, cells[{x,y,metatile,pass}]` (Gen 3, MG7 G3C: the current map's cells whose metatile or (SE1) collision class changed, or went back to the map's own; `pass` the live cell's collision class) | yes |

#### Story rules (M8)

The client keeps each story cell of the WorldIndex open or closed for its own player (the
`active` set of `story.state`) from what gen1-bridge reports:

- **`object` cells** (STAY objects: people, item balls, statics, guards, Snorlax): closed while the
  object is visible in the hidden game (on the map of the live NPC list: where a live NPC stands). gen1-bridge reports **effective** visibility: the snapshot's
  `toggles` holds, for every map, each object whose effective visibility differs from its map
  record's `hidden` default (items taken, statics beaten, toggles, …), and `story.toggles{map, obj,
  visible}` reports every change. On the current map the live NPC list (`npc.*`) decides.
- **`tile` cells** (swap sites: cut trees, card-key doors, scripted swaps): closed while the map's live
  block at `(bx, by)` is the cell's **closing block** (`closed.block` in the WorldIndex: the site's
  `before`, or its `after` where only `before` is walkable at that cell). `story.blocks{map,
  changes}` reports the current map's live blocks that differ from the exported map data (after
  scripts, Cut, boulders, map entry); the snapshot's `blocks` holds the same for the current map.
  Other maps are taken to show their `start` block (cut trees grow back when the player leaves a map,
  as in Gen 1; card-key doors are stamped shut on map load). A closed cell's collision follows
  `closed.pass`: `solid` (nobody), `water` (surfers only) or `ground` (everybody; only its art
  differs); an open cell whose built state is water lets only surfers through.
- **`water` cells:** closed unless the player is surfing (`player.state.surf`).
- **`boulder_area` cells:** closed where a boulder stands (the live `npc.*` boulders).
- **`route` cells** (Wild Routes, WR5): a zone's item ball, keyed in its route map's grid, `obj` the ball's id in the
  zone. Closed until the player's route progress (the snapshot's `routes`, then `route.progress`) lists the ball as
  taken; the client decides, as for `object` cells.
- **`story.flags`** reports the diff of `save.flags` every 10 logic steps, when a script ends and on
  a map load; **`story.lit`** reports every dark map while Flash's light holds (or its reset). SE7 (Gen 3): a map
  whose entry scripts set a flash level above 0 (Dewford Gym) is dark (`g3map.dark`); it is lit (`story.lit`,
  `player.state.lit`) only while the hidden player is on it and the game's flash level there is 0; Flash's flag never
  lights it.
- **Details:** `story.toggles.obj` is the map object's name; `visible` back at the default is reported
  too; objects absent from `toggles` are at their default. `story.blocks` is sent on map entry only
  when something differs, and a reload of the same map re-sends blocks that went back to the map data
  (regrown trees); a client drops a map's live-block overrides when the player's map changes. A
  block change no swap site or recorded Cut explains is still reported, and logged by gen1-bridge as
  the error `story.unknown_block` (map symbol and count only). Cut tall grass has no swap site (it's
  cosmetic) but is reported in `story.blocks`.
- **Gen 3 (MG7, G3C):** `story.toggles.obj` is `O<localId>` (the NPC observer's id), and an object is
  hidden while its hide flag is set (Gen 3's own rule), so `toggles` lists exactly the objects whose
  flag is set; the default is visible. Gen 3 has no blocks: its runtime terrain is per cell (metatile
  ids 0–1023, rebuilt by the map's scripts on every load and never saved), so **`story.cells{map,
  cells[{x, y, metatile}]}`** reports the current map's cells whose metatile differs from what the
  client holds: on entry every changed cell, after a reload of the same map also the cells that went
  back (with the map's own metatile), then every change during the visit; the snapshot's `cells` holds
  the current map's changed cells. A client drops a map's cells when the player's map changes.
  **SE1** ([specs/STORY_EVENTS_PLAN.md](specs/STORY_EVENTS_PLAN.md)): each cell also carries **`pass`**, the live cell's
  collision class from its collision byte (g3map's vocabulary: `0x00`, `0x18`, `0x2B`, `0x71`, `0x72` and the ledges
  `0xA0`–`0xA3` are `ground`; `0x29` is `water`; every other byte, `0x07` blocked and `0xFF` void among them, is
  `solid`), and a cell whose collision class changed while its metatile didn't is sent too. The map's own cell is the
  exported one (g3map, with the unconditional changes SE2 bakes in), so a change the export already shows is never sent.
- **Gen 3 cells (SE3, SE4):** a story cell of type **`cell`** (the WorldIndex) is a cell the hidden game's story can
  change (the `g3sites` export), built in its most walkable state (`built{metatile, pass}`); its other states
  (`states[{metatile, pass, height, art}]`) are drawn per player and collide per player. On the hidden player's current
  map a cell is in the live state `story.cells` reported for it (else the map's own, `start`); on every other map it is
  in `start`. In its built state it is open; in another it draws that state's art (by metatile, and by pass where two
  states share a metatile; a live state the table doesn't list draws nothing over the built art) and collides by the
  live `pass`: `solid` always, `water` unless the player surfs, `ground` never.
- **Gen 2 blocks with several states ([specs/STORY_EVENTS_PLAN.md](specs/STORY_EVENTS_PLAN.md) §5.5):** a block that
  scripts turn into several others (the bedroom's decorations: the bed, carpet, plant and poster slots) has no swap
  site's closed state; each of its cells is built in its most walkable state (on a tie the first site's, as a single
  site's) and, where that is ground or water, carries a `cell` story cell whose numbers are **block ids**: `start` the
  map data's block, `built` and `states` the blocks the cell can show. Its live state is its block's in `story.blocks`
  (a block the client wasn't told is the map data's); it is drawn and collides as a Gen 3 cell does, by the matched
  state's `pass` (`story.blocks` carries none). A cell built solid (the poster on the wall) carries none: its built
  art stays. Such a cell takes the place of an object's story cell (the big doll and the console stand on the
  carpet), so on the live NPC list's map any `cell` cell an NPC stands on collides whatever its state.

The client draws closed `tile` cells with the site's `before` art (per player, never in the world's
blocks) and relays its active set with `story.state` (full on join, deltas after); the server uses
it for that player's collision.

### Battles

| Type | Dir | Data | ROM |
|---|---|---|---|
| `battle.start` | G→M, C→S | `battle` (id), `kind:"trainer"\|"rival"\|"scripted"\|"static"\|"surf"\|"fish"`, `trainer?{cls,party,name?,npc?,map?,x?,y?}`, `team?[{dex,level,moves[],dvs{atk,def,spd,spc} or ivs{hp,atk,def,spa,spd,spe}, item?, shiny?, gender?, form?}]`, `wild?{dex,level,x?,y?,dvs?,item?,shiny?,gender?,form?}`, `rules{catch,flee}`, and (foreign Pokémon) `use?[uid]` (the player's Pokémon Cobblemon may use; absent: the whole party), `format?:"singles"\|"double"\|"multi"` (absent: singles), `ally?{name?, team[]}` (only with `format:"multi"`). Trainer and rival battles need `trainer` and `team`; static, surf and fish need `wild`; scripted needs `team` or `wild`. `party` is the party index within the class (Gen 1), the trainer's id (Gen 3, up to 1023) or the member number within the class (Gen 2) | yes |
| `battle.result` | S→C, M→G | `battle, outcome:"win"\|"lose"\|"fled"\|"caught"\|"aborted"`, `caught?{uid,dex,level}` (required when caught) | |
| `battle.native` | G→M | `kind:"safari"\|"old_man"\|"ghost"\|"contest"\|"tower", phase:"start"\|"end"` (MG9: Gen 2's Bug-Catching Contest and Battle Tower; its catch tutorial is `old_man`) | |

#### Battle rules (M5)

- **Who fights:** the server runs every non-native battle in Cobblemon (`GEN_9_SINGLES`) against a
  hidden battle-proxy NPC placed at `trainer.map/x/y` (the trainer's cell, if within 32 blocks;
  otherwise 4 blocks in front of the player). No client ever sees the proxy; the fighter's client
  draws the trainer as a puppet. `trainer.name` is the name in Cobblemon's battle UI.
- **AI skill** from `trainer.cls` (`OPP_*`, prefix optional): gym leaders (including
  `OPP_GIOVANNI`) 3; the Elite Four and `OPP_RIVAL3` 5; `OPP_RIVAL1`/`OPP_RIVAL2` or kind `rival` 3;
  everyone else 1. Gen 3 (MG7) sends roles, `TRAINER_CLASS_*`: `LEADER`, `AQUA_LEADER` and
  `MAGMA_LEADER` 3; `ELITE_FOUR` and `CHAMPION` 5; `RIVAL` or kind `rival` 3; everyone else 1.
  Team members get IVs 2×DV+1 (HP from the derived HP DV) or the `ivs` as given (Gen 3), nature
  Hardy, the species' first ability, no item, exact moves (a repeated move is kept once).
- **Outcomes:** `win`; `lose` (a forfeit is a loss); `aborted` when the battle stopped with no winner,
  or couldn't start (no usable Pokémon, already in a battle, malformed start, and until M6 the wild
  kinds `static`/`surf`/`fish`); `fled` and `caught` from M6. A scripted battle with a team runs like a
  trainer battle. Exactly one `battle.result` per `battle` id, about 2 s after the deciding hit.
- **Ordering:** a gift's `party.give` must reach Minecraft before a `battle.start` that needs that
  Pokémon (the client flushes queued `party.*` before relaying a `battle.start`).
- **Repeats are safe:** a repeated `battle.start` with the same id is ignored by the server and
  answered by the client from its record (results kept 60 s after delivery), so gen1-bridge re-sends
  pending battles after a snapshot. With no server link the client answers `aborted` at once.
- **The Oak's-lab rival battle:** gen1-bridge continues the story on a loss, as Gen 1 does; every
  other trainer loss is a blackout.
- **Kinds (gen1-bridge):** a script's `start_battle` against a rival class (`OPP_RIVAL*`) is `rival`;
  other script-started trainers are `scripted`; map trainers (sight or talk) are `trainer`; scripted
  wild battles are `static`. `trainer.party` is the engine's 1-based party index.
- **gen1-bridge holds the hidden game** (mode `busy`) from `battle.start` to `battle.result`, re-sends
  the same `battle.start` after every snapshot, and only takes battles over while a client or test
  session is connected (otherwise the hidden game fights natively, on the mirror).
- **Results on the Gen 1 side:** `win` applies the engine's own effects (defeated trainer, event
  flag, prize money, `afterBattle`) and shows the trainer's loss line and prize text; `lose` shows the
  win line and runs the blackout (except the lab rival); `aborted` applies nothing and re-arms the
  battle (an aborted scripted battle ends its script so its trigger can run again); `fled`/`caught`
  for a trainer battle are treated like `aborted`. Expect text UIs right after a `battle.result`. A
  result gen1-bridge isn't waiting for is answered `error{code:"battle.unknown"}`.

#### Wild encounters, bag and Pokédex (M6)

- **Grass and cave rolls** are suppressed in the hidden game while a client or test session is
  connected: Cobblemon's habitat pools spawn those Pokémon in Minecraft.
- **Native special battles** stay in the hidden game and are announced with `battle.native{kind,
  phase}` (start, end, and start again after a snapshot): the Safari Zone, the old man's demo, and
  Pokémon Tower ghosts while the player lacks the SILPH_SCOPE (with it, Tower rolls are suppressed
  like any indoor roll).
- **Surfing and fishing:** the hidden game rolls (its own species, level and REPEL rules) and sends
  `battle.start{kind:"surf"|"fish", wild{dex, level, x, y}, rules{catch:true, flee:true}}`, holding
  the world; `wild.x/y` is the water cell in front of the player. The fishing bite text and animation
  stay native. Results: `win`, `fled` and `caught` run the engine's wild-battle ending; `lose` shows
  the blackout text and blacks out; `aborted` changes nothing. A `caught` result carries
  `caught{uid, dex, level}`; the Pokémon then appears in `dex.sync` and `party.sync`.
- **`bag.sync`** is the full map of the battle-usable items (Appendix B) held with quantity ≥ 1
  (absent means 0), never key items, sent while in game after every snapshot and on any change. A
  `bag.sync` can cross `bag.use` messages still in flight; Minecraft reconciles them.
- **`bag.use`** is applied in the hidden game (queued at the title), never below zero, and answered by
  the resulting `bag.sync`; an item that isn't mirrored gets `error{code:"bag.not_mirrored"}`.
- **`dex.sync`** is the only source of the hidden game's Pokédex: applied in game as a union (owned
  implies seen; nothing is ever cleared), newest wins, re-applied after New Game or CONTINUE.
- **`party.item`** (an item used from the Gen 1 bag on a party member with a uid, outside battles,
  while a client is connected): stones that evolve the species and Rare Candy below level 100 skip
  their Gen 1 effect and are applied by Cobblemon at once; TMs/HMs are taught natively (the Gen 1
  forget prompt picks the slot) and Cobblemon force-teaches the same move (`slot` 0-based, present
  only when a move was replaced). Each `party.item` is sent exactly once (it has no id), so the client
  queues it while offline. **FP4:** in all three games, with an `id` (Cobblemon applies an id once) that
  gen1-bridge sends again after a snapshot or on a new connection until a `party.sync` follows it; see
  "Foreign Pokémon" below.
- **`party.sync.give`** is matched exactly when present (box gifts included); the dex-and-level
  fallback applies only when `give` is absent.
- **Order within one logic step:** `party.*`, then `bag.sync`, then `battle.native`/`battle.start`.

#### Saving, PC and blackout rules (M7)

- **`save.begin` reasons:** `center` for the save gen1-bridge makes right after a nurse heal (with
  `Game:writeSave()`, once her lines are done and the engine's own quick-save gate is open); `menu`
  for every other hidden-game save (start menu, F1, Hall of Fame). Ids are unique per gen1-bridge
  process. Saves made while no client is connected collapse to the newest and go out after the next
  `hello` (so a `save.begin` can arrive right after the handshake).
- **`save.done`** is log-only on the Gen 1 side (its own save already happened and is never rolled
  back); an unknown or repeated id is counted, never answered with an error. Minecraft answers once
  per id, `ok:false` on failure; `re` is set only when the `save.begin` came on the current
  connection.
- **`session.bound.slot`** is the engine's save slot number, 0 for the flat legacy save file.
  gen1-bridge answers every (repeated) `session.bind`. Since M9 the world → slot pairing is kept
  installation-wide (Multiplayer rules below).
- **Bill's PC:** choosing the Pokémon storage entry sends one `pc.open{pc}` and holds the hidden game
  over its still-open PC menu until the `pc.closed` with the same `pc` id (M10).
  - Another id is counted and ignored, and so is an unexpected `pc.closed`.
  - A dropped link releases the hold.
  - The item PC stays native.
  - The server sends exactly one `pc.closed` per `pc.open`, echoing its id, at once if the PC
    can't open. A second `pc.open` while a PC is open is served by the open screen, and its close
    answers the newest id.
  - The client answers by itself, with the id, after 5 s if Cobblemon's PC never shows, and at
    once while it is pairing a save.
- **Heals:** `party.heal` is also sent at the nurse's heal. After a blackout it follows the
  `warp{arrive, kind:"blackout"}`. A blackout lands where the engine puts it: the fly spot of the last
  heal point's town, in front of its Pokémon Center (that map must be built in the world).
- **Order within one logic step:** `party.*`, `bag.sync`, `save.begin`, `save.loaded` (M10),
  `pc.open`, then `battle.*`.
- `debug.status` adds `save*`, `pc*`, `money`, `bagTotal`, `bagKinds`, `defeated`, `healHeld`,
  `centerArmed`, `saveQueued`, `pcHeld`.

#### Party rules (M5)

- `party.sync` (Cobblemon is authoritative) is debounced, **never sent during a battle**, and sent
  after each battle (after its `battle.result`). The client re-sends the latest `party.sync` and
  `dex.sync` after every gen1-bridge snapshot.
- `party.give` is applied once per `give` id (stored on the Pokémon, so it holds across restarts);
  it fills the party, then the PC. Gifts keep Cobblemon's random nature and ability; `ot` is used only
  for `source:"trade"`. Cobblemon's starter prompt is locked: the starter comes from the hidden game.
- `party.rename` is the only way a nickname changes (Cobblemon's own rename is cancelled).
  `party.take` handles trades (daycare in M8); `party.item` arrives in M6.
- **Gifts on the Gen 1 side:** `give_pokemon` runs natively (its nickname prompt reaches Minecraft as
  text, YES/NO and naming); once the prompt is done gen1-bridge sends `party.give` (`source`
  `starter`, `prize` or `gift`; `trade` and `native_catch` from M6/M8) and re-sends it after each
  snapshot until a `party.sync` shows it. The engine's own copy stays in the hidden party meanwhile,
  and the sync replaces that same member (matched by dex and level), so there is never a duplicate. A
  gift put in a Gen 1 box is taken out of it, because Cobblemon's PC keeps it. gen1-bridge gives up on
  a gift after 5 syncs and 2 minutes without it.
- **`party.sync` on the Gen 1 side** rebuilds the hidden party in place (species from dex, level,
  moves by Gen 1 number, HP, status), keeping uids in the mod's save data; species and moves the game
  can't hold are left out with counted warnings (`party.foreign_species`, `party.foreign_moves`; FP2 makes such
  members visitors); a sync waits while a gift is pending, at the title
  screen, or under a native battle.
- **`party.heal`** is sent on the engine's `heal_party` (Mom, …) and on a blackout.
- **Content rules (M8):** direct battles the engine pushes itself are held too: the ghost Marowak as
  kind `static` with `rules.catch:false` (`wild.x/y` the cell in front of the player), Giovanni in the
  hideout as `scripted` (with trainer and team); `aborted` ends a direct battle as a flight; an aborted
  `static_battle` whose script hid its object first (Snorlax waking) shows the object again.
  `party.take`, `daycare.deposit` and `daycare.withdraw` carry no id, so gen1-bridge re-sends them
  after snapshots until a `party.sync` confirms them (uid absent for take and deposit, present for
  withdraw; taken and boarded uids never re-enter the hidden party meanwhile); Minecraft treats
  repeats as idempotent by uid. `withdraw.steps` = the Gen 1 daycare's experience ÷ experience per
  step (normally 1). `native_catch` gifts are sent after the native battle ends (nickname included;
  box catches leave the Gen 1 box; never the old man's demo). Trades carry `ot` and the trade's
  nickname. `party.rename` is sent once per Name Rater change (`""` clears) and queued while offline.
  The Hall of Fame also sends `party.heal`. During held battles the hidden game plays the matching Gen 1
  battle theme (P22), and the victory theme after a win with texts. After a `win` the trainer's own
  defeat line (gym leaders' badge lines with their jingle) shows before the prize, as in Gen 1
  (`debug.status`: `trainerStarts`, `trainerStartsFailed`, `lossLines`, `lossLineSounds`).
- **The daycare (M8):** `daycare.deposit{uid}` (and `party.take{reason:"daycare"}`, treated the same,
  idempotent) moves the Pokémon from the Cobblemon party into a per-player daycare store on the
  server; `daycare.withdraw{uid, steps}` returns it to the party (the PC if full) with 1 XP per step,
  never past level 100. Repeats change nothing; mismatches are counted warnings. `party.sync` follows.
- **Gen 3's trades, daycare and eggs** (2026-10-15, docs/SEVERAL_GAMES_FOLLOWUPS.md item 7): the same
  messages as Gen 1's. An in-game trade (the engine's swap, `Trade.tradeMons`) is `party.take{reason:
  "trade"}` for the member sent and `party.give{source:"trade"}` (its nickname and `ot`) for the one
  received, sent once the trade's script has ended. Emerald's daycare holds two, each its own
  `daycare.deposit` and `daycare.withdraw`; `steps` is what the daycare counted for that member (the
  experience the engine gave it). The boarded uids are kept in mod.save with each member's personality
  value, so a CONTINUE finds them again, and `save.loaded` names them all (`daycare` the first,
  `boarded` every one when there are several). **Eggs never cross:** Cobblemon has no eggs, so the
  hidden game keeps them (every `party.sync` keeps them after Cobblemon's members; with no room left
  they wait in a hidden box and come back when there is room). An egg that hatches is
  `party.give{source:"egg"}`, sent after the hatch screen and its nickname prompt.
- `debug.status` (test role) adds `battle*`, `party*`, `giftsPending`, `healsPending`, `battleHeld`,
  `partySize`, `partyKnown`, and `playerNameLen`/`playerNameHash`, `rivalNameLen`/`rivalNameHash`
  (the first 7 hex digits of SHA-256, as an integer).

### Party, Pokédex, bag, PC

| Type | Dir | Data | ROM |
|---|---|---|---|
| `party.sync` | S→C, M→G | `party[{uid, give?, dex, level, nick?, moves[], hp, maxhp, status?, item?, shiny?, gender?, form?, friendship?, stats?{hp,atk,def,spa,spd,spe}, types?[], nature?, ot?, canLearn?[], stones?[], name?}], box{count}` (Cobblemon is authoritative; `dex` 0: a species with no national number) | yes |
| `party.give` | G→M, C→S | `give` (id), `dex, level, moves[]?, dvs?, ivs?, nick?, ot?, item?, shiny?, gender?, form?, friendship?, source:"starter"\|"gift"\|"trade"\|"prize"\|"native_catch"\|"egg"` (`ivs` over `dvs` when both; `egg`: an egg that hatched in the hidden game) | yes |
| `party.take` | G→M, C→S | `uid, reason:"trade"\|"daycare"\|"quest"` (`quest`: a quest took it, Kenya, Shuckie) | |
| `party.heal` | G→M, C→S | `{}` | |
| `party.rename` | G→M, C→S | `uid, nick` (empty clears) | yes |
| `party.item` | G→M, C→S | `item, uid, id?, kind?:"stone"\|"machine"\|"candy"\|"medicine"\|"pp"\|"vitamin"\|"other", slot?, move?, forget?` (an item from the hidden bag; a refusal is answered `act.result{kind:"item"}`) | |
| `party.order` | G→M, C→S | `uids[]` (a SWITCH in the hidden party menu: the new order) | |
| `party.change` | G→M, C→S | `uid, id, hp?, status?:"slp"\|"psn"\|"brn"\|"frz"\|"par"\|"none", friendship?` (an effect outside items: overworld poison, a haircut; at least one value) | |
| `party.hold` | G→M, C→S | `uid, item\|null, id` (GIVE/TAKE in the hidden party menu; `item` a canonical id, null for TAKE) | |
| `party.learn` | G→M, C→S | `uid, move, forget?, id` (a Move Tutor or the Move Reminder) | |
| `party.forget` | G→M, C→S | `uid, move, id` (the Move Deleter) | |
| `dex.sync` | S→C, M→G | `seen[dex], owned[dex]`: every species the server can name (MG7: the first 386); each bridge keeps the ones its game holds (its compat `maxDex`) and counts the rest | |
| `bag.sync` | G→M, C→S | `items{item:qty}` (battle-usable subset), and (MG6) `money?`, `stamp?` (the ledger stamp of the last projection) | |
| `bag.use` | S→C, M→G | `item, qty` (consumed in Minecraft) | |
| `bag.set` | S→C, M→G | `items{item:qty}, money, stamp` (MG6: the ledger's projection into the followed game's save on a resume or after a start) → `act.result{kind:"bag.set", left?}` (reasons `not_ingame`, `busy`, `too_many`) | |
| `pc.open` | G→M, C→S | `pc` (id, M10) | |
| `pc.closed` | S→C, M→G | `pc` (the `pc.open`'s id) | |
| `daycare.deposit` | G→M, C→S | `uid` | |
| `daycare.withdraw` | G→M, C→S | `uid, steps` | |

**A Pokémon's details (MG9, G2F):** a game with held items, shininess, genders and forms (Gen 2 on) carries them
in `battle.start` (team members and the wild), `party.give` and `party.sync`: `item` is the held item as the item
catalogue's **canonical id** (`protocol/items.json`, so one `party.sync` reads alike in every game; each adapter maps
it to its own symbol), `shiny` a boolean, `gender` `male`/`female`/`genderless`, `form` a form id (Unown's letter,
`a`..`z`), `friendship` 0–255 (gifts and party members). All optional: Gen 1 and Gen 3 send none and ignore them.
Minecraft applies shininess, gender, the held item (when Cobblemon has that item) and friendship; a form is carried
but not applied (Cobblemon's Unown has no letters). Gen 2's DVs are Gen 1's shape (`dvs`), and a Gen 2 game builds a
new party member's DVs so that its gender, shininess and letter match what Cobblemon's member has.

**Foreign Pokémon (FP1, [specs/FOREIGN_POKEMON_PLAN.md](specs/FOREIGN_POKEMON_PLAN.md) §8):** every Cobblemon
party member gets a record in each hidden game; one the game can't show as itself (a species over its `compat.maxDex`,
`dex` 0, a form it lacks) is a **visitor**, the adapter's stand-in species carrying the member's name, level, HP, stats
and field moves. So `party.sync` members carry, besides today's fields: `form` (Cobblemon's Showdown form id, absent for
the standard form), `stats` (Cobblemon's six numbers, pinned on every record with a uid), `types` (lower-case Showdown
names), `nature`, `ot` (only when the original trainer isn't the owner), `canLearn` (the TM/HM move numbers Cobblemon's
learnset allows; each adapter keeps its game's machines) and `stones` (the canonical ids of the stones that would evolve
it now, the evolution rules applied); a species with no national number goes out as `dex: 0` instead of being dropped.
The hidden game reports, Cobblemon decides (rule R6): a SWITCH is `party.order`, an effect outside items `party.change`,
GIVE/TAKE `party.hold`, tutors `party.learn`, the Deleter `party.forget`, a quest that takes a Pokémon `party.take{reason:
"quest"}`; each carries an `id` where a repeat would double an effect, and Cobblemon applies an id once. `party.item`
gains `id`, `kind` and `forget` (the move replaced, by number); refusals come back as `act.result{kind:"item", ok:false,
reason, id}` with reasons `unknown_uid`, `no_slot`, `move_known`, `no_evolution`, `max_level`, `no_effect`,
`unsupported`. `hello.ok.compat.visitor` says whether the stand-in registered (false: such members are left out, as
before); `session.bind.incompatible` carries the region's choice (`visitor`, the default, or `leave_out`);
`game.status.visitors` counts the visitors; `battle.start` gains `use`, `format` and `ally` for the hidden game's own
battles handed to Cobblemon (§4.12). The warnings for members and moves a game can't hold are `party.foreign_species`
and `party.foreign_moves` (they were `party.non_gen1_*`), and a nickname is cut to the game's `compat.nick`. The
messages and fields are defined now; each phase of the plan starts sending or applying its own (PROGRESS.md "Foreign
Pokémon"). **FP2 (Blue):** gen1-bridge registers the stand-in and says `compat.visitor: true`; a member Blue lacks is a
visitor record in its place, named from the sync's `name` (Cobblemon's species name) when it has no nickname; the
snapshot's party reports a visitor's real `dex` (0 allowed) with `visitor: true`; `game.status.visitors` is sent; a
stone used from Blue's bag is answered from the sync's `stones` and goes out as `party.item{kind:"stone"}`; Cobblemon
lets a Pokémon evolve past its region's ceiling unless the region record's `rules.compat.evolveBeyond` is false (Q9).
**FP4 (items, all three games):** a stone, Rare Candy, TM/HM, medicine, PP item or vitamin used from the hidden bag on a
member with a uid goes out as `party.item` with an `id` and its `kind`: the hidden game's own check decides (a stone from
the sync's `stones`, a machine from its `canLearn`), medicine and the PP family run natively on the record first (the
next sync rewrites it), stones and Rare Candy leave the evolution and level-up to Cobblemon, a PP item names its `move`,
a machine names the move it replaced (`forget`). The server applies Cobblemon's own item for the catalogue entry (the
mirror's item, else Cobblemon's item of the same canonical id; a revive brings back half the HP, a Max Revive all of
it), ignores an id it already applied, and answers a refusal with `act.result{kind:"item", ok:false, reason, id}` (re:
the item's seq), which the client relays to the link that sent the item as the reply to that `party.item`.
**FP4 (effects and moves, all three games):** gen1-bridge compares each member with a uid against its last look (after a
sync, a heal or a new save; not while no client follows, and not on a member an item was just used on): changed HP,
status or friendship goes out as `party.change`, a move added as `party.learn` (with `forget`, the move it replaced), a
move gone as `party.forget`, each with an `id` and re-sent like an item. The server sets the value (HP within Cobblemon's
maximum, `status:"none"` clears), teaches as a machine does, or benches the move (never the last one); an id applies once.
**FP4 (held items):** the watcher also sends GIVE/TAKE in the hidden party menu as `party.hold{uid, item | null, id}`
(canonical ids); items the catalogue marks `hiddenOnly` (Mail, ...) or maps to no Cobblemon item stay in the hidden game
and are never sent, and a sync whose member holds nothing leaves them on the record. Emerald's syncs and gifts carry the
held item through `symbols.gen3`. The server holds a new stack of the Cobblemon item (or removes it); an item the region's
game couldn't show goes back to the player's inventory.
**FP5 (the hidden games' own battles):** Crystal's Bug-Catching Contest battles are `battle.start{kind:"contest",
use:[lead]}` (the server fights with only that Pokémon; a catch is the contest's, and the one let go at its question
leaves as `party.take{reason:"contest"}`); Emerald's Steven battle is `format:"multi"` with `use`, `ally{name, team}` and
`split` (Cobblemon's `GEN_9_MULTI`, the ally and foes as trainer actors). Crystal's Battle Tower desk and Emerald's
Frontier and Tent desks refuse while a client follows, and `game.status.refused{feature, n}` gives the HUD its note;
Trainer Hill is held like any trainer; every battle Emerald leaves native is announced (`battle.native` kind `facility`
for rentals and facilities).

Species travel as national dex numbers (defs `nationalDex`), moves as Showdown numbers (defs
`moveNum`; Gen 1's move ids are the same numbers), items as engine symbols, and Pokémon identities
as Cobblemon UUIDs (`uid`). The schemas carry any number up to 2000; what a game holds is its
compat profile (`hello.ok.compat`: Gen 1 151 and 165, Emerald 386 and 354), applied by its bridge.

### Saving

| Type | Dir | Data | ROM |
|---|---|---|---|
| `save.begin` | G→M, C→S | `save` (id), `reason:"menu"\|"center"`, `pair` (the hidden save's lasting id), `at?` (its time, Unix s) | |
| `save.done` | S→C, M→G | `save, ok`; `re` optional | |
| `session.bind` | S→C, M→G | `world, multiplayer, incompatible?:"visitor"\|"leave_out"` (save pairing: the server tells its client the world id; the client passes it on after joining and after every reconnect; `incompatible`, M→G: the link's region record's `rules.compat.onIncompatible`, absent = `visitor`) | |
| `session.bound` | G→M, C→S | `slot, switched?:"none"\|"title"\|"saved"\|"moved"`; `re` optional (the client records the pairing on the server) | |
| `session.waiting` | G→M | `slot, reason:"busy"\|"new_game"\|"save_failed"`; `re` optional (the bind) | |
| `save.loaded` | G→M, C→S | `fresh, ops, pair?, at?, daycare?, boarded?` (which save the hidden game runs from; M10 crash recovery; `boarded`: every uid the daycare holds when it holds more than one) | |
| `save.mismatch` | S→C | `kind:"hidden_behind"\|"world_behind"\|"hidden_older"\|"hidden_newer"\|"new_game"\|"other", ops?, hiddenAt?, worldAt?` | |

#### Crash recovery and save pairs (M10)

**When the link drops:** if one side crashes or closes, the other pauses, then they resync.

- **The client:** when the link has been down for more than 1.5 s in a Kanto world, it shows a
  pausing screen. That pauses singleplayer's integrated server and locks the player's input.
  - The screen says why (connecting, rejected, not answering, version mismatch), gives the port
    and the retry countdown, and says what to do. For "not answering" it names Gen1Recomp's crash
    log by its path; nothing ever opens it.
  - Esc opens the vanilla pause menu. The overlay closes itself when the link is ready.
  - It never shows during the export or world-build flows.
  - While it is up, the player's `activity` is `paused`, and other players see a ⏸ bubble.
  - `tools/launch.ps1 -Watch` restarts a Gen1Recomp that closed while Minecraft runs.
- **gen1-bridge:** when its client drops, it releases every hold (PC, UI, steps and buttons).
  Battle waits stay pending for the next client.
- **Running hidden:** a minimised Gen1Recomp window already runs at full speed on Windows (measured
  in M10: LÖVE still reports it visible). A window hidden with `SW_HIDE` drops to the engine's
  10-frame cap, which is about 20 logic steps a second.
  - While a client or test session is connected, gen1-bridge wraps `love.window.isVisible`. A
    hidden window then keeps its normal cap and keeps drawing (the mirror needs it), and the frames
    it fakes are paced to 60 a second.
  - A minimised window is paced the same way, so it draws about 60 frames a second instead of
    hundreds. gen1-bridge never fakes the answer for a minimised window.
  - With no client connected, the engine's own behaviour is untouched.
  - The wrapper comes off when the link drops and on a hot reload.
- **Resync:**
  1. `hello` → `snapshot` → open UIs again → `session.bind`/`bound`.
  2. With `switched:"none"`, held messages go on. Otherwise they are dropped and the client sends
     `resync`.
  3. Then come gen1-bridge's queued messages, then `save.loaded`.
  4. Position and story come from the hidden game (position only when `ingame`). Pokémon come
     from Cobblemon (`party.sync`/`dex.sync` after the bind).

**Save pairs** let the server notice when a crash left the two saves out of step.

- **The pair id:** every hidden save gets a lasting `pair` id, carried by `save.begin`.
  - It is 16 hex digits.
  - gen1-bridge writes `{pair, at, ops}` into the save's own mod data when the engine announces
    the save, after the engine stamps `savedAt`. The same file write stores it, and CONTINUE
    restores it with the save, so a pair can never get out of step with its save.
  - A save from before M10 gets a pair derived from its own metadata, the same on every load.
  - The save gen1-bridge writes just before a slot switch (M9) keeps the leaving world's last
    pair. Its `ops` counts the operations sent plus those the switch drops unsent, so returning to
    that world flags exactly the Pokémon that never reached Minecraft.
  - The server keeps the player's last pair (`pair`, `at`, `ops`) in a persistent attachment,
    `kantobridge:save_pair`. It writes it before the save writes the player's data.
  - A repeated pair keeps its count. A failed Minecraft save keeps the pair.
- **`ops`** counts distinct Pokémon operations since that save: `party.give`, `party.take`,
  `daycare.deposit` and `daycare.withdraw`.
  - Each operation counts once, when gen1-bridge first sends it. The re-sends gen1-bridge makes
    after a snapshot don't count.
  - The server skips the same repeats: a gift whose `give` id is already on a Pokémon, a take or
    deposit of a Pokémon that's no longer there, a withdrawal of one not boarded, or the same
    operation on the same subject twice in a row.
  - Both sides reset the count where the stream puts the `save.begin`. gen1-bridge also resets it
    on CONTINUE (and F2) and on New Game.
  - An operation the hidden game makes after a save it hasn't announced yet (no client connected)
    waits until that save's `save.begin` has gone out, so both sides count it after the save.
- **When `save.loaded` comes:** gen1-bridge sends it after `session.bound` while in game, after its
  queued messages. It also sends it whenever the hidden game enters the game from the title:
  CONTINUE gives `fresh:false` with the loaded save's `pair`, NEW GAME gives `fresh:true`.
  - The client holds it in the pairing gate like other story data.
- **`daycare`** is the uid in the hidden game's **live** daycare. Right after a load, that is what
  the loaded save holds. **`boarded`** (2–3 uids) names every one when the daycare holds more than one
  (Gen 3's holds two); `daycare` is then the first of them.
- **The server compares** `save.loaded` with the stored pair, and sends `save.mismatch` or nothing:

  | Stored pair | `save.loaded` | Result |
  |---|---|---|
  | none | anything | nothing (first pairing) |
  | any | `fresh:true` | `new_game` |
  | same `pair` | same `ops` | nothing |
  | same `pair` | fewer `ops` | `hidden_behind` (`ops` = the difference): the hidden game went back to its save. Pokémon received since then are still in Minecraft, and the story may give them again |
  | same `pair` | more `ops` | `world_behind`: Minecraft went back to an older save. Pokémon the story gave since then are missing |
  | another `pair` | — | `hidden_older` or `hidden_newer`, by `at` when both are known, else `other` |

  The client shows each distinct mismatch once per world join, as a warning screen with an OK
  button. It pauses singleplayer only.
- **Daycare reconcile (every `save.loaded`):** if the server's daycare holds a Pokémon for this
  player whose uid isn't `save.loaded.daycare` (or in `boarded`), it goes back to the party, or to the
  PC if the party is full. A `daycare.withdraw` of a Pokémon already in the party or PC is a no-op, so it never
  makes a duplicate.

### Wild Routes (WR2)

| Type | Dir | Data | ROM |
|---|---|---|---|
| `route.give` | S→C, M→G | `route, id, item, keep?` (a zone's item ball; WR7: `keep:false` for a dungeon's item, not recorded) | |
| `route.battle` | S→C, M→G | `route, id, class, party[{species, level, moves[]?}], lines{before?}?` (a zone trainer) | yes |
| `route.blackout` | S→C, M→G | `{}` (a wild battle lost in Cobblemon) | |
| `route.result` | G→M, C→S | `op:"give"\|"battle"\|"blackout", ok, route?, id?, outcome?:"win"\|"lose"\|"abort", reason?`; `re` required | |
| `route.progress` | G→M, C→S | `routes{<zone>:{taken[id], wins{id:n}}}` (the zones whose progress changed, each whole) | |
| `route.sight` | S→C | `route, id, x, y, frames` (WR5: a zone trainer saw the player; it walks to cell `x, y` of the zone's route-map grid over `frames` after its "!") | |
| `route.fade` | S→C | `ticks` (WR7: the server moves the player in about that many ticks: into a dungeon, down its stairs, out) | |

#### Wild Routes rules (WR2)

[specs/WILD_ROUTES_PLAN.md](specs/WILD_ROUTES_PLAN.md) §4.1-4.2 and `gen1-bridge/lib/kb_routes.lua`.

- **The zones** are listed in `gen1-bridge/data/routes.json` (ids, map symbols, cell coordinates,
  counts and our own text; never map contents). At boot, before the engine's merge, gen1-bridge
  registers for each zone of the running game: the route map `KB_ROUTE_<ID>` (one block of the
  clearing's floor, 2×2 cells: A at (0, 0), arrival x1 at (1, 0) facing right), two step scripts
  (the clearing's last cells warp to x1; A warps back to the clearing's second-to-last cell, facing
  the town; both are queued scripts of the engine's own `warp`, followed as warp kind `script`),
  `KB_<CLASS>` copies of the zone's trainer classes, the route map's song when the music id exists,
  and last the town's patched `blocks`. A zone whose spot is unset, or that fails the signature
  check (the spot's block walkable, every opened block unwalkable and inside the map, nothing
  standing in it, the clearing walkable afterwards), registers nothing.
- **`route.give`** runs the engine's `give_item` in a queued script; `route.result.ok` says the item
  went into the bag (the bag's count rose). Reasons: `unknown_route`, `not_ingame`, `not_in_zone`
  (the hidden player isn't on the zone's route map), `taken`, `unknown_item`, `bag_full`, `busy`.
- **`route.battle`** writes `party` into the zone's `KB_<CLASS>` record, then a queued script shows
  `lines.before` (literal text) and runs `start_battle trainer KB_<CLASS> 1`: the battle itself is
  the bridge's script battle (`battle.start` / `battle.result`, prize money and blackout as for any
  scripted trainer). The answer comes when the script ends, with `outcome` `win`, `lose` or `abort`.
  Reasons: `unknown_route`, `not_ingame`, `not_in_zone`, `unknown_class` (the zone doesn't list the
  class), `bad_party` (a species the game doesn't have), `failed`, `busy`.
- **An end the adapter runs** (Gen 3 since MG8, Gen 2 since MG9 G2H): `route.give` and `route.battle` name a route,
  and the zone is that route's end in this game, its far (`to`) end too (when one game holds both, the one whose route
  map the hidden player stands on). The adapter gives the item with its own found line and starts the battle with the
  class's first trainer as the template and `party` as its team (by the game's own names), and answers once its
  lines are over (a line waiting for its press never times out the request).
- **`route.blackout`** runs the engine's own loss handling anywhere in the game (heal, half the
  money, the warp to the last heal point; its `party.heal` follows the warp's arrival as for any
  blackout). Reasons: `not_ingame`, `busy`, `failed`. (WR6) The server sends it when Cobblemon ends a
  natural wild battle (a habitat's Pokémon, in a zone or in Kanto) with the player among the losers;
  a catch doesn't count, and battles the hidden game started answer through `battle.result`.
- **One at a time:** requests wait (up to 120 logic steps, else `busy`) for an idle world: in the
  game, standing, nothing scripted, no warp or battle. At most 8 wait; more are answered `busy`.
  Requests of an old connection are dropped.
- **Progress** (balls taken, each trainer's wins) lives in the hidden save's mod data
  (`mod.save "routes"`), so an older save brings older progress back with its bag and money. It is
  in every `snapshot` and goes out as `route.progress` when it changes and whole after a save loads
  or a new game starts. "Beaten on this visit" (rematches) is the server's to keep.
- **Trainers (WR5)** are the server's: every tick, a player inside a zone's box who is free (not a
  spectator, not held by a build, `activity` `none`, not in a Cobblemon battle) and in a trainer's
  sight (straight ahead in the WorldIndex's `facing`, within `sight` cells) gets `route.sight`. The
  client shows "!" over the trainer's puppet for 60 frames, holds the player, and walks the puppet to
  the cell in front of the player (16 frames a cell). Once those frames have passed (plus 4 ticks)
  the server sends `route.battle` with the trainer's party and a line by class (our own text,
  `data/kantobridge/wild_routes/lines.json`, one text-box line each). One encounter at a time. The
  answer ends it: a win keeps that trainer quiet for the rest of the visit (until the player leaves
  the zone's box), anything else lets it look again after 100 ticks; with no answer while the player
  is free for 600 ticks, the server gives up the same way. After the answer the client walks the
  puppet back to its post.
- **Dungeons (WR7)** are the server's too. A player standing on a zone's dungeon entrance starts a
  gathering; everyone who steps on it within 100 ticks joins the party. Each gets `route.fade` at
  once; when the window closes the party takes a free slot of the dungeon region, floor 1 is built
  and they are teleported onto its start. A floor is built as soon as someone enters the one before
  it. On a floor's stairs a player gets `route.fade` (10 ticks) and goes down; the last floor's stairs
  are the exit, back beside the entrance (its `out` side). Leaving the slot any other way (the hidden
  game's blackout warp, Fly) or going offline leaves the visit; a visit without players is cleared.
  The client fades out over at most 10 ticks, holds the player, stays dark until the teleport has
  landed and the world there is drawn (or 15 s after it was due), then fades in. Inside, the first
  time a floor is entered: wild Pokémon from the dungeon's habitat pool roam from PMD's enemy spots
  (levels plus one every second floor), Poké Ball displays stand on its item spots (on the last floor
  also a treasure of four beside the exit), and its Monster House fills when someone first steps in;
  one more Pokémon appears every 600 ticks away from players, up to ten. Stepping on an item sends
  that player `route.give` with `keep:false` (given, never recorded: a dungeon's items are new each
  visit); the display goes once the answer says it's in the bag. No Rare Candy or Master Ball, at
  most one Nugget a visit. A cleared slot takes its entities with it.
- **The client (WR5)** forwards the server's `route.give`, `route.battle` and `route.blackout` to
  gen1-bridge, and gen1-bridge's `route.result` for them back to the server with the server's own
  `seq` as `re`; every `route.progress` goes up too. The save pairing's gate decides, as for the
  other relays: while it holds, nothing passes and the server asks again. For a zone's item ball the
  client sends `route.give` itself (an A press at the ball in front of the player, or a click on its
  puppet from the cell beside it); that answer stays on the client, and the `route.progress` that
  follows opens the ball's `route` story cell and takes its puppet away.

### Regions (MG2, several games)

[specs/MULTI_GAME_PLAN.md](specs/MULTI_GAME_PLAN.md) §4.3, §4.7 and §4.8; `gen1-bridge/act/kb_region.lua`,
`observe/kb_progress.lua`, `lib/kb_routes.lua` (routes v2).

| Type | Dir | Data | ROM |
|---|---|---|---|
| `game.status` | G→M, C→S | `game, region?, playthrough:"title"\|"fresh"\|"saved", ingame, started, badges, markers{<name>:bool}, name?, slot?, visitors?` (one hidden game's story progress; `name` is the save's player name, the player's own text; `visitors`: how many party members it shows as visitors) | yes |
| `region.park` | M→G | `region` (hold this game where it stands) → `act.result{kind:"park"}` | |
| `region.resume` | M→G | `region, to{map,x,y,facing}` (release the hold; warp onto `to` when not there) → `act.result{kind:"resume"}` | |
| `region.start` | M→G | `region` (start the game from its title: CONTINUE or NEW GAME) → `act.result{kind:"start", detail:"new"\|"continue"}` | |
| `region.state` | C→S, S→C | `followed?, parked[]` (MG4: the client's links as it sees them, region ids; S→C once on join with the stored presence) | |

#### Region rules (MG2)

- **`game.status`** is built from the adapter's `playthrough` (`title`: no live game; `fresh`: a New
  Game never saved; `saved`: loaded by CONTINUE or saved since), `ingame`, the badge count and the
  game profile's **markers** (`protocol/games.json`: `starter`, `champion`, … → a flag each adapter
  resolves in its own namespace; Gen 1 reads `save.flags[name]`). `started` is `playthrough ≠ title`.
  Sent after every `snapshot`, after `session.bound`, on `save.created` and `save.loaded`, and when a
  marker or the badge count changes (checked every 10 steps). At the title the boot skeleton's save
  says nothing: `badges` 0, every marker false, no `name`. The client forwards it with its link's
  `region`; the server keeps one record per player and region (`kantobridge:progress`) and the pass
  reads it to choose between resuming and starting the far game (MG5).
- **`region.park`** needs the hidden player in the game and idle (standing, nothing scripted, no warp
  or battle, no UI); it pushes a **hold** (the Bill's PC hold's pattern: a plain state over the
  overworld that refuses input and steps and freezes scripts and NPC movement), fades the music out,
  and writes `mod.save "region"` = `{away:{map,x,y}}`, which `snapshot.away` shows. Reasons:
  `not_ingame`, `not_idle`, `busy`. A park while parked is answered `ok` again.
- **`region.resume`** pops the hold and restores the music. When the hidden player is not on `to`,
  the adapter queues the engine's own `warp` there — **only onto one of this mod's route maps
  (`KB_ROUTE_*`) or the saved `away` cell** (story safety, MULTI_GAME_PLAN §4.13; towns are entered
  through the route map's own step script), followed as warp kind `script`. Reasons: `not_ingame`,
  `not_idle` (while a warp or battle is under way), `busy`, `bad_target`, `not_parked`.
- **`region.start`** works from the title screen only (`not_title` otherwise): CONTINUE when the
  active slot has a save (`detail:"continue"`), else NEW GAME (`detail:"new"`), driven through the
  title menu the way the save-slot switch rebuilds it. The intro then runs as ordinary UI: `ui.open`
  text boxes, the naming screen, the mirror for graphics; `game.status{ingame:true}` follows when the
  game reaches its overworld. Nothing skips the intro (§4.13). Reasons: `not_title`, `busy`,
  `failed`.
- **A link drop** pops a hold so the game can be played alone; the `away` marker stays in the save
  until a resume, and `snapshot.away` tells a reconnecting client.
- **Routes v2** (`gen1-bridge/data/routes.json` `version: 2`, `protocol/world/route_list.schema.json`;
  the export's `routes` resource v2): a `connecting` route has two **ends**, `from` and `to`, each
  with `region`, `games`, `map`, `x`, `y`, `facing`, `depth`, and optionally its own `classes` and
  `music` (else the route's). Each hidden game registers **only the end whose `games` include it**:
  the clearing patch in its town and a route map `KB_ROUTE_<ID>` of today's `[A][x1]` form (A warps
  back to the clearing; x1 is the arrival); the far cells live in the other process. The export item
  carries `ends{from{region,games}, to{region,games}}` and `end` (`from`\|`to`: the half this game
  registered, which its `town` and `routeMap` describe), so the builder knows the route is
  connecting and which region each half belongs to. An end's `music: null` means no song for that
  half (absent: the route's `music`); an end's `region` absent means its games' default region from
  the catalogue. When both ends list the running game (a one-engine test world only), the `to` end's
  route map is `KB_ROUTE_<ID>_TO` and the export lists one item per end. A free-roam zone is
  unchanged (its `from` may name a `region`; absent means the legacy region).
- **A Gen 3 end** (MG8, [specs/HOENN_PASS_PLAN.md](specs/HOENN_PASS_PLAN.md) K2–K4): Gen 3 takes no
  route map, map script or patched exit (spike S2), so the game3 adapter installs its end itself.
  The clearing opens at run time on every load of the town and is baked into the town's `g3map`
  (no `story.cells` for it). Stepping onto the clearing's trigger cell parks the hidden player
  there, reported on the route map at x1 facing right: a `warp` of kind `script` as on Gen 1, with
  no engine warp. A `move.step` left from x1 (onto A) is the way back, the engine's warp to the
  clearing's back cell facing the town; any other step there is refused `tile`. A `region.resume`
  onto the route map warps to the trigger cell and parks there. `route.give` puts the item in the
  bag with a found line; `route.battle` takes the first trainer of the class as the template
  (`TRAINER_CLASS_*`) with the request's party by Emerald's constant names (`species` without
  `SPECIES_`, `moves` without `MOVE_`), held for Cobblemon like any Gen 3 battle. A Gen 3 export
  carries the `routes` resource too.

### Crossings (MG5, several games)

[specs/MULTI_GAME_PLAN.md](specs/MULTI_GAME_PLAN.md) §4.7, §4.8, §4.12, D5, D6, D12; `server/PassService`,
`client/zone/PassRuntime`.

| Type | Dir | Data | ROM |
|---|---|---|---|
| `pass.cross` | C→S | `route, dir:"to"\|"from"` (the player stands on that pass and heads for the other half) | |
| `pass.abort` | C→S | `route, reason` (the client could not park, stash, start or resume) | |
| `party.stash` | C→S | `region` (every party member into Cobblemon's PC before a `region.start`) → `act.result{kind:"stash", moved}` or `error{code:"stash.pc_full"}` | |

#### Crossing rules (MG5)

- **The pass** is the far doorway of a connecting route half's **gate house** (D12): the WorldIndex zone entry carries
  `pass{cells[], route, toRegion, end}` and `gateHouse{x, y, w, d, facing}` (cells in the route map's grid). Its cells
  are story cells of type `pass`, closed per player (`pass_closed`) while the far region isn't built or the player has
  no link to its game.
- **The server** (`pass.cross`): the player must stand on that pass; the target region must exist and be built
  (`pass.no_region`, `pass.not_built`); the player's `region.state` must list a link for the target's game
  (`pass.no_link`); one crossing at a time (`pass.busy`). It answers `route.fade{ticks:10}` and starts a 15 s timer.
- **The client** (on the fade; `client/zone/PassChoreography`): `region.park` on the followed link (a refusal →
  `pass.abort`); then, by the target's last `game.status`: `fresh`/`saved` → `region.resume{to: that half's route map
  x1}`, and the target link is followed once the resume is acknowledged; `title` → `party.stash`, then `region.start`,
  and the target link is followed as soon as the start is acknowledged, so the intro's screens show on the player's own
  body with the fade lifted; the crossing then waits for `game.status{ingame:true}` (or 10 s after the intro's last
  screen closes). Following a link sends `region.state{followed, parked[]}` up (MG4). One 15 s clock runs from
  `pass.cross` until the far link is followed; every failure sends `pass.abort{route, reason}`, lifts the fade and
  posts a HUD line.
- **On `region.state`** the server teleports the player into the other half's gate house, just inside its far
  doorway; after a `start` it leaves the teleport to the client's relink (the first room, the one accepted teleport).
  `pass.abort` or the timer → `error{code:"pass.timeout"}`: the fade lifts where the player stands and the followed
  link is unchanged.
- **The name** (D6): the client answers the started game's first naming screen with the parked save's
  `game.status.name`, truncated to the game's limit; the screen is shown only when the name was truncated or the game
  asks something else (the rival's name, a gender).
- **Items and trainers** of a half belong to its region (`route.give`/`route.battle` carry `region`; the client forwards
  them to that region's link). "Add a region" refuses a second region of one world space (`build.region_exists`).

### One economy (MG6, several games)

[specs/MULTI_GAME_PLAN.md](specs/MULTI_GAME_PLAN.md) §4.9, D7: Pokémon, money and items are the player's; badges, key
items, HMs and the story stay per game. [`protocol/items.json`](../protocol/items.json) (schema
[`items.schema.json`](../protocol/items.schema.json)) is the **canonical item catalogue**: id → `class` (`ball`,
`medicine`, `battle`, `field`, `stone`, `held`, `valuable`, `tm`, `key`), the Cobblemon item it is mirrored as (when it
is), the `move` a machine teaches (TMs cross **by move**), and each family's symbol (`symbols.gen1`: the Gen 1 import's
ids, e.g. `POTION`, `TM_MEGA_PUNCH`). fabric-bridge bundles it (`Items`); gen1-bridge carries the generated
`lib/kb_items.lua`; `kb_protocol.items` reads it in Python.

- **The ledger** is server-side, per player (`kantobridge:ledger`, persistent): the wallet (`money`) and the item
  ledger (canonical id → quantity). Class `key` never enters it (key items, HMs, badges, story items).
- **Projection:** on a `region.resume` or after a `region.start` the server sends `bag.set{items, money, stamp}` to
  the followed link: the adapter writes `save.money` and replaces the bag's non-key items with the projection, within
  the game's limits (Gen 1: 20 bag slots, 99 a stack; `too_many` with `left` for what did not fit, which stays in the
  ledger: "in your trunk", shown on F7); the `stamp` goes into the save's mod data.
- **Read-back:** while followed, every `bag.sync` carries `money` and the `stamp`; the server applies the diff of that
  game's bag and money since the last projection to the ledger (prize money, purchases, found items, blackout
  halving). On park the last `bag.sync` is final. A save with no `stamp` (never projected) is **adopted once**: its
  money joins the wallet and its items the ledger; a save restored to an older state (`save.mismatch` kinds) keeps
  the ledger's view and is re-projected.
- **The Minecraft mirror** (`bag.sync` → Cobblemon items → `bag.use`) is one per player from the ledger, not one per
  hidden game; the stacks it creates are **marked**, so items found in Minecraft are never counted or deleted by it
  and a use debits the ledger (reaching the followed game through the next projection).
- **Legacy worlds** (no `regions.json`) keep today's per-game bag path on the server: the hidden bag's battle
  items mirrored as before and no `bag.set`. On the wire `bag.sync` always carries `money` (MG6) and carries
  `stamp` only once the save was projected.

### Export (builder role)

| Type | Dir | Data | ROM |
|---|---|---|---|
| `export.index` | M→G, G→M | Request (M→G): `{}`. Reply (G→M, `re` set): `game` (a catalogue id, MG1)`, engine, bridge, resources[{kind,id,hash,size}]` | |
| `export.get` | M→G | `kind:"map"\|"tileset"\|"image"\|"sprite"\|"palettes"\|"encounters"\|"field"\|"names"\|"layout"\|"swaps"\|"profile"\|"routes", id` | |
| `export.data` | G→M | `kind, id, hash, data`; `re` required | yes |

#### Export resources

A builder connection (role `builder`) asks for `export.index`, then fetches each resource it needs with
`export.get`. Each resource's `data` has its own schema in
[`protocol/export/<kind>.schema.json`](../protocol/export/); everything in it is ROM-derived, except
`profile`, which is another mod's hand-authored file (it stays on this machine all the same).

- **Timing.** gen1-bridge builds every resource once, spread over many logic steps, when the first
  `export.index` arrives: about 8 s of game time on the real game (10–15 s of wall time at 60 fps),
  then cached for that gen1-bridge instance. Fetching all resources afterwards takes well under a
  second. Clients keep their heartbeat going while they wait.
- **Size.** The real Red/Blue export is about 360 resources and 0.8 MB; the largest line is about
  33 KB (`encounters`).
- **Errors:** `error{code}` with `role` (not a builder or test connection), `not_found` (no such
  resource), `bad_message`, `export_failed` (the game data couldn't be read), or `union_room_on`
  (`export.index` while the engine's Union Room patched this boot's Centers: switch it off and
  restart; no world is built from patched data).
- **Image ids** are `<folder>.<name>` from the asset path: `tilesets.*` for atlases and animation
  strips, `fx.*` for overworld effects and emotion bubbles.
- **Field tables** are a fixed list chosen by gen1-bridge (ledges, tile pairs, cut-tree swaps,
  card-key doors, dark maps, spinners, forced movement, warp carpets, fly warps, last-map rewrites,
  water tilesets, rods, …); integer keys become strings.

| Kind | Id | Contents |
|---|---|---|
| map | map symbol | The map record (size in blocks, `blocks`, `border`, `connections`, `warps`, `signs`, `objects`) plus derived fields: `surface`, `outside`, per-cell `cells` flags, `parent` (the warp that first leads into an interior, by BFS from the surface), `lastMap`, `dark`, `palette`, and `borderDrawn` (the block the engine really draws around the map, or null for none; the voxel plan's V2) |
| tileset | tileset symbol | Atlas image id, `tilesPerRow`, `blocks` (block → 16 tile ids), `animation`, tile classes: walkable, counter, grass, door, warp, water, shore, cut tree, spinner, pads; and `animated` (the animated tiles as data: `tiles`, `kind` `hshift`/`frames`/`toggle`, `period`, `offsets` or `images` + `sequence`, or `image` + `stripOffsets` + `gate`; V2) |
| image | `<folder>.<name>`, e.g. `tilesets.overworld` | A greyscale PNG (4 shades), base64: atlases, animation strips, effects |
| sprite | sprite symbol | A sheet of 16×16 frames: 0–2 standing down/up/left, 3–5 walking; right is left mirrored |
| palettes | `sgb` | SGB palettes: name → 4 RGB colours, lightest first |
| encounters | `all` | Slot buckets, grass/water tables per map (species symbol, dex, level), rod tables |
| field | `all` | Selected engine field tables as JSON (ledges, cut-tree swaps, dark maps, …), and `display`: the player's void-fill option and whether it is the engine's default (V2) |
| names | `all` | Species → dex, move → number, item → number, with display names |
| layout | `kanto` | Block origins of the surface maps relative to `PALLET_TOWN`, from the engine's neighbour arithmetic |
| swaps | `all` | Runtime block-change sites (cut trees, card-key doors, scripted swaps) the builder prepares story cells for; MG9: Gen 2's (Cut trees, Whirlpools as kind `whirlpool`, the blocks its map scripts change, the bedroom's decorations), the list the bridge's runtime check uses |
| routes | `all` | Wild Routes (WR2): the zones this game runs, always present (`routes` is empty when there are none). Each zone: `id`, `name`, `kind` (`free_roam`), `theme` (`forest`), `town{map, cleared[[bx,by]], triggers[[x,y]], back{x,y,facing}}` (the blocks turned into path, the cells whose step leads out, where the way back lands), `routeMap{id, width, height, a{x,y}, arrive{x,y,facing}}`, and the generator's settings `trainers{perArea, rematch, classes[]}`, `areas{main, side}`, `levels{from, perArea, maxRise}`, `dungeon{chance, floors}`. The town's own `map` resource carries the clearing in its `blocks` ([specs/WILD_ROUTES_PLAN.md](specs/WILD_ROUTES_PLAN.md) §4.1-4.2) |
| profile | `dramaless` | Only when the Dramaless Shape mod (`DRAMALESS_SHAPE`) is installed in Gen1Recomp, enabled or not: its hand-authored shape profile `data/voxel_heights.lua` as text (never run), with the mod's version and whether it is enabled. The builder plans with it, and this player's client builds geometry with it ([specs/PROFILE_PLAN.md](specs/PROFILE_PLAN.md)); the file itself is never relayed |
| g3map | map symbol (`EM_*`) | **Gen 3 only** (MG7 G3D, [specs/GEN3_BRIDGE_PLAN.md](specs/GEN3_BRIDGE_PLAN.md) §5): a map in cells (16×16 metatiles): `w, h` (the layout's whole grid, as the engine places neighbours), `trueW, trueH` (the map's own size; a padded map's cells past it show the border), `pair`, `cells` (base64, 4 bytes a cell as the engine's layout keeps them: metatile u16, collision u8, elevation u8), `border{w, h, mids}`, `connections[{dir, map, offset}]` in cells (north, south, east, west, dive, emerge; a direction may have several), `warps[{x, y, to or dynamic, warp}]` (`warp` 0-based), `objects[{lid, gfx, x, y, elev, flag, kind, stays}]` (`stays`: it keeps its cell by the engine's movement for it, G3E), `mapType`, `kind`, `music`, `weather`; `dark: true` for a cave that needs Flash (the header's cave flag; the follow-up's item 8) and (SE7) for a map whose entry scripts set a flash level above 0 (Dewford Gym: lit only once the game's flash level there is 0). SE2: the cells have the unconditional changes of the map's entry scripts baked in (a layout every visit swaps to, a cell every visit sets) |
| g3tiles | pair id | **Gen 3**: a tileset pair: `mids`, per metatile its 4 quarters' ids in the flattened art (`quarters`, page × 1024 + index), its behaviour (`beh`, names in `behaviors`), and whether part of it draws over sprites (`over`); `land`: the behaviours used here whose cells roll land encounters, by the engine's own rule (G3E). A quarter the tile animations paste into (item 8) is a quarter of its own, never shared with a static one, listed in `g3anims` |
| g3image | `g3q.<n>` | **Gen 3**: an RGBA PNG page of 1,024 8×8 quarters (32 × 32, 256×256 px): the over, middle and under planes flattened (GBA pixels are opaque or clear) |
| g3sprite | `OBJ_EVENT_GFX_*` | **Gen 3**: an object-event sheet, `gid, w, h, frames`, RGBA PNG strip (0-2 stand, 3-8 walk, 9-17 run where present); item 8: `SURF_BLOB` (no `gid`), the field effect under a surfing player, three frames facing down, up and left |
| g3layout | region (`hoenn`) | **Gen 3**: `root, unit: "cells", maps{x, y, w, h}`: the surface maps' cell origins, by connections from the root; MG8: `start{map, x, y}`, the room a new game starts in (the engine's `newGameStart`: the moving truck on RSE), which the builder places under the root since its door is a dynamic warp |
| g3encounters | `all` | **Gen 3**: per map `land` (12 slots), `water` (5), `rock` (5), `fish` (10: old rod 0-1, good 2-4, super 5-9), each `{rate, slots[{dex, min, max}]}` with national dex numbers; `landWeights`: the 12 land slots' chances in percent as the engine's roll uses them (G3E) |
| g3names | `all` | **Gen 3**: the engine's constant names: `species` → national dex, `moves` → number, `items` → number |
| g3sites | `all` | **Gen 3** (SE2, [specs/STORY_EVENTS_PLAN.md](specs/STORY_EVENTS_PLAN.md) §4): every cell the hidden game's story can change, `sites[{map, x, y, own{metatile, coll, elev}, states[{metatile, coll, elev, source}]}]` (`own` the map's cell as g3map exports it; `states` every other state, `source` `script` (a setmetatile any of the map's scripts can run), `layout` (an alternate layout its entry scripts swap to) or `native` (an engine writer: the gym puzzles, ash, ice, cracked floors, TVs and PCs)), and `baked`, the cells whose unconditional change g3map already shows. The Battle Frontier's generated floors, secret bases' decorations and the link rooms are left out |
| g3anims | `all` | **Gen 3** (the follow-up's item 8): the animated quarters, `anims[{q, f, s}]`: the quarter `q` (its page holds its first frame), its frames `f` (quarter ids on the pages) and its loop `s` (`[frame index, GBA frames]` in order, 60 a second), as the engine's tileset animations run their banks over their two counters from 0; palette animations are left out |
| g2map | map symbol (`G2_*`) | **Gen 2 only** (MG9 G2D, [specs/GEN2_BRIDGE_PLAN.md](specs/GEN2_BRIDGE_PLAN.md) §6): a map in 32-px blocks: `w, h`, `blocks` (base64, a byte a block, as the import holds them: the copy taken at boot, never a loaded map's edited one; block 0 draws as the border block and collides as a wall), `border`, `variant` (its `g2tiles`), `tileset`, `env`, `palette`, `daytime` (the baked one: DAY, a pinned map's own, a dark map's after Flash), `group`, `num`, `area` (`johto`/`kanto`, the engine's landmark rule), `dark`, `fishGroup`, `connections[{dir, map, offset}]` in blocks, `warps[{x, y, to, warp, dynamic}]` in cells (`warp` 0-based; `dynamic` for warp -1, the destination remembered at run time), `coords[{x, y, scene}]`, `signs[{x, y, kind}]`, `objects[{lid, sprite or var, palette, x, y, movement, kind, flag, hours, stays, big}]` (`var`: a variable sprite's raw id; `palette`: an OBJ palette that overrides the sprite's) |
| g2tiles | variant id (`TILESET_*_<n>`) | **Gen 2**: a tileset drawn with one palette set and roof at the baked daytime: `tileset`, `roof`, per block (128) its 16 tiles' ids in the baked art (`blocks`, page × 1024 + index) and its four cells' COLL bytes (`coll`), and `meanings`: per byte used here what the engine's own predicates say (`perm` land/water/wall, grass, encounters, ice, whirlpool, trees, waterfall and `current`, counter, `ledge` facings, one-way `sides` and `neighbor`, `warp` kind (door, stairs, ladder, pad, hole, edge: a ladder apart from a staircase since the 3D heights plan's W3, for the builder's real shafts) and `mat`, `facesDown`) |
| g2image | `g2q.<n>` | **Gen 2**: an RGBA PNG page of 1,024 baked 8×8 tiles: the sheets' four shades through the map's GBC palettes (Palettes.bgSet: environment, daytime, roof colours, Crystal's special sets; roof sheets over tiles $0a-$12), in the cart's colours |
| g2anims | `all` | **Gen 2**: the animated tiles (water, flowers, whirlpools, the tower pillar, lava, currents and waterfalls), `anims[{q, f, s}]` as `g3anims`: a tile of its own whose page holds its frame at the tileset timer's 0, its frames and its loop in frames (the 0-7 timer ticking once every `period` frames of the tileset's program) |
| g2sprite | `SPRITE_*`, `SPRITE_*.p<n>` | **Gen 2**: an object sheet through one OBJ palette (DAY): `sprite, palette, w (16), h, frames, walker, type`, an RGBA PNG strip of 16×16 frames, shade 0 cut out; `.p<n>` for a palette a map's object overrides the sprite's with |
| g2layout | area (`johto`, `kanto`) | **Gen 2**: `root, unit: "blocks", maps{x, y, w, h}`: one surface area's block origins by connections from its root (New Bark Town; Crystal's Kanto from Pallet Town, joined to Johto by no connection), directions taken in a fixed order; `start{map, x, y}` on Johto's, where a new game starts |
| g2encounters | `all` | **Gen 2**: per map `grass` (`rates` and seven `slots` per time of day) and `water` (`rate`, three slots), the swarms (`swarmGrass`, `swarmWater`), `fish` groups (rows `{dex, level, chance}`, a time group's night row as `nite`), headbutt `trees` and Rock Smash `rocks` by set with `treeSets`, the Bug-Catching Contest (`contest`), and the slots' cumulative chances (`grassChances`, `waterChances`); national dex numbers |
| g2names | `all` | **Gen 2**: the engine's constant names: `species` → national dex, `moves` → id, `items` → id |

**Per-cell flags** (`map.cells`): one integer per 16-px cell, rows top to bottom. Like the engine,
a cell's behaviour comes from its bottom-left 8×8 tile.

| Bit | Value | Meaning |
|---|---|---|
| 0 | 1 | Walkable |
| 1 | 2 | Water (surfable, including shore) |
| 2 | 4 | Shore |
| 3 | 8 | Tall grass |
| 4 | 16 | Door tile |
| 5 | 32 | Warp tile: the collision tile is in the tileset's `warp` list (stairs, ladders, …) |
| 6 | 64 | Counter |
| 7 | 128 | Cut tree: a tree tile (not walkable) in a block that `field.cutTreeSwaps` swaps |
| 8 | 256 | Warp pad, **only on a cell with a warp entry** (bit 11); elsewhere pad tiles are floor art |
| 9 | 512 | Hole, only on a cell with a warp entry, likewise |
| 10 | 1024 | Spinner: the cell is in `field.spinners` (the arrow tile ids are the tileset's `spinner` list) |
| 11 | 2048 | A warp entry is on this cell |
| 12 | 4096 | A sign is on this cell |
| 13 | 8192 | Ledge |
| 14–15 | | Ledge hop direction: 0 down, 1 up, 2 left, 3 right |

**Hashes and the bundle.** A resource's `hash` is the SHA-256 (lowercase hex) of its canonical JSON
(sorted keys, `,`/`:` separators, UTF-8, non-ASCII unescaped), and `size` is that text's length in
bytes. A downloaded export is stored as an **export bundle**, which stays under `local/`:

```
<gameDir>/kantobridge/export/<game>/
  index.json          the export.index reply's data (pretty-printed, protocol field order)
  <kind>/<id>.json    one resource's data, exactly its canonical JSON (so the file's SHA-256 is its hash)
```

- Export ids double as file names, so they must be file-safe: `^[A-Za-z0-9_][A-Za-z0-9_.-]{0,63}$`,
  with no `..`, and unique ignoring case. (The protocol's general `id` pattern also allows `:`; export
  ids never use it.)
- The bundle's identity, `exportHash` in the WorldIndex, is the SHA-256 of the index **data's**
  canonical JSON, not of the pretty `index.json` file.
- `ExportCli` (`./gradlew exportCli -Pkb.exportPort=<port> -Pkb.exportOut=<dir>`) and the client's
  "Create Kanto world" flow both write bundles; `python -m fake_gen1 --write-bundle` writes the
  synthetic one, byte for byte the same as what `ExportCli` downloads from `fake_gen1`.

### Test only (role `test`)

| Type | Dir | Data | ROM |
|---|---|---|---|
| `debug.status` | M→G, G→M | Request: `{}`. Reply (`re` set): `npcs, topState, mode, flags`, plus other counts and flags, `engineMissing` (the engine members gen1-bridge relies on that are absent: names only) and `unionRoom` (`"on"`/`"off"`); never content | |
| `debug.quit` | M→G | `code?`: asks the test driver to exit | |

### Relay only (client ↔ server)

| Type | Dir | Data | ROM |
|---|---|---|---|
| `story.state` | C→S | `full`; when full: `active[cell], surf, bike, lit[]` (and `run?`); otherwise deltas `add?[], remove?[], surf?, bike?, run?, lit?` (`run`, FA8: the hidden player runs, so the server gives the running speed). BIKE_PLAN: `bikeKind?` (with `bike` in a full state or a delta that changes it) and `trick?` deltas: the server keeps the player seated on a bike entity of that kind and pose while `bike` is true (never while `surf` is). A cell is `"MAP:x:y"`. `active` counts only in a full state; a delta's `lit` replaces the lit set. The client applies a change to its own player's state first, then relays it: a full state on join, snapshot and respawn, deltas after (sent from M3 on) | |
| `warp.request` | C→S | `to{map,x,y,facing}, kind?`. With `kind`: the hidden game's warp. Without: the client's own sync teleport (join, reconnect, drift, stuck) | |
| `warp.done` | S→C | `ok, to?`; `re` optional | |
| `activity` | C→S | `activity:"dialogue"\|"cutscene"\|"battle"\|"paused"\|"none"` (speech bubbles; `paused` while the player's Gen1Recomp is disconnected) | |
| `relay.hello` | S→C, C→S | `protocol, mod` (the relay's version handshake on join; M10) | |
| `texture.status` | C→S | `ok, slotMapHash?, textures?, missing?, shapes?, shapeIndexHash?, packs?, reason?` | |
| `world.index` | S→C | `index, count, hash, part, enc?` (the WorldIndex JSON in parts: since MG9 G2G deflated, `enc:"deflate"`, when a client asks for it; a server from before G2G pushed it as text on join) | yes |
| `world.index.offer` | S→C | `hash, chars` (MG9 G2G: a region's WorldIndex by the SHA-256 of its text and its wire size, after `world.regions` on join and whenever a build publishes) | |
| `world.index.get` | C→S | `hash` (the offered index this client lacks) | |
| `world.slots` | S→C | `index, count, hash, part, enc?` (the slot-map JSON in parts, so a joining player can build their own pack against the host's slots; M9; deflated and on request since G2G, as `world.index`) | yes |
| `world.slots.offer` | S→C | `hash, chars` (MG9 G2G: the world's slot map by its hash and wire size, after the index offers) | |
| `world.slots.get` | C→S | `hash` (the offered slot map this client lacks) | |
| `world.shapes` | S→C | `doc, index, count, hash, part, enc?` (the shape index, `doc:"index"`, deflated with `enc:"deflate"`, and the shape map, `doc:"map"`, in parts, when a client asks for them; the voxel plan's V2) | yes |
| `world.shapes.offer` | S→C | `index{hash, chars}, map{hash, chars}` (a region's shape documents by the SHA-256 of what they hold and their wire size, after `world.slots` on join and whenever a build publishes) | |
| `world.shapes.get` | C→S | `index?, map?` (the content hashes of the offered documents this client lacks) | |
| `world.regions` | S→C | `index, count, hash, part` (MG3: the region table and records, in parts on join before the regions' `world.index` parts; ids, numbers and hashes only) | |

**The WorldIndex** ([`protocol/world/world_index.schema.json`](../protocol/world/world_index.schema.json))
is what the builder writes to `<world>/kantobridge/world_index.json`:

- the scale (a cell is 2×2 blocks, the surface's feet level, the interior layer step);
- each map's block origin (the north-west corner of its cell (0, 0) at feet level), size in cells,
  layer, kind, palette, biome, darkness and parent (from the voxel plan's V7, an interior with the outdoor biome lies
  under the open sky and has no ceiling: a forest);
- the slot-map hash;
- the story-cell table (`"MAP:x:y"` keys, each with its type and the object or swap site behind it;
  objects carry their `hidden` default; tile cells their `start` block, `water`, and `closed{block,
  pass, height, art}` with the closed art's four slot indices, so each client draws and collides
  its own player's closed cells; SE3, Gen 3's `cell` cells their `start` metatile, `built{metatile, pass}` and
  `states[{metatile, pass, height, art}]`, every state's art as four slot indices; Gen 2's the same in block ids);
- the habitat block positions and pools (WR6: a zone's too, `map` its route map, one pool per area
  with tall grass, `kantobridge:zone_<zone>_a<n>`), with `range`, the block's `RangeOfInfluence`, where it isn't 14
  (the cave area's are 6: [specs/CAVES_3D_PLAN.md](specs/CAVES_3D_PLAN.md) §3.3);
- (Wild Routes, WR3) the zones: each zone's box of blocks (`x, z, w, d`), the feet level of its floor (`y`), its theme,
  biome and route map. The route map is a `maps` entry of kind `route` placed at the zone's trailhead, so its cells A
  and x1 are the trailhead's; written only in a world that has zones
  ([specs/WILD_ROUTES_PLAN.md](specs/WILD_ROUTES_PLAN.md) §4.4). WR5 adds each zone's `trainers` (`id`, cell `x, y`,
  `facing`, `sight` in cells, `class`, `sprite`, `party[{species, level}]`) and `balls` (`id`, cell `x, y`, `item`),
  with `ballSprite`. Their cells are in the route map's grid (A is 0,0, cells may be negative), so `cellOrigin` places
  them; each ball's cell is also a `route` story cell. Party species are ROM-derived (logs carry counts only). WR7 adds
  a zone's `dungeon` (its entrance cell in the same grid), and the world's `dungeons` region (every dungeon slot's box,
  `x, z, w, d`, and the first floors' feet level `y`): a client inside it whose hidden player is on a route map is
  away, as in a zone;
- (caves in 3D, H1) a map's layout, written only for a map whose cells don't all sit at its placement
  ([specs/CAVES_3D_PLAN.md](specs/CAVES_3D_PLAN.md) §3.1): `pieces` (parts placed apart: a cell rectangle `x, y, w, h`,
  an optional `mask` of alternating runs, and a block offset `dx, dy, dz`), `levels` (each cell's floor above its
  piece's feet, as `(level, count)` pairs) and `ramps` (stairs cells: `x, y`, the `dir` they climb and their `rise`).
  `cellOrigin` and `toBlock` go through them; a cell's lookup window is then its own floor's, 3 below to 4 above.
  Since the 3D heights plan (D5) every interior under a ceiling has a layout with `ceilings`: each cell's ceiling (the
  first solid block over its room) above its piece's feet, as `(ceiling, count)` pairs; a world built before it has none
  (3 blocks of air over each floor). Interior layers stand 29 blocks apart (`scale.layerStep`; 22 before), the world's
  bottom at Y -352.
- `shafts` (optional, H5, [specs/CAVES_3D_PLAN.md](specs/CAVES_3D_PLAN.md) §3.5): each real ladder or hole of a cave
  built in 3D, `kind` (`ladder`, `hole`), its `top{map,x,y}` cell straight over its `bottom{map,x,y}`, `up` (climbed up
  too), and its column: `x, z` and the blocks `y0` (the bottom cell's feet) to `y1` (the top cell's floor); a ladder's
  `wall` (`up`, `down`, `left`, `right`: the side of the column its ladder stands on). A ladder's top cell keeps its floor
  in the half away from the ladder, to step off onto. A block inside a shaft (its air) is in no cell; a server rule that
  asks which map a player is on there (Flash's Night Vision) uses the shaft's ends.

Clients turn block positions into cells and back with it. The `parts` of `world.index` are slices of
its JSON text (64 Ki characters each); `hash` is the SHA-256 of the whole text.

**The slot map** ([`protocol/world/slot_map.schema.json`](../protocol/world/slot_map.schema.json)),
`<world>/kantobridge/slot_map.json`, gives every `(atlas, palette, tile, shape)` the export uses a tile
block: family `f` of that shape (`kantobridge:tile_full_ff` or `tile_half_ff`) and slot `s` (block
state `hi = s >> 4`, `lo = s & 15`). It is computed from the **whole export, never from a build's map
filter**, so every build from the same export, and every player's pack, agrees. `slotMapHash` is the
SHA-256 of its canonical JSON. Capacity: 16 families × 256 slots per shape.

**Regions (MG3, several games)** ([`protocol/world/regions.schema.json`](../protocol/world/regions.schema.json),
[`region.schema.json`](../protocol/world/region.schema.json); MULTI_GAME_PLAN §4.6, §4.14):

- **The region table** `<world>/kantobridge/regions.json` lists the regions of the one flat overworld, each built
  from one game's export at a slot: slot `n` has its origin at `(pitch · n, 64, 0)` along +X (`pitch` 8192, D8).
  **The region record** `<world>/kantobridge/regions/<id>/region.json` (canonical JSON) holds the region's game and
  family, export hash, slot and origin, its `bounds` (no two regions' overlap: `build.region_overlap`), its `areas`
  (surface, zones, dungeons, caves: "which region am I in"), its slot-map family range, its documents
  (`regions/<id>/world_index.json`, `shape_index.json`, `shape_map.bin`) with their hashes, the shape generator, its
  rules (profile, movement, compat) and namespaces (art set; habitat pools `kantobridge:<region>_<map>`).
- **The legacy rule:** a world without `regions.json` is one implicit region `kanto` at slot 0 with families 0..15,
  its index at the root, every message without `region`, one link. Nothing is rewritten until a build with a region
  writes the new layout (then the root index and shape files go). Every golden and every existing world stays
  byte-identical.
- **The slot map is one per world** (`slot_map.json` version 2): each region owns a family range of the 112 families
  per shape (MG7, E7: a Gen 1 region takes 16, a Gen 3 region 80; MG9, J7: a Gen 2 region 16; each starting on a
  multiple of 16) and its keys carry the
  region and its art set, so a rebuild of one region never renumbers another's
  blocks. A region's `WorldIndex.slotMapHash` is the hash of the version-1 form of **that region's slice** of the map
  (the legacy region at 0..15 keeps today's hash exactly); `world.slots` carries the whole document's hash.
- **On join** a region world sends `world.regions` (the table and every record), then each region's
  `world.index.offer` tagged with `region`, the one `world.slots.offer`, then each region's `world.shapes.offer` tagged
  with `region`; each document's parts follow the client's `get` for it (tagged as its offer; MG9 G2G, before which
  the indices and the slot map went out as parts on every join). A legacy world sends the same offers untagged, without
  `world.regions`.
- **The `region` field:** every relayed type the client sends for its hidden game (`story.state`, `warp.request`,
  `session.*`, `save.*`, `route.*`, `party.*`, `daycare.*`, `bag.*`, `battle.*`, `pc.*`, `texture.status`,
  `game.status`) carries an optional `region`, the region of the link it came from; absent means the legacy region.
  The server resolves a region from the message's `region`, else from the player's position, else the legacy one;
  its `region`-tagged messages are routed by the client to that region's link. Services keyed by region: warps
  (`warp.request.to.map` against that region's index), zones and dungeons, habitats, Flash, encounters, battles,
  texture status, the session record (format 2: `players{uuid:{slot, regions:{<id>:{slot}}}}`, format 1 read), the
  save pairs (`kantobridge:save_pairs`, the old `kantobridge:save_pair` read once as the legacy region's), the daycare
  (`regions/<id>/daycare/<uuid>.json`), the per-player progress record (`kantobridge:progress`, fed by `game.status`).


**The shape index** ([`protocol/world/shape_index.schema.json`](../protocol/world/shape_index.schema.json)),
`<world>/kantobridge/shape_index.json` (the voxel plan's V2, [specs/VOXEL_BLOCKS_PLAN.md](specs/VOXEL_BLOCKS_PLAN.md)
§4.2), names the world's shapes without a pixel: canonical JSON of the planner's version and parameters, the sorted
atlases and palettes, the sorted shape keys (`geoms`; a key such as `cube:<a>|<u>,<d>,<n>,<s>,<w>,<e>` names one tile per
face of a cube, `<a>` an index into `atlases`) and the sorted `[geom, palette]` pairs (`instances`). A shape id is an index
into `instances` plus 1; 0 means no shape. Its keys come from the whole export, never from a build's filter;
`shapeIndexHash` is the SHA-256 of its text. From V7 its `templates` hold the world's structure templates (bounded
buildings a door folds into): per entry its tile grid, door cells, ground tile, outdoor flag and, per grid position, the
Tier A keys it would otherwise be, under `sig` (the first 16 hex digits of the SHA-256 of the entry's canonical JSON);
a key `tmpl:<a>|<sig>|<bx>,<by>,<bz>` is one block of one, so a guest rebuilds every structure from the host's list
with its own art. A key may end in `|tint=<1..3>`: its faces are darkened by a vertex colour (an interior's ceiling).

**The shape map**, `<world>/kantobridge/shape_map.bin`, gives positions their shape ids: a binary file (magic `KBSM`,
version 1, the SHA-256 of the WorldIndex's text and of the shape index's text, then per box of columns (the surface box,
each interior volume) a stack of ids per column from the feet level up, then closed story-cell overlays from V5: per
tile story cell, sorted by its story key, the block of its north-west column at feet level and four stacks of ids
(north-west, north-east, south-west, south-east) of its closed art, bottom-up; a client draws a cell's overlay only
while its own player sees the cell closed). `shapeMapHash` is the SHA-256 of its bytes. Each client builds the geometry of every shape from its **own** export and
pack; a position without an entry, or a client without geometry, draws the plain cube of its block.

**The generated resource pack** (`<gameDir>/kantobridge/resourcepack/`, ROM-derived art, never
shared) holds textures only, addressed by key, never by slot (layout 2, from the voxel plan's V1):

- `pack.mcmeta` (pack format 34) and `kantobridge.json` at the root: `layout` (2), `artSet` (the art
  set: the game the export came from, the path segment `<set>` below), `game`, `exportHash`, and the
  counts `tiles`, `sprites`, `textures`; MG7: a pack of several games' art (the player's other exports on
  disk, one art set per game family, so Kanto and Hoenn share one pack) adds `sets`, a list of
  `{artSet, game, exportHash, tiles, sprites}`, the pack's own set first; its top-level counts are the
  sets' sum. A pack without `sets` holds its one set. A region's tile keys are drawn with the loaded set
  of its own game (or a compatible one), the shapes with the followed region's;
- `assets/kantobridge/textures/block/<set>/tile/<atlas>/<palette>/<tile, 4 digits>.png` (16×16,
  coloured, with `.mcmeta` animations for water and flowers): sprite id
  `kantobridge:block/<set>/tile/<atlas>/<palette>/<tile>`; a Gen 3 set's tiles are its baked quarters,
  `<atlas>` the quarter page `g3q.<n>`, `<palette>` `baked`, the tile the quarter's index in its page
  (MG7);
- `assets/kantobridge/textures/npc/<set>/<palette>/<sprite>.png` (64×32 sheets: standing and walking
  rows, facing down, up, left, right);
- `assets/kantobridge/textures/player/<set>/<palette>/surf.png` (the surf sprite, from the export's
  `field.playerSprites.surf`).

Ids become path segments lower-cased, with anything outside `[a-z0-9_.-]` replaced by `_`. The pack
holds every texture of the whole export, so one pack fits every world of a compatible game (the same
game, or Red with Blue): the client's runtime model turns each tile block's slot into a key through
the world's slot map, and the key into this sprite. It sits **below** player resource packs, so a
player pack overrides any texture by repeating its path. A pack without `layout` (layout 1, from
before V1) counts as no pack.

#### Multiplayer rules (M9)

- **`world.slots`** is offered after the indices on join and whenever a build publishes (`world.slots.offer`; its
  parts, deflated, answer a `world.slots.get` since MG9 G2G); the server offers it only when the slot map's hash
  equals the WorldIndex's `slotMapHash`, and the client accepts it only when the text's and the parsed map's hashes
  both match (and the offer's). Each client draws the
  world's tile blocks through it with its **own** art: a block's slot names a key, the key a texture
  of the client's own pack. The pack doesn't depend on the host's slot map (since V1), so joining a
  host with other slots is a re-mesh, never a re-export. A client re-exports its own art only when it
  has no usable pack (none, layout 1, or one of an incompatible game) or its pack lacks textures the
  world's slot map names, and only while gen1-bridge is connected to a compatible game (the same game,
  or Red with Blue), once per world. Art is never sent over the network. A world without a slot map
  (built before M9) shows placeholders.
- **Indices and the slot map on demand** (MG9 G2G, specs/GEN2_BRIDGE_PLAN.md §8): a region's WorldIndex is offered
  with **`world.index.offer{hash, chars}`** (the SHA-256 of its text, which its shape map names, and the deflated
  parts' total size) and the world's slot map with **`world.slots.offer{hash, chars}`** (the map's own hash). The
  client keeps both in the shapes' cache (`<hash>.world.z`, `<hash>.slots.z`) and asks for what it lacks with
  **`world.index.get{hash}`** (tagged as the offer) or **`world.slots.get{hash}`**; the server answers with that
  document's parts as Base64 of its deflated text (`enc:"deflate"`, `hash` that wire text's), each checked against the
  offer, with the shapes' rules (only what it still publishes, once per 5 s, `bad_message` without a `hash`). A held
  index stays until the one offered in its place is in; a region's shapes wait for its index. The real three-region
  world (Kanto, Hoenn, Johto) shrank from 117 parts on every join (the indices 12 + 45 + 18, the slot map 42) to
  8 + 3 parts on a first join and none on a rejoin.
- **Shapes on demand** (2026-10-15, docs/SEVERAL_GAMES_FOLLOWUPS.md item 4, the voxel plan's R10): after the
  `world.slots` offer the server sends each region's **`world.shapes.offer`**: its shape index and shape map, each by
  the SHA-256 of what it holds (the index's UTF-8 text, the map's file bytes) and its wire size in characters. The
  client looks each up in its own cache (`<gameDir>/kantobridge/shapes/docs/`, the deflated documents by hash, checked
  when read back, at most 64 MB, least recently used out first) and asks for the ones it lacks with one
  **`world.shapes.get{index?, map?}`** (the offer's hashes, tagged with the offer's region). The server answers with
  that document's `world.shapes` parts while it still publishes it, else with nothing (a rebuild sends a new offer);
  the same document asked for again within 5 s goes out once; a request naming no document is `bad_message`. A rejoin
  of an unchanged world therefore downloads no shapes. Offered only when both files exist and the shape map's header
  names the published WorldIndex and that shape index.
- **`world.shapes`** (parts): the shape index (`doc:"index"`) as Base64 of its deflated UTF-8 text with
  `enc:"deflate"` (a server from before 2026-10-15 pushed the text itself on join, without `enc`, and the client still
  takes that), then Base64 of the shape map's deflated bytes (`doc:"map"`); `hash` is the SHA-256 of the document's
  whole transmitted text. A completed document must hold what its region's offer named, else it is refused. The
  client accepts the pair when both documents assemble and match their hashes, the index parses with a known version,
  the map's index hash is the hash of the `world.index` text it holds and its shape hash is the SHA-256 of the
  received index text; otherwise it keeps drawing cubes and reports why. A guest builds geometry for the **host's**
  keys from its own art; a key it can't build draws the plain cube.
- **`texture.status`:** `ok:true` means this client draws the world with its own art; it carries
  `slotMapHash` (the world's slot map it resolved its sprites against), `textures` and `missing` (0).
  `ok:false` carries `reason`: `no_art` (no usable pack, and gen1-bridge isn't connected to a
  compatible game; the export starts once it is), `art_incomplete` (the pack lacks `missing` of the
  world's keys, drawn as placeholders; with `slotMapHash`, `textures`, `missing`), `no_slots` (no slot
  map arrived 15 s after the index), `reload`, `export.<code>`, `pack.<code>`, `pack.io`,
  `interrupted`, `internal`. Failures retry only when the world changes or the player rejoins. The
  pack's root `kantobridge.json` carries `layout`, `artSet`, `game`, `exportHash` and its counts. From V2 every
  status also says how many shape ids the client has geometry for (`shapes`, 0 while it draws cubes) and from which
  shape index (`shapeIndexHash`). A client that draws its own art but cubes keeps `ok:true` and says why in
  `reason`: `no_shapes` (the world sent none), `shape_map_mismatch` (the shape map doesn't decode or names another
  index), `geometry_unsupported` (a shape index it can't read), `shapes_off` (its `kantobridge-client.json` says
  `"shapes": "off"`); an art reason wins over these. From V6 a client that applies texture packs also says how many
  manifests (`packs`; omitted when none), so "my tree looks different" can be told from a world problem.
- **Activity** reaches other players through Fabric's attachment sync (`kantobridge:activity`, to all
  but the player); clients draw speech bubbles from it.
- **Authority:** every client → server message affects only the sender (GameTest-proven for every
  type).
- **Per-world save slots:** on `session.bind{world, multiplayer:true}` gen1-bridge pairs the world
  with its own save slot (created on first use; the world → slot map is kept installation-wide, per
  game version) and switches the hidden game to it at runtime: at the title it just moves the active
  slot; from a saved game it first saves that game into its own slot (behind the engine's quick-save
  gate, not announced as `save.begin`), then returns to the title; a never-saved New Game moves as is
  into an empty slot, otherwise waits for the title. `session.bound{slot}` comes only once the hidden
  game is on the slot (possibly much later than the bind); meanwhile a `log` warning
  `session.switch_waiting` (reason `busy`, `new_game` or `save_failed`) says why, `bag.use` is refused
  with `error{code:"session.pending"}`, and the client neither relays story data to the server nor
  world data to gen1-bridge. A singleplayer bind (`multiplayer:false`) keeps the current slot, unless it
  is a multiplayer world's, in which case the remembered offline slot returns. Errors: `session.no_game`,
  `session.no_slots`, `session.no_slot`, `session.store_failed`, `session.failed`. After a switch the
  snapshot shows the title (`ingame:false`).
- **The client's side of pairing:** it sends `session.bind` on entering a Kanto world and after every
  reconnect, and accepts only a `session.bound` whose `re` matches its latest bind. Until then it relays
  nothing between gen1-bridge and the server (story messages wait in order, keeping only the newest
  `bag.sync`; `pc.open` is answered `pc.closed` at once; server data is kept, newest `party.sync`/
  `dex.sync` only) and holds the player.
  - **Since M10, `session.bound.switched` decides:** with `none` the held messages go on in order;
    with `title`, `saved` or `moved` they are dropped and the client sends `resync`.
  - Without the field it falls back to the M9 guess: the held messages go on only when the pairing
    provably kept the same save (same gen1-bridge session and world).
  - The HUD shows the waiting reason from `session.waiting`. gen1-bridge still logs
    `session.switch_waiting` for diagnostics, but the client no longer reads it.
  - Refused binds are retried with back-off (5 s doubling to 60 s). `session.no_slots` plays
    unpaired.
- **Speech bubbles:** `activity` picks one bubble in this order: battle, then paused, then dialogue,
  then cutscene.
- **Other players' surfing:** each player's surf flag (set from their own `story.state`) reaches other
  players through the synced `kantobridge:surfing` attachment. Clients draw the surf sprite under
  other surfers within 64 blocks.
- **Ports on one PC:** each Gen1Recomp instance (a separate install per player) has its own port (the
  BRIDGE PORT mod option; Minecraft's `config/kantobridge-client.json` or `-Dkantobridge.port`). If the
  configured port stays busy or refused for 3 s, gen1-bridge takes the first free one of the next 8
  (never by address reuse) and reports it (`debug.status`: `port`, `portConfigured`, `portFallback`).

## Relay to the Minecraft server

The Minecraft server gets each player's story data only from **that player's own client**, over
one Fabric custom payload, **`kantobridge:json`**: a UTF-8 JSON string of at most **256 KiB**
carrying the same envelope in both directions. Larger data is split at the application level
(`world.index` parts).

- Types marked C→S above are accepted from clients; S→C types go to clients. Anything else is
  rejected with `error`.
- The same path serves singleplayer (integrated server) and multiplayer.
- The server trusts each client for its own player's story state, and a player can only affect
  themselves.
- ROM-derived data such as text and trainer teams may pass through the server; art never does.
- **The relay's version handshake (M10):** when a player joins, the server sends `relay.hello{protocol,
  mod}` and the client answers with its own.
  - The server disconnects a client that can't receive `kantobridge:json` (no kantobridge mod), or
    that answers with another protocol. The reason names both versions and has an English fallback
    for clients without the mod's translations. The missing-mod check runs on the next server task,
    after the join.
  - A client that never answers isn't kicked.
  - The client leaves by itself when the server's protocol differs.
  - GameTest mock players are left alone.

## Changes from the draft in PLAN.md §3

- `re` always lives in the envelope. `act.result`, `save.done`, `export.data` and `error` don't
  repeat it in `data`.
- `save.done` carries the `save` id, so the client can match it across the relay.
- Added optional fields: `move.step.push`, `player.pos.n`, `player.blocked.n`, `act.field.item`,
  `ui.update.qty`, `party.give.dvs`, `debug.quit.code`. `hello.reject.reason` is a closed list.
- `daycare.deposit`/`withdraw` also travel C→S, because the server holds the Pokémon (PLAN P15).
- `party.sync` and the snapshot's party are marked ROM-derived, because a nickname can hold a
  species name from the game.
- The relay-only types (`story.state`, `warp.request`, `warp.done`, `activity`, `texture.status`,
  `world.index`) are now fully defined.
- `snapshot.ingame` (M1): at the title screen there is no live position, only the save's.
- The export resources, their per-cell flags and the bundle layout (M2 preparation), and the
  WorldIndex schema.
- Added in M9: `world.slots` (S→C), so a joining player builds their own pack against the host's
  slot map.
- Added in M10:
  - crash recovery: `save.begin.pair`/`at`, `save.loaded`, `save.mismatch`;
  - the relay handshake `relay.hello`;
  - `session.bound.switched` and `session.waiting`;
  - `pc.open`/`pc.closed` ids;
  - `hello.ok.tested`;
  - `activity:"paused"`.
  The client drops a `pc.open` without an id and answers a `save.begin` without a `pair` with
  `save.done{ok:false}`.
- Added by the voxel plan ([specs/VOXEL_BLOCKS_PLAN.md](specs/VOXEL_BLOCKS_PLAN.md)):
  - V1: `texture.status.missing` and its new meaning, the reasons `no_art` and `art_incomplete`
    (`no_bridge` and `slot_map_mismatch` are gone), and the generated pack's layout 2.
  - V2: `world.shapes` (S→C), the shape index and shape map files, `texture.status.shapes` and `shapeIndexHash`, and
    the export's `map.borderDrawn`, `tileset.animated` and `field.display` (all optional, so older bundles stay valid).
  - V3 and V4 change no message: their keys (`slab`, `roof`, `tuft`, `cell`) and the planners `kb-shapes/2` and
    `kb-shapes/3` live in the shape index, which already travels.
  - V5: the shape map's closed story-cell overlays (per tile story cell, four stacks of ids of its closed art), and
    `kb-shapes/4`; still `world.shapes`.
  - V6: `texture.status.packs` (optional). Texture packs themselves never travel: their manifest format is
    `protocol/packs/kb_pack.schema.json`, read only by the client that has them ([PACKS.md](PACKS.md)).
  - V7: the shape index's `templates` and `tmpl` keys, the `tint` modifier on keys (ceilings), and an interior placed
    with the outdoor biome (under the open sky, no ceiling). The planner is `kb-shapes/5`.
  - V8 and V9 change no message (characters are drawn from each client's own sheets; V9 removes only jar files).
- Added by the profile plan ([specs/PROFILE_PLAN.md](specs/PROFILE_PLAN.md)):
  - P1: the export resource `profile/dramaless` (another mod's shape profile, as text; it never leaves this machine);
    the planner `kb-shapes/6`, whose unit variants carry the pinned class and the `prop_bg` shades (`g2_prop_bg3`).
  - P2: a shape-index template may carry `band` (a band-table building: its band table, composite grid, overhang rows,
    and per position its solid and decor heights; its columns are empty); the planner `kb-shapes/7`. Still
    `world.shapes`.
  - P3: the shape key kind `box` (`box:<a>|<u>,<n>,<s>,<w>,<e>|<h>`, `|v=p` for sunk panes); unit variants'
    parameters (`_k<two digits each>`); a template's `hull` (a canopy or a planter) and `band.parts` (a furniture
    entry's pieces); the planner `kb-shapes/8`. Still `world.shapes`.
- Added after the several-games plan (docs/SEVERAL_GAMES_FOLLOWUPS.md item 4, the voxel plan's R10, 2026-10-15):
  `world.shapes.offer` (S→C) and `world.shapes.get` (C→S); `world.shapes.enc` (the index deflated). The shape
  documents go out when a client asks for them, not on every join.
- Added in MG9 G2G (specs/GEN2_BRIDGE_PLAN.md §8, 2026-10-15): `world.index.offer` and `world.slots.offer` (S→C),
  `world.index.get` and `world.slots.get` (C→S); `world.index.enc` and `world.slots.enc` (deflated parts). The indices
  and the slot map are offered by hash as the shapes are and kept by the client beside them
  (`<gameDir>/kantobridge/shapes/docs/`, `<hash>.world.z` and `<hash>.slots.z`): a rejoin of an unchanged world
  downloads nothing. A client still takes parts no offer announced (a server from before G2G); a client from before
  G2G gets no index from a G2G server, so a host and its players update together.
