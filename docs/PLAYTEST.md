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
