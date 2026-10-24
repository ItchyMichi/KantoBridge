-- Fake wild encounters, fishing, the bag and items used on a party member
-- for the fake engine (M6). Written for Kanto Bridge from the engine
-- behaviour lib/kb_engine.lua cites (v0.3.85: OverworldController's
-- onStepComplete encounter roll with rollsIndoorEncounters, and
-- goFishing/fishVerdict, src/inventory/Bag.lua's stacks model,
-- src/ui/BagMenu.lua's use-flow, src/ui/MoveLearnMenu.lua); the code is our
-- own, and every species, item and text the tests install is made up.
--
--   FakeWild.attach(E)        after FakeScript.attach
--   E.rolls[terrain]          what the vanilla roll draws ("grass", "water",
--                             "indoor"): { species, level }, or nil for none
--   E.rollCalls[terrain]      vanilla rolls made (a suppressed one makes none)
--   E.rollsIndoorEncounters(def, indoor)   the indoor rule (B6): never on an
--                             outdoor map (`outdoor`, or the OVERWORLD tileset)
--   E.fishPools[rod]          the rod's candidates ({ { species, level } ...},
--                             or { always = { species, level } }); E.fishBites
--                             false makes the vanilla pick bite nothing
--   E.goFishing(rod)          OverworldState:goFishing's shape: the roll
--                             through encounter.fishing, the cast box (auto),
--                             then the verdict: no bite / the bite and a
--                             native battle
--   E.useItem(id, mon, opts)  BagMenu's useOn for a party member (the
--                             item.use hook around a fake vanillaUseOn)
--   E.bagList()               a BagMenu-like list of the bag's rows (one per
--                             stack, with `slot` and `stack`)
--   E.bagRemoves              every Bag.remove call: { id, qty, data, slot }
--   E.forgetSlot              the move slot the fake MoveLearnMenu replaces
--   E.nativeItems             what the fake vanillaUseOn did, in order
--
-- Engine modules it provides: src.inventory.Bag (add, remove, rows, order,
-- isBadge, slotsFor, stackRows, MAX_STACK), src.inventory.ItemEffects
-- (isStone), src.render.Transition (whiteFlash).

local FakeWild = {}

local STONES = { FIRE_STONE = true, WATER_STONE = true, THUNDER_STONE = true, LEAF_STONE = true,
                 MOON_STONE = true }

