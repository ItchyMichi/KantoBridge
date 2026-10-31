# Walking about in a battle (Cobblemon's R)

Written and built 2026-10-31 at the user's request: "I want to be able to move during battle, using the R button that is a
feature in cobblemon ... reposition in combat and snap back to the hidden player position after combat ends." The user
agreed to the proposal with one change: no jumping or sneaking.

## 1. What was there

- Cobblemon 1.8.1 (tag `1.8.1`): `PartySendBinding.toggleBattleScreen` flips `ClientBattle.minimised` and reopens
  `BattleGUI` when it turns false; `BattleGUI.onClose` sets it true, and a minimised `BattleGUI` closes itself
  (`BattleGUI.kt:101`); `BattleInitializeHandler` starts every battle with it false. KantoBridge binds nothing to R.
- `InputLock.Reason.BATTLE` held the player for the whole battle, minimised or not (`MoveController.beforeMove`).
- The movement sync is unlinked for the whole battle (nothing reaches the waiting hidden game); relinking afterwards
  sets `rejoinWanted`, and `MovementSync.rejoin` teleports the player onto the hidden player's cell (`warp.request`,
  behind the fade) unless already on it. `WarpService.teleport` has no distance limit. In a Wild Routes zone the player is
  "away" and isn't put back, as before.
- `PokemonBattle.checkFlee` ends a wild battle as fled once no player is within the wild Pokémon's flee distance (12
  blocks, `CobblemonSettings.FLEE_DISTANCE`; -1 when the rules forbid running). Wild Pokémon stand about 4 blocks ahead
  (`EncounterPlacement.AHEAD`).

## 2. Decisions

| # | Decision |
|---|---|
| D1 | Minimised (R, or the screen closed) the player may walk; the screen up, the player is held as before. |
| D2 | A fence of **6 blocks** (`BattleRoam.RADIUS`) round where the player stood on the battle's first tick: walking out stops at it, along it or back in is free. 4 + 6 (+ a little momentum) stays under the 12-block flee distance, so walking never ends a battle; Run works as before. |
| D3 | No jump and no sneak while roaming (the user's change). |
| D4 | The snap back is the existing relink teleport with the fade (instant, the hidden player's facing). |
| D5 | Nothing reaches the hidden game during a battle: the sync stays unlinked, and A, Start and the bicycle key stay off (`ActRuntime.idle` counts the battle). |
| D6 | On our bike the seat stays held (a passenger's keys don't move it anyway). Native battles (Safari, the ghost, …) are hidden-game screens and unchanged. |

## 3. What was built

- `client/move/BattleRoam`: the fence (`fence(anchor, x, z, yaw, forward, left)` removes the outward part of the input).
- `InputLock`: `Reason.ROAM`; `reason(…, battle, roam, …)`; `setRoam(Roam)`: while set and unlocked, `apply` zeroes jump and
  sneak and fences the impulses.
- `MoveController`: `Frame.roam`; the anchor (`roamAnchor()`) taken on the battle's first tick, cleared after.
- `MoveRuntime`: `ClientBattles.minimised()` into the frame; roaming = `ROAM` with an anchor and no bike seat; it isn't a lock.
- `RemotePilot`: `roam {dir, ticks}` (the screen minimised, forward + jump + sneak held; result `moved`, `from_anchor`,
  `rise`, `crouched`, `lock`); `lock` in the state; the battle mode un-minimises before reopening the screen.
- Tests: `BattleRoamTest`, `InputLockTest` (roam reasons, no jump/sneak, the lock wins), `MoveControllerTest` (roam, the
  anchor, nothing sent, the snap back's `warp.request`). Live: `tools/e2e/battle_roam.py` (Blue's rival battle in Oak's lab
  on the pod). PLAYTEST.md "Walking about in a battle" is the user's check.
