# Spec: hidden-player step speed-up (less rubber-banding)

- **Status:** superseded by [STEP_SPEED_PLAN.md](STEP_SPEED_PLAN.md) (2026-10-26: all three games, implemented; its
  §3–§6 policy, option, telemetry and tests were reused there). Kept for its Gen 1 analysis.
- **Size:** small. gen1-bridge only: one hook, one option, telemetry and tests.
- **Changes to the protocol schemas, the Minecraft mod or the server:** none.
- **Background:** [NEXT_STAGES.md](../NEXT_STAGES.md) section 1.

All `path:line` references were checked on 2026-10-03. Gen1Recomp references are to v0.3.49
(`refs/gen1recomp` is the same source); the rest are to this repository.

---

## 1. The problem

The Minecraft player is sometimes pulled back ("rubber-banding") when they get ahead of the hidden
Gen1Recomp player. The causes, from the code:

- **No headroom.** Minecraft walks at exactly Gen 1's pace. `Rules.WALK_SPEED = 0.1737F` and
  `BIKE_SPEED = 0.3474F` (`fabric-bridge/src/main/java/dev/kantobridge/server/Rules.java:45-47`)
  move one 2-block cell per 16 and 8 Gen 1 frames (PROGRESS.md deviation 33). The hidden step takes
  `stepFrames 16` and `bikeStepFrames 8` (`src/world/FieldDefaults.lua:212-213`).
- **One step at a time, with a short queue.**
  - gen1-bridge runs one step at a time, with `Move.MAX_QUEUE = 2` waiting behind it
    (`gen1-bridge/act/kb_move.lua:51`).
  - A further step drops the oldest waiting one, which is answered `player.blocked{script}`
    (`kb_move.lua:28-30`, PROTOCOL.md "Movement rules (M3)").
- **Diagonals outrun the hidden game.**
  - The client sends one `move.step` per cell boundary its feet cross. A corner cut sends two
    (`client/move/MovementSync.java:18-19`, `:417`).
  - Minecraft normalises diagonal input, so at 45° the player crosses grid cells √2 ≈ 1.41× as fast
    as walking straight.
  - The hidden player needs 16 frames per step, whatever the direction. So it falls about 0.4 cell
    behind per cell walked diagonally; after a few cells a step is dropped. The hidden player then
    ends a cell behind, and `MovementSync` snaps the Minecraft player back once nothing is pending
    (`SNAP_FRAMES = 6`, `MovementSync.java:89`).
- **The Cycling Road, uphill.** On a slope map, a bike step that isn't downhill takes the *walking*
  length (`src/world/Player.lua:128-129`). Minecraft keeps bike speed, so it is twice as fast as the
  hidden player there today.
- **A hidden slow frame** has the same effect as a diagonal.

The other corrections are legitimate and stay: refusals by NPCs, tiles or scripts, and drift
steering after `DRIFT_MS = 500` (`MovementSync.java:71`).

## 2. Goal and non-goals

**Goal:** the hidden player always finishes a bridge-driven step before the Minecraft player reaches
the next cell, including on diagonals and the Cycling Road. The queue then never fills and steps are
never dropped.

**Non-goals:**
- Minecraft's speeds are unchanged.
- No diagonal steps in the hidden game.
- The protocol is unchanged.
- No change for moves the game makes by itself: cutscenes, hops, spinners, currents, the Cycling
  Road roll.
- No change for the user walking in Gen1Recomp's own window.

---

## 3. Design

### 3.1 The hook

Gen1Recomp passes every player step length through the public mod hook **`movement.speed`**:

```lua
-- src/world/Player.lua:121-145 (Player:stepLength(dir)), abridged
local frames = onBike and self.bikeStepFrames or self.stepFrames or STEP_FRAMES
if slope then frames = self.stepFrames or STEP_FRAMES end
if Runtime.wantsHook("movement.speed") then
  frames = Runtime.call("movement.speed", function(f) return f end, frames, {
    onBike = onBike, slope = slope, dir = dir, surfing = self.surfing and true or false,
    player = self, input = Game.input, save = save })
end
return math.max(1, math.floor(tonumber(frames) or STEP_FRAMES))
```

