-- game3 bag (GEN3_BRIDGE_PLAN.md §4.10, phase G3F; the items group): the bag for the MG6
-- ledger (bag.sync's counts, bag.use, bag.set's projection) and the item
-- uses on party members.
--
-- * Symbols: Emerald's ITEM_* names without the prefix (the save view's
--   spelling, boot.lua's inventory: POTION, TM01, SUN_STONE), the
--   catalogue's `symbols.gen3` (protocol/items.json). The engine's numbers
--   come from the constants (Constants.of, constants.lua:39-62: C:id("items",
--   "ITEM_POTION")).
-- * The bag is session.bag (bag.lua:9-27): Bag.get / Bag.add (all or
--   nothing per call, :375-474) / Bag.remove; pockets and their capacity are
--   the engine's (profiles/emerald/bag.lua).
-- * Key items never cross (MG6): the KEY_ITEMS pocket (ItemsData.pocketOf,
--   items_data.lua:333-336), HMs (ItemsData.isHm, :385) and the badges the
--   save view lists as items.
-- * Item uses on party members go to Cobblemon (FOREIGN_POKEMON_PLAN §4.4,
--   FP4; below, "item uses on party members").
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Items3 = {}

local unpack = table.unpack or unpack -- luacheck: ignore (LuaJIT's global)

-- bag.set's order: balls, medicine, battle items, field items, stones, valuables, held items, TMs (gen1.lua's)
Items3.CLASS_ORDER = { ball = 1, medicine = 2, battle = 3, field = 4, stone = 5, valuable = 6, held = 7, tm = 8 }
Items3.FAMILY = "gen3"
Items3.MAX_STACK = 99
Items3.MAX_MONEY = 999999
-- the candy Cobblemon levels with (FP4)
Items3.RARE_CANDY = "RARE_CANDY"

