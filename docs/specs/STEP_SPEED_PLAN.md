# Step speed plan: the hidden player never falls behind Minecraft (all three games)

Written 2026-10-26 from the user's brief: "where the player can move diagonally in Minecraft they can move faster than
the hidden game player … speed the player up in the hidden game so the Minecraft player can't go faster and 'lag',
causing them to teleport to the hidden player's location. It must be applicable to all three hidden games." It
supersedes [HIDDEN_STEP_SPEEDUP.md](HIDDEN_STEP_SPEEDUP.md) (2026-10-03, Gen 1 only, written before the ride, the
Gen 2 and Gen 3 adapters and the v0.3.85 pin); that spec's analysis still holds for Gen 1 and is reused here.

Every `path:line` was checked on 2026-10-26: Gen1Recomp references are to v0.3.85 (`refs/gen1recomp`), the rest to this
repository at e67b653.

## 0. In one paragraph

Minecraft walks at exactly the hidden game's pace (`Rules.WALK_SPEED`: one 2-block cell per 16 frames; `BIKE_SPEED`,
also used while Emerald runs: 8), the hidden game takes one step at a time with two waiting behind it, and a 45°
diagonal crosses cell boundaries √2 ≈ 1.41× faster than a straight walk. So on a diagonal the hidden player falls 0.4
cell behind per cell, the queue fills after a few cells, the oldest waiting step is dropped (`player.blocked{script}`)
and the client snaps the player back; farther behind, or stuck, it teleports. The fix is the user's: **shorten the
hidden game's own step for the steps the bridge drives**, by default to half (walk 16 → 8 frames, Emerald's run 8 → 4),
so the hidden player always lands before the Minecraft player reaches the next cell and the queue never fills. Minecraft's
speeds, the protocol and the client stay as they are. Gen 1 and Gen 2 expose a public hook for it (`movement.speed`);
Gen 3 has none, so its adapter writes the step length at the one place it already taps every step. Rides (bikes) are the
mirror image, Minecraft following the hidden game, and are not sped up; their lag gets a separate, small client-side
catch-up (Part B, D6).

## 1. What the code says (the investigation)

### 1.1 The mechanism, confirmed

- **No headroom.** `fabric-bridge/src/main/java/dev/kantobridge/server/Rules.java:45-47`: `WALK_SPEED = 0.1737F`
  (16 frames a cell), `BIKE_SPEED = 0.3474F` (8). `Rules.applyMovement` (`:97-109`) uses `BIKE_SPEED` for
  `state.bike() || state.run()`: Emerald's running shoes move Minecraft at 8 frames a cell, the hidden run step is 8
  frames too (`src/core/game3/player.lua:19 RUN_FRAMES = 8`). Walking is 16 in all three engines (`src/world/Player.lua`
  `STEP_FRAMES`; `src/world/gen2/Bike.lua:149-153`; `player.lua:18 WALK_FRAMES`).
- **One step at a time, two waiting.** `gen1-bridge/act/kb_move.lua:66 Move.MAX_QUEUE = 2`; a third request drops the
  oldest waiting one, answered `player.blocked{script}` (PROTOCOL.md "Movement rules (M3)"). The driver presses the
  direction only while the classifier says `idle` and the player stands (`kb_move.lua:311-328 begin`).
- **Diagonals.** The client sends one `move.step` per cell boundary the feet cross; a corner cut in one tick sends two,
  in boundary order (`client/move/MovementSync.java:760-771 crossings`). Minecraft normalises diagonal input, so at 45°
  the player crosses boundaries √2 times as often. The hidden player needs 16 frames per step whatever the direction.
- **What the player sees.** A dropped step's `player.blocked` snaps the player to the hidden cell over `SNAP_FRAMES = 6`
  once nothing is pending (`MovementSync.java:414-433`); a disagreement of more than 2 cells, or no answer for
  `STEP_TIMEOUT_MS = 3000` ("lost"), ends in `correct()` → `teleport()` (`:960-1000`); the mover's stuck fallback
  (`ForcedMover.SETTLE_TICKS = 10`) teleports too (`MoveController.afterMove` → `MovementSync.onStuck`, `:505-511`).
  That is the "teleport to the hidden player's location" the user logged.
