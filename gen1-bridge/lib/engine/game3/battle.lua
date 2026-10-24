-- game3 battles (GEN3_BRIDGE_PLAN.md §4.10, phase G3F): every Gen 3 battle
-- starts through BattleBridge.start (battle_bridge.lua:397-746; trainerbattle
-- and dowildbattle, adapters.lua:1552-1594; the field's wild rolls,
-- player.lua:1291-1312 through startWild; fishing, Rock Smash and Sweet
-- Scent, field.lua:1528-1635; trainer sight, trainer_sight.lua:697; the story
-- specials). The adapter wraps it once per process and decides before the
-- battle starts (a headless start can't become a visible one later,
-- battle/init.lua:591-594):
--
-- * native (the original, untouched): no client, a record already held, link,
--   spectate, Union Room, frontier and facility kinds, the Safari Zone, the
--   tutorials (Wally's, the Pokedude's, the old man's), or a battle the core
--   declines;
-- * held: the core's plan goes out as battle.start; the battle starts
--   headless without auto fight (the field locks, the battle song plays, the
--   foe is marked seen, battle/init.lua:591-619, :828-851) and waits in its
--   command phase; the observer reports it as a held battle (busy), never as a
--   screen; party syncs wait until it ends.
--
-- battleFinish(id, result) answers it: a trainer win pays the engine's prize
-- (Prize.rewardRse and Prize.apply, battle/init.lua:1776-1787) and queues the
-- trainer's defeat line and the money text; then Battle.abort(word) ends it,
-- so the engine records the outcome code (scripting/natives.lua:198-212),
-- writes the party back (a loss: half the money and the whiteout to the heal
-- point, battle_bridge.lua:257-279), emits battle.ended and returns to the
-- field; the wrapped done shows the queued texts in the field's message box,
-- then calls the caller's done (the script's flag, VAR_RESULT, its
-- continuation). As on Gen 1 (gen1.lua:2195-2202), no experience, evolution
-- or catch is written into the hidden game: the Pokemon are Cobblemon's and
-- come back through party.sync and dex.sync. An aborted battle never reaches
-- the caller's done: a script stops there (Vm:halt), so its trainer
-- challenges again; Birch's first battle, which has no other way out, goes on
-- as a win.
--
-- encounterRoll: the field's land rolls (grass and caves, "land") are the
-- habitat pools' (GEN3_BRIDGE_PLAN §6); water rolls go on and reach the
-- wrapper as surf. Fishing is told apart by a flag set around the engine's
-- Field.tryFishingEncounter.
--
-- Trainer teams (trainerTeam): the foe the engine is starting (two opponents
-- merged, BattleBridge.twoOpponentFoe), each member's national dex number,
-- level, moves (Showdown numbers, as Emerald's ids; a member without moves
-- takes the learnset's, Pokemon.movesAtLevel, as the battle does,
-- battle/init.lua:264-271) and IVs. The name is the class name and the
-- trainer's (ROM-derived, never logged).
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Battle3 = {}

-- opts that mark a battle the hidden game fights itself (battle_bridge.lua:332-338, battle/kinds.lua:5-15)
-- (FP5: Trainer Hill's battles are held for Cobblemon like any trainer's: the Hill has no rules or rentals)
Battle3.NATIVE_OPTS = { "link", "spectate", "unionRoom", "pokedude", "oldManTutorial", "tutorialKind", "frontier",
                        "frontierFoeHalf", "battleTower", "eReader", "secretBase", "trainerTower",
                        "pyramid", "pike", "dome", "palace", "arena", "factory", "recordedLink", "towerLinkMulti",
                        "safari", "partner" }
