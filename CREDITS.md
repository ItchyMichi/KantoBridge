# Credits

Kanto Bridge is Copyright (C) 2026 ItchyMichi and contributors, licensed under the GNU General Public License,
version 3 only (GPL-3.0-only): see [`LICENSE`](LICENSE).

## Required credit: Gen1Recomp

**Based on the Pokemon Gen 1 Recompilation Project by BOIS CLUB GAMES, LLC
(https://github.com/bryanthaboi/gen1recomp)**

Gen1Recomp is licensed under GPL-3.0 with additional terms (its `LICENSE.MD`). Its attribution term asks every work
based on it to show the credit above in its README, in its in-app credits, and in any launcher or front end used to
start the game. Kanto Bridge's `gen1-bridge` runs inside Gen1Recomp, and `tools/launch.ps1` starts it, so the credit
appears in the README, in this file, in both mods' descriptions, and in `launch.ps1` and the installer skeleton when
they run. **Forks must keep it.** Gen1Recomp's launcher is proprietary; nothing of it is used or included here.

## Built on (needed at runtime, not included in this repository)

- **Gen1Recomp** v0.3.49 (GPL-3.0 with additional terms): the hidden game, and the mod platform `gen1-bridge` runs on.
  Get it only from https://github.com/bryanthaboi/gen1recomp.
- **Minecraft: Java Edition** 1.21.1 by Mojang Studios. NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR
  ASSOCIATED WITH MOJANG OR MICROSOFT.
- **Fabric Loader** and **Fabric API** (Apache-2.0), https://fabricmc.net. `fabric-bridge` was generated from Fabric's
  example mod template (CC0), placeholder icon included.
- **Cobblemon** 1.8.1 (MPL-2.0), https://gitlab.com/cable-mc/cobblemon: every Pokémon, the party and PC, battles and
  spawning.
- **Parchment** mappings (CC0) and Mojang's official mappings, used to build; neither is redistributed.
- **Gradle** and its wrapper (Apache-2.0).
- The Python packages in `tools/requirements.txt`, each under its own licence, for the tools and tests.

## Studied, not copied

- **Dramaless Shape** by Stahltier (MIT for its 2.0 line), https://github.com/artyrambles/DRAMALESS_SHAPE: its shape
  profile's format and its shape rules were studied and re-implemented in this project's own code. Its hand-made table
  (`data/voxel_heights.lua`) is read at runtime from the player's own installed copy of the mod and is never
  redistributed.
- **gen1coop**, **Gen1Online**, **SkyCraft** and **overworld-spawn-mod** (MIT), **Easy NPC** (MIT, code only),
  **Kanto in First Person** (no licence: ideas only), the Gen1Recomp mod index, and **pokered** (vocabulary only) were
  read as references. No code, art, sound or data from them is included. [`docs/REFERENCES.md`](docs/REFERENCES.md)
  records what each was used for and its licence.

## How it was made

Vibe coded: designed and directed by ItchyMichi ([`docs/DESIGN.md`](docs/DESIGN.md)), and written almost entirely by
Claude (Anthropic) in Claude Code. Its commits carry a `Co-Authored-By: Claude` trailer.

## Trademarks and game content

Pokémon and all related names are the property of Nintendo, Creatures Inc. and GAME FREAK inc. Minecraft is a
trademark of Mojang Synergies AB. Kanto Bridge is not affiliated with or endorsed by Nintendo, Creatures Inc., GAME
FREAK inc., The Pokémon Company, Mojang, Microsoft, BOIS CLUB GAMES, LLC or the Cobblemon team.

This repository contains no ROM and nothing taken from one: no game art, music, text or data. Each player builds their
world on their own machine from their own legally obtained copy of the game.