- **It is not only diagonals.** Any slow frame on either side has the same effect, with less margin at the run's 8 frames.

### 1.2 Per game

| Game | Walk | Run | Surf | Hook for the step length | Where the driver's step starts |
|---|---|---|---|---|---|
| Blue (Gen 1) | 16 | – | 16 | `movement.speed` in `Player:stepLength(dir)` (`src/world/Player.lua:121-145`), ctx `{onBike, slope, dir, surfing, player, input, save}` | `Player:tryMove` (`:149`) from the held direction |
| Crystal (Gen 2) | 16 | – | 16 | `movement.speed` in `World:movePlayer(dir)` (`src/world/gen2/World.lua:10716-10736`), ctx `{onBike, surfing, downhill, playerState, player, input, save}` — **no `dir`**; the vanilla link is `sameFrames` | `World:movePlayer` from the held direction (`gen1-bridge/lib/engine/gen2/move.lua:11-13`) |
| Emerald (Gen 3) | 16 | 8 | **8** (`ordinaryStepFrames`, `player.lua:859-865`) | **none**: `beginStep` and `ordinaryStepFrames` write `Player.stepFrames` (`player.lua:502-531`, `:852-855`) | `Player.update` → `Player.tryMove(dir, game, run)` through the module table (`player.lua:1501-1503`), which the game3 adapter already wraps (`lib/engine/game3/move.lua:56-80 installTap`) |

Two facts from the table matter for the design:

- **Emerald already runs the experiment.** Its hidden surfer steps 8 frames while Minecraft surfs at 16 (`applyMovement`
  gives surfing the walk speed): the hidden player has been 2× ahead on every surf since MG7, through the field-spot
  sweeps (FA, WB8: Emerald 42/42) and the water plan, with no complaint. A hidden player ahead of Minecraft is the
  state this plan creates everywhere.
- **Gen 3's tap is a hook.** `installTap` replaces `Player.tryMove` on the module with a wrapper that calls the original
  and then a sink with `(dir, result, why)`. When the result is `"step"`, `Player.stepFrames` is final for that step and
  `Player.progress` is 0 (`beginStep`, `player.lua:511`; the position is `progress / stepFrames`, `:1362-1372`): a write
  there is the same as a hook's answer.

### 1.3 The bike reports are the mirror image

Since BIKE_PLAN D1 a bike in any game is a **ride**: the client sends no `move.step`; the hidden game drives and the
client walks each `player.pos` it didn't ask for as a `ForcedMover` leg of the engine's frames (`MovementSync.java:196-200
riding`, `ForcedMover.java:49-57`). A leg started late is never caught up: the mover walks every leg at exactly its
frames, so the latency between the key, gen1-bridge, the engine and the client stays as a constant lag. At the Mach's 4
frames a cell (30 blocks a second) 100 ms is 1.5 cells; B0 and B7 measured lag ≤ 2 cells, 4 on Route 111's muddy slope,
with the server's "moved wrongly" and the stuck fallback's teleport there (PROGRESS.md "The bike", B5/B7; BIKE_FOLLOWUPS
A1, A3). So:

- the hidden speed-up **must not** apply to ride steps (it would widen that lag), and it won't: the driver owns no
  step while a ride direction is held (`kb_move.lua:429-437`: steps are refused `script`);
- the ride's lag needs the opposite fix, on the client: a catch-up when legs queue up (Part B). The muddy slope itself
  is a world/builder matter (BIKE_FOLLOWUPS A1) and stays out of this plan.

### 1.4 What is already there

- The 2026-10-03 spec's policy, option, telemetry and test list (HIDDEN_STEP_SPEEDUP.md §3-§6) are reused. Its bike
  rows (uphill Cycling Road, 8 → 4) are obsolete: bikes are rides.
- The driver has what ownership needs: `active` (`{dir, n, push, gen, phase, since, from}`), the `mode` it is stepped
  with, `holds` (`kb_move.lua:89-93`, `:311-328`). Nothing records the last mode yet.
