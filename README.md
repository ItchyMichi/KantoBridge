# Kanto Bridge

Play Pokémon Red/Blue's story in Minecraft. The real game runs hidden in
[Gen1Recomp](https://github.com/bryanthaboi/gen1recomp) and acts as the brain: story, scripts, map,
NPCs, bag, money and badges. Minecraft with [Cobblemon](https://cobblemon.com) is the body: it
draws everything and owns all Pokémon, wild spawns and battles. A small bridge joins the two over a
local TCP socket: a Gen1Recomp mod (`gen1-bridge`) and a Fabric mod (`fabric-bridge`).

Kanto is built in Minecraft from each player's **own** ROM, on their own machine. Nothing from the
ROM is ever distributed. Kanto Bridge is free software under the GPL-3.0 ([`LICENSE`](LICENSE),
[`CREDITS.md`](CREDITS.md)). It's **vibe coded**: designed and directed by its author and written almost
entirely by Claude in Claude Code.

**To set it up and play, see [`INSTALL.md`](INSTALL.md).** What to build next is in
[`docs/ROADMAP.md`](docs/ROADMAP.md).

**Status:** all ten milestones of [`docs/PLAN.md`](docs/PLAN.md) are built and pass the automated
checks. That covers the bridge, the world builder, the single-player story from the opening to the
Hall of Fame, multiplayer, crash recovery and the launcher. The in-game play-test in
[`docs/PLAYTEST.md`](docs/PLAYTEST.md) is still to do. [`docs/PROGRESS.md`](docs/PROGRESS.md)
records every deviation, open issue and question.

- [`docs/DESIGN.md`](docs/DESIGN.md): the full design
- [`docs/PLAN.md`](docs/PLAN.md): the implementation plan, and [`docs/PLAYTEST.md`](docs/PLAYTEST.md)
  the manual checks for each milestone
- [`docs/PROTOCOL.md`](docs/PROTOCOL.md): the bridge protocol (v1; schemas in `protocol/`)
- [`docs/NEXT_STAGES.md`](docs/NEXT_STAGES.md): proposals for after M10 (the hidden-player speed-up,
  the generative voxel system, first-person dressing, several regions in one world)
- [`docs/VERSIONS.md`](docs/VERSIONS.md): pinned versions
- [`docs/REFERENCES.md`](docs/REFERENCES.md): reference projects, their licences, and how Gen1Recomp and Cobblemon work inside
- [`CLAUDE.md`](CLAUDE.md): working rules for this repo

## Setting up a new machine

1. **Install the tools:** Git, and a **JDK 21**, e.g. `winget install EclipseAdoptium.Temurin.21.JDK`.
   That MSI changes the machine's default Java, so check `JAVA_HOME` afterwards.
2. **Clone this repository.**
3. **Build the Fabric mod.** Gradle must run on Java 21:

   ```sh
   cd fabric-bridge
   JAVA_HOME="<path to JDK 21>" ./gradlew build   # PowerShell: $env:JAVA_HOME='<path>'; .\gradlew.bat build
   ```

   `./gradlew runClient` starts a development Minecraft with Fabric API and Cobblemon (run folder
   `local/minecraft/`). Automated checks never launch it; see [`docs/PLAYTEST.md`](docs/PLAYTEST.md).
4. **Set up Gen1Recomp** in `local/` (git-ignored), from the official GitHub only. Never use
   gen1recomp[.]com, which is an impersonation site.
   1. Download the pinned Windows release zip listed in [`docs/VERSIONS.md`](docs/VERSIONS.md).
      Check its SHA-256 against the release's `sha256sums.txt`.
   2. Unzip the files inside its `gen1recomp-win64/` folder into `local/gen1recomp/`.
   3. Create an empty `local/gen1recomp/portable.txt` beside `gen1recomp.exe`, so Gen1Recomp keeps
      its saves, settings and ROM import in that folder.
   4. Link the mod, in `cmd`: `mkdir local\gen1recomp\mods`, then
      `mklink /J local\gen1recomp\mods\gen1-bridge gen1-bridge`.
   5. Start `local\gen1recomp\gen1recomp.exe` and import **your own** ROM through its launcher.
      Keep its window open behind Minecraft. Minimising it is fine too: it keeps full speed.
5. **Optional, for development:** clone the reference projects into `refs/` (git-ignored), as
   listed in [`docs/REFERENCES.md`](docs/REFERENCES.md).
6. **Tests:** `.\tools\setup.ps1` creates the project-local Python venv (Python 3.12, wheels only),
   then `.\tools\check.ps1` runs every automated test. See [`tools/README.md`](tools/README.md).

## Playing (development setup)

1. **Build the world once.** Either use **Create Kanto world** on Minecraft's title screen, which
   builds it from your running Gen1Recomp, or run the headless build from `tools/`:
   `.venv\Scripts\python.exe e2e\build_world.py --maps all --world Kanto`. The headless build uses the
   test install's export.
2. **Play:** `.\tools\launch.ps1` starts Gen1Recomp, waits until the bridge listens, and starts the
   dev client. Open the `Kanto` world. `.\tools\launch.ps1 -DryRun` shows what it would do.
3. **Two players on one PC:** set up a second Gen1Recomp install (`local\gen1recomp-p2`, the same
   steps as above), then `.\tools\launch.ps1 -Players 2`. Both clients join `localhost`.
4. **If Gen1Recomp closes,** Minecraft pauses until it's back. `-Watch` restarts it for you; then pick
   CONTINUE.
5. **Keys** (rebindable under Options → Controls → Key Binds → Kanto Bridge): A = `Z` (and right-click on what you aim
   at), B = `X` inside our screens, START = `Enter`, SELECT = `Backspace`, **Bicycle = `C`** (on and off, as the bag's
   Bicycle). On a bike the hidden game rides it: the movement keys steer by the camera, and sneak or jump is B (the Acro
   Bike's wheelie, bunny hop and jumps in Emerald; the Cycling Road's brake in Blue); sneak never gets you off.
6. **STEP SPEED** (Gen1Recomp's F10 mod manager → gen1-bridge): the hidden player's own steps run at 2X by default, so a
   diagonal walk in Minecraft never outruns it (no snap-backs); the Gen1Recomp window shows it walking faster. 1X is the
   game's own pace.

A one-click installer for players is designed but not built: [`docs/INSTALLER.md`](docs/INSTALLER.md).

## Built on

- **Based on the Pokemon Gen 1 Recompilation Project by BOIS CLUB GAMES, LLC
  (https://github.com/bryanthaboi/gen1recomp).** Gen1Recomp's licence (GPL-3.0 with additional terms)
  asks every work based on it to show this credit; forks of Kanto Bridge must keep it.
- [Cobblemon](https://gitlab.com/cable-mc/cobblemon) (MPL-2.0).
- [Fabric](https://fabricmc.net). The `fabric-bridge` project was generated from Fabric's template
  (CC0).
- The shape rules re-implemented from [Dramaless Shape](https://github.com/artyrambles/DRAMALESS_SHAPE)
  (MIT), whose table is read from the player's own installed copy. Everything else:
  [`CREDITS.md`](CREDITS.md).

## Licence

Kanto Bridge is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, version 3 of the License
(GPL-3.0-only), together with Gen1Recomp's attribution term above. It is distributed in the hope that
it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE. See [`LICENSE`](LICENSE).

Pokémon and all related names are the property of Nintendo, Creatures Inc. and GAME FREAK inc.
Minecraft is a trademark of Mojang Synergies AB. Not an official Minecraft product; not approved by or
associated with Mojang or Microsoft. This project is not affiliated with any of them.
