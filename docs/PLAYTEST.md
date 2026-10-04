# Kanto Bridge: play-test checklist

Manual checks for the user, milestone by milestone, in the order to run them. The automated checks
(`tools/check.ps1`) cover everything that can run without a person watching; these steps cover what
only you can see. Tick items off here, or tell the next run what failed (PROGRESS.md lists the open
ones).

**Before any session:**

- **The easy way (since M10):** `.\tools\launch.ps1` from the repository folder. It starts
  Gen1Recomp (Blue, with its log console), waits until the bridge listens, then starts the dev
  client. `-Players 2` starts the two-player setup (M9), `-Watch` restarts Gen1Recomp if it closes,
  and `-DryRun` only shows what it would do. Details are in `tools/README.md`.
- **By hand:** start Gen1Recomp from PowerShell inside `local\gen1recomp\` with
  `.\gen1recomp.exe --console --game=blue`, then the client as in M1.
- You can minimise Gen1Recomp's window; it keeps full speed (measured in M10). Older notes below
  say "never minimise" — that was a guess from reading the code, and it was wrong.
- Close Gen1Recomp and Minecraft before starting an automated run.

## M0: Foundations

Nothing in M0 is visible in game. These are optional, but the second one is worth doing once,
because M0 changed the build in a way that affects the dev client:

- [ ] With the console open as above, a line containing `[gen1-bridge] gen1-bridge loaded` appears
      once the title screen shows, followed by `[gen1-bridge] game.ready ...`. (Tell the next run
      whether it did; the pre-flight didn't record it. Automated runs already see both lines through
      piped stdout.)
- [ ] `runClient` reaches the title screen with Cobblemon loaded: in PowerShell, in
      `fabric-bridge\`, run
      `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'; .\gradlew.bat runClient`.
      Before M0's Kotlin-plugin fix, Cobblemon would have crashed during startup
      (`ClassNotFoundException: net.minecraft.class_2960`). Its run folder is now `local\minecraft\`.
      Quit from the title screen.
- [ ] `.\tools\check.ps1` runs green on your machine (a few minutes, mostly Gradle).

## M1: Bridge connection

**Start both programs:**

1. In PowerShell: `cd "C:\User Apps\Kanto Bridge\local\gen1recomp"`, then
   `.\gen1recomp.exe --console --game=blue`. Continue your save (or start a new game) so you are in
   the overworld.
2. In a second PowerShell: `cd "C:\User Apps\Kanto Bridge\fabric-bridge"`, then
   `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'; .\gradlew.bat runClient`.
   Keep Gen1Recomp's window visible beside Minecraft.

**Check:**

- [ ] On Minecraft's title screen, the top-left status line reads **connected (blue)** and fades
      after about 5 seconds.
- [ ] Press **F7**: the debug overlay shows the hidden game's map id, cell, facing and mode.
- [ ] Walk around in **Gen1Recomp's own window**: the overlay's cell and facing follow every step;
      the mode reads `idle` while walking and changes (`screen`, `cutscene`, …) when a text box or
      menu opens.
- [ ] Go through a door in Gen1Recomp: the overlay's map id changes.
- [ ] Close Gen1Recomp: the status line shows "not connected, retrying…". Start it again: Minecraft
      reconnects by itself within a few seconds.
- [ ] Gen1Recomp's console shows `[gen1-bridge]` lines about the listener and the session, and no
      game text.

Minecraft's settings for the bridge live in `local\minecraft\config\kantobridge-client.json` (the
port, 27151; since the voxel plan's V2 also `"shapes": "on"` or `"off"`). F7 can be rebound in Controls.

## M2: World export and builder (Pallet Town and its interiors)

