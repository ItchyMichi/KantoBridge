# Kanto Bridge protocol

Status: **skeleton only.** No messages are defined yet. This file and [DESIGN.md](DESIGN.md) are
the source of truth; the JSON schemas in [`../protocol/`](../protocol/README.md) must match this file.

The protocol connects the two halves of the bridge:

- **gen1-bridge**: the Gen1Recomp mod (Lua), running in the player's hidden Gen1Recomp.
- **fabric-bridge**: the Fabric mod (Java), running in Minecraft with Cobblemon.

## Transport

A local TCP socket between the two halves on the player's own machine. The Gen1Recomp side needs
the sandbox's `network` permission (see [REFERENCES.md](REFERENCES.md)).

To decide:

- which side listens and which connects, and the port
- framing and encoding (JSON messages; delimiting)
- message envelope (type, sequence/ids, protocol version)
- error handling, timeouts, reconnects and backpressure

## Handshake

Version handshake between the bridge halves before anything else is exchanged.

## Position

Player tile position and facing, sent whenever the player crosses into a new tile.

## Interaction

"Face it and press A" for NPCs, signs and items, and field-move interactions (Cut, Strength, Surf, Flash).

## Dialogue

### Text

Text boxes from the hidden game.

### Choices

Yes/no and other choices, and the answer sent back.

### Naming

Naming screens, and the chosen name sent back.

## NPCs

NPC positions, facing and visibility, mirrored as client-side puppets.

## World changes

Cut trees, moved boulders, Snorlax waking, collected items, and per-player blockades.

## Cutscenes and forced movement

Control locks, scripted paths, spinner tiles, warp pads and currents.

## Battles

Trainer battle start (opponent team handed over to Cobblemon) and the result sent back.

## Party and Pokédex sync

Shadow-party updates and Pokémon seen/caught, linked by a shared Pokémon ID.

## Saving

Both sides save at the same moment (save menu, Pokémon Centers), and the world is rebuilt on load.

## Topics in DESIGN.md not yet placed in a category

To be assigned before message definitions are written:

- warps, and travel (Fly, Teleport, Dig, Escape Rope, blacking out)
- menus as Minecraft screens (start menu, bag, Pokédex, Mart, PC, Game Corner, Name Rater, intro)
- items crossing over (TMs/HMs, evolution stones, Name Rater renames)
- fishing (which rods the player owns)
- Flash lighting in Rock Tunnel
- crash recovery and resync from the last save
- sound pass-through (if used)