function FakeWild.attach(E)
  local ow = E.ow
  E.rolls, E.rollCalls, E.fishPools = {}, {}, {}
  E.fishBites = true
  E.nativeItems = {}
  E.bagRemoves = {}
  E.forgetSlot = 1
  E.save.repelSteps = 0

  -- ------------------------------------------------------------ modules

  -- src/inventory/Bag.lua (v0.3.85): totals in save.inventory, the
  -- acquisition order in save.bagOrder (one entry per stack), and
  -- save.bagStacks for an item whose stacks are not the derived shape (every
  -- stack but the last at 99). The fake keeps the rules the bridge sees --
  -- stacks split at MAX_STACK (:58-130: slotsFor, countsOf, setCounts),
  -- rows one per stack (stackRows :191-203, Bag.rows :369-373), addTo
  -- (:383-425: fill the last stack, open new ones), removeFrom (:427-463:
  -- the named slot first, then the rest in order; the order entry and the
  -- stacks go at zero) -- and leaves out the pockets; MG6 adds the 20-slot cap
  -- (the made-up bag never fills). Every item splits (Gen 1 and no
  -- `pocket`, :60-65); badges are never bag items (:23-26, :113).
  local MAX_STACK = 99
  local Bag = { MAX_STACK = MAX_STACK }
  local function isBadge(id) return type(id) == "string" and id:find("BADGE", 1, true) ~= nil end
  Bag.isBadge = isBadge
  local function splits(id, data)
    if E.GameVersion.generation() ~= 1 then return false end
    local def = data and data.items and data.items[id]
    return not (def and def.pocket)
  end
  -- a valid explicit stack list for the item, or nil (stackList :67-78)
  local function stackList(stacks, id, total)
    local list = stacks and stacks[id]
    if type(list) ~= "table" or #list == 0 or type(total) ~= "number" then return nil end
    local sum = 0
    for i = 1, #list do
      local c = list[i]
      if type(c) ~= "number" or c < 1 or c > MAX_STACK or c % 1 ~= 0 then return nil end
      sum = sum + c
    end
    if sum ~= total then return nil end
    return list
  end
  local function slotsFor(id, n, data, stacks)
    if not n or isBadge(id) then return 0 end
    if type(n) ~= "number" then return 1 end
    if n <= 0 then return 0 end
    if not splits(id, data) then return 1 end
    local list = stackList(stacks, id, n)
    if list then return #list end
    if n <= MAX_STACK then return 1 end
    return math.ceil(n / MAX_STACK)
  end
  Bag.slotsFor = slotsFor
  -- MG6: Bag.capacity(data, pocket) (:43-55): the ITEM pocket's cap (every Gen 1
  -- item's), data.constants.bagSize or 20
  function Bag.capacity(data, pocket)
    if pocket and pocket ~= "ITEM" then return 20 end
    local c = data and data.constants and data.constants.bagSize
    if type(c) == "number" and c >= 1 then return math.floor(c) end
    return 20
  end
  -- MG6: Bag.slots(save, data) (:344-349): the occupied slots, one per stack
  -- (badges take none)
  function Bag.slots(save, data)
    if not save or not save.inventory then return 0 end
    local n = 0
    for id, total in pairs(save.inventory) do
      n = n + slotsFor(id, total, data, save.bagStacks)
    end
    return n
  end
  local function derivedCount(total, k, n)
    if type(total) ~= "number" or n <= 1 then return total end
    if k < n then return MAX_STACK end
    return total - MAX_STACK * (n - 1)
  end
  local function isDerived(counts)
    for k = 1, #counts - 1 do
      if counts[k] ~= MAX_STACK then return false end
    end
    return true
  end
  local function countsOf(store, id, data, stacks)
    local total = store[id]
    local n = slotsFor(id, total, data, stacks)
    local list = stackList(stacks, id, total)
    local out = {}
    for k = 1, n do out[k] = (list and list[k]) or derivedCount(total, k, n) end
    return out
  end
  local function setCounts(store, id, counts, stacks)
    local sum = 0
    for i = 1, #counts do sum = sum + counts[i] end
    store[id] = sum > 0 and sum or nil
    if stacks then
      stacks[id] = (#counts > 1 and not isDerived(counts)) and counts or nil
    end
  end
  local function occurrences(order, id)
    local at = {}
    for i = 1, #order do
      if order[i] == id then at[#at + 1] = i end
    end
    return at
  end
  local function stacksOf(save)
    local t = save.bagStacks
    if type(t) ~= "table" then t = {} end
    return t
  end
  local function keepStacks(save, t)
    save.bagStacks = next(t) ~= nil and t or nil
  end

  -- the sort of ids the order lacks (byIndex :137-149): by the item's index,
  -- then by id
  local function byIndex(data)
    local items = data and data.items
    local function key(id)
      local def = items and items[id]
      return (def and (def.index or def.itemId)) or math.huge
    end
    return function(a, b)
      local ia, ib = key(a), key(b)
      if ia ~= ib then return ia < ib end
      return tostring(a) < tostring(b)
    end
  end
  -- Bag.order (:339-367) with normalize (:151-181): stale ids and stack
  -- entries beyond an item's count dropped, ids the order lacks appended
  function Bag.order(save, data)
    if not save then return {} end
    local store = save.inventory or {}
    save.inventory = store
    local order = save.bagOrder or {}
    save.bagOrder = order
    local stacks = stacksOf(save)
    for id in pairs(stacks) do
      local list = splits(id, data) and stackList(stacks, id, store[id])
      if not list or #list < 2 or isDerived(list) then stacks[id] = nil end
    end
    local seen, w = {}, 0
    for i = 1, #order do
      local id = order[i]
      local k = (seen[id] or 0) + 1
      if k <= slotsFor(id, store[id], data, stacks) then
        seen[id] = k
        w = w + 1
        order[w] = id
      end
    end
    for i = #order, w + 1, -1 do order[i] = nil end
    local missing = {}
    for id, n in pairs(store) do
      for _ = (seen[id] or 0) + 1, slotsFor(id, n, data, stacks) do
        missing[#missing + 1] = id
      end
    end
    if #missing > 1 then table.sort(missing, byIndex(data)) end
    for _, id in ipairs(missing) do order[#order + 1] = id end
    keepStacks(save, stacks)
    return order
  end
  -- Bag.stackRows / Bag.rows (:191-203, :369-373): { id, slot, index,
  -- count } per stack, in bag order
  function Bag.stackRows(store, order, data, stacks)
    local rows, seen = {}, {}
    for i, id in ipairs(order) do
      local k = (seen[id] or 0) + 1
      seen[id] = k
      local total = store[id]
      local list = stackList(stacks, id, total)
      rows[#rows + 1] = { id = id, slot = k, index = i,
        count = (list and list[k]) or derivedCount(total, k, slotsFor(id, total, data, stacks)) }
    end
    return rows
  end
  function Bag.rows(save, data)
    if not save then return {} end
    local order = Bag.order(save, data)
    return Bag.stackRows(save.inventory, order, data, save.bagStacks)
  end
  -- Bag.add (:443-461) over addTo (:383-425): a badge is a plain count; an
  -- item fills its last stack to 99 and opens new ones behind it
  function Bag.add(save, id, qty, data)
    if not save then return false end
    qty = qty or 1
    local inv = save.inventory or {}
    save.inventory = inv
    if isBadge(id) then
      inv[id] = (inv[id] or 0) + qty
      return true
    end
    local order = Bag.order(save, data)
    local stacks = stacksOf(save)
    local old = inv[id]
    if type(old) == "number" and old <= 0 then old = nil end
    -- MG6: the 20-slot cap (Bag.lua:243, :248: no free slot for a new stack
    -- fails the add whole; :231: a non-splitting stack past 99)
    local free = Bag.capacity(data) - Bag.slots(save, data)
    if not splits(id, data) then
      if not old and free <= 0 then return false end
      if (old or 0) + qty > MAX_STACK then return false end
      inv[id] = (old or 0) + qty
      if not old then order[#order + 1] = id end
      keepStacks(save, stacks)
      return true
    end
    local counts = old and countsOf(inv, id, data, stacks) or {}
    local left = qty
    for k = 1, #counts do
      local sum = counts[k] + left
      if sum < MAX_STACK + 1 then
        counts[k] = sum
        left = 0
        break
      end
      counts[k] = MAX_STACK
      left = sum - MAX_STACK
    end
    while left > 0 do
      counts[#counts + 1] = math.min(left, MAX_STACK)
      left = left - MAX_STACK
    end
    local before = #occurrences(order, id)
    if #counts - #(old and countsOf(inv, id, data, stacks) or {}) > free then return false end
    setCounts(inv, id, counts, stacks)
    for _ = before + 1, #counts do order[#order + 1] = id end
    keepStacks(save, stacks)
    return true
  end
  -- Bag.remove(save, id, qty, data, slot) (:464-469; was (save, id, qty)
  -- before v0.3.85) over removeFrom (:427-463): the named stack first (slot
  -- 1 when none or unknown), then the others in order; a stack at zero
  -- loses its order entry; never below zero. `data` decides whether the
  -- item splits (the engine falls back to its module table without it).
  -- Every call is recorded in E.bagRemoves.
  function Bag.remove(save, id, qty, data, slot)
    E.bagRemoves[#E.bagRemoves + 1] = { id = id, qty = qty, data = data, slot = slot,
                                        args = select("#", save, id, qty, data, slot) }
    if not save or not save.inventory then return end
    qty = qty or 1
    local inv, order = save.inventory, save.bagOrder
    local stacks = stacksOf(save)
    local old = inv[id]
    if old ~= nil and type(old) ~= "number" then return end
    if not old or old <= 0 then
      inv[id] = nil
      if order then
        for i = #order, 1, -1 do
          if order[i] == id then table.remove(order, i) end
        end
      end
      keepStacks(save, stacks)
      return
    end
    if isBadge(id) or not splits(id, data) then
      local left = old - qty
      inv[id] = left > 0 and left or nil
      if order and not inv[id] then
        for i = #order, 1, -1 do
          if order[i] == id then table.remove(order, i) end
        end
      end
      keepStacks(save, stacks)
      return
    end
    local counts = countsOf(inv, id, data, stacks)
    local k = (slot and counts[slot]) and slot or 1
    local take = math.min(qty, counts[k])
    counts[k] = counts[k] - take
    qty = qty - take
    for i = 1, #counts do
      if qty <= 0 then break end
      take = math.min(qty, counts[i])
      counts[i] = counts[i] - take
      qty = qty - take
    end
    if order then
      local at = occurrences(order, id)
      for i = #at, 1, -1 do
        if counts[i] ~= nil and counts[i] <= 0 then table.remove(order, at[i]) end
      end
    end
    local kept = {}
    for i = 1, #counts do
      if counts[i] > 0 then kept[#kept + 1] = counts[i] end
    end
    setCounts(inv, id, kept, stacks)
    keepStacks(save, stacks)
  end
  E.modules["src.inventory.Bag"] = Bag
  E.Bag = Bag

  -- FP4: ItemEffects.use for the medicine the tests use (POTION: 20 HP on a
  -- hurt member; ETHER: 10 PP on the picked move; HP_UP: always), the
  -- result word vanillaUseOn reads ("consumed" or "failed")
  local MEDICINE = { POTION = true, ETHER = true, HP_UP = true }
  E.modules["src.inventory.ItemEffects"] = {
    isStone = function(id) return STONES[id] or false end,
    use = function(_data, _save, id, mon, battle, moveIndex)
      if battle or not mon then return "failed" end
      if id == "POTION" then
        local max = mon.stats and mon.stats.hp or mon.maxHp or mon.hp
        if (mon.hp or 0) <= 0 or mon.hp >= max then return "failed" end
        mon.hp = math.min(max, mon.hp + 20)
        return "consumed"
      elseif id == "ETHER" then
        local mv = moveIndex and mon.moves[moveIndex]
        if not mv then return "failed" end
        mv.pp = (mv.pp or 0) + 10
        return "consumed"
      elseif id == "HP_UP" then
        return "consumed"
      end
      return "failed"
    end,
  }

  -- Transition.whiteFlash (src/render/Transition.lua:226-238): a state that
  -- pops itself after `frames` steps
  E.flashes = 0
  E.modules["src.render.Transition"] = {
    whiteFlash = function(_, frames)
      E.flashes = E.flashes + 1
      local s = { isFlash = true, t = 0, frames = frames or 8 }
      function s.update(self)
        self.t = self.t + 1
        if self.t >= self.frames then E.uiPop() end
      end
      return s
    end,
  }

  local function safariHere()
    local def = ow.map and ow.map.def
    return E.save.safari ~= nil and def ~= nil and E.Map.inRegion(def, "SAFARI", "SAFARI_ZONE")
  end
  local function ghostHere()
    local def = ow.map and ow.map.def
    local ghost = def and E.Map.ghostBattles(def)
    return ghost ~= nil and not (ghost.unlessItem and E.save.inventory[ghost.unlessItem])
  end

  local function wildBattle(enc, hooked)
    local info = { kind = "wild", species = enc.species, level = enc.level, hooked = hooked,
                   ghost = (not hooked and ghostHere()) or nil, safari = safariHere() or nil }
    local fake = { kind = "wild" }
    E.nativeBattle(info, function(result) ow:afterBattle(result, fake) end)
  end

  -- ------------------------------------------------------------ the step's encounter roll

  -- OverworldState.rollsIndoorEncounters(def, indoor) (OC:4782-4788, since
  -- v0.3.85; B6): never on an outdoor map -- Map.isOutdoor: the def's own
  -- `outdoor` field (src/mods/Schemas.lua:1938, a mod's route map), else
  -- the OVERWORLD tileset -- then the ROM's rule: a map index before
  -- field.indoorEncounters.firstIndoorMap never rolls, and the excluded
  -- tileset (FOREST) neither. The made-up maps carry no index and the fake
  -- data no indoorEncounters, so their `indoor` flag stands in for that
  -- range (the v0.3.49 fake's rule, OC:4781-4790 then).
  function E.rollsIndoorEncounters(def, indoor)
    if E.Map.isOutdoor(def) then return false end
    if type(indoor) == "table" then
      if def.index ~= nil and def.index < indoor.firstIndoorMap then return false end
      return def.tileset ~= indoor.excludedTileset
    end
    return def.indoor == true
  end

  -- onStepComplete's tail (OC:4790-4797, :4916-4958): the grace steps after
  -- a wild battle, then grass, water while surfing, or any cell of a map
  -- that rolls indoor encounters (:4926-4931); the roll through
  -- encounter.roll and encounter.species; REPEL; the native battle (ghost
  -- and Safari variants)
  function E.afterStep()
    local grace = ow.wildEncounterGraceSteps or 0
    if grace > 0 then
      ow.wildEncounterGraceSteps = grace - 1
      return
    end
    local p, m = ow.player, ow.map
    local terrain
    if m:isGrassCell(p.cellX, p.cellY) then
      terrain = "grass"
    elseif p.surfing and m:isWaterCell(p.cellX, p.cellY) then
      terrain = "water"
    elseif E.rollsIndoorEncounters(m.def, E.data.field and E.data.field.indoorEncounters) then
      terrain = "indoor"
    end
    if not terrain then return end
    local encDef = E.data.encounters and E.data.encounters[m.id] or nil
    local ctx = { mapId = m.id, terrain = terrain }
    local function vanilla(_, c)
      E.rollCalls[c.terrain] = (E.rollCalls[c.terrain] or 0) + 1
      local r = E.rolls[c.terrain]
      return r and { species = r.species, level = r.level } or nil
    end
    local enc
    if E.runtime.wants("encounter.roll") then
      enc = E.runtime.call("encounter.roll", vanilla, encDef, ctx)
    else
      enc = vanilla(encDef, ctx)
    end
    if not enc then return end
    local lead = E.save.party[1]
    if (E.save.repelSteps or 0) > 0 and lead and enc.level < lead.level then return end
    wildBattle(enc, false)
  end

  -- ------------------------------------------------------------ fishing

  -- goFishing (OC:2488-2520): the roll, then the cast box whose
  -- continuation is the verdict (fishVerdict, OC:2522-2544)
  function E.goFishing(rod)
    local pool = E.fishPools[rod]
    local always = pool and pool.always
    local function vanilla(_, _, candidates)
      if always then return { species = always.species, level = always.level } end
      local first = type(candidates) == "table" and candidates[1] or nil
      if not E.fishBites or not first then return nil end
      return { species = first.species, level = first.level }
    end
    local enc
    if E.runtime.wants("encounter.fishing") then
      enc = E.runtime.call("encounter.fishing", vanilla, rod, ow.map.id, pool)
    else
      enc = vanilla(rod, ow.map.id, pool)
    end
    E.fishCasts = (E.fishCasts or 0) + 1
    ow.fishing = { facing = ow.player.facing }
    ow.player.fishing = true
    E.uiPush(E.textBox("CAST THE ROD!", { auto = { delay = 20 }, onDone = function() E.fishVerdict(enc) end }))
  end

  function E.fishVerdict(enc)
    if not enc then
      E.uiPush(E.textBox("NO NIBBLE.", { onDone = function()
        ow.fishing = nil
        ow.player.fishing = nil
      end }))
      return
    end
    E.uiPush(E.textBox("A BITE!", { onDone = function()
      ow.fishing = nil
      ow.player.fishing = nil
      wildBattle(enc, true)
    end }))
  end

  -- the old man's catching demo (Commands.old_man_demo, src/script/Commands.lua:982-990)
  function E.oldManDemo(species, level, onDone)
    E.nativeBattle({ kind = "wild", species = species, level = level, demo = true }, onDone)
  end

  -- ------------------------------------------------------------ items on a party member

  -- BagMenu's list rows (buildItems, src/ui/BagMenu.lua:20-40): one per
  -- stack in bag order (Bag.rows), with its count (none for a key item or
  -- an HM), its `slot` and `stack`, then CANCEL
  local function bagRows()
    local items = {}
    for _, row in ipairs(Bag.rows(E.save, E.data)) do
      local def = E.data.items and E.data.items[row.id] or nil
      local unsellable = (def and def.keyItem) or row.id:find("^HM_") ~= nil
      items[#items + 1] = { value = row.id, label = row.id, count = (not unsellable) and row.count or nil,
                            slot = row.slot, stack = row.count }
    end
    items[#items + 1] = { cancel = true, label = "CANCEL" }
    return items
  end
  function E.bagList()
    return { items = bagRows(), index = 1, scroll = 0, isFakeList = true }
  end

  -- the item-use party menu (PartyMenu with keepOpen): close() pops it when
  -- it is on top (PartyMenu:close, src/ui/PartyMenu.lua:469-476)
  local PartyMenu = E.modules["src.ui.PartyMenu"]
  function E.partyPicker(opts)
    local s = setmetatable({ keepOpen = opts == nil or opts.keepOpen ~= false, picks = 0 }, { __index = PartyMenu })
    s.screenId = "PartyMenu"
    function s.close(self)
      if E.stack:top() == self then E.uiPop() end
      self.closed = true
    end
    return s
  end

  -- consume (BagMenu.lua:42-48): one taken out of the stack under the
  -- cursor when that row is the item's (its `slot`), with the live data;
  -- a row gone resets the cursor. The fake then rebuilds its list's rows
  -- in place (the engine's list is rebuilt by the menu's refresh).
  local function consume(id, list)
    local before = #Bag.rows(E.save, E.data)
    local row = list and list.items and list.items[list.index]
    local slot = row and row.value == id and row.slot or nil
    Bag.remove(E.save, id, 1, E.data, slot)
    if list and list.isFakeList then
      if #Bag.rows(E.save, E.data) < before then list.index, list.scroll = 1, 0 end
      list.items = bagRows()
    end
  end

  -- MoveLearnMenu (src/ui/MoveLearnMenu.lua): A writes the new move over
  -- slot E.forgetSlot (:111-112) and finishes learned; B gives up
  local function moveLearnMenu(mon, moveId, onDone)
    local s = { screenId = "MoveLearnMenu", isMenu = true }
    function s.update(self)
      local input = E.game.input
      if input:wasPressed("a") then
        E.uiPop()
        local md = E.data.moves[moveId]
        mon.moves[E.forgetSlot] = { id = moveId, pp = md and md.pp or 0 }
        onDone(true)
      elseif input:wasPressed("b") then
        E.uiPop()
        onDone(false)
      end
    end
    return s
  end

  -- vanillaUseOn's outcomes for the items the tests use (BagMenu.lua:95-523
  -- over ItemEffects.use): a stone evolves the member, RARE_CANDY levels it
  -- up, a TM/HM teaches its move (the forget menu with four moves; TMs are
  -- used up, HMs stay), anything else has no effect.
  local function vanillaUseOn(game, battle, id, mon, list, moveIndex, picker)
    local def = E.data.items[id] or {}
    local sdef = E.data.pokemon[mon.species] or {}
    local function closePicker()
      if not picker then return end
      picker:close()
      if not battle then E.uiPush(E.modules["src.render.Transition"].whiteFlash(game, 19)) end
    end
    local function failed()
      E.nativeItems[#E.nativeItems + 1] = { kind = "failed", item = id }
      E.uiPush(E.textBox("NO EFFECT.", { onDone = closePicker }))
    end
    if battle then return failed() end
    if MEDICINE[id] then
      if E.modules["src.inventory.ItemEffects"].use(game.data, game.save, id, mon, battle, moveIndex) ~= "consumed" then
        return failed()
      end
      consume(id, list)
      E.nativeItems[#E.nativeItems + 1] = { kind = "healed", item = id }
      closePicker()
      return
    end
    if STONES[id] then
      for _, evo in ipairs(sdef.evolutions or {}) do
        if evo.method == "ITEM" and evo.item == id then
          consume(id, list)
          mon.species = evo.species
          E.nativeItems[#E.nativeItems + 1] = { kind = "evolved", item = id }
          closePicker()
          return
        end
      end
      return failed()
    end
    if id == "RARE_CANDY" then
      if mon.level >= 100 then return failed() end
      mon.level = mon.level + 1
      consume(id, list)
      E.nativeItems[#E.nativeItems + 1] = { kind = "leveled", item = id }
      closePicker()
      return
    end
    local machine = def.machine
    if machine then
      local ok = false
      for _, m in ipairs(sdef.tmhm or {}) do if m == machine.move then ok = true end end
      for _, mv in ipairs(mon.moves) do if mv.id == machine.move then ok = false end end
      if not ok then return failed() end
      local function taught(learned)
        if learned then
          if machine.kind ~= "HM" then consume(id, list) end
          E.nativeItems[#E.nativeItems + 1] = { kind = "learned", item = id }
        else
          E.nativeItems[#E.nativeItems + 1] = { kind = "declined", item = id }
        end
        closePicker()
      end
      if #mon.moves < 4 then
        local md = E.data.moves[machine.move]
        table.insert(mon.moves, { id = machine.move, pp = md and md.pp or 0 })
        taught(true)
      else
        E.uiPush(moveLearnMenu(mon, machine.move, taught))
      end
      return
    end
    return failed()
  end

  -- useOn (BagMenu.lua:525-528) from the item-use party menu: the picker is
  -- on top while the use runs (keepOpen), unless opts.picker says otherwise.
  -- opts: battle, list, picker (false: none), moveIndex (FP4: a PP item's move)
  function E.useItem(id, mon, opts)
    opts = opts or {}
    local list = opts.list or E.bagList()
    local picker = opts.picker
    if picker == nil then
      picker = E.partyPicker()
      E.uiPush(picker)
    elseif picker == false then
      picker = nil
    end
    E.lastBagList = list
    return E.runtime.call("item.use", vanillaUseOn, E.game, opts.battle, id, mon, list, opts.moveIndex, picker)
  end
end

return FakeWild
