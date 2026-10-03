# Kanto Bridge: working instructions

Pokémon Red/Blue's story runs in a **hidden Gen1Recomp**. It is displayed and played in **Minecraft
1.21.1 with Cobblemon**, joined by a two-part bridge talking over a local TCP socket.

## Sources of truth

- [`docs/DESIGN.md`](docs/DESIGN.md) is the design. It is written by the user; never modify it.
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md) is the message protocol. Schemas in `protocol/` must match it.
- [`docs/VERSIONS.md`](docs/VERSIONS.md) holds the pinned versions and where each came from.
- [`docs/REFERENCES.md`](docs/REFERENCES.md) covers what each reference repo in `refs/` is good for,
  its licence, and where things live in Gen1Recomp and Cobblemon.

## Hard rules

- **Never read, copy, move or commit a ROM or anything derived from one.** This includes
  Gen1Recomp's import cache, saves, `roms/`, and generated packs. ROM handling happens only through
  Gen1Recomp's own import, done by the user.
- **`local/` and `refs/` are git-ignored. Never commit them.** Don't open files in `local/`: they are
  ROM-derived.
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
- Don't change pinned versions without asking.

## Architecture decisions (already made)

- **NPCs are client-side puppets.** Each player's client draws them from that player's own hidden
  Gen1Recomp, so story objects can differ per player in multiplayer. They have no AI and no logic.
- **All NPC dialogue and story logic come from the hidden Gen1Recomp.** No third-party NPC or
  dialogue mods at runtime (Easy NPC, KantoNPCs…). They are references only.
- **Dialogue** uses our own screen, unless Cobblemon's dialogue screen can be driven with live text
  from code. See the REFERENCES.md Cobblemon answers: it can, but only server-side.

## Ownership

| Owner | Owns |
|---|---|
| **Gen1Recomp** | Story/event flags, map state, NPCs, dialogue, bag, money, badges, the shadow party, the Pokédex |
| **Cobblemon** | All Pokémon data (party, PC, moves, levels, evolutions, natures, abilities), wild spawns, battles |
| **Minecraft** | The player's exact position, camera and visuals |

## Layout

| Path | Contents |
|---|---|
| `gen1-bridge/` | Gen1Recomp mod (Lua, mod API 2, `network` permission) |
| `fabric-bridge/` | Fabric mod `kantobridge` (Java, package `dev.kantobridge`) |
| `protocol/` | Shared JSON schemas |
| `datapack-templates/` | Habitat pool, biome and settings templates (never ROM data) |
| `tools/` | Test stand-ins `fake_gen1`, `fake_minecraft` |
| `refs/` | Reference clones (ignored) |
| `local/` | The user's ROM, Gen1Recomp data and generated packs (ignored) |

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

**gen1-bridge** has no build step. It runs inside Gen1Recomp, and **Gen1Recomp's data lives in
`local/` via portable mode**:

- **The marker:** an empty `portable.txt` makes Gen1Recomp keep all of its data in the folder holding
  the marker (saves, `options.lua`, ROM cache, `mods/`, `roms/`). Gen1Recomp checks the folder
  *containing* the game folder first (a source run), then the game folder itself. For a release
  `.exe`, the marker goes beside the exe.
- **The intended layout:** a pinned Gen1Recomp in `local/gen1recomp/engine/` and `portable.txt` in
  `local/gen1recomp/`. Link the mod in rather than copying it:
  `mklink /J local\gen1recomp\mods\gen1-bridge gen1-bridge`.
- **Running it:** `lovec local/gen1recomp/engine --game=red`. `lovec` shows the console, where the
  mod's `mod.log` output appears. This needs LÖVE 11.5, which isn't installed here yet. The user
  does the ROM import.

## Testing

- **Prefer testing against the stand-ins in `tools/`:** `fake_gen1` in place of
  Gen1Recomp + gen1-bridge, and `fake_minecraft` in place of Minecraft + fabric-bridge. Neither may
  contain ROM data; use made-up maps, text and flags.
- **Full play-testing in the Minecraft window is done by the user.** Don't claim something works
  in-game unless the user has confirmed it.
