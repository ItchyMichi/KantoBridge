-- The parts of Gen1Recomp v0.3.85's Gen 3 engine that the game3 adapter's
-- G3F parts read (lib/engine/game3/battle.lua, party.lua, items.lua,
-- routes.lua; GEN3_BRIDGE_PLAN.md §4.10), on top of fake_engine3.lua and
-- fake_export3.lua: the battle bridge and the battle module, trainers and
-- the prize, the party's giveMon and giveEgg, species and moves, the Pokedex,
-- the bag and item data, the Safari Zone, the whiteout; the in-game trade's
-- swap, the daycare, eggs and their hatching, the hidden boxes' open slots
-- (SEVERAL_GAMES_FOLLOWUPS.md item 7). Everything is made up: trainer
-- ids, classes, names, species (internal = national - 250, as
-- fake_export3's Pokemon.national), moves and items.
--
--   FakeBattle3.attach(E)         adds the modules and these helpers:
--   E.trainerTable[id] = { class, className, name, party = {{species, level, ivs?, moves?}}, defeat?, double? }
--   E.startTrainer(id, opts)      a script's trainerbattle: BattleBridge.start with a done that records the result
--   E.startWild(species, level, opts)  a field encounter: BattleBridge.startWild
--   E.fish(species, level)        Field.tryFishingEncounter (the rod's encounter)
--   E.battleResults               what each start's done got, in order
--   E.endNative(result)           ends a native (not held) battle as the engine would
--   E.giveMon(species, level, nickname, toPC)  a script's givemon on the live session
--   E.tradeMon(species, level, nickname, ot)   what Trade.createTradeMon makes (E.Trade.tradeMons swaps it in)
--   E.Daycare.deposit / take / step, E.Breeding.giveEggFromDaycare / hatchMon, E.Party.giveEgg: the engine's
--   E.TradeScene.open, E.EggHatch.open           the trade's scene and the hatch screen up
--
-- Engine files (refs/gen1recomp/src/core/game3): battle_bridge.lua:122-132,
-- :316-330, :397-501 (start, finish), :748-759 (startWild); battle/init.lua:
-- 369-379, :408-488 (finish), :4279-4281 (abort); scripting/trainers.lua:
-- 169-289; battle/prize.lua:167-200; party.lua:180-292; pokemon.lua:281-301,
-- :472, :1089-1098, :1204; dex.lua:184-204; bag.lua:351-536;
-- items_data.lua:333-385; safari.lua:118; field.lua:1714, :2054-2090;
-- summary_data.lua:110; scripting/vm.lua:50-68.

local FakeBattle3 = {}

function FakeBattle3.attach(E)
  local floor = math.floor

  -- ---- species, moves (fake_export3's Pokemon.national: national = species + 250)
  E.Pokemon = E.Pokemon or {}
  function E.Pokemon.speciesFromNational(n)
    n = tonumber(n)
    if not n or n < 251 or n > 386 then return nil end
    return n - 250
  end
  function E.Pokemon.movesAtLevel(species, level)
    local moves = { 33 }
    if (tonumber(level) or 1) >= 5 then moves[2] = 45 end
    local pp = {}
    for i = 1, #moves do pp[i] = 35 end
    return moves, pp, pp
  end
  function E.Pokemon.movePp(id) return tonumber(id) and 20 + tonumber(id) % 20 or 0 end
  function E.Pokemon.applyStats(mon)
    mon.maxHp = 10 + 3 * (tonumber(mon.level) or 1)
    if mon.hp == nil or mon.hp > mon.maxHp then mon.hp = mon.maxHp end
    E.statsApplied = (E.statsApplied or 0) + 1
  end
  function E.Pokemon.moveFromTmItem(id) return E.tmMoves and E.tmMoves[tonumber(id)] or nil end

  E.SummaryData = {}
  function E.SummaryData.expForLevel(_rate, level) return (tonumber(level) or 1) ^ 3 end

  E.Dex = {}
  function E.Dex.setSeen(dex, species) dex.seen = dex.seen or {}; dex.seen[species] = true end
  function E.Dex.setCaught(dex, species)
    dex.seen, dex.owned = dex.seen or {}, dex.owned or {}
    dex.seen[species], dex.owned[species] = true, true
  end

  -- ---- the party (party.lua:180-292): a record as giveMon makes one
  E.monSerial = 0
  E.Party = {}
  function E.Party.giveMon(session, species, level, nickname, opts)
    E.monSerial = E.monSerial + 1
    local mon = {
      species = tonumber(species), speciesId = tonumber(species), level = tonumber(level) or 5,
      nickname = type(nickname) == "string" and nickname or "", moves = { 33 }, pp = { 35 }, maxPp = { 35 },
      ivs = { hp = 1, atk = 2, def = 3, spa = 4, spd = 5, spe = 6 }, otName = session.name, growthRate = 0,
      serial = E.monSerial, personality = 7000 + E.monSerial, exp = (tonumber(level) or 5) ^ 3,
    }
    E.Pokemon.applyStats(mon)
    session.party = session.party or {}
    local boxId, slot
    if #session.party < 6 then
      session.party[#session.party + 1] = mon
    elseif type(opts) == "table" and opts.toPC then
      session.storage = session.storage or { boxes = { { mons = {} } } }
      local mons = session.storage.boxes[1].mons
      slot = #mons + 1
      mons[slot] = mon
      boxId = 1
    else
      return false, 2, nil
    end
    session.dex = session.dex or { seen = {}, owned = {}, caught = {} }
    session.dex.seen[mon.species], session.dex.owned[mon.species] = true, true
    return true, boxId and 1 or 0, mon, boxId, slot
  end
  function E.giveMon(species, level, nickname, toPC)
    return E.Party.giveMon(E.Runtime.getSession(), species, level, nickname, { toPC = toPC })
  end
  -- Party.giveEgg (party.lua:294-304): a giveMon (the gift seam sees it) made an egg afterwards
  function E.Party.giveEgg(session, species, opts)
    local ok, code, egg = E.Party.giveMon(session, species, 5, "EGG", opts)
    if ok and egg then
      egg.isEgg, egg.name, egg.nickname = true, "EGG", "EGG"
    end
    return ok, code, egg
  end
  function E.Pokemon.isEgg(mon)
    if not mon then return false end
    return mon.isEgg == true or mon.egg == true or mon.species == 412
  end

  -- ---- trades, the daycare, eggs (natives_trade.lua:303-343, src/core/game3/daycare.lua:76-467,
  -- breeding.lua:540-620, src/core/game3/storage.lua:74-183, src/ui/game3/egg_hatch.lua:132)
  E.Trade = {}
  function E.Trade.tradeMons(session, playerSlot, offered)
    if not (session and offered) then return nil end
    session.party = session.party or {}
    local slot = (tonumber(playerSlot) or 0) + 1
    local sent = session.party[slot]
    if not sent then return nil end
    session.party[slot] = offered
    return sent
  end
  -- what Trade.createTradeMon makes: its own record (a scratch session's giveMon), the trade's nickname and trainer
  function E.tradeMon(species, level, nickname, ot)
    E.monSerial = E.monSerial + 1
    local mon = {
      species = species, speciesId = species, level = level, nickname = nickname, name = nickname, otName = ot, ot = ot,
      moves = { 33 }, pp = { 35 }, maxPp = { 35 }, ivs = { hp = 9, atk = 9, def = 9, spa = 9, spd = 9, spe = 9 },
      growthRate = 0, serial = E.monSerial, personality = 7000 + E.monSerial, exp = level ^ 3,
    }
    E.Pokemon.applyStats(mon)
    return mon
  end
  E.TradeScene = { open = false }
  function E.TradeScene.isOpen() return E.TradeScene.open == true end
  E.Daycare = { DAYCARE_MON_COUNT = 2 }
  function E.Daycare.stateOf(session)
    if not session then return nil end
    if type(session.daycare) ~= "table" then session.daycare = { steps = { 0, 0 }, stepCounter = 0 } end
    E.daycareReads = (E.daycareReads or 0) + 1
    return session.daycare
  end
  function E.Daycare.mon(dc, index) return dc and dc[index] or nil end
  function E.Daycare.deposit(session, partySlot)
    local dc = E.Daycare.stateOf(session)
    local mon = session.party[partySlot]
    local free = dc[1] == nil and 1 or (dc[2] == nil and 2 or nil)
    if not (mon and free) then return nil end
    dc[free], dc.steps[free] = mon, 0
    table.remove(session.party, partySlot)
    return free
  end
  function E.Daycare.step(session)
    local dc = E.Daycare.stateOf(session)
    for i = 1, 2 do
      if dc[i] then dc.steps[i] = dc.steps[i] + 1 end
    end
  end
  function E.Daycare.withdraw(session, mon, steps)
    mon.exp = (mon.exp or 0) + (tonumber(steps) or 0)
    session.party[#session.party + 1] = mon
    return mon.species
  end
  function E.Daycare.take(session, index)
    local dc = E.Daycare.stateOf(session)
    local mon = dc[index]
    if not mon then return 0 end
    E.Daycare.withdraw(session, mon, dc.steps[index])
    dc[index], dc.steps[index] = nil, 0
    if dc[2] and not dc[1] then
      dc[1], dc[2], dc.steps[1], dc.steps[2] = dc[2], nil, dc.steps[2], 0
    end
    return mon.species
  end
  E.Breeding = {}
  function E.Breeding.giveEggFromDaycare(session)
    E.monSerial = E.monSerial + 1
    local egg = {
      species = 5, speciesId = 5, level = 5, nickname = "", isEgg = true, eggCycles = 20, moves = { 33 }, pp = { 35 },
      maxPp = { 35 }, ivs = { hp = 3, atk = 3, def = 3, spa = 3, spd = 3, spe = 3 }, otName = session.name,
      growthRate = 0, serial = E.monSerial, personality = 7000 + E.monSerial,
    }
    session.party[#session.party + 1] = egg
    return egg
  end
  function E.Breeding.hatchMon(session, mon)
    mon.isEgg, mon.egg, mon.level, mon.nickname = false, false, 5, ""
    mon.otName = (session or E.Runtime.getSession()).name
    E.Pokemon.applyStats(mon)
    return mon
  end
  E.Storage = { IN_BOX_COUNT = 30 }
  function E.Storage.ensure(session)
    if not session then return nil end
    session.storage = session.storage or { boxes = { { mons = {} } } }
    return session.storage
  end
  function E.Storage.findOpenSlot(storage)
    for b, box in ipairs(storage and storage.boxes or {}) do
      for slot = 1, E.Storage.IN_BOX_COUNT do
        if box.mons[slot] == nil then return b, slot end
      end
    end
    return nil, nil
  end
  E.EggHatch = { open = false }
  function E.EggHatch.isOpen() return E.EggHatch.open == true end

  -- ---- trainers (scripting/trainers.lua:169-289) and the prize (battle/prize.lua:167-200)
  E.trainerTable = {}
  E.Trainers = {}
  function E.Trainers.foeFromId(id)
    local t = E.trainerTable[tonumber(id)]
    if not t then return nil end
    local party = {}
    for i, m in ipairs(t.party) do
      party[i] = { species = m.species, level = m.level, ivs = m.ivs or { hp = 7, atk = 7, def = 7, spa = 7, spd = 7, spe = 7 },
                   iv = 7, moves = m.moves, trainerId = tonumber(id) }
    end
    return { species = party[1].species, level = party[1].level, party = party, trainerId = tonumber(id),
             trainerClass = t.class, trainerName = t.name, doubleBattle = t.double }
  end
  -- Trainers.get (scripting/trainers.lua:90-132): the record by id (class, name, party)
  function E.Trainers.get(id)
    local t = E.trainerTable[tonumber(id)]
    return t and { id = tonumber(id), class = t.class, className = t.className, name = t.name, party = t.party } or nil
  end
  function E.Trainers.info(id)
    local t = E.trainerTable[tonumber(id)]
    return t and { class = t.class, className = t.className, name = t.name, party = t.party } or nil
  end
  function E.Trainers.dialogs(id)
    local t = E.trainerTable[tonumber(id)]
    return t and { defeat = t.defeat } or {}
  end
  E.Prize = { MAX_MONEY = 999999 }
  function E.Prize.rewardRse(id)
    local t = E.trainerTable[tonumber(id)]
    local last = t and t.party[#t.party]
    return 40 * (last and last.level or 1)
  end
  function E.Prize.apply(session, amount)
    local before = tonumber(session.money) or 0
    session.money = math.min(E.Prize.MAX_MONEY, before + amount)
    return session.money - before, session.money
  end
  function E.Prize.moneyMessage(name, amount) return ("%s got $%d for winning!"):format(tostring(name), amount) end

  -- ---- the battle module (battle/init.lua): active, its state, abort -> finish -> onDone
  E.Battle._active, E.Battle._st, E.Battle._onDone = false, nil, nil
  function E.Battle.getState() return E.Battle._st end
  function E.Battle.abort(result)
    if not E.Battle._active then return end
    E.Battle._active = false
    E.aborts = (E.aborts or 0) + 1
    if E.Field.unlock then E.Field.unlock() end
    local cb = E.Battle._onDone
    E.Battle._onDone = nil
    if cb then cb(result or "run") end
  end
  function E.endNative(result) E.Battle.abort(result) end

  -- ---- the whiteout (battle_bridge.lua:122-132; field.lua:2054-2090)
  E.respawns = 0
  function E.Field.respawnAtHeal()
    E.respawns = E.respawns + 1
    if E.Space.vm.running then E.Space.vm:halt(true) end
  end
  E.fishingFoe = nil
  function E.Field.tryFishingEncounter(rod)
    E.fishRods = (E.fishRods or 0) + 1
    if E.fishingFoe then return E.BattleBridge.startWild(nil, E.game, E.fishingFoe, {}) end
    return false
  end
  function E.Space.vm:halt(aborted)
    self.running = false
    E.halts = (E.halts or 0) + 1
  end
  function E.Space.returnToField() E.returnedToField = (E.returnedToField or 0) + 1 end

  -- ---- the battle bridge (battle_bridge.lua:397-759): the start's checks, the battle, finish
  E.outcomeCodes = { win = 1, lose = 2, run = 4, caught = 7 }
  E.starts = {}
  E.BattleBridge = {}
  function E.BattleBridge.applyWhiteoutMoneyLoss(session)
    local money = tonumber(session.money) or 0
    local kept = floor(money / 2)
    session.money = kept
    return money - kept, kept
  end
  function E.BattleBridge.twoOpponentFoe(foe, idB)
    local other = E.Trainers.foeFromId(idB)
    local merged = {}
    for k, v in pairs(foe) do merged[k] = v end
    merged.party = {}
    for _, m in ipairs(foe.party or {}) do merged.party[#merged.party + 1] = m end
    for _, m in ipairs(other and other.party or {}) do merged.party[#merged.party + 1] = m end
    return merged
  end
  function E.BattleBridge.start(mod, game, foe, opts)
    opts = opts or {}
    local session = E.Runtime.getSession()
    E.starts[#E.starts + 1] = { foe = foe, opts = opts, headless = opts.headless == true, autoFight = opts.autoFight }
    if not session then return nil, "no session" end
    if #(session.party or {}) == 0 then return nil, "empty party" end
    if E.failNextStart then
      E.failNextStart = nil
      if opts.done then opts.done("win") end
      return nil, "start failed"
    end
    local done = opts.done
    local st = {
      trainerId = opts.trainerId or (foe and foe.trainerId), defeatText = opts.defeatText, double = opts.double,
      wild = opts.wild == true, safari = opts.safari or (E.Safari.active and opts.wild) or nil, moneyMultiplier = 1,
      kinds = { twoOpponents = opts.twoOpponents },
    }
    E.Battle._active, E.Battle._st = true, st
    E.Field.lock("battle")
    E.Battle._onDone = function(result)
      session.battleOutcome = E.outcomeCodes[result] or 1
      if result == "lose" then
        E.BattleBridge.applyWhiteoutMoneyLoss(session)
        E.Field.respawnAtHeal()
      end
      E.Space.returnToField()
      if done then done(result) end
    end
    return true
  end
  function E.BattleBridge.startWild(mod, game, encounter, opts)
    opts = opts or {}
    opts.wild = true
    return E.BattleBridge.start(mod, game, encounter, opts)
  end

  E.battleResults = {}
  local function recorder(label)
    return function(result)
      E.battleResults[#E.battleResults + 1] = { label = label, result = result }
    end
  end
  function E.startTrainer(id, opts)
    opts = opts or {}
    E.Space.vm.running = true
    local foe = E.Trainers.foeFromId(id)
    local o = {
      wild = false, trainerId = id, defeatText = opts.defeatText, double = opts.double, twoOpponents = opts.twoOpponents,
      trainerIdB = opts.trainerIdB, done = recorder("trainer"),
    }
    -- (FP5: any other kind flag a test gives, as the engine's callers set them)
    for k, v in pairs(opts) do if o[k] == nil then o[k] = v end end
    return E.BattleBridge.start(nil, E.game, foe, o)
  end
  function E.startWild(species, level, opts)
    opts = opts or {}
    local o = { done = opts.withDone and recorder("wild") or nil, firstBattleKind = opts.firstBattleKind,
                safari = opts.safari, link = opts.link }
    for k, v in pairs(opts) do if o[k] == nil and k ~= "withDone" then o[k] = v end end
    if opts.withDone then E.Space.vm.running = true end
    return E.BattleBridge.startWild(nil, E.game, { species = species, level = level }, o)
  end
  function E.fish(species, level)
    E.fishingFoe = { species = species, level = level }
    local out = { E.Field.tryFishingEncounter(1) }
    E.fishingFoe = nil
    return (table.unpack or unpack)(out)
  end

  -- ---- the Safari Zone (safari.lua:118)
  E.Safari = { active = false }
  function E.Safari.isActive() return E.Safari.active == true end

  -- ---- the bag (bag.lua:351-536): a made-up pocket layout, 99 a slot, ITEMS 30 slots
  E.itemPockets = {}   -- id -> pocket name (default ITEMS)
  E.hms, E.tms = {}, {}
  E.ItemsData = { CAPACITY = { ITEMS = 30, KEY_ITEMS = 30, POKE_BALLS = 16, TM_CASE = 64, BERRY_POUCH = 46 } }
  function E.ItemsData.pocketOf(id) return E.itemPockets[tonumber(id)] or "ITEMS" end
  function E.ItemsData.displayName(id) return ("TEST ITEM %d"):format(tonumber(id) or 0) end
  function E.ItemsData.isHm(id) return E.hms[tonumber(id)] == true end
  function E.ItemsData.isTm(id) return E.tms[tonumber(id)] == true end
  local function pocketList(bag, id)
    bag.pockets = bag.pockets or {}
    local pocket = E.ItemsData.pocketOf(id)
    bag.pockets[pocket] = bag.pockets[pocket] or {}
    return bag.pockets[pocket], pocket
  end
  function E.Bag.get(bag, id)
    local list = pocketList(bag, id)
    local n = 0
    for _, row in ipairs(list) do
      if row.id == tonumber(id) then n = n + row.qty end
    end
    return n
  end
  function E.Bag.add(bag, id, qty)
    id, qty = tonumber(id), floor(tonumber(qty) or 1)
    local list, pocket = pocketList(bag, id)
    local have = E.Bag.get(bag, id)
    local slotsNow = 0
    for _, row in ipairs(list) do if row.id == id then slotsNow = slotsNow + 1 end end
    local slotsNeeded = math.ceil((have + qty) / 99)
    if #list - slotsNow + slotsNeeded > (E.ItemsData.CAPACITY[pocket] or 30) then return false, 0 end
    for i = #list, 1, -1 do if list[i].id == id then table.remove(list, i) end end
    local total = have + qty
    while total > 0 do
      local n = math.min(99, total)
      list[#list + 1] = { id = id, qty = n }
      total = total - n
    end
    return true, qty
  end
  function E.Bag.remove(bag, id, qty)
    id, qty = tonumber(id), floor(tonumber(qty) or 1)
    local have = E.Bag.get(bag, id)
    if have < qty then return false end
    local list = pocketList(bag, id)
    for i = #list, 1, -1 do if list[i].id == id then table.remove(list, i) end end
    local left = have - qty
    while left > 0 do
      local n = math.min(99, left)
      list[#list + 1] = { id = id, qty = n }
      left = left - n
    end
    return true
  end

  -- the flag store's vars (scripting/flags.lua): getVar(store, ctx, id)
  function E.Flags.getVar(store, _ctx, id)
    return type(store) == "table" and type(store.vars) == "table" and store.vars[tonumber(id)] or nil
  end

  -- the constants: items by name (C:id, constants.lua:39-41) and the trainer classes
  local mt = getmetatable(E.Constants.of("emerald"))
  local Methods = mt and mt.__index
  if type(Methods) == "table" and Methods.id == nil then
    function Methods:id(kind, name)
      local t = E.constTables[kind]
      for _, sub in pairs(type(t) == "table" and t or {}) do
        if type(sub) == "table" then
          for id, n in pairs(sub) do if n == name then return id end end
        end
      end
      return nil
    end
  end
  E.constTables.items = {
    ITEM_ = { [1] = "ITEM_TEST_BALL", [2] = "ITEM_TEST_POTION", [3] = "ITEM_TEST_KEY", [4] = "ITEM_none",
              [5] = "ITEM_POKE_BALL", [13] = "ITEM_POTION", [17] = "ITEM_PARALYZE_HEAL", [94] = "ITEM_FIRE_STONE",
              [68] = "ITEM_RARE_CANDY", [289] = "ITEM_TM01", [294] = "ITEM_TM06", [339] = "ITEM_HM01",
              [259] = "ITEM_MACH_BIKE", [110] = "ITEM_NUGGET", [93] = "ITEM_SUN_STONE", [34] = "ITEM_ETHER", [200] = "ITEM_LEFTOVERS", [121] = "ITEM_ORANGE_MAIL" },
  }
  E.itemPockets[259], E.itemPockets[339] = "KEY_ITEMS", "TM_CASE"
  E.itemPockets[289], E.itemPockets[294] = "TM_CASE", "TM_CASE"
  E.itemPockets[5] = "POKE_BALLS"
  E.hms[339] = true
  E.tms[289], E.tms[294] = true, true
  E.constTables.trainer_classes = {
    TRAINER_CLASS_ = { [1] = "TRAINER_CLASS_YOUNGSTER", [2] = "TRAINER_CLASS_RIVAL", [3] = "TRAINER_CLASS_LEADER" },
    FACILITY_CLASS_ = { [1] = "FACILITY_CLASS_WRONG" },
  }

  local mods = E.modules
  -- FP4: the item seams the adapter wraps (src/core/game3/evolution.lua, item_use.lua, battle/learn_move.lua,
  -- src/ui/game3/evolution_scene.lua), as small as the tests need: FIRE_STONE (94) evolves internal species 4 into 5;
  -- a scene or an apply is recorded (E.evolutions); levelUpEvent is recorded (E.levelUps); the TM check reads
  -- Pokemon.canLearnTmItem (E.tmLearners[species][tm]); teachMove fills a free slot; LearnMove.begin replaces
  -- E.forgetSlot (default 1) and says yes
  E.evolutions, E.levelUps, E.tmLearners, E.forgetSlot = {}, {}, {}, 1
  E.Evolution = {}
  function E.Evolution.itemTarget(mon, item) return tonumber(item) == 94 and tonumber(mon.species) == 4 and 5 or nil end
  function E.Evolution.levelTarget() return nil end
  function E.Evolution.targetSpecies() return nil end
  function E.Evolution.apply(mon, target) E.evolutions[#E.evolutions + 1] = { via = "apply", target = target } mon.species = target end
  E.EvolutionScene = {}
  function E.EvolutionScene.start(mon, target, opts)
    E.evolutions[#E.evolutions + 1] = { via = "scene", target = target }
    mon.species = target
    if opts and opts.onDone then opts.onDone({ evolved = true }) end
  end
  E.ItemUse = {}
  function E.ItemUse.levelUpEvent(mon, level) E.levelUps[#E.levelUps + 1] = level end
  function E.Pokemon.movesLearnedAt(_species, _level) return { 33 } end
  function E.ItemUse.ppItemNeedsMove(id) return tonumber(id) == 34 end -- the fake's ETHER
  function E.Pokemon.canLearnTmItem(species, tm)
    local row = E.tmLearners[tonumber(species)]
    return row ~= nil and row[tonumber(tm)] == true
  end
  function E.Pokemon.teachMove(mon, moveId)
    if #mon.moves >= 4 then return false end
    mon.moves[#mon.moves + 1] = moveId
    return true
  end
  function E.ItemUse.checkTmPreflight(mon, tmId)
    local moveId = E.Pokemon.moveFromTmItem(tmId)
    if not moveId then return "invalid", "NO EFFECT" end
    if not E.Pokemon.canLearnTmItem(mon.species, tmId) then return "incompatible", "CAN'T LEARN", moveId end
    for _, m in ipairs(mon.moves) do if m == moveId then return "knows", "KNOWS IT", moveId end end
    return "ok", nil, moveId
  end
  E.LearnMove = {}
  function E.LearnMove.begin(opts)
    opts.mon.moves[E.forgetSlot] = opts.moveId
    if opts.onDone then opts.onDone(true) end
  end
  mods["src.core.game3.evolution"] = E.Evolution
  mods["src.ui.game3.evolution_scene"] = E.EvolutionScene
  mods["src.core.game3.item_use"] = E.ItemUse
  mods["src.core.game3.battle.learn_move"] = E.LearnMove
  mods["src.core.game3.battle"] = E.Battle
  mods["src.core.game3.battle_bridge"] = E.BattleBridge
  mods["src.core.game3.battle.prize"] = E.Prize
  mods["src.core.game3.scripting.trainers"] = E.Trainers
  mods["src.core.game3.pokemon"] = E.Pokemon
  mods["src.core.game3.party"] = E.Party
  mods["src.core.game3.summary_data"] = E.SummaryData
  mods["src.core.game3.dex"] = E.Dex
  mods["src.core.game3.items_data"] = E.ItemsData
  mods["src.core.game3.safari"] = E.Safari
  mods["src.core.game3.scripting.natives_trade"] = E.Trade
  mods["src.core.game3.trade_scene"] = E.TradeScene
  mods["src.core.game3.daycare"] = E.Daycare
  mods["src.core.game3.breeding"] = E.Breeding
  mods["src.core.game3.storage"] = E.Storage
  mods["src.ui.game3.egg_hatch"] = E.EggHatch
end

return FakeBattle3
