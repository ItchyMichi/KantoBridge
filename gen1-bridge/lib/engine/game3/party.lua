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
-- Trades, the daycare and eggs (docs/SEVERAL_GAMES_FOLLOWUPS.md item 7):
-- the engine changes the party natively, so each becomes what Cobblemon must
-- follow, as gen1.lua's party operations do (kb_party sends them):
-- * In-game trades: Trade.tradeMons (natives_trade.lua:303; the scene's
--   onSwap, natives_trade.lua:431) is wrapped once per process. On the live
--   session the member sent goes as party.take{reason:"trade"} by its uid and
--   the one received as a gift with source "trade" (its nickname and OT, read
--   once the trade's script has ended: a trade evolution comes first). A sync
--   waits while a trade is armed (natives_trade.lua:481) or its scene is open
--   (src/core/game3/trade_scene.lua:887).
-- * The daycare holds two (src/core/game3/daycare.lua:76-130). It is watched
--   every step, as gen1.lua's daycareStep: a member with a uid that it holds
--   is daycare.deposit; back in the party, daycare.withdraw with the steps the
--   daycare counted for it (src/core/game3/daycare.lua:450), the experience
--   Daycare.withdraw (wrapped) gave it (src/core/game3/daycare.lua:313). The boarded
--   uids, with each member's personality value, are kept in mod.save (part of
--   the hidden save), so a CONTINUE finds them again.
-- * Takes, deposits and withdrawals go out until a sync confirms them; a
--   sync never brings a taken or boarded uid back, and a withdrawn member
--   stays until a sync names it.
-- * Eggs (the daycare's, breeding.lua:540-565, and Party.giveEgg,
--   party.lua:294-304): Cobblemon has no eggs, so an egg lives in the hidden
--   party only. It is never a gift; every sync keeps it after Cobblemon's
--   members, in a hidden box while the party has no room and back once it
--   has (src/core/game3/storage.lua:164). Hatching (Breeding.hatchMon,
--   breeding.lua:568, wrapped; src/ui/game3/egg_hatch.lua:177) makes it a gift
--   with source "egg", sent once the hatch screen and its nickname prompt are
--   done (src/ui/game3/egg_hatch.lua:132).
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Party3 = {}

