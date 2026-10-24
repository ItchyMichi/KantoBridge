-- gen2 party (GEN2_BRIDGE_PLAN.md §5.10, phase G2F): the hidden party is the
-- shadow of Cobblemon's, as on Gen 1 and 3 (gen1.lua's "shadow party",
-- game3/party.lua).
--
-- * Numbers: Gen 2 names species, moves and items by the disassembly's
--   constants (CYNDAQUIL, TACKLE, POTION); a species record's `dex` (= its
--   index, RomExtractorGen2.lua:1933) is its national number, a move's
--   `index` its number (1-251, :4612-4616).
-- * The party is save.party, Mon records (src/battle/gen2/Mon.lua:513-570).
--   applyParty rebuilds it in place from a party.sync: a known uid keeps its
--   record, a gift's entry takes the gift's record, anything else is made
--   with Mon.new and stamped with the player as trainer (Mon.stampOT,
--   :572-585); then species, level and experience, moves with their PP,
--   stats (Mon.stats, :180), HP scaled, status, nickname, held item and
--   friendship follow the entry. A new record's DVs reproduce what the entry
--   says of its gender, shininess and Unown letter (J12): Gen 2 reads all
--   three off the DVs (Mon.vanillaShiny :616-624, Gender.of, Unown.letterFromDVs).
-- * Gifts have no single seam in Gen 2 (eleven code paths append to the
--   party, research note g2_ui_battles_party.md §6.4), so the party is
--   diffed every step: a record that appears in the live save's party,
--   isn't an egg and carries no uid is a gift. Its source comes from the seam
--   that made it, where one is wrapped: `givepoke` (the script.command hook;
--   "starter" before the starter flag with no other member), an in-game trade
--   (NpcTrade.perform, src/core/gen2/NpcTrade.lua:140-174: the member sent goes
--   as party.take{reason:"trade"}), a hatched egg (Breeding.hatch,
--   src/core/gen2/Breeding.lua:1087-1148: source "egg", sent once the hatch and
--   its naming screen are done); anything else (the Bug-Catching Contest's
--   catch, Game Corner prizes, the Shuckle) is a "gift". A gift waits for the
--   script that gave it (its nickname comes from inside the op, Vm.lua:616-637).
-- * Eggs (giveegg, the Day-Care's, Crystal's Odd Egg): Cobblemon has no eggs,
--   so an egg stays in the hidden party (after Cobblemon's members) until it
--   hatches; it is never a gift.
-- * The Day-Care (two keepers, save.dayCare.man/.lady, Breeding.lua:686-704)
--   is watched every step, as Gen 1's and 3's: a member with a uid that a
--   keeper holds is daycare.deposit; back in the party (Breeding.withdraw
--   rebuilds the record, :864-918, wrapped so the uid follows it),
--   daycare.withdraw with the experience the Day-Care gave it (one per step).
-- * Heals: the nurse (saves.lua's scriptCommand) and blackouts.
-- * dex.sync: save.pokedex.seen / .caught by species id.
-- * Nicknames: renames in the hidden game go to Cobblemon.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Party2 = {}

Party2.MAX_MOVE = 251
Party2.HEAL_HOLD_STEPS = 600    -- a blackout's heal waits for its warp at most this long (gen1's)
Party2.PARTY_SIZE = 6
-- the starter's event flag (E26, G2-S4; the catalogue's starter marker)
Party2.STARTER_FLAG = 26
-- stack screens that hold party members (a sync waits while one is up)
Party2.PARTY_SCREENS = {
  Gen2PartyMenu = true, Gen2SummaryMenu = true, Gen2PackMenu = true, Gen2BoxMenu = true, Gen2PcMenu = true,
  Gen2DayCareMenu = true, Gen2TradeMenu = true, Gen2HeldItemMenu = true, Gen2MoveDeleter = true, Gen2MoveTutor = true,
  Gen2EvolutionAnim = true, Gen2EggHatchAnim = true, Gen2TradeAnim = true, Gen2NamingScreen = true,
  Gen2BattleState = true,
}
-- screens a hatched egg's gift waits for (the hatch, then its nickname)
Party2.HATCH_SCREENS = { Gen2EggHatchAnim = true, Gen2NamingScreen = true }
Party2.KEEPERS = { "man", "lady" }
-- the core's status words -> Gen 2's (Mon records, Battle.lua)
Party2.STATUS = { SLP = "sleep", PSN = "poison", BRN = "burn", FRZ = "freeze", PAR = "paralyze" }
Party2.GENDER_OUT = { male = "male", female = "female", unknown = "genderless" }
Party2.UNOWN = "UNOWN"          -- Unown.SPECIES (src/core/gen2/Unown.lua:29)
local unpack = table.unpack or unpack -- luacheck: ignore (LuaJIT's global)
local function pack(...) return { n = select("#", ...), ... } end

function Party2.install(b)
  local def = b.def
  local floor = math.floor

  local function data()
    local g = b.game
    return type(g) == "table" and type(g.data) == "table" and g.data or nil
  end
  local function Mon() return b.mod("src.battle.gen2.Mon") end

  -- ---- numbers (cached per data table: a new import is a new table)

  local index = setmetatable({}, { __mode = "k" })
  local function tables(d)
    local t = index[d]
    if t then return t end
    t = { speciesByDex = {}, moveByNum = {} }
    for id, rec in pairs(type(d.pokemon) == "table" and d.pokemon or {}) do
      local n = type(rec) == "table" and tonumber(rec.dex or rec.index) or nil
      if n and type(id) == "string" and not t.speciesByDex[n] then t.speciesByDex[n] = id end
    end
    for id, rec in pairs(type(d.moves) == "table" and d.moves or {}) do
      local n = type(rec) == "table" and tonumber(rec.index) or nil
      if n and type(id) == "string" and not t.moveByNum[n] then t.moveByNum[n] = id end
    end
    index[d] = t
    return t
  end

  local function dexOf(species)
    local d = data()
    local rec = d and type(d.pokemon) == "table" and d.pokemon[species] or nil
    local n = type(rec) == "table" and tonumber(rec.dex or rec.index) or nil
    return n and floor(n) or nil
  end
  local function moveNum(id)
    local d = data()
    local rec = d and type(d.moves) == "table" and d.moves[id] or nil
    local n = type(rec) == "table" and tonumber(rec.index) or nil
    return n and n >= 1 and n <= Party2.MAX_MOVE and floor(n) or nil
  end

  def("dexOf", dexOf)
  def("speciesOfDex", function(dex)
    local d = data()
    dex = tonumber(dex)
    if not (d and dex) then return nil end
    return tables(d).speciesByDex[floor(dex)]
  end)
  def("moveNumOf", moveNum)
  def("moveIdOfNum", function(n)
    local d = data()
    n = tonumber(n)
    if not (d and n) or n < 1 or n > Party2.MAX_MOVE then return nil end
    return tables(d).moveByNum[floor(n)]
  end)

  -- ---- the visitor stand-in (docs/specs/FOREIGN_POKEMON_PLAN.md §3, FP3)
  --
  -- KB_VISITOR, registered from the mod's entry chunk through the public
  -- registry in gs's shape (src/mods/Schemas.lua:1782-1824): dex 999 and no
  -- index, so the Pokedex never lists it and `checkpoke` never matches it
  -- (src/script/gen2/Vm.lua:632-638); no evolutions; egg groups EGG_NONE
  -- twice, the "No Eggs" group (src/core/gen2/Breeding.lua:101-105), so the
  -- Day-Care never makes an egg with it (Q10). Its party icon is a sheet of
  -- our own under an ICON_ name, the species mapped to it (:2902-2953). FP0
  -- ran it on Crystal (PROGRESS.md "Foreign Pokemon: spike FP0").
  --
  -- A record carries what the hidden game would otherwise rebuild from the
  -- species: kbStats (Cobblemon's six stats), kbName (a visitor's species
  -- name, upper case), kbTypes and kbGender (a visitor's). Mon.refreshStats
  -- (src/battle/gen2/Mon.lua:347-363: every party menu, summary and battle
  -- start) and Mon.syncIdentity (:219-241: name, types, gender, also on load)
  -- are wrapped once per process to put them back. Extra fields survive the
  -- save (FP0).
  local VISITOR = "KB_VISITOR"
  local visitorAccepted = false
  -- PartyMenu.FIELD_MOVES (src/ui/gen2/PartyMenu.lua:66-73), when the module isn't there
  local FIELD_MOVES = { "CUT", "FLY", "SURF", "STRENGTH", "FLASH", "WATERFALL", "WHIRLPOOL", "DIG", "TELEPORT",
                        "SOFTBOILED", "HEADBUTT", "ROCK_SMASH", "MILK_DRINK", "SWEET_SCENT" }
  -- Showdown's types as Gen 2's type ids; one Gen 2 lacks is CURSE_TYPE, which shows "???" (src/battle/TypeChart.lua:7)
  Party2.TYPES = { normal = "NORMAL", fire = "FIRE", water = "WATER", electric = "ELECTRIC", grass = "GRASS",
                   ice = "ICE", fighting = "FIGHTING", poison = "POISON", ground = "GROUND", flying = "FLYING",
                   psychic = "PSYCHIC_TYPE", bug = "BUG", rock = "ROCK", ghost = "GHOST", dragon = "DRAGON",
                   dark = "DARK", steel = "STEEL" }

  local function pinWrappers()
    local M = Mon()
    if type(M) ~= "table" or rawget(M, "kbPinned") then return end
    M.kbPinned = true
    local function repin(mon)
      if type(mon) ~= "table" then return end
      if type(mon.kbName) == "string" then mon.name = mon.kbName end
      if type(mon.kbTypes) == "table" then mon.types = mon.kbTypes end
      if type(mon.kbGender) == "string" then mon.gender = mon.kbGender end
      local p = mon.kbStats
      if type(p) == "table" then
        mon.stats = { hp = p.hp, attack = p.attack, defense = p.defense, speed = p.speed,
                      specialAttack = p.specialAttack, specialDefense = p.specialDefense }
        mon.maxHp = p.hp
        if type(mon.hp) == "number" and mon.hp > p.hp then mon.hp = p.hp end
      end
    end
    for _, name in ipairs({ "syncIdentity", "refreshStats" }) do
      local orig = M[name]
      if type(orig) == "function" then
        M[name] = function(mon, ...)
          local r = orig(mon, ...)
          repin(mon)
          return r
        end
      end
    end
  end

  -- §4.14: a cartridge export never carries a visitor (Gen2Save.encode,
  -- src/save_convert/Gen2Save.lua:2271, refuses a species with no index,
  -- :1080-1087): the export gets a copy without them (party, boxes, the
  -- Day-Care, the Hall of Fame's teams). One wrapper per process.
  local function isVisitor(mon) return type(mon) == "table" and mon.species == VISITOR end
  local function withoutVisitors(save)
    if type(save) ~= "table" then return save end
    local copy = {}
    for k, v in pairs(save) do copy[k] = v end
    local function list(mons)
      local out = {}
      for k, mon in pairs(type(mons) == "table" and mons or {}) do
        if type(k) ~= "number" then out[k] = mon elseif not isVisitor(mon) then out[#out + 1] = mon end
      end
      return out
    end
    copy.party = list(save.party)
    if type(save.boxes) == "table" then
      copy.boxes = {}
      for k, box in pairs(save.boxes) do copy.boxes[k] = type(box) == "table" and list(box) or box end
    end
    if type(save.dayCare) == "table" then
      copy.dayCare = {}
      for k, side in pairs(save.dayCare) do
        if type(side) == "table" and isVisitor(side.mon) then
          local c = {}
          for kk, vv in pairs(side) do c[kk] = vv end
          c.mon = nil
          copy.dayCare[k] = c
        else
          copy.dayCare[k] = side
        end
      end
    end
    local hof = save.hallOfFame
    if type(hof) == "table" and type(hof.teams) == "table" then
      copy.hallOfFame = {}
      for k, v in pairs(hof) do copy.hallOfFame[k] = v end
      copy.hallOfFame.teams = {}
      for i, team in ipairs(hof.teams) do
        local t = list(team)
        if type(team) == "table" and type(team.mons) == "table" then t.mons = list(team.mons) end
        copy.hallOfFame.teams[i] = t
      end
    end
    return copy
  end
  local function wrapExport()
    local G = b.mod("src.save_convert.Gen2Save")
    if type(G) ~= "table" or type(G.encode) ~= "function" or rawget(G, "kbVisitorEncode") then return end
    local encode = G.encode
    G.kbVisitorEncode = encode
    G.encode = function(save, ...) return encode(withoutVisitors(save), ...) end
  end

  def("registerVisitor", function(content, dir)
    local reg = type(content) == "table" and content.pokemon or nil
    if type(reg) ~= "table" or type(reg.register) ~= "function" then return false end
    local art = type(dir) == "string" and dir or ""
    local ok, res = pcall(reg.register, reg, VISITOR, {
      id = VISITOR, name = "VISITOR", dex = 999, types = { "NORMAL" },
      baseStats = { hp = 50, attack = 50, defense = 50, speed = 50, specialAttack = 50, specialDefense = 50 },
      catchRate = 0, baseExp = 0, growthRate = "MEDIUM_FAST", levelMoves = {}, evolutions = {},
      eggGroups = { "EGG_NONE", "EGG_NONE" }, eggGroupsRaw = 0xFF,
      spriteFront = art .. "/gs_front.png", spriteBack = art .. "/gs_back.png", picSize = 5,
    })
    visitorAccepted = ok and res ~= nil and res ~= false
    local icons = content.icons
    if visitorAccepted and type(icons) == "table" and type(icons.register) == "function" then
      -- a sheet of our own (16x32, two frames, as every vanilla sheet) and the species mapped onto it
      pcall(icons.register, icons, "ICON_KB_VISITOR", { image = art .. "/gs_icon.png", width = 16, height = 32, frames = 2 })
      pcall(icons.register, icons, VISITOR, "ICON_KB_VISITOR")
    end
    if visitorAccepted then
      pcall(pinWrappers)
      pcall(wrapExport)
    elseif b.log then
      b.log.warn("party.visitor_refused", { at = ok and "nil" or "threw" })
    end
    return visitorAccepted
  end)
  def("visitorSpecies", function()
    local d = data()
    if not (visitorAccepted and d and type(d.pokemon) == "table" and type(d.pokemon[VISITOR]) == "table") then return nil end
    pcall(pinWrappers) -- the Mon module once it is there (a no-op after the first time)
    return VISITOR
  end)
  -- R2/Q15: Gen 2's only forms are Unown's letters (a..z)
  def("formKnown", function(species, form)
    return form == nil or (species == Party2.UNOWN and type(form) == "string" and form:find("^%l$") ~= nil)
  end)
  def("visitorMoves", function()
    local menu = b.mod("src.ui.gen2.PartyMenu")
    local list = type(menu) == "table" and type(menu.FIELD_MOVES) == "table" and menu.FIELD_MOVES or FIELD_MOVES
    local set = {}
    for _, id in ipairs(list) do set[id] = true end
    return set
  end)

  -- ---- the party, its uids and what the diff has seen

  local monUid = setmetatable({}, { __mode = "k" })
  -- every record the diff has looked at: true, or "egg" while it is one
  local seen = setmetatable({}, { __mode = "k" })
  -- a record's source, set by the seam that made it (weak: gone with the record)
  local tagged = setmetatable({}, { __mode = "k" })
  local seenSave = nil

  local function party()
    local s = b.saveObject()
    return type(s) == "table" and type(s.party) == "table" and s.party or nil, s
  end

  local function isEgg(mon) return type(mon) == "table" and mon.isEgg == true end

  local function vmRunning()
    local w = b.ow()
    if not w then return false end
    local ok, running = b.method(w, "scriptRunning")
    if ok then return running == true end
    local vm = w.vm
    local okVm, v = b.method(vm, "running")
    return okVm and v == true
  end

  local function stackIds()
    local out = {}
    local g = b.game
    local stack = type(g) == "table" and g.stack or nil
    for _, s in ipairs(type(stack) == "table" and type(stack.states) == "table" and stack.states or {}) do
      if type(s) == "table" and type(s.screenId) == "string" then out[s.screenId] = true end
    end
    return out
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

  -- ---- party operations (kb_party's: takes, deposits, withdrawals), as game3/party.lua's

  local function opsOf(st)
    local s = b.saveObject()
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
    op.id, op.state, op.syncs, op.session = st.serial, "new", 0, b.saveObject()
    st.ops[#st.ops + 1] = op
    return op.id
  end

  -- FP5 (FOREIGN_POKEMON_PLAN §4.12): a contest catch Cobblemon holds is that uid's record (so it is never a gift),
  -- and the one the player let go at the contest's question leaves Cobblemon (party.take{reason:"contest"})
  def("tagUid", function(mon, uid)
    if type(mon) == "table" and type(uid) == "string" then monUid[mon] = uid end
  end)
  def("queueContestTake", function(uid)
    if type(uid) == "string" then return queueOp({ kind = "take", uid = uid, reason = "contest" }) end
  end)

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

  -- The Day-Care watch's record for this save ({ session, list = { { mon, uid, exp0, keeper } } }), or nil.
  local function boardedOf(st)
    local rec = type(st) == "table" and st.boarded or nil
    if type(rec) == "table" and rec.session == b.saveObject() then return rec end
    return nil
  end

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

  -- trades, the Day-Care and eggs (bridgeCounts; the logs carry these counts only)
  local counts = { trades = 0, tradeNoUid = 0, deposits = 0, withdraws = 0, depositNoUid = 0, eggsLost = 0,
                   hatched = 0, gifts = 0 }

  -- ---- gifts

  local function giftsOf(st)
    local s = b.saveObject()
    local list = type(st) == "table" and type(st.gifts) == "table" and st.gifts or {}
    for i = #list, 1, -1 do
      if list[i].session ~= s then table.remove(list, i) end -- a new game or CONTINUE
    end
    return list
  end

  local function addGift(st, s, mon, source, extra)
    st.serial = st.serial + 1
    local g = {
      give = ("g-%s-%d"):format(st.token or "x", st.serial), serial = st.serial, state = "new", syncs = 0,
      box = false, source = source, session = s, mon = mon, waitScript = false,
    }
    for k, v in pairs(extra or {}) do g[k] = v end
    st.gifts[#st.gifts + 1] = g
    counts.gifts = counts.gifts + 1
    return g
  end

  -- The party diff: a record new to the live save's party that isn't an egg and has no uid is a gift (its
  -- source from the seam that made it, else "gift"); an egg that turns into a Pokemon in its own record is one
  -- too. The first look at a save takes what it has as known.
  local function scan()
    local p, s = party()
    local st = b.state()
    if not (p and st) then return end
    if s ~= seenSave then
      seenSave = s
      for _, mon in ipairs(p) do seen[mon] = isEgg(mon) and "egg" or true end
      return
    end
    for _, mon in ipairs(p) do
      local was = seen[mon]
      if was == nil then
        if isEgg(mon) then
          seen[mon] = "egg"
        else
          seen[mon] = true
          if monUid[mon] == nil then
            -- the seam that made it: its own tag, else the `givepoke` still running (its nickname prompt yields the
            -- script inside the op, so the record is in the party before the op returns)
            local tag = tagged[mon]
            local giving = st.givingSeam
            if tag == nil and type(giving) == "table" then
              if giving.save == s and vmRunning() then
                tag = { source = giving.source, waitScript = true }
                tagged[mon] = tag
              else
                st.givingSeam = nil
              end
            end
            local source = type(tag) == "table" and tag.source or "gift"
            addGift(st, s, mon, source, {
              waitScript = type(tag) == "table" and tag.waitScript == true or false,
              waitHatch = source == "egg",
            })
          end
        end
      elseif was == "egg" and not isEgg(mon) then
        seen[mon] = true
        if monUid[mon] == nil then addGift(st, s, mon, "egg", { waitHatch = true }) end
      end
    end
  end

  -- A gift's facts as the core's party.give wants them, read now (the nickname may come after the give).
  local function giftFacts(mon)
    if type(mon) ~= "table" then return nil end
    local dex = dexOf(mon.species)
    if not dex then return nil end
    local moves = {}
    for _, mv in ipairs(type(mon.moves) == "table" and mon.moves or {}) do
      local n = moveNum(type(mv) == "table" and mv.id or mv)
      if n and #moves < 4 then moves[#moves + 1] = n end
    end
    local d = type(mon.dvs) == "table" and mon.dvs or {}
    local dvs = { atk = tonumber(d.attack), def = tonumber(d.defense), spd = tonumber(d.speed), spc = tonumber(d.special) }
    for k, v in pairs(dvs) do dvs[k] = math.max(0, math.min(15, floor(v))) end
    local nick = type(mon.nickname) == "string" and mon.nickname ~= "" and mon.nickname or nil
    local ot = type(mon.otName) == "string" and mon.otName or (type(mon.ot) == "string" and mon.ot or nil)
    local out = { dex = dex, level = tonumber(mon.level), moves = moves, dvs = dvs, nick = nick, ot = ot }
    out.item = b.canonicalItem(mon.item)
    if type(mon.shiny) == "boolean" then out.shiny = mon.shiny end
    out.gender = Party2.GENDER_OUT[mon.gender]
    -- Unown's letter, 1-26 (Unown.letterFromDVs, src/core/gen2/Unown.lua:74-81) -> "a".."z"
    local letter = tonumber(mon.unownLetter)
    if mon.species == Party2.UNOWN and letter and letter >= 1 and letter <= 26 then
      out.form = string.char(96 + floor(letter))
    end
    if tonumber(mon.happiness) then out.friendship = math.max(0, math.min(255, floor(mon.happiness))) end
    return out
  end

  def("gifts", function()
    scan()
    local st = b.state()
    local out = {}
    local holding = type(b.heldBattle) == "function" and b.heldBattle()
    local scriptBusy = vmRunning()
    local screens = stackIds()
    local hatching = false
    for id in pairs(Party2.HATCH_SCREENS) do
      if screens[id] then hatching = true end
    end
    for _, g in ipairs(giftsOf(st)) do
      if g.waitHatch and not hatching then g.waitHatch = false end
      -- a new gift waits for the script that gave it (its nickname comes later), unless a battle is held now; a
      -- hatched egg for its screens (the nickname prompt)
      if g.waitHatch then
        -- not yet
      elseif g.state ~= "new" or not g.waitScript or not scriptBusy or holding then
        g.waitScript = false
        local f = giftFacts(g.mon)
        if f then
          out[#out + 1] = {
            give = g.give, serial = g.serial, state = g.state, sentGen = g.sentGen, syncs = g.syncs, box = g.box,
            source = g.source, dex = f.dex, level = f.level, moves = f.moves, dvs = f.dvs, nick = f.nick, ot = f.ot,
            item = f.item, shiny = f.shiny, gender = f.gender, form = f.form, friendship = f.friendship,
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
    if g then g.state, g.sentGen = "sent", generation end
  end)
  def("giftSynced", function(give)
    local g = giftNamed(give)
    if g then g.syncs = g.syncs + 1 end
  end)
  def("giftDone", function(give)
    local g, i, st = giftNamed(give)
    if g then table.remove(st.gifts, i) end
  end)

  -- Native catches: the Bug-Catching Contest's catch reaches the party at the contest's end (BugContest.lua:654,
  -- :801), which the diff sees; the catch tutorial's is the Dude's. Nothing to record here.
  def("nativeCaught", function() return false end)
  def("releaseCatches", function() return 0 end)

  -- ---- the seams that tell a gift's source

  -- The script.command hook (saves.lua's, which arms the nurse, runs under it): `givepoke` gives a gift (the
  -- starter before the starter flag with no other member), its records tagged once the op returns.
  local prevCommand = b.adapter.scriptCommand
  def("scriptCommand", function(nextFn, ctx, name, args, bridge, cmd)
    if name ~= "givepoke" then return prevCommand(nextFn, ctx, name, args, bridge, cmd) end
    scan()
    local p = party() or {}
    local members = 0
    for _, mon in ipairs(p) do
      if not isEgg(mon) then members = members + 1 end
    end
    local source = (members == 0 and not b.flag(Party2.STARTER_FLAG)) and "starter" or "gift"
    local before = {}
    for _, mon in ipairs(p) do before[mon] = true end
    local st = b.state()
    local outer = st and st.givingSeam
    if st then st.givingSeam = { source = source, save = b.saveObject() } end
    local out = pack(pcall(prevCommand, nextFn, ctx, name, args, bridge, cmd))
    if st then st.givingSeam = outer end
    for _, mon in ipairs(party() or {}) do
      if not before[mon] and tagged[mon] == nil then tagged[mon] = { source = source, waitScript = true } end
    end
    if not out[1] then error(out[2], 0) end
    return unpack(out, 2, out.n)
  end)

  -- One wrapper per process on an engine table's function; the handler is this instance's (a hot reload
  -- replaces it).
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

  -- NpcTrade.perform(data, save, row, index) -> (given, received): on the live save the member sent is a take by
  -- its uid, the one received a gift with source "trade".
  local function trade(original, d, save, row, idx)
    local given, received = original(d, save, row, idx)
    local s, st = b.saveObject(), b.state()
    if type(received) == "table" and type(given) == "table" and st and save == s then
      counts.trades = counts.trades + 1
      local uid = monUid[given]
      if uid then
        queueOp({ kind = "take", uid = uid, reason = "trade" })
      else
        for i, g in ipairs(type(st.gifts) == "table" and st.gifts or {}) do
          if g.mon == given and g.state == "new" then table.remove(st.gifts, i) break end
        end
        counts.tradeNoUid = counts.tradeNoUid + 1
        if b.log then b.log.warn("engine.trade_no_uid", { count = counts.tradeNoUid }) end
      end
      tagged[received] = { source = "trade", waitScript = vmRunning() }
      if b.log then b.log.info("engine.trade", { count = counts.trades }) end
    end
    return given, received
  end

  -- Breeding.hatch(data, save, index, nickname, where) -> the hatchling in the egg's slot: a gift, source "egg".
  local function hatch(original, d, save, idx, nickname, where)
    local hatched, effects = original(d, save, idx, nickname, where)
    if type(hatched) == "table" and save == b.saveObject() then
      tagged[hatched] = { source = "egg" }
      counts.hatched = counts.hatched + 1
      if b.log then b.log.info("engine.egg_hatched", { count = counts.hatched }) end
    end
    return hatched, effects
  end

  -- Breeding.withdraw(data, save, which) -> (ok, rebuilt, price): the record comes back rebuilt, so the uid of the
  -- one the keeper held moves to it, with the experience the Day-Care gave (the watch reports the withdrawal).
  local function withdraw(original, d, save, which)
    local side = save and type(save.dayCare) == "table" and save.dayCare[which == "lady" and "lady" or "man"] or nil
    local held = type(side) == "table" and side.mon or nil
    local ok, rebuilt, price = original(d, save, which)
    local st = b.state()
    if ok and type(rebuilt) == "table" and type(held) == "table" and st and save == b.saveObject() then
      st.withdrawn = type(st.withdrawn) == "table" and st.withdrawn or setmetatable({}, { __mode = "k" })
      st.withdrawn[held] = { rebuilt = rebuilt, exp = tonumber(held.experience) }
      seen[rebuilt] = true
      if monUid[held] then monUid[rebuilt] = monUid[held] end
    end
    return ok, rebuilt, price
  end

  -- Installed with the bridge (battle.lua's attachBridge): the trade, hatch and withdrawal seams, once per process.
  -- FP6 (FOREIGN_POKEMON_PLAN §4.9): quests that take a Pokemon away. Kenya's delivery (Mail.checkPokeMail removes
  -- the member on CORRECT, src/core/gen2/Mail.lua:394-420) and Mania taking Shuckie back (the ReturnShuckie special,
  -- src/script/gen2/Specials.lua:1419-1440) leave Cobblemon too: party.take{reason:"quest"}, so the next sync never
  -- puts them back. Both see a member by its record, so only natives reach them (a visitor isn't that species).
  local function questTakes(before)
    local now = {}
    for _, mon in ipairs(party() or {}) do now[mon] = true end
    for mon, uid in pairs(before) do
      if not now[mon] then
        queueOp({ kind = "take", uid = uid, reason = "quest" })
        counts.questTakes = (counts.questTakes or 0) + 1
      end
    end
  end
  local function uidsNow()
    local out = {}
    for _, mon in ipairs(party() or {}) do
      if monUid[mon] then out[mon] = monUid[mon] end
    end
    return out
  end
  local function checkPokeMail(original, save, slot, expected, ...)
    local before = uidsNow()
    local out = { pcall(original, save, slot, expected, ...) }
    if not out[1] then error(out[2], 0) end
    questTakes(before)
    return unpack(out, 2, table.maxn(out))
  end
  local function returnShuckie(original, vm, ...)
    local before = uidsNow()
    local out = { pcall(original, vm, ...) }
    if not out[1] then error(out[2], 0) end
    questTakes(before)
    return unpack(out, 2, table.maxn(out))
  end

  -- FP6 (§4.9): the Pokedex never counts the stand-in (save.pokedex.seen/caught by species id, Save.lua:1007)
  def("dexSweep", function()
    local s = b.saveObject()
    local dex = type(s) == "table" and type(s.pokedex) == "table" and s.pokedex or nil
    if not dex then return 0 end
    local n = 0
    for _, k in ipairs({ "seen", "caught", "owned" }) do
      if type(dex[k]) == "table" and dex[k][VISITOR] then
        dex[k][VISITOR] = nil
        n = n + 1
      end
    end
    return n
  end)

  function b.installGifts()
    wrap(b.c2("NpcTrade"), "perform", trade)
    wrap(b.c2("Breeding"), "hatch", hatch)
    wrap(b.c2("Breeding"), "withdraw", withdraw)
    wrap(b.c2("Mail"), "checkPokeMail", checkPokeMail)
    local Specials = b.mod("src.script.gen2.Specials")
    wrap(type(Specials) == "table" and Specials.ALL or nil, "ReturnShuckie", returnShuckie)
  end

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
      if st.healHold <= Party2.HEAL_HOLD_STEPS then return 0 end
      st.healHold = nil
    end
    local n = st.heals or 0
    st.heals = 0
    return n
  end)

  -- ---- the shadow party

  def("partySafe", function()
    if not b.inField() then return false end
    -- FP5: the Bug-Catching Contest keeps only the lead in the party (BugContest.dropOffMons, the rest stashed until
    -- returnMons): a sync waits for its end
    if b.ask(b.c2("BugContest"), "isActive", false, b.saveObject()) == true then return false end
    local w = b.ow()
    if w and w.battleActive then return false end
    if type(b.heldBattle) == "function" and b.heldBattle() then return false end
    local screens = stackIds()
    for id in pairs(Party2.PARTY_SCREENS) do
      if screens[id] then return false end
    end
    scan()
    for _, g in ipairs(giftsOf(b.state())) do
      if g.state == "new" and g.waitScript and vmRunning() then return false end
    end
    return true
  end)

  -- DVs for a new record that reproduce what the entry says (J12): shininess (Gen 2's pattern: Defense, Speed
  -- and Special 10, Attack 2, 3, 6, 7, 10, 11, 14 or 15), the gender (Gender.of: Attack x 16 + Speed against the
  -- species' ratio), Unown's letter (Unown.letterFromDVs). The nearest set to `base` that fits; what can't fit
  -- together (a shiny female of a mostly male species) gives way, the letter first, then the gender.
  local function dvsFor(species, def_, e, base)
    local M = Mon()
    local Gender, Unown = b.c2("Gender"), b.c2("Unown")
    local shinyOf = type(M) == "table" and M.vanillaShiny or nil
    local genderOf = type(Gender) == "table" and Gender.of or nil
    local letterOf = type(Unown) == "table" and Unown.letterFromDVs or nil
    local ratio = type(def_) == "table" and def_.genderRatio or nil
    local wantShiny = type(shinyOf) == "function" and e.shiny or nil
    local wantGender = type(genderOf) == "function" and (e.gender == "genderless" and "unknown" or e.gender) or nil
    local wantLetter = nil
    if species == Party2.UNOWN and type(letterOf) == "function" and type(e.form) == "string" and e.form:find("^%l$") then
      wantLetter = e.form:byte() - 96
    end
    if wantShiny == nil and wantGender == nil and wantLetter == nil then return base end
    local ok, found = pcall(function()
      for _, pass in ipairs({ { true, true }, { false, true }, { false, false } }) do
        local best, bestScore
        local dv = {}
        for atk = 0, 15 do
          for dfn = 0, 15 do
            for spd = 0, 15 do
              for spc = 0, 15 do
                dv.attack, dv.defense, dv.speed, dv.special = atk, dfn, spd, spc
                local fits = (wantShiny == nil or (shinyOf(dv) == true) == wantShiny)
                  and (not pass[2] or wantGender == nil or genderOf(ratio, dv) == wantGender)
                  and (not pass[1] or wantLetter == nil or letterOf(dv) == wantLetter)
                if fits then
                  local sc = math.abs((base.attack or 0) - atk) + math.abs((base.defense or 0) - dfn)
                    + math.abs((base.speed or 0) - spd) + math.abs((base.special or 0) - spc)
                  if not bestScore or sc < bestScore then
                    best, bestScore = { attack = atk, defense = dfn, speed = spd, special = spc }, sc
                  end
                end
              end
            end
          end
        end
        if best then return best end
      end
      return nil
    end)
    return ok and found or base
  end

  -- Brings one record in line with a sync entry.
  local function updateMon(mon, e, d)
    local M = Mon()
    local def_ = type(d.pokemon) == "table" and d.pokemon[e.species] or nil
    if mon.species ~= e.species or mon.level ~= e.level then
      mon.species, mon.level = e.species, e.level
      if type(def_) == "table" then
        mon.name = def_.name or e.species
        mon.types = def_.types
        local okG, growth = b.call(M, "growthFor", d, def_.growthRate)
        local okE, exp = b.call(M, "experienceForLevel", okG and growth or nil, e.level)
        if okE and tonumber(exp) then mon.experience = exp end
      end
    end
    if #e.moves > 0 then
      local oldPp = {}
      for _, mv in ipairs(type(mon.moves) == "table" and mon.moves or {}) do
        if type(mv) == "table" and mv.id then oldPp[mv.id] = { pp = mv.pp, ppUps = mv.ppUps } end
      end
      local moves = {}
      for i, id in ipairs(e.moves) do
        local mdef = type(d.moves) == "table" and d.moves[id] or nil
        local full = type(mdef) == "table" and tonumber(mdef.pp) or 0
        local old = oldPp[id]
        moves[i] = { id = id, pp = math.min(full, old and tonumber(old.pp) or full), maxPp = full,
                     ppUps = old and old.ppUps or nil }
      end
      mon.moves = moves
    end
    if type(def_) == "table" and type(def_.baseStats) == "table" then
      local ok, stats = b.call(M, "stats", def_.baseStats, mon.dvs, mon.level, mon.statExp)
      if ok and type(stats) == "table" then
        mon.stats = stats
        mon.maxHp = stats.hp
      end
    end
    local maxHp = tonumber(mon.maxHp) or 1
    if e.hp <= 0 then
      mon.hp = 0
    else
      mon.hp = math.max(1, math.min(maxHp, floor(e.hp * maxHp / e.maxhp + 0.5)))
    end
    mon.status = Party2.STATUS[e.status]
    mon.statusTurns = mon.status == "sleep" and (tonumber(mon.statusTurns) or 1) or nil
    mon.nickname = type(e.nick) == "string" and e.nick ~= "" and e.nick or nil
    -- (MG9 G2F) the held item and friendship Cobblemon's member has: an entry with details (a client that sends them
    -- always says whether it is shiny) sets the item, none when it names none or one this game lacks
    if e.item ~= nil or e.shiny ~= nil then
      -- FP4 (§5): a hidden-only item (Mail, ...) stays while Cobblemon's member holds nothing; FP6 (§3.5): one that
      -- Cobblemon's item replaces goes back to the hidden bag
      local hidden = mon.item ~= nil and mon.item ~= "" and b.heldHiddenOnly(mon.item)
      if not (e.item == nil and hidden) then
        local next = b.itemSymbol(e.item)
        if hidden and next ~= mon.item and type(b.bagReturn) == "function" then b.bagReturn(mon.item) end
        mon.item = next
      end
    end
    if tonumber(e.friendship) then mon.happiness = floor(e.friendship) end
    -- FP3 (§3.1, Q20): Cobblemon's stats on every record that has them; a visitor's name, types and gender
    if type(e.stats) == "table" then
      local p = e.stats
      mon.kbStats = { hp = p.hp, attack = p.atk, defense = p.def, speed = p.spe, specialAttack = p.spa, specialDefense = p.spd }
      mon.stats = { hp = p.hp, attack = p.atk, defense = p.def, speed = p.spe, specialAttack = p.spa, specialDefense = p.spd }
      mon.maxHp = p.hp
      mon.hp = e.hp <= 0 and 0 or math.max(1, math.min(p.hp, e.hp))
    else
      mon.kbStats = nil
    end
    if e.visitor then
      mon.kbName = type(e.name) == "string" and e.name ~= "" and e.name:upper() or nil
      if mon.kbName then mon.name = mon.kbName end
      local types = {}
      for _, t in ipairs(type(e.types) == "table" and e.types or {}) do types[#types + 1] = Party2.TYPES[t] or "CURSE_TYPE" end
      mon.kbTypes = #types > 0 and types or nil
      if mon.kbTypes then mon.types = mon.kbTypes end
      mon.kbGender = e.gender == "genderless" and "unknown" or e.gender
      if mon.kbGender then mon.gender = mon.kbGender end
    else
      mon.kbName, mon.kbTypes, mon.kbGender = nil, nil, nil
    end
    -- FP4 (§4.4): the stones Cobblemon would take, as Gen 2 item symbols
    if type(e.stones) == "table" then
      local set = {}
      for _, id in ipairs(e.stones) do
        local sym = b.itemSymbol(id)
        if sym then set[sym] = true end
      end
      mon.kbStones = set
    else
      mon.kbStones = nil
    end
    -- FP4: the machine moves Cobblemon's learnset allows (by Gen 2 move id)
    if type(e.canLearn) == "table" then
      local set = {}
      for _, n in ipairs(e.canLearn) do
        local id = n >= 1 and n <= Party2.MAX_MOVE and tables(d).moveByNum[n] or nil
        if id then set[id] = true end
      end
      mon.kbCanLearn = set
    else
      mon.kbCanLearn = nil
    end
  end

  -- A new record: Mon.new with DVs that reproduce the entry's gender, shininess and letter, stamped as the player's.
  local function fresh(s, d, e)
    local M = Mon()
    local def_ = type(d.pokemon) == "table" and d.pokemon[e.species] or nil
    if type(def_) ~= "table" then return nil end
    local okR, base = b.call(M, "randomDVs")
    base = okR and type(base) == "table" and base or { attack = 8, defense = 8, speed = 8, special = 8 }
    local dvs = dvsFor(e.species, def_, e, base)
    local ok, mon = b.call(M, "new", d, e.species, e.level, { dvs = { attack = dvs.attack, defense = dvs.defense,
                                                                     speed = dvs.speed, special = dvs.special },
                                                            item = b.itemSymbol(e.item), happiness = e.friendship })
    if not (ok and type(mon) == "table") then return nil end
    if e.shiny ~= nil then mon.shiny = e.shiny end
    b.call(M, "stampOT", s, mon)
    return mon
  end

  def("applyParty", function(entries)
    local p, s = party()
    local st = b.state()
    local d = data()
    if not (p and st and d and type(entries) == "table") then return nil end
    scan()
    local byUid = {}
    for _, mon in ipairs(p) do
      local uid = monUid[mon]
      if uid then byUid[uid] = mon end
    end
    local res = { created = 0, updated = 0, matched = 0, kept = 0, failed = 0 }
    local out, kept = {}, {}
    for _, e in ipairs(entries) do
      local mon, how = byUid[e.uid], "updated"
      if e.give ~= nil then
        local g, i = giftNamed(e.give)
        if mon == nil and g and type(g.mon) == "table" and g.session == s then mon, how = g.mon, "matched" end
        if i then table.remove(st.gifts, i) end
      end
      if mon == nil or kept[mon] then
        mon = fresh(s, d, e)
        how = mon and "created" or "failed"
      end
      res[how] = res[how] + 1
      if mon then
        local ok = pcall(updateMon, mon, e, d)
        if not ok then res.failed = res.failed + 1 end
        monUid[mon] = e.uid
        seen[mon] = true
        kept[mon] = true
        out[#out + 1] = mon
      end
    end
    -- gifts Cobblemon hasn't shown yet stay
    for _, g in ipairs(giftsOf(st)) do
      if type(g.mon) == "table" and not kept[g.mon] and #out < Party2.PARTY_SIZE then
        out[#out + 1] = g.mon
        kept[g.mon] = true
        res.kept = res.kept + 1
      end
    end
    -- a member back from the Day-Care that Cobblemon hasn't shown yet stays too (its withdrawal is pending)
    for _, op in ipairs(opsOf(st)) do
      if op.kind == "withdraw" and type(op.mon) == "table" and not kept[op.mon] and #out < Party2.PARTY_SIZE then
        out[#out + 1] = op.mon
        kept[op.mon] = true
        res.kept = res.kept + 1
      end
    end
    -- eggs live in the hidden game only: after Cobblemon's members, in a hidden box while the party has no room
    -- (the current box, Boxes.box, src/core/gen2/Boxes.lua:49-56), and back from the boxes once it has
    res.eggs, res.parked, res.back = 0, 0, 0
    local Boxes = b.c2("Boxes")
    local perBox = type(Boxes) == "table" and tonumber(Boxes.MONS_PER_BOX) or 20
    for _, mon in ipairs(p) do
      if not kept[mon] and isEgg(mon) then
        kept[mon] = true
        if #out < Party2.PARTY_SIZE then
          out[#out + 1] = mon
          res.eggs = res.eggs + 1
        else
          local okBox, box = b.call(Boxes, "box", s, nil)
          if okBox and type(box) == "table" and #box < perBox then
            box[#box + 1] = mon
            res.parked = res.parked + 1
          else
            counts.eggsLost = counts.eggsLost + 1
            if b.log then b.log.warn("engine.egg_no_room", { count = counts.eggsLost }) end
          end
        end
      end
    end
    if #out < Party2.PARTY_SIZE then
      for _, box in pairs(type(s.boxes) == "table" and s.boxes or {}) do
        for i = #(type(box) == "table" and box or {}), 1, -1 do
          if #out < Party2.PARTY_SIZE and isEgg(box[i]) and not kept[box[i]] then
            local egg = table.remove(box, i)
            kept[egg] = true
            out[#out + 1] = egg
            res.eggs, res.back = res.eggs + 1, res.back + 1
          end
        end
      end
    end
    for i = #p, 1, -1 do p[i] = nil end
    for i, mon in ipairs(out) do p[i] = mon end
    res.size = #p
    b.dropView()
    return res
  end)

  def("bridgeCounts", function()
    local st = b.state()
    local p = party() or {}
    local known, eggs = 0, 0
    for _, mon in ipairs(p) do
      if monUid[mon] then known = known + 1 end
      if isEgg(mon) then eggs = eggs + 1 end
    end
    local battle = st and st.battle
    local rec, boarded = boardedOf(st), 0
    for _, e in ipairs(rec and rec.list or {}) do
      if e.uid then boarded = boarded + 1 end
    end
    return {
      party = #p, known = known, gifts = #giftsOf(st), heals = st and st.heals or 0,
      held = type(battle) == "table" and battle.state ~= "done" and 1 or 0, landRolls = st and st.landRolls or 0,
      trades = counts.trades, tradeNoUid = counts.tradeNoUid, deposits = counts.deposits,
      withdraws = counts.withdraws, depositNoUid = counts.depositNoUid, opsPending = #opsOf(st),
      daycareHeld = boarded > 0, boarded = boarded, eggs = eggs, eggsLost = counts.eggsLost,
      hatched = counts.hatched, giftsSeen = counts.gifts,
    }
  end)

  -- ---- the Day-Care (two keepers)

  -- What the keepers hold now: { mon, keeper } each, or nil without a save.
  local function boardedNow(s)
    if type(s) ~= "table" then return nil end
    local dc = type(s.dayCare) == "table" and s.dayCare or {}
    local out = {}
    for _, keeper in ipairs(Party2.KEEPERS) do
      local side = dc[keeper]
      local mon = type(side) == "table" and side.mon or nil
      if type(mon) == "table" then out[#out + 1] = { mon = mon, keeper = keeper } end
    end
    return out
  end

  -- The record kept in mod.save: { boarded = { { uid, keeper } } }, or false when nobody Cobblemon knows is there.
  local function storeOf(rec)
    local list = {}
    for _, e in ipairs(rec.list) do
      if e.uid then list[#list + 1] = { uid = e.uid, keeper = e.keeper } end
    end
    return #list > 0 and { boarded = list } or false
  end

  local function inParty(s, mon)
    for _, m in ipairs(type(s.party) == "table" and s.party or {}) do
      if m == mon then return true end
    end
    return false
  end

  -- One step of the Day-Care watch (kb_party's watchDaycare), as game3/party.lua's: deposit, withdraw (with the
  -- experience the Day-Care gave it, its "steps"), adopted, unknown, cleared or lost.
  def("daycareStep", function(stored)
    local s, st = b.saveObject(), b.state()
    if not (st and type(s) == "table") then return nil end
    local now = boardedNow(s)
    if not now then return nil end
    local rec = boardedOf(st)
    if not rec then
      -- a new game, a CONTINUE or a new process: what the keepers hold gets its uids back from mod.save
      rec = { session = s, list = {} }
      st.boarded = rec
      local saved = type(stored) == "table" and type(stored.boarded) == "table" and stored.boarded or {}
      local adopted = 0
      for _, h in ipairs(now) do
        local uid
        for _, e in ipairs(saved) do
          if type(e) == "table" and type(e.uid) == "string" and e.keeper == h.keeper then uid = e.uid break end
        end
        if uid then
          monUid[h.mon] = uid
          adopted = adopted + 1
        end
        rec.list[#rec.list + 1] = { mon = h.mon, uid = uid, keeper = h.keeper, exp0 = tonumber(h.mon.experience) }
      end
      if adopted < #now then return { kind = "unknown" } end
      if adopted > 0 then return { kind = "adopted", count = adopted } end
      return nil
    end
    local here = {}
    for _, h in ipairs(now) do here[h.mon] = h end
    for i, e in ipairs(rec.list) do
      if not here[e.mon] then
        table.remove(rec.list, i)
        local back = type(st.withdrawn) == "table" and st.withdrawn[e.mon] or nil
        if back then st.withdrawn[e.mon] = nil end
        if not e.uid then return { kind = "cleared", store = storeOf(rec) } end
        local mon = back and back.rebuilt or e.mon
        if not inParty(s, mon) then return { kind = "lost", uid = e.uid, store = storeOf(rec) } end
        local exp = back and back.exp or tonumber(e.mon.experience)
        local steps = math.max(0, floor((exp or 0) - (e.exp0 or exp or 0)))
        monUid[mon] = e.uid
        seen[mon] = true
        queueOp({ kind = "withdraw", uid = e.uid, steps = steps, mon = mon })
        counts.withdraws = counts.withdraws + 1
        return { kind = "withdraw", uid = e.uid, steps = steps, store = storeOf(rec) }
      end
    end
    local known = {}
    for _, e in ipairs(rec.list) do known[e.mon] = true end
    for _, h in ipairs(now) do
      if not known[h.mon] then
        local uid = monUid[h.mon]
        rec.list[#rec.list + 1] = { mon = h.mon, uid = uid, keeper = h.keeper, exp0 = tonumber(h.mon.experience) }
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

  def("applyDex", function(seenList, owned)
    local s = b.saveObject()
    local d = data()
    if not (type(s) == "table" and d) then return nil end
    s.pokedex = type(s.pokedex) == "table" and s.pokedex or {}
    s.pokedex.seen = type(s.pokedex.seen) == "table" and s.pokedex.seen or {}
    s.pokedex.caught = type(s.pokedex.caught) == "table" and s.pokedex.caught or {}
    local t = tables(d)
    local res = { seen = 0, owned = 0, unknown = 0 }
    local function each(list, field, key)
      for _, n in ipairs(type(list) == "table" and list or {}) do
        local sp = t.speciesByDex[tonumber(n) or -1]
        if sp then
          if not s.pokedex[field][sp] then
            s.pokedex[field][sp] = true
            res[key] = res[key] + 1
          end
        else
          res.unknown = res.unknown + 1
        end
      end
    end
    each(seenList, "seen", "seen")
    each(owned, "caught", "owned")
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
  -- Crystal's: stat exp, Pokerus, the caught data (src/battle/gen2/Mon.lua), and a held item Cobblemon can't hold
  -- (Mail, ...; put back only on a record holding nothing)
  local STATE_KEYS = { "statExp", "pokerus", "caughtTime", "caughtLevel", "caughtLocation", "caughtByGender" }
  def("partyStates", function()
    local p = party()
    local out = {}
    for _, mon in ipairs(p or {}) do
      local uid = monUid[mon]
      if uid and mon.isEgg ~= true then
        local st = {}
        for _, k in ipairs(STATE_KEYS) do st[k] = stateCopy(mon[k]) end
        if type(mon.item) == "string" and b.heldHiddenOnly(mon.item) then st.item = mon.item end
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
        if type(st.item) == "string" and (mon.item == nil or mon.item == "") then mon.item = st.item end
        n = n + 1
      end
    end
    return n
  end)

  -- ---- what kb_party watches (FP4: party.change, party.learn, party.forget)

  local FACT_STATUS = { sleep = "slp", poison = "psn", toxic = "psn", burn = "brn", freeze = "frz", paralyze = "par" }
  def("partyFacts", function()
    local p, s = party()
    local out = {}
    for _, mon in ipairs(p or {}) do
      local uid = monUid[mon]
      if uid and mon.isEgg ~= true then
        local moves = {}
        for _, mv in ipairs(type(mon.moves) == "table" and mon.moves or {}) do
          local n = type(mv) == "table" and b.adapter.moveNumOf(mv.id) or nil
          if n then moves[#moves + 1] = n end
        end
        -- the held item as its canonical id (false: none; hidden: a hidden-only one, never forwarded)
        local item = false
        if type(mon.item) == "string" and mon.item ~= "" and mon.item ~= "NO_ITEM" then
          item = b.heldHiddenOnly(mon.item) and "hidden" or (b.canonicalItem(mon.item) or "hidden")
        end
        out[#out + 1] = { uid = uid, hp = tonumber(mon.hp), status = FACT_STATUS[mon.status] or "none",
                          friendship = tonumber(mon.happiness) and floor(mon.happiness) or nil, moves = moves,
                          item = item }
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
    st.renames[#st.renames + 1] = { uid = uid, nick = nick, session = b.saveObject() }
  end)
  def("takeRenames", function()
    local st = b.state()
    local out = {}
    local list = type(st) == "table" and st.renames or nil
    if type(list) ~= "table" then return out end
    local s = b.saveObject()
    for _, r in ipairs(list) do
      if r.session == s then out[#out + 1] = { uid = r.uid, nick = r.nick } end
    end
    st.renames = {}
    return out
  end)
end

return Party2
