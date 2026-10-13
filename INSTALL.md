# Installing and playing Kanto Bridge

Kanto Bridge plays Pokémon Red/Blue's story inside Minecraft: the real game runs hidden in Gen1Recomp and decides the
story, while Minecraft with Cobblemon draws the world and runs every Pokémon and battle. See [`README.md`](README.md)
for the idea and [`CREDITS.md`](CREDITS.md) for what it's built on.

**Status: a development setup.** Everything below runs from a clone of this repository, on Windows. A one-click
installer is designed ([`docs/INSTALLER.md`](docs/INSTALLER.md)) but not built yet, and the in-game play-test is still
in progress ([`docs/PLAYTEST.md`](docs/PLAYTEST.md)), so expect rough edges.

**Never share anything taken from your game:** not your ROM, not Gen1Recomp's saves or import cache, not the exports,
texture packs or worlds Kanto Bridge builds from it. Kanto Bridge itself never reads or copies your ROM; Gen1Recomp's own
importer does that.

## What you need

- **Windows 10 or 11, 64-bit.** The scripts are PowerShell; Gen1Recomp's pinned build is the Windows one.
- **Git.**
- **JDK 21**, e.g. `winget install EclipseAdoptium.Temurin.21.JDK`. That installer may change your machine's default
  Java; Kanto Bridge only needs it for its own builds.
- **Python 3.12** (64-bit), only for the tests and the headless world build.
- **Minecraft: Java Edition.** The development setup downloads Minecraft 1.21.1, Fabric Loader 0.19.5, Fabric API
  0.116.17 and Cobblemon 1.8.1 through Gradle by itself ([`docs/VERSIONS.md`](docs/VERSIONS.md)).
- **Your own Pokémon Red or Blue ROM**, legally obtained.
- **Gen1Recomp v0.3.85**, the Windows release zip, **only** from
  https://github.com/bryanthaboi/gen1recomp/releases/tag/v0.3.85. `gen1recomp[.]com` is an impersonation site: never
  download anything from it.

## 1. Get the code

```powershell
git clone https://github.com/ItchyMichi/KantoBridge.git
cd KantoBridge
```

