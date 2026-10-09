# Roadmap: what to build next

Written on 2026-10-04, when the repository went public, from a full read of the documents and the code (four review
passes: what PROGRESS.md leaves open, the plans and proposals, the code's debt, and the setup). [PROGRESS.md](PROGRESS.md)
stays the record of what's done and how; this file says what comes next and in what order. Keep it current: tick off
or move what lands, and add what the play-tests find.

References use `file:line` as they stood on that date.

## 1. Where things stand

**Built and committed:**

- **PLAN.md's M0–M10:** the bridge, the world builder, the single-player story from the opening to the Hall of Fame,
  multiplayer, crash recovery and the launcher (`PROGRESS.md:9-21`).
- **The voxel plan's V1–V9:** shaped blocks, units, structures, texture packs and characters (`PROGRESS.md:36-47`).
- **The profile plan's P1–P4:** Dramaless Shape's hand-made table, buildings, interiors and boxes (`PROGRESS.md:427-432`).

**Green in automation:** 1,455 pytest tests (the end-to-end ones on the real game included), 848 JUnit tests and 92
GameTests; the whole of Kanto (220 maps, 2.5 million blocks) builds in about 61 s.

**Not confirmed in game:** none of [PLAYTEST.md](PLAYTEST.md)'s ~200 checks is ticked. The one recorded play-test (the
voxel look, `PROGRESS.md:422-425`) found folded-cube trees and boxy buildings, which the profile plan answered; whether
it fixed them waits on a new world. V1's own performance gate (X1) was skipped (`PROGRESS.md:53-55`).

**Specced, not built:** the hidden-step speed-up ([specs/HIDDEN_STEP_SPEEDUP.md](specs/HIDDEN_STEP_SPEEDUP.md)).

**Close to their limits:**

| Budget | Limit | Latest | Ref |
|---|---|---|---|
| Quads in one block, most | 96 | 87 | `PROGRESS.md:472` |
| Quads in one 16³ section, most | 30,000 | 24,064 (26,752 at P1–P2) | `PROGRESS.md:473, 448` |
| Shape map on the wire | 524,288 characters | 409,476 (78%) | `PROGRESS.md:471-472` |
| Full-shape block slots | 4,096 | 3,926 (96%) | `PROGRESS.md:1404-1405` |
| Structure self-check, minimum | 0.35 | 0.530 (0.364 with the class boxes scored) | `PROGRESS.md:473-474` |
| gen1-bridge's logic per step | 1 ms | 146 µs on average, 2,485 µs at most (1 step in 2,063) | `PROGRESS.md:1300-1301` |

## 2. Next, in order

### Stage 1: validate before adding (you; agents prepare and record)

Everything later builds on today's renderer, world format and movement, and none of it has been seen in game.

1. **The profile-plan world:** check Dramaless Shape is switched off in your Gen1Recomp, start a **new** world, then
   work through PLAYTEST's "Profile plan" (P1–P3) and the voxel sections it touches.
2. **The measurements X1–X7** (PLAYTEST's voxel sections; thresholds in `PROGRESS.md:49-63`): the runtime model against
   the JSON baseline, shapes against cubes, mesh times, the `.vox` axes, 64 puppets, and (optional) Sodium, Iris and
   Distant Horizons. X1's baseline needs a world built before V3; an agent can build one at V1a's commit if you agree.
   If any fails, the voxel plan's R1 ladder applies (`specs/VOXEL_BLOCKS_PLAN.md:1376`).
3. **The story play-tests M0–M10** (PLAYTEST), and one session on modest hardware (`DESIGN.md:173`).
4. **Record it:** an agent ticks PLAYTEST, notes failures in PROGRESS, and turns them into Stage 3 items.

### Stage 2: the hidden-step speed-up (small; can run alongside Stage 1)

Walking diagonally outruns the hidden game, which rubber-bands, and an uphill bike step takes walking time there.
[The spec](specs/HIDDEN_STEP_SPEEDUP.md) is ready: gen1-bridge only, no protocol, schema or Java change, no rebuild. It
wraps the engine's public `movement.speed` hook for the steps the bridge drives, with a STEP SPEED option (1×, 1.5×,
2×, 3×; default 2×). Its code anchors still hold (`gen1-bridge/act/kb_move.lua:51`,
`client/move/MovementSync.java:71, 89`). Its open questions (`specs/HIDDEN_STEP_SPEEDUP.md:423-430`): the default speed,
whether 1× also undoes the Cycling Road fix, and whether Minecraft gets a STEP SPEED too. The spec says you meant to
code it yourself; an agent can, if you'd rather. Land it before the movement play-test, so that play-test sees the
final behaviour.

