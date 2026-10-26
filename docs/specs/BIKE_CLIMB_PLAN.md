# The bike's climb: BIKE_FOLLOWUPS A1 (Route 111's muddy slope at full Mach speed)

Written 2026-10-26 from the A1 check (commit b8bd649) and a read of the code it points at. Nothing here is built yet.

## 0. In one paragraph

The engine rides the Mach up Route 111's muddy slope at 4 frames a cell, which is 1.5 blocks a tick. The built slope is two
ramp cells of `step_collider` stairs (six half-block steps a cell, 1.5 blocks up per block forward), so a tick of riding
needs about 2.25 blocks of climb. Minecraft's step-up gives at most one step candidate a tick and takes the **first**
(lowest) candidate that gains any ground, so the bike gains a third of a block a tick whatever `maxUpStep` is (0.6 and
1.0 both failed: A1). The fix: when the floor ahead of the bike rises more than its step-up, **lift the bike first, then
move it forward**, inside `BikeEntity.move`. The rider's client and the server's re-simulation of the vehicle packet run
the same override, so they agree by construction and "moved wrongly" can't fire. Walkers, the other rides and the world
are untouched.

## 1. What the check found (b8bd649, PROGRESS "The bike", BIKE_FOLLOWUPS A1)

- Route 111 column 29: level 15 (floor y 88) from (29, 8) south, level 21 (y 94) from (29, 5) north; (29, 7) and (29, 6)
  are ramp cells of rise 3, built as `StepColliderBlock` LOW3 (thirds 8, 16, 24 px over the cell's first block) and HIGH3
  (on two full blocks: a flat third, then 8 and 16 px) climbing north: stair tops 0.5, 1.0, 1.5 | 2.0, 2.5, 3.0 blocks
  over a cell's 2 blocks. Every outdoor muddy slope is built alike; Granite Cave B1F's is flat.
- The Mach's steps on it: 16, 8, then 4 frames a cell; at 3 frames a tick that is 0.75 cell = 1.5 blocks a tick forward and
  2.25 blocks of rise. The client bike stalled on the stairs (lag 4 cells, 8–9 "moved wrongly" of 0.33 blocks, one stuck
  fallback); the full-block step changed nothing.
- `ForcedMover` is horizontal only (`Velocity(x, z)`); `MoveRuntime.beforeMove` sets the seat's x/z and keeps its y (gravity
  and step-up as when walking); `RideHop` is the only thing that gives the seat an upward velocity (a hop).

## 2. Why step-up can't be made to do it

`Entity.collide` (1.21.1): the move is resolved y first, then x/z; if the horizontal part was cut and the entity was on
the ground, the step-up collects the colliders' top heights within `maxUpStep` **in ascending order** and retries the
horizontal move from the **original** box at each height, returning the **first** that goes further. On half-block stairs
the first candidate is always the next step (0.5 up, a third of a block on), so a tick gains one or two thirds whatever
the step height. The retry also replaces the move's own y with the candidate, so a lift folded into the velocity doesn't
help once the horizontal part is cut. The lift has to come **before** the collide: a y the collide resolves first, big
enough that the horizontal pass is already clear.

## 3. The design

### 3.1 Where: `BikeEntity.move(MoverType, Vec3)` (D1)

Override `move`: before `super.move`, look at the floor under the bike's footprint at the point the move ends (x + dx,
z + dz) and, if it stands higher than the feet by more than `STEP` (0.6) but no more than `MAX_LIFT`, raise the move's y
to that height (`max(dy, floorAhead - y)`); then `super.move`. The vertical is resolved first (open air above a slope),
the horizontal pass clears the stairs, gravity next tick finds the bike on its step. Same code on the rider's client
(the controlling instance, `MoverType.SELF`) and in the server's `handleMoveVehicle` (`MoverType.PLAYER`, the packet's
delta already carries the lift: the override computes the same lift from the same start and end, so it changes nothing
there and the server's own move lands where the client's did).

