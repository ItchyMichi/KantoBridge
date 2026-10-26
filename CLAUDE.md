# Kanto Bridge: working instructions

Pokémon Red/Blue's story runs in a **hidden Gen1Recomp**. It is displayed and played in **Minecraft
1.21.1 with Cobblemon**, joined by a two-part bridge talking over a local TCP socket.

## Sources of truth

- [`docs/DESIGN.md`](docs/DESIGN.md) is the design. It is written by the user; never modify it.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) is the message protocol and transport. Schemas in
  `protocol/` must match it.
- [`docs/VERSIONS.md`](docs/VERSIONS.md) holds the pinned versions and where each came from.
- [`docs/REFERENCES.md`](docs/REFERENCES.md) covers what each reference repo in `refs/` is good for,
  its licence, and where things live in Gen1Recomp and Cobblemon.
- [`docs/PLAN.md`](docs/PLAN.md) is the implementation plan: milestones, designs and pre-approved
  defaults. [`docs/PROGRESS.md`](docs/PROGRESS.md) records where execution stands (milestones,
  spikes, deviations, open issues, blocked questions), and [`docs/PLAYTEST.md`](docs/PLAYTEST.md)
  is the user's manual checklist.
- [`docs/specs/VOXEL_BLOCKS_PLAN.md`](docs/specs/VOXEL_BLOCKS_PLAN.md) is the voxel plan (V1–V9: the blocks'
  runtime model, shapes, story art in the mesh, texture packs, structures and dressing, characters), executed and
  recorded in PROGRESS.md's "Voxel plan" sections. [`docs/PACKS.md`](docs/PACKS.md) is the texture-pack format
  (keys, the `kb_pack` manifest, `.vox` conventions, what may be shared); its schema is
  `protocol/packs/kb_pack.schema.json`.
- [`docs/specs/PROFILE_PLAN.md`](docs/specs/PROFILE_PLAN.md) is the profile plan (P1–P4): Dramaless Shape's
  hand-made table drives the shapes where it says something, our rules elsewhere; executed and recorded in
  PROGRESS.md's "Profile plan" sections.
- [`docs/specs/CAVES_AND_WOODS_PLAN.md`](docs/specs/CAVES_AND_WOODS_PLAN.md) is the caves and woods plan (CW1–CW5): Kanto
  in First Person's cave rock, forest canopy and vines, re-implemented from its rules (never installed, nothing
  copied); executed and recorded in PROGRESS.md's "Caves and woods" sections.
- [`docs/specs/MULTI_GAME_PLAN.md`](docs/specs/MULTI_GAME_PLAN.md) is the plan for several games in one world
  (MG0–MG8): a game catalogue, engine adapters per family inside the one bridge mod, one hidden process per game,
  regions as data in the flat overworld, connecting routes with gate houses, per-game story progress and one trainer
  economy (money and items projected into the followed game). Its §9 holds the user's decisions of 2026-10-13. The user's
  design statement of 2026-10-13 (one dimension, flat generation, interiors underground, forests open-air, caves in
  their own 3D area) governs it; the research report's vanilla-overworld track (M11–M13) is not the direction.