The agent has already built a world called **Kanto** from your game (Pallet Town, Red's house on both
floors, Blue's house and Oak's lab), headless. It is in `local\minecraft\saves\Kanto`, and its
textures are in `local\minecraft\kantobridge\resourcepack\`.

**Look at the built world** (Gen1Recomp doesn't need to run for this):

1. Start Minecraft as in M1 (`.\gradlew.bat runClient`) and open **Singleplayer → Kanto**.
2. You spawn in Pallet Town in Adventure mode. To look inside the underground rooms, switch to
   spectator mode with `/gamemode spectator` (fly through walls), and back with
   `/gamemode adventure`. If commands are off in this world, press Esc → **Open to LAN** → Allow
   Cheats: **ON** → Start LAN World first.

- [ ] Pallet Town looks right: the houses, the lab, fences, flowers, the water to the south, in your
      game's Super Game Boy colours. The flowers and the water are animated.
- [ ] The textures load: no purple-and-black "missing texture" blocks anywhere.
- [ ] The interiors are underground, below their buildings: Red's house 1F, with its 2F below that,
      Blue's house and Oak's lab. Each is fully enclosed (no holes to the void), lit, and each room's
      floor matches the original layout.
- [ ] Walls are about 2 blocks high, and roofs and cliffs about 4. You can't jump (that comes in
      M3), but in spectator mode nothing looks broken.
- [ ] The trees around the town form a solid wall.
- [ ] Note anything that looks wrong; say which map and roughly where.

**Optional: a full Kanto.** The agent also made an experimental headless build of all of Kanto,
`KantoFullE2E` (Singleplayer list). It has two known issues that M8 fixes: one interior is missing,
and a few Cut-tree/door spots may show the wrong block.

**Optional: the "Create Kanto world" button.** This builds **all of Kanto**, not just Pallet Town,
from your running Gen1Recomp. Full-size builds aren't checked until M8, so treat this as an
experiment.

1. Start Gen1Recomp (as in M1) and stay at its title screen, or in game.
2. In Minecraft's title screen, click **Create Kanto world**. Watch the export progress (the status
   line says "exporting…"), the texture reload, the world creation and the build progress.
3. - [ ] You arrive at the Pallet Town spawn in Adventure mode; the status line reconnects.
4. - [ ] Pressing **Cancel** during the export, or during the build, removes the half-made world
     folder.
5. - [ ] With Gen1Recomp closed, the button gives a clear error.

## M3: Walking and warps

You walk in Minecraft and the hidden game's player walks with you. The `Kanto` world only has
Pallet Town and its four interiors so far, so play inside them. **Don't walk north out of Pallet
Town**: that starts Oak's cutscene, which comes in M5.

**Start:**

1. Start Gen1Recomp as in M1 and start a **New Game** (do the intro in Gen1Recomp's own window for
   now; M5 moves it to Minecraft). Stop once you're standing in Red's room.
2. Start Minecraft as in M1 and open **Singleplayer → Kanto**.

**Check:**

- [ ] You're placed in Red's room (the underground copy), on the same tile and facing the same way
      as in Gen1Recomp.
- [ ] Walking with WASD moves Gen1Recomp's player tile by tile, in step with you; the speed feels the
      same in both windows.
- [ ] Walking into walls, tables and the TV stops you; Gen1Recomp's player just turns.
- [ ] The stairs: the screen fades out and in, and you're downstairs where Gen1Recomp's player is.
- [ ] The front door: stepping down off the mat fades you out; you appear outside the house and
      walk one step out, like Gen1Recomp's player.
- [ ] Blue's house and Oak's lab: in and out through their doors works the same way.
- [ ] You can't jump or sprint, and you take no damage; it's always noon and Peaceful.
- [ ] While a text box is open in Gen1Recomp, you can't move in Minecraft.
- [ ] Walking into a person's tile snaps you back (the NPCs themselves appear in M4).
- [ ] F7 shows the movement counters: steps sent and answered stay equal.
- [ ] `/gamemode spectator` still lets you fly around (if commands are on); `/gamemode adventure`
      puts you back on Gen1Recomp's tile.
- [ ] Note anything that feels off: lag, rubber-banding, corner cuts, the fade, the camera turning.

## M4: Dialogue, menus and NPC mirroring

Start as in M3 (Gen1Recomp in game, standing in Pallet Town or a house; Minecraft in the `Kanto`
world). Text is still in Minecraft's own font.

- [ ] People appear as flat, upright figures in their places (Mom downstairs, the girl in Blue's
      house, Oak's aides in the lab). Walking ones walk tile by tile, and they face the right way
      from every side you look at them.
- [ ] Talk to Mom with **Z** (or right-click her): a Gen 1 style text box opens in Minecraft, types
      the text, shows ▼ when it waits; **Z** or **X** moves on; it closes at the end. Gen1Recomp's
      window shows the same box moving along.
- [ ] Read a sign in Pallet Town, and check the TV and the bookshelves with Z or right-click.
- [ ] **Enter** opens the start menu; arrows and Z pick, X backs out.
- [ ] In the menu, ITEM opens the bag list; picking an item gives USE/TOSS; TOSS asks for a quantity
      (up/down, left/right by 10); X backs out of each.
- [ ] A YES/NO question works with arrows and Z; X answers NO.
- [ ] Your name in the start menu (the trainer card), or any other picture-style screen, opens the
      **mirror**: Gen1Recomp's live picture inside Minecraft, driven with arrows, Z and X.
- [ ] Esc during a text box opens Minecraft's pause menu; closing it brings the box back.
- [ ] F7 shows the UI, NPC and action lines.
- [ ] Note how the boxes, the `PK`/`MN` letters, ▶ and ▼ look, and anything sized or placed oddly.

## M5: Opening, Oak's cutscene, starter and rival battle

This plays the story's opening from Minecraft. Use a **new game**: in Gen1Recomp's window, stay at
its title screen.

1. Start Gen1Recomp as in M1 and leave it at the title screen.
2. Start Minecraft as in M1 and open **Singleplayer → Kanto**.

- [ ] Minecraft shows Gen1Recomp's title screen in the mirror; choose NEW GAME with arrows and Z.
- [ ] Oak's speech shows in the mirror. When he asks your name, and later your rival's, Minecraft's
      own naming screen opens (presets first, then NEW NAME for the letter grid); the names you type
      appear in Gen1Recomp.
- [ ] You start in Red's room in Minecraft, on the same tile as in Gen1Recomp.
- [ ] No Cobblemon starter prompt appears at any point.
- [ ] Walk out of the house and north towards the edge of Pallet Town: Oak stops you, walks you to
      his lab, and you can't move while he leads you (the camera follows the walk).
- [ ] In the lab, choose a starter from the table (Z or right-click a ball). If it asks for a
      nickname, Minecraft's naming screen opens.
- [ ] The starter appears in Cobblemon's party (with its nickname, if you gave one).
- [ ] The rival's battle opens Cobblemon's battle screen with the rival's name; no extra trainer
      model appears; you can't walk or press Z/Enter during the battle.
- [ ] Win or lose, the story continues afterwards (in Gen 1 the lab battle never ends in a blackout).
      Forfeiting counts as a loss.
- [ ] After the battle, the starter's HP and level in Gen1Recomp's party screen (Enter → POKéMON, in
      the mirror) match Cobblemon's.
- [ ] F7 shows a "Relay:" line counting the battle and party messages.

## M6: Route 1, habitat pools, catching, party/Pokédex/bag sync

The `Kanto` world now also has Route 1 and Viridian City (its buildings come in M7). Play on from your
M5 game (Oak's parcel errand sends you north).

- [ ] Walking through Route 1's tall grass starts **no** battle in Gen1Recomp's window.
- [ ] Wild Pokémon spawn in Minecraft around Route 1's grass (Cobblemon's normal wild spawning), only
      Route 1's species and levels, standing on the ground, not on trees or roofs.
- [ ] Battle one (Cobblemon's usual way) and catch it: it joins your party in Cobblemon, and the
      Gen1Recomp party screen (Enter → POKéMON, on the mirror) shows it too.
- [ ] The Pokédex count rises in Gen1Recomp (Enter → POKéDEX) as you see and catch Pokémon.
- [ ] Poké Balls and Potions you have in Gen1Recomp's bag appear in your Minecraft inventory, and the
      counts match; using or throwing one in Minecraft lowers the Gen1Recomp count too. Key items never
      appear in Minecraft.
- [ ] Right-clicking a person with an empty hand talks to them; holding a Poké Ball throws it instead.
- [ ] Choosing **Run** in a wild battle ends it.
- [ ] Optional: use a Potion from Gen1Recomp's bag (ITEM in the start menu) on a Pokémon: Cobblemon's
      HP follows.

## M7: Viridian City (Pokémon Center, Mart, PC, money, trainers, saving)

The agent rebuilds the `Kanto` world with Viridian City's buildings and Route 2 before you test (your
player data stays).

- [ ] **Heal at the nurse** in Viridian's Pokémon Center: Cobblemon's party is healed, and both games
      save (Gen1Recomp writes its save after the nurse's farewell).
- [ ] **The Mart:** buy and sell; the money box and the clerk's line update; the bag and the Poké
      Ball/Potion counts in Minecraft follow; cancelling or answering NO changes nothing.
- [ ] **Bill's PC** (in the Center): Cobblemon's PC opens; deposit or withdraw a Pokémon; closing it
      returns to the Gen 1 PC menu; LOG OFF. The hidden party (Enter → POKéMON) matches. Your own PC
      (items) stays the Gen 1 screen.
- [ ] **A trainer** (e.g. on Route 2 or in Viridian Forest's entrance area): the "!" bubble, the
      trainer walks up, Cobblemon's battle starts; after a win, talking to them again gives their
      after-battle line; the route music comes back after the battle.
- [ ] **Black out once** (lose a battle on purpose): you appear in front of the Viridian Pokémon
      Center, healed, with half your money.
- [ ] **Save** from Gen1Recomp's start menu (SAVE), quit both games, start them again, and continue:
      your position, party, bag, money and story agree in both.
- [ ] Watch how long saves take, and note any stall.
- [ ] F7 shows the world id and the save slot.

## M8: The rest of Kanto

The `Kanto` world now holds **all of Kanto** (220 maps, built headless from your game). Play the story
in order; tick each item, or note what went wrong and where (map and what you did).

**Field moves and movement (try each once it's available):**

- [ ] **Cut** (HM01 + Cascade Badge): with an empty hand, right-click (or Z) a small tree: it's cut for
      you; leave the area and come back: it has grown back.
- [ ] **Surf**: right-click water: you ride on the surface (surf sprite); right-click land to get off.
- [ ] **Strength**: use it from the party menu, then walk into a boulder twice: it moves.
- [ ] **Flash** in Rock Tunnel: dark until you use Flash, then you can see (Night Vision).
- [ ] **Bicycle** from the bag: faster; Cycling Road's slope pulls you downhill; sneak brakes.
- [ ] **Fly** (party menu → FLY → a town), **Dig**, **Teleport**, **Escape Rope**.
- [ ] Spinner tiles slide you (Viridian Gym, Rocket Hideout); holes and warp pads move you (Silph Co,
      Saffron Gym, the Mansion); Seafoam's currents push you.
- [ ] Blockades stay until the story clears them, for you: the thirsty gate guards, Snorlax,
      card-key doors, Rocket grunts, the Viridian old man.

**Badge by badge:**

1. - [ ] Viridian Forest's bug catchers; **Brock** (badge, TM, his Bide).
2. - [ ] Mt. Moon (choose a fossil); Cerulean: the rival, **Misty**, Nugget Bridge, Bill (the S.S.
     Ticket); the bike voucher and the bike shop.
3. - [ ] Vermilion: the S.S. Anne (HM01 from the captain), **Lt. Surge** (the trash-can switches);
     Diglett's Cave.
4. - [ ] Rock Tunnel with Flash; Lavender's Pokémon Tower (the unidentified ghosts are native battles on
     the mirror until you have the Silph Scope); Celadon: **Erika**, the Game Corner (slots on the
     mirror; the prize counter), the Rocket Hideout (spinners, the elevator, Giovanni); the Silph
     Scope, the ghost Marowak, Mr. Fuji and the Poké Flute.
5. - [ ] Snorlax on Route 12 or 16 with the flute (a Cobblemon battle; catch it or knock it out; it
     stays gone); Fuchsia: **Koga**, the Safari Zone (a native battle on the mirror; what you catch
     appears in Cobblemon), Surf and Strength.
6. - [ ] Saffron: Silph Co (card keys, warp pads, the Lapras gift, Giovanni), **Sabrina**.
7. - [ ] Cinnabar: the Mansion's switches, **Blaine**, the lab (fossil revival); Seafoam Islands.
8. - [ ] Viridian Gym: **Giovanni**; Victory Road's boulders; **the Elite Four** (no healing between
     rooms), **the Champion**, the Hall of Fame and the credits (on the mirror).

**Optional extras:**

- [ ] The daycare (Route 5): deposit, walk, withdraw: the Pokémon comes back with experience.
- [ ] An in-game trade, and the Name Rater (Lavender).
- [ ] The Power Plant (Voltorb/Electrode, Zapdos), the other birds, Mewtwo in Cerulean Cave.
- [ ] Battle music: Gen1Recomp's window plays Gen 1 battle music during Cobblemon battles; Cobblemon's
      own battle music stays silent.

**After-battle text (check as you meet them):**

- [ ] A gym leader: after the Cobblemon win, their badge lines appear (with the badge jingle), then
      the prize money, then the TM.
- [ ] The Game Corner rocket: his line, then he leaves. Silph Co's Giovanni: his line shows once.
- [ ] The Elite Four: each door opens after its battle; nobody heals you between rooms.

## M9: Multiplayer (two players on one PC)

**Setup (once):** Player 2 needs a second Gen1Recomp install. Make `local\gen1recomp-p2` the same way as
`local\gen1recomp` (README "Setting up a new machine", step 4: unzip the release, `portable.txt`, the
`mods\gen1-bridge` junction, import your ROM). For a quick try, `local\gen1recomp-e2e` (the automated
tests' install) also works as Player 2's game.

**Start (M10's `tools\launch.ps1` will do this in one go):**

1. Start Player 1's Gen1Recomp (`local\gen1recomp`, port 27151), then Player 2's (`local\gen1recomp-p2`).
   The second one picks port 27152 by itself; to fix it, set BRIDGE PORT = 27152 in its mod settings
   (F10). Keep both windows visible.
2. In `local\minecraft\server.properties` set `online-mode=false` (the headless builds already point
   `level-name` at `saves/Kanto`), then in `fabric-bridge\` run `.\gradlew.bat runServer`.
3. In two more terminals in `fabric-bridge\`: `.\gradlew.bat runClient` (Player 1) and
   `.\gradlew.bat runClient2` (Player 2). Both: Multiplayer → Direct Connection → `localhost`.

**Check:**

- [ ] Each player's Gen1Recomp switches to its own save slot for this world (Gen1Recomp's launcher
      later shows it as `MULTIPLAYER …`); your single-player save is untouched.
- [ ] Player 2's first join exports their own art and reloads textures (the HUD says so); the world
      then looks the same for both.
- [ ] You see each other walking; puppets (NPCs, item balls) are each player's own: take an item ball
      as Player 1, and it's still there for Player 2.
- [ ] Cut a tree as Player 1: it's gone for Player 1 only; Player 2 still bumps into it.
- [ ] Standing inside the other player's blockade (an uncut tree, a guard, a boulder), you look faded
      to them.
- [ ] Speech bubbles appear over the other player while they're in a dialogue, a cutscene or a battle.
- [ ] Each player's trainers and gym leaders are their own; a story battle is only visible to the
      player fighting it (their opponent's Pokémon may be visible to others).
- [ ] Doors and warps work per player.
- [ ] A Cobblemon trade between the players (try a trade evolution, e.g. Kadabra): both Gen1Recomp
      parties update afterwards.
- [ ] A Cobblemon battle between the players: both parties' HP and levels sync back afterwards.
- [ ] Leaving the server and opening the single-player `Kanto` world: Gen1Recomp returns to your
      offline save slot.

## M10: Reliability, launch and performance

**Launching:**

- [ ] `.\tools\launch.ps1 -DryRun` prints what it would start (Gen1Recomp with `--game=blue
      --console`, then `gradlew runClient`) and starts nothing.
- [ ] `.\tools\launch.ps1` starts Gen1Recomp, waits until the bridge listens ("Gen1Recomp is
      listening on 127.0.0.1:27151"), then starts the dev client. Open the `Kanto` world and play a
      little.
- [ ] Running it again while Gen1Recomp is still open reuses that Gen1Recomp instead of starting a
      second one.
- [ ] Optional: `.\tools\launch.ps1 -Players 2` starts both Gen1Recomps, the dev server and both
      clients (M9's setup in one go).

**Crash recovery (single player, in the `Kanto` world):**

- [ ] Close Gen1Recomp's window while walking. Within about 2 seconds Minecraft shows the pause
      overlay ("waiting for Gen1Recomp", the port and a countdown), and the world stops.
- [ ] While the overlay is up, Esc opens the normal pause menu, and closing that menu brings the
      overlay back.
- [ ] Start Gen1Recomp again (or start with `.\tools\launch.ps1 -Watch`, which restarts it by
      itself). The overlay closes and Gen1Recomp's title menu appears in Minecraft. Pick CONTINUE:
      you're back where you last saved.
- [ ] **A save that went back:**
      1. Save in Gen1Recomp, then receive a Pokémon from the story (a gift, a trade or a Game
         Corner prize).
      2. Close Gen1Recomp without saving, restart it and CONTINUE.
      3. A warning says Gen1Recomp went back to its last save, and that Pokémon received since
         are still in your party.
- [ ] **The daycare:**
      1. Deposit a Pokémon at the Route 5 daycare.
      2. Close Gen1Recomp without saving, restart it and CONTINUE.
      3. The Pokémon is back in your party, not stranded in the daycare.
- [ ] Reconnect in the middle of a text box: the same text box comes back.
- [ ] If Gen1Recomp ever freezes on its blue error screen, the overlay says it isn't answering and
      names its crash log. Don't share that log: it may contain game text.

**Minimised and hidden:**

- [ ] Minimise Gen1Recomp's window while walking in Minecraft: walking stays in step, with no
      slow-down.
- [ ] Optional: with the F7 overlay open, the mirror (e.g. the party screen) shows at most 30 frames
      a second.

**Multiplayer extras (two clients, as in M9):**

- [ ] Close one player's Gen1Recomp: the other player sees a ⏸ bubble over them until it's back.
- [ ] Surf with one player: the other sees them on the surf sprite, not standing on the water.

## Voxel plan (specs/VOXEL_BLOCKS_PLAN.md)

The voxel plan's milestones V1–V9 have their own items below. **Measurements** (the plan's §10) write numbers only to
`local/reports/mesh_report.json`. For each one:

- Start the dev client with the measuring tool: in `fabric-bridge\`, run
  `$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'; .\gradlew.bat runClient -Pkb.meshstats=true`.
