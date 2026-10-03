# datapack-templates/

Templates for the datapack the world builder generates on each player's machine:

- **Habitat pools**: Cobblemon habitat pool templates, one pool per route, single phase.
- **Biomes**: the custom "Kanto Interior" biome (no fog, no cave sounds, indoor music) and the cave biome.
- **Settings**: world settings such as game rules, and switching off hunger, damage, weather and vanilla mob spawning.

**Never ROM data.** These files are structure only. Anything that comes from the game is filled in
locally by the world builder from the player's own ROM, through Gen1Recomp's import. That includes
which species appear on which route, their levels, and version exclusives. The generated datapack
goes into `local/`, which is git-ignored, and is never committed or distributed.

Empty for now.
