-- gen2 battles (GEN2_BRIDGE_PLAN.md §5.10, phase G2F; decision J10): every Gen 2
-- battle reaches World:startBattle (src/world/gen2/World.lua:6957-7142: step
-- encounters, roamers, fishing, headbutt, Sweet Scent, scripted trainers and
-- statics through startScriptedBattle, :7165-7262; the contest, the catch
-- tutorial, the Battle Tower). There is no headless battle, so the hold keeps
-- the engine's own start and swaps only the screen (spike G2-S6):
--
-- * the wrapper on World.startBattle decides: native (no client, a record
--   already held, the Bug-Catching Contest, the catch tutorial, the Battle
--   Tower, or a battle the core declines) runs untouched; held, the core's
--   plan goes out as battle.start and the battle is armed;
-- * World.pushBattleTransition returns false while armed (no wipe; the
--   original then pushes the battle at once, :7139-7140);
-- * BattleState.new (src/ui/gen2/BattleState.lua, which Screens builds) hands
--   back a placeholder instead of the battle screen while armed. The model
--   (Battle.new, already through the trainer.party hook) and the engine's own
--   after-battle closure ride on it. Any state on the stack freezes the field
--   and the script (Game2.lua:1314-1318), so the hidden game waits there.
--
-- battleFinish(id, result) answers it; the placeholder acts in its own update
-- (inside the engine's step): a trainer win pays the prize as the battle's own
-- end does (Battle:awardPrizeMoney, Battle.lua:3792-3810: the wallet and Mom's
-- share) and shows the trainer's defeat line and the money line
-- (Prize.message) as text boxes; a CANLOSE loss (the Cherrygrove rival) its
-- loss line. Then the model's endBattle(word) raises battle.ended (nothing
-- else does under the hold) and the engine's closure runs the whole tail: the
-- pop, the whiteout on a loss (not for CANLOSE), the roamers, Bill's call, the
-- map music, Mom's shopping, then the caller's onDone, which resumes the
-- script (the trainer flag). An abort hands the closure nil: the script ends
-- there (Vm.lua:1034-1058), so its trainer challenges again. As on Gen 1 and
-- 3, no experience, evolution or catch is written into the hidden game.
--
-- encounterRoll: the field's grass rolls (grass and caves, kinds wild and
-- sweet_scent) are the habitat pools' (J6); water rolls go on and reach the
-- wrapper as surf; fishing arrives with battleType "fish".
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Battle2 = {}

Battle2.WORDS = { win = "win", lose = "lose", run = "run", caught = "caught" }
Battle2.RIVALS = { RIVAL1 = true, RIVAL2 = true }
-- wBattleType values a script arms (World.lua:153-159; Battle.lua:168-184)
Battle2.CANLOSE = 1
Battle2.TRAP = 9
local DELTA = { up = { 0, -1 }, down = { 0, 1 }, left = { -1, 0 }, right = { 1, 0 } }

