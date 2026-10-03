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
- **Dialogue** uses our own screen, unless Cobblemon's dialogue screen can be driven with live text
  from code.
  - Finding: it can, but only server-side (REFERENCES.md, Cobblemon question b). ROM-derived text is
    now allowed through the server, so Cobblemon's screen is the default.
  - Its gaps: advancing and choosing are mouse-only, the text input is capped at 30 characters, and
    the box is a fixed size.
  - The user confirms the choice when planning.
- **Bridge topology** (details in PROTOCOL.md):
  - `gen1-bridge` listens on `127.0.0.1:27151`, and `fabric-bridge`'s **client** connects.
  - The Minecraft server never talks to Gen1Recomp. It receives each player's story data from that
    player's own client over Fabric custom payloads.
- **Trainer battles** (the recommended approach, to be confirmed when planning): Cobblemon runs them
  on the server, against a temporary `NPCEntity` built from the team the hidden game hands over.
  The opponent NPC is shown only to the fighting player.
  - **Decided:** other players may see the opponent's sent-out Pokémon; no extra hiding.
- **`gen1-bridge` may use Gen1Recomp engine internals** (`engine_internals`) wherever they give the
  most complete result. Re-check every internal it touches whenever the Gen1Recomp pin changes.
- **ROM-derived data** (dialogue text, trainer teams) **may pass through the Minecraft host's
  server.** Art never does: textures come from each player's own local resource pack.
- **No project licence.** This is a personal test project.

## Ownership

| Owner | Owns |
|---|---|
| **Gen1Recomp** | Story/event flags, map state, NPCs, dialogue, bag, money, badges, the shadow party, the Pokédex |
| **Cobblemon** | All Pokémon data (party, PC, moves, levels, evolutions, natures, abilities), wild spawns, battles |
| **Minecraft** | The player's exact position, camera and visuals |

## Layout

| Path | Contents |
|---|---|
| `gen1-bridge/` | Gen1Recomp mod (Lua, mod API 2, `network` + `engine_internals`) |
| `fabric-bridge/` | Fabric mod `kantobridge` (Java, package `dev.kantobridge`) |
| `protocol/` | Shared JSON schemas |
| `datapack-templates/` | Habitat pool, biome and settings templates (never ROM data) |
| `tools/` | Test stand-ins `fake_gen1`, `fake_minecraft` |
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

- **The install:** Gen1Recomp **v0.3.49** (official Windows release zip, SHA-256 checked) is in
  `local/gen1recomp/`.
- **Portable mode:** an empty `portable.txt` beside `gen1recomp.exe` makes it keep all of its data
  in that folder: saves, `options.lua`, ROM cache, `roms/`, `mods/`.
- **The mod link:** `local/gen1recomp/mods/gen1-bridge` is a junction to `gen1-bridge/`, so edits
  apply on the next boot. Remove it only with `rmdir` in `cmd`, never `rm -rf` through it.
- **Running it:** `local\gen1recomp\gen1recomp.exe`.
  - `--game=red` skips the launcher.
  - `--console` should show a log console with `mod.log` output (LÖVE's standard option; not yet
    tried on this build).
  - `POKEPORT_DEV=1` enables the developer console (backtick).
  - The user does the first-run ROM import.
- **Keep its window visible behind Minecraft. Never minimise it:** a minimised window runs at
  about a third of normal speed (from reading the code, not measured). The user glances at the
  window to check it's working.
- **Updating Gen1Recomp:** replace the release files (exe, DLLs, `license.txt`) and leave the data
  folders alone. Update VERSIONS.md.

## Testing

- **Prefer testing against the stand-ins in `tools/`:** `fake_gen1` (listens like gen1-bridge) in
  place of Gen1Recomp + gen1-bridge, and `fake_minecraft` (connects like the fabric-bridge client)
  in place of Minecraft + fabric-bridge. Neither may contain ROM data; use made-up maps, text and
  flags.
- **Full play-testing in the Minecraft window is done by the user.** Don't claim something works
  in-game unless the user has confirmed it.
