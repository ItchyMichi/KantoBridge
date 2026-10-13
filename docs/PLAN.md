# Kanto Bridge: implementation plan

Written 2026-10-03 for **one execution run** that codes the whole project, following the build order
in [DESIGN.md](DESIGN.md) §10. The milestones are ordered so that stopping after any one of them leaves a
committed, working state. A later run resumes from [PROGRESS.md](PROGRESS.md).

**Scale.** Expect roughly 25–40k lines across Lua, Java and Python. Do it milestone by milestone, with
parallel tracks inside each milestone (§7).

---

## 0. How to execute this plan

### 0.1 Read first, in order

1. [`CLAUDE.md`](../CLAUDE.md): hard rules, decisions, ownership, build and run.
2. [`docs/DESIGN.md`](DESIGN.md): the design. It is the source of truth; never edit it.
3. This plan.
4. [`docs/PROTOCOL.md`](PROTOCOL.md), [`docs/VERSIONS.md`](VERSIONS.md) and
   [`docs/REFERENCES.md`](REFERENCES.md).
5. **The research notes** in `refs/_research/` (git-ignored, on this machine only). They hold verified
   `file:line` references for every engine, Minecraft and Cobblemon hook this plan names. Before
   implementing a component, read the notes listed for it in Appendix A.

### 0.2 Ground rules for the run

- **CLAUDE.md's hard rules apply throughout.**
- **Milestones run in order.** A milestone is done only when its automated checks pass. If one can't
  pass, use the pre-approved fallback for it (§8) and record that in PROGRESS.md.
- **The protocol has one owner.** [`docs/PROTOCOL.md`](PROTOCOL.md) and `protocol/` (the JSON schemas)
  define it. Change the docs and schemas first, then both sides of the bridge.
- **Installs:**
  - No system-wide installs, and no changes to system settings (Java defaults, PATH).
  - Allowed: a project-local Python venv (`tools/.venv`) and Gradle/Maven downloads.
- **Gen1Recomp instances:**
  - The executing agent may launch only the **test instance** `local/gen1recomp-e2e/`.
  - It must never launch, read or change the user's instance `local/gen1recomp/`.
- **Minecraft:**
  - Don't use `runClient` for verification; nobody can see it. Use JUnit, GameTests and the headless
    server instead.
  - `runServer` is allowed for headless world builds once the user has accepted the EULA (§0.6).
- **Never push, publish, or create git remotes.**
- **Commit at the end of every milestone** (§0.5).

### 0.3 Data safety when testing on the user's real game

The user allows the agent to run the test instance and the world builder on their imported game, on
one condition: **the agent never reads or prints game content.**

- **ROM import:** the agent never imports a ROM; the user does.
- **Files under `local/`:** the agent never opens them, except:
  - listing file names;
  - reading the redacted reports our own tools write to `local/reports/`, which contain only counts,
    ids and hashes.
- **Logging:**
  - Every log line (gen1-bridge, fabric-bridge, tools) prints only message types, engine symbol ids
    (e.g. `PALLET_TOWN`, `EVENT_GOT_STARTER`), counts, lengths and short sha256 hashes.
  - Never print text, names, map or tile data, images, trainer teams or sprite pixels.
- **Schemas:** every ROM-derived field carries `"x-rom-derived": true`. Redaction in Python
  (`tools/kb_protocol`) and Java (`proto.Redact`) is driven by that flag.
- **Test assertions** compare structure, counts and hashes only. Failure messages print the schema
  path and error kind, never the values.
- **Generated output stays under `local/`** (all git-ignored), and is never committed, copied
  elsewhere or uploaded. That covers exports, packs, worlds and reports.
- **Gen1Recomp's crash log** (`%APPDATA%\pokemon-love2d\lua-error.log`) may contain game text. Never
  open it; tell the user it exists.

### 0.4 When to stop and ask

- **Pre-approved; just proceed:**
  - the defaults in §1.2;
  - the fallbacks in §8;
  - implementation choices inside a component's design.
- **Stop:** write the question in PROGRESS.md under "Blocked", commit, and end the run with the
  question. Stop when:
  - a hard rule would be touched;
  - a version pin would change;
  - a spike and its fallback both fail;
  - a user-visible behaviour not covered by §1 has to be decided;
  - something needs a system-wide install or the user's own Gen1Recomp instance.

### 0.5 Commits, progress log, resumption

- **`docs/PROGRESS.md`** is created in M0 and updated at every milestone. It holds:
  - the milestone table (status, date, commit);
  - deviations from the plan, with the reason for each;
  - open issues;
  - Blocked questions;
  - play-test items waiting on the user.
- **Commit message:** `M<n>: <title>`, with the attribution trailer the harness specifies. Commit
  nothing from `local/` or `refs/`.
- **`docs/PLAYTEST.md`** collects the user's manual checklist for each milestone, in the order to run
  it.
- **Resuming:** a new run reads PROGRESS.md and continues at the first milestone that isn't done.

### 0.6 Pre-flight: the user does this once, before execution starts

1. **Import your own ROM into both Gen1Recomp installs.** Run `local\gen1recomp\gen1recomp.exe` and
   `local\gen1recomp-e2e\gen1recomp.exe` once each, import the same ROM in each launcher, then close
   them.
2. **Check the console output.** In cmd or PowerShell, run
   `local\gen1recomp-e2e\gen1recomp.exe --console --game=red` (use `--game=blue` for a Blue ROM).
   Wait for the title screen, check that a line containing `[gen1-bridge] gen1-bridge loaded`
   appears, then close it. Note in PROGRESS.md (or tell the run) whether the console worked.
3. **Accept the Minecraft EULA for the headless dev server.** Read <https://aka.ms/MinecraftEULA>,
   then create `local\minecraft\eula.txt` containing `eula=true`. The agent needs this only for
   headless world builds; it must never accept the EULA itself.
4. **Review the defaults in §1.2.** Change any you disagree with.
5. **Start the run.** Close Gen1Recomp and Minecraft, then start the run with:
   *"Execute docs/PLAN.md from the first incomplete milestone in docs/PROGRESS.md."*

---

## 1. Decisions

### 1.1 Decided by the user