- In the world, switch to spectator mode, turn vsync off, and stand at one of the viewpoints in
  `local/reports/viewpoints.json` (each has a ready `/tp` command; `tools/e2e/build_world.py` writes the file after a
  headless build).
- Press **F8** and keep still: the tool re-meshes the view, then samples 30 s of frames and the heap. F7 shows its
  line; the run is appended to the report. Do three runs per scene; the medians count.

### V1a: measuring tools

- [ ] **X1 baseline** (today's rendering, before V1b): check out the V1a commit, measure, then come back:
      ```powershell
      git checkout (git log --format=%h --grep='^V1a:' -1)
      # measure as above: the spawn town, the densest map you know, all of Kanto from above (render distance 16 and 32)
      git checkout main
      ```
      Use your current `saves/Kanto` (built before V3; a world rebuilt by V3 or later doesn't load at the V1a commit).
      F7's mesh line says `model json` here and `model runtime` from V1b on.
- [ ] Also note the time of one F3+T (the line's `reload` value) and of joining the world (`join→view`).

### V1: runtime model with the same cubes

Nothing should look different: the tile blocks are now drawn by one model of ours instead of generated JSON. The
generated pack holds textures only and sits at the **bottom** of the resource-pack list.

- [ ] The world looks exactly as before from the viewpoints (the spawn town, a cave, the sea from above): the same
      tiles, water and flowers animating, the closed art of a Cut tree, puppets and the surf sprite.
- [ ] F7: the Textures line reads `ready (-)` with `keys N (0 without art)`. (`-Pkb.meshstats=true` also shows the
      Mesh line with `model runtime`.)
- [ ] F3+T: the world reloads and still looks the same.
- [ ] Options → Resource Packs: "Kanto Bridge (generated)" is at the bottom of the selected list.
- [ ] **X4, a pack of your own:** make a folder pack in `local\minecraft\resourcepacks\` with a `pack.mcmeta`
      (`{"pack":{"pack_format":34,"description":"test"}}`) and one PNG copied from your generated pack
      (`local\minecraft\kantobridge\resourcepack\assets\kantobridge\textures\block\blue\tile\...`, same path inside
      your pack), painted over. Select it above "Kanto Bridge (generated)", press Done: that tile changes everywhere.
      Restart the game: the order is kept and the tile is still changed. Remove the pack: the tile is back.
- [ ] **Two players (as in M9):** the guest joins the host's world and draws it with their own art right away, with no
      export and no resource reload (the Textures line goes straight to `ready`), even if the host's world was built
      from the other game version.
- [ ] **X1 with the runtime model:** measure the same scenes as the V1a baseline (three runs each). Pass: re-mesh at
      most 1.25 × the baseline, frame rate at least 97 % of it, reload at most 2 s longer.
- Note: a world built before M9 has no slot map and now shows placeholders; all your worlds have one.

### V2: shape data, sync and per-face art

No block changes, but walls and buildings now show their drawn rows on their sides. Rebuild your world so it gets
the two shape files (a rebuild changes no block, and your player data stays): with Minecraft closed, run
`tools\.venv\Scripts\python.exe tools\e2e\build_world.py --maps all --world Kanto` from the repository folder (it
exports from the test install, then builds headless). Until then the world draws cubes (F7: `cubes because
no_shapes`).

- [ ] **Pallet Town from the south** reads like the 2D game: a house's front shows its door and windows at the
      height they're drawn, its roof rows above them; tree walls and fences show their own rows.
- [ ] **From the north** a house has no second front door: its back is plain wall.
- [ ] F7: the Shapes line reads `on yes`, an index hash and a table with shapes and quads, `cubes because -`.
      (`cache yes` from the second join on.)
- [ ] **Two players:** the guest sees the same faces as the host (F7 shows the same index hash on both), drawn with
      their own art.
- [ ] **`"shapes": "off"`** in `kantobridge-client.json`, then restart the game: the world looks exactly as at V1
      (F7: `cubes because shapes_off`). Set it back to `"on"`.
- [ ] Animated tiles (water, flowers) still move as before.

### V3: carriers and Tier A shapes

Buildings get their measured heights (up to six blocks), gable roofs and doors folded into their facades; ledges get a
lip. Blocks change, so rebuild your world first (Minecraft closed):
`tools\.venv\Scripts\python.exe tools\e2e\build_world.py --maps all --world Kanto`.

- [ ] Walk around a house, a fence, a ledge and the shore: no holes, no black faces, no flicker.
- [ ] Roofs look right: they rise from the facade's top to a ridge and fall again, hipped at the ends; the door's art
      stands at the back of its doorway, which you can still walk into.
- [ ] Ledge lips: a low step on top of each ledge; hopping down works as before, and you can't climb back up.
- [ ] The crosshair still lands on signs and counters, and talking or reading works as before (VD8: the block outline
      stays the cell's box; say if it bothers you).
- [ ] Walking is unchanged: nothing new to bump into on paths, grass, doors or shores; walls, trees and buildings stop
      you where they did.
- [ ] Interiors look as before (walls two blocks high).
- [ ] An old world (one built before this rebuild) still loads and shows its old look.

### V4: Tier B shapes

Trees are round, signs and posts stand as thin cut-outs on their own drawing, tall grass and flowers stand up from the
ground, and the void fill's two-cell ring around each outdoor map holds the border trees as round trees. Blocks change,
so rebuild your world first (Minecraft closed):
`tools\.venv\Scripts\python.exe tools\e2e\build_world.py --maps all --world Kanto`. The first time you join, your
client builds the tree and grass shapes from its own export (F7's Shapes line says `building`, then shows the table).

- [ ] **Tall grass:** walk into it from the south: its southern row of strokes hides your feet, and you walk through it
      freely. Flowers stand up too and still animate.
- [ ] **Wild Pokémon still appear in tall grass** (Route 1, and any route with grass).
- [ ] **Signs and posts** stand as thin cut-outs on their own drawing, the ground showing around them; reading a sign
      works as before.
- [ ] **Trees:** lone trees, tree walls along routes and the border trees around towns (two cells beyond the map) are
      round; no holes, no black faces, no flicker where trees touch. You still can't walk into or through them, nor
      leave a map through its border trees.
- [ ] **An item on a table** (in a house or a lab) sits on top of the table, not inside it.
- [ ] From above, looking north at 45°, the scene still reads like the 2D game (round trees show a little higher than
      they're drawn).
- [ ] Interiors: lone furniture (plants, statues) may stand as cut-outs or round shapes; nothing new to bump into.
- [ ] **Two players:** the guest sees the same trees and grass, drawn from their own export.
- [ ] `"shapes": "off"` still shows the V1 cubes.
- [ ] **X2, shapes against cubes** on the same world: measure (as above, three runs per scene) with `"shapes": "on"`,
      then with `"off"` (restart the game in between), at render distance 16 and 32. Pass: with shapes, at least 90 %
      of the cubes' frame rate at 16 and 85 % at 32; the heap with shapes at most 32 MB more.
- [ ] **X3, mesh time** (the same runs): the report's `ours_ms` has median ≤ 1.0 ms and P95 ≤ 4 ms; with shapes,
      `section_ms` P95 ≤ 2 × the cubes' and ≤ 15 ms. Each run also records `prediction` (sections whose emitted quads
      equal the pure-Java count): `equal` should be all of `sections`.

### V5: story art in the mesh

Closed story art (a Cut tree still standing, a locked card-key door, a sealed gate) is now part of the world's mesh,
lit and shaded like the terrain beside it, and still each player's own. Rebuild your world first (Minecraft closed):
`tools\.venv\Scripts\python.exe tools\e2e\build_world.py --maps all --world Kanto`.

- [ ] **A Cut tree before Cut:** a tree stands on its cell (round, like the trees around it); you can't walk through
      it, and the crosshair lands on it (a right-click reaches the tree).
- [ ] **Cut it:** the tree goes and you walk through. Note any lag between "collision gone" and "tree gone" (a few
      frames may show; say if you see it).
- [ ] **A card-key door** (Silph Co.): locked, a wall you can't pass; opened with the card key, gone.
- [ ] **Two players:** one cuts a tree, the other still sees it standing and can't pass, each from their own hidden
      game; when the second cuts it too, it goes for them.
- [ ] Flat closed art (art on walkable or water cells, such as the S.S. Anne's hull before the ship leaves) still lies
      on the ground.
- [ ] Closed art is lit like the terrain beside it: no block brighter or darker than its neighbours.
- [ ] `"shapes": "off"` in `kantobridge-client.json`: closed art shows as plain tile columns, as before V5.
- [ ] A world built before this rebuild still shows its closed art as it did (drawn by the old renderer).

### V6: texture packs and `.vox` models

Your own texture packs can now change a tile's texture, a character's sheet, how trees, props and grass are shaped,
and swap a unit for a `.vox` model ([PACKS.md](PACKS.md)). No rebuild needed. The dev client's folder is
`local\minecraft\`; its template lands in `local\minecraft\kantobridge\pack-template\blue\`.

- [ ] **Pack template** on the title screen (beside "Create Kanto world"): a toast counts the textures and models it
      wrote and the folder opens. In a world, `/kbpack template` does the same.
- [ ] **Make a test pack:** copy the template folder to `local\minecraft\resourcepacks\`, rename it, and edit
      `assets\kantobridge\kb_pack\template.json` (PACKS.md has an example). Put in:
  - a texture: one tile's `tile:` key (from `keys.json`) → a 16 × 16 PNG of your own under
    `assets\<your namespace>\textures\block\`;
  - a rule: `"class:tree": {"style": "columns"}` (trees as plain columns) or `"class:grass": {"style": "flat"}`;
  - a `.vox` tree: one of `reference\models\*.vox` edited in MagicaVoxel, named by its `cell:` key under `models`;
  - a `.vox` sprite (`sprite:` key under `models`): it only counts as applied; characters draw it from V8;
  - a character sheet: a `sprite:` key under `textures` → a PNG in the generated layout (64 × 32), or twice that.

  Enable it above "Kanto Bridge (generated)" and press F3+T. F7's **Packs** line shows `1 applied`, its entries and
  nothing skipped; `/kbpack` says the same.
- [ ] The texture shows on that tile wherever the tile is used (only in one palette if your key has `@PALETTE`).
- [ ] The rule changes every tree (or all the grass) without a rebuild: F7's Shapes line says `building`, then the
      new table.
- [ ] The `.vox` tree stands wherever that unit does, in its own colours, lit and shaded like the terrain; you still
      can't walk through it.
- [ ] The character's sheet is yours.
- [ ] **Break it on purpose** (a key with a typo, a 24 × 24 texture, a rule `"style": "round"`): F7 counts each under
      "skipped" by its code, everything else still applies, nothing crashes.
- [ ] **Remove the pack** (F3+T): everything falls back to your generated art and shapes.
- [ ] **Prepare for sharing:** `/kbpack share <your pack folder>` writes a copy to `local\minecraft\kantobridge\shared\`;
      it holds your own files and nothing from `reference\` (not even a texture you moved or renamed), no `keys.json`.
- [ ] **X5, the calibration model:** name any tree's `cell:` key with `reference\calibration.vox`
      (`"voxelsPerBlock": 8`). Stand south of it and look north: the **red post stands at the back right** (north-east)
      and the **blue strip lies along the front** (south). If it shows mirrored or turned, say which.

### V7: structures and dressing

Buildings a door leads into (houses, Pokémon Centers, Marts, gyms) are drawn from their own drawing: the facade
stands up as before, windows sit one pixel back, and the roof lies flat on the walls showing the drawn roof; their
volume (and collision) stays what it was. A building without windows keeps V6's look. Interiors' walls reach
the ceiling, and the ceiling wears the room's wall art, darker. Viridian Forest lies under the open sky. Rebuild your
world first (Minecraft closed): `tools\.venv\Scripts\python.exe tools\e2e\build_world.py --maps all --world Kanto`.

- [ ] **Houses in a town from all four sides:** the front reads like the 2D game from the usual view; windows are
      recessed; the sides and the back show the wall's art (no stretched stripes); the roof sits on the walls, with no
      gap and nothing floating. Say whether the flat roof reads better or worse than V6's sloped one.
- [ ] Walk around and into the buildings: doors work as before, you can't walk into a wall, and nothing invisible
      stops you at street level.
- [ ] A building without windows (or one whose check failed) still looks as it did in V6: plain columns.
- [ ] **Interiors in first person:** walls reach the ceiling; the ceiling wears the room's wall art, darker; no light
      leaks through anywhere.
- [ ] **Viridian Forest:** open sky above it (day and night), its trees, wild Pokémon in its grass; you go in and out
      through its gates as before.
- [ ] The build report counts the buildings: `structures_by_kind.building` and `fallback`, `shape_templates`,
      `selfcheck.templates_failed`; `notes.open_air_sky` (forests under the sky) and `notes.open_air_kept_ceiling`
      (should be absent).
- [ ] With a texture pack: a `struct:` key from `keys.json` with `{"bands": {"roofRows": 1}}` changes where that
      building's roof starts; a `struct:` model (start from `reference\models\struct_*.vox` of a new pack template)
      replaces the building wherever it stands.

### V8: characters

NPCs now stand as thick cards by default: today's card from the front, a slab two sprite pixels deep from the side and
above. Other styles are options in `local\minecraft\config\kantobridge-client.json` (dev client):
`"characters": {"style": "thick"}` (or `"flat"`, `"carved"`, `"rigged"`), and `"packs": false` to leave texture packs
out of characters. Restart the game after changing it. F7's Characters line shows how many sprites each style drew and
what fell back (and why). No rebuild needed.

- [ ] **Thick (the default):** a walking NPC, a standing NPC, an item ball, a boulder and Snorlax look as before from
      the front; from the side and above they have depth; no gaps, no flicker.
- [ ] **Carved:** NPCs are solid figures turned by their own facing (they don't turn to follow you); walking alternates
      feet; an item ball and a boulder are round. Sprites that can't be carved stay thick (F7 says why).
- [ ] **Rigged:** people are blocky dolls whose legs and arms swing while they walk; anything that isn't a person
      (items, boulders, Snorlax) falls back to carved or thick. Say whether the swing reads well.
- [ ] **Flat:** today's cards.
- [ ] **Push a boulder** (Strength) in each style: it moves cell by cell as before.
- [ ] **Surf:** the mount you ride stands in the same style (thick by default).
- [ ] **A pack's sprite model** (a `sprite:` key under `models`, PACKS.md): that character is the model, facing where
      it faces; with `"packs": false` it is back to its style.
- [ ] **X7, 64 puppets:** with the measuring tool (`.\gradlew.bat runClient -Pkb.meshstats=true`), stand where many
      NPCs are in view (a busy town; two players' worth if you can), and measure frame time (F3's graph: average and 1%
      low) with `"style": "flat"`, then `"thick"`, then `"carved"` (restart in between). Pass: thick costs under 0.3 ms
      a frame more than flat, carved under 0.5 ms with about 20 figures in view and under 1.5 ms with 64.

### V9: clean-up, and other renderers if you want them

The jar no longer carries the tile blocks' placeholder block states and models (the runtime model never read them),
and the invisible blocks break into transparent particles. Nothing to rebuild.

- [ ] A world plays as at V8: tiles, shapes, story art, characters; F3+T changes nothing for the worse.
- [ ] A world built **before V5** still shows its closed story art (a Cut tree before Cut, a locked gate). Tell me
      if you'd rather drop that fallback (PROGRESS.md, "Questions for you").
- [ ] **X6 (optional; nothing is pinned):** the same world with **Sodium** (`mc1.21.1-0.6.13-fabric` or
      `mc1.21.1-0.8.13-fabric`), then with **Iris** on top (`1.8.8+1.21.1` with Sodium 0.6, `1.8.14-beta.1+1.21.1`
      with Sodium 0.8) and a shader pack, then with **Distant Horizons** (`3.3.3-1.21.1`). For each: shapes, story
      art, puppets and their styles draw; no crash; no black or missing faces; distant terrain shows the map's
      colours (story and ledge blocks invisible). Any pin that follows is your decision and a VERSIONS.md change.

## Profile plan

### P1: Dramaless Shape's table, trees, props, signs and posts

Once, before a new world:

1. **Install the mod.** Copy the Dramaless Shape mod into `local\gen1recomp\mods\DRAMALESS_SHAPE\`: from your
   `refs\DRAMALESS_SHAPE-main\`, the files `manifest.json`, `main.lua`, `mod.card`, `LICENSE`, `README.md`,
   `CREDITS.md`, `CHANGELOG.md` and the folders `lib`, `data`, `docs` (or install it the way you usually install
   mods). Tell me if you'd rather I copy it.
2. **Switch it off.** Start `local\gen1recomp\gen1recomp.exe` without `--game` (the launcher), open its mod settings
   and switch "Dramaless Shape Voxel Mod" off; or in game, F10. It must stay off: the bridge only reads its table, and
   switched on it would run its own 3D view and hotkeys inside the hidden game.
3. Start as usual (`.\tools\launch.ps1`) and create a **new** world.

- [ ] The world's `local\minecraft\saves\<world>\kantobridge\build_report.json` shows `"profile": {"status": "ok",
      ... "enabled": false}` and `units_by_source` with `pin` > 0.
- [ ] **Trees:** Pallet Town's and Route 1's trees stand round, like Route 2's; the border trees too.
- [ ] **The town sign** is a thin standee, the **fence posts** that run north–south are thin posts, the **cuttable
      bush** is a cutout standing on grass (and after Cut, plain grass).
- [ ] **Interiors:** stools, potted plants and TVs (the profile's standees) stand as cutouts, not cubes.
- [ ] **Counters** (a Center, a Mart, a route gate) show their counter top on top and their front panel on the
      front, instead of the same tile on every face.
- [ ] **Without the mod** (rename its folder, new world): the trees still stand round where their drawing is round
      (our own fallback), and some fences come out as cutouts.

### P2: buildings from the profile's band tables

A new world (the mod installed and switched off, as for P1).

- [ ] **Houses** (Pallet Town, Viridian City) from all four sides: a flat roof on top showing the roof's drawing, its
      ends stepping down where the drawing tapers; the side walls in the house's own colours (no roof stripes on the
      sides); windows set one pixel into the wall.
- [ ] **Oak's lab, a Center, a Mart, a gym**: the same; an awning where one is drawn; the roof's front edge over the
      street where it has an eave.
- [ ] **Doors:** walk in and out of every kind of building: the doorway is open two blocks high, the door's art stands
      at its back, and the warp works as before.
- [ ] **The Pokémon Tower** (Lavender Town) stands as one tall building; the part of its drawing on Route 10 is flat
      ground you can't walk on.
- [ ] **Tall buildings** (Silph Co, 21 blocks; the Celadon department store): whole, nothing cut off at the top.
- [ ] **Interiors** where the profile models things: Red's and Blue's houses, the other houses (the trade houses, the
      Name Rater's, Mr. Fuji's, the Day Care…), Oak's lab, the Celadon Mansion. What it models looks right, stays under
      the ceiling, and you can't walk through anything the game doesn't let you.
- [ ] **A building the profile doesn't model** keeps its shape from before (four kinds, six places in all).

### P3: boxes, interiors, stairs and canopies

A new world (the mod installed and switched off, as for P1).

- [ ] **A Pokémon Center and a Mart:** counters one block; shelves standing as deep boxes with their books set in;
      tables and desks at their own heights; nothing through the ceiling; you can't walk through anything the game
      doesn't let you.
- [ ] **The houses, Oak's lab, the Celadon Mansion:** the furniture from the profile's parts (desks with things on them,
      trays, machines) whole, lids and fronts in the right colours.
- [ ] **A gym:** the same; statues and plants as before (P1).
- [ ] **Outdoors:** cliffs four blocks high (the profile's height), their faces folded like the drawing; fences low;
      ledge walls low.
- [ ] **Viridian Forest:** the big trees as one round crown over four cells, whole under the open sky.
- [ ] **Stairs** (Red's house, the route gates, Mt. Moon): a rising stair shows four steps you walk through to the
      warp, as before; a stair down stays flat.
- [ ] **Round stumps and cans** where the profile pins them: a cut top or an open mouth.
