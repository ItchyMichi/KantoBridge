# Battle sounds: the hidden game's move sounds in Cobblemon battles (BS0–BS8)

Written 2026-11-02 at the user's request: "When testing the emerald world space the other day I noticed that during battle,
no hidden game attack sounds would play when attacking in cobblemon. Could you investigate whether it would possible to get
battle sounds played for attacks that are present in both cobblemon and the hidden game for all of the hidden games ? If
possible prepare a plan that could implement this feature."

**Status: optional, parked for later (the user, 2026-11-02); nothing built.** The user wants to hear what Cobblemon's own
move sounds are like in play first, then decide whether to run it. Don't start any phase until the user asks. The decisions
in §3 are defaults; the user may change any of them before a run.

## 0. In one paragraph

It is possible, for all three games, and each game can play exactly what its own battle animation would have played.
Today nothing reaches the hidden game while Cobblemon fights. The battle is held, and the hidden game only plays the battle
theme: P22, `gen1.lua:1803-1840` for Blue, and the engine's own start for Crystal and Emerald. So the only move sounds
anyone hears are Cobblemon's own, which exist for 87 moves. The plan:

1. **Server.** A server mixin on Cobblemon's `MoveInstruction` sees every move at the moment Cobblemon announces
   "X used Y!", together with what the move did (missed, charged, how many hits, super effective or not). The server
   sends one new message, `battle.move`, to each fighting player in a region world. The message carries the move's
   Showdown number, which is already how moves cross the bridge.
2. **Client.** The client forwards `battle.move` to the followed game's link, like `battle.result`. For moves the hidden
   game covers, it mutes Cobblemon's own move sound on this player's client.