| # | Decision |
|---|---|
| U1 | **Dialogue, choices and naming use our own client-side screens** (keyboard A/B/arrows; Gen 1 limits). Cobblemon's dialogue screen is not used. |
| U2 | **The agent may run the test Gen1Recomp and the world builder on the user's imported game**, under §0.3. The user does the ROM imports and all in-game play-testing. |
| U3 | **Battles use modern Cobblemon rules** (`GEN_9_SINGLES`). Trainer teams keep Gen 1 species, levels and moves. |
| U4 | **Sound passes through Gen1Recomp.** Its window plays the music and effects; Minecraft's music is silenced; Cobblemon provides cries. |
| U5 | **Gen1Recomp v0.3.85** (the pin since 2026-10-13, [`specs/ENGINE_UPGRADE_PLAN.md`](specs/ENGINE_UPGRADE_PLAN.md); v0.3.49 during the run), the official Windows release zip, runs portable from `local/gen1recomp/` (the user's) and `local/gen1recomp-e2e/` (tests). |
| U6 | **The Gen1Recomp window stays visible behind Minecraft** and is never minimised. |
| U7 | **`gen1-bridge` may use engine internals** wherever that gives the most complete result. All internals go through one adapter module. |
| U8 | **ROM-derived data may pass through the host's Minecraft server**: text and trainer teams. Art never does. |
| U9 | **Other players may see a story battle's Pokémon.** |
| U10 | **No project licence**: a personal test project. *Superseded 2026-10-04: the user licensed the repository GPL-3.0-only and made it public ([`LICENSE`](../LICENSE), [`CREDITS.md`](../CREDITS.md)).* |
| U11 | **Transport:** `gen1-bridge` listens on `127.0.0.1:27151`; the Minecraft client connects and reconnects; the server gets story data only through each player's client. |

### 1.2 Plan defaults

These are pre-approved. Edit this table before execution to change any of them.

#### World and feel

| # | Default | Why |
|---|---|---|
| P1 | **Scale:** one 16-px cell = 2×2 blocks. Each block's top face shows one 8×8 Game Boy tile. The outdoor walking surface is at Y 64, with ground blocks at Y 63. | DESIGN §3 "such as 2×2" |
| P2 | **Movement:** jumping off (`generic.jump_strength` 0), sprinting off. Gen 1 speeds: walk ≈ 0.174, bike ≈ 0.347 movement attribute, plus `abilities.walkingSpeed` to match. | Faithful, and one-block walls are enough |
| P3 | **Per-player blockades** use invisible **story-cell blocks** whose collision depends on the moving player (§4.2). No movement mixin. A one-way **ledge collider** block handles ledges. NPC puppets collide on the client only. | Covers the client and the server without rubber-banding (`refs/_research/P4_mc_runtime.md` §3) |
| P4 | **World depth:** override `minecraft:overworld` in the mod jar's `data/` to `min_y` −256, `height` 576. Interiors go in layers 22 blocks apart below Y 64. | Silph Co.'s 11 floors stacked beneath one building need about 260 blocks |
| P5 | **Colours:** Super Game Boy palettes per map, Gen1Recomp's default mode. An interior uses its parent outdoor map's palette. | Matches what the user sees in Gen1Recomp |
| P6 | **Water** is a solid half-height tile block with animated Gen 1 water art. There is no Minecraft water. Story cells block it unless the player is surfing. | Gen 1 look; surfers walk on the surface |
| P7 | **Game rules and protection:** Peaceful difficulty, a damage veto, adventure mode, `randomTickSpeed` 0, and the full rule list in Appendix D. | `refs/_research/P4_mc_runtime.md` §6 |

#### Gameplay

| # | Default | Why |
|---|---|---|
| P8 | **Wild Pokémon:** grass, cave and Viridian Forest encounters come from Cobblemon habitat pools. **Surfing and fishing encounters are rolled by the hidden game**, then fought in Cobblemon against a spawned Pokémon. | DESIGN §5 turns off only *grass* encounters, and Cobblemon fishing mixes nearby pools |
| P9 | **Special battles run natively in the hidden game**, shown through the mirror screen: the Safari Zone, the old man's demo and the unidentified ghost. Safari catches cross over as gifts. | DESIGN §5 says these "need custom screens" |
| P10 | **Graphical screens use the mirror screen:** party, summary, Pokédex pages, town map, slots, trade, Hall of Fame and intro art. Text boxes, yes/no, naming and list menus are native Minecraft screens. | Full coverage at small cost |
| P11 | **Trainer Pokémon** get IVs = 2×DV+1 from the hidden game's DVs, nature Hardy, the species' first ability and no held item. Cobblemon AI skill: 1 for regular trainers, 3 for the rival and gym leaders, 5 for the Elite Four and Champion. | Deterministic and fair |
| P12 | **Experience:** Cobblemon's calculator with `experienceMultiplier` 1.0 and `awardExperienceOnBattleLoss` true. | `refs/_research/P6_cobblemon_integration.md` §7 |
| P13 | **Moves:** HM moves may be swapped out in Cobblemon (benched moves can be re-learned, so nobody softlocks). Field-move eligibility reads the shadow party. | DESIGN §5 open item |
| P14 | **Evolution:** Cobblemon's accept prompt for level-ups. Stones from the Gen 1 bag evolve at once. Evolutions into species beyond 151 are blocked. | Close to Gen 1's "B cancels" |
| P15 | **Daycare:** Gen 1's daycare, done with Cobblemon. Depositing holds the Pokémon server-side; withdrawing grants XP by steps. Lowest priority in M8. | DESIGN §5 open item |
| P16 | **Nicknames** change only through the Name Rater (and the catch or gift prompt). Cobblemon's summary rename is cancelled. | Gen 1 rule |
| P17 | **The bag** stays authoritative in the hidden game. Battle items (balls, medicine) are mirrored into the Minecraft inventory as Cobblemon items, and consumption syncs back. Key items never cross. | Cobblemon battles use the inventory |
| P18 | **Cut trees grow back** when the player leaves the map (Gen 1 behaviour). | Faithful |
| P19 | **Cycling Road:** the hidden game's downhill roll pulls the Minecraft player. Holding sneak = holding B (brake). | DESIGN §4 "Cycling Road's slope" |

#### Interface and audio

| # | Default | Why |
|---|---|---|
| P20 | **Keys (rebindable):** A = `Z` (plus right-click on targets), B = `X` (inside screens), Start = `Enter`, Select = `Backspace`. The `X` clash with vanilla's creative-only "load toolbar" key does no harm. | Free keys (`refs/_research/P4_mc_runtime.md` §8) |
| P21 | **Hidden-game display options are pinned to classic** at boot: no voxel, tilt, zoom, wide battle or dynamic layout. Text speed is FAST. | Mirror capture geometry (`refs/_research/P2_gen1_ui.md` §1.7) |
| P22 | **Gen 1 battle music** plays from the hidden game during Cobblemon battles, if the engine's music API allows. Cobblemon battle themes are silenced. Polish item in M8. | U4 |

---

## 2. Architecture

### 2.1 Components

```
Gen1Recomp v0.3.85 (visible, behind MC)        Minecraft client                         Minecraft server (integrated or dedicated)
  gen1-bridge (Lua)                              fabric-bridge (client)                     fabric-bridge (server) + Cobblemon 1.8.1
  - observes: player, NPCs, UI, story,   TCP     - BridgeClient (socket thread)   Fabric     - StoryState per player (attachment)
    bag, party, battles, saves       127.0.0.1   - movement sync, input lock,     payload    - story-cell collision (shared code)
  - acts: steps, A/B taps, UI answers, :27151    - puppets (client-only NPCs)     kantobridge:json   - warps (teleport), rules, saves
    field moves, battle results,      NDJSON     - our screens + mirror screen  <-------->   - battles (proxy NPC / static Pokémon)
    party/dex/bag writes          <---------->   - per-player story view                     - party / Pokédex / bag sync
  - exports world data for the builder           - generated resource pack                 - world builder (runs in the server)
                                                 - export client (builder role)
```

**Who owns what** (CLAUDE.md):
- Gen1Recomp owns the story, maps, NPCs, dialogue, bag, money, badges, the shadow party and the
  Pokédex.
- Cobblemon owns all Pokémon data, spawns and battles.
- Minecraft owns position, camera and visuals.

### 2.2 Units, scale and coordinates

- **Gen 1 units:**
  - tile = 8 px (art);
  - **cell = 16 px**, the walk grid. Every protocol coordinate is a cell `(map, x, y)`;
  - block = 32 px (map sizes, connection offsets).
- **Outdoor layout:** the 36 outdoor maps are laid out by breadth-first search from `PALLET_TOWN`
  over the connections, using the engine's own `computeNeighbors` arithmetic
  (`refs/_research/P3_gen1_data_export.md` §5). Global cell `(gx, gy)` maps to blocks `X = 2·gx`,
  `Z = 2·gy`, with Pallet Town's cell (0,0) at X 0, Z 0. The walking surface is at Y 64.
- **Gaps:** inside the 340×360-cell bounding box, empty space is filled with tree-wall void, as
  Gen1Recomp does.
- **Interiors:** layer *k* ≥ 1 has its floor surface at Y = 64 − 22·k.
  - Each interior sits under its entrance door. Multi-floor buildings stack downwards.
  - Tunnels (the Underground Paths, Diglett's Cave) sit under one end only.
  - Horizontal gaps are at least 16 blocks; any overlap moves to a deeper layer, then sideways.
- **WorldIndex:** the builder writes `<world>/kantobridge/world_index.json`, and the server syncs it to
  clients on join. It holds:
  - scale constants;
  - per map: origin, size in cells, layer, kind, palette and biome;
  - the slot-map hash;
  - the story-cell table;
  - habitat block positions.
- **Lookups:** `toCell(BlockPos)` and `toBlock(map, x, y)` (the centre of the 2×2 footprint) are pure
  and unit-tested.

### 2.3 Threads and ordering

- **gen1-bridge:**
  - Everything runs from the `input.step` hook, about 60 times a second. `core.update` doesn't fire
    under test drivers (`refs/_research/P7_testing_tooling.md` §1).
  - Sockets are non-blocking, with at most one injected tap per step.
  - The only other hook is a read-only `render.output` observer for mirror frames.
- **Client:**
  - The socket thread reads and writes and hands messages to the client thread through a queue. All
    game, UI, puppet and texture work happens on the client thread.
  - Story-state changes are applied to the local player's attachment **first**, then relayed from the
    client thread, so they stay ordered with movement packets.
- **Server:** payload handlers run on the server thread; battle outcomes come from Cobblemon events
  and callbacks.

### 2.4 Key flows

Each flow lists, in order, the messages and what happens. Message names are those in §3.

1. **Walking:**
   1. The Minecraft player crosses into a new cell, or pushes against an edge: `move.step{dir}`.
   2. gen1-bridge sets facing, holds the direction, and releases it on landing.
   3. The step lands: `player.pos{kind:walk}`. If refused: `player.blocked` → the Minecraft client
      snaps back to the cell centre.
2. **Talking:**
   1. Right-clicking a puppet or a block: `act.interact{x,y}`.
   2. gen1-bridge sets facing and taps A in the same step; `world.interacted` confirms it.
   3. A text box opens: `ui.open{kind:text}` → our TextBox screen.
   4. A in Minecraft: `ui.advance`. gen1-bridge taps A once the box is ready.
   5. `ui.close`.
3. **Yes/no and menus:** `ui.open{kind:choice|menu|list|quantity}` → our screens → `ui.pick`/`ui.back`/`ui.qty`.
   gen1-bridge applies the answer with taps.
4. **Naming:** `ui.open{kind:naming}` → our naming screen → `ui.name`. gen1-bridge picks NEW NAME if
   presets are shown, sets the glyphs and confirms.
5. **NPCs:**
   - Each step, gen1-bridge diffs `ow.npcs` and sends `npc.set`/`npc.add`/`npc.remove`/`npc.move`/`npc.face`.
   - The client keeps puppets (negative entity ids, client only) in step.
6. **Cutscenes and forced movement:**
   - `player.state{mode:forced|cutscene}` locks Minecraft input.
   - Each step start sends `player.pos{kind, frames}`; the client drives the player along the path.
   - Unlock comes when `player.state{mode:idle}`.
7. **Warps:**
   1. `warp{phase:start, kind}`: the client fades out.
   2. `warp{phase:arrive, to}`: the client sends `warp.request` to the server.
   3. The server teleports the player (same dimension).
   4. The client waits until the destination section is compiled, then fades in.
8. **Trainer battle:**
   1. The hidden game sights or talks to a trainer; the `trainer.before_battle` hook defers it.
   2. `battle.start{team}` → relayed → the server spawns a hidden battle-proxy NPC with that party and
      calls `BattleBuilder.pvn`.
   3. The end callback → `battle.result` → relayed → gen1-bridge applies a win (flags, rewards) or a
      loss (blackout).
9. **Static, surfing and fishing encounters:**
   1. `battle.start{kind:static|surf|fish, wild}`.
   2. The server spawns a no-AI, unbattleable `PokemonEntity` and calls `pve`.
   3. The outcome (captured, defeated, fled, lost) goes back as `battle.result`.
10. **Wild grass and cave Pokémon:** Cobblemon habitat spawns, battled natively. Pokédex and party
    changes reach the hidden game through `dex.sync`/`party.sync`.
11. **Gifts and trades:**
    1. The hidden game gives a Pokémon: `party.give`.
    2. The server adds it to the Cobblemon party (overflowing to the PC).
    3. The next `party.sync` rewrites the shadow party.
    4. In-game trades send `party.take` + `party.give`.
12. **Saving:**
    1. Hidden-game save, or a Pokémon Center heal: `save.begin`.
    2. The server saves player and Cobblemon data and the world: `save.done`.
    3. On load, `snapshot` rebuilds the per-player world view, and `party.sync` refreshes the shadow
       party.
13. **Reconnect:**
    1. The socket drops: the client pauses (input lock plus overlay) and retries with backoff.
    2. `hello` → `snapshot` → reconcile. Position comes from the hidden game; Pokémon from Cobblemon.
14. **Multiplayer:**
    - Each client relays only its own player's story state.
    - The server keeps a `StoryState` per player and uses it for story-cell collision, warps,
      battles and speech-bubble activity.

---

## 3. Protocol v1 (draft; transcribe in M0)

M0 writes these into `docs/PROTOCOL.md` and `protocol/` as JSON Schema 2020-12. The executor may add
fields. Changing a meaning needs a PROGRESS.md note.

### 3.1 Transport and envelope

- **Socket:** TCP on `127.0.0.1:27151`. Both sides read the port from config, so two pairs can run on
  one PC.
  - gen1-bridge listens and serves one connection at a time; a newer connection replaces the older.
  - The client connects at startup and reconnects with backoff (0.5 s doubling to 5 s).
- **Framing:** newline-delimited **strict** JSON, UTF-8, one object per line. The parsers reject
  NaN/Inf and trailing data, and empty objects stay `{}`.
- **Line size:** at most 4 MiB. Export pages are kept at or below 1 MiB.
- **Envelope:** `{"type": "<dotted>", "seq": <int ≥1, per sender>, "re": <seq, replies only>, "data": {…}}`.
- **Handshake** is the first exchange: `hello` → `hello.ok` | `hello.reject`. The protocol number must
  match exactly.
- **Heartbeat:** the client sends `ping` every 2 s; `pong` replies. With 10 s of silence, either side
  drops the connection.
- **Redaction:** every ROM-derived field in the schemas carries `"x-rom-derived": true` (§0.3).

### 3.2 Message catalog

Directions: **G→M** gen1-bridge to the Minecraft client; **M→G** the reverse. The **ROM** column
marks payloads with ROM-derived fields.

#### Session

| Type | Dir | Data (key fields) | ROM |
|---|---|---|---|
| `hello` | M→G | `protocol:1, role:"client"\|"builder"\|"test", mod, minecraft, player:{uuid,name}?, world:{id,multiplayer}?` | |
| `hello.ok` | G→M | `protocol, bridge, engine, game:"red"\|"blue", session` | |
| `hello.reject` | G→M | `reason, protocol` | |
| `ping` / `pong` | both | `t` (ms) | |
| `resync` | M→G | `{}`; asks for a `snapshot` | |
| `snapshot` | G→M | `player{map,x,y,facing}, state{…}, flags[], toggles{map:{obj:bool}}, blocks[{map,bx,by,block}], lit[], bag{}, money, coins, badges[], party[], dex{seen[],owned[]}, npcs{map,list[]}, ui?` | yes |
| `log` | G→M | `level, code, msg` (bridge diagnostics, never game text) | |
| `error` | both | `code, msg, re?` | |

#### Movement

| Type | Dir | Data (key fields) | ROM |
|---|---|---|---|
| `move.step` | M→G | `dir:"up"\|"down"\|"left"\|"right", n` (counter). Sent on a cell crossing **or** after pushing against an edge for 8 or more ticks (exit mats, gates). | |
| `move.face` | M→G | `dir` (turn in place while idle) | |
| `move.hold` | M→G | `button:"b", on:bool` (Cycling Road brake) | |
| `player.pos` | G→M | `map, x, y, facing, kind:"walk"\|"bike"\|"surf"\|"ledge"\|"script"\|"spin"\|"current"\|"slope"\|"push"\|"sync", frames, n?`. Sent at step start (target cell) and on non-step changes. | |
| `player.blocked` | G→M | `dir, reason:"tile"\|"entity"\|"bounds"\|"script", map, x, y` | |
| `player.state` | G→M | `mode:"idle"\|"screen"\|"forced"\|"cutscene"\|"busy", bike, surf, slope, hidden, dark, lit` | |
| `warp` | G→M | `phase:"start"\|"arrive", kind:"door"\|"stairs"\|"edge"\|"hole"\|"pad"\|"fly"\|"teleport"\|"dig"\|"rope"\|"blackout"\|"script"\|"elevator", to:{map,x,y,facing}?` | |
| `emote` | G→M | `target:"player"\|<npc id>, bubble:"!"\|"?"\|"happy"\|null, frames` | |

#### Interaction

| Type | Dir | Data (key fields) | ROM |
|---|---|---|---|
| `act.interact` | M→G | `map, x, y` (face the cell and press A) | |
| `act.button` | M→G | `button, how:"tap"\|"down"\|"up"` (mirror screen, start menu) | |
| `act.field` | M→G | `action:"cut"\|"surf"\|"strength"\|"flash"\|"dig"\|"teleport"\|"bicycle"\|"fish"\|"fly", x?, y?, dest?` | |
| `act.result` | G→M | `re, ok, kind?, reason?` | |

#### UI

| Type | Dir | Data (key fields) | ROM |
|---|---|---|---|
| `ui.open` | G→M | `ui` (id), `kind:"text"\|"choice"\|"menu"\|"list"\|"quantity"\|"naming"\|"mirror"`, plus per-kind fields (below) | yes |
| `ui.update` | G→M | `ui` plus the changed fields: `index, top, page, line, waiting, done, items` | yes |
| `ui.close` | G→M | `ui` | |
| `ui.advance` | M→G | `ui` (A on a text box; queued until ready) | |
| `ui.pick` / `ui.back` / `ui.qty` | M→G | `ui, index` / `ui` / `ui, qty` | |
| `ui.name` | M→G | `ui, glyphs[]` (Gen 1 charset, length checked) | yes |
| `mirror.sub` / `mirror.unsub` | M→G | `fps` (≤ 30) / `{}` | |
| `mirror.frame` | G→M | `w:160, h:144, png` (base64), `n`. About 3–10 KB per frame. | yes |

Per-kind fields of `ui.open`:

| Kind | Fields |
|---|---|
| text | `pages[[lines]], cont[[bool]], auto?, stay?, instant?` |
| choice | `labels[], index, question?` |
| menu, list | `title?, items[{label,right?}], index, top, rows, screen?, footer?, money?` |
| quantity | `qty, max, unit?` |
| naming | `title, max, presets[], value, mon?` |
| mirror | `screen?` |

#### NPCs

| Type | Dir | Data (key fields) | ROM |
|---|---|---|---|
| `npc.set` | G→M | `map, list[{id, sprite, kind:"person"\|"item"\|"boulder"\|"static"\|"trainer", x, y, facing, moving}]` | |
| `npc.add` / `npc.remove` | G→M | the npc / `id` | |
| `npc.move` | G→M | `id, x, y, facing, frames, kind:"walk"\|"script"\|"push"\|"march"` | |
| `npc.face` | G→M | `id, facing` | |

Sprite ids are engine symbols (`SPRITE_*`). The art comes from each player's own generated pack.

#### Story

| Type | Dir | Data (key fields) | ROM |
|---|---|---|---|
| `story.flags` | G→M | `set[], clear[]` (diff of `save.flags`) | |
| `story.toggles` | G→M | `map, obj, visible` | |
| `story.blocks` | G→M | `map, changes[{bx,by,block}]` (live map block diff: Cut, doors, scripted swaps) | yes |
| `story.lit` | G→M | `map, lit` | |

#### Battles

| Type | Dir | Data (key fields) | ROM |
|---|---|---|---|
| `battle.start` | G→M | `battle` (id), `kind:"trainer"\|"rival"\|"scripted"\|"static"\|"surf"\|"fish"`, `trainer:{cls,party,name,npc,map,x,y}?`, `team[{dex,level,moves[num],dvs{}}]?`, `wild:{dex,level,x,y}?`, `rules:{catch,flee}` | yes |
| `battle.result` | M→G | `battle, outcome:"win"\|"lose"\|"fled"\|"caught"\|"aborted"`, `caught:{uid,dex,level}?` | |
| `battle.native` | G→M | `kind:"safari"\|"old_man"\|"ghost", phase:"start"\|"end"` | |

#### Party, Pokédex, bag, PC

| Type | Dir | Data (key fields) | ROM |
|---|---|---|---|
| `party.sync` | M→G | `party[{uid, dex, level, nick?, moves[num], hp, maxhp, status?}], box:{count}` (Cobblemon is authoritative) | |
| `party.give` | G→M | `give, dex, level, moves[num]?, nick?, ot?, source:"starter"\|"gift"\|"trade"\|"prize"\|"native_catch"` | yes |
| `party.take` | G→M | `uid, reason:"trade"\|"daycare"` | |
| `party.heal` | G→M | `{}` | |
| `party.rename` | G→M | `uid, nick` | yes |
| `party.item` | G→M | `item, uid, slot?, move?` (stone, TM/HM, Rare Candy) | |
| `dex.sync` | M→G | `seen[dex], owned[dex]` | |
| `bag.sync` | G→M | `items{id:qty}` (battle-usable subset) | |
| `bag.use` | M→G | `item, qty` (consumed in Minecraft) | |
| `pc.open` / `pc.closed` | G→M / M→G | `{}` | |
| `daycare.deposit` / `daycare.withdraw` | G→M | `uid` / `uid, steps` | |

#### Saving

| Type | Dir | Data (key fields) | ROM |
|---|---|---|---|
| `save.begin` | G→M | `save, reason:"menu"\|"center"` | |
| `save.done` | M→G | `re, ok` | |
| `session.bind` / `session.bound` | M→G / G→M | `world, multiplayer` / `slot` (multiplayer save pairing) | |

#### Export (builder role)

| Type | Dir | Data (key fields) | ROM |
|---|---|---|---|
| `export.index` | M→G, then G→M | request: `{}`. Reply: `game, engine, bridge, resources[{kind,id,hash,size}]` | |
| `export.get` | M→G | `kind:"map"\|"tileset"\|"image"\|"sprite"\|"palettes"\|"encounters"\|"field"\|"names"\|"layout"\|"swaps", id` | |
| `export.data` | G→M | `re, kind, id, hash, data` | yes |

#### Test only (role `test`)

| Type | Dir | Data (key fields) | ROM |
|---|---|---|---|
| `debug.status` | M→G, then G→M | counts and states only (`npcs`, `topState` kind, `mode`, flags count) | |
| `debug.quit` | M→G | asks the test driver to exit | |

### 3.3 Server relay (client ↔ server)

One Fabric payload, `kantobridge:json` (a JSON string of at most 256 KiB), carries the same envelope
in both directions. Larger data is split at the application level.

| Direction | Types |
|---|---|
| client → server | `story.state` (the player's blockade-relevant state: active cells, surf/bike, lit maps; full on join, then deltas), `warp.request{to}`, `battle.start`, `party.give/take/heal/rename/item`, `bag.sync`, `pc.open`, `save.begin`, `activity{dialogue\|cutscene\|battle\|none}`, `texture.status` |
| server → client | `world.index` (chunked on join), `warp.done`, `battle.result`, `party.sync`, `dex.sync`, `bag.use`, `pc.closed`, `save.done`, `error` |

---

## 4. Component designs

### 4.1 gen1-bridge (Lua, inside Gen1Recomp)

Research notes: P1 (movement and state), P2 (UI and mirror), P3 (data and export), P7 (testing, JSON,
hot reload), A and B (mod system and engine).

#### Files

- Plain Lua 5.1 modules loaded with `load(mod:read(rel), "@"..rel)()`. Never `require` our own files.
- File names start with `kb_` to avoid modkit's generated-module name checks.
- Never write the strings `data/generated/` or `assets/generated/` in mod files.
- Tests live outside the mod folder (`tools/lua/`).

| File | Contents |
|---|---|
| `main.lua` | Bootstrap. Loads modules, wires hooks and events, owns the per-step pump |
| `lib/kb_json.lua` | Our own strict, deterministic codec: sorted keys, `{}` vs `[]` sentinels, no NaN, errors on trailing data |
| `lib/kb_net.lua` | Lazy listener (see below) |
| `lib/kb_proto.lua` | Envelope, sequence numbers, dispatch table, request/reply |
| `lib/kb_log.lua` | Redacted logging to stdout through `mod.log`, plus a `log` message to Minecraft |
| `lib/kb_engine.lua`, `lib/engine/gen1.lua` | The adapter loader and the Gen 1 engine adapter (MULTI_GAME_PLAN §4.3, MG2). **Only the adapters under `lib/engine/` touch engine internals.** Each function documents its engine `file:line` (v0.3.85 since 2026-10-13; `tools/pin_check.py` re-stamps the ones that only moved) and is re-checked on any pin change |
| `observe/kb_player.lua` | Player position, the classifier and `player.*` messages |
| `observe/kb_npcs.lua` | NPC diffing |
| `observe/kb_story.lua` | Flag, toggle, block and light diffs |
| `observe/kb_ui.lua` | UI classification and `ui.*` messages |
| `observe/kb_mirror.lua` | Frame capture |
| `observe/kb_bag.lua` | Bag diffs |
| `act/kb_move.lua` | The step driver |
| `act/kb_interact.lua` | Face-and-press-A |
| `act/kb_uiact.lua` | UI answers |
| `act/kb_field.lua` | Field moves |
| `act/kb_battle.lua` | Battle interception and results |
| `act/kb_party.lua` | Shadow party, gifts, trades, heals, renames, items, Pokédex, daycare |
| `act/kb_save.lua` | Saving and session pairing |
| `export/kb_export.lua` | Export index and pages |
| `export/kb_cells.lua` | Per-cell flag strings |
| `export/kb_images.lua` | PNG bytes plus base64 |
| `export/kb_swapsites.lua` | Curated runtime block-change sites |

#### The listener

- Bind lazily on the first `input.step`, not in the entry chunk.
- Bind without `reuseaddr` on Windows (elsewhere it only clears a crashed predecessor's TIME_WAIT
  remnants, never a live listener's port); retry every second while the port is busy, logging once.
- On `game.ready`, run `collectgarbage("collect")` once, so an old instance's sockets left by hot
  reload are closed.
- Call `settimeout(0)` on accepted clients.
- Buffer partial reads and keep any unsent tail.
- Load LuaSocket lazily: `require("socket")` inside a function, so `modkit validate` can still load the
  mod.

#### Hot-reload safety

- Prefer hooks and events: they die with the loader.
- If an engine function must be patched, patch it **once**, into a process-lifetime slot that points
  at "the current instance". Never chain patches.
  (`refs/_research/P7_testing_tooling.md` §6)

#### Classifier

- Each step, `classify()` returns one of:
  - `screen`: the top state isn't the overworld;
  - `forced`: the player is moved by `scriptMoves`, a spinner, a warp, Fly or a hole;
  - `cutscene`: the world is held but the player stands still;
  - `busy`: a transition or emote;
  - `idle`.
- It is built from `acceptsMenuInput`-like tests plus `hopLand`, `cutAnim`, `dustAnim.boulder`,
  `spin*` and `hole*` (`refs/_research/P1_gen1_movement.md` §3).
- Directions are injected only in `idle`. All holds are released the moment the state leaves `idle`.

#### Step driver

1. Set `ow.player.facing` directly, which avoids the 4-frame turn.
2. `mod.input:press(dir)`.
3. Release on the landing poll unless another step is queued (walk: 16 frames, bike: 8).
4. Watch start, land and bump through the `movement.collision` hook (filtered to the player) and
   polling, or idempotent wrappers on `Player.tryMove`/`Player.update`.
5. Keep a "desired holds" set and re-press after a focus-change `Input:reset`.
6. Queue at most 2 steps.

#### Interact

In one `input.step`, set the facing and `mod.input:tap("a")`. Confirm with `world.interacted`, and retry
once if the NPC was moving (`refs/_research/P1_gen1_movement.md` §5).

#### UI observer

- Poll `game.stack:top()` each step; use `screen.pushed`/`screen.popped` only as wake-ups.
- Classify by metatable (`mod.ui.TextBox/Menu/ListMenu/ChoiceBox/QuantityBox/NamingScreen`) plus
  `screenId`/`kind`.
- Small adapters handle `PartyMenu`, `PokedexMenu`, `PrizeCounter` and `TownMap`'s fly list. Anything
  else falls back to `kind:mirror`.
- **Text:**
  - Send `pages` + `contBefore` once, then the cursor.
  - Translate charmap codes (`<PK>`, `<MN>`, `#`→POKé, `é`, `♂`, `♀`, `×`, `¥`) into a small markup
    the client renders.
  - Tap A only when the "ready" predicate holds: `waiting and preWait==0`, or done and not
    choice/auto/stay (`refs/_research/P2_gen1_ui.md` §3.3).
- **Choice:** B always answers NO. For YES, set `index`=1, then tap A.
- **Naming:** once the grid is the top state (pick NEW NAME first if presets are up), set `glyphs` and
  call `confirm()`.
- **Opening:** wrap `intro.oak_speech.build`. Replace `name_player`/`name_rival` with `fn` steps that
  wait for `ui.name`, and leave Oak's text native and mirrored.
- **Nicknames:** answer through the `catch.nickname` and `pokemon.before_give` hooks. The Name Rater
  pushes `NamingScreen.new` directly, so the observer catches it.

#### Mirror

- While a client subscribes, the `render.output` observer returns `next(ctx)` unchanged and captures
  the frame:
  1. downscale the game rectangle into a 160×144 canvas;
  2. `newImageData()`;
  3. `encode("png")` with no filename;
  4. base64;
  5. send at most `fps` frames, skipping identical ones.
- Never hide states, never take over output, never write files (`refs/_research/P2_gen1_ui.md` §9).

#### NPCs

- Diff `ow.npcs` by `npc.id` each step: sprite = `appearance or def.sprite`.
- Kind:
  - `item` if `def.item` is set (and not "0");
  - `boulder` if `SPRITE_BOULDER` or `pushable`;
  - `static` if `def.pokemon`;
  - `trainer` if `trainerClass`;
  - otherwise `person`.
- Facing changes, moves (from `targetX/Y`, `stepFrames`) and the global `ow.emote` are sent as they
  happen.

#### Story diffs

| What | How it's watched |
|---|---|
| `save.flags` | Full diff every 10 steps and on script end. `flag.changed` misses direct writes. |
| `save.objectToggles`, `itemsTaken`, `defeatedTrainers` | Diff |
| Live map blocks | Snapshot of `ow.map` blocks and `ow.cutBlocks` at map enter, then diffed after scripts, Cut and boulders |
| Lit maps | `save.flashLit`, `ow.dark` |

#### Battles

- **Wild encounters:** wrap `encounter.roll` to return `nil`:
  - **pass through** on Safari maps (the native battle shows on the mirror);
  - while surfing, take the rolled species/level and **suppress** the native battle, sending
    `battle.start{kind:surf}`.
- **Fishing:** wrap `encounter.fishing` the same way (`kind:fish`).
- **Trainers:** the `trainer.before_battle` hook defers. The team comes from
  `Pokemon.movesAtLevel` plus the copied special-move table, the rival's starter offset and fixed DVs
  (`refs/_research/P3_gen1_data_export.md` §7).
- **Scripted battles** (rival, Snorlax, the ghost Marowak, statics): wrap `script.command` for
  `start_battle`/`rival_battle`/`static_battle`, and the talk path for static objects.
- **Applying `battle.result`:**
  - win: cancel the deferred battle and run the engine's win side-effects (`defeatedTrainers`, event
    flag, `checkVictoryRewards`, `afterBattle`);
  - lose: the engine's blackout path (the Oak's-lab rival loss goes on, as in Gen 1);
  - for scripted battles: resume the script with `ctx.lastBattleResult`.
- **Native special battles:** `battle.native{start/end}` for the Safari Zone, the old man and the
  unidentified ghost.

#### Party and Pokédex

- **Shadow party:** apply `party.sync` by rebuilding `save.party` with the engine's `Pokemon.new`
  plus the given moves, and store uids in `mod.save`.
- **Pokédex:** apply `dex.sync` to `save.pokedex`.
- **Gifts:** `pokemon.received`/`give_pokemon` → `party.give`, and stop the Gen 1 party from adding a
  duplicate (the next `party.sync` is authoritative).
- **Heal:** `party.heal` at the nurse's heal.
- **Bill's PC:** open `pc.open` by overriding the PC screen id or wrapping its menu entry. Item
  storage stays native.
- **Item use on a Pokémon** (stone, TM/HM, Rare Candy) → `party.item`.
- **Name Rater** → `party.rename`.
- **Field-move eligibility:** the shadow party already carries the moves. Optionally answer through
  the `fieldmove.eligibility` hook.

#### Export

- Read through `mod.datasets:open(GameVersion.get())`: detached copies; the live tables change in
  play.
  - Fallback if the dataset view fails on the portable build: deep-copy the live `Data` right after
    `mods.loaded`.
- Compute per-cell flags in Lua with `Map.new(def, tileset)` methods:
  walkable, water, shore, grass, ledge direction, door, warp, counter, cut-tree, pad or hole, spinner.
- Also export:
  - layout (`computeNeighbors` from Pallet Town);
  - each interior's parent door, with `LAST_MAP` resolved;
  - palettes (SGB name per map plus colours);
  - images (atlases, sprite sheets, flowers, spinner, cut tree);
  - encounters (grass/water tables, slots, buckets), Super Rod, names (dex, move and item ids);
  - field tables;
  - swap sites.
- Paged as `export.index` + `export.get`.

#### Saving and sessions

- `save.writing` → `save.begin`.
- A Pokémon Center heal also triggers both saves: `mod.game:writeSave()`, a spike in M7.
- `session.bind` loads or creates the save slot paired with the world (multiplayer); fallback: the
  launcher passes `--slot`.

#### Options pin (P21)

On `game.ready`, set the hidden game's display options to classic and text speed to FAST.

### 4.2 fabric-bridge: common (`src/main`)

Research notes: P4, P5, P6.

#### Protocol and relay

- `proto/`: `Envelope`, type constants generated from `protocol/catalog.json`, a Gson codec, and
  `Redact` (reads `x-rom-derived` from the bundled catalog).
- `net/KbJsonPayload`: a record with `ByteBufCodecs.stringUtf8(262144)`, registered C2S and S2C in
  common init. Relay handlers run on the server thread.

#### World data

`world/`: `WorldIndex`, `MapPlacement`, `CellPos`, `Scale`, `StoryCellTable`, `SlotMap`. All pure Java,
unit-tested.

#### Story state

- `story/StoryState`: a Fabric data attachment on `Player`, on both sides. It holds:
  - active blockade cells;
  - boulder positions;
  - surf/bike flags;
  - lit maps.
- In singleplayer, client and server share statics, so the state must never live in statics.

#### Blocks

- **Tile blocks:** families `kantobridge:tile_full_NN` and `tile_half_NN`, with properties `hi`/`lo`
  0..15. Never one 0..255 property: that costs 95 MB vs 5 MB (`refs/_research/P5_mc_content_testing.md` §1).
  - Shapes are per family. No `onPlace`.
  - Placeholder blockstates and models ship in the jar; the generated pack overrides them per state.
- **`StoryCellBlock` + block entity:**
  - Invisible (`RenderShape.INVISIBLE`), with an empty outline shape so it never catches the
    crosshair, no occlusion and no light blocking.
  - `getCollisionShape`: given an `EntityCollisionContext` whose entity (or the player controlling
    it) has this cell active in `StoryState`, return a full cube; otherwise empty. With no entity,
    return a full cube (pathfinding).
  - The block entity holds the cell key and type (`object`, `tile`, `water`, `boulder_area`).
- **`LedgeColliderBlock`:**
  - Has `HORIZONTAL_FACING`.
  - A 24-px box when the entity's bounding box lies entirely on the downhill side; empty otherwise;
    solid for an empty context.
  - The hidden game performs the hop; Minecraft only allows the drop.

#### Entities, data and mixins

- **`PuppetEntity` type:** plain `Entity`, `noSave().noSummon()`, registered in common init but only
  ever spawned on the client.
- **Static data in the jar's `data/`** (all stable `KnownPack`, so no "experimental" warning):
  - void world preset `kantobridge:kanto` (`layers: []`, `structure_overrides: []`);
  - tag `#minecraft:normal` and lang key `generator.kantobridge.kanto`;
  - biomes `kanto_outdoor`, `kanto_interior`, `kanto_cave`: music `minecraft:intentionally_empty`, no
    `mood_sound`, empty spawners;
  - the overworld dimension-type override (P4);
  - NPC classes `kantobridge:battle_proxy[_gym|_rival|_elite]`;
  - spawn rule `no_interior_spawns`;
  - the PC, healer and pasture recipes disabled through load conditions.
- **Mixins (common):** `NPCEntity#broadcastToPlayer` at HEAD returns false for our battle proxies, so
  nobody sees them; the fighter's client draws the puppet.

### 4.3 fabric-bridge: client (`src/client`)

| Package | Contents |
|---|---|
| `bridge/` | `BridgeClient` (socket thread, reconnect, heartbeat, handshake), `Dispatcher` (to the client thread), `ConnectionHud` (status corner and pause overlay), `ExportClient` (builder role, on its own short connection) |
| `move/` | `MovementSync`, `InputLock`, `ForcedMover`, `WarpFade`, `SpeedSync` (below) |
| `puppet/` | `PuppetManager` (negative ids from −1,000,000 downwards; re-add after world change), `PuppetRenderer` (below) |
| `ui/` | Our screens and keybinds (below) |
| `story/` | `ClientStoryView`, `StoryCellRenderer` (below) |
| `packs/` | Mixin on `BuiltInPackSource.loadPacks` (client only) that injects `<gameDir>/kantobridge/resourcepack/` as a **required** pack, then `delayTextureReload()`; `PackStatus` |
| `fx/` | `SpeechBubbles`, `BlockadeFade` (below) |
| `world/` | `CreateKantoWorldFlow` (below) |

**Movement (`move/`):**
- `MovementSync`:
  - tracks the feet cell through the WorldIndex;
  - sends `move.step` on adjacent crossings, plus edge-push intent (direction held against a wall for
    8 or more ticks);
  - sends `move.face` on a quadrant change while idle;
  - handles blocked snap-back and drift correction (more than 500 ms out of step: steer back; more
    than 2 cells: teleport).
- `InputLock`: a `KeyboardInput.tick` TAIL mixin.
- `ForcedMover`: per-tick `setDeltaMovement` towards the target cells, over the frames given.
- `WarpFade`: a `HudRenderCallback` overlay, then `warp.request`, then a wait for `isSectionCompiled`.
- `SpeedSync`: speeds are set on the server; jump and sprint are off.

**Puppets (`puppet/`):** `PuppetRenderer` is an upright billboard with `entityCutoutNoCull` and frames:
- 0–2 standing down/up/left, 3–5 walking; right is left mirrored;
- the frame is picked from the NPC's facing relative to the camera;
- emote bubbles.

Clicks: `UseEntityCallback` → `act.interact`, returning **FAIL**. `ClientPreAttackCallback` cancels
left-clicks. `canBeCollidedWith()` is true, so puppets collide for the local player only.

**UI (`ui/`):**
- Every screen sets `isPauseScreen()` to false and overrides `renderBackground` so the world stays
  visible.
- `Gen1Box` widget: a white box with a black border.
- `TextBoxScreen`: typewriter text, ▼, scroll/page following `cont`, keyboard A/B.
- `ChoiceOverlay`.
- `MenuScreen`/`ListScreen`: generic, with a right column and footer/money.
- `QuantityScreen`.
- `NamingScreen`: Gen 1 charset, length 7 or 10, presets first.
- `MirrorScreen`: one reused 160×144 `DynamicTexture` with nearest filtering, scaled integer-wise.
  Keys map to buttons. It subscribes on open and unsubscribes on close.
- `StartMenuKey`: sends `act.button start` when idle.
- `Keybinds`: from P20.
- Optional polish: a Gen 1 bitmap font built into the generated pack.

**Story view (`story/`):**
- `ClientStoryView` turns story messages plus the story-cell table into the local `StoryState`, then
  relays `story.state` deltas.
- `StoryCellRenderer` (block-entity renderer) draws the per-player tile overlay (tree, closed door,
  swapped block) from slot-mapped textures when the local player's state has it closed.

**Effects (`fx/`):**
- `SpeechBubbles`: `WorldRenderEvents.AFTER_ENTITIES`, using the name-tag recipe, fed by a synced
  `activity` attachment (`allButTarget`).
- `BlockadeFade`: other players render translucent while standing in your blockade cells.

**Create Kanto world (`world/`):** a title-screen button, `CreateKantoWorldFlow`:
1. check the Gen1Recomp connection;
2. export through `ExportClient` to `<gameDir>/kantobridge/export/`;
3. generate the resource pack (tiles, sprites, slot map) and reload;
4. `createWorldOpenFlows().createFreshLevel(...)` with preset `kantobridge:kanto`, game type
   Adventure, and the game rules from Appendix D;
5. the server-side builder runs with a progress screen.

**Multiplayer clients:** on join, compare the server's slot-map hash with the local pack's. If they
differ, export your own art, regenerate the pack against the server's slot map, and reload.

### 4.4 fabric-bridge: server

| Service | Responsibility |
|---|---|
| `Rules` | On `SERVER_STARTED`: game rules (Appendix D), Peaceful plus difficulty lock, default game type Adventure, clear weather, fixed time. On join and respawn: Adventure plus Gen 1 speeds. `ALLOW_DAMAGE` veto for players. Cobblemon settings (Appendix C) at `SERVER_STARTING`; starter locked on `DATA_SYNCHRONIZED`. |
| `RelayService` | Dispatches client payloads and validates the sender (a player can only affect themselves). |
| `StoryStateService` | Applies `story.state` to the player's attachment. |
| `WarpService` | `warp.request` → `teleportTo` in the same level → `warp.done`. On join, teleports to the player's hidden-game position from the client's snapshot. |
| `BattleService` (trainer) | Spawns a `battle_proxy` NPC at the trainer's cell: `NPCPartyStore` from the team, exact moves through `MoveSet.setMove`, IVs from DVs, nature Hardy. Calls `pvn(player, npc, null, GEN_9_SINGLES, false, false)`. Classifies the end in `onEndHandlers` (with a `BATTLE_FLED` flag) as win, lose (forfeit is a loss), or aborted when there are no winners. Discards the proxy 7–10 s later. Tags proxies and cleans them up on load. |
| `EncounterService` (static, surf, fish) | Spawns a `PokemonEntity` (`no_ai=true`, persistent, `UNBATTLEABLE`, not counted towards the spawn cap, exact moves) and calls `pve(..., fleeDistance 12)`. Cancels `THROWN_POKEBALL_HIT` outside its battle. Maps captured, defeated, lost, fled and aborted. Discards the entity after. |
| `PartyService` | Snapshots party and PC on `DATA_SYNCHRONIZED` and on change (debounced) → `party.sync`. `party.give` → `PlayerPartyStore.add` with exact moves and OT. Also handles take, heal (`party.heal()`), rename (`setNickname`; player renames cancelled through `POKEMON_NICKNAMED`), and items (stones with `forceEvolve` of the matching `ItemInteractionEvolution`; TMs/HMs with `setMove`; Rare Candy with `addExperienceWithPlayer`). Filters evolutions beyond 151 in `EVOLUTION_TESTED`. |
| `DexService` | `POKEDEX_DATA_CHANGED_POST` (dex ≤ 151) → `dex.sync`. Hidden-game Pokédex → `setKnowledgeProgress` (no events, so no loop). |
| `BagService` | `bag.sync` → adds or removes mapped Cobblemon items in the inventory. Items consumed in Minecraft → `bag.use`. |
| `PcService` | `pc.open` → a custom `PCLink` + `OpenPCPacket`. The client sends `pc.closed` when the PC screen closes. |
| `SaveService` | `save.begin` → save players and Cobblemon storage, save all chunks → `save.done`. |
| `SessionService` | World id, `session.bind` relay, and the multiplayer save pairing record. |
| `HabitatService` | At build time, places and configures habitat blocks. At runtime, verifies they're loaded and logs counts. |

### 4.5 The world builder (Java, runs inside a server)

Research notes: P3, P5, D (height rules). The builder takes `(ServerLevel, ExportBundle, filter,
bounds?)`, so it runs in a GameTest, the integrated server or a headless server.

#### ExportBundle

- Written by `ExportClient`, or by `ExportCli` (a plain Java `main`, run headless with Gradle's
  `JavaExec`).
- Stored as `<gameDir>/kantobridge/export/<game>/` with an index, per-resource files and hashes.
- ROM-derived; stays under `local/`.

#### Layout

- BFS from `PALLET_TOWN`. Assert that all 72 directed edges agree and nothing overlaps.
- Compute the bounding box and void fill.

#### Interiors

- Use the parent-door table; place each interior under its door.
- Floor chains stack downwards.
- Pack per layer with 16-block gaps. Spill to a deeper layer, then sideways.
- Tunnels go under one end.
- Write placements into the WorldIndex.

#### Cell model and heights

Re-implement Dramaless Shape's documented rules; don't copy its code
(`refs/_research/D_voxel_pokered.md`).

| Cell | Built as |
|---|---|
| Ground, grass, flowers | A full tile block at Y 63 (feet Y 64) |
| Water | A half tile block, top at Y 63.5 |
| Ledge | Ledge art on top, plus a `LedgeColliderBlock` at Y 64 |
| Solid | A tile column: walls and trees 2 blocks high, roofs and cliffs 4, void fill 3. Measured building heights are an optional polish |
| Interior | Floor at the layer surface, walls 2 blocks, an opaque ceiling 4 blocks above the floor, and `minecraft:light` at level 15 on a 4-block grid, except on dark maps |
| Rock Tunnel | No lights; Flash gives the player Night Vision while on those maps |

- **Top faces** carry tile art. **Side faces** reuse the top tile in the MVP; folding the art upright
  is a polish item.
- **Counters** are 1 block high, so clerks stay clickable.
- **Avoid barriers** in front of anything clickable. They aren't needed with jumping off.

#### Slot map, textures and pack

- **Slot map:** a deterministic function `(atlas id, palette id, tile index, shape) → (block family, hi, lo)`,
  enumerated in sorted order. It is written into the world and is authoritative in multiplayer.
- **Textures:**
  - Colour each greyscale atlas tile with its SGB palette (shade → colour).
  - Upscale 8×8 to 16×16 with nearest-neighbour.
  - Water and flower tiles get `.mcmeta` animations (Game Boy frames → ticks, about 20 frames = 7
    ticks).
  - Sprites: colour with the map palette, keep shade 0 transparent, and lay out the sheet frames.
- **Resource pack writer:**
  - `pack.mcmeta` (`pack_format` 34);
  - `assets/kantobridge/blockstates/*.json`, every one of the 256 variants per block (unused ones point
    at a placeholder);
  - models;
  - `textures/block/tile/...`;
  - `textures/npc/...`;
  - `sounds.json` (none needed: silent biomes).
- **Datapack writer:** `<world>/datapacks/kantobridge_generated/` with `pack_format` 48, **no worldgen
  entries**. It contains `data/kantobridge/habitat_pools/<map>.json`:
  - one entry per Gen 1 slot;
  - `bucket: "common"`;
  - weight = the slot's percentage;
  - `levelRange` = the single level;
  - species = Cobblemon id by dex number;
  - no phases.

#### Placing blocks

- **BlockPlacer:** a time-sliced server task (20–30 ms per tick), per chunk, top-down.
- Flags: `setBlock(…, 18)`, or `16` while nobody can see the chunk.
- Holds a region ticket per chunk and waits on `lightEngine.waitForPendingTasks` before moving on.
- Re-runnable: clears its area first.
- Writes a progress report.
- **Biomes:** per 4×4×4 cell with `fillBiomesFromNoise`, then `resendBiomesForChunks`. Never
  `/fillbiome`, which is capped at 32,768 blocks.
- **Story cells:** placed at:
  - every STAY-object cell (people, items, statics, guards, Snorlax);
  - every cut-tree cell;
  - card-key doors;
  - curated swap sites;
  - every water cell;
  - every walkable cell of maps with boulders.

  Their types and sources go into the story-cell table.
- **Habitat blocks:**
  - For each map with grass or cave encounters, cover its grass (or cave floor) cells greedily with
    radius 14.
  - Place each block 2 blocks under the ground with `Level.setBlock`.
  - Configure it through the block-entity API: `NaturalHabitatSpawning`, `ReplaceSpawns=false`,
    `RangeOfInfluence` 14, `PoolId`, `PhaseOrder` SIMPLE, then `refreshFromSettings()`.
  - No habitat blocks in the Safari Zone.

#### Finishing up

- Spawn platform and default spawn at Pallet Town.
- Write `world_index.json`, the story-cell table, the slot map and `build_report.json` (counts and
  hashes only, also copied to `local/reports/`).

#### Running the builder

| Path | How |
|---|---|
| User | Title-screen "Create Kanto world" (§4.3) |
| Agent, headless | `tools/e2e/build_world.py` launches the test Gen1Recomp at the title screen (driver mode), runs `ExportCli`, then `./gradlew runServer` with `-Dkantobridge.build=<bundle>` and `server.properties` set to `level-name=saves/Kanto`, `level-type=kantobridge:kanto`. The world lands in `local/minecraft/saves/Kanto`, where the dev client can open it. Needs the user's EULA from §0.6. |

### 4.6 tools/

| Item | Contents |
|---|---|
| `requirements.txt` | Wheel-only pins: pytest 9.1.1, pytest-timeout 2.4.0, jsonschema 4.26.0 (+ dependencies), lupa 2.8 |
| `setup.ps1`, `setup.sh` | Create `tools/.venv` |
| `pytest.ini` | Test configuration |
| `check.ps1`, `check.sh` | One entry point for every automated test: Python, Lua, Gradle `build` (JUnit + GameTests) |
| `kb_protocol/` | Framing, schema registry (`referencing`), message builders, redaction |
| `fake_gen1/` | Listens like gen1-bridge. Holds a **synthetic** world: a procedurally drawn tileset, a 10×9-block test town, a house, 2 NPCs, a sign, a trainer. Has scripted scenarios and a CLI for manual driving |
| `fake_minecraft/` | Connects like the client. Runs scenarios, validates every message, redacts logs |
| `lua/` | lupa runner (`lupa.luajit21` only), fake LuaSocket with exact 3.0-rc1 semantics, a sandbox-parity environment, a small assert library |
| `e2e/` | `run_gen1recomp.py`: launches `local/gen1recomp-e2e/gen1recomp.exe` with `POKEPORT_DRIVER`, `POKEPORT_CONSOLE=1`, `POKEPORT_BACKGROUND=1`, a timeout, and stdout captured and redacted |
| `e2e/drivers/*.lua` | Our own unsandboxed driver scripts. They can `startNewGame({intro=false})`, warp, and set flags, items and party for scenario setup, and they print only PASS/FAIL markers |
| `e2e/test_*.py` | Scenarios run through `fake_minecraft` |
| `e2e/build_world.py` | The headless builder path (§4.5) |
| `launch.ps1` | Dev launch: Gen1Recomp (`--game` from config) visible, waits for the port, then `runClient` |

---

## 5. Testing strategy

| Layer | Tool | Covers | Runs with |
|---|---|---|---|
| Lua unit | lupa (LuaJIT 2.1) + pytest | codec, framing, proto, classifier, step driver, UI observer, NPC diff, export shaping. Uses fake engine tables, a fake socket and sandbox parity | `tools/check` |
| Python unit and contract | pytest + jsonschema | schemas and examples, fake_gen1 ↔ fake_minecraft, gen1-bridge (in lupa) ↔ fake_minecraft over a Python-backed socket shim | `tools/check` |
| Java unit | JUnit 5 via `fabric-loader-junit` 0.19.5 | `WorldIndex`, layout, interior packing, cell model, slot map, palettes, texture generation, pack and datapack writers, protocol codec and redaction, movement-sync logic, text paging, naming rules, name and id mapping | `gradlew build` |
| GameTest (server) | Fabric GameTest (Loom `fabricApi.configureTests`) | synthetic world build inside test bounds; tile, story-cell and ledge collision with mock players; habitat placement and configuration; battle-proxy spawn, party building, battle start and forced end; static encounter spawn; relay handlers; rules | `gradlew build` (`runGameTest`) |
| Stand-in integration | JUnit + `fake_gen1` subprocess | the `BridgeClient` state machine (reconnect, heartbeat, resync) without Minecraft | `gradlew build` |
| End-to-end, real engine | `tools/e2e` + drivers + fake_minecraft on `local/gen1recomp-e2e` | handshake, snapshot, steps, warps, interaction and UI flows, mirror frames, battle start and result application, export completeness. Content is never printed | `tools/check --e2e` (skipped with a warning if pre-flight isn't done) |
| Builder end-to-end, real data | `tools/e2e/build_world.py` | the real export and world build, judged from report counts only | M2, M6, M8 gates |
| Manual play-test | the user | everything visible | `docs/PLAYTEST.md` after the run |

There are no client GameTests on 1.21.1, so keep client logic in pure classes with thin Minecraft glue.

---

## 6. Milestones

Every milestone ends with:
1. `tools/check` green (plus `--e2e` where listed);
2. PROGRESS.md updated;
3. new items appended to PLAYTEST.md;
4. a commit.

The track tags are **[L]** gen1-bridge Lua, **[C]** client, **[S]** server/common, **[B]** builder,
**[T]** tools and tests, **[D]** docs.

### M0: Foundations, tooling and spikes

**Tasks:**
- [D] Write the protocol v1 catalog (§3) into `docs/PROTOCOL.md`, `protocol/envelope.schema.json`,
  `protocol/messages/*.schema.json` and `protocol/catalog.json`, with `x-rom-derived` annotations and
  synthetic examples.
- [D] Update CLAUDE.md's pointers and create `docs/PROGRESS.md` and `docs/PLAYTEST.md`.
- [T] `.gitignore`: add `.venv/`, `__pycache__/`, `.pytest_cache/`.
- [T] The venv, requirements, pytest configuration, `check` scripts, `kb_protocol`, the lupa runner,
  the fake socket and the sandbox-parity environment.
- [L] `kb_json` (with property tests against Python `json`), `kb_net` framing, `kb_proto`, `kb_log`,
  and the `kb_engine` skeleton. `main.lua` loads modules with `mod:read` + `load`.
- [S] Gradle changes:
  - `fabric-loader-junit` and `test { useJUnitPlatform() }`;
  - `fabricApi { configureTests { createSourceSet = true; modId = "kantobridge-gametest"; enableClientGameTests = false } }`
    plus a delete step for the old GameTest world;
  - client and server run directories set to `../local/minecraft`.
- [S] Mixin configs (common and client), the `proto` codec and `Redact`, `KbJsonPayload` plus a relay
  skeleton, and the static jar data: preset, tag, lang, biomes, dimension-type override, NPC classes,
  spawn rule, recipe disables.

**Spikes:** each one records pass or fail in PROGRESS.md, and a fail uses its fallback from §8.

| Spike | Checks |
|---|---|
| S1 | Cobblemon starts in the GameTest server; a mock player is possible |
| S2 | Tile-block registration (32 × 256 states) and memory |
| S3 | The resource-pack injection mixin compiles and resolves, with a GameTest/JUnit check of the pack source logic |
| S4 | lupa loads a sandbox-parity module and the fake socket round-trips |
| S5 | The e2e runner launches the test instance with a driver that prints "KB_READY" after `game.ready` and exits. Skipped if pre-flight isn't done |

**Checks:** `tools/check` green, the schemas validate their examples, and S1–S4 pass (S5 if pre-flight
is done). **Commit "M0: foundations".**

### M1: Bridge connection

**Tasks:**
- [L] Lazy listener with the hot-reload-safe bind; single client; handshake; heartbeat; `snapshot`
  (player and state); per-step `player.pos`/`player.state` with the classifier; log forwarding;
  options pin (P21).
- [C] `BridgeClient` (socket thread, backoff reconnect, heartbeat, handshake), `Dispatcher`,
  `ConnectionHud`, and a debug overlay key that shows the hidden map id, cell and mode.
- [T] `fake_gen1` (synthetic town, handshake, position stream), `fake_minecraft`, and JUnit
  integration of `BridgeClient` against `fake_gen1`.
- [T] `e2e/test_handshake.py`. Driver: `startNewGame({intro=false})`. Asserts: `hello.ok` (protocol,
  engine `0.3.49`), the snapshot has `player.map/x/y`, at least 3 heartbeats, and a reconnect after the
  client drops.

**Checks:** `tools/check --e2e` green.
**Play-test:** start Gen1Recomp (`--game=red`) and `runClient`; the HUD shows "connected", and the debug
overlay changes as you walk in Gen1Recomp's own window.
**Commit.**

### M2: World export and builder (Pallet Town and its interiors)

**Tasks:**
- [L] Export service: `export.index` and `export.get` for every kind in §3.2; per-cell flags through
  `Map.new`; the layout; parent doors with `LAST_MAP` resolved; palettes; images as PNG base64; an
  initial swap-sites table (cut trees and card-key doors).
- [C] `ExportClient`, plus `ExportCli` for headless use.
- [B] ExportBundle I/O, layout, interior placer, cell model and heights, slot map, palettes and
  textures, resource pack writer, BlockPlacer, biomes, story-cell planner, spawn, WorldIndex and
  reports. Maps are filtered: `PALLET_TOWN`, `REDS_HOUSE_1F`, `REDS_HOUSE_2F`, `BLUES_HOUSE`,
  `OAKS_LAB`.
- [S] Tile blocks, `StoryCellBlock` and `LedgeColliderBlock` (block entity and collision logic),
  `WorldIndex` sync on join.
- [C] The pack-injection mixin and reload; the "Create Kanto world" flow (export → pack →
  `createFreshLevel` → build with progress).
- [T] GameTests: a synthetic bundle builds inside the test bounds (blocks, heights, story cells,
  ledge, biomes). JUnit for layout (synthetic graph), packing, slot-map determinism, palette
  colouring and WorldIndex round trips.
- [T] `e2e/build_world.py --maps pallet`, real data. Report asserts: `maps_built=5`, `overlaps=0`,
  `story_cells>0`, `warnings=0`, a pack written with more than 0 textures, `world_index` present.

**Checks:** `tools/check --e2e` green; the real build report passes.
**Play-test:** open `saves/Kanto` in `runClient`. Pallet Town looks right, the houses and lab are
underground and fully enclosed, and the textures load.
**Commit.**

### M3: Walking and warps

**Tasks:**
- [L] Step driver (press/release, facing first, landing release, re-press after a reset, queue of 2),
  blocked detection, `player.pos` kinds (walk, bike, ledge, push, sync), edge-push handling, warps
  (`player.warped`/`map.entered` `via`, departure kinds), connection crossings.
- [C] `MovementSync` (crossings, edge-push intent, face, snap-back, drift correction), `InputLock`,
  `ForcedMover`, `WarpFade`; on join, teleport to the hidden-game position.
- [S] `Rules` (game rules, Peaceful, Adventure, damage veto, jump and sprint off, Gen 1 speeds),
  `WarpService`.
- [T] Lua tests for the step driver and classifier against a fake overworld; Java tests for the
  `MovementSync` model.
- [T] e2e: step up, then the house door warp (`warp` start/arrive, `map.entered`), then the stairs,
  then a blocked step into a wall (`player.blocked`).

**Checks:** green.
**Play-test:** walk Pallet Town; enter and leave houses; use the stairs; a ledge on the edge of the
filtered area if present; Gen1Recomp's window follows.
**Commit.**

### M4: Dialogue, menus and NPC mirroring

**Tasks:**
- [L] UI observer (text with charmap markup and the ready gate, choice, menu, list, quantity,
  naming), answer actions, mirror capture with sub/unsub and fps cap, the NPC observer and emotes,
  `act.interact` with confirm and retry, the start menu.
- [C] UI kit (`TextBoxScreen`, `ChoiceOverlay`, `MenuScreen`, `ListScreen`, `QuantityScreen`,
  `NamingScreen`, `MirrorScreen`), keybinds, `PuppetManager` and `PuppetRenderer` with sprite frames
  from the generated pack, `UseEntityCallback`/`UseBlockCallback` returning FAIL, the in-world A key,
  and the start-menu key.
- [B] Sprite sheets in the resource pack (frames and palettes).
- [T] Lua UI-observer tests (fake states for every kind), Java text-paging and naming-rule tests,
  schema contract tests.
- [T] e2e: interact with an NPC in the house. Assert that `ui.open{text}` arrives (page count only),
  advance to `ui.close`, count `npc.set` NPCs, receive mirror frames (dimensions, more than 3 frames,
  size within bounds), and check a choice flow via a driver-set scenario.

**Checks:** green.
**Play-test:** talk to Mom, read signs, open the start menu, check NPCs walk and face correctly, and
see the mirror on a graphical screen.
**Commit.**

### M5: Opening, Oak's cutscene, starter and rival battle

**Tasks:**
- [L] Title menu mirroring; `intro.oak_speech.build` naming steps fed by `ui.name`; cutscene and
  forced-move reporting (Oak's escort); the gift path (`pokemon.before_give` nickname prompt,
  `party.give`); battle interception for scripted and rival battles; applying `battle.result`
  (including the Oak's-lab rival loss path).
- [S] `BattleService` (proxy NPC, party, IVs, `pvn`, end classification, cleanup, owner-hiding
  mixin); `PartyService` give and sync; starter lock and Cobblemon settings (Appendix C).
- [C] Relays for `battle.start`/`result`, `party.*`; `activity` updates.
- [T] GameTests: proxy spawn, party building with exact moves (number mapping), battle start succeeds
  with a mock player, `stop()` maps to aborted. Java name and id mapping tests (dex and move number;
  special names).
- [T] e2e: a driver sets up the lab scenario and triggers the rival battle start. Assert that
  `battle.start` validates (team size and levels in range, counts only), send a `battle.result` win,
  and assert the Gen 1 progress flag count went up.

**Checks:** green.
**Play-test:** new game from Minecraft (names typed in Minecraft); Oak stops you and leads you to the
lab; choose a starter; it appears in Cobblemon; the rival battle runs in Cobblemon and the story
continues.
**Commit.**

### M6: Route 1, habitat pools, catching, party/Pokédex/bag sync

**Tasks:**
- [L] Encounter suppression (`encounter.roll` returns nil; Safari passes through; surf and fish go to
  `battle.start`); party, Pokédex and bag diffs and application; item use → `party.item`.
- [B] Habitat pools for every encounter map (the datapack) and habitat block placement. Maps filter
  now includes `ROUTE_1` and `VIRIDIAN_CITY`.
- [S] `EncounterService` (surf, fish, static), `DexService`, `BagService` (Appendix B item table),
  full `PartyService` sync (debounced, `DATA_SYNCHRONIZED`), and the evolution filter.
- [T] GameTests: habitat block configured (pool attached, range, influence present); a static
  `PokemonEntity` spawns with `no_ai` and starts `pve`; inventory sync from `bag.sync`.
- [T] e2e: 200 grass steps on Route 1 produce no native battles; `dex.sync` shows up in
  `save.pokedex` counts; `party.sync` rebuilds the shadow party (the count matches).
- [T] Real build: `build_world.py --maps route1`. Report: `habitat_blocks>0`, `pools>0`.

**Checks:** green.
**Play-test:** wild Pokémon appear on Route 1; catch one; Gen 1's party menu (mirror) shows it; the
Pokédex count rises; Poké Ball counts match.
**Commit.**

### M7: Viridian City (Pokémon Center, Mart, PC, money, trainers, saving)

**Tasks:**
- [L] Nurse heal → `party.heal` plus both saves (spike on `mod.game:writeSave()`; fallback: prompt the
  player to save); Mart flows through the generic menus; Bill's PC → `pc.open`; trainer sight → walk-up
  → `battle.start`; `save.begin`/`done`; the blackout path.
- [S] `PcService`, `SaveService`, the full `BattleService` result mapping (blackout teleport and
  heal), `SessionService` basics.
- [B] Maps filter: `VIRIDIAN_CITY` interiors and `ROUTE_2` (south).
- [T] e2e: heal emits `party.heal` and `save.begin`; a Mart purchase flow changes money and bag
  (counts only); a trainer engagement emits a valid `battle.start`; a lose result lands at the
  Pokémon Center.

**Checks:** green.
**Play-test:** heal, buy and sell, use the PC, beat a trainer, black out once, save in both games and
reload.
**Commit.**

### M8: The rest of Kanto (single player)

Ordered so that the core parts come first.

**Tasks:**
1. [B] Full build: all 36 outdoor maps and 186 interiors (packing, caves, dark maps, void fill, the
   tunnel rule), all habitat maps, all story cells. A full real build with report gates (overlaps 0,
   every map placed, build time recorded).
2. [L] Swap-site audit: find every runtime block change in the engine v0.3.49 source (grep `setBlock`,
   `replaceBlock` and stamping in `src/` and `data/scripts/`) and list each in `kb_swapsites.lua`.
   Unknown changes at runtime are logged as errors.
3. [L]/[C] Forced movement kinds: spinners, currents, slope (P19), holes and pads, boulder pushes,
   with animations.
4. [L]/[C]/[S] Field moves from Minecraft:
   - right-click a cut tree → `act.field cut`;
   - walking into a boulder with Strength on pushes it;
   - right-click water → surf;
   - Fly through the mirror fly list or a native list adapter;
   - Dig, Teleport and Escape Rope.
5. [C]/[S] Bike and surf feel (speeds, surf sprite, story-cell water); Flash Night Vision.
6. [L]/[S] Static encounters (Snorlax after the Poké Flute, the birds, Mewtwo, Voltorb/Electrode,
   Marowak with the Silph Scope).
7. [L]/[C] Native special battles through the mirror (Safari, old man, ghost) and Safari catch
   crossover.
8. [L]/[S] Gifts and in-game trades, Name Rater, stones, TMs/HMs, Rare Candy, Game Corner (mirror),
   prize counter adapter.
9. [L]/[C] Gyms, Elite Four and Champion; Hall of Fame and credits (mirror).
10. [L]/[S] Daycare (P15), the per-player Flash/lit state, polish (P22 battle music).
11. [T] e2e scenarios set up by drivers: cut a tree (state set by the driver), surf, a spinner maze
    tile, a static encounter start, a gift, an in-game trade, an elevator warp. Shape and count
    assertions only.

**Checks:** green; the full real build report passes.
**Play-test:** the long checklist in PLAYTEST.md (badge by badge).
**Commit.**

### M9: Multiplayer

**Tasks:**
- [C]/[S] Two players: per-player story state and story-cell collision (GameTests with 2 mock players
  and different states); per-player puppets, warps and trainers; owner-hidden battle proxies;
  `activity` attachment speech bubbles; blockade fading.
- [C] Texture flow for joining clients (slot-map hash check → export your own art → regenerate the
  pack).
- [L]/[S] Online save pairing (`session.bind` → per-world slot; fallback: `--slot` from the launcher);
  host version exclusives (the build runs on the host).
- [S] Player trades and battles → `party.sync`/`dex.sync` for both, including trade evolutions
  (Cobblemon's prompt).
- [T] GameTests for relay authority (a player can't change another's state); `fake_minecraft`
  double-client test against `fake_gen1`.

**Checks:** green.
**Play-test:** two dev clients (`runClient --username Player2`) with two Gen1Recomp instances on
different ports (`launch.ps1` handles it). Check per-player item balls and trees, NPCs and trainers,
bubbles, a trade and a battle.
**Commit.**

### M10: Reliability, performance and launch

**Tasks:**
- [L]/[C]/[S] Crash recovery: pause overlay, reconnect, `snapshot` reconcile (hidden-game position
  and story, Cobblemon Pokémon), the save-pair mismatch warning.
- [L]/[C]/[S] Version handshake hardening and mismatch screens.
- [L]/[C]/[S] Performance pass:
  - gen1-bridge per-step budget under 1 ms on average;
  - mirror at most 30 fps;
  - puppet culling;
  - builder throughput recorded.
- [T] `tools/launch.ps1` (dev and player modes).
- [T] Installer: a written design (a portable Prism bundle as in SkyCraft) and a skeleton script only.
  Don't download or bundle third-party launchers without the user's OK.
- [D] Final docs: README, CLAUDE.md, PROTOCOL.md complete, PLAYTEST.md complete, PROGRESS.md final.

**Checks:** green.
**Commit "M10: reliability and launch".**

---

## 7. Parallel execution guide

- **One orchestrator owns** `protocol/`, `docs/PROTOCOL.md`, `docs/PROGRESS.md` and the commits. It
  merges and runs `tools/check` at each milestone gate.
- **Tracks within a milestone run in parallel** (sub-agents), each with exclusive file ownership:

  | Track | Owns |
  |---|---|
  | [L] | `gen1-bridge/**`, `tools/lua/**` |
  | [C] | `fabric-bridge/src/client/**` |
  | [S] | `fabric-bridge/src/main/**` except `builder/` |
  | [B] | `fabric-bridge/src/main/java/dev/kantobridge/builder/**`, `datapack-templates/**` |
  | [T] | `tools/**` except `tools/lua/` |
  | [GameTest] | `fabric-bridge/src/gametest/**`, written by the track whose code is tested |
- **Shared Java model classes** (`proto/`, `world/`, `story/`) belong to [S]. Other tracks request
  changes through the orchestrator.
- **Order inside a milestone:**
  1. protocol and schema changes;
  2. the tracks in parallel;
  3. integration;
  4. the e2e run;
  5. docs;
  6. commit.

---

## 8. Risks, spikes and pre-approved fallbacks

| Risk | Detection | Fallback (pre-approved) |
|---|---|---|
| `mod.datasets:open` fails on the fused portable build | M2 export test | Deep-copy the live `Data` right after `mods.loaded` (before any map loads) |
| `render.output` capture misbehaves or is slow | M4 mirror test, fps and size | Option B in `refs/_research/P2_gen1_ui.md` §1.7 (compose observer at UI canvas scale). Failing that, native adapters only, and the user looks at the Gen1Recomp window for graphical screens |
| `--console` doesn't work on the fused exe | Pre-flight step 2 | Rely on piped stdout in automation and on `log` messages shown in Minecraft (debug overlay) |
| Step driver desyncs (missed or duplicate steps) | M3 e2e, drift counters | Lower the queue to 1; resync with `player.pos{kind:sync}` and a client snap; last resort `mod.world:warpTo` (resets NPCs, so log it) |
| Engine internals behave differently from the research notes | Unit and e2e failures | Re-read the cited source in `refs/gen1recomp` (at the pinned tag: v0.3.85 since 2026-10-13) and adjust inside the engine adapter (`lib/engine/gen1.lua`) only |
| Habitat blocks don't attach or spawn | M6 GameTest | The activated style (`TICK`, chance 0.02, `maxSpawns` 4). Failing that, our own spawner: spawn from the pool near players on route grass cells through the Cobblemon API |
| Proxy NPC battle problems | M5 GameTest and play-test | Our own `NPCBattleActor` subclass through `BattleRegistry.startBattle`, with our own owner-only entity |
| Story-cell collision rubber-banding | GameTest and play-test | Make the blockade client-only, with a server check that teleports back to the last valid cell |
| Resource-pack injection fails | S3, M2 | Write to `<gameDir>/resourcepacks/kantobridge_generated` and enable it via `Options` + `reloadResourcePacks` |
| Full build too slow (over 30 min) | M8 report | Cache unchanged chunks by export hash; build the outdoor maps first and the interiors lazily on first warp |
| Interior packing can't fit in depth | M8 build | Spill sideways on the same layer, under the void-fill area |
| `mod.game:writeSave()` unsupported | M7 spike | At the Center, show "Save your game?" and drive the start-menu SAVE path |
| Session slot switching at runtime impossible | M9 | `launch.ps1` starts Gen1Recomp with `--slot` from a per-world pairing file |
| Cobblemon update or Gen1Recomp pin change | — | Not allowed during the run (pins) |

---

## 9. Definition of done

**Automated:**
- M0–M10 complete, with `tools/check --e2e` green.
- A full real build from the test instance's export, with a clean report.

**Docs:**
- PROTOCOL.md matches the schemas and the code.
- PLAYTEST.md gives the user a complete, ordered checklist.
- PROGRESS.md lists every deviation and open issue.

**Data safety:** no ROM-derived file in git, and no game content in any log or test output.

**Ready to play:** the user can run `tools/launch.ps1`, open the "Kanto" world and play from the
opening to the Hall of Fame. Multiplayer works with two instances.

---

## Appendix A: Research notes (`refs/_research/`)

| Note | Use it for |
|---|---|
| `A_gen1recomp_modding.md` | Manifest, loader, sandbox, network permission, logging, portable mode |
| `B_gen1recomp_engine.md` | Save tables, maps, tiles, text, choices, naming, scripted movement, encounters, trainers, warps, saving |
| `C_community_mods.md` | gen1coop and Gen1Online socket patterns; overworld-spawn-mod encounter suppression; SkyCraft installer model |
| `D_voxel_pokered.md` | Dramaless Shape height rules (re-implement, don't copy); pokered locations |
| `E_cobblemon_apis.md` | Cobblemon APIs: party, PC, battles, spawning, habitats, Pokédex, events, config |
| `F_cobblemon_npc_dialogue.md` | `NPCEntity`, dialogue (not used), `pvn`, visibility |
| `G_easynpc_kantonpcs.md` | Skins, dialogue screen layout and speech bubbles as references; what KantoNPCs built |
| `P1_gen1_movement.md` | **Step recipes, input gating, NPC fields, interaction, warps, forced movement, field moves, bike, background running** |
| `P2_gen1_ui.md` | **Frame capture, state classification, TextBox, ChoiceBox, menus, naming, opening, input** |
| `P3_gen1_data_export.md` | **Dataset access, map, tileset and image schemas, palettes, layout, interiors, encounters, trainers, names, paging** |
| `P4_mc_runtime.md` | **Puppets, click interception, collision, input lock, speeds, warps, rules, networking, screens, bubbles, mixins** |
| `P5_mc_content_testing.md` | **Tile blocks, atlas, pack loading, world preset, depth, biomes, mass placement, GameTest, JUnit** |
| `P6_cobblemon_integration.md` | **Battle proxy, static encounters, party, PC, Pokédex, items, habitats, config, names, rules** |
| `P7_testing_tooling.md` | **Drivers, modkit, lupa, JSON codec, venv, hot reload, logs** |

## Appendix B: Name and id mapping

- **Species:** by national dex number, `PokemonSpecies.getByPokedexNumber(n, "cobblemon")`. gen1-bridge
  sends `dex`, never the internal species index.
- **Moves:** Gen 1 move id 1–165 = Showdown num, `Moves.getByNumericalId(n)`. gen1-bridge sends numbers.
- **Display-only special names:** Nidoran♀/♂ (`nidoranf`/`nidoranm`), Farfetch'd (`farfetchd`),
  Mr. Mime (`mrmime`), Vise Grip (`visegrip`), High Jump Kick (`highjumpkick`), Psychic (`PSYCHIC_M`).
- **Battle items (Gen 1 → Cobblemon):**
  - Balls: `POKE_BALL`→`poke_ball`, `GREAT_BALL`→`great_ball`, `ULTRA_BALL`→`ultra_ball`,
    `MASTER_BALL`→`master_ball`, `SAFARI_BALL`→`safari_ball`.
  - Medicine: `POTION`/`SUPER_POTION`/`HYPER_POTION`/`MAX_POTION`/`FULL_RESTORE`/`REVIVE`/`MAX_REVIVE`→
    the same lowercase names; `ANTIDOTE`, `BURN_HEAL`, `ICE_HEAL`, `AWAKENING`,
    `PARLYZ_HEAL`→`paralyze_heal`, `FULL_HEAL`, `ETHER`, `MAX_ETHER`, `ELIXER`→`elixir`,
    `MAX_ELIXER`→`max_elixir`.
  - X items:
    - `X_ATTACK`→`x_attack`, `X_ACCURACY`→`x_accuracy`, `X_SPEED`→`x_speed`;
    - `X_DEFEND`→`x_defense`;
    - `X_SPECIAL`→`x_sp_atk`, which only boosts Special Attack;
    - `DIRE_HIT`→`dire_hit`, `GUARD_SPEC`→`guard_spec`.
  - Verify every id against `refs/cobblemon` in M6; anything unmapped stays Gen 1-only.
- **Evolution items:** `FIRE_STONE`, `WATER_STONE`, `THUNDER_STONE`, `LEAF_STONE` and `MOON_STONE` →
  `CobblemonItems.*_STONE`.

## Appendix C: Cobblemon settings

Apply in code at `SERVER_STARTING`, with shipped `config/cobblemon/main.json` (`lastSavedVersion` 1.8.1)
as a fallback.

| Setting | Value |
|---|---|
| `healPercent` / `faintAwakenHealthPercent` | 0 / 0 |
| `experienceMultiplier` | 1.0 |
| `awardExperienceOnBattleLoss` | true |
| `allowExperienceFromPvP` | false |
| `preventCompletePartyDeposit` | true |
| `playerDamagePokemon` | false |
| `shinyRate` | very large (no Gen 1 shinies) |
| `minimum`/`maximumSpawningZoneDistanceFromPlayer` | 8 / 32 |
| `savePokemonToWorld` | false |
| `defaultFleeDistance` | 12 |
| `passiveStatuses` | long ranges (Gen 1 statuses persist) |
| Gamerule `doPokemonLoot` | false |
| Gamerule `battleInvulnerability` | true |
| Gamerule `doShinyStarters` | false |
| Gamerule `doPokemonSpawning` | true (natural habitats need it) |
| Starter prompt | Locked per player on `DATA_SYNCHRONIZED`, through `setStarterLocked(true)` + `sendToPlayer` |

## Appendix D: Minecraft game rules

| Rule | Value |
|---|---|
| `doDaylightCycle`, `doWeatherCycle`, `doMobSpawning`, `doInsomnia`, `doPatrolSpawning`, `doTraderSpawning`, `doWardenSpawning` | false |
| `disableRaids` | true |
| `fallDamage`, `drowningDamage`, `fireDamage`, `freezeDamage` | false |
| `keepInventory`, `doImmediateRespawn` | true |
| `spawnRadius` | 0 |
| `mobGriefing`, `doFireTick` | false |
| `randomTickSpeed` | 0 |
| `doVinesSpread`, `doTileDrops`, `doEntityDrops`, `doMobLoot`, `projectilesCanBreakBlocks` | false |
| `announceAdvancements`, `showDeathMessages`, `commandBlockOutput` | false |

Also: Peaceful difficulty (locked), default game type Adventure, clear weather, fixed time.

## Appendix E: Repository layout at completion

```
gen1-bridge/        main.lua, manifest.json, mod.card, lib/, observe/, act/, export/
fabric-bridge/      src/main (common + builder), src/client, src/gametest, src/test
protocol/           envelope.schema.json, messages/*.schema.json, catalog.json, examples/
tools/              kb_protocol/, fake_gen1/, fake_minecraft/, lua/, e2e/, tests/, check.*, launch.ps1
datapack-templates/ habitat_pool.template.json, pack.mcmeta templates
docs/               DESIGN, PLAN, PROGRESS, PLAYTEST, PROTOCOL, REFERENCES, VERSIONS
local/              (ignored) gen1recomp/, gen1recomp-e2e/, minecraft/ (run dir: saves, kantobridge/export|resourcepack), reports/
refs/               (ignored) reference clones, _research/
```
