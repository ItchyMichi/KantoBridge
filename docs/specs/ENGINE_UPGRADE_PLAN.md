# Gen1Recomp upgrade plan: v0.3.49 → v0.3.85

Written 2026-10-13 for the user's request: *inspect the current code, check what needs updating for the Gen1Recomp
version that supports Hoenn, make a plan to update it, with test runs that prove the system still works.* Every fact
below was read from the official repository (<https://github.com/bryanthaboi/gen1recomp>, cloned read-only into the
git-ignored `refs/gen1recomp/`, never run) at the tags `v0.3.49` (the pin) and `v0.3.85` (the latest release,
2026-10-12), and from this repository at `c82fffb`. `file:line` references name the tag they belong to.

It can run **before** [`MULTI_GAME_PLAN.md`](MULTI_GAME_PLAN.md) (recommended: every spike and test of that plan then
runs on the engine Hoenn needs, and the ROMs are re-imported once, not twice) or after it, at the latest before MG7.
Its decision D11 ("the pin stays v0.3.49; revisit at MG7") is answered by this plan once the user says go (they did,
on 2026-10-13, with the defaults: §0).

## 0. Execute

1. **Read order:** §1 (what the inspection found), §2 (why this version), §4 (the phases), §5 (the gates), then the
   report `tools/pin_check.py` writes (§1.1), and MULTI_GAME_PLAN §0's ground rules, which apply unchanged.
2. **Ground rules.** CLAUDE.md's hard rules: nothing ROM-derived is opened, copied or committed; **the agent never
   imports a ROM**, the user does (on the pod too: §4.1); logs carry types, ids, counts and hashes only; the clone in
   `refs/gen1recomp` is read with `git` and `grep` and never run; the pinned versions change only after the user's
   OK (decision U1 below), and `docs/VERSIONS.md` records where each value came from.
3. **Who does what.** UP0 and UP5 need the user (downloads, hashes, imports, the Windows playtest). UP1–UP4 and UP6 are
   the agent's, on this server (no engine), the pod (the Linux build) and the user's PC (the Windows build).
4. **Decisions for the user** (defaults in bold proceed if the user says "go" without changing them):

| # | Question | Options | Default |
|---|---|---|---|
| U1 | Target version | **v0.3.85** (the audit below is against it); or the newest release on the day the work starts (re-run `tools/pin_check.py` against that tag and skim its notes first: about 30 minutes) | **v0.3.85** |
| U2 | Who re-imports on the pod | **the user**, with the ROM placed in the volume's `rom-inbox/` and the one command in §4.1 run through `!`; or the user tells the agent to run that exact command once | **the user** |
| U3 | The Union Room | **OFF in every KantoBridge install**, pinned by gen1-bridge and refused at boot when it is on (§1.3 B2); or leave the engine's default ON and rebuild Kanto with the 2F and stairs (not recommended: the export never sees the patched maps) | **OFF** |
| U4 | Upgrade style on the user's PC | **fresh folders** (`local/gen1recomp-e2e` and `local/gen1recomp` re-created from the new zip, imports redone there, the old folders kept as `-0.3.49` until G5 passes); or in place (unzip over the old folder; data stays; the launcher asks to re-import) | **fresh folders** |
| U5 | The fallback | **keep the v0.3.49 folders and the pod's old AppImage until G6 passes**, then the user deletes them; or delete at once | **keep** |
| U6 | JUnit literals | **update** the `"0.3.49"` strings in `fabric-bridge/src/test` too (they are self-consistent fakes, so they pass either way); or leave them | **update** |