- [`docs/specs/ENGINE_UPGRADE_PLAN.md`](docs/specs/ENGINE_UPGRADE_PLAN.md) is the plan for moving the Gen1Recomp pin
  from v0.3.49 to v0.3.85 (UP0–UP6, gates G0–G6; written 2026-10-13): the audit of every cited engine line
  (`tools/pin_check.py`), the re-import every ROM needs, the Union Room (on by default upstream since v0.3.61; it
  rewrites Gen 1's Pokémon Centers at data load, so every KantoBridge install runs with it OFF), and the seams that
  changed (text boxes, the bag, battle music). In progress since 2026-10-13 with its defaults (the user's answer to
  U1–U6: v0.3.85, the user re-imports, the Union Room OFF, fresh folders, the v0.3.49 folders kept until the last
  gate, the JUnit literals updated); PROGRESS.md's "Engine upgrade" section records the gates.
- [`docs/specs/FOREIGN_POKEMON_PLAN.md`](docs/specs/FOREIGN_POKEMON_PLAN.md) is the foreign Pokémon plan (FP0–FP9,
  v2 2026-10-24): a visitor record in every hidden game for each Cobblemon Pokémon that game lacks; combat stays
  Cobblemon's; its §2 rules R1–R6 and §10 decisions Q1–Q24 govern (each a default the user may change). It is the only guidance on foreign Pokémon: the user's earlier upload
  FOREIGN_POKEMON_AND_ITEMS.md (2026-10-13, Gen 1 only) is superseded; don't take guidance from it.
- [`docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md`](docs/specs/FIELD_MECHANICS_AUDIT_PLAN.md) is the audit of every tile
  mechanic, in-visit script and field feature in all three games against Minecraft (FA0–FA8; rules A–F, decisions D1–D10;
  proposed 2026-10-24): inventory from the engine's own data, a live sweep on the pod, collision parity on the real index,
  then fixes per game. STORY_EVENTS_PLAN.md covers what entry scripts change; this covers what happens while moving.
- [`docs/specs/WATER_AND_BRIDGES_PLAN.md`](docs/specs/WATER_AND_BRIDGES_PLAN.md) is the water and bridges plan
  (WB0–WB8, written 2026-10-25 after FA9): the sea as the chain's datum, lagoons as ponds, waterfalls as cascades, seats
  on landings, and Emerald's bridges as two-floor cells (`decks` in the layout, the floor from the game's elevation); its §3
  decisions D1–D11 are defaults the user may change. BRIDGES_PROBLEM.md is its input.
- [`docs/specs/BIKE_PLAN.md`](docs/specs/BIKE_PLAN.md) is the bike plan (B0–B8, written 2026-10-26 from the audit's
  K3/K4): while the hidden player is on a bike the hidden game drives and Minecraft steers (`move.ride` holds, the
  engine's steps walked on a ridden bike entity other players see); Emerald's Mach and Acro mechanics come out of the
  engine, Blue and Crystal keep their one bike unchanged; its §3 decisions D1–D12 are defaults the user may change and
  §8 is the recipe for running it unattended.
- `refs/_research/` (git-ignored) holds the research notes, with verified `file:line` references,
  that the plan cites.

## Hard rules

- **Never read, copy, move or commit a ROM or anything derived from one.** This includes
  Gen1Recomp's import cache, saves, `roms/`, and generated packs. ROM handling happens only through
  Gen1Recomp's own import, done by the user.
- **`local/` and `refs/` are git-ignored. Never commit them.** Never open Gen1Recomp's data in
  `local/`: it is ROM-derived.
- **Never copy code from `refs/` without checking its licence first and recording it in
  REFERENCES.md.** Several projects licence code but exclude assets (Easy NPC, overworld-spawn-mod),
  and some must never be reused (pokered, KantoNPCs/CobbleKanto, Gen1Online's art, Gen1Recomp's
  launcher files).
- **Get Gen1Recomp only from <https://github.com/bryanthaboi/gen1recomp>.** `gen1recomp.com` is an
  impersonation site; never download anything from it.
- **Every KantoBridge install runs with Gen1Recomp's Union Room OFF** (Launcher Options → Union Room → OFF,
  then restart). It is on by default upstream and rewrites Gen 1's Pokémon Centers at data load, which the
  export never sees. gen1-bridge pins the option off and guards it: it logs `engine.union_room_on` once if a
  boot ran patched, refuses `export.index` with `error{code:"union_room_on"}`, and reports it in `hello.ok`
  (`union_room`) and `debug.status` (`unionRoom`); the client shows a notice; the e2e driver
  `tools/e2e/drivers/kb_union_off.lua` switches it off headlessly.
- Never run Gen1Recomp from `refs/`, and never use its `Play-Windows.bat` or `scripts/setup.ps1`.
  They scan for a `.gb` and write ROM data into the source tree.
- **If something is ambiguous or a step fails, stop and ask the user.** Don't improvise a workaround.
- Never use Yarn mappings. Use Mojang official + Parchment only.
- Don't change pinned versions without asking. Don't change system-wide settings (Java defaults,
  PATH) without asking.

## Architecture decisions

- **NPCs are client-side puppets.** Each player's client draws them from that player's own hidden
  Gen1Recomp, so story objects can differ per player in multiplayer. They have no AI and no logic.