-- FP5 (FOREIGN_POKEMON_PLAN §4.12): what battle.native says for each native battle (anything else: facility)
Battle3.TUTORIAL_OPTS = { "pokedude", "oldManTutorial", "tutorialKind" }
-- the core's outcome words -> the engine's (scripting/natives.lua:198-212)
Battle3.WORDS = { win = "win", lose = "lose", run = "run", caught = "caught", abort = "run" }
Battle3.RIVAL_CLASS = "TRAINER_CLASS_RIVAL"
local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }
-- VAR_LAST_TALKED (pokeemerald include/constants/vars.h)
Battle3.VAR_LAST_TALKED = 0x800F
local unpack = table.unpack or unpack -- luacheck: ignore (LuaJIT's global)
local function pack(...) return { n = select("#", ...), ... } end

function Battle3.install(b)
  local def = b.def
  local floor = math.floor

  local function Bt() return b.g3("battle") end
  local function active() return b.ask(Bt(), "isActive", false) == true end
  local function held()
    local st = b.state()
    local rec = st and st.battle
    return type(rec) == "table" and rec or nil, st
  end

  -- The battle.native kind of a battle the hidden game fights itself, or nil.
  local function native(opts)
    local Safari = b.g3("safari")
    if opts.safari or b.ask(Safari, "isActive", false, b.session()) == true then return "safari" end
    for _, k in ipairs(Battle3.TUTORIAL_OPTS) do
      if opts[k] then return "old_man" end
    end
    for _, k in ipairs(Battle3.NATIVE_OPTS) do
      if opts[k] then return "facility" end
    end
    return nil
  end

  -- The cell in front of the player.
  local function front()
    local P = b.g3("player")
    if type(P) ~= "table" then return nil end
    local d = DELTA[P.facing] or DELTA.down
    local x, y = tonumber(P.cellX), tonumber(P.cellY)
    if not (x and y) then return nil end
    return x + d[1], y + d[2]
  end

  -- The object the player last talked to, as the observer names it (O<lid>), and its cell.
  local function lastTalked()
    local store = b.flagStore()
    local Objects = b.g3("objects")
    local lid
    if type(store) == "table" then
      local okVar, v = b.call(b.g3("scripting.flags"), "getVar", store, nil, Battle3.VAR_LAST_TALKED)
      lid = okVar and tonumber(v) or nil
      local vars = store.vars
      if not lid and type(vars) == "table" then
        lid = tonumber(vars[Battle3.VAR_LAST_TALKED]) or tonumber(vars[("0x%X"):format(Battle3.VAR_LAST_TALKED)])
      end
    end
    if not lid or lid < 1 or lid > 254 then return nil end
    local eo = type(Objects) == "table" and type(Objects._byId) == "table" and Objects._byId[lid] or nil
    local x = type(eo) == "table" and tonumber(eo.cellX) or nil
    local y = type(eo) == "table" and tonumber(eo.cellY) or nil
    return "O" .. lid, x, y
  end

  -- One foe party member as the core's team member, or nil.
  local function member(m)
    if type(m) ~= "table" then return nil end
    local Pokemon = b.g3("pokemon")
    local species = tonumber(m.species or m.speciesId or m.id)
    local dex = tonumber(b.ask(Pokemon, "national", nil, species))
    local level = tonumber(m.level or m.lvl)
    if not (dex and level) then return nil end
    local moves = {}
    local list = type(m.moves) == "table" and m.moves or nil
    if not list or #list == 0 then
      local ok, learned = b.call(Pokemon, "movesAtLevel", species, level)
      list = ok and type(learned) == "table" and learned or {}
    end
    for _, mv in ipairs(list) do
      local n = tonumber(type(mv) == "table" and (mv.id or mv.move) or mv)
      if n and n >= 1 and #moves < 4 then moves[#moves + 1] = floor(n) end
    end
    local iv = type(m.ivs) == "table" and m.ivs or {}
    local flat = tonumber(m.iv) or 0
    local ivs = {}
    for _, k in ipairs({ "hp", "atk", "def", "spa", "spd", "spe" }) do
      local v = tonumber(iv[k]) or flat
      ivs[k] = math.max(0, math.min(31, floor(v)))
    end
    return { dex = floor(dex), level = floor(level), moves = moves, ivs = ivs }
  end

  -- The foe of a trainer battle: the one being started (st.starting), else the engine's for the id.
  local function foeOf(trainerId)
    local _, st = held()
    local starting = st and st.starting
    if type(starting) == "table" and tonumber(starting.trainerId) == tonumber(trainerId) and type(starting.foe) == "table" then
      return starting.foe
    end
    return b.ask(b.g3("scripting.trainers"), "foeFromId", nil, trainerId)
  end

  -- (team, name, baseMoney) of a trainer by id, or nil plus a reason (gen1.lua's contract).
  def("trainerTeam", function(cls, trainerId)
    local foe = foeOf(trainerId)
    if type(foe) ~= "table" or type(foe.party) ~= "table" or #foe.party == 0 then return nil, "no_party" end
    local team = {}
    for i = 1, math.min(#foe.party, 6) do
      local m = member(foe.party[i])
      if not m then return nil, "member" end
      team[#team + 1] = m
    end
    local Trainers = b.g3("scripting.trainers")
    local info = b.ask(Trainers, "info", nil, trainerId, { rivalName = nil })
    local name
    if type(info) == "table" then
      local cn, n = info.className, info.name
      if type(cn) == "string" and cn ~= "" and type(n) == "string" and n ~= "" then
        name = cn .. " " .. n
      elseif type(n) == "string" and n ~= "" then
        name = n
      end
    end
    return team, name, 0
  end)

  -- The facts the core's battles.begin wants, or nil (the hidden game fights it).
  local function factsOf(foe, opts)
    local _, st = held()
    if opts.wild then
      local species = type(foe) == "table" and tonumber(foe.species or foe.id or foe.speciesId) or nil
      local dex = tonumber(b.ask(b.g3("pokemon"), "national", nil, species))
      local level = type(foe) == "table" and tonumber(foe.level or foe.lvl) or nil
      if not (dex and level) then return nil end
      local P = b.g3("player")
      local kind = (st and st.fishing) and "fish" or (type(P) == "table" and P.surfing == true and "surf") or "static"
      local x, y = front()
      local first = opts.firstBattleKind ~= nil or opts.firstBattle == true
      return {
        path = "wild", wild = true, kind = kind, dex = floor(dex), level = floor(level), x = x, y = y,
        catch = not first, flee = not first,
      }
    end
    local trainerId = tonumber(opts.trainerId or (type(foe) == "table" and foe.trainerId))
    if not trainerId or trainerId < 0 or trainerId > 1023 then return nil end
    local C = b.constants()
    local cls = C and b.ask(C, "name", nil, C, "trainer_classes", type(foe) == "table" and foe.trainerClass or nil,
                            "TRAINER_CLASS_") or nil
    if not b.symbol(cls) then cls = "TRAINER_CLASS_" .. tostring(type(foe) == "table" and foe.trainerClass or 0) end
    local npc, x, y = lastTalked()
    local Map = b.g3("map")
    return {
      path = npc and "trainer" or "script", cls = cls, party = trainerId, rival = cls == Battle3.RIVAL_CLASS,
      npc = npc, x = x, y = y, map = type(Map) == "table" and Map.current or nil,
    }
  end

  local function copy(t)
    local out = {}
    for k, v in pairs(t) do out[k] = v end
    return out
  end

  -- The texts after a battle, one message box each, then the caller's done.
  local function showThen(texts, i, finish)
    local Message = b.ui3("message")
    local text = texts[i]
    if text == nil or type(Message) ~= "table" or type(Message.show) ~= "function" then
      finish()
      return
    end
    local ok = pcall(Message.show, text, { done = function() showThen(texts, i + 1, finish) end })
    if not ok then finish() end
  end

  -- The engine ended a held battle (Battle.abort, through its finish): show the texts, then the caller's done.
  local function ended(rec, result)
    local _, st = held()
    if st and st.battle == rec then st.battle = nil end
    rec.state = "done"
    local done = rec.done
    -- (MG8: a zone battle's act waits for its answer, so an abort answers it too, as a run)
    if rec.aborted and not rec.firstBattle and not rec.route then
      -- a script stops here, so its trainer challenges again (gen1: "the script stops")
      local Space = b.g3("scripting.space")
      local vm = type(Space) == "table" and Space.vm or nil
      if type(vm) == "table" and type(vm.isRunning) == "function" and vm:isRunning() then pcall(vm.halt, vm) end
      return
    end
    local word = rec.aborted and (rec.route and "run" or "win") or result
    showThen(rec.texts or {}, 1, function()
      if type(done) == "function" then pcall(done, word) end
    end)
  end

  -- The wrapper's body: native or held (the record in b.state().battle).
  local function startBattle(original, mod, game, foe, opts)
    opts = type(opts) == "table" and opts or {}
    local st = b.state()
    local bridge = st and st.bridge
    local live = type(bridge) == "table" and type(bridge.live) == "function" and bridge.live() == true
    local kind = native(opts)
    if not live or type(bridge.battle) ~= "function" or (st.battle and st.battle.state ~= "done") or active()
        or kind then
      -- FP5: a native battle is announced (battle.native) while it runs
      if kind and st then st.native = { kind = kind, session = b.session() } end
      return original(mod, game, foe, opts)
    end
    if opts.twoOpponents and opts.trainerIdB and not opts.wild then
      local ok, merged = b.call(b.g3("battle_bridge"), "twoOpponentFoe", foe, opts.trainerIdB)
      if ok and type(merged) == "table" then foe = merged end
    end
    local facts = factsOf(foe, opts)
    if not facts then return original(mod, game, foe, opts) end
    st.starting = { foe = foe, trainerId = facts.party }
    local okPlan, plan = pcall(bridge.battle, facts)
    st.starting = nil
    if not okPlan or type(plan) ~= "table" then return original(mod, game, foe, opts) end
    local rec = {
      id = plan.id, kind = plan.kind, data = plan.data, path = facts.path, state = "waiting", cls = facts.cls,
      wild = facts.wild, session = b.session(), done = opts.done, trainerId = facts.party, texts = {},
      firstBattle = opts.firstBattleKind ~= nil or opts.firstBattle == true, starting = true,
      route = opts.kbRoute == true,
    }
    st.battle = rec
    local o = copy(opts)
    o.headless, o.autoFight = true, false
    o.done = function(result)
      if rec.starting then
        -- the start failed (battle_bridge.lua:677-685 calls done("win")): as without us
        rec.failed = result or "win"
        return
      end
      ended(rec, result or "win")
    end
    local r1, r2 = original(mod, game, foe, o)
    rec.starting = false
    if rec.failed or not active() then
      -- nothing to hold (an empty party, a failed start): the caller sees the engine's own answer
      if st.battle == rec then st.battle = nil end
      if rec.failed and type(opts.done) == "function" then pcall(opts.done, rec.failed) end
      return r1, r2
    end
    return r1, r2
  end

  -- Fishing: the flag the facts read while the engine rolls the rod's encounter.
  local function fishing(original, ...)
    local st = b.state()
    if st then st.fishing = true end
    local out = pack(pcall(original, ...))
    if st then st.fishing = nil end
    if not out[1] then error(out[2], 0) end
    return unpack(out, 2, out.n)
  end

  -- One wrapper per process on an engine table's function; the handler is this instance's (a hot reload replaces it).
  local function wrap(M, key, handler)
    if type(M) ~= "table" or type(M[key]) ~= "function" then return end
    rawset(M, "_kb_handler_" .. key, handler)
    if rawget(M, "_kb_wrapped_" .. key) then return end
    local original = M[key]
    rawset(M, "_kb_original_" .. key, original)
    rawset(M, "_kb_wrapped_" .. key, true)
    M[key] = function(...)
      local h = rawget(M, "_kb_handler_" .. key)
      if type(h) ~= "function" then return original(...) end
      return h(original, ...)
    end
  end

  b.wrap = wrap

  def("attachBridge", function(bridge)
    local st = b.state()
    if not st then return end
    st.bridge = bridge
    wrap(b.g3("battle_bridge"), "start", startBattle)
    wrap(b.g3("field"), "tryFishingEncounter", fishing)
    -- the gift seam (party.lua)
    if type(b.installGifts) == "function" then b.installGifts() end
    -- FP4: item uses on party members (bag.lua)
    if type(b.installItems) == "function" then b.installItems() end
  end)

  -- The field's land rolls are the habitat pools' while a client follows; water goes on (held as surf).
  def("encounterRoll", function(nextFn, encDef, ctx, bridge)
    local live = type(bridge) == "table" and type(bridge.live) == "function" and bridge.live() == true
    if live and type(ctx) == "table" and ctx.terrain == "land" then
      local st = b.state()
      if st then st.landRolls = (st.landRolls or 0) + 1 end
      return nil
    end
    return nextFn(encDef, ctx)
  end)

  def("battlePending", function()
    local rec = held()
    if not rec or rec.state == "done" then return nil end
    return rec
  end)

  def("battleSent", function(id, gen)
    local rec = held()
    if rec and rec.id == id then rec.sentGen = gen end
  end)

  -- Drops a held record the game no longer has (a new session, a reset): true when one went.
  def("battleStale", function()
    local rec, st = held()
    if not rec then return false end
    if rec.session ~= b.session() or (rec.state == "waiting" and not rec.starting and not active()) then
      st.battle = nil
      return true
    end
    return false
  end)

  -- The answer to a held battle: true when it was this one.
  def("battleFinish", function(id, result)
    local rec = held()
    if not rec or rec.id ~= id or rec.state ~= "waiting" then return false end
    local word = Battle3.WORDS[result]
    if not word then return false end
    rec.state = "result"
    rec.aborted = result == "abort"
    local B = Bt()
    local state = b.ask(B, "getState", nil)
    if result == "win" and not rec.wild and type(state) == "table" then
      -- the prize as the battle's own end pays it (battle/init.lua:1776-1787), and its texts
      local Prize, session = b.g3("battle.prize"), b.session()
      local okAmount, amount = b.call(Prize, "rewardRse", state.trainerId or rec.trainerId, {
        double = state.double or false,
        twoOpponents = type(state.kinds) == "table" and state.kinds.twoOpponents or nil,
        trainerIdB = state.trainerIdB,
        moneyMultiplier = state.moneyMultiplier or 1,
      })
      local defeat = state.defeatText
      if defeat == nil then
        local dialogs = b.ask(b.g3("scripting.trainers"), "dialogs", nil, state.trainerId or rec.trainerId)
        defeat = type(dialogs) == "table" and dialogs.defeat or nil
      end
      if defeat ~= nil then rec.texts[#rec.texts + 1] = defeat end
      if okAmount and tonumber(amount) and tonumber(amount) > 0 and type(session) == "table" then
        local okApply, gained = b.call(Prize, "apply", session, amount)
        if okApply and tonumber(gained) then
          local text = b.ask(Prize, "moneyMessage", nil, session.name, amount)
          if text ~= nil then rec.texts[#rec.texts + 1] = text end
        end
        b.dropView()
      end
    end
    if active() then
      b.call(B, "abort", word)
    else
      ended(rec, word)
    end
    if result == "lose" then b.dropView() end
    return true
  end)

  -- A special battle the hidden game fights itself, for battle.native: the Safari Zone's, a tutorial's
  -- (old_man), a facility's (FP5: the Frontier's rentals, anything the adapter leaves native).
  def("nativeBattle", function()
    local st = b.state()
    if not active() then
      if st then st.native = nil end
      return nil
    end
    local rec = held()
    if rec and rec.state ~= "done" then return nil end
    local nat = st and st.native or nil
    if type(nat) == "table" and nat.session == b.session() then return nat.kind end
    local state = b.ask(Bt(), "getState", nil)
    if type(state) == "table" and state.safari then return "safari" end
    return nil
  end)

  -- The held battle for the observer (busy, not a screen).
  function b.heldBattle()
    local rec = held()
    return rec ~= nil and rec.state ~= "done"
  end
end

return Battle3
