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
-- * Item uses on party members (stones, Rare Candy, TMs and HMs) would be
--   lost on the shadow party: the next party.sync overwrites the member
--   while the item is spent. They are refused while a client follows (the
--   hidden game's own "It won't have any effect"); Cobblemon's own items do
--   the same on the Minecraft side.
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Items3 = {}

-- bag.set's order: balls, medicine, battle items, field items, stones, valuables, held items, TMs (gen1.lua's)
Items3.CLASS_ORDER = { ball = 1, medicine = 2, battle = 3, field = 4, stone = 5, valuable = 6, held = 7, tm = 8 }
Items3.FAMILY = "gen3"
Items3.MAX_STACK = 99
Items3.MAX_MONEY = 999999
-- classes whose use on a party member Cobblemon owns (stones evolve, Rare Candy levels, TMs and HMs teach)
Items3.MEMBER_CLASSES = { stone = true, tm = true }
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

  -- item.use (item_use.lua:1559-1577: game, nil, itemId, partySlot, bag): a stone, Rare Candy, TM or HM used on a party
  -- member while a client follows is refused (the shadow party is Cobblemon's; the next party.sync would undo it while
  -- the item is spent).
  def("itemUse", function(nextFn, g, battle, itemId_, target, list, moveIndex, picker)
    local st = b.state()
    local bridge = st and st.bridge
    local live = type(bridge) == "table" and type(bridge.live) == "function" and bridge.live() == true
    if live and battle == nil and tonumber(target) ~= nil then
      local C = b.constants()
      local name = C and b.ask(C, "name", nil, C, "items", tonumber(itemId_), "ITEM_") or nil
      local sym = type(name) == "string" and name:gsub("^ITEM_", "") or nil
      local cls = sym and b.adapter.itemClass(sym) or nil
      local Data = b.g3("items_data")
      local machine = tonumber(itemId_) and (b.ask(Data, "isTm", false, tonumber(itemId_)) == true
                                              or b.ask(Data, "isHm", false, tonumber(itemId_)) == true)
      if (cls and Items3.MEMBER_CLASSES[cls]) or sym == Items3.RARE_CANDY or machine then
        st.itemRefused = (st.itemRefused or 0) + 1
        return false, "noeffect", "It won't have any effect."
      end
    end
    return nextFn(g, battle, itemId_, target, list, moveIndex, picker)
  end)

  def("itemCounts", function()
    local st = b.state()
    return { refused = st and st.itemRefused or 0 }
  end)
end

return Items3
