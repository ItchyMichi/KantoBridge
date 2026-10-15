-- gen2 bag (GEN2_BRIDGE_PLAN.md §5.10, phase G2F; the items and saving
-- groups' bag and PC): the bag for the MG6 ledger (bag.sync's counts, bag.use,
-- bag.set's projection), the item uses on party members, and Bill's PC.
--
-- * The bag is Gen 1's shared module (src/inventory/Bag.lua) over
--   save.inventory, an id -> count map bucketed into four pockets by each
--   item's `pocket` (ITEM 20 slots, BALL 12, KEY_ITEM 25, TM_HM 57; stacks of
--   99; Bag.lua:1-21, :443-469). Symbols are the item ids (POTION,
--   TM_DYNAMICPUNCH), the catalogue's `symbols.gen2` (protocol/items.json).
-- * Money is save.player.money (Save.lua:207-213); Mom's savings
--   (save.mom.savedMoney) stay Crystal's (J13): a prize's share for her
--   leaves the shared wallet like spending.
-- * Key items never cross (MG6): the KEY_ITEM pocket and HMs (TM_HM items
--   named HM_*). Gen 2's badges are not items.
-- * Item uses on party members (stones, Rare Candy, TMs and HMs) would be
--   lost on the shadow party (the next party.sync overwrites the member while
--   the item is spent). Gen 2 has no item.use hook, so Game2:usePartyItem and
--   Game2:useFieldItem (Game2.lua:686-905) are wrapped: while a client
--   follows they are refused with the hidden game's own "no effect" line, as
--   Gen 3's are (Cobblemon's own items do the same on the Minecraft side).
-- * Bill's PC is Cobblemon's PC (gen1.lua's hold): CenterPcMenu:choose
--   (src/ui/gen2/CenterPcMenu.lua:217-232) is wrapped, so the BILL's row says
--   its line as usual and then, instead of Gen 2's box screens, a hold state
--   waits on top of the still-open PC menu until Cobblemon's PC closes
--   (kb_pc's pc.open / pc.closed).
--
-- Paths are refs/gen1recomp's (Gen1Recomp v0.3.85).

local Items2 = {}

Items2.CLASS_ORDER = { ball = 1, medicine = 2, battle = 3, field = 4, stone = 5, valuable = 6, held = 7, tm = 8 }
Items2.FAMILY = "gen2"
Items2.MAX_STACK = 99
Items2.MAX_MONEY = 999999
Items2.KEY_POCKET = "KEY_ITEM"
Items2.ITEM_POCKET_SLOTS = 20
-- classes whose use on a party member Cobblemon owns (stones evolve, Rare Candy levels, TMs and HMs teach)
Items2.MEMBER_CLASSES = { stone = true, tm = true }
Items2.RARE_CANDY = "RARE_CANDY"
-- the hidden game's own refusal (ItemEffects' NoEffect line)
Items2.NO_EFFECT = "It won't have any effect."
Items2.BILLS = "bills"
local unpack = table.unpack or unpack -- luacheck: ignore (LuaJIT's global)
local function pack(...) return { n = select("#", ...), ... } end

function Items2.install(b)
  local def = b.def
  local floor = math.floor

  local function catalogue()
    local items = b.opts and b.opts.items
    return type(items) == "table" and type(items.items) == "table" and items.items or {}
  end
  local function data()
    local g = b.game
    return type(g) == "table" and type(g.data) == "table" and g.data or nil
  end
  local function Bag() return b.mod("src.inventory.Bag") end
  local function save()
    local s = b.saveObject()
    return type(s) == "table" and s or nil
  end
  local function itemDef(sym)
    local d = data()
    local rec = d and type(d.items) == "table" and d.items[sym] or nil
    return type(rec) == "table" and rec or nil
  end
  local function live()
    local st = b.state()
    local bridge = st and st.bridge
    return type(bridge) == "table" and type(bridge.live) == "function" and bridge.live() == true
  end

  def("itemTable", function()
    local out = {}
    for cid, entry in pairs(catalogue()) do
      local sym = type(entry) == "table" and type(entry.symbols) == "table" and entry.symbols[Items2.FAMILY] or nil
      if type(sym) == "string" then out[sym] = cid end
    end
    return out
  end)

  -- MG9 (G2F): a held item between the game's symbol and the catalogue's canonical id (the protocol's), or nil for
  -- one the catalogue doesn't know
  function b.canonicalItem(sym)
    if type(sym) ~= "string" then return nil end
    local cid = b.adapter.itemTable()[sym]
    return type(cid) == "string" and cid or nil
  end
  function b.itemSymbol(cid)
    if type(cid) ~= "string" then return nil end
    local entry = catalogue()[cid]
    local sym = type(entry) == "table" and type(entry.symbols) == "table" and entry.symbols[Items2.FAMILY] or nil
    return type(sym) == "string" and sym or nil
  end

  def("itemClass", function(symbol)
    for _, entry in pairs(catalogue()) do
      if type(entry) == "table" and type(entry.symbols) == "table" and entry.symbols[Items2.FAMILY] == symbol then
        return entry.class
      end
    end
    return nil
  end)

  def("isKeyItem", function(sym)
    if type(sym) ~= "string" then return false end
    if sym:find("^HM_") then return true end
    local rec = itemDef(sym)
    return rec ~= nil and rec.pocket == Items2.KEY_POCKET
  end)

  -- The move a TM or HM teaches (its record's `teaches`, RomExtractorGen2.lua:4495-4511), as its number.
  def("tmMove", function(sym)
    local rec = itemDef(sym)
    if not rec or rec.teaches == nil then return nil end
    return b.adapter.moveNumOf(rec.teaches)
  end)

  def("bagCounts", function(items)
    local out = {}
    local s = save()
    local inv = s and type(s.inventory) == "table" and s.inventory or nil
    if not inv then return out end
    for _, sym in ipairs(type(items) == "table" and items or {}) do
      local qty = inv[sym]
      if type(qty) == "number" and qty >= 1 and not b.adapter.isKeyItem(sym) then out[sym] = floor(qty) end
    end
    return out
  end)

  -- Up to qty of an item out of the bag (Bag.remove, Bag.lua:464-469): (taken, held).
  def("bagRemove", function(sym, qty)
    local s = save()
    local inv = s and type(s.inventory) == "table" and s.inventory or nil
    if not inv then return 0, 0 end
    local held = type(inv[sym]) == "number" and math.max(0, floor(inv[sym])) or 0
    local n = math.min(floor(tonumber(qty) or 0), held)
    if n <= 0 then return 0, held end
    local ok = b.call(Bag(), "remove", s, sym, n, data())
    if not ok then return 0, held end
    b.dropView()
    return n, held
  end)

  -- The ITEM pocket's limits (the ledger's): its slots (Bag.capacity), the stack, the slots in use (Bag.slots).
  def("bagLimits", function()
    local out = { slots = Items2.ITEM_POCKET_SLOTS, stack = Items2.MAX_STACK, used = 0 }
    local okCap, cap = b.call(Bag(), "capacity", data())
    if okCap and tonumber(cap) and cap >= 1 then out.slots = floor(cap) end
    local s = save()
    if s then
      local okUsed, used = b.call(Bag(), "slots", s, data())
      if okUsed and tonumber(used) then out.used = floor(used) end
    end
    return out
  end)

  -- bag.set's projection (docs/PROTOCOL.md "One economy"), as gen1.lua's: every catalogue item this game knows that
  -- isn't a key item leaves the bag, then the projection goes in by class, 99 a call (Bag.add is all or nothing); what
  -- didn't fit is `left`. The wallet (save.player.money) is written clamped. Returns (ok, left).
  def("bagSet", function(items, money)
    local s = save()
    local B = Bag()
    if not (s and type(B) == "table" and type(B.add) == "function" and type(B.remove) == "function") then
      return false, {}
    end
    local d = data()
    s.inventory = type(s.inventory) == "table" and s.inventory or {}
    local inv = s.inventory
    local tbl = b.adapter.itemTable()
    local syms = {}
    for sym in pairs(tbl) do syms[#syms + 1] = sym end
    table.sort(syms)
    for _, sym in ipairs(syms) do
      local held = type(inv[sym]) == "number" and floor(inv[sym]) or 0
      if held > 0 and not b.adapter.isKeyItem(sym) then pcall(B.remove, s, sym, held, d) end
    end
    local list = {}
    for sym, qty in pairs(type(items) == "table" and items or {}) do
      if type(sym) == "string" and tbl[sym] and type(qty) == "number" and qty >= 1 and not b.adapter.isKeyItem(sym)
          and itemDef(sym) then
        list[#list + 1] = { sym = sym, qty = floor(qty), rank = Items2.CLASS_ORDER[b.adapter.itemClass(sym) or ""] or 9 }
      end
    end
    table.sort(list, function(x, y)
      if x.rank ~= y.rank then return x.rank < y.rank end
      return x.sym < y.sym
    end)
    local left = {}
    for _, e in ipairs(list) do
      local remaining = e.qty
      while remaining > 0 do
        local n = math.min(remaining, Items2.MAX_STACK)
        local ok, added = pcall(B.add, s, e.sym, n, d)
        if not (ok and added == true) then break end
        remaining = remaining - n
      end
      if remaining > 0 then left[e.sym] = remaining end
    end
    local m = tonumber(money)
    if m then
      s.player = type(s.player) == "table" and s.player or {}
      s.player.money = math.max(0, math.min(Items2.MAX_MONEY, floor(m)))
    end
    b.dropView()
    return true, left
  end)

  -- Where routes need it: how many of an item the bag holds.
  def("inventoryCount", function(sym)
    local s = save()
    local inv = s and type(s.inventory) == "table" and s.inventory or nil
    return inv and type(inv[sym]) == "number" and floor(inv[sym]) or 0
  end)

  -- Gen 2 raises no item.use (the hook main.lua wraps never fires); the guards below do its work.
  def("itemUse", function(nextFn, ...) return nextFn(...) end)
  def("takeItemUses", function() return {} end)
  def("tmWatchStep", function() end)
  def("itemCounts", function()
    local st = b.state()
    return { refused = st and st.itemRefused or 0 }
  end)

  -- ---- item uses on party members (refused while a client follows)

  local function refuse(g)
    local st = b.state()
    if st then st.itemRefused = (st.itemRefused or 0) + 1 end
    local Strings = b.mod("src.core.Strings")
    local text = Items2.NO_EFFECT
    if type(Strings) == "table" or type(Strings) == "function" then
      local ok, t = pcall(Strings, Items2.NO_EFFECT)
      if ok and type(t) == "string" then text = t end
    end
    if type(g) == "table" and type(g.say) == "function" then pcall(g.say, g, text) end
  end

  local function memberItem(sym)
    if type(sym) ~= "string" then return false end
    if sym == Items2.RARE_CANDY or sym:find("^TM_") or sym:find("^HM_") then return true end
    local cls = b.adapter.itemClass(sym)
    if cls and Items2.MEMBER_CLASSES[cls] then return true end
    local rec = itemDef(sym)
    return rec ~= nil and rec.teaches ~= nil
  end

  -- Game2:usePartyItem(itemId) (stones, Rare Candy and the rest used on a member) and Game2:useFieldItem(itemId)
  -- (a TM or HM taught from the PACK; Game2.lua:686-751), refused for the member items while a client follows.
  local function usePartyItem(original, g, itemId, ...)
    if live() and memberItem(itemId) then return refuse(g) end
    return original(g, itemId, ...)
  end
  local function useFieldItem(original, g, itemId, ...)
    local rec = itemDef(itemId)
    if live() and rec and rec.teaches ~= nil then return refuse(g) end
    return original(g, itemId, ...)
  end

  -- ---- Bill's PC

  local pcCounts = { taken = 0, native = 0 }

  local function pcRecord()
    local st = b.state()
    local rec = type(st) == "table" and st.pc or nil
    return type(rec) == "table" and rec or nil, st
  end

  -- The hold: a plain state over the PC menu that pops itself once its record is no longer waiting (only the top
  -- state updates, Game2.lua:1314-1318).
  local function holdPc()
    local st = b.state()
    local g = b.game
    if not (st and type(g) == "table" and g.stack) then return end
    local rec = { state = "waiting", ow = b.ow(), save = b.saveObject() }
    local stack = g.stack
    local hold = { kantoBridgePcHold = true, isOpaque = false }
    function hold.update()
      if rec.state ~= "waiting" and stack:top() == hold then stack:pop() end
    end
    function hold.draw() end
    rec.hold = hold
    st.pc = rec
    stack:push(hold)
  end

  -- CenterPcMenu:choose: the BILL's row's line goes as usual, then the hold in place of Gen 2's box screens.
  local function choose(original, menu, ...)
    local entries = type(menu) == "table" and menu.entries or nil
    local entry = type(entries) == "table" and entries[menu.index] or nil
    if not (type(entry) == "table" and entry.id == Items2.BILLS) or not live() or pcRecord() then
      if type(entry) == "table" and entry.id == Items2.BILLS then pcCounts.native = pcCounts.native + 1 end
      return original(menu, ...)
    end
    local own = rawget(menu, "say")
    local say = menu.say
    menu.say = function(m, pages, _done, ...)
      menu.say = own
      return say(m, pages, function() holdPc() end, ...)
    end
    local out = pack(pcall(original, menu, ...))
    if rawget(menu, "say") ~= own then menu.say = own end
    if not out[1] then error(out[2], 0) end
    pcCounts.taken = pcCounts.taken + 1
    return unpack(out, 2, out.n)
  end

  -- Installed with the bridge (battle.lua's attachBridge), once per process.
  function b.installItemGuards()
    local wrap = b.wrap
    if type(wrap) ~= "function" then return end
    local Game2 = b.mod("src.core.Game2")
    wrap(Game2, "usePartyItem", usePartyItem)
    wrap(Game2, "useFieldItem", useFieldItem)
    wrap(b.ui2("CenterPcMenu"), "choose", choose)
  end

  -- ui.pc.items fires inside Gen 2's box screens (PcMenu.lua:126-140), which the hold never opens.
  def("pcItems", function(nextFn, g, items) return nextFn(g, items) end)

  -- The PC hold as plain values ({ state, sentGen, id }) or nil; a hold whose world went away is dropped.
  def("pcPending", function()
    local rec, st = pcRecord()
    if not rec then return nil end
    if rec.state ~= "waiting" or rec.ow ~= b.ow() or rec.save ~= b.saveObject() then
      if rec.state == "waiting" then rec.state = "dropped" end
      st.pc = nil
      return nil, "dropped"
    end
    return { state = rec.state, sentGen = rec.sentGen, id = rec.id }
  end)
  def("pcSent", function(generation, id)
    local rec = pcRecord()
    if rec then rec.sentGen, rec.id = generation, id end
  end)
  def("pcClose", function(why, id)
    local rec, st = pcRecord()
    if not rec or rec.state ~= "waiting" then return false end
    if id ~= nil and rec.id ~= id then return false, "stale" end
    rec.state = why or "closed"
    st.pc = nil
    return true
  end)
  def("pcHeld", function() return pcRecord() ~= nil end)
  def("pcCounts", function() return { taken = pcCounts.taken, native = pcCounts.native } end)
end

return Items2
