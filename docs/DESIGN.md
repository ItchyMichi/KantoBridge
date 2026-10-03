Kanto in Minecraft: complete design
1. Overview

Three programs, each with one job:

Gen1Recomp (hidden): runs the real game: story, scripts, map, NPCs, bag, money and badges. It's the brain.
Minecraft with Cobblemon (visible): draws everything and owns all Pokémon, wild spawns and battles. It's the body.
The bridge: a small Gen1Recomp mod plus a Fabric mod for Minecraft, talking over a local network connection. Gen1Recomp mods need the sandbox's "network" permission to use sockets.

A one-time world builder creates Kanto in Minecraft from the player's own ROM, and nothing from the ROM is ever distributed.

2. Setup for players
One-click installer that sets up Gen1Recomp, the bridge, Minecraft, Fabric and Cobblemon, using a bundled launcher like SkyCraft's.
ROM import: the installer asks for the player's ROM, imports it into Gen1Recomp, then runs the world builder.
Pinned versions of Gen1Recomp, the voxel mod, Cobblemon and the bridge, updated deliberately.
Other Gen1Recomp mods that change encounters or rendering stay off in the hidden game unless the bridge supports them.
3. World building

Scale and shape:

Footprint: each Game Boy tile becomes a fixed footprint, such as 2×2 blocks.
Height from voxel data: Dramatic Shape's terrain heights decide how tall each tile stands, divided down to blocks. The builder reads its cache or calls its generator.
Meaning from tile data: Gen1Recomp's decoded map data tags each tile as walkable, wall, water, ledge, tall grass, warp, counter or cuttable tree. Voxel data gives the shape; tile data gives the behaviour.