function Battle2.install(b)
  local def = b.def
  local floor = math.floor

  local function held()
    local st = b.state()
    local rec = st and st.battle
    return type(rec) == "table" and rec or nil, st
  end

  local function data()
    local g = b.game
    return type(g) == "table" and type(g.data) == "table" and g.data or nil
  end

  local function onStack(state)
    local g = b.game
    local stack = type(g) == "table" and g.stack or nil
    for _, s in ipairs(type(stack) == "table" and type(stack.states) == "table" and stack.states or {}) do
      if s == state then return true end
    end
    return false
  end

  -- The battle kind the hidden game keeps (battle.native), or nil for one the client may fight.
  local function nativeKind(opts)
    if opts.contest then return "contest" end
    if opts.tutorial then return "old_man" end
    if opts.battleTower then return "tower" end
    return nil
  end

  -- The cell in front of the player.
  local function front(w)
    local p = w and w.player
    if type(p) ~= "table" then return nil end
    local d = DELTA[p.facing] or DELTA.down
    local x, y = tonumber(p.cellX), tonumber(p.cellY)
    if not (x and y) then return nil end
    return x + d[1], y + d[2]
  end

  local function dvsOut(mon)
    local d = type(mon) == "table" and type(mon.dvs) == "table" and mon.dvs or {}
    local out = { atk = tonumber(d.attack), def = tonumber(d.defense), spd = tonumber(d.speed), spc = tonumber(d.special) }
    for k, v in pairs(out) do
      if not v then return nil end
      out[k] = math.max(0, math.min(15, floor(v)))
    end
    return out
  end

  -- A Pokemon's details (held item, shininess, gender, Unown's letter) into `out`.
  local GENDER = { male = "male", female = "female", unknown = "genderless" }
  local function details(mon, out)
    out.item = b.canonicalItem(mon.item)
    if type(mon.shiny) == "boolean" then out.shiny = mon.shiny end
    out.gender = GENDER[mon.gender]
    local letter = tonumber(mon.unownLetter)
    if mon.species == "UNOWN" and letter and letter >= 1 and letter <= 26 then out.form = string.char(96 + floor(letter)) end
    return out
  end

  -- One foe party member as the core's team member, or nil.
  local function member(mon)
    if type(mon) ~= "table" then return nil end
    local dex = b.adapter.dexOf(mon.species)
    local level = tonumber(mon.level)
    local dvs = dvsOut(mon)
    if not (dex and level and dvs) then return nil end
    local moves = {}
    for _, mv in ipairs(type(mon.moves) == "table" and mon.moves or {}) do
      local n = b.adapter.moveNumOf(type(mv) == "table" and mv.id or mv)
      if n and #moves < 4 then moves[#moves + 1] = n end
    end
    return details(mon, { dex = dex, level = floor(level), moves = moves, dvs = dvs })
  end

  -- The member number of a trainer (its row's `index` in its class, RomExtractorGen2.lua:5440-5447), or nil.
  local function memberNumber(tr)
    local d = data()
    local classes = d and type(d.trainers) == "table" and d.trainers.classes or nil
    local cls = type(classes) == "table" and tr.classId and classes[tr.classId] or nil
    for i, row in ipairs(type(cls) == "table" and type(cls.trainers) == "table" and cls.trainers or {}) do
      if row == tr.row or (tr.memberId ~= nil and row.id == tr.memberId) then return tonumber(row.index) or i end
    end
    return nil
  end

  -- (team, name, baseMoney) of the trainer being started or held, or nil plus a reason (gen1.lua's contract).
  def("trainerTeam", function(cls, party)
    local rec, st = held()
    local tr = (st and st.starting) or (rec and rec.trainer) or nil
    if type(tr) ~= "table" or type(tr.party) ~= "table" or #tr.party == 0 then return nil, "no_party" end
    local team = {}
    for i = 1, math.min(#tr.party, 6) do
      local m = member(tr.party[i])
      if not m then return nil, "member" end
      team[#team + 1] = m
    end
    local name = type(tr.name) == "string" and tr.name ~= "" and tr.name or nil
    return team, name, tonumber(tr.baseMoney) or 0
  end)

  -- The facts the core's battles.begin wants, or nil (the hidden game fights it).
  local function factsOf(w, opts)
    if type(opts.wild) == "table" then
      local mon = opts.wild
      local dex = b.adapter.dexOf(mon.species)
      local level = tonumber(mon.level)
      if not (dex and level) then return nil end
      local kind = "static"
      if opts.battleType == "fish" then
        kind = "fish"
      elseif b.ask(b.w2("FieldMoves"), "isSurfing", false, w.playerState) == true then
        kind = "surf"
      end
      local x, y = front(w)
      local facts = { path = "wild", wild = true, kind = kind, dex = dex, level = floor(level), x = x, y = y,
                      catch = true, flee = opts.battleType ~= Battle2.TRAP, dvs = dvsOut(mon) }
      return details(mon, facts)
    end
    local tr = opts.trainer
    if type(tr) ~= "table" then return nil end
    local cls = b.symbol(tr.classId) and tr.classId or ("TRAINER_CLASS_" .. tostring(tonumber(tr.class) or 0))
    local party = memberNumber(tr) or 0
    local facts = { path = "script", cls = cls, party = party, rival = Battle2.RIVALS[tr.classId] == true,
                    map = w.map and b.ext(w.map.id) or nil }
    -- the object the trainer is, when the engaged one is this trainer (World:startTrainerScript, :8567-8579)
    local npc = w.trainerNpc or w.talkNpc
    local struct = type(npc) == "table" and type(npc.def) == "table" and npc.def.trainer or nil
    if type(struct) == "table" and tonumber(struct.class) == tonumber(tr.class)
        and (tonumber(struct.member) == party or party == 0) then
      local index = tonumber(npc.def.index)
      if index then
        facts.path, facts.npc = "trainer", ("O%d"):format(index)
        facts.x, facts.y = tonumber(npc.cellX), tonumber(npc.cellY)
      end
    end
    return facts
  end

  -- The texts after a held battle, one text box each (Game2:say, Game2.lua:940-943), then `finish`.
  local function showThen(texts, i, finish)
    local g = b.game
    local text = texts[i]
    if text == nil or type(g) ~= "table" or type(g.say) ~= "function" then
      finish()
      return
    end
    local ok = pcall(g.say, g, text, function() showThen(texts, i + 1, finish) end)
    if not ok then finish() end
  end

  -- The placeholder's work once the answer is in (its update, inside the engine's step).
  local function conclude(rec)
    if rec.state ~= "result" or rec.concluding then return end
    rec.concluding = true
    local ph, model = rec.placeholder, rec.model
    local word = rec.word
    local texts = {}
    if not rec.aborted and type(model) == "table" and not model.wild and type(model.trainer) == "table" then
      local tr = model.trainer
      if word == "win" then
        -- WinTrainerBattle's arm (engine/battle/core.asm:2310-2323): the defeat line, then the money
        if type(tr.winText) == "string" and tr.winText ~= "" then texts[#texts + 1] = tr.winText end
        local okAward, award = b.method(model, "awardPrizeMoney")
        if okAward and type(award) == "table" then
          local s = b.saveObject()
          local name = type(s) == "table" and type(s.player) == "table" and s.player.name or nil
          local text = b.ask(b.mod("src.battle.gen2.Prize"), "message", nil, award, name)
          if type(text) == "string" and text ~= "" then texts[#texts + 1] = text end
          b.dropView()
        end
      elseif word == "lose" and model.battleType == Battle2.CANLOSE then
        if type(tr.lossText) == "string" and tr.lossText ~= "" then texts[#texts + 1] = tr.lossText end
      end
    end
    if word then b.method(model, "endBattle", word) end
    showThen(texts, 1, function()
      local _, st = held()
      if st and st.battle == rec then st.battle = nil end
      rec.state = "done"
      if type(ph) == "table" and type(ph.onDone) == "function" then
        local ok, err = pcall(ph.onDone, word, model)
        if not ok then b.warnAt("engine.battle_tail", err) end
      end
    end)
  end

  -- The wrapper's bodies, installed once per process (b.wrap, party.lua).
  local function startBattle(original, w, opts, onDone)
    opts = type(opts) == "table" and opts or {}
    local st = b.state()
    local bridge = st and st.bridge
    local live = type(bridge) == "table" and type(bridge.live) == "function" and bridge.live() == true
    local kind = nativeKind(opts)
    local rec0 = held()
    if not live or type(bridge.battle) ~= "function" or (rec0 and rec0.state ~= "done") or kind
        or w ~= b.ow() or opts.party ~= nil then
      if kind and st then st.native = { kind = kind, world = w } end
      return original(w, opts, onDone)
    end
    local facts = factsOf(w, opts)
    if not facts then return original(w, opts, onDone) end
    st.starting = facts.wild and nil or opts.trainer
    local okPlan, plan = pcall(bridge.battle, facts)
    st.starting = nil
    if not okPlan or type(plan) ~= "table" then return original(w, opts, onDone) end
    local rec = {
      id = plan.id, kind = plan.kind, data = plan.data, path = facts.path, state = "waiting", cls = facts.cls,
      wild = facts.wild == true, session = b.saveObject(), trainer = opts.trainer,
    }
    st.battle, st.armed = rec, rec
    local out = { original(w, opts, onDone) }
    st.armed = nil
    if rec.placeholder == nil then
      -- nothing was pushed (no stack): the engine answered on its own
      if st.battle == rec then st.battle = nil end
    end
    return out[1]
  end

  local function pushTransition(original, w, battle, opts, done)
    local st = b.state()
    if st and st.armed then return false end
    return original(w, battle, opts, done)
  end

  local function newBattleState(original, g, opts)
    local st = b.state()
    local rec = st and st.armed
    if type(rec) ~= "table" or type(opts) ~= "table" then return original(g, opts) end
    st.armed = nil
    local ph = { screenId = "Gen2BattleState", isBattle = true, isOpaque = false, kbHeld = true,
                 battle = opts.battle, onDone = opts.onDone }
    function ph.update() conclude(rec) end
    function ph.draw() end
    rec.placeholder, rec.model = ph, opts.battle
    return ph
  end

  def("attachBridge", function(bridge)
    local st = b.state()
    if not st then return end
    st.bridge = bridge
    local wrap = b.wrap
    if type(wrap) ~= "function" then return end
    wrap(b.w2("World"), "startBattle", startBattle)
    wrap(b.w2("World"), "pushBattleTransition", pushTransition)
    wrap(b.ui2("BattleState"), "new", newBattleState)
    -- the gift seams (party.lua) and the item-use guards (bag.lua)
    if type(b.installGifts) == "function" then b.installGifts() end
    if type(b.installItemGuards) == "function" then b.installItemGuards() end
  end)

  -- The field's grass rolls are the habitat pools' while a client follows; water and fishing go on (held).
  def("encounterRoll", function(nextFn, tables, ctx, bridge)
    local live = type(bridge) == "table" and type(bridge.live) == "function" and bridge.live() == true
    if live and type(ctx) == "table" and ctx.terrain == "grass" and (ctx.kind == "wild" or ctx.kind == "sweet_scent") then
      local st = b.state()
      if st then st.landRolls = (st.landRolls or 0) + 1 end
      return nil
    end
    return nextFn(tables, ctx)
  end)
  def("encounterFishing", function(nextFn, rod, mapId, pool, _bridge, ctx)
    return nextFn(rod, mapId, pool, ctx)
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

  -- Drops a held record the game no longer has (a new save, a reset, its placeholder gone): true when one went.
  def("battleStale", function()
    local rec, st = held()
    if not rec then return false end
    if rec.session ~= b.saveObject() or (rec.state == "waiting" and rec.placeholder ~= nil and not onStack(rec.placeholder)) then
      st.battle = nil
      return true
    end
    return false
  end)

  -- The answer to a held battle: true when it was this one. The placeholder acts on it at the engine's next step.
  def("battleFinish", function(id, result)
    local rec = held()
    if not rec or rec.id ~= id or rec.state ~= "waiting" or not rec.placeholder then return false end
    if result ~= "abort" and not Battle2.WORDS[result] then return false end
    rec.state = "result"
    rec.aborted = result == "abort"
    rec.word = Battle2.WORDS[result]
    if result == "lose" then b.dropView() end
    return true
  end)

  -- A special battle the hidden game fights itself (battle.native): the contest, the catch tutorial, the Tower.
  -- (its screen comes after the wipe: the kind is told once World.battleActive is up, and forgotten once it is
  -- down again or the World is another)
  def("nativeBattle", function()
    local st = b.state()
    local nat = st and st.native
    if type(nat) ~= "table" then return nil end
    local w = b.ow()
    if w ~= nat.world then
      st.native = nil
      return nil
    end
    if w.battleActive then
      nat.up = true
      return nat.kind
    end
    if nat.up then st.native = nil end
    return nil
  end)

  -- The held battle for the observer (busy, not a screen) and the party's safety.
  function b.heldBattle()
    local rec = held()
    return rec ~= nil and rec.state ~= "done"
  end
end

return Battle2
