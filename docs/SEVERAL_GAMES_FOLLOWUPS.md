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

- [ ] **Hoenn's shape map is too big:** 2,080,948 characters against a 524,288 budget (about 4 times; 1,766,184 before
      the 151 maps built since), sent to every player who joins, and at the build's Gen 3 limit of 32 parts. The fix is
      planned as voxel plan R10 (a region's shape map sent in parts, on demand).
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
- [ ] **Emerald's trades, daycare and eggs** are undone by the next party sync.
- [ ] **Hoenn's people and the surf blob** are drawn as placeholders; **animated tiles and darkness** aren't shown.
- [ ] **An empty nickname can't be confirmed** in the client (the hidden game would keep the species' name, but
      `ui.open` sends no default): send the species' name as the default, or allow an empty name.
- [ ] **Starting in Hoenn:** a fresh player always follows Kanto's game first (the legacy region); there is no way to
      choose where to start (the video's autopilot forced Hoenn).
- [x] **Emerald's script warps** (2026-10-14): not the script warps themselves but every warp's tail. Emerald keeps
      fading in after the map.entered that ends a warp, and gen1-bridge's warp observer took that fade for a new "script"
      warp that never arrived: the client went dark and held the player for ten seconds after almost every door and
      stairs (14 times in one story run), and cutscene fades with no map change (the clock, a battle's end, Birch's
      lab) did the same. The fade that carried an arrival now starts no warp, and a fade-started script warp whose fade
      ends without a map change arrives a second later where the player stands. The story run now: none.

## Later: not in the plan (§8 of the plan)

- [ ] Gold, Silver and Crystal (Johto): a Gen 2 adapter, its own research first (Crystal is imported).
- [ ] FireRed and LeafGreen as a second Kanto; Yellow.
- [ ] Several games in one process, if Gen1Recomp adds a way to switch games.
- [ ] Foreign Pokémon allowed in every region; rules per region (running shoes, bike speeds).
- [ ] Heights for Hoenn's outdoors and Gen 3's two-floor bridges.
- [ ] Visiting a region without its game; level caps or a region-only party; a guard in the gate house.
