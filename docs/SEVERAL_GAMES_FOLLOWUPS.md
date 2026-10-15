# Several games: what is left after MG8

The multi-game plan ([specs/MULTI_GAME_PLAN.md](specs/MULTI_GAME_PLAN.md)) is done: MG0–MG8 are committed (PROGRESS.md
"Several games"). This is what remains, as a checklist (2026-10-14). Tick an item when it is done and say where.

## Yours, at your PC

- [ ] **Spike S1's last part:** Blue and Emerald running hidden beside Minecraft on your PC: memory use, and that
      minimised windows keep full speed (a pod could only measure the two games alone: about 620 MB together).
- [ ] **Play-test** PLAYTEST.md "Several games", MG3 to MG8: none of it has been played in a real client by you yet.
      MG4 has two setup defaults to confirm (the fresh install renamed after an import; player 2's ports at +10).
- [ ] **Choose the pass's real exits:** stand where each clearing should start, face the trees, press F7 and send me
      the map, cell and facing. Today's are temporary test spots (Viridian City's north-west corner, Littleroot Town's
      south-east corner).

## Gaps to fix (development)

- [x] **Hoenn's shape map is too big** (2026-10-15): the shape documents are now offered by hash and sent only when a
      client lacks them; the client keeps them, so a rejoin downloads none. Measured: every join used to send about
      12.4 million characters of shapes (Hoenn's index alone 8.85 million, as plain text, plus its 2.08 million map);
      a first join now downloads 3.85 million once (the index deflated, 20 parts; the map, 32), a rejoin nothing (both
      checked in the real client, `tools/e2e/shapes_cache_check.py`). The build budget is now per region and per
      document (64 parts each), the same for every game. Hoenn's map itself is the same size (re-encoding it would
      save only 5-15%). Since MG9 G2G (2026-10-15) the regions' world indices and the slot map are offered the same
      way, deflated: with Johto beside Kanto and Hoenn a join used to send 7.5 million characters of them, a first join
      now downloads 0.63 million, a rejoin none.
- [x] **Three Hoenn seams** where neighbouring maps don't meet (2026-10-14: checked, nothing to rebuild). The game's own
      map data disagrees by two cells between western and eastern Hoenn, so one of the four joins can't meet. The build
      keeps the narrow Petalburg City / Route 104 path (2 cells wide) straight and puts the seam on Fallarbor Town /
      Route 114 and Dewford Town / Route 107; in the real client, walking across either, you are walked two cells
      sideways just past the edge (`tools/e2e/seam_check.py`). Route 116 / Verdanturf Town can't be crossed in the
      game; its overlap covers only trees and rocks.
- [x] **Hoenn maps not built** (2026-10-14): every map a player can be put in is built now: 508 of 519 (the 11 left out
      are the decomp's unused and prototype maps), among them Sootopolis, the S.S. Tidal, the contest halls, the ferry's
      islands, Navel Rock and the secret bases. The 36 "no arrival" interiors were the export's warp landings, 1-based
      where the protocol says 0-based: every interior now sits under its own door. Warnings: 7 (were 43).
- [x] **Emerald's trades, daycare and eggs** (2026-10-15): the engine changed the party itself and the next sync took
      it back. Now an in-game trade sends the Pokémon away in Minecraft and gives the one received (its nickname and
      trainer); the daycare holds two, each taken from and given back to the Minecraft party (with the steps it
      walked as experience), and both are found again after CONTINUE. Cobblemon has no eggs, so an egg stays in the
      hidden game (never lost to a sync; parked in a hidden box while the party is full) and becomes a Minecraft
      Pokémon when it hatches, with the nickname given on the hatch screen. Checked on the real Emerald
      (`test_emerald.py`'s party scene).
- [x] **Hoenn's people and the surf blob, animated tiles and darkness** (2026-10-15): people and objects now stand as
      the game draws them (a person a block wide and two tall, as tall as Kanto's; facing and stepping), the surf blob
      is under a surfer, Hoenn's water, flowers and other animated tiles move with the game's own timing (935
      animations, 504 of them on built maps), and the four caves that need Flash (Granite Cave B1F/B2F, Victory Road
      B1F/B2F) are dark until Flash is used, then lit by Night Vision as Kanto's are. Left out: the one tileset whose
      colours cycle (they stay on the first), berry trees' growth stages, a walker's second step frame, the GBA's
      Flash circle. An Emerald export from before needs exporting and Hoenn rebuilding to show them.
- [x] **An empty nickname can't be confirmed** (2026-10-15): it can now. Confirming the naming screen with nothing
      typed sends an empty name, and the hidden game takes it as its own screen takes an empty name: no nickname (the
      Pokémon keeps its species' name, and in Gen 1 an evolution still renames it), or the screen's default where it
      has one (the Name Rater keeps the old nickname; Emerald's player name keeps the one it picked). Not the species'
      name as a typed nickname, which would freeze it. Gen 1's player and rival names still take a name or a preset,
      as in the game (an empty one re-prompts there).
- [ ] **Starting in Hoenn:** a fresh player always follows Kanto's game first (the legacy region); there is no way to
      choose where to start (the video's autopilot forced Hoenn).
- [x] **Emerald's script warps** (2026-10-14): not the script warps themselves but every warp's tail. Emerald keeps
      fading in after the map.entered that ends a warp, and gen1-bridge's warp observer took that fade for a new "script"
      warp that never arrived: the client went dark and held the player for ten seconds after almost every door and
      stairs (14 times in one story run), and cutscene fades with no map change (the clock, a battle's end, Birch's
      lab) did the same. The fade that carried an arrival now starts no warp, and a fade-started script warp whose fade
      ends without a map change arrives a second later where the player stands. The story run now: none.

## Later: not in the plan (§8 of the plan)

- [ ] Gold, Silver and Crystal (Johto): a Gen 2 adapter, its own research first (Crystal is imported). Planned
      2026-10-15: [specs/GEN2_BRIDGE_PLAN.md](specs/GEN2_BRIDGE_PLAN.md) (MG9).
- [ ] FireRed and LeafGreen as a second Kanto; Yellow.
- [ ] Several games in one process, if Gen1Recomp adds a way to switch games.
- [ ] Foreign Pokémon allowed in every region; rules per region (running shoes, bike speeds).
- [ ] Heights for Hoenn's outdoors and Gen 3's two-floor bridges.
- [ ] Visiting a region without its game; level caps or a region-only party; a guard in the gate house.