- `status.configure{...}` (`main.lua:829`) is how test drivers set the port; `debug.status` already carries
  `moveDropped`/`moveRepressed` (`:1015`). `mod.options:define` (`:167`) and `mod.options_changed` (`:1275`) exist.
- `main.lua` wraps hooks with `mod.hooks:wrap(name, perf.hook(fn), priority)` (`:765-802`); `Hooks:wrap` sorts by
  priority, highest first = outermost (`src/mods/Hooks.lua:19-26`); a mod's wraps go with it on a hot reload.
- The client's F7 overlay shows the counters this plan must drive to zero (`MovementSync.Stats`: `blocked`, `snaps`,
  `lost`, `drifts`, `steers`, `teleports`).

## 2. The design

### 2.1 The rule (D1, D2)

For a step the bridge drives (a `move.step` the driver is executing, in mode `idle`, not a push):

```
pace   = Minecraft's frames a cell for the hidden player's state: 8 while Emerald runs, else 16
target = ceil(pace * 100 / speedPct)          -- speedPct 200 by default: 8, or 4 while running
frames = max(1, min(frames, target))          -- never longer than the engine wanted
```

| State | Engine's frames | Minecraft's pace | 1X | 1.5X | **2X** | 3X |
|---|---|---|---|---|---|---|
| Walk (all three) | 16 | 16 | 16 | 11 | **8** | 6 |
| Emerald run | 8 | 8 | 8 | 6 | **4** | 3 |
| Blue / Crystal surf | 16 | 16 | 16 | 11 | **8** | 6 |
| Emerald surf | 8 | 16 | 8 | 8 | **8** | 6 |

- 2× leaves the hidden player idle half of each straight cell; a 45° diagonal needs 1.41 steps per 16 frames, about 11.3
  frames of work at 8 per step, so the queue stays empty. The same ratio holds while running (4 of 8).