**Decided 2026-10-13:** the user chose the defaults (U1–U6); U2 was carried out through the pod's upload page (Blue,
Crystal, Emerald imported into `local/gen1recomp-data-0.3.85`). The gates are recorded in PROGRESS.md ("Engine
upgrade").

## 1. What the inspection found

### 1.1 Method and numbers

`tools/pin_check.py` (new, committed with this plan) scans every `file:line` citation of an engine source in
`gen1-bridge/`, `tools/e2e/`, `tools/lua/`, `tools/fake_gen1/` and `fabric-bridge/src/`, resolves each to a file of the
old tag, and classifies it against `git diff -U0 <old> <new>`:

| Class | Meaning | Count |
|---|---|---|
| same | identical lines, same numbers | 172 |
| shifted | the code is unchanged but moved: the comment's line numbers are stale | 474 |
| changed | the cited lines themselves were edited: re-check the behaviour | 49 |
| missing | the file is gone | 0 |
| unresolved | not an engine file (`src/x.lua` in a filter test) | 1 |

696 citations in 810 scanned files; 288 citing lines inside `gen1-bridge/` need their numbers re-stamped. The 49 changed
citations fall on 14 engine files and are listed with a disposition in Appendix B. Per cited file (changed / total):
`Game.lua` 15/71, `Bag.lua` 7/8, `main.lua` 5/32, `OverworldController.lua` 3/264, `TextBox.lua` 3/17,
`BattleState.lua` 3/15, `SaveData.lua` 3/10, `BagMenu.lua` 3/10, `Commands.lua` 2/38, and one each in `Music.lua`,
`Input.lua`, `Version.lua`, `Assets.lua`, `Logger.lua`. Upstream: 36 releases and 252 commits between the tags.

### 1.2 What held

- **The mod API:** `modApi` 2 (`src/core/Version.lua:19`), `saveFormat` 5, the manifest validator `src/mods/Manifest.lua`
  unchanged, our `game_version` range `>=0.3.49 <0.4.0` admits v0.3.85, `rom-cache.complete` is still the marker
  (`CacheContract.lua:36`). Every hook gen1-bridge wraps exists with the same number of call sites: `world.talk`,
  `ui.pc.items`, `trainer.party`, `trainer.before_battle`, `script.command`, `render.output`, `render.output_enabled`,
  `movement.collision`, `item.use`, `input.step`, `encounter.species`, `encounter.roll`, `encounter.fishing`; so do the
  14 events it listens to and the `mod.save/events/input/hooks/world/ui/options/storage/log` objects (`Loader.lua`
  changed only in a fast path for engine-internal requires, `:247-300`; `Sandbox.lua` gained `evalData`, loader side).
- **Unchanged engine modules** the bridge reads: `WorldAPI`, `Map`, `ScriptRunner`, `StateStack`, `HotReload`,
  `DatasetViews`, `ImportAccess`, `Runtime`, `Hooks`, `Events`, `Warp`, `Collision`, `FieldDefaults`, `Badges`,
  `Pokemon`, `Party`, `Boxes`, `Stats`, `Growth`, `MapScripts`, `Flags`, `Transition`, `Screens`, `Strings`, `ModUI`,
  `PrizeCounter`, `QuantityBox`, `ChoiceBox`, `StartMenu`, `Tilt`, `Zoom`, `Pipelines`, `FixedStep`, `PadHints`,
  `MapLoader`, `MoveLearnMenu`, `PokedexMenu` (0 lines changed); `Menu`, `ListMenu`, `NPC`, `TownMap`, `TitleState`,
  `Player` changed in lines we don't cite.
- **Launch and test plumbing:** `--game=`, `POKEPORT_VERSION`, `POKEPORT_CONSOLE`, `POKEPORT_BACKGROUND`,
  `POKEPORT_DRIVER` (exit 0 / 1 on a driver error / 3 on a Lua error, `main.lua:86-89, :1102-1103`),
  `POKEPORT_IMPORT_ONLY`, `POKEPORT_IMPORT_ROM`, `POKEPORT_FORCE_IMPORT`, `POKEPORT_IDENTITY` all kept; the save-slot
  functions keep their signatures (`SaveData.lua:1699-1896, :2063`), plus a new `duplicateSlot` (`:1997`).
- **The export's data source:** `mod.datasets:open(version)` still decodes the version's cache files
  (`DatasetViews.lua:427`, `data/generated/*.lua`), so the export reads the ROM's own tables, never the live game's.

### 1.3 What broke or moved, ranked by impact

**B1. Every game's import cache generation changed: the user re-imports every ROM.** `CacheContract.lua:12-36`:
Gen 1 `rom-cache-v12-gen1` → `v13`, Yellow `yellow2` → `yellow5`, Gold/Silver `v13` → `v15`, Crystal `crystal5` →
`crystal7`, FireRed `v21` → `v25`, LeafGreen `v6` → `v10`, Emerald `v3` → `v5`; Gen 1 gains the generated files
`events.lua` and `diploma.lua`. A stale cache is "not imported": the launcher shows an *update required* re-import
prompt with an *Always re-import automatically* option (`RomImporter.lua:2649-2670`), and a scripted boot (our e2e
runs, `main.lua:955-998`) stops at the importer unless `POKEPORT_IMPORT_ROM` names the file, so a stale test install
shows as a `kb_ready` timeout, not as an error. The marker file now carries the ROM's SHA-1, so our tools keep
checking names only, never its contents.

**B2. The Union Room is ON by default and rewrites Gen 1's Pokémon Centers at data load.** Since v0.3.61 ("union for
everyone"; parity fixes in v0.3.84), `src/core/Data.lua:185` calls `UnionCenters.seed` for every Gen 1 boot: with the
setting on, `UnionCenters.apply` (`src/world/gen1/UnionCenters.lua:297-390`) replaces 12 maps with patched copies
(`VIRIDIAN_POKECENTER`, `PEWTER_POKECENTER`, `CERULEAN_POKECENTER`, `MT_MOON_POKECENTER`, `ROCK_TUNNEL_POKECENTER`,
`VERMILION_POKECENTER`, `CELADON_POKECENTER`, `LAVENDER_POKECENTER`, `FUCHSIA_POKECENTER`, `CINNABAR_POKECENTER`,
`SAFFRON_POKECENTER`, `INDIGO_PLATEAU_LOBBY`: stairs blocks, the link receptionist removed from 1F, a warp added),
adds the maps `POKECENTER_2F` and the union room, patches the `POKECENTER` tileset and composes a new tileset image,
and `UnionSafety` relocates a save made on an added cell (`Game.lua:1379`, `:1554`). The export never sees any of it
(§1.2), so the hidden game and the Minecraft world would disagree at every Center: walkable stairs where Minecraft has
a wall, a vanished NPC, `story.unknown_block` on every visit, and a 2F the world doesn't have. The setting is one key,
`unionRoom = false` in `options.lua` (`src/online/union/Setting.lua`; absent means ON; it is not a launcher key, so
the engine's own options writes keep it). With the setting off the engine's own test asserts the vanilla data
(`tests/drivers/union_gen1_off.lua`: no added maps, the 1F desk blocks, the receptionist back). **Decision U3: OFF.**

**B3. The text box's rules changed** (`src/render/TextBox.lua`): `visibleText` now returns the lines in `shownSource`
(`:406-412`), with a blank continuation row for Gen 1's fixed two rows (`beginLine`, `:389-401`); `arrowVisible`
changed its precedence (`:431-440`: `waitButton` hides the arrow unless an auto or stay prompt is due, on every
generation, not only Gold); `blankArrowCell` (`:449-464`), `stay.press` (`:541-548`) and `noLetterDelay` (`:687-689`)
are new. gen1-bridge re-implements the old rules in `kb_engine.lua` `visibleLines` (`:771`), `textReady` (`:786`) and
`textArrow` (`:810`): the dialogue Minecraft shows and the moment it accepts A come from these.

**B4. The bag has a new model** (`src/inventory/Bag.lua`): totals in `save.inventory` as before, plus a side table
`save.bagStacks` for stacks split at 99 (`:58-130`), `Bag.rows` (`:369`), `Bag.remove(save, id, qty, data, slot)`
(`:464`, was `(save, id, qty)`), `BagMenu` rows carrying `slot` and `stack` and consuming by slot (`BagMenu.lua:22-48`).
gen1-bridge's `bagRemove` and the ESCAPE ROPE use pass three arguments (`kb_engine.lua:2989`, `:3047`, `:4318`): they
still remove the right total, but without `data` the engine falls back to the module table to decide whether an item
splits. The bag observer reads totals (unchanged). The lupa fakes model the old API.

**B5. Battle music rules changed** (`src/battle/BattleState.lua`): the victory jingle is the gym leader's when the
battle is `final` **or** `isGymLeader`, and a `gym`-kind battle that isn't a leader's gets the trainer jingle
(`:5900-5911`); after a **won** final battle the map's song is no longer restored (`:5963-5970`). gen1-bridge mirrors
the old mapping in `playVictoryMusic` (`kb_engine.lua:1645-1648`) and always restores the map song (`:2052`).

**B6. The indoor wild-encounter rule** moved into `OverworldState.rollsIndoorEncounters(def, indoor)`
(`OverworldController.lua:4782-4788`): a map with the new `outdoor` field (`Schemas.lua:1938`; CONTRIBUTING-mods.md
§4c) never rolls off grass, and Fly and Teleport work on it. gen1-bridge's suppression wrapper is unaffected (it
answers `encounter.roll`); `fake_wild.lua` models the old test; our route maps (`kb_routes.lua:291`) may declare
`outdoor = true`.

**B7. Warps:** `Commands.warp(ctx, mapId, x, y, facing, door)` gained a door flag (`Commands.lua:395-401`) and
`startWarpTo` now infers a door warp when the cell the player stands on has a warp to the destination
(`OverworldController.lua:5571-5590`), so the door walk-out can play for warps that used to be plain. Our route warps
(`queueScript {"warp", …}`) keep working; the e2e walk test's warp kinds are the check.

**B8. Save and options plumbing:** `Game:writeSave` calls `SaveData.save` through `UnionSafety.write` (identity with
the setting off); live options are written with `saveSessionOptions`, which re-reads the launcher's own keys from disk
(`SaveData.lua:875-905`, `:2776`, `:2796`); options writes are deferred and flushed on focus loss (`Game.lua:432-437`,
`:1157-1169`); `options.lua` is written through a `.tmp` file; `restoreSave` keeps the title song fading into the map's
(`Game.lua:1575-1590`). gen1-bridge's pins and its save flow are unaffected in substance; comments and the fakes change.

**B9. The process model:** on desktop, returning to the launcher now **restarts the process** with a handoff file
(`main.lua:625-645`, `HostShell.restart`), a driver coroutine is resumed with `(Game, driverHost)` (`:1134-1136`),
`POKEPORT_DRIVER_PACED=1` keeps the engine's own frame pacing under a driver (`:1775-1778`), F3 opens a frame profiler,
and `src/box/Transaction.recover` runs before every boot. Nothing in gen1-bridge depends on these; MULTI_GAME_PLAN's
Appendix A.2 ("in-process switch through the launcher") is corrected by UP6.

**B10. Smaller:** `Assets.imageData` kept (now `Assets.lua:141`; a background PNG decode worker and `Assets.compose`
were added), `Music.playMap/playBattle/playVictory/restoreMap/current` kept with extra optional arguments
(`Music.lua:439-545`), `Input:reset` wipes two more stick fields (`Input.lua:198-203`), `Logger` never throws on a bad
format (`Logger.lua:5-26`), `Version.shell` 2 → 3 (the native shell contract; the release builds match), Ruby and
Sapphire in `GameVersion.VERSIONS` and `ORDER` (`GameVersion.lua:163-200`), zip and 7z ROM import
(`src/import/RomArchive.lua`), Gen 3 `pokemon.sprite`/`pokemon.icon` hooks, `Gen3Compat.lua` +119 lines, a launcher
"Box" (cross-game Pokémon and item storage, `src/box/`: launcher side, no in-game hook; it bypasses the one-trainer
economy of MULTI_GAME_PLAN D7 if a player uses it, a note for that plan).

### 1.4 Where the pin is written

Code and tests: `gen1-bridge/lib/kb_engine.lua:5,20` (`Engine.CHECKED`), `gen1-bridge/export/kb_swapsites.lua:19`,
`gen1-bridge/manifest.json` (`game_version`), `gen1-bridge/mod.card` (the `known` line about options),
`tools/lua/fake_engine.lua:20`, `tools/lua/fake_center.lua:98`, `tools/lua/sandbox.lua:4`, `tools/lua/fake_mod.lua:2`,
`tools/lua/test_lua_sandbox_main.py:49,134-135,150`, `tools/lua/test_lua_session.py:41,297,396,411,414`,
`tools/lua/test_lua_export.py:108`, `tools/e2e/test_handshake.py:25`, `tools/e2e/test_s5_ready.py:25`,
`tools/e2e/test_crash.py:145`, `tools/e2e/test_export.py:89,96`, `tools/e2e/README.md:33`, `tools/installer/pins.json`,
`fabric-bridge/src/test/.../{BridgeSessionTest,VersionCheckTest,ExportCliTest,FakeExportPeer}.java` (U6).
Living docs: `CLAUDE.md:159`, `INSTALL.md:25-26,56-60`, `CREDITS.md:19`, `docs/VERSIONS.md:46`,
`docs/REFERENCES.md:19,97,361`, `docs/INSTALLER.md:119`, `docs/ROADMAP.md:89,199`, `docs/PLAN.md:124,486,1268`,
`tools/lua/README.md:10`. Historical specs (`HIDDEN_STEP_SPEEDUP.md`, `WILD_ROUTES_PLAN.md`, the research reports)
keep their v0.3.49 references with one line at the top saying so.

## 2. Why v0.3.85, and what Hoenn gets from it

Emerald is importable at v0.3.49 already (MULTI_GAME_PLAN A.1), but as a beta with bugs a bridge cannot live with,
fixed since: trainers not triggering battles (#2827, v0.3.72), the white-out crash (#2736, v0.3.59), surfing not
persisting (#2761, v0.3.64), the move reminder (#2849, v0.3.75), save states in battle (#2873, v0.3.81), the
Pokédex cries (#2795), collision stutter while running (#2799, #2825), hidden trainers showing (#2876), the Aqua
Hideout Matt analysis (`docs/emerald-matt-2748.md`), FireRed/Emerald import problems on some builds (#2828, #2866),
plus Ruby and Sapphire, archive import (the user's ROMs are zipped), Gen 3 sprite and icon hooks for mods, and the
`outdoor` map field. MULTI_GAME_PLAN's Gen 3 facts (A.4–A.6) were already read at v0.3.85, and its setup tool
(§4.11) wants archive import. Staying at v0.3.49 means doing this upgrade later anyway, with the same re-import cost
and a second round of re-checks. One engine version for Kanto and Hoenn: v0.3.85.

## 3. What changes

| Area | Files | Change |
|---|---|---|
| Audit tool | `tools/pin_check.py` (new), `tools/tests/test_pins.py` (new) | the citation audit, a `--restamp` mode, and one test that every place in §1.4 agrees on the pin |
| Engine seams | `gen1-bridge/lib/kb_engine.lua` | `CHECKED`/`TESTED` "0.3.85"; `REQUIRED` table checked at boot; Union Room pin and guard; text box rules (B3); bag calls pass `data` (B4); victory and map-music rules (B5); 288 re-stamped citations; the 23 changed ones re-read |
| Mod metadata | `gen1-bridge/manifest.json`, `mod.card` | `game_version` `>=0.3.85 <0.4.0`; the `known` line names the Union Room pin |
| Status and protocol | `gen1-bridge/main.lua`, `protocol/`, `catalog.json` | `debug.status` gains `engineMissing` (list) and `unionRoom` ("off"/"on"); `export.index` answers `error{code:"union_room_on"}` while it is on (an optional field and a new error code: protocol stays 1) |
| Client | `fabric-bridge` client (`VersionCheck`, F7 line) | the untested-engine toast already exists; add the Union Room notice |
| Fakes and unit tests | `tools/lua/{fake_ui,fake_wild,fake_content,fake_center,fake_engine,fake_mod,sandbox}.lua`, the `test_lua_*.py` literals | model the new rules (B3–B8), new literals |
| e2e | `tools/e2e/run_gen1recomp.py`, `drivers/kb_ready.lua`, `drivers/kb_union_off.lua` (new), `test_s5_ready.py`, `test_export.py` | `KB_READY` adds `missing=` and `union_room=`; the runner names the stale-import cause on a ready timeout; `KB_GEN1RECOMP_APP`/`KB_GEN1RECOMP_HOME` overrides for a second Linux build; the export test compares hashes with the previous report |
| Installs | `INSTALL.md`, `docs/INSTALLER.md`, `tools/installer/pins.json`, `tools/launch.ps1` (no code change) | v0.3.85 zip and hashes; the Union Room OFF step; re-import |
| Records | `docs/VERSIONS.md`, `REFERENCES.md`, `CREDITS.md`, `CLAUDE.md`, `ROADMAP.md`, `PLAN.md`, `PROGRESS.md`, `MULTI_GAME_PLAN.md` (§0 and D11, A.2, §4.11 zips) | the new pin and its provenance |

## 4. Phases

### UP0: the user's pre-flight

1. **Downloads, official releases only** (never `gen1recomp[.]com`), SHA-256 from the release's own `sha256sums.txt`:
   - `gen1recomp-0.3.85-windows.zip` (51,220,042 bytes):
     `20cff4a16cf7235d224fe6dc32c0490f482a9d768d29a194bae5fff93c82c53a`
   - `gen1recomp-0.3.85-linux-x86_64.AppImage` (51,016,896 bytes):
     `ed558a8c53667b157baadee301889ef81174b03198b98e10772df11efd38fa89`
   ```powershell
   Get-FileHash .\gen1recomp-0.3.85-windows.zip -Algorithm SHA256
   ```
2. **The Windows test install** (U4): unzip the zip's inner `gen1recomp-win64\` into `local\gen1recomp-e2e-0.3.85\`,
   create an empty `portable.txt` beside the exe, link the mod (`mklink /J local\gen1recomp-e2e-0.3.85\mods\gen1-bridge
   gen1-bridge` in `cmd`), start the exe, import the ROM in its launcher, open **Launcher Options → Union Room → OFF**,
   close it. Renaming `local\gen1recomp-e2e` to `local\gen1recomp-e2e-0.3.49` and the new folder to
   `local\gen1recomp-e2e` is optional now (UP1): the e2e runner prefers `local\gen1recomp-e2e-0.3.85` when it exists
   (`KB_GEN1RECOMP_INSTALL` overrides it), so either name works.
3. **The pod:** put the ROM in the volume's `rom-inbox/` (it was empty on 2026-10-09). The agent prepares the AppImage
   (§4.1); the import itself is the user's command in §4.1.
4. **Say go** with any changes to U1–U6.

### UP1: tooling first (no engine needed)

1. **`tools/pin_check.py`** (the audit, committed with this plan: `python3 -I tools/pin_check.py <clone> <old tag>
   <new tag> [--report local/reports/pin_check.json]`). Add `--restamp`: it rewrites the line numbers of *same* and
   *shifted* citations in gen1-bridge and the tools in place (old → new start, ranges kept), leaves *changed* ones for
   the agent, and prints what it rewrote. Both are listed in `tools/README.md`; the audit exits 1 when a cited file
   is missing.
2. **`tools/tests/test_pins.py`:** reads `Engine.CHECKED`, `manifest.json`'s `game_version`, `pins.json`, `VERSIONS.md`,
   `INSTALL.md`, `CLAUDE.md` and the e2e/lupa literals, and fails when they disagree (ROADMAP Stage 4, item 2).
3. **Make engine upgrades loud** (ROADMAP Stage 4, item 1): an `Engine.REQUIRED` table in `kb_engine.lua` listing the
   24 modules `try()` loads and the functions and fields each must have (`Game.logicStep`, `Game:writeSave`,
   `SaveData.save/load/activeSlot/…`, `Bag.add/remove/order`, `Music.playBattle/playVictory/restoreMap`,
   `Assets.imageData`, `TextBox.new/soundOpts`, `BattleState.newTrainer/stampOT`, `Commands.hide_object/show_object`,
   `WorldAPI` calls, `DatasetViews` through `mod.datasets:open`, …); `engine.checkRequired()` at `game.ready` logs one
   `engine.missing` warning naming what is absent, `debug.status.engineMissing` lists it, `KB_READY` prints
   `missing=<n>`, and `test_s5_ready.py` asserts 0.
4. **The Union Room pin and guard:** `Engine.CLASSIC` gains `unionRoom = false` (persisted by the engine's own options
   write, as the display pins are); `engine.unionRoomPatched()` is true when `game.data.maps.POKECENTER_2F` exists;
   at `game.ready` that logs `engine.union_room_on` once, `debug.status.unionRoom` says `"on"`, `export.index` is refused
   with `error{code:"union_room_on"}`, and the client shows one notice ("Gen1Recomp's Union Room is on: Launcher
   Options → Union Room → OFF, then restart"). A new driver `tools/e2e/drivers/kb_union_off.lua` (modelled on
   `kb_mods_off.lua`) sets the option off through `SaveData.loadOptions`/`saveOptions` and quits, for the headless pod.
5. Schemas and catalog: the optional `debug.status` fields, the error code; `kb_protocol.catalog --write`; both
   stand-ins follow.

### UP2: the engine seams (gen1-bridge)

1. `Engine.CHECKED = "0.3.85"`, `TESTED` the same; `manifest.json` `game_version` `"0.0.0-dev || >=0.3.85 <0.4.0"`;
   the header comment of `kb_engine.lua` names the new tag and commit (`e50c396`, `dev` on 2026-10-12).
2. Run `pin_check.py --restamp` for the 288 shifted citing lines; then the 23 changed citations in `kb_engine.lua`
   and the 3 in `main.lua`/`act/kb_save.lua`/`lib/kb_routes.lua` by hand, per Appendix B.
3. **B3, text boxes:** `visibleLines` reads `s.shownSource` when present (a `false` entry is a blank row), else the old
   rule; `textArrow` follows the new `arrowVisible` (`waitButton` first, then the auto/stay prompt cases); `textReady`
   adds `stay.press` and the `sfxHeld` check before a stay prompt. The `ui.open`/`ui.update` text shown in Minecraft is
   the deliverable; the e2e UI and opening tests are the proof.
4. **B4, the bag:** pass `engine.gameData()` as the fourth argument to every `Bag.remove`; keep totals as the mirrored
   count; note `bagStacks` in the bag observer's comment (never mirrored).
5. **B5, music:** `playVictoryMusic` passes `"gym"` when `rec.music == "final"` or the class is a badge fight
   (`badgeFight`, the engine's `isGymLeader`), `"trainer"` for a `gym`-kind battle that isn't; `restoreMapMusic` is
   skipped after a won `final` battle (the Hall of Fame sequence owns the music).
6. **B6/B7:** the route maps declare `outdoor = true` (`kb_routes.lua:291`) so Fly and Teleport behave as on a route
   (check against WILD_ROUTES_PLAN's rules first; if the zone must refuse Fly, leave it out and say so in the comment);
   route warps may pass `door = true` for the walk-out. Nothing else changes.
7. `mod.card`: the `known` line says the Union Room is pinned off while the mod is on.

### UP3: fakes and unit tests (lupa, this server or the pod)

`fake_ui.lua` (text box: `shownSource`, the arrow rule, `stay.press`, `noLetterDelay`), `fake_wild.lua` (the Bag
stand-in: `rows`, `remove(save, id, qty, data, slot)`, `bagStacks`; the indoor rule through `rollsIndoorEncounters`),
`fake_content.lua` (music recorder: the new victory kinds, `restoreMap` not called after a won final),
`fake_center.lua` (`writeSave` through a `UnionSafety`-shaped wrapper that is the identity), `fake_engine.lua`
(`Version.engine` "0.3.85", `shell` 3), `fake_mod.lua`/`sandbox.lua` (re-checked against `Loader.lua`/`Sandbox.lua`
at the new tag: the mod object is unchanged), the literals in §1.4. Then:

```sh
cd tools && .venv/bin/python -m pytest -c pytest.ini --rootdir . lua tests -q
```

Done when the lupa count is at least today's (925 Lua tests at the M10 gate; 1,479 pytest in all at the last gate) and
`test_pins.py` passes.

### UP4: the pod, the Linux build

1. Start a dev pod (the standing rules: stop it after the job), `kb-pod prep`. Fetch the AppImage from the official
   release into its own new directory, check its SHA-256 against §UP0, extract it (`./gen1recomp-0.3.85-linux-x86_64.AppImage
   --appimage-extract`), move `local/gen1recomp-linux` to `local/gen1recomp-linux-0.3.49` and the extracted tree to
   `local/gen1recomp-linux`. The data home `local/gen1recomp-data/` stays (its import is the user's).
   *As carried out (2026-10-13): the pod keeps both builds side by side instead of renaming. The extracted tree is
   `local/gen1recomp-linux-0.3.85` with its own data home `local/gen1recomp-data-0.3.85` (imported through the pod's
   upload page: Blue, Crystal, Emerald), and the v0.3.49 folders `local/gen1recomp-linux` and `local/gen1recomp-data`
   are untouched (U5); the runner prefers the versioned names, `KB_GEN1RECOMP_APP`/`KB_GEN1RECOMP_HOME` override them.*
2. **The re-import is the user's** (U2), from their phone through `!`:
   ```sh
   ! ssh kb-dev 'cd /workspace && XDG_DATA_HOME=/workspace/local/gen1recomp-data ALSOFT_DRIVERS=null POKEPORT_CONSOLE=1 POKEPORT_IMPORT_ONLY=1 POKEPORT_IMPORT_ROM=/workspace/rom-inbox/<file> xvfb-run -a local/gen1recomp-linux/AppRun --game=blue 2>&1 | grep -c .'
   ```
   (prints only a line count). The agent then checks, by names only, that `local/gen1recomp-data/pokemon-love2d/blue/`
   has `rom-cache.complete` with a new modification time, and runs `kb_union_off.lua` once.
3. Gates G1–G4 (§5). A stale import shows as `kb_ready` timing out with the launcher open: the runner's message says so.
   *As carried out (2026-10-13): G0–G2 passed first time apart from the lazily created `Game.logicStep` (out of the
   boot check) and the export's speed on the pod (7–8 minutes on both releases, so the e2e waits scale through
   `KB_EXPORT_INDEX_TIMEOUT`/`KB_EXPORT_TEST_TIMEOUT`, and the drivers' own limits through `KB_DRIVER_EXTRA`); G3
   found the press-signal regression (the client presses only while `waiting`, v0.3.85 hides the arrow on `{DONE}`
   texts); G4 found the Linux TIME_WAIT bind after a crash (`SO_REUSEADDR` off Windows now, through `tcp4()`), a stale
   kind list in `test_export.py`, and the `routes` resource carrying its two chances as fractions against the
   protocol's canonical-number rule (whole percent now, so its hash changes; no rebuild). PROGRESS.md note 9 has
   the details.*

### UP5: the user's PC, the Windows build

1. The e2e install from UP0 step 2: first copy the last v0.3.49 export report, `local\reports\export_report.json`, to `local\reports\export_report_previous.json` (the new report then lists, per kind, which resources changed hash: G2's comparison, which decides G6); then `tools/check.sh --e2e` (the Windows-only `test_hidden.py` included), G5. On the pod the export builds at a few steps a second (both releases alike) and the test's default waits are too short: `KB_EXPORT_INDEX_TIMEOUT`/`KB_EXPORT_TEST_TIMEOUT` raise them there.
2. The user's own install, same recipe as UP0 step 2 into `local\gen1recomp-0.3.85\` (their saves: the user copies
   `save.lua`, `options.lua`, the slot files and `mod_cache\` from the old folder before the first start; the ROM cache
   is re-imported, never copied by the agent), Union Room OFF, then the rename. `launch.ps1` needs no change: it finds
   `local\gen1recomp\gen1recomp.exe`.
3. Playtest (PLAYTEST.md), the items that touch the changed seams: M1 (connection, F7's engine line shows 0.3.85 and no
   untested-engine toast), M3 (doors and stairs: the walk-out), M4 (dialogue pages and the arrow, a two-row sign),
   M5 (the rival battle's music and the map song after), M7 (the Center's nurse and PC with the vanilla 1F desk, the
   Mart's buy and sell counts, a 99+ stack of POTIONs if they have one), M8's Brock (the badge jingle), M10 (hidden
   window speed), Wild Routes WR3–WR4 (the zone's warps in and out).

### UP6: pins, docs and records

`VERSIONS.md` (the new row: zip name, SHA-256, release date, "latest when pinned on <date>", the `.love` and AppImage
hashes for the pod), `INSTALL.md` §3 (v0.3.85, the hashes, step 6 "Launcher Options → Union Room → OFF", "zipped ROMs
can be imported as they are"), `INSTALLER.md` and `pins.json`, `CREDITS.md`, `CLAUDE.md:159` and the "Gen1Recomp" bullet
(the Union Room rule joins the hard rules: *every KantoBridge install runs with the Union Room off*), `REFERENCES.md`
(`dev` @ `e50c396`, 2026-10-12, in v0.3.85; the Gen1Recomp index's line numbers re-stamped where cited),
`ROADMAP.md` (the upgrade-watch row: "v0.3.85; `tools/pin_check.py` is the first step of the next bump"; Stage 4 items
1–2 done), `PLAN.md:124,486,1268`, `PROGRESS.md` (a new section "Engine upgrade" with the gate numbers and the audit
counts), `MULTI_GAME_PLAN.md` (§0 ground rule 2 and D11: "bumped to v0.3.85 on <date>"; A.2: the launcher restarts the
process; §4.11 step 5: archives import directly), `tools/e2e/README.md:33`, `tools/lua/README.md:10`.

## 5. The checks

Feature tests while building, the full suites at the gates (the standing rule). Each gate names its pass condition.

| Gate | Where | What runs | Passes when |
|---|---|---|---|
| **G0 static** | this server | `tools/pin_check.py refs/gen1recomp v0.3.49 v0.3.85`; after UP2, `pin_check.py refs/gen1recomp v0.3.85 v0.3.85` on the re-stamped code; `pytest lua tests` (UP3); `./gradlew test` (JUnit, no engine) on the pod | 0 missing files; every *changed* row in Appendix B has its disposition applied; lupa and JUnit counts at least the last gate's (925 Lua / 913 JUnit) |
| **G1 smoke** | pod | `test_s5_ready.py` (`KB_READY engine=0.3.85 status_engine=0.3.85 modules=30 mod_errors=0 missing=0 union_room=off`), `test_handshake.py` (`hello.ok` engine and `tested` both 0.3.85, no `engine.untested`), `test_hot_reload.py` | all three pass; the crash log untouched |
| **G2 export** | pod | `test_export.py` | the same counts as today (222 maps, 36 surface, 36 layout, 186 interiors, 242 LAST_MAP warps, 802 warps, 24 tilesets, 151 species, 165 moves), every schema strict, hashes match Python's; the swap audit unchanged; the index hash and per-resource hashes compared with the report from v0.3.49 kept in `local/reports/` (differences listed by resource name only) |
| **G3 seams** | pod | `test_walk.py` (warp kinds stairs/edge/door, B7), `test_ui.py` and `test_opening.py` (pages, the arrow, naming, B3), `test_battle.py` both outcomes (B5), `test_route1.py` (bag.sync and bag.use totals, B4), `test_viridian.py` (the Center's save, the PC rows, the Mart's counts), `test_content.py` (the gym's badge jingle counts), `test_endgame.py` (the champion: no map song restored on the win), `test_wild_routes.py` (route warps) | all pass with their existing assertions, plus the new music and union assertions |
| **G4 full** | pod | `kb-pod check --e2e` (every e2e driver, the lupa suite, `tools/tests`) and `gradlew build` with GameTests | pytest at least 1,479 + the new tests; GameTests 94/94; JUnit at least 913; `test_perf.py` within its budgets (gen1-bridge under 1 ms a step, the mirror at most 30 fps) |
| **G5 Windows** | user's PC | `tools/check.sh --e2e` on the test install (adds `test_hidden.py`), the playtest list of UP5 step 3 | every e2e passes; the playtest items ticked in PLAYTEST.md |
| **G6 world** | user's PC (the pod has no EULA file) | only if G2 found a changed `maps`, `tilesets`, `palettes` or `sprites` hash: `tools/e2e/build_world.py --maps all`, then the shape self-check | the build report within its gates (220 maps placed, 0 warnings, self-check floors) and the client joins the rebuilt world; otherwise the existing worlds stay valid (the pairing key is the game and world id, not the export hash) |

A failing gate stops the phase; the fix goes into the same seam, never into a workaround elsewhere (CLAUDE.md:
*only `kb_engine.lua` touches internals*).

## 6. Rollback

The v0.3.49 folders stay until G6 (U5): `local/gen1recomp-e2e-0.3.49`, `local/gen1recomp-0.3.49` and the pod's
`local/gen1recomp-linux-0.3.49`. Rolling back is `git revert` of the upgrade commits and the folder renames; the old
engine then asks to re-import its own cache generation (the user's action again). Saves are forward-compatible
(`saveFormat` 5 both ways) unless the Union Room was on and the player stood on an added cell, which U3 prevents.

## 7. Risks

| Risk | Sign | Answer |
|---|---|---|
| A newer release lands before the work starts (several ship a day) | U1 | re-run `pin_check.py` against it and read its notes; if it touches the files of Appendix B, redo the dispositions; the plan's structure holds |
| The headless import on the pod needs a display or refuses the inbox path | the user's command exits without a new marker | `xvfb-run` is in the command; if the importer still refuses, the user imports on their PC and the pod waits for UP5's result (the pod gates then run after the PC's) |
| The text-box rewrite (B3) changes a timing the Minecraft dialogue screen relied on | `test_ui`/`test_opening` page counts or the arrow checks fail; a playtest sign shows a blank row | the fake and the seam follow the engine's new `shownSource`; a blank row is a real row in Gen 1's two-row box and is shown as such |
| The export hash changes though maps and tilesets didn't (`text`/`rom_text` re-extraction, v0.3.52 #2634) | G2 lists `text` as different | no rebuild: the world is built from `maps`/`tilesets`/`palettes`/`sprites`; dialogue comes live from the hidden game |
| Two engine builds on one pod data home | a v0.3.49 run after the re-import | never run the old AppImage against the upgraded data home; `KB_GEN1RECOMP_HOME` points a rollback run at its own home |
| The Union Room setting is re-enabled by the launcher UI on the user's PC | `debug.status.unionRoom` "on", the client notice, `export.index` refused | the pin writes it off again at the next options write; the notice says what to click |
| `Loader`'s new fast require path hides a denial that used to warn | a module gen1-bridge `try()`s loads where it was refused before, or the reverse | the `REQUIRED` check at boot names anything missing; `test_lua_sandbox_main.py` keeps the module list |

## 8. Appendix A: the audit, by cited engine file

Counts are citations (changed / shifted / same) and the file's diff size between the tags.

| Cited file | Cites | Changed | Shifted | Same | +/- |
|---|---|---|---|---|---|
| src/core/Game.lua | 71 | 15 | 56 | 0 | +81/-9 |
| src/inventory/Bag.lua | 8 | 7 | 0 | 1 | +356/-66 |
| main.lua | 32 | 5 | 27 | 0 | +233/-65 |
| src/world/OverworldController.lua | 264 | 3 | 261 | 0 | +327/-96 |
| src/render/TextBox.lua | 17 | 3 | 10 | 4 | +58/-18 |
| src/battle/BattleState.lua | 15 | 3 | 8 | 4 | +194/-86 |
| src/core/SaveData.lua | 10 | 3 | 7 | 0 | +211/-8 |
| src/ui/BagMenu.lua | 10 | 3 | 6 | 1 | +60/-26 |
| src/script/Commands.lua | 38 | 2 | 28 | 8 | +8/-1 |
| src/core/Music.lua | 5 | 1 | 4 | 0 | +137/-29 |
| src/core/Input.lua | 2 | 1 | 1 | 0 | +147/-91 |
| src/core/Version.lua | 1 | 1 | 0 | 0 | +1/-1 |
| src/render/Assets.lua | 1 | 1 | 0 | 0 | +83/-4 |
| src/core/Logger.lua | 1 | 1 | 0 | 0 | +22/-1 |
| src/mods/Loader.lua | 20 | 0 | 20 | 0 | +78/-4 |
| src/world/Map.lua, WorldAPI.lua, ScriptRunner.lua, StateStack.lua, pokemon/*.lua, Warp.lua, FieldDefaults.lua, DatasetViews.lua, ModUI.lua, HotReload.lua, Badges.lua, Collision.lua, MapScripts.lua, Hooks.lua, Events.lua, Runtime.lua, ImportAccess.lua, Flags.lua, Transition.lua, Screens.lua, Strings.lua, FixedStep.lua, Tilt.lua, Zoom.lua, Pipelines.lua, PadHints.lua, MapLoader.lua, MoveLearnMenu.lua, PokedexMenu.lua, StartMenu.lua, PrizeCounter.lua, QuantityBox.lua, ChoiceBox.lua | 128 | 0 | 0 | 128 | 0 |
| src/ui/Menu.lua, ListMenu.lua, NPC.lua, Data.lua, TownMap.lua, ShopMenu.lua, TileRenderer.lua, Renderer.lua, PartyMenu.lua, RomExtractor.lua, LaunchOptions.lua, Player.lua, ItemEffects.lua, Sandbox.lua, GameVersion.lua, NamingScreen.lua, Evolution.lua, link/Protocol.lua, TitleState.lua, ImageWriter.lua, ManagerState.lua, CacheContract.lua | 73 | 0 | 56 | 17 | small |

## 9. Appendix B: the 49 changed citations and their disposition

"Now" is the approximate new start line. Dispositions: **re-stamp** (numbers only), **re-read** (confirm the behaviour,
then re-stamp), **change** (code follows §4).

| Cited (v0.3.49) | Now | Cited by | What changed | Do |
|---|---|---|---|---|
| main.lua:993-1011 | ~1131 | run_gen1recomp.py:12, kb_ready.lua:7 | the driver is resumed with `(Game, driverHost)`; exit codes same | re-stamp |
| main.lua:1637-1642 | ~1775 | run_gen1recomp.py:300, test_hidden.py:7, kb_wild.lua:17 | `POKEPORT_DRIVER_PACED=1` added before the driver check | re-stamp; optional: `kb_wild`'s `KB_PACE` through it |
| BattleState.lua:816-1090 | ~850 | fake_content.lua:9 | cry pre-warming inserted in the builders | re-stamp |
| BattleState.lua:5812-5890 | ~5912 | kb_engine.lua:2061 | `finish` keeps the music after a won final battle | change (B5) |
| BattleState.lua:5859-5865 | ~5959 | kb_engine.lua:2048 | same | change (B5) |
| Game.lua:41-119, :47-119, :52-119 | ~43 | kblua.py:265, kb_engine.lua:3861, :4879, main.lua:830 | two requires, `Input:init(false)` | re-stamp |
| Game.lua:427-434 | ~430 | kb_engine.lua:4708 | `DeferredWrite.tick`, profiler marks | re-stamp |
| Game.lua:1120-1139 | ~1151 | kb_engine.lua:1205 | options flushed on focus/visibility | re-stamp |
| Input.lua:182-203 | ~193 | kb_engine.lua:1205 | right-stick fields in `reset` | re-stamp |
| Game.lua:1326-1351, :1328-1351, :1342-1345 | ~1360 | kb_engine.lua:4442, :3200, kb_save.lua:11, main.lua:609, fake_center.lua:4, :87, kb_viridian.lua:40 | `SaveData.save` called through `UnionSafety.write` | re-read (identity with the setting off), re-stamp; fake_center wrapper |
| Game.lua:1475-1537 | ~1537 | kb_engine.lua:4665, fake_center.lua:291 | `UnionSafety.settle`, the title song kept, `TradeTxn.resumePending` | re-stamp |
| Logger.lua:5-13 | ~26 | fake_mod.lua:34 | format hardening | re-stamp |
| Music.lua:418-497 | ~461 | fake_content.lua:12 | `playMap(.., song)`, `restoreMap(data, reason)`, fades | re-stamp; the fake accepts the extra arguments |
| SaveData.lua:569-654 | ~570 | kb_engine.lua:25 | `audioMode` added, two caches removed; the six CLASSIC keys intact | re-stamp |
| SaveData.lua:1222-1872 | ~1254 | fake_center.lua:20 | legacy migration lines, `duplicateSlot` after | re-stamp |
| SaveData.lua:2585-2638 | ~2785 | kb_engine.lua:4662 | `saveSessionOptions` | re-stamp |
| Version.lua:6-23 | 6 | kb_engine.lua:214 | `shell` 3 | re-stamp |
| Bag.lua:139-181, :139-164, :166-181 | ~443, ~463 | kb_engine.lua:2958, :2978, :4159, kb_route1.lua:6, kb_viridian.lua:7, fake_wild.lua:43, fake_field.lua:87 | stacks model, `remove(save, id, qty, data, slot)` | change (B4) |
| Assets.lua:67-71 | 141 | kb_engine.lua:5213 | `imageData` moved below the new compose/prefetch code | re-stamp |
| TextBox.lua:391-399, :415-426, :457-698 | ~404, ~428, ~486 | kb_engine.lua:771, :810, :786 | `shownSource`, the arrow rule, `stay.press`, `noLetterDelay` | change (B3) |
| Commands.lua:395-401 | 395 | kb_routes.lua:21, fake_script.lua:548 | `warp(…, door)` | re-stamp; optional door flag |
| BagMenu.lua:20-37, :39-46, :89-511 | ~20, ~42, ~95 | fake_wild.lua:188, :242, kb_engine.lua:3008 | rows with slot and stack; consume by slot | change in the fake; comment in kb_engine |
| OverworldController.lua:296-345 | ~301 | kb_engine.lua:5171 | `_setGameForTest` inserted after `computeNeighbors` | re-stamp |
| OverworldController.lua:4781-4790 | ~4923 | kb_engine.lua:2631 | `rollsIndoorEncounters(def, indoor)` | re-read; comment; fake_wild rule (B6) |
| OverworldController.lua:5411-5425 | ~5561 | kb_viridian.lua:12 | atlas prefetch; door-warp inference | re-stamp; `test_walk` is the check (B7) |
| src/x.lua:12 | – | test_s5_ready.py:45 | a filter example, not a citation | ignore |

## 10. Appendix C: upstream facts used

- Repository `dev` head `e50c396e` (2026-10-12 18:39 -0400); 100 releases listed by the API; `v0.3.85` published
  2026-10-12, the latest on 2026-10-13. Release assets: Windows zip, `.love`, Linux x86_64 and arm64 AppImages, flatpak,
  macOS, Android, iOS, Switch, Xbox, PS4, PortMaster, RG34XX, and `sha256sums.txt` (the hashes in UP0 come from it;
  v0.3.49's file confirms the pinned zip hash `7816b1fd…3ae878`).
- Source layout: `src/` gained `box/` and `recommend/`; `src/world/gen1/Union*.lua`, `src/online/union/*`,
  `src/ui/union/*`, `src/core/game3/link/union_*` are the Union Room; `docs/union-room.md` is its design note;
  `docs/new-features.md` lists the user-facing changes since the pin.
- Release notes with mod or Hoenn relevance: v0.3.50 (Gen 3 custom maps in the save editor), v0.3.52 (Gen 1 dialogue
  blank lines, Mew encounter, Marts capping quantity, staged mod changes applied after restart), v0.3.55 (BOX),
  v0.3.56 (`pokemon.icon` on Gen 3), v0.3.58 (mod-authored outdoor maps rolling encounters: the `outdoor` field),
  v0.3.59 (Emerald white-out crash), v0.3.61 (Union Room), v0.3.64 (Emerald surf), v0.3.65 (`pokemon.sprite` kinds),
  v0.3.72 (Emerald trainers triggering battles), v0.3.73 (optional mod dependencies), v0.3.79 (Gen 1 FLASH state saved),
  v0.3.81 (Emerald save states in battle), v0.3.84 (Union Room parity; Gen 1 copies of Gen 2 species through mods;
  `pokemon.sprite`/`icon` for Unown on Gen 3).

## 11. Appendix D: `tools/pin_check.py`

```sh
python3 -I tools/pin_check.py refs/gen1recomp v0.3.49 v0.3.85 --report local/reports/pin_check.json
```

Reads the clone only through `git -C` (`ls-tree`, `diff --numstat`, `diff -U0`), scans the repository's own files for
`src/…lua:N[-M]`, `Name.lua:N`, `OC:N` and `, :N` continuations, and prints the table of §1.1 and Appendix A plus the
changed citations with their citing `file:line`. The JSON report holds every citation with its class and new start
line; nothing in it comes from a ROM. `--restamp` (UP1) will rewrite the numbers of same and shifted citations in
place.