The folders `local\` (your Gen1Recomp, Minecraft run folder and everything built from your game) and `refs\`
(reference projects, for development) are yours to create; git ignores both.

## 2. Build the Minecraft mod

In PowerShell, from the repository folder (use your own JDK 21 path):

```powershell
cd fabric-bridge
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.<your version>-hotspot'
.\gradlew.bat build
cd ..
```

The first build downloads Minecraft, Fabric and Cobblemon, and runs the mod's tests, so it takes a few minutes. The mod
lands in `fabric-bridge\build\libs\kantobridge-0.1.0.jar`; the development client below uses the project directly.
(Git Bash: `JAVA_HOME="<path to JDK 21>" ./gradlew build`.)

## 3. Set up Gen1Recomp, the hidden game

1. **Download** `gen1recomp-0.3.85-windows.zip` from the release above and check it. Its SHA-256 must be
   `20cff4a16cf7235d224fe6dc32c0490f482a9d768d29a194bae5fff93c82c53a`:

   ```powershell
   Get-FileHash .\gen1recomp-0.3.85-windows.zip -Algorithm SHA256
   ```

   (or `.\tools\installer\install.ps1 -Verify <path to the zip> -Id gen1recomp`).
2. **Unzip** the files inside the zip's `gen1recomp-win64\` folder into `local\gen1recomp\`, so that
   `local\gen1recomp\gen1recomp.exe` exists.
3. **Make it portable:** create an empty file `local\gen1recomp\portable.txt`. Gen1Recomp then keeps its saves,
   settings, ROM import and mods in that folder.
4. **Install the bridge mod** by linking it, in a **Command Prompt** (`cmd`) from the repository folder:

   ```bat
   mkdir local\gen1recomp\mods
   mklink /J local\gen1recomp\mods\gen1-bridge gen1-bridge
   ```

   (A junction keeps it up to date with the repository. Remove it only with `rmdir`, never by deleting through it.)
5. **Import your ROM:** start `local\gen1recomp\gen1recomp.exe` on its own. Its launcher opens; import your own Red or
   Blue ROM there (a zipped or 7z ROM can be imported as it is). An import made by an older Gen1Recomp release is
   stale and must be redone: the launcher asks to re-import.
6. **Switch the Union Room off**, in the same launcher: **Launcher Options → Union Room → OFF**, then close Gen1Recomp
   and start it again once (the setting applies from the next start). Every Kanto Bridge install runs with it off: it
   is on by default and adds a floor to every Pokémon Center that the Minecraft world doesn't have. While it is on,
   the bridge refuses to export and Minecraft shows a notice saying what to switch off.

**Or let the setup tool do steps 1–6:** put your ROM file(s) into `local\rom-inbox\` and run
`.\tools\setup_games.ps1` (`-DryRun` first shows the plan). It downloads and checks the pinned release, makes one
portable install per **world space** (`local\gen1recomp` for Red/Blue/Yellow, `local\gen1recomp-hoenn` for
Ruby/Sapphire/Emerald, `-johto`, `-kanto_frlg`), runs Gen1Recomp's own importer on each file, switches the Union Room
off, and writes `local\launch.json` and the client's `links` (below). It never reads or copies your ROM; you clear the
inbox afterwards. Games without a bridge adapter yet (everything but Red and Blue today) are imported and listed, but
Minecraft can't follow them until their adapter exists ([`docs/specs/MULTI_GAME_PLAN.md`](docs/specs/MULTI_GAME_PLAN.md)).

Upgrading from the previous pin (v0.3.49, until 2026-10-13): set v0.3.85 up in a fresh folder the same way and import your ROM
again (the release changed its import cache for every game); copy your saves (`save.lua`, `options.lua`, the slot
files and `mod_cache\`) from the old folder before the first start, never the ROM cache, and keep the old folder until
you have played on the new one.

Gen1Recomp's window has to stay open while you play (it plays the sound, too), but it can sit behind Minecraft or be
minimised: it keeps full speed.

## 4. Optional: Dramaless Shape's shape profile

With [Dramaless Shape](https://github.com/artyrambles/DRAMALESS_SHAPE) (v2.0.4, MIT) installed, Kanto Bridge builds your
world with its hand-made shapes: round trees, thin signs, buildings with real roofs, furniture, stairs and big trees.
Without it, Kanto Bridge's own rules apply.

1. Download its release and copy these into `local\gen1recomp\mods\DRAMALESS_SHAPE\`: `manifest.json`, `main.lua`,
   `mod.card`, `LICENSE`, `README.md`, `CREDITS.md`, `CHANGELOG.md`, and the folders `lib`, `data` and `docs`.
2. **Switch it off:** start `gen1recomp.exe` on its own, open the launcher's mod settings, and switch "Dramaless Shape
   Voxel Mod" off (or press F10 in game). It must stay off: Kanto Bridge only reads its table, and switched on it would
   draw its own 3D view inside the hidden game.
3. Build a **new** world (below); an existing world keeps the shapes it was built with.

## 5. Play

1. **Start everything:** from the repository folder,

   ```powershell
   .\tools\launch.ps1            # every install with an adapter (today: Blue, or Red: -Game red)
   .\tools\launch.ps1 -Games blue,emerald    # or -Regions kanto,hoenn: one hidden game per install
   ```

   It starts one Gen1Recomp per install with the bridge (the game it finds imported there, by folder names), waits
   until each bridge listens (27151, 27152, ...), then starts the development Minecraft client with the links. It finds
   JDK 21 by itself (or pass `-JavaHome <path>`). `-DryRun` shows what it would do; `-Watch` restarts a Gen1Recomp
   that closes. (By hand: start `gen1recomp.exe --game=blue` from its folder, then in `fabric-bridge` run
   `.\gradlew.bat runClient -Pkb.links=blue:27151` with `JAVA_HOME` set.)
2. **Build your world once:** on Minecraft's title screen, press **Create Kanto world**. Gen1Recomp must be at its
   title screen or in game. It exports your game's art (about 15 seconds), builds every map of Kanto (about a minute),
   and lets you in, in Pallet Town.
3. **Pick your save:** Gen1Recomp's title shows on screen; choose NEW GAME or CONTINUE. Then walk, talk and play.

Later sessions: run `.\tools\launch.ps1`, open the world from Singleplayer, and pick CONTINUE.

## Controls

| Key | Does |
|---|---|
| WASD or the arrow keys | Walk (no jumping or sprinting: Gen 1 rules) |
| `Z`, or right-click what you look at | A: talk, read signs, confirm |
| `X` | B: back |
| `Enter` | START: the start menu (Pokémon, items, save, …) |
| `Backspace` | SELECT |
| `Esc` | Minecraft's pause menu |
| `F7` | The bridge's debug overlay |
| Sneak | Brake on Cycling Road |

All are rebindable: Options → Controls → Key Binds → Kanto Bridge. In screens drawn from the hidden game (party,
summary, Pokédex, title, Safari battles), every key is a button tap. In Gen1Recomp's window, F10 opens its mod manager.

**Saving:** SAVE in the start menu, or a Pokémon Center's nurse, saves both Gen1Recomp's game and the Minecraft side.
**If Gen1Recomp closes,** Minecraft pauses; start it again (or let `-Watch` do it) and pick CONTINUE.

## Settings

The client's settings are in `local\minecraft\config\kantobridge-client.json` (in any other Minecraft folder:
`config\kantobridge-client.json`). Restart Minecraft after changing it.

```json
{
  "links": [{"game": "blue", "port": 27151}, {"game": "emerald", "port": 27152}],
  "shapes": "on",
  "characters": { "style": "thick", "packs": true }
}
```

- `links`: one entry per hidden game, its id and the port its gen1-bridge listens on (Gen1Recomp's BRIDGE PORT, F10
  in its mod manager; `tools\setup_games.ps1` writes them). The older `port` key is one link whose game Minecraft
  learns on connecting. `-Dkantobridge.links=blue:27151,emerald:27152` (what `launch.ps1` passes as `-Pkb.links`)
  overrides the file for dev runs; `-Dkantobridge.port` still works for one link.
- `shapes`: `"off"` draws plain cubes instead of the shaped world (a fallback).
- `characters.style`: how people are drawn: `flat`, `thick` (the default), `carved` or `rigged`.
- `characters.packs`: `false` keeps texture packs away from characters.

Texture packs that change Kanto's look are ordinary resource packs with a small manifest: see
[`docs/PACKS.md`](docs/PACKS.md).

## Two players on one PC

Each player needs their own Gen1Recomp install with their own ROM:

1. Set up a second install the same way in `local\gen1recomp-p2\` (its own `portable.txt`, mod link, ROM import and
   Union Room off), and in its mod manager (F10) set BRIDGE PORT to **27161** (player 2's ports are 10 above player
   1's, leaving room for player 1's other games; `launch.json`'s `ports.player2`).
2. Build the world once as above (**Create Kanto world**; it lands in `local\minecraft\saves\Kanto`), then close
   Minecraft.
3. Create `local\minecraft\eula.txt` containing `eula=true` (you accept Minecraft's EULA), and
   `local\minecraft\server.properties` pointing the development server at that world:

   ```properties
   level-name=saves/Kanto
   level-type=kantobridge\:kanto
   online-mode=false
   difficulty=peaceful
   gamemode=adventure
   spawn-protection=0
   max-tick-time=-1
   generate-structures=false
   ```

4. Run `.\tools\launch.ps1 -Players 2`: it starts a development server and two clients; join `localhost` from each
   (Multiplayer → Direct Connection).

Playing across the internet with real Minecraft accounts is designed but not tested yet.

## Troubleshooting

| Problem | Try |
|---|---|
| `launch.ps1`: "no JDK 21 found" | Pass `-JavaHome <path to JDK 21>`, or put `{"javaHome": "<path>"}` in `local\launch.json` |
| "didn't open its bridge port" | Is the `gen1-bridge` link in `local\gen1recomp\mods\`? Mods start only once a game boots |
| "took port …, but its Minecraft connects to …" | Free the port, or set BRIDGE PORT (F10) and the client's `port` to the same number |
| Minecraft says the versions don't match | Use the same commit of this repository for both halves |
| The world is plain cubes, or a new feature doesn't show | Build a new world: a world keeps the shapes it was built with |
| Walking rubber-bands a little | Known: the hidden game walks at Gen 1 speed; a speed-up is planned |
| Gen1Recomp's log | Start it with `--console` |
| Gen1Recomp crashed | Its crash log is `%APPDATA%\pokemon-love2d\lua-error.log`. It can contain game text: don't post it publicly |

More: [`docs/PLAYTEST.md`](docs/PLAYTEST.md) (what to check), [`docs/PROGRESS.md`](docs/PROGRESS.md) (known issues).

## Tests (for contributors)

1. `.\tools\setup.ps1` creates the project's Python venv in `tools\.venv` (Python 3.12, wheels only).
2. `.\tools\check.ps1` runs every automated test: pytest, then the Gradle build with its JUnit tests and GameTests.
   Set `KB_JAVA_HOME` to your JDK 21 first. `-NoGradle` skips Gradle; `-E2E` adds the end-to-end tests, which need a
   separate Gen1Recomp test install in `local\gen1recomp-e2e\` (or `local\gen1recomp-e2e-0.3.85\`, which the runner
   prefers when it exists) set up like the one above, Union Room off included.

How the project is organised and worked on: [`CLAUDE.md`](CLAUDE.md), [`tools/README.md`](tools/README.md),
[`docs/ROADMAP.md`](docs/ROADMAP.md).