- **Documentation:** the comment at `Player.lua:118-120` says the hook "lets a mod multiply or
  replace that (running shoes, dash, etc.)". It is listed among the mod hooks in
  `refs/gen1recomp/docs/mod-api-gen2-compat.md:538`.
- **Gen 2:** Gold/Silver/Crystal call the same hook with the same context keys
  (`src/world/gen2/World.lua:10665-10673`), so this design carries over to a later Gen 2 bridge.
- **Registration:** `mod.hooks:wrap(name, callback, priority)` (`src/mods/Loader.lua:1390-1392`).
  - The chain calls `callback(nextFn, frames, ctx)`.
  - Higher priority runs first, i.e. outermost (`src/mods/Hooks.lua:19-37`).
  - A link that throws is logged and skipped (`Hooks.lua:62-104`).
  - The mod's wraps are removed with the mod on a hot reload: the owner is the mod id.
- **Position math:** the engine moves the player by `floor(progress * 16 / stepLen)` pixels
  (`Player.lua:243`), so any whole number of frames from 1 up is valid.

### 3.2 Which steps are shortened

Every call to `stepLength` reaches the hook, so gen1-bridge must recognise its own steps.

| Move | Where `stepLength` is called | Shortened? | How it's recognised |
|---|---|---|---|
| A `move.step` walk, bike or surf step | `Player:tryMove` (`Player.lua:183`) | **yes** | the step driver owns the step (§3.4) |
| The second half of such a step across a map connection (the seam) | `OverworldController.lua:2153` | **yes** | same; the driver's step is still active |
| A bike step uphill on the Cycling Road (`slope`) | `Player.lua:128-129` | **yes**, to bike pace (§3.3) | same |
| A ledge hop started by a bridge step | `OverworldController.lua:2016-2017, 2028-2029` (`ledgeHop = true`, then `stepLength() * 2`) | **no** | `player.ledgeHop` is true |
| A spinner tile | `Player.lua:131-132` (`spinning`) | no | `player.spinning`, and the driver doesn't own it |
| A scripted or simulated pad press (cutscenes, Oak's escort, door walk-outs, the Cycling Road roll, starting to Surf) | `OverworldController.lua:5710` | no | the driver owns no step outside "idle": every hold is released when the state leaves idle (`kb_move.lua:36-38`) |
| Seafoam currents | the scripted path | no | same |
| The user walking in Gen1Recomp's own window | `Player.lua:183` | no | the driver holds no direction |
| A Strength boulder push (`push:true`) | — (the player stays) | no | the active request has `push` set |
| NPCs, and Yellow's Pikachu | their own `stepFrames` (`src/world/NPC.lua:9, :109`) | no | not `Player:stepLength`; only the player reaches the hook |

**Why ledge hops stay unchanged:** the client animates every move it didn't ask for, ledge hops
included, with the hidden move's `frames` (`MovementSync.java:288-294` → `forced(...)`). A halved
hop would make the Minecraft player hop twice as fast. Hops, spins, currents and scripts therefore
keep Gen 1's timing, so the client's animations and cutscenes still match.

### 3.3 The formula

```
base   = ctx.onBike and player.bikeStepFrames or player.stepFrames   -- Minecraft's pace: 8 or 16
frames = max(1, ceil(min(in, base) * 100 / speedPct))
```

- **`in`** is the length after any inner hook links.
- **`min(in, base)`** brings a Cycling Road uphill bike step (16) to bike pace (8) before speeding
  up. That fixes today's 2× mismatch there, and it never lengthens a step another mod made shorter.
- **`speedPct`** is the STEP SPEED option (§3.5): 100, 150, 200 or 300. The default is **200**.

| `speedPct` | walk and surf | bike | Cycling Road uphill on the bike |
|---|---|---|---|
| 100 (off) | 16 | 8 | **8** (the bike-pace fix still applies) |
| 150 | 11 | 6 | 6 |
| **200** | **8** | **4** | **4** |
| 300 | 6 | 3 | 3 |

- **Is 2× enough?** Straight walking leaves the hidden player idle half of each cell. A 45° diagonal
  needs about 1.41 steps per 16 frames, which is about 11.3 frames of work at 8 frames per step.
  The queue therefore stays empty.
- **What 1× does:** it reproduces today's lengths everywhere except the Cycling Road uphill fix.
  That's the rollback setting. Whether 100 should skip the `min` fix too is open question 1 in §9.

### 3.4 Recognising the bridge's own steps

Timing:
- gen1-bridge runs its step driver at the start of every logic step, from its `input.step` hook,
  before calling `nextFn` (`gen1-bridge/main.lua:729-739`).
- The driver presses the direction with `mod.input:press` while the game is idle and the player
  stands still (`kb_move.lua:19-23`).
- "The overworld polls held directions … in that same logic step", so `Player:tryMove` and the hook
  run while the driver's state describes this step.
- On the landing poll, a queued step chains in the same logic step, with its direction pressed
  first (`kb_move.lua:24-27`).

The state the hook needs is already in the driver:
- `active` is the request being executed, with `{dir, n, push, gen, phase, since, from}`
  (`kb_move.lua:288`, `:307`);
- `holds` maps each button to the press token gen1-bridge owns;
- the last classified mode is available too.

Add one query:

```lua
-- act/kb_move.lua, inside Move.new
-- True while this driver is executing a move.step in `dir` that it is pressing right now, in "idle".
function driver.ownsStep(dir)
  return active ~= nil and not active.push and active.dir == dir
     and holds[dir] ~= nil and lastMode == "idle"
end
```

Notes:
- `lastMode` is the mode `driver.step` was last called with. Record it there.
- `tryMove` passes its `dir`, while scripted presses call `stepLength()` without one. That makes
  `ctx.dir` the facing, but the direction check plus `holds` and "idle" already exclude scripts.

### 3.5 The option

Define it next to BRIDGE PORT (`main.lua:81-83`). `choice` rows are supported by the manager
(`src/mods/ManagerState.lua:1167-1189`):

```lua
mod.options:define({
  { key = "port", label = "BRIDGE PORT", type = "number", default = 27151, min = 1024, max = 65535, step = 1 },
  { key = "step_speed", label = "STEP SPEED", type = "choice", default = 200,
    choices = { { "1X", 100 }, { "1.5X", 150 }, { "2X", 200 }, { "3X", 300 } } },
})
```

- **Reading it:** at load, with `mod.options:get("step_speed")`. Anything other than 100, 150, 200 or
  300 means 200, logged once as `move.speed_option_invalid`.
- **Changing it:** the existing `mod.options_changed` handler (`main.lua:896-905`) gains a branch for
  `payload.key == "step_speed"` that applies at once. Steps already under way keep their length.
- **Tests:** a driver or test session can set it through `exports.configure{ stepSpeed = pct }`, the
  same way it sets the port.

### 3.6 Reading the engine's player object

The hook hands us `ctx.player`, an engine object. Reading `ledgeHop`, `spinning`, `stepFrames` and
`bikeStepFrames` is engine-internal access, so it belongs in `lib/kb_engine.lua` with citations
(CLAUDE.md: only that file touches engine internals):

```lua
-- lib/kb_engine.lua
-- The player facts movement.speed needs (src/world/Player.lua:121-145; the hop flag is set
-- before its stepLength call, src/world/OverworldController.lua:2016-2017, :2028-2029).
function engine.stepContext(ctx)
  local p = type(ctx) == "table" and ctx.player or nil
  if type(p) ~= "table" then return nil end
  return {
    isPlayer = (p == livePlayer),                      -- the overworld's player (observe() keeps it)
    ledge    = p.ledgeHop and true or false,
    spinning = p.spinning and true or false,
    onBike   = ctx.onBike and true or false,
    base     = (ctx.onBike and tonumber(p.bikeStepFrames)) or tonumber(p.stepFrames) or 16,
    dir      = ctx.dir,
  }
end
```

`livePlayer` is the overworld player that `engine.observe()` already records (`kb_engine.lua:445`).
A `nil` context or an unknown player means "unchanged".

### 3.7 The policy module and the hook

Keep the decision in a small pure module so lupa can test it without the engine:

```lua
-- act/kb_speed.lua
local Speed = {}
Speed.CHOICES = { [100] = true, [150] = true, [200] = true, [300] = true }

function Speed.new(opts)            -- opts.pct (default 200)
  local s = { pct = Speed.CHOICES[opts and opts.pct] and opts.pct or 200,
              counts = { fast = 0, passed = 0 } }
  -- frames: the length after inner links; c: engine.stepContext(ctx); owns: driver.ownsStep(c.dir)
  function s.apply(frames, c, owns)
    if not (c and c.isPlayer and owns) then return frames end
    if c.ledge or c.spinning then s.counts.passed = s.counts.passed + 1; return frames end
    local base = math.min(frames, c.base)
    local out = math.max(1, math.ceil(base * 100 / s.pct))
    if out ~= frames then s.counts.fast = s.counts.fast + 1 end
    return out
  end
  function s.setPct(pct) if Speed.CHOICES[pct] then s.pct = pct; return true end return false end
  return s
end
return Speed
```

```lua
-- main.lua, next to the other hooks (main.lua:519-560, :857)
-- Outermost (priority 1000), so it shapes the final length after any other mod's link.
mod.hooks:wrap("movement.speed", perf.hook(function(nextFn, frames, ctx)
  local f = nextFn(frames, ctx)
  local c = engine.stepContext(ctx)
  return speed.apply(f, c, c ~= nil and move.ownsStep(c.dir))
end), 1000)
```

`perf.hook` is the M10 per-step cost wrapper the other hooks use. The hook is cheap: a few table
reads per step start.

### 3.8 Telemetry

`debug.status` values are integers or booleans (`protocol/messages/debug.status.schema.json`). Add:
- `moveSpeedPct`: the current option;
- `moveFastSteps`: bridge steps shortened;
- `moveFastPassed`: bridge steps left at Gen 1 timing (hops, spins).

Log `move.speed` `{pct}` once at load and on every change. As always, log codes and numbers only.

---

## 4. Effects

**What changes:**
- `player.pos.frames` reports the real, shorter length, because the observer reads `stepFramesCur`
  (`kb_engine.lua:455`). The client ignores `frames` for its own steps (`MovementSync.java:27-28`: a walk
  "this side asked for and Minecraft is already making needs nothing"; code at `:281-286`), so Minecraft is unaffected.
- **The hidden player is slightly ahead.** It lands before the Minecraft player reaches the cell's
  centre. A door warp, a trainer's sight check or a sign script starts up to about half a cell
  earlier than now, relative to Minecraft. The existing logic already covers this: `InputLock` holds
  the player while the game isn't idle, warps fade, and steering or snapping settles the player on
  the hidden cell, which is the same cell the Minecraft feet are already in.
- **Refusals arrive sooner,** so snap-backs get shorter.
- **The Gen1Recomp window** shows the player walking twice as fast. The user glances at it, so
  mention this in PLAYTEST.

**What doesn't change:**
- **Game logic.** Gen 1 counts steps, not time, so each cell is still visited once: surf and
  fishing encounters, trainer sight lines, coordinate triggers, and the poison, daycare, Safari and
  bike counters are untouched.
- **Wandering NPCs** move by time, so they move about half as far per player step. Their puppets
  follow the hidden NPCs at the NPCs' own pace.
- **The protocol,** the client and server code, and Minecraft's speeds.

---

## 5. Files to change

| File | Change |
|---|---|
| `gen1-bridge/act/kb_speed.lua` | **New.** The policy (§3.7) |
| `gen1-bridge/act/kb_move.lua` | `driver.ownsStep(dir)`; record `lastMode` in `driver.step`; update the header comment "lands 16 frames later (8 on the bicycle)" (`:21-23`) to mention STEP SPEED |
| `gen1-bridge/lib/kb_engine.lua` | `engine.stepContext(ctx)` with citations (§3.6) |
| `gen1-bridge/main.lua` | Load `kb_speed`; define the option; the `movement.speed` wrap; the `options_changed` branch; `exports.configure{stepSpeed}`; the three `debug.status` fields |
| `gen1-bridge/README.md` | The option, and what it does and doesn't shorten |
| `tools/lua/test_lua_speed.py` | **New.** Policy tests (§6.1) |
| `tools/lua/test_lua_move.py` (or the driver test file) | `ownsStep` cases (§6.1) |
| `tools/lua/test_lua_sandbox_main.py` | The module list: one more module (`kb_speed`); `test_s5_ready` counts modules too (now 30, then 31) |
| `tools/e2e/test_speed.py` | **New.** The real-engine test (§6.2) |
| `docs/PROTOCOL.md` | "Movement rules (M3)": `frames` is the hidden step's real length; STEP SPEED shortens only `move.step` steps. No schema change |
| `docs/PLAYTEST.md` | Play-test items (§6.4) |
| `docs/PROGRESS.md` | A deviation entry and the gate result when done |
| `docs/NEXT_STAGES.md` | §1: point here, status "implemented" |

**No change to:**
- `fabric-bridge/**`;
- `protocol/**` schemas;
- `tools/fake_gen1`. It keeps 16 frames. Optionally, a `step_frames` setting would make stand-in
  answers look like the speed-up, but the client never uses `frames` for its own steps.

---

## 6. Tests

### 6.1 lupa (`tools/lua`)

**The policy (`kb_speed`):**
- 200: walk 16 → 8, bike 8 → 4, uphill bike (in 16, base 8) → 4, surf 16 → 8.
- 150: 16 → 11, 8 → 6.
- 300: 16 → 6, 8 → 3.
- 100: unchanged, except uphill bike 16 → 8.
- No change when: `owns` is false; `ledge` or `spinning` is true; the context is `nil`;
  `isPlayer` is false.
- Never lengthens: `in = 5` with base 16 at 100 stays 5.
- Results are always whole numbers ≥ 1.
- `setPct` rejects other values, and the counters count.

**The driver (`ownsStep`):** a fake engine and the existing driver harness.
- Before the press: false.
- While pressing `dir` in idle: true.
- A different direction: false.
- A push request: false.
- After the state leaves idle (holds released): false.
- On the landing poll with a chained step: true for the new direction, in the same logic step.
- With no client connected: false.

**main.lua in the sandbox:**
- The hook is wrapped once, at priority 1000.
- Reloading the mod leaves exactly one wrap.
- The option is defined, and an `options_changed` event for `step_speed` changes the percentage.
- An invalid value falls back to 200, logged once.
- `debug.status` carries the three fields.
- Every message stays schema-valid.

### 6.2 End to end on the test install (`tools/e2e/test_speed.py`, marker `e2e`)

**Paced, not driver-run.** A test driver turns frame pacing off and runs logic once per frame (the
M10 finding in PROGRESS.md deviation 108), so wall-clock cadence means nothing there. Use
`launch_plain()` from `run_gen1recomp.py`, which runs at a real 60 logic steps a second, the way
`test_hidden.py` does.

**The scenario:**
1. Start a New Game in a driver-free way, or use a save that `test_hidden`'s fixtures already make.
   If neither exists, fall back to a driver run that reports logic-step timestamps and space the
   requests in logic steps.
2. Find an open area: grass encounters are suppressed while a test session is connected.
3. Send a 40-cell diagonal staircase (right, up, right, up…). Pipeline the steps at Minecraft's
   diagonal cadence: one step every 16 / √2 ≈ 11.3 frames, about 188 ms, sent the moment the
   previous one starts. That's what the client does at 45°.
4. Run it twice: at **2×** (the default) and at **1×** (`exports.configure{stepSpeed = 100}`).

**Assertions (counts only):**
- **At 2×:** every step is answered `player.pos`, with zero `player.blocked{script}` answers.
  `debug.status` shows the driver's `dropped` = 0 and `moveFastSteps` = 40. Answer frames are 8.
- **At 1×:** it records the baseline drops. The test asserts that 2× has fewer, and logs both
  numbers. If 1× happens to drop nothing at this cadence, the test says so and doesn't fail.
- **A ledge hop at 2×:** the answer's kind is `ledge` with 32 frames, and `moveFastPassed`
  increases.
- **On the bike** (driver setup gives a bike): answer frames are 4.

### 6.3 Java (optional)

Pure JUnit: a small simulation feeding `MovementSync` a 45° Minecraft path at walking speed.
- A fake hidden executor with a queue of 2 answers each step after *k* frames.
- With *k* = 16 it counts snaps.
- With *k* = 8 it asserts none.

This documents the cause and the fix next to the client code. No production Java changes.

### 6.4 Play-test (append to PLAYTEST.md)

1. Walk diagonally across Pallet Town and Route 1 with F7 open. "Move … lost" and "Fixes … snaps /
   drift" should stay at or near 0, where before they climbed.
2. Ride uphill on the Cycling Road: no pull-backs.
3. Hop a ledge and walk through a door: they look the same as before.
4. Gen1Recomp's window shows the player walking faster. That's expected.
5. Set STEP SPEED to 1X in the F10 mod manager: the old behaviour (and its rubber-banding on
   diagonals) returns.

---

## 7. Acceptance criteria

1. At the default 2×, bridge-driven walk and surf steps take 8 frames, bike steps 4, and uphill
   Cycling Road bike steps 4. Ledge hops (32), spinners, currents, the Cycling Road roll, cutscene
   and scripted moves, and steps taken in Gen1Recomp's own window keep Gen 1's timing.
2. The e2e diagonal staircase at 2× drops no steps.
3. Per-cell game logic is unchanged: the engine's own step-based counters advance once per cell.
   Check one, such as the poison or daycare step counter, through a driver probe if one is cheap.
   Otherwise this rests on the lupa tests and the play-test.
4. Exactly one wrap exists, through hot reloads. STEP SPEED changes apply at once and are logged.
5. `tools/check --e2e` is green. No schema or Java change.
6. The play-test items in §6.4 pass.

## 8. Risks

| Risk | Mitigation |
|---|---|
| A script or warp starts while the Minecraft player is still mid-cell | Already handled by `InputLock`, warp fades and steering; covered in the play-test |
| Another Gen1Recomp mod also wraps `movement.speed` | Ours is outermost (priority 1000) and only ever shortens toward Minecraft's pace (`min`) |
| A Gen1Recomp update renames `ledgeHop`/`spinning` or moves `stepLength` | They are read only in `kb_engine.stepContext`, with citations. CLAUDE.md already requires re-checking internals on a pin change. If the hook disappears, behaviour falls back to today's |
| A user dislikes the fast walking in Gen1Recomp's window | The 1X setting restores it |
| The cause is something else on the user's machine (refusals, not drops) | F7 counters tell them apart: "blocked" for refusals, "lost" and "snaps" for drops. If refusals dominate, this spec doesn't address them; record it |

## 9. Open questions for the user

1. **The default:** 2× (recommended) or 1.5×?
2. **The 1X setting:** should it also undo the Cycling Road uphill fix? It is applied even at 100
   because it only removes a mismatch. Recommended: keep it.
3. **Another axis:** should STEP SPEED also be offered on the Minecraft side (a faster Minecraft
   walk with the hidden player sped up further)? Recommended: no. Minecraft's pace is the design's
   Gen 1 feel.