- `min` means a step another rule made shorter (Emerald's surf) is left alone; 1X reproduces today's lengths exactly.

### 2.2 Which steps (D3)

Shortened: the driver's own `move.step` walk, run and surf steps, including the second half of a step across a map
connection (the seam) and a step chained on the landing poll.

Untouched (Gen 1 timing, so the client's `ForcedMover` animations and cutscenes still match): ledge hops (Gen 1
`player.ledgeHop`; Gen 2 sets 32 after the hook, `World.lua:10701`; Gen 3 `Player.jumping`), spinners and ice, currents,
every scripted or simulated press (cutscenes, door walk-outs, Oak's escort, the Cycling Road roll; the driver holds
nothing outside `idle`), Strength pushes, every ride step (the bike), the user's own presses in the hidden window, NPCs
and followers (their own `stepFrames`; note Gen 3's follower copies the player's, `src/world/game3/Follower.lua:65`, so
it keeps up: fine).

### 2.3 gen1-bridge

**`act/kb_speed.lua` (new, pure).** `Speed.new{pct}` with `apply(frames, c, owns)` (the rule above; `c` is
`{isPlayer, pass, run}` from the adapter, `owns` from the driver), `setPct`, counters `fast` / `passed`.
`Speed.CHOICES = {100, 150, 200, 300}`; anything else is 200, logged once `move.speed_option_invalid`.

**`act/kb_move.lua`.** Record `lastMode` in `driver.step`; add

```lua
-- True while this driver is executing a move.step (pressed, or under way: the seam's second half) in `dir`, in idle.
function driver.ownsStep(dir)
  return active ~= nil and not active.push and lastMode == "idle" and (dir == nil or active.dir == dir)
end
```

`dir` is optional because Gen 2's ctx has none; there the adapter passes the player's facing (the driver turned the
player before pressing, `begin`). Update the header's "lands 16 frames later (8 on the bicycle)" to name STEP SPEED.

**The adapters (`lib/engine/*`), one new interface function `stepContext`** (Engine.GROUPS "field"; the stub returns
nil = unchanged):

- `gen1.lua`: from the hook's ctx: `isPlayer = ctx.player == ow.player`, `pass = player.ledgeHop or player.spinning`,
  `dir = ctx.dir`, `run = false`. Citations `src/world/Player.lua:121-145`, `OverworldController.lua` (the hop flag set
  before `stepLength`).
- `gen2/move.lua`: `isPlayer = ctx.player == ow.player`, `pass = ctx.onBike or ctx.downhill`, `dir = player.facing`,
  `run = false`. Citations `src/world/gen2/World.lua:10716-10736`.
- `game3/move.lua`: no hook, so the **tap's sink** gets a second job: when `result == "step"` and the core's shaper
  says so, write `P.stepFrames`. Add `def("stepShaper", function(fn) shaper = fn end)`; the sink calls
  `shaper(dir, { run = P.running == true, pass = P.jumping or P.biking or P.surfHopping or P.dismounting })` and writes
  the number it returns, if any, with `P.progress == 0` still true. Citations `player.lua:502-531`, `:852-855`,
  `:859-865`, `:1362-1372`; add `Player.stepFrames`/`Player.tryMove` to the adapter's `checkRequired` list so a pin
  change is loud.

**`main.lua`.** Load `kb_speed`; define the option next to BRIDGE PORT:

```lua
{ key = "step_speed", label = "STEP SPEED", type = "choice", default = 200,
  choices = { { "1X", 100 }, { "1.5X", 150 }, { "2X", 200 }, { "3X", 300 } } }
```

Wrap `movement.speed` once, outermost (priority 1000; Gen 1 and Gen 2 raise it, Gen 3 never does):

```lua
mod.hooks:wrap("movement.speed", perf.hook(function(nextFn, frames, ctx)
  local f = nextFn(frames, ctx)
  local c = engine.stepContext(ctx)
  return speed.apply(f, c, c ~= nil and mover.ownsStep(c.dir))
end), 1000)
```

and hand Gen 3 its shaper, the same policy without a hook:

```lua
engine.stepShaper(function(dir, frames, c)   -- the tap calls it with P.stepFrames and {run, pass}
  return speed.apply(frames, c, mover.ownsStep(dir))
end)
```

`mod.options_changed` branch for `step_speed`; `status.configure{stepSpeed}`
for drivers; `debug.status` gains `moveSpeedPct`, `moveFastSteps`, `moveFastPassed`; log `move.speed {pct}` at load and
on change. Steps already under way keep their length.

### 2.4 The client and the server (D4)

No production change. `player.pos.frames` reports the real, shorter length; the client ignores `frames` for the steps it
asked for (`MovementSync.ours`, `:938-957`), and the F7 counters measure the result. One optional JUnit (§4, S5)
documents the cause next to the client code: a 45° path at walking speed against a fake hidden executor that answers
after *k* frames with a queue of 2; *k* = 16 snaps, *k* = 8 doesn't.

### 2.5 Part B: the ride's catch-up (D6; the bike reports)

`ForcedMover` (`client/move/ForcedMover.java`) gains a catch-up: while legs wait behind the current one (`queued() > 0`,
only ever on a ride or a scripted chain), the current leg's remaining frames are scaled by `1 / (1 + queued)`, capped so
one tick never moves more than the server's vehicle tolerance (Minecraft's "moved too quickly" threshold for vehicles),
so a backlog of two legs drains within about a cell instead of staying as a constant lag. `MAX_LEGS` (16) stays as the
far fallback. The ride's drift branch (`MovementSync.tick`, `:… riding`) keeps correcting only when no leg is running.
Measured by `tools/e2e/bike_follow.py`'s "lag per second" columns (B0: 1, 2, 1, 0 … cells) and B7's lag max (2):
the bar is lag ≤ 1 cell after the first second on the Mach and 0 stuck legs. Route 111's muddy slope is excluded (A1:
a world change).

## 3. Decisions (defaults proceed unless the user says otherwise)

