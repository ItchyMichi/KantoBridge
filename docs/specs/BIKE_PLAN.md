# The bike: the plan (B0–B8)

Written 2026-10-26, after the water and bridges plan (WATER_AND_BRIDGES_PLAN.md, WB0–WB8 done, world LandSky6) and
from the field audit's K3/K4 findings (FIELD_MECHANICS_AUDIT_PLAN.md §13.4, §13.6, §13.9 Q4). The user's brief: one bike
feature in Minecraft for all three games, built on Emerald's bike system (Mach speed, Acro wheelie, bunny hop, side and
turn jumps, rails, bumpy slopes), which Blue and Crystal see as their one plain bike without any change to their own
behaviour; a bike that feels native in Minecraft, is drawn with a model other players can see, with placeholder art the
user may replace. Every number and file below was read from the repo and the engine's reference source
(`refs/gen1recomp`, v0.3.85); what a pod must confirm is marked *(B0)* or *(B1)*. Decisions are defaults the user may
change (§3); the phases are §4; §8 is the recipe for running it without the user.

## 0. In one paragraph

Today the Minecraft player leads and the hidden game follows: the client sends a `move.step` for each cell its feet
cross, gen1-bridge presses the direction for one step and answers, and Minecraft's walking speed is tuned to the
engine's 16 frames a cell (8 on a bike). That model can't carry Emerald's bikes: the Mach Bike only speeds up while the
direction stays held across landings (`machTrySpeedUp`, bike/rse.lua:326), the Acro tricks are input-history gestures
(B and a direction within 4 frames of each other, B held 40 frames, bike/rse.lua:426-460, :514-542), and rails, bumpy
slopes, muddy slopes and cracked floors are decided by the engine per step. So **while the hidden player is on a bike,
the hidden game drives and Minecraft steers**: the client sends the rider's held intent (`move.ride{dir, b}`: the
camera-relative movement keys as one Gen 1 direction, sneak or jump as B) and no steps; gen1-bridge holds those buttons
for the engine, whose own bike code runs exactly as in the game; every `player.pos` the engine emits (16, 8, 6 or 4
frames a cell; hops; ledge hops) is walked by the existing `ForcedMover`, now applied to a **bike entity the player
rides** (a vanilla passenger seat: the server spawns it when the player's relayed story state says bike, every client
sees it, the riding pose is Minecraft's own). Refusals need no colliders and no snap-backs: a refused direction simply
doesn't move. Blue and Crystal run through the same messages with their own engines (one bike, 8 frames, the Cycling
Road's roll and brake as today), so nothing about them changes but the seat and the look. The look is a hand-made
placeholder model in three liveries (Mach, Acro, plain) with a wheelie pitch and a real small hop, synced to other
players through the entity's data; the bike music and sounds come from the hidden game as everything else does.

## 1. What exists (the survey of 2026-10-26)

### 1.1 The engines

| | Blue (Gen 1) | Crystal (Gen 2) | Emerald (Gen 3) |
|---|---|---|---|
| Bikes | one; `OverworldState:useBicycle` (OverworldController.lua:1154), allowed outdoors / `bikeRiding` maps (:1140) | one; `Bike.lua` (town, route, cave, gate: :60-66) | Mach and Acro (`P.bikeType`); `Rse.getOnOff` (bike/rse.lua:958), reached from the bag through `ItemUse.useBike` |
| Step | 8 frames (`bikeStepFrames`) | half the walk: 8 (`Bike.stepFramesFor`, :51); coasting across the Cycling Road slope is a walk (:149-154) | Mach 16 → 8 → 4 by `frameCounter` 0, 1, 2 (`MACH_STEP_FRAMES`, :46-49); Acro 6 (`FRAMES_FAST_2`), wheelie 8 |
| Held input | directions polled with `isDown` each idle frame (:1888-1905) | the same (`Bike.forcedDirection`, :139) | `Player.update` polls `Rse.dpad(input)` on the landing poll: a held direction chains steps with no idle frame (player.lua:1437-1451; the adapter's `chainsHeld`) |
| Speed-up | — | — | `machTrySpeedUp` (:326): a step at the current gear, then `frameCounter + 1` (max 2); no direction → `slowDown` (:347): one gear down per step; a turn at speed slows first (`machTransition`, :361) |
| Brake / B | A or B held on a slope map stops the roll (:1897) | a held direction wins over the roll | Acro: B is the trick button (below); Mach: B does nothing on the bike |
| Tricks | — | — | Acro: tap B standing → wheelie standing (:462-470); B held 40 frames standing → bunny hop in place (:514-542); B held + forward → wheelie moving at 8 frames (:574-604); B + a perpendicular direction within 4 frames, inside the 6-frame turn → side jump one cell (:489-512, :792); B + the opposite direction → turn jump in place (:614, :808); a wheelie hops over ledges (:674); a bumpy slope forces the wheelie (:893) |
| Rails | — | — | rails let the Acro bike pass only along them, in a wheelie (`acroCollision` :148, `canFaceDir` :159, `willCollide` :167); refused on foot and to the Mach (109 rail cell-directions, 166 A today) |
| Special tiles | the Cycling Road's roll (`slope`) and brake | the Cycling Road's roll, steered (FA8 item 13) | muddy slope pushes back unless the Mach rides it at full speed (step_callbacks_rse.lua:200-202 for Route 119's pull-down; forced_movement.lua for the slope), cracked floors hold under a Mach at `FASTEST` (:358-361), bike-blocked cells (long grass, hot springs, logs, no-running, even-elevation Fortree bridges: `runningDisallowedByMetatile`, :136-146), the Cycling Road challenge counts collisions and time (:1030-1049) |
| Pose | one sprite | one sprite | `P.acroAnim {kind: back \| standBack \| pedal}` and `Rse.state.acroState` (NORMAL, TURNING, WHEELIE_STANDING, BUNNY_HOP, WHEELIE_MOVING, SIDE_JUMP, TURN_JUMP); hops set `P.jumping` with `P.jumpType` low / normal / high (`Player.bikeStep`, player.lua:1072) |
| Music | `Music.playMap(..., onBike)` | `MUSIC_BICYCLE` | `MUS_CYCLING` set by `getOnOff`; sounds `SE_BIKE_HOP`, `SE_WALL_HIT` |

### 1.2 The bridge

- `player.state.bike` (boolean) is the only bike fact: `f.bike = P.biking` (game3/observe.lua:162), `w.playerState ==
  "bike"` (gen2/observe.lua:141), `save.onBike` (gen1.lua:632). The kind (Mach or Acro) is not sent. `player.pos`
  kind `bike` carries the step's `frames` (kb_player.lua:101-112); the Cycling Road roll is renamed `slope` by the step
  driver (act/kb_move.lua:418-423).
- `move.hold{button:"b", on}` is the one hold the client may ask for (the brake; Emerald's run since FA8 item 7):
  `driver.hold` (kb_move.lua:342) keeps it as a desired state and presses or releases B on each poll (:407); every hold
  is released the moment the state leaves `idle` and pressed again when it returns. Directions are pressed per step only
  (`begin`, :306-315) and released on the landing poll unless the next queued step is ready.
- `act.field{action:"bicycle"}` gets on or off (`bike_on` / `bike_off`; refusals `no_item`, `not_here`, `failed`):
  Gen 1 `actions.bicycle` (gen1.lua:5105-5117), Gen 2 `useFieldItem` (gen2/fieldmoves.lua:79-82), Gen 3 `ItemUse.useBike`
  with whichever of `MACH_BIKE`, `ACRO_BIKE` the bag holds (game3/fieldmoves.lua:36, :105-113). **No client key sends
  it today**: the client's `act.field` comes from clicks only (`FieldClick`: cut, surf, dismount); `FieldFeedback.ACTIONS`
  already names `bicycle`. Players get on through the bag mirror screen.
- Gen 3's step verdicts come from a tap on `Player.tryMove`; bike steps go through `Player.bikeStep` and never pass it
  (K3). The Gen 3 classifier (kb_player.lua:81-94) calls `P.action` states `busy` through `f.anim` *(B1: check which of
  the bike's in-place actions, a wheelie, a hop in place, a collide, flip the mode)*.

### 1.3 The client and the server

- Walking: `MovementSync` sends `move.step` on a cell crossing and `move.face` on a quadrant change; `Intent.of` turns
  the input impulses and the yaw into one Gen 1 direction (diagonals: none). `MoveRuntime.beforeMove` gives the player
  the `ForcedMover`'s velocity (one leg per `player.pos` the client didn't ask for, 3 frames a tick, up to 16 legs
  queued, a settle and a stuck fallback that teleports); `InputLock` zeroes movement, jump and sneak while a forced move
  runs (`KeyboardInputMixin`); `SlopeBrake` sends `move.hold b` from the sneak key on a slope map and from the sprint key
  on foot in Gen 3; `LocalPlayerMixin` stops sprinting. `MovementSync.onPosition` (:352-405) walks any `bike` step the
  client didn't ask for as a forced leg already (the test "the bike rolls on its own", MovementSyncTest:563).
- Speeds are server attributes: `Rules.WALK_SPEED` 0.1737 (16 frames a cell), `BIKE_SPEED` 0.3474 (8), set from the
  relayed `story.state` (`StoryStateService` → `Rules.applyMovement`, Rules.java:99-114); jump strength 0.
- `story.state` (client → server) carries `surf`, `bike`, `run` and the active cells; the server keeps them in the
  player's `StoryState` (StoryState.java:31) and syncs `surf` to the other players through the `kantobridge:surfing`
  attachment (Surfing.java), whose clients draw the surf sprite under that player (`SurfSprite`). Nothing shows another
  player's bike.
- `StoryStates.governingPlayer(entity)` (:45-50) already answers a vehicle's controlling passenger, so every
  story-state collider (`BikeWallBlock`, `LedgeColliderBlock`, `FallWallBlock`, story cells) treats a ridden entity as
  its rider. `BikeWallBlock` (FA8 item 10, kind 15) walls `CellFlags.NO_BIKE` cells for a biker.
- Entities: `KbEntities` registers the client-only `PuppetEntity`; `PuppetRuntime` registers its renderer
  (EntityRendererRegistry). No server entity of ours exists yet. Warps teleport with `ServerPlayer.teleportTo`
  (WarpService.java:118), which dismounts a passenger.
- The e2e tools can put the hidden Emerald player on a bike (`kb_g3spots.lua:350-352 vehicle = mach | acro`,
  `field_spots.py --extra ... "vehicle"`), sweep bike verdicts per cell (`kb_g3field.lua:437 bikeVerdict`) and drive the
  real client (`RemotePilot`: `key` with a hold, `step`, `record`, `screenshot`, `measure`).

### 1.4 What the audit left (FIELD_MECHANICS_AUDIT_PLAN §13.4, §13.6, §13.10)

- K3: the Mach Bike stays at 16 frames at a player's cadence; one bike speed in the protocol; the Acro tricks can't be
  asked for; rails and bumpy slopes are refused on foot and open in Minecraft (166 A). Bike-blocked cells: 5,761
  cell-directions the game refuses a bike (walled since FA8 item 10 where `NO_BIKE` is set).
- K4 (Blue): steering, the brake and dismounting are refused (`not_idle`) while Route 17 rolls the player as `script`
  steps; the engine lets the player steer (OverworldController.lua:1888-1905: any held direction or A/B stops the pull).
- PLAYTEST.md items waiting on this plan: 239 (the bicycle from the bag), 1062 (the Cycling Road can't be steered),
  1068 (Mach speed, Acro tricks), 1070/1081 (bikes into long grass), 1077 (Crystal's Cycling Road steering), 1086 (Route
  110's deck end to end). WATER_AND_BRIDGES_PLAN §2.2 item 10 hands the Cycling Road's riding to this plan.

## 2. The design

### 2.1 The ride: the hidden game drives, Minecraft steers (D1)

- **Off the bike nothing changes**: steps on crossings, faces, pushes, the brake and run holds, drift fixes, warps.
- **On the bike** (`player.state.bike`, with the link up and the hidden game in its overworld) the client sends no
  `move.step` and no `move.face`. Each world tick it reads the movement keys physically (`keyUp`, `keyDown`, `keyLeft`,
  `keyRight`; the input lock zeroes the impulses, as it must) and the camera yaw, turns them into one Gen 1 direction
  with `Intent.of` (a diagonal keeps the direction held the tick before, else none), and B from sneak or jump. On any
  change it sends `move.ride{dir, b}`; on leaving the bike, a warp start, a link change or a mode other than `idle` it
  sends `move.ride{dir:null, b:false}` once (and gen1-bridge lets go of everything anyway).
- gen1-bridge keeps the ride as a desired hold set beside the brake (`kb_move.lua`): while the mode is `idle` and no
  `move.step` is in flight, the direction is pressed and stays pressed; B likewise; a change presses the new direction
  in the same logic step it releases the old; the mode leaving `idle` releases both, its return presses them again (the
  brake's rule, :407, not the step's landing release). A `move.ride` with a direction empties the step queue
  (`blocked: script`, as a dropped step); a `move.step` arriving while a ride direction is held is refused `script`.
  The engine does the rest: Gen 3's `Player.update` → `Rse.update` reads the held buttons every landing poll and chains
  steps, speeds up, turns, wheelies, hops, bumps, rides rails, climbs Route 119's slope, crosses cracked floors; Gen 1's
  `handleInput` and Gen 2's `Bike` walk a held direction one step per idle poll at 8 frames and roll or brake on the
  Cycling Road.
- Every `player.pos` while riding is a move the client didn't ask for: `MovementSync.onPosition` walks it with the
  `ForcedMover` (kind `bike`: the step's frames; `ledge`: 32 frames two cells; `slope`: the roll). The mover's velocity
  goes to the **bike entity** instead of the player (the rider's position follows its seat). An in-place action (a
  wheelie, a hop on the spot, a collide, a turn) moves nobody: the pose comes from `player.state` (§2.2).
- Refusals: the engine's own (`Rse.collision`, bike/rse.lua:210: object events, bike-blocked tiles, rails across,
  elevation, the map's edge). The bike doesn't move; the hidden game plays `SE_WALL_HIT` (sound passes through
  Gen1Recomp). The Gen 3 adapter reports a blocking collision as `player.blocked` without `n` (one per collide action,
  from a tap on `Rse.collision` or `P.startAction{walk:"normal"}`) so the client can jolt the bike; the client's
  `onBlocked` without `n` already tolerates it (the hidden cell equals the bike's).
- Latency: key → `move.ride` at the tick's end (≤ 50 ms) → the press in the next logic step (≤ 17 ms) → the step's
  `player.pos` that step → the mover next client tick: about 2 ticks from the key to motion; a release lets the current
  step finish (4–16 frames) and the bike stops on a cell centre. *(B0 measures both on the pod before anything is
  built; §3 D1 names the fallback.)*
- Where the hidden game can't drive (a Wild Routes zone off the route map, the hidden player on an unbuilt map) the
  bike is **free**: the rider's input moves the entity like a boat at its kind's base speed, with the rider's story-state
  colliders; no `move.ride` is sent (nothing follows). The input lock's other reasons (no link, a screen, a battle, a
  warp) hold the bike still as they hold the player.

### 2.2 The messages (PROTOCOL.md "Movement", "Relay only"; schemas in `protocol/messages`, `protocol/defs.schema.json`)

| Type | Dir | Change |
|---|---|---|
| `move.ride` | M→G | **new**: `dir: "up"\|"down"\|"left"\|"right"\|null, b: boolean`. The rider's held intent while the hidden player is on a bike; sent on change; no answer, no `n`. Outside a bike, or off `idle`, gen1-bridge holds nothing and answers nothing. `x-re: never`. |
| `move.step`, `move.face` | M→G | unchanged; refused `player.blocked{script}` while a ride direction is held |
| `player.state` | G→M | **new optional** `bikeKind: "bike"\|"mach"\|"acro"` (present whenever `bike` is true: Gen 1 and Gen 2 always `bike`), `trick: "none"\|"wheelie"\|"hop"` (Gen 3: `wheelie` for `acroState` WHEELIE_STANDING / WHEELIE_MOVING or `acroAnim` back / pedal, `hop` for BUNNY_HOP; absent elsewhere), `gear: 0\|1\|2` (Gen 3's Mach `frameCounter`, for the wheel spin and the dev overlay; absent elsewhere) |
| `player.pos` | G→M | **new optional** `jump: "low"\|"normal"\|"high"` (Gen 3's `P.jumpType` on a hop step: low = a bunny hop onto the next cell, normal = a side or turn jump, high = a ledge hop; the `ledge` kind keeps its 32 frames) |
| `player.blocked` | G→M | unchanged shape; Gen 3 may now send one without `n` for a bike's collide (`reason: tile\|entity\|bounds`) |
| `act.field` | M→G | unchanged (`bicycle`); the client gains a key that sends it |
| `story.state` | C→S | **new optional** `bikeKind` (with `bike` in a full state or a delta that changes it) and `trick` deltas; the server keeps both in `StoryState` |

Rules added to "Movement rules (M3)": a ride hold is a desired state like the brake; it never answers; a `move.ride`
with a direction drops the waiting steps; an engine refusal of a held ride direction may be reported as
`player.blocked` without `n`, at most once per 16 frames; a client that doesn't know `trick`, `gear`, `bikeKind` or
`jump` ignores them. Regenerate `catalog.json` and the examples (`python -m kb_protocol.catalog --write`); `fake_gen1`
learns `move.ride` (holds walk its player on at the bike's frames until released; `bikeKind` in its state).

### 2.3 gen1-bridge

- `act/kb_move.lua`: `driver.ride(data, gen)` (validation: `dir` null or a direction, `b` boolean); the ride set held
  like the brake (pressed on each idle poll, released off `idle`, re-pressed on a focus change by `repress`); `begin`
  refuses a step while a ride direction is held; a ride with a direction drops the queue; stats `rides`, `rideHeld`.
  `main.lua:666` routes `move.ride` beside `move.hold`.
- The Gen 3 adapter (`lib/engine/game3/observe.lua`): `f.bikeKind = P.bikeType` (mach / acro), `f.trick` from
  `Rse.state.acroState` and `P.acroAnim`, `f.gear = Rse.state.frameCounter` (Mach), `f.jump = P.jumpType` while
  `P.jumping and P.biking`; the classifier keeps `idle` through the bike's own in-place actions *(B1: `P.action` set by
  `Rse`'s transitions must not count as `f.anim`; a wheelie standing is a held pose, the bunny hop charge is 40 frames
  of it; find the exact fields and exempt them, nothing else)*. `lib/engine/game3/move.lua`: the collide report
  (§2.1). `kb_player.lua`: the new fields in `Player.state` and `posMessage`.
- The Gen 2 adapter: `f.bikeKind = "bike"`. The Gen 1 adapter: `facts.bikeKind = "bike"`. Nothing else: their engines
  read held directions as they always did; K4's refusals vanish because steering and braking become holds, not steps
  *(B6 confirms on the real engine: Route 17 steered left and right, braked, and got off at the bottom)*.

### 2.4 The client (`client/move`, `client/ride` new, `client/story`)

- `RideInput` (plain Java, tested): per tick, the physical movement keys and the yaw → `Dir` or null (the diagonal
  rule), `b` from sneak or jump, the **side-jump assist**: a B edge within 2 ticks of a new perpendicular direction is
  sent in one `move.ride` with it (the engine's 4-frame window, bike/rse.lua:446-453), else each change goes as it comes.
- `RideController` (plain Java, tested through `MoveController`): owns whether the ride is on (`player.state.bike` and
  linked and `idle`), sends `move.ride` on change and the null ride on leaving, tells `MovementSync` to send no steps or
  faces (a new `riding` flag beside `climbing`), and points the `ForcedMover`'s velocity at the seat: `MoveRuntime`
  applies it to `player.getVehicle()` when that is our bike (`setDeltaMovement` on the entity, the player's own
  movement zeroed by the lock as during any forced move), else to the player as today. `MovementSync.lastY` and the
  floor choice use the seat's feet while riding (a rider sits 0.6 above them; the deck overlap, WB4).
- `InputLock`: a new reason `RIDING` (the hidden game drives: movement, jump and sneak zeroed for the player; the keys
  are still read physically by `RideInput`), `noSneak` while riding (no crouch); the `FORCED` reason stays for the legs.
  `SlopeBrake` sends no `move.hold b` while riding (B travels in `move.ride`).
- The hop: a `player.pos` with `jump` gives the bike entity an upward velocity on top of the leg (low 0.25, normal 0.3,
  high 0.42 blocks a tick: the engine's arc fits the leg's 16 or 32 frames); gravity lands it. A standing hop (no
  `player.pos`) comes from `trick: hop`: a small hop each 16 frames while it lasts.
- `ClientStoryView` relays `bikeKind` and `trick` in `story.state` (a full state and deltas, as `surf` and `run`).
- The Bicycle key: `Keybinds` gains `key.kantobridge.bicycle` (default **C**; Cobblemon uses R, the arrows and M), which
  sends `act.field{action:"bicycle"}` through `ActRuntime`'s tracked send; `FieldFeedback` shows `bike_on` / `bike_off`
  and the refusals as it does for the other actions. The bag mirror keeps working. Vanilla's sneak-to-dismount is
  blocked for our bike (§2.5), so the key and the hidden game are the only ways off.
- The dev overlay (`DebugOverlay`) adds the ride line: kind, gear, trick, held dir/b, legs queued, the collide count.

### 2.5 The server (`entity/BikeEntity`, `server/Bikes`, `story`, `server/WarpService`)

- `BikeEntity` (a plain `Entity`, registered in `KbEntities` beside the puppet; `noSave`, `noSummon`, normal tracking):
  one seat; `getControllingPassenger` → its rider (so `governingPlayer` and the colliders see the rider); synched data
  `KIND` (bike / mach / acro), `TRICK` (none / wheelie / hop), `GEAR`; gravity and block collision in `tick` (`move(SELF,
  delta)`); the client that rides it is its mover (`isControlledByLocalInstance`), so Minecraft's own
  `ServerboundMoveVehiclePacket` path carries its position to the server and to every other client; size 0.8 × 0.9;
  the rider's seat 0.6 up; the rider's body yaw clamped to the bike's heading (±105°, as a horse); the heading turns
  toward the hidden facing (`player.state`'s facing is in `player.pos`; the client sets the yaw from the leg's
  direction, standing turns from `move.face`-less facings in `player.pos` kind `sync`). It despawns when it has had no
  rider for 20 ticks or its rider's story state no longer says bike. Riders take no fall damage (Rules already protect
  players); the bike itself can't be damaged, pushed or picked.
- `Bikes` (server service): `ensure(player)` on every relayed `story.state` and on the player's tick while
  `state.bike()` and not riding (rate-limited to once a second): spawn at the player's feet with the player's yaw,
  `startRiding(bike, true)`; `dismount(player)` when `bike` turns false: `stopRiding`, discard; `KIND`, `TRICK` and
  `GEAR` from the state on each change. `Rules.applyMovement` keeps setting the walking or bike attribute (the attribute
  moves nothing while seated; it matters the moment the player steps off and in free mode).
- `WarpService.teleport` (WarpService.java:101-121): a riding player's bike is teleported to the same spot first and the
  player re-seated after `teleportTo` (which dismounts), so a warp, a sync teleport or a relink never leaves the bike
  behind; a dimension never changes. Tests: WarpGameTests.
- A mixin on `Player#wantsToStopRiding` (common; the client's `LocalPlayer` override too) returns false while the vehicle
  is a `BikeEntity`: sneak never dismounts (it is B). `stopRiding` from vanilla's other paths (death, a portal, `/kill`)
  is followed by `ensure` putting the player back on.
- Multiplayer: the other clients see the entity, its passenger, its synced `KIND`/`TRICK`/`GEAR`; no attachment needed.
  The entity never carries art data; `KIND` is a byte.

### 2.6 The look (`client/ride/BikeRenderer`, `BikeModel`, `assets/kantobridge/textures/entity/bike_{mach,acro,plain}.png`)

- A hand-made `LayerDefinition` (cuboids: two wheels, a frame, a saddle, a handlebar; the Acro a BMX with a higher bar
  and pegs; the plain bike Gen 1's upright frame) and three 64 × 32 placeholder textures drawn by hand in the repo
  (nothing ROM-derived; an ordinary resource pack or a `kb_pack` may override the three files later: D7). The wheels
  spin with the leg's speed (gear or frames); `TRICK` wheelie pitches the model 35° nose-up; a hop is the entity's own
  jump (§2.4); a collide jolts it 0.1 blocks back for 4 ticks (client-side only).
- First person: the handlebar and front wheel are drawn (the renderer draws the model for the local rider too, as
  boats are); third person and other players: the model under the rider in Minecraft's riding pose.
- `SurfSprite` is untouched; a surfing player is never on a bike (the engines clear it).

### 2.7 The three games

- **Emerald**: everything above. Which bike the player owns is the engine's (`MACH_BIKE` or `ACRO_BIKE` in the bag;
  Rydel swaps them); `bikeKind` tells the client. The Cycling Road challenge, Route 119's slope, the cracked floors of
  Granite Cave, Mirage Tower and Sky Pillar, Fortree's bridges at an even elevation, Pacifidlog's logs and the long grass
  all come out of the engine's rules through the ride holds; the Minecraft side only follows and draws.
- **Blue**: `bikeKind: bike`; the ride holds press a direction (8 frames) and B (the brake); Route 17's roll keeps its
  `slope` kind (the driver's rename stays: a step down nobody held); steering is a held direction, braking a held B,
  both answered by the engine itself (K4 closed). `actions.bicycle`'s refusals (`not_here` on the forced bike) reach the
  key's feedback. The Cycling Road's forced bike (`save.forcedBike`) is the engine's.
- **Crystal**: `bikeKind: bike`; the roll and steering as FA8 item 13 left them (steps answered, the roll `slope`), now
  as holds; `Bike.stepFrames`'s coasting rule (a walk's frames across the slope) comes out of the engine.
- All three: getting on and off is the hidden game's decision and timing (the bag's text box, the forced bike, Surf
  clearing the bike, a map that forbids it); the seat appears and vanishes with `player.state.bike`.

### 2.8 Not touched

The builder and the worlds (no rebuild: `BikeWallBlock`, `NO_BIKE`, the decks and the edge walls stay as LandSky6 has
them); the step driver's walking path; the parity tools (the bike column of `kb_g3field.lua` stays informative only);
the puppets; Cobblemon.

## 3. Decisions (defaults proceed unless the user says otherwise)

| # | Decision | Default |
|---|---|---|
| D1 | While the hidden player is on a bike, the hidden game drives and Minecraft steers (`move.ride` holds; the engine's steps walked by the mover on the seat). Fallback if B0 finds the lag unplayable (> 150 ms key-to-motion or the mover unable to keep a 4-frame stream smooth): the client pre-moves one cell on a key press at the current gear and the engine's leg corrects it (B9) | yes (drive) |
| D2 | The bike is a ridden server entity (a vanilla passenger seat), spawned and removed by the server from the player's relayed story state; one entity type, the kind as synced data | yes |
| D3 | Speeds are the engine's: 16 / 8 / 4 frames a cell on the Mach (7.5 / 15 / 30 blocks a second), 6 on the Acro (20), 8 for wheelies, Blue's and Crystal's 8 (15, as today). No cap *(B0 checks 30 blocks a second against the server's vehicle move checks and chunk loading on LandSky6)* | yes |
| D4 | Keys: the movement keys steer (camera-relative, snapped to the dominant Gen 1 direction; a diagonal keeps the last direction); sneak and jump are both B while riding; a new Bicycle key (default C) gets on and off through `act.field{bicycle}`; the bag still works; vanilla's sneak-dismount is off for the bike | yes |
| D5 | Collision while driven is the engine's alone: no new colliders for rails, bumpy or muddy slopes; `BikeWallBlock` stays; a refused direction is a jolt and the engine's sound | yes |
| D6 | Tricks are shown as pose and motion: a wheelie pitches the bike, a hop is a real small jump of the entity, a side or turn jump is the engine's step with the hop; the state reaches other players through the entity's synced data | yes |
| D7 | Art: a hand-made cuboid model and three hand-drawn placeholder textures in the mod; packs may override them; the hidden game's bike sprites (ROM-derived) are not used | yes |
| D8 | Free mode: off a route map in a zone, or wherever the hidden game isn't followed while the player is on a bike, the rider's input moves the bike like a boat at its kind's base speed | yes |
| D9 | Warps and sync teleports keep the rider on the bike (the server moves the bike too); a bike state change by the hidden game (a map that forbids it, Surf, the forced bike) is followed through `player.state.bike` | yes |
| D10 | Protocol: `move.ride` new; `bikeKind`, `trick`, `gear` on `player.state` and `story.state`; `jump` on `player.pos`; all optional but `move.ride`'s fields; `bike` stays a boolean | yes |
| D11 | The Mach's `gear` and the Acro's `trick` are Gen 3 only; Blue and Crystal send `bikeKind: bike` and nothing else new | yes |
| D12 | Order: B0 on a pod first (the facts the design rests on), then B1 (protocol, bridge, Lua), B2 (client), B3 (server entity, GameTests; **checkpoint**), B4 (the look), B5 (tricks, hops, the collide), B6 (Blue and Crystal on the real engines), B7 (e2e on the pod, videos, PLAYTEST), B8 (record; **checkpoint**); B9 only if D1's fallback is needed | yes |

## 4. Phases

Each phase: the tests for what it touches, then the pod step named, then PROGRESS.md ("The bike"). Pods per the standing
rules (a small 22-only pod; stop it after each job; the full suite at B3 and B8). Push only on the user's go.

- **B0 Facts on a pod** (~45 min, an 8-vCPU pod for the real client; LandSky6 on the volume). Three measurements, no
  new mod code: (a) the follow: with `kb_g3spots.lua` extended by a `ride` command (`mod.input:press` of a direction and
  B for N logic steps, the engine's own `Rse` driving) put the hidden Emerald player on the Mach Bike at Route 110's
  south end and hold up for 30 cells, then on the Acro with B held (a wheelie run) and on Route 119's rails; the real
  client (Xvfb, `field_spots.py`'s harness, `RemotePilot record`) follows through today's forced legs: log the mover's
  `forced`, `stuck`, `teleports`, the cells of lag between the hidden player and the client each second, the frame rate
  at 4 frames a cell (`frame_rate.py`'s measure), whether the server ever answers "moved too quickly"; (b) the key
  path: `RemotePilot key` on W with a 1-tick `move.ride`-like hold sent by a throwaway client patch is **not** built;
  instead time the existing path (`move.hold b` on a slope map: the client's send to the engine's press, from the logs'
  timestamps) as the lower bound of the ride's latency; (c) the classifier: which `P.action` kinds flip the mode off
  `idle` (a wheelie standing, the bunny hop charge, a collide, a turn in place) by driving each and reading
  `player.state` in the bridge's log. Write the three results in PROGRESS and §9; if (a) shows the mover can't keep a
  4-frame stream (stuck > 0 per 30 cells or lag > 2 cells), D1's fallback is on for B9 and B2 budgets for it.
- **B1 The protocol and the bridge**: the schemas (`move.ride`, `playerState` + `bikeKind`/`trick`/`gear`, `player.pos`
  + `jump`, `story.state` + `bikeKind`/`trick`), examples, `catalog.json`, `MsgTypes`; `kb_move.lua` ride holds and the
  step refusal; `main.lua` routing; the Gen 3 facts (`observe.lua`) and the classifier exemption; the collide report in
  `move.lua`; `bikeKind` in the Gen 1 and Gen 2 adapters; `fake_gen1`'s `move.ride`. Checks: pytest protocol tests,
  `tools/lua/test_lua_move.py` (a ride direction pressed and kept across landings; released off idle and pressed again;
  a step refused while riding; B pressed with the direction in one step), `test_lua_game3_move.py` (kind, trick, gear,
  jump from fake `P` fields; the mode stays idle through a fake wheelie action), `test_lua_player.py` (Gen 1 `bikeKind`),
  `test_lua_gen2_observe.py`; then on the pod the real Emerald engine with the extended spots driver: a ride hold from
  the bridge (a test session sending `move.ride`) accelerates the Mach to 4 frames within 3 steps and slows on release;
  B held standing on the Acro gives `trick: wheelie`, then `hop` after 40 frames; a side jump from one message.
- **B2 The client**: `RideInput`, `RideController`, the `riding` flag in `MovementSync`, the mover on the seat
  (`MoveRuntime`, with a stand-in seat in tests), `InputLock.RIDING` and `noSneak`, `SlopeBrake` quiet while riding,
  `ClientStoryView` relay, the Bicycle key, the overlay line. Checks: `RideInputTest` (the diagonal rule, the side-jump
  assist, B from either key), `MoveControllerTest`/`MovementSyncTest` (no steps while riding; `move.ride` on change and
  the null ride on leaving; a stream of 4-frame `bike` positions becomes legs; a `jump` leg flags the hop; a `blocked`
  without `n` leaves the cell; leaving the bike resumes steps on the next crossing), `SlopeBrakeTest`, the relay test
  (`StoryStateMessagesTest` with `bikeKind`/`trick`), the fake_gen1 rig.
- **B3 The server entity** (**checkpoint**: the full suite): `BikeEntity`, `KbEntities`, `Bikes`, `StoryState` +
  `bikeKind`/`trick`, `StoryStateMessages`, `StoryStateService`, `WarpService` re-seat, the `wantsToStopRiding` mixin,
  `Rules` unchanged. Checks: GameTests (`RideGameTests`): a relayed `story.state{bike:true, bikeKind:"mach"}` seats the
  mock player on a bike with `KIND` mach; `bike:false` removes it; the bike stops at a `BikeWallBlock` and passes a
  `LedgeColliderBlock` downhill as its rider would (reuse `CollisionGameTests.walk` on the entity); a warp teleport
  keeps the rider seated at the destination; two players each see the other's bike (`MultiplayerGameTests` pattern);
  sneak never dismounts (`wantsToStopRiding` false); a bike without a rider is gone after 20 ticks; JUnit for `Bikes`'s
  rate limit. Pod: the GameTests in their own batch (`gradlew runGameTest`), JUnit per package (`kb-junit`).
- **B4 The look**: `BikeModel`, `BikeRenderer` (registered as the puppet's is), the three textures, the first-person
  draw, the yaw clamp. Checks: a JUnit on the model's layer definition (parts, texture size); on the pod the dev client
  on LandSky6 with the hidden Emerald player on each bike kind and Blue's: `context_shots.py`-style screenshots from
  first and third person (`RemotePilot screenshot`), a sheet to the user.
- **B5 Tricks, hops and the collide**: `TRICK`/`GEAR` through the relay to the entity, the wheelie pitch, the hop
  velocities, the standing hop cadence, the wheel spin, the collide jolt. Checks: JUnit (`RideState`: pitch and hop from
  the synced values; the hop velocity per `jump` kind), GameTests (the synced data reaches a second mock player), then on
  the pod the real Emerald engine driven by the test session through `move.ride`: a wheelie run on Route 119's rails
  (the bike passes along, is refused across), a bunny hop over Route 111's ledge, Route 119's muddy slope climbed at
  full Mach speed and refused slower, Granite Cave B1F's cracked floor crossed at full speed and fallen through slower
  (the fall warp as before), the Cycling Road challenge's collision count rising on a bump; videos.
- **B6 Blue and Crystal on the real engines**: the ride holds through `gen1.lua` and `gen2/`: Blue's Route 17 steered
  left and right while rolling, braked with B, got off at the bottom and refused on the slope (`not_here`), the bike
  from the key and from the bag; Crystal's Cycling Road the same and `Bike.stepFrames`'s coasting walk; both ride into a
  cave and a gate where allowed and are refused where not. Checks: `g1_field_check.py`/`g2_field_check.py` rows for
  Route 17 and Route 16's gate, the story spot drivers' `vehicle`; videos.
- **B7 End to end on the pod** (~1.5 h, an 8-vCPU pod): `field_spots.py` per game on LandSky6 with the bike spots added
  as `--extra` (Blue: Route 17 top to bottom, Route 16's gate; Crystal: Routes 16–17; Emerald: Route 110 end to end on the
  deck, Route 119's rails and slope, Route 111's ledge hop, the long grass refusal, Fortree's bridge, Pacifidlog's logs,
  Granite Cave's cracked floor, Mauville's bike shop door); a two-client LAN run (`RemotePilot lan`, the multiplayer e2e
  pattern) where the second client records the first's bike; the frame-rate run at 30 blocks a second; videos and a
  sheet to the user; PLAYTEST.md's bike items rewritten (239, 1062, 1068, 1070, 1077, 1081, 1086 and the new ones: the
  Bicycle key, the tricks, the other player's bike).
- **B8 The record** (**checkpoint**: the full suite): PROGRESS.md "The bike" (numbers only), this plan's §9, PROTOCOL.md
  and CLAUDE.md's pointer, README's key list. **Push on the user's go.**
- **B9 (only if D1's fallback is on after B0)**: the client's one-cell pre-move on a key press at the current gear
  (`RideController` sends `move.ride` and starts a predicted leg; the engine's `player.pos` confirms or the next
  `blocked` snaps back one cell); JUnit for the predicted leg's reconciliation; the same pod checks as B5.

## 5. Risks

- **The follow's feel.** Two ticks of latency and a stop on the cell centre are the game's grid, not Minecraft's; a
  player used to a horse may find it heavy. B0 measures it on the real client; D1's fallback (B9) pre-moves one cell.
- **A 4-frame stream through the mover.** Each leg is 1.33 ticks; the mover's per-tick velocity is exact on flat
  ground but a step-up or a block edge can lose a tick (it catches up: "a tick lost to a collision is caught up on the
  next one"). The deck's walls and the edge walls never stand in a ridden direction the engine allows. If the stuck
  fallback fires on a long run, the teleport is behind the fade, as today.
- **The classifier and the bike's actions.** If a wheelie or a collide flips the mode off `idle`, the ride holds are
  released each time and the bunny hop can't charge; B0 (c) and B1 find the exact fields; exempting them changes no
  other path (walking actions keep their classes).
- **Vehicle move checks.** The server accepts a vehicle move under 100 blocks² a packet and teleports it back when it
  collides with something new; at 1.5 blocks a tick with the same colliders on both sides this never fires *(B0)*.
- **Dismount through vanilla.** Death, `/kill`, a dimension change dismount; `Bikes.ensure` re-seats within a second.
  A player who logs out riding comes back walking until their full `story.state` arrives (as surf does).
- **Key conflicts.** C is free in vanilla and Cobblemon 1.8.1 (R, M, the arrows, B's party keys are its); the key is
  rebindable; the plan never binds Z/X/Enter/Backspace (the Game Boy keys).
- **Gen 1's forced bike and refusals on the slope.** Getting off on Route 17 is refused `not_here` by the engine; the
  feedback line says so; the entity stays.
- **Free mode drift.** In a zone the bike moves without the hidden game; returning to the route map, the ride resumes
  from the hidden cell as walking does today (a relink step at most).

## 6. Not in this plan

Builder colliders for rails and bumpy slopes (the engine refuses them to walkers; FA8's 166 A on foot stay a parity
note); the hidden game's own bike sprites as the player's model (a later pack option); Cobblemon's Pokémon riding; a
bike for NPC puppets (trainers on bikes keep their sprites); the Cycling Road's Minecraft-side timing display; running
shoes changes; Blue's or Crystal's engine behaviour (nothing changes there by design); the HIDDEN_STEP_SPEEDUP spec.

## 7. Sizes

- Code: protocol (5 schemas, the catalog), `kb_move.lua`, the three adapters' facts, `kb_player.lua`, `fake_gen1`;
  client `RideInput`, `RideController`, `MovementSync`/`MoveController`/`MoveRuntime` edits, `InputLock`, `SlopeBrake`,
  `ClientStoryView`, `Keybinds`, `ActRuntime`, `BikeRenderer`/`BikeModel`, 3 textures, lang keys; server `BikeEntity`,
  `Bikes`, `StoryState`/`StoryStateMessages`/`StoryStateService`, `WarpService`, one mixin; tests in each layer; the
  spots driver's `ride`; PLAYTEST and PROGRESS. No world rebuild.
- Pod time: B0 ~0.75 h, B1 ~0.5 h, B3 ~0.5 h, B4 ~0.5 h, B5 ~1 h, B6 ~0.75 h, B7 ~1.5 h, B8 ~0.5 h: about 6 pod hours,
  under $2.50 at the 8-vCPU pod's rate ($0.32/h) and less on 4 vCPU where the client isn't needed.

## 8. Running it without the user (the recipe; written for an agent working alone, 2026-10-26)

The user has read D1–D12 and will not answer questions while this runs. Decide by the defaults; where the plan is
silent, keep the game's behaviour and write the choice in PROGRESS.md. A blocked phase is recorded with its reason and
the work moves to the next phase that doesn't depend on it; the final report lists what is done and what is not.

**Where things are.** The repo is `/home/claude/kantobridge` (commit identity set; the push URL `github-kantobridge`,
the repo is public; push only on the user's go). The engine's reference source is `refs/gen1recomp` (read-only; nothing
copied from it: every rule re-implemented from reading, as the audit did). On a pod the volume `kantobridge-dev`
(jcp4eqtl70, EU-RO-1 only) is `/workspace`: `/workspace/local` is ROM-derived (the builds and the e2e read it; never open
or copy it), `/workspace/local/minecraft/kantobridge/export/<game>` the export bundles, the world **LandSky6** (all three
regions, table v16t) is the world to test on. Numbers, map ids, cells, counts and hashes may leave the pod; nothing else.

**Pods.** Create pods as `kb-dev52` (wi2la1q7lg49sj) is made (`get-pod` it and copy: image `eclipse-temurin:21-jdk-noble`,
the sshd start command with `PUBLIC_KEY`, the volume at `/workspace`, port 22/tcp, 20–30 GB container disk, EU-RO-1):
4 vCPU / 16 GB (cpu3g x4 $0.16/h, cpu5g x4 $0.184/h, cpu5c x4 $0.14/h) for JUnit, Lua and the engine runs; 8 vCPU / 32 GB
(cpu3g x8 $0.32/h) for anything with the real client (it hangs on 2 vCPU) and the GameTests. When a stopped pod's host is
full, make a new one rather than retrying for long. After every start: `kb-pod host <ip> <port>` (get-pod's
`ssh.direct`), `kb-pod prep`, `ssh kb-dev 'ln -sfn /workspace/local /root/kantobridge/local'`; for anything that runs the
engine or the client also `/home/claude/kb-runpod/prep_base.sh` and `/home/claude/kb-runpod/pod/prep2.sh`, then
`Xvfb :99` (`setsid nohup`; check with `pgrep -f "[X]vfb :99"`; the field sweeps stop it, restart it before pictures).
The container disk is wiped on stop: the pod scripts live in `/home/claude/kb-runpod/pod/` (scp them each time; add the
ones this plan makes there too); `/workspace` persists. Stop a pod (`pod-action stop`) the moment its job ends; never
leave one running while waiting.

**Scripts.** The real engine with a test session: `tools/e2e/run_gen1recomp.py` and the spot drivers
(`tools/e2e/drivers/kb_g3spots.lua`, `kb_g1spots.lua`, `kb_g2spots.lua`: the `go` command with `vehicle`; add `ride`),
`tools/e2e/field_spots.py` (`--extra` spots of our own, `--record`), `tools/e2e/g{1,2,3}_field_check.py` (sweeps),
`tools/e2e/frame_rate.py` (RemotePilot `measure`), `tools/e2e/test_multiplayer.py` (two clients on a LAN world);
`/home/claude/kb-runpod/pod/sky5.sh` shows how a world and the spots are run end to end. The client is driven by
`RemotePilot` (`key`, `step`, `record`, `screenshot`, `measure`, `lan`); `DISPLAY=:99 KB_DATASET_MEMO=30` for every e2e.

**Tests.** JUnit on the pod with `/home/claude/kb-runpod/kb-junit [package ...]` (one package at a time: the whole suite
in one Gradle run hangs at its end on these pods); GameTests `./gradlew runGameTest` (report in `build/reports/gametest`;
tests that reset static state need their own batch); pytest from `tools/` with `tools/.venv/bin/python -m pytest -c
pytest.ini --rootdir .` (the Lua side under lupa is in it); after a schema change `python -m kb_protocol.catalog
--write`. Feature tests per phase; the full set at B3 and B8 only. Mojang mappings + Parchment only, never Yarn; Fabric's
rendering API only.

**Gotchas.** Never `pkill -f` a pattern that matches your own ssh command line (kill by PID). Long jobs:
`(setsid nohup cmd > log 2>&1 < /dev/null &)` and poll the log. Waiting: `for i in $(seq 1 N); do sleep 4; done`. Never
load a whole transcript or log into context (`grep` it). The Minecraft EULA is accepted on the volume. A fresh
container's first `gradlew` spends ~10 min configuring. Gen 3's bike code reads `game.input:isDown` each landing poll;
`mod.input:press` returns a token and `release` takes it (kb_move.lua's `press`/`release`). The client's input lock
zeroes `player.input` impulses: read `minecraft.options.key*.isDown()` for the ride. `ServerPlayer.teleportTo` dismounts.
The classifier's `f.anim`/`f.emote` make `busy` (kb_player.lua:91).

**Record and report.** After each phase: commit (a clear message; the attribution lines the session carries), a
PROGRESS.md entry under "The bike" (numbers only: counts, cells, frames, ticks of lag), and the plan's results in §9 of
this file. Pictures and videos go to the user with SendUserFile (30 MiB limit; shrink video with ffmpeg libx264 crf 26).
Pushes only on the user's go, after the scan every push had: author and committer the noreply identity, no `local/`,
`refs/`, binaries, secrets or personal addresses; a bundle backup first in `/home/claude/backups/`
(`kantobridge-<date>-<hash>.bundle` + `.sha256`). When everything is done, or nothing more can be done alone: stop every
pod, confirm it, and finish with a summary (done, the measurements, left and why).

## 9. Results (2026-10-26, run unattended)

Numbers only; the reasons and the choices are in PROGRESS.md "The bike".

- **B0:** Mach 30 cells up and 30 down on Route 110 from (4, 41): steps 16, 8, then 4 frames (28); lag per second at most
  2 cells (first 3 seconds), 0 after; stuck 0, teleports 0; "moved too quickly" 0. Acro wheelie 26 cells at 8 frames, plain
  25 at 6. Client 10 fps (software). Key to engine (`move.hold b`): 69-118 ms (8 presses); press to client motion 182-325
  ms. Classifier: no in-place action leaves `idle`; moving hops set `P.jumping` (read as a ledge before B1). Gate held:
  D1 stays, no B9.
- **B1:** real engine through `move.ride`: Mach 4 frames at step 3; a step while riding refused `script`; Acro B held:
  wheelie, then hop; one message side jump (16 frames, jump normal); wheelie run 8 frames. Tests: Lua move 41, game3
  observe 42, stand-ins 31, protocol 585; pytest 2,441.
