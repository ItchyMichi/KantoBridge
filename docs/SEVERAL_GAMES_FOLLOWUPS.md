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

- [ ] **Hoenn's shape map is too big:** 1,766,184 characters against a 524,288 budget (about 3.4 times), sent to every
      player who joins. The fix is planned as voxel plan R10 (a region's shape map sent in parts, on demand).
- [ ] **Three Hoenn seams** where neighbouring maps don't meet: Dewford Town / Route 107, Fallarbor Town / Route 114,
      Verdanturf Town / Route 116 (plus one overlap).
- [ ] **Hoenn maps not built:** Sootopolis and others that no door or warp reaches (the truck and the maps only left by
      a warp are built since `6ce738b`); 36 interiors have no known arrival cell.
- [ ] **Emerald's trades, daycare and eggs** are undone by the next party sync.
- [ ] **Hoenn's people and the surf blob** are drawn as placeholders; **animated tiles and darkness** aren't shown.
- [ ] **An empty nickname can't be confirmed** in the client (the hidden game would keep the species' name, but
      `ui.open` sends no default): send the species' name as the default, or allow an empty name.
- [ ] **Starting in Hoenn:** a fresh player always follows Kanto's game first (the legacy region); there is no way to
      choose where to start (the video's autopilot forced Hoenn).
- [ ] **Emerald's script warps** (Mom leading the player into the house, leaving the rival's house) end only by the
      warp observer's 20-second timeout (`warp.no_arrival kind=script`, the client's `warp: arrive_timeout`): the
      client may pause there.

## Later: not in the plan (§8 of the plan)

- [ ] Gold, Silver and Crystal (Johto): a Gen 2 adapter, its own research first (Crystal is imported).
- [ ] FireRed and LeafGreen as a second Kanto; Yellow.
- [ ] Several games in one process, if Gen1Recomp adds a way to switch games.
- [ ] Foreign Pokémon allowed in every region; rules per region (running shoes, bike speeds).
- [ ] Heights for Hoenn's outdoors and Gen 3's two-floor bridges.
- [ ] Visiting a region without its game; level caps or a region-only party; a guard in the gate house.