| # | Decision | Default | Alternative |
|---|---|---|---|
| D1 | The default STEP SPEED | **2X** (8 frames walking, 4 running) | 1.5X (11 / 6), feels closer to the game in the hidden window but leaves only 1.45× headroom on a diagonal run |
| D2 | The rule | `min(frames, ceil(pace / factor))`: shortens toward Minecraft's pace, never lengthens | the old spec's `min(in, base) / factor` (would take Emerald's surf to 4) |
| D3 | What is shortened | only driver-owned `move.step` walk/run/surf steps in `idle` | also forced moves (rejected: the client animates those with their frames) |
| D4 | Minecraft's side | speeds, protocol, schemas unchanged; one documenting JUnit | a Minecraft-side STEP SPEED too (rejected: Gen 1 feel is the design) |
| D5 | The option | STEP SPEED in the F10 manager (1X … 3X) and `configure{stepSpeed}` for tests; invalid → 2X, logged once | a hidden constant |
| D6 | Part B, the ride catch-up | **in this plan** (small, measurable with the bike tools; it is what the user's bike reports describe) | leave it in BIKE_FOLLOWUPS for a later bike plan |
| D7 | Gen 3 without a hook | write `Player.stepFrames` in the adapter's tap at the step's start, cited; mention a `movement.speed` call in `game3/player.lua` as an upstream request | wait for upstream (no date) |
| D8 | Emerald's surf (already 8 vs 16) | left at 8 (the rule's `min`) | 4 (uniform ratio; no need) |
| D9 | 1X | today's lengths exactly, everywhere | – |

## 4. Phases

Each phase ends with its tests green; pods per the usual rules (small 22-only pods for Lua, the volume's region for the
real engines; stop after each job). Feature tests only; the full suite at the two checkpoints.

- **S0 — the baseline, on the real engines (pod).** `tools/e2e/step_speed.py` (new; modelled on `bike_bridge.py`: a test
  session, the spots drivers `kb_spots`/`kb_g2spots`/`kb_g3spots` for placement, `KB_DATASET_MEMO=30`). For each game it
  sends a 40-cell diagonal staircase (right, up, right, up …) in an open area, each step the moment the previous one
  starts, **at Minecraft's 45° cadence**: one step per 16/√2 ≈ 11.3 frames (188 ms) walking; Emerald again with B held
  (running, 94 ms). Records per game: answers by kind, `player.blocked{script}` count, `debug.status.moveDropped`,
  frames seen, lost answers. Expected today: drops in every game after a few cells, most while running. Places: Blue
  Route 1 / Pallet Town, Crystal Route 29 / 30, Emerald Route 102 / 110 (the sweep files have the open rows).
- **S1 — the policy and the driver (Lua, no engine).** `kb_speed.lua`; `ownsStep` + `lastMode`; the option, `configure`,
  `options_changed`, `debug.status` fields, the log. Tests: `tools/lua/test_lua_speed.py` (new: the table in §2.1 for
  every pct; no change when `owns` false / `pass` / nil context / not the player; never lengthens; whole numbers ≥ 1;
  `setPct` rejects; counters), `test_lua_move.py` (+ `ownsStep`: before the press false; pressed true; the seam's
  "moving" phase true; other dir false; push false; off idle false; chained step true on the landing poll; a ride
  direction held false; no link false), `test_lua_sandbox_main.py` (one wrap at priority 1000, one after a reload, the
  option, the invalid value, the three status fields, schemas valid; the module count +1).
- **S2 — Gen 1 and Gen 2 (Lua).** `stepContext` in `gen1.lua` and `gen2/move.lua`; the `movement.speed` wrap in
  `main.lua`. Tests: `test_lua_gen2_move.py` (a fake `World:movePlayer` ctx with and without `downhill`/`onBike`; the
  player's facing as `dir`), the gen1 sandbox (ctx with `ledgeHop`, `spinning`, another player object).
- **S3 — Gen 3 (Lua).** `stepShaper` in `game3/move.lua`'s tap; `checkRequired` entries. Tests: `test_lua_game3_move.py`
  (the fake player module's `tryMove` returning `"step"` with `running` true/false → `stepFrames` 8/4; `"blocked"`
  untouched; `jumping`/`biking`/`surfHopping` untouched; a hot reload keeps one tap; the shaper absent → untouched).
- **S4 — the real engines (pod; checkpoint 1).** S0's `step_speed.py` again at 2X and at 1X (`configure{stepSpeed}`):
  at 2X zero `script` refusals and `moveDropped` 0 in all three games, answer frames 8 (4 running); `moveFastSteps` = the
  steps; a ledge hop answers `ledge` 32 and bumps `moveFastPassed` (Blue Route 1 / Crystal Route 29 / Emerald Route 111
  (19, 12)); a seam at 2X (`test_crossing_real.py`, `seam_check.py` unchanged); cells visited = steps sent (the per-cell
  logic; Emerald's step-counter facts if the driver exposes one cheaply). The Gen 1 e2e regression (`test_walk.py`,
  `test_route1.py`), `test_crystal.py`, `test_emerald.py`. Then the full suite: `kb-junit`, `gradlew runGameTest`,
  pytest.
- **S5 — the real client (pod, Xvfb).** `bike_ride.py` gains a `--walk-diagonal` pilot mode (W + D held, 20 s, on foot;
  Emerald also with sprint = B): F7's `blocked`, `snaps`, `lost`, `drifts`, `teleports` before (1X) and after (2X) per
  game; the bar at 2X is `lost` 0, `snaps` 0, `teleports` 0 (refusals by walls stay refusals). The optional JUnit of
  §2.4. The field-spot regression (`field_spots.py`) for the three games.
- **S6 — Part B, the ride catch-up (D6; pod; checkpoint 2).** `ForcedMover` catch-up + `ForcedMoverTest` (a backlog of 2
  drains within a cell; a single leg keeps its frames; the per-tick cap), `RideControllerTest` unchanged; `bike_follow.py`
  and `bike_ride.py --e2e` on Route 110's Mach lane and the Acro wheelie: lag per second and lag max before/after, 0
  "moved too quickly", 0 stuck. Then the full suite again.
- **S7 — the record.** PROTOCOL.md "Movement rules (M3)" (`frames` is the hidden step's real length; STEP SPEED shortens
  only driver-owned steps), `gen1-bridge/README.md` (the option), PLAYTEST.md (the items below), PROGRESS.md (a "Step
  speed" section with S0/S4/S5/S6 numbers), ROADMAP.md and NEXT_STAGES.md §1 ("implemented, see STEP_SPEED_PLAN.md"),
  HIDDEN_STEP_SPEEDUP.md's status line ("superseded"), BIKE_FOLLOWUPS A3 (the catch-up; B9 still unbuilt), CLAUDE.md's
  pointer.

**Play-test items (PLAYTEST.md):**
1. Walk diagonally across Pallet Town and Route 1 (Blue), Cherrygrove to Route 30 (Crystal), Littleroot to Route 102
   (Emerald), F7 open: "lost", "snaps" and "teleports" stay at 0 where they climbed before. Emerald again holding sprint.
2. The hidden window shows the player walking twice as fast. Expected; STEP SPEED 1X in the F10 manager brings the old
   pace (and the old rubber-banding) back.
3. Hop a ledge, walk through a door, watch a cutscene: the same timing as before.
4. (Part B) The Mach on Route 110: the bike under you stays within a cell of where the game is; no put-backs on the lane.

## 5. Risks

| Risk | Mitigation |
|---|---|
| A script, warp or trainer sight fires while the Minecraft player is still half a cell back | Already the case for every refusal; `InputLock` holds the player, warps fade, steering settles on the hidden cell (the one the feet are entering). Emerald's surf has run this way since MG7. S5 watches `drifts`/`steers` |
| A `player.pos` the client can't match to its step (`ours` false) walks a forced leg at the short frames | One cell at 2× speed, once; the seam case is covered by `test_crossing_real.py` in S4 |
| Gen 3's follower (`Follower.lua:65`) and running animation speed up with the player | Hidden window only; the follower keeps step by design |
| Another mod wraps `movement.speed` | Ours is outermost and only shortens toward Minecraft's pace |
| A pin change renames `stepFrames`/`tryMove`/`ledgeHop`/`spinning` | Read only in the adapters, cited; `checkRequired` names them; the hook falling away means today's behaviour, not a crash |
| Part B's faster catch-up trips the server's vehicle move check | The per-tick cap; measured with the bike tools (0 "moved too quickly" is the bar, as in B7) |
| The cause on the user's PC is refusals, not drops | F7 tells them apart (`blocked` vs `lost`/`snaps`); S0's baseline and S5 record both |

## 6. Not in this plan

Route 111's muddy slope (BIKE_FOLLOWUPS A1, a world change), the rails (A2, upstream), B9's client pre-move (A3 beyond
the catch-up), a Minecraft-side speed option (D4), diagonal steps in the hidden games, any protocol or schema change.

## 7. Sizes

| Part | Size |
|---|---|
| S1–S3 Lua (policy, driver, three adapters, option, tests) | small: ~250 lines, ~40 tests |
| S0/S4 the e2e tool and runs | small: one tool, three games × two settings |
| S5 the client measure | small: one pilot mode, no production Java |
| S6 Part B | small: one method in `ForcedMover`, tests, two bike runs |
| Pod time | about 2–3 h on a cpu3g x8 ($0.32/hr): under $1 |

## 8. Running it without the user

The recipe is BIKE_PLAN §8's: pods per the rules (create a new 22-only pod when hosts are full), `kb-pod prep`, the
local link, Xvfb for S5/S6, `KB_DATASET_MEMO=30` everywhere; feature tests per phase, the full suite at S4 and S6; a
PROGRESS.md section with the numbers; commit per phase, never push. Defaults D1–D9 proceed; a changed default is noted
in PROGRESS and here (§9).

## 9. Results (2026-10-26, run unattended)

Numbers only; the tables, the reasons and the choices are in PROGRESS.md "Step speed". Pod kb-dev49 (cpu3g x8, $0.32/hr),
about 2.0 h, about $0.65.

- **S0:** 40 steps at 188 ms (94 ms running): `script` refusals / moveDropped Blue 10, Crystal 12, Emerald 11 walking and 11
  running; answers 16 frames (8 running).
- **S1-S3:** Lua 1,668 passed (15 new policy tests, 8 driver, 5 sandbox, 1 Gen 2, 6 Gen 3).
- **S4:** at 2X all three games (and Emerald running) 0 `script`, moveDropped 0, 40 of 40 cells, frames 8 (4 running),
  moveFastSteps 40; at 1X 10, 11, 11, 11 dropped. Ledge: Blue 32 frames + moveFastPassed 1; Crystal and Emerald 64 frames,
  as at S0. E2E `test_walk`, `test_route1`, `test_crystal`, `test_emerald`, `test_crossing_real` green (two checks updated
  for the warp's `elev`). Checkpoint 1: JUnit 1,488 (KbPackReloadTest only), GameTests 141/141, pytest 2,477.
- **S5:** F7 on a diagonal walk, 1X → 2X: refusals Blue 8 → 0, Crystal 8 → 0, Emerald 6 → 0, Emerald sprinting 5 → 0;
  forced legs 16, 16, 12, 3 → 0; teleports (Emerald sprinting) 2 → 0; lost, snaps 0 in all. Field spots 11/11, 12/13
  (known), 42/42; the seam check 5 of 5 steps.
- **S6:** Mach lag max 2/2 before; 2/2, 1/1, 2/2 in three runs after; catch-up ticks 10-16 a run; stuck 0, "moved too
  quickly" 0, "moved wrongly" 0. Checkpoint 2: JUnit 1,493 (KbPackReloadTest and one assertion of mine, fixed: the client
  package then 567, the known failure only), GameTests 141/141 on the rerun (one flake), pytest 2,477.
- **Defaults:** all kept. D6's bar (lag ≤ 1 after the first second) met in 1 of 3 runs on the software client; the catch-up
  stays (no cost measured), the rest is B9 (BIKE_FOLLOWUPS A3). Where the plan was silent: Gen 1's context passes bike steps
  (D3's intent); the functions sit in the "movement" group; Gen 2's ledges count as shortened (its hook runs before the
  ledge test; the engine then writes 32).