Generated packs (built locally from the player's ROM):

Resource pack: block textures from the decoded tile art, NPC textures from sprites, and optionally music and sound effects.
Datapack: custom biomes, habitat pools and any world settings.

Collision that protects the story: every blocked tile is solid and too tall to jump over, with barrier blocks where needed. Ledges only work one way, and players are in adventure mode with no breaking or placing blocks.

Overworld: all towns and routes sit on the surface, around Y 64, joined edge to edge exactly as the original game connects them.

Interiors underground:

Placement: each interior is built directly under its own building where possible. Minecraft loads full chunk columns, so the interior is already loaded when you reach the door.
Bigger interiors get deeper layers so they don't overlap neighbours.
Multi-floor buildings are stacked in order beneath the building.
Caves like Mt. Moon, Rock Tunnel, Diglett's Cave and the Seafoam Islands sit under their mountains or islands.
Separation: thick solid layers between spaces, at least 16 blocks apart, so nothing is seen, walked into or heard.
Warps: doors, stairs, ladders and holes stay warps. The hidden game performs the warp, and Minecraft teleports you with a short fade.
Room for more: a datapack can lower the world's bottom limit if more underground space is ever needed.

Lighting and atmosphere:

Light: invisible light blocks light interiors. Rock Tunnel only gets its lights once the hidden game says Flash was used.
Custom biomes: a "Kanto Interior" biome with no fog, no cave sounds and indoor music, plus a separate cave biome.

Minecraft's own rules switched off: hunger, damage, fall damage, weather and vanilla mob spawning. The day/night cycle is off or purely cosmetic.

4. Story and controls

The hidden game runs the story exactly as written. The bridge translates both ways.

Into the game:

Position: your position is rounded to a tile and your view to one of four facings, sent whenever you cross into a new tile.
Interacting: right-clicking an NPC, sign or item becomes "face it and press A."
Field moves: Cut, Strength, Surf and Flash are triggered by interacting with the tree, boulder or water.

Out to Minecraft:

Dialogue: text boxes, yes/no choices and naming screens become Minecraft screens.
NPCs: positions, facing and visibility are mirrored as Minecraft characters with no AI of their own.
World changes: cut trees, moved boulders, Snorlax waking and collected items all update the world.

Movement the game controls:

Cutscenes: controls lock and you're moved along the path the game dictates, as when Oak leads you to his lab.
Forced-movement tiles: spinner tiles, warp pads and currents work the same way.
Travel: Fly, Teleport, Dig and Escape Rope are instant teleports. Blacking out sends you to your last Pokémon Center.
Bicycle and Surf: these get their own speed and water rules, including Cycling Road's slope.

Menus as Minecraft screens: the start menu, bag, Pokédex, Mart, PC, Game Corner, Name Rater and the opening intro, all reading from the hidden game.

5. Pokémon

Wild Pokémon:

Grass encounters off in the hidden game.
Cobblemon habitat blocks spawn them instead. Habitat pools arrived in Cobblemon 1.8. You'd make one custom pool per route from the original encounter tables, with a single phase so routes stay consistent.
Gen 1 rules: the original 151 only, original levels, and version exclusives matching the ROM.
Fishing only works once the hidden game says you own the right rod.

Scripted encounters stay in the hidden game: Snorlax, the legendary birds, Mewtwo, the Power Plant Voltorb and Electrode, the Lavender ghost and gift Pokémon.

Battles:

Cobblemon runs every battle. The hidden game starts trainer battles and hands over the team; the results come back.
Ruleset: decide whether battles use Gen 1 rules or modern ones.
Special cases: the Safari Zone, the old man's catching demo and the unidentified ghost need custom screens.

Items and moves crossing over:

TMs and HMs live in the bag, but teaching applies the move in Cobblemon. Decide whether HM moves can be swapped out.
Evolution stones used from the bag trigger the evolution in Cobblemon.
Daycare: decide whether to use Gen 1's daycare or Cobblemon's own systems.
Name Rater: renaming changes the Cobblemon nickname.
6. Data ownership and saving
Gen1Recomp owns: story flags, map state, NPCs, bag, money, badges, the shadow party and the Pokédex.
Cobblemon owns: all Pokémon data (party, PC, moves, levels, evolutions, natures, abilities), wild spawns and battles.
Minecraft owns: the player's exact position, camera and visuals.

Keeping them in step:

Shadow party: the hidden game keeps a simplified copy of only what the story checks, such as HM moves, party space for gifts and the Pokédex count.
IDs: every Pokémon has an ID linking its Cobblemon and shadow copies.
One PC: Bill's PC in the hidden game opens Cobblemon's PC.
Pokédex sync: every Pokémon seen or caught is sent to the hidden game, so Oak's aides and Oak's comments work.
Healing: Pokémon Centers heal in Cobblemon.

Saving: both sides save at the same moment, triggered by the save menu and Pokémon Centers. On load, the world is rebuilt from the story save (NPCs, removed trees, collected items), and Cobblemon refreshes the shadow party.

7. Sound

Either pass Gen1Recomp's music and effects through, or generate a local sound pack from the ROM like the textures. Cobblemon supplies Pokémon cries.

8. Multiplayer: separate stories

Setup: one player hosts a Minecraft world (a server, or LAN shared over the internet). Each player runs their own hidden Gen1Recomp with their own ROM, story save and bridge. Each player's textures come from their own locally generated resource pack, so no ROM-derived art is sent between players.

Shared: terrain, buildings, interiors, wild Pokémon, other players, and player battles and trades.
Per player: story progress, bag, money, badges, shadow party, Pokédex and every story object.

The golden rule: the real world is always open at every blockade spot. Each player's client draws their own story objects, and both the client and the server add collision for that player's remaining blockades. Both sides know the player's flags, so they always agree.

Player-specific:

Items: item balls, TMs, hidden items and NPC gifts. Everyone collects their own copy.
Blockades: cuttable trees, Strength boulders, Snorlax, the thirsty gate guards, Rocket grunts, the Lavender ghost, card-key doors and the Viridian old man.
Trainers and gyms: each player has their own trainers, gym puzzles and gym leaders.
Story and gift Pokémon: everyone gets their own Mewtwo, birds, Snorlax, Lapras, Eevee and fossils.

Smoothing it over:

Speech bubbles over players in dialogue, cutscenes or battles, so nobody looks like they're talking to thin air.
Fading other players slightly when they're standing inside one of your blockades.
Story battles are only visible to the player fighting them.
Warps are per player, so each person moves through doors independently.
Red vs Blue: the host's version decides wild exclusives, and players trade for the rest.

Trades and battles use Cobblemon's systems, and both players' bridges then update their shadow parties and Pokédexes, including trade evolutions.

Saving: each multiplayer world gets its own paired online story save and Pokémon data, kept separate from the offline save.

9. Reliability
Version handshake between the bridge halves before connecting.
Crash recovery: if one side crashes, the other pauses and resyncs from the last save.
Running hidden: Gen1Recomp keeps full speed while minimized and unfocused.
Test stand-ins: a fake version of each side for testing one half alone.
10. Build order

Single player:

Bridge connection, with walking in Pallet Town moving you in the hidden game.
World builder for Pallet Town, with the player's house and Oak's lab underground and working doors.
Dialogue and NPC mirroring.
Oak's cutscene, choosing a starter, and the rival battle in Cobblemon.
A Route 1 habitat pool, catching, and syncing the shadow party and Pokédex.
Viridian City: the Pokémon Center (healing, saving, PC) and the Mart (bag and money).
The rest of Kanto: forced movement, field moves, menus and special battles.

Multiplayer:

Two players in one world, seeing each other and sharing wild spawns.
One player-specific item ball and one cuttable tree, including collision.
Player-specific NPCs and trainers, with speech bubbles.
Trades and player battles updating both shadow parties.
The full list of player-specific objects.
11. Risks
Moving targets: Gen1Recomp, the voxel mod and Cobblemon all update often.
Licences: check the voxel mod's licence before reusing its code or cache format.
Performance: two programs at once; test on modest hardware early.
Legal: use only players' own ROMs, ship no game data, and treat this as a personal project. Nintendo has a long history of taking down fan projects, so check the risk properly before any public release.
12. Future

Gen1Recomp now also covers Gold, Silver, Crystal, FireRed and LeafGreen. Building the bridge around general ideas (tiles, warps, flags, parties) rather than Kanto specifics leaves the door open to Johto later.