3. **gen1-bridge.** gen1-bridge runs the hidden game's **own animation interpreter for sound only**, never drawn:
   - Blue: Gen 1's `AnimPlayer`.
   - Crystal: Gen 2's `AnimRunner`, with sound hooks.
   - Emerald: a second `AnimVm`.

   It steps the interpreter once per logic step and then plays the game's effectiveness hit sound. Moves the hidden game
   lacks (for example Cobblemon's newer moves) keep Cobblemon's sound.

## 1. What exists (surveyed 2026-11-02)

Engine paths are `refs/gen1recomp` at tag `v0.3.85` (the pin). Cobblemon facts come from the pinned 1.8.1 jar in the
Gradle cache (`com.cobblemon:fabric:1.8.1+1.21.1`; `refs/cobblemon` is not on this PC), read with `javap` and `unzip`.

### 1.1 The bridge today

- **Battles hold the hidden game.** Blue sits in the field under a hold and plays the Gen 1 theme through `Music.playBattle`
  (`gen1-bridge/lib/engine/gen1.lua:1803-1840`).
  - Crystal keeps the engine's start, with a placeholder `BattleState` on the stack
    (`lib/engine/gen2/battle.lua:1-38`).
  - Emerald starts the battle **headless** (`lib/engine/game3/battle.lua:1-45`). `Anim.reset({headless=true})` makes
    `AnimVm:launch` skip every move script (`src/core/game3/battle/anim.lua:274-316`, `anim_vm.lua:505-516`).
  - Nothing about the moves Cobblemon plays reaches any hidden game.
- **Natural wild battles do not involve the hidden game.** These are the habitat pools' Pokémon. The hidden game stays in
  the field, and only `route.blackout` follows a loss (PROTOCOL.md "Wild Routes rules").
- **Cobblemon's battle theme is cleared for Kanto battles** by `battle/BattleMusic.java`. This covers a battle the hidden
  game started, or any battle in a world with a published region. This plan uses the same rule (D2).
- **Moves cross as Showdown numbers**, which equal each game's move ids: Gen 1 1–165, Gen 2 1–251, Gen 3 1–354
  (`pokemon/GenOneIds.java`, `Gen1Mons.move(int)`, `lib/engine/game3/battle.lua:41-42`).
  - Each adapter maps a number to its engine move id: `engine.moveIdOfNum` (`gen1.lua:1613-1624`,
    `gen2/party.lua:118`, `game3/party.lua:126`).
  - Each adapter maps a national dex number to its species: `speciesOfDex` (`gen1.lua:1592`, `gen2/party.lua:111`,
    `game3/party.lua:116`).
  - `protocol/games.json` gives each game's `compat.maxMove`: Red/Blue 165, Crystal 251, Emerald 354. The client keeps
    the linked game's compat profile from `hello.ok` (`client/bridge/HelloOk.compatField`).
- **The relay.** An S→C message tagged with `region` is routed by the client to that region's link
  (`client/bridge/Links.java:316-330`). `battle.result` is registered that way in `client/net/GameRelayRuntime.java:95`.
  - The server tags a held battle's messages with its start's region (`BattleService.java:377-389`,
    `EncounterService.java:374-376`, `server/Regions.java:49-54`).
  - The server finds the region a player stands in with `Regions.regionAt` (`:121-124`) and `Regions.currentRegionId`
    (`:180`).
- **gen1-bridge's step.** `step(game)` (`main.lua:916`) runs once per engine logic step from the `input.step` hook
  (`main.lua:1118-1130`). Request handlers are registered with `session.on(...)`, and `answered(...)` wraps the battle
  ones (`main.lua:769-777`). Adapters expose grouped functions that the loader checks (`lib/kb_engine.lua:55-110`).
  - `battles.stats().nativeActive` says a native battle is running (`act/kb_battle.lua:347-352`). These are Safari, the
    old man, the ghost, the contest, the tower and the facilities.
- **No sound code in fabric-bridge** (only `client/weather/WeatherView.java` touches sound). There is no setting for
  sounds. `client/bridge/ClientConfig.java` reads on/off switches (`readSwitch`, `:315`).

### 1.2 Gen 1 (Blue): `AnimPlayer` is pure, and its events carry the sounds

- `AnimPlayer.new(data)` builds no images (`src/battle/AnimPlayer.lua:134-146`). Images are only made in `draw`.
  - `start(moveId, attackerIsPlayer)` (`:401`) compiles the steps and events. On the enemy's turn it swaps AMNESIA for
    CONF_ANIM and REST for SLP_ANIM (`:405-409`).
  - Each row's sound is an event `{ sound = <move id>, frame = n }` (`:478-485`, header `:52-53`). That move's
    MoveSoundTable entry (sound, pitch, tempo) is what plays.
  - `update()` advances one 60 fps frame (`:628-638`), `isDone()` (`:640-642`), and `pollEffects()` returns the events
    that are due (`:646-655`).
- **The battle screen's own path.**
  - `BattleState` makes the player from `game.data.battle_anims` (`src/battle/BattleState.lua:749-755`).
  - It polls the frame-0 events right after `start` (`:1619-1629`).
  - It routes `ev.sound` to `playAnimSound` (`:3486-3488`). That function plays the attacker's cry for GROWL and ROAR
    (`Sound.playMoveCry(data, species, data.moves[m].anim.tempo)`), and `Sound.playMove(data, data.moves[m].anim)` for
    every other move (`:3424-3446`).
  - With no animation data it plays one `Sound.playMove` (`:1630-1647`).
  - It is gated by `game.save.options.animations ~= false` (`:3296-3299`).
  - A missed move plays no animation, except EXPLODE_EFFECT (`:3301-3304`).
- **Charge turns** play `XSTATITEM_ANIM` (the enemy: `XSTATITEM_DUPLICATE_ANIM`) for CHARGE_EFFECT moves, `TELEPORT`
  for Fly, and `SLIDE_DOWN_ANIM` for Dig (`src/battle/MoveEffects.lua:657-658`, `BattleState.lua:4531-4536`).
- **Hit sounds.** `EffectRegistry.HIT_SOUNDS` = superEffective `{Super_Effective, pitch 0xe0}`, notVeryEffective
  `{Not_Very_Effective, 0x50}`, neutral `{Damage, 0x20}` (`src/battle/EffectRegistry.lua:21-29`, chosen at `:274-280`).
  They are played with `Sound.playMove` after the animation (`BattleState.lua:3672-3687`). Multi-hit moves replay the
  animation for each strike (`EffectRegistry.lua:245-251`).
- **The sound API** is `src/core/Sound.lua`: `play` `:462`, `playStereo` `:521`, `playMove` `:687` (with the channel gate
  described at `:527-548`), `playCry` `:803`, `playMoveCry` `:903`.

### 1.3 Gen 2 (Crystal): `AnimRunner` is LÖVE-free, and sound goes through hooks

- `src/battle/gen2/AnimRunner.lua` says it is LÖVE-free: "sound and cries go out through the `hooks` table" (`:25-27`).
  - `AnimRunner.new(opts)` (`:66-74`) takes `data`, `constants`, `battleTurn` (0 while the player attacks), `param`,
    `animId`, `sfxOrder` and `hooks{sound, cry, pokeballWobble}`.
  - `start(key)` (`:117`); `scriptForMove(data, moveId)` (`:134-137`); `anim_sound` (`:301-313`); `anim_cry` (`:315-320`).
  - `step()` is one 60 Hz frame and returns false when the script ends (`:444-473`).
- **The battle screen's own path.**
  - It reads `data.gen2BattleAnims` and `data.gen2Constants` (`src/ui/gen2/BattleState.lua:507-508`).
  - `startAnim` (`:1624-1700`) skips a move when `options.battleScene == false` (`:1629-1632`). Its hooks play sounds
    with `Sound.playStereo(data, name)` when `audio.sfx[name]` exists, and cries with `Sound.playCry(data, species)`
    (`:1653-1688`).
  - `animForMove` uses `anims.moves[moveId]` as the key (`:1711-1722`). A missed move plays nothing (`:2289-2301`).
  - The charge turn passes param 1 (`src/battle/gen2/Battle.lua:1689`).
  - Hit sounds are `Sfx_SuperEffective`, `Sfx_NotVeryEffective` and `Sfx_Damage`, played with `Sound.play`
    (`BattleState.lua:1704-1709`, `:2414-2420`) when the after-animation starts, right after the move's script.

### 1.4 Gen 3 (Emerald): a second `AnimVm`, stepped and never drawn

- **The tick.** `AnimVm:update` (`src/core/game3/battle/anim_vm.lua:1814-1826`) runs the script step, the sprite
  callbacks, the BG fade and `AnimTasks.update`. A headless VM finishes at once (`:1816`).
  - `AnimVm.new()` (`:142`), `setPack` (`:338`), `busy()` (`:347`).
  - `launch` opts (`:460-494`): `attackerId`/`targetId`, `attackerSide`/`targetSide`, `isReversed`,
    `attackerSpecies`/`targetSpecies`, `moveTurn`, `ctx`.
- **The engine's own test does what we need.** `tests/emerald_battle_anims_test.lua:97-142` runs a fresh `AnimVm.new()`
  with `setPack(pack)` under `Anim.reset({headless=true})`, without LÖVE. It covers all 354 moves for both sides and
  asserts no Lua error, no timeout and no task error.
- **The sound ops.**
  - `playse` and `playsewithpan` call `play_se12` → `Audio.playSe(id, {pan})` (`:111-114`, `:1502-1512`).
  - Pan is mirrored by attacker side and clamped to -64..63 (`adjust_panning`, `:54-78`).
  - `loopsewithpan` and `waitplaysewithpan` are tasks (`:1520-1540`). `waitsound` blocks on the sound tasks
    (`:1450-1457`).
  - The sound tasks themselves (double cry, high-pitch cry, cry with echo, and others) are in
    `anim_port/g4_tasks_a.lua:378-561`. Cries need species.
  - Some sounds come from visual tasks (Shock Wave, Rollout, StatsChange, …). So a runner that skipped the visuals would
    get the timing wrong and miss sounds. The whole VM has to run.
- **The shared state.**
  - `reset` (`:377-419`), `finish` (`:421-439`) and every `launch` (through `begin`, `:453-454`) wipe the module-level
    pools (AnimSprites, AnimTasks, AnimPal, `AnimCoords.bind`).
  - So a second VM may only run while `Anim._vm` is not running a visible animation. That holds in the field and in a
    headless held battle, and fails in a visible native battle or a contest.
  - The `end` op sets the BGM volume to 256 (`:1497`). `stopsound` stops every SE (`:1478-1482`). The
    `invisible`/`visible` ops write `Anim._present` (`set_visible`, `:1752-1760`); `monbg`/`clearmonbg` touch it too
  (`:1570-1609`).
- **Moves.** `Anim.scriptForMove(num)` (`anim.lua:875-890`) loads the pack on the first call (`load_pack`, `:684-763`:
  it decodes the images once, a one-time hitch). An unknown move falls back to GENERIC_HIT, which has no sound.
- **Two-turn and multi-hit moves.** The move turn is 0 on a charge turn. It is 1 on the release, and for Solar Beam in sun
  (`battle/engine.lua:952`, `:960`, `:972`). It goes up by one with each hit (`:362`). `choosetwoturnanim` takes label2
  on an odd turn (`anim_vm.lua:1660-1664`), and `jumpifmoveturn` compares the turn (`:1667-1671`).
- **Hit sounds.** `SE_NOT_EFFECTIVE` 12, `SE_EFFECTIVE` 13 and `SE_SUPER_EFFECTIVE` 14 (`src/core/game3/se_ids.lua:14-16`)
  are chosen by effectiveness and panned to the battler that was hit: -64 player, 63 foe (`battle/anim_seq.lua:85-100`).
  The scene option is `Options.battleScene(session)` (`anim_seq.lua:75-83`).
- **The gate fields.** `Battle._headless` (`battle/init.lua:51`, set `:591`) and `Battle.isActive()` (`:369`).
  `Audio.playSe(id, opts)` has no field check, so SEs play in the field and mix with the BGM (`src/core/game3/audio.lua`
  `:961-1036`).

### 1.5 Cobblemon 1.8.1

- **The moment.** `MoveInstruction.invoke` dispatches the private static lambda `invoke$lambda$1(MoveInstruction,
  PokemonBattle, BattlePokemon user, Effect, ActionEffectTimeline)`. In order, it:
  1. broadcasts the "used_move" or "used_move_on" line (`PokemonBattle.broadcastChatMessage`);
  2. calls `BattleActor.onUseMove(user, target)`;
  3. builds the action effect's context and runs `ActionEffectTimeline.run`.

  This was checked with `javap -c -p` on `MoveInstruction.class`: `onUseMove` is called inside the lambda, which runs
  from its declaration to `invoke$lambda$1$1$0`.
- **What the move did.** `InstructionSet.findInstructionsCausedBy(causer)` returns the instructions after the move, up to
  the next `CauserInstruction`. The classes that matter: `MissInstruction`, `FailInstruction`, `ImmuneInstruction`,
  `DamageInstruction` (`getExpectedTarget()`), `HitCountInstruction` (`getHitCount()`), `PrepareInstruction`,
  `SuperEffectiveInstruction` and `ResistedInstruction`.
  - `BattleMessage.hasOptionalArgument(String)` reads Showdown's `[still]`. `MoveTemplate.getNum()` is the Showdown
    number. `BattleActor.getSide()` gives the side.
- **Cobblemon's own move sounds.**
  - 136 moves have action effects (`data/cobblemon/action_effects/moves/*.json`). 87 of them have sound events
    `cobblemon:move.<id>.actor|target` (167 events in `assets/cobblemon/sounds.json`).
  - They are played **on the client** by `entity_molang` keyframes (`q.sound('move.ember.actor')`;
    `EntityMoLangActionEffectKeyframe` → `RunPosableMoLangPacket`).
  - Cobblemon's own `FabricSoundEngineMixin` injects into `SoundEngine.play` at the channel's `execute` call. A HEAD
    inject on the same method coexists with it.

## 2. The design

### 2.1 The message (PROTOCOL.md "Battles"; `protocol/messages/battle.move.schema.json`)

| Type | Dir | Data | ROM |
|---|---|---|---|
| `battle.move` | S→C, M→G | `move` (Showdown number, 1–1024), `side:"player"\|"foe"` (whose Pokémon used it, seen from the receiving player), `missed?:true` (every target missed, failed or was immune, or Showdown marked the line `[still]` with no `-prepare`), `charge?:true` (a two-turn move's first turn: `[still]` with a `-prepare` it caused), `release?:true` (the turn after that user's charge of the same move), `hits?` (2–10, from `-hitcount`), `hit?:"normal"\|"super"\|"weak"` (it damaged a Pokémon other than the user; absent: no damage), `user?`, `target?` (national dex numbers, for cries), `battle?` (the held battle's id; absent for a natural battle), `region?` | |

- `legs: ["s2c", "m2g"]`, `re: "never"`, `rom: false`. Showdown and dex numbers are Cobblemon's, and nothing is redacted.
- Fire-and-forget: no answer and no retry. The client never queues it while the link is down: a late sound is worse than
  none.
- `hit` is `super` if a `SuperEffectiveInstruction` was caused by the move, else `weak` if a `ResistedInstruction` was,
  else `normal`, and only when a `DamageInstruction` caused by the move hit a Pokémon other than the user. Recoil and
  self-damage don't count.
- gen1-bridge accepts it from the `client` and `test` roles only (the stream roles). It drops it with no answer when the
  hidden game isn't in game.

### 2.2 The server (`battle/BattleSounds.java`, `mixin/MoveInstructionMixin.java`)

- **The mixin.** Target `MoveInstruction`, method `invoke$lambda$1`.

  ```java
  @Mixin(value = MoveInstruction.class, remap = false)
  abstract class MoveInstructionMixin {
      @Inject(method = "invoke$lambda$1",
              at = @At(value = "INVOKE", target = "Lcom/cobblemon/mod/common/api/battles/model/actor/BattleActor;"
                      + "onUseMove(Lcom/cobblemon/mod/common/battles/pokemon/BattlePokemon;"
                      + "Lcom/cobblemon/mod/common/battles/pokemon/BattlePokemon;)V"),
              require = 0)
      private static void kantobridge$moveUsed(MoveInstruction self, PokemonBattle battle, BattlePokemon user,
              Effect effect, ActionEffectTimeline timeline, CallbackInfoReturnable<DispatchResult> cir) {
          BattleSounds.onMove(self, battle, user);
      }
  }
  ```

  - `require = 0`: if a later Cobblemon renumbers the lambda, the feature goes quiet instead of stopping the game.
  - `BattleSounds.mixinApplied()` turns true on the first call. A GameTest checks the class carries the injector
    (§4 BS2).
  - Register it in `kantobridge.mixins.json`.
- **`BattleSounds.onMove`** runs on the server thread, catches everything and counts failures.
  1. `MoveFacts.of(self)`: `num = self.getMove().getNum()`. `still = self.getMessage().hasOptionalArgument("still")`.
     `caused = self.getInstructionSet().findInstructionsCausedBy(self)`. From these it derives `missed`, `charge`,
     `hits` and `hit` as in §2.1. `user`/`target` are `getEffectedPokemon().getSpecies().getNationalPokedexNumber()`
     (target: `self.getTargetPokemon()`, nullable).
  2. **Release.** Keep an LRU of at most 64 battles, keyed by `battle.getBattleId()`, each a map from the user
     `BattlePokemon.getUuid()` to the charged move number. A `charge` stores the move. The same user's next move with
     the same number and no `charge` sets `release` and clears the entry. Any other move from that user also clears it.
  3. **Recipients.** Every `PlayerBattleActor` in `battle.getActors()` whose `getEntity()` is online, in a world where
     `Regions.exists(server)`, or with a running `BattleService`/`EncounterService` battle (D2). Spectators aren't actors
     and get nothing. `side = user.getActor().getSide() == actor.getSide() ? "player" : "foe"`.
  4. **Region and battle id.** If `BattleService` or `EncounterService` has a run for that player, use the run's `region`
     and `id`. Add small `regionOf(UUID)` / `idOf(UUID)` lookups to both. Otherwise use
     `Regions.currentRegionId(player, null)` and no `battle`.
  5. `RelayService.get().send(player, MsgTypes.BATTLE_MOVE, Regions.tag(data, server, region), null)`.
     `MsgTypes.BATTLE_MOVE` is generated from the catalogue by Gradle.
  6. Counters, counts only: `sent`, `skipped`, `failed`, `mixinApplied`. Log nothing per move.
- Building the payload is a pure static `BattleSounds.payload(MoveFacts, boolean ownSide, @Nullable String battle)` so
  JUnit can cover it without Cobblemon running.

### 2.3 The client (`client/battle/BattleSoundRelay.java`, `client/battle/CobblemonMoveMute.java`, `mixin/client/SoundEngineMixin.java`)

- **The setting.** `kantobridge-client.json`, key `battleSounds`:
  - `"on"` (the default): forward, and mute Cobblemon's sound for covered moves;
  - `"both"`: forward, mute nothing;
  - `"off"`: forward nothing, mute nothing (today's behaviour).

  Add it to `ClientConfig` (a three-way reader beside `readSwitch`; a bad value warns and falls back to `on`), to its
  javadoc list, and to the record.
- **Forwarding.** In `GameRelayRuntime` (beside `:95`), register
  `runtime.links.onServer(MsgTypes.BATTLE_MOVE, false, (link, m) -> runtime.relay(link).moveSound(m))`.
  - `GameRelay.moveSound` sends `port.toBridge(MsgTypes.BATTLE_MOVE, data)` only while the link is up and
    `battleSounds != off`. Otherwise it drops the message and counts it (`moveSoundsDropped`).
  - It never records or retries. Add `battle.move` to `RegionTags.TAGGED` (its schema carries `region`).
- **Muting Cobblemon.** When `GameRelay.moveSound` forwards a move with `move <= link.hello().compat().maxMove()` and the
  setting is `on`, it calls `CobblemonMoveMute.cover(nowMs)`, which sets `muteUntil = now + 6000`. A forwarded move the
  game lacks calls `CobblemonMoveMute.uncover()` (`muteUntil = 0`), so Cobblemon's sound plays for it.
  - `shouldMute(ResourceLocation id, long now)` is true when `ClientBattles.active()`, `now < muteUntil`, namespace
    `cobblemon`, and the path starts with `move.`.
  - `SoundEngineMixin`:
    `@Inject(method = "play", at = @At("HEAD"), cancellable = true) void kantobridge$muteCobblemonMove(SoundInstance sound, CallbackInfo ci)`
    cancels when `shouldMute(sound.getLocation(), now)`. This covers `SoundManager.play` and `playDelayed`, since both
    end in `SoundEngine.play`. Count the mutes.
  - Other players nearby are not in this battle (`ClientBattles.active()` is false for them), so they keep hearing
    Cobblemon's sounds.

### 2.4 gen1-bridge's core (`act/kb_battlesound.lua`, `main.lua`, `lib/kb_engine.lua`)

- **The interface group.** `sound = { "moveSoundReady", "moveSoundStart", "moveSoundStep", "moveSoundStop",
  "moveSoundHit" }` in `kb_engine.lua`'s groups. Every adapter implements them (§2.5–2.7).
- **The contract** (`count` is a function the driver calls once for each sound it asks the engine to play):

  | Function | Returns | Meaning |
  |---|---|---|
  | `moveSoundReady()` | `true`, or `false, reason` | The engine can run a sound track now (reasons: `unavailable`, `visible_battle`, `anim_busy`). |
  | `moveSoundStart(job, pass, count)` | `track`, or `nil, reason` | Starts pass `pass` (1-based) of `job`. Reasons: `range` (no engine move for `job.move`), `option` (the game's battle animations are off), `unavailable`. A track may already be done (`track.done`). |
  | `moveSoundStep(track)` | boolean | One logic step; true while sounds may still come. |
  | `moveSoundStop(track)` | — | Drop it. Never resets shared engine state (Gen 3: `vm.active = false`, no `vm:reset()`). |
  | `moveSoundHit(job, count)` | — | The game's effectiveness sound for `job.hit`, panned or picked for the side that was hit. |

- **`BattleSound.new{engine, log}`** keeps one job at a time.
  - `receive(data, generation)` validates the fields as the schema does and refuses a malformed message with
    `bad_message`. Then:
    - in game? (else `skipped.not_ingame`);
    - a native battle running (`battles.stats().nativeActive`)? (`skipped.native`);
    - `missed` and the move is not Selfdestruct (120) or Explosion (153)? (`skipped.missed`; D6);
    - otherwise it makes the job (passes = `hits` or 1, at most 10). A running job is replaced
      (`moveSoundStop`, `replaced`).
  - `step(live, generation)` is called once per logic step from `step(game)` in `main.lua`, after `battles.step` and
    guarded by `pcall` like the route step.
    - A dropped or changed session clears the job (`dropped`).
    - With no track, it asks `moveSoundReady`; not ready clears the job (`skipped.<reason>`). Then it calls
      `moveSoundStart(job, pass, count)`. `nil, option` still plays the hit sound for the pass (D10). `nil, range` ends
      the job.
    - With a track, it calls `moveSoundStep`. When that returns false, or after `CAP = 1500` steps (`capped`, with
      `moveSoundStop`), it plays `moveSoundHit` when `job.hit` is set. Then the next pass, or the job ends (`played`).
- **Registration in `main.lua`.** `session.on("battle.move", answered(function(d) return battleSound.receive(d,
  session.generation()) end))`. Add `debug.status.moveSounds`:
  - `received`, `played`, `passes`, `hits`, `requests` (the drivers' `count` calls), `replaced`, `capped`, `dropped`;
  - `skipped{not_ingame, native, missed, range, option, unavailable, visible_battle, anim_busy}`;
  - `last{move, passes, steps, requests}` (numbers only).
- **Logging.** `log.once` per reason code; no per-move info lines.

### 2.5 Blue (`lib/engine/gen1.lua`, a new section "move sounds (BATTLE_SOUNDS_PLAN)")

- **Ready:** `game.data` has `moves` and `audio.sfx`, and `src.core.Sound` has `playMove` (else `unavailable`, with a
  `log.once("engine.move_sound_unavailable")`).
- **Start:**
  1. `id = engine.moveIdOfNum(job.move)`; nil → `range`.
  2. `game.save.options.animations == false` → `option` (`BattleState.lua:3296-3299`).
  3. `attackerIsPlayer = job.side == "player"`.
  4. The animation id is `id`. On `job.charge` it is instead `SLIDE_DOWN_ANIM` for DIG, else
     `MoveEffects[data.moves[id].effect].charge.anim` (the foe: `.enemyAnim` when set), else
     `XSTATITEM_ANIM`/`XSTATITEM_DUPLICATE_ANIM` (`MoveEffects.lua:657-658`, `BattleState.lua:4531-4536`).
  5. With `game.data.battle_anims` and `src.battle.AnimPlayer`: `p = AnimPlayer.new(game.data.battle_anims)`,
     `p:start(animId, attackerIsPlayer)`, then route `p:pollEffects()` at once, since frame-0 rows play at the start as
     `BattleState.lua:1627-1629` does.
     - If `p:isDone()` straight after start (no data for that id), use the fallback below.
  6. Without a player, the fallback plays one sound: `Sound.playMove(data, data.moves[id].anim)`, or the cry for
     GROWL/ROAR. The track is then done (`BattleState.lua:1630-1647`).
- **Routing an event.** `ev.sound` (a move id `m`):
  - when the animation is GROWL or ROAR, `Sound.playMoveCry(data, engine.speciesOfDex(user dex), data.moves[m].anim.tempo)`;
    no species → nothing;
  - else `Sound.playMove(data, data.moves[m].anim)`.

  The user dex is `job.user`. Other events (SE_*, SFX_BALL_TOSS/TINK) play nothing here. `count()` runs for every call
  made.
- **Step:** `p:update()`, route `p:pollEffects()`, and return `not p:isDone()`.
- **Hit:** `local spec = require("src.battle.EffectRegistry").HIT_SOUNDS[super → "superEffective", weak →
  "notVeryEffective", else "neutral"]`, then `Sound.playMove(data, { sound = spec.sound, pitch = spec.pitch })`.
- Blue's held battle and the field both qualify. The core already refuses native battles.

### 2.6 Crystal (`lib/engine/gen2/sound.lua`, added to `Gen2.PARTS`)

- **Ready:** `data.gen2BattleAnims.scripts`, `data.audio.sfx`, `src.battle.gen2.AnimRunner` and `src.core.Sound` exist
  (else `unavailable`).
- **Start:**
  1. `id = adapter.moveIdOfNum(job.move)`; nil → `range`.
  2. `game.options.battleScene == false` → `option`.
  3. `key = AnimRunner.scriptForMove(anims, id)`; nil, or not in `anims.scripts` → `range`.
  4. `species` for the two sides: player = the user when `side == "player"`, else the target (and the reverse for enemy),
     each through `speciesOfDex`.
  5. Create the runner:

     ```lua
     AnimRunner.new{ data = anims, constants = data.gen2Constants, battleTurn = side == "player" and 0 or 1,
                     animId = id, param = job.charge and 1 or 0, sfxOrder = data.audio.sfxOrder,
                     flying = { player = false, enemy = false },
                     hooks = { sound = function(name) if name and audio.sfx[name] then count(); Sound.playStereo(data, name) end end,
                               cry = function(s) local sp = species[s]; if sp and audio.cries and audio.cries[sp] then count(); Sound.playCry(data, sp) end end,
                               pokeballWobble = function() return 0 end } }
     ```

  6. `runner:start(key)`.
- **Step:** `return runner:step()`.
- **Hit:** `Sfx_SuperEffective` / `Sfx_NotVeryEffective` / `Sfx_Damage` with `Sound.play(data, name)` when
  `audio.sfx[name]` exists (`BattleState.lua:1704-1709`, `:2414-2420`).

### 2.7 Emerald (`lib/engine/game3/sound.lua`, added to `Game3.PARTS`)

- **Ready:** `Bt = b.g3("battle")`, `Anim = b.mod("src.core.game3.battle.anim")`,
  `AnimVm = b.mod("src.core.game3.battle.anim_vm")` and `Audio = b.g3("audio")` all load (else `unavailable`). Then:
  - `Bt.isActive()` and not `Bt._headless` → `visible_battle`;
  - `Anim._vm` and `Anim._vm.active` and not `Anim._vm.headless` → `anim_busy`.
- **Start:**
  1. `b.g3("options").battleScene(b.session()) == false` → `option` (as `anim_seq.lua:75-83` reads it).
  2. `script = Anim.scriptForMove(job.move)` in a pcall. It is `range` when `Anim._pack` or `Anim._pack.moves[job.move]`
     is missing, or when `job.move > 354`.
  3. Snapshot `Anim._present[id].visible` for ids 0–3.
  4. Create the VM: `vm = AnimVm.new(); vm:setPack(Anim._pack)`, then

     ```lua
     vm:launch(script, { attackerId = side == "player" and 0 or 1, targetId = side == "player" and 1 or 0,
                         attackerSide = side == "player" and "player" or "enemy",
                         targetSide = side == "player" and "enemy" or "player",
                         isReversed = side ~= "player",
                         attackerSpecies = speciesOfDex(job.user), targetSpecies = speciesOfDex(job.target),
                         moveTurn = turn, ctx = {} })
     ```

     `turn` is 1 on `release`, `pass - 1` for a multi-hit pass, else 0 (`charge` included).
  5. Count sounds by wrapping `Audio.playSe` and `Audio.playCry` only during our `vm:launch` and `vm:update` calls: set
     the wrapper, run the call, and restore it in a `finally`-style pcall. The wrapper calls `count()`, then the
     original.
- **Step:** run `vm:update(1/60)` under that wrapper, and return `vm:busy()`. When it ends, restore the visibility
  snapshot (Fly/Dig leave `invisible` behind).
- **Stop:** set `vm.active = false`, restore the snapshot, and drop the VM. Never call `vm:reset()`: it would wipe the
  shared pools.
- **Hit:** `SE = b.mod("src.core.game3.se_ids")`. Play `SE_SUPER_EFFECTIVE` / `SE_NOT_EFFECTIVE` / `SE_EFFECTIVE`, panned
  to the side that was hit (-64 when the target is the player's, else 63): `Audio.playSe(id, { pan = pan })`.
- **The pack.** The first `scriptForMove` decodes the pack's images once, a visible hitch. Load it when a held battle
  starts: in the wrapper's held path, call `Anim.scriptForMove(1)` in a pcall right after the battle is armed. Time it
  with `kb_perf`.

### 2.8 Not touched

Battle starts, results, music, the hold, Cobblemon's battle rules, the battle screen and other players' sounds all stay as
they are. So do Cobblemon cries on send-out, and everything outside battles.

## 3. Decisions (defaults proceed unless the user says otherwise)

| # | Decision |
|---|---|
| D1 | **Scope:** each move's battle-animation sounds plus the effectiveness hit sound, for moves the hidden game has (Showdown number ≤ its `compat.maxMove` and an animation in its data). Not in scope: send-out, recall, faint, ball, stat-change and status animations, low-HP alarm, level-up (§6). |
| D2 | **Which battles:** every Cobblemon battle a player fights (never spectates) in a world with a published region, or one the hidden game started: held and natural wild battles alike, and PvP. This is BattleMusic's rule. Each player hears their own followed hidden game. |
| D3 | **Both sides' moves:** the player's and the opponent's, with `side` from the receiving player's view. |
| D4 | **Faithful tracks:** each game runs its own interpreter for sound only, never drawn, one frame per logic step: Gen 1 `AnimPlayer`, Gen 2 `AnimRunner`, Gen 3 a second `AnimVm`. No hand-made sound tables. |
| D5 | **Cobblemon's own move sounds** are muted on the fighter's client for covered moves (`battleSounds: "on"`). Moves the game lacks keep them. `"both"` keeps all of them, and `"off"` restores today's behaviour. |
| D6 | **A miss plays no track** (all three games skip the animation). This covers fail, immune, and `[still]` without `-prepare`. Selfdestruct (120) and Explosion (153) play anyway. |
| D7 | **Two-turn moves:** the charge turn plays the game's charge track (Gen 1 charge animation ids, Gen 2 param 1, Gen 3 turn 0). The release plays the move (Gen 3 turn 1). Solar Beam in sun plays the charge track, a known gap (§5). |
| D8 | **Multi-hit moves:** the track replays for each hit, each followed by its hit sound (Gen 3 turn `hit − 1`). At most 10 passes. |
| D9 | **Overlap:** a new `battle.move` replaces the running job. Sounds already started finish. |
| D10 | **The game's own battle-animation option is respected** (Gen 1 BATTLE ANIMATION, Gen 2 and Gen 3 BATTLE SCENE): off means no track, but the hit sound still plays. |
| D11 | **Cries inside tracks** (Growl, Roar, Gen 2 `anim_cry`, Gen 3 cry tasks) are the hidden game's cry of that species. A species the game lacks plays nothing for that cry; the rest of the track plays. |
| D12 | **Fire-and-forget:** no answer, no retry, never queued while a link is down. Nothing plays without a stream role connected. |
| D13 | **Every engine member used is optional** (guarded, not in `REQUIRES`). A missing one turns that game's move sounds off, with one `engine.move_sound_unavailable` log and a count. It is never an error elsewhere. |
| D14 | **The Cobblemon mixin is `require = 0`:** a changed Cobblemon disables the feature quietly (`mixinApplied` false, which a GameTest catches). |
| D15 | **Caps:** 1500 logic steps per pass. A job holds one move; nothing is queued behind it. |

## 4. Phases

Each phase ends with its named tests passing, a commit, and a PROGRESS.md line under a new section "Battle sounds".

| Phase | Work | Tests |
|---|---|---|
| **BS0** | Re-check §1's citations on the current tree. Confirm with `javap -c -p` that `invoke$lambda$1` still holds the `onUseMove` call, at the pinned Cobblemon. Record the result in §9. | none (a check) |
| **BS1** | **The protocol:** `protocol/messages/battle.move.schema.json` and a synthetic example in `protocol/examples/`. Regenerate the catalogue (`.venv\Scripts\python.exe -m kb_protocol.catalog --write` from `tools/`) and Lua's copy (`tools/lua/gen_catalog.py`). Add PROTOCOL.md's row and a "Battle sounds rules" list (§2.1, D6–D9, `debug.status.moveSounds`). `RegionTags.TAGGED` gets it. `tools/fake_gen1/server.py` records it (as it records `battle.result`), and `fake_minecraft` can send it. | `test_schemas.py`, the catalogue staleness tests, `test_lua_kb_proto.py`, the `RegionTags` JUnit |
| **BS2** | **The server:** `MoveFacts`, `BattleSounds` (payload, recipients, region, LRU), `MoveInstructionMixin`, and the `BattleService`/`EncounterService` lookups. | JUnit `BattleSoundsTest`: payload fields for a plain hit, super, weak, miss, immune, `[still]`+prepare (charge), the release after a charge, a cleared charge, hits 3, a recoil-only DamageInstruction (no `hit`), side from either actor. GameTest `battleSoundsMixinPresent`: `MoveInstruction` has the injector (reflect for the generated handler `kantobridge$moveUsed`, or Mixin's applied list). |
| **BS3** | **The client:** the `battleSounds` setting, `GameRelay.moveSound`, the registration, `CobblemonMoveMute`, `SoundEngineMixin`. | JUnit: `ClientConfigTest` (on/both/off/bad value), `CobblemonMoveMuteTest` (covered, uncovered, expiry, not in battle, other namespaces, non-`move.` paths), `LinksTest`/`GameRelay` (forwarded to the tagged region's link; dropped when off or the link is down; never recorded). |
| **BS4** | **gen1-bridge's core:** `act/kb_battlesound.lua`, `main.lua` registration and step call, `debug.status.moveSounds`, and the `sound` group in `kb_engine.lua` with stubs that answer `unavailable`. | `tools/lua/test_lua_battlesound.py` on a stub engine: validation, refusals and every skip reason, passes and hit order (track, hit, track, hit), replacement, cap, a session drop, `requests` counting, a native battle running. |
| **BS5** | **Blue** (§2.5). Add an `AnimPlayer` stand-in to `tools/lua/fake_engine.lua`: made-up anim rows with sound events at frames 0, 5 and 12, a GROWL row, and `data.moves` anim tables with made-up names. | `tools/lua/test_lua_movesound.py`: frames of each request, GROWL → cry with tempo, enemy AMNESIA swap, charge ids (CHARGE_EFFECT, FLY, DIG), no-data fallback, option off → hit only, range, the hit sounds' spec per effectiveness. |
| **BS6** | **Crystal** (§2.6), with an `AnimRunner` stand-in in `fake_engine2.lua`. | `test_lua_gen2_movesound.py`: hooks → `playStereo`/`playCry` counts, battleTurn by side, param 1 on charge, species per side, `battleScene` off, hit names. |
| **BS7** | **Emerald** (§2.7), with `Anim`/`AnimVm`/`Audio`/`Battle` stand-ins in `fake_engine3.lua`. The VM stand-in emits SEs on given frames and has a `busy()`. | `test_lua_game3_movesound.py`: the gates (visible battle, busy `Anim._vm`), launch opts by side, turn rules (charge 0, release 1, multi-hit k−1), the visibility snapshot restored, stop never calls `reset`, `playSe` wrapped only during our calls, hit SE ids and pan, the pack warm call on a held start. |
| **BS8** | **Live checks on the pod and the docs.** Real engines with fake_minecraft: extend `tools/e2e/test_battle.py` (Blue), `test_crystal.py` `_battles` and `test_emerald.py` "battle". While the battle is held, send `battle.move` for: a damaging move with `hit:"super"`; a charge then release of a two-turn move; `hits:3`; `missed`; a number above the game's `maxMove`. Assert from `debug.status.moveSounds`: passes and hits as sent, `requests > 0` for each played move, `skipped.missed` 1, `skipped.range` 1, no `failed`, no `engine.*` warning in the log. Live client: a new `tools/e2e/battle_sounds.py` copying `battle_roam.py`'s path to Blue's rival battle. The pilot fights; assert the server's `sent > 0`, the client's forwarded count equals what the hidden game `received`, and `played > 0`. Docs: CLAUDE.md's plan list gets a line; PLAYTEST.md gets "Battle sounds" (§4.1); REFERENCES.md records the Cobblemon mixin target under "Calling Cobblemon from Java"; this file's §9. | the extended e2e, `battle_sounds.py`, then the full `check.ps1`/`check.sh` with `--e2e` on the pod |

### 4.1 PLAYTEST.md "Battle sounds" (the user's check)

1. Blue: fight the Oak's-lab rival. Each Tackle or Scratch plays Gen 1's move sound in Gen1Recomp's window, followed by
   the hit sound, as the message shows. Growl plays the hidden game's cry. Cobblemon's own Tail Whip sound is silent.
2. Crystal and Emerald: one trainer battle each. Emerald's Fly or Dig plays its charge sound on the first turn and its
   attack sound on the second. A multi-hit move plays one sound per hit.
3. A natural wild battle in each region: move sounds play over the field music.
4. Set `"battleSounds": "both"`, then `"off"`, in `kantobridge-client.json` (restart), and hear the difference.
5. Turn the hidden game's battle animations off in its OPTION menu (through the mirror): only the hit sounds remain.

## 5. Risks

- **Gen 3's shared pools.** A second VM is safe only while `Anim._vm` is idle. The gate refuses otherwise. A battle that
  turns visible mid-track, or `Anim.reset`, cuts our loops short; that is accepted.
  - The `end` op forces the BGM volume to 256. That is the normal level in a held battle and in the field, but the risk
    stays listed.
  - `stopsound` stops every SE, including field SEs. A new SE also replaces whatever plays on the same m4a player
    (`audio.lua:975-977`).
  - A few sprite callbacks read the global `Anim._vm` instead of ours (`anim_port/g1_pret.lua:106-110`), so some pans
    may come out centred.
- **Hitches on first use.**
  - Gen 3 decodes its pack once (mitigated in §2.7), and each SE is synthesised on its first play (`audio.lua:912-945`).
  - Gen 1 chip sounds are synthesised on first play too (`Sound.prewarmMove` exists, `Sound.lua:878`; not used by
    default).
  - Measure them with `kb_perf` at BS8, and prewarm only if a step goes over 16 ms.
- **Timing against Cobblemon.** Our track starts with the "used" line. Cobblemon's in-world animation starts about 1.25 s
  later (the action effects' `delay`). This is the Gen 1–3 order (message, then animation), so it is accepted.
  - Long Gen 3 tracks (Sky Attack, Thunder) may run into the next move. D9 replaces them.
- **Solar Beam in sun (Gen 3)** plays the charge track: Showdown marks the line `[still]` with `-prepare` and sends no
  release line.
- **Text boxes that wait for sound.** Gen 1 text boxes can hold for a sound effect (`sfxWait`, `gen1.lua:929-964`). A hit
  sound still playing when the result's texts begin may hold the first page for a fraction of a second.
- **`require = 0` hides breakage.** That is why the GameTest checks the mixin is present and the e2e checks `sent > 0`.
- **The mute window.** 6 s covers Cobblemon's longest action effect for these moves (about 3.5 s). A foreign move inside
  that window gets its sound back because it calls `uncover`.

## 6. Not in this plan

- Other battle sounds: send-out and recall, faint, ball throw and wobble, stat-change and status animations (Gen 2's
  after-animations, Gen 3's general animations), weather, the low-HP alarm, level-up and the experience bar.
- The hidden game's battle theme for natural wild battles (today the field music keeps playing).
- Spectators' sounds, and Cobblemon battles outside region worlds.
- Contest, Battle Tower and facility battles. They stay native in the hidden game and already have their own sounds.

## 7. Sizes (rough)

| Part | Code | Tests |
|---|---|---|
| Protocol (schema, docs, fakes) | ~80 lines | existing suites |
| Server (BattleSounds, MoveFacts, mixin, lookups) | ~260 lines Java | ~220 lines JUnit + 1 GameTest |
| Client (setting, relay, mute, mixin) | ~180 lines Java | ~200 lines JUnit |
| gen1-bridge core | ~220 lines Lua | ~300 lines pytest |
| Blue / Crystal / Emerald drivers | ~150 / ~130 / ~200 lines Lua | ~150 each, plus fakes |
| e2e | ~250 lines Python | — |

## 8. Running it without the user (the recipe)

The user has read D1–D15 and won't answer questions while this runs. Decide by the defaults. Where the plan is silent,
keep the game's behaviour and write the choice in PROGRESS.md. A blocked phase is recorded with its reason, and the work
moves on to the next phase that doesn't depend on it. The final report lists what is done and what isn't.

- **Hard rules (CLAUDE.md).**
  - Never open `local/` (ROM-derived). Never copy code from `refs/`: every engine rule above is called through the
    engine's own functions, and nothing is re-implemented from its source.
  - Logs and test output carry only types, ids, counts and frame numbers. Sound names and SE numbers are ids.
  - Mojang mappings + Parchment only. JDK 21 per command (`JAVA_HOME` as in CLAUDE.md). The system Java stays 17.
  - Real-engine tests run only on a test install, never the user's play installs. This PC has no e2e test install
    (memory, 2026-10-28), so BS8 runs on a pod.
- **Order.** BS0 → BS1 → BS4 (the core with stubs) → BS2 → BS3 → BS5 → BS6 → BS7 → BS8. BS5–BS7 are independent of each
  other once BS4 is in. BS2/BS3 and BS4–BS7 meet only at the schema.
- **Local tests.** `.\tools\check.ps1 -NoGradle` for pytest and Lua. `cd fabric-bridge; $env:JAVA_HOME = 'C:\Program
  Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot'; .\gradlew.bat test` for JUnit, and `runGameTest` for the GameTest. A
  stale catalogue fails a test: regenerate it after any schema edit.
- **Pods (BS8).** Use the mechanics in BIKE_PLAN.md §8: the volume `kantobridge-dev` (jcp4eqtl70, EU-RO-1) at
  `/workspace`, `run_gen1recomp.py`, Xvfb, RemotePilot, and stopping the pod the moment its job ends.
  - From this PC, use the passphrase-less key `~/.ssh/kb_runpod_pc` on a throwaway pod shaped like kb-dev54 (image
    `eclipse-temurin:21-jdk-noble`, its sshd start command, `22/tcp`, cpu3g x4 $0.16/h; 8 vCPU for the live client).
    Take `ssh.direct` from `get-pod` about a minute after creating it.
  - State the hourly price before creating a pod. The user may need to allow `create-pod`/`pod-action` in /permissions
    first.
- **What can't be checked headlessly.** Xvfb pods have no audio device, so "heard" is the user's (PLAYTEST §4.1). The
  automated checks stop at "the engine was asked to play" (`requests`) and the timing (`last.steps`).
- **Stop and ask the user** (record it and go on with the rest) when:
  - the Cobblemon lambda no longer holds `onUseMove`;
  - a Gen 3 run logs task errors from the second VM;
  - any check needs a ROM import or a play install.
- **Record and report.** After each phase, commit (a clear message, the session's attribution lines), add a PROGRESS.md
  line (numbers only), and fill §9. No pushes without the user's go.

## 9. Results

(Empty until a run.)