- **All NPC dialogue and story logic come from the hidden Gen1Recomp.** No third-party NPC or
  dialogue mods at runtime (Easy NPC, KantoNPCs…). They are references only.
- **Dialogue, yes/no and naming use our own client-side screens**, with keyboard A/B/arrows and
  Gen 1 limits. Cobblemon's dialogue screen is not used.
- **Graphical Gen 1 screens** (party, summary, Pokédex pages, town map, slots, …) and native special
  battles (Safari, old man, ghost) are shown on a **mirror screen**: the hidden game's live frame.
- **Battles use modern Cobblemon rules** (`GEN_9_SINGLES`), with Gen 1 species, levels and moves.
- **Sound passes through Gen1Recomp.** Minecraft's music is silenced; Cobblemon provides cries.
- **Bridge topology** (details in PROTOCOL.md):
  - `gen1-bridge` listens on `127.0.0.1:27151`, and `fabric-bridge`'s **client** connects.
  - The Minecraft server never talks to Gen1Recomp. It receives each player's story data from that
    player's own client over Fabric custom payloads.
- **Trainer battles** (PLAN.md §4.4): Cobblemon runs them on the server, against a temporary
  battle-proxy `NPCEntity` built from the team the hidden game hands over. No player sees the proxy;
  the fighting player's client draws the trainer puppet.
  - Other players may see the opponent's sent-out Pokémon; no extra hiding.
- **Wild Pokémon:**
  - Grass and cave encounters come from Cobblemon habitat pools.
  - Surfing, fishing and static encounters are rolled or triggered by the hidden game and fought in
    Cobblemon.
  - Safari Zone battles run natively in the hidden game.
- **`gen1-bridge` may use Gen1Recomp engine internals** (`engine_internals`) wherever they give the
  most complete result, but only inside its engine adapters, `lib/engine/**` (one per engine family,
  MULTI_GAME_PLAN §4.3; the Gen 3 adapter keeps its parts in `lib/engine/game3/`, the Gen 2 adapter in `lib/engine/gen2/`, and the parts both share in `lib/engine/common/`); the core never touches them. Re-check every internal an adapter touches whenever
  the Gen1Recomp pin changes.
- **ROM-derived data** (dialogue text, trainer teams) **may pass through the Minecraft host's
  server.** Art never does: textures come from each player's own local resource pack.
- **The block approach** (the voxel plan): tile blocks keep their slots and their collision; one runtime model
  (Fabric's rendering API only, never Indigo) draws every carrier block from an immutable render snapshot with this
  player's own art. A pixel-free shape planner (`kb-shapes/<n>`, shared by the server and the clients) decides each
  block's shape key; the build writes `shape_index.json` and `shape_map.bin` beside the WorldIndex and relays them
  (`world.shapes`). Each client builds the geometry from its own export: Tier A folded columns and roofs, Tier B units
  (hulls, objects, tufts), Tier C building templates, interior dressing; anything that fails its self-check is drawn
  as columns, never a hole. Closed story art is part of the mesh, per player.
- **Texture packs** are ordinary resource packs with a `kb_pack` manifest: textures, shape rules and `.vox` models
  that change only their own client's look (never collision or anything the server decides) and never travel. The
  pack template is written only on the player's action; its generated files are ROM-derived and never shared.
- **Dramaless Shape's profile** (the profile plan, the user's decision of 2026-10-04): its hand-made table
  (`data/voxel_heights.lua`) is read at runtime from the mod installed in Gen1Recomp, kept **switched off** there.
  gen1-bridge exports it as text (`profile/dramaless`); Java reads it as data, never runs it, and the planner turns its
  pins into units and class boxes and its band tables into buildings and furniture (`kb-shapes/6..8`, P1–P3). Its code
  is never copied (every rule re-implemented from the research notes), the table never committed, its
  `figures`/`mounted` masks never read. Without it, our own rules apply.
- **Characters** draw through a style chosen per sprite from its own sheet: thick cards by default, carved and
  rigged figures as settings (`characters.style`), a pack's sprite model; each falls back down rigged → carved →
  thick → flat. Players keep their Minecraft skins.
