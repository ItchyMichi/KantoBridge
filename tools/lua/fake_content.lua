-- Fake M8 (wave 2) content for the fake engine: direct battles
-- (OverworldState:pushBattle with BattleState.newWild/newTrainer and their
-- ghost/safari/demo variants), the talk path's static Pokemon objects and
-- talk scripts behind the world.talk hook, in-game trades (Commands.trade),
-- the Name Rater, the daycare, native catches (pokemon.caught), the Hall of
-- Fame, a ghost trigger like the Marowak's, a hideout boss like Giovanni's,
-- and the music API as a recorder. Written for Kanto Bridge from the
-- engine behaviour lib/kb_engine.lua cites (v0.3.85: OC:1261-1295
-- pushBattle, OC:3600-3622 talkTo's static branch,
-- src/battle/BattleState.lua:850-1120 and :5649-5714,
-- src/script/Commands.lua:920-943 and :1093-1184,
-- data/scripts/story2.lua:1020-1210, story3.lua:134-194 and :458-513,
-- story4.lua:744-818, src/core/Music.lua:439-545); the code is our own and
-- every species, trainer, text and map the tests install is made up.
--
--   FakeContent.attach(E)     after FakeCenter.attach
--   E.newWild(species, level), E.newTrainer(cls, party)   BattleState stand-ins
--                             (M8 wave 3: also the module's newTrainer, which
--                             engageTrainer's start calls; E.trainerBuilds)
--   ow:pushBattle(battle, npc)   the theme, then a native battle screen (A
--                             ends it with E.nativeResult, then onFinish)
--   E.pushes                  every battle pushBattle received
--   E.talkTo(npc)             statics (def.pokemon: the text, then the
--                             battle pushed directly), E.talkFns[id](npc,
--                             done), E.talkRows[id] (command rows), else
--                             fake_ui's talk
--   Commands.trade, Commands.record_hall_of_fame
--   E.partyPick(onSwitch, onCancel)   a pickOnly party menu (a mirror screen)
--   E.nameRaterTalk, E.daycareTalk, E.bossTalk   talk functions
--   E.ghostTrigger(map, x, y, species, level, flag)   a step trigger
--   E.catchBattle(species, level, opts)   a native battle that catches on A
--   E.music                   { battle = { {kind, trainer, own} }, victory = { kind },
--                             victoryCalls = { {kind, trainer} }, map = { {map, song, ...} } }

local FakeContent = {}

local OPPOSITE = { up = "down", down = "up", left = "right", right = "left" }

function FakeContent.attach(E)
  local ow = E.ow
  local Commands = E.Commands
  E.pushes = {}
  E.talkFns, E.talkRows = {}, {}
  E.music = { battle = {}, victory = {}, victoryCalls = {}, map = {} }

  -- ------------------------------------------------------------ the music API

  -- The music API as the battle seam calls it (src/core/Music.lua at
  -- v0.3.85, the old positional arguments plus optional extras, UP3/B5):
  -- playMap(data, mapId, onBike, surfing, fade, song) (:439-451),
  -- playBattle(data, kind, trainerId, song) (:461-467), playVictory(data,
  -- kind, trainerId) (:474-483) and current() (:524-526), recorded;
  -- restoreMap(data, reason) stays fake_center's counter (:545)
  local Music = E.modules["src.core.Music"]
  function Music.playMap(_, mapId, onBike, surfing, fade, song)
    E.music.map[#E.music.map + 1] = { map = mapId, onBike = onBike, surfing = surfing, fade = fade, song = song }
  end
  function Music.playBattle(_, kind, trainerId, song)
    E.music.battle[#E.music.battle + 1] = { kind = kind, trainer = trainerId, song = song }
  end
  function Music.playVictory(_, kind, trainerId)
    E.music.victory[#E.music.victory + 1] = kind
    E.music.victoryCalls[#E.music.victoryCalls + 1] = { kind = kind, trainer = trainerId }
    return true
  end
  function Music.current() return E.music.currentSong end

  -- data/scripts/victories.lua as the music rule reads it: the tests' E.victories
  E.modules["data.scripts.victories"] = setmetatable({}, {
    __index = function(_, key) return E.victories and E.victories[key] or nil end,
  })

  -- ------------------------------------------------------------ BattleState stand-ins

  local Battle = {}
  Battle.__index = Battle
  -- BattleState:battleKind (BattleState.lua:1909-1914)
  function Battle:battleKind()
    if self.ghost then return "ghost" end
    if self.safari then return "safari" end
    if self.demo then return "oldman" end
    return self.kind
  end
  function Battle:makeGhost() self.ghost = true end              -- :1020-1023
  function Battle:makeUnveiledGhost() self.scopeReveal = true end -- :1033-1036
  function Battle:makeOldManDemo() self.demo = true end
  -- playBattleTheme (:1890-1893): the engine's own choice, recorded
  function Battle:playBattleTheme()
    E.music.battle[#E.music.battle + 1] = { kind = self.kind == "trainer" and "trainer" or "wild",
                                            trainer = self.oppClass, own = true }
  end

  function E.newWild(species, level)
    local mon = E.Pokemon.new(E.data, species, level)
    return setmetatable({ kind = "wild", enemy = { mon = mon } }, Battle)
  end
  function E.newTrainer(cls, party)
    return setmetatable({ kind = "trainer", oppClass = cls, partyIndex = party or 1, trainer = { id = cls } }, Battle)
  end
  -- BattleState.newTrainer (BattleState.lua:925-1003), the one engageTrainer's
  -- start calls (fake_script.lua); E.trainerBuilds counts real builds
  E.modules["src.battle.BattleState"].newTrainer = function(_, cls, party)
    E.trainerBuilds = (E.trainerBuilds or 0) + 1
    return E.newTrainer(cls, party)
  end

  -- OverworldState:pushBattle (OC:1261-1295): the battle's theme with the
  -- wipe, then the battle screen; BattleState:finish hands the result to
  -- onFinish after the pop (BattleState.lua:5912-5990; since v0.3.85 its
  -- Music.restoreMap, :5963-5970, is skipped after a won final battle --
  -- the fake's native screen plays no music at all, so the rule lives in
  -- kb_engine's own restore for the battles Cobblemon fights)
  function ow.pushBattle(self, battle, trainerNpc)
    local mon = battle.enemy and battle.enemy.mon
    E.pushes[#E.pushes + 1] = { kind = battle.kind, species = mon and mon.species, level = mon and mon.level,
                                cls = battle.oppClass, npc = trainerNpc and trainerNpc.id, ghost = battle.ghost,
                                scope = battle.scopeReveal, endText = battle.endBattleText }
    battle:playBattleTheme()
    E.nativeBattle({ kind = battle.kind, species = mon and mon.species, level = mon and mon.level,
                     cls = battle.oppClass, party = battle.partyIndex, ghost = battle.ghost,
                     safari = battle.safari, demo = battle.demo, direct = true }, function(result)
      battle.result = result
      if battle.onFinish then battle.onFinish(result) end
    end)
  end

  -- ------------------------------------------------------------ the talk path

  -- talkTo with the branches M8 needs (OC:3552-3672): a hand-ported talk
  -- script (a function or command rows) wins, then a static Pokemon object
  -- (OC:3600-3622: its text, then BattleState.newWild pushed directly; a
  -- win or a catch beats it for good, lose and run leave it), then
  -- fake_ui's own talk
  local baseTalk = E.talkTo
  function E.talkTo(npc)
    local fn, rows = E.talkFns[npc.id], E.talkRows[npc.id]
    local d = npc.def or {}
    if fn or rows or d.pokemon then
      npc.frozen = true
      npc.facing = OPPOSITE[ow.player.facing]
    end
    local unfreeze = function() npc.frozen = false end
    if fn then return fn(npc, unfreeze) end
    if rows then return E.runScript(rows, { npc = npc, onDone = unfreeze }) end
    if d.pokemon then
      E.uiPush(E.textBox(E.talk[npc.id] or "GYAOO!", { onDone = function()
        local battle = E.newWild(d.pokemon, d.level)
        battle.onFinish = function(result)
          if result ~= "lose" and result ~= "run" then
            E.save.defeatedTrainers[npc.id] = true
            for i, n in ipairs(ow.npcs) do
              if n == npc then table.remove(ow.npcs, i) break end
            end
            for i, e in ipairs(ow.entities or {}) do
              if e == npc then table.remove(ow.entities, i) break end
            end
          end
          ow:afterBattle(result, battle)
          unfreeze()
        end
        ow:pushBattle(battle)
      end }))
      return
    end
    return baseTalk(npc)
  end

  -- a hideout boss like Giovanni's (story3.lua:458-513): a text, then the
  -- trainer battle pushed directly with its armed loss line; a win marks
  -- him beaten, sets the flag and shows a farewell
  function E.bossTalk(npc, done)
    if E.trainerDefeated(npc) then
      E.uiPush(E.textBox("MEET AGAIN.", { onDone = done }))
      return
    end
    E.uiPush(E.textBox("YOU GOT HERE!", { onDone = function()
      local battle = E.newTrainer(npc.def.trainerClass, npc.def.trainerParty or 1)
      battle.endBattleText = "IT CANNOT BE!"
      battle.onFinish = function(result)
        if result ~= "win" then
          ow:afterBattle(result, battle)
          done()
          return
        end
        E.save.defeatedTrainers[npc.id] = true
        E.save.flags.EVENT_BEAT_TEST_BOSS = true
        E.uiPush(E.textBox("MEET AGAIN.", { onDone = function()
          ow:afterBattle(result, battle)
          done()
        end }))
      end
      ow:pushBattle(battle, npc)
    end }))
  end

  -- a step trigger like the ghost Marowak's (story3.lua:134-194): a text,
  -- then a wild battle pushed directly, uncatchable, disguised (or, with
  -- the SILPH_SCOPE, unveiled); a win sets the flag, a flight steps right
  function E.ghostTrigger(mapId, x, y, species, level, flag)
    E.mapScripts[mapId] = { onStep = function(_, w, px, py)
      if E.save.flags[flag] or px ~= x or py ~= y then return false end
      E.uiPush(E.textBox("BE GONE...", { onDone = function()
        local battle = E.newWild(species, level)
        battle.noCatch = true
        if E.save.inventory.SILPH_SCOPE then battle:makeUnveiledGhost() else battle:makeGhost() end
        battle.onFinish = function(result)
          if result == "win" then
            E.save.flags[flag] = true
            E.uiPush(E.textBox("IT DEPARTED."))
          elseif result ~= "lose" then
            E.ghostStepsRight = (E.ghostStepsRight or 0) + 1
            E.scriptMove(w.player, "right", 1)
          end
          w:afterBattle(result, battle)
        end
        w:pushBattle(battle)
      end }))
      return true
    end }
  end

  -- ------------------------------------------------------------ a party menu to pick from

  -- PartyMenu with pickOnly (src/ui/PartyMenu.lua:826-834): A picks the
  -- member under the cursor (the menu pops first), B cancels
  function E.partyPick(onSwitch, onCancel)
    local PartyMenu = E.modules["src.ui.PartyMenu"]
    local s = setmetatable({ screenId = "PartyMenu", index = 1, pickOnly = true }, { __index = PartyMenu })
    function s.update(self)
      local input = E.game.input
      if input:wasPressed("down") then
        self.index = math.min(#E.save.party, self.index + 1)
      elseif input:wasPressed("up") then
        self.index = math.max(1, self.index - 1)
      elseif input:wasPressed("a") then
        E.uiPop()
        onSwitch(E.save.party[self.index])
      elseif input:wasPressed("b") then
        E.uiPop()
        if onCancel then onCancel() end
      end
    end
    return E.uiPush(s)
  end

  -- ------------------------------------------------------------ in-game trades

  -- Commands.trade (Commands.lua:1093-1184): the offer, the pick, the
  -- species check, the flag, the swap (the received member joins at the
  -- end with the trade's nickname, traded, OT "TRAINER"), the TradeAnim
  -- screen, the thanks
  function Commands.trade(ctx, index, doneFlag)
    local trade = E.data.field.trades[index]
    local runner = ctx.runner
    if doneFlag and ctx.save.flags[doneFlag] then
      Commands.show_text(ctx, "ALREADY TRADED.")
      return
    end
    Commands.ask(ctx, "TRADE WITH ME?")
    if not ctx.lastCheck then
      Commands.show_text(ctx, "NO TRADE.")
      return
    end
    local picked
    E.partyPick(function(mon)
      picked = mon
      runner:resume()
    end, function() runner:resume() end)
    runner:yield()
    if not picked then
      Commands.show_text(ctx, "NO TRADE.")
      return
    end
    if picked.species ~= trade.give then
      Commands.show_text(ctx, "WRONG ONE.")
      return
    end
    local slot
    for i, m in ipairs(ctx.save.party) do
      if m == picked then slot = i end
    end
    if doneFlag then ctx.save.flags[doneFlag] = true end
    Commands.show_text(ctx, "CONNECT THE CABLE.")
    local sent = ctx.save.party[slot]
    local newMon = E.Pokemon.new(E.data, trade.get, sent.level)
    newMon.nickname = trade.nickname
    newMon.traded = true
    newMon.ot = "TRAINER"
    newMon.otId = 777
    table.remove(ctx.save.party, slot)
    table.insert(ctx.save.party, newMon)
    E.uiPush(E.screenState("TradeAnim", { onClose = function() runner:resume() end }))
    runner:yield()
    Commands.show_text(ctx, "TRADED!")
  end

  -- ------------------------------------------------------------ the Name Rater

  -- story4.lua:744-818: the offer, the pick, a foreign OT refused, the
  -- offer to rename, the naming screen (a name equal to the species' or an
  -- empty one clears the nickname)
  function E.nameRaterTalk(_, done)
    local function bye() E.uiPush(E.textBox("COME ANY TIME!", { onDone = done })) end
    E.uiPush(E.textBox("RATE YOUR NAMES?", { choice = function(yes)
      if not yes then return bye() end
      E.uiPush(E.textBox("WHICH ONE?", { onDone = function()
        E.partyPick(function(mon)
          local def = E.data.pokemon[mon.species] or {}
          local p = E.save.player
          local foreign = mon.traded or (mon.ot ~= nil and mon.ot ~= p.name)
            or (mon.otId ~= nil and p.id ~= nil and mon.otId ~= p.id)
          if foreign then
            E.uiPush(E.textBox("A FINE NAME!", { onDone = done }))
            return
          end
          E.uiPush(E.textBox("A NICER NAME?", { choice = function(rename)
            if not rename then return bye() end
            E.uiPush(E.textBox("WHAT NAME?", { onDone = function()
              E.uiPush(E.namingScreen({ title = "NICKNAME?", maxLen = 10, mon = mon, default = mon.nickname,
                onDone = function(name)
                  if name and #name > 0 and name ~= def.name then mon.nickname = name else mon.nickname = nil end
                  E.uiPush(E.textBox("RENAMED!", { onDone = done }))
                end }))
            end }))
          end }))
        end, bye)
      end }))
    end }))
  end

  -- ------------------------------------------------------------ the daycare

  -- story2.lua:1020-1210: with a member boarded, its steps fold into its
  -- exp, the levels grown set the fee, YES pays and takes it back (to the
  -- end of the party); otherwise the offer, the pick, the member out of
  -- the party into save.daycare
  local function levelForExp(exp)
    local level = 1
    while (level + 1) ^ 3 <= exp and level < 100 do level = level + 1 end
    return level
  end
  function E.daycareTalk(_, done)
    local dc = E.save.daycare
    if dc and dc.mon then
      local mon = dc.mon
      mon.exp = (mon.exp or 0) + (dc.steps or 0)
      dc.steps = 0
      if dc.depositLevel == nil then dc.depositLevel = mon.level end
      local start = dc.depositLevel
      local newLevel = math.max(start, levelForExp(mon.exp))
      local fee = 100 + (newLevel - start) * 100
      E.uiPush(E.textBox(newLevel > start and "IT HAS GROWN!" or "BACK ALREADY?", { onDone = function()
        if #E.save.party >= 6 then
          E.uiPush(E.textBox("NO ROOM.", { onDone = done }))
          return
        end
        E.uiPush(E.textBox("YOU OWE ME.", { choice = function(yes)
          if not yes then
            mon.level = start
            E.uiPush(E.textBox("COME AGAIN.", { onDone = done }))
            return
          end
          if (E.save.money or 0) < fee then
            mon.level = start
            E.uiPush(E.textBox("NOT ENOUGH MONEY.", { onDone = done }))
            return
          end
          E.save.money = E.save.money - fee
          mon.level = newLevel
          table.insert(E.save.party, mon)
          E.save.daycare = nil
          E.uiPush(E.textBox("HERE IT IS!", { onDone = done }))
        end }))
      end }))
      return
    end
    E.uiPush(E.textBox("RAISE ONE?", { choice = function(yes)
      if not yes then
        E.uiPush(E.textBox("COME AGAIN.", { onDone = done }))
        return
      end
      if #E.save.party < 2 then
        E.uiPush(E.textBox("ONLY ONE WITH YOU.", { onDone = done }))
        return
      end
      E.uiPush(E.textBox("WHICH ONE?", { onDone = function()
        E.partyPick(function(mon)
          for i, m in ipairs(E.save.party) do
            if m == mon then table.remove(E.save.party, i) break end
          end
          E.save.daycare = { mon = mon, steps = 0, depositLevel = mon.level }
          E.uiPush(E.textBox("I'LL LOOK AFTER IT.", { onDone = done }))
        end, done)
      end }))
    end }))
  end

  -- a boarded member gains a step's exp per step (OC:4855-4859)
  local afterStep = E.afterStep
  function E.afterStep()
    local dc = E.save.daycare
    if dc and dc.mon then dc.steps = (dc.steps or 0) + 1 end
    if afterStep then return afterStep() end
  end

  -- ------------------------------------------------------------ native catches

  -- a native battle that catches on A (storeCaughtMon,
  -- BattleState.lua:5649-5714): the member joins the party, or the current
  -- box when it is full; pokemon.caught (not for the old man's demo); the
  -- nickname prompt over the battle; then the battle leaves.
  -- opts: safari, demo, onFinish(result)
  function E.catchBattle(species, level, opts)
    opts = opts or {}
    local s = { isBattleState = true, screenId = "BattleState", kind = "wild",
                safari = opts.safari and (E.save.safari or { balls = 30 }) or nil, demo = opts.demo }
    function s.battleKind(self)
      if self.safari then return "safari" end
      if self.demo then return "oldman" end
      return "wild"
    end
    function s.update(self)
      local input = E.game.input
      if self.ending then
        if E.stack:top() == self then E.uiPop() end
        if opts.onFinish then opts.onFinish("caught") end
        return
      end
      if self.caught or not input:wasPressed("a") then return end
      self.caught = true
      local mon = E.Pokemon.new(E.data, species, level)
      mon.ot, mon.otId = E.save.player.name, E.save.player.id
      local dest = "party"
      if self.demo then
        self.ending = true
        return
      end
      if #E.save.party < 6 then
        E.save.party[#E.save.party + 1] = mon
      else
        local box = E.save.boxes[E.save.currentBox]
        box[#box + 1] = mon
        dest = "box"
      end
      E.save.pokedex.owned[species] = true
      E.runtime.emit("pokemon.caught", { battle = self, mon = mon, species = species, destination = dest })
      E.uiPush(E.textBox("NICKNAME IT?", { choice = function(yes)
        if not yes then
          self.ending = true
          return
        end
        E.uiPush(E.namingScreen({ title = "NICKNAME?", maxLen = 10, mon = mon, onDone = function(nick)
          if nick and #nick > 0 then mon.nickname = nick end
          self.ending = true
        end }))
      end }))
    end
    E.battles[#E.battles + 1] = { kind = "wild", species = species, level = level, safari = opts.safari,
                                  demo = opts.demo }
    E.uiPush(s)
    return s
  end

  -- ------------------------------------------------------------ the Hall of Fame

  -- Commands.record_hall_of_fame (Commands.lua:920-970), shortened: the
  -- record, the HallOfFame and Credits screens, the heal and the save while
  -- THE END is up
  function Commands.record_hall_of_fame(ctx)
    local runner = ctx.runner
    ctx.save.hallOfFame = ctx.save.hallOfFame or {}
    ctx.save.hallOfFame[#ctx.save.hallOfFame + 1] = { size = #ctx.save.party }
    E.uiPush(E.screenState("HallOfFame", { onClose = function()
      E.uiPush(E.screenState("Credits", { onClose = function()
        for _, mon in ipairs(ctx.save.party) do E.Pokemon.heal(mon) end
        E.game:writeSave()
        runner:resume()
      end }))
    end }))
    runner:yield()
  end

  -- Commands.show_object / hide_object (Commands.lua:603-649): the toggle,
  -- and the live NPC added or removed in place on the current map
  function Commands.show_object(ctx, mapId, name)
    ctx.save.objectToggles = ctx.save.objectToggles or {}
    ctx.save.objectToggles[mapId] = ctx.save.objectToggles[mapId] or {}
    ctx.save.objectToggles[mapId][name] = true
    E.shown = (E.shown or 0) + 1
    if E.hiddenNpcs and E.hiddenNpcs[name] and ow.map and ow.map.id == mapId then
      table.insert(ow.npcs, E.hiddenNpcs[name])
      E.hiddenNpcs[name] = nil
    end
  end
  function Commands.hide_object(ctx, mapId, name)
    ctx.save.objectToggles = ctx.save.objectToggles or {}
    ctx.save.objectToggles[mapId] = ctx.save.objectToggles[mapId] or {}
    ctx.save.objectToggles[mapId][name] = false
    if ow.map and ow.map.id == mapId then
      for i, n in ipairs(ow.npcs) do
        if n.def and n.def.name == name then
          E.hiddenNpcs = E.hiddenNpcs or {}
          E.hiddenNpcs[name] = table.remove(ow.npcs, i)
          break
        end
      end
    end
  end
  E.modules["src.script.Commands"] = Commands
end

return FakeContent
