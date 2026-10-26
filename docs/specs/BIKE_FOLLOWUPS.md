# The bike: what is left (input for a later plan)

Written 2026-10-26, after BIKE_PLAN.md B0–B8 ran unattended (commits 0887a35..d564f4d). Each item says what is wrong,
the evidence (numbers from PROGRESS.md "The bike" and the plan's §9), where to look, and ideas. Nothing here is
decided; the order is roughly by how much it matters to a player.

## A. Riding doesn't match the game

**A1. Route 111's muddy slope at full Mach speed.** The engine climbs it from (29, 11) to (29, 3) at 16, 8, then 4
frames a cell; the Minecraft bike falls behind (lag 4 cells), the server answers "moved wrongly" 8–9 times (0.33 blocks
each) and the mover's stuck fallback fires once. Raising the bike's step from 0.6 to a full block changed nothing (it
was reverted). Every other ride in B5 and B7 had 0 complaints.
- Likely cause: the slope was built for walkers, who are always pushed back down it, so its climb in the world may be a
  step or wall the bike can't ride. Not checked: the built heights at Route 111 (29, 3..8) and Granite Cave B1F's slope.
- Where: the levels pipeline / `WorldPlan` for muddy-slope cells; `BikeEntity` (step, collisions);
  `ServerGamePacketListenerImpl.handleMoveVehicle` (why the server's own move of the bike stops short).
- Ideas: a dump of those cells' heights and colliders; a slope collider that opens for a biker at full speed (as the
  waterfall's foot wall opens while `forced`); or let the bike be teleported along the slope (a forced-move kind).
- **Checked 2026-10-26** (LandSky6's region files and the levels dump `levels_wb6_pin`, the same table and options; the
  dump on the volume in `/workspace/reports/muddy/`): Route 111's column 29 is level 15 (floor y 88) from (29, 8) south and
  level 21 (y 94) from (29, 5) north; the 6-block climb is two ramp cells, (29, 7) and (29, 6), rise 3 each, built as
  `step_collider` low3/high3 stairs: six half-block stairs a third of a block long per cell, **1.5 blocks up per block
  forward**. Every outdoor muddy slope is built alike (Route 111 (17, 36-37), Route 115 (28, 8-12), Route 119 (6, 54-55),
  the Safari Zone southwest (8, 0-3), rise 2-3 a cell); Granite Cave B1F's muddy column is flat (y 49). **The cause:**
  Minecraft lifts an entity at most its step-up (0.6) per tick; the Mach moves 1.5 blocks a tick, which on this slope needs
  about 2.25 blocks of climb a tick, so the bike stalls on the stairs whatever its step-up (1.0 also failed). A gentler
  ramp can't fix it: even spread over all four muddy cells the slope needs 1.1 blocks a tick.
- **The fix this points to:** let the ride's forced legs climb: give a leg the target cell's floor height (the levels and
  the ramp lift the WorldIndex already has, `toBlock`/`rampLift`) and have the mover add the vertical share to its velocity
  when the floor ahead rises more than step-up allows, so collision resolves the lift first and the forward move clears the
  stairs; the server re-simulates the same move. Bars: the Mach up Route 111 (29, 11) to (29, 3) with 0 "moved wrongly",
  0 stuck, lag at most 1; walkers and the other rides unchanged.
- **Done 2026-10-26** (BIKE_CLIMB_PLAN.md C1-C4; PROGRESS "The bike", "The climb"): in the entity rather than the mover,
  since step-up keeps the first (lowest) candidate step and a client-side lift alone would leave the server's own move
  short: `BikeEntity.move` lifts the move onto the step colliders' top along its path when that is more than the step-up
  (`BikeClimb`; stairs only, at most 5 blocks), and follows stairs down under the move's end; both sides run it. The bars
  were met three times (lag 1, 0 complaints, 0 stuck, ends in (29, 3)); the slope ridden down too (19 cells, lag 1).

**A2. Route 119's rails.** The engine (Gen1Recomp v0.3.85) lets the Acro hop once, to (10, 7), then stops at the rail;
across the rail from (11, 6) is refused; the Mach stops there too. B0's other start under a rail, (5, 12), is a cell the
client can't reach.
- Where: `refs/gen1recomp` `bike/rse.lua` `wheelieHoppingMoving`, `Rse.collision` (it asks `Collision.canEnter`, so a
  rail cell may come back `IMPASSABLE` rather than a rail collision), compared with pokeemerald's `bike.c`.
- Ideas: try the other 63 rail rows of the FA sweep; if the engine is wrong, it is upstream's (report it), not ours (D5).

**A3. The feel (latency).** From the key to the seat's first motion: 0.26–0.38 s on the pod's software client at about
10 frames a second (it runs ticks two at a time). The plan's bar was 150 ms. Not measured on real hardware.
- Ideas: measure on the GPU pod (kb-gpu1, NVIDIA L4) with `tools/e2e/bike_ride.py`; if it's still slow, B9 (the client's
  one-cell pre-move, BIKE_PLAN §4) is designed and ready to build. Also check the side-jump assist: a sneak tap with no
  direction is held back 2 ticks (100 ms) before it goes out.
- **2026-10-26, STEP_SPEED_PLAN Part B:** `ForcedMover` now catches up while legs wait (1 + waiting ticks' frames a tick, at
  most 3, at most 3 blocks a tick). On the software client the Mach's lag max was 2/2 before and 2/2, 1/1, 2/2 in three
  runs after (catch-up ticks 10-16 a run, 0 stuck, 0 server complaints): the backlog drains, the latency stays.
- **2026-10-26, the GPU pod** (kb-gpu1, L4, 340-400 fps): Mach lag max 1 in 3 of 3 runs with the catch-up (one 2 in the
  first second without it); key to the seat's first motion 233-307 ms for the Mach, 793-917 ms for the wheelie (its B tap
  waits first). (Those latencies were mostly the pilot's own sampling: see below.)
- **2026-10-26, B9 built** (PROGRESS.md "The bike", B9): on the client's own clock the engine's first step arrives about
  50 ms after `move.ride`; the seat's first motion went from 100 ms to 50 ms with the pre-move (RTX 4090 pod). **A3 is
  closed on the pod**; the feel on the user's PC is the play-test item. Still open here: the side-jump assist's 2-tick wait
  for a B tap with no direction (B with a direction is never pre-moved).

**A4. The Cycling Road deck end to end** wasn't shown: from Route 110 (27, 22) down, a Cycling Road trainer stands in
that column after 9 cells (24 bumps). That's the game's object, so correct, but the full ride needs another lane or a
route round the trainers.

## B. Not driven yet

- **B1. Blue's forced bike:** getting off on Route 17 should be refused (`not_here`) when you came in through the gate. The
  spots driver warps in without the gate's forced-bike flag, so the test got off fine. Needs a driver `forced_bike` option
  or a ride in through Route 16's gate.
- **B2. Blue's roll after a brake** started again only when a direction was pressed (seen in B6). Check against the
  original game whether that's right.
- **B3. Crystal: riding into Route 16's gate** took no step and no answer: either the engine refuses a bike there or a
  script held the field. Unresolved.
- **B4. Caves** with a bike in Blue and Crystal (allowed and refused ones) weren't tried.
- **B5. The Bicycle key with the real items:** the tests gave the bike through the drivers' `give_bike`; with both the
  Mach and the Acro in Emerald's bag the adapter uses whichever it finds first (`Field3.BIKES` order); a successful
  `bike_on`/`bike_off` shows nothing of ours (only the game's text and the seat).

## C. The look and the model (placeholder art)

- **C1.** The wheels are 8-pixel discs and read a little square; the rider's legs stick forward in Minecraft's sitting
  pose. Bigger wheels need more texture room (the textures are 64 × 32; `tools/bike_textures.py` draws them).
- **C2.** A standing bunny hop and the wheelie are drawn as pose (other players see them through the synced trick), not
  as the entity's own motion; the Mach's gear isn't relayed (other players' wheels turn with the distance).
- **C3.** A warp replaces the bike (the old one goes, a fresh one is under the rider the next tick): may show a one-tick
  flicker; not looked at.
- **C4.** The second client's view only proved the bike is there (its count, the video at a distance); a close-up of
  another player's wheelie and hop wasn't taken.

## D. Smaller choices to revisit

- **D1.** A bump's reason is always `tile` (the engine's collide action doesn't say what it hit).
- **D2.** Free mode (D8) is only out in a Wild Routes zone; on a map the world doesn't have, the rider is held like a
  walker.
- **D3.** Hop lifts are tuned so the seat lands within its leg: low 0.22, normal 0.27, high 0.42 blocks a tick.

## E. Not the bike's, seen on the way

- **E1.** `KbPackReloadTest.modelsReadFitAndStayWithinTheBudget` fails (it failed before this plan too).
- **E2.** Crystal's Ice Path ladder spot fails (a 3D cave's ladder: the client climbs instead of stepping; known).
- **E3. Suspected, not verified:** for Gen 3 walking ledge hops, `observe/kb_player.lua` `stepStart` sends the landing as
  the step's target plus one more cell, but Emerald's `Player.targetX/Y` is already the landing (two cells on). The spots
  still pass (the client is corrected afterwards), so check whether the client overshoots and is pulled back.
- **E4. Pod practice:** two Gradle runs from a copied checkout broke the tests (stale build classes, 0 tests counted);
  run the suite and the client one after the other. `pkill`/`pgrep -f` patterns must not match your own command line
  (killing `GradleDaemon` also killed a running client).
