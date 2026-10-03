# Kanto Bridge protocol

Status: **skeleton only.** No messages are defined yet. This file and [DESIGN.md](DESIGN.md) are
the source of truth; the JSON schemas in [`../protocol/`](../protocol/README.md) must match this file.

The protocol connects the two halves of the bridge:

- **gen1-bridge**: the Gen1Recomp mod (Lua), running in the player's hidden Gen1Recomp.
- **fabric-bridge**: the Fabric mod (Java), running in Minecraft with Cobblemon.

## Transport

A local TCP socket between the two halves on the player's own machine. The Gen1Recomp side needs
the sandbox's `network` permission (see [REFERENCES.md](REFERENCES.md)).

Decided 2026-10-03:

- **Endpoints.** The socket joins `gen1-bridge` and the **client** side of `fabric-bridge`, both on
  the player's machine. The Minecraft server never talks to Gen1Recomp (see
  [Relay to the Minecraft server](#relay-to-the-minecraft-server)).
- **Listening.** `gen1-bridge` listens on **`127.0.0.1:27151`**: loopback only, so no firewall
  prompt. The port is configurable on both sides, e.g. for two players testing on one PC.
  - `fabric-bridge`'s client connects when Minecraft starts and reconnects automatically with
    backoff.
  - `gen1-bridge` serves one connection at a time; a new connection replaces the old one.
  - Gen1Recomp mods only start once a game boots, so the listener exists from then on.
  - **Why this way round:** the Lua side is single-threaded and sandboxed, and polling `accept()`
    each logic step is its simplest non-blocking pattern, while Java can connect and retry on a
    background thread. Gen1Recomp is also the long-lived side, while Minecraft clients restart
    and rejoin.
- **Framing.** Newline-delimited JSON: one UTF-8 JSON object per line. The Lua side keeps a
  reassembly buffer for partial reads.
- **First message.** The version handshake (DESIGN.md §9).

Still to decide, together with the messages:

- the message envelope (type, ids) and handshake fields
- heartbeat and timeout values
- error handling and backpressure

## Relay to the Minecraft server

Decided 2026-10-03: the Minecraft server gets each player's story data only from **that player's
own client**, over Fabric custom payload packets. It replies the same way; for example, a battle
result goes back to the client, which forwards it to Gen1Recomp.

- The same path serves singleplayer (integrated server) and multiplayer.
- The server trusts each client for its own player's story state.
- ROM-derived data such as text and trainer teams may pass through the server.

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
