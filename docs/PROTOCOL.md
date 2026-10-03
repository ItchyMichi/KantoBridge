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
  - gen1-bridge binds lazily on its first logic step, **without `SO_REUSEADDR`**, and retries every
    second while the port is busy.
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

## Redaction and logging

Every ROM-derived field (text, names, trainer teams, map and tile data, images, encounter data)
carries `"x-rom-derived": true` in its schema. The derived [`catalog.json`](../protocol/catalog.json)
lists those locations per type, and the tools (`tools/kb_protocol`) and Java (`proto.Redact`) replace
them with `<rom:KIND:LEN:SHA8>` placeholders before anything is logged. gen1-bridge itself logs only
message types, engine symbols, counts, lengths and short hashes. The details are in
[`protocol/README.md`](../protocol/README.md).

## Text

- Text lines are UTF-8 strings. The Gen 1 charmap is translated by gen1-bridge: `<PK>` and `<MN>`
  stay as tokens, `#` becomes `POKé`, and `é ♂ ♀ × ¥` are sent as themselves. The full markup is
  fixed in M4.
- Naming glyphs (`ui.name`) are the engine's glyph tokens, one per entry, e.g. `"A"`, `"é"`,
  `"<PK>"`. Multi-character cells count as one glyph.

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
| `hello.ok` | G→M | `protocol, bridge, engine, game:"red"\|"blue", session`; `re` required | |
| `hello.reject` | G→M | `reason:"protocol"\|"role"\|"malformed"\|"busy", protocol`; `re` required | |
| `ping` | G→M, M→G | `t` (sender's ms clock) | |
| `pong` | G→M, M→G | `t` (echoed); `re` required | |
| `resync` | M→G | `{}`; asks for a `snapshot` | |
| `snapshot` | G→M | `ingame` (false at the title screen and intro: `player` is then only the save's last position and must not move the Minecraft player), `player{map,x,y,facing}, state{…player.state}, flags[], toggles{map:{obj:bool}}, blocks[{map,bx,by,block}], lit[], bag{item:qty}, money, coins, badges[], party[{uid?,dex,level,nick?,moves[],hp?,maxhp?,status?}], dex{seen[],owned[]}, npcs{map,list[]}, ui?`; `re` optional (set when answering `resync`) | yes |
| `log` | G→M | `level, code, msg` (bridge diagnostics, never game text) | |
| `error` | G→M, M→G, C→S, S→C | `code, msg`; `re` optional (the offending message) | |

### Movement

| Type | Dir | Data | ROM |
|---|---|---|---|
| `move.step` | M→G | `dir:"up"\|"down"\|"left"\|"right", n` (counter), `push?`. Sent on a cell crossing, or (`push:true`) after pushing against an edge for 8 or more ticks (exit mats, gates) | |
| `move.face` | M→G | `dir` (turn in place while idle) | |
| `move.hold` | M→G | `button:"b", on` (Cycling Road brake) | |
| `player.pos` | G→M | `map, x, y, facing, kind:"walk"\|"bike"\|"surf"\|"ledge"\|"script"\|"spin"\|"current"\|"slope"\|"push"\|"sync", frames, n?`. Sent at step start (target cell) and on non-step changes | |
| `player.blocked` | G→M | `dir, reason:"tile"\|"entity"\|"bounds"\|"script", map, x, y, n?` | |
| `player.state` | G→M | `mode:"idle"\|"screen"\|"forced"\|"cutscene"\|"busy", bike, surf, slope, hidden, dark, lit` (all required) | |
| `warp` | G→M | `phase:"start"\|"arrive", kind:"door"\|"stairs"\|"edge"\|"hole"\|"pad"\|"fly"\|"teleport"\|"dig"\|"rope"\|"blackout"\|"script"\|"elevator", to?{map,x,y,facing}` (required on arrive) | |
| `emote` | G→M | `target:"player"\|<npc id>, bubble:"!"\|"?"\|"happy"\|null, frames` | |

### Interaction

| Type | Dir | Data | ROM |
|---|---|---|---|
| `act.interact` | M→G | `map, x, y` (face the cell and press A) | |
| `act.button` | M→G | `button, how:"tap"\|"down"\|"up"` (mirror screen, start menu) | |
| `act.field` | M→G | `action:"cut"\|"surf"\|"strength"\|"flash"\|"dig"\|"teleport"\|"bicycle"\|"fish"\|"fly", x?, y?, dest?, item?` | |
| `act.result` | G→M | `ok, kind?, reason?`; `re` required | |

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
| `ui.name` | M→G | `ui, glyphs[]` (Gen 1 glyph tokens, 1–10) | yes |
| `mirror.sub` | M→G | `fps` (1–30) | |
| `mirror.unsub` | M→G | `{}` | |
| `mirror.frame` | G→M | `w:160, h:144, png` (base64), `n`. About 3–10 KB per frame | yes |

Per-kind fields of `ui.open`:

| Kind | Fields |
|---|---|
| text | `pages[[lines]], cont[[bool]], auto?, stay?, instant?` |
| choice | `labels[], index, question?` |
| menu, list | `title?, items[{label,right?}], index, top, rows, screen?, footer?, money?` |
| quantity | `qty, max, unit?` |
| naming | `title, max, presets[], value, mon?` |
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

### Battles

| Type | Dir | Data | ROM |
|---|---|---|---|
| `battle.start` | G→M, C→S | `battle` (id), `kind:"trainer"\|"rival"\|"scripted"\|"static"\|"surf"\|"fish"`, `trainer?{cls,party,name?,npc?,map?,x?,y?}`, `team?[{dex,level,moves[],dvs{atk,def,spd,spc}}]`, `wild?{dex,level,x?,y?}`, `rules{catch,flee}`. Trainer and rival battles need `trainer` and `team`; static, surf and fish need `wild`; scripted needs `team` or `wild` | yes |
| `battle.result` | S→C, M→G | `battle, outcome:"win"\|"lose"\|"fled"\|"caught"\|"aborted"`, `caught?{uid,dex,level}` (required when caught) | |
| `battle.native` | G→M | `kind:"safari"\|"old_man"\|"ghost", phase:"start"\|"end"` | |

### Party, Pokédex, bag, PC

| Type | Dir | Data | ROM |
|---|---|---|---|
| `party.sync` | S→C, M→G | `party[{uid, dex, level, nick?, moves[], hp, maxhp, status?}], box{count}` (Cobblemon is authoritative) | yes |
| `party.give` | G→M, C→S | `give` (id), `dex, level, moves[]?, dvs?, nick?, ot?, source:"starter"\|"gift"\|"trade"\|"prize"\|"native_catch"` | yes |
| `party.take` | G→M, C→S | `uid, reason:"trade"\|"daycare"` | |
| `party.heal` | G→M, C→S | `{}` | |
| `party.rename` | G→M, C→S | `uid, nick` (empty clears) | yes |
| `party.item` | G→M, C→S | `item, uid, slot?, move?` (stone, TM/HM, Rare Candy) | |
| `dex.sync` | S→C, M→G | `seen[dex], owned[dex]` (dex ≤ 151) | |
| `bag.sync` | G→M, C→S | `items{item:qty}` (battle-usable subset) | |
| `bag.use` | S→C, M→G | `item, qty` (consumed in Minecraft) | |
| `pc.open` | G→M, C→S | `{}` | |
| `pc.closed` | S→C, M→G | `{}` | |
| `daycare.deposit` | G→M, C→S | `uid` | |
| `daycare.withdraw` | G→M, C→S | `uid, steps` | |

Species travel as national dex numbers, moves as Gen 1 move ids (= Showdown numbers), items as
engine symbols, and Pokémon identities as Cobblemon UUIDs (`uid`).

### Saving

| Type | Dir | Data | ROM |
|---|---|---|---|
| `save.begin` | G→M, C→S | `save` (id), `reason:"menu"\|"center"` | |
| `save.done` | S→C, M→G | `save, ok`; `re` optional | |
| `session.bind` | M→G | `world, multiplayer` (multiplayer save pairing) | |
| `session.bound` | G→M | `slot`; `re` optional | |

### Export (builder role)

| Type | Dir | Data | ROM |
|---|---|---|---|
| `export.index` | M→G, G→M | Request (M→G): `{}`. Reply (G→M, `re` set): `game, engine, bridge, resources[{kind,id,hash,size}]` | |
| `export.get` | M→G | `kind:"map"\|"tileset"\|"image"\|"sprite"\|"palettes"\|"encounters"\|"field"\|"names"\|"layout"\|"swaps", id` | |
| `export.data` | G→M | `kind, id, hash, data`; `re` required | yes |

#### Export resources

A builder connection (role `builder`) asks for `export.index`, then fetches each resource it needs with
`export.get`. Each resource's `data` has its own schema in
[`protocol/export/<kind>.schema.json`](../protocol/export/); everything in it is ROM-derived.

| Kind | Id | Contents |
|---|---|---|
| map | map symbol | The map record (size in blocks, `blocks`, `border`, `connections`, `warps`, `signs`, `objects`) plus derived fields: `surface`, `outside`, per-cell `cells` flags, `parent` (the warp that first leads into an interior, by BFS from the surface), `lastMap`, `dark`, `palette` |
| tileset | tileset symbol | Atlas image id, `tilesPerRow`, `blocks` (block → 16 tile ids), `animation`, and tile classes: walkable, counter, grass, door, warp, water, shore, cut tree, spinner, pads |
| image | `<folder>.<name>`, e.g. `tilesets.overworld` | A greyscale PNG (4 shades), base64: atlases, animation strips, effects |
| sprite | sprite symbol | A sheet of 16×16 frames: 0–2 standing down/up/left, 3–5 walking; right is left mirrored |
| palettes | `sgb` | SGB palettes: name → 4 RGB colours, lightest first |
| encounters | `all` | Slot buckets, grass/water tables per map (species symbol, dex, level), rod tables |
| field | `all` | Selected engine field tables as JSON (ledges, cut-tree swaps, dark maps, …) |
| names | `all` | Species → dex, move → number, item → number, with display names |
| layout | `kanto` | Block origins of the surface maps relative to `PALLET_TOWN`, from the engine's neighbour arithmetic |
| swaps | `all` | Runtime block-change sites (cut trees, card-key doors, scripted swaps) the builder prepares story cells for |

**Per-cell flags** (`map.cells`): one integer per 16-px cell, rows top to bottom. Like the engine,
a cell's behaviour comes from its bottom-left 8×8 tile.

| Bit | Value | Meaning |
|---|---|---|
| 0 | 1 | Walkable |
| 1 | 2 | Water (surfable, including shore) |
| 2 | 4 | Shore |
| 3 | 8 | Tall grass |
| 4 | 16 | Door tile |
| 5 | 32 | Warp tile (stairs, mats, …) |
| 6 | 64 | Counter |
| 7 | 128 | Cut tree |
| 8 | 256 | Warp pad |
| 9 | 512 | Hole |
| 10 | 1024 | Spinner tile |
| 11 | 2048 | A warp entry is on this cell |
| 12 | 4096 | A sign is on this cell |
| 13 | 8192 | Ledge |
| 14–15 | | Ledge hop direction: 0 down, 1 up, 2 left, 3 right |

**Hashes and the bundle.** A resource's `hash` is the SHA-256 (lowercase hex) of its canonical JSON
(sorted keys, `,`/`:` separators, UTF-8, non-ASCII unescaped), and `size` is that text's length in
bytes. A downloaded export is stored as an **export bundle**, which stays under `local/`:

```
<gameDir>/kantobridge/export/<game>/
  index.json          the export.index reply's data
  <kind>/<id>.json    one resource's data, exactly its canonical JSON (so the file's SHA-256 is its hash)
```

### Test only (role `test`)

| Type | Dir | Data | ROM |
|---|---|---|---|
| `debug.status` | M→G, G→M | Request: `{}`. Reply (`re` set): `npcs, topState, mode, flags`, plus other counts and flags; never content | |
| `debug.quit` | M→G | `code?`: asks the test driver to exit | |

### Relay only (client ↔ server)

| Type | Dir | Data | ROM |
|---|---|---|---|
| `story.state` | C→S | `full`; when full: `active[cell], surf, bike, lit[]`; otherwise deltas `add?[], remove?[], surf?, bike?, lit?`. A cell is `"MAP:x:y"` | |
| `warp.request` | C→S | `to{map,x,y,facing}, kind?` | |
| `warp.done` | S→C | `ok, to?`; `re` optional | |
| `activity` | C→S | `activity:"dialogue"\|"cutscene"\|"battle"\|"none"` (speech bubbles) | |
| `texture.status` | C→S | `ok, slotMapHash?, textures?, reason?` | |
| `world.index` | S→C | `index, count, hash, part` (the WorldIndex JSON in parts, on join) | yes |

**The WorldIndex** ([`protocol/world/world_index.schema.json`](../protocol/world/world_index.schema.json))
is what the builder writes to `<world>/kantobridge/world_index.json`:

- the scale (a cell is 2×2 blocks, the surface's feet level, the interior layer step);
- each map's block origin (the north-west corner of its cell (0, 0) at feet level), size in cells,
  layer, kind, palette, biome, darkness and parent;
- the slot-map hash;
- the story-cell table (`"MAP:x:y"` keys, each with its type and the object or swap site behind it);
- the habitat block positions and pools.

Clients turn block positions into cells and back with it. The `parts` of `world.index` are slices of
its JSON text; `hash` is the SHA-256 of the whole text.

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