- **Licence: GPL-3.0-only** (the user's decision of 2026-10-04; [`LICENSE`](LICENSE), [`CREDITS.md`](CREDITS.md)).
  Gen1Recomp's attribution term travels with it: the README, both mods' descriptions and any launcher that starts the
  game show its credit line. The repository is public: nothing ROM-derived, no secrets, no personal data in it.
- **Testing on the user's real game is allowed** through the separate test install
  `local/gen1recomp-e2e/` only, never the user's `local/gen1recomp/`. The conditions are in
  [`docs/PLAN.md` §0.3](docs/PLAN.md):
  - the agent never imports a ROM and never opens game data;
  - logs and test output contain only types, ids, counts and hashes;
  - generated exports, packs and worlds stay in `local/`.

## Ownership

| Owner | Owns |
|---|---|
| **Gen1Recomp** | Story/event flags, map state, NPCs, dialogue, bag, money, badges, the shadow party, the Pokédex |
| **Cobblemon** | All Pokémon data (party, PC, moves, levels, evolutions, natures, abilities), wild spawns, battles |
| **Minecraft** | The player's exact position, camera and visuals |

## Layout

| Path | Contents |
|---|---|
| `gen1-bridge/` | Gen1Recomp mod (Lua, mod API 2, `network` + `engine_internals`). `lib/`, `observe/`, `act/` and `export/` hold the `kb_*` modules; only the engine adapters `lib/engine/**` touch engine internals (`gen1.lua`; since MG7 `game3.lua`, Emerald, with its parts in `lib/engine/game3/`, docs/specs/GEN3_BRIDGE_PLAN.md; since MG9 `gen2.lua`, Gold, Silver and Crystal (Crystal switched on), with its parts in `lib/engine/gen2/`, docs/specs/GEN2_BRIDGE_PLAN.md; the parts game3 and gen2 share in `lib/engine/common/`; `lib/kb_engine.lua` is the loader that picks one by Gen1Recomp's engine id, MULTI_GAME_PLAN §4.3, D1) |
| `fabric-bridge/` | Fabric mod `kantobridge` (Java, package `dev.kantobridge`): `src/main` (common, builder; `scene/`, `builder/shape/` with `geom/` and `dressing/`, `character/`), `src/client` (`model/` the runtime model, `shape/`, `packs/`, `puppet/style/`), `src/test` (JUnit), `src/gametest` (server GameTests) |
| `protocol/` | Message schemas (JSON Schema 2020-12), synthetic examples, and the derived `catalog.json` and `redaction-vectors.json` ([`protocol/README.md`](protocol/README.md)); `games.json` (the game catalogue, MULTI_GAME_PLAN §4.2: the one place that names the games; generated into `gen1-bridge/lib/kb_games.lua`); `world/` (the WorldIndex, slot map and shape index), `export/`, `packs/` (the texture-pack manifest) |
| `datapack-templates/` | Habitat pool, biome and settings templates (never ROM data) |
| `tools/` | `kb_protocol` (Python protocol helpers), the stand-ins `fake_gen1` and `fake_minecraft`, `lua/` (lupa test harness), `tests/`, `e2e/` (test-instance runner, drivers, `build_world.py`), `setup.*`, `check.*`, `launch.ps1` (starts Gen1Recomp, then Minecraft), `installer/` (skeleton only, see [`docs/INSTALLER.md`](docs/INSTALLER.md)) ([`tools/README.md`](tools/README.md)) |
| `refs/` | Reference clones (ignored) |
| `local/` | Ignored. Holds the user's Gen1Recomp install and its ROM-derived data, plus generated packs |

## Build and run

**fabric-bridge** needs JDK 21 for Gradle itself. This machine's default Java is 17, so set
`JAVA_HOME` per command.

```sh
# Git Bash
cd fabric-bridge
JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot" ./gradlew build
```

```powershell
# PowerShell
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'; .\gradlew.bat build
```

- Output: `fabric-bridge/build/libs/kantobridge-<version>.jar`.
- `./gradlew runClient` starts a dev client with Fabric API and Cobblemon.
- Loom is pinned at 1.17.21, because Loom 1.18+ requires Gradle on Java 25.

**gen1-bridge** has no build step. It runs inside the pinned Gen1Recomp, whose **data lives in
`local/` via portable mode**:

- **The install:** Gen1Recomp **v0.3.85** (official Windows release zip, SHA-256 checked; the pin
  since 2026-10-13) is in `local/gen1recomp/`, a fresh folder from the new zip (ENGINE_UPGRADE_PLAN
  UP5); the v0.3.49 folders stay beside it as `-0.3.49` until gate G6 passes.
- **Portable mode:** an empty `portable.txt` beside `gen1recomp.exe` makes it keep all of its data
  in that folder: saves, `options.lua`, ROM cache, `roms/`, `mods/`.
- **The mod link:** `local/gen1recomp/mods/gen1-bridge` is a junction to `gen1-bridge/`, so edits
  apply on the next boot. Remove it only with `rmdir` in `cmd`, never `rm -rf` through it.
- **Running it:** `local\gen1recomp\gen1recomp.exe`, or `.\tools\launch.ps1`, which starts it,
  waits for the bridge and then starts the dev client. Agents only dry-run `launch.ps1`
  (`-DryRun`): it would start the user's own instance.
  - **The user's imported game is Blue**, so use `--game=blue`; `--game=` skips the launcher.
  - `--console` shows a log console with `mod.log` output. In PowerShell, from inside the install
    folder, the command needs the `.\` prefix: `.\gen1recomp.exe --console --game=blue`.
  - `POKEPORT_DEV=1` enables the developer console (backtick).
  - The user does the first-run ROM import.
- **Keep its window visible behind Minecraft** (PLAN U6): the user glances at it to check it's
  working. **Minimising is safe, though:** M10 measured that a minimised window keeps full speed,
  because LÖVE still reports it as visible on Windows. That corrects the older "a third of the
  speed" note, which came from reading the code.
  - A window *hidden* with `SW_HIDE` does slow down. While Minecraft is connected, gen1-bridge keeps
    it at full speed (`gen1-bridge/README.md`).
  - While connected, gen1-bridge also paces a minimised window's drawing.
- **Updating Gen1Recomp:** replace the release files (exe, DLLs, `license.txt`) and leave the data
  folders alone. Update VERSIONS.md. `tools/pin_check.py` is the first step (it audits every cited
  engine line); when the release bumps the ROM cache generation (v0.3.85 did), the user re-imports
  the ROM in the launcher, never the agent.
- **The test install** `local/gen1recomp-e2e/` (or `local/gen1recomp-e2e-0.3.85/`: the e2e runner
  prefers the folder named for its pin, `tools/e2e/README.md`) has the same layout, its own
  `portable.txt`, and its own `mods/gen1-bridge` junction. Automated tests launch only this one, with
  a test driver script (see the plan).
- **The Minecraft dev run directory** is `local/minecraft/` (client and server; the user's EULA
  acceptance lives there). The plan keeps generated worlds and packs in `local/`.

**Tools** use a project-local venv, `tools/.venv` (`tools/setup.ps1` or `tools/setup.sh` creates it
from the pinned `tools/requirements.txt`).

## Testing

- **One entry point:** `.\tools\check.ps1` (PowerShell) or `tools/check.sh` (Git Bash) runs pytest
  (protocol, `kb_protocol`, gen1-bridge Lua under lupa) and then the Gradle build (JUnit and
  GameTests). `-E2E`/`--e2e` adds the end-to-end tests on `local/gen1recomp-e2e/`; `-NoGradle`/
  `--no-gradle` skips Gradle.
- **After editing a message schema,** regenerate the derived files from `tools/`:
  `.venv\Scripts\python.exe -m kb_protocol.catalog --write`. A test fails while they are stale.
- **Prefer testing against the stand-ins in `tools/`:** `fake_gen1` (listens like gen1-bridge) in
  place of Gen1Recomp + gen1-bridge, and `fake_minecraft` (connects like the fabric-bridge client)
  in place of Minecraft + fabric-bridge. Neither may contain ROM data; use made-up maps, text and
  flags.
- **Full play-testing in the Minecraft window is done by the user.** Don't claim something works
  in-game unless the user has confirmed it.
