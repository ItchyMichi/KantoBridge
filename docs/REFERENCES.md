# References

Reference material studied for Kanto Bridge on 2026-10-03. Everything here lives in `refs/`, which
is git-ignored and never committed. **Nothing has been copied from any reference into this
repository.** Before copying anything, check the licence below, then record here what was copied,
from where, and under which licence.

Conventions:
- `path:line` references are relative to the reference's folder in `refs/` unless written in full.
- **⚠ Not confirmed** marks anything inferred from code or docs but not run or checked. All of these
  are also collected in [Not confirmed](#not-confirmed) at the end.
- No ROM, save or ROM-derived cache was opened while studying. Image assets and game-data files
  were inventoried by name and size only.

## At a glance

| Reference | Folder in `refs/` | Version studied | Licence | Use in Kanto Bridge |
|---|---|---|---|---|
| [Gen1Recomp](#gen1recomp) | `gen1recomp/` | `dev` @ `e50c396` (2026-10-12), the commit release v0.3.85 was built from (the pin since 2026-10-13); first studied at `dev` @ `cb02aeb` (2026-10-02, in v0.3.49) | GPL-3.0 with Section 7 terms: a credit line is required, and the launcher files are proprietary | The hidden game, and the mod platform `gen1-bridge` runs on |
| [Gen1Recomp mod index](#gen1recomp-mod-index) | `gen1recomp-mod-index/` | `main` @ `67c40b2` | None stated; treat as all rights reserved | Finding comparable mods; the index schema |
| [Dramaless Shape](#dramaless-shape-voxel-mod) (voxel mod) | `DRAMALESS_SHAPE-main/` | v2.0.4 zip (no git) | MIT for the 2.0 line; the Dramatic Shape base is unverifiable | How a Gen1Recomp map becomes 3D; height rules |
| [Kanto in First Person](#kanto-in-first-person-ds_fp_ceiling) (`ds_fp_ceiling`) | `kanto-first-person/` | `main` @ `94d0151`, v1.57.2 (cloned 2026-10-03) | None stated; treat as all rights reserved, its art and sounds included | Ideas for first-person dressing: interiors, caves, sky, canopy, camera feel |
| [gen1coop](#gen1coop) | `gen1coop/` | `main` @ `bdd0c11` | MIT | The cleanest example of sockets inside the Gen1Recomp sandbox |
| [Gen1Online](#gen1online) | `Gen1Online/` | `master` @ `a5251ee` | MIT for code; its follower art is ROM-derived and must never be reused | A robust non-blocking socket client; version reporting |
| [overworld-spawn-mod](#overworld-spawn-mod-wilds-of-kanto) | `overworld-spawn-mod/` | `main` @ `e66e03d` | MIT for code and original assets; third-party sprite packs excluded | How to switch off random encounters |
| [pokered](#pokered) | `pokered/` | `master` @ `d2704a6` | None; it is a disassembly of the game | Gen 1 vocabulary and where things are defined; documentation only |
| [SkyCraft](#skycraft) | `SkyCraft/` | `main` @ `bfcaf17` | MIT; bundles Prism Launcher (GPL-3.0) | The closest architectural precedent; a model for the installer |
| [Easy NPC](#easy-npc) | `BOs-Easy-NPC/` | branch `1.21.1` @ `2c7fb93` (v7.14.0) | MIT for code only; images, models and other assets are explicitly excluded | Reference for skins, a dialogue screen and speech bubbles |
| [Cobblemon](#cobblemon-181) | `cobblemon/` | tag `1.8.1` @ `0bc5bb5` | MPL-2.0 | Pokémon, party/PC, battles, spawning, habitat pools |
| [KantoNPCs / CobbleKanto](#kantonpcs--cobblekanto) | not cloned | n/a | Custom "All Rights Reserved" | Feature reference only; source is not public |
| Dramatic Shape (original) | not cloned | n/a | Unknown | `github.com/DramaticShape/DramaticShapeVoxelMod` and the account return 404 |

### Licence verdicts for this project

- **Gen1Recomp:** GPL-3.0 with extra terms (`LICENSE.MD`).
  - Any "work based on this program" must credit *"Based on the Pokemon Gen 1 Recompilation Project
    by BOIS CLUB GAMES, LLC (https://github.com/bryanthaboi/gen1recomp)"* in its README, in-app
    credits, and **any launcher or front end used to start the game**.
  - The launcher files (`src/import/LauncherView.lua`, `LauncherSettings.lua`, `OnlinePanel.lua`,
    `CartLabelArt.lua`, `CartShape.lua`, `src/mods/LauncherMods.lua`, the launcher UI part of
    `RomImporter.lua`, `assets/launcher/`, `assets/labels/`) are proprietary and must never be
    copied.
  - Copied GPL code brings GPLv3 plus the credit requirement.
  - ⚠ Whether a mod that only calls the mod API counts as a "work based on" Gen1Recomp is a legal
    question and is not confirmed.
- **Cobblemon:** MPL-2.0, which is file-level copyleft. Calling its API from our own files is fine.
  A copied or modified Cobblemon file stays MPL-2.0. ⚠ The repo has no separate asset licence and
  no asset exclusion. Its models, textures and sounds depict Nintendo/Game Freak IP, so treat them
  as off-limits anyway.
- **Easy NPC:** MIT covers code only (`LICENSE.md:3-4`, *"Images, models and other assets are
  explicitly excluded"*). `README.md:120-134` adds that assets *"may not be redistributed, modified,
  or used in other projects without permission"*.
  - Excluded: `models/`, `core/Common/src/main/resources/assets/easy_npc/textures/`,
    `config-ui/Common/src/main/resources/assets/easy_npc_config_ui/textures/`, and the `logo.png`
    files.
  - ⚠ Treat the bundled `.npc.snbt` presets and model JSONs as excluded creative content too. The
    licence text doesn't name them.
- **Dramaless Shape:** MIT is credible for Stahltier's 2.0 work and for the code it says came from
  Terrarium. The original Dramatic Shape base is only *claimed* MIT, and only up to v1.6.1, and that
  claim can't be verified now. Some BattleArt-fork code is still present.
  - Fine: study it, and re-implement its documented rules ourselves with credit.
  - Needs your decision first: copying its code.
  - **Decided 2026-10-04 (the profile plan, PD1):** its `data/voxel_heights.lua` table is read at runtime from your
    installed copy of the mod (gen1-bridge exports it as `profile/dramaless`; Java reads it as data, never runs it). It
    is never copied into this project. Its `figures` and `mounted` masks are not read: they are traced from the game's
    art. The test install holds a copy of the mod's runtime files (`tools/e2e/install_profile_mod.py`), switched off.
  - **P3 (2026-10-04):** two more research passes read `lib/TileShape.lua`, `lib/ChunkMesher.lua`,
    `lib/Structures.lua` and `lib/Buildings.lua` for the class boxes, bookcases, stairs, reliefs, hull variants and the
    furniture parts path (notes in `refs/_research/Q_dramaless_classes.md` and `R_dramaless_parts.md`, with
    `file:line` references). Every rule is re-implemented in our own Java (`builder/shape/ClassBoxes`, `BigHulls`,
    `geom/Furniture`, `HullVariants`, `BigHull`); no code is copied.
- **Kanto in First Person:** no licence anywhere in the repo (its README's "Credits and licence"
  section grants none). Ideas only: never copy its code, panoramas, pictures, sounds or sprites.
  - **The caves and woods plan (2026-10-11):** its placement rules and numbers (the common fract-of-sine position hash,
    densities, sizes, salts) for the cave rock (CW1), the woods (CW2) and the forest's apron (CW3, built in vanilla
    blocks) are re-implemented in our own Java from the notes `refs/_research/KFP_CAVES_INTERIORS.md` and
    `KFP_WOODS.md`, with credit. Nothing is copied, and the mod is never installed or read at runtime (it stores no
    positions: every one is computed from a cell's coordinates).
- **gen1coop, SkyCraft:** MIT. Code can be reused with the notice. SkyCraft bundles an unmodified
  Prism Launcher under GPL-3.0.
- **Gen1Online:** MIT for code. Never reuse `assets/followers/` (converted from pokeemerald
  graphics). ⚠ The repo also commits a GTS database and a tunnel URL; these weren't inspected.
- **overworld-spawn-mod:** MIT for code and original assets. `THIRD_PARTY_NOTICES.md` excludes
  `assets/pmdcollab/` (CC BY-NC 4.0), `assets/enhanced_overworld/` (original authors' licences) and
  `assets/generated/`. Treat all of `assets/` as off-limits.
- **pokered, the mod index, KantoNPCs/CobbleKanto:** no reuse at all.
  - pokered is game content with no licence.
  - The mod index states no licence.
  - CobbleKanto's licence forbids copying or deriving from its code, models, textures, **dialogue
    files** and configs.

---

## Gen1Recomp

The `file.lua:NN` line numbers in this section were read at `dev` @ `cb02aeb` (v0.3.49); most of them
moved in v0.3.85, though the code they point at is largely unchanged (ENGINE_UPGRADE_PLAN §1.1: of the
696 citations in this repository, 474 only shifted and 49 fall on changed code). They are not re-stamped
by hand: `tools/pin_check.py` (`python3 -I tools/pin_check.py refs/gen1recomp v0.3.49 v0.3.85`) is the
way to resolve a citation against the current tag; it classes each one as same, shifted (with its new
start line) or changed.

- **Source:** `https://github.com/bryanthaboi/gen1recomp`, default branch `dev`. Release **v0.3.85**
  (2026-10-12), built from `dev` @ `e50c396` (2026-10-12), is **the pinned version** since 2026-10-13
  (see [VERSIONS.md](VERSIONS.md) and [`specs/ENGINE_UPGRADE_PLAN.md`](specs/ENGINE_UPGRADE_PLAN.md)).
  This section was first written at commit `cb02aebd01a9d3eff1ca0c6e5e5f633a4c1a89b9` (2026-10-02),
  included in release v0.3.49. Several releases go out per day, so always pin a tag.
- **Official sources** per `README.md:12`: *"This GitHub repository, the Discord, and
  https://gen1re.com are the only official sources for this project."* The same line warns that
  `gen1recomp[.]com` impersonates the project. Never download from it.
- **What it is:** a hand-written Lua port of pret's disassemblies running on **LÖVE 11.5**
  (LuaJIT). It is not an emulator and not a recompilation of SM83 code (`README.md:4, 65-70`).
  - Game data and graphics are decoded from the player's ROM at import time.
  - Game state is plain Lua tables keyed by **pokered symbol names** (`PALLET_TOWN`,
    `EVENT_FOLLOWED_OAK_INTO_LAB`, `PALLETTOWN_OAK`, …). There is no WRAM byte array.
- **Key files:**

| File | What it covers |
|---|---|
| `src/mods/Manifest.lua` | Manifest validator |
| `src/mods/Loader.lua` | Discovery, ordering, entry call, and the `mod` API object |
| `src/mods/Sandbox.lua` | The mod sandbox and the `require` gate |
| `src/mods/Hooks.lua`, `src/mods/Events.lua` | Hook and event buses |
| `src/world/WorldAPI.lua` | The supported overworld API for mods |
| `src/world/OverworldController.lua` | The live overworld |
| `src/core/SaveData.lua` | The save table and portable mode |
| `src/link/Net.lua` | The engine's own non-blocking TCP client |
| `docs/modding.md`, `CONTRIBUTING-mods.md`, `docs/modding/reference/registries.md` | Mod documentation |
| `docs/guides/portable-mode.md`, `docs/guides/running-from-source.md` | Running guides |
| `mods/example_mew_starter/`, `mods/examples/` | Example mods |

### Mod format

The authoritative validator is `src/mods/Manifest.lua:293-488`. The human-readable spec is
`docs/modding.md:24-94` and `CONTRIBUTING-mods.md`.

**`manifest.json` fields.** With api 2, a vocabulary violation is a hard load error (`:30-35, 316`).

| Field | Required | Values |
|---|---|---|
| `id` | yes | `^[%w_%-]+$` |
| `name`, `version` | yes | Non-empty strings; `version` is semver by convention |
| `entry` | yes | Relative path inside the mod; no `..` |
| `api` | no, default 1 | ≤ 2 (`Version.modApi`) |
| `profile` | no, default `content` | `content` / `overhaul` / `total_conversion` |
| `category` | no, default `OTHER` | TWEAK, BALANCE, CONTENT, QUEST, MECHANIC, GRAPHICS, LANGUAGE, AUDIO, UI, TOOL, TOTAL_CONVERSION, OTHER (GAMEPLAY is an alias of TWEAK) |
| `games` | no | e.g. `red`, `blue`, `gen1`, `all`. Absent means all Gen 1 games. |
| `game_version` | no | Semver range. **Skipped on a `0.0.0-dev` source checkout.** |
| `priority` | no, default 0 | Lower loads earlier |
| `dependencies` / `optional_dependencies` / `conflicts` | no | `"id"`, `"id@range"`, `"id@range#owner/repo"` or tables |
| `permissions` | no | `network`, `filesystem`, `engine_internals`, `steps`, `background`, `compute` |
| `experimental` | no | **Experimental mods start disabled** |
| `affects_link` | no | Defaults to true for non-`content` profiles |
| others | no | `github`, `description`, `log_url`, `options_schema`, `assets_transforms`, `required_imports`/`optional_imports`, `required_assets`/`optional_assets`, `force_enable_env`, `language` |

**Other parts of a mod:**

- **`mod.card`** is a Lua file that returns a table: `summary` (≤100 chars), `author`, `tags`,
  `differences = {changed, added, known}`, `credits = {{who, for_}}` and
  `compat = {engine, modApi}`. The loader never reads it (`CONTRIBUTING-mods.md:423-443`). Gallery
  and index submissions expect it.
- **Entry point:** `Loader:_loadMod` (`src/mods/Loader.lua:1793-1803`) runs the entry chunk with the
  `mod` object.
  - If the chunk returns a function, that function is called with `mod` too. Both
    `return function(mod) … end` (the official example) and `local mod = ...` work.
  - Entry chunks run when a **game boots**, not in the launcher.
  - A throwing entry rolls back everything that mod registered.
- **The `mod` object** (`Loader.lua:1340-1752`):

  | Member | What it does |
  |---|---|
  | `content.<registry>` | Data registries |
  | `events:on/once/emit` | Event bus |
  | `hooks:wrap(name, fn(next, …), priority)` | A wrapper that doesn't call `next` owns the result |
  | `input:tap/press/release` | Game Boy buttons |
  | `world` | `WorldAPI`: warps, flags, NPC handles, scripts, wild battles |
  | `battle`, `game`, `ui` | Battle API, the live game, UI helpers |
  | `save:get/set` | Per-mod data in the save |
  | `storage`, `options`, `commands` | Persistent storage, mod options, commands |
  | `exports` / `find(id)` | Sharing between mods |
  | `datasets:open("red")` | Read-only game data |
  | `log` | Logging |
  | `fetch` | HTTP; needs `network` |
- **Logging:** `mod.log:info/warn/error(fmt, …)` goes through `string.format`, so write `%%` for a
  literal percent sign. It **prints to stdout** prefixed with the mod id and keeps the last 200
  lines in memory. There is no log file (`Loader.lua:1632-1636`, `src/core/Logger.lua`). On Windows,
  run `lovec.exe` from a terminal to see it.
- **Load order** (`Loader.lua:1934-2167`):
  1. Discover the mods.
  2. Apply the saved enable state. Experimental mods default to off.
  3. Apply the `games` gate.
  4. Validate (`game_version`, imports).
  5. Resolve dependencies. Conflicts fail the mod that declares them.
  6. Sort: dependencies first, then cart rank, the player's saved order, `priority` ascending, and
     `id`.

  Enable state lives in `options.lua`. A missing entry counts as enabled.
- **Where mods live:** one level below `mods/` on LÖVE's read path: the save dir, the source dir,
  or the portable folder. Junctions and symlinks are followed (`conf.lua:2-5`), which is the
  `mklink /J` dev workflow. Zips aren't loaded at runtime; the launcher extracts them into
  `mods/<id>/`.
- **Validation tool:** `tools/modkit.py validate <path>` runs the real loader headlessly. It needs
  LuaJIT, which isn't installed here, so `gen1-bridge` hasn't been validated with it.

### Sandbox and the `network` permission

- **Sandbox:** each mod runs in its own environment (`src/mods/Sandbox.lua:156-185`).
  - `io`, `package`, `dofile`, `loadfile`, `debug` and most of `os` are removed.
  - `love.filesystem`, `love.system` and `love.event` are blocked.
  - `love.thread` is blocked for mods (`Sandbox.lua:105`), so all networking runs on the game thread.
- **Require gate** (`Sandbox.lua:22-89`, applied at `:239-254`): the modules `socket`, `enet`,
  `http`, `https`, `ssl`, `mime` and `ltn12` (and submodules like `socket.core`) need the `network`
  permission. Without it, `require` fails with *'<name> needs the "network" permission in
  manifest.json'*.
- **What `network` gives:** `require("socket")` returns **LÖVE's bundled LuaSocket, unwrapped**.
  - There's no host allowlist and no localhost-only rule.
  - TCP client and server, UDP and `socket.select` are all available.
  - It also unlocks `mod.fetch` and `mod.postLog`.
  - **Nobody is asked to approve it.** Permissions are only shown as a disclosure in the F10 mod
    manager ("USES THE NETWORK") and in the index UI.
- **Polling pattern**, from the engine's own client `src/link/Net.lua:197-381` and gen1coop:
  - Call `settimeout(0)` on every socket.
  - **Connect** without blocking: treat `"timeout"` as pending, and check `socket.select(nil, {s}, 0)`
    plus `getpeername()` with a time limit.
  - **Send:** flush a send buffer with `send` each frame.
  - **Receive:** read with `receive(8192)` and append the *partial* third return value too. A
    `"*l"` read loses partial lines when non-blocking.
  - **Frame** newline-delimited JSON (the engine ships `src/link/Json.lua`).
  - **Pump** it from `mod.hooks:wrap("core.update", …)` (once per frame) or `"input.step"` (once
    per 60 Hz logic step, `src/core/Game.lua:316-321`).

### Where game state and data live

| What | Where | How a mod reads/writes it |
|---|---|---|
| Player map/position/facing | Live in `ow.player.cellX/cellY/facing`, `ow.map.id`. Copied to `save.player` only when saving (`src/world/OverworldController.lua:6671-6681`). | `mod.world:current()` → `{mapId, x, y, facing}` (`src/world/WorldAPI.lua:114-120`) |
| Story/event flags | `save.flags[EVENT_*] = true` (`src/script/Flags.lua`) | `mod.world:getFlag/setFlag`. The `flag.changed` event fires only via `Flags.set/clear`; ~56 direct writes bypass it, so **diff `save.flags`**. |
| Hide/show objects | `save.objectToggles[mapId][objName]`, plus `itemsTaken`, `defeatedTrainers` (`OverworldController.lua:227-242`) | `mod.world:toggleObject` (emits `world.object_toggled`). Script `show_object`/`hide_object` emit nothing (`src/script/Commands.lua:602-646`). |
| Bag, badges | `save.inventory[ITEM] = qty`, `save.bagOrder`. Badges are inventory keys (`BOULDERBADGE`…) (`src/inventory/Bag.lua`, `Badges.lua:22-37`) | Read and write the tables, or use `Bag.add/remove` |
| Money, coins | `save.money`, `save.coins` | Read and write the fields |
| Party | `save.party` (≤6 mon tables: `species, level, exp, dvs, statExp, stats, hp, status, moves, nickname…`; `src/pokemon/Pokemon.lua:60-87`) | Read the table; `mod.world:reorderParty` |
| PC boxes | `save.boxes[1..12]` (20 each), `save.currentBox` (`src/pokemon/Boxes.lua`) | Read the tables |
| Pokédex | `save.pokedex.seen[SPECIES]`, `save.pokedex.owned[SPECIES]` | Read and write the tables |
| Maps | `Data.maps[id]`: `width/height` (32 px blocks), `blocks`, `borderBlock`, `tileset`, `warps {x,y,destMap,destWarp}`, `signs`, `objects {name, sprite, x, y, movement, text, trainerClass, item, hidden…}`, `connections` (`docs/modding/reference/registries.md:674-697`) | `mod.content.maps`, `mod.datasets:open`, `mod.world:mapOverview()` (cell-resolution collision plus warp/item markers) |
| Tiles, collision | `Data.tilesets`: `blocks`, `walkable`, `doorTiles`, `warpTiles`, `counterTiles`, `grassTile`, `waterTiles`. A cell's behaviour comes from its **bottom-left 8×8 tile** (`src/world/Map.lua:1-8, 209-272`). | `map:isWalkableCell/isGrassCell/isWaterCell/isDoorTileCell/isWarpTileCell/isCounterCell`, `warpPadOrHoleAt` |
| Ledges, cut trees, spinners, forced movement | `Data.field.ledges`, `cutTreeSwaps`, `spinners`, `forcedMovement`, `warpCarpets`, `hiddenItems`, `indoorEncounters`, `darkMaps`, `badgeGates` | Through `mod.content`. Cut blocks are per visit (`ow.cutBlocks`), not in the save. |
| NPCs at runtime | `ow.npcs` with `id = "<MAP>_obj_<n>"`, `cellX/cellY`, `facing`, `moving` (`src/world/NPC.lua:31-67`) | `mod.world:npc(map, idx)` handles (`scriptMove`, `face`, `position`, `placeAt`…). **No per-NPC movement event, so mirroring means polling.** |
| Map script state | No `wCurMapScript` equivalent. Progress is carried by flags, toggles and transient cutscene state. | Map hooks in `src/script/MapScripts.lua` |
| ROM-decoded cache | `<root>/<version>/data/generated/*.lua`, `assets/generated/**`, a `rom-cache.complete` marker (`src/core/Data.lua:8-15`). The root is the save dir, or the portable folder. | **Never read it from outside the game.** It is ROM-derived. |
| Saves | `saves/<version>/<slot>.lua` and `options.lua` in the same root | `save.writing`/`save.loaded` events, `save.write` hook |

### Text boxes, yes/no choices, naming screens

- **Text boxes:** `src/render/TextBox.lua` is a stack state with `isTextBox = true` and
  `pages = {{line…}…}`. It pops on A/B and then calls `onDone`.
  - Text is produced both by the script command `show_text` (`src/script/Commands.lua:81-169`) and
    by **direct `TextBox.new` calls**: NPC and sign text, cutscenes, trainer text and Cut.
  - **Intercept:** the `screen.pushed {state}` event fires for every push (`src/core/StateStack.lua:28-45`).
    The hook `screen.render_visible` exists so a mod can *"mirror a state elsewhere and hide only
    its main-screen render"* while the state keeps input (`StateStack.lua:67-73`).
  - Read `state.pages`, show them in Minecraft, and advance with `mod.input:tap(game, "a")`.
  - For script text only, `script.command` sees `("show_text", args)` first.
- **Yes/no:** `Commands.ask` is `show_text` with `opts.choice`. When the text finishes, it pushes
  `src/ui/ChoiceBox.lua` and the result goes to `onChoose(yes)` / `ctx.lastCheck`
  (`TextBox.lua:592-603`).
  - **Injecting the answer** (supported): tap `down` if needed, then `a`.
  - Multi-choice menus use `src/ui/Menu` (`Commands.lua:1505-1527`).
  - Full screens such as StartMenu, BagMenu, PokedexMenu, ShopMenu, PlayerPC, PartyMenu and
    NamingScreen go through the **`screens` registry**. A mod can override a screen id with its own
    factory (`src/ui/Screens.lua`), the cleanest way to put a bridge-driven proxy in place.
- **Naming:** `src/ui/NamingScreen.lua` calls `onDone(name, typed)`.
  - It is opened via `Screens.push(game, "NamingScreen", …)` for the Oak intro
    (`src/ui/OakSpeech.lua:481`) and catch nicknames (`src/battle/BattleState.lua:5524`, hook
    `catch.nickname`), and once directly (`data/scripts/story4.lua:796`).
  - **Intercept:** override the `NamingScreen` screen id, which misses the direct call, or watch
    `screen.pushed` and drive the grid with button taps.

### Scripted movement and forced movement

- **Cutscenes:** `OverworldState:scriptMove(entity, dir, tiles, onDone)` queues moves for NPCs
  **and the player** (`OverworldController.lua:5655-5690`). This is the simulated-joypad equivalent,
  and it locks input while running.
  - Cutscenes are Lua callbacks. Oak's Pallet intro is `PALLET_TOWN.onStep` in
    `data/scripts/story2.lua:87-300+`.
  - Command-list scripts use `move_player`, `move_npc`, `walk_npc`, `face` and `warp`
    (`Commands.lua:395-480`).
- **Forced movement:**

  | Mechanic | Where |
  |---|---|
  | Spinner tiles | `checkSpinner`/`runSpinnerMoves` (`:4825-4860`) |
  | Forced bike/surf | `checkForcedMovement` (`:4977-5020`) |
  | Seafoam currents | `checkSeafoamCurrent` (`:5028`) |
  | Cycling Road slope | `field.forcedMovement.slopeMaps` (`:1770-1815`) |
  | Warp pads and holes | `Map:warpPadOrHoleAt` |
  | Ledges | `checkLedgeHop` (`:1985`) |

- **Observing:** `world.stepped {mapId, x, y, tile, tod}` fires **only for non-scripted player
  steps** (`:1606-1608, 4676`) and carries no facing. To mirror cutscenes and forced paths, poll
  `ow.player` and `ow.npcs` every logic step from `input.step`.

### Random encounters

- **Trigger:** at the end of `onStepComplete` (`OverworldController.lua:4762-4812`), for grass,
  water while surfing, and indoor maps. `Encounter.roll` (`src/world/Encounter.lua:22-39`) picks
  from `Data.encounters[mapId] = {grass = {rate, slots}, water = …}`.
- **Hooks:**
  - `encounter.roll`: return `nil` to suppress the encounter, or a table to force one.
  - `encounter.species`.
  - `encounter.table` (preview).
  - `encounter.fishing`: a separate path for fishing.
  - The data route is `mod.content.encounters:patch(mapId, {grass = {rate = 0}})`.
- **How other mods do it:** overworld-spawn-mod wraps `encounter.roll` and returns `nil`
  (`refs/overworld-spawn-mod/main.lua:450-455`), keeping the unwrap handle. Fishing, static,
  scripted and trainer encounters are not covered by that hook. gen1coop and Gen1Online don't touch
  encounters.
- **Encounter tables for habitat pools:** `mod.world:effectiveEncounters(mapId, terrain)` returns
  the distribution without rolling (`WorldAPI.lua:421-459`). This is the in-game source for building
  habitat pools locally.

### Trainer battles, warps, saving

- **Trainer battles:**
  - `checkTrainerSight` calls `engageTrainer`, which emits `world.trainer_engaged`.
  - The **`trainer.before_battle`** hook gets `(game, {trainerClass, partyIndex, mapId, npcId},
    continueBattle)`. Return `true` to defer, call `continueBattle({cancel = true})` to cancel
    (`OverworldController.lua:4035-4058`).
  - The `trainer.party` hook can replace the enemy party (`BattleState.lua:917-935`).
  - Winning sets `save.defeatedTrainers[npc.id]` and the trainer's event flag, then runs rewards
    (`:4122-4137`).
  - **There is no supported way to inject a battle result.** The candidates are: defer, then cancel
    and apply the win side-effects ourselves; wrap `script.command` for `start_battle`/`rival_battle`;
    or divert `pushBattle` (internals).
  - Events: `battle.started`, `battle.ended`, `pokemon.caught`.
- **Warps:**
  - Warps emit `player.warped`, `map.exited` and `map.entered` (`via` = `warp`/`connection`/…).
  - The **`warp.destination`** hook can reroute a warp (`src/world/Warp.lua:113-122`).
  - Code can warp with `mod.world:warpTo(map, x, y, facing, {arrive = "fly"|"teleport"})`.
- **Saving:**
  - `Game:writeSave()` emits `save.writing`; the `save.write` hook can veto it
    (`src/core/Game.lua:1328-1351`).
  - Loading emits `save.loaded`.
  - ⚠ Whether a mod calling `mod.game:writeSave()` itself is supported is not confirmed.

### Running from source and portable mode

- **Running from source:** LÖVE **11.5** (`conf.lua:64`). Run `lovec <gamedir>` (it shows the
  console) or `love <gamedir>`.
  - `--game=red` skips the launcher.
  - `--developer` / `POKEPORT_DEV=1` gives the console and F5 hot reload
    (`docs/guides/launch-options.md`).
  - LÖVE is **not installed** on this machine. It isn't needed: we run the release build, whose
    exe has LÖVE fused in. The `--game`, `--slot` and `POKEPORT_DEV` options work there too, because
    the game's own code reads them (`conf.lua:8-13`, `main.lua:798`).
- **Never use `Play-Windows.bat` or `scripts/setup.ps1`.** They scan the project root for a `*.gb`
  and build `data/generated` into the source tree (`scripts/setup.ps1:1-45`). `scripts/run.ps1`
  refuses to start without that dev data, so call `lovec` directly.
- **ROM import** (done by you, never by us): the launcher verifies the ROM by SHA-1, then decodes
  it into the private cache. The ROM itself isn't copied into the cache, but portable mode can keep
  a ROM copy in `roms/`.
- **Portable mode** (`docs/guides/portable-mode.md`, `src/core/SaveData.lua:403-519`):
  - **Marker:** an empty **`portable.txt`**. Gen1Recomp checks, in order, the macOS app container,
    then **`getSourceBaseDirectory()`** (the folder *containing* the game folder or `.exe`), then
    **`getSource()`** (the game folder). The first that has the marker and is writable wins
    (`SaveData.lua:443-462`). A fused `.exe` checks the folder beside the exe.
  - **What moves into that folder:** `save.lua`, `options.lua` (including mod enable state), the
    whole ROM cache, `mods/`, `roms/`, `imports/`, `baseroms/`, `shaders/` and `prints/`. There's no
    env var or flag, only the marker, and Flatpak ignores it.
  - **Our layout** (decided 2026-10-03; see [`CLAUDE.md`](../CLAUDE.md)): the official Windows
    release (v0.3.85 since 2026-10-13; v0.3.49 before) is unzipped into `local/gen1recomp/`, with
    `portable.txt` beside its fused `gen1recomp.exe`. For a fused exe the source-base directory is
    the exe's own folder, so all Gen1Recomp data lands in `local/gen1recomp/`.
  - Never run Gen1Recomp from `refs/`.
- **Running hidden:**
  - When the window is minimised the frame cap drops to 10 fps (`main.lua:1739-1745`), and catch-up
    is clamped (`src/core/FixedStep.lua:75-87`). ⚠ From reading the code, game logic would then run
    at about **⅓ speed**; this hasn't been measured.
  - Unfocused but visible keeps full speed. A focus change resets all input, including held mod
    buttons (`Game.lua:1120-1139`).
  - `POKEPORT_BACKGROUND=1` only mutes audio on Windows; on macOS it also moves the window
    off-screen.
  - This conflicts with DESIGN.md §9 ("keeps full speed while minimized"). **Decided:** keep
    Gen1Recomp's window visible behind Minecraft and never minimise it.

---

## Gen1Recomp mod index

- **Source:** `https://github.com/bryanthaboi/gen1recomp-mod-index`, commit `67c40b2` (2026-10-02).
- **Licence:** none. There's no LICENSE file, `package.json` is private, and the README covers only
  third-party CSS. Treat it as all rights reserved, reference only.
- **Useful for:**
  - finding comparable mods: twelve entries request `network`, e.g. `tebwritescode@gen1mmo`,
    `alamops@rby_mmo` and `tebwritescode@savesync`; others are `Gamecorner_033@gen1online`,
    `YoDrehDenSwagAuf@overworld_wild_spawns` and `eduardocalafell@modern_dialog`;
  - the metadata format, if we ever list the bridge.
- **Key files:** `README.md`, `schema/mod.schema.json` (id pattern `^[A-Za-z0-9_-]+$`),
  `mods/<Author>@<id>/meta.json` (244 entries), `blocklist.json`, `scanner/`.

---

## Dramaless Shape (voxel mod)

- **Source:** `refs/DRAMALESS_SHAPE-main/` is a zip extract you supplied, so it has no `.git`.
  - It is v2.0.4 by Stahltier, `github.com/artyrambles/DRAMALESS_SHAPE` (release v2.0.4,
    2026-09-01).
  - It is a fork of the now-deleted Dramatic Shape voxel mod, drawing on BrenoBertucci's
    Terrarium.
  - Mod id `DRAMALESS_SHAPE`, api 2, permission `engine_internals`. It conflicts with
    `DRAMATIC_SHAPE`, `TERRARIUM`, `BATTLE_ART_VOXEL_FORK` and `potato_voxel`.
  - ⚠ Not confirmed byte-identical to the GitHub release.
- **Licence:** see [verdicts](#licence-verdicts-for-this-project).
  - `LICENSE:1-29` is MIT for the 2.0 line. It says the repo *"contains code derived from
    DramaticShapeVoxelMod"* and that BattleArt, Stadium extraction and the Apache-2.0 OpenXR loader
    are not distributed.
  - `README.md:87-104` and `CHANGELOG.md:132-136` claim the original was MIT up to v1.6.1. They
    contradict each other: v1.6.1 in one place, v1.6.2 in another.
  - `lib/VoxelMeshDisk.lua:27,29` still carry BATTLE_ART fork paths and signature.
- **Key files:**

| File | Purpose |
|---|---|
| `main.lua` | Entry point and all engine hooks |
| `data/voxel_heights.lua` | Hand-authored class heights (`:141-177`), per-tileset tile pins, building band tables |
| `lib/TileShape.lua` | Classifies tiles and cells |
| `lib/Structures.lua` | Measured heights |
| `lib/Buildings.lua` | Building models |
| `lib/VoxelScene.lua` | `groundAt` |
| `lib/VoxelCompanionHost.lua`, `docs/voxel-companion-api-v1.md` | Public per-cell snapshot API |

### How terrain heights are decided

1. **Tile classes:** `TileShape.forMap` (`lib/TileShape.lua:385-472`) is cached per tileset. For
   each 8×8 tile it uses the first match:
   - an authored pin in `voxel_heights.lua`;
   - the tileset's grass tile → `grass`;
   - an animated tile → `flower`;
   - a water tile → `water`;
   - a walkable tile → `ground`;
   - anything else → `wall`.
2. **Per position:** `TileShape.at` (`:478-507`) checks conditional pins first. Otherwise the
   **cell rule** applies on the 16 px walk grid: `isWaterCell` → water, `isWalkableCell` → ground.
   This works because collision is judged by a cell's bottom-left tile.
3. **Measured heights:** `Structures.buildVolume` (`lib/Structures.lua:2145-2317`) handles
   unauthored solid regions.
   - Height = drawn rows × 8 px, aware of repeating patterns, capped at **48 px**.
   - Each region takes its modal height.
   - Outdoors, up to 16 px of the top rows becomes a sloped roof.
   - Buildings are modelled from authored band tables plus measurements of the art.
4. **Class heights** in world px; a tile is 8 px, a cell 16 px, a block 32 px:

   | Class | Height |
   |---|---|
   | ground / grass | 0 |
   | water | −2 |
   | ledge (OVERWORLD tiles $0D $1D $27 $34 $36 $37) | 6 |
   | wall | 16 |
   | tree hull | 16 |
   | forest canopy | 32 |
   | cliff | 32 |
   | roof | 28 |
   | measured volumes | 8–48 |
   | furniture | 3–32 |

5. **There is no terrain elevation.** Every walkable cell is at Y = 0; hills and plateaus are solid
   volumes. `lib/MapGrids.lua` is an unwired draft of per-cell elevations covering only Pallet Town
   and Route 1.

### Cache format

- **No height cache exists on disk.** All caches are in memory, built lazily as you play: TileShape
  per tileset, Structures per map, and meshes per map.
- **The one disk cache is disabled.** `lib/VoxelMeshDisk.lua` has `available()` returning false
  (`:33-41`). It would store LZ4-compressed render triangles, not heights, at
  `mod-derived/BATTLE_ART_VOXEL_FORK/mesh-cache-v1/<map>.*.bavc`, with a `"BAVC"` header and a
  fingerprint. It's of no use to us.
- **Can our world builder use it?**
  - It can't read a cache, because there isn't one.
  - It can't call the code standalone: it needs Gen1Recomp's `src.*` modules, LÖVE, LuaJIT `ffi`
    and the ROM-derived atlas pixels.
  - The **Voxel Companion API v1** gives per-cell `{class, class height, solid, walkable, tags}`,
    but only for the active map with voxel mode on.
- **Recommendation:** re-implement the TileShape classification and height table in our own
  builder, from Gen1Recomp's decoded map data. Optionally add Structures-style measured heights.
- **Never run** `tools/building_images.py` or `tools/contact_sheets.py`. They composite images from
  the ROM-derived atlas.

---

## Kanto in First Person (`ds_fp_ceiling`)

- **Source:** `https://github.com/mrmushrooms11/kanto-first-person` @ `94d0151` (shallow clone,
  2026-10-03); manifest id `ds_fp_ceiling`, "Kanto in First Person - Interiors and Tweaks" v1.57.2,
  api 2, permissions `filesystem` and `engine_internals`.
- **Licence:** none stated. Its README and CHANGELOG carry no grant, so it is all rights reserved.
  Ideas only. Its own assets were inventoried by name and size and not opened: mp3 ambience and
  footstep sounds, backdrop panoramas, poster strips, and a bird transform that derives frames from
  the player's import.
- **What it is:** a companion **patch** to the Dramatic Shape voxel mod that finishes its
  first-person view. Everything it adds is presentational; collision, movement, scripts and saves
  are untouched.
  - **Interiors:** walls on the three sides Gen 1 never draws; ceilings with headroom; doors;
    pictures; contact shadows; lamps.
  - **Caves:** rock roofs, stalactites, pools, torches, bats.
  - **Outdoors:** a painted horizon, clouds, a night sky, birds, rain, storms, rainbows, chimney
    smoke, Lavender fog.
  - **Woods:** a forest canopy and vines; tall grass in the wind; particles; terrain continuing past
    the map edge.
  - **Feel:** a crouch-and-arc ledge hop and a stepping-through-the-doorway camera.
- **Why it's a patcher:** `README.md` ("How it works") explains that Dramatic Shape keeps its
  modules private with no exports, so it writes its renderer modules into Dramatic Shape's folder.
  Writes go through LÖVE's save directory where possible, it keeps a ledger, and it restores the
  files on removal.
- **Techniques worth re-implementing in our builder** (described in `payload_ceiling.lua:1-40`):
  - **Risers:** every wall-height cell grows side faces up to the ceiling, textured with that
    cell's own tile art repeated per course.
  - **The ceiling:** at about two cells of headroom, textured with the room's dominant wall tile,
    darkened.
  - In both cases the map's own art supplies the materials, so nothing is authored.
- Proposals that build on it: [NEXT_STAGES.md](NEXT_STAGES.md) section 3.

## gen1coop

- **Source:** `https://github.com/git-mrjoufflu/gen1coop` @ `bdd0c11` (2026-08-23).
- **Licence:** MIT (© 2026 MrJoufflu). The repo has 7 files and no game assets.
- **What it is:** overworld co-op for up to 10 instances, over LAN or a Python relay.
- **Useful for:** the smallest clean reference for TCP inside the sandbox.
- **Key files:** `manifest.json` (`permissions: ["network","engine_internals"]`), `main.lua`,
  `relay_server.py`, `PROGRESS.md`.
- **Findings:**

  | Topic | What gen1coop does |
  |---|---|
  | Server | `socket.bind("*", 51820)` with `settimeout(0)`; polls `accept()` (`main.lua:474-528`) |
  | Client | Non-blocking connect checked with `socket.select` + `getpeername`, 10 s timeout (`:560-631`). An earlier blocking connect froze the game. |
  | Polling | Pumps from the `input.step` hook (`:1353-1366`). Polling on `world.stepped` broke, because it only fires when the player moves. |
  | Wire format | Newline CSV; no handshake, version check, heartbeat or reconnect |
  | Partial reads | ⚠ Ignores LuaSocket's partial value, so a split line can be lost. Ours should buffer. |
  | Remote players | Puppets via `mod.world:spawnNpc`/`npc():scriptMove`; facing inferred from step deltas |

## Gen1Online

- **Source:** `https://github.com/gamecorner-033/Gen1Online` @ `a5251ee` (2026-08-27), mod
  `gen1online-plus` 0.5.0.
- **Licence:** MIT for code. **Never reuse** `assets/followers/` (532 PNGs converted from
  pokeemerald graphics).
- **What it is:** an MMO-style mod that now targets **Crystal**, talking to a central HTTP/JSON
  "GTS" server.
- **Useful for:**
  - a robust non-blocking HTTP keep-alive client, `main.lua:1103-1346`:
    - accumulates partial reads;
    - stall detection at 12 s;
    - retry after 0.5 s;
    - 15 s connect timeout.
  - version reporting: every request carries `modVersion`, `gameVersion` (`src.core.GameVersion.get()`)
    and `recompVersion` (`src.core.Version.engine`) (`:496-510`);
  - the engine's link modules: `src.link.Json`, `LinkBattle` with a custom net adapter, and
    `Protocol.packMon`;
  - redirecting the save by patching `SaveData.save`;
  - puppets that follow the engine's NPC pose contract, which voxel pipelines also draw.
- **Downsides:** it is heavily monkeypatched and depends on a server.

## overworld-spawn-mod ("Wilds of Kanto")

- **Source:** `https://github.com/YoDrehDenSwagAuf/overworld-spawn-mod` @ `e66e03d` (2026-08-23),
  id `overworld_wild_spawns` 2.2.0, permission `engine_internals`.
- **Licence:** see verdicts. It is ~96 MB because of third-party sprite packs.
- **Useful for:**
  - turning off encounters with the `encounter.roll` hook (`main.lua:450-455`);
  - reading `game.data.encounters[mapId]` (`lib/game_compat/gen1.lua:133-139`);
  - starting a wild battle with `world:queueScript({{"start_battle","wild",species,level}})`
    (`:143-150`);
  - mod options via `options.lua`.

## SkyCraft

- **Source:** `https://github.com/chasmlol/SkyCraft` @ `bfcaf17` (2026-10-01).
- **Licence:** MIT. It bundles an unmodified portable Prism Launcher (GPL-3.0).
- **What it is:** a Skyrim (SKSE) ↔ Minecraft (Fabric) bridge. It is the mirror image of our design:
  Minecraft runs hidden while Skyrim draws everything.
- **Link design:** Windows shared memory with a magic + version header
  (`protocol/skycraft_protocol.h:45-53`), an 8 s heartbeat timeout
  (`fabric/src/main/java/dev/skycraft/link/SkyLink.java:22-23`), and a safe state when the link is
  lost. It keeps the hidden window "focused, not iconified" with a mixin (`.../client/mixin/WindowMixin.java`).
- **Repo shape:** it already uses our layout: a shared `protocol/` and `tools/fake_skyrim.py` /
  `fake_guest.py` stand-ins.
- **Installer model for DESIGN.md §2:**
  - Ship an unmodified portable Prism with a ready-made instance pinning Minecraft and Fabric.
  - Hash-pin the mod jars (`tools/package.ps1:19-30`).
  - Prism handles Microsoft sign-in and Java.
  - Unpack to `%LOCALAPPDATA%` with a version stamp, keeping the account and world on update
    (`skse/src/Launcher.cpp:105-205`).
  - ⚠ Prism's and Microsoft's terms for bundling haven't been checked.
- **Note:** it targets MC 26.3 with the non-remap Loom plugin, so its build can't be copied for
  1.21.1.

---

## Cobblemon 1.8.1

- **Source:** official repository `https://gitlab.com/cable-mc/cobblemon`, linked as "Source Code"
  on Modrinth and in Cobblemon's own `fabric.mod.json`.
  - Tag `1.8.1`, commit `0bc5bb5caaca5622c9f1856cadd866ad85d21287` (2026-09-13).
  - The published POM's `gitCommit` is the same hash, so the source matches the released jar.
- **Licence:** MPL-2.0 (`LICENSE`, header in `HEADER`). See verdicts.
- **Layout:** `common/` (Kotlin plus Java mixins), `fabric/`, `neoforge/`.
- **Build:** Architectury Loom `1.11-SNAPSHOT`; Mojang mappings plus Parchment
  `parchment-1.21:2024.07.28`. The same Mojang names as ours, so signatures line up.
- **Fabric dependencies** (`fabric/src/main/resources/fabric.mod.json:43-48`): `fabricloader
  >=0.17.2`, `fabric-api >=0.116.6+1.21.1`, `minecraft 1.21.1`, `java 21`.
  - **Fabric Language Kotlin `1.13.6+kotlin.2.2.20` is bundled jar-in-jar**
    (`fabric/build.gradle.kts:66-77`), as are GraalJS 22.3.0, ICU4J and the MongoDB driver.
  - Players need only Fabric API, as on Modrinth.
  - fabric-permissions-api is optional.
- **Maven:** `com.cobblemon:fabric:1.8.1+1.21.1` from `https://artefacts.cobblemon.com/releases`
  (also `https://maven.impactdev.net/repository/development/`). The coordinates and repos come from
  `gradle/build-logic/src/main/kotlin/cobblemon.publish-conventions.gradle.kts`.

Paths below are relative to `refs/cobblemon/`. Abbreviations:
- `K/` = `common/src/main/kotlin/com/cobblemon/mod/common/`
- `R/` = `common/src/main/resources/`

### Calling Cobblemon from Java

| Kotlin construct | How to call it from Java |
|---|---|
| `object` | `.INSTANCE`, e.g. `Cobblemon.INSTANCE.getStorage()`, `BattleBuilder.INSTANCE.pvn(…)` |
| Companion functions without `@JvmStatic` | `.Companion`, e.g. `PokemonProperties.Companion.parse("pikachu level=5")` |
| Extension functions | Static `<File>Kt` methods, e.g. `PlayerExtensionsKt.party(player)` (`K/util/PlayerExtensions.kt:60-62`) |
| Events | `CobblemonEvents.X.subscribe(Priority.NORMAL, e -> …)` with a `Consumer`; no `Unit` needed (`K/api/reactive/Observable.kt:33-35`) |
| Other Kotlin lambdas | Return `Unit.INSTANCE` |
| Default parameters | `@JvmOverloads` only where declared (`BattleBuilder.pvp1v1/pve/pvn`, `PokemonProperties.parse/create`); otherwise pass every argument |

### APIs

- **Party and PC:**
  - `Cobblemon.storage` (`K/Cobblemon.kt:175`) has `getParty(player)` → `PlayerPartyStore` and
    `getPC(player)` → `PCStore` (`K/api/storage/PokemonStoreManager.kt:49-67`).
  - `PartyStore` has `get/set/remove/swap/heal()` (`K/api/storage/party/PartyStore.kt`).
  - Watch for changes with `PokemonStore.getAnyChangeObservable()` (`K/api/storage/PokemonStore.kt:189`).
  - **Opening Cobblemon's PC from code** (Bill's PC): `PCLinkManager.addLink(new PCLink(pc,
    uuid))`, then `new OpenPCPacket(pc).sendToPlayer(player)` (as in `K/command/PcCommand.kt:56-57`).
- **Pokémon:**
  - Create with `PokemonProperties.parse("bulbasaur level=5").create()`.
  - Look up species with `PokemonSpecies.getByName/getByPokedexNumber`.
  - **`Pokemon.uuid`** (`K/pokemon/Pokemon.kt:181`) is stable, the natural key for linking a
    Cobblemon Pokémon to its shadow copy. `persistentData` can hold extra data; call `onChange()`
    after editing it.
  - `nickname`, `level`, `heal()`, `moveSet` (`MoveSet.setMove`, `K/api/moves/MoveSet.kt:63`),
    `teachLearnableMoves`.
  - Evolution: `Evolution.forceEvolve`; for stones, `ContextEvolution.attemptEvolution`.
- **Battles** (`K/battles/BattleBuilder.kt`):
  - `pvp1v1` (l.54).
  - `pve(player, pokemonEntity)` (l.241) needs a real `PokemonEntity`.
  - `pvn(player, npcEntity)` (l.316) needs an `NPCEntity`.
  - Low level: `BattleRegistry.startBattle(format, side1, side2, canPreempt)`
    (`K/battles/BattleRegistry.kt:196-219`), with no validation.
  - Results: `PokemonBattle.winners/losers/onEndHandlers`, plus the events `BATTLE_VICTORY`,
    `BATTLE_FLED` and `BATTLE_FAINTED` (`K/api/events/CobblemonEvents.kt:125-141`).
  - Format: `BattleFormat(mod, battleType, ruleSet, gen = 9)`. ⚠ The bundled Showdown has `gen1`
    mods, but whether `mod = "gen1"` works with Cobblemon is untested.
- **Spawning:**
  - The world pool is `data/<ns>/spawn_pool_world/*.json`, read by `BestSpawner` and per-player
    spawners.
  - The `condition` fields are `biomes`, `dimensions`, `minY…`, `timeRange` and so on
    (`K/api/spawning/condition/SpawningCondition.kt:40-66`).
  - Natural spawning needs config `enableSpawning` and the gamerule `doPokemonSpawning`
    (`common/src/main/java/com/cobblemon/mod/common/mixin/ServerPlayerMixin.java:137-144`).
  - It can be restricted by:
    - pool files overriding a spawn with `"enabled": false`;
    - keeping Kanto biomes out of `#cobblemon:*` biome tags;
    - habitat blocks with `ReplaceSpawns`.
- **Habitat pools (new in 1.8.0)** (`changelogs/CHANGELOG-1.8.0.md:20-21`):
  - **Datapack path:** `data/<namespace>/habitat_pools/<name>.json` (`K/api/habitats/HabitatPools.kt:36-41`).
  - **Format** (`K/api/habitats/HabitatPool.kt:77-79`, `K/api/habitats/HabitatSpawn.kt:47-66`):

    ```json
    { "name": "…", "spawns": [ { "species": "Pidgey", "spawnablePositionType": "grounded",
      "bucket": "common", "weight": 1.0, "levelRange": "2-5", "modifiers": "",
      "phases": "1", "timeRange": "day", "minLight": 0, "maxLight": 15 } ] }
    ```

    - `species` and `spawnablePositionType` are required. The position types are `grounded`,
      `surface`, `submerged`, `seafloor`, `lavafloor` and `fishing`.
    - ⚠ The shipped pools also carry `"type": "cobblemon:natural"`, which 1.8.1 doesn't read.
    - A real example is `R/data/cobblemon/habitat_pools/abandoned_fortress.json`.
  - **Phases:** the count is the number of distinct phase numbers, and the phase advances once per
    Minecraft day. Giving every spawn phase `"1"`, or leaving `phases` out, gives **one fixed
    phase**: the "same spawns every day" that DESIGN.md wants (`HabitatPool.kt:97-103`).
  - **Placing a pool:** put a `cobblemon:habitat_block` in the world (it mimics another block and
    has full collision; `K/block/habitat/HabitatBlock.kt:103-127`). Configure it through the
    creative-op GUI or block-entity NBT (`PoolId`, `SpawningStyle`, `PhaseOrder` required,
    `ReplaceSpawns`, `RangeOfInfluence`, …; `K/util/DataKeys.kt:275-307`).
  - **Two styles:**
    - `natural` feeds the player spawner, so it **stops when `doPokemonSpawning` is off**.
    - `activated` runs its own `FixedAreaSpawner` (trigger REDSTONE/TICK, chance, max spawns).
  - ⚠ Configuring blocks purely by NBT, and activated spawning with the gamerule off, were read in
    code but not tested.
- **Pokédex:** `PokedexManager` (`K/api/pokedex/PokedexManager.kt:27`) has
  `encounter/catch/obtain`. Events: `POKEMON_SEEN`, `POKEMON_GAINED`, `POKEDEX_DATA_CHANGED_PRE/POST`
  (`K/api/events/CobblemonEvents.kt:309-321`). `R/data/cobblemon/dexes/kanto.json` exists.
- **Other events:** `POKEMON_CAPTURED`, `TRADE_EVENT_POST` (with trade evolutions),
  `EVOLUTION_COMPLETE`, `POKEMON_HEALED`, `POKEMON_NICKNAMED`, `LEVEL_UP_EVENT`, `POKEMON_FAINTED`.
- **Starter screen:** `Cobblemon.starterHandler` is replaceable (`K/Cobblemon.kt:167`), and
  `config/cobblemon/starters.json` has `allowStarterOnJoin`. Either can stop Cobblemon's own
  starter prompt so Oak's lab picks the starter.
- **Test commands:** `/givepokemon`, `/spawnpokemon`, `/healpokemon`, `/pc`, `/pokedex`,
  `/spawnnpc`, `/opendialogue`, `/checkspawn`, `/stopbattle`.

### NPCs, dialogue and NPC battles (questions a–d)

I re-checked the key lines of these answers myself against the 1.8.1 source, and checked (d)
against the Minecraft 1.21.1 and Fabric API 0.116.17 jars from the build.

**a. How does the NPC system work?** Confidence: **high**.

- **Entity:** `NPCEntity` (`K/entity/npc/NPCEntity.kt:100`) is a **final** Kotlin class,
  `AgeableMob`, server-authoritative. Its synced data covers class, resource identifier, aspects,
  battle ids, player-skin bytes, name-tag flag and hitbox (`:292-304`).
- **Configuration:** datapack **NPC classes** `data/<ns>/npcs/*.json` and **presets**
  `data/<ns>/npc_presets/*.json` (`K/api/npc/NPCClass.kt:35-61`, documented in `K/api/npc/README.md`).
  The fields include `resourceIdentifier`, `names`, `aspects`, `hitbox`, `battleConfiguration`,
  `interaction` (`dialogue`/`script`/`custom_script`/`none`), `party` (`simple`/`pool`/`script`),
  `skill`, `behaviours`, `isInvulnerable`, `isMovable`, `canDespawn` and `hideNameTag`. Example:
  `R/data/cobblemon/npcs/standard.json`.
- **Spawning:** `/spawnnpc <class>`, the world spawner (spawn detail type `npc`), or code:
  `new NPCEntity(level)` → set `npc` → `initialize(level)` → `addFreshEntity`
  (`K/command/SpawnNPCCommand.kt:78-82`).
- **Rendering:** bedrock geo models chosen by resource identifier plus aspects, or a player skin
  whose PNG bytes are synced from the server (`NPCEntity.kt:562-595`).
- **AI and interaction:** the brain is empty unless behaviours are configured. Interaction is a
  pluggable **server-side** handler (`NPCEntity.kt:625-650`).
- **Verdict:** a poor fit for our client-side puppets (server-synced, bedrock-model pipeline,
  server-side interaction), and a good fit as a **server-side battle opponent**.

**b. How does the dialogue system work, and can it be driven at runtime?** Confidence: **high**
for the behaviour; medium-high for Java ergonomics, since it wasn't compiled.

- **Data:** `data/<ns>/dialogues/*.json` with `pages` (speaker, lines, `input`), `speakers` and
  actions. The input types are `option`, `text`, `auto-continue` and `none`. Actions are MoLang. See
  `R/data/cobblemon/dialogues/npc-example.json`.
- **Model:** the server runs one `ActiveDialogue` per player (`K/api/dialogue/ActiveDialogue.kt:38`),
  started by `DialogueManager.startDialogue(player, dialogue)` (`@JvmStatic`,
  `K/api/dialogue/DialogueManager.kt:31-60`).
- **Packets:**
  - The server sends a `DialogueOpenedPacket` (DTO of resolved text and options); actions stay on
    the server.
  - The client answers with `InputToDialoguePacket`, or `EscapeDialoguePacket` to close.
- **Screen:** `DialogueScreen` (`K/client/gui/dialogue/DialogueScreen.kt:50`) is final and draws a
  196×74 box with speaker plate, portrait, option buttons and a text input.
  - You advance it by clicking the mouse; there's no keyboard navigation of options.
  - The text input is capped at **30 characters** (`.../widgets/DialogueTextInputWidget.kt:32`).
- **`/opendialogue <dialogue> <player>`** just calls `player.openDialogue` (`K/command/OpenDialogueCommand.kt:55`).
- **Opening it from code with runtime text: yes, on the server.** Build
  `new Dialogue(pages, …)` / `DialoguePage` / `DialogueOptionSetInput` / `DialogueOption` /
  `DialogueTextInput` (all public constructors), then call `DialogueManager.startDialogue`.
  `ActiveDialogue.setPage` can stream new pages.
- **Choices report back: yes, on the server.** `FunctionDialogueAction((dialogue, value) -> …)`
  takes a plain code callback (`K/api/dialogue/DialogueAction.kt:33-37`). The `value` is the chosen
  option, or the typed text for a naming page. `ActiveDialogue.completion` signals close.
- **Purely client-side: no, not without mixins.**
  - The server drops input when the player has no active dialogue
    (`K/net/serverhandling/dialogue/InputToDialogueHandler.kt:19-24`).
  - `DialogueScreen.onClose()` only sends an escape packet and waits for the server (`:286-288`).

**c. How does a battle against an NPC start?** Confidence: **high** for the path; **medium** for a
temporary NPC (read, not run); **medium-low** for the entity-less prediction.

- **The path:** interaction → MoLang `q.npc.start_battle` (`K/entity/npc/NPCServerDelegate.kt:72-117`)
  → **`BattleBuilder.pvn(player, NPCEntity …)`** (`K/battles/BattleBuilder.kt:316-399`).
  - It builds a `PlayerBattleActor`.
  - It gets the NPC's party via `getPartyForChallenge`.
  - It wraps the NPC in **`NPCBattleActor`** (`K/entity/npc/NPCBattleActor.kt:28-37`, an
    `EntityBackedBattleActor<NPCEntity>`).
  - It then calls `BattleRegistry.startBattle`.
  - The opponent must be a **live server-side `NPCEntity` with a party**.
- **What the entity is for:**
  - the send-out position and facing, and the throw animation;
  - recall, and battle-id cleanup about 3 s after the battle ends;
  - there is no distance check for NPC battles.
- **A temporary NPC just for the battle:** no code in Cobblemon does it, but the public API
  supports it:
  1. `new NPCEntity(level)`; set the class, position and an `NPCPartyStore` built from
     `PokemonProperties`.
  2. `addFreshEntity`, then `pvn`.
  3. Discard it after `BATTLE_VICTORY` plus the cleanup.
- **Visible to one player only:**
  - The NPC itself can be hidden per player (see d).
  - Its **sent-out Pokémon are ordinary `PokemonEntity`s** added to the world
    (`K/pokemon/Pokemon.kt:797-811`), so every nearby player sees them unless we add a mixin.
- **Entity-less option:** `TrainerBattleActor` (`K/battles/actor/TrainerBattleActor.kt:20-29`)
  exists, but nothing in Cobblemon uses it. ⚠ It probably stalls at the player's send-out: that
  code looks for an opposing entity-backed actor with `first {}`, which throws when there isn't one
  (`K/pokemon/Pokemon.kt:851-853`).

**d. Is there a supported way to make something visible to only one player?** Confidence:
**high**.

- **Cobblemon:** only for `NPCEntity`, and only as a per-player *hide list*.
  - `NPCEntity` overrides `broadcastToPlayer` and hides itself from a player whose persistent
    MoLang data marks that NPC `hide = 1` (`K/entity/npc/NPCEntity.kt:410-427`). The flag is set
    with `q.player.set_npc_variable(npc, 'hide', 1)` (`K/api/molang/function/PlayerMoLangFunctions.kt:315-330`).
  - Players with `seehiddennpcs` (ops, or a single-player host with cheats on) always see hidden
    NPCs.
  - It's undocumented, and there's nothing similar for `PokemonEntity`.
- **Vanilla 1.21.1** (checked in the mapped jar): `ChunkMap$TrackedEntity.updatePlayer` asks
  `Entity.broadcastToPlayer(ServerPlayer)` before pairing an entity with a player. Our own entity
  class can override it to be owner-only. Foreign or final classes (`NPCEntity`, `PokemonEntity`)
  need a mixin.
- **Fabric API 0.116.17** (checked in the jar): `EntityTrackingEvents.StartTracking`/`StopTracking`
  return `void`. They only notify and can't veto. `PlayerLookup` is a lookup helper. Fabric API has
  no visibility API.
- **Client-only entities** are inherently per player, which fits our puppet design. The server
  knows nothing about them, so interaction has to be caught client-side.

### Implications for Kanto Bridge

Updated 2026-10-03 with the user's decisions: ROM-derived data may pass through the host's server,
and other players may see a story battle's Pokémon.

- **Dialogue: our own client-side screens** (decided by the user while planning, 2026-10-03).
  Cobblemon's screen could be driven with live text from code on the server, but it fell short on
  UX:
  - **Mouse-only.** Pages and options are clicked. The screen only reacts to the inventory key and
    Escape (both close it) and to Enter in the text input
    (`K/client/gui/dialogue/DialogueScreen.kt:290-297`).
  - **Names.** The text input is capped at 30 characters.
  - **Round trip.** Every page goes client → server → client.

  Since Minecraft screens are needed for the menus anyway, one client-side UI kit covers dialogue
  too. See [PLAN.md](PLAN.md) §4.3.
- **Trainer battles (recommended): run them on the server.**
  1. Our server-side code spawns a **temporary `NPCEntity`** at the trainer's tile, built from the
     team the hidden game hands over.
  2. It hides the NPC from every other player: a small mixin making it owner-only, or Cobblemon's
     hide flags.
  3. It starts the battle with `BattleBuilder.pvn`.
  4. It reports the result from `BATTLE_VICTORY`/`BATTLE_FLED`.
  5. It discards the NPC.

  The player's own client-side puppet stays the visible trainer. Other players may see the
  opponent's sent-out Pokémon (decided), so no `PokemonEntity` mixin is needed.

  Alternatives:
  - an entity-less `TrainerBattleActor` (spike-test only);
  - our own entity-backed battle actor with our own owner-only entity;
  - persistent NPCs per trainer, which conflict with per-player story objects.

---

## Easy NPC

- **Source:** `https://github.com/MarkusBordihn/BOs-Easy-NPC`, branch `1.21.1` @ `2c7fb93`
  (2026-10-02), mod 7.14.0. Its Fabric build uses Mojang mappings, so names match ours. Built
  against Cobblemon 1.7.3.
- **Licence:** MIT for code only; assets are excluded. See verdicts. Reference only; we won't use it
  at runtime.

Paths below are relative to
`refs/BOs-Easy-NPC/core/Common/src/main/java/de/markusbordihn/easynpc/` (`EN/`).

- **Custom skins:**
  - `SkinType` (`EN/data/skin/SkinType.java:26-33`) selects the source: player name/UUID via
    Mojang's session server (`EN/utils/PlayersUtils.java:51-54, 159`), a URL download, a local
    `config/easy_npc/skin/<model>/` file, or a resource location.
    - URL downloads block private addresses and cap the size (`EN/client/texture/RemoteTextureLoader.java:60-260`).
  - Downloads are cached under `<gameDir>/easy_npc/cache/`.
  - Each texture is registered on the render thread as a `DynamicTexture`
    (`EN/client/texture/TextureRegistrationHelper.java:48-67`, `TextureRegistrationQueue.java:53-56`).
  - Humanoids use a vanilla `PlayerModel` renderer whose `getTextureLocation()` returns the selected
    skin (`EN/client/renderer/entity/standard/PlayerRenderer.java:23-82`).
- **Dialogue screen:**
  - The data is static NPC NBT: `DialogDataSet` → `DialogDataEntry` (texts, buttons, conditions) →
    `DialogButtonEntry` with an `ActionDataSet` (`EN/data/dialog/*`).
  - The server sends the whole dialog set through a container menu (`EN/menu/dialog/DialogMenuHandler.java:64-76`).
  - `DialogScreen` (`EN/client/screen/dialog/DialogScreen.java:57-330`) wraps text at 192 px, shows
    10 lines per page with page buttons and an optional typewriter effect, and lays out buttons
    with `DialogButtonLayout` presets.
  - A button sends `ExecuteDialogButtonActionMessage(npc, dialog, button)`, and the server runs the
    predefined actions.
  - **No runtime text API and no code callbacks.**
- **Also useful:**
  - **speech bubbles**: `SpeechBubbleMessage` + `EN/client/renderer/entity/SpeechBubbleRenderer.java:47-597`,
    for DESIGN.md's bubbles over players;
  - **drawing a Cobblemon model from Java without a server entity**: a client-only `PokemonEntity`
    proxy that is never added to the world
    (`refs/BOs-Easy-NPC/core/Fabric/src/main/java/de/markusbordihn/easynpc/client/renderer/entity/cobblemon/CobblemonNPCRenderer.java:93-127`).
- **Per-player visibility** only hides rendering (`isInvisibleTo`) on entities every client still
  receives. Easy NPC has no client-only entities.

---

## KantoNPCs / CobbleKanto

- **Source: not public.**
  - Modrinth lists no source URL for any of the ten CobbleKanto projects.
  - The only public repos are issue trackers (`github.com/PrimordioCobble/<Mod>-Issues`) and the
    docs repo `https://github.com/PrimordioCobble/KantoNPCs-Wiki`.
  - Nothing was cloned or decompiled.
- **Licence:** "CobbleKanto Project Custom License, All Rights Reserved"
  (`https://gist.github.com/PrimordioCobble/781664946f0ed66ca56d16eac72bbfdf`). You may use the
  compiled mods in play and modpacks with credit. It forbids extracting, copying, modifying or
  deriving from code, models, textures, dialogue files or configs. The modpack is All Rights
  Reserved.
- **What they've built** (all MC 1.21.1 Fabric, on Cobblemon 1.7.x; the modpack embeds 1.7.3):

  | Project | What it adds |
  |---|---|
  | KantoNPCs | Dialog, battle, rival-by-starter, mart, trader and move-tutor NPCs with editor screens; a money system; external JSON dialogue (`config/kantonpcs/dialogs/<locale>_arrays.json`, `start/end/post`, `answers {main, yes, no, cancel}`) |
  | KantoBlocks / KantoModels | 480+ blocks and 280+ props |
  | KantoSpawners | Spawner blocks |
  | KantoStarters | Starter stations |
  | KantoTeleports | Teleport blocks |
  | KantoItems | Key items |
  | SceneBlocks | Cut/Rock Smash/Strength/Flash, with **per-player** Cut/Rock Smash clearing |
  | CobbleKanto Core | Puzzles, gyms, legendary receivers |
- **For us:** they overlap our features by design but are architecturally different: static and
  Minecraft-side, where ours is driven by Gen1Recomp. Don't depend on them or reuse anything. Their
  per-player field-move clearing and rival-by-starter confirm players expect those mechanics.
- **Related:** the CurseForge **world** "Cobblemon Kanto – Pokemon's FireRed and LeafGreen
  recreated" by JondGames (All Rights Reserved) targets Cobblemon 1.8.1. ⚠ It reportedly needs only
  Cobblemon, which would make it a real example of Cobblemon's built-in NPCs; this is unverified.

---

## pokered

- **Source:** `https://github.com/pret/pokered` @ `d2704a63` (2026-09-22).
- **Licence:** none. There's no LICENSE file and no licence statement. It's a disassembly of the
  game: maps, text, graphics and audio are game content. **Documentation only.** Copy nothing and
  never build anything from it.
- **Useful for:** Gen1Recomp keeps pokered's symbol names, so pokered explains what the names in
  Gen1Recomp's data mean.

| Concept | Where it is defined |
|---|---|
| Event flags | `constants/event_constants.asm` (e.g. `EVENT_FOLLOWED_OAK_INTO_LAB` `:5`, `EVENT_GOT_STARTER` `:17`), stored in `wEventFlags` (`ram/wram.asm:2141`); macros in `macros/scripts/events.asm` |
| Toggleable ("missable") objects | `constants/toggle_constants.asm`, `data/maps/toggleable_objects.asm`, `engine/overworld/toggleable_objects.asm` |
| Map headers and connections | `data/maps/headers/<Map>.asm` (`map_header`, `connection`), `constants/map_constants.asm` (sizes in blocks) |
| Warps, signs, NPC objects | `data/maps/objects/<Map>.asm` (`warp_event`, `bg_event`, `object_event`); block layouts `maps/*.blk` |
| Map scripts and text | `scripts/<Map>.asm` (e.g. `scripts/PalletTown.asm:1`), `text/<Map>.asm`; per-map script index `wCurMapScript` and `wGameProgressFlags` |
| Wild encounter tables | `data/wild/grass_water.asm:1` (`WildDataPointers`), `data/wild/maps/*.asm` (Red/Blue differences in `IF DEF(_RED)` blocks, 34 files), `data/wild/probabilities.asm`; rods: `data/wild/good_rod.asm`, `super_rod.asm`, Old Rod in `engine/items/item_effects.asm:1826` |
| Trainers | `data/trainers/parties.asm`, `constants/trainer_constants.asm`, `engine/battle/read_trainer_party.asm`; trainer headers via the `trainer` macro (`macros/scripts/maps.asm:117`), engine `home/trainers.asm` |
| Collision, ledges, warps, doors | `data/tilesets/collision_tile_ids.asm` (passable tiles), `ledge_tiles.asm`, `warp_tile_ids.asm`, `warp_pad_hole_tile_ids.asm`, `door_tile_ids.asm`, `tileset_headers.asm` |
| Spinners, forced movement | Per-map arrow tables (e.g. `scripts/ViridianGym.asm:54`), `home/map_objects.asm:5`; `data/maps/force_bike_surf.asm` |
| Hidden items | `data/events/hidden_events.asm`, `hidden_item_coords.asm` |
| Text engine, yes/no, naming | `home/text.asm` (`TextCommandProcessor :315`), `macros/scripts/text.asm`; `home/yes_no.asm`, `engine/menus/text_box.asm:206`; `engine/menus/naming_screen.asm` |
| Scripted movement | `home/map_objects.asm:53` (`StartSimulatingJoypadStates`), `engine/overworld/auto_movement.asm:130` (`RLEList_ProfOakWalkToLab`), `home/pathfinding.asm` |
| Player data | `ram/wram.asm`: party `wPartyCount :1722`, Pokédex `wPokedexOwned/Seen :1751/1754`, bag `wBagItems :1759`, money `wPlayerMoney :1761` (BCD), badges `wObtainedBadges :1767`, position `wCurMap/wYCoord/wXCoord :1782-1789` |
| Save layout | `ram/sram.asm`, `engine/menus/save.asm` |

---

## Not confirmed

Collected from every section above.

1. **Gen1Recomp licensing.** This is a personal test project with no licence, so these matter only
   if it is ever published:
   - whether a mod that only uses the mod API is a "work based on" Gen1Recomp, which would bring in
     the credit-line requirement;
   - whether our installer counts as a "launcher used to start the game", which would need the
     credit shown inside it.
2. **Gen1Recomp runtime behaviour:** nothing was run.
   - `gen1-bridge` hasn't been loaded in Gen1Recomp, and `modkit validate` needs LuaJIT, which isn't
     installed.
   - Whether LÖVE's `--console` option opens a log console on the fused release exe.
   - The ~⅓ speed when minimised is a reading of the code.
   - It's unknown whether hot reload leaves old sockets or hooks behind.
   - `mod.game:writeSave()` from a mod, and yielding inside a `script.command` hook, are both
     untested.
   - The LuaSocket version in LÖVE 11.5 is assumed to be standard.
3. **Dramaless Shape:**
   - the licence of the original Dramatic Shape base, and whether Terrarium is really MIT;
   - whether the BattleArt-lineage code is covered by the MIT grant;
   - whether the zip matches the GitHub v2.0.4 release byte for byte.
4. **Cobblemon:**
   - the Java ergonomics of the dialogue classes (not compiled);
   - a temporary-NPC battle end to end;
   - the entity-less `TrainerBattleActor` stall;
   - when vanilla re-checks `broadcastToPlayer` after a hide flag changes;
   - habitat blocks configured purely by NBT;
   - activated habitat spawning with `doPokemonSpawning` off;
   - the meaning of `"type": "cobblemon:natural"`;
   - `BattleFormat(mod = "gen1")`;
   - asset licensing beyond MPL-2.0.
5. **Community refs:**
   - Gen1Online's server-side version check (the server isn't in the repo);
   - the contents of its committed GTS database;
   - which hook covers fishing in overworld-spawn-mod;
   - Prism/Microsoft terms for bundling the launcher;
   - the "Cobblemon Kanto" world's dependency list;
   - all KantoNPCs internals.
6. **Easy NPC:** whether `.npc.snbt` presets count as excluded assets. The wiki submodule wasn't
   fetched.