### Stage 3: story-integrity fixes

| Item | Why | Size | Refs |
|---|---|---|---|
| Pokémon Tower's wild Pokémon once the SILPH SCOPE is held | There are none: no pool, and the hidden game suppresses its rolls. Needs your choice of approach | M | `PROGRESS.md:1383-1387`; `builder/plan/HabitatPlanner.java:41,61` |
| NPCs across a map seam | Puppets vanish as you cross a town–route seam. Spike whether gen1-bridge can read a neighbouring map's objects cheaply | M | `PROGRESS.md:1389-1390` |
| A riding veto inside Kanto | A Cobblemon land ride's step height (1.2) crosses one-block story cells and counters | S–M | `docs/research/VOXEL_WORLD_REPORT.md:178, 9087` |
| The profile's `support` rule | Standees on counters, tables and bookcases stand on the floor behind them (about 260 cells) | M | `PROGRESS.md:561-562, 579` |
| "Create Kanto world" robustness | Closing the game mid-build leaves a half-built world; the reload check is a heuristic; the world has commands on | S–M | `PROGRESS.md:1406-1409, 840-842` |
| Crash recovery that repairs | It only warns today. Start with `save.loaded` on a savestate restore and the New Game re-gifts | L | `PROGRESS.md:1393-1399` |
| An id for `party.item` | Sent once, queued until a client connects, and not safe to re-send | S–M | `PROGRESS.md:984-985`; `protocol/messages/party.item.schema.json:10` |
| Your own options and offline slot | Restore Gen1Recomp's display options and its offline slot when Minecraft goes, if you choose (Q1, Q3) | S each | `PROGRESS.md:1350-1365` |
| Pokédex owned markers | The list has none | S | `PROGRESS.md:1391` |
| One process per install | Two processes of one install share slots and files: a lock file at bridge start | S | `PROGRESS.md:1400-1401` |
| Q5: the pre-V5 renderer | Remove it once your worlds are rebuilt; that completes V9's definition of done | S | `PROGRESS.md:1369-1372, 405-408` |

### Stage 4: engineering clean-ups

Already done while publishing: the licence travels inside the jar, and gen1-bridge's `mod.card` describes what it does
today. Next, by value against effort:

1. **Make engine upgrades loud.** `kb_engine.lua` loads 24 engine modules through a `try()` that returns nil silently
   (`gen1-bridge/lib/kb_engine.lua:181-185`), and some seams are called through a discarded `pcall`
   (`gen1-bridge/main.lua:812-814`). List the modules, patched functions and key fields in one table, check it at boot
   with one warning naming what's missing, and assert it in an e2e smoke test. The 651 lupa tests run against v0.3.49
   fakes, so they can't see engine drift.
2. **A consistency test** for the versions in `gradle.properties`, `tools/installer/pins.json`, `fabric.mod.json` and
   gen1-bridge's `manifest.json`/`mod.card`, the engine pin (`Engine.CHECKED`), the default port, and the build gates
   `tools/e2e/build_world.py:60-79` copies from Java by hand.
3. **One home for the port.** 27151 is written in 8+ places, and the fallback span in three forms; when gen1-bridge falls
   back to +1..+8 the client still tries one port (`gen1-bridge/lib/kb_net.lua:227-241`). Put it in `catalog.json`, and
   let the client try the span (or let `launch.ps1` pass `-Dkantobridge.port`).
4. **One grid flood fill** for the 15 hand-written queue loops in 11 shape files (`geom/Background`, `BandBuilding`,
   `Hulls`, `HullVariants`, `KeyGeometry`, `ObjectRule`, `TemplateRule`, `Occupancy`, `Structures`, `Units`,
   `ShapeMap`); the goldens protect the refactor.
5. **One set of validators:** Lua's `sym` exists 7 times; Java's symbol length is 64 in `proto/Ids.java` but 96 in
   `world/DataReader.java:20`.