Why the entity and not the mover (A1's first idea): the mover has no world and lives on the client only; a client-side
lift would need the server's vanilla step-up to accept the move, which §2 says it may not when the lift lands short of a
step top. One place, run on both sides, is the symmetric fix. `ForcedMover`, `MovementSync`, the protocol and the world
stay as they are.

### 3.2 The floor ahead (D2)

A collision sweep, not a cell lookup: take the bike's box moved by (dx, 0, dz), raise it by `MAX_LIFT`, and sweep it down
by `MAX_LIFT` against the level's block collisions (`Shapes.collide(Axis.Y, box, shapes, -MAX_LIFT)`); the sweep stops on
the highest collider top under the footprint (exactly: a step's own top, a plateau, a slab), so there is no residue for
step-up to take and the bike lands on the stair, not in the air. `level.getBlockCollisions(this, box)` gives the shapes;
the ledge collider and the bike wall are in them, so they are handled by D3, not climbed.

### 3.3 Only stairs (D3)

The lift applies only when a `StepColliderBlock` lies within the footprint's columns between the feet and the feet +
`MAX_LIFT` (a block scan of at most 2 × 2 × 6 positions a tick while moving). Then a bike climbs the world's stairs at any
speed and nothing else: a bike wall still stops it (its shape reaches the feet), a ledge's collider is never ridden up, a
two-block plain wall in free mode is still a wall, and on flat ground or a rise within the step-up the vanilla move runs
unchanged.

### 3.4 Numbers (D4)

- `STEP` stays 0.6 (walkers' step, the hop tests).
- `MAX_LIFT` 5.0 blocks: one tick of the Mach needs up to 2.25 + half a step; a catch-up tick (`ForcedMover` Part B, at
  most 3 blocks) up to 4.5 + half a step. Six blocks is a cliff's height between levels: never lifted (D3 also refuses it).
- A hop's lift (`RideHop`, dy > 0) is kept when it is the larger; a downward dy (gravity) is replaced only by a lift.

### 3.5 Downhill (D5, second)

Riding down a muddy slope at speed (the engine allows and forces it) the bike leaves the stairs horizontally and lands
a few ticks on (gravity 0.08 a tick): it works today, it just flies. If the look wants it, the same sweep can **follow**
the floor down when the only thing under the destination is stairs and the drop is at most `MAX_LIFT`: set dy to
`floorAhead - y` when it is below −`STEP`. Built after the climb, behind its own bar, and dropped if it hurts the hop or
the ledge ride-down (a ledge is not stairs, so it shouldn't).

### 3.6 Not touched

`ForcedMover`, `MovementSync` (B9's pre-move included), `MoveRuntime.beforeMove`, `RideHop`, the step colliders, the
levels pipeline, the regions (no rebuild), the protocol, gen1-bridge. Walking players never run this code.

## 4. Phases

**C1. The lift.** `BikeEntity.move` override with the sweep (§3.2), the stairs gate (§3.3) and the numbers (§3.4); the
lift math in a small plain-Java helper (`entity/BikeClimb`: `lift(feetY, floorAhead, step, maxLift)` → the y to use, or
NaN) with a unit test. GameTests in `RideGameTests` (the batch that seats a mock player):
- an outdoor stairs cell as the builder lays it (LOW3 at the floor, two full blocks with HIGH3 on them, a plateau three
  up), the seated bike at its foot given `move(SELF, (1.5, -0.08, 0))` twice: on the plateau in two ticks, its feet never
  below the stair top under it after each move; the same at 0.75 a tick (the Acro's speed) arrives in four;
- a cave stairs cell (stage TWO, rise 2) the same;
- a two-block plain wall: not climbed at 1.5 a tick; the bike wall and the ledge (the existing test) unchanged;
- a walker (`MockPlayers.with`) on the outdoor stairs at 1.5 a tick: stalls as before (nothing of this is theirs).
Bar: JUnit for the helper, the ride GameTests green, `CollisionGameTests` unchanged.

**C2. The real engine (pod).** `tools/e2e/bike_ride.py` as in B5/B7 on LandSky6, Emerald: `muddy_slope` (the Mach from
(29, 11), W held) and, as the regression, `mach`, `wheelie`, `side_jump`, `rail_hops`, `cracked_floor`, `ledge_hop` and
`--e2e` (the deck, the refused cells, the bike shop). Bar for the slope: the client ends in the hidden cell (29, 3),
"moved wrongly" 0, "moved too quickly" 0, stuck 0, lag at most 2 on the software client (the Mach lane's own figure
there; 1 on the GPU pod if one is used); every other ride as in B5/B7 (0 complaints, ends in the hidden cell). Also a
run of the walkers' Route 111 spot(s) and one bike cave spot (the field spots driver) so a walker on the slope is still
pushed back as before. Video of the climb for the user (shrunk, ≤ 30 MiB).

**C3. Downhill (§3.5), optional.** Only if C2's video shows the flight as a wrong look: the follow-down, its GameTest
(the bike rides down the outdoor stairs at 1.5 a tick staying within 0.1 of each step top), and the slope ridden down on
the pod (from (29, 3) south, S held) with C2's bars.

**C4. The record.** PROGRESS "The bike" (an A1 entry with C1–C3's numbers), BIKE_FOLLOWUPS A1 closed with the pointer,
BIKE_PLAN §9's "left" line, PLAYTEST.md's slope item, `fabric-bridge/README` if it lists the entity's rules. Local
commits only (push on the user's go).

## 5. Risks

- **The server's y tolerance.** `handleMoveVehicle` compares the re-simulated position with the packet's; the override
  makes both moves the same, but if the server's chunk has the stairs unloaded (it never is, the rider is there) the sweep
  finds no stairs and no lift: the vanilla stall, with the old "moved wrongly" and its snap-back. Accept.
- **A hop on the stairs** (the Acro's bunny hop up a slope, if the engine ever allows it): the hop's dy stays when it is the
  larger; otherwise the lift takes over. The hop tests (RideHopTest, the look checks) are unchanged either way.
- **Free mode on stairs** (out in a zone): the bike now climbs any step-collider stairs at free speed; that is a walker's
  right too, only faster. Zones have no stairs today.
- **Cost of the sweep:** one `getBlockCollisions` and ≤ 24 block reads a tick only while the bike moves horizontally; the
  stairs gate runs first and is cheaper, so flat riding pays a block scan and nothing else.

## 6. Sizes

C1 about 120 lines of Java and three GameTests; C2 one pod session (kb-dev50 or a small cpu3g x4 pod, ~45 min with the
regression rides, about $0.15–0.25); C3 ~40 lines if built; C4 docs. Full suite (JUnit per package, GameTests, pytest)
once, at C2's end.

## 7. Results (2026-10-26, run the same day; the record is PROGRESS.md "The bike", "The climb")

- **C1** (b295ed6): `BikeClimb` (`lift`, `drop`, `stairs`, `top`) and the `BikeEntity.move` override; BikeClimbTest 6,
  RideGameTests +2. One change to the design: D2's sweep runs against the step colliders' shapes only (not every block
  collision), so the plateau's own height comes from the last step's flush top and a bike wall in the column is never a
  "floor"; the stairs scan reaches 5 blocks under the feet as well, for the way down.
- **C2** (kb-dev49): the Mach up the slope three times: lag 1, 0 "moved wrongly", 0 "moved too quickly", 0 stuck, ends in
  (29, 3) (B5/B7: lag 4, 8-9, 0, 1, not in the cell). The regression rides (Mach lane, wheelie, side jump, rail hops, the
  crack, the ledge hop, the deck) as before: lag ≤ 2, 0 complaints, in the hidden cell. Video sent.
- **C3** built: `drop` and the two-part move (forward, then down); `bike_ride.py` gained `muddy_slope_down` ((29, 3), W
  facing down). The engine rides the Mach down at 16, 8, 4, then 8, 8 on the slope's cells, then up to 4 again, 19 cells
  to (29, 22): lag 1, 0 complaints, in the hidden cell; the GameTest's push-back-speed descent lands on its stair. Video
  sent.
- **C4**: this section; PROGRESS, BIKE_FOLLOWUPS A1, BIKE_PLAN §9, PLAYTEST.md, CLAUDE.md. Checkpoint: GameTests 143/143,
  JUnit per package 1,507 (1 failing: KbPackReloadTest, as before). Pod about 35 min, about $0.19. Not pushed.
