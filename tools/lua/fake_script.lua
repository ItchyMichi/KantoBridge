-- Fake script runner, battles, trainers, gifts and party helpers for the fake
-- engine (M5). Written for Kanto Bridge from the engine behaviour the
-- research notes and lib/kb_engine.lua's citations document
-- (refs/_research/B_gen1recomp_engine.md sections 2 and 5,
-- P3_gen1_data_export.md section 7): the code is our own, and every species,
-- move, trainer and text the tests install is made up.
--
--   FakeScript.attach(E)       after FakeWorld.attach and FakeUi.attach
--   E.installData(data)        pokemon, moves, trainers, text, trainer_headers,
--                              constants, field (plain tables)
--   E.runScript(rows, extra)   runs command rows on the overworld's runner
--                              (the script.command hook wraps every row)
--   E.engageTrainer(npc, onDone)          a map trainer: battle text, then
--                              trainer.before_battle (OC:4038-4174's shape)
--   E.startTrainerApproach(npc)           the sight path: engaging, then engage
--   E.nativeResult             what a native (not intercepted) battle ends
--                              with when A is pressed on it (default "win")
--   E.battles                  every native battle pushed: { kind, cls, party, species, level }
--   E.afterBattles, E.rewards, E.scriptErrors, E.scriptsEnded   records
--
-- Engine modules it provides (E.modules): src.pokemon.Pokemon (movesAtLevel,
-- new with deterministic DVs, heal), src.pokemon.Stats (calc),
-- src.pokemon.Growth (expForLevel), src.battle.BattleState (stampOT),
-- src.render.TextBox (new), src.core.RomText, src.script.Flags,
-- src.mods.Runtime (wantsHook, call), src.core.GameVersion.isYellow.

local FakeScript = {}

local unpack = unpack or table.unpack

function FakeScript.attach(E)
  E.battles, E.afterBattles, E.rewards, E.scriptErrors = {}, {}, {}, {}
  E.scriptsEnded = 0
  E.nativeResult = "win"
  E.dvSeed = 0
  E.save.defeatedTrainers = E.save.defeatedTrainers or {}
  E.save.boxes = E.save.boxes or { {}, {} }
  E.save.currentBox = 1
  E.save.player.rival = E.save.player.rival or "RIVALTEST"
  E.save.player.id = E.save.player.id or 4242

  -- the stack's own push/pop, as StateStack has them (with the screen events)
  function E.stack:push(state) return E.uiPush(state) end
  function E.stack:pop() return E.uiPop() end

  -- ------------------------------------------------------------ data

  function E.installData(data)
    for k, v in pairs(data) do E.data[k] = v end
    E.data.text = E.data.text or {}
    E.data.trainer_headers = E.data.trainer_headers or {}
    -- Data:trainerHeader (src/core/Data.lua:425-428)
    E.data.trainerHeader = function(self, label, index)
      local perMap = self.trainer_headers[label]
      return perMap and perMap[index] or nil
    end
  end

  -- ------------------------------------------------------------ modules

  local Growth = {}
  function Growth.expForLevel(_, level) return level * level * level end

  local Stats = {}
  function Stats.calc(def, level, dvs, _)
    local out = {}
    for _, key in ipairs({ "hp", "attack", "defense", "speed", "special" }) do
      local base = def.baseStats and def.baseStats[key] or 50
      local v = math.floor((base + (dvs[key] or 0)) * 2 * level / 100)
      out[key] = key == "hp" and (v + level + 10) or (v + 5)
    end
    return out
  end

  local Pokemon = {}
  function Pokemon.movesAtLevel(def, level)
    local moves = {}
    local function add(id)
      for _, m in ipairs(moves) do if m == id then return end end
      moves[#moves + 1] = id
    end
    for _, m in ipairs(def.level1Moves or {}) do add(m) end
    for _, e in ipairs(def.learnset or {}) do
      if e.level <= level then add(e.move) end
    end
    while #moves > 4 do table.remove(moves, 1) end
    return moves
  end
  function Pokemon.new(data, species, level)
    local def = data.pokemon[species]
    assert(def, "unknown species " .. tostring(species))
    E.dvSeed = E.dvSeed + 1
    local s = E.dvSeed
    local dvs = { attack = (s * 3) % 16, defense = (s * 5) % 16, speed = (s * 7) % 16, special = (s * 11) % 16 }
    dvs.hp = (dvs.attack % 2) * 8 + (dvs.defense % 2) * 4 + (dvs.speed % 2) * 2 + (dvs.special % 2)
    local stats = Stats.calc(def, level, dvs)
    local moves = {}
    for _, id in ipairs(Pokemon.movesAtLevel(def, level)) do
      local md = data.moves[id]
      moves[#moves + 1] = { id = id, pp = md and md.pp or 0 }
    end
    return { species = species, level = level, exp = Growth.expForLevel(def.growthRate, level), dvs = dvs,
             statExp = {}, stats = stats, hp = stats.hp, catchRate = def.catchRate, moves = moves }
  end
  function Pokemon.heal(mon)
    mon.hp = mon.stats.hp
    mon.status = nil
    for _, mv in ipairs(mon.moves) do
      local md = E.data.moves[mv.id]
      if md then mv.pp = md.pp end
    end
  end

  local BattleState = {}
  function BattleState.stampOT(save, mon)
    mon.ot = mon.ot or save.player.name
    mon.otId = mon.otId or save.player.id
  end

  local TextBox = {}
  -- TextBox.new substitutes {PLAYER} and {RIVAL} itself (TextBox.lua:170)
  function TextBox.substitute(_, text)
    local p = E.save.player
    return (text:gsub("{PLAYER}", p.name or ""):gsub("{RIVAL}", p.rival or ""))
  end
  function TextBox.new(game, text, onDone, opts)
    local o = {}
    for k, v in pairs(opts or {}) do o[k] = v end
    o.onDone = onDone
    return E.textBox(TextBox.substitute(game, text), o)
  end

  local function romText(_, _, fallback, ...)
    return string.format(fallback, ...)
  end

  local Flags = {}
  function Flags.set(save, name) save.flags[name] = true end
  function Flags.clear(save, name) save.flags[name] = nil end
  function Flags.get(save, name) return save.flags[name] == true end

  local Runtime = {}
  function Runtime.wantsHook(name) return E.runtime.wants ~= nil and E.runtime.wants(name) or false end
  function Runtime.call(name, vanilla, ...) return E.runtime.call(name, vanilla, ...) end

  E.GameVersion.isYellow = function() return false end

  E.modules["src.pokemon.Pokemon"] = Pokemon
  E.modules["src.pokemon.Stats"] = Stats
  E.modules["src.pokemon.Growth"] = Growth
  E.modules["src.battle.BattleState"] = BattleState
  E.modules["src.render.TextBox"] = TextBox
  E.modules["src.core.RomText"] = romText
  E.modules["src.script.Flags"] = Flags
  E.modules["src.mods.Runtime"] = Runtime
  E.Pokemon = Pokemon

  -- ------------------------------------------------------------ the overworld's battle helpers

  local ow = E.ow

  -- OverworldState:afterBattle (OC:5184-5225): a wild battle's encounter
  -- grace steps; a lose blacks out, except the Oak's-lab rival
  function ow.afterBattle(self, result, battle)
    E.afterBattles[#E.afterBattles + 1] = { result = result, kind = battle and battle.kind,
                                            cls = battle and battle.oppClass }
    if battle and battle.kind == "wild" then self.wildEncounterGraceSteps = 3 end
    if result ~= "lose" then return end
    if battle and battle.oppClass == "OPP_RIVAL1" and self.map and self.map.id == "OAKS_LAB" then return end
    for _, mon in ipairs(E.save.party) do Pokemon.heal(mon) end
    E.save.money = math.floor(E.save.money / 2)
    local heal = E.healPoint or { map = self.map.id, x = 1, y = 1 }
    E.blackOutTo(heal.map, heal.x, heal.y)
  end

  -- checkVictoryRewards (OC:4251-4312): recorded; a made-up reward pushes a box
  function ow.checkVictoryRewards(_, cls, party, shown)
    E.rewards[#E.rewards + 1] = { cls = cls, party = party, shown = shown }
    local reward = E.victories and E.victories[cls .. "#" .. tostring(party)]
    if reward then
      if reward.badge then E.save.inventory[reward.badge] = 1 end
      E.uiPush(E.textBox(reward.text or "A REWARD!"))
    end
  end

  -- A native battle: a graphical screen (isBattleState) that A ends with
  -- E.nativeResult, calling onFinish(result) after it pops. info.safari,
  -- info.ghost and info.demo make the special battles BattleState:battleKind
  -- names (src/battle/BattleState.lua:1857-1862).
  function E.nativeBattle(info, onFinish)
    E.battles[#E.battles + 1] = info
    local s = { isBattleState = true, screenId = "BattleState", info = info,
                safari = info.safari, ghost = info.ghost, demo = info.demo }
    function s.battleKind(self)
      if self.ghost then return "ghost" end
      if self.safari then return "safari" end
      if self.demo then return "oldman" end
      return info.kind == "wild" and "wild" or "trainer"
    end
    function s.update(self)
      if E.game.input:wasPressed("a") then
        E.uiPop()
        if onFinish then onFinish(E.nativeResult) end
      end
    end
    E.uiPush(s)
    return s
  end

  function ow.trainerDefeated(self, npc)
    if E.save.defeatedTrainers[npc.id] then return true end
    local header = E.data.trainerHeader and E.data:trainerHeader(self.map.def.label, npc.def.index)
    return header ~= nil and header.event ~= nil and E.save.flags[header.event] == true
  end
  E.trainerDefeated = function(npc) return ow:trainerDefeated(npc) end

  -- OverworldState.prepareTrainerBattle (OC:4038-4059)
  local function prepareTrainerBattle(context, startBattle, cancelBattle)
    local wants = E.runtime.wants and E.runtime.wants("trainer.before_battle")
    if not wants then
      startBattle()
      return false
    end
    local started = false
    local function continueBattle(options)
      if started then return false end
      started = true
      if type(options) == "table" and options.cancel == true then
        if cancelBattle then cancelBattle() end
      else
        startBattle(options)
      end
      return true
    end
    E.continues = (E.continues or 0) + 1
    local deferred = E.runtime.call("trainer.before_battle", function() return false end, E.game, context,
      continueBattle)
    if deferred ~= true and not started then continueBattle() end
    return deferred == true
  end

  -- OverworldState:engageTrainer (OC:4067-4174), shortened: the battle text,
  -- then the battle (deferrable), whose win marks the trainer, sets the
  -- header's event, runs the rewards and afterBattle, then onDone.
  function E.engageTrainer(npc, onDone)
    local d = npc.def
    local header = E.data:trainerHeader(ow.map.def.label, d.index)
    local function startBattle()
      local info = { kind = "trainer", cls = d.trainerClass, party = d.trainerParty or 1 }
      E.nativeBattle(info, function(result)
        local fake = { kind = "trainer", oppClass = d.trainerClass, partyIndex = d.trainerParty or 1 }
        if result == "win" then
          E.save.defeatedTrainers[npc.id] = true
          if header and header.event then E.save.flags[header.event] = true end
          ow:checkVictoryRewards(d.trainerClass, d.trainerParty or 1, false)
        end
        ow:afterBattle(result, fake)
        if onDone then onDone() end
      end)
    end
    local function prepareBattle()
      prepareTrainerBattle({ trainerClass = d.trainerClass, partyIndex = d.trainerParty or 1,
                             mapId = ow.map.id, npcId = npc.id }, startBattle, function()
        ow.cancelledTrainerSight = { npcId = npc.id, playerX = ow.player.cellX, playerY = ow.player.cellY }
        if onDone then onDone() end
      end)
    end
    E.uiPush(E.textBox((header and header.battleText) or "LET'S BATTLE!", { onDone = prepareBattle }))
  end

  -- OverworldState:startTrainerApproach (OC:4422-4467), without the walk-up
  function E.startTrainerApproach(npc)
    ow.engaging = true
    npc.frozen = true
    E.engageTrainer(npc, function()
      npc.frozen = false
      ow.engaging = false
    end)
  end

  -- ------------------------------------------------------------ the script runner

  local Commands = {}
  E.Commands = Commands

  local Runner = {}
  Runner.__index = Runner

  -- (running = true also holds the world, as the older tests' plain runner did)
  function Runner:isRunning()
    return self.running == true or (self.co ~= nil and coroutine.status(self.co) ~= "dead")
  end
  function Runner:yield() return coroutine.yield() end

  function Runner:resume(...)
    local co = self.co
    if not co then return end
    if coroutine.status(co) == "running" then
      self.waitingFrames = 1
      return
    end
    local ok, err = coroutine.resume(co, ...)
    if not ok then
      E.scriptErrors[#E.scriptErrors + 1] = tostring(err)
      self.co = nil
    elseif coroutine.status(co) == "dead" and self.co == co then
      self.co = nil
    end
  end

  -- ScriptRunner:update (ScriptRunner.lua:290-308)
  function Runner:update()
    if self:isRunning() and self.waitingFrames then
      self.waitingFrames = self.waitingFrames - 1
      if self.waitingFrames <= 0 then
        self.waitingFrames = nil
        self:resume()
      end
    end
    if self:isRunning() and self.waitingCheck then
      local done, result = self.waitingCheck()
      if done then
        self.waitingCheck = nil
        self:resume(result)
      end
    end
  end

  -- ScriptRunner:exec (ScriptRunner.lua:144-193): every row goes through the
  -- script.command hook; jumps by row number or label; "end" halts
  function Runner:exec(script, ctx)
    local labels = {}
    for i, row in ipairs(script) do
      if row[1] == "label" then labels[row[2]] = i end
    end
    local pc = 1
    while pc <= #script do
      local row = script[pc]
      local name = row[1]
      local fn = Commands[name]
      assert(fn, "fake script: unknown command " .. tostring(name))
      local args = { select(2, unpack(row)) }
      local jump = E.runtime.call("script.command", function(hctx, _, hargs)
        return fn(hctx, unpack(hargs))
      end, ctx, name, args)
      if type(jump) == "string" then
        if jump == "end" then pc = math.huge else pc = assert(labels[jump], "missing label") end
      elseif type(jump) == "number" then
        pc = jump
      else
        pc = pc + 1
      end
    end
  end

  function Runner:run(script, extra)
    assert(not self:isRunning(), "script already running")
    local ctx = { game = E.game, overworld = ow, save = E.save, runner = self }
    for k, v in pairs(extra or {}) do ctx[k] = v end
    self.ctx = ctx
    self.co = coroutine.create(function()
      self:exec(script, ctx)
      for _, callback in ipairs(ctx.afterScript or {}) do callback() end
      if ctx.onDone then ctx.onDone() end
      E.scriptsEnded = E.scriptsEnded + 1
    end)
    self:resume()
  end

  ow.runner = setmetatable({}, Runner)
  function E.runScript(rows, extra) return ow.runner:run(rows, extra) end

  -- ------------------------------------------------------------ commands (Commands.lua's shape)

  function Commands.show_text(ctx, text)
    local runner = ctx.runner
    E.uiPush(E.textBox(text, { onDone = function() runner:resume() end }))
    runner:yield()
  end

  function Commands.ask(ctx, text)
    local runner = ctx.runner
    E.uiPush(E.textBox(text, { choice = function(yes)
      ctx.lastCheck = yes
      runner:resume()
    end }))
    runner:yield()
  end

  function Commands.label() end
  function Commands.jump(_, target) return target end
  function Commands.jump_if_true(ctx, target) if ctx.lastCheck then return target end end
  function Commands.jump_if_false(ctx, target) if not ctx.lastCheck then return target end end
  function Commands.set_flag(ctx, name) ctx.save.flags[name] = true end
  function Commands.clear_flag(ctx, name) ctx.save.flags[name] = nil end
  function Commands.check_flag(ctx, name) ctx.lastCheck = ctx.save.flags[name] == true end
  function Commands.save_end_battle_text(ctx, text) ctx.endBattleText = text end

  function Commands.heal_party(ctx)
    for _, mon in ipairs(ctx.save.party) do Pokemon.heal(mon) end
  end

  function Commands.move_player(ctx, dir, tiles)
    local runner = ctx.runner
    E.scriptMove(ow.player, dir, tiles, function() runner:resume() end)
    runner:yield()
  end

  -- start_battle (Commands.lua:339-393): the checkpoint resume path first
  function Commands.start_battle(ctx, kind, a, b)
    local runner = ctx.runner
    local resumed = ctx.resumeBattle
    if resumed then
      ctx.resumeBattle = nil
      local result, battle = resumed.result, resumed.battle
      ctx.lastBattleResult = result
      ctx.lastCheck = result == "win"
      E.resumed = (E.resumed or 0) + 1
      if result == "win" then
        ctx.afterScript = ctx.afterScript or {}
        table.insert(ctx.afterScript, function() ow:afterBattle(result, battle) end)
      else
        ow:afterBattle(result, battle)
      end
      return
    end
    local info = { kind = kind, cls = kind ~= "wild" and a or nil, party = kind ~= "wild" and b or nil,
                   species = kind == "wild" and a or nil, level = kind == "wild" and b or nil }
    local fake = { kind = kind == "wild" and "wild" or "trainer", oppClass = info.cls, partyIndex = info.party }
    ctx.endBattleText = nil
    E.nativeBattle(info, function(result)
      ctx.lastBattleResult = result
      ctx.lastCheck = result == "win"
      ow:afterBattle(result, fake)
      runner:resume()
    end)
    runner:yield()
  end

  -- rival_battle (Commands.lua:1047-1076), Red/Blue
  function Commands.rival_battle(ctx, cls, base)
    local offset = 0
    if ctx.save.flags.EVENT_CHOSE_SQUIRTLE then offset = 1 elseif ctx.save.flags.EVENT_CHOSE_BULBASAUR then offset = 2 end
    Commands.start_battle(ctx, "trainer", cls, base + offset)
  end

  -- static_battle (Commands.lua:991-1000)
  function Commands.static_battle(ctx, species, level, beatFlag)
    Commands.start_battle(ctx, "wild", species, level)
    if ctx.lastBattleResult ~= "lose" then
      if beatFlag then ctx.save.flags[beatFlag] = true end
      if ctx.npc then E.hiddenObjects = (E.hiddenObjects or 0) + 1 end
    end
  end

  -- AskName (Commands.lua:761-794): YES/NO, then the naming screen
  local function askNickname(ctx, mon)
    local runner = ctx.runner
    local success = ctx.lastCheck
    E.uiPush(E.textBox("NICKNAME IT?", { choice = function(yes)
      if not yes then
        ctx.lastCheck = success
        runner:resume()
        return
      end
      E.uiPush(E.namingScreen({ title = "NICKNAME?", maxLen = 10, mon = mon, onDone = function(nick)
        if nick and #nick > 0 then mon.nickname = nick end
        ctx.lastCheck = success
        runner:resume()
      end }))
    end }))
    runner:yield()
  end

  -- give_pokemon (Commands.lua:807-869)
  function Commands.give_pokemon(ctx, species, level, skipNickname)
    local gift = { ctx = ctx, species = species, level = level }
    E.runtime.emit("pokemon.before_give", gift)
    species, level = gift.species, gift.level
    local mon = Pokemon.new(E.data, species, level)
    if gift.nickname then mon.nickname = gift.nickname end
    BattleState.stampOT(ctx.save, mon)
    local boxNum
    if #ctx.save.party < 6 then
      ctx.save.party[#ctx.save.party + 1] = mon
    else
      local box = ctx.save.boxes[ctx.save.currentBox]
      box[#box + 1] = mon
      boxNum = ctx.save.currentBox
    end
    ctx.save.pokedex.seen[species] = true
    ctx.save.pokedex.owned[species] = true
    ctx.lastCheck = true
    ctx.addedToParty = boxNum == nil
    ctx.boxNum = boxNum
    if not gift.nickname and not skipNickname and ctx.runner then askNickname(ctx, mon) end
  end
end

return FakeScript