6. **Small Java dedupes:** an atomic-write helper (`DaycareStore`, `SessionRecord`), one resource-reload poll
   (`CreateKantoWorldFlow`, `TextureSyncRuntime`), one template-entry parse with a counter instead of an empty catch
   (`geom/ShapeTable.java:226-228`, `client/packs/KeyIndex.java:155-157`), deleting the unused `geom/QuadSink`, the top
   stack frame in `Dispatcher.java:92`'s log, and one mixin-verification task in `build.gradle`.
7. **Test infrastructure:** one pre-flight fixture for the 21 copied e2e skips; `tools/fake_gen1` and `tools/kb_protocol`
   as Gradle test inputs (`build.gradle:506-518`); a missing venv failing `check.*` instead of silently skipping six
   integration tests; a testable step machine pulled out of `CreateKantoWorldFlow` (615 lines, no tests); `fake_gen1`
   simulating `party.sync`, `dex.sync`, `battle.result` and `bag.use`.
8. **Split `kb_engine.lua`** (5,240 lines, one closure) by its milestone sections into `lib/engine/*.lua`. Needs your OK:
   CLAUDE.md lets only `lib/kb_engine.lua` touch engine internals.
9. **Portability:** the JDK path hard-coded in `tools/check.*` and `build_world.py:80` (`KB_JAVA_HOME` overrides it),
   `PLAYTEST.md:41,44`'s absolute path, and the Windows crash-log path in a player-facing message
   (`lang/en_us.json:167`). The mod code itself is portable; the scripts and the pinned Gen1Recomp build are Windows.
10. **Loose ends:** the dormant hidden-game-to-Cobblemon Pokédex path (`pokemon/DexService.java:41`; wire it or delete
    it), `datapack-templates/` (empty), and stale comments (`block/ShapeBlock.java:9`, `geom/QuadMerger.java:9-10`,
    `client/net/SessionRuntime.java:35-37`).

### Stage 5: the installer and a first release

