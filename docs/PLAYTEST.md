# Kanto Bridge: play-test checklist

Manual checks for the user, milestone by milestone, in the order to run them. The automated checks
(`tools/check.ps1`) cover everything that can run without a person watching; these steps cover what
only you can see. Tick items off here, or tell the next run what failed (PROGRESS.md lists the open
ones).

**Before any session:**

- Start Gen1Recomp from PowerShell inside `local\gen1recomp\`:
  `.\gen1recomp.exe --console --game=blue`.
- Keep its window visible behind Minecraft; never minimise it.
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
port, 27151). F7 can be rebound in Controls.

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