Party3.MAX_MOVE = 354           -- Emerald's moves (constants/emerald/moves.lua: MOVES_COUNT 355)
Party3.HEAL_HOLD_STEPS = 600    -- gen1's: a blackout's heal waits for its warp at most this long
-- stack layers that hold party members (ui.lua's SCREENS), and the egg hatching
Party3.PARTY_SCREENS = { party = true, summary = true, box_storage = true, bag = true, evolution_scene = true,
                         move_relearner = true, pc = true, egg_hatch = true }
Party3.STARTER_FLAG = "FLAG_SYS_POKEMON_GET"
Party3.PARTY_SIZE = 6
Party3.DAYCARE_SLOTS = 2        -- src/core/game3/daycare.lua:9 DAYCARE_MON_COUNT
Party3.BOX_SLOTS = 30           -- src/core/game3/storage.lua:13 IN_BOX_COUNT

-- FP7: Gen 3's natures in their id order (Pokemon.natureId: personality % 25), as Cobblemon's (Showdown's) names
Party3.NATURES = { "hardy", "lonely", "brave", "adamant", "naughty", "bold", "docile", "relaxed", "impish", "lax",
                   "timid", "hasty", "serious", "jolly", "naive", "modest", "mild", "quiet", "bashful", "rash", "calm",
                   "gentle", "sassy", "careful", "quirky" }
Party3.NATURE_ID = {}
for i, n in ipairs(Party3.NATURES) do Party3.NATURE_ID[n] = i - 1 end
-- Pokemon.gender's letters <-> the protocol's genders
Party3.GENDER_OUT = { M = "male", F = "female", U = "genderless" }
Party3.GENDER_IN = { male = "M", female = "F" }

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

  -- An egg (src/core/game3/pokemon.lua:1399, the engine's own test).
  local function isEgg(mon)
    if type(mon) ~= "table" then return false end
    local ok, v = b.call(Pokemon(), "isEgg", mon)
    if ok then return v == true end
    return mon.isEgg == true or mon.egg == true
  end

  -- trades, the daycare and eggs (bridgeCounts; the logs carry these counts only)
  local counts = { trades = 0, tradeNoUid = 0, deposits = 0, withdraws = 0, depositNoUid = 0, eggGifts = 0,
                   eggsParked = 0, eggsBack = 0, eggsLost = 0, hatched = 0 }

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

  -- ---- the visitor stand-in (docs/specs/FOREIGN_POKEMON_PLAN.md §3, FP3)
  --
  -- KB_VISITOR at internal index 440 (412 is EGG and 413+ the Unown
  -- letters, src/core/game3/pokemon.lua:537,1432-1439), registered from the
  -- mod's entry chunk in game3's shape (src/mods/Schemas.lua:1829-1857). It
  -- has no national number (writeMon never writes one, pokemon.lua:293-301),
  -- so no Pokedex lists it; no evolutions, catch rate 0. Its art comes
  -- through the pokemon.sprite and pokemon.icon hooks, which pass the numeric
  -- species (src/mods/Gen3Compat.lua:2095-2109, 2168-2182): a registered name
  -- doesn't resolve by itself. Pokemon.applyStats (pokemon.lua:472; at every
  -- save load too, save_mon.lua:44-60) is wrapped once per process to put a
  -- record's kbStats (Cobblemon's numbers) back. FP0 ran all of it on Emerald.
  local VISITOR = 440
  local visitorAccepted = false
  -- FieldMoves.MOVES (src/core/game3/field_moves.lua:20-36), when the module isn't there
  local FIELD_MOVES = { 15, 19, 57, 70, 148, 249, 127, 291, 91, 100, 135, 208, 230, 29, 290 }

  local function isVisitor(mon) return type(mon) == "table" and tonumber(mon.species) == VISITOR end

  local function pinWrapper()
    local P = Pokemon()
    if type(P) ~= "table" or type(P.applyStats) ~= "function" or rawget(P, "kbPinned") then return end
    P.kbPinned = true
    local orig = P.applyStats
    P.applyStats = function(mon, ...)
      local r = orig(mon, ...)
      local p = type(mon) == "table" and mon.kbStats or nil
      if type(p) == "table" then
        mon.maxHp, mon.attack, mon.defense, mon.speed, mon.spAtk, mon.spDef = p.hp, p.atk, p.def, p.spe, p.spa, p.spd
        mon.atk, mon.def, mon.spe, mon.spa, mon.spd = p.atk, p.def, p.spe, p.spa, p.spd
        if type(mon.hp) == "number" and mon.hp > p.hp then mon.hp = p.hp end
      end
      return r
    end
  end

  -- §4.14: a cartridge export never carries a visitor (rse's exportPort
  -- writes an internal index raw, src/save_convert/Gen3Save.lua:1602-1606):
  -- the export (SaveConvert.lua:680, Gen3Save.forVersion(v).exportPort)
  -- gets a copy of the session without them. One wrapper per process.
  local function withoutVisitors(save)
    if type(save) ~= "table" then return save end
    local copy = {}
    for k, v in pairs(save) do copy[k] = v end
    local function list(mons)
      local out = {}
      for _, mon in ipairs(type(mons) == "table" and mons or {}) do
        if not isVisitor(mon) then out[#out + 1] = mon end
      end
      return out
    end
    copy.party = list(save.party)
    local storage = save.storage
    if type(storage) == "table" and type(storage.boxes) == "table" then
      local cs = {}
      for k, v in pairs(storage) do cs[k] = v end
      cs.boxes = {}
      for i, box in ipairs(storage.boxes) do
        local cb = {}
        for k, v in pairs(type(box) == "table" and box or {}) do cb[k] = v end
        if type(box) == "table" and type(box.mons) == "table" then
          cb.mons = {}
          for k = 1, Party3.BOX_SLOTS do
            local mon = box.mons[k]
            if not isVisitor(mon) then cb.mons[k] = mon end
          end
        end
        cs.boxes[i] = cb
      end
      copy.storage = cs
    end
    if type(save.hallOfFameTeams) == "table" then
      copy.hallOfFameTeams = {}
      for i, team in ipairs(save.hallOfFameTeams) do
        local ct = {}
        for k, v in pairs(type(team) == "table" and team or {}) do ct[k] = v end
        if type(team) == "table" and type(team.mons) == "table" then ct.mons = list(team.mons) end
        copy.hallOfFameTeams[i] = ct
      end
    end
    return copy
  end
  local function wrapExport()
    local G = b.mod("src.save_convert.Gen3Save")
    if type(G) ~= "table" or type(G.forVersion) ~= "function" or rawget(G, "kbVisitorForVersion") then return end
    local forVersion = G.forVersion
    G.kbVisitorForVersion = forVersion
    G.forVersion = function(...)
      local codec = forVersion(...)
      if type(codec) == "table" and type(codec.exportPort) == "function" and not rawget(codec, "kbVisitorExport") then
        local exportPort = codec.exportPort
        codec.kbVisitorExport = exportPort
        codec.exportPort = function(save, ...) return exportPort(withoutVisitors(save), ...) end
      end
      return codec
    end
  end

  def("registerVisitor", function(content, dir, hooks)
    local reg = type(content) == "table" and content.pokemon or nil
    if type(reg) ~= "table" or type(reg.register) ~= "function" then return false end
    local art = type(dir) == "string" and dir or ""
    local front, back, icon = art .. "/game3_front.png", art .. "/game3_back.png", art .. "/game3_icon.png"
    local function record(types)
      return {
        id = "KB_VISITOR", name = "VISITOR", index = VISITOR, types = types,
        baseStats = { hp = 50, attack = 50, defense = 50, speed = 50, specialAttack = 50, specialDefense = 50 },
        catchRate = 0, baseExp = 0, growthRate = "MEDIUM_FAST", learnset = {}, evolutions = {},
        -- FP6 (Q10): Undiscovered twice (breeding.lua:34): a visitor in the daycare never makes an egg
        eggGroups = { 15, 15 },
        spriteFront = front, spriteBack = back,
      }
    end
    -- Q3: the "???" type where the registry takes it, else Normal
    local ok, res = pcall(reg.register, reg, "KB_VISITOR", record({ "MYSTERY" }))
    if not (ok and res ~= nil and res ~= false) then ok, res = pcall(reg.register, reg, "KB_VISITOR", record({ "NORMAL" })) end
    visitorAccepted = ok and res ~= nil and res ~= false
    hooks = hooks or (type(b.opts) == "table" and b.opts.hooks) or nil
    if visitorAccepted and type(hooks) == "table" and type(hooks.wrap) == "function" then
      pcall(hooks.wrap, hooks, "pokemon.sprite", function(nextFn, path, ctx)
        if type(ctx) == "table" and tonumber(ctx.gen3Species) == VISITOR then return ctx.side == "back" and back or front end
        return nextFn(path, ctx)
      end)
      pcall(hooks.wrap, hooks, "pokemon.icon", function(nextFn, path, ctx)
        if type(ctx) == "table" and tonumber(ctx.gen3Species) == VISITOR then return icon end
        return nextFn(path, ctx)
      end)
    end
    if visitorAccepted then
      pcall(pinWrapper)
      pcall(wrapExport)
    elseif b.log then
      b.log.warn("party.visitor_refused", { at = ok and "nil" or "threw" })
    end
    return visitorAccepted
  end)
  def("visitorSpecies", function()
    if not visitorAccepted then return nil end
    local name = b.ask(Pokemon(), "name", nil, VISITOR)
    if name == nil then return nil end
    pcall(pinWrapper)
    return VISITOR
  end)
  -- R2/Q15: Emerald's only forms Cobblemon could name are Unown's letters (a..z)
  def("formKnown", function(species, form)
    return form == nil or (b.ask(Pokemon(), "national", nil, tonumber(species)) == 201 and type(form) == "string"
                           and form:find("^%l$") ~= nil)
  end)
  def("visitorMoves", function()
    local FM = b.g3("field_moves")
    local set = {}
    if type(FM) == "table" and type(FM.MOVES) == "table" then
      for _, n in pairs(FM.MOVES) do set[n] = true end
    else
      for _, n in ipairs(FIELD_MOVES) do set[n] = true end
    end
    return set
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

  -- ---- party operations (kb_party's ops: takes, deposits, withdrawals), as gen1.lua's
  --
  -- Queued in the process-lifetime state, with the session they belong to (a new game or CONTINUE drops the
  -- others); each goes out once and again after every snapshot until a party.sync confirms it.

  local function opsOf(st)
    local s = b.session()
    local list = type(st) == "table" and type(st.ops) == "table" and st.ops or {}
    for i = #list, 1, -1 do
      if list[i].session ~= s then table.remove(list, i) end -- a new game or CONTINUE
    end
    return list
  end

  local function queueOp(op)
    local st = b.state()
    if not st then return nil end
    st.ops = type(st.ops) == "table" and st.ops or {}
    st.serial = st.serial + 1
    op.id, op.state, op.syncs, op.session = st.serial, "new", 0, b.session()
    st.ops[#st.ops + 1] = op
    return op.id
  end

  -- The pending operations of this session, as plain values, oldest first.
  def("ops", function()
    local out = {}
    for _, op in ipairs(opsOf(b.state())) do
      out[#out + 1] = { id = op.id, serial = op.id, kind = op.kind, uid = op.uid, reason = op.reason,
                        steps = op.steps, state = op.state, sentGen = op.sentGen, syncs = op.syncs }
    end
    return out
  end)

  local function opNamed(id)
    local st = b.state()
    for i, op in ipairs(type(st) == "table" and type(st.ops) == "table" and st.ops or {}) do
      if op.id == id then return op, i, st end
    end
    return nil
  end
  def("opSent", function(id, generation)
    local op = opNamed(id)
    if op then op.state, op.sentGen = "sent", generation end
  end)
  def("opSynced", function(id)
    local op = opNamed(id)
    if op then op.syncs = op.syncs + 1 end
  end)
  def("opDone", function(id)
    local op, i, st = opNamed(id)
    if op then table.remove(st.ops, i) end
  end)

  -- The daycare watch's record for this session ({ session, list = { { mon, uid, steps, pid } } }), or nil.
  local function boardedOf(st)
    local rec = type(st) == "table" and st.boarded or nil
    if type(rec) == "table" and rec.session == b.session() then return rec end
    return nil
  end

  -- The uids no party.sync may put back into the hidden party: the pending takes and the members the daycare holds.
  def("excludedUids", function()
    local st = b.state()
    local out = {}
    for _, op in ipairs(opsOf(st)) do
      if op.kind == "take" and op.uid then out[op.uid] = true end
    end
    local rec = boardedOf(st)
    for _, e in ipairs(rec and rec.list or {}) do
      if e.uid then out[e.uid] = true end
    end
    return out
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
    -- FP4 (§5): the held item, when Cobblemon has one for it; FP7: shininess, gender, friendship, nature
    local held = type(b.heldCanonical) == "function" and b.heldCanonical(mon.item or mon.heldItem) or false
    local P = Pokemon()
    local shiny = b.ask(P, "isShiny", nil, mon)
    local personality = tonumber(mon.personality) or 0
    local gender = Party3.GENDER_OUT[b.ask(P, "gender", nil, species, personality)]
    local nature = Party3.NATURES[(tonumber(b.ask(P, "natureId", nil, personality)) or -1) + 1]
    local f = tonumber(mon.friendship or mon.happiness)
    return { species = species, dex = floor(dex), level = tonumber(mon.level), moves = moves, ivs = ivs, nick = nick, ot = ot,
             item = held ~= "hidden" and held or nil, shiny = type(shiny) == "boolean" and shiny or nil, gender = gender,
             friendship = f and math.max(0, math.min(255, floor(f))) or nil, nature = nature }
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

  -- A gift of the live session's (st.gifts), as give records it.
  local function addGift(st, s, mon, source, extra)
    st.serial = st.serial + 1
    local g = {
      give = ("g-%s-%d"):format(st.token or "x", st.serial), serial = st.serial, state = "new", syncs = 0,
      box = false, source = source, session = s, mon = mon, waitScript = false,
    }
    for k, v in pairs(extra or {}) do g[k] = v end
    st.gifts[#st.gifts + 1] = g
    return g
  end

  -- The engine's Trade.tradeMons (natives_trade.lua:303-343), wrapped: on the live session the member that
  -- left is a take by its uid, the one that arrived a gift with source "trade" (its facts read once the
  -- trade's script has ended, so a trade evolution comes first).
  local function tradeMons(original, session, playerSlot, offered)
    local s = b.session()
    local sent = original(session, playerSlot, offered)
    local st = b.state()
    if type(sent) == "table" and type(offered) == "table" and st and type(s) == "table" and session == s then
      counts.trades = counts.trades + 1
      local uid = monUid[sent]
      if uid then
        queueOp({ kind = "take", uid = uid, reason = "trade" })
      else
        -- a member Cobblemon never named: a gift not yet sent is simply forgotten; anything else is counted
        for i, g in ipairs(type(st.gifts) == "table" and st.gifts or {}) do
          if g.mon == sent and g.state == "new" then table.remove(st.gifts, i) break end
        end
        counts.tradeNoUid = counts.tradeNoUid + 1
        if b.log then b.log.warn("engine.trade_no_uid", { count = counts.tradeNoUid }) end
      end
      addGift(st, s, offered, "trade", { waitScript = vmRunning() })
      if b.log then b.log.info("engine.trade", { count = counts.trades }) end
    end
    return sent
  end

  -- The engine's Breeding.hatchMon (breeding.lua:568-620; src/ui/game3/egg_hatch.lua:177), wrapped: on the
  -- live session the egg that hatched is a gift with source "egg", sent once the hatch screen has closed
  -- (its nickname prompt is part of it).
  local function hatchMon(original, session, mon)
    local wasEgg = isEgg(mon)
    local result = original(session, mon)
    local s, st = b.session(), b.state()
    if wasEgg and type(mon) == "table" and not isEgg(mon) and st and type(s) == "table"
        and (session == nil or session == s) then
      counts.hatched = counts.hatched + 1
      addGift(st, s, mon, "egg", { waitHatch = true })
      if b.log then b.log.info("engine.egg_hatched", { count = counts.hatched }) end
    end
    return result
  end

  -- The engine's Daycare.withdraw (src/core/game3/daycare.lua:313-334; Daycare.take passes the steps the daycare
  -- counted, src/core/game3/daycare.lua:357), wrapped: the steps each member came back with, for the daycare watch
  -- (however many steps came since it last looked).
  local function withdraw(original, session, mon, steps, stored)
    local st = b.state()
    if st and type(mon) == "table" then
      st.withdrawn = type(st.withdrawn) == "table" and st.withdrawn or setmetatable({}, { __mode = "k" })
      st.withdrawn[mon] = math.max(0, floor(tonumber(steps) or 0))
    end
    return original(session, mon, steps, stored)
  end

  -- The hatch screen is up (src/ui/game3/egg_hatch.lua:132).
  local function hatching()
    return b.ask(b.ui3("egg_hatch"), "isOpen", false) == true
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

  -- Called by the battle part's attachBridge too: install the gift seams once per process (the giveMon gift, the
  -- in-game trade's swap, the daycare's withdrawal, the egg hatching).
  -- FP6 (FOREIGN_POKEMON_PLAN §4.11, Q7): contests are for natives; a visitor at the entry is the game's own
  -- "can't enter" (Util.eligibility, src/core/game3/rse/contest_util.lua:100-103: the desk's ineligible line)
  local function eligibility(original, mon, ...)
    local st = b.state()
    local bridge = st and st.bridge
    local live = type(bridge) == "table" and type(bridge.live) == "function" and bridge.live() == true
    local U = b.g3("rse.contest_util")
    if live and isVisitor(mon) and type(U) == "table" and type(U.ELIGIBILITY) == "table" then
      counts.contestRefused = (counts.contestRefused or 0) + 1
      return U.ELIGIBILITY.CANT_ENTER
    end
    return original(mon, ...)
  end

  function b.installGifts()
    wrap(b.g3("party"), "giveMon", give)
    wrap(b.g3("scripting.natives_trade"), "tradeMons", tradeMons)
    wrap(b.g3("daycare"), "withdraw", withdraw)
    wrap(b.g3("breeding"), "hatchMon", hatchMon)
    wrap(b.g3("rse.contest_util"), "eligibility", eligibility)
  end

  -- FP6 (§4.9): the Pokedex never counts the stand-in (session.dex.seen/owned by internal species, dex.lua:184-204)
  def("dexSweep", function()
    local s = b.session()
    local dex = type(s) == "table" and type(s.dex) == "table" and s.dex or nil
    if not dex then return 0 end
    local n = 0
    for _, k in ipairs({ "seen", "owned" }) do
      if type(dex[k]) == "table" and dex[k][VISITOR] then
        dex[k][VISITOR] = nil
        n = n + 1
      end
    end
    return n
  end)

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
    local list = giftsOf(st)
    for i = #list, 1, -1 do
      -- an egg (Party.giveEgg gives it through giveMon) stays in the hidden game until it hatches
      if list[i].state == "new" and isEgg(list[i].mon) then
        table.remove(list, i)
        counts.eggGifts = counts.eggGifts + 1
      end
    end
    local hatchOpen = hatching()
    for _, g in ipairs(list) do
      if g.waitHatch and not hatchOpen then g.waitHatch = false end
      -- a new gift waits for the script that gave it (its nickname comes later), unless a battle is held now;
      -- a hatched egg for the hatch screen (its nickname prompt)
      if g.waitHatch then
        -- not yet
      elseif g.state ~= "new" or not g.waitScript or not scriptBusy or holding then
        g.waitScript = false
        local f = giftFacts(g.mon)
        if f then
          out[#out + 1] = {
            give = g.give, serial = g.serial, state = g.state, sentGen = g.sentGen, syncs = g.syncs, box = g.box,
            source = g.source, dex = f.dex, level = f.level, moves = f.moves, ivs = f.ivs, nick = f.nick, ot = f.ot,
            item = f.item or nil, shiny = f.shiny, gender = f.gender, friendship = f.friendship, nature = f.nature,
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
    -- an in-game trade under way (its member chosen by party slot, natives_trade.lua:481, the swap in its scene)
    -- and an egg hatching (the name prompt over its screen)
    local Trade = b.g3("scripting.natives_trade")
    if type(Trade) == "table" and rawget(Trade, "_offered") ~= nil and vmRunning() then return false end
    if b.ask(b.g3("trade_scene"), "isOpen", false) == true or hatching() then return false end
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
    -- FP7 (FOREIGN_POKEMON_PLAN §5): Cobblemon's shininess (the record's isShiny, pokemon.lua:1442-1451), nature and
    -- gender (both read from the personality, :392-407: a new one is drawn only when the record's disagrees; never
    -- for Unown, whose letter it is), and friendship
    if type(e.shiny) == "boolean" then mon.isShiny = e.shiny end
    local wantNature = type(e.nature) == "string" and Party3.NATURE_ID[e.nature] or nil
    local wantGender = Party3.GENDER_IN[e.gender]
    local P = Pokemon()
    local sp = tonumber(mon.species)
    if (wantNature or wantGender) and type(P) == "table" and b.ask(P, "national", nil, sp) ~= 201 then
      local function fits(p)
        if wantNature and b.ask(P, "natureId", nil, p) ~= wantNature then return false end
        if wantGender then
          local g = b.ask(P, "gender", nil, sp, p)
          if g ~= "U" and g ~= wantGender then return false end
        end
        return true
      end
      if not fits(tonumber(mon.personality) or 0) then
        for _ = 1, 4096 do
          local p = math.random(0, 65535) * 65536 + math.random(0, 65535)
          if fits(p) then
            mon.personality = p
            break
          end
        end
      end
    end
    if tonumber(e.friendship) then mon.friendship = math.max(0, math.min(255, floor(e.friendship))) end
    -- FP4 (§5): Cobblemon's held item (an entry with details always says whether it is shiny); a hidden-only
    -- item (Mail, ...) stays while Cobblemon's member holds nothing
    if (e.item ~= nil or e.shiny ~= nil) and type(b.heldNumber) == "function" then
      local hidden = b.heldCanonical(mon.item or mon.heldItem) == "hidden"
      if not (e.item == nil and hidden) then
        local n = e.item ~= nil and b.heldNumber(e.item) or nil
        -- FP6 (§3.5): a hidden-only item Cobblemon's replaces goes back to the hidden bag
        if hidden and n ~= (mon.item or mon.heldItem) and type(b.bagReturn) == "function" then
          b.bagReturn(mon.item or mon.heldItem)
        end
        mon.item, mon.heldItem = n, n
      end
    end
    -- FP3 (§3.1, Q20): Cobblemon's stats on every record that has them (the wrapper keeps them)
    if type(e.stats) == "table" then
      local p = e.stats
      mon.kbStats = { hp = p.hp, atk = p.atk, def = p.def, spa = p.spa, spd = p.spd, spe = p.spe }
      mon.maxHp, mon.attack, mon.defense, mon.speed, mon.spAtk, mon.spDef = p.hp, p.atk, p.def, p.spe, p.spa, p.spd
      mon.atk, mon.def, mon.spe, mon.spa, mon.spd = p.atk, p.def, p.spe, p.spa, p.spd
      mon.hp = e.hp <= 0 and 0 or math.max(1, math.min(p.hp, e.hp))
    else
      mon.kbStats = nil
    end
    -- FP4 (§4.4): the stones and machine moves Cobblemon would take
    if type(e.stones) == "table" then
      local set = {}
      for _, id in ipairs(e.stones) do
        local cat = type(b.opts) == "table" and type(b.opts.items) == "table" and b.opts.items.items or nil
        local item = type(cat) == "table" and cat[id] or nil
        local sym = type(item) == "table" and type(item.symbols) == "table" and item.symbols.gen3 or nil
        if sym then set[sym] = true end
      end
      mon.kbStones = set
    else
      mon.kbStones = nil
    end
    if type(e.canLearn) == "table" then
      local set = {}
      for _, n in ipairs(e.canLearn) do
        if n >= 1 and n <= Party3.MAX_MOVE then set[n] = true end
      end
      mon.kbCanLearn = set
    else
      mon.kbCanLearn = nil
    end
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

  -- An egg with no room in the party waits in the first open hidden box slot (src/core/game3/storage.lua:74,
  -- :164); false when every box is full.
  local function parkEgg(s, mon)
    local Storage = b.g3("storage")
    local ok, storage = b.call(Storage, "ensure", s)
    if not (ok and type(storage) == "table" and type(storage.boxes) == "table") then return false end
    local okSlot, boxId, slot = b.call(Storage, "findOpenSlot", storage)
    local box = okSlot and boxId and storage.boxes[boxId] or nil
    if not (type(box) == "table" and type(box.mons) == "table" and tonumber(slot)) then return false end
    box.mons[slot] = mon
    return true
  end

  -- The eggs in the hidden boxes, in box order: { mons, key, mon } each.
  local function boxedEggs(s)
    local out = {}
    local storage = type(s) == "table" and s.storage or nil
    for _, box in ipairs(type(storage) == "table" and type(storage.boxes) == "table" and storage.boxes or {}) do
      local mons = type(box) == "table" and type(box.mons) == "table" and box.mons or nil
      for k = 1, mons and Party3.BOX_SLOTS or 0 do
        if isEgg(mons[k]) then out[#out + 1] = { mons = mons, key = k, mon = mons[k] } end
      end
    end
    return out
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
    -- a member back from the daycare that Cobblemon hasn't shown yet stays too (its withdrawal is pending)
    for _, op in ipairs(opsOf(st)) do
      if op.kind == "withdraw" and type(op.mon) == "table" and not seen[op.mon] and #out < Party3.PARTY_SIZE then
        out[#out + 1] = op.mon
        seen[op.mon] = true
        res.kept = res.kept + 1
      end
    end
    -- eggs live in the hidden game only: after Cobblemon's members, in a hidden box while the party has no
    -- room, and back from the boxes once it has
    res.eggs, res.parked, res.back = 0, 0, 0
    for _, mon in ipairs(p) do
      if not seen[mon] and isEgg(mon) then
        seen[mon] = true
        if #out < Party3.PARTY_SIZE then
          out[#out + 1] = mon
          res.eggs = res.eggs + 1
        elseif parkEgg(s, mon) then
          res.parked = res.parked + 1
          counts.eggsParked = counts.eggsParked + 1
        else
          counts.eggsLost = counts.eggsLost + 1
          if b.log then b.log.warn("engine.egg_no_room", { count = counts.eggsLost }) end
        end
      end
    end
    if #out < Party3.PARTY_SIZE then
      for _, slot in ipairs(boxedEggs(s)) do
        if #out < Party3.PARTY_SIZE and not seen[slot.mon] then
          slot.mons[slot.key] = nil
          out[#out + 1] = slot.mon
          seen[slot.mon] = true
          res.eggs, res.back = res.eggs + 1, res.back + 1
          counts.eggsBack = counts.eggsBack + 1
        end
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
    local rec, boarded, eggs = boardedOf(st), 0, 0
    for _, e in ipairs(rec and rec.list or {}) do
      if e.uid then boarded = boarded + 1 end
    end
    for _, mon in ipairs(p) do
      if isEgg(mon) then eggs = eggs + 1 end
    end
    return {
      party = #p, known = known, gifts = #giftsOf(st), heals = st and st.heals or 0,
      held = type(battle) == "table" and battle.state ~= "done" and 1 or 0, landRolls = st and st.landRolls or 0,
      trades = counts.trades, tradeNoUid = counts.tradeNoUid, deposits = counts.deposits,
      withdraws = counts.withdraws, depositNoUid = counts.depositNoUid, opsPending = #opsOf(st),
      daycareHeld = boarded > 0, boarded = boarded, eggs = eggs, eggsBoxed = #boxedEggs(b.session()),
      eggGifts = counts.eggGifts, eggsParked = counts.eggsParked, eggsBack = counts.eggsBack,
      eggsLost = counts.eggsLost, hatched = counts.hatched,
    }
  end)

  -- ---- the daycare (two members, src/core/game3/daycare.lua:76-130)

  -- What the daycare holds now: { mon, steps } per slot in use, or nil without one. Daycare.stateOf makes the
  -- daycare's record on first use (src/core/game3/daycare.lua:53-83), as every step's Daycare.step does anyway
  -- (step_events.lua:355).
  local function boardedNow(s)
    local Daycare = b.g3("daycare")
    local ok, dc = b.call(Daycare, "stateOf", s)
    if not (ok and type(dc) == "table") then return nil end
    local out = {}
    for i = 1, tonumber(Daycare.DAYCARE_MON_COUNT) or Party3.DAYCARE_SLOTS do
      local okMon, mon = b.call(Daycare, "mon", dc, i)
      if okMon and type(mon) == "table" and (tonumber(mon.species or mon.speciesId) or 0) ~= 0 then
        out[#out + 1] = { mon = mon, steps = tonumber(type(dc.steps) == "table" and dc.steps[i]) or 0 }
      end
    end
    return out
  end

  -- The record kb_party keeps in mod.save: { boarded = { { uid, pid } } } (pid: the member's personality value),
  -- or false when the daycare holds nobody Cobblemon knows.
  local function storeOf(rec)
    local list = {}
    for _, e in ipairs(rec.list) do
      if e.uid then list[#list + 1] = { uid = e.uid, pid = e.pid } end
    end
    return #list > 0 and { boarded = list } or false
  end

  local function inParty(s, mon)
    for _, m in ipairs(type(s.party) == "table" and s.party or {}) do
      if m == mon then return true end
    end
    return false
  end

  -- One step of the daycare watch (kb_party's watchDaycare, as gen1.lua's engine.daycareStep). stored: the record
  -- kept in mod.save (storeOf's) or nil. Returns what happened, or nil; `store` is the record to keep from now:
  --   { kind = "deposit", uid, store }          a member with a uid went in (a daycare.deposit is queued)
  --   { kind = "withdraw", uid, steps, store }  it came back (daycare.withdraw queued, with the steps the
  --                                             daycare counted for it: the experience it was given)
  --   { kind = "adopted", count }               the members of a loaded save got their uids back from `stored`
  --   { kind = "unknown" }                      a member without a uid went in (or came with a loaded save)
  --   { kind = "cleared" | "lost", store }      one left with nothing to tell (no uid; not back in the party)
  def("daycareStep", function(stored)
    local s, st = b.session(), b.state()
    if not (st and type(s) == "table") then return nil end
    local now = boardedNow(s)
    if not now then return nil end
    local rec = boardedOf(st)
    if not rec then
      -- a new game, a CONTINUE or a new process: what the daycare holds gets its uids back from mod.save (part
      -- of the hidden save), matched by personality value
      rec = { session = s, list = {} }
      st.boarded = rec
      local saved = type(stored) == "table" and type(stored.boarded) == "table" and stored.boarded or {}
      local used, adopted = {}, 0
      for _, h in ipairs(now) do
        local uid
        for i, e in ipairs(saved) do
          if not used[i] and type(e) == "table" and type(e.uid) == "string" and e.pid == h.mon.personality then
            uid, used[i] = e.uid, true
            break
          end
        end
        if uid then
          monUid[h.mon] = uid
          adopted = adopted + 1
        end
        rec.list[#rec.list + 1] = { mon = h.mon, uid = uid, steps = h.steps, pid = h.mon.personality }
      end
      if adopted < #now then return { kind = "unknown" } end
      if adopted > 0 then return { kind = "adopted", count = adopted } end
      return nil
    end
    local here = {}
    for _, h in ipairs(now) do here[h.mon] = h end
    -- what left: back in the party it is a withdrawal (with the steps Daycare.withdraw was given, else the steps
    -- last counted for it), else it's gone
    for i, e in ipairs(rec.list) do
      local h = here[e.mon]
      if h then
        e.steps = h.steps
      else
        table.remove(rec.list, i)
        local given = type(st.withdrawn) == "table" and st.withdrawn[e.mon] or nil
        if given then st.withdrawn[e.mon] = nil end
        if not e.uid then return { kind = "cleared", store = storeOf(rec) } end
        if not inParty(s, e.mon) then return { kind = "lost", uid = e.uid, store = storeOf(rec) } end
        local steps = given or e.steps
        monUid[e.mon] = e.uid
        queueOp({ kind = "withdraw", uid = e.uid, steps = steps, mon = e.mon })
        counts.withdraws = counts.withdraws + 1
        return { kind = "withdraw", uid = e.uid, steps = steps, store = storeOf(rec) }
      end
    end
    -- what came in
    local known = {}
    for _, e in ipairs(rec.list) do known[e.mon] = true end
    for _, h in ipairs(now) do
      if not known[h.mon] then
        local uid = monUid[h.mon]
        rec.list[#rec.list + 1] = { mon = h.mon, uid = uid, steps = h.steps, pid = h.mon.personality }
        if not uid then
          counts.depositNoUid = counts.depositNoUid + 1
          return { kind = "unknown" }
        end
        queueOp({ kind = "deposit", uid = uid })
        counts.deposits = counts.deposits + 1
        return { kind = "deposit", uid = uid, store = storeOf(rec) }
      end
    end
    return nil
  end)

  -- The uids the daycare holds (save.loaded's daycare and boarded), in slot order as they went in.
  def("daycareUids", function()
    local out = {}
    local rec = boardedOf(b.state())
    for _, e in ipairs(rec and rec.list or {}) do
      if e.uid then out[#out + 1] = e.uid end
    end
    return out
  end)
  def("daycareUid", function()
    local rec = boardedOf(b.state())
    for _, e in ipairs(rec and rec.list or {}) do
      if e.uid then return e.uid end
    end
    return nil
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


  -- ---- hidden-only state (FOREIGN_POKEMON_PLAN §3.5, R3; FP6)
  --
  -- The fields the hidden game writes on a record that Cobblemon has no home for, per uid: kb_party takes them
  -- before every sync apply (partyStates), keeps them in mod.save ("uidState") and puts them back after it
  -- (restoreStates), so a rebuilt record (a member back from Cobblemon's PC or daycare, a swap between visitor and
  -- native) gets them again. A kept record gets its own values back.
  local function stateCopy(v, depth)
    if type(v) ~= "table" then return v end
    if (depth or 0) > 4 then return nil end
    local out = {}
    for k, x in pairs(v) do
      if type(k) == "string" or type(k) == "number" then out[k] = stateCopy(x, (depth or 0) + 1) end
    end
    return out
  end
  -- Emerald's: EVs, the contest condition and sheen, ribbons, Pokerus, and a held item Cobblemon can't hold
  -- (Mail, ...; put back only on a record holding nothing). Personality isn't kept: gender and shininess are
  -- Cobblemon's.
  local STATE_KEYS = { "evs", "contest", "sheen", "ribbons", "pokerus" }
  def("partyStates", function()
    local p = party()
    local out = {}
    for _, mon in ipairs(p or {}) do
      local uid = monUid[mon]
      if uid and mon.isEgg ~= true then
        local st = {}
        for _, k in ipairs(STATE_KEYS) do st[k] = stateCopy(mon[k]) end
        local held = mon.item or mon.heldItem
        if type(b.heldCanonical) == "function" and b.heldCanonical(held) == "hidden" then st.item = held end
        out[uid] = st
      end
    end
    return out
  end)
  def("restoreStates", function(states)
    if type(states) ~= "table" then return 0 end
    local n = 0
    for _, mon in ipairs(party() or {}) do
      local st = monUid[mon] and states[monUid[mon]] or nil
      if type(st) == "table" then
        for _, k in ipairs(STATE_KEYS) do
          if st[k] ~= nil then mon[k] = stateCopy(st[k]) end
        end
        local held = mon.item or mon.heldItem
        if st.item ~= nil and (held == nil or held == 0) then mon.item, mon.heldItem = st.item, st.item end
        n = n + 1
      end
    end
    return n
  end)

  -- ---- what kb_party watches (FP4: party.change, party.learn, party.forget)

  -- a record's status: the symbol, or the GBA bitfield (sleep turns in bits 0-2, then PSN BRN FRZ PAR TOX;
  -- item_use.lua:102-111 reads both)
  local FACT_STATUS = { SLP = "slp", PSN = "psn", BRN = "brn", FRZ = "frz", PAR = "par", TOX = "psn" }
  local function factStatus(mon)
    local st = mon.status
    if type(st) == "string" then return FACT_STATUS[st:upper()] or "none" end
    local n = floor(tonumber(st) or 0)
    if n % 8 > 0 or (tonumber(mon.sleep) or 0) > 0 then return "slp" end
    if floor(n / 8) % 2 == 1 or floor(n / 128) % 2 == 1 then return "psn" end
    if floor(n / 16) % 2 == 1 then return "brn" end
    if floor(n / 32) % 2 == 1 then return "frz" end
    if floor(n / 64) % 2 == 1 then return "par" end
    return "none"
  end
  def("partyFacts", function()
    local p, s = party()
    local out = {}
    for _, mon in ipairs(p or {}) do
      local uid = monUid[mon]
      if uid and mon.isEgg ~= true then
        local moves = {}
        for _, id in ipairs(type(mon.moves) == "table" and mon.moves or {}) do
          local n = tonumber(type(id) == "table" and id.id or id)
          if n and n >= 1 then moves[#moves + 1] = floor(n) end
        end
        local f = tonumber(mon.friendship or mon.happiness)
        local item = type(b.heldCanonical) == "function" and b.heldCanonical(mon.item or mon.heldItem) or false
        out[#out + 1] = { uid = uid, hp = tonumber(mon.hp), status = factStatus(mon),
                          friendship = f and floor(f) or nil, moves = moves, item = item }
      end
    end
    return out, s
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