Players can only use the development setup today ([INSTALL.md](../INSTALL.md)). [INSTALLER.md](INSTALLER.md) designs a
one-click per-user install (a portable Prism Launcher with a ready instance, a portable Gen1Recomp, pinned downloads
checked by hash, the ROM import through Gen1Recomp's own importer); only a skeleton exists
(`tools/installer/install.ps1`, which verifies downloads and prints the plan).

1. Answer INSTALLER.md's four questions (`docs/INSTALLER.md:136-147`) and pin a Prism version.
2. Implement its steps; write the Prism instance template, `prismlauncher.cfg` defaults, the `.cmd` wrappers and a
   THIRD-PARTY-NOTICES file (Prism's GPL notice and source link included).
3. A release script and a version policy: both mods are still 0.1.0, and every player of a world must run the same
   release (the bridge's version handshake checks it).
4. Tighten the pins: Gen1Recomp's size, and a Cobblemon hash from an official source.
5. Decide Dramaless Shape for players (only the world builder's needs it, switched off).
6. A dedicated-server path for players (only the development server exists).
7. Update, repair and uninstall (`docs/INSTALLER.md:89-102`); test on a clean machine and on modest hardware.
8. Ship Kanto as a flat world first: Stage 7 changes the world format.

### Stage 6: presentation (first-person dressing)

Built: interior walls up to the ceiling, darkened ceilings, open-air forest and Safari, building backs, carved
doorways, the forest's canopies. Not built ([NEXT_STAGES.md](NEXT_STAGES.md) §3): shaped doors, caves (rock roof,
dripstone, pools, lanterns), trunks, terrain past the map edge, day/night, weather and sky, per-map fog and colours,
particles, a ledge-hop and doorway camera, sound per tile class. Client-only items first; builder items in one batch,
since each needs a rebuild. Day/night and weather clash with today's server rules (noon at every start).

### Stage 7: Kanto inside a vanilla overworld (M11–M14)

Designed only in the research report (`docs/research/VOXEL_WORLD_REPORT.md:8690-8871`): M11 Kanto in a vanilla world
with one gate (a chunk-generator subclass, the project's first access widener, region messages), M12 real play outside
(Survival, scoped rules, five more server mixins), M13 edges, more gates and sky, M14 regions as data. Before it: your
answers to D1–D9, D16 and D17 (`:8660-8676`), the footprint and edge numbers the builder doesn't report yet, the
experiments E1–E12 (`:8972-8983`), and a firm spec in `docs/specs/`, as the voxel and profile plans had.

**Update (2026-10-09):** [specs/VANILLA_WORLD_PLAN.md](specs/VANILLA_WORLD_PLAN.md) proposes the first step from your
brief of that day. Not decided yet:
- every interior moves into one custom dimension;
- Kanto sits contained in pre-generated vanilla land behind a buffer zone;
- spikes S1–S9 on the RunPod pod come first, then milestones W1–W5.

It replaces M11a's generator and leaves the gates and the rules by zone for later.

### Stage 8: regions and Johto

After M14 ([NEXT_STAGES.md](NEXT_STAGES.md) §5): a Gen 2 bridge, a generation-neutral protocol (species up to 1,025,
Gen 2 items, the clock), one hidden game per region (or a switch at a crossing), per-region rules. Groundwork exists (the
neutral `scene/` layer, packs that name their game); a region-scoped world format and a configurable map edge don't.

## 3. Decisions waiting on you

1. **PROGRESS's questions** (`PROGRESS.md:1348-1379`): Q1 restore your display options on exit; Q2 a multiplayer world
   switched mid-save; Q3 restore your offline slot; Q4 a LAN switch for offline guests; Q5 remove the pre-V5 renderer;
   Q6 the installer's questions.
2. **Pokémon Tower after the SILPH SCOPE:** a per-player pool gated on the scope, or the hidden game rolls and starts a
   Cobblemon battle (`PROGRESS.md:1383-1387`).
3. **The speed-up** (`specs/HIDDEN_STEP_SPEEDUP.md:423-430`), and who codes it.
4. **The voxel defaults the run took** (`PROGRESS.md:30-34`): VD5 thick cards by default, VD7 dressing scope, VD8 the
   block outline around thin art, VD9 the generated pack below player packs, VD10 a settings file or a screen.
5. **The profile plan:** whether to build the `support` rule.
6. **Stage 7's D-decisions** and, for Stage 6, day/night, weather and per-map moods.
7. **Release:** the installer's questions, Dramaless Shape for players, commands in player-created worlds, and the legal
   check DESIGN.md asks for before a public release (`DESIGN.md:174`).

## 4. Upgrade watch

| Component | Pin | What an upgrade involves |
|---|---|---|
| Gen1Recomp | v0.3.49 | It ships often. Re-check every internal in `gen1-bridge/lib/kb_engine.lua` (about 144 functions), the patched functions and the e2e drivers' `require`s; a new export means a world rebuild |
| Cobblemon | 1.8.1 | Re-check its 9 mixins (3 in common code) and 73 imports, some from non-API packages |
| Minecraft | 1.21.1 | Needs Cobblemon for that version, Loom 1.18+ (Gradle on JDK 25), and the model API re-researched |
| Fabric Loader, Fabric API, Parchment | 0.19.5, 0.116.17, 2024.11.17 | Already the newest for 1.21.1 |
| Dramaless Shape | 2.0.4 (read from the player's install) | Its table is parsed leniently; a new format shows in the build report's `profile.unknown` |

Every pin change needs your OK first (CLAUDE.md); [VERSIONS.md](VERSIONS.md) records them.

## 5. Working on this repository

- **Read [CLAUDE.md](../CLAUDE.md) first.** Its hard rules matter most in a public repository: nothing from a ROM is ever
  opened, copied or committed (that covers Gen1Recomp's data, exports, generated packs, worlds and screenshots);
  `local/` and `refs/` stay out of git; reference code is never copied without checking its licence; Gen1Recomp comes
  only from its GitHub.
- **How work has been done:** a plan in `docs/specs/` (the design, milestones, pre-approved defaults) → one milestone
  at a time, each green with `tools/check.ps1` (with `-E2E` on the test install where the plan asks) → PROGRESS.md
  records deviations and open issues → PLAYTEST.md gets the checks only you can do in game → one commit per milestone.
  Ambiguity stops the run with a question.
- **Setup and tests:** [INSTALL.md](../INSTALL.md), [tools/README.md](../tools/README.md).
- **Licence:** GPL-3.0-only with Gen1Recomp's credit line ([LICENSE](../LICENSE), [CREDITS.md](../CREDITS.md)).
