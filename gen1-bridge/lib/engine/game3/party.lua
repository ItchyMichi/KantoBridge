-- game3 party (GEN3_BRIDGE_PLAN.md §4.10, phase G3F): the hidden party is
-- the shadow of Cobblemon's, as on Gen 1 (gen1.lua's "shadow party").
--
-- * Numbers: internal species <-> national dex through Pokemon.national and
--   Pokemon.speciesFromNational (pokemon.lua:281-301); Emerald's move ids are
--   the Showdown numbers (1-354).
-- * applyParty rebuilds session.party in place from a party.sync: a known
--   uid keeps its record, a gift's entry takes the gift's record, anything
--   else is made with Party.giveMon on a scratch session (as the engine's
--   trades do, natives_trade.lua:262) so the live party and Pokedex stay
--   untouched; then species, level and experience, moves with their PP,
--   stats (Pokemon.applyStats, pokemon.lua:472), HP scaled to the new maximum,
--   status and nickname follow the entry; the battle's move overlay is reset
--   (battle/party_view.lua:21-53). Gifts Cobblemon hasn't confirmed stay.
-- * partySafe: in the field, no battle (a held or native one writes the
--   party back over it, battle_bridge.lua:205-256), no party screen open, no
--   script giving a Pokemon still running.
-- * Gifts: Party.giveMon (party.lua:180-292; the starter, givemon, mystery
--   gifts) is wrapped once per process; a call on the live session is a gift
--   (source starter when the starter flag isn't set and the party was empty).
--   Its facts are read when it goes out, so the script's later nickname is
--   included; it goes out once the giving script has ended, or at once while
--   a battle is held (Birch's battle follows the starter). A box gift leaves
--   the hidden box when it goes out: Cobblemon's PC keeps it.
-- * Heals (party.heal): the nurse (saves.lua's scriptCommand) and blackouts
--   (main.lua's world.blacked_out), as gen1.lua:2566-2587.
-- * dex.sync: Dex.setSeen / Dex.setCaught on the session's Pokedex.
-- * Nicknames: renames in the hidden game (the Name Rater) go to Cobblemon.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Party3 = {}

Party3.MAX_MOVE = 354           -- Emerald's moves (constants/emerald/moves.lua: MOVES_COUNT 355)
Party3.HEAL_HOLD_STEPS = 600    -- gen1's: a blackout's heal waits for its warp at most this long
-- stack layers that hold party members (ui.lua's SCREENS)
Party3.PARTY_SCREENS = { party = true, summary = true, box_storage = true, bag = true, evolution_scene = true,
                         move_relearner = true, pc = true }
Party3.STARTER_FLAG = "FLAG_SYS_POKEMON_GET"

function Party3.install(b)
  local def = b.def
  local floor = math.floor

  local function Pokemon() return b.g3("pokemon") end
  local function battleActive() return b.ask(b.g3("battle"), "isActive", false) == true end

  local function vmRunning()
    local Space = b.g3("scripting.space")
    local vm = type(Space) == "table" and Space.vm or nil
    if type(vm) ~= "table" or type(vm.isRunning) ~= "function" then return false end
    local ok, running = pcall(vm.isRunning, vm)
    return ok and running == true
  end

  -- ---- numbers

  def("dexOf", function(species)
    local n = tonumber(b.ask(Pokemon(), "national", nil, tonumber(species)))
    return n and floor(n) or nil
  end)
  def("speciesOfDex", function(dex)
    dex = tonumber(dex)
    if not dex or dex < 1 then return nil end
    local sp = tonumber(b.ask(Pokemon(), "speciesFromNational", nil, dex))
    return sp and floor(sp) or nil
  end)
  def("moveNumOf", function(id)
    id = tonumber(id)
    return id and id >= 1 and id <= Party3.MAX_MOVE and floor(id) or nil
  end)
  def("moveIdOfNum", function(n)
    n = tonumber(n)
    return n and n >= 1 and n <= Party3.MAX_MOVE and floor(n) or nil
  end)

  -- ---- uids (weak keys: a record that leaves the party drops out)

  local monUid = setmetatable({}, { __mode = "k" })
  local function party()
    local s = b.session()
    return type(s) == "table" and type(s.party) == "table" and s.party or nil, s
  end

  def("uidOf", function(mon) return type(mon) == "table" and monUid[mon] or nil end)

  def("partyUids", function()
    local list, set = {}, {}
    for i, mon in ipairs(party() or {}) do
      local uid = monUid[mon]
      list[i] = uid or false
      if uid then set[uid] = true end
    end
    return list, set
  end)

  def("adoptUids", function(list)
    local p = party()
    local n = 0
    if type(list) ~= "table" or not p then return 0 end
    for i, mon in ipairs(p) do
      local uid = list[i]
      if type(uid) == "string" and monUid[mon] == nil then
        monUid[mon] = uid
        n = n + 1
      end
    end
    return n
  end)

  -- ---- gifts

  -- A gift's facts as the core's party.give wants them, read now (the nickname may have come after the give).
  local function giftFacts(mon)
    if type(mon) ~= "table" then return nil end
    local species = tonumber(mon.species or mon.speciesId)
    local dex = tonumber(b.ask(Pokemon(), "national", nil, species))
    if not dex then return nil end
    local moves = {}
    for _, mv in ipairs(type(mon.moves) == "table" and mon.moves or {}) do
      local n = tonumber(type(mv) == "table" and mv.id or mv)
      if n and n >= 1 and n <= Party3.MAX_MOVE and #moves < 4 then moves[#moves + 1] = floor(n) end
    end
    local iv = type(mon.ivs) == "table" and mon.ivs or {}
    local ivs = {}
    for _, k in ipairs({ "hp", "atk", "def", "spa", "spd", "spe" }) do
      ivs[k] = math.max(0, math.min(31, floor(tonumber(iv[k]) or 0)))
    end
    local nick = type(mon.nickname) == "string" and mon.nickname ~= "" and mon.nickname or nil
    local ot = type(mon.otName) == "string" and mon.otName or (type(mon.ot) == "string" and mon.ot or nil)
    return { species = species, dex = floor(dex), level = tonumber(mon.level), moves = moves, ivs = ivs, nick = nick, ot = ot }
  end

  -- Where a gift's record sits in the hidden boxes (session.storage.boxes[n].mons, storage.lua:57-72): the
  -- slot table and key, found by identity (its box first).
  local function boxSlot(g)
    local s = b.session()
    local storage = type(s) == "table" and s.storage or nil
    local boxes = type(storage) == "table" and type(storage.boxes) == "table" and storage.boxes or {}
    local order = {}
    if boxes[g.boxId] then order[1] = boxes[g.boxId] end
    for _, box in pairs(boxes) do order[#order + 1] = box end
    for _, box in ipairs(order) do
      local mons = type(box) == "table" and box.mons or nil
      for k, mon in pairs(type(mons) == "table" and mons or {}) do
        if mon == g.mon then return mons, k end
      end
    end
    return nil
  end

  -- The engine's Party.giveMon, wrapped: a give on the live session is captured as a gift.
  local function give(original, session, species, level, nickname, opts)
    local s = b.session()
    local starter = false
    if session == s and type(s) == "table" then
      starter = not b.flag(Party3.STARTER_FLAG) and (type(s.party) ~= "table" or #s.party == 0)
    end
    local ok, code, mon, boxId, slotIdx = original(session, species, level, nickname, opts)
    local st = b.state()
    if ok and session == s and st and type(mon) == "table" then
      st.serial = st.serial + 1
      st.gifts[#st.gifts + 1] = {
        give = ("g-%s-%d"):format(st.token or "x", st.serial), serial = st.serial, state = "new", syncs = 0,
        box = boxId ~= nil, boxId = boxId, source = starter and "starter" or "gift", session = s,
        mon = mon, waitScript = vmRunning(),
      }
    end
    return ok, code, mon, boxId, slotIdx
  end

  -- One wrapper per process; the handler is this instance's (a hot reload replaces it).
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
  Party3.wrap = wrap

  -- Called by the battle part's attachBridge too: install the gift seam once per process.
  function b.installGifts()
    wrap(b.g3("party"), "giveMon", give)
  end

  local function giftsOf(st)
    local s = b.session()
    local list = type(st) == "table" and type(st.gifts) == "table" and st.gifts or {}
    for i = #list, 1, -1 do
      if list[i].session ~= s then table.remove(list, i) end -- a new game or CONTINUE
    end
    return list
  end

  def("gifts", function()
    local st = b.state()
    local out = {}
    local holding = type(b.heldBattle) == "function" and b.heldBattle()
    local scriptBusy = vmRunning()
    for _, g in ipairs(giftsOf(st)) do
      -- a new gift waits for the script that gave it (its nickname comes later), unless a battle is held now
      if g.state ~= "new" or not g.waitScript or not scriptBusy or holding then
        g.waitScript = false
        local f = giftFacts(g.mon)
        if f then
          out[#out + 1] = {
            give = g.give, serial = g.serial, state = g.state, sentGen = g.sentGen, syncs = g.syncs, box = g.box,
            source = g.source, dex = f.dex, level = f.level, moves = f.moves, ivs = f.ivs, nick = f.nick, ot = f.ot,
          }
        end
      end
    end
    return out
  end)

  local function giftNamed(give)
    local st = b.state()
    for i, g in ipairs(type(st) == "table" and st.gifts or {}) do
      if g.give == give then return g, i, st end
    end
    return nil
  end

  def("giftSent", function(give, generation)
    local g = giftNamed(give)
    if not g then return end
    g.state, g.sentGen = "sent", generation
    if g.box then
      -- Cobblemon's PC keeps it: out of the hidden box (gen1.lua:2341-2347)
      local mons, k = boxSlot(g)
      if mons then mons[k] = nil end
    end
  end)
  def("giftSynced", function(give)
    local g = giftNamed(give)
    if g then g.syncs = g.syncs + 1 end
  end)
  def("giftDone", function(give)
    local g, i, st = giftNamed(give)
    if g then table.remove(st.gifts, i) end
  end)

  -- ---- native catches (the Safari Zone's, the battles the hidden game fights itself): gifts once it ends

  -- pokemon.caught {battle, mon, species, speciesId, isNew, ball, ballId, destination, box, slot}
  -- (battle/catching.lua:254-268): true when recorded.
  def("nativeCaught", function(payload)
    local st = b.state()
    if not (st and type(payload) == "table" and type(payload.mon) == "table") then return false end
    st.catches = type(st.catches) == "table" and st.catches or {}
    st.catches[#st.catches + 1] = {
      mon = payload.mon, box = payload.destination == "box", boxId = tonumber(payload.box), session = b.session(),
    }
    return true
  end)

  -- The recorded catches become gifts (source native_catch) once no battle is up: how many.
  def("releaseCatches", function()
    local st = b.state()
    if not st or type(st.catches) ~= "table" or #st.catches == 0 or battleActive() then return 0 end
    local s, n = b.session(), 0
    for _, c in ipairs(st.catches) do
      if c.session == s then
        st.serial = st.serial + 1
        st.gifts[#st.gifts + 1] = {
          give = ("g-%s-%d"):format(st.token or "x", st.serial), serial = st.serial, state = "new", syncs = 0,
          box = c.box, boxId = c.boxId, source = "native_catch", session = s, mon = c.mon, waitScript = false,
        }
        n = n + 1
      end
    end
    st.catches = {}
    return n
  end)

  -- ---- heals (party.heal)

  def("queueHeal", function(kind)
    local st = b.state()
    if not st then return end
    st.heals = (st.heals or 0) + 1
    if kind == "blackout" then st.healHold = 0 end
  end)
  def("releaseHeals", function()
    local st = b.state()
    if st then st.healHold = nil end
  end)
  def("takeHeals", function()
    local st = b.state()
    if type(st) ~= "table" then return 0 end
    if st.healHold ~= nil then
      st.healHold = st.healHold + 1
      if st.healHold <= Party3.HEAL_HOLD_STEPS then return 0 end
      st.healHold = nil
    end
    local n = st.heals or 0
    st.heals = 0
    return n
  end)

  -- ---- the shadow party

  def("partySafe", function()
    if not b.inField() or battleActive() then return false end
    local Stack = b.ui3("stack")
    local top = b.ask(Stack, "top", nil)
    local id = type(top) == "table" and top.id or (type(top) == "string" and top or nil)
    if id ~= nil and Party3.PARTY_SCREENS[tostring(id)] then return false end
    local st = b.state()
    for _, g in ipairs(giftsOf(st)) do
      if g.state == "new" and g.waitScript and vmRunning() then return false end
    end
    return true
  end)

  -- Brings one record in line with a sync entry.
  local function updateMon(mon, e, s)
    local P = Pokemon()
    if mon.species ~= e.species or mon.level ~= e.level then
      mon.species, mon.speciesId, mon.level = e.species, e.species, e.level
      local SummaryData = b.g3("summary_data")
      local okExp, exp = b.call(SummaryData, "expForLevel", mon.growthRate, e.level)
      if okExp and tonumber(exp) then mon.exp = exp end
    end
    if #e.moves > 0 then
      local oldPp = {}
      for i, id in ipairs(type(mon.moves) == "table" and mon.moves or {}) do
        oldPp[tonumber(type(id) == "table" and id.id or id) or -1] = type(mon.pp) == "table" and mon.pp[i] or nil
      end
      local moves, pp, maxPp = {}, {}, {}
      for i, id in ipairs(e.moves) do
        local full = tonumber(b.ask(P, "movePp", 0, id)) or 0
        moves[i], maxPp[i] = id, full
        pp[i] = math.min(full, tonumber(oldPp[id]) or full)
      end
      mon.moves, mon.pp, mon.maxPp = moves, pp, maxPp
    end
    b.call(P, "applyStats", mon, s)
    local maxHp = tonumber(mon.maxHp) or 1
    if e.hp <= 0 then
      mon.hp = 0
    else
      mon.hp = math.max(1, math.min(maxHp, floor(e.hp * maxHp / e.maxhp + 0.5)))
    end
    mon.status = e.status
    mon.nickname = type(e.nick) == "string" and e.nick or ""
  end

  -- A new record from the engine's own giveMon, on a scratch session (the live party and Pokedex untouched).
  local function fresh(s, species, level)
    local Party = b.g3("party")
    local original = type(Party) == "table" and (rawget(Party, "_kb_original_giveMon") or Party.giveMon) or nil
    if type(original) ~= "function" then return nil end
    local scratch = {
      party = {}, name = s.name, playerName = s.playerName, trainerId = s.trainerId, id = s.id, playerId = s.playerId,
      secretId = s.secretId, gender = s.gender, dex = { seen = {}, owned = {}, caught = {} }, version = s.version,
      options = s.options,
    }
    local ok, given, _, mon = pcall(original, scratch, species, level, "", {})
    if ok and given and type(mon) == "table" then return mon end
    return nil
  end

  def("applyParty", function(entries)
    local p, s = party()
    local st = b.state()
    if not (p and st and type(entries) == "table") then return nil end
    local byUid = {}
    for _, mon in ipairs(p) do
      local uid = monUid[mon]
      if uid then byUid[uid] = mon end
    end
    local res = { created = 0, updated = 0, matched = 0, kept = 0, failed = 0 }
    local out, seen = {}, {}
    for _, e in ipairs(entries) do
      local mon, how = byUid[e.uid], "updated"
      if e.give ~= nil then
        local g, i = giftNamed(e.give)
        if mon == nil and g and type(g.mon) == "table" and not g.box and g.session == s then mon, how = g.mon, "matched" end
        if i then table.remove(st.gifts, i) end
      end
      if mon == nil or seen[mon] then
        mon = fresh(s, e.species, e.level)
        how = mon and "created" or "failed"
      end
      res[how] = res[how] + 1
      if mon then
        local ok = pcall(updateMon, mon, e, s)
        if not ok then res.failed = res.failed + 1 end
        monUid[mon] = e.uid
        seen[mon] = true
        out[#out + 1] = mon
      end
    end
    for _, g in ipairs(giftsOf(st)) do
      if type(g.mon) == "table" and not g.box and not seen[g.mon] and #out < 6 then
        out[#out + 1] = g.mon
        seen[g.mon] = true
        res.kept = res.kept + 1
      end
    end
    for i = #p, 1, -1 do p[i] = nil end
    for i, mon in ipairs(out) do p[i] = mon end
    s.move_overlay = {}
    res.size = #p
    b.dropView()
    return res
  end)

  def("bridgeCounts", function()
    local st = b.state()
    local p = party() or {}
    local known = 0
    for _, mon in ipairs(p) do
      if monUid[mon] then known = known + 1 end
    end
    local battle = st and st.battle
    return {
      party = #p, known = known, gifts = #giftsOf(st), heals = st and st.heals or 0,
      held = type(battle) == "table" and battle.state ~= "done" and 1 or 0, landRolls = st and st.landRolls or 0,
    }
  end)

  -- ---- the Pokedex (dex.sync: national numbers seen and owned in Cobblemon)

  def("applyDex", function(seen, owned)
    local s = b.session()
    if type(s) ~= "table" then return nil end
    s.dex = type(s.dex) == "table" and s.dex or { seen = {}, owned = {}, caught = {} }
    local Dex, P = b.g3("dex"), Pokemon()
    local res = { seen = 0, owned = 0, unknown = 0 }
    local function each(list, fn, field)
      for _, n in ipairs(type(list) == "table" and list or {}) do
        local sp = tonumber(b.ask(P, "speciesFromNational", nil, n))
        if sp then
          if b.call(Dex, fn, s.dex, sp) then res[field] = res[field] + 1 end
        else
          res.unknown = res.unknown + 1
        end
      end
    end
    each(seen, "setSeen", "seen")
    each(owned, "setCaught", "owned")
    b.dropView()
    return res
  end)

  -- ---- nicknames (party.rename)

  def("partyNicks", function()
    local p, s = party()
    local out = {}
    for _, mon in ipairs(p or {}) do
      local uid = monUid[mon]
      if uid then
        local nick = type(mon.nickname) == "string" and mon.nickname ~= "" and mon.nickname or false
        out[#out + 1] = { uid = uid, nick = nick }
      end
    end
    return out, s
  end)
  def("queueRename", function(uid, nick)
    local st = b.state()
    if not st then return end
    st.renames = type(st.renames) == "table" and st.renames or {}
    st.renames[#st.renames + 1] = { uid = uid, nick = nick, session = b.session() }
  end)
  def("takeRenames", function()
    local st = b.state()
    local out = {}
    local list = type(st) == "table" and st.renames or nil
    if type(list) ~= "table" then return out end
    local s = b.session()
    for _, r in ipairs(list) do
      if r.session == s then out[#out + 1] = { uid = r.uid, nick = r.nick } end
    end
    st.renames = {}
    return out
  end)
end

return Party3