function Items3.install(b)
  local def = b.def
  local floor = math.floor

  local function catalogue()
    local items = b.opts and b.opts.items
    return type(items) == "table" and type(items.items) == "table" and items.items or {}
  end

  -- The engine's number of one of this game's item symbols, or nil.
  local function itemId(sym)
    if type(sym) ~= "string" then return nil end
    local C = b.constants()
    local id = C and tonumber(b.ask(C, "id", nil, C, "items", "ITEM_" .. sym)) or nil
    return id
  end

  -- FP4 (§5): held items both ways through the catalogue (symbols.gen3). A record's item (a number, or a symbol)
  -- as its canonical id ("hidden" for one a member keeps in the hidden game: Mail, anything the catalogue maps
  -- to no Cobblemon item, anything it doesn't know; false for none), and a canonical id as the engine's number
  local function heldSym(v)
    if type(v) == "string" then return v ~= "" and v ~= "NONE" and (v:gsub("^ITEM_", "")) or nil end
    local n = tonumber(v)
    if not n or n == 0 then return nil end
    local C = b.constants()
    local name = C and b.ask(C, "name", nil, C, "items", n, "ITEM_") or nil
    return type(name) == "string" and (name:gsub("^ITEM_", "")) or nil
  end
  function b.heldCanonical(v)
    local sym = heldSym(v)
    if not sym then return false end
    local cid
    for id, entry in pairs(catalogue()) do
      if type(entry) == "table" and type(entry.symbols) == "table" and entry.symbols[Items3.FAMILY] == sym then cid = id break end
    end
    local entry = cid and catalogue()[cid] or nil
    if not (type(entry) == "table" and entry.hiddenOnly ~= true and type(entry.cobblemon) == "string") then return "hidden" end
    return cid
  end
  function b.heldNumber(cid)
    local entry = type(cid) == "string" and catalogue()[cid] or nil
    local sym = type(entry) == "table" and type(entry.symbols) == "table" and entry.symbols[Items3.FAMILY] or nil
    return sym and itemId(sym) or nil
  end

  local function bag()
    local s = b.session()
    return type(s) == "table" and type(s.bag) == "table" and s.bag or nil, s
  end

  def("itemTable", function()
    local out = {}
    for cid, entry in pairs(catalogue()) do
      local sym = type(entry) == "table" and type(entry.symbols) == "table" and entry.symbols[Items3.FAMILY] or nil
      if type(sym) == "string" then out[sym] = cid end
    end
    return out
  end)

  def("itemClass", function(symbol)
    for _, entry in pairs(catalogue()) do
      if type(entry) == "table" and type(entry.symbols) == "table" and entry.symbols[Items3.FAMILY] == symbol then
        return entry.class
      end
    end
    return nil
  end)

  def("isKeyItem", function(sym)
    if type(sym) ~= "string" then return false end
    if sym:find("BADGE", 1, true) then return true end
    local id = itemId(sym)
    if not id then return false end
    local Data = b.g3("items_data")
    if b.ask(Data, "pocketOf", nil, id) == "KEY_ITEMS" then return true end
    return b.ask(Data, "isHm", false, id) == true
  end)

  -- The move a TM or HM teaches (Pokemon.moveFromTmItem, pokemon.lua:1204), as its number.
  def("tmMove", function(sym)
    local id = itemId(sym)
    local mv = id and tonumber(b.ask(b.g3("pokemon"), "moveFromTmItem", nil, id)) or nil
    return mv and mv >= 1 and floor(mv) or nil
  end)

  def("bagCounts", function(items)
    local out = {}
    local bg = bag()
    if not bg then return out end
    local Bag = b.g3("bag")
    for _, sym in ipairs(type(items) == "table" and items or {}) do
      local id = itemId(sym)
      local qty = id and tonumber(b.ask(Bag, "get", 0, bg, id)) or 0
      if qty >= 1 and not b.adapter.isKeyItem(sym) then out[sym] = floor(qty) end
    end
    return out
  end)

  -- Up to qty of an item out of the bag: (taken, held).
  def("bagRemove", function(sym, qty)
    local bg = bag()
    local id = itemId(sym)
    if not (bg and id) then return 0, 0 end
    local Bag = b.g3("bag")
    local held = floor(tonumber(b.ask(Bag, "get", 0, bg, id)) or 0)
    local n = math.min(floor(tonumber(qty) or 0), held)
    if n <= 0 then return 0, held end
    local ok, removed = b.call(Bag, "remove", bg, id, n)
    if not (ok and removed ~= false) then return 0, held end
    b.dropView()
    return n, held
  end)

  -- The ITEMS pocket's limits (the ledger's): its capacity, the stack, the slots in use.
  def("bagLimits", function()
    local bg = bag()
    local out = { slots = 30, stack = Items3.MAX_STACK, used = 0 }
    local Data = b.g3("items_data")
    local cap = type(Data) == "table" and type(Data.CAPACITY) == "table" and tonumber(Data.CAPACITY.ITEMS) or nil
    if cap and cap >= 1 then out.slots = floor(cap) end
    local pockets = bg and type(bg.pockets) == "table" and bg.pockets or {}
    out.used = type(pockets.ITEMS) == "table" and #pockets.ITEMS or 0
    return out
  end)

  -- bag.set's projection (docs/PROTOCOL.md "One economy"), as gen1.lua's: every catalogue item this game knows that
  -- isn't a key item leaves the bag, then the projection goes in by class, 99 a call (Bag.add is all or nothing); what
  -- didn't fit is `left`. Money is written clamped. Returns (ok, left).
  def("bagSet", function(items, money)
    local bg, s = bag()
    local Bag = b.g3("bag")
    if not (bg and type(Bag) == "table" and type(Bag.add) == "function" and type(Bag.remove) == "function") then
      return false, {}
    end
    local tbl = b.adapter.itemTable()
    local syms = {}
    for sym in pairs(tbl) do syms[#syms + 1] = sym end
    table.sort(syms)
    for _, sym in ipairs(syms) do
      local id = itemId(sym)
      local held = id and floor(tonumber(b.ask(Bag, "get", 0, bg, id)) or 0) or 0
      if held > 0 and not b.adapter.isKeyItem(sym) then b.call(Bag, "remove", bg, id, held) end
    end
    local list = {}
    for sym, qty in pairs(type(items) == "table" and items or {}) do
      if type(sym) == "string" and tbl[sym] and type(qty) == "number" and qty >= 1 and not b.adapter.isKeyItem(sym) then
        list[#list + 1] = { sym = sym, qty = floor(qty), rank = Items3.CLASS_ORDER[b.adapter.itemClass(sym) or ""] or 9 }
      end
    end
    table.sort(list, function(x, y)
      if x.rank ~= y.rank then return x.rank < y.rank end
      return x.sym < y.sym
    end)
    local left = {}
    for _, e in ipairs(list) do
      local id = itemId(e.sym)
      local remaining = id and e.qty or 0
      while remaining > 0 do
        local n = math.min(remaining, Items3.MAX_STACK)
        local ok, added = b.call(Bag, "add", bg, id, n)
        if not (ok and added == true) then break end
        remaining = remaining - n
      end
      if remaining > 0 or not id then left[e.sym] = id and remaining or e.qty end
    end
    local m = tonumber(money)
    if m then s.money = math.max(0, math.min(Items3.MAX_MONEY, floor(m))) end
    b.dropView()
    return true, left
  end)

  -- Where routes need it: how many of an item the bag holds.
  def("inventoryCount", function(sym)
    local bg = bag()
    local id = itemId(sym)
    return (bg and id) and floor(tonumber(b.ask(b.g3("bag"), "get", 0, bg, id)) or 0) or 0
  end)

  -- ---- item uses on party members (FOREIGN_POKEMON_PLAN §4.4, FP4)
  --
  -- While a client follows, on a member with a Cobblemon uid:
  -- * medicine, vitamins and the PP family (item.use, item_use.lua:1559-1577:
  --   game, nil, itemId, partySlot, bag; the party menu's general case,
  --   src/ui/game3/party_menu.lua:2354) run natively on the record (the next
  --   sync rewrites it) and, when the engine used the item, go to Cobblemon
  --   as party.item (kind medicine; kb_party names pp and vitamin; a PP item
  --   names the move whose PP changed);
  -- * a stone is answered from the sync's stones (else the engine's own
  --   Evolution.itemTarget) and Cobblemon evolves it: the engine's scene (or
  --   Evolution.apply) is skipped for that member, the stone spent natively;
  -- * Rare Candy levels the record natively (ItemUse.levelUpEvent, :656, the
  --   item-only seam, says it happened) and goes to Cobblemon; the level-up's
  --   move prompt and evolution are Cobblemon's (Pokemon.movesLearnedAt and
  --   Evolution.levelTarget / targetSpecies answer nothing for that member);
  -- * a TM or HM's check (ItemUse.checkTmPreflight, :558) is answered from the
  --   sync's canLearn; once taught (Pokemon.teachMove into a free slot, or
  --   LearnMove.begin's forget flow), the move and the one it replaced go out.

  local function live()
    local st = b.state()
    local bridge = st and st.bridge
    return type(bridge) == "table" and type(bridge.live) == "function" and bridge.live() == true
  end
  local function memberUid(mon)
    if not (live() and type(mon) == "table" and mon.isEgg ~= true) then return nil end
    local uid = b.adapter.uidOf(mon)
    return type(uid) == "string" and uid or nil
  end
  -- An engine item id (a number, or a symbol with or without ITEM_) as the catalogue's gen3 symbol
  local function itemSym(v)
    if type(v) == "string" then return (v:gsub("^ITEM_", "")) end
    local C = b.constants()
    local name = C and tonumber(v) and b.ask(C, "name", nil, C, "items", tonumber(v), "ITEM_") or nil
    return type(name) == "string" and (name:gsub("^ITEM_", "")) or nil
  end
  local function queueUse(use)
    local st = b.state()
    if not st then return end
    if type(st.itemUses) ~= "table" then st.itemUses = {} end
    use.save = b.saveObject()
    st.itemUses[#st.itemUses + 1] = use
    st.itemForwarded = (st.itemForwarded or 0) + 1
  end
  def("takeItemUses", function()
    local st = b.state()
    local list = st and st.itemUses or nil
    local out = {}
    if type(list) ~= "table" then return out end
    local s = b.saveObject()
    for _, u in ipairs(list) do
      if u.save == s then
        out[#out + 1] = { item = u.item, uid = u.uid, kind = u.kind, move = u.move, slot = u.slot, forget = u.forget }
      end
    end
    st.itemUses = {}
    return out
  end)

  local function ppState(mon)
    local out = {}
    for i = 1, 4 do
      out[i] = table.concat({ tostring(type(mon.pp) == "table" and mon.pp[i] or ""),
                              tostring(type(mon.maxPp) == "table" and mon.maxPp[i] or ""),
                              tostring(type(mon.ppBonuses) == "table" and mon.ppBonuses[i] or mon.ppBonuses or "") }, ":")
    end
    return out
  end
  local function moveAt(mon, i)
    local id = type(mon.moves) == "table" and mon.moves[i] or nil
    return tonumber(type(id) == "table" and id.id or id)
  end
  -- useField's kinds for medicine (item_use.lua:1480-1545; HP items answer "heal" or none)
  local USES = { pp = true, vitamin = true, status = true, revive = true, heal = true, full_restore = true }

  def("itemUse", function(nextFn, g, battle, itemId_, target, list, moveIndex, picker)
    local s = b.session()
    local slot = tonumber(target)
    local mon = battle == nil and slot and s and type(s.party) == "table" and s.party[slot] or nil
    local uid = memberUid(mon)
    if not uid then return nextFn(g, battle, itemId_, target, list, moveIndex, picker) end
    local before = ppState(mon)
    local out = { pcall(nextFn, g, battle, itemId_, target, list, moveIndex, picker) }
    if not out[1] then error(out[2], 0) end
    local ok, kind = out[2], out[3]
    local sym = itemSym(itemId_)
    if ok == true and sym and USES[kind] then
      local use = { item = sym, uid = uid, kind = "medicine" }
      if kind == "pp" and b.ask(b.g3("item_use"), "ppItemNeedsMove", false, itemId_) == true then
        -- the one move whose PP changed (the hook isn't told the move slot)
        local after = ppState(mon)
        for i = 1, 4 do
          if before[i] ~= after[i] and moveAt(mon, i) then use.move = moveAt(mon, i) break end
        end
      end
      queueUse(use)
    end
    return unpack(out, 2, table.maxn(out))
  end)

  local pendingStone = setmetatable({}, { __mode = "k" })
  local candyMon = setmetatable({}, { __mode = "k" })
  local tmPending = setmetatable({}, { __mode = "k" })

  -- Evolution.itemTarget(mon, item, session): the sync's stones answer; a yes is the member's own species (the
  -- party menu and useEvolutionStone name it before the scene), which the scene's wrapper then skips
  local function itemTarget(original, mon, item, session, ...)
    local uid = memberUid(mon)
    if not uid then return original(mon, item, session, ...) end
    local sym = itemSym(item)
    local yes
    if type(mon.kbStones) == "table" then
      yes = sym ~= nil and mon.kbStones[sym] == true
    else
      yes = original(mon, item, session, ...) ~= nil
    end
    if not yes then return nil end
    local own = tonumber(mon.species or mon.speciesId)
    pendingStone[mon] = { item = sym, uid = uid, target = own }
    return own
  end
  local function stoneTaken(mon, target)
    local p = pendingStone[mon]
    if not (p and target == p.target) then return false end
    pendingStone[mon] = nil
    queueUse({ item = p.item, uid = p.uid, kind = "stone" })
    return true
  end
  local function sceneStart(original, mon, target, opts, ...)
    if stoneTaken(mon, target) then
      if type(opts) == "table" and type(opts.onDone) == "function" then opts.onDone({ evolved = false }) end
      return nil
    end
    return original(mon, target, opts, ...)
  end
  local function evolutionApply(original, mon, target, ...)
    if stoneTaken(mon, target) then return nil end
    return original(mon, target, ...)
  end

  -- ItemUse.levelUpEvent(mon, level): Rare Candy (the party menu's, or useRareCandy's) on a member: Cobblemon's
  local function levelUpEvent(original, mon, level, ...)
    local uid = memberUid(mon)
    if uid then
      candyMon[mon] = { species = tonumber(mon.species or mon.speciesId), level = tonumber(level) }
      queueUse({ item = Items3.RARE_CANDY, uid = uid, kind = "candy" })
    end
    return original(mon, level, ...)
  end
  local function movesLearnedAt(original, species, level, ...)
    for _, c in pairs(candyMon) do
      if c.species == tonumber(species) and c.level == tonumber(level) then return {} end
    end
    return original(species, level, ...)
  end
  local function levelTarget(original, mon, ...)
    if candyMon[mon] then return nil end
    return original(mon, ...)
  end
  local function targetSpecies(original, mon, ...)
    if candyMon[mon] then
      candyMon[mon] = nil
      return nil
    end
    return original(mon, ...)
  end

  -- ItemUse.checkTmPreflight(mon, tmId): the species' list answers through Pokemon.canLearnTmItem, the member's
  -- canLearn for a synced one; an "ok" is watched until the move lands
  local function checkTmPreflight(original, mon, tmId, ...)
    local uid = memberUid(mon)
    local P = b.g3("pokemon")
    if not (uid and type(mon.kbCanLearn) == "table" and type(P) == "table" and type(P.canLearnTmItem) == "function") then
      return original(mon, tmId, ...)
    end
    local can0 = P.canLearnTmItem
    local moveId = tonumber(b.ask(P, "moveFromTmItem", nil, tmId))
    P.canLearnTmItem = function() return moveId ~= nil and mon.kbCanLearn[moveId] == true end
    local out = { pcall(original, mon, tmId, ...) }
    P.canLearnTmItem = can0
    if not out[1] then error(out[2], 0) end
    if out[2] == "ok" and moveId then
      local before = {}
      for i = 1, 4 do before[i] = moveAt(mon, i) end
      tmPending[mon] = { item = itemSym(tmId), uid = uid, move = moveId, before = before }
    end
    return unpack(out, 2, table.maxn(out))
  end
  local function tmLanded(mon)
    local p = tmPending[mon]
    if not p then return end
    for i = 1, 4 do
      if moveAt(mon, i) == p.move and p.before[i] ~= p.move then
        tmPending[mon] = nil
        local use = { item = p.item, uid = p.uid, kind = "machine", move = p.move }
        if p.before[i] then use.slot, use.forget = i - 1, p.before[i] end
        queueUse(use)
        return
      end
    end
  end
  local function teachMove(original, mon, moveId, ...)
    local out = { pcall(original, mon, moveId, ...) }
    if not out[1] then error(out[2], 0) end
    if out[2] then tmLanded(mon) end
    return unpack(out, 2, table.maxn(out))
  end
  local function learnBegin(original, opts, ...)
    local mon = type(opts) == "table" and opts.mon or nil
    if mon and tmPending[mon] and type(opts.onDone) == "function" then
      local onDone = opts.onDone
      opts.onDone = function(learned, ...)
        if learned then tmLanded(mon) else tmPending[mon] = nil end
        return onDone(learned, ...)
      end
    end
    return original(opts, ...)
  end

  -- Installed with the bridge (battle.lua's attachBridge), once per process (b.wrap).
  function b.installItems()
    local wrap = b.wrap
    if type(wrap) ~= "function" then return end
    local Evolution = b.g3("evolution")
    wrap(Evolution, "itemTarget", itemTarget)
    wrap(Evolution, "apply", evolutionApply)
    wrap(Evolution, "levelTarget", levelTarget)
    wrap(Evolution, "targetSpecies", targetSpecies)
    wrap(b.ui3("evolution_scene"), "start", sceneStart)
    local ItemUse = b.g3("item_use")
    wrap(ItemUse, "levelUpEvent", levelUpEvent)
    wrap(ItemUse, "checkTmPreflight", checkTmPreflight)
    local P = b.g3("pokemon")
    wrap(P, "movesLearnedAt", movesLearnedAt)
    wrap(P, "teachMove", teachMove)
    wrap(b.g3("battle.learn_move"), "begin", learnBegin)
  end

  def("itemCounts", function()
    local st = b.state()
    return { refused = st and st.itemRefused or 0, forwarded = st and st.itemForwarded or 0 }
  end)
end

return Items3
