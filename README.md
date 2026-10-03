# Kanto Bridge

Play Pokémon Red/Blue's story in Minecraft. The real game runs hidden in
[Gen1Recomp](https://github.com/bryanthaboi/gen1recomp) and acts as the brain: story, scripts, map,
NPCs, bag, money and badges. Minecraft with [Cobblemon](https://cobblemon.com) is the body: it
draws everything and owns all Pokémon, wild spawns and battles. A small bridge joins the two over a
local TCP socket: a Gen1Recomp mod (`gen1-bridge`) and a Fabric mod (`fabric-bridge`).

Kanto is built in Minecraft from each player's **own** ROM, on their own machine. Nothing from the
ROM is ever distributed. This is a personal project.

**Status:** setup only. Both mods are skeletons that log a line when loaded.

- [`docs/DESIGN.md`](docs/DESIGN.md): the full design
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md): the bridge protocol (skeleton)
- [`docs/VERSIONS.md`](docs/VERSIONS.md): pinned versions
- [`docs/REFERENCES.md`](docs/REFERENCES.md): reference projects, their licences, and how Gen1Recomp and Cobblemon work inside
- [`CLAUDE.md`](CLAUDE.md): working rules for this repo

## Setting up a new machine

1. **Install the tools:**
   - Git;
   - a **JDK 21**, e.g. `winget install EclipseAdoptium.Temurin.21.JDK`;
   - [LÖVE 11.5](https://love2d.org), if you'll run Gen1Recomp from source.
2. **Clone this repository.**
3. **Build the Fabric mod.** Gradle must run on Java 21:

   ```sh
   cd fabric-bridge
   JAVA_HOME="<path to JDK 21>" ./gradlew build   # PowerShell: $env:JAVA_HOME='<path>'; .\gradlew.bat build
   ```

   `./gradlew runClient` starts a development Minecraft with Fabric API and Cobblemon.
4. **Set up Gen1Recomp** in `local/` (git-ignored), from the official GitHub only. Never use
   gen1recomp[.]com, which is an impersonation site.
   1. Put the pinned Gen1Recomp in `local/gen1recomp/engine/`.
   2. Create an empty `local/gen1recomp/portable.txt`, so Gen1Recomp keeps its saves, settings and
      ROM import in `local/gen1recomp/`.
   3. Link the mod, in `cmd`: `mklink /J local\gen1recomp\mods\gen1-bridge gen1-bridge`.
   4. Start it with `lovec local/gen1recomp/engine` and import **your own** ROM through
      Gen1Recomp's launcher.

   Don't use Gen1Recomp's `Play-Windows.bat` or `scripts/setup.ps1`; they write ROM data into the
   source tree.
5. **Optional, for development:** clone the reference projects into `refs/` (git-ignored), as
   listed in [`docs/REFERENCES.md`](docs/REFERENCES.md).

## Built on

- Gen1Recomp's licence asks works based on it to show this credit: *Based on the Pokemon Gen 1
  Recompilation Project by BOIS CLUB GAMES, LLC (https://github.com/bryanthaboi/gen1recomp)*.
- [Cobblemon](https://gitlab.com/cable-mc/cobblemon) (MPL-2.0).
- [Fabric](https://fabricmc.net). The `fabric-bridge` project was generated from Fabric's template
  (CC0).

Pokémon and all related names are the property of Nintendo, Creatures Inc. and GAME FREAK inc. This
project is not affiliated with them.
