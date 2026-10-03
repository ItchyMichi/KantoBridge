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
